package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A 1.20.1 ArmorMaterial enum keeps computing its values through its own getters on 1.21.1. */
class LegacyArmorMaterialEnumsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `an armor material enum registers holders computed by its getters and its items keep their durability`() {
        val modDir = tempDir.resolve("src/main/java/com/example")
        val armorDir = modDir.resolve("armor")
        armorDir.createDirectories()
        tempDir.resolve("build.gradle").writeText("plugins {\n    id 'net.minecraftforge.gradle' version '[6.0,6.2)'\n}\n")
        modDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.ID)
            public class ExampleMod {
                public static final String ID = "example";

                public ExampleMod(ModContainer container) {
                    IEventBus modEventBus = container.getEventBus();
                }
            }
        """.trimIndent())
        armorDir.resolve("ArmorMaterials.java").writeText("""
            package com.example.armor;

            import com.example.ExampleMod;
            import java.util.function.Supplier;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;

            public enum ArmorMaterials implements ArmorMaterial {

                COPPER(ExampleMod.ID + ":copper", 7, new int[] { 2, 4, 3, 1 }, 25, () -> SoundEvents.ARMOR_EQUIP_IRON, 0.0F, 0.0F,
                    () -> Ingredient.of(Items.COPPER_INGOT)),

                CARDBOARD(ExampleMod.ID + ":cardboard", 4, new int[] { 1, 1, 1, 1 }, 25, () -> SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F,
                    () -> Ingredient.of(Items.PAPER));

                private static final int[] MAX_DAMAGE_ARRAY = new int[] { 11, 16, 15, 13 };
                private final String name;
                private final int maxDamageFactor;
                private final int[] reduction;
                private final int enchantability;
                private final Supplier<SoundEvent> sound;
                private final float toughness;
                private final float knockbackResistance;
                private final Supplier<Ingredient> repair;

                private ArmorMaterials(String name, int maxDamageFactor, int[] reduction, int enchantability,
                    Supplier<SoundEvent> sound, float toughness, float knockbackResistance, Supplier<Ingredient> repair) {
                    this.name = name;
                    this.maxDamageFactor = maxDamageFactor;
                    this.reduction = reduction;
                    this.enchantability = enchantability;
                    this.sound = sound;
                    this.toughness = toughness;
                    this.knockbackResistance = knockbackResistance;
                    this.repair = repair;
                }

                @Override
                public int getEnchantmentValue() {
                    return enchantability;
                }

                @Override
                public SoundEvent getEquipSound() {
                    return sound.get();
                }

                @Override
                public Ingredient getRepairIngredient() {
                    return repair.get();
                }

                @Override
                public String getName() {
                    return name;
                }

                @Override
                public float getToughness() {
                    return toughness;
                }

                @Override
                public float getKnockbackResistance() {
                    return knockbackResistance;
                }

                @Override
                public int getDurabilityForType(Type type) {
                    return MAX_DAMAGE_ARRAY[type.ordinal()] * maxDamageFactor;
                }

                @Override
                public int getDefenseForType(Type type) {
                    return reduction[type.ordinal()];
                }

                public String getTextureName() {
                    return name.substring(name.indexOf(':') + 1);
                }
            }
        """.trimIndent())
        armorDir.resolve("BaseArmorItem.java").writeText("""
            package com.example.armor;

            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;

            public class BaseArmorItem extends ArmorItem {
                public BaseArmorItem(ArmorMaterial material, ArmorItem.Type type, Properties properties) {
                    super(material, type, properties.stacksTo(1));
                }

                public boolean isCardboard() {
                    return getMaterial() == ArmorMaterials.CARDBOARD;
                }

                public String texture() {
                    return ((ArmorMaterials) getMaterial()).getTextureName();
                }
            }
        """.trimIndent())

        BuildSystemPass().apply(tempDir)

        val materials = armorDir.resolve("ArmorMaterials.java").readText()
        val item = armorDir.resolve("BaseArmorItem.java").readText()
        val mod = modDir.resolve("ExampleMod.java").readText()
        assertTrue(materials.contains("public final class ArmorMaterials {"), materials)
        assertTrue(materials.contains("DeferredRegister.create(Registries.ARMOR_MATERIAL, ExampleMod.ID)"), materials)
        assertTrue(
            materials.contains(
                "public static final DeferredHolder<ArmorMaterial, ArmorMaterial> COPPER = " +
                    "register(Legacy.COPPER, () -> BuiltInRegistries.SOUND_EVENT.wrapAsHolder(Legacy.COPPER.getEquipSound()), false);"
            ),
            materials
        )
        assertTrue(materials.contains("enum Legacy {"), materials)
        assertTrue(materials.contains("private Legacy(String name, int maxDamageFactor"), materials)
        assertTrue(materials.contains("defense.put(type, legacy.getDefenseForType(type));"), materials)
        assertTrue(materials.contains("properties.durability(legacy.getDurabilityForType(type))"), materials)
        assertTrue(materials.contains("public static String getTextureName(Holder<ArmorMaterial> material) {"), materials)
        assertFalse(materials.contains("implements ArmorMaterial"), materials)
        assertFalse(materials.contains("ArmorItem.Type.BODY"), materials)
        assertTrue(item.contains("Holder<ArmorMaterial> material"), item)
        assertTrue(item.contains("super(material, type, ArmorMaterials.withDurability(properties.stacksTo(1), material, type));"), item)
        assertTrue(item.contains("getMaterial().is(ArmorMaterials.CARDBOARD.getKey())"), item)
        assertTrue(item.contains("ArmorMaterials.getTextureName(this.material)"), item)
        assertTrue(mod.contains("ArmorMaterials.ARMOR_MATERIALS.register(modEventBus);"), mod)
    }
}
