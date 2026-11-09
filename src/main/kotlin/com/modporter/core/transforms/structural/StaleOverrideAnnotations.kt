package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * An `@Override` method that overrides nothing on 1.21.1 (no project supertype declares its name and
 * arity, and no platform supertype does in the 1.21.1 class table) but that the project calls itself
 * is an ordinary method of the project now, so only its annotation goes: `ProcessingRecipe.getId()`
 * after `Recipe#getId` left the platform.
 *
 * A hook the project never calls stays as it is (the compiler keeps pointing at it: it needs a real
 * migration, not silence), and so does a method whose class has a supertype neither the project nor
 * the platform table knows (a library's), and `java.lang.Object`'s methods every class overrides.
 */
internal object StaleOverrideAnnotations {
    const val RULE_ID = "struct-stale-override-annotation"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val objectMethods = setOf("equals" to 1, "hashCode" to 0, "toString" to 0, "clone" to 0, "finalize" to 0)
    private val keywords = setOf("if", "for", "while", "switch", "catch", "return", "new", "else", "synchronized", "throw", "case", "do", "try")

    private data class Method(val owner: String, val name: String, val arity: Int, val annotationRange: IntRange?)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val supertypes = HashMap<String, List<String>>()
        val methods = HashMap<Path, List<Method>>()
        val declared = HashSet<Triple<String, String, Int>>()
        // annotations before and after @Override; group 1 is the @Override (with its trailing whitespace)
        val declaration = Regex(
            """(?m)^(?:[ \t]*@(?!Override\b)[\w.]+(?:\([^)]*\))?\s+)*[ \t]*(@Override\s+)?(?:@[\w.]+(?:\([^)]*\))?\s+)*""" +
                """(?:(?:public|protected|private|static|final|abstract|synchronized|default)\s+)*(?:<[^>{};]+>\s+)?[A-Za-z_$][\w$.]*(?:\s*<[^;{}()]*>)?(?:\s*\[\s*\])*\s+($ID)\s*\(([^()]*)\)\s*(?:throws\s+[^{;]+)?[{;]"""
        )
        for ((file, source) in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val types = Regex("""\b(?:class|interface|enum|record)\s+($ID)\s*(?:<[^{]*?>)?([^{;]*)\{""").findAll(masked).mapNotNull { match ->
                val close = JavaSourceText.matchingBrace(masked, match.range.last)
                if (close < 0) return@mapNotNull null
                supertypes[match.groupValues[1]] = Regex("""\b(?:extends|implements)\s+([^{]+?)(?=\s+(?:extends|implements|permits)\b|$)""")
                    .findAll(match.groupValues[2])
                    .flatMap { JavaSourceText.splitTopLevelArgs(it.groupValues[1]).asSequence() }
                    .map { it.trim().substringBefore('<').substringAfterLast('.').trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                Triple(match.groupValues[1], match.range.last, close)
            }.toList()
            methods[file] = declaration.findAll(masked).mapNotNull { match ->
                val owner = types.filter { (_, open, close) -> match.range.first in open..close }.minByOrNull { (_, open, close) -> close - open }?.first
                    ?: return@mapNotNull null
                val name = match.groupValues[2]
                if (name == owner || name in keywords) return@mapNotNull null
                val parameters = match.groupValues[3].trim()
                val arity = if (parameters.isEmpty()) 0 else JavaSourceText.splitTopLevelArgs(parameters).size
                declared += Triple(owner, name, arity)
                Method(owner, name, arity, match.groups[1]?.range)
            }.toList()
        }

        fun ancestors(type: String): Set<String> {
            val seen = linkedSetOf<String>()
            val frontier = ArrayDeque(supertypes[type].orEmpty())
            while (frontier.isNotEmpty()) {
                val current = frontier.removeFirst()
                if (seen.add(current)) supertypes[current].orEmpty().forEach(frontier::add)
            }
            return seen
        }
        val masked = sources.values.map { JavaSourceText.maskCommentsAndLiterals(it) }

        val changes = mutableListOf<Change>()
        for ((file, list) in methods) {
            val stale = list.filter { method ->
                if (method.annotationRange == null || (method.name to method.arity) in objectMethods) return@filter false
                val above = ancestors(method.owner)
                if (above.any { type -> Triple(type, method.name, method.arity) in declared }) return@filter false
                // every supertype outside the project must be a platform class the table knows
                val platform = above.filter { it !in supertypes }.map { TargetMembers.classesNamed(it) }
                if (platform.any { it.isEmpty() }) return@filter false
                if (platform.flatten().any { TargetMembers.declaresInHierarchy(it, method.name, method.arity) }) return@filter false
                masked.any { calledIn(it, method.name, method.arity) }
            }
            if (stale.isEmpty()) continue
            var text = sources.getValue(file)
            for (method in stale.sortedByDescending { it.annotationRange!!.first }) {
                text = text.removeRange(method.annotationRange!!)
            }
            if (!dryRun) file.writeText(text)
            changes += Change(file, 1, "@Override on methods that override nothing on 1.21.1 and that the project calls itself",
                "@Override", "(no annotation)", Confidence.HIGH, RULE_ID)
        }
        return changes
    }

    /** Whether [masked] calls `name(...)` with [arity] arguments (qualified or not; declarations excluded). */
    private fun calledIn(masked: String, name: String, arity: Int): Boolean =
        Regex("""(?<![\w$])${Regex.escape(name)}\s*\(""").findAll(masked).any { call ->
            val before = masked.substring(maxOf(0, call.range.first - 60), call.range.first)
            // a declaration has its return type (an identifier, `>` or `]`) right before the name
            val qualified = Regex("""\.\s*$""").containsMatchIn(before)
            if (!qualified && Regex("""[\w$>\]]\s+$""").containsMatchIn(before) && !Regex("""\b(?:return|new|throw|else)\s+$""").containsMatchIn(before)) return@any false
            val close = JavaSourceText.matchingParen(masked, call.range.last)
            if (close < 0) return@any false
            val args = masked.substring(call.range.last + 1, close)
            (if (args.isBlank()) 0 else JavaSourceText.splitTopLevelArgs(args).size) == arity
        }
}
