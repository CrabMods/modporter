package com.simibubi.create.content.equipment.armor;

import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.Create;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem.Type;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class AllArmorMaterials {
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, com.simibubi.create.Create.ID);

    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> COPPER = register(Legacy.COPPER, false);
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> CARDBOARD = register(Legacy.CARDBOARD, false);

    private static DeferredHolder<ArmorMaterial, ArmorMaterial> register(Legacy legacy, boolean dyeable) {
        ResourceLocation texture = ResourceLocation.parse(legacy.getName());
        List<ArmorMaterial.Layer> layers = dyeable
            ? List.of(new ArmorMaterial.Layer(texture, "", true), new ArmorMaterial.Layer(texture, "_overlay", false))
            : List.of(new ArmorMaterial.Layer(texture));
        return ARMOR_MATERIALS.register(texture.getPath(), () -> {
            EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
            for (ArmorItem.Type type : new ArmorItem.Type[] { ArmorItem.Type.HELMET, ArmorItem.Type.CHESTPLATE, ArmorItem.Type.LEGGINGS, ArmorItem.Type.BOOTS }) {
                defense.put(type, legacy.getDefenseForType(type));
            }
            return new ArmorMaterial(defense, legacy.getEnchantmentValue(), BuiltInRegistries.SOUND_EVENT.wrapAsHolder(legacy.getEquipSound()),
                legacy::getRepairIngredient, layers, legacy.getToughness(), legacy.getKnockbackResistance());
        });
    }

    private static Optional<Legacy> legacy(Holder<ArmorMaterial> material) {
        if (material.is(COPPER.getKey())) return Optional.of(Legacy.COPPER);
        if (material.is(CARDBOARD.getKey())) return Optional.of(Legacy.CARDBOARD);
        return Optional.empty();
    }

    /** 1.20.1's ArmorItem took its durability from the material; 1.21.1's reads it from the item properties. */
    public static Item.Properties withDurability(Item.Properties properties, Holder<ArmorMaterial> material, ArmorItem.Type type) {
        return legacy(material).map(legacy -> properties.durability(legacy.getDurabilityForType(type))).orElse(properties);
    }


    private AllArmorMaterials() {
    }

    enum Legacy {

	COPPER(Create.asResource("copper").toString(), 7, new int[] { 2, 4, 3, 1 }, 25, () -> AllSoundEvents.COPPER_ARMOR_EQUIP.getMainEvent(), 0.0F, 0.0F,
		() -> Ingredient.of(Items.COPPER_INGOT)),

	CARDBOARD(Create.asResource("cardboard")
		.toString(), 4, new int[] { 1, 1, 1, 1 }, 25, () -> SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.0F, 0.0F,
		() -> Ingredient.of(AllItems.CARDBOARD))

	;

	private static final int[] MAX_DAMAGE_ARRAY = new int[] { 11, 16, 15, 13 };
	private final String name;
	private final int maxDamageFactor;
	private final int[] damageReductionAmountArray;
	private final int enchantability;
	private final Supplier<SoundEvent> soundEvent;
	private final float toughness;
	private final float knockbackResistance;
	private final Supplier<Ingredient> repairMaterial;

	private Legacy(String name, int maxDamageFactor, int[] damageReductionAmountArray, int enchantability,
		Supplier<SoundEvent> soundEvent, float toughness, float knockbackResistance, Supplier<Ingredient> repairMaterial) {
		this.name = name;
		this.maxDamageFactor = maxDamageFactor;
		this.damageReductionAmountArray = damageReductionAmountArray;
		this.enchantability = enchantability;
		this.soundEvent = soundEvent;
		this.toughness = toughness;
		this.knockbackResistance = knockbackResistance;
		this.repairMaterial = Suppliers.memoize(repairMaterial::get);
	}

	
	public int getEnchantmentValue() {
		return this.enchantability;
	}

	
	public SoundEvent getEquipSound() {
		return this.soundEvent.get();
	}

	
	public Ingredient getRepairIngredient() {
		return this.repairMaterial.get();
	}

	public String getName() {
		return this.name;
	}

	
	public float getToughness() {
		return this.toughness;
	}

	
	public float getKnockbackResistance() {
		return this.knockbackResistance;
	}

	
	public int getDurabilityForType(Type pType) {
		return MAX_DAMAGE_ARRAY[pType.ordinal()] * this.maxDamageFactor;
	}

	
	public int getDefenseForType(Type pType) {
		return this.damageReductionAmountArray[pType.ordinal()];
	}

}
}
