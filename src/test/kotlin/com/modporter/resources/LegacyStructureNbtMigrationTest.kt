package com.modporter.resources

import com.modporter.resources.TypedNbt.ByteTag
import com.modporter.resources.TypedNbt.CompoundTag
import com.modporter.resources.TypedNbt.Compression
import com.modporter.resources.TypedNbt.DoubleTag
import com.modporter.resources.TypedNbt.FloatTag
import com.modporter.resources.TypedNbt.IntTag
import com.modporter.resources.TypedNbt.ListTag
import com.modporter.resources.TypedNbt.NbtDocument
import com.modporter.resources.TypedNbt.NbtTag
import com.modporter.resources.TypedNbt.ShortTag
import com.modporter.resources.TypedNbt.StringTag
import com.modporter.resources.TypedNbt.TypedNbtFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyStructureNbtMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `modded owners get componentized stacks while vanilla owners are left to the data fixer`() {
        val nestedFilter = stack(
            "example:filter",
            ByteTag(1),
            tag = compound(
                "Items" to compound(
                    "Size" to IntTag(9),
                    "Items" to list(stack("minecraft:gravel", ByteTag(1), slot = IntTag(0)))
                ),
                "Blacklist" to ByteTag(1)
            )
        )
        val root = structure(
            blocks = list(
                block(
                    "example:sorter",
                    "Filter" to nestedFilter,
                    "Held" to stack("minecraft:air", ByteTag(1)),
                    "Inventory" to list(
                        stack("minecraft:apple", ByteTag(3), slot = ByteTag(0)),
                        stack("minecraft:air", ByteTag(0), slot = ByteTag(1)),
                        stack(
                            "minecraft:diamond_sword",
                            ByteTag(1),
                            slot = ByteTag(2),
                            tag = compound(
                                "Damage" to IntTag(7),
                                "Enchantments" to list(compound("id" to StringTag("minecraft:sharpness"), "lvl" to ShortTag(2))),
                                "display" to compound("Name" to StringTag("""{"text":"Edge"}""")),
                                "Owner" to StringTag("someone")
                            )
                        )
                    )
                ),
                block(
                    "minecraft:chest",
                    "Items" to list(
                        stack(
                            "minecraft:leather_helmet",
                            ByteTag(1),
                            slot = ByteTag(0),
                            tag = compound("Trim" to compound("pattern" to StringTag("minecraft:coast")))
                        ),
                        stack("example:filter", ByteTag(1), slot = ByteTag(1), tag = nestedFilter.copyTag())
                    )
                )
            )
        )

        val counts = LegacyStructureNbtMigration.migrateDocument(NbtDocument("", root), "test")

        val sorter = blockNbt(root, 0)
        val filter = sorter.compound("Filter")
        assertEquals(setOf("components", "count", "id"), filter.entries.keys)
        assertEquals(IntTag(1), filter.entries["count"])
        val filterData = filter.compound("components").compound("minecraft:custom_data")
        val nested = filterData.compound("Items").list("Items").values.single() as CompoundTag
        assertEquals(linkedMapOf<String, NbtTag>("Slot" to IntTag(0), "count" to IntTag(1), "id" to StringTag("minecraft:gravel")), nested.entries)
        assertEquals(ByteTag(1), filterData.entries["Blacklist"])

        assertTrue(sorter.compound("Held").entries.isEmpty(), "an air stack becomes the empty compound")
        val inventory = sorter.list("Inventory").values.map { it as CompoundTag }
        assertEquals(2, inventory.size, "an empty slotted stack leaves the slot list")
        assertEquals(IntTag(3), inventory[0].entries["count"])
        val sword = inventory[1].compound("components")
        assertEquals(IntTag(7), sword.entries["minecraft:damage"])
        assertEquals(IntTag(2), sword.compound("minecraft:enchantments").compound("levels").entries["minecraft:sharpness"])
        assertEquals(StringTag("""{"text":"Edge"}"""), sword.entries["minecraft:custom_name"])
        assertEquals(StringTag("someone"), sword.compound("minecraft:custom_data").entries["Owner"])

        val chestItems = blockNbt(root, 1).list("Items").values.map { it as CompoundTag }
        assertTrue("Count" in chestItems[0].entries && "tag" in chestItems[0].entries, "the fixer upgrades vanilla container stacks")
        assertTrue("Count" in chestItems[1].entries, "the outer stack stays for the fixer")
        val chestNested = chestItems[1].compound("tag").compound("Items").list("Items").values.single() as CompoundTag
        assertEquals(IntTag(1), chestNested.entries["count"], "mod data inside a stack's tag is migrated here")

        assertEquals(5, counts.items)
        assertEquals(2, counts.emptyItems)
    }

    @Test
    fun `legacy fluid fields become both the bare stack and the tank form`() {
        val root = structure(
            blocks = list(
                block(
                    "example:tank",
                    "TankContent" to compound(
                        "FluidName" to StringTag("example:potion"),
                        "Amount" to IntTag(750),
                        "Tag" to compound("Potion" to StringTag("minecraft:fire_resistance"))
                    ),
                    "Drain" to compound("FluidName" to StringTag("minecraft:empty"), "Amount" to IntTag(0)),
                    "Pipe" to compound(
                        "Pulling" to ByteTag(1),
                        "FluidName" to StringTag("minecraft:water"),
                        "Amount" to IntTag(100)
                    )
                ),
                block("example:flask", "FluidName" to StringTag("minecraft:lava"), "Amount" to IntTag(5))
            )
        )

        val counts = LegacyStructureNbtMigration.migrateDocument(NbtDocument("", root), "test")

        val tank = blockNbt(root, 0)
        val content = tank.compound("TankContent")
        val customData = compound(
            "minecraft:custom_data" to compound("Potion" to StringTag("minecraft:fire_resistance"))
        )
        val expectedFluid = linkedMapOf<String, NbtTag>(
            "amount" to IntTag(750),
            "id" to StringTag("example:potion"),
            "components" to customData
        )
        assertEquals(expectedFluid, content.compound("Fluid").entries)
        assertEquals(IntTag(750), content.entries["amount"])
        assertEquals(StringTag("example:potion"), content.entries["id"])
        assertTrue(tank.compound("Drain").entries.isEmpty())
        val pipe = tank.compound("Pipe")
        assertEquals(setOf("Pulling", "Fluid", "amount", "id"), pipe.entries.keys)

        val flask = blockNbt(root, 1)
        assertEquals(StringTag("example:flask"), flask.entries["id"], "a block entity id is never overwritten")
        assertEquals(IntTag(5), flask.compound("Fluid").entries["amount"])
        assertNull(flask.entries["amount"])
        assertEquals(4, counts.fluids)
    }

    @Test
    fun `same meaning attributes are renamed, empty entity wrappers dropped and tag references moved`() {
        val zombie = compound(
            "id" to StringTag("minecraft:zombie"),
            "Attributes" to list(
                compound("Name" to StringTag("forge:entity_gravity"), "Base" to DoubleTag(0.08.toRawBits())),
                compound("Name" to StringTag("forge:step_height_addition"), "Base" to DoubleTag(0.0.toRawBits()))
            )
        )
        val root = structure(
            blocks = list(
                block(
                    "example:attribute_filter",
                    "Matched" to list(compound("tag" to StringTag("forge:storage_blocks"), "Inverted" to ByteTag(0)))
                )
            ),
            entities = list(
                compound("nbt" to compound(), "blockPos" to list(IntTag(0), IntTag(0), IntTag(0))),
                compound(
                    "nbt" to compound("id" to StringTag("example:seat"), "Passengers" to list(zombie)),
                    "blockPos" to list(IntTag(1), IntTag(0), IntTag(0))
                )
            )
        )

        val counts = LegacyStructureNbtMigration.migrateDocument(NbtDocument("", root), "test")

        val entities = root.list("entities").values
        assertEquals(1, entities.size)
        val attributes = zombie.list("Attributes").values.map { (it as CompoundTag).entries["Name"] }
        assertEquals(listOf(StringTag("minecraft:generic.gravity"), StringTag("forge:step_height_addition")), attributes)
        val matched = blockNbt(root, 0).list("Matched").values.single() as CompoundTag
        assertEquals(StringTag("c:storage_blocks"), matched.entries["tag"])
        assertEquals(1, counts.emptyEntities)
        assertEquals(1, counts.attributes)
        assertEquals(1, counts.tagReferences)
    }

    @Test
    fun `componentized fields the migration does not implement reject the plan only where it owns the stack`() {
        val trimmed = { stack("minecraft:iron_helmet", ByteTag(1), tag = compound("Trim" to compound())) }
        val vanilla = structure(blocks = list(block("minecraft:barrel", "Items" to list(trimmed()))))
        assertEquals(0, LegacyStructureNbtMigration.migrateDocument(NbtDocument("", vanilla), "test").total)

        val dir = tempDir.resolve("data/example/structure").createDirectories()
        dir.resolve("modded.nbt").writeBytes(encode(structure(blocks = list(block("example:rack", "Item" to trimmed())))))
        val plan = LegacyStructureNbtMigration.plan(listOf(tempDir))
        assertFalse(plan.accepted)
        assertTrue(plan.blockers.single().contains("'Trim' needs the vanilla ITEM_STACK fix"), plan.blockers.toString())
    }

    @Test
    fun `plan applies once, keeps unrelated bytes and is idempotent`() {
        val nan = FloatTag(0x7fa12345)
        val dir = tempDir.resolve("data/example/structures/gametest").createDirectories()
        val file = dir.resolve("case.nbt")
        file.writeBytes(
            encode(
                structure(
                    blocks = list(block("example:depot", "Held" to stack("minecraft:stone", ByteTag(2)))),
                    extra = arrayOf("Sentinel" to nan)
                )
            )
        )
        tempDir.resolve("assets/example/ponder").createDirectories().resolve("scene.nbt")
            .writeBytes(encode(structure(blocks = list(block("example:depot", "Held" to stack("minecraft:stone", ByteTag(2)))))))

        val plan = LegacyStructureNbtMigration.plan(listOf(tempDir))
        assertTrue(plan.accepted, plan.blockers.toString())
        assertEquals(listOf(file.toAbsolutePath().normalize()), plan.files.map { it.path })
        LegacyStructureNbtMigration.apply(plan)

        val migrated = TypedNbt.decode(file.readBytes())
        assertEquals(Compression.GZIP, migrated.compression)
        assertEquals(nan, migrated.document.root.entries["Sentinel"])
        val held = blockNbt(migrated.document.root, 0).compound("Held")
        assertEquals(IntTag(2), held.entries["count"])

        val again = LegacyStructureNbtMigration.plan(listOf(tempDir))
        assertTrue(again.files.isEmpty())
        assertContentEquals(file.readBytes(), Files.readAllBytes(file))
    }

    private fun structure(blocks: ListTag, entities: ListTag = list(), extra: Array<Pair<String, NbtTag>> = emptyArray()): CompoundTag =
        compound("DataVersion" to IntTag(3465), *extra, "blocks" to blocks, "entities" to entities)

    private fun block(id: String, vararg entries: Pair<String, NbtTag>): CompoundTag =
        compound(
            "pos" to list(IntTag(0), IntTag(0), IntTag(0)),
            "state" to IntTag(0),
            "nbt" to compound("id" to StringTag(id), *entries)
        )

    private fun stack(id: String, count: NbtTag, slot: NbtTag? = null, tag: CompoundTag? = null): CompoundTag {
        val entries = linkedMapOf<String, NbtTag>()
        if (slot != null) entries["Slot"] = slot
        entries["id"] = StringTag(id)
        entries["Count"] = count
        if (tag != null) entries["tag"] = tag
        return CompoundTag(entries)
    }

    private fun CompoundTag.copyTag(): CompoundTag {
        val bytes = encode(compound("tag" to compound("v" to this)))
        return TypedNbt.decode(bytes).document.root.compound("tag").compound("v").compound("tag")
    }

    private fun blockNbt(root: CompoundTag, index: Int): CompoundTag =
        (root.list("blocks").values[index] as CompoundTag).compound("nbt")

    private fun CompoundTag.compound(key: String): CompoundTag = entries[key] as CompoundTag
    private fun CompoundTag.list(key: String): ListTag = entries[key] as ListTag

    private fun compound(vararg entries: Pair<String, NbtTag>): CompoundTag = CompoundTag(linkedMapOf(*entries))

    private fun list(vararg values: NbtTag): ListTag =
        ListTag(values.firstOrNull()?.typeId ?: TypedNbt.TAG_END, values.toMutableList())

    private fun encode(root: CompoundTag): ByteArray =
        TypedNbt.encode(TypedNbtFile(NbtDocument("", root), Compression.GZIP))
}
