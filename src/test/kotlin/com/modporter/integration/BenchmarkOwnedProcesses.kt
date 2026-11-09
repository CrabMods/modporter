package com.modporter.integration

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** How long a terminated owned tree may take to leave its job and signal its parent handle. */
private const val TEARDOWN_SECONDS = 20L

/** Each owned Windows command gets an unnamed job before its first instruction executes. */
internal object BenchmarkOwnedProcesses {
    data class Capture(val finished: Boolean, val exitCode: Int?, val output: String, val pid: Long)

    fun start(builder: ProcessBuilder): Process =
        if (System.getProperty("os.name").startsWith("Windows")) WindowsJobProcess.start(builder)
        else builder.start()

    fun <T> use(builder: ProcessBuilder, body: (Process) -> T): T {
        val process = start(builder)
        var failure: Throwable? = null
        try {
            return body(process)
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            try {
                if (process is WindowsJobProcess) process.close()
                else {
                    if (process.isAlive) process.destroyForcibly()
                    runCatching { process.outputStream.close() }
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    fun finish(process: Process) {
        if (process is WindowsJobProcess) process.finishOwnedTree()
    }

    /** File capture cannot wait for EOF from a detached descendant's inherited pipe writer. */
    fun capture(command: List<String>, workingDir: Path, timeoutSeconds: Long): Capture {
        val outputFile = Files.createTempFile("modporter-owned-command-", ".log")
        try {
            val builder = ProcessBuilder(command).directory(workingDir.toFile())
                .redirectErrorStream(true).redirectOutput(outputFile.toFile())
            OwnedJavaProcessEnvironment.configure(builder)
            return use(builder) { process ->
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                val exitCode = if (finished) process.exitValue() else null
                if (!finished) process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                finish(process)
                Capture(finished, exitCode, Files.readString(outputFile), process.pid())
            }
        } finally {
            Files.deleteIfExists(outputFile)
        }
    }
}

@Structure.FieldOrder("length", "descriptor", "inherit")
internal class BenchmarkSecurityAttributes : Structure() {
    @JvmField var length = 0
    @JvmField var descriptor: Pointer? = null
    @JvmField var inherit = 1
}

@Structure.FieldOrder("cb", "reserved", "desktop", "title", "x", "y", "width", "height",
    "xChars", "yChars", "fill", "flags", "show", "reservedSize", "reservedBytes", "stdin", "stdout", "stderr")
internal class BenchmarkStartupInfo : Structure() {
    @JvmField var cb = 0
    @JvmField var reserved: Pointer? = null
    @JvmField var desktop: Pointer? = null
    @JvmField var title: Pointer? = null
    @JvmField var x = 0
    @JvmField var y = 0
    @JvmField var width = 0
    @JvmField var height = 0
    @JvmField var xChars = 0
    @JvmField var yChars = 0
    @JvmField var fill = 0
    @JvmField var flags = 0x101 // STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW
    @JvmField var show: Short = 0
    @JvmField var reservedSize: Short = 0
    @JvmField var reservedBytes: Pointer? = null
    @JvmField var stdin: Pointer? = null
    @JvmField var stdout: Pointer? = null
    @JvmField var stderr: Pointer? = null
}

@Structure.FieldOrder("startup", "attributes")
internal class BenchmarkStartupInfoEx : Structure() {
    @JvmField var startup = BenchmarkStartupInfo()
    @JvmField var attributes: Pointer? = null
}

@Structure.FieldOrder("process", "thread", "pid", "threadId")
internal class BenchmarkProcessInformation : Structure() {
    @JvmField var process: Pointer? = null
    @JvmField var thread: Pointer? = null
    @JvmField var pid = 0
    @JvmField var threadId = 0
}

internal class WindowsJobProcess private constructor(
    private val api: Api,
    private val job: Pointer,
    private val process: Pointer,
    private val input: NativeInput,
    private val processId: Long,
    private val processHandle: ProcessHandle
) : Process(), AutoCloseable {
    private var closed = false
    private var savedExit: Int? = null

    override fun pid(): Long = processId
    override fun toHandle(): ProcessHandle = processHandle
    override fun supportsNormalTermination(): Boolean = false
    override fun getOutputStream(): OutputStream = input
    override fun getInputStream(): InputStream = InputStream.nullInputStream()
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun isAlive(): Boolean = !closed && api.WaitForSingleObject(process, 0) == 258

    override fun exitValue(): Int {
        savedExit?.let { return it }
        if (isAlive) throw IllegalThreadStateException("Owned process $processId is still alive")
        val value = IntByReference()
        checkWin(api.GetExitCodeProcess(process, value), "GetExitCodeProcess")
        return value.value.also { savedExit = it }
    }

    override fun waitFor(): Int {
        while (!waitFor(1, TimeUnit.SECONDS)) Unit
        return exitValue()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        if (closed) return true
        val deadline = System.nanoTime() + unit.toNanos(timeout).coerceAtLeast(0)
        do {
            if (Thread.interrupted()) throw InterruptedException()
            val remaining = deadline - System.nanoTime()
            val millis = TimeUnit.NANOSECONDS.toMillis(remaining.coerceAtLeast(0)).coerceAtMost(200).toInt()
            when (api.WaitForSingleObject(process, millis)) {
                0 -> { exitValue(); return true }
                258 -> Unit
                else -> throw IOException("WaitForSingleObject failed: ${Native.getLastError()}")
            }
        } while (System.nanoTime() < deadline)
        return !isAlive
    }

    override fun destroy() {
        if (!closed) checkWin(api.TerminateJobObject(job, 1), "TerminateJobObject")
    }

    override fun destroyForcibly(): Process = apply { destroy() }

    private fun activeCount(): Int = Memory(48).use { accounting ->
        checkWin(api.QueryInformationJobObject(job, 1, accounting, 48, null), "QueryInformationJobObject")
        accounting.getInt(40)
    }

    fun finishOwnedTree() {
        if (closed) return
        input.close()
        if (activeCount() != 0) destroy()
        // Kernel-side teardown of a terminated JVM (heap unmapping, handle closure) runs at the
        // process's own priority; on a saturated machine it has outlasted a 2 s window with the
        // whole tree already dead (benchmark runs on 2026-11-06). The bound still refuses a tree
        // that survives, which is what it exists to catch.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEARDOWN_SECONDS)
        while (activeCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
        val survivors = activeCount()
        if (survivors != 0) {
            throw IOException("Owned job did not empty within ${TEARDOWN_SECONDS}s; parentPid=$processId; active=$survivors")
        }
        if (!waitFor(TEARDOWN_SECONDS, TimeUnit.SECONDS)) throw IOException("Owned parent did not exit; pid=$processId")
    }

    override fun close() {
        if (closed) return
        try {
            finishOwnedTree()
        } finally {
            runCatching { input.close() }
            api.CloseHandle(job) // KILL_ON_JOB_CLOSE also protects exceptional exits.
            api.CloseHandle(process)
            closed = true
        }
    }

    private class NativeInput(private val api: Api, private val handle: Pointer) : OutputStream() {
        private var closed = false
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (closed) throw IOException("Owned stdin is closed")
            var consumed = 0
            while (consumed < length) {
                val part = bytes.copyOfRange(offset + consumed, offset + length)
                val written = IntByReference()
                checkWin(api.WriteFile(handle, part, part.size, written, null), "WriteFile(stdin)")
                if (written.value <= 0) throw IOException("Owned stdin made no progress")
                consumed += written.value
            }
        }
        @Synchronized override fun close() {
            if (!closed) { api.CloseHandle(handle); closed = true }
        }
    }

    private interface Api : StdCallLibrary {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): Pointer?
        fun SetInformationJobObject(job: Pointer, kind: Int, information: Pointer, size: Int): Boolean
        fun QueryInformationJobObject(job: Pointer, kind: Int, information: Pointer, size: Int, returned: IntByReference?): Boolean
        fun TerminateJobObject(job: Pointer, code: Int): Boolean
        fun CreatePipe(read: PointerByReference, write: PointerByReference, attributes: BenchmarkSecurityAttributes, size: Int): Boolean
        fun SetHandleInformation(handle: Pointer, mask: Int, flags: Int): Boolean
        fun CreateFileW(path: WString, access: Int, sharing: Int, attributes: BenchmarkSecurityAttributes,
            disposition: Int, flags: Int, template: Pointer?): Pointer?
        fun InitializeProcThreadAttributeList(list: Pointer?, count: Int, flags: Int, size: LongByReference): Boolean
        fun UpdateProcThreadAttribute(list: Pointer, flags: Int, attribute: Long, value: Pointer,
            size: Long, previous: Pointer?, returned: Pointer?): Boolean
        fun DeleteProcThreadAttributeList(list: Pointer)
        fun CreateProcessW(application: WString?, command: CharArray, processAttributes: Pointer?,
            threadAttributes: Pointer?, inherit: Boolean, flags: Int, environment: Pointer,
            directory: WString, startup: Pointer, information: BenchmarkProcessInformation): Boolean
        fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
        fun ResumeThread(thread: Pointer): Int
        fun WaitForSingleObject(handle: Pointer, timeout: Int): Int
        fun GetExitCodeProcess(handle: Pointer, code: IntByReference): Boolean
        fun WriteFile(handle: Pointer, bytes: ByteArray, size: Int, written: IntByReference, overlapped: Pointer?): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    companion object {
        private fun checkWin(success: Boolean, operation: String) {
            if (!success) throw IOException("$operation failed: ${Native.getLastError()}")
        }

        private fun valid(handle: Pointer?): Pointer = handle?.takeUnless { Pointer.nativeValue(it) == -1L }
            ?: throw IOException("Windows handle creation failed: ${Native.getLastError()}")

        private fun executable(command: String, directory: Path, environment: Map<String, String>): Path {
            val declared = Path.of(command)
            if (declared.isAbsolute || command.contains('\\') || command.contains('/')) {
                val path = if (declared.isAbsolute) declared else directory.resolve(declared)
                if (Files.isRegularFile(path)) return path.toAbsolutePath().normalize()
            } else {
                val name = if (command.endsWith(".exe", true)) command else "$command.exe"
                val search = environment.entries.firstOrNull { it.key.equals("PATH", true) }?.value.orEmpty()
                for (part in search.split(';')) {
                    if (part.isBlank()) continue
                    val candidate = Path.of(part.trim().trim('"')).resolve(name)
                    if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize()
                    // a Store app's execution alias (WindowsApps) is listed by its directory but cannot
                    // be opened as a file; CreateProcessW starts it like any executable
                    if (listedEntry(candidate)) return candidate.toAbsolutePath().normalize()
                }
            }
            throw IOException("Owned executable not found: $command")
        }

        private fun listedEntry(candidate: Path): Boolean {
            val directory = candidate.parent ?: return false
            if (!Files.isDirectory(directory)) return false
            return runCatching {
                Files.newDirectoryStream(directory).use { entries ->
                    entries.any { it.fileName.toString().equals(candidate.fileName.toString(), ignoreCase = true) }
                }
            }.getOrDefault(false)
        }

        private fun quote(argument: String): String {
            if (argument.isNotEmpty() && argument.none { it.isWhitespace() || it == '"' }) return argument
            return buildString {
                append('"')
                var slashes = 0
                for (character in argument) {
                    when (character) {
                        '\\' -> slashes++
                        '"' -> { repeat(slashes * 2 + 1) { append('\\') }; append('"'); slashes = 0 }
                        else -> { repeat(slashes) { append('\\') }; slashes = 0; append(character) }
                    }
                }
                repeat(slashes * 2) { append('\\') }
                append('"')
            }
        }

        fun start(builder: ProcessBuilder): WindowsJobProcess =
            synchronized(Class.forName("java.lang.ProcessImpl")) { startLocked(builder) }

        // Use the JDK's Windows creation monitor while our inheritable handles exist, too.
        // This prevents a concurrent ordinary ProcessBuilder launch from inheriting them.
        private fun startLocked(builder: ProcessBuilder): WindowsJobProcess {
            check(Native.POINTER_SIZE == 8) { "Owned Minecraft tests require 64-bit Windows" }
            require(builder.redirectErrorStream() && builder.redirectOutput().type() == ProcessBuilder.Redirect.Type.WRITE) {
                "Owned Windows commands require merged file output"
            }
            require(builder.redirectInput() == ProcessBuilder.Redirect.PIPE) { "Owned Windows stdin must be a pipe" }
            val directory = (builder.directory()?.toPath() ?: Path.of("")).toAbsolutePath().normalize()
            val command = builder.command().toList()
            require(command.isNotEmpty() && command.none { '\u0000' in it })
            val target = executable(command.first(), directory, builder.environment())
            val batch = target.toString().endsWith(".bat", true) || target.toString().endsWith(".cmd", true)
            val application = if (batch) executable("cmd.exe", directory, builder.environment()) else target
            val commandLine = if (batch) {
                val tokens = listOf(target.toString()) + command.drop(1)
                require(tokens.none { token -> token.any { it == '"' || it == '%' || it == '\r' || it == '\n' } }) {
                    "Batch arguments requiring quote or percent expansion are not supported"
                }
                val body = tokens.joinToString(" ") { "\"$it\"" }
                // /s strips only this outer quote pair; the script path and every argument
                // stay independently quoted. /d and /v:off avoid user AutoRun/delayed expansion.
                quote(application.toString()) + " /d /v:off /s /c \"" + body + "\""
            } else {
                (listOf(application.toString()) + command.drop(1)).joinToString(" ", transform = ::quote)
            }
            require(commandLine.length < 32767) { "Owned command line exceeds Windows limit" }
            val environment = builder.environment().entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                .joinToString("\u0000") { "${it.key}=${it.value}" } + "\u0000\u0000"
            val environmentBytes = environment.toByteArray(Charsets.UTF_16LE)
            // a Store (MSIX) app, through its execution alias (which reads as no file at all) or its package
            // image under WindowsApps, is started from the command line, as ProcessBuilder does; activation
            // refuses an explicit image and a creation-time job list, so the suspended process joins the job
            // before it runs
            val alias = !Files.isRegularFile(application) ||
                application.any { it.toString().equals("WindowsApps", ignoreCase = true) }
            val api = Native.load("kernel32", Api::class.java)
            var job: Pointer? = null
            var stdinRead: Pointer? = null
            var stdinWrite: Pointer? = null
            var stdout: Pointer? = null
            val information = BenchmarkProcessInformation()
            var committed = false
            try {
                job = valid(api.CreateJobObjectW(null, null))
                Memory(144).use { limits ->
                    limits.clear()
                    limits.setInt(16, 0x2000) // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                    checkWin(api.SetInformationJobObject(valid(job), 9, limits, 144), "SetInformationJobObject")
                }
                val security = BenchmarkSecurityAttributes().also { it.length = it.size(); it.write() }
                check(security.size() == 24 && information.size() == 24) { "Unexpected Windows ABI" }
                val read = PointerByReference()
                val write = PointerByReference()
                checkWin(api.CreatePipe(read, write, security, 0), "CreatePipe")
                stdinRead = valid(read.value)
                stdinWrite = valid(write.value)
                checkWin(api.SetHandleInformation(valid(stdinWrite), 1, 0), "SetHandleInformation")
                stdout = valid(api.CreateFileW(WString(builder.redirectOutput().file().absolutePath),
                    0x40000000, 7, security, 2, 0x80, null))
                val size = LongByReference()
                api.InitializeProcThreadAttributeList(null, 2, 0, size)
                check(size.value in 1..1_048_576) { "Invalid process attribute size" }
                Memory(size.value).use { attributes ->
                    checkWin(api.InitializeProcThreadAttributeList(attributes, 2, 0, size), "InitializeProcThreadAttributeList")
                    try {
                        Memory(16).use { inherited ->
                            Memory(8).use { jobs ->
                                Memory(environmentBytes.size.toLong()).use { env ->
                                    inherited.setPointer(0, stdinRead)
                                    inherited.setPointer(8, stdout)
                                    jobs.setPointer(0, job)
                                    env.write(0, environmentBytes, 0, environmentBytes.size)
                                    checkWin(api.UpdateProcThreadAttribute(attributes, 0, 0x20002, inherited, 16, null, null), "Set inherited handle list")
                                    if (!alias) {
                                        checkWin(api.UpdateProcThreadAttribute(attributes, 0, 0x2000D, jobs, 8, null, null), "Set owned job list")
                                    }
                                    val startup = BenchmarkStartupInfoEx()
                                    startup.startup.cb = startup.size()
                                    startup.startup.stdin = stdinRead
                                    startup.startup.stdout = stdout
                                    startup.startup.stderr = stdout
                                    startup.attributes = attributes
                                    check(startup.size() == 112) { "Unexpected STARTUPINFOEX layout" }
                                    startup.write()
                                    checkWin(api.CreateProcessW(if (alias) null else WString(application.toString()),
                                        (commandLine + '\u0000').toCharArray(), null, null, true,
                                        0x08000000 or 0x00080000 or 0x00000400 or 0x00000004,
                                        env, WString(directory.toString()), startup.pointer, information), "CreateProcessW")
                                }
                            }
                        }
                    } finally {
                        api.DeleteProcThreadAttributeList(attributes)
                    }
                }
                information.read()
                if (alias) {
                    checkWin(api.AssignProcessToJobObject(valid(job), valid(information.process)), "AssignProcessToJobObject")
                }
                val pid = information.pid.toLong() and 0xffffffffL
                val handle = ProcessHandle.of(pid).orElseThrow { IOException("Owned suspended process disappeared: $pid") }
                val result = WindowsJobProcess(api, valid(job), valid(information.process), NativeInput(api, valid(stdinWrite)), pid, handle)
                if (api.ResumeThread(valid(information.thread)) == -1) throw IOException("ResumeThread failed: ${Native.getLastError()}")
                committed = true
                return result
            } finally {
                information.thread?.let { api.CloseHandle(it) }
                stdinRead?.let { api.CloseHandle(it) }
                stdout?.let { api.CloseHandle(it) }
                if (!committed) {
                    job?.let { api.TerminateJobObject(it, 1); api.CloseHandle(it) }
                    stdinWrite?.let { api.CloseHandle(it) }
                    information.process?.let { api.CloseHandle(it) }
                }
            }
        }
    }
}
