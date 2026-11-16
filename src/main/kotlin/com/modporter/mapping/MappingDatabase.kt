package com.modporter.mapping

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import com.modporter.core.pipeline.Confidence

private val logger = KotlinLogging.logger {}

/**
 * Central registry of migration mappings.
 * Loaded from JSON mapping files in resources/mappings/<pipeline>/.
 */
class MappingDatabase(private val mappingsPrefix: String = "/mappings/forge2neo") {
    private val textReplacements = mutableListOf<TextReplacement>()
    private val classRenames = mutableMapOf<String, ClassMapping>()
    private val methodRenames = mutableMapOf<String, MethodMapping>()
    private val resourceRenames = mutableMapOf<String, String>()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun load(mappingsPrefix: String): MappingDatabase {
            val db = MappingDatabase(mappingsPrefix)
            db.loadBuiltinMappings()
            return db
        }

        fun loadDefault(): MappingDatabase = load("/mappings/forge2neo")
    }

    private fun loadBuiltinMappings() {
        loadTextReplacementsFromResource("$mappingsPrefix/text-replacements.json")
        loadClassRenamesFromResource("$mappingsPrefix/class-renames.json")
        loadMethodRenamesFromResource("$mappingsPrefix/method-renames.json")
        loadResourceRenamesFromResource("$mappingsPrefix/resource-renames.json")
    }

    private fun loadTextReplacementsFromResource(path: String) {
        val stream = javaClass.getResourceAsStream(path) ?: run {
            logger.warn { "Mapping resource not found: $path" }
            return
        }
        val content = stream.bufferedReader().readText()
        val rules = json.decodeFromString<TextReplacementFile>(content)
        textReplacements.addAll(rules.replacements)
    }

    private fun loadClassRenamesFromResource(path: String) {
        val stream = javaClass.getResourceAsStream(path) ?: run {
            logger.warn { "Mapping resource not found: $path" }
            return
        }
        val content = stream.bufferedReader().readText()
        val rules = json.decodeFromString<ClassMappingFile>(content)
        rules.mappings.forEach { classRenames[it.forgeClass] = it }
    }

    private fun loadMethodRenamesFromResource(path: String) {
        val stream = javaClass.getResourceAsStream(path) ?: run {
            logger.warn { "Mapping resource not found: $path" }
            return
        }
        val content = stream.bufferedReader().readText()
        val rules = json.decodeFromString<MethodMappingFile>(content)
        rules.mappings.forEach { methodRenames["${it.forgeClass}.${it.forgeMethod}"] = it }
    }

    private fun loadResourceRenamesFromResource(path: String) {
        val stream = javaClass.getResourceAsStream(path) ?: run {
            logger.warn { "Mapping resource not found: $path" }
            return
        }
        val content = stream.bufferedReader().readText()
        val rules = json.decodeFromString<ResourceRenameFile>(content)
        rules.renames.forEach { resourceRenames[it.from] = it.to }
    }

    fun getTextReplacements(): List<TextReplacement> = textReplacements.toList()
    fun getClassMapping(forgeClass: String): ClassMapping? = classRenames[forgeClass]
    fun getAllClassMappings(): Map<String, ClassMapping> = classRenames.toMap()
    fun getMethodMapping(forgeClass: String, forgeMethod: String): MethodMapping? =
        methodRenames["$forgeClass.$forgeMethod"]
    fun getAllMethodMappings(): Map<String, MethodMapping> = methodRenames.toMap()
    fun getResourceRename(from: String): String? = resourceRenames[from]
    fun getAllResourceRenames(): Map<String, String> = resourceRenames.toMap()
}

// ---- Data models for mapping files ----

@Serializable
data class TextReplacementFile(val replacements: List<TextReplacement>)

@Serializable
data class TextReplacement(
    val id: String,
    val pattern: String,
    val replacement: String,
    val description: String,
    val fileGlob: String = "*.java",
    val isRegex: Boolean = false
)

@Serializable
data class ClassMappingFile(val mappings: List<ClassMapping>)

@Serializable
data class ClassMapping(
    val forgeClass: String,
    val neoForgeClass: String,
    val forgePackage: String = "",
    val neoForgePackage: String = "",
    val description: String = ""
)

@Serializable
data class MethodMappingFile(val mappings: List<MethodMapping>)

@Serializable
data class MethodMapping(
    val forgeClass: String,
    val forgeMethod: String,
    val forgeSignature: String = "",
    val neoForgeClass: String,
    val neoForgeMethod: String,
    val neoForgeSignature: String = "",
    val description: String = ""
)

@Serializable
data class ResourceRenameFile(val renames: List<ResourceRename>)

@Serializable
data class ResourceRename(
    val from: String,
    val to: String,
    val description: String = ""
)
