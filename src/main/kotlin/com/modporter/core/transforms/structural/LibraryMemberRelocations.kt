package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Static members of dependency classes that moved between the Forge 1.20.1 and NeoForge 1.21.1
 * builds (`mappings/forge2neo/library-member-relocations.json`, from javap of both jars). An
 * instance helper whose methods became static (`CatnipServices.REGISTRIES.getKeyOrThrow(x)` →
 * `RegisteredObjectsHelper.getKeyOrThrow(x)`) and a member that moved to another holder
 * (`ForgeCatnipServices.FLUID_RENDERER` → `CatnipServices.FLUID_RENDERER`) are both rewritten at
 * their use sites, with the imports following.
 */
internal object LibraryMemberRelocations {
    private const val RESOURCE = "/mappings/forge2neo/library-member-relocations.json"

    @Serializable
    data class Relocation(
        val owner: String,
        val member: String,
        val target: String,
        val targetMember: String? = null,
        val notes: String = ""
    ) {
        val ownerSimpleName: String get() = owner.substringAfterLast('.')
        val targetSimpleName: String get() = target.substringAfterLast('.')
    }

    @Serializable
    private data class File(val description: String = "", val relocations: List<Relocation> = emptyList())

    val relocations: List<Relocation> by lazy {
        val stream = LibraryMemberRelocations::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyList()
        Json { ignoreUnknownKeys = true }.decodeFromString(File.serializer(), stream.bufferedReader().readText()).relocations
    }

    fun migrate(source: String): String {
        var result = source
        for (relocation in relocations) {
            val imported = result.contains("import ${relocation.owner};")
            if (!imported && !result.contains(relocation.owner)) continue
            val ownerNames = listOf(Regex.escape(relocation.owner)) + if (imported) listOf(Regex.escape(relocation.ownerSimpleName)) else emptyList()
            val use = Regex("""(?<![\w$])(?:${ownerNames.joinToString("|")})\.${Regex.escape(relocation.member)}(?![\w$])""")
            val replacement = relocation.targetMember?.let { "${relocation.targetSimpleName}.$it" } ?: relocation.targetSimpleName
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val edits = use.findAll(masked).map { it.range }.toList()
            if (edits.isEmpty()) continue
            result = edits.asReversed().fold(result) { text, range -> text.replaceRange(range, replacement) }
            if (!result.contains("import ${relocation.target};") && JavaSourceText.packageName(result) != relocation.target.substringBeforeLast('.')) {
                result = JavaSourceText.addImport(result, relocation.target)
            }
        }
        for (relocation in relocations) {
            if (!result.contains("import ${relocation.owner};")) continue
            val stillUsed = Regex("""(?<![\w$.])${Regex.escape(relocation.ownerSimpleName)}(?![\w$])""")
                .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result).replace("import ${relocation.owner};", ""))
            if (!stillUsed) result = JavaSourceText.removeImport(result, relocation.owner)
        }
        return result
    }
}
