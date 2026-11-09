package com.modporter.resources

import com.modporter.core.pipeline.*
import com.modporter.mapping.MappingDatabase
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}
private val RESOURCE_JSON = Json {
    prettyPrint = true
}
private val COMMON_TAG_ROOTS = listOf(
    "bones",
    "chests",
    "cobblestone",
    "crops",
    "dusts",
    "dyes",
    "eggs",
    "ender_pearls",
    "end_stones",
    "feathers",
    "fences",
    "gems",
    "glass",
    "glass_panes",
    "gravel",
    "ingots",
    "leather",
    "nether_stars",
    "nuggets",
    "ores",
    "raw_materials",
    "rods",
    "sand",
    "seeds",
    "storage_blocks",
    "stone",
    "string"
)
private val COMMON_TAG_NAMESPACE_PATTERN = Regex(
    """([#"])(?:forge|neoforge):(${COMMON_TAG_ROOTS.joinToString("|") { Regex.escape(it) }})(?=[/"\]\s,}])"""
)
private val FLATTENABLE_WORLDGEN_VALUE_PROVIDER_TYPES = setOf(
    "biased_to_bottom",
    "trapezoid",
    "uniform",
    "very_biased_to_bottom"
)
private val TAG_REFERENCE_VALUE_KEYS = setOf(
    "blockTag",
    "fluidTag",
    "itemTag",
    "tag"
)
private val INGREDIENT_LIST_KEYS = setOf(
    "ingredients",
    "repair_ingredients"
)
private val COMMON_TAG_PATH_RENAMES = mapOf(
    "cobblestone" to "cobblestones",
    "glass" to "glass_blocks",
    "gravel" to "gravels",
    "leather" to "leathers",
    "sand" to "sands",
    "stone" to "stones",
    "string" to "strings",
    "tools/bows" to "tools/bow",
    "tools/brushes" to "tools/brush",
    "tools/crossbows" to "tools/crossbow",
    "tools/fishing_rods" to "tools/fishing_rod",
    "tools/knives" to "tools/knife",
    "tools/shears" to "tools/shear",
    "tools/shields" to "tools/shield",
    "tools/tridents" to "tools/spear",
    "tools/wrenches" to "tools/wrench"
)
private val COMMON_TAG_PATH_PREFIX_RENAMES = mapOf(
    "cobblestone/" to "cobblestones/",
    "glass/" to "glass_blocks/",
    "gravel/" to "gravels/",
    "sand/" to "sands/"
)
private val RESOURCE_ID_RENAMES_121 = mapOf(
    "minecraft:grass" to "minecraft:short_grass",
    "minecraft:block/grass" to "minecraft:block/short_grass"
)
private val MODEL_EXTENSION_RENAMES_121 = linkedMapOf(
    "\"forge_data\"" to "\"neoforge_data\"",
    "\"forge:composite\"" to "\"neoforge:composite\"",
    "\"forge:elements\"" to "\"neoforge:elements\"",
    "\"forge:empty\"" to "\"neoforge:empty\"",
    "\"forge:fluid_container\"" to "\"neoforge:fluid_container\"",
    "\"forge:item_layers\"" to "\"neoforge:item_layers\"",
    "\"forge:obj\"" to "\"neoforge:obj\"",
    "\"forge:separate_transforms\"" to "\"neoforge:separate_transforms\""
)

/**
 * Pass 5: Resource file migration.
 * Handles data folder renames, mods.toml migration, and pack format updates.
 */
class ResourceMigrationPass(
    private val mappingDb: MappingDatabase,
    private val addToolCredit: Boolean = false,
    private val toolCreditName: String = "ModPorter",
    private val toolCreditUrl: String = "https://github.com/CrabMods/modporter"
) : Pass {
    override val name = "Resource Migration"
    override val order = 5

    override fun analyze(projectDir: Path): PassResult = processResources(projectDir, dryRun = true)
    override fun apply(projectDir: Path): PassResult = processResources(projectDir, dryRun = false)

    private fun processResources(projectDir: Path, dryRun: Boolean): PassResult {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        val renames = mappingDb.getAllResourceRenames()

        // Find resource directories
        val resourceDirs = findResourceDirs(projectDir)

        for (resourceDir in resourceDirs) {
            // Handle mods.toml rename
            val modsToml = resourceDir.resolve("META-INF/mods.toml")
            if (modsToml.exists()) {
                val target = resourceDir.resolve("META-INF/neoforge.mods.toml")
                changes.add(Change(
                    file = modsToml, line = 0,
                    description = "Rename mods.toml -> neoforge.mods.toml",
                    before = "META-INF/mods.toml",
                    after = "META-INF/neoforge.mods.toml",
                    confidence = Confidence.HIGH,
                    ruleId = "res-mods-toml"
                ))
                if (!dryRun) {
                    Files.move(modsToml, target, StandardCopyOption.REPLACE_EXISTING)
                    transformModsToml(target)
                }
            }
            if (addToolCredit) {
                val creditFile = resourceDir.resolve("META-INF/neoforge.mods.toml").takeIf { it.exists() }
                    ?: modsToml.takeIf { it.exists() }
                if (creditFile != null) {
                    appendToolCredit(creditFile, changes, dryRun)
                }
            }

            // Collect all data folder renames BEFORE executing any
            // (to avoid stale directory issues during walk)
            val dataDir = resourceDir.resolve("data")
            if (dataDir.exists()) {
                val folderRenames = renames.filter { it.key != "META-INF/mods.toml" }
                val pendingMoves = mutableListOf<Pair<Path, Path>>()

                // Snapshot all directories once
                val allDirs = Files.walk(dataDir, 10)
                    .filter { Files.isDirectory(it) }
                    .toList()

                for ((from, to) in folderRenames) {
                    // Extract just the last segment of `to` for renaming
                    // e.g., from="tags/items" to="tags/item" → lastSegment="item"
                    val toLastSegment = to.substringAfterLast("/")
                    for (dir in allDirs) {
                        val relative = dataDir.relativize(dir).toString().replace('\\', '/')
                        if (relative.endsWith("/$from") || relative == from) {
                            val targetPath = dir.parent.resolve(toLastSegment)
                            changes.add(Change(
                                file = dir, line = 0,
                                description = "Rename data folder: $from -> $to",
                                before = dir.toString(),
                                after = targetPath.toString(),
                                confidence = Confidence.HIGH,
                                ruleId = "res-folder-rename"
                            ))
                            pendingMoves.add(dir to targetPath)
                        }
                    }
                }

                // Execute all renames after collection
                if (!dryRun) {
                    for ((source, target) in pendingMoves) {
                        if (source.exists()) {
                            try {
                                if (target.exists()) {
                                    mergeDirectoryContents(source, target, errors)
                                } else {
                                    Files.move(source, target)
                                }
                            } catch (e: Exception) {
                                errors.add("Failed to rename $source: ${e.message}")
                            }
                        }
                    }
                }
            }

            // Migrate data/forge/ -> split between data/c/ (tags) and data/neoforge/ (non-tags)
            val forgeDataDir = resourceDir.resolve("data/forge")
            if (forgeDataDir.exists() && !dryRun) {
                migrateForgeDataDir(forgeDataDir, resourceDir, changes, errors)
            } else if (forgeDataDir.exists()) {
                changes.add(Change(
                    file = forgeDataDir, line = 0,
                    description = "Migrate data/forge/ -> data/c/ (tags) + data/neoforge/ (non-tags)",
                    before = "data/forge/",
                    after = "data/c/ + data/neoforge/",
                    confidence = Confidence.HIGH,
                    ruleId = "res-forge-namespace"
                ))
            }

            // Transform JSON data files: conditions, recipe format, namespace
            if (dataDir.exists()) {
                normalizeCommonTagFilePaths(resourceDir, changes, errors, dryRun)
                if (!dryRun) {
                    transformDataJsonFiles(dataDir, projectDir, changes, errors)
                    transformDataFunctionFiles(dataDir, changes, errors)
                    generateMissingSourceDefinedBlockTags(projectDir, resourceDir, changes, errors, dryRun)
                }
                migrateBannerPatternDataResources(dataDir, changes, errors, dryRun)
            }

            val assetsDir = resourceDir.resolve("assets")
            if (assetsDir.exists()) {
                migrateLegacyFogDistanceShaders(assetsDir, changes, errors, dryRun)
                if (!dryRun) {
                    transformAssetJsonFiles(assetsDir, resourceDirs, changes, errors)
                    fillMissingSoundSubtitleTranslations(assetsDir, changes, errors)
                    generateMissingItemModels(projectDir, resourceDir, changes, errors)
                    normalizeItemTextureMipDimensions(assetsDir, changes, errors)
                }
            }

            // Update pack.mcmeta
            val packMcmeta = resourceDir.resolve("pack.mcmeta")
            if (packMcmeta.exists()) {
                changes.add(Change(
                    file = packMcmeta, line = 0,
                    description = "Update pack_format: 15 -> 48 (data pack) with supported_formats 34-48",
                    before = "\"pack_format\": 15",
                    after = "\"pack_format\": 48",
                    confidence = Confidence.HIGH,
                    ruleId = "res-pack-format"
                ))
                if (!dryRun) {
                    updatePackFormat(packMcmeta)
                }
            }
        }

        if (isCreateStructureNbtOwner(projectDir)) {
            val createStructureNbtPlan = CreateStructureNbtMigrationStage.plan(resourceDirs)
            if (createStructureNbtPlan.blockers.isNotEmpty()) {
                errors += createStructureNbtPlan.blockers.map {
                    "Create structure NBT migration refused: $it"
                }
            } else {
                createStructureNbtPlan.files.forEach { file ->
                    val migratedPayloads = buildList {
                        if (file.migratedTankCount > 0) {
                            add("${file.migratedTankCount} tank FluidStack payload(s)")
                        }
                        if (file.migratedAirCount > 0) {
                            add("${file.migratedAirCount} empty ItemStack payload(s)")
                        }
                        if (file.migratedItemCount > 0) {
                            add("${file.migratedItemCount} nonempty ItemStack payload(s)")
                        }
                        if (file.migratedGravityCount > 0) {
                            add("${file.migratedGravityCount} entity gravity attribute(s)")
                        }
                        if (file.migratedEmptyEntityCount > 0) {
                            add(
                                "${file.migratedEmptyEntityCount} " +
                                    "source-proven empty entity wrapper(s)"
                            )
                        }
                    }.joinToString(" and ")
                    val appliedRuleIds = buildList {
                        if (file.migratedTankCount > 0) {
                            add(CreateStructureFluidNbtMigration.RULE_ID)
                        }
                        if (file.migratedAirCount > 0) {
                            add(CreateStructureEmptyItemNbtMigration.RULE_ID)
                        }
                        if (file.migratedItemCount > 0) {
                            add(CreateStructureItemStackNbtMigration.RULE_ID)
                        }
                        if (file.migratedGravityCount > 0) {
                            add(CreateStructureGravityAttributeNbtMigration.RULE_ID)
                        }
                        if (file.migratedEmptyEntityCount > 0) {
                            add(CreateStructureEmptyEntityNbtMigration.RULE_ID)
                        }
                    }
                    val ruleId =
                        appliedRuleIds.singleOrNull() ?: CreateStructureNbtMigrationStage.RULE_ID
                    changes += Change(
                        file = file.path,
                        line = 0,
                        description = "Migrate $migratedPayloads in Create structure NBT",
                        before = file.originalSha256,
                        after = file.migratedSha256,
                        confidence = Confidence.HIGH,
                        ruleId = ruleId
                    )
                }
                if (!dryRun) {
                    CreateStructureNbtMigrationStage.apply(createStructureNbtPlan)
                }
            }
        }

        if (!dryRun) {
            generateMissingLiquidBlockAssets(projectDir, resourceDirs, changes, errors)
            generateMissingLightBlockAssets(projectDir, resourceDirs, changes, errors)
            generateLegacyNitrogenFuelSprites(projectDir, resourceDirs, changes, errors)
        }

        // Handle template mods.toml files (used by Groovy template expansion)
        migrateTemplateFiles(projectDir, changes, errors, dryRun)

        migrateCustomEnchantmentData(projectDir, changes, errors)

        return PassResult(name, changes, errors)
    }

    private fun migrateLegacyFogDistanceShaders(
        assetsDir: Path,
        changes: MutableList<Change>,
        errors: MutableList<String>,
        dryRun: Boolean
    ) {
        val vertexShaders = Files.walk(assetsDir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".vsh") }
                .sorted()
                .toList()
        }
        val uniformPattern = Regex(
            """(?m)^[ \t]*uniform\s+mat3\s+IViewRotMat\s*;\s*\r?\n"""
        )
        val legacyCallPattern = Regex(
            """(?<![A-Za-z0-9_$])fog_distance(?![A-Za-z0-9_$])\s*\(\s*ModelViewMat\s*,\s*IViewRotMat\s*\*\s*([A-Za-z_$][\w$]*)\s*,\s*FogShape\s*\)"""
        )
        val viewRotationName = Regex("""\bIViewRotMat\b""")

        vertexShaders.forEach { vertexShader ->
            val source = vertexShader.readText()
            if (!source.contains("fog_distance") || !source.contains("IViewRotMat")) {
                return@forEach
            }
            val executableSource = maskGlslComments(source)
            val uniforms = uniformPattern.findAll(executableSource).toList()
            val calls = legacyCallPattern.findAll(executableSource).toList()
            val references = viewRotationName.findAll(executableSource).toList()
            if (uniforms.size != 1 || calls.size != 1 || references.size != 2) {
                errors +=
                    "Cannot migrate legacy fog_distance shader $vertexShader: expected one IViewRotMat " +
                        "uniform and one exact legacy call with no other IViewRotMat references"
                return@forEach
            }

            val programJson = vertexShader.resolveSibling(
                vertexShader.fileName.toString().removeSuffix(".vsh") + ".json"
            )
            if (!programJson.exists()) {
                errors +=
                    "Cannot migrate legacy fog_distance shader $vertexShader: paired program JSON is missing"
                return@forEach
            }
            val programSource = programJson.readText()
            val program = parseResourceJson(programSource) as? JsonObject
            val programUniforms = program?.get("uniforms") as? JsonArray
            if (program == null || programUniforms == null) {
                errors +=
                    "Cannot migrate legacy fog_distance shader $vertexShader: paired program JSON has no uniforms array"
                return@forEach
            }
            val rotationUniforms = programUniforms.filter { uniform ->
                (uniform as? JsonObject)
                    ?.get("name")
                    .let { it as? JsonPrimitive }
                    ?.contentOrNull == "IViewRotMat"
            }
            if (rotationUniforms.size != 1) {
                errors +=
                    "Cannot migrate legacy fog_distance shader $vertexShader: paired program JSON must declare " +
                        "IViewRotMat exactly once"
                return@forEach
            }

            val position = calls.single().groupValues[1]
            var migratedVertex = source
            listOf(
                calls.single().range to "fog_distance($position, FogShape)",
                uniforms.single().range to ""
            ).sortedByDescending { it.first.first }
                .forEach { (range, replacement) ->
                    migratedVertex = migratedVertex.replaceRange(range, replacement)
                }
            val migratedProgram = JsonObject(
                program.mapValues { (key, value) ->
                    if (key == "uniforms") {
                        JsonArray(programUniforms.filterNot { uniform ->
                            (uniform as? JsonObject)
                                ?.get("name")
                                .let { it as? JsonPrimitive }
                                ?.contentOrNull == "IViewRotMat"
                        })
                    } else {
                        value
                    }
                }
            )
            val migratedProgramSource =
                RESOURCE_JSON.encodeToString(JsonElement.serializer(), migratedProgram) + "\n"

            changes += Change(
                file = vertexShader,
                line = source.lineSequence()
                    .takeWhile { !it.contains("fog_distance") }
                    .count() + 1,
                description = "Migrate the legacy three-argument fog_distance shader call",
                before = calls.single().value,
                after = "fog_distance($position, FogShape)",
                confidence = Confidence.HIGH,
                ruleId = "res-shader-fog-distance-121"
            )
            changes += Change(
                file = programJson,
                line = 0,
                description = "Remove the obsolete IViewRotMat shader uniform",
                before = "\"name\": \"IViewRotMat\"",
                after = "removed",
                confidence = Confidence.HIGH,
                ruleId = "res-shader-fog-distance-121"
            )
            if (!dryRun) {
                vertexShader.writeText(migratedVertex)
                programJson.writeText(migratedProgramSource)
            }
        }
    }

    private fun maskGlslComments(source: String): String {
        val masked = source.toCharArray()
        var index = 0
        while (index < source.length) {
            if (index + 1 < source.length && source[index] == '/' && source[index + 1] == '/') {
                masked[index] = ' '
                masked[index + 1] = ' '
                index += 2
                while (index < source.length && source[index] != '\n' && source[index] != '\r') {
                    masked[index] = ' '
                    index++
                }
                continue
            }
            if (index + 1 < source.length && source[index] == '/' && source[index + 1] == '*') {
                masked[index] = ' '
                masked[index + 1] = ' '
                index += 2
                while (index < source.length) {
                    if (index + 1 < source.length && source[index] == '*' && source[index + 1] == '/') {
                        masked[index] = ' '
                        masked[index + 1] = ' '
                        index += 2
                        break
                    }
                    if (source[index] != '\n' && source[index] != '\r') {
                        masked[index] = ' '
                    }
                    index++
                }
                continue
            }
            index++
        }
        return masked.concatToString()
    }

    private fun mergeDirectoryContents(source: Path, target: Path, errors: MutableList<String>) {
        target.createDirectories()
        Files.walk(source)
            .filter { Files.isRegularFile(it) }
            .forEach { file ->
                val relative = source.relativize(file)
                val destination = target.resolve(relative)
                try {
                    destination.parent.createDirectories()
                    if (destination.exists() && destination.readBytes().contentEquals(file.readBytes())) {
                        Files.deleteIfExists(file)
                    } else {
                        Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: Exception) {
                    errors.add("Failed to merge $file into $destination: ${e.message}")
                }
            }
        Files.walk(source)
            .sorted(Comparator.reverseOrder())
            .filter { Files.isDirectory(it) }
            .forEach { dir ->
                try {
                    Files.deleteIfExists(dir)
                } catch (e: Exception) {
                    errors.add("Failed to remove merged directory $dir: ${e.message}")
                }
            }
    }

    private fun normalizeCommonTagFilePaths(
        resourceDir: Path,
        changes: MutableList<Change>,
        errors: MutableList<String>,
        dryRun: Boolean
    ) {
        val commonTagsDir = resourceDir.resolve("data/c/tags")
        if (!commonTagsDir.exists()) return
        val tagFiles = Files.walk(commonTagsDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
            .toList()
        for (file in tagFiles) {
            val relative = commonTagsDir.relativize(file).toString().replace('\\', '/')
            val segments = relative.split('/')
            if (segments.size < 2) continue
            val registry = segments.first()
            val tagPath = segments.drop(1).joinToString("/").removeSuffix(".json")
            val normalizedTagPath = normalizeCommonTagPath(tagPath)
            if (normalizedTagPath == tagPath) continue
            val target = commonTagsDir.resolve(registry).resolve("$normalizedTagPath.json")
            changes.add(Change(
                file = file,
                line = 0,
                description = "Common tag path: $tagPath -> $normalizedTagPath",
                before = "c:$tagPath",
                after = "c:$normalizedTagPath",
                confidence = Confidence.HIGH,
                ruleId = "res-common-tag-path"
            ))
            if (dryRun) continue
            try {
                target.parent.createDirectories()
                if (target.exists()) {
                    val merged = mergeTagJson(target.readText(), file.readText())
                    if (merged != null) {
                        target.writeText(merged)
                        Files.deleteIfExists(file)
                    } else {
                        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                } else {
                    Files.move(file, target)
                }
            } catch (e: Exception) {
                errors.add("Failed to normalize common tag $file -> $target: ${e.message}")
            }
        }
    }

    private fun mergeTagJson(existingContent: String, incomingContent: String): String? {
        val existing = parseResourceJson(existingContent) as? JsonObject ?: return null
        val incoming = parseResourceJson(incomingContent) as? JsonObject ?: return null
        val values = linkedMapOf<String, JsonElement>()
        fun collect(root: JsonObject) {
            val array = root["values"] as? JsonArray ?: return
            for (value in array) values.putIfAbsent(value.toString(), value)
        }
        collect(existing)
        collect(incoming)
        val merged = linkedMapOf<String, JsonElement>()
        for ((key, value) in existing) {
            if (key != "values") merged[key] = value
        }
        if (existing["replace"] == null && incoming["replace"] != null) {
            merged["replace"] = incoming["replace"]!!
        }
        merged["values"] = JsonArray(values.values.toList())
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), JsonObject(merged)) + "\n"
    }

    private fun isCreateStructureNbtOwner(projectDir: Path): Boolean =
        listOf(
            "src/main/java/com/simibubi/create/Create.java",
            "src/main/java/com/simibubi/create/content/fluids/tank/FluidTankBlockEntity.java"
        ).all { projectDir.resolve(it).isRegularFile() }

    private fun findResourceDirs(projectDir: Path): List<Path> {
        val dirs = mutableListOf<Path>()
        val mainResources = projectDir.resolve("src/main/resources")
        if (mainResources.exists()) dirs.add(mainResources)
        val genResources = projectDir.resolve("src/generated/resources")
        if (genResources.exists()) dirs.add(genResources)
        // Every other source set with resources (game-test mods such as src/bucketTest) ships the
        // same mod metadata and data packs, which the loader reads with the 1.21 layout.
        val src = projectDir.resolve("src")
        if (src.exists()) {
            runCatching {
                Files.list(src).use { sets ->
                    sets.filter { Files.isDirectory(it) }
                        .map { it.resolve("resources") }
                        .filter { Files.isDirectory(it) && it !in dirs && it.parent.fileName.toString() != "references" }
                        .sorted()
                        .forEach { dirs.add(it) }
                }
            }
        }
        return dirs
    }

    /**
     * Find and transform template mods.toml files (used by Groovy template expansion in build.gradle).
     * These need the same transformations as regular mods.toml plus template variable renames.
     */
    private fun migrateTemplateFiles(projectDir: Path, changes: MutableList<Change>, errors: MutableList<String>, dryRun: Boolean) {
        val templateDirs = listOf(
            projectDir.resolve("src/main/templates"),
            projectDir.resolve("src/generated/templates"),
        )

        for (templateDir in templateDirs) {
            val modsToml = templateDir.resolve("META-INF/mods.toml")
            if (!modsToml.exists()) continue

            val target = templateDir.resolve("META-INF/neoforge.mods.toml")
            changes.add(Change(
                file = modsToml, line = 0,
                description = "Rename template mods.toml -> neoforge.mods.toml",
                before = "templates/META-INF/mods.toml",
                after = "templates/META-INF/neoforge.mods.toml",
                confidence = Confidence.HIGH,
                ruleId = "res-template-mods-toml"
            ))

            if (!dryRun) {
                Files.move(modsToml, target, StandardCopyOption.REPLACE_EXISTING)
                // Fix template variable references BEFORE transformModsToml
                // (so that ${forge_version_range} becomes ${neoforge_version_range}
                // instead of being resolved as a fixed version range)
                transformTemplateVariables(target)
                transformModsToml(target)
            }
            if (addToolCredit) {
                val creditFile = target.takeIf { it.exists() } ?: modsToml.takeIf { it.exists() }
                if (creditFile != null) {
                    appendToolCredit(creditFile, changes, dryRun)
                }
            }
        }
    }

    /**
     * Fix Groovy template variable references in template mods.toml files.
     * e.g., ${forge_version_range} -> ${neoforge_version_range}
     *        ${forge_version} -> ${neo_forge_version}
     */
    private fun transformTemplateVariables(file: Path) {
        var content = file.readText()
        content = content.replace("\${forge_version_range}", "\${neoforge_version_range}")
        content = content.replace("\${forge_version}", "\${neo_forge_version}")
        file.writeText(content)
    }

    /**
     * Full mods.toml → neoforge.mods.toml content transformation.
     *
     * Changes applied:
     * 1. loaderVersion: [47,) → [1,)
     * 2. modId="forge" → modId="neoforge" in dependencies
     * 3. mandatory=true → type="required", mandatory=false → type="optional"
     * 4. Forge dependency versionRange → [21.1,)
     * 5. Minecraft dependency versionRange → [1.21.1,1.22)
     * 6. Remove displayTest field
     * 7. Remove clientSideOnly field
     */
    internal fun transformModsToml(file: Path) {
        var content = file.readText()

        // 1. Update loader version range: [47,) or similar Forge ranges → [1,) (official NeoForge MDK value)
        content = content.replace(
            Regex("""loaderVersion\s*=\s*"\[[\d,.]+\)""""),
            """loaderVersion="[1,)""""
        )

        // 2. Update Forge dependency modId to NeoForge inside dependency tables.

        // 3. Replace mandatory=true/false with type="required"/"optional".
        // Some Forge mods already carry a NeoForge-style type field; in that case mandatory
        // must be removed instead of converted, otherwise the TOML table has duplicate keys.
        content = migrateMandatoryFields(content)

        // 4. Update NeoForge dependency versionRange
        // 5. Update Minecraft dependency versionRange
        content = updateDependencyBlocks(content)

        // 6. Remove displayTest line (removed in NeoForge)
        content = content.replace(
            Regex("""^\s*displayTest\s*=\s*"[^"]*"\s*$""", RegexOption.MULTILINE),
            ""
        )

        // 7. Remove clientSideOnly line (removed in NeoForge)
        content = content.replace(
            Regex("""^\s*clientSideOnly\s*=\s*(true|false)\s*$""", RegexOption.MULTILINE),
            ""
        )

        // 8. Fix Groovy template variable references
        content = content.replace("\${forge_version_range}", "\${neoforge_version_range}")
        content = content.replace("\${forge_version}", "\${neo_forge_version}")

        // Clean up any double blank lines left from removals
        content = content.replace(Regex("""\n{3,}"""), "\n\n")

        file.writeText(content)
    }

    private fun appendToolCredit(file: Path, changes: MutableList<Change>, dryRun: Boolean) {
        val before = file.readText()
        val after = appendToolCreditToModsToml(before)
        if (after == before) return
        changes.add(Change(
            file = file,
            line = 0,
            description = "Append ModPorter tool credit to mod metadata",
            before = "credits",
            after = toolCreditText(),
            confidence = Confidence.HIGH,
            ruleId = "res-tool-credit"
        ))
        if (!dryRun) {
            file.writeText(after)
        }
    }

    internal fun appendToolCreditToModsToml(content: String): String {
        val credit = toolCreditText()
        if (content.contains(toolCreditUrl) || content.contains("Ported with $toolCreditName")) return content

        val simpleCredits = Regex("""(?m)^([ \t]*credits\s*=\s*")([^"\r\n]*)(".*)$""")
        simpleCredits.find(content)?.let { match ->
            val current = match.groupValues[2].trim()
            val separator = if (current.isBlank()) "" else "; "
            val replacement = "${match.groupValues[1]}${current}${separator}${escapeTomlBasicString(credit)}${match.groupValues[3]}"
            return content.substring(0, match.range.first) + replacement + content.substring(match.range.last + 1)
        }

        val literalCredits = Regex(
            """^([ \t]*credits\s*=\s*''')(.*?)('''[ \t]*(?:#.*)?$)""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )
        literalCredits.find(content)?.let { match ->
            val current = match.groupValues[2].trim()
            val separator = if (current.isBlank()) "" else "; "
            val replacement = "${match.groupValues[1]}${current}${separator}$credit${match.groupValues[3]}"
            return content.substring(0, match.range.first) + replacement + content.substring(match.range.last + 1)
        }

        val modsHeader = Regex("""(?m)^[ \t]*\[\[\s*mods\s*]][ \t]*(?:#.*)?$""").find(content)
            ?: return content
        val nextTable = Regex("""(?m)^[ \t]*\[\[""")
            .find(content, modsHeader.range.last + 1)
            ?.range
            ?.first
            ?: content.length
        val modBlock = content.substring(modsHeader.range.first, nextTable)
        val insertRelative = Regex("""(?m)^[ \t]*(?:authors|description)\s*=""")
            .find(modBlock)
            ?.range
            ?.first
            ?: modBlock.length
        val indent = Regex("""(?m)^([ \t]*)modId\s*=""")
            .find(modBlock)
            ?.groupValues
            ?.get(1)
            ?: ""
        val insertAt = modsHeader.range.first + insertRelative
        val prefix = content.substring(0, insertAt)
        val suffix = content.substring(insertAt)
        val newlineBefore = if (prefix.endsWith("\n") || prefix.isEmpty()) "" else "\n"
        val newlineAfter = if (suffix.startsWith("\n") || suffix.isEmpty()) "" else "\n"
        return prefix + newlineBefore + indent + "credits=\"${escapeTomlBasicString(credit)}\"" + "\n" + newlineAfter + suffix
    }

    private fun toolCreditText(): String = "Ported with $toolCreditName: $toolCreditUrl"

    private fun escapeTomlBasicString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun migrateCustomEnchantmentData(projectDir: Path, changes: MutableList<Change>, errors: MutableList<String>) {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return

        val javaSources = srcDir.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.toPath() to it.readText() }
            .toList()
        if (javaSources.isEmpty()) return

        val modIds = detectJavaModIds(javaSources)
        val enchantmentKeys = linkedSetOf<CustomEnchantmentKey>()
        val keyPattern = Regex(
            """(?:net\.minecraft\.resources\.)?ResourceKey<Enchantment>\s+(\w+)\s*=\s*[\s\S]*?(?:net\.minecraft\.resources\.)?ResourceLocation\.fromNamespaceAndPath\(\s*([^,]+?)\s*,\s*"([^"]+)"\s*\)"""
        )

        for ((_, content) in javaSources) {
            val code = maskJavaComments(content)
            val executableCode = maskJavaCommentsAndLiterals(content)
            val packageName = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
                .find(code)
                ?.groupValues
                ?.get(1)
                .orEmpty()
            for (match in keyPattern.findAll(code)) {
                val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                if (!executableSegment.contains("ResourceKey") ||
                    !executableSegment.contains("Enchantment") ||
                    !executableSegment.contains("ResourceLocation") ||
                    !executableSegment.contains("fromNamespaceAndPath")
                ) {
                    continue
                }
                val modId = resolveModIdExpression(
                    match.groupValues[2],
                    modIds,
                    code,
                    match.range.first,
                    packageName
                ) ?: continue
                enchantmentKeys.add(CustomEnchantmentKey(modId, match.groupValues[3]))
            }
        }

        if (enchantmentKeys.isEmpty()) return

        for (key in enchantmentKeys) {
            if (customEnchantmentDataExists(projectDir, key)) continue

            changes.add(Change(
                file = projectDir.resolve("src/generated/resources/data/${key.modId}/enchantment/${key.name}.json"),
                line = 0,
                description = "Require source-derived data-driven custom enchantment '${key.modId}:${key.name}'",
                before = "(missing)",
                after = "data/${key.modId}/enchantment/${key.name}.json",
                confidence = Confidence.HIGH,
                ruleId = "res-custom-enchantment-data"
            ))
            errors.add("Missing source-derived data-driven custom enchantment JSON for '${key.modId}:${key.name}'")
        }
    }

    private fun customEnchantmentDataExists(projectDir: Path, key: CustomEnchantmentKey): Boolean =
        listOf(
            projectDir.resolve("src/main/resources"),
            projectDir.resolve("src/generated/resources")
        ).any { root -> root.resolve("data/${key.modId}/enchantment/${key.name}.json").exists() }

    private fun generateMissingSourceDefinedBlockTags(
        projectDir: Path,
        resourceDir: Path,
        changes: MutableList<Change>,
        errors: MutableList<String>,
        dryRun: Boolean
    ) {
        try {
            val javaSources = collectJavaSourceInfos(projectDir)
            if (javaSources.isEmpty()) return
            val ownedNamespaces = detectJavaModIds(javaSources.map { it.file to it.content })
                .values
                .toSet()
            if (ownedNamespaces.isEmpty()) return

            val blockTags = collectSourceDefinedBlockTags(javaSources)
                .filter { it.namespace in ownedNamespaces }
                .toSet()
            for (tag in blockTags) {
                if (sourceDefinedBlockTagExists(projectDir, tag)) continue
                val target = resourceDir.resolve("data/${tag.namespace}/tags/block/${tag.path}.json")
                changes.add(Change(
                    file = target,
                    line = 0,
                    description = "Create empty data pack definition for source-defined block tag '${tag.namespace}:${tag.path}'",
                    before = "(missing)",
                    after = "data/${tag.namespace}/tags/block/${tag.path}.json",
                    confidence = Confidence.HIGH,
                    ruleId = "res-source-defined-block-tag"
                ))
                if (!dryRun) {
                    target.parent.createDirectories()
                    target.writeText(emptyTagJson())
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to generate source-defined block tag resources: ${e.message}")
        }
    }

    private fun collectSourceDefinedBlockTags(javaSources: List<JavaSourceInfo>): Set<SourceDefinedBlockTag> {
        val tags = linkedSetOf<SourceDefinedBlockTag>()
        val literalPairPatterns = listOf(
            Regex("""\b(?:[A-Za-z_$][\w$]*\.)?(?:modBlockTag|blockTag)\s*\(\s*"([a-z0-9_.-]+)"\s*,\s*"([a-z0-9_./-]+)"\s*\)"""),
            Regex("""\bBlockTags\.create\s*\(\s*(?:ResourceLocation|net\.minecraft\.resources\.ResourceLocation)\.fromNamespaceAndPath\s*\(\s*"([a-z0-9_.-]+)"\s*,\s*"([a-z0-9_./-]+)"\s*\)\s*\)"""),
            Regex("""\bTagKey\.create\s*\(\s*(?:Registries|net\.minecraft\.core\.registries\.Registries)\.BLOCK\s*,\s*(?:ResourceLocation|net\.minecraft\.resources\.ResourceLocation)\.fromNamespaceAndPath\s*\(\s*"([a-z0-9_.-]+)"\s*,\s*"([a-z0-9_./-]+)"\s*\)\s*\)""")
        )
        for (source in javaSources) {
            val executableCode = source.executableCode
            if (!executableCode.contains("BlockTag") &&
                !executableCode.contains("BlockTags.create") &&
                !executableCode.contains("Registries.BLOCK")) {
                continue
            }
            for (pattern in literalPairPatterns) {
                pattern.findAll(source.code).forEach { match ->
                    val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                    if (!executableSegment.contains("BlockTag") &&
                        !executableSegment.contains("BlockTags.create") &&
                        !executableSegment.contains("Registries.BLOCK")) {
                        return@forEach
                    }
                    val path = match.groupValues[2].trim('/')
                    if (path.isNotBlank()) {
                        tags += SourceDefinedBlockTag(match.groupValues[1], path)
                    }
                }
            }
        }
        return tags
    }

    private fun sourceDefinedBlockTagExists(projectDir: Path, tag: SourceDefinedBlockTag): Boolean =
        listOf(
            projectDir.resolve("src/main/resources"),
            projectDir.resolve("src/generated/resources")
        ).any { root -> root.resolve("data/${tag.namespace}/tags/block/${tag.path}.json").exists() }

    private fun emptyTagJson(): String = "{\n    \"values\": []\n}\n"

    private data class SourceDefinedBlockTag(val namespace: String, val path: String)

    private fun generateMissingLiquidBlockAssets(
        projectDir: Path,
        resourceDirs: List<Path>,
        changes: MutableList<Change>,
        errors: MutableList<String>
    ) {
        if (resourceDirs.isEmpty()) return
        try {
            val liquidBlocks = collectRegisteredLiquidBlocks(projectDir)
            if (liquidBlocks.isEmpty()) return

            val targetRoot = resourceDirs.firstOrNull { it.resolve("assets").exists() } ?: resourceDirs.first()
            for (block in liquidBlocks) {
                val blockstateRelative = "assets/${block.namespace}/blockstates/${block.path}.json"
                val modelRelative = "assets/${block.namespace}/models/block/${block.path}.json"
                val existingBlockstate = resourceDirs
                    .map { it.resolve(blockstateRelative) }
                    .firstOrNull { it.exists() }
                val blockstateFile = existingBlockstate ?: targetRoot.resolve(blockstateRelative)
                if (existingBlockstate == null) {
                    blockstateFile.parent.createDirectories()
                    blockstateFile.writeText(liquidBlockStateJson(block.namespace, block.path))
                    changes.add(Change(
                        file = blockstateFile,
                        line = 0,
                        description = "Create LiquidBlock blockstate level variants for '${block.namespace}:${block.path}'",
                        before = "(missing blockstate)",
                        after = blockstateRelative,
                        confidence = Confidence.HIGH,
                        ruleId = "res-liquid-blockstate-level-variants"
                    ))
                } else {
                    val original = blockstateFile.readText()
                    val migrated = addLiquidBlockStateLevelVariants(original, block.namespace, block.path)
                    if (migrated != null && migrated != original) {
                        blockstateFile.writeText(migrated)
                        changes.add(Change(
                            file = blockstateFile,
                            line = 0,
                            description = "Complete LiquidBlock blockstate level variants for '${block.namespace}:${block.path}'",
                            before = "missing level variants",
                            after = "level=0..15",
                            confidence = Confidence.HIGH,
                            ruleId = "res-liquid-blockstate-level-variants"
                        ))
                    }
                }

                val existingModel = resourceDirs
                    .map { it.resolve(modelRelative) }
                    .firstOrNull { it.exists() }
                if (existingModel == null) {
                    val modelFile = targetRoot.resolve(modelRelative)
                    modelFile.parent.createDirectories()
                    modelFile.writeText(liquidBlockModelJson())
                    changes.add(Change(
                        file = modelFile,
                        line = 0,
                        description = "Create minimal LiquidBlock model for '${block.namespace}:${block.path}'",
                        before = "(missing block model)",
                        after = modelRelative,
                        confidence = Confidence.HIGH,
                        ruleId = "res-liquid-block-model"
                    ))
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to generate LiquidBlock asset resources: ${e.message}")
        }
    }

    private fun collectRegisteredLiquidBlocks(projectDir: Path): Set<RegisteredLiquidBlock> {
        val javaSources = collectJavaSourceInfos(projectDir)
        if (javaSources.isEmpty()) return emptySet()
        val modIds = detectJavaModIds(javaSources.map { it.file to it.content })
        if (modIds.isEmpty()) return emptySet()

        val blocks = linkedSetOf<RegisteredLiquidBlock>()
        val deferredRegisterPattern = Regex(
            """\b([A-Za-z_$][\w$]*)\s*=\s*(?:(?:[A-Za-z_$][\w$]*\.)*)DeferredRegister\.create\s*\("""
        )
        val registerPattern = Regex("""\b([A-Za-z_$][\w$]*)\.register\s*\(\s*"([a-z0-9_./-]+)"\s*,""")

        for (source in javaSources) {
            if (!source.executableCode.contains("DeferredRegister") ||
                !source.executableCode.contains("LiquidBlock")) {
                continue
            }
            val blockRegistries = linkedMapOf<String, String>()
            for (match in deferredRegisterPattern.findAll(source.code)) {
                val openParen = source.code.indexOf('(', match.range.first)
                val closeParen = findMatchingJavaParen(source.code, openParen)
                if (openParen < 0 || closeParen < 0) continue
                val executableSegment = source.executableCode.substring(match.range.first, closeParen + 1)
                if (!executableSegment.contains("DeferredRegister") ||
                    !executableSegment.contains("create") ||
                    !executableSegment.contains("BLOCK")) {
                    continue
                }
                val args = splitTopLevelJavaArguments(source.code.substring(openParen + 1, closeParen))
                if (args.size < 2 || !isBlockRegistryArgument(args[0])) continue
                val namespace = resolveModIdExpression(
                    args[1],
                    modIds,
                    source.code,
                    match.range.first,
                    source.packageName
                ) ?: continue
                blockRegistries[match.groupValues[1]] = namespace
            }
            if (blockRegistries.isEmpty()) continue

            for (match in registerPattern.findAll(source.code)) {
                val namespace = blockRegistries[match.groupValues[1]] ?: continue
                val openParen = source.code.indexOf('(', match.range.first)
                val closeParen = findMatchingJavaParen(source.code, openParen)
                if (openParen < 0 || closeParen < 0) continue
                val executableSegment = source.executableCode.substring(match.range.first, closeParen + 1)
                if (!executableSegment.contains(".register") ||
                    !executableSegment.contains("new") ||
                    !executableSegment.contains("LiquidBlock")) {
                    continue
                }
                blocks += RegisteredLiquidBlock(namespace, match.groupValues[2].trim('/'))
            }
        }
        return blocks
    }

    private fun isBlockRegistryArgument(argument: String): Boolean {
        val compact = argument.replace(Regex("""\s+"""), "")
        return compact == "Registries.BLOCK" ||
            compact == "BuiltInRegistries.BLOCK" ||
            compact == "net.minecraft.core.registries.Registries.BLOCK" ||
            compact == "net.minecraft.core.registries.BuiltInRegistries.BLOCK"
    }

    private fun addLiquidBlockStateLevelVariants(content: String, namespace: String, path: String): String? {
        val root = parseResourceJson(content) as? JsonObject ?: return null
        val variants = root["variants"] as? JsonObject ?: return null
        val entries = linkedMapOf<String, JsonElement>()
        var changed = false
        for ((key, value) in variants) {
            if (key.isBlank()) {
                changed = true
            } else {
                entries[key] = value
            }
        }
        for (level in 0..15) {
            val key = "level=$level"
            if (entries[key] == null) {
                entries[key] = liquidBlockVariant(namespace, path)
                changed = true
            }
        }
        if (!changed) return content

        val migrated = linkedMapOf<String, JsonElement>()
        for ((key, value) in root) {
            migrated[key] = if (key == "variants") JsonObject(entries) else value
        }
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), JsonObject(migrated)) + "\n"
    }

    private fun liquidBlockStateJson(namespace: String, path: String): String {
        val variants = linkedMapOf<String, JsonElement>()
        for (level in 0..15) {
            variants["level=$level"] = liquidBlockVariant(namespace, path)
        }
        val root = JsonObject(mapOf("variants" to JsonObject(variants)))
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), root) + "\n"
    }

    private fun liquidBlockVariant(namespace: String, path: String): JsonObject =
        JsonObject(mapOf("model" to JsonPrimitive("$namespace:block/$path")))

    private fun liquidBlockModelJson(): String = """
{
  "textures": {
    "particle": "minecraft:block/water_still"
  }
}
""".trimIndent() + "\n"

    private data class RegisteredLiquidBlock(val namespace: String, val path: String)

    private fun generateMissingLightBlockAssets(
        projectDir: Path,
        resourceDirs: List<Path>,
        changes: MutableList<Change>,
        errors: MutableList<String>
    ) {
        if (resourceDirs.isEmpty()) return
        try {
            val lightBlocks = collectRegisteredLightBlocks(projectDir)
            if (lightBlocks.isEmpty()) return

            val targetRoot = resourceDirs.firstOrNull { it.resolve("assets").exists() } ?: resourceDirs.first()
            for (block in lightBlocks) {
                val blockstateRelative = "assets/${block.namespace}/blockstates/${block.path}.json"
                val existingBlockstate = resourceDirs
                    .map { it.resolve(blockstateRelative) }
                    .firstOrNull { it.exists() }
                val blockstateFile = existingBlockstate ?: targetRoot.resolve(blockstateRelative)
                if (existingBlockstate == null) {
                    blockstateFile.parent.createDirectories()
                    blockstateFile.writeText(lightBlockStateJson())
                    changes.add(Change(
                        file = blockstateFile,
                        line = 0,
                        description = "Create LightBlock blockstate level variants for '${block.namespace}:${block.path}'",
                        before = "(missing blockstate)",
                        after = blockstateRelative,
                        confidence = Confidence.HIGH,
                        ruleId = "res-light-blockstate-level-variants"
                    ))
                } else {
                    val original = blockstateFile.readText()
                    val migrated = addLightBlockStateLevelVariants(original)
                    if (migrated != null && migrated != original) {
                        blockstateFile.writeText(migrated)
                        changes.add(Change(
                            file = blockstateFile,
                            line = 0,
                            description = "Complete LightBlock blockstate level variants for '${block.namespace}:${block.path}'",
                            before = "missing level variants",
                            after = "level=0..15",
                            confidence = Confidence.HIGH,
                            ruleId = "res-light-blockstate-level-variants"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to generate LightBlock asset resources: ${e.message}")
        }
    }

    private fun collectRegisteredLightBlocks(projectDir: Path): Set<RegisteredLightBlock> {
        val javaSources = collectJavaSourceInfos(projectDir)
        if (javaSources.isEmpty()) return emptySet()
        val modIds = detectJavaModIds(javaSources.map { it.file to it.content })
        if (modIds.isEmpty()) return emptySet()
        val sourceIndex = JavaSourceIndex(javaSources)

        val blocks = linkedSetOf<RegisteredLightBlock>()
        val deferredRegisterPattern = Regex(
            """\b([A-Za-z_$][\w$]*)\s*=\s*(?:(?:[A-Za-z_$][\w$]*\.)*)DeferredRegister\.create\s*\("""
        )
        val registerPattern = Regex(
            """\b([A-Za-z_$][\w$]*)\.register\s*\(\s*"([a-z0-9_./-]+)"\s*,\s*(?:(?:\(\)\s*->\s*new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?))|([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)::new)"""
        )

        for (source in javaSources) {
            if (!source.executableCode.contains("DeferredRegister") ||
                !source.executableCode.contains(".register(")) {
                continue
            }
            val blockRegistries = linkedMapOf<String, String>()
            for (match in deferredRegisterPattern.findAll(source.code)) {
                val openParen = source.code.indexOf('(', match.range.first)
                val closeParen = findMatchingJavaParen(source.code, openParen)
                if (openParen < 0 || closeParen < 0) continue
                val executableSegment = source.executableCode.substring(match.range.first, closeParen + 1)
                if (!executableSegment.contains("DeferredRegister") ||
                    !executableSegment.contains("create") ||
                    !executableSegment.contains("BLOCK")) {
                    continue
                }
                val args = splitTopLevelJavaArguments(source.code.substring(openParen + 1, closeParen))
                if (args.size < 2 || !isBlockRegistryArgument(args[0])) continue
                val namespace = resolveModIdExpression(
                    args[1],
                    modIds,
                    source.code,
                    match.range.first,
                    source.packageName
                ) ?: continue
                blockRegistries[match.groupValues[1]] = namespace
            }
            if (blockRegistries.isEmpty()) continue

            for (match in registerPattern.findAll(source.code)) {
                val namespace = blockRegistries[match.groupValues[1]] ?: continue
                val executableSegment = source.executableCode.substring(match.range.first, match.range.last + 1)
                if (!executableSegment.contains(".register(") ||
                    (!executableSegment.contains("new") && !executableSegment.contains("::new"))) {
                    continue
                }
                val factoryType = match.groupValues[3].ifBlank { match.groupValues[4] }
                if (!javaTypeExtendsLightBlock(factoryType, source, sourceIndex)) continue
                blocks += RegisteredLightBlock(namespace, match.groupValues[2].trim('/'))
            }
        }
        return blocks
    }

    private fun javaTypeExtendsLightBlock(
        reference: String,
        context: JavaSourceInfo,
        index: JavaSourceIndex,
        visited: Set<String> = emptySet()
    ): Boolean {
        val clean = sanitizeJavaTypeReference(reference)
        if (isVanillaLightBlockReference(clean, context, index)) return true
        val source = resolveJavaTypeReference(clean, context, index) ?: return false
        if (source.fqName in visited) return false
        val classBlock = extractJavaClassBlock(source.executableCode, source.simpleName) ?: return false
        val superclass = directSuperclassReference(classBlock, source.simpleName) ?: return false
        return javaTypeExtendsLightBlock(superclass, source, index, visited + source.fqName)
    }

    private fun isVanillaLightBlockReference(
        reference: String,
        context: JavaSourceInfo,
        index: JavaSourceIndex
    ): Boolean {
        val clean = sanitizeJavaTypeReference(reference)
        if (clean == "net.minecraft.world.level.block.LightBlock") return true
        if (clean != "LightBlock") return false
        if (resolveJavaTypeReference(clean, context, index) != null) return false
        return context.imports[clean] == "net.minecraft.world.level.block.LightBlock" ||
            "net.minecraft.world.level.block" in context.wildcardImports
    }

    private fun addLightBlockStateLevelVariants(content: String): String? {
        val root = parseResourceJson(content) as? JsonObject ?: return null
        val variants = root["variants"] as? JsonObject ?: return null
        val entries = linkedMapOf<String, JsonElement>()
        var changed = false
        for ((key, value) in variants) {
            if (key.isBlank()) {
                changed = true
            } else {
                entries[key] = value
            }
        }
        for (level in 0..15) {
            val levelKey = "level=$level"
            if (entries[levelKey] == null) {
                entries[levelKey] = lightBlockVariant(level)
                changed = true
            }
        }
        if (!changed) return content

        val migrated = linkedMapOf<String, JsonElement>()
        for ((key, value) in root) {
            migrated[key] = if (key == "variants") JsonObject(entries) else value
        }
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), JsonObject(migrated)) + "\n"
    }

    private fun lightBlockStateJson(): String {
        val variants = linkedMapOf<String, JsonElement>()
        for (level in 0..15) {
            variants["level=$level"] = lightBlockVariant(level)
        }
        val root = JsonObject(mapOf("variants" to JsonObject(variants)))
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), root) + "\n"
    }

    private fun lightBlockVariant(level: Int): JsonObject =
        JsonObject(mapOf("model" to JsonPrimitive("minecraft:block/light_${level.toString().padStart(2, '0')}")))

    private data class RegisteredLightBlock(val namespace: String, val path: String)

    private fun migrateBannerPatternDataResources(
        dataDir: Path,
        changes: MutableList<Change>,
        errors: MutableList<String>,
        dryRun: Boolean
    ) {
        try {
            Files.walk(dataDir)
                .filter { Files.isRegularFile(it) && it.toString().replace("\\", "/").contains("/tags/banner_pattern/pattern_item/") }
                .filter { it.toString().endsWith(".json") }
                .forEach { tagFile ->
                    val root = parseResourceJson(tagFile.readText()) as? JsonObject ?: return@forEach
                    val values = root["values"] as? JsonArray ?: return@forEach
                    for (value in values) {
                        val id = (value as? JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.content
                            ?.takeUnless { it.startsWith("#") }
                            ?: continue
                        val namespace = id.substringBefore(':', missingDelimiterValue = "")
                        val path = id.substringAfter(':', missingDelimiterValue = "")
                        if (namespace.isBlank() || path.isBlank()) continue

                        val target = dataDir.resolve("$namespace/banner_pattern/$path.json")
                        if (target.exists()) continue

                        val content = bannerPatternJson(namespace, path)
                        changes.add(Change(
                            file = target,
                            line = 1,
                            description = "Create data-driven banner pattern '$id' referenced by pattern item tags",
                            before = "(missing)",
                            after = "data/$namespace/banner_pattern/$path.json",
                            confidence = Confidence.HIGH,
                            ruleId = "res-banner-pattern-data-resource"
                        ))
                        if (!dryRun) {
                            target.parent.createDirectories()
                            target.writeText(content)
                        }
                    }
                }
        } catch (e: Exception) {
            errors.add("Failed to migrate banner pattern data resources: ${e.message}")
        }
    }

    private fun bannerPatternJson(namespace: String, path: String): String = """
{
  "asset_id": "$namespace:$path",
  "translation_key": "block.minecraft.banner.$namespace.$path"
}
""".trimIndent() + "\n"

    private fun detectJavaModIds(javaSources: List<Pair<Path, String>>): Map<String, String> {
        val ids = mutableMapOf<String, String>()
        for ((_, content) in javaSources) {
            val code = maskJavaComments(content)
            val executableCode = maskJavaCommentsAndLiterals(content)
            val packageName = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
                .find(code)
                ?.groupValues
                ?.get(1)
                .orEmpty()
            Regex("static\\s+final\\s+String\\s+(MODID|MOD_ID)\\s*=\\s*\"([^\"]+)\"")
                .findAll(code)
                .filter { match ->
                    val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                    executableSegment.contains("static") &&
                        executableSegment.contains("final") &&
                        executableSegment.contains("String") &&
                        executableSegment.contains("=")
                }
                .forEach { match ->
                    val ownerClass = javaTypeNameContainingOffset(code, match.range.first)
                        ?: return@forEach
                    ids["$ownerClass.${match.groupValues[1]}"] = match.groupValues[2]
                    if (packageName.isNotBlank()) {
                        ids["$packageName.$ownerClass.${match.groupValues[1]}"] = match.groupValues[2]
                    }
                }
            val typeDeclarations = findJavaTypeDeclarations(code)
            Regex("""@Mod\s*\(\s*"([^"]+)"\s*\)""")
                .findAll(code)
                .forEach { match ->
                    val declaration = typeDeclarations.firstOrNull { declaration ->
                        declaration.kind == "class" &&
                            declaration.start > match.range.last &&
                            executableCode.substring(match.range.last + 1, declaration.start)
                                .none { it == ';' || it == '{' || it == '}' }
                    } ?: return@forEach
                    val executableSegment = executableCode.substring(match.range.first, declaration.openBrace + 1)
                    if (!executableSegment.contains("@Mod") ||
                        !executableSegment.contains("class")) {
                        return@forEach
                    }
                    ids[declaration.name] = match.groupValues[1]
                    if (packageName.isNotBlank()) {
                        ids["$packageName.${declaration.name}"] = match.groupValues[1]
                    }
                }
        }
        return ids
    }

    private data class JavaTypeDeclaration(
        val kind: String,
        val name: String,
        val start: Int,
        val openBrace: Int,
        val closeBrace: Int
    )

    private fun findJavaTypeDeclarations(source: String): List<JavaTypeDeclaration> {
        val executableSource = maskJavaCommentsAndLiterals(source)
        val declarations = mutableListOf<JavaTypeDeclaration>()
        val typePattern = Regex("""\b(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)\b""")
        for (match in typePattern.findAll(executableSource)) {
            val openBrace = executableSource.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingJavaBrace(executableSource, openBrace) else -1
            if (openBrace >= 0 && closeBrace > openBrace) {
                declarations += JavaTypeDeclaration(
                    kind = match.groupValues[1],
                    name = match.groupValues[2],
                    start = match.range.first,
                    openBrace = openBrace,
                    closeBrace = closeBrace
                )
            }
        }
        return declarations
    }

    private fun javaTypeNameContainingOffset(source: String, offset: Int): String? {
        val executableSource = maskJavaCommentsAndLiterals(source)
        var containingType: JavaTypeDeclaration? = null
        for (declaration in findJavaTypeDeclarations(source)) {
            if (offset in declaration.openBrace..declaration.closeBrace &&
                executableSource.substring(declaration.start, declaration.openBrace + 1).contains(declaration.kind)) {
                if (containingType == null || declaration.openBrace > containingType.openBrace) {
                    containingType = declaration
                }
            }
        }
        return containingType?.name
    }

    private fun resolveModIdExpression(
        expression: String,
        modIds: Map<String, String>,
        source: String,
        offset: Int,
        packageName: String
    ): String? {
        val trimmed = expression.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.trim('"')
        }
        if (trimmed.contains('.')) {
            return modIds[trimmed]
        }
        val owner = javaTypeNameContainingOffset(source, offset) ?: return null
        return modIds["$owner.$trimmed"]
            ?: packageName.takeIf { it.isNotBlank() }?.let { modIds["$it.$owner.$trimmed"] }
    }

    private data class CustomEnchantmentKey(val modId: String, val name: String)

    private fun migrateMandatoryFields(content: String): String {
        val output = mutableListOf<String>()
        val block = mutableListOf<String>()
        var blockIsDependency = false

        fun flushBlock() {
            if (block.isEmpty()) return
            if (!blockIsDependency) {
                output.addAll(block)
                block.clear()
                return
            }
            val hasType = block.any { Regex("""^\s*type\s*=""").containsMatchIn(it) }
            for (line in block) {
                val mandatory = Regex("""^(\s*)mandatory\s*=\s*(true|false).*$""").find(line)
                if (mandatory == null) {
                    output.add(line)
                    continue
                }

                if (!hasType) {
                    val value = if (mandatory.groupValues[2] == "true") "required" else "optional"
                    output.add("${mandatory.groupValues[1]}type=\"$value\"")
                }
            }
            block.clear()
        }

        for (line in content.lines()) {
            if (isTomlTableHeader(line)) {
                flushBlock()
                blockIsDependency = isTomlDependencyArrayHeader(line)
            }
            block.add(line)
        }
        flushBlock()

        return output.joinToString("\n")
    }

    /**
     * Update dependency metadata from fields in the same TOML dependency table.
     */
    private fun updateDependencyBlocks(content: String): String {
        val lines = content.lines().toMutableList()
        var inDependencyBlock = false
        var currentDepModId: String? = null

        for (i in lines.indices) {
            val line = lines[i]

            if (isTomlTableHeader(line)) {
                inDependencyBlock = isTomlDependencyArrayHeader(line)
                currentDepModId = null
            }
            if (!inDependencyBlock) continue

            // Detect dependency modId
            val modIdMatch = Regex("""modId\s*=\s*"([^"]+)"""").find(line)
            if (modIdMatch != null) {
                currentDepModId = if (modIdMatch.groupValues[1] == "forge") "neoforge" else modIdMatch.groupValues[1]
                if (modIdMatch.groupValues[1] == "forge") {
                    lines[i] = line.replace(
                        Regex("""modId\s*=\s*"forge""""),
                        """modId="neoforge""""
                    )
                }
            }

            // Update versionRange based on which dependency we're in
            // Skip lines with template variables (${...}) — they use Groovy expansion
            if (currentDepModId != null && line.contains("versionRange") && !line.contains("\${")) {
                when (currentDepModId) {
                    "neoforge" -> {
                        lines[i] = line.replace(
                            Regex("""versionRange\s*=\s*"[^"]*""""),
                            """versionRange="[21.1,)""""
                        )
                    }
                    "minecraft" -> {
                        lines[i] = line.replace(
                            Regex("""versionRange\s*=\s*"\[1\.20[^"]*""""),
                            """versionRange="[1.21.1,1.22)""""
                        )
                    }
                    "mysterious_mountain_lib" -> {
                        lines[i] = line.replace(
                            Regex("""versionRange\s*=\s*"[^"]*""""),
                            """versionRange="[1.0.0,)""""
                        )
                    }
                    "terrablender" -> {
                        lines[i] = line.replace(
                            Regex("""versionRange\s*=\s*"[^"]*""""),
                            """versionRange="[4.0.0,)""""
                        )
                    }
                }
            }
        }

        return lines.joinToString("\n")
    }

    private fun isTomlTableHeader(line: String): Boolean {
        return Regex("""^\s*(?:\[\s*[^]\r\n#]+\]|\[\[\s*[^]\r\n#]+\]\])\s*(?:#.*)?$""").matches(line)
    }

    private fun isTomlDependencyArrayHeader(line: String): Boolean =
        Regex("""^\s*\[\[\s*dependencies\.[^]\r\n#]+\]\]\s*(?:#.*)?$""").matches(line)

    /**
     * Split data/forge/ into data/c/ (tags) and data/neoforge/ (everything else).
     * In NeoForge 1.21.1, forge: tags moved to the unified c: (common) namespace,
     * while non-tag data (loot modifiers, biome modifiers) uses neoforge: namespace.
     */
    private fun migrateForgeDataDir(forgeDataDir: Path, resourceDir: Path, changes: MutableList<Change>, errors: MutableList<String>) {
        val tagsDir = forgeDataDir.resolve("tags")
        val cDataDir = resourceDir.resolve("data/c")
        val neoforgeDataDir = resourceDir.resolve("data/neoforge")

        try {
            // Move tags/ -> data/c/tags/
            if (tagsDir.exists()) {
                cDataDir.resolve("tags").createDirectories()
                Files.walk(tagsDir).filter { Files.isRegularFile(it) }.forEach { file ->
                    val relative = tagsDir.relativize(file)
                    val target = cDataDir.resolve("tags").resolve(relative)
                    target.parent.createDirectories()
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
                }
                changes.add(Change(
                    file = tagsDir, line = 0,
                    description = "Migrate data/forge/tags/ -> data/c/tags/ (common tag namespace)",
                    before = "data/forge/tags/", after = "data/c/tags/",
                    confidence = Confidence.HIGH, ruleId = "res-forge-to-c-tags"
                ))
            }

            // Move remaining non-tag content -> data/neoforge/
            val remaining = Files.walk(forgeDataDir)
                .filter { Files.isRegularFile(it) }
                .toList()
            if (remaining.isNotEmpty()) {
                neoforgeDataDir.createDirectories()
                for (file in remaining) {
                    val relative = forgeDataDir.relativize(file)
                    val target = neoforgeDataDir.resolve(relative)
                    target.parent.createDirectories()
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
                }
                changes.add(Change(
                    file = forgeDataDir, line = 0,
                    description = "Migrate data/forge/ (non-tags) -> data/neoforge/",
                    before = "data/forge/", after = "data/neoforge/",
                    confidence = Confidence.HIGH, ruleId = "res-forge-to-neoforge"
                ))
            }

            // Clean up empty forge directory
            Files.walk(forgeDataDir)
                .sorted(Comparator.reverseOrder())
                .filter { Files.isDirectory(it) }
                .forEach { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
        } catch (e: Exception) {
            errors.add("Failed to migrate data/forge: ${e.message}")
        }
    }

    /**
     * Transform JSON data files for NeoForge 1.21.1 compatibility:
     * - Recipe results: "item" key → "id" key
     * - Smelting results: plain string → {"id": "..."} object
     * - Forge conditions: "conditions" → "neoforge:conditions"
     * - Condition types: "forge:" prefix → "neoforge:" prefix
     */
    internal fun transformDataJsonFiles(
        dataDir: Path,
        projectDir: Path,
        changes: MutableList<Change>,
        errors: MutableList<String>
    ) {
        val codeAwardedAdvancements = detectCodeAwardedAdvancements(projectDir)
        val recipeCodecHints = collectRecipeDataCodecHints(projectDir)
        Files.walk(dataDir)
            .filter { it.toString().endsWith(".json") && Files.isRegularFile(it) }
            .forEach { file ->
                try {
                    var content = file.readText()
                    var modified = false
                    val filePath = file.toString().replace("\\", "/")
                    val isRecipeFile = filePath.contains("/recipe/")
                    val isLootTableFile = filePath.contains("/loot_table/") || filePath.contains("/loot_tables/")
                    val isLootModifierFile = filePath.contains("/loot_modifiers/") &&
                        !filePath.endsWith("/global_loot_modifiers.json")
                    val isAdvancementFile = filePath.contains("/advancement/")
                    val isWorldgenFile = filePath.contains("/worldgen/")
                    val isDimensionTypeFile = filePath.contains("/dimension_type/")

                    // Recipe result: "item" → "id" in result objects
                    // Match "result": {"item": "..." pattern and change to "result": {"id": "..."
                    // Forge recipe/loot conditions → NeoForge conditions
                    // Only rename "conditions" in non-advancement files (advancements use "conditions" for triggers)
                    if (isRecipeFile && content.contains("\"result\"")) {
                        val newContent = migrateRecipeResultEntries(content, recipeCodecHints)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Minecraft recipe result entries: \"item\" -> \"id\"",
                                before = "\"result\": {\"item\": \"mod:item\"}",
                                after = "\"result\": {\"id\": \"mod:item\"}",
                                confidence = Confidence.HIGH,
                                ruleId = "res-recipe-result-entry-id"
                            ))
                        }
                    }

                    if (isRecipeFile && recipeCodecHints.hasCompoundTagFields && content.contains("\"tag\"")) {
                        val newContent = migrateRecipeCompoundTagFields(content, recipeCodecHints)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Recipe CompoundTag codec field: legacy SNBT string -> JSON object",
                                before = "\"tag\": \"{Foo:1b}\"",
                                after = "\"tag\": {\"Foo\": 1}",
                                confidence = Confidence.HIGH,
                                ruleId = "res-recipe-snbt-compound-tag"
                            ))
                        }
                    }

                    if (isLootTableFile && content.contains("\"neoforge:conditions\"")) {
                        val newContent = content.replace("\"neoforge:conditions\"", "\"conditions\"")
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Loot table: restore vanilla \"conditions\" key",
                                before = "\"neoforge:conditions\"", after = "\"conditions\"",
                                confidence = Confidence.HIGH,
                                ruleId = "res-loot-table-conditions-key"
                            ))
                        }
                    }

                    if (isLootTableFile &&
                        content.contains("\"minecraft:loot_table\"") &&
                        content.contains("\"name\"")
                    ) {
                        val newContent = migrateLootTableEntryNames(content)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Loot table entry: \"name\" -> \"value\" for nested loot table references",
                                before = "\"type\": \"minecraft:loot_table\", \"name\": \"mod:table\"",
                                after = "\"type\": \"minecraft:loot_table\", \"value\": \"mod:table\"",
                                confidence = Confidence.HIGH,
                                ruleId = "res-loot-table-entry-name-to-value"
                            ))
                        }
                    }

                    if (isLootTableFile && content.contains("\"minecraft:random_chance_with_looting\"")) {
                        val newContent = migrateRandomChanceWithLootingConditions(content)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Loot condition: random_chance_with_looting -> random_chance_with_enchanted_bonus",
                                before = "\"condition\": \"minecraft:random_chance_with_looting\"",
                                after = "\"condition\": \"minecraft:random_chance_with_enchanted_bonus\"",
                                confidence = Confidence.HIGH,
                                ruleId = "res-loot-random-chance-with-looting-121"
                            ))
                        }
                    }

                    if (!isAdvancementFile && !isLootTableFile && !isLootModifierFile && content.contains("\"conditions\"")) {
                        val newContent = content
                            .replace("\"conditions\"", "\"neoforge:conditions\"")
                            .replace("\"forge:", "\"neoforge:")
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Forge conditions -> NeoForge conditions",
                                before = "\"conditions\"", after = "\"neoforge:conditions\"",
                                confidence = Confidence.HIGH,
                                ruleId = "res-conditions-namespace"
                            ))
                        }
                    }

                    // Advancement trigger: "neoforge:conditions" → "conditions", "tag" → "items" with # prefix
                    if (isLootModifierFile &&
                        (content.contains("\"neoforge:conditions\"") || content.contains("\"condition\""))
                    ) {
                        val newContent = migrateGlobalLootModifierJson(content)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Global loot modifier conditions: NeoForge wrapper -> loot condition codec",
                                before = "\"neoforge:conditions\": [{\"condition\": \"...\"}]",
                                after = "\"conditions\": [{\"type\": \"...\"}]",
                                confidence = Confidence.HIGH,
                                ruleId = "res-glm-loot-conditions-121"
                            ))
                        }
                    }

                    if (isAdvancementFile) {
                        // Fix over-renamed trigger conditions while preserving top-level NeoForge load conditions.
                        if (content.contains("\"neoforge:conditions\"")) {
                            val newContent = migrateAdvancementConditionKeys(content)
                            if (newContent != content) {
                                content = newContent
                                modified = true
                                changes.add(Change(
                                    file = file, line = 0,
                                    description = "Advancement trigger: restore nested \"conditions\" key",
                                    before = "\"neoforge:conditions\"", after = "\"conditions\"",
                                    confidence = Confidence.HIGH,
                                    ruleId = "res-advancement-conditions-fix"
                                ))
                            }
                        }
                        // Tag syntax in item predicates: "tag": "xxx" → "items": "#xxx"
                        if (content.contains("\"advancements\"") && content.contains("\"advancement\"")) {
                            val newContent = unwrapSingleConditionalAdvancement(content)
                            if (newContent != content) {
                                content = newContent
                                modified = true
                                changes.add(Change(
                                    file = file, line = 0,
                                    description = "Advancement: unwrap single conditional advancement",
                                    before = "\"advancements\": [{\"advancement\": {...}, \"conditions\": [...]}]",
                                    after = "advancement with top-level \"neoforge:conditions\"",
                                    confidence = Confidence.HIGH,
                                    ruleId = "res-advancement-unwrap-single-conditional"
                                ))
                            }
                        }
                        val tagPattern = Regex(""""tag"\s*:\s*"([^"]+)"""")
                        if (tagPattern.containsMatchIn(content)) {
                            val newContent = tagPattern.replace(content) { match ->
                                """"items": "#${match.groupValues[1]}""""
                            }
                            if (newContent != content) {
                                content = newContent
                                modified = true
                                changes.add(Change(
                                    file = file, line = 0,
                                    description = "Advancement item predicate: \"tag\" -> \"items\" with # prefix",
                                    before = "\"tag\": \"...\"", after = "\"items\": \"#...\"",
                                    confidence = Confidence.HIGH,
                                    ruleId = "res-advancement-tag-to-items"
                                ))
                            }
                        }
                        val iconItemPattern = Regex("""("icon"\s*:\s*\{[^}]*)"item"(\s*:)""")
                        if (iconItemPattern.containsMatchIn(content)) {
                            val newContent = iconItemPattern.replace(content, """$1"id"$2""")
                            if (newContent != content) {
                                content = newContent
                                modified = true
                                changes.add(Change(
                                    file = file, line = 0,
                                    description = "Advancement display icon: \"item\" -> \"id\"",
                                    before = "\"icon\": {\"item\": \"...\"}",
                                    after = "\"icon\": {\"id\": \"...\"}",
                                    confidence = Confidence.HIGH,
                                    ruleId = "res-advancement-icon-id"
                                ))
                            }
                        }
                        val topLevelIdPattern = Regex("""^\s*\{\s*"id"\s*:\s*"[^"]+"\s*,\s*""")
                        if (topLevelIdPattern.containsMatchIn(content)) {
                            val newContent = topLevelIdPattern.replace(content, "{\n  ")
                            if (newContent != content) {
                                content = newContent
                                modified = true
                                changes.add(Change(
                                    file = file, line = 0,
                                    description = "Remove legacy top-level advancement id field",
                                    before = "\"id\": \"mod:advancement\"",
                                    after = "(advancement id comes from file path)",
                                    confidence = Confidence.HIGH,
                                    ruleId = "res-advancement-remove-top-level-id"
                                ))
                            }
                        }
                        val advancementId = advancementIdFromPath(dataDir, file)
                        val criteria = codeAwardedAdvancements[advancementId].orEmpty()
                        if (criteria.isNotEmpty()) {
                            val triggerPattern = Regex(""""trigger"\s*:\s*"${Regex.escape(advancementId)}"""")
                            val hasAwardedCriterion = criteria.any { criterion ->
                                Regex(""""${Regex.escape(criterion)}"\s*:""").containsMatchIn(content)
                            }
                            if (hasAwardedCriterion && triggerPattern.containsMatchIn(content)) {
                                val newContent = triggerPattern.replace(content, """"trigger": "minecraft:impossible"""")
                                if (newContent != content) {
                                    content = newContent
                                    modified = true
                                    changes.add(Change(
                                        file = file, line = 0,
                                        description = "Code-awarded advancement trigger -> minecraft:impossible",
                                        before = "\"trigger\": \"$advancementId\"",
                                        after = "\"trigger\": \"minecraft:impossible\"",
                                        confidence = Confidence.HIGH,
                                        ruleId = "res-advancement-code-awarded-trigger"
                                    ))
                                }
                            }
                        }
                    }

                    if (isRecipeFile) {
                        val stainedGlassContent = migrateLegacyStainedGlassRecipeIngredients(content)
                        if (stainedGlassContent != content) {
                            content = stainedGlassContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Preserve stained glass washing ingredients as native tag intersections",
                                before = "Legacy Forge stained glass item tags",
                                after = "Native glass kind and dyed tag intersection",
                                confidence = Confidence.HIGH,
                                ruleId = "res-create-stained-glass-intersection"
                            ))
                        }
                    }

                    // forge: namespace in condition types (without touching conditions key)
                    if (content.contains("\"forge:")) {
                        val newContent = content.replace("\"forge:", "\"neoforge:")
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "forge: namespace -> neoforge: in JSON",
                                before = "\"forge:", after = "\"neoforge:",
                                confidence = Confidence.HIGH,
                                ruleId = "res-forge-namespace-json"
                            ))
                        }
                    }

                    val commonTagContent = normalizeCommonTagNamespaces(content)
                    if (commonTagContent != content) {
                        content = commonTagContent
                        modified = true
                        changes.add(Change(
                            file = file, line = 0,
                            description = "Common item tag namespace: forge/neoforge -> c",
                            before = "forge:ingots or neoforge:ingots",
                            after = "c:ingots",
                            confidence = Confidence.HIGH,
                            ruleId = "res-common-tag-namespace"
                        ))
                    }

                    val tagReferenceContent = normalizeTagReferenceNamespaces(content)
                    if (tagReferenceContent != content) {
                        content = tagReferenceContent
                        modified = true
                        changes.add(Change(
                            file = file, line = 0,
                            description = "Tag references: forge/neoforge -> c namespace",
                            before = "forge:tag or neoforge:tag",
                            after = "c:tag",
                            confidence = Confidence.HIGH,
                            ruleId = "res-tag-reference-c-namespace"
                        ))
                    }

                    val resourceIdContent = renameLegacyResourceIds(content)
                    if (resourceIdContent != content) {
                        content = resourceIdContent
                        modified = true
                        changes.add(Change(
                            file = file, line = 0,
                            description = "Legacy vanilla resource ids -> 1.21.1 ids",
                            before = "minecraft:grass",
                            after = "minecraft:short_grass",
                            confidence = Confidence.HIGH,
                            ruleId = "res-legacy-resource-id-renames-121"
                        ))
                    }

                    if (isRecipeFile && (
                        content.contains("partial_nbt") ||
                            content.contains("\"forge:nbt\"") ||
                            content.contains("\"neoforge:nbt\"")
                    )) {
                        val newContent = migratePartialNbtIngredients(content)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Recipe ingredient: legacy NBT -> NeoForge data component ingredient",
                                before = "\"type\": \"forge:partial_nbt\" or \"forge:nbt\"",
                                after = "\"type\": \"neoforge:components\"",
                                confidence = Confidence.HIGH,
                                ruleId = "res-recipe-partial-nbt-component-ingredient"
                            ))
                        }
                    }

                    if (isRecipeFile && content.contains(":uncrafting\"")) {
                        val newContent = migrateUncraftingRecipeInputWrappers(content)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Uncrafting recipe: legacy input wrapper -> 1.21 input_count field",
                                before = "\"input\": {\"ingredient\": {\"item\": \"...\"}}",
                                after = "\"input\": {\"item\": \"...\"}, \"input_count\": ...",
                                confidence = Confidence.HIGH,
                                ruleId = "res-uncrafting-recipe-input-count-121"
                            ))
                        }
                    }

                    if (isRecipeFile && content.contains("\"neoforge:conditional\"")) {
                        val newContent = unwrapSingleConditionalRecipe(content)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Recipe: unwrap single neoforge conditional recipe",
                                before = "\"type\": \"neoforge:conditional\"",
                                after = "inner recipe with top-level \"neoforge:conditions\"",
                                confidence = Confidence.HIGH,
                                ruleId = "res-recipe-unwrap-single-conditional"
                            ))
                        }
                    }

                    // Loot function renames: set_nbt -> set_custom_data, copy_nbt -> copy_custom_data
                    if (content.contains("set_nbt") || content.contains("copy_nbt")) {
                        val newContent = content
                            .replace("\"minecraft:set_nbt\"", "\"minecraft:set_custom_data\"")
                            .replace("\"minecraft:copy_nbt\"", "\"minecraft:copy_custom_data\"")
                            .replace("\"set_nbt\"", "\"set_custom_data\"")
                            .replace("\"copy_nbt\"", "\"copy_custom_data\"")
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Loot function: set_nbt/copy_nbt -> set_custom_data/copy_custom_data",
                                before = "set_nbt/copy_nbt", after = "set_custom_data/copy_custom_data",
                                confidence = Confidence.HIGH,
                                ruleId = "res-loot-nbt-rename"
                            ))
                        }
                    }

                    if (isLootTableFile && content.contains("\"minecraft:looting_enchant\"")) {
                        val newContent = migrateLootTableFunctionNames(content)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Loot function: looting_enchant -> enchanted_count_increase",
                                before = "\"function\": \"minecraft:looting_enchant\"",
                                after = "\"function\": \"minecraft:enchanted_count_increase\", \"enchantment\": \"minecraft:looting\"",
                                confidence = Confidence.HIGH,
                                ruleId = "res-loot-looting-enchant-function"
                            ))
                        }
                    }

                    if ((isWorldgenFile || isDimensionTypeFile) &&
                        content.contains("\"value\"") &&
                        (content.contains("\"min_inclusive\"") || content.contains("\"max_inclusive\""))
                    ) {
                        val newContent = flattenWorldgenProviderValueObjects(content)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Worldgen provider: flatten legacy value min/max object",
                                before = "\"value\": {\"min_inclusive\": ..., \"max_inclusive\": ...}",
                                after = "\"min_inclusive\": ..., \"max_inclusive\": ...",
                                confidence = Confidence.HIGH,
                                ruleId = "res-worldgen-provider-value-flatten"
                            ))
                        }
                    }

                    if (isWorldgenFile && content.contains(":no_structure\"")) {
                        val newContent = flattenNoStructurePlacementModifiers(content)
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "no_structure placement modifier: flatten legacy value object",
                                before = "\"type\": \"<namespace>:no_structure\", \"value\": {...}",
                                after = "\"type\": \"<namespace>:no_structure\", \"additional_clearance\": ...",
                                confidence = Confidence.HIGH,
                                ruleId = "res-no-structure-placement-flatten"
                            ))
                        }
                    }

                    if (modified) {
                        file.writeText(content)
                    }
                } catch (e: Exception) {
                    errors.add("Failed to transform ${file.fileName}: ${e.message}")
                }
            }
    }

    private fun transformDataFunctionFiles(
        dataDir: Path,
        changes: MutableList<Change>,
        errors: MutableList<String>
    ) {
        Files.walk(dataDir)
            .filter { it.toString().endsWith(".mcfunction") && Files.isRegularFile(it) }
            .forEach { file ->
                try {
                    val original = file.readText()
                    var content = original
                    val appliedRules = linkedSetOf<String>()

                    val tagContent = normalizeMcfunctionTagReferences(content)
                    if (tagContent != content) {
                        content = tagContent
                        appliedRules += "res-mcfunction-common-tag-reference"
                    }

                    val itemStackContent = migrateMcfunctionItemStackNbt(content)
                    if (itemStackContent != content) {
                        content = itemStackContent
                        appliedRules += "res-mcfunction-itemstack-components"
                    }

                    if (content != original) {
                        file.writeText(content)
                        if ("res-mcfunction-common-tag-reference" in appliedRules) {
                            changes.add(Change(
                                file = file,
                                line = 0,
                                description = "Command tag references: forge/neoforge/common singular paths -> c namespace 1.21 paths",
                                before = "#forge:stone or #c:string",
                                after = "#c:stones or #c:strings",
                                confidence = Confidence.HIGH,
                                ruleId = "res-mcfunction-common-tag-reference"
                            ))
                        }
                        if ("res-mcfunction-itemstack-components" in appliedRules) {
                            changes.add(Change(
                                file = file,
                                line = 0,
                                description = "Command item stack NBT -> 1.21 data component syntax",
                                before = "mod:item{Unbreakable:1}",
                                after = "mod:item[minecraft:unbreakable={}]",
                                confidence = Confidence.HIGH,
                                ruleId = "res-mcfunction-itemstack-components"
                            ))
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Failed to transform function ${file.fileName}: ${e.message}")
                }
            }
    }

    private fun normalizeMcfunctionTagReferences(content: String): String =
        Regex("""#(?:forge|neoforge|c):([A-Za-z0-9_./-]+)""").replace(content) { match ->
            "#c:${normalizeCommonTagPath(match.groupValues[1])}"
        }

    private fun migrateMcfunctionItemStackNbt(content: String): String {
        val result = StringBuilder()
        var cursor = 0
        val idPattern = Regex("""[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+""")
        while (cursor < content.length) {
            val match = idPattern.find(content, cursor) ?: break
            val idStart = match.range.first
            val idEnd = match.range.last + 1
            if (idStart > cursor) {
                result.append(content, cursor, idStart)
            }

            val previous = content.getOrNull(idStart - 1)
            val next = content.getOrNull(idEnd)
            if (previous != null && isResourceLocationCommandChar(previous) || next != '{') {
                result.append(match.value)
                cursor = idEnd
                continue
            }

            val closeBrace = findMatchingSnbtBrace(content, idEnd)
            if (closeBrace < 0) {
                result.append(match.value)
                cursor = idEnd
                continue
            }

            val nbt = content.substring(idEnd, closeBrace + 1)
            val components = legacyItemNbtToCommandComponents(nbt)
            if (components == null) {
                result.append(match.value)
                result.append(nbt)
            } else {
                result.append(match.value)
                result.append(components)
            }
            cursor = closeBrace + 1
        }
        if (cursor < content.length) {
            result.append(content, cursor, content.length)
        }
        return result.toString()
    }

    private fun isResourceLocationCommandChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '-' || char == '.' || char == '/' || char == ':'

    private fun findMatchingSnbtBrace(source: String, openBrace: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in openBrace until source.length) {
            val char = source[index]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun legacyItemNbtToCommandComponents(nbt: String): String? {
        val trimmed = nbt.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val body = trimmed.substring(1, trimmed.length - 1).trim()
        if (body.isEmpty()) return null

        val components = mutableListOf<String>()
        val customData = mutableListOf<String>()
        for (entry in splitTopLevelSnbtEntries(body)) {
            val colon = findTopLevelSnbtColon(entry)
            if (colon <= 0) return null
            val key = unquoteSnbtString(entry.substring(0, colon).trim())
            val rawValue = entry.substring(colon + 1).trim()
            when (key) {
                "Unbreakable" -> {
                    if (isTruthySnbtValue(rawValue)) {
                        components += "minecraft:unbreakable={}"
                    }
                }
                "Damage" -> {
                    val damage = parseSnbtInt(rawValue) ?: return null
                    components += "minecraft:damage=$damage"
                }
                else -> customData += entry
            }
        }
        if (customData.isNotEmpty()) {
            components += "minecraft:custom_data={${customData.joinToString(",")}}"
        }
        if (components.isEmpty()) return "[]"
        return components.joinToString(prefix = "[", postfix = "]")
    }

    private fun isTruthySnbtValue(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized == "true") return true
        if (normalized == "false") return false
        return parseSnbtInt(normalized)?.let { it != 0 } ?: false
    }

    private fun collectRecipeDataCodecHints(projectDir: Path): RecipeDataCodecHints {
        val javaSources = collectJavaSourceInfos(projectDir)
        val sourceHints = if (javaSources.isEmpty()) {
            RecipeDataCodecHints.EMPTY
        } else {
            val index = JavaSourceIndex(javaSources)
            val stringConstants = collectJavaStringConstants(javaSources.map { it.content })
            val registryNamespaces = collectRecipeSerializerRegistryNamespaces(javaSources, stringConstants)
            if (registryNamespaces.isEmpty()) {
                RecipeDataCodecHints.EMPTY
            } else {
                val itemStackFields = linkedMapOf<String, MutableSet<String>>()
                val itemStackListEntryFields = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
                val compoundTagFields = linkedMapOf<String, MutableSet<String>>()
                val registerPattern = Regex(
                    """\b([A-Za-z_$][\w$]*)\.register\(\s*"([^"]+)"\s*,\s*(?:(?:\(\)\s*->\s*new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?))|([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)::new)"""
                )

                for (source in javaSources) {
                    if (!source.executableCode.contains(".register(")) continue
                    registerPattern.findAll(source.code)
                        .filter { match ->
                            val executableSegment = source.executableCode.substring(match.range.first, match.range.last + 1)
                            executableSegment.contains(".register(") &&
                                (executableSegment.contains("new") || executableSegment.contains("::new"))
                        }
                        .forEach { match ->
                            val namespace = registryNamespaces[match.groupValues[1]] ?: return@forEach
                            val serializerId = match.groupValues[2]
                            val factoryReference = match.groupValues[3].ifBlank { match.groupValues[4] }
                            val fields = recipeCodecFieldsForFactory(factoryReference, source, index)
                            if (fields.isEmpty) return@forEach

                            val typeId = "$namespace:$serializerId"
                            if (fields.itemStackFields.isNotEmpty()) {
                                itemStackFields.getOrPut(typeId) { linkedSetOf() }.addAll(fields.itemStackFields)
                            }
                            fields.itemStackFieldsByListField.forEach { (listField, entryFields) ->
                                itemStackListEntryFields
                                    .getOrPut(typeId) { linkedMapOf() }
                                    .getOrPut(listField) { linkedSetOf() }
                                    .addAll(entryFields)
                            }
                            if (fields.compoundTagFields.isNotEmpty()) {
                                compoundTagFields.getOrPut(typeId) { linkedSetOf() }.addAll(fields.compoundTagFields)
                            }
                        }
                }

                RecipeDataCodecHints(
                    itemStackFieldsByType = itemStackFields.mapValues { it.value.toSet() },
                    itemStackListEntryFieldsByType = itemStackListEntryFields.mapValues { (_, fieldsByList) ->
                        fieldsByList.mapValues { it.value.toSet() }
                    },
                    compoundTagFieldsByType = compoundTagFields.mapValues { it.value.toSet() }
                )
            }
        }
        val dependencyHints = collectDependencyRecipeDataShapeHints(projectDir)
        return sourceHints + dependencyHints
    }

    private fun collectDependencyRecipeDataShapeHints(projectDir: Path): RecipeDataCodecHints {
        val itemStackFields = linkedMapOf<String, MutableSet<String>>()
        val itemStackListEntryFields = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()

        for (jar in collectGradleDependencyJars(projectDir).distinct()) {
            try {
                ZipFile(jar.toFile()).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name.replace('\\', '/')
                        if (entry.isDirectory || !name.endsWith(".json")) continue
                        if (!name.startsWith("data/") && !name.contains("/data/")) continue
                        if (!name.contains("/recipe/") && !name.contains("/recipes/")) continue
                        val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                        val root = parseResourceJson(content) as? JsonObject ?: continue
                        collectRecipeShapeHints(root, itemStackFields, itemStackListEntryFields)
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }

        if (itemStackFields.isEmpty() && itemStackListEntryFields.isEmpty()) return RecipeDataCodecHints.EMPTY
        return RecipeDataCodecHints(
            itemStackFieldsByType = itemStackFields.mapValues { it.value.toSet() },
            itemStackListEntryFieldsByType = itemStackListEntryFields.mapValues { (_, fieldsByList) ->
                fieldsByList.mapValues { it.value.toSet() }
            }
        )
    }

    private fun collectRecipeShapeHints(
        element: JsonElement,
        itemStackFields: MutableMap<String, MutableSet<String>>,
        itemStackListEntryFields: MutableMap<String, MutableMap<String, MutableSet<String>>>
    ) {
        when (element) {
            is JsonArray -> element.forEach { collectRecipeShapeHints(it, itemStackFields, itemStackListEntryFields) }
            is JsonObject -> {
                val recipeType = (element["type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                if (recipeType != null) {
                    for ((key, value) in element) {
                        when (value) {
                            is JsonObject -> {
                                if (isTargetItemStackObject(value)) {
                                    itemStackFields.getOrPut(recipeType) { linkedSetOf() }.add(key)
                                }
                            }
                            is JsonArray -> {
                                val entryFields = targetItemStackEntryFields(value)
                                if (entryFields.isNotEmpty()) {
                                    itemStackListEntryFields
                                        .getOrPut(recipeType) { linkedMapOf() }
                                        .getOrPut(key) { linkedSetOf() }
                                        .addAll(entryFields)
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                element.values.forEach { collectRecipeShapeHints(it, itemStackFields, itemStackListEntryFields) }
            }
            else -> Unit
        }
    }

    private fun targetItemStackEntryFields(entries: JsonArray): Set<String> {
        val fields = linkedSetOf<String>()
        for (entry in entries) {
            val entryObject = entry as? JsonObject ?: continue
            for ((key, value) in entryObject) {
                if (value is JsonObject && isTargetItemStackObject(value)) {
                    fields += key
                }
            }
        }
        return fields
    }

    private fun isTargetItemStackObject(element: JsonObject): Boolean {
        val id = element["id"] as? JsonPrimitive ?: return false
        return id.isString
    }

    private fun collectGradleDependencyJars(projectDir: Path): List<Path> {
        val buildFiles = listOf("build.gradle", "build.gradle.kts")
            .map { projectDir.resolve(it) }
            .filter { it.exists() }
        if (buildFiles.isEmpty()) return emptyList()

        val jars = linkedSetOf<Path>()
        for (file in buildFiles) {
            val content = file.readText()
            collectGradleFileDependencies(projectDir, content).forEach { jars.add(it) }
            collectGradleCoordinateDependencies(content).forEach { coordinate ->
                jars.addAll(gradleCacheJarsForCoordinate(coordinate))
            }
        }
        return jars.toList()
    }

    private fun collectGradleFileDependencies(projectDir: Path, content: String): List<Path> {
        val jars = mutableListOf<Path>()
        val pattern = Regex("""files\s*\(\s*['"]([^'"]+\.jar)['"]\s*\)""")
        for (match in pattern.findAll(content)) {
            val pathText = match.groupValues[1]
            val path = Paths.get(pathText)
            val resolved = if (path.isAbsolute) path else projectDir.resolve(pathText)
            if (resolved.exists() && Files.isRegularFile(resolved)) {
                jars.add(resolved)
            }
        }
        return jars
    }

    private fun collectGradleCoordinateDependencies(content: String): List<String> {
        val coordinates = linkedSetOf<String>()
        val quotePattern = Regex("""['"]([A-Za-z0-9_.-]+(?:\.[A-Za-z0-9_.-]+)*:[A-Za-z0-9_.-]+:[^'"\s)]+)['"]""")
        for (match in quotePattern.findAll(content)) {
            coordinates += match.groupValues[1].substringBeforeLast('@')
        }
        return coordinates.toList()
    }

    private fun gradleCacheJarsForCoordinate(coordinate: String): List<Path> {
        val parts = coordinate.split(':')
        if (parts.size < 3) return emptyList()
        val group = parts[0]
        val artifact = parts[1]
        val version = parts[2].substringBefore(':')
        val gradleHome = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".gradle")
        val moduleDir = gradleHome.resolve("caches/modules-2/files-2.1/$group/$artifact/$version")
        if (!moduleDir.exists()) return emptyList()
        val expectedName = "$artifact-$version.jar"
        return Files.walk(moduleDir).use { files ->
            files
                .filter { Files.isRegularFile(it) && it.fileName.toString() == expectedName }
                .toList()
        }
    }

    private fun collectRecipeSerializerRegistryNamespaces(
        sources: List<JavaSourceInfo>,
        stringConstants: Map<String, String>
    ): Map<String, String> {
        val registries = linkedMapOf<String, String>()
        val createPattern = Regex(
            """(?s)\b([A-Za-z_$][\w$]*)\s*=\s*DeferredRegister\.create\(\s*[^,]+,\s*([^)]+?)\s*\)\s*;"""
        )
        for (source in sources) {
            if (!source.executableCode.contains("DeferredRegister") || !source.executableCode.contains("RecipeSerializer")) continue
            for (match in createPattern.findAll(source.code)) {
                val executableSegment = source.executableCode.substring(match.range.first, match.range.last + 1)
                if (!executableSegment.contains("DeferredRegister") || !executableSegment.contains("create")) {
                    continue
                }
                val namespace = resolveJavaStringExpression(
                    match.groupValues[2].trim(),
                    stringConstants,
                    source.code,
                    match.range.first,
                    source.packageName
                ) ?: continue
                registries[match.groupValues[1]] = namespace
            }
        }
        return registries
    }

    private fun recipeCodecFieldsForFactory(
        factoryReference: String,
        context: JavaSourceInfo,
        index: JavaSourceIndex
    ): RecipeCodecFieldSet {
        val source = resolveJavaTypeReference(factoryReference, context, index) ?: return RecipeCodecFieldSet.EMPTY
        val nestedClass = nestedClassName(factoryReference, source)
        return recipeCodecFieldsForType(source, nestedClass, index, visited = linkedSetOf())
    }

    private fun recipeCodecFieldsForType(
        source: JavaSourceInfo,
        nestedClass: String?,
        index: JavaSourceIndex,
        visited: MutableSet<String>
    ): RecipeCodecFieldSet {
        val key = "${source.fqName}#${nestedClass.orEmpty()}"
        if (!visited.add(key)) return RecipeCodecFieldSet.EMPTY

        val className = nestedClass ?: source.simpleName
        val classRange = javaClassBlockRange(source.executableCode, className)
        val classBlock = classRange?.let { source.code.substring(it) } ?: source.code
        val executableClassBlock = classRange?.let { source.executableCode.substring(it) } ?: source.executableCode
        var fields = recipeCodecFieldsInBlock(classBlock, executableClassBlock)
        fields += recipeListEntryCodecFieldsInBlock(classBlock, executableClassBlock, source, index, visited)

        val superclass = directSuperclassReference(executableClassBlock, className)
        if (superclass != null) {
            val parent = resolveJavaTypeReference(superclass, source, index)
            if (parent != null) {
                fields += recipeCodecFieldsForType(parent, nestedClass = null, index, visited)
            }
        }
        return fields
    }

    private fun recipeCodecFieldsInBlock(block: String, executableBlock: String): RecipeCodecFieldSet {
        val itemStackFields = Regex(
            """\bItemStack\s*\.\s*CODEC\s*\.\s*(?:optionalFieldOf|fieldOf)\s*\(\s*"([^"]+)""""
        )
            .findAll(block)
            .filter { match ->
                val executableSegment = executableBlock.substring(match.range.first, match.range.last + 1)
                executableSegment.contains("ItemStack") &&
                    executableSegment.contains("CODEC") &&
                    (executableSegment.contains("fieldOf") || executableSegment.contains("optionalFieldOf"))
            }
            .map { it.groupValues[1] }
            .toSet()
        val compoundTagFields = Regex(
            """\bCompoundTag\s*\.\s*CODEC\s*\.\s*(?:optionalFieldOf|fieldOf)\s*\(\s*"([^"]+)""""
        )
            .findAll(block)
            .filter { match ->
                val executableSegment = executableBlock.substring(match.range.first, match.range.last + 1)
                executableSegment.contains("CompoundTag") &&
                    executableSegment.contains("CODEC") &&
                    (executableSegment.contains("fieldOf") || executableSegment.contains("optionalFieldOf"))
            }
            .map { it.groupValues[1] }
            .toSet()
        return RecipeCodecFieldSet(itemStackFields = itemStackFields, compoundTagFields = compoundTagFields)
    }

    private fun recipeListEntryCodecFieldsInBlock(
        block: String,
        executableBlock: String,
        context: JavaSourceInfo,
        index: JavaSourceIndex,
        visited: MutableSet<String>
    ): RecipeCodecFieldSet {
        val listEntryFields = linkedMapOf<String, MutableSet<String>>()
        val codecListPattern = Regex(
            """\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*\.\s*CODEC\s*\.\s*listOf\s*\(\s*\)\s*\.\s*(?:optionalFieldOf|fieldOf)\s*\(\s*"([^"]+)""""
        )
        codecListPattern.findAll(block)
            .filter { match ->
                val executableSegment = executableBlock.substring(match.range.first, match.range.last + 1)
                executableSegment.contains("CODEC") &&
                    executableSegment.contains("listOf") &&
                    (executableSegment.contains("fieldOf") || executableSegment.contains("optionalFieldOf"))
            }
            .forEach { match ->
                val entryType = resolveRecipeCodecOwner(match.groupValues[1], context, index) ?: return@forEach
                val entryFields = recipeCodecFieldsForType(entryType.first, entryType.second, index, visited)
                if (entryFields.itemStackFields.isEmpty()) return@forEach
                listEntryFields
                    .getOrPut(match.groupValues[2]) { linkedSetOf() }
                    .addAll(entryFields.itemStackFields)
            }
        return RecipeCodecFieldSet(itemStackFieldsByListField = listEntryFields.mapValues { it.value.toSet() })
    }

    private fun directSuperclassReference(classBlock: String, className: String): String? =
        Regex("""\bclass\s+${Regex.escape(className)}\b[^{]*\bextends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)""")
            .find(classBlock)
            ?.groupValues
            ?.get(1)

    private fun collectJavaSourceInfos(projectDir: Path): List<JavaSourceInfo> {
        val sourceRoots = listOf(
            projectDir.resolve("src/main/java"),
            projectDir.resolve("src/generated/java")
        ).filter { it.exists() }
        if (sourceRoots.isEmpty()) return emptyList()

        return sourceRoots.flatMap { root ->
            Files.walk(root).use { files ->
                files
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                    .toList()
                    .mapNotNull { javaSourceInfo(it) }
            }
        }
    }

    private fun javaSourceInfo(file: Path): JavaSourceInfo? {
        val content = file.readText()
        val code = maskJavaComments(content)
        val executableCode = maskJavaCommentsAndLiterals(content)
        val packageName = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
            .find(executableCode)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        val simpleName = sourceDeclaredJavaTypeName(executableCode) ?: return null
        val imports = linkedMapOf<String, String>()
        val wildcardImports = linkedSetOf<String>()
        Regex("""(?m)^\s*import\s+(?!static)([\w.]+(?:\.\*)?)\s*;""")
            .findAll(executableCode)
            .forEach { match ->
                val imported = match.groupValues[1]
                if (imported.endsWith(".*")) {
                    wildcardImports += imported.removeSuffix(".*")
                } else {
                    imports[imported.substringAfterLast('.')] = imported
                }
            }
        val fqName = if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
        return JavaSourceInfo(file, content, code, executableCode, packageName, simpleName, fqName, imports, wildcardImports)
    }

    private fun sourceDeclaredJavaTypeName(executableCode: String): String? {
        val declarations = topLevelJavaTypeDeclarations(executableCode)
        if (declarations.isEmpty()) return null
        val publicDeclarations = declarations.filter { it.isPublic }
        return when {
            publicDeclarations.size == 1 -> publicDeclarations.single().name
            publicDeclarations.size > 1 -> null
            declarations.size == 1 -> declarations.single().name
            else -> null
        }
    }

    private fun topLevelJavaTypeDeclarations(executableCode: String): List<JavaDeclaredType> {
        val identifier = """[A-Za-z_$][\w$]*"""
        val declaration = Regex(
            """(?m)^[ \t]*(?:(?:@(?:$identifier\.)*$identifier(?:\s*\([^;\r\n]*\))?|public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(class|interface|enum|record)\s+($identifier)\b"""
        )
        return declaration.findAll(executableCode).mapNotNull { match ->
            if (javaBraceDepthBefore(executableCode, match.range.first) != 0) return@mapNotNull null
            val openBrace = executableCode.indexOf('{', match.range.last + 1)
            if (openBrace < 0) return@mapNotNull null
            val closeBrace = findMatchingJavaBrace(executableCode, openBrace)
            if (closeBrace < 0) return@mapNotNull null
            JavaDeclaredType(
                name = match.groupValues[2],
                isPublic = Regex("""\bpublic\b""").containsMatchIn(match.value)
            )
        }.toList()
    }

    private fun javaBraceDepthBefore(source: String, offset: Int): Int {
        var depth = 0
        var index = 0
        while (index < offset && index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (depth > 0) depth--
            }
            index++
        }
        return depth
    }

    private fun resolveJavaTypeReference(
        reference: String,
        context: JavaSourceInfo,
        index: JavaSourceIndex
    ): JavaSourceInfo? {
        val clean = sanitizeJavaTypeReference(reference)
        if (clean.isBlank()) return null
        val segments = clean.split('.').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        for (end in segments.size downTo 1) {
            val candidate = segments.take(end).joinToString(".")
            index.byFqName[candidate]?.let { return it }
            if (end == 1) {
                resolveSimpleJavaType(candidate, context, index)?.let { return it }
            }
        }

        return resolveSimpleJavaType(segments.first(), context, index)
    }

    private fun resolveRecipeCodecOwner(
        reference: String,
        context: JavaSourceInfo,
        index: JavaSourceIndex
    ): Pair<JavaSourceInfo, String?>? {
        val clean = sanitizeJavaTypeReference(reference)
        if (clean.isBlank()) return null
        resolveJavaTypeReference(clean, context, index)?.let { source ->
            return source to nestedClassName(clean, source)
        }
        val simpleName = clean.substringAfterLast('.')
        if (javaClassBlockRange(context.executableCode, simpleName) != null) {
            return context to simpleName
        }
        return null
    }

    private fun resolveSimpleJavaType(
        simpleName: String,
        context: JavaSourceInfo,
        index: JavaSourceIndex
    ): JavaSourceInfo? {
        context.imports[simpleName]?.let { fqName ->
            index.byFqName[fqName]?.let { return it }
        }

        if (context.packageName.isNotBlank()) {
            index.byFqName["${context.packageName}.$simpleName"]?.let { return it }
        }

        val wildcardMatches = context.wildcardImports
            .mapNotNull { packageName -> index.byFqName["$packageName.$simpleName"] }
            .distinctBy { it.fqName }
        if (wildcardMatches.size == 1) return wildcardMatches.single()

        return null
    }

    private fun sanitizeJavaTypeReference(reference: String): String =
        reference
            .trim()
            .removePrefix("new ")
            .substringBefore("<")
            .removeSuffix("::new")
            .removeSuffix(".class")
            .trim()

    private fun nestedClassName(reference: String, topLevel: JavaSourceInfo): String? {
        val clean = sanitizeJavaTypeReference(reference)
        val afterFqName = clean.removePrefix("${topLevel.fqName}.")
        if (afterFqName != clean) return afterFqName.substringBefore('.').takeIf { it.isNotBlank() }
        val afterSimpleName = clean.removePrefix("${topLevel.simpleName}.")
        if (afterSimpleName != clean) return afterSimpleName.substringBefore('.').takeIf { it.isNotBlank() }
        return null
    }

    private fun extractJavaClassBlock(source: String, className: String): String? {
        val range = javaClassBlockRange(source, className) ?: return null
        return source.substring(range)
    }

    private fun javaClassBlockRange(source: String, className: String): IntRange? {
        val classMatch = Regex("""\bclass\s+${Regex.escape(className)}\b""").find(source) ?: return null
        val openBrace = source.indexOf('{', classMatch.range.last + 1)
        if (openBrace < 0) return null
        val closeBrace = findMatchingJavaBrace(source, openBrace)
        if (closeBrace < 0) return null
        return classMatch.range.first..closeBrace
    }

    private fun findMatchingJavaBrace(source: String, openBrace: Int): Int {
        var depth = 0
        var index = openBrace
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var escaped = false
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when {
                !inString && !inChar && !inLineComment && !inBlockComment &&
                    char == '"' && next == '"' && source.getOrNull(index + 2) == '"' -> {
                    index = source.indexOf("\"\"\"", index + 3).let { if (it < 0) source.length else it + 3 }
                    continue
                }
                inLineComment -> {
                    if (char == '\n' || char == '\r') inLineComment = false
                }
                inBlockComment -> {
                    if (char == '*' && next == '/') {
                        inBlockComment = false
                        index++
                    }
                }
                escaped -> escaped = false
                inString -> when (char) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                inChar -> when (char) {
                    '\\' -> escaped = true
                    '\'' -> inChar = false
                }
                char == '/' && next == '/' -> {
                    inLineComment = true
                    index++
                }
                char == '/' && next == '*' -> {
                    inBlockComment = true
                    index++
                }
                char == '"' -> inString = true
                char == '\'' -> inChar = true
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    private fun findMatchingJavaParen(source: String, openParen: Int): Int {
        if (openParen < 0 || source.getOrNull(openParen) != '(') return -1
        var depth = 0
        var index = openParen
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var escaped = false
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when {
                inLineComment -> {
                    if (char == '\n' || char == '\r') inLineComment = false
                }
                inBlockComment -> {
                    if (char == '*' && next == '/') {
                        inBlockComment = false
                        index++
                    }
                }
                escaped -> escaped = false
                inString -> when (char) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                inChar -> when (char) {
                    '\\' -> escaped = true
                    '\'' -> inChar = false
                }
                char == '/' && next == '/' -> {
                    inLineComment = true
                    index++
                }
                char == '/' && next == '*' -> {
                    inBlockComment = true
                    index++
                }
                char == '"' -> inString = true
                char == '\'' -> inChar = true
                char == '(' -> depth++
                char == ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    private fun splitTopLevelJavaArguments(arguments: String): List<String> {
        val result = mutableListOf<String>()
        var depthParen = 0
        var depthBrace = 0
        var depthBracket = 0
        var start = 0
        var index = 0
        var inString = false
        var inChar = false
        var escaped = false
        while (index < arguments.length) {
            val char = arguments[index]
            when {
                escaped -> escaped = false
                inString -> when (char) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                inChar -> when (char) {
                    '\\' -> escaped = true
                    '\'' -> inChar = false
                }
                char == '"' -> inString = true
                char == '\'' -> inChar = true
                char == '(' -> depthParen++
                char == ')' -> if (depthParen > 0) depthParen--
                char == '{' -> depthBrace++
                char == '}' -> if (depthBrace > 0) depthBrace--
                char == '[' -> depthBracket++
                char == ']' -> if (depthBracket > 0) depthBracket--
                char == ',' && depthParen == 0 && depthBrace == 0 && depthBracket == 0 -> {
                    result += arguments.substring(start, index).trim()
                    start = index + 1
                }
            }
            index++
        }
        result += arguments.substring(start).trim()
        return result.filter { it.isNotBlank() }
    }

    private fun migrateRecipeResultEntries(
        content: String,
        recipeCodecHints: RecipeDataCodecHints = RecipeDataCodecHints.EMPTY
    ): String {
        val root = parseResourceJson(content) ?: return content
        val result = migrateRecipeResultEntries(root, currentRecipeType = null, recipeCodecHints)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun migrateRecipeResultEntries(
        element: JsonElement,
        currentRecipeType: String?,
        recipeCodecHints: RecipeDataCodecHints
    ): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = migrateRecipeResultEntries(child, currentRecipeType, recipeCodecHints)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val objectType = (element["type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                val recipeType = objectType ?: currentRecipeType
                val entries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val listEntryFields = recipeCodecHints.itemStackListEntryFields(recipeType, key)
                    val result = if (listEntryFields.isNotEmpty() && value is JsonArray) {
                        migrateRecipeListEntryItemStackFields(value, listEntryFields)
                    } else if (shouldMigrateRecipeItemStackField(recipeType, key, recipeCodecHints)) {
                        migrateRecipeResultValue(value)
                    } else {
                        migrateRecipeResultEntries(value, recipeType, recipeCodecHints)
                    }
                    changed = changed || result.changed
                    entries[key] = result.element
                }
                JsonElementMigration(JsonObject(entries), changed)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun migrateRecipeListEntryItemStackFields(
        element: JsonArray,
        stackFields: Set<String>
    ): JsonElementMigration {
        var changed = false
        val values = element.map { entry ->
            val entryObject = entry as? JsonObject ?: return@map entry
            val presentStackFields = stackFields.filter { it in entryObject }
            if (presentStackFields.isEmpty()) return@map entry

            val count = entryObject["count"]
            val fieldsFoldingCount = presentStackFields
                .filter { field -> count != null && canFoldLegacyItemStackCount(entryObject.getValue(field)) }
                .toSet()
            var entryChanged = false
            val entries = linkedMapOf<String, JsonElement>()
            for ((key, value) in entryObject) {
                when {
                    key in presentStackFields -> {
                        val migrated = migrateRecipeItemStackFieldValue(
                            value,
                            count.takeIf { key in fieldsFoldingCount }
                        )
                        entryChanged = entryChanged || migrated.changed
                        entries[key] = migrated.element
                    }
                    key == "count" && fieldsFoldingCount.isNotEmpty() -> {
                        entryChanged = true
                    }
                    else -> entries[key] = value
                }
            }
            changed = changed || entryChanged
            if (entryChanged) JsonObject(entries) else entry
        }
        return JsonElementMigration(JsonArray(values), changed)
    }

    private fun canFoldLegacyItemStackCount(element: JsonElement): Boolean =
        when (element) {
            is JsonPrimitive -> element.isString
            is JsonObject -> "count" !in element && ("item" in element || "id" in element)
            else -> false
        }

    private fun migrateRecipeItemStackFieldValue(element: JsonElement, count: JsonElement?): JsonElementMigration {
        val migrated = migrateRecipeResultValue(element)
        if (count == null || migrated.element !is JsonObject || "count" in migrated.element) {
            return migrated
        }
        val entries = linkedMapOf<String, JsonElement>()
        for ((key, value) in migrated.element) {
            entries[key] = value
        }
        entries["count"] = count
        return JsonElementMigration(JsonObject(entries), changed = true)
    }

    private fun shouldMigrateRecipeItemStackField(
        type: String?,
        fieldName: String,
        recipeCodecHints: RecipeDataCodecHints
    ): Boolean =
        (fieldName == "result" && type?.startsWith("minecraft:") == true) ||
            (type != null && fieldName in recipeCodecHints.itemStackFields(type))

    private fun migrateRecipeResultValue(element: JsonElement): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = migrateRecipeResultValue(child)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                val hasId = "id" in element
                if (!element.containsKey("item") || hasId) {
                    return JsonElementMigration(element, changed = false)
                }
                val entries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    if (key == "item") {
                        entries["id"] = value
                    } else {
                        entries[key] = value
                    }
                }
                JsonElementMigration(JsonObject(entries), changed = true)
            }
            is JsonPrimitive -> {
                if (!element.isString) return JsonElementMigration(element, changed = false)
                val entries = linkedMapOf<String, JsonElement>()
                entries["id"] = element
                JsonElementMigration(JsonObject(entries), changed = true)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun migrateRecipeCompoundTagFields(
        content: String,
        recipeCodecHints: RecipeDataCodecHints
    ): String {
        val root = parseResourceJson(content) ?: return content
        val result = migrateRecipeCompoundTagFields(root, currentRecipeType = null, recipeCodecHints)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun migrateRecipeCompoundTagFields(
        element: JsonElement,
        currentRecipeType: String?,
        recipeCodecHints: RecipeDataCodecHints
    ): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = migrateRecipeCompoundTagFields(child, currentRecipeType, recipeCodecHints)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val objectType = (element["type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                val recipeType = objectType ?: currentRecipeType
                val entries = linkedMapOf<String, JsonElement>()
                val compoundFields = recipeCodecHints.compoundTagFields(recipeType)
                for ((key, value) in element) {
                    val result = if (key in compoundFields) {
                        migrateSnbtCompoundTagValue(value)
                    } else {
                        migrateRecipeCompoundTagFields(value, recipeType, recipeCodecHints)
                    }
                    changed = changed || result.changed
                    entries[key] = result.element
                }
                JsonElementMigration(JsonObject(entries), changed)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun migrateSnbtCompoundTagValue(element: JsonElement): JsonElementMigration {
        val primitive = element as? JsonPrimitive ?: return JsonElementMigration(element, changed = false)
        if (!primitive.isString) return JsonElementMigration(element, changed = false)
        val compound = parseSnbtCompoundJson(primitive.content) ?: return JsonElementMigration(element, changed = false)
        return JsonElementMigration(compound, changed = true)
    }

    private fun migratePartialNbtIngredients(content: String): String {
        val root = parseResourceJson(content) ?: return content
        val result = migratePartialNbtIngredients(root, currentKey = null, arrayMayBeIngredient = false)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun migratePartialNbtIngredients(
        element: JsonElement,
        currentKey: String?,
        arrayMayBeIngredient: Boolean
    ): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val childMayBeIngredient = arrayMayBeIngredient || currentKey in INGREDIENT_LIST_KEYS
                val values = element.map { child ->
                    val result = migratePartialNbtIngredients(child, currentKey = null, arrayMayBeIngredient = childMayBeIngredient)
                    changed = changed || result.changed
                    result.element
                }
                val migratedArray = JsonArray(values)
                if (arrayMayBeIngredient && values.any { it.isNeoForgeCustomIngredient() }) {
                    val compound = linkedMapOf<String, JsonElement>()
                    compound["type"] = JsonPrimitive("neoforge:compound")
                    compound["children"] = migratedArray
                    JsonElementMigration(JsonObject(compound), changed = true)
                } else {
                    JsonElementMigration(migratedArray, changed)
                }
            }
            is JsonObject -> {
                migratePartialNbtIngredientObject(element)?.let {
                    return JsonElementMigration(it, changed = true)
                }

                var changed = false
                val entries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val result = migratePartialNbtIngredients(value, currentKey = key, arrayMayBeIngredient = false)
                    entries[key] = result.element
                    changed = changed || result.changed
                }
                JsonElementMigration(JsonObject(entries), changed)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun migratePartialNbtIngredientObject(element: JsonObject): JsonObject? {
        val type = (element["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: return null
        if (type !in setOf("forge:partial_nbt", "neoforge:partial_nbt", "forge:nbt", "neoforge:nbt")) return null

        val item = (element["item"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: return null
        val nbt = (element["nbt"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: return null
        val components = partialNbtToComponents(nbt) ?: return null

        val migrated = linkedMapOf<String, JsonElement>()
        migrated["type"] = JsonPrimitive("neoforge:components")
        migrated["components"] = components
        migrated["items"] = JsonPrimitive(item)
        return JsonObject(migrated)
    }

    private fun parseSnbtCompoundJson(value: String): JsonObject? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val body = trimmed.substring(1, trimmed.length - 1).trim()
        if (body.isEmpty()) return JsonObject(emptyMap())

        val entries = linkedMapOf<String, JsonElement>()
        for (entry in splitTopLevelSnbtEntries(body)) {
            val colon = findTopLevelSnbtColon(entry)
            if (colon <= 0) return null
            val key = unquoteSnbtString(entry.substring(0, colon).trim())
            val element = parseSnbtJsonElement(entry.substring(colon + 1).trim()) ?: return null
            entries[key] = element
        }
        return JsonObject(entries)
    }

    private fun parseSnbtJsonElement(value: String): JsonElement? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseSnbtCompoundJson(trimmed)
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return parseSnbtArrayJson(trimmed)
        }
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return JsonPrimitive(unquoteSnbtString(trimmed))
        }
        return parseSnbtNumberPrimitive(trimmed)
            ?: when (trimmed.lowercase()) {
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> JsonPrimitive(trimmed)
            }
    }

    private fun parseSnbtArrayJson(value: String): JsonArray? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val body = trimmed.substring(1, trimmed.length - 1).trim()
        val arrayBody = if (body.length > 2 && body[1] == ';' && body[0] in charArrayOf('B', 'b', 'I', 'i', 'L', 'l')) {
            body.substring(2).trim()
        } else {
            body
        }
        if (arrayBody.isEmpty()) return JsonArray(emptyList())

        val values = splitTopLevelSnbtEntries(arrayBody).map { entry ->
            parseSnbtJsonElement(entry) ?: return null
        }
        return JsonArray(values)
    }

    private fun findTopLevelSnbtColon(value: String): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in value.indices) {
            val char = value[index]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && (char == '{' || char == '[') -> depth++
                !inString && (char == '}' || char == ']') -> depth--
                !inString && depth == 0 && char == ':' -> return index
            }
        }
        return -1
    }

    private fun partialNbtToComponents(nbt: String): JsonObject? {
        val trimmed = nbt.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        val body = trimmed.substring(1, trimmed.length - 1).trim()
        if (body.isEmpty()) return JsonObject(emptyMap())

        val components = linkedMapOf<String, JsonElement>()
        val customData = linkedMapOf<String, JsonElement>()
        for (entry in splitTopLevelSnbtEntries(body)) {
            val colon = findTopLevelSnbtColon(entry)
            if (colon <= 0) continue
            val key = entry.substring(0, colon).trim().trim('"')
            val value = entry.substring(colon + 1).trim()
            when (key) {
                "Damage" -> {
                    val damage = parseSnbtInt(value) ?: continue
                    components["minecraft:damage"] = JsonPrimitive(damage)
                }
                "Potion" -> {
                    components["minecraft:potion_contents"] = JsonObject(mapOf(
                        "potion" to JsonPrimitive(unquoteSnbtString(value))
                    ))
                }
                else -> {
                    customData[key] = parseSnbtJsonPrimitive(value)
                }
            }
        }
        if (customData.isNotEmpty()) {
            components["minecraft:custom_data"] = JsonObject(customData)
        }
        return JsonObject(components)
    }

    private fun splitTopLevelSnbtEntries(body: String): List<String> {
        val entries = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inString = false
        var escaped = false
        for (index in body.indices) {
            val char = body[index]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && (char == '{' || char == '[') -> depth++
                !inString && (char == '}' || char == ']') -> depth--
                !inString && depth == 0 && char == ',' -> {
                    entries.add(body.substring(start, index).trim())
                    start = index + 1
                }
            }
        }
        entries.add(body.substring(start).trim())
        return entries.filter { it.isNotEmpty() }
    }

    private fun parseSnbtInt(value: String): Int? =
        value.trim().trimEnd('b', 'B', 's', 'S', 'l', 'L').toIntOrNull()

    private fun parseSnbtNumberPrimitive(value: String): JsonPrimitive? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        val suffix = normalized.last()
        val unsigned = when (suffix) {
            'b', 'B', 's', 'S', 'l', 'L', 'f', 'F', 'd', 'D' -> normalized.dropLast(1)
            else -> normalized
        }
        return when {
            suffix in charArrayOf('f', 'F', 'd', 'D') ||
                unsigned.contains('.') ||
                unsigned.indexOf('e', ignoreCase = true) >= 0 ->
                unsigned.toDoubleOrNull()?.let { JsonPrimitive(it) }
            suffix in charArrayOf('l', 'L') ->
                unsigned.toLongOrNull()?.let { JsonPrimitive(it) }
            else ->
                unsigned.toIntOrNull()?.let { JsonPrimitive(it) }
                    ?: unsigned.toLongOrNull()?.let { JsonPrimitive(it) }
        }
    }

    private fun parseSnbtJsonPrimitive(value: String): JsonElement {
        val normalized = value.trim()
        parseSnbtNumberPrimitive(normalized)?.let { return it }
        return when (normalized.lowercase()) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> JsonPrimitive(unquoteSnbtString(normalized))
        }
    }

    private fun unquoteSnbtString(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"")
        } else {
            trimmed
        }
    }

    private fun JsonElement.isNeoForgeCustomIngredient(): Boolean {
        val objectValue = this as? JsonObject ?: return false
        val ingredientType = ((objectValue["type"] ?: objectValue["neoforge:ingredient_type"]) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: return false
        return ingredientType.startsWith("neoforge:")
    }

    private fun migrateUncraftingRecipeInputWrappers(content: String): String {
        val root = parseResourceJson(content) as? JsonObject ?: return content
        val type = (root["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        if (type?.endsWith(":uncrafting") != true) return content

        var changed = false
        var pendingInputCount: JsonElement? = null
        val entries = linkedMapOf<String, JsonElement>()
        for ((key, value) in root) {
            when (key) {
                "input" -> {
                    val input = value as? JsonObject
                    val ingredient = input?.get("ingredient")
                    if (ingredient != null) {
                        entries["input"] = unwrapIngredientWrapper(ingredient)
                        pendingInputCount = input["count"]
                        changed = true
                    } else {
                        entries[key] = value
                    }
                    if (pendingInputCount != null) {
                        entries["input_count"] = pendingInputCount!!
                    }
                }
                "key" -> {
                    val keyObject = value as? JsonObject
                    if (keyObject == null) {
                        entries[key] = value
                    } else {
                        val migratedKey = linkedMapOf<String, JsonElement>()
                        for ((symbol, ingredient) in keyObject) {
                            val simplified = unwrapIngredientWrapper(ingredient)
                            migratedKey[symbol] = simplified
                            changed = changed || simplified != ingredient
                        }
                        entries[key] = JsonObject(migratedKey)
                    }
                }
                else -> entries[key] = value
            }
        }
        if (!changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), JsonObject(entries)) + "\n"
    }

    private fun unwrapIngredientWrapper(element: JsonElement): JsonElement {
        val objectValue = element as? JsonObject ?: return element
        val nested = objectValue["ingredient"]
        if (nested != null && objectValue.keys.all { it == "ingredient" }) {
            return unwrapIngredientWrapper(nested)
        }
        return element
    }

    private fun migrateGlobalLootModifierJson(content: String): String {
        val root = parseResourceJson(content) as? JsonObject ?: return content
        if (!root.containsKey("type")) return content

        val conditions = root["neoforge:conditions"] ?: root["conditions"] ?: return content
        val migratedConditions = migrateGlobalLootConditions(conditions)
        val hadNeoForgeConditions = root.containsKey("neoforge:conditions")
        if (!hadNeoForgeConditions && !migratedConditions.changed) return content

        val entries = linkedMapOf<String, JsonElement>()
        var wroteConditions = false
        for ((key, value) in root) {
            when (key) {
                "neoforge:conditions" -> {
                    if (!wroteConditions) {
                        entries["conditions"] = migratedConditions.element
                        wroteConditions = true
                    }
                }
                "conditions" -> {
                    entries["conditions"] = migratedConditions.element
                    wroteConditions = true
                }
                else -> entries[key] = value
            }
        }
        if (!wroteConditions) {
            entries["conditions"] = migratedConditions.element
        }

        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), JsonObject(entries)) + "\n"
    }

    private fun migrateGlobalLootConditions(element: JsonElement): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = migrateGlobalLootConditions(child)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val entries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val result = migrateGlobalLootConditions(value)
                    if (key == "condition") {
                        val condition = normalizeGlobalLootConditionType(result.element)
                        entries[key] = condition
                        changed = changed || result.changed || condition != result.element
                    } else {
                        entries[key] = result.element
                        changed = changed || result.changed
                    }
                }
                JsonElementMigration(JsonObject(entries), changed)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun normalizeGlobalLootConditionType(element: JsonElement): JsonElement {
        val value = (element as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: return element
        val normalized = if (":" in value) value else "minecraft:$value"
        return JsonPrimitive(
            when (normalized) {
                "minecraft:alternative" -> "minecraft:any_of"
                else -> normalized
            }
        )
    }

    private fun normalizeTagReferenceNamespaces(content: String): String {
        val root = parseResourceJson(content) ?: return content
        val result = normalizeTagReferenceNamespaces(root, currentKey = null)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun normalizeTagReferenceNamespaces(element: JsonElement, currentKey: String?): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = normalizeTagReferenceNamespaces(child, currentKey)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val type = (element["type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                val entries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val childKey = if (key == "name" && type == "minecraft:tag") "tag" else key
                    val result = normalizeTagReferenceNamespaces(value, childKey)
                    changed = changed || result.changed
                    entries[key] = result.element
                }
                JsonElementMigration(JsonObject(entries), changed)
            }
            is JsonPrimitive -> {
                if (!element.isString) return JsonElementMigration(element, changed = false)
                val value = element.content
                val normalized = normalizeTagReferenceString(currentKey, value)
                if (normalized == value) {
                    JsonElementMigration(element, changed = false)
                } else {
                    JsonElementMigration(JsonPrimitive(normalized), changed = true)
                }
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun normalizeTagReferenceString(currentKey: String?, value: String): String {
        return when {
            value.startsWith("#forge:") -> "#c:${normalizeCommonTagPath(value.removePrefix("#forge:"))}"
            value.startsWith("#neoforge:") -> "#c:${normalizeCommonTagPath(value.removePrefix("#neoforge:"))}"
            value.startsWith("#c:") -> "#c:${normalizeCommonTagPath(value.removePrefix("#c:"))}"
            currentKey != null && currentKey in TAG_REFERENCE_VALUE_KEYS && value.startsWith("forge:") ->
                "c:${normalizeCommonTagPath(value.removePrefix("forge:"))}"
            currentKey != null && currentKey in TAG_REFERENCE_VALUE_KEYS && value.startsWith("neoforge:") ->
                "c:${normalizeCommonTagPath(value.removePrefix("neoforge:"))}"
            currentKey != null && currentKey in TAG_REFERENCE_VALUE_KEYS && value.startsWith("c:") ->
                "c:${normalizeCommonTagPath(value.removePrefix("c:"))}"
            else -> value
        }
    }

    private fun normalizeCommonTagPath(path: String): String {
        COMMON_TAG_PATH_RENAMES[path]?.let { return it }
        for ((from, to) in COMMON_TAG_PATH_PREFIX_RENAMES) {
            if (path.startsWith(from)) return to + path.removePrefix(from)
        }
        return path
    }

    private fun renameLegacyResourceIds(content: String): String {
        if (RESOURCE_ID_RENAMES_121.keys.none { content.contains("\"$it\"") }) return content
        val root = parseResourceJson(content) ?: return content
        val result = renameLegacyResourceIds(root)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun renameLegacyResourceIds(element: JsonElement): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = renameLegacyResourceIds(child)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val entries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val result = renameLegacyResourceIds(value)
                    changed = changed || result.changed
                    entries[key] = result.element
                }
                JsonElementMigration(JsonObject(entries), changed)
            }
            is JsonPrimitive -> {
                if (!element.isString) return JsonElementMigration(element, changed = false)
                val renamed = RESOURCE_ID_RENAMES_121[element.content] ?: return JsonElementMigration(element, changed = false)
                JsonElementMigration(JsonPrimitive(renamed), changed = true)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun migrateAdvancementConditionKeys(content: String): String {
        val root = parseResourceJson(content) ?: return content
        val result = migrateAdvancementConditionKeys(root, depth = 0)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun migrateAdvancementConditionKeys(element: JsonElement, depth: Int): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = migrateAdvancementConditionKeys(child, depth + 1)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val entries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val result = migrateAdvancementConditionKeys(value, depth + 1)
                    val migratedKey = if (key == "neoforge:conditions" && depth > 0) {
                        changed = true
                        "conditions"
                    } else {
                        key
                    }
                    entries[migratedKey] = result.element
                    changed = changed || result.changed
                }
                JsonElementMigration(JsonObject(entries), changed)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun unwrapSingleConditionalAdvancement(content: String): String {
        val root = parseResourceJson(content) as? JsonObject ?: return content
        val advancements = root["advancements"] as? JsonArray ?: return content
        if (advancements.size != 1) return content

        val conditionalEntry = advancements.single() as? JsonObject ?: return content
        val advancement = conditionalEntry["advancement"] as? JsonObject ?: return content
        val conditions = conditionalEntry["neoforge:conditions"] ?: conditionalEntry["conditions"]

        val unwrapped = linkedMapOf<String, JsonElement>()
        if (conditions != null) {
            unwrapped["neoforge:conditions"] = conditions
        }
        for ((key, value) in advancement) {
            if ((key == "neoforge:conditions" || key == "conditions") && conditions != null) continue
            unwrapped[key] = value
        }
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), JsonObject(unwrapped)) + "\n"
    }

    private fun unwrapSingleConditionalRecipe(content: String): String {
        val root = parseResourceJson(content) as? JsonObject ?: return content
        val type = (root["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        if (type != "neoforge:conditional") return content

        val recipes = root["recipes"] as? JsonArray ?: return content
        if (recipes.size != 1) return content

        val conditionalEntry = recipes.single() as? JsonObject ?: return content
        val recipe = conditionalEntry["recipe"] as? JsonObject ?: return content
        val conditions = conditionalEntry["neoforge:conditions"] ?: conditionalEntry["conditions"]

        val unwrapped = linkedMapOf<String, JsonElement>()
        if (conditions != null) {
            unwrapped["neoforge:conditions"] = conditions
        }
        for ((key, value) in recipe) {
            if (key == "neoforge:conditions" && conditions != null) continue
            unwrapped[key] = value
        }
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), JsonObject(unwrapped)) + "\n"
    }

    private fun migrateLootTableEntryNames(content: String): String {
        val root = parseResourceJson(content) ?: return content
        val result = migrateLootTableEntryNames(root)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun migrateLootTableEntryNames(element: JsonElement): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = migrateLootTableEntryNames(child)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val migratedEntries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val result = migrateLootTableEntryNames(value)
                    changed = changed || result.changed
                    migratedEntries[key] = result.element
                }

                val type = (migratedEntries["type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                if (type != "minecraft:loot_table" ||
                    "name" !in migratedEntries ||
                    "value" in migratedEntries
                ) {
                    return JsonElementMigration(JsonObject(migratedEntries), changed)
                }

                val rewritten = linkedMapOf<String, JsonElement>()
                for ((key, value) in migratedEntries) {
                    rewritten[if (key == "name") "value" else key] = value
                }
                JsonElementMigration(JsonObject(rewritten), changed = true)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun migrateRandomChanceWithLootingConditions(content: String): String {
        val root = parseResourceJson(content) ?: return content
        val result = migrateRandomChanceWithLootingConditions(root)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun migrateRandomChanceWithLootingConditions(element: JsonElement): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = migrateRandomChanceWithLootingConditions(child)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val migratedEntries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val result = migrateRandomChanceWithLootingConditions(value)
                    changed = changed || result.changed
                    migratedEntries[key] = result.element
                }

                val condition = (migratedEntries["condition"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                if (condition != "minecraft:random_chance_with_looting") {
                    return JsonElementMigration(JsonObject(migratedEntries), changed)
                }

                val chance = jsonNumber(migratedEntries["chance"]) ?: return JsonElementMigration(JsonObject(migratedEntries), changed)
                val multiplier = jsonNumber(migratedEntries["looting_multiplier"]) ?: 0.0
                val enchantedChance = JsonObject(linkedMapOf(
                    "type" to JsonPrimitive("minecraft:linear"),
                    "base" to JsonPrimitive(chance + multiplier),
                    "per_level_above_first" to JsonPrimitive(multiplier)
                ))

                val rewritten = linkedMapOf<String, JsonElement>()
                for ((key, value) in migratedEntries) {
                    when (key) {
                        "condition" -> {
                            rewritten["condition"] = JsonPrimitive("minecraft:random_chance_with_enchanted_bonus")
                            rewritten["enchanted_chance"] = enchantedChance
                            rewritten["enchantment"] = JsonPrimitive("minecraft:looting")
                            rewritten["unenchanted_chance"] = JsonPrimitive(chance)
                        }
                        "chance", "looting_multiplier" -> Unit
                        else -> rewritten[key] = value
                    }
                }
                JsonElementMigration(JsonObject(rewritten), changed = true)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun jsonNumber(element: JsonElement?): Double? =
        (element as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun migrateLootTableFunctionNames(content: String): String {
        val root = parseResourceJson(content) ?: return content
        val result = migrateLootTableFunctionNames(root)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun migrateLootTableFunctionNames(element: JsonElement): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = migrateLootTableFunctionNames(child)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val migratedEntries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val result = migrateLootTableFunctionNames(value)
                    changed = changed || result.changed
                    migratedEntries[key] = result.element
                }

                val functionValue = (migratedEntries["function"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                if (functionValue != "minecraft:looting_enchant") {
                    return JsonElementMigration(JsonObject(migratedEntries), changed)
                }

                val hasEnchantment = migratedEntries.containsKey("enchantment")
                val rewritten = linkedMapOf<String, JsonElement>()
                var insertedEnchantment = false
                for ((key, value) in migratedEntries) {
                    if (key == "function") {
                        rewritten[key] = JsonPrimitive("minecraft:enchanted_count_increase")
                    } else {
                        rewritten[key] = value
                    }
                    if (key == "count" && !hasEnchantment) {
                        rewritten["enchantment"] = JsonPrimitive("minecraft:looting")
                        insertedEnchantment = true
                    }
                }
                if (!hasEnchantment && !insertedEnchantment) {
                    rewritten["enchantment"] = JsonPrimitive("minecraft:looting")
                }
                JsonElementMigration(JsonObject(rewritten), changed = true)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun flattenWorldgenProviderValueObjects(content: String): String {
        val root = parseResourceJson(content) ?: return content
        val result = flattenWorldgenProviderValueObjects(root)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun flattenNoStructurePlacementModifiers(content: String): String {
        val root = parseResourceJson(content) ?: return content
        val result = flattenNoStructurePlacementModifiers(root)
        if (!result.changed) return content
        return RESOURCE_JSON.encodeToString(JsonElement.serializer(), result.element) + "\n"
    }

    private fun parseResourceJson(content: String): JsonElement? =
        try {
            RESOURCE_JSON.parseToJsonElement(content)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun flattenWorldgenProviderValueObjects(element: JsonElement): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = flattenWorldgenProviderValueObjects(child)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val migratedEntries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val result = flattenWorldgenProviderValueObjects(value)
                    changed = changed || result.changed
                    migratedEntries[key] = result.element
                }

                val type = (migratedEntries["type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                val valueObject = migratedEntries["value"] as? JsonObject
                if (type != null &&
                    isFlattenableWorldgenValueProviderType(type) &&
                    valueObject != null &&
                    hasInclusiveBounds(valueObject) &&
                    valueObject.keys.none { migratedEntries.containsKey(it) }
                ) {
                    val flattened = linkedMapOf<String, JsonElement>()
                    for ((key, value) in migratedEntries) {
                        if (key == "value") {
                            for ((valueKey, valueValue) in valueObject) {
                                flattened[valueKey] = valueValue
                            }
                        } else {
                            flattened[key] = value
                        }
                    }
                    JsonElementMigration(JsonObject(flattened), true)
                } else {
                    JsonElementMigration(JsonObject(migratedEntries), changed)
                }
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun isFlattenableWorldgenValueProviderType(type: String): Boolean {
        val vanillaType = type.removePrefix("minecraft:")
        return vanillaType in FLATTENABLE_WORLDGEN_VALUE_PROVIDER_TYPES
    }

    private fun hasInclusiveBounds(valueObject: JsonObject): Boolean =
        "min_inclusive" in valueObject || "max_inclusive" in valueObject

    private fun flattenNoStructurePlacementModifiers(element: JsonElement): JsonElementMigration {
        return when (element) {
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val result = flattenNoStructurePlacementModifiers(child)
                    changed = changed || result.changed
                    result.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            is JsonObject -> {
                var changed = false
                val migratedEntries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val result = flattenNoStructurePlacementModifiers(value)
                    changed = changed || result.changed
                    migratedEntries[key] = result.element
                }

                val type = (migratedEntries["type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                if (type?.endsWith(":no_structure") != true) {
                    return JsonElementMigration(JsonObject(migratedEntries), changed)
                }

                val valueObject = migratedEntries["value"] as? JsonObject
                val flattened = linkedMapOf<String, JsonElement>()
                for ((key, value) in migratedEntries) {
                    if (key == "value" && valueObject != null) {
                        for ((valueKey, valueValue) in valueObject) {
                            flattened.putIfAbsent(valueKey, valueValue)
                        }
                    } else if (key != "value") {
                        flattened[key] = value
                    }
                }
                if ("occupies_vegetation" !in flattened) {
                    flattened["occupies_vegetation"] = JsonPrimitive(false)
                }
                if ("structures_allowed" !in flattened) {
                    flattened["structures_allowed"] = JsonArray(emptyList())
                }
                JsonElementMigration(JsonObject(flattened), changed || valueObject != null ||
                    "occupies_vegetation" !in migratedEntries ||
                    "structures_allowed" !in migratedEntries)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private data class RecipeDataCodecHints(
        val itemStackFieldsByType: Map<String, Set<String>> = emptyMap(),
        val itemStackListEntryFieldsByType: Map<String, Map<String, Set<String>>> = emptyMap(),
        val compoundTagFieldsByType: Map<String, Set<String>> = emptyMap()
    ) {
        val hasCompoundTagFields: Boolean
            get() = compoundTagFieldsByType.isNotEmpty()

        fun itemStackFields(type: String?): Set<String> =
            if (type == null) emptySet() else itemStackFieldsByType[type].orEmpty()

        fun itemStackListEntryFields(type: String?, listFieldName: String): Set<String> =
            if (type == null) emptySet() else itemStackListEntryFieldsByType[type]?.get(listFieldName).orEmpty()

        fun compoundTagFields(type: String?): Set<String> =
            if (type == null) emptySet() else compoundTagFieldsByType[type].orEmpty()

        operator fun plus(other: RecipeDataCodecHints): RecipeDataCodecHints =
            RecipeDataCodecHints(
                itemStackFieldsByType =
                    (itemStackFieldsByType.keys + other.itemStackFieldsByType.keys).associateWith { key ->
                        itemStackFieldsByType[key].orEmpty() + other.itemStackFieldsByType[key].orEmpty()
                    },
                itemStackListEntryFieldsByType =
                    (itemStackListEntryFieldsByType.keys + other.itemStackListEntryFieldsByType.keys).associateWith { type ->
                        val left = itemStackListEntryFieldsByType[type].orEmpty()
                        val right = other.itemStackListEntryFieldsByType[type].orEmpty()
                        (left.keys + right.keys).associateWith { field ->
                            left[field].orEmpty() + right[field].orEmpty()
                        }
                    },
                compoundTagFieldsByType =
                    (compoundTagFieldsByType.keys + other.compoundTagFieldsByType.keys).associateWith { key ->
                        compoundTagFieldsByType[key].orEmpty() + other.compoundTagFieldsByType[key].orEmpty()
                    }
            )

        companion object {
            val EMPTY = RecipeDataCodecHints()
        }
    }

    private data class RecipeCodecFieldSet(
        val itemStackFields: Set<String> = emptySet(),
        val itemStackFieldsByListField: Map<String, Set<String>> = emptyMap(),
        val compoundTagFields: Set<String> = emptySet()
    ) {
        val isEmpty: Boolean
            get() = itemStackFields.isEmpty() && itemStackFieldsByListField.isEmpty() && compoundTagFields.isEmpty()

        operator fun plus(other: RecipeCodecFieldSet): RecipeCodecFieldSet =
            RecipeCodecFieldSet(
                itemStackFields = itemStackFields + other.itemStackFields,
                itemStackFieldsByListField =
                    (itemStackFieldsByListField.keys + other.itemStackFieldsByListField.keys).associateWith { key ->
                        itemStackFieldsByListField[key].orEmpty() + other.itemStackFieldsByListField[key].orEmpty()
                    },
                compoundTagFields = compoundTagFields + other.compoundTagFields
            )

        companion object {
            val EMPTY = RecipeCodecFieldSet()
        }
    }

    private data class JavaSourceInfo(
        val file: Path,
        val content: String,
        val code: String,
        val executableCode: String,
        val packageName: String,
        val simpleName: String,
        val fqName: String,
        val imports: Map<String, String>,
        val wildcardImports: Set<String>
    )

    private data class JavaDeclaredType(
        val name: String,
        val isPublic: Boolean
    )

    private data class JavaSourceIndex(
        val sources: List<JavaSourceInfo>
    ) {
        val byFqName: Map<String, JavaSourceInfo> = sources.associateBy { it.fqName }
    }

    private data class JsonElementMigration(val element: JsonElement, val changed: Boolean)

    private fun transformAssetJsonFiles(
        assetsDir: Path,
        resourceRoots: List<Path>,
        changes: MutableList<Change>,
        errors: MutableList<String>
    ) {
        Files.walk(assetsDir)
            .filter { it.toString().endsWith(".json") && Files.isRegularFile(it) }
            .forEach { file ->
                try {
                    val content = file.readText()
                    var newContent = content
                    val applied = mutableListOf<Pair<String, String>>()
                    val textureRewrites = mutableListOf<Pair<String, String>>()
                    for ((from, to) in MODEL_EXTENSION_RENAMES_121) {
                        if (newContent.contains(from)) {
                            newContent = newContent.replace(from, to)
                            applied.add(from to to)
                        }
                    }
                    val resourceIdContent = renameLegacyResourceIds(newContent)
                    val resourceIdsChanged = resourceIdContent != newContent
                    if (resourceIdsChanged) {
                        newContent = resourceIdContent
                    }
                    val textureReferenceContent = migrateModelTextureReferencesThatPointToModels(
                        file = file,
                        assetsDir = assetsDir,
                        resourceRoots = resourceRoots,
                        content = newContent,
                        rewrites = textureRewrites
                    )
                    val textureReferencesChanged = textureReferenceContent != newContent
                    if (textureReferencesChanged) {
                        newContent = textureReferenceContent
                    }
                    if (newContent != content) {
                        file.writeText(newContent)
                        if (applied.isNotEmpty()) {
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Model extension namespace: Forge -> NeoForge",
                                before = applied.joinToString(", ") { it.first },
                                after = applied.joinToString(", ") { it.second },
                                confidence = Confidence.HIGH,
                                ruleId = "res-model-extension-neoforge-namespace"
                            ))
                        }
                        if (resourceIdsChanged) {
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Legacy vanilla asset resource ids -> 1.21.1 ids",
                                before = "minecraft:block/grass",
                                after = "minecraft:block/short_grass",
                                confidence = Confidence.HIGH,
                                ruleId = "res-legacy-resource-id-renames-121"
                            ))
                        }
                        if (textureReferencesChanged) {
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Resolve model texture references that point to model files",
                                before = textureRewrites.joinToString(", ") { it.first },
                                after = textureRewrites.joinToString(", ") { it.second },
                                confidence = Confidence.HIGH,
                                ruleId = "res-model-texture-reference-model-target"
                            ))
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Failed to transform asset ${file.fileName}: ${e.message}")
                }
            }
    }

    private fun migrateModelTextureReferencesThatPointToModels(
        file: Path,
        assetsDir: Path,
        resourceRoots: List<Path>,
        content: String,
        rewrites: MutableList<Pair<String, String>>
    ): String {
        val normalizedPath = file.toString().replace('\\', '/')
        if (!normalizedPath.contains("/models/") || !content.contains("\"textures\"")) return content
        val namespace = assetNamespaceForFile(assetsDir, file) ?: return content
        val root = parseResourceJson(content) ?: return content
        val migration = migrateModelTextureReferences(root, namespace, resourceRoots, rewrites)
        return if (migration.changed) {
            RESOURCE_JSON.encodeToString(JsonElement.serializer(), migration.element) + "\n"
        } else {
            content
        }
    }

    private fun migrateModelTextureReferences(
        element: JsonElement,
        defaultNamespace: String,
        resourceRoots: List<Path>,
        rewrites: MutableList<Pair<String, String>>
    ): JsonElementMigration {
        return when (element) {
            is JsonObject -> {
                var changed = false
                val entries = linkedMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    if (key == "textures" && value is JsonObject) {
                        val textureEntries = linkedMapOf<String, JsonElement>()
                        var textureChanged = false
                        for ((textureKey, textureValue) in value) {
                            val primitive = textureValue as? JsonPrimitive
                            val reference = primitive
                                ?.takeIf { it.isString }
                                ?.content
                            val replacement = reference
                                ?.takeUnless { it.startsWith("#") }
                                ?.let {
                                    resolveTextureReferenceThatTargetsModel(
                                        reference = it,
                                        defaultNamespace = defaultNamespace,
                                        textureKey = textureKey,
                                        resourceRoots = resourceRoots,
                                        visitedModels = linkedSetOf()
                                    )
                                }
                            if (replacement != null && replacement != reference) {
                                textureEntries[textureKey] = JsonPrimitive(replacement)
                                rewrites.add(reference to replacement)
                                textureChanged = true
                            } else {
                                textureEntries[textureKey] = textureValue
                            }
                        }
                        entries[key] = if (textureChanged) JsonObject(textureEntries) else value
                        changed = changed || textureChanged
                    } else {
                        val child = migrateModelTextureReferences(value, defaultNamespace, resourceRoots, rewrites)
                        entries[key] = child.element
                        changed = changed || child.changed
                    }
                }
                JsonElementMigration(JsonObject(entries), changed)
            }
            is JsonArray -> {
                var changed = false
                val values = element.map { child ->
                    val migrated = migrateModelTextureReferences(child, defaultNamespace, resourceRoots, rewrites)
                    changed = changed || migrated.changed
                    migrated.element
                }
                JsonElementMigration(JsonArray(values), changed)
            }
            else -> JsonElementMigration(element, changed = false)
        }
    }

    private fun resolveTextureReferenceThatTargetsModel(
        reference: String,
        defaultNamespace: String,
        textureKey: String,
        resourceRoots: List<Path>,
        visitedModels: MutableSet<String>
    ): String? {
        val ref = parseResourceReference(reference, defaultNamespace) ?: return null
        if (resourceExists(resourceRoots, ref.namespace, "textures/${ref.path}.png")) return null
        val modelFile = findResourceFile(resourceRoots, ref.namespace, "models/${ref.path}.json") ?: return null
        val modelId = "${ref.namespace}:${ref.path}"
        if (!visitedModels.add(modelId)) return null
        val model = parseResourceJson(modelFile.readText()) as? JsonObject ?: return null
        val textureValue = resolvedModelTextureValue(model["textures"] as? JsonObject, textureKey) ?: return null
        val textureRef = parseResourceReference(textureValue, ref.namespace) ?: return null
        if (resourceExists(resourceRoots, textureRef.namespace, "textures/${textureRef.path}.png") ||
            textureRef.namespace == "minecraft"
        ) {
            return "${textureRef.namespace}:${textureRef.path}"
        }
        return resolveTextureReferenceThatTargetsModel(
            reference = textureValue,
            defaultNamespace = ref.namespace,
            textureKey = textureKey,
            resourceRoots = resourceRoots,
            visitedModels = visitedModels
        )
    }

    private fun resolvedModelTextureValue(textures: JsonObject?, preferredKey: String): String? {
        if (textures == null) return null
        val candidateKeys = (listOf(preferredKey, "layer0", "particle") + textures.keys).distinct()
        for (key in candidateKeys) {
            val value = resolveTextureAlias(textures, key, linkedSetOf())
            if (value != null) return value
        }
        return null
    }

    private fun resolveTextureAlias(textures: JsonObject, key: String, visitedKeys: MutableSet<String>): String? {
        if (!visitedKeys.add(key)) return null
        val value = (textures[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?: return null
        return if (value.startsWith("#")) {
            resolveTextureAlias(textures, value.removePrefix("#"), visitedKeys)
        } else {
            value
        }
    }

    private fun assetNamespaceForFile(assetsDir: Path, file: Path): String? {
        val relative = assetsDir.relativize(file)
        return if (relative.nameCount > 0) relative.getName(0).toString() else null
    }

    private data class ResourceReference(val namespace: String, val path: String)

    private fun parseResourceReference(value: String, defaultNamespace: String): ResourceReference? {
        if (value.isBlank() || value.startsWith("#")) return null
        val parts = value.split(':', limit = 2)
        val namespace = if (parts.size == 2) parts[0] else defaultNamespace
        val path = if (parts.size == 2) parts[1] else parts[0]
        if (namespace.isBlank() || path.isBlank()) return null
        if (path.contains("..") || path.startsWith("/") || path.endsWith("/")) return null
        return ResourceReference(namespace, path)
    }

    private fun resourceExists(resourceRoots: List<Path>, namespace: String, relativePath: String): Boolean =
        findResourceFile(resourceRoots, namespace, relativePath) != null

    private fun findResourceFile(resourceRoots: List<Path>, namespace: String, relativePath: String): Path? =
        resourceRoots
            .asSequence()
            .map { it.resolve("assets").resolve(namespace).resolve(relativePath) }
            .firstOrNull { it.exists() }

    private fun fillMissingSoundSubtitleTranslations(
        assetsDir: Path,
        changes: MutableList<Change>,
        errors: MutableList<String>
    ) {
        try {
            Files.list(assetsDir).use { namespaces ->
                namespaces
                    .filter { Files.isDirectory(it) }
                    .forEach { namespaceDir ->
                        val soundsFile = namespaceDir.resolve("sounds.json")
                        if (!soundsFile.exists()) return@forEach

                        val subtitles = soundSubtitleKeys(soundsFile)
                        if (subtitles.isEmpty()) return@forEach

                        val langFile = namespaceDir.resolve("lang/en_us.json")
                        val existing = if (langFile.exists()) {
                            parseResourceJson(langFile.readText()) as? JsonObject ?: return@forEach
                        } else {
                            JsonObject(emptyMap())
                        }

                        val entries = linkedMapOf<String, JsonElement>()
                        for ((key, value) in existing) {
                            entries[key] = value
                        }

                        val missing = subtitles.filter { it !in entries }.sorted()
                        if (missing.isEmpty()) return@forEach

                        for (subtitle in missing) {
                            entries[subtitle] = JsonPrimitive(humanizeSubtitleKey(subtitle))
                        }

                        langFile.parent.createDirectories()
                        langFile.writeText(RESOURCE_JSON.encodeToString(JsonElement.serializer(), JsonObject(entries)) + "\n")
                        changes.add(Change(
                            file = langFile,
                            line = 0,
                            description = "Add missing sound subtitle translations",
                            before = "(missing subtitle keys)",
                            after = missing.joinToString(", "),
                            confidence = Confidence.HIGH,
                            ruleId = "res-sound-subtitle-lang"
                        ))
                    }
            }
        } catch (e: Exception) {
            errors.add("Failed to fill sound subtitle translations: ${e.message}")
        }
    }

    private fun soundSubtitleKeys(soundsFile: Path): Set<String> {
        val root = parseResourceJson(soundsFile.readText()) as? JsonObject ?: return emptySet()
        return root.values
            .mapNotNull { sound ->
                (sound as? JsonObject)
                    ?.get("subtitle")
                    ?.jsonPrimitive
                    ?.takeIf { it.isString }
                    ?.content
            }
            .toSet()
    }

    private fun humanizeSubtitleKey(key: String): String {
        val tail = key.substringAfterLast('.').substringAfterLast(':').ifBlank { key }
        return tail
            .split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
            .ifBlank { key }
    }

    private fun generateMissingItemModels(
        projectDir: Path,
        resourceDir: Path,
        changes: MutableList<Change>,
        errors: MutableList<String>
    ) {
        try {
            val itemRegistrations = detectRegisteredItems(projectDir)
            if (itemRegistrations.isEmpty()) return

            val assetsRoot = resourceDir.resolve("assets")
            if (!assetsRoot.exists()) return
            val resourceRoots = findResourceDirs(projectDir)

            Files.list(assetsRoot).use { namespaces ->
                namespaces
                    .filter { Files.isDirectory(it) }
                    .forEach { namespaceDir ->
                        val modId = namespaceDir.fileName.toString()
                        val modelDir = namespaceDir.resolve("models/item")
                        val textureDir = namespaceDir.resolve("textures/item")
                        if (!textureDir.exists()) return@forEach

                        for (item in itemRegistrations) {
                            val modelFile = modelDir.resolve("${item.id}.json")
                            val modelRelativePath = "assets/$modId/models/item/${item.id}.json"
                            if (resourceRoots.any { it.resolve(modelRelativePath).exists() }) continue

                            val texture = when {
                                textureDir.resolve("${item.id}.png").exists() -> item.id
                                else -> null
                            } ?: continue

                            modelFile.parent.createDirectories()
                            modelFile.writeText(itemModelJson(modId, texture))
                            changes.add(Change(
                                file = modelFile,
                                line = 0,
                                description = "Create missing item model for registered item '${item.id}'",
                                before = "(missing model)",
                                after = modelRelativePath,
                                confidence = Confidence.HIGH,
                                ruleId = "res-create-missing-item-model"
                            ))
                        }
                    }
            }
        } catch (e: Exception) {
            errors.add("Failed to generate missing item models: ${e.message}")
        }
    }

    private fun detectRegisteredItems(projectDir: Path): List<RegisteredItem> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val items = linkedMapOf<String, RegisteredItem>()
        val registerPattern = Regex("""ITEMS\.register\(\s*"([^"]+)"\s*,\s*\(\)\s*->\s*new\s+([\w$.]+)""")
        Files.walk(srcDir).use { sources ->
            sources
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .forEach { file ->
                    val source = file.readText()
                    val code = maskJavaComments(source)
                    val executableCode = maskJavaCommentsAndLiterals(source)
                    registerPattern.findAll(code)
                        .filter { match ->
                            val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                            executableSegment.contains("ITEMS.register(") &&
                                executableSegment.contains("new")
                        }
                        .forEach { match ->
                            val id = match.groupValues[1]
                            items.putIfAbsent(id, RegisteredItem(id))
                        }
                }
        }
        return items.values.toList()
    }

    private fun itemModelJson(modId: String, texture: String): String = """
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "$modId:item/$texture"
  }
}
""".trimIndent() + "\n"

    private data class RegisteredItem(val id: String)

    private data class NitrogenFuelSpriteSpec(
        val namespace: String,
        val menuTexturePath: String,
        val spriteStem: String
    )

    private fun generateLegacyNitrogenFuelSprites(
        projectDir: Path,
        resourceDirs: List<Path>,
        changes: MutableList<Change>,
        errors: MutableList<String>
    ) {
        try {
            val specs = collectNitrogenFuelSpriteSpecs(projectDir, errors)
            if (specs.isEmpty()) return

            val assetRoots = resourceDirs
                .map { it.resolve("assets") }
                .filter { it.exists() }
            if (assetRoots.isEmpty()) return

            specs.forEach { spec ->
                val sourceAssetsDir = assetRoots.firstOrNull { assetsDir ->
                    assetsDir.resolve(spec.namespace).resolve(spec.menuTexturePath).exists()
                }
                if (sourceAssetsDir == null) {
                    errors.add(
                        "Cannot generate Nitrogen fuel sprites for namespace '${spec.namespace}': " +
                            "legacy texture is missing from all resource roots at ${spec.menuTexturePath}"
                    )
                    return@forEach
                }

                val sourceTexture = sourceAssetsDir.resolve(spec.namespace).resolve(spec.menuTexturePath)

                val iconTarget = sourceAssetsDir
                    .resolve(spec.namespace)
                    .resolve("textures/gui/sprites/modporter/nitrogen_fuel_${spec.spriteStem}_icon.png")
                val backgroundTarget = sourceAssetsDir
                    .resolve(spec.namespace)
                    .resolve("textures/gui/sprites/modporter/nitrogen_fuel_${spec.spriteStem}_background.png")

                cropNitrogenFuelSprite(
                    sourceTexture,
                    iconTarget,
                    x = 176,
                    y = 0,
                    width = 14,
                    height = 14,
                    changes = changes,
                    description = "Generate Nitrogen fuel icon sprite from legacy menu texture",
                    ruleId = "res-nitrogen-fuel-icon-sprite"
                )
                cropNitrogenFuelSprite(
                    sourceTexture,
                    backgroundTarget,
                    x = 56,
                    y = 35,
                    width = 14,
                    height = 14,
                    changes = changes,
                    description = "Generate Nitrogen fuel background sprite from legacy menu texture",
                    ruleId = "res-nitrogen-fuel-background-sprite"
                )
            }
        } catch (e: Exception) {
            errors.add("Failed to generate Nitrogen fuel sprites: ${e.message}")
        }
    }

    private fun collectNitrogenFuelSpriteSpecs(
        projectDir: Path,
        errors: MutableList<String>
    ): Set<NitrogenFuelSpriteSpec> {
        val sourceRoots = listOf(
            projectDir.resolve("src/main/java"),
            projectDir.resolve("src/generated/java")
        ).filter { it.exists() }
        if (sourceRoots.isEmpty()) return emptySet()

        val javaSources = sourceRoots.flatMap { root ->
            Files.walk(root).use { files ->
                files
                    .filter { Files.isRegularFile(it) && it.extension == "java" }
                    .toList()
            }
        }
        if (javaSources.isEmpty()) return emptySet()

        val sourceTexts = javaSources.associateWith { it.readText() }
        val commentMaskedSources = sourceTexts.mapValues { (_, source) -> maskJavaComments(source) }
        val executableMaskedSources = sourceTexts.mapValues { (_, source) -> maskJavaCommentsAndLiterals(source) }
        val stringConstants = collectJavaStringConstants(commentMaskedSources.values)
        val specs = linkedSetOf<NitrogenFuelSpriteSpec>()
        val resourceLocationExpression =
            """(?:new\s+ResourceLocation|ResourceLocation\.fromNamespaceAndPath|net\.minecraft\.resources\.ResourceLocation\.fromNamespaceAndPath)"""
        val texturePattern = Regex(
            """\bResourceLocation\s+[A-Za-z_$][\w$]*\s*=\s*$resourceLocationExpression\s*\(\s*([^,\r\n]+?)\s*,\s*"textures/gui/menu/([^"]+?)\.png"\s*\)\s*;"""
        )

        for ((javaFile, source) in commentMaskedSources) {
            if (!containsNitrogenFuelCategoryApiUse(source)) {
                continue
            }
            val executableSource = executableMaskedSources.getValue(javaFile)
            val packageName = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
                .find(source)
                ?.groupValues
                ?.get(1)
                .orEmpty()
            for (match in texturePattern.findAll(source)) {
                val executableSegment = executableSource.substring(match.range.first, match.range.last + 1)
                if (!executableSegment.contains("ResourceLocation") ||
                    (!executableSegment.contains("fromNamespaceAndPath") &&
                        !executableSegment.contains("new ResourceLocation"))
                ) {
                    continue
                }
                val namespaceExpression = match.groupValues[1].trim()
                val namespace = resolveJavaStringExpression(
                    namespaceExpression,
                    stringConstants,
                    source,
                    match.range.first,
                    packageName
                )
                if (namespace == null) {
                    errors.add(
                        "Cannot resolve Nitrogen fuel texture namespace expression '$namespaceExpression' " +
                            "for textures/gui/menu/${match.groupValues[2]}.png"
                    )
                    continue
                }
                val textureTail = match.groupValues[2]
                val spriteStem = textureTail
                    .substringAfterLast('/')
                    .replace(Regex("""[^A-Za-z0-9_]+"""), "_")
                    .trim('_')
                if (spriteStem.isBlank()) {
                    errors.add(
                        "Cannot derive Nitrogen fuel sprite name from legacy texture path " +
                            "textures/gui/menu/$textureTail.png"
                    )
                    continue
                }
                specs += NitrogenFuelSpriteSpec(
                    namespace = namespace,
                    menuTexturePath = "textures/gui/menu/$textureTail.png",
                    spriteStem = spriteStem
                )
            }
        }
        return specs
    }

    private fun containsNitrogenFuelCategoryApiUse(source: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        return containsNitrogenFuelCategoryApiUse(code, "jei") ||
            containsNitrogenFuelCategoryApiUse(code, "rei")
    }

    private fun containsNitrogenFuelCategoryApiUse(code: String, integration: String): Boolean {
        val fqType =
            """com\.aetherteam\.nitrogen\.integration\.${Regex.escape(integration)}\.categories\.fuel\.AbstractFuelCategory"""
        val hasImport = Regex("""(?m)^\s*import\s+$fqType\s*;""").containsMatchIn(code)
        val simpleTypeUse = Regex("""\bextends\s+AbstractFuelCategory\b|\bnew\s+AbstractFuelCategory\s*\(""")
            .containsMatchIn(code)
        val qualifiedTypeUse = Regex("""\bextends\s+$fqType\b|\bnew\s+$fqType\s*\(""")
            .containsMatchIn(code)
        return qualifiedTypeUse || (hasImport && simpleTypeUse)
    }

    private fun collectJavaStringConstants(sources: Iterable<String>): Map<String, String> {
        val constants = linkedMapOf<String, String>()
        val packagePattern = Regex("""(?m)^package\s+([\w.]+)\s*;""")
        val constantPattern = Regex(
            """\b(?:public|protected|private)?\s*static\s+final\s+String\s+([A-Za-z_$][\w$]*)\s*=\s*"([^"]+)""""
        )
        sources.forEach { rawSource ->
            val source = maskJavaComments(rawSource)
            val executableSource = maskJavaCommentsAndLiterals(rawSource)
            val packageName = packagePattern.find(source)?.groupValues?.get(1).orEmpty()
            constantPattern.findAll(source)
                .filter { match ->
                    val executableSegment = executableSource.substring(match.range.first, match.range.last + 1)
                    executableSegment.contains("static") &&
                        executableSegment.contains("final") &&
                        executableSegment.contains("String") &&
                        executableSegment.contains("=")
                }
                .forEach { match ->
                    val name = match.groupValues[1]
                    val value = match.groupValues[2]
                    val className = javaTypeNameContainingOffset(source, match.range.first)
                    if (className != null) {
                        constants["$className.$name"] = value
                        if (packageName.isNotBlank()) {
                            constants["$packageName.$className.$name"] = value
                        }
                    }
                }
        }
        return constants
    }

    private fun resolveJavaStringExpression(
        expression: String,
        constants: Map<String, String>,
        source: String,
        offset: Int,
        packageName: String
    ): String? {
        val trimmed = expression.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.trim('"')
        }
        if (trimmed.contains('.')) {
            return constants[trimmed]
        }
        val owner = javaTypeNameContainingOffset(source, offset) ?: return null
        return constants["$owner.$trimmed"]
            ?: packageName.takeIf { it.isNotBlank() }?.let { constants["$it.$owner.$trimmed"] }
    }

    private fun maskJavaComments(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        while (index < chars.size) {
            when {
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '/' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index < chars.size && chars[index] != '\n' && chars[index] != '\r') {
                        chars[index++] = ' '
                    }
                }
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '*' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index + 1 < chars.size && !(chars[index] == '*' && chars[index + 1] == '/')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 1 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                    }
                }
                else -> index++
            }
        }
        return String(chars)
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val chars = maskJavaComments(source).toCharArray()
        var index = 0
        while (index < chars.size) {
            if (index + 2 < chars.size && chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"') {
                chars[index] = ' '
                chars[index + 1] = ' '
                chars[index + 2] = ' '
                index += 3
                while (index + 2 < chars.size && !(chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"')) {
                    if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                    index++
                }
                if (index + 2 < chars.size) {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    chars[index + 2] = ' '
                    index += 3
                }
            } else if (chars[index] == '"' || chars[index] == '\'') {
                val quote = chars[index]
                chars[index++] = ' '
                var escaped = false
                while (index < chars.size) {
                    val current = chars[index]
                    if (current != '\n' && current != '\r') chars[index] = ' '
                    index++
                    if (escaped) {
                        escaped = false
                    } else if (current == '\\') {
                        escaped = true
                    } else if (current == quote) {
                        break
                    }
                }
            } else {
                index++
            }
        }
        return String(chars)
    }

    private fun cropNitrogenFuelSprite(
        sourceTexture: Path,
        target: Path,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        changes: MutableList<Change>,
        description: String,
        ruleId: String
    ) {
        val sourceImage = ImageIO.read(sourceTexture.toFile())
            ?: error("Cannot read legacy Nitrogen fuel texture: $sourceTexture")
        if (sourceImage.width < x + width || sourceImage.height < y + height) {
            error("Legacy Nitrogen fuel texture is too small for required crop $x,$y ${width}x$height: $sourceTexture")
        }

        val targetImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = targetImage.createGraphics()
        try {
            graphics.drawImage(sourceImage, 0, 0, width, height, x, y, x + width, y + height, null)
        } finally {
            graphics.dispose()
        }

        val existed = target.exists()
        target.parent.createDirectories()
        ImageIO.write(targetImage, "png", target.toFile())
        changes.add(Change(
            file = target,
            line = 0,
            description = description,
            before = if (existed) target.toString() else "(missing sprite)",
            after = "${sourceTexture.fileName}:$x,$y ${width}x$height",
            confidence = Confidence.HIGH,
            ruleId = ruleId
        ))
    }

    private fun normalizeItemTextureMipDimensions(
        assetsDir: Path,
        changes: MutableList<Change>,
        errors: MutableList<String>
    ) {
        try {
            Files.walk(assetsDir)
                .filter { Files.isRegularFile(it) && it.toString().replace('\\', '/').contains("/textures/item/") }
                .filter { it.fileName.toString().endsWith(".png", ignoreCase = true) }
                .forEach { file ->
                    val image = ImageIO.read(file.toFile()) ?: return@forEach
                    val targetWidth = nextMultipleOf16(image.width)
                    val targetHeight = nextMultipleOf16(image.height)
                    if (targetWidth == image.width && targetHeight == image.height) return@forEach

                    val resized = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
                    val graphics = resized.createGraphics()
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
                        graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null)
                    } finally {
                        graphics.dispose()
                    }
                    ImageIO.write(resized, "png", file.toFile())
                    changes.add(Change(
                        file = file,
                        line = 0,
                        description = "Resize item texture to a 16x mipmap-compatible dimension",
                        before = "${image.width}x${image.height}",
                        after = "${targetWidth}x${targetHeight}",
                        confidence = Confidence.HIGH,
                        ruleId = "res-item-texture-mip-dimensions"
                    ))
                }
        } catch (e: Exception) {
            errors.add("Failed to normalize item texture mip dimensions: ${e.message}")
        }
    }

    private fun nextMultipleOf16(value: Int): Int =
        if (value <= 0) 16 else ((value + 15) / 16) * 16

    private fun normalizeCommonTagNamespaces(content: String): String =
        COMMON_TAG_NAMESPACE_PATTERN.replace(content) { match ->
            "${match.groupValues[1]}c:${match.groupValues[2]}"
        }

    private fun detectCodeAwardedAdvancements(projectDir: Path): Map<String, Set<String>> {
        val sourceDirs = listOf(
            projectDir.resolve("src/main/java"),
            projectDir.resolve("src/main/kotlin")
        ).filter { it.exists() }
        if (sourceDirs.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, MutableSet<String>>()
        val constantPattern = Regex("""static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"""")
        val callPattern = Regex("""tryAwardAdvancement\s*\(\s*[^,]+,\s*([^,]+),\s*"([^"]+)"""")

        for (sourceDir in sourceDirs) {
            Files.walk(sourceDir)
                .filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".kt")) }
                .forEach { file ->
                    val content = file.readText()
                    val code = maskJavaComments(content)
                    val executableCode = maskJavaCommentsAndLiterals(content)
                    val packageName = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
                        .find(code)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                    val constants = linkedMapOf<String, String>()
                    constantPattern.findAll(code)
                        .filter { match ->
                            val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                            executableSegment.contains("static") &&
                                executableSegment.contains("final") &&
                                executableSegment.contains("String") &&
                                executableSegment.contains("=")
                        }
                        .forEach { match ->
                            val name = match.groupValues[1]
                            val value = match.groupValues[2]
                            val className = javaTypeNameContainingOffset(code, match.range.first)
                            if (className != null) {
                                constants["$className.$name"] = value
                                if (packageName.isNotBlank()) {
                                    constants["$packageName.$className.$name"] = value
                                }
                            }
                        }

                    callPattern.findAll(code)
                        .filter { match ->
                            executableCode
                                .substring(match.range.first, match.range.last + 1)
                                .contains("tryAwardAdvancement")
                        }
                        .forEach { match ->
                            val rawId = match.groupValues[1].trim()
                            val advancementId = when {
                                rawId.startsWith("\"") && rawId.endsWith("\"") -> rawId.trim('"')
                                rawId.contains(".") -> constants[rawId]
                                else -> javaTypeNameContainingOffset(code, match.range.first)?.let { owner ->
                                    constants["$owner.$rawId"]
                                        ?: if (packageName.isNotBlank()) constants["$packageName.$owner.$rawId"] else null
                                }
                            } ?: return@forEach
                            val criterion = match.groupValues[2]
                            result.getOrPut(advancementId) { linkedSetOf() }.add(criterion)
                        }
                }
        }

        return result.mapValues { it.value.toSet() }
    }

    private fun advancementIdFromPath(dataDir: Path, file: Path): String {
        val relative = dataDir.relativize(file).toString().replace('\\', '/')
        val parts = relative.split('/')
        if (parts.size >= 3 && parts[1] == "advancement") {
            val name = parts.drop(2).joinToString("/").removeSuffix(".json")
            return "${parts[0]}:$name"
        }
        return file.fileName.toString().removeSuffix(".json")
    }

    private fun updatePackFormat(file: Path) {
        var content = file.readText()
        // Data pack format = 48, resource pack format = 34 for MC 1.21.1
        // Use 48 as primary (higher value) with supported_formats range
        content = content.replace(
            Regex(""""pack_format"\s*:\s*\d+"""),
            "\"pack_format\": 48"
        )
        // Add supported_formats range if not already present
        if (!content.contains("supported_formats")) {
            content = content.replace(
                "\"pack_format\": 48",
                "\"pack_format\": 48, \"supported_formats\": {\"min_inclusive\": 34, \"max_inclusive\": 48}"
            )
        }
        file.writeText(content)
    }
}
