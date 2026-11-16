package com.modporter.mapping

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for mapping data classes serialization/deserialization and edge cases.
 */
class MappingDataClassTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `TextReplacement defaults work`() {
        val rule = TextReplacement(
            id = "test",
            pattern = "foo",
            replacement = "bar",
            description = "test rule"
        )
        assertEquals("*.java", rule.fileGlob)
        assertEquals(false, rule.isRegex)
    }

    @Test
    fun `ClassMapping with all fields`() {
        val mapping = ClassMapping(
            forgeClass = "ForgeHooks",
            neoForgeClass = "CommonHooks",
            forgePackage = "net.minecraftforge.common",
            neoForgePackage = "net.neoforged.neoforge.common",
            description = "Main hooks class"
        )
        assertEquals("ForgeHooks", mapping.forgeClass)
        assertEquals("CommonHooks", mapping.neoForgeClass)
        assertEquals("net.minecraftforge.common", mapping.forgePackage)
        assertEquals("Main hooks class", mapping.description)
    }

    @Test
    fun `ClassMapping with defaults`() {
        val mapping = ClassMapping(
            forgeClass = "ForgeHooks",
            neoForgeClass = "CommonHooks"
        )
        assertEquals("", mapping.forgePackage)
        assertEquals("", mapping.neoForgePackage)
        assertEquals("", mapping.description)
    }

    @Test
    fun `MethodMapping with all fields`() {
        val mapping = MethodMapping(
            forgeClass = "ResourceLocation",
            forgeMethod = "<init>(String, String)",
            forgeSignature = "(Ljava/lang/String;Ljava/lang/String;)V",
            neoForgeClass = "ResourceLocation",
            neoForgeMethod = "fromNamespaceAndPath",
            neoForgeSignature = "(Ljava/lang/String;Ljava/lang/String;)LResourceLocation;",
            description = "Constructor to factory"
        )
        assertEquals("ResourceLocation", mapping.forgeClass)
        assertEquals("fromNamespaceAndPath", mapping.neoForgeMethod)
    }

    @Test
    fun `MethodMapping with defaults`() {
        val mapping = MethodMapping(
            forgeClass = "A",
            forgeMethod = "b",
            neoForgeClass = "C",
            neoForgeMethod = "d"
        )
        assertEquals("", mapping.forgeSignature)
        assertEquals("", mapping.neoForgeSignature)
        assertEquals("", mapping.description)
    }

    @Test
    fun `ResourceRename with defaults`() {
        val rename = ResourceRename(from = "tags/blocks", to = "tags/block")
        assertEquals("", rename.description)
    }

    @Test
    fun `TextReplacementFile deserialization`() {
        val jsonStr = """{"replacements":[{"id":"t1","pattern":"a","replacement":"b","description":"d"}]}"""
        val file = json.decodeFromString<TextReplacementFile>(jsonStr)
        assertEquals(1, file.replacements.size)
        assertEquals("t1", file.replacements[0].id)
    }

    @Test
    fun `ClassMappingFile deserialization`() {
        val jsonStr = """{"mappings":[{"forgeClass":"A","neoForgeClass":"B"}]}"""
        val file = json.decodeFromString<ClassMappingFile>(jsonStr)
        assertEquals(1, file.mappings.size)
        assertEquals("A", file.mappings[0].forgeClass)
    }

    @Test
    fun `MethodMappingFile deserialization`() {
        val jsonStr = """{"mappings":[{"forgeClass":"A","forgeMethod":"b","neoForgeClass":"C","neoForgeMethod":"d"}]}"""
        val file = json.decodeFromString<MethodMappingFile>(jsonStr)
        assertEquals(1, file.mappings.size)
    }

    @Test
    fun `ResourceRenameFile deserialization`() {
        val jsonStr = """{"renames":[{"from":"a","to":"b"}]}"""
        val file = json.decodeFromString<ResourceRenameFile>(jsonStr)
        assertEquals(1, file.renames.size)
    }

    @Test
    fun `MappingDatabase handles missing resource files gracefully`() {
        // loadDefault should work even if some files hypothetically don't exist
        val db = MappingDatabase.loadDefault()
        assertNotNull(db)
        assertTrue(db.getTextReplacements().isNotEmpty())
    }

    @Test
    fun `getClassMapping returns null for unknown class`() {
        val db = MappingDatabase.loadDefault()
        val result = db.getClassMapping("NonExistentClass")
        assertEquals(null, result)
    }

    @Test
    fun `getMethodMapping returns null for unknown method`() {
        val db = MappingDatabase.loadDefault()
        val result = db.getMethodMapping("Unknown", "unknown")
        assertEquals(null, result)
    }

    @Test
    fun `getResourceRename returns null for unknown path`() {
        val db = MappingDatabase.loadDefault()
        val result = db.getResourceRename("nonexistent/path")
        assertEquals(null, result)
    }
}
