package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The registry an object type is registered into, so a registry-typed library argument can name it
 * (`DeathCounterDisplaySource` -> `DisplaySource`, `BeltBlock` -> `Block`).
 *
 * Registry element types come from evidence only: the platform registry keys
 * (`Registries`/`NeoForgeRegistries.Keys`, generic signatures in the 1.21.1 class table) and the
 * project's own `ResourceKey<Registry<X>>` declarations. A type's registry is the nearest element
 * type above it, walking the project's `extends`/`implements` and then the platform hierarchy;
 * when that is ambiguous there is no answer.
 */
internal class RegistryElementTypes private constructor(
    // raw simple name -> element type source text and the raw type's qualified name
    private val projectElements: Map<String, ProjectElement>,
    // project type simple name -> simple names of its direct supertypes
    private val projectSupertypes: Map<String, List<String>>
) {
    data class ProjectElement(val text: String, val qualifiedName: String)

    /**
     * The registry element type for [type] (a raw type name, simple or qualified) as source text
     * usable in [source]: simple when [source] can see the simple name, otherwise qualified.
     */
    fun registryTypeOf(type: String, source: String): String? {
        val simple = type.substringBefore('<').trim().substringAfterLast('.')
        var frontier = listOf(simple)
        val seen = HashSet<String>()
        while (frontier.isNotEmpty()) {
            val found = frontier.mapNotNull { elementFor(it, source) }.distinct()
            if (found.size > 1) return null
            found.singleOrNull()?.let { return it }
            frontier = frontier.filter(seen::add).flatMap { projectSupertypes[it].orEmpty() }
        }
        return null
    }

    private fun elementFor(simple: String, source: String): String? {
        projectElements[simple]?.let { element ->
            val visible = Regex("""(?m)^\s*import\s+${Regex.escape(element.qualifiedName)}\s*;""").containsMatchIn(source) ||
                packageOf(source) == element.qualifiedName.substringBeforeLast('.', "")
            return if (visible) element.text else element.qualifiedName + element.text.removePrefix(simple)
        }
        if (simple in projectSupertypes) return null
        return TargetMembers.classesNamed(simple)
            .mapNotNull { platformElementAbove(it) }
            .distinct()
            .singleOrNull()
    }

    private fun platformElementAbove(owner: String): String? {
        var frontier = listOf(owner)
        val seen = HashSet<String>()
        while (frontier.isNotEmpty()) {
            val found = frontier.mapNotNull { platformElements[it] }.distinct()
            if (found.size > 1) return null
            found.singleOrNull()?.let { return it }
            frontier = frontier.filter(seen::add).flatMap { TargetMembers.supertypesOf(it) }
        }
        return null
    }

    companion object {
        private val registryKeyHolders = listOf(
            "net.minecraft.core.registries.Registries",
            "net.neoforged.neoforge.registries.NeoForgeRegistries\$Keys"
        )
        private val registryKeySignature = Regex("""^Lnet/minecraft/resources/ResourceKey<Lnet/minecraft/core/Registry<(.+)>;>;$""")

        // element raw qualified name -> element type source text (qualified)
        private val platformElements: Map<String, String> by lazy {
            registryKeyHolders.flatMap { TargetMembers.fieldSignatures(it).values }
                .mapNotNull { registryKeySignature.find(it)?.groupValues?.get(1) }
                .mapNotNull { JvmSignatureText.referenceType(it) }
                .groupBy { it.substringBefore('<') }
                .filterValues { it.distinct().size == 1 }
                .mapValues { it.value.first() }
        }

        private val typeDeclaration = Regex(
            """\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)\s*(?:<[^{]*?>)?\s*(?:\([^)]*\)\s*)?([^{;]*)\{"""
        )
        private val supertypeClause = Regex("""\b(?:extends|implements)\s+([^{]+?)(?=\s+(?:extends|implements|permits)\b|$)""")
        private val registryKeyDeclaration = Regex("""\bResourceKey\s*<\s*Registry\s*<""")

        fun collect(javaFiles: List<Path>): RegistryElementTypes {
            val elements = HashMap<String, MutableSet<ProjectElement>>()
            val supertypes = HashMap<String, List<String>>()
            val sources = javaFiles.mapNotNull { file -> runCatching { file to file.readText() }.getOrNull() }
            // qualified names of the project's top-level types (one per file, named after it)
            val projectTypes = sources.mapNotNull { (file, source) ->
                val name = file.fileName.toString().removeSuffix(".java")
                packageOf(source).let { if (it.isEmpty()) name else "$it.$name" }
            }.toSet()
            for ((_, source) in sources) {
                val masked = JavaSourceText.maskCommentsAndLiterals(source)
                for (match in typeDeclaration.findAll(masked)) {
                    supertypes[match.groupValues[1]] = supertypeClause.findAll(match.groupValues[2])
                        .flatMap { splitTopLevel(it.groupValues[1]).asSequence() }
                        .map { it.trim().substringBefore('<').substringAfterLast('.').trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                }
                for (match in registryKeyDeclaration.findAll(masked)) {
                    val open = masked.indexOf('<', match.range.last - 1)
                    val close = matchingAngle(masked, open)
                    if (close < 0) continue
                    val element = source.substring(open + 1, close).trim()
                    val raw = element.substringBefore('<').trim()
                    // only plain or unbounded-wildcard element types name the same type everywhere
                    val arguments = element.substringAfter('<', "").removeSuffix(">")
                    if (arguments.isNotEmpty() && splitTopLevel(arguments).any { it.trim() != "?" }) continue
                    val qualified = qualify(raw, source, projectTypes) ?: continue
                    val simple = raw.substringAfterLast('.')
                    elements.getOrPut(simple) { linkedSetOf() } += ProjectElement(simple + element.removePrefix(raw), qualified)
                }
            }
            // a raw name declared as two different element types is ambiguous
            return RegistryElementTypes(elements.filterValues { it.size == 1 }.mapValues { it.value.first() }, supertypes)
        }

        /**
         * The qualified name of the type [raw] as [source] sees it: a single-type import, a project
         * type in its own package, or a project type under one of its on-demand imports.
         */
        private fun qualify(raw: String, source: String, projectTypes: Set<String>): String? {
            if (raw.contains('.')) return raw
            Regex("""(?m)^\s*import\s+([\w.]+\.${Regex.escape(raw)})\s*;""").find(source)?.let { return it.groupValues[1] }
            val pkg = packageOf(source)
            val candidates = listOf(if (pkg.isEmpty()) raw else "$pkg.$raw") +
                Regex("""(?m)^\s*import\s+([\w.]+)\.\*\s*;""").findAll(source).map { "${it.groupValues[1]}.$raw" }
            return candidates.filter { it in projectTypes }.distinct().singleOrNull()
        }

        private fun packageOf(source: String): String =
            Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(source)?.groupValues?.get(1).orEmpty()

        private fun matchingAngle(text: String, open: Int): Int {
            if (open < 0) return -1
            var depth = 0
            for (index in open until text.length) {
                when (text[index]) {
                    '<' -> depth++
                    '>' -> if (--depth == 0) return index
                    ';', '{', '}' -> return -1
                }
            }
            return -1
        }

        private fun splitTopLevel(text: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { index, c ->
                when (c) {
                    '<' -> depth++
                    '>' -> depth--
                    ',' -> if (depth == 0) {
                        parts += text.substring(start, index)
                        start = index + 1
                    }
                }
            }
            parts += text.substring(start)
            return parts
        }
    }
}

/** JVM generic signatures (JVMS 4.7.9.1) as Java source type text with qualified names. */
internal object JvmSignatureText {
    /** `Lnet/minecraft/world/level/block/entity/BlockEntityType<*>;` -> `net.minecraft.world.level.block.entity.BlockEntityType<?>`. */
    fun referenceType(signature: String): String? = runCatching {
        val (text, end) = parse(signature, 0)
        text.takeIf { end == signature.length }
    }.getOrNull()

    private fun parse(signature: String, start: Int): Pair<String, Int> = when (signature[start]) {
        '*' -> "?" to start + 1
        '+' -> parse(signature, start + 1).let { (type, end) -> "? extends $type" to end }
        '-' -> parse(signature, start + 1).let { (type, end) -> "? super $type" to end }
        'T' -> signature.indexOf(';', start).let { end -> signature.substring(start + 1, end) to end + 1 }
        '[' -> parse(signature, start + 1).let { (type, end) -> "$type[]" to end }
        'L' -> parseClass(signature, start + 1)
        else -> error("unsupported signature at $start: $signature")
    }

    private fun parseClass(signature: String, start: Int): Pair<String, Int> {
        val text = StringBuilder()
        var index = start
        while (true) {
            when (val c = signature[index]) {
                ';' -> return text.toString() to index + 1
                '/', '$', '.' -> { text.append('.'); index++ }
                '<' -> {
                    val arguments = mutableListOf<String>()
                    index++
                    while (signature[index] != '>') {
                        val (argument, end) = parse(signature, index)
                        arguments += argument
                        index = end
                    }
                    text.append('<').append(arguments.joinToString(", ")).append('>')
                    index++
                }
                else -> { text.append(c); index++ }
            }
        }
    }
}
