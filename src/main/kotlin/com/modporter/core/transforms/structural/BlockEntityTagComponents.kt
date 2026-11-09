package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * 1.20.5 moved a block item's `"BlockEntityTag"` child out of the stack NBT into the
 * `DataComponents.BLOCK_ENTITY_DATA` component (`BlockItem.BLOCK_ENTITY_TAG` is gone, and
 * `BlockItem.setBlockEntityData` writes the component). Reads and writes that went through the
 * stack tag with that key, whether spelled as the constant or the literal, read and write the
 * component the way Create 6 does: `getOrDefault(BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag()`
 * for the copy, `set(BLOCK_ENTITY_DATA, CustomData.of(tag))` for the write.
 */
internal object BlockEntityTagComponents {
    private const val COMPONENT = "DataComponents.BLOCK_ENTITY_DATA"
    private const val KEY = """(?:(?:BlockItem\.)?BLOCK_ENTITY_TAG|"BlockEntityTag")"""
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val STACK = """((?:this\.)?$ID(?:\(\))?)"""
    private const val TAG = """\.(?:getTag|getOrCreateTag)\(\)\s*"""

    fun migrate(source: String): String {
        if (!source.contains("BLOCK_ENTITY_TAG") && !source.contains("\"BlockEntityTag\"")) return source
        var result = source
        val masked = JavaSourceText.maskCommentsAndLiterals(result)
        if (!Regex(KEY).containsMatchIn(masked) && !source.contains("\"BlockEntityTag\"")) return source

        // Locals aliasing the stack tag: CompoundTag stackTag = stack.getTag();
        val aliases = Regex("""\bCompoundTag\s+($ID)\s*=\s*$STACK$TAG;""").findAll(result)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val receivers = mutableListOf("$STACK$TAG") + aliases.keys.map { "(${Regex.escape(it)})\\s*" }

        for (receiver in receivers) {
            fun stack(match: MatchResult): String = aliases[match.groupValues[1]] ?: match.groupValues[1]
            result = Regex("""$STACK\.hasTag\(\)\s*&&\s*$receiver\.contains\(\s*$KEY\s*\)""").replace(result) { match ->
                "${match.groupValues[1]}.has($COMPONENT)"
            }
            result = Regex("""$receiver\.contains\(\s*$KEY\s*\)""").replace(result) { match -> "${stack(match)}.has($COMPONENT)" }
            result = Regex("""$receiver\.getCompound\(\s*$KEY\s*\)""").replace(result) { match ->
                "${stack(match)}.getOrDefault($COMPONENT, CustomData.EMPTY).copyTag()"
            }
            result = Regex("""$receiver\.put\(\s*$KEY\s*,\s*([^;()]+?)\s*\)""").replace(result) { match ->
                "${stack(match)}.set($COMPONENT, CustomData.of(${match.groupValues[2]}))"
            }
            result = Regex("""$receiver\.remove\(\s*$KEY\s*\)""").replace(result) { match -> "${stack(match)}.remove($COMPONENT)" }
        }
        result = Regex("""$STACK\.getTagElement\(\s*$KEY\s*\)""").replace(result) { match ->
            val stack = match.groupValues[1]
            "($stack.has($COMPONENT) ? $stack.get($COMPONENT).copyTag() : null)"
        }
        result = Regex("""$STACK\.removeTagKey\(\s*$KEY\s*\)""").replace(result) { match -> "${match.groupValues[1]}.remove($COMPONENT)" }
        if (result == source) return source
        if (!Regex(KEY).containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result).replace("import static", ""))) {
            result = Regex("""(?m)^import\s+static\s+net\.minecraft\.world\.item\.BlockItem\.BLOCK_ENTITY_TAG;\s*\r?\n""").replace(result, "")
        }
        result = JavaSourceText.addImport(result, "net.minecraft.core.component.DataComponents")
        if (result.contains("CustomData.")) result = JavaSourceText.addImport(result, "net.minecraft.world.item.component.CustomData")
        return result
    }
}
