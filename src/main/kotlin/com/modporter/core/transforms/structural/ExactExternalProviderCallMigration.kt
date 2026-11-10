package com.modporter.core.transforms.structural

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Rewrites exact target-platform provider calls after their callers have acquired provider contracts. */
internal class ExactExternalProviderCallMigration {
    private data class PendingRewrite(
        val file: Path,
        val call: MethodCallExpr,
        val rewrite: ExactExternalProviderContracts.CallRewrite,
        val providerExpression: String
    )

    private data class PendingFamily(
        val key: String,
        val members: List<JavaProjectTypeIndex.ExactProjectMethod>,
        val providerNames: Map<String, String>
    )

    private data class PendingRegistryBufferFamily(
        val key: String,
        val members: List<JavaProjectTypeIndex.ExactProjectMethod>,
        val parameterIndex: Int
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = Files.walk(sourceRoot).use { stream ->
            stream.filter { it.extension == "java" }.toList()
        }
        val candidateFiles = files.filter { file ->
            ExactExternalProviderContracts.containsLegacyCall(file.readText())
        }
        if (candidateFiles.isEmpty()) return emptyList()

        val index = JavaProjectTypeIndex.build(sourceRoot)
        val units = linkedMapOf<Path, com.github.javaparser.ast.CompilationUnit>()
        val lexicalFiles = linkedSetOf<Path>()
        fun unit(file: Path) = units.getOrPut(file.toAbsolutePath().normalize()) { index.unit(file) }
        fun ensureLexical(file: Path) {
            val normalized = file.toAbsolutePath().normalize()
            if (lexicalFiles.add(normalized)) LexicalPreservingPrinter.setup(unit(normalized))
        }
        val pendingRewrites = mutableListOf<PendingRewrite>()
        val pendingFamilies = linkedMapOf<String, PendingFamily>()
        val pendingRegistryBufferFamilies = linkedMapOf<String, PendingRegistryBufferFamily>()
        candidateFiles.forEach { file ->
            unit(file).findAll(MethodCallExpr::class.java).toList().forEach calls@ { call ->
                if (ExactExternalProviderContracts.callRewrite(call, index) == null) return@calls
                val caller = call.findAncestor(MethodDeclaration::class.java).orElseThrow {
                    IllegalStateException("Exact external provider call '$call' is not enclosed by a method")
                }
                if (exactDeclaredProviderExpression(caller, call, index) != null) return@calls
                val legacyBuffers = caller.parameters.withIndex().filter { (_, parameter) ->
                    index.declaredType(parameter.type, caller) == FRIENDLY_BYTE_BUF
                }
                if (legacyBuffers.size > 1) {
                    throw IllegalStateException(
                        "Ambiguous FriendlyByteBuf provider boundaries in ${caller.nameAsString}"
                    )
                }
                val legacyBuffer = legacyBuffers.singleOrNull() ?: return@calls
                val family = index.exactProjectOverrideFamily(caller) ?: throw IllegalStateException(
                    "Cannot prove a closed project override family for FriendlyByteBuf provider boundary " +
                        "${caller.nameAsString}"
                )
                val familyKey = family.joinToString("|") { member ->
                    "${member.owner}.${member.method.nameAsString}/${member.method.parameters.size}"
                }
                pendingRegistryBufferFamilies.getOrPut(familyKey) {
                    family.forEach { member ->
                        val parameter = member.method.parameters.getOrNull(legacyBuffer.index)
                            ?: throw IllegalStateException(
                                "Registry buffer override family $familyKey has inconsistent arity"
                            )
                        if (index.declaredType(parameter.type, member.method) != FRIENDLY_BYTE_BUF) {
                            throw IllegalStateException(
                                "Registry buffer override family $familyKey is only partially legacy-compatible"
                            )
                        }
                    }
                    PendingRegistryBufferFamily(familyKey, family, legacyBuffer.index)
                }
            }
        }
        pendingRegistryBufferFamilies.values.forEach { family ->
            family.members.forEach { member ->
                ensureLexical(member.file)
                unit(member.file).addImport(REGISTRY_FRIENDLY_BYTE_BUF)
                member.method.parameters[family.parameterIndex].setType("RegistryFriendlyByteBuf")
            }
        }
        candidateFiles.forEach { file ->
            unit(file).findAll(MethodCallExpr::class.java).toList().forEach calls@ { call ->
                val rewrite = ExactExternalProviderContracts.callRewrite(call, index) ?: return@calls
                val caller = call.findAncestor(MethodDeclaration::class.java).orElseThrow {
                    IllegalStateException("Exact external provider call '$call' is not enclosed by a method")
                }
                var provider = exactDeclaredProviderExpression(caller, call, index)
                if (provider == null) {
                    val family = index.exactProjectOverrideFamily(caller) ?: throw IllegalStateException(
                        "Exact external provider call '$call' in ${caller.nameAsString} has no declared provider " +
                            "source and no closed project override family rooted in a proven non-override method"
                    )
                    val familyKey = family.joinToString("|") { member ->
                        "${member.owner}.${member.method.nameAsString}/${member.method.parameters.size}"
                    }
                    val pendingFamily = pendingFamilies.getOrPut(familyKey) {
                        val names = family.associate { member ->
                            member.owner to uniqueProviderName(member.method)
                        }
                        PendingFamily(familyKey, family, names)
                    }
                    val owner = exactEnclosingNamedClass(caller)?.fullyQualifiedName?.orElse(null)
                        ?: throw IllegalStateException("Cannot resolve provider-demanded caller owner for '$call'")
                    provider = pendingFamily.providerNames.getValue(owner)
                }
                pendingRewrites += PendingRewrite(file, call, rewrite, provider)
            }
        }
        pendingFamilies.values.forEach { family ->
            family.members.forEach { member ->
                if (member.method.parameters.any { parameter ->
                        index.declaredType(parameter.type, member.method) == HOLDER_LOOKUP_PROVIDER
                    }
                ) {
                    throw IllegalStateException(
                        "Partially provider-aware override family ${family.key} cannot be migrated atomically"
                    )
                }
                ensureLexical(member.file)
                member.method.addParameter(
                    "net.minecraft.core.HolderLookup.Provider",
                    family.providerNames.getValue(member.owner)
                )
            }
        }
        pendingRewrites.forEach { pending ->
            ensureLexical(pending.file)
            pending.call.setName(pending.rewrite.targetMethodName)
            pending.call.arguments.add(
                pending.rewrite.providerParameterIndex,
                StaticJavaParser.parseExpression(pending.providerExpression)
            )
        }
        val changedFiles = linkedSetOf<Path>().apply {
            addAll(pendingRewrites.map { it.file.toAbsolutePath().normalize() })
            addAll(pendingFamilies.values.flatMap { family -> family.members.map { it.file } })
            addAll(pendingRegistryBufferFamilies.values.flatMap { family -> family.members.map { it.file } })
        }
        val changes = mutableListOf<Change>()
        changedFiles.forEach { file ->
            val migrated = LexicalPreservingPrinter.print(unit(file))
            if (!dryRun) file.writeText(migrated)
            changes += Change(
                file = file,
                line = 1,
                description = "Migrate exact target-platform call to its provider-aware contract",
                before = "legacy external call without registry provider",
                after = "exact provider-aware external call",
                confidence = Confidence.HIGH,
                ruleId = "struct-exact-external-provider-call"
            )
        }
        return changes
    }

    private fun uniqueProviderName(method: MethodDeclaration): String {
        val identifiers = method.findAll(NameExpr::class.java).map { it.nameAsString }.toSet() +
            method.parameters.map { it.nameAsString }
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "modporterRegistries" else "modporterRegistries$suffix"
            if (candidate !in identifiers) return candidate
            suffix++
        }
    }

    private fun exactDeclaredProviderExpression(
        caller: MethodDeclaration,
        call: MethodCallExpr,
        index: JavaProjectTypeIndex
    ): String? {
        ExactExternalProviderContracts.providerExpression(caller, index)?.let { return it }
        val typedParameters = caller.parameters.mapNotNull { parameter ->
            if (hasNullableEvidence(parameter, caller)) return@mapNotNull null
            val type = index.declaredType(parameter.type, caller) ?: return@mapNotNull null
            type to parameter.nameAsString
        }
        fun unique(candidates: List<String>, label: String): String? {
            val distinct = candidates.distinct()
            if (distinct.size > 1) {
                throw IllegalStateException(
                    "Ambiguous exact $label provider sources in ${caller.nameAsString}: $distinct"
                )
            }
            return distinct.singleOrNull()
        }
        unique(typedParameters.mapNotNull { (type, name) ->
            name.takeIf { type in setOf(HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS) }
        }, "direct")?.let { return it }
        ExactReceiverProviderProvenance.providerExpression(call, caller, index)?.let { return it }
        return unique(typedParameters.mapNotNull { (type, name) ->
            "$name.registryAccess()".takeIf {
                LEVEL_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } ||
                    ENTITY_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) }
            }
        }, "derived")
    }

    private fun hasNullableEvidence(parameter: Parameter, caller: MethodDeclaration): Boolean {
        if (parameter.annotations.any { it.name.identifier == "Nullable" }) return true
        val name = parameter.nameAsString
        return caller.findAll(BinaryExpr::class.java).any { expression ->
            expression.operator in setOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS) &&
                ((expression.left is NameExpr && expression.left.asNameExpr().nameAsString == name &&
                    expression.right is NullLiteralExpr) ||
                    (expression.right is NameExpr && expression.right.asNameExpr().nameAsString == name &&
                        expression.left is NullLiteralExpr))
        }
    }

    private companion object {
        const val HOLDER_LOOKUP_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
        const val REGISTRY_ACCESS = "net.minecraft.core.RegistryAccess"
        const val FRIENDLY_BYTE_BUF = "net.minecraft.network.FriendlyByteBuf"
        const val REGISTRY_FRIENDLY_BYTE_BUF = "net.minecraft.network.RegistryFriendlyByteBuf"
        val LEVEL_PROVIDER_TYPES = setOf(
            "net.minecraft.world.level.Level",
            "net.minecraft.server.level.ServerLevel",
            "net.minecraft.world.level.WorldGenLevel",
            "net.minecraft.world.level.LevelAccessor",
            "net.minecraft.world.level.ServerLevelAccessor"
        )
        val ENTITY_PROVIDER_TYPES = setOf(
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.server.level.ServerPlayer"
        )
    }
}
