package com.modporter.core.transforms.build

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Dependency statements a Forge build writes in shapes that cannot be replaced as a whole:
 * ForgeGradle `mod*` configurations nested inside wrappers, and `jarJar(...)` wrapped notations
 * whose coordinates move to their NeoForge artifacts while the wrapper, its version constraints
 * and the author's `${property}` placeholders stay (ModDev accepts the same `jarJar` forms, see
 * Create 6's build). Whole-statement replacement stays with [BuildSystemPass.resolveDependencies].
 */
internal object GradleDependencyNotations {
    /** Statement starts that declare dependencies inside a Gradle `dependencies {}` block. */
    val STATEMENT_KEYWORDS = listOf(
        "compileOnlyApi", "compileOnly", "runtimeOnly", "implementation", "api",
        "annotationProcessor", "jarJar", "def "
    )

    private val forgeModConfigurations = linkedMapOf(
        "modCompileOnlyApi" to "compileOnlyApi",
        "modCompileOnly" to "compileOnly",
        "modRuntimeOnly" to "runtimeOnly",
        "modImplementation" to "implementation",
        "modApi" to "api",
    )

    private val quotedNotation = Regex("""(["'])([^"'\s:]+):([^"'\s:]+)(?::([^"'\s]+))?\1""")

    data class ConfigurationRename(val from: String, val to: String, val line: Int)

    data class NotationRewrite(val before: String, val after: String, val line: Int)

    /** Rewritten content, the notation edits made, and the repositories of every known dependency kept in place. */
    data class WrappedNotationResult(val content: String, val rewrites: List<NotationRewrite>, val mavenUrls: Set<String>)

    /** True when [trimmedLine] begins a dependency statement (keyword followed by a non-identifier). */
    fun startsDependencyStatement(trimmedLine: String): Boolean =
        STATEMENT_KEYWORDS.any { keyword ->
            trimmedLine.startsWith(keyword) &&
                (keyword.endsWith(" ") || trimmedLine.getOrNull(keyword.length)?.let { it.isLetterOrDigit() || it == '_' } != true)
        }

    /** ForgeGradle's `mod*` configurations are the plain configurations on ModDev, wherever they appear. */
    fun renameForgeModConfigurations(content: String): Pair<String, List<ConfigurationRename>> {
        val renames = mutableListOf<ConfigurationRename>()
        val pattern = Regex("""\b(${forgeModConfigurations.keys.joinToString("|")})\b(?=\s*\()""")
        val result = pattern.replace(content) { match ->
            val to = forgeModConfigurations.getValue(match.value)
            renames += ConfigurationRename(match.value, to, lineNumber(content, match.range.first))
            to
        }
        return result to renames
    }

    /** A statement whose notation sits inside a `jarJar(...)` wrapper or carries `${property}` placeholders. */
    fun isWrappedOrTemplated(statement: String): Boolean =
        statement.contains("jarJar(") || statement.contains("\${")

    /** True when one of the statement's notations already names a NeoForge artifact of a known dependency. */
    fun isOnTargetArtifact(statement: String, resolver: DependencyResolver): Boolean =
        quotedNotation.findAll(statement).any { match ->
            val known = resolver.knownDependencyFor(match.groupValues[2], match.groupValues[3]) ?: return@any false
            known.artifactRenames.any { rename -> match.groupValues[3].startsWith(rename.to) }
        }

    /**
     * Rewrites the quoted notations of wrapped or templated statements in place: the artifact moves
     * through the known dependency's [ArtifactRename]s and a literal version becomes the target
     * version; placeholders are left for the version-property migration.
     */
    fun rewriteWrappedKnownNotations(content: String, resolver: DependencyResolver): WrappedNotationResult {
        val rewrites = mutableListOf<NotationRewrite>()
        val mavenUrls = linkedSetOf<String>()
        val edits = mutableListOf<Pair<IntRange, String>>()
        for (match in quotedNotation.findAll(content)) {
            val statement = enclosingStatement(content, match.range.first)
            if (!isWrappedOrTemplated(statement)) continue
            val group = match.groupValues[2]
            val artifact = match.groupValues[3]
            val rest = match.groupValues[4]
            val known = resolver.knownDependencyFor(group, artifact) ?: continue
            if (known.status != "available" || known.neoforgeCoords.isEmpty()) continue
            known.mavenUrl?.let(mavenUrls::add)
            if (known.artifactRenames.any { artifact.startsWith(it.to) }) continue
            val rename = known.artifactRenames.firstOrNull { artifact.startsWith(it.from) }
            val targetArtifact = rename?.let { it.to + artifact.removePrefix(it.from) } ?: artifact
            val targetVersion = known.neoforgeCoords
                .map { coord -> coord.coord.split(':').getOrNull(2) }
                .distinct()
                .singleOrNull()
            val targetRest = when {
                rest.isEmpty() -> ""
                rest.contains("\${") || targetVersion == null -> ":$rest"
                else -> ":$targetVersion" + rest.substringAfter(':', "").let { if (it.isEmpty()) "" else ":$it" }
            }
            val quote = match.groupValues[1]
            val after = "$quote$group:$targetArtifact$targetRest$quote"
            if (after == match.value) continue
            edits += match.range to after
            rewrites += NotationRewrite(match.value, after, lineNumber(content, match.range.first))
        }
        if (edits.isEmpty()) return WrappedNotationResult(content, emptyList(), mavenUrls)
        val result = StringBuilder(content)
        edits.sortedByDescending { it.first.first }.forEach { (range, text) -> result.replace(range.first, range.last + 1, text) }
        return WrappedNotationResult(result.toString(), rewrites, mavenUrls)
    }

    /** Scripts pulled in with `apply from:` / `apply(from = ...)`, resolved against the build file's directory. */
    fun appliedScripts(buildText: String, buildDir: Path): List<Path> =
        Regex("""\bapply\s*\(?\s*from\s*[:=]\s*["']([^"']+)["']""")
            .findAll(buildText)
            .map { buildDir.resolve(it.groupValues[1].removePrefix("./")) }
            .filter { it.exists() }
            .distinct()
            .toList()

    /**
     * The dependency statement around [index]: from the nearest line above that starts one (an
     * empty line or a block brace ends the search with no statement) to the end of the line where
     * the parentheses opened since then close again.
     */
    private fun enclosingStatement(content: String, index: Int): String {
        var start = lineStart(content, index)
        while (true) {
            val lineText = content.substring(start, lineEnd(content, start)).trim()
            if (startsDependencyStatement(lineText)) break
            if (lineText.isEmpty() || lineText.endsWith("{") || lineText.endsWith("}") || start == 0) return ""
            // start - 1 is the newline that ends the previous line; search before it
            start = lineStart(content, start - 2)
        }
        var end = start
        var depth = 0
        while (end < content.length) {
            when (content[end]) {
                '(' -> depth++
                ')' -> depth--
                '\n' -> if (depth <= 0 && end >= index) break
            }
            end++
        }
        return content.substring(start, end)
    }

    private fun lineStart(content: String, index: Int): Int = content.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }

    private fun lineEnd(content: String, index: Int): Int = content.indexOf('\n', index).let { if (it < 0) content.length else it }

    private fun lineNumber(content: String, index: Int): Int = content.substring(0, index).count { it == '\n' } + 1
}
