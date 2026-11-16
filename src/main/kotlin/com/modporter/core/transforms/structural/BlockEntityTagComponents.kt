package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * 1.20.5 moved a block item's `"BlockEntityTag"` child out of the stack NBT into the
 * `DataComponents.BLOCK_ENTITY_DATA` component (`BlockItem.BLOCK_ENTITY_TAG` is gone, and
 * `BlockItem.setBlockEntityData` writes the component). Reads and writes that went through the
 * stack tag with that key, whether spelled as the constant or the literal, read and write the
 * component: `getOrDefault(BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag()` for the copy, and
 * `BlockItem.setBlockEntityData(stack, type, tag)` for the write. 1.21.1 only applies block entity
 * data that names its block entity type, so the write adds the type of the block the item places,
 * looked up from the block entity type registry the way the block itself is validated
 * (`BlockEntityType#isValid`). The stack tag reaching the key may be the stack's own tag or a
 * local copy of its custom data (`getOrDefault(CUSTOM_DATA, EMPTY).copyTag()`).
 */
internal object BlockEntityTagComponents {
    private const val COMPONENT = "DataComponents.BLOCK_ENTITY_DATA"
    private const val KEY = """(?:(?:BlockItem\.)?BLOCK_ENTITY_TAG|"BlockEntityTag")"""
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val STACK = """((?:this\.)?$ID(?:\(\))?)"""
    private const val TAG = """\.(?:getTag|getOrCreateTag)\(\)\s*"""
    private const val CUSTOM_DATA_COPY =
        """\.getOrDefault\(\s*(?:net\.minecraft\.core\.component\.)?DataComponents\.CUSTOM_DATA\s*,\s*(?:net\.minecraft\.world\.item\.component\.)?CustomData\.EMPTY\s*\)\.copyTag\(\)\s*"""
    private const val TYPE_HELPER = "modporterBlockEntityTypeOf"

    fun migrate(source: String): String {
        if (!source.contains("BLOCK_ENTITY_TAG") && !source.contains("\"BlockEntityTag\"")) return source
        var result = source
        val masked = JavaSourceText.maskCommentsAndLiterals(result)
        if (!Regex(KEY).containsMatchIn(masked) && !source.contains("\"BlockEntityTag\"")) return source

        // Locals aliasing the stack tag: CompoundTag stackTag = stack.getTag(); or a custom data copy
        val aliases = (Regex("""\bCompoundTag\s+($ID)\s*=\s*$STACK$TAG;""").findAll(result) +
            Regex("""\bCompoundTag\s+($ID)\s*=\s*$STACK$CUSTOM_DATA_COPY;""").findAll(result))
            .associate { it.groupValues[1] to it.groupValues[2] }
        val receivers = mutableListOf("$STACK$TAG") + aliases.keys.map { "(${Regex.escape(it)})\\s*" }
        var typed = false

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
                typed = true
                val stack = stack(match)
                "net.minecraft.world.item.BlockItem.setBlockEntityData($stack, $TYPE_HELPER($stack), ${match.groupValues[2]})"
            }
            result = Regex("""$receiver\.remove\(\s*$KEY\s*\)""").replace(result) { match -> "${stack(match)}.remove($COMPONENT)" }
        }
        result = Regex("""$STACK\.getTagElement\(\s*$KEY\s*\)""").replace(result) { match ->
            val stack = match.groupValues[1]
            "($stack.has($COMPONENT) ? $stack.get($COMPONENT).copyTag() : null)"
        }
        result = Regex("""$STACK\.removeTagKey\(\s*$KEY\s*\)""").replace(result) { match -> "${match.groupValues[1]}.remove($COMPONENT)" }
        // 1.20.1 had the tag whenever it held the key, and the block entity data no longer lives in
        // custom data: `hasTag() && tag.contains(KEY)` that an earlier rule split keeps one test
        result = Regex(
            """$STACK\.has\(\s*(?:net\.minecraft\.core\.component\.)?DataComponents\.CUSTOM_DATA\s*\)\s*&&\s*(\1)\.has\(\s*(?:net\.minecraft\.core\.component\.)?$COMPONENT\s*\)"""
        ).replace(result) { match -> "${match.groupValues[1]}.has($COMPONENT)" }
        if (result == source) return source
        if (!Regex(KEY).containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result).replace("import static", ""))) {
            result = Regex("""(?m)^import\s+static\s+net\.minecraft\.world\.item\.BlockItem\.BLOCK_ENTITY_TAG;\s*\r?\n""").replace(result, "")
        }
        result = JavaSourceText.addImport(result, "net.minecraft.core.component.DataComponents")
        if (result.contains("CustomData.")) result = JavaSourceText.addImport(result, "net.minecraft.world.item.component.CustomData")
        if (typed) result = addTypeHelper(result)
        return result
    }

    /** The block entity type of the block an item places, as a private static helper of the outermost type. */
    private fun addTypeHelper(source: String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        if (masked.contains("$TYPE_HELPER(net.minecraft.world.item.ItemStack")) return source
        val declaration = Regex("""\b(?:class|enum|record)\s+$ID[^{]*\{""").find(masked) ?: return source
        val close = JavaSourceText.matchingBrace(masked, declaration.range.last)
        if (close < 0) return source
        val nl = if (source.contains("\r\n")) "\r\n" else "\n"
        val helper = listOf(
            "",
            "    /** The block entity type whose valid blocks include the block this item places. */",
            "    private static net.minecraft.world.level.block.entity.BlockEntityType<?> $TYPE_HELPER(net.minecraft.world.item.ItemStack stack) {",
            "        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem))",
            "            throw new IllegalStateException(\"Block entity data on a non-block item: \" + stack);",
            "        net.minecraft.world.level.block.state.BlockState state = blockItem.getBlock().defaultBlockState();",
            "        for (net.minecraft.world.level.block.entity.BlockEntityType<?> type : net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE)",
            "            if (type.isValid(state))",
            "                return type;",
            "        throw new IllegalStateException(\"No block entity type places \" + blockItem.getBlock());",
            "    }",
            ""
        ).joinToString(nl)
        return source.substring(0, close) + helper + source.substring(close)
    }
}
