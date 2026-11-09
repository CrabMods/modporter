package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCreateLegacyRecipeSerializationBoundaries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CreateLegacyRecipeSerializationBoundariesTest {
    private val pass = StructuralRefactorPass()

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()

    @Test
    fun `processing ingredient JSON uses the nonempty target codec only at the serializer boundary`() {
        val source = """
            package com.simibubi.create.content.processing.recipe;

            import com.google.gson.JsonArray;
            import com.google.gson.JsonObject;
            import com.mojang.serialization.JsonOps;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            final class ProcessingRecipeSerializer<T extends ProcessingRecipe<?>>
                implements RecipeSerializer<T> {
                void writeToJson(JsonObject json, T recipe) {
                    JsonArray jsonIngredients = new JsonArray();
                    recipe.ingredients.forEach(i -> jsonIngredients.add(i.toJson()));
                    unrelated().toJson();
                }
                Other unrelated() { return null; }
            }
            class ProcessingRecipe<T> {}
            class Other { Object toJson() { return null; } }
        """.trimIndent()

        val migrated = pass.migrateCreateLegacyRecipeSerializationBoundaries(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "recipe.ingredients.forEach(i -> jsonIngredients.add(" +
                    "Ingredient.CODEC_NONEMPTY.encodeStart(" +
                    "JsonOps.INSTANCE, i).getOrThrow()))"
            ),
            migrated
        )
        assertTrue(normalized.contains("unrelated().toJson()"), migrated)
        assertEquals(
            migrated,
            pass.migrateCreateLegacyRecipeSerializationBoundaries(migrated)
        )
    }

    @Test
    fun `sequenced recipes retain explicit IDs and use typed processing serializers`() {
        val source = """
            package com.simibubi.create.content.processing.sequenced;

            import com.google.common.collect.ImmutableList;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonParseException;
            import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
            import com.simibubi.create.content.processing.recipe.ProcessingRecipeSerializer;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeManager;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.common.conditions.ICondition.IContext;

            class SequencedRecipe<T extends ProcessingRecipe<?>> {
                static SequencedRecipe<?> fromJson(
                    JsonObject json, SequencedAssemblyRecipe parent, int index
                ) {
                    ResourceLocation parentId = parent.getId();
                    Recipe<?> recipe = RecipeManager.fromJson(
                        ResourceLocation.fromNamespaceAndPath(
                            parentId.getNamespace(), parentId.getPath() + "_step_" + index
                        ),
                        json,
                        IContext.EMPTY
                    );
                    if (recipe instanceof ProcessingRecipe<?> processingRecipe) {
                        Ingredient transit = Ingredient.of(parent.getTransitionalItem());
                        processingRecipe.getIngredients().set(
                            0,
                            index == 0
                                ? Ingredient.merge(
                                    ImmutableList.of(transit, parent.getIngredient())
                                )
                                : transit
                        );
                        return new SequencedRecipe<>(processingRecipe);
                    }
                    throw new JsonParseException("Not a supported recipe type");
                }

                static SequencedRecipe<?> readFromBuffer(FriendlyByteBuf buffer) {
                    ResourceLocation serializerId = buffer.readResourceLocation();
                    ResourceLocation recipeId = buffer.readResourceLocation();
                    RecipeSerializer<?> serializer =
                        BuiltInRegistries.RECIPE_SERIALIZER.get(serializerId);
                    if (!(serializer instanceof ProcessingRecipeSerializer))
                        throw new JsonParseException("Not a supported recipe type");
                    @SuppressWarnings("rawtypes")
                    ProcessingRecipe recipe =
                        (ProcessingRecipe) serializer.fromNetwork(recipeId, buffer);
                    return new SequencedRecipe<>(recipe);
                }

                void writeToBuffer(RegistryFriendlyByteBuf buffer) {
                    ProcessingRecipeSerializer<T> serializer = serializer();
                    buffer.writeResourceLocation(serializerId(serializer));
                    buffer.writeResourceLocation(recipeId());
                    serializer.toNetwork(buffer, wrapped());
                }

                ProcessingRecipeSerializer<T> serializer() { return null; }
                ResourceLocation serializerId(ProcessingRecipeSerializer<T> serializer) {
                    return null;
                }
                ResourceLocation recipeId() { return null; }
                T wrapped() { return null; }
            }
            class SequencedAssemblyRecipe {
                ResourceLocation getId() { return null; }
                Object getTransitionalItem() { return null; }
                Ingredient getIngredient() { return null; }
            }
        """.trimIndent()

        val migrated = pass.migrateCreateLegacyRecipeSerializationBoundaries(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "ResourceLocation stepId = ResourceLocation.fromNamespaceAndPath(" +
                    "parentId.getNamespace(), parentId.getPath() + \"_step_\" + index);"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "ResourceLocation serializerId = ResourceLocation.parse(" +
                    "GsonHelper.getAsString(json, \"type\"));"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "serializer instanceof ProcessingRecipeSerializer<?>"
            ),
            migrated
        )
        assertTrue(
            Regex(
                Regex.escape(
                    "ProcessingRecipeSerializer<?> processingSerializer = " +
                        "(ProcessingRecipeSerializer<?>) serializer;"
                )
            ).findAll(normalized).count() == 2,
            migrated
        )
        assertTrue(
            normalized.contains(
                "Recipe<?> recipe = processingSerializer.fromJson(stepId, json);"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "CompoundIngredient.of(transit, parent.getIngredient())"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "readFromBuffer(RegistryFriendlyByteBuf buffer)"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "ProcessingRecipe<?> recipe = " +
                    "processingSerializer.fromNetwork(recipeId, buffer);"
            ),
            migrated
        )
        assertFalse(migrated.contains("RecipeManager.fromJson"), migrated)
        assertFalse(migrated.contains("Ingredient.merge"), migrated)
        assertFalse(migrated.contains("streamCodec().decode"), migrated)
        assertTrue(
            normalized.contains(
                "buffer.writeResourceLocation(serializerId(serializer)); " +
                    "buffer.writeResourceLocation(recipeId()); " +
                    "serializer.toNetwork(buffer, wrapped());"
            ),
            migrated
        )
        assertEquals(
            migrated,
            pass.migrateCreateLegacyRecipeSerializationBoundaries(migrated)
        )
    }

    @Test
    fun `runtime generated recipes use the conditional codec and singular target path`() {
        val source = """
            package com.simibubi.create.foundation.data;

            import com.google.gson.JsonObject;
            import com.mojang.serialization.JsonOps;
            import java.util.Collections;

            final class RuntimeDataGenerator {
                static final Files JSON_FILES = null;

                static class Builder<T extends ProcessingRecipe<?>>
                    extends ProcessingRecipeBuilder<T> {
                    public T build() {
                        T t = super.build();
                        DataGenResult<T> result =
                            new DataGenResult<>(t, Collections.emptyList());
                        JSON_FILES.put(
                            result.getId().withPrefix("recipes/"),
                            result.serializeRecipe()
                        );
                        return t;
                    }
                }
            }
        """.trimIndent()

        val migrated = pass.migrateCreateLegacyRecipeSerializationBoundaries(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "JsonObject serialized = Recipe.CONDITIONAL_CODEC.encodeStart(" +
                    "JsonOps.INSTANCE, Optional.of(new WithConditions<>(t)))" +
                    ".getOrThrow().getAsJsonObject();"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "JSON_FILES.put(result.getId().withPrefix(\"recipe/\"), serialized);"
            ),
            migrated
        )
        assertFalse(migrated.contains("serializeRecipe()"), migrated)
        assertTrue(migrated.contains("import java.util.Optional;"), migrated)
        assertTrue(
            migrated.contains("import net.minecraft.world.item.crafting.Recipe;"),
            migrated
        )
        assertTrue(
            migrated.contains(
                "import net.neoforged.neoforge.common.conditions.WithConditions;"
            ),
            migrated
        )
        assertEquals(
            migrated,
            pass.migrateCreateLegacyRecipeSerializationBoundaries(migrated)
        )
    }

    @Test
    fun `Create special recipes use the exact target factory contract`() {
        val source = """
            package com.simibubi.create.foundation.data.recipe;

            import com.simibubi.create.AllRecipeTypes;
            import java.util.function.Supplier;
            import net.minecraft.data.recipes.SpecialRecipeBuilder;
            import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

            final class CreateStandardRecipeGen {
                Object TOOLBOX_DYEING = createSpecial(
                    AllRecipeTypes.TOOLBOX_DYEING::getSerializer,
                    "crafting",
                    "toolbox_dyeing"
                );
                Object ITEM_COPYING = createSpecial(
                    AllRecipeTypes.ITEM_COPYING::getSerializer,
                    "crafting",
                    "item_copying"
                );

                Object createSpecial(
                    Supplier<? extends SimpleCraftingRecipeSerializer<?>> serializer,
                    String recipeType,
                    String path
                ) {
                    SpecialRecipeBuilder builder =
                        SpecialRecipeBuilder.special(serializer.get());
                    return builder;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateCreateLegacyRecipeSerializationBoundaries(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "createSpecial(ToolboxDyeingRecipe::new, " +
                    "\"crafting\", \"toolbox_dyeing\")"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "createSpecial(ItemCopyingRecipe::new, " +
                    "\"crafting\", \"item_copying\")"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "Function<CraftingBookCategory, Recipe<?>> serializer"
            ),
            migrated
        )
        assertTrue(
            normalized.contains("SpecialRecipeBuilder.special(serializer)"),
            migrated
        )
        assertTrue(
            normalized.contains(
                "SpecialRecipeBuilder builder = " +
                    "SpecialRecipeBuilder.special(serializer)"
            ),
            migrated
        )
        assertFalse(migrated.contains("serializer.get()"), migrated)
        assertFalse(migrated.contains("AllRecipeTypes"), migrated)
        assertFalse(migrated.contains("SimpleCraftingRecipeSerializer"), migrated)
        assertEquals(
            migrated,
            pass.migrateCreateLegacyRecipeSerializationBoundaries(migrated)
        )
    }

    @Test
    fun `special recipe helper stays unchanged when any factory is unresolved`() {
        val source = """
            import com.simibubi.create.AllRecipeTypes;
            import java.util.function.Supplier;
            import net.minecraft.data.recipes.SpecialRecipeBuilder;
            import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

            final class Partial {
                Object first = createSpecial(
                    AllRecipeTypes.TOOLBOX_DYEING::getSerializer, "crafting", "a"
                );
                Object second = createSpecial(
                    AllRecipeTypes.ITEM_COPYING::getSerializer, "crafting", "b"
                );
                Object third = createSpecial(other(), "crafting", "c");

                Object createSpecial(
                    Supplier<? extends SimpleCraftingRecipeSerializer<?>> serializer,
                    String recipeType,
                    String path
                ) {
                    return SpecialRecipeBuilder.special(serializer.get());
                }
            }
        """.trimIndent()

        assertEquals(
            source,
            pass.migrateCreateLegacyRecipeSerializationBoundaries(source)
        )
    }
}
