package com.modporter.core.transforms.structural

import kotlin.test.Test
import kotlin.test.assertFailsWith

class LegacyPlantDependencyResourceOwnershipTest {
    @Test
    fun `reviewed dependency prepass owns exactly the access transformer transition`() {
        val source = ReviewedPlantPlatformFingerprints.all.single {
            it.phase == InventoryPhase.SOURCE
        }
        val target = ReviewedPlantPlatformFingerprints.all.single {
            it.phase == InventoryPhase.TARGET &&
                it.artifactSha256 ==
                "0d168b646b1f1dfe490afb05a9dc51db34df456c309ab2028e508ed99c7bbd77"
        }
        val path = "src/main/resources/META-INF/accesstransformer.cfg"
        val before = "cde51eb3f6dd81c81ac37ead89078e78de9288ce36a8c4de53eab6cf2d9c9db0"
        val after = "408a784139aa4920dc9f65cbfe8bdc35f81d4340c38599b7e646b382a6c64382"
        val exact = mapOf<String, Pair<String?, String?>>(path to (before to after))

        ReviewedPlantPlatformFingerprints.requireDependencyOnlyResourceTransitions(
            source,
            target,
            exact
        )

        val rejected: List<Pair<String, Map<String, Pair<String?, String?>>>> = listOf(
            "missing transition" to emptyMap(),
            "unexpected addition" to exact.plus(
                "src/main/resources/unreviewed.txt" to (null to "a".repeat(64))
            ),
            "removal" to mapOf(path to (before to null)),
            "before hash drift" to mapOf(path to ("b".repeat(64) to after)),
            "after hash drift" to mapOf(path to (before to "c".repeat(64)))
        )
        rejected.forEach { (label, transitions) ->
            assertFailsWith<IllegalArgumentException>(label) {
                ReviewedPlantPlatformFingerprints.requireDependencyOnlyResourceTransitions(
                    source,
                    target,
                    transitions
                )
            }
        }

        assertFailsWith<IllegalArgumentException>("target platform drift") {
            ReviewedPlantPlatformFingerprints.requireDependencyOnlyResourceTransitions(
                source,
                target.copy(artifactSha256 = "d".repeat(64)),
                exact
            )
        }
    }
}
