package com.modporter.core.pipeline

import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PlannedMixinPipelineParityTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `full pipeline dry run registers retained mixin config with apply parity`() {
        val dryProject = retainedQueryProject(directory.resolve("dry"))
        val applyProject = retainedQueryProject(directory.resolve("apply"))
        val drySourceBefore = dryProject.source.readText()
        val dryMetadataBefore = dryProject.metadata.readText()

        val dryResult = Pipeline(
            listOf(StructuralRefactorPass(), BuildSystemPass()),
            dryRun = true
        ).run(dryProject.root)

        assertEquals(0, dryResult.totalErrors, dryResult.errorText())
        assertEquals(drySourceBefore, dryProject.source.readText())
        assertEquals(dryMetadataBefore, dryProject.metadata.readText())
        assertFalse(dryProject.mixinConfig.exists())
        val structuralChanges = dryResult.passResults
            .single { it.passName == "Structural Refactor" }
            .changes
        val drySourcePath = dryProject.source.toAbsolutePath().normalize()
        assertFalse(
            structuralChanges.any { change ->
                change.file.toAbsolutePath().normalize() == drySourcePath &&
                    change.ruleId == "struct-lazy-optional"
            },
            structuralChanges.filter { it.file.toAbsolutePath().normalize() == drySourcePath }.toString()
        )
        val expectedConfigPath = dryProject.mixinConfig.toAbsolutePath().normalize()
        val plannedConfig = structuralChanges.singleOrNull {
            it.file.toAbsolutePath().normalize() == expectedConfigPath
        } ?: error(
            buildString {
                appendLine("Expected Structural mixin config change at $expectedConfigPath; observed:")
                structuralChanges.forEach { change ->
                    appendLine(
                        "${change.file.toAbsolutePath().normalize()} | ${change.ruleId} | " +
                            change.after.take(120).replace("\n", "\\n")
                    )
                }
            }
        )
        assertTrue(plannedConfig.after.trimStart().startsWith("{"), plannedConfig.after)
        assertTrue(plannedConfig.after.contains("LegacyCapabilityServerLevelMixin"), plannedConfig.after)
        val dryRegistrations = dryResult.mixinRegistrations()
        assertEquals(
            listOf("[[mixins]] config=\"modporter-legacy-capability-query.mixins.json\""),
            dryRegistrations.map { it.after }
        )

        val applyResult = Pipeline(
            listOf(StructuralRefactorPass(), BuildSystemPass()),
            dryRun = false
        ).run(applyProject.root)

        assertEquals(0, applyResult.totalErrors, applyResult.errorText())
        assertTrue(
            applyProject.source.readText().contains("LegacyBlockEntityCapabilityQueries.getRetained("),
            applyProject.source.readText()
        )
        val applyStructuralChanges = applyResult.passResults
            .single { it.passName == "Structural Refactor" }
            .changes
        val applySourcePath = applyProject.source.toAbsolutePath().normalize()
        assertFalse(
            applyStructuralChanges.any { change ->
                change.file.toAbsolutePath().normalize() == applySourcePath &&
                    change.ruleId == "struct-lazy-optional"
            },
            applyStructuralChanges.filter { it.file.toAbsolutePath().normalize() == applySourcePath }.toString()
        )
        assertTrue(applyProject.mixinConfig.exists())
        assertTrue(
            applyProject.metadata.readText().contains(
                "config=\"modporter-legacy-capability-query.mixins.json\""
            ),
            applyProject.metadata.readText()
        )
        val applyRegistrations = applyResult.mixinRegistrations()
        assertEquals(dryRegistrations.map { it.ruleId to it.after }, applyRegistrations.map { it.ruleId to it.after })
    }

    @Test
    fun `planned mixin configs are structured owned and deduplicated per pipeline run`() {
        val project = metadataOnlyProject(directory.resolve("owned"))
        val metadataBefore = project.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        val validConfig = """
            {
              "required": true,
              "package": "example.mixin",
              "mixins": ["GeneratedMixin"]
            }
        """.trimIndent() + "\n"
        val ownedConfig = project.resolve("src/main/resources/generated.mixins.json")
        val malformedConfig = project.resolve("src/main/resources/malformed.mixins.json")
        val ordinaryData = project.resolve("src/main/resources/ordinary-data.json")
        val plannedOrdinaryData = project.resolve("src/main/resources/planned-data.json")
        ordinaryData.writeText(validConfig)
        val foreignConfig = directory.resolve("other-project/src/main/resources/foreign.mixins.json")
        val planned = listOf(
            plannedConfigChange(ownedConfig, validConfig),
            plannedConfigChange(ownedConfig, validConfig),
            plannedConfigChange(malformedConfig, "{ not-json"),
            plannedConfigChange(plannedOrdinaryData, validConfig),
            plannedConfigChange(foreignConfig, validConfig)
        )

        val result = Pipeline(
            listOf(PlannedConfigPass(planned), BuildSystemPass()),
            dryRun = true
        ).run(project)

        assertEquals(0, result.totalErrors, result.errorText())
        assertEquals(metadataBefore, project.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText())
        assertFalse(ownedConfig.exists())
        assertFalse(malformedConfig.exists())
        assertEquals(validConfig, ordinaryData.readText())
        assertFalse(plannedOrdinaryData.exists())
        assertFalse(foreignConfig.exists())
        val registrations = result.mixinRegistrations()
        assertEquals(1, registrations.size, registrations.joinToString("\n"))
        assertEquals("[[mixins]] config=\"generated.mixins.json\"", registrations.single().after)
    }

    @Test
    fun `relative pipeline root preserves planned config ownership`() {
        val project = Path.of(
            "build",
            "tmp",
            "planned-mixin-relative-${UUID.randomUUID()}"
        )
        try {
            metadataOnlyProject(project)
            val configSource = """
                {
                  "package": "relative.mixin",
                  "mixins": ["RelativeMixin"]
                }
            """.trimIndent() + "\n"

            val result = Pipeline(
                listOf(ProjectRelativeConfigPass(configSource), BuildSystemPass()),
                dryRun = true
            ).run(project)

            assertEquals(0, result.totalErrors, result.errorText())
            val registrations = result.mixinRegistrations()
            assertEquals(1, registrations.size, registrations.joinToString("\n"))
            assertEquals("[[mixins]] config=\"relative.mixins.json\"", registrations.single().after)
            assertFalse(project.resolve("src/main/resources/relative.mixins.json").exists())
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    private fun retainedQueryProject(root: Path): RetainedQueryProject {
        root.createDirectories()
        root.resolve("build.gradle").writeText(
            """
            plugins {
                id("net.neoforged.moddev.legacyforge") version "2.0.107"
            }
            legacyForge {
                version = "1.20.1-47.4.0"
            }
            """.trimIndent() + "\n"
        )
        val metadata = root.resolve("src/main/resources/META-INF/neoforge.mods.toml").also {
            it.parent.createDirectories()
            it.writeText(
                "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n\n" +
                    "[[mods]]\nmodId=\"create\"\nversion=\"test\"\ndisplayName=\"Create\"\n"
            )
        }
        root.resolve("src/main/java/com/simibubi/create/CreateEntry.java").also {
            it.parent.createDirectories()
            it.writeText(
                """
                package com.simibubi.create;
                import net.neoforged.fml.common.Mod;
                @Mod("create") class CreateEntry {}
                """.trimIndent()
            )
        }
        val compatPackage = "com.modporter.generated.create.compat"
        val compat = root.resolve("src/main/java/${compatPackage.replace('.', '/')}").createDirectories()
        compat.resolve("LazyOptional.java").writeText(BuildSystemPass.legacyLazyOptionalShim(compatPackage))
        compat.resolve("Capability.java").writeText(BuildSystemPass.legacyCapabilityShim(compatPackage))
        val source = root.resolve(
            "src/main/java/com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java"
        ).also {
            it.parent.createDirectories()
            it.writeText(
                """
                package com.simibubi.create.content.schematics.cannon;

                import $compatPackage.LazyOptional;
                import java.util.LinkedHashSet;
                import net.minecraft.core.BlockPos;
                import net.minecraft.core.Direction;
                import net.minecraft.world.level.Level;
                import net.minecraft.world.level.block.entity.BlockEntity;
                import net.neoforged.neoforge.capabilities.Capabilities;
                import net.neoforged.neoforge.items.IItemHandler;

                public class SchematicannonBlockEntity {
                    public LinkedHashSet<LazyOptional<IItemHandler>> attachedInventories = new LinkedHashSet<>();
                    private final Level level = new Level();
                    private final BlockPos worldPosition = new BlockPos(1);

                    public void findInventories() {
                        attachedInventories.clear();
                        for (Direction facing : Direction.values()) {
                            if (!level.isLoaded(worldPosition.relative(facing)))
                                continue;
                            BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(facing));
                            if (blockEntity != null) {
                                LazyOptional<IItemHandler> capability = blockEntity.getCapability(Capabilities.ItemHandler.BLOCK, facing.getOpposite());
                                if (capability.isPresent()) {
                                    attachedInventories.add(capability);
                                }
                            }
                        }
                    }
                }
                """.trimIndent()
            )
        }
        return RetainedQueryProject(
            root = root,
            source = source,
            metadata = metadata,
            mixinConfig = root.resolve("src/main/resources/modporter-legacy-capability-query.mixins.json")
        )
    }

    private fun metadataOnlyProject(root: Path): Path {
        root.createDirectories()
        root.resolve("build.gradle").writeText("plugins { id 'net.neoforged.moddev' }\n")
        root.resolve("src/main/resources/META-INF/neoforge.mods.toml").also {
            it.parent.createDirectories()
            it.writeText(
                "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n\n" +
                    "[[mods]]\nmodId=\"owned\"\nversion=\"test\"\ndisplayName=\"Owned\"\n"
            )
        }
        return root
    }

    private fun plannedConfigChange(file: Path, after: String): Change = Change(
        file = file,
        line = 1,
        description = "Plan a generated mixin config",
        before = "(absent)",
        after = after,
        confidence = Confidence.HIGH,
        ruleId = "test-plan-mixin-config"
    )

    private fun PipelineResult.mixinRegistrations(): List<Change> = passResults
        .single { it.passName == "Build System" }
        .changes.filter { it.ruleId == "build-register-existing-mixin-config" }

    private fun PipelineResult.errorText(): String = passResults
        .flatMap { result -> result.errors.map { "${result.passName}: $it" } }
        .joinToString("\n")

    private data class RetainedQueryProject(
        val root: Path,
        val source: Path,
        val metadata: Path,
        val mixinConfig: Path
    )

    private class ProjectRelativeConfigPass(private val configSource: String) : Pass {
        override val name = "Project-relative config producer"
        override val order = 3

        override fun analyze(projectDir: Path): PassResult = PassResult(
            name,
            listOf(
                Change(
                    file = projectDir.resolve("src/main/resources/relative.mixins.json"),
                    line = 1,
                    description = "Plan a project-relative mixin config",
                    before = "(absent)",
                    after = configSource,
                    confidence = Confidence.HIGH,
                    ruleId = "test-plan-relative-mixin-config"
                )
            )
        )

        override fun apply(projectDir: Path): PassResult = analyze(projectDir)
    }

    private class PlannedConfigPass(private val changes: List<Change>) : Pass {
        override val name = "Planned config producer"
        override val order = 3

        override fun analyze(projectDir: Path): PassResult = PassResult(name, changes)

        override fun apply(projectDir: Path): PassResult = PassResult(name, changes)
    }
}
