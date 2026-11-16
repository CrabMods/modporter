package com.modporter.core.transforms.structural

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** The entries of a module's Mixin configs: the JSON files under `src/main/resources` that declare a `package`. */
internal object MixinConfigEntries {
    class Unlisted(message: String) : RuntimeException(message)

    /**
     * The new text of every Mixin config of [moduleDir] that lists one of [mixins] (fully qualified
     * class names), without those entries, as before/after pairs. Each entry goes with the comma
     * that separated it, and the result must parse to the same config minus exactly those entries.
     * Throws [Unlisted] when a mixin is listed by no config or cannot be removed cleanly.
     */
    fun without(moduleDir: Path, mixins: Set<String>): Map<Path, Pair<String, String>> {
        val resources = moduleDir.resolve("src/main/resources")
        val configs = if (resources.isDirectory()) Files.walk(resources).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "json" }.sorted().toList()
        } else emptyList()
        val listed = mutableSetOf<String>()
        val writes = linkedMapOf<Path, Pair<String, String>>()
        for (config in configs) {
            val text = config.readText()
            if (mixins.none { text.contains(it.substringAfterLast('.')) }) continue
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: continue
            val packageName = (root["package"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val entries = mixins.filter { it.startsWith("$packageName.") }.map { it.removePrefix("$packageName.") }.toSet()
            var updated = text
            for (entry in entries) {
                val removed = removeEntry(updated, entry)
                if (removed != updated) listed += "$packageName.$entry"
                updated = removed
            }
            if (updated == text) continue
            if (runCatching { Json.parseToJsonElement(updated) }.getOrNull() != withoutEntries(root, entries)) {
                throw Unlisted("${moduleDir.relativize(config)} could not drop ${entries.sorted()} cleanly")
            }
            writes[config] = text to updated
        }
        (mixins - listed).takeIf { it.isNotEmpty() }?.let { throw Unlisted("no Mixin config lists ${it.sorted()}") }
        return writes
    }

    private fun withoutEntries(root: JsonObject, entries: Set<String>): JsonElement = JsonObject(root.mapValues { (_, value) ->
        if (value is JsonArray) JsonArray(value.filterNot { it is JsonPrimitive && it.isString && it.content in entries }) else value
    })

    /** [json] without every array element `"[entry]"`, each with the comma that separated it. */
    private fun removeEntry(json: String, entry: String): String {
        var result = json
        while (true) {
            val next = removeFirst(result, entry)
            if (next == result) return result
            result = next
        }
    }

    private fun removeFirst(json: String, entry: String): String {
        val match = Regex(""""${Regex.escape(entry)}"""").find(json) ?: return json
        val following = Regex("""^\s*,""").find(json.substring(match.range.last + 1))
        val (start, end) = if (following != null) {
            match.range.first to match.range.last + 1 + following.range.last + 1
        } else {
            val preceding = Regex(""",\s*$""").find(json.substring(0, match.range.first))
            if (preceding != null) preceding.range.first to match.range.last + 1 else match.range.first to match.range.last + 1
        }
        // a line left empty goes with the element
        val lineStart = json.lastIndexOf('\n', start - 1) + 1
        val lineEnd = json.indexOf('\n', end).let { if (it < 0) json.length else it + 1 }
        val rest = json.substring(lineStart, start) + json.substring(end, lineEnd)
        return if (rest.isBlank()) json.removeRange(lineStart, lineEnd) else json.removeRange(start, end)
    }
}
