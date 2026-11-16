package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * NeoForge 1.21 moved `setCancellationResult` from `PlayerInteractEvent` down to the four
 * subevents that can be cancelled with a result. A handler written against the base event
 * dispatches on the concrete subevent instead of calling a method the base type no longer has.
 *
 * The same holds for `setCanceled`: the base event no longer is a cancellable event; only the
 * subevents that implement `ICancellableEvent` are. Forge 1.20.1 cancelled exactly those (its base
 * setCanceled refused a non-cancelable event), so a base-typed call cancels through that interface.
 */
internal object PlayerInteractCancellation {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val CANCELLABLE = "net.neoforged.bus.api.ICancellableEvent"
    private val subevents = listOf("EntityInteractSpecific", "EntityInteract", "RightClickBlock", "RightClickItem")

    fun migrate(source: String): String {
        if (!source.contains("PlayerInteractEvent") ||
            !source.contains(".setCancellationResult(") && !source.contains(".setCanceled(")
        ) {
            return source
        }
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        // values declared with the base type, as parameters or locals
        val baseEvents = Regex("""(?<![\w$.])PlayerInteractEvent\s+($ID)\s*[,);=]""").findAll(masked).map { it.groupValues[1] }.toSet()
        if (baseEvents.isEmpty()) return source
        val names = baseEvents.joinToString("|") { Regex.escape(it) }
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val results = Regex("""(?m)^([ \t]*)($names)\.setCancellationResult\(([^;]+)\);[ \t]*$""").findAll(masked).map { match ->
            val indent = match.groupValues[1]
            val event = match.groupValues[2]
            val result = source.substring(match.groups[3]!!.range).trim()
            val local = if (event == "e") "sub" else "e"
            val cases = subevents.joinToString(newline) { "$indent    case PlayerInteractEvent.$it $local -> $local.setCancellationResult($result);" }
            match.range to "${indent}switch ($event) {$newline$cases$newline$indent    default -> {$newline$indent    }$newline$indent}"
        }
        val cancels = Regex("""(?m)^([ \t]*)($names)\.setCanceled\(([^;]+)\);[ \t]*$""").findAll(masked).map { match ->
            val indent = match.groupValues[1]
            val event = match.groupValues[2]
            val value = source.substring(match.groups[3]!!.range).trim()
            var local = "modporterCancellable"
            while (Regex("""\b${Regex.escape(local)}\b""").containsMatchIn(masked)) local += "_"
            match.range to "${indent}if ($event instanceof $CANCELLABLE $local) $local.setCanceled($value);"
        }
        val edits = (results + cancels).sortedBy { it.first.first }.toList()
        if (edits.isEmpty()) return source
        return edits.asReversed().fold(source) { text, (range, replacement) -> text.replaceRange(range, replacement) }
    }
}
