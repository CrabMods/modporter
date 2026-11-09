package com.modporter.integration

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path
import java.time.Instant

/** Read-only Restart Manager evidence, collected only when the existing release gate fails. */
internal object WindowsRuntimeLogOwners {
    private interface RestartManager : StdCallLibrary {
        fun RmStartSession(handle: IntByReference, flags: Int, key: CharArray): Int
        fun RmRegisterResources(handle: Int, files: Int, paths: Array<WString>,
            applications: Int, processes: Pointer?, services: Int, names: Pointer?): Int
        fun RmGetList(handle: Int, needed: IntByReference, count: IntByReference,
            processes: Pointer?, rebootReasons: IntByReference): Int
        fun RmEndSession(handle: Int): Int
    }

    fun describe(path: Path): String {
        if (!System.getProperty("os.name").startsWith("Windows")) return "not Windows"
        return runCatching { query(path) }.getOrElse {
            "Restart Manager query failed: ${it::class.java.simpleName}: ${it.message}"
        }
    }

    private fun query(path: Path): String {
        val api = Native.load("Rstrtmgr", RestartManager::class.java)
        val handle = IntByReference()
        val started = api.RmStartSession(handle, 0, CharArray(33))
        if (started != 0) return "RmStartSession=$started"
        try {
            val registered = api.RmRegisterResources(handle.value, 1,
                arrayOf(WString(path.toAbsolutePath().normalize().toString())), 0, null, 0, null)
            if (registered != 0) return "RmRegisterResources=$registered"
            val needed = IntByReference()
            val count = IntByReference()
            val reasons = IntByReference()
            var result = api.RmGetList(handle.value, needed, count, null, reasons)
            if (result == 0) return "none; currentPid=${ProcessHandle.current().pid()}"
            if (result != 234) return "RmGetList=$result"
            repeat(3) {
                val capacity = needed.value
                if (capacity !in 1..1024) return "unexpected owner count=$capacity"
                // RM_PROCESS_INFO contains no pointers: DWORD pid + FILETIME +
                // WCHAR app[256] + WCHAR service[64] + four 32-bit fields = 668 bytes.
                Memory(capacity * 668L).use { memory ->
                    count.value = capacity
                    result = api.RmGetList(handle.value, needed, count, memory, reasons)
                    if (result == 0) {
                        val owners = (0 until count.value).map { index ->
                            val at = index * 668L
                            val pid = memory.getInt(at)
                            val low = memory.getInt(at + 4).toLong() and 0xffffffffL
                            val high = memory.getInt(at + 8).toLong() and 0xffffffffL
                            val ticks = (high shl 32) or low
                            val start = Instant.ofEpochSecond(ticks / 10_000_000L - 11_644_473_600L,
                                ticks % 10_000_000L * 100L)
                            "pid=$pid,start=$start,app=${memory.getWideString(at + 12)}"
                        }
                        return "currentPid=${ProcessHandle.current().pid()}; owners=$owners"
                    }
                    if (result != 234) return "RmGetList=$result"
                }
            }
            return "owner list changed during query; needed=${needed.value}"
        } finally {
            api.RmEndSession(handle.value)
        }
    }
}
