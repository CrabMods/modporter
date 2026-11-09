package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Dependency types whose NeoForge 1.21.1 build exposes a `HolderLookup.Provider` accessor
 * (`mappings/forge2neo/library-provider-carriers.json`, from javap of the 1.21.1 jars). A method
 * with such a parameter, or a class extending such a type, already has a registry provider in
 * scope: `scene.world().getHolderLookupProvider()` on a Ponder scene builder, for example.
 */
internal object LibraryProviderCarriers {
    private const val RESOURCE = "/mappings/forge2neo/library-provider-carriers.json"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val parameterPattern = Regex("""(?:final\s+)?(?:@$ID(?:\([^)]*\))?\s*)*((?:$ID\.)*$ID)(?:<[^>]*>)?\s+($ID)$""")
    private val typePattern = Regex(
        """(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*(?:class|interface|enum|record)\s+$ID\b([^{;]*)\{"""
    )

    @Serializable
    data class Carrier(val owner: String, val accessor: String, val notes: String = "") {
        val ownerSimpleName: String get() = owner.substringAfterLast('.')
        val ownerPackage: String get() = owner.substringBeforeLast('.')
    }

    @Serializable
    private data class File(val description: String = "", val carriers: List<Carrier> = emptyList())

    val carriers: List<Carrier> by lazy {
        val stream = LibraryProviderCarriers::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyList()
        Json { ignoreUnknownKeys = true }.decodeFromString(File.serializer(), stream.bufferedReader().readText()).carriers
    }

    private fun visible(source: String, carrier: Carrier): Boolean =
        source.contains("import ${carrier.owner};") ||
            source.contains("import ${carrier.ownerPackage}.*;") ||
            source.contains(carrier.owner)

    private fun carrierNamed(simpleName: String, source: String): Carrier? =
        carriers.firstOrNull { it.ownerSimpleName == simpleName && visible(source, it) }

    /** `name.accessor()` for the carrier-typed parameters of the current method; the first one when several carry the same provider. */
    fun fromParameters(parameters: List<String>, source: String): String? =
        parameters.asSequence()
            .mapNotNull { parameter -> parameterPattern.find(parameter.trim().replace(Regex("""\s+"""), " ")) }
            .mapNotNull { match ->
                carrierNamed(match.groupValues[1].substringAfterLast('.'), source)?.let { match.groupValues[2] + it.accessor }
            }
            .firstOrNull()

    /** The accessor on `this` when a type enclosing [offset] (innermost first) extends or implements a carrier. */
    fun fromEnclosingTypes(source: String, offset: Int): String? {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        return typePattern.findAll(masked)
            .mapNotNull { match ->
                val close = JavaSourceText.matchingBrace(masked, match.range.last)
                if (close < 0 || offset !in match.range.last..close) null else Triple(match.groupValues[1], match.range.last, close)
            }
            .sortedBy { (_, open, close) -> close - open }
            .mapNotNull { (header, _, _) ->
                Regex("""\b(?:extends|implements)\b([^{]*)""").find(header)?.groupValues?.get(1)
                    ?.split(',')
                    ?.map { it.trim().substringBefore('<').substringAfterLast('.') }
                    ?.firstNotNullOfOrNull { carrierNamed(it, source) }
                    ?.accessor
                    ?.removePrefix(".")
            }
            .firstOrNull()
    }
}
