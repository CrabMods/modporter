package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Forge 1.20.1's `stack.getOrCreateTag()` (ItemStack and FluidStack) created the stack's tag and
 * returned it live, so writes to it changed the stack. On 1.21.1 that state is the `CUSTOM_DATA`
 * component, read as `getOrDefault(CUSTOM_DATA, CustomData.EMPTY).copyTag()`: a copy, whose writes
 * are lost unless the stack is given the tag back (`set(CUSTOM_DATA, CustomData.of(tag))`, on both
 * stack types). On a receiver declared `ItemStack` or `FluidStack`:
 * - a statement writing through the copy (`copy.putX(..)`, `copy.remove(..)`, or a call handed the
 *   copy) becomes a block that names the copy, runs the statement on it and sets it back;
 * - a `CompoundTag t = copy;` local the code writes (or hands to a call) is set back after the last
 *   statement of its block that uses it, when nothing between the two leaves the block early and
 *   neither the local nor the stack is reassigned.
 * Setting the tag back even when it is unchanged keeps getOrCreateTag's effect of giving the stack a tag.
 */
internal object CopiedCustomDataWrites {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val COPY = """((?:this\s*\.\s*)?$ID)\s*\.\s*getOrDefault\(\s*(?:net\.minecraft\.core\.component\.)?DataComponents\.CUSTOM_DATA\s*,\s*(?:net\.minecraft\.world\.item\.component\.)?CustomData\.EMPTY\s*\)\s*\.\s*copyTag\(\s*\)"""
    private val copy = Regex("""(?<![\w$.])$COPY""")
    private val alias = Regex("""(?<![\w$.])(?:final\s+)?(?:net\.minecraft\.nbt\.)?CompoundTag\s+($ID)\s*=\s*$COPY\s*;""")
    private val stackTypes = setOf("ItemStack", "FluidStack")
    private const val SET_BACK = "net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of"
    private const val NAME = "modporterCustomData"

    fun migrate(source: String): String {
        if (!source.contains("copyTag()") || !source.contains("CUSTOM_DATA")) return source
        return statements(aliases(source))
    }

    private fun stackReceiver(masked: String, receiver: String, at: Int): Boolean {
        val name = receiver.replace(Regex("""\s+"""), "").removePrefix("this.")
        val type = JavaScopedDeclarations.visibleAt(masked, name, at)?.type ?: return false
        return type.substringBefore('<').substringAfterLast('.') in stackTypes
    }

    private fun aliases(source: String): String {
        val insertions = mutableListOf<Pair<Int, String>>()
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        for (declaration in alias.findAll(masked).toList().asReversed()) {
            val tag = declaration.groupValues[1]
            val receiver = declaration.groupValues[2]
            if (!stackReceiver(masked, receiver, declaration.range.first)) continue
            val blockOpen = enclosingBraceOpen(masked, declaration.range.first) ?: continue
            val blockClose = JavaSourceText.matchingBrace(masked, blockOpen)
            if (blockClose < 0) continue
            val tagUse = Regex("""(?<![\w$.])${Regex.escape(tag)}(?![\w$])""")
            val last = topLevelStatements(masked, declaration.range.last + 1, blockClose)
                .lastOrNull { (start, end) -> tagUse.containsMatchIn(masked.substring(start, end + 1)) } ?: continue
            val span = masked.substring(declaration.range.last + 1, last.second + 1)
            val stack = Regex.escape(receiver.replace(Regex("""\s+"""), "").removePrefix("this."))
            val e = Regex.escape(tag)
            val written = Regex("""(?<![\w$.])$e\s*\.\s*(?:put\w*|remove|merge|getCompound|getList)\s*\(|[(,]\s*$e\s*[,)]""").containsMatchIn(span)
            if (!written ||
                Regex("""\b(?:return|throw|break|continue|yield)\b""").containsMatchIn(span) ||
                Regex("""(?<![\w$.])$e\s*=(?!=)|=\s*$e\s*;""").containsMatchIn(span) ||
                Regex("""(?<![\w$])(?:this\s*\.\s*)?$stack\s*=(?!=)""").containsMatchIn(span) ||
                Regex("""(?<![\w$])(?:this\s*\.\s*)?$stack\s*\.\s*set\s*\(\s*(?:net\.minecraft\.core\.component\.)?DataComponents\.CUSTOM_DATA""")
                    .containsMatchIn(masked.substring(declaration.range.last + 1, blockClose))
            ) continue
            val indent = lineIndent(source, declaration.range.first)
            insertions += (last.second + 1) to "\n$indent${receiver.trim()}.set($SET_BACK($tag));"
        }
        return insertions.sortedByDescending { it.first }.fold(source) { text, (at, insertion) -> text.substring(0, at) + insertion + text.substring(at) }
    }

    private fun statements(source: String): String {
        var result = source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        var counter = 0
        val rewritten = mutableListOf<IntRange>()
        for (match in copy.findAll(masked).toList().asReversed()) {
            val receiver = match.groupValues[1]
            if (!stackReceiver(masked, receiver, match.range.first)) continue
            val after = masked.substring(match.range.last + 1)
            val statement: IntRange = Regex("""^\s*\.\s*(?:put\w*|remove|merge)\s*\(""").find(after)?.let { call ->
                // copy.putX(...);
                val open = match.range.last + call.range.last + 1
                val close = JavaSourceText.matchingParen(masked, open)
                val end = statementEnd(masked, close) ?: return@let null
                if (!statementStart(masked, match.range.first)) return@let null
                match.range.first..end
            } ?: run {
                // call(..., copy, ...);
                if (!Regex("""[(,]\s*$""").containsMatchIn(masked.substring(0, match.range.first)) || !Regex("""^\s*[,)]""").containsMatchIn(after)) return@run null
                val open = enclosingParenOpen(masked, match.range.first) ?: return@run null
                val callee = Regex("""((?:$ID\s*\.\s*)*$ID)\s*$""").find(masked.substring(0, open)) ?: return@run null
                if (callee.groupValues[1] in setOf("if", "while", "for", "switch", "synchronized", "return")) return@run null
                val end = statementEnd(masked, JavaSourceText.matchingParen(masked, open)) ?: return@run null
                if (!statementStart(masked, callee.range.first)) return@run null
                callee.range.first..end
            } ?: continue
            if (rewritten.any { it.first <= statement.last && statement.first <= it.last }) continue
            rewritten += statement
            var name: String
            do {
                counter++
                name = if (counter == 1) NAME else "$NAME$counter"
            } while (Regex("""\b$name\b""").containsMatchIn(source))
            val indent = lineIndent(source, statement.first)
            val body = result.substring(statement.first, match.range.first) + name + result.substring(match.range.last + 1, statement.last + 1)
            val block = "{\n$indent    net.minecraft.nbt.CompoundTag $name = ${result.substring(match.range.first, match.range.last + 1)};\n" +
                "$indent    $body\n" +
                "$indent    ${receiver.trim()}.set($SET_BACK($name));\n$indent}"
            result = result.substring(0, statement.first) + block + result.substring(statement.last + 1)
        }
        return result
    }

    /** The `;` closing a statement whose expression ends at [close], or null. */
    private fun statementEnd(masked: String, close: Int): Int? {
        if (close < 0) return null
        val semicolon = Regex("""^\s*;""").find(masked.substring(close + 1)) ?: return null
        return close + 1 + semicolon.range.last
    }

    /** Whether a statement may begin at [start]: after `;`, a brace, `else`, or a control header's `)`. */
    private fun statementStart(masked: String, start: Int): Boolean {
        var index = start - 1
        while (index >= 0 && masked[index].isWhitespace()) index--
        if (index < 0) return false
        return when (masked[index]) {
            ';', '{', '}' -> true
            ')' -> {
                val open = matchingOpenParen(masked, index) ?: return false
                Regex("""\b(?:if|while|for)\s*$""").containsMatchIn(masked.substring(0, open))
            }
            else -> Regex("""\belse$""").containsMatchIn(masked.substring(0, index + 1))
        }
    }

    private fun matchingOpenParen(masked: String, close: Int): Int? {
        var depth = 0
        for (index in close downTo 0) {
            when (masked[index]) {
                ')' -> depth++
                '(' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun enclosingParenOpen(masked: String, at: Int): Int? {
        var depth = 0
        for (index in at - 1 downTo 0) {
            when (masked[index]) {
                ')' -> depth++
                '(' -> if (depth == 0) return index else depth--
                ';', '{', '}' -> if (depth == 0) return null
            }
        }
        return null
    }

    private fun enclosingBraceOpen(masked: String, at: Int): Int? {
        var depth = 0
        for (index in at - 1 downTo 0) {
            when (masked[index]) {
                '}' -> depth++
                '{' -> if (depth == 0) return index else depth--
            }
        }
        return null
    }

    /** The top-level statements of a block between [from] and [to] (exclusive), as index ranges. */
    private fun topLevelStatements(masked: String, from: Int, to: Int): List<Pair<Int, Int>> {
        val statements = mutableListOf<Pair<Int, Int>>()
        var start = from
        var paren = 0
        var brace = 0
        var index = from
        while (index < to) {
            when (masked[index]) {
                '(' -> paren++
                ')' -> paren--
                '{' -> brace++
                '}' -> {
                    brace--
                    if (brace == 0 && paren == 0) {
                        val next = masked.substring(index + 1, to).trimStart()
                        val continues = next.startsWith(";") || next.startsWith(")") || next.startsWith(",") || next.startsWith(".") ||
                            Regex("""^(?:else|catch|finally|while)\b""").containsMatchIn(next)
                        if (!continues) {
                            statements += start to index
                            start = index + 1
                        }
                    }
                }
                ';' -> if (paren == 0 && brace == 0) {
                    statements += start to index
                    start = index + 1
                }
            }
            index++
        }
        return statements.filter { (s, e) -> masked.substring(s, e + 1).isNotBlank() }
    }

    private fun lineIndent(source: String, at: Int): String {
        val lineStart = source.lastIndexOf('\n', at - 1) + 1
        return source.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
    }
}
