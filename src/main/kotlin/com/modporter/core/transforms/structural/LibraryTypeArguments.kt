package com.modporter.core.transforms.structural

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Dependency classes whose type-parameter list grew between their Forge 1.20.1 and NeoForge
 * 1.21.1 builds, listed in `library-type-parameters.json` from `javap` of both jars. Registrate's
 * `RegistryEntry<T>` became `RegistryEntry<R, S extends R>` (a `DeferredHolder`), so a usage
 * gains a registry-type argument in front of its old argument: a bounded wildcard is repeated
 * (`? extends DisplaySource` → `? extends DisplaySource, ? extends DisplaySource`), anything else gets the
 * unbounded wildcard the registry type is unknown for (`T` → `?, T`, `?` → `?, ?`).
 */
internal object LibraryTypeArguments {
    private const val RESOURCE = "/mappings/forge2neo/library-type-parameters.json"

    @Serializable
    data class GrownType(
        val binaryName: String,
        val forgeArity: Int,
        val targetArity: Int,
        val prepend: String = "registry-type",
        val notes: String = ""
    ) {
        val simpleName: String get() = binaryName.substringAfterLast('.')
    }

    @Serializable
    private data class TypesFile(val description: String = "", val types: List<GrownType> = emptyList())

    val types: List<GrownType> by lazy {
        val stream = LibraryTypeArguments::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyList()
        Json { ignoreUnknownKeys = true }.decodeFromString(TypesFile.serializer(), stream.bufferedReader().readText()).types
    }

    /**
     * Rewrites every `Name<arg>` of a listed type the source imports (or spells out) to its grown form.
     * With [registries], an argument whose registry is known gets it as the registry-type argument
     * (`RegistryEntry<BeltBlock>` -> `RegistryEntry<net.minecraft.world.level.block.Block, BeltBlock>`;
     * a type variable through its bound): `S extends R` rules out a wildcard there.
     */
    fun migrate(source: String, registries: RegistryElementTypes? = null): String {
        var result = source
        for (type in types) {
            if (type.targetArity != type.forgeArity + 1 || type.forgeArity != 1) continue
            if (!source.contains("import ${type.binaryName};") && !source.contains(type.binaryName)) continue
            result = rewriteUsages(result, type, registries)
        }
        return result
    }

    private fun rewriteUsages(source: String, type: GrownType, registries: RegistryElementTypes?): String {
        val scopes = if (registries != null) JavaTypeParameterScopes(source) else null
        val name = Regex("""(?<![\w$.])(?:${Regex.escape(type.binaryName)}|${Regex.escape(type.simpleName)})\s*<""")
        val builder = StringBuilder()
        var cursor = 0
        while (true) {
            val match = name.find(source, cursor) ?: break
            val openAngle = match.range.last
            val closeAngle = matchingAngle(source, openAngle)
            if (closeAngle < 0) {
                builder.append(source, cursor, match.range.last + 1)
                cursor = match.range.last + 1
                continue
            }
            val arguments = splitTypeArguments(source.substring(openAngle + 1, closeAngle))
            builder.append(source, cursor, openAngle + 1)
            if (arguments.size == type.forgeArity) {
                val registryType = if (registries != null && scopes != null) registryTypeFor(arguments.single(), openAngle, source, registries, scopes) else null
                builder.append(if (registryType != null) "$registryType, ${arguments.single()}" else grownArguments(arguments.single()))
            } else {
                builder.append(source, openAngle + 1, closeAngle)
            }
            cursor = closeAngle
        }
        builder.append(source, cursor, source.length)
        return builder.toString()
    }

    // the registry an argument's objects live in: a type variable's comes from its bound (another
    // type variable is the registry type itself, as in `<R, T extends R>`), a type's from the evidence
    private fun registryTypeFor(argument: String, offset: Int, source: String, registries: RegistryElementTypes, scopes: JavaTypeParameterScopes): String? {
        if (argument.startsWith("?")) return null
        val raw = argument.substringBefore('<').trim()
        scopes.boundOf(raw, offset)?.let { bound ->
            val boundRaw = bound.substringBefore('<').trim()
            if (scopes.isTypeVariable(boundRaw, offset)) return boundRaw
            return registries.registryTypeOf(boundRaw, source)
        }
        if (scopes.isTypeVariable(raw, offset)) return null
        return registries.registryTypeOf(raw, source)
    }

    private fun grownArguments(argument: String): String {
        val trimmed = argument.trim()
        val bounded = Regex("""^\?\s+extends\s+(.+)$""").find(trimmed)
        // `? extends X` -> `? extends X, ? extends X`: the registry slot stays open so an
        // ItemEntry<Item> (ItemProviderEntry<Item, Item>) still fits where ItemLike was asked for.
        return when {
            bounded != null -> "$trimmed, $trimmed"
            else -> "?, $trimmed"
        }
    }

    private fun matchingAngle(source: String, openAngle: Int): Int {
        var depth = 0
        var index = openAngle
        while (index < source.length) {
            when (source[index]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return index
                }
                ';', '{', '}' -> return -1
            }
            index++
        }
        return -1
    }

    private fun splitTypeArguments(text: String): List<String> {
        val arguments = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { index, c ->
            when (c) {
                '<' -> depth++
                '>' -> depth--
                ',' -> if (depth == 0) {
                    arguments += text.substring(start, index)
                    start = index + 1
                }
            }
        }
        arguments += text.substring(start)
        return arguments.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
