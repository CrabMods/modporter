package com.modporter.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StrictUtf8RuntimeLogTest {
    @Test
    fun `strict runtime log preserves unicode bytes`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("utf8.log")
        val expected = "OUT µ中文\nERR µ中文\n"
        log.writeBytes(expected.toByteArray(Charsets.UTF_8))

        val decoded = assertIs<StrictUtf8RuntimeLog.DecodeResult.Success>(
            StrictUtf8RuntimeLog.decode(log)
        )

        assertEquals(expected, decoded.text)
        assertEquals(expected.toByteArray(Charsets.UTF_8).size, decoded.bytes)
    }

    @Test
    fun `strict runtime log rejects malformed bytes with raw evidence`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("malformed.log")
        log.writeBytes("prefix ".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xa6.toByte(), 0xcc.toByte()))

        val failure = assertIs<StrictUtf8RuntimeLog.DecodeResult.Failure>(
            StrictUtf8RuntimeLog.decode(log)
        )

        assertEquals(7, failure.invalidOffset)
        assertTrue(failure.invalidLength >= 1)
        assertTrue(failure.note().contains("sha256="))
        assertTrue(failure.note().contains("invalidOffset=7"))
    }
}
