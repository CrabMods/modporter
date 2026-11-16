package com.modporter.transforms

import com.modporter.mapping.MappingDatabase
import com.modporter.resources.ResourceMigrationPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for mods.toml → neoforge.mods.toml content transformation.
 * Verifies all field changes required for NeoForge 1.21.1 compatibility.
 */
class ModsTomlTransformTest {

    @TempDir
    lateinit var tempDir: Path

    private val pass = ResourceMigrationPass(MappingDatabase.loadDefault())

    private fun transformToml(input: String): String {
        val file = tempDir.resolve("test.toml")
        file.writeText(input)
        pass.transformModsToml(file)
        return file.readText()
    }

    @Test
    fun `updates loaderVersion from Forge range to NeoForge`() {
        val result = transformToml("""
            modLoader="javafml"
            loaderVersion="[47,)"
        """.trimIndent())

        assertTrue(result.contains("""loaderVersion="[1,)""""), "loaderVersion should be [1,)")
        assertFalse(result.contains("[47,)"), "Old Forge version range should be gone")
    }

    @Test
    fun `updates loaderVersion with different Forge versions`() {
        // Some mods might use different Forge version ranges
        val result = transformToml("""loaderVersion="[43,)".""".trimIndent())
        assertTrue(result.contains("[1,)"), "Should handle other Forge version ranges")
    }

    @Test
    fun `replaces modId forge with neoforge in dependencies`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
        """.trimIndent())

        assertTrue(result.contains("""modId="neoforge""""), "forge → neoforge")
        assertFalse(result.contains("""modId="forge""""), "No forge modId remaining")
    }

    @Test
    fun `replaces modId forge with spaces around equals`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId = "forge"
        """.trimIndent())

        assertTrue(result.contains("""modId="neoforge""""), "Should handle spaces around =")
        assertFalse(result.contains(""""forge""""), "No forge reference remaining")
    }

    @Test
    fun `replaces mandatory true with type required`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="neoforge"
            mandatory=true
            versionRange="[47,)"
        """.trimIndent())

        assertTrue(result.contains("""type="required""""), "mandatory=true → type=\"required\"")
        assertFalse(result.contains("mandatory=true"), "No mandatory=true remaining")
    }

    @Test
    fun `replaces mandatory false with type optional`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="jei"
            mandatory=false
        """.trimIndent())

        assertTrue(result.contains("""type="optional""""), "mandatory=false → type=\"optional\"")
        assertFalse(result.contains("mandatory=false"), "No mandatory=false remaining")
    }

    @Test
    fun `updates neoforge dependency versionRange`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="neoforge"
            type="required"
            versionRange="[47,)"
        """.trimIndent())

        assertTrue(result.contains("[21.1,)"), "NeoForge versionRange should be [21.1,)")
    }

    @Test
    fun `removes mandatory when dependency already has type`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="jei"
            type="optional"
            mandatory=false
        """.trimIndent())

        assertTrue(result.contains("""type="optional""""), "Existing type field is preserved")
        assertFalse(result.contains("mandatory"), "mandatory field should be removed")
        assertTrue(Regex("""type\s*=""").findAll(result).count() == 1, "No duplicate type keys")
    }

    @Test
    fun `updates minecraft dependency versionRange`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="minecraft"
            type="required"
            versionRange="[1.20.1,1.21)"
        """.trimIndent())

        assertTrue(result.contains("[1.21.1,1.22)"), "MC versionRange should be [1.21.1,1.22)")
        assertFalse(result.contains("[1.20.1"), "Old MC version range should be gone")
    }

    @Test
    fun `updates known 121 dependency version ranges`() {
        val result = transformToml("""
            [[dependencies.sakura]]
            modId="mysterious_mountain_lib"
            type="required"
            versionRange="[1.5.18-1.20.1,)"

            [[dependencies.sakura]]
            modId="terrablender"
            type="optional"
            versionRange="[3.0.0,)"
        """.trimIndent())

        assertTrue(result.contains("""modId="mysterious_mountain_lib""""))
        assertTrue(result.contains("""versionRange="[1.0.0,)""""))
        assertTrue(result.contains("""modId="terrablender""""))
        assertTrue(result.contains("""versionRange="[4.0.0,)""""))
        assertFalse(result.contains("1.5.18-1.20.1"))
        assertFalse(result.contains("[3.0.0,)"))
    }

    @Test
    fun `dependency migrations are bounded to dependency tables`() {
        val result = transformToml("""
            [[mods]]
            modId="forge"
            mandatory=false
            versionRange="[47,)"

            [[dependencies.examplemod]]
            modId = "forge"
            mandatory=true
            versionRange="[47,)"

            [metadata]
            modId="forge"
            type="metadata"
            mandatory=false
            versionRange="[47,)"

            [[dependencies.examplemod]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.20.1,1.21)"
        """.trimIndent())

        val modBlock = result.substringAfter("[[mods]]").substringBefore("[[dependencies.examplemod]]")
        val neoforgeDependencyBlock = result.substringAfter("[[dependencies.examplemod]]").substringBefore("[metadata]")
        val metadataBlock = result.substringAfter("[metadata]").substringBeforeLast("[[dependencies.examplemod]]")
        val minecraftDependencyBlock = result.substringAfterLast("[[dependencies.examplemod]]")

        assertTrue(modBlock.contains("""modId="forge""""), modBlock)
        assertTrue(modBlock.contains("mandatory=false"), modBlock)
        assertTrue(modBlock.contains("""versionRange="[47,)""""), modBlock)

        assertTrue(neoforgeDependencyBlock.contains("""modId="neoforge""""), neoforgeDependencyBlock)
        assertTrue(neoforgeDependencyBlock.contains("""type="required""""), neoforgeDependencyBlock)
        assertTrue(neoforgeDependencyBlock.contains("""versionRange="[21.1,)""""), neoforgeDependencyBlock)
        assertFalse(neoforgeDependencyBlock.contains("mandatory"), neoforgeDependencyBlock)

        assertTrue(metadataBlock.contains("""modId="forge""""), metadataBlock)
        assertTrue(metadataBlock.contains("""type="metadata""""), metadataBlock)
        assertTrue(metadataBlock.contains("mandatory=false"), metadataBlock)
        assertTrue(metadataBlock.contains("""versionRange="[47,)""""), metadataBlock)

        assertTrue(minecraftDependencyBlock.contains("""type="required""""), minecraftDependencyBlock)
        assertTrue(minecraftDependencyBlock.contains("""versionRange="[1.21.1,1.22)""""), minecraftDependencyBlock)
        assertFalse(minecraftDependencyBlock.contains("mandatory"), minecraftDependencyBlock)
    }

    @Test
    fun `removes displayTest field`() {
        val result = transformToml("""
            [[mods]]
            modId="mymod"
            displayTest="MATCH_VERSION"
            version="1.0"
        """.trimIndent())

        assertFalse(result.contains("displayTest"), "displayTest should be removed")
        assertTrue(result.contains("modId=\"mymod\""), "Other fields preserved")
    }

    @Test
    fun `removes clientSideOnly field`() {
        val result = transformToml("""
            modLoader="javafml"
            clientSideOnly=true
            [[mods]]
            modId="mymod"
        """.trimIndent())

        assertFalse(result.contains("clientSideOnly"), "clientSideOnly should be removed")
    }

    @Test
    fun `preserves unrelated fields`() {
        val result = transformToml("""
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"
            [[mods]]
            modId="mymod"
            version="1.0.0"
            displayName="My Mod"
            description="A test mod"
            authors="TestAuthor"
        """.trimIndent())

        assertTrue(result.contains("""license="MIT""""), "license preserved")
        assertTrue(result.contains("""modId="mymod""""), "modId preserved")
        assertTrue(result.contains("""version="1.0.0""""), "version preserved")
        assertTrue(result.contains("""displayName="My Mod""""), "displayName preserved")
        assertTrue(result.contains("""authors="TestAuthor""""), "authors preserved")
    }

    @Test
    fun `handles complete realistic mods toml`() {
        val result = transformToml("""
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"

            [[mods]]
            modId="examplemod"
            version="1.0.0"
            displayName="Example Mod"
            displayTest="MATCH_VERSION"
            description='''An example mod'''

            [[dependencies.examplemod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
            ordering="NONE"
            side="BOTH"

            [[dependencies.examplemod]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.20.1,1.21)"
            ordering="NONE"
            side="BOTH"

            [[dependencies.examplemod]]
            modId="jei"
            mandatory=false
            versionRange="[15.2,)"
            ordering="AFTER"
            side="CLIENT"
        """.trimIndent())

        // Verify all transformations
        assertTrue(result.contains("""loaderVersion="[1,)""""), "loaderVersion updated")
        assertFalse(result.contains("displayTest"), "displayTest removed")
        assertTrue(result.contains("""modId="neoforge""""), "forge → neoforge")
        assertTrue(result.contains("[21.1,)"), "NeoForge version range")
        assertTrue(result.contains("[1.21.1,1.22)"), "MC version range")
        assertTrue(result.contains("""type="required""""), "mandatory=true → type=required")
        assertTrue(result.contains("""type="optional""""), "mandatory=false → type=optional")
        assertFalse(result.contains("mandatory"), "No mandatory field remaining")

        // Preserve unrelated fields
        assertTrue(result.contains("""license="MIT""""), "license preserved")
        assertTrue(result.contains("""ordering="AFTER""""), "ordering preserved")
        assertTrue(result.contains("""side="CLIENT""""), "side preserved")
        assertTrue(result.contains("""modId="jei""""), "JEI dep preserved")
    }

    @Test
    fun `does not modify non-forge dependency modId`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="jei"
            mandatory=false
        """.trimIndent())

        assertTrue(result.contains("""modId="jei""""), "JEI modId should not change")
    }

    @Test
    fun `cleans up double blank lines from removals`() {
        val result = transformToml("""
            modLoader="javafml"
            clientSideOnly=true
            loaderVersion="[47,)"

            [[mods]]
            modId="mymod"
            displayTest="MATCH_VERSION"
            version="1.0"
        """.trimIndent())

        assertFalse(result.contains("\n\n\n"), "Should not have triple+ blank lines")
    }

    @Test
    fun `custom enchantment resource keys hard gate missing source derived data`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.enchantment.Enchantment;

            public final class ModEnchantments {
                public static final net.minecraft.resources.ResourceKey<Enchantment> PERMANENCE =
                    net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT,
                        ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "permanence"));
            }
        """.trimIndent())

        val result = pass.apply(tempDir)
        val generated = tempDir.resolve("src/generated/resources/data/example/enchantment/permanence.json")

        assertTrue(result.changes.any { it.ruleId == "res-custom-enchantment-data" })
        assertTrue(result.errors.any {
            it.contains("Missing source-derived data-driven custom enchantment JSON for 'example:permanence'")
        })
        assertFalse(generated.exists(), "Resource migration must not create default custom enchantment JSON")
    }
}
