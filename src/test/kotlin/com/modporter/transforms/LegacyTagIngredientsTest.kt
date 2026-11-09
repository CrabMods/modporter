package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyTagIngredientsTest {
    @TempDir lateinit var directory: Path
    private var newline = "\n"
    private fun java(name: String, text: String): Path {
        val file = directory.resolve("src/main/java/example/$name.java")
        file.parent.createDirectories()
        file.writeText(text.trimIndent().replace("\n", newline))
        return file
    }
    private fun fixture(): Pair<Path, Path> {
        val ingredient = java("CatalogIngredient", """
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import com.google.gson.JsonElement;
            import com.google.gson.JsonObject;
            import it.unimi.dsi.fastutil.ints.IntArrayList;
            import it.unimi.dsi.fastutil.ints.IntList;
            import it.unimi.dsi.fastutil.ints.IntComparators;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.entity.player.StackedContents;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.common.crafting.AbstractIngredient;
            import net.neoforged.neoforge.common.crafting.CraftingHelper;
            import net.neoforged.neoforge.common.crafting.IIngredientSerializer;
            public class CatalogIngredient extends AbstractIngredient {
                final TagKey<Block> catalog;
                ItemStack[] items;
                IntList indices;
                CatalogIngredient(TagKey<Block> catalog) { this.catalog = catalog; }
                public static CatalogIngredient create(TagKey<Block> catalog) { return new CatalogIngredient(catalog); }
                void dissolve() {
                    if (items == null) {
                        List<ItemStack> list = new ArrayList<>();
                        for (Block block : BuiltInRegistries.BLOCK.getOrCreateTag(catalog)) {
                            ItemStack stack = new ItemStack(block);
                            if (!stack.isEmpty()) list.add(stack);
                        }
                        items = list.toArray(ItemStack[]::new);
                    }
                }
                public ItemStack[] getItems() { dissolve(); return items; }
                public boolean test(ItemStack stack) {
                    dissolve();
                    for (ItemStack item : items) if (item.is(stack.getItem())) return true;
                    return false;
                }
                @Override public IntList getStackingIds() {
                    if (indices == null || checkInvalidation()) {
                        markValid(); dissolve(); indices = new IntArrayList(items.length);
                        for (ItemStack stack : items) { indices.add(StackedContents.getStackingIndex(stack)); }
                        indices.sort(IntComparators.NATURAL_COMPARATOR);
                    }
                    return indices;
                }
                public boolean isSimple() { return true; }
                public IIngredientSerializer<? extends Ingredient> getSerializer() { return Serializer.INSTANCE; }
                @Override public JsonElement toJson() {
                    JsonObject json = new JsonObject();
                    json.addProperty("type", CraftingHelper.getID(Serializer.INSTANCE).toString());
                    json.addProperty("catalog", catalog.location().toString());
                    return json;
                }
                public static class Serializer implements IIngredientSerializer<CatalogIngredient> {
                    public static final Serializer INSTANCE = new Serializer();
                    @Override public CatalogIngredient parse(JsonObject json) {
                        return new CatalogIngredient(TagKey.create(Registries.BLOCK, ResourceLocation.parse(GsonHelper.getAsString(json, "catalog"))));
                    }
                    @Override public CatalogIngredient parse(FriendlyByteBuf buffer) {
                        return new CatalogIngredient(TagKey.create(Registries.BLOCK, buffer.readResourceLocation()));
                    }
                    @Override public void write(FriendlyByteBuf buffer, CatalogIngredient ingredient) { buffer.writeResourceLocation(ingredient.catalog.location()); }
                }
            }
        """)
        val registry = java("Ingredients", """
            package example;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.registries.RegisterEvent;
            import net.neoforged.neoforge.common.crafting.CraftingHelper;
            class Ingredients {
                static void register(RegisterEvent event) {
                    if (event.getRegistryKey().equals(Registries.RECIPE_SERIALIZER)) {
                        CraftingHelper.register(ResourceLocation.fromNamespaceAndPath("example", "catalog"), CatalogIngredient.Serializer.INSTANCE);
                    }
                }
            }
        """)
        return ingredient to registry
    }

    @Test
    fun `CRLF class headers remain valid Java when replacing the superclass with an interface`() {
        newline = "\r\n"
        val (ingredient, _) = fixture()
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val parsed = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)).parse(ingredient.readText())
        assertTrue(parsed.isSuccessful, parsed.problems.toString())
        assertTrue(ingredient.readText().contains("implements net.neoforged.neoforge.common.crafting.ICustomIngredient"), ingredient.readText())
    }

    @Test
    fun `tag ingredient retains matching and its original json key while adopting native type codecs`() {
        val (ingredient, registry) = fixture()
        val result = StructuralRefactorPass().apply(directory)
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "struct-legacy-tag-ingredient" }, result.changes.toString())
        val source = ingredient.readText()
        assertTrue(source.contains("implements net.neoforged.neoforge.common.crafting.ICustomIngredient"), source)
        assertTrue(source.contains("java.util.Arrays.stream(items)"), source)
        assertTrue(source.contains(".toVanilla()"), source)
        assertTrue(source.contains("Block block = modporterIngredientHolder.value()"), source)
        assertTrue(source.contains("if (item.is(stack.getItem())) return true"), source)
        assertTrue(source.contains("Stream.<String>of(\"catalog\")"), source)
        assertTrue(source.contains("Serializer.INSTANCE.parse(json)"), source)
        assertTrue(source.contains("Serializer.INSTANCE.write(buffer, value)"), source)
        assertTrue(registry.readText().contains("NeoForgeRegistries.Keys.INGREDIENT_TYPES"), registry.readText())
        assertFalse(source.contains("IIngredientSerializer"), source)
        assertFalse(source.contains("checkInvalidation"), source)
    }

    @Test
    fun `concrete ingredient consumers prevent an unproven public factory return type change`() {
        val (ingredient, _) = fixture()
        java("ConcreteUse", """
            package example;
            class ConcreteUse { CatalogIngredient value; }
        """)
        val result = StructuralRefactorPass().apply(directory)
        assertFalse(result.changes.any { it.ruleId == "struct-legacy-tag-ingredient" }, result.changes.toString())
        assertTrue(ingredient.readText().contains("extends AbstractIngredient"), ingredient.readText())
    }
}
