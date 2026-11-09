package com.modporter.transforms

import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Custom enchantment data derived across packages, as mods lay registries out in practice. */
class CustomEnchantmentCrossPackageTest {
    @TempDir
    lateinit var tempDir: Path

    private fun java(relative: String, source: String) {
        tempDir.resolve("src/main/java").resolve(relative).also {
            it.parent.createDirectories()
            it.writeText(source.trimIndent())
        }
    }

    @Test
    fun `category item class resolves where the category is declared and super min cost binds to the parent`() {
        java(
            "com/example/ExampleMod.java",
            """
            package com.example;
            public final class ExampleMod {
                public static final String MODID = "example";
            }
            """
        )
        java(
            "com/example/items/Wand.java",
            """
            package com.example.items;
            import net.minecraft.world.item.Item;
            public class Wand extends Item {
                public Wand(Properties properties) { super(properties); }
            }
            """
        )
        java(
            "com/example/init/ModItems.java",
            """
            package com.example.init;
            import com.example.ExampleMod;
            import com.example.items.*;
            import net.minecraft.world.item.Item;
            import net.minecraftforge.registries.DeferredRegister;
            import net.minecraftforge.registries.ForgeRegistries;
            import net.minecraftforge.registries.RegistryObject;
            public final class ModItems {
                public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExampleMod.MODID);
                public static final RegistryObject<Item> WAND = ITEMS.register("magic_wand", () -> new Wand(new Item.Properties()));
            }
            """
        )
        java(
            "com/example/init/ModEnchantments.java",
            """
            package com.example.init;
            import com.example.ExampleMod;
            import com.example.enchantments.*;
            import com.example.items.*;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraftforge.registries.DeferredRegister;
            import net.minecraftforge.registries.ForgeRegistries;
            import net.minecraftforge.registries.RegistryObject;
            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final RegistryObject<Enchantment> SPARK = ENCHANTMENTS.register("spark", () -> new Spark(Enchantment.Rarity.COMMON));
                public static final EnchantmentCategory WAND = EnchantmentCategory.create("wand", item -> item instanceof Wand);
            }
            """
        )
        java(
            "com/example/enchantments/Spark.java",
            """
            package com.example.enchantments;
            import com.example.init.ModEnchantments;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            public class Spark extends Enchantment {
                public Spark(Rarity rarity) {
                    super(rarity, ModEnchantments.WAND, new EquipmentSlot[] {EquipmentSlot.MAINHAND});
                }
                @Override
                public int getMaxLevel() {
                    return 3;
                }
                public int getMinCost(int level) {
                    return 5 + (level - 1) * 8;
                }
                public int getMaxCost(int level) {
                    return super.getMinCost(level) + 50;
                }
            }
            """
        )

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(
            result.errors.none { "enchantment" in it.lowercase() },
            result.errors.joinToString("\n")
        )
        val json = tempDir.resolve("src/generated/resources/data/example/enchantment/spark.json").readText()
        assertTrue(json.contains(""""supported_items": "#example:enchantable/magic_wand""""), json)
        fun cost(field: String, level: Int): Int {
            val start = json.indexOf("\"$field\"")
            val block = json.substring(start, json.indexOf('}', start) + 1)
            val base = Regex("\"base\"\\s*:\\s*(-?\\d+)").find(block)!!.groupValues[1].toInt()
            val per = Regex("\"per_level_above_first\"\\s*:\\s*(-?\\d+)").find(block)!!.groupValues[1].toInt()
            return base + per * (level - 1)
        }
        for (level in 1..3) {
            assertEquals(5 + (level - 1) * 8, cost("min_cost", level))
            // Forge 1.20.1 Enchantment.getMinCost(level) is 1 + level * 10.
            assertEquals(1 + level * 10 + 50, cost("max_cost", level))
        }
    }
}
