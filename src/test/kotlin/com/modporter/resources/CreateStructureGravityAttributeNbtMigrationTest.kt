package com.modporter.resources

import com.modporter.resources.CreateStructureFluidNbtMigration.ByteTag
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateStructureGravityAttributeNbtMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exact root and passenger profiles migrate with typed fields preserved`() {
        for (compression in Compression.values()) {
            val armorStand = entity(
                "minecraft:armor_stand",
                attributes = listOf(
                    attribute("example:unrelated", DoubleTag(bits(0.25))),
                    attribute(PORTING_LIB_GRAVITY)
                ),
                passengers = listOf(
                    entity(
                        "minecraft:zombie",
                        listOf(
                            attribute(
                                FORGE_GRAVITY,
                                modifiers = ListTag(TAG_END, mutableListOf())
                            )
                        )
                    )
                ),
                extra = "Marker" to ByteTag(1)
            )
            val secondZombie =
                entity("minecraft:zombie", listOf(attribute(FORGE_GRAVITY)))
            val unknownModEntity =
                entity("example:mob", listOf(attribute(PORTING_LIB_GRAVITY)))
            val decoy = compound(
                "Attributes" to compounds(attribute(FORGE_GRAVITY))
            )
            val input = structureBytes(
                compression,
                linkedMapOf(
                    "DataVersion" to IntTag(3465),
                    "entities" to compounds(
                        wrapper(armorStand),
                        wrapper(secondZombie),
                        wrapper(unknownModEntity)
                    ),
                    "decoy" to decoy
                )
            )

            val first = CreateStructureNbtMigrationStage.migrateBytes(
                input,
                "exact-$compression",
                migrateGravityAttributes = true
            )
            assertEquals(3, first.migratedGravityCount)
            assertEquals(0, first.migratedTankCount)
            assertEquals(0, first.migratedAirCount)

            val decoded = CreateStructureFluidNbtMigration.decodeTypedNbt(first.bytes)
            assertEquals(compression, decoded.compression)
            assertEquals(3465, (decoded.document.root.entries["DataVersion"] as IntTag).value)
            val migratedArmorStand = rootEntity(decoded.document.root, 0)
            assertEquals(
                listOf("id", "Marker", "Attributes", "Passengers"),
                migratedArmorStand.entries.keys.toList()
            )
            assertEquals(
                listOf("example:unrelated", TARGET_GRAVITY),
                attributeNames(migratedArmorStand)
            )
            assertEquals(
                TARGET_GRAVITY,
                attributeNames(passenger(migratedArmorStand, 0)).single()
            )
            val passengerGravity =
                attributes(passenger(migratedArmorStand, 0)).values.single() as CompoundTag
            val emptyModifiers = passengerGravity.entries["Modifiers"] as ListTag
            assertEquals(TAG_END, emptyModifiers.elementType)
            assertTrue(emptyModifiers.values.isEmpty())
            assertEquals(
                TARGET_GRAVITY,
                attributeNames(rootEntity(decoded.document.root, 1)).single()
            )
            assertEquals(
                PORTING_LIB_GRAVITY,
                attributeNames(rootEntity(decoded.document.root, 2)).single()
            )
            assertEquals(
                FORGE_GRAVITY,
                attributeNames(decoded.document.root.entries["decoy"] as CompoundTag).single()
            )
            val migratedGravity = attributes(migratedArmorStand).values[1] as CompoundTag
            assertEquals(listOf("Base", "Name"), migratedGravity.entries.keys.toList())
            assertEquals(BASE_BITS, (migratedGravity.entries["Base"] as DoubleTag).rawBits)

            val second = CreateStructureNbtMigrationStage.migrateBytes(
                first.bytes,
                "exact-$compression-second",
                migrateGravityAttributes = true
            )
            assertEquals(0, second.migratedGravityCount)
            assertContentEquals(first.bytes, second.bytes)
        }
    }

    @Test
    fun `empty TAG End lists are admitted and preserve exact bytes`() {
        val emptyRootEntities = structureBytes(
            Compression.GZIP,
            linkedMapOf("entities" to ListTag(TAG_END, mutableListOf()))
        )
        val emptyRootMigration = CreateStructureNbtMigrationStage.migrateBytes(
            emptyRootEntities,
            "empty-root",
            migrateGravityAttributes = true
        )
        assertEquals(0, emptyRootMigration.migratedGravityCount)
        assertContentEquals(emptyRootEntities, emptyRootMigration.bytes)

        val knownEntityWithEmptyLists = entity(
            "minecraft:zombie",
            extraEntries = listOf(
                "Attributes" to ListTag(TAG_END, mutableListOf()),
                "Passengers" to ListTag(TAG_END, mutableListOf())
            )
        )
        val emptyNestedLists = structureBytes(
            Compression.RAW,
            linkedMapOf("entities" to compounds(wrapper(knownEntityWithEmptyLists)))
        )
        val emptyNestedMigration = CreateStructureNbtMigrationStage.migrateBytes(
            emptyNestedLists,
            "empty-nested",
            migrateGravityAttributes = true
        )
        assertEquals(0, emptyNestedMigration.migratedGravityCount)
        assertContentEquals(emptyNestedLists, emptyNestedMigration.bytes)
    }

    @Test
    fun `unsupported recognized gravity shapes block the whole plan before writes`() {
        val root = tempDir.resolve("resources").also { it.createDirectories() }
        val cases = linkedMapOf(
            "accepted.nbt" to entity(
                "minecraft:armor_stand",
                listOf(attribute(PORTING_LIB_GRAVITY))
            ),
            "duplicate.nbt" to entity(
                "minecraft:zombie",
                listOf(attribute(FORGE_GRAVITY), attribute(FORGE_GRAVITY))
            ),
            "collision.nbt" to entity(
                "minecraft:zombie",
                listOf(attribute(FORGE_GRAVITY), attribute(TARGET_GRAVITY))
            ),
            "unproved-alias.nbt" to entity(
                "minecraft:armor_stand",
                listOf(attribute(FORGE_GRAVITY))
            ),
            "wrong-base-type.nbt" to entity(
                "minecraft:zombie",
                listOf(attribute(FORGE_GRAVITY, IntTag(0)))
            ),
            "wrong-base-value.nbt" to entity(
                "minecraft:zombie",
                listOf(attribute(FORGE_GRAVITY, DoubleTag(bits(0.09))))
            ),
            "modifiers.nbt" to entity(
                "minecraft:zombie",
                listOf(
                    attribute(
                        FORGE_GRAVITY,
                        modifiers = compounds(compound("Operation" to IntTag(0)))
                    )
                )
            )
        )
        val originals = linkedMapOf<Path, ByteArray>()
        cases.forEach { (name, entity) ->
            val file = root.resolve("data/create/structure/gametest/$name")
                .also { it.parent.createDirectories() }
            val bytes = structureBytes(
                Compression.GZIP,
                linkedMapOf("entities" to compounds(wrapper(entity)))
            )
            file.writeBytes(bytes)
            originals[file] = bytes
        }

        val plan = CreateStructureNbtMigrationStage.plan(listOf(root))
        assertFalse(plan.accepted)
        assertEquals(1, plan.files.size)
        assertEquals(6, plan.blockers.size)
        assertTrue(plan.blockers.any { it.contains("duplicate legacy gravity aliases") })
        assertTrue(plan.blockers.any { it.contains("native gravity target already exists") })
        assertTrue(plan.blockers.any { it.contains("legacy gravity alias") && it.contains("not admitted") })
        assertTrue(plan.blockers.any { it.contains("Base must be TAG_Double") })
        assertTrue(plan.blockers.any { it.contains("Base must be exactly 0.08") })
        assertTrue(plan.blockers.any { it.contains("Modifiers must be empty") })
        val failure =
            runCatching { CreateStructureNbtMigrationStage.apply(plan) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        originals.forEach { (file, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(file))
        }
    }

    @Test
    fun `joint stage composes all three migrations only in Create data paths`() {
        val root = tempDir.resolve("resources").also { it.createDirectories() }
        val joint = root.resolve("data/create/structures/gametest/joint.nbt")
            .also { it.parent.createDirectories() }
        val generic = root.resolve("gameteststructures/generic.nbt")
            .also { it.parent.createDirectories() }
        val rootEntries = linkedMapOf<String, NbtTag>(
            "DataVersion" to IntTag(3465),
            "blocks" to compounds(
                blockEntity(
                    "create:fluid_tank",
                    "TankContent" to compound(
                        "FluidName" to StringTag("minecraft:water"),
                        "Amount" to IntTag(5000)
                    )
                ),
                blockEntity(
                    "create:funnel",
                    "Filter" to compound(
                        "id" to StringTag("minecraft:air"),
                        "Count" to ByteTag(1)
                    )
                )
            ),
            "entities" to compounds(
                wrapper(entity("minecraft:zombie", listOf(attribute(FORGE_GRAVITY))))
            )
        )
        val jointBytes = structureBytes(Compression.GZIP, rootEntries)
        val genericBytes = structureBytes(
            Compression.GZIP,
            linkedMapOf(
                "entities" to compounds(
                    wrapper(entity("minecraft:zombie", listOf(attribute(FORGE_GRAVITY))))
                )
            )
        )
        joint.writeBytes(jointBytes)
        generic.writeBytes(genericBytes)

        val first = CreateStructureNbtMigrationStage.plan(listOf(root))
        assertTrue(first.accepted)
        assertEquals(1, first.files.size)
        assertEquals(1, first.migratedTankCount)
        assertEquals(1, first.migratedAirCount)
        assertEquals(1, first.migratedGravityCount)
        CreateStructureNbtMigrationStage.apply(first)
        assertContentEquals(genericBytes, Files.readAllBytes(generic))

        val second = CreateStructureNbtMigrationStage.plan(listOf(root))
        assertTrue(second.accepted)
        assertTrue(second.files.isEmpty())
        assertEquals(0, second.migratedGravityCount)
    }

    private fun structureBytes(
        compression: Compression,
        entries: LinkedHashMap<String, NbtTag>
    ): ByteArray = CreateStructureFluidNbtMigration.encodeTypedNbt(
        TypedNbtFile(NbtDocument("typed-root", CompoundTag(entries)), compression)
    )

    private fun wrapper(entity: CompoundTag): CompoundTag =
        compound("nbt" to entity)

    private fun entity(
        id: String,
        attributes: List<CompoundTag> = emptyList(),
        passengers: List<CompoundTag> = emptyList(),
        extra: Pair<String, NbtTag>? = null,
        extraEntries: List<Pair<String, NbtTag>> = emptyList()
    ): CompoundTag {
        val entries = linkedMapOf<String, NbtTag>("id" to StringTag(id))
        extra?.let { entries[it.first] = it.second }
        if (attributes.isNotEmpty()) entries["Attributes"] = compounds(*attributes.toTypedArray())
        if (passengers.isNotEmpty()) entries["Passengers"] = compounds(*passengers.toTypedArray())
        extraEntries.forEach { entries[it.first] = it.second }
        return CompoundTag(entries)
    }

    private fun attribute(
        name: String,
        base: NbtTag = DoubleTag(BASE_BITS),
        modifiers: ListTag? = null
    ): CompoundTag {
        val entries = linkedMapOf<String, NbtTag>(
            "Base" to base,
            "Name" to StringTag(name)
        )
        modifiers?.let { entries["Modifiers"] = it }
        return CompoundTag(entries)
    }

    private fun blockEntity(
        id: String,
        vararg entries: Pair<String, NbtTag>
    ): CompoundTag {
        val nbtEntries = linkedMapOf<String, NbtTag>("id" to StringTag(id))
        entries.forEach { nbtEntries[it.first] = it.second }
        return compound("nbt" to CompoundTag(nbtEntries))
    }

    private fun compound(vararg entries: Pair<String, NbtTag>): CompoundTag =
        CompoundTag(linkedMapOf(*entries))

    private fun compounds(vararg values: CompoundTag): ListTag =
        ListTag(TAG_COMPOUND, values.toMutableList())

    private fun rootEntity(root: CompoundTag, index: Int): CompoundTag {
        val wrapper = (root.entries["entities"] as ListTag).values[index] as CompoundTag
        return wrapper.entries["nbt"] as CompoundTag
    }

    private fun passenger(entity: CompoundTag, index: Int): CompoundTag =
        (entity.entries["Passengers"] as ListTag).values[index] as CompoundTag

    private fun attributes(entity: CompoundTag): ListTag =
        entity.entries["Attributes"] as ListTag

    private fun attributeNames(entity: CompoundTag): List<String> =
        attributes(entity).values.map {
            ((it as CompoundTag).entries["Name"] as StringTag).value
        }

    private companion object {
        const val TAG_END = 0
        const val TAG_COMPOUND = 10
        const val TARGET_GRAVITY = "minecraft:generic.gravity"
        const val FORGE_GRAVITY = "forge:entity_gravity"
        const val PORTING_LIB_GRAVITY = "porting_lib:entity_gravity"
        val BASE_BITS: Long = bits(0.08)

        fun bits(value: Double): Long = java.lang.Double.doubleToRawLongBits(value)
    }
}
