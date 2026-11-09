package com.modporter.integration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameTestWorldIsolationTest {
    @Test
    fun `new universe preserves server chunks and loose structures and rejects reuse`(@TempDir root: Path) {
        val project = root.resolve("project with spaces").createDirectories()
        val region = project.resolve("run/world/region/r.0.0.mca")
        region.parent.createDirectories()
        val originalRegion = byteArrayOf(2, 7, 1, 8)
        region.writeBytes(originalRegion)
        val structure = project.resolve("run/gameteststructures/example.snbt")
        structure.parent.createDirectories()
        structure.writeText("{blocks:[]}")
        val reports = root.resolve("reports with spaces")

        val plan = GameTestWorldIsolation.prepare(project, reports, "example")

        assertTrue(plan.universe.isAbsolute)
        assertEquals(reports.resolve("example-gametest-universe").toAbsolutePath(), plan.universe)
        assertEquals(plan.universe.resolve("world"), plan.world)
        assertFalse(Files.exists(plan.world), "Minecraft must create its own fresh flat test world")
        assertEquals(listOf("--init-script", plan.initScript.toString(),
            "-Dmodporter.gametest.universe=${plan.universe}"), plan.gradleArguments)
        assertContentEquals(originalRegion, region.readBytes())
        assertEquals("{blocks:[]}", structure.readText())
        // The converted build stages GameTest-only world datapacks in the dedicated server's world;
        // the isolated run mirrors exactly that folder, and the receipt and telemetry name it.
        val worldDatapacks = project.toAbsolutePath().normalize().resolve("run/world/datapacks")
        assertEquals(worldDatapacks, plan.worldDatapacksSource)
        assertTrue(plan.note.contains("worldDatapacksFrom=$worldDatapacks"))
        assertTrue(plan.receipt.readText().contains("\"worldDatapacksSource\":${Json.encodeToString(worldDatapacks.toString())}"))
        val initScript = plan.initScript.readText()
        assertTrue(initScript.contains("'run/world/datapacks'"))
        assertTrue(initScript.contains("tasks.named('runGameTestServer')") && initScript.contains("doFirst"))
        // a Kotlin build registers its runs lazily; only `all` realizes them before the run starts
        assertTrue(initScript.contains(".runs.all { modporterRun ->"))
        // the script also runs for a buildSrc build, whose projectsEvaluated has no ModDev runs
        assertTrue(initScript.contains("if (gradle.parent != null) {"))
        assertTrue(initScript.contains("modporterUniverse.list().length != 0"))
        val receipt = plan.receipt.readBytes()
        assertFailsWith<IllegalStateException> { GameTestWorldIsolation.prepare(project, reports, "example") }
        assertContentEquals(receipt, plan.receipt.readBytes())
        assertContentEquals(originalRegion, region.readBytes())
    }

    @Test
    fun `drifted isolation script is refused before reserving a world`(@TempDir root: Path) {
        val project = root.resolve("project").createDirectories()
        val reports = root.resolve("reports")
        val first = GameTestWorldIsolation.prepare(project, reports, "first")
        first.initScript.writeText("throw new GradleException('drift')")

        assertFailsWith<IllegalStateException> { GameTestWorldIsolation.prepare(project, reports, "second") }
        assertFalse(Files.exists(reports.resolve("second-gametest-universe")))
        assertFalse(Files.exists(reports.resolve("second-gametest-world-fixture.json")))
    }
}
