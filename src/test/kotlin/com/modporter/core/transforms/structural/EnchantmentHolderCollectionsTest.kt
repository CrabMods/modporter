package com.modporter.core.transforms.structural

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class EnchantmentHolderCollectionsTest {
    @Test
    fun `intrinsic keyed enchantment overrides use holders and the supplied registry lookup`() {
        val source =
            """
            package example;

            import java.util.Map;
            import net.minecraft.core.Holder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.Enchantments;

            class SubmersibleMask extends BaseMask {
                @Override
                public int getEnchantmentLevel(ItemStack mask, Enchantment enchantment) {
                    if (enchantment.is(Enchantments.RESPIRATION)) {
                        return 2;
                    }
                    return super.getEnchantmentLevel(mask, enchantment);
                }

                public Map<Enchantment, Integer> getAllEnchantments(ItemStack mask) {
                    Map<Enchantment, Integer> result = super.getAllEnchantments(mask);
                    result.put(Enchantments.RESPIRATION, 2);
                    return result;
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateEnchantmentHolderCollections(source)
        val compact = compact(migrated)

        assertTrue(
            compact.contains(
                "getEnchantmentLevel(ItemStack mask, Holder<Enchantment> enchantment)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "@Override public ItemEnchantments getAllEnchantments(" +
                    "ItemStack mask, HolderLookup.RegistryLookup<Enchantment> enchantments)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "new ItemEnchantments.Mutable(" +
                    "super.getAllEnchantments(mask, enchantments))"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "modporterEnchantments.set(" +
                    "enchantments.getOrThrow(Enchantments.RESPIRATION), 2)"
            ),
            migrated
        )
        assertTrue(compact.contains("return modporterEnchantments.toImmutable()"), migrated)
        assertTrue(migrated.contains("import net.minecraft.core.HolderLookup;"), migrated)
        assertTrue(
            migrated.contains(
                "import net.minecraft.world.item.enchantment.ItemEnchantments;"
            ),
            migrated
        )
        assertFalse(compact.contains("Map<Enchantment, Integer> result"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateEnchantmentHolderCollections(migrated)
        )
    }

    @Test
    fun `holder filtering retains levels in an ItemEnchantments mutable component`() {
        val source =
            """
            package example;

            import java.util.HashMap;
            import java.util.Map;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;

            class UpgradeFilter {
                ItemStack filter(ItemStack output) {
                    Map<Enchantment, Integer> kept = new HashMap<>();

                    EnchantmentHelper.getEnchantmentsForCrafting(output).entrySet().forEach(entry -> {
                        net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>
                            enchantment = entry.getKey();
                        int level = entry.getIntValue();
                        if (enchantment.value().canEnchant(output))
                            kept.put(enchantment, level);
                    });

                    EnchantmentHelper.setEnchantments(kept, output);
                    return output;
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateEnchantmentHolderCollections(source)
        val compact = compact(migrated)

        assertTrue(
            compact.contains(
                "ItemEnchantments.Mutable kept = new ItemEnchantments.Mutable(" +
                    "EnchantmentHelper.getEnchantmentsForCrafting(output))"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "kept.removeIf(enchantment_ -> " +
                    "!enchantment_.value().canEnchant(output))"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "EnchantmentHelper.setEnchantments(output, kept.toImmutable())"
            ),
            migrated
        )
        assertFalse(compact.contains("entrySet().forEach"), migrated)
        assertFalse(compact.contains("new HashMap<>()"), migrated)
        assertTrue(
            migrated.contains(
                "import net.minecraft.world.item.enchantment.ItemEnchantments;"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateEnchantmentHolderCollections(migrated)
        )
    }

    @Test
    fun `partial and side effecting lookalikes remain unchanged`() {
        val source =
            """
            package example;

            import java.util.HashMap;
            import java.util.Map;
            import net.minecraft.core.Holder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;
            import net.minecraft.world.item.enchantment.Enchantments;

            class UnprovenShapes extends BaseMask {
                public int getEnchantmentLevel(ItemStack stack, Enchantment enchantment) {
                    if (enchantment.is(Enchantments.RESPIRATION))
                        return calculateLevel();
                    return super.getEnchantmentLevel(stack, enchantment);
                }

                public Map<Enchantment, Integer> getAllEnchantments(ItemStack stack) {
                    Map<Enchantment, Integer> result = super.getAllEnchantments(stack);
                    audit(result);
                    result.put(Enchantments.RESPIRATION, calculateLevel());
                    return result;
                }

                void filter(ItemStack output) {
                    Map<Enchantment, Integer> kept = new HashMap<>();
                    EnchantmentHelper.getEnchantmentsForCrafting(output).entrySet().forEach(entry -> {
                        Holder<Enchantment> holder = entry.getKey();
                        int level = entry.getIntValue();
                        audit(holder);
                        if (holder.value().canEnchant(output))
                            kept.put(holder, level);
                    });
                    EnchantmentHelper.setEnchantments(kept, output);
                }
            }

            class EqualityMask extends BaseMask {
                public int getEnchantmentLevel(ItemStack stack, Enchantment enchantment) {
                    if (enchantment == Enchantments.RESPIRATION)
                        return 1;
                    return super.getEnchantmentLevel(stack, enchantment);
                }

                public Map<Enchantment, Integer> getAllEnchantments(ItemStack stack) {
                    Map<Enchantment, Integer> result = super.getAllEnchantments(stack);
                    result.put(Enchantments.RESPIRATION, 1);
                    return result;
                }
            }

            class EscapingMap {
                void filter(ItemStack output) {
                    Map<Enchantment, Integer> kept = new HashMap<>();
                    EnchantmentHelper.getEnchantmentsForCrafting(output).entrySet().forEach(entry -> {
                        Holder<Enchantment> holder = entry.getKey();
                        int level = entry.getIntValue();
                        if (holder.value().canEnchant(output))
                            kept.put(holder, level);
                    });
                    EnchantmentHelper.setEnchantments(kept, output);
                    audit(kept);
                }
            }
            """.trimIndent() + "\n"

        assertEquals(
            source,
            StructuralRefactorPass().migrateEnchantmentHolderCollections(source)
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
