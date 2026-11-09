package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildSystemGeneratedResourcesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `existing nested generated resources declaration is not registered twice`() {
        val projectDir = tempDir.resolve("nested-generated-resources")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id("net.neoforged.moddev.legacyforge") version "2.0.107"
            }

            legacyForge {
                version = "1.20.1-47.4.0"
            }

            sourceSets.main {
                resources {
                    srcDir("src/generated/resources")
                    exclude(".cache/")
                    exclude("data/example/.wiki")
                }
            }
            """.trimIndent()
        )

        val result = BuildSystemPass().apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertEquals(1, generatedResourcesDeclarations(content), content)
        assertTrue(content.contains("""exclude(".cache/")"""), content)
        assertTrue(content.contains("""exclude("data/example/.wiki")"""), content)
    }

    @Test
    fun `generated resources declaration is added when the project has none`() {
        val projectDir = tempDir.resolve("missing-generated-resources")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id("net.neoforged.moddev.legacyforge") version "2.0.107"
            }

            legacyForge {
                version = "1.20.1-47.4.0"
            }
            """.trimIndent()
        )

        val result = BuildSystemPass().apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertEquals(1, generatedResourcesDeclarations(content), content)
    }

    private fun generatedResourcesDeclarations(content: String): Int =
        Regex("""srcDir\s*(?:\(\s*)?(['"])src/generated/resources/?\1\s*\)?""")
            .findAll(content)
            .count()
}
