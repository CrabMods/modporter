package com.modporter.core.transforms.build

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The nested legacy enum keeps its SoundEvent parameter; 1.21 holder-typed vanilla sounds pass their value. */
class LegacyArmorMaterialHolderSoundTest {
    @Test
    fun `vanilla holder sounds at a SoundEvent parameter pass their value while plain sounds stay`() {
        val source = """
            package example;

            import java.util.EnumMap;
            import java.util.function.Supplier;
            import net.minecraft.Util;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.crafting.Ingredient;

            public enum ModArmor implements ArmorMaterial {
                SCALE("scale", 21, Util.make(new EnumMap<>(ArmorItem.Type.class), map -> {
                    map.put(ArmorItem.Type.BOOTS, 3);
                }), 15, SoundEvents.ARMOR_EQUIP_GENERIC, 0.5F, () -> Ingredient.EMPTY),
                METAL("metal", 20, Util.make(new EnumMap<>(ArmorItem.Type.class), map -> {
                    map.put(ArmorItem.Type.BOOTS, 3);
                }), 8, ModSounds.METAL_EQUIP.get(), 1.0F, () -> Ingredient.EMPTY),
                PLAIN("plain", 20, Util.make(new EnumMap<>(ArmorItem.Type.class), map -> {
                    map.put(ArmorItem.Type.BOOTS, 3);
                }), 8, SoundEvents.ANVIL_LAND, 1.0F, () -> Ingredient.EMPTY);

                private final String name;
                private final int durability;
                private final EnumMap<ArmorItem.Type, Integer> protection;
                private final int enchantability;
                private final SoundEvent equipSound;
                private final float toughness;
                private final Supplier<Ingredient> repair;

                ModArmor(String name, int durability, EnumMap<ArmorItem.Type, Integer> protection, int enchantability, final SoundEvent sound, float toughness, Supplier<Ingredient> repair) {
                    this.name = name;
                    this.durability = durability;
                    this.protection = protection;
                    this.enchantability = enchantability;
                    this.equipSound = sound;
                    this.toughness = toughness;
                    this.repair = repair;
                }

                @Override public int getDurabilityForType(ArmorItem.Type type) { return durability; }
                @Override public int getDefenseForType(ArmorItem.Type type) { return protection.get(type); }
                @Override public int getEnchantmentValue() { return enchantability; }
                @Override public SoundEvent getEquipSound() { return equipSound; }
                @Override public Ingredient getRepairIngredient() { return repair.get(); }
                @Override public String getName() { return name; }
                @Override public float getToughness() { return toughness; }
                @Override public float getKnockbackResistance() { return 0.0F; }
            }
        """.trimIndent()

        val rendered = assertNotNull(LegacyArmorMaterialEnums.render(source, "\"example\"", emptySet()))

        assertTrue(rendered.source.contains("}), 15, SoundEvents.ARMOR_EQUIP_GENERIC.value(), 0.5F,"), rendered.source)
        assertTrue(rendered.source.contains("}), 8, ModSounds.METAL_EQUIP.get(), 1.0F,"), rendered.source)
        // SoundEvents.ANVIL_LAND is a plain SoundEvent in 1.21.1
        assertTrue(rendered.source.contains("}), 8, SoundEvents.ANVIL_LAND, 1.0F,"), rendered.source)
    }
}
