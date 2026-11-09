package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateLegacyTagConventionMigration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateLegacyTagConventionMigrationTest {
    @Test
    fun `migrates the complete Create tag family and is idempotent`() {
        val project = project(legacyAllTags(), legacyMilling())
        val allTags = allTags(project)
        val milling = milling(project)

        val result = CreateLegacyTagConventionMigration.migrate(project, dryRun = false)

        assertEquals(2, result.changes.size, result.gates.joinToString())
        assertTrue(result.gates.isEmpty())
        val tagsAfter = allTags.readText()
        val millingAfter = milling.readText()
        assertTrue(tagsAfter.contains("NameSpace.MINECRAFT"))
        assertTrue(tagsAfter.contains("NameSpace.FORGE"))
        assertTrue(tagsAfter.contains("FORGE(\"c\")"))
        assertTrue(tagsAfter.contains("MINECRAFT(\"minecraft\")"))
        assertTrue(tagsAfter.contains("forgeItemTag(String path)"))
        assertTrue(tagsAfter.contains("STRIPPED_WOOD(FORGE, \"stripped_woods\")"))
        assertTrue(tagsAfter.contains("FLOUR(FORGE, \"flours\")"))
        assertTrue(tagsAfter.contains("WHEAT_FLOUR(FORGE, \"flours/wheat\")"))
        assertTrue(tagsAfter.contains("DOUGH(FORGE, \"foods/dough\")"))
        assertTrue(tagsAfter.contains("WHEAT_DOUGH(FORGE, \"foods/dough/wheat\")"))
        assertTrue(tagsAfter.contains("HELMET_ARMORS(MINECRAFT, \"head_armor\")"))
        assertTrue(tagsAfter.contains("CHESTPLATE_ARMORS(MINECRAFT, \"chest_armor\")"))
        assertTrue(tagsAfter.contains("LEGGING_ARMORS(MINECRAFT, \"leg_armor\")"))
        assertTrue(tagsAfter.contains("BOOT_ARMORS(MINECRAFT, \"foot_armor\")"))
        assertTrue(tagsAfter.contains("UNRELATED(MOD, \"keep/me\")"))
        assertFalse(tagsAfter.contains("FORGE(\"forge\")"))
        assertTrue(millingAfter.contains("TagEmptyCondition(\"c\", \"ores/\" + name)"))
        assertTrue(millingAfter.contains("AllTags.forgeItemTag(\"ores/\" + name)"))

        val second = CreateLegacyTagConventionMigration.migrate(project, dryRun = false)
        assertTrue(second.changes.isEmpty())
        assertTrue(second.gates.isEmpty())
        assertEquals(tagsAfter, allTags.readText())
        assertEquals(millingAfter, milling.readText())
    }

    @Test
    fun `dry run reports both files without writing either`() {
        val project = project(legacyAllTags(), legacyMilling())
        val tagsBefore = allTags(project).readText()
        val millingBefore = milling(project).readText()

        val result = CreateLegacyTagConventionMigration.migrate(project, dryRun = true)

        assertEquals(2, result.changes.size, result.gates.joinToString())
        assertTrue(result.gates.isEmpty())
        assertEquals(tagsBefore, allTags(project).readText())
        assertEquals(millingBefore, milling(project).readText())
    }

    @Test
    fun `refuses a partial two-file family without writing`() {
        val project = project(
            legacyAllTags(),
            legacyMilling().replace(
                "TagEmptyCondition(\"forge\", \"ores/\" + name)",
                "TagEmptyCondition(\"c\", \"ores/\" + name)"
            )
        )
        val tagsBefore = allTags(project).readText()
        val millingBefore = milling(project).readText()

        val result = CreateLegacyTagConventionMigration.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("partial or incompatible"))
        assertEquals(tagsBefore, allTags(project).readText())
        assertEquals(millingBefore, milling(project).readText())
    }

    @Test
    fun `refuses an altered enum entry without writing`() {
        val project = project(
            legacyAllTags().replace(
                "WHEAT_DOUGH(FORGE, \"dough/wheat\")",
                "WHEAT_DOUGH(FORGE, \"dough/custom\")"
            ),
            legacyMilling()
        )
        val tagsBefore = allTags(project).readText()
        val millingBefore = milling(project).readText()

        val result = CreateLegacyTagConventionMigration.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("partial or incompatible"))
        assertEquals(tagsBefore, allTags(project).readText())
        assertEquals(millingBefore, milling(project).readText())
    }

    @Test
    fun `second publish failure rolls the first file back`() {
        val project = project(legacyAllTags(), legacyMilling())
        val tagsBefore = allTags(project).readText()
        val millingBefore = milling(project).readText()
        var publishes = 0

        val result = CreateLegacyTagConventionMigration.migrateForTest(
            project,
            dryRun = false
        ) { source, target ->
            val isPublish = source.fileName.toString().contains("-stage-")
            if (isPublish && ++publishes == 2) throw IOException("injected second publish failure")
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        }

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("injected second publish failure"))
        assertEquals(tagsBefore, allTags(project).readText())
        assertEquals(millingBefore, milling(project).readText())
    }

    private fun project(allTagsSource: String, millingSource: String): Path {
        val project = Files.createTempDirectory("create-legacy-tags")
        val allTags = allTags(project)
        allTags.parent.createDirectories()
        allTags.writeText(allTagsSource)
        val milling = milling(project)
        milling.parent.createDirectories()
        milling.writeText(millingSource)
        return project
    }

    private fun allTags(project: Path): Path =
        project.resolve("src/main/java/com/simibubi/create/AllTags.java")

    private fun milling(project: Path): Path =
        project.resolve(
            "src/main/java/com/simibubi/create/api/data/recipe/MillingRecipeGen.java"
        )

    private fun legacyAllTags(): String = """
        package com.simibubi.create;

        import static com.simibubi.create.AllTags.NameSpace.CURIOS;
        import static com.simibubi.create.AllTags.NameSpace.FORGE;
        import static com.simibubi.create.AllTags.NameSpace.MOD;
        import static com.simibubi.create.AllTags.NameSpace.QUARK;
        import static com.simibubi.create.AllTags.NameSpace.TIC;

        import net.minecraft.core.Registry;
        import net.minecraft.core.registries.BuiltInRegistries;
        import net.minecraft.core.registries.Registries;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.tags.TagKey;
        import net.minecraft.world.item.Item;
        import net.minecraft.world.level.block.Block;
        import net.minecraft.world.level.material.Fluid;

        public class AllTags {
            public static <T> TagKey<T> optionalTag(Registry<T> registry, ResourceLocation id) {
                return TagKey.create(registry.key(), id);
            }

            public static <T> TagKey<T> forgeTag(Registry<T> registry, String path) {
                return optionalTag(registry, FORGE.id(path));
            }

            public static TagKey<Block> forgeBlockTag(String path) {
                return forgeTag(BuiltInRegistries.BLOCK, path);
            }

            public static TagKey<Item> forgeItemTag(String path) {
                return forgeTag(BuiltInRegistries.ITEM, path);
            }

            public static TagKey<Fluid> forgeFluidTag(String path) {
                return forgeTag(BuiltInRegistries.FLUID, path);
            }

            public enum NameSpace {
                MOD("create"),
                FORGE("forge"),
                TIC("tconstruct"),
                QUARK("quark"),
                CURIOS("curios");

                NameSpace(String id) {
                }

                public ResourceLocation id(String path) {
                    return ResourceLocation.fromNamespaceAndPath("unused", path);
                }
            }

            public enum AllBlockTags {
                CARDBOARD_STORAGE_BLOCKS(FORGE, "storage_blocks/cardboard"),
                ANDESITE_ALLOY_STORAGE_BLOCKS(FORGE, "storage_blocks/andesite_alloy"),
                STONE_ORES_IN_GROUND(FORGE, "ores_in_ground/stone"),
                DEEPSLATE_ORES_IN_GROUND(FORGE, "ores_in_ground/deepslate"),
                RELOCATION_NOT_SUPPORTED(FORGE);

                AllBlockTags(NameSpace namespace) {}
                AllBlockTags(NameSpace namespace, String path) {}
            }

            public enum AllItemTags {
                UNRELATED(MOD, "keep/me"),
                STRIPPED_LOGS(FORGE),
                STRIPPED_WOOD(FORGE),
                OBSIDIAN_DUST(FORGE, "dusts/obsidian"),
                PLATES(FORGE),
                OBSIDIAN_PLATES(FORGE, "plates/obsidian"),
                CARDBOARD_PLATES(FORGE, "plates/cardboard"),
                WRENCH(FORGE, "tools/wrench"),
                CERTUS_QUARTZ(FORGE, "gems/certus_quartz"),
                AMETRINE_ORES(FORGE, "ores/ametrine"),
                ANTHRACITE_ORES(FORGE, "ores/anthracite"),
                EMERALDITE_ORES(FORGE, "ores/emeraldite"),
                LIGNITE_ORES(FORGE, "ores/lignite"),
                RAW_MATERIALS(FORGE),
                CARDBOARD_STORAGE_BLOCKS(FORGE, "storage_blocks/cardboard"),
                ANDESITE_ALLOY_STORAGE_BLOCKS(FORGE, "storage_blocks/andesite_alloy"),
                STONE_ORES_IN_GROUND(FORGE, "ores_in_ground/stone"),
                DEEPSLATE_ORES_IN_GROUND(FORGE, "ores_in_ground/deepslate"),
                HONEY_BUCKETS(FORGE, "buckets/honey"),
                FLOUR(FORGE),
                WHEAT_FLOUR(FORGE, "flour/wheat"),
                DOUGH(FORGE),
                WHEAT_DOUGH(FORGE, "dough/wheat"),
                HELMET_ARMORS(FORGE, "armors/helmets"),
                CHESTPLATE_ARMORS(FORGE, "armors/chestplates"),
                LEGGING_ARMORS(FORGE, "armors/leggings"),
                BOOT_ARMORS(FORGE, "armors/boots");

                AllItemTags(NameSpace namespace) {}
                AllItemTags(NameSpace namespace, String path) {}
            }

            public enum AllFluidTags {
                TEA(FORGE),
                HONEY(FORGE),
                CHOCOLATE(FORGE),
                CREOSOTE(FORGE);

                AllFluidTags(NameSpace namespace) {}
            }
        }
    """.trimIndent()

    private fun legacyMilling(): String = """
        package com.simibubi.create.api.data.recipe;

        import com.simibubi.create.AllTags;
        import com.tterrag.registrate.util.entry.ItemEntry;
        import net.minecraft.world.item.Item;
        import net.neoforged.neoforge.common.conditions.NotCondition;
        import net.neoforged.neoforge.common.conditions.TagEmptyCondition;

        public abstract class MillingRecipeGen {
            protected GeneratedRecipe metalOre(
                String name,
                ItemEntry<? extends Item> crushed,
                int duration
            ) {
                return create(name + "_ore", b -> b.duration(duration)
                    .withCondition(new NotCondition(
                        new TagEmptyCondition("forge", "ores/" + name)))
                    .require(AllTags.forgeItemTag("ores/" + name))
                    .output(crushed.get()));
            }
        }
    """.trimIndent()
}
