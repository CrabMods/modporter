package com.modporter.integration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Fail-closed validation for Create's native station-map child receipt and the
 * one-client-world coordinator. The existing spawn receipt is still validated
 * independently by StrictReviewValidationRegistry before these methods run.
 */
internal object CreateStationMapRuntimeValidation {
    const val RECEIPT_SCHEMA = "modporter.strict-review.create-station-map.v1"
    const val COORDINATOR_SCHEMA = "modporter.strict-review.client-world-coordinator.v1"
    const val PROFILE_ID = "create-station-map/native-roundtrip/v1"
    const val SCENARIO_ID = "station_map_native_roundtrip"

    val semanticCheckIds = listOf(
        "station.provider_save_reload",
        "station.packet_codec_client_update",
        "station.first_person_native_renderer"
    )

    private val coordinatorScenarioIds =
        StrictSpawnValidationContract.scenarioIds + SCENARIO_ID
    private val sha256Pattern = Regex("""^[0-9a-f]{64}$""")
    private val requiredClassResources = linkedMapOf(
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
            "com/simibubi/create/foundation/map/StationMapDecorationRenderer.class"
    )

    internal data class Context(
        val spawnProfileId: String,
        val validationSessionId: String,
        val spawnReceiptPath: Path,
        val stationReceiptPath: Path,
        val coordinatorReceiptPath: Path,
        val evidenceDir: Path,
        val convertedModDir: Path,
        val bindings: Map<String, String>
    )

    private data class ClasspathEntry(
        val path: Path,
        val kind: String,
        val sha256: String
    )

    fun verifyStationChild(context: Context, station: JsonObject): List<String> {
        val issues = mutableListOf<String>()
        exactKeys(
            station,
            setOf(
                "schema", "profileId", "sessionId", "validationSessionId", "status",
                "processStartCount", "worldLoadCount", "scenarioCount", "failedCount",
                "cleanupFailureCount", "overlapFailureCount", "allScenariosPassed",
                "requestedScenarios", "observedScenarios", "runtime", "bindings", "scenarios"
            ),
            "station receipt",
            issues
        )
        requireString(station, "schema", RECEIPT_SCHEMA, issues)
        requireString(station, "profileId", PROFILE_ID, issues)
        requireString(station, "validationSessionId", context.validationSessionId, issues)
        requireString(station, "status", "PASS", issues)
        validUuid(station, "sessionId", "station receipt", issues)
        requireInt(station, "processStartCount", 1, issues)
        requireInt(station, "worldLoadCount", 1, issues)
        requireInt(station, "scenarioCount", 1, issues)
        requireInt(station, "failedCount", 0, issues)
        requireInt(station, "cleanupFailureCount", 0, issues)
        requireInt(station, "overlapFailureCount", 0, issues)
        requireBoolean(station, "allScenariosPassed", true, issues)
        requireStringList(station, "requestedScenarios", listOf(SCENARIO_ID), issues)
        requireStringList(station, "observedScenarios", listOf(SCENARIO_ID), issues)
        val bindings = station["bindings"]?.asStringMap()
        if (bindings != context.bindings) {
            issues += "station receipt bindings do not exactly match the sealed manifest"
        }
        station["runtime"]?.objectOrNull()?.let {
            issues += verifyRuntime(context, it)
        } ?: run { issues += "station receipt runtime must be an object" }

        val scenarios = station["scenarios"]?.arrayOrNull()
        if (scenarios == null || scenarios.size != 1) {
            issues += "station receipt scenarios must contain exactly one entry"
        } else {
            val scenario = scenarios.single().objectOrNull()
            if (scenario == null) issues += "station scenario must be an object"
            else issues += verifyStationScenario(context, scenario)
        }
        return issues
    }

    fun verifyCoordinator(
        context: Context,
        coordinator: JsonObject,
        spawn: JsonObject,
        station: JsonObject
    ): List<String> {
        val issues = mutableListOf<String>()
        exactKeys(
            coordinator,
            setOf(
                "schema", "profileId", "sessionId", "validationSessionId", "status",
                "processStartCount", "worldLoadCount", "scenarioCount", "failedCount",
                "cleanupFailureCount", "overlapFailureCount", "allScenariosPassed",
                "finalExitRequested", "clientBrowseCompleted", "requestedScenarios",
                "observedScenarios", "children", "scenarios"
            ),
            "coordinator",
            issues
        )
        requireString(coordinator, "schema", COORDINATOR_SCHEMA, issues)
        requireString(coordinator, "profileId", context.spawnProfileId, issues)
        requireString(coordinator, "validationSessionId", context.validationSessionId, issues)
        requireString(coordinator, "status", "PASS", issues)
        validUuid(coordinator, "sessionId", "coordinator", issues)
        requireInt(coordinator, "processStartCount", 1, issues)
        requireInt(coordinator, "worldLoadCount", 1, issues)
        requireInt(coordinator, "scenarioCount", coordinatorScenarioIds.size, issues)
        requireInt(coordinator, "failedCount", 0, issues)
        requireInt(coordinator, "cleanupFailureCount", 0, issues)
        requireInt(coordinator, "overlapFailureCount", 0, issues)
        requireBoolean(coordinator, "allScenariosPassed", true, issues)
        requireBoolean(coordinator, "finalExitRequested", true, issues)
        requireBoolean(coordinator, "clientBrowseCompleted", true, issues)
        requireStringList(coordinator, "requestedScenarios", coordinatorScenarioIds, issues)
        requireStringList(coordinator, "observedScenarios", coordinatorScenarioIds, issues)

        val coordinatorSession = string(coordinator, "sessionId")
        val spawnSession = string(spawn, "sessionId")
        val stationSession = string(station, "sessionId")
        if (coordinatorSession == null ||
            coordinatorSession != spawnSession ||
            coordinatorSession != stationSession
        ) {
            issues += "coordinator and both children must share the managed wrapper sessionId"
        }
        if (string(spawn, "validationSessionId") != context.validationSessionId ||
            string(station, "validationSessionId") != context.validationSessionId
        ) {
            issues += "both children must share the coordinator validationSessionId"
        }

        val children = coordinator["children"]?.objectOrNull()
        if (children == null) {
            issues += "coordinator children must be an object"
        } else {
            exactKeys(children, setOf("spawn", "station"), "coordinator children", issues)
            verifyChildReference(
                children["spawn"]?.objectOrNull(),
                "spawn",
                context.spawnReceiptPath,
                spawn,
                StrictSpawnValidationContract.RECEIPT_SCHEMA,
                context.spawnProfileId,
                StrictSpawnValidationContract.scenarioIds.size,
                issues
            )
            verifyChildReference(
                children["station"]?.objectOrNull(),
                "station",
                context.stationReceiptPath,
                station,
                RECEIPT_SCHEMA,
                PROFILE_ID,
                1,
                issues
            )
        }

        val coordinatorScenarios = coordinator["scenarios"]?.arrayOrNull()
        val spawnScenarios = spawn["scenarios"]?.arrayOrNull()
        val stationScenarios = station["scenarios"]?.arrayOrNull()
        if (coordinatorScenarios == null || coordinatorScenarios.size != coordinatorScenarioIds.size) {
            issues += "coordinator scenarios must contain the exact four scenario projection"
        } else if (spawnScenarios == null ||
            spawnScenarios.size != StrictSpawnValidationContract.scenarioIds.size ||
            stationScenarios == null ||
            stationScenarios.size != 1
        ) {
            issues += "coordinator children lack the exact scenario arrays needed for projection"
        } else {
            val childScenarios = spawnScenarios + stationScenarios
            for ((index, expectedId) in coordinatorScenarioIds.withIndex()) {
                val projected = coordinatorScenarios[index].objectOrNull()
                val child = childScenarios[index].objectOrNull()
                if (projected == null || child == null) {
                    issues += "coordinator scenario $index or child scenario is not an object"
                    continue
                }
                val expectedChild = if (index < spawnScenarios.size) "spawn" else "station"
                val expectedChildIndex = if (expectedChild == "spawn") index else 0
                exactKeys(
                    projected,
                    setOf("id", "child", "childIndex", "passed", "cleanupPassed"),
                    "coordinator scenario $index",
                    issues
                )
                requireString(projected, "id", expectedId, issues)
                requireString(projected, "child", expectedChild, issues)
                requireInt(projected, "childIndex", expectedChildIndex, issues)
                requireBoolean(projected, "passed", true, issues)
                requireBoolean(projected, "cleanupPassed", true, issues)
                if (string(child, "id") != expectedId ||
                    boolean(child, "passed") != true ||
                    boolean(child, "cleanupPassed") != true
                ) {
                    issues += "coordinator scenario $expectedId does not project a passing child scenario"
                }
            }
        }
        return issues
    }

    private fun verifyStationScenario(context: Context, scenario: JsonObject): List<String> {
        val issues = mutableListOf<String>()
        exactKeys(
            scenario,
            setOf(
                "id", "passed", "cleanupPassed", "startedAtTick", "finishedAtTick",
                "semanticChecks", "observations", "artifacts"
            ),
            "station scenario",
            issues
        )
        requireString(scenario, "id", SCENARIO_ID, issues)
        requireBoolean(scenario, "passed", true, issues)
        requireBoolean(scenario, "cleanupPassed", true, issues)
        val started = long(scenario, "startedAtTick")
        val finished = long(scenario, "finishedAtTick")
        if (started == null || finished == null || started < 0L || finished < started) {
            issues += "station scenario must record ordered nonnegative tick bounds"
        }
        requireStringList(scenario, "semanticChecks", semanticCheckIds, issues)

        val observations = scenario["observations"]?.objectOrNull()
        if (observations == null) {
            issues += "station scenario observations must be an object"
            return issues
        }
        exactKeys(
            observations,
            setOf(
                "providerSaveReload", "packetCodecClientUpdate",
                "firstPersonNativeRenderer", "cleanup"
            ),
            "station observations",
            issues
        )
        val provider = observations["providerSaveReload"]?.objectOrNull()
        val packet = observations["packetCodecClientUpdate"]?.objectOrNull()
        val renderer = observations["firstPersonNativeRenderer"]?.objectOrNull()
        val cleanup = observations["cleanup"]?.objectOrNull()
        if (provider == null) issues += "providerSaveReload observation is missing"
        if (packet == null) issues += "packetCodecClientUpdate observation is missing"
        if (renderer == null) issues += "firstPersonNativeRenderer observation is missing"
        if (cleanup == null) issues += "cleanup observation is missing"
        if (provider != null) verifyProvider(provider, issues)
        if (packet != null) verifyPacket(packet, issues)
        if (renderer != null) verifyRenderer(renderer, issues)
        if (cleanup != null) verifyCleanup(cleanup, issues)

        val decorations = listOfNotNull(
            provider?.get("serverDecoration")?.objectOrNull(),
            provider?.get("reloadedDecoration")?.objectOrNull(),
            packet?.get("decodedDecoration")?.objectOrNull(),
            packet?.get("clientDecoration")?.objectOrNull(),
            renderer?.get("renderedDecoration")?.objectOrNull()
        )
        if (decorations.size != 5) {
            issues += "station scenario must record all five decoration observations"
        } else {
            decorations.forEachIndexed { index, decoration ->
                verifyDecoration(decoration, "decoration[$index]", issues)
            }
            if (decorations.distinct().size != 1) {
                issues += "server, reload, decoded packet, client and rendered decorations differ"
            }
        }
        val ownedMapId = renderer?.let { int(it, "ownedMapId") }
        val heldMapId = renderer?.let { int(it, "heldMapId") }
        if (ownedMapId == null || ownedMapId >= 0 || heldMapId != ownedMapId) {
            issues += "renderer must observe the same owned negative map id in the held item"
        }

        val artifacts = scenario["artifacts"]?.arrayOrNull()
        if (artifacts == null || artifacts.size != 2) {
            issues += "station scenario must contain exactly trace and first_person_png artifacts"
        } else {
            val artifactsByKind = artifacts.mapNotNull { it.objectOrNull() }
                .associateBy { string(it, "kind") }
            if (artifactsByKind.keys != setOf("trace", "first_person_png")) {
                issues += "station artifacts must be exactly trace and first_person_png"
            }
            val trace = artifactsByKind["trace"]?.let {
                verifyArtifact(context, it, "trace", issues)
            }
            if (trace != null && trace.readBytes().isEmpty()) {
                issues += "station trace artifact must not be empty"
            }
            val png = artifactsByKind["first_person_png"]?.let {
                verifyArtifact(context, it, "first_person_png", issues)
            }
            if (png != null && renderer != null) {
                val image = runCatching { ImageIO.read(png.toFile()) }.getOrNull()
                if (image == null) {
                    issues += "first-person artifact is not a decodable PNG"
                } else {
                    val bounded = image.width > 0 && image.height > 0 &&
                        image.width <= 4096 && image.height <= 4096 &&
                        image.width.toLong() * image.height.toLong() <= 16_777_216L
                    if (!bounded) {
                        issues += "first-person PNG dimensions exceed the bounded validator surface"
                    } else {
                        val expectedWidth = int(renderer, "screenshotWidth")
                        val expectedHeight = int(renderer, "screenshotHeight")
                        val expectedColors = int(renderer, "sampledDistinctColors")
                        if (image.width != expectedWidth || image.height != expectedHeight) {
                            issues += "first-person PNG dimensions do not match the receipt"
                        }
                        val colors = buildSet {
                            for (y in 0 until image.height) for (x in 0 until image.width) {
                                add(image.getRGB(x, y))
                            }
                        }.size
                        if (expectedColors == null || expectedColors < 2 || colors != expectedColors) {
                            issues += "first-person PNG distinct-color count is missing, blank, or incorrect"
                        }
                    }
                }
            }
        }
        return issues
    }

    private fun verifyProvider(value: JsonObject, issues: MutableList<String>) {
        exactKeys(
            value,
            setOf(
                "providerClass", "savedTagByteCount", "savedTagSha256",
                "stationListSize", "reloadedImplementsStationMapData",
                "serverDecoration", "reloadedDecoration"
            ),
            "providerSaveReload",
            issues
        )
        if (string(value, "providerClass").isNullOrBlank()) issues += "provider class must be non-empty"
        val byteCount = int(value, "savedTagByteCount")
        if (byteCount == null || byteCount <= 0) issues += "saved tag byte count must be positive"
        validSha(value, "savedTagSha256", "saved tag", issues)
        requireInt(value, "stationListSize", 1, issues)
        requireBoolean(value, "reloadedImplementsStationMapData", true, issues)
    }

    private fun verifyPacket(value: JsonObject, issues: MutableList<String>) {
        exactKeys(
            value,
            setOf(
                "packetClass", "connectionType", "encodedByteCount", "encodedSha256",
                "fullyConsumed", "deepFieldsEqual", "mapPatchBytesEqual",
                "sentThroughServerConnection", "directApplyInvoked",
                "directClientHandlerInvoked", "clientObjectDistinctFromServer",
                "decodedDecoration", "clientDecoration"
            ),
            "packetCodecClientUpdate",
            issues
        )
        requireString(
            value,
            "packetClass",
            "net.minecraft.network.protocol.game.ClientboundMapItemDataPacket",
            issues
        )
        if (string(value, "connectionType").isNullOrBlank()) issues += "connection type must be non-empty"
        val encoded = int(value, "encodedByteCount")
        if (encoded == null || encoded <= 0) issues += "encoded packet byte count must be positive"
        validSha(value, "encodedSha256", "encoded packet", issues)
        for (key in listOf(
            "fullyConsumed", "deepFieldsEqual", "mapPatchBytesEqual",
            "sentThroughServerConnection", "clientObjectDistinctFromServer"
        )) requireBoolean(value, key, true, issues)
        requireBoolean(value, "directApplyInvoked", false, issues)
        requireBoolean(value, "directClientHandlerInvoked", false, issues)
    }

    private fun verifyRenderer(value: JsonObject, issues: MutableList<String>) {
        exactKeys(
            value,
            setOf(
                "productionDelegateClass", "productionDelegateReturned", "renderThread",
                "ownedMapId", "heldMapId", "renderedDecoration",
                "itemInHandRenderMapFrame", "mapRendererRenderFrame",
                "mapInstanceDrawFrame", "rendererManagerRenderFrame",
                "screenshotWidth", "screenshotHeight", "sampledDistinctColors"
            ),
            "firstPersonNativeRenderer",
            issues
        )
        requireString(
            value,
            "productionDelegateClass",
            "com.simibubi.create.foundation.map.StationMapDecorationRenderer",
            issues
        )
        for (key in listOf(
            "productionDelegateReturned", "renderThread", "itemInHandRenderMapFrame",
            "mapRendererRenderFrame", "mapInstanceDrawFrame", "rendererManagerRenderFrame"
        )) requireBoolean(value, key, true, issues)
        for (key in listOf("screenshotWidth", "screenshotHeight")) {
            val size = int(value, key)
            if (size == null || size <= 0) issues += "$key must be positive"
        }
    }

    private fun verifyCleanup(value: JsonObject, issues: MutableList<String>) {
        val booleans = setOf(
            "serverHandsRestored", "clientHandsRestored", "itemInHandCacheRestored",
            "screenRestored", "cameraRestored", "hideGuiRestored",
            "rendererDelegateRestoredByIdentity", "serverMapAbsent", "clientMapAbsent",
            "mapRendererEntryAbsent", "dynamicTextureEntryAbsent", "chunkQuiescencePassed"
        )
        exactKeys(value, booleans + setOf("cleanupFenceTicks", "chunkQuiescence"), "cleanup", issues)
        booleans.forEach { requireBoolean(value, it, true, issues) }
        val fence = int(value, "cleanupFenceTicks")
        if (fence == null || fence <= 0) issues += "cleanup must cross a positive render/tick fence"
        val health = value["chunkQuiescence"]?.objectOrNull()
        if (health == null) {
            issues += "cleanup chunk quiescence evidence is missing"
            return
        }
        exactKeys(health, setOf("first", "best", "last", "stableServerTicks", "stableSinceServerTick",
            "lastSampleServerTick", "requiredFenceTicks"), "chunk quiescence", issues)
        for (name in listOf("first", "best", "last")) {
            val snapshot = health[name]?.objectOrNull()
            if (snapshot == null) {
                issues += "chunk quiescence $name snapshot is missing"
                continue
            }
            exactKeys(snapshot, ChunkQuiescenceRuntimeSource.snapshotKeys.toSet(), "chunk quiescence $name", issues)
            requireBoolean(snapshot, "sampledOnServerThread", true, issues)
            val holderCount = int(snapshot, "holderCount")
            if (holderCount == null || holderCount <= 0) issues += "chunk quiescence $name must sample real chunk holders"
            for (counter in ChunkQuiescenceRuntimeSource.informationalCounterNames) {
                val primitive = snapshot[counter] as? JsonPrimitive
                val count = primitive?.takeUnless { it.isString }?.content?.toLongOrNull()
                if (count == null || count < 0) issues += "chunk quiescence $name.$counter must be a nonnegative count"
            }
            var countersZero = true
            for (counter in ChunkQuiescenceRuntimeSource.zeroCounterNames) {
                val primitive = snapshot[counter] as? JsonPrimitive
                val count = primitive?.takeUnless { it.isString }?.content?.toLongOrNull()
                if (count == null || count < 0) issues += "chunk quiescence $name.$counter must be a nonnegative count"
                if (count != 0L) countersZero = false
                if (name == "last" && count != 0L) issues += "chunk quiescence last.$counter must be 0 before exit"
            }
            val blockers = snapshot["blockers"]?.arrayOrNull()
            if (blockers == null || blockers.size > 8) issues += "chunk quiescence $name blockers must be bounded"
            val quiet = countersZero && holderCount != null && holderCount > 0 && blockers?.isEmpty() == true
            requireBoolean(snapshot, "quiescent", quiet, issues)
            if (name == "last" && !quiet) issues += "last chunk quiescence snapshot is not clean"
        }
        val required = int(health, "requiredFenceTicks")
        val stable = int(health, "stableServerTicks")
        val since = long(health, "stableSinceServerTick")
        val last = long(health, "lastSampleServerTick")
        if (required != 3 || fence != required || stable == null || stable < 3 ||
            since == null || since < 0 || last == null || last < since || last - since + 1 != stable.toLong()
        ) issues += "chunk quiescence must cross the existing three stable server ticks before exit"
    }

    private fun verifyDecoration(
        value: JsonObject,
        label: String,
        issues: MutableList<String>
    ) {
        exactKeys(value, setOf("typeKey", "x", "y", "rotation", "name"), label, issues)
        requireString(value, "typeKey", "create:station", issues)
        if (int(value, "x") == null || int(value, "y") == null) {
            issues += "$label x/y must be integers"
        }
        requireInt(value, "rotation", 0, issues)
        if (string(value, "name").isNullOrBlank()) issues += "$label name must be non-empty"
    }

    private fun verifyRuntime(context: Context, runtime: JsonObject): List<String> {
        val issues = mutableListOf<String>()
        exactKeys(
            runtime,
            setOf(
                "minecraftVersion", "neoForgeVersion", "javaVersion", "javaVendor",
                "javaHome", "javaExecutable", "javaExecutableSha256",
                "classpathSha256", "classpathEntries", "classResources"
            ),
            "station runtime",
            issues
        )
        requireString(runtime, "minecraftVersion", StrictSpawnValidationContract.MINECRAFT_VERSION, issues)
        requireString(runtime, "neoForgeVersion", StrictSpawnValidationContract.NEOFORGE_VERSION, issues)
        val javaVersion = string(runtime, "javaVersion")
        if (javaVersion == null || javaVersion.removePrefix("1.").takeWhile(Char::isDigit).toIntOrNull() != 21) {
            issues += "station runtime must use Java 21"
        }
        if (string(runtime, "javaVendor").isNullOrBlank()) issues += "javaVendor must be non-empty"
        if (string(runtime, "javaHome").isNullOrBlank()) issues += "javaHome must be non-empty"
        val javaExecutable = realFile(runtime, "javaExecutable", issues)
        validSha(runtime, "javaExecutableSha256", "java executable", issues)
        if (javaExecutable != null &&
            string(runtime, "javaExecutableSha256") != sha256(javaExecutable)
        ) {
            issues += "java executable hash changed after runtime"
        }

        val entries = parseClasspath(runtime, issues)
        val expectedClasspathHash = string(runtime, "classpathSha256")
        if (expectedClasspathHash == null || !sha256Pattern.matches(expectedClasspathHash)) {
            issues += "classpathSha256 must be lowercase SHA-256"
        } else if (entries.isNotEmpty() && expectedClasspathHash != sha256Classpath(entries)) {
            issues += "classpathSha256 does not bind ordered entry paths and bytes"
        }

        val classResources = runtime["classResources"]?.objectOrNull()
        if (classResources == null) {
            issues += "classResources must be an object"
        } else {
            val sourceRoot = context.convertedModDir.resolve("src/main/java")
            val stationHarness = findUniqueNamedFile(
                sourceRoot,
                StrictSpawnValidationContract.STATION_HARNESS_FILE
            )
            val harnessResource = stationHarness?.let {
                sourceRoot.relativize(it)
                    .toString().replace('\\', '/').removeSuffix(".java") + ".class"
            }
            val harnessClass = harnessResource?.removeSuffix(".class")?.replace('/', '.')
            if (harnessClass == null || harnessResource == null) {
                issues += "could not resolve the unique staged station harness class"
            }
            val expectedResources = linkedMapOf<String, String>().apply {
                putAll(requiredClassResources)
                if (harnessClass != null && harnessResource != null) put(harnessClass, harnessResource)
            }
            exactKeys(classResources, expectedResources.keys, "classResources", issues)
            for ((className, expectedResource) in expectedResources) {
                val resourceObject = classResources[className]?.objectOrNull()
                if (resourceObject == null) {
                    issues += "classResources lacks $className"
                    continue
                }
                exactKeys(resourceObject, setOf("resource", "origin", "sha256"), "class resource $className", issues)
                val resource = string(resourceObject, "resource")
                val origin = string(resourceObject, "origin")?.let {
                    runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull()
                }
                val expectedSha = string(resourceObject, "sha256")
                if (resource != expectedResource ||
                    origin == null || expectedSha == null || !sha256Pattern.matches(expectedSha)
                ) {
                    issues += "class resource $className has invalid resource/origin/hash"
                    continue
                }
                val matching = entries.filter { it.path == origin }
                if (matching.size != 1) {
                    issues += "class resource $className origin is not one bound classpath entry"
                    continue
                }
                val resourceMatches = entries.mapNotNull { entry ->
                    hashResource(entry, resource)?.let { entry to it }
                }
                if (resourceMatches.size != 1 || resourceMatches.single().first.path != origin) {
                    issues += "class resource $className is absent, duplicated, or loaded from a different origin"
                    continue
                }
                val actual = resourceMatches.single().second
                if (actual == null || actual != expectedSha) {
                    issues += "class resource $className bytes do not match its bound origin"
                }
            }
        }
        return issues
    }

    private fun parseClasspath(runtime: JsonObject, issues: MutableList<String>): List<ClasspathEntry> {
        val raw = runtime["classpathEntries"]?.arrayOrNull()
        if (raw == null || raw.isEmpty()) {
            issues += "classpathEntries must be a non-empty array"
            return emptyList()
        }
        val entries = mutableListOf<ClasspathEntry>()
        for ((index, element) in raw.withIndex()) {
            val entry = element.objectOrNull()
            if (entry == null) {
                issues += "classpathEntries[$index] must be an object"
                continue
            }
            exactKeys(entry, setOf("path", "kind", "sha256"), "classpathEntries[$index]", issues)
            val pathValue = string(entry, "path")
            val kind = string(entry, "kind")
            val expectedSha = string(entry, "sha256")
            val path = pathValue?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
            val real = path?.let { runCatching { it.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull() }
            if (kind == null) {
                issues += "classpathEntries[$index] kind is missing"
                continue
            }
            if (path == null || real == null || real != path || pathValue != path.toString() ||
                Files.isSymbolicLink(path) || kind !in setOf("file", "directory") ||
                expectedSha == null || !sha256Pattern.matches(expectedSha)
            ) {
                issues += "classpathEntries[$index] has invalid real path/kind/hash"
                continue
            }
            val actualSha = when (kind) {
                "file" -> path.takeIf { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }?.let(::sha256)
                "directory" -> path.takeIf { it.isDirectory(LinkOption.NOFOLLOW_LINKS) }?.let {
                    runCatching { sha256Directory(it) }.getOrNull()
                }
                else -> null
            }
            if (actualSha == null || actualSha != expectedSha) {
                issues += "classpathEntries[$index] bytes changed after runtime"
                continue
            }
            entries += ClasspathEntry(path, kind, expectedSha)
        }
        if (entries.map { it.path }.distinct().size != entries.size) {
            issues += "classpathEntries contains duplicate real paths"
        }
        return entries
    }

    private fun verifyChildReference(
        reference: JsonObject?,
        label: String,
        expectedPath: Path,
        actualChild: JsonObject,
        schema: String,
        profileId: String,
        scenarioCount: Int,
        issues: MutableList<String>
    ) {
        if (reference == null) {
            issues += "coordinator child $label is missing"
            return
        }
        exactKeys(
            reference,
            setOf(
                "path", "sha256", "schema", "profileId", "sessionId",
                "validationSessionId", "scenarioCount"
            ),
            "coordinator child $label",
            issues
        )
        val path = string(reference, "path")?.let {
            runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull()
        }
        if (path != expectedPath.toAbsolutePath().normalize()) {
            issues += "coordinator child $label path differs from the declared child receipt"
        }
        val expectedSha = string(reference, "sha256")
        if (expectedSha == null || !sha256Pattern.matches(expectedSha) ||
            !expectedPath.isRegularFile(LinkOption.NOFOLLOW_LINKS) ||
            sha256(expectedPath) != expectedSha
        ) {
            issues += "coordinator child $label hash does not bind the current child receipt"
        }
        requireString(reference, "schema", schema, issues)
        requireString(reference, "profileId", profileId, issues)
        requireInt(reference, "scenarioCount", scenarioCount, issues)
        for (key in listOf("schema", "profileId", "sessionId", "validationSessionId", "scenarioCount")) {
            if (reference[key] != actualChild[key]) {
                issues += "coordinator child $label copied $key differs from the child receipt"
            }
        }
    }

    private fun verifyArtifact(
        context: Context,
        value: JsonObject,
        expectedKind: String,
        issues: MutableList<String>
    ): Path? {
        exactKeys(value, setOf("kind", "path", "sha256"), "artifact $expectedKind", issues)
        requireString(value, "kind", expectedKind, issues)
        val pathValue = string(value, "path")
        val expectedSha = string(value, "sha256")
        if (pathValue.isNullOrBlank() || expectedSha == null || !sha256Pattern.matches(expectedSha)) {
            issues += "artifact $expectedKind has invalid path/hash"
            return null
        }
        val root = context.evidenceDir.toAbsolutePath().normalize()
        val supplied = runCatching { Path.of(pathValue) }.getOrNull()
        val path = supplied?.let {
            if (it.isAbsolute) it.toAbsolutePath().normalize() else root.resolve(it).normalize()
        }
        if (path == null || !path.startsWith(root) ||
            !path.isRegularFile(LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
        ) {
            issues += "artifact $expectedKind is missing, symlinked, or escapes the evidence directory"
            return null
        }
        if (sha256(path) != expectedSha) {
            issues += "artifact $expectedKind hash does not match current bytes"
            return null
        }
        return path
    }

    private fun realFile(value: JsonObject, key: String, issues: MutableList<String>): Path? {
        val text = string(value, key)
        val path = text?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        val real = path?.let { runCatching { it.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull() }
        if (path == null || real != path || text != path.toString() ||
            !path.isRegularFile(LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
        ) {
            issues += "$key must identify a real absolute non-symlink file"
            return null
        }
        return path
    }

    private fun findUniqueNamedFile(root: Path, fileName: String): Path? {
        if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) return null
        val matches = Files.walk(root).use { stream ->
            stream.filter {
                it.isRegularFile(LinkOption.NOFOLLOW_LINKS) && it.fileName.toString() == fileName
            }.toList()
        }
        return matches.singleOrNull()?.toAbsolutePath()?.normalize()
    }

    private fun hashResource(entry: ClasspathEntry, resource: String): String? =
        when (entry.kind) {
            "directory" -> entry.path.resolve(resource).normalize()
                .takeIf { it.startsWith(entry.path) && it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
                ?.let(::sha256)
            "file" -> runCatching {
                ZipFile(entry.path.toFile()).use { zip ->
                    val zipEntry = zip.getEntry(resource) ?: return@use null
                    sha256(zip.getInputStream(zipEntry).readBytes())
                }
            }.getOrNull()
            else -> null
        }

    private fun sha256Classpath(entries: List<ClasspathEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach {
            digest.update(it.path.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(it.kind.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(it.sha256.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    internal fun sha256Directory(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = Files.walk(root).use { stream ->
            stream.filter {
                if (Files.isSymbolicLink(it)) error("classpath directory contains a symbolic link")
                it.isRegularFile(LinkOption.NOFOLLOW_LINKS)
            }.toList()
        }
            // the harness and the supplemental auditor (sha256-relative-nul-file-sha256-nul-v1) order
            // by the relative '/'-separated name in code point order
            .sortedBy { root.relativize(it).toString().replace('\\', '/') }
        files.forEach {
            digest.update(root.relativize(it).toString().replace('\\', '/').toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(sha256(it).toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    private fun exactKeys(
        value: JsonObject,
        expected: Set<String>,
        label: String,
        issues: MutableList<String>
    ) {
        if (value.keys != expected) {
            issues += "$label keys differ: missing=${expected - value.keys} extra=${value.keys - expected}"
        }
    }

    private fun requireString(
        value: JsonObject,
        key: String,
        expected: String,
        issues: MutableList<String>
    ) {
        if (string(value, key) != expected) issues += "$key must be '$expected'"
    }

    private fun requireInt(
        value: JsonObject,
        key: String,
        expected: Int,
        issues: MutableList<String>
    ) {
        if (int(value, key) != expected) issues += "$key must be $expected"
    }

    private fun requireBoolean(
        value: JsonObject,
        key: String,
        expected: Boolean,
        issues: MutableList<String>
    ) {
        if (boolean(value, key) != expected) issues += "$key must be $expected"
    }

    private fun requireStringList(
        value: JsonObject,
        key: String,
        expected: List<String>,
        issues: MutableList<String>
    ) {
        if (value[key]?.asStringList() != expected) issues += "$key must equal $expected"
    }

    private fun validUuid(
        value: JsonObject,
        key: String,
        label: String,
        issues: MutableList<String>
    ) {
        val actual = string(value, key)
        if (actual.isNullOrBlank() || runCatching { UUID.fromString(actual) }.isFailure) {
            issues += "$label $key must be a UUID"
        }
    }

    private fun validSha(
        value: JsonObject,
        key: String,
        label: String,
        issues: MutableList<String>
    ) {
        val actual = string(value, key)
        if (actual == null || !sha256Pattern.matches(actual)) {
            issues += "$label $key must be lowercase SHA-256"
        }
    }

    private fun string(value: JsonObject, key: String): String? =
        value[key]?.primitiveOrNull()?.contentOrNull
    private fun int(value: JsonObject, key: String): Int? =
        value[key]?.primitiveOrNull()?.intOrNull
    private fun long(value: JsonObject, key: String): Long? =
        value[key]?.primitiveOrNull()?.longOrNull
    private fun boolean(value: JsonObject, key: String): Boolean? =
        value[key]?.primitiveOrNull()?.contentOrNull?.toBooleanStrictOrNull()
    private fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
    private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.arrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.asStringList(): List<String>? =
        (this as? JsonArray)?.map { it.primitiveOrNull()?.contentOrNull ?: return null }
    private fun JsonElement.asStringMap(): Map<String, String>? =
        (this as? JsonObject)?.mapValues { it.value.primitiveOrNull()?.contentOrNull ?: return null }
    private fun sha256(path: Path): String = sha256(path.readBytes())
    private fun sha256(bytes: ByteArray): String =
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
