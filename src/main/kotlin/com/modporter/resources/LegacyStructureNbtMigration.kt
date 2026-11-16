package com.modporter.resources

import com.modporter.resources.TypedNbt.ByteTag
import com.modporter.resources.TypedNbt.CompoundTag
import com.modporter.resources.TypedNbt.IntTag
import com.modporter.resources.TypedNbt.ListTag
import com.modporter.resources.TypedNbt.NbtDocument
import com.modporter.resources.TypedNbt.NbtTag
import com.modporter.resources.TypedNbt.ShortTag
import com.modporter.resources.TypedNbt.StringTag
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Upgrades the Forge 1.20.1 payloads embedded in structure and GameTest NBT to the NeoForge
 * 1.21.1 shapes that the ported readers consume.
 *
 * Structure files keep their 1.20.1 DataVersion, so Minecraft's STRUCTURE data fix still runs
 * at load. That fix only reaches payloads at vanilla schema paths; a stack inside a modded block
 * entity or inside custom data stays legacy and a 1.21 reader drops its count and tag. Every
 * conversion here is therefore keyed on the payload's own legacy shape, never on a mod or block
 * entity id:
 *
 * - ItemStack `{id, Count, tag?, Slot?}` follows the 1.21.1 ITEM_STACK fix
 *   (`ItemStackComponentizationFix`): `Count` becomes the integer `count`; the vanilla tag fields
 *   implemented below become their data components; the remainder becomes `minecraft:custom_data`.
 *   A tag field that the vanilla fix componentizes but this migration does not implement rejects
 *   the plan instead of being guessed. The vanilla fix only rewrites stacks that still carry
 *   `Count`, so pre-converted stacks at vanilla paths are left alone when it runs at load.
 * - Forge FluidStack fields `{FluidName, Amount, Tag?}`. Forge wrote the same fields for a bare
 *   FluidStack and for a FluidTank; NeoForge 1.21 reads a bare stack as `{id, amount,
 *   components?}` and a tank as `{Fluid: {...}}`. The data alone cannot tell the two consumers
 *   apart, so both forms are written; each consumer's codec ignores the other's keys. A legacy
 *   `Tag` becomes `minecraft:custom_data`, matching the code-side FluidStack tag migration.
 * - Entity attribute ids that name the same attribute in 1.21 (`forge:entity_gravity`,
 *   `porting_lib:entity_gravity` -> `minecraft:generic.gravity`, both default 0.08). Ids whose
 *   1.21 counterpart has a different base meaning (step height addition) are left alone.
 * - Root entity wrappers whose `nbt` is empty: they name no entity type, never spawn anything,
 *   and make 1.21 log a skipped-entity warning.
 * - String tag references under the same keys the JSON data migration rewrites (`tag`,
 *   `itemTag`, `blockTag`, `fluidTag`), with the same legacy `forge:`/`neoforge:` -> `c:` rule.
 */
internal object LegacyStructureNbtMigration {
    const val RULE_ID = "res-legacy-structure-nbt-121"

    private val STRUCTURE_DIRECTORIES = setOf("structure", "structures", "gameteststructures")
    private val RESOURCE_ID = Regex("""[a-z0-9_.-]+:[a-z0-9_./-]+""")
    private val LEGACY_STACK_KEYS = setOf("id", "Count", "tag", "Slot")
    private val POTION_HOLDERS = setOf(
        "minecraft:potion",
        "minecraft:splash_potion",
        "minecraft:lingering_potion",
        "minecraft:tipped_arrow"
    )
    private val SAME_MEANING_ATTRIBUTES = mapOf(
        "forge:entity_gravity" to "minecraft:generic.gravity",
        "porting_lib:entity_gravity" to "minecraft:generic.gravity"
    )

    /** Tag fields the vanilla ITEM_STACK fix componentizes for every item but this migration does not implement. */
    private val UNIMPLEMENTED_GENERIC_ITEM_FIELDS = setOf(
        "HideFlags",
        "BlockStateTag",
        "EntityTag",
        "BlockEntityTag",
        "CanDestroy",
        "CanPlaceOn",
        "AttributeModifiers",
        "Trim",
        "CustomPotionColor",
        "CustomPotionEffects",
        "custom_potion_effects"
    )

    /** Tag fields the vanilla fix componentizes only for these items; elsewhere they are custom data. */
    private val UNIMPLEMENTED_ITEM_SPECIFIC_FIELDS: Map<String, Set<String>> = mapOf(
        "minecraft:crossbow" to setOf("Charged", "ChargedProjectiles"),
        "minecraft:bundle" to setOf("Items"),
        "minecraft:filled_map" to setOf("map", "Decorations"),
        "minecraft:writable_book" to setOf("pages", "filtered_pages"),
        "minecraft:written_book" to setOf(
            "pages", "filtered_pages", "title", "filtered_title", "author", "generation", "resolved"
        ),
        "minecraft:suspicious_stew" to setOf("effects", "Effects"),
        "minecraft:debug_stick" to setOf("DebugProperty"),
        "minecraft:compass" to setOf("LodestoneDimension", "LodestonePos", "LodestoneTracked"),
        "minecraft:firework_star" to setOf("Explosion"),
        "minecraft:firework_rocket" to setOf("Fireworks"),
        "minecraft:player_head" to setOf("SkullOwner"),
        "minecraft:knowledge_book" to setOf("Recipes"),
        "minecraft:goat_horn" to setOf("instrument"),
        "minecraft:tropical_fish_bucket" to setOf("BucketVariantTag")
    )

    data class Counts(
        var items: Int = 0,
        var emptyItems: Int = 0,
        var fluids: Int = 0,
        var attributes: Int = 0,
        var emptyEntities: Int = 0,
        var tagReferences: Int = 0
    ) {
        val total: Int get() = items + emptyItems + fluids + attributes + emptyEntities + tagReferences

        fun describe(): String = buildList {
            if (items > 0) add("$items legacy ItemStack payload(s)")
            if (emptyItems > 0) add("$emptyItems empty ItemStack payload(s)")
            if (fluids > 0) add("$fluids legacy FluidStack payload(s)")
            if (attributes > 0) add("$attributes renamed entity attribute(s)")
            if (emptyEntities > 0) add("$emptyEntities empty entity wrapper(s)")
            if (tagReferences > 0) add("$tagReferences legacy tag reference(s)")
        }.joinToString(" and ")
    }

    data class PlannedFile(
        val path: Path,
        val originalBytes: ByteArray,
        val migratedBytes: ByteArray,
        val counts: Counts,
        val originalSha256: String,
        val migratedSha256: String
    )

    data class Plan(val files: List<PlannedFile>, val blockers: List<String>) {
        val accepted: Boolean get() = blockers.isEmpty()
    }

    internal class MigrationRejected(message: String) : IllegalArgumentException(message)

    fun plan(resourceRoots: Collection<Path>): Plan {
        val files = sortedSetOf<Path>()
        resourceRoots
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .forEach { root ->
                if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) return@forEach
                Files.walk(root).use { paths ->
                    paths.filter { candidate ->
                        candidate.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                            candidate.extension.equals("nbt", ignoreCase = true) &&
                            isActiveStructurePath(root.relativize(candidate))
                    }.forEach(files::add)
                }
            }
        val planned = mutableListOf<PlannedFile>()
        val blockers = mutableListOf<String>()
        for (file in files) {
            if (Files.isSymbolicLink(file)) {
                blockers.add("$file: symbolic-link structure input is not admitted")
                continue
            }
            try {
                val original = Files.readAllBytes(file)
                val (migrated, counts) = migrateBytes(original, file.toString())
                if (counts.total == 0) continue
                planned.add(
                    PlannedFile(file, original, migrated, counts, sha256(original), sha256(migrated))
                )
            } catch (failure: Exception) {
                blockers.add("$file: ${failure.message}")
            }
        }
        return Plan(planned, blockers)
    }

    fun apply(plan: Plan) {
        require(plan.accepted) { "Legacy structure NBT migration refused: ${plan.blockers.joinToString("; ")}" }
        for (file in plan.files) {
            require(sha256(Files.readAllBytes(file.path)) == file.originalSha256) {
                "Structure NBT changed after planning: ${file.path}"
            }
        }
        val written = mutableListOf<PlannedFile>()
        try {
            for (file in plan.files) {
                replaceAtomically(file.path, file.migratedBytes)
                written.add(file)
            }
        } catch (failure: Exception) {
            for (file in written.asReversed()) {
                runCatching { replaceAtomically(file.path, file.originalBytes) }
                    .onFailure { failure.addSuppressed(it) }
            }
            throw failure
        }
    }

    internal fun migrateBytes(input: ByteArray, context: String = "<memory>"): Pair<ByteArray, Counts> {
        val typed = TypedNbt.decode(input)
        val counts = migrateDocument(typed.document, context)
        return if (counts.total == 0) input to counts else TypedNbt.encode(typed) to counts
    }

    internal fun migrateDocument(document: NbtDocument, context: String): Counts {
        val counts = Counts()
        (document.root.entries["blocks"] as? ListTag)?.let { blocks ->
            blocks.values.forEachIndexed { index, block ->
                val blockEntity = (block as? CompoundTag)?.entries?.get("nbt") as? CompoundTag
                    ?: return@forEachIndexed
                migrateCompound(blockEntity, "$context blocks[$index].nbt", counts, isVanillaOwned(blockEntity))
            }
        }
        (document.root.entries["entities"] as? ListTag)?.let { entities ->
            val before = entities.values.size
            entities.values.removeIf { wrapper ->
                val nbt = (wrapper as? CompoundTag)?.entries?.get("nbt")
                nbt is CompoundTag && nbt.entries.isEmpty()
            }
            counts.emptyEntities += before - entities.values.size
            entities.values.forEachIndexed { index, wrapper ->
                val nbt = (wrapper as? CompoundTag)?.entries?.get("nbt") as? CompoundTag
                    ?: return@forEachIndexed
                migrateCompound(nbt, "$context entities[$index].nbt", counts, isVanillaOwned(nbt))
            }
        }
        return counts
    }

    /**
     * Vanilla's STRUCTURE fix walks vanilla block entities and entities (and vanilla passengers of
     * those) by schema, so the stacks it reaches are left for it. It never enters modded owners or
     * the custom data of a stack, so those are migrated here.
     */
    private fun isVanillaOwned(owner: CompoundTag): Boolean =
        (owner.entries["id"] as? StringTag)?.value?.startsWith("minecraft:") == true

    /**
     * Post-order: children first, so stacks nested in a legacy tag are upgraded before the tag
     * becomes custom data. [fixerReached] marks a subtree that vanilla's data fix upgrades at load.
     */
    private fun migrateCompound(compound: CompoundTag, context: String, counts: Counts, fixerReached: Boolean) {
        val legacyStack = isLegacyItemStack(compound)
        for ((key, value) in compound.entries.entries.toList()) {
            when (value) {
                // A stack's tag is mod data the fixer moves to custom data without entering it.
                is CompoundTag -> migrateCompound(
                    value,
                    "$context.$key",
                    counts,
                    fixerReached && !(legacyStack && key == "tag")
                )
                is ListTag -> migrateList(value, "$context.$key", counts, fixerReached, passengers = key == "Passengers")
                is StringTag -> if (key in TAG_REFERENCE_VALUE_KEYS) {
                    val normalized = normalizeLegacyTagReference(key, value.value)
                    if (normalized != value.value) {
                        compound.entries[key] = StringTag(normalized)
                        counts.tagReferences++
                    }
                }
                else -> Unit
            }
        }
        // The fixer does not know Forge attribute ids anywhere.
        migrateAttributes(compound, counts)
        if (fixerReached) return
        if (legacyStack) {
            requireConvertibleStack(compound, context)
            if (isEmptyLegacyStack(compound, context)) {
                // The 1.21 empty stack serializes as an empty compound.
                compound.entries.clear()
                counts.emptyItems++
            } else {
                componentizeStack(compound, context)
                counts.items++
            }
        }
        if ("FluidName" in compound.entries) {
            migrateFluidFields(compound, context)
            counts.fluids++
        }
    }

    private fun migrateList(
        list: ListTag,
        context: String,
        counts: Counts,
        fixerReached: Boolean,
        passengers: Boolean = false
    ) {
        val iterator = list.values.listIterator()
        var index = 0
        while (iterator.hasNext()) {
            val element = iterator.next()
            when (element) {
                is CompoundTag -> {
                    val reached = if (passengers) fixerReached && isVanillaOwned(element) else fixerReached
                    val slottedEmpty = !reached && isLegacyItemStack(element) && "Slot" in element.entries &&
                        requireConvertibleStack(element, "$context[$index]").let { true } &&
                        isEmptyLegacyStack(element, "$context[$index]")
                    if (slottedEmpty) {
                        // Slot-indexed inventories treat a missing slot as empty; a bare {Slot}
                        // would make the 1.21 reader log an invalid stack.
                        iterator.remove()
                        counts.emptyItems++
                    } else {
                        migrateCompound(element, "$context[$index]", counts, reached)
                    }
                }
                is ListTag -> migrateList(element, "$context[$index]", counts, fixerReached)
                else -> Unit
            }
            index++
        }
    }

    /** The legacy ItemStack shape: a resource-id `id` beside a numeric `Count`. */
    private fun isLegacyItemStack(compound: CompoundTag): Boolean {
        val id = compound.entries["id"] as? StringTag ?: return false
        if (!RESOURCE_ID.matches(id.value)) return false
        val count = compound.entries["Count"] ?: return false
        return count is ByteTag || count is ShortTag || count is IntTag
    }

    private fun requireConvertibleStack(compound: CompoundTag, context: String) {
        if (!LEGACY_STACK_KEYS.containsAll(compound.entries.keys)) {
            throw MigrationRejected("$context: legacy ItemStack with unsupported keys ${compound.entries.keys}")
        }
    }

    private fun isEmptyLegacyStack(compound: CompoundTag, context: String): Boolean {
        val id = (compound.entries["id"] as StringTag).value
        return id == "minecraft:air" || numericInt(compound.entries["Count"], "$context.Count") <= 0
    }

    private fun componentizeStack(stack: CompoundTag, context: String) {
        val id = (stack.entries["id"] as StringTag).value
        val count = numericInt(stack.entries["Count"], "$context.Count")
        val legacyTag = stack.entries["tag"]?.let {
            it as? CompoundTag ?: throw MigrationRejected("$context.tag must be a compound")
        }
        val components = legacyTag?.let { componentizeTag(id, it, "$context.tag") } ?: linkedMapOf()
        val replacement = linkedMapOf<String, NbtTag>()
        stack.entries.forEach { (key, value) ->
            if (key != "id" && key != "Count" && key != "tag") replacement[key] = value
        }
        if (components.isNotEmpty()) replacement["components"] = CompoundTag(components)
        replacement["count"] = IntTag(count)
        replacement["id"] = StringTag(id)
        stack.entries.clear()
        stack.entries.putAll(replacement)
    }

    private fun componentizeTag(
        itemId: String,
        tag: CompoundTag,
        context: String
    ): LinkedHashMap<String, NbtTag> {
        val data = LinkedHashMap(tag.entries)
        UNIMPLEMENTED_GENERIC_ITEM_FIELDS.firstOrNull { it in data }?.let {
            throw MigrationRejected("$context: '$it' needs the vanilla ITEM_STACK fix, which is not implemented here")
        }
        UNIMPLEMENTED_ITEM_SPECIFIC_FIELDS[itemId]?.firstOrNull { it in data }?.let {
            throw MigrationRejected("$context: '$it' of $itemId needs the vanilla ITEM_STACK fix, which is not implemented here")
        }
        val components = linkedMapOf<String, NbtTag>()
        data.remove("Damage")?.let {
            val damage = numericInt(it, "$context.Damage")
            if (damage != 0) components["minecraft:damage"] = IntTag(damage)
        }
        data.remove("RepairCost")?.let {
            val cost = numericInt(it, "$context.RepairCost")
            if (cost != 0) components["minecraft:repair_cost"] = IntTag(cost)
        }
        data.remove("CustomModelData")?.let {
            components["minecraft:custom_model_data"] = IntTag(numericInt(it, "$context.CustomModelData"))
        }
        data.remove("Unbreakable")?.let {
            if (numericInt(it, "$context.Unbreakable") != 0) components["minecraft:unbreakable"] = CompoundTag()
        }
        data.remove("Enchantments")?.let {
            enchantmentLevels(it, "$context.Enchantments")?.let { levels ->
                components["minecraft:enchantments"] = levels
            }
        }
        if (itemId == "minecraft:enchanted_book") {
            data.remove("StoredEnchantments")?.let {
                enchantmentLevels(it, "$context.StoredEnchantments")?.let { levels ->
                    components["minecraft:stored_enchantments"] = levels
                }
            }
        }
        data["display"]?.let { display ->
            val remaining = componentizeDisplay(display, components, "$context.display")
            if (remaining.entries.isEmpty()) data.remove("display") else data["display"] = remaining
        }
        if (itemId in POTION_HOLDERS) {
            data.remove("Potion")?.let {
                val potion = (it as? StringTag)?.value
                    ?: throw MigrationRejected("$context.Potion must be a string")
                if (!RESOURCE_ID.matches(potion)) throw MigrationRejected("$context.Potion '$potion' is not a resource id")
                components["minecraft:potion_contents"] = CompoundTag(linkedMapOf("potion" to StringTag(potion)))
            }
        }
        if (data.isNotEmpty()) components["minecraft:custom_data"] = CompoundTag(data)
        return components
    }

    private fun enchantmentLevels(tag: NbtTag, context: String): CompoundTag? {
        val list = tag as? ListTag ?: throw MigrationRejected("$context must be a list")
        if (list.values.isEmpty()) return null
        val levels = linkedMapOf<String, NbtTag>()
        list.values.forEachIndexed { index, entry ->
            val enchantment = entry as? CompoundTag
                ?: throw MigrationRejected("$context[$index] must be a compound")
            if (enchantment.entries.keys != setOf("id", "lvl")) {
                // `[{}]` was the legacy glint override; the vanilla fix maps it to another component.
                throw MigrationRejected("$context[$index] has unsupported keys ${enchantment.entries.keys}")
            }
            val id = (enchantment.entries["id"] as? StringTag)?.value
                ?: throw MigrationRejected("$context[$index].id must be a string")
            if (!RESOURCE_ID.matches(id)) throw MigrationRejected("$context[$index].id '$id' is not a resource id")
            levels[id] = IntTag(numericInt(enchantment.entries["lvl"], "$context[$index].lvl"))
        }
        return CompoundTag(linkedMapOf("levels" to CompoundTag(levels)))
    }

    private fun componentizeDisplay(
        tag: NbtTag,
        components: LinkedHashMap<String, NbtTag>,
        context: String
    ): CompoundTag {
        val display = tag as? CompoundTag ?: throw MigrationRejected("$context must be a compound")
        val remaining = LinkedHashMap(display.entries)
        remaining.remove("Name")?.let {
            components["minecraft:custom_name"] = it as? StringTag
                ?: throw MigrationRejected("$context.Name must be a string")
        }
        remaining.remove("Lore")?.let {
            val lore = it as? ListTag ?: throw MigrationRejected("$context.Lore must be a list")
            if (lore.values.any { line -> line !is StringTag }) {
                throw MigrationRejected("$context.Lore must contain strings")
            }
            components["minecraft:lore"] = lore
        }
        remaining.remove("color")?.let {
            components["minecraft:dyed_color"] =
                CompoundTag(linkedMapOf("rgb" to IntTag(numericInt(it, "$context.color"))))
        }
        if ("MapColor" in remaining) {
            throw MigrationRejected("$context.MapColor needs the vanilla ITEM_STACK fix, which is not implemented here")
        }
        return CompoundTag(remaining)
    }

    private fun migrateFluidFields(compound: CompoundTag, context: String) {
        val name = (compound.entries.remove("FluidName") as? StringTag)?.value
            ?: throw MigrationRejected("$context.FluidName must be a string")
        val amount = (compound.entries.remove("Amount") as? IntTag)?.value
            ?: throw MigrationRejected("$context.Amount must be an int beside FluidName")
        val legacyTag = compound.entries.remove("Tag")?.let {
            it as? CompoundTag ?: throw MigrationRejected("$context.Tag must be a compound")
        }
        if (name == "minecraft:empty" || amount <= 0) {
            if (legacyTag != null && legacyTag.entries.isNotEmpty()) {
                throw MigrationRejected("$context: empty legacy fluid carries a tag")
            }
            return
        }
        if (!RESOURCE_ID.matches(name)) throw MigrationRejected("$context.FluidName '$name' is not a resource id")
        if ("Fluid" in compound.entries) throw MigrationRejected("$context already has a Fluid entry beside legacy fields")

        fun modern(): LinkedHashMap<String, NbtTag> = linkedMapOf<String, NbtTag>().apply {
            put("amount", IntTag(amount))
            put("id", StringTag(name))
            if (legacyTag != null && legacyTag.entries.isNotEmpty()) {
                put(
                    "components",
                    CompoundTag(linkedMapOf("minecraft:custom_data" to CompoundTag(LinkedHashMap(legacyTag.entries))))
                )
            }
        }
        compound.entries["Fluid"] = CompoundTag(modern())
        val bare = modern()
        if (bare.keys.none { it in compound.entries }) compound.entries.putAll(bare)
    }

    private fun migrateAttributes(compound: CompoundTag, counts: Counts) {
        val attributes = compound.entries["Attributes"] as? ListTag ?: return
        val names = attributes.values.mapNotNull { ((it as? CompoundTag)?.entries?.get("Name") as? StringTag)?.value }
        attributes.values.forEach { entry ->
            val attribute = entry as? CompoundTag ?: return@forEach
            val name = (attribute.entries["Name"] as? StringTag)?.value ?: return@forEach
            val target = SAME_MEANING_ATTRIBUTES[name] ?: return@forEach
            if (target in names) {
                throw MigrationRejected("attribute list has both $name and $target")
            }
            attribute.entries["Name"] = StringTag(target)
            counts.attributes++
        }
    }

    private fun numericInt(tag: NbtTag?, context: String): Int = when (tag) {
        is ByteTag -> tag.value.toInt()
        is ShortTag -> tag.value.toInt()
        is IntTag -> tag.value
        else -> throw MigrationRejected("$context must be TAG_Byte, TAG_Short or TAG_Int")
    }

    private fun isActiveStructurePath(relativePath: Path): Boolean {
        val parts = (0 until relativePath.nameCount).map { relativePath.getName(it).toString() }
        if (parts.size >= 2 && parts.first() == "gameteststructures") return true
        return parts.size >= 4 && parts[0] == "data" && parts[2] in STRUCTURE_DIRECTORIES
    }

    private fun replaceAtomically(target: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".modporter.tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
