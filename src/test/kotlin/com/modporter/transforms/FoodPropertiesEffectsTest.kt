package com.modporter.transforms

import com.modporter.core.transforms.structural.FoodPropertiesEffects
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoodPropertiesEffectsTest {
    @Test
    fun `typed food effect loops preserve probability calls and one explicit copy`() {
        val source = """
            package example;
            import com.mojang.datafixers.util.Pair;
            import java.util.List;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.food.FoodProperties;
            import net.minecraft.world.item.alchemy.Potion;
            class Effects {
                static void apply(MobEffectInstance effect) {}
                static void consume(FoodProperties food, Potion potion,
                        List<Pair<MobEffectInstance, Float>> unrelated) {
                    for (Pair<MobEffectInstance, Float> effect : food.getEffects()) {
                        if (effect.getSecond() > 0.0F)
                            apply(new MobEffectInstance(effect.getFirst()));
                    }
                    for (Pair<MobEffectInstance, Float> pair : unrelated)
                        apply(new MobEffectInstance(pair.getFirst()));
                    potion.getEffects();
                }
            }
        """.trimIndent()

        val migrated = FoodPropertiesEffects.migrate(StructuralRefactorPass(), source)
        assertTrue(migrated.contains(
            "FoodProperties.PossibleEffect effect : food.effects()"
        ), migrated)
        assertTrue(migrated.contains("effect.probability() > 0.0F"), migrated)
        assertTrue(migrated.contains("apply(effect.effect())"), migrated)
        assertFalse(migrated.contains("new MobEffectInstance(effect.effect())"), migrated)
        assertFalse(migrated.contains("effect.getFirst()"), migrated)
        assertFalse(migrated.contains("effect.getSecond()"), migrated)
        assertTrue(migrated.contains(
            "for (Pair<MobEffectInstance, Float> pair : unrelated)"
        ), migrated)
        assertTrue(migrated.contains("new MobEffectInstance(pair.getFirst())"), migrated)
        assertTrue(migrated.contains("potion.getEffects()"), migrated)
        assertEquals(1, migrated.windowed("effect.effect()".length).count { it == "effect.effect()" })
        assertEquals(1, migrated.windowed("effect.probability()".length).count { it == "effect.probability()" })
        assertEquals(migrated, FoodPropertiesEffects.migrate(StructuralRefactorPass(), migrated))
    }

    @Test
    fun `unsafe first value escape leaves the typed loop fail closed`() {
        val source = """
            package example;
            import com.mojang.datafixers.util.Pair;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.food.FoodProperties;
            class Effects {
                static void retain(MobEffectInstance effect) {}
                static void consume(FoodProperties food) {
                    for (Pair<MobEffectInstance, Float> effect : food.getEffects())
                        retain(effect.getFirst());
                }
            }
        """.trimIndent()

        val migrated = FoodPropertiesEffects.migrate(StructuralRefactorPass(), source)
        assertTrue(migrated.contains(
            "for (Pair<MobEffectInstance, Float> effect : food.effects())"
        ), migrated)
        assertTrue(migrated.contains("retain(effect.getFirst())"), migrated)
        assertFalse(migrated.contains("PossibleEffect"), migrated)
    }

    @Test
    fun `only target food property methods and canonical copy constructors are admitted`() {
        val source = """
            package example;
            import com.mojang.datafixers.util.Pair;
            import net.minecraft.world.food.FoodProperties;
            import net.minecraft.world.item.ItemStack;
            class Effects {
                static final class CustomFoodAccess {
                    FoodProperties getFoodProperties(ItemStack stack, Object entity) { return null; }
                }
                static final class MobEffectInstance {
                    MobEffectInstance(net.minecraft.world.effect.MobEffectInstance effect) {}
                }
                static void custom(CustomFoodAccess access, ItemStack stack) {
                    for (Pair<net.minecraft.world.effect.MobEffectInstance, Float> effect
                            : access.getFoodProperties(stack, null).getEffects())
                        new net.minecraft.world.effect.MobEffectInstance(effect.getFirst());
                }
                static void shadow(FoodProperties food) {
                    for (Pair<net.minecraft.world.effect.MobEffectInstance, Float> effect : food.getEffects())
                        new MobEffectInstance(effect.getFirst());
                }
            }
        """.trimIndent()

        val migrated = FoodPropertiesEffects.migrate(StructuralRefactorPass(), source)
        assertTrue(migrated.contains(
            "Pair<net.minecraft.world.effect.MobEffectInstance, Float> effect"
        ), migrated)
        assertTrue(migrated.contains(
            "access.getFoodProperties(stack, null).effects()"
        ), migrated)
        assertTrue(migrated.contains(
            "new net.minecraft.world.effect.MobEffectInstance(effect.getFirst())"
        ), migrated)
        assertTrue(migrated.contains("new MobEffectInstance(effect.getFirst())"), migrated)
        assertFalse(migrated.contains("PossibleEffect"), migrated)
        assertFalse(migrated.contains("effect.effect()"), migrated)
    }
}
