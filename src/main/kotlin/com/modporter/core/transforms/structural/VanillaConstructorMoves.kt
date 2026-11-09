package com.modporter.core.transforms.structural

/** Constructor changes verified against the 1.20.1 and 1.21.1 game sources. */
internal fun StructuralRefactorPass.migrateVanillaConstructorMoves(source: String): String {
    var result = source
    if (source.contains("NbtAccounter")) {
        // create(quota) retains the legacy maximum depth of 512.
        for (type in listOf("NbtAccounter", "net.minecraft.nbt.NbtAccounter")) {
            if (type == "NbtAccounter" && !source.contains("import net.minecraft.nbt.NbtAccounter;")) continue
            result = rewriteExecutableJavaNew(result, type) { args ->
                if (args.size == 1) "$type.create(${args.single().trim()})" else null
            }
        }
    }
    if (!source.contains("super(") || !source.contains("DoorBlock")) return result
    // The new expressions are handled separately; a subclass calls the same changed constructor.
    val edits = mutableListOf<Pair<IntRange, String>>()
    val masked = maskJavaCommentsAndLiterals(result)
    for (type in collectJavaClassDeclarations(result)) {
        val base = type.directSuper.substringAfterLast('.')
        if (base !in setOf("DoorBlock", "TrapDoorBlock")) continue
        val owner = "net.minecraft.world.level.block.$base"
        if (type.directSuper != owner && !source.contains("import $owner;") &&
            !source.contains("import net.minecraft.world.level.block.*;")
        ) continue
        for (constructor in javaConstructorRanges(result, type.name)) {
            val propertyParameters = callableParameters(result, constructor.range).mapNotNull { parseJavaParameter(it) }
                .filter { it.first.substringAfterLast('.') == "Properties" }.map { it.second }.toSet()
            if (propertyParameters.isEmpty()) continue
            val call = Regex("""\bsuper\s*\(""").find(masked, constructor.range.first) ?: continue
            if (call.range.first !in constructor.range) continue
            val close = findMatchingParen(masked, call.range.last)
            if (close < 0 || close !in constructor.range) continue
            val args = splitTopLevelJavaArgs(result.substring(call.range.last + 1, close)).map { it.trim() }
            if (args.size == 2 && args[0] in propertyParameters) {
                edits += (call.range.first..close) to "super(${args[1]}, ${args[0]})"
            }
        }
    }
    for ((range, replacement) in edits.sortedByDescending { it.first.first }) {
        result = result.replaceRange(range, replacement)
    }
    return result
}
