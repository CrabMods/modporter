package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyPortalProbeTransitions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticPortalFunctionBoundariesTest {
    @TempDir lateinit var directory: Path
    private fun java(name: String, source: String): Path = directory.resolve("src/main/java/example/$name.java").also {
        it.parent.createDirectories()
        it.writeText(source.trimIndent())
    }

    private fun fixture(shadow: Boolean): Pair<Path, Path> {
        java("Example", """
            package example;
            import net.neoforged.fml.common.Mod;
            @Mod("portal_probe") class Example {}
        """)
        val provider = java("Provider", """
            package example;
            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraftforge.common.util.ITeleporter;
            class Provider {
                static PortalInfo query(ServerLevel destination, Probe probe, Function<ServerLevel, ITeleporter> factory) {
                    ITeleporter teleporter = factory.apply(destination);
                    return teleporter.getPortalInfo(probe, destination, probe::findDimensionEntryPoint);
                }
            }
        """)
        val api = java("Api", """
            package example;
            import java.util.function.Function;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraftforge.common.util.ITeleporter;
            class Api {
                static PortalInfo forward(ServerLevel destination, Probe probe, Function<ServerLevel, ITeleporter> factory) {
                    ${if (shadow) "Other Provider = null;" else ""}
                    return Provider.query(destination, probe, factory);
                }
                static PortalInfo vanilla(ServerLevel destination, Probe probe) {
                    return Api.forward(destination, probe, ServerLevel::getPortalForcer);
                }
            }
        """)
        return provider to api
    }

    @Test
    fun `static project forwarders and direct method reference arguments close to the proven dispatch`() {
        val (provider, api) = fixture(false)
        val changes = StructuralRefactorPass().migrateLegacyPortalProbeTransitions(directory, false)
        assertTrue(changes.isNotEmpty(), changes.toString())
        assertTrue(provider.readText().contains("LegacyPortalProbe.adapt(factory.apply(destination))"), provider.readText())
        assertTrue(api.readText().contains("LegacyPortalProbe::vanilla"), api.readText())
        assertFalse(api.readText().contains("import net.minecraftforge.common.util.ITeleporter;"), api.readText())
    }

    @Test
    fun `a value shadowing the static owner rejects the complete provider family`() {
        val (provider, api) = fixture(true)
        val beforeProvider = provider.readText()
        val beforeApi = api.readText()
        assertTrue(StructuralRefactorPass().migrateLegacyPortalProbeTransitions(directory, false).isEmpty())
        assertTrue(provider.readText() == beforeProvider)
        assertTrue(api.readText() == beforeApi)
    }
}
