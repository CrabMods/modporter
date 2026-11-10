package com.modporter.integration

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyCapabilityNativeRuntimeValidationTest {
    @Test
    fun `profile absence does not create a native request`(@TempDir root: Path) {
        val project = root.resolve("empty").also { it.createDirectories() }
        val prepared = LegacyCapabilityNativeRuntimeValidation.prepare(
            project,
            strictReviewRequest = null,
            harnessPackage = HARNESS_PACKAGE
        )

        assertNull(prepared.request)
        assertTrue(prepared.issues.isEmpty(), prepared.issues.joinToString("\n"))
    }

    @Test
    fun `preparation and complete bound evidence validate`(@TempDir root: Path) {
        val fixture = fixture(root)
        val request = fixture.request

        assertEquals(COMPAT_PACKAGE, request.compatPackage)
        assertEquals(HARNESS_PACKAGE, request.harnessPackage)
        assertEquals(CONFIG_FILE, request.mixinConfigResource)
        assertEquals(sha256(fixture.configSource), request.mixinConfigSha256)
        assertEquals(
            sha256(
                LegacyCapabilityNativeRuntimeHarnessSource.source(HARNESS_PACKAGE, COMPAT_PACKAGE)
                    .toByteArray(StandardCharsets.UTF_8)
            ),
            request.harnessSourceSha256
        )

        val result = fixture.verify()
        assertEquals("VALIDATED", result.state, result.issues.joinToString("\n"))
        assertTrue(result.issues.isEmpty(), result.issues.joinToString("\n"))
        assertEquals(LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID, result.profileId)
        assertEquals(request.receiptPath.toString(), result.receiptPath)
        assertEquals(sha256(request.receiptPath), result.receiptSha256)
        assertEquals(request.tracePath.toString(), result.tracePath)
        assertEquals(sha256(request.tracePath), result.traceSha256)
        assertEquals(request.wrapperReceiptPath.toString(), result.wrapperReceiptPath)
        assertEquals(sha256(request.wrapperReceiptPath), result.wrapperReceiptSha256)
        assertEquals(sha256(fixture.runtimeLog), result.runtimeLogSha256)
    }

    @Test
    fun `preparation refuses unowned source config and stale evidence`(@TempDir root: Path) {
        val badDeclaration = preparedProject(root.resolve("declaration"))
        badDeclaration.extensionMixin.writeText(
            "package $COMPAT_PACKAGE.mixin; public abstract class LegacyCapabilityLevelExtensionMixin {}\n"
        )
        val declarationResult = LegacyCapabilityNativeRuntimeValidation.prepare(
            badDeclaration.project,
            badDeclaration.strict,
            HARNESS_PACKAGE
        )
        assertNull(declarationResult.request)
        assertIssue(declarationResult.issues, "lacks declaration 'public interface")

        val badConfig = preparedProject(root.resolve("config"))
        badConfig.config.writeText(configSource(requiredMixins.reversed()))
        val configResult = LegacyCapabilityNativeRuntimeValidation.prepare(
            badConfig.project,
            badConfig.strict,
            HARNESS_PACKAGE
        )
        assertNull(configResult.request)
        assertIssue(configResult.issues, "mixinConfig.mixins")

        val fixture = fixture(root.resolve("stale"))
        val stale = LegacyCapabilityNativeRuntimeValidation.prepare(
            fixture.project,
            fixture.strict,
            HARNESS_PACKAGE
        )
        assertNull(stale.request)
        assertIssue(stale.issues, "runtime evidence path already exists")
    }

    @Test
    fun `canonical source config and runtime class drift are refused`(@TempDir root: Path) {
        val forged = fixture(root.resolve("forged"))
        val forgedRequest = forged.request.copy(harnessSourceSha256 = "0".repeat(64))
        val forgedResult = forged.verify(forgedRequest)
        assertEquals("FAILED", forgedResult.state)
        assertNull(forgedResult.profileId)
        assertNull(forgedResult.receiptSha256)
        assertNull(forgedResult.traceSha256)
        assertNull(forgedResult.wrapperReceiptSha256)
        assertNull(forgedResult.runtimeLogSha256)
        assertIssue(forgedResult.issues, "expected canonical")

        val configDrift = fixture(root.resolve("config-drift"))
        configDrift.configSource.writeText(configDrift.configSource.toFile().readText() + "\n")
        val configResult = configDrift.verify()
        assertEquals("FAILED", configResult.state)
        assertIssue(configResult.issues, "retained mixin config source hash")

        val classDrift = fixture(root.resolve("class-drift"))
        classDrift.helperClass.writeBytes("changed-class".toByteArray())
        val classResult = classDrift.verify()
        assertEquals("FAILED", classResult.state)
        assertIssue(classResult.issues, "runtime class helperClassSha256 hash")
    }

    @Test
    fun `scenario cleanup and malformed primitive evidence fail without throwing`(@TempDir root: Path) {
        val semantic = fixture(root.resolve("semantic"))
        semantic.mutateTrace { trace ->
            val scenarios = trace.getValue("scenarios") as JsonArray
            val first = scenarios.first().jsonObject
            val altered = JsonObject(first + ("ordinal" to JsonPrimitive(4)))
            JsonObject(trace + ("scenarios" to JsonArray(listOf(altered) + scenarios.drop(1))))
        }
        val semanticResult = semantic.verify()
        assertEquals("FAILED", semanticResult.state)
        assertIssue(semanticResult.issues, "scenario[0].ordinal")

        val malformed = fixture(root.resolve("malformed"))
        malformed.mutateTrace { trace ->
            val cleanup = trace.getValue("cleanup").jsonObject
            val malformedCleanup = JsonObject(
                cleanup +
                    ("clientFenceTicks" to JsonPrimitive("3")) +
                    ("unexpected" to JsonPrimitive(true))
            )
            JsonObject(trace + ("cleanup" to malformedCleanup))
        }
        val malformedResult = runCatching { malformed.verify() }
        assertTrue(malformedResult.isSuccess, malformedResult.exceptionOrNull()?.stackTraceToString())
        assertEquals("FAILED", malformedResult.getOrThrow().state)
        assertIssue(malformedResult.getOrThrow().issues, "cleanup keys differ")
        assertIssue(malformedResult.getOrThrow().issues, "client cleanup fence")

        val wrongRootType = fixture(root.resolve("wrong-root"))
        wrongRootType.receipt.writeText("[]")
        val wrongRootResult = runCatching { wrongRootType.verify() }
        assertTrue(wrongRootResult.isSuccess, wrongRootResult.exceptionOrNull()?.stackTraceToString())
        assertEquals("FAILED", wrongRootResult.getOrThrow().state)
        assertIssue(wrongRootResult.getOrThrow().issues, "Could not parse native capability receipt")
    }

    @Test
    fun `old single notice chunk evidence is refused`(@TempDir root: Path) {
        val oldEvidence = fixture(root.resolve("old-single-notice-chunks"))
        oldEvidence.mutateTrace { trace ->
            val scenarios = (trace.getValue("scenarios") as JsonArray).mapIndexed { index, element ->
                val scenario = element.jsonObject
                when (index) {
                    1 -> JsonObject(
                        scenario +
                            ("nativeNotices" to JsonPrimitive(1)) +
                            ("legacyNotices" to JsonPrimitive(1))
                    )
                    4 -> JsonObject(scenario + ("legacyNotices" to JsonPrimitive(1)))
                    else -> scenario
                }
            }
            JsonObject(trace + ("scenarios" to JsonArray(scenarios)))
        }
        oldEvidence.mutateReceipt { receipt ->
            JsonObject(
                receipt +
                    ("serverNativeNotices" to JsonPrimitive(3)) +
                    ("serverLegacyNotices" to JsonPrimitive(3)) +
                    ("clientLegacyNotices" to JsonPrimitive(3))
            )
        }

        val result = oldEvidence.verify()
        assertEquals("FAILED", result.state)
        assertIssue(result.issues, "serverNativeNotices")
        assertIssue(result.issues, "serverLegacyNotices")
        assertIssue(result.issues, "clientLegacyNotices")
        assertIssue(result.issues, "scenario[1].nativeNotices")
        assertIssue(result.issues, "scenario[1].legacyNotices")
        assertIssue(result.issues, "scenario[4].legacyNotices")
    }

    @Test
    fun `wrapper process leakage and unbound log seal are refused`(@TempDir root: Path) {
        val wrapperLeak = fixture(root.resolve("wrapper"))
        wrapperLeak.mutateWrapper { wrapper ->
            JsonObject(
                wrapper + (
                    "activeRuntimeProcesses" to
                        JsonArray(listOf(buildJsonObject { put("processId", 1234) }))
                    )
            )
        }
        val wrapperResult = wrapperLeak.verify()
        assertEquals("FAILED", wrapperResult.state)
        assertIssue(wrapperResult.issues, "activeRuntimeProcesses must be an empty")

        val copiedSession = fixture(root.resolve("copied-session"))
        copiedSession.mutateReceipt { receipt ->
            JsonObject(
                receipt + ("sessionId" to JsonPrimitive(copiedSession.request.validationSessionId))
            )
        }
        val copiedResult = copiedSession.verify()
        assertEquals("FAILED", copiedResult.state)
        assertIssue(copiedResult.issues, "reused validationSessionId")

        val logDrift = fixture(root.resolve("log"))
        logDrift.runtimeLog.writeText(
            logDrift.runtimeLog.toFile().readText()
                .replace("[ModPorterCapabilityProbe] Server PASS fixture\n", "")
        )
        val logResult = logDrift.verify()
        assertEquals("FAILED", logResult.state)
        assertIssue(logResult.issues, "exactly one marker")
    }

    private data class PreparedProject(
        val project: Path,
        val evidence: Path,
        val runtime: Path,
        val config: Path,
        val extensionMixin: Path,
        val strict: StrictReviewRuntimeRequest
    )

    private inner class Fixture(
        val project: Path,
        val evidence: Path,
        val strict: StrictReviewRuntimeRequest,
        val request: LegacyCapabilityNativeRuntimeRequest,
        val configSource: Path,
        val helperClass: Path,
        val receipt: Path,
        val trace: Path,
        val wrapper: Path,
        val runtimeLog: Path
    ) {
        fun verify(
            actualRequest: LegacyCapabilityNativeRuntimeRequest = request
        ): LegacyCapabilityNativeValidation = LegacyCapabilityNativeRuntimeValidation.verify(
            actualRequest,
            runtimePassed = true,
            runtimeNote = "fixture",
            runtimeLog = runtimeLog
        )

        fun mutateTrace(change: (JsonObject) -> JsonObject) {
            trace.writeText(change(readObject(trace)).toString())
            resealReceiptAndLog()
        }

        fun mutateReceipt(change: (JsonObject) -> JsonObject) {
            receipt.writeText(change(readObject(receipt)).toString())
            rewriteLog()
        }

        fun mutateWrapper(change: (JsonObject) -> JsonObject) {
            wrapper.writeText(change(readObject(wrapper)).toString())
        }

        private fun resealReceiptAndLog() {
            val current = readObject(receipt)
            receipt.writeText(
                JsonObject(current + ("traceSha256" to JsonPrimitive(sha256(trace)))).toString()
            )
            rewriteLog()
        }

        private fun rewriteLog() {
            runtimeLog.writeText(runtimeLogSource(request, sha256(receipt), sha256(trace)))
        }
    }

    private fun fixture(root: Path): Fixture {
        val prepared = preparedProject(root)
        val preparation = LegacyCapabilityNativeRuntimeValidation.prepare(
            prepared.project,
            prepared.strict,
            HARNESS_PACKAGE
        )
        val request = assertNotNull(
            preparation.request,
            preparation.issues.joinToString("\n")
        )
        assertTrue(preparation.issues.isEmpty(), preparation.issues.joinToString("\n"))

        val harness = prepared.project.resolve("src/main/java")
            .resolve(HARNESS_PACKAGE.replace('.', '/'))
            .resolve(LegacyCapabilityNativeRuntimeHarnessSource.FILE_NAME)
        harness.parent.createDirectories()
        harness.writeText(
            LegacyCapabilityNativeRuntimeHarnessSource.source(HARNESS_PACKAGE, COMPAT_PACKAGE)
        )

        val classes = prepared.project.resolve("build/classes/java/main")
        val helperClass = classes.resolve(
            COMPAT_PACKAGE.replace('.', '/') + "/LegacyBlockEntityCapabilityQueries.class"
        )
        val serverClass = classes.resolve(
            COMPAT_PACKAGE.replace('.', '/') + "/mixin/LegacyCapabilityServerLevelMixin.class"
        )
        val extensionClass = classes.resolve(
            COMPAT_PACKAGE.replace('.', '/') + "/mixin/LegacyCapabilityLevelExtensionMixin.class"
        )
        val harnessClass = classes.resolve(
            HARNESS_PACKAGE.replace('.', '/') + "/ModPorterLegacyCapabilityNativeHarness.class"
        )
        linkedMapOf(
            helperClass to "helper-class",
            serverClass to "server-mixin-class",
            extensionClass to "extension-mixin-class",
            harnessClass to "harness-class"
        ).forEach { (path, content) ->
            path.parent.createDirectories()
            path.writeBytes(content.toByteArray())
        }
        val runtimeConfig = prepared.project.resolve("build/resources/main/$CONFIG_FILE")
        runtimeConfig.parent.createDirectories()
        runtimeConfig.writeBytes(prepared.config.readBytes())

        val artifactHashes = buildJsonObject {
            put("helperClassSha256", sha256(helperClass))
            put("serverMixinClassSha256", sha256(serverClass))
            put("levelExtensionMixinClassSha256", sha256(extensionClass))
            put("harnessClassSha256", sha256(harnessClass))
            put("mixinConfigSha256", request.mixinConfigSha256)
        }
        val wrapperSession = UUID.randomUUID().toString()
        val scenarios = JsonArray(expectedScenarios.mapIndexed { index, id ->
            buildJsonObject {
                put("id", id)
                put("side", if (index < 3) "server" else "client")
                put("ordinal", index)
                put("startTick", (100 + index).toLong())
                put("endTick", (100 + index).toLong())
                put(
                    "nativeNotices",
                    when (index) {
                        1 -> 2
                        in 0..2 -> 1
                        else -> 0
                    }
                )
                put("legacyNotices", if (index == 1 || index == 4) 2 else 1)
                put("replacementIdentityPassed", true)
                put("result", "PASS")
            }
        })
        val traceRoot = buildJsonObject {
            put("schemaVersion", 1)
            put("artifactSchema", LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID + ".trace")
            put("profileId", LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID)
            put("sessionId", wrapperSession)
            put("validationSessionId", request.validationSessionId)
            put("requestedScenarios", strings(expectedScenarios))
            put("observedScenarios", strings(expectedScenarios))
            put("scenarios", scenarios)
            putJsonObject("cleanup") {
                put("serverRestorePassed", true)
                put("serverRestoreTick", 200L)
                put("clientBaselineObserved", true)
                put("clientFenceStartTick", 300L)
                put("clientFenceEndTick", 302L)
                put("clientFenceTicks", 3)
                put("originalBlockEntityPresent", false)
            }
            put("artifactHashes", artifactHashes)
            put("status", "PASS")
        }
        request.tracePath.writeText(traceRoot.toString())

        val receiptRoot = buildJsonObject {
            put("schemaVersion", 1)
            put("artifactSchema", LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID + ".receipt")
            put("profileId", LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID)
            put("sessionId", wrapperSession)
            put("validationSessionId", request.validationSessionId)
            put("processStartCount", 1)
            put("worldLoadCount", 1)
            put("scenarioCount", expectedScenarios.size)
            put("failedCount", 0)
            put("cleanupFailureCount", 0)
            put("allScenariosPassed", true)
            put("requestedScenarios", strings(expectedScenarios))
            put("observedScenarios", strings(expectedScenarios))
            put("serverNativeNotices", 4)
            put("serverLegacyNotices", 4)
            put("clientLegacyNotices", 4)
            put("cleanupPassed", true)
            put("tracePath", request.tracePath.toString())
            put("traceSha256", sha256(request.tracePath))
            put("artifactHashes", artifactHashes)
            put("status", "PASS")
        }
        request.receiptPath.writeText(receiptRoot.toString())

        val strictChild = prepared.evidence.resolve("strict-coordinator-receipt.json")
        strictChild.writeText("{\"status\":\"PASS\"}\n")
        val wrapperRoot = buildJsonObject {
            put("schemaVersion", 1)
            put("sessionId", wrapperSession)
            put("projectRoot", prepared.project.toString())
            put("runtimeDirectory", prepared.runtime.toString())
            put("runtimeDirectorySha256", "1".repeat(64))
            put("runtimeKind", "client")
            put("evidenceMode", "harness")
            put("harnessContractRequested", true)
            put("processStartCount", 1)
            put("processInventoryChecked", true)
            put("activeRuntimeProcesses", JsonArray(emptyList()))
            put("reconciledExitedProcessIds", JsonArray(emptyList()))
            put("preRunRuntimeProcesses", JsonArray(emptyList()))
            put("postRunRuntimeProcesses", JsonArray(emptyList()))
            putJsonObject("command") {
                put("executable", "java")
                put("arguments", JsonArray(emptyList()))
                put("processId", 42)
                put("started", true)
                put("exited", true)
                put("timedOut", false)
                put("killedOwnedProcessIds", JsonArray(emptyList()))
            }
            putJsonObject("expected") {
                put("scenarioCount", 1)
                put("scenarioIds", strings(listOf("strict-spawn")))
                put("processStartCount", 1)
                put("worldLoadCount", 1)
            }
            put("exitCode", 0)
            put("runtimeExitValidated", true)
            put("harnessReceiptPath", strictChild.toString())
            put("harnessReceiptSha256", sha256(strictChild))
            put("harnessValidated", true)
            put("passed", true)
            put("failure", "")
        }
        request.wrapperReceiptPath.writeText(wrapperRoot.toString())

        val runtimeLog = prepared.evidence.resolve("runtime.log")
        runtimeLog.writeText(
            runtimeLogSource(request, sha256(request.receiptPath), sha256(request.tracePath))
        )
        return Fixture(
            project = prepared.project,
            evidence = prepared.evidence,
            strict = prepared.strict,
            request = request,
            configSource = prepared.config,
            helperClass = helperClass,
            receipt = request.receiptPath,
            trace = request.tracePath,
            wrapper = request.wrapperReceiptPath,
            runtimeLog = runtimeLog
        )
    }

    private fun preparedProject(root: Path): PreparedProject {
        root.createDirectories()
        val project = root.resolve("converted").also { it.createDirectories() }
        val javaRoot = project.resolve("src/main/java")
        val compatDir = javaRoot.resolve(COMPAT_PACKAGE.replace('.', '/'))
        compatDir.createDirectories()
        compatDir.resolve("LegacyBlockEntityCapabilityQueries.java").writeText(
            "package $COMPAT_PACKAGE; public final class LegacyBlockEntityCapabilityQueries {}\n"
        )
        val mixinDir = compatDir.resolve("mixin").also { it.createDirectories() }
        mixinDir.resolve("LegacyCapabilityServerLevelMixin.java").writeText(
            "package $COMPAT_PACKAGE.mixin; public abstract class LegacyCapabilityServerLevelMixin {}\n"
        )
        val extensionMixin = mixinDir.resolve("LegacyCapabilityLevelExtensionMixin.java")
        extensionMixin.writeText(
            "package $COMPAT_PACKAGE.mixin; public interface LegacyCapabilityLevelExtensionMixin {}\n"
        )
        val config = project.resolve("src/main/resources/$CONFIG_FILE")
        config.parent.createDirectories()
        config.writeText(configSource(requiredMixins))

        val evidence = root.resolve("evidence").also { it.createDirectories() }.toAbsolutePath().normalize()
        val runtime = root.resolve("runtime").also { it.createDirectories() }.toAbsolutePath().normalize()
        val repository = root.resolve("repository").also { it.createDirectories() }
        val profile = StrictSpawnSourceProfile(
            repositoryRoot = repository,
            inputProjectDir = root.resolve("input"),
            convertedBuildRoot = project,
            convertedModDir = project,
            inputPackageEntity = root.resolve("input/PackageEntity.java"),
            generatedPackageEntity = compatDir.resolve("PackageEntity.java"),
            accessTransformer = project.resolve("src/main/resources/META-INF/accesstransformer.cfg"),
            buildFiles = listOf(project.resolve("build.gradle")),
            gradleProperties = project.resolve("gradle.properties"),
            ruleSource = repository.resolve("Rule.kt"),
            ruleTests = repository.resolve("RuleTest.kt"),
            sourceLabel = "fixture",
            change = Change(
                file = root.resolve("input/PackageEntity.java"),
                line = 1,
                description = "fixture",
                before = "before",
                after = "after",
                confidence = Confidence.MEDIUM,
                ruleId = "fixture"
            ),
            productBindings = emptyMap()
        )
        val strict = StrictReviewRuntimeRequest(
            profileId = "fixture-profile",
            validationSessionId = UUID.randomUUID().toString(),
            manifestPath = evidence.resolve("strict-manifest.json"),
            receiptPath = evidence.resolve("strict-receipt.json"),
            wrapperReceiptPath = evidence.resolve("managed-wrapper.json"),
            evidenceDir = evidence,
            runtimeDirectory = runtime,
            profile = profile,
            productBindings = emptyMap()
        )
        return PreparedProject(project, evidence, runtime, config, extensionMixin, strict)
    }

    private fun configSource(mixins: List<String>): String = buildJsonObject {
        put("required", true)
        put("minVersion", "0.8")
        put("package", "$COMPAT_PACKAGE.mixin")
        put("compatibilityLevel", "JAVA_21")
        putJsonArray("mixins") {
            mixins.forEach { add(JsonPrimitive(it)) }
        }
        putJsonObject("injectors") {
            put("defaultRequire", 1)
        }
    }.toString()

    private fun strings(values: List<String>): JsonArray =
        JsonArray(values.map(::JsonPrimitive))

    private fun readObject(path: Path): JsonObject =
        Json.parseToJsonElement(path.toFile().readText()).jsonObject

    private fun runtimeLogSource(
        request: LegacyCapabilityNativeRuntimeRequest,
        receiptHash: String,
        traceHash: String
    ): String = """
        [ModPorterCapabilityProbe] Server PASS fixture
        [ModPorterCapabilityProbe] Client PASS fixture
        [ModPorterCapabilityProbe] Server cleanup PASS fixture
        [ModPorterCapabilityProbe] Receipt sealed path=${request.receiptPath} sha256=$receiptHash traceSha256=$traceHash status=PASS
    """.trimIndent() + "\n"

    private fun assertIssue(issues: List<String>, expected: String) {
        assertTrue(
            issues.any { expected in it },
            "Expected issue containing '$expected', found:\n${issues.joinToString("\n")}"
        )
    }

    private fun sha256(path: Path): String = sha256(path.readBytes())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val COMPAT_PACKAGE = "com.modporter.generated.create.compat"
        const val HARNESS_PACKAGE = "com.modporter.generated.create.benchmark"
        const val CONFIG_FILE = "modporter-legacy-capability-query.mixins.json"
        val requiredMixins = listOf(
            "LegacyCapabilityServerLevelMixin",
            "LegacyCapabilityLevelExtensionMixin"
        )
        val expectedScenarios = listOf(
            "server-position-native",
            "server-chunk-nested",
            "server-chunk-exception",
            "client-position-default",
            "client-chunk-nested",
            "client-chunk-exception"
        )
    }
}
