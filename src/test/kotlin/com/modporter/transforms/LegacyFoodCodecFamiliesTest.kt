package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.stmt.ForEachStmt
import com.modporter.core.transforms.structural.LegacyFoodCodecFamilies
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyFoodCodecFamiliesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `legacy food and fluid schemas keep their custom fields and codecs`() {
        val source = directory.resolve("src/main/java/example/LegacyCodecs.java")
        source.parent.createDirectories()
        source.writeText("""
            package example;
            import com.mojang.datafixers.util.Pair;
            import com.mojang.serialization.Codec;
            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Optional;
            import java.util.function.Supplier;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.util.ExtraCodecs;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.food.FoodProperties;
            import net.neoforged.neoforge.fluids.FluidStack;
            class LegacyCodecs {
                static final Codec<MobEffectInstance> MOB_EFFECT_INSTANCE = Codec.unit(new MobEffectInstance(null));
                static final Codec<FluidStack> FLUID_STACK = RecordCodecBuilder.create(instance -> instance.group(
                    BuiltInRegistries.FLUID.byNameCodec().fieldOf("FluidName").forGetter(FluidStack::getFluid),
                    Codec.INT.fieldOf("Amount").forGetter(FluidStack::getAmount),
                    CompoundTag.CODEC.optionalFieldOf("Tag").forGetter(stack -> Optional.ofNullable(stack.getTag()))
                ).apply(instance, (fluid, amount, tag) -> {
                    FluidStack decoded = new FluidStack(fluid, amount);
                    if (!decoded.isEmpty()) tag.ifPresent(decoded::setTag);
                    return decoded;
                }));
                static final Codec<FoodProperties> FOOD = RecordCodecBuilder.create(instance -> instance.group(
                    ExtraCodecs.NON_NEGATIVE_INT.fieldOf("nutrition").forGetter(FoodProperties::getNutrition),
                    Codec.FLOAT.fieldOf("saturation_modifier").forGetter(FoodProperties::getSaturationModifier),
                    Codec.BOOL.optionalFieldOf("is_meat", false).forGetter(FoodProperties::isMeat),
                    Codec.BOOL.optionalFieldOf("can_always_eat", false).forGetter(FoodProperties::canAlwaysEat),
                    Codec.BOOL.optionalFieldOf("is_fast_food", false).forGetter(FoodProperties::isFastFood),
                    FoodEffect.CODEC.codec().listOf().optionalFieldOf("effects", List.of()).forGetter(food -> {
                        List<FoodEffect> effects = new ArrayList<>();
                        for (Pair<MobEffectInstance, Float> effect : food.effects())
                            effects.add(new FoodEffect(effect.getFirst(), effect.getSecond()));
                        return effects;
                    })
                ).apply(instance, (nutrition, saturationModifier, meat, alwaysEat, fastFood, effects) -> {
                    FoodProperties.Builder builder = new FoodProperties.Builder()
                        .nutrition(nutrition)
                        .saturationModifier(saturationModifier);
                    if (alwaysEat) builder.alwaysEdible();
                    if (fastFood) builder.fast();
                    for (FoodEffect effect : effects) builder.effect(effect.effectSupplier(), effect.probability());
                    return builder.build();
                }));
                record FoodEffect(Supplier<MobEffectInstance> effectSupplier, float probability) {
                    static final MapCodec<FoodEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        MOB_EFFECT_INSTANCE.fieldOf("effect").forGetter(FoodEffect::effect),
                        Codec.FLOAT.fieldOf("probability").forGetter(FoodEffect::probability)
                    ).apply(instance, FoodEffect::new));
                    FoodEffect(MobEffectInstance effect, float probability) { this(() -> effect, probability); }
                    MobEffectInstance effect() { return new MobEffectInstance(effectSupplier.get()); }
                }
                static List<?> unrelated(PotionLike potion) { return potion.getEffects(); }
                interface PotionLike { List<?> getEffects(); }
            }
        """.trimIndent())

        val compat = "com.modporter.generated.example.compat"
        val changes = LegacyFoodCodecFamilies.migrate(StructuralRefactorPass(), directory, { compat }, false)
        assertTrue(changes.any { it.ruleId == LegacyFoodCodecFamilies.RULE_ID }, changes.toString())
        val migrated = source.readText()
        assertTrue(migrated.contains("\"saturation_modifier\""), migrated)
        assertTrue(migrated.contains("\"is_meat\""), migrated)
        assertEquals(1, migrated.windowed("\"saturation_modifier\"".length).count { it == "\"saturation_modifier\"" })
        assertEquals(1, migrated.windowed("\"is_meat\"".length).count { it == "\"is_meat\"" })
        assertTrue(migrated.contains("LegacyFoodPropertiesCodec.saturationModifier(food)"), migrated)
        assertTrue(migrated.contains("LegacyFoodPropertiesCodec.remember("), migrated)
        assertTrue(migrated.contains(
            "Codec.<net.minecraft.world.food.FoodProperties, net.minecraft.world.food.FoodProperties>either("
        ), migrated)
        assertEquals(
            2,
            migrated.windowed(
                "RecordCodecBuilder.<net.minecraft.world.food.FoodProperties>create(".length
            ).count {
                it == "RecordCodecBuilder.<net.minecraft.world.food.FoodProperties>create("
            },
            migrated
        )
        assertTrue(migrated.contains(
            "(net.minecraft.world.food.FoodProperties food) -> food.nutrition()"
        ), migrated)
        val typedEffectGetters = JavaParser().parse(migrated).result.orElseThrow()
            .findAll(LambdaExpr::class.java)
            .filter { lambda ->
                lambda.parameters.singleOrNull()?.nameAsString == "food" &&
                    lambda.findAll(ForEachStmt::class.java).any { loop ->
                        loop.iterable.toString() == "food.effects()"
                    }
            }
        assertEquals(2, typedEffectGetters.size, migrated)
        assertTrue(typedEffectGetters.all { it.isEnclosingParameters }, migrated)
        assertTrue(typedEffectGetters.all {
            it.parameters.single().type.toString() == "net.minecraft.world.food.FoodProperties"
        }, migrated)
        assertTrue(migrated.contains(
            "Either<net.minecraft.world.food.FoodProperties, net.minecraft.world.food.FoodProperties> either"
        ), migrated)
        assertTrue(migrated.contains(".fieldOf(\"saturation\")"), migrated)
        assertTrue(migrated.contains("ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf(\"eat_seconds\", 1.6F)"), migrated)
        assertTrue(migrated.contains("ItemStack.SINGLE_ITEM_CODEC.optionalFieldOf(\"using_converts_to\")"), migrated)
        assertTrue(migrated.contains("FoodProperties::saturation"), migrated)
        assertTrue(migrated.contains("FoodProperties::eatSeconds"), migrated)
        assertTrue(migrated.contains("FoodProperties::usingConvertsTo"), migrated)
        assertTrue(migrated.contains("new net.minecraft.world.food.FoodProperties("), migrated)
        assertTrue(migrated.contains(
            "new net.minecraft.world.food.FoodProperties.PossibleEffect(effect.effectSupplier(), effect.probability())"
        ), migrated)
        assertTrue(migrated.contains("LegacyFoodPropertiesCodec.hasLegacyFields(food)"), migrated)
        assertTrue(migrated.contains("Either.<net.minecraft.world.food.FoodProperties, net.minecraft.world.food.FoodProperties>right(food)"), migrated)
        assertTrue(migrated.contains("Either.<net.minecraft.world.food.FoodProperties, net.minecraft.world.food.FoodProperties>left(food)"), migrated)
        assertTrue(migrated.contains("FoodProperties.PossibleEffect effect"), migrated)
        assertTrue(migrated.contains("food.effects()"), migrated)
        assertTrue(migrated.contains("effect.effect()"), migrated)
        assertTrue(migrated.contains("effect.probability()"), migrated)
        assertTrue(migrated.contains("FoodEffect.CODEC.codec().listOf()"), migrated)
        assertEquals(2, migrated.windowed("FoodEffect.CODEC.codec().listOf()".length)
            .count { it == "FoodEffect.CODEC.codec().listOf()" })
        assertTrue(migrated.contains("MOB_EFFECT_INSTANCE.fieldOf(\"effect\")"), migrated)
        assertTrue(migrated.contains("DataComponents.CUSTOM_DATA"), migrated)
        assertTrue(migrated.contains("CustomData.of(value)"), migrated)
        assertFalse(migrated.contains("stack.getTag()"), migrated)
        assertFalse(migrated.contains("decoded::setTag"), migrated)
        assertTrue(migrated.contains("potion.getEffects()"), migrated)

        val helper = directory.resolve("src/main/java/${compat.replace('.', '/')}/LegacyFoodPropertiesCodec.java")
        assertTrue(helper.exists())
        val helperSource = helper.readText()
        assertTrue(helperSource.contains("ReferenceQueue<FoodProperties>"), helperSource)
        assertTrue(helperSource.contains("System.identityHashCode(properties)"), helperSource)
        assertTrue(helperSource.contains("properties == reference.get()"), helperSource)
        assertTrue(helperSource.contains("boolean hasLegacyFields(FoodProperties properties)"), helperSource)
        assertTrue(helperSource.contains("return requireFields(properties).saturationModifier();"), helperSource)
        assertTrue(helperSource.contains("return requireFields(properties).meat();"), helperSource)
        assertTrue(helperSource.contains("return requireFields(properties).fastFood();"), helperSource)
        assertTrue(helperSource.contains("throw new IllegalStateException"), helperSource)
        assertFalse(helperSource.contains("nutrition == 0"), helperSource)
        assertFalse(helperSource.contains("properties.saturation() /"), helperSource)
        assertFalse(helperSource.contains("Float.compare(properties.eatSeconds()"), helperSource)

        val beforeSecondPass = migrated
        val second = LegacyFoodCodecFamilies.migrate(StructuralRefactorPass(), directory, { compat }, false)
        assertTrue(second.isEmpty(), second.toString())
        assertEquals(beforeSecondPass, source.readText())
    }
}
