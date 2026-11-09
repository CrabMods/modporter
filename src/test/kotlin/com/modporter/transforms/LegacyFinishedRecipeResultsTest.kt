package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyFinishedRecipeResults
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyFinishedRecipeResultsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `delegating finished recipe record becomes conditional recipe output`() {
        val source = writeSource(
            "ConditionalRecipes.java",
            """
            package com.example;

            import com.google.gson.JsonArray;
            import com.google.gson.JsonObject;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.RecipeProvider;
            import net.minecraft.data.recipes.ShapelessRecipeBuilder;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.common.conditions.ICondition;

            public class ConditionalRecipes extends RecipeProvider {
                private final List<ICondition> conditions;

                public ConditionalRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries, List<ICondition> conditions) {
                    super(output, registries);
                    this.conditions = conditions;
                }

                @Override
                protected void buildRecipes(RecipeOutput output) {
                    ShapelessRecipeBuilder builder = null;
                    builder.save(result -> {
                        output.accept(!conditions.isEmpty()
                            ? new ConditionalResult(result, conditions)
                            : result);
                    }, ResourceLocation.parse("example:conditional"));
                }

                private record ConditionalResult(RecipeOutput wrapped, List<ICondition> conditions)
                    implements RecipeOutput {
                    public ResourceLocation getId() {
                        return wrapped.getId();
                    }

                    public Object getType() {
                        return wrapped.getType();
                    }

                    public JsonObject serializeAdvancement() {
                        return wrapped.serializeAdvancement();
                    }

                    public ResourceLocation getAdvancementId() {
                        return wrapped.getAdvancementId();
                    }

                    public void serializeRecipeData(JsonObject json) {
                        wrapped.serializeRecipeData(json);
                        JsonArray serialized = new JsonArray();
                        conditions.forEach(condition -> serialized.add(condition.toString()));
                        json.add("conditions", serialized);
                    }
                }
            }
            """.trimIndent()
        )

        LegacyFinishedRecipeResults.migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(
            migrated.contains(
                "builder.save(!conditions.isEmpty() ? output.withConditions(conditions.toArray(new net.neoforged.neoforge.common.conditions.ICondition[0])) : output, ResourceLocation.parse(\"example:conditional\"));"
            ),
            migrated
        )
        assertFalse(migrated.contains("record ConditionalResult"), migrated)
        assertFalse(migrated.contains("modporter\$resultOverrides"), migrated)
    }

    @Test
    fun `result overriding finished recipe record preserves encoded stack and advancement conditions`() {
        val source = writeSource(
            "ExternalCookingRecipes.java",
            """
            package com.example;

            import com.google.gson.JsonArray;
            import com.google.gson.JsonObject;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.FinishedRecipe;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.RecipeProvider;
            import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraftforge.common.crafting.CraftingHelper;
            import net.neoforged.neoforge.common.conditions.ICondition;

            public class ExternalCookingRecipes extends RecipeProvider {
                private final ResourceLocation externalResult;
                private final List<ICondition> conditions;

                public ExternalCookingRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries,
                                              ResourceLocation externalResult, List<ICondition> conditions) {
                    super(output, registries);
                    this.externalResult = externalResult;
                    this.conditions = conditions;
                }

                @Override
                protected void buildRecipes(RecipeOutput output) {
                    SimpleCookingRecipeBuilder builder = null;
                    boolean external = true;
                    builder.save(result -> {
                        output.accept(external
                            ? new ExternalResult(result, externalResult, conditions)
                            : result);
                    }, ResourceLocation.parse("example:external"));
                }

                private record ExternalResult(FinishedRecipe wrapped, ResourceLocation outputOverride,
                                              List<ICondition> conditions) implements FinishedRecipe {
                    public ResourceLocation getId() {
                        return wrapped.getId();
                    }

                    public Object getType() {
                        return wrapped.getType();
                    }

                    public JsonObject serializeAdvancement() {
                        return wrapped.serializeAdvancement();
                    }

                    public ResourceLocation getAdvancementId() {
                        return wrapped.getAdvancementId();
                    }

                    public void serializeRecipeData(JsonObject json) {
                        wrapped.serializeRecipeData(json);
                        json.addProperty("result", outputOverride.toString());
                        JsonArray serialized = new JsonArray();
                        conditions.forEach(condition -> serialized.add(CraftingHelper.serialize(condition)));
                        json.add("conditions", serialized);
                    }
                }
            }
            """.trimIndent()
        )

        LegacyFinishedRecipeResults.migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(
            migrated.contains(
                "builder.save(external ? modporter\$recipeOutput(output, externalResult, conditions) : output, ResourceLocation.parse(\"example:external\"));"
            ),
            migrated
        )
        assertFalse(migrated.contains("record ExternalResult"), migrated)
        assertTrue(migrated.contains("new java.util.IdentityHashMap<>()"), migrated)
        assertTrue(migrated.contains("throw new IllegalStateException(\"Duplicate recipe \" + id);"), migrated)
        assertTrue(migrated.contains("Recipe.CONDITIONAL_CODEC.encodeStart"), migrated)
        assertTrue(migrated.contains("Advancement.CONDITIONAL_CODEC.encodeStart"), migrated)
        assertTrue(migrated.contains("result.getAsJsonObject().addProperty(\"id\", resultOverride.toString());"), migrated)
        assertTrue(migrated.contains("modporter\$resultOverrides.clear();"), migrated)
        assertTrue(migrated.contains("modporter\$resultOverrides = null;"), migrated)
        assertTrue(migrated.contains("this.buildRecipes(recipeOutput, registries);"), migrated)
    }

    @Test
    fun `multiple decorator records and save lambdas migrate from one source file`() {
        val source = writeSource(
            "CombinedRecipes.java",
            """
            package com.example;

            import com.google.gson.JsonArray;
            import com.google.gson.JsonObject;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.RecipeProvider;
            import net.minecraft.data.recipes.ShapelessRecipeBuilder;
            import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.common.conditions.ICondition;

            public class CombinedRecipes extends RecipeProvider {
                private final ResourceLocation externalResult;
                private final List<ICondition> conditions;

                public CombinedRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries,
                                       ResourceLocation externalResult, List<ICondition> conditions) {
                    super(output, registries);
                    this.externalResult = externalResult;
                    this.conditions = conditions;
                }

                @Override
                protected void buildRecipes(RecipeOutput output) {
                    SimpleCookingRecipeBuilder cooking = null;
                    boolean external = true;
                    cooking.save(result -> {
                        output.accept(external
                            ? new ExternalResult(result, externalResult, conditions)
                            : result);
                    }, ResourceLocation.parse("example:external"));

                    ShapelessRecipeBuilder crafting = null;
                    crafting.save(result -> {
                        output.accept(!conditions.isEmpty()
                            ? new ConditionalResult(result, conditions)
                            : result);
                    }, ResourceLocation.parse("example:conditional"));
                }

                private record ExternalResult(RecipeOutput wrapped, ResourceLocation outputOverride,
                                              List<ICondition> conditions) implements RecipeOutput {
                    public ResourceLocation getId() {
                        return wrapped.getId();
                    }

                    public Object getType() {
                        return wrapped.getType();
                    }

                    public JsonObject serializeAdvancement() {
                        return wrapped.serializeAdvancement();
                    }

                    public ResourceLocation getAdvancementId() {
                        return wrapped.getAdvancementId();
                    }

                    public void serializeRecipeData(JsonObject json) {
                        wrapped.serializeRecipeData(json);
                        json.addProperty("result", outputOverride.toString());
                        JsonArray serialized = new JsonArray();
                        conditions.forEach(condition -> serialized.add(condition.toString()));
                        json.add("conditions", serialized);
                    }
                }

                private record ConditionalResult(RecipeOutput wrapped, List<ICondition> conditions)
                    implements RecipeOutput {
                    public ResourceLocation getId() {
                        return wrapped.getId();
                    }

                    public Object getType() {
                        return wrapped.getType();
                    }

                    public JsonObject serializeAdvancement() {
                        return wrapped.serializeAdvancement();
                    }

                    public ResourceLocation getAdvancementId() {
                        return wrapped.getAdvancementId();
                    }

                    public void serializeRecipeData(JsonObject json) {
                        wrapped.serializeRecipeData(json);
                        JsonArray serialized = new JsonArray();
                        conditions.forEach(condition -> serialized.add(condition.toString()));
                        json.add("conditions", serialized);
                    }
                }
            }
            """.trimIndent()
        )

        LegacyFinishedRecipeResults.migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(
            migrated.contains(
                "cooking.save(external ? modporter\$recipeOutput(output, externalResult, conditions) : output, ResourceLocation.parse(\"example:external\"));"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "crafting.save(!conditions.isEmpty() ? output.withConditions(conditions.toArray(new net.neoforged.neoforge.common.conditions.ICondition[0])) : output, ResourceLocation.parse(\"example:conditional\"));"
            ),
            migrated
        )
        assertFalse(migrated.contains("record ExternalResult"), migrated)
        assertFalse(migrated.contains("record ConditionalResult"), migrated)
        assertTrue(migrated.contains("modporter\$resultOverrides"), migrated)
    }

    @Test
    fun `actual target condition codec decorators retain complete modeled behavior`() {
        val source = writeSource(
            "ActualDecoratorShape.java",
            """
            package com.example;

            import com.google.gson.JsonArray;
            import com.google.gson.JsonObject;
            import java.util.List;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.RecipeProvider;
            import net.minecraft.data.recipes.ShapelessRecipeBuilder;
            import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.common.conditions.ICondition;
            import org.jetbrains.annotations.NotNull;

            class ActualDecoratorShape extends RecipeProvider {
                private final ResourceLocation externalResult =
                    ResourceLocation.parse("othermod:result");
                private final List<ICondition> conditions = List.of();

                @Override
                protected void buildRecipes(RecipeOutput output) {
                    ShapelessRecipeBuilder shapeless = null;
                    shapeless.save(result -> {
                        output.accept(!conditions.isEmpty()
                            ? new ConditionSupportingResult(result, conditions)
                            : result);
                    }, ResourceLocation.parse("example:shapeless"));

                    SimpleCookingRecipeBuilder cooking = null;
                    boolean external = true;
                    cooking.save(result -> {
                        output.accept(external
                            ? new ModdedCookingResult(result, externalResult, conditions)
                            : result);
                    }, ResourceLocation.parse("example:cooking"));
                }

                private record ModdedCookingResult(RecipeOutput wrapped,
                                                   ResourceLocation outputOverride,
                                                   List<ICondition> conditions)
                    implements RecipeOutput {
                    public ResourceLocation getId() {
                        return wrapped.getId();
                    }

                    public RecipeSerializer<?> getType() {
                        return wrapped.getType();
                    }

                    public JsonObject serializeAdvancement() {
                        return wrapped.serializeAdvancement();
                    }

                    public ResourceLocation getAdvancementId() {
                        return wrapped.getAdvancementId();
                    }

                    public void serializeRecipeData(JsonObject object) {
                        wrapped.serializeRecipeData(object);
                        object.addProperty("result", outputOverride.toString());
                        JsonArray conds = new JsonArray();
                        conditions.forEach(c -> conds.add(
                            net.neoforged.neoforge.common.conditions.ICondition.CODEC
                                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, c)
                                .getOrThrow()
                                .getAsJsonObject()));
                        object.add("conditions", conds);
                    }
                }

                private record ConditionSupportingResult(RecipeOutput wrapped,
                                                         List<ICondition> conditions)
                    implements RecipeOutput {
                    public ResourceLocation getId() {
                        return wrapped.getId();
                    }

                    public RecipeSerializer<?> getType() {
                        return wrapped.getType();
                    }

                    public JsonObject serializeAdvancement() {
                        return wrapped.serializeAdvancement();
                    }

                    public ResourceLocation getAdvancementId() {
                        return wrapped.getAdvancementId();
                    }

                    public void serializeRecipeData(@NotNull JsonObject json) {
                        wrapped.serializeRecipeData(json);
                        JsonArray conds = new JsonArray();
                        conditions.forEach(c -> conds.add(
                            net.neoforged.neoforge.common.conditions.ICondition.CODEC
                                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, c)
                                .getOrThrow()
                                .getAsJsonObject()));
                        json.add("conditions", conds);
                    }
                }
            }
            """.trimIndent()
        )

        LegacyFinishedRecipeResults.migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(
            migrated.contains(
                "shapeless.save(!conditions.isEmpty() ? output.withConditions(conditions.toArray(new net.neoforged.neoforge.common.conditions.ICondition[0])) : output, ResourceLocation.parse(\"example:shapeless\"));"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "cooking.save(external ? modporter\$recipeOutput(output, externalResult, conditions) : output, ResourceLocation.parse(\"example:cooking\"));"
            ),
            migrated
        )
        assertFalse(migrated.contains("record ModdedCookingResult"), migrated)
        assertFalse(migrated.contains("record ConditionSupportingResult"), migrated)
        assertTrue(migrated.contains("modporter\$resultOverrides"), migrated)
    }

    @Test
    fun `decorator admission rejects every unmodeled component member and serialization statement`() {
        data class UnsafeShape(
            val name: String,
            val components: String = "RecipeOutput wrapped, List<ICondition> conditions",
            val constructorArgs: String = "result, conditions",
            val extraMember: String = "",
            val extraSerialization: String = "",
        )

        val shapes = listOf(
            UnsafeShape(
                name = "ExtraComponent",
                components = "RecipeOutput wrapped, List<ICondition> conditions, String ignored",
                constructorArgs = "result, conditions, \"ignored\"",
            ),
            UnsafeShape(
                name = "CompactConstructor",
                extraMember = """
                    UnsafeResult {
                        if (conditions == null)
                            throw new IllegalArgumentException("conditions");
                    }
                """.trimIndent(),
            ),
            UnsafeShape(
                name = "ArbitraryMethod",
                extraMember = "private void audit() {}",
            ),
            UnsafeShape(
                name = "ExtraSerialization",
                extraSerialization = "json.addProperty(\"group\", \"custom\");",
            ),
        )

        val originals = shapes.associateWith { shape ->
            val className = "${shape.name}Recipes"
            writeSource(
                "$className.java",
                """
                package com.example;

                import com.google.gson.JsonArray;
                import com.google.gson.JsonObject;
                import java.util.List;
                import net.minecraft.data.recipes.RecipeOutput;
                import net.minecraft.data.recipes.RecipeProvider;
                import net.minecraft.data.recipes.ShapelessRecipeBuilder;
                import net.minecraft.resources.ResourceLocation;
                import net.neoforged.neoforge.common.conditions.ICondition;

                class $className extends RecipeProvider {
                    private final List<ICondition> conditions = List.of();

                    @Override
                    protected void buildRecipes(RecipeOutput output) {
                        ShapelessRecipeBuilder builder = null;
                        builder.save(result -> {
                            output.accept(new UnsafeResult(${shape.constructorArgs}));
                        }, ResourceLocation.parse("example:unsafe"));
                    }

                    private record UnsafeResult(${shape.components}) implements RecipeOutput {
                        public ResourceLocation getId() {
                            return wrapped.getId();
                        }

                        public Object getType() {
                            return wrapped.getType();
                        }

                        public JsonObject serializeAdvancement() {
                            return wrapped.serializeAdvancement();
                        }

                        public ResourceLocation getAdvancementId() {
                            return wrapped.getAdvancementId();
                        }

                        public void serializeRecipeData(JsonObject json) {
                            wrapped.serializeRecipeData(json);
                            ${shape.extraSerialization}
                            JsonArray serialized = new JsonArray();
                            conditions.forEach(condition -> serialized.add(condition.toString()));
                            json.add("conditions", serialized);
                        }

                        ${shape.extraMember}
                    }
                }
                """.trimIndent()
            ).let { path -> path to path.readText() }
        }

        val changes = LegacyFinishedRecipeResults.migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        for ((shape, original) in originals) {
            val (path, text) = original
            assertEquals(text, path.readText(), shape.name)
            assertTrue(path.readText().contains("record UnsafeResult"), shape.name)
        }
    }

    private fun writeSource(name: String, content: String): Path {
        val directory = tempDir.resolve("src/main/java/com/example")
        directory.createDirectories()
        return directory.resolve(name).also { it.writeText(content) }
    }
}
