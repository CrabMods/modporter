package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCommonNeoForge121Apis
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactProviderMethodGraphDeclarationIdentityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `constant specific enum methods are not duplicate named enum declarations`() {
        val source = """
            package sample;

            enum Strategy {
                A {
                    @Override int select(String value) { return 1; }
                },
                B {
                    @Override int select(String value) { return 2; }
                };

                abstract int select(String value);
                int ordinary(String value) { return select(value); }
            }
        """.trimIndent()
        val file = write("Strategy.java", source)
        val errors = mutableListOf<String>()

        val changes = StructuralRefactorPass().migrateCommonNeoForge121Apis(tempDir, false, errors)

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        assertTrue(changes.isEmpty(), changes.joinToString("\n"))
        assertEquals(source, file.readText())
    }

    @Test
    fun `scalar and varargs class variable overloads keep distinct declaration identities`() {
        val source = """
            package sample;

            class CreateTagAppender<T> {
                CreateTagAppender<T> add(T entry) { return this; }
                @SafeVarargs
                final CreateTagAppender<T> add(T... entries) { return this; }
            }
        """.trimIndent()
        val file = write("CreateTagAppender.java", source)
        val errors = mutableListOf<String>()

        val changes = StructuralRefactorPass().migrateCommonNeoForge121Apis(tempDir, false, errors)

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        assertTrue(changes.isEmpty(), changes.joinToString("\n"))
        assertEquals(source, file.readText())
    }

    @Test
    fun `explicit and varargs multidimensional duplicates remain rejected before writes`() {
        val source = """
            package sample;

            class Duplicate<T> {
                @SafeVarargs
                final void add(T[]... entries) {}
                void add(T[][] entries) {}
            }
        """.trimIndent()
        val file = write("Duplicate.java", source)

        val failure = assertFailsWith<IllegalStateException> {
            StructuralRefactorPass().migrateCommonNeoForge121Apis(tempDir, false)
        }

        assertTrue(
            failure.message.orEmpty().contains("Cannot resolve duplicate exact Java method declarations"),
            failure.message
        )
        assertTrue(failure.message.orEmpty().contains("java.lang.Object[][]"), failure.message)
        assertEquals(source, file.readText())
    }

    private fun write(name: String, source: String): Path {
        val file = tempDir.resolve("src/main/java/sample").resolve(name)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
