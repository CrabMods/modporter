package com.modporter.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/**
 * Fail-closed evidence for the single Flywheel source-collision warning produced by Create's
 * official 1.21.1 development dependency topology.
 *
 * The production entry point deliberately accepts no caller-supplied policy or expected hashes.
 * Synthetic specifications are available only through [TestSupport] for focused unit tests.
 */
internal object FlywheelJarJarRuntimeEvidence {
    internal enum class Status {
        NOT_PRESENT,
        ACCEPTED,
        REJECTED
    }

    internal data class AuditResult(
        val status: Status,
        val warningLineIndex: Int?,
        val failures: List<String>,
        val evidenceSummary: String?
    ) {
        fun allowedIssueAt(index: Int): String? =
            evidenceSummary?.takeIf { status == Status.ACCEPTED && warningLineIndex == index }
    }

    private data class ArtifactPin(
        val fileName: String,
        val sha256: String,
        val size: Long? = null,
        val entryPayloadSha256: String? = null
    )

    private data class CandidatePin(
        val group: String,
        val artifact: String,
        val range: String,
        val artifactVersion: String,
        val path: String,
        val sha256: String,
        val isObfuscated: Boolean?
    ) {
        val coordinate: String
            get() = "$group:$artifact"
    }

    private data class CandidateRootPin(
        val artifact: ArtifactPin,
        val candidates: List<CandidatePin>
    )

    private data class PayloadPin(
        val externalFileCount: Int,
        val nestedFileCount: Int,
        val commonFileCount: Int,
        val identicalCommonFileCount: Int,
        val classFileCount: Int,
        val classPayloadSha256: String,
        val identicalCommonPayloadSha256: String,
        val differingCommonEntry: String,
        val nestedOnlyEntry: String,
        val externalVersionLine: String,
        val nestedVersionLine: String
    )

    private data class EvidenceSpec(
        val sourceFlywheel: ArtifactPin,
        val vanillin: ArtifactPin,
        val selector: ArtifactPin,
        val loader: ArtifactPin,
        val sourceFlywheelModId: String,
        val sourceFlywheelVersion: String,
        val nestedFlywheelVersion: String,
        val vanillinModId: String,
        val vanillinVersion: String,
        val vanillinFlywheelRange: String,
        val vanillinFlywheelDependencyType: String,
        val vanillinFlywheelDependencySide: String,
        val selectorVersion: String,
        val loaderVersion: String,
        val candidateRoots: List<CandidateRootPin>,
        val flywheelCandidate: CandidatePin,
        val metadataCandidateCount: Int,
        val selectedDependencyCount: Int,
        val payload: PayloadPin
    )

    private data class ParsedCandidate(
        val group: String,
        val artifact: String,
        val range: String,
        val artifactVersion: String,
        val path: String,
        val sha256: String,
        val isObfuscated: Boolean?
    ) {
        fun asPin(): CandidatePin =
            CandidatePin(group, artifact, range, artifactVersion, path, sha256, isObfuscated)
    }

    private data class ParsedMetadata(
        val candidates: List<ParsedCandidate>
    )

    private data class TomlDependency(
        val modId: String,
        val type: String,
        val versionRange: String,
        val side: String
    )

    private data class TomlFacts(
        val modId: String?,
        val version: String?,
        val dependencies: List<TomlDependency>
    )

    private data class PayloadFacts(
        val externalFiles: Map<String, ByteArray>,
        val nestedFiles: Map<String, ByteArray>,
        val commonNames: Set<String>,
        val identicalCommonNames: Set<String>,
        val differingCommonNames: Set<String>,
        val externalOnlyNames: Set<String>,
        val nestedOnlyNames: Set<String>,
        val externalClassNames: Set<String>,
        val nestedClassNames: Set<String>,
        val externalClassDigest: String,
        val nestedClassDigest: String,
        val externalIdenticalDigest: String,
        val nestedIdenticalDigest: String
    )

    private val productionSpec = EvidenceSpec(
        sourceFlywheel = ArtifactPin(
            fileName = "flywheel-neoforge-1.21.1-1.0.6.jar",
            sha256 = "31dda15c205eb596d3b3449ef03f6af7363a6cd35b3da4bfe916b304f9e5337e",
            size = 873_570
        ),
        vanillin = ArtifactPin(
            fileName = "vanillin-neoforge-1.21.1-1.1.3-41.jar",
            sha256 = "c27a5d4ea2f4dd98ee359455fe4baff87d462ab556506daeb08d0ce5e42ccf0f",
            size = 886_007
        ),
        selector = ArtifactPin(
            fileName = "JarJarSelector-0.4.1.jar",
            sha256 = "f34bab02a5e967650dc52b7cb37a200051a7078f7d1868597699d78697da2400"
        ),
        loader = ArtifactPin(
            fileName = "loader-4.0.42.jar",
            sha256 = "182fcee7ed6e2be4c815bd08d2c766d355c58ab1e227b6ffd84ed26a0f71b6d6"
        ),
        sourceFlywheelModId = "flywheel",
        sourceFlywheelVersion = "1.0.6",
        nestedFlywheelVersion = "1.0.6-41",
        vanillinModId = "vanillin",
        vanillinVersion = "1.1.3-41",
        vanillinFlywheelRange = "[1.0.0,2.0)",
        vanillinFlywheelDependencyType = "required",
        vanillinFlywheelDependencySide = "CLIENT",
        selectorVersion = "0.4.1",
        loaderVersion = "4.0.42",
        candidateRoots = listOf(
            CandidateRootPin(
                artifact = ArtifactPin(
                    "neoforge-21.1.230.jar",
                    "111b1b3136a1fdee48654cbf8d1e5062ffe75875e83b4d7f69e0a1366a85d062",
                    // ModDev regenerates this container. Both observed containers have all
                    // 10557 named entry payloads byte-identical; ZIP metadata is not a mod.
                    entryPayloadSha256 = "1c40cad92daf78fff5896c2a7b45032ae05782dc2c211e41e4194be0494c5a4e"
                ),
                candidates = listOf(
                    CandidatePin(
                        "io.github.llamalad7",
                        "mixinextras-neoforge",
                        "[0.5.3,)",
                        "0.5.3",
                        "META-INF/jarjar/mixinextras-neoforge-0.5.3.jar",
                        "9822e773bd9f42d36ed53ea3d67486207291747a7bfacc29beeb92040721bc9b",
                        false
                    ),
                    CandidatePin(
                        "net.neoforged",
                        "neoforge-coremods",
                        "[21.1.230,)",
                        "21.1.230",
                        "META-INF/jarjar/net.neoforged.neoforge-coremods-21.1.230.jar",
                        "43bff7bef65a31ee634f9c112fc67e75a1fa0c19763db6b584d71a14ec1f77d9",
                        false
                    )
                )
            ),
            CandidateRootPin(
                artifact = ArtifactPin(
                    "vanillin-neoforge-1.21.1-1.1.3-41.jar",
                    "c27a5d4ea2f4dd98ee359455fe4baff87d462ab556506daeb08d0ce5e42ccf0f",
                    886_007
                ),
                candidates = listOf(
                    CandidatePin(
                        "dev.engine-room.flywheel",
                        "neoforge",
                        "[1.0.6-41,)",
                        "1.0.6-41",
                        "META-INF/jars/flywheel-neoforge-1.21.1-1.0.6-41.jar",
                        "64dc580cfc1700b785fb3a9c1f21b283ccdc801909a4cfbb1dc25fead91c3678",
                        null
                    )
                )
            ),
            CandidateRootPin(
                artifact = ArtifactPin(
                    "cc-tweaked-1.21.1-forge-1.116.1.jar",
                    "f5e8872a2ccca38aa210c477c84d5f2ba3e8f054328b6816461af4c1f8c460a5"
                ),
                candidates = listOf(
                    CandidatePin(
                        "cc.tweaked",
                        "cobalt",
                        "[0.9.6,)",
                        "0.9.6",
                        "META-INF/jarjar/cobalt-0.9.6.jar",
                        "774034d31b10192ca5fafdb551a525c57c6553b9c5b53b17e774fd8ff2f4b300",
                        false
                    ),
                    CandidatePin(
                        "com.jcraft",
                        "jzlib",
                        "[1.1.3,)",
                        "1.1.3",
                        "META-INF/jarjar/jzlib-1.1.3.jar",
                        "89b1360f407381bf61fde411019d8cbd009ebb10cff715f3669017a031027560",
                        false
                    )
                )
            ),
            CandidateRootPin(
                artifact = ArtifactPin(
                    "journeymap-121d84332dba0910.jar",
                    "121d84332dba09100ea8c654a8c5b75bdf1257639d00aca1210c5dc31f36cb38"
                ),
                candidates = listOf(
                    CandidatePin(
                        "ar.com.hjg",
                        "pngj",
                        "[2.1.0]",
                        "2.1.0",
                        "META-INF/jarjar/pngj-2.1.0.jar",
                        "e6b762f15e4891178dddd74e4d57318f518ce278000129efa41f85410b132ccc",
                        false
                    ),
                    CandidatePin(
                        "info.journeymap",
                        "journeymap-api-neoforge",
                        "[2.0.0-1.21.1-SNAPSHOT]",
                        "2.0.0-1.21.1-SNAPSHOT",
                        "META-INF/jarjar/journeymap-api-neoforge-2.0.0-1.21.1-SNAPSHOT.jar",
                        "6a0261a3f26dee9a6b3876eef891478d96ea3df730d31accc0ef414251d43e33",
                        false
                    ),
                    CandidatePin(
                        "mysticdrew",
                        "common-networking-neoforge",
                        "[1.0.18-1.21.1]",
                        "1.0.18-1.21.1",
                        "META-INF/jarjar/common-networking-neoforge-1.0.18-1.21.1.jar",
                        "63ae6219f0c2d87e7df83c0e8bcd2890cf72186f09ac34e991588912f6fda63e",
                        false
                    )
                )
            )
        ),
        flywheelCandidate = CandidatePin(
            "dev.engine-room.flywheel",
            "neoforge",
            "[1.0.6-41,)",
            "1.0.6-41",
            "META-INF/jars/flywheel-neoforge-1.21.1-1.0.6-41.jar",
            "64dc580cfc1700b785fb3a9c1f21b283ccdc801909a4cfbb1dc25fead91c3678",
            null
        ),
        metadataCandidateCount = 8,
        selectedDependencyCount = 7,
        payload = PayloadPin(
            externalFileCount = 636,
            nestedFileCount = 637,
            commonFileCount = 636,
            identicalCommonFileCount = 635,
            classFileCount = 555,
            classPayloadSha256 = "4af00873e5c7655d2dcc2bd302593a72b6e0b2b27a3ac7cfd0f9bf2d770afd61",
            identicalCommonPayloadSha256 = "f24232eac69c1285d76b931ef612ce856819adf59c5d2def61e86ec49576491c",
            differingCommonEntry = "META-INF/neoforge.mods.toml",
            nestedOnlyEntry = "META-INF/architectury-loom-nesting-metadata.json",
            externalVersionLine = "version = \"1.0.6\"",
            nestedVersionLine = "version = \"1.0.6-41\""
        )
    )

    fun audit(projectDir: Path, runtimeLog: Path): AuditResult {
        if (!runtimeLog.isRegularFile()) {
            return AuditResult(Status.REJECTED, null, listOf("runtime log missing: $runtimeLog"), null)
        }
        return when (val decoded = StrictUtf8RuntimeLog.decode(runtimeLog)) {
            is StrictUtf8RuntimeLog.DecodeResult.Success ->
                auditWithSpec(projectDir, decoded.text.lines(), productionSpec)
            is StrictUtf8RuntimeLog.DecodeResult.Failure ->
                AuditResult(Status.REJECTED, null, listOf(decoded.note()), null)
        }
    }

    internal fun audit(projectDir: Path, lines: List<String>): AuditResult =
        auditWithSpec(projectDir, lines, productionSpec)

    private fun auditWithSpec(projectDir: Path, lines: List<String>, spec: EvidenceSpec): AuditResult {
        val selectorWarningIndices = lines.indices.filter { isJarSelectorWarning(lines[it]) }
        if (selectorWarningIndices.isEmpty()) {
            return AuditResult(Status.NOT_PRESENT, null, emptyList(), null)
        }

        val failures = mutableListOf<String>()
        if (selectorWarningIndices.size != 1) {
            failures.add("expected exactly one JarSelector WARN, found ${selectorWarningIndices.size}")
        }
        val warningIndex = selectorWarningIndices.singleOrNull()
        val warningPath = warningIndex?.let { parseExpectedWarningPath(lines[it], spec.sourceFlywheelModId) }
        if (warningIndex != null && warningPath == null) {
            failures.add("JarSelector WARN was not the exact ${spec.sourceFlywheelModId} source-collision message")
        }

        val sourceCandidates = lines.mapNotNull { pathAfter(it, "Considering mod file candidate ") }.distinctPaths()
        val loadedModFiles = lines.mapNotNull { pathBetween(it, "Loading mod file ", " with languages") }.distinctPaths()
        val skippedFiles = lines.mapNotNull { pathBetween(it, "Skipping ", " because it was already located earlier") }.distinctPaths()
        val classpathEntries = runtimeClasspathEntries(projectDir, failures)

        val sourceFlywheel = warningPath
        if (sourceFlywheel != null) {
            checkArtifact("source Flywheel", sourceFlywheel, spec.sourceFlywheel, failures)
            if (!sourceCandidates.containsSamePath(sourceFlywheel)) {
                failures.add("warning-selected Flywheel path was not a top-level runtime source candidate")
            }
            if (!loadedModFiles.containsSamePath(sourceFlywheel)) {
                failures.add("warning-selected Flywheel path was not loaded as the retained source mod")
            }
        }

        val vanillinPath = uniquePathByFileName(
            "Vanillin runtime source",
            sourceCandidates,
            spec.vanillin.fileName,
            failures
        )
        if (vanillinPath != null) {
            checkArtifact("Vanillin", vanillinPath, spec.vanillin, failures)
            if (!loadedModFiles.containsSamePath(vanillinPath)) {
                failures.add("Vanillin source path was not present in an actual Loading mod file record")
            }
        }

        val selectorPath = uniquePathByFileName(
            "JarJarSelector runtime classpath",
            classpathEntries,
            spec.selector.fileName,
            failures
        )
        if (selectorPath != null) {
            checkArtifact("JarJarSelector", selectorPath, spec.selector, failures)
            if (!skippedFiles.containsSamePath(selectorPath)) {
                failures.add("JarJarSelector classpath path was not read back by ModDiscoverer")
            }
        }
        val loaderPath = uniquePathByFileName(
            "FML loader runtime classpath",
            classpathEntries,
            spec.loader.fileName,
            failures
        )
        if (loaderPath != null) {
            checkArtifact("FML loader", loaderPath, spec.loader, failures)
            if (!skippedFiles.containsSamePath(loaderPath)) {
                failures.add("FML loader classpath path was not read back by ModDiscoverer")
            }
        }
        if (lines.none { it.contains("FML ${spec.loaderVersion} loading") }) {
            failures.add("runtime log did not report FML ${spec.loaderVersion} loading")
        }
        if (lines.none { it.contains("JarInJarDependencyLocator from ${spec.loader.fileName}") }) {
            failures.add("runtime log did not bind JarInJarDependencyLocator to ${spec.loader.fileName}")
        }

        val metadataRoots = mutableMapOf<Path, ParsedMetadata>()
        sourceCandidates.filter { it.isRegularFile() && it.fileName.toString().endsWith(".jar", true) }
            .forEach { source ->
                readMetadata(source, failures)?.let { metadataRoots[source] = it }
            }
        verifyCandidateInventory(metadataRoots, spec, failures)

        if (sourceFlywheel != null && vanillinPath != null) {
            verifyModFacts(sourceFlywheel, vanillinPath, spec, failures)
            verifyPayload(sourceFlywheel, vanillinPath, spec, failures)
        }
        verifyRuntimeLogVersions(lines, sourceFlywheel, vanillinPath, spec, failures)
        verifyDependencyCount(lines, spec, failures)

        val status = if (failures.isEmpty()) Status.ACCEPTED else Status.REJECTED
        val summary = if (status == Status.ACCEPTED) {
            "exact Flywheel/Vanillin JarJar source collision accepted: " +
                "source=${spec.sourceFlywheel.sha256.take(12)}, " +
                "nested=${spec.flywheelCandidate.sha256.take(12)}, " +
                "selector=${spec.selectorVersion}, loader=${spec.loaderVersion}, " +
                "candidates=${spec.metadataCandidateCount}->${spec.selectedDependencyCount}; " +
                "metadataRootSha256=" + metadataRoots.keys.sortedBy { it.toString() }
                    .joinToString(",") { "${it.fileName}:${sha256(it)}" }
        } else {
            null
        }
        return AuditResult(status, warningIndex, failures.distinct(), summary)
    }

    private fun verifyCandidateInventory(
        metadataRoots: Map<Path, ParsedMetadata>,
        spec: EvidenceSpec,
        failures: MutableList<String>
    ) {
        val expectedRootNames = spec.candidateRoots.map { it.artifact.fileName }.toSet()
        val unexpectedRoots = metadataRoots.keys
            .filter { it.fileName.toString() !in expectedRootNames }
            .map { it.fileName.toString() }
            .sorted()
        if (unexpectedRoots.isNotEmpty()) {
            failures.add("unexpected runtime source JarJar metadata roots: $unexpectedRoots")
        }

        var actualCandidateCount = 0
        spec.candidateRoots.forEach { expectedRoot ->
            val matching = metadataRoots.entries.filter {
                it.key.fileName.toString() == expectedRoot.artifact.fileName
            }
            if (matching.size != 1) {
                failures.add(
                    "expected exactly one JarJar metadata root ${expectedRoot.artifact.fileName}, " +
                        "found ${matching.size}"
                )
                return@forEach
            }
            val (rootPath, metadata) = matching.single()
            checkArtifact("JarJar metadata root ${expectedRoot.artifact.fileName}", rootPath, expectedRoot.artifact, failures)
            actualCandidateCount += metadata.candidates.size
            val actualPins = metadata.candidates.map { it.asPin() }
            if (actualPins != expectedRoot.candidates) {
                failures.add(
                    "JarJar metadata candidates drifted for ${expectedRoot.artifact.fileName}: " +
                        "expected=${expectedRoot.candidates.renderCandidates()} actual=${actualPins.renderCandidates()}"
                )
            }
        }
        if (actualCandidateCount != spec.metadataCandidateCount) {
            failures.add(
                "expected ${spec.metadataCandidateCount} JarJar metadata candidates, found $actualCandidateCount"
            )
        }
        val flywheelMatches = metadataRoots.values
            .flatMap { it.candidates }
            .map { it.asPin() }
            .filter { it.coordinate == spec.flywheelCandidate.coordinate }
        if (flywheelMatches != listOf(spec.flywheelCandidate)) {
            failures.add(
                "expected one exact Flywheel metadata candidate, found ${flywheelMatches.renderCandidates()}"
            )
        }
        if (spec.metadataCandidateCount - spec.selectedDependencyCount != 1) {
            failures.add("pinned candidate counts do not describe exactly one source de-duplication")
        }
    }

    private fun verifyModFacts(
        sourceFlywheel: Path,
        vanillin: Path,
        spec: EvidenceSpec,
        failures: MutableList<String>
    ) {
        val externalToml = readJarEntry(sourceFlywheel, "META-INF/neoforge.mods.toml", failures)
        val vanillinToml = readJarEntry(vanillin, "META-INF/neoforge.mods.toml", failures)
        val nestedBytes = readJarEntry(vanillin, spec.flywheelCandidate.path, failures)
        if (externalToml == null || vanillinToml == null || nestedBytes == null) return

        val externalFacts = parseTomlFacts(externalToml.decodeToString())
        if (externalFacts.modId != spec.sourceFlywheelModId ||
            externalFacts.version != spec.sourceFlywheelVersion
        ) {
            failures.add(
                "source Flywheel TOML drifted: modId=${externalFacts.modId} version=${externalFacts.version}"
            )
        }
        val vanillinFacts = parseTomlFacts(vanillinToml.decodeToString())
        if (vanillinFacts.modId != spec.vanillinModId || vanillinFacts.version != spec.vanillinVersion) {
            failures.add("Vanillin TOML drifted: modId=${vanillinFacts.modId} version=${vanillinFacts.version}")
        }
        val flywheelDependencies = vanillinFacts.dependencies.filter { it.modId == spec.sourceFlywheelModId }
        val expectedDependency = TomlDependency(
            spec.sourceFlywheelModId,
            spec.vanillinFlywheelDependencyType,
            spec.vanillinFlywheelRange,
            spec.vanillinFlywheelDependencySide
        )
        if (flywheelDependencies != listOf(expectedDependency)) {
            failures.add("Vanillin Flywheel dependency drifted: $flywheelDependencies")
        }

        val nestedFiles = readZipBytes(nestedBytes, failures, "nested Flywheel")
        val nestedToml = nestedFiles["META-INF/neoforge.mods.toml"]
        if (nestedToml == null) {
            failures.add("nested Flywheel lacks META-INF/neoforge.mods.toml")
        } else {
            val nestedFacts = parseTomlFacts(nestedToml.decodeToString())
            if (nestedFacts.modId != spec.sourceFlywheelModId ||
                nestedFacts.version != spec.nestedFlywheelVersion
            ) {
                failures.add(
                    "nested Flywheel TOML drifted: modId=${nestedFacts.modId} version=${nestedFacts.version}"
                )
            }
        }
    }

    private fun verifyPayload(
        sourceFlywheel: Path,
        vanillin: Path,
        spec: EvidenceSpec,
        failures: MutableList<String>
    ) {
        val nestedBytes = readJarEntry(vanillin, spec.flywheelCandidate.path, failures) ?: return
        val nestedSha = sha256(nestedBytes)
        if (nestedSha != spec.flywheelCandidate.sha256) {
            failures.add(
                "nested Flywheel SHA-256 drifted: expected=${spec.flywheelCandidate.sha256} actual=$nestedSha"
            )
        }
        val payload = payloadFacts(sourceFlywheel, nestedBytes, failures) ?: return
        val pin = spec.payload

        checkCount("external Flywheel file", payload.externalFiles.size, pin.externalFileCount, failures)
        checkCount("nested Flywheel file", payload.nestedFiles.size, pin.nestedFileCount, failures)
        checkCount("common Flywheel file", payload.commonNames.size, pin.commonFileCount, failures)
        checkCount(
            "identical common Flywheel file",
            payload.identicalCommonNames.size,
            pin.identicalCommonFileCount,
            failures
        )
        if (payload.differingCommonNames != setOf(pin.differingCommonEntry)) {
            failures.add("shared Flywheel payload differences drifted: ${payload.differingCommonNames.sorted()}")
        }
        if (payload.externalOnlyNames.isNotEmpty()) {
            failures.add("external-only Flywheel entries found: ${payload.externalOnlyNames.sorted()}")
        }
        if (payload.nestedOnlyNames != setOf(pin.nestedOnlyEntry)) {
            failures.add("nested-only Flywheel entries drifted: ${payload.nestedOnlyNames.sorted()}")
        }
        if (payload.externalClassNames != payload.nestedClassNames) {
            failures.add("external and nested Flywheel class name sets differ")
        }
        checkCount("Flywheel class", payload.externalClassNames.size, pin.classFileCount, failures)
        if (payload.externalClassDigest != pin.classPayloadSha256 ||
            payload.nestedClassDigest != pin.classPayloadSha256
        ) {
            failures.add(
                "Flywheel class payload digest drifted: " +
                    "external=${payload.externalClassDigest} nested=${payload.nestedClassDigest}"
            )
        }
        if (payload.externalIdenticalDigest != pin.identicalCommonPayloadSha256 ||
            payload.nestedIdenticalDigest != pin.identicalCommonPayloadSha256
        ) {
            failures.add(
                "identical shared Flywheel payload digest drifted: " +
                    "external=${payload.externalIdenticalDigest} nested=${payload.nestedIdenticalDigest}"
            )
        }

        val externalToml = payload.externalFiles[pin.differingCommonEntry]?.decodeToString()
        val nestedToml = payload.nestedFiles[pin.differingCommonEntry]?.decodeToString()
        if (externalToml == null || nestedToml == null) {
            failures.add("Flywheel version TOML was unavailable for exact line comparison")
        } else {
            val externalLines = externalToml.lines()
            val nestedLines = nestedToml.lines()
            val differences = externalLines.zip(nestedLines).filter { it.first != it.second }
            if (externalLines.size != nestedLines.size ||
                differences != listOf(pin.externalVersionLine to pin.nestedVersionLine)
            ) {
                failures.add("Flywheel TOML differs by more than the exact pinned version line")
            }
        }
    }

    private fun verifyRuntimeLogVersions(
        lines: List<String>,
        sourceFlywheel: Path?,
        vanillin: Path?,
        spec: EvidenceSpec,
        failures: MutableList<String>
    ) {
        val flywheelModListLine =
            "Flywheel ${spec.sourceFlywheelVersion} (${spec.sourceFlywheelModId})"
        val vanillinModListLine = "Vanillin ${spec.vanillinVersion} (${spec.vanillinModId})"
        if (lines.count { it.trim() == flywheelModListLine } != 1) {
            failures.add("runtime Mod List lacks one exact '$flywheelModListLine' line")
        }
        if (lines.count { it.trim() == vanillinModListLine } != 1) {
            failures.add("runtime Mod List lacks one exact '$vanillinModListLine' line")
        }
        val nestedValidLine =
            "Found valid mod file ${spec.flywheelCandidate.path.substringAfterLast('/')} " +
                "with {${spec.sourceFlywheelModId}} mods - versions {${spec.nestedFlywheelVersion}}"
        if (lines.none { it.contains(nestedValidLine) }) {
            failures.add("runtime did not inspect the exact nested Flywheel version")
        }
        if (sourceFlywheel != null &&
            lines.none {
                it.contains("Found valid mod file ${sourceFlywheel.fileName}") &&
                    it.contains(
                        "with {${spec.sourceFlywheelModId}} mods - " +
                            "versions {${spec.sourceFlywheelVersion}}"
                    )
            }
        ) {
            failures.add("runtime did not declare the exact retained Flywheel source version")
        }
        if (vanillin != null &&
            lines.none {
                it.contains("Found valid mod file ${vanillin.fileName}") &&
                    it.contains("with {${spec.vanillinModId}} mods - versions {${spec.vanillinVersion}}")
            }
        ) {
            failures.add("runtime did not declare the exact Vanillin source version")
        }
    }

    private fun verifyDependencyCount(
        lines: List<String>,
        spec: EvidenceSpec,
        failures: MutableList<String>
    ) {
        val locatorLines = lines.filter {
            it.contains("JarInJarDependencyLocator/") &&
                it.contains(" dependencies adding them to mods collection")
        }
        val expected =
            "Found ${spec.selectedDependencyCount} dependencies adding them to mods collection"
        if (locatorLines.size != 1 || !locatorLines.single().contains(expected)) {
            failures.add(
                "expected one JarInJar selected-dependency line '$expected', found " +
                    locatorLines.map { it.substringAfter("]: ", it) }
            )
        }
    }

    private fun readMetadata(path: Path, failures: MutableList<String>): ParsedMetadata? {
        val metadataBytes = readJarEntry(path, "META-INF/jarjar/metadata.json", null) ?: return null
        return parseMetadata(path, metadataBytes, failures)
    }

    private fun parseMetadata(
        root: Path,
        metadataBytes: ByteArray,
        failures: MutableList<String>
    ): ParsedMetadata? =
        runCatching {
            val rootJson = Json.parseToJsonElement(metadataBytes.decodeToString()).jsonObject
            require(rootJson.keys == setOf("jars")) {
                "metadata root keys were ${rootJson.keys}"
            }
            val candidates = rootJson.getValue("jars").jsonArray.map { element ->
                val candidate = element.jsonObject
                val identifier = candidate.getValue("identifier").jsonObject
                val version = candidate.getValue("version").jsonObject
                val isObfuscated = candidate["isObfuscated"]?.jsonPrimitive?.booleanOrNull
                val expectedKeys = if (candidate.containsKey("isObfuscated")) {
                    setOf("identifier", "version", "path", "isObfuscated")
                } else {
                    setOf("identifier", "version", "path")
                }
                require(candidate.keys == expectedKeys) {
                    "candidate keys were ${candidate.keys}"
                }
                require(identifier.keys == setOf("group", "artifact")) {
                    "identifier keys were ${identifier.keys}"
                }
                require(version.keys == setOf("range", "artifactVersion")) {
                    "version keys were ${version.keys}"
                }
                val nestedPath = candidate.getValue("path").jsonPrimitive.content
                val nestedBytes = readJarEntry(root, nestedPath, failures)
                    ?: error("nested candidate missing: $nestedPath")
                ParsedCandidate(
                    group = identifier.getValue("group").jsonPrimitive.content,
                    artifact = identifier.getValue("artifact").jsonPrimitive.content,
                    range = version.getValue("range").jsonPrimitive.content,
                    artifactVersion = version.getValue("artifactVersion").jsonPrimitive.content,
                    path = nestedPath,
                    sha256 = sha256(nestedBytes),
                    isObfuscated = isObfuscated
                )
            }
            ParsedMetadata(candidates)
        }.getOrElse {
            failures.add("invalid JarJar metadata in ${root.fileName}: ${it.message}")
            null
        }

    private fun payloadFacts(
        sourceFlywheel: Path,
        nestedBytes: ByteArray,
        failures: MutableList<String>
    ): PayloadFacts? =
        runCatching {
            val externalFiles = readZipFile(sourceFlywheel)
            val nestedFiles = readZipBytes(nestedBytes, failures, "nested Flywheel")
            val common = externalFiles.keys intersect nestedFiles.keys
            val identical = common.filterTo(linkedSetOf()) {
                externalFiles.getValue(it).contentEquals(nestedFiles.getValue(it))
            }
            val differing = common - identical
            val externalClasses = externalFiles.keys.filterTo(linkedSetOf()) { it.endsWith(".class") }
            val nestedClasses = nestedFiles.keys.filterTo(linkedSetOf()) { it.endsWith(".class") }
            PayloadFacts(
                externalFiles = externalFiles,
                nestedFiles = nestedFiles,
                commonNames = common,
                identicalCommonNames = identical,
                differingCommonNames = differing,
                externalOnlyNames = externalFiles.keys - nestedFiles.keys,
                nestedOnlyNames = nestedFiles.keys - externalFiles.keys,
                externalClassNames = externalClasses,
                nestedClassNames = nestedClasses,
                externalClassDigest = entrySetDigest(externalFiles, externalClasses),
                nestedClassDigest = entrySetDigest(nestedFiles, nestedClasses),
                externalIdenticalDigest = entrySetDigest(externalFiles, identical),
                nestedIdenticalDigest = entrySetDigest(nestedFiles, identical)
            )
        }.getOrElse {
            failures.add("could not compare Flywheel payloads: ${it.message}")
            null
        }

    private fun readZipFile(path: Path): Map<String, ByteArray> =
        ZipFile(path.toFile()).use { zip ->
            buildMap {
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        require(put(entry.name, zip.getInputStream(entry).use { it.readBytes() }) == null) {
                            "duplicate ZIP entry ${entry.name}"
                        }
                    }
                }
            }
        }

    private fun readZipBytes(
        bytes: ByteArray,
        failures: MutableList<String>,
        label: String
    ): Map<String, ByteArray> =
        runCatching {
            buildMap {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) {
                            require(put(entry.name, zip.readBytes()) == null) {
                                "duplicate ZIP entry ${entry.name}"
                            }
                        }
                    }
                }
            }
        }.getOrElse {
            failures.add("could not read $label ZIP: ${it.message}")
            emptyMap()
        }

    private fun readJarEntry(
        jar: Path,
        entryName: String,
        failures: MutableList<String>?
    ): ByteArray? =
        runCatching {
            ZipFile(jar.toFile()).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrElse {
            failures?.add("could not read $entryName from ${jar.fileName}: ${it.message}")
            null
        }

    private fun parseTomlFacts(text: String): TomlFacts {
        var section = ""
        var modId: String? = null
        var version: String? = null
        var dependencyOwner: String? = null
        var dependencyValues = linkedMapOf<String, String>()
        val dependencies = mutableListOf<TomlDependency>()

        fun flushDependency() {
            if (dependencyOwner == null) return
            val id = dependencyValues["modId"]
            val type = dependencyValues["type"]
            val range = dependencyValues["versionRange"]
            val side = dependencyValues["side"]
            if (id != null && type != null && range != null && side != null) {
                dependencies.add(TomlDependency(id, type, range, side))
            }
            dependencyOwner = null
            dependencyValues = linkedMapOf()
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[[") && line.endsWith("]]")) {
                flushDependency()
                section = line.removePrefix("[[").removeSuffix("]]").trim()
                if (section.startsWith("dependencies.")) {
                    dependencyOwner = section.substringAfter("dependencies.")
                }
                return@forEach
            }
            val separator = line.indexOf('=')
            if (separator < 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().unquoteToml()
            when {
                section == "mods" && key == "modId" && modId == null -> modId = value
                section == "mods" && key == "version" && version == null -> version = value
                dependencyOwner != null -> dependencyValues[key] = value
            }
        }
        flushDependency()
        return TomlFacts(modId, version, dependencies)
    }

    private fun String.unquoteToml(): String =
        if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))) {
            substring(1, length - 1)
        } else {
            this
        }

    private fun runtimeClasspathEntries(
        projectDir: Path,
        failures: MutableList<String>
    ): List<Path> {
        val moddev = projectDir.resolve("build/moddev")
        if (!moddev.exists()) {
            failures.add("ModDev runtime classpath directory missing: $moddev")
            return emptyList()
        }
        val classpathFiles = mutableListOf<Path>()
        Files.walk(moddev).use { files ->
            files.filter {
                Files.isRegularFile(it) && it.fileName.toString().endsWith("Classpath.txt")
            }.forEach { classpathFiles.add(it) }
        }
        if (classpathFiles.isEmpty()) {
            failures.add("no ModDev *Classpath.txt runtime evidence found under $moddev")
            return emptyList()
        }
        val entries = mutableListOf<Path>()
        classpathFiles.sorted().forEach { classpathFile ->
            classpathFile.readLines().forEach { line ->
                line.split(File.pathSeparatorChar).forEach { raw ->
                    pathOf(raw.trim().trim('"'))?.let(entries::add)
                }
            }
        }
        return entries.distinctPaths()
    }

    private fun isJarSelectorWarning(line: String): Boolean =
        (line.contains("/WARN]") || line.startsWith("WARN ")) &&
            line.contains("JarSelector/]")

    private fun parseExpectedWarningPath(line: String, sourceModId: String): Path? {
        val prefix =
            "Attempted to select a dependency jar for JarJar which was passed in as source: " +
                "$sourceModId. Using Mod File: "
        val marker = "JarSelector/]: "
        val message = line.substringAfter(marker, missingDelimiterValue = "")
        if (!message.startsWith(prefix)) return null
        val pathText = message.removePrefix(prefix)
        if (pathText.isBlank() || pathText.contains('\n') || pathText.contains('\r')) return null
        return pathOf(pathText)
    }

    private fun pathAfter(line: String, marker: String): Path? {
        val raw = line.substringAfter(marker, missingDelimiterValue = "")
        return if (raw.isBlank()) null else pathOf(raw)
    }

    private fun pathBetween(line: String, prefix: String, suffix: String): Path? {
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val valueStart = start + prefix.length
        val end = line.indexOf(suffix, valueStart)
        if (end < 0) return null
        return pathOf(line.substring(valueStart, end))
    }

    private fun pathOf(raw: String): Path? =
        runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull()

    private fun List<Path>.distinctPaths(): List<Path> =
        distinctBy { normalizedPathKey(it) }

    private fun List<Path>.containsSamePath(path: Path): Boolean =
        any { normalizedPathKey(it) == normalizedPathKey(path) }

    private fun normalizedPathKey(path: Path): String {
        val text = path.toAbsolutePath().normalize().toString()
        return if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) {
            text.lowercase(Locale.ROOT)
        } else {
            text
        }
    }

    private fun uniquePathByFileName(
        label: String,
        paths: List<Path>,
        fileName: String,
        failures: MutableList<String>
    ): Path? {
        val matches = paths.filter { it.fileName?.toString() == fileName }.distinctPaths()
        if (matches.size != 1) {
            failures.add("expected exactly one $label path named $fileName, found ${matches.size}")
            return null
        }
        return matches.single()
    }

    private fun checkArtifact(
        label: String,
        path: Path,
        pin: ArtifactPin,
        failures: MutableList<String>
    ) {
        if (!path.isRegularFile()) {
            failures.add("$label artifact missing: $path")
            return
        }
        if (path.fileName.toString() != pin.fileName) {
            failures.add("$label filename drifted: expected=${pin.fileName} actual=${path.fileName}")
        }
        pin.size?.let { expectedSize ->
            val actualSize = Files.size(path)
            if (actualSize != expectedSize) {
                failures.add("$label size drifted: expected=$expectedSize actual=$actualSize")
            }
        }
        val actualSha = sha256(path)
        if (pin.entryPayloadSha256 != null) {
            val actualPayloadSha = runCatching {
                val entries = readZipFile(path)
                entrySetDigest(entries, entries.keys)
            }.getOrElse {
                failures.add("$label ZIP payload could not be verified: ${it.message}")
                null
            }
            if (actualPayloadSha != pin.entryPayloadSha256) {
                failures.add(
                    "$label entry payload SHA-256 drifted: " +
                        "expected=${pin.entryPayloadSha256} actual=$actualPayloadSha; container=$actualSha"
                )
            }
        } else if (actualSha != pin.sha256) {
            failures.add("$label SHA-256 drifted: expected=${pin.sha256} actual=$actualSha")
        }
    }

    private fun checkCount(
        label: String,
        actual: Int,
        expected: Int,
        failures: MutableList<String>
    ) {
        if (actual != expected) failures.add("$label count drifted: expected=$expected actual=$actual")
    }

    private fun entrySetDigest(files: Map<String, ByteArray>, names: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        names.sorted().forEach { name ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(MessageDigest.getInstance("SHA-256").digest(files.getValue(name)))
        }
        return digest.digest().toHex()
    }

    private fun sha256(path: Path): String =
        Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().toHex()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun List<CandidatePin>.renderCandidates(): String =
        joinToString(prefix = "[", postfix = "]") {
            "${it.coordinate}:${it.range}:${it.artifactVersion}:${it.path}:${it.sha256.take(12)}:" +
                "${it.isObfuscated}"
        }

    /**
     * Pure fixture entry points. The production [audit] method never calls a caller-provided spec.
     */
    internal object TestSupport {
        internal class PreparedSpec internal constructor(
            private val runner: (Path, List<String>) -> AuditResult
        ) {
            internal fun audit(projectDir: Path, lines: List<String>): AuditResult =
                runner(projectDir, lines)
        }

        internal data class ProductionPins(
            val sourceFlywheelSha256: String,
            val vanillinSha256: String,
            val nestedFlywheelSha256: String,
            val selectorSha256: String,
            val loaderSha256: String,
            val classPayloadSha256: String,
            val identicalCommonPayloadSha256: String,
            val rootEntryPayloadSha256: Map<String, String>,
            val candidateInventory: List<String>
        )

        internal fun productionPins(): ProductionPins =
            ProductionPins(
                sourceFlywheelSha256 = productionSpec.sourceFlywheel.sha256,
                vanillinSha256 = productionSpec.vanillin.sha256,
                nestedFlywheelSha256 = productionSpec.flywheelCandidate.sha256,
                selectorSha256 = productionSpec.selector.sha256,
                loaderSha256 = productionSpec.loader.sha256,
                classPayloadSha256 = productionSpec.payload.classPayloadSha256,
                identicalCommonPayloadSha256 = productionSpec.payload.identicalCommonPayloadSha256,
                rootEntryPayloadSha256 = productionSpec.candidateRoots.mapNotNull { root ->
                    root.artifact.entryPayloadSha256?.let { root.artifact.fileName to it }
                }.toMap(),
                candidateInventory = productionSpec.candidateRoots.flatMap { root ->
                    root.candidates.map { candidate ->
                        "${root.artifact.fileName}|${root.artifact.sha256}|${candidate.coordinate}|" +
                            "${candidate.range}|${candidate.artifactVersion}|${candidate.path}|" +
                            "${candidate.sha256}|${candidate.isObfuscated}"
                    }
                }
            )

        internal fun prepareSyntheticSpec(
            sourceFlywheel: Path,
            vanillin: Path,
            selector: Path,
            loader: Path,
            candidateRoots: List<Path>,
            selectorVersion: String,
            loaderVersion: String,
            selectedDependencyCount: Int,
            entryPayloadRootNames: Set<String> = emptySet()
        ): PreparedSpec {
            val failures = mutableListOf<String>()
            val sourceToml = requireNotNull(readJarEntry(sourceFlywheel, "META-INF/neoforge.mods.toml", failures))
            val vanillinToml = requireNotNull(readJarEntry(vanillin, "META-INF/neoforge.mods.toml", failures))
            val sourceFacts = parseTomlFacts(sourceToml.decodeToString())
            val vanillinFacts = parseTomlFacts(vanillinToml.decodeToString())
            val rootPins = candidateRoots.map { root ->
                val metadata = requireNotNull(readMetadata(root, failures))
                CandidateRootPin(
                    ArtifactPin(
                        root.fileName.toString(), sha256(root), Files.size(root).takeUnless {
                            root.fileName.toString() in entryPayloadRootNames
                        },
                        entryPayloadSha256 = if (root.fileName.toString() in entryPayloadRootNames) {
                            val entries = readZipFile(root)
                            entrySetDigest(entries, entries.keys)
                        } else null
                    ),
                    metadata.candidates.map { it.asPin() }
                )
            }
            require(failures.isEmpty()) { failures.joinToString("; ") }
            val flywheelCandidate = rootPins
                .flatMap { it.candidates }
                .single { it.group.contains("flywheel") }
            val nestedBytes = requireNotNull(readJarEntry(vanillin, flywheelCandidate.path, failures))
            val nestedFiles = readZipBytes(nestedBytes, failures, "synthetic nested Flywheel")
            val nestedToml = requireNotNull(nestedFiles["META-INF/neoforge.mods.toml"])
            val nestedFacts = parseTomlFacts(nestedToml.decodeToString())
            val payload = requireNotNull(payloadFacts(sourceFlywheel, nestedBytes, failures))
            val tomlDifferences = payload.externalFiles.getValue("META-INF/neoforge.mods.toml")
                .decodeToString()
                .lines()
                .zip(
                    payload.nestedFiles.getValue("META-INF/neoforge.mods.toml")
                        .decodeToString()
                        .lines()
                )
                .filter { it.first != it.second }
            require(tomlDifferences.size == 1)
            val dependency = vanillinFacts.dependencies.single { it.modId == sourceFacts.modId }
            val prepared = EvidenceSpec(
                sourceFlywheel = ArtifactPin(
                    sourceFlywheel.fileName.toString(),
                    sha256(sourceFlywheel),
                    Files.size(sourceFlywheel)
                ),
                vanillin = ArtifactPin(vanillin.fileName.toString(), sha256(vanillin), Files.size(vanillin)),
                selector = ArtifactPin(selector.fileName.toString(), sha256(selector), Files.size(selector)),
                loader = ArtifactPin(loader.fileName.toString(), sha256(loader), Files.size(loader)),
                sourceFlywheelModId = requireNotNull(sourceFacts.modId),
                sourceFlywheelVersion = requireNotNull(sourceFacts.version),
                nestedFlywheelVersion = requireNotNull(nestedFacts.version),
                vanillinModId = requireNotNull(vanillinFacts.modId),
                vanillinVersion = requireNotNull(vanillinFacts.version),
                vanillinFlywheelRange = dependency.versionRange,
                vanillinFlywheelDependencyType = dependency.type,
                vanillinFlywheelDependencySide = dependency.side,
                selectorVersion = selectorVersion,
                loaderVersion = loaderVersion,
                candidateRoots = rootPins,
                flywheelCandidate = flywheelCandidate,
                metadataCandidateCount = rootPins.sumOf { it.candidates.size },
                selectedDependencyCount = selectedDependencyCount,
                payload = PayloadPin(
                    externalFileCount = payload.externalFiles.size,
                    nestedFileCount = payload.nestedFiles.size,
                    commonFileCount = payload.commonNames.size,
                    identicalCommonFileCount = payload.identicalCommonNames.size,
                    classFileCount = payload.externalClassNames.size,
                    classPayloadSha256 = payload.externalClassDigest,
                    identicalCommonPayloadSha256 = payload.externalIdenticalDigest,
                    differingCommonEntry = payload.differingCommonNames.single(),
                    nestedOnlyEntry = payload.nestedOnlyNames.single(),
                    externalVersionLine = tomlDifferences.single().first,
                    nestedVersionLine = tomlDifferences.single().second
                )
            )
            return PreparedSpec { projectDir, lines -> auditWithSpec(projectDir, lines, prepared) }
        }

        internal fun audit(
            projectDir: Path,
            runtimeLog: Path,
            preparedSpec: PreparedSpec
        ): AuditResult =
            if (!runtimeLog.isRegularFile()) {
                AuditResult(Status.REJECTED, null, listOf("runtime log missing: $runtimeLog"), null)
            } else {
                when (val decoded = StrictUtf8RuntimeLog.decode(runtimeLog)) {
                    is StrictUtf8RuntimeLog.DecodeResult.Success ->
                        preparedSpec.audit(projectDir, decoded.text.lines())
                    is StrictUtf8RuntimeLog.DecodeResult.Failure ->
                        AuditResult(Status.REJECTED, null, listOf(decoded.note()), null)
                }
            }
    }
}
