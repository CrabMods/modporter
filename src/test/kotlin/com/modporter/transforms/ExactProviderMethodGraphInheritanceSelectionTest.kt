package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCommonNeoForge121Apis
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactProviderMethodGraphInheritanceSelectionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `inherited concrete implementation supplies an abstract interface method`() {
        verifySelection("int read();")
    }

    @Test
    fun `inherited concrete implementation takes precedence over an interface default`() {
        verifySelection("default int read() { return 9; }")
    }

    @Test
    fun `inherited implementation cannot acquire a provider without closing its interface contract`() {
        val source = """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            interface Encoder {
                CompoundTag encode(ItemStack stack);
            }
            class Parent {
                public CompoundTag encode(ItemStack stack) { return stack.serializeNBT(); }
            }
            class Child extends Parent implements Encoder {}
            class Caller {
                CompoundTag save(Level level, Child child, ItemStack stack) {
                    return child.encode(stack);
                }
            }
        """.trimIndent()
        val file = tempDir.resolve("src/main/java/sample/Family.java")
        file.parent.createDirectories()
        file.writeText(source)
        for (dryRun in listOf(true, false)) {
            val failure = assertFailsWith<IllegalStateException> {
                StructuralRefactorPass().migrateCommonNeoForge121Apis(tempDir, dryRun)
            }
            assertTrue(failure.message.orEmpty().contains("Cannot partially rewrite inherited provider contract"), failure.message)
            assertEquals(source, file.readText())
        }
    }

    private fun verifySelection(interfaceMethod: String) {
        val source = """
            package sample;
            import java.util.function.IntSupplier;
            interface Reader {
                $interfaceMethod
            }
            class Parent {
                public int read() { return 3; }
            }
            class Child extends Parent implements Reader {
                int evaluate() {
                    IntSupplier value = this::read;
                    return read() + this.read() + super.read() + value.getAsInt();
                }
            }
            class Caller {
                int evaluate(Child child) { return child.read(); }
            }
        """.trimIndent()
        val file = tempDir.resolve("src/main/java/sample/Family.java")
        file.parent.createDirectories()
        file.writeText(source)
        for (dryRun in listOf(true, false)) {
            val errors = mutableListOf<String>()
            val changes = StructuralRefactorPass()
                .migrateCommonNeoForge121Apis(tempDir, dryRun, errors)
            assertTrue(errors.isEmpty(), errors.joinToString("\n"))
            assertTrue(changes.isEmpty(), changes.joinToString("\n"))
            assertEquals(source, file.readText())
        }
        val diagnostics = ByteArrayOutputStream()
        val output = tempDir.resolve("compiled").createDirectories()
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(
            null, null, diagnostics, "-proc:none", "-d", output.toString(), file.toString()
        ), diagnostics.toString(Charsets.UTF_8))
    }
}
