package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 1.21.1 declares several behaviour methods `protected` that 1.20.1 code called on other
 * instances (`Blocks.LEVER.getShape(state, level, pos, context)` from a different block). Such a
 * call does not compile outside the declaring hierarchy's own instance, and dispatching through
 * the state would recurse into the caller. The call keeps its receiver and arguments and goes
 * through a generated Mixin `@Invoker` of exactly the 1.21.1 declaration.
 *
 * Admission is by the 1.21.1 class table only: the receiver is a platform static field
 * (`Holder.FIELD`, typed by its descriptor), exactly one method of that name and arity exists in
 * the receiver type's hierarchy, and that declaration is protected. A mod without a Mixin config
 * owning the calling package keeps the call (the residual compile error stays visible).
 */
internal object ProtectedPlatformCalls {
    const val RULE_ID = "build-protected-platform-call-invoker"

    private val call = Regex("""(?<![\w$.])([A-Z][\w$]*)\s*\.\s*([A-Z][A-Z0-9_]*)\s*\.\s*([a-z][\w$]*)\s*\(""")

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = GradleModLayout.modModule(projectDir).resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val generator = ThirdPartyReflectionMixinMigration()
        val changes = mutableListOf<Change>()
        val consumers = mutableListOf<String>()
        val invokers = linkedSetOf<ThirdPartyReflectionMixinMigration.PlatformInvoker>()
        val files = Files.walk(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        for (file in files) {
            val original = file.readText()
            if (!call.containsMatchIn(original)) continue
            val masked = JavaSourceText.maskCommentsAndLiterals(original)
            val imports = Regex("""(?m)^\s*import\s+([\w.]+)\s*;""").findAll(masked)
                .associate { it.groupValues[1].substringAfterLast('.') to it.groupValues[1] }
            val edits = mutableListOf<Pair<IntRange, String>>()
            for (match in call.findAll(masked)) {
                val holder = imports[match.groupValues[1]]?.takeIf { TargetMembers.hasClass(it) } ?: continue
                val fieldDescriptor = TargetMembers.fieldDescriptor(holder, match.groupValues[2]) ?: continue
                val receiverType = Regex("""^L([\w/$]+);$""").find(fieldDescriptor)?.groupValues?.get(1)?.replace('/', '.') ?: continue
                val name = match.groupValues[3]
                val open = match.range.last
                val close = JavaSourceText.matchingParen(masked, open)
                if (close < 0) continue
                val argumentText = original.substring(open + 1, close)
                val arguments = if (argumentText.isBlank()) emptyList() else JavaSourceText.splitTopLevelArgs(argumentText).map { it.trim() }
                val declaration = protectedDeclaration(receiverType, name, arguments.size) ?: continue
                val (owner, descriptor) = declaration
                val parameterTypes = TargetMembers.parameterTypes(descriptor).map { ThirdPartyReflectionMixinMigration.javaTypeOf(it) ?: return@map null }
                if (parameterTypes.any { it == null }) continue
                val returnType = ThirdPartyReflectionMixinMigration.javaTypeOf(descriptor.substringAfterLast(')')) ?: continue
                val invoker = ThirdPartyReflectionMixinMigration.PlatformInvoker(owner, name, returnType, parameterTypes.filterNotNull())
                val invokerType = generator.platformInvokerName(projectDir, original, owner) ?: continue
                invokers += invoker
                val receiver = original.substring(match.range.first, match.groups[3]!!.range.first).trim().removeSuffix(".").trim()
                edits += match.range.first..close to
                    "(($invokerType) (Object) $receiver).${invoker.invokerMethod}(${arguments.joinToString(", ")})"
            }
            if (edits.isEmpty()) continue
            val migrated = edits.sortedByDescending { it.first.first }.fold(original) { text, (range, replacement) ->
                text.replaceRange(range, replacement)
            }
            consumers += original
            changes += Change(
                file = file,
                line = 1,
                description = "Call platform methods 1.21.1 declares protected through a generated Mixin invoker",
                before = "Holder.FIELD.protectedMethod(...)",
                after = "((Invoker) (Object) Holder.FIELD).modporter\$invoke...(...)",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
            if (!dryRun) file.writeText(migrated)
        }
        if (invokers.isEmpty()) return changes
        return changes + generator.writePlatformInvokers(projectDir, consumers, invokers, dryRun)
    }

    /** The single protected 1.21.1 declaration of [name] with [arity] parameters reachable from [type]. */
    private fun protectedDeclaration(type: String, name: String, arity: Int): Pair<String, String>? {
        val found = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        val frontier = ArrayDeque(listOf(type))
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            if (!seen.add(current)) continue
            TargetMembers.methodDescriptors(current, name)
                .filter { TargetMembers.parameterTypes(it).size == arity }
                .forEach { found += current to it }
            frontier += TargetMembers.supertypesOf(current)
        }
        val distinct = found.distinctBy { it.second }
        val single = distinct.singleOrNull() ?: return null
        val access = TargetMembers.inheritedMethod(type, name, single.second)?.access ?: return null
        return single.takeIf { access == "protected" }
    }
}
