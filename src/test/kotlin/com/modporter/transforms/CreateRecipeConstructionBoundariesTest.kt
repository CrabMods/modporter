package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCreateRecipeConstructionBoundaries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CreateRecipeConstructionBoundariesTest {
    private val pass = StructuralRefactorPass()

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()

    @Test
    fun `Blueprint retains registry transport while using compound and record ingredient APIs`() {
        val source = """
            package example;

            import java.util.Collection;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.ItemTags;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.Ingredient.ItemValue;
            import net.minecraft.world.item.crafting.Ingredient.TagValue;
            import net.minecraft.world.item.crafting.Ingredient.Value;
            import net.neoforged.neoforge.common.crafting.MultiItemValue;

            final class Blueprint {
                private static ItemStack convertIngredientToFilter(
                    Ingredient ingredient, HolderLookup.Provider registries
                ) {
                    Value[] acceptedItems = ingredient.values;
                    if (acceptedItems.length == 1)
                        return convertIItemListToFilter(acceptedItems[0], registries);
                    for (int i = 0; i < acceptedItems.length; i++)
                        convertIItemListToFilter(acceptedItems[i], registries);
                    return ItemStack.EMPTY;
                }

                private static ItemStack convertIItemListToFilter(
                    Value itemList, HolderLookup.Provider registries
                ) {
                    Collection<ItemStack> stacks = itemList.getItems();
                    if (itemList instanceof ItemValue)
                        return stacks.iterator().next();
                    if (itemList instanceof TagValue) {
                        ResourceLocation location =
                            ResourceLocation.parse(GsonHelper.getAsString(itemList.serialize(), "tag"));
                        return filter(ItemTags.create(location));
                    }
                    if (itemList instanceof MultiItemValue) {
                        serialize(stacks, registries);
                        return ItemStack.EMPTY;
                    }
                    return ItemStack.EMPTY;
                }

                static ItemStack filter(Object tag) { return ItemStack.EMPTY; }
                static void serialize(Collection<ItemStack> stacks, HolderLookup.Provider registries) {}
            }
        """.trimIndent()

        val migrated = pass.migrateCreateRecipeConstructionBoundaries(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "boolean isCompoundIngredient = ingredient.getCustomIngredient() " +
                    "instanceof CompoundIngredient;"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "convertIItemListToFilter(acceptedItems[0], registries, isCompoundIngredient)"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "convertIItemListToFilter(acceptedItems[i], registries, isCompoundIngredient)"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "Value itemList, HolderLookup.Provider registries, boolean isCompoundIngredient"
            ),
            migrated
        )
        assertTrue(normalized.contains("itemList instanceof TagValue"), migrated)
        assertTrue(
            normalized.contains("TagValue tagValue = (TagValue) itemList;"),
            migrated
        )
        assertTrue(normalized.contains("ItemTags.create(tagValue.tag().location())"), migrated)
        assertTrue(normalized.contains("if (isCompoundIngredient)"), migrated)
        assertTrue(normalized.contains("serialize(stacks, registries)"), migrated)
        assertTrue(
            migrated.contains(
                "import net.neoforged.neoforge.common.crafting.CompoundIngredient;"
            ),
            migrated
        )
        assertFalse(migrated.contains("MultiItemValue"), migrated)
        assertFalse(migrated.contains("itemList.serialize()"), migrated)
        assertEquals(migrated, pass.migrateCreateRecipeConstructionBoundaries(migrated))
    }

    @Test
    fun `Stonecutter and toolbox recipe constructors use exact 121 contracts`() {
        val source = """
            package com.simibubi.create.compat.jei;

            import com.simibubi.create.Create;
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
            import net.minecraft.world.item.crafting.StonecutterRecipe;

            final class Condensed extends StonecutterRecipe {
                Condensed(Ingredient ingredient) {
                    super(ResourceLocation.parse(""), "", ingredient, ItemStack.EMPTY);
                }
            }

            final class ToolboxColoringRecipeMaker {
                static Stream<CraftingRecipe> createRecipes() {
                    String group = "create.toolbox.color";
                    NonNullList<Ingredient> inputs = NonNullList.create();
                    ItemStack output = ItemStack.EMPTY;
                    return Arrays.stream(DyeColor.values()).map(color -> {
                        ResourceLocation id =
                            Create.asResource(group + "." + output.getDescriptionId());
                        return new ShapelessRecipe(
                            id, group, CraftingBookCategory.MISC, output, inputs
                        );
                    });
                }
            }
        """.trimIndent()

        val migrated = pass.migrateCreateRecipeConstructionBoundaries(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains("super(\"\", ingredient, ItemStack.EMPTY);"),
            migrated
        )
        assertTrue(
            normalized.contains(
                "Stream<RecipeHolder<CraftingRecipe>> createRecipes()"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "ShapelessRecipe recipe = new ShapelessRecipe(" +
                    "group, CraftingBookCategory.MISC, output, inputs);"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "return new RecipeHolder<>(" +
                    "Create.asResource(group + \"/\" + color), recipe);"
            ),
            migrated
        )
        assertTrue(
            migrated.contains("import net.minecraft.world.item.crafting.RecipeHolder;"),
            migrated
        )
        assertFalse(migrated.contains("ResourceLocation"), migrated)
        assertEquals(migrated, pass.migrateCreateRecipeConstructionBoundaries(migrated))
    }

    @Test
    fun `custom recipe constructor and copied NBT become category and component patch boundaries`() {
        val source = """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.component.CustomData;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.CraftingInput;
            import net.minecraft.world.item.crafting.CustomRecipe;

            final class ToolboxDyeingRecipe extends CustomRecipe {
                ToolboxDyeingRecipe(CraftingBookCategory category) {
                    super(rl, category);
                }

                public ItemStack assemble(
                    CraftingInput inventory, HolderLookup.Provider registries
                ) {
                    ItemStack toolbox = inventory.getItem(0);
                    ItemStack dyedToolbox = ItemStack.EMPTY;
                    if (toolbox.has(DataComponents.CUSTOM_DATA)) {
                        dyedToolbox.setTag(
                            toolbox.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                                .copyTag().copy()
                        );
                    }
                    return dyedToolbox;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateCreateRecipeConstructionBoundaries(source)
        val normalized = compact(migrated)

        assertTrue(normalized.contains("super(category);"), migrated)
        assertTrue(
            normalized.contains("if (!toolbox.isComponentsPatchEmpty())"),
            migrated
        )
        assertTrue(
            normalized.contains(
                "dyedToolbox.applyComponents(toolbox.getComponentsPatch());"
            ),
            migrated
        )
        assertFalse(migrated.contains("setTag("), migrated)
        assertFalse(migrated.contains("DataComponents"), migrated)
        assertFalse(migrated.contains("CustomData"), migrated)
        assertFalse(migrated.contains("ResourceLocation"), migrated)
        assertEquals(migrated, pass.migrateCreateRecipeConstructionBoundaries(migrated))
    }

    @Test
    fun `same named project recipe APIs remain unchanged`() {
        val source = """
            final class Ingredient {
                static final class Value {}
            }
            final class HolderLookup {
                static final class Provider {}
            }
            class StonecutterRecipe {}
            class CustomRecipe {}
            final class Lookalikes extends StonecutterRecipe {
                Lookalikes(Object ingredient) {
                    super();
                }
                static Object convert(
                    Ingredient.Value value, HolderLookup.Provider provider
                ) {
                    return value;
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateCreateRecipeConstructionBoundaries(source))
    }
}
