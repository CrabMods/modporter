package com.modporter.core.transforms.util

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

/**
 * Text-level helpers for Java sources shared by the migration rules: masking comments and
 * literals so patterns only see executable code, balanced-delimiter scanning, argument
 * splitting and import editing. The passes keep private copies of older variants; new rules
 * use this one.
 */
object JavaSourceText {
    /** Same length as [source], with comment and literal characters replaced by spaces (line breaks kept). */
    fun maskCommentsAndLiterals(source: String): String {
        val result = StringBuilder(source.length)
        var index = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inTextBlock = false
        var inChar = false
        var escaped = false
        while (index < source.length) {
            val ch = source[index]
            val next = source.getOrNull(index + 1)
            val nextTwo = source.getOrNull(index + 2)
            when {
                inLineComment -> {
                    if (ch == '\r' || ch == '\n') {
                        inLineComment = false
                        result.append(ch)
                    } else {
                        result.append(' ')
                    }
                }
                inBlockComment -> {
                    if (ch == '*' && next == '/') {
                        result.append("  ")
                        index++
                        inBlockComment = false
                    } else {
                        result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                    }
                }
                inTextBlock -> {
                    result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                    if (ch == '"' && next == '"' && nextTwo == '"' && !escaped) {
                        result.append("  ")
                        index += 2
                        inTextBlock = false
                    }
                    escaped = ch == '\\' && !escaped
                }
                inString -> {
                    result.append(' ')
                    if (ch == '"' && !escaped) inString = false
                    escaped = ch == '\\' && !escaped
                }
                inChar -> {
                    result.append(' ')
                    if (ch == '\'' && !escaped) inChar = false
                    escaped = ch == '\\' && !escaped
                }
                ch == '/' && next == '/' -> {
                    inLineComment = true
                    result.append("  ")
                    index++
                }
                ch == '/' && next == '*' -> {
                    inBlockComment = true
                    result.append("  ")
                    index++
                }
                ch == '"' && next == '"' && nextTwo == '"' -> {
                    inTextBlock = true
                    result.append("\"  ")
                    index += 2
                }
                ch == '"' -> {
                    inString = true
                    result.append('"')
                }
                ch == '\'' -> {
                    inChar = true
                    result.append('\'')
                }
                else -> result.append(ch)
            }
            index++
        }
        return result.toString()
    }

    /** Index of the `}` closing the `{` at [openIndex] of [masked] (a masked source), or -1. */
    fun matchingBrace(masked: String, openIndex: Int): Int = matching(masked, openIndex, '{', '}')

    /** Index of the `)` closing the `(` at [openIndex] of [masked] (a masked source), or -1. */
    fun matchingParen(masked: String, openIndex: Int): Int = matching(masked, openIndex, '(', ')')

    private fun matching(masked: String, openIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        for (index in openIndex until masked.length) {
            when (masked[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    /** Splits a Java argument list on top-level commas (parentheses, braces, brackets, angles and strings respected). */
    fun splitTopLevelArgs(args: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var angleDepth = 0
        var inString = false
        var escaped = false
        for (i in args.indices) {
            val ch = args[i]
            if (inString) {
                if (ch == '"' && !escaped) inString = false
                escaped = ch == '\\' && !escaped
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                ',' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0 && angleDepth == 0) {
                    result.add(args.substring(start, i))
                    start = i + 1
                }
            }
        }
        result.add(args.substring(start))
        return result.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun packageName(source: String): String? =
        Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(maskCommentsAndLiterals(source))?.groupValues?.get(1)

    fun addImport(source: String, importName: String): String {
        val masked = maskCommentsAndLiterals(source)
        if (Regex("""(?m)^\s*import\s+${Regex.escape(importName)}\s*;""").containsMatchIn(masked)) return source
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val importLine = "import $importName;$newline"
        val lastImport = Regex("""(?m)^import\s+[^;]+;""").findAll(masked).lastOrNull()
        if (lastImport != null) {
            val insertPos = lastImport.range.last + 1
            return source.substring(0, insertPos) + newline + importLine.trimEnd() + source.substring(insertPos)
        }
        val packageDecl = Regex("""(?m)^package\s+[^;]+;""").find(masked)
        if (packageDecl != null) {
            val insertPos = packageDecl.range.last + 1
            return source.substring(0, insertPos) + newline + newline + importLine.trimEnd() + source.substring(insertPos)
        }
        return importLine + source
    }

    fun removeImport(source: String, importName: String): String =
        Regex("""(?m)^[ \t]*import\s+(?:static\s+)?${Regex.escape(importName)}(?:\.\*)?\s*;[ \t]*\r?\n?""").replace(source, "")

    /** Every `src/<set>/java` root beside [srcDir] (`src/main/java`) except `references`, walked after it. */
    fun walkJavaSourceRoots(srcDir: Path): Stream<Path> {
        val setDir = srcDir.parent
        val src = setDir?.parent
        if (srcDir.fileName?.toString() != "java" || setDir?.fileName?.toString() != "main" || src?.fileName?.toString() != "src") {
            return Files.walk(srcDir)
        }
        val siblings = runCatching {
            Files.list(src).use { sets ->
                sets.filter { set ->
                    set != setDir && set.fileName.toString() != "references" && Files.isDirectory(set.resolve("java"))
                }.map { it.resolve("java") }.sorted().toList()
            }
        }.getOrDefault(emptyList())
        return siblings.fold(Files.walk(srcDir)) { stream, root -> Stream.concat(stream, Files.walk(root)) }
    }

    fun lineNumberAt(source: String, offset: Int): Int = source.take(offset).count { it == '\n' } + 1
}
