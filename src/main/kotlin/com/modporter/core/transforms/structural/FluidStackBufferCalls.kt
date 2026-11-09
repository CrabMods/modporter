package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Forge's `IForgeFriendlyByteBuf#writeFluidStack/readFluidStack` wrote an empty stack as a single
 * `false` flag (javap of forge-1.20.1-47.1.33), so their 1.21.1 counterpart is NeoForge's
 * `FluidStack.OPTIONAL_STREAM_CODEC`, which needs a `RegistryFriendlyByteBuf`. A call is rewritten
 * only on a receiver declared as a byte buffer in scope; a `FriendlyByteBuf` parameter receiver is
 * declared `RegistryFriendlyByteBuf` (the registry-buffer flow then carries it to callers), any
 * other `FriendlyByteBuf` receiver is left for review.
 */
internal object FluidStackBufferCalls {
    private val call = Regex("""(?<![\w$.])([A-Za-z_$][\w$]*)\s*\.\s*(readFluidStack|writeFluidStack)\s*\(""")

    fun migrate(source: String): String {
        if (!source.contains("readFluidStack(") && !source.contains("writeFluidStack(")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = mutableMapOf<Int, Pair<IntRange, String>>()
        for (match in call.findAll(masked)) {
            val buffer = match.groupValues[1]
            val declaration = JavaScopedDeclarations.visibleAt(masked, buffer, match.range.first) ?: continue
            val open = match.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) continue
            val args = JavaSourceText.splitTopLevelArgs(source.substring(open + 1, close)).map { it.trim() }.filter { it.isNotEmpty() }
            val replacement = when {
                match.groupValues[2] == "readFluidStack" && args.isEmpty() -> "FluidStack.OPTIONAL_STREAM_CODEC.decode($buffer)"
                match.groupValues[2] == "writeFluidStack" && args.size == 1 -> "FluidStack.OPTIONAL_STREAM_CODEC.encode($buffer, ${args[0]})"
                else -> continue
            }
            if (declaration.type == "FriendlyByteBuf") {
                val afterName = masked.indexOf(buffer, declaration.offset) + buffer.length
                if (!Regex("""^\s*[,)]""").containsMatchIn(masked.substring(afterName))) continue
                edits[declaration.offset] = (declaration.offset until declaration.offset + "FriendlyByteBuf".length) to "RegistryFriendlyByteBuf"
            } else if (declaration.type != "RegistryFriendlyByteBuf") continue
            edits[match.range.first] = (match.range.first..close) to replacement
        }
        if (edits.isEmpty()) return source
        var result = edits.values.sortedBy { it.first.first }.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
        result = JavaSourceText.addImport(result, "net.neoforged.neoforge.fluids.FluidStack")
        return JavaSourceText.addImport(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    }
}
