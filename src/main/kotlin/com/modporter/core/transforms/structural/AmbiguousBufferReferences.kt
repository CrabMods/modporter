package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * 1.21.1's `FriendlyByteBuf` pairs instance methods such as `readUUID()` / `writeUUID(UUID)` with
 * static twins taking the buffer first (`readUUID(ByteBuf)`, `writeUUID(ByteBuf, UUID)`; class
 * table). A method reference `FriendlyByteBuf::readUUID` handed to `readList` / `writeCollection`
 * fits both, so javac rejects it; on 1.20.1 only the instance method existed. The reference becomes
 * the lambda calling that instance method.
 */
internal object AmbiguousBufferReferences {
    private const val OWNER = "net.minecraft.network.FriendlyByteBuf"
    private const val BYTE_BUF = "Lio/netty/buffer/ByteBuf;"
    private val reference = Regex("""(?<![\w$.])(?:net\.minecraft\.network\.)?(?:Registry)?FriendlyByteBuf\s*::\s*([A-Za-z_$][\w$]*)""")

    fun migrate(source: String): String {
        if (!source.contains("FriendlyByteBuf::") && !source.contains("FriendlyByteBuf ::")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        return reference.findAll(masked).toList().asReversed().fold(source) { text, match ->
            val count = instanceArity(match.groupValues[1]) ?: return@fold text
            val arguments = (1..count).map { "modporterValue$it" }
            val parameters = (listOf("modporterBuffer") + arguments).joinToString(", ")
            val lambda = "${if (count == 0) parameters else "($parameters)"} -> modporterBuffer.${match.groupValues[1]}(${arguments.joinToString(", ")})"
            text.replaceRange(match.range, lambda)
        }
    }

    /** The parameter count of the instance overload when a static twin taking a ByteBuf first makes the reference ambiguous. */
    private fun instanceArity(name: String): Int? {
        val shapes = TargetMembers.methodDescriptors(OWNER, name).map { TargetMembers.parameterTypes(it) }
        return shapes.firstOrNull { instance ->
            shapes.any { twin -> twin.firstOrNull() == BYTE_BUF && twin.drop(1) == instance }
        }?.size
    }
}
