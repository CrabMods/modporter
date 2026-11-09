package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * NeoForge 1.21 moved `setCancellationResult` from `PlayerInteractEvent` down to the four
 * subevents that can be cancelled with a result. A handler written against the base event
 * dispatches on the concrete subevent, the way Create 6 does, instead of calling a method the
 * base type no longer has.
 */
internal object PlayerInteractCancellation {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val subevents = listOf("EntityInteractSpecific", "EntityInteract", "RightClickBlock", "RightClickItem")

    fun migrate(source: String): String {
        if (!source.contains(".setCancellationResult(") || !source.contains("PlayerInteractEvent")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        // values declared with the base type, as parameters or locals
        val baseEvents = Regex("""(?<![\w$.])PlayerInteractEvent\s+($ID)\s*[,);=]""").findAll(masked).map { it.groupValues[1] }.toSet()
        if (baseEvents.isEmpty()) return source
        val statement = Regex("""(?m)^([ \t]*)(${baseEvents.joinToString("|") { Regex.escape(it) }})\.setCancellationResult\(([^;]+)\);[ \t]*$""")
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val edits = statement.findAll(masked).map { match ->
            val indent = match.groupValues[1]
            val event = match.groupValues[2]
            val result = source.substring(match.groups[3]!!.range).trim()
            val local = if (event == "e") "sub" else "e"
            val cases = subevents.joinToString(newline) { "$indent    case PlayerInteractEvent.$it $local -> $local.setCancellationResult($result);" }
            match.range to "${indent}switch ($event) {$newline$cases$newline$indent    default -> {$newline$indent    }$newline$indent}"
        }.toList()
        if (edits.isEmpty()) return source
        return edits.asReversed().fold(source) { text, (range, replacement) -> text.replaceRange(range, replacement) }
    }
}
