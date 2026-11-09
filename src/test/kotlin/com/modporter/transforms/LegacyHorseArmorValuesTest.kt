package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyHorseArmorValues
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyHorseArmorValuesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `canonical horse armor calls use the native body armor getter`() {
        val source = """
            package example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.animal.horse.Horse;

            class Haunting {
                void convert(Entity entity) {
                    if (entity instanceof Horse horse) {
                        if (!horse.getArmor()
                            .isEmpty())
                            horse.spawnAtLocation(horse.getArmor());
                    }
                }
            }
        """.trimIndent()

        val migrated = passFor(source).migrateLegacyHorseArmorValues(source)

        assertEquals(2, Regex("""\bhorse\.getBodyArmorItem\(\)""").findAll(migrated).count(), migrated)
        assertFalse(migrated.contains("horse.getArmor()"), migrated)
        assertTrue(migrated.contains("horse.spawnAtLocation(horse.getBodyArmorItem());"), migrated)
    }

    @Test
    fun `qualified cast field method and this receivers retain their exact evaluation shapes`() {
        val source = """
            package example;

            import net.minecraft.world.entity.animal.horse.Horse;
            import net.minecraft.world.item.ItemStack;

            class Access extends Horse {
                private Horse field;

                private Horse nextHorse() {
                    return field;
                }

                ItemStack values(Object value, Horse nullable) {
                    ItemStack one = nextHorse().getArmor();
                    ItemStack two = ((Horse) value).getArmor();
                    ItemStack three = field.getArmor();
                    ItemStack four = nullable.getArmor();
                    ItemStack five = this.getArmor();
                    ItemStack six =
                        ((net.minecraft.world.entity.animal.horse.Horse) value).getArmor();
                    return one;
                }
            }
        """.trimIndent()

        val migrated = passFor(source).migrateLegacyHorseArmorValues(source)

        assertEquals(6, Regex("""\bgetBodyArmorItem\(\)""").findAll(migrated).count(), migrated)
        assertEquals(
            Regex("""\bnextHorse\(\)""").findAll(source).count(),
            Regex("""\bnextHorse\(\)""").findAll(migrated).count(),
            migrated
        )
        assertTrue(migrated.contains("nextHorse().getBodyArmorItem()"), migrated)
        assertTrue(migrated.contains("nullable.getBodyArmorItem()"), migrated)
        assertTrue(migrated.contains("this.getBodyArmorItem()"), migrated)
        assertFalse(migrated.contains(".getArmor()"), migrated)
    }

    @Test
    fun `a project subclass with the inherited vanilla getter migrates`() {
        val source = """
            package example;

            import net.minecraft.world.entity.animal.horse.Horse;
            import net.minecraft.world.item.ItemStack;

            class SpecialHorse extends Horse {
            }

            class Access {
                ItemStack armor(SpecialHorse horse) {
                    return horse.getArmor();
                }
            }
        """.trimIndent()

        val migrated = passFor(source).migrateLegacyHorseArmorValues(source)

        assertTrue(migrated.contains("return horse.getBodyArmorItem();"), migrated)
    }

    @Test
    fun `a project horse armor override blocks virtual call migration`() {
        val custom = """
            package example;

            import net.minecraft.world.entity.animal.horse.Horse;
            import net.minecraft.world.item.ItemStack;

            class SpecialHorse extends Horse {
                @Override
                public ItemStack getArmor() {
                    return customArmor();
                }

                private ItemStack customArmor() {
                    return ItemStack.EMPTY;
                }
            }
        """.trimIndent()
        val caller = """
            package example;

            import net.minecraft.world.entity.animal.horse.Horse;
            import net.minecraft.world.item.ItemStack;

            class Access {
                ItemStack armor(Horse horse) {
                    return horse.getArmor();
                }
            }
        """.trimIndent()
        val pass = passFor(custom, caller)

        assertEquals(caller, pass.migrateLegacyHorseArmorValues(caller))
        assertEquals(custom, pass.migrateLegacyHorseArmorValues(custom))
    }

    @Test
    fun `lookalikes unknown calls overloads and method references stay unchanged`() {
        val source = """
            package example;

            import java.util.function.Function;
            import net.minecraft.world.entity.animal.horse.Horse;
            import net.minecraft.world.item.ItemStack;

            class Other {
                ItemStack getArmor() {
                    return ItemStack.EMPTY;
                }
            }

            class Access {
                String text = "horse.getArmor()";

                <T> void values(Horse horse, Other other, T unknown) {
                    // horse.getArmor() is documentation.
                    horse.getArmor(1);
                    other.getArmor();
                    unknown.getArmor();
                    Function<Horse, ItemStack> unbound = Horse::getArmor;
                    java.util.function.Supplier<ItemStack> bound = horse::getArmor;
                }
            }
        """.trimIndent()

        assertEquals(source, passFor(source).migrateLegacyHorseArmorValues(source))
    }

    @Test
    fun `wildcard only horse names remain unresolved and migration is idempotent`() {
        val wildcard = """
            package example;

            import net.minecraft.world.entity.animal.horse.*;

            class Access {
                Object armor(Horse horse) {
                    return horse.getArmor();
                }
            }
        """.trimIndent()
        assertEquals(wildcard, passFor(wildcard).migrateLegacyHorseArmorValues(wildcard))

        val exact = """
            package example;
            class Access {
                Object armor(net.minecraft.world.entity.animal.horse.Horse horse) {
                    return horse.getArmor();
                }
            }
        """.trimIndent()
        val pass = passFor(exact)
        val migrated = pass.migrateLegacyHorseArmorValues(exact)
        assertEquals(migrated, pass.migrateLegacyHorseArmorValues(migrated))
    }

    private fun passFor(vararg sources: String): StructuralRefactorPass {
        val files = sources.mapIndexed { index, source ->
            directory.resolve("HorseArmorFixture$index.java").also { it.writeText(source) }
        }
        return StructuralRefactorPass().also { pass ->
            pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        }
    }
}
