package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Forge 1.20.1 criterion instances extend `AbstractCriterionTriggerInstance(ResourceLocation,
 * ContextAwarePredicate)` and are read by `CriterionTrigger#createInstance(JsonObject, ...)`;
 * 1.21.1 instances are `SimpleCriterionTrigger.SimpleInstance`s (`player()`, `validate`) read by
 * `CriterionTrigger#codec()`, and listeners expose their instance as `trigger()` (member table).
 *
 * Only the shape whose 1.21 form is fully determined is migrated: an instance whose state is the
 * trigger id and the `ContextAwarePredicate.ANY` player predicate, i.e. no fields of its own and no
 * `serializeToJson`. It becomes a `SimpleInstance` with an optional `player` predicate and the
 * codec vanilla uses for it (`EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player")`); an
 * abstract base of that shape implements `SimpleInstance`; `new Instance(id)` becomes
 * `new Instance()`; a trigger's `createInstance` that only builds such an instance becomes
 * `codec()`. Instances carrying state are reported, not guessed.
 */
internal object LegacyCriterionInstances {
    const val RULE_ID = "struct-legacy-criterion-instances"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val LEGACY_BASE = "AbstractCriterionTriggerInstance"

    private data class InstanceClass(val file: Path, val outer: String, val name: String, val isAbstract: Boolean)

    fun migrate(projectDir: Path, dryRun: Boolean, errors: MutableList<String>): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }.toMutableMap()
        val legacy = sources.filterValues { it.contains(LEGACY_BASE) || it.contains("Instance") && it.contains("CriterionTrigger") }
        if (legacy.values.none { it.contains(LEGACY_BASE) }) return emptyList()

        // 1. instance classes of the migratable shape, bases first
        val bases = mutableMapOf<String, InstanceClass>()
        val concrete = mutableListOf<InstanceClass>()
        repeat(3) {
            for ((file, source) in legacy) {
                val masked = JavaSourceText.maskCommentsAndLiterals(source)
                val outer = Regex("""\bclass\s+($ID)""").find(masked)?.groupValues?.get(1) ?: continue
                Regex("""(?m)^[ \t]+(?:public\s+|protected\s+)?(abstract\s+)?static\s+class\s+($ID)\s+extends\s+((?:$ID\.)?$ID)\s*\{""").findAll(masked).forEach { match ->
                    val name = match.groupValues[2]
                    val parent = match.groupValues[3]
                    if (parent != LEGACY_BASE && parent !in bases) return@forEach
                    val key = "$outer.$name"
                    if (key in bases || concrete.any { "${it.outer}.${it.name}" == key }) return@forEach
                    val close = JavaSourceText.matchingBrace(masked, match.range.last)
                    if (close < 0) return@forEach
                    val body = masked.substring(match.range.last + 1, close)
                    val reason = unsupported(body, name)
                    if (reason != null) {
                        errors.add("Cannot migrate criterion instance $key: $reason")
                        return@forEach
                    }
                    val instance = InstanceClass(file, outer, name, match.groupValues[1].isNotBlank())
                    if (instance.isAbstract) bases[key] = instance else concrete += instance
                }
            }
        }
        if (bases.isEmpty() && concrete.isEmpty()) return emptyList()

        // 2. rewrite declarations
        for (instance in bases.values + concrete) {
            val source = sources.getValue(instance.file)
            sources[instance.file] = if (instance.isAbstract) abstractBase(source, instance) else concreteInstance(source, instance, bases.keys)
        }
        // 3. constructions, trigger codecs, listener accessors and the gone getId override
        val concreteNames = concrete.map { it.name }.toSet() + concrete.map { "${it.outer}.${it.name}" }
        for ((file, source) in sources.toMap()) {
            var result = source
            if (concreteNames.any { result.contains("new $it(") }) {
                for (name in concreteNames) {
                    result = replaceCode(result, Regex("""\bnew\s+${Regex.escape(name)}\s*\(\s*[^()]*(?:\([^()]*\))?[^()]*\)""")) { "new $name()" }
                }
            }
            result = triggerCodecs(result, concrete)
            result = replaceCode(result, Regex("""(?<![\w$])($ID)\s*\.\s*getTriggerInstance\(\)""")) { match ->
                val receiver = match.groupValues[1]
                if (Regex("""\bListener\s*<[^>]*>\s+${Regex.escape(receiver)}\b""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result))) "$receiver.trigger()" else match.value
            }
            if (Regex("""\bimplements\s+CriterionTrigger\s*<""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result))) {
                result = Regex("""@Override\s+(public\s+ResourceLocation\s+getId\s*\(\s*\))""").replace(result) { it.groupValues[1] }
            }
            sources[file] = result
        }

        val changes = mutableListOf<Change>()
        for ((file, updated) in sources) {
            val original = runCatching { file.readText() }.getOrDefault("")
            if (updated == original) continue
            if (!dryRun) file.writeText(updated)
            changes += Change(file, 0, "Forge criterion instances of the id-plus-ANY-player shape become 1.21 SimpleInstances read by codec()",
                "extends AbstractCriterionTriggerInstance / createInstance(JsonObject, ...)", "implements SimpleCriterionTrigger.SimpleInstance / codec()", Confidence.HIGH, RULE_ID)
        }
        return changes
    }

    /** Why [body] is not the id-plus-ANY-player shape, or null when it is. */
    private fun unsupported(body: String, name: String): String? {
        if (Regex("""(?m)^[ \t]+(?:(?:private|protected|public|final)\s+)+(?!static\b)(?!class\b)[\w<>\[\].?, ]+\s+$ID\s*[;=]""").containsMatchIn(body)) return "it declares fields"
        if (body.contains("serializeToJson")) return "it serializes its own JSON"
        Regex("""\b${Regex.escape(name)}\s*\(([^)]*)\)\s*\{([^{}]*)\}""").findAll(body).forEach { ctor ->
            val superCall = Regex("""super\s*\(([^;]*)\)\s*;""").find(ctor.groupValues[2])?.groupValues?.get(1) ?: return "its constructor does not call super"
            val args = superCall.split(',').map { it.trim() }
            if (args.size == 2 && args[1] != "ContextAwarePredicate.ANY" && ctor.groupValues[1].split(',').size != 2) return "its player predicate is not ContextAwarePredicate.ANY"
        }
        return null
    }

    private fun abstractBase(source: String, instance: InstanceClass): String {
        var result = replaceCode(source, Regex("""\b(abstract\s+static\s+class\s+${Regex.escape(instance.name)})\s+extends\s+$LEGACY_BASE\b""")) {
            "${it.groupValues[1]} implements SimpleCriterionTrigger.SimpleInstance"
        }
        // its (ResourceLocation, ContextAwarePredicate) constructor only forwarded to the legacy base
        result = Regex("""(?m)^[ \t]*(?:public\s+|protected\s+)?${Regex.escape(instance.name)}\s*\(\s*ResourceLocation\s+$ID\s*,\s*ContextAwarePredicate\s+$ID\s*\)\s*\{\s*super\s*\([^;]*\)\s*;\s*\}\s*\r?\n""").replace(result, "")
        return withImports(result)
    }

    private fun concreteInstance(source: String, instance: InstanceClass, bases: Set<String>): String {
        val name = Regex.escape(instance.name)
        var result = replaceCode(source, Regex("""\b(static\s+class\s+$name)\s+extends\s+$LEGACY_BASE\b""")) {
            "${it.groupValues[1]} implements SimpleCriterionTrigger.SimpleInstance"
        }
        val masked = JavaSourceText.maskCommentsAndLiterals(result)
        val header = Regex("""\bstatic\s+class\s+$name\b[^{]*\{""").find(masked) ?: return result
        val close = JavaSourceText.matchingBrace(masked, header.range.last)
        val ctor = Regex("""(?m)^([ \t]*)(?:public\s+|protected\s+)?$name\s*\(\s*(?:final\s+)?ResourceLocation\s+$ID\s*\)\s*\{[^{}]*\}[ \t]*\r?\n""")
            .find(masked.substring(header.range.last + 1, close)) ?: return result
        val indent = ctor.groupValues[1]
        val newline = if (result.contains("\r\n")) "\r\n" else "\n"
        val members = listOf(
            "${indent}public static final Codec<${instance.name}> CODEC = RecordCodecBuilder.create(instance -> instance.group(",
            "$indent\tEntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf(\"player\").forGetter(${instance.name}::player)",
            "$indent).apply(instance, ${instance.name}::new));",
            "",
            "${indent}private final Optional<ContextAwarePredicate> player;",
            "",
            "${indent}public ${instance.name}() {",
            "$indent\tthis(Optional.empty());",
            "$indent}",
            "",
            "${indent}public ${instance.name}(Optional<ContextAwarePredicate> player) {",
            "$indent\tthis.player = player;",
            "$indent}",
            "",
            "$indent@Override",
            "${indent}public Optional<ContextAwarePredicate> player() {",
            "$indent\treturn player;",
            "$indent}",
            ""
        ).joinToString(newline)
        val start = header.range.last + 1 + ctor.range.first
        val end = header.range.last + 1 + ctor.range.last + 1
        result = result.substring(0, start) + members + result.substring(end)
        return withImports(result)
    }

    /** `public I createInstance(JsonObject json, DeserializationContext context) { return new I(...); }` becomes `codec()`. */
    private fun triggerCodecs(source: String, concrete: List<InstanceClass>): String {
        if (!source.contains("createInstance")) return source
        var result = source
        for (instance in concrete) {
            val type = """(?:(?:${Regex.escape(instance.outer)}\.)?${Regex.escape(instance.name)})"""
            val method = Regex("""(?m)^([ \t]*)(?:@Override\s*\r?\n[ \t]*)?public\s+($type)\s+createInstance\s*\(\s*JsonObject\s+$ID\s*,\s*DeserializationContext\s+$ID\s*\)\s*\{\s*return\s+new\s+$type\s*\(\s*\)\s*;\s*\}""")
            result = method.replace(result) { match ->
                val indent = match.groupValues[1]
                val newline = if (result.contains("\r\n")) "\r\n" else "\n"
                "$indent@Override$newline${indent}public Codec<${match.groupValues[2]}> codec() {$newline$indent\treturn ${match.groupValues[2]}.CODEC;$newline$indent}"
            }
        }
        if (result != source) {
            result = JavaSourceText.addImport(result, "com.mojang.serialization.Codec")
            result = removeUnusedImport(result, "com.google.gson.JsonObject")
            result = removeUnusedImport(result, "net.minecraft.advancements.critereon.DeserializationContext")
        }
        return result
    }

    private fun withImports(source: String): String {
        var result = source
        for (import in listOf(
            "com.mojang.serialization.Codec",
            "com.mojang.serialization.codecs.RecordCodecBuilder",
            "java.util.Optional",
            "net.minecraft.advancements.critereon.ContextAwarePredicate",
            "net.minecraft.advancements.critereon.EntityPredicate",
            "net.minecraft.advancements.critereon.SimpleCriterionTrigger"
        )) {
            val simple = import.substringAfterLast('.')
            if (Regex("""\b$simple\b""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result))) result = JavaSourceText.addImport(result, import)
        }
        return removeUnusedImport(result, "net.minecraft.advancements.critereon.$LEGACY_BASE")
    }

    private fun removeUnusedImport(source: String, import: String): String {
        val line = Regex("""(?m)^[ \t]*import\s+${Regex.escape(import)}\s*;[ \t]*\r?\n""")
        val without = line.replace(source, "")
        val simple = import.substringAfterLast('.')
        return if (Regex("""\b${Regex.escape(simple)}\b""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(without))) source else without
    }

    private fun replaceCode(source: String, pattern: Regex, replacement: (MatchResult) -> String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = pattern.findAll(masked).map { it.range to replacement(it) }.toList()
        return edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    }
}
