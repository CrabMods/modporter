package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * NeoForge 1.21.1 freezes the built-in registries before common setup runs. A project method
 * that writes a built-in registry (`Registry.register(BuiltInRegistries.TRIGGER_TYPES, ...)`, the
 * 1.21 form of `CriteriaTriggers.register`) and that 1.20.1 code called from common-setup work
 * now writes a frozen registry. Its calls move into a `RegisterEvent` listener for that registry,
 * registered next to the setup listener on the same mod bus, in their original order.
 *
 * Evidence: the written registry comes from the method bodies (transitively, through static
 * project calls); the registry key is the `Registries` field whose generic signature is the
 * `ResourceKey` of that `BuiltInRegistries` field's registry type (1.21.1 class table). Setup
 * methods are those with an `FMLCommonSetupEvent` parameter, registered as `bus.addListener(Owner::method)`.
 */
internal object LegacySetupRegistryWrites {
    const val RULE_ID = "struct-setup-built-in-registry-writes"

    private const val BUILT_IN = "net.minecraft.core.registries.BuiltInRegistries"
    private const val KEYS = "net.minecraft.core.registries.Registries"
    private val write = Regex("""\bRegistry\s*\.\s*register(?:ForHolder)?\s*\(\s*(?:net\.minecraft\.core\.registries\.)?BuiltInRegistries\s*\.\s*([A-Z_]+)\s*,""")
    private val staticCall = Regex("""(?<![\w$.])([A-Z][\w$]*)\s*\.\s*([a-z][\w$]*)\s*\(\s*\)""")

    private data class Method(val owner: String, val name: String, val body: String)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = GradleModLayout.modModule(projectDir).resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { it.readText() }
        if (sources.values.none { it.contains("BuiltInRegistries") }) return emptyList()

        // static no-argument project methods, by simple owner name and method name
        val methods = linkedMapOf<Pair<String, String>, Method>()
        for ((_, source) in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val owner = Regex("""\b(?:class|interface|enum)\s+([A-Za-z_$][\w$]*)""").find(masked)?.groupValues?.get(1) ?: continue
            Regex("""\bstatic\s+(?:final\s+)?void\s+([a-z][\w$]*)\s*\(\s*\)\s*\{""").findAll(masked).forEach { header ->
                val close = JavaSourceText.matchingBrace(masked, header.range.last)
                if (close > header.range.last) {
                    methods[owner to header.groupValues[1]] = Method(owner, header.groupValues[1], masked.substring(header.range.last + 1, close))
                }
            }
        }
        // the built-in registries each method writes, through static project calls
        val writes = mutableMapOf<Pair<String, String>, Set<String>>()
        fun written(key: Pair<String, String>, seen: MutableSet<Pair<String, String>> = mutableSetOf()): Set<String> {
            writes[key]?.let { return it }
            if (!seen.add(key)) return emptySet()
            val method = methods[key] ?: return emptySet()
            val direct = write.findAll(method.body).map { it.groupValues[1] }.toMutableSet()
            staticCall.findAll(method.body).forEach { call ->
                direct += written(call.groupValues[1] to call.groupValues[2], seen)
            }
            return direct.also { writes[key] = it }
        }

        val changes = mutableListOf<Change>()
        for ((file, source) in sources) {
            if (!source.contains("FMLCommonSetupEvent")) continue
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val owner = Regex("""\b(?:class|interface|enum)\s+([A-Za-z_$][\w$]*)""").find(masked)?.groupValues?.get(1) ?: continue
            for (setup in Regex("""\bstatic\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*(?:final\s+)?(?:[\w.]+\.)?FMLCommonSetupEvent\s+[A-Za-z_$][\w$]*\s*\)\s*\{""").findAll(masked)) {
                val close = JavaSourceText.matchingBrace(masked, setup.range.last)
                if (close < 0) continue
                val registration = Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$.()]*)\s*\.\s*addListener\s*\(\s*${Regex.escape(owner)}\s*::\s*${Regex.escape(setup.groupValues[1])}\s*\)\s*;""")
                    .find(masked) ?: continue
                // statements `C.m();` in the setup body whose method writes exactly one built-in registry
                val statements = Regex("""(?m)^([ \t]*)([A-Z][\w$]*)\s*\.\s*([a-z][\w$]*)\s*\(\s*\)\s*;[ \t]*\r?\n""")
                    .findAll(masked.substring(0, close), setup.range.last).toList()
                val movedIndexes = linkedMapOf<Int, String>()
                statements.forEachIndexed { index, statement ->
                    val registry = written(statement.groupValues[2] to statement.groupValues[3]).singleOrNull() ?: return@forEachIndexed
                    movedIndexes[index] = registry
                    // An adjacent preceding call whose class refers to the writer's class (an
                    // initialization trigger such as `Advancements.register()` filling the writer's
                    // list from its static initializers) moves with it, keeping the order.
                    var previous = index - 1
                    while (previous >= 0 && previous !in movedIndexes) {
                        val gap = masked.substring(statements[previous].range.last + 1, statements[previous + 1].range.first)
                        if (gap.isNotBlank()) break
                        val ownerSource = sources.values.firstOrNull { text ->
                            Regex("""\b(?:class|interface|enum)\s+${Regex.escape(statements[previous].groupValues[2])}\b""").containsMatchIn(text)
                        } ?: break
                        val writerClass = statement.groupValues[2]
                        val refersToWriter = Regex("""(?<![\w$.])${Regex.escape(writerClass)}\s*\.""")
                            .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(ownerSource))
                        // an empty `C.m()` only initializes C: it moves when C's initialization reaches the writer
                        val initializesWriter = !refersToWriter &&
                            emptyStaticMethod(ownerSource, statements[previous].groupValues[3]) &&
                            reachesClass(statements[previous].groupValues[2], writerClass, sources.values)
                        if (!refersToWriter && !initializesWriter) break
                        movedIndexes[previous] = registry
                        previous--
                    }
                }
                val moved = movedIndexes.entries.sortedBy { it.key }.map { statements[it.key] to it.value }
                if (moved.isEmpty()) continue
                val byKey = moved.groupBy({ registryKey(it.second) }, { it.first })
                if (byKey.keys.any { it == null }) continue
                val newline = if (source.contains("\r\n")) "\r\n" else "\n"
                var result = source
                // remove the moved statements, last first
                moved.map { it.first }.sortedByDescending { it.range.first }.forEach { statement ->
                    result = result.removeRange(statement.range)
                }
                // one listener per registry key, after the setup listener's registration
                val listeners = byKey.entries.joinToString("") { (key, statements) ->
                    val name = "modporterRegister" + key!!.split('_').joinToString("") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }
                    val calls = statements.joinToString(newline) { "            ${it.groupValues[2]}.${it.groupValues[3]}();" }
                    "$newline    private static void $name(net.neoforged.neoforge.registries.RegisterEvent event) {$newline" +
                        "        if (event.getRegistryKey().equals($KEYS.$key)) {$newline$calls$newline        }$newline    }$newline"
                }
                val listenerCalls = byKey.keys.joinToString("") { key ->
                    val name = "modporterRegister" + key!!.split('_').joinToString("") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }
                    "$newline${registration.groupValues[1]}${registration.groupValues[2]}.addListener($owner::$name);"
                }
                val registrationEnd = registration.range.last + 1
                result = result.substring(0, registrationEnd) + listenerCalls + result.substring(registrationEnd)
                val typeClose = JavaSourceText.matchingBrace(
                    JavaSourceText.maskCommentsAndLiterals(result),
                    JavaSourceText.maskCommentsAndLiterals(result).indexOf('{', Regex("""\b(?:class|interface|enum)\s+${Regex.escape(owner)}\b""").find(result)!!.range.last)
                )
                if (typeClose < 0) continue
                result = result.substring(0, typeClose) + listeners + result.substring(typeClose)
                changes += Change(
                    file = file,
                    line = 1,
                    description = "Move built-in registry writes out of common setup into RegisterEvent listeners",
                    before = moved.joinToString { "${it.first.groupValues[2]}.${it.first.groupValues[3]}() in ${setup.groupValues[1]}" },
                    after = byKey.keys.joinToString { "RegisterEvent for Registries.$it" },
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
                if (!dryRun) file.writeText(result)
                break
            }
        }
        return changes
    }

    /** The `Registries` key whose ResourceKey wraps the registry type of `BuiltInRegistries.[field]`. */
    private fun registryKey(field: String): String? {
        val registry = TargetMembers.fieldSignatures(BUILT_IN)[field] ?: return null
        val expected = "Lnet/minecraft/resources/ResourceKey<${registry.removeSuffix(";")};>;"
        return TargetMembers.fieldSignatures(KEYS).entries.singleOrNull { it.value == expected }?.key
    }

    /** Whether [source] declares `static void [name]()` with an empty body. */
    private fun emptyStaticMethod(source: String, name: String): Boolean {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val declaration = Regex("""\bstatic\s+void\s+${Regex.escape(name)}\s*\(\s*\)\s*\{""").find(masked) ?: return false
        val close = JavaSourceText.matchingBrace(masked, declaration.range.last)
        return close > declaration.range.last && masked.substring(declaration.range.last + 1, close).isBlank()
    }

    /**
     * Whether initializing project class [from] reaches [target]: [target] is referred to (`Target.`) by
     * [from] or by a project class it refers to, at most three classes away.
     */
    private fun reachesClass(from: String, target: String, sources: Collection<String>): Boolean {
        val declared = sources.mapNotNull { text ->
            Regex("""\b(?:class|interface|enum|record)\s+([A-Z][\w$]*)""").find(JavaSourceText.maskCommentsAndLiterals(text))
                ?.let { it.groupValues[1] to JavaSourceText.maskCommentsAndLiterals(text) }
        }.toMap()
        val seen = mutableSetOf(from)
        var frontier = listOf(from)
        repeat(3) {
            val next = mutableListOf<String>()
            for (name in frontier) {
                val masked = declared[name] ?: continue
                if (Regex("""(?<![\w$.])${Regex.escape(target)}\s*\.""").containsMatchIn(masked)) return true
                Regex("""(?<![\w$.])([A-Z][\w$]*)\b""").findAll(masked).map { it.groupValues[1] }
                    .filter { it in declared && seen.add(it) }
                    .forEach { next += it }
            }
            frontier = next
        }
        return false
    }
}
