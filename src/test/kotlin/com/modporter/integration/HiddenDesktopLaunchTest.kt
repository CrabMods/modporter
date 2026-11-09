package com.modporter.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The benchmark's game runtimes must run invisibly: their launcher puts them on a private desktop. */
class HiddenDesktopLaunchTest {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    @Test
    fun `nested runtime launcher routes the command through the hidden desktop helper`(@TempDir tempDir: Path) {
        assumeTrue(isWindows)
        val command = MinecraftRuntimeLease.guard(tempDir, listOf("cmd.exe", "/c", "gradlew.bat", "runClient", "--no-daemon"), "client")
        val host = Path.of(command[command.indexOf("-File") + 1]).readText()
        val launcher = Path.of(command[command.indexOf("-Executable") + 1]).readText()
        assertTrue(command.contains("-ManagedRunner"), command.toString())
        assertTrue(host.contains("[Console]::OutputEncoding = \$utf8"), host)
        assertTrue(host.contains("\$global:OutputEncoding = \$utf8"), host)
        assertTrue(host.contains("& \$ManagedRunner @managedParameters"), host)
        assertTrue(launcher.contains("hidden-desktop-"), launcher)
        assertTrue(launcher.contains("-Executable \$modporterExecutable -ArgumentList \$modporterArguments -DesktopName \"modporter-hidden-\$PID\""), launcher)
        assertTrue(!launcher.contains("& \$modporterExecutable @modporterArguments"), launcher)
    }

    @Test
    fun `outer managed PowerShell host emits strict UTF8 bytes and preserves exit code`(@TempDir tempDir: Path) {
        assumeTrue(isWindows)
        val host = tempDir.resolve("managed_runner_utf8_host.ps1")
        host.writeBytes(
            checkNotNull(
                javaClass.getResourceAsStream(
                    MinecraftRuntimeLease.MANAGED_RUNNER_UTF8_HOST_RESOURCE
                )
            ).use { it.readBytes() }
        )
        val managedRunner = tempDir.resolve("managed-probe.ps1")
        managedRunner.writeText(
            """
            [CmdletBinding()]
            param(
                [Parameter(Mandatory = ${'$'}true)][string]${'$'}ProjectRoot,
                [Parameter(Mandatory = ${'$'}true)][string]${'$'}Executable,
                [string]${'$'}RuntimeKind
            )
            [Console]::WriteLine('OUT … µ中文')
            [Console]::Error.WriteLine('ERR … µ中文')
            exit 7
            """.trimIndent() + "\n"
        )
        val log = tempDir.resolve("managed-host-utf8.log")
        val builder = ProcessBuilder(
            "pwsh", "-NoProfile", "-File", host.toString(),
            "-ManagedRunner", managedRunner.toString(),
            "-ProjectRoot", tempDir.toString(),
            "-Executable", "probe.exe",
            "-RuntimeKind", "server"
        ).directory(tempDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())

        BenchmarkOwnedProcesses.use(builder) { process ->
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "managed UTF-8 host did not finish")
            BenchmarkOwnedProcesses.finish(process)
            val decoded = assertIs<StrictUtf8RuntimeLog.DecodeResult.Success>(
                StrictUtf8RuntimeLog.decode(log)
            )
            assertEquals(7, process.exitValue(), decoded.text)
            assertTrue(decoded.text.contains("OUT … µ中文"), decoded.text)
            assertTrue(decoded.text.contains("ERR … µ中文"), decoded.text)
            val bytes = log.readBytes()
            assertTrue(
                bytes.asList().windowed(3).any {
                    it == listOf(0xe2.toByte(), 0x80.toByte(), 0xa6.toByte())
                },
                "UTF-8 ellipsis bytes are missing: ${bytes.joinToString(" ") { "%02x".format(it) }}"
            )
            assertTrue(
                bytes.asList().windowed(2).none {
                    it == listOf(0xa1.toByte(), 0xad.toByte())
                },
                "GBK ellipsis bytes remain in managed host output"
            )
        }
    }

    @Test
    fun `terminating managed runner errors retain UTF8 diagnostics and failure exit`(@TempDir tempDir: Path) {
        assumeTrue(isWindows)
        val host = tempDir.resolve("managed_runner_utf8_host.ps1")
        host.writeBytes(checkNotNull(javaClass.getResourceAsStream(
            MinecraftRuntimeLease.MANAGED_RUNNER_UTF8_HOST_RESOURCE
        )).use { it.readBytes() })
        val runner = tempDir.resolve("managed-error.ps1")
        runner.writeText("""
            [CmdletBinding()]
            param([string]${'$'}ProjectRoot, [string]${'$'}Executable, [string]${'$'}RuntimeKind)
            throw 'Native receipt failed … µ中文'
        """.trimIndent() + "\n")
        val log = tempDir.resolve("managed-error.log")
        val builder = ProcessBuilder(
            "pwsh", "-NoProfile", "-File", host.toString(),
            "-ManagedRunner", runner.toString(), "-ProjectRoot", tempDir.toString(),
            "-Executable", "probe.exe", "-RuntimeKind", "server"
        ).directory(tempDir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        BenchmarkOwnedProcesses.use(builder) { process ->
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "managed error host did not finish")
            BenchmarkOwnedProcesses.finish(process)
            val decoded = assertIs<StrictUtf8RuntimeLog.DecodeResult.Success>(StrictUtf8RuntimeLog.decode(log))
            assertTrue(process.exitValue() != 0, "terminating managed error lost its failure exit")
            assertTrue(decoded.text.contains("Native receipt failed … µ中文"), decoded.text)
        }
    }

    @Test
    fun `helper starts children on the named hidden desktop and keeps their output and exit code`(@TempDir tempDir: Path) {
        assumeTrue(isWindows)
        val helper = tempDir.resolve("hidden_desktop.ps1")
        helper.writeBytes(checkNotNull(javaClass.getResourceAsStream(MinecraftRuntimeLease.HIDDEN_DESKTOP_RESOURCE)).use { it.readBytes() })
        val log = tempDir.resolve("probe.log")
        val processBuilder = ProcessBuilder(
            "pwsh", "-NoProfile", "-Command",
            "& '$helper' -Executable pwsh -ArgumentList @('-NoProfile','-File','$helper','-Probe') -DesktopName modporter-hidden-test; exit \$LASTEXITCODE"
        ).redirectErrorStream(true).redirectOutput(log.toFile())
        BenchmarkOwnedProcesses.use(processBuilder) { process ->
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "helper did not finish")
            BenchmarkOwnedProcesses.finish(process)
            assertEquals(0, process.exitValue(), log.readText())
            assertTrue(log.readText().contains("desktop=modporter-hidden-test"), log.readText())
        }

        val exitBuilder = ProcessBuilder(
            "pwsh", "-NoProfile", "-Command",
            "& '$helper' -Executable cmd.exe -ArgumentList @('/c','exit 7') -DesktopName modporter-hidden-test; exit \$LASTEXITCODE"
        ).redirectErrorStream(true).redirectOutput(tempDir.resolve("exit.log").toFile())
        BenchmarkOwnedProcesses.use(exitBuilder) { exit ->
            assertTrue(exit.waitFor(120, TimeUnit.SECONDS))
            BenchmarkOwnedProcesses.finish(exit)
            assertEquals(7, exit.exitValue())
        }
    }

    @Test
    fun `leased hidden Gradle JDK21 chain emits strict UTF8 stdout and stderr`(@TempDir tempDir: Path) {
        assumeTrue(isWindows)
        val java21 = checkNotNull(java21Executable()) { "JDK 21 executable is required for the native runtime probe" }
        val java21ForGradle = java21.toString().replace("\\", "/").replace("'", "\\'")
        tempDir.resolve("settings.gradle").writeText("rootProject.name = 'utf8-runtime-probe'\n")
        tempDir.resolve("build.gradle").writeText(
            """
            tasks.register('utf8RuntimeProbe', Exec) {
                doFirst {
                    logger.lifecycle('GRADLE µ中文')
                }
                commandLine '$java21ForGradle',
                    '-Dfile.encoding=GBK', 'Utf8Probe.java'
            }
            """.trimIndent() + "\n"
        )
        tempDir.resolve("Utf8Probe.java").writeText(
            """
            public class Utf8Probe {
                public static void main(String[] args) {
                    String sample = new String(new int[] {0x00B5, 0x4E2D, 0x6587}, 0, 3);
                    System.out.println("OUT " + sample);
                    System.err.println("ERR " + sample);
                    System.out.println("java.version=" + System.getProperty("java.version"));
                    System.out.println("file.encoding=" + System.getProperty("file.encoding"));
                    System.out.println("stdout.encoding=" + System.getProperty("sun.stdout.encoding"));
                    System.out.println("stderr.encoding=" + System.getProperty("sun.stderr.encoding"));
                }
            }
            """.trimIndent() + "\n"
        )

        val repositoryRoot = Path.of("").toAbsolutePath().normalize()
        val guarded = MinecraftRuntimeLease.guard(
            tempDir,
            listOf(
                repositoryRoot.resolve("gradlew.bat").toString(),
                "utf8RuntimeProbe",
                "--no-daemon",
                "--console=plain"
            ),
            "server"
        )
        val log = tempDir.resolve("utf8-runtime-probe.log")
        val processBuilder = ProcessBuilder(guarded)
            .directory(tempDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
        OwnedJavaProcessEnvironment.configure(processBuilder)

        BenchmarkOwnedProcesses.use(processBuilder) { process ->
            assertTrue(process.waitFor(240, TimeUnit.SECONDS), "leased UTF-8 runtime probe did not finish")
            BenchmarkOwnedProcesses.finish(process)
            val decoded = assertIs<StrictUtf8RuntimeLog.DecodeResult.Success>(
                StrictUtf8RuntimeLog.decode(log)
            )
            assertEquals(0, process.exitValue(), decoded.text)
            assertTrue(decoded.text.contains("GRADLE µ中文"), decoded.text)
            assertTrue(decoded.text.contains("OUT µ中文"), decoded.text)
            assertTrue(decoded.text.contains("ERR µ中文"), decoded.text)
            assertTrue(decoded.text.contains("java.version=21."), decoded.text)
            assertTrue(decoded.text.contains("file.encoding=GBK"), decoded.text)
            assertTrue(decoded.text.contains("stdout.encoding=UTF-8"), decoded.text)
            assertTrue(decoded.text.contains("stderr.encoding=UTF-8"), decoded.text)
        }
    }

    private fun java21Executable(): Path? {
        val executable = "java.exe"
        val direct = System.getenv("MODPORTER_BENCHMARK_JAVA21")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?.toAbsolutePath()
            ?.normalize()
        if (direct != null && Files.isRegularFile(direct)) return direct

        val home = listOf("MODPORTER_BENCHMARK_JAVA21_HOME", "JAVA21_HOME", "JDK21_HOME")
            .asSequence()
            .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
            .map { Path.of(it) }
            .map { it.toAbsolutePath().normalize().resolve("bin").resolve(executable) }
            .firstOrNull { Files.isRegularFile(it) }
        if (home != null) return home

        return listOf(
            Path.of("C:/Program Files/Java"),
            Path.of("C:/Program Files/Eclipse Adoptium"),
            Path.of(System.getProperty("user.home"), ".jdks")
        ).asSequence()
            .filter(Files::isDirectory)
            .flatMap { root ->
                Files.list(root).use { stream ->
                    stream.filter(Files::isDirectory)
                        .filter { it.fileName.toString().contains("21") }
                        .map { it.resolve("bin").resolve(executable) }
                        .toList()
                        .asSequence()
                }
            }
            .firstOrNull { Files.isRegularFile(it) }
    }
}
