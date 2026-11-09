package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModernClientApiMigrationsTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `recipe holder streams and registrate ingredients keep legacy value semantics`() {
        val source = """
            import com.tterrag.registrate.providers.RegistrateRecipeProvider;
            import com.tterrag.registrate.util.DataIngredient;
            import java.util.List;
            import java.util.Optional;
            import java.util.function.Consumer;
            import net.minecraft.client.Minecraft;
            import net.minecraft.data.recipes.ShapedRecipeBuilder;
            import net.minecraft.data.recipes.ShapelessRecipeBuilder;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.CraftingInput;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeHolder;
            import net.minecraft.world.item.crafting.RecipeManager;
            import net.minecraft.world.item.crafting.RecipeType;
            class Recipes {
                CraftingRecipe selected;
                void find(RecipeManager manager, RecipeType<CraftingRecipe> type,
                          ShapedRecipeBuilder shaped, ShapelessRecipeBuilder shapeless,
                          RegistrateRecipeProvider provider, ResourceLocation id) {
                    List<CraftingRecipe> all = manager.getAllRecipesFor(type);
                    List<CraftingRecipe> explicit =
                        manager.<CraftingInput, CraftingRecipe>getAllRecipesFor(type);
                    Optional<CraftingRecipe> found = manager.getAllRecipesFor(type).stream().findFirst();
                    selected = manager.getAllRecipesFor(type).parallelStream()
                        .filter(recipe -> recipe.matches())
                        .findAny().orElse(null);
                    DataIngredient ingredient = DataIngredient.items(item);
                    shapeless.requires(ingredient);
                    shaped.define('X', DataIngredient.tag(tag))
                        .unlockedBy("has_item", ingredient.getCritereon(provider));
                    CraftingRecipe byId = manager.getAllRecipesFor(type).stream()
                        .filter(holder -> holder.id().equals(id))
                        .findFirst().orElse(null);
                    List<CraftingRecipe> alreadyMapped = manager.getAllRecipesFor(type).stream()
                        .map(RecipeHolder::value).toList();
                    List<CraftingRecipe> filtered = manager.getAllRecipesFor(type);
                    filtered = filtered.stream()
                        .filter(recipe -> blocked(recipe.getId()))
                        .toList();
                    ShapelessRecipeBuilder.shapeless(null, null)
                        .requires(DataIngredient.items(item));
                    ShapedRecipeBuilder.shaped(null, null)
                        .define('Y', DataIngredient.tag(tag));
                }
                void consumeRecipes(Consumer<Recipe<?>> consumer) {
                    Minecraft.getInstance().getConnection().getRecipeManager()
                        .getRecipes().forEach(consumer);
                }
                boolean blocked(Object id) { return false; }
            }
        """.trimIndent()
        var result = pass.migrateRecipeHolderStreamValues(source)
        result = pass.migrateRegistrateDataIngredients(result)
        assertTrue(result.contains("manager.getAllRecipesFor(type).stream().map(RecipeHolder::value).toList()"), result)
        assertTrue(result.contains(".stream().map(RecipeHolder::value).findFirst()"), result)
        assertTrue(result.contains(".parallelStream().map(RecipeHolder::value)"), result)
        assertTrue(result.contains("shapeless.requires(ingredient.toVanilla())"), result)
        assertTrue(result.contains("shaped.define('X', DataIngredient.tag(tag).toVanilla())"), result)
        assertTrue(result.contains("ingredient.getCriterion(provider)"), result)
        assertTrue(result.contains(".getRecipes().stream().map(RecipeHolder::value).forEach(consumer)"), result)
        assertTrue(result.contains("manager.<CraftingInput, CraftingRecipe>getAllRecipesFor(type).stream().map(RecipeHolder::value).toList()"), result)
        assertTrue(result.contains(".filter(holder -> holder.id().equals(id))\n            .findFirst()"), result)
        assertFalse(result.contains(".map(RecipeHolder::value).filter(holder -> holder.id()"), result)
        assertFalse(result.contains(".map(RecipeHolder::value).map(RecipeHolder::value)"), result)
        assertTrue(result.contains("List<CraftingRecipe> filtered = manager.getAllRecipesFor(type);"), result)
        assertTrue(result.contains(".requires(DataIngredient.items(item).toVanilla())"), result)
        assertTrue(result.contains(".define('Y', DataIngredient.tag(tag).toVanilla())"), result)
        assertTrue(pass.migrateRecipeHolderStreamValues(result) == result, result)
        assertTrue(pass.migrateRegistrateDataIngredients(result) == result, result)
    }

    @Test
    fun `recipe and datagen value migration rejects lookalike owners`() {
        val source = """
            class DataIngredient {
                static DataIngredient tag(Object tag) { return null; }
                Object getCritereon(Object provider) { return null; }
            }
            class Manager {
                Values getAllRecipesFor(Object type) { return null; }
            }
            class Builder {
                Builder requires(DataIngredient ingredient) { return this; }
                Builder define(char key, DataIngredient ingredient) { return this; }
            }
            class Lookalikes {
                void use(Manager manager, Builder builder, Object type, Object provider, Object tag) {
                    Object value = manager.getAllRecipesFor(type).stream().findFirst();
                    DataIngredient ingredient = DataIngredient.tag(tag);
                    builder.requires(ingredient).define('X', ingredient);
                    ingredient.getCritereon(provider);
                }
            }
        """.trimIndent()
        var result = pass.migrateRecipeHolderStreamValues(source)
        result = pass.migrateRegistrateDataIngredients(result)
        assertTrue(result == source, result)
    }

    @Test
    fun `recipe and datagen value migration requires exact generic and visible types`() {
        val source = """
            import com.tterrag.registrate.util.DataIngredient;
            import java.util.List;
            import java.util.function.Consumer;
            import net.minecraft.data.recipes.ShapedRecipeBuilder;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeManager;
            import net.minecraft.world.item.crafting.RecipeType;
            class FakeRecipe {}
            class OtherManager {
                List<Recipe<?>> getRecipes() { return null; }
            }
            class ShadowBuilder {
                ShadowBuilder shaped(Object category, Object item) { return this; }
                ShadowBuilder define(char key, DataIngredient ingredient) { return this; }
            }
            class Boundaries {
                void reject(RecipeManager manager, RecipeType type, OtherManager other,
                            ShadowBuilder ShapedRecipeBuilder, DataIngredient ingredient) {
                    List<FakeRecipe> fake = manager.getAllRecipesFor(type);
                    Consumer<Object> objectConsumer = value -> {};
                    manager.getRecipes().forEach(objectConsumer);
                    Consumer<Recipe<?>> recipeConsumer = value -> {};
                    other.getRecipes().forEach(recipeConsumer);
                    ShapedRecipeBuilder.shaped(null, null).define('X', ingredient);
                    {
                        Consumer<Recipe<?>> scoped = value -> {};
                    }
                    {
                        Consumer<Object> scoped = value -> {};
                        manager.getRecipes().forEach(scoped);
                    }
                }
            }
        """.trimIndent()
        var result = pass.migrateRecipeHolderStreamValues(source)
        result = pass.migrateRegistrateDataIngredients(result)
        assertTrue(result == source, result)
    }

    @Test
    fun `helmet overlay consumes supplied graphics and retains full screen texture state`() {
        val source = """
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.gui.Gui;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
            class Overlay extends Gui implements IClientItemExtensions {
                Overlay() { super(Minecraft.getInstance(), Minecraft.getInstance().getItemRenderer()); }
                @Override
                public void renderHelmetOverlay(ItemStack stack, Player player, int width, int height, float partialTick) {
                    float alpha = opacity(partialTick);
                    screenWidth = width;
                    screenHeight = height;
                    renderTextureOverlay(new GuiGraphics(Minecraft.getInstance(), buffers), TEXTURE, alpha);
                }
            }
        """.trimIndent()
        val result = pass.migrateLegacyHelmetOverlayRendering(source)
        assertTrue(result.contains("super(Minecraft.getInstance());"), result)
        assertTrue(result.contains("GuiGraphics guiGraphics, DeltaTracker deltaTracker"), result)
        assertTrue(result.contains("float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);"), result)
        assertTrue(result.contains("guiGraphics.blit(TEXTURE, 0, 0, -90"), result)
        assertTrue(result.contains("RenderSystem.depthMask(false);"), result)
        assertFalse(result.contains("screenWidth ="), result)
        assertFalse(result.contains("renderTextureOverlay("), result)
    }

    @Test
    fun `flywheel builder wraps the level instead of dropping model data lookup`() {
        val source = """
            import dev.engine_room.flywheel.lib.model.baked.ForgeBlockModelBuilder;
            class Visual {
                void build() {
                    var model = new ForgeBlockModelBuilder(modelWorld, blocks.positions())
                        .modelDataLookup(contraption::getModelData)
                        .materialFunc(materials)
                        .build();
                }
            }
        """.trimIndent()
        val result = pass.migrateLegacyFlywheelBlockModelSource(source, "example.compat")
        assertTrue(result.contains("import dev.engine_room.flywheel.lib.model.baked.BlockModelBuilder;"), result)
        assertTrue(result.contains("new BlockModelBuilder(new example.compat.LegacyModelDataBlockAndTintGetter(modelWorld, contraption::getModelData), blocks.positions())"), result)
        assertTrue(result.contains(".materialFunc(materials)"), result)
        assertFalse(result.contains("ForgeBlockModelBuilder"), result)
    }

    @Test
    fun `flywheel baked model factory becomes the public constructor without touching shadows`() {
        val source = """
            import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
            class Models {
                Object build(Object model) {
                    return BakedModelBuilder.create(model).materialFunc(materials).build();
                }
                Object unrelated(Object model) {
                    return OtherBakedModelBuilder.create(model);
                }
            }
        """.trimIndent()
        val result = pass.migrateFlywheelBakedModelBuilders(source)
        assertTrue(result.contains("new BakedModelBuilder(model).materialFunc(materials).build()"), result)
        assertTrue(result.contains("OtherBakedModelBuilder.create(model)"), result)
        assertFalse(result.contains("return BakedModelBuilder.create(model)"), result)
        assertTrue(pass.migrateFlywheelBakedModelBuilders(result) == result, result)
    }

    @Test
    fun `item stack duration receives the sole proven living context`() {
        val source = """
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            class Renderer {
                void render(ItemStack stack, LivingEntity player) {
                    int duration = stack.getUseDuration();
                }
                void ambiguous(ItemStack stack, LivingEntity first, LivingEntity second) {
                    int duration = stack.getUseDuration();
                }
            }
        """.trimIndent()
        val result = pass.migrateContextBoundItemStackUseDurations(source, JavaInheritanceIndex.EMPTY)
        assertTrue(result.contains("stack.getUseDuration(player)"), result)
        assertTrue(result.contains("void ambiguous(ItemStack stack, LivingEntity first, LivingEntity second) {\n        int duration = stack.getUseDuration();"), result)
    }
}
