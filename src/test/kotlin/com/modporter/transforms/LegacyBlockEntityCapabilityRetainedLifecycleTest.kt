package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.LegacyBlockEntityCapabilityQueries
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyBlockEntityCapabilityRetainedLifecycleTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `exact retained Create site generates required two phase mixins and executable lifecycle`() {
        val compatPackage = "com.modporter.generated.create.compat"
        val sourceRoot = directory.resolve("src/main/java")
        val compat = sourceRoot.resolve(compatPackage.replace('.', '/')).createDirectories()
        compat.resolve("LazyOptional.java").writeText(BuildSystemPass.legacyLazyOptionalShim(compatPackage))
        compat.resolve("Capability.java").writeText(BuildSystemPass.legacyCapabilityShim(compatPackage))
        directory.resolve("build.gradle").writeText(
            """
            plugins {
                id("net.neoforged.moddev.legacyforge") version "2.0.107"
            }
            legacyForge {
                version = "1.20.1-47.4.0"
            }
            """.trimIndent()
        )
        directory.resolve("src/main/resources/META-INF/neoforge.mods.toml").apply {
            parent.createDirectories()
            writeText("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n\n[[mods]]\nmodId=\"create\"\nversion=\"test\"\ndisplayName=\"Create\"\n")
        }

        val createSource = writeJava(
            "com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java",
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
            """
        )

        val dryRun = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = true
        )
        assertTrue(dryRun.gates.isEmpty(), dryRun.gates.joinToString("\n"))
        assertFalse(createSource.readText().contains("getRetained("), createSource.readText())
        assertFalse(directory.resolve("src/main/resources/modporter-legacy-capability-query.mixins.json").exists())

        val result = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = false
        )
        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertTrue(createSource.readText().contains("LegacyBlockEntityCapabilityQueries.getRetained("), createSource.readText())

        val config = directory.resolve("src/main/resources/modporter-legacy-capability-query.mixins.json")
        assertTrue(config.exists())
        val configText = config.readText()
        assertTrue(configText.contains("\"required\": true"), configText)
        assertTrue(configText.contains("\"defaultRequire\": 1"), configText)
        assertTrue(configText.contains("LegacyCapabilityServerLevelMixin"), configText)
        assertTrue(configText.contains("LegacyCapabilityLevelExtensionMixin"), configText)

        val serverMixin = compat.resolve("mixin/LegacyCapabilityServerLevelMixin.java")
        val defaultMixin = compat.resolve("mixin/LegacyCapabilityLevelExtensionMixin.java")
        assertTrue(serverMixin.readText().contains("@WrapMethod("), serverMixin.readText())
        assertTrue(serverMixin.readText().contains("require = 1"), serverMixin.readText())
        assertTrue(serverMixin.readText().contains("expect = 1"), serverMixin.readText())
        assertTrue(serverMixin.readText().contains("runInvalidation("), serverMixin.readText())
        assertTrue(defaultMixin.readText().contains("ILevelExtension.class"), defaultMixin.readText())
        assertTrue(
            defaultMixin.readText().contains("public interface LegacyCapabilityLevelExtensionMixin"),
            defaultMixin.readText()
        )
        assertFalse(
            defaultMixin.readText().contains("class LegacyCapabilityLevelExtensionMixin"),
            defaultMixin.readText()
        )
        assertTrue(defaultMixin.readText().contains("@WrapMethod("), defaultMixin.readText())
        assertTrue(defaultMixin.readText().contains("remap = false"), defaultMixin.readText())
        assertTrue(defaultMixin.readText().contains("runInvalidation("), defaultMixin.readText())

        val second = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = false
        )
        assertTrue(second.gates.isEmpty(), second.gates.joinToString("\n"))
        assertTrue(second.changes.isEmpty(), second.changes.joinToString("\n"))

        val buildResult = BuildSystemPass().apply(directory)
        assertTrue(buildResult.errors.isEmpty(), buildResult.errors.joinToString("\n"))
        assertTrue(
            buildResult.changes.any { it.ruleId == "build-register-existing-mixin-config" },
            buildResult.changes.joinToString("\n")
        )
        val modsMetadata = directory.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(modsMetadata.contains("[[mixins]]"), modsMetadata)
        assertTrue(
            modsMetadata.contains("config=\"modporter-legacy-capability-query.mixins.json\""),
            modsMetadata
        )

        createSource.deleteIfExists()
        writeRuntimeStubs(compatPackage)
        compat.resolve("LegacyCapabilities.java").writeText(
            """
            package $compatPackage;
            public final class LegacyCapabilities {
                public static final Capability<Object> ITEM_HANDLER = new Capability<>();
                public static final Capability<Object> FLUID_HANDLER = new Capability<>();
                public static final Capability<Object> ENERGY = new Capability<>();
                private LegacyCapabilities() {}
            }
            """.trimIndent()
        )
        compat.resolve("LegacyCapabilityProvider.java").writeText(
            """
            package $compatPackage;
            import net.minecraft.core.Direction;
            public interface LegacyCapabilityProvider {
                <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side);
            }
            """.trimIndent()
        )
        compat.resolve("LegacyBlockEntityCapabilities.java").deleteIfExists()
        writeLifecycleProbe(compatPackage)

        val classes = directory.resolve("classes").createDirectories()
        val javaFiles = sourceRoot.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.absolutePath }
            .toList()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(
            0,
            compiler.run(
                null,
                diagnostics,
                diagnostics,
                "-d",
                classes.toString(),
                *javaFiles.toTypedArray()
            ),
            diagnostics.toString()
        )
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("example.RetainedLifecycleProbe").getMethod("check").invoke(null)
        }
    }

    @Test
    fun `reviewed retained profiles bind all six exact paths and external consumer`() {
        val dependencyPath =
            "src/main/java/com/simibubi/create/api/equipment/goggles/IHaveGoggleInformation.java"
        val dependencyDigest = "5bee65849fb6955e23006064e586ed528c320ad2cd4d0570bcac832d0c657053"
        val reviewed = linkedMapOf(
            "src/main/java/com/simibubi/create/content/fluids/FlowSource.java" to
                "273cf38dc11bfa48cd1f33931adb08720b20bb3fed64b4215afa68d62247d28d",
            "src/main/java/com/simibubi/create/content/fluids/tank/FluidTankBlockEntity.java" to
                "b9f7588f83df79f05d0f38af77e34db7dfc7407c652019d4d35e0f07c2d2014d",
            "src/main/java/com/simibubi/create/content/kinetics/mechanicalArm/ArmInteractionPoint.java" to
                "4898572486cdcc925dd861744502f6343b4d8a61e5677702d3d29112cfc79030",
            "src/main/java/com/simibubi/create/content/logistics/chute/ChuteBlockEntity.java" to
                "73d71e1ccdfdb41bbceb8834aa88aaad7e1fe2851b1a03aa7b2b98bc5cdd274a",
            "src/main/java/com/simibubi/create/content/logistics/tunnel/BrassTunnelBlockEntity.java" to
                "3d4488256e745a5b1d91a98d7c50e29c8a22dbb02612cd87579efd4fdd825823",
            "src/main/java/com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java" to
                "a7f0ce4469a1e9b61c09c571070f67ced67bdfb946e174159665957c41af41be"
        )
        val supersededDirectStageDigests = linkedMapOf(
            "src/main/java/com/simibubi/create/content/fluids/FlowSource.java" to
                "4492e7cd9b715237e401b697fc718d111dd7f04b585a561cbef5bfb21f96691f",
            "src/main/java/com/simibubi/create/content/fluids/tank/FluidTankBlockEntity.java" to
                "f8e3af0a50159ca05b4ae44e545570a794a58da8a0c8cabbc6e88f1872f2dd5f",
            "src/main/java/com/simibubi/create/content/kinetics/mechanicalArm/ArmInteractionPoint.java" to
                "f01325deb58b04d5a6bdc31e58681068be3c126363962fb76d6969c0b42ca09f",
            "src/main/java/com/simibubi/create/content/logistics/chute/ChuteBlockEntity.java" to
                "3e6a1dfd7d2f93784b14deb2076da6448a1f0334f4b39380104847a74c652372",
            "src/main/java/com/simibubi/create/content/logistics/tunnel/BrassTunnelBlockEntity.java" to
                "ba73084f4380dbbe8756934cb040eb95805e3603b038ae81e9c68b4a0790a517",
            "src/main/java/com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java" to
                "dc1ae7eebf9a08cfba58ec570bce705854d631fbb0df5c51e4a6f44e880cab9b"
        )
        val projectDigests = reviewed + (dependencyPath to dependencyDigest)

        reviewed.forEach { (path, digest) ->
            assertTrue(
                LegacyBlockEntityCapabilityQueries.reviewedRetainedSourceDigestProfile(
                    path,
                    digest,
                    projectDigests
                ),
                path
            )
        }

        supersededDirectStageDigests.forEach { (path, digest) ->
            assertFalse(
                LegacyBlockEntityCapabilityQueries.reviewedRetainedSourceDigestProfile(
                    path,
                    digest,
                    projectDigests
                ),
                "Superseded direct-stage digest remained admitted: $path"
            )
        }

        val flow = reviewed.entries.first()
        val tank = reviewed.entries.elementAt(1)
        assertFalse(
            LegacyBlockEntityCapabilityQueries.reviewedRetainedSourceDigestProfile(
                "shadow/${flow.key}",
                flow.value,
                projectDigests
            )
        )
        assertFalse(
            LegacyBlockEntityCapabilityQueries.reviewedRetainedSourceDigestProfile(
                flow.key,
                tank.value,
                projectDigests
            )
        )
        assertFalse(
            LegacyBlockEntityCapabilityQueries.reviewedRetainedSourceDigestProfile(
                tank.key,
                tank.value,
                projectDigests - dependencyPath
            )
        )
        assertFalse(
            LegacyBlockEntityCapabilityQueries.reviewedRetainedSourceDigestProfile(
                tank.key,
                tank.value,
                projectDigests + (
                    dependencyPath to
                        "2e373b54fab5899c4f16af37e31e81d5c4a2b8d8108b4555d3babd8a0d6a5e64"
                    )
            )
        )
        assertFalse(
            LegacyBlockEntityCapabilityQueries.reviewedRetainedSourceDigestProfile(
                tank.key,
                tank.value,
                projectDigests + (dependencyPath to "0".repeat(64))
            )
        )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MODPORTER_CREATE_PROFILE_REPLAY", matches = ".+")
    fun `configured Create tree admits exactly the six reviewed retained sites without mutation`() {
        val capture = Path.of(System.getenv("MODPORTER_CREATE_PROFILE_REPLAY"))
            .toAbsolutePath()
            .normalize()
        val project = directory.resolve("create-profile-replay")
        val expectedSources = setOf(
            "src/main/java/com/simibubi/create/content/fluids/FlowSource.java",
            "src/main/java/com/simibubi/create/content/fluids/tank/FluidTankBlockEntity.java",
            "src/main/java/com/simibubi/create/content/kinetics/mechanicalArm/ArmInteractionPoint.java",
            "src/main/java/com/simibubi/create/content/logistics/chute/ChuteBlockEntity.java",
            "src/main/java/com/simibubi/create/content/logistics/tunnel/BrassTunnelBlockEntity.java",
            "src/main/java/com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java"
        )
        val reviewedInputs = expectedSources +
            "src/main/java/com/simibubi/create/api/equipment/goggles/IHaveGoggleInformation.java"
        val captureBefore = reviewedInputs.associateWith { relative -> capture.resolve(relative).readText() }
        reviewedInputs.forEach { relative ->
            val target = project.resolve(relative)
            target.parent.createDirectories()
            Files.copy(capture.resolve(relative), target)
        }
        writeCreateBlockEntityHierarchy(project)
        val stagedBefore = reviewedInputs.associateWith { relative -> project.resolve(relative).readText() }
        assertEquals(captureBefore, stagedBefore)
        val mixinConfig = project.resolve(
            "src/main/resources/modporter-legacy-capability-query.mixins.json"
        )
        assertFalse(mixinConfig.exists(), mixinConfig.toString())

        val result = LegacyBlockEntityCapabilityQueries.migrate(
            project,
            { "com.modporter.generated.create.compat" },
            dryRun = true
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        val retainedSources = result.retainedFiles.asSequence()
            .map { file ->
                project.relativize(file.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/')
            }
            .toSet()
        assertEquals(expectedSources, retainedSources)
        assertTrue(
            result.changes.any { change ->
                change.file.toAbsolutePath().normalize() == mixinConfig &&
                    change.after.contains("LegacyCapabilityLevelExtensionMixin")
            },
            result.changes.joinToString("\n")
        )
        assertFalse(mixinConfig.exists(), mixinConfig.toString())
        stagedBefore.forEach { (relative, source) ->
            assertEquals(source, project.resolve(relative).readText(), "staged $relative")
            assertEquals(captureBefore.getValue(relative), capture.resolve(relative).readText(), "capture $relative")
        }
    }

    @Test
    fun `comment and string only drift cannot impersonate reviewed retained source`() {
        val compatPackage = "com.modporter.generated.create.compat"
        val source = writeJava(
            "com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java",
            reviewedSchematicannonSource(compatPackage)
        )
        val baseline = source.readText()

        val accepted = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = true
        )
        assertTrue(accepted.gates.isEmpty(), accepted.gates.joinToString("\n"))

        source.writeText(
            baseline +
                "\n// attachedInventories.clear() capability.isPresent() getCapability retained alias\n"
        )
        val commentDrift = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = true
        )
        assertTrue(
            commentDrift.gates.any { it.contains("cannot prove LazyOptional lifecycle use") },
            commentDrift.gates.joinToString("\n")
        )
        assertTrue(
            commentDrift.gates.any { it.contains("sourceStageSha256=") },
            commentDrift.gates.joinToString("\n")
        )

        source.writeText(
            baseline.replace(
                "public class SchematicannonBlockEntity {",
                "public class SchematicannonBlockEntity {\n" +
                    "    private static final String ADMISSION_NOISE = " +
                    "\"attachedInventories.clear() capability.isPresent() getCapability\";"
            )
        )
        val stringDrift = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = true
        )
        assertTrue(
            stringDrift.gates.any { it.contains("cannot prove LazyOptional lifecycle use") },
            stringDrift.gates.joinToString("\n")
        )
        assertTrue(
            stringDrift.gates.any { it.contains("sourceStageSha256=") },
            stringDrift.gates.joinToString("\n")
        )
    }

    @Test
    fun `same exact Create owner without refresh topology remains gated`() {
        val compatPackage = "com.modporter.generated.create.compat"
        writeJava(
            "com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java",
            """
            package com.simibubi.create.content.schematics.cannon;
            import $compatPackage.LazyOptional;
            import java.util.LinkedHashSet;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.items.IItemHandler;
            public class SchematicannonBlockEntity {
                public LinkedHashSet<LazyOptional<IItemHandler>> attachedInventories = new LinkedHashSet<>();
                public void findInventories(BlockEntity blockEntity, Direction facing) {
                    LazyOptional<IItemHandler> capability = blockEntity.getCapability(Capabilities.ItemHandler.BLOCK, facing.getOpposite());
                    attachedInventories.add(capability);
                }
            }
            """
        )
        val result = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = false
        )
        assertTrue(
            result.gates.any { it.contains("cannot prove LazyOptional lifecycle use") },
            result.gates.joinToString("\n")
        )
    }

    @Test
    fun `same exact Create owner with listener or extra alias remains gated`() {
        val compatPackage = "com.modporter.generated.create.compat"
        val source = writeJava(
            "com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java",
            schematicannonDriftSource(compatPackage, "capability.addListener(ignored -> {});")
        )
        val listenerResult = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = false
        )
        assertTrue(
            listenerResult.gates.any { it.contains("cannot prove LazyOptional lifecycle use") },
            listenerResult.gates.joinToString("\n")
        )

        source.writeText(
            schematicannonDriftSource(
                compatPackage,
                "LazyOptional<IItemHandler> alias = capability;"
            )
        )
        val aliasResult = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = false
        )
        assertTrue(
            aliasResult.gates.any { it.contains("cannot prove LazyOptional lifecycle use") },
            aliasResult.gates.joinToString("\n")
        )
    }

    private fun reviewedSchematicannonSource(compatPackage: String): String =
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
        """

    private fun schematicannonDriftSource(compatPackage: String, extraUse: String): String =
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
                    if (!level.isLoaded(worldPosition.relative(facing))) continue;
                    BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(facing));
                    if (blockEntity != null) {
                        LazyOptional<IItemHandler> capability = blockEntity.getCapability(
                            Capabilities.ItemHandler.BLOCK, facing.getOpposite()
                        );
                        $extraUse
                        if (capability.isPresent()) attachedInventories.add(capability);
                    }
                }
            }
        }
        """.trimIndent()

    private fun writeRuntimeStubs(compatPackage: String) {
        writeJava(
            "net/minecraft/core/Direction.java",
            """
            package net.minecraft.core;
            public enum Direction {
                UP, DOWN, NORTH, SOUTH, EAST, WEST;
                public Direction getOpposite() { return this == UP ? DOWN : UP; }
            }
            """
        )
        writeJava(
            "net/minecraft/core/BlockPos.java",
            """
            package net.minecraft.core;
            public final class BlockPos {
                private final long value;
                public BlockPos(long value) { this.value = value; }
                public long asLong() { return value; }
                public BlockPos relative(Direction direction) { return new BlockPos(value + direction.ordinal() + 1); }
                @Override public boolean equals(Object other) {
                    return other instanceof BlockPos pos && value == pos.value;
                }
                @Override public int hashCode() { return Long.hashCode(value); }
            }
            """
        )
        writeJava(
            "net/minecraft/world/level/ChunkPos.java",
            """
            package net.minecraft.world.level;
            import net.minecraft.core.BlockPos;
            public final class ChunkPos {
                private final long value;
                public ChunkPos(long value) { this.value = value; }
                public long toLong() { return value; }
                public static long asLong(BlockPos pos) { return pos.asLong(); }
            }
            """
        )
        writeJava(
            "net/minecraft/world/level/Level.java",
            """
            package net.minecraft.world.level;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public class Level {
                private Object handler = "A";
                private int queries;
                private BlockEntity blockEntity;
                public void setHandler(Object handler) { this.handler = handler; }
                public int queries() { return queries; }
                public boolean isLoaded(BlockPos pos) { return true; }
                public void place(BlockEntity owner) { this.blockEntity = owner; }
                public BlockEntity getBlockEntity(BlockPos pos) {
                    return blockEntity != null && blockEntity.getBlockPos().equals(pos) ? blockEntity : null;
                }
                @SuppressWarnings("unchecked")
                public <T> T getCapability(Object capability, BlockPos pos, Object state, BlockEntity owner, Direction side) {
                    queries++;
                    return (T) handler;
                }
            }
            """
        )
        writeJava(
            "net/minecraft/server/level/ServerLevel.java",
            """
            package net.minecraft.server.level;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.ChunkPos;
            import net.minecraft.world.level.Level;
            public class ServerLevel extends Level {
                public void invalidateCapabilities(BlockPos pos) {}
                public void invalidateCapabilities(ChunkPos pos) {}
            }
            """
        )
        writeJava(
            "net/minecraft/world/level/block/entity/BlockEntity.java",
            """
            package net.minecraft.world.level.block.entity;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.Level;
            public class BlockEntity {
                private Level level;
                private final BlockPos pos;
                private boolean removed;
                public BlockEntity(Level level, BlockPos pos) {
                    this.level = level;
                    this.pos = pos;
                    if (level != null) level.place(this);
                }
                public Level getLevel() { return level; }
                public void setLevel(Level level) {
                    this.level = level;
                    if (level != null) level.place(this);
                }
                public BlockPos getBlockPos() { return pos; }
                public Object getBlockState() { return this; }
                public boolean isRemoved() { return removed; }
                public void setRemoved(boolean removed) { this.removed = removed; }
                public void invalidateCapabilities() {}
            }
            """
        )
        writeJava(
            "net/neoforged/neoforge/capabilities/Capabilities.java",
            """
            package net.neoforged.neoforge.capabilities;
            public final class Capabilities {
                public static final class ItemHandler { public static final Object BLOCK = new Object(); }
                public static final class FluidHandler { public static final Object BLOCK = new Object(); }
                public static final class EnergyStorage { public static final Object BLOCK = new Object(); }
            }
            """
        )
        writeJava(
            "net/neoforged/neoforge/common/extensions/ILevelExtension.java",
            """
            package net.neoforged.neoforge.common.extensions;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.ChunkPos;
            public interface ILevelExtension {
                default void invalidateCapabilities(BlockPos pos) {}
                default void invalidateCapabilities(ChunkPos pos) {}
            }
            """
        )
        writeJava("net/neoforged/neoforge/items/IItemHandler.java", "package net.neoforged.neoforge.items; public interface IItemHandler {}")
        writeJava(
            "org/spongepowered/asm/mixin/Mixin.java",
            """
            package org.spongepowered.asm.mixin;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            public @interface Mixin { Class<?>[] value() default {}; boolean remap() default true; }
            """
        )
        writeJava(
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation.java",
            """
            package com.llamalad7.mixinextras.injector.wrapoperation;
            public interface Operation<R> { R call(Object... args); }
            """
        )
        writeJava(
            "com/llamalad7/mixinextras/injector/wrapmethod/WrapMethod.java",
            """
            package com.llamalad7.mixinextras.injector.wrapmethod;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
            public @interface WrapMethod {
                String[] method();
                boolean remap() default true;
                int require() default -1;
                int expect() default 1;
                int allow() default -1;
            }
            """
        )
    }

    private fun writeLifecycleProbe(compatPackage: String) {
        writeJava(
            "example/RetainedLifecycleProbe.java",
            """
            package example;

            import $compatPackage.Capability;
            import $compatPackage.LazyOptional;
            import $compatPackage.LegacyBlockEntityCapabilityQueries;
            import $compatPackage.LegacyCapabilities;
            import $compatPackage.LegacyCapabilityProvider;
            import java.util.concurrent.atomic.AtomicBoolean;
            import java.util.concurrent.atomic.AtomicInteger;
            import java.util.concurrent.atomic.AtomicReference;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.ChunkPos;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public final class RetainedLifecycleProbe {
                public static void check() {
                    checkLevel(new Level());
                    checkLevel(new ServerLevel());
                    checkLevelNullPlacement();
                    checkNativeNullPresence();
                    checkRemovedAndReplacedOwner();
                    checkProviderIdentity();
                    checkListenerExceptionLeavesFreshCache();
                    checkNativeFailureStillCompletes();
                }

                private static void checkLevel(Level level) {
                    BlockPos pos = new BlockPos(7);
                    BlockEntity owner = new BlockEntity(level, pos);
                    LazyOptional<Object> first = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ITEM_HANDLER, Direction.UP
                    );
                    if (first != LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ITEM_HANDLER, Direction.UP
                    )) throw new AssertionError("same retained query lost wrapper identity");

                    AtomicInteger notices = new AtomicInteger();
                    AtomicBoolean insideNative = new AtomicBoolean();
                    AtomicReference<LazyOptional<Object>> reacquired = new AtomicReference<>();
                    first.addListener(old -> {
                        if (insideNative.get()) throw new AssertionError("listener ran inside native invalidation");
                        if (old.isPresent()) throw new AssertionError("old wrapper was not terminal");
                        LazyOptional<Object> fresh = LegacyBlockEntityCapabilityQueries.getRetained(
                            owner, LegacyCapabilities.ITEM_HANDLER, Direction.UP
                        );
                        if (fresh == old || !"B".equals(fresh.orElse(null)))
                            throw new AssertionError("listener did not reacquire replacement");
                        reacquired.set(fresh);
                        notices.incrementAndGet();
                    });
                    if (level.queries() != 1) throw new AssertionError("retained acquisition did not query native state once");
                    if (!"A".equals(first.orElse(null)) || level.queries() != 1)
                        throw new AssertionError("wrapper read repeated the native query");

                    level.setHandler("B");
                    LegacyBlockEntityCapabilityQueries.runInvalidation(level, pos, () -> {
                        insideNative.set(true);
                        try {
                            if (first.isPresent()) throw new AssertionError("old wrapper remained present at invalidation head");
                            if (notices.get() != 0) throw new AssertionError("listener ran before native invalidation completed");
                        } finally {
                            insideNative.set(false);
                        }
                    });
                    if (notices.get() != 1) throw new AssertionError("listener did not run exactly once");
                    LazyOptional<Object> second = reacquired.get();
                    if (second == null || second != LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ITEM_HANDLER, Direction.UP
                    ) || !second.isPresent()) throw new AssertionError("fresh wrapper did not survive invalidation");

                    AtomicInteger nestedNotices = new AtomicInteger();
                    second.addListener(old -> nestedNotices.incrementAndGet());
                    level.setHandler("C");
                    LegacyBlockEntityCapabilityQueries.runInvalidation(level, pos, () -> {
                        LegacyBlockEntityCapabilityQueries.runInvalidation(
                            level,
                            new ChunkPos(pos.asLong()),
                            () -> {
                                if (nestedNotices.get() != 0)
                                    throw new AssertionError("nested invalidation drained inside nested native call");
                            }
                        );
                        if (nestedNotices.get() != 0)
                            throw new AssertionError("nested invalidation drained before outer native return");
                    });
                    if (nestedNotices.get() != 1) throw new AssertionError("nested invalidation did not drain once");
                    LazyOptional<Object> third = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ITEM_HANDLER, Direction.UP
                    );
                    if (third == second || !"C".equals(third.orElse(null)))
                        throw new AssertionError("chunk/position invalidation did not replace wrapper");
                }

                private static void checkLevelNullPlacement() {
                    BlockPos pos = new BlockPos(13);
                    BlockEntity owner = new BlockEntity(null, pos);
                    LazyOptional<Object> beforePlacement = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.FLUID_HANDLER, Direction.DOWN
                    );
                    if (beforePlacement.isPresent())
                        throw new AssertionError("level-null lookup reported a present native handler");
                    Level level = new Level();
                    owner.setLevel(level);
                    LazyOptional<Object> afterPlacement = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.FLUID_HANDLER, Direction.DOWN
                    );
                    if (!afterPlacement.isPresent() || afterPlacement == beforePlacement)
                        throw new AssertionError("level-null empty result was retained after placement");
                    AtomicInteger notices = new AtomicInteger();
                    afterPlacement.addListener(old -> notices.incrementAndGet());
                    LegacyBlockEntityCapabilityQueries.runInvalidation(level, pos, () -> {});
                    if (notices.get() != 1 || afterPlacement.isPresent())
                        throw new AssertionError("placed wrapper did not terminate on invalidation");
                }

                private static void checkNativeNullPresence() {
                    Level level = new Level();
                    level.setHandler(null);
                    BlockEntity owner = new BlockEntity(level, new BlockPos(17));
                    LazyOptional<Object> ordinaryMissing = LegacyBlockEntityCapabilityQueries.get(
                        owner, LegacyCapabilities.ITEM_HANDLER, Direction.SOUTH
                    );
                    LazyOptional<Object> missing = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ITEM_HANDLER, Direction.SOUTH
                    );
                    if (ordinaryMissing.isPresent() || ordinaryMissing.orElse(null) != null ||
                        missing.isPresent() || missing.orElse(null) != null)
                        throw new AssertionError("native null result reported present");
                    level.setHandler("available");
                    LazyOptional<Object> ordinaryAvailable = LegacyBlockEntityCapabilityQueries.get(
                        owner, LegacyCapabilities.ITEM_HANDLER, Direction.SOUTH
                    );
                    LazyOptional<Object> available = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ITEM_HANDLER, Direction.SOUTH
                    );
                    if (!ordinaryAvailable.isPresent() ||
                        !"available".equals(ordinaryAvailable.orElse(null)) ||
                        !available.isPresent() || !"available".equals(available.orElse(null)))
                        throw new AssertionError("native null result was incorrectly retained");
                }

                private static void checkRemovedAndReplacedOwner() {
                    Level level = new Level();
                    BlockPos pos = new BlockPos(19);
                    BlockEntity original = new BlockEntity(level, pos);
                    LazyOptional<Object> originalWrapper = LegacyBlockEntityCapabilityQueries.getRetained(
                        original, LegacyCapabilities.ITEM_HANDLER, Direction.EAST
                    );
                    BlockEntity replacement = new BlockEntity(level, pos);
                    LazyOptional<Object> staleQuery = LegacyBlockEntityCapabilityQueries.getRetained(
                        original, LegacyCapabilities.ITEM_HANDLER, Direction.EAST
                    );
                    if (staleQuery.isPresent())
                        throw new AssertionError("replaced block entity still queried native capability");
                    LegacyBlockEntityCapabilityQueries.runInvalidation(level, pos, () -> {});
                    if (originalWrapper.isPresent())
                        throw new AssertionError("replacement invalidation did not terminate original wrapper");
                    LazyOptional<Object> replacementWrapper = LegacyBlockEntityCapabilityQueries.getRetained(
                        replacement, LegacyCapabilities.ITEM_HANDLER, Direction.EAST
                    );
                    if (!replacementWrapper.isPresent())
                        throw new AssertionError("replacement block entity did not acquire native capability");
                    replacement.setRemoved(true);
                    LazyOptional<Object> removedQuery = LegacyBlockEntityCapabilityQueries.getRetained(
                        replacement, LegacyCapabilities.ITEM_HANDLER, Direction.EAST
                    );
                    if (removedQuery.isPresent())
                        throw new AssertionError("removed block entity still queried native capability");
                    LegacyBlockEntityCapabilityQueries.runInvalidation(level, pos, () -> {});
                    if (replacementWrapper.isPresent())
                        throw new AssertionError("removed block entity wrapper did not terminate");
                }

                private static void checkProviderIdentity() {
                    ProviderBlockEntity provider = new ProviderBlockEntity();
                    LazyOptional<Object> queried = LegacyBlockEntityCapabilityQueries.getRetained(
                        provider, LegacyCapabilities.ITEM_HANDLER, Direction.NORTH
                    );
                    if (queried != provider.optional)
                        throw new AssertionError("source provider wrapper identity was not preserved");
                }

                private static void checkListenerExceptionLeavesFreshCache() {
                    Level level = new Level();
                    BlockEntity owner = new BlockEntity(level, new BlockPos(23));
                    LazyOptional<Object> old = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ENERGY, null
                    );
                    old.addListener(ignored -> { throw new Expected(); });
                    try {
                        LegacyBlockEntityCapabilityQueries.runInvalidation(level, owner.getBlockPos(), () -> {});
                        throw new AssertionError("listener exception was swallowed");
                    } catch (Expected expected) {
                    }
                    LazyOptional<Object> fresh = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ENERGY, null
                    );
                    if (fresh == old || !fresh.isPresent())
                        throw new AssertionError("exception left stale retained cache state");
                }


                private static void checkNativeFailureStillCompletes() {
                    Level level = new Level();
                    BlockEntity owner = new BlockEntity(level, new BlockPos(29));
                    LazyOptional<Object> old = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ENERGY, Direction.WEST
                    );
                    old.addListener(ignored -> { throw new ListenerExpected(); });
                    try {
                        LegacyBlockEntityCapabilityQueries.runInvalidation(
                            level,
                            owner.getBlockPos(),
                            () -> { throw new NativeExpected(); }
                        );
                        throw new AssertionError("native invalidation failure was swallowed");
                    } catch (NativeExpected expected) {
                        if (expected.getSuppressed().length != 1 ||
                            !(expected.getSuppressed()[0] instanceof ListenerExpected))
                            throw new AssertionError("listener failure was not suppressed onto native failure");
                    }
                    LazyOptional<Object> fresh = LegacyBlockEntityCapabilityQueries.getRetained(
                        owner, LegacyCapabilities.ENERGY, Direction.WEST
                    );
                    if (old.isPresent() || fresh == old || !fresh.isPresent())
                        throw new AssertionError("native failure stranded invalidation state");
                }

                private static final class Expected extends RuntimeException {}
                private static final class NativeExpected extends RuntimeException {}
                private static final class ListenerExpected extends RuntimeException {}

                private static final class ProviderBlockEntity extends BlockEntity
                    implements LegacyCapabilityProvider {
                    private final LazyOptional<Object> optional = LazyOptional.of(() -> "provider");
                    private ProviderBlockEntity() { super(new Level(), new BlockPos(31)); }
                    @Override
                    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                        return optional.cast();
                    }
                }
            }
            """
        )
    }

    private fun writeCreateBlockEntityHierarchy(project: Path) {
        val root = project.resolve("src/main/java/com/simibubi/create/foundation/blockEntity")
            .createDirectories()
        root.resolve("SmartBlockEntity.java").writeText(
            """
            package com.simibubi.create.foundation.blockEntity;
            public abstract class SmartBlockEntity extends CachedRenderBBBlockEntity {}
            """.trimIndent()
        )
        root.resolve("CachedRenderBBBlockEntity.java").writeText(
            """
            package com.simibubi.create.foundation.blockEntity;
            public abstract class CachedRenderBBBlockEntity extends SyncedBlockEntity {}
            """.trimIndent()
        )
        root.resolve("SyncedBlockEntity.java").writeText(
            """
            package com.simibubi.create.foundation.blockEntity;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public abstract class SyncedBlockEntity extends BlockEntity {}
            """.trimIndent()
        )
    }

    private fun writeJava(relative: String, source: String): Path {
        val file = directory.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }
}
