package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyPortalProbeTransitions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.streams.toList

class LegacyPortalProbeTransitionsTest {
    @TempDir lateinit var directory: Path

    private fun java(relative: String, source: String): Path {
        val file = directory.resolve("src/main/java/$relative")
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }

    private fun modEntry() {
        java("example/ExampleMod.java", """
            package example;
            import net.neoforged.fml.common.Mod;
            @Mod("portal_probe") public class ExampleMod {}
        """)
    }

    @Test
    fun `closed probe and teleporter Function family retain dispatch field state and fallback`() {
        modEntry()
        val probe = java("example/ProbeEntity.java", """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;

            public class ProbeEntity extends Entity {
                public void rememberEntrance() {
                    portalEntrancePos = blockPosition();
                }

                @Override
                public PortalInfo findDimensionEntryPoint(ServerLevel destination) {
                    return super.findDimensionEntryPoint(destination);
                }
            }
        """)
        val provider = java("example/ProbeProviders.java", """
            package example;

            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraftforge.common.util.ITeleporter;

            class ProbeProviders {
                static final Logger LOGGER = new Logger();

                PortalInfo query(
                    ServerLevel otherLevel,
                    ProbeEntity probe,
                    Function<ServerLevel, ITeleporter> customPortalForcer
                ) {
                    ITeleporter teleporter = customPortalForcer.apply(otherLevel);
                    return teleporter.getPortalInfo(
                        probe,
                        otherLevel,
                        probe::findDimensionEntryPoint
                    );
                }

                Function<ServerLevel, ITeleporter> vanilla() {
                    return ServerLevel::getPortalForcer;
                }

                Function<ServerLevel, ITeleporter> external() {
                    return serverLevel -> {
                        try {
                            return (ITeleporter) Class.forName("external.portal.LegacyForcer")
                                .getDeclaredConstructor(ServerLevel.class, boolean.class)
                                .newInstance(serverLevel, true);
                        } catch (Exception exception) {
                            LOGGER.error("External portal adapter failed", exception);
                        }
                        return serverLevel.getPortalForcer();
                    };
                }
            }

            class Logger {
                void error(String message, Throwable throwable) {}
            }
        """)
        val forwardingApi = java("example/PortalApi.java", """
            package example;

            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraftforge.common.util.ITeleporter;

            class PortalApi {
                static PortalInfo query(
                    ServerLevel otherLevel,
                    ProbeEntity probe,
                    Function<ServerLevel, ITeleporter> customPortalForcer
                ) {
                    return new ProbeProviders().query(otherLevel, probe, customPortalForcer);
                }
            }
        """)
        val pass = StructuralRefactorPass()
        val dryRun = pass.migrateLegacyPortalProbeTransitions(directory, dryRun = true)
        assertEquals(4, dryRun.size, dryRun.toString())
        assertFalse(
            directory.resolve("src/main/java/com/modporter/generated/portal_probe/compat/LegacyPortalProbe.java").exists()
        )

        val changes = pass.migrateLegacyPortalProbeTransitions(directory, dryRun = false)
        assertEquals(4, changes.size, changes.toString())
        val probeSource = probe.readText()
        assertTrue(probeSource.contains("protected BlockPos portalEntrancePos;"), probeSource)
        assertTrue(probeSource.contains("portalEntrancePos = blockPosition();"), probeSource)
        assertTrue(probeSource.contains("public PortalInfo findDimensionEntryPoint(ServerLevel destination)"), probeSource)
        assertTrue(
            probeSource.contains(
                "return LegacyPortalProbe.findDimensionEntryPoint(this, portalEntrancePos, destination);"
            ),
            probeSource
        )
        assertFalse(probeSource.contains("super.findDimensionEntryPoint"), probeSource)
        assertFalse(probeSource.contains("@Override\n    public PortalInfo findDimensionEntryPoint"), probeSource)

        val providerSource = provider.readText()
        assertTrue(
            providerSource.contains("Function<ServerLevel, ?> customPortalForcer"),
            providerSource
        )
        assertTrue(
            providerSource.contains(
                "LegacyPortalProbe.Teleporter teleporter = " +
                    "LegacyPortalProbe.adapt(customPortalForcer.apply(otherLevel));"
            ),
            providerSource
        )
        assertTrue(
            providerSource.contains("return teleporter.getPortalInfo(\n            probe,\n            otherLevel,\n            probe::findDimensionEntryPoint"),
            providerSource
        )
        assertTrue(providerSource.contains("return LegacyPortalProbe::vanilla;"), providerSource)
        assertTrue(
            providerSource.contains(
                "return LegacyPortalProbe.reflective(Class.forName(\"external.portal.LegacyForcer\")"
            ),
            providerSource
        )
        assertTrue(
            providerSource.contains(".getDeclaredConstructor(ServerLevel.class, boolean.class)"),
            providerSource
        )
        assertTrue(providerSource.contains("LOGGER.error(\"External portal adapter failed\", exception);"), providerSource)
        assertTrue(providerSource.contains("return LegacyPortalProbe.vanilla(serverLevel);"), providerSource)
        assertFalse(providerSource.contains("import net.minecraftforge.common.util.ITeleporter;"), providerSource)
        val forwardingSource = forwardingApi.readText()
        assertTrue(
            forwardingSource.contains(
                "Function<ServerLevel, ?> customPortalForcer"
            ),
            forwardingSource
        )
        assertFalse(
            forwardingSource.contains("import net.minecraftforge.common.util.ITeleporter;"),
            forwardingSource
        )

        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/portal_probe/compat/LegacyPortalProbe.java"
        ).readText()
        assertTrue(helper.contains("findClosestPortalPosition(exitPos, isNether, worldBorder)"), helper)
        assertTrue(helper.contains("addRegionTicket("), helper)
        assertTrue(helper.contains("PortalForcer.TICKET_RADIUS"), helper)
        assertTrue(helper.contains("PortalShape.findCollisionFreePosition("), helper)
        assertTrue(helper.contains("defaultPortalInfo.apply(destination)"), helper)
        assertTrue(helper.contains("PortalForcer portalForcer = level.getPortalForcer();"), helper)
        assertTrue(helper.contains("public static Teleporter adapt(Object target)"), helper)
        assertTrue(helper.contains("target.getClass() == PortalForcer.class"), helper)
        assertTrue(helper.contains("adapter.writer.write(defaultPortalInfo.apply(level))"), helper)
        assertTrue(helper.contains("Function<ServerLevel, returnType>"), helper)
        assertTrue(helper.contains("Cannot supply the old default Function"), helper)
        assertTrue(helper.contains("DimensionTransition.class.isAssignableFrom(returnType)"), helper)
        assertTrue(helper.contains("DimensionTransition cannot be reduced to PortalInfo"), helper)
        assertTrue(helper.contains("public static PortalInfo portalInfo("), helper)
        assertTrue(helper.contains("Set.of(\"pos\", \"speed\", \"yRot\", \"xRot\")"), helper)
        assertTrue(helper.contains("public static MethodHandle findVirtual("), helper)
        assertTrue(helper.contains("MethodHandles.filterReturnValue(target, filter)"), helper)
        assertFalse(helper.contains(".createPortal("), helper)
        assertFalse(helper.contains("DO_NOTHING"), helper)
        assertFalse(helper.contains("PLACE_PORTAL_TICKET"), helper)
        assertTrue(pass.migrateLegacyPortalProbeTransitions(directory, dryRun = false).isEmpty())
    }

    @Test
    fun `custom teleporter implementations do not suppress an independent exact probe`() {
        modEntry()
        val probe = java("example/RejectedProbe.java", """
            package example;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            class RejectedProbe extends Entity {
                void remember() { portalEntrancePos = blockPosition(); }
                @Override
                public PortalInfo findDimensionEntryPoint(ServerLevel destination) {
                    return super.findDimensionEntryPoint(destination);
                }
            }
        """)
        val file = java("example/CustomTeleporter.java", """
            package example;
            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraftforge.common.util.ITeleporter;

            class CustomTeleporter implements ITeleporter {
                @Override
                public PortalInfo getPortalInfo(
                    Entity entity,
                    ServerLevel destination,
                    Function<ServerLevel, PortalInfo> defaultPortalInfo
                ) {
                    return new PortalInfo(
                        entity.position(),
                        entity.getDeltaMovement(),
                        entity.getYRot(),
                        entity.getXRot()
                    );
                }
            }
        """)
        val original = file.readText()
        val originalProbe = probe.readText()

        val changes = StructuralRefactorPass().migrateLegacyPortalProbeTransitions(directory, dryRun = false)

        assertEquals(2, changes.size, changes.toString())
        assertEquals(original, file.readText())
        val migratedProbe = probe.readText()
        assertFalse(migratedProbe == originalProbe, migratedProbe)
        assertTrue(migratedProbe.contains("protected BlockPos portalEntrancePos;"), migratedProbe)
        assertTrue(
            migratedProbe.contains(
                "return LegacyPortalProbe.findDimensionEntryPoint(this, portalEntrancePos, destination);"
            ),
            migratedProbe
        )
        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/portal_probe/compat/LegacyPortalProbe.java"
        )
        assertTrue(helper.exists())
        val helperSource = helper.readText()
        assertTrue(helperSource.contains("findClosestPortalPosition(exitPos, isNether, worldBorder)"), helperSource)
        assertTrue(helperSource.contains("TicketType.PORTAL"), helperSource)
        assertTrue(helperSource.contains("PortalForcer.TICKET_RADIUS"), helperSource)
        assertTrue(helperSource.contains("PortalShape.findCollisionFreePosition("), helperSource)
        assertFalse(helperSource.contains("MethodHandle"), helperSource)
        assertFalse(helperSource.contains("java.lang.reflect"), helperSource)
        assertFalse(helperSource.contains(".createPortal("), helperSource)
        assertTrue(StructuralRefactorPass()
            .migrateLegacyPortalProbeTransitions(directory, dryRun = false)
            .isEmpty())
    }

    @Test
    fun `owned helper drift refuses the exact probe without partial writes`() {
        modEntry()
        val probe = java("example/DriftProbe.java", """
            package example;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            class DriftProbe extends Entity {
                void remember() { portalEntrancePos = blockPosition(); }
                @Override
                public PortalInfo findDimensionEntryPoint(ServerLevel destination) {
                    return super.findDimensionEntryPoint(destination);
                }
            }
        """)
        val helper = java(
            "com/modporter/generated/portal_probe/compat/LegacyPortalProbe.java",
            """
            package com.modporter.generated.portal_probe.compat;
            public final class LegacyPortalProbe { private LegacyPortalProbe() {} }
            """
        )
        val originalProbe = probe.readText()
        val originalHelper = helper.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyPortalProbeTransitions(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(originalProbe, probe.readText())
        assertEquals(originalHelper, helper.readText())
    }

    @Test
    fun `unsupported extra helper consumer refuses native closure without partial writes`() {
        modEntry()
        val probe = java("example/ExtraConsumerProbe.java", """
            package example;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            class ExtraConsumerProbe extends Entity {
                void remember() { portalEntrancePos = blockPosition(); }
                @Override
                public PortalInfo findDimensionEntryPoint(ServerLevel destination) {
                    return super.findDimensionEntryPoint(destination);
                }
            }
        """)
        java("example/UnknownPortalProbeUse.java", """
            package example;
            import com.modporter.generated.portal_probe.compat.LegacyPortalProbe;
            class UnknownPortalProbeUse {
                Class<?> helperType() { return LegacyPortalProbe.class; }
            }
        """)
        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/portal_probe/compat/LegacyPortalProbe.java"
        )
        helper.parent.createDirectories()
        helper.writeText(
            LegacyPortalProbeTransitionsTest::class.java
                .getResource("/compat/LegacyPortalProbe.java.template")!!
                .readText()
                .replace("@PACKAGE@", "com.modporter.generated.portal_probe.compat")
        )
        val original = snapshotBytes()

        val changes = StructuralRefactorPass()
            .migrateLegacyPortalProbeTransitions(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, snapshotBytes())
        assertTrue(probe.exists())
    }

    @Test
    fun `Function parameter with an unproved forwarding use rejects the complete family`() {
        modEntry()
        val file = java("example/UnsafeForward.java", """
            package example;
            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraftforge.common.util.ITeleporter;

            class UnsafeForward {
                PortalInfo closed(
                    ServerLevel destination,
                    Entity probe,
                    Function<ServerLevel, ITeleporter> provider
                ) {
                    ITeleporter teleporter = provider.apply(destination);
                    return teleporter.getPortalInfo(
                        probe,
                        destination,
                        probe::findDimensionEntryPoint
                    );
                }

                void open(Function<ServerLevel, ITeleporter> provider) {
                    consume(provider);
                }

                void consume(Object value) {}
            }
        """)
        val original = file.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyPortalProbeTransitions(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, file.readText())
    }

    @Test
    fun `probe fields with any additional source use remain untouched`() {
        modEntry()
        val file = java("example/OpenProbe.java", """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;

            class OpenProbe extends Entity {
                void remember() { portalEntrancePos = blockPosition(); }
                BlockPos entrance() { return portalEntrancePos; }
                @Override
                public PortalInfo findDimensionEntryPoint(ServerLevel destination) {
                    return super.findDimensionEntryPoint(destination);
                }
            }
        """)
        val original = file.readText()

        val changes = StructuralRefactorPass().migrateLegacyPortalProbeTransitions(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, file.readText())
    }

    @Test
    fun `reflection without the original catch log and PortalForcer fallback remains untouched`() {
        modEntry()
        val file = java("example/OpenReflection.java", """
            package example;
            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraftforge.common.util.ITeleporter;

            class OpenReflection {
                Function<ServerLevel, ITeleporter> provider() {
                    return level -> (ITeleporter) Class.forName("external.Unproven")
                        .getDeclaredConstructor(ServerLevel.class, boolean.class)
                        .newInstance(level, true);
                }
            }
        """)
        val original = file.readText()

        val changes = StructuralRefactorPass().migrateLegacyPortalProbeTransitions(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, file.readText())
    }

    @Test
    fun `probe and provider edits in one source are applied atomically`() {
        modEntry()
        val file = java("example/CombinedProbe.java", """
            package example;

            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraftforge.common.util.ITeleporter;

            public class CombinedProbe extends Entity {
                void rememberEntrance() {
                    portalEntrancePos = blockPosition();
                }

                @Override
                public PortalInfo findDimensionEntryPoint(ServerLevel destination) {
                    return super.findDimensionEntryPoint(destination);
                }

                PortalInfo query(
                    ServerLevel destination,
                    Function<ServerLevel, ITeleporter> provider
                ) {
                    CombinedProbe probe = this;
                    ITeleporter teleporter = provider.apply(destination);
                    return teleporter.getPortalInfo(
                        probe,
                        destination,
                        probe::findDimensionEntryPoint
                    );
                }
            }
        """)

        val pass = StructuralRefactorPass()
        val changes = pass.migrateLegacyPortalProbeTransitions(directory, dryRun = false)

        assertEquals(2, changes.size, changes.toString())
        val migrated = file.readText()
        assertTrue(migrated.contains("protected BlockPos portalEntrancePos;"), migrated)
        assertTrue(
            migrated.contains(
                "return LegacyPortalProbe.findDimensionEntryPoint(this, portalEntrancePos, destination);"
            ),
            migrated
        )
        assertTrue(
            migrated.contains("Function<ServerLevel, ?> provider"),
            migrated
        )
        assertTrue(
            migrated.contains(
                "LegacyPortalProbe.Teleporter teleporter = " +
                    "LegacyPortalProbe.adapt(provider.apply(destination));"
            ),
            migrated
        )
        assertFalse(migrated.contains("import net.minecraftforge.common.util.ITeleporter;"), migrated)
        assertTrue(passHasOneHelperImport(migrated), migrated)
    }

    private fun snapshotBytes(): Map<String, List<Byte>> = java.nio.file.Files.walk(directory).use { paths ->
        paths.filter(java.nio.file.Files::isRegularFile).toList().associate { path ->
            directory.relativize(path).toString() to java.nio.file.Files.readAllBytes(path).toList()
        }
    }

    private fun passHasOneHelperImport(source: String): Boolean =
        source.lines().count {
            it == "import com.modporter.generated.portal_probe.compat.LegacyPortalProbe;"
        } == 1
}
