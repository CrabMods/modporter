package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * Forge's `IForgeRegistry#tags()` handed out an `ITagManager` whose `getTag(key)` is an `ITag`
 * (empty when the tag is unbound) streaming its values (javap of forge-1.20.1-47.1.33). NeoForge has
 * no tag manager; vanilla's `Registry#getTag(TagKey)` is an `Optional<HolderSet.Named<T>>` (1.21.1
 * member table), so `getTag(key).stream()` becomes
 * `registry.getTag(key).stream().flatMap(HolderSet.Named::stream).map(Holder::value)`, the same
 * values and the same empty stream for an unbound tag. The other reads of Forge's
 * `ForgeRegistryTag` are views of the same contents (forge-1.20.1-47.1.33 sources): `forEach`,
 * `isEmpty()` and `size()` read them through that stream or the named set's size, and
 * `isKnownTagName(key)` (a tag the manager holds, bound) is the registry having the tag. An `ITag`
 * local read only as a collection (`isEmpty`, `size`, `stream`, `contains`, `forEach`, a for-each
 * loop) holds the values as a list. This covers the chained form on a `BuiltInRegistries` constant
 * and an `ITagManager` local initialised from one (optionally through `Objects.requireNonNull`)
 * that is used only these ways; anything else is left for review.
 */
internal object ForgeRegistryTagStreams {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val REGISTRY = """(?:net\.minecraft\.core\.registries\.)?BuiltInRegistries\.[A-Z][A-Z0-9_]*"""
    private const val STREAM = ".stream().flatMap(net.minecraft.core.HolderSet.Named::stream).map(net.minecraft.core.Holder::value)"
    private const val SIZE = ".map(net.minecraft.core.HolderSet.Named::size).orElse(0)"
    private val collectionReads = setOf("isEmpty", "size", "stream", "contains", "forEach")

    fun migrate(source: String): String {
        if (!source.contains("tags()")) return source
        var result = source
        // registry.tags().getTag(key).stream()
        result = replaceTagReads(result, Regex("""(?<![\w$.])($REGISTRY)\s*\.\s*tags\(\)\s*\.\s*(getTag|isKnownTagName)\(""")) { it.groupValues[1] }
        // ITagManager<T> tags = [Objects.requireNonNull(]registry.tags()[, "..."]); ... tags.getTag(key).stream()
        val masked = JavaSourceText.maskCommentsAndLiterals(result)
        val declaration = Regex(
            """(?m)^[ \t]*(?:final\s+)?(?:[\w.]+\.)?ITagManager\s*<[^;=]*>\s+($ID)\s*=\s*(?:(?:java\.util\.)?Objects\.requireNonNull\(\s*)?($REGISTRY)\s*\.\s*tags\(\)\s*(?:,[^;]*\))?\s*;[ \t]*\r?\n"""
        )
        for (match in declaration.findAll(masked).toList().asReversed()) {
            val registry = match.groupValues[2]
            // the local's scope: the rest of the block declaring it
            val current = JavaSourceText.maskCommentsAndLiterals(result)
            val scope = match.range.last + 1 until enclosingBlockEnd(current, match.range.last + 1)
            val name = Regex.escape(match.groupValues[1])
            val uses = Regex("""(?<![\w$.])$name(?![\w$])""").findAll(current.substring(scope)).count()
            val read = Regex("""(?<![\w$.])$name\s*\.\s*(getTag|isKnownTagName)\(""")
            val rewritten = replaceTagReads(result.substring(scope), read) { registry }
            val remaining = Regex("""(?<![\w$.])$name(?![\w$])""").findAll(JavaSourceText.maskCommentsAndLiterals(rewritten)).count()
            // every use in scope must have been rewritten
            if (rewritten == result.substring(scope) || remaining != 0 || uses == 0) continue
            result = result.substring(0, match.range.first) + rewritten + result.substring(scope.last + 1)
        }
        if (result == source) return result
        for (type in listOf("ITagManager", "ITag")) {
            if (!Regex("""\b$type\b""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(JavaSourceText.removeImport(result, "net.neoforged.neoforge.registries.tags.$type")))) {
                result = JavaSourceText.removeImport(result, "net.neoforged.neoforge.registries.tags.$type")
                result = JavaSourceText.removeImport(result, "net.minecraftforge.registries.tags.$type")
            }
        }
        return result
    }

    /** Rewrites each tag read starting where [pattern] matches (its last group is the method) on the registry. */
    private fun replaceTagReads(source: String, pattern: Regex, registry: (MatchResult) -> String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = pattern.findAll(masked).mapNotNull { match ->
            val open = match.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) return@mapNotNull null
            val key = source.substring(open + 1, close).trim()
            val tag = "${registry(match)}.getTag($key)"
            if (match.groupValues.last() == "isKnownTagName") return@mapNotNull (match.range.first..close) to "$tag.isPresent()"
            val after = masked.substring(close + 1)
            Regex("""^\s*\.\s*stream\(\s*\)""").find(after)?.let { return@mapNotNull (match.range.first..close + it.range.last + 1) to "$tag$STREAM" }
            Regex("""^\s*\.\s*isEmpty\(\s*\)""").find(after)?.let { return@mapNotNull (match.range.first..close + it.range.last + 1) to "($tag$SIZE == 0)" }
            Regex("""^\s*\.\s*size\(\s*\)""").find(after)?.let { return@mapNotNull (match.range.first..close + it.range.last + 1) to "$tag$SIZE" }
            if (Regex("""^\s*\.\s*forEach\(""").containsMatchIn(after)) return@mapNotNull (match.range.first..close) to "$tag$STREAM"
            localTagList(source, masked, match.range.first, close)?.let { (range, declaration) -> return@mapNotNull range to "$declaration$tag$STREAM.toList()" }
            null
        }.toList()
        return edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    }

    /**
     * `ITag<T> name = <read>;` whose every later use of `name` is a collection read: the range from the
     * declared type to the read's end, and the `java.util.List<T> name = ` that replaces its head.
     */
    private fun localTagList(source: String, masked: String, start: Int, close: Int): Pair<IntRange, String>? {
        if (!Regex("""^\s*;""").containsMatchIn(masked.substring(close + 1))) return null
        val head = Regex("""(?:[\w.]+\.)?ITag\s*<([^;=<>]+)>\s+($ID)\s*=\s*$""").find(masked.substring(0, start)) ?: return null
        val name = head.groupValues[2]
        val scopeEnd = enclosingBlockEnd(masked, start)
        val later = masked.substring(close + 1, scopeEnd)
        val uses = Regex("""(?<![\w$.])${Regex.escape(name)}(?![\w$])""").findAll(later).toList()
        val readsOnly = uses.all { use ->
            val call = Regex("""^\s*\.\s*(\w+)\s*\(""").find(later.substring(use.range.last + 1))
            call?.groupValues?.get(1) in collectionReads ||
                Regex(""":\s*$""").containsMatchIn(later.substring(0, use.range.first)) &&
                Regex("""^\s*\)""").containsMatchIn(later.substring(use.range.last + 1))
        }
        if (uses.isEmpty() || !readsOnly) return null
        return (head.range.first..close) to "java.util.List<${source.substring(head.groups[1]!!.range).trim()}> $name = "
    }

    private fun enclosingBlockEnd(masked: String, at: Int): Int {
        var depth = 0
        for (index in at until masked.length) {
            when (masked[index]) {
                '{' -> depth++
                '}' -> if (depth-- == 0) return index
            }
        }
        return masked.length
    }
}
