package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.type.Type
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Migrates the project-local CompoundTag lifecycle method family to provider-aware 1.21 signatures. */
internal class LegacyNbtProviderMethodMigration {
    private data class SourceUnit(
        val file: Path,
        val source: String,
        val compilationUnit: CompilationUnit,
        val lineOffsets: IntArray
    )

    private data class TargetMethod(
        val unit: SourceUnit,
        val method: MethodDeclaration,
        val owner: String,
        val providerIndex: Int,
        val providerName: String,
        val legacyParameterTypes: List<String>
    ) {
        val legacyArity: Int get() = legacyParameterTypes.size
    }

    private data class MethodShape(val name: String, val parameterTypes: List<String>)

    private data class Edit(val start: Int, val endExclusive: Int, val replacement: String)

    fun migrate(
        projectDir: Path,
        dryRun: Boolean,
        allowContextProviderExpressions: Boolean = true
    ): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = Files.walk(sourceRoot)
            .filter { it.extension == "java" }
            .filter { !sourceRoot.relativize(it).toString().replace('\\', '/').startsWith("com/modporter/generated/") }
            .toList()
        if (files.isEmpty()) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val typeIndex = JavaProjectTypeIndex.build(sourceRoot)
        val units = mutableListOf<SourceUnit>()
        files.forEach { file ->
            val source = file.readText()
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful) {
                throw IllegalStateException(
                    "Cannot parse $file while migrating provider-aware NBT methods: " +
                    parsed.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            val unit = SourceUnit(file, source, parsed.result.orElseThrow(), lineOffsets(source))
            if (isNbtMigrationUnitSource(source)) units += unit
        }
        if (units.isEmpty()) return emptyList()

        val allMethods = units.flatMap { unit ->
            unit.compilationUnit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
                declaringTypeName(method)?.let { owner -> Triple(unit, method, owner) }
            }
        }
        val providerAwareTargets = allMethods.mapNotNull { (unit, method, owner) ->
            resolvedMethodShapeWithoutProvider(method, typeIndex)?.let { shape ->
                val providerIndex = method.parameters.indexOfFirst { parameter ->
                    typeIndex.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
                }
                TargetMethod(
                    unit,
                    method,
                    owner,
                    providerIndex,
                    existingProviderName(method) ?: return@let null,
                    shape.parameterTypes
                )
            }
        }
        val contracts = linkedMapOf<MethodDeclaration, TargetMethod>()
        fun registerContract(target: TargetMethod): Boolean {
            val previous = contracts[target.method]
            if (previous != null) {
                if (previous.providerIndex != target.providerIndex ||
                    previous.legacyParameterTypes != target.legacyParameterTypes
                ) {
                    throw IllegalStateException(
                        "Conflicting HolderLookup.Provider contracts for " +
                            "${target.owner}.${target.method.nameAsString}: " +
                            "${previous.providerIndex} versus ${target.providerIndex}"
                    )
                }
                return false
            }
            contracts[target.method] = target
            return true
        }
        providerAwareTargets.forEach(::registerContract)
        var expanded: Boolean
        do {
            expanded = false
            allMethods.forEach candidates@{ (unit, method, owner) ->
                if (method in contracts || method.parameters.any { parameter ->
                        typeIndex.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
                    }
                ) return@candidates
                val shape = resolvedMethodShape(method, typeIndex) ?: return@candidates
                val anchors = contracts.values.filter { anchor ->
                    anchor.method.nameAsString == shape.name &&
                        anchor.legacyParameterTypes == shape.parameterTypes &&
                        areOverrideRelated(owner, anchor.owner, typeIndex)
                }
                if (anchors.isEmpty()) return@candidates
                val positions = anchors.map { it.providerIndex }.distinct()
                if (positions.size != 1) {
                    throw IllegalStateException(
                        "Conflicting HolderLookup.Provider positions in override family " +
                            "$owner.${method.nameAsString}${shape.parameterTypes}: $positions"
                    )
                }
                if (registerContract(
                        TargetMethod(
                            unit,
                            method,
                            owner,
                            positions.single(),
                            uniqueProviderName(
                                method,
                                anchors.map { it.providerName }.distinct().singleOrNull()
                            ),
                            shape.parameterTypes
                        )
                    )
                ) expanded = true
            }

            contracts.values.toList().forEach anchors@{ anchor ->
                val dataParameters = anchor.method.parameters.mapIndexedNotNull { index, parameter ->
                    if (index == anchor.providerIndex && anchor.method.parameters.size == anchor.legacyArity + 1) {
                        return@mapIndexedNotNull null
                    }
                    val type = typeIndex.declaredType(parameter.type, anchor.method) ?: return@mapIndexedNotNull null
                    parameter.nameAsString.takeIf { type == COMPOUND_TAG }
                }.toSet()
                if (dataParameters.isEmpty()) return@anchors
                anchor.method.findAll(MethodCallExpr::class.java).forEach calls@{ call ->
                    if (enclosingCallable(call) !== anchor.method) return@calls
                    val dataArgumentIndexes = call.arguments.mapIndexedNotNull { index, argument ->
                        index.takeIf { argument is NameExpr && argument.nameAsString in dataParameters }
                    }
                    if (dataArgumentIndexes.isEmpty()) return@calls
                    if (dataArgumentIndexes.size != 1) {
                        throw IllegalStateException(
                            "Ambiguous CompoundTag forwarding arguments in ${anchor.owner}.${anchor.method.nameAsString}: $call"
                        )
                    }
                    val calleeOwner = typeIndex.projectMethodOwner(call) ?: return@calls
                    val matching = allMethods.filter { (_, candidate, owner) ->
                        owner == calleeOwner &&
                            candidate.nameAsString == call.nameAsString &&
                            candidate.parameters.none { parameter ->
                                typeIndex.declaredType(parameter.type, candidate) == HOLDER_LOOKUP_PROVIDER
                            } &&
                            candidate.parameters.size == call.arguments.size
                    }.filter { (_, candidate, _) ->
                        val shape = resolvedMethodShape(candidate, typeIndex) ?: return@filter false
                        typeIndex.argumentsMatchTypes(call, shape.parameterTypes)
                    }
                    if (matching.size > 1) {
                        throw IllegalStateException("Ambiguous project CompoundTag forwarder target for '$call'")
                    }
                    val (unit, method, owner) = matching.singleOrNull() ?: return@calls
                    val shape = resolvedMethodShape(method, typeIndex) ?: return@calls
                    val providerIndex = shape.parameterTypes.size
                    if (providerIndex !in 0..shape.parameterTypes.size) {
                        throw IllegalStateException("Invalid forwarded provider position $providerIndex for '$call'")
                    }
                    if (registerContract(
                            TargetMethod(
                                unit,
                                method,
                                owner,
                                providerIndex,
                                uniqueProviderName(method, anchor.providerName),
                                shape.parameterTypes
                            )
                        )
                    ) expanded = true
                }
            }
        } while (expanded)
        val targets = contracts.values.filter { target ->
            target.method.parameters.none { parameter ->
                typeIndex.declaredType(parameter.type, target.method) == HOLDER_LOOKUP_PROVIDER
            }
        }
        val familyTargets = targets + providerAwareTargets
        if (familyTargets.isEmpty()) return emptyList()
        val targetDeclarations = familyTargets.associateBy { it.method }
        val targetsByName = familyTargets.groupBy { it.method.nameAsString }
        val edits = linkedMapOf<SourceUnit, MutableList<Edit>>()

        targets.forEach { target ->
            val insertionOffset = parameterInsertionOffset(target.unit, target.method, target.providerIndex)
            val prefix = when {
                target.method.parameters.isEmpty() -> ""
                target.providerIndex == target.method.parameters.size -> ", "
                else -> ""
            }
            val suffix = if (target.providerIndex < target.method.parameters.size) ", " else ""
            edits.getOrPut(target.unit) { mutableListOf() } += Edit(
                insertionOffset,
                insertionOffset,
                prefix + providerParameter(target.unit, target.providerName) + suffix
            )
        }

        units.forEach { unit ->
            unit.compilationUnit.findAll(MethodCallExpr::class.java).forEach calls@{ call ->
                val namedTargets = targetsByName[call.nameAsString].orEmpty()
                if (namedTargets.isEmpty()) return@calls
                val callable = enclosingCallable(call) ?: return@calls
                val receiverType = typeIndex.methodCallReceiverType(call) ?: return@calls
                val matches = namedTargets.filter { target ->
                    target.legacyArity == call.arguments.size &&
                        typeIndex.isTypeAssignableTo(receiverType, target.owner) &&
                        typeIndex.argumentsMatchTypes(call, target.legacyParameterTypes)
                }
                if (matches.isEmpty()) return@calls
                val positions = matches.map { it.providerIndex }.distinct()
                if (positions.size != 1) {
                    throw IllegalStateException(
                        "Ambiguous HolderLookup.Provider positions for typed call '$call': $positions"
                    )
                }
                val provider = targetDeclarations[callable as? MethodDeclaration]?.providerName
                    ?: existingProviderName(callable)
                    ?: if (allowContextProviderExpressions) {
                        exactProviderExpressionAtCall(
                            call,
                            callable,
                            typeIndex
                        )
                    } else {
                        null
                    }
                    ?: return@calls
                val index = positions.single()
                val insertionOffset = argumentInsertionOffset(unit, call, index)
                val prefix = when {
                    call.arguments.isEmpty() -> ""
                    index == call.arguments.size -> ", "
                    else -> ""
                }
                val suffix = if (index < call.arguments.size) ", " else ""
                edits.getOrPut(unit) { mutableListOf() } += Edit(
                    insertionOffset,
                    insertionOffset,
                    prefix + provider + suffix
                )
            }
        }

        val changes = mutableListOf<Change>()
        edits.forEach { (unit, rawEdits) ->
            val fileEdits = rawEdits.distinct().sortedByDescending { it.start }
            var migrated = unit.source
            fileEdits.forEach { edit ->
                migrated = migrated.substring(0, edit.start) + edit.replacement + migrated.substring(edit.endExclusive)
            }
            val verification = parser.parse(migrated)
            if (!verification.isSuccessful) {
                throw IllegalStateException(
                    "Provider-aware NBT method migration produced invalid Java in ${unit.file}: " +
                        verification.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            if (!dryRun) unit.file.writeText(migrated)
            changes += Change(
                file = unit.file,
                line = 1,
                description = "Migrate CompoundTag lifecycle method family to HolderLookup.Provider signatures",
                before = "project NBT lifecycle declaration/call without registry provider",
                after = "provider-aware declaration and exact internal forwarding",
                confidence = Confidence.HIGH,
                ruleId = "struct-nbt-provider-method-family"
            )
        }
        return changes
    }

    private fun resolvedMethodShape(method: MethodDeclaration, typeIndex: JavaProjectTypeIndex): MethodShape? {
        val parameterTypes = method.parameters.map { parameter ->
            typeIndex.declaredType(parameter.type, method) ?: return null
        }
        return MethodShape(method.nameAsString, parameterTypes)
    }

    private fun resolvedMethodShapeWithoutProvider(
        method: MethodDeclaration,
        typeIndex: JavaProjectTypeIndex
    ): MethodShape? {
        val providerIndexes = method.parameters.mapIndexedNotNull { index, parameter ->
            index.takeIf { typeIndex.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER }
        }
        if (providerIndexes.size != 1) return null
        val providerIndex = providerIndexes.single()
        val legacyTypes = method.parameters.map { parameter ->
            typeIndex.declaredType(parameter.type, method) ?: return null
        }.toMutableList().also { it.removeAt(providerIndex) }
        val returnType = typeIndex.declaredType(method.type, method)
        if (COMPOUND_TAG !in legacyTypes && returnType != COMPOUND_TAG) return null
        return MethodShape(method.nameAsString, legacyTypes)
    }

    private fun isNbtMigrationUnitSource(source: String): Boolean =
        source.contains("CompoundTag") || source.contains("HolderLookup.Provider")

    private fun declaringTypeName(method: MethodDeclaration): String? =
        method.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .flatMap { it.fullyQualifiedName }
            .orElse(null)

    private fun areOverrideRelated(
        first: String,
        second: String,
        typeIndex: JavaProjectTypeIndex
    ): Boolean = first == second ||
        typeIndex.isTypeAssignableTo(first, second) ||
        typeIndex.isTypeAssignableTo(second, first)

    private fun exactProviderExpressionAtCall(
        call: MethodCallExpr,
        callable: CallableDeclaration<*>,
        typeIndex: JavaProjectTypeIndex
    ): String? {
        fun blockEntityProvider(receiver: String): String? =
            "$receiver.getLevel().registryAccess()".takeIf {
                ExactNullabilityProof.expressionProvenNonNullAt("$receiver.level", callable, call) ||
                    ExactNullabilityProof.expressionProvenNonNullAt("$receiver.getLevel()", callable, call)
            }
        fun unique(expressions: List<String>, label: String): String? {
            val distinct = expressions.distinct()
            if (distinct.size > 1) {
                throw IllegalStateException(
                    "Ambiguous $label registry provider expressions for ${call.nameAsString}: $distinct"
                )
            }
            return distinct.singleOrNull()
        }

        val parsedParameters = callable.parameters.mapNotNull { parameter ->
            if (!ExactNullabilityProof.parameterProvenNonNullAt(parameter, callable, call)) {
                return@mapNotNull null
            }
            typeIndex.declaredType(parameter.type, callable)?.let { it to parameter.nameAsString }
        }
        unique(parsedParameters.mapNotNull { (type, name) ->
            when (type) {
                HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS -> name
                else -> null
            }
        }, "direct")?.let { return it }
        unique(parsedParameters.mapNotNull { (type, name) ->
            when (type) {
                in LEVEL_TYPES -> "$name.registryAccess()"
                INVENTORY -> "$name.player.registryAccess()"
                BLOCK_ENTITY -> blockEntityProvider(name)
                in ENTITY_TYPES -> "$name.registryAccess()"
                MINECRAFT -> "$name.level.registryAccess()"
                else -> null
            }
        }, "typed parameter")?.let { return it }

        val callerOwner = callable.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .flatMap { it.fullyQualifiedName }
            .orElse(null)
            ?: return null
        val scope = call.scope.orElse(null)
        val receiverExpression = when (scope) {
            null, is ThisExpr, is SuperExpr -> "this"
            is NameExpr -> scope.nameAsString
            else -> null
        }
        val receiverType = typeIndex.methodCallReceiverType(call)
        if (receiverExpression != null && receiverType != null &&
            typeIndex.isTypeAssignableTo(receiverType, BLOCK_ENTITY)
        ) {
            blockEntityProvider(receiverExpression)?.let { return it }
        }
        if (typeIndex.isTypeAssignableTo(callerOwner, BLOCK_ENTITY)) {
            blockEntityProvider("this")?.let { return it }
        }
        return null
    }

    private fun existingProviderName(callable: CallableDeclaration<*>): String? {
        val providers = callable.parameters.filter { isProvider(it.type) }.map { it.nameAsString }.distinct()
        if (providers.size > 1) {
            throw IllegalStateException("Ambiguous HolderLookup.Provider parameters in ${callable.nameAsString}")
        }
        return providers.singleOrNull()
    }

    private fun enclosingCallable(node: Node): CallableDeclaration<*>? {
        var current = node.parentNode.orElse(null)
        while (current != null) {
            if (current is MethodDeclaration || current is ConstructorDeclaration) {
                return current as CallableDeclaration<*>
            }
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun parameterInsertionOffset(unit: SourceUnit, method: MethodDeclaration, index: Int): Int {
        if (index < method.parameters.size) return offset(unit, method.parameters[index].range.orElseThrow().begin)
        return closeParenOffset(unit, method)
    }

    private fun argumentInsertionOffset(unit: SourceUnit, call: MethodCallExpr, index: Int): Int {
        if (index < call.arguments.size) return offset(unit, call.arguments[index].range.orElseThrow().begin)
        return offset(unit, call.range.orElseThrow().end)
    }

    private fun closeParenOffset(unit: SourceUnit, method: MethodDeclaration): Int {
        val start = offset(unit, method.name.range.orElseThrow().end) + 1
        val open = unit.source.indexOf('(', start)
        require(open >= 0)
        var depth = 0
        for (index in open until unit.source.length) {
            when (unit.source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("Cannot find method parameter close parenthesis in ${unit.file}")
    }

    private fun uniqueProviderName(method: MethodDeclaration, preferredName: String? = null): String {
        val identifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]*").findAll(method.toString()).map { it.value }.toSet()
        val baseName = preferredName
            ?.takeIf { Regex("[A-Za-z_$][A-Za-z0-9_$]*").matches(it) }
            ?: "registries"
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) baseName else "$baseName${suffix + 1}"
            if (candidate !in identifiers) return candidate
            suffix++
        }
    }

    private fun providerParameter(unit: SourceUnit, name: String): String {
        val hasHolderLookupImport = unit.compilationUnit.imports.any { import ->
            !import.isStatic &&
                (
                    (!import.isAsterisk && import.nameAsString == "net.minecraft.core.HolderLookup") ||
                        (import.isAsterisk && import.nameAsString == "net.minecraft.core")
                    )
        }
        val type = if (hasHolderLookupImport) "HolderLookup.Provider" else HOLDER_LOOKUP_PROVIDER
        return "$type $name"
    }

    private fun normalizeType(type: Type): String = type.asString().replace(Regex("\\s+"), "")
    private fun isProvider(type: Type): Boolean = normalizeType(type) in setOf(
        "HolderLookup.Provider",
        "net.minecraft.core.HolderLookup.Provider"
    )

    private companion object {
        const val HOLDER_LOOKUP_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
        const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
        const val REGISTRY_ACCESS = "net.minecraft.core.RegistryAccess"
        const val INVENTORY = "net.minecraft.world.entity.player.Inventory"
        const val BLOCK_ENTITY = "net.minecraft.world.level.block.entity.BlockEntity"
        const val MINECRAFT = "net.minecraft.client.Minecraft"
        val LEVEL_TYPES = setOf(
            "net.minecraft.world.level.Level",
            "net.minecraft.server.level.ServerLevel",
            "net.minecraft.world.level.WorldGenLevel",
            "net.minecraft.world.level.LevelAccessor",
            "net.minecraft.world.level.ServerLevelAccessor",
            "net.minecraft.world.level.LevelReader"
        )
        val ENTITY_TYPES = setOf(
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.server.level.ServerPlayer"
        )
    }

    private fun offset(unit: SourceUnit, position: Position): Int =
        unit.lineOffsets[position.line - 1] + position.column - 1

    private fun lineOffsets(source: String): IntArray {
        val result = mutableListOf(0)
        source.forEachIndexed { index, char -> if (char == '\n') result += index + 1 }
        return result.toIntArray()
    }
}
