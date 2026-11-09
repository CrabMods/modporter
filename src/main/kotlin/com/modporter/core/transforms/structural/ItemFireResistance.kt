package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * `Item#isFireResistant()` is gone in 1.21.1; fire resistance is the `FIRE_RESISTANT` data
 * component (member table: `Item#components()`, `ItemStack#has`). A stack's item check
 * `stack.getItem().isFireResistant()`, also split across lines, asks the stack; a receiver
 * declared as an item type (resolved through its import to a class the 1.21.1 member table knows
 * under `net.minecraft.world.item`) asks the item's default components.
 */
internal object ItemFireResistance {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val COMPONENT = "net.minecraft.core.component.DataComponents.FIRE_RESISTANT"

    fun migrate(source: String): String {
        if (!source.contains("isFireResistant()")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = mutableListOf<Pair<IntRange, String>>()
        Regex("""\.\s*getItem\(\)\s*\.\s*isFireResistant\(\)""").findAll(masked).forEach { edits += it.range to ".has($COMPONENT)" }
        val itemTypes = Regex("""(?m)^\s*import\s+(net\.minecraft\.world\.item\.($ID))\s*;""").findAll(masked)
            .filter { TargetMembers.hasClass(it.groupValues[1]) }
            .map { it.groupValues[2] }
            .toSet() + "Item"
        val variables = Regex("""\b(${itemTypes.joinToString("|") { Regex.escape(it) }})\s+($ID)\s*(?=[=;,)])""").findAll(masked)
            .map { it.groupValues[2] }.toSet() +
            Regex("""\binstanceof\s+(${itemTypes.joinToString("|") { Regex.escape(it) }})\s+($ID)\b""").findAll(masked).map { it.groupValues[2] }.toSet()
        if (variables.isNotEmpty()) {
            Regex("""(?<![\w$.])(${variables.joinToString("|") { Regex.escape(it) }})\.isFireResistant\(\)""").findAll(masked)
                .filter { match -> edits.none { (range, _) -> match.range.first in range } }
                .forEach { edits += it.range to "${it.groupValues[1]}.components().has($COMPONENT)" }
        }
        return edits.sortedByDescending { it.first.first }.fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    }
}
