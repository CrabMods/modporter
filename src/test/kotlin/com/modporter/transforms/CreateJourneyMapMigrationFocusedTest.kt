package com.modporter.core.transforms.structural

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fast integration gate for CreateJourneyMapMigration.
 *
 * The generated-Java compiler and event execution probe lives in
 * CreateJourneyMapMigrationTest so this class can be integrated and run first.
 */
class CreateJourneyMapMigrationFocusedTest {
    private val trainMap =
        "src/main/java/com/simibubi/create/compat/trainmap/JourneyTrainMap.java"
    private val fullscreenMixin =
        "src/main/java/com/simibubi/create/foundation/mixin/compat/journeymap/" +
            "JourneyFullscreenMapMixin.java"
    private val mixinConfig = "src/main/resources/create.mixins.json"
    private val mixinEntry = "compat.journeymap.JourneyFullscreenMapMixin"

    @Test
    fun `both exact fixture families migrate and only remove the admitted mixin entry`() {
        listOf("original", "owned").forEach { fixture ->
            val project = copyFixture(fixture)
            val configBefore = project.resolve(mixinConfig).readText()

            val result = CreateJourneyMapMigration.migrate(
                StructuralRefactorPass(),
                project,
                dryRun = false
            )

            assertTrue(result.gates.isEmpty(), "$fixture gates: ${result.gates}")
            assertEquals(3, result.changes.size, "$fixture change count")
            assertEquals(
                listOf(trainMap, fullscreenMixin, mixinConfig),
                result.changes.map {
                    project.relativize(it.file).toString().replace('\\', '/')
                }
            )
            assertTrue(
                result.changes.all { it.ruleId == CreateJourneyMapMigration.RULE_ID }
            )

            val migrated = project.resolve(trainMap).readText()
            assertTrue(migrated.contains("@JourneyMapPlugin(apiVersion = \"2.0.0\")"))
            assertTrue(migrated.contains("implements IClientPlugin"))
            assertTrue(
                migrated.contains(
                    "FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(" +
                        "Create.ID, JourneyTrainMap::onFullscreenRender);"
                )
            )
            assertTrue(migrated.contains("screen.getCenterBlockX(true)"))
            assertTrue(migrated.contains("screen.getCenterBlockZ(true)"))
            assertTrue(migrated.contains("event.getPartialTicks()"))
            assertTrue(migrated.contains("Screen uiScreen = screen.getScreen();"))
            assertTrue(migrated.contains("TrainMapManager.tick();"))
            assertTrue(migrated.contains("TrainMapSyncClient.requestData();"))
            assertTrue(migrated.contains("TrainMapManager.handleToggleWidgetClick"))
            assertTrue(migrated.contains("RemovedGuiUtils.drawHoveringText"))
            assertFalse(migrated.contains("GridRenderer"))
            assertFalse(migrated.contains("getMouseDrag"))
            assertFalse(migrated.contains("isScrolling"))

            assertFalse(project.resolve(fullscreenMixin).exists())
            val expectedConfig = configBefore.replace(
                Regex(
                    """(?m)^[ \t]*"${Regex.escape(mixinEntry)}",[ \t]*(?:\r?\n|$)"""
                ),
                ""
            )
            assertEquals(expectedConfig, project.resolve(mixinConfig).readText())
        }
    }

    @Test
    fun `dry run and repeat migration preserve bytes`() {
        val dryProject = copyFixture("owned")
        val dryBefore = snapshot(dryProject)
        val dry = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            dryProject,
            dryRun = true
        )
        assertTrue(dry.gates.isEmpty(), dry.gates.toString())
        assertEquals(3, dry.changes.size)
        assertSnapshot(dryBefore, snapshot(dryProject))

        val project = copyFixture("owned")
        val first = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = false
        )
        assertTrue(first.gates.isEmpty(), first.gates.toString())
        val afterFirst = snapshot(project)
        val second = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = false
        )
        assertEquals(CreateJourneyMapMigration.Result.EMPTY, second)
        assertSnapshot(afterFirst, snapshot(project))
    }

    @Test
    fun `unknown bodies foreign consumers and partial families fail closed`() {
        val unknownBodies = listOf(
            trainMap to { source: String ->
                source.replace(
                    "\t\tTrainMapManager.tick();",
                    "\t\tTrainMapManager.tick();\n\t\tTrainMapManager.renderToggleWidget(null, 8, 9);"
                )
            },
            fullscreenMixin to { source: String ->
                source.replace(
                    "\t\tboolean dragging = isScrolling;",
                    "\t\tboolean dragging = !isScrolling;"
                )
            }
        )
        unknownBodies.forEach { (relative, mutate) ->
            val project = copyFixture("owned")
            val target = project.resolve(relative)
            target.writeText(mutate(target.readText()))
            assertRejectedUnchanged(project, "unknown members or method bodies")
        }

        val foreignProject = copyFixture("owned")
        val foreign =
            foreignProject.resolve("src/main/java/example/ForeignJourneyConsumer.java")
        foreign.parent.createDirectories()
        foreign.writeText(
            """
                package example;
                import journeymap.api.v2.client.IClientAPI;
                final class ForeignJourneyConsumer {
                    IClientAPI api;
                }
            """.trimIndent()
        )
        assertRejectedUnchanged(
            foreignProject,
            "Unrecognized JourneyMap source consumers"
        )

        val fqnProject = copyFixture("owned")
        val fqnForeign =
            fqnProject.resolve("src/main/java/example/ForeignJourneyFqnConsumer.java")
        fqnForeign.parent.createDirectories()
        fqnForeign.writeText(
            """
                package example;
                final class ForeignJourneyFqnConsumer {
                    journeymap.api.v2.client.IClientAPI api;
                }
            """.trimIndent()
        )
        assertRejectedUnchanged(
            fqnProject,
            "Unrecognized JourneyMap source consumers"
        )

        listOf(trainMap, fullscreenMixin, mixinConfig).forEach { missing ->
            val project = copyFixture("owned")
            project.resolve(missing).deleteIfExists()
            assertRejectedUnchanged(project, "incomplete", alternate = "missing")
        }
    }

    @Test
    fun `foreign mixin package rejects deletion while comments and strings are ignored`() {
        val wrongPackage = copyFixture("owned")
        val config = wrongPackage.resolve(mixinConfig)
        config.writeText(
            config.readText().replace(
                "\"package\": \"com.simibubi.create.foundation.mixin\"",
                "\"package\": \"example.foreign.mixin\""
            )
        )
        assertRejectedUnchanged(wrongPackage, "package")

        val documentationOnly = copyFixture("owned")
        val documentation =
            documentationOnly.resolve("src/main/java/example/JourneyDocumentation.java")
        documentation.parent.createDirectories()
        val documentationBytes = """
            package example;
            final class JourneyDocumentation {
                // journeymap.api.v2.client.IClientAPI is named only in documentation.
                String example = "journeymap.api.v2.client.IClientAPI";
            }
        """.trimIndent().toByteArray()
        Files.write(documentation, documentationBytes)

        val result = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            documentationOnly,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.toString())
        assertEquals(3, result.changes.size)
        assertContentEquals(documentationBytes, documentation.readBytes())
    }

    @Test
    fun `commit probe failure restores exact file set and bytes`() {
        val project = copyFixture("original")
        val before = snapshot(project)
        val visited = mutableListOf<String>()

        val result = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = false,
            commitProbe = { index, path ->
                visited += project.relativize(path).toString().replace('\\', '/')
                if (index == 2) error("injected transaction failure")
            }
        )

        assertEquals(listOf(trainMap, fullscreenMixin, mixinConfig), visited)
        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any {
                it.contains("transaction failed") &&
                    it.contains("injected transaction failure")
            },
            result.gates.toString()
        )
        assertSnapshot(before, snapshot(project))
        assertTrue(project.resolve(trainMap).isRegularFile())
        assertTrue(project.resolve(fullscreenMixin).isRegularFile())
        assertTrue(project.resolve(mixinConfig).isRegularFile())
    }

    private fun assertRejectedUnchanged(
        project: Path,
        expected: String,
        alternate: String? = null
    ) {
        val before = snapshot(project)
        val result = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = false
        )
        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any { gate ->
                gate.contains(expected, ignoreCase = true) ||
                    (alternate != null && gate.contains(alternate, ignoreCase = true))
            },
            result.gates.toString()
        )
        assertSnapshot(before, snapshot(project))
    }

    private fun copyFixture(name: String): Path {
        val source = fixtureRoot().resolve(name).resolve("project")
        assertTrue(source.exists(), "Missing fixture project: $source")
        val destination = createTempDirectory("create-journeymap-focused-$name-")
            .resolve("project")
        source.toFile().copyRecursively(destination.toFile(), overwrite = true)
        return destination
    }

    private fun fixtureRoot(): Path {
        System.getProperty("modporter.journeymap.fixtureRoot")
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it) }
            ?.toAbsolutePath()
            ?.normalize()
            ?.let { configured ->
                if (configured.resolve("original/project").exists()) return configured
            }

        var cursor: Path? = Path.of("").toAbsolutePath().normalize()
        while (cursor != null) {
            val published = cursor.resolve(
                "src/test/resources/fixtures/create-journeymap-v2"
            )
            if (published.resolve("original/project").exists()) return published
            cursor = cursor.parent
        }
        error(
            "Cannot locate Create JourneyMap fixtures; set " +
                "-Dmodporter.journeymap.fixtureRoot=<fixture directory>"
        )
    }

    private fun snapshot(project: Path): Snapshot {
        val files = Files.walk(project).use { paths ->
            paths.filter { it.isRegularFile() }
                .toList()
                .associate { file ->
                    project.relativize(file).toString().replace('\\', '/') to
                        file.readBytes()
                }
        }
        return Snapshot(files)
    }

    private fun assertSnapshot(expected: Snapshot, actual: Snapshot) {
        assertEquals(expected.files.keys, actual.files.keys, "project file set changed")
        expected.files.forEach { (relative, bytes) ->
            assertContentEquals(bytes, actual.files.getValue(relative), relative)
        }
    }

    private data class Snapshot(val files: Map<String, ByteArray>)
}
