package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemLegacyPlantCompat
import com.modporter.core.transforms.structural.CanonicalRuntimeIdentity
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BuildSystemLegacyPlantCompatTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `groovy dependencies select the canonical runtime jar variant`() {
        install(kotlinDsl = false)
    }

    @Test
    fun `kotlin dependencies select the canonical runtime jar variant`() {
        install(kotlinDsl = true)
    }

    private fun install(kotlinDsl: Boolean) {
        val identity = CanonicalRuntimeIdentity.fromProductionResources()
        val currentVersion = "1.0.2-${identity.sourceSha256.take(16)}"
        val previousVersion = "1.0.0-bd2f764a86abe9dc"
        val previousJarSha256 =
            "f93da8ad5fea04a3e85934214a04f605fd6015276bdf36cb4421ca431e739ffa"
        val previousGameLibraryVersion = "1.0.1-bd2f764a86abe9dc"
        val previousGameLibraryJarSha256 =
            "1866250e5c14060af68293db21947eb3fc43d5503ac66778323ee49e1e5a4e39"
        val extension = if (kotlinDsl) "gradle.kts" else "gradle"
        directory.resolve("build.$extension").writeText(
            if (kotlinDsl) {
                """
                plugins {
                    id("net.neoforged.moddev") version "2.0.140"
                }

                dependencies {
                }
                """.trimIndent() + "\n"
            } else {
                """
                plugins {
                    id 'net.neoforged.moddev' version '2.0.140'
                }

                dependencies {
                }
                """.trimIndent() + "\n"
            }
        )
        val request = directory.resolve(".modporter/legacy-plant-compat.required")
        request.parent.createDirectories()
        request.writeText(identity.runtimeRequest())

        val first = BuildSystemLegacyPlantCompat.install(directory, dryRun = false)
        assertTrue(first.isNotEmpty())

        val owningBuild = directory.resolve("build.$extension").readText()
        assertEquals(
            2,
            owningBuild.lineSequence()
                .count { "modporterLegacyPlantCompatJarVariant" in it }
        )
        assertEquals(
            0,
            owningBuild.lineSequence()
                .count { "modporterLegacyPlantCompatDevRuntime" in it }
        )
        assertEquals(
            2,
            owningBuild.lineSequence()
                .count { "LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE" in it }
        )
        assertEquals(
            2,
            owningBuild.lineSequence().count { "LibraryElements.JAR" in it }
        )
        assertEquals(
            2,
            owningBuild.lineSequence().count {
                "requireCapability(" in it &&
                    "com.modporter.compat:legacy-plant-compat" in it
            }
        )
        assertTrue("additionalRuntimeClasspath" !in owningBuild)

        val auxiliaryBuild = directory.resolve(
            ".modporter/legacy-plant-compat/build.gradle"
        ).readText()
        assertTrue(auxiliaryBuild.contains("tasks.named('jar', Jar).configure"))
        assertTrue(auxiliaryBuild.contains(identity.jarSha256))
        assertTrue(auxiliaryBuild.contains("version = '$currentVersion'"))
        assertTrue(auxiliaryBuild.contains("'FMLModType': 'GAMELIBRARY'"))
        assertTrue(
            auxiliaryBuild.contains(
                "'MixinConfigs': 'modporter-legacy-plant-dt.mixins.json'"
            )
        )
        assertTrue(
            BuildSystemLegacyPlantCompat.install(directory, dryRun = false).isEmpty()
        )

        val unknownIdentity = owningBuild.replace(
            currentVersion,
            "9.9.9-unknown-packaging"
        )
        require(unknownIdentity != owningBuild)
        directory.resolve("build.$extension").writeText(unknownIdentity)
        assertThrows(IllegalStateException::class.java) {
            BuildSystemLegacyPlantCompat.install(directory, dryRun = false)
        }

        val unknownHashIdentity = owningBuild.replace(
            identity.jarSha256,
            "0".repeat(64)
        )
        require(unknownHashIdentity != owningBuild)
        directory.resolve("build.$extension").writeText(unknownHashIdentity)
        assertThrows(IllegalArgumentException::class.java) {
            BuildSystemLegacyPlantCompat.install(directory, dryRun = false)
        }

        val previousGameLibraryIdentity = owningBuild
            .replace(currentVersion, previousGameLibraryVersion)
            .replace(identity.jarSha256, previousGameLibraryJarSha256)
        directory.resolve("build.$extension").writeText(
            previousGameLibraryIdentity.replace(
                previousGameLibraryJarSha256,
                "1".repeat(64)
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            BuildSystemLegacyPlantCompat.install(directory, dryRun = false)
        }
        directory.resolve("build.$extension").writeText(previousGameLibraryIdentity)
        val upgradedGameLibrary = BuildSystemLegacyPlantCompat.install(
            directory,
            dryRun = false
        )
        assertTrue(upgradedGameLibrary.isNotEmpty())
        val upgradedGameLibraryBuild = directory.resolve("build.$extension").readText()
        assertEquals(3, upgradedGameLibraryBuild.windowed(currentVersion.length).count {
            it == currentVersion
        })
        assertTrue(previousGameLibraryVersion !in upgradedGameLibraryBuild)
        assertTrue(identity.jarSha256 in upgradedGameLibraryBuild)

        val previousIdentity = owningBuild
            .replace(currentVersion, previousVersion)
            .replace(identity.jarSha256, previousJarSha256)
        val previousWithDevelopmentRuntime = addKnownDevelopmentRuntimeDependency(
            previousIdentity,
            kotlinDsl
        )
        directory.resolve("build.$extension").writeText(
            previousWithDevelopmentRuntime.replace(
                "additionalRuntimeClasspath",
                "tamperedRuntimeClasspath"
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            BuildSystemLegacyPlantCompat.install(directory, dryRun = false)
        }
        directory.resolve("build.$extension").writeText(
            previousWithDevelopmentRuntime
        )
        val upgraded = BuildSystemLegacyPlantCompat.install(directory, dryRun = false)
        assertTrue(upgraded.isNotEmpty())
        val upgradedBuild = directory.resolve("build.$extension").readText()
        assertEquals(
            0,
            upgradedBuild.lineSequence()
                .count { "modporterLegacyPlantCompatDevRuntime" in it }
        )
        assertEquals(
            2,
            upgradedBuild.lineSequence()
                .count { "LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE" in it }
        )
        assertTrue("additionalRuntimeClasspath" !in upgradedBuild)
        assertEquals(3, upgradedBuild.windowed(currentVersion.length).count {
            it == currentVersion
        })
        assertTrue(previousVersion !in upgradedBuild)
        assertTrue(identity.jarSha256 in upgradedBuild)
        assertTrue(
            BuildSystemLegacyPlantCompat.install(directory, dryRun = false).isEmpty()
        )
    }

    private fun addKnownDevelopmentRuntimeDependency(
        script: String,
        kotlinDsl: Boolean
    ): String {
        val block = if (kotlinDsl) {
            """
            // modporterLegacyPlantCompatDevRuntime
            dependencies {
                add("additionalRuntimeClasspath", project(":modporterLegacyPlantCompat")) {
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
                        requireCapability("com.modporter.compat:legacy-plant-compat")
                    }
                }
            }
            """.trimIndent()
        } else {
            """
            // modporterLegacyPlantCompatDevRuntime
            dependencies {
                add('additionalRuntimeClasspath', project(':modporterLegacyPlantCompat')) {
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
                        requireCapability('com.modporter.compat:legacy-plant-compat')
                    }
                }
            }
            """.trimIndent()
        }
        val metadataMarker = "// modporterLegacyPlantCompatMetadata"
        val metadataStart = script.indexOf(metadataMarker)
        require(metadataStart >= 0)
        return script.substring(0, metadataStart).trimEnd() +
            "\n\n" +
            block +
            "\n\n" +
            script.substring(metadataStart)
    }
}
