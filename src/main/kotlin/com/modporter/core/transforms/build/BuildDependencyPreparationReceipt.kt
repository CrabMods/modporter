package com.modporter.core.transforms.build

import com.modporter.core.pipeline.PassResult
import java.security.MessageDigest
import java.util.Base64

internal data class BuildResolvedSourceDependency(
    val component: String,
    val configurations: Set<String>,
    val artifactSha256: String
) {
    init {
        val parts = component.split(':')
        require(parts.size in 3..4 && parts.all(String::isNotBlank)) {
            "Resolved source component must be group:artifact:version[:classifier]: $component"
        }
        require(configurations.isNotEmpty() && configurations.none(String::isBlank))
        requireSha256(artifactSha256, "source artifact")
    }

    val group: String get() = component.substringBefore(':')
    val artifact: String get() = component.split(':')[1]
    val version: String get() = component.split(':')[2]
    val classifier: String? get() = component.split(':').getOrNull(3)
}

internal data class BuildTargetCoordinate(
    val configuration: String,
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String?,
    val transitive: Boolean
) {
    init {
        require(configuration.isNotBlank())
        require(group.isNotBlank())
        require(artifact.isNotBlank())
        require(version.isNotBlank())
        require(classifier?.isNotBlank() != false)
    }

    val selector: String
        get() = listOfNotNull(group, artifact, version, classifier).joinToString(":")

    fun canonicalText(): String = buildList {
        add("configuration=${receiptAtom(configuration)}")
        add("group=${receiptAtom(group)}")
        add("artifact=${receiptAtom(artifact)}")
        add("version=${receiptAtom(version)}")
        classifier?.let { add("classifier=${receiptAtom(it)}") }
        add("transitive=$transitive")
    }.joinToString("\n", postfix = "\n")

    companion object {
        fun from(coordinate: NeoForgeCoord): BuildTargetCoordinate {
            val parts = coordinate.coord.split(':')
            require(parts.size in 3..4 && parts.all(String::isNotBlank)) {
                "Mapped target coordinate must be group:artifact:version[:classifier]: " +
                    coordinate.coord
            }
            return BuildTargetCoordinate(
                configuration = coordinate.config,
                group = parts[0],
                artifact = parts[1],
                version = parts[2],
                classifier = parts.getOrNull(3),
                transitive = coordinate.transitive
            )
        }
    }
}

internal enum class BuildDependencyDecisionKind(val receiptValue: String) {
    MAPPED("mapped"),
    REMOVE("remove")
}

internal data class BuildDependencyDecisionReceipt(
    val source: BuildResolvedSourceDependency,
    val sourceSelector: String,
    val kind: BuildDependencyDecisionKind,
    val targetCoordinates: List<BuildTargetCoordinate>,
    val packagePrefixes: List<String>,
    val catalogEntrySha256: String
) {
    init {
        require(sourceSelector.isNotBlank())
        requireSha256(catalogEntrySha256, "dependency catalog entry")
        require(packagePrefixes.isNotEmpty())
        require(packagePrefixes.distinct().size == packagePrefixes.size)
        require(packagePrefixes.none(String::isBlank))
        require(targetCoordinates.distinct().size == targetCoordinates.size)
        if (kind == BuildDependencyDecisionKind.REMOVE) {
            require(targetCoordinates.isEmpty())
        } else {
            require(targetCoordinates.isNotEmpty())
        }
    }

    val sha256: String get() = buildReceiptSha256(canonicalText())

    fun canonicalText(): String = buildList {
        add("schema=build-dependency-decision-v1")
        add("sourceComponent=${receiptAtom(source.component)}")
        add("sourceArtifactSha256=${source.artifactSha256}")
        source.configurations.sorted().forEach {
            add("sourceConfiguration=${receiptAtom(it)}")
        }
        add("sourceSelector=${receiptAtom(sourceSelector)}")
        add("status=${kind.receiptValue}")
        add("catalogEntrySha256=$catalogEntrySha256")
        targetCoordinates.sortedBy(BuildTargetCoordinate::canonicalText).forEach {
            add("target=${receiptAtom(it.canonicalText())}")
        }
        packagePrefixes.sorted().forEach {
            add("packagePrefix=${receiptAtom(it)}")
        }
    }.joinToString("\n", postfix = "\n")
}

internal data class BuildFileHashTransition(
    val path: String,
    val beforeSha256: String?,
    val afterSha256: String?
) {
    init {
        requireReceiptPath(path)
        require(beforeSha256 != null || afterSha256 != null)
        beforeSha256?.let { requireSha256(it, "before file") }
        afterSha256?.let { requireSha256(it, "after file") }
        require(beforeSha256 != afterSha256)
    }

    fun canonicalText(): String =
        "${receiptAtom(path)}|${beforeSha256 ?: "missing"}|${afterSha256 ?: "missing"}"
}

internal data class BuildInertRewriteReceipt(
    val path: String,
    val owner: String,
    val memberSignature: String,
    val removedTypes: List<String>,
    val beforeNodeSha256: String,
    val afterNodeSha256: String?,
    val afterOwnerSha256: String,
    val verifiedPostcondition: String
) {
    init {
        requireReceiptPath(path)
        require(owner.isNotBlank())
        require(memberSignature.isNotBlank())
        require(removedTypes.isNotEmpty() && removedTypes.distinct().size == removedTypes.size)
        requireSha256(beforeNodeSha256, "before AST node")
        afterNodeSha256?.let { requireSha256(it, "after AST node") }
        requireSha256(afterOwnerSha256, "after owner AST node")
        require(verifiedPostcondition in setOf(
            "default-return-body",
            "removed-signature-member",
            "removed-constructor-statements",
            "empty-initializer"
        ))
        if (verifiedPostcondition == "removed-signature-member") {
            require(afterNodeSha256 == null)
        } else {
            require(afterNodeSha256 != null)
        }
    }

    val sha256: String get() = buildReceiptSha256(canonicalText())

    fun canonicalText(): String = buildList {
        add("schema=build-inert-rewrite-v1")
        add("path=${receiptAtom(path)}")
        add("owner=${receiptAtom(owner)}")
        add("memberSignature=${receiptAtom(memberSignature)}")
        removedTypes.sorted().forEach { add("removedType=${receiptAtom(it)}") }
        add("beforeNodeSha256=$beforeNodeSha256")
        add("afterNodeSha256=${afterNodeSha256 ?: "absent"}")
        add("afterOwnerSha256=$afterOwnerSha256")
        add("verifiedPostcondition=$verifiedPostcondition")
    }.joinToString("\n", postfix = "\n")
}

internal data class RemovedDependencySourceReceipt(
    val path: String,
    val beforeSourceSha256: String,
    val afterSourceSha256: String,
    val removedImports: List<String>,
    val packagePrefixes: List<String>,
    val inertRewrites: List<BuildInertRewriteReceipt>
) {
    init {
        requireReceiptPath(path)
        requireSha256(beforeSourceSha256, "before source")
        requireSha256(afterSourceSha256, "after source")
        require(beforeSourceSha256 != afterSourceSha256)
        require(removedImports.isNotEmpty() && removedImports.distinct().size == removedImports.size)
        require(packagePrefixes.isNotEmpty() && packagePrefixes.distinct().size == packagePrefixes.size)
        require(inertRewrites.isNotEmpty())
    }
}

internal data class RemovedDependencyStubResult(
    val changes: List<com.modporter.core.pipeline.Change>,
    val sources: List<RemovedDependencySourceReceipt>
)

internal data class BuildRemovedDependencyReceipt(
    val sourceComponent: String,
    val sourceArtifactSha256: String,
    val decisionSha256: String,
    val packagePrefixes: List<String>,
    val changedSourceHashes: Map<String, Pair<String, String>>,
    val inertRewrites: List<BuildInertRewriteReceipt>,
    val remainingRemovedPackageReferences: List<String>
) {
    init {
        require(sourceComponent.isNotBlank())
        requireSha256(sourceArtifactSha256, "removed source artifact")
        requireSha256(decisionSha256, "dependency decision")
        require(packagePrefixes.isNotEmpty())
        require(changedSourceHashes.isNotEmpty())
        changedSourceHashes.forEach { (path, hashes) ->
            requireReceiptPath(path)
            requireSha256(hashes.first, "before changed source")
            requireSha256(hashes.second, "after changed source")
            require(hashes.first != hashes.second)
        }
        require(inertRewrites.isNotEmpty())
    }

    val sha256: String get() = buildReceiptSha256(canonicalText())

    fun canonicalText(): String = buildList {
        add("schema=build-removed-dependency-v1")
        add("sourceComponent=${receiptAtom(sourceComponent)}")
        add("sourceArtifactSha256=$sourceArtifactSha256")
        add("decisionSha256=$decisionSha256")
        packagePrefixes.sorted().forEach { add("packagePrefix=${receiptAtom(it)}") }
        changedSourceHashes.toSortedMap().forEach { (path, hashes) ->
            add("changedSource=${receiptAtom(path)}|${hashes.first}|${hashes.second}")
        }
        inertRewrites.sortedBy(BuildInertRewriteReceipt::canonicalText).forEach {
            add("inertRewrite=${receiptAtom(it.canonicalText())}|${it.sha256}")
        }
        remainingRemovedPackageReferences.sorted().forEach {
            add("remainingRemovedPackageReference=${receiptAtom(it)}")
        }
    }.joinToString("\n", postfix = "\n")
}

internal data class BuildDependencyPreparationReceipt(
    val result: PassResult,
    val mappingResourceSha256: String,
    val decisions: List<BuildDependencyDecisionReceipt>,
    val removals: List<BuildRemovedDependencyReceipt>,
    val buildFilesBefore: Map<String, String>,
    val buildFilesAfter: Map<String, String>,
    val resourceFilesBefore: Map<String, String>,
    val resourceFilesAfter: Map<String, String>,
    val sourceFilesBefore: Map<String, String>,
    val sourceFilesAfter: Map<String, String>,
    val changedBuildFiles: List<BuildFileHashTransition>,
    val changedResourceFiles: List<BuildFileHashTransition>,
    val changedSourceFiles: List<BuildFileHashTransition>
) {
    init {
        requireSha256(mappingResourceSha256, "dependency mapping resource")
        require(decisions.map(BuildDependencyDecisionReceipt::sha256).distinct().size ==
            decisions.size)
        require(removals.map(BuildRemovedDependencyReceipt::sha256).distinct().size ==
            removals.size)
        val removalDecisionHashes = decisions.filter {
            it.kind == BuildDependencyDecisionKind.REMOVE
        }.map(BuildDependencyDecisionReceipt::sha256).toSet()
        require(removals.all { it.decisionSha256 in removalDecisionHashes })
        (buildFilesBefore.values + buildFilesAfter.values +
            resourceFilesBefore.values + resourceFilesAfter.values +
            sourceFilesBefore.values + sourceFilesAfter.values).forEach {
            requireSha256(it, "snapshot file")
        }
        require(changedBuildFiles == fileTransitions(buildFilesBefore, buildFilesAfter))
        require(changedResourceFiles == fileTransitions(resourceFilesBefore, resourceFilesAfter))
        require(changedSourceFiles == fileTransitions(sourceFilesBefore, sourceFilesAfter))
    }

    val sha256: String get() = buildReceiptSha256(canonicalText())

    fun canonicalText(): String = buildList {
        add("schema=build-dependency-preparation-v2")
        add("mappingResourceSha256=$mappingResourceSha256")
        decisions.sortedBy(BuildDependencyDecisionReceipt::sha256).forEach {
            add("decision=${receiptAtom(it.canonicalText())}|${it.sha256}")
        }
        removals.sortedBy(BuildRemovedDependencyReceipt::sha256).forEach {
            add("removal=${receiptAtom(it.canonicalText())}|${it.sha256}")
        }
        buildFilesBefore.toSortedMap().forEach { (path, hash) ->
            add("buildBefore=${receiptAtom(path)}|$hash")
        }
        buildFilesAfter.toSortedMap().forEach { (path, hash) ->
            add("buildAfter=${receiptAtom(path)}|$hash")
        }
        resourceFilesBefore.toSortedMap().forEach { (path, hash) ->
            add("resourceBefore=${receiptAtom(path)}|$hash")
        }
        resourceFilesAfter.toSortedMap().forEach { (path, hash) ->
            add("resourceAfter=${receiptAtom(path)}|$hash")
        }
        sourceFilesBefore.toSortedMap().forEach { (path, hash) ->
            add("sourceBefore=${receiptAtom(path)}|$hash")
        }
        sourceFilesAfter.toSortedMap().forEach { (path, hash) ->
            add("sourceAfter=${receiptAtom(path)}|$hash")
        }
    }.joinToString("\n", postfix = "\n")
}

internal fun fileTransitions(
    before: Map<String, String>,
    after: Map<String, String>
): List<BuildFileHashTransition> =
    (before.keys + after.keys).sorted().mapNotNull { path ->
        val oldHash = before[path]
        val newHash = after[path]
        if (oldHash == newHash) null
        else BuildFileHashTransition(path, oldHash, newHash)
    }

internal fun buildReceiptSha256(text: String): String =
    buildReceiptSha256(text.toByteArray(Charsets.UTF_8))

internal fun buildReceiptSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun requireSha256(value: String, label: String) {
    require(value.matches(Regex("[0-9a-f]{64}"))) { "$label hash is not SHA-256: $value" }
}

private fun requireReceiptPath(path: String) {
    require(path.isNotBlank() && '\\' !in path && !path.startsWith('/') &&
        path.split('/').none { it == ".." }) {
        "Receipt path must be a normalized project-relative path: $path"
    }
}

private fun receiptAtom(value: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))
