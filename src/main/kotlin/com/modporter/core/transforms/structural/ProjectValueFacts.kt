package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Constant-time equivalent of isDirectClassBodyMember for one immutable,
 * comment-and-literal-masked source.
 */
internal class MaskedClassBodyDepthIndex(maskedSource: String) {
    private val sourceLength = maskedSource.length
    private val depthBefore = IntArray(sourceLength + 1).also { depths ->
        for (index in maskedSource.indices) {
            depths[index + 1] = depths[index] + when (maskedSource[index]) {
                '{' -> 1
                '}' -> -1
                else -> 0
            }
        }
    }

    fun isDirectMember(classBody: IntRange, index: Int): Boolean {
        if (index <= classBody.first || classBody.first >= sourceLength) return false
        return depthBefore[index.coerceAtMost(sourceLength)] - depthBefore[classBody.first] == 1
    }
}

/** Canonical value declarations; empty entries block a same-named inherited declaration. */
internal data class ProjectValueFacts(
    val fields: Map<String, Map<String, String>> = emptyMap(),
    val getters: Map<String, Map<String, String>> = emptyMap(),
    val methods: Map<String, Map<String, List<Method>>> = emptyMap(),
    val parents: Map<String, String> = emptyMap(),
    val types: Set<String> = emptySet()
) {
    data class Method(val returnType: String, val parameterTypes: List<String>)

    companion object {
        private val platform = mapOf(
            "ItemStack" to "net.minecraft.world.item.ItemStack",
            "FluidStack" to "net.neoforged.neoforge.fluids.FluidStack",
            "ItemStackHandler" to "net.neoforged.neoforge.items.ItemStackHandler",
            "PanoramaRenderer" to "net.minecraft.client.renderer.PanoramaRenderer"
        )
        fun collect(pass: StructuralRefactorPass, files: List<Path>): ProjectValueFacts = with(pass) {
            val fields = mutableMapOf<String, MutableMap<String, String>>()
            val getters = mutableMapOf<String, MutableMap<String, String>>()
            val methods = mutableMapOf<String, MutableMap<String, MutableList<Method>>>()
            val parents = mutableMapOf<String, String>()
            val types = mutableSetOf<String>()
            val sources = files.associateWith { it.readText() }
            val projectTypes = mutableMapOf<String, MutableSet<String>>()
            for ((_, text) in sources) {
                val code = maskJavaCommentsAndLiterals(text)
                val blocks = javaTypeBlocks(text, code)
                val pkg = packageNameOf(text)
                for (block in blocks) {
                    val nested = blocks.filter { block.bodyStart in it.bodyStart..it.end }
                        .sortedByDescending { it.end - it.bodyStart }
                        .joinToString(".") { it.name }
                    if (nested.isNotBlank()) {
                        projectTypes.getOrPut(block.name) { linkedSetOf() } +=
                            if (pkg.isBlank()) nested else "$pkg.$nested"
                    }
                }
            }
            for ((_, text) in sources) {
                val code = maskJavaCommentsAndLiterals(text)
                val blocks = javaTypeBlocks(text, code)
                val directMembers = MaskedClassBodyDepthIndex(code)
                val ownTypes = blocks.map { it.name }.toSet()
                val pkg = packageNameOf(text)
                val ownTypeNames = blocks.mapNotNull { block ->
                    val nested = blocks.filter { block.bodyStart in it.bodyStart..it.end }
                        .sortedByDescending { it.end - it.bodyStart }
                        .joinToString(".") { it.name }
                    nested.takeIf { it.isNotBlank() }?.let {
                        block.name to if (pkg.isBlank()) it else "$pkg.$it"
                    }
                }.groupBy({ it.first }, { it.second })
                val imports = Regex("""(?m)^\s*import\s+(?!static\b)([\w.]+)\s*;""").findAll(code)
                    .associate { it.groupValues[1].substringAfterLast('.') to it.groupValues[1] }
                val onDemandImports = Regex("""(?m)^\s*import\s+(?!static\b)([\w.]+)\.\*\s*;""").findAll(code)
                    .map { it.groupValues[1] }
                    .toList()
                fun ownerAt(at: Int): String? = blocks.filter { at in it.bodyStart..it.end }
                    .sortedByDescending { it.end - it.bodyStart }.map { it.name }
                    .takeIf { it.isNotEmpty() }?.joinToString(".")?.let { if (pkg.isBlank()) it else "$pkg.$it" }
                fun canonical(type: String): String? {
                    var value = type.trim()
                    if (value == "?") return "?"
                    if (value.startsWith("? extends ")) {
                        return canonical(value.removePrefix("? extends ").trim())?.let { "? extends $it" }
                    }
                    if (value.startsWith("? super ")) {
                        return canonical(value.removePrefix("? super ").trim())?.let { "? super $it" }
                    }
                    if (value.endsWith("[]")) return canonical(value.dropLast(2).trim())?.plus("[]")
                    val open = value.indexOf('<')
                    val entry = (if (open < 0) value else value.substring(0, open)).trim()
                    if (entry in primitives) return entry
                    val registryEntries = setOf("com.tterrag.registrate.util.entry.ItemEntry", "com.tterrag.registrate.util.entry.BlockEntry", "com.tterrag.registrate.util.entry.ItemProviderEntry")
                    val base = when {
                        entry in registryEntries -> entry
                        entry !in ownTypes && imports[entry] in registryEntries -> imports.getValue(entry)
                        entry in platform.values -> entry
                        platform[entry] != null -> {
                            if (entry in ownTypes) return null
                            platform.getValue(entry).takeIf {
                                text.contains("import $it;") || text.contains("import ${it.substringBeforeLast('.')}.*;")
                            } ?: return null
                        }
                        ownTypeNames[entry]?.distinct()?.singleOrNull() != null -> ownTypeNames.getValue(entry).distinct().single()
                        imports[entry] != null -> imports.getValue(entry)
                        projectTypes[entry]?.filter { candidate ->
                            pkg.isBlank() && '.' !in candidate || pkg.isNotBlank() && candidate.startsWith("$pkg.")
                        }?.distinct()?.singleOrNull() != null ->
                            projectTypes.getValue(entry).filter { candidate ->
                                pkg.isBlank() && '.' !in candidate || pkg.isNotBlank() && candidate.startsWith("$pkg.")
                            }.distinct().single()
                        entry in setOf("String", "Object", "Integer", "Boolean", "Long", "Double", "Float", "Runnable") -> "java.lang.$entry"
                        '.' in entry && ' ' !in entry -> {
                            val first = entry.substringBefore('.')
                            when {
                                imports[first] != null -> imports.getValue(first) + entry.removePrefix(first)
                                first.firstOrNull()?.isLowerCase() == true -> entry
                                pkg.isNotBlank() -> "$pkg.$entry"
                                else -> entry
                            }
                        }
                        else -> return null
                    }
                    if (open < 0 || base in registryEntries) return base
                    val close = value.lastIndexOf('>')
                    if (close < open) return null
                    val arguments = JavaSourceText.splitTopLevelArgs(value.substring(open + 1, close))
                        .map { canonical(it) ?: return null }
                    return "$base<${arguments.joinToString(",")}>"
                }
                for (block in blocks) {
                    ownerAt(block.bodyStart)?.let(types::add)
                    val header = code.substring(block.declarationStart, block.bodyStart)
                    val declaration = Regex("""\bclass\s+${Regex.escape(block.name)}\b""").find(header) ?: continue
                    var cursor = declaration.range.last + 1
                    while (header.getOrNull(cursor)?.isWhitespace() == true) cursor++
                    if (header.getOrNull(cursor) == '<') {
                        var depth = 0
                        while (cursor < header.length) {
                            val c = header[cursor++]
                            if (c == '<') depth++
                            if (c == '>' && --depth == 0) break
                        }
                    }
                    val parent = Regex("""^\s*extends\s+([\w$.]+)""").find(header.substring(cursor))?.groupValues?.get(1) ?: continue
                    val first = parent.substringBefore('.')
                    val samePackage = if (pkg.isBlank()) parent else "$pkg.$parent"
                    val qualified = when {
                        imports[first] != null -> imports.getValue(first) + parent.removePrefix(first)
                        '.' in parent && parent.first().isLowerCase() -> parent
                        // a same-package type shadows an on-demand import
                        projectTypes[first]?.contains(if (pkg.isBlank()) first else "$pkg.$first") == true -> samePackage
                        '.' !in parent -> onDemandImports
                            .map { "$it.$parent" }
                            .filter { TargetMembers.hasClass(it) || projectTypes[first]?.contains(it) == true }
                            .distinct()
                            .singleOrNull()
                            ?: samePackage
                        else -> samePackage
                    }
                    ownerAt(block.bodyStart)?.let { parents[it] = qualified }
                }
                val type = """((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(?:\s*<[^;=(){}]*>)?(?:\s*\[\])?)"""
                val field = Regex("""(?m)(?:^|(?<=[;{}]))[ \t]*(?:(?:public|protected|private|static|final|volatile|transient)\s+)*$type\s+([A-Za-z_$][\w$]*)\s*(?:=[^;]*)?;""")
                for (match in field.findAll(code)) {
                    val fq = canonical(match.groupValues[1]) ?: ""
                    val owner = ownerAt(match.range.first) ?: continue
                    val block = blocks.filter { match.range.first in it.bodyStart..it.end }.minByOrNull { it.end - it.bodyStart } ?: continue
                    if (!directMembers.isDirectMember(block.bodyStart..block.end, match.range.first)) continue
                    fields.getOrPut(owner) { mutableMapOf() }[match.groupValues[2]] = fq
                }
                val getter = Regex("""(?m)(?:^|(?<=[;{}]))[ \t]*(?:(?:public|protected|private|static|final|synchronized|default)\s+)*$type\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*(?:throws\s+[^{;]+)?[{;]""")
                for (match in getter.findAll(code)) {
                    val fq = canonical(match.groupValues[1]) ?: ""
                    val owner = ownerAt(match.range.first) ?: continue
                    val block = blocks.filter { match.range.first in it.bodyStart..it.end }.minByOrNull { it.end - it.bodyStart } ?: continue
                    if (!directMembers.isDirectMember(block.bodyStart..block.end, match.range.first)) continue
                    getters.getOrPut(owner) { mutableMapOf() }[match.groupValues[2]] = fq
                }
                // a record declares one implicit accessor per component; an explicit accessor
                // (collected above) has the same type and is kept
                for (header in Regex("""\brecord\s+[A-Za-z_$][\w$]*\s*(?:<[^>{};]*>)?\s*\(""").findAll(code)) {
                    val close = JavaSourceText.matchingParen(code, header.range.last)
                    if (close < 0) continue
                    val bodyOpen = code.indexOf('{', close)
                    if (bodyOpen < 0) continue
                    val owner = ownerAt(bodyOpen + 1) ?: continue
                    val components = code.substring(header.range.last + 1, close).trim()
                    if (components.isEmpty()) continue
                    for (component in JavaSourceText.splitTopLevelArgs(components)) {
                        val (componentType, componentName) = parseJavaParameter(component.trim()) ?: continue
                        val fq = canonical(componentType) ?: continue
                        getters.getOrPut(owner) { mutableMapOf() }.putIfAbsent(componentName, fq)
                    }
                }
                val method = Regex(
                    """(?m)(?:^|(?<=[;{}]))[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\s+)*(?:<[^>{};]+>\s+)?$type\s+([A-Za-z_$][\w$]*)\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)\s*(?:throws\s+[^{;]+)?[{;]"""
                )
                for (match in method.findAll(code)) {
                    val owner = ownerAt(match.range.first) ?: continue
                    val block = blocks.filter { match.range.first in it.bodyStart..it.end }.minByOrNull { it.end - it.bodyStart } ?: continue
                    if (!directMembers.isDirectMember(block.bodyStart..block.end, match.range.first)) continue
                    val returnType = canonical(match.groupValues[1]) ?: continue
                    val parameters = match.groupValues[3].trim().let {
                        if (it.isBlank()) emptyList() else JavaSourceText.splitTopLevelArgs(it)
                    }
                    val parameterTypes = mutableListOf<String>()
                    var complete = true
                    for (parameter in parameters) {
                        val parameterType = parseJavaParameter(parameter.trim())?.first?.let(::canonical)
                        if (parameterType == null) {
                            complete = false
                            break
                        }
                        parameterTypes += parameterType
                    }
                    if (!complete) continue
                    val key = "${match.groupValues[2]}/${parameterTypes.size}"
                    methods.getOrPut(owner) { mutableMapOf() }.getOrPut(key) { mutableListOf() } +=
                        Method(returnType, parameterTypes)
                }
            }
            ProjectValueFacts(fields, getters, methods, parents, types)
        }

        private val primitives = setOf(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void"
        )
    }
}
