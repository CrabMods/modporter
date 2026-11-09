package com.modporter.integration

import com.modporter.core.pipeline.PipelineResult
import com.modporter.core.transforms.structural.CreateCopperDataMapMigration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

object CreateDataGenerationEvidence {
    const val SCHEMA = "modporter.create-data-generation.v1"

    private val json = Json { ignoreUnknownKeys = false }
    private val expectedMaps = linkedMapOf(
        "oxidizables.json" to 18,
        "waxables.json" to 24
    )

    data class ReceiptReference(
        val path: Path,
        val sha256: String
    )

    fun required(result: PipelineResult): Boolean =
        result.passResults
            .asSequence()
            .flatMap { it.changes.asSequence() }
            .any { it.ruleId == CreateCopperDataMapMigration.RULE_ID }

    fun seal(
        runId: String,
        caseId: String,
        buildRoot: Path,
        modDir: Path,
        reportsDir: Path,
        runDataLog: Path,
        runDataLeaseReceipt: Path,
        processResourcesLog: Path
    ): ReceiptReference {
        require(runId.isNotBlank()) { "Create data-generation evidence requires a run id" }
        require(caseId == "create") { "Create data-generation evidence cannot bind case '$caseId'" }

        val normalizedBuildRoot = buildRoot.toAbsolutePath().normalize()
        val normalizedModDir = modDir.toAbsolutePath().normalize()
        val normalizedReportsDir = reportsDir.toAbsolutePath().normalize()
        require(normalizedModDir == normalizedBuildRoot) {
            "Create benchmark data generation must use the selected single-project output: " +
                "buildRoot=$normalizedBuildRoot, modDir=$normalizedModDir"
        }
        val logs = linkedMapOf(
            "runData" to requireReportLog(
                runDataLog,
                normalizedReportsDir.resolve("$caseId-runData.log")
            ),
            "processResources" to requireReportLog(
                processResourcesLog,
                normalizedReportsDir.resolve("$caseId-processResources.log")
            )
        )
        val runtimeDirectory = normalizedModDir.resolve("run")
        val runtimeLease = requireRuntimeLease(
            runDataLeaseReceipt,
            normalizedReportsDir.resolve("$caseId-runData-session.json"),
            normalizedBuildRoot,
            runtimeDirectory
        )
        val fileEvidence = expectedMaps.map { (name, expectedCount) ->
            val relative = Path.of("data/neoforge/data_maps/block").resolve(name)
            val generated = normalizedModDir.resolve("src/generated/resources").resolve(relative)
            val packaged = normalizedModDir.resolve("build/resources/main").resolve(relative)
            val generatedBytes = requireDataMap(generated, normalizedModDir, expectedCount)
            val packagedBytes = requireDataMap(packaged, normalizedModDir, expectedCount)
            require(generatedBytes.contentEquals(packagedBytes)) {
                "Generated and packaged Create data maps differ: $name"
            }
            DataMapEvidence(
                name = name,
                valueCount = expectedCount,
                generated = FileEvidence(generated, generatedBytes.size.toLong(), sha256(generatedBytes)),
                packaged = FileEvidence(packaged, packagedBytes.size.toLong(), sha256(packagedBytes))
            )
        }

        val receipt = normalizedReportsDir.resolve("$caseId-data-generation-receipt.json")
        val receiptText = renderReceipt(
            runId,
            caseId,
            normalizedBuildRoot,
            normalizedModDir,
            runtimeLease,
            logs,
            fileEvidence
        )
        Files.writeString(
            receipt,
            receiptText,
            StandardCharsets.UTF_8,
            CREATE_NEW,
            WRITE
        )
        return ReceiptReference(receipt, sha256(receipt.readBytes())).also {
            verifyReference(it, normalizedReportsDir)
        }
    }

    fun verifyReference(reference: ReceiptReference, reportsDir: Path) {
        val normalizedReportsDir = reportsDir.toAbsolutePath().normalize()
        val receipt = reference.path.toAbsolutePath().normalize()
        require(receipt.startsWith(normalizedReportsDir)) {
            "Create data-generation receipt is outside the selected reports directory: $receipt"
        }
        require(isStrictRegularFile(receipt)) {
            "Create data-generation receipt is missing or linked: $receipt"
        }
        require(sha256(receipt.readBytes()) == reference.sha256) {
            "Create data-generation receipt hash changed: $receipt"
        }
    }

    private fun requireReportLog(path: Path, expected: Path): FileEvidence {
        val normalized = path.toAbsolutePath().normalize()
        val normalizedExpected = expected.toAbsolutePath().normalize()
        require(normalized == normalizedExpected) {
            "Create data-generation log path differs: expected $normalizedExpected, got $normalized"
        }
        require(isStrictRegularFile(normalized)) {
            "Create data-generation log is missing or linked: $normalized"
        }
        val text = normalized.readText()
        require("BUILD SUCCESSFUL" in text && "BUILD FAILED" !in text) {
            "Create data-generation log lacks a clean successful Gradle terminal marker: $normalized"
        }
        val bytes = normalized.readBytes()
        return FileEvidence(normalized, bytes.size.toLong(), sha256(bytes))
    }

    private fun requireRuntimeLease(
        path: Path,
        expected: Path,
        buildRoot: Path,
        runtimeDirectory: Path
    ): RuntimeLeaseEvidence {
        val normalized = path.toAbsolutePath().normalize()
        val normalizedExpected = expected.toAbsolutePath().normalize()
        require(normalized == normalizedExpected) {
            "Create runData lease receipt path differs: expected $normalizedExpected, got $normalized"
        }
        require(isStrictRegularFile(normalized)) {
            "Create runData lease receipt is missing or linked: $normalized"
        }
        val bytes = normalized.readBytes()
        val root = json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        require(root["schemaVersion"]?.jsonPrimitive?.int == 1) {
            "Create runData lease receipt has an unexpected schema"
        }
        val sessionId = root["sessionId"]?.jsonPrimitive?.content.orEmpty()
        require(sessionId.isNotBlank()) { "Create runData lease receipt has no session id" }
        require(
            Path.of(root["projectRoot"]?.jsonPrimitive?.content.orEmpty())
                .toAbsolutePath().normalize() == buildRoot
        ) {
            "Create runData lease receipt project root differs"
        }
        require(
            Path.of(root["runtimeDirectory"]?.jsonPrimitive?.content.orEmpty())
                .toAbsolutePath().normalize() == runtimeDirectory
        ) {
            "Create runData lease receipt runtime directory differs"
        }
        require(root["runtimeKind"]?.jsonPrimitive?.content == "server") {
            "Create runData lease receipt did not use the server runtime inventory"
        }
        require(root["processStartCount"]?.jsonPrimitive?.int == 1) {
            "Create runData lease receipt must record exactly one process start"
        }
        require(root["processInventoryChecked"]?.jsonPrimitive?.boolean == true) {
            "Create runData lease receipt did not inspect active runtime processes"
        }
        require(root["activeRuntimeProcesses"]?.jsonArray?.isEmpty() == true) {
            "Create runData lease receipt found an active overlapping runtime"
        }
        require(root["exitCode"]?.jsonPrimitive?.int == 0) {
            "Create runData lease receipt has a nonzero exit code"
        }
        require(root["passed"]?.jsonPrimitive?.boolean == true) {
            "Create runData lease receipt is not passing"
        }
        require(root["failure"]?.jsonPrimitive?.content.orEmpty().isBlank()) {
            "Create runData lease receipt contains a failure"
        }
        val command = root["command"]?.jsonObject
            ?: error("Create runData lease receipt has no command")
        val executable = Path.of(
            command["executable"]?.jsonPrimitive?.content.orEmpty()
        ).toAbsolutePath().normalize()
        val arguments = command["arguments"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: error("Create runData lease receipt has no command arguments")
        require(arguments.isEmpty()) {
            "Create runData lease receipt passed unexpected arguments to its nested launcher"
        }
        val launcher = requireLauncher(executable, buildRoot, runtimeDirectory)
        return RuntimeLeaseEvidence(
            file = FileEvidence(normalized, bytes.size.toLong(), sha256(bytes)),
            sessionId = sessionId,
            runtimeDirectory = runtimeDirectory,
            launcher = launcher
        )
    }

    private fun requireLauncher(
        path: Path,
        buildRoot: Path,
        runtimeDirectory: Path
    ): FileEvidence {
        val expectedDirectory = buildRoot.resolve(
            "build/minecraft-mod-testing/nested-launchers"
        ).toAbsolutePath().normalize()
        require(path.parent == expectedDirectory) {
            "Create runData nested launcher is outside the converted project: $path"
        }
        require(isStrictRegularFile(path)) {
            "Create runData nested launcher is missing or linked: $path"
        }
        val name = path.fileName.toString()
        val match = Regex("""command-([0-9a-f]{64})\.ps1""").matchEntire(name)
            ?: error("Create runData nested launcher is not content addressed: $path")
        val bytes = path.readBytes()
        require(sha256(bytes) == match.groupValues[1]) {
            "Create runData nested launcher hash differs from its file name: $path"
        }
        val text = bytes.toString(StandardCharsets.UTF_8)
        val executableMatch = Regex(
            """(?m)^[ \t]*\${'$'}modporterExecutable = ('(?:[^'\r\n]|'')*')\r?$"""
        ).find(text) ?: error("Create runData nested launcher has no executable")
        val expectedGradle = buildRoot.resolve("gradlew.bat").toAbsolutePath().normalize()
        val actualGradle = Path.of(
            parsePowerShellLiteral(executableMatch.groupValues[1])
        ).toAbsolutePath().normalize()
        require(actualGradle == expectedGradle) {
            "Create runData nested launcher Gradle executable differs"
        }
        val argumentsMatch = Regex(
            """(?ms)^[ \t]*\${'$'}modporterArguments = @\(\r?\n(.*?)^[ \t]*\)\r?$"""
        ).find(text) ?: error("Create runData nested launcher has no argument block")
        val argumentLines = argumentsMatch.groupValues[1]
            .lines()
            .filter { it.isNotBlank() }
            .map { it.trim() }
        val actualArguments = argumentLines.mapIndexed { index, line ->
            val needsComma = index < argumentLines.lastIndex
            require(line.endsWith(",") == needsComma) {
                "Create runData nested launcher argument separators differ"
            }
            parsePowerShellLiteral(if (needsComma) line.dropLast(1) else line)
        }
        val expectedArguments = listOf(
            "runData",
            "--no-daemon",
            "--stacktrace",
            "--console=plain",
            "-PminecraftModTestingRuntimeDirectory=$runtimeDirectory"
        )
        require(actualArguments == expectedArguments) {
            "Create runData nested launcher command differs"
        }
        return FileEvidence(path, bytes.size.toLong(), sha256(bytes))
    }

    private fun parsePowerShellLiteral(literal: String): String {
        require(
            Regex("""'(?:[^']|'')*'""").matches(literal)
        ) {
            "Create runData nested launcher contains a malformed PowerShell literal"
        }
        val value = literal.substring(1, literal.lastIndex).replace("''", "'")
        require(powerShellLiteral(value) == literal) {
            "Create runData nested launcher contains a noncanonical PowerShell literal"
        }
        return value
    }

    private fun powerShellLiteral(value: String): String =
        "'${value.replace("'", "''")}'"

    private fun requireDataMap(
        path: Path,
        modDir: Path,
        expectedCount: Int
    ): ByteArray {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(modDir)) {
            "Create data map is outside the converted mod directory: $normalized"
        }
        require(isStrictRegularFile(normalized)) {
            "Create data map is missing or linked: $normalized"
        }
        val bytes = normalized.readBytes()
        require(bytes.isNotEmpty()) { "Create data map is empty: $normalized" }
        val root = json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        val values = root["values"]?.jsonObject
            ?: error("Create data map has no object-valued 'values': $normalized")
        require(values.size == expectedCount) {
            "Create data map ${normalized.fileName} has ${values.size} values; expected $expectedCount"
        }
        return bytes
    }

    private fun renderReceipt(
        runId: String,
        caseId: String,
        buildRoot: Path,
        modDir: Path,
        runtimeLease: RuntimeLeaseEvidence,
        logs: Map<String, FileEvidence>,
        files: List<DataMapEvidence>
    ): String = buildString {
        appendLine("{")
        appendLine("  \"schema\": ${quote(SCHEMA)},")
        appendLine("  \"runId\": ${quote(runId)},")
        appendLine("  \"caseId\": ${quote(caseId)},")
        appendLine("  \"ruleId\": ${quote(CreateCopperDataMapMigration.RULE_ID)},")
        appendLine("  \"buildRoot\": ${quote(buildRoot.toString())},")
        appendLine("  \"modDir\": ${quote(modDir.toString())},")
        appendLine("  \"runtimeLease\": ${renderRuntimeLease(runtimeLease)},")
        appendLine("  \"logs\": {")
        logs.entries.forEachIndexed { index, (name, evidence) ->
            append("    ${quote(name)}: ${renderFile(evidence)}")
            appendLine(if (index == logs.size - 1) "" else ",")
        }
        appendLine("  },")
        appendLine("  \"files\": [")
        files.forEachIndexed { index, evidence ->
            appendLine("    {")
            appendLine("      \"name\": ${quote(evidence.name)},")
            appendLine("      \"valueCount\": ${evidence.valueCount},")
            appendLine("      \"generated\": ${renderFile(evidence.generated)},")
            appendLine("      \"packaged\": ${renderFile(evidence.packaged)}")
            append("    }")
            appendLine(if (index == files.lastIndex) "" else ",")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun renderFile(evidence: FileEvidence): String =
        """{"path":${quote(evidence.path.toString())},"bytes":${evidence.bytes},"sha256":${quote(evidence.sha256)}}"""

    private fun renderRuntimeLease(evidence: RuntimeLeaseEvidence): String =
        """{"path":${quote(evidence.file.path.toString())},"bytes":${evidence.file.bytes},"sha256":${quote(evidence.file.sha256)},"sessionId":${quote(evidence.sessionId)},"runtimeDirectory":${quote(evidence.runtimeDirectory.toString())},"launcher":${renderFile(evidence.launcher)}}"""

    private fun isStrictRegularFile(path: Path): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private data class FileEvidence(
        val path: Path,
        val bytes: Long,
        val sha256: String
    )

    private data class RuntimeLeaseEvidence(
        val file: FileEvidence,
        val sessionId: String,
        val runtimeDirectory: Path,
        val launcher: FileEvidence
    )

    private data class DataMapEvidence(
        val name: String,
        val valueCount: Int,
        val generated: FileEvidence,
        val packaged: FileEvidence
    )
}
