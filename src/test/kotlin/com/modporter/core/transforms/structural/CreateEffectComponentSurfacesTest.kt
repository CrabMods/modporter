package com.modporter.core.transforms.structural

import com.google.gson.JsonParseException
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateEffectComponentSurfacesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `Create effect and component boundaries retain their source behavior`() {
        writeCompleteFixture()
        val before = javaFiles().associateWith { it.readText() }

        assertEquals(6, migrate(dryRun = true).size)
        assertEquals(before, javaFiles().associateWith { it.readText() })
        assertEquals(6, migrate(dryRun = false).size)

        val fluid = read(
            "com/simibubi/create/content/fluids/potion/PotionFluid.java"
        )
        assertFalse(fluid.contains("removeChildTag"), fluid)
        assertTrue(fluid.contains("DataComponents.CUSTOM_DATA"), fluid)
        assertTrue(fluid.contains("modporterPotionData.copyTag()"), fluid)
        assertTrue(fluid.contains("modporterPotionTag.remove(\"Potion\")"), fluid)
        assertTrue(fluid.contains("if (modporterPotionData != null)"), fluid)
        assertFalse(fluid.contains("new CompoundTag"), fluid)

        val milk = read(
            "com/simibubi/create/impl/effect/MilkEffectHandler.java"
        )
        assertFalse(milk.contains("curePotionEffects"), milk)
        assertFalse(milk.contains("new ItemStack"), milk)
        assertFalse(milk.contains("import net.minecraft.world.item.ItemStack;"), milk)
        assertFalse(milk.contains("import net.minecraft.world.item.Items;"), milk)
        assertTrue(
            compact(milk).contains(
                "entity.removeEffectsCuredBy(EffectCures.MILK)"
            ),
            milk
        )

        val effects = read(
            "com/simibubi/create/impl/effect/PotionEffectHandler.java"
        )
        assertTrue(
            compact(effects).contains(
                "return java.util.stream.StreamSupport.stream(" +
                    "bottle.getOrDefault(DataComponents.POTION_CONTENTS, " +
                    "PotionContents.EMPTY).getAllEffects().spliterator(), false)" +
                    ".collect(java.util.stream.Collectors.toCollection(" +
                    "java.util.ArrayList::new))"
            ),
            effects
        )

        val tooltip = read(
            "com/simibubi/create/foundation/data/recipe/" +
                "LogStrippingFakeRecipes.java"
        )
        assertFalse(tooltip.contains("TooltipPart"), tooltip)
        assertTrue(
            compact(tooltip).contains(
                "axe.set(DataComponents.ATTRIBUTE_MODIFIERS, " +
                    "axe.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, " +
                    "ItemAttributeModifiers.EMPTY).withTooltip(false))"
            ),
            tooltip
        )

        val deployer = read(
            "com/simibubi/create/content/kinetics/deployer/DeployerHandler.java"
        )
        assertFalse(deployer.contains("stack.isEdible()"), deployer)
        assertTrue(
            compact(deployer).contains(
                "if (stack.has(DataComponents.FOOD))"
            ),
            deployer
        )
        assertTrue(
            compact(deployer).contains(
                "if (foodProperties != null && " +
                    "playerEntity.canEat(foodProperties.canAlwaysEat()))"
            ),
            deployer
        )

        val textAudit = read(
            "com/simibubi/create/foundation/CreateNBTProcessors.java"
        )
        assertFalse(
            textAudit.contains("NBTProcessors.textComponentHasClickEvent"),
            textAudit
        )
        assertEquals(
            2,
            Regex(
                """private static boolean\s+""" +
                    Regex.escape(
                        "modporter\$legacyTextComponentHasClickEvent"
                    ) +
                    """\("""
            ).findAll(textAudit).count()
        )
        assertTrue(textAudit.contains("object.has(\"extra\")"), textAudit)
        assertTrue(textAudit.contains("reader.setLenient(false)"), textAudit)
        assertTrue(textAudit.contains("java.util.Objects.requireNonNull"), textAudit)
        assertFalse(textAudit.contains("RegistryAccess.EMPTY"), textAudit)

        val migrated = javaFiles().associateWith { it.readText() }
        assertTrue(migrate(dryRun = false).isEmpty())
        assertEquals(migrated, javaFiles().associateWith { it.readText() })
    }

    @Test
    fun `milk cure migration rejects reassigned cure items transactionally`() {
        val source =
            """
            package com.simibubi.create.impl.effect;

            import java.util.List;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;

            class MilkEffectHandler {
                void apply(List<LivingEntity> entities, ItemStack replacement) {
                    ItemStack curativeItem = new ItemStack(Items.MILK_BUCKET);
                    curativeItem = replacement;
                    for (LivingEntity entity : entities)
                        entity.curePotionEffects(curativeItem);
                }
            }
            """.trimIndent() + "\n"
        write("com/simibubi/create/impl/effect/MilkEffectHandler.java", source)

        assertFailsWith<IllegalStateException> {
            migrate(dryRun = false)
        }
        assertEquals(
            source,
            read("com/simibubi/create/impl/effect/MilkEffectHandler.java")
        )
    }

    @Test
    fun `milk cure migration rejects foreign receivers transactionally`() {
        val source =
            """
            package com.simibubi.create.impl.effect;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;

            class MilkEffectHandler {
                void apply(Other entity) {
                    ItemStack curativeItem = new ItemStack(Items.MILK_BUCKET);
                    entity.curePotionEffects(curativeItem);
                }
            }

            class Other {
                void curePotionEffects(ItemStack stack) {}
            }
            """.trimIndent() + "\n"
        write("com/simibubi/create/impl/effect/MilkEffectHandler.java", source)

        assertFailsWith<IllegalStateException> {
            migrate(dryRun = false)
        }
        assertEquals(
            source,
            read("com/simibubi/create/impl/effect/MilkEffectHandler.java")
        )
    }

    @Test
    fun `deployer food migration rejects an unpaired stack guard transactionally`() {
        val source =
            """
            package com.simibubi.create.content.kinetics.deployer;

            import net.minecraft.world.item.ItemStack;

            class DeployerHandler {
                boolean deploy(ItemStack stack) {
                    return stack.isEdible();
                }
            }
            """.trimIndent() + "\n"
        write(
            "com/simibubi/create/content/kinetics/deployer/DeployerHandler.java",
            source
        )

        assertFailsWith<IllegalStateException> {
            migrate(dryRun = false)
        }
        assertEquals(
            source,
            read(
                "com/simibubi/create/content/kinetics/deployer/" +
                    "DeployerHandler.java"
            )
        )
    }

    @Test
    fun `stored text audit follows legacy component siblings without scanning metadata`() {
        write(
            "com/simibubi/create/foundation/CreateNBTProcessors.java",
            """
            package com.simibubi.create.foundation;

            import net.createmod.catnip.nbt.NBTProcessors;
            import net.minecraft.nbt.Tag;

            public class CreateNBTProcessors {
                public static boolean audit(String entry) {
                    return NBTProcessors.textComponentHasClickEvent(entry);
                }

                public static boolean audit(Tag entry) {
                    return NBTProcessors.textComponentHasClickEvent(
                        entry.getAsString());
                }
            }
            """
        )
        write(
            "net/createmod/catnip/nbt/NBTProcessors.java",
            """
            package net.createmod.catnip.nbt;
            public final class NBTProcessors {}
            """
        )
        write(
            "net/minecraft/nbt/Tag.java",
            """
            package net.minecraft.nbt;
            public interface Tag {
                String getAsString();
            }
            """
        )

        assertEquals(1, migrate(dryRun = false).size)
        val classes = compileJavaSources()
        URLClassLoader(
            arrayOf(classes.toUri().toURL()),
            javaClass.classLoader
        ).use { loader ->
            val owner = loader.loadClass(
                "com.simibubi.create.foundation.CreateNBTProcessors"
            )
            val audit = owner.getDeclaredMethod("audit", String::class.java)

            fun hasClick(json: String): Boolean =
                audit.invoke(null, json) as Boolean

            fun failure(json: String): Throwable =
                assertFailsWith<InvocationTargetException> {
                    hasClick(json)
                }.cause ?: error("reflection failure had no cause")

            fun assertJsonFailure(json: String) {
                val cause = failure(json)
                assertTrue(cause is JsonParseException, cause.toString())
            }

            assertFalse(hasClick(""))
            assertTrue(
                hasClick(
                    """{"text":"root","clickEvent":""" +
                        """{"action":"run_command","value":"/say root"}}"""
                )
            )
            assertTrue(
                hasClick(
                    """[{"text":"safe"},{"text":"array","clickEvent":""" +
                        """{"action":"open_url","value":"https://example.invalid"}}]"""
                )
            )
            assertTrue(
                hasClick(
                    """{"text":"safe","extra":[{"text":"nested","extra":[""" +
                        """{"text":"click","clickEvent":""" +
                        """{"action":"suggest_command","value":"/help"}}]}]}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"hover","hoverEvent":{"action":"show_text",""" +
                        """"contents":{"text":"hidden","clickEvent":""" +
                        """{"action":"run_command","value":"/say hidden"}}}}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"translate":"key","with":[{"text":"argument",""" +
                        """"clickEvent":{"action":"run_command",""" +
                        """"value":"/say argument"}}]}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"metadata","custom":{"clickEvent":""" +
                        """{"action":"run_command","value":"/say metadata"}}}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"file","clickEvent":""" +
                        """{"action":"open_file","value":"secret.txt"}}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"first"} {"text":"ignored","clickEvent":""" +
                        """{"action":"run_command","value":"/ignored"}}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"color","color":"not_a_legacy_color"}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"flag","bold":[true]}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"translate":"key","with":[[]]}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"unknown-hover","hoverEvent":""" +
                        """{"action":"unknown","contents":{"missing":"text"}}}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"item-hover","hoverEvent":{"action":"show_item",""" +
                        """"contents":{"id":"minecraft:stone","count":1,""" +
                        """"tag":"definitely not snbt"}}}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"entity-hover","hoverEvent":""" +
                        """{"action":"show_entity","contents":"ignored"}}"""
                )
            )
            assertTrue(
                hasClick(
                    """{"text":"root","extra":[{"text":"click","clickEvent":""" +
                        """{"action":"run_command","value":"/first"}},[]]}"""
                )
            )

            assertJsonFailure("""{"text":""")
            assertJsonFailure("""/* comment */{"text":"strict"}""")
            assertJsonFailure("""{text:"strict"}""")
            assertJsonFailure("""{'text':'strict'}""")
            assertJsonFailure("""{"extra":[{"text":"child"}]}""")
            assertJsonFailure("""{"text":"root","extra":[]}""")
            assertJsonFailure("""{"translate":"key","with":{}}""")
            assertJsonFailure("""{"score":{"name":"player"}}""")
            assertJsonFailure("""{"selector":"@a","separator":null}""")
            assertJsonFailure(
                """{"text":"root","extra":[{"text":"click","clickEvent":""" +
                    """{"action":"run_command","value":"/first"}},{"extra":[]}]}"""
            )
            assertJsonFailure(
                """{"nbt":"path","storage":"Bad Namespace:Path"}"""
            )
            assertJsonFailure("""{"text":"font","font":"Bad:Font"}""")
            assertJsonFailure("""{"text":"click","clickEvent":"wrong"}""")
            assertJsonFailure(
                """{"text":"click","clickEvent":{"action":null,"value":"x"}}"""
            )
            assertJsonFailure(
                """{"text":"item","hoverEvent":{"action":"show_item",""" +
                    """"contents":{"id":"minecraft:stone","count":"1"}}}"""
            )

            assertTrue(failure("null") is NullPointerException)
            assertTrue(failure("[]") is NullPointerException)
            assertTrue(
                failure("""{"text":"root","extra":[[]]}""") is
                    NullPointerException
            )
            assertTrue(
                failure(
                    """{"selector":"@a","separator":[]}"""
                ) is NullPointerException
            )
            assertTrue(
                failure(
                    """{"text":"item","hoverEvent":{"action":"show_item",""" +
                        """"value":{"text":"legacy","extra":[[]]}}}"""
                ) is NullPointerException
            )
            assertTrue(
                failure(
                    """{"text":"entity","hoverEvent":{"action":"show_entity",""" +
                        """"contents":{"type":"minecraft:pig","id":"bad-uuid"}}}"""
                ) is IllegalArgumentException
            )
        }
    }

    private fun writeCompleteFixture() {
        write(
            "com/simibubi/create/content/fluids/potion/PotionFluid.java",
            """
            package com.simibubi.create.content.fluids.potion;

            import net.neoforged.neoforge.fluids.FluidStack;

            class PotionFluid {
                static FluidStack finish(FluidStack fs, boolean empty) {
                    if (empty) {
                        fs.removeChildTag("Potion");
                        return fs;
                    }
                    return fs;
                }
            }
            """
        )
        write(
            "com/simibubi/create/impl/effect/MilkEffectHandler.java",
            """
            package com.simibubi.create.impl.effect;

            import java.util.List;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;

            class MilkEffectHandler {
                void apply(List<LivingEntity> entities) {
                    ItemStack curativeItem = new ItemStack(Items.MILK_BUCKET);
                    for (LivingEntity entity : entities)
                        entity.curePotionEffects(curativeItem);
                }
            }
            """
        )
        write(
            "com/simibubi/create/impl/effect/PotionEffectHandler.java",
            """
            package com.simibubi.create.impl.effect;

            import java.util.List;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.alchemy.PotionContents;

            class PotionEffectHandler {
                static List<MobEffectInstance> getEffects(ItemStack bottle) {
                    return bottle.getOrDefault(
                        DataComponents.POTION_CONTENTS,
                        PotionContents.EMPTY
                    ).getAllEffects();
                }
            }
            """
        )
        write(
            "com/simibubi/create/foundation/data/recipe/" +
                "LogStrippingFakeRecipes.java",
            """
            package com.simibubi.create.foundation.data.recipe;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;

            class LogStrippingFakeRecipes {
                static void hide(ItemStack axe) {
                    axe.hideTooltipPart(TooltipPart.MODIFIERS);
                }
            }
            """
        )
        write(
            "com/simibubi/create/content/kinetics/deployer/DeployerHandler.java",
            """
            package com.simibubi.create.content.kinetics.deployer;

            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.food.FoodProperties;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;

            class DeployerHandler {
                static boolean deploy(
                        ItemStack stack,
                        Item item,
                        Player player,
                        Player playerEntity) {
                    if (stack.isEdible()) {
                        FoodProperties foodProperties =
                            item.getFoodProperties(stack, player);
                        if (playerEntity.canEat(foodProperties.canAlwaysEat()))
                            return true;
                    }
                    return false;
                }
            }
            """
        )
        write(
            "com/simibubi/create/foundation/CreateNBTProcessors.java",
            """
            package com.simibubi.create.foundation;

            import java.util.List;
            import net.createmod.catnip.nbt.NBTProcessors;
            import net.minecraft.nbt.Tag;

            class CreateNBTProcessors {
                static boolean page(Tag inbt) {
                    return NBTProcessors.textComponentHasClickEvent(
                        inbt.getAsString());
                }

                static boolean clipboard(List<String> entries) {
                    for (String entry : entries)
                        if (NBTProcessors.textComponentHasClickEvent(entry))
                            return true;
                    return false;
                }
            }
            """
        )
    }

    private fun migrate(dryRun: Boolean): List<com.modporter.core.pipeline.Change> {
        val files = javaFiles()
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        return CreateEffectComponentSurfaces.migrate(pass, directory, dryRun)
    }

    private fun compileJavaSources(): Path {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "Tests require a JDK Java compiler"
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val classes = directory.resolve("compiled")
        classes.createDirectories()
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            val units = manager.getJavaFileObjectsFromPaths(javaFiles())
            val success = compiler.getTask(
                null,
                manager,
                diagnostics,
                listOf(
                    "-proc:none",
                    "-classpath",
                    System.getProperty("java.class.path"),
                    "-d",
                    classes.toString()
                ),
                null,
                units
            ).call()
            assertTrue(
                success,
                diagnostics.diagnostics.joinToString("\n") { it.toString() }
            )
        }
        return classes
    }

    private fun javaFiles(): List<Path> {
        val root = directory.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .sorted()
                .toList()
        }
    }

    private fun write(relative: String, source: String) {
        val file = directory.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent() + "\n")
    }

    private fun read(relative: String): String =
        directory.resolve("src/main/java").resolve(relative).readText()

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .replace(Regex("""\s*,\s*"""), ", ")
            .trim()
}
