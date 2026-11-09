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

class LegacyMobEffectCodecsTest {
    @TempDir lateinit var directory: Path
    @Test
    fun `removed factor data leaves the source effect schema and target constructor aligned`() {
        val file = directory.resolve("src/main/java/example/Effects.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import com.mojang.serialization.Codec;
            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.effect.MobEffectInstance.FactorData;
            class Effects {
                static final MapCodec<MobEffectInstance> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BuiltInRegistries.MOB_EFFECT.byNameCodec().fieldOf("effect").forGetter(MobEffectInstance::getEffect),
                    Codec.INT.optionalFieldOf("duration", 0).forGetter(MobEffectInstance::getDuration),
                    Codec.INT.optionalFieldOf("amplifier", 0).forGetter(MobEffectInstance::getAmplifier),
                    Codec.BOOL.optionalFieldOf("ambient", false).forGetter(MobEffectInstance::isAmbient),
                    Codec.BOOL.optionalFieldOf("show_particles", true).forGetter(MobEffectInstance::isVisible),
                    Codec.BOOL.optionalFieldOf("show_icon", true).forGetter(MobEffectInstance::showIcon),
                    hidden.optionalFieldOf("hidden_effect").forGetter(Effects::hidden),
                    FactorData.CODEC.optionalFieldOf("factor_data").forGetter(MobEffectInstance::getFactorData)
                ).apply(instance, (effect, duration, amplifier, ambient, particles, icon, hiddenEffect, factorData) ->
                    new MobEffectInstance(effect, duration, amplifier, ambient, particles, icon, hiddenEffect.orElse(null), factorData)));
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertFalse(source.contains("MobEffectInstance.FactorData"), source)
        assertTrue(source.contains("Codec.PASSTHROUGH.optionalFieldOf(\"factor_data\")"), source)
        assertTrue(source.contains("Legacy factor_data cannot be migrated"), source)
        assertTrue(source.contains("MOB_EFFECT.holderByNameCodec()"), source)
        assertTrue(source.contains("new MobEffectInstance(effect, duration, amplifier, ambient, particles, icon, hiddenEffect.orElse(null))"), source)
        assertTrue(source.contains("optionalFieldOf(\"hidden_effect\")"), source)
    }
}
