package com.modporter.resources

import com.modporter.resources.CreateStructureFluidNbtMigration.CompoundTag
import com.modporter.resources.CreateStructureFluidNbtMigration.DoubleTag
import com.modporter.resources.CreateStructureFluidNbtMigration.ListTag
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtDocument
import com.modporter.resources.CreateStructureFluidNbtMigration.StringTag

/**
 * Source-bound rename for the three legacy gravity attributes observed in the
 * converted Create structure corpus. The shared typed codec preserves the
 * surrounding binary NBT representation.
 */
internal object CreateStructureGravityAttributeNbtMigration {
    const val RULE_ID = "res-create-structure-gravity-attribute-121"

    private const val TAG_COMPOUND = 10
    private const val TARGET_ATTRIBUTE = "minecraft:generic.gravity"
    private const val FORGE_ATTRIBUTE = "forge:entity_gravity"
    private const val PORTING_LIB_ATTRIBUTE = "porting_lib:entity_gravity"
    private val EXPECTED_BASE_BITS = java.lang.Double.doubleToRawLongBits(0.08)
    private val LEGACY_ATTRIBUTES = setOf(FORGE_ATTRIBUTE, PORTING_LIB_ATTRIBUTE)
    private val ADMITTED_ENTITY_ATTRIBUTES = mapOf(
        "minecraft:armor_stand" to PORTING_LIB_ATTRIBUTE,
        "minecraft:zombie" to FORGE_ATTRIBUTE
    )

    internal fun migrateDocument(document: NbtDocument, context: String): Int {
        val entitiesTag = document.root.entries["entities"] ?: return 0
        val entities = entitiesTag as? ListTag
            ?: reject("$context: root entities must be a list")
        if (entities.values.isEmpty()) return 0
        if (entities.elementType != TAG_COMPOUND) {
            reject("$context: root entities must be a compound list")
        }

        var migrated = 0
        entities.values.forEachIndexed { index, value ->
            val wrapper = value as? CompoundTag
                ?: reject("$context: entities[$index] is not a compound")
            val entityTag = wrapper.entries["nbt"] ?: return@forEachIndexed
            val entity = entityTag as? CompoundTag
                ?: reject("$context: entities[$index].nbt is not a compound")
            migrated += migrateEntity(entity, "$context entities[$index].nbt")
        }
        return migrated
    }

    private fun migrateEntity(entity: CompoundTag, context: String): Int {
        val entityId = (entity.entries["id"] as? StringTag)?.value
        val admittedLegacyAttribute = entityId?.let(ADMITTED_ENTITY_ATTRIBUTES::get)
        var migrated = 0
        if (entityId != null && admittedLegacyAttribute != null) {
            migrated += migrateAttributes(
                entity,
                entityId,
                admittedLegacyAttribute,
                context
            )
        }

        val passengersTag = entity.entries["Passengers"] ?: return migrated
        val passengers = passengersTag as? ListTag
        if (passengers == null) {
            if (admittedLegacyAttribute != null) {
                reject("$context $entityId: Passengers must be a list")
            }
            return migrated
        }
        if (passengers.values.isEmpty()) return migrated
        if (passengers.elementType != TAG_COMPOUND) {
            reject("$context $entityId: nonempty Passengers must be a compound list")
        }
        passengers.values.forEachIndexed { index, value ->
            val passenger = value as? CompoundTag
                ?: reject("$context Passengers[$index] is not a compound")
            migrated += migrateEntity(passenger, "$context Passengers[$index]")
        }
        return migrated
    }

    private fun migrateAttributes(
        entity: CompoundTag,
        entityId: String,
        admittedLegacyAttribute: String,
        context: String
    ): Int {
        val attributesTag = entity.entries["Attributes"] ?: return 0
        val attributes = attributesTag as? ListTag
            ?: reject("$context $entityId: Attributes must be a list")
        if (attributes.values.isEmpty()) return 0
        if (attributes.elementType != TAG_COMPOUND) {
            reject("$context $entityId: nonempty Attributes must be a compound list")
        }

        val legacy = mutableListOf<Pair<Int, String>>()
        var targetCount = 0
        attributes.values.forEachIndexed { index, value ->
            val attribute = value as? CompoundTag
                ?: reject("$context $entityId: Attributes[$index] is not a compound")
            val name = (attribute.entries["Name"] as? StringTag)?.value
            if (name != null && name in LEGACY_ATTRIBUTES) legacy += index to name
            if (name == TARGET_ATTRIBUTE) targetCount++
        }
        if (legacy.isEmpty()) return 0
        if (legacy.size != 1) {
            reject(
                "$context $entityId: duplicate legacy gravity aliases " +
                    legacy.joinToString { it.second }
            )
        }
        if (targetCount > 0) {
            reject("$context $entityId: native gravity target already exists")
        }

        val (index, legacyName) = legacy.single()
        if (legacyName != admittedLegacyAttribute) {
            reject(
                "$context $entityId: legacy gravity alias $legacyName is not admitted; " +
                    "expected $admittedLegacyAttribute"
            )
        }
        val attribute = attributes.values[index] as CompoundTag
        val keys = attribute.entries.keys
        if (keys != setOf("Base", "Name") && keys != setOf("Base", "Name", "Modifiers")) {
            reject(
                "$context $entityId: unsupported legacy gravity keys $keys; " +
                    "expected [Base, Name] with optional empty Modifiers"
            )
        }
        val base = attribute.entries["Base"] as? DoubleTag
            ?: reject("$context $entityId: gravity Base must be TAG_Double")
        if (base.rawBits != EXPECTED_BASE_BITS) {
            reject("$context $entityId: gravity Base must be exactly 0.08")
        }
        attribute.entries["Modifiers"]?.let { modifiersTag ->
            val modifiers = modifiersTag as? ListTag
                ?: reject("$context $entityId: gravity Modifiers must be a list")
            if (modifiers.values.isNotEmpty()) {
                reject("$context $entityId: gravity Modifiers must be empty")
            }
        }

        attribute.entries["Name"] = StringTag(TARGET_ATTRIBUTE)
        return 1
    }

    private fun reject(message: String): Nothing =
        throw IllegalArgumentException(message)
}
