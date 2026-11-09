package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyMigrationArtifactInventory
import com.modporter.core.transforms.structural.LegacyMigrationArtifactPair
import com.modporter.core.transforms.structural.LegacyModelDataManagerCompatMigration
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyModelDataManagerCompatTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `exact Create pair receives live nullable manager bridge and client-only accessor`() {
        val fixture = fixture()
        writeCreateSources(fixture.project)

        val changes = LegacyModelDataManagerCompatMigration.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false,
            inventory = fixture.inventory
        )

        assertTrue(changes.isNotEmpty())
        val compat = fixture.project.resolve(
            "src/main/java/com/modporter/generated/copycat_test/compat"
        )
        val filtered = fixture.project.resolve(
            "src/main/java/com/simibubi/create/content/decoration/copycat/" +
                "FilteredBlockAndTintGetter.java"
        ).readText()
        val copycat = fixture.project.resolve(
            "src/main/java/com/simibubi/create/content/decoration/copycat/" +
                "CopycatBlock.java"
        ).readText()
        val resolver = compat.resolve("LegacyModelDataManagers.java").readText()
        val view = compat.resolve("LegacyRenderChunkRegionView.java").readText()
        val accessor = compat.resolve(
            "mixin/LegacyRenderChunkRegionAccessor.java"
        ).readText()
        val config = fixture.project.resolve(
            "src/main/resources/copycat_test.modporter-model-data.mixins.json"
        ).readText()
        val metadata = fixture.project.resolve(
            "src/main/resources/META-INF/neoforge.mods.toml"
        ).readText()
        val copycatCompact = copycat.replace(Regex("""\s+"""), " ")

        assertTrue(
            filtered.contains(
                "com.modporter.generated.copycat_test.compat." +
                    "LegacyModelDataManagerProvider"
            ),
            filtered
        )
        assertTrue(
            filtered.contains(
                "modporter\$getLegacyModelDataManager"
            ) && filtered.contains(
                "LegacyModelDataManagers.resolve(wrapped)"
            ),
            filtered
        )
        assertTrue(
            copycatCompact.contains(
                "ModelDataManager modelDataManager = " +
                    "com.modporter.generated.copycat_test.compat." +
                    "LegacyModelDataManagers.resolve(level);"
            ),
            copycat
        )
        assertTrue(
            copycatCompact.contains("if (modelDataManager == null)") &&
                copycatCompact.contains("return getMaterial(level, pos);") &&
                copycatCompact.contains(
                    "CopycatModel.getMaterial(modelDataManager.getAt(pos))"
                ),
            copycat
        )

        val providerIndex = resolver.indexOf(
            "instanceof LegacyModelDataManagerProvider"
        )
        val levelIndex = resolver.indexOf(
            "instanceof net.minecraft.world.level.Level"
        )
        val regionIndex = resolver.indexOf(
            "instanceof LegacyRenderChunkRegionView"
        )
        val nullIndex = resolver.indexOf("return null;")
        assertTrue(
            providerIndex in 0 until levelIndex &&
                levelIndex < regionIndex &&
                regionIndex < nullIndex,
            resolver
        )
        assertTrue(resolver.contains("java.util.Objects.requireNonNull(getter"), resolver)
        assertFalse(resolver.contains("getModelData("), resolver)
        assertFalse(resolver.contains("net.minecraft.client."), resolver)
        assertFalse(resolver.contains("LegacyRenderChunkRegionAccessor"), resolver)
        assertFalse(resolver.contains("org.spongepowered.asm.mixin"), resolver)
        assertFalse(view.contains("net.minecraft.client."), view)
        assertFalse(view.contains("org.spongepowered.asm.mixin"), view)

        assertTrue(
            accessor.contains(
                "targets = \"net.minecraft.client.renderer.chunk.RenderChunkRegion\""
            ) &&
                accessor.contains("@org.spongepowered.asm.mixin.gen.Accessor(\"level\")"),
            accessor
        )
        assertTrue(config.contains("\"client\""), config)
        assertFalse(config.contains("\"mixins\""), config)
        assertFalse(config.contains("\"server\""), config)
        assertTrue(
            metadata.contains(
                "config=\"copycat_test.modporter-model-data.mixins.json\""
            ),
            metadata
        )

        assertTrue(
            LegacyModelDataManagerCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            ).isEmpty()
        )
    }

    @Test
    fun `Copycat admission preserves the real five argument connectivity guard`() {
        val fixture = fixture()
        writeCreateSources(fixture.project)
        val copycat = fixture.project.resolve(
            "src/main/java/com/simibubi/create/content/decoration/copycat/" +
                "CopycatBlock.java"
        )
        val before = copycat.readText()
        val realGuard = "level, state, side, pos, queryPos))"
        val driftedGuard = "level, state, side, pos, queryState, queryPos))"
        assertTrue(before.contains(realGuard), before)
        val drifted = before.replace(realGuard, driftedGuard)
        assertFalse(drifted == before)
        copycat.writeText(drifted)

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyModelDataManagerCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("connectivity guard changed"),
            failure.message
        )
        assertEquals(drifted, copycat.readText())
        assertFalse(
            fixture.project.resolve(
                "src/main/java/com/modporter/generated/copycat_test/compat/" +
                    "LegacyModelDataManagers.java"
            ).exists()
        )
    }

    @Test
    fun `either exact residual activates fail closed full-family admission`() {
        val fixture = fixture()
        writeCreateSources(fixture.project)
        Files.delete(
            fixture.project.resolve(
                "src/main/java/com/simibubi/create/content/decoration/copycat/" +
                    "CopycatBlock.java"
            )
        )
        Files.delete(
            fixture.project.resolve(
                "src/main/java/com/simibubi/create/content/decoration/copycat/" +
                    "CopycatModel.java"
            )
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyModelDataManagerCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            )
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "Expected exactly one source declaration"
            ),
            failure.message
        )
        assertFalse(
            fixture.project.resolve(
                "src/main/java/com/modporter/generated/copycat_test/compat/" +
                    "LegacyModelDataManagers.java"
            ).exists()
        )
    }

    @Test
    fun `extra project manager query rejects the whole transaction before writes`() {
        val fixture = fixture()
        writeCreateSources(fixture.project)
        val extra = fixture.project.resolve("src/main/java/example/Extra.java")
        extra.parent.createDirectories()
        extra.writeText(
            """
            package example;

            import net.minecraft.world.level.BlockAndTintGetter;

            final class Extra {
                Object query(BlockAndTintGetter level) {
                    return level.getModelDataManager();
                }
            }
            """.trimIndent() + "\n"
        )
        val filtered = fixture.project.resolve(
            "src/main/java/com/simibubi/create/content/decoration/copycat/" +
                "FilteredBlockAndTintGetter.java"
        )
        val before = filtered.readText()

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyModelDataManagerCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            )
        }

        assertTrue(failure.message.orEmpty().contains("additional"))
        assertEquals(before, filtered.readText())
        assertFalse(
            fixture.project.resolve(
                "src/main/java/com/modporter/generated/copycat_test/compat/" +
                    "LegacyModelDataManagers.java"
            ).exists()
        )
    }

    @Test
    fun `dependency manager declaration rejects the exact sealed classpath`() {
        val fixture = fixture(dependencyManagerDeclaration = true)
        writeCreateSources(fixture.project)
        val filtered = fixture.project.resolve(
            "src/main/java/com/simibubi/create/content/decoration/copycat/" +
                "FilteredBlockAndTintGetter.java"
        )
        val before = filtered.readText()

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyModelDataManagerCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("unexpected") ||
                failure.message.orEmpty().contains("dependency"),
            failure.message
        )
        assertEquals(before, filtered.readText())
    }

    @Test
    fun `common helpers preserve provider priority nullness and live manager replacement`() {
        val fixture = fixture()
        writeCreateSources(fixture.project)
        LegacyModelDataManagerCompatMigration.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false,
            inventory = fixture.inventory
        )
        val compat = fixture.project.resolve(
            "src/main/java/com/modporter/generated/copycat_test/compat"
        )
        val semanticSources = temporary.resolve("semantic-sources")
        fun source(relative: String, content: String): Path =
            semanticSources.resolve(relative).also {
                it.parent.createDirectories()
                it.writeText(content.trimIndent() + "\n")
            }

        val sources = mutableListOf<Path>()
        listOf(
            "LegacyModelDataManagerProvider.java",
            "LegacyRenderChunkRegionView.java",
            "LegacyModelDataManagers.java"
        ).forEach { name ->
            val destination = semanticSources.resolve(
                "com/modporter/generated/copycat_test/compat/$name"
            )
            destination.parent.createDirectories()
            Files.copy(compat.resolve(name), destination)
            sources.add(destination)
        }
        sources.add(source(
            "org/jetbrains/annotations/Nullable.java",
            """
            package org.jetbrains.annotations;
            public @interface Nullable {}
            """
        ))
        sources.add(source(
            "net/minecraft/world/level/BlockAndTintGetter.java",
            """
            package net.minecraft.world.level;
            public interface BlockAndTintGetter {}
            """
        ))
        sources.add(source(
            "net/neoforged/neoforge/client/model/data/ModelDataManager.java",
            """
            package net.neoforged.neoforge.client.model.data;
            public final class ModelDataManager {
                public final String id;
                public ModelDataManager(String id) { this.id = id; }
            }
            """
        ))
        sources.add(source(
            "net/minecraft/world/level/Level.java",
            """
            package net.minecraft.world.level;
            import net.neoforged.neoforge.client.model.data.ModelDataManager;
            public class Level implements BlockAndTintGetter {
                private ModelDataManager manager;
                public Level(ModelDataManager manager) { this.manager = manager; }
                public ModelDataManager getModelDataManager() { return manager; }
                public void setModelDataManager(ModelDataManager manager) {
                    this.manager = manager;
                }
            }
            """
        ))
        sources.add(source(
            "probe/Semantics.java",
            """
            package probe;

            import com.modporter.generated.copycat_test.compat.LegacyModelDataManagerProvider;
            import com.modporter.generated.copycat_test.compat.LegacyModelDataManagers;
            import com.modporter.generated.copycat_test.compat.LegacyRenderChunkRegionView;
            import net.minecraft.world.level.BlockAndTintGetter;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.client.model.data.ModelDataManager;

            public final class Semantics {
                private static final class Provider
                        implements BlockAndTintGetter, LegacyModelDataManagerProvider {
                    private final ModelDataManager manager;
                    Provider(ModelDataManager manager) { this.manager = manager; }
                    @Override
                    public ModelDataManager modporter${'$'}getLegacyModelDataManager() {
                        return manager;
                    }
                }

                private static final class Region
                        implements BlockAndTintGetter, LegacyRenderChunkRegionView {
                    private final Level level;
                    Region(Level level) { this.level = level; }
                    @Override
                    public Level modporter${'$'}getBackingLevel() { return level; }
                }

                private static final class ProviderRegion
                        implements BlockAndTintGetter,
                        LegacyModelDataManagerProvider,
                        LegacyRenderChunkRegionView {
                    private final ModelDataManager provider;
                    private final Level level;
                    ProviderRegion(ModelDataManager provider, Level level) {
                        this.provider = provider;
                        this.level = level;
                    }
                    @Override
                    public ModelDataManager modporter${'$'}getLegacyModelDataManager() {
                        return provider;
                    }
                    @Override
                    public Level modporter${'$'}getBackingLevel() { return level; }
                }

                public static void run() {
                    ModelDataManager first = new ModelDataManager("first");
                    ModelDataManager second = new ModelDataManager("second");
                    ModelDataManager provider = new ModelDataManager("provider");
                    Level level = new Level(first);
                    Region region = new Region(level);
                    if (LegacyModelDataManagers.resolve(level) != first)
                        throw new AssertionError("level manager");
                    if (LegacyModelDataManagers.resolve(new Provider(provider)) != provider)
                        throw new AssertionError("provider manager");
                    if (LegacyModelDataManagers.resolve(
                            new ProviderRegion(provider, level)) != provider)
                        throw new AssertionError("provider priority");
                    level.setModelDataManager(second);
                    if (LegacyModelDataManagers.resolve(region) != second)
                        throw new AssertionError("region did not observe replacement");
                    try {
                        LegacyModelDataManagers.resolve(null);
                        throw new AssertionError("null receiver accepted");
                    } catch (NullPointerException expected) {
                    }
                }
            }
            """
        ))

        val classes = temporary.resolve("semantic-classes")
        compileJava(sources, classes)
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("probe.Semantics").getMethod("run").invoke(null)
        }

        val deniedClientLoader = object : URLClassLoader(
            arrayOf(classes.toUri().toURL()),
            null
        ) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("net.minecraft.client.") ||
                    name.startsWith("org.spongepowered.asm.mixin.")
                ) {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }
        deniedClientLoader.use { loader ->
            listOf(
                "com.modporter.generated.copycat_test.compat." +
                    "LegacyModelDataManagerProvider",
                "com.modporter.generated.copycat_test.compat." +
                    "LegacyRenderChunkRegionView",
                "com.modporter.generated.copycat_test.compat." +
                    "LegacyModelDataManagers"
            ).forEach { name ->
                assertSame(loader.loadClass(name), Class.forName(name, true, loader))
            }
        }
    }

    private fun writeCreateSources(project: Path) {
        val root = project.resolve(
            "src/main/java/com/simibubi/create/content/decoration/copycat"
        )
        root.createDirectories()
        root.resolve("FilteredBlockAndTintGetter.java").writeText(
            """
            package com.simibubi.create.content.decoration.copycat;

            import org.jetbrains.annotations.Nullable;
            import net.minecraft.world.level.BlockAndTintGetter;
            import net.neoforged.neoforge.client.model.data.ModelDataManager;

            public class FilteredBlockAndTintGetter
                    implements BlockAndTintGetter {
                private BlockAndTintGetter wrapped;

                @Override
                public @Nullable ModelDataManager getModelDataManager() {
                    return wrapped.getModelDataManager();
                }
            }
            """.trimIndent() + "\n"
        )
        root.resolve("CopycatBlock.java").writeText(
            """
            package com.simibubi.create.content.decoration.copycat;

            import org.jetbrains.annotations.Nullable;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.BlockAndTintGetter;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.client.model.data.ModelDataManager;

            public abstract class CopycatBlock {
                @Override
                public BlockState getAppearance(
                        BlockState state,
                        BlockAndTintGetter level,
                        BlockPos pos,
                        Direction side,
                        @Nullable BlockState queryState,
                        @Nullable BlockPos queryPos) {
                    if (isIgnoredConnectivitySide(
                            level, state, side, pos, queryPos))
                        return state;
                    ModelDataManager modelDataManager =
                        level.getModelDataManager();
                    if (modelDataManager == null)
                        return getMaterial(level, pos);
                    return CopycatModel.getMaterial(modelDataManager.getAt(pos));
                }
            }
            """.trimIndent() + "\n"
        )
        root.resolve("CopycatModel.java").writeText(
            """
            package com.simibubi.create.content.decoration.copycat;

            import com.simibubi.create.AllBlocks;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.client.model.data.ModelData;

            public abstract class CopycatModel {
                public static final Object MATERIAL_PROPERTY = null;

                public static BlockState getMaterial(ModelData data) {
                    BlockState material =
                        data == null ? null : data.get(MATERIAL_PROPERTY);
                    return material == null
                        ? AllBlocks.COPYCAT_BASE.getDefaultState()
                        : material;
                }
            }
            """.trimIndent() + "\n"
        )
    }

    private fun fixture(
        dependencyManagerDeclaration: Boolean = false
    ): Fixture {
        val project = temporary.resolve(
            if (dependencyManagerDeclaration) "dependency-project" else "project"
        )
        val run = temporary.resolve(
            if (dependencyManagerDeclaration) "dependency-run" else "run"
        )
        val sourcePlatform = compileJar(
            run.resolve("source-platform"),
            sourcePlatformSources()
        )
        val sourceSources = sourceJar(
            run.resolve("source-platform-sources.jar"),
            sourcePlatformSources()
        )
        val targetPlatform = compileJar(
            run.resolve("target-platform"),
            targetPlatformSources()
        )
        val targetSources = sourceJar(
            run.resolve("target-platform-sources.jar"),
            targetPlatformSources()
        )
        val sourceArtifact = artifact(
            sourcePlatform,
            "generated-platform:::source",
            order = 0
        )
        val sourceClosure = mutableListOf(sourceArtifact)
        if (dependencyManagerDeclaration) {
            val dependency = compileJar(
                run.resolve("dependency"),
                mapOf(
                    "dep/ExtraManager.java" to """
                        package dep;
                        public final class ExtraManager {
                            public net.minecraftforge.client.model.data.ModelDataManager
                                getModelDataManager() {
                                return null;
                            }
                        }
                    """.trimIndent() + "\n"
                ),
                classpath = listOf(sourcePlatform)
            )
            sourceClosure += artifact(
                dependency,
                "example:extra-manager:1.0",
                order = 1,
                kind = LegacyMigrationArtifactInventory.ComponentKind.MODULE
            )
        }
        val sourceSourcesArtifact = nonClasspathArtifact(
            sourceSources,
            "generated-platform:::source-sources"
        )
        val targetArtifact = nonClasspathArtifact(
            targetPlatform,
            "generated-platform:::target"
        )
        val targetSourcesArtifact = nonClasspathArtifact(
            targetSources,
            "generated-platform:::target-sources"
        )

        val inventory = LegacyMigrationArtifactPair(
            ownerProject = project,
            leafProject = project,
            sealedRunRoot = run,
            source = LegacyMigrationArtifactInventory.Source(
                platformIdentity = LegacyMigrationArtifactInventory.PlatformIdentity(
                    family = "legacy-forge",
                    minecraftVersion = "1.20.1",
                    loaderVersion = "47.1.33",
                    resolvedLoaderCoordinate =
                    "net.minecraftforge:forge:1.20.1-47.1.33"
                ),
                platformArtifacts = LegacyMigrationArtifactInventory.PlatformArtifacts(
                    named = sourceArtifact,
                    merged = sourceArtifact,
                    sources = sourceSourcesArtifact
                ),
                closureArtifacts = sourceClosure,
                classRuntimeFeature = 17,
                compileClasspathConfiguration = ":|compileClasspath"
            ),
            target = LegacyMigrationArtifactInventory.Target(
                platformIdentity = LegacyMigrationArtifactInventory.PlatformIdentity(
                    family = "neoforge",
                    minecraftVersion = "1.21.1",
                    loaderVersion = "21.1.230",
                    resolvedLoaderCoordinate =
                    "net.neoforged:neoforge:21.1.230"
                ),
                platformArtifacts = LegacyMigrationArtifactInventory.PlatformArtifacts(
                    named = targetArtifact,
                    merged = targetArtifact,
                    sources = targetSourcesArtifact
                ),
                closureArtifacts = emptyList()
            )
        )
        val metadata = project.resolve(
            "src/main/resources/META-INF/neoforge.mods.toml"
        )
        metadata.parent.createDirectories()
        metadata.writeText(
            """
            modLoader="javafml"
            loaderVersion="[1,)"
            license="MIT"

            [[mods]]
            modId="copycat_test"
            version="1.0.0"
            displayName="Copycat Test"
            """.trimIndent() + "\n"
        )
        return Fixture(project, inventory)
    }

    private fun sourcePlatformSources(): Map<String, String> = linkedMapOf(
        "org/jetbrains/annotations/Nullable.java" to """
            package org.jetbrains.annotations;
            public @interface Nullable {}
        """.trimIndent() + "\n",
        "net/minecraftforge/api/distmarker/Dist.java" to """
            package net.minecraftforge.api.distmarker;
            public enum Dist { CLIENT, DEDICATED_SERVER }
        """.trimIndent() + "\n",
        "net/minecraftforge/api/distmarker/OnlyIn.java" to """
            package net.minecraftforge.api.distmarker;
            public @interface OnlyIn { Dist value(); }
        """.trimIndent() + "\n",
        "net/minecraftforge/client/model/data/ModelDataManager.java" to """
            package net.minecraftforge.client.model.data;
            public class ModelDataManager {}
        """.trimIndent() + "\n",
        "net/minecraftforge/common/extensions/IForgeBlockGetter.java" to """
            package net.minecraftforge.common.extensions;
            import net.minecraftforge.client.model.data.ModelDataManager;
            import org.jetbrains.annotations.Nullable;
            public interface IForgeBlockGetter {
                @Nullable
                default ModelDataManager getModelDataManager() {
                    return null;
                }
            }
        """.trimIndent() + "\n",
        "net/minecraft/world/level/Level.java" to """
            package net.minecraft.world.level;
            public class Level
                    implements net.minecraftforge.common.extensions.IForgeBlockGetter {}
        """.trimIndent() + "\n",
        "net/minecraft/client/multiplayer/ClientLevel.java" to """
            package net.minecraft.client.multiplayer;
            public final class ClientLevel extends net.minecraft.world.level.Level {
                private final net.minecraftforge.client.model.data.ModelDataManager
                    modelDataManager =
                    new net.minecraftforge.client.model.data.ModelDataManager();
                @Override
                public net.minecraftforge.client.model.data.ModelDataManager
                    getModelDataManager() {
                    return modelDataManager;
                }
            }
        """.trimIndent() + "\n",
        "net/minecraft/client/renderer/chunk/RenderChunkRegion.java" to """
            package net.minecraft.client.renderer.chunk;
            import net.minecraftforge.api.distmarker.Dist;
            import net.minecraftforge.api.distmarker.OnlyIn;
            @OnlyIn(Dist.CLIENT)
            public class RenderChunkRegion
                    implements net.minecraftforge.common.extensions.IForgeBlockGetter {
                protected final net.minecraft.world.level.Level level;
                public RenderChunkRegion(net.minecraft.world.level.Level level) {
                    this.level = level;
                }
                @Override
                public net.minecraftforge.client.model.data.ModelDataManager
                    getModelDataManager() {
                    return level.getModelDataManager();
                }
            }
        """.trimIndent() + "\n"
    )

    private fun targetPlatformSources(): Map<String, String> = linkedMapOf(
        "org/jetbrains/annotations/Nullable.java" to """
            package org.jetbrains.annotations;
            public @interface Nullable {}
        """.trimIndent() + "\n",
        "net/neoforged/api/distmarker/Dist.java" to """
            package net.neoforged.api.distmarker;
            public enum Dist { CLIENT, DEDICATED_SERVER }
        """.trimIndent() + "\n",
        "net/neoforged/api/distmarker/OnlyIn.java" to """
            package net.neoforged.api.distmarker;
            public @interface OnlyIn { Dist value(); }
        """.trimIndent() + "\n",
        "net/neoforged/fml/common/EventBusSubscriber.java" to """
            package net.neoforged.fml.common;
            import net.neoforged.api.distmarker.Dist;
            public @interface EventBusSubscriber {
                String modid();
                Dist value();
            }
        """.trimIndent() + "\n",
        "net/minecraft/core/BlockPos.java" to """
            package net.minecraft.core;
            public final class BlockPos {}
        """.trimIndent() + "\n",
        "net/neoforged/neoforge/client/model/data/ModelData.java" to """
            package net.neoforged.neoforge.client.model.data;
            public class ModelData {
                public static final ModelData EMPTY = new ModelData();
                public <T> T get(Object property) { return null; }
            }
        """.trimIndent() + "\n",
        "net/neoforged/neoforge/client/model/data/ModelDataManager.java" to """
            package net.neoforged.neoforge.client.model.data;
            import net.minecraft.core.BlockPos;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.fml.common.EventBusSubscriber;
            @EventBusSubscriber(modid = "neoforge", value = Dist.CLIENT)
            public class ModelDataManager {
                public ModelData getAt(BlockPos pos) {
                    return ModelData.EMPTY;
                }
            }
        """.trimIndent() + "\n",
        "net/neoforged/neoforge/common/extensions/ILevelExtension.java" to """
            package net.neoforged.neoforge.common.extensions;
            import net.neoforged.neoforge.client.model.data.ModelDataManager;
            import org.jetbrains.annotations.Nullable;
            public interface ILevelExtension {
                @Nullable
                default ModelDataManager getModelDataManager() {
                    return null;
                }
            }
        """.trimIndent() + "\n",
        "net/minecraft/world/level/BlockAndTintGetter.java" to """
            package net.minecraft.world.level;
            public interface BlockAndTintGetter {}
        """.trimIndent() + "\n",
        "net/minecraft/world/level/Level.java" to """
            package net.minecraft.world.level;
            public class Level implements BlockAndTintGetter,
                    net.neoforged.neoforge.common.extensions.ILevelExtension {
                @Override
                public net.neoforged.neoforge.client.model.data.ModelDataManager
                    getModelDataManager() {
                    return null;
                }
            }
        """.trimIndent() + "\n",
        "net/minecraft/client/renderer/chunk/RenderChunkRegion.java" to """
            package net.minecraft.client.renderer.chunk;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockAndTintGetter;
            import net.minecraft.world.level.Level;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.api.distmarker.OnlyIn;
            import net.neoforged.neoforge.client.model.data.ModelData;
            @OnlyIn(Dist.CLIENT)
            public class RenderChunkRegion implements BlockAndTintGetter {
                protected final Level level;
                public RenderChunkRegion(Level level) {
                    this.level = level;
                }
                public ModelData getModelData(BlockPos pos) {
                    return ModelData.EMPTY;
                }
            }
        """.trimIndent() + "\n"
    )

    private fun compileJar(
        root: Path,
        sources: Map<String, String>,
        classpath: List<Path> = emptyList()
    ): Path {
        val sourceRoot = root.resolve("sources")
        val sourceFiles = sources.map { (relative, content) ->
            sourceRoot.resolve(relative).also {
                it.parent.createDirectories()
                it.writeText(content)
            }
        }
        val classes = root.resolve("classes")
        compileJava(sourceFiles, classes, classpath)
        val jar = root.resolve("classes.jar")
        jar.parent.createDirectories()
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            Files.walk(classes).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    val relative = classes.relativize(file)
                        .toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(relative))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
        }
        return jar
    }

    private fun sourceJar(
        file: Path,
        sources: Map<String, String>
    ): Path {
        file.parent.createDirectories()
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            sources.forEach { (relative, content) ->
                zip.putNextEntry(ZipEntry(relative))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun compileJava(
        sources: List<Path>,
        classes: Path,
        classpath: List<Path> = emptyList()
    ) {
        classes.createDirectories()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for model-data compatibility tests")
        val diagnostics = ByteArrayOutputStream()
        val arguments = mutableListOf(
            "-proc:none",
            "-d",
            classes.toString()
        )
        if (classpath.isNotEmpty()) {
            arguments += listOf(
                "-classpath",
                classpath.joinToString(System.getProperty("path.separator"))
            )
        }
        arguments += sources.map(Path::toString)
        val result = compiler.run(
            null,
            diagnostics,
            diagnostics,
            *arguments.toTypedArray()
        )
        assertEquals(0, result, diagnostics.toString(Charsets.UTF_8))
    }

    private fun artifact(
        file: Path,
        component: String,
        order: Int,
        kind: LegacyMigrationArtifactInventory.ComponentKind =
            LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM
    ): LegacyMigrationArtifactInventory.Artifact =
        LegacyMigrationArtifactInventory.Artifact(
            component = component,
            componentKind = kind,
            displayName = file.fileName.toString(),
            configurations = setOf("compileClasspath"),
            file = file,
            sha256 = sha256(file),
            resolutionConfigurations = setOf(":|compileClasspath"),
            configurationOrder = mapOf(":|compileClasspath" to order)
        )

    private fun nonClasspathArtifact(
        file: Path,
        component: String
    ): LegacyMigrationArtifactInventory.Artifact =
        LegacyMigrationArtifactInventory.Artifact(
            component = component,
            componentKind =
            LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM,
            displayName = file.fileName.toString(),
            configurations = emptySet(),
            file = file,
            sha256 = sha256(file),
            resolutionConfigurations = emptySet(),
            configurationOrder = emptyMap()
        )

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Fixture(
        val project: Path,
        val inventory: LegacyMigrationArtifactPair
    )
}
