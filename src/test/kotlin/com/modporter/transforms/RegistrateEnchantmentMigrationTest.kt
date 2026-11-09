package com.modporter.transforms

import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistrateEnchantmentMigrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `registrate enchantments derive resource keys and replace marker interfaces with data tags`() {
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
        write("com/example/AllEnchantments.java", """
            package com.example;

            import com.example.armor.CapacityEnchantment;
            import com.example.cannon.PotatoRecoveryEnchantment;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment.Rarity;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public final class AllEnchantments {
                private static final ExampleRegistrate REGISTRATE = ExampleMod.registrate();

                public static final RegistryEntry<PotatoRecoveryEnchantment> POTATO_RECOVERY =
                    REGISTRATE.object("potato_recovery")
                        .enchantment(EnchantmentCategory.BOW, PotatoRecoveryEnchantment::new)
                        .addSlots(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)
                        .lang("Potato Recovery")
                        .rarity(Rarity.UNCOMMON)
                        .register();

                public static final RegistryEntry<CapacityEnchantment> CAPACITY =
                    REGISTRATE.object("capacity")
                        .enchantment(EnchantmentCategory.ARMOR_CHEST, CapacityEnchantment::new)
                        .addSlots(EquipmentSlot.CHEST)
                        .lang("Capacity")
                        .rarity(Rarity.COMMON)
                        .register();
            }
        """)
        write("com/example/AllItems.java", """
            package com.example;

            import com.example.armor.BacktankItem;
            import com.example.cannon.PotatoCannonItem;

            public final class AllItems {
                private static final ExampleRegistrate REGISTRATE = ExampleMod.registrate();

                public static final ItemEntry<PotatoCannonItem> POTATO_CANNON =
                    REGISTRATE.item("potato_cannon", PotatoCannonItem::new).register();

                public static final ItemEntry<? extends BacktankItem> COPPER_BACKTANK =
                    REGISTRATE.item("copper_backtank", properties -> new BacktankItem(properties)).register();

                public static final ItemEntry<? extends BacktankItem> NETHERITE_BACKTANK =
                    REGISTRATE.item("netherite_backtank", properties -> new BacktankItem.Layered(properties)).register();
            }
        """)
        write("com/example/cannon/PotatoCannonItem.java", """
            package com.example.cannon;

            public class PotatoCannonItem extends Item {
            }
        """)
        val potatoEnchantment = write("com/example/cannon/PotatoRecoveryEnchantment.java", """
            package com.example.cannon;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class PotatoRecoveryEnchantment extends Enchantment {
                public PotatoRecoveryEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }

                @Override
                public boolean canApplyAtEnchantingTable(ItemStack stack) {
                    return stack.getItem() instanceof PotatoCannonItem;
                }
            }
        """)
        val capacityEnchantment = write("com/example/armor/CapacityEnchantment.java", """
            package com.example.armor;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class CapacityEnchantment extends Enchantment {
                public CapacityEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }

                @Override
                public boolean canApplyAtEnchantingTable(ItemStack stack) {
                    return stack.getItem() instanceof ICapacityEnchantable;
                }

                public interface ICapacityEnchantable {
                }
            }
        """)
        val backtankItem = write("com/example/armor/BacktankItem.java", """
            package com.example.armor;

            import com.example.armor.CapacityEnchantment.ICapacityEnchantable;

            public class BacktankItem extends Item implements ICapacityEnchantable {
                public BacktankItem(Properties properties) {
                    super(properties);
                }

                public static class Layered extends BacktankItem {
                    public Layered(Properties properties) {
                        super(properties);
                    }
                }
            }
        """)

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        val registrations = tempDir.resolve("src/main/java/com/example/AllEnchantments.java").readText()
        assertTrue(
            result.changes.any { it.ruleId == "text-custom-enchantment-data" },
            result.errors.joinToString("\n")
        )
        assertTrue(
            result.changes.any { it.ruleId == "custom-enchantment-resourcekey-context" },
            result.errors.joinToString("\n")
        )
        assertTrue(
            result.changes.any { it.ruleId == "text-enchantment-marker-to-item-tag" },
            result.errors.joinToString("\n")
        )
        assertFalse(registrations.contains(".enchantment("), registrations)
        assertFalse(registrations.contains("REGISTRATE"), registrations)
        assertFalse(registrations.contains("Enchantment.Rarity"), registrations)
        assertTrue(registrations.contains("ResourceKey<Enchantment> POTATO_RECOVERY"), registrations)
        assertTrue(registrations.contains("ResourceKey<Enchantment> CAPACITY"), registrations)
        assertTrue(registrations.contains("java.util.List.of(POTATO_RECOVERY, CAPACITY)"), registrations)

        val potatoJson = generated("enchantment/potato_recovery.json")
        assertTrue(potatoJson.contains(""""max_level": 3"""), potatoJson)
        assertTrue(potatoJson.contains(""""weight": 5"""), potatoJson)
        assertTrue(potatoJson.contains(""""supported_items": "#example:enchantable/potato_cannon""""))
        assertTrue(potatoJson.contains(""""slots": [
    "hand"
  ]"""))
        assertTrue(generated("tags/item/enchantable/potato_cannon.json").contains(""""example:potato_cannon""""))

        val capacityJson = generated("enchantment/capacity.json")
        assertTrue(capacityJson.contains(""""max_level": 3"""), capacityJson)
        assertTrue(capacityJson.contains(""""weight": 10"""), capacityJson)
        assertTrue(capacityJson.contains(""""supported_items": "#example:enchantable/capacity""""))
        assertTrue(capacityJson.contains(""""slots": [
    "chest"
  ]"""))
        val capacityTag = generated("tags/item/enchantable/capacity.json")
        assertTrue(capacityTag.contains(""""example:copper_backtank""""))
        assertTrue(capacityTag.contains(""""example:netherite_backtank""""))

        assertFalse(potatoEnchantment.exists(), "unreferenced data-driven Enchantment subclass should be removed")
        assertFalse(capacityEnchantment.exists(), "data tags replace the obsolete marker-interface container")
        val migratedBacktank = backtankItem.readText()
        assertFalse(migratedBacktank.contains("ICapacityEnchantable"), migratedBacktank)
        assertFalse(
            result.errors.any { it.contains("Cannot derive custom enchantment data") },
            result.errors.joinToString("\n")
        )
    }

    @Test
    fun `registrate enchantments fail closed when the registrate namespace cannot be sourced`() {
        write("com/example/AllEnchantments.java", """
            package com.example;

            public final class AllEnchantments {
                private static final ExampleRegistrate REGISTRATE = Registrates.unknown();

                public static final RegistryEntry<GhostEnchantment> GHOST =
                    REGISTRATE.object("ghost")
                        .enchantment(EnchantmentCategory.BOW, GhostEnchantment::new)
                        .addSlots(EquipmentSlot.MAINHAND)
                        .rarity(Enchantment.Rarity.RARE)
                        .register();
            }
        """)
        write("com/example/GhostEnchantment.java", """
            package com.example;

            public class GhostEnchantment extends Enchantment {
                public GhostEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }
            }
        """)

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(
            result.errors.any { it.contains("Registrate receiver 'REGISTRATE' is unresolved") },
            result.errors.joinToString("\n")
        )
        assertFalse(
            tempDir.resolve("src/generated/resources/data/example/enchantment/ghost.json").exists(),
            "unresolved Registrate namespace must not produce guessed data"
        )
    }

    @Test
    fun `registrate equipment slots cannot be inferred from string literals`() {
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
        write("com/example/AllEnchantments.java", """
            package com.example;

            public final class AllEnchantments {
                private static final ExampleRegistrate REGISTRATE = ExampleMod.registrate();

                public static final RegistryEntry<GhostEnchantment> GHOST =
                    REGISTRATE.object("ghost")
                        .enchantment(EnchantmentCategory.BOW, GhostEnchantment::new)
                        .addSlots(parse("EquipmentSlot.MAINHAND"))
                        .rarity(Enchantment.Rarity.RARE)
                        .register();
            }
        """)
        write("com/example/GhostEnchantment.java", """
            package com.example;

            public class GhostEnchantment extends Enchantment {
                public GhostEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }
            }
        """)

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(
            result.errors.any { it.contains("equipment slots are unresolved") },
            result.errors.joinToString("\n")
        )
        assertFalse(
            tempDir.resolve("src/generated/resources/data/example/enchantment/ghost.json").exists(),
            "slot names inside string literals must not become enchantment definition evidence"
        )
    }

    @Test
    fun `registrate builder values require direct constructor forwarding`() {
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
        write("com/example/AllEnchantments.java", """
            package com.example;

            public final class AllEnchantments {
                private static final ExampleRegistrate REGISTRATE = ExampleMod.registrate();

                public static final RegistryEntry<MisdirectedEnchantment> MISDIRECTED =
                    REGISTRATE.object("misdirected")
                        .enchantment(EnchantmentCategory.BOW, MisdirectedEnchantment::new)
                        .addSlots(EquipmentSlot.MAINHAND)
                        .rarity(Enchantment.Rarity.RARE)
                        .register();
            }
        """)
        write("com/example/MisdirectedEnchantment.java", """
            package com.example;

            public class MisdirectedEnchantment extends Enchantment {
                public MisdirectedEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(Rarity.COMMON, category, slots);
                }
            }
        """)

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(
            result.errors.any { it.contains("does not directly forward rarity, category, and slots") },
            result.errors.joinToString("\n")
        )
        assertFalse(
            tempDir.resolve("src/generated/resources/data/example/enchantment/misdirected.json").exists(),
            "builder values must not replace constructor behavior unless the constructor forwards them"
        )
    }

    private fun write(relativePath: String, content: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content.trimIndent())
        return file
    }

    private fun generated(relativePath: String): String =
        tempDir.resolve("src/generated/resources/data/example").resolve(relativePath).readText()
}
