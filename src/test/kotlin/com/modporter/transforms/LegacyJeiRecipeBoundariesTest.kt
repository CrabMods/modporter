package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateItemStackHoverNameSource
import com.modporter.core.transforms.structural.migrateJeiRecipeCategoryBackgroundApi
import com.modporter.core.transforms.structural.migrateLegacyJeiRecipeBoundaries
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyJeiRecipeBoundariesTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `JEI crafting transfer handler follows the holder recipe type contract`() {
        val source = """
            package example;

            import java.util.Optional;
            import mezz.jei.api.constants.RecipeTypes;
            import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
            import mezz.jei.api.recipe.RecipeType;
            import mezz.jei.api.recipe.transfer.IRecipeTransferError;
            import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.inventory.MenuType;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import org.jetbrains.annotations.Nullable;

            class BlueprintMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
                protected BlueprintMenu(MenuType<?> type, int id) { super(type, id); }
                public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int slot) {
                    return net.minecraft.world.item.ItemStack.EMPTY;
                }
                public boolean stillValid(Player player) { return true; }
            }

            class Packet {
                Packet(net.minecraft.resources.ResourceLocation id) {}
            }

            public class BlueprintTransferHandler
                    implements IRecipeTransferHandler<BlueprintMenu, CraftingRecipe> {
                public Class<BlueprintMenu> getContainerClass() {
                    return BlueprintMenu.class;
                }

                public Optional<MenuType<BlueprintMenu>> getMenuType() {
                    return Optional.empty();
                }

                public RecipeType<CraftingRecipe> getRecipeType() {
                    return RecipeTypes.CRAFTING;
                }

                public @Nullable IRecipeTransferError transferRecipe(
                        BlueprintMenu menu,
                        CraftingRecipe craftingRecipe,
                        IRecipeSlotsView recipeSlots,
                        Player player,
                        boolean maxTransfer,
                        boolean doTransfer) {
                    if (doTransfer)
                        new Packet(craftingRecipe.getId());
                    return null;
                }
            }
        """.trimIndent()

        val result = pass.migrateLegacyJeiRecipeBoundaries(source)

        assertTrue(
            result.contains(
                "implements IRecipeTransferHandler<BlueprintMenu, RecipeHolder<CraftingRecipe>>"
            ),
            result
        )
        assertTrue(
            result.contains("RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType()"),
            result
        )
        assertTrue(
            result.contains("RecipeHolder<CraftingRecipe> craftingRecipe"),
            result
        )
        assertTrue(result.contains("new Packet(craftingRecipe.id())"), result)
        assertTrue(
            result.contains("import net.minecraft.world.item.crafting.RecipeHolder;"),
            result
        )
        assertFalse(result.contains("craftingRecipe.getId()"), result)
        assertTrue(pass.migrateLegacyJeiRecipeBoundaries(result) == result, result)
    }

    @Test
    fun `JEI potion effect sets and typed recipe lookup use holder public APIs`() {
        val source = """
            package example;

            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.HashSet;
            import java.util.List;
            import java.util.Map;
            import java.util.Set;
            import java.util.function.Consumer;
            import java.util.stream.Collectors;
            import net.minecraft.client.Minecraft;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeType;

            public class CreateJEI {
                public void registerExtraIngredients(Collection<Potion> potions) {
                    Set<Set<MobEffect>> visitedEffects = new HashSet<>();
                    for (Potion potion : potions) {
                        if (!potion.getEffects().isEmpty())
                            if (!visitedEffects.add(potion.getEffects().stream()
                                    .map(MobEffectInstance::getEffect)
                                    .collect(Collectors.toSet())))
                                continue;
                    }
                }

                @SuppressWarnings("unchecked")
                public static <T extends Recipe<?>> void consumeTypedRecipes(
                        Consumer<T> consumer, RecipeType<?> type) {
                    Map<ResourceLocation, Recipe<?>> map = Minecraft.getInstance()
                        .getConnection()
                        .getRecipeManager().recipes.get(type);
                    if (map != null)
                        map.values().forEach(recipe -> consumer.accept((T) recipe));
                }
            }
        """.trimIndent()

        val result = pass.migrateLegacyJeiRecipeBoundaries(source)

        assertTrue(result.contains("Set<Set<Holder<MobEffect>>> visitedEffects"), result)
        assertTrue(result.contains("import net.minecraft.core.Holder;"), result)
        assertTrue(
            result.contains("List<? extends RecipeHolder<?>> holders = Minecraft.getInstance()"),
            result
        )
        assertTrue(result.contains(".getAllRecipesFor((RecipeType) type);"), result)
        assertTrue(
            result.contains(
                "holders.forEach(holder -> consumer.accept((T) holder.value()));"
            ),
            result
        )
        assertTrue(
            result.contains("@SuppressWarnings({\"unchecked\", \"rawtypes\"})"),
            result
        )
        assertTrue(
            result.contains("import net.minecraft.world.item.crafting.RecipeHolder;"),
            result
        )
        assertFalse(result.contains(".getRecipeManager().recipes"), result)
        assertFalse(result.contains("import java.util.Map;"), result)
        assertTrue(pass.migrateLegacyJeiRecipeBoundaries(result) == result, result)
    }

    @Test
    fun `JEI polishing payload saves the matching stack with client registries`() {
        val source = """
            package example;

            import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.core.NonNullList;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.component.CustomData;
            import net.minecraft.world.item.crafting.Ingredient;

            class SandPaperPolishingRecipe {
                NonNullList<Ingredient> getIngredients() { return NonNullList.create(); }
            }

            public class PolishingCategory {
                private final ItemStack renderedSandpaper = ItemStack.EMPTY;

                public void draw(
                        SandPaperPolishingRecipe recipe,
                        IRecipeSlotsView slots,
                        GuiGraphics graphics,
                        double mouseX,
                        double mouseY) {
                    NonNullList<Ingredient> ingredients = recipe.getIngredients();
                    ItemStack[] matchingStacks = ingredients.get(0).getItems();
                    if (matchingStacks.length == 0)
                        return;

                    CompoundTag tag = renderedSandpaper
                        .getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag();
                    tag.put("Polishing", matchingStacks[0].serializeNBT());
                    tag.putBoolean("JEI", true);
                    renderedSandpaper.set(
                        DataComponents.CUSTOM_DATA,
                        CustomData.of(tag));
                }
            }
        """.trimIndent()

        val result = pass.migrateLegacyJeiRecipeBoundaries(source)

        assertTrue(
            result.contains(
                "matchingStacks[0].saveOptional(" +
                    "net.minecraft.client.Minecraft.getInstance().level.registryAccess())"
            ),
            result
        )
        assertTrue(result.contains("tag.put(\"Polishing\","), result)
        assertTrue(result.contains("tag.putBoolean(\"JEI\", true);"), result)
        assertTrue(result.contains("CustomData.of(tag)"), result)
        assertFalse(result.contains("matchingStacks[0].serializeNBT()"), result)
        assertTrue(pass.migrateLegacyJeiRecipeBoundaries(result) == result, result)
    }

    @Test
    fun `JEI boundary lookalikes remain unchanged`() {
        val source = """
            package example;

            import java.util.HashSet;
            import java.util.Set;

            class MobEffect {}
            class RecipeTypes {
                static final Object CRAFTING = new Object();
            }
            class Lookalikes {
                Set<Set<MobEffect>> values = new HashSet<>();

                Object getRecipeType() {
                    return RecipeTypes.CRAFTING;
                }

                Object transferRecipe(Object recipe) {
                    return recipe.getId();
                }
            }
        """.trimIndent()

        assertTrue(pass.migrateLegacyJeiRecipeBoundaries(source) == source)
    }

    @Test
    fun `abstract JEI category keeps its background override and nested drawable draw`() {
        val source = """
            package example;

            import mezz.jei.api.gui.drawable.IDrawable;
            import mezz.jei.api.recipe.category.IRecipeCategory;
            import net.minecraft.client.gui.GuiGraphics;

            public abstract class CreateRecipeCategory<T> implements IRecipeCategory<T> {
                protected final IDrawable background;

                protected CreateRecipeCategory(IDrawable background) {
                    this.background = background;
                }

                @Override
                public IDrawable getBackground() {
                    return this.background;
                }

                protected static IDrawable asDrawable(Texture texture) {
                    return new IDrawable() {
                        public int getWidth() { return texture.getWidth(); }
                        public int getHeight() { return texture.getHeight(); }

                        @Override
                        public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
                            texture.render(graphics, xOffset, yOffset);
                        }
                    };
                }
            }
        """.trimIndent()

        val result = pass.migrateJeiRecipeCategoryBackgroundApi(source)

        assertTrue(result == source, result)
        assertTrue(result.contains("public IDrawable getBackground()"), result)
        assertFalse(result.contains("this.background.draw(graphics);"), result)
    }

    @Test
    fun `hover-name supplier creates and names a fresh stack for each invocation`() {
        val source = """
            package example;

            import java.util.function.Supplier;
            import net.minecraft.world.item.ItemStack;

            class ProcessingViaFanCategory {
                static Supplier<ItemStack> getFan(String name) {
                    return () -> AllBlocks.ENCASED_FAN.asStack()
                        .setHoverName(CreateLang.translateDirect("recipe." + name + ".fan")
                            .withStyle(style -> style.withItalic(false)));
                }
            }
        """.trimIndent()

        val result = pass.migrateItemStackHoverNameSource(source)
        val compact = result.replace(Regex("""\s+"""), " ")

        assertTrue(
            compact.contains(
                "return () -> net.minecraft.Util.make(AllBlocks.ENCASED_FAN.asStack(), " +
                    "modporterStack -> modporterStack.set(DataComponents.CUSTOM_NAME, " +
                    "CreateLang.translateDirect(\"recipe.\" + name + \".fan\")"
            ),
            result
        )
        assertFalse(result.contains("net.minecraft.Util.make(() ->"), result)
        assertTrue(
            result.contains("import net.minecraft.core.component.DataComponents;"),
            result
        )
        assertTrue(pass.migrateItemStackHoverNameSource(result) == result, result)
    }

    @Test
    fun `legacy JEI module repairs an abstract category corrupted by an older background pass`() {
        val source = """
            package example;

            import mezz.jei.api.gui.drawable.IDrawable;
            import mezz.jei.api.recipe.category.IRecipeCategory;
            import net.minecraft.client.gui.GuiGraphics;

            abstract class CreateRecipeCategory<T> implements IRecipeCategory<T> {
                protected final IDrawable background;
                protected final IDrawable icon;

                CreateRecipeCategory(Info<T> info) {
                    this.background = info.background();
                    this.icon = info.icon();
                }

                @Override
                public IDrawable getIcon() {
                    return icon;
                }

                protected static IDrawable asDrawable(Texture texture) {
                    return new IDrawable() {
                        public int getWidth() {
                            return texture.getWidth();
                        }

                        public int getHeight() {
                            return texture.getHeight();
                        }

                        @Override
                        public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
                            this.background.draw(graphics);
                            texture.render(graphics, xOffset, yOffset);
                        }
                    };
                }

                record Info<T>(IDrawable background, IDrawable icon) {}
            }
        """.trimIndent()

        val result = pass.migrateLegacyJeiRecipeBoundaries(source)

        assertTrue(result.contains("public IDrawable getBackground()"), result)
        assertTrue(result.contains("return this.background;"), result)
        assertFalse(result.contains("this.background.draw(graphics);"), result)
        assertTrue(result.contains("texture.render(graphics, xOffset, yOffset);"), result)
        assertTrue(pass.migrateLegacyJeiRecipeBoundaries(result) == result, result)
    }

    @Test
    fun `legacy JEI module repairs an already-wrapped supplier without sharing its stack`() {
        val source = """
            package example;

            import java.util.function.Supplier;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.world.item.ItemStack;

            abstract class ProcessingViaFanCategory {
                static Supplier<ItemStack> getFan(String name) {
                    return net.minecraft.Util.make(
                        () -> AllBlocks.ENCASED_FAN.asStack(),
                        modporterStack -> modporterStack.set(
                            DataComponents.CUSTOM_NAME,
                            CreateLang.translateDirect("recipe." + name + ".fan")));
                }
            }
        """.trimIndent()

        val result = pass.migrateLegacyJeiRecipeBoundaries(source)
        val repairedSupplier = Regex(
            """return\s+\(\s*\)\s*->\s*""" +
                """net\.minecraft\.Util\.make\s*\(\s*""" +
                """AllBlocks\.ENCASED_FAN\.asStack\s*\(\s*\)\s*,\s*""" +
                """modporterStack\s*->\s*modporterStack\.set\s*\(\s*""" +
                """DataComponents\.CUSTOM_NAME\s*,\s*""" +
                """CreateLang\.translateDirect\s*\(\s*""" +
                """"recipe\."\s*\+\s*name\s*\+\s*"\.fan"\s*""" +
                """\)\s*\)\s*\)\s*;"""
        )

        assertTrue(
            repairedSupplier.containsMatchIn(result),
            result
        )
        assertFalse(result.contains("Util.make(() ->"), result)
        assertTrue(pass.migrateLegacyJeiRecipeBoundaries(result) == result, result)
    }
}
