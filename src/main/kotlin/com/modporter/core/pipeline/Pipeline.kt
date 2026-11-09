package com.modporter.core.pipeline

import mu.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Pipeline orchestrator that runs transformation passes in order.
 */
class Pipeline(
    private val passes: List<Pass>,
    private val minConfidence: Confidence = Confidence.LOW,
    private val dryRun: Boolean = false,
    private val pipelineName: String = "ModPorter"
) {
    /**
     * Run all applicable passes on the project.
     */
    fun run(projectDir: Path): PipelineResult {
        logger.info { "Starting $pipelineName pipeline on: $projectDir" }
        logger.info { "Mode: ${if (dryRun) "DRY RUN" else "APPLY"}, Min confidence: $minConfidence" }

        val sortedPasses = passes.sortedBy { it.order }
        val results = mutableListOf<PassResult>()
        var pipelineFailure: Throwable? = null
        try {
            val modDir = GradleModLayout.modModule(projectDir)
            if (modDir != projectDir) logger.info { "Multi-project build: migrating mod module $modDir" }
            for (pass in sortedPasses) {
                val passDir = if (pass.migratesBuildRoot) projectDir else modDir
                if (!pass.isApplicable(passDir)) {
                    logger.info { "Skipping pass '${pass.name}' (not applicable)" }
                    continue
                }

                logger.info { "Running pass ${pass.order}: ${pass.name}" }

                val result = if (dryRun) {
                    pass.analyze(passDir)
                } else {
                    pass.apply(passDir)
                }

                // Filter by confidence (lower ordinal = higher confidence)
                // HIGH=0, MEDIUM=1, LOW=2 — keep changes with ordinal <= minConfidence ordinal
                val filtered = result.copy(
                    changes = result.changes.filter { it.confidence.ordinal <= minConfidence.ordinal }
                )

                results.add(filtered)
                logger.info {
                    "Pass '${pass.name}' complete: ${filtered.changeCount} changes " +
                    "(${filtered.highConfidence} high, ${filtered.mediumConfidence} medium, ${filtered.lowConfidence} low)"
                }

                if (filtered.errors.isNotEmpty()) {
                    logger.warn { "Pass '${pass.name}' had ${filtered.errors.size} errors" }
                    filtered.errors.forEach { logger.warn { "  - $it" } }
                }
                if (filtered.skipped.isNotEmpty()) {
                    logger.warn { "Pass '${pass.name}' skipped ${filtered.skipped.size} source shapes" }
                    filtered.skipped.forEach { logger.warn { "  - $it" } }
                }
                if (pass is ProjectScopedPass && pass.abortOnErrors && filtered.errors.isNotEmpty()) {
                    break
                }
            }

            return PipelineResult(results, dryRun, pipelineName)
        } catch (failure: Throwable) {
            pipelineFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            for (pass in sortedPasses.asReversed()) {
                if (pass !is ProjectScopedPass) continue
                try {
                    pass.finishProject(projectDir)
                } catch (failure: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = failure
                    else previous.addSuppressed(failure)
                }
            }
            val failedCleanup = cleanupFailure
            if (failedCleanup != null) {
                val original = pipelineFailure
                if (original == null) throw failedCleanup
                original.addSuppressed(failedCleanup)
            }
        }
    }
}

/**
 * Aggregate result of the entire pipeline run.
 */
data class PipelineResult(
    val passResults: List<PassResult>,
    val dryRun: Boolean,
    val pipelineName: String = "ModPorter"
) {
    val totalChanges get() = passResults.sumOf { it.changeCount }
    val totalErrors get() = passResults.sumOf { it.errors.size }
    val totalSkipped get() = passResults.sumOf { it.skipped.size }

    fun summary(): String = buildString {
        appendLine("═══════════════════════════════════════════")
        appendLine("  $pipelineName Pipeline ${if (dryRun) "(DRY RUN)" else ""} Summary")
        appendLine("═══════════════════════════════════════════")
        for (result in passResults) {
            val skippedText = if (result.skipped.isNotEmpty()) ", ${result.skipped.size} skipped" else ""
            appendLine("  ${result.passName}: ${result.changeCount} changes$skippedText")
        }
        appendLine("───────────────────────────────────────────")
        appendLine("  Total changes: $totalChanges")
        appendLine("  Total skipped: $totalSkipped")
        appendLine("  Total errors: $totalErrors")
        appendLine("═══════════════════════════════════════════")
    }
}
