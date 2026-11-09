package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class ContainerRecipeArgumentsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `a closed recipe input type is recognized inside its positive pattern scope`() {
        val file = directory.resolve("src/main/java/example/LocalRecipe.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;
            abstract class Parent<T extends RecipeInput> implements Recipe<T> {}
            abstract class LocalRecipe extends Parent<RecipeInput> {
                void use(Object recipe, CraftingContainer grid) {
                    if (recipe instanceof LocalRecipe local) {
                        local.getRemainingItems(grid);
                    }
                }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        assertTrue(file.readText().contains("local.getRemainingItems(grid.asCraftInput())"), file.readText())
    }

    @Test
    fun `optional crafting recipes receive crafting input without changing unrelated matches`() {
        val file = directory.resolve("src/main/java/example/Recipes.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import java.util.Optional;
            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.level.Level;
            class Recipes {
                boolean matches(Optional<CraftingRecipe> recipe, CraftingContainer grid, Level level, Other other) {
                    other.matches(grid, level);
                    return recipe.filter(value -> value.matches(grid, level)).isPresent();
                }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains("value.matches(grid.asCraftInput(), level)"), source)
        assertTrue(source.contains("other.matches(grid, level)"), source)
    }
}
