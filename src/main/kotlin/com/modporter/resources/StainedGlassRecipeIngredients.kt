package com.modporter.resources

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Legacy Forge item tags that NeoForge 1.21.1 splits into several tags keep their exact member set
 * in recipe resources.
 *
 * NeoForge's `TagConventionLogWarning` maps such a legacy path to the tags whose intersection holds
 * the old members (`forge:stained_glass` -> `c:glass_blocks` and `c:dyed`; a plain rename to one of
 * them would admit clear glass). The table is `intersections` in `tag-field-renames.json`, generated
 * from the Forge and NeoForge sources by `scripts/forge_tag_renames.py`.
 *
 * An ingredient written exactly as `{"tag": "forge:<path>"}` becomes a `neoforge:intersection`
 * ingredient of those tags where the vanilla recipe schema puts a whole ingredient: the
 * `ingredient`, `base`, `addition` and `template` fields, the entries of a shaped `key`, and the
 * elements of an `ingredients` list. Inside any other array (a vanilla alternatives list, which only
 * holds item and tag values) or under other fields it stays for the ordinary tag migration.
 */
internal fun migrateLegacyStainedGlassRecipeIngredients(
    content: String
): String {
    if (!content.contains("\"forge:")) return content
    if (LEGACY_ITEM_TAG_INTERSECTIONS.keys.none { content.contains("\"$it\"") }) return content

    val root = try {
        RECIPE_JSON.parseToJsonElement(content) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return content
    var changed = false

    fun migrate(element: JsonElement, wholeIngredient: Boolean, shapedKey: Boolean = false): JsonElement = when (element) {
        is JsonObject -> intersection(element)?.takeIf { wholeIngredient }?.also { changed = true }
            ?: JsonObject(element.mapValues { (key, value) ->
                when {
                    value is JsonArray -> JsonArray(value.map { migrate(it, wholeIngredient = key == "ingredients") })
                    shapedKey -> migrate(value, wholeIngredient = true)
                    else -> migrate(value, wholeIngredient = key in INGREDIENT_FIELDS, shapedKey = key == "key")
                }
            })
        is JsonArray -> JsonArray(element.map { migrate(it, wholeIngredient = false) })
        else -> element
    }

    val migrated = migrate(root, wholeIngredient = false)
    if (!changed) return content
    return RECIPE_JSON.encodeToString(JsonElement.serializer(), migrated) + "\n"
}

/** The intersection ingredient for an exact `{"tag": "forge:<split path>"}` value, or null. */
private fun intersection(ingredient: JsonObject): JsonObject? {
    if (ingredient.size != 1) return null
    val legacyTag = (ingredient["tag"] as? JsonPrimitive)?.contentOrNull ?: return null
    val tags = LEGACY_ITEM_TAG_INTERSECTIONS[legacyTag] ?: return null
    return JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("neoforge:intersection"),
            "children" to JsonArray(tags.map { JsonObject(mapOf("tag" to JsonPrimitive(it))) })
        )
    )
}

/** `forge:<legacy item tag path>` -> the NeoForge tags whose intersection holds its members. */
private val LEGACY_ITEM_TAG_INTERSECTIONS: Map<String, List<String>> by lazy {
    val stream = object {}.javaClass.getResourceAsStream("/mappings/forge2neo/tag-field-renames.json")
        ?: return@lazy emptyMap()
    val root = Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    root["intersections"]?.jsonArray.orEmpty().mapNotNull { entry ->
        val fields = entry.jsonObject
        if (fields["class"]?.jsonPrimitive?.contentOrNull != "Items") return@mapNotNull null
        val path = fields["forgePath"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val tags = fields["neoforgeTags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return@mapNotNull null
        if (tags.size < 2) return@mapNotNull null
        "forge:$path" to tags
    }.toMap()
}

/** Vanilla recipe fields holding one ingredient (cooking/stonecutting `ingredient`, smithing `base`/`addition`/`template`). */
private val INGREDIENT_FIELDS = setOf("ingredient", "base", "addition", "template")

private val RECIPE_JSON = Json {
    prettyPrint = true
}
