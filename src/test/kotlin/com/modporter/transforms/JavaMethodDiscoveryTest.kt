package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.javaMethodRangesIncludingDefault
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class JavaMethodDiscoveryTest {
    @Test
    fun `class literals inside method annotations remain methods`() {
        val source = """
            public class Reader {
                @Binding(String.class)
                public String read() { return "text"; }
            }
        """.trimIndent()
        val methods = with(StructuralRefactorPass()) { javaMethodRangesIncludingDefault(source) }
        assertEquals(listOf("read"), methods.map { it.name })
    }

    @Test
    fun `record headers do not hide the record methods from provider discovery`() {
        val source = """
            public record Order(Entry entry, int count) {
                public record Entry(int amount) {
                    public String write() {
                        return Integer.toString(amount);
                    }
                }
                public Entry create() {
                    return new Entry(count);
                }
            }
        """.trimIndent()
        val methods = with(StructuralRefactorPass()) { javaMethodRangesIncludingDefault(source) }
        assertEquals(listOf("write", "create"), methods.map { it.name })
    }
}
