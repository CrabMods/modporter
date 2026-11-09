package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.net.URLClassLoader
import java.util.Optional
import javax.tools.ToolProvider
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeOptionalValuesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `generated optional code preserves empty and present results on the JVM`() {
        val source = """
            package net.minecraft.world.item.crafting;
            import java.util.Optional;
            public class RecipeOptionalFixture {
                public static Optional<CraftingRecipe> recipe(boolean present) {
                    Optional<CraftingRecipe> result = new RecipeManager(present).getRecipeFor(null, null, null).map(RecipeHolder::value);
                    return result;
                }
                public static Optional<String> other() {
                    Optional<String> result = Optional.empty();
                    return result;
                }
            }
            record CraftingRecipe(int marker) {}
            record RecipeHolder<T>(T value) {}
            class RecipeManager {
                boolean present;
                RecipeManager(boolean present) { this.present = present; }
                Optional<RecipeHolder<CraftingRecipe>> getRecipeFor(Object type, Object input, Object level) {
                    return present ? Optional.of(new RecipeHolder<>(new CraftingRecipe(17))) : Optional.empty();
                }
            }
        """.trimIndent()
        val generated = StructuralRefactorPass().migrateRecipeHolderIdAndLocalMmlibApi(source)
        val file = directory.resolve("RecipeOptionalFixture.java")
        file.writeText(generated)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), file.toString()), generated)
        URLClassLoader(arrayOf(directory.toUri().toURL()), null).use { loader ->
            val fixture = loader.loadClass("net.minecraft.world.item.crafting.RecipeOptionalFixture")
            val recipe = fixture.getMethod("recipe", Boolean::class.javaPrimitiveType)
            assertTrue((recipe.invoke(null, false) as Optional<*>).isEmpty)
            assertTrue((recipe.invoke(null, true) as Optional<*>).isPresent)
            assertTrue((fixture.getMethod("other").invoke(null) as Optional<*>).isEmpty)
        }
    }

    @Test
    fun `holder locals return empty without touching a same-named optional in another method`() {
        val source = """
            import java.util.Optional;
            import net.minecraft.world.item.crafting.RecipeHolder;
            import net.minecraft.world.item.crafting.RecipeManager;
            class Queries {
                Optional<CraftingRecipe> recipe(RecipeManager manager) {
                    Optional<CraftingRecipe> result = manager.getRecipeFor(type, input, level).map(RecipeHolder::value);
                    return result;
                }
                Optional<String> other() {
                    Optional<String> result = Optional.empty();
                    return result;
                }
            }
        """.trimIndent()
        val result = StructuralRefactorPass().migrateRecipeHolderIdAndLocalMmlibApi(source)
        assertTrue(result.contains("return result.map(RecipeHolder::value);"), result)
        assertTrue(result.contains("Optional<String> result = Optional.empty();\n        return result;"), result)
        assertFalse(result.contains("Optional.of(result.get().value())"), result)
    }

    @Test
    fun `holder rewrite stays inside the declaring block when a sibling block reuses the name`() {
        val source = """
            import java.util.Optional;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.RecipeHolder;
            import net.minecraft.world.item.crafting.RecipeManager;
            class Mortar {
                ResourceLocation lastRecipeID;
                Optional<MortarRecipe> find(RecipeManager manager, boolean cached) {
                    if (cached) {
                        RecipeHolder<?> recipe = manager.byKey(lastRecipeID).get();
                        if (recipe.value() instanceof MortarRecipe mortar) {
                            return Optional.of(mortar);
                        }
                    }
                    if (lastRecipeID == null) {
                        Optional<MortarRecipe> recipe = manager.getRecipeFor(type, input, level).map(RecipeHolder::value);
                        if (recipe.isPresent()) {
                            lastRecipeID = recipe.get().getId();
                            return recipe;
                        }
                    }
                    return Optional.empty();
                }
                Optional<MortarRecipe> unsupported(RecipeManager manager) {
                    Optional<MortarRecipe> recipe = manager.getRecipeFor(type, input, level).map(RecipeHolder::value);
                    consume(recipe.get());
                    return recipe;
                }
            }
        """.trimIndent()

        val result = StructuralRefactorPass().migrateRecipeHolderIdAndLocalMmlibApi(source)

        assertTrue(result.contains("RecipeHolder<?> recipe = manager.byKey(lastRecipeID).get();"), result)
        assertTrue(
            result.contains(
                "Optional<RecipeHolder<MortarRecipe>> recipe = manager.getRecipeFor(type, input, level);"
            ),
            result
        )
        assertTrue(result.contains("lastRecipeID = recipe.get().id();"), result)
        assertTrue(result.contains("return recipe.map(RecipeHolder::value);"), result)
        // an unsupported use keeps the value optional unchanged
        assertTrue(
            result.contains(
                "Optional<MortarRecipe> recipe = manager.getRecipeFor(type, input, level).map(RecipeHolder::value);\n" +
                    "        consume(recipe.get());"
            ),
            result
        )
    }
}
