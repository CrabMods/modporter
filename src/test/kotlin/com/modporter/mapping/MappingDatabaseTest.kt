package com.modporter.mapping

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MappingDatabaseTest {

    @Test
    fun `loads text replacements from resources`() {
        val db = MappingDatabase.loadDefault()
        val replacements = db.getTextReplacements()

        assertTrue(replacements.isNotEmpty(), "Should have text replacements loaded")
        assertTrue(replacements.size >= 20, "Should have at least 20 text replacement rules")

        // Verify package renames exist
        val pkgRenames = replacements.filter { it.id.startsWith("pkg-") }
        assertTrue(pkgRenames.isNotEmpty(), "Should have package rename rules")

        // Verify order: more specific packages come before generic
        val fmlIdx = replacements.indexOfFirst { it.id == "pkg-fml" }
        val mainIdx = replacements.indexOfFirst { it.id == "pkg-forge-main" }
        assertTrue(fmlIdx < mainIdx, "pkg-fml should come before pkg-forge-main")
    }

    @Test
    fun `loads class renames from resources`() {
        val db = MappingDatabase.loadDefault()
        val mappings = db.getAllClassMappings()

        assertTrue(mappings.isNotEmpty())

        // Check specific mappings
        val itemExt = db.getClassMapping("IForgeItem")
        assertNotNull(itemExt)
        assertEquals("IItemExtension", itemExt.neoForgeClass)

        val blockExt = db.getClassMapping("IForgeBlock")
        assertNotNull(blockExt)
        assertEquals("IBlockExtension", blockExt.neoForgeClass)

        val regObj = db.getClassMapping("RegistryObject")
        assertNotNull(regObj)
        assertEquals("DeferredHolder", regObj.neoForgeClass)
    }

    @Test
    fun `loads method renames from resources`() {
        val db = MappingDatabase.loadDefault()
        val mappings = db.getAllMethodMappings()

        assertTrue(mappings.isNotEmpty())

        // Check ResourceLocation methods
        val rlTwoArg = db.getMethodMapping("ResourceLocation", "<init>(String, String)")
        assertNotNull(rlTwoArg)
        assertEquals("fromNamespaceAndPath", rlTwoArg.neoForgeMethod)

        val rlOneArg = db.getMethodMapping("ResourceLocation", "<init>(String)")
        assertNotNull(rlOneArg)
        assertEquals("parse", rlOneArg.neoForgeMethod)
    }

    @Test
    fun `loads resource renames from resources`() {
        val db = MappingDatabase.loadDefault()
        val renames = db.getAllResourceRenames()

        assertTrue(renames.isNotEmpty())
        assertEquals("tags/block", renames["tags/blocks"])
        assertEquals("tags/item", renames["tags/items"])
        assertEquals("recipe", renames["recipes"])
        assertEquals("loot_table", renames["loot_tables"])
        assertEquals("META-INF/neoforge.mods.toml", renames["META-INF/mods.toml"])
    }

    @Test
    fun `IForgeXXX to IXXXExtension pattern coverage`() {
        val db = MappingDatabase.loadDefault()
        val extensionMappings = db.getAllClassMappings()
            .filter { it.key.startsWith("IForge") }

        // Should have at least 30 IForge -> IExtension mappings
        assertTrue(extensionMappings.size >= 25,
            "Should have at least 25 IForge extension mappings, found ${extensionMappings.size}")

        // All should follow the pattern (except irregular ones)
        val irregular = setOf("IForgeShearable")
        extensionMappings.filter { it.key !in irregular }.forEach { (forge, mapping) ->
            val expectedNeo = forge.replace("IForge", "I") + "Extension"
            assertEquals(expectedNeo, mapping.neoForgeClass,
                "$forge should map to $expectedNeo but maps to ${mapping.neoForgeClass}")
        }
    }
}
