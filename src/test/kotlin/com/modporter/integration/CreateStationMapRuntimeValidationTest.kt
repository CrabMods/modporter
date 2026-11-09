package com.modporter.integration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class CreateStationMapRuntimeValidationTest {
    @Test
    fun `valid child and coordinator bind native observations and both child bytes`(@TempDir root: Path) {
        val fixture = fixture(root)
        assertTrue(
            CreateStationMapRuntimeValidation.verifyStationChild(
                fixture.context,
                fixture.station
            ).isEmpty()
        )
        assertTrue(
            CreateStationMapRuntimeValidation.verifyCoordinator(
                fixture.context,
                fixture.coordinator,
                fixture.spawn,
                fixture.station
            ).isEmpty()
        )
    }

    @Test
    fun `station child rejects direct handler shortcuts decoration drift and forged png metadata`(@TempDir root: Path) {
        val fixture = fixture(root)
        val shortcut = mutateObservation(
            fixture.station,
            "packetCodecClientUpdate",
            "directClientHandlerInvoked",
            JsonPrimitive(true)
        )
        val drift = mutateObservation(
            fixture.station,
            "firstPersonNativeRenderer",
            "renderedDecoration",
            decoration("different")
        )
        val png = mutateObservation(
            fixture.station,
            "firstPersonNativeRenderer",
            "sampledDistinctColors",
            JsonPrimitive(999)
        )
        assertContains(
            CreateStationMapRuntimeValidation.verifyStationChild(fixture.context, shortcut),
            "directClientHandlerInvoked must be false"
        )
        assertContains(
            CreateStationMapRuntimeValidation.verifyStationChild(fixture.context, drift),
            "decorations differ"
        )
        assertContains(
            CreateStationMapRuntimeValidation.verifyStationChild(fixture.context, png),
            "distinct-color count"
        )
    }

    @Test
    fun `coordinator rejects copied pass flags stale child hashes and scenario reordering`(@TempDir root: Path) {
        val fixture = fixture(root)
        val children = fixture.coordinator.getValue("children") as JsonObject
        val spawnRef = children.getValue("spawn") as JsonObject
        val forgedSpawnRef = JsonObject(spawnRef + ("sha256" to JsonPrimitive("0".repeat(64))))
        val forgedChildren = JsonObject(children + ("spawn" to forgedSpawnRef))
        val stale = JsonObject(fixture.coordinator + ("children" to forgedChildren))
        assertContains(
            CreateStationMapRuntimeValidation.verifyCoordinator(
                fixture.context,
                stale,
                fixture.spawn,
                fixture.station
            ),
            "hash does not bind"
        )

        val scenarios = fixture.coordinator.getValue("scenarios") as JsonArray
        val reordered = JsonObject(
            fixture.coordinator + ("scenarios" to JsonArray(listOf(scenarios[1], scenarios[0]) + scenarios.drop(2)))
        )
        assertContains(
            CreateStationMapRuntimeValidation.verifyCoordinator(
                fixture.context,
                reordered,
                fixture.spawn,
                fixture.station
            ),
            "must be 'non_quantized_motion'"
        )

        val first = scenarios.first() as JsonObject
        val copiedPass = JsonObject(first + ("passed" to JsonPrimitive(false)))
        val falseProjection = JsonObject(
            fixture.coordinator + ("scenarios" to JsonArray(listOf(copiedPass) + scenarios.drop(1)))
        )
        assertContains(
            CreateStationMapRuntimeValidation.verifyCoordinator(
                fixture.context,
                falseProjection,
                fixture.spawn,
                fixture.station
            ),
            "passed must be true"
        )
    }

    private data class Fixture(
        val context: CreateStationMapRuntimeValidation.Context,
        val spawn: JsonObject,
        val station: JsonObject,
        val coordinator: JsonObject
    )

    @Test
    fun `pending chunk work and shortened health fences cannot be hidden behind copied pass flags`(@TempDir root: Path) {
        val fixture = fixture(root)
        fun altered(change: (JsonObject) -> JsonObject): JsonObject {
            val scenario = fixture.station.getValue("scenarios").jsonArray.single().jsonObject
            val observations = scenario.getValue("observations").jsonObject
            val cleanup = observations.getValue("cleanup").jsonObject
            val updated = JsonObject(scenario + ("observations" to JsonObject(observations +
                ("cleanup" to change(cleanup)))))
            return JsonObject(fixture.station + ("scenarios" to JsonArray(listOf(updated))))
        }
        val pending = altered { cleanup ->
            val health = cleanup.getValue("chunkQuiescence").jsonObject
            val last = health.getValue("last").jsonObject
            JsonObject(cleanup + ("chunkQuiescence" to JsonObject(health +
                ("last" to JsonObject(last + ("pendingUnloads" to JsonPrimitive(1)))))))
        }
        assertContains(CreateStationMapRuntimeValidation.verifyStationChild(fixture.context, pending),
            "last.pendingUnloads must be 0")
        val short = altered { cleanup ->
            val health = cleanup.getValue("chunkQuiescence").jsonObject
            JsonObject(cleanup + ("chunkQuiescence" to JsonObject(health +
                ("stableServerTicks" to JsonPrimitive(2)))))
        }
        assertContains(CreateStationMapRuntimeValidation.verifyStationChild(fixture.context, short),
            "three stable server ticks")
    }

    private fun fixture(root: Path): Fixture {
        val converted = root.resolve("converted")
        val sourceRoot = converted.resolve("src/main/java")
        val harnessRelative =
            "com/modporter/generated/create/benchmark/${StrictSpawnValidationContract.STATION_HARNESS_FILE}"
        sourceRoot.resolve(harnessRelative).also {
            it.parent.createDirectories()
            it.writeText("package com.modporter.generated.create.benchmark; final class ModPorterStrictStationMapHarness {}")
        }
        val classpath = root.resolve("classpath").also { it.createDirectories() }
        val resources = linkedMapOf(
            "net.minecraft.network.protocol.game.ClientboundMapItemDataPacket" to
                "net/minecraft/network/protocol/game/ClientboundMapItemDataPacket.class",
            "net.minecraft.world.level.saveddata.maps.MapDecoration" to
                "net/minecraft/world/level/saveddata/maps/MapDecoration.class",
            "net.minecraft.world.level.saveddata.maps.MapItemSavedData" to
                "net/minecraft/world/level/saveddata/maps/MapItemSavedData.class",
            "net.minecraft.client.multiplayer.ClientPacketListener" to
                "net/minecraft/client/multiplayer/ClientPacketListener.class",
            "net.minecraft.client.renderer.ItemInHandRenderer" to
                "net/minecraft/client/renderer/ItemInHandRenderer.class",
            "net.minecraft.client.gui.MapRenderer" to
                "net/minecraft/client/gui/MapRenderer.class",
            "net.neoforged.neoforge.client.gui.map.MapDecorationRendererManager" to
                "net/neoforged/neoforge/client/gui/map/MapDecorationRendererManager.class",
            "com.simibubi.create.AllMapDecorationTypes" to
                "com/simibubi/create/AllMapDecorationTypes.class",
            "com.simibubi.create.content.trains.station.StationMarker" to
                "com/simibubi/create/content/trains/station/StationMarker.class",
            "com.simibubi.create.content.trains.station.StationMapData" to
                "com/simibubi/create/content/trains/station/StationMapData.class",
            "com.simibubi.create.foundation.map.StationMapDecorationRenderer" to
                "com/simibubi/create/foundation/map/StationMapDecorationRenderer.class",
            "com.modporter.generated.create.benchmark.ModPorterStrictStationMapHarness" to
                "com/modporter/generated/create/benchmark/ModPorterStrictStationMapHarness.class"
        )
        resources.values.forEachIndexed { index, relative ->
            classpath.resolve(relative).also {
                it.parent.createDirectories()
                it.writeBytes("class-$index".toByteArray())
            }
        }
        val evidence = root.resolve("evidence").also { it.createDirectories() }
        val trace = evidence.resolve("station.trace").also { it.writeText("native station trace\n") }
        val png = evidence.resolve("station.png")
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xff000000.toInt())
        image.setRGB(1, 0, 0xffffffff.toInt())
        image.setRGB(0, 1, 0xff000000.toInt())
        image.setRGB(1, 1, 0xffffffff.toInt())
        ImageIO.write(image, "png", png.toFile())

        val sessionId = UUID.randomUUID().toString()
        val validationId = UUID.randomUUID().toString()
        val bindings = mapOf(
            "runtime.stationHarness.sha256" to sha256(sourceRoot.resolve(harnessRelative)),
            "runtime.buildFiles.sha256" to "1".repeat(64)
        )
        val classpathHash = sha256Directory(classpath)
        val classResources = buildJsonObject {
            resources.forEach { (className, resource) ->
                putJsonObject(className) {
                    put("resource", resource)
                    put("origin", classpath.toRealPath(LinkOption.NOFOLLOW_LINKS).toString())
                    put("sha256", sha256(classpath.resolve(resource)))
                }
            }
        }
        val java = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        ).toRealPath(LinkOption.NOFOLLOW_LINKS)
        val station = buildJsonObject {
            put("schema", CreateStationMapRuntimeValidation.RECEIPT_SCHEMA)
            put("profileId", CreateStationMapRuntimeValidation.PROFILE_ID)
            put("sessionId", sessionId)
            put("validationSessionId", validationId)
            put("status", "PASS")
            put("processStartCount", 1)
            put("worldLoadCount", 1)
            put("scenarioCount", 1)
            put("failedCount", 0)
            put("cleanupFailureCount", 0)
            put("overlapFailureCount", 0)
            put("allScenariosPassed", true)
            put("requestedScenarios", strings(CreateStationMapRuntimeValidation.SCENARIO_ID))
            put("observedScenarios", strings(CreateStationMapRuntimeValidation.SCENARIO_ID))
            putJsonObject("runtime") {
                put("minecraftVersion", StrictSpawnValidationContract.MINECRAFT_VERSION)
                put("neoForgeVersion", StrictSpawnValidationContract.NEOFORGE_VERSION)
                put("javaVersion", "21.0.8")
                put("javaVendor", "fixture")
                put("javaHome", java.parent.parent.toString())
                put("javaExecutable", java.toString())
                put("javaExecutableSha256", sha256(java))
                put("classpathSha256", classpathDigest(classpath, classpathHash))
                putJsonArray("classpathEntries") {
                    add(buildJsonObject {
                        put("path", classpath.toRealPath(LinkOption.NOFOLLOW_LINKS).toString())
                        put("kind", "directory")
                        put("sha256", classpathHash)
                    })
                }
                put("classResources", classResources)
            }
            putJsonObject("bindings") { bindings.forEach { (key, value) -> put(key, value) } }
            putJsonArray("scenarios") {
                add(stationScenario(trace, png))
            }
        }
        val spawn = buildJsonObject {
            put("schema", StrictSpawnValidationContract.RECEIPT_SCHEMA)
            put("profileId", StrictSpawnValidationContract.PROFILE_ID)
            put("sessionId", sessionId)
            put("validationSessionId", validationId)
            put("scenarioCount", 3)
            putJsonArray("scenarios") {
                StrictSpawnValidationContract.scenarioIds.forEach {
                    add(buildJsonObject {
                        put("id", it)
                        put("passed", true)
                        put("cleanupPassed", true)
                    })
                }
            }
        }
        val spawnPath = root.resolve("spawn.json").also { it.writeText(spawn.toString()) }
        val stationPath = root.resolve("station.json").also { it.writeText(station.toString()) }
        val coordinatorPath = root.resolve("coordinator.json")
        val coordinator = coordinator(
            spawn,
            station,
            spawnPath,
            stationPath,
            sessionId,
            validationId
        )
        coordinatorPath.writeText(coordinator.toString())
        val context = CreateStationMapRuntimeValidation.Context(
            spawnProfileId = StrictSpawnValidationContract.PROFILE_ID,
            validationSessionId = validationId,
            spawnReceiptPath = spawnPath.toAbsolutePath().normalize(),
            stationReceiptPath = stationPath.toAbsolutePath().normalize(),
            coordinatorReceiptPath = coordinatorPath.toAbsolutePath().normalize(),
            evidenceDir = evidence.toAbsolutePath().normalize(),
            convertedModDir = converted.toAbsolutePath().normalize(),
            bindings = bindings
        )
        return Fixture(context, spawn, station, coordinator)
    }

    private fun stationScenario(trace: Path, png: Path): JsonObject = buildJsonObject {
        put("id", CreateStationMapRuntimeValidation.SCENARIO_ID)
        put("passed", true)
        put("cleanupPassed", true)
        put("startedAtTick", 100)
        put("finishedAtTick", 180)
        put("semanticChecks", strings(*CreateStationMapRuntimeValidation.semanticCheckIds.toTypedArray()))
        putJsonObject("observations") {
            putJsonObject("providerSaveReload") {
                put("providerClass", "net.minecraft.core.RegistryAccess\$Frozen")
                put("savedTagByteCount", 24)
                put("savedTagSha256", "2".repeat(64))
                put("stationListSize", 1)
                put("reloadedImplementsStationMapData", true)
                put("serverDecoration", decoration("station"))
                put("reloadedDecoration", decoration("station"))
            }
            putJsonObject("packetCodecClientUpdate") {
                put("packetClass", "net.minecraft.network.protocol.game.ClientboundMapItemDataPacket")
                put("connectionType", "NEOFORGE")
                put("encodedByteCount", 42)
                put("encodedSha256", "3".repeat(64))
                put("fullyConsumed", true)
                put("deepFieldsEqual", true)
                put("mapPatchBytesEqual", true)
                put("sentThroughServerConnection", true)
                put("directApplyInvoked", false)
                put("directClientHandlerInvoked", false)
                put("clientObjectDistinctFromServer", true)
                put("decodedDecoration", decoration("station"))
                put("clientDecoration", decoration("station"))
            }
            putJsonObject("firstPersonNativeRenderer") {
                put("productionDelegateClass", "com.simibubi.create.foundation.map.StationMapDecorationRenderer")
                put("productionDelegateReturned", true)
                put("renderThread", true)
                put("ownedMapId", -14001)
                put("heldMapId", -14001)
                put("renderedDecoration", decoration("station"))
                put("itemInHandRenderMapFrame", true)
                put("mapRendererRenderFrame", true)
                put("mapInstanceDrawFrame", true)
                put("rendererManagerRenderFrame", true)
                put("screenshotWidth", 2)
                put("screenshotHeight", 2)
                put("sampledDistinctColors", 2)
            }
            putJsonObject("cleanup") {
                listOf(
                    "serverHandsRestored", "clientHandsRestored", "itemInHandCacheRestored",
                    "screenRestored", "cameraRestored", "hideGuiRestored",
                    "rendererDelegateRestoredByIdentity", "serverMapAbsent", "clientMapAbsent",
                    "mapRendererEntryAbsent", "dynamicTextureEntryAbsent", "chunkQuiescencePassed"
                ).forEach { put(it, true) }
                put("cleanupFenceTicks", 3)
                val quiet = buildJsonObject {
                    put("sampledOnServerThread", true)
                    put("holderCount", 2)
                    put("generationRefSum", 7)
                    put("generationRefNonzero", 1)
                    put("generationRefMax", 7)
                    put("mainThreadTasks", 3)
                    ChunkQuiescenceRuntimeSource.zeroCounterNames.forEach { put(it, 0) }
                    putJsonArray("blockers") {}
                    put("quiescent", true)
                }
                putJsonObject("chunkQuiescence") {
                    put("first", quiet)
                    put("best", quiet)
                    put("last", quiet)
                    put("stableServerTicks", 3)
                    put("stableSinceServerTick", 20)
                    put("lastSampleServerTick", 22)
                    put("requiredFenceTicks", 3)
                }
            }
        }
        putJsonArray("artifacts") {
            add(artifact("trace", trace))
            add(artifact("first_person_png", png))
        }
    }

    private fun coordinator(
        spawn: JsonObject,
        station: JsonObject,
        spawnPath: Path,
        stationPath: Path,
        sessionId: String,
        validationId: String
    ): JsonObject = buildJsonObject {
        put("schema", CreateStationMapRuntimeValidation.COORDINATOR_SCHEMA)
        put("profileId", StrictSpawnValidationContract.PROFILE_ID)
        put("sessionId", sessionId)
        put("validationSessionId", validationId)
        put("status", "PASS")
        put("processStartCount", 1)
        put("worldLoadCount", 1)
        put("scenarioCount", 4)
        put("failedCount", 0)
        put("cleanupFailureCount", 0)
        put("overlapFailureCount", 0)
        put("allScenariosPassed", true)
        put("finalExitRequested", true)
        put("clientBrowseCompleted", true)
        val ids = StrictSpawnValidationContract.scenarioIds +
            CreateStationMapRuntimeValidation.SCENARIO_ID
        put("requestedScenarios", strings(*ids.toTypedArray()))
        put("observedScenarios", strings(*ids.toTypedArray()))
        putJsonObject("children") {
            put("spawn", childRef(spawn, spawnPath))
            put("station", childRef(station, stationPath))
        }
        putJsonArray("scenarios") {
            ids.forEachIndexed { index, id ->
                add(buildJsonObject {
                    put("id", id)
                    put("child", if (index < 3) "spawn" else "station")
                    put("childIndex", if (index < 3) index else 0)
                    put("passed", true)
                    put("cleanupPassed", true)
                })
            }
        }
    }

    private fun childRef(child: JsonObject, path: Path): JsonObject = buildJsonObject {
        put("path", path.toAbsolutePath().normalize().toString())
        put("sha256", sha256(path))
        for (key in listOf("schema", "profileId", "sessionId", "validationSessionId", "scenarioCount")) {
            put(key, child.getValue(key))
        }
    }

    private fun decoration(name: String): JsonObject = buildJsonObject {
        put("typeKey", "create:station")
        put("x", 3)
        put("y", -4)
        put("rotation", 0)
        put("name", name)
    }

    private fun artifact(kind: String, path: Path): JsonObject = buildJsonObject {
        put("kind", kind)
        put("path", path.toAbsolutePath().normalize().toString())
        put("sha256", sha256(path))
    }

    private fun mutateObservation(
        station: JsonObject,
        observation: String,
        key: String,
        value: JsonElement
    ): JsonObject {
        val scenarios = station.getValue("scenarios") as JsonArray
        val scenario = scenarios.single() as JsonObject
        val observations = scenario.getValue("observations") as JsonObject
        val target = observations.getValue(observation) as JsonObject
        val updated = JsonObject(target + (key to value))
        val newObservations = JsonObject(observations + (observation to updated))
        val newScenario = JsonObject(scenario + ("observations" to newObservations))
        return JsonObject(station + ("scenarios" to JsonArray(listOf(newScenario))))
    }

    private fun strings(vararg values: String): JsonArray =
        JsonArray(values.map(::JsonPrimitive))

    private fun assertContains(issues: List<String>, text: String) {
        assertTrue(issues.any { it.contains(text) }, issues.joinToString("\n"))
    }

    private fun classpathDigest(path: Path, sha: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(path.toRealPath(LinkOption.NOFOLLOW_LINKS).toString().toByteArray())
        digest.update(0)
        digest.update("directory".toByteArray())
        digest.update(0)
        digest.update(sha.toByteArray())
        digest.update('\n'.code.toByte())
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    private fun sha256Directory(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = Files.walk(root).use { stream ->
            stream.sorted().filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
        files.forEach {
            digest.update(root.relativize(it).toString().replace('\\', '/').toByteArray())
            digest.update(0)
            digest.update(sha256(it).toByteArray())
            digest.update(0)
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    private fun sha256(path: Path): String =
        java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(path.readBytes())
        )
}
