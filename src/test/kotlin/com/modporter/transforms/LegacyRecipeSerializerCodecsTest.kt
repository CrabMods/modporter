package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A recipe serializer keeps its own JSON and network code behind the 1.21 codecs. */
class LegacyRecipeSerializerCodecsTest {

    @TempDir
    lateinit var tempDir: Path

    private val srcDir: Path get() = tempDir.resolve("src/main/java/com/example")

    private fun java(name: String, text: String) {
        srcDir.createDirectories()
        srcDir.resolve("$name.java").writeText(text.trimIndent() + "\n")
    }

    private fun read(name: String): String = srcDir.resolve("$name.java").readText()

    @Test
    fun `a builder based serializer keeps its json and buffer code and its recipes learn their ids`() {
        java("ExampleMod", """
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
            }
        """)
        java("GrindingRecipe", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

            public abstract class GrindingRecipe implements Recipe<RecipeWrapper> {
                protected final ResourceLocation id;
                protected final int duration;

                protected GrindingRecipe(ResourceLocation id, int duration) {
                    this.id = id;
                    this.duration = duration;
                }

                public ResourceLocation getId() {
                    return id;
                }
            }
        """)
        java("GrindingSerializer", """
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class GrindingSerializer<T extends GrindingRecipe> implements RecipeSerializer<T> {
                private final Factory<T> factory;

                public GrindingSerializer(Factory<T> factory) {
                    this.factory = factory;
                }

                public final void write(JsonObject json, T recipe) {
                    json.addProperty("duration", recipe.duration);
                }

                @Override
                public final T fromJson(ResourceLocation id, JsonObject json) {
                    return factory.create(id, GsonHelper.getAsInt(json, "duration"));
                }

                @Override
                public final void toNetwork(FriendlyByteBuf buffer, T recipe) {
                    buffer.writeVarInt(recipe.duration);
                }

                @Override
                public final T fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
                    return factory.create(id, buffer.readVarInt());
                }

                public interface Factory<T> {
                    T create(ResourceLocation id, int duration);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val serializer = read("GrindingSerializer")
        val recipe = read("GrindingRecipe")
        val compat = tempDir.resolve("src/main/java/com/modporter/generated/example/compat")
        assertTrue(serializer.contains("public com.mojang.serialization.MapCodec<T> codec() {"), serializer)
        assertTrue(serializer.contains("return LegacyRecipeCodecs.codec(this::fromJson, this::write);"), serializer)
        assertTrue(serializer.contains("return LegacyRecipeCodecs.streamCodec(this::toNetwork, this::fromNetwork);"), serializer)
        assertFalse(Regex("""@Override\s+public final T fromJson""").containsMatchIn(serializer), serializer)
        assertTrue(recipe.contains("implements LegacyRecipeCodecs.Identified, Recipe<RecipeWrapper>"), recipe)
        assertTrue(recipe.contains("protected ResourceLocation id;"), recipe)
        assertTrue(recipe.contains("public void modporter\$setRecipeId(ResourceLocation id) {"), recipe)
        assertTrue(compat.resolve("LegacyRecipeCodecs.java").exists())
        assertTrue(compat.resolve("LegacyRecipeIdEvents.java").readText().contains("@EventBusSubscriber(modid = \"example\")"))
        assertTrue(compat.resolve("LegacyRecipeIdClientEvents.java").readText().contains("RecipesUpdatedEvent"))
    }

    @Test
    fun `a shaped recipe subclass keeps its asymmetric match and both serializer payloads`() {
        java("ExampleMod", """
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
            }
        """)
        java("LargeCraftingInventory", """
            package com.example;

            import net.minecraft.world.inventory.CraftingContainer;

            public abstract class LargeCraftingInventory implements CraftingContainer {
            }
        """)
        java("LargeShapedRecipe", """
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.core.NonNullList;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.ShapedRecipe;
            import net.minecraft.world.level.Level;

            public class LargeShapedRecipe extends ShapedRecipe {
                private boolean allowReflected;

                public LargeShapedRecipe(ResourceLocation id, String group, int width, int height,
                        NonNullList<Ingredient> ingredients, ItemStack output, boolean allowReflected) {
                    super(id, group, CraftingBookCategory.MISC, width, height, ingredients, output);
                    this.allowReflected = allowReflected;
                }

                private static LargeShapedRecipe fromShaped(ShapedRecipe recipe, boolean allowReflected) {
                    return new LargeShapedRecipe(recipe.getId(), recipe.getGroup(), recipe.getWidth(), recipe.getHeight(),
                        recipe.getIngredients(), recipe.getResultItem(null), allowReflected);
                }

                @Override
                public boolean matches(CraftingContainer input, Level level) {
                    if (!(input instanceof LargeCraftingInventory))
                        return false;
                    if (allowsReflected())
                        return super.matches(input, level);
                    for (int x = 0; x <= input.getWidth() - getWidth(); x++)
                        for (int y = 0; y <= input.getHeight() - getHeight(); y++)
                            if (matchesSpecific(input, x, y))
                                return true;
                    return false;
                }

                private boolean matchesSpecific(CraftingContainer input, int left, int top) {
                    NonNullList<Ingredient> ingredients = getIngredients();
                    int width = getWidth();
                    int height = getHeight();
                    for (int x = 0; x < input.getWidth(); x++) {
                        for (int y = 0; y < input.getHeight(); y++) {
                            int localX = x - left;
                            int localY = y - top;
                            Ingredient ingredient = Ingredient.EMPTY;
                            if (localX >= 0 && localY >= 0 && localX < width && localY < height)
                                ingredient = ingredients.get(localX + localY * width);
                            if (!ingredient.test(input.getItem(x + y * input.getWidth())))
                                return false;
                        }
                    }
                    return true;
                }

                public boolean allowsReflected() {
                    return allowReflected;
                }

                public static class Serializer extends ShapedRecipe.Serializer {
                    @Override
                    public ShapedRecipe fromJson(ResourceLocation id, JsonObject json) {
                        return fromShaped(super.fromJson(id, json), GsonHelper.getAsBoolean(json, "allowReflected", true));
                    }

                    @Override
                    public ShapedRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
                        return fromShaped(super.fromNetwork(id, buffer), buffer.readBoolean() && buffer.readBoolean());
                    }

                    @Override
                    public void toNetwork(FriendlyByteBuf buffer, ShapedRecipe recipe) {
                        super.toNetwork(buffer, recipe);
                        if (recipe instanceof LargeShapedRecipe) {
                            buffer.writeBoolean(true);
                            buffer.writeBoolean(((LargeShapedRecipe) recipe).allowsReflected());
                        } else {
                            buffer.writeBoolean(false);
                        }
                    }
                }
            }
        """)
        java("LargeRecipeLookup", """
            package com.example;

            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;

            public class LargeRecipeLookup {
                boolean matches(CraftingContainer input, Level level) {
                    CraftingContainer grid = new LargeCraftingInventory() {};
                    boolean vanilla = level.getRecipeManager()
                        .getRecipeFor(RecipeType.CRAFTING, grid.asCraftInput(), level)
                        .isPresent();
                    boolean shaped = LargeRecipeTypes.LARGE.find(grid, level).isPresent();
                    boolean lookalike = LookalikeRecipeTypes.LARGE.find(grid, level).isPresent();
                    return vanilla || shaped || lookalike;
                }
            }
        """)
        java("LargeRecipeTypes", """
            package com.example;

            import java.util.Optional;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;

            public enum LargeRecipeTypes {
                LARGE;

                public <I extends RecipeInput, T extends Recipe<I>> Optional<T> find(I input, Level level) {
                    return level.getRecipeManager().getRecipeFor(type(), input, level);
                }

                private <I extends RecipeInput, T extends Recipe<I>> RecipeType<T> type() {
                    return null;
                }
            }
        """)
        java("LookalikeRecipeTypes", """
            package com.example;

            import java.util.Optional;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;
            import net.minecraft.world.level.Level;

            public final class LookalikeRecipeTypes {
                public static final LookalikeRecipeTypes LARGE = new LookalikeRecipeTypes();

                public <I extends RecipeInput, T extends Recipe<I>> Optional<T> find(I input, Level level) {
                    return Optional.empty();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val recipe = read("LargeShapedRecipe")
        val lookup = read("LargeRecipeLookup")
        val bridge = tempDir.resolve("src/main/java/com/modporter/generated/example/compat/LegacyCraftingInputs.java").readText()
        assertTrue(recipe.contains("new ShapedRecipePattern(width, height, ingredients, java.util.Optional.empty())"), recipe)
        assertTrue(recipe.contains("super(group, category, pattern, output);"), recipe)
        assertTrue(recipe.contains("public boolean matches(CraftingInput input, Level level)"), recipe)
        assertTrue(recipe.contains("LegacyCraftingInputs.isFrom(input, LargeCraftingInventory.class)"), recipe)
        assertTrue(recipe.contains("input.width()"), recipe)
        assertTrue(recipe.contains("private boolean matchesSpecific(CraftingInput input"), recipe)
        assertTrue(recipe.contains("implements RecipeSerializer<LargeShapedRecipe>"), recipe)
        assertTrue(recipe.contains("RecipeSerializer.SHAPED_RECIPE.codec().forGetter(recipe -> recipe)"), recipe)
        assertTrue(recipe.contains("optionalFieldOf(\"allowReflected\", true)"), recipe)
        assertTrue(recipe.contains("ShapedRecipe.Serializer.STREAM_CODEC.decode(buffer)"), recipe)
        assertTrue(recipe.contains("buffer.readBoolean() && buffer.readBoolean()"), recipe)
        assertTrue(recipe.contains("buffer.writeBoolean(true);"), recipe)
        assertTrue(lookup.contains("LegacyCraftingInputs.from(grid)"), lookup)
        assertTrue(lookup.contains("LargeRecipeTypes.LARGE.find(LegacyCraftingInputs.from(grid), level)"), lookup)
        assertTrue(lookup.contains("LookalikeRecipeTypes.LARGE.find(grid, level)"), lookup)
        assertFalse(lookup.contains("LookalikeRecipeTypes.LARGE.find(LegacyCraftingInputs.from(grid), level)"), lookup)
        assertTrue(bridge.contains("ConcurrentMap<IdentityReference, Class<?>> ORIGINS"), bridge)
        assertTrue(bridge.contains("input == reference.get()"), bridge)
        assertFalse(bridge.contains("WeakHashMap"), bridge)
    }

    @Test
    fun `recipe predicates filtering looked up holders test the holder's recipe`() {
        java("RecipeTypes", """
            package com.example;

            import java.util.function.Predicate;
            import net.minecraft.world.item.crafting.Recipe;

            public class RecipeTypes {
                public static final Predicate<? super Recipe<?>> CAN_BE_AUTOMATED = r -> true;
            }
        """)
        java("Fan", """
            package com.example;

            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.item.crafting.SingleRecipeInput;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public class Fan {
                boolean canSmoke(ItemStack stack, Level level) {
                    return level.getRecipeManager()
                        .getRecipeFor(RecipeType.SMOKING, new SingleRecipeInput(stack), level)
                        .filter(RecipeTypes.CAN_BE_AUTOMATED)
                        .isPresent();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val fan = read("Fan")
        assertTrue(fan.contains(".filter(holder -> RecipeTypes.CAN_BE_AUTOMATED.test(holder.value()))"), fan)
    }

    @Test
    fun `direct recipe manager lambdas unwrap holders only at recipe consumers`() {
        java("RecipeTypes", """
            package com.example;

            import java.util.Optional;
            import net.minecraft.world.item.crafting.CraftingInput;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;

            public enum RecipeTypes {
                LARGE;

                public <I extends net.minecraft.world.item.crafting.RecipeInput, T extends Recipe<I>> Optional<T> find(
                        I input, Level level) {
                    return level.getRecipeManager().getRecipeFor(type(), input, level);
                }

                private <I extends net.minecraft.world.item.crafting.RecipeInput, T extends Recipe<I>> RecipeType<T> type() {
                    return null;
                }

                public static boolean allowed(CraftingRecipe recipe, CraftingInput input) {
                    return recipe.matches(input, null);
                }
            }
        """)
        java("RecipeLookup", """
            package com.example;

            import net.minecraft.core.RegistryAccess;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.CraftingInput;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;

            public class RecipeLookup {
                ItemStack craft(CraftingInput input, RegistryAccess registries, Level level) {
                    ItemStack result = level.getRecipeManager()
                        .getRecipeFor(RecipeType.CRAFTING, input, level)
                        .filter(recipe -> RecipeTypes.allowed(recipe, input))
                        .map(recipe -> recipe.assemble(input, registries))
                        .orElse(null);
                    return result;
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val types = read("RecipeTypes")
        val lookup = read("RecipeLookup")
        assertTrue(types.contains(".getRecipeFor(type(), input, level).map(RecipeHolder::value);"), types)
        assertTrue(types.contains("import net.minecraft.world.item.crafting.RecipeHolder;"), types)
        assertTrue(lookup.contains(".filter(recipe -> RecipeTypes.allowed(recipe.value(), input))"), lookup)
        assertTrue(lookup.contains(".map(recipe -> recipe.value().assemble(input, registries))"), lookup)
    }

    @Test
    fun `a finished recipe result holding its recipe saves it through the recipe output`() {
        java("GrindingRecipe", """
            package com.example;

            import net.minecraft.world.item.crafting.Recipe;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

            public abstract class GrindingRecipe implements Recipe<RecipeWrapper> {
            }
        """)
        java("GrindingBuilder", """
            package com.example;

            import com.google.gson.JsonArray;
            import com.google.gson.JsonObject;
            import java.util.List;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.common.conditions.ICondition;

            public class GrindingBuilder<T extends GrindingRecipe> {
                private final List<ICondition> conditions = List.of();

                public void build(RecipeOutput consumer, T recipe, ResourceLocation id) {
                    consumer.accept(new DataGenResult<>(recipe, id, conditions));
                }

                public static class DataGenResult<S extends GrindingRecipe> implements RecipeOutput {
                    private S recipe;
                    private ResourceLocation id;
                    private List<ICondition> recipeConditions;

                    public DataGenResult(S recipe, ResourceLocation id, List<ICondition> recipeConditions) {
                        this.recipe = recipe;
                        this.id = id;
                        this.recipeConditions = recipeConditions;
                    }

                    @Override
                    public void serializeRecipeData(JsonObject json) {
                        json.add("conditions", new JsonArray());
                    }

                    @Override
                    public ResourceLocation getId() {
                        return id;
                    }

                    @Override
                    public RecipeSerializer<?> getType() {
                        return null;
                    }

                    @Override
                    public JsonObject serializeAdvancement() {
                        return null;
                    }

                    @Override
                    public ResourceLocation getAdvancementId() {
                        return null;
                    }
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val builder = read("GrindingBuilder")
        assertTrue(builder.contains("new DataGenResult<>(recipe, id, conditions).saveTo(consumer);"), builder)
        assertTrue(builder.contains("public static class DataGenResult<S extends GrindingRecipe> {"), builder)
        assertTrue(builder.contains("output.accept(getId(), recipe, null, recipeConditions.toArray(new net.neoforged.neoforge.common.conditions.ICondition[0]));"), builder)
        assertFalse(builder.contains("serializeRecipeData"), builder)
        assertFalse(builder.contains("getAdvancementId"), builder)
    }
}
