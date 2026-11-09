package com.modporter.transforms

import com.modporter.core.transforms.build.DepResolution
import com.modporter.core.transforms.build.DependencyResolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyResolverTest {
    @Test
    fun `create compat libraries resolve offline to their neoforge 1_21_1 artifacts`() {
        val resolver = DependencyResolver(offlineMode = true)

        val chunks = resolver.resolve("""compileOnly("dev.ftb.mods:ftb-chunks-forge:2001.1.4")""")
        assertTrue(chunks is DepResolution.Resolved, chunks.toString())
        assertEquals("dev.ftb.mods:ftb-chunks-neoforge:2101.1.1", (chunks as DepResolution.Resolved).coords.single().coord)
        assertEquals(false, chunks.coords.single().transitive)

        val architectury = resolver.resolve("""compileOnly("dev.architectury:architectury-forge:9.1.12")""")
        assertTrue(architectury is DepResolution.Resolved, architectury.toString())
        assertEquals("dev.architectury:architectury-neoforge:13.0.6", (architectury as DepResolution.Resolved).coords.single().coord)

        val computercraft = resolver.resolve("""compileOnly("cc.tweaked:cc-tweaked-1.21.1-core-api:1.105.0")""")
        assertTrue(computercraft is DepResolution.Resolved, computercraft.toString())
        assertTrue((computercraft as DepResolution.Resolved).coords.any { it.coord == "cc.tweaked:cc-tweaked-1.21.1-forge:1.116.1" && it.config == "runtimeOnly" })
        assertTrue(resolver.targetVersionPropertiesForBuild("""runtimeOnly("cc.tweaked:cc-tweaked-${'$'}{cc_tweaked_minecraft_version}-forge:${'$'}{cc_tweaked_version}")""")
            .any { it.name == "cc_tweaked_version" && it.value == "1.116.1" })
    }

    @Test
    fun `modrinth resolutions keep the declaring configuration`() {
        val resolver = DependencyResolver(offlineMode = true)
        val body = """[{"version_number":"4.1-488","game_versions":["1.21.1"],"loaders":["neoforge"]}]"""

        val compileOnly = resolver.parseModrinthResponse("quark", body, resolver.modrinthConfiguration("compileOnly"))

        assertTrue(compileOnly is DepResolution.Resolved, compileOnly.toString())
        assertEquals("compileOnly", (compileOnly as DepResolution.Resolved).coords.single().config)
        assertEquals("maven.modrinth:quark:4.1-488", compileOnly.coords.single().coord)
        assertEquals("runtimeOnly", resolver.modrinthConfiguration("runtimeOnly"))
        assertEquals("implementation", resolver.modrinthConfiguration("jarJar"))
        assertEquals("implementation", resolver.modrinthConfiguration(null))
    }
}
