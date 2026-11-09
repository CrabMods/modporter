package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * `CreativeModeTab.Output#accept` takes an `ItemLike` or an `ItemStack` (1.21.1 member table).
 * NeoForge 21.1's `DeferredItem` and `DeferredBlock` implement `ItemLike`, but a plain
 * `DeferredHolder` (what `RegistryObject` becomes) does not (javap of neoforge-21.1.86), so a
 * holder handed to a creative tab output is unwrapped with `.get()`.
 *
 * Both sides need evidence: the receiver is a `CreativeModeTab.Output` or
 * `BuildCreativeModeTabContentsEvent` parameter (or the output parameter of a `displayItems`
 * lambda), and the argument is a field the project declares as `DeferredHolder`/`RegistryObject`,
 * named bare in its own class or through a static import, or qualified by its class.
 */
internal object CreativeTabHolderArguments {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val holderField = Regex("""\b(?:DeferredHolder|RegistryObject)\s*<[^;=]*>\s+($ID)\s*=""")
    private val outputParameter = Regex("""\b(?:CreativeModeTab\.Output|Output|BuildCreativeModeTabContentsEvent)\s+($ID)\s*[,)]""")
    private val displayItemsLambda = Regex("""\.displayItems\s*\(\s*\(\s*$ID\s*,\s*($ID)\s*\)\s*->""")

    /** Simple class name to the holder fields it declares. */
    fun collect(javaFiles: List<Path>): Map<String, Set<String>> {
        val result = linkedMapOf<String, MutableSet<String>>()
        for (file in javaFiles) {
            val source = runCatching { file.readText() }.getOrNull() ?: continue
            if (!source.contains("DeferredHolder") && !source.contains("RegistryObject")) continue
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val owner = Regex("""\b(?:class|interface|enum)\s+($ID)""").find(masked)?.groupValues?.get(1) ?: continue
            holderField.findAll(masked).forEach { result.getOrPut(owner) { linkedSetOf() }.add(it.groupValues[1]) }
        }
        return result
    }

    fun migrate(source: String, holders: Map<String, Set<String>>): String {
        if (holders.isEmpty() || !source.contains(".accept(")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val outputs = (outputParameter.findAll(masked) + displayItemsLambda.findAll(masked)).map { it.groupValues[1] }.toSet()
        if (outputs.isEmpty()) return source
        val owner = Regex("""\b(?:class|interface|enum)\s+($ID)""").find(masked)?.groupValues?.get(1)
        val bare = buildSet {
            owner?.let { holders[it] }?.let(::addAll)
            Regex("""(?m)^\s*import\s+static\s+[\w.]*\.($ID)\.($ID|\*)\s*;""").findAll(masked).forEach { import ->
                val fields = holders[import.groupValues[1]].orEmpty()
                if (import.groupValues[2] == "*") addAll(fields) else if (import.groupValues[2] in fields) add(import.groupValues[2])
            }
        }
        val receiver = outputs.joinToString("|") { Regex.escape(it) }
        val call = Regex("""(?<![\w$.])(?:$receiver)\.accept\(\s*(?:($ID)\.)?($ID)\s*(?=[,)])""")
        val edits = call.findAll(masked).mapNotNull { match ->
            val qualifier = match.groupValues[1]
            val name = match.groupValues[2]
            val isHolder = if (qualifier.isEmpty()) name in bare else holders[qualifier]?.contains(name) == true
            if (!isHolder) null else match.range.last + 1 to ".get()"
        }.toList()
        return edits.asReversed().fold(source) { text, (offset, value) -> text.substring(0, offset) + value + text.substring(offset) }
    }
}
