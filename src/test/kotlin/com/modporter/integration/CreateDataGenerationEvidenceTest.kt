package com.modporter.integration

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.PassResult
import com.modporter.core.pipeline.PipelineResult
import com.modporter.core.transforms.structural.CreateCopperDataMapMigration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateDataGenerationEvidenceTest {
    @Test
    fun `requires generation only for the actual copper data-map rule`() {
        assertFalse(CreateDataGenerationEvidence.required(PipelineResult(emptyList(), dryRun = false)))
        assertFalse(CreateDataGenerationEvidence.required(resultWithRule("unrelated-rule")))
        assertTrue(
            CreateDataGenerationEvidence.required(
                resultWithRule(CreateCopperDataMapMigration.RULE_ID)
            )
        )
    }

    @Test
    fun `seals matching generated and packaged native data maps`() {
        val fixture = fixture()
        val reference = CreateDataGenerationEvidence.seal(
            runId = "strict-create-test",
            caseId = "create",
            buildRoot = fixture.modDir,
            modDir = fixture.modDir,
            reportsDir = fixture.reportsDir,
            runDataLog = fixture.runDataLog,
            runDataLeaseReceipt = fixture.runDataLeaseReceipt,
            processResourcesLog = fixture.processResourcesLog
        )

        CreateDataGenerationEvidence.verifyReference(reference, fixture.reportsDir)
        val receipt = Json.parseToJsonElement(reference.path.readText()).jsonObject
        assertEquals(CreateDataGenerationEvidence.SCHEMA, receipt["schema"].toString().trim('"'))
        assertEquals("strict-create-test", receipt["runId"].toString().trim('"'))
        assertEquals(2, receipt["files"]!!.jsonArray.size)
    }

    @Test
    fun `rejects missing empty wrong-count and generated-packaged mismatch`() {
        val missing = fixture()
        Files.delete(missing.generated("oxidizables.json"))
        assertFailsWith<IllegalArgumentException> { missing.seal() }

        val empty = fixture()
        empty.writeMap("waxables.json", 0, both = true)
        assertFailsWith<IllegalArgumentException> { empty.seal() }

        val wrongCount = fixture()
        wrongCount.writeMap("oxidizables.json", 17, both = true)
        assertFailsWith<IllegalArgumentException> { wrongCount.seal() }

        val mismatch = fixture()
        mismatch.packaged("waxables.json").writeText(dataMapJson(24, "packaged"))
        assertFailsWith<IllegalArgumentException> { mismatch.seal() }

        val changedLauncher = fixture()
        changedLauncher.runDataLauncher.writeText(
            changedLauncher.runDataLauncher.readText() + "# changed\n"
        )
        assertFailsWith<IllegalArgumentException> { changedLauncher.seal() }

        val substitutedLog = fixture()
        val compileLog = substitutedLog.reportsDir.resolve("create-compileJava.log")
        compileLog.writeText("compileJava\nBUILD SUCCESSFUL\n")
        assertFailsWith<IllegalArgumentException> {
            substitutedLog.seal(runDataLog = compileLog)
        }

        val anotherCaseOutput = fixture()
        val unrelated = anotherCaseOutput.modDir.parent.resolve("worldedit")
        anotherCaseOutput.writeMaps(unrelated)
        assertFailsWith<IllegalArgumentException> {
            anotherCaseOutput.seal(
                buildRoot = anotherCaseOutput.modDir,
                modDir = unrelated
            )
        }
    }

    @Test
    fun `rejects overwritten receipt and detects receipt tampering`() {
        val fixture = fixture()
        val reference = fixture.seal()
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { fixture.seal() }

        reference.path.writeText(reference.path.readText() + "\n")
        assertFailsWith<IllegalArgumentException> {
            CreateDataGenerationEvidence.verifyReference(reference, fixture.reportsDir)
        }
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("create-data-generation-evidence")
        val fixture = Fixture(
            modDir = root.resolve("converted"),
            reportsDir = root.resolve("selected-run/reports")
        )
        fixture.reportsDir.createDirectories()
        fixture.runDataLog.writeText("runData\nBUILD SUCCESSFUL\n")
        fixture.processResourcesLog.writeText("processResources\nBUILD SUCCESSFUL\n")
        fixture.writeRuntimeLease()
        fixture.writeMap("oxidizables.json", 18, both = true)
        fixture.writeMap("waxables.json", 24, both = true)
        return fixture
    }

    private fun resultWithRule(ruleId: String): PipelineResult =
        PipelineResult(
            passResults = listOf(
                PassResult(
                    passName = "structural",
                    changes = listOf(
                        Change(
                            file = Path.of("CreateRegistrate.java"),
                            line = 1,
                            description = "test",
                            before = "before",
                            after = "after",
                            confidence = Confidence.HIGH,
                            ruleId = ruleId
                        )
                    )
                )
            ),
            dryRun = false
        )

    private data class Fixture(
        val modDir: Path,
        val reportsDir: Path
    ) {
        val runDataLog: Path = reportsDir.resolve("create-runData.log")
        val runDataLeaseReceipt: Path = reportsDir.resolve("create-runData-session.json")
        val processResourcesLog: Path = reportsDir.resolve("create-processResources.log")
        lateinit var runDataLauncher: Path

        fun generated(name: String): Path =
            modDir.resolve("src/generated/resources/data/neoforge/data_maps/block/$name")

        fun packaged(name: String): Path =
            modDir.resolve("build/resources/main/data/neoforge/data_maps/block/$name")

        fun writeMap(name: String, count: Int, both: Boolean) {
            generated(name).parent.createDirectories()
            generated(name).writeText(dataMapJson(count))
            if (both) {
                packaged(name).parent.createDirectories()
                packaged(name).writeText(generated(name).readText())
            }
        }

        fun writeMaps(root: Path) {
            for ((name, count) in mapOf("oxidizables.json" to 18, "waxables.json" to 24)) {
                val generated = root.resolve(
                    "src/generated/resources/data/neoforge/data_maps/block/$name"
                )
                val packaged = root.resolve(
                    "build/resources/main/data/neoforge/data_maps/block/$name"
                )
                generated.parent.createDirectories()
                packaged.parent.createDirectories()
                generated.writeText(dataMapJson(count))
                packaged.writeText(generated.readText())
            }
        }

        fun writeRuntimeLease() {
            val projectRoot = modDir.toAbsolutePath().normalize()
            val runtimeDirectory = projectRoot.resolve("run")
            val source = launcherSource(projectRoot, runtimeDirectory)
            runDataLauncher = projectRoot.resolve(
                "build/minecraft-mod-testing/nested-launchers/command-${sha256(source.toByteArray())}.ps1"
            )
            runDataLauncher.parent.createDirectories()
            runDataLauncher.writeBytes(source.toByteArray())
            runDataLeaseReceipt.writeText(
                """
                {
                  "schemaVersion": 1,
                  "sessionId": "test-session",
                  "projectRoot": ${jsonString(projectRoot.toString())},
                  "runtimeDirectory": ${jsonString(runtimeDirectory.toString())},
                  "runtimeKind": "server",
                  "processStartCount": 1,
                  "processInventoryChecked": true,
                  "activeRuntimeProcesses": [],
                  "command": {
                    "executable": ${jsonString(runDataLauncher.toString())},
                    "arguments": []
                  },
                  "exitCode": 0,
                  "passed": true,
                  "failure": ""
                }
                """.trimIndent() + "\n"
            )
        }

        fun seal(
            buildRoot: Path = this.modDir,
            modDir: Path = this.modDir,
            runDataLog: Path = this.runDataLog
        ): CreateDataGenerationEvidence.ReceiptReference =
            CreateDataGenerationEvidence.seal(
                runId = "strict-create-test",
                caseId = "create",
                buildRoot = buildRoot,
                modDir = modDir,
                reportsDir = reportsDir,
                runDataLog = runDataLog,
                runDataLeaseReceipt = runDataLeaseReceipt,
                processResourcesLog = processResourcesLog
            )
    }

    companion object {
        private fun dataMapJson(count: Int, prefix: String = "entry"): String =
            buildString {
                append("{\"values\":{")
                repeat(count) { index ->
                    if (index > 0) append(',')
                    append("\"create:$prefix-$index\":{\"block\":\"create:target-$index\"}")
                }
                append("}}\n")
            }

        private fun jsonString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun launcherSource(projectRoot: Path, runtimeDirectory: Path): String =
            listOf(
                "runData",
                "--no-daemon",
                "--stacktrace",
                "--console=plain",
                "-PminecraftModTestingRuntimeDirectory=$runtimeDirectory"
            ).joinToString(",\n") { "    ${powerShellLiteral(it)}" }.let { arguments ->
                """
                    ${'$'}ErrorActionPreference = 'Stop'
                    & 'preflight.ps1' -Guard 'runtime_process_guard.ps1' -ExpectedGuardSha256 'fixture' -Scope ${powerShellLiteral(projectRoot.toString())}
                    if (${'$'}LASTEXITCODE -ne 0) { exit ${'$'}LASTEXITCODE }
                    ${'$'}modporterExecutable = ${powerShellLiteral(projectRoot.resolve("gradlew.bat").toString())}
                    ${'$'}modporterArguments = @(
                    $arguments
                    )
                    & 'hidden-desktop.ps1' -Executable ${'$'}modporterExecutable -ArgumentList ${'$'}modporterArguments -DesktopName "modporter-hidden-${'$'}PID"
                    if (${'$'}null -ne ${'$'}LASTEXITCODE) { exit ${'$'}LASTEXITCODE }
                """.trimIndent() + "\n"
            }

        private fun powerShellLiteral(value: String): String =
            "'${value.replace("'", "''")}'"

        private fun sha256(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
