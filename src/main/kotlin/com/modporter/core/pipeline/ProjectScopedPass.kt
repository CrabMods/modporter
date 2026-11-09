package com.modporter.core.pipeline

import java.nio.file.Path

/** A pass that owns resources used by subsequent passes in one project invocation. */
interface ProjectScopedPass : Pass {
    val abortOnErrors: Boolean get() = true

    /** Called once even when the pipeline fails before this pass runs. */
    fun finishProject(projectDir: Path)
}
