package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Dependency methods whose NeoForge 1.21.1 build gained a `HolderLookup.Provider` parameter
 * (`mappings/forge2neo/library-provider-parameters.json`, from javap of both jars). A call to one of
 * them is provider evidence like an `ItemStack#save` call, and gets the provider in scope once the
 * surrounding method has one.
 */
internal object LibraryProviderParameters {
    private const val RESOURCE = "/mappings/forge2neo/library-provider-parameters.json"

    @Serializable
    data class Method(
        val owner: String,
        val name: String,
        val forgeParameters: List<String>,
        val providerIndex: Int,
        val notes: String = ""
    ) {
        val ownerSimpleName: String get() = owner.substringAfterLast('.')
        val callPattern: Regex get() = Regex("""(?<![\w$])(?:${Regex.escape(owner)}|${Regex.escape(ownerSimpleName)})\.${Regex.escape(name)}\s*\(""")
    }

    @Serializable
    private data class File(val description: String = "", val methods: List<Method> = emptyList())

    val methods: List<Method> by lazy {
        val stream = LibraryProviderParameters::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyList()
        Json { ignoreUnknownKeys = true }.decodeFromString(File.serializer(), stream.bufferedReader().readText()).methods
    }

    private fun imported(source: String, method: Method): Boolean =
        source.contains("import ${method.owner};") || source.contains("${method.owner}.${method.name}(")

    /** True when [methodText] calls a listed method with its Forge arity (so it needs a provider on 1.21.1). */
    fun callsNeedProvider(methodText: String, source: String): Boolean {
        val masked = JavaSourceText.maskCommentsAndLiterals(methodText)
        return methods.any { method ->
            imported(source, method) && method.callPattern.findAll(masked).any { call ->
                val close = JavaSourceText.matchingParen(masked, call.range.last)
                close > 0 && argumentCount(methodText.substring(call.range.last + 1, close)) == method.forgeParameters.size
            }
        }
    }

    /** Adds the provider [providerAt] the call site to every listed call still using its Forge arity. */
    fun migrateCalls(source: String, providerAt: (source: String, offset: Int) -> String?): String {
        var result = source
        for (method in methods) {
            if (!imported(result, method)) continue
            while (true) {
                val masked = JavaSourceText.maskCommentsAndLiterals(result)
                val call = method.callPattern.findAll(masked).firstOrNull { call ->
                    val close = JavaSourceText.matchingParen(masked, call.range.last)
                    close > 0 && argumentCount(result.substring(call.range.last + 1, close)) == method.forgeParameters.size &&
                        providerAt(result, call.range.first) != null
                } ?: break
                val close = JavaSourceText.matchingParen(masked, call.range.last)
                val args = JavaSourceText.splitTopLevelArgs(result.substring(call.range.last + 1, close)).map { it.trim() }.toMutableList()
                args.add(method.providerIndex.coerceAtMost(args.size), providerAt(result, call.range.first)!!)
                result = result.substring(0, call.range.last + 1) + args.joinToString(", ") + result.substring(close)
            }
        }
        return result
    }

    private fun argumentCount(argumentText: String): Int =
        if (argumentText.isBlank()) 0 else JavaSourceText.splitTopLevelArgs(argumentText).size
}
