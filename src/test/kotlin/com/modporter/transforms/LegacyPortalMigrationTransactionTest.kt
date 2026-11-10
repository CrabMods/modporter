package com.modporter.transforms

import com.modporter.core.transforms.structural.PortalTransactionFault
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyPortalFamilyTransaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LegacyPortalMigrationTransactionTest {
    @TempDir
    lateinit var directory: Path

    private fun java(relative: String, source: String): Path {
        val file = directory.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }

    private fun modEntry() {
        java("example/ExampleMod.java", """
            package example;
            import net.neoforged.fml.common.Mod;
            @Mod("portal_transaction") public class ExampleMod {}
        """)
    }

    private fun legacyValue(): Path = java("example/PortalValue.java", """
        package example;
        import net.minecraft.world.level.portal.PortalInfo;
        import net.minecraft.world.phys.Vec3;
        class PortalValue {
            Vec3 position(PortalInfo value) {
                return value.pos;
            }
        }
    """)

    private fun exactProbe(): Path = java("example/ProbeEntity.java", """
        package example;
        import net.minecraft.server.level.ServerLevel;
        import net.minecraft.world.entity.Entity;
        import net.minecraft.world.level.portal.PortalInfo;
        class ProbeEntity extends Entity {
            void rememberEntrance() {
                portalEntrancePos = blockPosition();
            }
            @Override
            public PortalInfo findDimensionEntryPoint(ServerLevel destination) {
                return super.findDimensionEntryPoint(destination);
            }
        }
    """)

    private fun methodHandleFamily() {
        java("example/ExternalPortalCompat.java", """
            package example;
            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;

            public class ExternalPortalCompat {
                private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
                private static MethodHandle constructorHandle;
                private static MethodHandle portalInfoHandle;
                private static boolean failed;
                static {
                    try {
                        Class<?> owner = Class.forName("external.portal.TravelerState");
                        MethodHandles.Lookup privateLookup =
                            MethodHandles.privateLookupIn(owner, LOOKUP);
                        constructorHandle = LOOKUP.findConstructor(
                            owner,
                            MethodType.methodType(void.class, Entity.class)
                        );
                        MethodType portalInfoType =
                            MethodType.methodType(PortalInfo.class, ServerLevel.class);
                        portalInfoHandle = privateLookup.findVirtual(
                            owner,
                            "findDimensionEntryPoint",
                            portalInfoType
                        );
                    } catch (Exception exception) {
                        Logger.error("lookup failed", exception);
                        failed = true;
                    }
                }
                public static PortalInfo query(ServerLevel destination, Entity probe) {
                    if (!failed) {
                        try {
                            Object state = constructorHandle.invoke(probe);
                            return (PortalInfo) portalInfoHandle.invoke(state, destination);
                        } catch (Throwable throwable) {
                            Logger.error("invoke failed", throwable);
                        }
                    }
                    return null;
                }
            }
            class Logger {
                static void error(String message, Throwable throwable) {}
            }
        """)
        java("example/PortalConsumer.java", """
            package example;
            import java.util.function.BiFunction;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            class PortalConsumer {
                Object external(ServerLevel destination, Entity probe) {
                    return inspect(destination, probe, ExternalPortalCompat::query);
                }
                Object inspect(
                    ServerLevel destination,
                    Entity probe,
                    BiFunction<ServerLevel, Entity, PortalInfo> provider
                ) {
                    PortalInfo info = provider.apply(destination, probe);
                    return info == null ? null : info.pos;
                }
            }
        """)
    }

    private fun snapshot(): Map<Path, ByteArray> {
        if (!Files.exists(directory)) return emptyMap()
        return Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .toList()
                .associate { file -> directory.relativize(file) to file.readBytes() }
        }
    }

    private fun assertSnapshotUnchanged(expected: Map<Path, ByteArray>) {
        val actual = snapshot()
        assertEquals(expected.keys, actual.keys)
        for ((relative, bytes) in expected) {
            assertContentEquals(bytes, actual.getValue(relative), relative.toString())
        }
    }

    @Test
    fun `failure after first temporary migration leaves every original byte unchanged`() {
        modEntry()
        methodHandleFamily()
        val original = snapshot()

        assertFailsWith<IllegalStateException> {
            StructuralRefactorPass().migrateLegacyPortalFamilyTransaction(
                directory,
                dryRun = false,
                fault = PortalTransactionFault.AFTER_FIRST_TEMP_MIGRATION
            )
        }

        assertSnapshotUnchanged(original)
    }

    @Test
    fun `failure after first publish staging entry leaves every original byte unchanged`() {
        modEntry()
        methodHandleFamily()
        val original = snapshot()

        assertFailsWith<IllegalStateException> {
            StructuralRefactorPass().migrateLegacyPortalFamilyTransaction(
                directory,
                dryRun = false,
                fault = PortalTransactionFault.AFTER_FIRST_PRESTAGE
            )
        }

        assertSnapshotUnchanged(original)
    }

    @Test
    fun `helper resource failure leaves every original byte unchanged`() {
        modEntry()
        methodHandleFamily()
        val original = snapshot()

        assertFailsWith<IllegalStateException> {
            StructuralRefactorPass().migrateLegacyPortalFamilyTransaction(
                directory,
                dryRun = false,
                fault = PortalTransactionFault.HELPER_LOAD
            )
        }

        assertSnapshotUnchanged(original)
    }

    @Test
    fun `ordinary teleporter family is outside the transaction`() {
        modEntry()
        java("example/UnknownTeleporter.java", """
            package example;
            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraftforge.common.util.ITeleporter;

            class UnknownTeleporter implements ITeleporter {
                @Override
                public PortalInfo getPortalInfo(
                    Entity entity,
                    ServerLevel destination,
                    Function<ServerLevel, PortalInfo> defaultPortalInfo
                ) {
                    return defaultPortalInfo.apply(destination);
                }
            }
        """)
        val original = snapshot()

        val changes = StructuralRefactorPass().migrateLegacyPortalFamilyTransaction(
            directory,
            dryRun = false
        )

        assertTrue(changes.isEmpty(), changes.toString())
        assertSnapshotUnchanged(original)
    }

    @Test
    fun `PortalInfo value only project is outside the transaction`() {
        modEntry()
        legacyValue()
        val original = snapshot()

        val changes = StructuralRefactorPass().migrateLegacyPortalFamilyTransaction(
            directory,
            dryRun = false
        )

        assertTrue(changes.isEmpty(), changes.toString())
        assertSnapshotUnchanged(original)
    }

    @Test
    fun `exact probe preserves an unrelated ordinary teleporter byte for byte`() {
        modEntry()
        val probe = exactProbe()
        val teleporter = java("example/UnrelatedTeleporter.java", """
            package example;
            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraftforge.common.util.ITeleporter;

            class UnrelatedTeleporter implements ITeleporter {
                @Override
                public PortalInfo getPortalInfo(
                    Entity entity,
                    ServerLevel destination,
                    Function<ServerLevel, PortalInfo> defaultPortalInfo
                ) {
                    return defaultPortalInfo.apply(destination);
                }
            }
        """)
        val originalProbe = probe.readBytes()
        val originalTeleporter = teleporter.readBytes()
        val original = snapshot()
        val pass = StructuralRefactorPass()

        val preview = pass.migrateLegacyPortalFamilyTransaction(directory, dryRun = true)
        assertTrue(preview.isNotEmpty(), preview.toString())
        assertSnapshotUnchanged(original)

        val changes = pass.migrateLegacyPortalFamilyTransaction(
            directory,
            dryRun = false
        )

        assertTrue(changes.isNotEmpty(), changes.toString())
        assertTrue(!probe.readBytes().contentEquals(originalProbe))
        assertContentEquals(originalTeleporter, teleporter.readBytes())
        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/portal_transaction/compat/LegacyPortalProbe.java"
        ).readText()
        assertTrue(helper.contains("findClosestPortalPosition(exitPos, isNether, worldBorder)"), helper)
        assertTrue(helper.contains("TicketType.PORTAL"), helper)
        assertTrue(helper.contains("PortalForcer.TICKET_RADIUS"), helper)
        assertTrue(helper.contains("PortalShape.findCollisionFreePosition("), helper)
        assertTrue(!helper.contains("MethodHandle"), helper)
        assertTrue(!helper.contains("java.lang.reflect"), helper)
        assertTrue(!helper.contains(".createPortal("), helper)
        val migrated = snapshot()
        assertTrue(pass.migrateLegacyPortalFamilyTransaction(directory, dryRun = false).isEmpty())
        assertSnapshotUnchanged(migrated)
    }

    @Test
    fun `failure after first atomic publish rolls the aggregate project back`() {
        modEntry()
        methodHandleFamily()
        val original = snapshot()

        assertFailsWith<IllegalStateException> {
            StructuralRefactorPass().migrateLegacyPortalFamilyTransaction(
                directory,
                dryRun = false,
                fault = PortalTransactionFault.AFTER_FIRST_PUBLISH
            )
        }

        assertSnapshotUnchanged(original)
    }

    @Test
    fun `dry run executes the temporary three pass plan without original writes`() {
        modEntry()
        methodHandleFamily()
        val original = snapshot()

        val changes = StructuralRefactorPass().migrateLegacyPortalFamilyTransaction(
            directory,
            dryRun = true
        )

        assertTrue(changes.isNotEmpty(), changes.toString())
        assertSnapshotUnchanged(original)
    }
}
