package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A private cooking helper gains the 1.21 recipe factory only when every call site can supply one. */
class CookingHelperFactoryWideningTest {
    private val hints = RecipeSerializerFactoryHints(emptyMap(), emptyMap())

    @Test
    fun `constant call sites widen the helper and pass the matching factory`() {
        val source = """
            package example;

            import net.minecraft.data.recipes.RecipeCategory;
            import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
            import net.minecraft.world.item.crafting.AbstractCookingRecipe;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class CookingGen {
                void inFurnace(RecipeCategory category) {
                    create(RecipeSerializer.SMELTING_RECIPE, category);
                    create(RecipeSerializer.SMOKING_RECIPE, category);
                }

                private void create(RecipeSerializer<? extends AbstractCookingRecipe> serializer, RecipeCategory category) {
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateLegacyRecipeBuilderSource(source, hints)

        assertTrue(
            migrated.contains(
                "private <T extends AbstractCookingRecipe> void create(RecipeSerializer<T> serializer, " +
                    "AbstractCookingRecipe.Factory<T> serializerFactory, RecipeCategory category)"
            ),
            migrated
        )
        assertTrue(migrated.contains("create(RecipeSerializer.SMELTING_RECIPE, SmeltingRecipe::new, category);"), migrated)
        assertTrue(migrated.contains("create(RecipeSerializer.SMOKING_RECIPE, SmokingRecipe::new, category);"), migrated)
    }

    @Test
    fun `a wildcard-typed serializer at any call site keeps the helper and its callers unchanged`() {
        val source = """
            package example;

            import net.minecraft.data.recipes.RecipeCategory;
            import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
            import net.minecraft.world.item.crafting.AbstractCookingRecipe;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class CookingBuilder {
                public static CookingBuilder generic(RecipeCategory category, RecipeSerializer<? extends AbstractCookingRecipe> serializer) {
                    return new CookingBuilder(determineBookCategory(serializer, category));
                }

                private static String determineBookCategory(RecipeSerializer<? extends AbstractCookingRecipe> serializer, RecipeCategory category) {
                    return serializer == RecipeSerializer.SMELTING_RECIPE ? "furnace" : "other";
                }

                private CookingBuilder(String book) {
                }
            }
        """.trimIndent()

        assertEquals(source, StructuralRefactorPass().migrateLegacyRecipeBuilderSource(source, hints))
    }
}
