package com.modporter.core.transforms.build

import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PortalApiMigrationStructuralBridgeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `structural portal probe bridge still closes onto the direct portal api`() {
        verifyBridge(multiFile = false)
    }

    @Test
    fun `wildcard portal probe closes through the cross file provider and teleporter chain`() {
        verifyBridge(multiFile = true)
    }

    private fun verifyBridge(multiFile: Boolean) {
        val sourceDir = directory.resolve("src/main/java/com/example/portal")
        sourceDir.createDirectories()
        val tracks = sourceDir.resolve("AllPortalTracks.java")
        tracks.writeText(
            """
            package com.example.portal;

            import java.util.function.BiFunction;
            import java.util.function.Function;
            import net.minecraft.core.BlockPos;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import com.modporter.generated.example.compat.LegacyPortalProbe;
            import com.modporter.generated.example.compat.PortalInfo;

            public class AllPortalTracks {
                static Exit fromProbe(ServerLevel level, Face face,
                                      ResourceKey<Level> firstDimension,
                                      ResourceKey<Level> secondDimension,
                                      BiFunction<ServerLevel, Probe, ?> provider) {
                    MinecraftServer server = level.getServer();
                    ServerLevel otherLevel = server.getLevel(secondDimension);
                    BlockPos portalPos = face.getConnectedPos();
                    BlockState portalState = level.getBlockState(portalPos);
                    Probe probe = new Probe();
                    probe.setPortalEntrancePos();
                    PortalInfo info = LegacyPortalProbe.portalInfo(provider.apply(otherLevel, probe));
                    if (info == null)
                        return null;
                    BlockPos output = BlockPos.containing(info.pos);
                    return new Exit(otherLevel, output);
                }

                static Exit fromTeleporter(ServerLevel level, Face face,
                                           ResourceKey<Level> firstDimension,
                                           ResourceKey<Level> secondDimension,
                                           Function<ServerLevel, ?> factory) {
                    return fromProbe(level, face, firstDimension, secondDimension,
                        (otherLevel, probe) -> {
                            LegacyPortalProbe.Teleporter teleporter =
                                LegacyPortalProbe.adapt(factory.apply(otherLevel));
                            return teleporter.getPortalInfo(
                                probe, otherLevel, probe::findDimensionEntryPoint);
                        });
                }

                static Exit aether(ServerLevel level, Face face,
                                   ResourceKey<Level> firstDimension,
                                   ResourceKey<Level> secondDimension) {
                    return fromTeleporter(level, face, firstDimension, secondDimension,
                        target -> LegacyPortalProbe.reflective(
                            Class.forName("dev.example.portal.CustomPortalForcer")
                                .getDeclaredConstructor(ServerLevel.class)
                                .newInstance(target)));
                }

                static Exit betterEnd(ServerLevel level, Face face,
                                      ResourceKey<Level> firstDimension,
                                      ResourceKey<Level> secondDimension) {
                    return fromProbe(level, face, firstDimension, secondDimension,
                        BetterEndPortalCompat::find);
                }
            }
            """.trimIndent() + "\n"
        )
        val provider = if (multiFile) {
            val original = tracks.readText()
            val teleporterStart = original.indexOf("    static Exit fromTeleporter(")
            val teleporterEnd = original.indexOf("    static Exit aether(", teleporterStart)
            check(teleporterStart >= 0 && teleporterEnd > teleporterStart)
            val teleporter = original.substring(teleporterStart, teleporterEnd)
            tracks.writeText(
                original.removeRange(teleporterStart, teleporterEnd)
                    .replace("return fromTeleporter(", "return PortalTrackProvider.fromTeleporter(")
                    .replace("return fromProbe(", "return PortalTrackProvider.fromProbe(")
            )
            sourceDir.resolve("PortalTrackProvider.java").also { file ->
                file.writeText(
                    """
                    package com.example.portal;

                    import java.util.function.BiFunction;
                    import java.util.function.Function;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.server.level.ServerLevel;
                    import net.minecraft.world.level.Level;
                    import com.modporter.generated.example.compat.LegacyPortalProbe;

                    public class PortalTrackProvider {
                        static Exit fromProbe(ServerLevel level, Face face,
                                              ResourceKey<Level> firstDimension,
                                              ResourceKey<Level> secondDimension,
                                              BiFunction<ServerLevel, Probe, ?> provider) {
                            return AllPortalTracks.fromProbe(level, face, firstDimension, secondDimension, provider);
                        }

                    """.trimIndent() + "\n" + teleporter + "}\n"
                )
            }
        } else null
        val betterEnd = sourceDir.resolve("BetterEndPortalCompat.java")
        betterEnd.writeText(
            """
            package com.example.portal;

            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;

            public class BetterEndPortalCompat {
                private static final MethodHandle HANDLE = MethodHandles.constant(Object.class, null);
                public static Object find(Object level, Object probe) { return null; }
            }
            """.trimIndent() + "\n"
        )
        val generatedDir = directory.resolve("src/main/java/com/modporter/generated/example/compat")
        generatedDir.createDirectories()
        val probe = generatedDir.resolve("LegacyPortalProbe.java")
        probe.writeText(
            """
            package com.modporter.generated.example.compat;

            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;

            public final class LegacyPortalProbe {
                private static final MethodHandle HANDLE = MethodHandles.constant(Object.class, null);
            }
            """.trimIndent() + "\n"
        )

        val original = snapshot()
        val preview = PortalApiMigration().migrate(directory, dryRun = true)
        assertTrue(preview.any { it.ruleId == "build-portal-dimension-transition-pipeline" })
        assertEquals(original, snapshot())
        val changes = PortalApiMigration().migrate(directory, dryRun = false)
        val migrated = tracks.readText()

        assertTrue(changes.any { it.ruleId == "build-portal-dimension-transition-pipeline" })
        assertTrue(changes.count { it.ruleId == "build-portal-unreferenced-reflection-adapter" } == 2)
        assertTrue(migrated.contains("instanceof Portal modporterPortal"), migrated)
        assertTrue(migrated.contains("modporterPortal.getPortalDestination(level, probe, probe.blockPosition())"), migrated)
        assertTrue(migrated.contains("BlockPos.containing(info.pos())"), migrated)
        assertFalse(migrated.contains("BiFunction"), migrated)
        assertFalse(migrated.contains("Function"), migrated)
        assertFalse(migrated.contains("LegacyPortalProbe"), migrated)
        assertFalse(migrated.contains("Class.forName"), migrated)
        assertFalse(migrated.contains("getDeclaredConstructor"), migrated)
        assertFalse(betterEnd.exists())
        assertFalse(probe.exists())
        if (provider != null) {
            val bridge = provider.readText()
            assertFalse(bridge.contains("BiFunction"), bridge)
            assertFalse(bridge.contains("Function"), bridge)
            assertFalse(bridge.contains("LegacyPortalProbe"), bridge)
            assertTrue(bridge.contains("AllPortalTracks.fromProbe(level, face, firstDimension, secondDimension)"), bridge)
        }
        val migratedSnapshot = snapshot()
        assertTrue(PortalApiMigration().migrate(directory, dryRun = false).isEmpty())
        assertEquals(migratedSnapshot, snapshot())
    }

    private fun snapshot(): Map<String, List<Byte>> = Files.walk(directory).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            directory.relativize(path).toString() to Files.readAllBytes(path).toList()
        }
    }
}
