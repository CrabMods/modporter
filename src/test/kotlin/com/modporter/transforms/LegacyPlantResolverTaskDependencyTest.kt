package com.modporter.transforms

import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyPlantResolverTaskDependencyTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `resolver executes configuration builtBy producer before inventory`() {
        val project = directory.resolve("fixture").also { it.createDirectories() }
        project.resolve("settings.gradle").writeText("rootProject.name = 'fixture'\n")
        project.resolve("gradle.properties").writeText(
            """
            minecraft_version=1.20.1
            forge_version=47.2.0
            """.trimIndent() + "\n"
        )
        project.resolve("buildSrc/build.gradle").also { it.parent.createDirectories() }
            .writeText(
                """
                plugins {
                    id 'groovy-gradle-plugin'
                }

                gradlePlugin {
                    plugins {
                        fixtureLegacyModDev {
                            id = 'net.neoforged.moddev.legacyforge'
                            implementationClass = 'fixture.LegacyModDevFixturePlugin'
                        }
                    }
                }
                """.trimIndent() + "\n"
            )
        project.resolve(
            "buildSrc/src/main/groovy/fixture/LegacyModDevFixturePlugin.groovy"
        ).also { it.parent.createDirectories() }.writeText(
            """
            package fixture

            import org.gradle.api.Plugin
            import org.gradle.api.Project

            class LegacyModDevFixturePlugin implements Plugin<Project> {
                @Override
                void apply(Project project) {
                    project.pluginManager.apply('java')
                }
            }
            """.trimIndent() + "\n"
        )
        project.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev.legacyforge'
            }

            // Resolver declaration identity fixture:
            // id 'net.neoforged.moddev.legacyforge' version 'fixture-1.0'

            def generatedArtifact = layout.buildDirectory.file(
                'moddev/artifacts/forge-1.20.1-47.2.0.jar'
            )
            def stageGeneratedArtifact = tasks.register('stageGeneratedArtifact') {
                outputs.file(generatedArtifact)
                doLast {
                    def output = generatedArtifact.get().asFile
                    output.parentFile.mkdirs()
                    output.bytes = 'source-proven-fixture'.getBytes('UTF-8')
                    file('producer-ran.txt').text = output.canonicalPath
                }
            }
            def generatedFiles = files(generatedArtifact)
            generatedFiles.builtBy(stageGeneratedArtifact)

            dependencies {
                compileOnly generatedFiles
                runtimeOnly generatedFiles
            }
            """.trimIndent() + "\n"
        )

        val repositoryRoot = Path.of("").toAbsolutePath().normalize()
        val initScript =
            repositoryRoot.resolve("src/main/resources/legacy-plant-resolve.init.gradle")
        val inventoryOutput = project.resolve("inventory.json")
        val result = GradleRunner.create()
            .withProjectDir(project.toFile())
            .withTestKitDir(testKitDirectory("source").toFile())
            .withArguments(
                "modporterLegacyPlantResolve",
                "-I",
                initScript.toString(),
                "-DmodporterLegacyPlantInventoryOutput=$inventoryOutput",
                "-DmodporterLegacyPlantInventoryPhase=source",
                "-DmodporterLegacyPlantClassRuntimeFeature=17",
                "--stacktrace",
            )
            .buildAndFail()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":stageGeneratedArtifact")?.outcome,
            result.output
        )
        assertTrue(project.resolve("producer-ran.txt").exists(), result.output)
        assertTrue(
            project.resolve("build/moddev/artifacts/forge-1.20.1-47.2.0.jar").exists(),
            result.output
        )
        assertTrue(
            result.output.contains(
                "Source graph did not resolve net.minecraftforge:forge:1.20.1-47.2.0"
            ),
            result.output
        )
        assertFalse(
            result.output.contains(
                "Legacy plant inventory requires a regular artifact file"
            ),
            result.output
        )
    }

    @Test
    fun `target resolver admits only source proven managed staged modules`() {
        val project = targetManagedFixture()
        val repositoryRoot = Path.of("").toAbsolutePath().normalize()
        val initScript =
            repositoryRoot.resolve("src/main/resources/legacy-plant-resolve.init.gradle")
        val runtimeHash = sha256("canonical-runtime-fixture".toByteArray())

        val accepted = runTargetManagedFixture(
            project,
            initScript,
            runtimeHash,
            drift = "none"
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            accepted.task(":stageManagedRuntimeArtifact")?.outcome,
            accepted.output
        )
        assertTrue(
            accepted.output.contains(
                "Target graph did not resolve net.neoforged:neoforge:21.1.209"
            ),
            accepted.output
        )
        assertFalse(
            accepted.output.contains("Unsupported resolved artifact component"),
            accepted.output
        )

        mapOf(
            "receipt" to "Managed runtime artifact receipt identity changed",
            "hash" to "Managed runtime artifact receipt hash identity changed",
            "task" to
                "Managed runtime artifact producer is not bound to the resolved configuration"
        ).forEach { (drift, expectedFailure) ->
            val rejected = runTargetManagedFixture(
                project,
                initScript,
                runtimeHash,
                drift
            )
            assertEquals(
                TaskOutcome.SUCCESS,
                rejected.task(":stageManagedRuntimeArtifact")?.outcome,
                rejected.output
            )
            assertTrue(rejected.output.contains(expectedFailure), rejected.output)
            assertFalse(
                rejected.output.contains(
                    "Target graph did not resolve net.neoforged:neoforge:21.1.209"
                ),
                rejected.output
            )
        }
    }

    private fun targetManagedFixture(): Path {
        val project = directory.resolve("target-managed").also { it.createDirectories() }
        project.resolve("settings.gradle").writeText(
            """
            rootProject.name = 'target-managed'
            include ':modporterLegacyPlantCompat'
            project(':modporterLegacyPlantCompat').projectDir =
                file('.modporter/legacy-plant-compat')
            """.trimIndent() + "\n"
        )
        project.resolve("gradle.properties").writeText(
            """
            minecraft_version=1.21.1
            neo_forge_version=21.1.209
            """.trimIndent() + "\n"
        )
        project.resolve("buildSrc/build.gradle").also { it.parent.createDirectories() }
            .writeText(
                """
                plugins {
                    id 'groovy-gradle-plugin'
                }

                gradlePlugin {
                    plugins {
                        fixtureModDev {
                            id = 'net.neoforged.moddev'
                            implementationClass = 'fixture.ModDevFixturePlugin'
                        }
                    }
                }
                """.trimIndent() + "\n"
            )
        project.resolve(
            "buildSrc/src/main/groovy/fixture/ModDevFixturePlugin.groovy"
        ).also { it.parent.createDirectories() }.writeText(
            """
            package fixture

            import org.gradle.api.Plugin
            import org.gradle.api.Project

            class ModDevFixturePlugin implements Plugin<Project> {
                @Override
                void apply(Project project) {
                    project.pluginManager.apply('java')
                }
            }
            """.trimIndent() + "\n"
        )

        val moduleBytes = "source-proven-managed-module".toByteArray()
        val moduleDirectory = project.resolve("repository/example/managed/1.0")
            .also { it.createDirectories() }
        writeJar(moduleDirectory.resolve("managed-1.0.jar"), moduleBytes)
        moduleDirectory.resolve("managed-1.0.pom").writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>managed</artifactId>
              <version>1.0</version>
            </project>
            """.trimIndent() + "\n"
        )
        val jarHash = sha256(
            java.nio.file.Files.readAllBytes(moduleDirectory.resolve("managed-1.0.jar"))
        )

        project.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev'
            }

            // Resolver declaration identity fixture:
            // id 'net.neoforged.moddev' version 'fixture-1.0'

            repositories {
                maven { url = uri('repository') }
            }

            def managedCoordinate = 'example:managed:1.0'
            def managedSha256 = '$jarHash'
            def managedSource = configurations.create('managedRuntimeSource') {
                canBeConsumed = false
                canBeResolved = true
                transitive = true
            }
            dependencies {
                add(managedSource.name, managedCoordinate)
            }
            def managedRoot = layout.buildDirectory.dir(
                'modporter/runtime-artifacts/managed/' + managedSha256
            )
            def managedFile = managedRoot.map {
                it.file('managed-' + managedSha256.substring(0, 16) + '.jar')
            }
            def managedReceipt = managedRoot.map { it.file('receipt.properties') }
            def stageManaged = tasks.register('stageManagedRuntimeArtifact') {
                inputs.files(managedSource)
                inputs.property(
                    'drift',
                    providers.gradleProperty('managedReceiptDrift').orElse('none')
                )
                outputs.file(managedFile)
                outputs.file(managedReceipt)
                doLast { task ->
                    def source = managedSource.singleFile.canonicalFile
                    def staged = managedFile.get().asFile.canonicalFile
                    staged.parentFile.mkdirs()
                    java.nio.file.Files.copy(
                        source.toPath(),
                        staged.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    def drift = providers.gradleProperty(
                        'managedReceiptDrift'
                    ).getOrElse('none')
                    def receipt = managedReceipt.get().asFile
                    receipt.setText([
                        'schemaVersion=' + (drift == 'receipt' ? '999' : '2'),
                        'projectPath=' + project.path,
                        'taskPath=' + (
                            drift == 'task' ? ':wrongManagedProducer' : task.path
                        ),
                        'sourceConfiguration=' + managedSource.name,
                        'runtimeConfiguration=runtimeOnly',
                        'coordinate=' + managedCoordinate,
                        'sourceSha256=' + managedSha256,
                        'stagedSha256=' + (
                            drift == 'hash' ? '${"0".repeat(64)}' : managedSha256
                        ),
                        'byteLength=' + staged.length(),
                        'runtimeFilename=' + staged.name,
                        'nestedJars='
                    ].join(System.lineSeparator()) + System.lineSeparator(), 'UTF-8')
                }
            }
            def managedFiles = files(managedFile)
            managedFiles.builtBy(stageManaged)

            def generatedPlatform = layout.buildDirectory.file(
                'moddev/artifacts/neoforge-21.1.209.jar'
            )
            def createPlatform = tasks.register('createMinecraftArtifacts') {
                outputs.file(generatedPlatform)
                doLast {
                    def output = generatedPlatform.get().asFile
                    output.parentFile.mkdirs()
                    output.bytes = 'generated-neoforge-platform'.getBytes('UTF-8')
                }
            }
            def generatedPlatformFiles = files(generatedPlatform)
            generatedPlatformFiles.builtBy(createPlatform)

            dependencies {
                implementation project(':modporterLegacyPlantCompat')
                compileOnly 'example:managed:1.0'
                compileOnly generatedPlatformFiles
                runtimeOnly managedFiles
                runtimeOnly generatedPlatformFiles
            }
            """.trimIndent() + "\n"
        )

        val runtimeBytes = "canonical-runtime-fixture".toByteArray()
        val runtimeHash = sha256(runtimeBytes)
        val runtimeProject =
            project.resolve(".modporter/legacy-plant-compat").also { it.createDirectories() }
        runtimeProject.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev'
            }

            // id 'net.neoforged.moddev' version 'fixture-1.0'
            // expected-runtime-sha256=$runtimeHash
            group = 'com.modporter.compat'
            version = '1.0'
            base {
                archivesName = 'legacy-plant-compat'
            }
            tasks.named('jar') {
                doLast {
                    archiveFile.get().asFile.bytes =
                        'canonical-runtime-fixture'.getBytes('UTF-8')
                }
            }
            """.trimIndent() + "\n"
        )
        project.resolve(".modporter/legacy-plant-compat.required").writeText(
            """
            schema=legacy-plant-runtime-request-v1
            package=com.modporter.compat.plant.v1
            sourceSha256=${"b".repeat(64)}
            jarSha256=$runtimeHash
            """.trimIndent() + "\n"
        )
        return project
    }

    // Keep native TestKit state in the testKitTest task temporary tree so its
    // lifetime is bounded by that task rather than JUnit fixture cleanup.
    private fun testKitDirectory(name: String): Path =
        Path.of("build", "tmp", "testKitTest", "legacy-plant-resolver-test-kit")
            .toAbsolutePath()
            .normalize()
            // JUnit owns directory; its unique name namespaces this test run without
            // placing native TestKit caches inside the directory JUnit must delete.
            .resolve(directory.fileName.toString())
            .resolve(name)
            .also { it.createDirectories() }

    private fun runTargetManagedFixture(
        project: Path,
        initScript: Path,
        runtimeHash: String,
        drift: String
    ) = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withTestKitDir(testKitDirectory("target-$drift").toFile())
        .withArguments(
            "modporterLegacyPlantResolve",
            "-I",
            initScript.toString(),
            "-DmodporterLegacyPlantInventoryOutput=" +
                project.resolve("inventory-$drift.json"),
            "-DmodporterLegacyPlantInventoryPhase=target",
            "-DmodporterLegacyPlantRuntimeJarSha256=$runtimeHash",
            "-DmodporterLegacyPlantClassRuntimeFeature=21",
            "-PmanagedReceiptDrift=$drift",
            "--stacktrace",
        )
        .buildAndFail()

    private fun writeJar(path: Path, payload: ByteArray) {
        ZipOutputStream(path.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("fixture.txt"))
            output.write(payload)
            output.closeEntry()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
