package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.DoubleLiteralExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * This migration is intentionally source-bound. It admits a legacy client factory only when:
 *
 *  - the registered EntityType.EntityFactory constructor reference has a closed source forwarding
 *    path into Registrate's EntityBuilder constructor;
 *  - the legacy position constructor delegates to that registered constructor and then performs
 *    only setPos(x, y, z) and refreshDimensions();
 *  - the old packet factory performs only that position construction, the three legacy velocity
 *    reads, optional entity.position() field snapshots, and return;
 *  - no direct call or second method-reference use of the packet factory exists;
 *  - IEntityWithComplexSpawn writes and unconditionally restores the authoritative three-float
 *    motion payload;
 *  - the legacy lerpMotion override has the exact current-motion plus scaled-packet averaging
 *    shape; and
 *  - no source reads SpawnEntity#getAdditionalData from the factory; and
 *  - no supplied EntityJoinLevelEvent method directly cancels the event.
 *
 * The generated override uses the real ClientboundAddEntityPacket delivered by the target client.
 * It does not instantiate a packet, invent constructor arguments, or decode the complex-spawn
 * payload. A configured, validation-enabled access transformer exposes the packet's stored
 * xa/ya/za integers so the pre-insertion EntityJoinLevel state remains raw. NeoForge continues to
 * invoke readSpawnData through AdvancedAddEntityPayload; a generated prefix performs the one
 * deferred legacy lerpMotion call before the unchanged full-float read.
 *
 * The compatibility contract begins at supplied source and requires recompilation across the
 * Forge 1.20.1 -> NeoForge 1.21.1 API boundary. The removed PlayMessages descriptor is outside the
 * target binary-compatibility contract. A project-level source audit must also establish that
 * admitted entities cannot be cancelled and retained by supplied EntityJoinLevelEvent handlers;
 * this pass rejects direct source cancellation.
 */

private const val LEGACY_FORGE_SPAWN =
    "net.minecraftforge.network.PlayMessages.SpawnEntity"
private const val STALE_NEOFORGE_SPAWN =
    "net.neoforged.neoforge.network.PlayMessages.SpawnEntity"
private val LEGACY_SPAWN_IMPORTS = setOf(LEGACY_FORGE_SPAWN, STALE_NEOFORGE_SPAWN)

private const val LEVEL_FQN = "net.minecraft.world.level.Level"
private const val LIVING_ENTITY_FQN = "net.minecraft.world.entity.LivingEntity"
private const val ENTITY_TYPE_FQN = "net.minecraft.world.entity.EntityType"
private const val ENTITY_FACTORY_FQN =
    "net.minecraft.world.entity.EntityType.EntityFactory"
private const val COMPLEX_SPAWN_FQN =
    "net.neoforged.neoforge.entity.IEntityWithComplexSpawn"
private const val REGISTRY_BUFFER_FQN =
    "net.minecraft.network.RegistryFriendlyByteBuf"
private const val ADD_ENTITY_PACKET_FQN =
    "net.minecraft.network.protocol.game.ClientboundAddEntityPacket"
private const val REGISTRATE_ENTITY_BUILDER_FQN =
    "com.tterrag.registrate.builders.EntityBuilder"
private val RAW_PACKET_FIELDS = listOf("xa", "ya", "za")
private const val ACCESS_TRANSFORMER_RELATIVE =
    "src/main/resources/META-INF/accesstransformer.cfg"
private const val ENTITY_JOIN_EVENT_SIMPLE = "EntityJoinLevelEvent"
private val RAW_PACKET_ACCESS_TRANSFORMERS = RAW_PACKET_FIELDS.map {
    "public $ADD_ENTITY_PACKET_FQN $it"
}
private const val PENDING_MOTION_FIELD = "modporterLegacySpawnMotionPending"
private const val PENDING_MOTION_X_FIELD = "modporterLegacySpawnMotionX"
private const val PENDING_MOTION_Y_FIELD = "modporterLegacySpawnMotionY"
private const val PENDING_MOTION_Z_FIELD = "modporterLegacySpawnMotionZ"
private val GENERATED_MOTION_FIELDS = setOf(
    PENDING_MOTION_FIELD,
    PENDING_MOTION_X_FIELD,
    PENDING_MOTION_Y_FIELD,
    PENDING_MOTION_Z_FIELD
)
private const val RULE_ID = "struct-legacy-client-spawn-factory"

private data class SpawnSource(
    val file: Path,
    val source: String,
    val unit: CompilationUnit
)

private data class SpawnType(
    val source: SpawnSource,
    val declaration: ClassOrInterfaceDeclaration
) {
    val name: String get() = declaration.nameAsString
}

private data class SpawnCallable(
    val owner: SpawnType,
    val declaration: CallableDeclaration<*>
)

private data class SpawnEdit(
    val start: Int,
    val endExclusive: Int,
    val replacement: String
)

private data class LegacyFactoryShape(
    val method: MethodDeclaration,
    val positionConstructor: ConstructorDeclaration,
    val snapshotFields: List<String>,
    val complexSpawn: ComplexSpawnShape
)

private data class ComplexSpawnShape(
    val readSpawnData: MethodDeclaration
)

private data class AccessTransformerPlan(
    val migrated: String,
    val addedLines: List<String>
)

private data class LegacyFactoryPlan(
    val source: SpawnSource,
    val owner: SpawnType,
    val legacyImport: String,
    val customFactoryCall: MethodCallExpr,
    val factory: LegacyFactoryShape
)

private class SpawnOffsets(private val source: String) {
    private val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }

    fun start(node: Node): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)

    fun endExclusive(node: Node): Int = node.range
        .map { lineStarts[it.end.line - 1] + it.end.column }
        .orElse(-1)

    fun text(node: Node): String {
        val start = start(node)
        val end = endExclusive(node)
        return if (start < 0 || end < start) "" else source.substring(start, end)
    }

    fun fullLineRange(node: Node): Pair<Int, Int>? {
        val nodeStart = start(node)
        val nodeEnd = endExclusive(node)
        if (nodeStart < 0 || nodeEnd < nodeStart) return null
        val lineStart = source.lastIndexOf('\n', nodeStart - 1)
            .let { if (it < 0) 0 else it + 1 }
        val lineEnd = source.indexOf('\n', nodeEnd)
            .let { if (it < 0) source.length else it + 1 }
        return lineStart to lineEnd
    }
}

private class EntityFactorySourceGraph(
    private val sources: List<SpawnSource>
) {
    val types: List<SpawnType> = sources.flatMap { source ->
        source.unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .map { SpawnType(source, it) }
    }
    val methods: List<SpawnCallable> = types.flatMap { owner ->
        owner.declaration.methods.map { SpawnCallable(owner, it) }
    }
    val constructors: List<SpawnCallable> = types.flatMap { owner ->
        owner.declaration.constructors.map { SpawnCallable(owner, it) }
    }

    fun provesRegisteredFactory(owner: SpawnType): Boolean {
        val references = sources.flatMap { source ->
            source.unit.findAll(MethodReferenceExpr::class.java)
                .filter { reference ->
                    reference.identifier == "new" &&
                        namesOwner(reference.scope.toString(), owner.name)
                }
        }
        if (references.size != 1) return false
        val reference = references.single()
        val call = reference.parentNode.orElse(null) as? MethodCallExpr ?: return false
        val argumentIndex = call.arguments.indexOf(reference)
        if (argumentIndex < 0) return false
        val target = resolveMethod(call, argumentIndex) ?: return false
        return traceFactoryParameter(target, argumentIndex, mutableSetOf())
    }

    fun hasSourceSubclass(owner: SpawnType): Boolean = types.any { candidate ->
        candidate !== owner && candidate.declaration.extendedTypes.any {
            it.nameWithScope == owner.name
        }
    }

    private fun traceFactoryParameter(
        callable: SpawnCallable,
        parameterIndex: Int,
        visited: MutableSet<String>
    ): Boolean {
        if (parameterIndex !in callable.declaration.parameters.indices) return false
        val parameter = callable.declaration.parameters[parameterIndex]
        if (!isEntityFactoryParameter(callable.owner.source.unit, parameter)) return false

        val range = callable.declaration.range.orElse(null) ?: return false
        val key = buildString {
            append(callable.owner.source.file)
            append(':')
            append(range.begin.line)
            append(':')
            append(range.begin.column)
            append(':')
            append(parameterIndex)
        }
        if (!visited.add(key)) return false

        val parameterName = parameter.nameAsString
        if (hasShadowingBinding(callable.declaration, parameter, parameterName)) return false
        val uses = callable.declaration.findAll(NameExpr::class.java).filter { use ->
            use.nameAsString == parameterName &&
                use.findAncestor(CallableDeclaration::class.java).orElse(null) ===
                callable.declaration
        }
        if (uses.isEmpty()) return false

        return uses.all { use ->
            when (val parent = use.parentNode.orElse(null)) {
                is MethodCallExpr -> {
                    val index = parent.arguments.indexOf(use)
                    if (index < 0) false
                    else resolveMethod(parent, index)?.let {
                        traceFactoryParameter(it, index, visited)
                    } == true
                }
                is ObjectCreationExpr -> {
                    val index = parent.arguments.indexOf(use)
                    if (index < 0) false
                    else resolveConstructor(parent, index)?.let {
                        traceFactoryParameter(it, index, visited)
                    } == true
                }
                is ExplicitConstructorInvocationStmt -> {
                    val index = parent.arguments.indexOf(use)
                    !parent.isThis &&
                        index == 4 &&
                        parent.arguments.size == 6 &&
                        directlyExtendsRegistrateEntityBuilder(callable.owner)
                }
                else -> false
            }
        }
    }

    private fun resolveMethod(call: MethodCallExpr, argumentIndex: Int): SpawnCallable? {
        var candidates = methods.filter { candidate ->
            val method = candidate.declaration as MethodDeclaration
            method.nameAsString == call.nameAsString &&
                method.parameters.size == call.arguments.size &&
                argumentIndex in method.parameters.indices &&
                isEntityFactoryParameter(
                    candidate.owner.source.unit,
                    method.parameters[argumentIndex]
                )
        }
        val scope = call.scope.orElse(null)
        when (scope) {
            null, is ThisExpr -> {
                val enclosing = call.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null)
                val local = candidates.filter { it.owner.declaration === enclosing }
                if (local.isNotEmpty()) candidates = local
            }
            is NameExpr -> {
                if (scope.nameAsString.firstOrNull()?.isUpperCase() == true) {
                    candidates = candidates.filter { it.owner.name == scope.nameAsString }
                }
            }
        }
        return candidates.singleOrNull()
    }

    private fun resolveConstructor(
        creation: ObjectCreationExpr,
        argumentIndex: Int
    ): SpawnCallable? {
        val typeName = creation.type.nameAsString
        return constructors.filter { candidate ->
            val constructor = candidate.declaration as ConstructorDeclaration
            candidate.owner.name == typeName &&
                constructor.parameters.size == creation.arguments.size &&
                argumentIndex in constructor.parameters.indices &&
                isEntityFactoryParameter(
                    candidate.owner.source.unit,
                    constructor.parameters[argumentIndex]
                )
        }.singleOrNull()
    }

    private fun directlyExtendsRegistrateEntityBuilder(owner: SpawnType): Boolean {
        if (!hasExactImport(
                owner.source.unit,
                "EntityBuilder",
                REGISTRATE_ENTITY_BUILDER_FQN
            )
        ) return false
        return owner.declaration.extendedTypes.size == 1 &&
            owner.declaration.extendedTypes.single().nameWithScope == "EntityBuilder"
    }

    private fun hasShadowingBinding(
        callable: CallableDeclaration<*>,
        parameter: Parameter,
        name: String
    ): Boolean {
        val parameterShadow = callable.findAll(Parameter::class.java).any {
            it !== parameter &&
                it.nameAsString == name &&
                it.findAncestor(CallableDeclaration::class.java).orElse(null) === callable
        }
        if (parameterShadow) return true
        return callable.findAll(VariableDeclarator::class.java).any {
            it.nameAsString == name &&
                it.findAncestor(CallableDeclaration::class.java).orElse(null) === callable
        }
    }
}

/**
 * Removes a closed Forge PlayMessages client factory and restores its position-constructor
 * effects through the target packet lifecycle. The complete legacy family is staged before the
 * first source write.
 */
internal fun StructuralRefactorPass.migrateLegacyClientSpawnFactories(
    project: Path,
    dryRun: Boolean
): List<Change> {
    val root = project.resolve("src/main/java")
    if (!Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths ->
        paths.filter { it.toString().endsWith(".java") }.toList()
    }
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val sourceTexts = files.associateWith { it.readText() }
    val parseResults = sourceTexts.mapValues { (_, source) -> parser.parse(source) }
    if (parseResults.values.any { !it.isSuccessful || !it.result.isPresent }) {
        return emptyList()
    }
    val sources = files.map { file ->
        SpawnSource(
            file,
            sourceTexts.getValue(file),
            parseResults.getValue(file).result.orElseThrow()
        )
    }
    if (declaresDirectJoinCancellation(sources)) return emptyList()
    val graph = EntityFactorySourceGraph(sources)
    val importedLegacySources = sources.filter { source ->
        source.unit.imports.any {
            !it.isStatic && !it.isAsterisk && it.nameAsString in LEGACY_SPAWN_IMPORTS
        }
    }
    if (importedLegacySources.isEmpty()) return emptyList()
    val accessTransformer = project.resolve(ACCESS_TRANSFORMER_RELATIVE)
    if (!Files.isRegularFile(accessTransformer) ||
        !hasAccessTransformerWiring(project)
    ) return emptyList()
    val accessTransformerSource = accessTransformer.readText()
    val accessTransformerPlan =
        planRawPacketAccessTransformers(accessTransformerSource) ?: return emptyList()
    val customFactoryFiles = sources.filter { source ->
        source.unit.findAll(MethodCallExpr::class.java).any {
            it.nameAsString == "setCustomClientFactory"
        }
    }.map { it.file }.toSet()
    if (customFactoryFiles != importedLegacySources.map { it.file }.toSet()) {
        return emptyList()
    }

    val plans = mutableListOf<LegacyFactoryPlan>()
    for (source in importedLegacySources) {
        val plan = planLegacyFactory(source, graph, sources) ?: return emptyList()
        plans += plan
    }

    val plansByFile = plans.groupBy { it.source.file }
    val staged = linkedMapOf<Path, String>()
    val changes = mutableListOf<Change>()
    for ((file, filePlans) in plansByFile) {
        val source = sourceTexts.getValue(file)
        val offsets = SpawnOffsets(source)
        val edits = mutableListOf<SpawnEdit>()
        for (plan in filePlans) {
            val scope = plan.customFactoryCall.scope.orElse(null) ?: return emptyList()
            val callStart = offsets.start(plan.customFactoryCall)
            val callEnd = offsets.endExclusive(plan.customFactoryCall)
            val methodRange = offsets.fullLineRange(plan.factory.method) ?: return emptyList()
            val readBodyStart = plan.factory.complexSpawn.readSpawnData.body
                .map { offsets.start(it) }
                .orElse(-1)
            if (callStart < 0 || callEnd <= callStart) return emptyList()
            if (readBodyStart < 0) return emptyList()

            edits += SpawnEdit(
                callStart,
                callEnd,
                offsets.text(scope)
            )
            val indent = source.substring(
                methodRange.first,
                offsets.start(plan.factory.method)
            ).takeWhile { it == ' ' || it == '\t' }
            val newline = if (source.contains("\r\n")) "\r\n" else "\n"
            edits += SpawnEdit(
                methodRange.first,
                methodRange.second,
                nativeRecreateOverride(indent, newline, plan.factory.snapshotFields)
            )
            edits += SpawnEdit(
                readBodyStart + 1,
                readBodyStart + 1,
                deferredLerpPrefix(indent, newline)
            )
        }

        var migrated = applySpawnEdits(source, edits)
        for (legacyImport in filePlans.map { it.legacyImport }.distinct()) {
            migrated = removeImport(migrated, legacyImport)
        }
        migrated = addImportIfMissing(migrated, ADD_ENTITY_PACKET_FQN)
        if (migrated == source) return emptyList()
        staged[file] = migrated

        for (plan in filePlans) {
            changes += Change(
                file = file,
                line = plan.customFactoryCall.range.map { it.begin.line }.orElse(1),
                description =
                    "Preserve raw-join and deferred-lerp phases on the native spawn lifecycle",
                before = "setCustomClientFactory(${plan.owner.name}::${plan.factory.method.nameAsString})",
                after =
                    "registered EntityFactory + recreateFromPacket + real readSpawnData prefix",
                confidence = Confidence.MEDIUM,
                ruleId = RULE_ID
            )
        }
    }
    if (accessTransformerPlan.migrated != accessTransformerSource) {
        staged[accessTransformer] = accessTransformerPlan.migrated
        changes += Change(
            file = accessTransformer,
            line = accessTransformerSource.lineSequence().count().coerceAtLeast(1),
            description = "Expose raw native add-entity packet velocity for legacy spawn order",
            before = accessTransformerPlan.addedLines.joinToString(" + ") { "missing $it" },
            after = accessTransformerPlan.addedLines.joinToString(" + "),
            confidence = Confidence.HIGH,
            ruleId = RULE_ID
        )
    }

    if (!dryRun) {
        staged.forEach { (file, migrated) -> file.writeText(migrated) }
    }
    return changes
}

private fun planLegacyFactory(
    source: SpawnSource,
    graph: EntityFactorySourceGraph,
    allSources: List<SpawnSource>
): LegacyFactoryPlan? {
    val imports = source.unit.imports.filter {
        !it.isStatic && !it.isAsterisk && it.nameAsString in LEGACY_SPAWN_IMPORTS
    }
    val legacyImport = imports.singleOrNull()?.nameAsString ?: return null
    if (!hasExactImport(source.unit, "Level", LEVEL_FQN)) return null
    if (!hasExactImport(source.unit, "LivingEntity", LIVING_ENTITY_FQN)) return null
    if (!hasExactImport(source.unit, "EntityType", ENTITY_TYPE_FQN)) return null
    if (!hasExactImport(source.unit, "IEntityWithComplexSpawn", COMPLEX_SPAWN_FQN)) return null
    if (!hasExactImport(source.unit, "RegistryFriendlyByteBuf", REGISTRY_BUFFER_FQN)) return null
    if (source.unit.imports.any {
            !it.isStatic &&
                !it.isAsterisk &&
                it.nameAsString.substringAfterLast('.') == "ClientboundAddEntityPacket" &&
                it.nameAsString != ADD_ENTITY_PACKET_FQN
        }
    ) return null

    val calls = source.unit.findAll(MethodCallExpr::class.java).filter {
        it.nameAsString == "setCustomClientFactory"
    }
    val customCall = calls.singleOrNull() ?: return null
    if (customCall.arguments.size != 1) return null
    val methodReference = customCall.arguments.single() as? MethodReferenceExpr ?: return null
    val ownerDeclaration = customCall.findAncestor(ClassOrInterfaceDeclaration::class.java)
        .orElse(null) ?: return null
    val owner = graph.types.singleOrNull { it.declaration === ownerDeclaration } ?: return null
    if (methodReference.scope.toString() != owner.name) return null
    if (methodReference.identifier == "new") return null

    if (owner.declaration.extendedTypes.size != 1 ||
        owner.declaration.extendedTypes.single().nameWithScope != "LivingEntity"
    ) return null
    if (owner.declaration.implementedTypes.none {
            it.nameWithScope == "IEntityWithComplexSpawn"
        }
    ) return null
    if (graph.hasSourceSubclass(owner)) return null
    if (owner.declaration.methods.any {
            it.nameAsString in setOf(
                "recreateFromPacket",
                "setPos",
                "refreshDimensions",
                "setDeltaMovement",
                "position",
                "onAddedToLevel"
            )
        }
    ) return null
    if (owner.declaration.fields.flatMap { it.variables }.any {
            it.nameAsString in GENERATED_MOTION_FIELDS
        }
    ) return null

    val factoryMethods = owner.declaration.methods.filter {
        it.nameAsString == methodReference.identifier
    }
    val factory = factoryMethods.singleOrNull() ?: return null
    if (!factory.isStatic || factory.type.asString() != owner.name) return null
    if (factory.parameters.size != 2 ||
        factory.parameters[0].type.asString() != "SpawnEntity" ||
        factory.parameters[1].type.asString() != "Level"
    ) return null

    if (!factoryUseIsClosed(owner, factory, methodReference, allSources)) return null
    if (!graph.provesRegisteredFactory(owner)) return null
    val positionConstructor = closedPositionConstructor(owner) ?: return null
    if (!hasRegisteredConstructor(owner)) return null
    val complexSpawn = authoritativeComplexSpawnMotion(owner) ?: return null
    if (!hasSpawnRefreshPath(owner, complexSpawn.readSpawnData)) return null
    if (!hasExactLegacyLerpMotion(owner)) return null
    val shape = closedFactoryBody(
        owner,
        factory,
        positionConstructor,
        complexSpawn
    ) ?: return null

    return LegacyFactoryPlan(
        source = source,
        owner = owner,
        legacyImport = legacyImport,
        customFactoryCall = customCall,
        factory = shape
    )
}

private fun factoryUseIsClosed(
    owner: SpawnType,
    factory: MethodDeclaration,
    admittedReference: MethodReferenceExpr,
    sources: List<SpawnSource>
): Boolean {
    val references = sources.flatMap { source ->
        source.unit.findAll(MethodReferenceExpr::class.java).filter {
            it.identifier == factory.nameAsString &&
                namesOwner(it.scope.toString(), owner.name)
        }
    }
    if (references.size != 1 || references.single() !== admittedReference) return false

    val directCalls = sources.flatMap { source ->
        source.unit.findAll(MethodCallExpr::class.java).filter { call ->
            if (call.nameAsString != factory.nameAsString) return@filter false
            val scope = call.scope.orElse(null)
            when (scope) {
                null -> call.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null) === owner.declaration
                is ThisExpr -> call.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null) === owner.declaration
                else -> namesOwner(scope.toString(), owner.name)
            }
        }
    }
    if (directCalls.isNotEmpty()) return false

    return sources.none { source ->
        source.unit.imports.any {
            it.isStatic &&
                !it.isAsterisk &&
                it.nameAsString.endsWith(".${owner.name}.${factory.nameAsString}")
        }
    }
}

private fun hasRegisteredConstructor(owner: SpawnType): Boolean {
    val constructors = owner.declaration.constructors.filter { constructor ->
        constructor.parameters.size == 2 &&
            normalizeType(constructor.parameters[0].type.asString()) == "EntityType<?>" &&
            constructor.parameters[1].type.asString() == "Level"
    }
    return constructors.size == 1
}

private fun closedPositionConstructor(owner: SpawnType): ConstructorDeclaration? {
    val constructor = owner.declaration.constructors.singleOrNull { candidate ->
        candidate.parameters.size == 4 &&
            candidate.parameters[0].type.asString() == "Level" &&
            candidate.parameters.drop(1).all { it.type.asString() == "double" }
    } ?: return null
    val level = constructor.parameters[0].nameAsString
    val x = constructor.parameters[1].nameAsString
    val y = constructor.parameters[2].nameAsString
    val z = constructor.parameters[3].nameAsString
    val statements = constructor.body.statements
    if (statements.size != 3) return null

    val delegation = statements[0] as? ExplicitConstructorInvocationStmt ?: return null
    if (!delegation.isThis || delegation.arguments.size != 2) return null
    if ((delegation.arguments[1] as? NameExpr)?.nameAsString != level) return null

    val setPos = (statements[1] as? ExpressionStmt)
        ?.expression as? MethodCallExpr ?: return null
    if (setPos.nameAsString != "setPos" ||
        setPos.scope.orElse(null) !is ThisExpr ||
        setPos.arguments.map { (it as? NameExpr)?.nameAsString } != listOf(x, y, z)
    ) return null

    val refresh = (statements[2] as? ExpressionStmt)
        ?.expression as? MethodCallExpr ?: return null
    if (refresh.nameAsString != "refreshDimensions" ||
        refresh.scope.orElse(null) !is ThisExpr ||
        refresh.arguments.isNotEmpty()
    ) return null
    return constructor
}

private fun closedFactoryBody(
    owner: SpawnType,
    factory: MethodDeclaration,
    positionConstructor: ConstructorDeclaration,
    complexSpawn: ComplexSpawnShape
): LegacyFactoryShape? {
    val body = factory.body.orElse(null) ?: return null
    val statements = body.statements
    if (statements.size < 3) return null
    if (factory.findAll(MethodCallExpr::class.java).any {
            it.nameAsString == "getAdditionalData"
        }
    ) return null

    val declaration = (statements.first() as? ExpressionStmt)
        ?.expression
        ?.let { it as? com.github.javaparser.ast.expr.VariableDeclarationExpr }
        ?.variables
        ?.singleOrNull()
        ?: return null
    if (declaration.type.asString() != owner.name) return null
    val entityName = declaration.nameAsString
    val packetName = factory.parameters[0].nameAsString
    val levelName = factory.parameters[1].nameAsString
    val creation = declaration.initializer.orElse(null) as? ObjectCreationExpr ?: return null
    if (creation.type.nameAsString != owner.name || creation.arguments.size != 4) return null
    if ((creation.arguments[0] as? NameExpr)?.nameAsString != levelName) return null
    val positionGetters = listOf("getPosX", "getPosY", "getPosZ")
    if (!positionGetters.indices.all { index ->
            isZeroArgumentCall(
                creation.arguments[index + 1],
                packetName,
                positionGetters[index]
            )
        }
    ) return null

    val velocity = (statements[1] as? ExpressionStmt)
        ?.expression as? MethodCallExpr ?: return null
    if (velocity.nameAsString != "setDeltaMovement" ||
        (velocity.scope.orElse(null) as? NameExpr)?.nameAsString != entityName ||
        velocity.arguments.size != 3
    ) return null
    val velocityGetters = listOf("getVelX", "getVelY", "getVelZ")
    if (!velocityGetters.indices.all { index ->
            isZeroArgumentCall(
                velocity.arguments[index],
                packetName,
                velocityGetters[index]
            )
        }
    ) return null

    val returned = statements.last() as? ReturnStmt ?: return null
    if ((returned.expression.orElse(null) as? NameExpr)?.nameAsString != entityName) return null

    val snapshots = mutableListOf<String>()
    for (statement in statements.drop(2).dropLast(1)) {
        val assignment = (statement as? ExpressionStmt)
            ?.expression as? AssignExpr ?: return null
        if (assignment.operator != AssignExpr.Operator.ASSIGN) return null
        val target = assignment.target as? FieldAccessExpr ?: return null
        if ((target.scope as? NameExpr)?.nameAsString != entityName) return null
        val value = assignment.value as? MethodCallExpr ?: return null
        if (value.nameAsString != "position" ||
            value.arguments.isNotEmpty() ||
            (value.scope.orElse(null) as? NameExpr)?.nameAsString != entityName
        ) return null
        val field = target.nameAsString
        if (owner.declaration.fields.flatMap { it.variables }.none {
                it.nameAsString == field
            }
        ) return null
        snapshots += field
    }
    if (snapshots.distinct().size != snapshots.size) return null

    return LegacyFactoryShape(factory, positionConstructor, snapshots, complexSpawn)
}

private fun authoritativeComplexSpawnMotion(owner: SpawnType): ComplexSpawnShape? {
    val write = owner.declaration.methods.singleOrNull {
        it.nameAsString == "writeSpawnData" &&
            it.parameters.size == 1 &&
            it.parameters.single().type.asString() == "RegistryFriendlyByteBuf"
    } ?: return null
    val read = owner.declaration.methods.singleOrNull {
        it.nameAsString == "readSpawnData" &&
            it.parameters.size == 1 &&
            it.parameters.single().type.asString() == "RegistryFriendlyByteBuf"
    } ?: return null
    val writeBody = write.body.orElse(null) ?: return null
    val readBody = read.body.orElse(null) ?: return null
    val writeBuffer = write.parameters.single().nameAsString
    val readBuffer = read.parameters.single().nameAsString

    val motionVariables = writeBody.statements.mapNotNull { statement ->
        val variable = (statement as? ExpressionStmt)
            ?.expression
            ?.let { it as? com.github.javaparser.ast.expr.VariableDeclarationExpr }
            ?.variables
            ?.singleOrNull()
            ?: return@mapNotNull null
        val initializer = variable.initializer.orElse(null) as? MethodCallExpr
            ?: return@mapNotNull null
        if (initializer.nameAsString == "getDeltaMovement" &&
            initializer.arguments.isEmpty() &&
            (initializer.scope.isEmpty || initializer.scope.get() is ThisExpr)
        ) variable.nameAsString else null
    }
    val motion = motionVariables.singleOrNull() ?: return null
    val writeCalls = writeBody.statements.mapNotNull { statement ->
        val call = (statement as? ExpressionStmt)?.expression as? MethodCallExpr
            ?: return@mapNotNull null
        if (call.nameAsString != "writeFloat" ||
            (call.scope.orElse(null) as? NameExpr)?.nameAsString != writeBuffer ||
            call.arguments.size != 1
        ) return@mapNotNull null
        call
    }
    if (writeCalls.size != 3) return null
    val writes = writeCalls.map { call ->
        floatField(call.arguments.single(), motion) ?: return null
    }
    if (writes != listOf("x", "y", "z")) return null

    val movementCalls = readBody.statements.mapNotNull { statement ->
        val call = (statement as? ExpressionStmt)?.expression as? MethodCallExpr
            ?: return@mapNotNull null
        if (call.nameAsString == "setDeltaMovement" &&
            (call.scope.isEmpty || call.scope.get() is ThisExpr)
        ) call else null
    }
    val movement = movementCalls.singleOrNull() ?: return null
    if (movement.arguments.size != 3) return null
    if (!movement.arguments.all { argument ->
        isZeroArgumentCall(argument, readBuffer, "readFloat")
    }) return null
    return ComplexSpawnShape(read)
}

private fun hasSpawnRefreshPath(
    owner: SpawnType,
    read: MethodDeclaration
): Boolean {
    val body = read.body.orElse(null) ?: return false
    return body.statements.any { statement ->
        val call = (statement as? ExpressionStmt)?.expression as? MethodCallExpr
            ?: return@any false
        if (call.scope.isPresent && call.scope.get() !is ThisExpr) return@any false
        owner.declaration.methods.any { target ->
            target.nameAsString == call.nameAsString &&
                target.parameters.size == call.arguments.size &&
                target.body.orElse(null)?.statements?.any { targetStatement ->
                    val targetCall = (targetStatement as? ExpressionStmt)
                        ?.expression as? MethodCallExpr
                    targetCall?.nameAsString == "refreshDimensions" &&
                        targetCall.arguments.isEmpty() &&
                        (targetCall.scope.isEmpty || targetCall.scope.get() is ThisExpr)
                } == true
        }
    }
}

private fun hasExactLegacyLerpMotion(owner: SpawnType): Boolean {
    val lerp = owner.declaration.methods.singleOrNull {
        it.nameAsString == "lerpMotion"
    } ?: return false
    if (lerp.isStatic ||
        lerp.type.asString() != "void" ||
        lerp.parameters.size != 3 ||
        lerp.parameters.any { it.type.asString() != "double" }
    ) return false
    val parameters = lerp.parameters.map { it.nameAsString }
    val statement = lerp.body.orElse(null)?.statements?.singleOrNull()
        as? ExpressionStmt ?: return false
    val setMovement = statement.expression as? MethodCallExpr ?: return false
    if (setMovement.nameAsString != "setDeltaMovement" ||
        setMovement.arguments.size != 1 ||
        (setMovement.scope.isPresent && setMovement.scope.get() !is ThisExpr)
    ) return false

    val scale = setMovement.arguments.single() as? MethodCallExpr ?: return false
    if (scale.nameAsString != "scale" || scale.arguments.size != 1) return false
    val half = scale.arguments.single() as? DoubleLiteralExpr ?: return false
    if (half.value.trimEnd('f', 'F', 'd', 'D').toDoubleOrNull() != 0.5) return false

    val add = scale.scope.orElse(null) as? MethodCallExpr ?: return false
    if (add.nameAsString != "add" ||
        add.arguments.map { (it as? NameExpr)?.nameAsString } != parameters
    ) return false
    val currentMovement = add.scope.orElse(null) as? MethodCallExpr ?: return false
    return currentMovement.nameAsString == "getDeltaMovement" &&
        currentMovement.arguments.isEmpty() &&
        (currentMovement.scope.isEmpty || currentMovement.scope.get() is ThisExpr)
}

private fun isZeroArgumentCall(
    expression: Expression,
    scopeName: String,
    methodName: String
): Boolean {
    val call = expression as? MethodCallExpr ?: return false
    return call.nameAsString == methodName &&
        call.arguments.isEmpty() &&
        (call.scope.orElse(null) as? NameExpr)?.nameAsString == scopeName
}

private fun floatField(expression: Expression, variable: String): String? {
    val unwrapped = if (expression is CastExpr && expression.type.asString() == "float") {
        expression.expression
    } else {
        expression
    }
    val field = unwrapped as? FieldAccessExpr ?: return null
    if ((field.scope as? NameExpr)?.nameAsString != variable) return null
    return field.nameAsString
}

private fun isEntityFactoryParameter(unit: CompilationUnit, parameter: Parameter): Boolean {
    val type = normalizeType(parameter.type.asString())
    return when {
        type.startsWith("EntityFactory<") ->
            hasExactImport(unit, "EntityFactory", ENTITY_FACTORY_FQN)
        type.startsWith("EntityType.EntityFactory<") ->
            hasExactImport(unit, "EntityType", ENTITY_TYPE_FQN)
        type.startsWith("$ENTITY_FACTORY_FQN<") -> true
        else -> false
    }
}

private fun hasExactImport(
    unit: CompilationUnit,
    simpleName: String,
    fullyQualifiedName: String
): Boolean {
    val exact = unit.imports.any {
            !it.isStatic && !it.isAsterisk && it.nameAsString == fullyQualifiedName
        }
    if (!exact) return false
    return unit.imports.none {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == simpleName &&
            it.nameAsString != fullyQualifiedName
    }
}

private fun normalizeType(type: String): String =
    type.filterNot(Char::isWhitespace)

private fun namesOwner(scope: String, ownerName: String): Boolean =
    scope == ownerName || scope.endsWith(".$ownerName")

private fun declaresDirectJoinCancellation(sources: List<SpawnSource>): Boolean =
    sources.any { source ->
        source.unit.findAll(MethodDeclaration::class.java).any { method ->
            method.parameters.any {
                it.type.asString().substringAfterLast('.') == ENTITY_JOIN_EVENT_SIMPLE
            } && method.findAll(MethodCallExpr::class.java).any {
                it.nameAsString == "setCanceled"
            }
        }
    }

private fun hasAccessTransformerWiring(project: Path): Boolean {
    val scripts = listOf(
        project.resolve("build.gradle"),
        project.resolve("build.gradle.kts")
    ).filter { Files.isRegularFile(it) }
    return scripts.any { script ->
        val compact = script.readText()
            .replace(Regex("\\s+"), "")
            .replace('"', '\'')
        val targetWiring =
            compact.contains("validateAccessTransformers=true") &&
                compact.contains(
                    "accessTransformers{file('$ACCESS_TRANSFORMER_RELATIVE')}"
                )
        // Structural refactors run before Build System converts the legacyForge block and enables
        // NeoForge AT validation. Admit only the exact existing AT file wiring that that later pass
        // preserves; a missing path or an unrelated access-transformer declaration still fails.
        val migratableLegacyWiring =
            compact.contains("legacyForge{") &&
                compact.contains("file('$ACCESS_TRANSFORMER_RELATIVE').exists()") &&
                compact.contains(
                    "accessTransformers.from'$ACCESS_TRANSFORMER_RELATIVE'"
                )
        targetWiring || migratableLegacyWiring
    }
}

private fun planRawPacketAccessTransformers(source: String): AccessTransformerPlan? {
    val directives = source.lineSequence().mapNotNull { line ->
        val directive = line.substringBefore('#').trim()
        if (directive.isEmpty()) null else directive
    }.toList()
    val missing = mutableListOf<String>()
    for ((index, field) in RAW_PACKET_FIELDS.withIndex()) {
        val matching = directives.map { it.split(Regex("\\s+")) }.filter { parts ->
            parts.size >= 3 &&
                parts[1] == ADD_ENTITY_PACKET_FQN &&
                parts[2] == field
        }
        if (matching.size > 1) return null
        val expected = RAW_PACKET_ACCESS_TRANSFORMERS[index]
        val directive = matching.singleOrNull()
        if (directive == null) {
            missing += expected
        } else if (directive != listOf("public", ADD_ENTITY_PACKET_FQN, field)) {
            return null
        }
    }
    if (missing.isEmpty()) return AccessTransformerPlan(source, emptyList())

    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val separator = if (source.isEmpty() ||
        source.endsWith("\n") ||
        source.endsWith("\r")
    ) "" else newline
    return AccessTransformerPlan(
        source + separator + missing.joinToString(newline) + newline,
        missing
    )
}

private fun nativeRecreateOverride(
    indent: String,
    newline: String,
    snapshotFields: List<String>
): String = buildString {
    append(indent)
    append("private boolean ")
    append(PENDING_MOTION_FIELD)
    append(';')
    append(newline)
    append(indent)
    append("private double ")
    append(PENDING_MOTION_X_FIELD)
    append(';')
    append(newline)
    append(indent)
    append("private double ")
    append(PENDING_MOTION_Y_FIELD)
    append(';')
    append(newline)
    append(indent)
    append("private double ")
    append(PENDING_MOTION_Z_FIELD)
    append(';')
    append(newline)
    append(newline)
    append(indent)
    append("@Override")
    append(newline)
    append(indent)
    append("public void recreateFromPacket(ClientboundAddEntityPacket packet) {")
    append(newline)
    append(indent)
    append('\t')
    append("setPos(packet.getX(), packet.getY(), packet.getZ());")
    append(newline)
    append(indent)
    append('\t')
    append("refreshDimensions();")
    append(newline)
    for (field in snapshotFields) {
        append(indent)
        append('\t')
        append(field)
        append(" = position();")
        append(newline)
    }
    append(indent)
    append('\t')
    append("super.recreateFromPacket(packet);")
    append(newline)
    append(indent)
    append('\t')
    append("setDeltaMovement(packet.xa, packet.ya, packet.za);")
    append(newline)
    append(indent)
    append('\t')
    append("this.")
    append(PENDING_MOTION_X_FIELD)
    append(" = packet.getXa();")
    append(newline)
    append(indent)
    append('\t')
    append("this.")
    append(PENDING_MOTION_Y_FIELD)
    append(" = packet.getYa();")
    append(newline)
    append(indent)
    append('\t')
    append("this.")
    append(PENDING_MOTION_Z_FIELD)
    append(" = packet.getZa();")
    append(newline)
    append(indent)
    append('\t')
    append("this.")
    append(PENDING_MOTION_FIELD)
    append(" = true;")
    append(newline)
    append(indent)
    append('}')
    append(newline)
}

private fun deferredLerpPrefix(
    indent: String,
    newline: String
): String = buildString {
    append(newline)
    append(indent)
    append('\t')
    append("if (this.")
    append(PENDING_MOTION_FIELD)
    append(") {")
    append(newline)
    append(indent)
    append("\t\tthis.")
    append(PENDING_MOTION_FIELD)
    append(" = false;")
    append(newline)
    append(indent)
    append("\t\tlerpMotion(this.")
    append(PENDING_MOTION_X_FIELD)
    append(", this.")
    append(PENDING_MOTION_Y_FIELD)
    append(", this.")
    append(PENDING_MOTION_Z_FIELD)
    append(");")
    append(newline)
    append(indent)
    append('\t')
    append('}')
}

private fun applySpawnEdits(source: String, edits: List<SpawnEdit>): String {
    if (edits.isEmpty()) return source
    val ordered = edits.sortedBy { it.start }
    if (ordered.any { it.start < 0 || it.endExclusive < it.start }) return source
    if (ordered.zipWithNext().any { (left, right) -> left.endExclusive > right.start }) {
        return source
    }
    val result = StringBuilder(source.length)
    var cursor = 0
    for (edit in ordered) {
        result.append(source, cursor, edit.start)
        result.append(edit.replacement)
        cursor = edit.endExclusive
    }
    result.append(source, cursor, source.length)
    return result.toString()
}
