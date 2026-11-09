package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 1.21.1's `RecipeManager.getRecipeFor(...)` returns `Optional<RecipeHolder<T>>` (class table
 * signature) where 1.20.1 returned the recipe. A project predicate over recipes
 * (`Predicate<? super Recipe<?>>` field) filtering that result tests the holder's recipe:
 * `.filter(P)` becomes `.filter(holder -> P.test(holder.value()))`.
 */
internal object RecipeHolderPredicateFilters {
    const val RULE_ID = "struct-recipe-holder-predicate-filter"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val recipeMembers = setOf(
        "matches",
        "assemble",
        "canCraftInDimensions",
        "getResultItem",
        "getRemainingItems",
        "getIngredients",
        "isSpecial",
        "showNotification",
        "getGroup",
        "getToastSymbol",
        "getSerializer",
        "getType",
        "isIncomplete"
    )

    private data class MethodKey(val name: String, val arity: Int)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val originalSources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val sources = originalSources.toMutableMap()
        val recipeTypes = collectRecipeTypes(sources.values)
        val recipeParameters = collectRecipeParameterPositions(sources.values, recipeTypes)
        // `Owner.FIELD` and `FIELD` of recipe predicates the project declares
        val predicates = sources.flatMap { (file, source) ->
            val owner = file.fileName.toString().removeSuffix(".java")
            Regex("""\bPredicate\s*<\s*(?:\?\s+super\s+)?Recipe\s*<\s*\?\s*>\s*>\s+($ID)\s*=""")
                .findAll(JavaSourceText.maskCommentsAndLiterals(source))
                .flatMap { sequenceOf(it.groupValues[1], "$owner.${it.groupValues[1]}") }
                .toList()
        }.toSet()
        val changes = mutableListOf<Change>()
        for ((file, source) in sources.toMap()) {
            var result = source
            result = preserveOptionalRecipeApi(result, recipeTypes)
            result = unwrapRecipeHolderLambdas(result, recipeParameters)
            var cursor = 0
            while (true) {
                val masked = JavaSourceText.maskCommentsAndLiterals(result)
                val filter = Regex("""\.\s*filter\s*\(\s*((?:$ID\.)?$ID)\s*\)""").find(masked, cursor) ?: break
                cursor = filter.range.last + 1
                if (filter.groupValues[1] !in predicates) continue
                // the filtered value comes from getRecipeFor in the same statement
                val statementStart = maxOf(masked.lastIndexOf(';', filter.range.first), masked.lastIndexOf('{', filter.range.first), masked.lastIndexOf('}', filter.range.first)) + 1
                val chain = masked.substring(statementStart, filter.range.first)
                if (!Regex("""\.\s*getRecipeFor\s*\(""").containsMatchIn(chain)) continue
                val replacement = ".filter(holder -> ${filter.groupValues[1]}.test(holder.value()))"
                result = result.substring(0, filter.range.first) + replacement + result.substring(filter.range.last + 1)
                cursor = filter.range.first + replacement.length
            }
            if (result == source) continue
            sources[file] = result
            changes += Change(file, 1, "Recipe-holder boundaries preserve project recipe APIs and pass recipes to recipe consumers",
                "getRecipeFor recipe value", "RecipeHolder.value()", Confidence.HIGH, RULE_ID)
        }
        if (!dryRun) {
            for ((file, source) in sources) {
                if (source != originalSources[file]) file.writeText(source)
            }
        }
        return changes
    }

    private fun collectRecipeTypes(sources: Collection<String>): Set<String> {
        val direct = linkedSetOf(
            "Recipe",
            "CraftingRecipe",
            "ShapedRecipe",
            "ShapelessRecipe",
            "AbstractCookingRecipe"
        )
        val parents = mutableMapOf<String, String>()
        for (source in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            Regex("""\b(?:class|interface|record)\s+($ID)\b[^{;]*?\b(?:extends|implements)\s+((?:$ID\.)*$ID)""")
                .findAll(masked)
                .forEach { parents[it.groupValues[1]] = it.groupValues[2].substringAfterLast('.') }
        }
        var changed: Boolean
        do {
            changed = false
            for ((type, parent) in parents) {
                if (parent in direct && direct.add(type)) changed = true
            }
        } while (changed)
        return direct
    }

    /**
     * Method parameters that source declarations prove consume a recipe. A method name/arity is
     * accepted only when every declaration with that shape agrees on the recipe positions.
     */
    private fun collectRecipeParameterPositions(
        sources: Collection<String>,
        recipeTypes: Set<String>
    ): Map<MethodKey, Set<Int>> {
        val declarations = mutableMapOf<MethodKey, MutableList<Set<Int>>>()
        val header = Regex(
            """(?m)^[ \t]*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:<[^;\r\n{]+>\s+)?[\w.$<>, ?@\[\]]+\s+($ID)\s*\(([^)]*)\)\s*(?:throws[^{]+)?\{"""
        )
        for (source in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            for (method in header.findAll(masked)) {
                val parameters = JavaSourceText.splitTopLevelArgs(method.groupValues[2])
                val positions = parameters.mapIndexedNotNull { index, parameter ->
                    val withoutAnnotations = parameter.replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "").trim()
                    val type = withoutAnnotations.substringBeforeLast(' ').removePrefix("final ").trim()
                    val simple = type.substringBefore('<').substringAfterLast('.')
                    if (simple in recipeTypes) index else null
                }.toSet()
                declarations.getOrPut(MethodKey(method.groupValues[1], parameters.size)) { mutableListOf() }.add(positions)
            }
        }
        return declarations.mapNotNull { (key, positions) ->
            val first = positions.firstOrNull() ?: return@mapNotNull null
            if (first.isNotEmpty() && positions.all { it == first }) key to first else null
        }.toMap()
    }

    /** A project Optional<Recipe> helper keeps its public API while the manager now returns holders. */
    private fun preserveOptionalRecipeApi(source: String, recipeTypes: Set<String>): String {
        if (!source.contains("Optional<") || !source.contains("getRecipeFor(")) return source
        var result = source
        var cursor = 0
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val method = Regex(
                """(?m)^[ \t]*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:<([^;\r\n{]+)>\s+)?Optional\s*<\s*($ID)\s*>\s+$ID\s*\([^)]*\)\s*(?:throws[^{]+)?\{"""
            ).find(masked, cursor) ?: break
            val close = JavaSourceText.matchingBrace(masked, method.range.last)
            if (close < 0) break
            cursor = close + 1
            val recipeType = method.groupValues[2]
            val genericBounds = method.groupValues[1]
            val isRecipe = recipeType in recipeTypes ||
                Regex("""\b${Regex.escape(recipeType)}\s+extends\s+(?:[\w.]+\.)?Recipe\s*<""").containsMatchIn(genericBounds)
            if (!isRecipe) continue
            val body = result.substring(method.range.last + 1, close)
            val returned = Regex("""(?s)\breturn\s+([^;]*?\.getRecipeFor\s*\([^;]+?\)(?:\s*\.[^;]+?)?)\s*;""").find(body) ?: continue
            if (returned.groupValues[1].contains("RecipeHolder::value")) continue
            val replacement = "return ${returned.groupValues[1].trim()}.map(RecipeHolder::value);"
            val start = method.range.last + 1 + returned.range.first
            val end = method.range.last + 1 + returned.range.last
            result = result.substring(0, start) + replacement + result.substring(end + 1)
            result = JavaSourceText.addImport(result, "net.minecraft.world.item.crafting.RecipeHolder")
            cursor = start + replacement.length
        }
        return result
    }

    /**
     * Lambdas on a direct RecipeManager.getRecipeFor chain receive RecipeHolder<T>. Add value()
     * only for Recipe interface members or arguments whose project method declaration consumes a
     * recipe at that exact arity and position.
     */
    private fun unwrapRecipeHolderLambdas(source: String, recipeParameters: Map<MethodKey, Set<Int>>): String {
        if (!source.contains("getRecipeFor(") || !source.contains("->")) return source
        var result = source
        var searchFrom = 0
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val lookup = Regex("""\.\s*getRecipeFor\s*\(""").find(masked, searchFrom) ?: break
            val statementEnd = masked.indexOf(';', lookup.range.last).let { if (it < 0) masked.length else it }
            var chain = result.substring(lookup.range.first, statementEnd)
            var chainChanged = false
            for (lambda in Regex("""\.\s*(?:filter|map|flatMap)\s*\(\s*($ID)\s*->""").findAll(JavaSourceText.maskCommentsAndLiterals(chain)).toList().asReversed()) {
                val parameter = lambda.groupValues[1]
                val lambdaStart = lambda.range.last + 1
                val openParen = chain.indexOf('(', lambda.range.first)
                val closeParen = if (openParen < 0) -1 else JavaSourceText.matchingParen(JavaSourceText.maskCommentsAndLiterals(chain), openParen)
                if (closeParen < lambdaStart) continue
                var body = chain.substring(lambdaStart, closeParen)
                val member = Regex(
                    """\b${Regex.escape(parameter)}\s*\.\s*(${recipeMembers.joinToString("|") { Regex.escape(it) }})\s*\("""
                )
                val memberMatch = member.find(JavaSourceText.maskCommentsAndLiterals(body))
                if (memberMatch != null) {
                    val receiverEnd = memberMatch.range.first + memberMatch.value.indexOf('.')
                    body = body.substring(0, receiverEnd) + ".value()" + body.substring(receiverEnd)
                    chainChanged = true
                }
                body = unwrapRecipeArguments(body, parameter, recipeParameters).also {
                    if (it != body) chainChanged = true
                }
                chain = chain.substring(0, lambdaStart) + body + chain.substring(closeParen)
            }
            if (chainChanged) {
                result = result.substring(0, lookup.range.first) + chain + result.substring(statementEnd)
                searchFrom = lookup.range.first + chain.length
            } else {
                searchFrom = statementEnd + 1
            }
        }
        return result
    }

    private fun unwrapRecipeArguments(
        expression: String,
        parameter: String,
        recipeParameters: Map<MethodKey, Set<Int>>
    ): String {
        var result = expression
        var cursor = 0
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val call = Regex("""(?<![.\w$])(?:(?:$ID)\s*\.\s*)*($ID)\s*\(""").find(masked, cursor) ?: break
            val open = call.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) break
            val argumentsText = result.substring(open + 1, close)
            val arguments = JavaSourceText.splitTopLevelArgs(argumentsText)
            val positions = recipeParameters[MethodKey(call.groupValues[1], arguments.size)].orEmpty()
            if (positions.isEmpty()) {
                cursor = close + 1
                continue
            }
            val replacements = positions.filter { arguments.getOrNull(it)?.trim() == parameter }.toSet()
            if (replacements.isEmpty()) {
                cursor = close + 1
                continue
            }
            val rebuilt = arguments.mapIndexed { index, argument ->
                if (index in replacements) "$parameter.value()" else argument
            }.joinToString(", ")
            result = result.substring(0, open + 1) + rebuilt + result.substring(close)
            cursor = open + 1 + rebuilt.length
        }
        return result
    }
}
