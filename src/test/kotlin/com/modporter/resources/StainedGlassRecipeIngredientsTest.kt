package com.modporter.resources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class StainedGlassRecipeIngredientsTest {

    @Test
    fun `stained glass splashing ingredient becomes the exact intersection`() {
        val source = splashingRecipe("forge:stained_glass", "minecraft:glass")

        val migrated = migrateLegacyStainedGlassRecipeIngredients(source)
        val ingredient = migratedIngredient(migrated)
        assertEquals(
            "neoforge:intersection",
            ingredient.getValue("type").jsonPrimitive.content
        )
        assertEquals(
            listOf("c:glass_blocks", "c:dyed"),
            intersectionTags(ingredient)
        )
        assertEquals(
            migrated,
            migrateLegacyStainedGlassRecipeIngredients(migrated)
        )
    }

    @Test
    fun `stained glass pane splashing ingredient keeps pane membership`() {
        val source = splashingRecipe(
            "forge:stained_glass_panes",
            "minecraft:glass_pane"
        )

        val ingredient = migratedIngredient(
            migrateLegacyStainedGlassRecipeIngredients(source)
        )
        assertEquals(
            listOf("c:glass_panes", "c:dyed"),
            intersectionTags(ingredient)
        )
    }

    @Test
    fun `same tag outside the proven ingredient context is unchanged`() {
        val source =
            """
            {
              "type": "create:splashing",
              "ingredients": [
                {
                  "item": "minecraft:sand"
                }
              ],
              "metadata": {
                "tag": "forge:stained_glass"
              }
            }
            """.trimIndent() + "\n"

        assertEquals(
            source,
            migrateLegacyStainedGlassRecipeIngredients(source)
        )
    }

    @Test
    fun `lookalike recipe and broadened prior tag are unchanged`() {
        val lookalike = splashingRecipe(
            "forge:stained_glass",
            "minecraft:glass"
        ).replace("create:splashing", "example:splashing")
        val normalized = splashingRecipe(
            "c:stained_glass",
            "minecraft:glass"
        )

        assertEquals(
            lookalike,
            migrateLegacyStainedGlassRecipeIngredients(lookalike)
        )
        assertEquals(
            normalized,
            migrateLegacyStainedGlassRecipeIngredients(normalized)
        )
    }

    @Test
    fun `ingredient with additional meaning is unchanged`() {
        val source =
            """
            {
              "type": "create:splashing",
              "ingredients": [
                {
                  "tag": "forge:stained_glass",
                  "count": 2
                }
              ],
              "results": [
                {
                  "item": "minecraft:glass"
                }
              ]
            }
            """.trimIndent() + "\n"

        assertEquals(
            source,
            migrateLegacyStainedGlassRecipeIngredients(source)
        )
    }

    private fun splashingRecipe(tag: String, result: String): String =
        """
        {
          "type": "create:splashing",
          "ingredients": [
            {
              "tag": "$tag"
            }
          ],
          "results": [
            {
              "item": "$result"
            }
          ]
        }
        """.trimIndent() + "\n"

    private fun migratedIngredient(content: String): JsonObject {
        val root = JSON.parseToJsonElement(content) as JsonObject
        return (root.getValue("ingredients") as JsonArray)
            .single() as JsonObject
    }

    private fun intersectionTags(ingredient: JsonObject): List<String> =
        (ingredient.getValue("children") as JsonArray).map { child ->
            (child as JsonObject).getValue("tag").jsonPrimitive.content
        }

    private companion object {
        val JSON = Json
    }
}
