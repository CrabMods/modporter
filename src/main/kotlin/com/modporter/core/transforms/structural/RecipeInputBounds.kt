package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.core.transforms.util.JavaScopedDeclarations
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 1.21's `Recipe<T extends RecipeInput>` no longer accepts a `Container`: a mod's own recipe
 * type parameter bounded by `Container` and passed to `Recipe<T>` (`ProcessingRecipe<T extends
 * Container> implements Recipe<T>`, `<C extends Container, R extends Recipe<C>> find(C inv, ...)`)
 * is bounded by `RecipeInput` instead, and `getContainerSize()` on such a value is `size()`.
 */
internal object RecipeInputBounds {
    private const val ID = """[A-Za-z_$][\w$]*"""

    private data class Application(val name: String, val arguments: List<String>)
    private data class Declaration(val name: String, val parameters: List<String>, val applications: List<Application>)

    /** Generic parameter positions constrained to RecipeInput, followed through project types. */
    fun collect(files: List<Path>): Map<String, Set<Int>> {
        val declarations = files.flatMap { file ->
            val source = JavaSourceText.maskCommentsAndLiterals(file.readText())
            Regex("""\b(?:class|interface|record)\s+($ID)\b([^;{]*)\{""").findAll(source).map { type ->
                val tail = type.groupValues[2].trimStart()
                val parameters = if (tail.startsWith('<')) {
                    val close = matchingAngle(tail, 0)
                    if (close < 0) emptyList() else JavaSourceText.splitTopLevelArgs(tail.substring(1, close))
                        .map { it.substringBefore("extends").trim().substringAfterLast(' ') }
                } else emptyList()
                Declaration(type.groupValues[1], parameters, applications(tail))
            }.toList()
        }
        val ambiguous = declarations.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        // A project type named Recipe must not be confused with the platform interface.
        if (declarations.any { it.name == "Recipe" }) return emptyMap()
        val positions = mutableMapOf("Recipe" to mutableSetOf(0))
        var changed: Boolean
        do {
            changed = false
            for (type in declarations) {
                if (type.name in ambiguous || type.parameters.isEmpty()) continue
                for (application in type.applications) {
                    for (position in positions[application.name].orEmpty().toList()) {
                        val argument = application.arguments.getOrNull(position)?.trim() ?: continue
                        val parameter = type.parameters.indexOf(argument)
                        if (parameter >= 0 && positions.getOrPut(type.name) { mutableSetOf() }.add(parameter)) changed = true
                    }
                }
            }
        } while (changed)
        return positions.mapValues { it.value.toSet() }
    }

    private fun matchingAngle(text: String, start: Int): Int {
        var depth = 0
        for (index in start until text.length) {
            when (text[index]) {
                '<' -> depth++
                '>' -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun applications(text: String): List<Application> =
        Regex("""\b((?:$ID\.)*$ID)\s*<""").findAll(text).mapNotNull { match ->
            val close = matchingAngle(text, match.range.last)
            if (close < 0) null else Application(match.groupValues[1].substringAfterLast('.'),
                JavaSourceText.splitTopLevelArgs(text.substring(match.range.last + 1, close)))
        }.toList()

    fun containerArgumentRanges(text: String, positions: Map<String, Set<Int>>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (type in Regex("""\b((?:$ID\.)*$ID)\s*<""").findAll(text)) {
            val slots = positions[type.groupValues[1].substringAfterLast('.')].orEmpty()
            if (slots.isEmpty()) continue
            val close = matchingAngle(text, type.range.last)
            if (close < 0) continue
            val contents = text.substring(type.range.last + 1, close)
            var cursor = 0
            JavaSourceText.splitTopLevelArgs(contents).forEachIndexed { index, argument ->
                val start = contents.indexOf(argument, cursor)
                if (start < 0) return@forEachIndexed
                cursor = start + argument.length
                if (index in slots && argument.trim() in setOf("Container", "net.minecraft.world.Container")) {
                    val token = Regex("""(?:net\.minecraft\.world\.)?Container""").find(argument) ?: return@forEachIndexed
                    val offset = type.range.last + 1 + start
                    ranges += (offset + token.range.first)..(offset + token.range.last)
                }
            }
        }
        return ranges.distinct()
    }

    fun migrate(source: String, positions: Map<String, Set<Int>> = mapOf("Recipe" to setOf(0))): String {
        if (!source.contains("Container") || positions.isEmpty()) return source
        var result = source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val renamed = linkedSetOf<String>()
        // every `<..., X extends Container, ...>` list whose declaration also says Recipe<X>
        val edits = mutableListOf<Pair<IntRange, String>>()
        for (list in Regex("""<((?:[^<>{};]|<[^<>{};]*>)*)>""").findAll(masked)) {
            val bound = Regex("""(?<![\w$])($ID)\s+extends\s+(?:net\.minecraft\.world\.)?Container(?![\w$])""").findAll(list.groupValues[1]).toList()
            if (bound.isEmpty()) continue
            val declarationEnd = masked.indexOfAny(charArrayOf('{', ';'), list.range.last).let { if (it < 0) masked.length else it }
            val declaration = masked.substring(list.range.first, declarationEnd)
            for (parameter in bound) {
                val name = parameter.groupValues[1]
                if (applications(declaration).none { application ->
                        positions[application.name].orEmpty().any { application.arguments.getOrNull(it)?.trim() == name }
                    }) continue
                val start = list.range.first + 1 + parameter.range.first
                edits += (start..start + parameter.value.length - 1) to "$name extends RecipeInput"
                renamed += name
            }
        }
        if (edits.isEmpty()) return source
        result = edits.sortedByDescending { it.first.first }.fold(result) { text, (range, replacement) -> text.replaceRange(range, replacement) }
        val resultCode = JavaSourceText.maskCommentsAndLiterals(result)
        val scopes = JavaTypeParameterScopes(result)
        for (call in Regex("""(?<![\w$.])($ID)\.getContainerSize\(\)""").findAll(resultCode).toList().asReversed()) {
            val variable = call.groupValues[1]
            val declaration = JavaScopedDeclarations.visibleAt(resultCode, variable, call.range.first) ?: continue
            if (declaration.type in renamed && scopes.boundOf(declaration.type, declaration.offset) == "RecipeInput") {
                result = result.replaceRange(call.range, "$variable.size()")
            }
        }
        result = JavaSourceText.addImport(result, "net.minecraft.world.item.crafting.RecipeInput")
        if (!Regex("""(?<![\w$.])Container(?![\w$])""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result).replace(Regex("""(?m)^import .*$"""), ""))) {
            result = JavaSourceText.removeImport(result, "net.minecraft.world.Container")
        }
        return result
    }
}
