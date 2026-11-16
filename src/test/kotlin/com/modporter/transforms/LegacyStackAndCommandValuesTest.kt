package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyCommandResults
import com.modporter.core.transforms.structural.migrateLegacyEmptyStackMaxSize
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyStackAndCommandValuesTest {
    private val pass = StructuralRefactorPass()

    private fun compact(source: String): String = source.replace(Regex("""\s+"""), " ").trim()

    @Test
    fun `stack size reads keep the legacy empty stack maximum once per receiver`() {
        val source = """
            package example;

            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;

            public class Limits {
                private ItemStack filter = ItemStack.EMPTY;

                ItemStack filter() { return filter; }

                int limit(ItemStack stack, Item item) {
                    int a = stack.getMaxStackSize();
                    int b = filter().getMaxStackSize();
                    int c = item.getMaxStackSize();
                    return Math.min(a, b) + c;
                }

                static class Nested {
                    int nested(ItemStack other) {
                        return other.getMaxStackSize();
                    }
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyEmptyStackMaxSize(source)
        val text = compact(migrated)

        assertTrue(text.contains("int a = modporterLegacyMaxStackSize(stack);"), migrated)
        assertTrue(text.contains("int b = modporterLegacyMaxStackSize(filter());"), migrated)
        assertTrue(text.contains("int c = item.getMaxStackSize();"), migrated)
        assertTrue(text.contains("return modporterLegacyMaxStackSize(other);"), migrated)
        assertEquals(
            1,
            Regex("""private static int modporterLegacyMaxStackSize\(net\.minecraft\.world\.item\.ItemStack stack\)""").findAll(migrated).count(),
            migrated
        )
        assertTrue(text.contains("return stack.isEmpty() ? 64 : stack.getMaxStackSize();"), migrated)
        assertEquals(migrated, pass.migrateLegacyEmptyStackMaxSize(migrated))
    }

    @Test
    fun `mixins and look-alike receivers keep their native stack size calls`() {
        val source = """
            package example;

            import net.minecraft.world.item.ItemStack;
            import org.spongepowered.asm.mixin.Mixin;

            @Mixin(ItemStack.class)
            abstract class StackMixin {
                int size(ItemStack stack) { return stack.getMaxStackSize(); }
            }
        """.trimIndent()
        val lookalike = """
            package example;

            class Box {
                int getMaxStackSize() { return 3; }
                int read(Box box) { return box.getMaxStackSize(); }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyEmptyStackMaxSize(source))
        assertEquals(lookalike, pass.migrateLegacyEmptyStackMaxSize(lookalike))
    }

    @Test
    fun `a returned prefixed command result is captured through the source callback`() {
        val source = """
            package example;

            import net.minecraft.commands.CommandSourceStack;
            import net.minecraft.server.level.ServerPlayer;

            class Dispatch {
                static int forward(ServerPlayer player, CommandSourceStack source, String command) {
                    return player.server.getCommands().performPrefixedCommand(source, command);
                }
                static void fire(ServerPlayer player, CommandSourceStack source) {
                    player.server.getCommands().performPrefixedCommand(source, "say hi");
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyCommandResults(source)
        val text = compact(migrated)

        assertTrue(text.contains("int[] modporterCommandResult = {0};"), migrated)
        assertTrue(
            text.contains(
                "player.server.getCommands().performPrefixedCommand((source).withCallback((modporterSuccess, modporterValue) -> modporterCommandResult[0] = modporterValue), command);"
            ),
            migrated
        )
        assertTrue(text.contains("return modporterCommandResult[0];"), migrated)
        assertTrue(text.contains("player.server.getCommands().performPrefixedCommand(source, \"say hi\");"), migrated)
        assertFalse(text.contains("return player.server.getCommands().performPrefixedCommand("), migrated)
    }
}
