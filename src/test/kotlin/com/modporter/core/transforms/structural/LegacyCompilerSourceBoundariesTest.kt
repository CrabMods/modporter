package com.modporter.core.transforms.structural

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyCompilerSourceBoundariesTest {
    @Test
    fun `legacy all unconstrained item predicate keeps its tag restriction`() {
        val source =
            """
            package example;

            import net.minecraft.advancements.critereon.EnchantmentPredicate;
            import net.minecraft.advancements.critereon.InventoryChangeTrigger;
            import net.minecraft.advancements.critereon.ItemPredicate;
            import net.minecraft.advancements.critereon.MinMaxBounds;
            import net.minecraft.advancements.critereon.NbtPredicate;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;

            final class Advancements {
                Object whenItemCollected(TagKey<Item> tag) {
                    return InventoryChangeTrigger.TriggerInstance.hasItems(
                        new ItemPredicate(
                            tag, null,
                            MinMaxBounds.Ints.ANY,
                            MinMaxBounds.Ints.ANY,
                            EnchantmentPredicate.NONE,
                            EnchantmentPredicate.NONE,
                            null,
                            NbtPredicate.ANY));
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateLegacyCompilerSourceBoundaries(source)
        assertTrue(
            compact(migrated).contains(
                "InventoryChangeTrigger.TriggerInstance.hasItems(" +
                    "ItemPredicate.Builder.item().of(tag).build())"
            ),
            migrated
        )
        assertFalse(migrated.contains("NbtPredicate.ANY"), migrated)
        assertFalse(
            migrated.contains(
                "import net.minecraft.advancements.critereon.NbtPredicate;"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass()
                .migrateLegacyCompilerSourceBoundaries(migrated)
        )
    }

    @Test
    fun `item capability predicate uses target nullable result only at the declared overload`() {
        val source =
            """
            package example;

            import java.util.Optional;
            import java.util.function.Predicate;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.capabilities.Capabilities;

            final class Attributes {
                static final Object FLUID = singleton(
                    "fluid",
                    s -> s.getCapability(
                        Capabilities.FluidHandler.ITEM).isPresent());
                static final boolean RETAINED =
                    Optional.empty().isPresent();

                private static Object singleton(
                    String id, Predicate<ItemStack> predicate) {
                    return predicate;
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateLegacyCompilerSourceBoundaries(source)
        assertTrue(
            compact(migrated).contains(
                "s -> s.getCapability(" +
                    "Capabilities.FluidHandler.ITEM) != null"
            ),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "Optional.empty().isPresent()"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass()
                .migrateLegacyCompilerSourceBoundaries(migrated)
        )
    }

    @Test
    fun `item capability presence handles typed locals and preserves unproven receivers`() {
        val source =
            """
            package example;

            import java.util.Optional;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.capabilities.Capabilities;

            final class Drain {
                boolean accepts(ItemStack itemstack) {
                    ItemStack heldItem = itemstack;
                    if (!heldItem.getCapability(
                            Capabilities.FluidHandler.ITEM)
                        .isPresent()) {
                        return false;
                    }
                    Optional.empty().isPresent();
                    return true;
                }

                boolean hasFluid(ItemStack heldItem) {
                    return heldItem.getCapability(
                        Capabilities.FluidHandler.ITEM).isPresent();
                }

                boolean retained(Object heldItem) {
                    return heldItem.getCapability(
                        Capabilities.FluidHandler.ITEM).isPresent();
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateLegacyCompilerSourceBoundaries(source)
        val compacted = compact(migrated)
        assertTrue(
            compacted.contains(
                "if (heldItem.getCapability(" +
                    "Capabilities.FluidHandler.ITEM) == null)"
            ),
            migrated
        )
        assertTrue(
            compacted.contains(
                "return heldItem.getCapability(" +
                    "Capabilities.FluidHandler.ITEM) != null;"
            ),
            migrated
        )
        assertTrue(
            compacted.contains("Optional.empty().isPresent()"),
            migrated
        )
        assertTrue(
            compacted.contains(
                "return heldItem.getCapability(" +
                    "Capabilities.FluidHandler.ITEM).isPresent();"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass()
                .migrateLegacyCompilerSourceBoundaries(migrated)
        )
    }

    @Test
    fun `literal zero Tier maps to the native wooden incorrect block tag`() {
        val source =
            """
            package example;

            import net.minecraft.world.item.Tier;
            import net.minecraft.world.item.crafting.Ingredient;

            enum Cardboard implements Tier {
                VALUE;

                public int getUses() { return 0; }
                public float getSpeed() { return 1; }
                public float getAttackDamageBonus() { return 2; }

                public int getLevel() {
                    return 0;
                }

                public int getEnchantmentValue() { return 1; }
                public Ingredient getRepairIngredient() {
                    return Ingredient.EMPTY;
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateLegacyCompilerSourceBoundaries(source)
        assertTrue(
            migrated.contains("import net.minecraft.tags.BlockTags;"),
            migrated
        )
        assertTrue(
            migrated.contains("import net.minecraft.tags.TagKey;"),
            migrated
        )
        assertTrue(
            migrated.contains(
                "import net.minecraft.world.level.block.Block;"
            ),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "public TagKey<Block> getIncorrectBlocksForDrops() " +
                    "{ return BlockTags.INCORRECT_FOR_WOODEN_TOOL; }"
            ),
            migrated
        )
        assertFalse(migrated.contains("getLevel()"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass()
                .migrateLegacyCompilerSourceBoundaries(migrated)
        )
    }

    @Test
    fun `unproven literal tier levels remain unchanged`() {
        val source =
            """
            package example;

            import net.minecraft.world.item.Tier;

            enum CustomTier implements Tier {
                VALUE;

                public int getLevel() {
                    return 1;
                }
            }
            """.trimIndent() + "\n"

        assertEquals(
            source,
            StructuralRefactorPass()
                .migrateLegacyCompilerSourceBoundaries(source)
        )
    }

    @Test
    fun `block tag holder stream unwraps before ItemLike method reference`() {
        val source =
            """
            package example;

            import java.util.List;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.ItemLike;

            final class Fluids {
                List<Item> planks() {
                    return BuiltInRegistries.BLOCK
                        .getOrCreateTag(BlockTags.PLANKS)
                        .stream()
                        .map(ItemLike::asItem)
                        .toList();
                }

                List<Item> retained(List<ItemLike> values) {
                    return values.stream().map(ItemLike::asItem).toList();
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateLegacyCompilerSourceBoundaries(source)
        val compact = compact(migrated)
        assertTrue(
            migrated.contains("import net.minecraft.core.Holder;"),
            migrated
        )
        assertTrue(
            compact.contains(
                ".getOrCreateTag(BlockTags.PLANKS).stream()" +
                    ".map(Holder::value).map(ItemLike::asItem)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "values.stream().map(ItemLike::asItem)"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass()
                .migrateLegacyCompilerSourceBoundaries(migrated)
        )
    }

    @Test
    fun `GameTest enchantment uses its helper level registry provider`() {
        val source =
            """
            package example;

            import com.simibubi.create.infrastructure.gametest.CreateGameTestHelper;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.enchantment.EnchantmentInstance;
            import net.minecraft.world.item.enchantment.Enchantments;

            final class TestItems {
                static Object attributeFilters(
                    CreateGameTestHelper helper) {
                    return new EnchantmentInstance(
                        parameters.holders()
                            .lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.PROTECTION),
                        1);
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateLegacyCompilerSourceBoundaries(source)
        assertTrue(
            compact(migrated).contains(
                "new EnchantmentInstance(" +
                    "helper.getLevel().registryAccess()" +
                    ".registryOrThrow(Registries.ENCHANTMENT)" +
                    ".getHolderOrThrow(Enchantments.PROTECTION), 1)"
            ),
            migrated
        )
        assertFalse(migrated.contains("parameters.holders()"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass()
                .migrateLegacyCompilerSourceBoundaries(migrated)
        )
    }

    @Test
    fun `declared parameters provider root is not treated as stray`() {
        val source =
            """
            package example;

            import com.simibubi.create.infrastructure.gametest.CreateGameTestHelper;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.enchantment.EnchantmentInstance;
            import net.minecraft.world.item.enchantment.Enchantments;

            final class TestItems {
                static Object retained(
                    CreateGameTestHelper helper,
                    Parameters parameters) {
                    return new EnchantmentInstance(
                        parameters.holders()
                            .lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.PROTECTION),
                        1);
                }
            }
            """.trimIndent() + "\n"

        assertEquals(
            source,
            StructuralRefactorPass()
                .migrateLegacyCompilerSourceBoundaries(source)
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
