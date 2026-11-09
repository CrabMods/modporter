package com.modporter.core.transforms.structural

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class StainedGlassIngredientBoundariesTest {

    @Test
    fun `Create washing keeps the stained glass intersections`() {
        val source = createWashingSource(
            "Tags.Items.STAINED_GLASS",
            "Tags.Items.STAINED_GLASS_PANES"
        )

        val migrated = StructuralRefactorPass()
            .migrateStainedGlassIngredientBoundaries(source)
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
        assertEquals(
            migrated,
            StructuralRefactorPass()
                .migrateStainedGlassIngredientBoundaries(migrated)
        )
    }

    @Test
    fun `broad or malformed partner aborts the recipe pair`() {
        val broadPartner = createWashingSource(
            "Tags.Items.STAINED_GLASS",
            "Tags.Items.GLASS_PANES"
        )

        assertEquals(
            broadPartner,
            StructuralRefactorPass()
                .migrateStainedGlassIngredientBoundaries(broadPartner)
        )
    }

    @Test
    fun `lookalike washing generator is unchanged`() {
        val lookalike = createWashingSource(
            "Tags.Items.STAINED_GLASS",
            "Tags.Items.STAINED_GLASS_PANES"
        ).replace(
            "final class CreateWashingRecipeGen",
            "final class OtherWashingRecipeGen"
        )

        assertEquals(
            lookalike,
            StructuralRefactorPass()
                .migrateStainedGlassIngredientBoundaries(lookalike)
        )
    }

    @Test
    fun `conflicting intersection ingredient name is unchanged`() {
        val conflict = createWashingSource(
            "Tags.Items.STAINED_GLASS",
            "Tags.Items.STAINED_GLASS_PANES"
        ).replace(
            "import net.neoforged.neoforge.common.Tags;",
            """
            import example.IntersectionIngredient;
            import net.neoforged.neoforge.common.Tags;
            """.trimIndent()
        )

        assertEquals(
            conflict,
            StructuralRefactorPass()
                .migrateStainedGlassIngredientBoundaries(conflict)
        )
    }

    private fun createWashingSource(
        blockIngredient: String,
        paneIngredient: String
    ): String =
        """
        package com.simibubi.create.foundation.data.recipe;

        import com.simibubi.create.api.data.recipe.WashingRecipeGen;
        import net.minecraft.world.item.Items;
        import net.minecraft.world.item.crafting.Ingredient;
        import net.neoforged.neoforge.common.Tags;

        final class CreateWashingRecipeGen extends WashingRecipeGen {
            GeneratedRecipe
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
