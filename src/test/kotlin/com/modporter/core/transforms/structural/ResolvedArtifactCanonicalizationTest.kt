package com.modporter.core.transforms.structural

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResolvedArtifactCanonicalizationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `canonical artifact line records the verified file size`() {
        val bytes = "real artifact bytes".toByteArray()
        val file = temporary.resolve("fixture.jar")
        file.writeBytes(bytes)
        val artifact = artifact(file, Files.size(file))

        val fields = artifact.canonicalLines()
            .single { it.startsWith("artifact=") }
            .removePrefix("artifact=")
            .split('|')

        assertEquals(
            artifact.key,
            Base64.getUrlDecoder().decode(fields[0]).toString(Charsets.UTF_8)
        )
        assertEquals(Files.size(file).toString(), fields[1])
        assertEquals(sha256(bytes), fields[2])
        assertFailsWith<IllegalArgumentException> {
            artifact(file, Files.size(file) + 1)
        }
    }

    private fun artifact(file: Path, size: Long): ResolvedArtifact {
        val variantAttributes = emptyMap<String, String>()
        return ResolvedArtifact(
            projectPath = ":",
            component = "example:fixture:1.0",
            componentKind = "module",
            classifier = null,
            displayName = "fixture.jar",
            configurations = setOf(":|runtimeClasspath"),
            configurationOrder = mapOf(":|runtimeClasspath" to 0),
            variantAttributes = variantAttributes,
            file = file,
            size = size,
            sha256 = sha256(file.toFile().readBytes()),
            variantSha256 = sha256(
                variantAttributes.toSortedMap().entries.joinToString("\n") {
                    "${it.key}=${it.value}"
                }.toByteArray()
            )
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
