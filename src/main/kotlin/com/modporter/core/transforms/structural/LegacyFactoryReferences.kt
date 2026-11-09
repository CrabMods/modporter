package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Factories that left the platform between 1.20.1 and 1.21.1:
 *
 * - Forge's `RegistryObject.create(name, registry)` is NeoForge's `DeferredHolder.create(key, name)`;
 *   a built-in registry `BuiltInRegistries.X` names its key `Registries.X` (both fields in the
 *   1.21.1 class table).
 * - `ResourceLocation`'s public constructors are gone (only a private one remains); a
 *   `ResourceLocation::new` handed a single string (`computeIfAbsent(key, ...)`, `.map(...)`) was the
 *   parsing constructor, which is `ResourceLocation::parse`.
 */
internal object LegacyFactoryReferences {
    private const val ID = """[A-Za-z_$][\w$]*"""

    fun migrate(source: String): String {
        var result = source
        if (result.contains("RegistryObject.create(")) result = registryObjectCreates(result)
        if (result.contains(".getDelegateOrThrow(")) result = delegateOrThrow(result)
        // 1.20.1's RecordItem is gone: a music disc is an item with the jukebox-playable component
        if (result.contains("instanceof RecordItem")) {
            val discs = Regex("""([\w$.]+(?:\(\))?)\.getItem\(\)\s+instanceof\s+(?:net\.minecraft\.world\.item\.)?RecordItem\b(?!\s+\w)""")
            val updated = discs.replace(result) { "${it.groupValues[1]}.has(net.minecraft.core.component.DataComponents.JUKEBOX_PLAYABLE)" }
            if (updated != result) {
                result = updated
                if (!Regex("""(?<![\w$.])RecordItem\b""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result).replace(Regex("""(?m)^\s*import\s+[^;]+;"""), ""))) {
                    result = result.replace(Regex("""(?m)^\s*import\s+net\.minecraft\.world\.item\.RecordItem\s*;\s*\r?\n"""), "")
                }
            }
        }
        if (result.contains("new EntityDimensions(")) result = entityDimensions(result)
        if (result.contains("ResourceLocation::new")) {
            result = Regex("""(\bcomputeIfAbsent\s*\([^;]*?,\s*|\.\s*map\s*\(\s*)ResourceLocation::new(\s*\))""").replace(result) {
                "${it.groupValues[1]}ResourceLocation::parse${it.groupValues[2]}"
            }
        }
        return result
    }

    /**
     * 1.20.1's `new EntityDimensions(width, height, fixed)` is a five-component record on 1.21.1
     * (eye height, attachments); the `fixed(w, h)` / `scalable(w, h)` factories of both versions build
     * the same dimensions with the default eye height 1.20.1 derived from the height.
     */
    private fun entityDimensions(source: String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        var result = source
        Regex("""(?<![\w$.])new\s+(?:net\.minecraft\.world\.entity\.)?EntityDimensions\s*\(""").findAll(masked).toList().asReversed().forEach { call ->
            val close = JavaSourceText.matchingParen(masked, call.range.last)
            if (close < 0) return@forEach
            val args = JavaSourceText.splitTopLevelArgs(source.substring(call.range.last + 1, close)).map { it.trim() }
            if (args.size != 3) return@forEach
            val (width, height, fixed) = args
            val factory = { kind: String -> "net.minecraft.world.entity.EntityDimensions.$kind($width, $height)" }
            val replacement = when (fixed) {
                "true" -> factory("fixed")
                "false" -> factory("scalable")
                else -> "($fixed ? ${factory("fixed")} : ${factory("scalable")})"
            }
            result = result.substring(0, call.range.first) + replacement + result.substring(close + 1)
        }
        return result
    }

    /**
     * Forge's `registry.getDelegateOrThrow(x)` returned the registered `Holder.Reference` or threw;
     * on a built-in registry that is `getHolderOrThrow(key)`, the key being `getResourceKey(value)`
     * for a registered value (`getHolder(id).orElseThrow()` for an id). The argument's declared
     * type where the call stands picks the form; an argument of unknown type is left alone.
     */
    private fun delegateOrThrow(source: String): String {
        var result = source
        var cursor = 0
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val call = Regex("""\b((?:net\.minecraft\.core\.registries\.)?BuiltInRegistries\.[A-Z_]+)\s*\.\s*getDelegateOrThrow\s*\(""").find(masked, cursor) ?: break
            val close = JavaSourceText.matchingParen(masked, call.range.last)
            if (close < 0) break
            cursor = close
            val registry = result.substring(call.groups[1]!!.range)
            val argument = result.substring(call.range.last + 1, close).trim()
            if (!Regex("""^$ID$""").matches(argument)) continue
            val type = com.modporter.core.transforms.util.JavaScopedDeclarations.visibleAt(masked, argument, call.range.first)?.type ?: continue
            val replacement = when (type) {
                "ResourceKey" -> "$registry.getHolderOrThrow($argument)"
                "ResourceLocation" -> "$registry.getHolder($argument).orElseThrow()"
                else -> "$registry.getHolderOrThrow($registry.getResourceKey($argument).orElseThrow())"
            }
            result = result.substring(0, call.range.first) + replacement + result.substring(close + 1)
            cursor = call.range.first + replacement.length
        }
        return result
    }

    private fun registryObjectCreates(source: String): String {
        var result = source
        var cursor = 0
        var changed = false
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val start = masked.indexOf("RegistryObject.create(", cursor)
            if (start < 0 || (start > 0 && (masked[start - 1].isLetterOrDigit() || masked[start - 1] == '.'))) {
                if (start < 0) break
                cursor = start + 1
                continue
            }
            val open = start + "RegistryObject.create".length
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) break
            val args = JavaSourceText.splitTopLevelArgs(result.substring(open + 1, close)).map { it.trim() }
            val registry = args.getOrNull(1)?.let { Regex("""^(?:net\.minecraft\.core\.registries\.)?BuiltInRegistries\.([A-Z_]+)$""").find(it) }
            val field = registry?.groupValues?.get(1)
            if (args.size != 2 || field == null || !TargetMembers.hasField("net.minecraft.core.registries.Registries", field)) {
                cursor = close
                continue
            }
            val replacement = "DeferredHolder.create(Registries.$field, ${args[0]})"
            result = result.substring(0, start) + replacement + result.substring(close + 1)
            cursor = start + replacement.length
            changed = true
        }
        if (!changed) return source
        result = JavaSourceText.addImport(result, "net.neoforged.neoforge.registries.DeferredHolder")
        return JavaSourceText.addImport(result, "net.minecraft.core.registries.Registries")
    }
}
