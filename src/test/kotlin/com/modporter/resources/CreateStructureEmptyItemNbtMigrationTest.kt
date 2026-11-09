package com.modporter.resources

import com.modporter.resources.CreateStructureFluidNbtMigration.CompoundTag
import com.modporter.resources.CreateStructureFluidNbtMigration.ListTag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateStructureEmptyItemNbtMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exact direct legacy air migrates while nonair and slot wrappers survive`() {
        val taggedNonAir = namedCompound(
            "Filter",
            listOf(
                namedString("id", "minecraft:diamond"),
                namedByte("Count", 64),
                namedCompound("tag", listOf(namedInt("Damage", 7)))
            )
        )
        val slotWrappedAir = namedCompound(
            "Inventory",
            listOf(
                namedList(
                    "Items",
                    TAG_COMPOUND,
                    listOf(
                        compoundPayload(
                            listOf(
                                namedByte("Slot", 0),
                                namedString("id", "minecraft:air"),
                                namedByte("Count", 1)
                            )
                        )
                    )
                )
            )
        )
        val input = structure(
            compressed = false,
            blocks = listOf(
                blockEntityBlock(
                    "create:funnel",
                    listOf(
                        namedCompound(
                            "Filter",
                            listOf(
                                namedString("id", "minecraft:air"),
                                namedByte("Count", 1)
                            )
                        ),
                        slotWrappedAir
                    )
                ),
                blockEntityBlock(
                    "create:deployer",
                    listOf(
                        namedCompound(
                            "Filter",
                            listOf(
                                namedString("id", "minecraft:air"),
                                namedInt("Count", 0)
                            )
                        ),
                        namedCompound(
                            "HeldItem",
                            listOf(
                                namedString("id", "minecraft:air"),
                                namedByte("Count", 1)
                            )
                        )
                    )
                ),
                blockEntityBlock("create:content_observer", listOf(taggedNonAir))
            )
        )

        val first = CreateStructureEmptyItemNbtMigration.migrateBytes(input, "exact-air")
        assertEquals(3, first.migratedAirCount)
        assertTrue(first.bytes.containsSubsequence(slotWrappedAir))
        assertTrue(first.bytes.containsSubsequence(taggedNonAir))

        val decoded = CreateStructureFluidNbtMigration.decodeTypedNbt(first.bytes)
        assertTrue(itemField(decoded.document.root, 0, "Filter").entries.isEmpty())
        assertTrue(itemField(decoded.document.root, 1, "Filter").entries.isEmpty())
        assertTrue(itemField(decoded.document.root, 1, "HeldItem").entries.isEmpty())

        val second =
            CreateStructureEmptyItemNbtMigration.migrateBytes(first.bytes, "exact-air-second")
        assertEquals(0, second.migratedAirCount)
        assertContentEquals(first.bytes, second.bytes)
    }

    @Test
    fun `only inventoried owner paths are admitted`() {
        val ignoredAir = listOf(
            namedString("id", "minecraft:air"),
            namedByte("Count", 1)
        )
        val input = structure(
            compressed = false,
            blocks = listOf(
                blockEntityBlock(
                    "create:funnel",
                    listOf(namedCompound("Filter", ignoredAir))
                ),
                blockEntityBlock(
                    "example:funnel",
                    listOf(namedCompound("Filter", ignoredAir))
                ),
                blockEntityBlock(
                    "create:basin",
                    listOf(namedCompound("Item", ignoredAir))
                ),
                blockEntityBlock(
                    "create:deployer",
                    listOf(
                        namedCompound(
                            "HeldItem",
                            listOf(namedCompound("Item", ignoredAir))
                        )
                    )
                ),
                blockEntityBlock(
                    "create:brass_tunnel",
                    listOf(
                        namedList(
                            "Filters",
                            TAG_COMPOUND,
                            listOf(
                                compoundPayload(emptyList()),
                                compoundPayload(emptyList()),
                                compoundPayload(emptyList()),
                                compoundPayload(
                                    listOf(namedCompound("Filter", ignoredAir))
                                )
                            )
                        )
                    )
                )
            )
        )

        val migration =
            CreateStructureEmptyItemNbtMigration.migrateBytes(input, "owner-paths")
        assertEquals(1, migration.migratedAirCount)
        val decoded = CreateStructureFluidNbtMigration.decodeTypedNbt(migration.bytes)
        assertTrue(itemField(decoded.document.root, 0, "Filter").entries.isEmpty())
        assertFalse(itemField(decoded.document.root, 1, "Filter").entries.isEmpty())
        assertFalse(itemField(decoded.document.root, 2, "Item").entries.isEmpty())
        assertFalse(itemField(decoded.document.root, 3, "HeldItem").entries.isEmpty())
        val filters = blockEntity(decoded.document.root, 4).entries["Filters"] as ListTag
        val indexThree = filters.values[3] as CompoundTag
        assertFalse((indexThree.entries["Filter"] as CompoundTag).entries.isEmpty())
    }

    @Test
    fun `unsupported air payloads block the whole joint plan before writes`() {
        val root = tempDir.resolve("resources").also { it.createDirectories() }
        val accepted = root.resolve("data/create/structures/gametest/accepted.nbt")
            .also { it.parent.createDirectories() }
        val tagged = root.resolve("data/create/structures/gametest/tagged.nbt")
        val extra = root.resolve("data/create/structures/gametest/extra.nbt")
        val wrongType = root.resolve("data/create/structures/gametest/wrong-type.nbt")
        val acceptedBytes = structure(
            compressed = true,
            blocks = listOf(blockEntityBlock(
                "create:funnel",
                listOf(namedCompound(
                    "Filter",
                    listOf(namedString("id", "minecraft:air"), namedByte("Count", 1))
                ))
            ))
        )
        val taggedBytes = structure(
            compressed = true,
            blocks = listOf(blockEntityBlock(
                "create:funnel",
                listOf(namedCompound(
                    "Filter",
                    listOf(
                        namedString("id", "minecraft:air"),
                        namedByte("Count", 1),
                        namedCompound("tag", emptyList())
                    )
                ))
            ))
        )
        val extraBytes = structure(
            compressed = true,
            blocks = listOf(blockEntityBlock(
                "create:funnel",
                listOf(namedCompound(
                    "Filter",
                    listOf(
                        namedString("id", "minecraft:air"),
                        namedByte("Count", 0),
                        namedByte("Unknown", 1)
                    )
                ))
            ))
        )
        val wrongTypeBytes = structure(
            compressed = true,
            blocks = listOf(blockEntityBlock(
                "create:funnel",
                listOf(namedCompound(
                    "Filter",
                    listOf(namedString("id", "minecraft:air"), namedShort("Count", 1))
                ))
            ))
        )
        accepted.writeBytes(acceptedBytes)
        tagged.writeBytes(taggedBytes)
        extra.writeBytes(extraBytes)
        wrongType.writeBytes(wrongTypeBytes)

        val plan = CreateStructureNbtMigrationStage.plan(listOf(root))
        assertFalse(plan.accepted)
        assertEquals(1, plan.files.size)
        assertEquals(3, plan.blockers.size)
        assertTrue(plan.blockers.any { it.contains("unsupported legacy air ItemStack keys") })
        assertTrue(plan.blockers.any { it.contains("TAG_Byte or TAG_Int") })
        val failure =
            runCatching { CreateStructureNbtMigrationStage.apply(plan) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertContentEquals(acceptedBytes, Files.readAllBytes(accepted))
        assertContentEquals(taggedBytes, Files.readAllBytes(tagged))
        assertContentEquals(extraBytes, Files.readAllBytes(extra))
        assertContentEquals(wrongTypeBytes, Files.readAllBytes(wrongType))
    }

    @Test
    fun `joint stage composes fluid and air once and is idempotent`() {
        val root = tempDir.resolve("resources").also { it.createDirectories() }
        val file = root.resolve("data/create/structure/gametest/joint.nbt")
            .also { it.parent.createDirectories() }
        file.writeBytes(
            structure(
                compressed = true,
                blocks = listOf(
                    blockEntityBlock(
                        "create:fluid_tank",
                        listOf(namedCompound(
                            "TankContent",
                            listOf(
                                namedString("FluidName", "minecraft:water"),
                                namedInt("Amount", 5000)
                            )
                        ))
                    ),
                    blockEntityBlock(
                        "create:funnel",
                        listOf(namedCompound(
                            "Filter",
                            listOf(
                                namedString("id", "minecraft:air"),
                                namedByte("Count", 1)
                            )
                        ))
                    )
                )
            )
        )

        val first = CreateStructureNbtMigrationStage.plan(listOf(root))
        assertTrue(first.accepted)
        assertEquals(1, first.files.size)
        assertEquals(1, first.migratedTankCount)
        assertEquals(1, first.migratedAirCount)
        CreateStructureNbtMigrationStage.apply(first)

        val second = CreateStructureNbtMigrationStage.plan(listOf(root))
        assertTrue(second.accepted)
        assertTrue(second.files.isEmpty())
        assertEquals(0, second.migratedTankCount)
        assertEquals(0, second.migratedAirCount)
    }

    private fun itemField(
        root: CompoundTag,
        blockIndex: Int,
        key: String
    ): CompoundTag = blockEntity(root, blockIndex).entries[key] as CompoundTag

    private fun blockEntity(root: CompoundTag, blockIndex: Int): CompoundTag {
        val blocks = root.entries["blocks"] as ListTag
        val block = blocks.values[blockIndex] as CompoundTag
        return block.entries["nbt"] as CompoundTag
    }

    private fun structure(compressed: Boolean, blocks: List<ByteArray>): ByteArray {
        val document = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(TAG_COMPOUND)
                output.writeNbtString("typed-root")
                output.write(namedInt("DataVersion", 3465))
                output.write(namedList("blocks", TAG_COMPOUND, blocks))
                output.writeByte(TAG_END)
            }
            bytes.toByteArray()
        }
        if (!compressed) return document
        return ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).use { it.write(document) }
            bytes.toByteArray()
        }
    }

    private fun blockEntityBlock(
        blockEntityId: String,
        entries: List<ByteArray>
    ): ByteArray = compoundPayload(
        listOf(
            namedList("pos", TAG_INT, listOf(intPayload(1), intPayload(2), intPayload(3))),
            namedInt("state", 0),
            namedCompound("nbt", listOf(namedString("id", blockEntityId)) + entries)
        )
    )

    private fun namedByte(name: String, value: Int) =
        named(TAG_BYTE, name, byteArrayOf(value.toByte()))
    private fun namedShort(name: String, value: Int) =
        named(TAG_SHORT, name, shortPayload(value))
    private fun namedInt(name: String, value: Int) =
        named(TAG_INT, name, intPayload(value))
    private fun namedString(name: String, value: String) =
        named(TAG_STRING, name, nbtString(value))
    private fun namedList(name: String, elementType: Int, values: List<ByteArray>) =
        named(
            TAG_LIST,
            name,
            byteArrayOf(elementType.toByte()) + intPayload(values.size) + values.flattenBytes()
        )
    private fun namedCompound(name: String, entries: List<ByteArray>) =
        named(TAG_COMPOUND, name, compoundPayload(entries))
    private fun compoundPayload(entries: List<ByteArray>) =
        entries.flattenBytes() + byteArrayOf(TAG_END.toByte())
    private fun named(type: Int, name: String, payload: ByteArray) =
        byteArrayOf(type.toByte()) + nbtString(name) + payload
    private fun nbtString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return shortPayload(bytes.size) + bytes
    }
    private fun shortPayload(value: Int) = numericPayload { it.writeShort(value) }
    private fun intPayload(value: Int) = numericPayload { it.writeInt(value) }
    private fun numericPayload(write: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use(write)
            bytes.toByteArray()
        }
    private fun DataOutputStream.writeNbtString(value: String) = write(nbtString(value))
    private fun List<ByteArray>.flattenBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        forEach(output::write)
        return output.toByteArray()
    }
    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return indices.any { start ->
            start + needle.size <= size &&
                needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }

    private companion object {
        const val TAG_END = 0
        const val TAG_BYTE = 1
        const val TAG_SHORT = 2
        const val TAG_INT = 3
        const val TAG_STRING = 8
        const val TAG_LIST = 9
        const val TAG_COMPOUND = 10
    }
}
