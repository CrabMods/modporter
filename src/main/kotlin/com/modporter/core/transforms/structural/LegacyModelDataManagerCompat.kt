package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Preserves the Forge 1.20.1 nullable `BlockAndTintGetter#getModelDataManager()` contract for
 * project sources. Forge declared the getter on every block getter (null by default, the live
 * manager on ClientLevel and on RenderChunkRegion through its backing Level); NeoForge 21.1 keeps
 * it only on Level. Each query on a receiver proven to be a non-Level BlockAndTintGetter calls a
 * generated bridge with Forge's dispatch, and a source-owned getter that overrode the method
 * becomes a provider of that bridge. Admission is by declared types, the sealed source and target
 * platforms, and a proof that every consumer cannot observe NeoForge's ModelData.EMPTY where Forge
 * returned null; it never depends on a class, package or file name. The migration is
 * inventory-backed because NeoForge's RenderChunkRegion only exposes a snapshot while the legacy
 * contract requires the live backing Level manager and its null branch.
 */
internal object LegacyModelDataManagerCompatMigration {
    const val RULE_ID = "struct-legacy-model-data-manager-compat"
    const val COMPLETION_MARKER = "modporter-legacy-model-data-manager-compat-v1"

    private const val BLOCK_AND_TINT_GETTER =
        "net.minecraft.world.level.BlockAndTintGetter"
    private const val BLOCK_POS = "net.minecraft.core.BlockPos"
    private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
    private const val DIRECTION = "net.minecraft.core.Direction"
    private const val LEVEL = "net.minecraft.world.level.Level"
    private const val OLD_MANAGER =
        "net.minecraftforge.client.model.data.ModelDataManager"
    private const val TARGET_MANAGER =
        "net.neoforged.neoforge.client.model.data.ModelDataManager"
    private const val TARGET_MODEL_DATA =
        "net.neoforged.neoforge.client.model.data.ModelData"
    private const val OLD_MODEL_DATA =
        "net.minecraftforge.client.model.data.ModelData"
    private const val OLD_BLOCK_GETTER_EXTENSION =
        "net.minecraftforge.common.extensions.IForgeBlockGetter"
    private const val TARGET_LEVEL_EXTENSION =
        "net.neoforged.neoforge.common.extensions.ILevelExtension"
    private const val CLIENT_LEVEL = "net.minecraft.client.multiplayer.ClientLevel"
    private const val RENDER_CHUNK_REGION =
        "net.minecraft.client.renderer.chunk.RenderChunkRegion"

    private const val MANAGER_GETTER = "getModelDataManager"
    private const val PROVIDER_GETTER = "modporter\$getLegacyModelDataManager"
    private const val BACKING_LEVEL_GETTER = "modporter\$getBackingLevel"
    private const val MANAGER_DESCRIPTOR =
        "()Lnet/minecraftforge/client/model/data/ModelDataManager;"
    private const val TARGET_MANAGER_DESCRIPTOR =
        "()Lnet/neoforged/neoforge/client/model/data/ModelDataManager;"
    private const val TARGET_GET_AT_DESCRIPTOR =
        "(Lnet/minecraft/core/BlockPos;)" +
            "Lnet/neoforged/neoforge/client/model/data/ModelData;"

    private data class SourceUnit(
        val file: Path,
        val original: String,
        val unit: CompilationUnit,
        val canonicalTypes: Map<ClassOrInterfaceDeclaration, String>
    )

    private class ProviderPlan(
        val source: SourceUnit,
        val type: ClassOrInterfaceDeclaration,
        val method: MethodDeclaration,
        val addInterface: Boolean
    )

    private class CallPlan(
        val source: SourceUnit,
        val call: MethodCallExpr,
        val receiver: String
    )

    private class GenericPlan(
        val providers: List<ProviderPlan>,
        val calls: List<CallPlan>
    ) {
        val isEmpty: Boolean get() = providers.isEmpty() && calls.isEmpty()
    }

    private data class PlannedWrite(
        val file: Path,
        val before: String?,
        val after: String,
        val change: Change
    )

    fun migrate(
        pass: StructuralRefactorPass,
        project: Path,
        dryRun: Boolean
    ): List<Change> =
        migrate(
            pass,
            project,
            dryRun,
            LegacyMigrationArtifactInventoryHandoff.require(project)
        )

    internal fun migrate(
        pass: StructuralRefactorPass,
        project: Path,
        dryRun: Boolean,
        inventory: LegacyMigrationArtifactPair
    ): List<Change> {
        val normalizedProject = project.toAbsolutePath().normalize()
        val sourceRoot = normalizedProject.resolve("src/main/java")
        if (!sourceRoot.isDirectory()) return emptyList()

        val generatedPackage =
            pass.detectRequiredGeneratedCompatPackage(
                normalizedProject,
                "legacy nullable model-data manager bridge"
            )
        val generatedRoot = sourceRoot.resolve(generatedPackage.replace('.', '/'))
        val completionFile = generatedRoot.resolve("LegacyModelDataManagers.java")
        val sourceFiles = pass.walkJavaSourceRoots(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" && it.isRegularFile() }
                .filter {
                    !it.toString().replace('\\', '/')
                        .contains("/com/modporter/generated/")
                }
                .sorted()
                .toList()
        }
        val completed = completionFile.isRegularFile() &&
            completionFile.readText().contains(COMPLETION_MARKER)
        val candidate = completed || sourceFiles.any { it.readText().contains(MANAGER_GETTER) }
        if (!candidate) return emptyList()

        require(inventory.acceptsProject(normalizedProject)) {
            "Legacy model-data inventory belongs to another project; no files changed"
        }
        inventory.verifyCurrentBytes("legacy model-data manager preflight")
        require(
            inventory.source.platformIdentity.family == "legacy-forge" &&
                inventory.source.platformIdentity.minecraftVersion == "1.20.1"
        ) {
            "Legacy model-data source inventory is not Forge 1.20.1; no files changed"
        }
        require(
            inventory.target.platformIdentity.family == "neoforge" &&
                inventory.target.platformIdentity.minecraftVersion == "1.21.1"
        ) {
            "Legacy model-data target inventory is not NeoForge 1.21.1; no files changed"
        }

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val failures = mutableListOf<String>()
        val units = sourceFiles.mapNotNull { file ->
            val source = file.readText()
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful || parsed.result.isEmpty) {
                failures += "$file: ${pass.parseProblems(parsed)}"
                null
            } else {
                val unit = parsed.result.orElseThrow()
                SourceUnit(file, source, unit, canonicalTypes(unit))
            }
        }
        require(failures.isEmpty()) {
            "Cannot build a complete model-data source inventory because parsing failed: " +
                failures.joinToString("; ") +
                "; no files changed"
        }

        val plan = planGenericSources(units, generatedPackage)
        if (plan.isEmpty && !completed) return emptyList()
        verifySealedSurfaces(inventory, parser)
        val migrated = if (plan.isEmpty) {
            emptyMap()
        } else {
            migrateGenericSources(parser, plan, generatedPackage)
        }
        val providerFiles = plan.providers.map { it.source.file }.toSet()

        val writes = mutableListOf<PlannedWrite>()
        for ((unit, text) in migrated) {
            if (text == unit.original) continue
            writes += PlannedWrite(
                file = unit.file,
                before = unit.original,
                after = text,
                change = Change(
                    file = unit.file,
                    line = 1,
                    description = if (unit.file in providerFiles) {
                        "Preserve nullable manager forwarding through a source-owned provider"
                    } else {
                        "Preserve live nullable manager lookup through the legacy bridge"
                    },
                    before = "Forge BlockAndTintGetter#getModelDataManager()",
                    after = "$generatedPackage.LegacyModelDataManagers.resolve(...)",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            )
        }

        val generated = generatedSources(generatedPackage)
        for ((relative, content) in generated) {
            check(parser.parse(content).isSuccessful) {
                "Generated model-data source is invalid: $relative; no files changed"
            }
            planCanonicalWrite(
                writes,
                sourceRoot.resolve(relative),
                content,
                "Generate nullable live model-data compatibility bridge"
            )
        }

        val mixinConfigName = mixinConfigName(generatedPackage)
        val resourceRoot = normalizedProject.resolve("src/main/resources")
        val mixinConfig = generatedMixinConfig(generatedPackage)
        checkClientOnlyMixinConfig(mixinConfig)
        planCanonicalWrite(
            writes,
            resourceRoot.resolve(mixinConfigName),
            mixinConfig,
            "Register client-only backing-Level accessor for RenderChunkRegion"
        )

        val metadata = findNeoForgeMetadata(resourceRoot)
        val metadataBefore = metadata.readText()
        val metadataAfter = ensureMixinMetadata(metadataBefore, mixinConfigName)
        if (metadataAfter != metadataBefore) {
            writes += PlannedWrite(
                file = metadata,
                before = metadataBefore,
                after = metadataAfter,
                change = Change(
                    file = metadata,
                    line = 1,
                    description = "Register dedicated client model-data mixin config",
                    before = "(missing [[mixins]] registration)",
                    after = "config=\"$mixinConfigName\"",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            )
        }

        if (!dryRun && writes.isNotEmpty()) {
            applyTransaction(writes)
        }
        return writes.map { it.change }
    }

    /**
     * Plans every project use of the removed BlockAndTintGetter manager getter.
     *
     * A receiver is admitted only when its declared type is proven to be a BlockAndTintGetter that
     * is not a Level: Level and its subclasses keep `getModelDataManager()` on NeoForge 21.1
     * (ILevelExtension) and are not legacy uses. A source-owned getter class (not a Level) that
     * declares the old override becomes a provider, so the bridge keeps Forge's dispatch. Each
     * rewritten query must keep a consumer that cannot observe NeoForge's non-null
     * `ModelDataManager.getAt(BlockPos)` (ModelData.EMPTY where Forge returned null): the value is
     * only null-checked, returned by a provider, or read through `getAt(pos)` into a source-owned
     * static method that maps a null and a property-less ModelData to the same result.
     */
    private fun planGenericSources(
        units: List<SourceUnit>,
        generatedPackage: String
    ): GenericPlan {
        val index = TypeIndex(units)
        val providers = mutableListOf<ProviderPlan>()
        units.forEach { source ->
            source.unit.findAll(MethodDeclaration::class.java).forEach declaration@{ method ->
                if (method.nameAsString != MANAGER_GETTER || method.parameters.isNotEmpty()) {
                    return@declaration
                }
                val owner = method.parentNode.orElse(null) as? ClassOrInterfaceDeclaration
                    ?: return@declaration
                val ownerName = source.canonicalTypes[owner] ?: return@declaration
                if (owner.isInterface || index.classify(ownerName) != GetterKind.GETTER) return@declaration
                require(
                    index.resolve(source, method.typeAsString, method) in setOf(OLD_MANAGER, TARGET_MANAGER) &&
                        method.isPublic && !method.isStatic && method.body.isPresent
                ) {
                    "$ownerName declares a getModelDataManager() outside the public nullable manager " +
                        "override shape; no files changed"
                }
                val provider = "$generatedPackage.LegacyModelDataManagerProvider"
                providers += ProviderPlan(
                    source,
                    owner,
                    method,
                    owner.implementedTypes.none { canonicalType(source.unit, it.asString()) == provider }
                )
            }
        }
        val providerMethods = providers.map { it.method }

        val calls = mutableListOf<CallPlan>()
        units.forEach { source ->
            source.unit.findAll(MethodReferenceExpr::class.java).forEach reference@{ reference ->
                if (reference.identifier != MANAGER_GETTER) return@reference
                val type = (reference.scope as? com.github.javaparser.ast.expr.TypeExpr)
                    ?.let { index.resolve(source, it.type.asString(), reference) }
                    ?: index.receiverType(source, reference.scope)
                require(type == null || index.classify(type) != GetterKind.GETTER) {
                    "${source.file} uses getModelDataManager as a method reference on a " +
                        "BlockAndTintGetter; no files changed"
                }
            }
            source.unit.findAll(MethodCallExpr::class.java).forEach query@{ call ->
                if (call.nameAsString != MANAGER_GETTER || call.arguments.isNotEmpty()) return@query
                val scope = call.scope.orElse(null)
                val receiverType = if (scope == null) {
                    index.enclosingType(source, call)
                } else {
                    require(scope !is com.github.javaparser.ast.expr.SuperExpr ||
                        index.enclosingType(source, call)?.let(index::classify) != GetterKind.GETTER
                    ) {
                        "${source.file} calls super.getModelDataManager() in a BlockAndTintGetter; " +
                            "no files changed"
                    }
                    index.receiverType(source, scope)
                } ?: return@query
                if (index.classify(receiverType) != GetterKind.GETTER) return@query
                val failure = consumerFailure(source, call, providerMethods, index)
                require(failure == null) {
                    "${source.file}:${call.range.map { it.begin.line }.orElse(0)} " +
                        "getModelDataManager() $failure; no files changed"
                }
                calls += CallPlan(
                    source,
                    call,
                    when (scope) {
                        null, is ThisExpr -> "this"
                        else -> scope.toString()
                    }
                )
            }
        }
        return GenericPlan(providers, calls)
    }

    private fun consumerFailure(
        source: SourceUnit,
        call: MethodCallExpr,
        providers: List<MethodDeclaration>,
        index: TypeIndex
    ): String? {
        when (val parent = call.parentNode.orElse(null)) {
            is ReturnStmt -> {
                val callable = parent.findAncestor(MethodDeclaration::class.java).orElse(null)
                val lambda = parent.findAncestor(LambdaExpr::class.java).orElse(null)
                return if (lambda == null && providers.any { it === callable }) {
                    null
                } else {
                    "returns the manager to callers that are not proven null/EMPTY-insensitive"
                }
            }
            is BinaryExpr -> return if (isNullComparison(parent, call)) null else
                "is compared outside a null check"
            is MethodCallExpr -> return if (parent.scope.orElse(null) === call) {
                getAtFailure(source, parent, index)
            } else {
                "is passed to a method that is not a proven consumer"
            }
            is VariableDeclarator -> {
                val name = parent.nameAsString
                val block = parent.findAncestor(BlockStmt::class.java).orElse(null)
                    ?: return "is stored outside a block-local variable"
                val declarationEnd = parent.range.map { it.end }.orElse(null)
                    ?: return "has no source range"
                if (block.findAll(VariableDeclarator::class.java).count { it.nameAsString == name } != 1 ||
                    block.findAll(LambdaExpr::class.java).any { lambda ->
                        lambda.parameters.any { it.nameAsString == name }
                    } ||
                    block.findAll(AssignExpr::class.java).any {
                        (it.target as? NameExpr)?.nameAsString == name
                    }
                ) {
                    return "is held in a local that is shadowed or reassigned"
                }
                block.findAll(NameExpr::class.java).forEach { use ->
                    if (use.nameAsString != name ||
                        use.range.map { !it.begin.isAfter(declarationEnd) }.orElse(true)
                    ) return@forEach
                    val failure = when (val holder = use.parentNode.orElse(null)) {
                        is BinaryExpr -> if (isNullComparison(holder, use)) null else
                            "is compared outside a null check"
                        is MethodCallExpr -> if (holder.scope.orElse(null) === use) {
                            getAtFailure(source, holder, index)
                        } else {
                            "is passed to a method that is not a proven consumer"
                        }
                        else -> "is used outside null checks and getAt(BlockPos)"
                    }
                    if (failure != null) return failure
                }
                return null
            }
            else -> return "is used outside null checks, getAt(BlockPos), and provider returns"
        }
    }

    private fun getAtFailure(
        source: SourceUnit,
        getAt: MethodCallExpr,
        index: TypeIndex
    ): String? {
        if (getAt.nameAsString != "getAt" || getAt.arguments.size != 1) {
            return "reaches a ModelDataManager member other than getAt(BlockPos)"
        }
        val position = index.receiverType(source, getAt.arguments.single())
        if (position != BLOCK_POS) return "reaches getAt with an argument not proven to be a BlockPos"
        val consumer = getAt.parentNode.orElse(null) as? MethodCallExpr
            ?: return "reads getAt(BlockPos) outside a proven null/EMPTY-insensitive consumer"
        if (consumer.arguments.singleOrNull() !== getAt) {
            return "reads getAt(BlockPos) outside a proven null/EMPTY-insensitive consumer"
        }
        val target = index.staticMethod(source, consumer)
            ?: return "passes getAt(BlockPos) to a consumer that is not one source-owned static method"
        val (targetSource, method) = target
        val parameter = method.parameters.single()
        if (index.resolve(targetSource, parameter.typeAsString, parameter) !in
            setOf(OLD_MODEL_DATA, TARGET_MODEL_DATA)
        ) {
            return "passes getAt(BlockPos) to ${method.nameAsString} without a ModelData parameter"
        }
        return if (nullAndEmptyAgree(method, parameter.nameAsString)) null else
            "passes getAt(BlockPos) to ${method.nameAsString}, which can tell null from ModelData.EMPTY"
    }

    /**
     * Whether every read of [name] is `name == null ? null : name.get(P)` (or the `!=` form), so a
     * null value and ModelData.EMPTY (whose `get` returns null for every property) agree.
     */
    private fun nullAndEmptyAgree(method: MethodDeclaration, name: String): Boolean {
        val body = method.body.orElse(null) ?: return false
        if (body.findAll(AssignExpr::class.java).any { (it.target as? NameExpr)?.nameAsString == name } ||
            body.findAll(VariableDeclarator::class.java).any { it.nameAsString == name } ||
            body.findAll(LambdaExpr::class.java).any { lambda ->
                lambda.parameters.any { it.nameAsString == name }
            }
        ) return false
        val uses = body.findAll(NameExpr::class.java).filter { it.nameAsString == name }
        return uses.isNotEmpty() && uses.all { use ->
            val conditional = use.findAncestor(ConditionalExpr::class.java).orElse(null)
                ?: return@all false
            val condition = conditional.condition as? BinaryExpr ?: return@all false
            if (!isNullComparison(condition, null, name)) return@all false
            val (nullBranch, valueBranch) = if (condition.operator == BinaryExpr.Operator.EQUALS) {
                conditional.thenExpr to conditional.elseExpr
            } else {
                conditional.elseExpr to conditional.thenExpr
            }
            val get = valueBranch as? MethodCallExpr ?: return@all false
            nullBranch is NullLiteralExpr &&
                get.nameAsString == "get" &&
                get.arguments.size == 1 &&
                (get.arguments.single() is NameExpr || get.arguments.single() is FieldAccessExpr) &&
                (get.scope.orElse(null) as? NameExpr)?.nameAsString == name &&
                (use === get.scope.orElse(null) || condition.left === use || condition.right === use)
        }
    }

    private fun isNullComparison(
        expression: BinaryExpr,
        operand: com.github.javaparser.ast.expr.Expression?,
        name: String? = null
    ): Boolean {
        if (expression.operator != BinaryExpr.Operator.EQUALS &&
            expression.operator != BinaryExpr.Operator.NOT_EQUALS
        ) return false
        fun matches(side: com.github.javaparser.ast.expr.Expression): Boolean =
            if (name != null) (side as? NameExpr)?.nameAsString == name else side === operand
        return (matches(expression.left) && expression.right is NullLiteralExpr) ||
            (matches(expression.right) && expression.left is NullLiteralExpr)
    }

    private fun migrateGenericSources(
        parser: JavaParser,
        plan: GenericPlan,
        generatedPackage: String
    ): Map<SourceUnit, String> {
        val touched = (plan.providers.map { it.source } + plan.calls.map { it.source })
            .distinctBy { it.file }
        touched.forEach { LexicalPreservingPrinter.setup(it.unit) }
        val resolver = "$generatedPackage.LegacyModelDataManagers"
        plan.calls.forEach { call ->
            val provider = plan.providers.singleOrNull { provider ->
                provider.method.body.orElse(null)?.statements?.singleOrNull()
                    ?.let { it as? ReturnStmt }?.expression?.orElse(null) === call.call
            }
            if (provider != null) return@forEach
            call.call.replace(
                StaticJavaParser.parseExpression("$resolver.resolve(${call.receiver})")
            )
        }
        plan.providers.forEach { provider ->
            if (provider.addInterface) {
                provider.type.addImplementedType("$generatedPackage.LegacyModelDataManagerProvider")
            }
            provider.method.setName(PROVIDER_GETTER)
            val single = provider.method.body.orElse(null)?.statements?.singleOrNull()
                ?.let { it as? ReturnStmt }?.expression?.orElse(null)
            val returned = plan.calls.singleOrNull { it.call === single }
            if (returned != null) {
                provider.method.setBody(
                    StaticJavaParser.parseBlock("{ return $resolver.resolve(${returned.receiver}); }")
                )
            }
        }
        return touched.associateWith { source ->
            val text = LexicalPreservingPrinter.print(source.unit)
                .let { if (it.endsWith('\n')) it else "$it\n" }
            check(parser.parse(text).isSuccessful) {
                "Model-data migration emitted invalid Java for ${source.file}; no files changed"
            }
            text
        }
    }

    /** Whether project source has a use this migration owns (shared with the inventory prepass). */
    internal fun hasLegacySurface(units: List<CompilationUnit>): Boolean {
        val sources = units.map { SourceUnit(Path.of(""), "", it, canonicalTypes(it)) }
        val index = TypeIndex(sources)
        return sources.any { source ->
            source.unit.findAll(MethodDeclaration::class.java).any { method ->
                method.nameAsString == MANAGER_GETTER && method.parameters.isEmpty() &&
                    (method.parentNode.orElse(null) as? ClassOrInterfaceDeclaration)?.let { owner ->
                        !owner.isInterface &&
                            source.canonicalTypes[owner]?.let(index::classify) == GetterKind.GETTER
                    } == true
            } || source.unit.findAll(MethodCallExpr::class.java).any { call ->
                call.nameAsString == MANAGER_GETTER && call.arguments.isEmpty() &&
                    (
                        call.scope.orElse(null)?.let { index.receiverType(source, it) }
                            ?: index.enclosingType(source, call)
                        )?.let(index::classify) == GetterKind.GETTER
            }
        }
    }

    private enum class GetterKind { GETTER, LEVEL, OTHER, UNKNOWN }

    /** Source-declared and 1.21.1 platform types, resolved conservatively. */
    private class TypeIndex(private val units: List<SourceUnit>) {
        private val declarations: Map<String, Pair<SourceUnit, ClassOrInterfaceDeclaration>> =
            units.flatMap { source ->
                source.canonicalTypes.map { (declaration, name) -> name to (source to declaration) }
            }.groupBy({ it.first }, { it.second })
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }

        fun classify(type: String): GetterKind {
            val pending = ArrayDeque(listOf(type))
            val seen = mutableSetOf<String>()
            var getter = false
            var level = false
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current) || current == "java.lang.Object") continue
                // The generated provider contract is not a block getter and declares no getter.
                if (current.startsWith("com.modporter.generated.")) continue
                val declared = declarations[current]
                if (declared != null) {
                    val (source, declaration) = declared
                    (declaration.extendedTypes + declaration.implementedTypes).forEach { supertype ->
                        pending += resolve(source, supertype.nameWithScope, declaration)
                            ?: return GetterKind.UNKNOWN
                    }
                    continue
                }
                if (!TargetMembers.hasClass(current)) return GetterKind.UNKNOWN
                if (TargetMembers.declaresInHierarchy(current, MANAGER_GETTER, 0)) level = true
                if (current == BLOCK_AND_TINT_GETTER ||
                    TargetMembers.inheritsFrom(current, BLOCK_AND_TINT_GETTER)
                ) getter = true
            }
            return when {
                level -> GetterKind.LEVEL
                getter -> GetterKind.GETTER
                else -> GetterKind.OTHER
            }
        }

        fun resolve(source: SourceUnit, raw: String, context: Node): String? {
            val cleaned = raw
                .replace(Regex("""@[\w.]+(?:\([^)]*\))?\s*"""), "")
                .substringBefore('<')
                .replace(" ", "")
                .removeSuffix("[]")
                .trim()
            if (cleaned.isEmpty() || cleaned == "var") return null
            val first = cleaned.substringBefore('.')
            val rest = cleaned.removePrefix(first)
            if (first.first().isLowerCase()) return cleaned
            var current: Node? = context
            while (current != null) {
                val parameters = when (current) {
                    is ClassOrInterfaceDeclaration -> current.typeParameters
                    is MethodDeclaration -> current.typeParameters
                    is com.github.javaparser.ast.body.ConstructorDeclaration -> current.typeParameters
                    else -> null
                }
                if (parameters?.any { it.nameAsString == first } == true) return null
                current = current.parentNode.orElse(null)
            }
            val local = source.canonicalTypes.entries.filter { it.key.nameAsString == first }
            if (local.isNotEmpty()) return local.singleOrNull()?.value?.plus(rest)
            val explicit = source.unit.imports.filter {
                !it.isStatic && !it.isAsterisk && it.name.identifier == first
            }.map { it.nameAsString }.distinct()
            if (explicit.isNotEmpty()) return explicit.singleOrNull()?.plus(rest)
            val packageName = source.unit.packageDeclaration.map { it.nameAsString }.orElse("")
            val samePackage = if (packageName.isBlank()) first else "$packageName.$first"
            if (samePackage in declarations) return samePackage + rest
            val onDemand = source.unit.imports.filter { !it.isStatic && it.isAsterisk }
                .map { "${it.nameAsString}.$first" }
                .filter { it in declarations || TargetMembers.hasClass(it) }
                .distinct()
            if (onDemand.isNotEmpty()) return onDemand.singleOrNull()?.plus(rest)
            if (first in setOf("Object", "String")) return "java.lang.$first$rest"
            return null
        }

        /** The declared type of a name, `this`, or `this.field` receiver; null when unproven. */
        fun receiverType(source: SourceUnit, expression: com.github.javaparser.ast.expr.Expression): String? =
            when (expression) {
                is ThisExpr -> if (expression.typeName.isPresent) null else enclosingType(source, expression)
                is FieldAccessExpr -> if (expression.scope is ThisExpr) {
                    fieldType(source, expression, expression.nameAsString)
                } else {
                    null
                }
                is NameExpr -> nameType(source, expression)
                is com.github.javaparser.ast.expr.EnclosedExpr -> receiverType(source, expression.inner)
                else -> null
            }

        /** The class whose instance an unqualified call or `this` denotes, unless anonymous. */
        fun enclosingType(source: SourceUnit, node: Node): String? {
            var current = node.parentNode.orElse(null)
            while (current != null) {
                when (current) {
                    is com.github.javaparser.ast.expr.ObjectCreationExpr ->
                        if (current.anonymousClassBody.isPresent) return null
                    is ClassOrInterfaceDeclaration -> return source.canonicalTypes[current]
                    is com.github.javaparser.ast.body.TypeDeclaration<*> -> return null
                }
                current = current.parentNode.orElse(null)
            }
            return null
        }

        private fun nameType(source: SourceUnit, name: NameExpr): String? {
            val identifier = name.nameAsString
            var current = name.parentNode.orElse(null)
            while (current != null) {
                when (current) {
                    is LambdaExpr -> current.parameters.firstOrNull { it.nameAsString == identifier }
                        ?.let { parameter ->
                            return if (parameter.type.isUnknownType) null else
                                resolve(source, parameter.typeAsString, parameter)
                        }
                    is com.github.javaparser.ast.stmt.CatchClause ->
                        if (current.parameter.nameAsString == identifier) return null
                    is com.github.javaparser.ast.stmt.ForEachStmt ->
                        if (current.variable.variables.any { it.nameAsString == identifier }) return null
                    is MethodDeclaration, is com.github.javaparser.ast.body.ConstructorDeclaration -> {
                        val callable = current as com.github.javaparser.ast.body.CallableDeclaration<*>
                        if (current.findAll(com.github.javaparser.ast.expr.TypePatternExpr::class.java)
                                .any { it.nameAsString == identifier }
                        ) return null
                        val locals = current.findAll(VariableDeclarator::class.java)
                            .filter { it.nameAsString == identifier }
                        val parameters = callable.parameters.filter { it.nameAsString == identifier }
                        if (locals.size + parameters.size > 1) return null
                        locals.singleOrNull()?.let { return resolve(source, it.typeAsString, it) }
                        parameters.singleOrNull()?.let { return resolve(source, it.typeAsString, it) }
                        return fieldType(source, name, identifier)
                    }
                    is com.github.javaparser.ast.body.TypeDeclaration<*> -> return null
                }
                current = current.parentNode.orElse(null)
            }
            return null
        }

        private fun fieldType(source: SourceUnit, use: Node, field: String): String? {
            var owner = enclosingType(source, use)
            val seen = mutableSetOf<String>()
            while (owner != null && seen.add(owner)) {
                val (ownerSource, declaration) = declarations[owner] ?: return null
                declaration.fields.flatMap { it.variables }.singleOrNull { it.nameAsString == field }
                    ?.let { return resolve(ownerSource, it.typeAsString, it) }
                val parent = declaration.extendedTypes.singleOrNull() ?: return null
                owner = resolve(ownerSource, parent.nameWithScope, declaration)
            }
            return null
        }

        /** The one source-owned static method a single-argument [call] binds to. */
        fun staticMethod(
            source: SourceUnit,
            call: MethodCallExpr
        ): Pair<SourceUnit, MethodDeclaration>? {
            val scope = call.scope.orElse(null)
            val owner = when {
                scope == null -> enclosingType(source, call)
                scope is NameExpr && scope.nameAsString.first().isUpperCase() &&
                    nameType(source, scope) == null -> resolve(source, scope.nameAsString, call)
                else -> null
            } ?: return null
            val (ownerSource, declaration) = declarations[owner] ?: return null
            val candidates = declaration.methods.filter {
                it.nameAsString == call.nameAsString && it.parameters.size == call.arguments.size
            }
            val method = candidates.singleOrNull() ?: return null
            if (!method.isStatic || declaration.extendedTypes.isNotEmpty() && scope == null) return null
            return ownerSource to method
        }
    }

    private fun verifySealedSurfaces(
        inventory: LegacyMigrationArtifactPair,
        parser: JavaParser
    ) {
        val sourceArtifacts = activeSourceClasspath(inventory.source)
        val sourceClasses = ClassPathIndex.read(
            sourceArtifacts,
            inventory.source.classRuntimeFeature,
            "legacy model-data source classpath"
        )
        val expectedDeclarers = setOf(
            OLD_BLOCK_GETTER_EXTENSION,
            CLIENT_LEVEL,
            RENDER_CHUNK_REGION
        )
        val declarers = sourceClasses.declarers(MANAGER_GETTER, MANAGER_DESCRIPTOR)
        require(declarers.map { it.first }.toSet() == expectedDeclarers) {
            "Sealed source compile classpath has unexpected ModelDataManager getter " +
                "declarers: ${declarers.map { it.first }}; no files changed"
        }
        require(declarers.all { (_, artifact) ->
            artifact.sameBytesAs(inventory.source.platformArtifacts.named)
        }) {
            "A project or dependency artifact declares the removed nullable manager getter; " +
                "no files changed"
        }
        require(
            sourceClasses.hasPublicConcreteInstanceMethod(
                OLD_BLOCK_GETTER_EXTENSION,
                MANAGER_GETTER,
                MANAGER_DESCRIPTOR
            ) &&
                sourceClasses.hasPublicConcreteInstanceMethod(
                    CLIENT_LEVEL,
                    MANAGER_GETTER,
                    MANAGER_DESCRIPTOR
                ) &&
                sourceClasses.hasPublicConcreteInstanceMethod(
                    RENDER_CHUNK_REGION,
                    MANAGER_GETTER,
                    MANAGER_DESCRIPTOR
                )
        ) {
            "Sealed Forge platform lacks the exact old manager forwarding ABI; " +
                "no files changed"
        }

        verifySourcePlatformSources(
            inventory.source.platformArtifacts.sources,
            parser
        )

        val targetClasses = ClassPathIndex.read(
            listOf(inventory.target.platformArtifacts.merged),
            21,
            "legacy model-data target platform"
        )
        require(
            targetClasses.hasPublicConcreteInstanceMethod(
                TARGET_LEVEL_EXTENSION,
                MANAGER_GETTER,
                TARGET_MANAGER_DESCRIPTOR
            ) &&
                targetClasses.inheritsFrom(LEVEL, TARGET_LEVEL_EXTENSION) ==
                HeritageProof.YES &&
                targetClasses.hasPublicConcreteInstanceMethod(
                    TARGET_MANAGER,
                    "getAt",
                    TARGET_GET_AT_DESCRIPTOR
                ) &&
                targetClasses.hasField(
                    RENDER_CHUNK_REGION,
                    "level",
                    "Lnet/minecraft/world/level/Level;",
                    requiredAccess = ACC_PROTECTED or ACC_FINAL
                ) &&
                !targetClasses.hasMethod(
                    RENDER_CHUNK_REGION,
                    MANAGER_GETTER,
                    TARGET_MANAGER_DESCRIPTOR
                )
        ) {
            "Sealed NeoForge target ABI does not match the live manager/backing-Level bridge; " +
                "no files changed"
        }
        verifyTargetPlatformSources(
            inventory.target.platformArtifacts.sources,
            parser
        )
    }

    private fun activeSourceClasspath(
        source: LegacyMigrationArtifactInventory.Source
    ): List<LegacyMigrationArtifactInventory.Artifact> {
        val configuration = source.compileClasspathConfiguration
        val active = source.closureArtifacts.filter {
            configuration in it.resolutionConfigurations
        }.sortedBy {
            it.configurationOrder.getValue(configuration)
        }
        require(active.isNotEmpty()) {
            "Sealed source compile classpath is empty; no files changed"
        }
        require(active.any { it.sameBytesAs(source.platformArtifacts.named) }) {
            "Sealed source compile classpath omits the exact generated platform; " +
                "no files changed"
        }
        active.forEach {
            it.verifyCurrentBytes("legacy model-data exact source classpath")
        }
        return active
    }

    private fun verifySourcePlatformSources(
        artifact: LegacyMigrationArtifactInventory.Artifact,
        parser: JavaParser
    ) {
        val blockGetter = parseJarSource(
            artifact,
            "net/minecraftforge/common/extensions/IForgeBlockGetter.java",
            parser
        )
        val getter = blockGetter.findAll(MethodDeclaration::class.java).singleOrNull {
            it.nameAsString == MANAGER_GETTER &&
                it.parameters.isEmpty() &&
                canonicalType(blockGetter, it.typeAsString) == OLD_MANAGER
        } ?: error("Forge IForgeBlockGetter source lacks nullable manager default; no files changed")
        require(
            getter.isDefault &&
                getter.annotations.any { it.nameAsString == "Nullable" } &&
                getter.body.orElseThrow().statements.single().asReturnStmt()
                    .expression.orElse(null) is NullLiteralExpr &&
                getter.annotations.none { it.nameAsString == "OnlyIn" }
        ) {
            "Forge IForgeBlockGetter nullable manager contract changed; no files changed"
        }

        val region = parseJarSource(
            artifact,
            "net/minecraft/client/renderer/chunk/RenderChunkRegion.java",
            parser
        )
        val regionType = region.findAll(ClassOrInterfaceDeclaration::class.java).single {
            it.nameAsString == "RenderChunkRegion"
        }
        require(isClientOnly(regionType)) {
            "Forge RenderChunkRegion is not verified client-only; no files changed"
        }
        require(
            regionType.fields.flatMap { it.variables }.any {
                it.nameAsString == "level" &&
                    canonicalType(region, it.typeAsString) == LEVEL &&
                    it.parentNode.orElse(null)?.let { parent ->
                        parent is com.github.javaparser.ast.body.FieldDeclaration &&
                            parent.isProtected && parent.isFinal
                    } == true
            }
        ) {
            "Forge RenderChunkRegion backing Level field changed; no files changed"
        }
        val regionGetter = regionType.methods.singleOrNull {
            it.nameAsString == MANAGER_GETTER && it.parameters.isEmpty()
        } ?: error("Forge RenderChunkRegion manager forwarder is missing; no files changed")
        require(
            isDirectManagerForwarder(
                regionGetter.body.orElseThrow().statements.singleOrNull(),
                "level"
            )
        ) {
            "Forge RenderChunkRegion no longer directly forwards its backing Level manager; " +
                "no files changed"
        }
    }

    private fun verifyTargetPlatformSources(
        artifact: LegacyMigrationArtifactInventory.Artifact,
        parser: JavaParser
    ) {
        val extension = parseJarSource(
            artifact,
            "net/neoforged/neoforge/common/extensions/ILevelExtension.java",
            parser
        )
        val extensionType = extension.findAll(ClassOrInterfaceDeclaration::class.java).single {
            it.nameAsString == "ILevelExtension"
        }
        val getter = extensionType.methods.singleOrNull {
            it.nameAsString == MANAGER_GETTER && it.parameters.isEmpty()
        } ?: error("NeoForge ILevelExtension manager getter is missing; no files changed")
        require(
            extensionType.annotations.none { it.nameAsString == "OnlyIn" } &&
                getter.annotations.none { it.nameAsString == "OnlyIn" } &&
                getter.annotations.any { it.nameAsString == "Nullable" } &&
                getter.isDefault &&
                canonicalType(extension, getter.typeAsString) == TARGET_MANAGER
        ) {
            "NeoForge Level manager getter is dist-restricted or changed; no files changed"
        }

        val manager = parseJarSource(
            artifact,
            "net/neoforged/neoforge/client/model/data/ModelDataManager.java",
            parser
        )
        val managerType = manager.findAll(ClassOrInterfaceDeclaration::class.java).single {
            it.nameAsString == "ModelDataManager"
        }
        require(
            managerType.isPublic &&
                managerType.annotations.none { it.nameAsString == "OnlyIn" } &&
                managerType.methods.any {
                    it.nameAsString == "getAt" &&
                        it.parameters.size == 1 &&
                        canonicalType(manager, it.parameters.single().typeAsString) ==
                        BLOCK_POS &&
                        canonicalType(manager, it.typeAsString) == TARGET_MODEL_DATA
                }
        ) {
            "NeoForge ModelDataManager is dist-restricted or lacks getAt(BlockPos); " +
                "no files changed"
        }

        val region = parseJarSource(
            artifact,
            "net/minecraft/client/renderer/chunk/RenderChunkRegion.java",
            parser
        )
        val regionType = region.findAll(ClassOrInterfaceDeclaration::class.java).single {
            it.nameAsString == "RenderChunkRegion"
        }
        require(
            isClientOnly(regionType) &&
                regionType.fields.flatMap { it.variables }.any {
                    it.nameAsString == "level" &&
                        canonicalType(region, it.typeAsString) == LEVEL &&
                        it.parentNode.orElse(null)?.let { parent ->
                            parent is com.github.javaparser.ast.body.FieldDeclaration &&
                                parent.isProtected && parent.isFinal
                        } == true
                } &&
                regionType.methods.none {
                    it.nameAsString == MANAGER_GETTER && it.parameters.isEmpty()
                } &&
                regionType.methods.any {
                    it.nameAsString == "getModelData" &&
                        it.parameters.size == 1 &&
                        canonicalType(region, it.parameters.single().typeAsString) ==
                        BLOCK_POS
                }
        ) {
            "NeoForge RenderChunkRegion source no longer matches the client-only " +
                "backing-Level accessor plan; no files changed"
        }

        // getAt(BlockPos) answers ModelData.EMPTY where Forge answered null; consumers are
        // admitted only when they read properties through `get`, which EMPTY never holds.
        val modelData = parseJarSource(
            artifact,
            "net/neoforged/neoforge/client/model/data/ModelData.java",
            parser
        )
        val modelDataType = modelData.findAll(ClassOrInterfaceDeclaration::class.java).single {
            it.nameAsString == "ModelData"
        }
        val empty = modelDataType.fields.flatMap { it.variables }.singleOrNull {
            it.nameAsString == "EMPTY"
        }?.initializer?.orElse(null)?.toString()?.replace(" ", "")
        val get = modelDataType.methods.singleOrNull {
            it.nameAsString == "get" && it.parameters.size == 1
        }
        val getReturn = get?.body?.orElse(null)?.statements?.singleOrNull()
            ?.let { it as? ReturnStmt }?.expression?.orElse(null)
            ?.let { if (it is com.github.javaparser.ast.expr.CastExpr) it.expression else it }
            as? MethodCallExpr
        val builder = modelDataType.methods.singleOrNull {
            it.nameAsString == "builder" && it.parameters.isEmpty() && it.isStatic
        }?.body?.orElse(null)?.statements?.singleOrNull()
            ?.let { it as? ReturnStmt }?.expression?.orElse(null)?.toString()?.replace(" ", "")
        require(
            empty in setOf("ModelData.builder().build()", "builder().build()") &&
                builder == "newBuilder(null)" &&
                getReturn != null &&
                getReturn.nameAsString == "get" &&
                getReturn.scope.map { it.toString() }.orElse("") in
                setOf("properties", "this.properties") &&
                getReturn.arguments.singleOrNull()?.toString() ==
                get.parameters.singleOrNull()?.nameAsString
        ) {
            "NeoForge ModelData no longer answers absent properties of an empty builder with " +
                "null; no files changed"
        }
    }

    private fun parseJarSource(
        artifact: LegacyMigrationArtifactInventory.Artifact,
        entryName: String,
        parser: JavaParser
    ): CompilationUnit {
        artifact.verifyCurrentBytes("legacy model-data source contract")
        val source = ZipFile(artifact.file.toFile()).use { zip ->
            val entry = zip.getEntry(entryName)
                ?: error("Verified platform sources lack $entryName; no files changed")
            zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use {
                it.readText()
            }
        }
        val parsed = parser.parse(source)
        require(parsed.isSuccessful && parsed.result.isPresent) {
            "Cannot parse verified platform source $entryName; no files changed"
        }
        return parsed.result.orElseThrow()
    }

    private fun generatedSources(packageName: String): Map<String, String> {
        val path = packageName.replace('.', '/')
        return linkedMapOf(
            "$path/LegacyModelDataManagerProvider.java" to """
                package $packageName;

                /**
                 * $COMPLETION_MARKER
                 *
                 * Source-owned forwarding contract for wrappers that exposed Forge's nullable
                 * BlockAndTintGetter model-data manager.
                 */
                public interface LegacyModelDataManagerProvider {
                    @org.jetbrains.annotations.Nullable
                    net.neoforged.neoforge.client.model.data.ModelDataManager
                        $PROVIDER_GETTER();
                }
            """.trimIndent() + "\n",
            "$path/LegacyRenderChunkRegionView.java" to """
                package $packageName;

                /**
                 * Server-safe view implemented only by the client mixin. This interface contains
                 * no renderer or Mixin type reference.
                 */
                public interface LegacyRenderChunkRegionView {
                    net.minecraft.world.level.Level $BACKING_LEVEL_GETTER();
                }
            """.trimIndent() + "\n",
            "$path/LegacyModelDataManagers.java" to """
                package $packageName;

                /**
                 * $COMPLETION_MARKER
                 *
                 * Resolves the same live nullable manager that Forge 1.20.1 exposed. A render
                 * region is deliberately resolved through its backing Level rather than its
                 * NeoForge snapshot.
                 */
                public final class LegacyModelDataManagers {
                    private LegacyModelDataManagers() {}

                    @org.jetbrains.annotations.Nullable
                    public static net.neoforged.neoforge.client.model.data.ModelDataManager
                        resolve(net.minecraft.world.level.BlockAndTintGetter getter) {
                        java.util.Objects.requireNonNull(getter, "getter");
                        if (getter instanceof LegacyModelDataManagerProvider provider)
                            return provider.$PROVIDER_GETTER();
                        if (getter instanceof net.minecraft.world.level.Level level)
                            return level.getModelDataManager();
                        if (getter instanceof LegacyRenderChunkRegionView region)
                            return java.util.Objects.requireNonNull(
                                region.$BACKING_LEVEL_GETTER(),
                                "render region backing level"
                            ).getModelDataManager();
                        return null;
                    }
                }
            """.trimIndent() + "\n",
            "$path/mixin/LegacyRenderChunkRegionAccessor.java" to """
                package $packageName.mixin;

                @org.spongepowered.asm.mixin.Mixin(
                    targets = "net.minecraft.client.renderer.chunk.RenderChunkRegion"
                )
                public interface LegacyRenderChunkRegionAccessor
                        extends $packageName.LegacyRenderChunkRegionView {
                    @Override
                    @org.spongepowered.asm.mixin.gen.Accessor("level")
                    net.minecraft.world.level.Level $BACKING_LEVEL_GETTER();
                }
            """.trimIndent() + "\n"
        )
    }

    private fun mixinConfigName(packageName: String): String {
        val modId = packageName.removePrefix("com.modporter.generated.")
            .substringBefore(".compat")
        require(modId.matches(Regex("[a-z0-9_]+"))) {
            "Generated compatibility package has no stable mod id: $packageName"
        }
        return "$modId.modporter-model-data.mixins.json"
    }

    private fun generatedMixinConfig(packageName: String): String = """
        {
          "required": true,
          "minVersion": "0.8",
          "package": "$packageName.mixin",
          "compatibilityLevel": "JAVA_21",
          "client": [
            "LegacyRenderChunkRegionAccessor"
          ],
          "injectors": {
            "defaultRequire": 1
          }
        }
    """.trimIndent() + "\n"

    private fun checkClientOnlyMixinConfig(source: String) {
        require(Regex(""""client"\s*:\s*\[""").containsMatchIn(source)) {
            "Generated RenderChunkRegion accessor is not in the client mixin array"
        }
        require(!Regex(""""mixins"\s*:""").containsMatchIn(source)) {
            "Generated RenderChunkRegion accessor leaked into the common mixin array"
        }
        require(!Regex(""""server"\s*:""").containsMatchIn(source)) {
            "Generated RenderChunkRegion accessor leaked into the server mixin array"
        }
    }

    private fun findNeoForgeMetadata(resourceRoot: Path): Path {
        val candidates = listOf(
            resourceRoot.resolve("META-INF/neoforge.mods.toml"),
            resourceRoot.resolve("META-INF/mods.toml")
        ).filter { it.isRegularFile() }
        require(candidates.isNotEmpty()) {
            "Cannot register model-data mixin: no NeoForge metadata exists; no files changed"
        }
        return candidates.first()
    }

    private fun ensureMixinMetadata(source: String, config: String): String {
        val escaped = Regex.escape(config)
        val registration = Regex(
            """(?ms)^\s*\[\[mixins]]\s*$.*?^\s*config\s*=\s*"$escaped"\s*$"""
        )
        if (registration.containsMatchIn(source)) return source
        require(!Regex("""(?m)^\s*config\s*=\s*"$escaped"\s*$""")
            .containsMatchIn(source)
        ) {
            "Mixin config $config is registered outside a [[mixins]] table; no files changed"
        }
        val separator = if (source.endsWith('\n')) "" else "\n"
        return source + separator + "\n[[mixins]]\nconfig=\"$config\"\n"
    }

    private fun planCanonicalWrite(
        writes: MutableList<PlannedWrite>,
        file: Path,
        content: String,
        description: String
    ) {
        val before = if (file.exists()) {
            require(file.isRegularFile()) {
                "Compatibility output is not a regular file: $file; no files changed"
            }
            file.readText()
        } else {
            null
        }
        if (before != null) {
            require(before == content) {
                "Existing compatibility output differs from the canonical source: $file; " +
                    "no files changed"
            }
            return
        }
        writes += PlannedWrite(
            file = file,
            before = null,
            after = content,
            change = Change(
                file = file,
                line = 1,
                description = description,
                before = "(missing compatibility artifact)",
                after = file.fileName.toString(),
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        )
    }

    private fun applyTransaction(writes: List<PlannedWrite>) {
        writes.forEach { write ->
            val current = if (write.file.exists()) write.file.readText() else null
            require(current == write.before) {
                "Project input changed after model-data preflight: ${write.file}; " +
                    "no files changed"
            }
        }
        val staged = linkedMapOf<PlannedWrite, Path>()
        try {
            for (write in writes) {
                write.file.parent.createDirectories()
                val temporary = write.file.resolveSibling(
                    ".${write.file.fileName}.modporter-${UUID.randomUUID()}.tmp"
                )
                temporary.writeText(write.after)
                require(temporary.readText() == write.after) {
                    "Could not verify staged model-data output for ${write.file}"
                }
                staged[write] = temporary
            }
            val applied = mutableListOf<PlannedWrite>()
            try {
                for ((write, temporary) in staged) {
                    moveReplacing(temporary, write.file)
                    applied += write
                }
            } catch (failure: Exception) {
                for (write in applied.asReversed()) {
                    runCatching {
                        if (write.before == null) {
                            Files.deleteIfExists(write.file)
                        } else {
                            write.file.writeText(write.before)
                        }
                    }.onFailure(failure::addSuppressed)
                }
                throw failure
            }
        } finally {
            staged.values.forEach { Files.deleteIfExists(it) }
        }
    }

    private fun moveReplacing(from: Path, to: Path) {
        try {
            Files.move(
                from,
                to,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun canonicalTypes(
        unit: CompilationUnit
    ): Map<ClassOrInterfaceDeclaration, String> {
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val result = linkedMapOf<ClassOrInterfaceDeclaration, String>()
        unit.findAll(ClassOrInterfaceDeclaration::class.java).forEach { type ->
            val nesting = generateSequence<Node>(type) { it.parentNode.orElse(null) }
                .filterIsInstance<ClassOrInterfaceDeclaration>()
                .toList()
                .asReversed()
                .joinToString(".") { it.nameAsString }
            result[type] = if (packageName.isBlank()) nesting else "$packageName.$nesting"
        }
        return result
    }

    private fun canonicalType(unit: CompilationUnit, raw: String): String {
        val cleaned = raw
            .replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "")
            .substringBefore('<')
            .trim()
            .removeSuffix("[]")
        if (cleaned.contains('.')) return cleaned
        unit.imports.firstOrNull {
            !it.isStatic && !it.isAsterisk && it.name.identifier == cleaned
        }?.let { return it.nameAsString }
        unit.imports.filter { !it.isStatic && it.isAsterisk }.forEach {
            val packageName = it.nameAsString
            when ("$packageName.$cleaned") {
                OLD_MANAGER,
                TARGET_MANAGER,
                TARGET_MODEL_DATA,
                BLOCK_AND_TINT_GETTER,
                BLOCK_POS,
                BLOCK_STATE,
                DIRECTION,
                LEVEL -> return "$packageName.$cleaned"
            }
        }
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        return if (packageName.isBlank()) cleaned else "$packageName.$cleaned"
    }

    private fun isDirectManagerForwarder(
        statement: Statement?,
        receiver: String
    ): Boolean {
        val call = (statement as? ReturnStmt)?.expression?.orElse(null)
            as? MethodCallExpr ?: return false
        val scope = call.scope.orElse(null)
        val receiverMatches = scope is NameExpr && scope.nameAsString == receiver ||
            scope is FieldAccessExpr &&
            scope.scope.toString() == "this" &&
            scope.nameAsString == receiver
        return receiverMatches &&
            call.nameAsString == MANAGER_GETTER &&
            call.arguments.isEmpty()
    }

    private fun isClientOnly(type: ClassOrInterfaceDeclaration): Boolean =
        type.annotations.any {
            it.nameAsString == "OnlyIn" &&
                it.toString().replace(Regex("""\s+"""), "")
                    .contains("(Dist.CLIENT)")
        }

    private enum class HeritageProof {
        YES,
        NO,
        UNKNOWN
    }

    private data class ClassMethod(
        val access: Int,
        val name: String,
        val descriptor: String
    )

    private data class ClassField(
        val access: Int,
        val name: String,
        val descriptor: String
    )

    private data class ClassInfo(
        val name: String,
        val superName: String?,
        val interfaces: List<String>,
        val fields: List<ClassField>,
        val methods: List<ClassMethod>,
        val byteSha256: String
    )

    private class ClassPathIndex private constructor(
        private val classes: Map<String, ClassInfo>,
        private val owners:
            Map<String, LegacyMigrationArtifactInventory.Artifact>
    ) {
        fun hasClass(name: String): Boolean = name in classes

        fun hasMethod(owner: String, name: String, descriptor: String): Boolean =
            classes[owner]?.methods?.any {
                it.name == name && it.descriptor == descriptor
            } == true

        fun hasPublicConcreteInstanceMethod(
            owner: String,
            name: String,
            descriptor: String
        ): Boolean =
            classes[owner]?.methods?.any {
                it.name == name &&
                    it.descriptor == descriptor &&
                    it.access and ACC_PUBLIC != 0 &&
                    it.access and ACC_STATIC == 0 &&
                    it.access and ACC_ABSTRACT == 0
            } == true

        fun hasField(
            owner: String,
            name: String,
            descriptor: String,
            requiredAccess: Int
        ): Boolean =
            classes[owner]?.fields?.any {
                it.name == name &&
                    it.descriptor == descriptor &&
                    it.access and requiredAccess == requiredAccess
            } == true

        fun declarers(
            name: String,
            descriptor: String
        ): List<Pair<String, LegacyMigrationArtifactInventory.Artifact>> =
            classes.values.mapNotNull { info ->
                if (info.methods.any {
                        it.name == name && it.descriptor == descriptor
                    }
                ) {
                    info.name to checkNotNull(owners[info.name])
                } else {
                    null
                }
            }

        fun inheritsFrom(owner: String, ancestor: String): HeritageProof {
            val pending = ArrayDeque(listOf(owner))
            val seen = mutableSetOf<String>()
            var unresolved = false
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (current == ancestor) return HeritageProof.YES
                if (!seen.add(current)) continue
                if (current == "java.lang.Object") continue
                val info = classes[current]
                if (info == null) {
                    unresolved = true
                    continue
                }
                info.superName?.let(pending::add)
                pending.addAll(info.interfaces)
            }
            return if (unresolved) HeritageProof.UNKNOWN else HeritageProof.NO
        }

        companion object {
            fun read(
                artifacts: List<LegacyMigrationArtifactInventory.Artifact>,
                runtimeFeature: Int,
                phase: String
            ): ClassPathIndex {
                val classes = linkedMapOf<String, ClassInfo>()
                val owners =
                    linkedMapOf<String, LegacyMigrationArtifactInventory.Artifact>()
                for (artifact in artifacts.distinctBy { it.file to it.sha256 }) {
                    artifact.verifyCurrentBytes(phase)
                    val artifactClasses = readArtifactClasses(artifact, runtimeFeature)
                    for ((name, info) in artifactClasses) {
                        val existing = classes[name]
                        require(existing == null || existing == info) {
                            "Exact compile classpath contains conflicting definitions for " +
                                "$name in ${owners[name]?.component} and " +
                                "${artifact.component}; no files changed"
                        }
                        if (existing == null) {
                            classes[name] = info
                            owners[name] = artifact
                        }
                    }
                }
                return ClassPathIndex(classes, owners)
            }

            private fun readArtifactClasses(
                artifact: LegacyMigrationArtifactInventory.Artifact,
                runtimeFeature: Int
            ): Map<String, ClassInfo> =
                ZipFile(artifact.file.toFile()).use { zip ->
                    val manifest = zip.getEntry("META-INF/MANIFEST.MF")?.let { entry ->
                        zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    }.orEmpty()
                    val multiRelease = Regex(
                        """(?im)^Multi-Release:\s*true\s*$"""
                    ).containsMatchIn(manifest)
                    data class Selected(val version: Int, val entryName: String)
                    val selected = linkedMapOf<String, Selected>()
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                        val versioned = Regex(
                            """^META-INF/versions/(\d+)/(.*\.class)$"""
                        ).matchEntire(entry.name)
                        val version: Int
                        val logical: String
                        if (versioned == null) {
                            version = 0
                            logical = entry.name
                        } else {
                            if (!multiRelease) continue
                            version = versioned.groupValues[1].toInt()
                            logical = versioned.groupValues[2]
                            if (version > runtimeFeature) continue
                        }
                        if (logical == "module-info.class" ||
                            logical.endsWith("/module-info.class")
                        ) continue
                        val previous = selected[logical]
                        if (previous == null || version > previous.version) {
                            selected[logical] = Selected(version, entry.name)
                        }
                    }
                    selected.values.associate { chosen ->
                        val entry = checkNotNull(zip.getEntry(chosen.entryName))
                        val info = zip.getInputStream(entry).use {
                            ClassReader(it.readBytes()).read()
                        }
                        info.name to info
                    }
                }
        }
    }

    private class ClassReader(bytes: ByteArray) {
        private val byteSha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private val input =
            DataInputStream(ByteArrayInputStream(bytes))
        private lateinit var utf8: Array<String?>
        private lateinit var classes: IntArray

        fun read(): ClassInfo {
            require(input.readInt() == 0xCAFEBABE.toInt()) {
                "Invalid classfile magic in model-data inventory"
            }
            input.readUnsignedShort()
            input.readUnsignedShort()
            readConstantPool()
            input.readUnsignedShort()
            val name = className(input.readUnsignedShort())
            val superIndex = input.readUnsignedShort()
            val interfaces = List(input.readUnsignedShort()) {
                className(input.readUnsignedShort())
            }
            val fields = readFields()
            val methods = readMethods()
            skipAttributes()
            return ClassInfo(
                name = name,
                superName = if (superIndex == 0) null else className(superIndex),
                interfaces = interfaces,
                fields = fields,
                methods = methods,
                byteSha256 = byteSha256
            )
        }

        private fun readConstantPool() {
            val count = input.readUnsignedShort()
            utf8 = arrayOfNulls(count)
            classes = IntArray(count)
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    3, 4 -> input.skipBytes(4)
                    5, 6 -> {
                        input.skipBytes(8)
                        index++
                    }
                    7 -> classes[index] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.skipBytes(2)
                    9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                    15 -> input.skipBytes(3)
                    else -> error(
                        "Unsupported classfile constant-pool tag $tag in model-data inventory"
                    )
                }
                index++
            }
        }

        private fun readFields(): List<ClassField> =
            List(input.readUnsignedShort()) {
                val access = input.readUnsignedShort()
                val name = utf8(input.readUnsignedShort())
                val descriptor = utf8(input.readUnsignedShort())
                skipAttributes()
                ClassField(access, name, descriptor)
            }

        private fun readMethods(): List<ClassMethod> =
            List(input.readUnsignedShort()) {
                val access = input.readUnsignedShort()
                val name = utf8(input.readUnsignedShort())
                val descriptor = utf8(input.readUnsignedShort())
                skipAttributes()
                ClassMethod(access, name, descriptor)
            }

        private fun skipAttributes() {
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort()
                skipFully(input.readInt())
            }
        }

        private fun skipFully(length: Int) {
            require(length >= 0) {
                "Negative classfile attribute length in model-data inventory"
            }
            var remaining = length
            while (remaining > 0) {
                val skipped = input.skipBytes(remaining)
                require(skipped > 0) {
                    "Truncated classfile attribute in model-data inventory"
                }
                remaining -= skipped
            }
        }

        private fun className(index: Int): String {
            require(index in classes.indices && classes[index] != 0)
            return utf8(classes[index]).replace('/', '.')
        }

        private fun utf8(index: Int): String =
            requireNotNull(utf8.getOrNull(index)) {
                "Missing classfile UTF-8 entry $index in model-data inventory"
            }
    }

    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PROTECTED = 0x0004
    private const val ACC_STATIC = 0x0008
    private const val ACC_FINAL = 0x0010
    private const val ACC_ABSTRACT = 0x0400
}
