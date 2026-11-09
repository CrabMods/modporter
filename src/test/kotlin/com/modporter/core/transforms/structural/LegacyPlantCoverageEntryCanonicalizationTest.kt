package com.modporter.core.transforms.structural

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LegacyPlantCoverageEntryCanonicalizationTest {
    @Test
    fun `producer parent and target pointer entries retain their digest after parsing`() {
        val parent = "entry.0.targetCoordinate." + "a".repeat(64)
        val parentLine = "$parent=Y29vcmQ"
        val childLine = "$parent.target.0=dGFyZ2V0"
        val producerText = listOf(parentLine, childLine).sorted()
            .joinToString("\n", postfix = "\n")
        val parsed = producerText.lineSequence().filter(String::isNotEmpty)
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val withTopLevelFields = parsed + ("inventorySha256" to digest(producerText))

        val verified = LegacyPlantCoverageV2Verifier.canonicalEntryText(withTopLevelFields)
        assertEquals("$childLine\n$parentLine\n", verified)
        assertEquals(producerText, verified)
        assertEquals(
            "1979aa1bb549e9cdd92a267b14c76fcb8f61a09df0609fe747bed43f5913c6c6",
            digest(verified)
        )
        val keySortedText = parsed.toSortedMap().entries
            .joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }
        assertNotEquals(digest(keySortedText), digest(verified))
        assertEquals(
            verified,
            LegacyPlantCoverageV2Verifier.canonicalEntryText(
                parsed.entries.reversed().associate { it.key to it.value }
            )
        )
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
