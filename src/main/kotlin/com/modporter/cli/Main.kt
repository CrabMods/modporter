package com.modporter.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.modporter.AppInfo
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.Pipeline
import com.modporter.core.pipeline.PipelineResult
import com.modporter.mapping.MappingDatabase
import com.modporter.registry.PipelineOptions
import com.modporter.registry.PipelineRegistry
import com.modporter.report.ReportGenerator
import java.nio.file.Path
import kotlin.io.path.createDirectories

fun main(args: Array<String>) {
    ModPorter().subcommands(
        PortCommand(),
        AnalyzeCommand(),
        ValidateCommand(),
        ListCommand()
    ).main(args)
}

// Keep Forge2Neo as alias for backward compatibility in tests.
class Forge2Neo : CliktCommand(
    name = "modporter",
    help = "General-purpose Minecraft Mod Migration Tool"
) {
    override fun run() = Unit
}

class ModPorter : CliktCommand(
    name = "modporter",
    help = "General-purpose Minecraft Mod Migration Tool"
) {
    override fun run() = Unit
}

class PortCommand : CliktCommand(
    name = "port",
    help = "Port a mod from one framework/version to another"
) {
    private val src by option("--src", help = "Source mod project directory")
        .path(mustExist = true, canBeFile = false)
        .required()

    private val out by option("--out", help = "Output directory (default: <src>-neoforge)")
        .path()

    private val pipelineId by option("--pipeline", "-p", help = "Pipeline ID (e.g., forge2neo). Auto-detects if omitted.")
        .default("auto")

    private val dryRun by option("--dry-run", help = "Preview changes without modifying files")
        .flag(default = false)

    private val minConfidence by option(
        "--min-confidence",
        help = "Minimum confidence level for dry-run/report output (high, medium, low)"
    )
        .default("low")

    private val report by option("--report", help = "Write detailed report to file")
        .path()

    @Suppress("unused")
    private val verbose by option("--verbose", help = "Show detailed transformation log")
        .flag(default = false)

    private val offline by option("--offline", help = "Skip online dependency resolution (use known mappings only)")
        .flag(default = false)

    private val resolveDeps by option("--resolve-deps", help = "Auto-resolve NeoForge versions of third-party deps (default: true)")
        .flag("--no-resolve-deps", default = true)

    private val addToolCredit by option(
        "--add-tool-credit",
        help = "Append a ModPorter credit to supported mod metadata credits fields"
    )
        .flag(default = false)

    override fun run() {
        val pipelineDef = resolvePipeline(pipelineId, src)
        val projectDir = out ?: src.resolveSibling("${src.fileName}-neoforge")
        val confidence = parseConfidence(minConfidence)

        if (!dryRun && confidence != Confidence.LOW) {
            throw UsageError(
                "--min-confidence filters reported changes only. Use --dry-run with " +
                    "--min-confidence $minConfidence to review filtered changes, then run apply " +
                    "mode without a confidence filter."
            )
        }

        if (!dryRun && projectDir != src) {
            echo("Copying project to $projectDir ...")
            copyProject(src, projectDir)
        }

        val targetDir = if (dryRun) src else projectDir
        val options = PipelineOptions(
            offline = offline,
            resolveDeps = resolveDeps,
            addToolCredit = addToolCredit
        )

        echo("===========================================")
        echo("  ${AppInfo.name} v${AppInfo.version}")
        echo("  Pipeline: ${pipelineDef.displayName}")
        echo("  Source: $src")
        echo("  Target: $targetDir")
        echo("  Mode: ${if (dryRun) "DRY RUN" else "APPLY"}")
        echo("  Min confidence: $confidence")
        if (resolveDeps) echo("  Dep resolution: ${if (offline) "offline only" else "online + offline"}")
        if (addToolCredit) echo("  Tool credit: enabled")
        echo("===========================================")
        echo()

        val mappingDb = MappingDatabase.load(pipelineDef.mappingsPrefix)
        val pipeline = Pipeline(
            passes = pipelineDef.passFactory(mappingDb, options),
            minConfidence = confidence,
            dryRun = dryRun,
            pipelineName = pipelineDef.displayName
        )

        val result = pipeline.run(targetDir)
        echo(result.summary())

        val reportPath = report
        if (reportPath != null) {
            reportPath.parent?.createDirectories()
            ReportGenerator().generate(result, reportPath)
            echo("Report written to: $reportPath")
        }

        failOnBlockingMigrationResult(result)
    }

    private fun copyProject(src: Path, dest: Path) {
        dest.createDirectories()
        src.toFile().copyRecursively(dest.toFile(), overwrite = true) { _, e ->
            echo("Warning: ${e.message}")
            OnErrorAction.SKIP
        }
    }

    private fun parseConfidence(value: String): Confidence = when (value.lowercase()) {
        "high" -> Confidence.HIGH
        "medium" -> Confidence.MEDIUM
        "low" -> Confidence.LOW
        else -> throw UsageError("Invalid --min-confidence '$value'; expected high, medium, or low.")
    }
}

class AnalyzeCommand : CliktCommand(
    name = "analyze",
    help = "Analyze a mod and report needed changes without modifying files"
) {
    private val src by option("--src", help = "Source mod project directory")
        .path(mustExist = true, canBeFile = false)
        .required()

    private val pipelineId by option("--pipeline", "-p", help = "Pipeline ID (e.g., forge2neo). Auto-detects if omitted.")
        .default("auto")

    private val report by option("--report", help = "Write report to file")
        .path()

    override fun run() {
        val pipelineDef = resolvePipeline(pipelineId, src)
        echo("Analyzing mod at: $src (pipeline: ${pipelineDef.displayName})")

        val mappingDb = MappingDatabase.load(pipelineDef.mappingsPrefix)
        val pipeline = Pipeline(
            passes = pipelineDef.passFactory(mappingDb, PipelineOptions()),
            dryRun = true,
            pipelineName = pipelineDef.displayName
        )

        val result = pipeline.run(src)
        echo(result.summary())

        val reportPath = report
        if (reportPath != null) {
            reportPath.parent?.createDirectories()
            ReportGenerator().generate(result, reportPath)
            echo("Report written to: $reportPath")
        }

        failOnBlockingMigrationResult(result)
    }
}

class ValidateCommand : CliktCommand(
    name = "validate",
    help = "Check if a ported mod still has remaining source framework references"
) {
    private val src by option("--src", help = "Mod project directory to validate")
        .path(mustExist = true, canBeFile = false)
        .required()

    private val pipelineId by option("--pipeline", "-p", help = "Pipeline ID (e.g., forge2neo). Auto-detects if omitted.")
        .default("auto")

    override fun run() {
        val pipelineDef = resolvePipeline(pipelineId, src)
        echo("Validating mod at: $src (pipeline: ${pipelineDef.displayName})")

        val patterns = pipelineDef.validationPatterns

        var found = 0
        java.nio.file.Files.walk(src)
            .filter { it.toString().endsWith(".java") }
            .forEach { file ->
                val content = file.toFile().readText()
                for (pattern in patterns) {
                    if (content.contains(pattern)) {
                        echo("  FOUND: $pattern in $file")
                        found++
                    }
                }
            }

        if (found == 0) {
            echo("No remaining source framework references found.")
        } else {
            echo("Found $found remaining source framework references")
            throw UsageError("Validation failed: found $found remaining source framework references")
        }
    }
}

class ListCommand : CliktCommand(
    name = "list",
    help = "List available migration pipelines"
) {
    override fun run() {
        echo("Available pipelines:")
        echo()
        for (p in PipelineRegistry.list()) {
            echo("  ${p.id.padEnd(20)} ${p.displayName}")
            echo("    ${p.sourceFramework.padEnd(20)} -> ${p.targetFramework}")
        }
    }
}

/**
 * Resolve pipeline by ID or auto-detect from project contents.
 */
private fun resolvePipeline(pipelineId: String, projectDir: Path) =
    if (pipelineId == "auto") {
        PipelineRegistry.detect(projectDir)
            ?: throw UsageError("No pipeline detected from project contents. Use --pipeline to specify one explicitly.")
    } else {
        PipelineRegistry.get(pipelineId)
            ?: error("Unknown pipeline: $pipelineId. Use 'modporter list' to see available pipelines.")
    }

private fun failOnBlockingMigrationResult(result: PipelineResult) {
    if (result.totalErrors == 0 && result.totalSkipped == 0) return
    throw UsageError(
        "Migration did not complete cleanly: ${result.totalErrors} errors, " +
            "${result.totalSkipped} skipped source shapes. See the summary/report for details."
    )
}
