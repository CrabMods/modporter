package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyFinishedRecipeResults
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyFinishedRecipeSerializationTest {
    @TempDir
    lateinit var tempDir: Path

    private fun write(name: String, content: String): Path {
        val directory = tempDir.resolve("src/main/java/example")
        directory.createDirectories()
        return directory.resolve(name).also { it.writeText(content.trimIndent()) }
    }

    private fun compact(source: String): String = source.replace(Regex("""\s+"""), " ")

    private fun project(serializeRecipeData: String, caller: Boolean = true): Path {
        write(
            "ExampleRecipe.java",
            """
            package example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public abstract class ExampleRecipe implements Recipe<RecipeInput> {
                public abstract ResourceLocation getId();
                public abstract RecipeSerializer<?> getSerializer();
            }
            """
        )
        write(
            "ExampleSerializer.java",
            """
            package example;

            import com.google.gson.JsonObject;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class ExampleSerializer<T extends ExampleRecipe> implements RecipeSerializer<T> {
                @Override
                public com.mojang.serialization.MapCodec<T> codec() {
                    return LegacyRecipeCodecs.codec(this::fromJson, this::writeToJson);
                }

                protected void writeToJson(JsonObject json, T recipe) {
                    json.addProperty("time", 1);
                }

                public final void write(JsonObject json, T recipe) {
                    writeToJson(json, recipe);
                }

                public final T fromJson(ResourceLocation id, JsonObject json) {
                    return null;
                }
            }
            """
        )
        val results = write(
            "ExampleResults.java",
            """
            package example;

            import com.google.gson.JsonArray;
            import com.google.gson.JsonObject;
            import java.util.List;
            import net.minecraft.data.recipes.FinishedRecipe;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.common.conditions.ICondition;
            import net.minecraftforge.common.crafting.CraftingHelper;

            public class ExampleResults {
                public static class Result<S extends ExampleRecipe> implements FinishedRecipe {
                    private List<ICondition> conditions;
                    private ExampleSerializer<S> serializer;
                    private ResourceLocation id;
                    private S recipe;

                    @SuppressWarnings("unchecked")
                    public Result(S recipe, List<ICondition> conditions) {
                        this.recipe = recipe;
                        this.conditions = conditions;
                        this.id = recipe.getId();
                        this.serializer = (ExampleSerializer<S>) recipe.getSerializer();
                    }

                    @Override
                    public void serializeRecipeData(JsonObject json) {
            $serializeRecipeData
                    }

                    @Override
                    public ResourceLocation getId() {
                        return id;
                    }

                    @Override
                    public RecipeSerializer<?> getType() {
                        return serializer;
                    }

                    @Override
                    public JsonObject serializeAdvancement() {
                        return null;
                    }

                    @Override
                    public ResourceLocation getAdvancementId() {
                        return null;
                    }
                }
            }
            """
        )
        if (caller) {
            write(
                "RuntimeRecipes.java",
                """
                package example;

                import com.google.gson.JsonObject;
                import java.util.Collections;
                import java.util.HashMap;
                import java.util.Map;
                import net.minecraft.resources.ResourceLocation;

                public class RuntimeRecipes {
                    static final Map<ResourceLocation, JsonObject> FILES = new HashMap<>();

                    static <T extends ExampleRecipe> void add(T recipe) {
                        ExampleResults.Result<T> result = new ExampleResults.Result<>(recipe, Collections.emptyList());
                        FILES.put(result.getId(), result.serializeRecipe());
                    }
                }
                """
            )
        }
        return results
    }

    private val exactData = """
                        serializer.write(json, recipe);
                        if (conditions.isEmpty())
                            return;
                        JsonArray array = new JsonArray();
                        conditions.forEach(c -> array.add(CraftingHelper.serialize(c)));
                        json.add("conditions", array);
    """.trimEnd()

    @Test
    fun `a result read through serializeRecipe keeps the recipe codec serialization`() {
        val results = project(exactData)

        LegacyFinishedRecipeResults.migrate(tempDir, dryRun = false)
        val migrated = compact(results.readText())

        assertFalse(migrated.contains("implements FinishedRecipe"), migrated)
        assertFalse(migrated.contains("serializeRecipeData"), migrated)
        assertTrue(
            migrated.contains("output.accept(getId(), recipe, null, conditions.toArray(new net.neoforged.neoforge.common.conditions.ICondition[0]));"),
            migrated
        )
        assertTrue(
            migrated.contains(
                "public com.google.gson.JsonObject serializeRecipe() { " +
                    "return net.minecraft.world.item.crafting.Recipe.CONDITIONAL_CODEC.encodeStart( " +
                    "com.mojang.serialization.JsonOps.INSTANCE, " +
                    "java.util.Optional.of(new net.neoforged.neoforge.common.conditions.WithConditions<>(conditions, recipe)) " +
                    ").getOrThrow().getAsJsonObject(); }"
            ),
            migrated
        )
    }

    @Test
    fun `serializeRecipe is not kept for extra data, foreign writers, or results nobody reads`() {
        val extra = project("$exactData\n                        json.addProperty(\"extra\", 1);")
        LegacyFinishedRecipeResults.migrate(tempDir, dryRun = false)
        assertFalse(extra.readText().contains("serializeRecipe()"), extra.readText())
        assertTrue(extra.readText().contains("saveTo("), extra.readText())

        tempDir.resolve("src").toFile().deleteRecursively()
        val foreign = project(exactData.replace("serializer.write(json, recipe);", "serializer.writeOther(json, recipe);"))
        LegacyFinishedRecipeResults.migrate(tempDir, dryRun = false)
        assertFalse(foreign.readText().contains("serializeRecipe()"), foreign.readText())

        tempDir.resolve("src").toFile().deleteRecursively()
        val unread = project(exactData, caller = false)
        LegacyFinishedRecipeResults.migrate(tempDir, dryRun = false)
        assertFalse(unread.readText().contains("serializeRecipe()"), unread.readText())
        assertTrue(unread.readText().contains("saveTo("), unread.readText())
    }
}
