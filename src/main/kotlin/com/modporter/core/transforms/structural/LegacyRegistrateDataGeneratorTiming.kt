package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Registrate for 1.21.1 builds its root data provider from its own NORMAL-priority
 * `GatherDataEvent` listener and refuses `addDataGenerator` afterwards. A 1.20.1 mod that added
 * extra Registrate data from a lower-priority gather listener (EventPriority.LOW/LOWEST) therefore
 * adds it too late. The leading call of that listener which reaches `addDataGenerator` (through
 * static no-argument project methods) moves into a HIGHEST-priority listener registered next to
 * it on the same bus; everything else in the listener keeps its priority and order.
 */
internal object LegacyRegistrateDataGeneratorTiming {
    const val RULE_ID = "struct-registrate-data-generator-timing"

    private val dataGenerator = Regex("""\.\s*addDataGenerator\s*\(""")
    private val staticCall = Regex("""(?<![\w$.])([A-Z][\w$]*)\s*\.\s*([a-z][\w$]*)\s*\(\s*\)""")
    private val unqualifiedCall = Regex("""(?<![\w$.])([a-z][\w$]*)\s*\(\s*\)""")
    private val registrationPattern = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$.()]*)\s*\.\s*addListener\s*\(\s*(?:net\.neoforged\.bus\.api\.)?EventPriority\s*\.\s*(LOW|LOWEST)\s*,\s*([A-Z][\w$]*)\s*::\s*([A-Za-z_$][\w$]*)\s*\)\s*;"""
    )
    private val leadingCall = Regex("""^\s*(?:([A-Z][\w$]*)\s*\.\s*)?([a-z][\w$]*)\s*\(\s*\)\s*;[ \t]*\r?\n""")

    private fun typeDeclaration(owner: String) = Regex("""\b(?:class|interface|enum)\s+${Regex.escape(owner)}\b""")

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = GradleModLayout.modModule(projectDir).resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { it.readText() }
        if (sources.values.none { it.contains("addDataGenerator") } ||
            sources.values.none { it.contains("com.tterrag.registrate") }
        ) {
            return emptyList()
        }
        // static no-argument project methods by (owner simple name, method)
        val bodies = linkedMapOf<Pair<String, String>, String>()
        for ((_, source) in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val owner = Regex("""\b(?:class|interface|enum)\s+([A-Za-z_$][\w$]*)""").find(masked)?.groupValues?.get(1) ?: continue
            Regex("""\bstatic\s+(?:final\s+)?void\s+([a-z][\w$]*)\s*\(\s*\)\s*\{""").findAll(masked).forEach { header ->
                val close = JavaSourceText.matchingBrace(masked, header.range.last)
                if (close > header.range.last) bodies[owner to header.groupValues[1]] = masked.substring(header.range.last + 1, close)
            }
        }
        val reaches = mutableMapOf<Pair<String, String>, Boolean>()
        fun reachesDataGenerator(key: Pair<String, String>, seen: MutableSet<Pair<String, String>> = mutableSetOf()): Boolean {
            reaches[key]?.let { return it }
            if (!seen.add(key)) return false
            val body = bodies[key] ?: return false
            val result = dataGenerator.containsMatchIn(body) ||
                staticCall.findAll(body).any { reachesDataGenerator(it.groupValues[1] to it.groupValues[2], seen) } ||
                unqualifiedCall.findAll(body).any { reachesDataGenerator(key.first to it.groupValues[1], seen) }
            return result.also { reaches[key] = it }
        }

        val updated = linkedMapOf<Path, String>()
        fun current(file: Path): String = updated[file] ?: sources.getValue(file)
        val moved = mutableListOf<Pair<Path, String>>()
        for (file in sources.keys) {
            val registrations = registrationPattern.findAll(JavaSourceText.maskCommentsAndLiterals(current(file))).toList()
            for (registration in registrations.asReversed()) {
                val listenerOwner = registration.groupValues[4]
                val listenerName = registration.groupValues[5]
                val ownerFile = sources.keys.firstOrNull { typeDeclaration(listenerOwner).containsMatchIn(current(it)) } ?: continue
                val ownerText = current(ownerFile)
                val ownerMasked = JavaSourceText.maskCommentsAndLiterals(ownerText)
                val header = Regex(
                    """\bstatic\s+void\s+${Regex.escape(listenerName)}\s*\(\s*(?:final\s+)?(?:[\w.]+\.)?GatherDataEvent\s+[A-Za-z_$][\w$]*\s*\)\s*\{"""
                ).find(ownerMasked) ?: continue
                // the listener's leading statement: a static no-argument call that reaches addDataGenerator
                val leading = leadingCall.find(ownerMasked.substring(header.range.last + 1)) ?: continue
                val callOwner = leading.groupValues[1].ifEmpty { listenerOwner }
                if (!reachesDataGenerator(callOwner to leading.groupValues[2])) continue
                val callStart = header.range.last + 1 + leading.range.first + (leading.value.length - leading.value.trimStart().length)
                val callEnd = header.range.last + 1 + leading.range.last + 1
                val call = ownerText.substring(callStart, callEnd).trim()
                val newline = if (ownerText.contains("\r\n")) "\r\n" else "\n"
                var name = "modporterAddRegistrateDataGenerators"
                while (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(ownerMasked)) name += "_"
                val qualifiedCall = if (leading.groupValues[1].isEmpty()) "$listenerOwner.$call" else call
                val listener = "$newline    public static void $name(net.neoforged.neoforge.data.event.GatherDataEvent event) {$newline" +
                    "        $qualifiedCall$newline    }$newline"
                var ownerUpdated = ownerText.removeRange(callStart, callEnd)
                val ownerUpdatedMasked = JavaSourceText.maskCommentsAndLiterals(ownerUpdated)
                val declaration = typeDeclaration(listenerOwner).find(ownerUpdatedMasked) ?: continue
                val ownerOpen = ownerUpdatedMasked.indexOf('{', declaration.range.last)
                val ownerClose = JavaSourceText.matchingBrace(ownerUpdatedMasked, ownerOpen)
                if (ownerClose < 0) continue
                ownerUpdated = ownerUpdated.substring(0, ownerClose) + listener + ownerUpdated.substring(ownerClose)
                updated[ownerFile] = ownerUpdated
                // register it at HIGHEST right after the existing registration (located again after the edit above)
                val registrationText = current(file)
                val located = registrationPattern.findAll(JavaSourceText.maskCommentsAndLiterals(registrationText))
                    .firstOrNull { it.groupValues[4] == listenerOwner && it.groupValues[5] == listenerName } ?: continue
                val registrationLine = "$newline${located.groupValues[1]}${located.groupValues[2]}.addListener(" +
                    "net.neoforged.bus.api.EventPriority.HIGHEST, $listenerOwner::$name);"
                updated[file] = registrationText.substring(0, located.range.last + 1) + registrationLine +
                    registrationText.substring(located.range.last + 1)
                moved += ownerFile to "$call from $listenerOwner.$listenerName"
            }
        }
        val changes = mutableListOf<Change>()
        for ((file, text) in updated) {
            if (text == sources.getValue(file)) continue
            if (!dryRun) file.writeText(text)
            changes += Change(
                file = file,
                line = 1,
                description = "Add Registrate data generators from a HIGHEST-priority gather-data listener",
                before = moved.filter { it.first == file }.joinToString { it.second }.ifEmpty { "a LOW/LOWEST gather-data listener registration" },
                after = "HIGHEST-priority listener ahead of Registrate's root data provider",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
        return changes
    }
}
