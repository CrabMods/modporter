package com.modporter.core.transforms.structural

import com.github.javaparser.StaticJavaParser
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RawShapedRecipeOutputResultsTest {
    @TempDir
    lateinit var project: Path

    @Test
    fun `raw shaped result becomes native recipe output without losing data`() {
        writeFixture()
        val recipeBefore = read("src/main/java/sample/MatrixRecipe.java")

        val changes = RawShapedRecipeOutputResults.migrate(
            project,
            dryRun = false
        )

        assertEquals(1, changes.size)
        val builder = read("src/main/java/sample/MatrixBuilder.java")
        assertEquals(
            recipeBefore,
            read("src/main/java/sample/MatrixRecipe.java")
        )
        assertFalse(builder.contains("class Payload"), builder)
        assertFalse(builder.contains(".toJson()"), builder)
        assertFalse(builder.contains("serializeRecipeData"), builder)
        assertTrue(builder.contains("validate(id);"), builder)
        assertFalse(
            builder.contains("accept(id, null"),
            builder
        )

        val unit = StaticJavaParser.parse(builder)
        val build = unit.getClassByName("MatrixBuilder").orElseThrow()
            .getMethodsByName("build")
            .single { it.parameters.size == 2 }
        assertEquals(2, build.body.orElseThrow().statements.size)
        val accept = build.body.orElseThrow().statements[1]
            .asExpressionStmt().expression.asMethodCallExpr()
        assertEquals("sink", accept.scope.orElseThrow().toString())
        assertEquals("accept", accept.nameAsString)
        assertEquals(4, accept.arguments.size)
        assertEquals("id", accept.arguments[0].toString())
        assertTrue(accept.arguments[2].isNullLiteralExpr)

        val recipe = accept.arguments[1].asObjectCreationExpr()
        assertEquals("sample.MatrixRecipe", recipe.type.asString())
        assertEquals(5, recipe.arguments.size)
        assertEquals("\"\"", recipe.arguments[0].toString())
        assertEquals(
            "net.minecraft.world.item.crafting.CraftingBookCategory.MISC",
            recipe.arguments[1].toString()
        )
        val pattern = recipe.arguments[2].asMethodCallExpr()
        assertEquals(
            "net.minecraft.world.item.crafting.ShapedRecipePattern",
            pattern.scope.orElseThrow().toString()
        )
        assertEquals("of", pattern.nameAsString)
        assertEquals(
            listOf("symbols", "layout"),
            pattern.arguments.map { it.toString() }
        )
        val stack = recipe.arguments[3].asObjectCreationExpr()
        assertEquals(
            "net.minecraft.world.item.ItemStack",
            stack.type.asString()
        )
        assertEquals(
            listOf("product", "amount"),
            stack.arguments.map { it.toString() }
        )
        assertEquals("allowFlip", recipe.arguments[4].toString())

        val conditions = accept.arguments[3].asMethodCallExpr()
        assertEquals("toArray", conditions.nameAsString)
        assertEquals("guards", conditions.scope.orElseThrow().toString())
        assertEquals(
            "new net.neoforged.neoforge.common.conditions.ICondition[0]",
            conditions.arguments.single().toString()
        )
    }

    @Test
    fun `ingredient codec key json remains eligible after serialization migration`() {
        writeFixture(ingredientCodecKeyJson())

        val changes = RawShapedRecipeOutputResults.migrate(
            project,
            dryRun = false
        )

        assertEquals(1, changes.size)
        val builder = read("src/main/java/sample/MatrixBuilder.java")
        assertFalse(builder.contains("class Payload"), builder)
        assertFalse(builder.contains("Ingredient.CODEC"), builder)
        assertFalse(builder.contains("serializeRecipeData"), builder)
    }

    @Test
    fun `ingredient codec key json rejects altered proof components`() {
        val invalid = mapOf(
            "codec" to ingredientCodecKeyJson(codec = "ICondition.CODEC"),
            "ops" to ingredientCodecKeyJson(
                ops = "com.mojang.serialization.JsonOps.EMPTY"
            ),
            "receiver" to ingredientCodecKeyJson(
                value = "other.getValue()"
            ),
            "value" to ingredientCodecKeyJson(
                value = "this.symbols.get(entry.getKey())"
            )
        )

        invalid.forEach { (label, keyJson) ->
            writeFixture(keyJson)
            val unsafe = read("src/main/java/sample/MatrixBuilder.java")

            val error = assertFailsWith<IllegalStateException>(
                "Expected $label mutation to fail closed"
            ) {
                RawShapedRecipeOutputResults.migrate(
                    project,
                    dryRun = false
                )
            }

            assertTrue(
                error.message.orEmpty().contains(
                    "does not preserve key JSON"
                ),
                "$label: ${error.message}"
            )
            assertEquals(
                unsafe,
                read("src/main/java/sample/MatrixBuilder.java"),
                label
            )
        }
    }

    @Test
    fun `migration is idempotent after the raw result is removed`() {
        writeFixture()

        RawShapedRecipeOutputResults.migrate(project, dryRun = false)
        val builder = read("src/main/java/sample/MatrixBuilder.java")
        val second = RawShapedRecipeOutputResults.migrate(
            project,
            dryRun = false
        )

        assertTrue(second.isEmpty())
        assertEquals(
            builder,
            read("src/main/java/sample/MatrixBuilder.java")
        )
    }

    @Test
    fun `missing mirror json fails before any source is written`() {
        writeFixture()
        val original = read("src/main/java/sample/MatrixBuilder.java")
        val unsafe = original.replace(
            "o.addProperty(\"acceptMirrored\", allowFlip);",
            "o.addProperty(\"different\", allowFlip);"
        )
        assertNotEquals(original, unsafe)
        write("src/main/java/sample/MatrixBuilder.java", unsafe)

        val error = assertFailsWith<IllegalStateException> {
            RawShapedRecipeOutputResults.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "does not preserve result or mirror JSON"
            )
        )
        assertEquals(unsafe, read("src/main/java/sample/MatrixBuilder.java"))
    }

    @Test
    fun `nonnull advancement fails closed`() {
        writeFixture()
        val original = read("src/main/java/sample/MatrixBuilder.java")
        val unsafe = Regex(
            """public JsonObject serializeAdvancement\(\)\s*\{\s*return null;\s*}"""
        ).replaceFirst(
            original,
            """
            public JsonObject serializeAdvancement() {
                return new JsonObject();
            }
            """.trimIndent()
        )
        assertNotEquals(original, unsafe)
        write("src/main/java/sample/MatrixBuilder.java", unsafe)

        val error = assertFailsWith<IllegalStateException> {
            RawShapedRecipeOutputResults.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "serializeAdvancement must return null"
            )
        )
        assertEquals(unsafe, read("src/main/java/sample/MatrixBuilder.java"))
    }

    @Test
    fun `target recipe must prove shaped and mirrored serialization`() {
        writeFixture()
        val original = read("src/main/java/sample/MatrixRecipe.java")
        val unsafe = original.replace(
            "RecipeSerializer.SHAPED_RECIPE.codec()",
            "Codec.STRING.fieldOf(\"opaque\")"
        )
        assertNotEquals(original, unsafe)
        write("src/main/java/sample/MatrixRecipe.java", unsafe)

        val error = assertFailsWith<IllegalStateException> {
            RawShapedRecipeOutputResults.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "with the exact target constructor and mirror codec; found 0"
            )
        )
        assertEquals(unsafe, read("src/main/java/sample/MatrixRecipe.java"))
    }

    @Test
    fun `ambiguous serializer association fails closed`() {
        writeFixture()
        val second = recipeSource()
            .replace("MatrixRecipe", "AlternateMatrixRecipe")
        write("src/main/java/sample/AlternateMatrixRecipe.java", second)
        val builder = read("src/main/java/sample/MatrixBuilder.java")

        val error = assertFailsWith<IllegalStateException> {
            RawShapedRecipeOutputResults.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "with the exact target constructor and mirror codec; found 2"
            )
        )
        assertEquals(builder, read("src/main/java/sample/MatrixBuilder.java"))
    }

    @Test
    fun `raw result type cannot escape the one build construction`() {
        writeFixture()
        val original = read("src/main/java/sample/MatrixBuilder.java")
        val unsafe = original.replace(
            "public class Payload implements RecipeOutput {",
            """
            private Payload cached;

                public class Payload implements RecipeOutput {
            """.trimIndent()
        )
        assertNotEquals(original, unsafe)
        write("src/main/java/sample/MatrixBuilder.java", unsafe)

        val error = assertFailsWith<IllegalStateException> {
            RawShapedRecipeOutputResults.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "escapes its closed build boundary"
            )
        )
        assertEquals(unsafe, read("src/main/java/sample/MatrixBuilder.java"))
    }

    @Test
    fun `unrelated recipe object result remains for the existing adapter`() {
        write(
            "src/main/java/sample/ObjectBackedResult.java",
            """
            package sample;

            import com.google.gson.JsonObject;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ObjectBackedResult implements RecipeOutput {
                private final Object recipe;

                public ObjectBackedResult(Object recipe) {
                    this.recipe = recipe;
                }

                public void serializeRecipeData(JsonObject json) {
                    json.addProperty("recipe", recipe.toString());
                }
            }
            """.trimIndent()
        )
        val original = read(
            "src/main/java/sample/ObjectBackedResult.java"
        )

        val changes = RawShapedRecipeOutputResults.migrate(
            project,
            dryRun = false
        )

        assertTrue(changes.isEmpty())
        assertEquals(
            original,
            read("src/main/java/sample/ObjectBackedResult.java")
        )
    }

    @Test
    fun `opaque condition encoder fails before any source is written`() {
        writeFixture()
        val original = read("src/main/java/sample/MatrixBuilder.java")
        val unsafe = Regex(
            """(?s)ICondition\.CODEC\s*\.encodeStart\(\s*""" +
                """com\.mojang\.serialization\.JsonOps\.INSTANCE,\s*""" +
                """condition\s*\)\s*\.getOrThrow\(\)\s*""" +
                """\.getAsJsonObject\(\)"""
        ).replaceFirst(
            original,
            "ConditionCodec.encode(condition)"
        )
        assertNotEquals(original, unsafe)
        write("src/main/java/sample/MatrixBuilder.java", unsafe)

        val error = assertFailsWith<IllegalStateException> {
            RawShapedRecipeOutputResults.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "does not preserve conditions JSON"
            )
        )
        assertEquals(unsafe, read("src/main/java/sample/MatrixBuilder.java"))
    }

    @Test
    fun `codec factory must preserve the shaped result`() {
        writeFixture()
        val original = read("src/main/java/sample/MatrixRecipe.java")
        val unsafe = original.replace(
            "recipe.getResultItem(null)",
            "ItemStack.EMPTY"
        )
        assertNotEquals(original, unsafe)
        write("src/main/java/sample/MatrixRecipe.java", unsafe)

        val error = assertFailsWith<IllegalStateException> {
            RawShapedRecipeOutputResults.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "with the exact target constructor and mirror codec; found 0"
            )
        )
        assertEquals(unsafe, read("src/main/java/sample/MatrixRecipe.java"))
    }

    private fun writeFixture(
        keyJson: String = "entry.getValue().toJson()"
    ) {
        write(
            "src/main/java/sample/RecipeKinds.java",
            """
            package sample;

            public final class RecipeKinds {
                public static final RecipeKinds MATRIX = new RecipeKinds();

                public Object getSerializer() {
                    return null;
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/sample/MatrixRecipe.java",
            recipeSource()
        )
        write(
            "src/main/java/sample/MatrixBuilder.java",
            builderSource(keyJson)
        )
    }

    private fun ingredientCodecKeyJson(
        codec: String =
            "net.minecraft.world.item.crafting.Ingredient.CODEC",
        ops: String = "com.mojang.serialization.JsonOps.INSTANCE",
        value: String = "entry.getValue()"
    ): String = "$codec.encodeStart($ops, $value).getOrThrow()"

    private fun recipeSource(): String =
        """
        package sample;

        import com.mojang.serialization.Codec;
        import com.mojang.serialization.MapCodec;
        import com.mojang.serialization.codecs.RecordCodecBuilder;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.item.crafting.CraftingBookCategory;
        import net.minecraft.world.item.crafting.RecipeSerializer;
        import net.minecraft.world.item.crafting.ShapedRecipe;
        import net.minecraft.world.item.crafting.ShapedRecipePattern;

        public class MatrixRecipe extends ShapedRecipe {
            private final boolean allowFlip;

            public MatrixRecipe(
                    String group,
                    CraftingBookCategory category,
                    ShapedRecipePattern pattern,
                    ItemStack result,
                    boolean allowFlip) {
                super(group, category, pattern, result);
                this.allowFlip = allowFlip;
            }

            private static MatrixRecipe fromShaped(
                    ShapedRecipe recipe,
                    boolean allowFlip) {
                return new MatrixRecipe(
                    recipe.getGroup(),
                    recipe.category(),
                    recipe.pattern,
                    recipe.getResultItem(null),
                    allowFlip);
            }

            public boolean acceptsFlip() {
                return this.allowFlip;
            }

            public RecipeSerializer<?> getSerializer() {
                return RecipeKinds.MATRIX.getSerializer();
            }

            public static class CodecHolder
                    implements RecipeSerializer<MatrixRecipe> {
                public static final MapCodec<MatrixRecipe> CODEC =
                    RecordCodecBuilder.mapCodec(instance -> instance.group(
                        RecipeSerializer.SHAPED_RECIPE.codec()
                            .forGetter(recipe -> recipe),
                        Codec.BOOL.optionalFieldOf("acceptMirrored", true)
                            .forGetter(MatrixRecipe::acceptsFlip)
                    ).apply(instance, MatrixRecipe::fromShaped));
            }
        }
        """.trimIndent()

    private fun builderSource(keyJson: String): String =
        """
        package sample;

        import java.util.List;
        import java.util.Map;
        import java.util.Map.Entry;
        import com.google.gson.JsonArray;
        import com.google.gson.JsonObject;
        import net.minecraft.data.recipes.RecipeOutput;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.world.item.Item;
        import net.minecraft.world.item.crafting.Ingredient;
        import net.minecraft.world.item.crafting.RecipeSerializer;
        import net.neoforged.neoforge.common.conditions.ICondition;

        public class MatrixBuilder {
            private final Item product;
            private final int amount;
            private final List<String> layout;
            private final Map<Character, Ingredient> symbols;
            private final boolean allowFlip;
            private final List<ICondition> guards;

            public void build(RecipeOutput sink) {
                this.build(
                    sink,
                    RecipeRegistry.getKeyOrThrow(this.product));
            }

            public void build(RecipeOutput sink, ResourceLocation id) {
                validate(id);
                sink.accept(new MatrixBuilder.Payload(
                    id,
                    product,
                    amount,
                    layout,
                    symbols,
                    allowFlip,
                    guards));
            }

            private void validate(ResourceLocation id) {
            }

            public class Payload implements RecipeOutput {
                private final ResourceLocation recipeId;
                private final Item product;
                private final int amount;
                private final List<String> layout;
                private final Map<Character, Ingredient> symbols;
                private final boolean allowFlip;
                private List<ICondition> guards;

                public Payload(
                        ResourceLocation recipeId,
                        Item product,
                        int amount,
                        List<String> layout,
                        Map<Character, Ingredient> symbols,
                        boolean allowFlip,
                        List<ICondition> guards) {
                    this.recipeId = recipeId;
                    this.product = product;
                    this.amount = amount;
                    this.layout = layout;
                    this.symbols = symbols;
                    this.allowFlip = allowFlip;
                    this.guards = guards;
                }

                public void serializeRecipeData(JsonObject o) {
                    JsonArray rows = new JsonArray();
                    for (String row : this.layout)
                        rows.add(row);
                    o.add("pattern", rows);
                    JsonObject keys = new JsonObject();
                    for (Entry<Character, Ingredient> entry : this.symbols.entrySet())
                        keys.add(
                            String.valueOf(entry.getKey()),
                            $keyJson);
                    o.add("key", keys);
                    JsonObject result = new JsonObject();
                    result.addProperty(
                        "item",
                        RecipeRegistry.getKeyOrThrow(this.product).toString());
                    if (this.amount > 1)
                        result.addProperty("count", this.amount);
                    o.add("result", result);
                    o.addProperty("acceptMirrored", allowFlip);
                    if (guards.isEmpty())
                        return;
                    JsonArray conditions = new JsonArray();
                    guards.forEach(condition ->
                        conditions.add(
                            ICondition.CODEC
                                .encodeStart(
                                    com.mojang.serialization.JsonOps.INSTANCE,
                                    condition)
                                .getOrThrow()
                                .getAsJsonObject()));
                    o.add("conditions", conditions);
                }

                public RecipeSerializer<?> getType() {
                    return RecipeKinds.MATRIX.getSerializer();
                }

                public ResourceLocation getId() {
                    return this.recipeId;
                }

                public JsonObject serializeAdvancement() {
                    return null;
                }

                public ResourceLocation getAdvancementId() {
                    return null;
                }
            }
        }
        """.trimIndent()

    private fun write(relative: String, source: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(source)
    }

    private fun read(relative: String): String =
        project.resolve(relative).readText()
}
