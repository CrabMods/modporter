package com.modporter.core.transforms.structural

import java.nio.file.Path

/**
 * Reviewed artifact profile for the actual Create source and prepared NeoForge target graphs.
 */
internal data class LegacyLootingArtifactProfile(
    val sourceIdentity: LegacyMigrationArtifactInventory.PlatformIdentity,
    val targetIdentity: LegacyMigrationArtifactInventory.PlatformIdentity,
    val sourceNamedDisplayName: String,
    val sourceNamedSha256: String,
    val sourceMergedDisplayName: String,
    val sourceMergedSha256: String,
    val sourceSourcesDisplayName: String,
    val sourceSourcesSha256: String,
    val targetNamedDisplayName: String,
    val targetNamedSha256: String,
    val targetMergedDisplayName: String,
    val targetMergedSha256: String,
    val targetSourcesDisplayName: String,
    val targetSourcesSha256: String,
    val mixinExtrasComponent: String,
    val mixinExtrasSha256: String
) {
    init {
        listOf(
            sourceNamedDisplayName,
            sourceMergedDisplayName,
            sourceSourcesDisplayName,
            targetNamedDisplayName,
            targetMergedDisplayName,
            targetSourcesDisplayName,
            mixinExtrasComponent
        ).forEach { require(it.isNotBlank()) }
        listOf(
            sourceNamedSha256,
            sourceMergedSha256,
            sourceSourcesSha256,
            targetNamedSha256,
            targetMergedSha256,
            targetSourcesSha256,
            mixinExtrasSha256
        ).forEach {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "Reviewed artifact profile contains a non-SHA-256 hash: $it"
            }
        }
    }

    companion object {
        val CREATE_FORGE_47_1_33_TO_NEOFORGE_21_1_230 =
            LegacyLootingArtifactProfile(
                sourceIdentity = LegacyMigrationArtifactInventory.PlatformIdentity(
                    family = "legacy-forge",
                    minecraftVersion = "1.20.1",
                    loaderVersion = "47.1.33",
                    resolvedLoaderCoordinate = "net.minecraftforge:forge:1.20.1-47.1.33"
                ),
                targetIdentity = LegacyMigrationArtifactInventory.PlatformIdentity(
                    family = "neoforge",
                    minecraftVersion = "1.21.1",
                    loaderVersion = "21.1.230",
                    resolvedLoaderCoordinate = "net.neoforged:neoforge:21.1.230"
                ),
                sourceNamedDisplayName =
                    "build/moddev/artifacts/forge-1.20.1-47.1.33.jar",
                sourceNamedSha256 =
                    "a1270c7d64018ba097add19d5dcee698abf8eb9971c8d5f91c7f1732e1e84ff3",
                sourceMergedDisplayName =
                    "build/moddev/artifacts/forge-1.20.1-47.1.33-merged.jar",
                sourceMergedSha256 =
                    "9a94e3d847ad67d3ba24a674daca735775fc9c7ee30292090559f186ea2191ac",
                sourceSourcesDisplayName =
                    "build/moddev/artifacts/forge-1.20.1-47.1.33-sources.jar",
                sourceSourcesSha256 =
                    "355bfbfb7688037df6fe9b1379811a851b9f394a64de7a68b8a0d10b95a5adfd",
                targetNamedDisplayName =
                    "build/moddev/artifacts/neoforge-21.1.230.jar",
                targetNamedSha256 =
                    "da2570515920276bb884d59868915e924bc80523e8771e145a11b12db41d5b2b",
                targetMergedDisplayName =
                    "build/moddev/artifacts/neoforge-21.1.230-merged.jar",
                targetMergedSha256 =
                    "92552fdc76a29894e7e6af44d0a06a10ed0e2c0e7b25545287144b5906e4a16b",
                targetSourcesDisplayName =
                    "build/moddev/artifacts/neoforge-21.1.230-sources.jar",
                targetSourcesSha256 =
                    "830d5991464c1c43d6960c2aaae6b2e3e61f83021aa192ae3783d47317d806f4",
                mixinExtrasComponent =
                    "io.github.llamalad7:mixinextras-neoforge:0.5.3",
                mixinExtrasSha256 =
                    "9822e773bd9f42d36ed53ea3d67486207291747a7bfacc29beeb92040721bc9b"
            )

        val CREATE_FORGE_47_1_33_TO_NEOFORGE_21_1_230_DEPENDENCY_PREPASS =
            CREATE_FORGE_47_1_33_TO_NEOFORGE_21_1_230.copy(
                targetNamedSha256 =
                    "0d168b646b1f1dfe490afb05a9dc51db34df456c309ab2028e508ed99c7bbd77",
                targetMergedSha256 =
                    "1e7ce155d3193663d2f2e077358ecff835de4d758689c0bb1bb6525d43b2e34d",
                targetSourcesSha256 =
                    "e3189c01eaddc7abe6c6f71931dc62bc7b1b86ec08eafe9803335236e2ba3ede"
            )

        private val REVIEWED = listOf(
            CREATE_FORGE_47_1_33_TO_NEOFORGE_21_1_230,
            CREATE_FORGE_47_1_33_TO_NEOFORGE_21_1_230_DEPENDENCY_PREPASS
        )

        fun requireReviewedTargetTuple(
            namedSha256: String,
            mergedSha256: String,
            sourcesSha256: String
        ): LegacyLootingArtifactProfile {
            val matches = REVIEWED.filter {
                it.targetNamedSha256 == namedSha256 &&
                    it.targetMergedSha256 == mergedSha256 &&
                    it.targetSourcesSha256 == sourcesSha256
            }
            require(matches.size == 1) {
                "Legacy looting target artifacts do not match exactly one reviewed tuple: " +
                    "named=$namedSha256 merged=$mergedSha256 sources=$sourcesSha256"
            }
            return matches.single()
        }

        fun requireReviewedTargetAuxiliaryTuple(
            mergedSha256: String,
            sourcesSha256: String
        ): LegacyLootingArtifactProfile {
            val matches = REVIEWED.filter {
                it.targetMergedSha256 == mergedSha256 &&
                    it.targetSourcesSha256 == sourcesSha256
            }
            require(matches.size == 1) {
                "Legacy looting target auxiliary artifacts do not match exactly one reviewed " +
                    "tuple: merged=$mergedSha256 sources=$sourcesSha256"
            }
            return matches.single()
        }

        fun requireReviewed(inventory: LegacyMigrationArtifactPair): LegacyLootingArtifactProfile {
            val source = inventory.source.platformArtifacts
            val target = inventory.target.platformArtifacts
            val profile = requireReviewedTargetTuple(
                target.named.sha256,
                target.merged.sha256,
                target.sources.sha256
            )
            require(
                inventory.source.platformIdentity == profile.sourceIdentity &&
                    inventory.target.platformIdentity == profile.targetIdentity &&
                    source.named.displayName.replace('\\', '/') ==
                    profile.sourceNamedDisplayName &&
                    source.named.sha256 == profile.sourceNamedSha256 &&
                    source.merged.displayName.replace('\\', '/') ==
                    profile.sourceMergedDisplayName &&
                    source.merged.sha256 == profile.sourceMergedSha256 &&
                    source.sources.displayName.replace('\\', '/') ==
                    profile.sourceSourcesDisplayName &&
                    source.sources.sha256 == profile.sourceSourcesSha256 &&
                    target.named.displayName.replace('\\', '/') ==
                    profile.targetNamedDisplayName &&
                    target.merged.displayName.replace('\\', '/') ==
                    profile.targetMergedDisplayName &&
                    target.sources.displayName.replace('\\', '/') ==
                    profile.targetSourcesDisplayName
            ) {
                "Legacy looting inventory identity does not match the reviewed artifact tuple"
            }
            return profile
        }
    }
}

internal object LegacyLootingArtifactInputs {
    fun resolve(
        project: Path,
        profile: LegacyLootingArtifactProfile? = null,
        templates: LegacyLootingPolicyMigration.Templates =
            LegacyLootingPolicyMigration.Templates.production()
    ): LegacyLootingPolicyMigration.Inputs =
        resolve(
            project,
            LegacyMigrationArtifactInventoryHandoff.require(project),
            profile,
            templates
        )

    fun resolve(
        project: Path,
        inventory: LegacyMigrationArtifactPair,
        profile: LegacyLootingArtifactProfile? = null,
        templates: LegacyLootingPolicyMigration.Templates =
            LegacyLootingPolicyMigration.Templates.production()
    ): LegacyLootingPolicyMigration.Inputs {
        val normalizedProject = project.toAbsolutePath().normalize()
        require(inventory.acceptsProject(normalizedProject)) {
            "Legacy looting inventory belongs to a different project"
        }
        inventory.verifyCurrentBytes("legacy looting input resolution")
        val selectedProfile = profile ?: LegacyLootingArtifactProfile.requireReviewed(inventory)
        require(inventory.source.platformIdentity == selectedProfile.sourceIdentity) {
            "Legacy looting source platform is not the reviewed Forge 1.20.1-47.1.33 graph"
        }
        require(inventory.target.platformIdentity == selectedProfile.targetIdentity) {
            "Legacy looting target platform is not the reviewed NeoForge 21.1.230 graph"
        }

        val sourceNamed = requirePlatformArtifact(
            inventory.source.platformArtifacts.named,
            selectedProfile.sourceNamedDisplayName,
            selectedProfile.sourceNamedSha256,
            "source named platform",
            LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM
        )
        requirePlatformArtifact(
            inventory.source.platformArtifacts.merged,
            selectedProfile.sourceMergedDisplayName,
            selectedProfile.sourceMergedSha256,
            "source merged platform",
            LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY
        )
        requirePlatformArtifact(
            inventory.source.platformArtifacts.sources,
            selectedProfile.sourceSourcesDisplayName,
            selectedProfile.sourceSourcesSha256,
            "source platform sources",
            LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY
        )
        val targetNamed = requirePlatformArtifact(
            inventory.target.platformArtifacts.named,
            selectedProfile.targetNamedDisplayName,
            selectedProfile.targetNamedSha256,
            "target named platform",
            LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM
        )
        val targetMerged = requirePlatformArtifact(
            inventory.target.platformArtifacts.merged,
            selectedProfile.targetMergedDisplayName,
            selectedProfile.targetMergedSha256,
            "target merged platform",
            LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY
        )
        val targetSources = requirePlatformArtifact(
            inventory.target.platformArtifacts.sources,
            selectedProfile.targetSourcesDisplayName,
            selectedProfile.targetSourcesSha256,
            "target platform sources",
            LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY
        )

        requireClosurePlatform(
            inventory.source.closureArtifacts,
            sourceNamed,
            setOf("compileClasspath", "runtimeClasspath"),
            "source"
        )
        requireClosurePlatform(
            inventory.target.closureArtifacts,
            targetNamed,
            setOf("compileClasspath", "runtimeClasspath"),
            "target"
        )
        val expectedMixinExtrasModule = moduleIdentity(selectedProfile.mixinExtrasComponent)
        val mixinExtrasCandidates = inventory.target.closureArtifacts.filter {
            moduleIdentity(it.component) == expectedMixinExtrasModule
        }
        require(mixinExtrasCandidates.size == 1) {
            "Target graph must contain one unambiguous $expectedMixinExtrasModule module"
        }
        val mixinExtras = mixinExtrasCandidates.single()
        require(mixinExtras.component == selectedProfile.mixinExtrasComponent) {
            "Target MixinExtras module has unexpected coordinate: ${mixinExtras.component}"
        }
        require(mixinExtras.sha256 == selectedProfile.mixinExtrasSha256) {
            "Target MixinExtras module has unexpected bytes: ${mixinExtras.sha256}"
        }
        require(mixinExtras.componentKind == LegacyMigrationArtifactInventory.ComponentKind.MODULE) {
            "Target MixinExtras input is not a resolved module artifact"
        }
        require("compileClasspath" in mixinExtras.configurations) {
            "Target MixinExtras input is absent from compileClasspath"
        }

        return LegacyLootingPolicyMigration.Inputs(
            project = inventory.leafProject,
            templates = templates,
            legacyPlatformJar = sourceNamed.file,
            targetPlatformJar = targetMerged.file,
            targetSourcesJar = targetSources.file,
            mixinExtrasJar = mixinExtras.file
        )
    }

    private fun requirePlatformArtifact(
        artifact: LegacyMigrationArtifactInventory.Artifact,
        expectedDisplayName: String,
        expectedSha256: String,
        label: String,
        expectedKind: LegacyMigrationArtifactInventory.ComponentKind
    ): LegacyMigrationArtifactInventory.Artifact {
        require(artifact.displayName.replace('\\', '/') == expectedDisplayName) {
            "$label has unexpected identity: ${artifact.displayName}"
        }
        require(artifact.sha256 == expectedSha256) {
            "$label has unexpected bytes: ${artifact.sha256}"
        }
        require(artifact.componentKind == expectedKind) {
            "$label has unexpected component kind: ${artifact.componentKind}"
        }
        return artifact
    }

    private fun requireClosurePlatform(
        closure: List<LegacyMigrationArtifactInventory.Artifact>,
        platform: LegacyMigrationArtifactInventory.Artifact,
        requiredConfigurations: Set<String>,
        phase: String
    ) {
        val matches = closure.filter { it.sameBytesAs(platform) }
        require(matches.size == 1) {
            "$phase closure does not contain exactly one sealed named platform artifact"
        }
        val closurePlatform = matches.single()
        require(
            closurePlatform.component == platform.component &&
                closurePlatform.componentKind == platform.componentKind &&
                closurePlatform.displayName == platform.displayName
        ) {
            "$phase closure named platform identity differs from its sealed platform record"
        }
        require(closurePlatform.configurations.containsAll(requiredConfigurations)) {
            "$phase named platform is not present on compileClasspath and runtimeClasspath"
        }
    }

    private fun moduleIdentity(component: String): String? {
        val parts = component.split(':')
        return if (parts.size >= 3) {
            parts.take(2).joinToString(":")
        } else {
            null
        }
    }
}
