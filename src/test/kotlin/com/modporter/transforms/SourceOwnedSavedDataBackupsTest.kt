package com.modporter.transforms

import com.modporter.core.transforms.structural.SourceOwnedSavedDataBackups
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SourceOwnedSavedDataBackupsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `owned family keeps dat old saves and restores server factory boundary`() {
        val fixture = writeFixture(directory.resolve("positive"))
        val managerBefore = fixture.managerPaths.associateWith { it.readText() }

        val result = SourceOwnedSavedDataBackups.migrate(
            fixture.project,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(3, result.changes.size)
        val utility = fixture.utility.readText()
        assertTrue(
            Regex(
                """saveWithDatOld\s*\(\s*T\s+savedData\s*,\s*File\s+file\s*,\s*""" +
                    """HolderLookup\.Provider\s+registries\s*\)"""
            ).containsMatchIn(utility),
            utility
        )
        assertTrue(
            utility.contains("savedData.save(new CompoundTag(), registries)"),
            utility
        )
        assertTrue(utility.contains("NbtIo.writeCompressed(compoundtag, temp.toPath())"), utility)
        assertTrue(
            utility.contains(
                "Util.safeReplaceFile(file.toPath(), temp.toPath(), oldFile.toPath())"
            ),
            utility
        )
        assertTrue(utility.contains("savedData.setDirty(false)"), utility)

        fixture.dataPaths.forEach { (className, path) ->
            val source = path.readText()
            assertTrue(
                source.contains("SavedData.Factory<$className> factory()"),
                source
            )
            assertTrue(source.contains("$className::new"), source)
            assertTrue(source.contains("$className::load"), source)
            assertTrue(
                Regex(
                    """computeIfAbsent\s*\(\s*factory\s*\(\s*\)\s*,"""
                ).containsMatchIn(source),
                source
            )
            assertTrue(
                Regex(
                    """public\s+static\s+$className\s+load\s*\(\s*""" +
                        """MinecraftServer\s+server\s*\)"""
                ).containsMatchIn(source),
                source
            )
            assertFalse(
                Regex(
                    """load\s*\(\s*MinecraftServer\s+server\s*,\s*""" +
                        """HolderLookup\.Provider"""
                ).containsMatchIn(source),
                source
            )
            assertTrue(
                Regex(
                    """@Override\s+public\s+void\s+save\s*\(\s*""" +
                        """@NotNull\s+File\s+file\s*,\s*""" +
                        """HolderLookup\.Provider\s+registries\s*\)"""
                ).containsMatchIn(source),
                source
            )
            assertTrue(
                source.contains("BackupWriter.saveWithDatOld(this, file, registries)"),
                source
            )
            assertTrue(source.contains("nbt.putString(\"preserved\", \"yes\")"), source)
        }
        assertEquals(managerBefore, fixture.managerPaths.associateWith { it.readText() })

        val second = SourceOwnedSavedDataBackups.migrate(
            fixture.project,
            dryRun = false
        )
        assertTrue(second.gates.isEmpty(), second.gates.joinToString("\n"))
        assertTrue(second.changes.isEmpty())
    }

    @Test
    fun `unknown replacement flow fails closed before any family write`() {
        val fixture = writeFixture(directory.resolve("unknown-flow"))
        fixture.utility.writeText(
            fixture.utility.readText().replace(
                "Util.safeReplaceFile(file, temp, oldFile);",
                "Util.safeReplaceFile(file, temp, oldFile, true);"
            )
        )
        val before = fixture.ownedPaths.associateWith { it.readText() }

        val result = SourceOwnedSavedDataBackups.migrate(
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any {
                it.contains("file replacement flow") ||
                    it.contains("provider or Path calls")
            },
            result.gates.joinToString("\n")
        )
        assertEquals(before, fixture.ownedPaths.associateWith { it.readText() })
    }

    @Test
    fun `external backup utility caller keeps the complete family unchanged`() {
        val fixture = writeFixture(directory.resolve("external-caller"))
        writeJava(
            fixture.project,
            "com/example/ExternalSave.java",
            """
            package com.example;

            import java.io.File;
            import example.storage.BackupWriter;
            import net.minecraft.world.level.saveddata.SavedData;

            public class ExternalSave {
                public static void save(SavedData data, File file) {
                    BackupWriter.saveWithDatOld(data, file);
                }
            }
            """
        )
        val before = fixture.ownedPaths.associateWith { it.readText() }

        val result = SourceOwnedSavedDataBackups.migrate(
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any { it.contains("outside its owned SavedData file save overrides") },
            result.gates.joinToString("\n")
        )
        assertEquals(before, fixture.ownedPaths.associateWith { it.readText() })
    }

    @Test
    fun `second publish failure restores every attempted source from prestaged originals`() {
        val fixture = writeFixture(directory.resolve("publish-failure"))
        val before = fixture.ownedPaths.associateWith { it.readText() }
        val fileIo = FailSecondPublishAndOptionalRollback(failRollback = false)

        val result = SourceOwnedSavedDataBackups.migrate(
            fixture.project,
            dryRun = false,
            fileIo = fileIo
        )

        assertTrue(result.changes.isEmpty(), result.changes.toString())
        assertTrue(
            result.gates.singleOrNull()?.contains(
                "all attempted source replacements were restored"
            ) == true,
            result.gates.joinToString("\n")
        )
        assertEquals(2, fileIo.publishAttempts)
        assertEquals(before, fixture.ownedPaths.associateWith { it.readText() })
        assertTrue(transactionArtifacts(fixture.project).isEmpty())
    }

    @Test
    fun `original byte drift after staging is preserved and blocks every publish`() {
        val fixture = writeFixture(directory.resolve("source-drift"))
        val before = fixture.ownedPaths.associateWith { it.readText() }
        val drift = "// concurrent source edit\n" + before.getValue(fixture.utility)
        val fileIo = DriftAfterAllBackups(fixture.utility, drift.toByteArray())

        val result = SourceOwnedSavedDataBackups.migrate(
            fixture.project,
            dryRun = false,
            fileIo = fileIo
        )

        assertTrue(result.changes.isEmpty(), result.changes.toString())
        assertTrue(
            result.gates.singleOrNull()?.contains(
                "failed before publish; no source file was replaced"
            ) == true,
            result.gates.joinToString("\n")
        )
        assertEquals(0, fileIo.publishAttempts)
        assertEquals(drift, fixture.utility.readText())
        fixture.dataPaths.values.forEach { path ->
            assertEquals(before.getValue(path), path.readText())
        }
        assertTrue(transactionArtifacts(fixture.project).isEmpty())
    }

    @Test
    fun `rollback failure reports the surviving change and retains verified recovery bytes`() {
        val fixture = writeFixture(directory.resolve("rollback-failure"))
        val before = fixture.ownedPaths.associateWith { it.readText() }
        val fileIo = FailSecondPublishAndOptionalRollback(failRollback = true)

        val result = SourceOwnedSavedDataBackups.migrate(
            fixture.project,
            dryRun = false,
            fileIo = fileIo
        )

        assertEquals(listOf(fixture.utility), result.changes.map { it.file })
        assertTrue(
            result.gates.singleOrNull()?.contains("rollback is incomplete") == true,
            result.gates.joinToString("\n")
        )
        assertTrue(
            result.gates.single().contains("Recovery copies retained at"),
            result.gates.single()
        )
        assertFalse(fixture.utility.readText() == before.getValue(fixture.utility))
        fixture.dataPaths.values.forEach { path ->
            assertEquals(before.getValue(path), path.readText())
        }

        val artifacts = transactionArtifacts(fixture.project)
        val recovery = artifacts.filter { it.fileName.toString().endsWith(".backup") }
        assertEquals(1, recovery.size, artifacts.toString())
        assertEquals(before.getValue(fixture.utility), recovery.single().readText())
        assertTrue(
            artifacts.none {
                it.fileName.toString().endsWith(".stage") ||
                    it.fileName.toString().endsWith(".rollback")
            },
            artifacts.toString()
        )
    }

    @Test
    fun `pre common source is left to the existing provider migration`() {
        val fixture = writeFixture(
            directory.resolve("pre-common"),
            postCommon = false
        )
        val before = fixture.ownedPaths.associateWith { it.readText() }

        val result = SourceOwnedSavedDataBackups.migrate(
            fixture.project,
            dryRun = true
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertTrue(result.changes.isEmpty())
        assertEquals(before, fixture.ownedPaths.associateWith { it.readText() })
    }

    @Test
    fun `a backup family with other names and the same shape gets the same rewrite`() {
        val project = directory.resolve("renamed")
        val utility = writeJava(
            project,
            "example/io/Snapshots.java",
            """
            package example.io;

            import java.io.File;
            import java.io.IOException;
            import net.minecraft.Util;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.NbtIo;
            import net.minecraft.world.level.saveddata.SavedData;

            public final class Snapshots {
                static <D extends SavedData> void persist(D data, File target) {
                    CompoundTag root = new CompoundTag();
                    root.put("data", data.save(new CompoundTag()));
                    try {
                        File staging = new File(target.getPath() + ".tmp");
                        NbtIo.writeCompressed(root, staging);
                        File previous = new File(target.getPath() + ".bak");
                        Util.safeReplaceFile(target, staging, previous);
                    } catch (IOException ignored) {
                    }
                }
            }
            """
        )
        val data = writeJava(
            project,
            "example/io/Ledger.java",
            """
            package example.io;

            import java.io.File;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.world.level.saveddata.SavedData;

            public class Ledger extends SavedData {
                public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
                    return tag;
                }

                private static Ledger read(CompoundTag tag, HolderLookup.Provider lookup) {
                    return new Ledger();
                }

                public void save(File target) {
                    Snapshots.persist(this, target);
                }

                public static Ledger get(MinecraftServer server, HolderLookup.Provider lookup) {
                    return server.overworld().getDataStorage().computeIfAbsent(
                        new SavedData.Factory<>(Ledger::new, Ledger::read), "ledger");
                }
            }
            """
        )
        writeJava(
            project,
            "example/io/LedgerUser.java",
            """
            package example.io;
            import net.minecraft.server.MinecraftServer;
            public class LedgerUser {
                Ledger ledger(MinecraftServer server) {
                    return Ledger.get(server);
                }
            }
            """
        )

        val result = SourceOwnedSavedDataBackups.migrate(project, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(2, result.changes.size)
        val migratedUtility = utility.readText()
        assertTrue(
            migratedUtility.contains(
                "static <D extends SavedData> void persist(D data, File target, HolderLookup.Provider registries)"
            ),
            migratedUtility
        )
        assertTrue(migratedUtility.contains("data.save(new CompoundTag(), registries)"), migratedUtility)
        assertTrue(migratedUtility.contains("NbtIo.writeCompressed(root, staging.toPath())"), migratedUtility)
        assertTrue(
            migratedUtility.contains(
                "Util.safeReplaceFile(target.toPath(), staging.toPath(), previous.toPath())"
            ),
            migratedUtility
        )
        val migratedData = data.readText()
        assertTrue(
            Regex("""@Override\s+public\s+void\s+save\s*\(\s*File\s+target\s*,\s*HolderLookup\.Provider\s+registries\s*\)""")
                .containsMatchIn(migratedData),
            migratedData
        )
        assertTrue(migratedData.contains("Snapshots.persist(this, target, registries)"), migratedData)
        assertTrue(
            Regex("""public\s+static\s+Ledger\s+get\s*\(\s*MinecraftServer\s+server\s*\)""")
                .containsMatchIn(migratedData),
            migratedData
        )
        assertTrue(
            Regex("""computeIfAbsent\(\s*factory\(\)\s*,\s*"ledger"\s*\)""").containsMatchIn(migratedData),
            migratedData
        )
        assertTrue(migratedData.contains("Ledger::new, Ledger::read"), migratedData)

        val second = SourceOwnedSavedDataBackups.migrate(project, dryRun = false)
        assertTrue(second.gates.isEmpty(), second.gates.joinToString("\n"))
        assertTrue(second.changes.isEmpty())
    }

    @Test
    fun `a caller that supplies the widened provider keeps the family unchanged`() {
        val fixture = writeFixture(directory.resolve("provider-caller"))
        val manager = fixture.managerPaths.first()
        manager.writeText(
            manager.readText()
                .replace("import net.minecraft.server.MinecraftServer;", "import net.minecraft.server.MinecraftServer;\nimport net.minecraft.core.HolderLookup;")
                .replace("(MinecraftServer server)", "(MinecraftServer server, HolderLookup.Provider registries)")
                .replace(".load(server)", ".load(server, registries)")
        )
        val before = fixture.ownedPaths.associateWith { it.readText() }

        val result = SourceOwnedSavedDataBackups.migrate(fixture.project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any { it.contains("does not pass only its original") },
            result.gates.joinToString("\n")
        )
        assertEquals(before, fixture.ownedPaths.associateWith { it.readText() })
    }

    private fun transactionArtifacts(project: Path): List<Path> =
        Files.walk(project).use { paths ->
            paths.filter {
                Files.isRegularFile(it) && it.fileName.toString().contains(".modporter-")
            }.sorted().toList()
        }

    private class DriftAfterAllBackups(
        private val target: Path,
        private val drift: ByteArray
    ) : SourceOwnedSavedDataBackups.ProjectFileIo {
        private val delegate = SourceOwnedSavedDataBackups.NioProjectFileIo
        private var backupWrites = 0
        var publishAttempts: Int = 0
            private set

        override fun readBytes(path: Path): ByteArray = delegate.readBytes(path)

        override fun createTempSibling(target: Path, suffix: String): Path =
            delegate.createTempSibling(target, suffix)

        override fun writeBytes(path: Path, bytes: ByteArray) {
            delegate.writeBytes(path, bytes)
            if (path.fileName.toString().endsWith(".backup")) {
                backupWrites += 1
                if (backupWrites == 3) delegate.writeBytes(target, drift)
            }
        }

        override fun moveReplace(source: Path, target: Path) {
            if (source.fileName.toString().endsWith(".stage")) publishAttempts += 1
            delegate.moveReplace(source, target)
        }

        override fun deleteIfExists(path: Path) {
            delegate.deleteIfExists(path)
        }
    }

    private class FailSecondPublishAndOptionalRollback(
        private val failRollback: Boolean
    ) : SourceOwnedSavedDataBackups.ProjectFileIo {
        private val delegate = SourceOwnedSavedDataBackups.NioProjectFileIo
        var publishAttempts: Int = 0
            private set
        private var rollbackAttempts: Int = 0

        override fun readBytes(path: Path): ByteArray = delegate.readBytes(path)

        override fun createTempSibling(target: Path, suffix: String): Path =
            delegate.createTempSibling(target, suffix)

        override fun writeBytes(path: Path, bytes: ByteArray) {
            delegate.writeBytes(path, bytes)
        }

        override fun moveReplace(source: Path, target: Path) {
            when {
                source.fileName.toString().endsWith(".stage") -> {
                    publishAttempts += 1
                    if (publishAttempts == 2) {
                        throw IOException("injected second SavedData publish failure")
                    }
                }
                source.fileName.toString().endsWith(".rollback") -> {
                    rollbackAttempts += 1
                    if (failRollback && rollbackAttempts == 1) {
                        throw IOException("injected SavedData rollback failure")
                    }
                }
            }
            delegate.moveReplace(source, target)
        }

        override fun deleteIfExists(path: Path) {
            delegate.deleteIfExists(path)
        }
    }

    private fun writeFixture(
        project: Path,
        postCommon: Boolean = true
    ): Fixture {
        val utility = writeJava(
            project,
            "example/storage/BackupWriter.java",
            """
            package example.storage;

            import java.io.File;
            import java.io.IOException;
            import java.nio.file.Paths;
                        import net.minecraft.Util;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.NbtIo;
            import net.minecraft.nbt.NbtUtils;
            import net.minecraft.world.level.saveddata.SavedData;

            public class BackupWriter {
                public static <T extends SavedData> void saveWithDatOld(
                        T savedData, File file) {
                    if (savedData.isDirty()) {
                        CompoundTag compoundtag = new CompoundTag();
                        compoundtag.put("data", savedData.save(new CompoundTag()));
                        NbtUtils.addCurrentDataVersion(compoundtag);
                        String savedDataName = file.getName().split("\\.")[0];
                        try {
                            File temp = File.createTempFile(
                                savedDataName, ".dat", file.getParentFile());
                            NbtIo.writeCompressed(compoundtag, temp);
                            File oldFile = Paths.get(
                                file.getParent(), savedDataName + ".dat_old").toFile();
                            Util.safeReplaceFile(file, temp, oldFile);
                        } catch (IOException exception) {
                            System.err.println(exception);
                        }
                        savedData.setDirty(false);
                    }
                }
            }
            """
        )
        val railway = writeData(
            project,
            packageName = "example.routes",
            className = "RouteSavedData",
            dataName = "example_routes",
            postCommon = postCommon
        )
        val logistics = writeData(
            project,
            packageName = "example.links",
            className = "LinkSavedData",
            dataName = "example_links",
            postCommon = postCommon
        )
        val railwayManager = writeManager(
            project,
            packageName = "example.routes",
            managerName = "RouteManager",
            dataName = "RouteSavedData",
            methodName = "loadRoutes"
        )
        val logisticsManager = writeManager(
            project,
            packageName = "example.links",
            managerName = "LinkManager",
            dataName = "LinkSavedData",
            methodName = "loadLinks"
        )
        return Fixture(
            project,
            utility,
            linkedMapOf(
                "RouteSavedData" to railway,
                "LinkSavedData" to logistics
            ),
            listOf(railwayManager, logisticsManager)
        )
    }

    private fun writeData(
        project: Path,
        packageName: String,
        className: String,
        dataName: String,
        postCommon: Boolean
    ): Path {
        val provider = if (postCommon) ", HolderLookup.Provider registries" else ""
        val fileOverride = if (postCommon) "" else "    @Override\n"
        val computeFactory = if (postCommon) {
            """
            new SavedData.Factory<>($className::new, $className::load),
                        "$dataName"
            """.trimIndent()
        } else {
            """
            $className::load, $className::new,
                        "$dataName"
            """.trimIndent()
        }
        return writeJava(
            project,
            packageName.replace('.', '/') + "/$className.java",
            """
        package $packageName;

        import java.io.File;
        import example.storage.BackupWriter;
        import net.minecraft.core.HolderLookup;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.server.MinecraftServer;
        import net.minecraft.world.level.saveddata.SavedData;
        import org.jetbrains.annotations.NotNull;

        public class $className extends SavedData {
            @Override
            public CompoundTag save(
                    CompoundTag nbt$provider) {
                nbt.putString("preserved", "yes");
                return nbt;
            }

            private static $className load(
                    CompoundTag nbt$provider) {
                return new $className();
            }

$fileOverride
            public void save(@NotNull File file) {
                BackupWriter.saveWithDatOld(this, file);
            }

            private $className() {}

            public static $className load(
                    MinecraftServer server$provider) {
                return server.overworld()
                    .getDataStorage()
                    .computeIfAbsent(
                        $computeFactory);
            }
        }
        """
        )
    }

    private fun writeManager(
        project: Path,
        packageName: String,
        managerName: String,
        dataName: String,
        methodName: String
    ): Path = writeJava(
        project,
        packageName.replace('.', '/') + "/$managerName.java",
        """
        package $packageName;

        import net.minecraft.server.MinecraftServer;

        public class $managerName {
            private $dataName savedData;

            private void $methodName(MinecraftServer server) {
                savedData = $dataName.load(server);
            }
        }
        """
    )

    private fun writeJava(project: Path, relative: String, source: String): Path {
        val path = project.resolve("src/main/java").resolve(relative)
        path.parent.createDirectories()
        path.writeText(source.trimIndent())
        return path
    }

    private data class Fixture(
        val project: Path,
        val utility: Path,
        val dataPaths: Map<String, Path>,
        val managerPaths: List<Path>
    ) {
        val ownedPaths: List<Path> get() = listOf(utility) + dataPaths.values
    }
}
