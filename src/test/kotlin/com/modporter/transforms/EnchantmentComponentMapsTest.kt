package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnchantmentComponentMapsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `component iteration retains raw enchantment bindings and streams unwrap held member calls`() {
        val file = directory.resolve("src/main/java/example/Enchants.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import java.util.Map;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;
            class Enchants {
                boolean allowed(ItemStack book) {
                    Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(book);
                    for (Enchantment enchantment : enchants.keySet()) {
                        log(enchantment);
                        if (!enchantment.is(Enchantments.KNOCKBACK)) return false;
                    }
                    return true;
                }
                boolean reassigned(ItemStack book, Enchantment replacement) {
                    Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(book);
                    for (Enchantment enchantment : enchants.keySet()) {
                        enchantment = replacement;
                        if (!enchantment.is(Enchantments.KNOCKBACK)) return false;
                    }
                    return true;
                }
                boolean maximum(ItemStack stack) {
                    return EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet().stream()
                        .anyMatch(entry -> entry.getKey().getMaxLevel() <= entry.getIntValue());
                }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains("ItemEnchantments enchants"), source)
        assertTrue(source.contains("Enchantment enchantment = modporterEnchantment.value()"), source)
        assertTrue(source.contains("log(enchantment)"), source)
        assertTrue(source.contains("modporterEnchantment.is(Enchantments.KNOCKBACK)"), source)
        assertTrue(source.contains("entry.getKey().value().getMaxLevel()"), source)
        assertTrue(source.contains("enchantment = replacement"), source)
        assertFalse(source.contains("modporterEnchantment_.is(Enchantments.KNOCKBACK)"), source)
    }

    @Test
    fun `item enchantment containsKey distinguishes raw enchantments from holders`() {
        val file = directory.resolve(
            "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/attributes/EnchantAttribute.java"
        )
        file.parent.createDirectories()
        file.writeText("""
            package com.simibubi.create.content.logistics.item.filter.attribute.attributes;

            import net.minecraft.core.Holder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;
            import net.minecraft.world.item.enchantment.ItemEnchantments;

            public class EnchantAttribute {
                private Enchantment enchantment;

                public boolean appliesTo(ItemStack itemStack) {
                    return EnchantmentHelper.getEnchantmentsForCrafting(itemStack).containsKey(enchantment);
                }

                public boolean appliesToField(ItemStack itemStack) {
                    return EnchantmentHelper.getEnchantmentsForCrafting(itemStack).containsKey(this.enchantment);
                }

                public boolean appliesToReassigned(ItemStack itemStack, Enchantment replacement) {
                    Enchantment candidate = enchantment;
                    candidate = replacement;
                    return EnchantmentHelper.getEnchantmentsForCrafting(itemStack).containsKey(candidate);
                }

                public boolean appliesToHolder(ItemEnchantments enchantments, Holder<Enchantment> enchantmentHolder) {
                    return enchantments.containsKey(enchantmentHolder);
                }

                public boolean leavesGetterAlone(ItemStack itemStack) {
                    return EnchantmentHelper.getEnchantmentsForCrafting(itemStack).containsKey(getEnchantment());
                }

                private Enchantment getEnchantment() {
                    return enchantment;
                }
            }
        """.trimIndent())

        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        val code = com.modporter.core.transforms.util.JavaSourceText.maskCommentsAndLiterals(source).filterNot(Char::isWhitespace)
        fun hasCode(expected: String) = code.contains(expected.filterNot(Char::isWhitespace))
        assertTrue(
            hasCode(
                "((java.util.function.BiPredicate<" +
                    "net.minecraft.world.item.enchantment.ItemEnchantments, " +
                    "net.minecraft.world.item.enchantment.Enchantment>) " +
                    "(modporterEnchantments, modporterEnchantment) -> " +
                    "modporterEnchantments.keySet().stream().map(net.minecraft.core.Holder::value)" +
                    ".anyMatch(java.util.Collections.singleton(modporterEnchantment)::contains))" +
                    ".test(EnchantmentHelper.getEnchantmentsForCrafting(itemStack), enchantment)"
            ),
            source
        )
        assertTrue(
            hasCode(
                "((java.util.function.BiPredicate<" +
                    "net.minecraft.world.item.enchantment.ItemEnchantments, " +
                    "net.minecraft.world.item.enchantment.Enchantment>) " +
                    "(modporterEnchantments, modporterEnchantment) -> " +
                    "modporterEnchantments.keySet().stream().map(net.minecraft.core.Holder::value)" +
                    ".anyMatch(java.util.Collections.singleton(modporterEnchantment)::contains))" +
                    ".test(EnchantmentHelper.getEnchantmentsForCrafting(itemStack), this.enchantment)"
            ),
            source
        )
        assertFalse(source.contains("holder.is(this.enchantment)"), source)
        assertTrue(
            hasCode(
                "((java.util.function.BiPredicate<" +
                    "net.minecraft.world.item.enchantment.ItemEnchantments, " +
                    "net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>>) " +
                    "(modporterEnchantments, modporterEnchantment) -> " +
                    "modporterEnchantments.keySet().contains(modporterEnchantment))" +
                    ".test(enchantments, enchantmentHolder)"
            ),
            source
        )
        assertTrue(
            hasCode(
                "((java.util.function.BiPredicate<" +
                    "net.minecraft.world.item.enchantment.ItemEnchantments, " +
                    "net.minecraft.world.item.enchantment.Enchantment>) " +
                    "(modporterEnchantments, modporterEnchantment) -> " +
                    "modporterEnchantments.keySet().stream().map(net.minecraft.core.Holder::value)" +
                    ".anyMatch(java.util.Collections.singleton(modporterEnchantment)::contains))" +
                    ".test(EnchantmentHelper.getEnchantmentsForCrafting(itemStack), candidate)"
            ),
            source
        )
        assertTrue(
            source.contains(
                "EnchantmentHelper.getEnchantmentsForCrafting(itemStack).containsKey(getEnchantment())"
            ),
            source
        )
        assertFalse(source.contains("Holder.direct"), source)
        assertFalse(source.contains("BuiltInRegistries"), source)
    }
}
