package com.modporter.core.transforms.structural

import com.modporter.core.transforms.build.BuildDependencyDecisionReceipt
import java.security.MessageDigest
import java.util.Base64

/**
 * One audited, whole-closure compatibility profile.  It is deliberately exact: an artifact,
 * decision, class, method, runtime, or active-owner change produces no profile receipt.
 */
internal data class ReviewedLegacyPlantMethodEvidence(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val codeAttributeSha256: String?
) {
    fun canonicalText(): String = listOf(
        atom(name),
        atom(descriptor),
        accessFlags.toString(),
        codeAttributeSha256 ?: "absent"
    ).joinToString("|")
}

internal data class ReviewedLegacyPlantClassEvidence(
    val configuration: String,
    val owner: String,
    val accessFlags: Int,
    val classSha256: String,
    val methods: List<ReviewedLegacyPlantMethodEvidence>
)

internal data class ReviewedLegacyPlantMethodFingerprint(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val codeAttributeSha256: String
) {
    fun matches(method: ReviewedLegacyPlantMethodEvidence): Boolean =
        name == method.name &&
            descriptor == method.descriptor &&
            accessFlags == method.accessFlags &&
            codeAttributeSha256 == method.codeAttributeSha256

    fun canonicalText(): String = listOf(
        atom(name),
        atom(descriptor),
        accessFlags.toString(),
        codeAttributeSha256
    ).joinToString("|")
}

internal data class ReviewedLegacyPlantClassFingerprint(
    val owner: String,
    val accessFlags: Int,
    val classSha256: String,
    val methods: List<ReviewedLegacyPlantMethodFingerprint>
) {
    fun matches(evidence: ReviewedLegacyPlantClassEvidence): Boolean =
        owner == evidence.owner &&
            accessFlags == evidence.accessFlags &&
            classSha256 == evidence.classSha256 &&
            methods.all { expected -> evidence.methods.any(expected::matches) }

    fun canonicalText(): String = buildList {
        add("owner=${atom(owner)}")
        add("accessFlags=$accessFlags")
        add("classSha256=$classSha256")
        methods.sortedBy(ReviewedLegacyPlantMethodFingerprint::canonicalText).forEach {
            add("method=${atom(it.canonicalText())}")
        }
    }.joinToString("\n", postfix = "\n")
}

internal data class ReviewedLegacyPlantAdapterProfileReceipt(
    val profileId: String,
    val sourceComponent: String,
    val sourceArtifactSha256: String,
    val sourceSelector: String,
    val sourceConfigurations: Set<String>,
    val targetComponent: String,
    val targetArtifactSha256: String,
    val targetConfiguration: String,
    val targetTransitive: Boolean,
    val catalogEntrySha256: String,
    val decisionSha256: String,
    val runtimeSourceSha256: String,
    val runtimeJarSha256: String,
    val sourceClasses: List<ReviewedLegacyPlantClassFingerprint>,
    val targetClasses: List<ReviewedLegacyPlantClassFingerprint>,
    val roles: List<String>,
    val requiredRuntimeJavaNames: Set<String>,
    val requiredRuntimeResources: Set<String>
) {
    init {
        require(profileId.isNotBlank())
        require(sourceConfigurations == setOf("compileClasspath"))
        require(targetConfiguration == "compileOnly" && targetTransitive)
        listOf(
            sourceArtifactSha256,
            targetArtifactSha256,
            catalogEntrySha256,
            decisionSha256,
            runtimeSourceSha256,
            runtimeJarSha256
        ).forEach { require(it.matches(Regex("[0-9a-f]{64}"))) }
        require(sourceClasses.map { it.owner }.distinct().size == sourceClasses.size)
        require(targetClasses.map { it.owner }.distinct().size == targetClasses.size)
        require(sourceClasses.size == 3 && targetClasses.size == 3)
        require(roles.size == 3 && roles.count { it.startsWith("plant-producer|") } == 2)
        require(roles.count { it.startsWith("plant-consumer-restoration|") } == 1)
        require(requiredRuntimeJavaNames.size == 14)
        require(requiredRuntimeResources == setOf(REQUIRED_MIXIN_CONFIG))
    }

    val sha256: String get() = profileSha256(canonicalText())

    fun matchesSource(
        artifact: ResolvedArtifact,
        evidence: List<ReviewedLegacyPlantClassEvidence>,
        runtimeIdentity: CanonicalRuntimeIdentity
    ): Boolean =
        artifact.component == sourceComponent &&
            artifact.sha256 == sourceArtifactSha256 &&
            matchesRuntime(runtimeIdentity) &&
            matchesClasses(artifact, sourceClasses, evidence)

    fun matchesTarget(
        artifact: ResolvedArtifact,
        evidence: List<ReviewedLegacyPlantClassEvidence>,
        runtimeIdentity: CanonicalRuntimeIdentity
    ): Boolean =
        artifact.component == targetComponent &&
            artifact.sha256 == targetArtifactSha256 &&
            matchesRuntime(runtimeIdentity) &&
            matchesClasses(artifact, targetClasses, evidence)

    fun matchesDecision(decision: BuildDependencyDecisionReceipt): Boolean =
        decision.sha256 == decisionSha256 &&
            decision.catalogEntrySha256 == catalogEntrySha256 &&
            decision.source.component == sourceComponent &&
            decision.source.artifactSha256 == sourceArtifactSha256 &&
            decision.source.configurations == sourceConfigurations &&
            decision.sourceSelector == sourceSelector &&
            decision.targetCoordinates.singleOrNull()?.let { target ->
                target.selector == targetComponent &&
                    target.configuration == targetConfiguration &&
                    target.transitive == targetTransitive
            } == true

    fun canonicalText(): String = buildList {
        add("schema=reviewed-legacy-plant-adapter-profile-v1")
        add("profileId=${atom(profileId)}")
        add("sourceComponent=${atom(sourceComponent)}")
        add("sourceArtifactSha256=$sourceArtifactSha256")
        add("sourceSelector=${atom(sourceSelector)}")
        sourceConfigurations.sorted().forEach { add("sourceConfiguration=${atom(it)}") }
        add("targetComponent=${atom(targetComponent)}")
        add("targetArtifactSha256=$targetArtifactSha256")
        add("targetConfiguration=${atom(targetConfiguration)}")
        add("targetTransitive=$targetTransitive")
        add("catalogEntrySha256=$catalogEntrySha256")
        add("decisionSha256=$decisionSha256")
        add("runtimeSourceSha256=$runtimeSourceSha256")
        add("runtimeJarSha256=$runtimeJarSha256")
        sourceClasses.sortedBy(ReviewedLegacyPlantClassFingerprint::owner).forEach {
            add("sourceClass=${atom(it.canonicalText())}")
        }
        targetClasses.sortedBy(ReviewedLegacyPlantClassFingerprint::owner).forEach {
            add("targetClass=${atom(it.canonicalText())}")
        }
        roles.sorted().forEach { add("role=${atom(it)}") }
        requiredRuntimeJavaNames.sorted().forEach { add("runtimeJava=${atom(it)}") }
        requiredRuntimeResources.sorted().forEach { add("runtimeResource=${atom(it)}") }
    }.joinToString("\n", postfix = "\n")

    private fun matchesRuntime(runtimeIdentity: CanonicalRuntimeIdentity): Boolean =
        runtimeIdentity.sourceSha256 == runtimeSourceSha256 &&
            runtimeIdentity.jarSha256 == runtimeJarSha256 &&
            runtimeIdentity.generatedSourceHashes().keys == requiredRuntimeJavaNames &&
            runtimeIdentity.generatedResourceHashes().keys == requiredRuntimeResources

    private fun matchesClasses(
        artifact: ResolvedArtifact,
        expected: List<ReviewedLegacyPlantClassFingerprint>,
        evidence: List<ReviewedLegacyPlantClassEvidence>
    ): Boolean = artifact.configurations.all { configuration ->
        val selected = evidence.filter { it.configuration == configuration }
        expected.all { fingerprint ->
            selected.singleOrNull { it.owner == fingerprint.owner }
                ?.let(fingerprint::matches) == true
        }
    }

    companion object {
        const val REQUIRED_MIXIN_CONFIG = "modporter-legacy-plant-dt.mixins.json"
    }
}

internal object ReviewedLegacyPlantAdapterProfiles {
    const val SOURCE_COMPONENT =
        "com.ferreusveritas.dynamictrees:DynamicTrees-1.20.1:1.3.0-BETA13"
    const val SOURCE_ARTIFACT_SHA256 =
        "b0eecc4dccc9e14f1738f2c77fa50fda2289a58dd4d2bf87aa4e4ba3f89281df"
    const val SOURCE_SELECTOR = "com.ferreusveritas.dynamictrees:DynamicTrees"
    const val TARGET_COMPONENT =
        "com.dtteam.dynamictrees:dynamictrees-neoforge-1.21.1:1.7.2"
    const val TARGET_ARTIFACT_SHA256 =
        "ef94737b99e68a0b489b56f901fcc71a3ac8e7a16e3a22ac8413c5f66df566f0"
    const val CATALOG_ENTRY_SHA256 =
        "caa05f56be0cee5c93fb403ee415194ad611e411e98fabf5e7a2a4b97d501e19"
    const val DECISION_SHA256 =
        "7023416bb11eac451647fe4504a9efab48ce4c997f726c06ccf0c55d54762192"

    fun forRuntime(runtimeIdentity: CanonicalRuntimeIdentity): ReviewedLegacyPlantAdapterProfileReceipt =
        ReviewedLegacyPlantAdapterProfileReceipt(
            profileId = "dynamic-trees-1.3.0-beta13-to-1.7.2-whole-closure-v1",
            sourceComponent = SOURCE_COMPONENT,
            sourceArtifactSha256 = SOURCE_ARTIFACT_SHA256,
            sourceSelector = SOURCE_SELECTOR,
            sourceConfigurations = setOf("compileClasspath"),
            targetComponent = TARGET_COMPONENT,
            targetArtifactSha256 = TARGET_ARTIFACT_SHA256,
            targetConfiguration = "compileOnly",
            targetTransitive = true,
            catalogEntrySha256 = CATALOG_ENTRY_SHA256,
            decisionSha256 = DECISION_SHA256,
            runtimeSourceSha256 = runtimeIdentity.sourceSha256,
            runtimeJarSha256 = runtimeIdentity.jarSha256,
            sourceClasses = SOURCE_CLASSES,
            targetClasses = TARGET_CLASSES,
            roles = listOf(
                "plant-producer|com.ferreusveritas.dynamictrees.block.DynamicSaplingBlock|getPlant|bootstrap:ReviewedExternalPlantAdapters#dynamicSapling",
                "plant-producer|com.ferreusveritas.dynamictrees.item.Seed|getPlant|bootstrap:ReviewedExternalPlantAdapters#seed",
                "plant-consumer-restoration|com.ferreusveritas.dynamictrees.systems.genfeature.MushroomRotGenFeature|canSustainMushroom->postRot|mixin:LegacyPlantMushroomRotMixin"
            ),
            requiredRuntimeJavaNames = runtimeIdentity.generatedSourceHashes().keys,
            requiredRuntimeResources = setOf(
                ReviewedLegacyPlantAdapterProfileReceipt.REQUIRED_MIXIN_CONFIG
            )
        )

    fun expectedArtifact(phase: InventoryPhase, artifact: ResolvedArtifact): Boolean =
        when (phase) {
            InventoryPhase.SOURCE -> artifact.component == SOURCE_COMPONENT
            InventoryPhase.TARGET -> artifact.component == TARGET_COMPONENT
        }

    fun matchArtifact(
        phase: InventoryPhase,
        artifact: ResolvedArtifact,
        evidence: List<ReviewedLegacyPlantClassEvidence>,
        runtimeIdentity: CanonicalRuntimeIdentity
    ): ReviewedLegacyPlantAdapterProfileReceipt? {
        val profile = forRuntime(runtimeIdentity)
        return profile.takeIf {
            when (phase) {
                InventoryPhase.SOURCE -> it.matchesSource(artifact, evidence, runtimeIdentity)
                InventoryPhase.TARGET -> it.matchesTarget(artifact, evidence, runtimeIdentity)
            }
        }
    }

    fun selectForTargetPlan(
        sourceGraph: ResolvedGraph,
        decisions: List<BuildDependencyDecisionReceipt>,
        runtimeIdentity: CanonicalRuntimeIdentity
    ): List<ReviewedLegacyPlantAdapterProfileReceipt> {
        val sources = sourceGraph.artifacts.filter {
            it.component == SOURCE_COMPONENT && it.sha256 == SOURCE_ARTIFACT_SHA256
        }
        if (sources.isEmpty()) return emptyList()
        require(sources.size == 1) { "Reviewed Dynamic Trees source artifact is ambiguous" }
        val profile = forRuntime(runtimeIdentity)
        val matches = decisions.filter(profile::matchesDecision)
        require(matches.size == 1) {
            "Reviewed Dynamic Trees source requires its exact mapping decision"
        }
        return listOf(profile)
    }

    private val SOURCE_CLASSES = listOf(
        ReviewedLegacyPlantClassFingerprint(
            "com/ferreusveritas/dynamictrees/block/DynamicSaplingBlock", 33,
            "7283f1d8ea2146c95a35a1fac100ab89a1e803a96d0840e83688231c1b6e784c",
            listOf(ReviewedLegacyPlantMethodFingerprint(
                "getPlant",
                "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                1, "35480c6fa0555946ca98c9d822852ab85f37e444e8f2d90c4919fac7b966dd08"
            ))
        ),
        ReviewedLegacyPlantClassFingerprint(
            "com/ferreusveritas/dynamictrees/item/Seed", 33,
            "bb7d33411d0fcb477ff69e6e69774c05be00b7eb7a697b19e18354b9ebc03e73",
            listOf(ReviewedLegacyPlantMethodFingerprint(
                "getPlant",
                "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                1, "a57e301c133b902e6fcb790daa4e53b72fe13b7b338238021c8f12cb504d6b4e"
            ))
        ),
        ReviewedLegacyPlantClassFingerprint(
            "com/ferreusveritas/dynamictrees/systems/genfeature/MushroomRotGenFeature", 33,
            "7e59b6e0692b1469d99a3d11762046e72458d75d626988d7c46e4f878f97871b",
            listOf(ReviewedLegacyPlantMethodFingerprint(
                "canSustainMushroom",
                "(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)Z",
                2, "80a38a39dda13cd79cf0907ee4f70541eb8bc6391f77c044fead651cd3ef15b3"
            ))
        )
    )

    private val TARGET_CLASSES = listOf(
        ReviewedLegacyPlantClassFingerprint(
            "com/dtteam/dynamictrees/block/sapling/DynamicSaplingBlock", 33,
            "096ff90b12032d3734a1cde68236cfb419119991fb8cec816f0197529148b5f9",
            listOf(ReviewedLegacyPlantMethodFingerprint(
                "canSurvive",
                "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
                1, "2dd4658120d28618e215b08fe9a59001078eef7cb406efd1934c5c74b2f45c17"
            ))
        ),
        ReviewedLegacyPlantClassFingerprint(
            "com/dtteam/dynamictrees/item/Seed", 33,
            "340725f2c959c26074790d6821b4289c47fc464ed21585f737438d7eee0df019",
            listOf(
                ReviewedLegacyPlantMethodFingerprint(
                    "doPlanting",
                    "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z",
                    1, "2985e32fa2cdcb0a5455aecdf745d009349ac58ee80a81351eaea97441befb51"
                ),
                ReviewedLegacyPlantMethodFingerprint(
                    "onItemUsePlantSeed",
                    "(Lnet/minecraft/world/item/context/UseOnContext;Z)Lnet/minecraft/world/InteractionResult;",
                    1, "f3ad9fba222a4301d325d4be6dd569090da443c475045eb35ed3090bdfce30a1"
                )
            )
        ),
        ReviewedLegacyPlantClassFingerprint(
            "com/dtteam/dynamictrees/systems/genfeature/MushroomRotGenFeature", 33,
            "dc3abd8cba01da6b1ac2c7ffaf3d2d83a55e7c6ebb520dfd8e09f3d43178d445",
            listOf(ReviewedLegacyPlantMethodFingerprint(
                "postRot",
                "(Lcom/dtteam/dynamictrees/systems/genfeature/GenFeatureConfiguration;Lcom/dtteam/dynamictrees/systems/genfeature/context/PostRotContext;)Z",
                4, "88f87593f393f808ad3cfd947a20900ab22f3b62fc040e82886ec59f2c3c5925"
            ))
        )
    )
}

private fun atom(value: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

private fun profileSha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
