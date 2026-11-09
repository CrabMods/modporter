package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
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
 * Preserves the exact Forge 1.20.1 nullable ModelDataManager contract used by Create's copycat
 * renderer. The migration is inventory-backed because NeoForge's RenderChunkRegion only exposes a
 * snapshot while the admitted source requires the live backing Level manager and its null branch.
 */
internal object LegacyModelDataManagerCompatMigration {
    const val RULE_ID = "struct-legacy-model-data-manager-compat"
    const val COMPLETION_MARKER = "modporter-legacy-model-data-manager-compat-v1"

    private const val FILTERED =
        "com.simibubi.create.content.decoration.copycat.FilteredBlockAndTintGetter"
    private const val COPYCAT_BLOCK =
        "com.simibubi.create.content.decoration.copycat.CopycatBlock"
    private const val COPYCAT_MODEL =
        "com.simibubi.create.content.decoration.copycat.CopycatModel"

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

    private data class AdmittedSources(
        val filtered: SourceUnit,
        val filteredType: ClassOrInterfaceDeclaration,
        val filteredMethod: MethodDeclaration,
        val filteredLegacyCall: MethodCallExpr?,
        val copycat: SourceUnit,
        val copycatType: ClassOrInterfaceDeclaration,
        val managerVariable: VariableDeclarator,
        val copycatLegacyCall: MethodCallExpr?,
        val model: SourceUnit,
        val alreadyMigrated: Boolean
    )

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
        val filteredPath = sourceRoot.resolve(
            FILTERED.replace('.', '/') + ".java"
        )
        val copycatPath = sourceRoot.resolve(
            COPYCAT_BLOCK.replace('.', '/') + ".java"
        )
        val filteredResidual = filteredPath.isRegularFile() &&
            filteredPath.readText().let {
                it.contains(MANAGER_GETTER) || it.contains(PROVIDER_GETTER)
            }
        val copycatResidual = copycatPath.isRegularFile() &&
            copycatPath.readText().let {
                it.contains(MANAGER_GETTER) ||
                    it.contains("LegacyModelDataManagers.resolve")
            }
        val candidate = filteredResidual || copycatResidual || (
            completionFile.isRegularFile() &&
                completionFile.readText().contains(COMPLETION_MARKER)
            )
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

        verifySealedSurfaces(inventory, parser)
        val admitted = admitExactCreateSources(units, generatedPackage)
        val migrated = migrateAdmittedSources(parser, admitted, generatedPackage)

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
                    description = when (unit) {
                        admitted.filtered ->
                            "Preserve unconditional nullable manager forwarding"
                        admitted.copycat ->
                            "Preserve live nullable manager lookup for copycat appearance"
                        else -> error("Unexpected model-data source plan")
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

    private fun admitExactCreateSources(
        units: List<SourceUnit>,
        generatedPackage: String
    ): AdmittedSources {
        fun exactType(name: String): Pair<SourceUnit, ClassOrInterfaceDeclaration> {
            val matches = units.flatMap { source ->
                source.canonicalTypes.entries.mapNotNull { (type, canonical) ->
                    if (canonical == name) source to type else null
                }
            }
            require(matches.size == 1) {
                "Expected exactly one source declaration for $name, found ${matches.size}; " +
                    "no files changed"
            }
            return matches.single()
        }

        val (filteredUnit, filteredType) = exactType(FILTERED)
        val (copycatUnit, copycatType) = exactType(COPYCAT_BLOCK)
        val (modelUnit, modelType) = exactType(COPYCAT_MODEL)

        require(
            filteredType.implementedTypes.size == 1 ||
                (
                    filteredType.implementedTypes.size == 2 &&
                        filteredType.implementedTypes.any {
                            canonicalType(filteredUnit.unit, it.asString()) ==
                                "$generatedPackage.LegacyModelDataManagerProvider"
                        }
                    )
        ) {
            "$FILTERED has unexpected direct interfaces; no files changed"
        }
        require(
            filteredType.implementedTypes.any {
                canonicalType(filteredUnit.unit, it.asString()) == BLOCK_AND_TINT_GETTER
            }
        ) {
            "$FILTERED no longer directly implements BlockAndTintGetter; no files changed"
        }
        val wrappedFields = filteredType.fields.flatMap { it.variables }.filter {
            it.nameAsString == "wrapped" &&
                canonicalType(filteredUnit.unit, it.typeAsString) == BLOCK_AND_TINT_GETTER
        }
        require(wrappedFields.size == 1) {
            "$FILTERED lacks its exact wrapped BlockAndTintGetter field; no files changed"
        }

        val legacyMethods = filteredType.methods.filter {
            it.nameAsString == MANAGER_GETTER && it.parameters.isEmpty()
        }
        val providerMethods = filteredType.methods.filter {
            it.nameAsString == PROVIDER_GETTER && it.parameters.isEmpty()
        }
        require(legacyMethods.size + providerMethods.size == 1) {
            "$FILTERED has an ambiguous manager forwarding method; no files changed"
        }
        val filteredMethod = (legacyMethods + providerMethods).single()
        require(
            filteredMethod.isPublic &&
                !filteredMethod.isStatic &&
                filteredMethod.annotations.any { it.nameAsString == "Override" } &&
                canonicalType(filteredUnit.unit, filteredMethod.typeAsString) in
                setOf(OLD_MANAGER, TARGET_MANAGER)
        ) {
            "$FILTERED manager forwarding signature changed; no files changed"
        }
        val filteredReturn = filteredMethod.body.orElseThrow().statements.singleOrNull()
            ?.asReturnStmt()?.expression?.orElse(null)
            ?: error("$FILTERED manager forwarding body is not one return; no files changed")
        val filteredLegacyCall =
            if (filteredMethod.nameAsString == MANAGER_GETTER) {
                filteredReturn as? MethodCallExpr
                    ?: error("$FILTERED manager forwarding is not a call; no files changed")
            } else {
                null
            }
        if (filteredLegacyCall != null) {
            require(
                filteredLegacyCall.nameAsString == MANAGER_GETTER &&
                    filteredLegacyCall.arguments.isEmpty() &&
                    (filteredLegacyCall.scope.orElse(null) as? NameExpr)
                        ?.nameAsString == "wrapped"
            ) {
                "$FILTERED no longer unconditionally forwards wrapped.getModelDataManager(); " +
                    "no files changed"
            }
        } else {
            require(
                isResolverCall(
                    filteredReturn,
                    generatedPackage,
                    expectedArgument = "wrapped"
                ) &&
                    filteredType.implementedTypes.any {
                        canonicalType(filteredUnit.unit, it.asString()) ==
                            "$generatedPackage.LegacyModelDataManagerProvider"
                    }
            ) {
                "$FILTERED has a noncanonical migrated provider method; no files changed"
            }
        }

        val appearance = copycatType.methods.singleOrNull {
            it.nameAsString == "getAppearance" &&
                it.parameters.size == 6 &&
                canonicalType(copycatUnit.unit, it.typeAsString) == BLOCK_STATE
        } ?: error("$COPYCAT_BLOCK has no exact getAppearance method; no files changed")
        val parameterTypes = appearance.parameters.map {
            canonicalType(copycatUnit.unit, it.typeAsString)
        }
        require(
            appearance.isPublic &&
                appearance.annotations.any { it.nameAsString == "Override" } &&
                parameterTypes == listOf(
                    BLOCK_STATE,
                    BLOCK_AND_TINT_GETTER,
                    BLOCK_POS,
                    DIRECTION,
                    BLOCK_STATE,
                    BLOCK_POS
                )
        ) {
            "$COPYCAT_BLOCK#getAppearance signature changed; no files changed"
        }
        val names = appearance.parameters.map { it.nameAsString }
        val stateName = names[0]
        val levelName = names[1]
        val posName = names[2]
        val sideName = names[3]
        val queryPosName = names[5]
        val statements = appearance.body.orElseThrow().statements
        require(statements.size == 4) {
            "$COPYCAT_BLOCK#getAppearance no longer has the exact four-statement query; " +
                "no files changed"
        }
        require(
            isIgnoredConnectivityReturn(
                statements[0],
                levelName,
                stateName,
                sideName,
                posName,
                queryPosName
            )
        ) {
            "$COPYCAT_BLOCK#getAppearance connectivity guard changed; no files changed"
        }
        val managerVariable = statements[1].asExpressionStmt().expression
            .asVariableDeclarationExpr().variables.single()
        require(
            canonicalType(copycatUnit.unit, managerVariable.typeAsString) in
                setOf(OLD_MANAGER, TARGET_MANAGER)
        ) {
            "$COPYCAT_BLOCK#getAppearance manager local changed type; no files changed"
        }
        val managerName = managerVariable.nameAsString
        val managerInitializer = managerVariable.initializer.orElseThrow()
        val copycatLegacyCall =
            (managerInitializer as? MethodCallExpr)?.takeIf {
                it.nameAsString == MANAGER_GETTER &&
                    it.arguments.isEmpty() &&
                    (it.scope.orElse(null) as? NameExpr)?.nameAsString == levelName
            }
        require(
            copycatLegacyCall != null ||
                isResolverCall(
                    managerInitializer,
                    generatedPackage,
                    expectedArgument = levelName
                )
        ) {
            "$COPYCAT_BLOCK#getAppearance manager lookup changed; no files changed"
        }
        require(
            isNullFallback(statements[2], managerName, levelName, posName) &&
                isCopycatManagerConsumer(statements[3], managerName, posName)
        ) {
            "$COPYCAT_BLOCK#getAppearance no longer preserves the nullable fallback and " +
                "live manager.getAt(pos) consumer; no files changed"
        }

        verifyCopycatModelConsumer(modelUnit, modelType)

        val admittedLegacyNodes = listOfNotNull(filteredLegacyCall, copycatLegacyCall)
        val allLegacyCalls = units.flatMap { source ->
            source.unit.findAll(MethodCallExpr::class.java).filter {
                it.nameAsString == MANAGER_GETTER && it.arguments.isEmpty()
            }
        }
        val unexpectedCalls = allLegacyCalls.filter { call ->
            admittedLegacyNodes.none { it === call }
        }
        require(unexpectedCalls.isEmpty()) {
            "Project source contains an additional getModelDataManager() query outside the " +
                "admitted Create pair; no files changed"
        }
        val allLegacyDeclarations = units.flatMap { source ->
            source.unit.findAll(MethodDeclaration::class.java).filter {
                it.nameAsString == MANAGER_GETTER &&
                    it.parameters.isEmpty() &&
                    canonicalType(source.unit, it.typeAsString) in
                    setOf(OLD_MANAGER, TARGET_MANAGER)
            }
        }
        require(
            allLegacyDeclarations.size == legacyMethods.size &&
                allLegacyDeclarations.all { it === legacyMethods.singleOrNull() }
        ) {
            "Project source contains an additional ModelDataManager getter declaration; " +
                "no files changed"
        }

        return AdmittedSources(
            filtered = filteredUnit,
            filteredType = filteredType,
            filteredMethod = filteredMethod,
            filteredLegacyCall = filteredLegacyCall,
            copycat = copycatUnit,
            copycatType = copycatType,
            managerVariable = managerVariable,
            copycatLegacyCall = copycatLegacyCall,
            model = modelUnit,
            alreadyMigrated = filteredLegacyCall == null && copycatLegacyCall == null
        )
    }

    private fun migrateAdmittedSources(
        parser: JavaParser,
        admitted: AdmittedSources,
        generatedPackage: String
    ): Map<SourceUnit, String> {
        val result = linkedMapOf<SourceUnit, String>()

        LexicalPreservingPrinter.setup(admitted.filtered.unit)
        if (!admitted.alreadyMigrated) {
            if (admitted.filteredLegacyCall != null) {
                admitted.filteredType.addImplementedType(
                    "$generatedPackage.LegacyModelDataManagerProvider"
                )
                admitted.filteredMethod.setName(PROVIDER_GETTER)
                admitted.filteredMethod.setBody(
                    StaticJavaParser.parseBlock(
                        "{ return $generatedPackage.LegacyModelDataManagers" +
                            ".resolve(wrapped); }"
                    )
                )
            }
        }
        val filteredText = LexicalPreservingPrinter.print(admitted.filtered.unit)
            .let { if (it.endsWith('\n')) it else "$it\n" }
        check(parser.parse(filteredText).isSuccessful) {
            "Filtered model-data migration emitted invalid Java; no files changed"
        }
        result[admitted.filtered] = filteredText

        LexicalPreservingPrinter.setup(admitted.copycat.unit)
        if (admitted.copycatLegacyCall != null) {
            admitted.managerVariable.setInitializer(
                StaticJavaParser.parseExpression(
                    "$generatedPackage.LegacyModelDataManagers.resolve(" +
                        admitted.copycatLegacyCall.scope.orElseThrow() +
                        ")"
                )
            )
        }
        val copycatText = LexicalPreservingPrinter.print(admitted.copycat.unit)
            .let { if (it.endsWith('\n')) it else "$it\n" }
        check(parser.parse(copycatText).isSuccessful) {
            "Copycat model-data migration emitted invalid Java; no files changed"
        }
        result[admitted.copycat] = copycatText

        return result
    }

    private fun verifyCopycatModelConsumer(
        source: SourceUnit,
        owner: ClassOrInterfaceDeclaration
    ) {
        val method = owner.methods.singleOrNull {
            it.nameAsString == "getMaterial" &&
                it.isPublic &&
                it.isStatic &&
                it.parameters.size == 1 &&
                canonicalType(source.unit, it.typeAsString) == BLOCK_STATE
        } ?: error("$COPYCAT_MODEL has no exact static getMaterial consumer; no files changed")
        require(
            canonicalType(source.unit, method.parameters.single().typeAsString) in
                setOf(
                    "net.minecraftforge.client.model.data.ModelData",
                    TARGET_MODEL_DATA
                )
        ) {
            "$COPYCAT_MODEL#getMaterial parameter changed; no files changed"
        }
        val dataName = method.parameters.single().nameAsString
        val statements = method.body.orElseThrow().statements
        require(statements.size == 2) {
            "$COPYCAT_MODEL#getMaterial no longer has the exact null/property consumer; " +
                "no files changed"
        }
        val material = statements[0].asExpressionStmt().expression
            .asVariableDeclarationExpr().variables.single()
        require(
            canonicalType(source.unit, material.typeAsString) == BLOCK_STATE
        ) {
            "$COPYCAT_MODEL#getMaterial material local changed; no files changed"
        }
        val conditional = material.initializer.orElse(null) as? ConditionalExpr
            ?: error("$COPYCAT_MODEL#getMaterial null lookup changed; no files changed")
        require(
            isNameComparedToNull(conditional.condition, dataName) &&
                conditional.thenExpr is NullLiteralExpr &&
                (conditional.elseExpr as? MethodCallExpr)?.let {
                    it.nameAsString == "get" &&
                        (it.scope.orElse(null) as? NameExpr)?.nameAsString == dataName &&
                        it.arguments.singleOrNull()?.toString() == "MATERIAL_PROPERTY"
                } == true
        ) {
            "$COPYCAT_MODEL#getMaterial no longer maps null data to null material; " +
                "no files changed"
        }
        val returned = statements[1].asReturnStmt().expression.orElse(null)
            as? ConditionalExpr
            ?: error("$COPYCAT_MODEL#getMaterial fallback changed; no files changed")
        require(
            isNameComparedToNull(returned.condition, material.nameAsString) &&
                returned.thenExpr.toString() ==
                "AllBlocks.COPYCAT_BASE.getDefaultState()" &&
                returned.elseExpr.toString() == material.nameAsString
        ) {
            "$COPYCAT_MODEL#getMaterial no longer gives null and ModelData.EMPTY the " +
                "same property-absent fallback; no files changed"
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

    private fun isResolverCall(
        expression: com.github.javaparser.ast.expr.Expression,
        generatedPackage: String,
        expectedArgument: String
    ): Boolean {
        val call = expression as? MethodCallExpr ?: return false
        return call.nameAsString == "resolve" &&
            call.arguments.singleOrNull()?.toString() == expectedArgument &&
            call.scope.orElse(null)?.toString() ==
            "$generatedPackage.LegacyModelDataManagers"
    }

    private fun isIgnoredConnectivityReturn(
        statement: Statement,
        level: String,
        state: String,
        side: String,
        pos: String,
        queryPos: String
    ): Boolean {
        val branch = statement as? IfStmt ?: return false
        if (branch.elseStmt.isPresent) return false
        val condition = branch.condition as? MethodCallExpr ?: return false
        val returned = singleReturn(branch.thenStmt)?.expression?.orElse(null)
            as? NameExpr ?: return false
        return condition.scope.isEmpty &&
            condition.nameAsString == "isIgnoredConnectivitySide" &&
            condition.arguments.map(Node::toString) ==
            listOf(level, state, side, pos, queryPos) &&
            returned.nameAsString == state
    }

    private fun isNullFallback(
        statement: Statement,
        manager: String,
        level: String,
        pos: String
    ): Boolean {
        val branch = statement as? IfStmt ?: return false
        if (branch.elseStmt.isPresent ||
            !isNameComparedToNull(branch.condition, manager)
        ) return false
        val returned = singleReturn(branch.thenStmt)?.expression?.orElse(null)
            as? MethodCallExpr ?: return false
        return returned.scope.isEmpty &&
            returned.nameAsString == "getMaterial" &&
            returned.arguments.map(Node::toString) == listOf(level, pos)
    }

    private fun isCopycatManagerConsumer(
        statement: Statement,
        manager: String,
        pos: String
    ): Boolean {
        val returned = statement as? ReturnStmt ?: return false
        val outer = returned.expression.orElse(null) as? MethodCallExpr ?: return false
        val inner = outer.arguments.singleOrNull() as? MethodCallExpr ?: return false
        return outer.nameAsString == "getMaterial" &&
            outer.scope.orElse(null)?.toString() == "CopycatModel" &&
            inner.nameAsString == "getAt" &&
            (inner.scope.orElse(null) as? NameExpr)?.nameAsString == manager &&
            inner.arguments.singleOrNull()?.toString() == pos
    }

    private fun isNameComparedToNull(
        expression: com.github.javaparser.ast.expr.Expression,
        name: String
    ): Boolean {
        val binary = expression as? BinaryExpr ?: return false
        if (binary.operator != BinaryExpr.Operator.EQUALS) return false
        return (
            binary.left is NameExpr &&
                binary.left.asNameExpr().nameAsString == name &&
                binary.right is NullLiteralExpr
            ) || (
            binary.right is NameExpr &&
                binary.right.asNameExpr().nameAsString == name &&
                binary.left is NullLiteralExpr
            )
    }

    private fun singleReturn(statement: Statement): ReturnStmt? =
        when {
            statement.isReturnStmt -> statement.asReturnStmt()
            statement.isBlockStmt &&
                statement.asBlockStmt().statements.size == 1 &&
                statement.asBlockStmt().statements.single().isReturnStmt ->
                statement.asBlockStmt().statements.single().asReturnStmt()
            else -> null
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
