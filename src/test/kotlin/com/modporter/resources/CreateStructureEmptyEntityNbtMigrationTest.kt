package com.modporter.resources

import com.modporter.resources.CreateStructureFluidNbtMigration.CompoundTag
import com.modporter.resources.CreateStructureFluidNbtMigration.Compression
import com.modporter.resources.CreateStructureFluidNbtMigration.DoubleTag
import com.modporter.resources.CreateStructureFluidNbtMigration.IntTag
import com.modporter.resources.CreateStructureFluidNbtMigration.ListTag
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtDocument
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtTag
import com.modporter.resources.CreateStructureFluidNbtMigration.StringTag
import com.modporter.resources.CreateStructureFluidNbtMigration.TypedNbtFile
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CreateStructureEmptyEntityNbtMigrationTest {
    @Test
    fun `exact source profile removes only empty roots and preserves seated zombies`() {
        val document = openPipesDocument()

        val migrated = CreateStructureEmptyEntityNbtMigration.migrateDocument(
            document,
            OPEN_PIPES_PATH,
            CreateStructureEmptyEntityNbtMigration.EXPECTED_SOURCE_SHA256
        )

        assertEquals(2, migrated)
        val remaining = entities(document)
        assertEquals(2, remaining.values.size)
        assertEquals(listOf("create:seat", "create:seat"), remaining.values.map(::entityId))
        assertEquals(
            listOf("Gerald North", "Gerald South"),
            remaining.values.map { wrapper ->
                val seat = wrapperEntity(wrapper)
                val passenger = passengers(seat).values.single() as CompoundTag
                assertEquals("minecraft:zombie", (passenger.entries["id"] as StringTag).value)
                (passenger.entries["CustomName"] as StringTag).value
            }
        )
        assertEquals(
            listOf(listOf(4, 1, 3), listOf(4, 1, 1)),
            remaining.values.map(::blockPos)
        )
    }

    @Test
    fun `joint stage rejects a target-only exact shape without source byte identity`() {
        val input = encode(openPipesDocument())

        val failure = assertFailsWith<IllegalArgumentException> {
            CreateStructureNbtMigrationStage.migrateBytes(
                input,
                OPEN_PIPES_PATH,
                migrateGravityAttributes = true
            )
        }

        assertTrue(failure.message.orEmpty().contains("not admitted for source SHA-256"))
        assertTrue(
            failure.message.orEmpty().contains(
                CreateStructureEmptyEntityNbtMigration.EXPECTED_SOURCE_SHA256
            )
        )
    }

    @Test
    fun `count position type and wrapper near misses reject before mutation`() {
        val cases = linkedMapOf<String, (NbtDocument) -> Unit>(
            "missing second placeholder" to { document ->
                entities(document).values.removeAt(2)
            },
            "new unknown placeholder" to { document ->
                entities(document).values.add(
                    emptyWrapper(
                        blockPos = listOf(9, 9, 9),
                        posBits = listOf(bits(9.0), bits(9.0), bits(9.0))
                    )
                )
            },
            "wrong block position" to { document ->
                val wrapper = entities(document).values[0] as CompoundTag
                wrapper.entries["blockPos"] = ints(4, 0, 4)
            },
            "wrong position type" to { document ->
                val wrapper = entities(document).values[0] as CompoundTag
                wrapper.entries["pos"] = ints(4, 0, 3)
            },
            "extra wrapper key" to { document ->
                val wrapper = entities(document).values[0] as CompoundTag
                wrapper.entries["unexpected"] = IntTag(1)
            },
            "missing nbt payload" to { document ->
                (entities(document).values[0] as CompoundTag).entries.remove("nbt")
                (entities(document).values[2] as CompoundTag).entries.remove("nbt")
            },
            "nonempty missing-id payload" to { document ->
                val wrapper = entities(document).values[0] as CompoundTag
                wrapper.entries["nbt"] = compound("Marker" to IntTag(1))
            },
            "blank entity id" to { document ->
                val wrapper = entities(document).values[0] as CompoundTag
                wrapper.entries["nbt"] = compound("id" to StringTag(""))
            }
        )

        cases.forEach { (label, mutate) ->
            val document = openPipesDocument()
            mutate(document)
            val before = encode(document)

            val failure = assertFailsWith<IllegalArgumentException>(label) {
                CreateStructureEmptyEntityNbtMigration.migrateDocument(
                    document,
                    OPEN_PIPES_PATH,
                    CreateStructureEmptyEntityNbtMigration.EXPECTED_SOURCE_SHA256
                )
            }

            assertTrue(failure.message.orEmpty().contains("open_pipes") ||
                failure.message.orEmpty().contains("entities"))
            assertContentEquals(before, encode(document), "$label mutated the typed document")
        }
    }

    @Test
    fun `cleanup is idempotent and dormant train observer remains outside scope`() {
        val document = openPipesDocument()
        assertEquals(
            2,
            CreateStructureEmptyEntityNbtMigration.migrateDocument(
                document,
                OPEN_PIPES_PATH,
                CreateStructureEmptyEntityNbtMigration.EXPECTED_SOURCE_SHA256
            )
        )
        val once = encode(document)
        assertEquals(
            0,
            CreateStructureEmptyEntityNbtMigration.migrateDocument(
                document,
                OPEN_PIPES_PATH,
                "target-bytes-after-cleanup"
            )
        )
        assertContentEquals(once, encode(document))
        val stageSecondPass = CreateStructureNbtMigrationStage.migrateBytes(
            once,
            OPEN_PIPES_PATH,
            migrateGravityAttributes = true
        )
        assertEquals(0, stageSecondPass.migratedEmptyEntityCount)
        assertContentEquals(once, stageSecondPass.bytes)

        val dormant = NbtDocument(
            "typed-root",
            compound(
                "entities" to compounds(
                    emptyWrapper(
                        blockPos = listOf(2, 3, 4),
                        posBits = listOf(bits(2.5), bits(3.5), bits(4.5))
                    )
                )
            )
        )
        val dormantBefore = encode(dormant)
        assertEquals(
            0,
            CreateStructureEmptyEntityNbtMigration.migrateDocument(
                dormant,
                "data/create/structure/gametest/contraptions/train_observer.nbt",
                "af5e7788baccef8412ab1b5753bd30b3e20aba4623ceecd636354202c0556af8"
            )
        )
        assertContentEquals(dormantBefore, encode(dormant))
    }

    private fun openPipesDocument(): NbtDocument = NbtDocument(
        "typed-root",
        compound(
            "DataVersion" to IntTag(3465),
            "entities" to compounds(
                emptyWrapper(
                    blockPos = listOf(4, 0, 3),
                    posBits = listOf(
                        0x4012000000000000L,
                        0x3fe9fffffd999980L,
                        0x400c000000000000L
                    )
                ),
                seatWrapper(4, 1, 3, "Gerald North"),
                emptyWrapper(
                    blockPos = listOf(4, 0, 1),
                    posBits = listOf(
                        0x4012000000000000L,
                        0x3fe9fffffd999980L,
                        0x3ff8000000000000L
                    )
                ),
                seatWrapper(4, 1, 1, "Gerald South")
            ),
            "Marker" to StringTag("preserve")
        )
    )

    private fun emptyWrapper(
        blockPos: List<Int>,
        posBits: List<Long>
    ): CompoundTag = compound(
        "nbt" to CompoundTag(),
        "blockPos" to ListTag(TAG_INT, blockPos.map(::IntTag).toMutableList()),
        "pos" to ListTag(TAG_DOUBLE, posBits.map(::DoubleTag).toMutableList())
    )

    private fun seatWrapper(
        x: Int,
        y: Int,
        z: Int,
        passengerName: String
    ): CompoundTag = compound(
        "nbt" to compound(
            "id" to StringTag("create:seat"),
            "Passengers" to compounds(
                compound(
                    "id" to StringTag("minecraft:zombie"),
                    "CustomName" to StringTag(passengerName)
                )
            )
        ),
        "blockPos" to ints(x, y, z),
        "pos" to doubles(x.toDouble(), y.toDouble(), z.toDouble())
    )

    private fun entityId(wrapper: NbtTag): String =
        (wrapperEntity(wrapper).entries["id"] as StringTag).value

    private fun wrapperEntity(wrapper: NbtTag): CompoundTag =
        (wrapper as CompoundTag).entries["nbt"] as CompoundTag

    private fun passengers(entity: CompoundTag): ListTag =
        entity.entries["Passengers"] as ListTag

    private fun blockPos(wrapper: NbtTag): List<Int> =
        ((wrapper as CompoundTag).entries["blockPos"] as ListTag).values.map {
            (it as IntTag).value
        }

    private fun entities(document: NbtDocument): ListTag =
        document.root.entries["entities"] as ListTag

    private fun encode(document: NbtDocument): ByteArray =
        CreateStructureFluidNbtMigration.encodeTypedNbt(
            TypedNbtFile(document, Compression.RAW)
        )

    private fun ints(vararg values: Int): ListTag =
        ListTag(TAG_INT, values.map(::IntTag).toMutableList())

    private fun doubles(vararg values: Double): ListTag =
        ListTag(TAG_DOUBLE, values.map { DoubleTag(bits(it)) }.toMutableList())

    private fun compound(vararg entries: Pair<String, NbtTag>): CompoundTag =
        CompoundTag(linkedMapOf(*entries))

    private fun compounds(vararg values: CompoundTag): ListTag =
        ListTag(TAG_COMPOUND, values.toMutableList())

    private companion object {
        const val TAG_INT = 3
        const val TAG_DOUBLE = 6
        const val TAG_COMPOUND = 10
        const val OPEN_PIPES_PATH =
            "data/create/structure/gametest/fluids/open_pipes.nbt"

        fun bits(value: Double): Long = java.lang.Double.doubleToRawLongBits(value)
    }
}
