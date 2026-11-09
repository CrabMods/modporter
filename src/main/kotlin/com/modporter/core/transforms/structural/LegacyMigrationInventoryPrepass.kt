package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.pipeline.PassResult
import com.modporter.core.pipeline.ProjectScopedPass
import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.mapping.TargetMembers
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Builds or replays the one sealed dependency inventory shared by legacy migrations before any
 * source-writing pass runs.
 */
internal class LegacyMigrationInventoryPrepass(
    private val detector: LegacyMigrationSurfaceDetector,
    private val inventory: LegacyMigrationInventoryDriver,
    private val sourceBinding: LegacyMigrationSourceBinding,
    private val sourcePreflight: LegacyMigrationSourcePreflight,
    private val sourcePlatform: LegacyMigrationSourcePlatformGate =
        LegacyMigrationSourcePlatformGate { true }
) : ProjectScopedPass {
    override val name = "Legacy migration dependency inventory"
    override val order = -1
    override val migratesBuildRoot = true

    private val activeOwners = ThreadLocal.withInitial {
        mutableMapOf<Path, ActiveInventory>()
    }

    constructor(buildSystemPass: BuildSystemPass) : this(
        detector = ParsedLegacyMigrationSurfaceDetector,
        inventory = ProductionLegacyMigrationInventoryDriver(buildSystemPass),
        sourceBinding = ProjectInputLegacyMigrationSourceBinding,
        sourcePreflight = ProductionLegacyMigrationSourcePreflight,
        sourcePlatform = ReviewedSourceDeclarationGate
    )

    override fun analyze(projectDir: Path): PassResult =
        prepare(projectDir, dryRun = true)

    override fun apply(projectDir: Path): PassResult =
        prepare(projectDir, dryRun = false)

    private fun prepare(projectDir: Path, dryRun: Boolean): PassResult {
        val owner = projectDir.toAbsolutePath().normalize()
        val leaf = GradleModLayout.modModule(owner).toAbsolutePath().normalize()
        val surface = try {
            detector.detect(owner, leaf)
        } catch (failure: Exception) {
            return failed("Cannot inspect legacy migration source ownership", failure)
        }
        if (!surface.requiresInventory) return PassResult(name, emptyList())
        if (!surface.requiresSealedInventory(owner, sourcePlatform)) {
            // Not a skipped transform: the established migrations still own these families.
            logger.info {
                "Sealed legacy inventory is not applicable to $owner: its build declarations " +
                    "match no reviewed source platform, so families " +
                    "${surface.legacyCandidates.sorted()} keep the established migrations"
            }
            return PassResult(name, emptyList())
        }

        if (surface.hasRerunMarker && !surface.coveragePointer) {
            return PassResult(
                name,
                emptyList(),
                errors = listOf(
                    "Legacy migration rerun markers exist without the sealed v2 coverage " +
                        "pointer; refusing to resolve or write source: ${surface.markers.sorted()}"
                )
            )
        }
        if (dryRun && !surface.coveragePointer) {
            return PassResult(
                name,
                emptyList(),
                errors = listOf(
                    "Fresh legacy dependency inventory cannot be published read-only. " +
                        "Run the apply pipeline once to create and independently verify the " +
                        "sealed v2 artifact graph before source dry-run analysis."
                )
            )
        }

        val before = try {
            ProjectInputSnapshot.capture(owner)
        } catch (failure: Exception) {
            return failed("Cannot bind legacy inventory to current project inputs", failure)
        }
        return try {
            val changes = if (surface.coveragePointer) {
                sourceBinding.verify(owner, leaf, inventory.identity)
                inventory.verifyAndPublish(owner, leaf)
                emptyList()
            } else {
                inventory.produceAndVerify(owner, leaf).changes
            }
            sourcePreflight.verify(leaf)
            val after = ProjectInputSnapshot.capture(owner)
            require(before == after) {
                "Project source/build/resource inputs changed while preparing the sealed " +
                    "legacy migration inventory"
            }
            val expectedFamilies = if (surface.legacyCandidates.isNotEmpty()) {
                surface.legacyCandidates
            } else {
                surface.completedFamilies
            }
            activeOwners.get()[owner] = ActiveInventory(
                writeBinding = !dryRun,
                expectedFamilies = expectedFamilies
            )
            PassResult(name, changes)
        } catch (failure: Exception) {
            runCatching { inventory.release(owner) }.exceptionOrNull()
                ?.let(failure::addSuppressed)
            failed("Legacy migration inventory prepass failed", failure)
        }
    }

    override fun finishProject(projectDir: Path) {
        val owner = projectDir.toAbsolutePath().normalize()
        val active = activeOwners.get().remove(owner)
        var failure: Throwable? = null
        try {
            if (active?.writeBinding == true) {
                val leaf = GradleModLayout.modModule(owner).toAbsolutePath().normalize()
                val current = detector.detect(owner, leaf)
                if (current.coveragePointer &&
                    current.legacyCandidates.isEmpty() &&
                    active.expectedFamilies.isNotEmpty() &&
                    current.completedFamilies.containsAll(active.expectedFamilies)
                ) {
                    sourceBinding.seal(owner, leaf, inventory.identity)
                }
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            try {
                inventory.release(owner)
            } catch (caught: Throwable) {
                val previous = failure
                if (previous == null) failure = caught else previous.addSuppressed(caught)
            }
            if (activeOwners.get().isEmpty()) activeOwners.remove()
        }
        failure?.let { throw it }
    }

    private fun failed(prefix: String, failure: Exception): PassResult =
        PassResult(
            name,
            emptyList(),
            errors = listOf("$prefix: ${failure.message ?: failure::class.java.name}")
        )

    private data class ActiveInventory(
        val writeBinding: Boolean,
        val expectedFamilies: Set<String>
    )
}

internal data class LegacyMigrationRuntimeIdentity(
    val sourceSha256: String,
    val jarSha256: String
)

internal data class LegacyMigrationSurfaceState(
    val legacyCandidates: Set<String>,
    val markers: Set<String>,
    val coveragePointer: Boolean
) {
    val hasRerunMarker: Boolean get() = markers.isNotEmpty()
    val completedFamilies: Set<String>
        get() = buildSet {
            if (markers.any {
                    it == "plant-runtime-request" || it == "plant-compat-source"
                }
            ) add("plant")
            if ("block-start-compat-source" in markers) add("block-start-break")
            if (markers.any {
                    it == "looting-evidence" || it == "looting-generated-source"
                }
            ) add("looting")
            if ("model-data-manager-generated-source" in markers) {
                add("model-data-manager")
            }
        }
    val requiresInventory: Boolean
        get() = legacyCandidates.isNotEmpty() || coveragePointer || hasRerunMarker

    /**
     * A sealed or replayed inventory always binds this owner; a fresh one can only be sealed for a
     * reviewed source platform.
     */
    fun requiresSealedInventory(owner: Path, gate: LegacyMigrationSourcePlatformGate): Boolean =
        requiresInventory && (coveragePointer || hasRerunMarker || gate.reviewed(owner))
}

/**
 * The producer binds the resolved source graph to a reviewed platform fingerprint, including the
 * exact hashes of the build declarations that resolved it. A source build whose declarations
 * match no reviewed source fingerprint can never seal, so the inventory-backed migrations do not
 * apply to it.
 */
internal fun interface LegacyMigrationSourcePlatformGate {
    fun reviewed(owner: Path): Boolean
}

internal object ReviewedSourceDeclarationGate : LegacyMigrationSourcePlatformGate {
    override fun reviewed(owner: Path): Boolean =
        ReviewedPlantPlatformFingerprints.all.any { fingerprint ->
            val declarations = fingerprint.expectedPlatformIdentity?.declarationFileHashes.orEmpty()
            fingerprint.phase == InventoryPhase.SOURCE &&
                declarations.isNotEmpty() &&
                declarations.all { (path, hash) ->
                    val file = owner.resolve(path)
                    file.isRegularFile() && sha256(file.readBytes()) == hash
                }
        }
}

internal fun interface LegacyMigrationSurfaceDetector {
    fun detect(owner: Path, leaf: Path): LegacyMigrationSurfaceState
}

internal interface LegacyMigrationInventoryDriver {
    val identity: LegacyMigrationRuntimeIdentity
    fun produceAndVerify(owner: Path, leaf: Path): PassResult
    fun verifyAndPublish(owner: Path, leaf: Path)
    fun release(owner: Path)
}

internal interface LegacyMigrationSourceBinding {
    fun verify(owner: Path, leaf: Path, identity: LegacyMigrationRuntimeIdentity)
    fun seal(owner: Path, leaf: Path, identity: LegacyMigrationRuntimeIdentity)
}

internal fun interface LegacyMigrationSourcePreflight {
    fun verify(leaf: Path)
}

private class ProductionLegacyMigrationInventoryDriver(
    private val buildSystemPass: BuildSystemPass
) : LegacyMigrationInventoryDriver {
    private val runtime by lazy { CanonicalRuntimeIdentity.fromProductionResources() }
    private val scanner by lazy {
        Jvm21LegacyPlantBytecodeScanner(ReviewedPlantPlatformFingerprints.all)
    }

    override val identity: LegacyMigrationRuntimeIdentity
        get() = LegacyMigrationRuntimeIdentity(runtime.sourceSha256, runtime.jarSha256)

    override fun produceAndVerify(owner: Path, leaf: Path): PassResult {
        val repositoryRoot = productionRepositoryRoot()
        val initScript =
            repositoryRoot.resolve("src/main/resources/legacy-plant-resolve.init.gradle")
        val resolver = ManagedLegacyPlantGradleResolver(
            repositoryRoot = repositoryRoot,
            initScript = initScript,
            runtimeJarSha256 = runtime.jarSha256
        )
        val planner = BuildSystemLegacyPlantTargetPlanner(
            buildSystemPass = BuildSystemPass(
                offlineMode = true,
                mappingsPrefix = buildSystemPass.mappingsPrefix
            ),
            runtimeIdentity = runtime,
            adapterRegistrations = emptyList()
        )
        return try {
            val result = LegacyPlantDependencyInventoryProducer.produce(
                ownerProject = owner,
                leafProject = leaf,
                resolver = resolver,
                scanner = scanner,
                buildPlanner = planner,
                runtimeIdentity = runtime,
                dryRun = false
            )
            require(result.errors.isEmpty()) {
                "Legacy dependency inventory producer reported errors: " +
                    result.errors.joinToString("; ")
            }
            LegacyMigrationArtifactInventoryHandoff.release(owner)
            LegacyPlantCoverageV2Verifier.verifyAndPublish(
                ownerProject = owner,
                explicitLeafProject = leaf,
                runtimeIdentity = runtime,
                scanner = scanner
            )
            result
        } catch (failure: Throwable) {
            runCatching { LegacyMigrationArtifactInventoryHandoff.release(owner) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun verifyAndPublish(owner: Path, leaf: Path) {
        try {
            LegacyMigrationArtifactInventoryHandoff.release(owner)
            LegacyPlantCoverageV2Verifier.verifyAndPublish(
                ownerProject = owner,
                explicitLeafProject = leaf,
                runtimeIdentity = runtime,
                scanner = scanner
            )
        } catch (failure: Throwable) {
            runCatching { LegacyMigrationArtifactInventoryHandoff.release(owner) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun release(owner: Path) {
        LegacyMigrationArtifactInventoryHandoff.release(owner)
    }

    private fun productionRepositoryRoot(): Path {
        val explicit = System.getProperty("modporter.repositoryRoot")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("MODPORTER_REPOSITORY_ROOT")?.takeIf(String::isNotBlank)
        val root = (explicit?.let { Path.of(it) } ?: Path.of(""))
            .toAbsolutePath()
            .normalize()
        require(root.resolve("mod-build.ps1").isRegularFile()) {
            "The exact ModPorter repository root is required for managed legacy inventory " +
                "resolution; set -Dmodporter.repositoryRoot or MODPORTER_REPOSITORY_ROOT: $root"
        }
        require(
            root.resolve("src/main/resources/legacy-plant-resolve.init.gradle").isRegularFile()
        ) {
            "Managed legacy inventory init script is missing from repository root $root"
        }
        return root
    }
}

private object ProductionLegacyMigrationSourcePreflight : LegacyMigrationSourcePreflight {
    override fun verify(leaf: Path) {
        val pass = StructuralRefactorPass()
        LegacyModelDataManagerCompatMigration.migrate(pass, leaf, dryRun = true)
        LegacyPlantablesRuntimeMigration.migrate(pass, leaf, dryRun = true)
        LegacyBlockStartBreakCompatMigration.migrate(pass, leaf, dryRun = true)
        LegacyLootingPolicyMigration.migrate(pass, leaf, dryRun = true)
    }
}

private object ProjectInputLegacyMigrationSourceBinding : LegacyMigrationSourceBinding {
    private const val RELATIVE =
        ".modporter/legacy-migration-source-binding.properties"
    private const val POINTER =
        ".modporter/legacy-plant-external-coverage.properties"

    override fun verify(
        owner: Path,
        leaf: Path,
        identity: LegacyMigrationRuntimeIdentity
    ) {
        val binding = owner.resolve(RELATIVE)
        require(binding.isRegularFile() && !Files.isSymbolicLink(binding)) {
            "Sealed legacy inventory has no exact current-source binding: $binding"
        }
        val lines = binding.readText().replace("\r\n", "\n").lineSequence()
            .filter(String::isNotBlank)
            .toList()
        require(lines.lastOrNull()?.startsWith("bindingSha256=") == true) {
            "Legacy source binding is missing its terminal digest"
        }
        val body = lines.dropLast(1).joinToString("\n", postfix = "\n")
        require(lines.last() == "bindingSha256=${sha256(body.toByteArray())}") {
            "Legacy source binding digest changed"
        }
        val values = lines.associate { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed legacy source binding line" }
            line.substring(0, separator) to line.substring(separator + 1)
        }
        require(values.size == lines.size) { "Duplicate legacy source binding keys" }
        require(values["schema"] == "legacy-migration-source-binding-v1")
        require(values["runtimeSourceSha256"] == identity.sourceSha256)
        require(values["runtimeJarSha256"] == identity.jarSha256)
        require(values["leaf"] == encode(owner.relativize(leaf).toString().replace('\\', '/')))
        val pointer = owner.resolve(POINTER)
        require(pointer.isRegularFile() && !Files.isSymbolicLink(pointer))
        require(values["inventoryPointerSha256"] == fileSha256(pointer))
        val snapshot = ProjectInputSnapshot.capture(owner)
        require(values["snapshotSha256"] == snapshot.digest) {
            "Current project source/build/resource inputs do not match the sealed legacy " +
                "inventory binding"
        }
        require(values["entryCount"] == snapshot.entries.size.toString())
        snapshot.entries.forEachIndexed { index, entry ->
            require(values["entry.$index"] == entry.canonicalText()) {
                "Current project input changed since legacy inventory binding: ${entry.path}"
            }
        }
        val expectedKeys = buildSet {
            addAll(
                setOf(
                    "schema",
                    "runtimeSourceSha256",
                    "runtimeJarSha256",
                    "leaf",
                    "inventoryPointerSha256",
                    "snapshotSha256",
                    "entryCount",
                    "bindingSha256"
                )
            )
            snapshot.entries.indices.forEach { add("entry.$it") }
        }
        require(values.keys == expectedKeys) { "Legacy source binding has unknown or missing keys" }
    }

    override fun seal(
        owner: Path,
        leaf: Path,
        identity: LegacyMigrationRuntimeIdentity
    ) {
        val pointer = owner.resolve(POINTER)
        require(pointer.isRegularFile() && !Files.isSymbolicLink(pointer)) {
            "Cannot seal a source binding without the verified v2 inventory pointer"
        }
        val snapshot = ProjectInputSnapshot.capture(owner)
        val body = buildString {
            appendLine("schema=legacy-migration-source-binding-v1")
            appendLine("runtimeSourceSha256=${identity.sourceSha256}")
            appendLine("runtimeJarSha256=${identity.jarSha256}")
            appendLine("leaf=${encode(owner.relativize(leaf).toString().replace('\\', '/'))}")
            appendLine("inventoryPointerSha256=${fileSha256(pointer)}")
            appendLine("snapshotSha256=${snapshot.digest}")
            appendLine("entryCount=${snapshot.entries.size}")
            snapshot.entries.forEachIndexed { index, entry ->
                appendLine("entry.$index=${entry.canonicalText()}")
            }
        }
        val content = body + "bindingSha256=${sha256(body.toByteArray())}\n"
        val target = owner.resolve(RELATIVE)
        target.parent.createDirectories()
        val temporary = target.parent.resolve(".${target.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.writeString(
                temporary,
                content,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

private data class ProjectInputSnapshot(
    val entries: List<ProjectInputEntry>,
    val digest: String
) {
    companion object {
        private val excludedDirectories = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".modporter",
            "build",
            "logs",
            "out",
            "run"
        )

        fun capture(owner: Path): ProjectInputSnapshot {
            val root = owner.toAbsolutePath().normalize()
            require(root.isDirectory()) { "Project root does not exist: $root" }
            val entries = mutableListOf<ProjectInputEntry>()
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult {
                    require(!attrs.isSymbolicLink) {
                        "Project input directory is a symbolic link: $dir"
                    }
                    if (dir != root) {
                        val segments = root.relativize(dir).map { it.toString() }
                        val name = dir.fileName.toString()
                        val alwaysExcluded = name in setOf(
                            ".git",
                            ".gradle",
                            ".idea",
                            ".modporter"
                        )
                        val generatedDirectory =
                            name in excludedDirectories && "src" !in segments
                        if (alwaysExcluded || generatedDirectory) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult {
                    require(attrs.isRegularFile && !attrs.isSymbolicLink) {
                        "Project input is not a regular owned file: $file"
                    }
                    val relative = root.relativize(file).toString().replace('\\', '/')
                    entries += ProjectInputEntry(
                        path = relative,
                        size = attrs.size(),
                        sha256 = fileSha256(file)
                    )
                    return FileVisitResult.CONTINUE
                }
            })
            val sorted = entries.sortedBy(ProjectInputEntry::path)
            val canonical = sorted.joinToString("\n", postfix = "\n") {
                it.canonicalText()
            }
            return ProjectInputSnapshot(sorted, sha256(canonical.toByteArray()))
        }
    }
}

private data class ProjectInputEntry(
    val path: String,
    val size: Long,
    val sha256: String
) {
    fun canonicalText(): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.toByteArray(StandardCharsets.UTF_8))
        return "$encoded|$size|$sha256"
    }
}

internal object ParsedLegacyMigrationSurfaceDetector : LegacyMigrationSurfaceDetector {
    private val plantTypes = mapOf(
        "IPlantable" to setOf(
            "net.minecraftforge.common.IPlantable",
            "net.neoforged.neoforge.common.IPlantable"
        ),
        "PlantType" to setOf(
            "net.minecraftforge.common.PlantType",
            "net.neoforged.neoforge.common.PlantType"
        )
    )
    private val lootingTypes = mapOf(
        "LootingLevelEvent" to setOf(
            "net.minecraftforge.event.entity.living.LootingLevelEvent",
            "net.neoforged.neoforge.event.entity.living.LootingLevelEvent"
        ),
        "LootingEnchantFunction" to setOf(
            "net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction"
        )
    )
    private val oldVanillaPlantBases = setOf(
        "net.minecraft.world.level.block.AttachedStemBlock",
        "net.minecraft.world.level.block.AzaleaBlock",
        "net.minecraft.world.level.block.BambooStalkBlock",
        "net.minecraft.world.level.block.BushBlock",
        "net.minecraft.world.level.block.CactusBlock",
        "net.minecraft.world.level.block.CropBlock",
        "net.minecraft.world.level.block.DeadBushBlock",
        "net.minecraft.world.level.block.FungusBlock",
        "net.minecraft.world.level.block.MangrovePropaguleBlock",
        "net.minecraft.world.level.block.MushroomBlock",
        "net.minecraft.world.level.block.NetherSproutsBlock",
        "net.minecraft.world.level.block.NetherWartBlock",
        "net.minecraft.world.level.block.PitcherCropBlock",
        "net.minecraft.world.level.block.PumpkinBlock",
        "net.minecraft.world.level.block.RootsBlock",
        "net.minecraft.world.level.block.SeaPickleBlock",
        "net.minecraft.world.level.block.SeagrassBlock",
        "net.minecraft.world.level.block.SmallDripleafBlock",
        "net.minecraft.world.level.block.StemBlock",
        "net.minecraft.world.level.block.SugarCaneBlock",
        "net.minecraft.world.level.block.TallSeagrassBlock",
        "net.minecraft.world.level.block.WaterlilyBlock",
        "net.minecraft.world.level.block.WitherRoseBlock"
    )
    private const val ITEM = "net.minecraft.world.item.Item"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val BLOCK_POS = "net.minecraft.core.BlockPos"
    private const val BLOCK_AND_TINT_GETTER =
        "net.minecraft.world.level.BlockAndTintGetter"
    private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
    private const val DIRECTION = "net.minecraft.core.Direction"
    private const val PLAYER = "net.minecraft.world.entity.player.Player"
    private const val SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer"
    private const val CREATE_COPYCAT_PACKAGE =
        "com.simibubi.create.content.decoration.copycat"
    private const val FILTERED_BLOCK_AND_TINT_GETTER =
        "$CREATE_COPYCAT_PACKAGE.FilteredBlockAndTintGetter"
    private const val COPYCAT_BLOCK = "$CREATE_COPYCAT_PACKAGE.CopycatBlock"
    private const val OLD_MODEL_DATA_MANAGER =
        "net.minecraftforge.client.model.data.ModelDataManager"
    private const val TARGET_MODEL_DATA_MANAGER =
        "net.neoforged.neoforge.client.model.data.ModelDataManager"
    private const val MODEL_DATA_MANAGER_GETTER = "getModelDataManager"
    private const val MODEL_DATA_MANAGER_MARKER =
        "modporter-legacy-model-data-manager-compat-v1"
    private val knownPlatformTypes = (
        plantTypes.values.flatten() +
            lootingTypes.values.flatten() +
            oldVanillaPlantBases +
            setOf(
                ITEM,
                ITEM_STACK,
                BLOCK_POS,
                BLOCK_AND_TINT_GETTER,
                BLOCK_STATE,
                DIRECTION,
                PLAYER,
                SERVER_PLAYER,
                OLD_MODEL_DATA_MANAGER,
                TARGET_MODEL_DATA_MANAGER
            )
        ).groupBy { it.substringAfterLast('.') }

    override fun detect(owner: Path, leaf: Path): LegacyMigrationSurfaceState {
        val normalizedOwner = owner.toAbsolutePath().normalize()
        val normalizedLeaf = leaf.toAbsolutePath().normalize()
        require(normalizedLeaf.startsWith(normalizedOwner)) {
            "Legacy migration leaf is outside its owner: $normalizedLeaf"
        }
        val sources = javaSources(normalizedLeaf)
        val units = sources.units
        val index = JavaSourceIndex(units)
        val candidates = mutableSetOf<String>()
        if (units.any { hasExactRemovedType(it, index, plantTypes) } ||
            index.hasOwnedPlantOverride()
        ) {
            candidates += "plant"
        }
        if (index.hasOwnedBlockStartBreak()) candidates += "block-start-break"
        if (units.any { hasExactRemovedType(it, index, lootingTypes) }) {
            candidates += "looting"
        }
        if (index.hasOwnedModelDataManagerSurface()) {
            candidates += "model-data-manager"
        }

        val pointer = normalizedOwner.resolve(
            ".modporter/legacy-plant-external-coverage.properties"
        ).isRegularFile()
        val markers = mutableSetOf<String>()
        listOf(normalizedOwner, normalizedLeaf).distinct().forEach { project ->
            if (project.resolve(".modporter/legacy-plant-compat.required").exists()) {
                markers += "plant-runtime-request"
            }
            if (project.resolve(".modporter/evidence/legacy-looting-migration.json").exists()) {
                markers += "looting-evidence"
            }
        }
        sources.files.forEach { source ->
            val relative = normalizedLeaf.relativize(source.file).toString().replace('\\', '/')
            when {
                relative.contains("/com/modporter/compat/plant/v1/") ->
                    markers += "plant-compat-source"
                relative.contains("/com/modporter/compat/item/v1/") ->
                    markers += "block-start-compat-source"
                source.masked.contains("ModporterLegacyLootingPolicy") ->
                    markers += "looting-generated-source"
            }
        }
        if (hasModelDataManagerGeneratedMarker(normalizedLeaf)) {
            markers += "model-data-manager-generated-source"
        }
        if (pointer) markers += "sealed-v2-coverage"
        if (sources.failures.isNotEmpty() &&
            (
                candidates.isNotEmpty() ||
                    markers.isNotEmpty() ||
                    sources.failures.any { hasLegacyParseFailureHint(it.source) }
                )
        ) {
            throw IllegalArgumentException(
                sources.failures.joinToString(
                    prefix = "Cannot inspect legacy source ownership:\n",
                    separator = "\n"
                ) { failure ->
                    "${failure.source.file}: ${failure.problems}"
                }
            )
        }
        return LegacyMigrationSurfaceState(candidates, markers, pointer)
    }

    private fun javaSources(leaf: Path): JavaSourceScan {
        val src = leaf.resolve("src/main/java")
        if (!src.isDirectory()) return JavaSourceScan(emptyList(), emptyList(), emptyList())
        val structural = StructuralRefactorPass()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        return Files.walk(src).use { paths ->
            val files = paths.filter {
                it.isRegularFile() && it.fileName.toString().endsWith(".java")
            }
                .filter { file ->
                    val relative = leaf.relativize(file).toString().replace('\\', '/')
                    !relative.contains("/build/") &&
                        !relative.contains("/src/references/") &&
                        !relative.contains("/com/modporter/generated/")
                }
                .sorted()
                .map { file ->
                    val source = file.readText()
                    JavaSourceText(
                        file = file,
                        source = source,
                        masked = structural.maskJavaCommentsAndLiterals(source)
                    )
                }
                .toList()
            val units = mutableListOf<JavaSourceUnit>()
            val failures = mutableListOf<JavaSourceParseFailure>()
            files.forEach { source ->
                val result = parser.parse(source.source)
                if (result.isSuccessful && result.result.isPresent) {
                    units += JavaSourceUnit(
                        file = source.file,
                        source = source.source,
                        masked = source.masked,
                        unit = result.result.orElseThrow()
                    )
                } else {
                    failures += JavaSourceParseFailure(
                        source = source,
                        problems = result.problems.joinToString("; ")
                    )
                }
            }
            JavaSourceScan(files, units, failures)
        }
    }

    private fun hasLegacyParseFailureHint(source: JavaSourceText): Boolean {
        val masked = source.masked
        val removedTypes = plantTypes.values.flatten() + lootingTypes.values.flatten()
        if (removedTypes.any(masked::contains)) return true

        val wildcardRemovedType = (plantTypes + lootingTypes).any { (simple, canonical) ->
            Regex("""(?<![\w$])${Regex.escape(simple)}(?![\w$])""")
                .containsMatchIn(masked) &&
                canonical.any { type ->
                    Regex(
                        """(?m)^\s*import\s+${Regex.escape(type.substringBeforeLast('.'))}""" +
                            """\s*\.\s*\*\s*;"""
                    ).containsMatchIn(masked)
                }
        }
        if (wildcardRemovedType) return true

        val plantOverrideHint = Regex(
            """(?s)@(?:java\.lang\.)?Override\b.*?""" +
                """\b(?:getPlant|getPlantType|mayPlaceOn)\s*\("""
        ).containsMatchIn(masked)
        val blockStartHint = Regex(
            """(?<![\w$])onBlockStartBreak\s*\("""
        ).containsMatchIn(masked)
        val modelDataManagerHint = Regex(
            """(?<![\w$])${Regex.escape(MODEL_DATA_MANAGER_GETTER)}\s*\("""
        ).containsMatchIn(masked) &&
            Regex(
                """(?m)^\s*package\s+${Regex.escape(CREATE_COPYCAT_PACKAGE)}\s*;"""
            ).containsMatchIn(masked) &&
            Regex(
                """(?<![\w$])(?:class|interface)\s+""" +
                    """(?:FilteredBlockAndTintGetter|CopycatBlock)(?![\w$])"""
            ).containsMatchIn(masked)
        return plantOverrideHint || blockStartHint || modelDataManagerHint
    }

    private fun hasModelDataManagerGeneratedMarker(leaf: Path): Boolean {
        val generated = leaf.resolve("src/main/java/com/modporter/generated")
        if (!generated.isDirectory()) return false
        return Files.walk(generated).use { paths ->
            paths.filter {
                it.isRegularFile() &&
                    it.fileName.toString() == "LegacyModelDataManagers.java"
            }.anyMatch { file ->
                val relative = generated.relativize(file)
                relative.nameCount >= 2 &&
                    relative.getName(relative.nameCount - 2).toString() == "compat" &&
                    file.readText().contains(MODEL_DATA_MANAGER_MARKER)
            }
        }
    }

    private fun hasExactRemovedType(
        source: JavaSourceUnit,
        index: JavaSourceIndex,
        removed: Map<String, Set<String>>
    ): Boolean = removed.any { (simple, canonical) ->
        if (!Regex("""(?<![\w$])${Regex.escape(simple)}(?![\w$])""")
                .containsMatchIn(source.masked) &&
            canonical.none(source.masked::contains)
        ) {
            return@any false
        }
        canonical.any(source.masked::contains) ||
            index.resolve(source, simple) in canonical ||
            source.unit.findAll(ClassOrInterfaceType::class.java).any {
            index.resolve(source, it.nameWithScope) in canonical
        }
    }

    private class JavaSourceIndex(private val units: List<JavaSourceUnit>) {
        private val declaredTypes = buildMap {
            units.forEach { source ->
                source.unit.findAll(TypeDeclaration::class.java).forEach { declaration ->
                    val canonical = canonicalType(source, declaration) ?: return@forEach
                    put(canonical, declaration to source)
                }
            }
        }
        private val parents = buildMap {
            declaredTypes.forEach { (canonical, pair) ->
                val declaration = pair.first as? ClassOrInterfaceDeclaration
                    ?: return@forEach
                val parent = declaration.extendedTypes.firstOrNull()?.nameWithScope
                    ?: return@forEach
                put(canonical, resolve(pair.second, parent) ?: parent)
            }
        }

        fun resolve(source: JavaSourceUnit, raw: String): String? {
            if (raw.contains('.')) return raw
            if (source.unit.findAll(TypeDeclaration::class.java).any {
                    it.nameAsString == raw
                }
            ) return null
            val explicit = source.unit.imports.filter {
                !it.isStatic && !it.isAsterisk && it.name.identifier == raw
            }
            if (explicit.isNotEmpty()) return explicit.singleOrNull()?.nameAsString
            val samePackage = if (source.packageName.isBlank()) raw
            else "${source.packageName}.$raw"
            if (samePackage in declaredTypes) return samePackage
            val wildcardCandidates = source.unit.imports.filter {
                !it.isStatic && it.isAsterisk
            }.map { "${it.nameAsString}.$raw" }
            val knownCandidates = knownPlatformTypes[raw].orEmpty()
                .filter(wildcardCandidates::contains)
            return knownCandidates.singleOrNull() ?: wildcardCandidates.singleOrNull()
        }

        fun hasOwnedPlantOverride(): Boolean =
            declaredTypes.any { (canonical, pair) ->
                val owner = pair.first as? ClassOrInterfaceDeclaration
                    ?: return@any false
                val relevant = owner.methods.filter(::isLegacyPlantOverrideShape)
                relevant.isNotEmpty() &&
                    (
                        inherits(canonical, oldVanillaPlantBases) ||
                            (
                                unresolvedExternalParent(canonical) &&
                                    relevant.any { method ->
                                        method.annotations.any {
                                            it.nameAsString.substringAfterLast('.') == "Override"
                                        }
                                    }
                                )
                        )
            }

        fun hasOwnedBlockStartBreak(): Boolean {
            for ((canonical, pair) in declaredTypes) {
                val owner = pair.first as? ClassOrInterfaceDeclaration ?: continue
                if (!inherits(canonical, setOf(ITEM))) continue
                if (owner.methods.any { method ->
                        method.nameAsString == "onBlockStartBreak" &&
                            method.typeAsString == "boolean" &&
                            method.parameters.size == 3 &&
                            resolve(pair.second, method.parameters[0].typeAsString) == ITEM_STACK &&
                            resolve(pair.second, method.parameters[1].typeAsString) == BLOCK_POS &&
                            resolve(pair.second, method.parameters[2].typeAsString) == PLAYER
                    }
                ) return true
            }
            return units.any(::hasOwnedBlockStartCall)
        }

        fun hasOwnedModelDataManagerSurface(): Boolean =
            hasExactFilteredManagerOverride() || hasExactCopycatManagerQuery()

        private fun hasExactFilteredManagerOverride(): Boolean {
            val pair = declaredTypes[FILTERED_BLOCK_AND_TINT_GETTER] ?: return false
            val owner = pair.first as? ClassOrInterfaceDeclaration ?: return false
            val source = pair.second
            val implementsBlockGetter = owner.implementedTypes.any {
                resolve(source, it.nameWithScope) == BLOCK_AND_TINT_GETTER
            }
            val hasWrappedGetter = owner.fields.flatMap { it.variables }.any {
                it.nameAsString == "wrapped" &&
                    resolve(source, it.typeAsString) == BLOCK_AND_TINT_GETTER
            }
            if (!implementsBlockGetter || !hasWrappedGetter) return false
            return owner.methods.any { method ->
                if (method.nameAsString != MODEL_DATA_MANAGER_GETTER ||
                    method.parameters.isNotEmpty() ||
                    !method.isPublic ||
                    method.isStatic ||
                    method.annotations.none {
                        it.nameAsString.substringAfterLast('.') == "Override"
                    } ||
                    resolve(source, method.typeAsString) !in
                    setOf(OLD_MODEL_DATA_MANAGER, TARGET_MODEL_DATA_MANAGER) ||
                    method.findAll(
                        com.github.javaparser.ast.expr.AnnotationExpr::class.java
                    ).none {
                        it.nameAsString.substringAfterLast('.') == "Nullable"
                    }
                ) {
                    return@any false
                }
                val returned = method.body.orElse(null)?.statements?.singleOrNull()
                    ?.let { it as? com.github.javaparser.ast.stmt.ReturnStmt }
                    ?.expression?.orElse(null) as? MethodCallExpr
                    ?: return@any false
                if (returned.nameAsString != MODEL_DATA_MANAGER_GETTER ||
                    returned.arguments.isNotEmpty()
                ) {
                    return@any false
                }
                when (val scope = returned.scope.orElse(null)) {
                    is NameExpr -> scope.nameAsString == "wrapped"
                    is com.github.javaparser.ast.expr.FieldAccessExpr ->
                        scope.scope.toString() == "this" &&
                            scope.nameAsString == "wrapped"
                    else -> false
                }
            }
        }

        private fun hasExactCopycatManagerQuery(): Boolean {
            val pair = declaredTypes[COPYCAT_BLOCK] ?: return false
            val owner = pair.first as? ClassOrInterfaceDeclaration ?: return false
            val source = pair.second
            return owner.methods.any { method ->
                if (method.nameAsString != "getAppearance" ||
                    !method.isPublic ||
                    method.annotations.none {
                        it.nameAsString.substringAfterLast('.') == "Override"
                    } ||
                    resolve(source, method.typeAsString) != BLOCK_STATE ||
                    method.parameters.map {
                        resolve(source, it.typeAsString)
                    } != listOf(
                        BLOCK_STATE,
                        BLOCK_AND_TINT_GETTER,
                        BLOCK_POS,
                        DIRECTION,
                        BLOCK_STATE,
                        BLOCK_POS
                    )
                ) {
                    return@any false
                }
                val receiver = method.parameters[1].nameAsString
                method.findAll(MethodCallExpr::class.java).any { call ->
                    call.nameAsString == MODEL_DATA_MANAGER_GETTER &&
                        call.arguments.isEmpty() &&
                        (call.scope.orElse(null) as? NameExpr)
                            ?.nameAsString == receiver
                }
            }
        }

        private fun hasOwnedBlockStartCall(source: JavaSourceUnit): Boolean {
            val values = source.unit.findAll(VariableDeclarator::class.java).mapNotNull {
                resolve(source, it.typeAsString)?.let { type -> it.nameAsString to type }
            }.toMap() + source.unit.findAll(com.github.javaparser.ast.body.Parameter::class.java)
                .mapNotNull {
                    resolve(source, it.typeAsString)?.let { type -> it.nameAsString to type }
                }
            fun expressionType(expression: com.github.javaparser.ast.expr.Expression): String? =
                when (expression) {
                    is NameExpr -> values[expression.nameAsString]
                    is MethodCallExpr -> {
                        if (expression.scope.isEmpty) {
                            null
                        } else {
                            val receiver = expressionType(expression.scope.get())
                            when {
                                expression.nameAsString == "getItem" &&
                                    receiver == ITEM_STACK -> ITEM
                                expression.nameAsString in
                                    setOf("getMainHandItem", "getOffhandItem") &&
                                    isPlatformSubtype(receiver, PLAYER) -> ITEM_STACK
                                else -> null
                            }
                        }
                    }
                    else -> null
                }
            return source.unit.findAll(MethodCallExpr::class.java).any { call ->
                if (call.nameAsString != "onBlockStartBreak" || call.scope.isEmpty) {
                    return@any false
                }
                val type = expressionType(call.scope.get())
                (type == ITEM_STACK && call.arguments.size == 2) ||
                    (type == ITEM && call.arguments.size == 3)
            }
        }

        private fun isLegacyPlantOverrideShape(
            method: com.github.javaparser.ast.body.MethodDeclaration
        ): Boolean = when (method.nameAsString) {
            "getPlant", "getPlantType" -> method.parameters.size == 2
            "mayPlaceOn" -> method.parameters.size == 3 &&
                method.typeAsString == "boolean"
            else -> false
        }

        private fun unresolvedExternalParent(type: String): Boolean {
            val parent = parents[type] ?: return false
            return parent !in declaredTypes && !TargetMembers.hasClass(parent)
        }

        private fun isPlatformSubtype(type: String?, target: String): Boolean =
            type != null && (
                type == target ||
                    (type == SERVER_PLAYER && target == PLAYER) ||
                    (
                        TargetMembers.hasClass(type) &&
                            TargetMembers.inheritsFrom(type, target)
                        )
                )

        private fun inherits(type: String, targets: Set<String>): Boolean {
            var current: String? = type
            val seen = mutableSetOf<String>()
            while (current != null && seen.add(current)) {
                val currentName = current
                if (currentName in targets) return true
                val sourceParent = parents[currentName]
                if (sourceParent != null) {
                    current = sourceParent
                    continue
                }
                if (TargetMembers.hasClass(currentName) &&
                    targets.any { target ->
                        currentName == target ||
                            TargetMembers.inheritsFrom(currentName, target)
                    }
                ) return true
                return false
            }
            return false
        }

        private fun canonicalType(
            source: JavaSourceUnit,
            declaration: TypeDeclaration<*>
        ): String? {
            val names = mutableListOf(declaration.nameAsString)
            var parent = declaration.parentNode.orElse(null)
            while (parent != null) {
                if (parent is TypeDeclaration<*>) names += parent.nameAsString
                parent = parent.parentNode.orElse(null)
            }
            names.reverse()
            val local = names.joinToString(".")
            return if (source.packageName.isBlank()) local else "${source.packageName}.$local"
        }
    }

    private data class JavaSourceUnit(
        val file: Path,
        val source: String,
        val masked: String,
        val unit: CompilationUnit
    ) {
        val packageName: String =
            unit.packageDeclaration.map { it.nameAsString }.orElse("")
    }

    private data class JavaSourceText(
        val file: Path,
        val source: String,
        val masked: String
    )

    private data class JavaSourceParseFailure(
        val source: JavaSourceText,
        val problems: String
    )

    private data class JavaSourceScan(
        val files: List<JavaSourceText>,
        val units: List<JavaSourceUnit>,
        val failures: List<JavaSourceParseFailure>
    )

}

private fun fileSha256(path: Path): String = sha256(path.readBytes())

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
