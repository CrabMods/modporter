package com.modporter.resources

import com.modporter.resources.CreateStructureFluidNbtMigration.CompoundTag
import com.modporter.resources.CreateStructureFluidNbtMigration.DoubleTag
import com.modporter.resources.CreateStructureFluidNbtMigration.IntTag
import com.modporter.resources.CreateStructureFluidNbtMigration.ListTag
import com.modporter.resources.CreateStructureFluidNbtMigration.NbtDocument
import com.modporter.resources.CreateStructureFluidNbtMigration.StringTag

/**
 * Removes the two source-proven empty passenger placeholders from Create's
 * active open_pipes GameTest structure.
 *
 * StructureTemplate records passengers twice when its capture loop ignores
 * Entity.save's false return value: once inside the seat's Passengers payload
 * and once as an empty root wrapper. The target attempts to instantiate every
 * root wrapper and warns because the empty payload has no entity id.
 *
 * Admission is bound to the exact untouched Forge 1.20.1 source bytes plus the
 * exact typed wrapper shapes. A converted/target-only file, a third record, or
 * any shape/position drift rejects the joint structure plan.
 */
internal object CreateStructureEmptyEntityNbtMigration {
    const val RULE_ID = "res-create-structure-empty-entity-121"

    internal const val EXPECTED_SOURCE_SHA256 =
        "1a1098ab9979b60546fb6416a42589b3f858e8a773f9536b3c803e82328f73c3"

    private const val TAG_INT = 3
    private const val TAG_DOUBLE = 6
    private const val TAG_COMPOUND = 10

    private val OPEN_PIPES_SUFFIXES = setOf(
        "data/create/structure/gametest/fluids/open_pipes.nbt",
        "data/create/structures/gametest/fluids/open_pipes.nbt"
    )

    private val EXPECTED_PLACEHOLDERS = listOf(
        ExpectedPlaceholder(
            index = 0,
            blockPos = listOf(4, 0, 3),
            posBits = listOf(
                0x4012000000000000L,
                0x3fe9fffffd999980L,
                0x400c000000000000L
            )
        ),
        ExpectedPlaceholder(
            index = 2,
            blockPos = listOf(4, 0, 1),
            posBits = listOf(
                0x4012000000000000L,
                0x3fe9fffffd999980L,
                0x3ff8000000000000L
            )
        )
    )

    internal fun migrateDocument(
        document: NbtDocument,
        context: String,
        sourceSha256: String
    ): Int {
        if (!isOpenPipes(context)) return 0

        val entitiesTag = document.root.entries["entities"]
            ?: reject("$context: source-bound open_pipes root has no entities list")
        val entities = entitiesTag as? ListTag
            ?: reject("$context: source-bound open_pipes entities must be a list")
        if (entities.values.isEmpty()) return 0
        if (entities.elementType != TAG_COMPOUND) {
            reject("$context: source-bound open_pipes entities must be a compound list")
        }

        val missingIdIndices = entities.values.mapIndexedNotNull { index, value ->
            val wrapper = value as? CompoundTag
                ?: reject("$context: entities[$index] is not a compound")
            val nbtTag = wrapper.entries["nbt"] ?: return@mapIndexedNotNull index
            val entity = nbtTag as? CompoundTag
                ?: reject("$context: entities[$index].nbt is not a compound")
            val id = entity.entries["id"]
            if (id !is StringTag || id.value.isBlank()) index else null
        }
        if (missingIdIndices.isEmpty()) return 0

        if (sourceSha256.lowercase() != EXPECTED_SOURCE_SHA256) {
            reject(
                "$context: empty root entity wrappers are not admitted for source SHA-256 " +
                    "$sourceSha256; expected $EXPECTED_SOURCE_SHA256"
            )
        }

        val expectedIndices = EXPECTED_PLACEHOLDERS.map(ExpectedPlaceholder::index)
        if (missingIdIndices != expectedIndices) {
            reject(
                "$context: source-bound open_pipes missing-id entity indices " +
                    "$missingIdIndices; expected exactly $expectedIndices"
            )
        }

        EXPECTED_PLACEHOLDERS.forEach { expected ->
            val wrapper = entities.values[expected.index] as CompoundTag
            validatePlaceholder(wrapper, expected, "$context entities[${expected.index}]")
        }

        EXPECTED_PLACEHOLDERS.asReversed().forEach { entities.values.removeAt(it.index) }
        return EXPECTED_PLACEHOLDERS.size
    }

    private fun validatePlaceholder(
        wrapper: CompoundTag,
        expected: ExpectedPlaceholder,
        context: String
    ) {
        if (wrapper.entries.keys.toList() != listOf("nbt", "blockPos", "pos")) {
            reject(
                "$context: empty root entity wrapper keys ${wrapper.entries.keys}; " +
                    "expected exactly [nbt, blockPos, pos]"
            )
        }
        val nbt = wrapper.entries["nbt"] as? CompoundTag
            ?: reject("$context.nbt must be a compound")
        if (nbt.entries.isNotEmpty()) {
            reject("$context.nbt must be exactly empty")
        }

        val blockPos = wrapper.entries["blockPos"] as? ListTag
            ?: reject("$context.blockPos must be a list")
        if (blockPos.elementType != TAG_INT || blockPos.values.size != 3) {
            reject("$context.blockPos must be exactly three TAG_Int values")
        }
        val actualBlockPos = blockPos.values.map {
            (it as? IntTag)?.value
                ?: reject("$context.blockPos must contain only TAG_Int values")
        }
        if (actualBlockPos != expected.blockPos) {
            reject(
                "$context.blockPos $actualBlockPos does not match source position " +
                    expected.blockPos
            )
        }

        val pos = wrapper.entries["pos"] as? ListTag
            ?: reject("$context.pos must be a list")
        if (pos.elementType != TAG_DOUBLE || pos.values.size != 3) {
            reject("$context.pos must be exactly three TAG_Double values")
        }
        val actualPosBits = pos.values.map {
            (it as? DoubleTag)?.rawBits
                ?: reject("$context.pos must contain only TAG_Double values")
        }
        if (actualPosBits != expected.posBits) {
            reject("$context.pos raw bits $actualPosBits do not match source position")
        }
    }

    private fun isOpenPipes(context: String): Boolean {
        val normalized = context.replace('\\', '/').lowercase()
        return OPEN_PIPES_SUFFIXES.any { suffix ->
            normalized == suffix || normalized.endsWith("/$suffix")
        }
    }

    private data class ExpectedPlaceholder(
        val index: Int,
        val blockPos: List<Int>,
        val posBits: List<Long>
    )

    private fun reject(message: String): Nothing =
        throw IllegalArgumentException(message)
}
