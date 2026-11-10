package com.modporter.integration

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.PipelineResult
import com.modporter.core.transforms.structural.CreateCopperDataMapMigration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs

internal object StrictSpawnValidationContract {
    const val RULE_ID = "struct-legacy-client-spawn-factory"
    const val STATION_RULE_ID = "struct-create-native-station-map-decoration"
    const val PROFILE_ID = "legacy-client-spawn-factory/package-entity/v1"
    const val RECEIPT_SCHEMA = "modporter.strict-review.legacy-client-spawn-factory.v1"
    const val MANIFEST_SCHEMA = "modporter.strict-review.legacy-client-spawn-manifest.v1"
    const val RECEIPT_FILE_NAME = "modporter-strict-spawn-receipt.json"
    const val STATION_RECEIPT_FILE_NAME = "modporter-strict-station-map-receipt.json"
    const val COORDINATOR_RECEIPT_FILE_NAME = "modporter-client-world-coordinator-receipt.json"
    const val GENERATED_HARNESS_FILE = "ModPorterStrictSpawnHarness.java"
    const val GENERIC_HARNESS_FILE = "ModPorterClientWorldHarness.java"
    const val STATION_HARNESS_FILE = "ModPorterStrictStationMapHarness.java"
    const val NATIVE_CAPABILITY_HARNESS_FILE =
        LegacyCapabilityNativeRuntimeHarnessSource.FILE_NAME
    const val COMPLETION_MARKER = "[ModPorterStrictSpawn] Receipt sealed:"
    const val MINECRAFT_VERSION = "1.21.1"
    const val NEOFORGE_VERSION = "21.1.230"

    val scenarioIds = listOf(
        "non_quantized_motion",
        "packet_clamped_motion",
        "tracker_reentry"
    )

    val lifecycleSemanticCheckIds = listOf(
        "join.pre_insertion",
        "join.client_position",
        "join.raw_packet_motion",
        "join.pending_true",
        "join.default_box_dimensions",
        "size.post_join",
        "size.decoded_box_dimensions",
        "size.pending_false",
        "size.deferred_average",
        "pre_tick.before_package_tick",
        "pre_tick.authoritative_motion",
        "pre_tick.decoded_box_dimensions"
    )

    val trackerSemanticCheckIds = lifecycleSemanticCheckIds + listOf(
        "tracker.same_server_entity",
        "tracker.fresh_client_instance",
        "tracker.lifecycle_repeated"
    )
}

internal data class StrictReviewAdmission(
    val enforced: Boolean,
    val rawMediumCount: Int,
    val rawLowCount: Int,
    val pendingChanges: List<Change>,
    val fatalIssues: List<String>,
    val profile: StrictSpawnSourceProfile? = null,
    val stationMapRequired: Boolean = false
) {
    val pendingCount: Int
        get() = pendingChanges.size

    val rawReviewCount: Int
        get() = rawMediumCount + rawLowCount
}

internal data class StrictSpawnSourceProfile(
    val repositoryRoot: Path,
    val inputProjectDir: Path,
    val convertedBuildRoot: Path,
    val convertedModDir: Path,
    val inputPackageEntity: Path,
    val generatedPackageEntity: Path,
    val accessTransformer: Path,
    val buildFiles: List<Path>,
    val gradleProperties: Path,
    val ruleSource: Path,
    val ruleTests: Path,
    val sourceLabel: String,
    val change: Change,
    val productBindings: Map<String, String>
)

internal data class StrictReviewRuntimeRequest(
    val profileId: String,
    val validationSessionId: String,
    val manifestPath: Path,
    val receiptPath: Path,
    val stationReceiptPath: Path = receiptPath.resolveSibling(
        StrictSpawnValidationContract.STATION_RECEIPT_FILE_NAME
    ),
    val coordinatorReceiptPath: Path = receiptPath.resolveSibling(
        StrictSpawnValidationContract.COORDINATOR_RECEIPT_FILE_NAME
    ),
    val wrapperReceiptPath: Path,
    val evidenceDir: Path,
    val runtimeDirectory: Path,
    val profile: StrictSpawnSourceProfile,
    val productBindings: Map<String, String>,
    val bindings: Map<String, String> = emptyMap(),
    val stationMapRequired: Boolean = false
)

internal data class StrictReviewRuntimePreparation(
    val request: StrictReviewRuntimeRequest?,
    val issues: List<String>
)

data class StrictReviewValidationSummary(
    val rawMediumCount: Int,
    val rawLowCount: Int,
    val pendingCount: Int,
    val validatedCount: Int,
    val unresolvedCount: Int,
    val state: String,
    val issues: List<String> = emptyList(),
    val profileId: String? = null,
    val receiptPath: String? = null,
    val receiptSha256: String? = null,
    val wrapperReceiptPath: String? = null,
    val wrapperReceiptSha256: String? = null,
    val runtimeLogSha256: String? = null
) {
    fun toTelemetryMap(): Map<String, Any?> = linkedMapOf(
        "state" to state,
        "profileId" to profileId,
        "rawMedium" to rawMediumCount,
        "rawLow" to rawLowCount,
        "pending" to pendingCount,
        "validated" to validatedCount,
        "unresolved" to unresolvedCount,
        "issues" to issues,
        "receiptPath" to receiptPath,
        "receiptSha256" to receiptSha256,
        "wrapperReceiptPath" to wrapperReceiptPath,
        "wrapperReceiptSha256" to wrapperReceiptSha256,
        "runtimeLogSha256" to runtimeLogSha256
    )
}

internal object StrictReviewValidationRegistry {
    private const val trackerExitMarginBlocks = 16.0
    private const val trackerExitEpsilon = 1.0E-9

    private val packageEntityRelativePath =
        Path.of("src/main/java/com/simibubi/create/content/logistics/box/PackageEntity.java")
    private val accessTransformerRelativePath =
        Path.of("src/main/resources/META-INF/accesstransformer.cfg")
    private val ruleSourceRelativePath =
        Path.of("src/main/kotlin/com/modporter/core/transforms/structural/LegacyClientSpawnFactories.kt")
    private val ruleTestsRelativePath =
        Path.of("src/test/kotlin/com/modporter/core/transforms/structural/LegacyClientSpawnFactoriesTest.kt")
    private val validatorSourceRelativePath =
        Path.of("src/test/kotlin/com/modporter/integration/StrictReviewValidation.kt")
    private val harnessGeneratorRelativePath =
        Path.of("src/test/kotlin/com/modporter/integration/LegacyClientSpawnRuntimeHarnessSource.kt")
    private val stationValidatorSourceRelativePath =
        Path.of("src/test/kotlin/com/modporter/integration/CreateStationMapRuntimeValidation.kt")
    private val stationHarnessGeneratorRelativePath =
        Path.of("src/test/kotlin/com/modporter/integration/CreateStationMapRuntimeHarnessSource.kt")
    private val nativeCapabilityValidatorRelativePath =
        Path.of("src/test/kotlin/com/modporter/integration/LegacyCapabilityNativeRuntimeValidation.kt")
    private val nativeCapabilityHarnessGeneratorRelativePath =
        Path.of("src/test/kotlin/com/modporter/integration/LegacyCapabilityNativeRuntimeHarnessSource.kt")
    private val runtimeToolRelativePaths = listOf(
        Path.of("scripts/Invoke-ModporterRuntimeSession.ps1"),
        Path.of("scripts/runtime_process_inventory.ps1"),
        Path.of("src/test/resources/benchmark-process/runtime_preflight.ps1"),
        Path.of("src/test/resources/benchmark-process/hidden_desktop.ps1")
    )
    private val packetAccessTransformerLines = listOf("xa", "ya", "za").map {
        "public net.minecraft.network.protocol.game.ClientboundAddEntityPacket $it"
    }
    private val dataGenerationMutableBindings = linkedSetOf(
        "generated.generatedResourcesTree.sha256",
        "generated.generatedAssetsTree.sha256"
    )
    private val expectedCreateDataMaps = linkedMapOf(
        "oxidizables.json" to 18,
        "waxables.json" to 24
    )
    private val sha256Pattern = Regex("""^[0-9a-f]{64}$""")
    private val gitCommitPattern = Regex("""@([0-9a-fA-F]{40})$""")
    private val json = Json { ignoreUnknownKeys = false }

    fun admit(
        repositoryRoot: Path,
        inputProjectDir: Path,
        convertedBuildRoot: Path,
        convertedModDir: Path,
        result: PipelineResult,
        sourceLabel: String,
        enforceHandsOff: Boolean
    ): StrictReviewAdmission {
        val allChanges = result.passResults.flatMap { it.changes }
        val mediumChanges = allChanges.filter { it.confidence == Confidence.MEDIUM }
        val lowChanges = allChanges.filter { it.confidence == Confidence.LOW }
        if (!enforceHandsOff) {
            return StrictReviewAdmission(
                enforced = false,
                rawMediumCount = mediumChanges.size,
                rawLowCount = lowChanges.size,
                pendingChanges = emptyList(),
                fatalIssues = emptyList()
            )
        }

        val fatalIssues = mutableListOf<String>()
        val stationChanges = allChanges.filter {
            it.ruleId == StrictSpawnValidationContract.STATION_RULE_ID
        }
        val stationMapRequired = stationChanges.isNotEmpty()
        if (stationMapRequired) {
            fatalIssues += validateGeneratedStationMapFamily(convertedModDir)
        }
        if (lowChanges.isNotEmpty()) {
            fatalIssues +=
                "Hands-off gate failed: ${lowChanges.size} LOW-confidence change(s) are never eligible for pending validation"
        }

        val supportedCandidates = mediumChanges.filter { it.ruleId == StrictSpawnValidationContract.RULE_ID }
        val supportedStationChanges = stationChanges.filter { it.confidence == Confidence.MEDIUM }
        val unsupportedMedium = mediumChanges - (supportedCandidates + supportedStationChanges).toSet()
        if (unsupportedMedium.isNotEmpty()) {
            fatalIssues +=
                "Hands-off gate failed: ${unsupportedMedium.size} unsupported MEDIUM-confidence change(s) require review: " +
                unsupportedMedium.take(8).joinToString(", ") { "${it.ruleId}@${it.file}:${it.line}" }
        }

        val generatedPackageEntity = convertedModDir.resolve(packageEntityRelativePath).normalize()
        val generatedAccessTransformer = convertedModDir.resolve(accessTransformerRelativePath).normalize()
        val sameRuleChanges = allChanges.filter {
            it.ruleId == StrictSpawnValidationContract.RULE_ID
        }
        val runtimePackageCandidates = sameRuleChanges.filter {
            pathIdentifies(it.file, generatedPackageEntity, packageEntityRelativePath)
        }
        val accessTransformerSupportChanges = sameRuleChanges.filter {
            pathIdentifies(it.file, generatedAccessTransformer, accessTransformerRelativePath)
        }
        val unclassifiedSameRuleChanges =
            sameRuleChanges - (runtimePackageCandidates + accessTransformerSupportChanges).toSet()
        if (unclassifiedSameRuleChanges.isNotEmpty()) {
            fatalIssues +=
                "Native Create client-world validation found unclassified " +
                "${StrictSpawnValidationContract.RULE_ID} change(s): " +
                unclassifiedSameRuleChanges.take(8).joinToString(", ") {
                    "${it.file}:${it.line}"
                }
        }
        if (accessTransformerSupportChanges.size > 1 ||
            accessTransformerSupportChanges.any { !isCanonicalPacketAccessSupportChange(it) }
        ) {
            fatalIssues +=
                "Native Create client-world validation found invalid packet access-transformer " +
                "support change(s): count=${accessTransformerSupportChanges.size}"
        }
        val needsRuntimeProfile = supportedCandidates.isNotEmpty() || stationMapRequired
        if (!needsRuntimeProfile) {
            return StrictReviewAdmission(
                enforced = true,
                rawMediumCount = mediumChanges.size,
                rawLowCount = lowChanges.size,
                pendingChanges = emptyList(),
                fatalIssues = fatalIssues,
                stationMapRequired = false
            )
        }
        if (supportedCandidates.size > 1 || runtimePackageCandidates.size != 1) {
            fatalIssues +=
                "Native Create client-world validation requires exactly one closed " +
                "${StrictSpawnValidationContract.RULE_ID} migration; " +
                "pending=${supportedCandidates.size}, runtime=${runtimePackageCandidates.size}"
            return StrictReviewAdmission(
                enforced = true,
                rawMediumCount = mediumChanges.size,
                rawLowCount = lowChanges.size,
                pendingChanges = emptyList(),
                fatalIssues = fatalIssues,
                stationMapRequired = stationMapRequired
            )
        }

        val change = runtimePackageCandidates.single()
        val match = matchPackageEntityProfile(
            repositoryRoot = repositoryRoot,
            inputProjectDir = inputProjectDir,
            convertedBuildRoot = convertedBuildRoot,
            convertedModDir = convertedModDir,
            sourceLabel = sourceLabel,
            change = change
        )
        if (match.issues.isNotEmpty() || match.profile == null) {
            fatalIssues +=
                "Hands-off gate failed: ${StrictSpawnValidationContract.RULE_ID} did not match the only supported " +
                "${StrictSpawnValidationContract.PROFILE_ID} source profile: ${match.issues.joinToString("; ")}"
            return StrictReviewAdmission(
                enforced = true,
                rawMediumCount = mediumChanges.size,
                rawLowCount = lowChanges.size,
                pendingChanges = emptyList(),
                fatalIssues = fatalIssues,
                stationMapRequired = stationMapRequired
            )
        }

        return StrictReviewAdmission(
            enforced = true,
            rawMediumCount = mediumChanges.size,
            rawLowCount = lowChanges.size,
            pendingChanges = supportedCandidates,
            fatalIssues = fatalIssues,
            profile = match.profile,
            stationMapRequired = stationMapRequired
        )
    }

    private fun validateGeneratedStationMapFamily(modDir: Path): List<String> {
        val expected = linkedMapOf(
            "src/main/java/com/simibubi/create/AllMapDecorationTypes.java" to
                listOf(
                    "STATION_MAP_DECORATION",
                    "DeferredRegister.create(Registries.MAP_DECORATION_TYPE"
                ),
            "src/main/java/com/simibubi/create/content/trains/station/StationMapData.java" to
                listOf("interface StationMapData", "addStationMarker"),
            "src/main/java/com/simibubi/create/foundation/map/StationMapDecorationRenderer.java" to
                listOf("implements IMapDecorationRenderer", "boolean render("),
            "src/main/java/com/simibubi/create/foundation/map/StationMapDecorationEvents.java" to
                listOf(
                    "RegisterMapDecorationRenderersEvent",
                    "new StationMapDecorationRenderer()"
                )
        )
        val issues = mutableListOf<String>()
        for ((relative, markers) in expected) {
            val file = modDir.resolve(relative).normalize()
            if (!file.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                issues += "Generated Create station-map family is missing: $relative"
                continue
            }
            val source = file.readText()
            val missingMarkers = markers.filterNot(source::contains)
            if (missingMarkers.isNotEmpty()) {
                issues +=
                    "Generated Create station-map family drifted: $relative lacks ${missingMarkers.joinToString()}"
            }
        }
        val texture = modDir.resolve(
            "src/main/resources/assets/create/textures/map/decorations/station.png"
        ).normalize()
        if (!texture.isRegularFile(LinkOption.NOFOLLOW_LINKS) ||
            texture.readBytes().isEmpty()
        ) {
            issues += "Generated Create station-map texture is missing or empty"
        }
        return issues
    }

    fun createRuntimeRequest(
        admission: StrictReviewAdmission,
        reportsDir: Path
    ): StrictReviewRuntimePreparation {
        val profile = admission.profile
            ?: return StrictReviewRuntimePreparation(
                request = null,
                issues = listOf("Strict review runtime request requires an admitted source profile")
            )
        if (admission.pendingCount !in 0..1 ||
            (!admission.stationMapRequired && admission.pendingCount == 0) ||
            admission.fatalIssues.isNotEmpty()
        ) {
            return StrictReviewRuntimePreparation(
                request = null,
                issues = listOf(
                    "Strict review runtime request requires one pending change or an independent " +
                        "station-map validation, and no fatal review issues"
                )
            )
        }

        return runCatching {
            reportsDir.createDirectories()
            val validationSessionId = UUID.randomUUID().toString()
            val prefix = "strict-spawn-$validationSessionId"
            val evidenceDir = reportsDir.resolve("$prefix-evidence").toAbsolutePath().normalize()
            evidenceDir.createDirectories()
            StrictReviewRuntimeRequest(
                profileId = StrictSpawnValidationContract.PROFILE_ID,
                validationSessionId = validationSessionId,
                manifestPath = reportsDir.resolve("$prefix-manifest.json").toAbsolutePath().normalize(),
                receiptPath = reportsDir.resolve("$prefix-${StrictSpawnValidationContract.RECEIPT_FILE_NAME}")
                    .toAbsolutePath().normalize(),
                stationReceiptPath = reportsDir.resolve(
                    "$prefix-${StrictSpawnValidationContract.STATION_RECEIPT_FILE_NAME}"
                ).toAbsolutePath().normalize(),
                coordinatorReceiptPath = reportsDir.resolve(
                    "$prefix-${StrictSpawnValidationContract.COORDINATOR_RECEIPT_FILE_NAME}"
                ).toAbsolutePath().normalize(),
                wrapperReceiptPath = reportsDir.resolve("$prefix-wrapper-receipt.json")
                    .toAbsolutePath().normalize(),
                evidenceDir = evidenceDir,
                runtimeDirectory = profile.convertedModDir.resolve("run").toAbsolutePath().normalize(),
                profile = profile,
                productBindings = profile.productBindings,
                stationMapRequired = admission.stationMapRequired
            )
        }.fold(
            onSuccess = { StrictReviewRuntimePreparation(it, emptyList()) },
            onFailure = {
                StrictReviewRuntimePreparation(
                    request = null,
                    issues = listOf("Failed to prepare strict spawn runtime request: ${it.message}")
                )
            }
        )
    }

    fun refreshGeneratedResourceBindingsAfterDataGeneration(
        request: StrictReviewRuntimeRequest,
        runId: String,
        receipt: CreateDataGenerationEvidence.ReceiptReference
    ): StrictReviewRuntimePreparation =
        runCatching {
            check(runId.isNotBlank()) {
                "Strict review data-generation binding refresh requires a benchmark run id"
            }
            check(request.bindings.isEmpty()) {
                "Strict review data-generation bindings cannot refresh after runtime manifest sealing"
            }
            check(request.productBindings == request.profile.productBindings) {
                "Strict review request and profile product bindings differ before data generation"
            }

            val currentBindings = productBindings(request.profile)
            check(currentBindings.keys == request.productBindings.keys) {
                "Strict review product binding keys changed before data-generation refresh"
            }
            val unexpectedDrift = request.productBindings.keys
                .filterNot(dataGenerationMutableBindings::contains)
                .filter { request.productBindings[it] != currentBindings[it] }
            check(unexpectedDrift.isEmpty()) {
                "Strict review admission products changed outside generated data resources: " +
                    unexpectedDrift.joinToString()
            }

            verifyDataGenerationReceipt(request, runId, receipt)

            val refreshedBindings = LinkedHashMap(request.productBindings)
            for (binding in dataGenerationMutableBindings) {
                refreshedBindings[binding] = checkNotNull(currentBindings[binding]) {
                    "Strict review data-generation binding disappeared: $binding"
                }
            }
            val changedBindings = refreshedBindings.keys.filter {
                refreshedBindings[it] != request.productBindings[it]
            }
            check(changedBindings.all(dataGenerationMutableBindings::contains)) {
                "Strict review data-generation refresh changed an unapproved binding: " +
                    changedBindings.joinToString()
            }

            val refreshedProfile = request.profile.copy(productBindings = refreshedBindings)
            StrictReviewRuntimePreparation(
                request = request.copy(
                    profile = refreshedProfile,
                    productBindings = refreshedBindings
                ),
                issues = emptyList()
            )
        }.fold(
            onSuccess = { it },
            onFailure = {
                StrictReviewRuntimePreparation(
                    request = null,
                    issues = listOf(
                        "Failed to refresh strict review data-generation bindings: ${it.message}"
                    )
                )
            }
        )

    fun sealRuntimeManifest(request: StrictReviewRuntimeRequest): StrictReviewRuntimePreparation =
        runCatching {
            val profile = request.profile
            val sourceRoot = profile.convertedModDir.resolve("src/main/java")
            val genericHarness = findUniqueNamedFile(
                sourceRoot,
                StrictSpawnValidationContract.GENERIC_HARNESS_FILE
            )
            val strictHarness = findUniqueNamedFile(
                sourceRoot,
                StrictSpawnValidationContract.GENERATED_HARNESS_FILE
            )
            check(genericHarness.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                "Generic client-world harness is missing after staging: $genericHarness"
            }
            check(strictHarness.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                "Strict spawn harness is missing after staging: $strictHarness"
            }
            val stationHarness = if (request.stationMapRequired) {
                findUniqueNamedFile(
                    sourceRoot,
                    StrictSpawnValidationContract.STATION_HARNESS_FILE
                ).also {
                    check(it.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                        "Strict station-map harness is missing after staging: $it"
                    }
                }
            } else {
                null
            }
            val nativeCapabilityHarness = findOptionalUniqueNamedFile(
                sourceRoot,
                StrictSpawnValidationContract.NATIVE_CAPABILITY_HARNESS_FILE
            )
            val runtimeBuildFiles = buildFiles(profile.convertedBuildRoot, profile.convertedModDir)
            check(runtimeBuildFiles.isNotEmpty()) { "No runtime build file exists after harness staging" }

            val bindings = linkedMapOf<String, String>().apply {
                putAll(request.productBindings)
                put("runtime.genericHarness.sha256", sha256(genericHarness))
                put("runtime.strictHarness.sha256", sha256(strictHarness))
                stationHarness?.let {
                    put("runtime.stationHarness.sha256", sha256(it))
                }
                nativeCapabilityHarness?.let {
                    put("runtime.nativeCapabilityHarness.sha256", sha256(it))
                }
                put("runtime.buildFiles.sha256", sha256Files(profile.convertedBuildRoot, runtimeBuildFiles))
                put(
                    "runtime.compiledClassesTree.sha256",
                    sha256Tree(profile.convertedModDir.resolve("build/classes"))
                )
                put(
                    "runtime.compiledResourcesTree.sha256",
                    sha256Tree(profile.convertedModDir.resolve("build/resources"))
                )
                put(
                    "runtime.productArtifactsTree.sha256",
                    sha256Tree(profile.convertedModDir.resolve("build/libs"))
                )
                put(
                    "runtime.modDevArtifactsTree.sha256",
                    sha256Tree(profile.convertedModDir.resolve("build/moddev/artifacts"))
                )
                put("runtime.expectedMinecraftVersion", StrictSpawnValidationContract.MINECRAFT_VERSION)
                put("runtime.expectedNeoForgeVersion", StrictSpawnValidationContract.NEOFORGE_VERSION)
                put("runtime.expectedJavaMajor", "21")
            }
            val manifest = buildJsonObject {
                put("schema", StrictSpawnValidationContract.MANIFEST_SCHEMA)
                put("profileId", request.profileId)
                put("validationSessionId", request.validationSessionId)
                put("ruleId", StrictSpawnValidationContract.RULE_ID)
                put("receiptPath", request.receiptPath.toString())
                put("stationMapRequired", request.stationMapRequired)
                if (request.stationMapRequired) {
                    put("stationReceiptPath", request.stationReceiptPath.toString())
                    put("coordinatorReceiptPath", request.coordinatorReceiptPath.toString())
                }
                put("evidenceDir", request.evidenceDir.toString())
                put("requestedScenarios", stringArray(StrictSpawnValidationContract.scenarioIds))
                put("bindings", stringMap(bindings))
            }
            writeJsonAtomic(request.manifestPath, manifest)
            StrictReviewRuntimePreparation(request.copy(bindings = bindings), emptyList())
        }.fold(
            onSuccess = { it },
            onFailure = {
                StrictReviewRuntimePreparation(
                    request = null,
                    issues = listOf("Failed to seal strict spawn validation manifest: ${it.message}")
                )
            }
        )

    fun verifyFinal(
        admission: StrictReviewAdmission,
        request: StrictReviewRuntimeRequest?,
        runtimePassed: Boolean,
        runtimeNote: String,
        runtimeLog: Path?
    ): StrictReviewValidationSummary {
        if (!admission.enforced) {
            return StrictReviewValidationSummary(
                rawMediumCount = admission.rawMediumCount,
                rawLowCount = admission.rawLowCount,
                pendingCount = 0,
                validatedCount = 0,
                unresolvedCount = admission.rawReviewCount,
                state = "NOT_ENFORCED"
            )
        }
        if (admission.rawReviewCount == 0 && !admission.stationMapRequired) {
            return StrictReviewValidationSummary(
                rawMediumCount = 0,
                rawLowCount = 0,
                pendingCount = 0,
                validatedCount = 0,
                unresolvedCount = 0,
                state = "NOT_REQUIRED"
            )
        }
        if (admission.fatalIssues.isNotEmpty() ||
            admission.profile == null ||
            admission.pendingCount !in 0..1 ||
            (!admission.stationMapRequired && admission.pendingCount != 1)
        ) {
            return StrictReviewValidationSummary(
                rawMediumCount = admission.rawMediumCount,
                rawLowCount = admission.rawLowCount,
                pendingCount = admission.pendingCount,
                validatedCount = 0,
                unresolvedCount = admission.rawReviewCount,
                state = "FAILED",
                issues = admission.fatalIssues,
                profileId = admission.profile?.let { StrictSpawnValidationContract.PROFILE_ID }
            )
        }
        if (request == null) {
            val issue =
                "Strict review final gate failed: ${StrictSpawnValidationContract.PROFILE_ID} receipt was not prepared"
            return failedSummary(admission, null, listOf(issue), runtimeLog)
        }
        if (!runtimePassed) {
            val issue =
                "Strict review final gate failed: runClientWorld did not pass before receipt validation: $runtimeNote"
            return failedSummary(admission, request, listOf(issue), runtimeLog)
        }

        val issues = mutableListOf<String>()
        issues += verifyCurrentBindings(request)
        val receipt = readJsonObject(request.receiptPath, "strict spawn receipt", issues)
        if (receipt != null) {
            issues += verifyHarnessReceipt(request, receipt)
        }
        var station: JsonObject? = null
        var coordinator: JsonObject? = null
        if (request.stationMapRequired) {
            station = readJsonObject(
                request.stationReceiptPath,
                "strict station-map receipt",
                issues
            )
            coordinator = readJsonObject(
                request.coordinatorReceiptPath,
                "client-world coordinator receipt",
                issues
            )
            val context = CreateStationMapRuntimeValidation.Context(
                spawnProfileId = request.profileId,
                validationSessionId = request.validationSessionId,
                spawnReceiptPath = request.receiptPath,
                stationReceiptPath = request.stationReceiptPath,
                coordinatorReceiptPath = request.coordinatorReceiptPath,
                evidenceDir = request.evidenceDir,
                convertedModDir = request.profile.convertedModDir,
                bindings = request.bindings
            )
            if (station != null) {
                issues += CreateStationMapRuntimeValidation.verifyStationChild(context, station)
            }
            if (receipt != null && station != null && coordinator != null) {
                issues += CreateStationMapRuntimeValidation.verifyCoordinator(
                    context = context,
                    coordinator = coordinator,
                    spawn = receipt,
                    station = station
                )
            }
        }
        val wrapper = readJsonObject(request.wrapperReceiptPath, "managed wrapper receipt", issues)
        val wrapperHarness = if (request.stationMapRequired) coordinator else receipt
        val wrapperHarnessPath = if (request.stationMapRequired) {
            request.coordinatorReceiptPath
        } else {
            request.receiptPath
        }
        if (wrapper != null && wrapperHarness != null) {
            issues += verifyWrapperReceipt(
                request,
                wrapper,
                wrapperHarness,
                wrapperHarnessPath
            )
        }
        if (issues.isNotEmpty()) {
            return failedSummary(admission, request, issues, runtimeLog)
        }

        return StrictReviewValidationSummary(
            rawMediumCount = admission.rawMediumCount,
            rawLowCount = admission.rawLowCount,
            pendingCount = admission.pendingCount,
            validatedCount = admission.pendingCount,
            unresolvedCount = admission.rawReviewCount - admission.pendingCount,
            state = "VALIDATED",
            profileId = request.profileId,
            receiptPath = wrapperHarnessPath.toString(),
            receiptSha256 = sha256(wrapperHarnessPath),
            wrapperReceiptPath = request.wrapperReceiptPath.toString(),
            wrapperReceiptSha256 = sha256(request.wrapperReceiptPath),
            runtimeLogSha256 = runtimeLog?.takeIf { it.isRegularFile() }?.let(::sha256)
        )
    }

    private fun failedSummary(
        admission: StrictReviewAdmission,
        request: StrictReviewRuntimeRequest?,
        issues: List<String>,
        runtimeLog: Path?
    ): StrictReviewValidationSummary =
        StrictReviewValidationSummary(
            rawMediumCount = admission.rawMediumCount,
            rawLowCount = admission.rawLowCount,
            pendingCount = admission.pendingCount,
            validatedCount = 0,
            unresolvedCount = admission.rawReviewCount,
            state = "FAILED",
            issues = issues,
            profileId = request?.profileId ?: admission.profile?.let { StrictSpawnValidationContract.PROFILE_ID },
            receiptPath = request?.let {
                if (it.stationMapRequired) it.coordinatorReceiptPath.toString()
                else it.receiptPath.toString()
            },
            receiptSha256 = request?.let {
                val path = if (it.stationMapRequired) it.coordinatorReceiptPath else it.receiptPath
                path.takeIf { candidate -> candidate.isRegularFile() }?.let(::sha256)
            },
            wrapperReceiptPath = request?.wrapperReceiptPath?.toString(),
            wrapperReceiptSha256 = request?.wrapperReceiptPath?.takeIf { it.isRegularFile() }?.let(::sha256),
            runtimeLogSha256 = runtimeLog?.takeIf { it.isRegularFile() }?.let(::sha256)
        )

    private data class ProfileMatch(
        val profile: StrictSpawnSourceProfile?,
        val issues: List<String>
    )

    private fun matchPackageEntityProfile(
        repositoryRoot: Path,
        inputProjectDir: Path,
        convertedBuildRoot: Path,
        convertedModDir: Path,
        sourceLabel: String,
        change: Change
    ): ProfileMatch {
        val issues = mutableListOf<String>()
        if (change.before != "setCustomClientFactory(PackageEntity::spawn)" ||
            change.after != "registered EntityFactory + recreateFromPacket + real readSpawnData prefix" ||
            change.description != "Preserve raw-join and deferred-lerp phases on the native spawn lifecycle"
        ) {
            issues += "change metadata does not match the closed native-spawn migration contract"
        }

        val generatedPackageEntity = convertedModDir.resolve(packageEntityRelativePath).normalize()
        if (!pathIdentifies(change.file, generatedPackageEntity, packageEntityRelativePath)) {
            issues += "change path is not the canonical PackageEntity source: ${change.file}"
        }
        val inputPackageEntity = findUniqueFile(inputProjectDir, packageEntityRelativePath, issues, "input PackageEntity")
        if (!generatedPackageEntity.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            issues += "generated PackageEntity is missing: $generatedPackageEntity"
        }

        if (inputPackageEntity != null) {
            issues += validateCanonicalInputPackageEntity(inputPackageEntity)
        }
        if (generatedPackageEntity.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            issues += validateGeneratedPackageEntity(generatedPackageEntity)
        }

        val accessTransformer = convertedModDir.resolve(accessTransformerRelativePath).normalize()
        if (!accessTransformer.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            issues += "configured access transformer is missing: $accessTransformer"
        } else {
            issues += validatePacketAccessTransformer(accessTransformer)
        }

        val buildFiles = buildFiles(convertedBuildRoot, convertedModDir)
        if (buildFiles.isEmpty()) {
            issues += "converted project has no build.gradle or build.gradle.kts"
        } else {
            val activeBuild = buildFiles.joinToString("\n") { compactJava(it.readText()) }
            if (!activeBuild.contains("accessTransformers{") ||
                !activeBuild.contains("src/main/resources/META-INF/accesstransformer.cfg")
            ) {
                issues += "converted build does not configure the canonical access transformer"
            }
            if (!activeBuild.contains("minecraftVersion=\"${StrictSpawnValidationContract.MINECRAFT_VERSION}\"") &&
                !activeBuild.contains("minecraftVersion='${StrictSpawnValidationContract.MINECRAFT_VERSION}'")
            ) {
                issues += "converted build does not pin Minecraft ${StrictSpawnValidationContract.MINECRAFT_VERSION}"
            }
        }

        val gradleProperties = sequenceOf(
            convertedModDir.resolve("gradle.properties"),
            convertedBuildRoot.resolve("gradle.properties")
        ).distinct().firstOrNull { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
        if (gradleProperties == null) {
            issues += "converted gradle.properties is missing"
        } else {
            val properties = gradleProperties.readText()
            if (!Regex("""(?m)^minecraft_version=1\.21\.1\s*$""").containsMatchIn(properties)) {
                issues += "gradle.properties does not bind minecraft_version=1.21.1"
            }
            if (!Regex("""(?m)^neo_forge_version=21\.1\.230\s*$""").containsMatchIn(properties)) {
                issues += "gradle.properties does not bind neo_forge_version=21.1.230"
            }
        }

        val ruleSource = repositoryRoot.resolve(ruleSourceRelativePath).normalize()
        val ruleTests = repositoryRoot.resolve(ruleTestsRelativePath).normalize()
        if (!ruleSource.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            issues += "validator owner source is missing: $ruleSource"
        }
        if (!ruleTests.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            issues += "validator owner tests are missing: $ruleTests"
        }
        for (relative in listOf(
            validatorSourceRelativePath,
            harnessGeneratorRelativePath,
            stationValidatorSourceRelativePath,
            stationHarnessGeneratorRelativePath
        ) + runtimeToolRelativePaths) {
            val path = repositoryRoot.resolve(relative).normalize()
            if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                issues += "strict runtime validation input is missing: $path"
            }
        }
        if (issues.isNotEmpty() || inputPackageEntity == null || gradleProperties == null) {
            return ProfileMatch(null, issues)
        }

        val profileWithoutBindings = StrictSpawnSourceProfile(
            repositoryRoot = repositoryRoot.toAbsolutePath().normalize(),
            inputProjectDir = inputProjectDir.toAbsolutePath().normalize(),
            convertedBuildRoot = convertedBuildRoot.toAbsolutePath().normalize(),
            convertedModDir = convertedModDir.toAbsolutePath().normalize(),
            inputPackageEntity = inputPackageEntity,
            generatedPackageEntity = generatedPackageEntity,
            accessTransformer = accessTransformer,
            buildFiles = buildFiles,
            gradleProperties = gradleProperties,
            ruleSource = ruleSource,
            ruleTests = ruleTests,
            sourceLabel = sourceLabel,
            change = change,
            productBindings = emptyMap()
        )
        val bindings = productBindings(profileWithoutBindings)
        return ProfileMatch(profileWithoutBindings.copy(productBindings = bindings), emptyList())
    }

    private fun validateCanonicalInputPackageEntity(file: Path): List<String> {
        val compact = compactJava(file.readText())
        val required = linkedMapOf(
            "canonical package" to
                "packagecom.simibubi.create.content.logistics.box;",
            "legacy complex-spawn interface" to
                "publicclassPackageEntityextendsLivingEntityimplementsIEntityAdditionalSpawnData",
            "registered legacy client factory" to
                "returnboxBuilder.setCustomClientFactory(PackageEntity::spawn).sized(1,1);",
            "position constructor" to
                "publicPackageEntity(LevelworldIn,doublex,doubley,doublez){this(AllEntityTypes.PACKAGE.get(),worldIn);this.setPos(x,y,z);this.refreshDimensions();}",
            "legacy packet factory" to
                "publicstaticPackageEntityspawn(SpawnEntityspawnEntity,Levelworld){PackageEntitypackageEntity=newPackageEntity(world,spawnEntity.getPosX(),spawnEntity.getPosY(),spawnEntity.getPosZ());packageEntity.setDeltaMovement(spawnEntity.getVelX(),spawnEntity.getVelY(),spawnEntity.getVelZ());packageEntity.clientPosition=packageEntity.position();returnpackageEntity;}",
            "exact legacy lerp" to
                "publicvoidlerpMotion(doublex,doubley,doublez){setDeltaMovement(getDeltaMovement().add(x,y,z).scale(.5f));}",
            "server factory used by the validator" to
                "publicstaticPackageEntityfromItemStack(Levelworld,Vec3position,ItemStackitemstack){PackageEntitypackageEntity=AllEntityTypes.PACKAGE.get().create(world);packageEntity.setPos(position);packageEntity.setBox(itemstack);returnpackageEntity;}",
            "spawn payload writer" to
                "publicvoidwriteSpawnData(FriendlyByteBufbuffer){buffer.writeItem(getBox());Vec3motion=getDeltaMovement();buffer.writeFloat((float)motion.x);buffer.writeFloat((float)motion.y);buffer.writeFloat((float)motion.z);}",
            "spawn payload reader" to
                "publicvoidreadSpawnData(FriendlyByteBufadditionalData){setBox(additionalData.readItem());setDeltaMovement(additionalData.readFloat(),additionalData.readFloat(),additionalData.readFloat());}",
            "dimension refresh through setBox" to
                "publicvoidsetBox(ItemStackbox){this.box=box.copy();refreshDimensions();}"
        )
        val issues = required.mapNotNull { (label, marker) ->
            if (compact.contains(marker)) null else "input PackageEntity lacks $label"
        }.toMutableList()
        if (compact.countOccurrences("setCustomClientFactory(") != 1) {
            issues += "input PackageEntity must contain exactly one custom client factory registration"
        }
        if (compact.contains("recreateFromPacket(") || compact.contains("modporterLegacySpawnMotion")) {
            issues += "input PackageEntity already contains generated native-spawn lifecycle state"
        }
        return issues
    }

    private fun validateGeneratedPackageEntity(file: Path): List<String> {
        val compact = compactJava(file.readText())
        val required = linkedMapOf(
            "native complex-spawn interface" to
                "publicclassPackageEntityextendsLivingEntityimplementsIEntityWithComplexSpawn",
            "native add-entity packet import" to
                "importnet.minecraft.network.protocol.game.ClientboundAddEntityPacket;",
            "pending flag" to
                "privatebooleanmodporterLegacySpawnMotionPending;",
            "pending X field" to
                "privatedoublemodporterLegacySpawnMotionX;",
            "pending Y field" to
                "privatedoublemodporterLegacySpawnMotionY;",
            "pending Z field" to
                "privatedoublemodporterLegacySpawnMotionZ;",
            "native recreate sequence" to
                "publicvoidrecreateFromPacket(ClientboundAddEntityPacketpacket){setPos(packet.getX(),packet.getY(),packet.getZ());refreshDimensions();clientPosition=position();super.recreateFromPacket(packet);setDeltaMovement(packet.xa,packet.ya,packet.za);this.modporterLegacySpawnMotionX=packet.getXa();this.modporterLegacySpawnMotionY=packet.getYa();this.modporterLegacySpawnMotionZ=packet.getZa();this.modporterLegacySpawnMotionPending=true;}",
            "deferred lerp and authoritative payload sequence" to
                "publicvoidreadSpawnData(RegistryFriendlyByteBufadditionalData){if(this.modporterLegacySpawnMotionPending){this.modporterLegacySpawnMotionPending=false;lerpMotion(this.modporterLegacySpawnMotionX,this.modporterLegacySpawnMotionY,this.modporterLegacySpawnMotionZ);}setBox(ItemStack.OPTIONAL_STREAM_CODEC.decode(additionalData));setDeltaMovement(additionalData.readFloat(),additionalData.readFloat(),additionalData.readFloat());}",
            "native payload writer" to
                "publicvoidwriteSpawnData(RegistryFriendlyByteBufbuffer){ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer,getBox());Vec3motion=getDeltaMovement();buffer.writeFloat((float)motion.x);buffer.writeFloat((float)motion.y);buffer.writeFloat((float)motion.z);}",
            "exact legacy lerp retained" to
                "publicvoidlerpMotion(doublex,doubley,doublez){setDeltaMovement(getDeltaMovement().add(x,y,z).scale(.5f));}",
            "dimension refresh through setBox retained" to
                "publicvoidsetBox(ItemStackbox){this.box=box.copy();refreshDimensions();}"
        )
        val issues = required.mapNotNull { (label, marker) ->
            if (compact.contains(marker)) null else "generated PackageEntity lacks $label"
        }.toMutableList()
        if (compact.contains("setCustomClientFactory(") || compact.contains("SpawnEntityspawnEntity")) {
            issues += "generated PackageEntity retains the removed legacy client factory"
        }
        if (compact.countOccurrences("publicvoidrecreateFromPacket(") != 1) {
            issues += "generated PackageEntity must contain exactly one recreateFromPacket override"
        }
        if (compact.countOccurrences("modporterLegacySpawnMotionPending") != 4) {
            issues += "generated pending-motion flag use count drifted"
        }
        return issues
    }

    private fun validatePacketAccessTransformer(file: Path): List<String> {
        val active = file.readText().lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return if (packetAccessTransformerLines.all { line -> active.count { it == line } == 1 }) {
            emptyList()
        } else {
            listOf("access transformer must expose packet xa/ya/za exactly once")
        }
    }

    private fun isCanonicalPacketAccessSupportChange(change: Change): Boolean {
        if (change.confidence != Confidence.HIGH ||
            change.description != "Expose raw native add-entity packet velocity for legacy spawn order"
        ) {
            return false
        }
        val addedLines = change.after.split(" + ")
        if (addedLines.isEmpty() ||
            addedLines.distinct().size != addedLines.size ||
            addedLines.any { it !in packetAccessTransformerLines }
        ) {
            return false
        }
        val expectedOrder = packetAccessTransformerLines.filter { it in addedLines }
        return addedLines == expectedOrder &&
            change.before == addedLines.joinToString(" + ") { "missing $it" }
    }

    private fun verifyDataGenerationReceipt(
        request: StrictReviewRuntimeRequest,
        runId: String,
        reference: CreateDataGenerationEvidence.ReceiptReference
    ) {
        val profile = request.profile
        val reportsDir = checkNotNull(request.manifestPath.parent)
            .toAbsolutePath()
            .normalize()
        val expectedReceipt = reportsDir.resolve("create-data-generation-receipt.json")
        check(reference.path.toAbsolutePath().normalize() == expectedReceipt) {
            "Create data-generation receipt path differs from the strict review run: ${reference.path}"
        }
        check(sha256Pattern.matches(reference.sha256)) {
            "Create data-generation receipt reference has an invalid SHA-256"
        }
        CreateDataGenerationEvidence.verifyReference(reference, reportsDir)

        val root = json.parseToJsonElement(reference.path.readText()).jsonObject
        check(
            root.keys == setOf(
                "schema",
                "runId",
                "caseId",
                "ruleId",
                "buildRoot",
                "modDir",
                "runtimeLease",
                "logs",
                "files"
            )
        ) {
            "Create data-generation receipt fields are incomplete or unexpected"
        }
        check(string(root, "schema") == CreateDataGenerationEvidence.SCHEMA) {
            "Create data-generation receipt schema differs"
        }
        check(string(root, "runId") == runId) {
            "Create data-generation receipt belongs to another benchmark run"
        }
        check(string(root, "caseId") == "create") {
            "Create data-generation receipt belongs to another benchmark case"
        }
        check(string(root, "ruleId") == CreateCopperDataMapMigration.RULE_ID) {
            "Create data-generation receipt rule differs"
        }
        check(receiptPath(root, "buildRoot") == profile.convertedBuildRoot.toAbsolutePath().normalize()) {
            "Create data-generation receipt build root differs"
        }
        check(receiptPath(root, "modDir") == profile.convertedModDir.toAbsolutePath().normalize()) {
            "Create data-generation receipt mod directory differs"
        }

        val runtimeLease = root["runtimeLease"]?.objectOrNull()
            ?: error("Create data-generation receipt has no runtime lease evidence")
        check(
            runtimeLease.keys ==
                setOf("path", "bytes", "sha256", "sessionId", "runtimeDirectory", "launcher")
        ) {
            "Create data-generation runtime lease evidence is incomplete or unexpected"
        }
        verifyReceiptFileEvidence(
            runtimeLease,
            "Create runData lease receipt",
            reportsDir.resolve("create-runData-session.json"),
            runtimeLease.keys
        )
        check(string(runtimeLease, "sessionId").orEmpty().isNotBlank()) {
            "Create data-generation runtime lease has no session id"
        }
        check(
            receiptPath(runtimeLease, "runtimeDirectory") ==
                profile.convertedModDir.resolve("run").toAbsolutePath().normalize()
        ) {
            "Create data-generation runtime directory differs"
        }
        val launcher = runtimeLease["launcher"]?.objectOrNull()
            ?: error("Create data-generation receipt has no runData launcher evidence")
        val launcherRoot = profile.convertedBuildRoot.resolve(
            "build/minecraft-mod-testing/nested-launchers"
        ).toAbsolutePath().normalize()
        verifyReceiptFileEvidence(
            launcher,
            "Create runData launcher",
            expectedRoot = launcherRoot
        )

        val logs = root["logs"]?.objectOrNull()
            ?: error("Create data-generation receipt has no log evidence")
        check(logs.keys == setOf("runData", "processResources")) {
            "Create data-generation receipt log evidence is incomplete or unexpected"
        }
        verifyReceiptFileEvidence(
            logs["runData"]?.objectOrNull()
                ?: error("Create data-generation receipt has no runData log evidence"),
            "Create runData log",
            reportsDir.resolve("create-runData.log")
        )
        verifyReceiptFileEvidence(
            logs["processResources"]?.objectOrNull()
                ?: error("Create data-generation receipt has no processResources log evidence"),
            "Create processResources log",
            reportsDir.resolve("create-processResources.log")
        )

        val files = root["files"]?.arrayOrNull()
            ?: error("Create data-generation receipt has no generated file evidence")
        check(files.size == expectedCreateDataMaps.size) {
            "Create data-generation receipt has incomplete generated file evidence"
        }
        val evidenceByName = files.map { element ->
            element.objectOrNull()
                ?: error("Create data-generation receipt contains non-object file evidence")
        }.associateBy { evidence ->
            string(evidence, "name")
                ?: error("Create data-generation receipt file evidence has no name")
        }
        check(evidenceByName.size == files.size && evidenceByName.keys == expectedCreateDataMaps.keys) {
            "Create data-generation receipt file names are incomplete or duplicated"
        }
        for ((name, expectedCount) in expectedCreateDataMaps) {
            val evidence = checkNotNull(evidenceByName[name])
            check(evidence.keys == setOf("name", "valueCount", "generated", "packaged")) {
                "Create data-generation evidence for $name is incomplete or unexpected"
            }
            check(int(evidence, "valueCount") == expectedCount) {
                "Create data-generation evidence value count differs for $name"
            }
            val relative = Path.of("data/neoforge/data_maps/block").resolve(name)
            val generated = verifyReceiptFileEvidence(
                evidence["generated"]?.objectOrNull()
                    ?: error("Create data-generation receipt lacks generated evidence for $name"),
                "Generated Create data map $name",
                profile.convertedModDir.resolve("src/generated/resources").resolve(relative)
            )
            val packaged = verifyReceiptFileEvidence(
                evidence["packaged"]?.objectOrNull()
                    ?: error("Create data-generation receipt lacks packaged evidence for $name"),
                "Packaged Create data map $name",
                profile.convertedModDir.resolve("build/resources/main").resolve(relative)
            )
            check(generated.contentEquals(packaged)) {
                "Generated and packaged Create data maps differ after receipt sealing: $name"
            }
            val values = json.parseToJsonElement(
                generated.toString(StandardCharsets.UTF_8)
            ).jsonObject["values"]?.objectOrNull()
                ?: error("Generated Create data map has no object-valued values: $name")
            check(values.size == expectedCount) {
                "Generated Create data map $name has ${values.size} values; expected $expectedCount"
            }
        }
    }

    private fun verifyReceiptFileEvidence(
        evidence: JsonObject,
        label: String,
        expectedPath: Path? = null,
        expectedKeys: Set<String> = setOf("path", "bytes", "sha256"),
        expectedRoot: Path? = null
    ): ByteArray {
        check(evidence.keys == expectedKeys) {
            "$label evidence is incomplete or unexpected"
        }
        val path = receiptPath(evidence, "path")
        if (expectedPath != null) {
            check(path == expectedPath.toAbsolutePath().normalize()) {
                "$label path differs: expected $expectedPath, got $path"
            }
        }
        if (expectedRoot != null) {
            check(path.startsWith(expectedRoot.toAbsolutePath().normalize())) {
                "$label is outside the expected directory: $path"
            }
        }
        check(path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "$label is missing or linked: $path"
        }
        val bytes = path.readBytes()
        check(long(evidence, "bytes") == bytes.size.toLong()) {
            "$label byte count changed after receipt sealing"
        }
        check(string(evidence, "sha256") == sha256(bytes)) {
            "$label SHA-256 changed after receipt sealing"
        }
        return bytes
    }

    private fun receiptPath(objectValue: JsonObject, key: String): Path {
        val value = string(objectValue, key)
            ?: error("Create data-generation receipt field '$key' is missing")
        return Path.of(value).toAbsolutePath().normalize()
    }

    private fun productBindings(profile: StrictSpawnSourceProfile): Map<String, String> {
        val sourceCommit = gitCommitPattern.find(profile.sourceLabel)?.groupValues?.get(1)?.lowercase()
            ?: "unavailable"
        val resourcesRoot = profile.convertedModDir.resolve("src/main/resources")
        val assetsRoot = resourcesRoot.resolve("assets")
        return linkedMapOf(
            "profile.id" to StrictSpawnValidationContract.PROFILE_ID,
            "rule.id" to StrictSpawnValidationContract.RULE_ID,
            "rule.change.sha256" to sha256(
                listOf(
                    profile.change.ruleId,
                    profile.change.file.toString(),
                    profile.change.line.toString(),
                    profile.change.description,
                    profile.change.before,
                    profile.change.after,
                    profile.change.confidence.name
                ).joinToString("\u0000").toByteArray()
            ),
            "rule.source.sha256" to sha256(profile.ruleSource),
            "rule.tests.sha256" to sha256(profile.ruleTests),
            "validator.source.sha256" to sha256(profile.repositoryRoot.resolve(validatorSourceRelativePath)),
            "validator.harnessGenerator.sha256" to
                sha256(profile.repositoryRoot.resolve(harnessGeneratorRelativePath)),
            "validator.stationSource.sha256" to
                sha256(profile.repositoryRoot.resolve(stationValidatorSourceRelativePath)),
            "validator.stationHarnessGenerator.sha256" to
                sha256(profile.repositoryRoot.resolve(stationHarnessGeneratorRelativePath)),
            "validator.nativeCapabilitySource.sha256" to
                sha256(profile.repositoryRoot.resolve(nativeCapabilityValidatorRelativePath)),
            "validator.nativeCapabilityHarnessGenerator.sha256" to
                sha256(profile.repositoryRoot.resolve(nativeCapabilityHarnessGeneratorRelativePath)),
            "validator.runtimeTools.sha256" to sha256Files(
                profile.repositoryRoot,
                runtimeToolRelativePaths.map(profile.repositoryRoot::resolve)
            ),
            "input.sourceLabel.sha256" to sha256(profile.sourceLabel.toByteArray()),
            "input.gitCommit" to sourceCommit,
            "input.packageEntity.sha256" to sha256(profile.inputPackageEntity),
            "input.sourceTree.sha256" to sha256Tree(profile.inputProjectDir.resolve("src")),
            "generated.packageEntity.sha256" to sha256(profile.generatedPackageEntity),
            "generated.productJavaTree.sha256" to sha256TreeExcludingNames(
                profile.convertedModDir.resolve("src/main/java"),
                setOf(
                    StrictSpawnValidationContract.GENERIC_HARNESS_FILE,
                    StrictSpawnValidationContract.GENERATED_HARNESS_FILE,
                    StrictSpawnValidationContract.STATION_HARNESS_FILE,
                    StrictSpawnValidationContract.NATIVE_CAPABILITY_HARNESS_FILE
                )
            ),
            "generated.accessTransformer.sha256" to sha256(profile.accessTransformer),
            "generated.productBuildFiles.sha256" to sha256Files(profile.convertedBuildRoot, profile.buildFiles),
            "generated.gradleProperties.sha256" to sha256(profile.gradleProperties),
            "generated.resourcesTree.sha256" to sha256Tree(resourcesRoot),
            "generated.assetsTree.sha256" to sha256Tree(assetsRoot),
            "generated.generatedResourcesTree.sha256" to
                sha256Tree(profile.convertedModDir.resolve("src/generated/resources")),
            "generated.generatedAssetsTree.sha256" to
                sha256Tree(profile.convertedModDir.resolve("src/generated/resources/assets")),
            "runtime.expectedMinecraftVersion" to StrictSpawnValidationContract.MINECRAFT_VERSION,
            "runtime.expectedNeoForgeVersion" to StrictSpawnValidationContract.NEOFORGE_VERSION,
            "runtime.expectedJavaMajor" to "21"
        )
    }

    private fun verifyCurrentBindings(request: StrictReviewRuntimeRequest): List<String> {
        val issues = mutableListOf<String>()
        val profile = request.profile
        val current = linkedMapOf(
            "rule.source.sha256" to profile.ruleSource,
            "rule.tests.sha256" to profile.ruleTests,
            "validator.source.sha256" to profile.repositoryRoot.resolve(validatorSourceRelativePath),
            "validator.harnessGenerator.sha256" to profile.repositoryRoot.resolve(harnessGeneratorRelativePath),
            "validator.stationSource.sha256" to
                profile.repositoryRoot.resolve(stationValidatorSourceRelativePath),
            "validator.stationHarnessGenerator.sha256" to
                profile.repositoryRoot.resolve(stationHarnessGeneratorRelativePath),
            "validator.nativeCapabilitySource.sha256" to
                profile.repositoryRoot.resolve(nativeCapabilityValidatorRelativePath),
            "validator.nativeCapabilityHarnessGenerator.sha256" to
                profile.repositoryRoot.resolve(nativeCapabilityHarnessGeneratorRelativePath),
            "input.packageEntity.sha256" to profile.inputPackageEntity,
            "generated.packageEntity.sha256" to profile.generatedPackageEntity,
            "generated.accessTransformer.sha256" to profile.accessTransformer,
            "generated.gradleProperties.sha256" to profile.gradleProperties
        )
        for ((binding, path) in current) {
            if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                issues += "Bound file disappeared before final validation: $path"
                continue
            }
            val expected = request.bindings[binding]
            val actual = sha256(path)
            if (expected != actual) {
                issues += "Bound file changed before final validation: $binding expected=$expected actual=$actual"
            }
        }
        val currentDerivedBindings = linkedMapOf(
            "validator.runtimeTools.sha256" to sha256Files(
                profile.repositoryRoot,
                runtimeToolRelativePaths.map(profile.repositoryRoot::resolve)
            ),
            "input.sourceTree.sha256" to sha256Tree(profile.inputProjectDir.resolve("src")),
            "generated.productJavaTree.sha256" to sha256TreeExcludingNames(
                profile.convertedModDir.resolve("src/main/java"),
                setOf(
                    StrictSpawnValidationContract.GENERIC_HARNESS_FILE,
                    StrictSpawnValidationContract.GENERATED_HARNESS_FILE,
                    StrictSpawnValidationContract.STATION_HARNESS_FILE,
                    StrictSpawnValidationContract.NATIVE_CAPABILITY_HARNESS_FILE
                )
            ),
            "generated.resourcesTree.sha256" to
                sha256Tree(profile.convertedModDir.resolve("src/main/resources")),
            "generated.assetsTree.sha256" to
                sha256Tree(profile.convertedModDir.resolve("src/main/resources/assets")),
            "generated.generatedResourcesTree.sha256" to
                sha256Tree(profile.convertedModDir.resolve("src/generated/resources")),
            "generated.generatedAssetsTree.sha256" to
                sha256Tree(profile.convertedModDir.resolve("src/generated/resources/assets")),
            "runtime.compiledResourcesTree.sha256" to
                sha256Tree(profile.convertedModDir.resolve("build/resources")),
            "runtime.productArtifactsTree.sha256" to
                sha256Tree(profile.convertedModDir.resolve("build/libs")),
            "runtime.modDevArtifactsTree.sha256" to
                sha256Tree(profile.convertedModDir.resolve("build/moddev/artifacts"))
        )
        for ((binding, actual) in currentDerivedBindings) {
            val expected = request.bindings[binding]
            if (expected != actual) {
                issues += "Bound tree changed before final validation: $binding expected=$expected actual=$actual"
            }
        }
        val staged = runCatching {
            linkedMapOf<String, Path>().apply {
                put(
                    "runtime.genericHarness.sha256",
                    findUniqueNamedFile(
                        profile.convertedModDir.resolve("src/main/java"),
                        StrictSpawnValidationContract.GENERIC_HARNESS_FILE
                    )
                )
                put(
                    "runtime.strictHarness.sha256",
                    findUniqueNamedFile(
                        profile.convertedModDir.resolve("src/main/java"),
                        StrictSpawnValidationContract.GENERATED_HARNESS_FILE
                    )
                )
                if (request.stationMapRequired) {
                    put(
                        "runtime.stationHarness.sha256",
                        findUniqueNamedFile(
                            profile.convertedModDir.resolve("src/main/java"),
                            StrictSpawnValidationContract.STATION_HARNESS_FILE
                        )
                    )
                }
                if (request.bindings.containsKey("runtime.nativeCapabilityHarness.sha256")) {
                    put(
                        "runtime.nativeCapabilityHarness.sha256",
                        findUniqueNamedFile(
                            profile.convertedModDir.resolve("src/main/java"),
                            StrictSpawnValidationContract.NATIVE_CAPABILITY_HARNESS_FILE
                        )
                    )
                } else {
                    check(
                        findOptionalUniqueNamedFile(
                            profile.convertedModDir.resolve("src/main/java"),
                            StrictSpawnValidationContract.NATIVE_CAPABILITY_HARNESS_FILE
                        ) == null
                    ) {
                        "Unexpected native capability harness appeared after manifest sealing"
                    }
                }
            }
        }.getOrElse {
            issues += "Could not resolve the staged runtime harnesses: ${it.message}"
            return issues
        }
        for ((binding, path) in staged) {
            if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                issues += "Staged runtime harness disappeared before final validation: $path"
                continue
            }
            val expected = request.bindings[binding]
            val actual = sha256(path)
            if (expected != actual) {
                issues += "Staged runtime harness changed before final validation: $binding expected=$expected actual=$actual"
            }
        }
        val runtimeBuildHash = sha256Files(
            profile.convertedBuildRoot,
            buildFiles(profile.convertedBuildRoot, profile.convertedModDir)
        )
        if (request.bindings["runtime.buildFiles.sha256"] != runtimeBuildHash) {
            issues += "Runtime build files changed after strict spawn manifest sealing"
        }
        return issues
    }

    private fun verifyHarnessReceipt(
        request: StrictReviewRuntimeRequest,
        root: JsonObject
    ): List<String> {
        val issues = mutableListOf<String>()
        requireString(root, "schema", StrictSpawnValidationContract.RECEIPT_SCHEMA, issues)
        requireString(root, "profileId", request.profileId, issues)
        requireString(root, "validationSessionId", request.validationSessionId, issues)
        requireString(root, "status", "PASS", issues)
        val wrapperSessionId = string(root, "sessionId")
        if (wrapperSessionId.isNullOrBlank()) {
            issues += "receipt.sessionId must contain the managed wrapper session id"
        } else {
            runCatching { UUID.fromString(wrapperSessionId) }
                .onFailure { issues += "receipt.sessionId is not a UUID" }
        }
        requireInt(root, "processStartCount", 1, issues)
        requireInt(root, "worldLoadCount", 1, issues)
        requireInt(root, "scenarioCount", StrictSpawnValidationContract.scenarioIds.size, issues)
        requireInt(root, "failedCount", 0, issues)
        requireInt(root, "cleanupFailureCount", 0, issues)
        requireInt(root, "overlapFailureCount", 0, issues)
        requireBoolean(root, "allScenariosPassed", true, issues)
        requireBoolean(root, "finalExitRequested", true, issues)
        requireStringList(root, "requestedScenarios", StrictSpawnValidationContract.scenarioIds, issues)
        requireStringList(root, "observedScenarios", StrictSpawnValidationContract.scenarioIds, issues)

        val receiptBindings = root["bindings"]?.asStringMap()
        if (receiptBindings == null) {
            issues += "receipt.bindings must be an object containing only strings"
        } else if (receiptBindings != request.bindings) {
            issues += "receipt.bindings do not exactly match the sealed validation manifest"
        }

        val runtime = root["runtime"]?.objectOrNull()
        if (runtime == null) {
            issues += "receipt.runtime must be an object"
        } else {
            requireString(runtime, "minecraftVersion", StrictSpawnValidationContract.MINECRAFT_VERSION, issues)
            requireString(runtime, "neoForgeVersion", StrictSpawnValidationContract.NEOFORGE_VERSION, issues)
            val javaVersion = string(runtime, "javaVersion")
            if (javaVersion == null || javaMajor(javaVersion) != 21) {
                issues += "receipt.runtime.javaVersion must identify Java 21"
            }
            if (string(runtime, "javaVendor").isNullOrBlank()) {
                issues += "receipt.runtime.javaVendor must be non-empty"
            }
            if (string(runtime, "javaHome").isNullOrBlank()) {
                issues += "receipt.runtime.javaHome must be non-empty"
            }
            issues += verifyRuntimeBytes(request, runtime)
        }

        val scenarios = root["scenarios"]?.arrayOrNull()
        if (scenarios == null) {
            issues += "receipt.scenarios must be an array"
        } else if (scenarios.size != StrictSpawnValidationContract.scenarioIds.size) {
            issues += "receipt.scenarios must contain exactly ${StrictSpawnValidationContract.scenarioIds.size} entries"
        } else {
            for ((index, expectedId) in StrictSpawnValidationContract.scenarioIds.withIndex()) {
                val scenario = scenarios[index].objectOrNull()
                if (scenario == null) {
                    issues += "receipt.scenarios[$index] must be an object"
                    continue
                }
                issues += verifyScenario(request, scenario, expectedId)
            }
        }
        return issues
    }

    private data class RuntimeClasspathEntry(
        val path: Path,
        val kind: String,
        val sha256: String
    )

    private fun verifyRuntimeBytes(
        request: StrictReviewRuntimeRequest,
        runtime: JsonObject
    ): List<String> {
        val issues = mutableListOf<String>()
        val javaExecutableValue = string(runtime, "javaExecutable")
        val javaExecutableHash = string(runtime, "javaExecutableSha256")
        val javaExecutable = javaExecutableValue?.let {
            runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull()
        }
        if (javaExecutable == null ||
            !javaExecutable.isRegularFile(LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(javaExecutable)
        ) {
            issues += "receipt.runtime.javaExecutable must be a non-symlink regular file"
        } else {
            val real = runCatching { javaExecutable.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            if (real == null || real.toString() != javaExecutableValue) {
                issues += "receipt.runtime.javaExecutable must be its real absolute path"
            }
            if (javaExecutableHash == null || !sha256Pattern.matches(javaExecutableHash)) {
                issues += "receipt.runtime.javaExecutableSha256 must be lowercase SHA-256"
            } else if (sha256(javaExecutable) != javaExecutableHash) {
                issues += "receipt.runtime.javaExecutableSha256 does not match the executable bytes"
            }
        }

        val rawEntries = runtime["classpathEntries"]?.arrayOrNull()
        val entries = mutableListOf<RuntimeClasspathEntry>()
        if (rawEntries == null || rawEntries.isEmpty()) {
            issues += "receipt.runtime.classpathEntries must be a non-empty array"
        } else {
            for ((index, element) in rawEntries.withIndex()) {
                val entry = element.objectOrNull()
                if (entry == null) {
                    issues += "receipt.runtime.classpathEntries[$index] must be an object"
                    continue
                }
                val pathValue = string(entry, "path")
                val kind = string(entry, "kind")
                val expectedHash = string(entry, "sha256")
                if (pathValue.isNullOrBlank() || kind == null || kind !in setOf("file", "directory") ||
                    expectedHash == null || !sha256Pattern.matches(expectedHash)
                ) {
                    issues += "receipt.runtime.classpathEntries[$index] has invalid path/kind/hash"
                    continue
                }
                val boundHash = checkNotNull(expectedHash)
                val path = runCatching { Path.of(pathValue).toAbsolutePath().normalize() }.getOrNull()
                val real = path?.let {
                    runCatching { it.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
                }
                if (path == null || real == null || real.toString() != pathValue || Files.isSymbolicLink(path)) {
                    issues += "receipt.runtime.classpathEntries[$index] must name a real non-symlink absolute path"
                    continue
                }
                val actualHash = when (kind) {
                    "file" -> if (path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) sha256(path) else null
                    "directory" -> if (path.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                        runCatching { sha256RuntimeDirectory(path) }.getOrNull()
                    } else {
                        null
                    }
                    else -> null
                }
                if (actualHash == null) {
                    issues += "receipt.runtime.classpathEntries[$index] kind does not match the current path"
                    continue
                }
                if (actualHash != boundHash) {
                    issues += "receipt.runtime.classpathEntries[$index] hash changed after runtime exit"
                }
                entries += RuntimeClasspathEntry(path, kind, boundHash)
            }
        }
        if (entries.map { it.path }.distinct().size != entries.size) {
            issues += "receipt.runtime.classpathEntries contains duplicate real paths"
        }
        val expectedClasspathHash = string(runtime, "classpathSha256")
        if (expectedClasspathHash == null || !sha256Pattern.matches(expectedClasspathHash)) {
            issues += "receipt.runtime.classpathSha256 must be lowercase SHA-256"
        } else if (entries.size == rawEntries?.size &&
            sha256RuntimeClasspath(entries) != expectedClasspathHash
        ) {
            issues += "receipt.runtime.classpathSha256 does not bind the ordered entry paths and bytes"
        }

        val classResources = runtime["classResources"]?.objectOrNull()
        if (classResources == null) {
            issues += "receipt.runtime.classResources must be an object"
        } else if (entries.isNotEmpty()) {
            val sourceRoot = request.profile.convertedModDir.resolve("src/main/java")
            val strictHarness = runCatching {
                findUniqueNamedFile(sourceRoot, StrictSpawnValidationContract.GENERATED_HARNESS_FILE)
            }.getOrNull()
            val genericHarness = runCatching {
                findUniqueNamedFile(sourceRoot, StrictSpawnValidationContract.GENERIC_HARNESS_FILE)
            }.getOrNull()
            val expectedResources = linkedMapOf(
                "packageEntity" to
                    "com/simibubi/create/content/logistics/box/PackageEntity.class",
                "strictHarness" to strictHarness?.let {
                    sourceRoot.relativize(it).toString()
                        .replace('\\', '/')
                        .removeSuffix(".java") + ".class"
                },
                "genericHarness" to genericHarness?.let {
                    sourceRoot.relativize(it).toString()
                        .replace('\\', '/')
                        .removeSuffix(".java") + ".class"
                }
            )
            for ((key, expectedResource) in expectedResources) {
                val resource = classResources[key]?.objectOrNull()
                if (resource == null || expectedResource == null) {
                    issues += "receipt.runtime.classResources.$key is missing"
                    continue
                }
                val resourceName = string(resource, "resource")
                val expectedHash = string(resource, "sha256")
                if (resourceName != expectedResource ||
                    expectedHash == null ||
                    !sha256Pattern.matches(expectedHash)
                ) {
                    issues += "receipt.runtime.classResources.$key has invalid resource/hash"
                    continue
                }
                val actualHash = hashClasspathResource(entries, resourceName)
                if (actualHash == null) {
                    issues += "receipt.runtime.classResources.$key is absent from the bound classpath"
                } else if (actualHash != expectedHash) {
                    issues += "receipt.runtime.classResources.$key does not match the loaded class bytes"
                }
            }
        }
        return issues
    }

    private fun verifyScenario(
        request: StrictReviewRuntimeRequest,
        scenario: JsonObject,
        expectedId: String
    ): List<String> {
        val issues = mutableListOf<String>()
        requireString(scenario, "id", expectedId, issues)
        requireBoolean(scenario, "passed", true, issues)
        requireBoolean(scenario, "cleanupPassed", true, issues)
        val startedAtTick = long(scenario, "startedAtTick")
        val finishedAtTick = long(scenario, "finishedAtTick")
        if (startedAtTick == null || finishedAtTick == null ||
            startedAtTick < 0L || finishedAtTick < startedAtTick
        ) {
            issues += "scenario $expectedId has invalid tick bounds"
        }
        val serverEntityId = int(scenario, "serverEntityId")
        if (serverEntityId == null || serverEntityId <= 0) {
            issues += "scenario $expectedId must record a positive serverEntityId"
        }
        val serverUuid = string(scenario, "serverEntityUuid")
        if (serverUuid.isNullOrBlank() || runCatching { UUID.fromString(serverUuid) }.isFailure) {
            issues += "scenario $expectedId must record a valid serverEntityUuid"
        }
        val clientIds = scenario["clientEntityIds"]?.asIntList()
        if (clientIds == null || clientIds.isEmpty() || clientIds.any { it <= 0 }) {
            issues += "scenario $expectedId must record positive clientEntityIds"
        }
        val clientUuids = scenario["clientEntityUuids"]?.asStringList()
        if (clientUuids == null || clientUuids.isEmpty() ||
            clientUuids.any { runCatching { UUID.fromString(it) }.isFailure }
        ) {
            issues += "scenario $expectedId must record valid clientEntityUuids"
        }
        val identityTokens = scenario["clientIdentityTokens"]?.asStringList()
        if (identityTokens == null || identityTokens.isEmpty()) {
            issues += "scenario $expectedId must record clientIdentityTokens"
        }
        if (expectedId == "tracker_reentry" &&
            (identityTokens == null || identityTokens.size < 2 || identityTokens.distinct().size < 2)
        ) {
            issues += "tracker_reentry must prove two distinct client entity instances"
        }

        val expectedChecks = if (expectedId == "tracker_reentry") {
            StrictSpawnValidationContract.trackerSemanticCheckIds
        } else {
            StrictSpawnValidationContract.lifecycleSemanticCheckIds
        }
        val semanticChecks = scenario["semanticChecks"]?.arrayOrNull()
        if (semanticChecks == null) {
            issues += "scenario $expectedId semanticChecks must be an array"
        } else {
            val ids = mutableListOf<String>()
            for ((checkIndex, element) in semanticChecks.withIndex()) {
                val check = element.objectOrNull()
                if (check == null) {
                    issues += "scenario $expectedId semanticChecks[$checkIndex] must be an object"
                    continue
                }
                val id = string(check, "id")
                if (id == null) {
                    issues += "scenario $expectedId semanticChecks[$checkIndex].id is missing"
                } else {
                    ids += id
                }
                if (boolean(check, "passed") != true) {
                    issues += "scenario $expectedId semantic check ${id ?: checkIndex.toString()} did not pass"
                }
            }
            if (ids != expectedChecks) {
                issues += "scenario $expectedId semantic check order/content drifted: $ids"
            }
        }
        val observations = scenario["observations"]?.objectOrNull()
        if (observations == null || observations.isEmpty()) {
            issues += "scenario $expectedId must retain non-empty semantic observations"
        }
        if (expectedId == "tracker_reentry") {
            issues += verifyTrackerExitTrace(scenario)
        }

        val artifacts = scenario["artifacts"]?.arrayOrNull()
        if (artifacts == null || artifacts.isEmpty()) {
            issues += "scenario $expectedId must retain at least one hashed semantic artifact"
        } else {
            for ((artifactIndex, element) in artifacts.withIndex()) {
                val artifact = element.objectOrNull()
                if (artifact == null) {
                    issues += "scenario $expectedId artifacts[$artifactIndex] must be an object"
                    continue
                }
                val pathValue = string(artifact, "path")
                val expectedSha = string(artifact, "sha256")
                if (pathValue.isNullOrBlank() || expectedSha == null || !sha256Pattern.matches(expectedSha)) {
                    issues += "scenario $expectedId artifacts[$artifactIndex] has invalid path/hash"
                    continue
                }
                val artifactPath = resolveConfinedArtifact(request.evidenceDir, pathValue)
                if (artifactPath == null) {
                    issues += "scenario $expectedId artifact escapes evidence directory: $pathValue"
                } else if (!artifactPath.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                    issues += "scenario $expectedId artifact is missing: $artifactPath"
                } else {
                    val actualSha = sha256(artifactPath)
                    if (actualSha != expectedSha) {
                        issues +=
                            "scenario $expectedId artifact hash mismatch: $artifactPath expected=$expectedSha actual=$actualSha"
                    }
                }
            }
        }
        return issues
    }

    /**
     * The tracker exit must leave the entity's native tracking boundary (ChunkMap's min of the scaled
     * type range and the view distance) by one margin, to a destination the player already held.
     */
    internal fun verifyTrackerExitTrace(scenario: JsonObject): List<String> {
        val tracker = scenario["observations"]
            ?.objectOrNull()
            ?.get("tracker")
            ?.objectOrNull()
            ?: return listOf("tracker_reentry tracker observations are missing")
        val exit = tracker["exit"]?.objectOrNull()
            ?: return listOf("tracker_reentry tracker exit observations are missing")
        val issues = mutableListOf<String>()
        val typeRange = int(exit, "typeRangeBlocks")
        val scaledRange = int(exit, "scaledRangeBlocks")
        val viewDistance = int(exit, "viewDistanceBlocks")
        if (typeRange == null || typeRange <= 0 || scaledRange == null || scaledRange <= 0 ||
            viewDistance == null || viewDistance <= 0
        ) {
            issues += "tracker exit typeRangeBlocks, scaledRangeBlocks and viewDistanceBlocks must be positive"
        }
        val effective = double(exit, "effectiveRangeBlocks")
        if (effective == null || !effective.isFinite() ||
            (scaledRange != null && viewDistance != null &&
                abs(effective - minOf(scaledRange, viewDistance)) > trackerExitEpsilon)
        ) {
            issues += "tracker exit effectiveRangeBlocks must be min(scaledRangeBlocks, viewDistanceBlocks)"
        }
        val margin = double(exit, "marginBlocks")
        if (margin == null || abs(margin - trackerExitMarginBlocks) > trackerExitEpsilon) {
            issues += "tracker exit marginBlocks must be $trackerExitMarginBlocks"
        }
        val distance = double(exit, "exitDistanceBlocks")
        if (distance == null || !distance.isFinite() || effective == null ||
            distance < effective + trackerExitMarginBlocks - trackerExitEpsilon
        ) {
            issues += "tracker exit distance must reach one margin past the native tracking boundary, " +
                "observed ${distance ?: "<missing>"} for range ${effective ?: "<missing>"}"
        }
        if (boolean(exit, "destinationResident") != true) {
            issues += "tracker exit destination must be resident before the exit teleport"
        }
        return issues
    }

    private fun verifyWrapperReceipt(
        request: StrictReviewRuntimeRequest,
        wrapper: JsonObject,
        harness: JsonObject,
        expectedHarnessPath: Path
    ): List<String> {
        val issues = mutableListOf<String>()
        requireInt(wrapper, "schemaVersion", 1, issues)
        requireString(wrapper, "runtimeKind", "client", issues)
        requireInt(wrapper, "processStartCount", 1, issues)
        requireBoolean(wrapper, "processInventoryChecked", true, issues)
        requireBoolean(wrapper, "harnessValidated", true, issues)
        requireBoolean(wrapper, "passed", true, issues)
        requireInt(wrapper, "exitCode", 0, issues)
        if (!string(wrapper, "failure").isNullOrEmpty()) {
            issues += "managed wrapper recorded a failure"
        }
        val wrapperSession = string(wrapper, "sessionId")
        val harnessSession = string(harness, "sessionId")
        if (wrapperSession.isNullOrBlank() || wrapperSession != harnessSession) {
            issues += "managed wrapper and harness sessionId values do not match"
        }
        val runtimeDirectory = string(wrapper, "runtimeDirectory")?.let { Path.of(it).toAbsolutePath().normalize() }
        if (runtimeDirectory != request.runtimeDirectory) {
            issues += "managed wrapper runtimeDirectory does not match the declared run directory"
        }
        val projectRoot = string(wrapper, "projectRoot")?.let { Path.of(it).toAbsolutePath().normalize() }
        if (projectRoot != request.profile.convertedBuildRoot) {
            issues += "managed wrapper projectRoot does not match the converted build root"
        }
        val harnessReceiptPath =
            string(wrapper, "harnessReceiptPath")?.let { Path.of(it).toAbsolutePath().normalize() }
        if (harnessReceiptPath != expectedHarnessPath) {
            issues += "managed wrapper did not validate the expected harness receipt"
        }
        val harnessSha = string(wrapper, "harnessReceiptSha256")
        val actualHarnessSha = sha256(expectedHarnessPath)
        if (harnessSha != actualHarnessSha) {
            issues += "managed wrapper harnessReceiptSha256 does not match the strict spawn receipt"
        }
        val activeProcesses = wrapper["activeRuntimeProcesses"]?.arrayOrNull()
        if (activeProcesses == null || activeProcesses.isNotEmpty()) {
            issues += "managed wrapper must record a clean pre-launch runtime process inventory"
        }
        val startedAt = string(wrapper, "startedAtUtc")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val finishedAt = string(wrapper, "finishedAtUtc")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)) {
            issues += "managed wrapper must record ordered UTC start and finish instants"
        }
        val command = wrapper["command"]?.objectOrNull()
        if (command == null) {
            issues += "managed wrapper command must be an object"
        } else {
            val arguments = command["arguments"]?.asStringList()
            if (arguments != emptyList<String>()) {
                issues += "managed wrapper launcher must not receive unbound external arguments"
            }
            val executable = string(command, "executable")?.let { Path.of(it).toAbsolutePath().normalize() }
            issues += verifyRuntimeLauncher(request, executable)
        }
        return issues
    }

    private fun verifyRuntimeLauncher(
        request: StrictReviewRuntimeRequest,
        executable: Path?
    ): List<String> {
        if (executable == null) return listOf("managed wrapper command executable is missing")
        val launcherRoot = request.profile.convertedBuildRoot
            .resolve("build/minecraft-mod-testing/nested-launchers")
            .toAbsolutePath()
            .normalize()
        if (!executable.startsWith(launcherRoot) || !executable.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            return listOf("managed wrapper command executable is not a regular confined launcher: $executable")
        }
        val issues = mutableListOf<String>()
        val hash = sha256(executable)
        if (executable.fileName.toString() != "command-$hash.ps1") {
            issues += "managed wrapper launcher filename does not bind its exact bytes"
        }
        val source = executable.readText()
        val processGuard = request.profile.repositoryRoot.resolve(runtimeToolRelativePaths[1])
        val preflight = request.profile.repositoryRoot.resolve(runtimeToolRelativePaths[2])
        val hiddenDesktop = request.profile.repositoryRoot.resolve(runtimeToolRelativePaths[3])
        val requiredMarkers = listOf(
            "ExpectedGuardSha256 '${sha256(processGuard)}'",
            "preflight-${sha256(preflight)}.ps1",
            "hidden-desktop-${sha256(hiddenDesktop)}.ps1",
            "runClientWorld",
            "--no-daemon",
            "--stacktrace"
        )
        for (marker in requiredMarkers) {
            if (!source.contains(marker)) {
                issues += "managed wrapper launcher lacks bound marker: $marker"
            }
        }
        return issues
    }

    private fun readJsonObject(path: Path, label: String, issues: MutableList<String>): JsonObject? {
        if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            issues += "$label is missing: $path"
            return null
        }
        return runCatching { json.parseToJsonElement(path.readText()).jsonObject }
            .onFailure { issues += "$label is invalid JSON: ${it.message}" }
            .getOrNull()
    }

    private fun requireString(
        objectValue: JsonObject,
        key: String,
        expected: String,
        issues: MutableList<String>
    ) {
        val actual = string(objectValue, key)
        if (actual != expected) issues += "$key must be '$expected', observed '${actual ?: "<missing>"}'"
    }

    private fun requireInt(
        objectValue: JsonObject,
        key: String,
        expected: Int,
        issues: MutableList<String>
    ) {
        val actual = int(objectValue, key)
        if (actual != expected) issues += "$key must be $expected, observed ${actual ?: "<missing>"}"
    }

    private fun requireBoolean(
        objectValue: JsonObject,
        key: String,
        expected: Boolean,
        issues: MutableList<String>
    ) {
        val actual = boolean(objectValue, key)
        if (actual != expected) issues += "$key must be $expected, observed ${actual ?: "<missing>"}"
    }

    private fun requireStringList(
        objectValue: JsonObject,
        key: String,
        expected: List<String>,
        issues: MutableList<String>
    ) {
        val actual = objectValue[key]?.asStringList()
        if (actual != expected) issues += "$key must equal $expected, observed ${actual ?: "<missing>"}"
    }

    private fun string(value: JsonObject, key: String): String? =
        value[key]?.primitiveOrNull()?.contentOrNull

    private fun int(value: JsonObject, key: String): Int? =
        value[key]?.primitiveOrNull()?.intOrNull

    private fun long(value: JsonObject, key: String): Long? =
        value[key]?.primitiveOrNull()?.longOrNull

    private fun double(value: JsonObject, key: String): Double? =
        value[key]?.primitiveOrNull()?.doubleOrNull

    private fun boolean(value: JsonObject, key: String): Boolean? =
        value[key]?.primitiveOrNull()?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
    private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.arrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement.asStringList(): List<String>? =
        (this as? JsonArray)?.map { element -> element.primitiveOrNull()?.contentOrNull ?: return null }

    private fun JsonElement.asIntList(): List<Int>? =
        (this as? JsonArray)?.map { element -> element.primitiveOrNull()?.intOrNull ?: return null }

    private fun JsonElement.asLongList(): List<Long>? =
        (this as? JsonArray)?.map { element -> element.primitiveOrNull()?.longOrNull ?: return null }

    private fun JsonElement.asStringMap(): Map<String, String>? {
        val objectValue = this as? JsonObject ?: return null
        return objectValue.mapValues { (_, value) -> value.primitiveOrNull()?.contentOrNull ?: return null }
    }

    private fun javaMajor(version: String): Int? {
        val normalized = version.removePrefix("1.")
        return normalized.takeWhile { it.isDigit() }.toIntOrNull()
    }

    private fun resolveConfinedArtifact(evidenceDir: Path, pathValue: String): Path? {
        val root = evidenceDir.toAbsolutePath().normalize()
        val supplied = Path.of(pathValue)
        val resolved = if (supplied.isAbsolute) supplied.toAbsolutePath().normalize() else root.resolve(supplied).normalize()
        return resolved.takeIf { it.startsWith(root) }
    }

    private fun findUniqueFile(
        root: Path,
        relative: Path,
        issues: MutableList<String>,
        label: String
    ): Path? {
        val direct = root.resolve(relative).normalize()
        if (direct.isRegularFile(LinkOption.NOFOLLOW_LINKS)) return direct
        if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            issues += "$label root is missing: $root"
            return null
        }
        val matches = Files.walk(root).use { stream ->
            stream.filter { path ->
                path.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                    path.toAbsolutePath().normalize().endsWith(relative)
            }.toList()
        }
        return when (matches.size) {
            1 -> matches.single().toAbsolutePath().normalize()
            0 -> {
                issues += "$label is missing below $root"
                null
            }
            else -> {
                issues += "$label is ambiguous below $root: ${matches.take(4)}"
                null
            }
        }
    }

    private fun pathIdentifies(changePath: Path, expected: Path, relative: Path): Boolean {
        val normalized = changePath.normalize()
        return if (normalized.isAbsolute) {
            normalized.toAbsolutePath().normalize() == expected.toAbsolutePath().normalize()
        } else {
            normalized.endsWith(relative)
        }
    }

    private fun buildFiles(buildRoot: Path, modDir: Path): List<Path> =
        listOf(buildRoot, modDir).distinct()
            .flatMap { root -> listOf(root.resolve("build.gradle"), root.resolve("build.gradle.kts")) }
            .filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .sortedBy { it.toString().replace('\\', '/') }

    private fun findOptionalUniqueNamedFile(root: Path, fileName: String): Path? {
        if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) return null
        val matches = Files.walk(root).use { stream ->
            stream.filter {
                it.isRegularFile(LinkOption.NOFOLLOW_LINKS) && it.fileName.toString() == fileName
            }.toList()
        }
        check(matches.size <= 1) {
            "Expected at most one $fileName below $root, observed ${matches.size}: ${matches.take(4)}"
        }
        return matches.singleOrNull()?.toAbsolutePath()?.normalize()
    }

    private fun findUniqueNamedFile(root: Path, fileName: String): Path {
        check(root.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "Source root is missing: $root" }
        val matches = Files.walk(root).use { stream ->
            stream.filter {
                it.isRegularFile(LinkOption.NOFOLLOW_LINKS) && it.fileName.toString() == fileName
            }.toList()
        }
        check(matches.size == 1) {
            "Expected exactly one $fileName below $root, observed ${matches.size}: ${matches.take(4)}"
        }
        return matches.single().toAbsolutePath().normalize()
    }

    private fun compactJava(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/"""), "")
            .replace(Regex("""(?m)//.*$"""), "")
            .replace(Regex("""\s+"""), "")

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = indexOf(needle, index)
            if (index < 0) return count
            count++
            index += needle.length
        }
    }

    private fun sha256(path: Path): String = sha256(path.readBytes())

    private fun sha256(bytes: ByteArray): String =
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun sha256Files(base: Path, files: List<Path>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files.sortedBy { it.toString().replace('\\', '/') }) {
            val normalized = file.toAbsolutePath().normalize()
            val relative = runCatching {
                base.toAbsolutePath().normalize().relativize(normalized).toString().replace('\\', '/')
            }.getOrElse { normalized.toString().replace('\\', '/') }
            digest.update(relative.toByteArray())
            digest.update(0)
            digest.update(file.readBytes())
            digest.update(0)
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    private fun sha256Tree(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            digest.update("<missing>".toByteArray())
            return java.util.HexFormat.of().formatHex(digest.digest())
        }
        val files = Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .toList()
        }
        for (file in files) {
            val relative = root.relativize(file).toString().replace('\\', '/')
            digest.update(relative.toByteArray())
            digest.update(0)
            digest.update(file.readBytes())
            digest.update(0)
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    private fun sha256TreeExcludingNames(root: Path, excludedNames: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            digest.update("<missing>".toByteArray())
            return java.util.HexFormat.of().formatHex(digest.digest())
        }
        val files = Files.walk(root).use { stream ->
            stream.filter {
                it.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                    it.fileName.toString() !in excludedNames
            }
                .sorted()
                .toList()
        }
        for (file in files) {
            val relative = root.relativize(file).toString().replace('\\', '/')
            digest.update(relative.toByteArray())
            digest.update(0)
            digest.update(file.readBytes())
            digest.update(0)
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    internal fun sha256RuntimeDirectory(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val paths = Files.walk(root).use { stream -> stream.toList() }
        val files = paths.mapNotNull { path ->
                when {
                    path == root -> null
                    Files.isSymbolicLink(path) ->
                        error("Runtime classpath directory contains a symbolic link: $path")
                    path.isDirectory(LinkOption.NOFOLLOW_LINKS) -> null
                    path.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> path
                    else -> error("Runtime classpath directory contains an unsupported entry: $path")
                }
        }
            // the harness and the supplemental auditor (sha256-relative-nul-file-sha256-lf-v1) order
            // by the relative '/'-separated name in code point order
            .sortedBy { root.relativize(it).toString().replace('\\', '/') }
        for (file in files) {
            val relative = root.relativize(file).toString().replace('\\', '/')
            digest.update(relative.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(sha256(file).toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    private fun sha256RuntimeClasspath(entries: List<RuntimeClasspathEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (entry in entries) {
            digest.update(entry.path.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(entry.kind.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(entry.sha256.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    private fun hashClasspathResource(
        entries: List<RuntimeClasspathEntry>,
        resourceName: String
    ): String? {
        for (entry in entries) {
            val bytes = when (entry.kind) {
                "directory" -> {
                    val resource = entry.path.resolve(resourceName).normalize()
                    if (resource.startsWith(entry.path) &&
                        resource.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(resource)
                    ) {
                        resource.readBytes()
                    } else {
                        null
                    }
                }
                "file" -> runCatching {
                    ZipFile(entry.path.toFile()).use { zip ->
                        val zipEntry = zip.getEntry(resourceName) ?: return@use null
                        zip.getInputStream(zipEntry).use { it.readBytes() }
                    }
                }.getOrNull()
                else -> null
            }
            if (bytes != null) return sha256(bytes)
        }
        return null
    }

    private fun writeJsonAtomic(path: Path, value: JsonObject) {
        path.parent?.createDirectories()
        val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        temporary.writeText(value.toString() + "\n")
        Files.move(
            temporary,
            path,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun stringArray(values: List<String>): JsonArray =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun stringMap(values: Map<String, String>): JsonObject =
        buildJsonObject { values.forEach { (key, value) -> put(key, value) } }
}
