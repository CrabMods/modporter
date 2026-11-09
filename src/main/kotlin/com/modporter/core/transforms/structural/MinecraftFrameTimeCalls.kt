package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * In 1.20.1 only `net.minecraft.client.Minecraft` declared `getFrameTime()` and
 * `getDeltaFrameTime()` (srg-mojang-1.20.1: m_91296_, m_91297_); 1.21.1 has neither on any type
 * (target members) and reads the partial tick from `Minecraft#getTimer()`. So a no-argument call of
 * either name is a Minecraft call whatever its receiver is spelled as (an inherited
 * `Screen#minecraft` field, say), unless the project declares a method of that name itself.
 */
internal object MinecraftFrameTimeCalls {
    private val names = listOf("getFrameTime", "getDeltaFrameTime")
    private val keywords = setOf("return", "new", "throw", "else", "case", "yield")

    /** The names of [names] some project source declares, whose calls may be the project's own. */
    fun projectDeclared(javaFiles: List<Path>): Set<String> = javaFiles.flatMapTo(linkedSetOf()) { file ->
        val source = runCatching { file.readText() }.getOrDefault("")
        if (names.none { source.contains(it) }) return@flatMapTo emptyList()
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        names.filter { name ->
            // a declaration: a return type right before the name, a body or `;` after the parameters
            Regex("""([\w$>\]])\s+${Regex.escape(name)}\s*\([^)]*\)\s*(?:throws\s+[\w.,\s]+)?[{;]""").findAll(masked).any { match ->
                val word = Regex("""([\w$]+)\s*$""").find(masked.substring(0, match.range.first + 1))?.groupValues?.get(1)
                word !in keywords
            }
        }
    }

    fun migrate(source: String, projectDeclared: Set<String>): String {
        val targets = names.filter { it !in projectDeclared && source.contains("$it()") }
        if (targets.isEmpty()) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val call = Regex("""\.\s*(?:${targets.joinToString("|")})\s*\(\s*\)""")
        val edits = call.findAll(masked).map { it.range to ".getTimer().getGameTimeDeltaPartialTick(false)" }.toList()
        return edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    }
}
