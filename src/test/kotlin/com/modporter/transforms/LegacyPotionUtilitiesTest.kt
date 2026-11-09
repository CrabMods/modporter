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

class LegacyPotionUtilitiesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `tag potion overloads retain a distinct empty sentinel and custom effects use components`() {
        val file = directory.resolve("src/main/java/example/Container.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.neoforged.fml.common.Mod;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionUtils;
            import net.minecraft.world.item.alchemy.Potions;
            @Mod("example")
            public class Container {
                Potion get(ItemStack stack) { return PotionUtils.getPotion(stack); }
                String name(CompoundTag tag) { return PotionUtils.getPotion(tag).getName("potion."); }
                void effects(ItemStack stack, CompoundTag tag) {
                    PotionUtils.setCustomEffects(stack, PotionUtils.getCustomEffects(tag));
                }
                Potion nestedLambda(ItemStack stack) {
                    java.util.function.Supplier<Potion> supplier = () -> {
                        return PotionUtils.getPotion(stack);
                    };
                    return supplier.get();
                }
                Potion absent() { return Potions.EMPTY; }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains(".potion().map(net.minecraft.core.Holder::value).orElse(com.modporter.generated.example.compat.LegacyPotionUtils.EMPTY)"), source)
        assertTrue(source.contains("LegacyPotionUtils.getName(LegacyPotionUtils.getPotion(tag), \"potion.\")"), source)
        assertTrue(source.contains(".update(DataComponents.POTION_CONTENTS, PotionContents.EMPTY"), source)
        assertTrue(source.contains("new PotionContents("), source)
        assertTrue(source.contains(".potion(),"), source)
        assertTrue(source.contains(".customColor(),"), source)
        assertTrue(source.contains("java.util.stream.Stream.concat("), source)
        assertTrue(source.contains(".customEffects().stream()"), source)
        assertTrue(source.contains(".stream().map(net.minecraft.world.effect.MobEffectInstance::new)"), source)
        assertFalse(source.contains("LegacyPotionUtils.setCustomEffects("), source)
        assertTrue(source.contains("return LegacyPotionUtils.getPotion(stack);"), source)
        assertTrue(source.contains("return com.modporter.generated.example.compat.LegacyPotionUtils.EMPTY;"), source)
        val helper = directory.resolve("src/main/java/com/modporter/generated/example/compat/LegacyPotionUtils.java").readText()
        assertTrue(helper.contains(".potion().map(Holder::value).orElse(EMPTY);"), helper)
        assertTrue(helper.contains("getList(\"CustomPotionEffects\", Tag.TAG_COMPOUND)"), helper)
        assertTrue(helper.contains("merged.addAll(effects)"), helper)
        assertTrue(helper.contains("old.customColor(), merged"), helper)
    }
}
