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
    fun `any recipe type gets the intersection and an already common tag is unchanged`() {
        val otherType = splashingRecipe(
            "forge:stained_glass",
            "minecraft:glass"
        ).replace("create:splashing", "example:washing")
        val normalized = splashingRecipe(
            "c:stained_glass",
            "minecraft:glass"
        )

        assertEquals(
            listOf("c:glass_blocks", "c:dyed"),
            intersectionTags(migratedIngredient(migrateLegacyStainedGlassRecipeIngredients(otherType)))
        )
        assertEquals(
            normalized,
            migrateLegacyStainedGlassRecipeIngredients(normalized)
        )
    }

    @Test
    fun `vanilla ingredient fields follow the table and alternatives lists stay as they are`() {
        val source =
            """
            {
              "type": "minecraft:crafting_shaped",
              "key": {
                "G": {
                  "tag": "forge:glass/red"
                },
                "P": [
                  {
                    "tag": "forge:stained_glass_panes"
                  },
                  {
                    "item": "minecraft:glass_pane"
                  }
                ]
              },
              "pattern": [
                "GP"
              ],
              "result": {
                "item": "minecraft:red_stained_glass"
              }
            }
            """.trimIndent() + "\n"

        val migrated = JSON.parseToJsonElement(migrateLegacyStainedGlassRecipeIngredients(source)) as JsonObject
        val key = migrated.getValue("key") as JsonObject
        assertEquals(listOf("c:glass_blocks", "c:dyed/red"), intersectionTags(key.getValue("G") as JsonObject))
        // a vanilla alternatives list holds item and tag values only, never a custom ingredient
        assertEquals(source.let { JSON.parseToJsonElement(it) as JsonObject }.getValue("key").let { (it as JsonObject).getValue("P") }, key.getValue("P"))

        val smelting = source.replace("\"type\": \"minecraft:crafting_shaped\"", "\"type\": \"minecraft:smelting\"")
            .let { JSON.parseToJsonElement(it) as JsonObject }
            .let { recipe -> JsonObject(mapOf("type" to recipe.getValue("type"), "ingredient" to (recipe.getValue("key") as JsonObject).getValue("G"))) }
            .toString()
        val ingredient = (JSON.parseToJsonElement(migrateLegacyStainedGlassRecipeIngredients(smelting)) as JsonObject).getValue("ingredient") as JsonObject
        assertEquals(listOf("c:glass_blocks", "c:dyed/red"), intersectionTags(ingredient))
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
