package com.modporter.core.transforms.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mod ids of catalog dependencies (`mappings/forge2neo/neoforge-deps.json`, field `modIds`, read
 * from the `META-INF/neoforge.mods.toml` of the entry's NeoForge artifact). A class whose package
 * belongs to such a dependency is owned by those mods at runtime.
 */
internal object OptionalDependencyModIds {
    private const val RESOURCE = "/mappings/forge2neo/neoforge-deps.json"

    private val prefixes: List<Pair<String, List<String>>> by lazy {
        val text = OptionalDependencyModIds::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()?.use { it.readText() }
            ?: return@lazy emptyList()
        Json.parseToJsonElement(text).jsonObject.getValue("dependencies").jsonArray.flatMap { element ->
            val entry = element.jsonObject
            val modIds = entry["modIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            if (modIds.isEmpty()) return@flatMap emptyList()
            entry["packagePrefixes"]?.jsonArray?.map { it.jsonPrimitive.content to modIds }.orEmpty()
        }
    }

    /** The mod ids that own [classFqn], or empty when the catalog has no evidence for it. */
    fun forClass(classFqn: String): List<String> {
        val matches = prefixes.filter { (prefix, _) -> classFqn.startsWith(prefix) }
        if (matches.isEmpty()) return emptyList()
        return matches.maxBy { it.first.length }.second
    }
}
