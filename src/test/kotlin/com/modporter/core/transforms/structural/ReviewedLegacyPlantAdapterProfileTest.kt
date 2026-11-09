package com.modporter.core.transforms.structural

import com.modporter.core.transforms.build.BuildDependencyDecisionKind
import com.modporter.core.transforms.build.BuildDependencyDecisionReceipt
import com.modporter.core.transforms.build.BuildResolvedSourceDependency
import com.modporter.core.transforms.build.BuildTargetCoordinate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedLegacyPlantAdapterProfileTest {
    @Test
    fun `reviewed Dynamic Trees receipt binds three surfaces and whole runtime`() {
        val runtime = CanonicalRuntimeIdentity.fromProductionResources()
        val profile = ReviewedLegacyPlantAdapterProfiles.forRuntime(runtime)

        assertEquals(
            ReviewedLegacyPlantAdapterProfiles.SOURCE_ARTIFACT_SHA256,
            profile.sourceArtifactSha256
        )
        assertEquals(
            ReviewedLegacyPlantAdapterProfiles.TARGET_ARTIFACT_SHA256,
            profile.targetArtifactSha256
        )
        assertEquals(3, profile.sourceClasses.size)
        assertEquals(3, profile.targetClasses.size)
        assertEquals(2, profile.roles.count { it.startsWith("plant-producer|") })
        assertEquals(
            1,
            profile.roles.count { it.startsWith("plant-consumer-restoration|") }
        )
        assertEquals(runtime.generatedSourceHashes().keys, profile.requiredRuntimeJavaNames)
        assertEquals(
            runtime.generatedResourceHashes().keys,
            profile.requiredRuntimeResources
        )
        assertTrue(
            profile.sourceClasses.any {
                it.owner.endsWith("/MushroomRotGenFeature") &&
                    it.methods.single().name == "canSustainMushroom"
            }
        )
        assertTrue(
            profile.targetClasses.any {
                it.owner.endsWith("/MushroomRotGenFeature") &&
                    it.methods.single().name == "postRot"
            }
        )
        assertTrue(profile.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `reviewed receipt rejects a missing consumer or sentinel resource`() {
        val profile = ReviewedLegacyPlantAdapterProfiles.forRuntime(
            CanonicalRuntimeIdentity.fromProductionResources()
        )

        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(roles = profile.roles.filterNot {
                it.startsWith("plant-consumer-restoration|")
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(requiredRuntimeResources = emptySet())
        }
    }
    @Test
    fun `reviewed profile seals exact dependency decision coordinates`() {
        val profile = ReviewedLegacyPlantAdapterProfiles.forRuntime(
            CanonicalRuntimeIdentity.fromProductionResources()
        )
        val decision = BuildDependencyDecisionReceipt(
            source = BuildResolvedSourceDependency(
                component = ReviewedLegacyPlantAdapterProfiles.SOURCE_COMPONENT,
                configurations = setOf("compileClasspath"),
                artifactSha256 = ReviewedLegacyPlantAdapterProfiles.SOURCE_ARTIFACT_SHA256
            ),
            sourceSelector = ReviewedLegacyPlantAdapterProfiles.SOURCE_SELECTOR,
            kind = BuildDependencyDecisionKind.MAPPED,
            targetCoordinates = listOf(
                BuildTargetCoordinate(
                    configuration = "compileOnly",
                    group = "com.dtteam.dynamictrees",
                    artifact = "dynamictrees-neoforge-1.21.1",
                    version = "1.7.2",
                    classifier = null,
                    transitive = true
                )
            ),
            packagePrefixes = listOf(
                "com.ferreusveritas.dynamictrees.",
                "com.dtteam.dynamictrees."
            ),
            catalogEntrySha256 = ReviewedLegacyPlantAdapterProfiles.CATALOG_ENTRY_SHA256
        )

        assertEquals(ReviewedLegacyPlantAdapterProfiles.DECISION_SHA256, decision.sha256)
        assertTrue(profile.matchesDecision(decision))
        assertFalse(
            profile.matchesDecision(
                decision.copy(
                    targetCoordinates = decision.targetCoordinates.map {
                        it.copy(transitive = false)
                    }
                )
            )
        )
    }

}
