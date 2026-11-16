package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyRecipeConstructionSurfaces
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyRecipeConstructionSurfacesTest {
    private val pass = StructuralRefactorPass()

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()

    @Test
    fun `removed ingredient value tests drop dead branches and tag values read their record tag`() {
        val source = """
            package example;

            import java.util.Collection;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.ItemTags;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Ingredient.ItemValue;
            import net.minecraft.world.item.crafting.Ingredient.TagValue;
            import net.minecraft.world.item.crafting.Ingredient.Value;
            import net.neoforged.neoforge.common.crafting.MultiItemValue;

            final class Filters {
                static ItemStack convert(Value itemList) {
                    Collection<ItemStack> stacks = itemList.getItems();
                    if (itemList instanceof ItemValue)
                        return stacks.iterator().next();
                    if (itemList instanceof TagValue) {
                        ResourceLocation location =
                            ResourceLocation.parse(GsonHelper.getAsString(itemList.serialize(), "tag"));
                        return filter(ItemTags.create(location));
                    }
                    if (itemList instanceof MultiItemValue) {
                        stacks.clear();
                        return ItemStack.EMPTY;
                    }
                    return ItemStack.EMPTY;
                }

                static ItemStack filter(Object tag) { return ItemStack.EMPTY; }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyRecipeConstructionSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains("ResourceLocation location = ((TagValue) itemList).tag().location();"),
            migrated
        )
        assertFalse(migrated.contains("MultiItemValue"), migrated)
        assertFalse(migrated.contains("stacks.clear()"), migrated)
        assertFalse(migrated.contains("GsonHelper"), migrated)
        assertTrue(normalized.contains("itemList instanceof TagValue"), migrated)
        assertTrue(normalized.contains("return stacks.iterator().next();"), migrated)
        assertTrue(
            normalized.contains("return filter(ItemTags.create(location)); } return ItemStack.EMPTY; }"),
            migrated
        )
        assertEquals(migrated, pass.migrateLegacyRecipeConstructionSurfaces(migrated))
    }

    @Test
    fun `a removed value test with an else branch keeps only that branch`() {
        val source = """
            package example;

            import net.minecraft.world.item.crafting.Ingredient.Value;
            import net.minecraftforge.common.crafting.MultiItemValue;

            final class Branches {
                static int count(Value value) {
                    if (value instanceof MultiItemValue) {
                        return 2;
                    } else {
                        return 1;
                    }
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyRecipeConstructionSurfaces(source)

        assertFalse(migrated.contains("MultiItemValue"), migrated)
        assertFalse(migrated.contains("return 2;"), migrated)
        assertTrue(compact(migrated).contains("static int count(Value value) { { return 1; } }"), migrated)
        assertEquals(migrated, pass.migrateLegacyRecipeConstructionSurfaces(migrated))
    }

    @Test
    fun `value tests and tag reads without proof stay unchanged`() {
        val source = """
            package example;

            import java.util.List;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.item.crafting.Ingredient.Value;
            import net.neoforged.neoforge.common.crafting.MultiItemValue;

            final class Producers {
                static Value make(List<net.minecraft.world.item.ItemStack> stacks) {
                    return new MultiItemValue(stacks);
                }
                static boolean test(Value value) {
                    if (value instanceof MultiItemValue) return true;
                    return false;
                }
                static String unguarded(Value value) {
                    return GsonHelper.getAsString(value.serialize(), "tag");
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyRecipeConstructionSurfaces(source))
    }

    @Test
    fun `recipe subclass constructors drop the legacy id argument`() {
        val source = """
            package example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.CustomRecipe;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.item.crafting.SingleItemRecipe;
            import net.minecraft.world.item.crafting.StonecutterRecipe;

            final class Condensed extends StonecutterRecipe {
                Condensed(Ingredient ingredient) {
                    super(ResourceLocation.parse(""), "", ingredient, ItemStack.EMPTY);
                }
            }

            class Dyeing extends CustomRecipe {
                Dyeing(CraftingBookCategory category) {
                    super(rl, category);
                }
            }

            class Cutting extends SingleItemRecipe {
                Cutting(RecipeType<?> type, RecipeSerializer<?> serializer, String group, Ingredient input, ItemStack result) {
                    super(type, serializer, ResourceLocation.fromNamespaceAndPath("example", "cutting"), group, input, result);
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyRecipeConstructionSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(normalized.contains("super(\"\", ingredient, ItemStack.EMPTY);"), migrated)
        assertTrue(normalized.contains("super(category);"), migrated)
        assertTrue(normalized.contains("super(type, serializer, group, input, result);"), migrated)
        assertFalse(migrated.contains("ResourceLocation"), migrated)
        assertEquals(migrated, pass.migrateLegacyRecipeConstructionSurfaces(migrated))
    }

    @Test
    fun `look-alike recipe bases and effectful ids keep their constructor arguments`() {
        val source = """
            package example;

            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.CustomRecipe;

            class StonecutterRecipe {
                StonecutterRecipe(Object id, Object group, Object input, Object result) {}
            }

            final class Lookalike extends StonecutterRecipe {
                Lookalike() {
                    super(null, "", null, null);
                }
            }

            class Effectful extends CustomRecipe {
                Effectful(CraftingBookCategory category) {
                    super(nextId(), category);
                }
                static Object nextId() { return null; }
            }

            class Shadowed extends CustomRecipe {
                static final Object category = null;
                Shadowed(CraftingBookCategory other) {
                    super(category, other);
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyRecipeConstructionSurfaces(source))
    }

    @Test
    fun `returned recipe stream elements keep their explicit id in RecipeHolder`() {
        val source = """
            package example;

            import java.util.Arrays;
            import java.util.stream.Stream;
            import net.minecraft.core.NonNullList;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.DyeColor;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.ShapelessRecipe;

            final class ColoringRecipes {
                static Stream<CraftingRecipe> createRecipes() {
                    String group = "example.color";
                    NonNullList<Ingredient> inputs = NonNullList.create();
                    return Arrays.stream(DyeColor.values()).map(color -> {
                        ItemStack output = ItemStack.EMPTY;
                        ResourceLocation id =
                            ResourceLocation.fromNamespaceAndPath("example", group + "." + output.getDescriptionId());
                        return new ShapelessRecipe(id, group, CraftingBookCategory.MISC, output, inputs);
                    });
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyRecipeConstructionSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(normalized.contains("static Stream<RecipeHolder<CraftingRecipe>> createRecipes()"), migrated)
        assertTrue(
            normalized.contains(
                "return new RecipeHolder<>(id, new ShapelessRecipe(group, CraftingBookCategory.MISC, output, inputs));"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "ResourceLocation id = ResourceLocation.fromNamespaceAndPath(\"example\", " +
                    "group + \".\" + output.getDescriptionId());"
            ),
            migrated
        )
        assertTrue(migrated.contains("import net.minecraft.world.item.crafting.RecipeHolder;"), migrated)
        assertEquals(migrated, pass.migrateLegacyRecipeConstructionSurfaces(migrated))
    }

    @Test
    fun `streams of non recipe values or look-alike recipes stay unchanged`() {
        val source = """
            package example;

            import java.util.Arrays;
            import java.util.stream.Stream;
            import net.minecraft.resources.ResourceLocation;

            class ShapelessRecipe {
                ShapelessRecipe(Object a, Object b, Object c, Object d, Object e) {}
            }

            final class Values {
                static Stream<ShapelessRecipe> recipes(ResourceLocation id) {
                    return Arrays.stream(new int[0]).boxed().map(value -> new ShapelessRecipe(id, "", null, null, null));
                }
                static Stream<Object> values(ResourceLocation id) {
                    return Arrays.stream(new int[0]).boxed().map(value -> new Object());
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyRecipeConstructionSurfaces(source))
    }

    @Test
    fun `whole stack tag copies into fresh stacks become component patch copies`() {
        val source = """
            package example;

            import com.tterrag.registrate.util.entry.BlockEntry;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.component.CustomData;
            import net.minecraft.world.item.crafting.CraftingInput;

            final class Copies {
                BlockEntry<?> entry;

                ItemStack assemble(CraftingInput inventory, HolderLookup.Provider registries) {
                    ItemStack source = inventory.getItem(0);
                    ItemStack result = new ItemStack(Items.STONE);
                    if (source.has(DataComponents.CUSTOM_DATA)) {
                        result.setTag(source.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                            .copy());
                    }
                    return result;
                }

                ItemStack recolor(ItemStack source) {
                    ItemStack recolored = entry.asStack();
                    if (source.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA))
                        recolored.setTag(source.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().copy());
                    return recolored;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyRecipeConstructionSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(normalized.contains("if (!source.isComponentsPatchEmpty()) {"), migrated)
        assertTrue(normalized.contains("result.applyComponents(source.getComponentsPatch());"), migrated)
        assertTrue(
            normalized.contains(
                "if (!source.isComponentsPatchEmpty()) recolored.applyComponents(source.getComponentsPatch());"
            ),
            migrated
        )
        assertFalse(migrated.contains("setTag("), migrated)
        assertFalse(migrated.contains("DataComponents"), migrated)
        assertFalse(migrated.contains("CustomData"), migrated)
        assertEquals(migrated, pass.migrateLegacyRecipeConstructionSurfaces(migrated))
    }

    @Test
    fun `tag copies into existing or touched stacks stay unchanged`() {
        val source = """
            package example;

            import net.minecraft.core.component.DataComponents;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.component.CustomData;

            final class Copies {
                void into(ItemStack source, ItemStack target) {
                    if (source.has(DataComponents.CUSTOM_DATA)) {
                        target.setTag(source.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().copy());
                    }
                }

                ItemStack touched(ItemStack source) {
                    ItemStack result = new ItemStack(Items.STONE);
                    result.setCount(2);
                    if (source.has(DataComponents.CUSTOM_DATA)) {
                        result.setTag(source.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().copy());
                    }
                    return result;
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyRecipeConstructionSurfaces(source))
    }

    @Test
    fun `CRLF sources keep their separators`() {
        val source = """
            package example;

            import net.minecraft.world.item.crafting.Ingredient.Value;
            import net.minecraftforge.common.crafting.MultiItemValue;

            final class Lines {
                static int count(Value value) {
                    if (value instanceof MultiItemValue) {
                        return 2;
                    }
                    return 1;
                }
            }
        """.trimIndent().replace("\n", "\r\n")

        val migrated = pass.migrateLegacyRecipeConstructionSurfaces(source)

        assertFalse(migrated.contains("MultiItemValue"), migrated)
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(migrated), migrated)
        assertTrue(migrated.contains("static int count(Value value) {\r\n        return 1;"), migrated)
    }
}
