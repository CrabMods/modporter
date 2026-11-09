package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension

/**
 * Filesystem transaction for [CreateItemStackDataFamilies].
 *
 * The complete Java source map is admitted and migrated once in memory. Every changed source must
 * parse before any stage or backup is created. Publication uses same-directory stage and recovery
 * files, verifies the original project snapshot before every replacement, and restores all
 * attempted targets in reverse order when commit fails.
 */
internal object CreateItemStackDataFamilyMigration {
    const val RULE_ID = "struct-create-item-stack-data-family"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    internal interface ProjectFileIo {
        fun readBytes(path: Path): ByteArray
        fun createTempSibling(target: Path, suffix: String): Path
        fun writeBytes(path: Path, bytes: ByteArray)
        fun moveReplace(source: Path, target: Path)
        fun deleteIfExists(path: Path)
    }

    internal object NioProjectFileIo : ProjectFileIo {
        override fun readBytes(path: Path): ByteArray = Files.readAllBytes(path)

        override fun createTempSibling(target: Path, suffix: String): Path =
            Files.createTempFile(target.parent, ".${target.fileName}.modporter-", suffix)

        override fun writeBytes(path: Path, bytes: ByteArray) {
            Files.write(path, bytes)
        }

        override fun moveReplace(source: Path, target: Path) {
            try {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        override fun deleteIfExists(path: Path) {
            Files.deleteIfExists(path)
        }
    }

    fun migrate(
        projectDir: Path,
        dryRun: Boolean,
        fileIo: ProjectFileIo = NioProjectFileIo
    ): Result = try {
        migrateChecked(projectDir.toAbsolutePath().normalize(), dryRun, fileIo)
    } catch (abort: AdmissionAbort) {
        Result(
            emptyList(),
            listOf(abort.message ?: "Create item-data family admission failed")
        )
    } catch (failure: Throwable) {
        Result(
            emptyList(),
            listOf(
                "Create item-data family migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(
        projectDir: Path,
        dryRun: Boolean,
        fileIo: ProjectFileIo
    ): Result {
        val files = javaFiles(projectDir)
        if (files.isEmpty()) return Result.EMPTY

        val originalBytes = LinkedHashMap<Path, ByteArray>()
        val pathByKey = LinkedHashMap<String, Path>()
        val sources = LinkedHashMap<String, String>()
        for (path in files) {
            val bytes = fileIo.readBytes(path).copyOf()
            val source = String(bytes, StandardCharsets.UTF_8)
            if (!source.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
                abort("Create item-data source is not exact UTF-8: $path")
            }
            val key = projectDir.relativize(path).toString().replace('\\', '/')
            if (pathByKey.put(key, path) != null) {
                abort("Create item-data source map has a duplicate path: $key")
            }
            originalBytes[path] = bytes
            sources[key] = source
        }

        val pending = CreateItemStackDataFamilies.hasPendingProjectMigration(sources)
        val migrated = CreateItemStackDataFamilies.migrateProject(sources)
        if (migrated === sources) {
            if (pending) {
                abort(
                    "Create item-data family is active but incomplete or has an unproved caller; " +
                        "no source file was written"
                )
            }
            return Result.EMPTY
        }
        if (migrated.keys != sources.keys) {
            abort("Create item-data transaction changed the project source key set")
        }

        val changedKeys = sources.keys.filter { key -> migrated.getValue(key) != sources.getValue(key) }
        if (changedKeys.isEmpty()) return Result.EMPTY

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val migratedBytes = LinkedHashMap<Path, ByteArray>()
        for (key in changedKeys) {
            val path = pathByKey.getValue(key)
            val source = migrated.getValue(key)
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful || parsed.result.isEmpty) {
                abort(
                    "Create item-data staged source did not parse at $path: " +
                        parsed.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            migratedBytes[path] = source.toByteArray(StandardCharsets.UTF_8)
        }

        val changes = migratedBytes.keys.map { path ->
            Change(
                file = path,
                line = 0,
                description = "Migrate Create item data as one provider/component source family",
                before = "Legacy per-stack NBT and provider-less nested ItemStack calls",
                after = "Source-proven providers, native components, and lossless copied custom data",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
        if (dryRun) return Result(changes, emptyList())

        val failure = commitProjectTransaction(
            projectDir = projectDir,
            migrated = migratedBytes,
            originals = originalBytes,
            fileIo = fileIo
        )
        return if (failure == null) {
            Result(changes, emptyList())
        } else {
            Result(
                changes.filter { it.file in failure.changedPaths },
                listOf(failure.gate)
            )
        }
    }

    private fun commitProjectTransaction(
        projectDir: Path,
        migrated: Map<Path, ByteArray>,
        originals: Map<Path, ByteArray>,
        fileIo: ProjectFileIo
    ): TransactionFailure? {
        val stages = linkedMapOf<Path, Path>()
        val backups = linkedMapOf<Path, Path>()
        val temporary = linkedSetOf<Path>()
        val retainedRecovery = linkedSetOf<Path>()
        val attempted = mutableListOf<Path>()
        val published = linkedSetOf<Path>()
        var failure: TransactionFailure? = null

        try {
            verifyProjectSnapshot(
                projectDir, originals, migrated, published, fileIo, "before staging"
            )
            for ((target, after) in migrated) {
                val stage = fileIo.createTempSibling(target, ".stage")
                temporary.add(stage)
                fileIo.writeBytes(stage, after.copyOf())
                check(fileIo.readBytes(stage).contentEquals(after)) {
                    "Staged Create item-data source changed before commit: $target"
                }
                stages[target] = stage

                val backup = fileIo.createTempSibling(target, ".backup")
                temporary.add(backup)
                val original = originals.getValue(target)
                fileIo.writeBytes(backup, original.copyOf())
                check(fileIo.readBytes(backup).contentEquals(original)) {
                    "Create item-data recovery copy does not match its snapshot: $target"
                }
                backups[target] = backup
            }

            verifyProjectSnapshot(
                projectDir, originals, migrated, published, fileIo, "after staging"
            )
            for ((target, stage) in stages) {
                verifyProjectSnapshot(
                    projectDir, originals, migrated, published, fileIo, "before publish"
                )
                attempted.add(target)
                fileIo.moveReplace(stage, target)
                published.add(target)
                check(fileIo.readBytes(target).contentEquals(migrated.getValue(target))) {
                    "Published Create item-data source does not match staged bytes: $target"
                }
            }
            verifyProjectSnapshot(
                projectDir, originals, migrated, published, fileIo, "after publish"
            )
        } catch (commitFailure: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            for (target in attempted.asReversed().distinct()) {
                val original = originals.getValue(target)
                val alreadyRestored = runCatching {
                    fileIo.readBytes(target).contentEquals(original)
                }.getOrDefault(false)
                if (alreadyRestored) continue

                val backup = backups[target]
                if (backup == null) {
                    rollbackFailures += IOException(
                        "No verified Create item-data recovery copy exists for $target"
                    )
                    continue
                }
                try {
                    check(fileIo.readBytes(backup).contentEquals(original)) {
                        "Create item-data recovery copy changed before rollback: $backup"
                    }
                    val rollbackStage = fileIo.createTempSibling(target, ".rollback")
                    temporary.add(rollbackStage)
                    fileIo.writeBytes(rollbackStage, original.copyOf())
                    check(fileIo.readBytes(rollbackStage).contentEquals(original)) {
                        "Create item-data rollback stage changed before restore: $target"
                    }
                    fileIo.moveReplace(rollbackStage, target)
                    check(fileIo.readBytes(target).contentEquals(original)) {
                        "Create item-data source did not match its snapshot after rollback: $target"
                    }
                } catch (rollbackFailure: Throwable) {
                    rollbackFailures += rollbackFailure
                }
            }

            val unresolved = originals.keys.filterTo(linkedSetOf()) { target ->
                runCatching {
                    !fileIo.readBytes(target).contentEquals(originals.getValue(target))
                }.getOrDefault(true)
            }
            val recoveryPaths = linkedMapOf<Path, Path>()
            unresolved.forEach { target ->
                backups[target]?.let { backup ->
                    retainedRecovery.add(backup)
                    recoveryPaths[target] = backup
                }
            }
            val cause = commitFailure.message ?: commitFailure::class.java.simpleName
            failure = if (unresolved.isEmpty()) {
                TransactionFailure(
                    emptySet(),
                    if (attempted.isEmpty()) {
                        "Create item-data transaction failed before publish; no source file was " +
                            "replaced: $cause"
                    } else {
                        "Create item-data transaction failed during publish; all attempted source " +
                            "replacements were restored: $cause"
                    }
                )
            } else {
                val recovery = recoveryPaths.entries.joinToString().ifBlank { "none available" }
                val rollback = rollbackFailures.joinToString { error ->
                    error.message ?: error::class.java.simpleName
                }
                TransactionFailure(
                    unresolved,
                    "Create item-data transaction failed and rollback is incomplete. " +
                        "Changed or unverifiable files: ${unresolved.joinToString()}. " +
                        "Recovery copies: $recovery. Commit failure: $cause. " +
                        "Rollback failure: $rollback"
                )
            }
        }

        val cleanupFailures = mutableListOf<String>()
        for (path in temporary) {
            if (path in retainedRecovery) continue
            try {
                fileIo.deleteIfExists(path)
            } catch (cleanupFailure: Throwable) {
                cleanupFailures +=
                    "$path (${cleanupFailure.message ?: cleanupFailure::class.java.simpleName})"
            }
        }
        if (cleanupFailures.isNotEmpty()) {
            val detail = "Temporary Create item-data transaction files could not be removed: " +
                cleanupFailures.joinToString()
            val existing = failure
            failure = if (existing == null) {
                TransactionFailure(migrated.keys, "Create item-data sources were committed. $detail")
            } else {
                existing.copy(gate = existing.gate + ". $detail")
            }
        }
        return failure
    }

    private fun verifyProjectSnapshot(
        projectDir: Path,
        originals: Map<Path, ByteArray>,
        migrated: Map<Path, ByteArray>,
        published: Set<Path>,
        fileIo: ProjectFileIo,
        phase: String
    ) {
        val currentFiles = javaFiles(projectDir).toSet()
        check(currentFiles == originals.keys) {
            "Create item-data Java source set changed $phase"
        }
        originals.forEach { (path, original) ->
            val expected = if (path in published) migrated.getValue(path) else original
            check(fileIo.readBytes(path).contentEquals(expected)) {
                "Create item-data source changed $phase: $path"
            }
        }
    }

    private fun javaFiles(projectDir: Path): List<Path> {
        val root = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "java" }
                .map { it.toAbsolutePath().normalize() }
                .sorted()
                .toList()
        }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private data class TransactionFailure(
        val changedPaths: Set<Path>,
        val gate: String
    )

    private class AdmissionAbort(message: String) : RuntimeException(message)
}
