package com.modporter.registry

import com.modporter.pipelines.forge2neo.Forge2NeoPipeline
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Central registry of available migration pipelines.
 * Pipelines are registered at startup and can be looked up by ID or auto-detected.
 */
object PipelineRegistry {
    private val pipelines = mutableMapOf<String, PipelineDefinition>()

    init {
        register(Forge2NeoPipeline.definition)
    }

    fun register(definition: PipelineDefinition) {
        pipelines[definition.id] = definition
        logger.debug { "Registered pipeline: ${definition.id} (${definition.displayName})" }
    }

    fun get(id: String): PipelineDefinition? = pipelines[id]

    fun list(): List<PipelineDefinition> = pipelines.values.toList()

    /**
     * Auto-detect which pipeline to use based on project contents.
     * Scans Java files for framework-specific patterns.
     */
    fun detect(buildDir: Path): PipelineDefinition? {
        val projectDir = com.modporter.core.pipeline.GradleModLayout.modModule(buildDir)
        val javaFiles = if (Files.exists(projectDir.resolve("src/main/java"))) {
            Files.walk(projectDir.resolve("src/main/java"))
                .filter { it.toString().endsWith(".java") }
                .toList()
        } else {
            emptyList()
        }

        if (javaFiles.isEmpty()) return null

        val executableContent = javaFiles.joinToString("\n") { file ->
            maskJavaCommentsAndLiterals(file.toFile().readText())
        }

        // Score each pipeline by how many detection patterns match
        return pipelines.values
            .map { def ->
                val score = def.detectionPatterns.count { pattern -> executableContent.contains(pattern) }
                def to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    out.append("  ")
                    i += 2
                    while (i < source.length && source[i] != '\n' && source[i] != '\r') {
                        out.append(' ')
                        i++
                    }
                }
                source.startsWith("/*", i) -> {
                    out.append("  ")
                    i += 2
                    while (i < source.length && !source.startsWith("*/", i)) {
                        out.append(if (source[i] == '\n' || source[i] == '\r') source[i] else ' ')
                        i++
                    }
                    if (i < source.length) {
                        out.append("  ")
                        i += 2
                    }
                }
                source.startsWith("\"\"\"", i) -> {
                    out.append("   ")
                    i += 3
                    while (i < source.length && !source.startsWith("\"\"\"", i)) {
                        out.append(if (source[i] == '\n' || source[i] == '\r') source[i] else ' ')
                        i++
                    }
                    if (i < source.length) {
                        out.append("   ")
                        i += 3
                    }
                }
                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    out.append(' ')
                    i++
                    var escaped = false
                    while (i < source.length) {
                        val ch = source[i]
                        out.append(if (ch == '\n' || ch == '\r') ch else ' ')
                        i++
                        if (escaped) {
                            escaped = false
                        } else if (ch == '\\') {
                            escaped = true
                        } else if (ch == quote) {
                            break
                        }
                    }
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
