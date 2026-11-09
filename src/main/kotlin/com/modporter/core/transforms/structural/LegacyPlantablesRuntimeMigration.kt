package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.shared.LegacyPlantRuntimeResources
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Source-gated migration for Forge 1.20.1's block-oriented plant contract.
 *
 * This must run as an early atomic preflight, before migrateLegacyPlantApiSource or any other
 * per-file writer. Every unsupported source shape, missing external coverage fact, or unverified
 * shared-runtime artifact is rejected before a source or runtime-request file is written.
 */
internal object LegacyPlantablesRuntimeMigration {
    const val RULE_ID = "struct-legacy-plantables-compat"
    const val COMPAT_PACKAGE = "com.modporter.compat.plant.v1"

    private const val BLOCK = "net.minecraft.world.level.block.Block"
    private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
    private const val BLOCK_GETTER = "net.minecraft.world.level.BlockGetter"
    private const val BLOCK_POS = "net.minecraft.core.BlockPos"
    private const val DIRECTION = "net.minecraft.core.Direction"
    private const val BUSH_BLOCK = "net.minecraft.world.level.block.BushBlock"
    private const val BAMBOO = "net.minecraft.world.level.block.BambooStalkBlock"
    private const val CACTUS = "net.minecraft.world.level.block.CactusBlock"
    private const val SUGAR_CANE = "net.minecraft.world.level.block.SugarCaneBlock"
    private const val PUMPKIN = "net.minecraft.world.level.block.PumpkinBlock"
    private const val LEGACY_SOIL_METHOD = "modporter\$legacyCanSustainPlant"
    private const val LEGACY_PLACEMENT_METHOD = "modporter\$legacyMayPlaceOn"
    private const val RUNTIME_REQUEST = ".modporter/legacy-plant-compat.required"
    private const val GET_PLANT_DESCRIPTOR =
        "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)" +
            "Lnet/minecraft/world/level/block/state/BlockState;"
    private const val MAY_PLACE_ON_DESCRIPTOR =
        "(Lnet/minecraft/world/level/block/state/BlockState;" +
            "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"
    private const val LEGACY_SOIL_DESCRIPTOR =
        "(Lnet/minecraft/world/level/block/state/BlockState;" +
            "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/core/Direction;Lnet/minecraftforge/common/IPlantable;)Z"

    private val legacyIPlantable = setOf(
        "net.minecraftforge.common.IPlantable",
        "net.neoforged.neoforge.common.IPlantable"
    )
    private val legacyPlantType = setOf(
        "net.minecraftforge.common.PlantType",
        "net.neoforged.neoforge.common.PlantType"
    )
    private val canonicalRuntimeTypes = LegacyPlantRuntimeResources.JAVA_NAMES
        .mapTo(linkedSetOf()) { name ->
            "$COMPAT_PACKAGE.${name.replace('/', '.')}"
        }
    private val canonicalRuntimeHeritage = setOf(
        "$COMPAT_PACKAGE.IPlantable",
        "$COMPAT_PACKAGE.LegacyPlantSustainer",
        "$COMPAT_PACKAGE.LegacyPlantPlacement",
        "$COMPAT_PACKAGE.LegacyPlantableAdapter",
        "$COMPAT_PACKAGE.LegacySoilAdapter",
        "$COMPAT_PACKAGE.LegacyPlantPlacementAdapter"
    )
    private val OLD_MAY_PLACE_ON_DECLARERS = setOf(
        "net.minecraft.world.level.block.AttachedStemBlock",
        "net.minecraft.world.level.block.AzaleaBlock",
        "net.minecraft.world.level.block.BushBlock",
        "net.minecraft.world.level.block.CropBlock",
        "net.minecraft.world.level.block.DeadBushBlock",
        "net.minecraft.world.level.block.FungusBlock",
        "net.minecraft.world.level.block.MangrovePropaguleBlock",
        "net.minecraft.world.level.block.MushroomBlock",
        "net.minecraft.world.level.block.NetherSproutsBlock",
        "net.minecraft.world.level.block.NetherWartBlock",
        "net.minecraft.world.level.block.PitcherCropBlock",
        "net.minecraft.world.level.block.RootsBlock",
        "net.minecraft.world.level.block.SeaPickleBlock",
        "net.minecraft.world.level.block.SeagrassBlock",
        "net.minecraft.world.level.block.SmallDripleafBlock",
        "net.minecraft.world.level.block.StemBlock",
        "net.minecraft.world.level.block.TallSeagrassBlock",
        "net.minecraft.world.level.block.WaterlilyBlock",
        "net.minecraft.world.level.block.WitherRoseBlock"
    )
    private data class SourceUnit(
        val file: Path,
        val source: String,
        val masked: String,
        val unit: CompilationUnit
    ) {
        val packageName: String =
            unit.packageDeclaration.map { it.nameAsString }.orElse("")
    }

    private data class Visibility(
        val iPlantable: Boolean,
        val plantType: Boolean
    )

    private data class SourceType(
        val canonicalName: String,
        val source: SourceUnit,
        val declaration: TypeDeclaration<*>,
        val originalMethods: List<MethodDeclaration>,
        var parent: String? = null,
        val interfaces: MutableList<String> = mutableListOf(),
        val unresolvedHeritage: MutableList<String> = mutableListOf(),
        var directLegacyContract: Boolean = false,
        var legacySoil: Boolean = false
    )

    private data class ProjectIndex(
        val types: Map<String, SourceType>,
        val methods: Map<MethodIdentity, ProjectMethod>,
        val externalHeritage: SealedLegacyPlantSourceHeritage?
    ) {
        fun sourceType(declaration: TypeDeclaration<*>): SourceType? =
            types.values.firstOrNull { it.declaration === declaration }

        fun inheritsFrom(typeName: String, base: String): Boolean {
            val pending = ArrayDeque(listOf(typeName))
            val seen = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (current == base) return true
                if (!seen.add(current)) continue
                val source = types[current]
                if (source != null) {
                    source.parent?.let(pending::add)
                    pending.addAll(source.interfaces)
                    continue
                }
                externalHeritage?.directHeritage(current)?.let {
                    pending.addAll(it)
                }
            }
            return false
        }

        fun inheritsOldVanillaPlantContract(typeName: String): Boolean {
            return typeName == BAMBOO ||
                typeName == CACTUS ||
                typeName == SUGAR_CANE ||
                typeName == PUMPKIN ||
                inheritsFrom(typeName, BUSH_BLOCK)
        }

        fun sourceHierarchyProvidesConcreteExactGetPlant(typeName: String): Boolean {
            val pending = ArrayDeque<String>()
            val seen = mutableSetOf<String>()
            pending += typeName
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current)) continue
                val source = types[current]
                if (source != null) {
                    if (source.originalMethods.any {
                            isExactGetPlant(it, source.source) &&
                                it.body.isPresent &&
                                !it.isStatic &&
                                !it.isPrivate
                        }
                    ) return true
                    source.parent?.let(pending::add)
                    pending.addAll(source.interfaces)
                } else {
                    if (externalHeritage?.declaresConcreteMethod(
                            current,
                            "getPlant",
                            GET_PLANT_DESCRIPTOR
                        ) == true
                    ) {
                        return true
                    }
                    externalHeritage?.directHeritage(current)?.let(pending::addAll)
                }
            }
            return false
        }

        fun inheritsLegacyContract(typeName: String): Boolean {
            val pending = ArrayDeque<String>()
            val seen = mutableSetOf<String>()
            pending += typeName
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current)) continue
                if (current in legacyIPlantable) return true
                val source = types[current]
                if (source != null) {
                    if (source.directLegacyContract) return true
                    source.parent?.let(pending::add)
                    pending.addAll(source.interfaces)
                } else {
                    externalHeritage?.directHeritage(current)?.let(pending::addAll)
                }
            }
            return false
        }

        fun sourceDeclaresLegacyPlacement(type: SourceType): Boolean =
            !TargetMembers.hasClass(type.canonicalName) &&
                type.declaration is ClassOrInterfaceDeclaration &&
                inheritsFrom(type.canonicalName, BUSH_BLOCK) &&
                sourceMethods(type.declaration).any {
                    isExactMayPlaceOn(it, type.source)
                }

        fun nearestSourcePlacementSuper(typeName: String): SourcePlacementSuper {
            var current = types[typeName]?.parent ?: return SourcePlacementSuper.Unknown
            val seen = mutableSetOf<String>()
            while (seen.add(current)) {
                if (TargetMembers.hasClass(current) &&
                    TargetMembers.inheritsFrom(current, BUSH_BLOCK)
                ) {
                    val descriptor =
                        "(Lnet/minecraft/world/level/block/state/BlockState;" +
                            "Lnet/minecraft/world/level/BlockGetter;" +
                            "Lnet/minecraft/core/BlockPos;)Z"
                    val declaring = TargetMembers.inheritedMethod(
                        current,
                        "mayPlaceOn",
                        descriptor
                    )?.owner ?: return SourcePlacementSuper.Unknown
                    return if (declaring in OLD_MAY_PLACE_ON_DECLARERS) {
                        SourcePlacementSuper.OldVanilla(declaring)
                    } else {
                        SourcePlacementSuper.Unknown
                    }
                }
                val source = types[current]
                if (source != null) {
                    if (sourceMethods(source.declaration).any {
                            isExactMayPlaceOn(it, source.source)
                        }
                    ) {
                        return SourcePlacementSuper.Source
                    }
                    current = source.parent ?: return SourcePlacementSuper.Unknown
                    continue
                }
                if (current in OLD_MAY_PLACE_ON_DECLARERS) {
                    return SourcePlacementSuper.OldVanilla(current)
                }
                if (externalHeritage?.declaresMethod(
                        current,
                        "mayPlaceOn",
                        MAY_PLACE_ON_DESCRIPTOR
                    ) == true
                ) {
                    return SourcePlacementSuper.Unknown
                }
                val externalParent = externalHeritage?.directSuperclass(current)
                    ?: return SourcePlacementSuper.Unknown
                current = externalParent
                continue
            }
            return SourcePlacementSuper.Unknown
        }

        fun superSoilDispatch(typeName: String): SuperSoilDispatch {
            var current = types[typeName]?.parent ?: return SuperSoilDispatch.UNKNOWN
            val seen = mutableSetOf<String>()
            while (seen.add(current)) {
                val source = types[current]
                if (source != null) {
                    if (source.legacySoil) return SuperSoilDispatch.SOURCE_OVERRIDE
                    if (TargetMembers.hasClass(current) &&
                        TargetMembers.inheritsFrom(current, BLOCK)
                    ) {
                        return SuperSoilDispatch.VANILLA_DEFAULT
                    }
                    current = source.parent ?: return SuperSoilDispatch.UNKNOWN
                    continue
                }
                if (externalHeritage?.declaresMethod(
                        current,
                        "canSustainPlant",
                        LEGACY_SOIL_DESCRIPTOR
                    ) == true
                ) {
                    return if (current == BLOCK) {
                        SuperSoilDispatch.VANILLA_DEFAULT
                    } else {
                        SuperSoilDispatch.UNKNOWN
                    }
                }
                current = externalHeritage?.directSuperclass(current)
                    ?: return SuperSoilDispatch.UNKNOWN
            }
            return SuperSoilDispatch.UNKNOWN
        }

        fun exactLocalMethod(
            owner: String,
            name: String,
            arity: Int
        ): ProjectMethod? {
            val pending = ArrayDeque<String>()
            val candidates = mutableListOf<ProjectMethod>()
            val seen = mutableSetOf<String>()
            pending += owner
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current)) continue
                candidates += methods.values.filter {
                    it.identity.owner == current &&
                        it.identity.name == name &&
                        it.identity.parameterTypes.size == arity &&
                        it.signatureResolved
                }
                types[current]?.parent?.let(pending::add)
                types[current]?.interfaces?.let(pending::addAll)
            }
            return candidates.singleOrNull()
        }

        fun hasExternalType(typeName: String): Boolean =
            externalHeritage?.hasClass(typeName) == true

        fun inheritedMemberTypes(
            owner: String,
            simpleName: String,
            accessingPackage: String
        ): Set<String> {
            val seen = mutableSetOf<String>()
            var level = directHeritage(owner).toSet()
            while (level.isNotEmpty()) {
                val candidates = linkedSetOf<String>()
                val next = linkedSetOf<String>()
                for (current in level) {
                    if (!seen.add(current)) continue
                    sourceMemberType(
                        current,
                        simpleName,
                        accessingPackage
                    )?.let(candidates::add)
                    externalHeritage?.directMemberType(
                        current,
                        simpleName,
                        accessingPackage,
                        inherited = true
                    )?.let(candidates::add)
                    next += directHeritage(current)
                }
                if (candidates.isNotEmpty()) return candidates
                level = next
            }
            return emptySet()
        }

        private fun directHeritage(owner: String): List<String> {
            val source = types[owner]
            return if (source != null) {
                listOfNotNull(source.parent) + source.interfaces
            } else {
                externalHeritage?.directHeritage(owner).orEmpty()
            }
        }

        private fun sourceMemberType(
            owner: String,
            simpleName: String,
            accessingPackage: String
        ): String? {
            val candidate = types["$owner.$simpleName"] ?: return null
            val parent = candidate.declaration.parentNode.orElse(null) as? TypeDeclaration<*>
                ?: return null
            val parentType = sourceType(parent) ?: return null
            if (parentType.canonicalName != owner) return null
            val modifiers = candidate.declaration.modifiers.map(Modifier::getKeyword).toSet()
            if (Modifier.Keyword.PRIVATE in modifiers) return null
            if (Modifier.Keyword.PUBLIC in modifiers ||
                Modifier.Keyword.PROTECTED in modifiers ||
                parent is ClassOrInterfaceDeclaration && parent.isInterface
            ) {
                return candidate.canonicalName
            }
            return candidate.canonicalName.takeIf {
                candidate.source.packageName == accessingPackage
            }
        }
    }

    private data class MethodIdentity(
        val owner: String,
        val name: String,
        val parameterTypes: List<String>
    )

    private data class MethodParameterIdentity(
        val identity: String,
        val resolved: Boolean
    )

    private data class ProjectMethod(
        val identity: MethodIdentity,
        val signatureResolved: Boolean,
        val returnsLegacyPlantable: Boolean,
        val legacyParameterPositions: Set<Int>
    )

    private sealed interface SourcePlacementSuper {
        data object Source : SourcePlacementSuper
        data class OldVanilla(val declaringClass: String) : SourcePlacementSuper
        data object Unknown : SourcePlacementSuper
    }

    private enum class SuperSoilDispatch {
        SOURCE_OVERRIDE,
        VANILLA_DEFAULT,
        UNKNOWN
    }

    private data class PlannedWrite(
        val file: Path,
        val content: String,
        val change: Change
    )

    fun migrate(pass: StructuralRefactorPass, project: Path, dryRun: Boolean): List<Change> {
        val root = project.resolve("src/main/java")
        if (!root.isDirectory()) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val files = pass.walkJavaSourceRoots(root).use { paths ->
            paths.filter { it.extension == "java" }
                .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
                .filter {
                    !it.toString().replace('\\', '/')
                        .contains("/com/modporter/compat/plant/v1/")
                }
                .toList()
        }

        // Parse the whole source tree for source ownership and inheritance. Once any legacy plant
        // source exists, every source must parse: an unparsed token-free child may still inherit a
        // legacy alias declared in another file.
        val parseFailures = mutableListOf<Pair<Path, String>>()
        val units = files.mapNotNull { file ->
            val source = file.readText()
            val masked = pass.maskJavaCommentsAndLiterals(source)
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful) {
                parseFailures += file to pass.parseProblems(parsed)
                return@mapNotNull null
            }
            SourceUnit(file, source, masked, parsed.result.orElseThrow())
        }
        if (units.any { containsLegacyToken(it.masked) } && parseFailures.isNotEmpty()) {
            val failures = parseFailures.joinToString("; ") { (file, problem) ->
                "$file: $problem"
            }
            error(
                "Cannot build a complete legacy plant heritage index because source parsing " +
                    "failed: $failures; no files changed"
            )
        }

        gateMixedSimpleOwnership(units)
        val projectTypes = collectProjectTypeNames(units)
        val visibility = units.associateWith { source ->
            visibility(source, projectTypes)
        }
        val externalHeritage =
            if (LegacyMigrationArtifactInventoryHandoff.isPublished(project)) {
                SealedLegacyPlantSourceHeritage.from(
                    LegacyMigrationArtifactInventoryHandoff.require(project)
                )
            } else {
                SealedLegacyPlantSourceHeritage.java17Platform()
            }
        val index = buildIndex(
            units,
            visibility,
            projectTypes,
            externalHeritage
        )
        val affected = units.filter { source ->
            val visible = visibility.getValue(source)
            source.unit.findAll(ClassOrInterfaceType::class.java).any {
                isLegacyType(it, "IPlantable", legacyIPlantable, visible.iPlantable) ||
                    isLegacyType(it, "PlantType", legacyPlantType, visible.plantType)
                } ||
                containsImplicitLegacyOverride(source, visible, index) ||
                containsUnsupportedScopedLegacyHeritage(source, index) ||
                index.types.values.any {
                    it.source === source &&
                        (index.inheritsLegacyContract(it.canonicalName) ||
                            index.sourceDeclaresLegacyPlacement(it))
                }
        }

        // Unresolved simple names are a reason to stop, never permission to guess ownership.
        for (source in units.filter { containsLegacyToken(it.masked) }) {
            verifyNoUnownedSimpleName(source, "IPlantable", legacyIPlantable, projectTypes)
            verifyNoUnownedSimpleName(source, "PlantType", legacyPlantType, projectTypes)
        }
        if (affected.isEmpty()) return emptyList()
        verifyCompleteHeritageIndex(index)
        gateUnsupportedLegacyOwners(affected, visibility, index)
        verifyTargetSurface()
        verifyExternalCoverage(project, affected, visibility)

        val compatPackage = COMPAT_PACKAGE
        val helper = "$compatPackage.LegacyPlantables"

        // All gates and every transformed byte are computed before the first write.
        val migratedSources = linkedMapOf<Path, String>()
        for (source in affected) {
            val migrated = migrateSource(
                parser = parser,
                source = source,
                visibility = visibility.getValue(source),
                projectIndex = index,
                compatPackage = compatPackage,
                helper = helper
            )
            check(parser.parse(migrated).isSuccessful) {
                "Legacy plantable migration emitted invalid Java for ${source.file}; no files changed"
            }
            if (migrated != source.source) migratedSources[source.file] = migrated
        }

        val writes = mutableListOf<PlannedWrite>()
        for ((file, migrated) in migratedSources) {
            writes += PlannedWrite(
                file,
                migrated,
                Change(
                    file = file,
                    line = 1,
                    description = "Retain Forge 1.20.1 plant identity, state and soil dispatch",
                    before = "IPlantable/PlantType and boolean canSustainPlant",
                    after = "source-owned compat contracts and exact legacy dispatch",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            )
        }

        val generated = generatedSources(compatPackage)
        for ((name, content) in generated) {
            check(parser.parse(content).isSuccessful) {
                "Legacy plantable template $name emitted invalid Java; no files changed"
            }
        }
        val requestFile = project.resolve(RUNTIME_REQUEST)
        val requestContent = runtimeRequest(generated)
        if (!requestFile.exists() || requestFile.readText() != requestContent) {
            writes += PlannedWrite(
                requestFile,
                requestContent,
                Change(
                    file = requestFile,
                    line = 1,
                    description = "Request the canonical shared legacy plant Jar-in-Jar runtime",
                    before = "(missing or stale runtime request)",
                    after = "fixed package, source hash and canonical JAR hash",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            )
        }

        if (!dryRun) {
            for (write in writes) {
                write.file.parent.createDirectories()
                write.file.writeText(write.content)
            }
        }
        return writes.map { it.change }
    }

    private fun migrateSource(
        parser: JavaParser,
        source: SourceUnit,
        visibility: Visibility,
        projectIndex: ProjectIndex,
        compatPackage: String,
        helper: String
    ): String {
        val unit = source.unit
        LexicalPreservingPrinter.setup(unit)
        val plantableTypes = unit.findAll(ClassOrInterfaceType::class.java)
            .filter { isLegacyType(it, "IPlantable", legacyIPlantable, visibility.iPlantable) }
            .toList()
        val plantTypes = unit.findAll(ClassOrInterfaceType::class.java)
            .filter { isLegacyType(it, "PlantType", legacyPlantType, visibility.plantType) }
            .toList()
        val recordComponentShapes = unit.findAll(RecordDeclaration::class.java).map { record ->
            record to record.parameters.map(Parameter::toString)
        }
        val facts = LegacyExpressionFacts(source, plantableTypes, projectIndex)
        val implicitOwners = unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .filter { owner ->
                val sourceType = projectIndex.sourceType(owner) ?: return@filter false
                projectIndex.inheritsOldVanillaPlantContract(sourceType.canonicalName) &&
                    owner.methods.any {
                        isExactGetPlant(it, source) || isExactGetPlantType(it, source, visibility)
                    }
            }.toSet()
        var needsPlantableImport = false

        gateUnsupportedTypeShapes(source, plantableTypes, facts)
        gateOverloadCollapse(source, plantableTypes, projectIndex)
        gateLegacyArgumentFlows(source, facts, projectIndex)

        for (owner in implicitOwners) {
            if (owner.implementedTypes.none {
                    it.nameAsString == "IPlantable" ||
                        it.nameWithScope == "$compatPackage.IPlantable"
                }
            ) {
                owner.addImplementedType("IPlantable")
            }
            needsPlantableImport = true
        }

        // Preserve source-owned old mayPlaceOn bodies separately from the target method. The
        // target method remains in place for 1.21.1 canSurvive behavior; the cloned compatibility
        // method rewrites old super dispatch to a transformed source bridge or to the exact
        // Forge 1.20.1 vanilla declaring-class dispatcher.
        for (owner in unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
            val ownerType = projectIndex.sourceType(owner) ?: continue
            if (TargetMembers.hasClass(ownerType.canonicalName)) continue
            if (!projectIndex.inheritsFrom(ownerType.canonicalName, BUSH_BLOCK)) continue
            val oldMethod = owner.methods.singleOrNull {
                isExactMayPlaceOn(it, source)
            } ?: continue
            if (owner.methods.any {
                    it.nameAsString == LEGACY_PLACEMENT_METHOD &&
                        isExactMayPlaceOnParameters(it, source)
                }
            ) {
                continue
            }
            val compatibilityMethod = parser.parseBodyDeclaration<MethodDeclaration>(
                oldMethod.toString()
            ).result.orElseThrow()
            compatibilityMethod.setName(LEGACY_PLACEMENT_METHOD)
            compatibilityMethod.modifiers.removeIf {
                it.keyword in setOf(
                    Modifier.Keyword.PRIVATE,
                    Modifier.Keyword.PROTECTED,
                    Modifier.Keyword.PUBLIC
                )
            }
            compatibilityMethod.addModifier(Modifier.Keyword.PUBLIC)
            compatibilityMethod.annotations.removeIf { it.nameAsString == "Override" }
            compatibilityMethod.addAnnotation("Override")

            val superCalls = compatibilityMethod.findAll(MethodCallExpr::class.java)
                .filter {
                    it.nameAsString == "mayPlaceOn" &&
                        it.scope.orElse(null) is SuperExpr &&
                        it.arguments.size == 3 &&
                        it.findAncestor(MethodDeclaration::class.java)
                            .map { method -> method === compatibilityMethod }
                            .orElse(false)
                }
            val superDispatch = projectIndex.nearestSourcePlacementSuper(
                ownerType.canonicalName
            )
            if (superCalls.isNotEmpty() && superDispatch == SourcePlacementSuper.Unknown) {
                error(
                    "Cannot prove old super.mayPlaceOn dispatch for " +
                        "${ownerType.canonicalName}; source left unchanged"
                )
            }
            for (call in superCalls) {
                val replacement = when (superDispatch) {
                    SourcePlacementSuper.Source ->
                        "super.$LEGACY_PLACEMENT_METHOD(${call.arguments.joinToString(", ")})"
                    is SourcePlacementSuper.OldVanilla ->
                        "$helper.oldMayPlaceOnFrom(" +
                            "${superDispatch.declaringClass}.class, this, " +
                            "${call.arguments.joinToString(", ")})"
                    SourcePlacementSuper.Unknown -> error("unreachable")
                }
                call.replace(expression(parser, replacement))
            }
            for (call in compatibilityMethod.findAll(MethodCallExpr::class.java).toList()) {
                if (call.nameAsString != "mayPlaceOn" || call.arguments.size != 3) continue
                if (call.findAncestor(MethodDeclaration::class.java)
                        .map { method -> method !== compatibilityMethod }
                        .orElse(true)
                ) {
                    continue
                }
                val scope = call.scope.orElse(null)
                if (scope == null ||
                    scope is ThisExpr && scope.typeName.isEmpty
                ) {
                    call.setName(LEGACY_PLACEMENT_METHOD)
                } else if (scope !is SuperExpr) {
                    error(
                        "Cannot preserve qualified mayPlaceOn receiver '$scope' in the old " +
                            "placement clone for ${ownerType.canonicalName}; source left unchanged"
                    )
                }
            }
            owner.addMember(compatibilityMethod)
            if (owner.implementedTypes.none {
                    it.nameWithScope == "$compatPackage.LegacyPlantPlacement" ||
                        it.nameAsString == "LegacyPlantPlacement"
                }
            ) {
                owner.addImplementedType("$compatPackage.LegacyPlantPlacement")
            }
        }

        val customSoilMethods = unit.findAll(MethodDeclaration::class.java).filter { method ->
            method.nameAsString == "canSustainPlant" &&
                method.parameters.lastOrNull()?.type.let { it != null && isLegacyType(it, "IPlantable", legacyIPlantable, visibility.iPlantable) }
        }
        for (method in customSoilMethods) {
            require(isLegacySoilSignature(method, source)) {
                "Unsupported canSustainPlant shape in ${source.file}; source left unchanged"
            }
            val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
                ?: error("Anonymous legacy soil override in ${source.file}; source left unchanged")
            val ownerType = projectIndex.sourceType(owner)
                ?: error("Cannot resolve legacy soil owner ${owner.nameAsString} in ${source.file}")
            require(projectIndex.inheritsFrom(ownerType.canonicalName, BLOCK)) {
                "Legacy soil owner ${ownerType.canonicalName} is not source-proven to extend Block; source left unchanged"
            }
            val superCalls = method.findAll(MethodCallExpr::class.java).filter {
                it.nameAsString == "canSustainPlant" &&
                    it.scope.orElse(null) is SuperExpr &&
                    it.arguments.size == 5 &&
                    it.findAncestor(MethodDeclaration::class.java)
                        .map { ownerMethod -> ownerMethod === method }
                        .orElse(false)
            }
            val superDispatch = projectIndex.superSoilDispatch(ownerType.canonicalName)
            if (superCalls.isNotEmpty() && superDispatch == SuperSoilDispatch.UNKNOWN) {
                error(
                    "Cannot prove the super canSustainPlant implementation for " +
                        "${ownerType.canonicalName}; source left unchanged"
                )
            }
            for (call in superCalls) {
                val replacement = when (superDispatch) {
                    SuperSoilDispatch.SOURCE_OVERRIDE ->
                        "super.$LEGACY_SOIL_METHOD(${call.arguments.joinToString(", ")})"
                    SuperSoilDispatch.VANILLA_DEFAULT ->
                        "$helper.canSustainPlantAfterSource(this, ${call.arguments.joinToString(", ")})"
                    SuperSoilDispatch.UNKNOWN -> error("unreachable")
                }
                call.replace(expression(parser, replacement))
            }
            method.setName(LEGACY_SOIL_METHOD)
            method.parameters.last().setType("java.lang.Object")
            if (owner.implementedTypes.none {
                    it.nameWithScope == "$compatPackage.LegacyPlantSustainer" ||
                        it.nameAsString == "LegacyPlantSustainer"
                }
            ) {
                owner.addImplementedType("$compatPackage.LegacyPlantSustainer")
            }
        }

        // Source declarations that explicitly inherited the old vanilla getPlant body receive the
        // same body locally. The generated IPlantable contract itself keeps getPlant abstract.
        for (owner in unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
            val ownerType = projectIndex.sourceType(owner) ?: continue
            val legacyHeritage =
                projectIndex.inheritsLegacyContract(ownerType.canonicalName) ||
                    owner in implicitOwners
            if (!legacyHeritage || owner.isInterface) continue
            if (projectIndex.sourceHierarchyProvidesConcreteExactGetPlant(
                    ownerType.canonicalName
                )
            ) continue
            val directAbstractObligation = owner.methods.any {
                isExactGetPlant(it, source) && it.body.isEmpty
            }
            if (directAbstractObligation) {
                require(owner.isAbstract) {
                    "Concrete ${ownerType.canonicalName} declares abstract getPlant; " +
                        "source left unchanged"
                }
                continue
            }
            val inheritedOldVanillaBody =
                projectIndex.inheritsOldVanillaPlantContract(ownerType.canonicalName)
            if (inheritedOldVanillaBody) {
                owner.addMember(
                    parser.parseBodyDeclaration<BodyDeclaration<*>>(
                        """
                        @Override
                        public net.minecraft.world.level.block.state.BlockState getPlant(
                            net.minecraft.world.level.BlockGetter level,
                            net.minecraft.core.BlockPos pos
                        ) {
                            return $helper.defaultPlant(this, level, pos);
                        }
                        """.trimIndent()
                    ).result.orElseThrow()
                )
            } else if (!owner.isAbstract) {
                error(
                    "Concrete ${ownerType.canonicalName} relies on an unproven external getPlant " +
                        "implementation; source left unchanged"
                )
            }
        }

        // Rewrite old BlockState and direct Block calls before changing their argument types.
        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            if (!call.findCompilationUnit().isPresent || call.nameAsString != "canSustainPlant") continue
            if (call.arguments.size == 4 && facts.isLegacyPlantable(call.arguments[3])) {
                val scope = call.scope.orElse(null)
                    ?: error("Unscoped four-argument canSustainPlant in ${source.file}; source left unchanged")
                call.replace(
                    expression(
                        parser,
                        "$helper.canSustainPlant($scope, ${call.arguments.joinToString(", ")})"
                    )
                )
            } else if (call.arguments.size == 5 && facts.isLegacyPlantable(call.arguments[4])) {
                if (call.scope.orElse(null) is SuperExpr &&
                    call.findAncestor(MethodDeclaration::class.java).map { it in customSoilMethods }.orElse(false)
                ) {
                    continue
                }
                val scope = call.scope.orElse(null)
                    ?: error("Unscoped five-argument canSustainPlant in ${source.file}; source left unchanged")
                call.replace(
                    expression(
                        parser,
                        "$helper.canSustainPlant($scope, ${call.arguments.joinToString(", ")})"
                    )
                )
            }
        }

        // getPlant/getPlantType calls on old interface values retain the original object identity;
        // no wrapper is introduced for Blocks.MELON or another vanilla singleton.
        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            if (!call.findCompilationUnit().isPresent ||
                call.nameAsString !in setOf("getPlant", "getPlantType") ||
                call.arguments.size != 2
            ) continue
            val scope = call.scope.orElse(null) ?: continue
            if (facts.isLegacyPlantable(scope)) {
                call.replace(
                    expression(
                        parser,
                        "$helper.${call.nameAsString}($scope, ${call.arguments.joinToString(", ")})"
                    )
                )
                continue
            }
            if (scope is MethodCallExpr && facts.mayReturnLegacyPlantable(scope)) {
                error(
                    "Ambiguous legacy plant return flow '${scope}' in ${source.file}; " +
                        "source left unchanged"
                )
            }
        }

        for (test in unit.findAll(InstanceOfExpr::class.java).toList()) {
            if (!test.findCompilationUnit().isPresent ||
                !isLegacyType(test.type, "IPlantable", legacyIPlantable, visibility.iPlantable)
            ) continue
            if (test.pattern.isPresent) {
                error(
                    "Pattern-bound IPlantable instanceof in ${source.file} requires control-flow " +
                        "rewriting; source left unchanged"
                )
            }
            test.replace(expression(parser, "$helper.isPlantable(${test.expression})"))
        }

        for (cast in unit.findAll(CastExpr::class.java).toList()) {
            if (!cast.findCompilationUnit().isPresent ||
                !isLegacyType(cast.type, "IPlantable", legacyIPlantable, visibility.iPlantable)
            ) continue
            cast.replace(expression(parser, "$helper.requirePlantable(${cast.expression})"))
        }

        for (type in plantableTypes) {
            if (!type.findCompilationUnit().isPresent) continue
            if (isContractHeritage(type) || type.parentNode.orElse(null) is ObjectCreationExpr) {
                type.replace(
                    parser.parseClassOrInterfaceType("IPlantable").result.orElseThrow()
                )
                needsPlantableImport = true
            } else {
                type.replace(parser.parseClassOrInterfaceType("java.lang.Object").result.orElseThrow())
            }
        }

        var needsPlantTypeImport = false
        for (type in plantTypes) {
            if (!type.findCompilationUnit().isPresent) continue
            type.replace(parser.parseClassOrInterfaceType("PlantType").result.orElseThrow())
            needsPlantTypeImport = true
        }

        unit.imports.removeIf { import ->
            !import.isStatic && !import.isAsterisk &&
                (import.nameAsString in legacyIPlantable || import.nameAsString in legacyPlantType)
        }
        if (needsPlantableImport && unit.imports.none { it.nameAsString == "$compatPackage.IPlantable" }) {
            unit.addImport("$compatPackage.IPlantable")
        }
        if (needsPlantTypeImport && unit.imports.none { it.nameAsString == "$compatPackage.PlantType" }) {
            unit.addImport("$compatPackage.PlantType")
        }
        for ((record, components) in recordComponentShapes) {
            check(record.parameters.map(Parameter::toString) == components) {
                "Legacy plant migration changed record components or generated accessor shape for " +
                    "${canonicalTypeName(source, record)}; source left unchanged"
            }
        }
        return LexicalPreservingPrinter.print(unit)
    }

    private fun generatedSources(compatPackage: String): Map<String, String> {
        val templates = canonicalRuntimeTemplates()
        val sourceSha256 = canonicalSourceHash(templates)
        return templates.mapKeys { (name, _) -> "$name.java" }
            .mapValues { (_, template) ->
                template
                    .replace("@PACKAGE@", compatPackage)
                    .replace("@RUNTIME_SOURCE_SHA256@", sourceSha256)
            }
    }

    private fun verifyTargetSurface() {
        require((OLD_MAY_PLACE_ON_DECLARERS +
            setOf(BAMBOO, CACTUS, SUGAR_CANE, PUMPKIN)).all {
                TargetMembers.hasClass(it)
            }
        ) {
            "Target vanilla plant/placement inventory no longer matches the verified 1.21.1 surface"
        }
        require(!TargetMembers.hasClass("net.minecraft.world.level.block.StemGrownBlock") &&
            !TargetMembers.hasClass("net.minecraft.world.level.block.MelonBlock")
        ) {
            "Target StemGrownBlock/MelonBlock inventory changed; re-verify identity adapters"
        }
        require(
            TargetMembers.fieldDescriptor(
                "net.minecraft.world.level.block.Blocks",
                "MELON"
            ) == "Lnet/minecraft/world/level/block/Block;"
        ) {
            "Target Blocks.MELON is no longer declared as Block; re-verify its adapter"
        }
        require(!TargetMembers.hasClass("net.neoforged.neoforge.common.IPlantable") &&
            !TargetMembers.hasClass("net.neoforged.neoforge.common.PlantType")
        ) {
            "Target restored a native plant contract; generated compatibility must be reconsidered"
        }
    }

    /**
     * Consumer-only projects such as Create can receive arbitrary dependency/world blocks. Source
     * transformation cannot prove those runtime classes implement the shared ABI or registered
     * adapters, so acceptance requires an inventory produced by the dependency-conversion stage.
     */
    private fun verifyExternalCoverage(
        project: Path,
        affected: List<SourceUnit>,
        visibility: Map<SourceUnit, Visibility>
    ) {
        val consumesArbitraryPlant = affected.any { source ->
            val visible = visibility.getValue(source)
            source.unit.findAll(ClassOrInterfaceType::class.java).any {
                isLegacyType(
                    it,
                    "IPlantable",
                    legacyIPlantable,
                    visible.iPlantable
                ) && !isContractHeritage(it)
            } ||
                source.unit.findAll(InstanceOfExpr::class.java).any {
                isLegacyType(
                    it.type,
                    "IPlantable",
                    legacyIPlantable,
                    visible.iPlantable
                )
            } ||
                source.unit.findAll(CastExpr::class.java).any {
                    isLegacyType(
                        it.type,
                        "IPlantable",
                        legacyIPlantable,
                        visible.iPlantable
                    )
                } ||
                source.unit.findAll(MethodCallExpr::class.java).any {
                    it.nameAsString == "canSustainPlant" &&
                        it.arguments.size in setOf(4, 5)
                }
        }
        if (!consumesArbitraryPlant) return

        val directCoverage =
            project.resolve(".modporter/legacy-plant-external-coverage.properties")
        val coverageProject = if (directCoverage.exists()) {
            project
        } else {
            LegacyMigrationArtifactInventoryHandoff.require(project).ownerProject
        }
        val coverage =
            coverageProject.resolve(".modporter/legacy-plant-external-coverage.properties")
        require(coverage.exists()) {
            "Legacy IPlantable consumer flow can receive external blocks, but no complete shared " +
                "adapter/dependency inventory exists at $coverage; source left unchanged"
        }
        val values = coverage.readText().lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { '=' in it }
            .associate { line ->
                line.substringBefore('=').trim() to line.substringAfter('=').trim()
            }
        val sourceHash = canonicalSourceHash(canonicalRuntimeTemplates())
        if (values["schema"] == "legacy-plant-external-coverage-v2") {
            val inventory = LegacyMigrationArtifactInventoryHandoff.require(project)
            require(inventory.ownerProject == coverageProject.toAbsolutePath().normalize()) {
                "Legacy plant v2 coverage pointer belongs to a different owning project"
            }
            LegacyPlantCoverageV2Verifier.verify(
                project = inventory.ownerProject,
                runtimeIdentity = CanonicalRuntimeIdentity.fromProductionResources()
            )
            return
        }
        require(values["schema"] == "legacy-plant-external-coverage-v1" &&
            values["producer"] == "dependency-conversion-v1" &&
            values["complete"] == "true" &&
            values["runtimeSourceSha256"] == sourceHash &&
            values["runtimeJarSha256"] == canonicalRuntimeJarHash()
        ) {
            "Legacy plant external coverage is incomplete or belongs to another runtime; " +
                "source left unchanged"
        }
        val entryCount = values["entryCount"]?.toIntOrNull()
        require(entryCount != null && entryCount > 0) {
            "Legacy plant external coverage has no bound dependency entries; source left unchanged"
        }
        val allowedCoverage = setOf(
            "canonical-abi",
            "shared-adapters",
            "verified-vanilla"
        )
        for (index in 0 until entryCount) {
            val prefix = "entry.$index."
            val id = values["${prefix}id"].orEmpty()
            val artifactPath = values["${prefix}artifactPath"].orEmpty()
            val artifactHash = values["${prefix}artifactSha256"].orEmpty()
            val admission = values["${prefix}coverage"].orEmpty()
            require(id.matches(Regex("[A-Za-z0-9_.:-]+")) &&
                artifactPath.isNotBlank() &&
                artifactHash.matches(Regex("[0-9a-f]{64}")) &&
                admission in allowedCoverage
            ) {
                "Malformed legacy plant dependency entry $index; source left unchanged"
            }
            val artifact = project.resolve(artifactPath).normalize()
            require(artifact.isRegularFile() && sha256(artifact) == artifactHash) {
                "Legacy plant dependency entry $id does not match its artifact bytes; " +
                    "source left unchanged"
            }
            if (admission == "shared-adapters") {
                val manifestPath = values["${prefix}registrationManifestPath"].orEmpty()
                val manifestHash = values["${prefix}registrationManifestSha256"].orEmpty()
                require(manifestPath.isNotBlank() &&
                    manifestHash.matches(Regex("[0-9a-f]{64}"))
                ) {
                    "Shared adapter entry $id has no registration manifest binding; " +
                        "source left unchanged"
                }
                val manifest = project.resolve(manifestPath).normalize()
                require(manifest.isRegularFile() && sha256(manifest) == manifestHash) {
                    "Shared adapter registration manifest for $id changed; source left unchanged"
                }
                val manifestValues = manifest.readText().lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                    .associate { line ->
                        line.substringBefore('=').trim() to
                            line.substringAfter('=').trim()
                    }
                val registrationCount =
                    manifestValues["registrationCount"]?.toIntOrNull() ?: 0
                require(
                    manifestValues["schema"] ==
                        "legacy-plant-adapter-registration-v1" &&
                        manifestValues["complete"] == "true" &&
                        manifestValues["runtimeSourceSha256"] == sourceHash &&
                        manifestValues["runtimeJarSha256"] == canonicalRuntimeJarHash() &&
                        manifestValues["artifactSha256"] == artifactHash &&
                        registrationCount > 0
                ) {
                    "Shared adapter registration manifest for $id is incomplete or belongs to " +
                        "different artifact/runtime bytes; source left unchanged"
                }
                for (registrationIndex in 0 until registrationCount) {
                    val registrationPrefix = "registration.$registrationIndex."
                    require(
                        manifestValues["${registrationPrefix}kind"] in
                            setOf("plant", "soil", "placement") &&
                            manifestValues["${registrationPrefix}mode"] in
                            setOf("exact", "class") &&
                            !manifestValues["${registrationPrefix}target"].isNullOrBlank() &&
                            !manifestValues["${registrationPrefix}owner"].isNullOrBlank() &&
                            manifestValues["${registrationPrefix}sourceSha256"]
                                ?.matches(Regex("[0-9a-f]{64}")) == true
                    ) {
                        "Shared adapter registration $registrationIndex for $id is incomplete; " +
                            "source left unchanged"
                    }
                }
            }
        }
        val entryKeys = values.keys.filter { it.startsWith("entry.") }.toSortedSet()
        val baseFields = setOf("id", "artifactPath", "artifactSha256", "coverage")
        val adapterFields = setOf(
            "registrationManifestPath",
            "registrationManifestSha256"
        )
        require(entryKeys.all { key ->
            val match = Regex("""entry\.(\d+)\.([A-Za-z][A-Za-z0-9]*)""").matchEntire(key)
                ?: return@all false
            val index = match.groupValues[1].toInt()
            val field = match.groupValues[2]
            index in 0 until entryCount && field in baseFields + adapterFields
        }) {
            "Legacy plant external coverage has unknown or out-of-range entry fields; " +
                "source left unchanged"
        }
        require((0 until entryCount).all { index ->
            baseFields.all { field -> "entry.$index.$field" in entryKeys }
        }) {
            "Legacy plant external coverage entry set is incomplete; source left unchanged"
        }
        val canonicalInventory = entryKeys.joinToString("\n", postfix = "\n") {
            "$it=${values.getValue(it)}"
        }
        require(values["inventorySha256"] == sha256(canonicalInventory)) {
            "Legacy plant external coverage inventory hash mismatch; source left unchanged"
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(
            java.nio.file.Files.readAllBytes(path)
        ).joinToString("") { "%02x".format(it) }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(
            content.toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }

    private fun canonicalRuntimeTemplates(): Map<String, String> =
        LegacyPlantRuntimeResources.javaTemplates()

    private fun canonicalSourceHash(templates: Map<String, String>): String =
        LegacyPlantRuntimeResources.canonicalSourceHashText(templates)

    private fun canonicalRuntimeJarHash(): String {
        val value = checkNotNull(
            javaClass.getResource("/compat/legacy-plant-compat-v1.jar.sha256")
        ) {
            "Missing canonical legacy plant runtime JAR hash"
        }.readText().trim()
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Malformed canonical legacy plant runtime JAR hash"
        }
        return value
    }

    private fun runtimeRequest(generatedSources: Map<String, String>): String {
        require(generatedSources.isNotEmpty())
        val sourceSha256 = canonicalSourceHash(canonicalRuntimeTemplates())
        return buildString {
            appendLine("schema=legacy-plant-runtime-request-v1")
            appendLine("package=$COMPAT_PACKAGE")
            appendLine("sourceSha256=$sourceSha256")
            appendLine("jarSha256=${canonicalRuntimeJarHash()}")
        }
    }

    private fun buildIndex(
        units: List<SourceUnit>,
        visibility: Map<SourceUnit, Visibility>,
        projectTypes: Set<String>,
        externalHeritage: SealedLegacyPlantSourceHeritage?
    ): ProjectIndex {
        val types = linkedMapOf<String, SourceType>()
        for (source in units) {
            for (declaration in source.unit.findAll(TypeDeclaration::class.java)
                .filter { isProjectTypeDeclaration(it) }
            ) {
                val canonical = canonicalTypeName(source, declaration)
                require(canonical !in types) {
                    "Duplicate canonical source type $canonical; source left unchanged"
                }
                types[canonical] = SourceType(
                    canonical,
                    source,
                    declaration,
                    sourceMethods(declaration).map { it.clone() }
                )
            }
        }
        val partialIndex = ProjectIndex(
            types = types,
            methods = emptyMap(),
            externalHeritage = externalHeritage
        )
        data class PendingHeritage(
            val owner: SourceType,
            val heritage: ClassOrInterfaceType,
            val parent: Boolean
        )
        var pending = types.values.flatMap { type ->
            val declaration = type.declaration
            buildList {
                if (declaration is ClassOrInterfaceDeclaration &&
                    !declaration.isInterface
                ) {
                    declaration.extendedTypes.firstOrNull()?.let {
                        add(PendingHeritage(type, it, true))
                    }
                }
                heritageInterfaces(declaration).forEach {
                    add(PendingHeritage(type, it, false))
                }
            }
        }
        while (pending.isNotEmpty()) {
            var progressed = false
            val remaining = mutableListOf<PendingHeritage>()
            for (item in pending) {
                val resolved = resolveHeritageType(
                    heritage = item.heritage,
                    owner = item.owner,
                    projectTypes = projectTypes,
                    index = partialIndex
                )
                if (resolved == null) {
                    remaining += item
                    continue
                }
                if (item.parent) {
                    check(item.owner.parent == null) {
                        "Source type ${item.owner.canonicalName} has more than one parent"
                    }
                    item.owner.parent = resolved
                } else {
                    item.owner.interfaces += resolved
                }
                progressed = true
            }
            if (!progressed) {
                remaining.forEach { item ->
                    item.owner.unresolvedHeritage +=
                        unresolvedHeritageName(
                            item.heritage,
                            item.owner,
                            projectTypes,
                            partialIndex
                        )
                }
                break
            }
            pending = remaining
        }
        for (type in types.values) {
            val declaration = type.declaration
            val visible = visibility.getValue(type.source)
            type.directLegacyContract = heritageInterfaces(declaration).any {
                    isLegacyType(
                        it,
                        "IPlantable",
                        legacyIPlantable,
                        visible.iPlantable
                    )
                }
            type.legacySoil = sourceMethods(declaration).any {
                it.nameAsString == "canSustainPlant" &&
                    it.parameters.lastOrNull()?.type.let { last ->
                        last != null && isLegacyType(
                            last,
                            "IPlantable",
                            legacyIPlantable,
                            visible.iPlantable
                        )
                    }
            }
        }
        val methods = linkedMapOf<MethodIdentity, ProjectMethod>()
        for (type in types.values) {
            val source = type.source
            val visible = visibility.getValue(source)
            for (method in sourceMethods(type.declaration)) {
                val parameterTypes = method.parameters.map {
                    methodParameterIdentity(
                        parameter = it,
                        method = method,
                        source = source,
                        projectTypes = projectTypes,
                        owner = type.declaration,
                        index = partialIndex
                    )
                }
                val identity = MethodIdentity(
                    owner = type.canonicalName,
                    name = method.nameAsString,
                    parameterTypes = parameterTypes.map {
                        it.identity
                    }
                )
                require(identity !in methods) {
                    "Duplicate source method identity $identity in ${source.file}"
                }
                methods[identity] = ProjectMethod(
                    identity = identity,
                    signatureResolved = parameterTypes.all {
                        it.resolved
                    },
                    returnsLegacyPlantable = isLegacyType(
                        method.type,
                        "IPlantable",
                        legacyIPlantable,
                        visible.iPlantable
                    ),
                    legacyParameterPositions = method.parameters.mapIndexedNotNull { index, parameter ->
                    index.takeIf {
                        isLegacyType(
                            parameter.type,
                            "IPlantable",
                            legacyIPlantable,
                            visible.iPlantable
                        )
                    }
                }.toSet()
                )
            }
        }
        return ProjectIndex(types, methods, externalHeritage)
    }

    private class LegacyExpressionFacts(
        private val source: SourceUnit,
        plantableTypes: List<ClassOrInterfaceType>,
        private val index: ProjectIndex
    ) {
        private data class Binding(
            val name: String,
            val declaration: Node,
            val scope: Node,
            val field: Boolean,
            val legacy: Boolean
        )

        private val legacyTypeNodes = plantableTypes.toSet()
        private val bindings = buildList {
            for (variable in source.unit.findAll(VariableDeclarator::class.java)) {
                val scope = bindingScope(variable) ?: continue
                add(
                    Binding(
                        variable.nameAsString,
                        variable,
                        scope,
                        variable.parentNode.orElse(null) is FieldDeclaration,
                        containsLegacyType(variable.type)
                    )
                )
            }
            for (parameter in source.unit.findAll(Parameter::class.java)) {
                val scope = bindingScope(parameter) ?: continue
                add(
                    Binding(
                        parameter.nameAsString,
                        parameter,
                        scope,
                        false,
                        containsLegacyType(parameter.type)
                    )
                )
            }
        }

        fun isLegacyPlantable(expression: Expression): Boolean = when (expression) {
            is EnclosedExpr -> isLegacyPlantable(expression.inner)
            is CastExpr -> containsLegacyType(expression.type)
            is NameExpr -> resolvesLegacyBinding(expression.nameAsString, expression)
            is FieldAccessExpr ->
                expression.scope is ThisExpr &&
                    resolvesLegacyBinding(expression.nameAsString, expression)
            is ConditionalExpr ->
                (isLegacyPlantable(expression.thenExpr) &&
                    (isLegacyPlantable(expression.elseExpr) ||
                        expression.elseExpr is NullLiteralExpr)) ||
                    (isLegacyPlantable(expression.elseExpr) &&
                        expression.thenExpr is NullLiteralExpr)
            is ThisExpr -> {
                val owner = expression.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null)
                owner?.let {
                    index.inheritsLegacyContract(canonicalTypeName(source, it))
                } == true
            }
            is ObjectCreationExpr -> containsLegacyType(expression.type)
            is MethodCallExpr -> returnsLegacyPlantable(expression)
            else -> false
        }

        fun isLegacyMethodReferenceScope(expression: Expression): Boolean =
            isLegacyPlantable(expression) ||
                (expression as? TypeExpr)?.type
                    ?.let { it as? ClassOrInterfaceType }
                    ?.takeIf { it.scope.isEmpty }
                    ?.let { resolvesLegacyBinding(it.nameAsString, expression) } == true

        fun mayReturnLegacyPlantable(call: MethodCallExpr): Boolean {
            return localMethod(call)?.returnsLegacyPlantable == true
        }

        private fun returnsLegacyPlantable(call: MethodCallExpr): Boolean {
            return localMethod(call)?.returnsLegacyPlantable == true
        }

        private fun localMethod(call: MethodCallExpr): ProjectMethod? {
            if (call.scope.isPresent) {
                val scope = call.scope.orElse(null)
                if (scope !is ThisExpr || scope.typeName.isPresent) return null
            }
            val owner = call.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .orElse(null) ?: return null
            return index.exactLocalMethod(
                canonicalTypeName(source, owner),
                call.nameAsString,
                call.arguments.size
            )
        }

        private fun containsLegacyType(type: Type): Boolean =
            (type as? ClassOrInterfaceType)?.let { it in legacyTypeNodes } == true ||
                type.findAll(ClassOrInterfaceType::class.java).any { it in legacyTypeNodes }

        private fun resolvesLegacyBinding(name: String, use: Node): Boolean =
            bindings.asSequence()
                .filter { it.name == name && contains(it.scope, use) }
                .filter { it.field || start(it.declaration) <= start(use) }
                .sortedWith(
                    compareBy<Binding> { span(it.scope) }
                        .thenByDescending { start(it.declaration) }
                )
                .firstOrNull()
                ?.legacy == true

        private fun bindingScope(node: Node): Node? {
            var parent = node.parentNode.orElse(null)
            while (parent != null) {
                if (parent is BlockStmt ||
                    parent is MethodDeclaration ||
                    parent is ConstructorDeclaration ||
                    parent is com.github.javaparser.ast.expr.LambdaExpr ||
                    parent is ForStmt ||
                    parent is ForEachStmt ||
                    parent is CatchClause ||
                    parent is TypeDeclaration<*>
                ) return parent
                parent = parent.parentNode.orElse(null)
            }
            return null
        }
    }

    private fun gateUnsupportedTypeShapes(
        source: SourceUnit,
        plantableTypes: List<ClassOrInterfaceType>,
        facts: LegacyExpressionFacts
    ) {
        val legacy = plantableTypes.toSet()
        if (plantableTypes.any { type ->
                type.findAncestor(ArrayType::class.java).isPresent ||
                    type.findAncestor(ClassOrInterfaceType::class.java).map { container ->
                        container.typeArguments.orElse(null)?.any { argument ->
                            argument === type ||
                                argument.findAll(ClassOrInterfaceType::class.java).any { it === type }
                        } == true
                    }.orElse(false)
            }
        ) {
            error(
                "IPlantable arrays or generic containers in ${source.file} require element-flow " +
                    "adaptation; source left unchanged"
            )
        }
        if (source.unit.findAll(TypePatternExpr::class.java).any { pattern ->
                (pattern.type as? ClassOrInterfaceType)?.let(legacy::contains) == true ||
                    pattern.type.findAll(ClassOrInterfaceType::class.java).any(legacy::contains)
            }
        ) {
            error("Pattern-bound IPlantable instanceof in ${source.file}; source left unchanged")
        }
        if (source.unit.findAll(ClassExpr::class.java).any {
                it.type.findAll(ClassOrInterfaceType::class.java).any(legacy::contains)
            }
        ) {
            error("IPlantable.class reflection in ${source.file} cannot preserve vanilla membership; source left unchanged")
        }
        if (source.unit.findAll(MethodReferenceExpr::class.java).any { reference ->
                reference.scope.findAll(ClassOrInterfaceType::class.java).any(legacy::contains) ||
                    reference.scope.toString() == "IPlantable" ||
                    reference.identifier in setOf("getPlant", "getPlantType") &&
                    facts.isLegacyMethodReferenceScope(reference.scope) ||
                    reference.identifier == "mayPlaceOn"
            }
        ) {
            error(
                "Legacy plant type, getter, or mayPlaceOn method reference in ${source.file} " +
                    "requires explicit lambda adaptation; source left unchanged"
            )
        }
        if (source.unit.findAll(TypeParameter::class.java).any { parameter ->
                parameter.typeBound.any { bound ->
                    bound.findAll(ClassOrInterfaceType::class.java).any(legacy::contains)
                }
            }
        ) {
            error("IPlantable generic bound in ${source.file} requires explicit adaptation; source left unchanged")
        }
    }

    private fun gateOverloadCollapse(
        source: SourceUnit,
        plantableTypes: List<ClassOrInterfaceType>,
        index: ProjectIndex
    ) {
        val legacy = plantableTypes.toSet()
        for (owner in source.unit.findAll(TypeDeclaration::class.java)) {
            val signatures = mutableSetOf<String>()
            for (method in sourceMethods(owner)) {
                val signature = method.nameAsString + "(" +
                    method.parameters.joinToString(",") {
                        normalizedParameter(it.type, legacy, source, owner, index)
                    } + ")"
                require(signatures.add(signature)) {
                    "Replacing IPlantable value types with Object collapses overload $signature " +
                        "in ${source.file}; source left unchanged"
                }
            }
            for (constructor in owner.members.filterIsInstance<ConstructorDeclaration>()) {
                val signature = "<init>(" +
                    constructor.parameters.joinToString(",") {
                        normalizedParameter(it.type, legacy, source, owner, index)
                    } + ")"
                require(signatures.add(signature)) {
                    "Replacing IPlantable value types with Object collapses constructor $signature " +
                        "in ${source.file}; source left unchanged"
                }
            }
        }
    }

    private fun gateLegacyArgumentFlows(
        source: SourceUnit,
        facts: LegacyExpressionFacts,
        index: ProjectIndex
    ) {
        for (creation in source.unit.findAll(ObjectCreationExpr::class.java)) {
            if (creation.arguments.any(facts::isLegacyPlantable)) {
                error(
                    "Passing IPlantable to constructor ${creation.type} in ${source.file} " +
                        "requires exact constructor ownership; source left unchanged"
                )
            }
        }
        for (call in source.unit.findAll(MethodCallExpr::class.java)) {
            for ((argumentIndex, argument) in call.arguments.withIndex()) {
                if (!facts.isLegacyPlantable(argument)) continue
                if (call.nameAsString == "canSustainPlant" &&
                    ((call.arguments.size == 4 && argumentIndex == 3) ||
                        (call.arguments.size == 5 && argumentIndex == 4))
                ) {
                    continue
                }
                val localDispatch = call.scope.isEmpty || call.scope.orElse(null) is ThisExpr
                if (localDispatch) {
                    val owner = call.findAncestor(ClassOrInterfaceDeclaration::class.java)
                        .orElse(null)
                    val method = owner?.let {
                        index.exactLocalMethod(
                            canonicalTypeName(source, it),
                            call.nameAsString,
                            call.arguments.size
                        )
                    }
                    if (method != null && argumentIndex in method.legacyParameterPositions) {
                        continue
                    }
                }
                error(
                    "Passing IPlantable to unproven call '${call.nameAsString}' in " +
                        "${source.file} requires an exact target contract; source left unchanged"
                )
            }
        }
    }

    private fun normalizedParameter(
        type: Type,
        legacy: Set<ClassOrInterfaceType>,
        source: SourceUnit,
        owner: TypeDeclaration<*>,
        index: ProjectIndex
    ): String {
        var text = type.toString()
        val nodes = buildList {
            (type as? ClassOrInterfaceType)?.takeIf(legacy::contains)?.let(::add)
            addAll(type.findAll(ClassOrInterfaceType::class.java).filter(legacy::contains))
        }.distinct()
        for (node in nodes) {
            text = text.replace(node.toString(), "java.lang.Object")
        }
        if (nodes.isEmpty() && type is ClassOrInterfaceType &&
            resolveTypeName(
                type.nameWithScope,
                source,
                index.types.keys,
                owner,
                index
            ) == "java.lang.Object"
        ) {
            return "java.lang.Object"
        }
        return text
    }

    private fun isLegacySoilSignature(method: MethodDeclaration, source: SourceUnit): Boolean {
        if (method.typeAsString != "boolean" || method.parameters.size != 5) return false
        val expected = listOf(BLOCK_STATE, BLOCK_GETTER, BLOCK_POS, DIRECTION)
        return method.parameters.take(4).mapIndexed { index, parameter ->
            resolvesTypeName(parameter.typeAsString, expected[index], source, parameter)
        }.all { it }
    }

    private fun containsImplicitLegacyOverride(
        source: SourceUnit,
        visibility: Visibility,
        index: ProjectIndex
    ): Boolean =
        source.unit.findAll(ClassOrInterfaceDeclaration::class.java).any { owner ->
            val sourceType = index.sourceType(owner) ?: return@any false
            index.inheritsOldVanillaPlantContract(sourceType.canonicalName) &&
                owner.methods.any {
                    isExactGetPlant(it, source) ||
                        isExactGetPlantType(it, source, visibility)
                }
        }

    private fun gateMixedSimpleOwnership(units: List<SourceUnit>) {
        val contracts = listOf(
            "IPlantable" to legacyIPlantable,
            "PlantType" to legacyPlantType
        )
        for (source in units) {
            val imports = source.unit.imports.filter { !it.isStatic }
            for ((simpleName, canonicalNames) in contracts) {
                val legacyPackages = canonicalNames.mapTo(linkedSetOf()) {
                    it.substringBeforeLast('.')
                }
                val importsLegacy = imports.any {
                    !it.isAsterisk && it.nameAsString in canonicalNames ||
                        it.isAsterisk && it.nameAsString in legacyPackages
                }
                if (!importsLegacy) continue
                val lexicalDeclarations =
                    source.unit.findAll(TypeDeclaration::class.java).filter {
                        it.nameAsString == simpleName
                    } +
                        source.unit.findAll(TypeParameter::class.java).filter {
                            it.nameAsString == simpleName
                        }
                require(lexicalDeclarations.isEmpty()) {
                    "A lexical $simpleName declaration shadows a legacy import in " +
                        "${source.file}; per-node ownership is not guessed and source is unchanged"
                }
            }
        }
    }

    private fun gateUnsupportedLegacyOwners(
        affected: List<SourceUnit>,
        visibility: Map<SourceUnit, Visibility>,
        index: ProjectIndex
    ) {
        for (source in affected) {
            val visible = visibility.getValue(source)
            if (containsUnsupportedScopedLegacyHeritage(source, index)) {
                error(
                    "Local or anonymous legacy plant heritage in ${source.file} has no stable " +
                        "canonical transform identity; source left unchanged"
                )
            }
            for (declaration in source.unit.findAll(TypeDeclaration::class.java)) {
                val sourceType = index.sourceType(declaration)
                val ownsLegacyType = source.unit.findAll(ClassOrInterfaceType::class.java).any {
                    nearestTypeOwner(it) === declaration &&
                        (isLegacyType(
                            it,
                            "IPlantable",
                            legacyIPlantable,
                            visible.iPlantable
                        ) ||
                            isLegacyType(
                                it,
                                "PlantType",
                                legacyPlantType,
                                visible.plantType
                            ))
                }
                val inheritsLegacy = sourceType?.let {
                    index.inheritsLegacyContract(it.canonicalName)
                } == true
                if (!isProjectTypeDeclaration(declaration) &&
                    (ownsLegacyType || inheritsLegacy)
                ) {
                    error(
                        "Local legacy plant type ${declaration.nameAsString} in ${source.file} " +
                            "has no stable canonical heritage identity; source left unchanged"
                    )
                }
                if (declaration is RecordDeclaration &&
                    (ownsLegacyType || inheritsLegacy)
                ) {
                    gateLegacyRecordOwner(
                        declaration,
                        source,
                        visible,
                        sourceType
                            ?: error(
                                "Cannot resolve record owner ${declaration.nameAsString} in " +
                                    "${source.file}; source left unchanged"
                            ),
                        inheritsLegacy
                    )
                } else if (declaration !is ClassOrInterfaceDeclaration &&
                    (ownsLegacyType || inheritsLegacy)
                ) {
                    error(
                        "Legacy plant contracts owned by ${declaration.javaClass.simpleName} " +
                            "${declaration.nameAsString} in ${source.file} require a dedicated " +
                            "enum transform; source left unchanged"
                    )
                }
            }
        }
    }

    private fun gateLegacyRecordOwner(
        record: RecordDeclaration,
        source: SourceUnit,
        visibility: Visibility,
        sourceType: SourceType,
        inheritsLegacy: Boolean
    ) {
        require(!inheritsLegacy) {
            "Record ${sourceType.canonicalName} inherits a legacy plant contract; record migration " +
                "does not synthesize getPlant or other default behavior; source left unchanged"
        }
        require(record.parameters.none {
                containsLegacyPlantType(it.type, visibility)
            }
        ) {
            "Record ${sourceType.canonicalName} has an IPlantable or PlantType component; changing " +
                "its generated accessor and value shape is unsupported; source left unchanged"
        }
        require(sourceMethods(record).none { method ->
                method.nameAsString == "canSustainPlant" &&
                    method.parameters.lastOrNull()?.type?.let {
                        containsLegacyPlantType(it, visibility)
                    } == true
            }
        ) {
            "Record ${sourceType.canonicalName} owns a legacy canSustainPlant contract; record " +
                "migration cannot attach block soil dispatch behavior; source left unchanged"
        }
    }

    private fun containsLegacyPlantType(
        type: Type,
        visibility: Visibility
    ): Boolean = buildList {
        (type as? ClassOrInterfaceType)?.let(::add)
        addAll(type.findAll(ClassOrInterfaceType::class.java))
    }.distinct().any {
        isLegacyType(
            it,
            "IPlantable",
            legacyIPlantable,
            visibility.iPlantable
        ) ||
            isLegacyType(
                it,
                "PlantType",
                legacyPlantType,
                visibility.plantType
            )
    }

    private fun containsUnsupportedScopedLegacyHeritage(
        source: SourceUnit,
        index: ProjectIndex
    ): Boolean {
        val projectTypes = index.types.keys
        val localHeritage = source.unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .filterNot { isProjectTypeDeclaration(it) }
            .flatMap { declaration ->
                buildList {
                    addAll(declaration.extendedTypes)
                    addAll(declaration.implementedTypes)
                }.map { declaration to it }
            }
        if (localHeritage.any { (declaration, heritage) ->
                resolvesHeritageToLegacy(
                    heritage,
                    source,
                    declaration,
                    projectTypes,
                    index
                )
            }
        ) {
            return true
        }
        return source.unit.findAll(ObjectCreationExpr::class.java)
            .filter { it.anonymousClassBody.isPresent }
            .any { creation ->
            resolvesHeritageToLegacy(
                creation.type,
                source,
                creation.findAncestor(TypeDeclaration::class.java).orElse(null),
                projectTypes,
                index
            )
        }
    }

    private fun resolvesHeritageToLegacy(
        heritage: ClassOrInterfaceType,
        source: SourceUnit,
        context: TypeDeclaration<*>?,
        projectTypes: Set<String>,
        index: ProjectIndex
    ): Boolean {
        if (isLegacyType(heritage, "IPlantable", legacyIPlantable, false)) return true
        val resolved = resolveTypeName(
            heritage.nameWithScope,
            source,
            projectTypes,
            context,
            index
        ) ?: return false
        return resolved in legacyIPlantable || index.inheritsLegacyContract(resolved)
    }

    private fun verifyCompleteHeritageIndex(index: ProjectIndex) {
        val unresolved = index.types.values.flatMap { type ->
            type.unresolvedHeritage.map { heritage ->
                "${type.canonicalName} -> $heritage"
            }
        }
        require(unresolved.isEmpty()) {
            "Legacy plant migration cannot prove the complete source heritage graph: " +
                unresolved.sorted().joinToString() +
                "; import the dependency heritage inventory before transforming sources"
        }
    }

    private fun resolveHeritageType(
        heritage: ClassOrInterfaceType,
        owner: SourceType,
        projectTypes: Set<String>,
        index: ProjectIndex
    ): String? {
        val resolved = resolveTypeName(
            heritage.nameWithScope,
            owner.source,
            projectTypes,
            owner.declaration,
            index
        ) ?: return null
        if (resolved !in projectTypes &&
            resolved !in legacyIPlantable &&
            resolved !in canonicalRuntimeHeritage &&
            !index.hasExternalType(resolved)
        ) {
            return null
        }
        return resolved
    }

    private fun unresolvedHeritageName(
        heritage: ClassOrInterfaceType,
        owner: SourceType,
        projectTypes: Set<String>,
        index: ProjectIndex
    ): String =
        resolveTypeName(
            heritage.nameWithScope,
            owner.source,
            projectTypes,
            owner.declaration,
            index
        ) ?: heritage.nameWithScope

    private fun nearestTypeOwner(node: Node): TypeDeclaration<*>? =
        node.findAncestor(TypeDeclaration::class.java).orElse(null)

    private fun isProjectTypeDeclaration(declaration: TypeDeclaration<*>): Boolean {
        val parent = declaration.parentNode.orElse(null)
        return parent is CompilationUnit || parent is TypeDeclaration<*>
    }

    private fun sourceMethods(declaration: TypeDeclaration<*>): List<MethodDeclaration> =
        declaration.members.filterIsInstance<MethodDeclaration>()

    private fun heritageInterfaces(
        declaration: TypeDeclaration<*>
    ): List<ClassOrInterfaceType> = when (declaration) {
        is ClassOrInterfaceDeclaration ->
            if (declaration.isInterface) {
                declaration.extendedTypes.toList()
            } else {
                declaration.implementedTypes.toList()
            }
        is EnumDeclaration -> declaration.implementedTypes.toList()
        is RecordDeclaration -> declaration.implementedTypes.toList()
        else -> emptyList()
    }

    private fun methodParameterIdentity(
        parameter: Parameter,
        method: MethodDeclaration,
        source: SourceUnit,
        projectTypes: Set<String>,
        owner: TypeDeclaration<*>,
        index: ProjectIndex
    ): MethodParameterIdentity {
        val resolved = resolveTypeIdentity(
            parameter.type,
            method,
            source,
            projectTypes,
            owner,
            index
        )
        val arraySuffix = if (parameter.isVarArgs) "[]" else ""
        if (resolved != null) {
            return MethodParameterIdentity(
                identity = resolved + arraySuffix,
                resolved = true
            )
        }
        val typeParameterBindings = buildList {
            val references = buildList {
                (parameter.type as? ClassOrInterfaceType)?.let(::add)
                addAll(parameter.type.findAll(ClassOrInterfaceType::class.java))
            }.distinct()
            for (reference in references) {
                if (reference.scope.isPresent ||
                    reference.typeArguments.isPresent
                ) {
                    continue
                }
                lexicalTypeParameterBinding(
                    name = reference.nameAsString,
                    method = method,
                    source = source,
                    owner = owner
                )?.let(::add)
            }
        }.distinct()
        val lexical = parameter.type.asString() + arraySuffix
        return MethodParameterIdentity(
            identity = buildString {
                append("<lexical:")
                append(lexical)
                if (typeParameterBindings.isNotEmpty()) {
                    append('|')
                    append(typeParameterBindings.joinToString(","))
                }
                append('>')
            },
            resolved = false
        )
    }

    private fun lexicalTypeParameterBinding(
        name: String,
        method: MethodDeclaration,
        source: SourceUnit,
        owner: TypeDeclaration<*>
    ): String? {
        method.typeParameters.indexOfFirst {
            it.nameAsString == name
        }.takeIf { it >= 0 }?.let { index ->
            val parameter = method.typeParameters[index]
            return "method:${method.nameAsString}#$index:" +
                lexicalTypeParameterBounds(parameter)
        }
        var declaration: TypeDeclaration<*>? = owner
        while (declaration != null) {
            val current = declaration
            val parameters = when (current) {
                is ClassOrInterfaceDeclaration -> current.typeParameters
                is RecordDeclaration -> current.typeParameters
                else -> emptyList()
            }
            parameters.indexOfFirst {
                it.nameAsString == name
            }.takeIf { it >= 0 }?.let { index ->
                return "type:${canonicalTypeName(source, current)}#$index:" +
                    lexicalTypeParameterBounds(parameters[index])
            }
            declaration = current.findAncestor(TypeDeclaration::class.java)
                .orElse(null)
        }
        return null
    }

    private fun lexicalTypeParameterBounds(parameter: TypeParameter): String =
        parameter.typeBound.joinToString("&") {
            it.asString()
        }.ifEmpty {
            "java.lang.Object"
        }

    private fun resolveTypeIdentity(
        type: Type,
        method: MethodDeclaration,
        source: SourceUnit,
        projectTypes: Set<String>,
        owner: TypeDeclaration<*>,
        index: ProjectIndex
    ): String? = when (type) {
        is PrimitiveType -> type.asString()
        is ArrayType -> resolveTypeIdentity(
            type.componentType,
            method,
            source,
            projectTypes,
            owner,
            index
        )?.plus("[]")
        is ClassOrInterfaceType -> {
            val typeParameter = if (type.scope.isEmpty) {
                lexicalTypeParameter(type.nameAsString, method, owner)
            } else {
                null
            }
            if (typeParameter != null) {
                val bound = typeParameter.typeBound.firstOrNull()
                if (bound == null) {
                    "java.lang.Object".takeIf(index::hasExternalType)
                } else {
                    resolveTypeIdentity(
                        bound,
                        method,
                        source,
                        projectTypes,
                        owner,
                        index
                    )
                }
            } else {
                resolveTypeName(
                    type.nameWithScope,
                    source,
                    projectTypes,
                    owner,
                    index
                )
            }
        }
        else -> null
    }

    private fun lexicalTypeParameter(
        name: String,
        method: MethodDeclaration,
        owner: TypeDeclaration<*>
    ): TypeParameter? {
        method.typeParameters.firstOrNull {
            it.nameAsString == name
        }?.let { return it }
        var declaration: TypeDeclaration<*>? = owner
        while (declaration != null) {
            val current = declaration
            val parameters = when (current) {
                is ClassOrInterfaceDeclaration -> current.typeParameters
                is RecordDeclaration -> current.typeParameters
                else -> emptyList()
            }
            parameters.firstOrNull { it.nameAsString == name }?.let { return it }
            declaration = current.findAncestor(TypeDeclaration::class.java)
                .orElse(null)
        }
        return null
    }

    private fun hasLexicalTypeShadow(
        source: SourceUnit,
        simpleName: String,
        context: Node?
    ): Boolean {
        if (source.unit.findAll(TypeParameter::class.java).any {
                it.nameAsString == simpleName &&
                    (context == null || contains(it.parentNode.orElse(source.unit), context))
            }
        ) {
            return true
        }
        return source.unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString == simpleName
        }
    }

    private fun isExactGetPlant(method: MethodDeclaration, source: SourceUnit): Boolean =
        method.nameAsString == "getPlant" &&
            method.parameters.size == 2 &&
            resolvesTypeName(method.typeAsString, BLOCK_STATE, source, method) &&
            resolvesTypeName(
                method.parameters[0].typeAsString,
                BLOCK_GETTER,
                source,
                method.parameters[0]
            ) &&
            resolvesTypeName(
                method.parameters[1].typeAsString,
                BLOCK_POS,
                source,
                method.parameters[1]
            )

    private fun isExactMayPlaceOn(
        method: MethodDeclaration,
        source: SourceUnit
    ): Boolean =
        method.nameAsString == "mayPlaceOn" &&
            method.typeAsString == "boolean" &&
            isExactMayPlaceOnParameters(method, source)

    private fun isExactMayPlaceOnParameters(
        method: MethodDeclaration,
        source: SourceUnit
    ): Boolean =
        method.parameters.size == 3 &&
            resolvesTypeName(
                method.parameters[0].typeAsString,
                BLOCK_STATE,
                source,
                method.parameters[0]
            ) &&
            resolvesTypeName(
                method.parameters[1].typeAsString,
                BLOCK_GETTER,
                source,
                method.parameters[1]
            ) &&
            resolvesTypeName(
                method.parameters[2].typeAsString,
                BLOCK_POS,
                source,
                method.parameters[2]
            )

    private fun isExactGetPlantType(
        method: MethodDeclaration,
        source: SourceUnit,
        visibility: Visibility
    ): Boolean =
        method.nameAsString == "getPlantType" &&
            method.parameters.size == 2 &&
            isLegacyType(
                method.type,
                "PlantType",
                legacyPlantType,
                visibility.plantType
            ) &&
            resolvesTypeName(
                method.parameters[0].typeAsString,
                BLOCK_GETTER,
                source,
                method.parameters[0]
            ) &&
            resolvesTypeName(
                method.parameters[1].typeAsString,
                BLOCK_POS,
                source,
                method.parameters[1]
            )

    private fun isContractHeritage(type: ClassOrInterfaceType): Boolean {
        val owner = type.parentNode.orElse(null) as? ClassOrInterfaceDeclaration ?: return false
        return owner.implementedTypes.any { it === type } ||
            owner.isInterface && owner.extendedTypes.any { it === type }
    }

    private fun visibility(
        source: SourceUnit,
        projectTypes: Set<String>
    ): Visibility =
        Visibility(
            iPlantable = resolvesToLegacy(
                source,
                "IPlantable",
                legacyIPlantable,
                projectTypes
            ),
            plantType = resolvesToLegacy(
                source,
                "PlantType",
                legacyPlantType,
                projectTypes
            )
        )

    private fun resolvesToLegacy(
        source: SourceUnit,
        simpleName: String,
        canonicalNames: Set<String>,
        projectTypes: Set<String>
    ): Boolean {
        val imports = source.unit.imports.filter { !it.isStatic }
        imports.firstOrNull {
            !it.isAsterisk && it.name.identifier == simpleName
        }?.let { return it.nameAsString in canonicalNames }
        val sourceDeclarations = source.unit.findAll(TypeDeclaration::class.java)
            .filter { it.nameAsString == simpleName }
        if (sourceDeclarations.any { it.parentNode.orElse(null) !is CompilationUnit } &&
            imports.any {
                it.isAsterisk && canonicalNames.any { canonical ->
                    canonical.substringBeforeLast('.') == it.nameAsString
                }
            }
        ) {
            error(
                "Lexically scoped $simpleName shadows a legacy wildcard import in " +
                    "${source.file}; per-node ownership is ambiguous and source is unchanged"
            )
        }
        if (sourceDeclarations.isNotEmpty()) {
            return false
        }
        val samePackage = if (source.packageName.isBlank()) {
            simpleName
        } else {
            "${source.packageName}.$simpleName"
        }
        if (samePackage in projectTypes) return false

        val wildcardImports = imports.filter { it.isAsterisk }
        val legacyWildcardPackages = canonicalNames.mapTo(linkedSetOf()) {
            it.substringBeforeLast('.')
        }
        if (wildcardImports.any { it.nameAsString in legacyWildcardPackages } &&
            wildcardImports.any { it.nameAsString !in legacyWildcardPackages }
        ) {
            error(
                "Cannot prove wildcard ownership of $simpleName in ${source.file}; " +
                    "legacy and nonlegacy wildcard imports coexist"
            )
        }

        val wildcardCandidates = imports.asSequence()
            .filter { it.isAsterisk }
            .map { "${it.nameAsString}.$simpleName" }
            .filter { candidate ->
                candidate in canonicalNames ||
                    candidate in projectTypes ||
                    TargetMembers.hasClass(candidate)
            }
            .toSet()
        val legacyCandidates = wildcardCandidates.filterTo(linkedSetOf()) {
            it in canonicalNames
        }
        val otherCandidates = wildcardCandidates - legacyCandidates
        require(legacyCandidates.isEmpty() || otherCandidates.isEmpty()) {
            "Ambiguous wildcard ownership of $simpleName in ${source.file}: " +
                wildcardCandidates.sorted().joinToString()
        }
        return legacyCandidates.size == 1
    }

    private fun verifyNoUnownedSimpleName(
        source: SourceUnit,
        simpleName: String,
        canonicalNames: Set<String>,
        projectTypes: Set<String>
    ) {
        val token = Regex("""(?<![\w$.])${Regex.escape(simpleName)}(?![\w$])""")
        if (!token.containsMatchIn(source.masked)) return
        if (resolvesToLegacy(source, simpleName, canonicalNames, projectTypes)) return
        val imports = source.unit.imports.filter { !it.isStatic }
        if (imports.any { !it.isAsterisk && it.name.identifier == simpleName }) return
        if (source.unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simpleName }) return
        val samePackage = if (source.packageName.isBlank()) {
            simpleName
        } else {
            "${source.packageName}.$simpleName"
        }
        if (samePackage in projectTypes) return
        error(
            "Cannot prove ownership of unresolved $simpleName in ${source.file}; " +
                "source left unchanged"
        )
    }

    private fun isLegacyType(
        type: Type,
        simpleName: String,
        canonicalNames: Set<String>,
        simpleVisible: Boolean
    ): Boolean =
        (type as? ClassOrInterfaceType)?.let {
            isLegacyType(it, simpleName, canonicalNames, simpleVisible)
        } == true ||
            type.findAll(ClassOrInterfaceType::class.java).any {
                isLegacyType(it, simpleName, canonicalNames, simpleVisible)
            }

    private fun isLegacyType(
        type: ClassOrInterfaceType,
        simpleName: String,
        canonicalNames: Set<String>,
        simpleVisible: Boolean
    ): Boolean {
        val raw = type.nameWithScope
        return raw in canonicalNames || raw == simpleName && simpleVisible
    }

    private fun collectProjectTypeNames(units: List<SourceUnit>): Set<String> =
        units.flatMap { source ->
            source.unit.findAll(TypeDeclaration::class.java)
                .filter { isProjectTypeDeclaration(it) }
                .map { canonicalTypeName(source, it) }
        }.toSet()

    private fun canonicalTypeName(
        source: SourceUnit,
        declaration: TypeDeclaration<*>
    ): String {
        val nested = generateSequence(declaration as Node?) { node ->
            node.parentNode.orElse(null)
        }.filterIsInstance<TypeDeclaration<*>>()
            .map { it.nameAsString }
            .toList()
            .asReversed()
            .joinToString(".")
        return if (source.packageName.isBlank()) nested else "${source.packageName}.$nested"
    }

    private fun resolveTypeName(
        raw: String,
        source: SourceUnit,
        projectTypes: Set<String>,
        context: TypeDeclaration<*>? = null,
        index: ProjectIndex? = null
    ): String? {
        fun known(candidate: String): Boolean =
            candidate in projectTypes ||
                candidate in canonicalRuntimeTypes ||
                candidate in legacyIPlantable ||
                candidate in legacyPlantType ||
                index?.hasExternalType(candidate) == true

        if ('.' in raw && raw.firstOrNull()?.isLowerCase() == true) {
            return raw.takeIf(::known)
        }
        var lexical = context
        while (lexical != null) {
            val owner = canonicalTypeName(source, lexical)
            val ownerPrefix = owner.substringBeforeLast('.', "")
            val candidates = buildList {
                add("$owner.$raw")
                add(if (ownerPrefix.isBlank()) raw else "$ownerPrefix.$raw")
            }
            candidates.firstOrNull(::known)?.let { return it }
            if ('.' !in raw && index != null) {
                val inherited = index.inheritedMemberTypes(
                    owner,
                    raw,
                    source.packageName
                )
                require(inherited.size <= 1) {
                    "Ambiguous inherited member type $raw in ${source.file} from $owner: " +
                        inherited.sorted().joinToString()
                }
                inherited.singleOrNull()?.let { return it }
            }
            lexical = lexical.findAncestor(TypeDeclaration::class.java)
                .orElse(null)
        }
        source.unit.imports.firstOrNull {
            !it.isStatic && !it.isAsterisk &&
                (it.name.identifier == raw || raw.startsWith("${it.name.identifier}."))
        }?.let { imported ->
            val suffix = raw.removePrefix(imported.name.identifier)
            return (imported.nameAsString + suffix).takeIf(::known)
        }
        val samePackage = if (source.packageName.isBlank()) raw else "${source.packageName}.$raw"
        if (known(samePackage)) return samePackage
        val javaLang = "java.lang.$raw"
        if (known(javaLang)) return javaLang
        val wildcardCandidates = source.unit.imports.asSequence()
            .filter { !it.isStatic && it.isAsterisk }
            .map { "${it.nameAsString}.$raw" }
            .filter(::known)
            .toSet()
        if (wildcardCandidates.size > 1) {
            error(
                "Ambiguous wildcard type $raw in ${source.file}: " +
                    wildcardCandidates.sorted().joinToString()
            )
        }
        wildcardCandidates.singleOrNull()?.let { return it }
        return null
    }

    private fun resolvesTypeName(
        raw: String,
        canonical: String,
        source: SourceUnit,
        context: Node? = null
    ): Boolean {
        if (raw == canonical) return true
        if (raw != canonical.substringAfterLast('.')) return false
        val simpleName = canonical.substringAfterLast('.')
        if (hasLexicalTypeShadow(source, simpleName, context)) {
            error(
                "Cannot prove exact signature type $simpleName in ${source.file}; " +
                    "a lexical same-simple type declaration is visible"
            )
        }
        if (source.unit.imports.any {
            !it.isStatic && !it.isAsterisk && it.nameAsString == canonical
        }) return true
        if (source.packageName == canonical.substringBeforeLast('.') &&
            source.unit.findAll(TypeDeclaration::class.java).none {
                it.nameAsString == simpleName
            }
        ) {
            return true
        }
        val wildcards = source.unit.imports.filter { !it.isStatic && it.isAsterisk }
        val canonicalPackage = canonical.substringBeforeLast('.')
        if (wildcards.any { it.nameAsString == canonicalPackage } && wildcards.size > 1) {
            error(
                "Cannot prove exact signature type $simpleName in ${source.file}; " +
                    "its canonical wildcard import coexists with another wildcard"
            )
        }
        return wildcards.size == 1 && wildcards.single().nameAsString == canonicalPackage
    }

    private fun containsLegacyToken(masked: String): Boolean =
        Regex("""(?<![\w$])(?:IPlantable|PlantType)(?![\w$])""").containsMatchIn(masked) ||
            legacyIPlantable.any(masked::contains) ||
            legacyPlantType.any(masked::contains)

    private fun expression(parser: JavaParser, source: String): Expression =
        parser.parseExpression<Expression>(source).result.orElseThrow()

    private fun start(node: Node): Long =
        node.range.map { (it.begin.line.toLong() shl 32) + it.begin.column }.orElse(-1L)

    private fun end(node: Node): Long =
        node.range.map { (it.end.line.toLong() shl 32) + it.end.column }.orElse(-1L)

    private fun span(node: Node): Long = end(node) - start(node)

    private fun contains(scope: Node, node: Node): Boolean =
        start(scope) <= start(node) && end(node) <= end(scope)
}
