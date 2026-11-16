package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * Forge 1.20.1 let a StairBlock read its base lazily: `new StairBlock(Supplier<BlockState>, props)`,
 * or a reflective write of the patched `stateSupplier` field into a stair constructed with a
 * stand-in state. That keeps stairs whose base block registers later working. NeoForge 1.21.1's
 * StairBlock stores the base at construction; per the 1.21.1 bytecode it is read back only by
 * `getExplosionResistance()` and by the codec's `base_state` getter. The lazy contract is kept by a
 * nested subclass constructed on AIR (the state the legacy code already passed), keeps the
 * original supplier, and overrides exactly those two reads.
 *
 * Covered owners: StairBlock(BlockState, Properties) and WeatheringCopperStairBlock(WeatherState,
 * BlockState, Properties). The supplier must be a source-declared `Supplier<BlockState>` local or
 * parameter, or a lambda.
 */
internal fun StructuralRefactorPass.migrateLegacySupplierStairs(source: String): String {
    if (!source.contains("StairBlock")) return source
    var result = source
    var masked = JavaSourceText.maskCommentsAndLiterals(result)
    val suppliers = Regex("""\bSupplier\s*<\s*(?:net\.minecraft\.world\.level\.block\.state\.)?BlockState\s*>\s+([A-Za-z_$][\w$]*)\s*[=;,)]""")
        .findAll(masked).map { it.groupValues[1] }.toSet()
    fun isSupplier(expression: String): Boolean = expression.trim() in suppliers ||
        Regex("""^\(\s*\)\s*->""").containsMatchIn(expression.trim())
    val used = linkedSetOf<String>()
    val edits = mutableListOf<Pair<IntRange, String>>()

    // a stair constructed on a stand-in state whose stateSupplier is written reflectively right after
    val reflective = Regex(
        """\b([A-Za-z_$][\w$.]*)\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+((?:net\.minecraft\.world\.level\.block\.)?(?:WeatheringCopperStairBlock|StairBlock))\s*\("""
    )
    for (match in reflective.findAll(masked)) {
        val variable = match.groupValues[2]
        val owner = match.groupValues[3].substringAfterLast('.')
        val open = match.range.last
        val close = JavaSourceText.matchingParen(masked, open)
        if (close < 0) continue
        val declarationEnd = masked.indexOf(';', close)
        if (declarationEnd < 0) continue
        val write = Regex(
            """ObfuscationReflectionHelper\s*\.\s*setPrivateValue\s*\(\s*(?:[\w.]+\.)?StairBlock\.class\s*,\s*${Regex.escape(variable)}\s*,"""
        ).find(masked, declarationEnd) ?: continue
        val between = masked.substring(declarationEnd + 1, write.range.first)
        if (between.isNotBlank()) continue
        val writeOpen = masked.indexOf('(', write.range.first)
        val writeClose = JavaSourceText.matchingParen(masked, writeOpen)
        if (writeClose < 0) continue
        val writeArgs = JavaSourceText.splitTopLevelArgs(result.substring(writeOpen + 1, writeClose)).map { it.trim() }
        if (writeArgs.size != 4 || writeArgs[3] != "\"stateSupplier\"" || !isSupplier(writeArgs[2])) continue
        val writeEnd = masked.indexOf(';', writeClose)
        if (writeEnd < 0 || masked.substring(writeClose + 1, writeEnd).isNotBlank()) continue
        val args = JavaSourceText.splitTopLevelArgs(result.substring(open + 1, close)).map { it.trim() }
        val stateIndex = if (owner == "WeatheringCopperStairBlock") 1 else 0
        if (args.size != stateIndex + 2) continue
        val helper = helperName(owner)
        used += owner
        val replaced = args.toMutableList().also { it[stateIndex] = writeArgs[2] }
        val lineStart = result.lastIndexOf('\n', write.range.first).let { if (it < 0) 0 else it + 1 }
        val lineEnd = result.indexOf('\n', writeEnd).let { if (it < 0) writeEnd + 1 else it + 1 }
        val removal = if (result.substring(lineStart, write.range.first).isBlank() && result.substring(writeEnd + 1, lineEnd).isBlank()) {
            lineStart until lineEnd
        } else {
            write.range.first..writeEnd
        }
        edits += removal to ""
        edits += match.groups[3]!!.range.first..close to "$helper(${replaced.joinToString(", ")})"
    }

    // a stair constructed straight from a supplier (the Forge-only constructor)
    for (match in Regex("""\bnew\s+((?:net\.minecraft\.world\.level\.block\.)?StairBlock)\s*\(""").findAll(masked)) {
        if (edits.any { (range, _) -> match.groups[1]!!.range.first in range }) continue
        val open = match.range.last
        val close = JavaSourceText.matchingParen(masked, open)
        if (close < 0) continue
        val args = JavaSourceText.splitTopLevelArgs(result.substring(open + 1, close)).map { it.trim() }
        if (args.size != 2 || !isSupplier(args[0])) continue
        used += "StairBlock"
        edits += match.groups[1]!!.range.first..close to "${helperName("StairBlock")}(${args.joinToString(", ")})"
    }
    if (edits.isEmpty()) return source
    edits.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
        result = result.replaceRange(range, replacement)
    }

    // the helpers become members of the outermost type of the file
    masked = JavaSourceText.maskCommentsAndLiterals(result)
    val typeOpen = Regex("""\b(?:class|enum|interface|record)\s+[A-Za-z_$][\w$]*[^{]*\{""").find(masked) ?: return source
    val typeClose = JavaSourceText.matchingBrace(masked, typeOpen.range.last)
    if (typeClose < 0) return source
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val members = used.filter { !Regex("""\bclass\s+${helperName(it)}\b""").containsMatchIn(masked) }
        .joinToString("") { helperSource(it, newline) }
    return result.substring(0, typeClose) + members + result.substring(typeClose)
}

private fun helperName(owner: String): String = "ModPorterSupplier$owner"

private fun helperSource(owner: String, nl: String): String {
    val name = helperName(owner)
    val state = "net.minecraft.world.level.block.state.BlockState"
    val supplier = "java.util.function.Supplier<$state>"
    return if (owner == "WeatheringCopperStairBlock") {
        listOf(
            "",
            "    /** A 1.20.1 lazy-base weathering copper stair: built on AIR, base read from the supplier. */",
            "    private static final class $name extends net.minecraft.world.level.block.WeatheringCopperStairBlock {",
            "        private static final com.mojang.serialization.MapCodec<net.minecraft.world.level.block.WeatheringCopperStairBlock> CODEC =",
            "            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(",
            "                net.minecraft.world.level.block.WeatheringCopper.WeatherState.CODEC.fieldOf(\"weathering_state\")",
            "                    .forGetter(net.minecraft.world.level.block.ChangeOverTimeBlock::getAge),",
            "                $state.CODEC.fieldOf(\"base_state\").forGetter(block -> (($name) block).modporterBaseState()),",
            "                propertiesCodec()",
            "            ).apply(instance, (weatherState, baseState, properties) -> new $name(weatherState, () -> baseState, properties)));",
            "        private final $supplier modporterBaseStateSupplier;",
            "",
            "        $name(net.minecraft.world.level.block.WeatheringCopper.WeatherState weatherState, $supplier baseState, Properties properties) {",
            "            super(weatherState, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), properties);",
            "            this.modporterBaseStateSupplier = baseState;",
            "        }",
            "",
            "        private $state modporterBaseState() {",
            "            return modporterBaseStateSupplier.get();",
            "        }",
            "",
            "        @Override",
            "        public float getExplosionResistance() {",
            "            return modporterBaseState().getBlock().getExplosionResistance();",
            "        }",
            "",
            "        @Override",
            "        public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.WeatheringCopperStairBlock> codec() {",
            "            return CODEC;",
            "        }",
            "    }",
            ""
        ).joinToString(nl)
    } else {
        listOf(
            "",
            "    /** A 1.20.1 lazy-base stair: built on AIR, base read from the supplier. */",
            "    private static final class $name extends net.minecraft.world.level.block.StairBlock {",
            "        private static final com.mojang.serialization.MapCodec<$name> CODEC =",
            "            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(",
            "                $state.CODEC.fieldOf(\"base_state\").forGetter($name::modporterBaseState),",
            "                propertiesCodec()",
            "            ).apply(instance, (baseState, properties) -> new $name(() -> baseState, properties)));",
            "        private final $supplier modporterBaseStateSupplier;",
            "",
            "        $name($supplier baseState, Properties properties) {",
            "            super(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), properties);",
            "            this.modporterBaseStateSupplier = baseState;",
            "        }",
            "",
            "        private $state modporterBaseState() {",
            "            return modporterBaseStateSupplier.get();",
            "        }",
            "",
            "        @Override",
            "        public float getExplosionResistance() {",
            "            return modporterBaseState().getBlock().getExplosionResistance();",
            "        }",
            "",
            "        @Override",
            "        public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.StairBlock> codec() {",
            "            return CODEC;",
            "        }",
            "    }",
            ""
        ).joinToString(nl)
    }
}
