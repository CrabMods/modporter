package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.GradleModLayout
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
    private const val PACKAGING_VERSION = "1.0.1"
    private const val PRE_GAME_LIBRARY_VERSION = "1.0.0-bd2f764a86abe9dc"
    private const val PRE_GAME_LIBRARY_JAR_SHA256 =
        "f93da8ad5fea04a3e85934214a04f605fd6015276bdf36cb4421ca431e739ffa"

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

        val sourceTemplates = canonicalRuntimeSources()
        val sourceSha256 = canonicalSourceHash(sourceTemplates)
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
        val planned = linkedMapOf<Path, String>()
        for ((name, template) in sourceTemplates) {
            val content = template
                .replace("@PACKAGE@", PACKAGE)
                .replace("@RUNTIME_SOURCE_SHA256@", sourceSha256)
            planned[
                runtimeRoot.resolve("src/main/java")
                    .resolve(PACKAGE.replace('.', '/'))
                    .resolve("$name.java")
            ] = content
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
        planned[runtimeRoot.resolve("build.gradle")] =
            auxiliaryBuildGradle(runtimeVersion, canonicalJarSha256, modDevVersion)
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
        val currentVersionCount = occurrenceCount(original, runtimeVersion)
        val previousVersionCount = occurrenceCount(original, PRE_GAME_LIBRARY_VERSION)
        val currentHashCount = occurrenceCount(original, jarSha256)
        val previousHashCount = occurrenceCount(
            original,
            PRE_GAME_LIBRARY_JAR_SHA256
        )
        return when {
            currentVersionCount == 3 && previousVersionCount == 0 -> {
                require(currentHashCount == 1) {
                    "Existing legacy plant runtime packaging has a different JAR hash"
                }
                if (jarSha256 != PRE_GAME_LIBRARY_JAR_SHA256) {
                    require(previousHashCount == 0) {
                        "Existing legacy plant runtime packaging mixes old and current JAR hashes"
                    }
                }
                original
            }
            previousVersionCount == 3 && currentVersionCount == 0 -> {
                require(previousHashCount == 1) {
                    "Known legacy plant packaging version has an unrecognized JAR hash"
                }
                if (jarSha256 != PRE_GAME_LIBRARY_JAR_SHA256) {
                    require(currentHashCount == 0) {
                        "Existing legacy plant runtime packaging mixes old and current JAR hashes"
                    }
                }
                original
                    .replace(PRE_GAME_LIBRARY_VERSION, runtimeVersion)
                    .replace(PRE_GAME_LIBRARY_JAR_SHA256, jarSha256)
            }
            else -> error(
                "Existing legacy plant runtime packaging has an unknown or mixed version"
            )
        }
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
                    'FMLModType': 'GAMELIBRARY'
                )
            }
            doLast {
                def digest = java.security.MessageDigest.getInstance('SHA-256')
                    .digest(archiveFile.get().asFile.bytes).encodeHex().toString()
                if (digest != '$jarSha256') {
                    throw new GradleException('Canonical legacy plant runtime JAR hash changed: ' + digest)
                }
            }
        }
    """.trimIndent() + "\n"

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

    private fun canonicalRuntimeSources(): Map<String, String> {
        val names = listOf(
            "PlantType",
            "IPlantable",
            "LegacyPlantSustainer",
            "LegacyPlantPlacement",
            "LegacyPlantableAdapter",
            "LegacySoilAdapter",
            "LegacyPlantPlacementAdapter",
            "LegacyPlantables"
        )
        return names.associateWith { name ->
            checkNotNull(javaClass.getResource("/compat/$name.java.template")) {
                "Missing canonical legacy plant runtime source $name"
            }.readText()
        }
    }

    private fun canonicalSourceHash(sources: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, content) in sources.toSortedMap()) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(content.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun uniqueExisting(root: Path, vararg names: String): Path? {
        val existing = names.map(root::resolve).filter { it.exists() }
        require(existing.size <= 1) {
            "Cannot install legacy plant runtime with multiple competing ${names.joinToString()}"
        }
        return existing.singleOrNull()
    }
}
