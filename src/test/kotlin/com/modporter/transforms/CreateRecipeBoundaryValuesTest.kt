package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCreateRecipeBoundaryValues
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateRecipeBoundaryValuesTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `recipe holders registrate values and crafting size cross proven 121 boundaries`() {
        val source = """
            package example;

            import com.tterrag.registrate.AbstractRegistrate;
            import com.tterrag.registrate.builders.BlockBuilder;
            import com.tterrag.registrate.providers.RegistrateRecipeProvider;
            import com.tterrag.registrate.util.DataIngredient;
            import java.util.List;
            import java.util.Optional;
            import net.minecraft.core.NonNullList;
            import net.minecraft.data.recipes.ShapedRecipeBuilder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.AbstractCookingRecipe;
            import net.minecraft.world.item.crafting.CampfireCookingRecipe;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;
            import net.minecraft.world.item.crafting.RecipeManager;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.item.crafting.ShapedRecipe;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.CampfireBlockEntity;

            class CreateRegistrate extends AbstractRegistrate<CreateRegistrate> {
            }

            class SandPaperInv implements RecipeInput {
                public ItemStack getItem(int slot) { return ItemStack.EMPTY; }
                public int size() { return 1; }
            }

            class Boundaries {
                static final CreateRegistrate REGISTRATE = null;
                final Params params = new Params();

                static void register(int width, int height) {
                    ShapedRecipe.setCraftingSize(width, height);
                }

                public <C extends RecipeInput, T extends Recipe<C>> Optional<T> find(C input, Level level) {
                    return level.getRecipeManager().getRecipeFor(getType(), input, level).map(RecipeHolder::value);
                }

                public <T extends RecipeType<?>> T getType() {
                    return null;
                }

                List<Recipe<SandPaperInv>> matching(Level level, SandPaperInv input) {
                    return level.getRecipeManager().getRecipesFor(getType(), input, level);
                }

                void loops(RecipeManager manager, RecipeType<Recipe<SandPaperInv>> type,
                           SandPaperInv input) {
                    for (Recipe<SandPaperInv> recipe : manager.getRecipesFor(type, input, null)) {
                        recipe.isSpecial();
                    }
                    for (Recipe<?> recipe : manager.getRecipes()) {
                        recipe.isSpecial();
                    }
                }

                void optional(RecipeManager manager, RecipeType<? extends AbstractCookingRecipe> type,
                              RecipeInput input, CampfireBlockEntity campfire, ItemStack stack) {
                    Optional<? extends AbstractCookingRecipe> cooking =
                        manager.getRecipeFor(type, input, null).filter(holder -> holder.value().isSpecial());
                    if (!cooking.isPresent()) {
                        cooking = manager.getRecipeFor(type, input, null)
                            .filter(holder -> holder.value().isSpecial());
                    }
                    Optional<CampfireCookingRecipe> campfireRecipe = campfire.getCookableRecipe(stack);
                }

                ItemStack guardedCampfire(Object blockEntity, ItemStack stack) {
                    if (!(blockEntity instanceof CampfireBlockEntity campfire))
                        return stack;
                    Optional<CampfireCookingRecipe> campfireRecipe = campfire.getCookableRecipe(stack);
                    return stack;
                }

                void ingredient(net.minecraft.resources.ResourceLocation id) {
                    params.ingredients.add(DataIngredient.ingredient(null, id));
                }

                BlockBuilder<?, ?> recipe() {
                    return REGISTRATE.block("example", properties -> null)
                        .recipe((context, provider) ->
                            ShapedRecipeBuilder.shaped(null, null)
                                .pattern("#")
                                .define('#', Ingredient.EMPTY)
                                .save(provider::accept));
                }

                static class Params {
                    NonNullList<Ingredient> ingredients = NonNullList.create();
                }
            }
        """.trimIndent()

        val result = pass.migrateCreateRecipeBoundaryValues(source)

        assertTrue(result.contains("ShapedRecipePattern.setCraftingSize(width, height)"), result)
        assertTrue(result.contains(".<C, T>getRecipeFor(getType(), input, level).map(RecipeHolder::value)"), result)
        assertTrue(
            result.contains(
                ".<SandPaperInv, Recipe<SandPaperInv>>getRecipesFor(getType(), input, level)" +
                    ".stream().map(RecipeHolder::value).toList()"
            ),
            result
        )
        assertTrue(
            result.contains("manager.getRecipesFor(type, input, null).stream().map(RecipeHolder::value).toList()"),
            result
        )
        assertTrue(
            result.contains("manager.getRecipes().stream().map(RecipeHolder::value).toList()"),
            result
        )
        assertTrue(
            result.contains(
                ".filter(holder -> holder.value().isSpecial()).map(RecipeHolder::value)"
            ),
            result
        )
        assertTrue(
            result.replace(Regex("""\s+"""), " ").contains(
                "cooking = manager.getRecipeFor(type, input, null) " +
                    ".filter(holder -> holder.value().isSpecial()).map(RecipeHolder::value);"
            ),
            result
        )
        assertTrue(
            Regex(
                Regex.escape("campfire.getCookableRecipe(stack).map(RecipeHolder::value)")
            ).findAll(result).count() == 2,
            result
        )
        assertTrue(
            result.contains("DataIngredient.ingredient(null, id).toVanilla()"),
            result
        )
        assertTrue(result.contains(".save(provider)"), result)
        assertFalse(result.contains(".save(provider::accept)"), result)
        assertTrue(
            result.contains("import net.minecraft.world.item.crafting.RecipeHolder;"),
            result
        )
        assertTrue(
            result.contains("import net.minecraft.world.item.crafting.ShapedRecipePattern;"),
            result
        )
        assertTrue(pass.migrateCreateRecipeBoundaryValues(result) == result, result)
    }

    @Test
    fun `lookalike recipe registrate ingredient and crafting APIs remain unchanged`() {
        val source = """
            class ShapedRecipe {
                static void setCraftingSize(int width, int height) {}
            }
            class DataIngredient {
                static DataIngredient ingredient(Object first, Object second) { return null; }
            }
            class Manager {
                Values getRecipes() { return null; }
                Values getRecipesFor(Object type, Object input, Object level) { return null; }
                Value getRecipeFor(Object type, Object input, Object level) { return null; }
            }
            class Builder {
                Builder recipe(Object callback) { return this; }
            }
            class Lookalikes {
                void use(Manager manager, Builder builder) {
                    ShapedRecipe.setCraftingSize(4, 5);
                    Object value = manager.getRecipes();
                    Object one = manager.getRecipeFor(null, null, null);
                    Object many = manager.getRecipesFor(null, null, null);
                    java.util.List<DataIngredient> values = null;
                    values.add(DataIngredient.ingredient(null, null));
                }
            }
        """.trimIndent()

        assertTrue(pass.migrateCreateRecipeBoundaryValues(source) == source)
    }
}
