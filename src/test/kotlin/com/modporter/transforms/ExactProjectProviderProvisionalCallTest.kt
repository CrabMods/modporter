package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.ExactProjectProviderCallMigration
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactProjectProviderProvisionalCallTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `typed existing provider argument roots a legacy declaration without adding it twice`() {
        verifyRecovery(directFields = false, constructor = false)
    }

    @Test
    fun `direct record fields retain their provider closure in a partial migration`() {
        verifyRecovery(directFields = true, constructor = false)
    }

    @Test
    fun `an existing constructor provider argument roots only the callee rewrite`() {
        verifyRecovery(directFields = false, constructor = true)
    }

    @Test
    fun `a real provider arity overload cannot root another declaration`() {
        val source = fixture(extra = """
            private static boolean walk(Pieces f, Pieces i, HolderLookup.Provider registries) {
                registries.toString();
                return false;
            }
        """.trimIndent())
        verifyRefusal(source)
    }

    @Test
    fun `a non provider extra argument does not establish a boundary`() {
        verifyRefusal(fixture(boundaryType = "String"))
    }

    @Test
    fun `a nullable extra provider argument does not establish a boundary`() {
        verifyRefusal(fixture(boundaryType = "@Nullable HolderLookup.Provider", extra = "@interface Nullable {}"))
    }

    @Test
    fun `a reassigned provider parameter does not establish a boundary`() {
        verifyRefusal(fixture(beforeCall = "registries = null;"))
    }

    private fun verifyRecovery(directFields: Boolean, constructor: Boolean) {
        val source = fixture(directFields = directFields, constructor = constructor)
        val file = writeFixture(source)
        val migration = ExactProjectProviderCallMigration()
        val preview = migration.migrate(tempDir, dryRun = true)
        assertTrue(preview.isNotEmpty())
        assertEquals(source, file.readText())
        val changes = migration.migrate(tempDir, dryRun = false)
        assertEquals(preview.size, changes.size)
        val migrated = file.readText()
        val parsed = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
            .parse(migrated)
        assertTrue(parsed.isSuccessful, parsed.problems.toString())
        val unit = parsed.result.orElseThrow()
        val walk = unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == "walk" }
        assertEquals(3, walk.parameters.size)
        val consumeCalls = unit.findAll(MethodCallExpr::class.java).filter { it.nameAsString == "consume" }
        assertEquals(2, consumeCalls.size)
        assertTrue(consumeCalls.all { it.arguments.size == 3 }, migrated)
        val incoming = unit.findAll(MethodCallExpr::class.java).single { it.nameAsString == "walk" }
        assertEquals(3, incoming.arguments.size, migrated)
        assertEquals("registries", incoming.arguments[2].toString())
        assertTrue(migration.migrate(tempDir, dryRun = false).isEmpty())
        assertEquals(migrated, file.readText())
        val diagnostics = ByteArrayOutputStream()
        val output = tempDir.resolve("compiled").createDirectories()
        val inputs = Files.walk(tempDir.resolve("src/main/java")).use { paths ->
            paths.filter { it.toString().endsWith(".java") }.map { it.toString() }.toList()
        }
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(
            null, null, diagnostics, "-proc:none", "-d", output.toString(), *inputs.toTypedArray()
        ), diagnostics.toString(Charsets.UTF_8))
    }

    private fun verifyRefusal(source: String) {
        val file = writeFixture(source)
        for (dryRun in listOf(true, false)) {
            val failure = assertFailsWith<IllegalStateException> {
                ExactProjectProviderCallMigration().migrate(tempDir, dryRun)
            }
            assertTrue(failure.message.orEmpty().contains("declared provider boundary"), failure.message)
            assertEquals(source, file.readText())
        }
    }

    private fun writeFixture(source: String): Path {
        val holder = tempDir.resolve("src/main/java/net/minecraft/core/HolderLookup.java")
        holder.parent.createDirectories()
        holder.writeText("package net.minecraft.core; public class HolderLookup { public interface Provider {} }")
        val file = tempDir.resolve("src/main/java/sample/Module.java")
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }

    private fun fixture(
        directFields: Boolean = false,
        constructor: Boolean = false,
        boundaryType: String = "HolderLookup.Provider",
        extra: String = "",
        beforeCall: String = ""
    ): String {
        val first = if (directFields) "f.values" else "f.values()"
        val second = if (directFields) "i.values" else "i.values()"
        val boundary = if (constructor) {
            "Module(Pieces f, Pieces i, $boundaryType registries) { $beforeCall walk(f, i, registries); }"
        } else {
            "private static boolean start(Pieces f, Pieces i, $boundaryType registries) { $beforeCall return walk(f, i, registries); }"
        }
        return """
            package sample;
            import java.util.Iterator;
            import java.util.List;
            import net.minecraft.core.HolderLookup;
            class Module {
                record Pieces(List<?> values) {}
                private static void consume(Object first, Object second, HolderLookup.Provider registries) {
                    registries.toString();
                }
                private static boolean walk(Pieces f, Pieces i) {
                    consume($first.get(0), $second.get(0));
                    for (Object left : $first) {
                        Iterator<?> iterator = $second.iterator();
                        Object right = iterator.next();
                        consume(left, right);
                    }
                    return true;
                }
                $boundary
                $extra
            }
        """.trimIndent()
    }
}
