package com.modporter.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BenchmarkOwnedProcessesTest {
    @Test
    fun `owned batch launch preserves spaced paths arguments and real exit code`(@TempDir root: Path) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = root.resolve("batch with spaces & !").createDirectories()
        val script = directory.resolve("probe.cmd")
        script.writeText("@echo off\r\necho FIRST=%1\r\necho SECOND=%2\r\necho EMPTY=%3\r\nexit /b 7\r\n")
        val result = BenchmarkOwnedProcesses.capture(listOf(script.toString(), "two words", "a&b", ""), directory, 10)
        assertTrue(result.finished, result.toString())
        assertEquals(7, result.exitCode, result.output)
        assertTrue(result.output.contains("FIRST=\"two words\""), result.output)
        assertTrue(result.output.contains("SECOND=\"a&b\""), result.output)
        assertTrue(result.output.contains("EMPTY=\"\""), result.output)
    }

    @Test
    fun `owned launch starts a PATH command that is a Windows app execution alias`(@TempDir root: Path) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val alias = System.getenv("PATH").orEmpty().split(';').filter { it.isNotBlank() }
            .map { Path.of(it.trim('"')).resolve("pwsh.exe") }
            .firstOrNull { java.nio.file.Files.isDirectory(it.parent) && !java.nio.file.Files.isRegularFile(it) &&
                java.nio.file.Files.newDirectoryStream(it.parent).use { entries -> entries.any { e -> e.fileName.toString().equals("pwsh.exe", true) } } }
        assumeTrue(alias != null, "no pwsh execution alias on PATH")
        val result = BenchmarkOwnedProcesses.capture(listOf("pwsh", "-NoProfile", "-Command", "exit 3"), root, 30)
        assertTrue(result.finished, result.toString())
        assertEquals(3, result.exitCode, result.output)
    }

    @Test
    fun `owned launch starts a Store package image under WindowsApps`(@TempDir root: Path) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val probe = runCatching {
            ProcessBuilder("pwsh", "-NoProfile", "-Command", "[Console]::Out.Write(\$PSHOME)").redirectErrorStream(true).start()
        }.getOrNull()
        assumeTrue(probe != null, "pwsh is not available")
        val home = probe!!.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
        probe.waitFor(30, TimeUnit.SECONDS)
        val image = Path.of(home).resolve("pwsh.exe")
        assumeTrue(home.contains("WindowsApps", ignoreCase = true) && java.nio.file.Files.isRegularFile(image),
            "pwsh is not a Store package here")
        val result = BenchmarkOwnedProcesses.capture(listOf(image.toString(), "-NoProfile", "-Command", "exit 4"), root, 30)
        assertTrue(result.finished, result.toString())
        assertEquals(4, result.exitCode, result.output)
    }

    @Test
    fun `closing one owned job preserves another live command`(@TempDir root: Path) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        fun command(log: String) = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
            "-Command", "Start-Sleep -Seconds 20").directory(root.toFile())
            .redirectErrorStream(true).redirectOutput(root.resolve(log).toFile())

        BenchmarkOwnedProcesses.use(command("control.log")) { control ->
            BenchmarkOwnedProcesses.use(command("target.log")) { target ->
                assertTrue(control.isAlive && target.isAlive)
                target.destroyForcibly()
                assertTrue(target.waitFor(2, TimeUnit.SECONDS))
                BenchmarkOwnedProcesses.finish(target)
                assertTrue(control.isAlive, "Closing a target job must preserve another command's process")
            }
        }
    }

    @Test
    fun `owned native launch preserves UTF8 argv environment and stdin`(@TempDir root: Path) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = root.resolve("probe with spaces µ中文").createDirectories()
        val source = directory.resolve("encoding-probe.ps1")
        val script = """
            param([string]${'$'}Literal)
            [Console]::OutputEncoding = [Text.UTF8Encoding]::new(${'$'}false)
            [Console]::WriteLine('OUT:µ中文|' + ${'$'}Literal)
            [Console]::Error.WriteLine('ERR:µ中文')
            [Console]::WriteLine('ENV:' + ${'$'}env:MODPORTER_ENCODING_PROBE)
            [Console]::WriteLine('IN:' + [Console]::In.ReadLine())
        """.trimIndent()
        source.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + script.toByteArray(Charsets.UTF_16LE))
        val argument = "white space µ中文 \\\"quoted\" tail\\"
        val log = root.resolve("encoding.log")
        val builder = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-File", source.toString(), argument)
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        builder.environment()["MODPORTER_ENCODING_PROBE"] = "µ中文"
        OwnedJavaProcessEnvironment.configure(builder)
        BenchmarkOwnedProcesses.use(builder) { process ->
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write("stop\n") }
            val exited = process.waitFor(30, TimeUnit.SECONDS)
            assertTrue(exited, if (exited) null else
                "PowerShell encoding probe did not exit within 30 seconds: " +
                    StrictUtf8RuntimeLog.decode(log))
            BenchmarkOwnedProcesses.finish(process)
            val output = StrictUtf8RuntimeLog.decode(log)
            assertEquals(0, process.exitValue(), output.toString())
        }
        val decoded = assertIs<StrictUtf8RuntimeLog.DecodeResult.Success>(StrictUtf8RuntimeLog.decode(log))
        assertTrue(decoded.text.contains("OUT:µ中文|$argument"), decoded.text)
        assertTrue(decoded.text.contains("ERR:µ中文"), decoded.text)
        assertTrue(decoded.text.contains("ENV:µ中文"), decoded.text)
        assertTrue(decoded.text.contains("IN:stop"), decoded.text)
    }
}
