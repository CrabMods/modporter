package com.modporter.resources

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateStructureFluidNbtMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `plain and empty Create tanks migrate while unrelated typed payload is preserved`() {
        val sentinel = namedCompound(
            "Sentinel",
            listOf(
                namedByte("byte", -7),
                namedShort("short", -1234),
                namedInt("int", 0x10203040),
                namedLong("long", -0x102030405060708),
                namedFloat("float", 1.25f),
                namedDouble("double", -9.5),
                namedRawFloat("noncanonical-float-nan", 0x7fa12345),
                namedRawDouble("noncanonical-double-nan", 0x7ff123456789abcdL),
                namedByteArray("bytes", byteArrayOf(-1, 0, 1, 127)),
                namedString("string", "keep-me"),
                namedList("list", TAG_SHORT, listOf(shortPayload(3), shortPayload(-4))),
                namedIntArray("ints", intArrayOf(Int.MIN_VALUE, 0, Int.MAX_VALUE)),
                namedLongArray("longs", longArrayOf(Long.MIN_VALUE, 7, Long.MAX_VALUE))
            )
        )
        val input = structure(
            compressed = true,
            extraRootEntries = listOf(sentinel, namedList("entities", TAG_COMPOUND, listOf(compoundPayload(
                listOf(namedString("id", "minecraft:marker"), namedDouble("x", 2.5))
            )))),
            blocks = listOf(
                tankBlock(
                    "create:fluid_tank",
                    listOf(namedString("FluidName", "minecraft:water"), namedInt("Amount", 5000))
                ),
                tankBlock(
                    "create:fluid_tank",
                    listOf(namedString("FluidName", "minecraft:empty"), namedInt("Amount", 0))
                )
            )
        )

        val first = CreateStructureFluidNbtMigration.migrateBytes(input, "plain")
        assertEquals(2, first.migratedTankCount)
        val decoded = gunzip(first.bytes)
        assertTrue(decoded.containsSubsequence(sentinel))
        assertTrue(decoded.containsAscii("Fluid"))
        assertTrue(decoded.containsAscii("amount"))
        assertTrue(decoded.containsAscii("minecraft:water"))
        assertFalse(decoded.containsAscii("FluidName"))
        assertFalse(decoded.containsAscii("minecraft:empty"))
        assertTrue(decoded.containsAscii("DataVersion"))
        assertTrue(decoded.containsSubsequence(intPayload(3465)))

        val second = CreateStructureFluidNbtMigration.migrateBytes(first.bytes, "plain-idempotent")
        assertEquals(0, second.migratedTankCount)
        assertContentEquals(first.bytes, second.bytes)
    }

    @Test
    fun `source-bound Create potion tag is preserved under custom data`() {
        val input = structure(
            compressed = true,
            blocks = listOf(
                tankBlock(
                    "create:fluid_tank",
                    listOf(
                        namedString("FluidName", "create:potion"),
                        namedInt("Amount", 750),
                        namedCompound(
                            "Tag",
                            listOf(
                                namedString("Bottle", "REGULAR"),
                                namedString("Potion", "minecraft:fire_resistance")
                            )
                        )
                    )
                )
            )
        )

        val migrated = CreateStructureFluidNbtMigration.migrateBytes(input, "potion")
        assertEquals(1, migrated.migratedTankCount)
        val decoded = gunzip(migrated.bytes)
        assertTrue(decoded.containsAscii("components"))
        assertTrue(decoded.containsAscii("minecraft:custom_data"))
        assertFalse(decoded.containsAscii("create:potion_fluid_bottle_type"))
        assertFalse(decoded.containsAscii("minecraft:potion_contents"))
        assertFalse(decoded.containsAscii("FluidName"))

        val root = CreateStructureFluidNbtMigration.decodeTypedNbt(migrated.bytes).document.root
        val blocks = root.entries["blocks"] as CreateStructureFluidNbtMigration.ListTag
        val block = blocks.values.single() as CreateStructureFluidNbtMigration.CompoundTag
        val blockEntity = block.entries["nbt"] as CreateStructureFluidNbtMigration.CompoundTag
        val tank = blockEntity.entries["TankContent"] as CreateStructureFluidNbtMigration.CompoundTag
        val fluid = tank.entries["Fluid"] as CreateStructureFluidNbtMigration.CompoundTag
        val components = fluid.entries["components"] as CreateStructureFluidNbtMigration.CompoundTag
        val customData =
            components.entries["minecraft:custom_data"] as CreateStructureFluidNbtMigration.CompoundTag
        assertEquals(listOf("Bottle", "Potion"), customData.entries.keys.toList())
        assertEquals(
            CreateStructureFluidNbtMigration.StringTag("REGULAR"),
            customData.entries["Bottle"]
        )
        assertEquals(
            CreateStructureFluidNbtMigration.StringTag("minecraft:fire_resistance"),
            customData.entries["Potion"]
        )

        val second = CreateStructureFluidNbtMigration.migrateBytes(migrated.bytes, "potion-idempotent")
        assertEquals(0, second.migratedTankCount)
        assertContentEquals(migrated.bytes, second.bytes)
    }

    @Test
    fun `basin spout and open end FluidTank consumers migrate without touching variant payloads`() {
        val input = structure(
            compressed = false,
            blocks = listOf(
                blockEntityBlock(
                    "create:mechanical_pump",
                    listOf(
                        namedCompound(
                            "west",
                            listOf(
                                namedCompound(
                                    "OpenEnd",
                                    listOf(
                                        namedByte("Pulling", 1),
                                        namedString("FluidName", "minecraft:empty"),
                                        namedInt("Amount", 0),
                                        namedCompound(
                                            "Location",
                                            listOf(
                                                namedInt("X", 1),
                                                namedInt("Y", 2),
                                                namedInt("Z", 3)
                                            )
                                        )
                                    )
                                )
                            )
                        ),
                        namedCompound(
                            "east",
                            listOf(
                                namedCompound(
                                    "OpenEnd",
                                    listOf(
                                        namedString("Variant", "source-owned"),
                                        namedByte("Pulling", 0),
                                        namedInt("Capacity", 1000),
                                        namedInt("Amount", 0),
                                        namedCompound(
                                            "Location",
                                            listOf(
                                                namedInt("X", 4),
                                                namedInt("Y", 5),
                                                namedInt("Z", 6)
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                blockEntityBlock(
                    "create:basin",
                    listOf(
                        namedList(
                            "InputTanks",
                            TAG_COMPOUND,
                            listOf(
                                tankSegment(
                                    listOf(
                                        namedString("FluidName", "minecraft:water"),
                                        namedInt("Amount", 1000)
                                    )
                                ),
                                tankSegment(
                                    listOf(
                                        namedString("Variant", "source-owned"),
                                        namedInt("Capacity", 1000),
                                        namedInt("Amount", 0)
                                    )
                                )
                            )
                        ),
                        namedList(
                            "OutputTanks",
                            TAG_COMPOUND,
                            listOf(
                                tankSegment(
                                    listOf(
                                        namedString("FluidName", "minecraft:empty"),
                                        namedInt("Amount", 0)
                                    )
                                )
                            )
                        )
                    )
                ),
                blockEntityBlock(
                    "create:spout",
                    listOf(
                        namedList(
                            "Tanks",
                            TAG_COMPOUND,
                            listOf(
                                tankSegment(
                                    listOf(
                                        namedString("FluidName", "minecraft:empty"),
                                        namedInt("Amount", 0)
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val first = CreateStructureFluidNbtMigration.migrateBytes(input, "consumer-fields")
        assertEquals(4, first.migratedTankCount)
        val root = CreateStructureFluidNbtMigration.decodeTypedNbt(first.bytes).document.root
        val blocks = root.entries["blocks"] as CreateStructureFluidNbtMigration.ListTag

        val pump = blockEntity(blocks, 0)
        val west = pump.entries["west"] as CreateStructureFluidNbtMigration.CompoundTag
        val openEnd = west.entries["OpenEnd"] as CreateStructureFluidNbtMigration.CompoundTag
        assertEquals(listOf("Pulling", "Location"), openEnd.entries.keys.toList())
        assertEquals(CreateStructureFluidNbtMigration.ByteTag(1), openEnd.entries["Pulling"])
        assertTrue(openEnd.entries["Location"] is CreateStructureFluidNbtMigration.CompoundTag)
        val east = pump.entries["east"] as CreateStructureFluidNbtMigration.CompoundTag
        val variantOpenEnd =
            east.entries["OpenEnd"] as CreateStructureFluidNbtMigration.CompoundTag
        assertEquals(
            listOf("Variant", "Pulling", "Capacity", "Amount", "Location"),
            variantOpenEnd.entries.keys.toList()
        )

        val basin = blockEntity(blocks, 1)
        val inputTanks = basin.entries["InputTanks"] as CreateStructureFluidNbtMigration.ListTag
        val water = tankContent(inputTanks, 0)
        val fluid = water.entries["Fluid"] as CreateStructureFluidNbtMigration.CompoundTag
        assertEquals(
            CreateStructureFluidNbtMigration.StringTag("minecraft:water"),
            fluid.entries["id"]
        )
        assertEquals(CreateStructureFluidNbtMigration.IntTag(1000), fluid.entries["amount"])
        val variant = tankContent(inputTanks, 1)
        assertEquals(
            listOf("Variant", "Capacity", "Amount"),
            variant.entries.keys.toList()
        )
        val outputTanks = basin.entries["OutputTanks"] as CreateStructureFluidNbtMigration.ListTag
        assertTrue(tankContent(outputTanks, 0).entries.isEmpty())

        val spout = blockEntity(blocks, 2)
        val spoutTanks = spout.entries["Tanks"] as CreateStructureFluidNbtMigration.ListTag
        assertTrue(tankContent(spoutTanks, 0).entries.isEmpty())

        val second = CreateStructureFluidNbtMigration.migrateBytes(
            first.bytes,
            "consumer-fields-second"
        )
        assertEquals(0, second.migratedTankCount)
        assertContentEquals(first.bytes, second.bytes)
    }

    @Test
    fun `unknown potion tag shape rejects complete plan before any file write`() {
        val root = tempDir.resolve("src/main/resources").also { it.createDirectories() }
        val acceptedFile = root.resolve("data/example/structures/gametest/a.nbt")
            .also { it.parent.createDirectories() }
        val rejectedFile = root.resolve("data/example/structures/gametest/b.nbt")
            .also { it.parent.createDirectories() }
        val acceptedBytes = structure(
            compressed = true,
            blocks = listOf(tankBlock(
                "create:fluid_tank",
                listOf(namedString("FluidName", "minecraft:water"), namedInt("Amount", 1000))
            ))
        )
        val rejectedBytes = structure(
            compressed = true,
            blocks = listOf(tankBlock(
                "create:fluid_tank",
                listOf(
                    namedString("FluidName", "create:potion"),
                    namedInt("Amount", 1000),
                    namedCompound(
                        "Tag",
                        listOf(
                            namedString("Bottle", "REGULAR"),
                            namedString("Potion", "minecraft:fire_resistance"),
                            namedString("opaque", "must-not-guess")
                        )
                    )
                )
            ))
        )
        acceptedFile.writeBytes(acceptedBytes)
        rejectedFile.writeBytes(rejectedBytes)

        val plan = CreateStructureFluidNbtMigration.plan(listOf(root))
        assertFalse(plan.accepted)
        assertEquals(1, plan.files.size)
        assertTrue(plan.blockers.single().contains("not the source-bound Bottle/Potion shape"))
        val failure = runCatching { CreateStructureFluidNbtMigration.apply(plan) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertContentEquals(acceptedBytes, Files.readAllBytes(acceptedFile))
        assertContentEquals(rejectedBytes, Files.readAllBytes(rejectedFile))
    }

    @Test
    fun `scanner admits active structure roots and exact Create block entity owners only`() {
        val root = tempDir.resolve("resources").also { it.createDirectories() }
        val createTank = structure(
            compressed = false,
            blocks = listOf(tankBlock(
                "create:fluid_tank",
                listOf(namedString("FluidName", "minecraft:water"), namedInt("Amount", 1000))
            ))
        )
        val otherTank = structure(
            compressed = false,
            blocks = listOf(tankBlock(
                "example:fluid_tank",
                listOf(namedString("FluidName", "minecraft:water"), namedInt("Amount", 1000))
            ))
        )
        root.resolve("assets/create/ponder/ignored.nbt")
            .also { it.parent.createDirectories() }
            .writeBytes(createTank)
        root.resolve("data/example/loot_tables/ignored.nbt")
            .also { it.parent.createDirectories() }
            .writeBytes(createTank)
        val admitted = root.resolve("gameteststructures/admitted.nbt")
            .also { it.parent.createDirectories() }
        admitted.writeBytes(createTank)
        root.resolve("data/example/structure/gametest/other-owner.nbt")
            .also { it.parent.createDirectories() }
            .writeBytes(otherTank)

        val plan = CreateStructureFluidNbtMigration.plan(listOf(root))
        assertTrue(plan.accepted)
        assertEquals(listOf(admitted.toAbsolutePath().normalize()), plan.files.map { it.path })
    }

    private fun structure(
        compressed: Boolean,
        extraRootEntries: List<ByteArray> = emptyList(),
        blocks: List<ByteArray>
    ): ByteArray {
        val document = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(TAG_COMPOUND)
                output.writeNbtString("typed-root")
                output.write(namedInt("DataVersion", 3465))
                extraRootEntries.forEach { output.write(it) }
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

    private fun tankBlock(blockEntityId: String, tankEntries: List<ByteArray>): ByteArray =
        blockEntityBlock(
            blockEntityId,
            listOf(
                namedCompound("TankContent", tankEntries),
                namedIntArray("Owner", intArrayOf(1, 2, 3, 4))
            )
        )

    private fun blockEntityBlock(
        blockEntityId: String,
        entries: List<ByteArray>
    ): ByteArray =
        compoundPayload(
            listOf(
                namedList("pos", TAG_INT, listOf(intPayload(1), intPayload(2), intPayload(3))),
                namedInt("state", 0),
                namedCompound(
                    "nbt",
                    listOf(namedString("id", blockEntityId)) + entries
                )
            )
        )

    private fun tankSegment(tankEntries: List<ByteArray>): ByteArray =
        compoundPayload(listOf(namedCompound("TankContent", tankEntries)))

    private fun blockEntity(
        blocks: CreateStructureFluidNbtMigration.ListTag,
        index: Int
    ): CreateStructureFluidNbtMigration.CompoundTag {
        val block = blocks.values[index] as CreateStructureFluidNbtMigration.CompoundTag
        return block.entries["nbt"] as CreateStructureFluidNbtMigration.CompoundTag
    }

    private fun tankContent(
        segments: CreateStructureFluidNbtMigration.ListTag,
        index: Int
    ): CreateStructureFluidNbtMigration.CompoundTag {
        val segment = segments.values[index] as CreateStructureFluidNbtMigration.CompoundTag
        return segment.entries["TankContent"] as CreateStructureFluidNbtMigration.CompoundTag
    }

    private fun namedByte(name: String, value: Int) = named(TAG_BYTE, name, byteArrayOf(value.toByte()))
    private fun namedShort(name: String, value: Int) = named(TAG_SHORT, name, shortPayload(value))
    private fun namedInt(name: String, value: Int) = named(TAG_INT, name, intPayload(value))
    private fun namedLong(name: String, value: Long) = named(TAG_LONG, name, longPayload(value))
    private fun namedFloat(name: String, value: Float) = named(TAG_FLOAT, name, floatPayload(value))
    private fun namedDouble(name: String, value: Double) = named(TAG_DOUBLE, name, doublePayload(value))
    private fun namedRawFloat(name: String, rawBits: Int) = named(TAG_FLOAT, name, intPayload(rawBits))
    private fun namedRawDouble(name: String, rawBits: Long) = named(TAG_DOUBLE, name, longPayload(rawBits))
    private fun namedString(name: String, value: String) = named(TAG_STRING, name, stringPayload(value))
    private fun namedByteArray(name: String, value: ByteArray) =
        named(TAG_BYTE_ARRAY, name, intPayload(value.size) + value)
    private fun namedIntArray(name: String, value: IntArray) =
        named(TAG_INT_ARRAY, name, intPayload(value.size) + value.asIterable().flatMapBytes(::intPayload))
    private fun namedLongArray(name: String, value: LongArray) =
        named(TAG_LONG_ARRAY, name, intPayload(value.size) + value.asIterable().flatMapBytes(::longPayload))

    private fun namedList(name: String, elementType: Int, values: List<ByteArray>): ByteArray =
        named(
            TAG_LIST,
            name,
            byteArrayOf(elementType.toByte()) + intPayload(values.size) + values.flattenBytes()
        )

    private fun namedCompound(name: String, entries: List<ByteArray>): ByteArray =
        named(TAG_COMPOUND, name, compoundPayload(entries))

    private fun compoundPayload(entries: List<ByteArray>): ByteArray =
        entries.flattenBytes() + byteArrayOf(TAG_END.toByte())

    private fun named(type: Int, name: String, payload: ByteArray): ByteArray =
        byteArrayOf(type.toByte()) + nbtString(name) + payload

    private fun nbtString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return shortPayload(bytes.size) + bytes
    }

    private fun stringPayload(value: String) = nbtString(value)
    private fun shortPayload(value: Int) = numericPayload { it.writeShort(value) }
    private fun intPayload(value: Int) = numericPayload { it.writeInt(value) }
    private fun longPayload(value: Long) = numericPayload { it.writeLong(value) }
    private fun floatPayload(value: Float) = numericPayload { it.writeFloat(value) }
    private fun doublePayload(value: Double) = numericPayload { it.writeDouble(value) }

    private fun numericPayload(write: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use(write)
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeNbtString(value: String) = write(nbtString(value))

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun ByteArray.containsAscii(value: String): Boolean =
        containsSubsequence(value.toByteArray(Charsets.UTF_8))

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return indices.any { start ->
            start + needle.size <= size &&
                needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }

    private fun List<ByteArray>.flattenBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        forEach { output.write(it) }
        return output.toByteArray()
    }

    private fun <T> Iterable<T>.flatMapBytes(transform: (T) -> ByteArray): ByteArray =
        map(transform).flattenBytes()

    private companion object {
        const val TAG_END = 0
        const val TAG_BYTE = 1
        const val TAG_SHORT = 2
        const val TAG_INT = 3
        const val TAG_LONG = 4
        const val TAG_FLOAT = 5
        const val TAG_DOUBLE = 6
        const val TAG_BYTE_ARRAY = 7
        const val TAG_STRING = 8
        const val TAG_LIST = 9
        const val TAG_COMPOUND = 10
        const val TAG_INT_ARRAY = 11
        const val TAG_LONG_ARRAY = 12
    }
}
