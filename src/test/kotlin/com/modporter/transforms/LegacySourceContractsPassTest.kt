package com.modporter.transforms

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.Pass
import com.modporter.core.pipeline.PassResult
import com.modporter.core.pipeline.Pipeline
import com.modporter.core.transforms.structural.LegacyMigrationArtifactInventory
import com.modporter.core.transforms.structural.LegacyMigrationArtifactInventoryHandoff
import com.modporter.core.transforms.structural.LegacyMigrationArtifactPair
import com.modporter.core.transforms.structural.LegacyMigrationInventoryPrepass
import com.modporter.core.transforms.structural.LegacySourceContractCoordinator
import com.modporter.core.transforms.structural.LegacySourceContractMode
import com.modporter.core.transforms.structural.LegacySourceContractOwnership
import com.modporter.core.transforms.structural.LegacySourceContractPlans
import com.modporter.core.transforms.structural.LegacySourceContractsPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import com.modporter.pipelines.forge2neo.Forge2NeoPipeline
import com.modporter.registry.PipelineOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LegacySourceContractsPassTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `source contracts migrate old names before text writers`() {
        val source = directory.resolve("src/main/java/example/Legacy.java").also {
            it.parent.createDirectories()
            it.writeText("old-name-contract")
        }
        val events = mutableListOf<String>()
        val coordinator = RecordingCoordinator(
            events = events,
            applyAction = { source.writeText("source-contract-migrated") }
        )
        val text = ObservingTextPass(source, events)

        val result = Pipeline(
            listOf(text, LegacySourceContractsPass(coordinator))
        ).run(directory)

        assertEquals(0, result.totalErrors)
        assertEquals("source-contract-migrated", source.readText())
        assertEquals(
            listOf(
                "preflight:model",
                "preflight:plant",
                "preflight:block",
                "apply:plant",
                "apply:block",
                "apply:model",
                "mark:APPLIED",
                "text:source-contract-migrated",
                "release"
            ),
            events
        )
    }

    @Test
    fun `source contract failure aborts before text writers`() {
        val source = directory.resolve("src/main/java/example/Legacy.java").also {
            it.parent.createDirectories()
            it.writeText("old-name-contract")
        }
        val events = mutableListOf<String>()
        val coordinator = RecordingCoordinator(
            events = events,
            preflightFailure = "incomplete old-name family"
        )
        val text = ObservingTextPass(source, events)

        val result = Pipeline(
            listOf(text, LegacySourceContractsPass(coordinator))
        ).run(directory)

        assertEquals(1, result.totalErrors)
        assertEquals("old-name-contract", source.readText())
        assertFalse(events.any { it.startsWith("text:") })
        assertFalse(events.any { it.startsWith("apply:") })
        assertEquals(
            listOf(
                "preflight:model",
                "preflight:plant",
                "preflight:block",
                "release"
            ),
            events
        )
    }

    @Test
    fun `request without inventory aborts while unrelated projects no-op`() {
        val requestedEvents = mutableListOf<String>()
        val requested = LegacySourceContractsPass(
            RecordingCoordinator(
                events = requestedEvents,
                published = false,
                request = true
            )
        ).apply(directory)
        assertEquals(1, requested.errors.size)
        assertTrue(requested.errors.single().contains("without a published sealed inventory"))

        val unrelatedEvents = mutableListOf<String>()
        val unrelated = LegacySourceContractsPass(
            RecordingCoordinator(
                events = unrelatedEvents,
                published = false,
                request = false
            )
        ).apply(directory)
        assertTrue(unrelated.errors.isEmpty())
        assertTrue(unrelated.changes.isEmpty())
        assertTrue(unrelatedEvents.isEmpty())
    }

    @Test
    fun `plant surface of an unreviewed source platform is not an inventory request`() {
        directory.resolve("build.gradle").writeText("plugins { id 'net.minecraftforge.gradle' }\n")
        directory.resolve("src/main/java/example/Crop.java").also {
            it.parent.createDirectories()
            it.writeText(
                """
                package example;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.BushBlock;
                import net.minecraft.world.level.block.state.BlockState;
                public class Crop extends BushBlock {
                    public Crop(Properties properties) { super(properties); }
                    @Override
                    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
                        return true;
                    }
                }
                """.trimIndent()
            )
        }
        assertTrue(
            com.modporter.core.transforms.structural.ParsedLegacyMigrationSurfaceDetector
                .detect(directory, directory).requiresInventory
        )

        val result = LegacySourceContractsPass().apply(directory)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `analyze reports every preflight plan without source writes`() {
        val source = directory.resolve("src/main/java/example/Legacy.java").also {
            it.parent.createDirectories()
            it.writeText("old-name-contract")
        }
        val events = mutableListOf<String>()
        val coordinator = RecordingCoordinator(events = events)
        val pass = LegacySourceContractsPass(coordinator)

        val result = pass.analyze(directory)

        assertEquals(3, result.changes.size)
        assertEquals("old-name-contract", source.readText())
        assertEquals(
            listOf(
                "preflight:model",
                "preflight:plant",
                "preflight:block",
                "mark:ANALYZED"
            ),
            events
        )
        pass.finishProject(directory)
        assertEquals("release", events.last())
    }

    @Test
    fun `ownership rejects analyzed apply and mode mixing and revalidates inventory`() {
        val inventory = inventory()
        LegacyMigrationArtifactInventoryHandoff.publish(inventory)
        try {
            LegacySourceContractOwnership.markAnalyzed(inventory)

            assertSame(
                inventory,
                LegacySourceContractOwnership.requireApplied(
                    inventory.leafProject,
                    dryRun = true
                )
            )
            assertFailsWith<IllegalArgumentException> {
                LegacySourceContractOwnership.requireApplied(inventory.leafProject)
            }
            assertFailsWith<IllegalArgumentException> {
                LegacySourceContractOwnership.markApplied(inventory)
            }
            assertFailsWith<IllegalArgumentException> {
                LegacySourceContractOwnership.release(inventory.leafProject)
            }

            LegacyMigrationArtifactInventoryHandoff.release(inventory.ownerProject)
            assertFailsWith<IllegalStateException> {
                LegacySourceContractOwnership.requireApplied(
                    inventory.ownerProject,
                    dryRun = true
                )
            }
        } finally {
            LegacySourceContractOwnership.release(inventory.ownerProject)
            LegacyMigrationArtifactInventoryHandoff.release(inventory.ownerProject)
        }
    }

    @Test
    fun `forge pipeline registers inventory then source contracts before text`() {
        val passes = Forge2NeoPipeline.definition.passFactory(
            MappingDatabase.loadDefault(),
            PipelineOptions()
        )

        assertIs<LegacyMigrationInventoryPrepass>(passes[0])
        assertEquals(-1, passes[0].order)
        assertIs<LegacySourceContractsPass>(passes[1])
        assertEquals(0, passes[1].order)
        assertIs<TextReplacementPass>(passes[2])
        assertTrue(passes[2].order > passes[1].order)
    }

    private fun inventory(): LegacyMigrationArtifactPair {
        val owner = directory.resolve("owner").createDirectories()
        val leaf = owner.resolve("module").createDirectories()
        val sealed = directory.resolve("sealed").createDirectories()
        val artifactFile = sealed.resolve("artifact.bin").also {
            it.writeBytes("sealed-inventory".toByteArray())
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(artifactFile.toFile().readBytes())
            .joinToString("") { "%02x".format(it) }
        val compileClasspath = "$leaf|compileClasspath"
        val artifact = LegacyMigrationArtifactInventory.Artifact(
            component = "fixture-project",
            componentKind = LegacyMigrationArtifactInventory.ComponentKind.PROJECT,
            displayName = "fixture project artifact",
            configurations = setOf(compileClasspath),
            file = artifactFile,
            sha256 = hash,
            resolutionConfigurations = setOf(compileClasspath),
            configurationOrder = mapOf(compileClasspath to 0)
        )
        val platform = LegacyMigrationArtifactInventory.PlatformArtifacts(
            named = artifact,
            merged = artifact,
            sources = artifact
        )
        return LegacyMigrationArtifactPair(
            ownerProject = owner,
            leafProject = leaf,
            sealedRunRoot = sealed,
            source = LegacyMigrationArtifactInventory.Source(
                platformIdentity = LegacyMigrationArtifactInventory.PlatformIdentity(
                    family = "legacy-forge",
                    minecraftVersion = "1.20.1",
                    loaderVersion = "47.1.33",
                    resolvedLoaderCoordinate = "net.minecraftforge:forge:1.20.1-47.1.33"
                ),
                platformArtifacts = platform,
                closureArtifacts = listOf(artifact),
                classRuntimeFeature = 17,
                compileClasspathConfiguration = compileClasspath
            ),
            target = LegacyMigrationArtifactInventory.Target(
                platformIdentity = LegacyMigrationArtifactInventory.PlatformIdentity(
                    family = "neoforge",
                    minecraftVersion = "1.21.1",
                    loaderVersion = "21.1.230",
                    resolvedLoaderCoordinate =
                        "net.neoforged:neoforge:21.1.230"
                ),
                platformArtifacts = platform,
                closureArtifacts = emptyList()
            )
        )
    }

    private class RecordingCoordinator(
        private val events: MutableList<String>,
        private val published: Boolean = true,
        private val request: Boolean = false,
        private val preflightFailure: String? = null,
        private val applyAction: () -> Unit = {}
    ) : LegacySourceContractCoordinator {
        override fun hasPublishedInventory(project: Path): Boolean = published

        override fun hasInventoryRequest(project: Path): Boolean = request

        override fun preflight(project: Path): LegacySourceContractPlans {
            events += "preflight:model"
            events += "preflight:plant"
            events += "preflight:block"
            preflightFailure?.let(::error)
            return LegacySourceContractPlans(
                modelData = listOf(change(project, "model")),
                plant = listOf(change(project, "plant")),
                blockStart = listOf(change(project, "block"))
            )
        }

        override fun apply(
            project: Path,
            plans: LegacySourceContractPlans
        ): List<Change> {
            events += "apply:plant"
            events += "apply:block"
            events += "apply:model"
            applyAction()
            return plans.plant + plans.blockStart + plans.modelData
        }

        override fun mark(project: Path, mode: LegacySourceContractMode) {
            events += "mark:$mode"
        }

        override fun release(owner: Path): Boolean {
            events += "release"
            return true
        }

        private fun change(project: Path, family: String): Change =
            Change(
                file = project.resolve("$family.java"),
                line = 1,
                description = family,
                before = "old",
                after = "new",
                confidence = Confidence.HIGH,
                ruleId = "test-$family"
            )
    }

    private class ObservingTextPass(
        private val source: Path,
        private val events: MutableList<String>
    ) : Pass {
        override val name = "text writer"
        override val order = 1

        override fun analyze(projectDir: Path): PassResult = apply(projectDir)

        override fun apply(projectDir: Path): PassResult {
            events += "text:${source.readText()}"
            return PassResult(name, emptyList())
        }
    }
}
