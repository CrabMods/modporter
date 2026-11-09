package com.modporter.core.pipeline

import java.nio.file.Path

/**
 * Confidence level for a transformation.
 * HIGH = deterministic rename, guaranteed correct
 * MEDIUM = pattern-based, expected to validate under strict gates
 * LOW = heuristic/AI-based, blocks strict success until covered by an automated gate
 */
enum class Confidence { HIGH, MEDIUM, LOW }

/**
 * A single change made by a pass.
 */
data class Change(
    val file: Path,
    val line: Int,
    val description: String,
    val before: String,
    val after: String,
    val confidence: Confidence,
    val ruleId: String
)

/**
 * Result of running a pass on the entire project.
 */
data class PassResult(
    val passName: String,
    val changes: List<Change>,
    val errors: List<String> = emptyList(),
    val skipped: List<String> = emptyList()
) {
    val changeCount get() = changes.size
    val highConfidence get() = changes.count { it.confidence == Confidence.HIGH }
    val mediumConfidence get() = changes.count { it.confidence == Confidence.MEDIUM }
    val lowConfidence get() = changes.count { it.confidence == Confidence.LOW }
}

/**
 * Interface for all transformation passes.
 * Each pass is responsible for one category of transformations.
 */
interface Pass {
    val name: String
    val order: Int

    /**
     * Analyze the project and return proposed changes without applying them.
     */
    fun analyze(projectDir: Path): PassResult

    /**
     * Apply the transformations to the project.
     * Returns the result with all changes made.
     */
    fun apply(projectDir: Path): PassResult

    /**
     * Check if this pass is applicable to the given project.
     */
    fun isApplicable(projectDir: Path): Boolean = true

    /**
     * Whether the pass migrates the build root (settings, plugin classpath, wrapper) rather than
     * the mod module; the two differ in a multi-project build (see [GradleModLayout]).
     */
    val migratesBuildRoot: Boolean get() = false
}
