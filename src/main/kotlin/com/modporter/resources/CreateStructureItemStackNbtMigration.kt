package com.modporter.resources

import com.modporter.resources.CreateStructureFluidNbtMigration.ByteTag
import com.modporter.resources.CreateStructureFluidNbtMigration.CompoundTag
import com.modporter.resources.CreateStructureFluidNbtMigration.IntTag
import com.modporter.resources.CreateStructureFluidNbtMigration.ListTag
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtDocument
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtTag
import com.modporter.resources.CreateStructureFluidNbtMigration.ShortTag
import com.modporter.resources.CreateStructureFluidNbtMigration.StringTag
import java.util.LinkedHashMap

/**
 * Source-bound ItemStack componentization for the active Create structure
 * fixtures.
 *
 * Minecraft's 1.21.1 ITEM_STACK data fix is the semantic oracle:
 * - Count becomes the integer `count` field;
 * - vanilla tag fields become their data components;
 * - residual Create-owned tag data becomes `minecraft:custom_data`;
 * - Slot and unrelated admitted root fields survive.
 *
 * StructureTemplateManager invokes the whole STRUCTURE data fix. The active
 * converted fixtures still expose legacy stacks at these exact consumer paths,
 * so this migration applies the independently verified ITEM_STACK result
 * without changing the structure DataVersion. The path inventory is closed
 * over the 63 Create fixtures.
 */
internal object CreateStructureItemStackNbtMigration {
    const val RULE_ID = "res-create-structure-item-stack-components-121"

    private const val TAG_END = 0
    private const val TAG_COMPOUND = 10
    private val RESOURCE_ID = Regex("""[a-z0-9_.-]+:[a-z0-9_./-]+""")
    private val DAMAGE_ITEMS = setOf(
        "create:netherite_diving_boots",
        "create:netherite_diving_helmet",
        "minecraft:diamond_sword",
        "minecraft:iron_helmet",
        "minecraft:netherite_leggings",
        "minecraft:netherite_sword"
    )

    private val ADMITTED_PATHS: Map<String, Set<String>> = mapOf(
        "create:basin" to setOf("Filter"),
        "create:belt" to setOf("Inventory.Items[].Item"),
        "create:brass_tunnel" to setOf(
            "Filters[].Filter",
            "Filters[].Filter.tag.Items.Items[]"
        ),
        "create:chute" to setOf("Item"),
        "create:content_observer" to setOf("Filter"),
        "create:contraption_controls" to setOf("Filter"),
        "create:copycat" to setOf("Item"),
        "create:creative_crate" to setOf("Filter"),
        "create:deployer" to setOf(
            "Filter",
            "HeldItem",
            "Inventory[]"
        ),
        "create:depot" to setOf("HeldItem.Item"),
        "create:item_vault" to setOf("Inventory.Items[]"),
        "create:mechanical_roller" to setOf("Filter"),
        "create:smart_chute" to setOf(
            "Filter",
            "Filter.tag.Items.Items[]"
        ),
        "minecraft:armor_stand" to setOf("ArmorItems[]"),
        "minecraft:barrel" to setOf("Items[]"),
        "minecraft:chest" to setOf("Items[]"),
        "minecraft:dispenser" to setOf("Items[]")
    )

    internal data class ByteMigration(
        val bytes: ByteArray,
        val migratedItemCount: Int
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
        var migrated = 0
        val blocks = document.root.entries["blocks"]
        if (blocks != null) {
            val list = blocks as? ListTag
                ?: reject("$context: root blocks must be a list")
            requireCompoundList(list, "$context root blocks")
            list.values.forEachIndexed { index, value ->
                val block = value as? CompoundTag
                    ?: reject("$context: blocks[$index] is not a compound")
                val blockEntity = block.entries["nbt"] as? CompoundTag
                    ?: return@forEachIndexed
                val owner = (blockEntity.entries["id"] as? StringTag)?.value
                    ?: return@forEachIndexed
                migrated += migrateOwner(
                    owner = owner,
                    root = blockEntity,
                    context = "$context blocks[$index] $owner"
                )
            }
        }

        val entities = document.root.entries["entities"]
        if (entities != null) {
            val list = entities as? ListTag
                ?: reject("$context: root entities must be a list")
            requireCompoundList(list, "$context root entities")
            list.values.forEachIndexed { index, value ->
                val wrapper = value as? CompoundTag
                    ?: reject("$context: entities[$index] is not a compound")
                val entity = wrapper.entries["nbt"] as? CompoundTag
                    ?: return@forEachIndexed
                val owner = (entity.entries["id"] as? StringTag)?.value
                    ?: return@forEachIndexed
                migrated += migrateOwner(
                    owner = owner,
                    root = entity,
                    context = "$context entities[$index] $owner"
                )
            }
        }
        return migrated
    }

    private fun requireCompoundList(list: ListTag, context: String) {
        if (list.values.isEmpty() && list.elementType == TAG_END) return
        if (list.elementType != TAG_COMPOUND) {
            reject("$context is not a compound list")
        }
    }

    private fun migrateOwner(
        owner: String,
        root: CompoundTag,
        context: String
    ): Int = migrateCompound(
        owner = owner,
        displayPath = "",
        familyPath = "",
        compound = root,
        context = context
    )

    /**
     * Walk children first. A Create filter's legacy tag can itself contain an
     * ItemStackHandler payload; those nested stacks must be componentized
     * before the outer tag moves under minecraft:custom_data.
     */
    private fun migrateCompound(
        owner: String,
        displayPath: String,
        familyPath: String,
        compound: CompoundTag,
        context: String
    ): Int {
        var migrated = 0
        compound.entries.toList().forEach { (key, child) ->
            val childDisplay = joinPath(displayPath, key)
            val childFamily = joinPath(familyPath, key)
            migrated += when (child) {
                is CompoundTag -> migrateCompound(
                    owner,
                    childDisplay,
                    childFamily,
                    child,
                    context
                )
                is ListTag -> migrateList(
                    owner,
                    childDisplay,
                    childFamily,
                    child,
                    context
                )
                else -> 0
            }
        }

        if (!isLegacyItemStack(compound)) return migrated
        if (familyPath !in ADMITTED_PATHS[owner].orEmpty()) {
            reject(
                "$context $displayPath: legacy ItemStack is outside the " +
                    "source-proven owner/path inventory"
            )
        }
        val replacement = componentizeStack(
            compound,
            "$context $displayPath"
        )
        compound.entries.clear()
        compound.entries.putAll(replacement.entries)
        return migrated + 1
    }

    private fun migrateList(
        owner: String,
        displayPath: String,
        familyPath: String,
        list: ListTag,
        context: String
    ): Int {
        var migrated = 0
        list.values.forEachIndexed { index, child ->
            val childDisplay = "$displayPath[$index]"
            val childFamily = "$familyPath[]"
            migrated += when (child) {
                is CompoundTag -> migrateCompound(
                    owner,
                    childDisplay,
                    childFamily,
                    child,
                    context
                )
                is ListTag -> migrateList(
                    owner,
                    childDisplay,
                    childFamily,
                    child,
                    context
                )
                else -> 0
            }
        }
        return migrated
    }

    private fun joinPath(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent.$child"

    private fun isLegacyItemStack(compound: CompoundTag): Boolean =
        compound.entries["id"] is StringTag &&
            compound.entries.containsKey("Count")

    private fun componentizeStack(
        legacy: CompoundTag,
        context: String
    ): CompoundTag {
        val allowedRootKeys = setOf("id", "Count", "Slot", "tag")
        if (!allowedRootKeys.containsAll(legacy.entries.keys)) {
            reject("$context: unsupported legacy ItemStack root keys ${legacy.entries.keys}")
        }
        val id = (legacy.entries["id"] as? StringTag)?.value
            ?: reject("$context: ItemStack id must be TAG_String")
        requireResourceId(id, "$context ItemStack id")
        if (id == "minecraft:air") {
            reject("$context: legacy air stack escaped the empty-stack migration")
        }
        val count = when (val countTag = legacy.entries["Count"]) {
            is ByteTag -> countTag.value.toInt()
            is IntTag -> countTag.value
            else -> reject("$context Count must be source-proven TAG_Byte or TAG_Int")
        }
        if (count !in 1..127) {
            reject("$context: legacy Count must be between 1 and 127, got $count")
        }
        legacy.entries["Slot"]?.let { validateSlot(it, "$context Slot") }
        val legacyTag = legacy.entries["tag"]?.let {
            it as? CompoundTag
                ?: reject("$context: legacy tag must be TAG_Compound")
        }
        val components = legacyTag?.let {
            componentizeTag(id, it, "$context tag")
        }

        val entries = linkedMapOf<String, NbtTag>()
        legacy.entries.forEach { (key, value) ->
            if (key !in setOf("id", "Count", "tag")) {
                entries[key] = value
            }
        }
        if (components != null && components.entries.isNotEmpty()) {
            entries["components"] = components
        }
        entries["count"] = IntTag(count)
        entries["id"] = StringTag(id)
        return CompoundTag(entries)
    }

    private fun componentizeTag(
        itemId: String,
        legacyTag: CompoundTag,
        context: String
    ): CompoundTag {
        val components = linkedMapOf<String, NbtTag>()
        val customData = LinkedHashMap(legacyTag.entries)

        customData.remove("Damage")?.let { damageTag ->
            if (itemId !in DAMAGE_ITEMS) {
                reject("$context Damage is not source-proven for $itemId")
            }
            val damage = (damageTag as? IntTag)?.value
                ?: reject("$context Damage must be TAG_Int")
            if (damage < 0) reject("$context Damage must be nonnegative")
            if (damage > 0) {
                components["minecraft:damage"] = IntTag(damage)
            }
        }

        customData.remove("StoredEnchantments")?.let { enchantments ->
            if (itemId != "minecraft:enchanted_book") {
                reject("$context StoredEnchantments is not source-proven for $itemId")
            }
            components["minecraft:stored_enchantments"] =
                componentizeStoredEnchantments(enchantments, "$context StoredEnchantments")
        }

        customData.remove("display")?.let { display ->
            if (itemId != "minecraft:name_tag") {
                reject("$context display is not source-proven for $itemId")
            }
            components["minecraft:custom_name"] =
                componentizeDisplayName(display, "$context display")
        }

        customData.remove("Potion")?.let { potionTag ->
            if (itemId != "minecraft:potion") {
                reject("$context Potion is not source-proven for $itemId")
            }
            val potion = (potionTag as? StringTag)?.value
                ?: reject("$context Potion must be TAG_String")
            requireResourceId(potion, "$context Potion")
            components["minecraft:potion_contents"] = CompoundTag(
                linkedMapOf("potion" to StringTag(potion))
            )
        }

        validateAndRepairCustomData(itemId, customData, context)
        if (customData.isNotEmpty()) {
            components["minecraft:custom_data"] = CompoundTag(customData)
        }
        return CompoundTag(components)
    }

    private fun componentizeStoredEnchantments(
        tag: NbtTag,
        context: String
    ): CompoundTag {
        val list = tag as? ListTag
            ?: reject("$context must be a list")
        requireCompoundList(list, context)
        if (list.values.isEmpty()) {
            reject("$context must contain at least one enchantment")
        }
        val levels = linkedMapOf<String, NbtTag>()
        list.values.forEachIndexed { index, value ->
            val enchantment = value as? CompoundTag
                ?: reject("$context[$index] must be a compound")
            if (enchantment.entries.keys != setOf("id", "lvl")) {
                reject("$context[$index] has unsupported keys ${enchantment.entries.keys}")
            }
            val id = (enchantment.entries["id"] as? StringTag)?.value
                ?: reject("$context[$index].id must be TAG_String")
            requireResourceId(id, "$context[$index].id")
            val level = numericInt(enchantment.entries["lvl"], "$context[$index].lvl")
            if (level <= 0) reject("$context[$index].lvl must be positive")
            if (levels.put(id, IntTag(level)) != null) {
                reject("$context contains duplicate enchantment '$id'")
            }
        }
        return CompoundTag(
            linkedMapOf("levels" to CompoundTag(levels))
        )
    }

    private fun componentizeDisplayName(
        tag: NbtTag,
        context: String
    ): StringTag {
        val display = tag as? CompoundTag
            ?: reject("$context must be a compound")
        if (display.entries.keys != setOf("Name")) {
            reject("$context has unsupported keys ${display.entries.keys}")
        }
        val name = (display.entries["Name"] as? StringTag)?.value
            ?: reject("$context Name must be TAG_String")
        if (name.isBlank()) reject("$context Name must not be blank")
        return StringTag(name)
    }

    private fun validateAndRepairCustomData(
        itemId: String,
        customData: LinkedHashMap<String, NbtTag>,
        context: String
    ) {
        if (customData.isEmpty()) return
        when (itemId) {
            "create:schedule" -> {
                requireExactKeys(customData, setOf("Schedule"), itemId, context)
                if (customData["Schedule"] !is CompoundTag) {
                    reject("$context Schedule must be a compound")
                }
            }
            "create:netherite_backtank" -> {
                requireExactKeys(customData, setOf("Air"), itemId, context)
                if (numericInt(customData["Air"], "$context Air") < 0) {
                    reject("$context Air must be nonnegative")
                }
            }
            "create:filter" -> {
                requireExactKeys(
                    customData,
                    setOf("Items", "RespectNBT", "Blacklist"),
                    itemId,
                    context
                )
                validateItemStackHandler(customData["Items"], "$context Items")
                validateBooleanByte(customData["RespectNBT"], "$context RespectNBT")
                validateBooleanByte(customData["Blacklist"], "$context Blacklist")
            }
            "create:attribute_filter" -> {
                val allowed = setOf("WhitelistMode", "Items", "MatchedAttributes")
                if (!allowed.containsAll(customData.keys) ||
                    !customData.keys.containsAll(setOf("WhitelistMode", "MatchedAttributes"))
                ) {
                    reject("$context: unsupported $itemId custom-data keys ${customData.keys}")
                }
                migrateAttributeFilterTagAlias(customData, context)
            }
            else -> reject(
                "$context: residual legacy tag keys ${customData.keys} have no " +
                    "source-bound component mapping for $itemId"
            )
        }
    }

    private fun requireExactKeys(
        values: Map<String, NbtTag>,
        expected: Set<String>,
        itemId: String,
        context: String
    ) {
        if (values.keys != expected) {
            reject("$context: unsupported $itemId custom-data keys ${values.keys}")
        }
    }

    private fun migrateAttributeFilterTagAlias(
        customData: LinkedHashMap<String, NbtTag>,
        context: String
    ) {
        val whitelistMode = numericInt(customData["WhitelistMode"], "$context WhitelistMode")
        if (whitelistMode != 0) {
            reject("$context WhitelistMode must match the source-proven value 0")
        }
        customData["Items"]?.let {
            validateItemStackHandler(it, "$context Items")
        }
        val matched = customData["MatchedAttributes"] as? ListTag
            ?: reject("$context MatchedAttributes must be a list")
        requireCompoundList(matched, "$context MatchedAttributes")
        matched.values.forEachIndexed { index, value ->
            val attribute = value as? CompoundTag
                ?: reject("$context MatchedAttributes[$index] must be a compound")
            val attributeId = (attribute.entries["attributeId"] as? StringTag)?.value
                ?: reject("$context MatchedAttributes[$index].attributeId must be TAG_String")
            requireResourceId(attributeId, "$context MatchedAttributes[$index].attributeId")
            validateBooleanByte(
                attribute.entries["Inverted"],
                "$context MatchedAttributes[$index].Inverted"
            )
            when (attributeId) {
                "create:in_tag" -> {
                    if (attribute.entries.keys !=
                        setOf("attributeId", "tag", "Inverted")
                    ) {
                        reject(
                            "$context MatchedAttributes[$index] has unsupported " +
                                "in_tag keys ${attribute.entries.keys}"
                        )
                    }
                    val tag = attribute.entries["tag"] as? StringTag
                        ?: reject(
                            "$context MatchedAttributes[$index].tag must be TAG_String"
                        )
                    requireResourceId(tag.value, "$context MatchedAttributes[$index].tag")
                    if (tag.value == "forge:storage_blocks") {
                        attribute.entries["tag"] = StringTag("c:storage_blocks")
                    }
                }
                "create:has_enchant" -> {
                    if (attribute.entries.keys !=
                        setOf("attributeId", "enchantId", "Inverted")
                    ) {
                        reject(
                            "$context MatchedAttributes[$index] has unsupported " +
                                "has_enchant keys ${attribute.entries.keys}"
                        )
                    }
                    val enchantment = attribute.entries["enchantId"] as? StringTag
                        ?: reject(
                            "$context MatchedAttributes[$index].enchantId must be TAG_String"
                        )
                    requireResourceId(
                        enchantment.value,
                        "$context MatchedAttributes[$index].enchantId"
                    )
                }
                else -> {
                    if (attribute.entries.keys != setOf("attributeId", "Inverted")) {
                        reject(
                            "$context MatchedAttributes[$index] has unsupported " +
                                "keys ${attribute.entries.keys}"
                        )
                    }
                }
            }
        }
    }

    private fun validateItemStackHandler(tag: NbtTag?, context: String) {
        val handler = tag as? CompoundTag
            ?: reject("$context must be a compound")
        if (handler.entries.keys != setOf("Size", "Items")) {
            reject("$context has unsupported keys ${handler.entries.keys}")
        }
        val size = numericInt(handler.entries["Size"], "$context Size")
        if (size < 0) reject("$context Size must be nonnegative")
        val items = handler.entries["Items"] as? ListTag
            ?: reject("$context Items must be a list")
        requireCompoundList(items, "$context Items")
    }

    private fun validateBooleanByte(tag: NbtTag?, context: String) {
        val value = (tag as? ByteTag)?.value?.toInt()
            ?: reject("$context must be TAG_Byte")
        if (value !in 0..1) reject("$context must be 0 or 1")
    }

    private fun validateSlot(tag: NbtTag, context: String) {
        val value = when (tag) {
            is ByteTag -> tag.value.toInt()
            is IntTag -> tag.value
            else -> reject("$context must be TAG_Byte or TAG_Int")
        }
        if (value !in 0..127) reject("$context must be between 0 and 127")
    }

    private fun numericInt(tag: NbtTag?, context: String): Int = when (tag) {
        is ByteTag -> tag.value.toInt()
        is ShortTag -> tag.value.toInt()
        is IntTag -> tag.value
        else -> reject("$context must be TAG_Byte, TAG_Short, or TAG_Int")
    }

    private fun requireResourceId(value: String, context: String) {
        if (!RESOURCE_ID.matches(value)) {
            reject("$context is not a canonical resource id: '$value'")
        }
    }

    private fun reject(message: String): Nothing =
        throw IllegalArgumentException(message)
}
