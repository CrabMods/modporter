package com.modporter.resources

import com.modporter.resources.CreateStructureFluidNbtMigration.ByteTag
import com.modporter.resources.CreateStructureFluidNbtMigration.CompoundTag
import com.modporter.resources.CreateStructureFluidNbtMigration.Compression
import com.modporter.resources.CreateStructureFluidNbtMigration.FloatTag
import com.modporter.resources.CreateStructureFluidNbtMigration.IntTag
import com.modporter.resources.CreateStructureFluidNbtMigration.ListTag
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtDocument
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtTag
import com.modporter.resources.CreateStructureFluidNbtMigration.ShortTag
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CreateStructureItemStackNbtMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `source proven stacks match the verified item DFU tree and migrate postorder`() {
        val attributeItems = itemHandler(
            stack("minecraft:apple", ByteTag(1), ByteTag(0)),
            stack(
                "minecraft:name_tag",
                ByteTag(1),
                ByteTag(1),
                compound("display" to compound("Name" to StringTag("""{"text":"Selected Tags"}""")))
            )
        )
        val attributeFilter = stack(
            "create:attribute_filter",
            ByteTag(1),
            tag = compound(
                "WhitelistMode" to IntTag(0),
                "Items" to attributeItems,
                "MatchedAttributes" to listOfCompounds(
                    compound(
                        "attributeId" to StringTag("create:in_tag"),
                        "tag" to StringTag("forge:storage_blocks"),
                        "Inverted" to ByteTag(0)
                    )
                )
            )
        )
        val root = compound(
            "DataVersion" to IntTag(3465),
            "Sentinel" to FloatTag(0x7fa12345),
            "blocks" to listOfCompounds(
                block(
                    "minecraft:chest",
                    "Items" to listOfCompounds(
                        stack("minecraft:apple", ByteTag(1), ByteTag(0)),
                        // The actual Create fixture has exactly one nonempty
                        // TAG_Int Count/Slot stack at this inventory family.
                        stack("create:golden_sheet", IntTag(16), IntTag(0)),
                        stack(
                            "minecraft:enchanted_book",
                            ByteTag(1),
                            ByteTag(3),
                            compound(
                                "StoredEnchantments" to listOfCompounds(
                                    compound(
                                        "lvl" to ShortTag(1),
                                        "id" to StringTag("minecraft:protection")
                                    )
                                )
                            )
                        ),
                        stack(
                            "minecraft:netherite_sword",
                            ByteTag(1),
                            ByteTag(4),
                            compound("Damage" to IntTag(7))
                        ),
                        stack(
                            "minecraft:iron_helmet",
                            ByteTag(1),
                            ByteTag(5),
                            compound("Damage" to IntTag(0))
                        )
                    )
                ),
                block(
                    "minecraft:barrel",
                    "Items" to listOfCompounds(
                        stack(
                            "create:schedule",
                            ByteTag(1),
                            ByteTag(13),
                            compound("Schedule" to compound("Cyclic" to ByteTag(1)))
                        )
                    )
                ),
                block(
                    "create:smart_chute",
                    "Filter" to stack(
                        "minecraft:potion",
                        ByteTag(1),
                        tag = compound("Potion" to StringTag("minecraft:water"))
                    )
                ),
                block(
                    "create:brass_tunnel",
                    "Filters" to listOfCompounds(
                        compound("Filter" to attributeFilter)
                    )
                )
            ),
            "entities" to listOfCompounds(
                entity(
                    "minecraft:armor_stand",
                    "ArmorItems" to listOfCompounds(
                        stack(
                            "create:netherite_backtank",
                            ByteTag(1),
                            tag = compound("Air" to IntTag(900))
                        )
                    )
                )
            )
        )
        val input = encode(root)

        val first = CreateStructureItemStackNbtMigration.migrateBytes(input, "verified-oracle")
        assertEquals(11, first.migratedItemCount)
        val migratedRoot = decodeRoot(first.bytes)
        assertEquals(IntTag(3465), migratedRoot.entries["DataVersion"])
        assertEquals(FloatTag(0x7fa12345), migratedRoot.entries["Sentinel"])

        val chestItems = ownerBlock(migratedRoot, 0)
            .entries["Items"] as ListTag
        assertModernStack(chestItems.values[0] as CompoundTag, "minecraft:apple", 1, ByteTag(0))
        assertModernStack(
            chestItems.values[1] as CompoundTag,
            "create:golden_sheet",
            16,
            IntTag(0)
        )
        val enchanted = chestItems.values[2] as CompoundTag
        val enchantments = components(enchanted)
            .entries["minecraft:stored_enchantments"] as CompoundTag
        val levels = enchantments.entries["levels"] as CompoundTag
        assertEquals(IntTag(1), levels.entries["minecraft:protection"])
        assertEquals(IntTag(7), components(chestItems.values[3] as CompoundTag)
            .entries["minecraft:damage"])
        assertFalse((chestItems.values[4] as CompoundTag).entries.containsKey("components"))

        val schedule = (ownerBlock(migratedRoot, 1).entries["Items"] as ListTag)
            .values.single() as CompoundTag
        assertTrue(
            (components(schedule).entries["minecraft:custom_data"] as CompoundTag)
                .entries["Schedule"] is CompoundTag
        )
        val potion = ownerBlock(migratedRoot, 2).entries["Filter"] as CompoundTag
        val potionContents = components(potion)
            .entries["minecraft:potion_contents"] as CompoundTag
        assertEquals(StringTag("minecraft:water"), potionContents.entries["potion"])

        val filters = ownerBlock(migratedRoot, 3).entries["Filters"] as ListTag
        val outerFilter = (filters.values.single() as CompoundTag)
            .entries["Filter"] as CompoundTag
        val filterData = components(outerFilter)
            .entries["minecraft:custom_data"] as CompoundTag
        val nestedItems = ((filterData.entries["Items"] as CompoundTag)
            .entries["Items"] as ListTag)
        assertModernStack(
            nestedItems.values[0] as CompoundTag,
            "minecraft:apple",
            1,
            ByteTag(0)
        )
        val named = nestedItems.values[1] as CompoundTag
        assertEquals(
            StringTag("""{"text":"Selected Tags"}"""),
            components(named).entries["minecraft:custom_name"]
        )
        val matched = filterData.entries["MatchedAttributes"] as ListTag
        val inTag = matched.values.single() as CompoundTag
        assertEquals(StringTag("c:storage_blocks"), inTag.entries["tag"])

        val armor = ownerEntity(migratedRoot, 0).entries["ArmorItems"] as ListTag
        val backtankData = components(armor.values.single() as CompoundTag)
            .entries["minecraft:custom_data"] as CompoundTag
        assertEquals(IntTag(900), backtankData.entries["Air"])

        val second = CreateStructureItemStackNbtMigration.migrateBytes(
            first.bytes,
            "verified-oracle-second"
        )
        assertEquals(0, second.migratedItemCount)
        assertContentEquals(first.bytes, second.bytes)
    }

    @Test
    fun `unknown paths root keys tag pairings and numeric widths reject`() {
        val outsideInventory = structure(
            block("example:chest", "Items" to listOfCompounds(stack("minecraft:apple")))
        )
        assertFailsWith<IllegalArgumentException> {
            CreateStructureItemStackNbtMigration.migrateBytes(
                encode(outsideInventory),
                "outside"
            )
        }

        val extraRootKey = structure(
            block(
                "minecraft:chest",
                "Items" to listOfCompounds(
                    compound(
                        "id" to StringTag("minecraft:apple"),
                        "Count" to ByteTag(1),
                        "Unknown" to ByteTag(1)
                    )
                )
            )
        )
        assertFailsWith<IllegalArgumentException> {
            CreateStructureItemStackNbtMigration.migrateBytes(
                encode(extraRootKey),
                "extra-root"
            )
        }

        val wrongTagPairing = structure(
            block(
                "minecraft:chest",
                "Items" to listOfCompounds(
                    stack(
                        "minecraft:apple",
                        tag = compound("Potion" to StringTag("minecraft:water"))
                    )
                )
            )
        )
        assertFailsWith<IllegalArgumentException> {
            CreateStructureItemStackNbtMigration.migrateBytes(
                encode(wrongTagPairing),
                "wrong-tag"
            )
        }

        val shortCount = structure(
            block(
                "minecraft:chest",
                "Items" to listOfCompounds(
                    stack("minecraft:apple", ShortTag(1))
                )
            )
        )
        assertFailsWith<IllegalArgumentException> {
            CreateStructureItemStackNbtMigration.migrateBytes(
                encode(shortCount),
                "short-count"
            )
        }
    }

    @Test
    fun `joint plan refuses every write when one stack is unsupported`() {
        val root = tempDir.resolve("resources").also { it.createDirectories() }
        val accepted = root.resolve("data/create/structure/gametest/accepted.nbt")
            .also { it.parent.createDirectories() }
        val rejected = root.resolve("data/create/structure/gametest/rejected.nbt")
        val acceptedBytes = encode(
            structure(
                block(
                    "minecraft:chest",
                    "Items" to listOfCompounds(stack("minecraft:diamond"))
                )
            )
        )
        val rejectedBytes = encode(
            structure(
                block(
                    "minecraft:chest",
                    "Items" to listOfCompounds(
                        compound(
                            "id" to StringTag("minecraft:diamond"),
                            "Count" to ByteTag(1),
                            "opaque" to IntTag(7)
                        )
                    )
                )
            )
        )
        accepted.writeBytes(acceptedBytes)
        rejected.writeBytes(rejectedBytes)

        val plan = CreateStructureNbtMigrationStage.plan(listOf(root))
        assertFalse(plan.accepted)
        assertEquals(1, plan.files.size)
        assertEquals(1, plan.migratedItemCount)
        assertTrue(plan.blockers.single().contains("unsupported legacy ItemStack root keys"))
        assertFailsWith<IllegalArgumentException> {
            CreateStructureNbtMigrationStage.apply(plan)
        }
        assertContentEquals(acceptedBytes, Files.readAllBytes(accepted))
        assertContentEquals(rejectedBytes, Files.readAllBytes(rejected))
    }

    @Test
    fun `empty TAG End entity list is legal`() {
        val root = compound(
            "DataVersion" to IntTag(3465),
            "blocks" to listOfCompounds(
                block(
                    "minecraft:chest",
                    "Items" to listOfCompounds(stack("minecraft:diamond"))
                )
            ),
            "entities" to ListTag(TAG_END, mutableListOf())
        )

        val migration = CreateStructureItemStackNbtMigration.migrateBytes(
            encode(root),
            "empty-entities"
        )
        assertEquals(1, migration.migratedItemCount)
    }

    private fun assertModernStack(
        stack: CompoundTag,
        id: String,
        count: Int,
        slot: NbtTag
    ) {
        assertEquals(StringTag(id), stack.entries["id"])
        assertEquals(IntTag(count), stack.entries["count"])
        assertEquals(slot, stack.entries["Slot"])
        assertFalse(stack.entries.containsKey("Count"))
        assertFalse(stack.entries.containsKey("tag"))
    }

    private fun components(stack: CompoundTag): CompoundTag =
        assertIs(stack.entries["components"])

    private fun structure(vararg blocks: CompoundTag): CompoundTag = compound(
        "DataVersion" to IntTag(3465),
        "blocks" to ListTag(TAG_COMPOUND, blocks.toMutableList()),
        "entities" to ListTag(TAG_END, mutableListOf())
    )

    private fun block(
        owner: String,
        vararg entries: Pair<String, NbtTag>
    ): CompoundTag = compound(
        "pos" to ListTag(
            TAG_INT,
            mutableListOf(IntTag(1), IntTag(2), IntTag(3))
        ),
        "state" to IntTag(0),
        "nbt" to CompoundTag(
            linkedMapOf<String, NbtTag>("id" to StringTag(owner)).apply {
                putAll(entries)
            }
        )
    )

    private fun entity(
        owner: String,
        vararg entries: Pair<String, NbtTag>
    ): CompoundTag = compound(
        "blockPos" to ListTag(
            TAG_INT,
            mutableListOf(IntTag(1), IntTag(2), IntTag(3))
        ),
        "nbt" to CompoundTag(
            linkedMapOf<String, NbtTag>("id" to StringTag(owner)).apply {
                putAll(entries)
            }
        )
    )

    private fun ownerBlock(root: CompoundTag, index: Int): CompoundTag {
        val blocks = root.entries["blocks"] as ListTag
        return (blocks.values[index] as CompoundTag).entries["nbt"] as CompoundTag
    }

    private fun ownerEntity(root: CompoundTag, index: Int): CompoundTag {
        val entities = root.entries["entities"] as ListTag
        return (entities.values[index] as CompoundTag).entries["nbt"] as CompoundTag
    }

    private fun stack(
        id: String,
        count: NbtTag = ByteTag(1),
        slot: NbtTag? = null,
        tag: CompoundTag? = null
    ): CompoundTag = CompoundTag(
        linkedMapOf<String, NbtTag>().apply {
            if (slot != null) put("Slot", slot)
            put("id", StringTag(id))
            put("Count", count)
            if (tag != null) put("tag", tag)
        }
    )

    private fun itemHandler(vararg stacks: CompoundTag): CompoundTag = compound(
        "Size" to IntTag(stacks.size),
        "Items" to ListTag(TAG_COMPOUND, stacks.toMutableList())
    )

    private fun listOfCompounds(vararg values: CompoundTag): ListTag =
        ListTag(TAG_COMPOUND, values.toMutableList())

    private fun compound(vararg entries: Pair<String, NbtTag>): CompoundTag =
        CompoundTag(linkedMapOf(*entries))

    private fun encode(root: CompoundTag): ByteArray =
        CreateStructureFluidNbtMigration.encodeTypedNbt(
            TypedNbtFile(NbtDocument("typed-root", root), Compression.RAW)
        )

    private fun decodeRoot(bytes: ByteArray): CompoundTag =
        CreateStructureFluidNbtMigration.decodeTypedNbt(bytes).document.root

    private companion object {
        const val TAG_END = 0
        const val TAG_INT = 3
        const val TAG_COMPOUND = 10
    }
}
