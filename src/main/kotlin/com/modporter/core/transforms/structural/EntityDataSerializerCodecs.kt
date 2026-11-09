package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * 1.21.1's `EntityDataSerializer<T>` reads and writes through one abstract
 * `StreamCodec<? super RegistryFriendlyByteBuf, T> codec()` (class table) where 1.20.1 had abstract
 * `write(FriendlyByteBuf, T)` and `read(FriendlyByteBuf)`. An implementation keeps its two methods,
 * which no longer override anything, and hands them to the codec, so the bytes stay the same.
 */
internal object EntityDataSerializerCodecs {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val implementation = Regex("""\bclass\s+($ID)\s*(?:<[^{]*?>)?[^{;]*\bimplements\s+[^{;]*\bEntityDataSerializer\s*<\s*([^{;]+?)\s*>\s*\{""")

    fun migrate(source: String): String {
        if (!source.contains("EntityDataSerializer")) return source
        var result = source
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val match = implementation.findAll(masked).firstOrNull { candidate ->
                val close = JavaSourceText.matchingBrace(masked, candidate.range.last)
                close > 0 && needsCodec(masked.substring(candidate.range.last, close))
            } ?: break
            val open = match.range.last
            val close = JavaSourceText.matchingBrace(masked, open)
            val valueType = result.substring(match.groups[2]!!.range).trim()
            val body = result.substring(open + 1, close)
                .replace(Regex("""@Override\s+(public\s+void\s+write\s*\()"""), "$1")
                .replace(Regex("""@Override\s+(public\s+[^(;{]+?\s+read\s*\()"""), "$1")
            val indent = Regex("""(?m)^([ \t]+)\S""").find(body)?.groupValues?.get(1) ?: "    "
            val codec = "\n$indent@Override\n" +
                "${indent}public net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, $valueType> codec() {\n" +
                "$indent${indent}return net.minecraft.network.codec.StreamCodec.of(this::write, this::read);\n" +
                "$indent}\n"
            result = result.substring(0, open + 1) + codec + body + result.substring(close)
        }
        return result
    }

    private fun needsCodec(body: String): Boolean =
        !Regex("""\bcodec\s*\(\s*\)""").containsMatchIn(body) &&
            Regex("""\bvoid\s+write\s*\(\s*(?:final\s+)?(?:$ID\.)*(?:Registry)?FriendlyByteBuf\s+$ID\s*,""").containsMatchIn(body) &&
            Regex("""\bread\s*\(\s*(?:final\s+)?(?:$ID\.)*(?:Registry)?FriendlyByteBuf\s+$ID\s*\)""").containsMatchIn(body)
}
