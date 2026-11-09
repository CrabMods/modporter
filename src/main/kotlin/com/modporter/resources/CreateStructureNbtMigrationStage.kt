package com.modporter.resources

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * One preflight and one atomic file transaction for the independently bounded
 * Create FluidStack, empty ItemStack, nonempty ItemStack, entity-gravity, and
 * source-bound empty-entity structure migrations.
 */
internal object CreateStructureNbtMigrationStage {
    const val RULE_ID = "res-create-structure-nbt-121"

    private val STRUCTURE_DIRECTORIES =
        setOf("structure", "structures", "gameteststructures")

    data class PlannedFile(
        val path: Path,
        val originalBytes: ByteArray,
        val migratedBytes: ByteArray,
        val migratedTankCount: Int,
        val migratedAirCount: Int,
        val migratedItemCount: Int,
        val migratedGravityCount: Int,
        val migratedEmptyEntityCount: Int,
        val originalSha256: String,
        val migratedSha256: String
    )

    data class Plan(
        val files: List<PlannedFile>,
        val blockers: List<String>
    ) {
        val accepted: Boolean get() = blockers.isEmpty()
        val migratedTankCount: Int get() = files.sumOf(PlannedFile::migratedTankCount)
        val migratedAirCount: Int get() = files.sumOf(PlannedFile::migratedAirCount)
        val migratedItemCount: Int get() = files.sumOf(PlannedFile::migratedItemCount)
        val migratedGravityCount: Int get() = files.sumOf(PlannedFile::migratedGravityCount)
        val migratedEmptyEntityCount: Int
            get() = files.sumOf(PlannedFile::migratedEmptyEntityCount)
    }

    internal data class ByteMigration(
        val bytes: ByteArray,
        val migratedTankCount: Int,
        val migratedAirCount: Int,
        val migratedItemCount: Int,
        val migratedGravityCount: Int,
        val migratedEmptyEntityCount: Int
    )

    fun plan(resourceRoots: Collection<Path>): Plan {
        val files = linkedSetOf<Path>()
        val gravityEligibleFiles = linkedSetOf<Path>()
        val blockers = mutableListOf<String>()
        resourceRoots
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .sorted()
            .forEach { root ->
                if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) return@forEach
                Files.walk(root).use { paths ->
                    paths.filter { candidate ->
                        candidate.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                            candidate.extension.equals("nbt", ignoreCase = true) &&
                            isActiveStructurePath(root.relativize(candidate))
                    }.sorted().forEach { candidate ->
                        files.add(candidate)
                        if (isCreateDataStructurePath(root.relativize(candidate))) {
                            gravityEligibleFiles.add(candidate)
                        }
                    }
                }
            }

        val planned = mutableListOf<PlannedFile>()
        for (file in files) {
            if (Files.isSymbolicLink(file)) {
                blockers.add("$file: symbolic-link structure input is not admitted")
                continue
            }
            try {
                val original = Files.readAllBytes(file)
                val migration = migrateBytes(
                    original,
                    file.toString(),
                    migrateGravityAttributes = file in gravityEligibleFiles
                )
                if (migration.migratedTankCount == 0 &&
                    migration.migratedAirCount == 0 &&
                    migration.migratedItemCount == 0 &&
                    migration.migratedGravityCount == 0 &&
                    migration.migratedEmptyEntityCount == 0
                ) {
                    continue
                }
                planned.add(
                    PlannedFile(
                        path = file,
                        originalBytes = original,
                        migratedBytes = migration.bytes,
                        migratedTankCount = migration.migratedTankCount,
                        migratedAirCount = migration.migratedAirCount,
                        migratedItemCount = migration.migratedItemCount,
                        migratedGravityCount = migration.migratedGravityCount,
                        migratedEmptyEntityCount = migration.migratedEmptyEntityCount,
                        originalSha256 = sha256(original),
                        migratedSha256 = sha256(migration.bytes)
                    )
                )
            } catch (failure: Exception) {
                blockers.add("$file: invalid or unsupported Create structure NBT: ${failure.message}")
            }
        }
        return Plan(planned, blockers)
    }

    internal fun migrateBytes(
        input: ByteArray,
        context: String = "<memory>",
        migrateGravityAttributes: Boolean = false
    ): ByteMigration {
        // Bind source-only cleanups to the bytes captured before any typed
        // migration mutates this shared document. A clean second pass has no
        // admitted placeholders and returns unchanged before hash admission.
        val sourceSha256 = sha256(input)
        val typedNbt = CreateStructureFluidNbtMigration.decodeTypedNbt(input)
        val migratedEmptyEntities =
            CreateStructureEmptyEntityNbtMigration.migrateDocument(
                typedNbt.document,
                context,
                sourceSha256
            )
        val migratedTanks =
            CreateStructureFluidNbtMigration.migrateDocument(typedNbt.document, context)
        val migratedAir =
            CreateStructureEmptyItemNbtMigration.migrateDocument(typedNbt.document, context)
        val migratedItems =
            CreateStructureItemStackNbtMigration.migrateDocument(typedNbt.document, context)
        val migratedGravity = if (migrateGravityAttributes) {
            CreateStructureGravityAttributeNbtMigration.migrateDocument(
                typedNbt.document,
                context
            )
        } else {
            0
        }
        if (
            migratedTanks == 0 &&
            migratedAir == 0 &&
            migratedItems == 0 &&
            migratedGravity == 0 &&
            migratedEmptyEntities == 0
        ) {
            return ByteMigration(input, 0, 0, 0, 0, 0)
        }
        return ByteMigration(
            bytes = CreateStructureFluidNbtMigration.encodeTypedNbt(typedNbt),
            migratedTankCount = migratedTanks,
            migratedAirCount = migratedAir,
            migratedItemCount = migratedItems,
            migratedGravityCount = migratedGravity,
            migratedEmptyEntityCount = migratedEmptyEntities
        )
    }

    fun apply(plan: Plan) {
        require(plan.accepted) {
            "Create structure NBT migration refused: ${plan.blockers.joinToString("; ")}"
        }
        for (file in plan.files) {
            val current = Files.readAllBytes(file.path)
            require(sha256(current) == file.originalSha256) {
                "Create structure NBT drifted after planning: ${file.path}"
            }
        }

        val replaced = mutableListOf<PlannedFile>()
        try {
            for (file in plan.files) {
                replaceAtomically(file.path, file.migratedBytes)
                replaced.add(file)
            }
        } catch (failure: Exception) {
            val rollbackFailures = mutableListOf<Throwable>()
            for (file in replaced.asReversed()) {
                runCatching { replaceAtomically(file.path, file.originalBytes) }
                    .exceptionOrNull()
                    ?.let(rollbackFailures::add)
            }
            rollbackFailures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun isActiveStructurePath(relativePath: Path): Boolean {
        val parts =
            (0 until relativePath.nameCount).map { relativePath.getName(it).toString() }
        if (parts.size >= 2 && parts.first() == "gameteststructures") return true
        return parts.size >= 4 &&
            parts[0] == "data" &&
            parts[1] == "create" &&
            parts[2] in STRUCTURE_DIRECTORIES
    }

    private fun isCreateDataStructurePath(relativePath: Path): Boolean {
        val parts =
            (0 until relativePath.nameCount).map { relativePath.getName(it).toString() }
        return parts.size >= 4 &&
            parts[0] == "data" &&
            parts[1] == "create" &&
            parts[2] in STRUCTURE_DIRECTORIES
    }

    private fun replaceAtomically(target: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".modporter.tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
