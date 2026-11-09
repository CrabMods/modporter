package com.modporter.core.transforms.structural

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectValueFactsDirectMemberIndexTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `masked depth index matches the legacy scan at every type offset`() {
        val source = """
            package sample;

            class Outer {
                String direct = "{ ignored string brace }";
                String text = ${"\"\"\""}
                    { ignored text block brace }
                    // ignored text
                ${"\"\"\""};
                // } ignored line comment
                /* { ignored block comment } */
                static {
                    if (true) {
                        String initializerLocal;
                    }
                }
                Runnable anonymous = new Runnable() {
                    String anonymousField;
                    public void run() {}
                };
                class Nested {
                    String nested;
                    String nestedGetter() { return nested; }
                }
                void container() {
                    class Local {
                        String local;
                        String localGetter() { return local; }
                    }
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val masked = with(pass) { maskJavaCommentsAndLiterals(source) }
        val blocks = with(pass) { javaTypeBlocks(source, masked) }
        val indexed = MaskedClassBodyDepthIndex(masked)

        assertEquals(source.length, masked.length)
        assertTrue(blocks.map { it.name }.containsAll(listOf("Outer", "Nested", "Local")))
        for (block in blocks) {
            val body = block.bodyStart..block.end
            for (offset in body) {
                assertEquals(
                    with(pass) { isDirectClassBodyMember(masked, body, offset) },
                    indexed.isDirectMember(body, offset),
                    "${block.referenceName} at offset $offset"
                )
            }
        }
    }

    @Test
    fun `collection keeps direct named type members and rejects executable body declarations`() {
        val source = """
            package sample;

            class Outer {
                String directField;
                String template = ${"\"\"\""}
                    String fakeTextBlockField;
                    { }
                ${"\"\"\""};
                // String fakeCommentField;
                static {
                    String initializerLocal;
                }
                Runnable anonymous = new Runnable() {
                    String anonymousField;
                    public void run() {}
                };
                String directGetter() {
                    String methodLocal;
                    return directField;
                }
                Object directMethod(String value) { return value; }
                class Nested {
                    String nestedField;
                    String nestedGetter() { return nestedField; }
                }
                void container() {
                    class Local {
                        String localField;
                        String localGetter() { return localField; }
                    }
                }
            }
        """.trimIndent()
        val file = directory.resolve("Outer.java").also { it.writeText(source) }
        val pass = StructuralRefactorPass()
        val facts = ProjectValueFacts.collect(pass, listOf(file))

        assertEquals(
            setOf("directField", "template", "anonymous"),
            facts.fields.getValue("sample.Outer").keys
        )
        assertEquals(setOf("nestedField"), facts.fields.getValue("sample.Outer.Nested").keys)
        assertEquals(setOf("localField"), facts.fields.getValue("sample.Outer.Local").keys)
        assertEquals("java.lang.String", facts.getters.getValue("sample.Outer").getValue("directGetter"))
        assertEquals("java.lang.String", facts.getters.getValue("sample.Outer.Nested").getValue("nestedGetter"))
        assertEquals("java.lang.String", facts.getters.getValue("sample.Outer.Local").getValue("localGetter"))
        assertTrue("directMethod/1" in facts.methods.getValue("sample.Outer"))
        assertTrue("container/0" in facts.methods.getValue("sample.Outer"))
        assertFalse(facts.fields.values.any { "initializerLocal" in it })
        assertFalse(facts.fields.values.any { "anonymousField" in it })
        assertFalse(facts.fields.values.any { "methodLocal" in it })
        assertFalse(facts.methods.values.any { "run/0" in it })
    }
}
