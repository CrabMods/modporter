package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.transforms.shared.LegacyPlantRuntimeResources
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Draft BuildSystemPass companion for the canonical legacy-plant runtime.
 *
 * Integration contract:
 * 1. LegacyPlantables.preflight emits `.modporter/legacy-plant-compat.required` only after every
 *    source and dependency-coverage gate has passed.
 * 2. BuildSystemPass calls this after it has converted the owning module to ModDevGradle and before
 *    returning its PassResult.
 * 3. Failure is a pass error and blocks project acceptance. No external repository or publication
 *    is used.
 *
 * Verified ModDevGradle 2.0.140 APIs:
 * - the plugin creates a non-transitive resolvable `jarJar` configuration per source set;
 * - `dependencies.jarJar(project(...))` is supported (Sodium's NeoForge 1.21.1 build uses it);
 * - `net.neoforged.moddevgradle.tasks.JarJar#getOutputDirectory` exposes the generated
 *   META-INF/jarjar tree;
 * - the ordinary `jar` task includes that JarJar task output.
 * - a helper marked `FMLModType: GAMELIBRARY` must remain on the ordinary runtime classpath so
 *   FML can discover it and load it in the game module layer;
 * - `additionalRuntimeClasspath` is reserved for libraries without `FMLModType` and would load
 *   this Minecraft-dependent helper too early in the bootstrap module layer.
 */
internal object BuildSystemLegacyPlantCompat {
    const val RULE_ID = "build-legacy-plant-shared-runtime"
    const val PACKAGE = "com.modporter.compat.plant.v1"
    const val GROUP = "com.modporter.compat"
    const val ARTIFACT = "legacy-plant-compat"
    const val PROJECT_PATH = ":modporterLegacyPlantCompat"
    const val NEOFORGE_VERSION = "21.1.230"

    private const val REQUEST = ".modporter/legacy-plant-compat.required"
    private const val RUNTIME_DIR = ".modporter/legacy-plant-compat"
    private const val JAR_VARIANT_MARKER = "modporterLegacyPlantCompatJarVariant"
    private const val DEV_RUNTIME_MARKER = "modporterLegacyPlantCompatDevRuntime"
    private const val METADATA_MARKER = "modporterLegacyPlantCompatMetadata"
    private const val PACKAGING_VERSION = "1.0.2"
    private const val FORGE_RELEASE = "1.20.1-47.1.33"
    private const val FORGE_COMMIT = "30f255d76c4c2e915414a24e6d8aed42f429817e"
    private const val COMPONENT_RESOURCE_ROOT =
        "META-INF/modporter/legacy-plant-compat"
    private const val PROVENANCE_PATH = "$COMPONENT_RESOURCE_ROOT/provenance.json"
    private const val MIXIN_CONFIG_ROLE =
        "Mixin configuration for the reviewed Dynamic Trees compatibility consumer"

    private data class PackagingIdentity(
        val version: String,
        val jarSha256: String
    )

    private data class RuntimeMaterial(
        val content: String,
        val role: String
    )

    private val knownPackagingIdentities = listOf(
        PackagingIdentity(
            version = "1.0.0-bd2f764a86abe9dc",
            jarSha256 =
                "f93da8ad5fea04a3e85934214a04f605fd6015276bdf36cb4421ca431e739ffa"
        ),
        PackagingIdentity(
            version = "1.0.1-bd2f764a86abe9dc",
            jarSha256 =
                "1866250e5c14060af68293db21947eb3fc43d5503ac66778323ee49e1e5a4e39"
        )
    )

    private val forgeDerivedSources = setOf(
        "PlantType",
        "IPlantable",
        "LegacyPlantables"
    )
    private val dynamicTreesDerivedSources = setOf("LegacyPlantMushroomRotCompat")

    /**
     * This manifest is shipped with ModPorter beside the canonical runtime sources. It is generated
     * by the runtime release job, not by a converted project. Missing or malformed bytes fail closed.
     */
    private val canonicalJarSha256: String by lazy {
        val value = checkNotNull(
            javaClass.getResource("/compat/legacy-plant-compat-v1.jar.sha256")
        ) {
            "Missing canonical legacy plant runtime JAR hash"
        }.readText().trim()
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Malformed canonical legacy plant runtime JAR hash"
        }
        value
    }

    fun install(projectDir: Path, dryRun: Boolean): List<Change> {
        val moduleDir = GradleModLayout.modModule(projectDir)
        val requestCandidates = listOf(
            projectDir.resolve(REQUEST),
            moduleDir.resolve(REQUEST)
        ).distinct().filter { it.exists() }
        if (requestCandidates.isEmpty()) return emptyList()
        require(requestCandidates.size == 1) {
            "Multiple legacy plant runtime requests exist at root and module scope"
        }
        val request = requestCandidates.single()

        val sourceTemplates = LegacyPlantRuntimeResources.javaTemplates()
        val sourceSha256 =
            LegacyPlantRuntimeResources.canonicalSourceHashText(sourceTemplates)
        val requestValues = request.readText().lineSequence()
            .filter { '=' in it }
            .associate { line ->
                line.substringBefore('=').trim() to line.substringAfter('=').trim()
            }
        require(requestValues["package"] == PACKAGE) {
            "Legacy plant runtime request has a non-canonical package"
        }
        require(requestValues["sourceSha256"] == sourceSha256) {
            "Legacy plant runtime request/source hash mismatch"
        }
        require(requestValues["jarSha256"] == canonicalJarSha256) {
            "Legacy plant runtime request/JAR hash mismatch"
        }

        val runtimeVersion = "$PACKAGING_VERSION-${sourceSha256.take(16)}"
        val runtimeRoot = projectDir.resolve(RUNTIME_DIR)
        val resourceRoot = runtimeRoot.resolve("src/main/resources")
        val packagePath = PACKAGE.replace('.', '/')
        val expandedSources = sourceTemplates.mapValues { (_, template) ->
            template
                .replace("@PACKAGE@", PACKAGE)
                .replace("@RUNTIME_SOURCE_SHA256@", sourceSha256)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
        }
        val runtimeResources = LegacyPlantRuntimeResources.expandedResources(sourceSha256)
            .mapValues { (_, content) -> RuntimeMaterial(content, MIXIN_CONFIG_ROLE) }
        val planned = linkedMapOf<Path, String>()
        for ((name, content) in expandedSources.toSortedMap()) {
            planned[
                runtimeRoot.resolve("src/main/java")
                    .resolve(packagePath)
                    .resolve("$name.java")
            ] = content
            planned[
                resourceRoot.resolve(COMPONENT_RESOURCE_ROOT)
                    .resolve("sources")
                    .resolve(packagePath)
                    .resolve("$name.java")
            ] = content
        }
        for ((path, resource) in runtimeResources.toSortedMap()) {
            planned[resourceRoot.resolve(path)] = resource.content
        }
        val settings = uniqueExisting(
            projectDir,
            "settings.gradle",
            "settings.gradle.kts"
        ) ?: projectDir.resolve("settings.gradle")
        val originalSettings = if (settings.exists()) settings.readText() else ""
        planned[settings] = patchSettings(
            originalSettings,
            settings.fileName.toString().endsWith(".kts")
        )

        val buildFile = uniqueExisting(moduleDir, "build.gradle", "build.gradle.kts")
            ?: error("Legacy plant runtime requires one owning module build script")
        val owningBuild = buildFile.readText()
        val modDevVersion = detectModDevVersion(owningBuild)
        val buildRecipe = reproducibleBuildGradle(runtimeVersion, modDevVersion)
        val materials = linkedMapOf(
            "META-INF/modporter/LICENSE" to RuntimeMaterial(
                requiredResourceText("/META-INF/modporter/LICENSE"),
                "Apache-2.0 license for ModPorter helper sources"
            ),
            "META-INF/modporter/NOTICE" to RuntimeMaterial(
                requiredResourceText("/META-INF/modporter/NOTICE"),
                "ModPorter distribution notice"
            ),
            "META-INF/modporter/third-party/minecraftforge/LICENSE.txt" to RuntimeMaterial(
                requiredResourceText(
                    "/META-INF/modporter/third-party/minecraftforge/LICENSE.txt"
                ),
                "LGPL-2.1-only license for Forge-derived sources"
            ),
            "META-INF/modporter/third-party/minecraftforge/README.md" to RuntimeMaterial(
                requiredResourceText(
                    "/META-INF/modporter/third-party/minecraftforge/README.md"
                ),
                "Minecraft Forge component notice and pinned provenance"
            ),
            "META-INF/modporter/third-party/dynamictrees/LICENSE" to RuntimeMaterial(
                requiredResourceText(
                    "/META-INF/modporter/third-party/dynamictrees/LICENSE"
                ),
                "MIT license for Dynamic Trees-derived sources"
            ),
            "META-INF/modporter/third-party/dynamictrees/README.md" to RuntimeMaterial(
                requiredResourceText(
                    "/META-INF/modporter/third-party/dynamictrees/README.md"
                ),
                "Dynamic Trees component notice and pinned provenance"
            ),
            "$COMPONENT_RESOURCE_ROOT/build.gradle.recipe" to RuntimeMaterial(
                buildRecipe,
                "Reproducible Gradle build recipe without an expected output hash"
            ),
            "$COMPONENT_RESOURCE_ROOT/BUILD.md" to RuntimeMaterial(
                buildInstructions(runtimeVersion),
                "Minimal source and resource reconstruction instructions"
            )
        )
        for ((path, material) in materials) {
            planned[resourceRoot.resolve(path)] = material.content
        }
        planned[resourceRoot.resolve(PROVENANCE_PATH)] = provenanceManifest(
            runtimeVersion = runtimeVersion,
            sourceSha256 = sourceSha256,
            expandedSources = expandedSources,
            runtimeResources = runtimeResources,
            materials = materials
        )
        planned[runtimeRoot.resolve("build.gradle")] =
            auxiliaryBuildGradle(
                runtimeVersion,
                canonicalJarSha256,
                modDevVersion,
                buildRecipe
            )
        planned[buildFile] = patchOwningBuild(
            owningBuild,
            buildFile.fileName.toString().endsWith(".kts"),
            runtimeVersion,
            canonicalJarSha256
        )

        val changes = planned.mapNotNull { (file, content) ->
            val before = if (file.exists()) file.readText() else null
            if (before == content) return@mapNotNull null
            Change(
                file = file,
                line = 1,
                description = "Install canonical shared legacy plant Jar-in-Jar runtime",
                before = before?.take(160) ?: "(missing)",
                after = content.take(160),
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
        if (!dryRun) {
            for ((file, content) in planned) {
                file.parent.toFile().mkdirs()
                file.writeText(content)
            }
        }
        return changes
    }

    private fun patchSettings(original: String, kotlinDsl: Boolean): String {
        if (Regex("""(?m)^\s*include\s*\(?\s*["']:modporterLegacyPlantCompat["']""")
                .containsMatchIn(original)
        ) {
            require(original.contains(RUNTIME_DIR)) {
                "Existing legacy plant auxiliary project points at an unverified directory"
            }
            return original
        }
        val separator = if ("\r\n" in original) "\r\n" else "\n"
        val block = if (kotlinDsl) {
            """
            include("$PROJECT_PATH")
            project("$PROJECT_PATH").projectDir = file("$RUNTIME_DIR")
            """.trimIndent()
        } else {
            """
            include('$PROJECT_PATH')
            project('$PROJECT_PATH').projectDir = file('$RUNTIME_DIR')
            """.trimIndent()
        }
        return original.trimEnd() + separator + separator + block + separator
    }

    private fun patchOwningBuild(
        original: String,
        kotlinDsl: Boolean,
        runtimeVersion: String,
        jarSha256: String
    ): String {
        require(original.contains("net.neoforged.moddev")) {
            "Legacy plant runtime packaging must run after ModDevGradle conversion"
        }
        require(!original.contains("$GROUP:$ARTIFACT") || original.contains(PROJECT_PATH)) {
            "An external legacy plant runtime dependency is forbidden"
        }
        val devRuntimeBlock = if (kotlinDsl) {
            """
            // $DEV_RUNTIME_MARKER
            dependencies {
                add("additionalRuntimeClasspath", project("$PROJECT_PATH")) {
                    attributes {
                        attribute(
                            org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            objects.named(
                                org.gradle.api.attributes.LibraryElements::class.java,
                                org.gradle.api.attributes.LibraryElements.JAR
                            )
                        )
                    }
                    capabilities {
                        requireCapability("$GROUP:$ARTIFACT")
                    }
                }
            }
            """.trimIndent()
        } else {
            """
            // $DEV_RUNTIME_MARKER
            dependencies {
                add('additionalRuntimeClasspath', project('$PROJECT_PATH')) {
                    attributes {
                        attribute(
                            org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            objects.named(
                                org.gradle.api.attributes.LibraryElements,
                                org.gradle.api.attributes.LibraryElements.JAR
                            )
                        )
                    }
                    capabilities {
                        requireCapability('$GROUP:$ARTIFACT')
                    }
                }
            }
            """.trimIndent()
        }
        if (original.contains(METADATA_MARKER)) {
            val installed = migrateKnownPackagingIdentity(
                original,
                runtimeVersion,
                jarSha256
            )
            val withoutLegacyDevRuntime = removeKnownDevelopmentRuntimeBlock(
                installed,
                devRuntimeBlock
            )
            val jarVariantCount = installed.lineSequence().count {
                JAR_VARIANT_MARKER in it
            }
            require(
                jarVariantCount == 2 &&
                    withoutLegacyDevRuntime.lineSequence().count {
                        "LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE" in it
                    } >= 2
            ) {
                "Existing legacy plant runtime dependencies do not select the canonical JAR variant"
            }
            require(occurrenceCount(withoutLegacyDevRuntime, PROJECT_PATH) == 2) {
                "Existing legacy plant runtime has unexpected auxiliary project references"
            }
            return withoutLegacyDevRuntime
        }
        require(
            original.lineSequence().none {
                JAR_VARIANT_MARKER in it || DEV_RUNTIME_MARKER in it
            }
        ) {
            "Partial legacy plant runtime dependency installation has no verified metadata"
        }
        val dependencyBlock = Regex("""(?m)^\s*dependencies\s*\{""").find(original)
            ?: error("Owning module has no dependencies block")
        val packagingDependencies = if (kotlinDsl) {
            """

                implementation(project("$PROJECT_PATH")) {
                    // $JAR_VARIANT_MARKER implementation
                    attributes {
                        attribute(
                            org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            objects.named(
                                org.gradle.api.attributes.LibraryElements::class.java,
                                org.gradle.api.attributes.LibraryElements.JAR
                            )
                        )
                    }
                    capabilities {
                        requireCapability("$GROUP:$ARTIFACT")
                    }
                }
                jarJar(project("$PROJECT_PATH")) {
                    // $JAR_VARIANT_MARKER jarJar
                    attributes {
                        attribute(
                            org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            objects.named(
                                org.gradle.api.attributes.LibraryElements::class.java,
                                org.gradle.api.attributes.LibraryElements.JAR
                            )
                        )
                    }
                    capabilities {
                        requireCapability("$GROUP:$ARTIFACT")
                    }
                }
            """.trimEnd()
        } else {
            """

                implementation(project('$PROJECT_PATH')) {
                    // $JAR_VARIANT_MARKER implementation
                    attributes {
                        attribute(
                            org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            objects.named(
                                org.gradle.api.attributes.LibraryElements,
                                org.gradle.api.attributes.LibraryElements.JAR
                            )
                        )
                    }
                    capabilities {
                        requireCapability('$GROUP:$ARTIFACT')
                    }
                }
                jarJar(project('$PROJECT_PATH')) {
                    // $JAR_VARIANT_MARKER jarJar
                    attributes {
                        attribute(
                            org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            objects.named(
                                org.gradle.api.attributes.LibraryElements,
                                org.gradle.api.attributes.LibraryElements.JAR
                            )
                        )
                    }
                    capabilities {
                        requireCapability('$GROUP:$ARTIFACT')
                    }
                }
            """.trimEnd()
        }
        val withDependencies = original.substring(
            0,
            dependencyBlock.range.last + 1
        ) + packagingDependencies + original.substring(dependencyBlock.range.last + 1)

        val metadataPatch = if (kotlinDsl) {
            kotlinMetadataPatch(runtimeVersion, jarSha256)
        } else {
            groovyMetadataPatch(runtimeVersion, jarSha256)
        }
        return withDependencies.trimEnd() +
            "\n\n" +
            metadataPatch +
            "\n"
    }

    private fun removeKnownDevelopmentRuntimeBlock(
        content: String,
        knownBlock: String
    ): String {
        val markerCount = content.lineSequence().count {
            DEV_RUNTIME_MARKER in it
        }
        require(markerCount in 0..1) {
            "Existing legacy plant runtime has duplicate development runtime dependencies"
        }
        if (markerCount == 0) {
            return content
        }

        require(occurrenceCount(content, knownBlock) == 1) {
            "Existing legacy plant development runtime block was modified"
        }
        val start = content.indexOf(knownBlock)
        val end = start + knownBlock.length
        val prefix = content.substring(0, start).trimEnd()
        val suffix = content.substring(end).trimStart()
        return if (suffix.isEmpty()) {
            "$prefix\n"
        } else {
            "$prefix\n\n${suffix.trimEnd()}\n"
        }
    }

    private fun migrateKnownPackagingIdentity(
        original: String,
        runtimeVersion: String,
        jarSha256: String
    ): String {
        require(original.lineSequence().count { METADATA_MARKER in it } == 1) {
            "Existing legacy plant runtime metadata marker is missing or duplicated"
        }
        val currentIdentity = PackagingIdentity(runtimeVersion, jarSha256)
        val acceptedIdentities = listOf(currentIdentity) + knownPackagingIdentities
        val matchingVersions = acceptedIdentities.filter {
            occurrenceCount(original, it.version) == 3
        }
        if (matchingVersions.size != 1) {
            error("Existing legacy plant runtime packaging has an unknown or mixed version")
        }
        val installedIdentity = matchingVersions.single()
        require(occurrenceCount(original, installedIdentity.jarSha256) == 1) {
            "Known legacy plant packaging version has an unrecognized JAR hash"
        }
        acceptedIdentities
            .map { it.version }
            .distinct()
            .filter { it != installedIdentity.version }
            .forEach { version ->
                require(occurrenceCount(original, version) == 0) {
                    "Existing legacy plant runtime packaging mixes known versions"
                }
            }
        acceptedIdentities
            .map { it.jarSha256 }
            .distinct()
            .filter { it != installedIdentity.jarSha256 }
            .forEach { hash ->
                require(occurrenceCount(original, hash) == 0) {
                    "Existing legacy plant runtime packaging mixes known JAR hashes"
                }
            }
        if (installedIdentity == currentIdentity) {
            return original
        }
        return original
            .replace(installedIdentity.version, runtimeVersion)
            .replace(installedIdentity.jarSha256, jarSha256)
    }

    private fun occurrenceCount(content: String, value: String): Int =
        Regex(Regex.escape(value)).findAll(content).count()

    private fun groovyMetadataPatch(runtimeVersion: String, jarSha256: String): String = """
        // $METADATA_MARKER
        tasks.named('jarJar', net.neoforged.moddevgradle.tasks.JarJar).configure {
            doLast {
                def metadataFile = outputDirectory.file('META-INF/jarjar/metadata.json').get().asFile
                if (!metadataFile.isFile()) throw new GradleException('Missing ModDev JarJar metadata')
                def metadata = new groovy.json.JsonSlurper().parse(metadataFile)
                def matches = metadata.jars.findAll {
                    it.identifier.group == '$GROUP' && it.identifier.artifact == '$ARTIFACT'
                }
                if (matches.size() != 1) {
                    throw new GradleException('Expected one canonical legacy plant JarJar entry')
                }
                def entry = matches[0]
                if (entry.version.artifactVersion != '$runtimeVersion') {
                    throw new GradleException('Legacy plant JarJar artifact version changed')
                }
                entry.version.range = '[$runtimeVersion,$runtimeVersion]'
                def embedded = outputDirectory.file(entry.path).get().asFile
                if (!embedded.isFile()) throw new GradleException('Missing embedded legacy plant JAR')
                def digest = java.security.MessageDigest.getInstance('SHA-256')
                    .digest(embedded.bytes).encodeHex().toString()
                if (digest != '$jarSha256') {
                    throw new GradleException('Legacy plant runtime JAR hash changed: ' + digest)
                }
                metadataFile.text = groovy.json.JsonOutput.toJson(metadata)
            }
        }
    """.trimIndent()

    private fun kotlinMetadataPatch(runtimeVersion: String, jarSha256: String): String = """
        // $METADATA_MARKER
        tasks.named<net.neoforged.moddevgradle.tasks.JarJar>("jarJar") {
            doLast {
                val metadataFile = outputDirectory.file("META-INF/jarjar/metadata.json").get().asFile
                if (!metadataFile.isFile) throw GradleException("Missing ModDev JarJar metadata")
                @Suppress("UNCHECKED_CAST")
                val metadata = groovy.json.JsonSlurper().parse(metadataFile) as MutableMap<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val jars = metadata["jars"] as MutableList<MutableMap<String, Any?>>
                val matches = jars.filter { entry ->
                    @Suppress("UNCHECKED_CAST")
                    val identifier = entry["identifier"] as Map<String, Any?>
                    identifier["group"] == "$GROUP" && identifier["artifact"] == "$ARTIFACT"
                }
                if (matches.size != 1) {
                    throw GradleException("Expected one canonical legacy plant JarJar entry")
                }
                val entry = matches.single()
                @Suppress("UNCHECKED_CAST")
                val version = entry["version"] as MutableMap<String, Any?>
                if (version["artifactVersion"] != "$runtimeVersion") {
                    throw GradleException("Legacy plant JarJar artifact version changed")
                }
                version["range"] = "[$runtimeVersion,$runtimeVersion]"
                val embedded = outputDirectory.file(entry["path"] as String).get().asFile
                if (!embedded.isFile) throw GradleException("Missing embedded legacy plant JAR")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(embedded.readBytes()).joinToString("") { "%02x".format(it) }
                if (digest != "$jarSha256") {
                    throw GradleException("Legacy plant runtime JAR hash changed: ${'$'}digest")
                }
                metadataFile.writeText(groovy.json.JsonOutput.toJson(metadata))
            }
        }
    """.trimIndent()

    private fun auxiliaryBuildGradle(
        runtimeVersion: String,
        jarSha256: String,
        modDevVersion: String,
        buildRecipe: String
    ): String = buildRecipe.trimEnd() + """

        tasks.named('jar', Jar).configure {
            doLast {
                def digest = java.security.MessageDigest.getInstance('SHA-256')
                    .digest(archiveFile.get().asFile.bytes).encodeHex().toString()
                if (digest != '$jarSha256') {
                    throw new GradleException('Canonical legacy plant runtime JAR hash changed: ' + digest)
                }
            }
        }
    """.trimIndent() + "\n"

    private fun reproducibleBuildGradle(
        runtimeVersion: String,
        modDevVersion: String
    ): String = """
        plugins {
            id 'java-library'
            id 'net.neoforged.moddev' version '$modDevVersion'
        }

        group = '$GROUP'
        version = '$runtimeVersion'

        base {
            archivesName = '$ARTIFACT'
        }

        configurations.named('apiElements') {
            outgoing.capability('$GROUP:$ARTIFACT:$runtimeVersion')
        }

        configurations.named('runtimeElements') {
            outgoing.capability('$GROUP:$ARTIFACT:$runtimeVersion')
        }

        java {
            toolchain {
                languageVersion = JavaLanguageVersion.of(21)
            }
        }

        neoForge {
            version = '$NEOFORGE_VERSION'
        }

        tasks.withType(AbstractArchiveTask).configureEach {
            preserveFileTimestamps = false
            reproducibleFileOrder = true
        }

        tasks.named('jar', Jar).configure {
            manifest {
                attributes(
                    'Automatic-Module-Name': '$PACKAGE',
                    'FMLModType': 'GAMELIBRARY',
                    'MixinConfigs': 'modporter-legacy-plant-dt.mixins.json'
                )
            }
        }
    """.trimIndent() + "\n"

    private fun buildInstructions(runtimeVersion: String): String = """
        # Rebuilding the shared legacy-plant runtime

        This archive contains every expanded Java source, every runtime resource, and every
        required legal material used to build `$GROUP:$ARTIFACT:$runtimeVersion`.

        1. Create an empty Gradle project.
        2. Copy each provenance `sources[].embeddedPath` entry to its matching
           `sources[].generatedPath` entry.
        3. Keep every `sources[].embeddedPath`, every `runtimeResources[].path`, every
           `materials[].path`, and `provenance.json` beneath `src/main/resources/` at
           the same archive path.
        4. Also save the embedded `build.gradle.recipe` as the project `build.gradle`.
        5. Run `gradle --no-daemon --no-build-cache clean jar` with JDK 21.

        The recipe pins ModDevGradle and NeoForge, disables archive timestamps,
        and uses reproducible archive ordering. It intentionally contains no
        expected output JAR hash because embedding that hash would be circular.
    """.trimIndent() + "\n"

    private fun provenanceManifest(
        runtimeVersion: String,
        sourceSha256: String,
        expandedSources: Map<String, String>,
        runtimeResources: Map<String, RuntimeMaterial>,
        materials: Map<String, RuntimeMaterial>
    ): String {
        val packagePath = PACKAGE.replace('.', '/')
        val sourceEntries = expandedSources.toSortedMap().map { (name, content) ->
            val (license, origin) = when (name) {
                in forgeDerivedSources -> "LGPL-2.1-only" to "MinecraftForge"
                in dynamicTreesDerivedSources -> "MIT" to "DynamicTrees"
                else -> "Apache-2.0" to "ModPorter"
            }
            val generatedPath = "src/main/java/$packagePath/$name.java"
            val embeddedPath =
                "$COMPONENT_RESOURCE_ROOT/sources/$packagePath/$name.java"
            """
                {
                  "name": ${jsonString(name)},
                  "generatedPath": ${jsonString(generatedPath)},
                  "embeddedPath": ${jsonString(embeddedPath)},
                  "sha256": ${jsonString(sha256(content))},
                  "license": ${jsonString(license)},
                  "origin": ${jsonString(origin)}
                }
            """.trimIndent()
        }.joinToString(",\n")
        val runtimeResourceEntries = runtimeResources.toSortedMap()
            .map { (path, resource) ->
                """
                    {
                      "path": ${jsonString(path)},
                      "sha256": ${jsonString(sha256(resource.content))},
                      "role": ${jsonString(resource.role)}
                    }
                """.trimIndent()
            }.joinToString(",\n")
        val materialEntries = materials.toSortedMap().map { (path, material) ->
            """
                {
                  "path": ${jsonString(path)},
                  "sha256": ${jsonString(sha256(material.content))},
                  "role": ${jsonString(material.role)}
                }
            """.trimIndent()
        }.joinToString(",\n")
        return """
            {
              "schemaVersion": 1,
              "component": ${jsonString("$GROUP:$ARTIFACT")},
              "packagingVersion": ${jsonString(PACKAGING_VERSION)},
              "runtimeVersion": ${jsonString(runtimeVersion)},
              "templateSourceSha": ${jsonString(sourceSha256)},
              "forgeRelease": ${jsonString(FORGE_RELEASE)},
              "forgeCommit": ${jsonString(FORGE_COMMIT)},
              "licenseClassification": "per-file",
              "sources": [
            ${sourceEntries.prependIndent("    ")}
              ],
              "runtimeResources": [
            ${runtimeResourceEntries.prependIndent("    ")}
              ],
              "materials": [
            ${materialEntries.prependIndent("    ")}
              ]
            }
        """.trimIndent() + "\n"
    }

    private fun requiredResourceText(path: String): String = checkNotNull(
        javaClass.getResource(path)
    ) {
        "Missing required legacy plant runtime material $path"
    }.readText()

    private fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun detectModDevVersion(owningBuild: String): String {
        val version = Regex(
            """id\s*\(?\s*["']net\.neoforged\.moddev["']\s*\)?\s*version\s*\(?\s*["']([^"']+)["']"""
        ).find(owningBuild)?.groupValues?.get(1)
            ?: error(
                "Cannot verify the owning ModDevGradle plugin version for the auxiliary runtime"
            )
        require(version.matches(Regex("""[0-9A-Za-z.+_-]+"""))) {
            "Unsafe ModDevGradle plugin version literal"
        }
        return version
    }

    private fun uniqueExisting(root: Path, vararg names: String): Path? {
        val existing = names.map(root::resolve).filter { it.exists() }
        require(existing.size <= 1) {
            "Cannot install legacy plant runtime with multiple competing ${names.joinToString()}"
        }
        return existing.singleOrNull()
    }
}
