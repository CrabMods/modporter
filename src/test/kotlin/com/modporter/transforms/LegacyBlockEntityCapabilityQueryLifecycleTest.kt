package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.LegacyBlockEntityCapabilities
import com.modporter.core.transforms.structural.LegacyBlockEntityCapabilityQueries
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyBlockEntityCapabilityQueryLifecycleTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `statically block entity query retains provider optional identity across invalidation`() {
        val sourceRoot = directory.resolve("src/main/java")
        val compatPackage = "com.modporter.generated.example.compat"
        val compat = sourceRoot.resolve(compatPackage.replace('.', '/')).createDirectories()
        compat.resolve("LazyOptional.java").writeText(BuildSystemPass.legacyLazyOptionalShim(compatPackage))
        compat.resolve("Capability.java").writeText(BuildSystemPass.legacyCapabilityShim(compatPackage))
        compat.resolve("LegacyCapabilities.java").writeText(
            """
            package $compatPackage;

            public final class LegacyCapabilities {
                public static final Capability<Object> ITEM_HANDLER = new Capability<>();
                public static final Capability<Object> FLUID_HANDLER = new Capability<>();
                public static final Capability<Object> ENERGY = new Capability<>();

                private LegacyCapabilities() {
                }
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

        writeJava(
            "net/minecraft/core/Direction.java",
            """
            package net.minecraft.core;
            public final class Direction {
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

            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public class Level {
                private BlockEntity blockEntity;
                public void place(BlockEntity blockEntity) { this.blockEntity = blockEntity; }
                public BlockEntity getBlockEntity(Object pos) { return blockEntity; }
                public <T> T getCapability(
                    Object capability,
                    Object pos,
                    Object state,
                    BlockEntity blockEntity,
                    Direction side
                ) {
                    return null;
                }
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
                private final Level level = new Level();
                private final BlockPos pos = new BlockPos(0);
                public BlockEntity() { level.place(this); }

                public Level getLevel() {
                    return level;
                }

                public BlockPos getBlockPos() {
                    return pos;
                }

                public Object getBlockState() {
                    return this;
                }

                public boolean isRemoved() {
                    return false;
                }

                public void invalidateCapabilities() {
                }
            }
            """
        )
        writeJava(
            "net/neoforged/neoforge/capabilities/Capabilities.java",
            """
            package net.neoforged.neoforge.capabilities;

            public final class Capabilities {
                public static final class ItemHandler {
                    public static final Object BLOCK = new Object();
                }

                public static final class FluidHandler {
                    public static final Object BLOCK = new Object();
                }

                public static final class EnergyStorage {
                    public static final Object BLOCK = new Object();
                }
            }
            """
        )
        val probeSource = writeJava(
            "example/ProbeSource.java",
            """
            package example;

            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilities;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public final class ProbeSource {
                private static LazyOptional<Object> cached = LazyOptional.empty();

                public static LazyOptional<Object> query(BlockEntity blockEntity) {
                    if (cached.isPresent())
                        return cached;
                    cached = blockEntity.getCapability(
                        LegacyCapabilities.FLUID_HANDLER, null
                    );
                    return cached;
                }
            }
            """
        )
        writeJava(
            "example/ProviderBlockEntity.java",
            """
            package example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilities;
            import com.modporter.generated.example.compat.LegacyCapabilityProvider;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public final class ProviderBlockEntity extends BlockEntity
                implements LegacyCapabilityProvider {
                private LazyOptional<Object> current = LazyOptional.of(() -> "A");

                public LazyOptional<Object> current() {
                    return current;
                }

                public void swap() {
                    LazyOptional<Object> previous = current;
                    current = LazyOptional.of(() -> "B");
                    previous.invalidate();
                }

                @Override
                public <T> LazyOptional<T> getCapability(
                    Capability<T> capability, Direction side
                ) {
                    if (capability == LegacyCapabilities.FLUID_HANDLER)
                        return current.cast();
                    return LazyOptional.empty();
                }
            }
            """
        )
        writeJava(
            "example/LifecycleProbe.java",
            """
            package example;

            import com.modporter.generated.example.compat.LazyOptional;

            public final class LifecycleProbe {
                public static void check() {
                    ProviderBlockEntity provider = new ProviderBlockEntity();
                    LazyOptional<Object> first = ProbeSource.query(provider);
                    if (first != provider.current() || !"A".equals(first.orElse(null)))
                        throw new AssertionError("first query lost provider optional identity");

                    provider.swap();
                    if (first.isPresent())
                        throw new AssertionError("cached optional did not observe provider invalidation");

                    LazyOptional<Object> second = ProbeSource.query(provider);
                    if (second == first || second != provider.current() ||
                        !"B".equals(second.orElse(null)))
                        throw new AssertionError("query did not reacquire the replacement optional");
                }
            }
            """
        )

        val producerResult = LegacyBlockEntityCapabilities.migrate(
            directory,
            "example",
            { compatPackage },
            dryRun = false
        )
        assertTrue(producerResult.gates.isEmpty(), producerResult.gates.joinToString("\n"))

        val result = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertTrue(
            probeSource.readText().contains(
                "LegacyBlockEntityCapabilityQueries.get(" +
                    "blockEntity, LegacyCapabilities.FLUID_HANDLER, null)"
            ),
            probeSource.readText()
        )

        compat.resolve("LegacyCapabilities.java").writeText(
            """
            package $compatPackage;

            public final class LegacyCapabilities {
                public static final Capability<Object> ITEM_HANDLER = new Capability<>();
                public static final Capability<Object> FLUID_HANDLER = new Capability<>();
                public static final Capability<Object> ENERGY = new Capability<>();

                private LegacyCapabilities() {
                }
            }
            """.trimIndent()
        )
        compat.resolve("LegacyBlockEntityCapabilities.java").deleteIfExists()

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
            loader.loadClass("example.LifecycleProbe").getMethod("check").invoke(null)
        }
    }

    private fun writeJava(relative: String, source: String): Path {
        val file = directory.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }
}
