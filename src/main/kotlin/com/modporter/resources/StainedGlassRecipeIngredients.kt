package com.modporter.resources

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Preserves the exact legacy Forge stained-glass sets in Create splashing recipe resources.
 *
 * NeoForge defines `forge:stained_glass` as the intersection of `c:glass_blocks` and
 * `c:dyed`, and its pane counterpart as `c:glass_panes` intersected with `c:dyed`.
 * A direct common-tag rename would admit clear glass.
 *
 * Admission is limited to the sole root `ingredients` entry of `create:splashing`, and that
 * entry must be exactly the one-key legacy Forge tag object. Other JSON keys, recipe types,
 * ingredient shapes, and already rewritten resources remain unchanged.
 */
internal fun migrateLegacyStainedGlassRecipeIngredients(
    content: String
): String {
    if (!content.contains("\"forge:stained_glass")) return content

    val root = try {
        STAINED_GLASS_RECIPE_JSON.parseToJsonElement(content) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return content
    val type = (root["type"] as? JsonPrimitive)
        ?.contentOrNull
    if (type != "create:splashing") return content

    val ingredients = root["ingredients"] as? JsonArray ?: return content
    if (ingredients.size != 1) return content
    val migratedIngredient = migrateStainedGlassIngredientObject(
        ingredients.single()
    ) ?: return content

    val entries = linkedMapOf<String, JsonElement>()
    for ((key, value) in root) {
        entries[key] = if (key == "ingredients") {
            JsonArray(listOf(migratedIngredient))
        } else {
            value
        }
    }
    return STAINED_GLASS_RECIPE_JSON.encodeToString(
        JsonElement.serializer(),
        JsonObject(entries)
    ) + "\n"
}

private fun migrateStainedGlassIngredientObject(
    element: JsonElement
): JsonObject? {
    val ingredient = element as? JsonObject ?: return null
    if (ingredient.size != 1) return null
    val legacyTag = (ingredient["tag"] as? JsonPrimitive)
        ?.contentOrNull
        ?: return null
    val glassTag = when (legacyTag) {
        "forge:stained_glass" -> "c:glass_blocks"
        "forge:stained_glass_panes" -> "c:glass_panes"
        else -> return null
    }
    return JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("neoforge:intersection"),
            "children" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf("tag" to JsonPrimitive(glassTag))
                    ),
                    JsonObject(
                        mapOf("tag" to JsonPrimitive("c:dyed"))
                    )
                )
            )
        )
    )
}

private val STAINED_GLASS_RECIPE_JSON = Json {
    prettyPrint = true
}
