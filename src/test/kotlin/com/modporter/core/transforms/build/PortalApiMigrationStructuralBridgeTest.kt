package com.modporter.core.transforms.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PortalApiMigrationStructuralBridgeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `structural portal probe bridge still closes onto the direct portal api`() {
        verifyBridge(multiFile = false, retainNativeProbe = false)
    }

    @Test
    fun `wildcard portal probe closes through the cross file provider and teleporter chain`() {
        verifyBridge(multiFile = true, retainNativeProbe = false)
    }

    @Test
    fun `actual SuperGlue probe survives wildcard portal closure as the exact native helper`() {
        verifyBridge(multiFile = true, retainNativeProbe = true)
    }

    @Test
    fun `targets empty path atomically closes the owned full helper and is idempotent`() {
        val superGlue = writeSuperGlueProbe()
        val probe = writeOwnedFullProbe()
        val originalSuperGlue = superGlue.readText()
        val original = snapshot()

        val preview = PortalApiMigration().migrate(directory, dryRun = true)
        assertEquals(1, preview.count { it.ruleId == "build-portal-native-probe-closure" })
        assertEquals(original, snapshot())

        val changes = PortalApiMigration().migrate(directory, dryRun = false)
        assertEquals(1, changes.count { it.ruleId == "build-portal-native-probe-closure" })
        assertEquals(originalSuperGlue, superGlue.readText())
        assertNativeProbe(probe.readText())
        val migrated = snapshot()
        assertTrue(PortalApiMigration().migrate(directory, dryRun = false).isEmpty())
        assertEquals(migrated, snapshot())
    }

    @Test
    fun `native helper atomic replacement rolls back after an injected publish failure`() {
        writeSuperGlueProbe()
        writeOwnedFullProbe()
        val original = snapshot()

        assertFailsWith<IllegalStateException> {
            PortalApiMigration(PortalApiMigrationFault.AFTER_FIRST_PUBLISH)
                .migrate(directory, dryRun = false)
        }

        assertEquals(original, snapshot())
    }

    @Test
    fun `targets empty path refuses helper drift and unsupported extra consumers`() {
        val superGlue = writeSuperGlueProbe()
        val probe = writeOwnedFullProbe()
        probe.writeText(probe.readText() + "// drift\n")
        val drifted = snapshot()

        assertTrue(PortalApiMigration().migrate(directory, dryRun = false).isEmpty())
        assertEquals(drifted, snapshot())

        probe.writeText(ownedProbeTemplate("/compat/LegacyPortalProbe.java.template"))
        val extra = directory.resolve("src/main/java/com/example/portal/UnknownProbeConsumer.java")
        extra.parent.createDirectories()
        extra.writeText(
            """
            package com.example.portal;
            import com.modporter.generated.example.compat.LegacyPortalProbe;
            class UnknownProbeConsumer {
                Class<?> helperType() { return LegacyPortalProbe.class; }
            }
            """.trimIndent() + "\n"
        )
        val unsupported = snapshot()

        assertTrue(PortalApiMigration().migrate(directory, dryRun = false).isEmpty())
        assertEquals(unsupported, snapshot())
        assertTrue(superGlue.exists())
    }

    private fun verifyBridge(multiFile: Boolean, retainNativeProbe: Boolean) {
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
                    return fromTeleporter(level, face, firstDimension, secondDimension,
                        BetterEndPortalCompat::find);
                }
            }

            class Face { BlockPos getConnectedPos() { return null; } }
            class Probe {
                void setPortalEntrancePos() {}
                Object findDimensionEntryPoint(ServerLevel level) { return null; }
                BlockPos blockPosition() { return null; }
            }
            class Exit { Exit(ServerLevel level, BlockPos pos) {} }
            """.trimIndent() + "\n"
        )
        val provider = if (multiFile) {
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
                            return AllPortalTracks.fromProbe(
                                level, face, firstDimension, secondDimension, provider);
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
                    }
                    """.trimIndent() + "\n"
                )
            }
        } else {
            null
        }
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
        val superGlue = if (retainNativeProbe) writeSuperGlueProbe() else null
        val originalSuperGlue = superGlue?.readText()
        val probe = if (retainNativeProbe) writeOwnedFullProbe() else writeDriftProbe()

        val original = snapshot()
        val preview = PortalApiMigration().migrate(directory, dryRun = true)
        assertTrue(preview.any { it.ruleId == "build-portal-dimension-transition-pipeline" })
        assertEquals(original, snapshot())

        val changes = PortalApiMigration().migrate(directory, dryRun = false)
        val migrated = tracks.readText()

        assertTrue(changes.any { it.ruleId == "build-portal-dimension-transition-pipeline" })
        assertEquals(
            if (retainNativeProbe) 1 else 2,
            changes.count { it.ruleId == "build-portal-unreferenced-reflection-adapter" }
        )
        assertTrue(migrated.contains("instanceof Portal modporterPortal"), migrated)
        assertTrue(
            migrated.contains("modporterPortal.getPortalDestination(level, probe, probe.blockPosition())"),
            migrated
        )
        assertTrue(migrated.contains("BlockPos.containing(info.pos())"), migrated)
        assertFalse(migrated.contains("BiFunction"), migrated)
        assertFalse(migrated.contains("Function"), migrated)
        assertFalse(migrated.contains("LegacyPortalProbe"), migrated)
        assertFalse(migrated.contains("Class.forName"), migrated)
        assertFalse(migrated.contains("getDeclaredConstructor"), migrated)
        assertFalse(betterEnd.exists())
        if (retainNativeProbe) {
            assertTrue(probe.exists())
            assertEquals(1, changes.count { it.ruleId == "build-portal-native-probe-closure" })
            assertNativeProbe(probe.readText())
            assertEquals(originalSuperGlue, superGlue!!.readText())
        } else {
            assertFalse(probe.exists())
        }
        provider?.let { bridge ->
            val source = bridge.readText()
            assertFalse(source.contains("BiFunction"), source)
            assertFalse(source.contains("Function"), source)
            assertFalse(source.contains("LegacyPortalProbe"), source)
            assertTrue(
                source.contains(
                    "AllPortalTracks.fromProbe(level, face, firstDimension, secondDimension)"
                ),
                source
            )
        }
        val migratedSnapshot = snapshot()
        assertTrue(PortalApiMigration().migrate(directory, dryRun = false).isEmpty())
        assertEquals(migratedSnapshot, snapshot())
    }

    private fun writeSuperGlueProbe(): Path {
        val file = directory.resolve(
            "src/main/java/com/simibubi/create/content/contraptions/glue/SuperGlueEntity.java"
        )
        file.parent.createDirectories()
        file.writeText(
            """
            package com.simibubi.create.content.contraptions.glue;

            import com.modporter.generated.example.compat.LegacyPortalProbe;
            import com.modporter.generated.example.compat.PortalInfo;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;

            public class SuperGlueEntity {
                protected BlockPos portalEntrancePos;

                public PortalInfo findDimensionEntryPoint(ServerLevel pDestination) {
                    return LegacyPortalProbe.findDimensionEntryPoint(
                        this,
                        portalEntrancePos,
                        pDestination
                    );
                }
            }
            """.trimIndent() + "\n"
        )
        return file
    }

    private fun writeOwnedFullProbe(): Path {
        val generatedDir = directory.resolve("src/main/java/com/modporter/generated/example/compat")
        generatedDir.createDirectories()
        return generatedDir.resolve("LegacyPortalProbe.java").also {
            it.writeText(ownedProbeTemplate("/compat/LegacyPortalProbe.java.template"))
        }
    }

    private fun writeDriftProbe(): Path {
        val generatedDir = directory.resolve("src/main/java/com/modporter/generated/example/compat")
        generatedDir.createDirectories()
        return generatedDir.resolve("LegacyPortalProbe.java").also { file ->
            file.writeText(
                """
                package com.modporter.generated.example.compat;

                import java.lang.invoke.MethodHandle;
                import java.lang.invoke.MethodHandles;

                public final class LegacyPortalProbe {
                    private static final MethodHandle HANDLE =
                        MethodHandles.constant(Object.class, null);
                }
                """.trimIndent() + "\n"
            )
        }
    }

    private fun ownedProbeTemplate(resource: String): String =
        PortalApiMigrationStructuralBridgeTest::class.java.getResource(resource)!!
            .readText()
            .replace("@PACKAGE@", "com.modporter.generated.example.compat")

    private fun assertNativeProbe(source: String) {
        assertEquals(ownedProbeTemplate("/compat/LegacyPortalProbe.native.java.template"), source)
        assertTrue(source.contains("findClosestPortalPosition(exitPos, isNether, worldBorder)"), source)
        assertTrue(source.contains("TicketType.PORTAL"), source)
        assertTrue(source.contains("PortalForcer.TICKET_RADIUS"), source)
        assertTrue(source.contains("PortalShape.findCollisionFreePosition("), source)
        assertFalse(source.contains("MethodHandle"), source)
        assertFalse(source.contains("java.lang.reflect"), source)
        assertFalse(source.contains(".createPortal("), source)
        assertFalse(source.contains("DO_NOTHING"), source)
        assertFalse(source.contains("PLACE_PORTAL_TICKET"), source)
    }

    private fun snapshot(): Map<String, List<Byte>> = Files.walk(directory).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            directory.relativize(path).toString() to Files.readAllBytes(path).toList()
        }
    }
}
