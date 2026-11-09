package com.modporter.core.transforms.structural

/**
 * Forge 1.20.1's `net.minecraftforge.common.util.NonNull*` functional interfaces have no NeoForge
 * 1.21.1 counterpart (the class table has none of them), and they only narrowed the nullness
 * contract of the `java.util.function` interfaces they mirror, so a source that imported one
 * imports the `java.util.function` interface and uses its name instead.
 */
internal object ForgeNonNullFunctionals {
    private val replacements = mapOf(
        "NonNullSupplier" to "Supplier",
        "NonNullConsumer" to "Consumer",
        "NonNullPredicate" to "Predicate",
        "NonNullFunction" to "Function",
        "NonNullUnaryOperator" to "UnaryOperator",
        "NonNullBiConsumer" to "BiConsumer",
        "NonNullBiFunction" to "BiFunction",
    )

    private val importPattern = Regex(
        """(?m)^import\s+net\.(?:minecraftforge|neoforged\.neoforge)\.common\.util\.(NonNull(?:Supplier|Consumer|Predicate|Function|UnaryOperator|BiConsumer|BiFunction))\s*;[ \t]*\r?\n?"""
    )

    fun migrate(source: String): String {
        val imported = importPattern.findAll(source).map { it.groupValues[1] }.toSet()
        if (imported.isEmpty()) return source
        // A source that already imports another Supplier (Guava's, say) keeps that import and
        // spells the java.util.function interface out, or every Supplier becomes ambiguous.
        fun clashes(target: String): Boolean =
            Regex("""(?m)^import\s+(?!java\.util\.function\.)[\w.]+\.${Regex.escape(target)}\s*;""").containsMatchIn(source)
        var result = importPattern.replace(source) { match ->
            val target = replacements.getValue(match.groupValues[1])
            val importLine = "import java.util.function.$target;"
            if (source.contains(importLine) || clashes(target)) "" else importLine + match.value.substring(match.value.indexOf(';') + 1)
        }
        for (name in imported) {
            val target = replacements.getValue(name)
            val spelled = if (clashes(target)) "java.util.function.$target" else target
            result = result.replace(Regex("""(?<![\w$.])${Regex.escape(name)}(?![\w$])"""), spelled)
        }
        return result
    }
}
