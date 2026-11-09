package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Override families of a project's own methods: every project type that declares a method of the
 * same name and arity, connected through `extends`/`implements` within the project. Threading a
 * `HolderLookup.Provider` through one declaration of a family means every declaration (abstract
 * ones included) and every override must change together, or the overrides stop overriding.
 *
 * A family is *project rooted* when none of its declarations overrides a method outside the
 * project, whether it says `@Override` or not (a platform supertype that declares the method is
 * looked up in the 1.21.1 class table): those families may be widened; a family that overrides a
 * platform or library method may not.
 */
internal class NbtHookFamilies private constructor(
    private val supertypesByType: Map<String, List<String>>,
    private val hooksByType: Map<String, List<Hook>>
) {
    // callsSuper: the body calls super.<name>(...) with the same arity, so it overrides something
    data class Hook(val name: String, val parameterCount: Int, val isOverride: Boolean, val callsSuper: Boolean = false)

    /** Annotations that describe a method without making anything call it. */
    private val descriptiveAnnotations = setOf(
        "Override", "Nullable", "NotNull", "Nonnull", "Deprecated", "SuppressWarnings", "OnlyIn", "SafeVarargs",
        "Contract", "VisibleForTesting", "Internal", "Experimental", "MustBeInvokedByOverriders", "CheckReturnValue"
    )

    private val subtypesByType: Map<String, List<String>> = supertypesByType
        .flatMap { (type, supertypes) -> supertypes.map { it to type } }
        .groupBy({ it.first }, { it.second })

    fun directSuperOf(type: String): String? = supertypesByType[type]?.firstOrNull()

    fun declares(type: String, name: String, parameterCount: Int): Boolean =
        hooksByType[type].orEmpty().any { it.name == name && it.parameterCount == parameterCount }

    /** True when [a] and [b] are the same type or one extends/implements the other, directly or not. */
    fun related(a: String, b: String): Boolean = a == b || b in ancestorsOf(a) || a in ancestorsOf(b)

    /** Every project type in the hierarchy around [type] that declares the hook, [type] included when it does. */
    private val memberCache = HashMap<Triple<String, String, Int>, Set<String>>()
    fun members(type: String, name: String, parameterCount: Int): Set<String> =
        memberCache.getOrPut(Triple(type, name, parameterCount)) {
            hierarchyAround(type).filterTo(linkedSetOf()) { declares(it, name, parameterCount) }
        }

    fun isProjectRooted(type: String, name: String, parameterCount: Int): Boolean {
        val members = members(type, name, parameterCount)
        if (members.isEmpty()) return false
        return members.all { member ->
            val hook = hooksByType.getValue(member).first { it.name == name && it.parameterCount == parameterCount }
            val projectSuper = ancestorsOf(member).any { declares(it, name, parameterCount) }
            // an override (annotated, calling the super method, or found in the platform class table)
            // of nothing in the project overrides a method outside it
            (projectSuper || !hook.isOverride && !hook.callsSuper) &&
                !overridesPlatformMethod(member, name, parameterCount)
        }
    }

    /**
     * Whether the declaration of [name] in [type] (with method [header]) may take a new parameter:
     * only with its project-rooted family when the project's family scan knows the declaration;
     * private and static methods override nothing.
     */
    fun widenable(type: String, name: String, parameterCount: Int, header: String): Boolean {
        // a framework calls an annotated method by reflection (@LuaFunction, @SubscribeEvent, a mixin
        // handler) with the parameters its signature has now
        val annotations = Regex("""@([\w.]+)""").findAll(header.substringBefore('(')).map { it.groupValues[1].substringAfterLast('.') }
        if (annotations.any { it !in descriptiveAnnotations }) return false
        if (Regex("""\b(?:private|static)\b""").containsMatchIn(header.substringBefore('('))) return true
        if (declares(type, name, parameterCount)) return isProjectRooted(type, name, parameterCount)
        return !header.contains("@Override") && !overridesPlatformMethod(type, name, parameterCount)
    }

    /**
     * Whether a Minecraft/NeoForge class above [type] (past the project's own types) declares the
     * method, so the project's declaration overrides it whether or not it says `@Override`
     * (1.21.1 class table: declared members and supertypes).
     */
    private fun overridesPlatformMethod(type: String, name: String, parameterCount: Int): Boolean =
        (ancestorsOf(type) + type).asSequence()
            .flatMap { supertypesByType[it].orEmpty().asSequence() }
            .filter { it !in supertypesByType }
            .distinct()
            .flatMap { TargetMembers.classesNamed(it).asSequence() }
            .any { TargetMembers.declaresInHierarchy(it, name, parameterCount) }

    private val ancestors = HashMap<String, Set<String>>()
    private fun ancestorsOf(type: String): Set<String> = ancestors.getOrPut(type) { closure(type, supertypesByType) }

    private fun hierarchyAround(type: String): Set<String> {
        val seen = linkedSetOf<String>()
        val frontier = ArrayDeque(listOf(type))
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            if (!seen.add(current)) continue
            // Two unrelated project subclasses of an external type do not form a
            // project override family through that external type alone.
            supertypesByType[current].orEmpty().filter { it in supertypesByType }.forEach(frontier::add)
            subtypesByType[current].orEmpty().forEach(frontier::add)
        }
        return seen
    }

    private fun closure(type: String, edges: Map<String, List<String>>): Set<String> {
        val seen = linkedSetOf<String>()
        val frontier = ArrayDeque(edges[type].orEmpty())
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            if (seen.add(current)) edges[current].orEmpty().forEach(frontier::add)
        }
        return seen
    }

    companion object {
        private val typePattern = Regex(
            """(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)[^{;]*\{"""
        )
        private val supertypePattern = Regex("""\b(?:extends|implements)\s+([^{]+?)(?=\s+(?:extends|implements|permits)\b|\s*\{)""")
        private val methodPattern = Regex(
            """(?m)^[ \t]*((?:@[\w.]+(?:\([^)]*\))?\s*)*)(?:(?:public|protected|private|static|final|abstract|synchronized|default)\s+)*(?:<[^>{};]+>\s*)?[A-Za-z_$][\w$.]*(?:\s*<[^;{}()]*>)?(?:\s*\[\s*\])*\s+([A-Za-z_$][\w$]*)\s*\(([^()]*)\)\s*(?:throws\s+[^{;]+)?[{;]"""
        )
        private val keywords = setOf("if", "for", "while", "switch", "catch", "return", "new", "else", "synchronized", "throw", "case", "do", "try")

        fun build(javaFiles: List<Path>): NbtHookFamilies {
            val supertypes = linkedMapOf<String, List<String>>()
            val hooks = linkedMapOf<String, MutableList<Hook>>()
            for (file in javaFiles) {
                val source = runCatching { file.readText() }.getOrNull() ?: continue
                val masked = JavaSourceText.maskCommentsAndLiterals(source)
                val types = typePattern.findAll(masked).mapNotNull { match ->
                    val open = match.range.last
                    val close = JavaSourceText.matchingBrace(masked, open)
                    if (close < 0) return@mapNotNull null
                    val name = match.groupValues[1]
                    supertypes[name] = supertypePattern.findAll(match.value)
                        .flatMap { it.groupValues[1].split(',').asSequence() }
                        .map { it.trim().substringBefore('<').substringAfterLast('.') }
                        .filter { it.isNotBlank() }
                        .toList()
                    Triple(name, open, close)
                }.toList()
                if (types.isEmpty()) continue
                for (match in methodPattern.findAll(masked)) {
                    val name = match.groupValues[2]
                    if (name in keywords || match.groupValues[3].contains("HolderLookup.Provider")) continue
                    val owner = types.filter { (_, open, close) -> match.range.first in open..close }
                        .minByOrNull { (_, open, close) -> close - open }
                        ?: continue
                    if (name == owner.first) continue
                    val parameters = match.groupValues[3].trim()
                    val parameterCount = if (parameters.isBlank()) 0 else JavaSourceText.splitTopLevelArgs(parameters).size
                    hooks.getOrPut(owner.first) { mutableListOf() } +=
                        Hook(name, parameterCount, match.groupValues[1].contains("@Override"), callsSuper(masked, match, name, parameterCount))
                }
            }
            return NbtHookFamilies(supertypes, hooks)
        }

        /** Whether the body of the declaration [match] calls `super.name(...)` with [parameterCount] arguments. */
        private fun callsSuper(masked: String, match: MatchResult, name: String, parameterCount: Int): Boolean {
            if (!match.value.endsWith("{")) return false
            val close = JavaSourceText.matchingBrace(masked, match.range.last)
            if (close < 0) return false
            val body = masked.substring(match.range.last, close)
            return Regex("""\bsuper\s*\.\s*${Regex.escape(name)}\s*\(""").findAll(body).any { call ->
                val end = JavaSourceText.matchingParen(body, call.range.last)
                end > 0 && body.substring(call.range.last + 1, end).let { args ->
                    (if (args.isBlank()) 0 else JavaSourceText.splitTopLevelArgs(args).size) == parameterCount
                }
            }
        }
    }
}
