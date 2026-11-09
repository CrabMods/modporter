package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 1.20.1's `Advancement.Builder#addCriterion(String, CriterionTriggerInstance)` takes a
 * `Criterion<?>` on 1.21.1, and the vanilla `XTrigger.TriggerInstance` factories return that
 * `Criterion` (class table). A project method that takes a `CriterionTriggerInstance` only to hand it
 * to `addCriterion`, and that every project caller hands such a platform factory call, takes the
 * `Criterion<?>` instead.
 */
internal object CriterionParameters {
    const val RULE_ID = "struct-criterion-parameters"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val CRITERION = "Lnet/minecraft/advancements/Criterion;"
    private val header = Regex("""\b($ID)\s*\(([^()]*\b(?:net\.minecraft\.advancements\.)?CriterionTriggerInstance\s+$ID[^()]*)\)\s*(?:throws\s+[^{;]+)?\{""")
    private val factory = Regex("""^($ID)\s*\.\s*TriggerInstance\s*\.\s*($ID)\s*\(""")

    private data class Target(val file: Path, val name: String, val count: Int, val index: Int, val typeRange: IntRange)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val targets = sources.filterValues { it.contains("CriterionTriggerInstance") }.flatMap { (file, source) -> targets(file, source) }
        if (targets.isEmpty()) return emptyList()
        val masked = sources.mapValues { JavaSourceText.maskCommentsAndLiterals(it.value) }
        val accepted = targets.filter { target -> callersPassFactories(target, sources, masked) }
        val changes = mutableListOf<Change>()
        for ((file, fileTargets) in accepted.groupBy { it.file }) {
            var result = sources.getValue(file)
            for (target in fileTargets.sortedByDescending { it.typeRange.first }) {
                result = result.replaceRange(target.typeRange, "net.minecraft.advancements.Criterion<?>")
            }
            if (!dryRun) file.writeText(result)
            changes += Change(file, 1, "Advancement criteria are handed on as Criterion<?>",
                "CriterionTriggerInstance parameter", "Criterion<?> parameter", Confidence.HIGH, RULE_ID)
        }
        return changes
    }

    /** Methods taking a CriterionTriggerInstance they use only as `addCriterion`'s criterion. */
    private fun targets(file: Path, source: String): List<Target> {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        return header.findAll(masked).mapNotNull { match ->
            val name = match.groupValues[1]
            if (Regex("""@Override\s+[^;{}]*$""").containsMatchIn(masked.substring(maxOf(0, match.range.first - 200), match.range.first))) return@mapNotNull null
            val parametersStart = match.groups[2]!!.range.first
            val parameters = JavaSourceText.splitTopLevelArgs(source.substring(parametersStart, match.groups[2]!!.range.last + 1))
            val index = parameters.indexOfFirst { Regex("""^\s*(?:final\s+)?(?:net\.minecraft\.advancements\.)?CriterionTriggerInstance\s+$ID\s*$""").matches(it) }
            if (index < 0) return@mapNotNull null
            val parameter = Regex("""(CriterionTriggerInstance|net\.minecraft\.advancements\.CriterionTriggerInstance)\s+($ID)""").find(parameters[index]) ?: return@mapNotNull null
            val declared = Regex("""(?:net\.minecraft\.advancements\.)?CriterionTriggerInstance\s+${Regex.escape(parameter.groupValues[2])}(?![\w$])""")
                .find(masked.substring(parametersStart, match.groups[2]!!.range.last + 1)) ?: return@mapNotNull null
            val typeStart = parametersStart + declared.range.first
            val close = JavaSourceText.matchingBrace(masked, match.range.last)
            if (close < 0) return@mapNotNull null
            val body = masked.substring(match.range.last, close)
            val variable = Regex.escape(parameter.groupValues[2])
            val uses = Regex("""(?<![\w$.])$variable(?![\w$])""").findAll(body).count()
            val criterionUses = Regex("""\.\s*addCriterion\s*\([^;]*?,\s*$variable\s*\)""").findAll(body).count()
            if (uses == 0 || uses != criterionUses) return@mapNotNull null
            Target(file, name, parameters.size, index, typeStart..(typeStart + parameter.groupValues[1].length - 1))
        }.toList()
    }

    private fun callersPassFactories(target: Target, sources: Map<Path, String>, masked: Map<Path, String>): Boolean {
        val call = Regex("""(?<![\w$])${Regex.escape(target.name)}\s*\(""")
        var calls = 0
        for ((file, text) in masked) {
            for (match in call.findAll(text)) {
                val close = JavaSourceText.matchingParen(text, match.range.last)
                if (close < 0) continue
                // the declaration itself
                if (Regex("""^\s*(?:throws\s+[^{;]+)?\{""").containsMatchIn(text.substring(close + 1)) &&
                    Regex("""[\w$>\]]\s+$""").containsMatchIn(text.substring(0, match.range.first))) continue
                val arguments = JavaSourceText.splitTopLevelArgs(sources.getValue(file).substring(match.range.last + 1, close))
                if (arguments.size != target.count) continue
                calls++
                val argument = factory.find(arguments[target.index].trim()) ?: return false
                val (outer, method) = argument.destructured
                val returnsCriterion = TargetMembers.methodsNamed(method).any { (owner, descriptor) ->
                    owner.endsWith(".$outer\$TriggerInstance") && descriptor.endsWith(")$CRITERION")
                }
                if (!returnsCriterion) return false
            }
        }
        return calls > 0
    }
}
