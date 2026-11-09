package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.PassResult
import com.modporter.core.transforms.build.BuildDependencyPreparationReceipt
import com.modporter.core.transforms.build.BuildSystemLegacyPlantCompat
import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.build.buildReceiptSha256
import com.modporter.core.transforms.build.fileTransitions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/**
 * Managed dependency resolver and reviewed semantic inventory implementation.
 */
internal data class LegacyPlantManagedCommand(
    val command: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String>,
    val log: Path
)

internal fun interface LegacyPlantManagedCommandExecutor {
    /** Returns null only when the managed process timed out and was terminated. */
    fun execute(invocation: LegacyPlantManagedCommand): Int?
}

internal object ProductionLegacyPlantManagedCommandExecutor :
    LegacyPlantManagedCommandExecutor {
    override fun execute(invocation: LegacyPlantManagedCommand): Int? {
        val process = ProcessBuilder(invocation.command)
            .directory(invocation.workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(invocation.log.toFile())
            .also { it.environment().putAll(invocation.environment) }
            .start()
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return null
        }
        return process.exitValue()
    }
}

private fun isLegacyPlantBuildInput(path: Path, relative: String): Boolean {
    val segments = relative.split('/')
    val sourceBoundary = segments.indexOfFirst { it == "src" || it == "resources" }
    if (segments.withIndex().any { (index, segment) ->
            segment in setOf("build", ".gradle") &&
                (sourceBoundary < 0 || index < sourceBoundary)
        }
    ) return false
    val name = path.fileName.toString()
    return name.endsWith(".gradle") ||
        name.endsWith(".gradle.kts") ||
        name in setOf("gradle.properties", "gradle-wrapper.properties") ||
        relative == "gradle/libs.versions.toml" ||
        relative.startsWith("buildSrc/")
}

private data class LegacyPlantBuildInputSnapshot(
    val buildFileHashes: Map<String, String>,
    val identitySha256: String
) {
    fun requireUnchanged(project: Path, attempt: String) {
        require(this == capture(project)) {
            "Staged Gradle inputs changed during $attempt"
        }
    }

    companion object {
        fun capture(project: Path): LegacyPlantBuildInputSnapshot {
            val root = project.toAbsolutePath().normalize()
            val hashes = Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() }
                    .map { it to it.relativeTo(root).toString().replace('\\', '/') }
                    .filter { (path, relative) ->
                        isLegacyPlantBuildInput(path, relative)
                    }
                    .toList()
                    .sortedBy { it.second }
                    .associate { (path, relative) ->
                        require(!Files.isSymbolicLink(path)) {
                            "Staged Gradle input is a symbolic link: $path"
                        }
                        relative to sha256(path.readBytes())
                    }
            }
            return LegacyPlantBuildInputSnapshot(
                hashes,
                legacyPlantBuildInputIdentity(hashes)
            )
        }
    }
}

internal object LegacyPlantMetadataTransportFailureClassifier {
    private const val REASON = "dependency-metadata-transport-failure"
    private val dependencyFailure =
        Regex("""^\s*>\s+Could not resolve (?!all files for configuration)(.+)$""")
    private val repositoryFailure =
        Regex("""^\s*>\s+Failed to list versions for (.+)$""")
    private val request = Regex("""Could not (?:GET|HEAD) '([^']+)'""")
    private val status =
        Regex("""(?:status|response) code\s*:?\s*([0-9]{3})""", RegexOption.IGNORE_CASE)
    private val semanticMarkers = listOf(
        "Could not find ",
        "No matching variant",
        "Cannot choose between the following variants",
        "Cannot find a version of ",
        "rejected by component selection",
        "version conflict",
        "checksum verification failed",
        "Could not apply plugin",
        "Could not compile initialization script",
        "Could not compile build file",
        "Could not compile settings file",
        "Could not compile script",
        "Script compilation error",
        "Compilation error",
        "Could not parse ",
        "Could not read artifact descriptor",
        "startup failed:",
        "Could not create task",
        "Could not get unknown property",
        "Unauthorized",
        "Forbidden",
        "authentication required",
        "credentials"
    )
    private val transportMarkers = listOf(
        "PKIX path building failed",
        "SSLHandshakeException",
        "Got SSL handshake exception",
        "UnknownHostException",
        "SocketTimeoutException",
        "ConnectException",
        "No route to host",
        "Network is unreachable",
        "Connection reset",
        "Remote host terminated the handshake"
    )
    private data class RequestCause(val url: String, val text: String)

    fun classify(log: String): String? {
        if (!log.contains("Execution failed for task ':modporterLegacyPlantResolve'.")) {
            return null
        }
        if (!log.contains("Could not resolve all files for configuration")) return null
        if (semanticMarkers.any { log.contains(it, ignoreCase = true) }) return null
        val lines = log.lineSequence().toList()
        val failureStarts = lines.indices.filter {
            dependencyFailure.find(lines[it]) != null
        }
        if (failureStarts.isEmpty()) return null
        for ((position, start) in failureStarts.withIndex()) {
            val end = failureStarts.getOrNull(position + 1) ?: lines.size
            val block = lines.subList(start, end)
            if (block.drop(1).any {
                    "> Could not " in it &&
                        "Could not resolve all files for configuration" !in it &&
                        request.find(it) == null
                }
            ) return null
            val repositoryStarts = block.indices.filter {
                repositoryFailure.find(block[it]) != null
            }
            if (repositoryStarts.isEmpty()) return null
            val causes = repositoryStarts.flatMapIndexed { repositoryPosition, repositoryStart ->
                val repositoryEnd =
                    repositoryStarts.getOrNull(repositoryPosition + 1) ?: block.size
                val repository = block.subList(repositoryStart, repositoryEnd)
                if (!repository.joinToString("\n").contains("/maven-metadata.xml")) {
                    return null
                }
                val requests = repository.mapIndexedNotNull { index, line ->
                    request.find(line)?.let { index to it.groupValues[1] }
                }
                if (requests.isEmpty()) return null
                requests.mapIndexed { requestPosition, (requestStart, url) ->
                    val requestEnd =
                        requests.getOrNull(requestPosition + 1)?.first ?: repository.size
                    RequestCause(
                        url,
                        repository.subList(requestStart, requestEnd).joinToString("\n")
                    )
                }
            }
            val createPkixPaths = causes.mapNotNull { cause ->
                val prefix = "https://maven.createmod.net/"
                if (!cause.url.startsWith(prefix) ||
                    !cause.text.contains("PKIX path building failed") ||
                    status.containsMatchIn(cause.text)
                ) return@mapNotNull null
                cause.url.removePrefix(prefix)
            }.toSet()
            if (causes.any {
                    !isTransportCause(it.url, it.text, createPkixPaths)
                }
            ) return null
        }
        return REASON
    }

    private fun isTransportCause(
        url: String,
        cause: String,
        createPkixPaths: Set<String>
    ): Boolean {
        val codes = status.findAll(cause).map { it.groupValues[1].toInt() }.toList()
        if (codes.isNotEmpty()) {
            val mirrorPrefix = "https://maven.ithundxr.dev/mirror/"
            if (codes == listOf(400) &&
                url.startsWith(mirrorPrefix) &&
                url.removePrefix(mirrorPrefix) in createPkixPaths
            ) return true
            return codes.all { it == 408 || it == 425 || it == 429 || it in 500..599 }
        }
        return transportMarkers.any { cause.contains(it, ignoreCase = true) }
    }
}

internal class ManagedLegacyPlantGradleResolver(
    private val repositoryRoot: Path,
    private val initScript: Path,
    private val runtimeJarSha256: String,
    private val commandExecutor: LegacyPlantManagedCommandExecutor =
        ProductionLegacyPlantManagedCommandExecutor
) : LegacyPlantGradleResolver {
    init {
        require(runtimeJarSha256.matches(Regex("[0-9a-f]{64}")))
    }

    override fun resolve(stagedProject: Path, phase: InventoryPhase): ResolvedGraph {
        val output = stagedProject.resolve(
            ".modporter/tmp/legacy-plant-resolve-${UUID.randomUUID()}.json"
        )
        output.parent.createDirectories()
        require(!output.exists())
        val phaseValue = phase.name.lowercase()
        val commandPrefix = listOf(
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            repositoryRoot.resolve("mod-build.ps1").toString(),
            "gradle"
        )
        val taskArguments = listOf(
            "modporterLegacyPlantResolve",
            "-p",
            stagedProject.toAbsolutePath().normalize().toString(),
            "-I",
            initScript.toAbsolutePath().normalize().toString()
        )
        val environment = mapOf(
            "MODPORTER_PLANT_INVENTORY_OUTPUT" to
                output.toAbsolutePath().normalize().toString(),
            "MODPORTER_PLANT_INVENTORY_PHASE" to phaseValue,
            "MODPORTER_PLANT_RUNTIME_JAR_SHA256" to runtimeJarSha256,
            "MODPORTER_PLANT_CLASS_RUNTIME_FEATURE" to
                if (phase == InventoryPhase.SOURCE) "17" else "21"
        )
        val sourceIdentity = LegacyPlantBuildInputSnapshot.capture(stagedProject)
        fun newLog(kind: String): Path = repositoryRoot.resolve("logs").resolve(
            "legacy-plant-resolve-$phaseValue-$kind-${UUID.randomUUID()}.log"
        ).also { it.parent.createDirectories() }
        fun invoke(offline: Boolean, log: Path): Int? =
            commandExecutor.execute(
                LegacyPlantManagedCommand(
                    command = commandPrefix +
                        (if (offline) listOf("--offline") else emptyList()) +
                        taskArguments,
                    workingDirectory = repositoryRoot,
                    environment = environment,
                    log = log
                )
            )

        val onlineLog = newLog("online")
        val onlineExit = invoke(offline = false, log = onlineLog)
        sourceIdentity.requireUnchanged(stagedProject, "managed online resolution")
        require(onlineExit != null) {
            "Managed legacy plant resolution timed out; preserved log: $onlineLog"
        }
        if (onlineExit == 0) {
            require(output.isRegularFile()) {
                "Managed legacy plant resolver emitted no graph; preserved log: $onlineLog"
            }
            val graph = parseGraph(
                output,
                phase,
                sourceIdentity,
                LegacyPlantResolutionProvenance(
                    origin = "online",
                    metadataFreshness = "not-asserted",
                    sourceIdentitySha256 = sourceIdentity.identitySha256
                )
            )
            graph.canonicalText()
            Files.deleteIfExists(output)
            Files.deleteIfExists(onlineLog)
            return graph
        }

        require(!output.exists()) {
            "Failed online resolver emitted a graph; refusing cache recovery; " +
                "preserved raw graph: $output; preserved log: $onlineLog"
        }
        val onlineText = onlineLog.readText()
        val reason = LegacyPlantMetadataTransportFailureClassifier.classify(onlineText)
            ?: error(
                "Managed legacy plant resolution failed ($onlineExit) with a " +
                    "non-recoverable cause; preserved log: $onlineLog\n$onlineText"
            )
        val offlineLog = newLog("offline-cache")
        val offlineExit = invoke(offline = true, log = offlineLog)
        sourceIdentity.requireUnchanged(
            stagedProject,
            "managed offline complete-cache resolution"
        )
        require(offlineExit != null) {
            "Managed offline cache resolution timed out; preserved logs: " +
                "$onlineLog, $offlineLog"
        }
        require(offlineExit == 0) {
            "Managed offline cache resolution failed ($offlineExit); preserved logs: " +
                "$onlineLog, $offlineLog\n${offlineLog.readText()}"
        }
        require(output.isRegularFile()) {
            "Managed offline cache resolver emitted no graph; preserved logs: " +
                "$onlineLog, $offlineLog"
        }
        val offlineText = offlineLog.readText()
        val graph = parseGraph(
            output,
            phase,
            sourceIdentity,
            LegacyPlantResolutionProvenance(
                origin = "gradle-offline-complete-cache",
                metadataFreshness = "cached",
                sourceIdentitySha256 = sourceIdentity.identitySha256,
                recoveryReason = reason,
                failedAttemptExitCode = onlineExit,
                failedAttemptLogSha256 = sha256(onlineText),
                recoveryAttemptLogSha256 = sha256(offlineText)
            )
        )
        graph.canonicalText()
        Files.deleteIfExists(output)
        return graph
    }

    private fun parseGraph(
        path: Path,
        expectedPhase: InventoryPhase,
        sourceIdentity: LegacyPlantBuildInputSnapshot,
        provenance: LegacyPlantResolutionProvenance
    ): ResolvedGraph {
        val root = Json.parseToJsonElement(path.readText()).jsonObject
        require(root.string("schema") == "modporter-legacy-plant-resolved-graph-v1")
        require(root.string("phase") == expectedPhase.name.lowercase())
        val unresolved = root.array("unresolved").map { it.jsonObject.canonicalJson() }
        require(unresolved.isEmpty()) {
            "Resolved graph still contains unresolved dependencies: $unresolved"
        }
        val configurations = root.array("configurations").map { element ->
            val value = element.jsonObject
            "${value.string("project")}|${value.string("configuration")}"
        }.toSet()
        val artifacts = root.array("artifacts").map { element ->
            val value = element.jsonObject
            val attributes = value.objectValue("variantAttributes")
                .mapValues { (_, attribute) -> attribute.jsonPrimitive.content }
            val file = Path.of(value.string("file")).toAbsolutePath().normalize()
            val artifact = ResolvedArtifact(
                projectPath = value.string("project"),
                component = value.string("component"),
                componentKind = value.string("componentKind"),
                classifier = value.stringOrNull("classifier"),
                displayName = value.string("artifact"),
                configurations = value.array("configurations")
                    .map { it.jsonPrimitive.content }
                    .toSet(),
                configurationOrder = value.objectValue("configurationOrder")
                    .mapValues { (_, order) -> order.jsonPrimitive.content.toInt() },
                variantAttributes = attributes,
                file = file,
                size = value.long("size"),
                sha256 = value.string("sha256"),
                variantSha256 = sha256(
                    attributes.toSortedMap().entries.joinToString("\n") {
                        "${it.key}=${it.value}"
                    }
                )
            )
            artifact
        }
        val edges = root.array("edges").map { it.jsonObject.canonicalJson() }
        val buildFiles = root.objectValue("buildFiles")
            .mapValues { (_, hash) -> hash.jsonPrimitive.content }
        require(buildFiles == sourceIdentity.buildFileHashes) {
            "Resolved graph build inputs differ from the exact staged source identity"
        }
        val platformAuxiliaries = root.array("platformAuxiliaries").map { element ->
            val value = element.jsonObject
            PlatformAuxiliaryArtifact(
                kind = value.string("kind"),
                displayName = value.string("displayName"),
                file = Path.of(value.string("file")).toAbsolutePath().normalize(),
                size = value.long("size"),
                sha256 = value.string("sha256")
            )
        }
        val platformIdentity = root["platformIdentity"]?.let { element ->
            if (element is JsonNull) {
                null
            } else {
                val value = element.jsonObject
                PlatformResolutionIdentity(
                    family = value.string("family"),
                    minecraftVersion = value.string("minecraftVersion"),
                    loaderVersion = value.string("loaderVersion"),
                    resolvedLoaderCoordinate = value.string("resolvedLoaderCoordinate"),
                    developmentPluginId = value.string("developmentPluginId"),
                    developmentPluginVersion =
                        value.string("developmentPluginVersion"),
                    developmentPluginClass = value.string("developmentPluginClass"),
                    developmentPluginSha256 =
                        value.string("developmentPluginSha256"),
                    generatedArtifactDisplayName =
                        value.string("generatedArtifactDisplayName"),
                    generatedArtifactSha256 =
                        value.string("generatedArtifactSha256"),
                    declarationFileHashes =
                        value.objectValue("declarationFileHashes").mapValues {
                            (_, hash) -> hash.jsonPrimitive.content
                        }
                )
            }
        }
        return ResolvedGraph(
            phase = expectedPhase,
            resolverSchema = root.string("schema"),
            gradleVersion = root.string("gradleVersion"),
            classRuntimeFeature = root.long("classRuntimeFeature").toInt(),
            buildFileHashes = buildFiles,
            configurations = configurations,
            artifacts = artifacts,
            edges = edges,
            unresolved = unresolved,
            platformIdentity = platformIdentity,
            platformAuxiliaries = platformAuxiliaries,
            resolutionProvenance = provenance
        )
    }
}

internal class BuildSystemLegacyPlantTargetPlanner(
    private val buildSystemPass: BuildSystemPass,
    private val runtimeIdentity: CanonicalRuntimeIdentity,
    private val adapterRegistrations: List<PlannedAdapterRegistrationReceipt> = emptyList()
) : LegacyPlantTargetBuildPlanner {
    override fun prepare(
        stagedProject: Path,
        sourceGraph: ResolvedGraph
    ): TargetBuildPlanReceipt {
        runtimeIdentity.verify()
        val dependencyPreparation = buildSystemPass.prepareDependencies(
            stagedProject,
            sourceGraph.buildSystemSourceDependencies()
        )
        val request = stagedProject.resolve(".modporter/legacy-plant-compat.required")
        request.parent.createDirectories()
        val requestContent = runtimeIdentity.runtimeRequest()
        if (request.exists()) {
            require(request.readText() == requestContent) {
                "Target stage contains a conflicting canonical plant runtime request"
            }
        } else {
            request.writeText(requestContent)
        }
        val runtimeChanges = BuildSystemLegacyPlantCompat.install(
            stagedProject,
            dryRun = false
        )
        val buildFilesAfterRuntime = snapshotBuildFiles(stagedProject)
        val sourceFilesAfterRuntime = snapshotPreparedSourceFiles(stagedProject)
        val preparation = BuildDependencyPreparationReceipt(
            result = PassResult(
                passName = dependencyPreparation.result.passName,
                changes = dependencyPreparation.result.changes + runtimeChanges,
                errors = dependencyPreparation.result.errors,
                skipped = dependencyPreparation.result.skipped
            ),
            mappingResourceSha256 = dependencyPreparation.mappingResourceSha256,
            decisions = dependencyPreparation.decisions,
            removals = dependencyPreparation.removals,
            buildFilesBefore = dependencyPreparation.buildFilesBefore,
            buildFilesAfter = buildFilesAfterRuntime,
            resourceFilesBefore = dependencyPreparation.resourceFilesBefore,
            resourceFilesAfter = dependencyPreparation.resourceFilesAfter,
            sourceFilesBefore = dependencyPreparation.sourceFilesBefore,
            sourceFilesAfter = sourceFilesAfterRuntime,
            changedBuildFiles = fileTransitions(
                dependencyPreparation.buildFilesBefore,
                buildFilesAfterRuntime
            ),
            changedResourceFiles = dependencyPreparation.changedResourceFiles,
            changedSourceFiles = fileTransitions(
                dependencyPreparation.sourceFilesBefore,
                sourceFilesAfterRuntime
            )
        )
        return TargetBuildPlanReceipt(
            preparation = preparation,
            adapterRegistrations = adapterRegistrations
        )
    }

    private fun snapshotBuildFiles(projectDir: Path): Map<String, String> =
        Files.walk(projectDir).use { paths ->
            paths.filter { it.isRegularFile() }
                .map { path ->
                    path to path.relativeTo(projectDir).toString().replace('\\', '/')
                }
                .filter { (path, relative) ->
                    isLegacyPlantBuildInput(path, relative)
                }
                .toList()
                .sortedBy { it.second }
                .associate { (path, relative) ->
                    relative to buildReceiptSha256(path.readBytes())
                }
        }

    private fun snapshotPreparedSourceFiles(projectDir: Path): Map<String, String> =
        Files.walk(projectDir).use { paths ->
            paths.filter { it.isRegularFile() }
                .map { path ->
                    path to path.relativeTo(projectDir).toString().replace('\\', '/')
                }
                .filter { (path, relative) ->
                    path.fileName.toString().endsWith(".java") &&
                        (
                            relative.startsWith("src/main/java/") ||
                                relative.startsWith(
                                    ".modporter/legacy-plant-compat/src/main/java/"
                                )
                            )
                }
                .toList()
                .sortedBy { it.second }
                .associate { (path, relative) ->
                    relative to buildReceiptSha256(path.readBytes())
                }
        }
}

internal data class PlatformArtifactFingerprint(
    val phase: InventoryPhase,
    val expectedPlatformIdentity: PlatformResolutionIdentity?,
    val reviewedAppliedPluginSha256s: Set<String>,
    val artifactDisplayName: String,
    val artifactSha256: String,
    val mergedArtifactDisplayName: String,
    val mergedArtifactSha256: String,
    val sourcesArtifactDisplayName: String,
    val sourcesArtifactSha256: String,
    val reviewedSourceEntries: Map<String, String>,
    val requiredClasses: Set<JvmClassFingerprint>,
    val requiredMethods: Set<JvmMethodFingerprint>,
    val sourceReceiptSha256: String,
    val closureManifestSha256: String
) {
    init {
        require(requiredClasses.isNotEmpty())
        require(artifactDisplayName.isNotBlank())
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(mergedArtifactDisplayName.isNotBlank())
        require(mergedArtifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourcesArtifactDisplayName.isNotBlank())
        require(sourcesArtifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(reviewedSourceEntries.isNotEmpty())
        require(reviewedSourceEntries.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        if (phase == InventoryPhase.SOURCE) requireNotNull(expectedPlatformIdentity)
        require(reviewedAppliedPluginSha256s.isNotEmpty())
        require(
            reviewedAppliedPluginSha256s.all { it.matches(Regex("[0-9a-f]{64}")) }
        )
        expectedPlatformIdentity?.let {
            require(it.developmentPluginSha256 in reviewedAppliedPluginSha256s)
        }
        require(sourceReceiptSha256.matches(Regex("[0-9a-f]{64}")))
        require(closureManifestSha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun matchesPlatformIdentity(actual: PlatformResolutionIdentity): Boolean =
        expectedPlatformIdentity?.let {
            actual.matchesReviewedPlatform(it, reviewedAppliedPluginSha256s)
        } == true
}

internal data class JvmClassFingerprint(
    val owner: String,
    val accessFlags: Int,
    val classSha256: String
) {
    init {
        require(owner.isNotBlank())
        require(accessFlags in 0..0xffff)
        require(classSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

internal data class JvmMethodFingerprint(
    val owner: String,
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val codeAttributeSha256: String?
) {
    init {
        require(owner.isNotBlank())
        require(name.isNotBlank())
        require(descriptor.isNotBlank())
        require(accessFlags in 0..0xffff)
        require(
            codeAttributeSha256 == null ||
                codeAttributeSha256.matches(Regex("[0-9a-f]{64}"))
        )
    }
}

/**
 * Reviewed against the full original Create source closure, not a substituted Forge patch line.
 * The named generated JAR is the actual source compile artifact. The Forge sources/universal JARs
 * are useful provenance but are deliberately not accepted as ABI substitutes here.
 */
internal object ReviewedPlantPlatformFingerprints {
    private val original: List<PlatformArtifactFingerprint> = listOf(
        PlatformArtifactFingerprint(
            phase = InventoryPhase.SOURCE,
            expectedPlatformIdentity = PlatformResolutionIdentity(
                family = "legacy-forge",
                minecraftVersion = "1.20.1",
                loaderVersion = "47.1.33",
                resolvedLoaderCoordinate = "net.minecraftforge:forge:1.20.1-47.1.33",
                developmentPluginId = "net.neoforged.moddev.legacyforge",
                developmentPluginVersion = "2.0.107",
                developmentPluginClass =
                    "net.neoforged.moddevgradle.boot.LegacyForgeModDevPlugin",
                developmentPluginSha256 =
                    "f81ca03355b608a4cfb11fdf96e12b0008dfcd287367e37392f340d9366c499f",
                generatedArtifactDisplayName =
                    "build/moddev/artifacts/forge-1.20.1-47.1.33.jar",
                generatedArtifactSha256 =
                    "a1270c7d64018ba097add19d5dcee698abf8eb9971c8d5f91c7f1732e1e84ff3",
                declarationFileHashes = mapOf(
                    "build.gradle" to
                        "e8d57cd8518449b3d5d323ee856a365d09d12d87823009898e7b5ac2f933e341",
                    "gradle.properties" to
                        "bd2d60a00e41849e2bcdd257706db87fe6e35fd1c0bc9728395e66ad8af43c27",
                    "settings.gradle" to
                        "73f41a4856cd386286eb10745ea2229626594eddc3d06072bf9df92d4da90a82"
                )
            ),
            reviewedAppliedPluginSha256s = setOf(
                "f81ca03355b608a4cfb11fdf96e12b0008dfcd287367e37392f340d9366c499f",
                "cd473e5a4d3cd58d2993d0c766a2b17b45dc37d820deb9cb3e5aef74fa545341"
            ),
            artifactDisplayName =
                "build/moddev/artifacts/forge-1.20.1-47.1.33.jar",
            artifactSha256 =
                "a1270c7d64018ba097add19d5dcee698abf8eb9971c8d5f91c7f1732e1e84ff3",
            mergedArtifactDisplayName =
                "build/moddev/artifacts/forge-1.20.1-47.1.33-merged.jar",
            mergedArtifactSha256 =
                "9a94e3d847ad67d3ba24a674daca735775fc9c7ee30292090559f186ea2191ac",
            sourcesArtifactDisplayName =
                "build/moddev/artifacts/forge-1.20.1-47.1.33-sources.jar",
            sourcesArtifactSha256 =
                "355bfbfb7688037df6fe9b1379811a851b9f394a64de7a68b8a0d10b95a5adfd",
            reviewedSourceEntries = mapOf(
                "net/minecraftforge/common/IPlantable.java" to
                    "ebda4d1fe744829f0312ef587fd94641cb96f43a74d0d3a292257c06ae69883a",
                "net/minecraftforge/common/PlantType.java" to
                    "bab19da8411b50387174346b23040e5e969b74a7173ddc38e971ca0f00e11887",
                "net/minecraftforge/common/extensions/IForgeBlock.java" to
                    "a599dd8e31a8dc08eb1b3ccb1802fe16b059391c45e6e1676cfae0644d783e25",
                "net/minecraft/world/level/block/Block.java" to
                    "53825381fa01831f39adf4a3f96df1a88cd5242e23f952c6c813ed3b632d11c0",
                "net/minecraft/world/level/block/BushBlock.java" to
                    "9f5921c47f4377a9420de694f44a97b440808592f7f3742ecbdbcedfd2e182ba"
            ),
            requiredClasses = setOf(
                JvmClassFingerprint(
                    "net/minecraftforge/common/IPlantable",
                    0x0601,
                    "3f88c216639a38dbc6beacf788075dc98249fff8065776ba14b1feeecbe4e0ed"
                ),
                JvmClassFingerprint(
                    "net/minecraftforge/common/extensions/IForgeBlock",
                    0x0601,
                    "235ed2cd5907d646bdae81a8a43c8b45ce63dc2822615ff9166f724590e5ca00"
                ),
                JvmClassFingerprint(
                    "net/minecraft/world/level/block/Block",
                    0x0021,
                    "34dad48244ae7704fedcd95e2b8ece94d20c1a442e65d6bc772a01e0825d6a16"
                ),
                JvmClassFingerprint(
                    "net/minecraft/world/level/block/BushBlock",
                    0x0021,
                    "31ab63445f0a2892226272fff5e5f777f08cb9b34de17acc86a24cb8eb645ce0"
                ),
                JvmClassFingerprint(
                    "net/minecraftforge/common/PlantType",
                    0x0031,
                    "e00429a6147da33f5016f3881bfaac08bd641a9f64c0afd84b3ed9f7e4b49894"
                )
            ),
            requiredMethods = setOf(
                JvmMethodFingerprint(
                    "net/minecraftforge/common/IPlantable",
                    "getPlantType",
                    "(Lnet/minecraft/world/level/BlockGetter;" +
                        "Lnet/minecraft/core/BlockPos;)" +
                        "Lnet/minecraftforge/common/PlantType;",
                    0x0001,
                    "1d149dc3af60bcdea2eea6c9ea807412badbaaba4a21a82efdcea638670c0d4c"
                ),
                JvmMethodFingerprint(
                    "net/minecraftforge/common/IPlantable",
                    "getPlant",
                    "(Lnet/minecraft/world/level/BlockGetter;" +
                        "Lnet/minecraft/core/BlockPos;)" +
                        "Lnet/minecraft/world/level/block/state/BlockState;",
                    0x0401,
                    null
                ),
                JvmMethodFingerprint(
                    "net/minecraftforge/common/extensions/IForgeBlock",
                    "canSustainPlant",
                    "(Lnet/minecraft/world/level/block/state/BlockState;" +
                        "Lnet/minecraft/world/level/BlockGetter;" +
                        "Lnet/minecraft/core/BlockPos;" +
                        "Lnet/minecraft/core/Direction;" +
                        "Lnet/minecraftforge/common/IPlantable;)Z",
                    0x0401,
                    null
                ),
                JvmMethodFingerprint(
                    "net/minecraft/world/level/block/Block",
                    "canSustainPlant",
                    "(Lnet/minecraft/world/level/block/state/BlockState;" +
                        "Lnet/minecraft/world/level/BlockGetter;" +
                        "Lnet/minecraft/core/BlockPos;" +
                        "Lnet/minecraft/core/Direction;" +
                        "Lnet/minecraftforge/common/IPlantable;)Z",
                    0x0001,
                    "7f68fc1b55f219888da31ae316af1350d65fcac0cf4c94bbef1a2e8da5ba91db"
                ),
                JvmMethodFingerprint(
                    "net/minecraft/world/level/block/BushBlock",
                    "mayPlaceOn",
                    "(Lnet/minecraft/world/level/block/state/BlockState;" +
                        "Lnet/minecraft/world/level/BlockGetter;" +
                        "Lnet/minecraft/core/BlockPos;)Z",
                    0x0004,
                    "f482465f18c4fdbbc3df12e15594020c41f180c8899a763f6b89ce5a89f15ffa"
                ),
                JvmMethodFingerprint(
                    "net/minecraftforge/common/PlantType",
                    "get",
                    "(Ljava/lang/String;)Lnet/minecraftforge/common/PlantType;",
                    0x0009,
                    "74f2ffc377ac6e687b3b28ff53042ed91990883502f878d13f80640c49dc4d39"
                )
            ),
            sourceReceiptSha256 =
                "117c4685d16414a210ac9c3c2409d0307fb8c5084cfdefde80f23e932e7f63a6",
            closureManifestSha256 =
                "b630cf006e9b9686ad389f26dcb9278535502724544e7aee9d5d51503dd42a0e"
        ),
        PlatformArtifactFingerprint(
            phase = InventoryPhase.TARGET,
            expectedPlatformIdentity = PlatformResolutionIdentity(
                family = "neoforge",
                minecraftVersion = "1.21.1",
                loaderVersion = "21.1.230",
                resolvedLoaderCoordinate = "net.neoforged:neoforge:21.1.230",
                developmentPluginId = "net.neoforged.moddev",
                developmentPluginVersion = "2.0.140",
                developmentPluginClass =
                    "net.neoforged.moddevgradle.boot.ModDevPlugin",
                developmentPluginSha256 =
                    "df07f287c4ef30c4b1a76cc9b2bb67d37d5485382a05a0dc8bfb7def9234ab5c",
                generatedArtifactDisplayName =
                    "build/moddev/artifacts/neoforge-21.1.230.jar",
                generatedArtifactSha256 =
                    "da2570515920276bb884d59868915e924bc80523e8771e145a11b12db41d5b2b",
                declarationFileHashes = mapOf(
                    "build.gradle" to
                        "102dec51a6ba5fa8ba58553a2af947274c381dca714ddaa5be3443f993105a90",
                    "gradle.properties" to
                        "ba6aa11ffb1b2944b734839970261d091b12afe9e2a716e25dae51b7d86703dd",
                    "settings.gradle" to
                        "b93453aae155c3d392b10a2626eb7633e33ab81c1b450b415afd42c628e91ff5"
                )
            ),
            reviewedAppliedPluginSha256s = setOf(
                "df07f287c4ef30c4b1a76cc9b2bb67d37d5485382a05a0dc8bfb7def9234ab5c",
                "ce42847dcd70e26a902cca1a04ed5185301a85846df43a2b4b99038ca3a3967f"
            ),
            artifactDisplayName =
                "build/moddev/artifacts/neoforge-21.1.230.jar",
            artifactSha256 =
                "da2570515920276bb884d59868915e924bc80523e8771e145a11b12db41d5b2b",
            mergedArtifactDisplayName =
                "build/moddev/artifacts/neoforge-21.1.230-merged.jar",
            mergedArtifactSha256 =
                "92552fdc76a29894e7e6af44d0a06a10ed0e2c0e7b25545287144b5906e4a16b",
            sourcesArtifactDisplayName =
                "build/moddev/artifacts/neoforge-21.1.230-sources.jar",
            sourcesArtifactSha256 =
                "830d5991464c1c43d6960c2aaae6b2e3e61f83021aa192ae3783d47317d806f4",
            reviewedSourceEntries = mapOf(
                "net/neoforged/neoforge/common/extensions/IBlockExtension.java" to
                    "2dea052b03b569b82ca728073ab4299282eb4bb9116637b9ee19ade36975e5a5",
                "net/neoforged/neoforge/common/extensions/IBlockStateExtension.java" to
                    "0ca55c4ed6929e848270097a6c8932a5dd9626ef93b80e6c51ff5f3dd23548f1",
                "net/minecraft/world/level/block/Block.java" to
                    "90e336f7151e3719a1ae8c0694f0023104a2c599c5851aa2d871ffbd1a95ea8f",
                "net/minecraft/world/level/block/BushBlock.java" to
                    "224ea54460f11fcf5d7fd4ad1e92269e94526b647c3eec413a17941e9befd42d"
            ),
            requiredClasses = setOf(
                JvmClassFingerprint(
                    "net/minecraft/world/level/block/Block",
                    0x0021,
                    "083bda462c9b9d8a23ea93edca56c137d95d3069824a6a8574c0958673d76741"
                ),
                JvmClassFingerprint(
                    "net/minecraft/world/level/block/BushBlock",
                    0x0421,
                    "6dd28d71e08c32eae23906c149afc583d600a0229cb8dc22012a8b0a19628990"
                ),
                JvmClassFingerprint(
                    "net/minecraft/world/level/block/state/BlockState",
                    0x0021,
                    "b7c92980767e378d848bbec0e7faea22d4834fca672e8b11829cb58e8676f9d3"
                ),
                JvmClassFingerprint(
                    "net/neoforged/neoforge/common/extensions/IBlockExtension",
                    0x0601,
                    "4018a3041dd156b81eb158af084bfea53353fb593ffcb51dc9b87ed0cd9d0615"
                ),
                JvmClassFingerprint(
                    "net/neoforged/neoforge/common/extensions/IBlockStateExtension",
                    0x0601,
                    "67e4a37c9f242dfdfa9c6d815c2e3232b80d29b6adddf438637eba84fc498806"
                )
            ),
            requiredMethods = setOf(
                JvmMethodFingerprint(
                    "net/minecraft/world/level/block/BushBlock",
                    "mayPlaceOn",
                    "(Lnet/minecraft/world/level/block/state/BlockState;" +
                        "Lnet/minecraft/world/level/BlockGetter;" +
                        "Lnet/minecraft/core/BlockPos;)Z",
                    0x0004,
                    "2ed70478d6e5d4a0e9be58d7a2617a97c4991ad8bfb8f5cd9c0dad9b505f0aa9"
                ),
                JvmMethodFingerprint(
                    "net/neoforged/neoforge/common/extensions/IBlockExtension",
                    "canSustainPlant",
                    "(Lnet/minecraft/world/level/block/state/BlockState;" +
                        "Lnet/minecraft/world/level/BlockGetter;" +
                        "Lnet/minecraft/core/BlockPos;" +
                        "Lnet/minecraft/core/Direction;" +
                        "Lnet/minecraft/world/level/block/state/BlockState;)" +
                        "Lnet/neoforged/neoforge/common/util/TriState;",
                    0x0001,
                    "e8640a192d678d7f84fd578a3c40d1025262690efc98187f01675fef87ca2fc3"
                ),
                JvmMethodFingerprint(
                    "net/neoforged/neoforge/common/extensions/IBlockStateExtension",
                    "canSustainPlant",
                    "(Lnet/minecraft/world/level/BlockGetter;" +
                        "Lnet/minecraft/core/BlockPos;" +
                        "Lnet/minecraft/core/Direction;" +
                        "Lnet/minecraft/world/level/block/state/BlockState;)" +
                        "Lnet/neoforged/neoforge/common/util/TriState;",
                    0x0001,
                    "e49826a1cecb0e5dae9b3202f712501cd4941cefa43b86d905c86810afa24921"
                )
            ),
            sourceReceiptSha256 =
                "117c4685d16414a210ac9c3c2409d0307fb8c5084cfdefde80f23e932e7f63a6",
            closureManifestSha256 =
                "63583dc14d7eb876be954400334f911af7ef9251fe6cc81cd80e7684f70f006e"
        )
    )

    // Exact dependency-only prepass variant reviewed in compat/legacy-plant-platform-prepass-v1.json.
    // Only Screen and BeaconBlockEntity differ from the original archive; all reviewed plant
    // source, class and method bytes are identical. Keep the original profile for sealed replay.
    val all: List<PlatformArtifactFingerprint> = original + original.single {
        it.phase == InventoryPhase.TARGET
    }.let { target ->
        val generatedSha256 =
            "0d168b646b1f1dfe490afb05a9dc51db34df456c309ab2028e508ed99c7bbd77"
        target.copy(
            expectedPlatformIdentity = requireNotNull(target.expectedPlatformIdentity).copy(
                generatedArtifactSha256 = generatedSha256
            ),
            artifactSha256 = generatedSha256,
            mergedArtifactSha256 =
                "1e7ce155d3193663d2f2e077358ecff835de4d758689c0bb1bb6525d43b2e34d",
            sourcesArtifactSha256 =
                "e3189c01eaddc7abe6c6f71931dc62bc7b1b86ec08eafe9803335236e2ba3ede",
            sourceReceiptSha256 =
                "06e1a8f5c644bae7fb67a13ab48284981a2903928a5943d162af6dc021ba41ea"
        )
    }
}

internal class Jvm21LegacyPlantBytecodeScanner(
    private val platformFingerprints: List<PlatformArtifactFingerprint>
) : LegacyPlantBytecodeScanner {
    private val legacyInterfaces = setOf(
        "net/minecraftforge/common/IPlantable",
        "net/neoforged/neoforge/common/IPlantable"
    )
    private val legacyPlantTypes = setOf(
        "net/minecraftforge/common/PlantType",
        "net/neoforged/neoforge/common/PlantType"
    )
    private val canonicalInterfaces = setOf(
        "com/modporter/compat/plant/v1/IPlantable",
        "com/modporter/compat/plant/v1/LegacyPlantSustainer",
        "com/modporter/compat/plant/v1/LegacyPlantPlacement"
    )
    private val plantMethods = setOf(
        "getPlant",
        "getPlantType",
        "canSustainPlant",
        "mayPlaceOn"
    )
    private val block = "net/minecraft/world/level/block/Block"
    private val bush = "net/minecraft/world/level/block/BushBlock"

    override fun scan(
        graph: ResolvedGraph,
        runtimeIdentity: CanonicalRuntimeIdentity,
        adapterRegistrations: List<PlannedAdapterRegistrationReceipt>
    ): ClosurePlantScan {
        runtimeIdentity.verify()
        val artifacts = graph.artifacts.associateWith {
            readArtifact(it, graph.classRuntimeFeature)
        }
        val byOwner = linkedMapOf<String, MutableList<OwnedClass>>()
        artifacts.values.flatten().forEach { owned ->
            byOwner.getOrPut(owned.facts.owner) { mutableListOf() } += owned
        }
        val blockers = mutableListOf<String>()
        val duplicates = linkedMapOf<String, List<String>>()
        val activeByConfiguration = graph.configurations.associateWith { configuration ->
            artifacts.flatMap { (artifact, classes) ->
                if (configuration in artifact.configurations) classes else emptyList()
            }.groupBy { it.facts.owner }.mapValues { (_, definitions) ->
                definitions.minWithOrNull(
                    compareBy<OwnedClass>(
                        { it.artifact.configurationOrder.getValue(configuration) },
                        { it.artifact.key }
                    )
                )!!
            }
        }
        val inheritanceByConfiguration = activeByConfiguration.mapValues { (_, classes) ->
            ClosureInheritance(classes.mapValues { it.value.facts })
        }
        for ((owner, definitions) in byOwner) {
            val byteHashes = definitions.map(OwnedClass::classSha256).distinct()
            if (byteHashes.size <= 1) continue
            val selectedFacts = activeByConfiguration.mapNotNull {
                    (configuration, classes) ->
                val selected = classes[owner] ?: return@mapNotNull null
                val inheritance = inheritanceByConfiguration.getValue(configuration)
                Triple(
                    configuration,
                    isConflictRelevant(selected.facts, inheritance),
                    plantConflictSignature(selected.facts, inheritance)
                )
            }
            if (selectedFacts.any { it.second } &&
                selectedFacts.map { it.third }.distinct().size > 1
            ) {
                blockers +=
                    "active relevant duplicate differs across classpaths for $owner: " +
                    selectedFacts.joinToString {
                        "${it.first}=${it.third}|relevant=${it.second}"
                    }
            }
            duplicates[owner.replace('/', '.')] = definitions.map {
                "${it.artifact.sha256}|${it.classSha256}|${it.entryName}|${it.release}"
            }.sorted()
        }

        val platformByArtifact = linkedMapOf<ResolvedArtifact, PlatformArtifactFingerprint>()
        val identityFingerprints = platformFingerprints.filter { fingerprint ->
            fingerprint.phase == graph.phase && (
                fingerprint.expectedPlatformIdentity == null ||
                    graph.platformIdentity?.let(fingerprint::matchesPlatformIdentity) == true
                )
        }
        val applicableFingerprints = identityFingerprints.filter { fingerprint ->
            val accepted = matchesReviewedSourceEvidence(graph, fingerprint)
            if (!accepted) {
                blockers +=
                    "platform merged/sources evidence does not match reviewed receipt " +
                        fingerprint.sourceReceiptSha256
            }
            accepted
        }
        if (graph.phase == InventoryPhase.SOURCE && graph.platformIdentity != null &&
            applicableFingerprints.isEmpty()
        ) {
            blockers +=
                "unsupported source platform identity ${graph.platformIdentity.sha256}"
        }
        for (fingerprint in applicableFingerprints) {
            val matches = graph.artifacts.filter { artifact ->
                artifact.displayName == fingerprint.artifactDisplayName &&
                    artifact.sha256 == fingerprint.artifactSha256 &&
                    matchesFingerprint(artifacts.getValue(artifact), fingerprint) &&
                    artifact.configurations.all { configuration ->
                        fingerprint.requiredClasses.all { required ->
                            activeByConfiguration.getValue(configuration)[required.owner]
                                ?.artifact == artifact
                        }
                    }
            }
            if (matches.size != 1) {
                blockers +=
                    "platform fingerprint ${fingerprint.sourceReceiptSha256} matched " +
                    "${matches.size} artifacts"
            } else {
                require(platformByArtifact.put(matches.single(), fingerprint) == null) {
                    "Multiple platform fingerprints matched ${matches.single().key}"
                }
            }
        }
        val adapterManifests = linkedMapOf<ResolvedArtifact, AdapterManifestReceipt>()
        if (adapterRegistrations.isNotEmpty() && graph.phase != InventoryPhase.TARGET) {
            blockers += "adapter registrations are only admissible in the target graph"
        }
        adapterRegistrations.groupBy { registration ->
            val matches = graph.artifacts.filter {
                it.matchesSelector(registration.targetSelector)
            }
            if (matches.size != 1) {
                blockers +=
                    "adapter target selector ${registration.targetSelector} matched " +
                    "${matches.size} target artifacts"
                null
            } else {
                matches.single()
            }
        }.forEach { (artifact, planned) ->
            if (artifact == null) return@forEach
            val registrations = planned.map {
                it.materialize()
            }
            val manifest = AdapterManifestReceipt(
                artifactSha256 = artifact.sha256,
                runtimeSourceSha256 = runtimeIdentity.sourceSha256,
                runtimeJarSha256 = runtimeIdentity.jarSha256,
                registrations = registrations
            )
            if (!manifest.isValidFor(artifact, runtimeIdentity)) {
                blockers += "invalid adapter manifest for ${artifact.key}"
            } else {
                adapterManifests[artifact] = manifest
            }
        }

        val scans = graph.artifacts.associateWith { artifact ->
            val classFacts = mutableListOf<String>()
            val relevant = mutableListOf<String>()
            val canonicalFlags = mutableListOf<Boolean>()
            val requiredAdapterKinds =
                mutableMapOf<String, MutableSet<String>>()
            val activeClasses = activeByConfiguration.flatMap {
                    (configuration, classes) ->
                classes.values.filter { it.artifact == artifact }.map {
                    configuration to it
                }
            }
            for ((configuration, owned) in activeClasses) {
                val facts = owned.facts
                val inheritance = inheritanceByConfiguration.getValue(configuration)
                val inheritedLegacy = inheritance.inheritsAny(facts.owner, legacyInterfaces)
                val inheritedCanonical =
                    inheritance.inheritsAny(facts.owner, canonicalInterfaces)
                val hasLegacyToken = facts.utf8Values.any {
                    it in legacyInterfaces || it in legacyPlantTypes
                }
                val methodSignals = facts.methods.filter { method ->
                    method.name in plantMethods &&
                        (method.name != "mayPlaceOn" ||
                            inheritance.inherits(facts.owner, bush))
                }
                if (inheritedLegacy || inheritedCanonical || hasLegacyToken ||
                    methodSignals.isNotEmpty()
                ) {
                    relevant += facts.owner.replace('/', '.')
                    canonicalFlags += inheritedCanonical
                    val required = requiredAdapterKinds.getOrPut(
                        facts.owner.replace('/', '.')
                    ) { mutableSetOf() }
                    if (inheritedLegacy || methodSignals.any {
                            it.name in setOf("getPlant", "getPlantType")
                        }
                    ) {
                        required += "plantable"
                    }
                    if (methodSignals.any { it.name == "canSustainPlant" }) {
                        required += "soil"
                    }
                    if (methodSignals.any { it.name == "mayPlaceOn" }) {
                        required += "placement"
                    }
                }
                if (inheritedLegacy) {
                    classFacts +=
                        "$configuration|${facts.owner}|inherits-legacy-plantable"
                }
                if (inheritedCanonical) {
                    classFacts +=
                        "$configuration|${facts.owner}|inherits-canonical-plant-abi"
                }
                methodSignals.forEach {
                    classFacts +=
                        "$configuration|${facts.owner}|method|${it.name}|" +
                        "${it.descriptor}|${it.accessFlags}|" +
                        it.codeAttributeSha256.orEmpty()
                }
                if (inheritance.inherits(facts.owner, block)) {
                    classFacts += "$configuration|${facts.owner}|inherits-block"
                }
            }
            val classes = artifacts.getValue(artifact)
            val artifactOwners = classes.map { it.facts.owner }.toSet()
            val canonicalRuntime = artifact.componentKind == "project" &&
                artifact.sha256 == runtimeIdentity.jarSha256 &&
                CANONICAL_RUNTIME_CLASSES.all { it in artifactOwners } &&
                artifact.configurations.all { configuration ->
                    CANONICAL_RUNTIME_CLASSES.all { owner ->
                        activeByConfiguration.getValue(configuration)[owner]
                            ?.artifact == artifact
                    }
                }
            val platform = platformByArtifact[artifact]
            val allCanonical =
                canonicalFlags.isNotEmpty() && canonicalFlags.all { it }
            val proposedManifest = adapterManifests[artifact]
            val adapterManifest = proposedManifest?.takeIf { manifest ->
                val ownedClasses = classes.map { it.facts.owner.replace('/', '.') }.toSet()
                val registrationsByTarget = manifest.registrations.groupBy {
                    registrationTargetClass(it.target)
                }
                val targetsOwned = registrationsByTarget.keys.all { it in ownedClasses }
                val completeClassCoverage =
                    relevant.distinct().all { owner ->
                        val required = requiredAdapterKinds[owner].orEmpty()
                        required.isNotEmpty() && required.all { kind ->
                            registrationsByTarget[owner].orEmpty().any {
                                it.registrationMode == "class" &&
                                    it.adapterKind == kind
                            }
                        }
                    }
                if (!targetsOwned || !completeClassCoverage) {
                    blockers +=
                        "adapter manifest does not class-cover every relevant plant surface " +
                        "for ${artifact.key}"
                    false
                } else {
                    true
                }
            }
            PlantSurfaceScan(
                hasPlantSurface = relevant.isNotEmpty(),
                relevantClasses = relevant.distinct().sorted(),
                classFacts = (
                    classFacts +
                        listOfNotNull(
                            platform?.let {
                                "platformFingerprint=${it.sourceReceiptSha256}"
                            },
                            platform?.run {
                                graph.platformIdentity
                            }?.let {
                                "platformIdentity=${it.sha256}"
                            }
                        )
                    ).distinct().sorted(),
                allRelevantClassesImplementCanonicalAbi =
                    relevant.isNotEmpty() && allCanonical,
                isVerifiedTargetVanilla =
                    graph.phase == InventoryPhase.TARGET && platform != null,
                isVerifiedSourcePlatform =
                    graph.phase == InventoryPhase.SOURCE && platform != null,
                isCanonicalRuntime = canonicalRuntime,
                adapterManifest = adapterManifest
            )
        }
        val defined = activeByConfiguration.values.flatMap { it.keys }
            .map { it.replace('/', '.') }
            .toSet()
        return ClosurePlantScan(
            artifacts = scans,
            definedClasses = defined,
            differentBytecodeDuplicates = duplicates,
            blockers = blockers
        )
    }

    private fun isConflictRelevant(
        facts: JvmClassFacts,
        inheritance: ClosureInheritance
    ): Boolean =
        isSurfaceRelevant(facts, inheritance) || inheritance.inherits(facts.owner, block)

    private fun isSurfaceRelevant(
        facts: JvmClassFacts,
        inheritance: ClosureInheritance
    ): Boolean =
        inheritance.inheritsAny(facts.owner, legacyInterfaces) ||
            inheritance.inheritsAny(facts.owner, canonicalInterfaces) ||
            facts.utf8Values.any {
                it in legacyInterfaces || it in legacyPlantTypes ||
                    it in canonicalInterfaces
            } ||
            facts.methods.any {
                it.name in plantMethods &&
                    (it.name != "mayPlaceOn" || inheritance.inherits(facts.owner, bush))
            }

    private fun plantConflictSignature(
        facts: JvmClassFacts,
        inheritance: ClosureInheritance
    ): String = buildList {
        add("super=${facts.superName.orEmpty()}")
        add("interfaces=${facts.interfaces.sorted().joinToString(",")}")
        add("block=${inheritance.inherits(facts.owner, block)}")
        add("legacy=${inheritance.inheritsAny(facts.owner, legacyInterfaces)}")
        add("canonical=${inheritance.inheritsAny(facts.owner, canonicalInterfaces)}")
        facts.methods.filter { it.name in plantMethods }
            .sortedWith(compareBy<JvmMethod>({ it.name }, { it.descriptor }))
            .forEach {
                add(
                    "method=${it.name}|${it.descriptor}|${it.accessFlags}|" +
                        it.codeAttributeSha256.orEmpty()
                )
            }
        facts.utf8Values.filter {
            it in legacyInterfaces || it in legacyPlantTypes ||
                it in canonicalInterfaces
        }.sorted().forEach { add("token=$it") }
    }.joinToString(";")

    private fun matchesFingerprint(
        classes: List<OwnedClass>,
        fingerprint: PlatformArtifactFingerprint
    ): Boolean {
        val byOwner = classes.groupBy { it.facts.owner }
        return fingerprint.requiredClasses.all { required ->
            byOwner[required.owner]?.singleOrNull()?.let {
                it.facts.accessFlags == required.accessFlags &&
                    it.classSha256 == required.classSha256
            } == true
        } && fingerprint.requiredMethods.all { required ->
            byOwner[required.owner]?.singleOrNull()?.facts?.methods?.singleOrNull {
                it.name == required.name && it.descriptor == required.descriptor
            }?.let {
                it.accessFlags == required.accessFlags &&
                    it.codeAttributeSha256 == required.codeAttributeSha256
            } == true
        }
    }

    private fun matchesReviewedSourceEvidence(
        graph: ResolvedGraph,
        fingerprint: PlatformArtifactFingerprint
    ): Boolean {
        val auxiliaries = graph.platformAuxiliaries.associateBy(PlatformAuxiliaryArtifact::kind)
        val merged = auxiliaries["merged"] ?: return false
        val sources = auxiliaries["sources"] ?: return false
        if (merged.displayName != fingerprint.mergedArtifactDisplayName ||
            merged.sha256 != fingerprint.mergedArtifactSha256 ||
            sources.displayName != fingerprint.sourcesArtifactDisplayName ||
            sources.sha256 != fingerprint.sourcesArtifactSha256
        ) {
            return false
        }
        JarFile(sources.file.toFile(), false).use { sourceJar ->
            JarFile(merged.file.toFile(), false).use { mergedJar ->
                return fingerprint.reviewedSourceEntries.all { (entryName, expectedHash) ->
                    val sourceEntry = sourceJar.getJarEntry(entryName) ?: return@all false
                    val mergedEntry = mergedJar.getJarEntry(entryName) ?: return@all false
                    val sourceBytes = sourceJar.getInputStream(sourceEntry).use { it.readBytes() }
                    val mergedBytes = mergedJar.getInputStream(mergedEntry).use { it.readBytes() }
                    sourceBytes.contentEquals(mergedBytes) &&
                        sha256(sourceBytes) == expectedHash
                }
            }
        }
    }

    private fun registrationTargetClass(target: String): String =
        target.substringBefore('#').replace('/', '.')

    private fun readArtifact(
        artifact: ResolvedArtifact,
        classRuntimeFeature: Int
    ): List<OwnedClass> =
        readSelectedJvmClasses(
            artifact.file,
            artifact.sha256,
            classRuntimeFeature
        ).map {
            OwnedClass(
                artifact = artifact,
                entryName = it.entryName,
                release = it.release,
                classSha256 = it.classSha256,
                facts = it.facts
            )
        }

    private data class OwnedClass(
        val artifact: ResolvedArtifact,
        val entryName: String,
        val release: Int,
        val classSha256: String,
        val facts: JvmClassFacts
    )

    companion object {
        private val CANONICAL_RUNTIME_CLASSES = setOf(
            "com/modporter/compat/plant/v1/PlantType",
            "com/modporter/compat/plant/v1/IPlantable",
            "com/modporter/compat/plant/v1/LegacyPlantSustainer",
            "com/modporter/compat/plant/v1/LegacyPlantPlacement",
            "com/modporter/compat/plant/v1/LegacyPlantableAdapter",
            "com/modporter/compat/plant/v1/LegacySoilAdapter",
            "com/modporter/compat/plant/v1/LegacyPlantPlacementAdapter",
            "com/modporter/compat/plant/v1/LegacyPlantables"
        )
    }
}

/**
 * Source-side class and Java-platform heritage selected from one exact, ordered compile classpath.
 * Nested canonical names exist only when the selected class proves its own InnerClasses row.
 */
internal class SealedLegacyPlantSourceHeritage private constructor(
    private val classes: Map<String, SelectedHeritage>,
    private val directMembers: Map<MemberKey, MemberType>
) {
    fun hasClass(canonicalName: String): Boolean = canonicalName in classes

    fun directHeritage(canonicalName: String): List<String>? =
        classes[canonicalName]?.let { definition ->
            require(
                definition.missingSuperclass == null &&
                    definition.missingInterfaces.isEmpty()
            ) {
                "Source heritage traversal reached incomplete class $canonicalName from " +
                    "${definition.provenance}: missing " +
                    listOfNotNull(definition.missingSuperclass)
                        .plus(definition.missingInterfaces)
                        .sorted()
                        .joinToString()
            }
            listOfNotNull(definition.superName) + definition.interfaces
        }

    fun directSuperclass(canonicalName: String): String? =
        classes[canonicalName]?.let { definition ->
            require(definition.missingSuperclass == null) {
                "Source superclass traversal reached incomplete class $canonicalName from " +
                    "${definition.provenance}: missing ${definition.missingSuperclass}"
            }
            definition.superName
        }

    fun declaresMethod(canonicalName: String, name: String, descriptor: String): Boolean =
        classes[canonicalName]?.facts?.methods?.any {
            it.name == name && it.descriptor == descriptor
        } == true

    fun declaresConcreteMethod(
        canonicalName: String,
        name: String,
        descriptor: String
    ): Boolean =
        classes[canonicalName]?.facts?.methods?.any {
            it.name == name && it.descriptor == descriptor &&
                it.accessFlags and ACC_ABSTRACT == 0 &&
                it.accessFlags and ACC_STATIC == 0 &&
                it.accessFlags and ACC_PRIVATE == 0
        } == true

    fun directMemberType(
        owner: String,
        simpleName: String,
        accessingPackage: String,
        inherited: Boolean
    ): String? {
        val member = directMembers[MemberKey(owner, simpleName)] ?: return null
        if (!inherited) return member.canonicalName
        if (member.accessFlags and ACC_PRIVATE != 0) return null
        if (member.accessFlags and (ACC_PUBLIC or ACC_PROTECTED) != 0) {
            return member.canonicalName
        }
        return member.canonicalName.takeIf {
            packageName(owner) == accessingPackage
        }
    }

    private data class MemberKey(val owner: String, val simpleName: String)

    private data class MemberType(
        val canonicalName: String,
        val accessFlags: Int
    )

    private data class SelectedHeritage(
        val canonicalName: String,
        val binaryName: String,
        val superName: String?,
        val interfaces: List<String>,
        val missingSuperclass: String?,
        val missingInterfaces: List<String>,
        val facts: JvmClassFacts,
        val provenance: String,
        val shadowed: List<String>
    )

    private data class ArtifactClass(
        val artifact: LegacyMigrationArtifactInventory.Artifact,
        val order: Int,
        val selected: SelectedJvmClass
    )

    private data class BinaryDefinition(
        val facts: JvmClassFacts,
        val provenance: String,
        val shadowed: List<String>
    )

    companion object {
        private const val JAVA_17_RELEASE_CODE = 'H'
        private const val ACC_PUBLIC = 0x0001
        private const val ACC_PRIVATE = 0x0002
        private const val ACC_PROTECTED = 0x0004
        private const val ACC_STATIC = 0x0008
        private const val ACC_ABSTRACT = 0x0400
        private val java17DefinitionsByPath =
            ConcurrentHashMap<Path, Map<String, BinaryDefinition>>()
        private val java17PlatformsByPath =
            ConcurrentHashMap<Path, SealedLegacyPlantSourceHeritage>()
        private val runtimeJava17Definitions by lazy {
            readRuntimeJava17Classes()
        }
        private val runtimeJava17Platform by lazy {
            build(runtimeJava17Definitions)
        }

        fun from(inventory: LegacyMigrationArtifactPair): SealedLegacyPlantSourceHeritage {
            inventory.verifyCurrentBytes("legacy plant source heritage import")
            val source = inventory.source
            require(source.classRuntimeFeature == 17) {
                "Legacy plant source heritage requires the exact Java 17 signature image, " +
                    "but the sealed graph selected feature ${source.classRuntimeFeature}"
            }
            val configuration = source.compileClasspathConfiguration
            val activeArtifacts = source.closureArtifacts.filter {
                configuration in it.resolutionConfigurations
            }.sortedBy {
                it.configurationOrder.getValue(configuration)
            }
            val artifactDefinitions = activeArtifacts.flatMap { artifact ->
                artifact.verifyCurrentBytes("legacy plant source heritage scan")
                val order = artifact.configurationOrder.getValue(configuration)
                readSelectedJvmClasses(
                    artifact.file,
                    artifact.sha256,
                    source.classRuntimeFeature
                ).map { ArtifactClass(artifact, order, it) }
            }.groupBy { it.selected.facts.owner }
                .mapValues { (owner, candidates) ->
                    val firstOrder = candidates.minOf(ArtifactClass::order)
                    val selected = candidates.filter { it.order == firstOrder }
                    require(selected.size == 1) {
                        "Sealed source compile classpath has an ambiguous owner $owner at " +
                            "order $firstOrder: " +
                            selected.joinToString {
                                "${it.artifact.component}|${it.artifact.sha256}"
                            }
                    }
                    val winner = selected.single()
                    BinaryDefinition(
                        facts = winner.selected.facts,
                        provenance =
                            "${winner.artifact.component}|${winner.artifact.sha256}|" +
                                "${winner.selected.entryName}|${winner.selected.classSha256}",
                        shadowed = candidates.filterNot { it === winner }
                            .sortedBy(ArtifactClass::order)
                            .map {
                                "${it.order}|${it.artifact.component}|${it.artifact.sha256}|" +
                                    "${it.selected.entryName}|${it.selected.classSha256}"
                            }
                    )
                }
            val platformDefinitions = readJava17SignatureClasses()
            val duplicatePlatformOwners =
                artifactDefinitions.keys.intersect(platformDefinitions.keys)
            require(duplicatePlatformOwners.isEmpty()) {
                "Sealed source compile classpath attempts to replace Java 17 platform classes: " +
                    duplicatePlatformOwners.sorted().joinToString()
            }
            return build(
                artifactDefinitions + platformDefinitions
            )
        }

        fun java17Platform(): SealedLegacyPlantSourceHeritage {
            if (Runtime.version().feature() == 17) return runtimeJava17Platform
            require(Runtime.version().feature() > 17) {
                "Cannot prove Java 17 platform heritage from runtime feature " +
                    Runtime.version().feature()
            }
            val path = java17SignaturePath()
            return java17PlatformsByPath.computeIfAbsent(path) {
                build(readJava17SignatureClasses(path))
            }
        }

        private fun build(
            definitions: Map<String, BinaryDefinition>
        ): SealedLegacyPlantSourceHeritage {
            val canonicalByBinary = linkedMapOf<String, String>()
            val declaredSelfRows = definitions.mapValues { (binaryName, definition) ->
                val rows = definition.facts.innerClasses.filter {
                    it.innerBinaryName == binaryName &&
                        it.outerBinaryName != null &&
                        it.innerSimpleName != null
                }.distinct()
                require(rows.size <= 1) {
                    "Selected class $binaryName has conflicting self InnerClasses rows: $rows"
                }
                rows.singleOrNull()
            }
            val selfRows = declaredSelfRows.mapValues { (_, row) ->
                row?.takeIf { requireNotNull(it.outerBinaryName) in definitions }
            }
            fun canonical(binaryName: String, pending: MutableSet<String>): String {
                canonicalByBinary[binaryName]?.let { return it }
                require(pending.add(binaryName)) {
                    "Selected InnerClasses graph contains a cycle at $binaryName"
                }
                val row = selfRows.getValue(binaryName)
                val value = if (row == null) {
                    binaryName.replace('/', '.')
                } else {
                    val outer = requireNotNull(row.outerBinaryName)
                    canonical(outer, pending) + "." + requireNotNull(row.innerSimpleName)
                }
                pending.remove(binaryName)
                canonicalByBinary[binaryName] = value
                return value
            }
            definitions.keys.sorted().forEach {
                canonical(it, mutableSetOf())
            }
            val collisions = canonicalByBinary.entries.groupBy { it.value }
                .filterValues { it.size > 1 }
            require(collisions.isEmpty()) {
                "Selected binary classes have colliding proven canonical aliases: " +
                    collisions.entries.sortedBy { it.key }
                        .joinToString { (alias, owners) ->
                            "$alias=${owners.map { it.key }.sorted().joinToString(",")}"
                        }
            }
            val selected = linkedMapOf<String, SelectedHeritage>()
            val members = linkedMapOf<MemberKey, MemberType>()
            for ((binaryName, definition) in definitions) {
                val canonicalName = canonicalByBinary.getValue(binaryName)
                val superName = definition.facts.superName?.let(canonicalByBinary::get)
                val missingSuperclass = definition.facts.superName
                    ?.takeIf { it !in canonicalByBinary }
                val interfaces = definition.facts.interfaces.mapNotNull(canonicalByBinary::get)
                val missingInterfaces = definition.facts.interfaces.filter {
                    it !in canonicalByBinary
                }
                selected[canonicalName] = SelectedHeritage(
                    canonicalName = canonicalName,
                    binaryName = binaryName,
                    superName = superName,
                    interfaces = interfaces,
                    missingSuperclass = missingSuperclass,
                    missingInterfaces = missingInterfaces,
                    facts = definition.facts,
                    provenance = definition.provenance,
                    shadowed = definition.shadowed
                )
                val selfRow = selfRows.getValue(binaryName) ?: continue
                val outer = canonicalByBinary.getValue(requireNotNull(selfRow.outerBinaryName))
                val key = MemberKey(outer, requireNotNull(selfRow.innerSimpleName))
                require(members.put(
                    key,
                    MemberType(canonicalName, selfRow.accessFlags)
                ) == null) {
                    "Selected classpath has more than one direct nested member " +
                        "${key.owner}.${key.simpleName}"
                }
            }
            return SealedLegacyPlantSourceHeritage(
                classes = selected.toMap(),
                directMembers = members.toMap()
            )
        }

        private fun java17SignaturePath(): Path {
            val explicit = System.getProperty("modporter.java17.ctSym")
                ?.takeIf(String::isNotBlank)
            return (explicit?.let { Path.of(it) }
                ?: Path.of(System.getProperty("java.home"), "lib", "ct.sym"))
                .toAbsolutePath()
                .normalize()
        }

        private fun readJava17SignatureClasses(): Map<String, BinaryDefinition> {
            if (Runtime.version().feature() == 17) return runtimeJava17Definitions
            require(Runtime.version().feature() > 17) {
                "Cannot prove Java 17 platform heritage from runtime feature " +
                    Runtime.version().feature()
            }
            return readJava17SignatureClasses(java17SignaturePath())
        }

        private fun readJava17SignatureClasses(
            file: Path
        ): Map<String, BinaryDefinition> =
            java17DefinitionsByPath.computeIfAbsent(file) {
                readJava17SignatureClassesUncached(it)
            }

        private fun readJava17SignatureClassesUncached(
            file: Path
        ): Map<String, BinaryDefinition> {
            require(file.isRegularFile()) {
                "Exact Java 17 signature image is unavailable: $file"
            }
            val definitions = linkedMapOf<String, MutableList<JvmClassFacts>>()
            JarFile(file.toFile(), false).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".sig")) continue
                    val releaseSet = entry.name.substringBefore('/', "")
                    if (JAVA_17_RELEASE_CODE !in releaseSet) continue
                    val bytes = archive.getInputStream(entry).use { it.readBytes() }
                    val facts = MinimalClassFileReader(bytes).read()
                    if (facts.owner == "module-info") continue
                    definitions.getOrPut(facts.owner) { mutableListOf() } += facts
                }
            }
            require(definitions.isNotEmpty()) {
                "Exact Java 17 signature image contains no release-17 classes: $file"
            }
            return definitions.mapValues { (owner, candidates) ->
                val distinct = candidates.distinct()
                require(distinct.size == 1) {
                    "Java 17 signature image selects inconsistent definitions for $owner"
                }
                BinaryDefinition(
                    facts = distinct.single(),
                    provenance = "ct.sym|17|$file",
                    shadowed = emptyList()
                )
            }
        }

        private fun readRuntimeJava17Classes(): Map<String, BinaryDefinition> {
            require(Runtime.version().feature() == 17) {
                "The jrt platform image is authoritative only for an exact Java 17 runtime"
            }
            val fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"))
            val modules = fileSystem.getPath("/modules")
            require(Files.isDirectory(modules)) {
                "Exact Java 17 runtime module image is unavailable: $modules"
            }
            val definitions = linkedMapOf<String, MutableList<JvmClassFacts>>()
            Files.walk(modules).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().endsWith(".class") &&
                        path.fileName.toString() !in
                        setOf("module-info.class", "package-info.class")
                }.forEach { path ->
                    val facts = MinimalClassFileReader(Files.readAllBytes(path)).read()
                    definitions.getOrPut(facts.owner) { mutableListOf() } += facts
                }
            }
            require(definitions.isNotEmpty()) {
                "Exact Java 17 runtime module image contains no classes"
            }
            return definitions.mapValues { (owner, candidates) ->
                val distinct = candidates.distinct()
                require(distinct.size == 1) {
                    "Java 17 runtime module image selects inconsistent definitions for $owner"
                }
                BinaryDefinition(
                    facts = distinct.single(),
                    provenance =
                        "jrt|17|${Path.of(System.getProperty("java.home")).toAbsolutePath()}",
                    shadowed = emptyList()
                )
            }
        }

        private fun packageName(canonicalName: String): String {
            val firstType = canonicalName.split('.').indexOfFirst {
                it.firstOrNull()?.isUpperCase() == true || '$' in it
            }
            return if (firstType <= 0) {
                canonicalName.substringBeforeLast('.', "")
            } else {
                canonicalName.split('.').take(firstType).joinToString(".")
            }
        }
    }
}

private data class SelectedJvmClass(
    val entryName: String,
    val release: Int,
    val classSha256: String,
    val facts: JvmClassFacts
)

private fun readSelectedJvmClasses(
    file: Path,
    expectedSha256: String,
    classRuntimeFeature: Int
): List<SelectedJvmClass> {
    require(file.isRegularFile())
    require(sha256(file.readBytes()) == expectedSha256)
    JarFile(file.toFile(), false).use { jar ->
        val multiRelease = jar.manifest?.mainAttributes
            ?.getValue("Multi-Release")
            ?.equals("true", ignoreCase = true) == true
        val selected = linkedMapOf<String, Pair<Int, String>>()
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.endsWith(".class") ||
                entry.name.endsWith("module-info.class") ||
                entry.name.endsWith("package-info.class")
            ) continue
            var logical = entry.name
            var release = 0
            if (entry.name.startsWith("META-INF/versions/")) {
                val parts = entry.name.split('/', limit = 4)
                val candidate = parts.getOrNull(2)?.toIntOrNull()
                if (!multiRelease || parts.size != 4 || candidate == null ||
                    candidate !in 9..classRuntimeFeature
                ) continue
                release = candidate
                logical = parts[3]
            }
            val previous = selected[logical]
            if (previous == null || release > previous.first) {
                selected[logical] = release to entry.name
            }
        }
        return selected.toSortedMap().map { (_, selectedEntry) ->
            val (release, entryName) = selectedEntry
            val bytes = jar.getInputStream(jar.getJarEntry(entryName)).use {
                it.readBytes()
            }
            SelectedJvmClass(
                entryName = entryName,
                release = release,
                classSha256 = sha256(bytes),
                facts = MinimalClassFileReader(bytes).read()
            )
        }
    }
}

private data class JvmClassFacts(
    val owner: String,
    val accessFlags: Int,
    val superName: String?,
    val interfaces: List<String>,
    val methods: List<JvmMethod>,
    val innerClasses: List<JvmInnerClass>,
    val utf8Values: Set<String>
)

private data class JvmInnerClass(
    val innerBinaryName: String,
    val outerBinaryName: String?,
    val innerSimpleName: String?,
    val accessFlags: Int
)

private data class JvmMethod(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val codeAttributeSha256: String?
)

private class ClosureInheritance(
    private val classes: Map<String, JvmClassFacts>
) {
    fun inherits(owner: String, ancestor: String): Boolean =
        inheritsAny(owner, setOf(ancestor))

    fun inheritsAny(owner: String, ancestors: Set<String>): Boolean {
        val pending = ArrayDeque(listOf(owner))
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current in ancestors) return true
            if (!seen.add(current)) continue
            val facts = classes[current] ?: continue
            facts.superName?.let(pending::add)
            pending.addAll(facts.interfaces)
        }
        return false
    }
}

private class MinimalClassFileReader(bytes: ByteArray) {
    private val input = DataInputStream(ByteArrayInputStream(bytes))
    private lateinit var utf8: Array<String?>
    private lateinit var classes: IntArray

    fun read(): JvmClassFacts {
        require(input.readInt() == 0xCAFEBABE.toInt())
        input.readUnsignedShort()
        input.readUnsignedShort()
        readConstantPool()
        val accessFlags = input.readUnsignedShort()
        val thisClass = input.readUnsignedShort()
        val superClass = input.readUnsignedShort()
        val interfaceCount = input.readUnsignedShort()
        val interfaces = (0 until interfaceCount).map { className(input.readUnsignedShort()) }
        skipMembers()
        val methods = readMethods()
        val innerClasses = readClassAttributes()
        return JvmClassFacts(
            owner = className(thisClass),
            accessFlags = accessFlags,
            superName = if (superClass == 0) null else className(superClass),
            interfaces = interfaces,
            methods = methods,
            innerClasses = innerClasses,
            utf8Values = utf8.filterNotNull().toSet()
        )
    }

    private fun readConstantPool() {
        val count = input.readUnsignedShort()
        utf8 = arrayOfNulls(count)
        classes = IntArray(count)
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[index] = input.readUTF()
                3, 4 -> input.skipFully(4)
                5, 6 -> {
                    input.skipFully(8)
                    index++
                }
                7 -> classes[index] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.skipFully(2)
                9, 10, 11, 12, 17, 18 -> input.skipFully(4)
                15 -> input.skipFully(3)
                else -> error("Unsupported classfile constant-pool tag $tag")
            }
            index++
        }
    }

    private fun skipMembers() {
        val fieldCount = input.readUnsignedShort()
        repeat(fieldCount) {
            input.skipFully(6)
            skipAttributes()
        }
    }

    private fun readMethods(): List<JvmMethod> {
        val result = mutableListOf<JvmMethod>()
        val count = input.readUnsignedShort()
        repeat(count) {
            val accessFlags = input.readUnsignedShort()
            val name = utf8At(input.readUnsignedShort())
            val descriptor = utf8At(input.readUnsignedShort())
            var codeAttributeSha256: String? = null
            val attributeCount = input.readUnsignedShort()
            repeat(attributeCount) {
                val attributeName = utf8At(input.readUnsignedShort())
                val length = input.readInt()
                require(length >= 0)
                val bytes = ByteArray(length)
                input.readFully(bytes)
                if (attributeName == "Code") {
                    require(codeAttributeSha256 == null) {
                        "Duplicate Code attribute on $name$descriptor"
                    }
                    codeAttributeSha256 = sha256(bytes)
                }
            }
            result += JvmMethod(
                name,
                descriptor,
                accessFlags,
                codeAttributeSha256
            )
        }
        return result
    }

    private fun readClassAttributes(): List<JvmInnerClass> {
        val innerClasses = mutableListOf<JvmInnerClass>()
        val count = input.readUnsignedShort()
        repeat(count) {
            val name = utf8At(input.readUnsignedShort())
            val length = input.readInt()
            require(length >= 0)
            val bytes = ByteArray(length)
            input.readFully(bytes)
            if (name != "InnerClasses") return@repeat
            val attribute = DataInputStream(ByteArrayInputStream(bytes))
            val classCount = attribute.readUnsignedShort()
            repeat(classCount) {
                val innerIndex = attribute.readUnsignedShort()
                val outerIndex = attribute.readUnsignedShort()
                val simpleIndex = attribute.readUnsignedShort()
                val accessFlags = attribute.readUnsignedShort()
                require(innerIndex != 0) {
                    "InnerClasses attribute has no inner class identity"
                }
                innerClasses += JvmInnerClass(
                    innerBinaryName = className(innerIndex),
                    outerBinaryName =
                        if (outerIndex == 0) null else className(outerIndex),
                    innerSimpleName =
                        if (simpleIndex == 0) null else utf8At(simpleIndex),
                    accessFlags = accessFlags
                )
            }
            require(attribute.available() == 0) {
                "InnerClasses attribute has trailing bytes"
            }
        }
        return innerClasses
    }

    private fun skipAttributes() {
        val count = input.readUnsignedShort()
        repeat(count) {
            input.readUnsignedShort()
            val length = input.readInt()
            require(length >= 0)
            input.skipFully(length.toLong())
        }
    }

    private fun className(index: Int): String = utf8At(classes[index])

    private fun utf8At(index: Int): String =
        requireNotNull(utf8[index]) { "Missing classfile UTF8 constant $index" }
}

private fun DataInputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            require(read() >= 0) { "Unexpected end of classfile" }
            remaining--
        }
    }
}

private fun ResolvedGraph.exactCompileClasspathConfiguration(): String {
    val candidates = configurations.filter {
        it.substringAfterLast('|') == "compileClasspath" && '|' in it
    }
    require(candidates.size == 1) {
        "${phase.name.lowercase()} graph must expose one exact project-qualified compile " +
            "classpath, found ${candidates.sorted()}"
    }
    return candidates.single()
}

/**
 * Concrete v2 verification for LegacyPlantablesRuntimeMigration.verifyExternalCoverage. It
 * verifies the pointer, immutable run, graph/artifact bijection, every referenced receipt, and the
 * complete run file set before returning.
 */
internal object LegacyPlantCoverageV2Verifier {
    private const val OUTPUT = ".modporter/legacy-plant-external-coverage.properties"
    private const val ROOT = ".modporter/legacy-plant-inventory/runs"

    fun verify(
        project: Path,
        runtimeIdentity: CanonicalRuntimeIdentity,
        expectedBuildMappingSha256: String = productionBuildMappingSha256(),
        scanner: LegacyPlantBytecodeScanner =
            Jvm21LegacyPlantBytecodeScanner(ReviewedPlantPlatformFingerprints.all)
    ) {
        verifyCoverage(
            project = project,
            runtimeIdentity = runtimeIdentity,
            expectedBuildMappingSha256 = expectedBuildMappingSha256,
            scanner = scanner
        )
    }

    @JvmOverloads
    fun verifyAndPublish(
        ownerProject: Path,
        explicitLeafProject: Path,
        runtimeIdentity: CanonicalRuntimeIdentity,
        expectedBuildMappingSha256: String = productionBuildMappingSha256(),
        scanner: LegacyPlantBytecodeScanner =
            Jvm21LegacyPlantBytecodeScanner(ReviewedPlantPlatformFingerprints.all)
    ): LegacyMigrationArtifactPair {
        val owner = ownerProject.toAbsolutePath().normalize()
        val leaf = explicitLeafProject.toAbsolutePath().normalize()
        require(leaf.startsWith(owner)) {
            "Legacy migration leaf project is outside its owning project: $leaf"
        }
        val verified = verifyCoverage(
            project = owner,
            runtimeIdentity = runtimeIdentity,
            expectedBuildMappingSha256 = expectedBuildMappingSha256,
            scanner = scanner
        )
        val source = sealedInventory(
            graph = verified.sourceGraph,
            runRoot = verified.runRoot,
            phase = InventoryPhase.SOURCE
        )
        val target = sealedInventory(
            graph = verified.targetGraph,
            runRoot = verified.runRoot,
            phase = InventoryPhase.TARGET
        )
        val inventory = LegacyMigrationArtifactPair(
            ownerProject = owner,
            leafProject = leaf,
            sealedRunRoot = verified.runRoot,
            source = LegacyMigrationArtifactInventory.Source(
                platformIdentity = source.platformIdentity,
                platformArtifacts = source.platformArtifacts,
                closureArtifacts = source.closureArtifacts,
                classRuntimeFeature = source.classRuntimeFeature,
                compileClasspathConfiguration = source.compileClasspathConfiguration
            ),
            target = LegacyMigrationArtifactInventory.Target(
                platformIdentity = target.platformIdentity,
                platformArtifacts = target.platformArtifacts,
                closureArtifacts = target.closureArtifacts
            )
        )
        LegacyMigrationArtifactInventoryHandoff.publish(inventory)
        return inventory
    }

    private fun verifyCoverage(
        project: Path,
        runtimeIdentity: CanonicalRuntimeIdentity,
        expectedBuildMappingSha256: String,
        scanner: LegacyPlantBytecodeScanner
    ): VerifiedCoverage {
        runtimeIdentity.verify()
        require(expectedBuildMappingSha256.matches(Regex("[0-9a-f]{64}")))
        val expectedRuntimeSourceSha256 = runtimeIdentity.sourceSha256
        val expectedRuntimeJarSha256 = runtimeIdentity.jarSha256
        val pointer = project.resolve(OUTPUT).toAbsolutePath().normalize()
        require(pointer.isRegularFile())
        val values = strictProperties(pointer)
        require(values["schema"] == "legacy-plant-external-coverage-v2")
        require(values["producer"] == "modporter-dual-closure-v1")
        require(values["complete"] == "true")
        require(values["runtimeSourceSha256"] == expectedRuntimeSourceSha256)
        require(values["runtimeJarSha256"] == expectedRuntimeJarSha256)
        val runId = values.getValue("runId")
        require(runId.matches(Regex("[0-9a-f]{64}")))
        val runRoot = project.resolve("$ROOT/$runId").toAbsolutePath().normalize()
        require(runRoot.parent == project.resolve(ROOT).toAbsolutePath().normalize())
        require(Files.isDirectory(runRoot) && !Files.isSymbolicLink(runRoot))
        val realRunRoot = runRoot.toRealPath()

        val referenced = mutableSetOf<Path>()
        fun receipt(pathKey: String, hashKey: String): Path {
            val relative = values.getValue(pathKey)
            val expectedPrefix = "$ROOT/$runId/"
            require(relative.startsWith(expectedPrefix))
            val path = project.resolve(relative).toAbsolutePath().normalize()
            require(
                path.startsWith(runRoot) &&
                    path.isRegularFile() &&
                    !Files.isSymbolicLink(path) &&
                    path.toRealPath().startsWith(realRunRoot)
            )
            require(values.getValue(hashKey).matches(Regex("[0-9a-f]{64}")))
            require(sha256(path.readBytes()) == values.getValue(hashKey))
            referenced.add(path)
            return path
        }

        val runSeed = receipt("runSeedPath", "runSeedSha256")
        require(sha256(runSeed.readBytes()) == runId)
        val sourceGraph = receipt("sourceGraphPath", "sourceGraphSha256")
        val targetGraph = receipt("targetGraphPath", "targetGraphSha256")
        val sourcePlatformRecord = requireNotNull(graphPlatformIdentity(sourceGraph)) {
            "Source graph is missing an exact platform resolution identity"
        }
        val sourcePlatformIdentity =
            parsePlatformIdentity(sourcePlatformRecord.canonicalText)
        val reviewedSourcePlatform = ReviewedPlantPlatformFingerprints.all.singleOrNull {
            it.phase == InventoryPhase.SOURCE &&
                it.matchesPlatformIdentity(sourcePlatformIdentity)
        } ?: error(
            "Source graph platform identity has no reviewed plant fingerprint: " +
                sourcePlatformRecord.sha256
        )
        val targetPlatformRecord = requireNotNull(graphPlatformIdentity(targetGraph)) {
            "Target graph is missing an exact platform resolution identity"
        }
        val targetPlatformIdentity =
            parsePlatformIdentity(targetPlatformRecord.canonicalText)
        val reviewedTargetPlatform = ReviewedPlantPlatformFingerprints.all.singleOrNull {
            it.phase == InventoryPhase.TARGET &&
                it.matchesPlatformIdentity(targetPlatformIdentity)
        } ?: error(
            "Target graph platform identity has no reviewed plant fingerprint: " +
                targetPlatformRecord.sha256
        )
        val sourceClosureScan =
            receipt("sourceClosureScanPath", "sourceClosureScanSha256")
        val targetClosureScan =
            receipt("targetClosureScanPath", "targetClosureScanSha256")
        val targetPlan = receipt("targetPlanPath", "targetPlanSha256")
        val runtimeIdentityReceipt =
            receipt("runtimeIdentityPath", "runtimeIdentitySha256")
        val platformAuxiliaryPaths = mapOf(
            "source" to mapOf(
                "merged" to receipt(
                    "sourcePlatformMergedPath",
                    "sourcePlatformMergedSha256"
                ),
                "sources" to receipt(
                    "sourcePlatformSourcesPath",
                    "sourcePlatformSourcesSha256"
                )
            ),
            "target" to mapOf(
                "merged" to receipt(
                    "targetPlatformMergedPath",
                    "targetPlatformMergedSha256"
                ),
                "sources" to receipt(
                    "targetPlatformSourcesPath",
                    "targetPlatformSourcesSha256"
                )
            )
        )
        require(
            values.getValue("sourcePlatformMergedSha256") ==
                reviewedSourcePlatform.mergedArtifactSha256 &&
                values.getValue("sourcePlatformSourcesSha256") ==
                reviewedSourcePlatform.sourcesArtifactSha256 &&
                values.getValue("targetPlatformMergedSha256") ==
                reviewedTargetPlatform.mergedArtifactSha256 &&
                values.getValue("targetPlatformSourcesSha256") ==
                reviewedTargetPlatform.sourcesArtifactSha256
        )
        val targetPlanRecords = receiptRecords(targetPlan)
        require(
            targetPlanRecords.singleValue("schema") ==
                "legacy-plant-target-build-plan-v2"
        )
        require(targetPlanRecords.all {
            it.first in setOf(
                "schema",
                "preparation",
                "adapterRegistration"
            )
        }) {
            "Target BuildSystem plan contains an unknown record type"
        }
        val preparationText =
            nestedTargetPlanReceipts(targetPlanRecords, "preparation").values.single()
        val preparationRecords = receiptRecords(preparationText)
        require(
            preparationRecords.singleValue("schema") ==
                "build-dependency-preparation-v2" &&
                preparationRecords.singleValue("mappingResourceSha256") ==
                values.getValue("buildMappingSha256") &&
                values.getValue("buildMappingSha256") ==
                expectedBuildMappingSha256
        )
        require(preparationRecords.all {
            it.first in setOf(
                "schema",
                "mappingResourceSha256",
                "decision",
                "removal",
                "buildBefore",
                "buildAfter",
                "resourceBefore",
                "resourceAfter",
                "sourceBefore",
                "sourceAfter"
            )
        }) {
            "BuildDependencyPreparationReceipt contains an unknown record type"
        }
        val plannedDecisionReceipts =
            nestedTargetPlanReceipts(preparationRecords, "decision")
        val plannedRemovalReceipts =
            nestedTargetPlanReceipts(preparationRecords, "removal")
        val preparationSnapshots = verifyBuildDeclarationTransition(
            sourceGraph,
            targetGraph,
            sourcePlatformIdentity,
            targetPlatformIdentity,
            preparationRecords
        )
        val plannedAdapterRegistrations =
            parsePlannedAdapterRegistrations(targetPlanRecords)
        plannedAdapterRegistrations.forEach { registration ->
            require(
                preparationSnapshots.sourceTransitions[
                    registration.adapterSourcePath
                ]?.second == registration.adapterSourceSha256 &&
                    preparationSnapshots.sourceAfter[
                        registration.adapterSourcePath
                    ] == registration.adapterSourceSha256
            ) {
                "Planned adapter registration is not bound to the prepared source snapshot"
            }
        }
        val runtimeSourceTransitions = runtimeIdentity.generatedSourceHashes()
            .mapKeys { (name, _) ->
                ".modporter/legacy-plant-compat/src/main/java/" +
                    "com/modporter/compat/plant/v1/$name.java"
            }
        require(runtimeSourceTransitions.all { (path, hash) ->
            preparationSnapshots.sourceBefore[path] == null &&
                preparationSnapshots.sourceAfter[path] == hash &&
                preparationSnapshots.sourceTransitions[path] == (null to hash)
        }) {
            "Prepared canonical runtime sources do not match verified resource bytes"
        }
        val runtimeRecords = receiptRecords(runtimeIdentityReceipt)
        require(
            runtimeRecords.singleValue("schema") ==
                "legacy-plant-canonical-runtime-identity-v1" &&
                runtimeRecords.singleValue("sourceSha256") ==
                expectedRuntimeSourceSha256 &&
                runtimeRecords.singleValue("jarSha256") ==
                expectedRuntimeJarSha256
        )
        val closureScanHashes = mapOf(
            "source" to closureArtifactScans(sourceClosureScan),
            "target" to closureArtifactScans(targetClosureScan)
        )

        val entryCount = values.getValue("entryCount").toInt()
        require(entryCount > 0)
        val topLevelFields = setOf(
            "schema",
            "producer",
            "runId",
            "runSeedPath",
            "runSeedSha256",
            "sourceGraphPath",
            "sourceGraphSha256",
            "sourceClosureScanPath",
            "sourceClosureScanSha256",
            "targetGraphPath",
            "targetGraphSha256",
            "targetClosureScanPath",
            "targetClosureScanSha256",
            "targetPlanPath",
            "targetPlanSha256",
            "buildMappingSha256",
            "runtimeIdentityPath",
            "runtimeIdentitySha256",
            "runtimeSourceSha256",
            "runtimeJarSha256",
            "sourcePlatformMergedPath",
            "sourcePlatformMergedSha256",
            "sourcePlatformSourcesPath",
            "sourcePlatformSourcesSha256",
            "targetPlatformMergedPath",
            "targetPlatformMergedSha256",
            "targetPlatformSourcesPath",
            "targetPlatformSourcesSha256",
            "entryCount",
            "inventorySha256",
            "complete"
        )
        val fixedEntryFields = setOf(
            "phase",
            "component",
            "variantSha256",
            "artifactPath",
            "artifactSha256",
            "scanPath",
            "scanSha256",
            "coverage",
            "registrationManifestPath",
            "registrationManifestSha256",
            "decisionPath",
            "decisionSha256",
            "removalPath",
            "removalSha256"
        )
        require(values.keys.all { key ->
            if (key in topLevelFields) return@all true
            val match = Regex("""entry\.(\d+)\.(.+)""").matchEntire(key)
                ?: return@all false
            val index = match.groupValues[1].toInt()
            val field = match.groupValues[2]
            index in 0 until entryCount &&
                (field in fixedEntryFields ||
                    Regex("""targetCoordinate\.[0-9a-f]{64}""").matches(field) ||
                    Regex(
                        """targetCoordinate\.[0-9a-f]{64}\.target\.\d+"""
                    ).matches(field))
        }) {
            "Coverage pointer contains unknown or out-of-range fields"
        }
        val entryLines = values.keys.filter { it.startsWith("entry.") }.sorted()
            .joinToString("\n", postfix = "\n") { "$it=${values.getValue(it)}" }
        require(
            sha256(entryLines.toByteArray(Charsets.UTF_8)) ==
                values.getValue("inventorySha256")
        )
        require(values.getValue("buildMappingSha256").matches(Regex("[0-9a-f]{64}")))

        val graphArtifacts = graphArtifactKeys(sourceGraph, "source") +
            graphArtifactKeys(targetGraph, "target")
        require(closureScanHashes.getValue("source").keys ==
            graphArtifacts.filter { it.phase == "source" }.map { it.key }.toSet())
        require(closureScanHashes.getValue("target").keys ==
            graphArtifacts.filter { it.phase == "target" }.map { it.key }.toSet())
        val covered = mutableSetOf<Pair<String, String>>()
        val referencedDecisionHashes = mutableSetOf<String>()
        val referencedRemovalHashes = mutableSetOf<String>()
        val removalSourceTransitions =
            mutableMapOf<String, Pair<String?, String?>>()
        val artifactPaths = mutableMapOf<Pair<String, String>, Path>()
        val scanPaths = mutableMapOf<Pair<String, String>, Path>()
        val seedEntryLines = mutableListOf<String>()
        repeat(entryCount) { index ->
            val prefix = "entry.$index"
            var decisionRecords = emptyList<Pair<String, String>>()
            var decisionSourceConfigurations = emptySet<String>()
            var matchedCoordinates = emptyMap<String, List<String>>()
            require(
                setOf(
                    "phase",
                    "component",
                    "variantSha256",
                    "artifactPath",
                    "artifactSha256",
                    "scanPath",
                    "scanSha256",
                    "coverage"
                ).all { "$prefix.$it" in values }
            )
            val phase = values.getValue("$prefix.phase")
            require(phase in setOf("source", "target"))
            val coverage = values.getValue("$prefix.coverage")
            require(coverage in setOf(
                "no-surface",
                "verified-vanilla",
                "canonical-abi",
                "shared-adapters",
                "removed-inert",
                "mapped-target",
                "verified-source-platform",
                "canonical-runtime"
            ))
            require(
                phase == "source" ||
                    coverage !in setOf(
                        "removed-inert",
                        "mapped-target",
                        "verified-source-platform"
                    )
            )
            require(
                phase == "target" ||
                    coverage !in setOf(
                        "verified-vanilla",
                        "canonical-abi",
                        "shared-adapters",
                        "canonical-runtime"
                    )
            )
            val artifactHash = values.getValue("$prefix.artifactSha256")
            require(artifactHash.matches(Regex("[0-9a-f]{64}")))
            val component = decode(values.getValue("$prefix.component"))
            require(
                ("$prefix.registrationManifestPath" in values) ==
                    ("$prefix.registrationManifestSha256" in values)
            )
            require(
                ("$prefix.decisionPath" in values) ==
                    ("$prefix.decisionSha256" in values)
            )
            require(
                ("$prefix.removalPath" in values) ==
                    ("$prefix.removalSha256" in values)
            )
            val artifact = receipt("$prefix.artifactPath", "$prefix.artifactSha256")
            require(artifact.fileName.toString() == "$artifactHash.jar")
            val scan = receipt("$prefix.scanPath", "$prefix.scanSha256")
            val scanRecords = receiptRecords(scan)
            require(scanRecords.singleValue("schema") == "legacy-plant-bytecode-scan-v1")
            val hasSurface = scanRecords.singleValue("hasPlantSurface").toBooleanStrict()
            val canonicalAbi = scanRecords
                .singleValue("allRelevantClassesImplementCanonicalAbi")
                .toBooleanStrict()
            val targetVanilla = scanRecords
                .singleValue("isVerifiedTargetVanilla")
                .toBooleanStrict()
            val sourcePlatform = scanRecords
                .singleValue("isVerifiedSourcePlatform")
                .toBooleanStrict()
            val canonicalRuntime = scanRecords
                .singleValue("isCanonicalRuntime")
                .toBooleanStrict()
            when (coverage) {
                "no-surface" -> require(!hasSurface)
                "verified-vanilla" -> require(hasSurface && targetVanilla)
                "canonical-abi" -> require(hasSurface && canonicalAbi)
                "shared-adapters" -> require(
                    hasSurface &&
                        scanRecords.any { it.first == "adapterManifestSha256" }
                )
                "removed-inert", "mapped-target" -> require(hasSurface)
                "verified-source-platform" -> require(hasSurface && sourcePlatform)
                "canonical-runtime" -> require(hasSurface && canonicalRuntime)
            }
            values["$prefix.registrationManifestPath"]?.let {
                val manifest = receipt(
                    "$prefix.registrationManifestPath",
                    "$prefix.registrationManifestSha256"
                )
                require(
                    scanRecords.singleValue("adapterManifestSha256") ==
                        values.getValue("$prefix.registrationManifestSha256")
                )
                val manifestRecords = receiptRecords(manifest)
                require(
                    manifestRecords.singleValue("schema") ==
                        "legacy-plant-adapter-manifest-v1"
                )
                require(
                    manifestRecords.singleValue("artifactSha256") == artifactHash &&
                        manifestRecords.singleValue("runtimeSourceSha256") ==
                        expectedRuntimeSourceSha256 &&
                        manifestRecords.singleValue("runtimeJarSha256") ==
                        expectedRuntimeJarSha256 &&
                        manifestRecords.any { it.first == "registration" }
                )
                manifestRecords.filter { it.first == "registration" }.forEach {
                        (_, value) ->
                    val parts = value.split('|', limit = 2)
                    require(
                        parts.size == 2 &&
                            parts[1].matches(Regex("[0-9a-f]{64}"))
                    )
                    val canonicalText = decode(parts[0])
                    require(sha256(canonicalText) == parts[1])
                    val registration = canonicalText.lineSequence()
                        .filter { it.isNotBlank() }
                        .map { raw ->
                            require('=' in raw)
                            raw.substringBefore('=') to raw.substringAfter('=')
                        }.toList()
                    require(
                        registration.singleValue("registrationMode") in
                            setOf("class", "exact") &&
                            registration.singleValue("adapterKind") in
                            setOf("plantable", "soil", "placement") &&
                            registration.singleValue("adapterSourceSha256")
                                .matches(Regex("[0-9a-f]{64}"))
                    )
                    decode(registration.singleValue("owner"))
                    decode(registration.singleValue("target"))
                }
            }
            values["$prefix.decisionPath"]?.let {
                val decision = receipt("$prefix.decisionPath", "$prefix.decisionSha256")
                require(
                    referencedDecisionHashes.add(
                        values.getValue("$prefix.decisionSha256")
                    )
                )
                require(
                    plannedDecisionReceipts[
                        values.getValue("$prefix.decisionSha256")
                    ] == decision.readText()
                ) {
                    "Coverage decision is not an exact member of the BuildSystem plan"
                }
                decisionRecords = receiptRecords(decision)
                require(
                    decisionRecords.singleValue("schema") ==
                        "build-dependency-decision-v1" &&
                        decode(decisionRecords.singleValue("sourceComponent")) ==
                        component &&
                        decisionRecords.singleValue("sourceArtifactSha256") ==
                        artifactHash &&
                        decisionRecords.singleValue("catalogEntrySha256")
                            .matches(Regex("[0-9a-f]{64}"))
                ) {
                    "Coverage decision identity does not match source entry $index: $component"
                }
                decisionSourceConfigurations = decisionRecords
                    .filter { it.first == "sourceConfiguration" }
                    .map { decode(it.second) }
                    .toSet()
                require(decisionSourceConfigurations.isNotEmpty()) {
                    "Coverage decision has no source configurations for entry $index: $component"
                }
                val status = decisionRecords.singleValue("status")
                val sourceSelector = decode(
                    decisionRecords.singleValue("sourceSelector")
                )
                require(matchesBuildSourceSelector(component, sourceSelector)) {
                    "Coverage decision selector does not match source component for entry " +
                        "$index: component=$component selector=$sourceSelector"
                }
                require(
                    status in setOf("mapped", "remove") &&
                        status == when (coverage) {
                            "removed-inert" -> "remove"
                            "mapped-target" -> "mapped"
                            else -> status
                        }
                ) {
                    "Coverage decision status is incompatible with entry $index: " +
                        "coverage=$coverage status=$status"
                }
            }
            values["$prefix.removalPath"]?.let {
                val removal = receipt("$prefix.removalPath", "$prefix.removalSha256")
                require(
                    referencedRemovalHashes.add(
                        values.getValue("$prefix.removalSha256")
                    )
                ) {
                    "Coverage removal is referenced more than once: " +
                        values.getValue("$prefix.removalSha256")
                }
                require(
                    plannedRemovalReceipts[
                        values.getValue("$prefix.removalSha256")
                    ] == removal.readText()
                ) {
                    "Coverage removal is not an exact member of the BuildSystem plan"
                }
                require(decisionRecords.isNotEmpty()) {
                    "Coverage removal has no bound decision for entry $index: $component"
                }
                val removalRecords = receiptRecords(removal)
                require(
                    removalRecords.singleValue("schema") ==
                        "build-removed-dependency-v1" &&
                        decode(removalRecords.singleValue("sourceComponent")) ==
                        component &&
                        removalRecords.singleValue("sourceArtifactSha256") ==
                        artifactHash &&
                        removalRecords.singleValue("decisionSha256") ==
                        values.getValue("$prefix.decisionSha256") &&
                        removalRecords.any { it.first == "inertRewrite" } &&
                        removalRecords.none {
                            it.first == "remainingRemovedPackageReference"
                        }
                ) {
                    "Coverage removal identity or postcondition does not match entry " +
                        "$index: $component"
                }
                removalRecords.filter { it.first == "inertRewrite" }.forEach { (_, value) ->
                    val parts = value.split('|', limit = 2)
                    require(parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}")))
                    val inertText = decode(parts[0])
                    require(sha256(inertText) == parts[1])
                    val inert = receiptRecords(inertText)
                    require(
                        inert.singleValue("schema") == "build-inert-rewrite-v1" &&
                            inert.any { it.first == "removedType" } &&
                            inert.singleValue("beforeNodeSha256")
                                .matches(Regex("[0-9a-f]{64}")) &&
                            inert.singleValue("afterOwnerSha256")
                                .matches(Regex("[0-9a-f]{64}"))
                    )
                    val postcondition = inert.singleValue("verifiedPostcondition")
                    val afterNode = inert.singleValue("afterNodeSha256")
                    require(
                        if (postcondition == "removed-signature-member") {
                            afterNode == "absent"
                        } else {
                            afterNode.matches(Regex("[0-9a-f]{64}"))
                        }
                    )
                }
                val changedSources = removalRecords
                    .filter { it.first == "changedSource" }
                    .associate { (_, value) ->
                        val parts = value.split('|')
                        require(
                            parts.size == 3 &&
                                parts[1].matches(Regex("[0-9a-f]{64}")) &&
                                parts[2].matches(Regex("[0-9a-f]{64}")) &&
                                parts[1] != parts[2]
                        )
                        decode(parts[0]) to (parts[1] to parts[2])
                    }
                require(changedSources.isNotEmpty())
                changedSources.forEach { (path, transition) ->
                    require(removalSourceTransitions.put(path, transition) == null) {
                        "Prepared source path belongs to more than one removal receipt: $path"
                    }
                    require(preparationSnapshots.sourceTransitions[path] == transition) {
                        "Removal receipt source transition is absent from preparation: $path"
                    }
                }
            }
            if (coverage == "shared-adapters") {
                require("$prefix.registrationManifestPath" in values)
            }
            if (coverage in setOf("removed-inert", "mapped-target")) {
                require("$prefix.decisionPath" in values)
            }
            if (coverage == "removed-inert") {
                require("$prefix.removalPath" in values)
            }
            if (coverage == "mapped-target") {
                require(values.keys.any { it.startsWith("$prefix.targetCoordinate.") })
                val pointerCoordinates = values.keys
                    .filter {
                        Regex(
                            """${Regex.escape(prefix)}\.targetCoordinate\.[0-9a-f]{64}"""
                        ).matches(it)
                    }
                    .associate { key ->
                        val id = key.substringAfterLast('.')
                        val coordinateText = decode(values.getValue(key))
                        require(sha256(coordinateText.toByteArray(Charsets.UTF_8)) == id)
                        val coordinate = receiptRecords(coordinateText)
                        require(coordinate.all {
                            it.first in setOf(
                                "configuration",
                                "group",
                                "artifact",
                                "version",
                                "classifier",
                                "transitive"
                            )
                        })
                        decode(coordinate.singleValue("configuration"))
                        val selector = listOfNotNull(
                            decode(coordinate.singleValue("group")),
                            decode(coordinate.singleValue("artifact")),
                            decode(coordinate.singleValue("version")),
                            coordinate.filter { it.first == "classifier" }
                                .singleOrNull()?.let { decode(it.second) }
                        ).joinToString(":")
                        coordinate.singleValue("transitive").toBooleanStrict()
                        val targetFields = values.keys.filter {
                            it.startsWith("$prefix.targetCoordinate.$id.target.")
                        }.sortedBy {
                            it.substringAfter("$prefix.targetCoordinate.$id.target.").toInt()
                        }
                        require(
                            targetFields.map {
                                it.substringAfter("$prefix.targetCoordinate.$id.target.").toInt()
                            } == (0 until targetFields.size).toList()
                        )
                        val targets = targetFields.map { decode(values.getValue(it)) }
                        require(targets.isNotEmpty())
                        require(targets.distinct().size == targets.size)
                        val expectedTargets = graphArtifacts.filter {
                            it.phase == "target" &&
                                it.component == selector
                        }.map(GraphArtifact::key).sorted()
                        require(targets == expectedTargets)
                        coordinateText to targets
                    }
                val decisionCoordinateList = decisionRecords
                    .filter { it.first == "target" }
                    .map { decode(it.second) }
                require(decisionCoordinateList.distinct().size == decisionCoordinateList.size)
                require(pointerCoordinates.keys == decisionCoordinateList.toSet())
                matchedCoordinates = pointerCoordinates
            }
            val variantHash = values.getValue("$prefix.variantSha256")
            val matching = graphArtifacts.singleOrNull {
                it.phase == phase &&
                    it.artifactSha256 == artifactHash &&
                    it.variantSha256 == variantHash &&
                    it.component == component
            } ?: error("Coverage entry $index has no exact graph artifact")
            if (decisionRecords.isNotEmpty()) {
                require(
                    decisionSourceConfigurations ==
                        matching.configurations.map {
                            it.configurationIdentity().name
                        }.toSet()
                ) {
                    "BuildSystem decision lost source configuration identity"
                }
            }
            require(covered.add(matching.phase to matching.key))
            require(
                artifactPaths.put(matching.phase to matching.key, artifact) == null &&
                    scanPaths.put(matching.phase to matching.key, scan) == null
            )
            require(
                closureScanHashes.getValue(phase).getValue(matching.key) ==
                    values.getValue("$prefix.scanSha256")
            )
            if (coverage == "verified-source-platform") {
                require(
                    matching.displayName ==
                        reviewedSourcePlatform.artifactDisplayName &&
                        matching.artifactSha256 ==
                        reviewedSourcePlatform.artifactSha256
                )
                val facts = scanRecords.filter { it.first == "fact" }
                    .map { decode(it.second) }
                    .toSet()
                require(
                    "platformIdentity=${sourcePlatformRecord.sha256}" in facts &&
                        (
                            "platformFingerprint=" +
                                reviewedSourcePlatform.sourceReceiptSha256
                            ) in facts
                )
            }
            if (coverage == "verified-vanilla") {
                require(
                    matching.displayName ==
                        reviewedTargetPlatform.artifactDisplayName &&
                        matching.artifactSha256 ==
                        reviewedTargetPlatform.artifactSha256
                )
                val facts = scanRecords.filter { it.first == "fact" }
                    .map { decode(it.second) }
                    .toSet()
                require(
                    "platformIdentity=${targetPlatformRecord.sha256}" in facts &&
                        (
                            "platformFingerprint=" +
                                reviewedTargetPlatform.sourceReceiptSha256
                            ) in facts
                )
            }
            seedEntryLines += listOf(
                phase.uppercase(),
                encode(matching.key),
                coverage,
                values.getValue("$prefix.scanSha256"),
                values["$prefix.registrationManifestSha256"].orEmpty(),
                values["$prefix.decisionSha256"].orEmpty(),
                values["$prefix.removalSha256"].orEmpty(),
                matchedCoordinates.toSortedMap().entries.joinToString(";") {
                    "${encode(it.key)}=" +
                        it.value.joinToString(",") { target -> encode(target) }
                }
            ).joinToString("|")
        }
        require(covered == graphArtifacts.map { it.phase to it.key }.toSet())
        require(referencedDecisionHashes == plannedDecisionReceipts.keys) {
            "BuildSystem decisions are not covered exactly once by the inventory"
        }
        require(referencedRemovalHashes == plannedRemovalReceipts.keys) {
            "BuildSystem removals are not covered exactly once by the inventory"
        }
        val adapterSourcePaths = plannedAdapterRegistrations
            .map(PlannedAdapterRegistrationReceipt::adapterSourcePath)
            .toSet()
        require(
            preparationSnapshots.sourceTransitions.keys ==
                removalSourceTransitions.keys +
                    adapterSourcePaths +
                    runtimeSourceTransitions.keys
        ) {
            "Prepared source transitions are not fully owned by removals, adapters, or runtime"
        }
        val expectedRunSeed = buildString {
            appendLine("sourceGraphSha256=${values.getValue("sourceGraphSha256")}")
            appendLine("targetGraphSha256=${values.getValue("targetGraphSha256")}")
            appendLine(
                "sourceClosureScanSha256=" +
                    values.getValue("sourceClosureScanSha256")
            )
            appendLine(
                "targetClosureScanSha256=" +
                    values.getValue("targetClosureScanSha256")
            )
            appendLine("targetPlanSha256=${values.getValue("targetPlanSha256")}")
            appendLine(
                "runtimeIdentitySha256=" +
                    values.getValue("runtimeIdentitySha256")
            )
            seedEntryLines.forEach { appendLine(it) }
        }
        require(runSeed.readText() == expectedRunSeed)

        val resolvedSourceGraph = resolvedGraphFromReceipt(
            sourceGraph,
            InventoryPhase.SOURCE,
            artifactPaths.filterKeys { it.first == "source" }
                .mapKeys { it.key.second },
            platformAuxiliaryPaths.getValue("source")
        )
        val resolvedTargetGraph = resolvedGraphFromReceipt(
            targetGraph,
            InventoryPhase.TARGET,
            artifactPaths.filterKeys { it.first == "target" }
                .mapKeys { it.key.second },
            platformAuxiliaryPaths.getValue("target")
        )
        val rescans = mapOf(
            "source" to scanner.scan(
                resolvedSourceGraph,
                runtimeIdentity,
                emptyList()
            ),
            "target" to scanner.scan(
                resolvedTargetGraph,
                runtimeIdentity,
                plannedAdapterRegistrations
            )
        )
        require(
            rescans.getValue("source").canonicalReceipt() ==
                sourceClosureScan.readText() &&
                rescans.getValue("target").canonicalReceipt() ==
                targetClosureScan.readText()
        ) {
            "Stored closure scan is not reproducible from the immutable artifact bytes"
        }
        rescans.forEach { (phase, closure) ->
            closure.artifacts.forEach { (artifact, rescan) ->
                val stored = scanPaths.getValue(phase to artifact.key)
                require(rescan.canonicalReceipt() == stored.readText()) {
                    "Stored semantic scan does not reproduce for $phase ${artifact.key}"
                }
            }
        }

        val actualFiles = Files.walk(runRoot).use { paths ->
            paths.peek {
                require(!Files.isSymbolicLink(it)) {
                    "Immutable legacy plant run contains a symbolic link"
                }
            }.filter { Files.isRegularFile(it) }
                .map { it.toAbsolutePath().normalize() }
                .toList()
                .toSet()
        }
        require(actualFiles == referenced) {
            "Immutable legacy plant run contains missing or unreferenced files"
        }
        return VerifiedCoverage(
            runRoot = runRoot,
            sourceGraph = resolvedSourceGraph,
            targetGraph = resolvedTargetGraph
        )
    }

    private fun matchesBuildSourceSelector(
        component: String,
        sourceSelector: String
    ): Boolean {
        val parts = component.split(':')
        if (parts.size !in 3..4 || parts.any(String::isBlank) ||
            sourceSelector.isBlank()
        ) {
            return false
        }
        val group = parts[0]
        val artifact = parts[1]
        if (':' in sourceSelector) {
            val selectorGroup = sourceSelector.substringBefore(':')
            val artifactPrefix = sourceSelector.substringAfter(':')
            return selectorGroup.isNotBlank() &&
                artifactPrefix.isNotBlank() &&
                ':' !in artifactPrefix &&
                group == selectorGroup &&
                (
                    artifact.equals(artifactPrefix, ignoreCase = true) ||
                        artifact.lowercase().startsWith(
                            artifactPrefix.lowercase() + "-"
                        )
                    )
        }
        return group == sourceSelector || group.startsWith("$sourceSelector.")
    }

    private fun productionBuildMappingSha256(): String {
        val resource = "/mappings/forge2neo/neoforge-deps.json"
        val bytes = LegacyPlantCoverageV2Verifier::class.java
            .getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("Dependency mapping resource not found: $resource")
        return sha256(bytes)
    }

    private fun sealedInventory(
        graph: ResolvedGraph,
        runRoot: Path,
        phase: InventoryPhase
    ): VerifiedSealedInventory {
        require(graph.phase == phase)
        val identity = requireNotNull(graph.platformIdentity) {
            "${phase.name.lowercase()} graph has no platform identity"
        }
        val phaseName = phase.name.lowercase()
        val realRunRoot = runRoot.toRealPath()
        val closureArtifacts = graph.artifacts.map { artifact ->
            val expectedPath = realRunRoot.resolve(
                "$phaseName/artifacts/${artifact.sha256}.jar"
            ).normalize()
            require(artifact.file.toRealPath() == expectedPath) {
                "Verified graph artifact is not at its canonical sealed path: ${artifact.file}"
            }
            LegacyMigrationArtifactInventory.Artifact(
                component = artifact.component,
                componentKind = when (artifact.componentKind) {
                    "module" -> LegacyMigrationArtifactInventory.ComponentKind.MODULE
                    "project" -> LegacyMigrationArtifactInventory.ComponentKind.PROJECT
                    "generated-platform" ->
                        LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM
                    else -> error(
                        "Unsupported legacy migration component kind: " +
                            artifact.componentKind
                    )
                },
                displayName = artifact.displayName,
                configurations = artifact.buildSystemConfigurations(),
                file = artifact.file,
                sha256 = artifact.sha256,
                resolutionConfigurations = artifact.configurations,
                configurationOrder = artifact.configurationOrder
            )
        }
        val namedIndex = graph.artifacts.indexOfFirst { artifact ->
            artifact.componentKind == "generated-platform" &&
                artifact.displayName == identity.generatedArtifactDisplayName &&
                artifact.sha256 == identity.generatedArtifactSha256
        }
        require(namedIndex >= 0) {
            "${phase.name.lowercase()} graph has no exact named platform artifact"
        }
        val auxiliaries = graph.platformAuxiliaries.associate { auxiliary ->
            val expectedPath = realRunRoot.resolve(
                "$phaseName/platform/${auxiliary.kind}-${auxiliary.sha256}.jar"
            ).normalize()
            require(auxiliary.file.toRealPath() == expectedPath) {
                "Verified platform auxiliary is not at its canonical sealed path: " +
                    auxiliary.file
            }
            auxiliary.kind to LegacyMigrationArtifactInventory.Artifact(
                component =
                    "platform-auxiliary:${identity.resolvedLoaderCoordinate}:" +
                        auxiliary.kind,
                componentKind =
                    LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY,
                displayName = auxiliary.displayName,
                configurations = emptySet(),
                file = auxiliary.file,
                sha256 = auxiliary.sha256
            )
        }
        require(auxiliaries.keys == setOf("merged", "sources"))
        return VerifiedSealedInventory(
            platformIdentity = LegacyMigrationArtifactInventory.PlatformIdentity(
                family = identity.family,
                minecraftVersion = identity.minecraftVersion,
                loaderVersion = identity.loaderVersion,
                resolvedLoaderCoordinate = identity.resolvedLoaderCoordinate
            ),
            platformArtifacts = LegacyMigrationArtifactInventory.PlatformArtifacts(
                named = closureArtifacts[namedIndex],
                merged = auxiliaries.getValue("merged"),
                sources = auxiliaries.getValue("sources")
            ),
            closureArtifacts = closureArtifacts,
            classRuntimeFeature = graph.classRuntimeFeature,
            compileClasspathConfiguration =
                graph.exactCompileClasspathConfiguration()
        )
    }

    private data class GraphArtifact(
        val phase: String,
        val key: String,
        val projectPath: String,
        val component: String,
        val classifier: String?,
        val displayName: String,
        val configurations: Set<String>,
        val variantSha256: String,
        val artifactSha256: String
    )

    private data class VerifiedCoverage(
        val runRoot: Path,
        val sourceGraph: ResolvedGraph,
        val targetGraph: ResolvedGraph
    )

    private data class VerifiedSealedInventory(
        val platformIdentity: LegacyMigrationArtifactInventory.PlatformIdentity,
        val platformArtifacts: LegacyMigrationArtifactInventory.PlatformArtifacts,
        val closureArtifacts: List<LegacyMigrationArtifactInventory.Artifact>,
        val classRuntimeFeature: Int,
        val compileClasspathConfiguration: String
    )

    private data class GraphPlatformIdentity(
        val canonicalText: String,
        val sha256: String
    )

    private data class PreparationSnapshots(
        val buildBefore: Map<String, String>,
        val buildAfter: Map<String, String>,
        val resourceBefore: Map<String, String>,
        val resourceAfter: Map<String, String>,
        val sourceBefore: Map<String, String>,
        val sourceAfter: Map<String, String>
    ) {
        val resourceTransitions: Map<String, Pair<String?, String?>>
            get() = LegacyPlantCoverageV2Verifier.snapshotTransitions(
                resourceBefore,
                resourceAfter
            )
        val sourceTransitions: Map<String, Pair<String?, String?>>
            get() = LegacyPlantCoverageV2Verifier.snapshotTransitions(
                sourceBefore,
                sourceAfter
            )
    }

    private fun graphPlatformIdentity(path: Path): GraphPlatformIdentity? {
        val records = receiptRecords(path)
        val values = records.filter { it.first == "platformIdentity" }
            .map { it.second }
        if (values.isEmpty()) return null
        val value = values.single()
        val parts = value.split('|', limit = 2)
        require(parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}")))
        val canonicalText = decode(parts[0])
        require(sha256(canonicalText) == parts[1])
        return GraphPlatformIdentity(canonicalText, parts[1])
    }

    private fun verifyBuildDeclarationTransition(
        sourceGraph: Path,
        targetGraph: Path,
        sourcePlatformIdentity: PlatformResolutionIdentity,
        targetPlatformIdentity: PlatformResolutionIdentity,
        targetPlanRecords: List<Pair<String, String>>
    ): PreparationSnapshots {
        val sourceBuildFiles = graphBuildFileHashes(sourceGraph)
        val targetBuildFiles = graphBuildFileHashes(targetGraph)
        require(
            sourcePlatformIdentity.declarationFileHashes.all { (path, hash) ->
                sourceBuildFiles[path] == hash
            } &&
                targetPlatformIdentity.declarationFileHashes.all { (path, hash) ->
                    targetBuildFiles[path] == hash
                }
        ) {
            "Platform declarations are not bound to their resolved graph build files"
        }

        val snapshots = PreparationSnapshots(
            buildBefore = targetPlanSnapshot(targetPlanRecords, "buildBefore"),
            buildAfter = targetPlanSnapshot(targetPlanRecords, "buildAfter"),
            resourceBefore = targetPlanSnapshot(targetPlanRecords, "resourceBefore"),
            resourceAfter = targetPlanSnapshot(targetPlanRecords, "resourceAfter"),
            sourceBefore = targetPlanSnapshot(targetPlanRecords, "sourceBefore"),
            sourceAfter = targetPlanSnapshot(targetPlanRecords, "sourceAfter")
        )
        require(
            snapshots.buildBefore == sourceBuildFiles &&
                snapshots.buildAfter == targetBuildFiles
        ) {
            "Resolved graph build snapshots do not match the BuildSystem preparation receipt"
        }
        require(snapshotTransitions(
            snapshots.buildBefore,
            snapshots.buildAfter
        ).isNotEmpty()) {
            "Dependency preparation did not change any build declaration"
        }
        return snapshots
    }

    private fun graphBuildFileHashes(path: Path): Map<String, String> {
        val result = linkedMapOf<String, String>()
        receiptRecords(path).filter { it.first == "buildFile" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}")))
            require(result.put(decode(parts[0]), parts[1]) == null) {
                "Duplicate resolved graph build file"
            }
        }
        require(result.isNotEmpty())
        return result
    }

    private fun targetPlanSnapshot(
        records: List<Pair<String, String>>,
        recordType: String
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        records.filter { it.first == recordType }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}")))
            val path = decode(parts[0])
            val normalized = Path.of(path).normalize()
            require(
                path.isNotBlank() &&
                    !normalized.isAbsolute &&
                    normalized.toString().replace('\\', '/') == path &&
                    !path.startsWith("../")
            )
            require(result.put(path, parts[1]) == null) {
                "Duplicate $recordType snapshot path: $path"
            }
        }
        return result
    }

    private fun snapshotTransitions(
        before: Map<String, String>,
        after: Map<String, String>
    ): Map<String, Pair<String?, String?>> =
        (before.keys + after.keys).sorted().mapNotNull { path ->
            val oldHash = before[path]
            val newHash = after[path]
            if (oldHash == newHash) null else path to (oldHash to newHash)
        }.toMap()

    private fun nestedTargetPlanReceipts(
        records: List<Pair<String, String>>,
        recordType: String
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        records.filter { it.first == recordType }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}")))
            val canonicalText = decode(parts[0])
            require(sha256(canonicalText) == parts[1])
            require(result.put(parts[1], canonicalText) == null) {
                "Duplicate $recordType receipt in target BuildSystem plan"
            }
        }
        return result
    }

    private fun resolvedGraphFromReceipt(
        path: Path,
        expectedPhase: InventoryPhase,
        artifactPaths: Map<String, Path>,
        platformAuxiliaryPaths: Map<String, Path>
    ): ResolvedGraph {
        val records = receiptRecords(path)
        require(records.singleValue("phase") == expectedPhase.name.lowercase())
        require(
            records.singleValue("resolverSchema") ==
                "modporter-legacy-plant-resolved-graph-v1"
        )
        val components = mutableMapOf<String, String>()
        val componentKinds = mutableMapOf<String, String>()
        val projectPaths = mutableMapOf<String, String>()
        val classifiers = mutableMapOf<String, String?>()
        val displayNames = mutableMapOf<String, String>()
        val sizes = mutableMapOf<String, Long>()
        val hashes = mutableMapOf<String, String>()
        val variantHashes = mutableMapOf<String, String>()
        val configurationsByArtifact =
            mutableMapOf<String, MutableSet<String>>()
        val ordersByArtifact =
            mutableMapOf<String, MutableMap<String, Int>>()
        val variantsByArtifact =
            mutableMapOf<String, MutableMap<String, String>>()

        records.filter { it.first == "projectPath" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2)
            projectPaths[decode(parts[0])] = decode(parts[1])
        }
        records.filter { it.first == "component" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2)
            components[decode(parts[0])] = decode(parts[1])
        }
        records.filter { it.first == "componentKind" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2)
            componentKinds[decode(parts[0])] = parts[1]
        }
        records.filter { it.first == "classifier" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2)
            classifiers[decode(parts[0])] =
                if (parts[1] == "absent") null else decode(parts[1])
        }
        records.filter { it.first == "displayName" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2)
            displayNames[decode(parts[0])] = decode(parts[1])
        }
        records.filter { it.first == "artifact" }.forEach { (_, value) ->
            val parts = value.split('|')
            require(parts.size == 3)
            val key = decode(parts[0])
            sizes[key] = parts[1].toLong()
            hashes[key] = parts[2]
        }
        records.filter { it.first == "variantSha256" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2)
            variantHashes[decode(parts[0])] = parts[1]
        }
        records.filter { it.first == "configuration" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            if (parts.size == 2) {
                configurationsByArtifact
                    .getOrPut(decode(parts[0])) { mutableSetOf() }
                    .add(decode(parts[1]))
            }
        }
        records.filter { it.first == "configurationOrder" }.forEach {
                (_, value) ->
            val parts = value.split('|')
            require(parts.size == 3)
            val key = decode(parts[0])
            val configuration = decode(parts[1])
            require(
                ordersByArtifact.getOrPut(key) { mutableMapOf() }
                    .put(configuration, parts[2].toInt()) == null
            )
        }
        records.filter { it.first == "variant" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2)
            val assignment = parts[1].split('=', limit = 2)
            require(assignment.size == 2)
            require(
                variantsByArtifact.getOrPut(decode(parts[0])) { mutableMapOf() }
                    .put(decode(assignment[0]), decode(assignment[1])) == null
            )
        }
        val artifactKeys = hashes.keys
        require(
            artifactPaths.keys == artifactKeys &&
                components.keys == artifactKeys &&
                componentKinds.keys == artifactKeys &&
                projectPaths.keys == artifactKeys &&
                classifiers.keys == artifactKeys &&
                displayNames.keys == artifactKeys &&
                sizes.keys == artifactKeys &&
                variantHashes.keys == artifactKeys &&
                configurationsByArtifact.keys == artifactKeys &&
                ordersByArtifact.keys == artifactKeys
        )
        val artifacts = artifactKeys.sorted().map { key ->
            ResolvedArtifact(
                projectPath = projectPaths.getValue(key),
                component = components.getValue(key),
                componentKind = componentKinds.getValue(key),
                classifier = classifiers.getValue(key),
                displayName = displayNames.getValue(key),
                configurations =
                    configurationsByArtifact.getValue(key).toSet(),
                configurationOrder =
                    ordersByArtifact.getValue(key).toMap(),
                variantAttributes =
                    variantsByArtifact[key].orEmpty().toMap(),
                file = artifactPaths.getValue(key),
                size = sizes.getValue(key),
                sha256 = hashes.getValue(key),
                variantSha256 = variantHashes.getValue(key)
            ).also {
                require(it.key == key)
            }
        }
        val buildFiles = records.filter { it.first == "buildFile" }
            .associate { (_, value) ->
                val parts = value.split('|', limit = 2)
                require(parts.size == 2)
                decode(parts[0]) to parts[1]
            }
        val configurations = records.filter { it.first == "configuration" }
            .mapNotNull { (_, value) ->
                if ('|' in value) null else decode(value)
            }
            .toSet()
        val platformIdentity = graphPlatformIdentity(path)?.let {
            parsePlatformIdentity(it.canonicalText)
        }
        val platformAuxiliaries = records.filter {
            it.first == "platformAuxiliary"
        }.map { (_, value) ->
            val parts = value.split('|')
            require(parts.size == 4)
            val kind = decode(parts[0])
            PlatformAuxiliaryArtifact(
                kind = kind,
                displayName = decode(parts[1]),
                file = platformAuxiliaryPaths.getValue(kind),
                size = parts[2].toLong(),
                sha256 = parts[3]
            )
        }
        require(platformAuxiliaryPaths.keys == platformAuxiliaries.map {
            it.kind
        }.toSet())
        val resolutionProvenance =
            LegacyPlantResolutionProvenance.fromCanonicalRecords(records)
        val graph = ResolvedGraph(
            phase = expectedPhase,
            resolverSchema = records.singleValue("resolverSchema"),
            gradleVersion = records.singleValue("gradleVersion"),
            classRuntimeFeature =
                records.singleValue("classRuntimeFeature").toInt(),
            buildFileHashes = buildFiles,
            configurations = configurations,
            artifacts = artifacts,
            edges = records.filter { it.first == "edge" }
                .map { decode(it.second) },
            unresolved = emptyList(),
            platformIdentity = platformIdentity,
            platformAuxiliaries = platformAuxiliaries,
            resolutionProvenance = resolutionProvenance
        )
        require(graph.canonicalText() == path.readText()) {
            "Resolved graph receipt does not round-trip canonically"
        }
        return graph
    }

    private fun parsePlatformIdentity(text: String): PlatformResolutionIdentity {
        val records = text.lineSequence().filter { it.isNotBlank() }.map { raw ->
            require('=' in raw)
            raw.substringBefore('=') to raw.substringAfter('=')
        }.toList()
        require(
            records.singleValue("schema") ==
                "legacy-plant-platform-resolution-v1"
        )
        val declarationFiles = records.filter {
            it.first == "declarationFile"
        }.associate { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2)
            decode(parts[0]) to parts[1]
        }
        return PlatformResolutionIdentity(
            family = records.singleValue("family"),
            minecraftVersion = decode(records.singleValue("minecraftVersion")),
            loaderVersion = decode(records.singleValue("loaderVersion")),
            resolvedLoaderCoordinate =
                decode(records.singleValue("resolvedLoaderCoordinate")),
            developmentPluginId =
                decode(records.singleValue("developmentPluginId")),
            developmentPluginVersion =
                decode(records.singleValue("developmentPluginVersion")),
            developmentPluginClass =
                decode(records.singleValue("developmentPluginClass")),
            developmentPluginSha256 =
                records.singleValue("developmentPluginSha256"),
            generatedArtifactDisplayName =
                decode(records.singleValue("generatedArtifactDisplayName")),
            generatedArtifactSha256 =
                records.singleValue("generatedArtifactSha256"),
            declarationFileHashes = declarationFiles
        )
    }

    private fun graphArtifactKeys(path: Path, phase: String): Set<GraphArtifact> {
        val records = receiptRecords(path)
        require(records.singleValue("resolverSchema") ==
            "modporter-legacy-plant-resolved-graph-v1")
        require(records.singleValue("phase") == phase)
        val projectPaths = mutableMapOf<String, String>()
        val components = mutableMapOf<String, String>()
        val classifiers = mutableMapOf<String, String?>()
        val displayNames = mutableMapOf<String, String>()
        val configurations = mutableMapOf<String, MutableSet<String>>()
        val variants = mutableMapOf<String, String>()
        val artifacts = mutableMapOf<String, String>()
        records.filter { it.first == "projectPath" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            projectPaths[decode(parts[0])] = decode(parts[1])
        }
        records.filter { it.first == "component" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            components[decode(parts[0])] = decode(parts[1])
        }
        records.filter { it.first == "classifier" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            classifiers[decode(parts[0])] =
                if (parts[1] == "absent") null else decode(parts[1])
        }
        records.filter { it.first == "displayName" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            displayNames[decode(parts[0])] = decode(parts[1])
        }
        records.filter { it.first == "configuration" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            if (parts.size == 2) {
                configurations.getOrPut(decode(parts[0])) { mutableSetOf() }
                    .add(decode(parts[1]))
            }
        }
        records.filter { it.first == "variantSha256" }.forEach { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}")))
            variants[decode(parts[0])] = parts[1]
        }
        records.filter { it.first == "artifact" }.forEach { (_, value) ->
            val parts = value.split('|')
            val key = decode(parts[0])
            require(parts.size == 3)
            require(key.matches(Regex("[0-9a-f]{64}")))
            artifacts[key] = parts[2]
        }
        require(
            projectPaths.keys == artifacts.keys &&
                components.keys == artifacts.keys &&
                classifiers.keys == artifacts.keys &&
                displayNames.keys == artifacts.keys &&
                configurations.keys == artifacts.keys &&
                variants.keys == artifacts.keys
        )
        return artifacts.map { (key, hash) ->
            require(hash.matches(Regex("[0-9a-f]{64}")))
            require(
                sha256(
                    listOf(
                        encode(projectPaths.getValue(key)),
                        encode(components.getValue(key)),
                        classifiers.getValue(key)?.let(::encode) ?: "absent",
                        encode(displayNames.getValue(key)),
                        variants.getValue(key),
                        hash
                    ).joinToString("|")
                ) == key
            ) {
                "Graph artifact key does not bind its full identity"
            }
            GraphArtifact(
                phase = phase,
                key = key,
                projectPath = projectPaths.getValue(key),
                component = components.getValue(key),
                classifier = classifiers.getValue(key),
                displayName = displayNames.getValue(key),
                configurations = configurations.getValue(key).toSet(),
                variantSha256 = variants.getValue(key),
                artifactSha256 = hash
            )
        }.toSet()
    }

    private fun closureArtifactScans(path: Path): Map<String, String> {
        val records = receiptRecords(path)
        require(records.singleValue("schema") == "legacy-plant-closure-scan-v1")
        return records.filter { it.first == "artifactScan" }.associate { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}")))
            decode(parts[0]) to parts[1]
        }
    }

    private fun parsePlannedAdapterRegistrations(
        records: List<Pair<String, String>>
    ): List<PlannedAdapterRegistrationReceipt> =
        records.filter { it.first == "adapterRegistration" }.map { (_, value) ->
            val parts = value.split('|', limit = 2)
            require(parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}")))
            val canonicalText = decode(parts[0])
            require(sha256(canonicalText) == parts[1])
            val registrationRecords = canonicalText.lineSequence()
                .filter { it.isNotBlank() }
                .map { raw ->
                    require('=' in raw)
                    raw.substringBefore('=') to raw.substringAfter('=')
                }.toList()
            PlannedAdapterRegistrationReceipt(
                targetSelector =
                    decode(registrationRecords.singleValue("targetSelector")),
                owner = decode(registrationRecords.singleValue("owner")),
                target = decode(registrationRecords.singleValue("target")),
                registrationMode =
                    registrationRecords.singleValue("registrationMode"),
                adapterKind = registrationRecords.singleValue("adapterKind"),
                adapterSourcePath =
                    decode(registrationRecords.singleValue("adapterSourcePath")),
                adapterSourceSha256 =
                    registrationRecords.singleValue("adapterSourceSha256")
            ).also {
                require(it.sha256 == parts[1])
            }
        }

    private fun strictProperties(path: Path): Map<String, String> {
        val values = linkedMapOf<String, String>()
        path.readText().lineSequence().forEachIndexed { index, raw ->
            if (raw.isBlank() || raw.startsWith("#")) return@forEachIndexed
            require('=' in raw) { "Malformed receipt line ${index + 1}" }
            val key = raw.substringBefore('=')
            val value = raw.substringAfter('=')
            require(key.matches(Regex("[A-Za-z0-9_.-]+")))
            require(values.put(key, value) == null) { "Duplicate receipt key $key" }
        }
        return values
    }

    private fun receiptRecords(path: Path): List<Pair<String, String>> =
        receiptRecords(path.readText(), path.toString())

    private fun receiptRecords(text: String): List<Pair<String, String>> =
        receiptRecords(text, "nested receipt")

    private fun receiptRecords(
        text: String,
        label: String
    ): List<Pair<String, String>> =
        text.lineSequence().filter { it.isNotBlank() }.mapIndexed { index, raw ->
            require('=' in raw) { "Malformed receipt line ${index + 1} in $label" }
            raw.substringBefore('=') to raw.substringAfter('=')
        }.toList()

    private fun List<Pair<String, String>>.singleValue(key: String): String =
        filter { it.first == key }.map { it.second }.single()

    private fun decode(value: String): String =
        Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
}

private fun JsonObject.string(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.stringOrNull(name: String): String? =
    getValue(name).let { if (it is JsonNull) null else it.jsonPrimitive.content }

private fun JsonObject.long(name: String): Long =
    getValue(name).jsonPrimitive.content.toLong()

private fun JsonObject.array(name: String): JsonArray =
    getValue(name).jsonArray

private fun JsonObject.objectValue(name: String): JsonObject =
    getValue(name).jsonObject

private fun JsonObject.canonicalJson(): String =
    entries.sortedBy { it.key }.joinToString(
        prefix = "{",
        postfix = "}",
        separator = ","
    ) { (key, value) -> "$key=$value" }

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
