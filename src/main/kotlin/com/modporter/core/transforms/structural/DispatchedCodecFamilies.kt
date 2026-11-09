package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * DataFixerUpper 7 (Minecraft 1.20.5+) declares `Codec.dispatch(type, codec)` with a
 * `Function<K, MapCodec<? extends V>>`; DFU 6 took plain codecs. A project's dispatched codec
 * family therefore moves to `MapCodec` as a whole:
 *
 * - the carriers the dispatch reads its codecs from: the key type itself for
 *   `dispatch(Base::codec, Function.identity())` (every `Codec<? extends Base>` slot: the registry
 *   and its key, the register helpers, `Base.codec()` and its overrides), or the key's codec field
 *   and constructor parameter for `dispatch(v -> v.type, type -> type.codec)`;
 * - the codecs flowing into them (`return CODEC;` in an override, `super(Impl.CODEC)`): a
 *   `RecordCodecBuilder.create` builds its `mapCodec`, `Codec.unit` is `MapCodec.unit`, and any other
 *   codec is read under `"value"`, which is where DFU 6's `KeyDispatchCodec` put a value whose codec
 *   was not a map codec, so the encoded form stays the same.
 */
internal object DispatchedCodecFamilies {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val identityDispatch = Regex("""\.dispatch\(\s*($ID)::($ID)\s*,\s*(?:Function\.identity\(\)|($ID)\s*->\s*\3)\s*\)""")
    private val fieldDispatch = Regex("""\b($ID)\s*\.\s*$ID\s*\.\s*dispatch\(\s*($ID)\s*->\s*\2\.$ID\s*,\s*($ID)\s*->\s*\3\.($ID)\s*\)""")
    private val typeDeclaration = Regex("""\b(?:class|interface|enum|record)\s+($ID)[^{;]*\{""")

    private class Source(val path: Path, initialText: String) {
        private var maskedCache: String? = null
        var text: String = initialText
            set(value) {
                field = value
                maskedCache = null
            }
        val masked: String get() = maskedCache ?: JavaSourceText.maskCommentsAndLiterals(text).also { maskedCache = it }
        var changed = false
    }

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.toFile().exists()) return emptyList()
        val sources = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
            .mapNotNull { path -> runCatching { Source(path, path.readText()) }.getOrNull() }
        if (sources.none { it.text.contains(".dispatch(") }) return emptyList()
        val changes = mutableListOf<Change>()

        for (source in sources.toList()) {
            for (match in identityDispatch.findAll(source.masked).toList()) {
                val base = match.groupValues[1]
                if (sources.none { declaresType(it.masked, base) }) continue
                // every slot of the registry element type is a carrier
                val carrier = Regex("""(?<![\w$.])(?:com\.mojang\.serialization\.)?Codec(\s*<\s*\?\s+extends\s+(?:$ID\.)*${Regex.escape(base)}\s*>)""")
                for (file in sources) {
                    val updated = carrier.replace(file.text) { "MapCodec${it.groupValues[1]}" }
                    if (updated != file.text) {
                        file.text = updated
                        file.changed = true
                    }
                }
                // codecs returned by the dispatched method's overrides
                val method = match.groupValues[2]
                val returning = Regex("""\bMapCodec\s*<\s*\?\s+extends\s+(?:$ID\.)*${Regex.escape(base)}\s*>\s+${Regex.escape(method)}\s*\(\s*\)\s*\{""")
                for (file in sources) {
                    // each conversion moves the text after it, so look again until nothing converts
                    // (a converted field is no longer a Codec)
                    do {
                        val masked = file.masked
                        val converted = returning.findAll(masked).any { declaration ->
                            val close = JavaSourceText.matchingBrace(masked, declaration.range.last)
                            val body = if (close < 0) "" else masked.substring(declaration.range.last, close)
                            val returned = Regex("""^\s*\{\s*return\s+((?:$ID\.)?$ID)\s*;\s*$""").find(body)?.groupValues?.get(1)
                            returned != null && convertCodecField(sources, file, returned, declaration.range.first)
                        }
                    } while (converted)
                }
                changes += Change(source.path, JavaSourceText.lineNumberAt(source.text, match.range.first),
                    "Dispatched codec family of $base moves to MapCodec (DFU 7 dispatch takes map codecs)",
                    "Codec<? extends $base>", "MapCodec<? extends $base>", Confidence.HIGH, "struct-dispatched-codec-family")
            }
            for (match in fieldDispatch.findAll(source.masked).toList()) {
                val keyType = match.groupValues[1]
                val field = match.groupValues[4]
                val keyFile = sources.firstOrNull { declaresType(it.masked, keyType) } ?: continue
                if (!migrateCodecField(keyFile, keyType, field)) continue
                // codecs handed to the key type's constructor by its subclasses; a subclass passing its
                // own constructor parameter through is a carrier too, and so on down its subclasses
                val carriers = ArrayDeque(listOf(keyType))
                val visited = mutableSetOf<String>()
                while (carriers.isNotEmpty()) {
                    val carrier = carriers.removeFirst()
                    if (!visited.add(carrier)) continue
                    for (file in sources) {
                        val subclasses = Regex("""\b(?:class|record)\s+($ID)\s*(?:<[^{]*?>)?\s+extends\s+${Regex.escape(carrier)}\b""")
                            .findAll(file.masked).map { it.groupValues[1] }.toList()
                        if (subclasses.isEmpty()) continue
                        do {
                            val converted = Regex("""\bsuper\s*\(\s*((?:$ID\.)?$ID)\s*\)\s*;""").findAll(file.masked).any { call ->
                                val argument = call.groupValues[1]
                                if (!argument.contains('.') && convertCodecParameter(file, argument, call.range.first)) {
                                    enclosingTypeName(file.masked, call.range.first)?.let(carriers::add)
                                    true
                                } else {
                                    convertCodecField(sources, file, argument, call.range.first)
                                }
                            }
                        } while (converted)
                    }
                }
                changes += Change(source.path, JavaSourceText.lineNumberAt(source.text, match.range.first),
                    "Dispatched codec family keyed by $keyType moves its $field to MapCodec (DFU 7 dispatch takes map codecs)",
                    "Codec<? extends T> $field", "MapCodec<? extends T> $field", Confidence.HIGH, "struct-dispatched-codec-family")
            }
        }

        for (file in sources.filter { it.changed }) {
            if (!file.text.contains("import com.mojang.serialization.MapCodec;") && Regex("""(?<![\w$.])MapCodec\b""").containsMatchIn(file.masked)) {
                file.text = JavaSourceText.addImport(file.text, "com.mojang.serialization.MapCodec")
            }
            if (!dryRun) file.path.writeText(file.text)
        }
        return changes
    }

    private fun declaresType(masked: String, name: String): Boolean =
        Regex("""\b(?:class|interface|enum|record)\s+${Regex.escape(name)}\b""").containsMatchIn(masked)

    /** The key type's `Codec<? extends T> field` and the constructor parameter assigned to it become MapCodec. */
    private fun migrateCodecField(file: Source, keyType: String, field: String): Boolean {
        val declaration = Regex("""(?<![\w$.])(?:com\.mojang\.serialization\.)?Codec(\s*<\s*\?\s+extends\s+$ID\s*>\s+${Regex.escape(field)}\s*[;,)])""")
        val masked = file.masked
        val typeMatch = Regex("""\b(?:class|interface|record)\s+${Regex.escape(keyType)}\b[^{;]*\{""").find(masked) ?: return false
        val close = JavaSourceText.matchingBrace(masked, typeMatch.range.last)
        if (close < 0) return false
        val body = file.text.substring(typeMatch.range.last, close)
        val updated = declaration.replace(body) { "MapCodec${it.groupValues[1]}" }
        if (updated == body) return false
        file.text = file.text.substring(0, typeMatch.range.last) + updated + file.text.substring(close)
        file.changed = true
        return true
    }

    /**
     * The codec field [reference] (`CODEC` in the type enclosing [at], or `Impl.CODEC`) becomes a
     * MapCodec built the way DFU 6 dispatched it; false when it is not a Codec field there.
     */
    private fun convertCodecField(sources: List<Source>, from: Source, reference: String, at: Int): Boolean {
        val owner = reference.substringBefore('.', "")
        val name = reference.substringAfter('.')
        val (file, range) = if (owner.isEmpty()) {
            from to enclosingType(from.masked, at)
        } else {
            val file = sources.firstOrNull { declaresType(it.masked, owner) } ?: return false
            file to typeBody(file.masked, owner)
        }
        range ?: return false
        val masked = file.masked
        val field = Regex("""(?<![\w$.])(?:com\.mojang\.serialization\.)?Codec\s*<\s*([^;=]+?)\s*>\s+${Regex.escape(name)}\s*=\s*""")
            .findAll(masked).filter { it.range.first in range }.minByOrNull { it.range.first } ?: return false
        val end = masked.indexOf(';', field.range.last)
        if (end < 0) return false
        val initializer = file.text.substring(field.range.last + 1, end).trim()
        val converted = when {
            Regex("""^RecordCodecBuilder\s*\.\s*(?:<[^>]*>\s*)?create\s*\(""").containsMatchIn(initializer) ->
                initializer.replaceFirst(Regex("""create\s*\("""), "mapCodec(")
            initializer.startsWith("Codec.unit(") -> "Map$initializer"
            Regex("""^[A-Za-z_$][\w$.]*(?:<[^>]*>)?[\s\S]*\)$|^[A-Za-z_$][\w$.]*$""").matches(initializer) &&
                !initializer.contains('?') -> "$initializer.fieldOf(\"value\")"
            else -> "($initializer).fieldOf(\"value\")"
        }
        val declarationStart = field.range.first
        val typeText = file.text.substring(declarationStart, field.range.last + 1).replaceFirst(Regex("""(?:com\.mojang\.serialization\.)?Codec"""), "MapCodec")
        file.text = file.text.substring(0, declarationStart) + typeText + converted + file.text.substring(end)
        file.changed = true
        return true
    }

    /** The `Codec<...> [name]` parameter of the constructor whose body holds [at] becomes MapCodec. */
    private fun convertCodecParameter(file: Source, name: String, at: Int): Boolean {
        val masked = file.masked
        val type = enclosingTypeName(masked, at) ?: return false
        val constructor = Regex("""\b${Regex.escape(type)}\s*\(([^()]*)\)\s*\{""").findAll(masked).firstOrNull { header ->
            val close = JavaSourceText.matchingBrace(masked, header.range.last)
            close > 0 && at in header.range.last..close
        } ?: return false
        val parameters = constructor.groups[1]!!.range
        val parameter = Regex("""(?<![\w$.])(?:com\.mojang\.serialization\.)?Codec(\s*<[^<>]*(?:<[^<>]*>[^<>]*)*>\s+${Regex.escape(name)})\b""")
            .find(masked.substring(parameters)) ?: return false
        val start = parameters.first + parameter.range.first
        file.text = file.text.substring(0, start) + "MapCodec" + parameter.groupValues[1] + file.text.substring(parameters.first + parameter.range.last + 1)
        file.changed = true
        return true
    }

    private fun enclosingTypeName(masked: String, at: Int): String? =
        typeDeclaration.findAll(masked)
            .mapNotNull { match ->
                val close = JavaSourceText.matchingBrace(masked, match.range.last)
                if (close > 0 && at in match.range.last..close) match to (close - match.range.last) else null
            }
            .minByOrNull { it.second }
            ?.first?.groupValues?.get(1)

    private fun typeBody(masked: String, name: String): IntRange? {
        val match = Regex("""\b(?:class|interface|enum|record)\s+${Regex.escape(name)}\b[^{;]*\{""").find(masked) ?: return null
        val close = JavaSourceText.matchingBrace(masked, match.range.last)
        return if (close < 0) null else match.range.last..close
    }

    /** The body of the innermost type declaration around [at]. */
    private fun enclosingType(masked: String, at: Int): IntRange? =
        typeDeclaration.findAll(masked)
            .mapNotNull { match ->
                val close = JavaSourceText.matchingBrace(masked, match.range.last)
                if (close > 0 && at in match.range.last..close) match.range.last..close else null
            }
            .minByOrNull { it.last - it.first }
}
