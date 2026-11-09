package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.PassResult
import com.modporter.core.transforms.build.BuildDependencyDecisionKind
import com.modporter.core.transforms.build.BuildDependencyDecisionReceipt
import com.modporter.core.transforms.build.BuildDependencyPreparationReceipt
import com.modporter.core.transforms.build.BuildRemovedDependencyReceipt
import com.modporter.core.transforms.build.BuildResolvedSourceDependency
import com.modporter.core.transforms.build.BuildTargetCoordinate
import com.modporter.core.transforms.shared.LegacyPlantRuntimeResources
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/**
 * The Gradle resolver, bytecode scanner, and BuildSystem decision receipt are explicit
 * dependencies so tests can supply deterministic fixtures.
 */
internal object LegacyPlantDependencyInventoryProducer {
    private const val OUTPUT = ".modporter/legacy-plant-external-coverage.properties"
    private const val INVENTORY_ROOT = ".modporter/legacy-plant-inventory"

    fun produce(
        ownerProject: Path,
        leafProject: Path,
        resolver: LegacyPlantGradleResolver,
        scanner: LegacyPlantBytecodeScanner,
        buildPlanner: LegacyPlantTargetBuildPlanner,
        runtimeIdentity: CanonicalRuntimeIdentity,
        dryRun: Boolean
    ): PassResult {
        runtimeIdentity.verify()
        val owner = ownerProject.toAbsolutePath().normalize()
        val leaf = leafProject.toAbsolutePath().normalize()
        require(leaf.startsWith(owner)) {
            "Legacy plant inventory leaf project is outside its owner: $leaf"
        }
        val leafRelative = owner.relativize(leaf)
        // Short on purpose: the staged target's nested Gradle starts java processes whose working
        // directory (<stage>/target/.modporter/legacy-plant-compat) must stay under Windows'
        // 260-character CreateProcess limit even below a 68-character benchmark run id.
        val owned = owner.resolve(".modporter/tmp/lp-${shortStageId()}")
        check(!owned.exists()) { "Legacy plant inventory stage already exists: $owned" }
        val sourceStage = owned.resolve("source")
        val targetStage = owned.resolve("target")
        val targetLeafStage = targetStage.resolve(leafRelative.toString())
        var completed = false
        try {
            copyProject(owner, sourceStage)
            copyProject(owner, targetStage)

            val sourceGraph = resolver.resolve(sourceStage, InventoryPhase.SOURCE)
            require(sourceGraph.unresolved.isEmpty()) {
                "Source dependency closure is unresolved: ${sourceGraph.unresolved.sorted()}"
            }

            val targetPlan = buildPlanner.prepare(targetLeafStage, sourceGraph)
            require(targetPlan.result.errors.isEmpty()) {
                "Target dependency-only BuildSystemPass failed: " +
                    targetPlan.result.errors.joinToString()
            }
            val targetGraph = resolver.resolve(targetStage, InventoryPhase.TARGET)
            require(targetGraph.unresolved.isEmpty()) {
                "Target dependency closure is unresolved: ${targetGraph.unresolved.sorted()}"
            }

            val sourceClosureScan = scanner.scan(
                sourceGraph,
                runtimeIdentity,
                emptyList()
            )
            val targetClosureScan = scanner.scan(
                targetGraph,
                runtimeIdentity,
                targetPlan.adapterRegistrations
            )
            val classification = classify(
                sourceGraph,
                targetGraph,
                sourceClosureScan,
                targetClosureScan,
                targetPlan,
                sourceClosureScan.blockers + targetClosureScan.blockers,
                runtimeIdentity
            )
            require(classification.complete) {
                classification.blockers.sorted().joinToString(
                    prefix = "Legacy plant inventory is incomplete: ",
                    separator = "; "
                )
            }

            val planned = materialize(
                owner,
                sourceGraph,
                targetGraph,
                sourceClosureScan,
                targetClosureScan,
                targetPlan,
                classification,
                runtimeIdentity
            )
            val changes = plannedChanges(planned)
            if (!dryRun) {
                commitMaterialization(owner, planned)
                publishInventory(owner, leaf, planned, sourceGraph, targetGraph)
            }
            completed = true
            return PassResult("Legacy plant dependency inventory", changes)
        } catch (failure: Throwable) {
            val receipt = owned.resolve("legacy-plant-inventory-failure.txt")
            runCatching {
                receipt.parent.createDirectories()
                Files.writeString(
                    receipt,
                    buildString {
                        appendLine("schema=legacy-plant-inventory-failure-v1")
                        appendLine("ownerProject=$owner")
                        appendLine("leafProject=$leaf")
                        appendLine("failureType=${failure::class.qualifiedName}")
                        appendLine("message=${failure.message.orEmpty()}")
                        appendLine()
                        append(failure.stackTraceToString())
                    }
                )
                forceFile(receipt)
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw IllegalStateException(
                "Legacy plant inventory failed; preserved diagnostic stage at $owned",
                failure
            )
        } finally {
            if (completed) {
                deleteOwnedTree(owned, owner.resolve(".modporter/tmp"))
            }
        }
    }

    private fun classify(
        sourceGraph: ResolvedGraph,
        targetGraph: ResolvedGraph,
        sourceClosureScan: ClosurePlantScan,
        targetClosureScan: ClosurePlantScan,
        targetPlan: TargetBuildPlanReceipt,
        scanBlockers: List<String>,
        runtimeIdentity: CanonicalRuntimeIdentity
    ): ClassificationResult {
        val sourceScans = sourceClosureScan.artifacts
        val targetScans = targetClosureScan.artifacts
        val entries = mutableListOf<CoverageEntry>()
        val blockers = scanBlockers.toMutableList()

        fun exactDecision(
            artifact: ResolvedArtifact
        ): BuildDependencyDecisionReceipt? {
            val matches = targetPlan.decisions.filter {
                it.source.component == artifact.component &&
                    it.source.artifactSha256 == artifact.sha256 &&
                    it.source.configurations == artifact.buildSystemConfigurations()
            }
            if (matches.size > 1) {
                blockers +=
                    "source ${artifact.component} matches more than one exact mapping decision"
            }
            return matches.singleOrNull()
        }

        fun exactRemoval(
            artifact: ResolvedArtifact,
            decision: BuildDependencyDecisionReceipt?
        ): BuildRemovedDependencyReceipt? {
            if (decision == null) return null
            val matches = targetPlan.removals.filter {
                it.sourceComponent == artifact.component &&
                    it.sourceArtifactSha256 == artifact.sha256 &&
                    it.decisionSha256 == decision.sha256
            }
            if (matches.size > 1) {
                blockers +=
                    "source ${artifact.component} matches more than one exact removal receipt"
            }
            return matches.singleOrNull()
        }

        if (sourceScans.keys != sourceGraph.artifacts.toSet()) {
            blockers += "source closure scan does not cover every resolved artifact exactly once"
        }
        if (targetScans.keys != targetGraph.artifacts.toSet()) {
            blockers += "target closure scan does not cover every resolved artifact exactly once"
        }

        for (artifact in targetGraph.artifacts.sortedBy { it.component }) {
            val scan = targetScans[artifact] ?: continue
            when {
                scan.isCanonicalRuntime ->
                    entries += CoverageEntry(InventoryPhase.TARGET, artifact, "canonical-runtime")
                scan.isVerifiedTargetVanilla ->
                    entries += CoverageEntry(InventoryPhase.TARGET, artifact, "verified-vanilla")
                scan.reviewedAdapterProfile?.let { profile ->
                    targetPlan.reviewedAdapterProfiles.singleOrNull { it.sha256 == profile.sha256 }
                } != null ->
                    entries += CoverageEntry(
                        InventoryPhase.TARGET,
                        artifact,
                        "reviewed-adapters"
                    )
                !scan.hasPlantSurface ->
                    entries += CoverageEntry(InventoryPhase.TARGET, artifact, "no-surface")
                scan.allRelevantClassesImplementCanonicalAbi ->
                    entries += CoverageEntry(InventoryPhase.TARGET, artifact, "canonical-abi")
                scan.adapterManifest?.isValidFor(artifact, runtimeIdentity) == true ->
                    entries += CoverageEntry(
                        InventoryPhase.TARGET,
                        artifact,
                        "shared-adapters"
                    )
                else -> blockers +=
                    "target ${artifact.component} has unadapted plant surface ${scan.relevantClasses}"
            }
        }

        for (artifact in sourceGraph.artifacts.sortedBy { it.component }) {
            val scan = sourceScans[artifact] ?: continue
            val decision = exactDecision(artifact)
            val removal = exactRemoval(artifact, decision)
            if (scan.isVerifiedSourcePlatform) {
                entries += CoverageEntry(
                    InventoryPhase.SOURCE,
                    artifact,
                    "verified-source-platform",
                    decision = decision,
                    removal = removal
                )
                continue
            }
            if (!scan.hasPlantSurface) {
                entries += CoverageEntry(
                    InventoryPhase.SOURCE,
                    artifact,
                    "no-surface",
                    decision = decision,
                    removal = removal
                )
                continue
            }
            when {
                decision == null ->
                    blockers += "source ${artifact.component} has plant surface but no mapping decision"
                decision.kind == BuildDependencyDecisionKind.REMOVE -> {
                    val targetComponents = targetGraph.artifacts.filter { target ->
                        target.component == decision.sourceSelector ||
                            target.component.startsWith("${decision.sourceSelector}:") ||
                            decision.targetCoordinates.any {
                                target.matchesSelector(it.selector)
                            }
                    }
                    val targetClasses = targetClosureScan.definedClasses.filter { className ->
                        decision.packagePrefixes.any { prefix ->
                            className == prefix.trimEnd('.') ||
                                className.startsWith(prefix.trimEnd('.') + ".")
                        }
                    }
                    val changedSourceMatches = removal?.changedSourceHashes?.all {
                        targetPlan.preparation.changedSourceFiles.singleOrNull { transition ->
                            transition.path == it.key
                        }?.let { transition ->
                            transition.beforeSha256 == it.value.first &&
                                transition.afterSha256 == it.value.second
                        } == true
                    } == true
                    if (removal == null ||
                        removal.remainingRemovedPackageReferences.isNotEmpty() ||
                        removal.packagePrefixes.toSet() != decision.packagePrefixes.toSet() ||
                        removal.inertRewrites.isEmpty() ||
                        !changedSourceMatches ||
                        targetComponents.isNotEmpty() ||
                        targetClasses.isNotEmpty()
                    ) {
                        blockers +=
                            "source ${artifact.component} has no exact inert removal and target-absence proof"
                    } else {
                        entries += CoverageEntry(
                            InventoryPhase.SOURCE,
                            artifact,
                            "removed-inert",
                            decision = decision,
                            removal = removal
                        )
                    }
                }
                decision.kind == BuildDependencyDecisionKind.MAPPED -> {
                    val coordinateTargets = decision.targetCoordinates.associateWith { coordinate ->
                        targetGraph.artifacts.filter {
                            it.matchesSelector(coordinate.selector)
                        }
                    }
                    val missingCoordinates = coordinateTargets.filterValues { it.isEmpty() }.keys
                    if (decision.targetCoordinates.isEmpty() || missingCoordinates.isNotEmpty()) {
                        blockers +=
                            "source ${artifact.component} has unresolved target coordinates " +
                                missingCoordinates.ifEmpty {
                                    decision.targetCoordinates.toSet()
                                }.map(BuildTargetCoordinate::canonicalText)
                        continue
                    }
                    val targets =
                        coordinateTargets.values.flatten().distinctBy(ResolvedArtifact::key)
                    val admittedTargetCoverage = setOf(
                        "verified-vanilla",
                        "canonical-runtime",
                        "canonical-abi",
                        "shared-adapters",
                        "reviewed-adapters"
                    )
                    val targetEntries = targets.mapNotNull { target ->
                        entries.singleOrNull {
                            it.phase == InventoryPhase.TARGET && it.artifact == target
                        }
                    }
                    if (targetEntries.size != targets.size ||
                        targetEntries.any { it.coverage !in admittedTargetCoverage }
                    ) {
                        blockers +=
                            "source ${artifact.component} maps to a target without admitted legacy " +
                                "semantics: ${targets.map { it.component }}"
                        continue
                    }
                    val reviewedProfile = scan.reviewedAdapterProfile
                    if (reviewedProfile != null) {
                        val plannedProfile = targetPlan.reviewedAdapterProfiles.singleOrNull {
                            it.sha256 == reviewedProfile.sha256
                        }
                        val reviewedTargets = targets.filter {
                            it.component == reviewedProfile.targetComponent &&
                                it.sha256 == reviewedProfile.targetArtifactSha256
                        }
                        val targetProfileMatches = reviewedTargets.size == 1 &&
                            targetScans.getValue(reviewedTargets.single())
                                .reviewedAdapterProfile?.sha256 == reviewedProfile.sha256 &&
                            targetEntries.single { it.artifact == reviewedTargets.single() }.coverage ==
                                "reviewed-adapters"
                        if (plannedProfile == null ||
                            !reviewedProfile.matchesDecision(decision) ||
                            !targetProfileMatches
                        ) {
                            blockers +=
                                "source ${artifact.component} lacks whole-closure reviewed profile " +
                                    "coverage for both producers and MushroomRot consumer"
                            continue
                        }
                    }
                    entries += CoverageEntry(
                        InventoryPhase.SOURCE,
                        artifact,
                        "mapped-target",
                        decision = decision,
                        matchedTargetCoordinates =
                            coordinateTargets.mapValues { (_, matches) ->
                            matches.map(ResolvedArtifact::key).sorted()
                        }
                    )
                }
                else ->
                    blockers +=
                        "source ${artifact.component} has unproved decision ${decision.kind}"
            }
        }

        if (targetScans.values.none { it.isVerifiedTargetVanilla }) {
            blockers += "target graph has no verified Minecraft/NeoForge vanilla artifact"
        }
        if (sourceScans.values.none { it.isVerifiedSourcePlatform }) {
            blockers += "source graph has no verified Forge/Minecraft plant platform artifact"
        }
        if (targetScans.values.count { it.isCanonicalRuntime } != 1) {
            blockers += "target graph must contain exactly one canonical runtime artifact"
        }
        val runtimeArtifact = targetScans.entries.singleOrNull { it.value.isCanonicalRuntime }?.key
        if (runtimeArtifact == null ||
            runtimeArtifact.componentKind != "project" ||
            runtimeArtifact.sha256 != runtimeIdentity.jarSha256
        ) {
            blockers += "canonical runtime classification is not bound to the verified project JAR"
        }
        val boundDecisionHashes = entries.mapNotNull {
            it.decision?.sha256
        }
        val plannedDecisionHashes = targetPlan.decisions.map {
            it.sha256
        }
        if (boundDecisionHashes.size != boundDecisionHashes.distinct().size ||
            boundDecisionHashes.toSet() != plannedDecisionHashes.toSet()
        ) {
            blockers +=
                "BuildSystem decisions are not bound exactly once to source closure entries"
        }
        val boundRemovalHashes = entries.mapNotNull {
            it.removal?.sha256
        }
        val plannedRemovalHashes = targetPlan.removals.map {
            it.sha256
        }
        if (boundRemovalHashes.size != boundRemovalHashes.distinct().size ||
            boundRemovalHashes.toSet() != plannedRemovalHashes.toSet()
        ) {
            blockers +=
                "BuildSystem removals are not bound exactly once to source closure entries"
        }
        val graphArtifacts = sourceGraph.artifacts.map { InventoryPhase.SOURCE to it } +
            targetGraph.artifacts.map { InventoryPhase.TARGET to it }
        for ((phase, artifact) in graphArtifacts) {
            if (entries.count { it.phase == phase && it.artifact == artifact } != 1) {
                blockers += "$phase ${artifact.component} has no coverage entry"
            }
        }
        return ClassificationResult(entries, blockers, blockers.isEmpty())
    }

    private fun materialize(
        projectDir: Path,
        sourceGraph: ResolvedGraph,
        targetGraph: ResolvedGraph,
        sourceClosureScan: ClosurePlantScan,
        targetClosureScan: ClosurePlantScan,
        targetPlan: TargetBuildPlanReceipt,
        classification: ClassificationResult,
        runtimeIdentity: CanonicalRuntimeIdentity
    ): MaterializationPlan {
        val sourceScans = sourceClosureScan.artifacts
        val targetScans = targetClosureScan.artifacts
        val sourceGraphText = sourceGraph.canonicalText()
        val targetGraphText = targetGraph.canonicalText()
        val sourceClosureScanText = sourceClosureScan.canonicalReceipt()
        val targetClosureScanText = targetClosureScan.canonicalReceipt()
        val targetPlanText = targetPlan.canonicalText()
        val runtimeIdentityText = runtimeIdentity.canonicalText()
        val entries = classification.entries.sortedWith(
            compareBy<CoverageEntry>({ it.phase.name }, { it.artifact.key })
        )
        val runSeed = buildString {
            appendLine("sourceGraphSha256=${sha256(sourceGraphText)}")
            appendLine("targetGraphSha256=${sha256(targetGraphText)}")
            appendLine("sourceClosureScanSha256=${sha256(sourceClosureScanText)}")
            appendLine("targetClosureScanSha256=${sha256(targetClosureScanText)}")
            appendLine("targetPlanSha256=${sha256(targetPlanText)}")
            appendLine("runtimeIdentitySha256=${sha256(runtimeIdentityText)}")
            entries.forEach { entry ->
                val scan = when (entry.phase) {
                    InventoryPhase.SOURCE -> sourceScans.getValue(entry.artifact)
                    InventoryPhase.TARGET -> targetScans.getValue(entry.artifact)
                }
                appendLine(
                    listOf(
                        entry.phase.name,
                        receiptAtom(entry.artifact.key),
                        entry.coverage,
                        sha256(scan.canonicalReceipt()),
                        scan.adapterManifest?.sha256.orEmpty(),
                        scan.reviewedAdapterProfile?.sha256.orEmpty(),
                        entry.decision?.sha256.orEmpty(),
                        entry.removal?.sha256.orEmpty(),
                        entry.matchedTargetCoordinates.entries
                            .sortedBy { it.key.canonicalText() }
                            .joinToString(";") {
                                "${receiptAtom(it.key.canonicalText())}=" +
                                    it.value.joinToString(",") { target ->
                                        receiptAtom(target)
                                    }
                            }
                    ).joinToString("|")
                )
            }
        }
        val runId = sha256(runSeed)
        val runRelative = "$INVENTORY_ROOT/runs/$runId"
        val runRoot = projectDir.resolve(runRelative)
        val textFiles = linkedMapOf<Path, String>()
        val binaryFiles = linkedMapOf<Path, Path>()
        textFiles[runRoot.resolve("run/seed.properties")] = runSeed
        textFiles[runRoot.resolve("source/graph.properties")] = sourceGraphText
        textFiles[runRoot.resolve("target/graph.properties")] = targetGraphText
        textFiles[runRoot.resolve("source/closure-scan.properties")] = sourceClosureScanText
        textFiles[runRoot.resolve("target/closure-scan.properties")] = targetClosureScanText
        textFiles[runRoot.resolve("build/target-plan.properties")] = targetPlanText
        textFiles[runRoot.resolve("runtime/identity.properties")] = runtimeIdentityText
        val platformAuxiliaryPointers = buildList {
            listOf(
                InventoryPhase.SOURCE to sourceGraph,
                InventoryPhase.TARGET to targetGraph
            ).forEach { (phase, graph) ->
                val phaseName = phase.name.lowercase()
                graph.platformAuxiliaries.sortedBy(PlatformAuxiliaryArtifact::kind)
                    .forEach { auxiliary ->
                        val kindName = auxiliary.kind.replaceFirstChar(Char::uppercase)
                        val path =
                            "$runRelative/$phaseName/platform/" +
                                "${auxiliary.kind}-${auxiliary.sha256}.jar"
                        binaryFiles[projectDir.resolve(path)] = auxiliary.file
                        add("${phaseName}Platform${kindName}Path=$path")
                        add("${phaseName}Platform${kindName}Sha256=${auxiliary.sha256}")
                    }
            }
        }

        val entryText = entries.flatMapIndexed { index, entry ->
            val prefix = "entry.$index"
            val phaseName = entry.phase.name.lowercase()
            val artifactPath =
                "$runRelative/$phaseName/artifacts/${entry.artifact.sha256}.jar"
            val scan = when (entry.phase) {
                InventoryPhase.SOURCE -> sourceScans.getValue(entry.artifact)
                InventoryPhase.TARGET -> targetScans.getValue(entry.artifact)
            }
            val scanReceipt = scan.canonicalReceipt()
            val scanSha256 = sha256(scanReceipt)
            val scanPath =
                "$runRelative/$phaseName/scans/${sha256(entry.artifact.key)}.properties"
            binaryFiles.putIfAbsent(projectDir.resolve(artifactPath), entry.artifact.file)
            textFiles.putIfAbsent(projectDir.resolve(scanPath), scanReceipt)
            buildList {
                add("$prefix.phase=$phaseName")
                add("$prefix.component=${receiptAtom(entry.artifact.component)}")
                add("$prefix.variantSha256=${entry.artifact.variantSha256}")
                add("$prefix.artifactPath=$artifactPath")
                add("$prefix.artifactSha256=${entry.artifact.sha256}")
                add("$prefix.scanPath=$scanPath")
                add("$prefix.scanSha256=$scanSha256")
                add("$prefix.coverage=${entry.coverage}")
                scan.adapterManifest?.let { manifest ->
                    val manifestPath =
                        "$runRelative/manifests/${manifest.sha256}.properties"
                    textFiles.putIfAbsent(projectDir.resolve(manifestPath), manifest.canonicalText)
                    add("$prefix.registrationManifestPath=$manifestPath")
                    add("$prefix.registrationManifestSha256=${manifest.sha256}")
                }
                entry.decision?.let { decision ->
                    val decisionPath =
                        "$runRelative/decisions/${decision.sha256}.properties"
                    textFiles.putIfAbsent(projectDir.resolve(decisionPath), decision.canonicalText())
                    add("$prefix.decisionPath=$decisionPath")
                    add("$prefix.decisionSha256=${decision.sha256}")
                }
                entry.removal?.let { removal ->
                    val removalPath =
                        "$runRelative/removals/${removal.sha256}.properties"
                    textFiles.putIfAbsent(projectDir.resolve(removalPath), removal.canonicalText())
                    add("$prefix.removalPath=$removalPath")
                    add("$prefix.removalSha256=${removal.sha256}")
                }
                entry.matchedTargetCoordinates.entries
                    .sortedBy { it.key.canonicalText() }
                    .forEach { (coordinate, targets) ->
                    val coordinateText = coordinate.canonicalText()
                    val coordinateId = sha256(coordinateText)
                    add(
                        "$prefix.targetCoordinate.$coordinateId=" +
                            receiptAtom(coordinateText)
                    )
                    targets.forEachIndexed { targetIndex, target ->
                        add(
                            "$prefix.targetCoordinate.$coordinateId.target.$targetIndex=" +
                                receiptAtom(target)
                        )
                    }
                }
            }
        }.sorted().joinToString("\n", postfix = "\n")
        val inventory = buildString {
            appendLine("schema=legacy-plant-external-coverage-v2")
            appendLine("producer=modporter-dual-closure-v1")
            appendLine("runId=$runId")
            appendLine("runSeedPath=$runRelative/run/seed.properties")
            appendLine("runSeedSha256=${sha256(runSeed)}")
            appendLine("sourceGraphPath=$runRelative/source/graph.properties")
            appendLine("sourceGraphSha256=${sha256(sourceGraphText)}")
            appendLine("sourceClosureScanPath=$runRelative/source/closure-scan.properties")
            appendLine("sourceClosureScanSha256=${sha256(sourceClosureScanText)}")
            appendLine("targetGraphPath=$runRelative/target/graph.properties")
            appendLine("targetGraphSha256=${sha256(targetGraphText)}")
            appendLine("targetClosureScanPath=$runRelative/target/closure-scan.properties")
            appendLine("targetClosureScanSha256=${sha256(targetClosureScanText)}")
            appendLine("targetPlanPath=$runRelative/build/target-plan.properties")
            appendLine("targetPlanSha256=${sha256(targetPlanText)}")
            appendLine("buildMappingSha256=${targetPlan.mappingResourceSha256}")
            appendLine("runtimeIdentityPath=$runRelative/runtime/identity.properties")
            appendLine("runtimeIdentitySha256=${sha256(runtimeIdentityText)}")
            appendLine("runtimeSourceSha256=${runtimeIdentity.sourceSha256}")
            appendLine("runtimeJarSha256=${runtimeIdentity.jarSha256}")
            platformAuxiliaryPointers.sorted().forEach(::appendLine)
            appendLine("entryCount=${entries.size}")
            appendLine("inventorySha256=${sha256(entryText)}")
            appendLine("complete=true")
            append(entryText)
        }
        return MaterializationPlan(
            runRoot = runRoot,
            textFiles = textFiles,
            binaryFiles = binaryFiles,
            output = projectDir.resolve(OUTPUT),
            outputContent = inventory
        )
    }

    private fun plannedChanges(plan: MaterializationPlan): List<Change> {
        val changes = mutableListOf<Change>()
        fun add(path: Path, before: String, after: String) {
            if (before == after) return
            changes += Change(
                file = path,
                line = 1,
                description = "Bind complete source and target legacy plant dependency closure",
                before = before,
                after = after,
                confidence = Confidence.HIGH,
                ruleId = "struct-legacy-plant-dependency-inventory"
            )
        }
        for ((path, content) in plan.textFiles) {
            val before = if (path.isRegularFile()) {
                sha256(Files.readString(path))
            } else {
                "(missing)"
            }
            add(path, before, sha256(content))
        }
        for ((path, source) in plan.binaryFiles) {
            val before = if (path.isRegularFile()) fileSha256(path) else "(missing)"
            add(path, before, fileSha256(source))
        }
        val outputBefore = if (plan.output.isRegularFile()) {
            sha256(Files.readString(plan.output))
        } else {
            "(missing)"
        }
        add(plan.output, outputBefore, sha256(plan.outputContent))
        return changes
    }

    private fun commitMaterialization(projectDir: Path, plan: MaterializationPlan) {
        val project = projectDir.toAbsolutePath().normalize()
        val allowedRuns = project.resolve("$INVENTORY_ROOT/runs").normalize()
        val finalRun = plan.runRoot.toAbsolutePath().normalize()
        val exactOutput = project.resolve(OUTPUT).normalize()
        require(finalRun.parent == allowedRuns &&
            plan.output.toAbsolutePath().normalize() == exactOutput
        ) {
            "Inventory materialization escaped the owned project root"
        }
        val transaction = project.resolve(
            ".modporter/tmp/lpw-${shortStageId()}"
        )
        val stagedRun = transaction.resolve("run")
        val temporaryOutput = plan.output.parent.resolve(
            ".${plan.output.fileName}.tmp-${UUID.randomUUID()}"
        )
        try {
            for ((target, content) in plan.textFiles) {
                val relative = finalRun.relativize(target.toAbsolutePath().normalize())
                require(!relative.startsWith("..")) { "Text receipt escaped inventory run: $target" }
                val staged = stagedRun.resolve(relative.toString())
                staged.parent.createDirectories()
                Files.writeString(staged, content)
                forceFile(staged)
            }
            for ((target, source) in plan.binaryFiles) {
                val relative = finalRun.relativize(target.toAbsolutePath().normalize())
                require(!relative.startsWith("..")) { "Artifact escaped inventory run: $target" }
                val staged = stagedRun.resolve(relative.toString())
                staged.parent.createDirectories()
                Files.copy(source, staged)
                forceFile(staged)
            }
            verifyRun(stagedRun, finalRun, plan)
            allowedRuns.createDirectories()
            if (finalRun.exists()) {
                verifyRun(finalRun, finalRun, plan)
            } else {
                Files.move(stagedRun, finalRun, StandardCopyOption.ATOMIC_MOVE)
            }

            plan.output.parent.createDirectories()
            Files.writeString(temporaryOutput, plan.outputContent)
            forceFile(temporaryOutput)
            require(sha256(Files.readString(temporaryOutput)) == sha256(plan.outputContent)) {
                "Coverage pointer changed while staging"
            }
            Files.move(
                temporaryOutput,
                plan.output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            Files.deleteIfExists(temporaryOutput)
            deleteOwnedTree(transaction, project.resolve(".modporter/tmp"))
        }
    }

    private fun publishInventory(
        ownerProject: Path,
        leafProject: Path,
        plan: MaterializationPlan,
        sourceGraph: ResolvedGraph,
        targetGraph: ResolvedGraph
    ) {
        verifyRun(plan.runRoot, plan.runRoot, plan)
        val source = sealedInventory(sourceGraph, plan.runRoot, InventoryPhase.SOURCE)
        val target = sealedInventory(targetGraph, plan.runRoot, InventoryPhase.TARGET)
        LegacyMigrationArtifactInventoryHandoff.publish(
            LegacyMigrationArtifactPair(
                ownerProject = ownerProject,
                leafProject = leafProject,
                sealedRunRoot = plan.runRoot,
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
        )
    }

    private fun sealedInventory(
        graph: ResolvedGraph,
        runRoot: Path,
        phase: InventoryPhase
    ): SealedInventory {
        require(graph.phase == phase)
        val identity = requireNotNull(graph.platformIdentity) {
            "${phase.name.lowercase()} graph has no platform identity"
        }
        val phaseName = phase.name.lowercase()
        val closureArtifacts = graph.artifacts.map { artifact ->
            LegacyMigrationArtifactInventory.Artifact(
                component = artifact.component,
                componentKind = when (artifact.componentKind) {
                    "module" -> LegacyMigrationArtifactInventory.ComponentKind.MODULE
                    "project" -> LegacyMigrationArtifactInventory.ComponentKind.PROJECT
                    "generated-platform" ->
                        LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM
                    else -> error(
                        "Unsupported legacy migration component kind: ${artifact.componentKind}"
                    )
                },
                displayName = artifact.displayName,
                configurations = artifact.buildSystemConfigurations(),
                file = runRoot.resolve(
                    "$phaseName/artifacts/${artifact.sha256}.jar"
                ),
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
            auxiliary.kind to LegacyMigrationArtifactInventory.Artifact(
                component =
                    "platform-auxiliary:${identity.resolvedLoaderCoordinate}:${auxiliary.kind}",
                componentKind =
                    LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY,
                displayName = auxiliary.displayName,
                configurations = emptySet(),
                file = runRoot.resolve(
                    "$phaseName/platform/${auxiliary.kind}-${auxiliary.sha256}.jar"
                ),
                sha256 = auxiliary.sha256
            )
        }
        require(auxiliaries.keys == setOf("merged", "sources"))
        return SealedInventory(
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

    private fun verifyRun(
        actualRoot: Path,
        finalRun: Path,
        plan: MaterializationPlan
    ) {
        val expected = linkedMapOf<Path, String>()
        plan.textFiles.forEach { (target, content) ->
            expected[finalRun.relativize(target.toAbsolutePath().normalize())] =
                sha256(content)
        }
        plan.binaryFiles.forEach { (target, source) ->
            expected[finalRun.relativize(target.toAbsolutePath().normalize())] =
                fileSha256(source)
        }
        val actual = Files.walk(actualRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { actualRoot.relativize(it) to fileSha256(it) }
                .toList()
                .toMap()
        }
        require(actual == expected) {
            "Staged legacy plant inventory run does not match its complete expected file set"
        }
    }

    private fun copyProject(source: Path, target: Path) {
        val excluded = setOf(".git", ".gradle", "build", "run", ".modporter")
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult {
                val relative = directory.relativeTo(source)
                if (relative.toString().isNotEmpty() &&
                    relative.getName(0).toString() in excluded
                ) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                require(!Files.isSymbolicLink(directory)) {
                    "Legacy plant staging does not admit symbolic links: $relative"
                }
                target.resolve(relative.toString()).createDirectories()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult {
                val relative = file.relativeTo(source)
                require(!Files.isSymbolicLink(file)) {
                    "Legacy plant staging does not admit symbolic links: $relative"
                }
                val destination = target.resolve(relative.toString())
                destination.parent.createDirectories()
                Files.copy(file, destination)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun deleteOwnedTree(root: Path, allowedParent: Path) {
        if (!root.exists()) return
        val absoluteRoot = root.toAbsolutePath().normalize()
        val absoluteParent = allowedParent.toAbsolutePath().normalize()
        require(absoluteRoot.startsWith(absoluteParent) && absoluteRoot != absoluteParent) {
            "Refusing to delete non-owned inventory stage $absoluteRoot"
        }
        Files.walk(absoluteRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun fileSha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(path.readBytes())
            .joinToString("") { "%02x".format(it) }
}

internal enum class InventoryPhase { SOURCE, TARGET }

internal interface LegacyPlantGradleResolver {
    fun resolve(stagedProject: Path, phase: InventoryPhase): ResolvedGraph
}

internal interface LegacyPlantBytecodeScanner {
    fun scan(
        graph: ResolvedGraph,
        runtimeIdentity: CanonicalRuntimeIdentity,
        adapterRegistrations: List<PlannedAdapterRegistrationReceipt>
    ): ClosurePlantScan
}

internal interface LegacyPlantTargetBuildPlanner {
    fun prepare(stagedProject: Path, sourceGraph: ResolvedGraph): TargetBuildPlanReceipt
}

internal data class PlatformResolutionIdentity(
    val family: String,
    val minecraftVersion: String,
    val loaderVersion: String,
    val resolvedLoaderCoordinate: String,
    val developmentPluginId: String,
    val developmentPluginVersion: String,
    val developmentPluginClass: String,
    val developmentPluginSha256: String,
    val generatedArtifactDisplayName: String,
    val generatedArtifactSha256: String,
    val declarationFileHashes: Map<String, String>
) {
    init {
        require(family in setOf("legacy-forge", "neoforge"))
        require(minecraftVersion.isNotBlank())
        require(loaderVersion.isNotBlank())
        require(resolvedLoaderCoordinate.isNotBlank())
        require(developmentPluginId.isNotBlank())
        require(developmentPluginVersion.isNotBlank())
        require(developmentPluginClass.isNotBlank())
        require(developmentPluginSha256.matches(Regex("[0-9a-f]{64}")))
        require(generatedArtifactDisplayName.isNotBlank())
        require(generatedArtifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(declarationFileHashes.isNotEmpty())
        require(
            declarationFileHashes.values.all {
                it.matches(Regex("[0-9a-f]{64}"))
            }
        )
    }

    val canonicalText: String
        get() = buildList {
            add("schema=legacy-plant-platform-resolution-v1")
            add("family=$family")
            add("minecraftVersion=${receiptAtom(minecraftVersion)}")
            add("loaderVersion=${receiptAtom(loaderVersion)}")
            add("resolvedLoaderCoordinate=${receiptAtom(resolvedLoaderCoordinate)}")
            add("developmentPluginId=${receiptAtom(developmentPluginId)}")
            add("developmentPluginVersion=${receiptAtom(developmentPluginVersion)}")
            add("developmentPluginClass=${receiptAtom(developmentPluginClass)}")
            add("developmentPluginSha256=$developmentPluginSha256")
            add("generatedArtifactDisplayName=${receiptAtom(generatedArtifactDisplayName)}")
            add("generatedArtifactSha256=$generatedArtifactSha256")
            declarationFileHashes.toSortedMap().forEach { (path, hash) ->
                add("declarationFile=${receiptAtom(path)}|$hash")
            }
        }.joinToString("\n", postfix = "\n")

    val sha256: String
        get() = receiptSha256(canonicalText)

    fun matchesReviewedPlatform(
        reviewed: PlatformResolutionIdentity,
        reviewedAppliedPluginSha256s: Set<String> =
            setOf(reviewed.developmentPluginSha256)
    ): Boolean =
        family == reviewed.family &&
            minecraftVersion == reviewed.minecraftVersion &&
            loaderVersion == reviewed.loaderVersion &&
            resolvedLoaderCoordinate == reviewed.resolvedLoaderCoordinate &&
            developmentPluginId == reviewed.developmentPluginId &&
            developmentPluginVersion == reviewed.developmentPluginVersion &&
            developmentPluginClass == reviewed.developmentPluginClass &&
            developmentPluginSha256 in reviewedAppliedPluginSha256s &&
            generatedArtifactDisplayName == reviewed.generatedArtifactDisplayName &&
            generatedArtifactSha256 == reviewed.generatedArtifactSha256
}

internal data class LegacyPlantResolutionProvenance(
    val origin: String,
    val metadataFreshness: String,
    val sourceIdentitySha256: String,
    val recoveryReason: String? = null,
    val failedAttemptExitCode: Int? = null,
    val failedAttemptLogSha256: String? = null,
    val recoveryAttemptLogSha256: String? = null
) {
    init {
        require(origin in setOf("online", "gradle-offline-complete-cache"))
        require(metadataFreshness in setOf("not-asserted", "cached"))
        require(sourceIdentitySha256.matches(Regex("[0-9a-f]{64}")))
        if (origin == "online") {
            require(metadataFreshness == "not-asserted")
            require(recoveryReason == null)
            require(failedAttemptExitCode == null)
            require(failedAttemptLogSha256 == null)
            require(recoveryAttemptLogSha256 == null)
        } else {
            require(metadataFreshness == "cached")
            require(recoveryReason == "dependency-metadata-transport-failure")
            require(failedAttemptExitCode != null && failedAttemptExitCode != 0)
            require(failedAttemptLogSha256?.matches(Regex("[0-9a-f]{64}")) == true)
            require(
                recoveryAttemptLogSha256 == null ||
                    recoveryAttemptLogSha256.matches(Regex("[0-9a-f]{64}"))
            )
        }
    }

    fun canonicalLines(): List<String> =
        listOf(
            "resolutionOrigin=$origin",
            "metadataFreshness=$metadataFreshness",
            "sourceIdentitySha256=$sourceIdentitySha256",
            "recoveryReason=${recoveryReason?.let(::receiptAtom) ?: "absent"}",
            "failedAttemptExitCode=${failedAttemptExitCode ?: "absent"}",
            "failedAttemptLogSha256=${failedAttemptLogSha256 ?: "absent"}"
        ) + listOfNotNull(
            recoveryAttemptLogSha256?.let { "recoveryAttemptLogSha256=$it" }
        )

    companion object {
        private val receiptKeys = setOf(
            "resolutionOrigin",
            "metadataFreshness",
            "sourceIdentitySha256",
            "recoveryReason",
            "failedAttemptExitCode",
            "failedAttemptLogSha256"
        )
        private const val recoveryLogKey = "recoveryAttemptLogSha256"

        fun fromCanonicalRecords(
            records: List<Pair<String, String>>
        ): LegacyPlantResolutionProvenance? {
            val provenance = records.filter {
                it.first in receiptKeys || it.first == recoveryLogKey
            }
            if (provenance.isEmpty()) return null
            require(provenance.groupingBy { it.first }.eachCount().values.all { it == 1 })
            require(provenance.map { it.first }.toSet().containsAll(receiptKeys))
            require(provenance.size in receiptKeys.size..receiptKeys.size + 1)
            val values = provenance.toMap()
            return LegacyPlantResolutionProvenance(
                origin = values.getValue("resolutionOrigin"),
                metadataFreshness = values.getValue("metadataFreshness"),
                sourceIdentitySha256 = values.getValue("sourceIdentitySha256"),
                recoveryReason = values.getValue("recoveryReason").let {
                    if (it == "absent") {
                        null
                    } else {
                        Base64.getUrlDecoder().decode(it).toString(Charsets.UTF_8)
                    }
                },
                failedAttemptExitCode = values.getValue("failedAttemptExitCode").let {
                    if (it == "absent") null else it.toInt()
                },
                failedAttemptLogSha256 = values.getValue("failedAttemptLogSha256").let {
                    if (it == "absent") null else it
                },
                recoveryAttemptLogSha256 = values[recoveryLogKey]
            )
        }
    }
}

internal fun legacyPlantBuildInputIdentity(
    buildFileHashes: Map<String, String>
): String {
    require(buildFileHashes.isNotEmpty())
    require(buildFileHashes.values.all { it.matches(Regex("[0-9a-f]{64}")) })
    return receiptSha256(
        buildFileHashes.toSortedMap().entries.joinToString("\n", postfix = "\n") {
            "${receiptAtom(it.key)}|${it.value}"
        }
    )
}

internal data class ResolvedGraph(
    val phase: InventoryPhase,
    val resolverSchema: String,
    val gradleVersion: String,
    val classRuntimeFeature: Int,
    val buildFileHashes: Map<String, String>,
    val configurations: Set<String>,
    val artifacts: List<ResolvedArtifact>,
    val edges: List<String>,
    val unresolved: List<String>,
    val platformIdentity: PlatformResolutionIdentity?,
    val platformAuxiliaries: List<PlatformAuxiliaryArtifact>,
    val resolutionProvenance: LegacyPlantResolutionProvenance? = null
) {
    init {
        require(resolverSchema == "modporter-legacy-plant-resolved-graph-v1")
        require(gradleVersion.isNotBlank())
        require(classRuntimeFeature in setOf(17, 21))
        require(buildFileHashes.isNotEmpty())
        require(buildFileHashes.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(
            platformIdentity?.declarationFileHashes.orEmpty().all { (path, hash) ->
                buildFileHashes[path] == hash
            }
        ) {
            "Platform declaration hashes are not bound to the resolved graph build files"
        }
        require(configurations.isNotEmpty())
        require(
            artifacts.flatMap(ResolvedArtifact::configurations).all {
                it in configurations
            }
        )
        configurations.forEach { configuration ->
            val orders = artifacts.filter {
                configuration in it.configurations
            }.map {
                it.configurationOrder.getValue(configuration)
            }
            require(
                orders.distinct().size == orders.size &&
                    orders.sorted() == (0 until orders.size).toList()
            ) {
                "Resolved graph configuration order is not unique and contiguous: $configuration"
            }
        }
        require(artifacts.map(ResolvedArtifact::key).distinct().size == artifacts.size)
        require(platformAuxiliaries.map(PlatformAuxiliaryArtifact::kind).distinct().size ==
            platformAuxiliaries.size)
        if (platformIdentity == null) {
            require(platformAuxiliaries.isEmpty())
        } else {
            require(platformAuxiliaries.map(PlatformAuxiliaryArtifact::kind).toSet() ==
                setOf("merged", "sources"))
        }
        resolutionProvenance?.let { provenance ->
            require(
                provenance.sourceIdentitySha256 ==
                    legacyPlantBuildInputIdentity(buildFileHashes)
            ) {
                "Legacy plant resolution provenance is not bound to exact build inputs"
            }
        }
    }

    fun canonicalText(): String =
        (
            listOf("phase=${phase.name.lowercase()}") +
                listOf(
                    "resolverSchema=$resolverSchema",
                    "gradleVersion=$gradleVersion",
                    "classRuntimeFeature=$classRuntimeFeature"
                ) +
                listOfNotNull(
                    platformIdentity?.let {
                        "platformIdentity=${receiptAtom(it.canonicalText)}|${it.sha256}"
                    }
                ) +
                buildFileHashes.toSortedMap().map {
                    "buildFile=${receiptAtom(it.key)}|${it.value}"
                } +
                resolutionProvenance?.canonicalLines().orEmpty() +
                platformAuxiliaries.flatMap { it.canonicalLines() } +
                configurations.map { "configuration=${receiptAtom(it)}" } +
                artifacts.flatMap { it.canonicalLines() } +
                edges.map { "edge=${receiptAtom(it)}" }
            )
            .sorted()
            .joinToString("\n", postfix = "\n")

    fun buildSystemSourceDependencies(): List<BuildResolvedSourceDependency> {
        require(phase == InventoryPhase.SOURCE)
        val modules = artifacts.filter { it.componentKind == "module" }
        val moduleProjects = modules.map(ResolvedArtifact::projectPath).toSet()
        require(moduleProjects.size == 1) {
            "BuildDependencyPreparationReceipt cannot erase multi-project dependency ownership: " +
                moduleProjects.sorted()
        }
        return modules.sortedBy(ResolvedArtifact::key).map { artifact ->
            BuildResolvedSourceDependency(
                component = artifact.component,
                configurations = artifact.buildSystemConfigurations(),
                artifactSha256 = artifact.sha256
            )
        }
    }
}

internal data class PlatformAuxiliaryArtifact(
    val kind: String,
    val displayName: String,
    val file: Path,
    val size: Long,
    val sha256: String
) {
    init {
        require(kind in setOf("merged", "sources"))
        require(displayName.isNotBlank())
        require(file.isRegularFile())
        require(Files.size(file) == size)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(fileReceiptSha256(file) == sha256)
    }

    fun canonicalLines(): List<String> = listOf(
        "platformAuxiliary=${receiptAtom(kind)}|${receiptAtom(displayName)}|$size|$sha256"
    )
}

internal data class ResolvedArtifact(
    val projectPath: String,
    val component: String,
    val componentKind: String,
    val classifier: String?,
    val displayName: String,
    val configurations: Set<String>,
    val configurationOrder: Map<String, Int>,
    val variantAttributes: Map<String, String>,
    val file: Path,
    val size: Long,
    val sha256: String,
    val variantSha256: String
) {
    val key: String
        get() = receiptSha256(
            listOf(
                receiptAtom(projectPath),
                receiptAtom(component),
                classifier?.let(::receiptAtom) ?: "absent",
                receiptAtom(displayName),
                variantSha256,
                sha256
            ).joinToString("|")
        )

    init {
        require(projectPath.startsWith(":"))
        require(file.isRegularFile())
        require(
            file.fileName.toString().endsWith(".jar") ||
                (
                    componentKind == "generated-platform" &&
                        file.fileName.toString() == "intermediateToNamed.zip"
                    )
        )
        require(componentKind in setOf("module", "project", "generated-platform"))
        if (componentKind == "module") {
            val parts = component.split(':')
            require(parts.size in 3..4 && parts.all(String::isNotBlank))
            require(parts.getOrNull(3) == classifier)
        } else {
            require(classifier == null)
        }
        require(configurations.isNotEmpty())
        require(configurations.all { it.configurationIdentity().projectPath == projectPath })
        require(configurationOrder.keys == configurations)
        require(configurationOrder.values.all { it >= 0 })
        require(Files.size(file) == size)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(fileReceiptSha256(file) == sha256)
        require(variantSha256 == receiptSha256(
            variantAttributes.toSortedMap().entries.joinToString("\n") {
                "${it.key}=${it.value}"
            }
        ))
    }

    fun canonicalLines(): List<String> = buildList {
        add("projectPath=${receiptAtom(key)}|${receiptAtom(projectPath)}")
        add("component=${receiptAtom(key)}|${receiptAtom(component)}")
        add("componentKind=${receiptAtom(key)}|$componentKind")
        add(
            "classifier=${receiptAtom(key)}|" +
                (classifier?.let(::receiptAtom) ?: "absent")
        )
        add("displayName=${receiptAtom(key)}|${receiptAtom(displayName)}")
        add(
            "artifact=${receiptAtom(key)}|" +
                "${this@ResolvedArtifact.size}|$sha256"
        )
        add("variantSha256=${receiptAtom(key)}|$variantSha256")
        configurations.sorted().forEach {
            add("configuration=${receiptAtom(key)}|${receiptAtom(it)}")
            add(
                "configurationOrder=${receiptAtom(key)}|${receiptAtom(it)}|" +
                    configurationOrder.getValue(it)
            )
        }
        variantAttributes.toSortedMap().forEach { (key, value) ->
            add(
                "variant=${receiptAtom(this@ResolvedArtifact.key)}|" +
                    "${receiptAtom(key)}=${receiptAtom(value)}"
            )
        }
    }

    fun matchesSelector(selector: String): Boolean = component == selector

    fun buildSystemConfigurations(): Set<String> =
        configurations.map { it.configurationIdentity().name }.toSet()
}

internal data class ResolvedConfigurationIdentity(
    val projectPath: String,
    val name: String
) {
    init {
        require(projectPath.startsWith(":"))
        require(name.isNotBlank() && '|' !in name)
    }
}

internal fun String.configurationIdentity(): ResolvedConfigurationIdentity {
    val parts = split('|')
    require(parts.size == 2) { "Malformed resolved configuration identity: $this" }
    return ResolvedConfigurationIdentity(parts[0], parts[1])
}

internal data class PlantSurfaceScan(
    val hasPlantSurface: Boolean,
    val relevantClasses: List<String>,
    val classFacts: List<String>,
    val allRelevantClassesImplementCanonicalAbi: Boolean,
    val isVerifiedTargetVanilla: Boolean,
    val isVerifiedSourcePlatform: Boolean,
    val isCanonicalRuntime: Boolean,
    val adapterManifest: AdapterManifestReceipt?,
    val reviewedAdapterProfile: ReviewedLegacyPlantAdapterProfileReceipt? = null
) {
    fun canonicalReceipt(): String = buildList {
        add("schema=legacy-plant-bytecode-scan-v1")
        add("hasPlantSurface=$hasPlantSurface")
        add("allRelevantClassesImplementCanonicalAbi=$allRelevantClassesImplementCanonicalAbi")
        add("isVerifiedTargetVanilla=$isVerifiedTargetVanilla")
        add("isVerifiedSourcePlatform=$isVerifiedSourcePlatform")
        add("isCanonicalRuntime=$isCanonicalRuntime")
        adapterManifest?.let { add("adapterManifestSha256=${it.sha256}") }
        reviewedAdapterProfile?.let { add("reviewedAdapterProfileSha256=${it.sha256}") }
        relevantClasses.sorted().forEach { add("relevantClass=${receiptAtom(it)}") }
        classFacts.sorted().forEach { add("fact=${receiptAtom(it)}") }
    }.joinToString("\n", postfix = "\n")
}

internal data class ClosurePlantScan(
    val artifacts: Map<ResolvedArtifact, PlantSurfaceScan>,
    val definedClasses: Set<String>,
    val differentBytecodeDuplicates: Map<String, List<String>>,
    val blockers: List<String>
) {
    fun canonicalReceipt(): String = buildList {
        add("schema=legacy-plant-closure-scan-v1")
        artifacts.entries.sortedBy { it.key.key }.forEach { (artifact, scan) ->
            add("artifactScan=${receiptAtom(artifact.key)}|${receiptSha256(scan.canonicalReceipt())}")
        }
        definedClasses.sorted().forEach { add("definedClass=${receiptAtom(it)}") }
        differentBytecodeDuplicates.toSortedMap().forEach { (owner, facts) ->
            facts.sorted().forEach {
                add("differentBytecodeDuplicate=${receiptAtom(owner)}|${receiptAtom(it)}")
            }
        }
        blockers.sorted().forEach { add("blocker=${receiptAtom(it)}") }
    }.joinToString("\n", postfix = "\n")
}

internal data class AdapterManifestReceipt(
    val artifactSha256: String,
    val runtimeSourceSha256: String,
    val runtimeJarSha256: String,
    val registrations: List<AdapterRegistrationReceipt>
) {
    val canonicalText: String
        get() = buildList {
            add("schema=legacy-plant-adapter-manifest-v1")
            add("artifactSha256=$artifactSha256")
            add("runtimeSourceSha256=$runtimeSourceSha256")
            add("runtimeJarSha256=$runtimeJarSha256")
            registrations.sortedBy(AdapterRegistrationReceipt::canonicalText).forEach {
                add("registration=${receiptAtom(it.canonicalText())}|${it.sha256}")
            }
        }.joinToString("\n", postfix = "\n")

    val sha256: String
        get() = receiptSha256(canonicalText)

    fun isValidFor(
        artifact: ResolvedArtifact,
        runtimeIdentity: CanonicalRuntimeIdentity
    ): Boolean =
        artifactSha256 == artifact.sha256 &&
            runtimeSourceSha256 == runtimeIdentity.sourceSha256 &&
            runtimeJarSha256 == runtimeIdentity.jarSha256 &&
            registrations.isNotEmpty() &&
            registrations.map {
                listOf(
                    it.owner,
                    it.target,
                    it.registrationMode,
                    it.adapterKind
                )
            }.distinct().size ==
            registrations.size
}

internal data class AdapterRegistrationReceipt(
    val owner: String,
    val target: String,
    val registrationMode: String,
    val adapterKind: String,
    val adapterSourceSha256: String
) {
    init {
        require(registrationMode in setOf("class", "exact"))
        require(adapterKind in setOf("plantable", "soil", "placement"))
        require(adapterSourceSha256.matches(Regex("[0-9a-f]{64}")))
    }

    val sha256: String
        get() = receiptSha256(canonicalText())

    fun canonicalText(): String = buildList {
        add("owner=${receiptAtom(owner)}")
        add("target=${receiptAtom(target)}")
        add("registrationMode=$registrationMode")
        add("adapterKind=$adapterKind")
        add("adapterSourceSha256=$adapterSourceSha256")
    }.joinToString("\n", postfix = "\n")
}

internal data class PlannedAdapterRegistrationReceipt(
    val targetSelector: String,
    val owner: String,
    val target: String,
    val registrationMode: String,
    val adapterKind: String,
    val adapterSourcePath: String,
    val adapterSourceSha256: String
) {
    init {
        require(targetSelector.isNotBlank())
        require(owner.isNotBlank())
        require(target.isNotBlank())
        require(registrationMode in setOf("class", "exact"))
        require(adapterKind in setOf("plantable", "soil", "placement"))
        require(adapterSourcePath.isNotBlank())
        val normalized = Path.of(adapterSourcePath).normalize()
        require(!normalized.isAbsolute && !normalized.startsWith(".."))
        require(adapterSourceSha256.matches(Regex("[0-9a-f]{64}")))
    }

    val sha256: String
        get() = receiptSha256(canonicalText())

    fun canonicalText(): String = buildList {
        add("targetSelector=${receiptAtom(targetSelector)}")
        add("owner=${receiptAtom(owner)}")
        add("target=${receiptAtom(target)}")
        add("registrationMode=$registrationMode")
        add("adapterKind=$adapterKind")
        add("adapterSourcePath=${receiptAtom(adapterSourcePath)}")
        add("adapterSourceSha256=$adapterSourceSha256")
    }.joinToString("\n", postfix = "\n")

    fun materialize(): AdapterRegistrationReceipt =
        AdapterRegistrationReceipt(
            owner = owner,
            target = target,
            registrationMode = registrationMode,
            adapterKind = adapterKind,
            adapterSourceSha256 = adapterSourceSha256
        )
}

internal class CanonicalRuntimeIdentity private constructor(
    private val sourceTemplates: Map<String, ByteArray>,
    private val jarHashReceipt: ByteArray,
    val sourceSha256: String,
    val jarSha256: String
) {
    val evidenceSha256: String
        get() = receiptSha256(canonicalText())

    fun verify() {
        require(sourceTemplates.keys == LegacyPlantRuntimeResources.JAVA_NAMES) {
            "Canonical runtime source set changed"
        }
        require(
            LegacyPlantRuntimeResources.canonicalSourceHashBytes(sourceTemplates) == sourceSha256
        ) {
            "Canonical runtime source or resource bytes changed"
        }
        require(jarHashReceipt.toString(Charsets.UTF_8).trim() == jarSha256) {
            "Canonical runtime JAR receipt changed"
        }
        require(jarSha256.matches(Regex("[0-9a-f]{64}")))
        require(
            generatedResourceHashes().keys ==
                setOf(ReviewedLegacyPlantAdapterProfileReceipt.REQUIRED_MIXIN_CONFIG)
        ) {
            "Canonical runtime resource set changed"
        }
    }

    fun canonicalText(): String = buildList {
        add("schema=legacy-plant-canonical-runtime-identity-v2")
        add("sourceSha256=$sourceSha256")
        add("jarSha256=$jarSha256")
        add("jarHashReceiptSha256=${receiptSha256(jarHashReceipt)}")
        sourceTemplates.toSortedMap().forEach { (name, bytes) ->
            add("source=$name|${receiptSha256(bytes)}")
        }
        LegacyPlantRuntimeResources.resourceTemplates().toSortedMap().forEach { (name, text) ->
            add("resourceTemplate=$name|${receiptSha256(text)}")
        }
        generatedResourceHashes().toSortedMap().forEach { (name, hash) ->
            add("resource=$name|$hash")
        }
    }.joinToString("\n", postfix = "\n")

    fun runtimeRequest(): String = buildString {
        appendLine("schema=legacy-plant-runtime-request-v1")
        appendLine("package=com.modporter.compat.plant.v1")
        appendLine("sourceSha256=$sourceSha256")
        appendLine("jarSha256=$jarSha256")
    }

    fun generatedSourceHashes(): Map<String, String> =
        sourceTemplates.mapValues { (_, bytes) ->
            val content = bytes.toString(Charsets.UTF_8)
                .replace("@PACKAGE@", "com.modporter.compat.plant.v1")
                .replace("@RUNTIME_SOURCE_SHA256@", sourceSha256)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            receiptSha256(content)
        }

    fun generatedResourceHashes(): Map<String, String> =
        LegacyPlantRuntimeResources.expandedResources(sourceSha256)
            .mapValues { (_, content) -> receiptSha256(content) }

    companion object {
        fun fromCanonicalResources(
            sourceTemplates: Map<String, ByteArray>,
            jarHashReceipt: ByteArray
        ): CanonicalRuntimeIdentity {
            val copied = sourceTemplates.mapValues { (_, bytes) -> bytes.copyOf() }
            val jarSha256 = jarHashReceipt.toString(Charsets.UTF_8).trim()
            val identity = CanonicalRuntimeIdentity(
                sourceTemplates = copied,
                jarHashReceipt = jarHashReceipt.copyOf(),
                sourceSha256 = LegacyPlantRuntimeResources.canonicalSourceHashBytes(copied),
                jarSha256 = jarSha256
            )
            identity.verify()
            return identity
        }

        fun fromProductionResources(): CanonicalRuntimeIdentity {
            val sources = LegacyPlantRuntimeResources.javaTemplates()
                .mapValues { (_, content) -> content.toByteArray(Charsets.UTF_8) }
            val jarHashReceipt = checkNotNull(
                CanonicalRuntimeIdentity::class.java.getResourceAsStream(
                    "/compat/legacy-plant-compat-v1.jar.sha256"
                )
            ) {
                "Missing canonical legacy plant runtime JAR hash"
            }.use { it.readBytes() }
            return fromCanonicalResources(sources, jarHashReceipt)
        }
    }
}

internal data class TargetBuildPlanReceipt(
    val preparation: BuildDependencyPreparationReceipt,
    val adapterRegistrations: List<PlannedAdapterRegistrationReceipt>,
    val reviewedAdapterProfiles: List<ReviewedLegacyPlantAdapterProfileReceipt> = emptyList()
) {
    init {
        require(
            adapterRegistrations.map(PlannedAdapterRegistrationReceipt::sha256)
                .distinct().size == adapterRegistrations.size
        )
        require(
            reviewedAdapterProfiles.map(ReviewedLegacyPlantAdapterProfileReceipt::sha256)
                .distinct().size == reviewedAdapterProfiles.size
        )
        reviewedAdapterProfiles.forEach { profile ->
            require(preparation.decisions.singleOrNull(profile::matchesDecision) != null) {
                "Reviewed adapter profile is not bound to its exact BuildSystem decision"
            }
        }
        adapterRegistrations.forEach { registration ->
            require(
                preparation.changedSourceFiles.singleOrNull {
                    it.path == registration.adapterSourcePath
                }?.afterSha256 == registration.adapterSourceSha256
            ) {
                "Adapter registration is not bound to the generated target source bytes"
            }
        }
    }

    val result: PassResult get() = preparation.result
    val decisions: List<BuildDependencyDecisionReceipt> get() = preparation.decisions
    val removals: List<BuildRemovedDependencyReceipt> get() = preparation.removals
    val mappingResourceSha256: String get() = preparation.mappingResourceSha256

    val sha256: String
        get() = receiptSha256(canonicalText())

    fun canonicalText(): String = buildList {
        add("schema=legacy-plant-target-build-plan-v3")
        add(
            "preparation=${receiptAtom(preparation.canonicalText())}|" +
                preparation.sha256
        )
        adapterRegistrations.sortedBy(PlannedAdapterRegistrationReceipt::sha256).forEach {
            add("adapterRegistration=${receiptAtom(it.canonicalText())}|${it.sha256}")
        }
        reviewedAdapterProfiles
            .sortedBy(ReviewedLegacyPlantAdapterProfileReceipt::sha256)
            .forEach {
                add("reviewedAdapterProfile=${receiptAtom(it.canonicalText())}|${it.sha256}")
            }
    }.joinToString("\n", postfix = "\n")
}

internal data class CoverageEntry(
    val phase: InventoryPhase,
    val artifact: ResolvedArtifact,
    val coverage: String,
    val decision: BuildDependencyDecisionReceipt? = null,
    val removal: BuildRemovedDependencyReceipt? = null,
    val matchedTargetCoordinates: Map<BuildTargetCoordinate, List<String>> = emptyMap()
)

internal data class ClassificationResult(
    val entries: List<CoverageEntry>,
    val blockers: List<String>,
    val complete: Boolean
)

internal data class MaterializationPlan(
    val runRoot: Path,
    val textFiles: Map<Path, String>,
    val binaryFiles: Map<Path, Path>,
    val output: Path,
    val outputContent: String
)

private data class SealedInventory(
    val platformIdentity: LegacyMigrationArtifactInventory.PlatformIdentity,
    val platformArtifacts: LegacyMigrationArtifactInventory.PlatformArtifacts,
    val closureArtifacts: List<LegacyMigrationArtifactInventory.Artifact>,
    val classRuntimeFeature: Int,
    val compileClasspathConfiguration: String
)

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

private fun receiptSha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun receiptSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun receiptAtom(value: String): String {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))
}

private fun fileReceiptSha256(path: Path): String =
    MessageDigest.getInstance("SHA-256")
        .digest(path.readBytes())
        .joinToString("") { "%02x".format(it) }


/** Twelve hex characters of a random UUID: unique enough for a stage name, short enough for Windows paths. */
private fun shortStageId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
