package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * Forge's `FluidStack` carried a mutable `CompoundTag` (`hasTag`, `getTag`, `setTag`, ...); NeoForge
 * 21.1's is a data component holder (javap of both jars). On a receiver declared as a `FluidStack`:
 * - `hasTag()` is `has(DataComponents.CUSTOM_DATA)`;
 * - `new FluidStack(X.getFluid(), n, X.getTag())` copies X's fluid and components: `X.copyWithAmount(n)`;
 * - `setTag(tag)` stores the tag as the custom data component;
 * - `getTag()` read right away (`.toString()`, `.getX(...)`, `.contains(...)`, `.isEmpty()`) is a
 *   copy of the custom data; a `getTag()` whose result could be written through is left alone,
 *   because the live-tag semantics of Forge cannot be reproduced by a copy.
 */
internal object FluidStackTags {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val CUSTOM_DATA = "net.minecraft.core.component.DataComponents.CUSTOM_DATA"
    private const val EMPTY_DATA = "net.minecraft.world.item.component.CustomData.EMPTY"
    private val readOnlyUse = Regex("""^\s*\.\s*(?:toString|getString|getInt|getBoolean|getFloat|getDouble|getLong|getCompound|getList|getUUID|contains|isEmpty|getAllKeys|get|equals)\s*\(""")

    fun migrate(source: String): String {
        if (!source.contains("FluidStack") || (!source.contains(".getTag()") && !source.contains(".hasTag()") && !source.contains(".setTag("))) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val stacks = Regex("""\bFluidStack\s+($ID)\s*(?=[=;,)])""").findAll(masked).map { it.groupValues[1] }.toSet()
        if (stacks.isEmpty()) return source
        val receiver = stacks.joinToString("|") { Regex.escape(it) }
        val edits = mutableListOf<Pair<IntRange, String>>()
        Regex("""\bnew\s+FluidStack\s*\(""").findAll(masked).forEach { constructor ->
            val close = JavaSourceText.matchingParen(masked, constructor.range.last)
            if (close < 0) return@forEach
            val args = JavaSourceText.splitTopLevelArgs(source.substring(constructor.range.last + 1, close)).map { it.trim() }
            val stack = args.firstOrNull()?.let { Regex("""^($receiver)\.getFluid\(\)$""").find(it)?.groupValues?.get(1) } ?: return@forEach
            if (args.size != 3 || args[2] != "$stack.getTag()") return@forEach
            edits += constructor.range.first..close to "$stack.copyWithAmount(${args[1]})"
        }
        Regex("""(?<![\w$.])($receiver)\.setTag\(""").findAll(masked).forEach { call ->
            val close = JavaSourceText.matchingParen(masked, call.range.last)
            if (close < 0) return@forEach
            val tag = source.substring(call.range.last + 1, close).trim()
            edits += call.range.first..close to "${call.groupValues[1]}.set($CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of($tag))"
        }
        Regex("""(?<![\w$.])($receiver)\.hasTag\(\)""").findAll(masked)
            .forEach { edits += it.range to "${it.groupValues[1]}.has($CUSTOM_DATA)" }
        Regex("""(?<![\w$.])($receiver)\.getTag\(\)""").findAll(masked)
            .filter { match -> readOnlyUse.containsMatchIn(masked.substring(match.range.last + 1)) }
            .filter { match -> edits.none { (range, _) -> match.range.first in range } }
            .forEach { edits += it.range to "${it.groupValues[1]}.getOrDefault($CUSTOM_DATA, $EMPTY_DATA).copyTag()" }
        return edits.sortedByDescending { it.first.first }.fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    }
}
