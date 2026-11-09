package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * Forge's `IExtensionPoint.DisplayTest` told the client which side a mod must be installed on. FML
 * 4 and NeoForge 21.1 have no display test at all (neither jar mentions one): in 1.21 a mod is only
 * required on the other side through the network payloads it registers as required, so a
 * server-only DisplayTest states what is already the default. Its registration is removed:
 * - `ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, ...);`
 * - the reflective form some mods used to stay compatible across Forge versions, a `try` whose only
 *   statement invokes `registerExtensionPoint` with `IExtensionPoint.DisplayTest.class` and whose
 *   catch clauses only rethrow;
 * then the imports nothing else uses (`IExtensionPoint`, Forge's `NetworkConstants`, and the
 * reflection exceptions and `Supplier` the reflective call needed).
 */
internal object DisplayTestRegistrations {
    private val direct = Regex(
        """(?m)^[ \t]*(?:[\w.]+\.)?ModLoadingContext\.get\(\)\.registerExtensionPoint\(\s*(?:[\w.]+\.)?IExtensionPoint\.DisplayTest\.class\b"""
    )
    // matched on comment- and literal-masked code; the method name is a string checked separately
    private val reflective = Regex(
        """(?m)^[ \t]*try\s*\{\s*(?:[\w.]+\.)?ModLoadingContext\.class\.getDeclaredMethod\("""
    )
    private val unusedImports = listOf(
        "net.neoforged.fml.IExtensionPoint", "net.minecraftforge.fml.IExtensionPoint",
        "net.neoforged.neoforge.network.NetworkConstants", "net.minecraftforge.network.NetworkConstants",
        "java.lang.reflect.InvocationTargetException", "java.util.function.Supplier"
    )

    fun migrate(source: String): String {
        if (!source.contains("DisplayTest")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val removals = mutableListOf<IntRange>()
        for (match in direct.findAll(source)) {
            if (masked.substring(match.range).trim().isEmpty()) continue
            val open = source.indexOf('(', match.range.first + match.value.indexOf("registerExtensionPoint"))
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0 || masked.getOrNull(close + 1) != ';') continue
            removals += lineStart(source, match.range.first)..lineEnd(source, close + 1)
        }
        for (match in reflective.findAll(masked)) {
            if (!source.substring(match.range.last + 1).trimStart().startsWith("\"registerExtensionPoint\"")) continue
            val tryOpen = masked.indexOf('{', match.range.first)
            val tryClose = JavaSourceText.matchingBrace(masked, tryOpen)
            if (tryClose < 0) continue
            val body = masked.substring(tryOpen + 1, tryClose)
            val statements = body.split(';').filter { it.isNotBlank() }
            if (statements.size != 1 || !source.substring(tryOpen + 1, tryClose).contains("IExtensionPoint.DisplayTest.class")) continue
            var end = tryClose
            while (true) {
                val catch = Regex("""^\s*catch\s*\([^)]*\)\s*\{""").find(masked.substring(end + 1)) ?: break
                val catchOpen = end + 1 + catch.range.last
                val catchClose = JavaSourceText.matchingBrace(masked, catchOpen)
                if (catchClose < 0 || !Regex("""^\s*throw\s+new\s+[\w.]+\s*\(\s*\w+\s*\)\s*;\s*$""").matches(masked.substring(catchOpen + 1, catchClose))) {
                    end = -1
                    break
                }
                end = catchClose
            }
            if (end < 0 || end == tryClose) continue
            removals += lineStart(source, match.range.first)..lineEnd(source, end + 1)
        }
        if (removals.isEmpty()) return source
        var result = removals.sortedByDescending { it.first }.fold(source) { text, range -> text.removeRange(range) }
        for (import in unusedImports) {
            val simpleName = import.substringAfterLast('.')
            val withoutImport = JavaSourceText.removeImport(result, import)
            if (withoutImport != result && !Regex("""(?<![\w$.])$simpleName(?![\w$])""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(withoutImport))) {
                result = withoutImport
            }
        }
        return result
    }

    private fun lineStart(source: String, index: Int): Int = source.lastIndexOf('\n', index - 1) + 1

    /** The index after the line break ending the line that holds [index] (exclusive end for removeRange). */
    private fun lineEnd(source: String, index: Int): Int {
        val newline = source.indexOf('\n', index)
        return if (newline < 0) source.length - 1 else newline
    }
}
