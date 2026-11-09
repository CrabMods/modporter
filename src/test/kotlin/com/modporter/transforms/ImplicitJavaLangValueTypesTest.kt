package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.SourceValueTypes
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class ImplicitJavaLangValueTypesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `implicit java lang types resolve from declarations without importing them`() {
        val source = """
            package example;
            class ValueUser {
                void use(String text, Integer count, Runnable callback) { text.toString(); }
            }
        """.trimIndent()
        val types = SourceValueTypes(StructuralRefactorPass(), source)
        val at = source.indexOf("text.toString")
        assertEquals("java.lang.String", types.typeOf("text", at))
        assertEquals("java.lang.Integer", types.typeOf("count", at))
        assertEquals("java.lang.Runnable", types.typeOf("callback", at))
    }

    @Test
    fun `a source owned same package class and an explicit import beat implicit java lang`() {
        val owner = directory.resolve("src/main/java/example/String.java")
        owner.parent.createDirectories()
        owner.writeText("package example; public class String {}")
        val source = """
            package example;
            import foreign.Integer;
            class ValueUser { void use(String text, Integer count) { text.toString(); } }
        """.trimIndent()
        val file = owner.resolveSibling("ValueUser.java").also { it.writeText(source) }
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(owner, file))
        val types = SourceValueTypes(pass, source)
        val at = source.indexOf("text.toString")
        assertEquals("example.String", types.typeOf("text", at))
        assertEquals("foreign.Integer", types.typeOf("count", at))
    }
}
