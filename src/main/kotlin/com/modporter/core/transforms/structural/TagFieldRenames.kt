package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Forge's `Tags.<Class>.<FIELD>` constants that NeoForge 1.21.1 renamed (`Tags.Items.SAND_COLORLESS`
 * -> `Tags.Items.SANDS_COLORLESS`), from `tag-field-renames.json`: Forge's `Tags.java` and
 * NeoForge's `TagConventionLogWarning.java` joined on the legacy `forge:` tag path
 * (scripts/forge_tag_renames.py). Only references to NeoForge's `Tags` are renamed: `Tags.Items.X`
 * where the file imports `net.neoforged.neoforge.common.Tags`, and `Items.X` where it imports the
 * nested `Tags.Items` itself.
 */
internal object TagFieldRenames {
    private const val RESOURCE = "/mappings/forge2neo/tag-field-renames.json"
    private const val TAGS = "net.neoforged.neoforge.common.Tags"

    @Serializable
    private data class Rename(val `class`: String, val forgeField: String, val neoforgeField: String, val forgePath: String = "")

    @Serializable
    private data class RenamesFile(val description: String = "", val forgeSources: String = "", val neoforgeSources: String = "", val renames: List<Rename> = emptyList())

    private val renames: Map<String, Map<String, String>> by lazy {
        val stream = TagFieldRenames::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyMap()
        Json { ignoreUnknownKeys = true }.decodeFromString(RenamesFile.serializer(), stream.bufferedReader().readText())
            .renames.groupBy { it.`class` }.mapValues { (_, list) -> list.associate { it.forgeField to it.neoforgeField } }
    }

    fun migrate(source: String): String {
        if (!source.contains(TAGS)) return source
        var result = source
        for ((cls, fields) in renames) {
            val qualifiers = buildList {
                if (Regex("""(?m)^\s*import\s+${Regex.escape(TAGS)}\s*;""").containsMatchIn(source)) add("Tags\\.$cls")
                add("${Regex.escape(TAGS)}\\.$cls")
                if (Regex("""(?m)^\s*import\s+${Regex.escape(TAGS)}\.$cls\s*;""").containsMatchIn(source)) add(cls)
            }
            val pattern = Regex("""(?<![\w$.])(${qualifiers.joinToString("|")})\.([A-Z][A-Z0-9_]*)(?![\w$])""")
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val edits = pattern.findAll(masked).mapNotNull { match ->
                fields[match.groupValues[2]]?.let { match.groups[2]!!.range to it }
            }.toList()
            for ((range, replacement) in edits.asReversed()) {
                result = result.replaceRange(range, replacement)
            }
        }
        return result
    }
}
