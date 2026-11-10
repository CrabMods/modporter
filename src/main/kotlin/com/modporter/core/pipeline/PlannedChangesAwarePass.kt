package com.modporter.core.pipeline

import java.nio.file.Path

/**
 * A pass whose dry-run analysis consumes the already filtered results of earlier passes.
 * The handoff is scoped to one [Pipeline.run] invocation and never persists across projects.
 */
interface PlannedChangesAwarePass : Pass {
    fun analyze(projectDir: Path, precedingResults: List<PassResult>): PassResult
}
