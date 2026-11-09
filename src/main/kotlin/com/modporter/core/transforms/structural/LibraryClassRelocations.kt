package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Dependency classes that moved to another package between the Forge 1.20.1 and NeoForge 1.21.1
 * builds while keeping their API (`mappings/forge2neo/library-class-relocations.json`, from javap of
 * both jars). The import and any fully qualified use follow the class to its new package.
 */
internal object LibraryClassRelocations {
    private const val RESOURCE = "/mappings/forge2neo/library-class-relocations.json"

    @Serializable
    data class Relocation(val from: String, val to: String, val notes: String = "")

    @Serializable
    private data class File(val description: String = "", val relocations: List<Relocation> = emptyList())

    val relocations: List<Relocation> by lazy {
        val stream = LibraryClassRelocations::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyList()
        Json { ignoreUnknownKeys = true }.decodeFromString(File.serializer(), stream.bufferedReader().readText()).relocations
    }

    fun migrate(source: String): String {
        var result = source
        for (relocation in relocations) {
            if (!result.contains(relocation.from)) continue
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val uses = Regex("""(?<![\w$])${Regex.escape(relocation.from)}(?![\w$])""").findAll(masked).map { it.range }.toList()
            result = uses.asReversed().fold(result) { text, range -> text.replaceRange(range, relocation.to) }
        }
        return result
    }
}
