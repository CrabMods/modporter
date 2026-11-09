package com.modporter.core.transforms.util

/**
 * Resolves which declaration of a simple name is visible at an offset of a masked Java source
 * (see [JavaSourceText.maskCommentsAndLiterals]), following Java's block scoping: a local or field
 * is visible inside the innermost block declaring it, and a method, catch, for or typed lambda
 * parameter inside the block its header opens. The innermost visible declaration wins, as in Java
 * shadowing. Names without a typed declaration in scope (untyped lambda parameters, inherited
 * fields) resolve to nothing, so callers can refuse instead of guessing.
 */
object JavaScopedDeclarations {
    data class Declaration(val type: String, val initializer: String?, val offset: Int)

    private const val ID = """[A-Za-z_$][\w$]*"""

    /** The declaration of [name] visible at [at] of [masked], or null when none is proven. */
    fun visibleAt(masked: String, name: String, at: Int): Declaration? {
        val pattern = Regex("""\b([A-Z][\w$]*)(?:<[^;=(){}]*>)?\s+${Regex.escape(name)}\s*(?=[=;,):])""")
        return pattern.findAll(masked)
            .mapNotNull { match -> scope(masked, match.range.first)?.takeIf { at in it }?.let { match to it } }
            .maxByOrNull { (_, scope) -> scope.first }
            ?.let { (match, _) -> Declaration(match.groupValues[1], initializer(masked, match.range.last + 1), match.range.first) }
    }

    /** Whether [name] is assigned anywhere in [masked] other than by its declarations' initializers. */
    fun isReassigned(masked: String, name: String): Boolean {
        val escaped = Regex.escape(name)
        val assignments = Regex("""(?<![\w$.])$escaped\s*(?:[-+*/%&|^]|<<|>>>?)?=(?!=)""").findAll(masked)
        return assignments.any { !isDeclaration(masked, it.range.first) } ||
            Regex("""(?:\+\+|--)\s*$escaped(?![\w$])|(?<![\w$.])$escaped\s*(?:\+\+|--)""").containsMatchIn(masked)
    }

    /** Whether the name at [offset] follows a type, i.e. is being declared rather than assigned. */
    private fun isDeclaration(masked: String, offset: Int): Boolean {
        val before = masked.substring(maxOf(0, offset - 120), offset)
        val type = Regex("""([\w$]+|[>\]])\s+$""").find(before) ?: return false
        return type.groupValues[1] !in statementKeywords
    }

    private val statementKeywords = setOf("return", "else", "do", "case", "throw", "yield", "assert")

    private fun initializer(masked: String, afterName: Int): String? {
        var index = afterName
        while (index < masked.length && masked[index].isWhitespace()) index++
        if (index >= masked.length || masked[index] != '=') return null
        val end = masked.indexOf(';', index)
        return if (end < 0) null else masked.substring(index + 1, end).trim()
    }

    /** The offsets where a declaration starting at [declaration] is visible, or null when its scope is not plain. */
    private fun scope(masked: String, declaration: Int): IntRange? {
        val openParen = unclosedBefore(masked, declaration, '(', ')')
        if (openParen < 0) {
            val openBrace = unclosedBefore(masked, declaration, '{', '}')
            val close = if (openBrace < 0) -1 else JavaSourceText.matchingBrace(masked, openBrace)
            if (close < 0) return null
            // members are visible throughout their type's body, locals from their declaration on
            val header = masked.substring(0, openBrace).takeLastWhile { it != ';' && it != '{' && it != '}' }
            return if (Regex("""\b(?:class|interface|enum|record)\b""").containsMatchIn(header)) openBrace until close else declaration until close
        }
        // a parameter: visible in the block the header opens
        val closeParen = JavaSourceText.matchingParen(masked, openParen)
        if (closeParen < 0) return null
        val rest = masked.substring(closeParen + 1)
        val body = Regex("""^\s*(?:->\s*|throws\s+[\w$.,\s]+?)?\{""").find(rest) ?: return null
        val openBody = closeParen + 1 + body.range.last
        val closeBody = JavaSourceText.matchingBrace(masked, openBody)
        return if (closeBody < 0) null else declaration until closeBody
    }

    /** The nearest [open] before [offset] not closed before it, stopping at statement or block boundaries for parentheses. */
    private fun unclosedBefore(masked: String, offset: Int, open: Char, close: Char): Int {
        var depth = 0
        for (index in offset - 1 downTo 0) {
            val char = masked[index]
            when {
                char == close -> depth++
                char == open -> if (depth == 0) return index else depth--
                open == '(' && depth == 0 && (char == ';' || char == '{' || char == '}') -> return -1
            }
        }
        return -1
    }
}
