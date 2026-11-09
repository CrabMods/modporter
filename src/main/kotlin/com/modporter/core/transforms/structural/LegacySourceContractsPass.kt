package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.PassResult
import com.modporter.core.pipeline.ProjectScopedPass
import java.nio.file.Path

/**
 * Applies source contracts that must still see Forge 1.20.1 names before text and AST renames.
 */
internal class LegacySourceContractsPass(
    private val coordinator: LegacySourceContractCoordinator =
        ProductionLegacySourceContractCoordinator
) : ProjectScopedPass {
    override val name = "Legacy source contracts"
    override val order = 0

    override fun analyze(projectDir: Path): PassResult =
        process(projectDir, dryRun = true)

    override fun apply(projectDir: Path): PassResult =
        process(projectDir, dryRun = false)

    private fun process(projectDir: Path, dryRun: Boolean): PassResult {
        val project = projectDir.toAbsolutePath().normalize()
        return try {
            if (!coordinator.hasPublishedInventory(project)) {
                require(!coordinator.hasInventoryRequest(project)) {
                    "Legacy source contracts were requested without a published sealed inventory"
                }
                return PassResult(name, emptyList())
            }

            val plans = coordinator.preflight(project)
            val changes = if (dryRun) {
                plans.reportedChanges
            } else {
                coordinator.apply(project, plans)
            }
            coordinator.mark(
                project,
                if (dryRun) {
                    LegacySourceContractMode.ANALYZED
                } else {
                    LegacySourceContractMode.APPLIED
                }
            )
            PassResult(name, changes)
        } catch (failure: Exception) {
            PassResult(
                name,
                emptyList(),
                errors = listOf(
                    "Legacy source contract migration failed: " +
                        (failure.message ?: failure::class.java.name)
                )
            )
        }
    }

    override fun finishProject(projectDir: Path) {
        coordinator.release(projectDir.toAbsolutePath().normalize())
    }
}

internal enum class LegacySourceContractMode {
    ANALYZED,
    APPLIED
}

internal class LegacySourceContractPlans(
    val modelData: List<Change>,
    val plant: List<Change>,
    val blockStart: List<Change>,
    internal val pass: StructuralRefactorPass? = null
) {
    val reportedChanges: List<Change>
        get() = modelData + plant + blockStart
}

internal interface LegacySourceContractCoordinator {
    fun hasPublishedInventory(project: Path): Boolean
    fun hasInventoryRequest(project: Path): Boolean
    fun preflight(project: Path): LegacySourceContractPlans
    fun apply(project: Path, plans: LegacySourceContractPlans): List<Change>
    fun mark(project: Path, mode: LegacySourceContractMode)
    fun release(owner: Path): Boolean
}

private object ProductionLegacySourceContractCoordinator : LegacySourceContractCoordinator {
    override fun hasPublishedInventory(project: Path): Boolean =
        LegacyMigrationArtifactInventoryHandoff.isPublished(project)

    override fun hasInventoryRequest(project: Path): Boolean =
        ParsedLegacyMigrationSurfaceDetector.detect(project, project)
            .requiresSealedInventory(project, ReviewedSourceDeclarationGate)

    override fun preflight(project: Path): LegacySourceContractPlans {
        LegacyMigrationArtifactInventoryHandoff.require(project)
        val pass = StructuralRefactorPass()
        val modelData =
            LegacyModelDataManagerCompatMigration.migrate(pass, project, dryRun = true)
        val plant =
            LegacyPlantablesRuntimeMigration.migrate(pass, project, dryRun = true)
        val blockStart =
            LegacyBlockStartBreakCompatMigration.migrate(pass, project, dryRun = true)
        return LegacySourceContractPlans(
            modelData = modelData,
            plant = plant,
            blockStart = blockStart,
            pass = pass
        )
    }

    override fun apply(
        project: Path,
        plans: LegacySourceContractPlans
    ): List<Change> {
        LegacyMigrationArtifactInventoryHandoff.require(project)
        val pass = requireNotNull(plans.pass) {
            "Legacy source contract apply has no preflight context"
        }
        return buildList {
            addAll(LegacyPlantablesRuntimeMigration.migrate(pass, project, dryRun = false))
            addAll(LegacyBlockStartBreakCompatMigration.migrate(pass, project, dryRun = false))
            addAll(LegacyModelDataManagerCompatMigration.migrate(pass, project, dryRun = false))
        }
    }

    override fun mark(project: Path, mode: LegacySourceContractMode) {
        val inventory = LegacyMigrationArtifactInventoryHandoff.require(project)
        when (mode) {
            LegacySourceContractMode.ANALYZED ->
                LegacySourceContractOwnership.markAnalyzed(inventory)
            LegacySourceContractMode.APPLIED ->
                LegacySourceContractOwnership.markApplied(inventory)
        }
    }

    override fun release(owner: Path): Boolean =
        LegacySourceContractOwnership.release(owner)
}

internal object LegacySourceContractOwnership {
    private data class Ownership(
        val inventory: LegacyMigrationArtifactPair,
        val mode: LegacySourceContractMode
    )

    private val ownership = ThreadLocal.withInitial {
        mutableMapOf<Path, Ownership>()
    }

    fun markAnalyzed(inventory: LegacyMigrationArtifactPair) {
        mark(inventory, LegacySourceContractMode.ANALYZED)
    }

    fun markApplied(inventory: LegacyMigrationArtifactPair) {
        mark(inventory, LegacySourceContractMode.APPLIED)
    }

    fun requireApplied(
        project: Path,
        dryRun: Boolean = false
    ): LegacyMigrationArtifactPair {
        val normalized = project.toAbsolutePath().normalize()
        val record = ownership.get()[normalized]
            ?: error("Legacy source contracts were not completed for $normalized")
        require(dryRun || record.mode == LegacySourceContractMode.APPLIED) {
            "Legacy source contracts were only analyzed, not applied, for $normalized"
        }
        val current = LegacyMigrationArtifactInventoryHandoff.require(normalized)
        require(current === record.inventory) {
            "Legacy source contract ownership no longer matches the published paired inventory"
        }
        current.verifyCurrentBytes("legacy source contract ownership lookup")
        return current
    }

    fun release(ownerProject: Path): Boolean {
        val normalized = ownerProject.toAbsolutePath().normalize()
        val records = ownership.get()
        val record = records[normalized] ?: return false
        require(normalized == record.inventory.ownerProject) {
            "Legacy source contract ownership must be released by its owning project"
        }
        removeAliases(records, record)
        if (records.isEmpty()) ownership.remove()
        return true
    }

    private fun mark(
        inventory: LegacyMigrationArtifactPair,
        mode: LegacySourceContractMode
    ) {
        inventory.verifyCurrentBytes("legacy source contract ownership publication")
        val aliases = setOf(inventory.ownerProject, inventory.leafProject)
        val records = ownership.get()
        val existing = aliases.mapNotNull(records::get).toSet()
        require(existing.isEmpty()) {
            val modes = existing.map { it.mode }.toSet()
            "Legacy source contract ownership is already published with mode $modes; " +
                "refusing to mix it with $mode"
        }
        val record = Ownership(inventory, mode)
        aliases.forEach { records[it] = record }
    }

    private fun removeAliases(
        records: MutableMap<Path, Ownership>,
        record: Ownership
    ) {
        setOf(record.inventory.ownerProject, record.inventory.leafProject).forEach { alias ->
            if (records[alias] === record) records.remove(alias)
        }
    }
}
