package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LegacyLootingArtifactInputsTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `sealed source and target inventories resolve exact production input roles`() {
        val fixture = fixture()

        val inputs = LegacyLootingArtifactInputs.resolve(
            fixture.ownerProject,
            fixture.inventory,
            fixture.profile
        )

        assertEquals(fixture.leafProject, inputs.project)
        assertEquals(fixture.sourceNamed.file, inputs.legacyPlatformJar)
        assertEquals(fixture.targetMerged.file, inputs.targetPlatformJar)
        assertEquals(fixture.targetSources.file, inputs.targetSourcesJar)
        assertEquals(fixture.mixinExtras.file, inputs.mixinExtrasJar)
    }

    @Test
    fun `source platform identity cannot substitute a different Forge patch line`() {
        val fixture = fixture()
        val wrongSource = LegacyMigrationArtifactInventory.Source(
            fixture.inventory.source.platformIdentity.copy(
                loaderVersion = "47.4.10",
                resolvedLoaderCoordinate = "net.minecraftforge:forge:1.20.1-47.4.10"
            ),
            fixture.inventory.source.platformArtifacts,
            fixture.inventory.source.closureArtifacts,
            fixture.inventory.source.classRuntimeFeature,
            fixture.inventory.source.compileClasspathConfiguration
        )
        val inventory = LegacyMigrationArtifactPair(
            fixture.ownerProject,
            fixture.leafProject,
            fixture.sealedRunRoot,
            wrongSource,
            fixture.inventory.target
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyLootingArtifactInputs.resolve(
                fixture.ownerProject,
                inventory,
                fixture.profile
            )
        }

        assertTrue(failure.message.orEmpty().contains("Forge 1.20.1-47.1.33"))
    }

    @Test
    fun `MixinExtras must be the exact target graph module`() {
        val fixture = fixture()
        val wrongMixin = artifact(
            "io.github.llamalad7:mixinextras-neoforge:0.5.2",
            LegacyMigrationArtifactInventory.ComponentKind.MODULE,
            "mixinextras-neoforge-0.5.2.jar",
            setOf("compileClasspath"),
            'x',
            fixture.sealedRunRoot
        )
        val wrongTarget = LegacyMigrationArtifactInventory.Target(
            fixture.inventory.target.platformIdentity,
            fixture.inventory.target.platformArtifacts,
            fixture.inventory.target.closureArtifacts
                .filterNot { it == fixture.mixinExtras } + wrongMixin
        )
        val inventory = LegacyMigrationArtifactPair(
            fixture.ownerProject,
            fixture.leafProject,
            fixture.sealedRunRoot,
            fixture.inventory.source,
            wrongTarget
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyLootingArtifactInputs.resolve(
                fixture.ownerProject,
                inventory,
                fixture.profile
            )
        }

        assertTrue(failure.message.orEmpty().contains("unexpected coordinate"))
    }

    @Test
    fun `artifact handoff serves both aliases until explicit owner release`() {
        val fixture = fixture()

        LegacyMigrationArtifactInventoryHandoff.publish(fixture.inventory)

        assertSame(
            fixture.inventory,
            LegacyMigrationArtifactInventoryHandoff.require(fixture.ownerProject)
        )
        assertSame(
            fixture.inventory,
            LegacyMigrationArtifactInventoryHandoff.require(fixture.leafProject)
        )
        assertTrue(LegacyMigrationArtifactInventoryHandoff.release(fixture.ownerProject))
        assertFailsWith<IllegalStateException> {
            LegacyMigrationArtifactInventoryHandoff.require(fixture.ownerProject)
        }
        assertFailsWith<IllegalStateException> {
            LegacyMigrationArtifactInventoryHandoff.require(fixture.leafProject)
        }
    }

    @Test
    fun `verified publish replaces the prior run for the same owner`() {
        val first = fixture()
        LegacyMigrationArtifactInventoryHandoff.publish(first.inventory)

        val second = fixture("replacement")
        LegacyMigrationArtifactInventoryHandoff.publish(second.inventory)

        assertSame(
            second.inventory,
            LegacyMigrationArtifactInventoryHandoff.require(second.ownerProject)
        )
        assertSame(
            second.inventory,
            LegacyMigrationArtifactInventoryHandoff.require(second.leafProject)
        )
        assertFailsWith<IllegalStateException> {
            LegacyMigrationArtifactInventoryHandoff.require(first.leafProject)
        }
        assertTrue(LegacyMigrationArtifactInventoryHandoff.release(second.ownerProject))
    }

    @Test
    fun `resolver rejects projects outside the exact owner and leaf aliases`() {
        val fixture = fixture()
        val unrelated = temporary.resolve("unrelated").createDirectories()

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyLootingArtifactInputs.resolve(
                unrelated,
                fixture.inventory,
                fixture.profile
            )
        }

        assertTrue(failure.message.orEmpty().contains("different project"))
    }

    @Test
    fun `production resolver fails when no sealed inventory was published`() {
        val project = temporary.resolve("missing-inventory").createDirectories()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingArtifactInputs.resolve(project)
        }

        assertTrue(failure.message.orEmpty().contains("No sealed legacy migration"))
    }

    @Test
    fun `resolver rehashes artifact records after the inventory was sealed`() {
        val fixture = fixture()
        fixture.sourceNamed.file.writeBytes("changed-after-seal".toByteArray())

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyLootingArtifactInputs.resolve(
                fixture.ownerProject,
                fixture.inventory,
                fixture.profile
            )
        }

        assertTrue(failure.message.orEmpty().contains("changed after sealing"))
    }

    @Test
    fun `conflicting MixinExtras versions make the target graph ambiguous`() {
        val fixture = fixture()
        val conflictingMixin = artifact(
            "io.github.llamalad7:mixinextras-neoforge:0.5.2",
            LegacyMigrationArtifactInventory.ComponentKind.MODULE,
            "mixinextras-neoforge-0.5.2.jar",
            setOf("compileClasspath"),
            'x',
            fixture.sealedRunRoot
        )
        val inventory = LegacyMigrationArtifactPair(
            fixture.ownerProject,
            fixture.leafProject,
            fixture.sealedRunRoot,
            fixture.inventory.source,
            LegacyMigrationArtifactInventory.Target(
                fixture.inventory.target.platformIdentity,
                fixture.inventory.target.platformArtifacts,
                fixture.inventory.target.closureArtifacts + conflictingMixin
            )
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyLootingArtifactInputs.resolve(
                fixture.ownerProject,
                inventory,
                fixture.profile
            )
        }

        assertTrue(failure.message.orEmpty().contains("unambiguous"))
    }

    @Test
    fun `production profile binds actual Create source and target artifacts`() {
        val profile =
            LegacyLootingArtifactProfile.CREATE_FORGE_47_1_33_TO_NEOFORGE_21_1_230

        assertEquals("47.1.33", profile.sourceIdentity.loaderVersion)
        assertEquals(
            "a1270c7d64018ba097add19d5dcee698abf8eb9971c8d5f91c7f1732e1e84ff3",
            profile.sourceNamedSha256
        )
        assertEquals("21.1.230", profile.targetIdentity.loaderVersion)
        assertEquals(
            "da2570515920276bb884d59868915e924bc80523e8771e145a11b12db41d5b2b",
            profile.targetNamedSha256
        )
        assertEquals(
            "830d5991464c1c43d6960c2aaae6b2e3e61f83021aa192ae3783d47317d806f4",
            profile.targetSourcesSha256
        )
        assertEquals(
            "9822e773bd9f42d36ed53ea3d67486207291747a7bfacc29beeb92040721bc9b",
            profile.mixinExtrasSha256
        )
    }

    private fun fixture(runName: String = "initial"): Fixture {
        val ownerProject = temporary.resolve("project").createDirectories()
        val leafProject = ownerProject.resolve("modules/$runName").createDirectories()
        val sealedRunRoot = temporary.resolve("sealed-run-$runName").createDirectories()
        val sourceIdentity = LegacyMigrationArtifactInventory.PlatformIdentity(
            "legacy-forge",
            "1.20.1",
            "47.1.33",
            "net.minecraftforge:forge:1.20.1-47.1.33"
        )
        val targetIdentity = LegacyMigrationArtifactInventory.PlatformIdentity(
            "neoforge",
            "1.21.1",
            "21.1.230",
            "net.neoforged:neoforge:21.1.230"
        )
        val sourceNamed = artifact(
            "generated-platform:source:named",
            LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM,
            "source-named.jar",
            setOf("compileClasspath", "runtimeClasspath"),
            'a',
            sealedRunRoot
        )
        val sourceMerged = artifact(
            "generated-platform:source:merged",
            LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY,
            "source-merged.jar",
            emptySet(),
            'b',
            sealedRunRoot
        )
        val sourceSources = artifact(
            "generated-platform:source:sources",
            LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY,
            "source-sources.jar",
            emptySet(),
            'c',
            sealedRunRoot
        )
        val targetNamed = artifact(
            "generated-platform:target:named",
            LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM,
            "target-named.jar",
            setOf("compileClasspath", "runtimeClasspath"),
            'd',
            sealedRunRoot
        )
        val targetMerged = artifact(
            "generated-platform:target:merged",
            LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY,
            "target-merged.jar",
            emptySet(),
            'e',
            sealedRunRoot
        )
        val targetSources = artifact(
            "generated-platform:target:sources",
            LegacyMigrationArtifactInventory.ComponentKind.PLATFORM_AUXILIARY,
            "target-sources.jar",
            emptySet(),
            'f',
            sealedRunRoot
        )
        val mixinExtras = artifact(
            "io.github.llamalad7:mixinextras-neoforge:0.5.3",
            LegacyMigrationArtifactInventory.ComponentKind.MODULE,
            "mixinextras-neoforge-0.5.3.jar",
            setOf("compileClasspath"),
            'g',
            sealedRunRoot
        )
        val profile = LegacyLootingArtifactProfile(
            sourceIdentity = sourceIdentity,
            targetIdentity = targetIdentity,
            sourceNamedDisplayName = sourceNamed.displayName,
            sourceNamedSha256 = sourceNamed.sha256,
            sourceMergedDisplayName = sourceMerged.displayName,
            sourceMergedSha256 = sourceMerged.sha256,
            sourceSourcesDisplayName = sourceSources.displayName,
            sourceSourcesSha256 = sourceSources.sha256,
            targetNamedDisplayName = targetNamed.displayName,
            targetNamedSha256 = targetNamed.sha256,
            targetMergedDisplayName = targetMerged.displayName,
            targetMergedSha256 = targetMerged.sha256,
            targetSourcesDisplayName = targetSources.displayName,
            targetSourcesSha256 = targetSources.sha256,
            mixinExtrasComponent = mixinExtras.component,
            mixinExtrasSha256 = mixinExtras.sha256
        )
        val inventory = LegacyMigrationArtifactPair(
            ownerProject,
            leafProject,
            sealedRunRoot,
            source = LegacyMigrationArtifactInventory.Source(
                sourceIdentity,
                LegacyMigrationArtifactInventory.PlatformArtifacts(
                    sourceNamed,
                    sourceMerged,
                    sourceSources
                ),
                listOf(sourceNamed),
                classRuntimeFeature = 17,
                compileClasspathConfiguration = ":|compileClasspath"
            ),
            target = LegacyMigrationArtifactInventory.Target(
                targetIdentity,
                LegacyMigrationArtifactInventory.PlatformArtifacts(
                    targetNamed,
                    targetMerged,
                    targetSources
                ),
                listOf(targetNamed, mixinExtras)
            )
        )
        return Fixture(
            ownerProject,
            leafProject,
            sealedRunRoot,
            profile,
            inventory,
            sourceNamed,
            targetMerged,
            targetSources,
            mixinExtras
        )
    }

    private fun artifact(
        component: String,
        kind: LegacyMigrationArtifactInventory.ComponentKind,
        displayName: String,
        configurations: Set<String>,
        seed: Char,
        sealedRunRoot: Path
    ): LegacyMigrationArtifactInventory.Artifact {
        val file = sealedRunRoot.resolve("artifacts/$seed-$displayName")
        file.parent.createDirectories()
        file.writeBytes(seed.toString().repeat(32).toByteArray())
        return LegacyMigrationArtifactInventory.Artifact(
            component,
            kind,
            displayName,
            configurations,
            file,
            sha256(file),
            resolutionConfigurations = configurations.map {
                if ('|' in it) it else ":|$it"
            }.toSet(),
            configurationOrder = configurations.map {
                (if ('|' in it) it else ":|$it") to 0
            }.toMap()
        )
    }

    private data class Fixture(
        val ownerProject: Path,
        val leafProject: Path,
        val sealedRunRoot: Path,
        val profile: LegacyLootingArtifactProfile,
        val inventory: LegacyMigrationArtifactPair,
        val sourceNamed: LegacyMigrationArtifactInventory.Artifact,
        val targetMerged: LegacyMigrationArtifactInventory.Artifact,
        val targetSources: LegacyMigrationArtifactInventory.Artifact,
        val mixinExtras: LegacyMigrationArtifactInventory.Artifact
    )

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(path.toFile().readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
