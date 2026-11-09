package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyMinecraftServerNetherGate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyServerNetherEnablementTest {
    @Test
    fun `negative null-return gate uses the actual Nether level and keeps failure behavior`() {
        val source = """
            package example;

            import net.minecraft.server.MinecraftServer;
            import net.minecraft.server.level.ServerLevel;

            public class PortalTracks {
                private static Exit nether(ServerLevel level) {
                    MinecraftServer minecraftServer = level.getServer();
                    if (!minecraftServer.isNetherEnabled())
                        return null;
                    return enter(level);
                }
            }
        """.trimIndent()

        val migrated = with(StructuralRefactorPass()) {
            migrateLegacyMinecraftServerNetherGate(source)
        }

        assertTrue(
            migrated.contains(
                "net.minecraft.server.level.ServerLevel netherLevel = " +
                    "minecraftServer.getLevel(net.minecraft.world.level.Level.NETHER);"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "if (netherLevel == null || !minecraftServer.isLevelEnabled(netherLevel)) return null;"
            ),
            migrated
        )
        assertTrue(migrated.contains("return enter(level);"), migrated)
        assertFalse(migrated.contains("isNetherEnabled"), migrated)
    }

    @Test
    fun `other server bindings and non-null-return boolean uses remain unchanged`() {
        val shadowed = """
            package example;

            class MinecraftServer {
                boolean isNetherEnabled() { return true; }
            }

            class Example {
                boolean enabled(MinecraftServer server) {
                    return server.isNetherEnabled();
                }
            }
        """.trimIndent()
        val positiveUse = """
            package example;

            import net.minecraft.server.MinecraftServer;

            class Example {
                boolean enabled(MinecraftServer server) {
                    return server.isNetherEnabled();
                }
            }
        """.trimIndent()

        val pass = StructuralRefactorPass()
        assertEquals(shadowed, pass.migrateLegacyMinecraftServerNetherGate(shadowed))
        assertEquals(positiveUse, pass.migrateLegacyMinecraftServerNetherGate(positiveUse))
    }
}
