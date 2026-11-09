package com.modporter.core.transforms.structural

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyPlantPlatformAdmissionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `exact generated platform fingerprint is admitted from scanned bytes`() {
        val fixture = fixture()

        val scan = fixture.scan(fixture.graph())
        val surface = scan.artifacts.values.single()

        assertEquals(emptyList<String>(), scan.blockers)
        assertTrue(surface.hasPlantSurface)
        assertEquals(listOf(BLOCK_OWNER.replace('/', '.')), surface.relevantClasses)
        assertTrue(surface.isVerifiedTargetVanilla)
        assertTrue(
            surface.classFacts.contains(
                "platformFingerprint=${fixture.fingerprint.sourceReceiptSha256}"
            )
        )
        assertTrue(
            surface.classFacts.contains("platformIdentity=${fixture.identity.sha256}")
        )
    }

    @Test
    fun `unknown classes jar hash is rejected even when the required class is unchanged`() {
        val fixture = fixture()
        val unknown = jar(
            directory.resolve("unknown-platform.jar"),
            mapOf(
                BLOCK_CLASS_ENTRY to fixture.blockClassBytes,
                "META-INF/unreviewed.txt" to "unreviewed".toByteArray()
            )
        )
        assertEquals(
            fixture.fingerprint.requiredClasses.single().classSha256,
            sha256(fixture.blockClassBytes)
        )
        assertNotEquals(fixture.fingerprint.artifactSha256, sha256(unknown))

        val scan = fixture.scan(fixture.graph(platformJar = unknown))
        val surface = scan.artifacts.values.single()

        assertEquals(listOf(fixture.zeroMatchBlocker), scan.blockers)
        assertFalse(surface.isVerifiedTargetVanilla)
        assertFalse(
            surface.classFacts.contains(
                "platformFingerprint=${fixture.fingerprint.sourceReceiptSha256}"
            )
        )
    }

    @Test
    fun `reviewed alternate plugin is admitted while unknown plugin or loader drift is rejected`() {
        val fixture = fixture()
        val alternatePluginHash = fixture.fingerprint.reviewedAppliedPluginSha256s
            .single { it != fixture.identity.developmentPluginSha256 }
        val alternateIdentity = fixture.identity.copy(
            developmentPluginSha256 = alternatePluginHash
        )

        val alternate = fixture.scan(fixture.graph(identity = alternateIdentity))
        val alternateSurface = alternate.artifacts.values.single()
        assertEquals(emptyList<String>(), alternate.blockers)
        assertTrue(alternateSurface.isVerifiedTargetVanilla)
        assertTrue(
            alternateSurface.classFacts.contains(
                "platformIdentity=${alternateIdentity.sha256}"
            )
        )

        val rejected = listOf(
            "unknown plugin hash" to fixture.identity.copy(
                developmentPluginSha256 = sha256("unknown-plugin")
            ),
            "loader version drift" to fixture.identity.copy(
                loaderVersion = fixture.identity.loaderVersion + "-drift"
            )
        )
        rejected.forEach { (label, identity) ->
            val scan = fixture.scan(fixture.graph(identity = identity))
            val surface = scan.artifacts.values.single()
            assertFalse(surface.isVerifiedTargetVanilla, label)
            assertFalse(
                surface.classFacts.contains(
                    "platformFingerprint=${fixture.fingerprint.sourceReceiptSha256}"
                ),
                label
            )
            assertFalse(
                surface.classFacts.any { it.startsWith("platformIdentity=") },
                label
            )
        }
    }

    @Test
    fun `merged or sources jar mismatch removes reviewed platform evidence`() {
        val fixture = fixture()
        val mismatchedAuxiliary = jar(
            directory.resolve("mismatched-auxiliary.jar"),
            mapOf(
                BLOCK_SOURCE_ENTRY to fixture.blockSourceBytes,
                "META-INF/unreviewed.txt" to "unreviewed".toByteArray()
            )
        )
        val expectedBlocker =
            "platform merged/sources evidence does not match reviewed receipt " +
                fixture.fingerprint.sourceReceiptSha256

        listOf(
            "merged" to fixture.graph(mergedJar = mismatchedAuxiliary),
            "sources" to fixture.graph(sourcesJar = mismatchedAuxiliary)
        ).forEach { (label, graph) ->
            val scan = fixture.scan(graph)
            val surface = scan.artifacts.values.single()
            assertEquals(listOf(expectedBlocker), scan.blockers, label)
            assertFalse(surface.isVerifiedTargetVanilla, label)
            assertFalse(
                surface.classFacts.contains(
                    "platformFingerprint=${fixture.fingerprint.sourceReceiptSha256}"
                ),
                label
            )
        }
    }

    @Test
    fun `earlier classpath owner prevents a later exact platform from being admitted`() {
        val fixture = fixture()
        val shadow = jar(
            directory.resolve("shadow.jar"),
            mapOf(BLOCK_CLASS_ENTRY to fixture.blockClassBytes)
        )

        val scan = fixture.scan(fixture.graph(leadingJar = shadow))
        val platformSurface = scan.artifacts.entries.single {
            it.key.displayName == fixture.fingerprint.artifactDisplayName
        }.value
        val shadowSurface = scan.artifacts.entries.single {
            it.key.displayName == SHADOW_DISPLAY_NAME
        }.value

        assertEquals(listOf(fixture.zeroMatchBlocker), scan.blockers)
        assertTrue(scan.differentBytecodeDuplicates.isEmpty())
        assertTrue(shadowSurface.hasPlantSurface)
        assertFalse(platformSurface.isVerifiedTargetVanilla)
        assertFalse(
            platformSurface.classFacts.contains(
                "platformFingerprint=${fixture.fingerprint.sourceReceiptSha256}"
            )
        )
    }

    private fun fixture(): Fixture {
        val sourceFile = directory.resolve("src/net/minecraft/world/level/block/Block.java")
        Files.createDirectories(sourceFile.parent)
        val sourceBytes = BLOCK_SOURCE.toByteArray(StandardCharsets.UTF_8)
        Files.write(sourceFile, sourceBytes)

        val classes = directory.resolve("classes")
        Files.createDirectories(classes)
        val diagnostics = ByteArrayOutputStream()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "Tests require a JDK Java compiler"
        }
        val exitCode = compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-proc:none",
            "--release",
            "17",
            "-d",
            classes.toString(),
            sourceFile.toString()
        )
        assertEquals(
            0,
            exitCode,
            diagnostics.toString(StandardCharsets.UTF_8)
        )

        val blockClassBytes = Files.readAllBytes(classes.resolve(BLOCK_CLASS_ENTRY))
        val platformJar = jar(
            directory.resolve(PLATFORM_DISPLAY_NAME),
            mapOf(BLOCK_CLASS_ENTRY to blockClassBytes)
        )
        val mergedJar = jar(
            directory.resolve(MERGED_DISPLAY_NAME),
            mapOf(BLOCK_SOURCE_ENTRY to sourceBytes)
        )
        val sourcesJar = jar(
            directory.resolve(SOURCES_DISPLAY_NAME),
            mapOf(BLOCK_SOURCE_ENTRY to sourceBytes)
        )
        val buildFileHashes = mapOf(
            "build.gradle" to sha256("synthetic target platform declaration")
        )
        val targetTemplate = ReviewedPlantPlatformFingerprints.all.first {
            it.phase == InventoryPhase.TARGET &&
                it.reviewedAppliedPluginSha256s.size > 1
        }
        val identity = requireNotNull(targetTemplate.expectedPlatformIdentity).copy(
            generatedArtifactDisplayName = PLATFORM_DISPLAY_NAME,
            generatedArtifactSha256 = sha256(platformJar),
            declarationFileHashes = buildFileHashes
        )
        val fingerprint = targetTemplate.copy(
            expectedPlatformIdentity = identity,
            artifactDisplayName = PLATFORM_DISPLAY_NAME,
            artifactSha256 = sha256(platformJar),
            mergedArtifactDisplayName = MERGED_DISPLAY_NAME,
            mergedArtifactSha256 = sha256(mergedJar),
            sourcesArtifactDisplayName = SOURCES_DISPLAY_NAME,
            sourcesArtifactSha256 = sha256(sourcesJar),
            reviewedSourceEntries = mapOf(
                BLOCK_SOURCE_ENTRY to sha256(sourceBytes)
            ),
            requiredClasses = setOf(
                JvmClassFingerprint(
                    owner = BLOCK_OWNER,
                    accessFlags = 0x0021,
                    classSha256 = sha256(blockClassBytes)
                )
            ),
            requiredMethods = emptySet(),
            sourceReceiptSha256 = sha256("synthetic target source receipt"),
            closureManifestSha256 = sha256("synthetic target closure manifest")
        )
        return Fixture(
            blockClassBytes = blockClassBytes,
            blockSourceBytes = sourceBytes,
            platformJar = platformJar,
            mergedJar = mergedJar,
            sourcesJar = sourcesJar,
            buildFileHashes = buildFileHashes,
            identity = identity,
            fingerprint = fingerprint,
            runtimeIdentity = CanonicalRuntimeIdentity.fromProductionResources()
        )
    }

    private inner class Fixture(
        val blockClassBytes: ByteArray,
        val blockSourceBytes: ByteArray,
        val platformJar: Path,
        val mergedJar: Path,
        val sourcesJar: Path,
        val buildFileHashes: Map<String, String>,
        val identity: PlatformResolutionIdentity,
        val fingerprint: PlatformArtifactFingerprint,
        val runtimeIdentity: CanonicalRuntimeIdentity
    ) {
        val zeroMatchBlocker: String
            get() = "platform fingerprint ${fingerprint.sourceReceiptSha256} matched 0 artifacts"

        fun scan(graph: ResolvedGraph): ClosurePlantScan =
            Jvm21LegacyPlantBytecodeScanner(listOf(fingerprint)).scan(
                graph,
                runtimeIdentity,
                emptyList()
            )

        fun graph(
            platformJar: Path = this.platformJar,
            identity: PlatformResolutionIdentity = this.identity,
            mergedJar: Path = this.mergedJar,
            sourcesJar: Path = this.sourcesJar,
            leadingJar: Path? = null
        ): ResolvedGraph {
            val configuration = ":|runtimeClasspath"
            val artifacts = buildList {
                leadingJar?.let {
                    add(
                        artifact(
                            file = it,
                            component = "example:shadow:1.0",
                            componentKind = "module",
                            displayName = SHADOW_DISPLAY_NAME,
                            configuration = configuration,
                            order = 0
                        )
                    )
                }
                add(
                    artifact(
                        file = platformJar,
                        component = "synthetic-target-platform",
                        componentKind = "generated-platform",
                        displayName = fingerprint.artifactDisplayName,
                        configuration = configuration,
                        order = if (leadingJar == null) 0 else 1
                    )
                )
            }
            return ResolvedGraph(
                phase = InventoryPhase.TARGET,
                resolverSchema = "modporter-legacy-plant-resolved-graph-v1",
                gradleVersion = "8.14.4",
                classRuntimeFeature = 21,
                buildFileHashes = buildFileHashes,
                configurations = setOf(configuration),
                artifacts = artifacts,
                edges = emptyList(),
                unresolved = emptyList(),
                platformIdentity = identity,
                platformAuxiliaries = listOf(
                    auxiliary("merged", fingerprint.mergedArtifactDisplayName, mergedJar),
                    auxiliary("sources", fingerprint.sourcesArtifactDisplayName, sourcesJar)
                )
            )
        }

        private fun artifact(
            file: Path,
            component: String,
            componentKind: String,
            displayName: String,
            configuration: String,
            order: Int
        ): ResolvedArtifact = ResolvedArtifact(
            projectPath = ":",
            component = component,
            componentKind = componentKind,
            classifier = null,
            displayName = displayName,
            configurations = setOf(configuration),
            configurationOrder = mapOf(configuration to order),
            variantAttributes = emptyMap(),
            file = file,
            size = Files.size(file),
            sha256 = sha256(file),
            variantSha256 = sha256("")
        )

        private fun auxiliary(
            kind: String,
            displayName: String,
            file: Path
        ): PlatformAuxiliaryArtifact = PlatformAuxiliaryArtifact(
            kind = kind,
            displayName = displayName,
            file = file,
            size = Files.size(file),
            sha256 = sha256(file)
        )
    }

    private fun jar(path: Path, entries: Map<String, ByteArray>): Path {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            entries.toSortedMap().forEach { (name, bytes) ->
                val entry = JarEntry(name)
                entry.time = 0L
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        return path
    }

    private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

    private fun sha256(value: String): String =
        sha256(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BLOCK_OWNER = "net/minecraft/world/level/block/Block"
        const val BLOCK_CLASS_ENTRY = "$BLOCK_OWNER.class"
        const val BLOCK_SOURCE_ENTRY = "$BLOCK_OWNER.java"
        const val PLATFORM_DISPLAY_NAME = "synthetic-target-platform.jar"
        const val MERGED_DISPLAY_NAME = "synthetic-target-platform-merged.jar"
        const val SOURCES_DISPLAY_NAME = "synthetic-target-platform-sources.jar"
        const val SHADOW_DISPLAY_NAME = "shadow.jar"
        val BLOCK_SOURCE = """
            package net.minecraft.world.level.block;

            public class Block {
                public int getPlant() {
                    return 7;
                }
            }
        """.trimIndent() + "\n"
    }
}
