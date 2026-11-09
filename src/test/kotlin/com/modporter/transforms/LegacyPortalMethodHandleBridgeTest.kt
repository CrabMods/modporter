package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyPortalMethodHandleReturns
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

class LegacyPortalMethodHandleBridgeTest {
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
            @Mod("portal_bridge") public class ExampleMod {}
        """)
    }

    @Test
    fun `exact virtual lookup is return filtered while lookup catch and null fallback remain`() {
        modEntry()
        val compat = java("example/ExternalPortalCompat.java", """
            package example;

            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import net.minecraft.core.BlockPos;
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
                        MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(owner, LOOKUP);
                        MethodType constructorType = MethodType.methodType(void.class, Entity.class);
                        constructorHandle = LOOKUP.findConstructor(owner, constructorType);
                        MethodType portalInfoType =
                            MethodType.methodType(PortalInfo.class, ServerLevel.class);
                        portalInfoHandle =
                            privateLookup.findVirtual(owner, "findDimensionEntryPoint", portalInfoType);
                    } catch (Exception exception) {
                        Logger.error("External portal compat failed", exception);
                        failed = true;
                    }
                }

                public static PortalInfo query(ServerLevel destination, Entity probe) {
                    if (!failed) {
                        try {
                            Object state = constructorHandle.invoke(probe);
                            return (PortalInfo) portalInfoHandle.invoke(state, destination);
                        } catch (Throwable throwable) {
                            Logger.error("External portal invocation failed", throwable);
                        }
                    }
                    return null;
                }
            }

            class Logger {
                static void error(String message, Throwable throwable) {}
            }
        """)
        val consumer = java("example/PortalConsumer.java", """
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
                    if (info == null) {
                        return null;
                    }
                    return info.pos;
                }
            }
        """)

        val pass = StructuralRefactorPass()
        val dryRun = pass.migrateLegacyPortalMethodHandleReturns(directory, dryRun = true)
        assertEquals(3, dryRun.size, dryRun.toString())
        assertFalse(
            directory.resolve(
                "src/main/java/com/modporter/generated/portal_bridge/compat/LegacyPortalProbe.java"
            ).exists()
        )

        val changes = pass.migrateLegacyPortalMethodHandleReturns(directory, dryRun = false)

        assertEquals(3, changes.size, changes.toString())
        val migrated = compat.readText()
        assertFalse(migrated.contains("MethodType.methodType(PortalInfo.class"), migrated)
        assertTrue(
            migrated.contains(
                "LegacyPortalProbe.findVirtual(privateLookup, owner, " +
                    "\"findDimensionEntryPoint\", ServerLevel.class)"
            ),
            migrated
        )
        assertTrue(migrated.contains("catch (Exception exception)"), migrated)
        assertTrue(migrated.contains("catch (Throwable throwable)"), migrated)
        assertTrue(migrated.contains("return null;"), migrated)
        val migratedConsumer = consumer.readText()
        assertTrue(
            migratedConsumer.contains("BiFunction<ServerLevel, Entity, ?> provider"),
            migratedConsumer
        )
        assertTrue(
            migratedConsumer.contains(
                "PortalInfo info = LegacyPortalProbe.portalInfo(" +
                    "provider.apply(destination, probe));"
            ),
            migratedConsumer
        )
        assertTrue(migratedConsumer.contains("return info.pos;"))

        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/portal_bridge/compat/LegacyPortalProbe.java"
        ).readText()
        assertTrue(helper.contains("owner.getDeclaredMethods()"), helper)
        assertTrue(helper.contains("lookup.findVirtual("), helper)
        assertTrue(helper.contains("MethodHandles.filterReturnValue(target, filter)"), helper)
        assertTrue(helper.contains("DimensionTransition.class.isAssignableFrom(returnType)"), helper)
        assertTrue(helper.contains("DimensionTransition cannot be reduced to PortalInfo"), helper)
        assertTrue(helper.contains("public static PortalInfo portalInfo("), helper)
        assertTrue(pass.migrateLegacyPortalMethodHandleReturns(directory, dryRun = false).isEmpty())
    }

    @Test
    fun `placement use outside the closed value consumer rejects the bridge`() {
        modEntry()
        val compat = java("example/OpenCompat.java", """
            package example;
            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;

            class OpenCompat {
                static MethodHandle handle;
                static {
                    try {
                        Class<?> owner = Class.forName("external.Open");
                        MethodHandles.Lookup lookup = MethodHandles.lookup();
                        handle = lookup.findVirtual(
                            owner,
                            "findDimensionEntryPoint",
                            MethodType.methodType(PortalInfo.class, ServerLevel.class)
                        );
                    } catch (Exception exception) {
                        Logger.error("failed", exception);
                    }
                }

                static PortalInfo query(ServerLevel level, Entity entity) {
                    try {
                        return (PortalInfo) handle.invoke(entity, level);
                    } catch (Throwable throwable) {
                        Logger.error("failed", throwable);
                    }
                    return null;
                }
            }
            class Logger {
                static void error(String message, Throwable throwable) {}
            }
        """)
        java("example/OpenConsumer.java", """
            package example;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            class OpenConsumer {
                void move(ServerLevel level, Entity entity) {
                    entity.changeDimension(level);
                    OpenCompat.query(level, entity);
                }
            }
        """)
        val original = compat.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyPortalMethodHandleReturns(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, compat.readText())
        assertFalse(
            directory.resolve(
                "src/main/java/com/modporter/generated/portal_bridge/compat/LegacyPortalProbe.java"
            ).exists()
        )
    }

    @Test
    fun `mixed canonical method reference and direct wrapper call reject the family`() {
        modEntry()
        val compat = java("example/MixedCompat.java", """
            package example;
            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;

            public class MixedCompat {
                private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
                private static MethodHandle handle;
                static {
                    try {
                        Class<?> owner = Class.forName("external.Mixed");
                        handle = LOOKUP.findVirtual(
                            owner,
                            "findDimensionEntryPoint",
                            MethodType.methodType(PortalInfo.class, ServerLevel.class)
                        );
                    } catch (Exception exception) {
                        Logger.error("failed", exception);
                    }
                }
                public static PortalInfo query(ServerLevel level, Entity entity) {
                    try {
                        return (PortalInfo) handle.invoke(entity, level);
                    } catch (Throwable throwable) {
                        Logger.error("failed", throwable);
                    }
                    return null;
                }
            }
            class Logger {
                static void error(String message, Throwable throwable) {}
            }
        """)
        java("example/MixedConsumer.java", """
            package example;
            import java.util.function.BiFunction;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            class MixedConsumer {
                Object external(ServerLevel level, Entity entity) {
                    inspect(level, entity, MixedCompat::query);
                    return MixedCompat.query(level, entity);
                }
                Object inspect(
                    ServerLevel level,
                    Entity entity,
                    BiFunction<ServerLevel, Entity, PortalInfo> provider
                ) {
                    PortalInfo info = provider.apply(level, entity);
                    return info == null ? null : info.pos;
                }
            }
        """)
        val original = compat.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyPortalMethodHandleReturns(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, compat.readText())
    }
}
