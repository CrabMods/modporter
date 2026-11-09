// REVIEW DRAFT ONLY.
// Proposed production path:
// src/main/kotlin/com/modporter/core/transforms/structural/LegacyItemComponentPatchTransport.kt
package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Migrates one fully proven legacy ItemStack capability-transport family.
 *
 * The admitted source family stores a nullable ForgeCaps compound in a block
 * entity, obtains it only from ItemStack#serializeNBT, and consumes it only in
 * the paired drop and clone paths through the removed
 * ItemStack(ItemLike, int, CompoundTag) constructor. The migration captures the
 * complete DataComponentPatch instead. Legacy or undecodable bytes remain
 * opaque inside a versioned CUSTOM_DATA carrier and are never interpreted as
 * components.
 *
 * Admission is deliberately project-wide and closed. Any extra writer,
 * setter/getter caller, persistence-key mismatch, producer key other than
 * ForgeCaps, constructor shape, or ambiguous canonical binding leaves every
 * file unchanged and reports a gate.
 */
internal object LegacyItemComponentPatchTransport {
    const val RULE_ID = "struct-legacy-item-component-patch-transport"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    internal data class IoHooks(
        val readBytes: (Path) -> ByteArray = { Files.readAllBytes(it) },
        val atomicMove: (Path, Path) -> Unit = { source, target ->
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        }
    )

    private const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val LEVEL = "net.minecraft.world.level.Level"
    private const val BLOCK_GETTER = "net.minecraft.world.level.BlockGetter"
    private const val LEVEL_READER = "net.minecraft.world.level.LevelReader"
    private const val BLOCK_POS = "net.minecraft.core.BlockPos"
    private const val BLOCK = "net.minecraft.world.level.block.Block"
    private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
    private const val HIT_RESULT = "net.minecraft.world.phys.HitResult"
    private const val PLAYER = "net.minecraft.world.entity.player.Player"
    private const val LOOT_BUILDER = "net.minecraft.world.level.storage.loot.LootParams.Builder"
    private const val BLOCK_ENTITY = "net.minecraft.world.level.block.entity.BlockEntity"
    private const val ENTITY_BLOCK = "net.minecraft.world.level.block.EntityBlock"
    private const val IBE = "com.simibubi.create.foundation.block.IBE"
    private const val CONSUMER = "java.util.function.Consumer"

    private class CapturedSource(
        val path: Path,
        bytes: ByteArray,
        val source: String,
        val unit: CompilationUnit
    ) {
        private val capturedBytes = bytes.copyOf()

        fun bytes(): ByteArray = capturedBytes.copyOf()
    }

    private data class ParsedFile(
        val path: Path,
        val source: String,
        val unit: CompilationUnit,
        val projectTypes: Set<String>,
        val projectFacts: ProjectValueFacts,
        val valueTypes: SourceValueTypes,
        val exactIbeReceiverTypes: Map<String, String>
    ) {
        private val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, character ->
                if (character == '\n') add(index + 1)
            }
        }

        fun offset(node: Node): Int? = node.range.map {
            lineStarts.getOrNull(it.begin.line - 1)?.plus(it.begin.column - 1)
        }.orElse(null)
    }

    private data class PersistenceRead(
        val method: MethodDeclaration,
        val tagName: String,
        val assignment: AssignExpr,
        val statement: Statement
    )

    private enum class CanonicalBinding {
        CANONICAL,
        UNRELATED,
        AMBIGUOUS
    }

    private data class Carrier(
        val file: ParsedFile,
        val owner: ClassOrInterfaceDeclaration,
        val ownerName: String,
        val setter: MethodDeclaration,
        val capParameterIndex: Int,
        val getter: MethodDeclaration,
        val vanillaGetter: MethodDeclaration,
        val writer: MethodDeclaration,
        val writerTagName: String,
        val writerStatement: Statement,
        val reader: MethodDeclaration,
        val readerTagName: String,
        val readerStatement: Statement,
        val constructors: List<ConstructorDeclaration>
    )

    private data class Producer(
        val file: ParsedFile,
        val method: MethodDeclaration,
        val nbtStatement: ExpressionStmt,
        val capStatement: ExpressionStmt,
        val setterCall: MethodCallExpr,
        val stackName: String,
        val levelName: String
    )

    private data class DropConsumer(
        val file: ParsedFile,
        val method: MethodDeclaration,
        val block: BlockStmt,
        val firstIndex: Int,
        val getterScope: String,
        val legacyName: String,
        val lootName: String,
        val overlayName: String,
        val modifiedName: String,
        val itemExpression: String,
        val countExpression: String,
        val preservedFilter: String,
        val lootBuilderName: String
    )

    private data class CloneConsumer(
        val file: ParsedFile,
        val method: MethodDeclaration,
        val block: BlockStmt,
        val firstIndex: Int,
        val optionalExpression: String,
        val legacyDeclaration: String,
        val legacyName: String,
        val vanillaDeclaration: String,
        val vanillaName: String,
        val integerDeclaration: String,
        val integerName: String,
        val resultName: String,
        val itemExpression: String,
        val countExpression: String,
        val finalIntegerKey: String,
        val levelName: String
    )

    private data class Names(
        val rawField: String,
        val versionField: String,
        val opaqueField: String,
        val rawGetter: String,
        val versionGetter: String,
        val opaqueGetter: String,
        val encodeHelper: String,
        val applyHelper: String,
        val attachHelper: String,
        val detachHelper: String,
        val detachTagHelper: String,
        val rawCarrierHelper: String,
        val mergeOpaqueHelper: String,
        val carrierKeyField: String,
        val markerKeyField: String,
        val rawKeyField: String,
        val rawVersionKeyField: String,
        val legacyKeyField: String,
        val displacedKeyField: String,
        val composedKeyField: String,
        val persistedRawKeyField: String,
        val persistedVersionKeyField: String,
        val persistedOpaqueKeyField: String,
        val carrierKey: String,
        val markerKey: String,
        val rawKey: String,
        val rawVersionKey: String,
        val legacyKey: String,
        val displacedKey: String,
        val composedKey: String,
        val persistedRawKey: String,
        val persistedVersionKey: String,
        val persistedOpaqueKey: String
    )

    private data class Plan(
        val carrier: Carrier,
        val producer: Producer,
        val drop: DropConsumer,
        val clone: CloneConsumer,
        val names: Names
    )

    fun migrate(projectDir: Path, dryRun: Boolean): Result =
        migrate(projectDir, dryRun, IoHooks())

    internal fun migrateForTest(
        projectDir: Path,
        dryRun: Boolean,
        hooks: IoHooks
    ): Result = migrate(projectDir, dryRun, hooks)

    private fun migrate(projectDir: Path, dryRun: Boolean, hooks: IoHooks): Result {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return Result.EMPTY
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val files = try {
            JavaSourceText.walkJavaSourceRoots(sourceRoot).use { paths ->
                paths.filter { it.toString().endsWith(".java") }
                    .toList()
                    .sortedBy { it.toAbsolutePath().normalize().toString() }
            }
        } catch (failure: Exception) {
            return Result(
                emptyList(),
                listOf(
                    "Legacy item component Java sources could not be enumerated: " +
                        (failure.message ?: failure::class.java.name)
                )
            )
        }
        val captured = captureSources(files, parser, hooks.readBytes)
        if (captured.second.isNotEmpty()) {
            return Result(emptyList(), captured.second)
        }
        val snapshots = captured.first
        if (snapshots.none {
                it.source.contains("serializeNBT()") &&
                    it.source.contains("\"ForgeCaps\"")
            }
        ) return Result.EMPTY

        val originals = snapshots.associate { it.path to it.source }
        val originalBytes = snapshots.associate { it.path to it.bytes() }
        val clonePass = StructuralRefactorPass()
        val facts = runCatching {
            projectFactsFromSnapshot(clonePass, snapshots)
        }.getOrElse { failure ->
            return Result(
                emptyList(),
                listOf(
                    "Legacy item component snapshot facts could not be collected: " +
                        (failure.message ?: failure::class.java.name)
                )
            )
        }
        clonePass.projectValueFacts = facts
        val exactIbeReceiverTypes = exactIbeConsumerReceiverTypes(
            snapshots.map { it.unit },
            facts
        )
        val indexedOriginals = snapshots.map {
            ParsedFile(
                it.path,
                it.source,
                it.unit,
                facts.types,
                facts,
                SourceValueTypes(clonePass, it.source),
                exactIbeReceiverTypes
            )
        }
        val preflight = discover(indexedOriginals, facts)
        val originalPlan = preflight.first
            ?: return Result(emptyList(), preflight.second.ifEmpty {
                listOf("Legacy item component transport was present but its dataflow was not closed")
            })

        val cloneSource = clonePass.migrateLegacyBlockCloneItemStackOverloadFamily(
            originalPlan.clone.file.source
        )
        if (cloneSource == originalPlan.clone.file.source &&
            !cloneUsesLevelReader(originalPlan.clone)
        ) {
            return Result(
                emptyList(),
                listOf("Legacy item component clone family did not preflight to a LevelReader signature")
            )
        }
        val stagedSources = originals.toMutableMap()
        stagedSources[originalPlan.clone.file.path] = cloneSource
        val stagedParsed = mutableListOf<ParsedFile>()
        val stagedParseGates = mutableListOf<String>()
        for (original in indexedOriginals) {
            val staged = stagedSources.getValue(original.path)
            val parse = try {
                parser.parse(staged)
            } catch (failure: Exception) {
                stagedParseGates +=
                    "Legacy item component staged source parser failed for ${original.path}: " +
                        (failure.message ?: failure::class.java.name)
                continue
            }
            val unit = parse.result.orElse(null)
            if (!parse.isSuccessful || unit == null) {
                stagedParseGates +=
                    "Legacy item component staged source did not parse for ${original.path}: " +
                        parse.problems.joinToString("; ") { it.message }
            } else {
                stagedParsed += ParsedFile(
                    original.path,
                    staged,
                    unit,
                    facts.types,
                    facts,
                    SourceValueTypes(clonePass, staged),
                    exactIbeReceiverTypes
                )
            }
        }
        if (stagedParseGates.isNotEmpty()) return Result(emptyList(), stagedParseGates)
        val discovery = discover(stagedParsed, facts)
        val plan = discovery.first
            ?: return Result(emptyList(), discovery.second.ifEmpty {
                listOf("Legacy item component staged family no longer satisfies closed admission")
            })
        if (!cloneUsesLevelReader(plan.clone)) {
            return Result(
                emptyList(),
                listOf("Legacy item component provider use requires the clone LevelReader signature first")
            )
        }

        val affected = listOf(
            plan.carrier.file,
            plan.producer.file,
            plan.drop.file,
            plan.clone.file
        ).distinctBy { it.path }
        affected.forEach { LexicalPreservingPrinter.setup(it.unit) }

        addCarrierState(plan.carrier, plan.names)
        rewriteProducer(plan)
        rewriteDrop(plan)
        rewriteClone(plan)

        val migratedByPath = linkedMapOf<Path, String>()
        val outputParseGates = mutableListOf<String>()
        for (file in affected) {
            val migrated = LexicalPreservingPrinter.print(file.unit)
            val parsed = try {
                parser.parse(migrated)
            } catch (failure: Exception) {
                outputParseGates +=
                    "Legacy item component staged output parser failed for ${file.path}: " +
                        (failure.message ?: failure::class.java.name)
                continue
            }
            if (!parsed.isSuccessful || parsed.result.orElse(null) == null) {
                outputParseGates +=
                    "Legacy item component staged output did not parse for ${file.path}: " +
                        parsed.problems.joinToString("; ") { it.message }
            } else {
                migratedByPath[file.path] = migrated
            }
        }
        if (outputParseGates.isNotEmpty()) return Result(emptyList(), outputParseGates)
        if (!dryRun) writeAtomicFamily(migratedByPath, originalBytes, hooks.atomicMove)
        val changes = migratedByPath.mapNotNull { (path, migrated) ->
            if (migrated == originals.getValue(path)) return@mapNotNull null
            Change(
                file = path,
                line = 1,
                description = "Transport the complete ItemStack component patch through the proven block-entity lifecycle",
                before = "serializeNBT ForgeCaps compound and ItemStack(ItemLike, int, CompoundTag)",
                after = "versioned DataComponentPatch plus opaque legacy-byte carrier",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
        return Result(changes, emptyList())
    }

    private fun captureSources(
        files: List<Path>,
        parser: JavaParser,
        readBytes: (Path) -> ByteArray
    ): Pair<List<CapturedSource>, List<String>> {
        val captured = mutableListOf<CapturedSource>()
        val gates = mutableListOf<String>()
        for (file in files) {
            val bytes = try {
                readBytes(file).copyOf()
            } catch (failure: Exception) {
                gates +=
                    "Legacy item component source could not be captured for $file: " +
                        (failure.message ?: failure::class.java.name)
                continue
            }
            val source = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (failure: Exception) {
                gates +=
                    "Legacy item component source is not strict UTF-8 for $file: " +
                        (failure.message ?: failure::class.java.name)
                continue
            }
            val parse = try {
                parser.parse(source)
            } catch (failure: Exception) {
                gates +=
                    "Legacy item component source parser failed for $file: " +
                        (failure.message ?: failure::class.java.name)
                continue
            }
            val unit = parse.result.orElse(null)
            if (!parse.isSuccessful || unit == null) {
                gates +=
                    "Legacy item component source did not parse for $file: " +
                        parse.problems.joinToString("; ") { it.message }
                continue
            }
            captured += CapturedSource(file, bytes, source, unit)
        }
        return captured to gates
    }

    private fun projectFactsFromSnapshot(
        pass: StructuralRefactorPass,
        snapshots: List<CapturedSource>
    ): ProjectValueFacts {
        val snapshotRoot = Files.createTempDirectory("modporter-component-facts-")
        try {
            val mirrorFiles = snapshots.mapIndexed { index, snapshot ->
                snapshotRoot.resolve("snapshot-${index.toString().padStart(6, '0')}.java").also {
                    Files.write(it, snapshot.bytes())
                }
            }
            return ProjectValueFacts.collect(pass, mirrorFiles)
        } finally {
            runCatching {
                Files.walk(snapshotRoot).use { paths ->
                    paths.toList()
                        .sortedByDescending { it.nameCount }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    private fun discover(
        files: List<ParsedFile>,
        facts: ProjectValueFacts
    ): Pair<Plan?, List<String>> {
        val gates = mutableListOf<String>()
        val carriers = files.flatMap { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { !it.isInterface }
                .mapNotNull { owner ->
                    val canonicalOwner = qualifiedNamedOwner(owner, file.unit)
                        ?: return@mapNotNull null
                    if (!inheritsFromBlockEntity(canonicalOwner, facts)) return@mapNotNull null
                    carrier(file, owner, canonicalOwner)
                }
        }
        if (carriers.size != 1) {
            gates += "Legacy item component transport requires exactly one proven nullable CompoundTag carrier; found ${carriers.size}"
            return null to gates
        }
        val carrier = carriers.single()

        val setterUses = files.flatMap { file ->
            file.unit.findAll(MethodCallExpr::class.java)
                .filter {
                    it.nameAsString == carrier.setter.nameAsString &&
                        it.arguments.size == carrier.setter.parameters.size
                }
                .map { Triple(file, it, it.receiverBinding(file, carrier.ownerName)) }
        }
        val ambiguousSetterUses = setterUses.filter { it.third == CanonicalBinding.AMBIGUOUS }
        if (ambiguousSetterUses.isNotEmpty()) {
            gates +=
                "Legacy item component carrier setter has ${ambiguousSetterUses.size} " +
                    "lexically ambiguous receiver binding(s)"
            return null to gates
        }
        val setterCalls = setterUses.filter { it.third == CanonicalBinding.CANONICAL }
            .map { it.first to it.second }
        if (setterCalls.size != 1) {
            gates += "Legacy item component carrier setter has ${setterCalls.size} callers; exactly one is required"
            return null to gates
        }
        val producer = producer(setterCalls.single().first, setterCalls.single().second, carrier)
        if (producer == null) {
            gates += "Legacy item component producer is not serializeNBT -> exact ForgeCaps -> sole setter"
            return null to gates
        }

        val getterCallUses = files.flatMap { file ->
            file.unit.findAll(MethodCallExpr::class.java)
                .filter {
                    it.nameAsString == carrier.getter.nameAsString &&
                        it.arguments.isEmpty()
                }
                .map { Triple(file, it, it.receiverBinding(file, carrier.ownerName)) }
        }
        val getterReferenceUses = files.flatMap { file ->
            file.unit.findAll(MethodReferenceExpr::class.java)
                .filter {
                    it.identifier == carrier.getter.nameAsString
                }
                .map { Triple(file, it, it.receiverBinding(file, carrier.ownerName)) }
        }
        val ambiguousGetterUses =
            getterCallUses.count { it.third == CanonicalBinding.AMBIGUOUS } +
                getterReferenceUses.count { it.third == CanonicalBinding.AMBIGUOUS }
        if (ambiguousGetterUses != 0) {
            gates +=
                "Legacy item component carrier getter has $ambiguousGetterUses " +
                    "lexically ambiguous receiver binding(s)"
            return null to gates
        }
        val getterCalls = getterCallUses.filter { it.third == CanonicalBinding.CANONICAL }
            .map { it.first to it.second }
        val getterReferences = getterReferenceUses.filter { it.third == CanonicalBinding.CANONICAL }
            .map { it.first to it.second }
        if (getterCalls.size != 1 || getterReferences.size != 1) {
            gates += "Legacy item component getter consumers are not exactly one drop call and one clone method reference"
            return null to gates
        }
        val drop = dropConsumer(
            getterCalls.single().first,
            getterCalls.single().second,
            carrier
        )
        val clone = cloneConsumer(
            getterReferences.single().first,
            getterReferences.single().second,
            carrier,
            facts
        )
        if (drop == null) gates += "Legacy item component drop consumer is not the recognized three-argument ItemStack overlay"
        if (clone == null) gates += "Legacy item component clone consumer is not the recognized three-argument ItemStack/custom-tag sequence"
        if (drop == null || clone == null) return null to gates

        return Plan(carrier, producer, drop, clone, namesFor(carrier.owner)) to emptyList()
    }

    private fun carrier(
        file: ParsedFile,
        owner: ClassOrInterfaceDeclaration,
        canonicalOwner: String
    ): Carrier? {
        val fields = owner.fields.flatMap { it.variables }
            .filter { file.isType(it.typeAsString, COMPOUND_TAG, it) }
        val candidates = fields.mapNotNull { field ->
            carrierForField(file, owner, canonicalOwner, field, fields)
        }
        return candidates.singleOrNull()
    }

    private fun carrierForField(
        file: ParsedFile,
        owner: ClassOrInterfaceDeclaration,
        canonicalOwner: String,
        field: VariableDeclarator,
        compoundFields: List<VariableDeclarator>
    ): Carrier? {
        val fieldName = field.nameAsString
        if (owner.findAll(VariableDeclarator::class.java).any {
                it !== field && it.nameAsString == fieldName
            }
        ) return null

        val constructors = owner.constructors
        if (constructors.isEmpty()) return null
        val constructorAssignments = constructors.associateWith { constructor ->
            constructor.findAll(AssignExpr::class.java).filter {
                it.target.isField(fieldName) && it.value is NullLiteralExpr
            }
        }
        if (constructorAssignments.any { it.value.size != 1 }) return null

        val setterMatches = owner.methods.mapNotNull { method ->
            val assignment = method.findAll(AssignExpr::class.java)
                .singleOrNull { it.target.isField(fieldName) } ?: return@mapNotNull null
            val parameterName = nullableCopyParameter(assignment.value, method) ?: return@mapNotNull null
            Triple(method, parameterName, assignment)
        }
        if (setterMatches.size != 1) return null
        val (setter, capParameter, setterAssignment) = setterMatches.single()
        if (setter.parameters.size != 2 ||
            setter.parameters.any { !file.isType(it.typeAsString, COMPOUND_TAG, it) }
        ) return null
        val capIndex = setter.parameters.indexOfFirst { it.nameAsString == capParameter }
        if (capIndex < 0) return null
        val vanillaParameter = setter.parameters[1 - capIndex].nameAsString
        val vanillaAssignments = setter.findAll(AssignExpr::class.java).filter { assignment ->
            val target = assignment.target.fieldName() ?: return@filter false
            target != fieldName && assignment.value.isCopyOf(vanillaParameter)
        }
        if (vanillaAssignments.size != 1) return null
        val vanillaFieldName = vanillaAssignments.single().target.fieldName() ?: return null
        val vanillaField = compoundFields.singleOrNull { it.nameAsString == vanillaFieldName } ?: return null

        val getter = owner.methods.singleOrNull { it.returnsField(fieldName, file, COMPOUND_TAG) }
            ?: return null
        val vanillaGetter = owner.methods.singleOrNull {
            it.returnsField(vanillaFieldName, file, COMPOUND_TAG)
        } ?: return null

        val writes = owner.methods.flatMap { method ->
            method.findAll(MethodCallExpr::class.java).mapNotNull { call ->
                if (call.nameAsString != "put" || call.arguments.size != 2 ||
                    call.arguments[0] !is StringLiteralExpr ||
                    !call.arguments[1].isField(fieldName)
                ) return@mapNotNull null
                val scope = call.scope.orElse(null) as? NameExpr ?: return@mapNotNull null
                val parameter = method.parameters.singleOrNull {
                    it.nameAsString == scope.nameAsString &&
                        file.isType(it.typeAsString, COMPOUND_TAG, it)
                } ?: return@mapNotNull null
                val statement = method.body.orElse(null)?.let { topLevelStatement(call, it) }
                    ?: return@mapNotNull null
                val guarded = call.findAncestor(IfStmt::class.java).orElse(null)
                    ?.condition?.findAll(NameExpr::class.java)
                    ?.any { it.nameAsString == fieldName } == true
                if (!guarded) return@mapNotNull null
                listOf(
                    method,
                    parameter.nameAsString,
                    (call.arguments[0] as StringLiteralExpr).value,
                    statement
                )
            }
        }
        if (writes.size != 1) return null
        @Suppress("UNCHECKED_CAST")
        val write = writes.single()
        val writer = write[0] as MethodDeclaration
        val writerTag = write[1] as String
        val key = write[2] as String
        val writerStatement = write[3] as Statement

        val reads = owner.methods.flatMap { method ->
            method.findAll(AssignExpr::class.java).mapNotNull { assignment ->
                if (!assignment.target.isField(fieldName)) return@mapNotNull null
                val read = nullableCompoundRead(assignment.value, method, key, file)
                    ?: return@mapNotNull null
                val statement = method.body.orElse(null)?.let { topLevelStatement(assignment, it) }
                    ?: return@mapNotNull null
                PersistenceRead(method, read, assignment, statement)
            }
        }
        if (reads.size != 1) return null
        val read = reads.single()

        val allowedAssignments = constructorAssignments.values.flatten().toSet() +
            setterAssignment + read.assignment
        val fieldReferences = owner.findAll(Expression::class.java).filter {
            it.isField(fieldName) &&
                it.nearestOwner() === owner
        }
        val allReferencesKnown = fieldReferences.all { reference ->
            val assignment = reference.findAncestor(AssignExpr::class.java).orElse(null)
            if (assignment in allowedAssignments) return@all true
            if (reference.findAncestor(ReturnStmt::class.java).orElse(null)
                    ?.findAncestor(MethodDeclaration::class.java)?.orElse(null) === getter
            ) return@all true
            val call = reference.findAncestor(MethodCallExpr::class.java).orElse(null)
            if (call != null && call.nameAsString == "put" && call.arguments.any { it === reference }) {
                return@all call.findAncestor(MethodDeclaration::class.java).orElse(null) === writer
            }
            val guard = reference.findAncestor(IfStmt::class.java).orElse(null)
            guard != null && guard.condition.isAncestorOf(reference) &&
                guard.findAncestor(MethodDeclaration::class.java).orElse(null) === writer
        }
        if (!allReferencesKnown) return null

        return Carrier(
            file = file,
            owner = owner,
            ownerName = canonicalOwner,
            setter = setter,
            capParameterIndex = capIndex,
            getter = getter,
            vanillaGetter = vanillaGetter,
            writer = writer,
            writerTagName = writerTag,
            writerStatement = writerStatement,
            reader = read.method,
            readerTagName = read.tagName,
            readerStatement = read.statement,
            constructors = constructors
        )
    }

    private fun producer(
        file: ParsedFile,
        setterCall: MethodCallExpr,
        carrier: Carrier
    ): Producer? {
        val method = setterCall.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
        val setterReceiver = setterCall.scope.orElse(null) as? NameExpr ?: return null
        if (setterCall.receiverBinding(file, carrier.ownerName) != CanonicalBinding.CANONICAL) {
            return null
        }
        val capArgument = setterCall.arguments[carrier.capParameterIndex] as? NameExpr ?: return null
        val capVariable = method.findAll(VariableDeclarator::class.java)
            .singleOrNull { it.nameAsString == capArgument.nameAsString } ?: return null
        val conditional = capVariable.initializer.orElse(null) as? ConditionalExpr ?: return null
        val nbtName = exactForgeCapsRead(conditional) ?: return null
        if (method.findAll(NameExpr::class.java).count { it.nameAsString == capVariable.nameAsString } != 1) {
            return null
        }
        val nbtVariable = method.findAll(VariableDeclarator::class.java)
            .singleOrNull { it.nameAsString == nbtName } ?: return null
        val serialize = nbtVariable.initializer.orElse(null) as? MethodCallExpr ?: return null
        if (serialize.nameAsString != "serializeNBT" || serialize.arguments.isNotEmpty()) return null
        val stack = serialize.scope.orElse(null) as? NameExpr ?: return null
        if (method.findAll(NameExpr::class.java).count { it.nameAsString == nbtName } != 2) return null
        if (serialize.receiverBinding(file, ITEM_STACK) != CanonicalBinding.CANONICAL) return null
        val level = method.parameters.singleOrNull {
            file.isType(it.typeAsString, LEVEL, it)
        } ?: return null
        val nbtStatement = nbtVariable.findAncestor(ExpressionStmt::class.java).orElse(null) ?: return null
        val capStatement = capVariable.findAncestor(ExpressionStmt::class.java).orElse(null) ?: return null
        val block = nbtStatement.parentNode.orElse(null) as? BlockStmt ?: return null
        if (capStatement.parentNode.orElse(null) !== block) return null
        val nbtIndex = block.statements.indexOf(nbtStatement)
        if (nbtIndex < 0 || block.statements.getOrNull(nbtIndex + 1) !== capStatement) return null
        if (setterCall.findAncestor(ExpressionStmt::class.java).orElse(null)
                ?.parentNode?.orElse(null) !== block
        ) return null
        return Producer(
            file,
            method,
            nbtStatement,
            capStatement,
            setterCall,
            stack.nameAsString,
            level.nameAsString
        )
    }

    private fun dropConsumer(
        file: ParsedFile,
        getterCall: MethodCallExpr,
        carrier: Carrier
    ): DropConsumer? {
        val method = getterCall.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
        val legacyVariable = getterCall.findAncestor(VariableDeclarator::class.java).orElse(null)
            ?: return null
        if (legacyVariable.initializer.orElse(null) !== getterCall) return null
        val legacyStatement = legacyVariable.findAncestor(ExpressionStmt::class.java).orElse(null)
            ?: return null
        val block = legacyStatement.parentNode.orElse(null) as? BlockStmt ?: return null
        val firstIndex = block.statements.indexOf(legacyStatement)
        if (firstIndex < 0 || block.statements.size != firstIndex + 3) return null
        val nullGuard = block.statements[firstIndex + 1] as? IfStmt ?: return null
        if (!nullGuard.condition.isNullComparison(legacyVariable.nameAsString)) return null
        val nullReturn = nullGuard.thenStmt.unwrapSingle() as? ReturnStmt ?: return null
        val lootName = (nullReturn.expression.orElse(null) as? NameExpr)?.nameAsString ?: return null
        val streamReturn = block.statements[firstIndex + 2] as? ReturnStmt ?: return null
        val lambda = streamReturn.findAll(LambdaExpr::class.java).singleOrNull() ?: return null
        if (lambda.parameters.size != 1) return null
        val overlayName = lambda.parameters.single().nameAsString
        val lambdaBlock = lambda.body as? BlockStmt ?: return null
        if (lambdaBlock.statements.size != 4) return null
        val filter = lambdaBlock.statements[0] as? IfStmt ?: return null
        val creationStatement = lambdaBlock.statements[1] as? ExpressionStmt ?: return null
        val modifiedVariable = creationStatement.findAll(VariableDeclarator::class.java)
            .singleOrNull() ?: return null
        val creation = modifiedVariable.initializer.orElse(null) as? ObjectCreationExpr ?: return null
        if (!file.isType(creation.type.asString(), ITEM_STACK, creation) ||
            creation.arguments.size != 3
        ) {
            return null
        }
        val capCopy = creation.arguments[2] as? MethodCallExpr ?: return null
        if (capCopy.nameAsString != "copy" ||
            (capCopy.scope.orElse(null) as? NameExpr)?.nameAsString != legacyVariable.nameAsString
        ) return null
        val itemCall = creation.arguments[0] as? MethodCallExpr ?: return null
        val countCall = creation.arguments[1] as? MethodCallExpr ?: return null
        if (itemCall.nameAsString != "getItem" || countCall.nameAsString != "getCount" ||
            itemCall.scope.map { it.toString() }.orElse("") != overlayName ||
            countCall.scope.map { it.toString() }.orElse("") != overlayName
        ) return null
        val overlayStatement = lambdaBlock.statements[2] as? ExpressionStmt ?: return null
        val setTag = overlayStatement.expression as? MethodCallExpr ?: return null
        if (setTag.nameAsString != "setTag" ||
            setTag.scope.map { it.toString() }.orElse("") != modifiedVariable.nameAsString ||
            setTag.arguments.size != 1
        ) return null
        val getTag = setTag.arguments.single() as? MethodCallExpr ?: return null
        if (getTag.nameAsString != "getTag" ||
            getTag.scope.map { it.toString() }.orElse("") != overlayName
        ) return null
        val finalReturn = lambdaBlock.statements[3] as? ReturnStmt ?: return null
        if (finalReturn.expression.map { it.toString() }.orElse("") != modifiedVariable.nameAsString) {
            return null
        }
        val lootBuilder = method.parameters.singleOrNull {
            file.isType(it.typeAsString, LOOT_BUILDER, it)
        } ?: return null
        val scope = getterCall.scope.orElse(null)?.toString() ?: return null
        if (getterCall.receiverBinding(file, carrier.ownerName) != CanonicalBinding.CANONICAL) {
            return null
        }
        return DropConsumer(
            file,
            method,
            block,
            firstIndex,
            scope,
            legacyVariable.nameAsString,
            lootName,
            overlayName,
            modifiedVariable.nameAsString,
            creation.arguments[0].toString(),
            creation.arguments[1].toString(),
            filter.toString(),
            lootBuilder.nameAsString
        )
    }

    private fun cloneConsumer(
        file: ParsedFile,
        getterReference: MethodReferenceExpr,
        carrier: Carrier,
        facts: ProjectValueFacts
    ): CloneConsumer? {
        val method = getterReference.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return null
        if (method.nameAsString != "getCloneItemStack" ||
            method.annotations.none { it.name.identifier == "Override" } ||
            !file.isType(method.typeAsString, ITEM_STACK, method)
        ) return null
        val cloneOwner = method.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .orElse(null) ?: return null
        val canonicalCloneOwner = qualifiedNamedOwner(cloneOwner, file.unit) ?: return null
        if (!inheritsFrom(canonicalCloneOwner, BLOCK, facts)) return null
        val parameterTypes = method.parameters.map { it.typeAsString }
        val threeArgumentShape = parameterTypes.size == 3 &&
            file.isEitherType(
                parameterTypes[0],
                BLOCK_GETTER,
                LEVEL_READER,
                method.parameters[0]
            ) &&
            file.isType(parameterTypes[1], BLOCK_POS, method.parameters[1]) &&
            file.isType(parameterTypes[2], BLOCK_STATE, method.parameters[2])
        val fiveArgumentShape = parameterTypes.size == 5 &&
            file.isType(parameterTypes[0], BLOCK_STATE, method.parameters[0]) &&
            file.isType(parameterTypes[1], HIT_RESULT, method.parameters[1]) &&
            file.isEitherType(
                parameterTypes[2],
                BLOCK_GETTER,
                LEVEL_READER,
                method.parameters[2]
            ) &&
            file.isType(parameterTypes[3], BLOCK_POS, method.parameters[3]) &&
            file.isType(parameterTypes[4], PLAYER, method.parameters[4])
        if (!threeArgumentShape && !fiveArgumentShape) return null
        if (getterReference.receiverBinding(file, carrier.ownerName) != CanonicalBinding.CANONICAL) {
            return null
        }
        val legacyVariable = getterReference.findAncestor(VariableDeclarator::class.java).orElse(null)
            ?: return null
        val legacyStatement = legacyVariable.findAncestor(ExpressionStmt::class.java).orElse(null)
            ?: return null
        val block = legacyStatement.parentNode.orElse(null) as? BlockStmt ?: return null
        val firstIndex = block.statements.indexOf(legacyStatement)
        if (firstIndex < 0 || block.statements.size != firstIndex + 7) return null

        val initializer = legacyVariable.initializer.orElse(null)?.toString()
            ?.replace(Regex("""\s+"""), "") ?: return null
        val getterName = Regex.escape(carrier.getter.nameAsString)
        val optionalMatch = Regex(
            """^(.+)\.map\((?:[\w$.]+)::${getterName}\)\.map\((?:[\w$.]+\.)?CompoundTag::copy\)\.orElse\(null\)$"""
        ).matchEntire(initializer) ?: return null
        val optionalExpression = optionalMatch.groupValues[1]
        if (!optionalExpression.matches(Regex("""[A-Za-z_$][\w$]*"""))) return null

        val vanillaStatement = block.statements[firstIndex + 1] as? ExpressionStmt ?: return null
        val vanillaVariable = vanillaStatement.findAll(VariableDeclarator::class.java)
            .singleOrNull() ?: return null
        val vanillaReference = vanillaVariable.findAll(MethodReferenceExpr::class.java)
            .singleOrNull { it.identifier == carrier.vanillaGetter.nameAsString } ?: return null
        if (vanillaReference.scope.toString() != getterReference.scope.toString()) return null
        val normalizedVanilla = vanillaVariable.initializer.orElse(null)?.toString()
            ?.replace(Regex("""\s+"""), "") ?: return null
        val vanillaGetter = Regex.escape(carrier.vanillaGetter.nameAsString)
        if (!normalizedVanilla.matches(Regex(
                """${Regex.escape(optionalExpression)}\.map\((?:[\w$.]+)::$vanillaGetter\)""" +
                    """\.map\((?:[\w$.]+\.)?CompoundTag::copy\)\.orElse\(new(?:[\w$.]+\.)?CompoundTag\(\)\)"""
            ))
        ) return null

        val integerStatement = block.statements[firstIndex + 2] as? ExpressionStmt ?: return null
        val integerVariable = integerStatement.findAll(VariableDeclarator::class.java)
            .singleOrNull { it.typeAsString == "int" } ?: return null
        val integerReference = integerVariable.findAll(MethodReferenceExpr::class.java)
            .singleOrNull() ?: return null
        if (integerReference.scope.toString() != getterReference.scope.toString()) return null
        val normalizedInteger = integerVariable.initializer.orElse(null)?.toString()
            ?.replace(Regex("""\s+"""), "") ?: return null
        if (!normalizedInteger.matches(Regex(
                """${Regex.escape(optionalExpression)}\.map\((?:[\w$.]+)::""" +
                    """[A-Za-z_$][\w$]*\)\.orElse\([^()]*\)"""
            ))
        ) return null
        val creationStatement = block.statements[firstIndex + 3] as? ExpressionStmt ?: return null
        val resultVariable = creationStatement.findAll(VariableDeclarator::class.java)
            .singleOrNull() ?: return null
        val creation = resultVariable.initializer.orElse(null) as? ObjectCreationExpr ?: return null
        if (!file.isType(creation.type.asString(), ITEM_STACK, creation) ||
            creation.arguments.size != 3 ||
            creation.arguments[2].toString() != legacyVariable.nameAsString
        ) return null

        val putIntStatement = block.statements[firstIndex + 4] as? ExpressionStmt ?: return null
        val putInt = putIntStatement.expression as? MethodCallExpr ?: return null
        if (putInt.nameAsString != "putInt" ||
            putInt.scope.map { it.toString() }.orElse("") != vanillaVariable.nameAsString ||
            putInt.arguments.size != 2 ||
            putInt.arguments[0] !is StringLiteralExpr ||
            putInt.arguments[1].toString() != integerVariable.nameAsString
        ) return null
        val setTagStatement = block.statements[firstIndex + 5] as? ExpressionStmt ?: return null
        val setTag = setTagStatement.expression as? MethodCallExpr ?: return null
        if (setTag.nameAsString != "setTag" ||
            setTag.scope.map { it.toString() }.orElse("") != resultVariable.nameAsString ||
            setTag.arguments.singleOrNull()?.toString() != vanillaVariable.nameAsString
        ) return null
        val returned = block.statements[firstIndex + 6] as? ReturnStmt ?: return null
        if (returned.expression.map { it.toString() }.orElse("") != resultVariable.nameAsString) {
            return null
        }
        val levelParameter = method.parameters.singleOrNull {
            file.isType(it.typeAsString, BLOCK_GETTER, it) ||
                file.isType(it.typeAsString, LEVEL_READER, it)
        } ?: return null

        return CloneConsumer(
            file,
            method,
            block,
            firstIndex,
            optionalExpression,
            legacyStatement.toString(),
            legacyVariable.nameAsString,
            vanillaStatement.toString(),
            vanillaVariable.nameAsString,
            integerStatement.toString(),
            integerVariable.nameAsString,
            resultVariable.nameAsString,
            creation.arguments[0].toString(),
            creation.arguments[1].toString(),
            (putInt.arguments[0] as StringLiteralExpr).value,
            levelParameter.nameAsString
        )
    }

    private fun addCarrierState(carrier: Carrier, names: Names) {
        val owner = carrier.owner
        owner.addMember(StaticJavaParser.parseBodyDeclaration(
            "private net.minecraft.nbt.Tag ${names.rawField};"
        ))
        owner.addMember(StaticJavaParser.parseBodyDeclaration(
            "private int ${names.versionField};"
        ))
        owner.addMember(StaticJavaParser.parseBodyDeclaration(
            "private net.minecraft.nbt.CompoundTag ${names.opaqueField};"
        ))
        listOf(
            names.carrierKeyField to names.carrierKey,
            names.markerKeyField to names.markerKey,
            names.rawKeyField to names.rawKey,
            names.rawVersionKeyField to names.rawVersionKey,
            names.legacyKeyField to names.legacyKey,
            names.displacedKeyField to names.displacedKey,
            names.composedKeyField to names.composedKey,
            names.persistedRawKeyField to names.persistedRawKey,
            names.persistedVersionKeyField to names.persistedVersionKey,
            names.persistedOpaqueKeyField to names.persistedOpaqueKey
        ).forEach { (field, value) ->
            owner.addMember(StaticJavaParser.parseBodyDeclaration(
                "private static final String $field = ${javaString(value)};"
            ))
        }
        carrier.constructors.forEach { constructor ->
            constructor.body.addStatement("this.${names.rawField} = null;")
            constructor.body.addStatement("this.${names.versionField} = 0;")
            constructor.body.addStatement("this.${names.opaqueField} = null;")
        }
        carrier.setter.addParameter("int", names.versionField)
        carrier.setter.addParameter("net.minecraft.nbt.Tag", names.rawField)
        carrier.setter.addParameter("net.minecraft.nbt.CompoundTag", names.opaqueField)
        carrier.setter.body.orElseThrow().addStatement(
            "this.${names.versionField} = ${names.versionField};"
        )
        carrier.setter.body.orElseThrow().addStatement(
            "this.${names.rawField} = ${names.rawField} == null ? null : ${names.rawField}.copy();"
        )
        carrier.setter.body.orElseThrow().addStatement(
            "this.${names.opaqueField} = ${names.opaqueField} == null ? null : ${names.opaqueField}.copy();"
        )

        val writeBlock = carrier.writer.body.orElseThrow()
        val writeIndex = writeBlock.statements.indexOf(carrier.writerStatement)
        writeBlock.addStatement(writeIndex + 1, StaticJavaParser.parseStatement("""
            if (this.${names.rawField} != null) {
                ${carrier.writerTagName}.putInt(${names.persistedVersionKeyField}, this.${names.versionField});
                ${carrier.writerTagName}.put(${names.persistedRawKeyField}, this.${names.rawField}.copy());
            }
        """.trimIndent()))
        writeBlock.addStatement(writeIndex + 2, StaticJavaParser.parseStatement("""
            if (this.${names.opaqueField} != null)
                ${carrier.writerTagName}.put(
                    ${names.persistedOpaqueKeyField}, this.${names.opaqueField}.copy()
                );
        """.trimIndent()))
        val readBlock = carrier.reader.body.orElseThrow()
        val readIndex = readBlock.statements.indexOf(carrier.readerStatement)
        readBlock.addStatement(
            readIndex + 1,
            StaticJavaParser.parseStatement(
                "this.${names.versionField} = ${carrier.readerTagName}.contains(" +
                    "${names.persistedVersionKeyField}, net.minecraft.nbt.Tag.TAG_INT) ? " +
                    "${carrier.readerTagName}.getInt(${names.persistedVersionKeyField}) : 0;"
            )
        )
        readBlock.addStatement(
            readIndex + 2,
            StaticJavaParser.parseStatement(
                "this.${names.rawField} = ${carrier.readerTagName}.get(${names.persistedRawKeyField});"
            )
        )
        readBlock.addStatement(
            readIndex + 3,
            StaticJavaParser.parseStatement(
                "if (this.${names.rawField} != null) this.${names.rawField} = this.${names.rawField}.copy();"
            )
        )
        readBlock.addStatement(
            readIndex + 4,
            StaticJavaParser.parseStatement(
                "this.${names.opaqueField} = ${carrier.readerTagName}.contains(" +
                    "${names.persistedOpaqueKeyField}, net.minecraft.nbt.Tag.TAG_COMPOUND) ? " +
                    "${carrier.readerTagName}.getCompound(${names.persistedOpaqueKeyField}).copy() : null;"
            )
        )

        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            public net.minecraft.nbt.Tag ${names.rawGetter}() {
                return this.${names.rawField};
            }
        """.trimIndent()))
        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            public int ${names.versionGetter}() {
                return this.${names.versionField};
            }
        """.trimIndent()))
        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            public net.minecraft.nbt.CompoundTag ${names.opaqueGetter}() {
                return this.${names.opaqueField};
            }
        """.trimIndent()))
        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            public static net.minecraft.nbt.Tag ${names.encodeHelper}(
                net.minecraft.world.item.ItemStack stack,
                net.minecraft.core.HolderLookup.Provider registries
            ) {
                return net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(
                    registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                    stack.getComponentsPatch()
                ).getOrThrow();
            }
        """.trimIndent()))
        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            private static net.minecraft.nbt.CompoundTag ${names.rawCarrierHelper}(
                int version,
                net.minecraft.nbt.Tag raw
            ) {
                net.minecraft.nbt.CompoundTag carrier = new net.minecraft.nbt.CompoundTag();
                carrier.putInt(${names.markerKeyField}, 1);
                carrier.putInt(${names.rawVersionKeyField}, version);
                carrier.put(${names.rawKeyField}, raw.copy());
                return carrier;
            }
        """.trimIndent()))
        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            public static net.minecraft.nbt.CompoundTag ${names.detachTagHelper}(
                net.minecraft.nbt.CompoundTag root
            ) {
                net.minecraft.nbt.Tag value = root.get(${names.carrierKeyField});
                if (!(value instanceof net.minecraft.nbt.CompoundTag)) {
                    return null;
                }
                net.minecraft.nbt.CompoundTag carrier =
                    (net.minecraft.nbt.CompoundTag) value;
                if (carrier.getInt(${names.markerKeyField}) != 1 ||
                    (!carrier.contains(${names.rawKeyField}) &&
                        !carrier.contains(${names.legacyKeyField}))) {
                    return null;
                }
                net.minecraft.nbt.CompoundTag transport = carrier.copy();
                net.minecraft.nbt.Tag displaced = transport.get(${names.displacedKeyField});
                transport.remove(${names.displacedKeyField});
                root.remove(${names.carrierKeyField});
                if (displaced != null)
                    root.put(${names.carrierKeyField}, displaced.copy());
                return transport;
            }
        """.trimIndent()))
        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            public static net.minecraft.nbt.CompoundTag ${names.detachHelper}(
                net.minecraft.world.item.ItemStack stack
            ) {
                net.minecraft.nbt.CompoundTag root = stack.getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY
                ).copyTag();
                net.minecraft.nbt.CompoundTag transport = ${names.detachTagHelper}(root);
                if (transport == null)
                    return null;
                stack.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(root)
                );
                return transport;
            }
        """.trimIndent()))
        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            public static net.minecraft.nbt.CompoundTag ${names.applyHelper}(
                net.minecraft.world.item.ItemStack stack,
                net.minecraft.core.HolderLookup.Provider registries,
                int version,
                net.minecraft.nbt.Tag raw
            ) {
                if (raw == null)
                    return null;
                if (version != 1)
                    return ${names.rawCarrierHelper}(version, raw);
                java.util.Optional<net.minecraft.core.component.DataComponentPatch> decoded =
                    net.minecraft.core.component.DataComponentPatch.CODEC.parse(
                        registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                        raw
                    ).result();
                if (decoded.isEmpty())
                    return ${names.rawCarrierHelper}(version, raw);
                stack.applyComponents(decoded.get());
                return ${names.detachHelper}(stack);
            }
        """.trimIndent()))
        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            public static net.minecraft.nbt.CompoundTag ${names.mergeOpaqueHelper}(
                net.minecraft.nbt.CompoundTag primary,
                net.minecraft.nbt.CompoundTag additional
            ) {
                if (primary == null)
                    return additional == null ? null : additional.copy();
                if (additional == null || primary.equals(additional))
                    return primary.copy();
                net.minecraft.nbt.CompoundTag merged = primary.copy();
                net.minecraft.nbt.CompoundTag composition =
                    new net.minecraft.nbt.CompoundTag();
                net.minecraft.nbt.Tag displaced = merged.get(${names.composedKeyField});
                if (displaced != null)
                    composition.put(${names.displacedKeyField}, displaced.copy());
                composition.put(${names.carrierKeyField}, additional.copy());
                merged.put(${names.composedKeyField}, composition);
                return merged;
            }
        """.trimIndent()))
        owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            public static void ${names.attachHelper}(
                net.minecraft.world.item.ItemStack stack,
                net.minecraft.nbt.CompoundTag opaque,
                net.minecraft.nbt.CompoundTag legacy
            ) {
                if (opaque == null && legacy == null)
                    return;
                net.minecraft.nbt.CompoundTag carrier =
                    opaque == null ? new net.minecraft.nbt.CompoundTag() : opaque.copy();
                carrier.putInt(${names.markerKeyField}, 1);
                if (legacy != null && !carrier.contains(${names.legacyKeyField}))
                    carrier.put(${names.legacyKeyField}, legacy.copy());
                net.minecraft.nbt.CompoundTag root = stack.getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY
                ).copyTag();
                net.minecraft.nbt.Tag displaced = root.get(${names.carrierKeyField});
                if (displaced == null)
                    carrier.remove(${names.displacedKeyField});
                else
                    carrier.put(${names.displacedKeyField}, displaced.copy());
                root.put(${names.carrierKeyField}, carrier);
                stack.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(root)
                );
            }
        """.trimIndent()))
    }

    private fun rewriteProducer(plan: Plan) {
        val producer = plan.producer
        val carrier = plan.carrier
        val names = plan.names
        val block = producer.nbtStatement.parentNode.orElseThrow() as BlockStmt
        val nbtIndex = block.statements.indexOf(producer.nbtStatement)
        val used = producer.method.findAll(SimpleName::class.java).mapTo(mutableSetOf()) { it.asString() }
        val cleanStackLocal = uniqueName("componentSource", used)
        used += cleanStackLocal
        val opaqueLocal = uniqueName("opaqueComponentState", used)
        used += opaqueLocal
        val rawLocal = uniqueName("componentPatch", used)
        block.setStatement(nbtIndex, StaticJavaParser.parseStatement(
            "net.minecraft.world.item.ItemStack $cleanStackLocal = ${producer.stackName}.copy();"
        ))
        block.addStatement(
            nbtIndex + 1,
            StaticJavaParser.parseStatement(
                "net.minecraft.nbt.CompoundTag $opaqueLocal = " +
                    "${carrier.ownerName}.${names.detachHelper}($cleanStackLocal);"
            )
        )
        block.addStatement(
            nbtIndex + 2,
            StaticJavaParser.parseStatement(
                "net.minecraft.nbt.Tag $rawLocal = ${carrier.ownerName}.${names.encodeHelper}(" +
                    "$cleanStackLocal, ${producer.levelName}.registryAccess());"
            )
        )
        producer.capStatement.remove()
        producer.setterCall.setArgument(carrier.capParameterIndex, StaticJavaParser.parseExpression("null"))
        producer.setterCall.addArgument("1")
        producer.setterCall.addArgument(rawLocal)
        producer.setterCall.addArgument(opaqueLocal)
    }

    private fun rewriteDrop(plan: Plan) {
        val drop = plan.drop
        val names = plan.names
        val owner = plan.carrier.ownerName
        val used = drop.method.findAll(SimpleName::class.java).mapTo(mutableSetOf()) { it.asString() }
        val rawName = uniqueName("componentPatch", used)
        used += rawName
        val versionName = uniqueName("componentPatchVersion", used)
        used += versionName
        val storedOpaqueName = uniqueName("storedOpaqueComponentState", used)
        used += storedOpaqueName
        val decodedOpaqueName = uniqueName("decodedOpaqueComponentState", used)
        used += decodedOpaqueName
        val overlayCopyName = uniqueName("cleanLootOverlay", used)
        used += overlayCopyName
        val overlayOpaqueName = uniqueName("lootOpaqueComponentState", used)
        used += overlayOpaqueName
        val opaqueName = uniqueName("opaqueComponentTransport", used)
        val replacement = StaticJavaParser.parseBlock("""
            {
                net.minecraft.nbt.Tag $rawName = ${drop.getterScope}.${names.rawGetter}();
                int $versionName = ${drop.getterScope}.${names.versionGetter}();
                net.minecraft.nbt.CompoundTag $storedOpaqueName =
                    ${drop.getterScope}.${names.opaqueGetter}();
                net.minecraft.nbt.CompoundTag ${drop.legacyName} =
                    ${drop.getterScope}.${plan.carrier.getter.nameAsString}();
                if ($rawName == null && $storedOpaqueName == null && ${drop.legacyName} == null)
                    return ${drop.lootName};
                return ${drop.lootName}.stream().map(${drop.overlayName} -> {
                    ${drop.preservedFilter}
                    net.minecraft.world.item.ItemStack ${drop.modifiedName} =
                        new net.minecraft.world.item.ItemStack(
                            ${drop.itemExpression}, ${drop.countExpression}
                        );
                    net.minecraft.nbt.CompoundTag $decodedOpaqueName = $owner.${names.applyHelper}(
                        ${drop.modifiedName},
                        ${drop.lootBuilderName}.getLevel().registryAccess(),
                        $versionName,
                        $rawName
                    );
                    net.minecraft.world.item.ItemStack $overlayCopyName =
                        ${drop.overlayName}.copy();
                    net.minecraft.nbt.CompoundTag $overlayOpaqueName =
                        $owner.${names.detachHelper}($overlayCopyName);
                    ${drop.modifiedName}.applyComponents($overlayCopyName.getComponentsPatch());
                    net.minecraft.nbt.CompoundTag $opaqueName =
                        $owner.${names.mergeOpaqueHelper}(
                            $storedOpaqueName, $decodedOpaqueName
                        );
                    if ($storedOpaqueName == null)
                        $opaqueName = $owner.${names.mergeOpaqueHelper}(
                            $opaqueName, $overlayOpaqueName
                        );
                    $owner.${names.attachHelper}(
                        ${drop.modifiedName}, $opaqueName, ${drop.legacyName}
                    );
                    return ${drop.modifiedName};
                }).toList();
            }
        """.trimIndent())
        repeat(3) { drop.block.statements.removeAt(drop.firstIndex) }
        replacement.statements.reversed().forEach {
            drop.block.addStatement(drop.firstIndex, it)
        }
    }

    private fun rewriteClone(plan: Plan) {
        val clone = plan.clone
        val names = plan.names
        val owner = plan.carrier.ownerName
        val used = clone.method.findAll(SimpleName::class.java).mapTo(mutableSetOf()) { it.asString() }
        val rawName = uniqueName("componentPatch", used)
        used += rawName
        val versionName = uniqueName("componentPatchVersion", used)
        used += versionName
        val storedOpaqueName = uniqueName("storedOpaqueComponentState", used)
        used += storedOpaqueName
        val decodedOpaqueName = uniqueName("decodedOpaqueComponentState", used)
        used += decodedOpaqueName
        val vanillaOpaqueName = uniqueName("vanillaOpaqueComponentState", used)
        used += vanillaOpaqueName
        val opaqueName = uniqueName("opaqueComponentTransport", used)
        val replacement = StaticJavaParser.parseBlock("""
            {
                net.minecraft.nbt.Tag $rawName = ${clone.optionalExpression}
                    .map($owner::${names.rawGetter})
                    .map(net.minecraft.nbt.Tag::copy)
                    .orElse(null);
                int $versionName = ${clone.optionalExpression}
                    .map($owner::${names.versionGetter})
                    .orElse(0);
                net.minecraft.nbt.CompoundTag $storedOpaqueName =
                    ${clone.optionalExpression}
                        .map($owner::${names.opaqueGetter})
                        .map(net.minecraft.nbt.CompoundTag::copy)
                        .orElse(null);
                ${clone.legacyDeclaration}
                ${clone.vanillaDeclaration}
                net.minecraft.nbt.CompoundTag $vanillaOpaqueName =
                    $owner.${names.detachTagHelper}(${clone.vanillaName});
                ${clone.integerDeclaration}
                net.minecraft.world.item.ItemStack ${clone.resultName} =
                    new net.minecraft.world.item.ItemStack(
                        ${clone.itemExpression}, ${clone.countExpression}
                    );
                net.minecraft.nbt.CompoundTag $decodedOpaqueName = $owner.${names.applyHelper}(
                    ${clone.resultName},
                    ${clone.levelName}.registryAccess(),
                    $versionName,
                    $rawName
                );
                net.minecraft.world.item.component.CustomData.update(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    ${clone.resultName},
                    custom -> custom.merge(${clone.vanillaName}.copy())
                );
                net.minecraft.nbt.CompoundTag $opaqueName =
                    $owner.${names.mergeOpaqueHelper}(
                        $storedOpaqueName, $decodedOpaqueName
                    );
                if ($storedOpaqueName == null)
                    $opaqueName = $owner.${names.mergeOpaqueHelper}(
                        $opaqueName, $vanillaOpaqueName
                    );
                $owner.${names.attachHelper}(
                    ${clone.resultName}, $opaqueName, ${clone.legacyName}
                );
                net.minecraft.world.item.component.CustomData.update(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    ${clone.resultName},
                    custom -> custom.putInt(
                        ${javaString(clone.finalIntegerKey)}, ${clone.integerName}
                    )
                );
                return ${clone.resultName};
            }
        """.trimIndent())
        repeat(7) { clone.block.statements.removeAt(clone.firstIndex) }
        replacement.statements.reversed().forEach {
            clone.block.addStatement(clone.firstIndex, it)
        }
    }

    private fun namesFor(owner: ClassOrInterfaceDeclaration): Names {
        val identifiers = owner.findAll(SimpleName::class.java).mapTo(mutableSetOf()) { it.asString() }
        fun id(base: String): String = uniqueName(base, identifiers).also { identifiers += it }
        val strings = owner.findAll(StringLiteralExpr::class.java).mapTo(mutableSetOf()) { it.value }
        fun key(base: String): String {
            var candidate = base
            while (candidate in strings) candidate += "_"
            strings += candidate
            return candidate
        }
        return Names(
            rawField = id("modporterComponentPatch"),
            versionField = id("modporterComponentPatchVersion"),
            opaqueField = id("modporterOpaqueComponentState"),
            rawGetter = id("getModporterComponentPatch"),
            versionGetter = id("getModporterComponentPatchVersion"),
            opaqueGetter = id("getModporterOpaqueComponentState"),
            encodeHelper = id("encodeModporterComponentPatch"),
            applyHelper = id("applyModporterComponentPatch"),
            attachHelper = id("attachModporterOpaqueComponents"),
            detachHelper = id("detachModporterOpaqueComponents"),
            detachTagHelper = id("detachModporterOpaqueCustomData"),
            rawCarrierHelper = id("modporterOpaqueRawPatch"),
            mergeOpaqueHelper = id("mergeModporterOpaqueComponents"),
            carrierKeyField = id("MODPORTER_COMPONENT_CARRIER_KEY"),
            markerKeyField = id("MODPORTER_COMPONENT_MARKER_KEY"),
            rawKeyField = id("MODPORTER_COMPONENT_RAW_KEY"),
            rawVersionKeyField = id("MODPORTER_COMPONENT_RAW_VERSION_KEY"),
            legacyKeyField = id("MODPORTER_COMPONENT_LEGACY_KEY"),
            displacedKeyField = id("MODPORTER_COMPONENT_DISPLACED_KEY"),
            composedKeyField = id("MODPORTER_COMPONENT_COMPOSED_KEY"),
            persistedRawKeyField = id("MODPORTER_COMPONENT_PATCH_KEY"),
            persistedVersionKeyField = id("MODPORTER_COMPONENT_PATCH_VERSION_KEY"),
            persistedOpaqueKeyField = id("MODPORTER_COMPONENT_OPAQUE_KEY"),
            carrierKey = key("__ModporterItemComponentTransport"),
            markerKey = key("Format"),
            rawKey = key("RawPatch"),
            rawVersionKey = key("RawPatchVersion"),
            legacyKey = key("LegacyForgeCaps"),
            displacedKey = key("DisplacedValue"),
            composedKey = key("ComposedOpaqueState"),
            persistedRawKey = key("__ModporterComponentPatch"),
            persistedVersionKey = key("__ModporterComponentPatchVersion"),
            persistedOpaqueKey = key("__ModporterOpaqueComponentState")
        )
    }

    private fun nullableCopyParameter(
        expression: Expression,
        method: MethodDeclaration
    ): String? {
        val conditional = expression as? ConditionalExpr ?: return null
        val comparison = conditional.condition as? BinaryExpr ?: return null
        if (comparison.operator != BinaryExpr.Operator.EQUALS) return null
        val parameter = when {
            comparison.left is NameExpr && comparison.right is NullLiteralExpr ->
                (comparison.left as NameExpr).nameAsString
            comparison.right is NameExpr && comparison.left is NullLiteralExpr ->
                (comparison.right as NameExpr).nameAsString
            else -> return null
        }
        if (method.parameters.none { it.nameAsString == parameter }) return null
        if (conditional.thenExpr !is NullLiteralExpr ||
            !conditional.elseExpr.isCopyOf(parameter)
        ) return null
        return parameter
    }

    private fun nullableCompoundRead(
        expression: Expression,
        method: MethodDeclaration,
        key: String,
        file: ParsedFile
    ): String? {
        val conditional = expression as? ConditionalExpr ?: return null
        val condition = conditional.condition as? MethodCallExpr ?: return null
        if (condition.nameAsString != "contains" ||
            condition.arguments.singleOrNull() !is StringLiteralExpr ||
            (condition.arguments.single() as StringLiteralExpr).value != key
        ) return null
        val tag = condition.scope.orElse(null) as? NameExpr ?: return null
        val parameter = method.parameters.singleOrNull {
            it.nameAsString == tag.nameAsString &&
                file.isType(it.typeAsString, COMPOUND_TAG, it)
        } ?: return null
        val read = conditional.thenExpr as? MethodCallExpr ?: return null
        if (read.nameAsString != "getCompound" ||
            read.scope.map { it.toString() }.orElse("") != parameter.nameAsString ||
            (read.arguments.singleOrNull() as? StringLiteralExpr)?.value != key ||
            conditional.elseExpr !is NullLiteralExpr
        ) return null
        return parameter.nameAsString
    }

    private fun exactForgeCapsRead(conditional: ConditionalExpr): String? {
        val condition = conditional.condition as? MethodCallExpr ?: return null
        if (condition.nameAsString != "contains" ||
            (condition.arguments.singleOrNull() as? StringLiteralExpr)?.value != "ForgeCaps"
        ) return null
        val nbt = condition.scope.orElse(null) as? NameExpr ?: return null
        val read = conditional.thenExpr as? MethodCallExpr ?: return null
        if (read.nameAsString != "getCompound" ||
            read.scope.map { it.toString() }.orElse("") != nbt.nameAsString ||
            (read.arguments.singleOrNull() as? StringLiteralExpr)?.value != "ForgeCaps" ||
            conditional.elseExpr !is NullLiteralExpr
        ) return null
        return nbt.nameAsString
    }

    private fun MethodDeclaration.returnsField(
        field: String,
        file: ParsedFile,
        canonicalType: String
    ): Boolean {
        if (parameters.isNotEmpty() || !file.isType(typeAsString, canonicalType, this)) {
            return false
        }
        val statements = body.orElse(null)?.statements ?: return false
        val returned = (statements.singleOrNull() as? ReturnStmt)
            ?.expression?.orElse(null) ?: return false
        return returned.isField(field)
    }

    private fun exactIbeConsumerReceiverTypes(
        units: List<CompilationUnit>,
        facts: ProjectValueFacts
    ): Map<String, String> {
        val declarations = units.flatMap { unit ->
            unit.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull { declaration ->
                qualifiedNamedOwner(declaration, unit)?.let { Triple(it, unit, declaration) }
            }
        }
        val ibe = declarations.singleOrNull { it.first == IBE } ?: return emptyMap()
        if (!ibe.third.isExactIbeConsumerContract(ibe.second, facts.types)) return emptyMap()

        val bindings = linkedMapOf<String, MutableSet<String>>()
        declarations.forEach { (ownerName, unit, declaration) ->
            if (declaration.isInterface) return@forEach
            if (declaration.methods.any {
                    it.nameAsString == "withBlockEntityDo" && it.parameters.size == 3
                } ||
                projectParentDefinesMethod(ownerName, "withBlockEntityDo/3", facts)
            ) return@forEach
            val implemented = declaration.implementedTypes.filter {
                unit.isType(it.nameWithScope, IBE, facts.types, declaration)
            }
            if (implemented.size != 1) return@forEach
            val argument = implemented.single().typeArguments.orElse(null)
                ?.singleOrNull() as? ClassOrInterfaceType ?: return@forEach
            val receiverType = unit.resolveExactProjectTypeName(
                argument.toString(),
                facts.types,
                declaration
            ) ?: return@forEach
            bindings.getOrPut(ownerName) { linkedSetOf() } += receiverType
        }
        return bindings.mapNotNull { (owner, receiverTypes) ->
            receiverTypes.singleOrNull()?.let { owner to it }
        }.toMap()
    }

    private fun projectParentDefinesMethod(
        owner: String,
        methodKey: String,
        facts: ProjectValueFacts
    ): Boolean {
        var current = facts.parents[owner]
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (facts.methods[current]?.get(methodKey).orEmpty().isNotEmpty()) return true
            current = facts.parents[current]
        }
        return false
    }

    private fun ClassOrInterfaceDeclaration.isExactIbeConsumerContract(
        unit: CompilationUnit,
        projectTypes: Set<String>
    ): Boolean {
        if (!isInterface) return false
        if (extendedTypes.size != 1 ||
            !unit.isType(
                extendedTypes.single().nameWithScope,
                ENTITY_BLOCK,
                projectTypes,
                this
            )
        ) return false
        val typeParameter = typeParameters.singleOrNull() ?: return false
        val bound = typeParameter.typeBound.singleOrNull() ?: return false
        if (!unit.isType(bound.nameWithScope, BLOCK_ENTITY, projectTypes, typeParameter)) {
            return false
        }
        val method = methods.singleOrNull {
            it.nameAsString == "withBlockEntityDo" && it.parameters.size == 3
        } ?: return false
        if (!method.isDefault || !method.type.isVoidType) return false
        if (!unit.isType(
                method.parameters[0].typeAsString,
                BLOCK_GETTER,
                projectTypes,
                method.parameters[0]
            ) ||
            !unit.isType(
                method.parameters[1].typeAsString,
                BLOCK_POS,
                projectTypes,
                method.parameters[1]
            )
        ) return false
        val consumer = method.parameters[2].type as? ClassOrInterfaceType ?: return false
        if (!unit.isType(consumer.nameWithScope, CONSUMER, projectTypes, consumer)) return false
        val consumed = consumer.typeArguments.orElse(null)?.singleOrNull() ?: return false
        if (consumed.toString() != typeParameter.nameAsString) return false

        val statement = method.body.orElse(null)?.statements?.singleOrNull()
            as? ExpressionStmt ?: return false
        val ifPresent = statement.expression as? MethodCallExpr ?: return false
        if (ifPresent.nameAsString != "ifPresent" || ifPresent.arguments.size != 1 ||
            (ifPresent.arguments[0] as? NameExpr)?.nameAsString !=
            method.parameters[2].nameAsString
        ) return false
        val optional = ifPresent.scope.orElse(null) as? MethodCallExpr ?: return false
        return optional.nameAsString == "getBlockEntityOptional" &&
            optional.arguments.size == 2 &&
            (optional.arguments[0] as? NameExpr)?.nameAsString ==
            method.parameters[0].nameAsString &&
            (optional.arguments[1] as? NameExpr)?.nameAsString ==
            method.parameters[1].nameAsString
    }

    private fun CompilationUnit.resolveExactProjectTypeName(
        sourceType: String,
        projectTypes: Set<String>,
        context: Node
    ): String? {
        val plain = sourceType.substringBefore('<').replace(" ", "")
        if (plain in projectTypes) return plain
        if (context.hasVisibleTypeParameter(plain.substringBefore('.'))) return null
        val first = plain.substringBefore('.')
        val declared = findAll(ClassOrInterfaceDeclaration::class.java)
            .filter { it.nameAsString == first }
            .mapNotNull { qualifiedNamedOwner(it, this) }
            .distinct()
        if (declared.size == 1) {
            return declared.single() + plain.removePrefix(first)
        }
        val packageName = packageDeclaration.map { it.nameAsString }.orElse("")
        val samePackage = projectTypes.filter {
            it.substringAfterLast('.') == first &&
                it.substringBeforeLast('.', "") == packageName
        }.distinct()
        if (samePackage.size == 1) {
            return samePackage.single() + plain.removePrefix(first)
        }
        val explicit = imports.filter {
            !it.isStatic && !it.isAsterisk && it.name.identifier == first
        }.map { it.nameAsString }.distinct()
        if (explicit.size == 1) {
            return explicit.single() + plain.removePrefix(first)
        }
        val wildcardNamespaces = imports.filter {
            !it.isStatic && it.isAsterisk
        }.map { it.nameAsString }.toSet()
        val wildcardMatches = projectTypes.filter {
            it.substringAfterLast('.') == first &&
                it.substringBeforeLast('.', "") in wildcardNamespaces
        }.distinct()
        return wildcardMatches.singleOrNull()?.plus(plain.removePrefix(first))
    }

    private fun NameExpr.exactIbeConsumerType(file: ParsedFile): String? {
        val lambda = findAncestor(LambdaExpr::class.java).orElse(null) ?: return null
        val parameter = lambda.parameters.singleOrNull() ?: return null
        if (parameter.nameAsString != nameAsString || !parameter.type.isUnknownType) {
            return null
        }
        val call = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return null
        if (call.nameAsString != "withBlockEntityDo" ||
            call.arguments.size != 3 ||
            call.arguments[2] !== lambda
        ) return null
        val callScope = call.scope.orElse(null)
        if (callScope != null && callScope !is ThisExpr) return null
        val declaration = call.nearestOwner() ?: return null
        val ownerName = qualifiedNamedOwner(declaration, file.unit) ?: return null
        return file.exactIbeReceiverTypes[ownerName]
    }

    private fun MethodCallExpr.receiverBinding(
        file: ParsedFile,
        ownerName: String
    ): CanonicalBinding {
        val receiver = scope.orElse(null)
        if (receiver == null) {
            val declaration = nearestOwner() ?: return CanonicalBinding.AMBIGUOUS
            val resolved = qualifiedNamedOwner(declaration, file.unit)
                ?: return CanonicalBinding.AMBIGUOUS
            return if (resolved == ownerName ||
                inheritsFrom(resolved, ownerName, file.projectFacts)
            ) {
                CanonicalBinding.CANONICAL
            } else {
                CanonicalBinding.UNRELATED
            }
        }
        val offset = file.offset(receiver) ?: return CanonicalBinding.AMBIGUOUS
        if (receiver is NameExpr &&
            file.valueTypes.untypedLambdaParameter(receiver.nameAsString, offset)
        ) {
            val exactIbeType = receiver.exactIbeConsumerType(file)
            return when {
                exactIbeType == ownerName -> CanonicalBinding.CANONICAL
                exactIbeType != null -> CanonicalBinding.UNRELATED
                else -> CanonicalBinding.AMBIGUOUS
            }
        }
        val resolved = file.valueTypes.typeOf(receiver.toString(), offset)
        if (resolved == null) {
            return if (receiver is NameExpr &&
                receiver.isExactNegatedPatternReceiver(file, ownerName, this)
            ) {
                CanonicalBinding.CANONICAL
            } else {
                CanonicalBinding.AMBIGUOUS
            }
        }
        if (receiver.hasVisibleTypeParameter(resolved.substringAfterLast('.'))) {
            return CanonicalBinding.AMBIGUOUS
        }
        return if (resolved == ownerName ||
            inheritsFrom(resolved, ownerName, file.projectFacts)
        ) {
            CanonicalBinding.CANONICAL
        } else if (resolved in file.projectFacts.types) {
            CanonicalBinding.UNRELATED
        } else {
            CanonicalBinding.AMBIGUOUS
        }
    }

    private fun MethodReferenceExpr.receiverBinding(
        file: ParsedFile,
        ownerName: String
    ): CanonicalBinding {
        val receiver = scope
        if (receiver is TypeExpr) {
            return file.typeBinding(receiver.type.toString(), ownerName, receiver)
        }
        val offset = file.offset(receiver) ?: return CanonicalBinding.AMBIGUOUS
        if (receiver is NameExpr &&
            file.valueTypes.untypedLambdaParameter(receiver.nameAsString, offset)
        ) {
            val exactIbeType = receiver.exactIbeConsumerType(file)
            return when {
                exactIbeType == ownerName -> CanonicalBinding.CANONICAL
                exactIbeType != null -> CanonicalBinding.UNRELATED
                else -> CanonicalBinding.AMBIGUOUS
            }
        }
        val resolved = file.valueTypes.typeOf(receiver.toString(), offset)
            ?: return CanonicalBinding.AMBIGUOUS
        if (receiver.hasVisibleTypeParameter(resolved.substringAfterLast('.'))) {
            return CanonicalBinding.AMBIGUOUS
        }
        return if (resolved == ownerName ||
            inheritsFrom(resolved, ownerName, file.projectFacts)
        ) {
            CanonicalBinding.CANONICAL
        } else if (resolved in file.projectFacts.types) {
            CanonicalBinding.UNRELATED
        } else {
            CanonicalBinding.AMBIGUOUS
        }
    }

    private fun NameExpr.isExactNegatedPatternReceiver(
        file: ParsedFile,
        ownerName: String,
        use: Node
    ): Boolean {
        val block = use.findAncestor(BlockStmt::class.java).orElse(null) ?: return false
        val useStatement = topLevelStatement(use, block) ?: return false
        val useIndex = block.statements.indexOfFirst { it === useStatement }
        if (useIndex <= 0) return false
        val guard = block.statements[useIndex - 1] as? IfStmt ?: return false
        if (guard.elseStmt.isPresent || !guard.thenStmt.exitsAbruptly()) return false
        val negated = guard.condition.unwrapParentheses() as? UnaryExpr ?: return false
        if (negated.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return false
        val test = negated.expression.unwrapParentheses() as? InstanceOfExpr ?: return false
        val pattern = test.pattern.orElse(null) as? TypePatternExpr ?: return false
        return pattern.nameAsString == nameAsString &&
            file.isType(pattern.type.toString(), ownerName, pattern)
    }

    private fun Expression.unwrapParentheses(): Expression {
        var current = this
        while (current is EnclosedExpr) current = current.inner
        return current
    }

    private fun Statement.exitsAbruptly(): Boolean = when (val direct = unwrapSingle()) {
        is ReturnStmt, is ThrowStmt -> true
        else -> false
    }

    private fun Expression.isCopyOf(name: String): Boolean {
        val call = this as? MethodCallExpr ?: return false
        return call.nameAsString == "copy" &&
            call.arguments.isEmpty() &&
            (call.scope.orElse(null) as? NameExpr)?.nameAsString == name
    }

    private fun Expression.isField(name: String): Boolean =
        this is NameExpr && nameAsString == name && isUnshadowedFieldReference(name) ||
            this is FieldAccessExpr && nameAsString == name && scope is ThisExpr

    private fun NameExpr.isUnshadowedFieldReference(name: String): Boolean {
        var current: Node? = this
        while (current != null) {
            when (current) {
                is LambdaExpr ->
                    if (current.parameters.any { it.nameAsString == name }) return false
                is MethodDeclaration ->
                    if (current.parameters.any { it.nameAsString == name }) return false
                is ConstructorDeclaration ->
                    if (current.parameters.any { it.nameAsString == name }) return false
                is CatchClause ->
                    if (current.parameter.nameAsString == name) return false
                is ClassOrInterfaceDeclaration -> return true
            }
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun Expression.fieldName(): String? = when (this) {
        is NameExpr -> nameAsString
        is FieldAccessExpr -> nameAsString.takeIf { scope is ThisExpr }
        else -> null
    }

    private fun Expression.isNullComparison(name: String): Boolean {
        val binary = this as? BinaryExpr ?: return false
        if (binary.operator != BinaryExpr.Operator.EQUALS) return false
        return binary.left is NameExpr &&
            (binary.left as NameExpr).nameAsString == name &&
            binary.right is NullLiteralExpr ||
            binary.right is NameExpr &&
            (binary.right as NameExpr).nameAsString == name &&
            binary.left is NullLiteralExpr
    }

    private fun Statement.unwrapSingle(): Statement =
        (this as? BlockStmt)?.statements?.singleOrNull() ?: this

    private fun Node.nearestOwner(): ClassOrInterfaceDeclaration? =
        findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)

    private fun Node.isAncestorOf(other: Node): Boolean {
        var current: Node? = other
        while (current != null) {
            if (current === this) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun ParsedFile.isType(
        sourceType: String,
        canonical: String,
        context: Node? = null
    ): Boolean = unit.isType(sourceType, canonical, projectTypes, context)

    private fun ParsedFile.typeBinding(
        sourceType: String,
        canonical: String,
        context: Node
    ): CanonicalBinding {
        if (isType(sourceType, canonical, context)) return CanonicalBinding.CANONICAL
        val plain = sourceType.substringBefore('<').replace(" ", "")
        if (context.hasVisibleTypeParameter(plain.substringBefore('.'))) {
            return CanonicalBinding.AMBIGUOUS
        }
        val resolved = resolveTypeName(plain)
        if (resolved != null) {
            return if (resolved == canonical ||
                inheritsFrom(resolved, canonical, projectFacts)
            ) {
                CanonicalBinding.CANONICAL
            } else {
                CanonicalBinding.UNRELATED
            }
        }
        val simple = canonical.substringAfterLast('.')
        val outerCanonical = canonical.substringBeforeLast('.')
        val nestedSpelling = outerCanonical.substringAfterLast('.') + "." + simple
        return if (plain == simple || plain == nestedSpelling) {
            CanonicalBinding.AMBIGUOUS
        } else {
            CanonicalBinding.UNRELATED
        }
    }

    private fun ParsedFile.resolveTypeName(plain: String): String? {
        if (plain in projectTypes) return plain
        val first = plain.substringBefore('.')
        val declared = unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .filter { it.nameAsString == first }
            .mapNotNull { qualifiedNamedOwner(it, unit) }
            .distinct()
        if (declared.size == 1) {
            return declared.single() + plain.removePrefix(first)
        }
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val samePackage = projectTypes.filter {
            it.substringAfterLast('.') == first &&
                it.substringBeforeLast('.', "") == packageName
        }.distinct()
        if (samePackage.size == 1) {
            return samePackage.single() + plain.removePrefix(first)
        }
        val explicit = unit.imports.filter {
            !it.isStatic && !it.isAsterisk &&
                it.name.identifier == first
        }.map { it.nameAsString }.distinct()
        if (explicit.size == 1) {
            return explicit.single() + plain.removePrefix(first)
        }
        val wildcardNamespaces = unit.imports.filter {
            !it.isStatic && it.isAsterisk
        }.map { it.nameAsString }.toSet()
        val wildcardMatches = projectTypes.filter {
            it.substringAfterLast('.') == first &&
                it.substringBeforeLast('.', "") in wildcardNamespaces
        }.distinct()
        if (wildcardMatches.size == 1) {
            return wildcardMatches.single() + plain.removePrefix(first)
        }
        return null
    }

    private fun ParsedFile.isEitherType(
        sourceType: String,
        first: String,
        second: String,
        context: Node? = null
    ): Boolean = isType(sourceType, first, context) || isType(sourceType, second, context)

    private fun CompilationUnit.isType(
        sourceType: String,
        canonical: String,
        projectTypes: Set<String>,
        context: Node? = null
    ): Boolean {
        val plain = sourceType.substringBefore('<').replace(" ", "")
        if (plain == canonical) return true
        if (context?.hasVisibleTypeParameter(plain.substringBefore('.')) == true) return false
        val simple = canonical.substringAfterLast('.')
        if (plain == simple) {
            val declared = findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { it.nameAsString == simple }
                .mapNotNull { qualifiedNamedOwner(it, this) }
                .distinct()
            if (declared.isNotEmpty()) return declared.singleOrNull() == canonical
            val packageName = packageDeclaration.map { it.nameAsString }.orElse("")
            val samePackage = projectTypes.filter {
                it.substringAfterLast('.') == simple &&
                    it.substringBeforeLast('.', "") == packageName
            }.distinct()
            if (samePackage.isNotEmpty()) return samePackage.singleOrNull() == canonical
            val explicit = imports.filter {
                !it.isStatic && !it.isAsterisk && it.name.identifier == simple
            }.map { it.nameAsString }.distinct()
            if (explicit.isNotEmpty()) return explicit.singleOrNull() == canonical
            val namespace = canonical.substringBeforeLast('.')
            return imports.any {
                !it.isStatic && it.isAsterisk && it.nameAsString == namespace
            }
        }
        val outerCanonical = canonical.substringBeforeLast('.')
        val nestedSpelling = outerCanonical.substringAfterLast('.') + "." + simple
        if (plain != nestedSpelling) return false
        return isType(plain.substringBefore('.'), outerCanonical, projectTypes, context)
    }

    private fun Node.hasVisibleTypeParameter(name: String): Boolean {
        var current: Node? = this
        while (current != null) {
            val typeParameters = when (current) {
                is ClassOrInterfaceDeclaration -> current.typeParameters
                is MethodDeclaration -> current.typeParameters
                is ConstructorDeclaration -> current.typeParameters
                else -> null
            }
            if (typeParameters?.any { it.nameAsString == name } == true) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun cloneUsesLevelReader(clone: CloneConsumer): Boolean =
        clone.method.parameters.singleOrNull {
            clone.file.isType(it.typeAsString, LEVEL_READER, it)
        }?.nameAsString == clone.levelName

    internal fun writeAtomicFamilyForTest(
        outputs: Map<Path, String>,
        originalBytes: Map<Path, ByteArray>,
        atomicMove: (Path, Path) -> Unit
    ) {
        writeAtomicFamily(outputs, originalBytes, atomicMove)
    }

    private fun writeAtomicFamily(
        outputs: Map<Path, String>,
        originalBytes: Map<Path, ByteArray>,
        atomicMove: (Path, Path) -> Unit
    ) {
        val changed = outputs.entries
            .filter { (path, source) ->
                !originalBytes.getValue(path).contentEquals(
                    source.toByteArray(StandardCharsets.UTF_8)
                )
            }
            .sortedBy { it.key.toAbsolutePath().normalize().toString() }
        if (changed.isEmpty()) return
        val stagedOutputs = linkedMapOf<Path, Path>()
        val stagedOriginals = linkedMapOf<Path, Path>()
        val committed = mutableListOf<Path>()
        val preservedRollbackStages = mutableSetOf<Path>()
        try {
            for ((path, source) in changed) {
                val outputStage =
                    Files.createTempFile(path.parent, ".modporter-component-stage-", ".java")
                stagedOutputs[path] = outputStage
                val originalStage =
                    Files.createTempFile(path.parent, ".modporter-component-original-", ".java")
                stagedOriginals[path] = originalStage
                Files.write(outputStage, source.toByteArray(StandardCharsets.UTF_8))
                Files.write(originalStage, originalBytes.getValue(path).copyOf())
            }
            for ((path, temporary) in stagedOutputs) {
                atomicMove(temporary, path)
                committed.add(path)
            }
        } catch (failure: Exception) {
            val rollbackFailures = mutableListOf<Exception>()
            for (path in committed.asReversed()) {
                try {
                    atomicMove(stagedOriginals.getValue(path), path)
                } catch (rollbackFailure: Exception) {
                    preservedRollbackStages.add(stagedOriginals.getValue(path))
                    rollbackFailures += rollbackFailure
                }
            }
            rollbackFailures.forEach(failure::addSuppressed)
            throw failure
        } finally {
            (stagedOutputs.values + stagedOriginals.values).forEach {
                if (it !in preservedRollbackStages) {
                    runCatching { Files.deleteIfExists(it) }
                }
            }
        }
    }

    private fun topLevelStatement(node: Node, body: BlockStmt): Statement? {
        var current: Node = node
        while (current.parentNode.orElse(null) !== body) {
            current = current.parentNode.orElse(null) ?: return null
        }
        return current as? Statement
    }

    private fun qualifiedNamedOwner(
        declaration: ClassOrInterfaceDeclaration,
        unit: CompilationUnit
    ): String? {
        val names = mutableListOf<String>()
        var current: Node = declaration
        while (current is TypeDeclaration<*>) {
            names += current.nameAsString
            current = current.parentNode.orElse(null) ?: return null
        }
        if (current !== unit) return null
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val nested = names.asReversed().joinToString(".")
        return if (packageName.isBlank()) nested else "$packageName.$nested"
    }

    private fun inheritsFromBlockEntity(
        owner: String,
        facts: ProjectValueFacts
    ): Boolean = inheritsFrom(owner, BLOCK_ENTITY, facts)

    private fun inheritsFrom(
        owner: String,
        target: String,
        facts: ProjectValueFacts
    ): Boolean {
        var current: String? = owner
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (current == target || TargetMembers.inheritsFrom(current, target)) {
                return true
            }
            current = facts.parents[current]
        }
        return false
    }

    private fun uniqueName(base: String, used: Set<String>): String {
        var candidate = base
        while (candidate in used) candidate += "_"
        return candidate
    }

    private fun javaString(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
}
