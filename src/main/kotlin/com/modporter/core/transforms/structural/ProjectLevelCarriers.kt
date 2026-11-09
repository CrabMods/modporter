package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The project's own classes that carry a `Level` in a public field or zero-argument accessor
 * (Create's `MovementContext.world`, a `getLevel()` on a behaviour context, ...). A method with
 * such a parameter already has a registry provider in `parameter.world.registryAccess()`, so it
 * is not widened with a `HolderLookup.Provider` of its own.
 */
internal object ProjectLevelCarriers {
    private val typePattern = Regex(
        """(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*(?:class|record)\s+([A-Za-z_$][\w$]*)[^{;]*\{"""
    )
    private val levelTypes = """(?:net\.minecraft\.(?:world\.level|server\.level|client\.multiplayer)\.)?(?:Level|ServerLevel|ClientLevel)"""
    private val fieldPattern = Regex("""(?m)^[ \t]*public\s+(?!static\b)(?:final\s+)?$levelTypes\s+([A-Za-z_$][\w$]*)\s*[;=]""")
    private val accessorPattern = Regex("""(?m)^[ \t]*public\s+(?!static\b)(?:final\s+)?$levelTypes\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{""")

    /** Simple class name to the member access (`.world` or `.getLevel()`) that yields its level. */
    fun collect(javaFiles: List<Path>): Map<String, String> {
        val carriers = linkedMapOf<String, String>()
        for (file in javaFiles) {
            val source = runCatching { file.readText() }.getOrNull() ?: continue
            if (!source.contains("Level")) continue
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val types = typePattern.findAll(masked).mapNotNull { match ->
                val close = JavaSourceText.matchingBrace(masked, match.range.last)
                if (close < 0) null else Triple(match.groupValues[1], match.range.last, close)
            }.toList()
            fun ownerOf(offset: Int): String? = types
                .filter { (_, open, close) -> offset in open..close }
                .minByOrNull { (_, open, close) -> close - open }
                ?.first
            fieldPattern.findAll(masked).forEach { match ->
                ownerOf(match.range.first)?.let { carriers.putIfAbsent(it, ".${match.groupValues[1]}") }
            }
            accessorPattern.findAll(masked).forEach { match ->
                ownerOf(match.range.first)?.let { carriers.putIfAbsent(it, ".${match.groupValues[1]}()") }
            }
        }
        return carriers
    }
}
