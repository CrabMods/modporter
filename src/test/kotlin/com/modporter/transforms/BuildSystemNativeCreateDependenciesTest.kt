package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.build.DepResolution
import com.modporter.core.transforms.build.DependencyResolver
import com.modporter.core.transforms.build.NeoForgeCoord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildSystemNativeCreateDependenciesTest {

    @TempDir
    lateinit var tempDir: Path

    private fun deterministicOnlinePass(lookups: MutableList<String>): BuildSystemPass =
        BuildSystemPass(
            offlineMode = false,
            dependencyResolverFactory = { offlineMode, mappingsPrefix ->
                DependencyResolver(
                    offlineMode = offlineMode,
                    mappingsPrefix = mappingsPrefix,
                    onlineLookup = { slug, configuration ->
                        lookups += "$slug@$configuration"
                        DepResolution.Resolved(
                            coords = listOf(
                                NeoForgeCoord(
                                    config = configuration,
                                    coord = "maven.modrinth:$slug:9.9.9-test+neoforge"
                                )
                            ),
                            mavenUrl = "https://api.modrinth.com/maven",
                            notes = "deterministic online resolver test result"
                        )
                    }
                )
            }
        )

    @Test
    fun `replaces old Forge JourneyMap with the full native NeoForge mod on both classpaths`() {
        val projectDir = tempDir.resolve("journeymap")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            repositories {
                maven { url = "https://www.cursemaven.com" }
            }

            dependencies {
                implementation fg.deobf("curse.maven:journeymap-32274:5457831")
            }
            """.trimIndent()
        )

        val result = BuildSystemPass().apply(projectDir)
        val build = projectDir.resolve("build.gradle").readText()
        val nativeCoordinate = "maven.modrinth:journeymap:1.21.1-6.0.0-beta.54+neoforge"
        val resolution = DependencyResolver(offlineMode = true).resolve(
            """implementation("curse.maven:journeymap-32274:5457831")"""
        )
        assertTrue(resolution is DepResolution.Resolved, resolution.toString())
        val staging = (resolution as DepResolution.Resolved).runtimeArtifactStaging
            ?: error("JourneyMap runtime artifact staging policy missing")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(result.changes.any { it.ruleId == "build-stage-runtime-artifact" })
        assertEquals("journeymap", staging.id)
        assertEquals(nativeCoordinate, staging.coordinate)
        assertEquals(
            "121d84332dba09100ea8c654a8c5b75bdf1257639d00aca1210c5dc31f36cb38",
            staging.sha256
        )
        assertEquals("journeymap-121d84332dba0910.jar", staging.runtimeFileName)
        assertEquals(
            setOf(
                "META-INF/jarjar/common-networking-neoforge-1.0.18-1.21.1.jar",
                "META-INF/jarjar/journeymap-api-neoforge-2.0.0-1.21.1-SNAPSHOT.jar",
                "META-INF/jarjar/pngj-2.1.0.jar"
            ),
            staging.expectedNestedJars.toSet()
        )
        assertEquals(1, Regex("""compileOnly\s+["']${Regex.escape(nativeCoordinate)}["']""").findAll(build).count(), build)
        assertFalse(
            Regex("""runtimeOnly\s*(?:\(\s*)?["']${Regex.escape(nativeCoordinate)}["']""")
                .containsMatchIn(build),
            build
        )
        assertEquals(
            1,
            Regex("""(?m)^\s*runtimeOnly\s+modporterJourneymapRuntimeFiles\s*$""")
                .findAll(build)
                .count(),
            build
        )
        val apiCoordinate = "info.journeymap:journeymap-api-neoforge:2.0.0-1.21.1-20260205.200232-15"
        assertTrue(build.contains("""compileOnly("$apiCoordinate") { transitive = false }"""), build)
        assertTrue(build.contains("https://jm.gserv.me/repository/maven-public"), build)
        assertTrue(build.contains("https://api.modrinth.com/maven"), build)
        assertEquals(
            1,
            Regex("""(?m)^// modporter-runtime-artifact-stage:journeymap:${staging.sha256}$""")
                .findAll(build)
                .count(),
            build
        )
        assertTrue(build.contains("configurations.create('modporterJourneymapRuntimeSource')"), build)
        assertTrue(
            build.contains(
                "final String modporterJourneymapCoordinate = " +
                    "'maven.modrinth:journeymap:1.21.1-6.0.0-beta.54+neoforge'"
            ),
            build
        )
        assertFalse(build.contains("def modporterJourneymapCoordinate"), build)
        assertTrue(build.contains("transitive = true"), build)
        assertTrue(build.contains("resolvedConfiguration.resolvedArtifacts.toList()"), build)
        assertTrue(build.contains("artifacts.size() != 1"), build)
        assertTrue(build.contains("new java.util.jar.JarFile(sourceFile)"), build)
        assertTrue(build.contains("stagedPath.contains('+') || stagedPath.contains('%')"), build)
        assertTrue(build.contains("java.nio.file.Files.mismatch("), build)
        assertTrue(build.contains("modporterJourneymapRuntimeFiles.builtBy(modporterJourneymapStageTask)"), build)
        listOf(
            "schemaVersion",
            "projectPath",
            "taskPath",
            "sourceConfiguration",
            "runtimeConfiguration",
            "coordinate",
            "sourceSha256",
            "stagedSha256",
            "byteLength",
            "runtimeFilename",
            "nestedJars"
        ).forEach { receiptKey ->
            assertTrue(build.contains("'$receiptKey="), "Missing receipt field $receiptKey:\n$build")
        }
        assertTrue(build.contains("'schemaVersion=2'"), build)
        assertTrue(
            build.contains(
                "'sourceConfiguration=' + modporterJourneymapRuntimeSource.name"
            ),
            build
        )
        assertFalse(build.contains("curse.maven:journeymap-32274"), build)
        assertFalse(build.contains("5457831"), build)
        assertFalse(build.contains("fg.deobf"), build)

        val firstPassBuild = build
        val secondResult = BuildSystemPass().apply(projectDir)
        assertTrue(secondResult.errors.isEmpty(), secondResult.errors.joinToString("\n"))
        assertEquals(firstPassBuild, projectDir.resolve("build.gradle").readText())
    }

    @Test
    fun `online Groovy and Kotlin second passes keep exact managed JourneyMap staging`() {
        data class DslCase(
            val directory: String,
            val fileName: String,
            val source: String,
            val coordinateDeclaration: String,
            val stagedDependency: String
        )

        val cases = listOf(
            DslCase(
                directory = "journeymap-online-groovy",
                fileName = "build.gradle",
                source = """
                    plugins {
                        id 'net.minecraftforge.gradle' version '[6.0,6.2)'
                    }

                    dependencies {
                        implementation fg.deobf("curse.maven:journeymap-32274:5457831")
                    }
                """.trimIndent(),
                coordinateDeclaration =
                    "final String modporterJourneymapCoordinate = " +
                        "'maven.modrinth:journeymap:1.21.1-6.0.0-beta.54+neoforge'",
                stagedDependency = "runtimeOnly modporterJourneymapRuntimeFiles"
            ),
            DslCase(
                directory = "journeymap-online-kotlin",
                fileName = "build.gradle.kts",
                source = """
                    plugins {
                        id("net.minecraftforge.gradle") version "[6.0,6.2)"
                    }

                    dependencies {
                        implementation(fg.deobf("curse.maven:journeymap-32274:5457831"))
                    }
                """.trimIndent(),
                coordinateDeclaration =
                    "val modporterJourneymapCoordinate = " +
                        "\"maven.modrinth:journeymap:1.21.1-6.0.0-beta.54+neoforge\"",
                stagedDependency = "runtimeOnly(modporterJourneymapRuntimeFiles)"
            )
        )

        for (case in cases) {
            val lookups = mutableListOf<String>()
            val projectDir = tempDir.resolve(case.directory)
            projectDir.createDirectories()
            val buildFile = projectDir.resolve(case.fileName)
            buildFile.writeText(case.source)

            val pass = deterministicOnlinePass(lookups)
            val firstResult = pass.apply(projectDir)
            assertTrue(firstResult.errors.isEmpty(), firstResult.errors.joinToString("\n"))
            assertTrue(lookups.isEmpty(), "Known JourneyMap mapping unexpectedly used online lookup: $lookups")
            val firstBuild = buildFile.readText()
            assertTrue(firstBuild.contains(case.coordinateDeclaration), firstBuild)
            assertTrue(firstBuild.contains(case.stagedDependency), firstBuild)
            assertFalse(firstBuild.contains("9.9.9-test+neoforge"), firstBuild)

            lookups.clear()
            val secondResult = pass.apply(projectDir)
            assertTrue(secondResult.errors.isEmpty(), secondResult.errors.joinToString("\n"))
            assertTrue(lookups.isEmpty(), "Mapped target coordinates were repinned online: $lookups")
            assertEquals(firstBuild, buildFile.readText())
        }
    }

    @Test
    fun `second pass rejects drift inside an existing managed staging block`() {
        val lookups = mutableListOf<String>()
        val projectDir = tempDir.resolve("journeymap-stage-drift")
        projectDir.createDirectories()
        val buildFile = projectDir.resolve("build.gradle")
        buildFile.writeText(
            """
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation fg.deobf("curse.maven:journeymap-32274:5457831")
            }
            """.trimIndent()
        )

        val pass = deterministicOnlinePass(lookups)
        val firstResult = pass.apply(projectDir)
        assertTrue(firstResult.errors.isEmpty(), firstResult.errors.joinToString("\n"))
        val drifted = buildFile.readText().replace(
            "final String modporterJourneymapCoordinate = " +
                "'maven.modrinth:journeymap:1.21.1-6.0.0-beta.54+neoforge'",
            "final String modporterJourneymapCoordinate = " +
                "'maven.modrinth:journeymap:1.21.1-6.0.0-beta.53+neoforge'"
        )
        assertTrue(drifted.contains("beta.53+neoforge"), drifted)
        buildFile.writeText(drifted)

        lookups.clear()
        val secondResult = pass.apply(projectDir)
        assertTrue(
            secondResult.errors.any { it.contains("Runtime artifact staging block drifted from policy: journeymap") },
            secondResult.errors.joinToString("\n")
        )
        assertTrue(lookups.isEmpty(), "Drift audit must fail before online resolution: $lookups")
        assertEquals(drifted, buildFile.readText())
    }

    @Test
    fun `pins Vanillin to the native Create Flywheel compatibility family`() {
        val projectDir = tempDir.resolve("vanillin")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText(
            """
            minecraft_version=1.20.1
            flywheel_minecraft_version=1.20.1
            flywheel_version=1.0.6-beta-266
            vanillin_version=1.0.0
            """.trimIndent()
        )
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                modCompileOnly("dev.engine-room.flywheel:flywheel-forge-api-${'$'}{flywheel_minecraft_version}:${'$'}{flywheel_version}")
                modRuntimeOnly("dev.engine-room.vanillin:vanillin-forge-${'$'}{flywheel_minecraft_version}:${'$'}{vanillin_version}")
            }
            """.trimIndent()
        )

        val result = BuildSystemPass().apply(projectDir)
        val build = projectDir.resolve("build.gradle").readText()
        val properties = projectDir.resolve("gradle.properties").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            build.contains(
                """runtimeOnly("dev.engine-room.vanillin:vanillin-neoforge-${'$'}{flywheel_minecraft_version}:${'$'}{vanillin_version}")"""
            ),
            build
        )
        assertTrue(properties.lineSequence().any { it.trim() == "flywheel_version=1.0.6" }, properties)
        assertTrue(properties.lineSequence().any { it.trim() == "vanillin_version=1.1.3-41" }, properties)
        assertFalse(properties.contains("1.1.4-48"), properties)
        assertFalse(build.contains("vanillin-forge-"), build)
        assertFalse(build.contains("modCompileOnly") || build.contains("modRuntimeOnly"), build)
    }
}
