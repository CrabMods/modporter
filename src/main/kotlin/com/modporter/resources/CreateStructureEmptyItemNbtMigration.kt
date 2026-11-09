package com.modporter.resources

import com.modporter.resources.CreateStructureFluidNbtMigration.ByteTag
import com.modporter.resources.CreateStructureFluidNbtMigration.CompoundTag
import com.modporter.resources.CreateStructureFluidNbtMigration.IntTag
import com.modporter.resources.CreateStructureFluidNbtMigration.ListTag
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtDocument
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtTag
import com.modporter.resources.CreateStructureFluidNbtMigration.StringTag
import java.util.LinkedHashMap

/**
 * Consumer-bound migration for legacy empty ItemStacks in active Create
 * structure NBT. Binary NBT decoding/encoding is delegated to the shared
 * CreateStructureFluidNbtMigration typed codec.
 */
internal object CreateStructureEmptyItemNbtMigration {
    const val RULE_ID = "res-create-structure-empty-item-stack-121"

    private const val TAG_COMPOUND = 10

    private sealed interface ItemPath {
        val display: String
    }

    private data class DirectItemPath(
        val key: String
    ) : ItemPath {
        override val display: String = key
    }

    private data class IndexedItemPath(
        val listKey: String,
        val index: Int,
        val key: String
    ) : ItemPath {
        override val display: String = "$listKey[$index].$key"
    }

    /*
     * Derived from the 63-file Create 6.0.8 fixture inventory. This is an
     * owner/path whitelist, not a recursive search for compounds named Item or
     * Filter.
     */
    private val ADMITTED_PATHS: Map<String, List<ItemPath>> = mapOf(
        "create:basin" to listOf(DirectItemPath("Filter")),
        "create:brass_tunnel" to listOf(
            DirectItemPath("Filter"),
            IndexedItemPath("Filters", 0, "Filter"),
            IndexedItemPath("Filters", 1, "Filter"),
            IndexedItemPath("Filters", 2, "Filter"),
            DirectItemPath("StackToDistribute")
        ),
        "create:chute" to listOf(DirectItemPath("Item")),
        "create:content_observer" to listOf(DirectItemPath("Filter")),
        "create:contraption_controls" to listOf(DirectItemPath("Filter")),
        "create:copycat" to listOf(DirectItemPath("Item")),
        "create:creative_crate" to listOf(DirectItemPath("Filter")),
        "create:deployer" to listOf(
            DirectItemPath("Filter"),
            DirectItemPath("HeldItem")
        ),
        "create:funnel" to listOf(DirectItemPath("Filter")),
        "create:mechanical_arm" to listOf(DirectItemPath("HeldItem")),
        "create:smart_chute" to listOf(
            DirectItemPath("Filter"),
            DirectItemPath("Item")
        ),
        "create:stockpile_switch" to listOf(DirectItemPath("Filter")),
        "create:track_observer" to listOf(DirectItemPath("Filter"))
    )

    internal data class ByteMigration(
        val bytes: ByteArray,
        val migratedAirCount: Int
    )

    internal fun migrateBytes(
        input: ByteArray,
        context: String = "<memory>"
    ): ByteMigration {
        val typedNbt = CreateStructureFluidNbtMigration.decodeTypedNbt(input)
        val migrated = migrateDocument(typedNbt.document, context)
        if (migrated == 0) return ByteMigration(input, 0)
        return ByteMigration(
            CreateStructureFluidNbtMigration.encodeTypedNbt(typedNbt),
            migrated
        )
    }

    internal fun migrateDocument(document: NbtDocument, context: String): Int {
        val blocks = document.root.entries["blocks"] as? ListTag ?: return 0
        if (blocks.elementType != TAG_COMPOUND) {
            reject("$context: root blocks list is not a compound list")
        }

        var migrated = 0
        blocks.values.forEachIndexed { index, value ->
            val block = value as? CompoundTag
                ?: reject("$context: blocks[$index] is not a compound")
            val blockEntity = block.entries["nbt"] as? CompoundTag
                ?: return@forEachIndexed
            val blockEntityId = (blockEntity.entries["id"] as? StringTag)?.value
                ?: return@forEachIndexed
            val admitted = ADMITTED_PATHS[blockEntityId] ?: return@forEachIndexed
            val blockContext = "$context blocks[$index] $blockEntityId"
            for (path in admitted) {
                migrated += when (path) {
                    is DirectItemPath ->
                        migrateDirect(blockEntity.entries, path.key, "$blockContext ${path.display}")
                    is IndexedItemPath ->
                        migrateIndexed(blockEntity, path, "$blockContext ${path.display}")
                }
            }
        }
        return migrated
    }

    private fun migrateIndexed(
        blockEntity: CompoundTag,
        path: IndexedItemPath,
        context: String
    ): Int {
        val listTag = blockEntity.entries[path.listKey] ?: return 0
        val list = listTag as? ListTag
            ?: reject("$context: ${path.listKey} must be a list")
        if (list.elementType != TAG_COMPOUND) {
            reject("$context: ${path.listKey} must be a compound list")
        }
        if (path.index >= list.values.size) return 0
        val wrapper = list.values[path.index] as? CompoundTag
            ?: reject("$context: indexed wrapper is not a compound")
        return migrateDirect(wrapper.entries, path.key, context)
    }

    private fun migrateDirect(
        owner: LinkedHashMap<String, NbtTag>,
        key: String,
        context: String
    ): Int {
        val existing = owner[key] ?: return 0
        val stack = existing as? CompoundTag
            ?: reject("$context: admitted ItemStack field must be a compound")
        val id = stack.entries["id"] ?: return 0
        if (id !is StringTag || id.value != "minecraft:air") return 0

        val keys = stack.entries.keys
        if (keys != setOf("id", "Count")) {
            reject(
                "$context: unsupported legacy air ItemStack keys $keys; " +
                    "expected exactly [id, Count]"
            )
        }
        val countTag = stack.entries.getValue("Count")
        val count = when (countTag) {
            is ByteTag -> countTag.value.toInt()
            is IntTag -> countTag.value
            else -> reject(
                "$context: legacy air Count must be TAG_Byte or TAG_Int, " +
                    "got type ${countTag.typeId}"
            )
        }
        if (count !in 0..1) {
            reject("$context: legacy air Count must be 0 or 1, got $count")
        }

        owner[key] = CompoundTag()
        return 1
    }

    private fun reject(message: String): Nothing =
        throw IllegalArgumentException(message)
}
