package com.modporter.integration

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal data class LegacyCapabilityNativeRuntimeRequest(
    val validationSessionId: String,
    val evidenceDir: Path,
    val receiptPath: Path,
    val tracePath: Path,
    val wrapperReceiptPath: Path,
    val convertedModDir: Path,
    val runtimeDirectory: Path,
    val compatPackage: String,
    val harnessPackage: String,
    val harnessSourceSha256: String,
    val mixinConfigResource: String,
    val mixinConfigSha256: String
)

internal data class LegacyCapabilityNativePreparation(
    val request: LegacyCapabilityNativeRuntimeRequest?,
    val issues: List<String>
)

data class LegacyCapabilityNativeValidation(
    val state: String,
    val issues: List<String>,
    val profileId: String? = null,
    val receiptPath: String? = null,
    val receiptSha256: String? = null,
    val tracePath: String? = null,
    val traceSha256: String? = null,
    val wrapperReceiptPath: String? = null,
    val wrapperReceiptSha256: String? = null,
    val runtimeLogSha256: String? = null
) {
    fun toTelemetryMap(): Map<String, Any?> = linkedMapOf(
        "state" to state,
        "issues" to issues,
        "profileId" to profileId,
        "receiptPath" to receiptPath,
        "receiptSha256" to receiptSha256,
        "tracePath" to tracePath,
        "traceSha256" to traceSha256,
        "wrapperReceiptPath" to wrapperReceiptPath,
        "wrapperReceiptSha256" to wrapperReceiptSha256,
        "runtimeLogSha256" to runtimeLogSha256
    )

    companion object {
        fun notEvaluated(): LegacyCapabilityNativeValidation =
            LegacyCapabilityNativeValidation("NOT_EVALUATED", emptyList())
    }
}

internal object LegacyCapabilityNativeRuntimeValidation {
    private const val MIXIN_CONFIG_FILE = "modporter-legacy-capability-query.mixins.json"
    private val json = Json { ignoreUnknownKeys = false }
    private val sha256 = Regex("^[0-9a-f]{64}$")
    private val javaPackage =
        Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$")
    private val generatedCompatPackage =
        Regex("^com\\.modporter\\.generated\\.[A-Za-z_][A-Za-z0-9_]*\\.compat$")
    private val packageDeclaration =
        Regex("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;")
    private val requiredMixinNames = listOf(
        "LegacyCapabilityServerLevelMixin",
        "LegacyCapabilityLevelExtensionMixin"
    )
    private val expectedArtifactKeys = setOf(
        "helperClassSha256",
        "serverMixinClassSha256",
        "levelExtensionMixinClassSha256",
        "harnessClassSha256",
        "mixinConfigSha256"
    )
    private val expectedScenarios = listOf(
        "server-position-native",
        "server-chunk-nested",
        "server-chunk-exception",
        "client-position-default",
        "client-chunk-nested",
        "client-chunk-exception"
    )
    private val expectedReceiptKeys = setOf(
        "schemaVersion", "artifactSchema", "profileId", "sessionId", "validationSessionId",
        "processStartCount", "worldLoadCount", "scenarioCount", "failedCount",
        "cleanupFailureCount", "allScenariosPassed", "requestedScenarios", "observedScenarios",
        "serverNativeNotices", "serverLegacyNotices", "clientLegacyNotices", "cleanupPassed",
        "tracePath", "traceSha256", "artifactHashes", "status"
    )
    private val expectedTraceKeys = setOf(
        "schemaVersion", "artifactSchema", "profileId", "sessionId", "validationSessionId",
        "requestedScenarios", "observedScenarios", "scenarios", "cleanup", "artifactHashes", "status"
    )
    private val expectedScenarioKeys = setOf(
        "id", "side", "ordinal", "startTick", "endTick", "nativeNotices",
        "legacyNotices", "replacementIdentityPassed", "result"
    )
    private val expectedCleanupKeys = setOf(
        "serverRestorePassed", "serverRestoreTick", "clientBaselineObserved",
        "clientFenceStartTick", "clientFenceEndTick", "clientFenceTicks",
        "originalBlockEntityPresent"
    )
    private val expectedMixinConfigKeys = setOf(
        "required", "minVersion", "package", "compatibilityLevel", "mixins", "injectors"
    )

    fun prepare(
        projectDir: Path,
        strictReviewRequest: StrictReviewRuntimeRequest?,
        harnessPackage: String
    ): LegacyCapabilityNativePreparation {
        val issues = mutableListOf<String>()
        val normalizedProject = projectDir.toAbsolutePath().normalize()
        val javaRoot = normalizedProject.resolve("src/main/java")
        val resourcesRoot = normalizedProject.resolve("src/main/resources")
        val helpers = regularFiles(javaRoot) { path ->
            path.fileName.toString() == "LegacyBlockEntityCapabilityQueries.java"
        }
        val mixinSources = regularFiles(javaRoot) { path ->
            path.fileName.toString().removeSuffix(".java") in requiredMixinNames
        }
        val retainedCallSites = regularFiles(javaRoot) { path ->
            path.fileName.toString().endsWith(".java") &&
                path.fileName.toString() !in setOf(
                    "LegacyBlockEntityCapabilityQueries.java",
                    "LegacyCapabilityServerLevelMixin.java",
                    "LegacyCapabilityLevelExtensionMixin.java"
                ) &&
                runCatching {
                    path.readText().contains("LegacyBlockEntityCapabilityQueries.getRetained(")
                }.getOrDefault(false)
        }
        val config = resourcesRoot.resolve(MIXIN_CONFIG_FILE).toAbsolutePath().normalize()
        val retainedShapePresent = retainedCallSites.isNotEmpty() ||
            mixinSources.isNotEmpty() || Files.exists(config, LinkOption.NOFOLLOW_LINKS)
        if (!retainedShapePresent) {
            return LegacyCapabilityNativePreparation(null, emptyList())
        }

        if (helpers.size != 1) {
            issues.add(
                "Native capability runtime profile requires exactly one generated " +
                    "LegacyBlockEntityCapabilityQueries.java; found ${helpers.size}: " +
                    helpers.joinToString()
            )
        }
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(config)) {
            issues.add("Native capability runtime profile requires one regular retained mixin config: $config")
        }
        if (strictReviewRequest == null) {
            issues.add(
                "Native capability runtime profile requires a strict review runtime request " +
                    "to bind immutable evidence"
            )
        }
        if (!javaPackage.matches(harnessPackage)) {
            issues.add("Native capability harness package is invalid: $harnessPackage")
        }
        if (issues.isNotEmpty()) {
            return LegacyCapabilityNativePreparation(null, issues)
        }

        val helper = helpers.single()
        val helperText = runCatching { helper.readText() }.getOrElse { error ->
            return LegacyCapabilityNativePreparation(
                null,
                listOf("Could not read generated capability helper $helper: ${error.message}")
            )
        }
        val compatPackage = packageDeclaration.find(helperText)?.groupValues?.get(1)
        if (compatPackage == null) {
            return LegacyCapabilityNativePreparation(
                null,
                listOf("Generated capability helper has no package declaration: $helper")
            )
        }
        if (!generatedCompatPackage.matches(compatPackage)) {
            issues.add("Generated capability helper package is not an owned compat package: $compatPackage")
        }
        val expectedHarnessPackage = compatPackage.removeSuffix(".compat") + ".benchmark"
        if (harnessPackage != expectedHarnessPackage) {
            issues.add(
                "Native capability harness package $harnessPackage does not match " +
                    "the generated compat package $compatPackage"
            )
        }
        val expectedHelper = javaRoot.resolve(compatPackage.replace('.', '/'))
            .resolve("LegacyBlockEntityCapabilityQueries.java")
            .toAbsolutePath()
            .normalize()
        if (helper != expectedHelper) {
            issues.add("Generated capability helper path does not match its package: $helper")
        }

        val mixinDir = helper.parent.resolve("mixin")
        val requiredSources = requiredMixinNames.associateWith { name ->
            mixinDir.resolve("$name.java").toAbsolutePath().normalize()
        }
        val unexpectedSources = mixinSources.filterNot(requiredSources.values::contains)
        if (unexpectedSources.isNotEmpty()) {
            issues.add(
                "Native capability mixin sources are not owned by the helper package: " +
                    unexpectedSources.joinToString()
            )
        }
        requiredSources.forEach { (name, source) ->
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
                issues.add("Native capability runtime profile is missing generated mixin source $source")
            } else {
                val sourceText = runCatching { source.readText() }.getOrElse { error ->
                    issues.add("Could not read generated mixin source $source: ${error.message}")
                    ""
                }
                val declaredPackage = packageDeclaration.find(sourceText)?.groupValues?.get(1)
                if (declaredPackage != "$compatPackage.mixin") {
                    issues.add(
                        "Generated mixin $name declares package $declaredPackage; " +
                            "expected $compatPackage.mixin"
                    )
                }
                val expectedDeclaration = if (name == "LegacyCapabilityServerLevelMixin") {
                    "public abstract class $name"
                } else {
                    "public interface $name"
                }
                if (!sourceText.contains(expectedDeclaration)) {
                    issues.add("Generated mixin $name lacks declaration '$expectedDeclaration'")
                }
            }
        }

        val configRoot = parseObject(config, "mixin config", issues)
        if (configRoot != null) {
            verifyMixinConfig(configRoot, compatPackage, issues)
        }
        if (issues.isNotEmpty()) {
            return LegacyCapabilityNativePreparation(null, issues)
        }

        val strict = checkNotNull(strictReviewRequest)
        if (strict.profile.convertedModDir.toAbsolutePath().normalize() != normalizedProject) {
            issues.add("Native capability converted mod directory differs from the strict review profile")
        }
        if (runCatching { UUID.fromString(strict.validationSessionId) }.isFailure) {
            issues.add("Native capability strict review validationSessionId is not a UUID")
        }
        val evidenceDir = strict.evidenceDir.toAbsolutePath().normalize()
        if (!Files.isDirectory(evidenceDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(evidenceDir)) {
            issues.add("Native capability evidence directory is missing or linked: $evidenceDir")
        }
        val receipt = evidenceDir.resolve(
            LegacyCapabilityNativeRuntimeHarnessSource.RECEIPT_FILE_NAME
        ).normalize()
        val trace = evidenceDir.resolve(
            LegacyCapabilityNativeRuntimeHarnessSource.TRACE_FILE_NAME
        ).normalize()
        val wrapperReceipt = strict.wrapperReceiptPath.toAbsolutePath().normalize()
        listOf(receipt, trace, wrapperReceipt)
            .filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            .forEach { stale ->
                issues.add("Native capability runtime evidence path already exists: $stale")
            }
        val resource = resourcesRoot.relativize(config).joinToString("/") { it.toString() }
        val harnessSource = LegacyCapabilityNativeRuntimeHarnessSource.source(
            harnessPackage,
            compatPackage
        )
        return LegacyCapabilityNativePreparation(
            request = if (issues.isEmpty()) {
                LegacyCapabilityNativeRuntimeRequest(
                    validationSessionId = strict.validationSessionId,
                    evidenceDir = evidenceDir,
                    receiptPath = receipt,
                    tracePath = trace,
                    wrapperReceiptPath = wrapperReceipt,
                    convertedModDir = normalizedProject,
                    runtimeDirectory = strict.runtimeDirectory.toAbsolutePath().normalize(),
                    compatPackage = compatPackage,
                    harnessPackage = harnessPackage,
                    harnessSourceSha256 = digest(harnessSource.toByteArray(StandardCharsets.UTF_8)),
                    mixinConfigResource = resource,
                    mixinConfigSha256 = digest(config)
                )
            } else {
                null
            },
            issues = issues
        )
    }

    fun verify(
        request: LegacyCapabilityNativeRuntimeRequest,
        runtimePassed: Boolean,
        runtimeNote: String,
        runtimeLog: Path
    ): LegacyCapabilityNativeValidation {
        val issues = mutableListOf<String>()
        if (!runtimePassed) {
            issues.add("Native capability runtime task failed: $runtimeNote")
        }
        verifyRequestPaths(request, issues)
        verifyStagedInputs(request, issues)

        val receipt = parseRequiredObject(request.receiptPath, "receipt", issues)
        val trace = parseRequiredObject(request.tracePath, "trace", issues)
        val wrapper = parseRequiredObject(request.wrapperReceiptPath, "managed wrapper receipt", issues)
        if (receipt == null || trace == null || wrapper == null) {
            return LegacyCapabilityNativeValidation("FAILED", issues)
        }

        requireExactKeys(receipt, expectedReceiptKeys, "receipt", issues)
        requireExactKeys(trace, expectedTraceKeys, "trace", issues)
        requireInt(receipt, "schemaVersion", 1, issues)
        requireString(
            receipt,
            "artifactSchema",
            LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID + ".receipt",
            issues,
            "receipt"
        )
        requireInt(trace, "schemaVersion", 1, issues, "trace")
        requireString(
            trace,
            "artifactSchema",
            LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID + ".trace",
            issues,
            "trace"
        )
        requireString(receipt, "profileId", LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID, issues, "receipt")
        requireString(trace, "profileId", LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID, issues, "trace")
        requireString(receipt, "validationSessionId", request.validationSessionId, issues, "receipt")
        requireString(trace, "validationSessionId", request.validationSessionId, issues, "trace")
        if (runCatching { UUID.fromString(request.validationSessionId) }.isFailure) {
            issues.add("Native capability validationSessionId is not a UUID")
        }

        val wrapperSession = string(receipt, "sessionId")
        if (wrapperSession == null || runCatching { UUID.fromString(wrapperSession) }.isFailure) {
            issues.add("Native capability receipt sessionId is not a UUID")
        } else {
            if (wrapperSession == request.validationSessionId) {
                issues.add("Native capability wrapper sessionId reused validationSessionId")
            }
            requireString(trace, "sessionId", wrapperSession, issues, "trace")
            verifyWrapperReceipt(request, wrapper, wrapperSession, issues)
        }

        requireString(receipt, "status", "PASS", issues, "receipt")
        requireString(trace, "status", "PASS", issues, "trace")
        requireInt(receipt, "processStartCount", 1, issues)
        requireInt(receipt, "worldLoadCount", 1, issues)
        requireInt(receipt, "scenarioCount", expectedScenarios.size, issues)
        requireInt(receipt, "failedCount", 0, issues)
        requireInt(receipt, "cleanupFailureCount", 0, issues)
        requireInt(receipt, "serverNativeNotices", 4, issues)
        requireInt(receipt, "serverLegacyNotices", 4, issues)
        requireInt(receipt, "clientLegacyNotices", 4, issues)
        requireBoolean(receipt, "allScenariosPassed", true, issues)
        requireBoolean(receipt, "cleanupPassed", true, issues)
        requireArray(receipt, "requestedScenarios", expectedScenarios, issues, "receipt")
        requireArray(receipt, "observedScenarios", expectedScenarios, issues, "receipt")
        requireArray(trace, "requestedScenarios", expectedScenarios, issues, "trace")
        requireArray(trace, "observedScenarios", expectedScenarios, issues, "trace")
        requireString(receipt, "tracePath", request.tracePath.toString(), issues, "receipt")
        val traceHash = digest(request.tracePath)
        requireString(receipt, "traceSha256", traceHash, issues, "receipt")

        verifyScenarios(trace, issues)
        verifyCleanup(trace, issues)
        verifyArtifacts(request, receipt, trace, issues)
        verifyRuntimeLog(request, runtimeLog, traceHash, issues)

        if (issues.isNotEmpty()) {
            return LegacyCapabilityNativeValidation("FAILED", issues)
        }
        val receiptHash = evidenceDigest(request.receiptPath, "receipt", issues)
        val finalTraceHash = evidenceDigest(request.tracePath, "trace", issues)
        val wrapperHash = evidenceDigest(
            request.wrapperReceiptPath,
            "managed wrapper receipt",
            issues
        )
        val runtimeLogHash = evidenceDigest(runtimeLog, "runtime log", issues)
        if (issues.isNotEmpty() || receiptHash == null || finalTraceHash == null ||
            wrapperHash == null || runtimeLogHash == null
        ) {
            return LegacyCapabilityNativeValidation("FAILED", issues)
        }
        return LegacyCapabilityNativeValidation(
            state = "VALIDATED",
            issues = emptyList(),
            profileId = LegacyCapabilityNativeRuntimeHarnessSource.PROFILE_ID,
            receiptPath = request.receiptPath.toAbsolutePath().normalize().toString(),
            receiptSha256 = receiptHash,
            tracePath = request.tracePath.toAbsolutePath().normalize().toString(),
            traceSha256 = finalTraceHash,
            wrapperReceiptPath = request.wrapperReceiptPath.toAbsolutePath().normalize().toString(),
            wrapperReceiptSha256 = wrapperHash,
            runtimeLogSha256 = runtimeLogHash
        )
    }

    private fun verifyRequestPaths(
        request: LegacyCapabilityNativeRuntimeRequest,
        issues: MutableList<String>
    ) {
        val evidenceDir = request.evidenceDir.toAbsolutePath().normalize()
        if (!Files.isDirectory(evidenceDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(evidenceDir)) {
            issues.add("Native capability evidence directory is missing or linked: $evidenceDir")
        }
        val receipt = request.receiptPath.toAbsolutePath().normalize()
        val trace = request.tracePath.toAbsolutePath().normalize()
        if (receipt.parent != evidenceDir || trace.parent != evidenceDir) {
            issues.add("Native capability receipt or trace escapes the declared evidence directory")
        }
        if (receipt != evidenceDir.resolve(LegacyCapabilityNativeRuntimeHarnessSource.RECEIPT_FILE_NAME) ||
            trace != evidenceDir.resolve(LegacyCapabilityNativeRuntimeHarnessSource.TRACE_FILE_NAME)
        ) {
            issues.add("Native capability receipt or trace path does not use the canonical evidence name")
        }
        if (receipt == trace) {
            issues.add("Native capability receipt and trace paths must be distinct")
        }
        if (!sha256.matches(request.harnessSourceSha256)) {
            issues.add("Native capability staged harness source hash is malformed")
        }
        if (!sha256.matches(request.mixinConfigSha256)) {
            issues.add("Native capability mixin config hash is malformed")
        }
        if (!javaPackage.matches(request.compatPackage) || !javaPackage.matches(request.harnessPackage)) {
            issues.add("Native capability request contains an invalid Java package")
        }
        if (!generatedCompatPackage.matches(request.compatPackage) ||
            request.harnessPackage != request.compatPackage.removeSuffix(".compat") + ".benchmark"
        ) {
            issues.add("Native capability request packages do not share the owned generated profile")
        }
        val resourcePath = runCatching { Path.of(request.mixinConfigResource) }.getOrNull()
        if (request.mixinConfigResource != MIXIN_CONFIG_FILE ||
            resourcePath == null || resourcePath.isAbsolute ||
            resourcePath.normalize() != Path.of(MIXIN_CONFIG_FILE)
        ) {
            issues.add("Native capability mixin config resource is not the canonical owned resource")
        }
        val convertedModDir = request.convertedModDir.toAbsolutePath().normalize()
        if (!Files.isDirectory(convertedModDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(convertedModDir)) {
            issues.add("Native capability converted mod directory is missing or linked: $convertedModDir")
        }
        val runtimeDirectory = request.runtimeDirectory.toAbsolutePath().normalize()
        if (!Files.isDirectory(runtimeDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(runtimeDirectory)) {
            issues.add("Native capability runtime directory is missing or linked: $runtimeDirectory")
        }
    }

    private fun verifyStagedInputs(
        request: LegacyCapabilityNativeRuntimeRequest,
        issues: MutableList<String>
    ) {
        val canonicalHarness = LegacyCapabilityNativeRuntimeHarnessSource.source(
            request.harnessPackage,
            request.compatPackage
        )
        val canonicalHarnessHash = digest(canonicalHarness.toByteArray(StandardCharsets.UTF_8))
        if (request.harnessSourceSha256 != canonicalHarnessHash) {
            issues.add(
                "Native capability request harness hash=${request.harnessSourceSha256}; " +
                    "expected canonical $canonicalHarnessHash"
            )
        }
        val harnessSource = request.convertedModDir.resolve("src/main/java")
            .resolve(request.harnessPackage.replace('.', '/'))
            .resolve(LegacyCapabilityNativeRuntimeHarnessSource.FILE_NAME)
            .toAbsolutePath()
            .normalize()
        requireFileHash(
            harnessSource,
            request.harnessSourceSha256,
            "staged native capability harness source",
            issues
        )
        val configSource = request.convertedModDir.resolve("src/main/resources")
            .resolve(request.mixinConfigResource)
            .toAbsolutePath()
            .normalize()
        requireFileHash(configSource, request.mixinConfigSha256, "retained mixin config source", issues)
        parseRequiredObject(configSource, "retained mixin config source", issues)?.let { config ->
            verifyMixinConfig(config, request.compatPackage, issues)
        }
        verifyGeneratedSources(request, issues)
    }

    private fun verifyGeneratedSources(
        request: LegacyCapabilityNativeRuntimeRequest,
        issues: MutableList<String>
    ) {
        val javaRoot = request.convertedModDir.resolve("src/main/java").toAbsolutePath().normalize()
        val helper = javaRoot.resolve(request.compatPackage.replace('.', '/'))
            .resolve("LegacyBlockEntityCapabilityQueries.java")
        val helpers = regularFiles(javaRoot) {
            it.fileName.toString() == "LegacyBlockEntityCapabilityQueries.java"
        }
        if (helpers != listOf(helper)) {
            issues.add("Native capability generated helper inventory differs: $helpers; expected $helper")
        }
        verifyJavaSource(
            helper,
            request.compatPackage,
            "public final class LegacyBlockEntityCapabilityQueries",
            "generated capability helper",
            issues
        )
        val mixinDir = helper.parent.resolve("mixin")
        val expectedMixins = requiredMixinNames.associateWith { mixinDir.resolve("$it.java") }
        val mixins = regularFiles(javaRoot) {
            it.fileName.toString().removeSuffix(".java") in requiredMixinNames
        }
        if (mixins != expectedMixins.values.sorted()) {
            issues.add(
                "Native capability generated mixin source inventory differs: $mixins; " +
                    "expected ${expectedMixins.values}"
            )
        }
        expectedMixins.forEach { (name, source) ->
            verifyJavaSource(
                source,
                request.compatPackage + ".mixin",
                if (name == "LegacyCapabilityServerLevelMixin") {
                    "public abstract class $name"
                } else {
                    "public interface $name"
                },
                "generated mixin $name",
                issues
            )
        }
    }

    private fun verifyJavaSource(
        source: Path,
        expectedPackage: String,
        expectedDeclaration: String,
        label: String,
        issues: MutableList<String>
    ) {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            issues.add("Native capability $label is missing or linked: $source")
            return
        }
        val text = runCatching { source.readText() }.getOrElse { error ->
            issues.add("Could not read native capability $label $source: ${error.message}")
            return
        }
        val actualPackage = packageDeclaration.find(text)?.groupValues?.get(1)
        if (actualPackage != expectedPackage) {
            issues.add("Native capability $label package=$actualPackage; expected $expectedPackage")
        }
        if (!text.contains(expectedDeclaration)) {
            issues.add("Native capability $label lacks declaration '$expectedDeclaration'")
        }
    }

    private fun verifyWrapperReceipt(
        request: LegacyCapabilityNativeRuntimeRequest,
        wrapper: JsonObject,
        expectedSession: String,
        issues: MutableList<String>
    ) {
        requireInt(wrapper, "schemaVersion", 1, issues, "wrapper")
        requireString(wrapper, "runtimeKind", "client", issues, "wrapper")
        requireString(wrapper, "evidenceMode", "harness", issues, "wrapper")
        requireBoolean(wrapper, "harnessContractRequested", true, issues, "wrapper")
        requireString(wrapper, "sessionId", expectedSession, issues, "wrapper")
        requireInt(wrapper, "processStartCount", 1, issues, "wrapper")
        requireBoolean(wrapper, "processInventoryChecked", true, issues, "wrapper")
        requireBoolean(wrapper, "runtimeExitValidated", true, issues, "wrapper")
        requireBoolean(wrapper, "harnessValidated", true, issues, "wrapper")
        requireBoolean(wrapper, "passed", true, issues, "wrapper")
        requireInt(wrapper, "exitCode", 0, issues, "wrapper")
        if (!string(wrapper, "failure").isNullOrEmpty()) {
            issues.add("Native capability managed wrapper recorded a failure")
        }
        val runtimeDirectory = string(wrapper, "runtimeDirectory")?.let {
            runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull()
        }
        if (runtimeDirectory != request.runtimeDirectory.toAbsolutePath().normalize()) {
            issues.add("Native capability managed wrapper runtimeDirectory differs from the request")
        }
        listOf(
            "activeRuntimeProcesses", "reconciledExitedProcessIds",
            "preRunRuntimeProcesses", "postRunRuntimeProcesses"
        )
            .forEach { key ->
                val inventory = wrapper[key] as? JsonArray
                if (inventory == null || inventory.isNotEmpty()) {
                    issues.add("Native capability wrapper.$key must be an empty process inventory")
                }
            }
        val command = wrapper["command"] as? JsonObject
        if (command == null) {
            issues.add("Native capability wrapper.command is missing")
        } else {
            requireBoolean(command, "started", true, issues, "wrapper.command")
            requireBoolean(command, "exited", true, issues, "wrapper.command")
            requireBoolean(command, "timedOut", false, issues, "wrapper.command")
            val killed = command["killedOwnedProcessIds"] as? JsonArray
            if (killed == null || killed.isNotEmpty()) {
                issues.add("Native capability wrapper.command.killedOwnedProcessIds must be empty")
            }
        }
        val expected = wrapper["expected"] as? JsonObject
        if (expected == null) {
            issues.add("Native capability wrapper.expected is missing")
        } else {
            requireInt(expected, "processStartCount", 1, issues, "wrapper.expected")
            requireInt(expected, "worldLoadCount", 1, issues, "wrapper.expected")
            val scenarioCount = strictInt(expected, "scenarioCount")
            val scenarioIds = stringArray(expected, "scenarioIds")
            if (scenarioCount == null || scenarioCount <= 0 ||
                scenarioIds == null || scenarioIds.size != scenarioCount ||
                scenarioIds.any { it.isBlank() } || scenarioIds.distinct().size != scenarioIds.size
            ) {
                issues.add("Native capability wrapper.expected scenario contract is invalid")
            }
        }
        val harnessReceiptPath = string(wrapper, "harnessReceiptPath")?.let {
            runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull()
        }
        val harnessReceiptHash = string(wrapper, "harnessReceiptSha256")
        if (harnessReceiptPath == null || harnessReceiptHash == null ||
            !sha256.matches(harnessReceiptHash)
        ) {
            issues.add("Native capability managed wrapper harness receipt binding is malformed")
        } else {
            requireFileHash(
                harnessReceiptPath,
                harnessReceiptHash,
                "managed wrapper harness receipt",
                issues
            )
        }
    }

    private fun verifyScenarios(trace: JsonObject, issues: MutableList<String>) {
        val scenarios = trace["scenarios"] as? JsonArray
        if (scenarios == null || scenarios.size != expectedScenarios.size) {
            issues.add(
                "Native capability trace scenarios count=${scenarios?.size ?: -1}; " +
                    "expected ${expectedScenarios.size}"
            )
            return
        }
        scenarios.forEachIndexed { index, element ->
            val scenario = element as? JsonObject
            if (scenario == null) {
                issues.add("Native capability scenario[$index] is not an object")
                return@forEachIndexed
            }
            requireExactKeys(scenario, expectedScenarioKeys, "scenario[$index]", issues)
            requireString(scenario, "id", expectedScenarios[index], issues, "scenario[$index]")
            requireString(
                scenario,
                "side",
                if (index < 3) "server" else "client",
                issues,
                "scenario[$index]"
            )
            requireInt(scenario, "ordinal", index, issues, "scenario[$index]")
            requireString(scenario, "result", "PASS", issues, "scenario[$index]")
            requireBoolean(scenario, "replacementIdentityPassed", true, issues, "scenario[$index]")
            val start = strictLong(scenario, "startTick")
            val end = strictLong(scenario, "endTick")
            if (start == null || end == null || start < 0 || end < start) {
                issues.add("Native capability scenario[$index] has invalid tick interval")
            }
            requireInt(
                scenario,
                "nativeNotices",
                when (index) {
                    1 -> 2
                    in 0..2 -> 1
                    else -> 0
                },
                issues,
                "scenario[$index]"
            )
            requireInt(
                scenario,
                "legacyNotices",
                if (index == 1 || index == 4) 2 else 1,
                issues,
                "scenario[$index]"
            )
        }
    }

    private fun verifyCleanup(trace: JsonObject, issues: MutableList<String>) {
        val cleanup = trace["cleanup"] as? JsonObject
        if (cleanup == null) {
            issues.add("Native capability trace cleanup object is missing")
            return
        }
        requireExactKeys(cleanup, expectedCleanupKeys, "cleanup", issues)
        requireBoolean(cleanup, "serverRestorePassed", true, issues, "cleanup")
        requireBoolean(cleanup, "clientBaselineObserved", true, issues, "cleanup")
        requireBoolean(cleanup, "originalBlockEntityPresent", false, issues, "cleanup")
        val restoreTick = strictLong(cleanup, "serverRestoreTick")
        if (restoreTick == null || restoreTick < 0) {
            issues.add("Native capability server cleanup tick is invalid: $restoreTick")
        }
        val fenceTicks = strictInt(cleanup, "clientFenceTicks")
        val fenceStart = strictLong(cleanup, "clientFenceStartTick")
        val fenceEnd = strictLong(cleanup, "clientFenceEndTick")
        if (fenceTicks != 3 || fenceStart == null || fenceEnd == null ||
            fenceStart < 0 || fenceEnd - fenceStart != 2L
        ) {
            issues.add(
                "Native capability client cleanup fence must contain exactly three " +
                    "distinct advancing ticks; start=$fenceStart end=$fenceEnd count=$fenceTicks"
            )
        }
    }

    private fun verifyArtifacts(
        request: LegacyCapabilityNativeRuntimeRequest,
        receipt: JsonObject,
        trace: JsonObject,
        issues: MutableList<String>
    ) {
        val receiptArtifacts = receipt["artifactHashes"] as? JsonObject
        val traceArtifacts = trace["artifactHashes"] as? JsonObject
        if (receiptArtifacts == null || traceArtifacts == null) {
            issues.add("Native capability artifact hashes are missing")
            return
        }
        if (receiptArtifacts != traceArtifacts) {
            issues.add("Native capability receipt and trace artifact hashes differ")
        }
        if (receiptArtifacts.keys != expectedArtifactKeys) {
            issues.add(
                "Native capability artifact hash keys=${receiptArtifacts.keys}; " +
                    "expected $expectedArtifactKeys"
            )
        }
        expectedArtifactKeys.forEach { key ->
            val value = string(receiptArtifacts, key)
            if (value == null || !sha256.matches(value)) {
                issues.add("Native capability artifact hash $key is missing or malformed")
            }
        }
        requireString(
            receiptArtifacts,
            "mixinConfigSha256",
            request.mixinConfigSha256,
            issues,
            "artifactHashes"
        )

        val classesRoot = request.convertedModDir.resolve("build/classes/java/main")
        val compatPath = request.compatPackage.replace('.', '/')
        val harnessPath = request.harnessPackage.replace('.', '/')
        val classFiles = linkedMapOf(
            "helperClassSha256" to
                classesRoot.resolve("$compatPath/LegacyBlockEntityCapabilityQueries.class"),
            "serverMixinClassSha256" to
                classesRoot.resolve("$compatPath/mixin/LegacyCapabilityServerLevelMixin.class"),
            "levelExtensionMixinClassSha256" to
                classesRoot.resolve("$compatPath/mixin/LegacyCapabilityLevelExtensionMixin.class"),
            "harnessClassSha256" to
                classesRoot.resolve("$harnessPath/ModPorterLegacyCapabilityNativeHarness.class")
        )
        classFiles.forEach { (key, path) ->
            val expected = string(receiptArtifacts, key)
            if (expected != null) {
                requireFileHash(path, expected, "runtime class $key", issues)
            }
        }
        val runtimeConfig = request.convertedModDir.resolve("build/resources/main")
            .resolve(request.mixinConfigResource)
        requireFileHash(
            runtimeConfig,
            request.mixinConfigSha256,
            "runtime retained mixin config resource",
            issues
        )
    }

    private fun verifyRuntimeLog(
        request: LegacyCapabilityNativeRuntimeRequest,
        runtimeLog: Path,
        traceHash: String,
        issues: MutableList<String>
    ) {
        if (!Files.isRegularFile(runtimeLog, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(runtimeLog)) {
            issues.add("Native capability runtime log is missing or linked: $runtimeLog")
            return
        }
        val log = runCatching { runtimeLog.readText() }.getOrElse { error ->
            issues.add("Could not read native capability runtime log $runtimeLog: ${error.message}")
            return
        }
        val lines = log.lineSequence().map(String::trim).toList()
        listOf(
            "[ModPorterCapabilityProbe] Server PASS",
            "[ModPorterCapabilityProbe] Client PASS",
            "[ModPorterCapabilityProbe] Server cleanup PASS"
        ).forEach { marker ->
            if (lines.count { it.startsWith("$marker ") } != 1) {
                issues.add("Native capability runtime log must contain exactly one marker: $marker")
            }
        }
        val receiptHash = digest(request.receiptPath)
        val exactSeal = "[ModPorterCapabilityProbe] Receipt sealed path=${request.receiptPath} " +
            "sha256=$receiptHash traceSha256=$traceHash status=PASS"
        if (lines.count { it == exactSeal } != 1) {
            issues.add("Native capability runtime log lacks the exact bound PASS receipt seal")
        }
    }

    private fun evidenceDigest(
        path: Path,
        label: String,
        issues: MutableList<String>
    ): String? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(normalized)
        ) {
            issues.add("Native capability $label is missing or linked: $normalized")
            return null
        }
        return runCatching { digest(normalized) }.getOrElse { error ->
            issues.add("Could not hash native capability $label $normalized: ${error.message}")
            null
        }
    }

    private fun requireFileHash(
        path: Path,
        expected: String,
        label: String,
        issues: MutableList<String>
    ) {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            issues.add("Native capability $label is missing or linked: $normalized")
            return
        }
        val actual = runCatching { digest(normalized) }.getOrElse { error ->
            issues.add("Could not hash native capability $label $normalized: ${error.message}")
            return
        }
        if (actual != expected) {
            issues.add("Native capability $label hash=$actual; expected $expected")
        }
    }

    private fun regularFiles(root: Path, predicate: (Path) -> Boolean): List<Path> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return emptyList()
        Files.walk(root).use { stream ->
            return stream.filter {
                Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(it) && predicate(it)
            }
                .map { it.toAbsolutePath().normalize() }
                .sorted()
                .toList()
        }
    }

    private fun verifyMixinConfig(
        config: JsonObject,
        compatPackage: String,
        issues: MutableList<String>
    ) {
        requireExactKeys(config, expectedMixinConfigKeys, "mixinConfig", issues)
        requireBoolean(config, "required", true, issues, "mixinConfig")
        requireString(config, "minVersion", "0.8", issues, "mixinConfig")
        requireString(config, "package", "$compatPackage.mixin", issues, "mixinConfig")
        requireString(config, "compatibilityLevel", "JAVA_21", issues, "mixinConfig")
        requireArray(config, "mixins", requiredMixinNames, issues, "mixinConfig")
        val injectors = config["injectors"] as? JsonObject
        if (injectors == null) {
            issues.add("Native capability mixinConfig.injectors is missing")
        } else {
            requireExactKeys(injectors, setOf("defaultRequire"), "mixinConfig.injectors", issues)
            requireInt(injectors, "defaultRequire", 1, issues, "mixinConfig.injectors")
        }
    }

    private fun parseRequiredObject(
        path: Path,
        label: String,
        issues: MutableList<String>
    ): JsonObject? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(normalized)
        ) {
            issues.add("Native capability $label is missing or linked: $normalized")
            return null
        }
        return parseObject(normalized, label, issues)
    }

    private fun requireExactKeys(
        value: JsonObject,
        expected: Set<String>,
        label: String,
        issues: MutableList<String>
    ) {
        if (value.keys != expected) {
            issues.add(
                "Native capability $label keys differ: " +
                    "missing=${expected - value.keys} extra=${value.keys - expected}"
            )
        }
    }

    private fun parseObject(path: Path, label: String, issues: MutableList<String>): JsonObject? =
        runCatching { json.parseToJsonElement(path.readText()).jsonObject }
            .getOrElse { error ->
                issues.add("Could not parse native capability $label $path: ${error.message}")
                null
            }

    private fun string(objectValue: JsonObject, key: String): String? =
        (objectValue[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull

    private fun strictInt(objectValue: JsonObject, key: String): Int? =
        (objectValue[key] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.intOrNull

    private fun strictLong(objectValue: JsonObject, key: String): Long? =
        (objectValue[key] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.longOrNull

    private fun strictBoolean(objectValue: JsonObject, key: String): Boolean? =
        (objectValue[key] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.booleanOrNull

    private fun stringArray(objectValue: JsonObject, key: String): List<String>? =
        (objectValue[key] as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?: return null
        }

    private fun requireString(
        objectValue: JsonObject,
        key: String,
        expected: String,
        issues: MutableList<String>,
        label: String
    ) {
        val actual = string(objectValue, key)
        if (actual != expected) {
            issues.add("Native capability $label.$key=$actual; expected $expected")
        }
    }

    private fun requireInt(
        objectValue: JsonObject,
        key: String,
        expected: Int,
        issues: MutableList<String>,
        label: String = "receipt"
    ) {
        val actual = strictInt(objectValue, key)
        if (actual != expected) {
            issues.add("Native capability $label.$key=$actual; expected $expected")
        }
    }

    private fun requireBoolean(
        objectValue: JsonObject,
        key: String,
        expected: Boolean,
        issues: MutableList<String>,
        label: String = "receipt"
    ) {
        val actual = strictBoolean(objectValue, key)
        if (actual != expected) {
            issues.add("Native capability $label.$key=$actual; expected $expected")
        }
    }

    private fun requireArray(
        objectValue: JsonObject,
        key: String,
        expected: List<String>,
        issues: MutableList<String>,
        label: String
    ) {
        val actual = stringArray(objectValue, key)
        if (actual != expected) {
            issues.add("Native capability $label.$key=$actual; expected $expected")
        }
    }

    private fun digest(path: Path): String = digest(Files.readAllBytes(path))

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
