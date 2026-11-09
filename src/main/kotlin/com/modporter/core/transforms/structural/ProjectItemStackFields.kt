package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The `ItemStack` fields each project class declares, so a qualified receiver such as
 * `player.spawnedItemEffects` is known to be a stack when `player` is typed by a project class.
 */
internal object ProjectItemStackFields {
    private val typePattern = Regex(
        """(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*(?:class|record|enum)\s+([A-Za-z_$][\w$]*)[^{;]*\{"""
    )
    private val fieldPattern = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private|static|final|transient|volatile)\s+)*(?:net\.minecraft\.world\.item\.)?ItemStack\s+([A-Za-z_$][\w$]*)\s*[;=,]"""
    )

    /** Simple class name to the names of its `ItemStack` fields. */
    fun collect(javaFiles: List<Path>): Map<String, Set<String>> {
        val fields = linkedMapOf<String, MutableSet<String>>()
        for (file in javaFiles) {
            val source = runCatching { file.readText() }.getOrNull() ?: continue
            if (!source.contains("ItemStack")) continue
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val types = typePattern.findAll(masked).mapNotNull { match ->
                val close = JavaSourceText.matchingBrace(masked, match.range.last)
                if (close < 0) null else Triple(match.groupValues[1], match.range.last, close)
            }.toList()
            fieldPattern.findAll(masked).forEach { match ->
                val owner = types
                    .filter { (_, open, close) -> match.range.first in open..close }
                    .minByOrNull { (_, open, close) -> close - open }
                    ?.first ?: return@forEach
                fields.getOrPut(owner) { linkedSetOf() }.add(match.groupValues[1])
            }
        }
        return fields
    }
}
