package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * Forge 1.20.1 named an item stack by writing the component's JSON into its `display.Name` tag
 * (`stack.getOrCreateTagElement("display").putString("Name", json)`), which is exactly what
 * `ItemStack#setHoverName` stored. 1.21.1 keeps that name in the `CUSTOM_NAME` data component.
 *
 * The write migrates only when the JSON is provably a serialized component - the 1.20.1
 * `Component.Serializer.toJson(component)` or its 1.21.1 codec form
 * `ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component).getOrThrow().toString()` -
 * so the component itself is stored without a registry-dependent round trip. Any other string
 * stays for the residual gate.
 */
internal fun StructuralRefactorPass.migrateLegacyItemDisplayNameWrites(source: String): String {
    if (!source.contains("getOrCreateTagElement")) return source
    val masked = maskJavaCommentsAndLiterals(source)
    val head = Regex("""\.\s*getOrCreateTagElement\s*\(\s*"display"\s*\)\s*\.\s*putString\s*\(""")
    val names = collectItemStackValueNames(source)
    val stackMethods = collectItemStackReturningMethodNames(source)
    val accessors = collectKnownItemStackAccessorExpressions(source)
    val typing by lazy { SourceValueTypes(this, source) }
    val edits = mutableListOf<Pair<IntRange, String>>()
    // the literal "display"/"Name" survive in the source, not in the masked text
    for (match in head.findAll(source)) {
        if (masked.substring(match.range.first, match.range.first + 1) != ".") continue
        val receiverStart = findExpressionReceiverStart(masked, match.range.first)
        if (receiverStart !in 0 until match.range.first) continue
        val stack = source.substring(receiverStart, match.range.first).trim()
        val isStack = isItemStackExpression(stack, names, stackMethods, accessors) ||
            typing.typeOf(stack, receiverStart) == "net.minecraft.world.item.ItemStack"
        if (!isStack) continue
        val open = match.range.last
        val close = JavaSourceText.matchingParen(masked, open)
        if (close < 0) continue
        val args = JavaSourceText.splitTopLevelArgs(source.substring(open + 1, close)).map { it.trim() }
        if (args.size != 2 || args[0] != "\"Name\"") continue
        val component = serializedComponent(args[1]) ?: continue
        val statementEnd = masked.indexOf(';', close)
        if (statementEnd < 0 || masked.substring(close + 1, statementEnd).isNotBlank()) continue
        // the call is the whole statement: `stack.getOrCreateTagElement("display").putString(...)`
        val before = masked.substring(0, receiverStart).trimEnd()
        if (before.isNotEmpty() && before.last() !in charArrayOf(';', '{', '}', ')')) continue
        edits += receiverStart..close to
            "$stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, $component)"
    }
    if (edits.isEmpty()) return source
    return edits.sortedByDescending { it.first.first }.fold(source) { text, (range, replacement) ->
        text.replaceRange(range, replacement)
    }
}

private fun serializedComponent(json: String): String? {
    val compact = json.trim()
    val legacy = Regex("""^(?:net\.minecraft\.network\.chat\.)?Component\s*\.\s*Serializer\s*\.\s*toJson\s*\(""").find(compact)
    if (legacy != null) {
        val close = JavaSourceText.matchingParen(compact, legacy.range.last)
        if (close != compact.length - 1) return null
        val args = JavaSourceText.splitTopLevelArgs(compact.substring(legacy.range.last + 1, close))
        return args.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
    val codec = Regex(
        """^(?:net\.minecraft\.network\.chat\.)?ComponentSerialization\s*\.\s*CODEC\s*\.\s*encodeStart\s*\("""
    ).find(compact) ?: return null
    val close = JavaSourceText.matchingParen(compact, codec.range.last)
    if (close < 0) return null
    val tail = compact.substring(close + 1).replace(Regex("""\s+"""), "")
    if (tail != ".getOrThrow().toString()") return null
    val args = JavaSourceText.splitTopLevelArgs(compact.substring(codec.range.last + 1, close)).map { it.trim() }
    if (args.size != 2 || !Regex("""^(?:com\.mojang\.serialization\.)?JsonOps\s*\.\s*INSTANCE$""").matches(args[0])) return null
    return args[1].takeIf { it.isNotEmpty() }
}
