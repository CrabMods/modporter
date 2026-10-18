package com.modporter.integration

import com.modporter.mapping.MappingDatabase
import com.modporter.resources.ResourceMigrationPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceMigrationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun setupResourceProject(name: String = "resmod"): Path {
        val projectDir = tempDir.resolve(name)
        val resourceDir = projectDir.resolve("src/main/resources")

        // mods.toml
        resourceDir.resolve("META-INF").createDirectories()
        resourceDir.resolve("META-INF/mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            [[mods]]
            modId="resmod"
            [[dependencies.resmod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
        """.trimIndent())

        // Data folders with plural names
        val dataDir = resourceDir.resolve("data/resmod")
        dataDir.resolve("tags/blocks").createDirectories()
        dataDir.resolve("tags/items").createDirectories()
        dataDir.resolve("tags/entity_types").createDirectories()
        dataDir.resolve("recipes").createDirectories()
        dataDir.resolve("loot_tables/blocks").createDirectories()
        dataDir.resolve("advancements").createDirectories()

        // Sample files in those directories
        dataDir.resolve("tags/items/my_tag.json").writeText("""{"values":["resmod:item"]}""")
        dataDir.resolve("recipes/my_recipe.json").writeText("""{"type":"minecraft:crafting_shaped"}""")
        dataDir.resolve("loot_tables/blocks/my_block.json").writeText("""{"type":"minecraft:block"}""")
        dataDir.resolve("advancements/root.json").writeText("""{"criteria":{}}""")

        // pack.mcmeta
        resourceDir.resolve("pack.mcmeta").writeText("""{"pack":{"pack_format":15,"description":"Test"}}""")

        return projectDir
    }

    private fun writeResultEntryCodecRecipeSources(projectDir: Path) {
        val javaDir = projectDir.resolve("src/main/java/resmod")
        javaDir.createDirectories()
        javaDir.resolve("ModRecipeSerializers.java").writeText("""
            package resmod;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModRecipeSerializers {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, "resmod");
                public static final DeferredHolder<RecipeSerializer<?>, CuttingRecipe.Serializer> CUTTING = RECIPE_SERIALIZERS.register("cutting", CuttingRecipe.Serializer::new);
            }
        """.trimIndent())
        javaDir.resolve("CuttingRecipe.java").writeText("""
            package resmod;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public final class CuttingRecipe {
                public static final class ResultEntry {
                    public static final Codec<ResultEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        ItemStack.CODEC.fieldOf("item").forGetter(entry -> null)
                    ).apply(instance, item -> null));
                }

                public static final class Serializer implements RecipeSerializer<CuttingRecipe> {
                    private static final MapCodec<CuttingRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        ResultEntry.CODEC.listOf().fieldOf("result").forGetter(recipe -> null)
                    ).apply(instance, result -> null));
                }
            }
        """.trimIndent())
    }

    private fun writeJarWithEntry(jar: Path, entryName: String, content: String) {
        jar.parent.createDirectories()
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    @Test
    fun `renames mods toml to neoforge mods toml`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val neoToml = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml")
        val oldToml = projectDir.resolve("src/main/resources/META-INF/mods.toml")

        assertTrue(neoToml.exists(), "neoforge.mods.toml should exist")
        assertFalse(oldToml.exists(), "Old mods.toml should be removed")
    }

    @Test
    fun `updates mods toml content`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val content = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(content.contains("neoforge"), "Should reference neoforge instead of forge")
    }

    @Test
    fun `tool credit is opt in and does not mention a fixed target version`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()

        ResourceMigrationPass(db).apply(projectDir)
        val defaultContent = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertFalse(defaultContent.contains("Ported with ModPorter"), "Tool credit should not be added by default")

        val creditedProject = setupResourceProject("credited-opt-in")
        ResourceMigrationPass(db, addToolCredit = true).apply(creditedProject)
        val creditedContent = creditedProject.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()

        assertTrue(creditedContent.contains("credits=\"Ported with ModPorter: https://github.com/CrabMods/modporter\""), creditedContent)
        assertFalse(creditedContent.contains("Ported with ModPorter to NeoForge 1.21.1"), creditedContent)
    }

    @Test
    fun `tool credit appends to existing credits without changing authors`() {
        val projectDir = tempDir.resolve("creditedmod")
        val metaInf = projectDir.resolve("src/main/resources/META-INF")
        metaInf.createDirectories()
        metaInf.resolve("mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            [[mods]]
            modId="creditedmod"
            displayName="Credited Mod"
            credits="Original helpers"
            authors="Original Author"
            description='''Example'''
        """.trimIndent())

        ResourceMigrationPass(MappingDatabase.loadDefault(), addToolCredit = true).apply(projectDir)

        val content = metaInf.resolve("neoforge.mods.toml").readText()
        assertTrue(content.contains("credits=\"Original helpers; Ported with ModPorter: https://github.com/CrabMods/modporter\""), content)
        assertTrue(content.contains("authors=\"Original Author\""), content)
    }

    @Test
    fun `custom enchantment resource keys require source derived data json`() {
        val projectDir = tempDir.resolve("missing-enchantment-data")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;

            public final class ModEnchantments {
                public static final net.minecraft.resources.ResourceKey<Enchantment> FIRE_REACT =
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "fire_react"));
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)
        val generated = projectDir.resolve("src/generated/resources/data/example/enchantment/fire_react.json")

        assertTrue(result.errors.any { it.contains("Missing source-derived data-driven custom enchantment JSON") })
        assertTrue(result.changes.any { it.ruleId == "res-custom-enchantment-data" })
        assertFalse(generated.exists(), "Resource migration must not create default custom enchantment JSON")
    }

    @Test
    fun `custom enchantment resource keys resolve bare mod ids from call site owner`() {
        val projectDir = tempDir.resolve("owner-local-enchantment-modid")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;

            public final class ModEnchantments {
                public static final String MODID = "example";
                public static final net.minecraft.resources.ResourceKey<Enchantment> FIRE_REACT =
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "fire_react"));
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any { it.contains("Missing source-derived data-driven custom enchantment JSON for 'example:fire_react'") },
            result.errors.joinToString("\n")
        )
        assertTrue(result.changes.any { it.ruleId == "res-custom-enchantment-data" })
    }

    @Test
    fun `custom enchantment resource keys do not resolve bare mod ids by global uniqueness`() {
        val projectDir = tempDir.resolve("global-bare-enchantment-modid")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;

            public final class ModEnchantments {
                public static final net.minecraft.resources.ResourceKey<Enchantment> FIRE_REACT =
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "fire_react"));
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertFalse(
            result.changes.any { it.ruleId == "res-custom-enchantment-data" },
            "Bare mod ids must resolve from the call-site owner, not from globally unique constants"
        )
        assertFalse(
            result.errors.any { it.contains("Missing source-derived data-driven custom enchantment JSON") },
            result.errors.joinToString("\n")
        )
    }

    @Test
    fun `custom enchantment resource keys do not infer mod id owner from java file names`() {
        val projectDir = tempDir.resolve("ambiguous-enchantment-modid")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public static final String MODID = "example";
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;

            public final class ModEnchantments {
                public static final net.minecraft.resources.ResourceKey<Enchantment> QUALIFIED =
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "qualified"));
                public static final net.minecraft.resources.ResourceKey<Enchantment> SIMPLE =
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "simple"));
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertFalse(
            result.changes.any { it.ruleId == "res-custom-enchantment-data" },
            "Unsupported mod id constant ownership must not produce custom enchantment data requirements"
        )
        assertFalse(
            result.errors.any { it.contains("Missing source-derived data-driven custom enchantment JSON") },
            result.errors.joinToString("\n")
        )
    }

    @Test
    fun `custom enchantment resource keys ignore text block mod id constants`() {
        val projectDir = tempDir.resolve("text-block-enchantment-modid")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText(listOf(
            "package com.example;",
            "",
            "public final class ExampleMod {",
            "    String docs() {",
            "        return \"\"\"",
            "            public final class ExampleMod {",
            "            public static final String MODID = \"example\";",
            "            public static final net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> DOC_KEY =",
            "                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,",
            "                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, \"doc_key\"));",
            "            }",
            "            \"\"\";",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;

            public final class ModEnchantments {
                public static final net.minecraft.resources.ResourceKey<Enchantment> FIRE_REACT =
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "fire_react"));
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertFalse(
            result.changes.any { it.ruleId == "res-custom-enchantment-data" },
            "Text block MODID examples must not resolve custom enchantment resource namespaces"
        )
        assertFalse(
            result.errors.any { it.contains("Missing source-derived data-driven custom enchantment JSON") },
            result.errors.joinToString("\n")
        )
    }

    @Test
    fun `custom enchantment resource keys ignore text block key examples`() {
        val projectDir = tempDir.resolve("text-block-enchantment-key")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText(listOf(
            "package com.example;",
            "",
            "import net.minecraft.world.item.enchantment.Enchantment;",
            "",
            "public final class ModEnchantments {",
            "    String docs() {",
            "        return \"\"\"",
            "            public static final net.minecraft.resources.ResourceKey<Enchantment> FIRE_REACT =",
            "                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,",
            "                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, \"fire_react\"));",
            "            \"\"\";",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertFalse(
            result.changes.any { it.ruleId == "res-custom-enchantment-data" },
            "Text block ResourceKey examples must not produce custom enchantment data requirements"
        )
        assertFalse(
            result.errors.any { it.contains("Missing source-derived data-driven custom enchantment JSON") },
            result.errors.joinToString("\n")
        )
    }

    @Test
    fun `renames data folders`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val dataDir = projectDir.resolve("src/main/resources/data/resmod")

        // New singular folders should exist
        assertTrue(dataDir.resolve("tags/item").exists(), "tags/items -> tags/item")
        assertTrue(dataDir.resolve("tags/block").exists(), "tags/blocks -> tags/block")
        assertTrue(dataDir.resolve("recipe").exists(), "recipes -> recipe")
        assertTrue(dataDir.resolve("loot_table").exists(), "loot_tables -> loot_table")
        assertTrue(dataDir.resolve("advancement").exists(), "advancements -> advancement")
    }

    @Test
    fun `preserves files during folder rename`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val dataDir = projectDir.resolve("src/main/resources/data/resmod")

        // Files should be preserved in renamed folders
        assertTrue(dataDir.resolve("tags/item/my_tag.json").exists(), "Tag file should be preserved")
        assertTrue(dataDir.resolve("recipe/my_recipe.json").exists(), "Recipe file should be preserved")
    }

    @Test
    fun `folder rename merges plural tags into existing singular tags`() {
        val projectDir = setupResourceProject()
        val dataDir = projectDir.resolve("src/main/resources/data/resmod")
        dataDir.resolve("tags/item/enchantable").createDirectories()
        dataDir.resolve("tags/item/enchantable/tool.json").writeText("""{"values":["resmod:tool"]}""")

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        assertTrue(result.changes.any { it.ruleId == "res-folder-rename" })
        assertTrue(dataDir.resolve("tags/item/my_tag.json").exists(), "Plural item tag should be merged")
        assertTrue(dataDir.resolve("tags/item/enchantable/tool.json").exists(), "Existing singular tag should remain")
        assertFalse(dataDir.resolve("tags/items").exists(), "Old plural item tag directory should be removed")
    }

    @Test
    fun `common tool tag file paths migrate to 1_21 singular paths`() {
        val projectDir = setupResourceProject()
        val dataDir = projectDir.resolve("src/generated/resources/data/c")
        dataDir.resolve("tags/items/tools").createDirectories()
        dataDir.resolve("tags/items/tools/bows.json").writeText("""{"values":["resmod:bow"]}""")
        dataDir.resolve("tags/items/tools/shields.json").writeText("""{"values":["resmod:shield"]}""")

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        assertTrue(result.changes.any { it.ruleId == "res-common-tag-path" })
        assertTrue(dataDir.resolve("tags/item/tools/bow.json").exists(), "tools/bows should become tools/bow")
        assertTrue(dataDir.resolve("tags/item/tools/shield.json").exists(), "tools/shields should become tools/shield")
        assertFalse(dataDir.resolve("tags/item/tools/bows.json").exists())
        assertFalse(dataDir.resolve("tags/item/tools/shields.json").exists())
    }

    @Test
    fun `recipe string result is migrated to id object`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("string_result.json").writeText("""
            {"type":"minecraft:smelting","ingredient":{"item":"minecraft:water_bucket"},"result":"resmod:hot_water_bucket"}
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/string_result.json").readText()
        assertTrue(recipe.contains(""""result": {"""), "Recipe result should be an object")
        assertTrue(recipe.contains(""""id": "resmod:hot_water_bucket""""))
        assertFalse(recipe.contains(""""result":"resmod:hot_water_bucket""""))
    }

    @Test
    fun `custom recipe data fields migrate only from source derived serializer codecs`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/resmod")
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        javaDir.createDirectories()
        recipeDir.createDirectories()

        javaDir.resolve("ResMod.java").writeText("""
            package resmod;

            public final class ResMod {
                public static final String MODID = "resmod";
            }
        """.trimIndent())
        javaDir.resolve("ModRecipeSerializers.java").writeText("""
            package resmod;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModRecipeSerializers {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, ResMod.MODID);
                public static final DeferredHolder<RecipeSerializer<?>, HeatingRecipe.Serializer> HEATING = RECIPE_SERIALIZERS.register("heating", HeatingRecipe.Serializer::new);
                public static final DeferredHolder<RecipeSerializer<?>, IncubationRecipe.Serializer> INCUBATION = RECIPE_SERIALIZERS.register("incubation", IncubationRecipe.Serializer::new);
                public static final DeferredHolder<RecipeSerializer<?>, PlainRecipe.Serializer> PLAIN = RECIPE_SERIALIZERS.register("plain", PlainRecipe.Serializer::new);
            }
        """.trimIndent())
        javaDir.resolve("AbstractHeatingSerializer.java").writeText("""
            package resmod;

            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class AbstractHeatingSerializer<T> implements RecipeSerializer<T> {
                private final MapCodec<T> codec = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    ItemStack.CODEC.fieldOf("result").forGetter(recipe -> null)
                ).apply(instance, value -> null));
            }
        """.trimIndent())
        javaDir.resolve("HeatingRecipe.java").writeText("""
            package resmod;

            public final class HeatingRecipe {
                public static class Serializer extends AbstractHeatingSerializer<HeatingRecipe> {
                }
            }
        """.trimIndent())
        javaDir.resolve("IncubationRecipe.java").writeText("""
            package resmod;

            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public final class IncubationRecipe {
                public static class Serializer implements RecipeSerializer<IncubationRecipe> {
                    private static final MapCodec<IncubationRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        CompoundTag.CODEC.optionalFieldOf("tag").forGetter(recipe -> null)
                    ).apply(instance, value -> null));
                }
            }
        """.trimIndent())
        javaDir.resolve("PlainRecipe.java").writeText("""
            package resmod;

            import net.minecraft.world.item.crafting.RecipeSerializer;

            public final class PlainRecipe {
                public static class Serializer implements RecipeSerializer<PlainRecipe> {
                }
            }
        """.trimIndent())
        recipeDir.resolve("heating.json").writeText("""
            {
              "type": "resmod:heating",
              "ingredient": {
                "item": "minecraft:stone"
              },
              "result": "resmod:heated_stone"
            }
        """.trimIndent())
        recipeDir.resolve("incubation.json").writeText("""
            {
              "type": "resmod:incubation",
              "ingredient": {
                "item": "resmod:egg"
              },
              "tag": "{Hungry:1b,IsBaby:1b,MoaType:\"resmod:white\",Nested:{Value:2s},List:[1b,2b]}"
            }
        """.trimIndent())
        recipeDir.resolve("plain.json").writeText("""
            {
              "type": "resmod:plain",
              "result": "resmod:plain_result",
              "tag": "{ShouldStay:1b}"
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val heating = projectDir.resolve("src/generated/resources/data/resmod/recipe/heating.json").readText()
        val incubation = projectDir.resolve("src/generated/resources/data/resmod/recipe/incubation.json").readText()
        val plain = projectDir.resolve("src/generated/resources/data/resmod/recipe/plain.json").readText()

        assertTrue(result.changes.any { it.ruleId == "res-recipe-result-entry-id" })
        assertTrue(result.changes.any { it.ruleId == "res-recipe-snbt-compound-tag" })
        assertTrue(heating.contains(""""result": {"""))
        assertTrue(heating.contains(""""id": "resmod:heated_stone""""))
        assertTrue(incubation.contains(""""tag": {"""))
        assertTrue(incubation.contains(""""Hungry": 1"""))
        assertTrue(incubation.contains(""""IsBaby": 1"""))
        assertTrue(incubation.contains(""""MoaType": "resmod:white""""))
        assertTrue(incubation.contains(""""Nested": {"""))
        assertTrue(incubation.contains(""""Value": 2"""))
        assertTrue(incubation.contains(""""List": ["""))
        assertTrue(plain.contains(""""result": "resmod:plain_result""""))
        assertTrue(plain.contains(""""tag": "{ShouldStay:1b}""""))
    }

    @Test
    fun `custom recipe codec source index uses declared type names not java file names`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/resmod")
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        javaDir.createDirectories()
        recipeDir.createDirectories()

        javaDir.resolve("RegistrySurface.java").writeText("""
            package resmod;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class RecipeRegistries {
                static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, "resmod");
                static final DeferredHolder<RecipeSerializer<?>, RenamedRecipe.Serializer> RENAMED = RECIPE_SERIALIZERS.register("renamed", RenamedRecipe.Serializer::new);
            }
        """.trimIndent())
        javaDir.resolve("RecipeSource.java").writeText("""
            package resmod;

            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            final class RenamedRecipe {
                static class Serializer implements RecipeSerializer<RenamedRecipe> {
                    private static final MapCodec<RenamedRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        CompoundTag.CODEC.optionalFieldOf("tag").forGetter(recipe -> null)
                    ).apply(instance, value -> null));
                }
            }
        """.trimIndent())
        recipeDir.resolve("renamed.json").writeText("""
            {
              "type": "resmod:renamed",
              "tag": "{Value:1b}"
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val renamed = projectDir.resolve("src/generated/resources/data/resmod/recipe/renamed.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-recipe-snbt-compound-tag" })
        assertTrue(renamed.contains(""""tag": {"""), renamed)
        assertTrue(renamed.contains(""""Value": 1"""), renamed)
        assertFalse(renamed.contains(""""tag": "{Value:1b}""""), renamed)
    }

    @Test
    fun `custom recipe codec source index does not resolve globally unique invisible types`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java")
        val registryDir = javaDir.resolve("resmod")
        val foreignDir = javaDir.resolve("other")
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        registryDir.createDirectories()
        foreignDir.createDirectories()
        recipeDir.createDirectories()

        registryDir.resolve("ModRecipeSerializers.java").writeText("""
            package resmod;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModRecipeSerializers {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, "resmod");
                public static final DeferredHolder<RecipeSerializer<?>, ForeignRecipe.Serializer> FOREIGN = RECIPE_SERIALIZERS.register("foreign", ForeignRecipe.Serializer::new);
            }
        """.trimIndent())
        foreignDir.resolve("ForeignRecipe.java").writeText("""
            package other;

            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public final class ForeignRecipe {
                public static class Serializer implements RecipeSerializer<ForeignRecipe> {
                    private static final MapCodec<ForeignRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        CompoundTag.CODEC.optionalFieldOf("tag").forGetter(recipe -> null)
                    ).apply(instance, value -> null));
                }
            }
        """.trimIndent())
        recipeDir.resolve("foreign.json").writeText("""
            {
              "type": "resmod:foreign",
              "tag": "{Value:1b}"
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val foreign = projectDir.resolve("src/generated/resources/data/resmod/recipe/foreign.json").readText()
        assertFalse(
            result.changes.any { it.ruleId == "res-recipe-snbt-compound-tag" },
            "Recipe codec source lookup must follow Java visibility, not globally unique simple type names"
        )
        assertTrue(foreign.contains(""""tag": "{Value:1b}""""), foreign)
        assertFalse(foreign.contains(""""tag": {"""), foreign)
    }

    @Test
    fun `custom recipe serializer registries do not resolve bare namespaces by global uniqueness`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/resmod")
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        javaDir.createDirectories()
        recipeDir.createDirectories()

        javaDir.resolve("ResMod.java").writeText("""
            package resmod;

            public final class ResMod {
                public static final String MODID = "resmod";
            }
        """.trimIndent())
        javaDir.resolve("ModRecipeSerializers.java").writeText("""
            package resmod;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModRecipeSerializers {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, MODID);
                public static final DeferredHolder<RecipeSerializer<?>, IncubationRecipe.Serializer> INCUBATION = RECIPE_SERIALIZERS.register("incubation", IncubationRecipe.Serializer::new);
            }
        """.trimIndent())
        javaDir.resolve("IncubationRecipe.java").writeText("""
            package resmod;

            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public final class IncubationRecipe {
                public static class Serializer implements RecipeSerializer<IncubationRecipe> {
                    private static final MapCodec<IncubationRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        CompoundTag.CODEC.optionalFieldOf("tag").forGetter(recipe -> null)
                    ).apply(instance, value -> null));
                }
            }
        """.trimIndent())
        recipeDir.resolve("incubation.json").writeText("""
            {
              "type": "resmod:incubation",
              "tag": "{Hungry:1b}"
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val incubation = projectDir.resolve("src/generated/resources/data/resmod/recipe/incubation.json").readText()
        assertFalse(
            result.changes.any { it.ruleId == "res-recipe-snbt-compound-tag" },
            "Bare serializer registry namespaces must resolve from the declaration owner, not from globally unique constants"
        )
        assertTrue(incubation.contains(""""tag": "{Hungry:1b}""""), incubation)
        assertFalse(incubation.contains(""""tag": {"""), incubation)
    }

    @Test
    fun `custom recipe data fields ignore text block serializer codecs`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/resmod")
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        javaDir.createDirectories()
        recipeDir.createDirectories()

        javaDir.resolve("ResMod.java").writeText("""
            package resmod;

            public final class ResMod {
                public static final String MODID = "resmod";
            }
        """.trimIndent())
        javaDir.resolve("ModRecipeSerializers.java").writeText("""
            package resmod;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModRecipeSerializers {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, ResMod.MODID);
                public static final DeferredHolder<RecipeSerializer<?>, IncubationRecipe.Serializer> INCUBATION = RECIPE_SERIALIZERS.register("incubation", IncubationRecipe.Serializer::new);
            }
        """.trimIndent())
        javaDir.resolve("IncubationRecipe.java").writeText(listOf(
            "package resmod;",
            "",
            "import net.minecraft.nbt.CompoundTag;",
            "import net.minecraft.world.item.crafting.RecipeSerializer;",
            "",
            "public final class IncubationRecipe {",
            "    public static class Serializer implements RecipeSerializer<IncubationRecipe> {",
            "        String docs() {",
            "            return \"\"\"",
            "                CompoundTag.CODEC.optionalFieldOf(\"tag\").forGetter(recipe -> null)",
            "                \"\"\";",
            "        }",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))
        recipeDir.resolve("incubation.json").writeText("""
            {
              "type": "resmod:incubation",
              "ingredient": {
                "item": "resmod:egg"
              },
              "tag": "{Hungry:1b}"
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val incubation = projectDir.resolve("src/generated/resources/data/resmod/recipe/incubation.json").readText()
        assertFalse(
            result.changes.any { it.ruleId == "res-recipe-snbt-compound-tag" },
            "Text block serializer codec examples must not drive recipe SNBT component migration"
        )
        assertTrue(incubation.contains(""""tag": "{Hungry:1b}""""), incubation)
        assertFalse(incubation.contains(""""tag": {"""), incubation)
    }

    @Test
    fun `codec declared result list item stack fields migrate without mod specific recipe ids`() {
        val projectDir = setupResourceProject()
        writeResultEntryCodecRecipeSources(projectDir)
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("cutting_result_array.json").writeText("""
            {
              "type": "resmod:cutting",
              "ingredients": [
                {
                  "item": "resmod:cabbage"
                }
              ],
              "result": [
                {
                  "item": "resmod:sliced_cabbage",
                  "count": 2
                }
              ],
              "tool": {
                "tag": "forge:tools/knives"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/cutting_result_array.json").readText()
        assertEquals(
            1,
            result.changes.count { it.ruleId == "res-recipe-result-entry-id" },
            "Codec-declared result entry stack migration rule should be recorded exactly once"
        )
        assertTrue(recipe.contains(""""item": "resmod:cabbage""""))
        assertTrue(recipe.contains(""""item": {"""))
        assertTrue(recipe.contains(""""id": "resmod:sliced_cabbage""""))
        assertTrue(recipe.contains(""""count": 2"""))
        assertTrue(recipe.contains(""""tag": "c:tools/knife""""))
        assertFalse(recipe.contains(""""item": "resmod:sliced_cabbage""""))
        assertFalse(recipe.contains("forge:tools/knives"))
    }

    @Test
    fun `result list item stack fields require serializer codec evidence`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("unproven_result_array.json").writeText("""
            {
              "type": "resmod:cutting",
              "ingredients": [
                {
                  "item": "resmod:cabbage"
                }
              ],
              "result": [
                {
                  "item": "resmod:sliced_cabbage",
                  "count": 2
                }
              ]
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/unproven_result_array.json").readText()
        assertFalse(
            result.changes.any { it.ruleId == "res-recipe-result-entry-id" },
            "Result entry stack migration must require source-declared serializer codec or target dependency data evidence"
        )
        assertTrue(recipe.contains(""""item": "resmod:sliced_cabbage""""))
        assertTrue(recipe.contains(""""count": 2"""))
        assertFalse(recipe.contains(""""item": {"""))
    }

    @Test
    fun `dependency recipe data shape migrates external result entry item stack fields`() {
        val projectDir = setupResourceProject()
        val dependencyJar = projectDir.resolve("libs/depmod-1.0.0.jar")
        writeJarWithEntry(
            dependencyJar,
            "data/depmod/recipe/cutting/example.json",
            """
                {
                  "type": "depmod:cutting",
                  "ingredients": [
                    {
                      "item": "depmod:cabbage"
                    }
                  ],
                  "result": [
                    {
                      "item": {
                        "count": 2,
                        "id": "depmod:sliced_cabbage"
                      }
                    }
                  ]
                }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle").writeText("""
            repositories { flatDir { dirs 'libs' } }
            dependencies {
                implementation files('libs/depmod-1.0.0.jar')
            }
        """.trimIndent())
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("external_cutting.json").writeText("""
            {
              "type": "depmod:cutting",
              "ingredients": [
                {
                  "item": "resmod:cabbage"
                }
              ],
              "result": [
                {
                  "item": "resmod:sliced_cabbage",
                  "count": 2
                }
              ]
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/external_cutting.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-recipe-result-entry-id" },
            "Target dependency data should prove external result entry item stack fields"
        )
        assertTrue(recipe.contains(""""item": {"""))
        assertTrue(recipe.contains(""""id": "resmod:sliced_cabbage""""))
        assertTrue(recipe.contains(""""count": 2"""))
        assertTrue(recipe.contains(""""item": "resmod:cabbage""""))
        assertFalse(recipe.contains(""""item": "resmod:sliced_cabbage""""))
    }

    @Test
    fun `conditional codec declared result list item stack fields are unwrapped and migrated`() {
        val projectDir = setupResourceProject()
        writeResultEntryCodecRecipeSources(projectDir)
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("conditional_cutting.json").writeText("""
            {
              "type": "neoforge:conditional",
              "recipes": [
                {
                  "neoforge:conditions": [
                    {
                      "type": "neoforge:mod_loaded",
                      "modid": "resmod"
                    }
                  ],
                  "recipe": {
                    "type": "resmod:cutting",
                    "ingredients": [
                      {
                        "item": "resmod:cabbage"
                      }
                    ],
                    "tool": {
                      "tag": "forge:tools/knives"
                    },
                    "result": [
                      {
                        "item": "resmod:sliced_cabbage",
                        "count": 2
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/conditional_cutting.json").readText()
        assertEquals(
            1,
            result.changes.count { it.ruleId == "res-recipe-result-entry-id" },
            "Conditional codec-declared result entry stack migration should be recorded exactly once"
        )
        assertTrue(recipe.contains(""""neoforge:conditions":"""))
        assertTrue(recipe.contains(""""type": "resmod:cutting""""))
        assertTrue(recipe.contains(""""item": {"""))
        assertTrue(recipe.contains(""""id": "resmod:sliced_cabbage""""))
        assertTrue(recipe.contains(""""count": 2"""))
        assertTrue(recipe.contains(""""tag": "c:tools/knife""""))
        assertFalse(recipe.contains(""""type": "neoforge:conditional""""))
        assertFalse(recipe.contains(""""item": "resmod:sliced_cabbage""""))
    }

    @Test
    fun `single conditional recipe is unwrapped with top level conditions`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        recipeDir.createDirectories()
        recipeDir.resolve("conditional_table.json").writeText("""
            {
              "type": "neoforge:conditional",
              "recipes": [
                {
                  "neoforge:conditions": [
                    {
                      "type": "neoforge:mod_loaded",
                      "modid": "farmersdelight"
                    }
                  ],
                  "recipe": {
                    "type": "minecraft:crafting_shapeless",
                    "ingredients": [
                      {
                        "tag": "forge:lumber"
                      }
                    ],
                    "result": {
                      "item": "resmod:conditional_table"
                    }
                  }
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/conditional_table.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-recipe-unwrap-single-conditional" },
            "Conditional recipe unwrap rule should be recorded"
        )
        assertTrue(recipe.contains(""""neoforge:conditions":"""))
        assertTrue(recipe.contains(""""type": "minecraft:crafting_shapeless""""))
        assertTrue(recipe.contains(""""tag": "c:lumber""""))
        assertTrue(recipe.contains(""""id": "resmod:conditional_table""""))
        assertFalse(recipe.contains(""""type": "neoforge:conditional""""))
        assertFalse(recipe.contains(""""recipes":"""))
        assertFalse(recipe.contains(""""recipe":"""))
    }

    @Test
    fun `loot table conditions remain vanilla conditions`() {
        val projectDir = setupResourceProject()
        val lootDir = projectDir.resolve("src/main/resources/data/resmod/loot_tables/blocks")
        lootDir.resolve("explosive_block.json").writeText("""
            {
              "type": "minecraft:block",
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "resmod:explosive_block"
                    }
                  ],
                  "conditions": [
                    {
                      "condition": "minecraft:survives_explosion"
                    }
                  ]
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val lootTable = projectDir.resolve("src/main/resources/data/resmod/loot_table/blocks/explosive_block.json").readText()
        assertTrue(lootTable.contains(""""conditions""""))
        assertTrue(lootTable.contains(""""condition": "minecraft:survives_explosion""""))
        assertFalse(lootTable.contains(""""neoforge:conditions""""))
    }

    @Test
    fun `global loot modifier conditions use loot condition codec wrapper key`() {
        val projectDir = setupResourceProject()
        val lootModifierDir = projectDir.resolve("src/main/resources/data/resmod/loot_modifiers")
        lootModifierDir.createDirectories()
        lootModifierDir.resolve("grass_seeds.json").writeText("""
            {
              "type": "resmod:grass_drops",
              "neoforge:conditions": [
                {
                  "condition": "minecraft:random_chance",
                  "chance": 0.0625
                },
                {
                  "condition": "minecraft:inverted",
                  "term": {
                    "condition": "minecraft:match_tool",
                    "predicate": {
                      "items": [
                        "minecraft:shears"
                      ]
                    }
                  }
                },
                {
                  "condition": "minecraft:alternative",
                  "terms": [
                    {
                      "condition": "minecraft:random_chance",
                      "chance": 0.25
                    },
                    {
                      "condition": "forge:loot_table_id",
                      "loot_table_id": "minecraft:gameplay/fishing"
                    }
                  ]
                },
                {
                  "condition": "block_state_property",
                  "block": "minecraft:grass"
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val lootModifier = projectDir.resolve("src/main/resources/data/resmod/loot_modifiers/grass_seeds.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-glm-loot-conditions-121" },
            "Global loot modifier condition migration rule should be recorded"
        )
        assertTrue(
            result.changes.any { it.ruleId == "res-legacy-resource-id-renames-121" },
            "Legacy resource id rename rule should be recorded"
        )
        assertTrue(lootModifier.contains(""""conditions":"""))
        assertTrue(lootModifier.contains(""""condition": "minecraft:random_chance""""))
        assertTrue(lootModifier.contains(""""condition": "minecraft:inverted""""))
        assertTrue(lootModifier.contains(""""condition": "minecraft:match_tool""""))
        assertTrue(lootModifier.contains(""""condition": "minecraft:any_of""""))
        assertTrue(lootModifier.contains(""""condition": "neoforge:loot_table_id""""))
        assertTrue(lootModifier.contains(""""condition": "minecraft:block_state_property""""))
        assertTrue(lootModifier.contains(""""block": "minecraft:short_grass""""))
        assertFalse(lootModifier.contains(""""neoforge:conditions""""))
        assertFalse(lootModifier.contains("minecraft:alternative"))
        assertFalse(lootModifier.contains("minecraft:grass"))
    }

    @Test
    fun `source defined project block tags get empty data pack definitions`() {
        val projectDir = setupResourceProject()
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod("resmod")
            public class ExampleMod {
                public static final String MODID = "resmod";
            }
        """.trimIndent())
        srcDir.resolve("ExampleBlockTags.java").writeText("""
            package com.example;

            import net.minecraft.tags.TagKey;
            import net.minecraft.world.level.block.Block;

            public class ExampleBlockTags {
                public static final TagKey<Block> MINEABLE_WITH_KNIFE = TagUtils.modBlockTag("resmod", "mineable_with_knife");
                public static final TagKey<Block> EXTERNAL_HEAT = TagUtils.modBlockTag("othermod", "heat_sources");
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val generatedTag = projectDir.resolve("src/main/resources/data/resmod/tags/block/mineable_with_knife.json")
        assertTrue(result.changes.any { it.ruleId == "res-source-defined-block-tag" })
        assertTrue(generatedTag.exists(), "Project-owned source-defined block tag should be materialized")
        assertTrue(generatedTag.readText().contains(""""values": []"""))
        assertFalse(projectDir.resolve("src/main/resources/data/othermod/tags/block/heat_sources.json").exists())
    }

    @Test
    fun `liquid block registrations get complete level blockstates from registry namespace`() {
        val projectDir = setupResourceProject()
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod("resmod")
            public class ExampleMod {
                public static final String MODID = "resmod";
            }
        """.trimIndent())
        srcDir.resolve("ModFluidBlocks.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.LiquidBlock;
            import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class ModFluidBlocks {
                public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, ExampleMod.MODID);
                public static final DeferredRegister<Block> UNCLEAR_BLOCKS = DeferredRegister.create(Registries.BLOCK, UNKNOWN_MODID);

                public static final Object MOLTEN_TEA = BLOCKS.register("molten_tea", () ->
                    new LiquidBlock(null, BlockBehaviour.Properties.of()));
                public static final Object RICE_WINE = BLOCKS.register("rice_wine", () ->
                    new LiquidBlock(null, BlockBehaviour.Properties.of()));
                public static final Object UNKNOWN = UNCLEAR_BLOCKS.register("should_not_exist", () ->
                    new LiquidBlock(null, BlockBehaviour.Properties.of()));
            }
        """.trimIndent())
        val assetsDir = projectDir.resolve("src/main/resources/assets/resmod")
        assetsDir.resolve("blockstates").createDirectories()
        assetsDir.resolve("models/block").createDirectories()
        assetsDir.resolve("blockstates/molten_tea.json").writeText("""
            {
              "variants": {
                "": {
                  "model": "resmod:block/molten_tea"
                }
              }
            }
        """.trimIndent())
        assetsDir.resolve("models/block/molten_tea.json").writeText("""
            {
              "textures": {
                "particle": "resmod:block/molten_tea"
              }
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val moltenTeaBlockstate = assetsDir.resolve("blockstates/molten_tea.json").readText()
        val riceWineBlockstate = assetsDir.resolve("blockstates/rice_wine.json").readText()
        val riceWineModel = assetsDir.resolve("models/block/rice_wine.json").readText()

        assertTrue(result.changes.any { it.ruleId == "res-liquid-blockstate-level-variants" })
        assertTrue(result.changes.any { it.ruleId == "res-liquid-block-model" })
        assertFalse(moltenTeaBlockstate.contains("\"\":"), "LiquidBlock empty variant should be replaced by level variants")
        assertTrue(moltenTeaBlockstate.contains("\"level=0\""))
        assertTrue(moltenTeaBlockstate.contains("\"level=15\""))
        assertTrue(riceWineBlockstate.contains("\"model\": \"resmod:block/rice_wine\""))
        assertTrue(riceWineBlockstate.contains("\"level=15\""))
        assertTrue(riceWineModel.contains("\"particle\": \"minecraft:block/water_still\""))
        assertFalse(assetsDir.resolve("blockstates/should_not_exist.json").exists())
    }

    @Test
    fun `light block subclass registrations get complete level blockstates`() {
        val projectDir = setupResourceProject()
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val blockDir = projectDir.resolve("src/main/java/com/example/block")
        srcDir.createDirectories()
        blockDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod("resmod")
            public class ExampleMod {
                public static final String MODID = "resmod";
            }
        """.trimIndent())
        blockDir.resolve("SunlightBlock.java").writeText("""
            package com.example.block;

            import net.minecraft.world.level.block.LightBlock;

            public class SunlightBlock extends LightBlock {
                public SunlightBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("ModBlocks.java").writeText("""
            package com.example;

            import com.example.block.*;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class ModBlocks {
                public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, ExampleMod.MODID);

                public static final Object SUNLIGHT = BLOCKS.register("sunlight", () ->
                    new SunlightBlock(BlockBehaviour.Properties.of().noOcclusion()));
            }
        """.trimIndent())
        val assetsDir = projectDir.resolve("src/main/resources/assets/resmod")
        assetsDir.createDirectories()

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val blockstate = assetsDir.resolve("blockstates/sunlight.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-light-blockstate-level-variants" })
        assertTrue(blockstate.contains("\"level=0\""))
        assertTrue(blockstate.contains("\"level=15\""))
        assertFalse(blockstate.contains("waterlogged"), "LightBlock level variants intentionally rely on vanilla partial matching for extra state properties")
        assertTrue(blockstate.contains("\"model\": \"minecraft:block/light_00\""))
        assertTrue(blockstate.contains("\"model\": \"minecraft:block/light_15\""))
    }

    @Test
    fun `model texture grass references migrate to short grass`() {
        val projectDir = setupResourceProject()
        val modelDir = projectDir.resolve("src/generated/resources/assets/resmod/models/block")
        modelDir.createDirectories()
        modelDir.resolve("mossy_log.json").writeText("""
            {
              "parent": "minecraft:block/cube_all",
              "textures": {
                "all": "minecraft:block/grass"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val model = modelDir.resolve("mossy_log.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-legacy-resource-id-renames-121" },
            "Legacy texture resource rename rule should be recorded"
        )
        assertTrue(model.contains(""""all": "minecraft:block/short_grass""""))
        assertFalse(model.contains("minecraft:block/grass"))
    }

    @Test
    fun `sound subtitles are added to english language file`() {
        val projectDir = setupResourceProject()
        val assetsDir = projectDir.resolve("src/main/resources/assets/resmod")
        assetsDir.resolve("lang").createDirectories()
        assetsDir.resolve("sounds.json").writeText("""
            {
              "taiko": {
                "category": "block",
                "subtitle": "resmod.sound.taiko",
                "sounds": ["resmod:taiko_1"]
              }
            }
        """.trimIndent())
        assetsDir.resolve("lang/en_us.json").writeText("""
            {
              "item.resmod.existing": "Existing"
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val lang = assetsDir.resolve("lang/en_us.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-sound-subtitle-lang" })
        assertTrue(lang.contains(""""item.resmod.existing": "Existing""""))
        assertTrue(lang.contains(""""resmod.sound.taiko": "Taiko""""))
    }

    @Test
    fun `loot table looting enchant function is migrated to enchanted count increase`() {
        val projectDir = setupResourceProject()
        val lootDir = projectDir.resolve("src/main/resources/data/resmod/loot_tables/entities")
        lootDir.createDirectories()
        lootDir.resolve("deer.json").writeText("""
            {
              "type": "minecraft:entity",
              "pools": [
                {
                  "rolls": 1.0,
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:leather",
                      "functions": [
                        {
                          "function": "minecraft:looting_enchant",
                          "count": {
                            "type": "minecraft:uniform",
                            "min": 0.0,
                            "max": 1.0
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val lootTable = projectDir.resolve("src/main/resources/data/resmod/loot_table/entities/deer.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-loot-looting-enchant-function" },
            "Loot function migration rule should be recorded"
        )
        assertTrue(lootTable.contains(""""function": "minecraft:enchanted_count_increase""""))
        assertTrue(lootTable.contains(""""enchantment": "minecraft:looting""""))
        assertFalse(lootTable.contains("minecraft:looting_enchant"))
    }

    @Test
    fun `loot table nested table entries and looting chance conditions use 1_21 schema`() {
        val projectDir = setupResourceProject()
        val lootDir = projectDir.resolve("src/generated/resources/data/resmod/loot_table/entities")
        lootDir.createDirectories()
        lootDir.resolve("troll.json").writeText("""
            {
              "type": "minecraft:entity",
              "pools": [
                {
                  "rolls": 1.0,
                  "conditions": [
                    {
                      "condition": "minecraft:random_chance_with_looting",
                      "chance": 0.025,
                      "looting_multiplier": 0.01
                    }
                  ],
                  "entries": [
                    {
                      "type": "minecraft:loot_table",
                      "name": "minecraft:entities/zombie",
                      "weight": 2
                    },
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:rotten_flesh"
                    }
                  ]
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val lootTable = lootDir.resolve("troll.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-loot-table-entry-name-to-value" })
        assertTrue(result.changes.any { it.ruleId == "res-loot-random-chance-with-looting-121" })
        assertTrue(lootTable.contains(""""type": "minecraft:loot_table""""))
        assertTrue(lootTable.contains(""""value": "minecraft:entities/zombie""""))
        assertTrue(lootTable.contains(""""name": "minecraft:rotten_flesh""""))
        assertTrue(lootTable.contains(""""condition": "minecraft:random_chance_with_enchanted_bonus""""))
        assertTrue(lootTable.contains(""""enchantment": "minecraft:looting""""))
        assertTrue(lootTable.contains(""""unenchanted_chance": 0.025"""))
        assertTrue(lootTable.contains(""""base": 0.035"""))
        assertTrue(lootTable.contains(""""per_level_above_first": 0.01"""))
        assertFalse(lootTable.contains("minecraft:random_chance_with_looting"))
        assertFalse(lootTable.contains("looting_multiplier"))
    }

    @Test
    fun `banner pattern item tags materialize data driven banner patterns`() {
        val projectDir = setupResourceProject()
        val tagDir = projectDir.resolve("src/generated/resources/data/resmod/tags/banner_pattern/pattern_item")
        tagDir.createDirectories()
        tagDir.resolve("naga.json").writeText("""
            {
              "values": [
                "resmod:naga"
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val bannerPattern = projectDir.resolve("src/generated/resources/data/resmod/banner_pattern/naga.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-banner-pattern-data-resource" })
        assertTrue(bannerPattern.contains(""""asset_id": "resmod:naga""""))
        assertTrue(bannerPattern.contains(""""translation_key": "block.minecraft.banner.resmod.naga""""))
    }

    @Test
    fun `common item tags migrate to c namespace in recipes and advancements`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("tagged.json").writeText("""
            {"type":"minecraft:crafting_shapeless","ingredients":[{"tag":"forge:ingots/iron"}],"result":{"item":"resmod:item"}}
        """.trimIndent())
        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("tagged.json").writeText("""
            {"criteria":{"has_iron":{"trigger":"minecraft:inventory_changed","conditions":{"items":[{"tag":"forge:ingots/iron"}]}}}}
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/tagged.json").readText()
        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/tagged.json").readText()
        assertTrue(recipe.contains(""""tag": "c:ingots/iron""""))
        assertFalse(recipe.contains("forge:ingots/iron"))
        assertFalse(recipe.contains("neoforge:ingots/iron"))
        assertTrue(advancement.contains(""""items": "#c:ingots/iron""""))
        assertFalse(advancement.contains("#forge:ingots/iron"))
        assertFalse(advancement.contains("#neoforge:ingots/iron"))
    }

    @Test
    fun `tag references migrate to c namespace in generated data`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        recipeDir.createDirectories()
        recipeDir.resolve("table.json").writeText("""
            {"type":"minecraft:crafting_shaped","key":{"L":{"tag":"forge:lumber"}},"result":{"item":"resmod:table"}}
        """.trimIndent())
        recipeDir.resolve("lantern.json").writeText("""
            {"type":"minecraft:crafting_shaped","key":{"C":{"tag":"c:cobblestone"},"G":{"tag":"forge:glass"},"S":{"tag":"neoforge:stone"}},"result":{"item":"resmod:lantern"}}
        """.trimIndent())
        val advancementDir = projectDir.resolve("src/generated/resources/data/resmod/advancements")
        advancementDir.createDirectories()
        advancementDir.resolve("table.json").writeText("""
            {"criteria":{"has_lumber":{"trigger":"minecraft:inventory_changed","conditions":{"items":[{"items":"#forge:lumber"}]}}}}
        """.trimIndent())
        advancementDir.resolve("lantern.json").writeText("""
            {"criteria":{"has_cobblestone":{"trigger":"minecraft:inventory_changed","conditions":{"items":[{"items":"#c:cobblestone"},{"items":"#forge:glass"},{"items":"#neoforge:stone"}]}}}}
        """.trimIndent())
        val tagDir = projectDir.resolve("src/generated/resources/data/c/tags/items")
        tagDir.createDirectories()
        tagDir.resolve("lumber.json").writeText("""{"values":["#forge:lumber/sakura","#neoforge:lumber/maple"]}""")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/table.json").readText()
        val lanternRecipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/lantern.json").readText()
        val advancement = projectDir.resolve("src/generated/resources/data/resmod/advancement/table.json").readText()
        val lanternAdvancement = projectDir.resolve("src/generated/resources/data/resmod/advancement/lantern.json").readText()
        val tag = projectDir.resolve("src/generated/resources/data/c/tags/item/lumber.json").readText()
        assertTrue(recipe.contains(""""tag": "c:lumber""""))
        assertTrue(lanternRecipe.contains(""""tag": "c:cobblestones""""))
        assertTrue(lanternRecipe.contains(""""tag": "c:glass_blocks""""))
        assertTrue(lanternRecipe.contains(""""tag": "c:stones""""))
        assertTrue(advancement.contains(""""items": "#c:lumber""""))
        assertTrue(lanternAdvancement.contains(""""items": "#c:cobblestones""""))
        assertTrue(lanternAdvancement.contains(""""items": "#c:glass_blocks""""))
        assertTrue(lanternAdvancement.contains(""""items": "#c:stones""""))
        assertTrue(tag.contains(""""#c:lumber/sakura""""))
        assertTrue(tag.contains(""""#c:lumber/maple""""))
        assertFalse(recipe.contains("forge:lumber"))
        assertFalse(lanternRecipe.contains(""""tag": "c:cobblestone""""))
        assertFalse(lanternRecipe.contains(""""tag": "c:glass""""))
        assertFalse(lanternRecipe.contains(""""tag": "c:stone""""))
        assertFalse(recipe.contains("neoforge:lumber"))
        assertFalse(advancement.contains("#forge:lumber"))
        assertFalse(lanternAdvancement.contains("#c:cobblestone\""))
        assertFalse(lanternAdvancement.contains("#c:glass\""))
        assertFalse(lanternAdvancement.contains("#c:stone\""))
        assertFalse(tag.contains("#forge:"))
        assertFalse(tag.contains("#neoforge:"))
    }

    @Test
    fun `common gravel and sand tags migrate to 1_21 common tag paths`() {
        val projectDir = setupResourceProject()
        val tagDir = projectDir.resolve("src/generated/resources/data/resmod/tags/blocks/ore_magnet")
        tagDir.createDirectories()
        tagDir.resolve("safe_replace.json").writeText("""
            {
              "values": [
                "#c:gravel",
                "#c:sand",
                "#forge:gravel",
                "#forge:sand"
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val tag = projectDir.resolve("src/generated/resources/data/resmod/tags/block/ore_magnet/safe_replace.json").readText()
        assertTrue(tag.contains(""""#c:gravels""""))
        assertTrue(tag.contains(""""#c:sands""""))
        assertFalse(tag.contains("#c:gravel\""))
        assertFalse(tag.contains("#c:sand\""))
        assertFalse(tag.contains("#forge:"))
    }

    @Test
    fun `common leather string tags and mcfunction item nbt migrate to 1_21 syntax`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        val advancementDir = projectDir.resolve("src/generated/resources/data/resmod/advancements/recipes/tools")
        val functionDir = projectDir.resolve("src/main/resources/data/resmod/functions")
        recipeDir.createDirectories()
        advancementDir.createDirectories()
        functionDir.createDirectories()
        recipeDir.resolve("gloves.json").writeText("""
            {
              "type": "minecraft:crafting_shaped",
              "key": {
                "#": {
                  "tag": "forge:leather"
                },
                "S": {
                  "tag": "c:string"
                }
              },
              "pattern": [
                "#S#"
              ],
              "result": "resmod:gloves"
            }
        """.trimIndent())
        advancementDir.resolve("gloves.json").writeText("""
            {
              "criteria": {
                "has_string": {
                  "trigger": "minecraft:inventory_changed",
                  "conditions": {
                    "items": [
                      {
                        "items": "#c:string"
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent())
        functionDir.resolve("suit_up.mcfunction").writeText("""
            give @p resmod:hammer{Unbreakable:1,CustomFlag:1b}
            execute at @p run fill ~-1 ~ ~-1 ~1 ~1 ~1 minecraft:air replace #forge:stone
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/gloves.json").readText()
        val advancement = projectDir.resolve("src/generated/resources/data/resmod/advancement/recipes/tools/gloves.json").readText()
        val function = projectDir.resolve("src/main/resources/data/resmod/function/suit_up.mcfunction").readText()

        assertTrue(result.changes.any { it.ruleId == "res-mcfunction-common-tag-reference" })
        assertTrue(result.changes.any { it.ruleId == "res-mcfunction-itemstack-components" })
        assertTrue(recipe.contains(""""tag": "c:leathers""""))
        assertTrue(recipe.contains(""""tag": "c:strings""""))
        assertTrue(advancement.contains(""""items": "#c:strings""""))
        assertTrue(function.contains("resmod:hammer["))
        assertTrue(function.contains("minecraft:unbreakable={}"))
        assertTrue(function.contains("minecraft:custom_data={CustomFlag:1b}"))
        assertTrue(function.contains("#c:stones"))
        assertFalse(recipe.contains("forge:leather"))
        assertFalse(recipe.contains("c:string\""))
        assertFalse(advancement.contains("#c:string\""))
        assertFalse(function.contains("{Unbreakable:1"))
        assertFalse(function.contains("#forge:stone"))
    }

    @Test
    fun `partial nbt recipe ingredients migrate to data component ingredients`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        recipeDir.createDirectories()
        recipeDir.resolve("charged_staff.json").writeText("""
            {
              "type": "minecraft:crafting_shapeless",
              "ingredients": [
                [
                  {
                    "type": "forge:partial_nbt",
                    "item": "minecraft:potion",
                    "nbt": "{Potion:\"minecraft:strength\"}"
                  },
                  {
                    "type": "forge:partial_nbt",
                    "item": "minecraft:potion",
                    "nbt": "{Potion:\"minecraft:long_strength\"}"
                  }
                ],
                {
                  "type": "forge:partial_nbt",
                  "item": "resmod:charged_staff",
                  "nbt": "{Damage:9}"
                }
              ],
              "result": {
                "item": "resmod:charged_staff"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/charged_staff.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-recipe-partial-nbt-component-ingredient" })
        assertTrue(recipe.contains(""""type": "neoforge:compound""""))
        assertTrue(recipe.contains(""""type": "neoforge:components""""))
        assertTrue(recipe.contains(""""minecraft:potion_contents": {"""))
        assertTrue(recipe.contains(""""potion": "minecraft:strength""""))
        assertTrue(recipe.contains(""""minecraft:damage": 9"""))
        assertTrue(recipe.contains(""""items": "resmod:charged_staff""""))
        assertFalse(recipe.contains("partial_nbt"))
        assertFalse(recipe.contains(""""type": "neoforge:partial_nbt""""))
        assertFalse(recipe.contains(""""type": "forge:partial_nbt""""))
    }

    @Test
    fun `legacy nbt recipe ingredients migrate to data component ingredients`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        recipeDir.createDirectories()
        recipeDir.resolve("halo.json").writeText("""
            {
              "type": "minecraft:crafting_shapeless",
              "ingredients": [
                {
                  "type": "forge:nbt",
                  "item": "resmod:divine_shard",
                  "nbt": "{SMCShardChallenge:7}"
                }
              ],
              "result": {
                "item": "resmod:halo"
              }
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/halo.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-recipe-partial-nbt-component-ingredient" })
        assertTrue(recipe.contains(""""type": "neoforge:components""""))
        assertTrue(recipe.contains(""""items": "resmod:divine_shard""""))
        assertTrue(recipe.contains(""""minecraft:custom_data": {"""))
        assertTrue(recipe.contains(""""SMCShardChallenge": 7"""))
        assertFalse(recipe.contains(""""type": "neoforge:nbt""""))
        assertFalse(recipe.contains(""""type": "forge:nbt""""))
    }

    @Test
    fun `damaged item repair recipes do not synthesize mod specific scepter serializers`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes/equipment")
        val javaDir = projectDir.resolve("src/main/java/resmod/init")
        recipeDir.createDirectories()
        javaDir.createDirectories()
        javaDir.resolve("ModRecipes.java").writeText("""
            package resmod.init;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class ModRecipes {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, "resmod");
            }
        """.trimIndent())
        recipeDir.resolve("zombie_scepter.json").writeText("""
            {
              "type": "minecraft:crafting_shapeless",
              "category": "equipment",
              "ingredients": [
                [
                  {
                    "type": "forge:partial_nbt",
                    "item": "minecraft:potion",
                    "nbt": "{Potion:\"minecraft:strength\"}"
                  },
                  {
                    "type": "forge:partial_nbt",
                    "item": "minecraft:potion",
                    "nbt": "{Potion:\"minecraft:long_strength\"}"
                  }
                ],
                {
                  "type": "forge:partial_nbt",
                  "item": "resmod:zombie_scepter",
                  "nbt": "{Damage:9}"
                },
                {
                  "item": "minecraft:rotten_flesh"
                }
              ],
              "result": {
                "item": "resmod:zombie_scepter"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/equipment/zombie_scepter.json").readText()
        val generatedSerializer = projectDir.resolve("src/main/java/com/modporter/generated/resmod/recipe/ModPorterScepterRepairRecipe.java")
        val registry = javaDir.resolve("ModRecipes.java").readText()
        assertTrue(result.changes.any { it.ruleId == "res-recipe-partial-nbt-component-ingredient" })
        assertFalse(result.changes.any { it.ruleId.contains("scepter") })
        assertTrue(recipe.contains(""""type": "minecraft:crafting_shapeless""""))
        assertTrue(recipe.contains(""""type": "neoforge:compound""""))
        assertTrue(recipe.contains(""""type": "neoforge:components""""))
        assertTrue(recipe.contains(""""item": "minecraft:rotten_flesh""""))
        assertTrue(recipe.contains(""""result":"""))
        assertFalse(recipe.contains("scepter_repair"))
        assertFalse(generatedSerializer.exists())
        assertFalse(registry.contains("ModPorterScepterRepairRecipe"))
        assertFalse(registry.contains("scepter_repair"))
        assertFalse(recipe.contains("partial_nbt"))
    }

    @Test
    fun `uncrafting recipes use 1_21 input count with ingredient objects`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes/uncrafting")
        recipeDir.createDirectories()
        recipeDir.resolve("tipped_arrow.json").writeText("""
            {
              "type": "resmod:uncrafting",
              "cost": 4,
              "input": {
                "count": 8,
                "ingredient": {
                  "item": "minecraft:tipped_arrow"
                }
              },
              "key": {
                "A": {
                  "item": "minecraft:arrow"
                }
              },
              "pattern": [
                "AAA",
                "A A",
                "AAA"
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/uncrafting/tipped_arrow.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-uncrafting-recipe-input-count-121" })
        assertTrue(recipe.contains(""""input": {"""))
        assertTrue(recipe.contains(""""item": "minecraft:tipped_arrow""""))
        assertTrue(recipe.contains(""""input_count": 8"""))
        assertTrue(recipe.contains(""""A": {"""))
        assertTrue(recipe.contains(""""item": "minecraft:arrow""""))
        assertFalse(recipe.contains(""""ingredient":"""))
    }

    @Test
    fun `single conditional advancement is unwrapped with top level neoforge conditions`() {
        val projectDir = setupResourceProject()
        val advancementDir = projectDir.resolve("src/generated/resources/data/resmod/advancements/recipes/decorations")
        advancementDir.createDirectories()
        advancementDir.resolve("conditional_table.json").writeText("""
            {
              "advancements": [
                {
                  "advancement": {
                    "parent": "minecraft:recipes/root",
                    "criteria": {
                      "has_the_recipe": {
                        "conditions": {
                          "recipe": "resmod:conditional_table"
                        },
                        "trigger": "minecraft:recipe_unlocked"
                      }
                    },
                    "rewards": {
                      "recipes": [
                        "resmod:conditional_table"
                      ]
                    }
                  },
                  "conditions": [
                    {
                      "type": "resmod:enabled"
                    }
                  ]
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val advancement = projectDir.resolve("src/generated/resources/data/resmod/advancement/recipes/decorations/conditional_table.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-advancement-unwrap-single-conditional" })
        assertTrue(advancement.contains(""""neoforge:conditions": ["""))
        assertTrue(advancement.contains(""""parent": "minecraft:recipes/root""""))
        assertTrue(advancement.contains(""""conditions": {"""), "Criterion predicate conditions should stay vanilla")
        assertFalse(advancement.contains(""""advancements":"""))
        assertFalse(advancement.contains(""""advancement":"""))
    }

    @Test
    fun `code awarded advancement uses impossible trigger and id icon`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("Awarder.java").writeText("""
            package com.example;

            class Awarder {
                private static final String ADVANCEMENT_ID = "resmod:manual";

                void award(Object player) {
                    AdvancementHelper.tryAwardAdvancement(player, ADVANCEMENT_ID, "code_triggered");
                }
            }
        """.trimIndent())

        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("manual.json").writeText("""
            {"id":"resmod:manual","display":{"icon":{"item":"resmod:item"}},"criteria":{"code_triggered":{"trigger":"resmod:manual"}}}
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/manual.json").readText()
        assertFalse(advancement.contains(""""id":"resmod:manual""""), "Top-level legacy id should be removed")
        assertTrue(advancement.contains(""""icon":{"id":"resmod:item"}"""), "Advancement icon should use id")
        assertTrue(advancement.contains(""""trigger": "minecraft:impossible""""), "Code-awarded criterion should use impossible trigger")
        assertFalse(advancement.contains(""""trigger":"resmod:manual""""))
    }

    @Test
    fun `code awarded advancement resolves qualified ids from declared class owner`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("Awarder.java").writeText("""
            package com.example;

            class Awarder {
                private static final String ADVANCEMENT_ID = "resmod:qualified";

                void award(Object player) {
                    AdvancementHelper.tryAwardAdvancement(player, Awarder.ADVANCEMENT_ID, "code_triggered");
                }
            }
        """.trimIndent())

        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("qualified.json").writeText("""
            {"display":{"icon":{"item":"resmod:item"}},"criteria":{"code_triggered":{"trigger":"resmod:qualified"}}}
        """.trimIndent())

        ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/qualified.json").readText()
        assertTrue(advancement.contains(""""trigger": "minecraft:impossible""""), advancement)
        assertFalse(advancement.contains(""""trigger":"resmod:qualified""""))
    }

    @Test
    fun `code awarded advancement does not infer constant owner from file name`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("Awarder.java").writeText("""
            package com.example;

            static final String ADVANCEMENT_ID = "resmod:manual";

            class Caller {
                void award(Object player) {
                    AdvancementHelper.tryAwardAdvancement(player, Awarder.ADVANCEMENT_ID, "code_triggered");
                }
            }
        """.trimIndent())

        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("manual.json").writeText("""
            {"display":{"icon":{"item":"resmod:item"}},"criteria":{"code_triggered":{"trigger":"resmod:manual"}}}
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/manual.json").readText()
        assertFalse(
            result.changes.any { it.ruleId == "res-advancement-code-awarded-trigger" },
            "Unsupported source shape must not be converted into code-awarded advancement evidence"
        )
        assertTrue(advancement.contains(""""trigger":"resmod:manual""""), advancement)
        assertFalse(advancement.contains("minecraft:impossible"), advancement)
    }

    @Test
    fun `code awarded advancement does not infer constant owner from previous type`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("Awarder.java").writeText("""
            package com.example;

            class Awarder {
            }

            static final String ADVANCEMENT_ID = "resmod:manual";

            class Caller {
                void award(Object player) {
                    AdvancementHelper.tryAwardAdvancement(player, Awarder.ADVANCEMENT_ID, "code_triggered");
                }
            }
        """.trimIndent())

        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("manual.json").writeText("""
            {"display":{"icon":{"item":"resmod:item"}},"criteria":{"code_triggered":{"trigger":"resmod:manual"}}}
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/manual.json").readText()
        assertFalse(
            result.changes.any { it.ruleId == "res-advancement-code-awarded-trigger" },
            "Constants outside a Java type body must not be assigned to the preceding type"
        )
        assertTrue(advancement.contains(""""trigger":"resmod:manual""""), advancement)
        assertFalse(advancement.contains("minecraft:impossible"), advancement)
    }

    @Test
    fun `code awarded advancement does not resolve bare ids by global uniqueness`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("Awarder.java").writeText("""
            package com.example;

            class Awarder {
                static final String ADVANCEMENT_ID = "resmod:manual";
            }

            class Caller {
                void award(Object player) {
                    AdvancementHelper.tryAwardAdvancement(player, ADVANCEMENT_ID, "code_triggered");
                }
            }
        """.trimIndent())

        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("manual.json").writeText("""
            {"display":{"icon":{"item":"resmod:item"}},"criteria":{"code_triggered":{"trigger":"resmod:manual"}}}
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/manual.json").readText()
        assertFalse(
            result.changes.any { it.ruleId == "res-advancement-code-awarded-trigger" },
            "Bare advancement ids must resolve from the call-site owner, not from globally unique constants"
        )
        assertTrue(advancement.contains(""""trigger":"resmod:manual""""), advancement)
        assertFalse(advancement.contains("minecraft:impossible"), advancement)
    }

    @Test
    fun `code awarded advancement ignores text block call examples`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("Awarder.java").writeText(listOf(
            "package com.example;",
            "",
            "class Awarder {",
            "    private static final String ADVANCEMENT_ID = \"resmod:manual\";",
            "",
            "    String docs() {",
            "        return \"\"\"",
            "            AdvancementHelper.tryAwardAdvancement(player, ADVANCEMENT_ID, \"code_triggered\");",
            "            \"\"\";",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))

        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("manual.json").writeText("""
            {"display":{"icon":{"item":"resmod:item"}},"criteria":{"code_triggered":{"trigger":"resmod:manual"}}}
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/manual.json").readText()
        assertFalse(
            result.changes.any { it.ruleId == "res-advancement-code-awarded-trigger" },
            "Documentation examples must not be converted into code-awarded advancement evidence"
        )
        assertTrue(advancement.contains(""""trigger":"resmod:manual""""), advancement)
        assertFalse(advancement.contains("minecraft:impossible"), advancement)
    }

    @Test
    fun `forge item layers model loader is migrated without changing model structure`() {
        val projectDir = setupResourceProject()
        val modelDir = projectDir.resolve("src/main/resources/assets/resmod/models/item")
        modelDir.createDirectories()
        modelDir.resolve("layered.json").writeText("""
            {
              "loader": "forge:item_layers",
              "parent": "minecraft:item/generated",
              "textures": {
                "layer0": "minecraft:item/bucket",
                "layer1": "resmod:item/overlay"
              },
              "overrides": [
                {
                  "predicate": { "resmod:hot_steam": 1 },
                  "model": "resmod:item/layered_with_smoke"
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val model = projectDir.resolve("src/main/resources/assets/resmod/models/item/layered.json").readText()
        assertTrue(model.contains(""""loader": "neoforge:item_layers""""))
        assertTrue(model.contains(""""overrides":"""), "Existing predicate override structure should be preserved")
        assertTrue(model.contains(""""model": "resmod:item/layered_with_smoke""""))
        assertFalse(model.contains(""""loader": "forge:item_layers""""))
    }

    @Test
    fun `forge model extension metadata and loaders are migrated to neoforge namespace`() {
        val projectDir = setupResourceProject()
        val blockModelDir = projectDir.resolve("src/main/resources/assets/resmod/models/block")
        val itemModelDir = projectDir.resolve("src/main/resources/assets/resmod/models/item")
        blockModelDir.createDirectories()
        itemModelDir.createDirectories()
        blockModelDir.resolve("glowing_block.json").writeText("""
            {
              "loader": "forge:composite",
              "children": {
                "main": {
                  "parent": "resmod:block/main",
                  "elements": [
                    {
                      "faces": {
                        "north": {
                          "texture": "#all",
                          "forge_data": {
                            "block_light": 15,
                            "sky_light": 15
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent())
        itemModelDir.resolve("separate.json").writeText("""
            {
              "loader": "forge:separate_transforms",
              "base": {
                "parent": "minecraft:item/generated"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val blockModel = blockModelDir.resolve("glowing_block.json").readText()
        val itemModel = itemModelDir.resolve("separate.json").readText()

        assertTrue(result.changes.any { it.ruleId == "res-model-extension-neoforge-namespace" })
        assertTrue(blockModel.contains(""""loader": "neoforge:composite""""))
        assertTrue(blockModel.contains(""""neoforge_data": {"""))
        assertTrue(itemModel.contains(""""loader": "neoforge:separate_transforms""""))
        assertFalse(blockModel.contains(""""forge_data""""))
        assertFalse(blockModel.contains(""""forge:composite""""))
        assertFalse(itemModel.contains(""""forge:separate_transforms""""))
    }

    @Test
    fun `model texture references that target item models resolve to concrete textures`() {
        val projectDir = setupResourceProject()
        val itemModelDir = projectDir.resolve("src/main/resources/assets/resmod/models/item")
        val itemTextureDir = projectDir.resolve("src/main/resources/assets/resmod/textures/item")
        itemModelDir.createDirectories()
        itemTextureDir.createDirectories()
        itemModelDir.resolve("transforming_blade.json").writeText("""
            {
              "loader": "forge:separate_transforms",
              "base": {
                "parent": "resmod:item/transforming_blade_handheld"
              },
              "perspectives": {
                "gui": {
                  "parent": "resmod:item/transforming_blade_gui"
                }
              },
              "textures": {
                "layer0": "resmod:item/transforming_blade_gui"
              }
            }
        """.trimIndent())
        itemModelDir.resolve("transforming_blade_gui.json").writeText("""
            {
              "parent": "minecraft:item/generated",
              "textures": {
                "layer0": "resmod:item/transforming_blade"
              }
            }
        """.trimIndent())
        itemTextureDir.resolve("transforming_blade.png").writeText("png")

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val model = itemModelDir.resolve("transforming_blade.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-model-texture-reference-model-target" })
        assertTrue(model.contains(""""loader": "neoforge:separate_transforms""""))
        assertTrue(model.contains(""""parent": "resmod:item/transforming_blade_gui""""))
        assertTrue(model.contains(""""layer0": "resmod:item/transforming_blade""""))
        assertFalse(model.contains(""""layer0": "resmod:item/transforming_blade_gui""""))
    }

    @Test
    fun `registered item with texture gets missing generated model`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("ItemRegister.java").writeText("""
            package com.example;

            class ItemRegister {
                static final Registry ITEMS = null;

                static void register() {
                    ITEMS.register("sample_item", () -> new Item(new Item.Properties()));
                }
            }
        """.trimIndent())
        val textureDir = projectDir.resolve("src/main/resources/assets/resmod/textures/item")
        textureDir.createDirectories()
        textureDir.resolve("sample_item.png").writeText("png")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val model = projectDir.resolve("src/main/resources/assets/resmod/models/item/sample_item.json").readText()
        assertTrue(model.contains(""""parent": "minecraft:item/generated""""))
        assertTrue(model.contains(""""layer0": "resmod:item/sample_item""""))
    }

    @Test
    fun `registered item text block documentation does not generate missing model`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("ItemRegister.java").writeText(listOf(
            "package com.example;",
            "",
            "class ItemRegister {",
            "    static void docs() {",
            "        String example = \"\"\"",
            "            ITEMS.register(\"sample_item\", () -> new Item(new Item.Properties()));",
            "            \"\"\";",
            "        if (example.isEmpty()) throw new IllegalStateException();",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))
        val textureDir = projectDir.resolve("src/main/resources/assets/resmod/textures/item")
        textureDir.createDirectories()
        textureDir.resolve("sample_item.png").writeText("png")

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val model = projectDir.resolve("src/main/resources/assets/resmod/models/item/sample_item.json")
        assertFalse(result.changes.any { it.ruleId == "res-create-missing-item-model" })
        assertFalse(model.exists(), "Documentation-only item registration must not create a model")
    }

    @Test
    fun `generated resources do not duplicate item models already in main resources`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("ItemRegister.java").writeText("""
            package com.example;

            class ItemRegister {
                static final Registry ITEMS = null;

                static void register() {
                    ITEMS.register("sample_item", () -> new Item(new Item.Properties()));
                }
            }
        """.trimIndent())
        val mainModelDir = projectDir.resolve("src/main/resources/assets/resmod/models/item")
        mainModelDir.createDirectories()
        mainModelDir.resolve("sample_item.json").writeText("""{"parent":"minecraft:item/generated"}""")
        val generatedTextureDir = projectDir.resolve("src/generated/resources/assets/resmod/textures/item")
        generatedTextureDir.createDirectories()
        generatedTextureDir.resolve("sample_item.png").writeText("png")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        assertFalse(projectDir.resolve("src/generated/resources/assets/resmod/models/item/sample_item.json").exists())
        assertTrue(mainModelDir.resolve("sample_item.json").exists())
    }

    @Test
    fun `registered item without matching texture does not guess model texture from class names`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("ItemRegister.java").writeText("""
            package com.example;

            class ItemRegister {
                static final Registry ITEMS = null;

                static void register() {
                    ITEMS.register("descriptive_item", () -> new DescriptiveItem(new Item.Properties()));
                }
            }
        """.trimIndent())
        val textureDir = projectDir.resolve("src/main/resources/assets/resmod/textures/item")
        textureDir.createDirectories()
        textureDir.resolve("bath_herb.png").writeText("png")

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val model = projectDir.resolve("src/main/resources/assets/resmod/models/item/descriptive_item.json")
        assertFalse(result.changes.any { it.ruleId == "res-create-missing-item-model" })
        assertFalse(model.exists(), "Item model generation must not map arbitrary nonmatching textures by item or class names")
    }

    @Test
    fun `item textures are resized to mipmap compatible dimensions`() {
        val projectDir = setupResourceProject()
        val textureDir = projectDir.resolve("src/main/resources/assets/resmod/textures/item")
        textureDir.createDirectories()
        val texture = textureDir.resolve("odd_sized.png")
        ImageIO.write(BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB), "png", texture.toFile())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val resized = ImageIO.read(texture.toFile())
        assertEquals(32, resized.width)
        assertEquals(32, resized.height)
    }

    @Test
    fun `updates pack format`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val packMcmeta = projectDir.resolve("src/main/resources/pack.mcmeta").readText()
        assertTrue(packMcmeta.contains("\"pack_format\": 48") || packMcmeta.contains("\"pack_format\":48"),
            "Pack format should be updated to 48 (data pack format)")
        assertTrue(packMcmeta.contains("supported_formats"), "Should include supported_formats range")
    }

    @Test
    fun `generated worldgen provider value min max object is flattened`() {
        val projectDir = tempDir.resolve("worldgenmod")
        val feature = projectDir.resolve(
            "src/generated/resources/data/sakura/worldgen/configured_feature/bamboo_column.json"
        )
        feature.parent.createDirectories()
        feature.writeText("""
            {
              "type": "minecraft:bamboo",
              "config": {
                "height": {
                  "type": "minecraft:biased_to_bottom",
                  "value": {
                    "max_inclusive": 18,
                    "min_inclusive": 9
                  }
                }
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val migrated = feature.readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-worldgen-provider-value-flatten" },
            "Worldgen provider flatten rule should be recorded"
        )
        assertTrue(migrated.contains(""""type": "minecraft:biased_to_bottom""""))
        assertTrue(migrated.contains(""""max_inclusive": 18"""))
        assertTrue(migrated.contains(""""min_inclusive": 9"""))
        assertFalse(migrated.contains(""""value": {"""), "1.21.1 provider min/max fields should not remain nested")
    }

    @Test
    fun `generated dimension type spawn light provider value object is flattened`() {
        val projectDir = tempDir.resolve("dimensiontypemod")
        val dimensionType = projectDir.resolve(
            "src/generated/resources/data/resmod/dimension_type/example_type.json"
        )
        dimensionType.parent.createDirectories()
        dimensionType.writeText("""
            {
              "monster_spawn_light_level": {
                "type": "minecraft:uniform",
                "value": {
                  "max_inclusive": 7,
                  "min_inclusive": 0
                }
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val migrated = dimensionType.readText()
        assertTrue(result.changes.any { it.ruleId == "res-worldgen-provider-value-flatten" })
        assertTrue(migrated.contains(""""type": "minecraft:uniform""""))
        assertTrue(migrated.contains(""""max_inclusive": 7"""))
        assertTrue(migrated.contains(""""min_inclusive": 0"""))
        assertFalse(migrated.contains(""""value": {"""))
    }

    @Test
    fun `generated no structure placement modifier value object is flattened`() {
        val projectDir = tempDir.resolve("placementmod")
        val placedFeature = projectDir.resolve(
            "src/generated/resources/data/resmod/worldgen/placed_feature/big_mushroom.json"
        )
        placedFeature.parent.createDirectories()
        placedFeature.writeText("""
            {
              "feature": "resmod:mushroom/big_mushroom",
              "placement": [
                {
                  "type": "resmod:no_structure",
                  "value": {
                    "additional_clearance": 0,
                    "occupies_surface": true,
                    "occupies_underground": false
                  }
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val migrated = placedFeature.readText()
        assertTrue(result.changes.any { it.ruleId == "res-no-structure-placement-flatten" })
        assertTrue(migrated.contains(""""type": "resmod:no_structure""""))
        assertTrue(migrated.contains(""""additional_clearance": 0"""))
        assertTrue(migrated.contains(""""occupies_surface": true"""))
        assertTrue(migrated.contains(""""occupies_underground": false"""))
        assertTrue(migrated.contains(""""occupies_vegetation": false"""))
        assertTrue(migrated.contains(""""structures_allowed": ["""))
        assertFalse(migrated.contains(""""value": {"""))
    }

    @Test
    fun `legacy Nitrogen fuel menu textures generate 1_21 sprites from source pixels`() {
        val projectDir = tempDir.resolve("nitrogenfuel")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ExampleFuelCategory.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleFuelCategory extends AbstractFuelCategory {
                public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "textures/gui/menu/altar.png");
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(176, 0, 0xFF112233.toInt())
        source.setRGB(189, 13, 0xFF445566.toInt())
        source.setRGB(56, 35, 0xFF778899.toInt())
        source.setRGB(69, 48, 0xFF99AABB.toInt())
        ImageIO.write(source, "png", textureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val icon = ImageIO.read(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").toFile())
        val background = ImageIO.read(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_background.png").toFile())
        assertTrue(result.changes.any { it.ruleId == "res-nitrogen-fuel-icon-sprite" })
        assertTrue(result.changes.any { it.ruleId == "res-nitrogen-fuel-background-sprite" })
        assertEquals(14, icon.width)
        assertEquals(14, icon.height)
        assertEquals(0xFF112233.toInt(), icon.getRGB(0, 0))
        assertEquals(0xFF445566.toInt(), icon.getRGB(13, 13))
        assertEquals(14, background.width)
        assertEquals(14, background.height)
        assertEquals(0xFF778899.toInt(), background.getRGB(0, 0))
        assertEquals(0xFF99AABB.toInt(), background.getRGB(13, 13))
    }

    @Test
    fun `legacy Nitrogen fuel sprite generation resolves texture across resource roots once`() {
        val projectDir = tempDir.resolve("nitrogenfuel-multi-root")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val mainTextureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        val generatedAssetsDir = projectDir.resolve("src/generated/resources/assets/example")
        srcDir.createDirectories()
        mainTextureDir.createDirectories()
        generatedAssetsDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ExampleFuelCategory.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.integration.rei.categories.fuel.AbstractFuelCategory;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleFuelCategory {
                public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "textures/gui/menu/altar.png");

                void register(Object registry, Object recipeType) {
                    registry.add(new AbstractFuelCategory(recipeType, TEXTURE) {});
                }
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(176, 0, 0xFF0A1B2C.toInt())
        source.setRGB(56, 35, 0xFF3D4E5F.toInt())
        ImageIO.write(source, "png", mainTextureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(result.errors.isEmpty(), "Expected the main resource texture to satisfy all roots: ${result.errors}")
        assertTrue(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").exists())
        assertTrue(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_background.png").exists())
        assertFalse(projectDir.resolve("src/generated/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").exists())
        assertFalse(projectDir.resolve("src/generated/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_background.png").exists())
        assertEquals(1, result.changes.count { it.ruleId == "res-nitrogen-fuel-icon-sprite" })
        assertEquals(1, result.changes.count { it.ruleId == "res-nitrogen-fuel-background-sprite" })
    }

    @Test
    fun `legacy Nitrogen fuel sprite generation rejects unresolved qualified namespace constants`() {
        val projectDir = tempDir.resolve("nitrogenfuel-unresolved")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ExampleFuelCategory.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleFuelCategory extends AbstractFuelCategory {
                public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MissingMod.MODID, "textures/gui/menu/altar.png");
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(source, "png", textureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any {
                it.contains("Cannot resolve Nitrogen fuel texture namespace expression 'MissingMod.MODID'")
            },
            result.errors.joinToString("\n")
        )
        assertFalse(result.changes.any { it.ruleId == "res-nitrogen-fuel-icon-sprite" })
        assertFalse(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").exists())
    }

    @Test
    fun `legacy Nitrogen fuel sprite generation rejects bare namespace constants from other owners`() {
        val projectDir = tempDir.resolve("nitrogenfuel-global-bare-namespace")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ExampleFuelCategory.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleFuelCategory extends AbstractFuelCategory {
                public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MODID, "textures/gui/menu/altar.png");
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(source, "png", textureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any {
                it.contains("Cannot resolve Nitrogen fuel texture namespace expression 'MODID'")
            },
            result.errors.joinToString("\n")
        )
        assertFalse(result.changes.any { it.ruleId == "res-nitrogen-fuel-icon-sprite" })
        assertFalse(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").exists())
    }

    @Test
    fun `legacy Nitrogen fuel sprite generation ignores text block namespace constants`() {
        val projectDir = tempDir.resolve("nitrogenfuel-text-block-namespace")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText(listOf(
            "package com.example;",
            "",
            "public class ExampleMod {",
            "    public String docs() {",
            "        return \"\"\"",
            "            public static final String MODID = \"example\";",
            "            \"\"\";",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))
        srcDir.resolve("ExampleFuelCategory.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleFuelCategory extends AbstractFuelCategory {
                public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "textures/gui/menu/altar.png");
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(source, "png", textureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any {
                it.contains("Cannot resolve Nitrogen fuel texture namespace expression 'ExampleMod.MODID'")
            },
            result.errors.joinToString("\n")
        )
        assertFalse(result.changes.any { it.ruleId == "res-nitrogen-fuel-icon-sprite" })
        assertFalse(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").exists())
    }

    @Test
    fun `legacy Nitrogen fuel sprite generation rejects placeholder sprite stems`() {
        val projectDir = tempDir.resolve("nitrogenfuel-placeholder-stem")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ExampleFuelCategory.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleFuelCategory extends AbstractFuelCategory {
                public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "textures/gui/menu/---.png");
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(source, "png", textureDir.resolve("---.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any {
                it.contains("Cannot derive Nitrogen fuel sprite name from legacy texture path textures/gui/menu/---.png")
            },
            result.errors.joinToString("\n")
        )
        assertFalse(result.changes.any { it.ruleId == "res-nitrogen-fuel-icon-sprite" })
        assertFalse(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_fuel_icon.png").exists())
    }

    @Test
    fun `legacy Nitrogen fuel sprite generation ignores comments and strings without API type use`() {
        val projectDir = tempDir.resolve("nitrogenfuel-comment-markers")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleNotes.java").writeText("""
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public class ExampleNotes {
                String note = "import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory; class Fake extends AbstractFuelCategory {}";
                // import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;
                // public class FakeFuelCategory extends AbstractFuelCategory {
                //   public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("example", "textures/gui/menu/altar.png");
                // }
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(source, "png", textureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId.startsWith("res-nitrogen-fuel-") })
        assertFalse(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").exists())
    }

    @Test
    fun `legacy Nitrogen fuel sprite generation ignores commented texture fields in real API classes`() {
        val projectDir = tempDir.resolve("nitrogenfuel-comment-texture")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleFuelCategory.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleFuelCategory extends AbstractFuelCategory {
                // public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("example", "textures/gui/menu/altar.png");
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(source, "png", textureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId.startsWith("res-nitrogen-fuel-") })
        assertFalse(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").exists())
    }

    @Test
    fun `legacy Nitrogen fuel sprite generation ignores text block texture fields in real API classes`() {
        val projectDir = tempDir.resolve("nitrogenfuel-text-block-texture")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleFuelCategory.java").writeText(listOf(
            "package com.example;",
            "",
            "import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;",
            "import net.minecraft.resources.ResourceLocation;",
            "",
            "public class ExampleFuelCategory extends AbstractFuelCategory {",
            "    public String docs() {",
            "        return \"\"\"",
            "            public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(\"example\", \"textures/gui/menu/altar.png\");",
            "            \"\"\";",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(source, "png", textureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId.startsWith("res-nitrogen-fuel-") })
        assertFalse(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").exists())
    }

    @Test
    fun `dry run does not rename folders`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).analyze(projectDir)

        // Old folders should still exist
        val dataDir = projectDir.resolve("src/main/resources/data/resmod")
        assertTrue(dataDir.resolve("tags/items").exists(), "Dry run should not rename folders")
        assertTrue(dataDir.resolve("recipes").exists(), "Dry run should not rename folders")

        // But changes should be reported
        assertTrue(result.changeCount > 0, "Dry run should report changes")
    }

    @Test
    fun `handles project without resources dir`() {
        val projectDir = tempDir.resolve("nores")
        projectDir.createDirectories()
        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        assertEquals(0, result.changeCount, "No resources = no changes")
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `handles generated resources dir`() {
        val projectDir = tempDir.resolve("genmod")
        val genDir = projectDir.resolve("src/generated/resources/data/genmod")
        genDir.resolve("tags/items").createDirectories()
        genDir.resolve("tags/items/gen_tag.json").writeText("""{"values":[]}""")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        assertTrue(genDir.resolve("tags/item").exists(), "Should also rename in generated resources")
    }
}
