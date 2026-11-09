package com.modporter.resources

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Canonical binary-NBT migration for Forge 1.20.1 Create tank block entities
 * embedded in active structure/GameTest resources.
 *
 * Scope is deliberately narrow:
 * - only active structure resource paths, never assets/create/ponder;
 * - only source-proven Create FluidTank consumer fields;
 * - only the exact legacy FluidTank TankContent/OpenEnd schema;
 * - tagged fluid conversion is admitted only for the source-proven Create
 *   potion shape. Every other legacy Tag payload rejects the complete plan
 *   before any file is written.
 *
 * The parser/writer retains the root name, compound entry order, list element
 * types, numeric widths, primitive arrays, all entities, and every unrelated
 * field. It does not raise DataVersion.
 */
internal object CreateStructureFluidNbtMigration {
    const val RULE_ID = "res-create-structure-fluid-stack-121"

    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
    private const val MAX_CONTAINER_ELEMENTS = 1_000_000
    private const val MAX_DEPTH = 64
    private val RESOURCE_ID = Regex("""[a-z0-9_.-]+:[a-z0-9_./-]+""")
    private val CREATE_TANK_BLOCK_ENTITIES = setOf(
        "create:fluid_tank",
        "create:creative_fluid_tank"
    )
    private val PIPE_DIRECTIONS = setOf(
        "down",
        "east",
        "north",
        "south",
        "up",
        "west"
    )
    private val STRUCTURE_DIRECTORIES = setOf("structure", "structures", "gameteststructures")

    data class PlannedFile(
        val path: Path,
        val originalBytes: ByteArray,
        val migratedBytes: ByteArray,
        val migratedTankCount: Int,
        val originalSha256: String,
        val migratedSha256: String
    )

    data class Plan(
        val files: List<PlannedFile>,
        val blockers: List<String>
    ) {
        val accepted: Boolean get() = blockers.isEmpty()
        val migratedTankCount: Int get() = files.sumOf(PlannedFile::migratedTankCount)
    }

    fun plan(resourceRoots: Collection<Path>): Plan {
        val files = linkedSetOf<Path>()
        val blockers = mutableListOf<String>()
        resourceRoots
            .map { it.toAbsolutePath() }
            .map { it.normalize() }
            .distinct()
            .sorted()
            .forEach { root ->
                if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) return@forEach
                Files.walk(root).use { paths ->
                    paths.filter { candidate ->
                        candidate.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                            candidate.extension.equals("nbt", ignoreCase = true) &&
                            isActiveStructurePath(root.relativize(candidate))
                    }.sorted().forEach { files.add(it) }
                }
            }

        val planned = mutableListOf<PlannedFile>()
        for (file in files) {
            if (Files.isSymbolicLink(file)) {
                blockers.add("$file: symbolic-link structure input is not admitted")
                continue
            }
            try {
                val original = Files.readAllBytes(file)
                val migration = migrateBytes(original, file.toString())
                if (migration.migratedTankCount == 0) continue
                planned.add(PlannedFile(
                    path = file,
                    originalBytes = original,
                    migratedBytes = migration.bytes,
                    migratedTankCount = migration.migratedTankCount,
                    originalSha256 = sha256(original),
                    migratedSha256 = sha256(migration.bytes)
                ))
            } catch (rejected: MigrationRejected) {
                blockers.add("$file: ${rejected.message}")
            } catch (failure: Exception) {
                blockers.add("$file: invalid or unsupported binary NBT: ${failure.message}")
            }
        }
        return Plan(planned, blockers)
    }

    /**
     * Applies a preflighted plan transactionally across all changed files.
     *
     * Every source hash is checked before the first write. If a later replace
     * fails, already replaced files are restored from the exact captured bytes.
     */
    fun apply(plan: Plan) {
        require(plan.accepted) {
            "Create structure fluid migration refused: ${plan.blockers.joinToString("; ")}"
        }
        for (file in plan.files) {
            val current = Files.readAllBytes(file.path)
            require(sha256(current) == file.originalSha256) {
                "Create structure NBT drifted after planning: ${file.path}"
            }
        }

        val replaced = mutableListOf<PlannedFile>()
        try {
            for (file in plan.files) {
                replaceAtomically(file.path, file.migratedBytes)
                replaced.add(file)
            }
        } catch (failure: Exception) {
            val rollbackFailures = mutableListOf<Throwable>()
            for (file in replaced.asReversed()) {
                runCatching { replaceAtomically(file.path, file.originalBytes) }
                    .exceptionOrNull()
                    ?.let(rollbackFailures::add)
            }
            rollbackFailures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    internal fun migrateBytes(input: ByteArray, context: String = "<memory>"): ByteMigration {
        val typedNbt = decodeTypedNbt(input)
        val migratedCount = migrateDocument(typedNbt.document, context)
        if (migratedCount == 0) return ByteMigration(input, 0)
        return ByteMigration(encodeTypedNbt(typedNbt), migratedCount)
    }

    /**
     * Reusable typed codec boundary for later narrowly proven resource fixes.
     * Callers receive a mutable typed tree and must provide their own consumer-
     * bound admission rules. This avoids introducing another binary NBT parser
     * for the separate legacy ItemStack resource migration.
     */
    internal fun decodeTypedNbt(input: ByteArray): TypedNbtFile {
        val compressed = input.size >= 2 &&
            input[0] == 0x1f.toByte() &&
            input[1] == 0x8b.toByte()
        val decoded = if (compressed) {
            GZIPInputStream(ByteArrayInputStream(input)).use { readBounded(it) }
        } else {
            require(input.size <= MAX_DECOMPRESSED_BYTES) {
                "NBT exceeds $MAX_DECOMPRESSED_BYTES bytes"
            }
            input
        }
        val document = NbtReader(decoded).readDocument()
        return TypedNbtFile(document, if (compressed) Compression.GZIP else Compression.RAW)
    }

    internal fun encodeTypedNbt(typedNbt: TypedNbtFile): ByteArray {
        val serialized = NbtWriter.write(typedNbt.document)
        return if (typedNbt.compression == Compression.GZIP) {
            ByteArrayOutputStream().use { bytes ->
                GZIPOutputStream(bytes).use { it.write(serialized) }
                bytes.toByteArray()
            }
        } else {
            serialized
        }
    }

    internal data class ByteMigration(val bytes: ByteArray, val migratedTankCount: Int)
    internal data class TypedNbtFile(
        val document: NbtDocument,
        val compression: Compression
    )
    internal enum class Compression { GZIP, RAW }

    private fun isActiveStructurePath(relativePath: Path): Boolean {
        val parts = (0 until relativePath.nameCount).map { relativePath.getName(it).toString() }
        if (parts.size >= 2 && parts.first() == "gameteststructures") return true
        return parts.size >= 4 &&
            parts[0] == "data" &&
            parts[2] in STRUCTURE_DIRECTORIES
    }

    internal fun migrateDocument(document: NbtDocument, context: String): Int {
        val blocks = document.root.entries["blocks"] as? ListTag ?: return 0
        if (blocks.elementType != TAG_COMPOUND) {
            throw MigrationRejected("$context: root blocks list is not a compound list")
        }

        var migrated = 0
        blocks.values.forEachIndexed { index, value ->
            val block = value as? CompoundTag
                ?: throw MigrationRejected("$context: blocks[$index] is not a compound")
            val blockEntity = block.entries["nbt"] as? CompoundTag ?: return@forEachIndexed
            val blockEntityId = (blockEntity.entries["id"] as? StringTag)?.value
                ?: return@forEachIndexed
            val ownerContext = "$context blocks[$index] $blockEntityId"
            when (blockEntityId) {
                in CREATE_TANK_BLOCK_ENTITIES -> {
                    migrated += migrateDirectTankContent(
                        blockEntity,
                        "TankContent",
                        ownerContext
                    )
                }
                "create:basin" -> {
                    migrated += migrateTankSegmentList(
                        blockEntity,
                        "InputTanks",
                        ownerContext
                    )
                    migrated += migrateTankSegmentList(
                        blockEntity,
                        "OutputTanks",
                        ownerContext
                    )
                }
                "create:spout" -> {
                    migrated += migrateTankSegmentList(
                        blockEntity,
                        "Tanks",
                        ownerContext
                    )
                }
                "create:mechanical_pump" -> {
                    migrated += migrateOpenEnds(blockEntity, ownerContext)
                }
            }
        }
        return migrated
    }

    private fun migrateDirectTankContent(
        owner: CompoundTag,
        key: String,
        context: String
    ): Int {
        val existing = owner.entries[key] ?: return 0
        val tank = existing as? CompoundTag
            ?: throw MigrationRejected("$context $key must be a compound")
        return when (classifyTankContent(tank, "$context $key")) {
            TankShape.NATIVE,
            TankShape.SOURCE_VARIANT -> 0
            TankShape.LEGACY -> {
                owner.entries[key] = migrateLegacyTank(tank, "$context $key")
                1
            }
        }
    }

    private fun migrateTankSegmentList(
        owner: CompoundTag,
        key: String,
        context: String
    ): Int {
        val existing = owner.entries[key] ?: return 0
        val list = existing as? ListTag
            ?: throw MigrationRejected("$context $key must be a list")
        if (list.values.isEmpty() && list.elementType == TAG_END) return 0
        if (list.elementType != TAG_COMPOUND) {
            throw MigrationRejected("$context $key must be a compound list")
        }
        var migrated = 0
        list.values.forEachIndexed { index, value ->
            val segment = value as? CompoundTag
                ?: throw MigrationRejected("$context $key[$index] must be a compound")
            migrated += migrateDirectTankContent(
                segment,
                "TankContent",
                "$context $key[$index]"
            )
        }
        return migrated
    }

    private fun migrateOpenEnds(
        owner: CompoundTag,
        context: String
    ): Int {
        var migrated = 0
        PIPE_DIRECTIONS.forEach { direction ->
            val connectionTag = owner.entries[direction] ?: return@forEach
            val connection = connectionTag as? CompoundTag
                ?: throw MigrationRejected("$context $direction must be a compound")
            val openEndTag = connection.entries["OpenEnd"] ?: return@forEach
            val openEnd = openEndTag as? CompoundTag
                ?: throw MigrationRejected("$context $direction.OpenEnd must be a compound")
            if (openEnd.entries.keys ==
                setOf("Variant", "Pulling", "Capacity", "Amount", "Location")
            ) {
                return@forEach
            }
            if ("FluidName" !in openEnd.entries &&
                "Amount" !in openEnd.entries &&
                "Tag" !in openEnd.entries
            ) {
                if ("Fluid" in openEnd.entries) {
                    validateNativeFluid(
                        openEnd.entries.getValue("Fluid"),
                        "$context $direction.OpenEnd"
                    )
                }
                return@forEach
            }
            val expected = setOf("Pulling", "FluidName", "Amount", "Location")
            if (openEnd.entries.keys != expected) {
                throw MigrationRejected(
                    "$context $direction.OpenEnd has unsupported legacy keys " +
                        "${openEnd.entries.keys}"
                )
            }
            val pulling = openEnd.entries["Pulling"] as? ByteTag
                ?: throw MigrationRejected(
                    "$context $direction.OpenEnd Pulling must be TAG_Byte"
                )
            if (pulling.value.toInt() !in 0..1) {
                throw MigrationRejected(
                    "$context $direction.OpenEnd Pulling must be 0 or 1"
                )
            }
            if (openEnd.entries["Location"] !is CompoundTag) {
                throw MigrationRejected(
                    "$context $direction.OpenEnd Location must be a compound"
                )
            }
            val fluidPayload = CompoundTag(
                linkedMapOf(
                    "FluidName" to openEnd.entries.getValue("FluidName"),
                    "Amount" to openEnd.entries.getValue("Amount")
                )
            )
            val migratedTank = migrateLegacyTank(
                fluidPayload,
                "$context $direction.OpenEnd"
            )
            val replacement = linkedMapOf<String, NbtTag>()
            openEnd.entries.forEach { (key, value) ->
                if (key != "FluidName" && key != "Amount") {
                    replacement[key] = value
                }
            }
            replacement.putAll(migratedTank.entries)
            openEnd.entries.clear()
            openEnd.entries.putAll(replacement)
            migrated++
        }
        return migrated
    }

    private fun classifyTankContent(tank: CompoundTag, context: String): TankShape {
        val keys = tank.entries.keys
        if (keys.isEmpty()) return TankShape.NATIVE
        if (keys == setOf("Fluid")) {
            validateNativeFluid(tank.entries.getValue("Fluid"), context)
            return TankShape.NATIVE
        }
        if (keys == setOf("Variant", "Capacity", "Amount")) {
            return TankShape.SOURCE_VARIANT
        }
        if ("FluidName" in keys || "Amount" in keys || "Tag" in keys) {
            val allowed = setOf("FluidName", "Amount", "Tag")
            if (!keys.containsAll(setOf("FluidName", "Amount")) || !allowed.containsAll(keys)) {
                throw MigrationRejected("$context: unsupported legacy TankContent keys $keys")
            }
            return TankShape.LEGACY
        }
        throw MigrationRejected("$context: unknown TankContent schema $keys")
    }

    private fun validateNativeFluid(tag: NbtTag, context: String) {
        val fluid = tag as? CompoundTag
            ?: throw MigrationRejected("$context: native Fluid must be a compound")
        val keys = fluid.entries.keys
        if (!keys.containsAll(setOf("id", "amount")) ||
            !setOf("id", "amount", "components").containsAll(keys)
        ) {
            throw MigrationRejected("$context: unsupported native Fluid keys $keys")
        }
        val id = (fluid.entries["id"] as? StringTag)?.value
            ?: throw MigrationRejected("$context: native Fluid.id must be a string")
        requireResourceId(id, "$context native Fluid.id")
        val amount = (fluid.entries["amount"] as? IntTag)?.value
            ?: throw MigrationRejected("$context: native Fluid.amount must be TAG_Int")
        if (amount <= 0) throw MigrationRejected("$context: native Fluid.amount must be positive")
        fluid.entries["components"]?.let {
            if (it !is CompoundTag) {
                throw MigrationRejected("$context: native Fluid.components must be a compound")
            }
        }
    }

    private fun migrateLegacyTank(tank: CompoundTag, context: String): CompoundTag {
        val fluidName = (tank.entries["FluidName"] as? StringTag)?.value
            ?: throw MigrationRejected("$context: FluidName must be TAG_String")
        val amount = (tank.entries["Amount"] as? IntTag)?.value
            ?: throw MigrationRejected("$context: Amount must be TAG_Int")
        val legacyTag = tank.entries["Tag"]

        if (fluidName == "minecraft:empty" || amount == 0) {
            if (fluidName != "minecraft:empty" || amount != 0 || legacyTag != null) {
                throw MigrationRejected(
                    "$context: only FluidName=minecraft:empty with Amount=0 and no Tag is admitted as empty"
                )
            }
            return CompoundTag()
        }

        requireResourceId(fluidName, "$context FluidName")
        if (amount < 0) throw MigrationRejected("$context: Amount must be positive")
        val fluid = CompoundTag(
            linkedMapOf(
                "amount" to IntTag(amount),
                "id" to StringTag(fluidName)
            )
        )
        if (legacyTag != null) {
            fluid.entries["components"] = migrateSourceBoundComponents(fluidName, legacyTag, context)
        }
        return CompoundTag(linkedMapOf("Fluid" to fluid))
    }

    private fun migrateSourceBoundComponents(
        fluidName: String,
        legacyTag: NbtTag,
        context: String
    ): CompoundTag {
        if (fluidName != "create:potion") {
            throw MigrationRejected("$context: tagged fluid '$fluidName' has no source-bound component mapping")
        }
        val tag = legacyTag as? CompoundTag
            ?: throw MigrationRejected("$context: create:potion Tag must be a compound")
        if (tag.entries.keys != setOf("Bottle", "Potion")) {
            throw MigrationRejected(
                "$context: create:potion Tag keys ${tag.entries.keys} are not the source-bound Bottle/Potion shape"
            )
        }
        val bottle = (tag.entries["Bottle"] as? StringTag)?.value
            ?: throw MigrationRejected("$context: create:potion Bottle must be TAG_String")
        when (bottle) {
            "REGULAR", "SPLASH", "LINGERING" -> Unit
            else -> throw MigrationRejected("$context: unknown Create BottleType '$bottle'")
        }
        val potion = (tag.entries["Potion"] as? StringTag)?.value
            ?: throw MigrationRejected("$context: create:potion Potion must be TAG_String")
        requireResourceId(potion, "$context create:potion Potion")

        return CompoundTag(
            linkedMapOf(
                // The actual converted Create consumer still reads the legacy
                // Bottle/Potion compound through DataComponents.CUSTOM_DATA.
                // CustomData.CODEC is CompoundTag.CODEC, so preserve the
                // admitted legacy compound exactly under this component.
                "minecraft:custom_data" to tag
            )
        )
    }

    private fun requireResourceId(value: String, context: String) {
        if (!RESOURCE_ID.matches(value)) {
            throw MigrationRejected("$context is not a canonical resource id: '$value'")
        }
    }

    private fun replaceAtomically(target: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".modporter.tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_DECOMPRESSED_BYTES) {
                throw MigrationRejected("decompressed NBT exceeds $MAX_DECOMPRESSED_BYTES bytes")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private enum class TankShape { NATIVE, LEGACY, SOURCE_VARIANT }
    private class MigrationRejected(message: String) : IllegalArgumentException(message)

    internal data class NbtDocument(val rootName: String, val root: CompoundTag)

    internal sealed interface NbtTag {
        val typeId: Int
    }

    internal data class ByteTag(val value: Byte) : NbtTag { override val typeId = TAG_BYTE }
    internal data class ShortTag(val value: Short) : NbtTag { override val typeId = TAG_SHORT }
    internal data class IntTag(val value: Int) : NbtTag { override val typeId = TAG_INT }
    internal data class LongTag(val value: Long) : NbtTag { override val typeId = TAG_LONG }
    // Store raw IEEE-754 bits. readFloat/writeFloat and readDouble/writeDouble
    // canonicalize noncanonical NaNs, which would violate unrelated-byte
    // preservation when a structure file is rewritten for TankContent only.
    internal data class FloatTag(val rawBits: Int) : NbtTag { override val typeId = TAG_FLOAT }
    internal data class DoubleTag(val rawBits: Long) : NbtTag { override val typeId = TAG_DOUBLE }
    internal data class ByteArrayTag(val value: ByteArray) : NbtTag { override val typeId = TAG_BYTE_ARRAY }
    internal data class StringTag(val value: String) : NbtTag { override val typeId = TAG_STRING }
    internal data class ListTag(
        val elementType: Int,
        val values: MutableList<NbtTag>
    ) : NbtTag {
        override val typeId = TAG_LIST
    }
    internal data class CompoundTag(
        val entries: LinkedHashMap<String, NbtTag> = linkedMapOf()
    ) : NbtTag {
        override val typeId = TAG_COMPOUND
    }
    internal data class IntArrayTag(val value: IntArray) : NbtTag { override val typeId = TAG_INT_ARRAY }
    internal data class LongArrayTag(val value: LongArray) : NbtTag { override val typeId = TAG_LONG_ARRAY }

    private class NbtReader(bytes: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(bytes))

        fun readDocument(): NbtDocument {
            val type = input.readUnsignedByte()
            require(type == TAG_COMPOUND) { "root tag must be TAG_Compound, got $type" }
            val name = readString()
            val root = readPayload(TAG_COMPOUND, 0) as CompoundTag
            require(input.available() == 0) { "trailing ${input.available()} bytes after root tag" }
            return NbtDocument(name, root)
        }

        private fun readPayload(type: Int, depth: Int): NbtTag {
            require(depth <= MAX_DEPTH) { "NBT nesting exceeds $MAX_DEPTH" }
            return when (type) {
                TAG_BYTE -> ByteTag(input.readByte())
                TAG_SHORT -> ShortTag(input.readShort())
                TAG_INT -> IntTag(input.readInt())
                TAG_LONG -> LongTag(input.readLong())
                TAG_FLOAT -> FloatTag(input.readInt())
                TAG_DOUBLE -> DoubleTag(input.readLong())
                TAG_BYTE_ARRAY -> ByteArrayTag(ByteArray(readSize("byte array")).also(input::readFully))
                TAG_STRING -> StringTag(readString())
                TAG_LIST -> {
                    val elementType = input.readUnsignedByte()
                    val size = readSize("list")
                    if (elementType == TAG_END && size != 0) {
                        throw IllegalArgumentException("nonempty list cannot have TAG_End element type")
                    }
                    ListTag(
                        elementType,
                        MutableList(size) { readPayload(elementType, depth + 1) }
                    )
                }
                TAG_COMPOUND -> {
                    val entries = linkedMapOf<String, NbtTag>()
                    while (true) {
                        val childType = input.readUnsignedByte()
                        if (childType == TAG_END) break
                        val name = readString()
                        require(name !in entries) { "duplicate compound key '$name'" }
                        entries[name] = readPayload(childType, depth + 1)
                    }
                    CompoundTag(entries)
                }
                TAG_INT_ARRAY -> IntArrayTag(IntArray(readSize("int array")) { input.readInt() })
                TAG_LONG_ARRAY -> LongArrayTag(LongArray(readSize("long array")) { input.readLong() })
                else -> throw IllegalArgumentException("unsupported NBT tag id $type")
            }
        }

        private fun readSize(kind: String): Int {
            val size = input.readInt()
            require(size in 0..MAX_CONTAINER_ELEMENTS) {
                "$kind size $size is outside 0..$MAX_CONTAINER_ELEMENTS"
            }
            return size
        }

        private fun readString(): String {
            val size = input.readUnsignedShort()
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private object NbtWriter {
        fun write(document: NbtDocument): ByteArray {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.writeByte(TAG_COMPOUND)
                writeString(data, document.rootName)
                writePayload(data, document.root)
            }
            return output.toByteArray()
        }

        private fun writePayload(output: DataOutputStream, tag: NbtTag) {
            when (tag) {
                is ByteTag -> output.writeByte(tag.value.toInt())
                is ShortTag -> output.writeShort(tag.value.toInt())
                is IntTag -> output.writeInt(tag.value)
                is LongTag -> output.writeLong(tag.value)
                is FloatTag -> output.writeInt(tag.rawBits)
                is DoubleTag -> output.writeLong(tag.rawBits)
                is ByteArrayTag -> {
                    output.writeInt(tag.value.size)
                    output.write(tag.value)
                }
                is StringTag -> writeString(output, tag.value)
                is ListTag -> {
                    output.writeByte(tag.elementType)
                    output.writeInt(tag.values.size)
                    tag.values.forEach { value ->
                        require(value.typeId == tag.elementType) {
                            "list declared type ${tag.elementType} contains ${value.typeId}"
                        }
                        writePayload(output, value)
                    }
                }
                is CompoundTag -> {
                    tag.entries.forEach { (name, value) ->
                        output.writeByte(value.typeId)
                        writeString(output, name)
                        writePayload(output, value)
                    }
                    output.writeByte(TAG_END)
                }
                is IntArrayTag -> {
                    output.writeInt(tag.value.size)
                    tag.value.forEach(output::writeInt)
                }
                is LongArrayTag -> {
                    output.writeInt(tag.value.size)
                    tag.value.forEach(output::writeLong)
                }
            }
        }

        private fun writeString(output: DataOutputStream, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 65535) { "NBT string is too long: ${bytes.size} bytes" }
            output.writeShort(bytes.size)
            output.write(bytes)
        }
    }

    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12
}
