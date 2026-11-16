package com.modporter.transforms

import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyEnchantmentVanillaTagsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `legacy enchantment flags select the vanilla enchantment tags`() {
        write("com/example/ExampleMod.java", """
            package com.example;

            public final class ExampleMod {
                public static final String ID = "example";
                private static final ExampleRegistrate REGISTRATE = ExampleRegistrate.create(ID);

                public static ExampleRegistrate registrate() {
                    return REGISTRATE;
                }
            }
        """)
        val entries = listOf("plain", "cursed", "hidden", "configured", "bookless")
        write("com/example/ModEnchantments.java", """
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment.Rarity;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public final class ModEnchantments {
                private static final ExampleRegistrate REGISTRATE = ExampleMod.registrate();
${entries.joinToString("\n") { name ->
            val type = className(name)
            """
                public static final RegistryEntry<$type> ${name.uppercase()} =
                    REGISTRATE.object("$name")
                        .enchantment(EnchantmentCategory.ARMOR_CHEST, $type::new)
                        .addSlots(EquipmentSlot.CHEST)
                        .rarity(Rarity.RARE)
                        .register();"""
        }}
            }
        """)
        enchantment("plain", "")
        enchantment(
            "cursed",
            """
                @Override
                public boolean isTreasureOnly() {
                    return true;
                }

                @Override
                public boolean isCurse() {
                    return true;
                }
            """
        )
        enchantment(
            "hidden",
            """
                @Override
                public boolean isTreasureOnly() {
                    return true;
                }

                @Override
                public boolean isDiscoverable() {
                    return false;
                }

                @Override
                public boolean isTradeable() {
                    return false;
                }
            """
        )
        enchantment(
            "configured",
            """
                @Override
                public boolean isTradeable() {
                    return ExampleConfig.TRADEABLE;
                }
            """
        )
        enchantment(
            "bookless",
            """
                @Override
                public boolean isAllowedOnBooks() {
                    return false;
                }
            """
        )
        val existing = tempDir.resolve("src/generated/resources/data/minecraft/tags/enchantment/non_treasure.json")
        existing.parent.createDirectories()
        existing.writeText("{\n  \"values\": [\n    \"other:kept\"\n  ]\n}\n")

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        entries.forEach { name ->
            assertTrue(generated("example/enchantment/$name.json").isNotBlank(), result.errors.joinToString("\n"))
        }
        assertEquals(listOf("other:kept", "example:plain"), tagValues("non_treasure"))
        assertEquals(listOf("example:cursed"), tagValues("curse"))
        assertEquals(listOf("example:cursed", "example:hidden"), tagValues("treasure"))
        assertEquals(listOf("example:cursed"), tagValues("on_random_loot"))
        assertEquals(listOf("example:cursed"), tagValues("tradeable"))
        listOf("in_enchanting_table", "on_mob_spawn_equipment", "on_traded_equipment").forEach {
            assertFalse(tag(it).exists(), it)
        }
        assertTrue(
            result.errors.any {
                it.contains("Cannot derive enchantment tags for example:configured") && it.contains("isTradeable")
            },
            result.errors.joinToString("\n")
        )
        assertTrue(
            result.errors.any {
                it.contains("Cannot derive enchantment tags for example:bookless") && it.contains("isAllowedOnBooks")
            },
            result.errors.joinToString("\n")
        )
        assertTrue(result.changes.any { it.ruleId == "text-custom-enchantment-vanilla-tag" })
    }

    @Test
    fun `discoverable but untradeable enchantments keep table and loot without the trade tag`() {
        write("com/example/ExampleMod.java", """
            package com.example;

            public final class ExampleMod {
                public static final String ID = "example";
                private static final ExampleRegistrate REGISTRATE = ExampleRegistrate.create(ID);

                public static ExampleRegistrate registrate() {
                    return REGISTRATE;
                }
            }
        """)
        write("com/example/ModEnchantments.java", """
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment.Rarity;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public final class ModEnchantments {
                private static final ExampleRegistrate REGISTRATE = ExampleMod.registrate();

                public static final RegistryEntry<UntradedEnchantment> UNTRADED =
                    REGISTRATE.object("untraded")
                        .enchantment(EnchantmentCategory.ARMOR_CHEST, UntradedEnchantment::new)
                        .addSlots(EquipmentSlot.CHEST)
                        .rarity(Rarity.RARE)
                        .register();
            }
        """)
        enchantment(
            "untraded",
            """
                @Override
                public boolean isTradeable() {
                    return false;
                }
            """
        )

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertFalse(tag("non_treasure").exists(), result.errors.joinToString("\n"))
        assertFalse(tag("tradeable").exists())
        listOf("in_enchanting_table", "on_mob_spawn_equipment", "on_traded_equipment", "on_random_loot").forEach {
            assertEquals(listOf("example:untraded"), tagValues(it), it)
        }
    }

    private fun className(name: String): String = name.replaceFirstChar { it.uppercase() } + "Enchantment"

    private fun enchantment(name: String, members: String) {
        val type = className(name)
        write("com/example/$type.java", """
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class $type extends Enchantment {
                public $type(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }
${members.trimIndent().prependIndent("                ")}
            }
        """)
    }

    private fun write(relativePath: String, content: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content.trimIndent())
        return file
    }

    private fun generated(relativePath: String): String =
        tempDir.resolve("src/generated/resources/data").resolve(relativePath).readText()

    private fun tag(name: String): Path =
        tempDir.resolve("src/generated/resources/data/minecraft/tags/enchantment/$name.json")

    private fun tagValues(name: String): List<String> =
        Regex("\"([^\"]+)\"").findAll(tag(name).readText()).map { it.groupValues[1] }
            .filter { it != "values" }.toList()
}
