package com.modporter.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeLines
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlywheelJarJarRuntimeEvidenceTest {
    @Test
    fun `production hashes and complete candidate inventory remain pinned`() {
        val pins = FlywheelJarJarRuntimeEvidence.TestSupport.productionPins()
        assertEquals(
            "31dda15c205eb596d3b3449ef03f6af7363a6cd35b3da4bfe916b304f9e5337e",
            pins.sourceFlywheelSha256
        )
        assertEquals(
            "c27a5d4ea2f4dd98ee359455fe4baff87d462ab556506daeb08d0ce5e42ccf0f",
            pins.vanillinSha256
        )
        assertEquals(
            "64dc580cfc1700b785fb3a9c1f21b283ccdc801909a4cfbb1dc25fead91c3678",
            pins.nestedFlywheelSha256
        )
        assertEquals(
            "f34bab02a5e967650dc52b7cb37a200051a7078f7d1868597699d78697da2400",
            pins.selectorSha256
        )
        assertEquals(
            "182fcee7ed6e2be4c815bd08d2c766d355c58ab1e227b6ffd84ed26a0f71b6d6",
            pins.loaderSha256
        )
        assertEquals(
            "4af00873e5c7655d2dcc2bd302593a72b6e0b2b27a3ac7cfd0f9bf2d770afd61",
            pins.classPayloadSha256
        )
        assertEquals(
            "f24232eac69c1285d76b931ef612ce856819adf59c5d2def61e86ec49576491c",
            pins.identicalCommonPayloadSha256
        )
        assertEquals(
            mapOf("neoforge-21.1.230.jar" to
                "1c40cad92daf78fff5896c2a7b45032ae05782dc2c211e41e4194be0494c5a4e"),
            pins.rootEntryPayloadSha256
        )
        assertEquals(
            listOf(
                "neoforge-21.1.230.jar|111b1b3136a1fdee48654cbf8d1e5062ffe75875e83b4d7f69e0a1366a85d062|" +
                    "io.github.llamalad7:mixinextras-neoforge|[0.5.3,)|0.5.3|" +
                    "META-INF/jarjar/mixinextras-neoforge-0.5.3.jar|" +
                    "9822e773bd9f42d36ed53ea3d67486207291747a7bfacc29beeb92040721bc9b|false",
                "neoforge-21.1.230.jar|111b1b3136a1fdee48654cbf8d1e5062ffe75875e83b4d7f69e0a1366a85d062|" +
                    "net.neoforged:neoforge-coremods|[21.1.230,)|21.1.230|" +
                    "META-INF/jarjar/net.neoforged.neoforge-coremods-21.1.230.jar|" +
                    "43bff7bef65a31ee634f9c112fc67e75a1fa0c19763db6b584d71a14ec1f77d9|false",
                "vanillin-neoforge-1.21.1-1.1.3-41.jar|" +
                    "c27a5d4ea2f4dd98ee359455fe4baff87d462ab556506daeb08d0ce5e42ccf0f|" +
                    "dev.engine-room.flywheel:neoforge|[1.0.6-41,)|1.0.6-41|" +
                    "META-INF/jars/flywheel-neoforge-1.21.1-1.0.6-41.jar|" +
                    "64dc580cfc1700b785fb3a9c1f21b283ccdc801909a4cfbb1dc25fead91c3678|null",
                "cc-tweaked-1.21.1-forge-1.116.1.jar|" +
                    "f5e8872a2ccca38aa210c477c84d5f2ba3e8f054328b6816461af4c1f8c460a5|" +
                    "cc.tweaked:cobalt|[0.9.6,)|0.9.6|META-INF/jarjar/cobalt-0.9.6.jar|" +
                    "774034d31b10192ca5fafdb551a525c57c6553b9c5b53b17e774fd8ff2f4b300|false",
                "cc-tweaked-1.21.1-forge-1.116.1.jar|" +
                    "f5e8872a2ccca38aa210c477c84d5f2ba3e8f054328b6816461af4c1f8c460a5|" +
                    "com.jcraft:jzlib|[1.1.3,)|1.1.3|META-INF/jarjar/jzlib-1.1.3.jar|" +
                    "89b1360f407381bf61fde411019d8cbd009ebb10cff715f3669017a031027560|false",
                "journeymap-121d84332dba0910.jar|" +
                    "121d84332dba09100ea8c654a8c5b75bdf1257639d00aca1210c5dc31f36cb38|" +
                    "ar.com.hjg:pngj|[2.1.0]|2.1.0|META-INF/jarjar/pngj-2.1.0.jar|" +
                    "e6b762f15e4891178dddd74e4d57318f518ce278000129efa41f85410b132ccc|false",
                "journeymap-121d84332dba0910.jar|" +
                    "121d84332dba09100ea8c654a8c5b75bdf1257639d00aca1210c5dc31f36cb38|" +
                    "info.journeymap:journeymap-api-neoforge|[2.0.0-1.21.1-SNAPSHOT]|" +
                    "2.0.0-1.21.1-SNAPSHOT|" +
                    "META-INF/jarjar/journeymap-api-neoforge-2.0.0-1.21.1-SNAPSHOT.jar|" +
                    "6a0261a3f26dee9a6b3876eef891478d96ea3df730d31accc0ef414251d43e33|false",
                "journeymap-121d84332dba0910.jar|" +
                    "121d84332dba09100ea8c654a8c5b75bdf1257639d00aca1210c5dc31f36cb38|" +
                    "mysticdrew:common-networking-neoforge|[1.0.18-1.21.1]|1.0.18-1.21.1|" +
                    "META-INF/jarjar/common-networking-neoforge-1.0.18-1.21.1.jar|" +
                    "63ae6219f0c2d87e7df83c0e8bcd2890cf72186f09ac34e991588912f6fda63e|false"
            ),
            pins.candidateInventory
        )
    }

    @Test
    fun `exact synthetic evidence accepts one source collision`(@TempDir tempDir: Path) {
        val fixture = Fixture.create(tempDir)
        val result = fixture.audit()

        assertEquals(FlywheelJarJarRuntimeEvidence.Status.ACCEPTED, result.status, result.failures.joinToString())
        assertTrue(result.failures.isEmpty())
        val warningLine = assertNotNull(result.warningLineIndex)
        assertTrue(result.allowedIssueAt(warningLine)?.contains("candidates=8->7") == true)
        assertEquals(null, result.allowedIssueAt(warningLine + 1))
    }

    @Test
    fun `generated root accepts only identical named payloads after ZIP repacking`(@TempDir tempDir: Path) {
        val fixture = Fixture.create(tempDir, pinNeoForgePayload = true)
        val root = tempDir.resolve("artifacts/neoforge-test.jar")
        val before = Files.readAllBytes(root)
        writeJar(root, readJarFile(root).entries.reversed().associate { it.key to it.value })
        assertTrue(!before.contentEquals(Files.readAllBytes(root)), "Fixture must change container bytes")

        val result = fixture.audit()
        assertEquals(FlywheelJarJarRuntimeEvidence.Status.ACCEPTED, result.status, result.failures.joinToString())
        assertTrue(assertNotNull(result.evidenceSummary).contains("metadataRootSha256="))

        fixture.rewriteJarEntry(root, "net/test/Platform.class") { it + byteArrayOf(9) }
        fixture.assertRejected("entry payload SHA-256 drifted")
    }

    @Test
    fun `generated payload pin does not admit renamed roots or repackage published artifacts`(@TempDir tempDir: Path) {
        val renamedFixture = Fixture.create(tempDir.resolve("renamed"), pinNeoForgePayload = true)
        val root = tempDir.resolve("renamed/artifacts/neoforge-test.jar")
        val renamed = root.resolveSibling("renamed-neoforge-test.jar")
        Files.move(root, renamed)
        renamedFixture.rewriteLog { lines -> lines.map { it.replace(root.toString(), renamed.toString()) } }
        renamedFixture.assertRejected("unexpected runtime source JarJar metadata roots")

        val published = Fixture.create(tempDir.resolve("published"), pinNeoForgePayload = true)
        writeJar(published.sourceFlywheel, readJarFile(published.sourceFlywheel).entries.reversed()
            .associate { it.key to it.value })
        published.assertRejected("source Flywheel SHA-256 drifted")
    }

    @Test
    fun `source Flywheel hash drift is rejected`(@TempDir tempDir: Path) {
        val fixture = Fixture.create(tempDir)
        Files.write(fixture.sourceFlywheel, byteArrayOf(0), StandardOpenOption.APPEND)

        fixture.assertRejected("source Flywheel SHA-256 drifted")
    }

    @Test
    fun `nested payload resource drift is rejected`(@TempDir tempDir: Path) {
        val fixture = Fixture.create(tempDir)
        fixture.mutateNestedSharedResource()

        fixture.assertRejected("nested Flywheel SHA-256 drifted")
        assertTrue(
            fixture.audit().failures.any {
                it.contains("shared Flywheel payload") ||
                    it.contains("identical shared Flywheel payload") ||
                    it.contains("identical common Flywheel file")
            },
            fixture.audit().failures.joinToString()
        )
    }

    @Test
    fun `loaded version and declared range drift are rejected`(@TempDir tempDir: Path) {
        val versionFixture = Fixture.create(tempDir.resolve("version"))
        versionFixture.rewriteLog { lines ->
            lines.map { line ->
                if (line.trim() == "Flywheel ${Fixture.SOURCE_VERSION} (flywheel)") {
                    "\t\tFlywheel 9.9 (flywheel)"
                } else {
                    line
                }
            }
        }
        versionFixture.assertRejected("runtime Mod List lacks one exact")

        val rangeFixture = Fixture.create(tempDir.resolve("range"))
        rangeFixture.rewriteJarEntry(
            rangeFixture.vanillin,
            "META-INF/jarjar/metadata.json"
        ) { bytes ->
            bytes.decodeToString()
                .replace(Fixture.NESTED_RANGE, "[9.0,)")
                .encodeToByteArray()
        }
        rangeFixture.assertRejected("JarJar metadata candidates drifted")
    }

    @Test
    fun `extra warning extra candidate and warning path mismatch are rejected`(@TempDir tempDir: Path) {
        val warningFixture = Fixture.create(tempDir.resolve("warning"))
        warningFixture.rewriteLog { lines -> lines + lines.single { it.contains("JarSelector/]:") } }
        warningFixture.assertRejected("expected exactly one JarSelector WARN")

        val candidateFixture = Fixture.create(tempDir.resolve("candidate"))
        candidateFixture.addUnexpectedCandidate()
        candidateFixture.assertRejected("JarJar metadata candidates drifted")

        val pathFixture = Fixture.create(tempDir.resolve("path"))
        val alternate = tempDir.resolve("path/alternate/${pathFixture.sourceFlywheel.fileName}")
        alternate.parent.createDirectories()
        Files.copy(pathFixture.sourceFlywheel, alternate, StandardCopyOption.REPLACE_EXISTING)
        pathFixture.rewriteLog { lines ->
            lines.map { line ->
                if (line.contains("JarSelector/]:")) {
                    line.replace(pathFixture.sourceFlywheel.toString(), alternate.toString())
                } else {
                    line
                }
            }
        }
        pathFixture.assertRejected("warning-selected Flywheel path was not a top-level runtime source candidate")
    }

    @Test
    fun `selector loader and classpath readback drift are rejected`(@TempDir tempDir: Path) {
        val selectorFixture = Fixture.create(tempDir.resolve("selector"))
        Files.write(selectorFixture.selector, byteArrayOf(1), StandardOpenOption.APPEND)
        selectorFixture.assertRejected("JarJarSelector SHA-256 drifted")

        val loaderFixture = Fixture.create(tempDir.resolve("loader"))
        Files.write(loaderFixture.loader, byteArrayOf(1), StandardOpenOption.APPEND)
        loaderFixture.assertRejected("FML loader SHA-256 drifted")

        val readbackFixture = Fixture.create(tempDir.resolve("readback"))
        val alternateDir = tempDir.resolve("readback/alternate")
        alternateDir.createDirectories()
        val alternateSelector = alternateDir.resolve(readbackFixture.selector.fileName)
        Files.copy(readbackFixture.selector, alternateSelector, StandardCopyOption.REPLACE_EXISTING)
        readbackFixture.classpath.writeLines(listOf(alternateSelector.toString(), readbackFixture.loader.toString()))
        readbackFixture.assertRejected("JarJarSelector classpath path was not read back by ModDiscoverer")
    }

    @Test
    fun `missing loaded source records and wrong selected count are rejected`(@TempDir tempDir: Path) {
        val loadedFixture = Fixture.create(tempDir.resolve("loaded"))
        loadedFixture.rewriteLog { lines ->
            lines.filterNot {
                it.contains("Loading mod file ${loadedFixture.sourceFlywheel}") ||
                    it.contains("Loading mod file ${loadedFixture.vanillin}")
            }
        }
        loadedFixture.assertRejected("was not loaded as the retained source mod")
        loadedFixture.assertRejected("Vanillin source path was not present")

        val countFixture = Fixture.create(tempDir.resolve("count"))
        countFixture.rewriteLog { lines ->
            lines.map { it.replace("Found 7 dependencies", "Found 6 dependencies") }
        }
        countFixture.assertRejected("expected one JarInJar selected-dependency line")
    }

    @Test
    fun `missing warning is not applicable rather than accepted`(@TempDir tempDir: Path) {
        val fixture = Fixture.create(tempDir)
        fixture.rewriteLog { lines -> lines.filterNot { it.contains("JarSelector/]:") } }

        val result = fixture.audit()
        assertEquals(FlywheelJarJarRuntimeEvidence.Status.NOT_PRESENT, result.status)
        assertEquals(null, result.evidenceSummary)
    }

    @Test
    fun `accepted evidence cannot allow a different warning line`(@TempDir tempDir: Path) {
        val fixture = Fixture.create(tempDir)
        fixture.rewriteLog { lines ->
            lines + "[00:00:01] [main/WARN] [example/Other/]: unrelated warning"
        }

        val result = fixture.audit()
        assertEquals(FlywheelJarJarRuntimeEvidence.Status.ACCEPTED, result.status)
        val unrelatedIndex = fixture.logFile.readLines().indexOfLast { it.contains("unrelated warning") }
        assertEquals(null, result.allowedIssueAt(unrelatedIndex))
    }

    private data class Candidate(
        val group: String,
        val artifact: String,
        val range: String,
        val version: String,
        val path: String,
        val isObfuscated: Boolean? = false,
        val bytes: ByteArray = jarBytes(mapOf("payload.txt" to "$group:$artifact".encodeToByteArray()))
    )

    private class Fixture private constructor(
        val projectDir: Path,
        val logFile: Path,
        val sourceFlywheel: Path,
        val vanillin: Path,
        val selector: Path,
        val loader: Path,
        val classpath: Path,
        private val roots: List<Path>,
        private val preparedSpec: FlywheelJarJarRuntimeEvidence.TestSupport.PreparedSpec
    ) {
        fun audit(): FlywheelJarJarRuntimeEvidence.AuditResult =
            FlywheelJarJarRuntimeEvidence.TestSupport.audit(projectDir, logFile, preparedSpec)

        fun assertRejected(expectedFailure: String) {
            val result = audit()
            assertEquals(
                FlywheelJarJarRuntimeEvidence.Status.REJECTED,
                result.status,
                result.failures.joinToString()
            )
            assertTrue(
                result.failures.any { it.contains(expectedFailure) },
                "Expected '$expectedFailure' in ${result.failures}"
            )
        }

        fun rewriteLog(transform: (List<String>) -> List<String>) {
            logFile.writeLines(transform(logFile.readLines()))
        }

        fun mutateNestedSharedResource() {
            rewriteJarEntry(vanillin, NESTED_PATH) { nested ->
                val entries = readJarBytes(nested).toMutableMap()
                entries["assets/test/shared.txt"] = "changed-resource".encodeToByteArray()
                jarBytes(entries)
            }
        }

        fun addUnexpectedCandidate() {
            val journey = roots.single { it.fileName.toString() == "journey-test.jar" }
            val extra = Candidate(
                group = "unexpected",
                artifact = "extra",
                range = "[1,)",
                version = "1",
                path = "META-INF/jarjar/extra.jar"
            )
            val entries = readJarFile(journey).toMutableMap()
            val metadata = entries.getValue("META-INF/jarjar/metadata.json").decodeToString()
            entries["META-INF/jarjar/metadata.json"] = metadata
                .replace("\"jars\":[", "\"jars\":[${candidateJson(extra)},")
                .encodeToByteArray()
            entries[extra.path] = extra.bytes
            writeJar(journey, entries)
        }

        fun rewriteJarEntry(path: Path, entry: String, transform: (ByteArray) -> ByteArray) {
            val entries = readJarFile(path).toMutableMap()
            entries[entry] = transform(entries.getValue(entry))
            writeJar(path, entries)
        }

        companion object {
            const val SOURCE_VERSION = "1.0-test"
            const val NESTED_VERSION = "1.0-test-7"
            const val VANILLIN_VERSION = "2.0-test"
            const val NESTED_RANGE = "[1.0-test-7,)"
            const val NESTED_PATH = "META-INF/jars/flywheel-test-nested.jar"
            private const val SELECTOR_VERSION = "9.9"
            private const val LOADER_VERSION = "8.8"

            fun create(root: Path, pinNeoForgePayload: Boolean = false): Fixture {
                root.createDirectories()
                val projectDir = root.resolve("project")
                val artifacts = root.resolve("artifacts")
                val moddev = projectDir.resolve("build/moddev")
                artifacts.createDirectories()
                moddev.createDirectories()

                val selector = artifacts.resolve("JarJarSelector-$SELECTOR_VERSION.jar")
                val loader = artifacts.resolve("loader-$LOADER_VERSION.jar")
                writeJar(selector, mapOf("selector.txt" to "selector".encodeToByteArray()))
                writeJar(loader, mapOf("loader.txt" to "loader".encodeToByteArray()))

                val sourceFlywheel = artifacts.resolve("flywheel-test-$SOURCE_VERSION.jar")
                val externalToml = flywheelToml(SOURCE_VERSION)
                writeJar(
                    sourceFlywheel,
                    linkedMapOf(
                        "META-INF/neoforge.mods.toml" to externalToml.encodeToByteArray(),
                        "com/example/A.class" to byteArrayOf(1, 2, 3),
                        "com/example/B.class" to byteArrayOf(4, 5, 6),
                        "assets/test/shared.txt" to "shared".encodeToByteArray()
                    )
                )

                val nestedEntries = linkedMapOf(
                    "META-INF/neoforge.mods.toml" to flywheelToml(NESTED_VERSION).encodeToByteArray(),
                    "META-INF/architectury-loom-nesting-metadata.json" to
                        """{"group":"dev.engine-room.flywheel","name":"neoforge","version":"$NESTED_VERSION","classifier":""}"""
                            .encodeToByteArray(),
                    "com/example/A.class" to byteArrayOf(1, 2, 3),
                    "com/example/B.class" to byteArrayOf(4, 5, 6),
                    "assets/test/shared.txt" to "shared".encodeToByteArray()
                )
                val nestedFlywheel = jarBytes(nestedEntries)
                val flywheelCandidate = Candidate(
                    group = "dev.engine-room.flywheel",
                    artifact = "neoforge",
                    range = NESTED_RANGE,
                    version = NESTED_VERSION,
                    path = NESTED_PATH,
                    isObfuscated = null,
                    bytes = nestedFlywheel
                )

                val vanillin = artifacts.resolve("vanillin-test-$VANILLIN_VERSION.jar")
                writeCandidateRoot(
                    vanillin,
                    listOf(flywheelCandidate),
                    mapOf(
                        "META-INF/neoforge.mods.toml" to vanillinToml().encodeToByteArray()
                    )
                )
                val neoforge = artifacts.resolve("neoforge-test.jar")
                writeCandidateRoot(
                    neoforge,
                    listOf(
                        Candidate("io.test", "mixinextras", "[1,)", "1", "META-INF/jarjar/mixinextras.jar"),
                        Candidate("io.test", "coremods", "[2,)", "2", "META-INF/jarjar/coremods.jar")
                    ),
                    mapOf("net/test/Platform.class" to byteArrayOf(10, 20, 30))
                )
                val cc = artifacts.resolve("cc-test.jar")
                writeCandidateRoot(
                    cc,
                    listOf(
                        Candidate("cc.test", "cobalt", "[3,)", "3", "META-INF/jarjar/cobalt.jar"),
                        Candidate("cc.test", "jzlib", "[4,)", "4", "META-INF/jarjar/jzlib.jar")
                    )
                )
                val journey = artifacts.resolve("journey-test.jar")
                writeCandidateRoot(
                    journey,
                    listOf(
                        Candidate("jm.test", "pngj", "[5]", "5", "META-INF/jarjar/pngj.jar"),
                        Candidate("jm.test", "api", "[6]", "6", "META-INF/jarjar/api.jar"),
                        Candidate("jm.test", "network", "[7]", "7", "META-INF/jarjar/network.jar")
                    )
                )

                val classpath = moddev.resolve("serverLegacyClasspath.txt")
                classpath.writeLines(listOf(selector.toString(), loader.toString()))
                val roots = listOf(neoforge, vanillin, cc, journey)
                val logFile = root.resolve("runtime.log")
                logFile.writeLines(validLog(sourceFlywheel, vanillin, roots, selector, loader))

                val prepared = FlywheelJarJarRuntimeEvidence.TestSupport.prepareSyntheticSpec(
                    sourceFlywheel = sourceFlywheel,
                    vanillin = vanillin,
                    selector = selector,
                    loader = loader,
                    candidateRoots = roots,
                    selectorVersion = SELECTOR_VERSION,
                    loaderVersion = LOADER_VERSION,
                    selectedDependencyCount = 7,
                    entryPayloadRootNames = if (pinNeoForgePayload) setOf("neoforge-test.jar") else emptySet()
                )
                return Fixture(
                    projectDir,
                    logFile,
                    sourceFlywheel,
                    vanillin,
                    selector,
                    loader,
                    classpath,
                    roots,
                    prepared
                )
            }

            private fun validLog(
                sourceFlywheel: Path,
                vanillin: Path,
                roots: List<Path>,
                selector: Path,
                loader: Path
            ): List<String> =
                buildList {
                    add("[00:00:00] [main/DEBUG] [ne.ne.fm.lo.FMLLoader/CORE]: FML $LOADER_VERSION loading")
                    add(
                        "[00:00:00] [main/DEBUG] [ne.ne.fm.ut.ServiceLoaderUtil/CORE]: " +
                            "0 - net.neoforged.fml.loading.moddiscovery.locators." +
                            "JarInJarDependencyLocator from ${loader.fileName}"
                    )
                    // java.nio.file.Path is Iterable<Path> on Windows. Wrap the source path so
                    // Kotlin appends the path object instead of expanding its name elements.
                    (roots + listOf(sourceFlywheel)).forEach {
                        add(
                            "[00:00:00] [main/DEBUG] [ne.ne.fm.lo.mo.ModFileParser/LOADING]: " +
                                "Considering mod file candidate $it"
                        )
                    }
                    add(
                        "[00:00:00] [main/DEBUG] [ne.ne.fm.lo.mo.ModDiscoverer/]: " +
                            "Skipping $selector because it was already located earlier"
                    )
                    add(
                        "[00:00:00] [main/DEBUG] [ne.ne.fm.lo.mo.ModDiscoverer/]: " +
                            "Skipping $loader because it was already located earlier"
                    )
                    add(
                        "[00:00:00] [main/DEBUG] [ne.ne.fm.lo.mo.ModFileInfo/LOADING]: " +
                            "Found valid mod file ${sourceFlywheel.fileName} with {flywheel} mods - " +
                            "versions {$SOURCE_VERSION}"
                    )
                    add(
                        "[00:00:00] [main/DEBUG] [ne.ne.fm.lo.mo.ModFileInfo/LOADING]: " +
                            "Found valid mod file ${vanillin.fileName} with {vanillin} mods - " +
                            "versions {$VANILLIN_VERSION}"
                    )
                    add(
                        "[00:00:00] [main/DEBUG] [ne.ne.fm.lo.mo.ModFileInfo/LOADING]: " +
                            "Found valid mod file ${NESTED_PATH.substringAfterLast('/')} " +
                            "with {flywheel} mods - versions {$NESTED_VERSION}"
                    )
                    add(
                        "[00:00:00] [main/WARN] [ne.ne.ja.se.JarSelector/]: " +
                            "Attempted to select a dependency jar for JarJar which was passed in as " +
                            "source: flywheel. Using Mod File: $sourceFlywheel"
                    )
                    add(
                        "[00:00:00] [main/INFO] [ne.ne.fm.lo.mo.lo.JarInJarDependencyLocator/]: " +
                            "Found 7 dependencies adding them to mods collection"
                    )
                    add("\t\tFlywheel $SOURCE_VERSION (flywheel)")
                    add("\t\tVanillin $VANILLIN_VERSION (vanillin)")
                    add(
                        "[00:00:00] [main/DEBUG] [ne.ne.fm.lo.mo.ModFile/LOADING]: " +
                            "Loading mod file $sourceFlywheel with languages [javafml]"
                    )
                    add(
                        "[00:00:00] [main/DEBUG] [ne.ne.fm.lo.mo.ModFile/LOADING]: " +
                            "Loading mod file $vanillin with languages [javafml]"
                    )
                }

            private fun flywheelToml(version: String): String =
                """
                modLoader = "javafml"
                loaderVersion = "[0,)"

                [[mods]]
                modId = "flywheel"
                version = "$version"
                displayName = "Flywheel"
                """.trimIndent()

            private fun vanillinToml(): String =
                """
                modLoader = "javafml"
                loaderVersion = "[0,)"

                [[mods]]
                modId = "vanillin"
                version = "$VANILLIN_VERSION"
                displayName = "Vanillin"

                [[dependencies.vanillin]]
                modId = "flywheel"
                type = "required"
                versionRange = "[1.0-test,2.0)"
                side = "CLIENT"
                """.trimIndent()
        }
    }

    private companion object {
        fun writeCandidateRoot(
            path: Path,
            candidates: List<Candidate>,
            extraEntries: Map<String, ByteArray> = emptyMap()
        ) {
            val entries = linkedMapOf<String, ByteArray>()
            entries["META-INF/jarjar/metadata.json"] = metadataJson(candidates).encodeToByteArray()
            candidates.forEach { entries[it.path] = it.bytes }
            entries.putAll(extraEntries)
            writeJar(path, entries)
        }

        fun metadataJson(candidates: List<Candidate>): String =
            """{"jars":[${candidates.joinToString(",") { candidateJson(it) }}]}"""

        fun candidateJson(candidate: Candidate): String {
            val obfuscated = candidate.isObfuscated?.let { ",\"isObfuscated\":$it" }.orEmpty()
            return "{\"identifier\":{\"group\":\"${candidate.group}\",\"artifact\":\"${candidate.artifact}\"}," +
                "\"version\":{\"range\":\"${candidate.range}\",\"artifactVersion\":\"${candidate.version}\"}," +
                "\"path\":\"${candidate.path}\"$obfuscated}"
        }

        fun writeJar(path: Path, entries: Map<String, ByteArray>) {
            path.parent?.createDirectories()
            Files.newOutputStream(path).use { output ->
                JarOutputStream(output).use { jar ->
                    entries.forEach { (name, bytes) ->
                        val entry = JarEntry(name)
                        entry.time = 0
                        jar.putNextEntry(entry)
                        jar.write(bytes)
                        jar.closeEntry()
                    }
                }
            }
        }

        fun jarBytes(entries: Map<String, ByteArray>): ByteArray {
            val output = ByteArrayOutputStream()
            JarOutputStream(output).use { jar ->
                entries.forEach { (name, bytes) ->
                    val entry = JarEntry(name)
                    entry.time = 0
                    jar.putNextEntry(entry)
                    jar.write(bytes)
                    jar.closeEntry()
                }
            }
            return output.toByteArray()
        }

        fun readJarFile(path: Path): Map<String, ByteArray> =
            Files.newInputStream(path).use { readJarBytes(it.readBytes()) }

        fun readJarBytes(bytes: ByteArray): Map<String, ByteArray> =
            buildMap {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) put(entry.name, zip.readBytes())
                    }
                }
            }
    }
}
