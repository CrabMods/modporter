package com.modporter.core.transforms.structural

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FinalBlockSourceAdaptersRuntimeTest {
    @TempDir
    lateinit var project: Path

    @Test
    fun `generated boundary captures fresh native records and catches conversion failures`() {
        writeTargetStubs()
        writeSourceWrapper()
        writeBoundary()
        writeRuntimeProbe()

        FinalBlockSourceAdapters.migrate(project, dryRun = false)

        val boundary = read("src/main/java/sample/Boundary.java")
        assertTrue(
            boundary.contains("MobileDispenseOrigin source"),
            boundary
        )
        assertTrue(
            boundary.contains(
                "this.behavior.dispense(source.modporter\$asBlockSource(), stack.copy())"
            ),
            boundary
        )
        assertTrue(
            boundary.indexOf("try {") <
                boundary.indexOf("source.modporter\$asBlockSource()"),
            boundary
        )

        val classes = project.resolve("compiled")
        classes.createDirectories()
        compileJava(classes)

        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val probe = loader.loadClass("sample.RuntimeProbe")
            val result = probe.getMethod("verify").invoke(null)
            assertTrue(result == "verified", result.toString())
        }
    }

    private fun writeTargetStubs() {
        write(
            "src/main/java/javax/annotation/Nullable.java",
            """
            package javax.annotation;

            public @interface Nullable {
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/core/Direction.java",
            """
            package net.minecraft.core;

            public enum Direction {
                NORTH,
                EAST
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/world/phys/Vec3.java",
            """
            package net.minecraft.world.phys;

            public record Vec3(double x, double y, double z) {
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/core/BlockPos.java",
            """
            package net.minecraft.core;

            import net.minecraft.world.phys.Vec3;

            public record BlockPos(int x, int y, int z) {
                public int getX() {
                    return x;
                }

                public int getY() {
                    return y;
                }

                public int getZ() {
                    return z;
                }

                public Vec3 getCenter() {
                    return new Vec3(x + 0.5D, y + 0.5D, z + 0.5D);
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/server/level/ServerLevel.java",
            """
            package net.minecraft.server.level;

            public record ServerLevel(String id) {
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/world/level/block/state/BlockState.java",
            """
            package net.minecraft.world.level.block.state;

            public record BlockState(String label) {
                public BlockState setValue(Object property, Object value) {
                    return new BlockState(label + ":" + value);
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/world/level/block/entity/BlockEntity.java",
            """
            package net.minecraft.world.level.block.entity;

            public class BlockEntity {
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/world/level/block/entity/DispenserBlockEntity.java",
            """
            package net.minecraft.world.level.block.entity;

            public final class DispenserBlockEntity extends BlockEntity {
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/core/dispenser/BlockSource.java",
            """
            package net.minecraft.core.dispenser;

            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.block.entity.DispenserBlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.Vec3;

            public record BlockSource(
                    ServerLevel level,
                    BlockPos pos,
                    BlockState state,
                    DispenserBlockEntity blockEntity) {
                public Vec3 center() {
                    return this.pos.getCenter();
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/world/item/ItemStack.java",
            """
            package net.minecraft.world.item;

            public record ItemStack(String id) {
                public ItemStack copy() {
                    return new ItemStack(id + ":copy");
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/core/dispenser/DispenseItemBehavior.java",
            """
            package net.minecraft.core.dispenser;

            import net.minecraft.world.item.ItemStack;

            public interface DispenseItemBehavior {
                ItemStack dispense(BlockSource source, ItemStack stack);
            }
            """.trimIndent()
        )
    }

    private fun writeSourceWrapper() {
        write(
            "src/main/java/sample/Carrier.java",
            """
            package sample;

            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.block.state.BlockState;

            public final class Carrier {
                BlockState state;
                ServerLevel level;
                boolean throwOnLevel;

                Carrier(BlockState state, ServerLevel level) {
                    this.state = state;
                    this.level = level;
                }

                ServerLevel level() {
                    if (throwOnLevel)
                        throw new NullPointerException("level capture");
                    return level;
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/sample/MobileDispenseOrigin.java",
            """
            package sample;

            import javax.annotation.Nullable;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.core.dispenser.BlockSource;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public final class MobileDispenseOrigin implements BlockSource {
                private static final Object FACING = new Object();
                private final BlockPos anchor;
                private final Carrier carrier;
                private final Direction direction;

                public MobileDispenseOrigin(
                        Carrier carrier,
                        BlockPos anchor,
                        @Nullable Direction direction) {
                    this.anchor = anchor;
                    this.carrier = carrier;
                    this.direction = direction;
                }

                @Override
                public double x() {
                    return (double)this.anchor.getX() + 0.5D;
                }

                @Override
                public double y() {
                    return (double)this.anchor.getY() + 0.5D;
                }

                @Override
                public double z() {
                    return (double)this.anchor.getZ() + 0.5D;
                }

                @Override
                public BlockPos getPos() {
                    return this.anchor;
                }

                @Override
                public BlockState getBlockState() {
                    if (this.direction != null)
                        return this.carrier.state.setValue(FACING, this.direction);
                    return this.carrier.state;
                }

                @Override
                @Nullable
                public <T extends BlockEntity> T getEntity() {
                    return null;
                }

                @Override
                @Nullable
                public ServerLevel getLevel() {
                    return this.carrier.level();
                }
            }
            """.trimIndent()
        )
    }

    private fun writeBoundary() {
        write(
            "src/main/java/sample/Boundary.java",
            """
            package sample;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.core.dispenser.BlockSource;
            import net.minecraft.core.dispenser.DispenseItemBehavior;
            import net.minecraft.world.item.ItemStack;

            public final class Boundary {
                private final DispenseItemBehavior behavior;
                private boolean hasErrored;

                Boundary(DispenseItemBehavior behavior) {
                    this.behavior = behavior;
                }

                ItemStack execute(
                        ItemStack stack,
                        Carrier carrier,
                        BlockPos pos,
                        Direction facing) {
                    if (this.hasErrored)
                        return stack;

                    BlockSource source =
                        new MobileDispenseOrigin(carrier, pos, facing);

                    try {
                        return this.behavior.dispense(source, stack.copy());
                    } catch (NullPointerException expected) {
                        this.hasErrored = true;
                        return stack;
                    }
                }

                boolean hasErrored() {
                    return this.hasErrored;
                }
            }
            """.trimIndent()
        )
    }

    private fun writeRuntimeProbe() {
        write(
            "src/main/java/sample/RuntimeProbe.java",
            """
            package sample;

            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.core.dispenser.BlockSource;
            import net.minecraft.core.dispenser.DispenseItemBehavior;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.block.state.BlockState;

            public final class RuntimeProbe {
                public static String verify() {
                    BlockPos pos = new BlockPos(2, 4, 6);
                    ServerLevel firstLevel = new ServerLevel("first");
                    Carrier carrier =
                        new Carrier(new BlockState("base"), firstLevel);

                    List<BlockSource> seen = new ArrayList<>();
                    DispenseItemBehavior behavior = (nativeSource, stack) -> {
                        seen.add(nativeSource);
                        return stack;
                    };
                    Boundary boundary = new Boundary(behavior);

                    ItemStack original = new ItemStack("first");
                    ItemStack firstResult =
                        boundary.execute(original, carrier, pos, Direction.EAST);
                    if (!firstResult.id().equals("first:copy"))
                        throw new AssertionError(firstResult);
                    BlockSource first = seen.get(0);
                    if (first.level() != firstLevel)
                        throw new AssertionError("first level");
                    if (first.pos() != pos)
                        throw new AssertionError("first pos");
                    if (!first.state().label().equals("base:EAST"))
                        throw new AssertionError(first.state());
                    if (first.blockEntity() != null)
                        throw new AssertionError("first entity");
                    if (first.center().x() != 2.5D ||
                            first.center().y() != 4.5D ||
                            first.center().z() != 6.5D)
                        throw new AssertionError(first.center());

                    ServerLevel secondLevel = new ServerLevel("second");
                    carrier.level = secondLevel;
                    carrier.state = new BlockState("next");
                    boundary.execute(
                        new ItemStack("second"),
                        carrier,
                        pos,
                        Direction.NORTH);
                    BlockSource second = seen.get(1);
                    if (second == first)
                        throw new AssertionError("cached native source");
                    if (second.level() != secondLevel)
                        throw new AssertionError("second level");
                    if (!second.state().label().equals("next:NORTH"))
                        throw new AssertionError(second.state());

                    carrier.level = null;
                    boundary.execute(
                        new ItemStack("null"),
                        carrier,
                        pos,
                        Direction.NORTH);
                    if (seen.get(2).level() != null)
                        throw new AssertionError("nullable level");

                    carrier.throwOnLevel = true;
                    ItemStack failed = new ItemStack("failed");
                    if (boundary.execute(
                            failed,
                            carrier,
                            pos,
                            Direction.NORTH) != failed)
                        throw new AssertionError("fallback stack");
                    if (!boundary.hasErrored())
                        throw new AssertionError("conversion failure escaped try");
                    if (seen.size() != 3)
                        throw new AssertionError("behavior invoked after capture failure");

                    return "verified";
                }
            }
            """.trimIndent()
        )
    }

    private fun compileJava(output: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for generated adapter execution test")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use {
                fileManager ->
            val sources = Files.walk(project.resolve("src/main/java")).use { stream ->
                stream.filter { it.extension == "java" }.toList()
            }
            val units = fileManager.getJavaFileObjectsFromPaths(sources)
            val success = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf("-d", output.toString()),
                null,
                units
            ).call()
            assertTrue(
                success,
                diagnostics.diagnostics.joinToString("\n") { it.toString() }
            )
        }
    }

    private fun write(relative: String, source: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(source)
    }

    private fun read(relative: String): String =
        project.resolve(relative).readText()
}
