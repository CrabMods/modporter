package com.modporter.core.transforms.structural

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class CreateItemStackDataFamilyMigrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `filesystem adapter commits the admitted family once and then no-ops`() {
        val fixture = fixture()

        val result = CreateItemStackDataFamilyMigration.migrate(
            fixture.project,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(setOf(fixture.entry, fixture.packagePort), result.changes.map { it.file }.toSet())
        assertTrue(
            fixture.entry.readText().contains(
                "saveAll(List<List<ClipboardEntry>> entries, ItemStack clipboardItem, " +
                    "HolderLookup.Provider registries)"
            )
        )
        assertTrue(
            fixture.packagePort.readText().contains(
                "ClipboardEntry.saveAll(list, mainHandItem, player.registryAccess())"
            )
        )
        assertTrue(transactionArtifacts(fixture.project).isEmpty())

        val rerun = CreateItemStackDataFamilyMigration.migrate(
            fixture.project,
            dryRun = false
        )
        assertEquals(CreateItemStackDataFamilyMigration.Result.EMPTY, rerun)
        assertTrue(transactionArtifacts(fixture.project).isEmpty())
    }

    @Test
    fun `failed second publish restores every exact source byte and cleans recovery files`() {
        val fixture = fixture()
        val originals = fixture.files.associateWith { it.readBytes() }

        val result = CreateItemStackDataFamilyMigration.migrate(
            fixture.project,
            dryRun = false,
            fileIo = FailSecondPublishIo()
        )

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("all attempted source replacements were restored"))
        originals.forEach { (path, bytes) ->
            assertTrue(path.readBytes().contentEquals(bytes), path.toString())
        }
        assertTrue(transactionArtifacts(fixture.project).isEmpty())
    }

    @Test
    fun `unknown clipboard caller reports an admission gate before any write`() {
        val fixture = fixture()
        val unknown = fixture.project.resolve(
            "src/main/java/example/UnknownClipboardWriter.java"
        )
        unknown.parent.createDirectories()
        unknown.writeText(
            """
            package example;

            public class UnknownClipboardWriter {
            	void save(ItemStack stack) {
            		ClipboardEntry.saveAll(pages, stack);
            	}
            }
            """.trimIndent() + "\n"
        )
        val originals = (fixture.files + listOf(unknown)).associateWith { it.readBytes() }

        val result = CreateItemStackDataFamilyMigration.migrate(
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("active but incomplete or has an unproved caller"))
        originals.forEach { (path, bytes) ->
            assertTrue(path.readBytes().contentEquals(bytes), path.toString())
        }
        assertTrue(transactionArtifacts(fixture.project).isEmpty())
    }

    @Test
    fun `dry run admits the captured mixed entry without changing any source byte`() {
        val fixture = fixture()
        fixture.entry.writeText(
            """
            package com.simibubi.create.content.equipment.clipboard;

            public class ClipboardEntry {
            	public static void saveAll(List<List<ClipboardEntry>> entries, ItemStack clipboardItem) {
            		CompoundTag tag = (clipboardItem).getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            		tag.put("Pages", NBTHelper.writeCompoundList(entries, list -> {
            			CompoundTag pageTag = new CompoundTag();
            			pageTag.put("Entries", NBTHelper.writeCompoundList(list, ClipboardEntry::writeNBT));
            			return pageTag;
            		}));
            		if ((tag).isEmpty()) {
            			(clipboardItem).remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            		} else {
            			(clipboardItem).set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            				net.minecraft.world.item.component.CustomData.of(tag));
            		}
            	}

            	public CompoundTag writeNBT(HolderLookup.Provider registries) {
            		CompoundTag nbt = new CompoundTag();
            		nbt.putString("Text", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, text).getOrThrow().toString());
            		if (icon.isEmpty())
            			return nbt;
            		nbt.put("Icon", icon.saveOptional(registries));
            		return nbt;
            	}
            }
            """.trimIndent() + "\n"
        )
        val originals = fixture.files.associateWith { it.readBytes() }

        val result = CreateItemStackDataFamilyMigration.migrate(
            fixture.project,
            dryRun = true
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(setOf(fixture.entry, fixture.packagePort), result.changes.map { it.file }.toSet())
        originals.forEach { (path, bytes) ->
            assertTrue(path.readBytes().contentEquals(bytes), path.toString())
        }
        assertTrue(transactionArtifacts(fixture.project).isEmpty())
    }

    @Test
    fun `duplicate canonical clipboard owner refuses before any source write`() {
        val fixture = fixture()
        val duplicate = fixture.project.resolve(
            "src/main/java/duplicate/ClipboardEntry.java"
        )
        duplicate.parent.createDirectories()
        duplicate.writeText(fixture.entry.readText())
        val originals = (fixture.files + listOf(duplicate)).associateWith { it.readBytes() }

        val result = CreateItemStackDataFamilyMigration.migrate(
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("active but incomplete or has an unproved caller"))
        originals.forEach { (path, bytes) ->
            assertTrue(path.readBytes().contentEquals(bytes), path.toString())
        }
        assertTrue(transactionArtifacts(fixture.project).isEmpty())
    }
    @Test
    fun `shared transaction plans the captured packet and minecart handoffs without writing`() {
        val configured = System.getenv("MODPORTER_CREATE_ITEM_DATA_REPLAY")
        assumeTrue(!configured.isNullOrBlank(), "Optional captured-source replay")
        val sourceRoot = Path.of(configured!!)
        val project = sourceRoot.parent.parent.parent
        val originals = java.nio.file.Files.walk(sourceRoot).use { paths ->
            paths.filter { it.toString().endsWith(".java") }.toList()
                .associateWith { it.readBytes() }
        }
        val result = CreateItemStackDataFamilyMigration.migrateHandoffs(project, dryRun = true)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(setOf("ClipboardScreen.java", "MinecartContraptionItem.java"),
            result.changes.map { it.file.fileName.toString() }.toSet())
        assertTrue(result.changes.all { it.ruleId == CreateItemDataHandoffs.RULE_ID })
        originals.forEach { (file, bytes) ->
            assertTrue(bytes.contentEquals(file.readBytes()), file.toString())
        }
    }

    private fun fixture(): Fixture {
        val project = directory.resolve("project")
        val entry = project.resolve(
            "src/main/java/com/simibubi/create/content/equipment/clipboard/ClipboardEntry.java"
        )
        val packagePort = project.resolve(
            "src/main/java/com/simibubi/create/content/logistics/packagePort/" +
                "PackagePortBlockEntity.java"
        )
        entry.parent.createDirectories()
        packagePort.parent.createDirectories()
        entry.writeText(
            """
            package com.simibubi.create.content.equipment.clipboard;

            public class ClipboardEntry {
            	public static void saveAll(List<List<ClipboardEntry>> entries, ItemStack clipboardItem) {
            		CompoundTag tag = clipboardItem.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            		tag.put("Pages", NBTHelper.writeCompoundList(entries, list -> {
            			CompoundTag pageTag = new CompoundTag();
            			pageTag.put("Entries", NBTHelper.writeCompoundList(list, ClipboardEntry::writeNBT));
            			return pageTag;
            		}));
            	}

            	public CompoundTag writeNBT() {
            		CompoundTag nbt = new CompoundTag();
            		nbt.putString("Text", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, text).getOrThrow().toString());
            		if (icon.isEmpty())
            			return nbt;
            		nbt.put("Icon", icon.serializeNBT());
            		return nbt;
            	}
            }
            """.trimIndent() + "\n"
        )
        packagePort.writeText(
            """
            package com.simibubi.create.content.logistics.packagePort;

            public abstract class PackagePortBlockEntity {
            	void addAddressToClipboard(Player player, ItemStack mainHandItem) {
            		ClipboardEntry.saveAll(list, mainHandItem);
            	}
            }
            """.trimIndent() + "\n"
        )
        return Fixture(project, entry, packagePort)
    }

    private fun transactionArtifacts(project: Path): List<Path> {
        val root = project.resolve("src/main/java")
        if (!root.exists()) return emptyList()
        return java.nio.file.Files.walk(root).use { stream ->
            stream.filter { path -> path.fileName.toString().contains(".modporter-") }
                .toList()
        }
    }

    private data class Fixture(
        val project: Path,
        val entry: Path,
        val packagePort: Path
    ) {
        val files: List<Path> = listOf(entry, packagePort)
    }

    private class FailSecondPublishIo :
        CreateItemStackDataFamilyMigration.ProjectFileIo {
        private val delegate = CreateItemStackDataFamilyMigration.NioProjectFileIo
        private var publishCount = 0

        override fun readBytes(path: Path): ByteArray = delegate.readBytes(path)

        override fun createTempSibling(target: Path, suffix: String): Path =
            delegate.createTempSibling(target, suffix)

        override fun writeBytes(path: Path, bytes: ByteArray) {
            delegate.writeBytes(path, bytes)
        }

        override fun moveReplace(source: Path, target: Path) {
            if (source.fileName.toString().endsWith(".stage")) {
                publishCount++
                if (publishCount == 2) {
                    throw IOException("injected second Create item-data publish failure")
                }
            }
            delegate.moveReplace(source, target)
        }

        override fun deleteIfExists(path: Path) {
            delegate.deleteIfExists(path)
        }
    }
}
