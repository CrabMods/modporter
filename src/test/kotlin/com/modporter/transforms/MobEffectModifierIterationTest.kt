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

class MobEffectModifierIterationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `effect templates produce scaled modifiers and retain tooltip attribute labels`() {
        val file = directory.resolve("src/main/java/example/Tooltip.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import java.util.Map;
            import java.util.Map.Entry;
            import net.minecraft.core.Holder;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.effect.MobEffectUtil;
            import net.minecraft.world.entity.ai.attributes.Attribute;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;
            import net.minecraft.world.item.ItemStack;
            class Tooltip {
                void append(Holder<MobEffect> effect, MobEffectInstance instance) {
                    Map<Attribute, AttributeModifier> modifiers = effect.value().getAttributeModifiers();
                    if (!modifiers.isEmpty()) {
                        for (Entry<Attribute, AttributeModifier> entry : modifiers.entrySet()) {
                            AttributeModifier template = entry.getValue();
                            AttributeModifier scaled = new AttributeModifier(template.getName(),
                                effect.value().getAttributeModifierValue(instance.getAmplifier(), template), template.operation());
                            output.add(new Pair(entry.getKey().getDescriptionId(), scaled));
                        }
                    }
                    MobEffectUtil.formatDuration(instance, 1.0F);
                    ItemStack.ATTRIBUTE_MODIFIER_FORMAT.format(2.0);
                }
                int operation(AttributeModifier modifier) { return modifier.operation().toValue(); }
                int other(CustomModifier modifier) { return modifier.operation().toValue(); }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(Regex("""effect\.value\(\)\.createModifiers\(instance\.getAmplifier\(\),\s*\(\s*modporterAttribute,\s*scaled\)\s*->""").containsMatchIn(source), source)
        assertTrue(source.contains("modporterAttribute.value().getDescriptionId(), scaled"), source)
        assertFalse(source.contains("getAttributeModifierValue"), source)
        assertTrue(source.contains("MobEffectUtil.formatDuration(instance, 1.0F, 20.0F)"), source)
        assertTrue(source.contains("ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT.format(2.0)"), source)
        assertTrue(source.contains("modifier.operation().id()"), source)
        assertTrue(source.contains("return modifier.operation().toValue()"), source)
    }
}
