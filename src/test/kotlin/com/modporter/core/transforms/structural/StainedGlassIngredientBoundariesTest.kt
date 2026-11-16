package com.modporter.core.transforms.structural

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class StainedGlassIngredientBoundariesTest {

    @Test
    fun `delegating tag overload receives the legacy intersection ingredient`() {
        val source = washingSource(
            "Tags.Items.STAINED_GLASS",
            "Tags.Items.STAINED_GLASS_PANES"
        )

        val pass = passWith(builder(), generator(), source)
        val migrated = pass.migrateStainedGlassIngredientBoundaries(source)
        val compact = compact(migrated)
        assertTrue(
            migrated.contains(
                "import net.neoforged.neoforge.common.crafting." +
                    "IntersectionIngredient;"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "b.require(IntersectionIngredient.of(" +
                    "Ingredient.of(Tags.Items.GLASS_BLOCKS), " +
                    "Ingredient.of(Tags.Items.DYED)))" +
                    ".output(Items.GLASS)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "b.require(IntersectionIngredient.of(" +
                    "Ingredient.of(Tags.Items.GLASS_PANES), " +
                    "Ingredient.of(Tags.Items.DYED)))" +
                    ".output(Items.GLASS_PANE)"
            ),
            migrated
        )
        assertFalse(migrated.contains("Tags.Items.STAINED_GLASS"), migrated)
        assertEquals(migrated, pass.migrateStainedGlassIngredientBoundaries(migrated))
    }

    @Test
    fun `colored legacy glass and the platform ingredient factory use the same data`() {
        val source = """
            package example;

            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.Tags;

            final class Inputs {
                static final Ingredient BLACK = Ingredient.of(Tags.Items.GLASS_BLACK);
                static final Object RETAINED = Tags.Items.STAINED_GLASS;
            }
        """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass().migrateStainedGlassIngredientBoundaries(source)
        assertTrue(
            migrated.contains(
                "BLACK = IntersectionIngredient.of(Ingredient.of(Tags.Items.GLASS_BLOCKS), " +
                    "Ingredient.of(Tags.Items.DYED_BLACK));"
            ),
            migrated
        )
        // a bare tag value has no ingredient form and is left for the compiler
        assertTrue(migrated.contains("RETAINED = Tags.Items.STAINED_GLASS;"), migrated)
    }

    @Test
    fun `already narrowed partner and unrelated tags remain unchanged`() {
        val source = washingSource(
            "Tags.Items.STAINED_GLASS",
            "Tags.Items.GLASS_PANES"
        )

        val migrated = passWith(builder(), generator(), source)
            .migrateStainedGlassIngredientBoundaries(source)
        val compact = compact(migrated)
        assertTrue(
            compact.contains(
                "b.require(IntersectionIngredient.of(" +
                    "Ingredient.of(Tags.Items.GLASS_BLOCKS), " +
                    "Ingredient.of(Tags.Items.DYED)))"
            ),
            migrated
        )
        assertTrue(compact.contains("b.require(Tags.Items.GLASS_PANES)"), migrated)
    }

    @Test
    fun `tag overload without an ingredient delegation is unchanged`() {
        val source = washingSource(
            "Tags.Items.STAINED_GLASS",
            "Tags.Items.STAINED_GLASS_PANES"
        )
        val storesTag = builder().replace(
            "return require(Ingredient.of(tag));",
            "tags.add(tag); return this;"
        )
        listOf(
            "stored tag" to passWith(storesTag, generator(), source),
            "no project declarations" to StructuralRefactorPass()
        ).forEach { (case, pass) ->
            assertEquals(source, pass.migrateStainedGlassIngredientBoundaries(source), case)
        }
    }

    @Test
    fun `conflicting intersection ingredient name uses the qualified target`() {
        val source = washingSource(
            "Tags.Items.STAINED_GLASS",
            "Tags.Items.STAINED_GLASS_PANES"
        ).replace(
            "import net.neoforged.neoforge.common.Tags;",
            """
            import example.IntersectionIngredient;
            import net.neoforged.neoforge.common.Tags;
            """.trimIndent()
        )

        val migrated = passWith(builder(), generator(), source)
            .migrateStainedGlassIngredientBoundaries(source)
        assertTrue(
            compact(migrated).contains(
                "b.require(net.neoforged.neoforge.common.crafting.IntersectionIngredient.of(" +
                    "Ingredient.of(Tags.Items.GLASS_BLOCKS), Ingredient.of(Tags.Items.DYED)))"
            ),
            migrated
        )
        assertFalse(
            migrated.contains("import net.neoforged.neoforge.common.crafting.IntersectionIngredient;"),
            migrated
        )
    }

    private fun passWith(vararg sources: String): StructuralRefactorPass =
        StructuralRefactorPass().also {
            it.projectSourceDeclarations = ProjectSourceDeclarations.of(sources.toList())
        }

    private fun builder(): String =
        """
        package example.recipe;

        import java.util.ArrayList;
        import java.util.List;
        import net.minecraft.tags.TagKey;
        import net.minecraft.world.item.Item;
        import net.minecraft.world.item.crafting.Ingredient;
        import net.minecraft.world.level.ItemLike;

        public class Builder<T> {
            private final List<Object> tags = new ArrayList<>();

            public Builder<T> require(TagKey<Item> tag) {
                return require(Ingredient.of(tag));
            }

            public Builder<T> require(ItemLike item) {
                return require(Ingredient.of(item));
            }

            public Builder<T> require(Ingredient ingredient) {
                return this;
            }

            public Builder<T> output(ItemLike item) {
                return this;
            }
        }
        """.trimIndent() + "\n"

    private fun generator(): String =
        """
        package example.recipe;

        import java.util.function.UnaryOperator;

        public abstract class RecipeGenerator {
            protected <T> Object create(String name, UnaryOperator<Builder<T>> transform) {
                return name;
            }
        }
        """.trimIndent() + "\n"

    private fun washingSource(
        blockIngredient: String,
        paneIngredient: String
    ): String =
        """
        package example.data;

        import example.recipe.RecipeGenerator;
        import net.minecraft.world.item.Items;
        import net.minecraft.world.item.crafting.Ingredient;
        import net.neoforged.neoforge.common.Tags;

        final class WashingRecipes extends RecipeGenerator {
            Object
                STAINED_GLASS = create(
                    "stained_glass",
                    b -> b.require($blockIngredient)
                        .output(Items.GLASS)),
                STAINED_GLASS_PANE = create(
                    "stained_glass_pane",
                    b -> b.require($paneIngredient)
                        .output(Items.GLASS_PANE));
        }
        """.trimIndent() + "\n"

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .replace(Regex("""\s*,\s*"""), ", ")
            .trim()
}
