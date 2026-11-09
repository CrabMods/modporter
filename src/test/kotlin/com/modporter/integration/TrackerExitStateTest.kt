package com.modporter.integration

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** The tracker exit is measured from the entity's native tracking range, inside chunks the player holds. */
class TrackerExitStateTest {
    @Test
    fun `tracker exit leaves the native tracking boundary by one chunk and waits for the destination to stream in`(@TempDir root: Path) {
        val unit = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
            .parse(LegacyClientSpawnRuntimeHarnessSource.source("example.benchmark", "create"))
            .result.orElseThrow()
        val margin = unit.findAll(FieldDeclaration::class.java)
            .single { it.variables.any { v -> v.nameAsString == "TRACKER_EXIT_MARGIN_BLOCKS" } }
        val method = unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == "moveTrackerOutLocked" }
        val file = root.resolve("TrackerExitProbe.java")
        file.writeText("""
            import java.util.*;
            public class TrackerExitProbe {
                $margin
                enum Stage { TRACKER_MOVE_OUT, WAIT_TRACKER_EXIT }
                static Stage stage = Stage.TRACKER_MOVE_OUT;
                static String failure;
                static long serverTicks = 7;
                static void failLocked(String value) { failure = value; }
                static void setStageLocked(Stage value) { stage = value; }
                static class Vec3 {
                    static final Vec3 ZERO = new Vec3(0, 0, 0);
                    final double x, y, z;
                    Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
                }
                static class BlockPos {
                    final double x;
                    BlockPos(double x) { this.x = x; }
                    static BlockPos containing(double x, double y, double z) { return new BlockPos(x); }
                }
                static class Level {
                    double lastResidentX;
                    boolean hasChunkAt(BlockPos position) { return position.x <= lastResidentX; }
                }
                static class EntityType {
                    int rangeChunks;
                    int clientTrackingRange() { return rangeChunks; }
                }
                static class PackageEntity {
                    final EntityType type = new EntityType();
                    double x, z;
                    Level level;
                    EntityType getType() { return type; }
                    double getX() { return x; }
                    double getZ() { return z; }
                    boolean isRemoved() { return false; }
                    Level level() { return level; }
                }
                static class PlayerList {
                    int viewChunks;
                    int getViewDistance() { return viewChunks; }
                }
                static class MinecraftServer {
                    final PlayerList list = new PlayerList();
                    double scaling = 1.0;
                    PlayerList getPlayerList() { return list; }
                    int getScaledTrackingDistance(int range) { return (int) (scaling * range); }
                }
                static class ServerPlayer {
                    final MinecraftServer server = new MinecraftServer();
                    final Level level = new Level();
                    Vec3 position = new Vec3(0, 70, 0);
                    int teleports;
                    Level serverLevel() { return level; }
                    void setInvulnerable(boolean value) { }
                    void setNoGravity(boolean value) { }
                    void setDeltaMovement(Vec3 value) { }
                    boolean teleportTo(Level level, double x, double y, double z, Set<?> relative, float yaw, float pitch) {
                        teleports++; position = new Vec3(x, y, z); return true;
                    }
                }
                static class PlayerSnapshot {
                    final Vec3 position;
                    final float yRot = 0, xRot = 0;
                    PlayerSnapshot(ServerPlayer player) { position = player.position; }
                }
                static class Lifecycle { boolean complete = true; }
                static class Scenario {
                    int expectedLifecycleCount = 2;
                    List<Lifecycle> lifecycles = new ArrayList<>(List.of(new Lifecycle()));
                    PlayerSnapshot trackerPlayerSnapshot;
                    PackageEntity serverEntity;
                    int trackerTypeRangeBlocks, trackerScaledRangeBlocks, trackerViewDistanceBlocks;
                    double trackerEffectiveRangeBlocks, trackerExitDistanceBlocks;
                    boolean trackerDestinationResident, trackerMovedOut;
                    long trackerMoveOutTick, trackerExitWaitStartTick = -1L;
                }
                static Scenario activeScenario;
                $method
                static void check(boolean condition, String message) {
                    if (!condition) throw new AssertionError(message + " failure=" + failure);
                }
                static ServerPlayer arrange(int rangeChunks, int viewChunks, double lastResidentX) {
                    ServerPlayer player = new ServerPlayer();
                    player.server.list.viewChunks = viewChunks;
                    player.level.lastResidentX = lastResidentX;
                    PackageEntity entity = new PackageEntity();
                    entity.type.rangeChunks = rangeChunks;
                    entity.x = 3;
                    entity.level = player.level;
                    activeScenario = new Scenario();
                    activeScenario.serverEntity = entity;
                    stage = Stage.TRACKER_MOVE_OUT;
                    failure = null;
                    return player;
                }
                public static void verify() {
                    // Create's package: 10 chunk range under a 12 chunk view; one chunk past 160 blocks
                    ServerPlayer player = arrange(10, 12, 191);
                    moveTrackerOutLocked(player);
                    check(failure == null && stage == Stage.WAIT_TRACKER_EXIT, "exit must proceed");
                    check(player.position.x == 179 && player.teleports == 1, "exit must land one chunk past 160 blocks");
                    check(activeScenario.trackerEffectiveRangeBlocks == 160
                        && activeScenario.trackerExitDistanceBlocks == 176
                        && activeScenario.trackerDestinationResident, "exit evidence must be recorded");
                    // a view distance below the type range bounds tracking first
                    player = arrange(10, 8, 191);
                    moveTrackerOutLocked(player);
                    check(failure == null && player.position.x == 147, "view distance must cap the effective range");
                    // a destination still streaming in is waited for, never loaded by the exit
                    player = arrange(10, 12, 150);
                    moveTrackerOutLocked(player);
                    PlayerSnapshot baseline = activeScenario.trackerPlayerSnapshot;
                    check(failure == null && player.teleports == 0 && stage == Stage.TRACKER_MOVE_OUT
                        && activeScenario.trackerExitWaitStartTick == 7, "an unheld destination must wait without teleporting");
                    serverTicks = 9;
                    player.level.lastResidentX = 191;
                    moveTrackerOutLocked(player);
                    check(failure == null && player.position.x == 179 && player.teleports == 1
                        && activeScenario.trackerPlayerSnapshot == baseline && stage == Stage.WAIT_TRACKER_EXIT,
                        "the exit proceeds once the destination is resident, from the first baseline");
                }
            }
        """.trimIndent())
        val output = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        assertEquals(0, checkNotNull(ToolProvider.getSystemJavaCompiler()).run(null, errors, errors,
            "-d", output.toString(), file.toString()), errors.toString())
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
            loader.loadClass("TrackerExitProbe").getMethod("verify").invoke(null)
        }
    }
}
