package com.modporter.transforms

import com.google.gson.JsonParser
import com.modporter.core.transforms.build.BuildSystemLegacyPlantCompat
import com.modporter.core.transforms.shared.LegacyPlantRuntimeResources
import com.modporter.core.transforms.structural.CanonicalRuntimeIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BuildSystemLegacyPlantCompatPackagingTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `runtime carries sources resources legal materials provenance and a non-circular recipe`() {
        val identity = CanonicalRuntimeIdentity.fromProductionResources()
        val javaTemplates = LegacyPlantRuntimeResources.javaTemplates()
        val expectedJavaNames = setOf(
            "PlantType",
            "IPlantable",
            "LegacyPlantSustainer",
            "LegacyPlantPlacement",
            "LegacyPlantableAdapter",
            "LegacySoilAdapter",
            "LegacyPlantPlacementAdapter",
            "LegacyPlantables",
            "LegacyPlantAdapterProfile",
            "ReviewedExternalPlantAdapters",
            "LegacyPlantMixinPlugin",
            "LegacyPlantMushroomRotCompat",
            "mixin/LegacyPlantMushroomRotMixin",
            "mixin/LegacyPlantPluginSentinel"
        )
        assertEquals(14, LegacyPlantRuntimeResources.JAVA_NAMES.size)
        assertEquals(expectedJavaNames, LegacyPlantRuntimeResources.JAVA_NAMES)
        assertEquals(LegacyPlantRuntimeResources.JAVA_NAMES, javaTemplates.keys)
        assertEquals(
            setOf("modporter-legacy-plant-dt.mixins.json"),
            LegacyPlantRuntimeResources.resourceTemplates().keys
        )
        assertEquals(
            identity.sourceSha256,
            canonicalWholeClosureSourceHash(
                javaTemplates,
                LegacyPlantRuntimeResources.resourceTemplates()
            )
        )
        assertEquals(
            identity.sourceSha256,
            LegacyPlantRuntimeResources.canonicalSourceHashText(javaTemplates)
        )
        assertEquals(
            identity.sourceSha256,
            LegacyPlantRuntimeResources.canonicalSourceHashBytes(
                javaTemplates.mapValues { (_, content) ->
                    content.toByteArray(Charsets.UTF_8)
                }
            )
        )
        assertFalse(identity.sourceSha256 == canonicalJavaOnlySourceHash(javaTemplates))
        directory.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev' version '2.0.140'
            }

            dependencies {
            }
            """.trimIndent() + "\n"
        )
        val request = directory.resolve(".modporter/legacy-plant-compat.required")
        request.parent.createDirectories()
        request.writeText(identity.runtimeRequest())

        assertTrue(
            BuildSystemLegacyPlantCompat.install(directory, dryRun = false).isNotEmpty()
        )

        val runtimeRoot = directory.resolve(".modporter/legacy-plant-compat")
        val resourceRoot = runtimeRoot.resolve("src/main/resources")
        val provenanceFile = resourceRoot.resolve(
            "META-INF/modporter/legacy-plant-compat/provenance.json"
        )
        val provenanceText = provenanceFile.readText()
        val provenance = JsonParser.parseString(provenanceText).asJsonObject
        assertEquals(1, provenance["schemaVersion"].asInt)
        assertEquals(
            "com.modporter.compat:legacy-plant-compat",
            provenance["component"].asString
        )
        assertEquals("1.0.2", provenance["packagingVersion"].asString)
        assertTrue(provenance["runtimeVersion"].asString.startsWith("1.0.2-"))
        assertEquals(identity.sourceSha256, provenance["templateSourceSha"].asString)
        assertEquals("1.20.1-47.1.33", provenance["forgeRelease"].asString)
        assertEquals(
            "30f255d76c4c2e915414a24e6d8aed42f429817e",
            provenance["forgeCommit"].asString
        )
        assertEquals("per-file", provenance["licenseClassification"].asString)
        assertFalse(identity.jarSha256 in provenanceText)

        val sources = provenance.getAsJsonArray("sources")
        val generatedPaths = Files.walk(runtimeRoot.resolve("src/main/java")).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .map { runtimeRoot.relativize(it).toString().replace('\\', '/') }
                .toList()
                .toSet()
        }
        assertEquals(14, generatedPaths.size)
        assertEquals(generatedPaths.size, sources.size())
        assertEquals(
            LegacyPlantRuntimeResources.JAVA_NAMES,
            sources.map { it.asJsonObject["name"].asString }.toSet()
        )
        assertEquals(
            generatedPaths,
            sources.map { it.asJsonObject["generatedPath"].asString }.toSet()
        )
        for (entry in sources.map { it.asJsonObject }) {
            val name = entry["name"].asString
            val generated = runtimeRoot.resolve(entry["generatedPath"].asString)
            val embedded = resourceRoot.resolve(entry["embeddedPath"].asString)
            assertTrue(generated.exists())
            assertTrue(embedded.exists())
            assertArrayEquals(generated.readBytes(), embedded.readBytes())
            assertEquals(entry["sha256"].asString, sha256(generated.readBytes()))
            when (name) {
                "PlantType", "IPlantable", "LegacyPlantables" -> {
                    assertEquals("LGPL-2.1-only", entry["license"].asString)
                    assertEquals("MinecraftForge", entry["origin"].asString)
                }
                "LegacyPlantMushroomRotCompat" -> {
                    assertEquals("MIT", entry["license"].asString)
                    assertEquals("DynamicTrees", entry["origin"].asString)
                }
                else -> {
                    assertEquals("Apache-2.0", entry["license"].asString)
                    assertEquals("ModPorter", entry["origin"].asString)
                }
            }
        }

        val expectedRuntimeResourceRoles = mapOf(
            "modporter-legacy-plant-dt.mixins.json" to
                "Mixin configuration for the reviewed Dynamic Trees compatibility consumer"
        )
        val runtimeResources = provenance.getAsJsonArray("runtimeResources")
            .map { it.asJsonObject }
            .associateBy { it["path"].asString }
        assertEquals(expectedRuntimeResourceRoles.keys, runtimeResources.keys)
        val expandedRuntimeResources =
            LegacyPlantRuntimeResources.expandedResources(identity.sourceSha256)
        for ((runtimePath, role) in expectedRuntimeResourceRoles) {
            val generated = resourceRoot.resolve(runtimePath)
            val entry = runtimeResources.getValue(runtimePath)
            assertTrue(generated.exists())
            assertEquals(expandedRuntimeResources.getValue(runtimePath), generated.readText())
            assertEquals(role, entry["role"].asString)
            assertEquals(entry["sha256"].asString, sha256(generated.readBytes()))
            assertFalse("@PACKAGE@" in generated.readText())
            val mixinConfig = JsonParser.parseString(generated.readText()).asJsonObject
            assertTrue(mixinConfig["required"].asBoolean)
            assertEquals("0.8.7", mixinConfig["minVersion"].asString)
            assertEquals(
                "com.modporter.compat.plant.v1.mixin",
                mixinConfig["package"].asString
            )
            assertEquals("JAVA_21", mixinConfig["compatibilityLevel"].asString)
            assertEquals(
                "com.modporter.compat.plant.v1.LegacyPlantMixinPlugin",
                mixinConfig["plugin"].asString
            )
            assertEquals(
                listOf("LegacyPlantPluginSentinel"),
                mixinConfig.getAsJsonArray("mixins").map { it.asString }
            )
            assertEquals(
                1,
                mixinConfig.getAsJsonObject("injectors")["defaultRequire"].asInt
            )
        }

        val expectedMaterialRoles = mapOf(
            "META-INF/modporter/LICENSE" to
                "Apache-2.0 license for ModPorter helper sources",
            "META-INF/modporter/NOTICE" to
                "ModPorter distribution notice",
            "META-INF/modporter/third-party/minecraftforge/LICENSE.txt" to
                "LGPL-2.1-only license for Forge-derived sources",
            "META-INF/modporter/third-party/minecraftforge/README.md" to
                "Minecraft Forge component notice and pinned provenance",
            "META-INF/modporter/third-party/dynamictrees/LICENSE" to
                "MIT license for Dynamic Trees-derived sources",
            "META-INF/modporter/third-party/dynamictrees/README.md" to
                "Dynamic Trees component notice and pinned provenance",
            "META-INF/modporter/legacy-plant-compat/build.gradle.recipe" to
                "Reproducible Gradle build recipe without an expected output hash",
            "META-INF/modporter/legacy-plant-compat/BUILD.md" to
                "Minimal source and resource reconstruction instructions"
        )
        val materials = provenance.getAsJsonArray("materials")
            .map { it.asJsonObject }
            .associateBy { it["path"].asString }
        assertEquals(expectedMaterialRoles.keys, materials.keys)
        for ((path, role) in expectedMaterialRoles) {
            val generated = resourceRoot.resolve(path)
            val entry = materials.getValue(path)
            assertTrue(generated.exists())
            assertEquals(role, entry["role"].asString)
            assertEquals(entry["sha256"].asString, sha256(generated.readBytes()))
            if (!path.startsWith("META-INF/modporter/legacy-plant-compat/")) {
                assertArrayEquals(classpathResource(path), generated.readBytes())
            }
        }

        val buildRecipe = resourceRoot.resolve(
            "META-INF/modporter/legacy-plant-compat/build.gradle.recipe"
        ).readText()
        assertTrue("net.neoforged.moddev' version '2.0.140" in buildRecipe)
        assertTrue("version = '21.1.230'" in buildRecipe)
        assertTrue("preserveFileTimestamps = false" in buildRecipe)
        assertTrue("reproducibleFileOrder = true" in buildRecipe)
        assertTrue(
            "'MixinConfigs': 'modporter-legacy-plant-dt.mixins.json'" in buildRecipe
        )
        assertFalse("doLast" in buildRecipe)
        assertFalse(identity.jarSha256 in buildRecipe)

        val auxiliaryBuild = runtimeRoot.resolve("build.gradle").readText()
        assertTrue(identity.jarSha256 in auxiliaryBuild)
        assertTrue("doLast" in auxiliaryBuild)
        assertTrue(
            "'MixinConfigs': 'modporter-legacy-plant-dt.mixins.json'" in auxiliaryBuild
        )
    }

    private fun classpathResource(path: String): ByteArray = checkNotNull(
        javaClass.getResource("/$path")
    ) {
        "Missing test classpath resource $path"
    }.openStream().use { it.readBytes() }

    private fun canonicalWholeClosureSourceHash(
        javaSources: Map<String, String>,
        resourceTemplates: Map<String, String>
    ): String {
        val entries = javaSources.mapValues { (_, content) ->
            content.replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        }.toMutableMap()
        for ((name, content) in resourceTemplates) {
            entries["resource/$name"] = content.toByteArray(Charsets.UTF_8)
        }
        return canonicalEntryHash(entries)
    }

    private fun canonicalJavaOnlySourceHash(sources: Map<String, String>): String =
        canonicalEntryHash(
            sources.mapValues { (_, content) ->
                content.replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            }
        )

    private fun canonicalEntryHash(entries: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, content) in entries.toSortedMap()) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(content)
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
