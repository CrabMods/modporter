package com.modporter.mapping

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * The structural migration rules as one member-style listing, in pipeline order.
 *
 * The rules live in `StructuralRules<NN>.kt` as `internal fun StructuralRefactorPass.<name>`
 * extensions, numbered in pipeline order. The source-shape guards in this package refer to a rule as
 * `private fun <name>` and read its body up to the member that follows it, so this view folds the
 * rule files back into the pass class: extension declarations become private members and their
 * bodies are indented like members. Lines inside raw strings are left untouched because they hold
 * generated Java text the guards compare literally.
 */
object StructuralRuleSources {
    private val packageDir: Path = Path.of("src/main/kotlin/com/modporter/core/transforms/structural").toAbsolutePath()
    private val passFile: Path = packageDir.resolve("StructuralRefactorPass.kt")
    private val extensionDeclaration = Regex("""^internal fun (<[^>]+>\s+)?(?:StructuralRefactorPass\.)?""")
    private val topLevelDeclaration = Regex("""^internal (val|var|data class|class|enum class|object|sealed class|interface)\b""")
    private val memberDeclaration = Regex("""^    internal (fun|val|var)\b""")

    /** The source of a standalone rule class in the structural package, such as `PotionUtilsStackCalls.kt`. */
    fun file(name: String): String = packageDir.resolve(name).readText()

    val text: String by lazy {
        val core = passFile.readText().trimEnd()
        require(core.endsWith("}")) { "$passFile must end with the class body" }
        val rules = Files.list(packageDir).use { files ->
            files.asSequence()
                .filter { it.extension == "kt" && it.name.startsWith("StructuralRules") }
                .sortedBy { it.name }
                .map { memberView(it.readText()) }
                .toList()
        }
        (asMembers(core.dropLast(1).lines()) + rules).joinToString("\n") + "\n}\n"
    }

    /** A rule file without its package, import and banner lines, indented as class members. */
    private fun memberView(source: String): String {
        val lines = source.lines()
        val first = lines.indexOfFirst { it.isNotBlank() && !it.startsWith("package ") && !it.startsWith("import ") && !it.startsWith("// ") }
        if (first < 0) return ""
        return indentOutsideRawStrings(lines.drop(first)).joinToString("\n")
    }

    private fun indentOutsideRawStrings(lines: List<String>): List<String> {
        var inRawString = false
        return lines.map { line ->
            val rendered = when {
                inRawString || line.isBlank() -> line
                extensionDeclaration.containsMatchIn(line) -> {
                    val declaration = extensionDeclaration.find(line)!!
                    "    private fun " + declaration.groupValues[1] + line.removePrefix(declaration.value)
                }
                topLevelDeclaration.containsMatchIn(line) -> "    private " + line.removePrefix("internal ")
                else -> "    $line"
            }
            if (line.split("\"\"\"").size % 2 == 0) inRawString = !inRawString
            rendered
        }
    }

    private fun asMembers(lines: List<String>): List<String> =
        lines.map { line -> if (memberDeclaration.containsMatchIn(line)) "    private " + line.removePrefix("    internal ") else line }
}
