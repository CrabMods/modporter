package com.modporter.integration

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * GameTest's flat generator must never read the dedicated server's existing normal chunks.
 *
 * The GameTest server still has to see the world datapacks the converted build stages for it in the
 * dedicated server's world (`run/world/datapacks`, where ModPorter's generated `prepareGameTestServerRun`
 * step writes the flat-preset override that adds the mod's own dimensions): without the universe
 * override that is the world the server would open. The init script mirrors only those datapack
 * files into the isolated world right before the server launches.
 */
internal object GameTestWorldIsolation {
    data class Plan(
        val universe: Path,
        val world: Path,
        val worldDatapacksSource: Path,
        val initScript: Path,
        val initSha256: String,
        val receipt: Path
    ) {
        val gradleArguments: List<String> get() = listOf(
            "--init-script", initScript.toString(),
            "-Dmodporter.gametest.universe=$universe"
        )
        val note: String get() = "isolatedGameTestWorld=$world; worldDatapacksFrom=$worldDatapacksSource; " +
            "fixture=$receipt; initSha256=$initSha256"
    }

    fun prepare(projectDir: Path, reportsDir: Path, caseId: String): Plan {
        require(Regex("[a-z0-9][a-z0-9_-]*").matches(caseId)) { "Invalid GameTest case id" }
        val reports = reportsDir.toAbsolutePath().normalize().createDirectories()
        val universe = reports.resolve("$caseId-gametest-universe")
        check(!Files.exists(universe, LinkOption.NOFOLLOW_LINKS)) {
            "Refusing to reuse a GameTest universe: $universe"
        }
        val serverWorld = projectDir.toAbsolutePath().normalize().resolve("run/world")
        check(!universe.startsWith(serverWorld) && !serverWorld.startsWith(universe)) {
            "GameTest universe overlaps the dedicated server world"
        }
        val scriptBytes = checkNotNull(javaClass.getResourceAsStream(
            "/benchmark-process/gametest_world_isolation.init.gradle"
        )) { "Missing GameTest world isolation script" }.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(scriptBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val script = reports.resolve("gametest-world-$digest.init.gradle")
        if (Files.exists(script, LinkOption.NOFOLLOW_LINKS)) {
            check(!Files.isSymbolicLink(script) && script.readBytes().contentEquals(scriptBytes)) {
                "GameTest world isolation script drifted: $script"
            }
        } else script.writeBytes(scriptBytes)
        Files.createDirectory(universe)
        val world = universe.resolve("world")
        val worldDatapacksSource = serverWorld.resolve("datapacks")
        val receipt = reports.resolve("$caseId-gametest-world-fixture.json")
        check(!Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) { "GameTest fixture receipt already exists" }
        receipt.writeText(buildJsonObject {
            put("schema", "modporter.gametest-world-isolation.v1")
            put("caseId", caseId)
            put("universe", universe.toString())
            put("world", world.toString())
            put("dedicatedServerWorld", serverWorld.toString())
            put("worldDatapacksSource", worldDatapacksSource.toString())
            put("initScript", script.toString())
            put("initSha256", digest)
            put("worldExistedBeforeLaunch", false)
        }.toString() + "\n")
        return Plan(universe, world, worldDatapacksSource, script, digest, receipt)
    }
}
