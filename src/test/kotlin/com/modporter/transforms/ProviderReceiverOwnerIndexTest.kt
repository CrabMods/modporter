package com.modporter.transforms

import com.modporter.core.transforms.structural.ProviderReceiverOwnerIndex
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.maskJavaCommentsAndLiterals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderReceiverOwnerIndexTest {
    @Test
    fun `late owners and same sized owner replacements update variable and getter evidence`() {
        val source = """
            class Outer {
                First first;
                Late late;
                public Late getLate() { return late; }
                public First getFirst() { return first; }
                String example = "Late imaginary;";
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val code = with(pass) { maskJavaCommentsAndLiterals(source) }
        val index = ProviderReceiverOwnerIndex(pass)
        val owners = linkedSetOf("First")
        fun resolve(receiver: String): String? = index.ownerAt(source, code, receiver, source.indexOf("example"), owners)
        assertEquals("First", resolve("first"))
        assertNull(resolve("late"))
        assertNull(resolve("getLate()"))
        owners += "Late"
        assertEquals("Late", resolve("late"))
        assertEquals("Late", resolve("this.getLate()"))
        assertEquals("First", resolve("this.first"))
        assertNull(resolve("imaginary"))
        owners.clear()
        owners += listOf("First", "Other")
        assertNull(resolve("late"))
        assertNull(resolve("getLate()"))
        assertEquals("First", resolve("getFirst()"))
    }

    @Test
    fun `source edits hash collisions rollback and this offsets keep their own receiver owner`() {
        val first = "class Outer {\n FB value;\n public FB obtain() { return value; }\n class Inner { FB nested; }\n}"
        val second = first.replace("FB", "Ea")
        assertEquals(first.hashCode(), second.hashCode())
        val pass = StructuralRefactorPass()
        val index = ProviderReceiverOwnerIndex(pass)
        val owners = linkedSetOf("FB", "Ea")
        for (source in listOf(first, second, first)) {
            val code = with(pass) { maskJavaCommentsAndLiterals(source) }
            val expected = if (source.contains("FB")) "FB" else "Ea"
            assertEquals(expected, index.ownerAt(source, code, "value", source.indexOf("value"), owners))
            assertEquals(expected, index.ownerAt(source, code, "obtain()", source.indexOf("value"), owners))
            assertEquals("Outer", index.ownerAt(source, code, "this", source.indexOf("value"), owners))
            assertEquals("Inner", index.ownerAt(source, code, "this", source.indexOf("nested"), owners))
        }
    }
}
