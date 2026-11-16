package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.RecipeSerializerFactoryHints
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.collectSimpleCraftingSerializerAccessors
import com.modporter.core.transforms.structural.migrateLegacyRecipeSerializationSurfaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyRecipeSerializationSurfacesTest {
    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()

    private val ingredientJsonSource = """
        package example;

        import com.google.gson.JsonArray;
        import com.google.gson.JsonObject;
        import net.minecraft.world.item.crafting.Ingredient;

        final class ExampleSerializer<T extends ProcessingRecipe<?>> {
            void writeToJson(JsonObject json, T recipe) {
                JsonArray ingredients = new JsonArray();
                recipe.ingredients.forEach(i -> ingredients.add(i.toJson()));
                unrelated().toJson();
            }
            JsonObject single(Ingredient ingredient) {
                return ingredient.toJson().getAsJsonObject();
            }
            Other unrelated() { return null; }
        }
        class Other { Object toJson() { return null; } }
    """.trimIndent()

    @Test
    fun `ingredient JSON writes use the allow-empty ingredient codec`() {
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts(
            fields = mapOf(
                "example.ProcessingRecipe" to mapOf(
                    "ingredients" to "net.minecraft.core.NonNullList<net.minecraft.world.item.crafting.Ingredient>"
                )
            ),
            types = setOf("example.ProcessingRecipe")
        )

        val migrated = pass.migrateLegacyRecipeSerializationSurfaces(ingredientJsonSource)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "recipe.ingredients.forEach(i -> ingredients.add(" +
                    "Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, i).getOrThrow()))"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "return Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, ingredient).getOrThrow().getAsJsonObject();"
            ),
            migrated
        )
        assertTrue(normalized.contains("unrelated().toJson();"), migrated)
        assertTrue(migrated.contains("import com.mojang.serialization.JsonOps;"), migrated)
        assertEquals(migrated, pass.migrateLegacyRecipeSerializationSurfaces(migrated))
    }

    @Test
    fun `ingredient JSON writes need a proven ingredient element`() {
        val migrated = StructuralRefactorPass().migrateLegacyRecipeSerializationSurfaces(ingredientJsonSource)

        assertTrue(compact(migrated).contains("forEach(i -> ingredients.add(i.toJson()))"), migrated)
        assertTrue(compact(migrated).contains("Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, ingredient)"), migrated)
    }

    @Test
    fun `Forge ingredient merges become the same value merge`() {
        val source = """
            package example;

            import com.google.common.collect.ImmutableList;
            import net.minecraft.world.item.crafting.Ingredient;

            final class Merges {
                static Ingredient combined(Ingredient first, Ingredient second, int part) {
                    return Ingredient.merge(ImmutableList.of(first, second));
                }
                static Object other(Merger merger) {
                    return merger.merge(ImmutableList.of());
                }
            }
            class Merger { Object merge(Object values) { return values; } }
        """.trimIndent()
        val pass = StructuralRefactorPass()

        val migrated = pass.migrateLegacyRecipeSerializationSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "return Ingredient.fromValues(ImmutableList.of(first, second).stream()" +
                    ".filter(part1 -> !part1.isCustom()).flatMap(part1 -> Arrays.stream(part1.getValues())));"
            ),
            migrated
        )
        assertTrue(normalized.contains("return merger.merge(ImmutableList.of());"), migrated)
        assertTrue(migrated.contains("import java.util.Arrays;"), migrated)
        assertEquals(migrated, pass.migrateLegacyRecipeSerializationSurfaces(migrated))
    }

    private val nestedSource = """
        package example;

        import com.google.gson.JsonObject;
        import com.google.gson.JsonParseException;
        import net.minecraft.core.registries.BuiltInRegistries;
        import net.minecraft.network.FriendlyByteBuf;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.world.item.crafting.Recipe;
        import net.minecraft.world.item.crafting.RecipeManager;
        import net.minecraft.world.item.crafting.RecipeSerializer;
        import net.neoforged.neoforge.common.conditions.ICondition.IContext;

        final class NestedRecipes {
            static Recipe<?> fromJson(JsonObject json, ResourceLocation parentId, int index) {
                Recipe<?> recipe = RecipeManager.fromJson(ResourceLocation.fromNamespaceAndPath(parentId.getNamespace(), parentId.getPath() + "_step_" + index), json, IContext.EMPTY);
                if (recipe instanceof NestedRecipe nested)
                    return nested;
                throw new JsonParseException("Not a supported recipe type");
            }

            static Recipe<?> readFromBuffer(FriendlyByteBuf buffer) {
                ResourceLocation serializerId = buffer.readResourceLocation();
                ResourceLocation recipeId = buffer.readResourceLocation();
                RecipeSerializer<?> serializer = BuiltInRegistries.RECIPE_SERIALIZER.get(serializerId);
                if (!(serializer instanceof NestedSerializer))
                    throw new JsonParseException("Not a supported recipe type");
                @SuppressWarnings("rawtypes")
                NestedRecipe recipe = (NestedRecipe) serializer.fromNetwork(recipeId, buffer);
                return recipe;
            }
        }
    """.trimIndent()

    @Test
    fun `nested recipe reads keep explicit ids through the guarded legacy serializer`() {
        val pass = StructuralRefactorPass()

        val migrated = pass.migrateLegacyRecipeSerializationSurfaces(nestedSource)
        val normalized = compact(migrated)

        assertTrue(normalized.contains("static Recipe<?> readFromBuffer(RegistryFriendlyByteBuf buffer)"), migrated)
        assertTrue(
            normalized.contains(
                "NestedRecipe recipe = (NestedRecipe) ((NestedSerializer) serializer).fromNetwork(recipeId, buffer);"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "ResourceLocation recipeId1 = ResourceLocation.fromNamespaceAndPath(parentId.getNamespace(), " +
                    "parentId.getPath() + \"_step_\" + index);"
            ),
            migrated
        )
        assertTrue(normalized.contains("String recipeType = GsonHelper.getAsString(json, \"type\");"), migrated)
        assertTrue(
            normalized.contains(
                "RecipeSerializer<?> recipeSerializer = BuiltInRegistries.RECIPE_SERIALIZER" +
                    ".getOptional(ResourceLocation.parse(recipeType)).orElseThrow(() -> new JsonSyntaxException(" +
                    "\"Invalid or unsupported recipe type '\" + recipeType + \"'\"));"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "Recipe<?> recipe = recipeSerializer instanceof NestedSerializer ? " +
                    "((NestedSerializer) recipeSerializer).fromJson(recipeId1, json) : " +
                    "Recipe.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(JsonParseException::new);"
            ),
            migrated
        )
        assertFalse(migrated.contains("RecipeManager"), migrated)
        assertFalse(migrated.contains("IContext"), migrated)
        assertFalse(migrated.contains("import net.minecraft.network.FriendlyByteBuf;"), migrated)
        listOf(
            "import net.minecraft.network.RegistryFriendlyByteBuf;",
            "import net.minecraft.util.GsonHelper;",
            "import com.google.gson.JsonSyntaxException;",
            "import com.mojang.serialization.JsonOps;"
        ).forEach { assertTrue(migrated.contains(it), migrated) }
        assertEquals(migrated, pass.migrateLegacyRecipeSerializationSurfaces(migrated))
    }

    @Test
    fun `recipe reads without a guarded project serializer stay unchanged`() {
        val source = """
            package example;

            import com.google.gson.JsonObject;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeManager;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            final class Unproven {
                static Recipe<?> fromJson(JsonObject json, ResourceLocation id) {
                    Recipe<?> recipe = RecipeManager.fromJson(id, json);
                    return recipe;
                }

                static Object read(RecipeSerializer<?> serializer, ResourceLocation id, FriendlyByteBuf buffer) {
                    return serializer.fromNetwork(id, buffer);
                }
            }
        """.trimIndent()

        assertEquals(source, StructuralRefactorPass().migrateLegacyRecipeSerializationSurfaces(source))
    }

    private fun writeRegistrations(root: Path) {
        val example = root.resolve("example").createDirectories()
        root.resolve("example/recipe").createDirectories()
        example.resolve("Kinds.java").writeText(
            """
            package example;

            import example.recipe.DyeingRecipe;
            import java.util.function.Supplier;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public enum Kinds {
                DYEING(() -> new SimpleCraftingRecipeSerializer<>(DyeingRecipe::new), false),
                COPYING(() -> new SimpleCraftingRecipeSerializer<>(CopyingRecipe::new), false),
                PLAIN(() -> null, true);

                private final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<?>> serializerObject;

                Kinds(Supplier<RecipeSerializer<?>> serializerSupplier, boolean flag) {
                    serializerObject = Registers.SERIALIZERS.register(name().toLowerCase(), serializerSupplier);
                }

                @SuppressWarnings("unchecked")
                public <T extends RecipeSerializer<?>> T getSerializer() {
                    return (T) serializerObject.get();
                }

                private static class Registers {
                    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
                        DeferredRegister.create(Registries.RECIPE_SERIALIZER, "example");
                }
            }
            """.trimIndent()
        )
        example.resolve("CopyingRecipe.java").writeText("package example;\npublic class CopyingRecipe {}\n")
        example.resolve("Holders.java").writeText(
            """
            package example;

            import example.recipe.DyeingRecipe;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class Holders {
                static final DeferredRegister<RecipeSerializer<?>> REGISTER =
                    DeferredRegister.create(Registries.RECIPE_SERIALIZER, "example");
                public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<DyeingRecipe>> DYE =
                    REGISTER.register("dye", () -> new SimpleCraftingRecipeSerializer<>(DyeingRecipe::new));
            }
            """.trimIndent()
        )
    }

    @Test
    fun `registered simple serializer accessors resolve to their recipe factories`(@TempDir root: Path) {
        writeRegistrations(root)
        val files = listOf("example/Kinds.java", "example/CopyingRecipe.java", "example/Holders.java").map(root::resolve)

        val accessors = StructuralRefactorPass().collectSimpleCraftingSerializerAccessors(files)

        assertEquals(
            mapOf(
                "example.Kinds.DYEING::getSerializer" to "example.recipe.DyeingRecipe::new",
                "example.Kinds.COPYING::getSerializer" to "example.CopyingRecipe::new",
                "example.Holders.DYE::get" to "example.recipe.DyeingRecipe::new"
            ),
            accessors
        )
    }

    @Test
    fun `special recipe helpers take the registered recipe factories`(@TempDir root: Path) {
        writeRegistrations(root)
        val pass = StructuralRefactorPass()
        val accessors = pass.collectSimpleCraftingSerializerAccessors(
            listOf("example/Kinds.java", "example/CopyingRecipe.java", "example/Holders.java").map(root::resolve)
        )
        val hints = RecipeSerializerFactoryHints(emptyMap(), emptyMap(), serializerAccessorFactories = accessors)
        val source = """
            package example.data;

            import example.Holders;
            import example.Kinds;
            import java.util.function.Supplier;
            import net.minecraft.data.recipes.SpecialRecipeBuilder;
            import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

            final class ExampleRecipeGen {
                Object DYEING = createSpecial(Kinds.DYEING::getSerializer, "crafting", "dyeing");
                Object COPYING = createSpecial(Kinds.COPYING::getSerializer, "crafting", "copying");
                Object DYE = createSpecial(Holders.DYE::get, "crafting", "dye");

                Object createSpecial(
                    Supplier<? extends SimpleCraftingRecipeSerializer<?>> serializer,
                    String recipeType,
                    String path
                ) {
                    SpecialRecipeBuilder builder = SpecialRecipeBuilder.special(serializer.get());
                    return builder;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyRecipeSerializationSurfaces(source, hints)
        val normalized = compact(migrated)

        assertTrue(normalized.contains("createSpecial(DyeingRecipe::new, \"crafting\", \"dyeing\")"), migrated)
        assertTrue(normalized.contains("createSpecial(CopyingRecipe::new, \"crafting\", \"copying\")"), migrated)
        assertTrue(normalized.contains("createSpecial(DyeingRecipe::new, \"crafting\", \"dye\")"), migrated)
        assertTrue(normalized.contains("Function<CraftingBookCategory, Recipe<?>> serializer"), migrated)
        assertTrue(normalized.contains("SpecialRecipeBuilder.special(serializer);"), migrated)
        listOf(
            "import example.recipe.DyeingRecipe;",
            "import example.CopyingRecipe;",
            "import java.util.function.Function;",
            "import net.minecraft.world.item.crafting.CraftingBookCategory;",
            "import net.minecraft.world.item.crafting.Recipe;"
        ).forEach { assertTrue(migrated.contains(it), migrated) }
        listOf("Kinds", "Holders", "Supplier", "SimpleCraftingRecipeSerializer", "serializer.get()")
            .forEach { assertFalse(migrated.contains(it), migrated) }
        assertEquals(migrated, pass.migrateLegacyRecipeSerializationSurfaces(migrated, hints))
    }

    @Test
    fun `special recipe helpers with an unresolved factory stay unchanged`(@TempDir root: Path) {
        writeRegistrations(root)
        val pass = StructuralRefactorPass()
        val accessors = pass.collectSimpleCraftingSerializerAccessors(
            listOf("example/Kinds.java", "example/CopyingRecipe.java").map(root::resolve)
        )
        val hints = RecipeSerializerFactoryHints(emptyMap(), emptyMap(), serializerAccessorFactories = accessors)
        val source = """
            package example.data;

            import example.Kinds;
            import java.util.function.Supplier;
            import net.minecraft.data.recipes.SpecialRecipeBuilder;
            import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

            final class Partial {
                Object first = createSpecial(Kinds.DYEING::getSerializer, "a");
                Object plain = createSpecial(Kinds.PLAIN::getSerializer, "b");

                Object createSpecial(Supplier<? extends SimpleCraftingRecipeSerializer<?>> serializer, String path) {
                    return SpecialRecipeBuilder.special(serializer.get());
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyRecipeSerializationSurfaces(source, hints))
    }
}
