package com.modporter.core.transforms.structural

import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

internal fun StructuralRefactorPass.migrateDelegatingEntityDimensionTransitionOverrides(
    projectDir: Path,
    dryRun: Boolean
): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()

    Files.walk(srcDir).use { files ->
        files.filter { it.extension == "java" }.toList().forEach fileLoop@ { file ->
            val original = file.readText()
            val executable = maskJavaCommentsAndLiterals(original)
            if (!executable.contains("PortalInfo") ||
                !executable.contains("findDimensionEntryPoint")
            ) {
                return@fileLoop
            }
            val imports = javaNonStaticImports(executable)
            val edits = mutableListOf<Pair<IntRange, String>>()

            javaMethodRangesIncludingDefault(original).forEach methodLoop@ { method ->
                if (method.name != "findDimensionEntryPoint") return@methodLoop
                val methodText = original.substring(method.range)
                val executableMethod = maskJavaCommentsAndLiterals(methodText)
                val signature = Regex(
                    """\b((?:net\.minecraft\.world\.level\.portal\.)?PortalInfo)\s+findDimensionEntryPoint\s*\(\s*(?:final\s+)?((?:net\.minecraft\.server\.level\.)?ServerLevel)\s+([A-Za-z_$][\w$]*)\s*\)"""
                ).find(executableMethod) ?: return@methodLoop
                val returnOwner = resolveJavaTypeReferenceFromImportsOrFqn(signature.groupValues[1], imports)
                val levelOwner = resolveJavaTypeReferenceFromImportsOrFqn(signature.groupValues[2], imports)
                if (returnOwner != "net.minecraft.world.level.portal.PortalInfo" ||
                    levelOwner != "net.minecraft.server.level.ServerLevel"
                ) {
                    return@methodLoop
                }
                val openBrace = executableMethod.indexOf('{', signature.range.last + 1)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(executableMethod, openBrace) else -1
                if (openBrace < 0 || closeBrace < 0) return@methodLoop
                val parameterName = signature.groupValues[3]
                val body = executableMethod.substring(openBrace + 1, closeBrace).trim()
                val exactDelegation = Regex(
                    """return\s+super\.findDimensionEntryPoint\s*\(\s*${Regex.escape(parameterName)}\s*\)\s*;"""
                )
                if (!exactDelegation.matches(body)) return@methodLoop

                val returnRange = signature.groups[1]!!.range
                edits += ((method.range.first + returnRange.first)..
                    (method.range.first + returnRange.last)) to "DimensionTransition"
            }

            if (edits.isEmpty()) return@fileLoop
            var migrated = applyStringEdits(original, edits)
            migrated = addImportIfMissing(
                migrated,
                "net.minecraft.world.level.portal.DimensionTransition"
            )
            if (!Regex("""\bPortalInfo\b""").containsMatchIn(maskJavaCommentsAndLiterals(migrated))) {
                migrated = removeImport(migrated, "net.minecraft.world.level.portal.PortalInfo")
            }

            changes += Change(
                file = file,
                line = lineNumberAt(original, edits.minOf { it.first.first }),
                description = "Migrate exact delegating Entity portal override to DimensionTransition",
                before = "PortalInfo findDimensionEntryPoint(ServerLevel)",
                after = "DimensionTransition findDimensionEntryPoint(ServerLevel)",
                confidence = Confidence.HIGH,
                ruleId = "struct-entity-delegating-dimension-transition"
            )
            if (!dryRun) file.writeText(migrated)
        }
    }
    return changes
}

internal fun StructuralRefactorPass.migrateRegistryKeyAccessors(
    projectDir: Path,
    dryRun: Boolean
): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val typeIndex = JavaProjectTypeIndex.build(srcDir)
    val changes = mutableListOf<Change>()

    Files.walk(srcDir).use { files ->
        files.filter { it.extension == "java" }.toList().forEach fileLoop@ { file ->
            val original = file.readText()
            if (!maskJavaCommentsAndLiterals(original).contains("getRegistryKey")) return@fileLoop
            val unit = typeIndex.unit(file)
            LexicalPreservingPrinter.setup(unit)
            var migratedCalls = 0

            unit.findAll(MethodCallExpr::class.java).forEach callLoop@ { call ->
                if (call.nameAsString != "getRegistryKey" || call.arguments.isNotEmpty()) return@callLoop
                val receiverType = typeIndex.methodCallReceiverType(call)
                val exactRegistryParameter = hasExactRegistryParameterReceiver(call, typeIndex)
                if (receiverType == null) {
                    if (exactRegistryParameter) {
                        throw IllegalStateException(
                            "Cannot resolve explicitly declared Registry receiver for '$call' in $file"
                        )
                    }
                    return@callLoop
                }
                if (!typeIndex.isTypeAssignableTo(receiverType, "net.minecraft.core.Registry")) {
                    if (exactRegistryParameter) {
                        throw IllegalStateException(
                            "Resolved explicitly declared Registry receiver '$call' as $receiverType in $file"
                        )
                    }
                    return@callLoop
                }
                call.setName("key")
                migratedCalls++
            }

            if (migratedCalls == 0) {
                typeIndex.release(file)
                return@fileLoop
            }
            val migrated = LexicalPreservingPrinter.print(unit)
            changes += Change(
                file = file,
                line = original.lineSequence().indexOfFirst { it.contains("getRegistryKey") }.let { it + 1 },
                description = "Migrate exact Registry#getRegistryKey calls to Registry#key",
                before = "Registry#getRegistryKey()",
                after = "Registry#key()",
                confidence = Confidence.HIGH,
                ruleId = "struct-registry-key-accessor"
            )
            if (!dryRun) file.writeText(migrated)
            typeIndex.release(file)
        }
    }
    return changes
}

private fun hasExactRegistryParameterReceiver(
    call: MethodCallExpr,
    typeIndex: JavaProjectTypeIndex
): Boolean {
    val receiver = call.scope.orElse(null) as? NameExpr ?: return false
    val callable = call.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return false
    val parameters = callable.parameters.filter { it.nameAsString == receiver.nameAsString }
    if (parameters.size > 1) {
        throw IllegalStateException("Ambiguous callable parameter '${receiver.nameAsString}' for '$call'")
    }
    val parameter = parameters.singleOrNull() ?: return false
    return typeIndex.declaredRawType(parameter.type, parameter) == "net.minecraft.core.Registry"
}
