package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Codec helpers DataFixerUpper 7 / 1.20.5 moved, and map/plain codec mismatches left by earlier
 * rewrites (executable code only; comments and strings keep their text):
 *
 * - `ExtraCodecs.lazyInitializedCodec(supplier)` is `Codec.lazyInitialized(supplier)` (a supplier
 *   of `registry.byNameCodec()` is left to [migrateLegacyLazyRegistryCodecs]: NeoForge registries
 *   exist when their holder class initializes);
 * - `ExtraCodecs.validate(codec, check)` is the instance method `codec.validate(check)`;
 * - `Owner.CODEC.codec()` on a platform field the 1.21.1 class table types as a plain `Codec` is
 *   just `Owner.CODEC` (only a `MapCodec` has `codec()`);
 * - a method declared to return `Codec<...>` that returns a field of its file declared `MapCodec`
 *   returns that map codec's `codec()`.
 */
internal fun StructuralRefactorPass.migrateCodecApiMoves(source: String): String {
    if (!source.contains("Codec")) return source
    var result = source
    result = replaceExecutableJavaRegex(result, Regex("""\bExtraCodecs\s*\.\s*lazyInitializedCodec\s*\((?!\s*\(\s*\)\s*->\s*[\w$.]+\.byNameCodec\(\)\s*\))""")) {
        "com.mojang.serialization.Codec.lazyInitialized("
    }
    result = rewriteExtraCodecsValidate(result)
    val owners = PlatformCodecOwners(result)
    result = replaceExecutableJavaRegex(result, Regex("""(?<![\w$.])((?:[A-Za-z_$][\w$]*\.)*([A-Z]\w*))\.CODEC\.codec\(\)""")) { match ->
        val qualifier = match.groupValues[1].removeSuffix(match.groupValues[2]).removeSuffix(".")
        val plain = owners.resolve(qualifier, match.groupValues[2]).let { candidates ->
            candidates.isNotEmpty() && candidates.all { TargetMembers.fieldDescriptor(it, "CODEC") == "Lcom/mojang/serialization/Codec;" }
        }
        if (plain) "${match.groupValues[1]}.CODEC" else match.value
    }
    return returnMapCodecAsCodec(result)
}

/**
 * The platform classes a simple type name in one file can denote. A type the file declares, or a
 * name it neither imports nor qualifies (a same-package type), is the project's own, never a
 * platform class that merely shares its simple name.
 */
private class PlatformCodecOwners(source: String) {
    private val masked = JavaSourceText.maskCommentsAndLiterals(source)
    private val declared = Regex("""\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)""")
        .findAll(masked).map { it.groupValues[1] }.toSet()
    private val imports = Regex("""(?m)^\s*import\s+([\w.]+?)(\.\*)?\s*;""").findAll(masked).toList()
    private val singleImports = imports.filter { it.groupValues[2].isEmpty() }
        .associate { it.groupValues[1].substringAfterLast('.') to it.groupValues[1] }
    private val onDemandImports = imports.filter { it.groupValues[2].isNotEmpty() }.map { it.groupValues[1] }

    fun resolve(qualifier: String, name: String): List<String> {
        if (qualifier.isNotEmpty()) {
            val first = qualifier.substringBefore('.')
            // Outer.Inner: the outer simple name resolves like any other type name
            if (first.first().isUpperCase()) {
                val rest = (qualifier.substringAfter('.', "").split('.').filter { it.isNotEmpty() } + name)
                return resolve("", first).mapNotNull { outer ->
                    "$outer$${rest.joinToString("$")}".takeIf(TargetMembers::hasClass)
                }
            }
            return listOfNotNull(binaryName("$qualifier.$name"))
        }
        if (name in declared) return emptyList()
        singleImports[name]?.let { return listOfNotNull(binaryName(it)) }
        return onDemandImports.mapNotNull { binaryName("$it.$name") }.distinct()
    }

    /** `a.b.Outer.Inner` as the table's binary name `a.b.Outer$Inner`, when the platform has it. */
    private fun binaryName(canonical: String): String? {
        val segments = canonical.split('.')
        for (nested in 0 until segments.size) {
            val packagePart = segments.dropLast(nested + 1)
            val typePart = segments.takeLast(nested + 1)
            val candidate = (if (packagePart.isEmpty()) "" else packagePart.joinToString(".") + ".") +
                typePart.joinToString("$")
            if (TargetMembers.hasClass(candidate)) return candidate
        }
        return null
    }
}

private fun rewriteExtraCodecsValidate(source: String): String {
    var result = source
    var cursor = 0
    while (true) {
        val masked = JavaSourceText.maskCommentsAndLiterals(result)
        val call = Regex("""\bExtraCodecs\s*\.\s*validate\s*\(""").find(masked, cursor) ?: break
        val close = JavaSourceText.matchingParen(masked, call.range.last)
        if (close < 0) break
        val args = JavaSourceText.splitTopLevelArgs(result.substring(call.range.last + 1, close)).map { it.trim() }
        if (args.size != 2) {
            cursor = close
            continue
        }
        val codec = if (Regex("""^[\w$.]+(?:\([^()]*\))?$""").matches(args[0])) args[0] else "(${args[0]})"
        val replacement = "$codec.validate(${args[1]})"
        result = result.substring(0, call.range.first) + replacement + result.substring(close + 1)
        cursor = call.range.first + replacement.length
    }
    return result
}

private fun returnMapCodecAsCodec(source: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    val masked = JavaSourceText.maskCommentsAndLiterals(source)
    val mapCodecFields = Regex("""\bMapCodec\s*<[^;=(){}]*>\s+($id)\s*=""").findAll(masked).map { it.groupValues[1] }.toSet()
    if (mapCodecFields.isEmpty()) return source
    val method = Regex("""(?<![\w$.])(?:com\.mojang\.serialization\.)?Codec\s*<[^;={}()]*>\s+$id\s*\([^()]*\)\s*\{\s*return\s+($id)\s*;""")
    var result = source
    for (match in method.findAll(masked).toList().asReversed()) {
        val field = match.groups[1]!!
        if (field.value !in mapCodecFields) continue
        result = result.substring(0, field.range.last + 1) + ".codec()" + result.substring(field.range.last + 1)
    }
    return result
}
