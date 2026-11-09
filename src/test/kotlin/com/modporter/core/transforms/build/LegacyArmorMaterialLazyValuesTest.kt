package com.modporter.core.transforms.build

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * NeoForge registers `minecraft:armor_material` before sound events and items, and the mod
 * constructor's `ARMOR_MATERIALS.register(bus)` initializes the migrated enum before anything
 * registers: a legacy armor enum's registry-backed constant values must stay lazy.
 */
class LegacyArmorMaterialLazyValuesTest {
    private fun enumSource(
        soundParameter: String,
        soundField: String,
        soundGetter: String,
        ingredientParameter: String,
        ingredientField: String,
        ingredientGetter: String,
        vararg constants: String
    ): String = """
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
            ${constants.joinToString(",\n            ")};

            private final String name;
            private final int durability;
            private final EnumMap<ArmorItem.Type, Integer> protection;
            private final int enchantability;
            $soundField
            private final float toughness;
            $ingredientField

            ModArmor(String name, int durability, EnumMap<ArmorItem.Type, Integer> protection, int enchantability, $soundParameter, float toughness, $ingredientParameter) {
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
            @Override public SoundEvent getEquipSound() { $soundGetter }
            @Override public Ingredient getRepairIngredient() { $ingredientGetter }
            @Override public String getName() { return name; }
            @Override public float getToughness() { return toughness; }
            @Override public float getKnockbackResistance() { return 0.0F; }
        }
    """.trimIndent()

    private fun constant(name: String, sound: String, ingredient: String) =
        """$name("${name.lowercase()}", 20, Util.make(new EnumMap<>(ArmorItem.Type.class), map -> {
                map.put(ArmorItem.Type.BOOTS, 3);
            }), 8, $sound, 1.0F, $ingredient)"""

    @Test
    fun `a SoundEvent parameter fed only holders becomes a holder the record receives undereferenced`() {
        val source = enumSource(
            "SoundEvent sound", "private final SoundEvent equipSound;", "return equipSound;",
            "Supplier<Ingredient> repair", "private final Supplier<Ingredient> repair;", "return repair.get();",
            constant("SCALE", "SoundEvents.ARMOR_EQUIP_GENERIC", "() -> Ingredient.of(ModItems.SCALE.get())"),
            constant("METAL", "ModSounds.METAL_EQUIP.get()", "() -> Ingredient.EMPTY")
        )

        val rendered = assertNotNull(
            LegacyArmorMaterialEnums.render(source, "\"example\"", emptySet(), emptyMap(), setOf("ModSounds.METAL_EQUIP"))
        ).source

        assertTrue(rendered.contains("int enchantability, Holder<SoundEvent> sound, float toughness, Supplier<Ingredient> repair)"), rendered)
        assertTrue(rendered.contains("private final Holder<SoundEvent> equipSound;"), rendered)
        assertTrue(rendered.contains("public SoundEvent getEquipSound() { return equipSound.value(); }"), rendered)
        assertTrue(rendered.contains("}), 8, SoundEvents.ARMOR_EQUIP_GENERIC, 1.0F,"), rendered)
        assertTrue(rendered.contains("}), 8, ModSounds.METAL_EQUIP, 1.0F,"), rendered)
        assertTrue(rendered.contains("register(Legacy.SCALE, () -> SoundEvents.ARMOR_EQUIP_GENERIC, false);"), rendered)
        assertTrue(rendered.contains("register(Legacy.METAL, () -> ModSounds.METAL_EQUIP, false);"), rendered)
        assertFalse(rendered.contains("wrapAsHolder"), rendered)
        // the already lazy ingredient supplier is untouched
        assertTrue(rendered.contains("1.0F, () -> Ingredient.of(ModItems.SCALE.get()))"), rendered)
        assertTrue(rendered.contains("public Ingredient getRepairIngredient() { return repair.get(); }"), rendered)
    }

    @Test
    fun `an eager Ingredient parameter becomes a supplier so item lookups wait for registration`() {
        val source = enumSource(
            "SoundEvent sound", "private final SoundEvent equipSound;", "return this.equipSound;",
            "Ingredient repair", "private final Ingredient repair;", "return this.repair;",
            constant("FROST", "SoundEvents.ARMOR_EQUIP_IRON", "Ingredient.of(ModItems.FROST_INGOT.get())"),
            constant("EMPTY", "SoundEvents.ARMOR_EQUIP_LEATHER", "Ingredient.EMPTY")
        )

        val rendered = assertNotNull(LegacyArmorMaterialEnums.render(source, "\"example\"", emptySet())).source

        assertTrue(rendered.contains("float toughness, Supplier<Ingredient> repair)"), rendered)
        assertTrue(rendered.contains("private final Supplier<Ingredient> repair;"), rendered)
        assertTrue(rendered.contains("public Ingredient getRepairIngredient() { return this.repair.get(); }"), rendered)
        assertTrue(rendered.contains("1.0F, () -> Ingredient.of(ModItems.FROST_INGOT.get()))"), rendered)
        assertTrue(rendered.contains("1.0F, () -> Ingredient.EMPTY)"), rendered)
        // vanilla holders alone also let the sound parameter widen
        assertTrue(rendered.contains("int enchantability, Holder<SoundEvent> sound, float toughness"), rendered)
        assertTrue(rendered.contains("public SoundEvent getEquipSound() { return this.equipSound.value(); }"), rendered)
        assertTrue(rendered.contains("register(Legacy.FROST, () -> SoundEvents.ARMOR_EQUIP_IRON, false);"), rendered)
    }

    @Test
    fun `a Supplier of SoundEvent parameter fed project holders passes them as holders`() {
        val source = enumSource(
            "Supplier<SoundEvent> sound", "private final Supplier<SoundEvent> equipSound;", "return this.equipSound.get();",
            "Supplier<Ingredient> repair", "private final Supplier<Ingredient> repair;", "return repair.get();",
            constant("ZANITE", "ModSounds.ZANITE_EQUIP", "() -> Ingredient.EMPTY"),
            constant("GRAVITITE", "ModSounds.GRAVITITE_EQUIP", "() -> Ingredient.EMPTY")
        )

        val rendered = assertNotNull(
            LegacyArmorMaterialEnums.render(
                source, "\"example\"", emptySet(), emptyMap(),
                setOf("ModSounds.ZANITE_EQUIP", "ModSounds.GRAVITITE_EQUIP")
            )
        ).source

        assertTrue(rendered.contains("int enchantability, Holder<SoundEvent> sound, float toughness"), rendered)
        assertTrue(rendered.contains("private final Holder<SoundEvent> equipSound;"), rendered)
        assertTrue(rendered.contains("public SoundEvent getEquipSound() { return this.equipSound.value(); }"), rendered)
        assertTrue(rendered.contains("register(Legacy.ZANITE, () -> ModSounds.ZANITE_EQUIP, false);"), rendered)
        assertTrue(rendered.contains("register(Legacy.GRAVITITE, () -> ModSounds.GRAVITITE_EQUIP, false);"), rendered)
        assertFalse(rendered.contains("wrapAsHolder"), rendered)
    }

    @Test
    fun `a sound that is neither a holder nor a proved producer keeps the value fallback`() {
        val source = enumSource(
            "Supplier<SoundEvent> sound", "private final Supplier<SoundEvent> equipSound;", "return this.equipSound.get();",
            "Supplier<Ingredient> repair", "private final Supplier<Ingredient> repair;", "return repair.get();",
            constant("ZANITE", "ModSounds.ZANITE_EQUIP", "() -> Ingredient.EMPTY"),
            constant("MIXED", "() -> ModSounds.MIXED_EQUIP.get()", "() -> Ingredient.EMPTY")
        )

        val rendered = assertNotNull(
            LegacyArmorMaterialEnums.render(source, "\"example\"", emptySet(), emptyMap(), setOf("ModSounds.ZANITE_EQUIP"))
        ).source

        // the parameter cannot widen for every constant, so the enum keeps its supplier...
        assertTrue(rendered.contains("int enchantability, Supplier<SoundEvent> sound, float toughness"), rendered)
        assertTrue(rendered.contains("public SoundEvent getEquipSound() { return this.equipSound.get(); }"), rendered)
        // ...the proved holder still reaches the record directly, the other wraps its value
        assertTrue(rendered.contains("register(Legacy.ZANITE, () -> ModSounds.ZANITE_EQUIP, false);"), rendered)
        assertTrue(rendered.contains("register(Legacy.MIXED, () -> BuiltInRegistries.SOUND_EVENT.wrapAsHolder(Legacy.MIXED.getEquipSound()), false);"), rendered)
    }
}
