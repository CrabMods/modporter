package com.modporter.resources

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Typed, lossless binary-NBT codec for resource migrations.
 *
 * The reader/writer keeps the root name, compound entry order, list element types, numeric
 * widths, primitive arrays and raw floating-point bits (noncanonical NaNs included), so a
 * migration that edits one subtree rewrites every unrelated byte unchanged.
 */
internal object TypedNbt {
    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
    private const val MAX_CONTAINER_ELEMENTS = 1_000_000
    private const val MAX_DEPTH = 64

    const val TAG_END = 0
    const val TAG_BYTE = 1
    const val TAG_SHORT = 2
    const val TAG_INT = 3
    const val TAG_LONG = 4
    const val TAG_FLOAT = 5
    const val TAG_DOUBLE = 6
    const val TAG_BYTE_ARRAY = 7
    const val TAG_STRING = 8
    const val TAG_LIST = 9
    const val TAG_COMPOUND = 10
    const val TAG_INT_ARRAY = 11
    const val TAG_LONG_ARRAY = 12

    data class NbtDocument(val rootName: String, val root: CompoundTag)

    sealed interface NbtTag {
        val typeId: Int
    }

    data class ByteTag(val value: Byte) : NbtTag { override val typeId = TAG_BYTE }
    data class ShortTag(val value: Short) : NbtTag { override val typeId = TAG_SHORT }
    data class IntTag(val value: Int) : NbtTag { override val typeId = TAG_INT }
    data class LongTag(val value: Long) : NbtTag { override val typeId = TAG_LONG }
    // Raw IEEE-754 bits: readFloat/writeFloat would canonicalize noncanonical NaNs.
    data class FloatTag(val rawBits: Int) : NbtTag { override val typeId = TAG_FLOAT }
    data class DoubleTag(val rawBits: Long) : NbtTag { override val typeId = TAG_DOUBLE }
    data class ByteArrayTag(val value: ByteArray) : NbtTag { override val typeId = TAG_BYTE_ARRAY }
    data class StringTag(val value: String) : NbtTag { override val typeId = TAG_STRING }
    data class ListTag(
        val elementType: Int,
        val values: MutableList<NbtTag>
    ) : NbtTag {
        override val typeId = TAG_LIST
    }
    data class CompoundTag(
        val entries: LinkedHashMap<String, NbtTag> = linkedMapOf()
    ) : NbtTag {
        override val typeId = TAG_COMPOUND
    }
    data class IntArrayTag(val value: IntArray) : NbtTag { override val typeId = TAG_INT_ARRAY }
    data class LongArrayTag(val value: LongArray) : NbtTag { override val typeId = TAG_LONG_ARRAY }

    enum class Compression { GZIP, RAW }

    data class TypedNbtFile(
        val document: NbtDocument,
        val compression: Compression
    )

    fun decode(input: ByteArray): TypedNbtFile {
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

    fun encode(typedNbt: TypedNbtFile): ByteArray {
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

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_DECOMPRESSED_BYTES) {
                "decompressed NBT exceeds $MAX_DECOMPRESSED_BYTES bytes"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

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
                    require(elementType != TAG_END || size == 0) {
                        "nonempty list cannot have TAG_End element type"
                    }
                    ListTag(elementType, MutableList(size) { readPayload(elementType, depth + 1) })
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
}
