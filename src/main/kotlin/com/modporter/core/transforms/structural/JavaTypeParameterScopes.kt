package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * Type-parameter lists of a Java source's generic types and methods, with the ranges they are
 * visible in, so a type variable is resolved where it is used: two methods each declaring their own
 * `T` (one `T extends Block`, one `T extends R`) do not share a bound.
 */
internal class JavaTypeParameterScopes(source: String) {
    private data class Scope(val range: IntRange, val bounds: Map<String, String?>)

    private val scopes: List<Scope>

    init {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val found = mutableListOf<Scope>()
        val typeHeader = Regex("""\b(?:class|interface|record)\s+[A-Za-z_$][\w$]*\s*<""")
        val methodHeader = Regex("""(?:\b(?:public|protected|private|static|final|abstract|synchronized|default)\s+|[;{}]\s*)<""")
        for (match in typeHeader.findAll(masked)) {
            val close = matchingAngle(masked, match.range.last)
            val body = masked.indexOf('{', close.coerceAtLeast(0))
            val end = if (close > 0 && body > 0) JavaSourceText.matchingBrace(masked, body) else -1
            if (end > 0) found += Scope(match.range.first..end, parameters(masked.substring(match.range.last + 1, close)))
        }
        for (match in methodHeader.findAll(masked)) {
            val close = matchingAngle(masked, match.range.last)
            if (close < 0) continue
            val open = masked.indexOf('(', close)
            if (open < 0 || !Regex("""^\s*[\w$.<>?,\[\]\s]+?\s+[A-Za-z_$][\w$]*\s*$""").matches(masked.substring(close + 1, open))) continue
            val closeParen = JavaSourceText.matchingParen(masked, open)
            if (closeParen < 0) continue
            val next = masked.substring(closeParen + 1).indexOfFirst { it == '{' || it == ';' }
            if (next < 0) continue
            val bodyStart = closeParen + 1 + next
            val end = if (masked[bodyStart] == '{') JavaSourceText.matchingBrace(masked, bodyStart) else bodyStart
            if (end > 0) found += Scope(match.range.first..end, parameters(masked.substring(match.range.last + 1, close)))
        }
        scopes = found.sortedByDescending { it.range.first }
    }

    fun isTypeVariable(name: String, offset: Int): Boolean = scopes.any { offset in it.range && name in it.bounds }

    /** The bound of type variable [name] where it is visible at [offset] (null when unbounded or not a type variable). */
    fun boundOf(name: String, offset: Int): String? =
        scopes.firstOrNull { offset in it.range && name in it.bounds }?.bounds?.get(name)

    private fun parameters(text: String): Map<String, String?> = splitTopLevel(text).associate { parameter ->
        val name = parameter.substringBefore("extends").trim().substringAfterLast(' ')
        name to parameter.substringAfter("extends", "").substringBefore('&').trim().ifEmpty { null }
    }

    private fun matchingAngle(text: String, open: Int): Int {
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
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
