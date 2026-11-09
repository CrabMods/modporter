package com.modporter.integration

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Reads final runtime evidence without replacement characters or charset guessing. */
internal object StrictUtf8RuntimeLog {
    sealed interface DecodeResult {
        data class Success(
            val text: String,
            val bytes: Int,
            val sha256: String
        ) : DecodeResult

        data class Failure(
            val path: Path,
            val bytes: Int,
            val sha256: String,
            val invalidOffset: Int,
            val invalidLength: Int
        ) : DecodeResult {
            fun note(): String =
                "runtime log is not strict UTF-8: path=$path; bytes=$bytes; sha256=$sha256; " +
                    "invalidOffset=$invalidOffset; invalidLength=$invalidLength"
        }
    }

    fun decode(path: Path): DecodeResult {
        val bytes = Files.readAllBytes(path)
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(bytes.size.coerceAtLeast(1))
        val decoded = decoder.decode(input, output, true)
        if (decoded.isError) {
            return DecodeResult.Failure(path, bytes.size, sha256, input.position(), decoded.length())
        }
        val flushed = decoder.flush(output)
        check(!flushed.isError) { "UTF-8 decoder flush failed unexpectedly for $path" }
        output.flip()
        return DecodeResult.Success(output.toString(), bytes.size, sha256)
    }

    /**
     * Runtime readiness markers are ASCII. A byte-preserving view lets the live poller find
     * those markers even if it observes an incomplete multibyte sequence at the current EOF.
     * Final acceptance still calls [decode] and rejects every malformed byte.
     */
    fun asciiSearchText(path: Path): String =
        String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1)
}
