package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.modporter.core.transforms.util.JavaSourceText

/**
 * NeoForge 1.21's `RecipeWrapper` is a read-only `RecipeInput` over an `IItemHandlerModifiable`;
 * Forge's was also a `Container` with `setItem`. A wrapper the mod fills through `setItem` keeps
 * its handler in a variable of its own and fills that instead:
 * `RecipeWrapper W = new RecipeWrapper(new ItemStackHandler(n))` becomes the handler plus the
 * wrapper over it, and `W.setItem(i, stack)` becomes `W_HANDLER.setStackInSlot(i, stack)`.
 *
 * Wrapper and handler identities are resolved inside the nearest declaring class and, for locals,
 * the same method or constructor. Repeated field names in sibling classes never share a mapping.
 */
internal object RecipeWrapperHandlers {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val declaration = Regex(
        """(?m)^([ \t]*)((?:(?:public|protected|private|static|final)\s+)*)RecipeWrapper\s+($ID)\s*=\s*new\s+RecipeWrapper\s*\(\s*(new\s+ItemStackHandler\s*\([^()]*\)|$ID)\s*\)\s*;"""
    )
    private val setItem = Regex("""(?<![\w$])($ID)\.setItem\(""")

    private data class Scope(val owner: Int, val callable: Int?)
    private data class Binding(
        val name: String,
        val scope: Scope,
        val field: Boolean,
        val start: Int,
        val type: String
    )
    private data class Wrapper(
        val match: MatchResult,
        val name: String,
        val scope: Scope,
        val field: Boolean,
        val start: Int,
        val handlerExpression: String
    )
    private data class Use(val match: MatchResult, val name: String, val scope: Scope, val start: Int)

    fun migrate(source: String): String {
        if (!source.contains("RecipeWrapper") || !source.contains(".setItem(")) return source
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val unit = parser.parse(source).result.orElse(null) ?: return source
        val imports = unit.imports.filter { !it.isStatic && !it.isAsterisk }.map { it.nameAsString }.toSet()
        if ("net.neoforged.neoforge.items.wrapper.RecipeWrapper" !in imports ||
            "net.neoforged.neoforge.items.ItemStackHandler" !in imports ||
            unit.findAll(TypeDeclaration::class.java).any {
                it.nameAsString in setOf("RecipeWrapper", "ItemStackHandler")
            }
        ) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val wrapperMatches = declaration.findAll(masked).toList()
        if (wrapperMatches.isEmpty()) return source
        val starts = buildList {
            add(0)
            source.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }
        fun start(node: Node): Int =
            node.range.map { starts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
        fun end(node: Node): Int =
            node.range.map { starts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)
        val types = unit.findAll(TypeDeclaration::class.java)
        val methods = unit.findAll(MethodDeclaration::class.java)
        val constructors = unit.findAll(ConstructorDeclaration::class.java)
        fun nearest(nodes: List<out Node>, at: Int): Node? = nodes
            .filter { at in start(it)..end(it) }
            .minByOrNull { end(it) - start(it) }
        fun scopeAt(at: Int, local: Boolean): Scope? {
            val owner = nearest(types, at) ?: return null
            if (!local) return Scope(start(owner), null)
            val callable = nearest(methods, at) ?: nearest(constructors, at) ?: return null
            return Scope(start(owner), start(callable))
        }

        val variables = unit.findAll(VariableDeclarator::class.java)
        val bindings = variables.mapNotNull { variable ->
            val position = start(variable)
            if (position < 0) return@mapNotNull null
            val field = variable.parentNode.orElse(null) is FieldDeclaration
            val scope = scopeAt(position, !field) ?: return@mapNotNull null
            Binding(variable.nameAsString, scope, field, position, variable.type.asString())
        }
        val wrappers = wrapperMatches.mapNotNull { match ->
            val name = match.groupValues[3]
            val variable = variables.singleOrNull { candidate ->
                candidate.nameAsString == name && start(candidate) in match.range
            } ?: return@mapNotNull null
            if (variable.type.asString().substringAfterLast('.') != "RecipeWrapper") return@mapNotNull null
            val field = variable.parentNode.orElse(null) is FieldDeclaration
            val scope = scopeAt(start(variable), !field) ?: return@mapNotNull null
            Wrapper(match, name, scope, field, start(variable), match.groupValues[4])
        }
        if (wrappers.isEmpty()) return source
        val wrappersByScope = wrappers.groupBy { it.scope to it.name }
        val uses = setItem.findAll(masked).mapNotNull { match ->
            val scope = scopeAt(match.range.first, local = true) ?: return@mapNotNull null
            Use(match, match.groupValues[1], scope, match.range.first)
        }.toList()
        fun wrapperFor(use: Use): Wrapper? {
            val local = wrappersByScope[use.scope to use.name].orEmpty()
                .filter { !it.field && it.start < use.start }
            if (local.isNotEmpty()) return local.singleOrNull()
            return wrappersByScope[Scope(use.scope.owner, null) to use.name].orEmpty()
                .filter { it.field }
                .singleOrNull()
        }
        val resolvedUses = uses.mapNotNull { use -> wrapperFor(use)?.let { use to it } }
        if (resolvedUses.isEmpty()) return source

        fun namedHandler(wrapper: Wrapper, name: String): String? {
            if (!wrapper.field) {
                val local = bindings.filter {
                    !it.field &&
                        it.name == name &&
                        it.scope == wrapper.scope &&
                        it.start < wrapper.start &&
                        it.type.substringAfterLast('.') == "ItemStackHandler"
                }
                if (local.isNotEmpty()) return local.singleOrNull()?.name
            }
            return bindings.filter {
                it.field &&
                    it.name == name &&
                    it.scope.owner == wrapper.scope.owner &&
                    it.type.substringAfterLast('.') == "ItemStackHandler"
            }.singleOrNull()?.name
        }
        fun generatedHandler(wrapper: Wrapper): String? {
            val name = "${wrapper.name}_HANDLER"
            val collision = bindings.any { binding ->
                binding.name == name &&
                    if (wrapper.field) binding.field && binding.scope.owner == wrapper.scope.owner
                    else !binding.field && binding.scope == wrapper.scope
            }
            return name.takeUnless { collision }
        }

        val handlers = linkedMapOf<Wrapper, String>()
        for (wrapper in resolvedUses.map { it.second }.distinct()) {
            val expression = wrapper.handlerExpression
            val handler = if (expression.startsWith("new")) {
                generatedHandler(wrapper)
            } else {
                namedHandler(wrapper, expression)
            } ?: continue
            handlers[wrapper] = handler
        }
        if (handlers.isEmpty()) return source
        val edits = mutableListOf<Pair<IntRange, String>>()
        for ((wrapper, handler) in handlers) {
            if (wrapper.handlerExpression.startsWith("new")) {
                val indent = wrapper.match.groupValues[1]
                val modifiers = wrapper.match.groupValues[2]
                val replacement =
                    "$indent${modifiers}ItemStackHandler $handler = ${wrapper.handlerExpression};\n" +
                        "$indent${modifiers}RecipeWrapper ${wrapper.name} = new RecipeWrapper($handler);"
                edits += wrapper.match.range to
                    if (source.contains("\r\n")) replacement.replace("\n", "\r\n") else replacement
            }
        }
        for ((use, wrapper) in resolvedUses) {
            val handler = handlers[wrapper] ?: continue
            edits += use.match.range to "$handler.setStackInSlot("
        }
        return edits.sortedByDescending { it.first.first }
            .fold(source) { text, (range, replacement) -> text.replaceRange(range, replacement) }
    }
}
