package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class GradleJavaVersionMigration {
    private data class Rule(val pattern: Regex, val replacement: String)

    private val rules = listOf(
        Rule(Regex("""JavaLanguageVersion\.of\s*\(\s*17\s*\)"""), "JavaLanguageVersion.of(21)"),
        Rule(Regex("""JavaVersion\.VERSION_17\b"""), "JavaVersion.VERSION_21"),
        Rule(Regex("""JavaVersion\.toVersion\s*\(\s*17\s*\)"""), "JavaVersion.toVersion(21)"),
        Rule(Regex("""\b(options\s*\.\s*release\s*=\s*)17\b"""), "$1" + "21"),
        Rule(Regex("""\b(options\s*\.\s*release\s*\.\s*set\s*\(\s*)17(\s*\))"""), "$1" + "21" + "$2"),
        Rule(Regex("""\b((?:source|target)Compatibility\s*=\s*)17\b"""), "$1" + "21")
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        Files.walk(projectDir).use { paths ->
            paths.filter { path ->
                path.isRegularFile() &&
                    (path.fileName.toString().endsWith(".gradle") || path.fileName.toString().endsWith(".gradle.kts")) &&
                    isProjectBuildScript(projectDir, path)
            }.sorted().forEach { file ->
                val original = file.readText()
                val executable = executableMask(original)
                val edits = rules.flatMap { rule ->
                    rule.pattern.findAll(executable).map { match ->
                        Triple(match.range, match.value, rule.pattern.replace(match.value, rule.replacement))
                    }.toList()
                }.toMutableList()
                val quotedCompatibility = Regex(
                    """\b((?:source|target)Compatibility\s*=\s*)(["'])17\2"""
                )
                quotedCompatibility.findAll(original).forEach { match ->
                    val executablePrefix = executable.substring(
                        match.range.first,
                        match.range.first + match.groupValues[1].length
                    )
                    if (executablePrefix.trimEnd() == match.groupValues[1].trimEnd()) {
                        edits += Triple(
                            match.range,
                            match.value,
                            match.groupValues[1] + match.groupValues[2] + "21" + match.groupValues[2]
                        )
                    }
                }
                edits.sortByDescending { it.first.first }
                if (edits.isEmpty()) return@forEach

                var migrated = original
                for ((range, before, after) in edits) {
                    migrated = migrated.replaceRange(range, after)
                    changes.add(
                        Change(
                            file = file,
                            line = original.lineNumberAt(range.first),
                            description = "Update Gradle Java target from 17 to 21",
                            before = before,
                            after = after,
                            confidence = Confidence.HIGH,
                            ruleId = "build-java-version"
                        )
                    )
                }
                if (!dryRun) file.writeText(migrated)
            }
        }
        return changes
    }

    private fun isProjectBuildScript(projectDir: Path, file: Path): Boolean {
        val relative = projectDir.relativize(file)
        return relative.none { segment ->
            segment.toString() in setOf(".git", ".gradle", "build", "out", "node_modules")
        }
    }

    private fun executableMask(source: String): String {
        val masked = source.toCharArray()
        var index = 0
        var blockComment = false
        var quote: Char? = null
        var tripleQuote = false

        fun mask(at: Int) {
            if (masked[at] != '\n' && masked[at] != '\r') masked[at] = ' '
        }

        while (index < source.length) {
            if (blockComment) {
                if (source.startsWith("*/", index)) {
                    mask(index)
                    mask(index + 1)
                    index += 2
                    blockComment = false
                } else {
                    mask(index++)
                }
                continue
            }

            if (quote != null) {
                val delimiter = if (tripleQuote) "$quote$quote$quote" else quote.toString()
                if (source.startsWith(delimiter, index)) {
                    repeat(delimiter.length) { mask(index + it) }
                    index += delimiter.length
                    quote = null
                    tripleQuote = false
                } else if (!tripleQuote && source[index] == '\\' && index + 1 < source.length) {
                    mask(index++)
                    mask(index++)
                } else {
                    mask(index++)
                }
                continue
            }

            when {
                source.startsWith("//", index) -> {
                    while (index < source.length && source[index] != '\n' && source[index] != '\r') mask(index++)
                }
                source.startsWith("/*", index) -> {
                    mask(index)
                    mask(index + 1)
                    index += 2
                    blockComment = true
                }
                source.startsWith("'''", index) || source.startsWith("\"\"\"", index) -> {
                    quote = source[index]
                    tripleQuote = true
                    repeat(3) { mask(index + it) }
                    index += 3
                }
                source[index] == '\'' || source[index] == '"' -> {
                    quote = source[index]
                    mask(index++)
                }
                else -> index++
            }
        }
        return masked.concatToString()
    }

    private fun String.lineNumberAt(offset: Int): Int =
        take(offset.coerceAtMost(length)).count { it == '\n' } + 1
}
