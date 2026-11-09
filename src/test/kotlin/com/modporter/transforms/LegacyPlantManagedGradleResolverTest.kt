package com.modporter.transforms

import com.modporter.core.transforms.structural.InventoryPhase
import com.modporter.core.transforms.structural.LegacyPlantCoverageV2Verifier
import com.modporter.core.transforms.structural.LegacyPlantManagedCommand
import com.modporter.core.transforms.structural.LegacyPlantManagedCommandExecutor
import com.modporter.core.transforms.structural.LegacyPlantMetadataTransportFailureClassifier
import com.modporter.core.transforms.structural.LegacyPlantResolutionProvenance
import com.modporter.core.transforms.structural.ManagedLegacyPlantGradleResolver
import com.modporter.core.transforms.structural.ResolvedArtifact
import com.modporter.core.transforms.structural.ResolvedGraph
import com.modporter.core.transforms.structural.legacyPlantBuildInputIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val METADATA_PATH =
    "dev/engine-room/flywheel/flywheel-forge-1.20.1/maven-metadata.xml"

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun sha256(text: String): String = sha256(text.toByteArray())

private fun isGeneratedBuildPath(path: String): Boolean {
    val segments = path.split('/')
    val sourceBoundary = segments.indexOfFirst { it == "src" || it == "resources" }
    return segments.withIndex().any { (index, segment) ->
        segment in setOf("build", ".gradle") &&
            (sourceBoundary < 0 || index < sourceBoundary)
    }
}

class LegacyPlantManagedGradleResolverTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `online success records non-asserted metadata freshness`() {
        val fixture = fixture("online")
        val executor = FakeExecutor { _, invocation ->
            invocation.log.writeText("online resolution complete\n")
            writeGraph(invocation, fixture)
            0
        }

        val graph = resolver(fixture, executor).resolve(fixture.stage, InventoryPhase.SOURCE)

        assertEquals("online", graph.resolutionProvenance?.origin)
        assertEquals("not-asserted", graph.resolutionProvenance?.metadataFreshness)
        assertEquals(1, executor.invocations.size)
        assertFalse("--offline" in executor.invocations.single().command)
        assertFalse(executor.invocations.single().log.exists())
        assertFalse(outputOf(executor.invocations.single()).exists())
    }

    @Test
    fun `metadata transport failure retries once through managed offline cache`() {
        val fixture = fixture("offline")
        val failure = metadataFailure()
        val offlineText = "offline cache resolution complete\n"
        val executor = FakeExecutor { index, invocation ->
            if (index == 0) {
                invocation.log.writeText(failure)
                1
            } else {
                invocation.log.writeText(offlineText)
                writeGraph(invocation, fixture)
                0
            }
        }

        val graph = resolver(fixture, executor).resolve(fixture.stage, InventoryPhase.SOURCE)

        assertEquals(2, executor.invocations.size)
        assertFalse("--offline" in executor.invocations[0].command)
        val offline = executor.invocations[1].command
        assertEquals(offline.indexOf("gradle") + 1, offline.indexOf("--offline"))
        assertTrue(executor.invocations[0].log.exists())
        assertTrue(executor.invocations[1].log.exists())
        assertEquals(offlineText, executor.invocations[1].log.readText())
        assertFalse(outputOf(executor.invocations[1]).exists())
        assertEquals(
            LegacyPlantResolutionProvenance(
                origin = "gradle-offline-complete-cache",
                metadataFreshness = "cached",
                sourceIdentitySha256 =
                    legacyPlantBuildInputIdentity(fixture.buildFileHashes()),
                recoveryReason = "dependency-metadata-transport-failure",
                failedAttemptExitCode = 1,
                failedAttemptLogSha256 = sha256(failure),
                recoveryAttemptLogSha256 = sha256(offlineText)
            ),
            graph.resolutionProvenance
        )
    }

    @Test
    fun `buildSrc generated outputs do not drift but buildSrc sources remain bound`() {
        val fixture = fixture("build-src-generated")
        val executor = FakeExecutor { _, invocation ->
            fixture.stage.resolve("buildSrc/build/classes/kotlin/main/Plugin.class")
                .also { it.parent.createDirectories() }
                .writeBytes(byteArrayOf(1, 2, 3))
            fixture.stage.resolve("module/build/generated.gradle")
                .also { it.parent.createDirectories() }
                .writeText("generated = true\n")
            fixture.stage.resolve(".gradle/caches/state.bin")
                .also { it.parent.createDirectories() }
                .writeBytes(byteArrayOf(4, 5, 6))
            invocation.log.writeText("online resolution complete\n")
            writeGraph(invocation, fixture)
            0
        }

        val graph = resolver(fixture, executor).resolve(fixture.stage, InventoryPhase.SOURCE)

        assertTrue("buildSrc/src/main/kotlin/Plugin.kt" in graph.buildFileHashes)
        assertTrue("buildSrc/src/main/kotlin/build/Helper.kt" in graph.buildFileHashes)
        assertTrue(
            "buildSrc/src/main/resources/META-INF/gradle-plugins/example.properties" in
                graph.buildFileHashes
        )
        assertTrue("gradle/libs.versions.toml" in graph.buildFileHashes)
        assertFalse(
            graph.buildFileHashes.keys.any(::isGeneratedBuildPath)
        )
    }

    @Test
    fun `source drift and failed output refuse recovery`() {
        val onlineDrift = fixture("online-drift")
        assertFails {
            resolver(onlineDrift, FakeExecutor { _, invocation ->
                onlineDrift.buildFile.writeText("changed = true\n")
                writeGraph(invocation, onlineDrift)
                0
            }).resolve(onlineDrift.stage, InventoryPhase.SOURCE)
        }

        val offlineDrift = fixture("offline-drift")
        assertFails {
            resolver(offlineDrift, FakeExecutor { index, invocation ->
                if (index == 0) {
                    invocation.log.writeText(metadataFailure())
                    1
                } else {
                    offlineDrift.buildFile.writeText("changed = true\n")
                    writeGraph(invocation, offlineDrift)
                    0
                }
            }).resolve(offlineDrift.stage, InventoryPhase.SOURCE)
        }

        val failedOutput = fixture("failed-output")
        val failedExecutor = FakeExecutor { _, invocation ->
            invocation.log.writeText(metadataFailure())
            writeGraph(invocation, failedOutput)
            1
        }
        assertFails {
            resolver(failedOutput, failedExecutor)
                .resolve(failedOutput.stage, InventoryPhase.SOURCE)
        }
        assertTrue(outputOf(failedExecutor.invocations.single()).exists())

        val buildSrcDrift = fixture("build-src-drift")
        val buildSrcExecutor = FakeExecutor { _, invocation ->
            buildSrcDrift.buildSrcSource.writeText("class ChangedPlugin\n")
            invocation.log.writeText("online resolution complete\n")
            writeGraph(invocation, buildSrcDrift)
            0
        }
        assertFails {
            resolver(buildSrcDrift, buildSrcExecutor)
                .resolve(buildSrcDrift.stage, InventoryPhase.SOURCE)
        }
        assertTrue(outputOf(buildSrcExecutor.invocations.single()).exists())
    }

    @Test
    fun `offline recovery requires exit zero and a new graph`() {
        listOf("nonzero", "timeout", "missing-output").forEach { case ->
            val fixture = fixture("offline-$case")
            val executor = FakeExecutor { index, invocation ->
                if (index == 0) {
                    invocation.log.writeText(metadataFailure())
                    1
                } else {
                    invocation.log.writeText("offline $case\n")
                    when (case) {
                        "nonzero" -> 2
                        "timeout" -> null
                        else -> 0
                    }
                }
            }
            assertFails {
                resolver(fixture, executor).resolve(fixture.stage, InventoryPhase.SOURCE)
            }
            assertEquals(2, executor.invocations.size)
        }
    }

    @Test
    fun `emitted graph remains fully verified`() {
        GraphMutation.entries.filter { it != GraphMutation.NONE }.forEach { mutation ->
            val fixture = fixture("graph-${mutation.name.lowercase()}")
            val executor = FakeExecutor { _, invocation ->
                invocation.log.writeText("online resolution complete\n")
                writeGraph(invocation, fixture, mutation)
                0
            }
            assertFails {
                resolver(fixture, executor).resolve(fixture.stage, InventoryPhase.SOURCE)
            }
            assertTrue(outputOf(executor.invocations.single()).exists())
        }

        val offlineFixture = fixture("offline-invalid-graph")
        val offlineExecutor = FakeExecutor { index, invocation ->
            if (index == 0) {
                invocation.log.writeText(metadataFailure())
                1
            } else {
                invocation.log.writeText("offline invalid graph\n")
                writeGraph(invocation, offlineFixture, GraphMutation.WRONG_HASH)
                0
            }
        }
        assertFails {
            resolver(offlineFixture, offlineExecutor)
                .resolve(offlineFixture.stage, InventoryPhase.SOURCE)
        }
        assertEquals(2, offlineExecutor.invocations.size)
        assertTrue(offlineExecutor.invocations.all { it.log.exists() })
        assertTrue(outputOf(offlineExecutor.invocations.last()).exists())
    }

    @Test
    fun `classifier accepts the complete create318 failure and duplicated wrappers`() {
        val full = assertNotNull(
            javaClass.getResource(
                "/legacy-plant/create318-metadata-transport-failure.log"
            )
        ).readText()
        val reason = "dependency-metadata-transport-failure"

        assertEquals(reason, LegacyPlantMetadataTransportFailureClassifier.classify(full))
        assertEquals(
            reason,
            LegacyPlantMetadataTransportFailureClassifier.classify("$full\n$full")
        )
        assertEquals(
            reason,
            LegacyPlantMetadataTransportFailureClassifier.classify(
                "wrapper exception\n$full\ncaused by wrapper\n$full"
            )
        )
    }

    @Test
    fun `classifier rejects mixed semantic auth unknown status and release gate failures`() {
        val failure = metadataFailure()
        assertNull(
            LegacyPlantMetadataTransportFailureClassifier.classify(
                "$failure\nNo matching variant of example:bad:1.0 was found."
            )
        )
        assertNull(
            LegacyPlantMetadataTransportFailureClassifier.classify(
                failure.replace("status code 400", "status code 401")
            )
        )
        assertNull(
            LegacyPlantMetadataTransportFailureClassifier.classify(
                metadataFailure(
                    createCause =
                        "Received status code 418 from server. " +
                            "PKIX path building failed"
                )
            )
        )
        assertNull(
            LegacyPlantMetadataTransportFailureClassifier.classify(
                "minecraft-mod-testing: FAIL: release gate failed\n" +
                    "> Could not resolve all files for configuration ':runtimeClasspath'."
            )
        )
    }

    @Test
    fun `mirror 400 recovery requires the same createmod metadata path`() {
        assertEquals(
            "dependency-metadata-transport-failure",
            LegacyPlantMetadataTransportFailureClassifier.classify(metadataFailure())
        )
        assertNull(
            LegacyPlantMetadataTransportFailureClassifier.classify(
                metadataFailure(mirrorPath = "example/other/1.0/maven-metadata.xml")
            )
        )
    }

    @Test
    fun `provenance receipts are all-or-none and replay canonically`() {
        val fixture = fixture("receipt")
        val oldGraph = fixture.resolvedGraph()
        val oldText = oldGraph.canonicalText()
        val oldRecords = receiptRecords(oldText)
        assertNull(LegacyPlantResolutionProvenance.fromCanonicalRecords(oldRecords))
        assertFalse("resolutionOrigin=" in oldText)
        assertEquals(oldText, replay(oldGraph, fixture).canonicalText())

        val provenance = LegacyPlantResolutionProvenance(
            origin = "gradle-offline-complete-cache",
            metadataFreshness = "cached",
            sourceIdentitySha256 =
                legacyPlantBuildInputIdentity(fixture.buildFileHashes()),
            recoveryReason = "dependency-metadata-transport-failure",
            failedAttemptExitCode = 1,
            failedAttemptLogSha256 = "b".repeat(64)
        )
        val recovered = oldGraph.copy(resolutionProvenance = provenance)
        val recoveredRecords = receiptRecords(recovered.canonicalText())
        assertFalse("recoveryAttemptLogSha256=" in recovered.canonicalText())
        assertEquals(
            provenance,
            LegacyPlantResolutionProvenance.fromCanonicalRecords(recoveredRecords)
        )
        assertEquals(recovered.canonicalText(), replay(recovered, fixture).canonicalText())

        val retainedProvenance = provenance.copy(
            recoveryAttemptLogSha256 = "c".repeat(64)
        )
        val retained = oldGraph.copy(resolutionProvenance = retainedProvenance)
        val retainedRecords = receiptRecords(retained.canonicalText())
        assertEquals(
            retainedProvenance,
            LegacyPlantResolutionProvenance.fromCanonicalRecords(retainedRecords)
        )
        assertEquals(retained.canonicalText(), replay(retained, fixture).canonicalText())
        assertFails {
            LegacyPlantResolutionProvenance.fromCanonicalRecords(
                recoveredRecords.filter { it.first != "metadataFreshness" }
            )
        }
        assertFails {
            LegacyPlantResolutionProvenance.fromCanonicalRecords(
                recoveredRecords + ("resolutionOrigin" to "online")
            )
        }
        assertFails {
            LegacyPlantResolutionProvenance.fromCanonicalRecords(
                listOf("recoveryAttemptLogSha256" to "d".repeat(64))
            )
        }
        assertFails {
            LegacyPlantResolutionProvenance.fromCanonicalRecords(
                retainedRecords + ("recoveryAttemptLogSha256" to "d".repeat(64))
            )
        }
    }

    private fun resolver(
        fixture: Fixture,
        executor: LegacyPlantManagedCommandExecutor
    ): ManagedLegacyPlantGradleResolver =
        ManagedLegacyPlantGradleResolver(
            repositoryRoot = fixture.repository,
            initScript = fixture.initScript,
            runtimeJarSha256 = "a".repeat(64),
            commandExecutor = executor
        )

    private fun fixture(name: String): Fixture {
        val repository = directory.resolve(name)
        val stage = repository.resolve("stage")
        stage.createDirectories()
        val buildFile = stage.resolve("build.gradle")
        buildFile.writeText("repositories { mavenCentral() }\n")
        stage.resolve("settings.gradle").writeText("rootProject.name = 'fixture'\n")
        stage.resolve("gradle/libs.versions.toml")
            .also { it.parent.createDirectories() }
            .writeText("[versions]\nfixture = \"1.0\"\n")
        stage.resolve("buildSrc/build.gradle.kts")
            .also { it.parent.createDirectories() }
            .writeText("plugins { `kotlin-dsl` }\n")
        val buildSrcSource = stage.resolve("buildSrc/src/main/kotlin/Plugin.kt")
            .also { it.parent.createDirectories() }
        buildSrcSource.writeText("class FixturePlugin\n")
        stage.resolve("buildSrc/src/main/kotlin/build/Helper.kt")
            .also { it.parent.createDirectories() }
            .writeText("class BuildHelper\n")
        stage.resolve(
            "buildSrc/src/main/resources/META-INF/gradle-plugins/example.properties"
        ).also { it.parent.createDirectories() }
            .writeText("implementation-class=FixturePlugin\n")
        val initScript = repository.resolve("legacy-plant-resolve.init.gradle")
        initScript.writeText("// fake command executor never loads this file\n")
        val artifact = repository.resolve("cache/example-1.0.jar")
        artifact.parent.createDirectories()
        artifact.writeBytes("artifact-$name".toByteArray())
        return Fixture(
            repository,
            stage,
            buildFile,
            initScript,
            artifact,
            buildSrcSource
        )
    }

    private fun writeGraph(
        invocation: LegacyPlantManagedCommand,
        fixture: Fixture,
        mutation: GraphMutation = GraphMutation.NONE
    ) {
        val artifactPath = if (mutation == GraphMutation.MISSING_ARTIFACT) {
            fixture.repository.resolve("cache/missing.jar")
        } else {
            fixture.artifact
        }
        val buildHashes = fixture.buildFileHashes().toMutableMap()
        if (mutation == GraphMutation.WRONG_BUILD_HASH) {
            buildHashes["build.gradle"] = "c".repeat(64)
        }
        val actualSize = Files.size(fixture.artifact)
        val actualHash = sha256(fixture.artifact.readBytes())
        val graph = buildJsonObject {
            put("schema", "modporter-legacy-plant-resolved-graph-v1")
            put("phase", invocation.environment.getValue("MODPORTER_PLANT_INVENTORY_PHASE"))
            put("gradleVersion", "8.14.4")
            put(
                "classRuntimeFeature",
                invocation.environment.getValue(
                    "MODPORTER_PLANT_CLASS_RUNTIME_FEATURE"
                ).toInt()
            )
            put("buildFiles", buildJsonObject {
                buildHashes.toSortedMap().forEach { (path, hash) -> put(path, hash) }
            })
            put("configurations", buildJsonArray {
                add(buildJsonObject {
                    put("project", ":")
                    put("configuration", "runtimeClasspath")
                })
            })
            put("artifacts", buildJsonArray {
                add(buildJsonObject {
                    put("project", ":")
                    put("component", "example:example:1.0")
                    put("componentKind", "module")
                    put("classifier", JsonNull)
                    put("artifact", "example-1.0.jar")
                    put("configurations", buildJsonArray {
                        add(JsonPrimitive(":|runtimeClasspath"))
                    })
                    put("configurationOrder", buildJsonObject {
                        put(":|runtimeClasspath", 0)
                    })
                    put("variantAttributes", buildJsonObject {})
                    put("file", artifactPath.toAbsolutePath().normalize().toString())
                    put(
                        "size",
                        if (mutation == GraphMutation.WRONG_SIZE) {
                            actualSize + 1
                        } else {
                            actualSize
                        }
                    )
                    put(
                        "sha256",
                        if (mutation == GraphMutation.WRONG_HASH) {
                            "d".repeat(64)
                        } else {
                            actualHash
                        }
                    )
                })
            })
            put("edges", buildJsonArray {})
            put("unresolved", buildJsonArray {
                if (mutation == GraphMutation.UNRESOLVED) {
                    add(buildJsonObject { put("failure", "not resolved") })
                }
            })
            put("platformIdentity", JsonNull)
            put("platformAuxiliaries", buildJsonArray {})
        }
        outputOf(invocation).writeText(graph.toString())
    }

    private fun outputOf(invocation: LegacyPlantManagedCommand): Path =
        Path.of(invocation.environment.getValue("MODPORTER_PLANT_INVENTORY_OUTPUT"))

    private fun replay(graph: ResolvedGraph, fixture: Fixture): ResolvedGraph {
        val receipt = fixture.repository.resolve(
            "graph-${graph.resolutionProvenance?.origin ?: "legacy"}.properties"
        )
        receipt.writeText(graph.canonicalText())
        val method = LegacyPlantCoverageV2Verifier::class.java.declaredMethods.single {
            it.name == "resolvedGraphFromReceipt"
        }
        method.trySetAccessible()
        return method.invoke(
            LegacyPlantCoverageV2Verifier,
            receipt,
            InventoryPhase.SOURCE,
            mapOf(graph.artifacts.single().key to fixture.artifact),
            emptyMap<String, Path>()
        ) as ResolvedGraph
    }

    private fun receiptRecords(text: String): List<Pair<String, String>> =
        text.lineSequence().filter { it.isNotBlank() }.map {
            it.substringBefore('=') to it.substringAfter('=')
        }.toList()

    private fun metadataFailure(
        mirrorPath: String = METADATA_PATH,
        createCause: String = "PKIX path building failed"
    ): String =
        """
        Execution failed for task ':modporterLegacyPlantResolve'.
        > Could not resolve all files for configuration ':runtimeClasspath'.
           > Could not resolve dev.engine-room.flywheel:flywheel-forge-1.20.1:[1.0,2.0).
              Required by:
                  root project :
               > Failed to list versions for dev.engine-room.flywheel:flywheel-forge-1.20.1.
                  > Unable to load Maven meta-data from https://maven.createmod.net/$METADATA_PATH.
                     > Could not HEAD 'https://maven.createmod.net/$METADATA_PATH'.
                        > $createCause
               > Failed to list versions for dev.engine-room.flywheel:flywheel-forge-1.20.1.
                  > Unable to load Maven meta-data from https://maven.ithundxr.dev/mirror/$mirrorPath.
                     > Could not GET 'https://maven.ithundxr.dev/mirror/$mirrorPath'. Received status code 400 from server: Bad Request
        """.trimIndent() + "\n"

    private class FakeExecutor(
        private val action: (Int, LegacyPlantManagedCommand) -> Int?
    ) : LegacyPlantManagedCommandExecutor {
        val invocations = mutableListOf<LegacyPlantManagedCommand>()

        override fun execute(invocation: LegacyPlantManagedCommand): Int? {
            val index = invocations.size
            invocations += invocation
            return action(index, invocation)
        }
    }

    private data class Fixture(
        val repository: Path,
        val stage: Path,
        val buildFile: Path,
        val initScript: Path,
        val artifact: Path,
        val buildSrcSource: Path
    ) {
        fun buildFileHashes(): Map<String, String> = listOf(
            "build.gradle",
            "settings.gradle",
            "gradle/libs.versions.toml",
            "buildSrc/build.gradle.kts",
            "buildSrc/src/main/kotlin/Plugin.kt",
            "buildSrc/src/main/kotlin/build/Helper.kt",
            "buildSrc/src/main/resources/META-INF/gradle-plugins/example.properties"
        ).associateWith { sha256(stage.resolve(it).readBytes()) }

        fun resolvedGraph(): ResolvedGraph {
            val artifactHash = sha256(artifact.readBytes())
            val configuration = ":|runtimeClasspath"
            return ResolvedGraph(
                phase = InventoryPhase.SOURCE,
                resolverSchema = "modporter-legacy-plant-resolved-graph-v1",
                gradleVersion = "8.14.4",
                classRuntimeFeature = 17,
                buildFileHashes = buildFileHashes(),
                configurations = setOf(configuration),
                artifacts = listOf(
                    ResolvedArtifact(
                        projectPath = ":",
                        component = "example:example:1.0",
                        componentKind = "module",
                        classifier = null,
                        displayName = "example-1.0.jar",
                        configurations = setOf(configuration),
                        configurationOrder = mapOf(configuration to 0),
                        variantAttributes = emptyMap(),
                        file = artifact,
                        size = Files.size(artifact),
                        sha256 = artifactHash,
                        variantSha256 = sha256("")
                    )
                ),
                edges = emptyList(),
                unresolved = emptyList(),
                platformIdentity = null,
                platformAuxiliaries = emptyList()
            )
        }
    }

    private enum class GraphMutation {
        NONE,
        UNRESOLVED,
        WRONG_BUILD_HASH,
        MISSING_ARTIFACT,
        WRONG_SIZE,
        WRONG_HASH
    }

}
