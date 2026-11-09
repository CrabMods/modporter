package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.replaceExecutableRegex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutableRegexSourceIntegrityTest {

    @TempDir
    lateinit var tempDir: Path

    private val srcDir: Path
        get() = tempDir.resolve("src/main/java/com/example")

    private fun java(name: String, source: String) {
        srcDir.createDirectories()
        srcDir.resolve("$name.java").writeText(source.trimIndent() + "\n")
    }

    @Test
    fun `masked-only no-op match preserves original literal bytes`() {
        val source = """throw new LuaException("Slot out of range (1 or greater)");"""
        val pattern = Regex("""LuaException\(([^()]*)\)""")

        val migrated = StructuralRefactorPass().replaceExecutableRegex(source, pattern) { it.value }

        assertEquals(source, migrated)
    }

    @Test
    fun `masked-only changed replacements still apply and raw matches expose original groups`() {
        val pass = StructuralRefactorPass()
        val pattern = Regex("""LuaException\(([^()]*)\)""")
        val maskedOnlySource = """throw new LuaException("Slot out of range (1 or greater)");"""
        val rawSource = """throw new LuaException("Slot out of range");"""

        val changed = pass.replaceExecutableRegex(maskedOnlySource, pattern) {
            """LuaException("replacement")"""
        }
        val rawGroup = pass.replaceExecutableRegex(rawSource, pattern) {
            "LuaException(${it.groupValues[1]})"
        }

        assertEquals("""throw new LuaException("replacement");""", changed)
        assertEquals(rawSource, rawGroup)
    }

    @Test
    fun `provider propagation preserves Create exception diagnostics`() {
        java(
            "StackData",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class StackData {
                private ItemStack held = ItemStack.EMPTY;

                public void loadFromNBT(CompoundTag tag) {
                    held = ItemStack.of(tag.getCompound("held"));
                }
            }
            """
        )
        java(
            "Wrapper",
            """
            package com.example;

            import dan200.computercraft.api.lua.LuaException;
            import net.minecraft.nbt.CompoundTag;

            public class Wrapper {
                private final StackData data = new StackData();

                public void fromStackNBT(CompoundTag tag) {
                    data.loadFromNBT(tag);
                }

                public void checkSlot() throws LuaException {
                    throw new LuaException("Slot out of range (1 or greater)");
                }

                public void checkSymbol() {
                    throw new IllegalArgumentException("Symbol ' ' (whitespace) is reserved and cannot be defined");
                }

                public void checkOutputs() {
                    throw new IllegalStateException("Do not call getOutputs() on a Result that doesNothing().");
                }

                public void checkHeldOutput() {
                    throw new IllegalStateException(
                        "Do not call getHeldOutput() on a Result with hasHeldOutput() == false.");
                }

                public String unrelated() {
                    // Parentheses in comments (must stay intact).
                    return "Parentheses in strings (must stay intact).";
                }
            }
            """
        )

        java(
            "Entry",
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            public class Entry {
                public void load(Level level, Wrapper wrapper, CompoundTag tag) {
                    wrapper.fromStackNBT(tag);
                }
            }
            """
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val data = srcDir.resolve("StackData.java").readText()
        val wrapper = srcDir.resolve("Wrapper.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(
            data.contains("public void loadFromNBT(CompoundTag tag, HolderLookup.Provider registries)"),
            data
        )
        assertTrue(
            data.contains("""ItemStack.parseOptional(registries, tag.getCompound("held"))"""),
            data
        )
        assertTrue(
            wrapper.contains("public void fromStackNBT(CompoundTag tag, HolderLookup.Provider registries)"),
            wrapper
        )
        assertTrue(wrapper.contains("data.loadFromNBT(tag, registries);"), wrapper)

        val expectedMessages = listOf(
            "Slot out of range (1 or greater)",
            "Symbol ' ' (whitespace) is reserved and cannot be defined",
            "Do not call getOutputs() on a Result that doesNothing().",
            "Do not call getHeldOutput() on a Result with hasHeldOutput() == false."
        )
        expectedMessages.forEach { message ->
            assertTrue(wrapper.contains("\"$message\""), wrapper)
        }
        assertFalse(Regex("""(?:LuaException|IllegalArgumentException|IllegalStateException)\(\s*\)""").containsMatchIn(wrapper))
        assertTrue(wrapper.contains("// Parentheses in comments (must stay intact)."))
        assertTrue(wrapper.contains("\"Parentheses in strings (must stay intact).\""))
    }
}
