package com.modporter.core.transforms.structural

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DeclaredHolderBuilderArgumentsTest {
    @Test
    fun `registrate loot callback uses its instance and enchantment provider root`() {
        val source =
            """
            package example;

            import com.tterrag.registrate.providers.loot.RegistrateBlockLootTables;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.level.storage.loot.entries.LootItem;
            import net.minecraft.world.level.storage.loot.functions.ApplyBonusCount;

            class OreCatalog {
                Object ore = builder.loot((tables, block) ->
                    tables.add(block,
                        RegistrateBlockLootTables.createSilkTouchDispatchTable(
                            block,
                            tables.applyExplosionDecay(
                                block,
                                LootItem.lootTableItem(raw)
                                    .apply(ApplyBonusCount.addOreBonusCount(
                                        Enchantments.FORTUNE))))));
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateDeclaredHolderBuilderArguments(source)
        val compact = compact(migrated)

        assertTrue(
            compact.contains(
                "tables.createSilkTouchDispatchTable(block, " +
                    "tables.applyExplosionDecay("
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "ApplyBonusCount.addOreBonusCount(" +
                    "tables.getRegistries().holderOrThrow(Enchantments.FORTUNE))"
            ),
            migrated
        )
        assertFalse(
            compact.contains(
                "RegistrateBlockLootTables.createSilkTouchDispatchTable"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateDeclaredHolderBuilderArguments(migrated)
        )
    }

    @Test
    fun `built in fluid key lookup receives deferred holder values`() {
        val source =
            """
            package example;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.neoforged.neoforge.common.NeoForgeMod;

            class FluidAliases {
                void aliases(Object oldStill, Object oldFlowing) {
                    BuiltInRegistries.FLUID.addAlias(
                        oldStill,
                        BuiltInRegistries.FLUID.getKey(NeoForgeMod.MILK));
                    BuiltInRegistries.FLUID.addAlias(
                        oldFlowing,
                        BuiltInRegistries.FLUID.getKey(NeoForgeMod.FLOWING_MILK));
                }

                Object unrelated() {
                    return anotherRegistry.getKey(NeoForgeMod.MILK);
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateDeclaredHolderBuilderArguments(source)
        val compact = compact(migrated)

        assertTrue(
            compact.contains(
                "BuiltInRegistries.FLUID.getKey(NeoForgeMod.MILK.value())"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "BuiltInRegistries.FLUID.getKey(NeoForgeMod.FLOWING_MILK.value())"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "anotherRegistry.getKey(NeoForgeMod.MILK)"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateDeclaredHolderBuilderArguments(migrated)
        )
    }

    @Test
    fun `emptying builder fluid output receives deferred holder value`() {
        val source =
            """
            package example;

            import com.simibubi.create.api.data.recipe.EmptyingRecipeGen;
            import net.neoforged.neoforge.common.NeoForgeMod;

            final class DrainRecipes extends EmptyingRecipeGen {
                Object first = create("milk", recipe -> recipe.require(input)
                    .output(NeoForgeMod.MILK, 250)
                    .output(container));

                Object second = create(id, recipe -> recipe
                    .output(NeoForgeMod.FLOWING_MILK, 125));
            }

            final class UnrelatedRecipes {
                Object retained = create("milk", recipe ->
                    recipe.output(NeoForgeMod.MILK, 250));
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateDeclaredHolderBuilderArguments(source)
        val compact = compact(migrated)

        assertTrue(
            compact.contains(
                ".output(NeoForgeMod.MILK.value(), 250)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                ".output(NeoForgeMod.FLOWING_MILK.value(), 125)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "recipe.output(NeoForgeMod.MILK, 250)"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateDeclaredHolderBuilderArguments(migrated)
        )
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .replace(Regex("""\s*,\s*"""), ", ")
            .trim()
}
