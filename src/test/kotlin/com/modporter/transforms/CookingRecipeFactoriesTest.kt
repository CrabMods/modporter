package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class CookingRecipeFactoriesTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `a cooking helper returning its builder gains the factory while an unrelated serializer call does not`() {
        val file = tempDir.resolve("src/main/java/com/example/Cooking.java")
        file.parent.createDirectories()
        file.writeText("""
            package com.example;

            import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
            import net.minecraft.data.recipes.RecipeCategory;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.AbstractCookingRecipe;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class Cooking {
                Object recipes() {
                    observe(RecipeSerializer.SMELTING_RECIPE, "furnace");
                    return build(RecipeSerializer.SMELTING_RECIPE, 200);
                }

                private SimpleCookingRecipeBuilder build(RecipeSerializer<? extends AbstractCookingRecipe> serializer, int ticks) {
                    return SimpleCookingRecipeBuilder.generic(Ingredient.of(Items.BEEF), RecipeCategory.FOOD, Items.COOKED_BEEF, .3f, ticks, serializer);
                }

                public void observe(Object serializer, String label) {
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val result = file.readText()
        assertTrue(result.contains("private <T extends AbstractCookingRecipe> SimpleCookingRecipeBuilder build(RecipeSerializer<T> serializer, AbstractCookingRecipe.Factory<T> serializerFactory, int ticks)"), result)
        assertTrue(result.contains("build(RecipeSerializer.SMELTING_RECIPE, SmeltingRecipe::new, 200)"), result)
        assertTrue(result.contains(".3f, ticks, serializer, serializerFactory)"), result)
        assertTrue(result.contains("observe(RecipeSerializer.SMELTING_RECIPE, \"furnace\")"), result)
    }
}
