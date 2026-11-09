package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.type.Type
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The last structural step: calls that still miss the `HolderLookup.Provider` their target now
 * takes. A method or constructor gained the provider (a 1.21.1 platform signature, or a project
 * family the provider threading widened), and a call the earlier rules could not type kept its old
 * argument list: `super.write(tag, clientPacket)`, a call on a lambda parameter, a platform
 * `serializeNBT()`.
 *
 * The target's declarations decide: those of the receiver's type and its ancestors when the receiver
 * resolves (`super`, `this`, a name declared where the call stands, a lambda parameter a project
 * method declares as `Consumer<X>`-like), or every declaration of the name otherwise. Only when none
 * of them takes the call's argument count, and all that take one more agree on where the provider
 * goes, does the call get the provider in scope (`registryAccessExpressionAt`) at that position.
 */
internal object ProviderArityRepair {
    const val RULE_ID = "struct-provider-arity-repair"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val PROVIDER_DESCRIPTOR = "Lnet/minecraft/core/HolderLookup\$Provider;"
    private const val STATIC_REGISTRIES_PROVIDER =
        "net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(" +
            "net.minecraft.core.registries.BuiltInRegistries.REGISTRY)"

    /** A declaration's parameter count and the provider's index among its parameters (null without one). */
    private data class Shape(
        val count: Int,
        val providerIndex: Int?,
        val parameterTypes: List<String> = emptyList()
    )

    private class Project(
        val methods: Map<String, Map<String, List<Shape>>>, // type -> method name -> shapes
        val constructors: Map<String, List<Shape>>,
        val supertypes: Map<String, List<String>>
    )

    private data class ProviderRootCandidate(
        val file: Path,
        val owner: String,
        val method: JavaMethodRange,
        val parameterCount: Int,
        val providerName: String
    )

    fun migrate(pass: StructuralRefactorPass, projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val originalSources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val index = with(pass) { collectJavaInheritanceIndex(files) }
        val initialProject = collect(originalSources.values)
        val sources = threadSourceProviderRoots(pass, originalSources, initialProject, index)
        val project = collect(sources.values)
        val providerNames = project.methods.values.flatMap { byName -> byName.filterValues { shapes -> shapes.any { it.providerIndex != null } }.keys }.toSet() +
            project.constructors.filterValues { shapes -> shapes.any { it.providerIndex != null } }.keys
        val changes = mutableListOf<Change>()
        for ((file, source) in sources) {
            val relocatedCalls = repairCatnipUnsafeItemComponents(pass, source)
            val contractedCalls = repairRemovedTrailingNullArguments(pass, relocatedCalls, project)
            val repairedCalls = repair(pass, contractedCalls, project, providerNames, index)
            val repairedFluidWrites = repairFluidStackWrites(pass, repairedCalls, index)
            val repairedFluidReads = repairStaticRegistryFluidReads(pass, repairedFluidWrites, index)
            val repaired = repairDetachedBlockEntityLookups(pass, repairedFluidReads)
            if (repaired == originalSources[file]) continue
            if (!dryRun) file.writeText(repaired)
            changes += Change(file, 1, "Calls match their source-proven target arity",
                "stale call arity", "target arity with the provider in scope when required", Confidence.HIGH, RULE_ID)
        }
        return changes
    }

    /**
     * Some source helpers own the provider-dependent operation but their callers own the provider:
     * a pipe connection serializes a FluidStack while its BlockEntityBehaviour write/read method has
     * the provider, or a value predicate loads container items while each caller has a Level. Widen
     * only a single, non-override source method whose overload set, method references, and every
     * source-resolved direct call close under the new trailing provider.
     */
    private fun threadSourceProviderRoots(
        pass: StructuralRefactorPass,
        sources: Map<Path, String>,
        project: Project,
        index: JavaInheritanceIndex
    ): Map<Path, String> {
        val families = NbtHookFamilies.build(sources.keys.toList())
        val candidates = sources.flatMap { (file, source) ->
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            with(pass) { javaMethodRangesIncludingDefault(source) }.mapNotNull { method ->
                if (method.header.contains("@Override") || method.header.contains("HolderLookup.Provider")) return@mapNotNull null
                val parameters = with(pass) { javaMethodParameters(source.substring(method.range)) }
                if (parameters.isEmpty()) return@mapNotNull null
                val owner = enclosingTypes(masked, method.range.first).firstOrNull() ?: return@mapNotNull null
                val shapes = project.methods[owner]?.get(method.name).orEmpty()
                if (shapes.size != 1 || shapes.single().count != parameters.size) return@mapNotNull null
                if (!families.widenable(owner, method.name, parameters.size, method.header) ||
                    families.members(owner, method.name, parameters.size) != setOf(owner)
                ) return@mapNotNull null
                val providerCalls =
                    containerHelperProviderCallOffsets(pass, source, method.range) +
                        fluidStackProviderCallOffsets(pass, source, method.range)
                if (providerCalls.isEmpty() ||
                    providerCalls.all { provenRegistryAccessAt(pass, source, it, index) != null }
                ) return@mapNotNull null
                ProviderRootCandidate(
                    file = file,
                    owner = owner,
                    method = method,
                    parameterCount = parameters.size,
                    providerName = with(pass) {
                        uniqueLocalNameInScope(source.substring(method.range), "registries")
                    }
                )
            }
        }
        if (candidates.isEmpty()) return sources

        val editsByFile = linkedMapOf<Path, MutableList<Pair<IntRange, String>>>()
        val importFiles = linkedSetOf<Path>()
        for (candidate in candidates) {
            if (hasTargetMethodReference(pass, sources.values, candidate, project)) continue
            val callEdits = directProviderCallEdits(pass, sources, candidate, project, index) ?: continue
            if (callEdits.isEmpty()) continue
            val declarationSource = sources.getValue(candidate.file)
            val nameOffset = declarationSource.indexOf(candidate.method.name, candidate.method.range.first)
            val open = declarationSource.indexOf('(', nameOffset)
            val close = if (open >= 0) JavaSourceText.matchingParen(
                JavaSourceText.maskCommentsAndLiterals(declarationSource),
                open
            ) else -1
            if (nameOffset < 0 || open < 0 || close <= open || close !in candidate.method.range) continue
            val originalParameters = declarationSource.substring(open + 1, close)
            val widenedParameters = "$originalParameters, HolderLookup.Provider ${candidate.providerName}"
            editsByFile.getOrPut(candidate.file) { mutableListOf() } +=
                (open + 1)..(close - 1) to widenedParameters
            for ((file, edits) in callEdits) {
                editsByFile.getOrPut(file) { mutableListOf() }.addAll(edits)
            }
            importFiles.add(candidate.file)
        }
        if (editsByFile.isEmpty()) return sources
        val normalizedEdits = linkedMapOf<Path, List<Pair<IntRange, String>>>()
        for ((file, edits) in editsByFile) {
            val byRange = edits.groupBy { it.first }
            if (byRange.values.any { sameRange -> sameRange.map { it.second }.distinct().size != 1 }) {
                return sources
            }
            val unique = byRange.values.map { it.first() }.sortedBy { it.first.first }
            if (unique.zipWithNext().any { (left, right) -> left.first.last >= right.first.first }) {
                return sources
            }
            normalizedEdits[file] = unique
        }

        return sources.mapValues { (file, source) ->
            var result = source
            for ((range, replacement) in normalizedEdits[file].orEmpty().sortedByDescending { it.first.first }) {
                result = result.replaceRange(range, replacement)
            }
            if (file in importFiles) {
                result = with(pass) { addImportIfMissing(result, "net.minecraft.core.HolderLookup") }
            }
            result
        }
    }

    private fun containerHelperProviderCallOffsets(
        pass: StructuralRefactorPass,
        source: String,
        range: IntRange
    ): List<Int> {
        if (!source.contains("import net.minecraft.world.ContainerHelper;")) return emptyList()
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val values = SourceValueTypes(pass, source)
        return Regex("""(?<![\w$])ContainerHelper\s*\.\s*loadAllItems\s*\(""")
            .findAll(masked.substring(range))
            .mapNotNull { relative ->
                val start = range.first + relative.range.first
                if (!values.isTypeName("ContainerHelper", "net.minecraft.world.ContainerHelper")) return@mapNotNull null
                val open = masked.indexOf('(', start)
                val close = if (open >= 0) JavaSourceText.matchingParen(masked, open) else -1
                start.takeIf {
                    close > open && close in range &&
                        JavaSourceText.splitTopLevelArgs(source.substring(open + 1, close)).size == 2
                }
            }
            .toList()
    }

    private fun fluidStackProviderCallOffsets(
        pass: StructuralRefactorPass,
        source: String,
        range: IntRange
    ): List<Int> {
        if (!source.contains("FluidStack")) return emptyList()
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val values = SourceValueTypes(pass, source)
        val offsets = mutableListOf<Int>()
        for (match in Regex("""\.\s*writeToNBT\s*\(""").findAll(masked.substring(range))) {
            val dot = range.first + match.range.first
            val start = with(pass) { findExpressionReceiverStart(masked, dot) }
            if (start in 0 until dot &&
                values.typeOf(source.substring(start, dot), dot) == "net.neoforged.neoforge.fluids.FluidStack"
            ) offsets += dot
        }
        offsets += staticRegistryFluidReadOffsets(pass, source, range)
        return offsets
    }

    private fun directProviderCallEdits(
        pass: StructuralRefactorPass,
        sources: Map<Path, String>,
        candidate: ProviderRootCandidate,
        project: Project,
        index: JavaInheritanceIndex
    ): Map<Path, List<Pair<IntRange, String>>>? {
        val edits = linkedMapOf<Path, MutableList<Pair<IntRange, String>>>()
        var targetCalls = 0
        for ((file, source) in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val values = SourceValueTypes(pass, source)
            val bindings = StructuredSourceBindings(pass, source)
            val call = Regex("""(?<![\w$])((?:$ID\s*\.\s*)*)${Regex.escape(candidate.method.name)}\s*\(""")
            for (match in call.findAll(masked)) {
                val open = match.range.last
                val close = JavaSourceText.matchingParen(masked, open)
                if (close < 0) continue
                if (Regex("""^\s*(?:throws\s+[^{;]+)?\{""").containsMatchIn(masked.substring(close + 1)) &&
                    Regex("""[\w$>\]]\s+$""").containsMatchIn(masked.substring(0, match.range.first))
                ) continue
                val arguments = if (masked.substring(open + 1, close).isBlank()) {
                    emptyList()
                } else {
                    JavaSourceText.splitTopLevelArgs(source.substring(open + 1, close))
                }
                if (arguments.size != candidate.parameterCount) continue
                val receiver = match.groupValues[1].trim().removeSuffix(".").trim()
                val qualifiedByExpression =
                    receiver.isEmpty() && match.range.first > 0 && masked[match.range.first - 1] == '.'
                val receiverTypes = when {
                    qualifiedByExpression -> {
                        val dot = match.range.first - 1
                        val start = with(pass) { findExpressionReceiverStart(masked, dot) }
                        val type = if (start in 0 until dot) {
                            values.typeOf(source.substring(start, dot), dot)
                        } else {
                            null
                        } ?: continue
                        resolvedHierarchy(pass, source, type, match.range.first, project) ?: continue
                    }
                    receiver.isEmpty() || receiver == "this" -> {
                        val owner = enclosingTypes(masked, match.range.first).firstOrNull() ?: continue
                        hierarchy(owner, project)
                    }
                    Regex("""^$ID$""").matches(receiver) -> {
                        val type = values.typeOf(receiver, match.range.first)
                            ?: receiverType(masked, receiver, match.range.first)
                            ?: bindings.lambdaParameterType(receiver, match.range.first)
                            ?: bindings.guardedPatternTypeAt(receiver, match.range.first)
                            ?: continue
                        resolvedHierarchy(pass, source, type, match.range.first, project) ?: continue
                    }
                    else -> {
                        val type = values.typeOf(receiver, match.range.first) ?: continue
                        resolvedHierarchy(pass, source, type, match.range.first, project) ?: continue
                    }
                }
                if (candidate.owner !in receiverTypes) continue
                val provider = provenRegistryAccessAt(pass, source, match.range.first, index) ?: return null
                val updated = (arguments.map { it.trim() } + provider).joinToString(", ")
                edits.getOrPut(file) { mutableListOf() } += (open + 1)..(close - 1) to updated
                targetCalls++
            }
        }
        return edits.takeIf { targetCalls > 0 }
    }

    private fun hasTargetMethodReference(
        pass: StructuralRefactorPass,
        sources: Collection<String>,
        candidate: ProviderRootCandidate,
        project: Project
    ): Boolean = sources.any { source ->
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val values = SourceValueTypes(pass, source)
        val bindings = StructuredSourceBindings(pass, source)
        Regex("""(?<![\w$])((?:$ID\s*\.\s*)*$ID)\s*::\s*${Regex.escape(candidate.method.name)}\b""")
            .findAll(masked)
            .any { reference ->
                val receiver = reference.groupValues[1].replace(Regex("""\s+"""), "")
                if (receiver == "this" || receiver == "super") {
                    val owner = enclosingTypes(masked, reference.range.first).firstOrNull()
                        ?: return@any false
                    val types = if (receiver == "this") hierarchy(owner, project) else hierarchy(owner, project) - owner
                    return@any candidate.owner in types
                }
                if (receiver.substringAfterLast('.') == candidate.owner &&
                    pass.projectValueFacts.types.any { it.substringAfterLast('.') == candidate.owner }
                ) return@any true
                val type = values.typeOf(receiver, reference.range.first)
                    ?: if (Regex("""^$ID$""").matches(receiver)) {
                        receiverType(masked, receiver, reference.range.first)
                            ?: bindings.lambdaParameterType(receiver, reference.range.first)
                            ?: bindings.guardedPatternTypeAt(receiver, reference.range.first)
                    } else {
                        null
                    }
                    ?: return@any false
                candidate.owner in (resolvedHierarchy(pass, source, type, reference.range.first, project)
                    ?: emptySet())
            }
    }

    private fun provenRegistryAccessAt(
        pass: StructuralRefactorPass,
        source: String,
        offset: Int,
        index: JavaInheritanceIndex
    ): String? {
        val aliasedLevel = if (source.contains("isClientSide")) {
            StructuredSourceBindings(pass, source).aliasedLocalLevelRegistryAccessAt(offset)
        } else {
            null
        }
        val expression = with(pass) {
            aliasedLevel
                ?: registryAccessExpressionAt(source, offset, index)
                ?: minecraftServerRegistryAccessAt(source, offset)
        } ?: return null
        val root = Regex("""^($ID)\b""").find(expression)?.groupValues?.get(1) ?: return expression
        val parameters = with(pass) { currentMethodParametersBeforeOffset(source, offset) }
        val rootParameter = parameters.singleOrNull { parameter ->
            Regex("""\b${Regex.escape(root)}\s*$""").containsMatchIn(parameter.trim())
        }
        if (rootParameter != null) {
            if (Regex("""@(?:[\w.]+\.)?Nullable\b""").containsMatchIn(rootParameter)) return null
            if (!canonicalProviderRootType(pass, source, rootParameter, root)) return null
        } else {
            val declaration = JavaScopedDeclarations.visibleAt(
                JavaSourceText.maskCommentsAndLiterals(source),
                root,
                offset
            )
            if (declaration != null &&
                !canonicalProviderRootType(pass, source, "${declaration.type} $root", root)
            ) {
                return null
            }
        }
        return with(pass) { holderLookupProviderArgumentExpression(expression) }
    }

    /**
     * Structured bindings used only for source shapes the shared value typer intentionally does
     * not model: Map.values() callbacks, Arrays.stream(Enum.values()) callbacks, a negated
     * early-return pattern variable, and a local Level whose side test flows through a boolean.
     */
    private class StructuredSourceBindings(
        private val pass: StructuralRefactorPass,
        private val source: String
    ) {
        private data class Binding(
            val node: Node,
            val scope: Node,
            val type: Type,
            val visibleBeforeDeclaration: Boolean = false
        )

        private val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        private val unit by lazy { parser.parse(source).result.orElse(null) }
        private val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }
        private val bindingsByName: Map<String, List<Binding>> by lazy {
            val result = mutableMapOf<String, MutableList<Binding>>()
            for (variable in unit?.findAll(VariableDeclarator::class.java).orEmpty()) {
                val scope = bindingScope(variable) ?: continue
                val field = scope is TypeDeclaration<*>
                result.getOrPut(variable.nameAsString) { mutableListOf() } +=
                    Binding(variable, scope, variable.type, field)
            }
            for (parameter in unit?.findAll(Parameter::class.java).orEmpty()) {
                val scope = bindingScope(parameter) ?: continue
                result.getOrPut(parameter.nameAsString) { mutableListOf() } +=
                    Binding(parameter, scope, parameter.type)
            }
            result
        }

        private fun start(node: Node): Int =
            node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)

        private fun end(node: Node): Int =
            node.range.map { lineStarts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)

        private fun bindingScope(node: Node): Node? {
            var parent = node.parentNode.orElse(null)
            while (parent != null) {
                if (parent is BlockStmt || parent is MethodDeclaration || parent is ConstructorDeclaration ||
                    parent is LambdaExpr || parent is ForStmt || parent is ForEachStmt ||
                    parent is CatchClause || parent is TypeDeclaration<*>
                ) {
                    return parent
                }
                parent = parent.parentNode.orElse(null)
            }
            return null
        }

        private fun visibleBinding(name: String, offset: Int): Binding? =
            bindingsByName[name].orEmpty()
                .asSequence()
                .filter {
                    offset in start(it.scope)..end(it.scope) &&
                        (it.visibleBeforeDeclaration || start(it.node) <= offset)
                }
                .sortedWith(
                    compareBy<Binding> { end(it.scope) - start(it.scope) }
                        .thenByDescending { start(it.node) }
                )
                .firstOrNull()

        private fun blockAt(offset: Int): BlockStmt? =
            unit?.findAll(BlockStmt::class.java).orEmpty()
                .filter { offset in start(it)..end(it) }
                .minByOrNull { end(it) - start(it) }

        private fun directStatement(node: Node, block: BlockStmt): Statement? {
            var current = node
            while (true) {
                val parent = current.parentNode.orElse(null) ?: return null
                if (parent === block) return current as? Statement
                current = parent
            }
        }

        private fun unwrap(expression: Expression): Expression {
            var result = expression
            while (result is EnclosedExpr) result = result.inner
            return result
        }

        private fun exits(statement: Statement): Boolean = when (statement) {
            is ReturnStmt, is ThrowStmt -> true
            is BlockStmt -> statement.statements.size == 1 && exits(statement.statements.single())
            else -> false
        }

        private fun simpleReceiverName(expression: Expression): String? = when (val value = unwrap(expression)) {
            is NameExpr -> value.nameAsString
            is FieldAccessExpr -> value.nameAsString.takeIf { value.scope is ThisExpr }
            else -> null
        }

        private fun canonicalProjectType(type: String): String? {
            val canonical = canonicalTypeName(source, type)
            if (canonical in pass.projectValueFacts.types || TargetMembers.hasClass(canonical)) {
                return canonical
            }
            val simple = type.substringBefore('<').trim().substringAfterLast('.')
            val packageName = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
                .find(source)
                ?.groupValues
                ?.get(1)
                .orEmpty()
            return pass.projectValueFacts.types.singleOrNull { candidate ->
                candidate.substringAfterLast('.') == simple &&
                    (packageName.isEmpty() || candidate.startsWith("$packageName."))
            }
        }

        fun lambdaParameterType(name: String, offset: Int): String? {
            if (!source.contains("->")) return null
            val lambda = unit?.findAll(LambdaExpr::class.java).orEmpty()
                .filter {
                    offset in start(it)..end(it) &&
                        it.parameters.any { parameter -> parameter.nameAsString == name }
                }
                .minByOrNull { end(it) - start(it) }
                ?: return null
            var argument: Node = lambda
            while (argument.parentNode.orElse(null) is EnclosedExpr) {
                argument = argument.parentNode.get()
            }
            val call = argument.parentNode.orElse(null) as? MethodCallExpr ?: return null
            if (call.arguments.none { it === argument }) return null

            if (call.nameAsString == "forEach") {
                val values = call.scope.orElse(null) as? MethodCallExpr
                if (values?.nameAsString == "values" && values.arguments.isEmpty()) {
                    val mapName = values.scope.orElse(null)?.let(::simpleReceiverName) ?: return null
                    val binding = visibleBinding(mapName, start(values)) ?: return null
                    val declared = binding.type.toString()
                    val open = declared.indexOf('<')
                    val close = declared.lastIndexOf('>')
                    if (open < 0 || close <= open) return null
                    val base = canonicalTypeName(source, declared.substring(0, open))
                    if (base !in setOf(
                            "java.util.Map",
                            "java.util.HashMap",
                            "java.util.IdentityHashMap",
                            "java.util.EnumMap"
                        )
                    ) return null
                    val arguments = JavaSourceText.splitTopLevelArgs(declared.substring(open + 1, close))
                    if (arguments.size != 2) return null
                    val valueType = arguments[1].trim()
                        .removePrefix("? extends ")
                        .takeUnless { it.startsWith("? super ") || it == "?" }
                        ?: return null
                    return canonicalProjectType(valueType)
                }
            }

            if (call.nameAsString !in setOf(
                    "filter",
                    "map",
                    "flatMap",
                    "peek",
                    "anyMatch",
                    "allMatch",
                    "noneMatch"
                )
            ) return null
            val stream = call.scope.orElse(null) as? MethodCallExpr ?: return null
            if (stream.nameAsString != "stream" || stream.arguments.size != 1 ||
                canonicalTypeName(source, stream.scope.orElse(null)?.toString().orEmpty()) != "java.util.Arrays"
            ) return null
            val values = unwrap(stream.arguments.single()) as? MethodCallExpr ?: return null
            if (values.nameAsString != "values" || values.arguments.isNotEmpty()) return null
            val declared = values.scope.orElse(null)?.toString() ?: return null
            return canonicalProjectType(declared)
        }

        fun guardedPatternTypeAt(name: String, offset: Int): String? {
            if (!source.contains("instanceof")) return null
            val block = blockAt(offset) ?: return null
            val types = block.statements.asSequence()
                .filterIsInstance<IfStmt>()
                .filter { start(it) < offset && exits(it.thenStmt) }
                .mapNotNull { statement ->
                    val condition = unwrap(statement.condition) as? UnaryExpr ?: return@mapNotNull null
                    if (condition.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return@mapNotNull null
                    val test = unwrap(condition.expression) as? InstanceOfExpr ?: return@mapNotNull null
                    val pattern = test.pattern.orElse(null) as? TypePatternExpr ?: return@mapNotNull null
                    pattern.type.toString().takeIf { pattern.nameAsString == name }
                }
                .mapNotNull(::canonicalProjectType)
                .distinct()
                .toList()
            return types.singleOrNull()
        }

        fun aliasedLocalLevelRegistryAccessAt(offset: Int): String? {
            if (!source.contains("isClientSide")) return null
            val block = blockAt(offset) ?: return null
            val candidates = unit?.findAll(VariableDeclarator::class.java).orEmpty()
                .mapNotNull { level ->
                    val levelStatement = directStatement(level, block) ?: return@mapNotNull null
                    if (start(levelStatement) >= offset || !level.initializer.isPresent) return@mapNotNull null
                    if (visibleBinding(level.nameAsString, offset)?.node !== level) return@mapNotNull null
                    val canonical = canonicalTypeName(source, level.type.toString())
                    if (canonical == "net.minecraft.server.level.ServerLevel") {
                        return@mapNotNull level.nameAsString
                    }
                    if (canonical != "net.minecraft.world.level.Level") return@mapNotNull null

                    val aliases = unit?.findAll(VariableDeclarator::class.java).orEmpty()
                        .filter { alias ->
                            alias.type.toString() == "boolean" &&
                                start(alias) > start(levelStatement) &&
                                start(alias) < offset &&
                                directStatement(alias, block) != null &&
                                alias.initializer.orElse(null)?.let(::clientSideRoot) == level.nameAsString
                        }
                    val guarded = aliases.any { alias ->
                        block.statements.asSequence()
                            .filterIsInstance<IfStmt>()
                            .filter { start(it) > start(alias) && start(it) < offset && exits(it.thenStmt) }
                            .any { statement ->
                                val condition = unwrap(statement.condition) as? NameExpr
                                condition?.nameAsString == alias.nameAsString &&
                                    visibleBinding(alias.nameAsString, start(statement))?.node === alias
                            }
                    }
                    level.nameAsString.takeIf { guarded }
                }
                .distinct()
            return candidates.singleOrNull()?.let { "$it.registryAccess()" }
        }

        fun enclosingLevelAccessorRegistryAccessAt(offset: Int): String? {
            val caller = unit?.findAll(MethodDeclaration::class.java).orEmpty()
                .filter { offset in start(it)..end(it) }
                .minByOrNull { end(it) - start(it) }
                ?.takeUnless { it.isStatic }
                ?: return null
            val owner = unit?.findAll(TypeDeclaration::class.java).orEmpty()
                .filter { offset in start(it)..end(it) }
                .minByOrNull { end(it) - start(it) }
                ?: return null
            val accessors = owner.members
                .filterIsInstance<MethodDeclaration>()
                .filter { method ->
                    !method.isStatic &&
                        method.parameters.isEmpty() &&
                        offset !in start(method)..end(method) &&
                        canonicalTypeName(source, method.type.toString()) in setOf(
                            "net.minecraft.world.level.Level",
                            "net.minecraft.server.level.ServerLevel",
                            "net.minecraft.client.multiplayer.ClientLevel"
                        )
                }
                .map { it.nameAsString }
                .distinct()
            val accessor = accessors.singleOrNull() ?: return null
            val precedingCallerSource = source.substring(start(caller).coerceAtLeast(0), offset)
            if (!Regex("""\b${Regex.escape(accessor)}\s*\(\s*\)\s*\.\s*isClientSide\b""")
                    .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(precedingCallerSource))
            ) {
                return null
            }
            return "$accessor().registryAccess()"
        }

        private fun clientSideRoot(expression: Expression): String? = when (val value = unwrap(expression)) {
            is FieldAccessExpr -> value.scope.let(::simpleReceiverName)
                .takeIf { value.nameAsString == "isClientSide" }
            is MethodCallExpr -> value.scope.orElse(null)?.let(::simpleReceiverName)
                .takeIf { value.nameAsString == "isClientSide" && value.arguments.isEmpty() }
            else -> null
        }
    }

    private fun canonicalProviderRootType(
        pass: StructuralRefactorPass,
        source: String,
        declaration: String,
        name: String
    ): Boolean {
        val type = declaration
            .replace(Regex("""@(?:[\w.]+\.)?[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*"""), "")
            .replace(Regex("""^\s*final\s+"""), "")
            .replace(Regex("""\s+${Regex.escape(name)}\s*$"""), "")
            .trim()
            .substringBefore('<')
            .trim()
        if (type.isEmpty()) return false
        val canonical = canonicalTypeName(source, type)
        return canonical == "net.minecraft.core.HolderLookup.Provider" ||
            TargetMembers.hasClass(canonical) ||
            canonical in pass.projectValueFacts.types &&
            canonical.substringAfterLast('.') !in setOf(
                "Level",
                "ServerLevel",
                "RegistryAccess",
                "Provider"
            )
    }

    /**
     * Catnip moved unsafe item sanitization from legacy NBT to components in the 1.21 library.
     * This is an exact owner/member relocation, not a provider overload.
     */
    private fun repairCatnipUnsafeItemComponents(pass: StructuralRefactorPass, source: String): String {
        val oldImport = "net.createmod.catnip.nbt.NBTProcessors"
        if (!source.contains("import $oldImport;") ||
            !source.contains("NBTProcessors.withUnsafeNBTDiscarded")
        ) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val target = "net.createmod.catnip.components.ComponentProcessors"
        val importCollision = Regex("""(?m)^\s*import\s+([\w.]+\.ComponentProcessors)\s*;""")
            .findAll(masked)
            .any { it.groupValues[1] != target }
        val typeCollision = Regex("""\b(?:class|interface|enum|record)\s+ComponentProcessors\b""")
            .containsMatchIn(masked)
        val replacements = Regex("""(?<![\w$.])NBTProcessors\s*\.\s*withUnsafeNBTDiscarded\b""")
            .findAll(masked)
            .filter {
                JavaScopedDeclarations.visibleAt(masked, "NBTProcessors", it.range.first) == null
            }
            .map {
                val simpleNameAvailable = !importCollision && !typeCollision &&
                    JavaScopedDeclarations.visibleAt(masked, "ComponentProcessors", it.range.first) == null
                it.range to if (simpleNameAvailable) {
                    "ComponentProcessors.withUnsafeComponentsDiscarded"
                } else {
                    "$target.withUnsafeComponentsDiscarded"
                }
            }
            .toList()
        if (replacements.isEmpty()) return source
        var result = source
        for ((range, replacement) in replacements.asReversed()) {
            result = result.replaceRange(range, replacement)
        }
        if (replacements.any { (_, replacement) -> replacement.startsWith("ComponentProcessors.") }) {
            result = with(pass) {
                addImportIfMissing(result, target)
            }
        }
        if (!JavaSourceText.maskCommentsAndLiterals(result).contains("NBTProcessors.")) {
            result = result.replace(
                Regex("""(?m)^[ \t]*import\s+${Regex.escape(oldImport)}\s*;\s*\r?\n"""),
                ""
            )
        }
        return result
    }

    /**
     * A prior migration can leave a trailing `null` from an API argument that the project's own
     * declaration never had. Remove it only when the receiver resolves to project source and every
     * reachable declaration of the method has exactly one fewer parameter.
     */
    private fun repairRemovedTrailingNullArguments(
        pass: StructuralRefactorPass,
        source: String,
        project: Project
    ): String {
        var result = source
        var cursor = 0
        var valueSource: String? = null
        var valueTypes: SourceValueTypes? = null
        fun expressionType(expression: String, offset: Int): String? {
            if (valueSource !== result) {
                valueSource = result
                valueTypes = SourceValueTypes(pass, result)
            }
            return valueTypes!!.typeOf(expression, offset)
        }
        val call = Regex("""(?<![\w$])((?:$ID\s*\.\s*)+)($ID)\s*\(""")
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val match = call.find(masked, cursor) ?: break
            cursor = match.range.last + 1
            val name = match.groupValues[2]
            if (project.methods.values.none { name in it }) continue
            val open = match.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) continue
            val arguments = if (masked.substring(open + 1, close).isBlank()) {
                emptyList()
            } else {
                JavaSourceText.splitTopLevelArgs(result.substring(open + 1, close))
            }
            if (arguments.size < 2 || arguments.last().trim() != "null") continue
            val receiver = match.groupValues[1].trim().removeSuffix(".").trim()
            if (!Regex("""^$ID$""").matches(receiver)) continue
            val type = expressionType(receiver, match.range.first)
                ?: receiverType(masked, receiver, match.range.first)
                ?: continue
            val rootType = type.substringBefore('<').substringAfterLast('.')
            if (rootType !in project.supertypes) continue
            val types = hierarchy(rootType, project)
            // An explicit external ancestor can contribute an overload that source collection cannot see.
            if (types.any { it !in project.supertypes }) continue
            val shapes = types.flatMap { project.methods[it]?.get(name).orEmpty() }
            if (shapes.isEmpty() || shapes.any { it.count != arguments.size - 1 }) continue
            val targetParameters = shapes
                .map { shape -> shape.parameterTypes.map(::simpleTypeName) }
                .distinct()
                .singleOrNull()
                ?.takeIf { it.size == arguments.size - 1 }
                ?: continue
            val retainedTypes = arguments.dropLast(1).map { expressionType(it, match.range.first) ?: return@map null }
            if (retainedTypes.any { it == null }) continue
            if (retainedTypes.filterNotNull().map(::simpleTypeName) != targetParameters) continue
            val updated = arguments.dropLast(1).joinToString(", ") { it.trim() }
            result = result.substring(0, open + 1) + updated + result.substring(close)
            cursor = open + 1 + updated.length
        }
        return result
    }

    private fun simpleTypeName(type: String): String =
        type.trim().removeSuffix("...").substringBefore('<').trim().removeSuffix("[]").trim().substringAfterLast('.')

    /**
     * A BlockEntity parameter can be detached from any level. Once the project graph has threaded a
     * provider into its method, registry lookups in that method must use the provider as well.
     */
    private fun repairDetachedBlockEntityLookups(pass: StructuralRefactorPass, source: String): String {
        if (!source.contains(".getLevel().holderLookup(") || !source.contains("HolderLookup.Provider")) return source
        val replacements = mutableListOf<Pair<IntRange, String>>()
        for (method in with(pass) { javaMethodRangesIncludingDefault(source) }) {
            val methodText = source.substring(method.range)
            val parameters = with(pass) { javaMethodParameters(methodText) }
            val provider = parameters.singleOrNull {
                it.type.contains("HolderLookup") && it.type.substringAfterLast('.') == "Provider"
            }?.name ?: continue
            val blockEntities = parameters.filter {
                it.type.substringAfterLast('.') == "BlockEntity"
            }.map { it.name }
            for (blockEntity in blockEntities) {
                val lookup = Regex(
                    """(?<![\w$])${Regex.escape(blockEntity)}\s*\.\s*getLevel\s*\(\s*\)\s*\.\s*holderLookup\s*\(\s*([A-Za-z_$][\w$.]*)\s*\)"""
                )
                for (match in lookup.findAll(methodText)) {
                    val absolute = (method.range.first + match.range.first)..(method.range.first + match.range.last)
                    replacements += absolute to "$provider.lookupOrThrow(${match.groupValues[1]})"
                }
            }
        }
        if (replacements.isEmpty()) return source
        var result = source
        for ((range, replacement) in replacements.sortedByDescending { it.first.first }) {
            result = result.replaceRange(range, replacement)
        }
        return result
    }

    private fun repair(pass: StructuralRefactorPass, source: String, project: Project, projectProviderNames: Set<String>, index: JavaInheritanceIndex): String {
        var result = repairCompoundListMethodReferences(pass, source, project, index)
        var cursor = 0
        var valueSource: String? = null
        var valueTypes: SourceValueTypes? = null
        var bindingSource: String? = null
        var sourceBindings: StructuredSourceBindings? = null
        fun expressionType(expression: String, offset: Int): String? {
            if (valueSource !== result) {
                valueSource = result
                valueTypes = SourceValueTypes(pass, result)
            }
            return valueTypes!!.typeOf(expression, offset)
        }
        fun bindings(): StructuredSourceBindings {
            if (bindingSource !== result) {
                bindingSource = result
                sourceBindings = StructuredSourceBindings(pass, result)
            }
            return sourceBindings!!
        }
        val call = Regex("""(?<![\w$])(new\s+)?((?:$ID\s*\.\s*)*)($ID)\s*\(""")
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val match = call.find(masked, cursor) ?: break
            cursor = match.range.last + 1
            val isNew = match.groups[1] != null
            val name = match.groupValues[3]
            if (name in javaKeywords) continue
            val open = match.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) continue
            // a declaration, not a call
            if (Regex("""^\s*(?:throws\s+[^{;]+)?\{""").containsMatchIn(masked.substring(close + 1)) && !isNew &&
                Regex("""[\w$>\]]\s+$""").containsMatchIn(masked.substring(0, match.range.first))) continue
            val argumentText = result.substring(open + 1, close)
            val arguments = if (masked.substring(open + 1, close).isBlank()) emptyList() else JavaSourceText.splitTopLevelArgs(argumentText)
            val receiverText = match.groupValues[2].trim().removeSuffix(".").trim()
            val qualifiedByExpression = !isNew && receiverText.isEmpty() && match.range.first > 0 && masked[match.range.first - 1] == '.'
            val position = when {
                isNew -> constructorProviderIndex(
                    name.substringAfterLast('.'),
                    arguments,
                    project
                ) { argument ->
                    if (argument.trim() == "this") {
                        enclosingTypes(masked, match.range.first).firstOrNull()
                    } else {
                        expressionType(argument, match.range.first)
                    }
                }
                else -> {
                    if (name !in projectProviderNames && TargetMembers.methodsNamed(name).none { (_, d) -> PROVIDER_DESCRIPTOR in TargetMembers.parameterTypes(d) }) continue
                    // the types whose declarations the call can reach
                    val receiverTypes = when {
                        qualifiedByExpression -> {
                            val dot = match.range.first - 1
                            val start = with(pass) { findExpressionReceiverStart(masked, dot) }
                            val valueType = if (start in 0 until dot) expressionType(result.substring(start, dot), dot) else null
                            valueType?.let {
                                resolvedHierarchy(pass, result, it, match.range.first, project)
                            } ?: continue // A declared, unmodeled value is not proof for an extra argument.
                        }
                        receiverText.isEmpty() || receiverText == "this" -> (enclosingTypes(masked, match.range.first).firstOrNull { type ->
                            hierarchy(type, project).any { project.methods[it]?.containsKey(name) == true }
                        } ?: enclosingTypes(masked, match.range.first).firstOrNull())?.let { hierarchy(it, project) }
                        receiverText == "super" -> enclosingTypes(masked, match.range.first).firstOrNull()?.let { hierarchy(it, project) - it }
                        Regex("""^$ID$""").matches(receiverText) -> {
                            val declaredType = expressionType(receiverText, match.range.first)
                                ?: receiverType(masked, receiverText, match.range.first)
                                ?: bindings().lambdaParameterType(receiverText, match.range.first)
                                ?: bindings().guardedPatternTypeAt(receiverText, match.range.first)
                            // A value shadows the class only where that declaration is visible. A
                            // same-named parameter in another method must not suppress this static owner.
                            val projectType = if (declaredType == null) {
                                pass.projectValueFacts.types
                                    .filter { it.substringAfterLast('.') == receiverText }
                                    .singleOrNull()
                            } else {
                                null
                            }
                            val type = declaredType
                                ?: projectType
                                ?: canonicalTypeName(result, receiverText).takeIf { canonical ->
                                    TargetMembers.hasClass(canonical) ||
                                        canonical in pass.projectValueFacts.types
                                }
                            if (type != null) {
                                resolvedHierarchy(pass, result, type, match.range.first, project)
                                    ?: continue
                            } else {
                                null
                            }
                        }
                        else -> {
                            val valueType = expressionType(receiverText, match.range.first)
                            valueType?.let {
                                resolvedHierarchy(pass, result, it, match.range.first, project)
                            } ?: continue // A declared, unmodeled field chain is not proof for an extra argument.
                        }
                    }
                    methodProviderIndex(name, arguments.size, receiverTypes, project)
                }
            } ?: continue
            val registryAccess = with(pass) {
                entityReceiverRegistryAccessAt(
                    source = result,
                    receiver = receiverText,
                    offset = match.range.first,
                    expressionType = ::expressionType,
                    bindings = bindings()
                )
                    ?: bindings().aliasedLocalLevelRegistryAccessAt(match.range.first)
                    ?: registryAccessExpressionAt(result, match.range.first, index)
                    ?: minecraftServerRegistryAccessAt(result, match.range.first)
                    ?: bindings().enclosingLevelAccessorRegistryAccessAt(match.range.first)
            } ?: continue
            val provider = with(pass) { holderLookupProviderArgumentExpression(registryAccess) }
            if (arguments.any { it.trim() == provider }) continue
            val updated = arguments.map { it.trim() }.toMutableList().apply { add(position, provider) }.joinToString(", ")
            result = result.substring(0, open + 1) + updated + result.substring(close)
            cursor = open + 1 + updated.length
        }
        return result
    }

    private fun resolvedHierarchy(
        pass: StructuralRefactorPass,
        source: String,
        type: String,
        offset: Int,
        project: Project
    ): Set<String>? {
        val raw = type.substringBefore('<').trim()
        val simple = raw.substringAfterLast('.')
        val lexicalBound = lexicalTypeParameterBound(pass, source, simple, offset)
        val resolved = lexicalBound ?: canonicalTypeName(source, raw)
        val resolvedSimple = resolved.substringAfterLast('.')
        val declaredHere = resolvedSimple in project.supertypes &&
            with(pass) {
                javaTypeBlocks(source, JavaSourceText.maskCommentsAndLiterals(source))
                    .any { it.name == resolvedSimple }
            }
        return when {
            resolved in pass.projectValueFacts.types ->
                hierarchy(resolvedSimple, project)
            declaredHere ->
                hierarchy(resolvedSimple, project)
            TargetMembers.hasClass(resolved) -> setOf(resolved)
            else -> null
        }
    }

    /**
     * A type variable is meaningful only in its lexical declaration. Method type parameters shadow
     * enclosing type parameters; unrelated `T` declarations elsewhere in the project never apply.
     */
    private fun lexicalTypeParameterBound(
        pass: StructuralRefactorPass,
        source: String,
        name: String,
        offset: Int
    ): String? {
        val method = with(pass) { javaMethodRangesIncludingDefault(source) }
            .filter { offset in it.range }
            .minByOrNull { it.range.last - it.range.first }
        method?.header?.let { header ->
            val modifiers = """(?:(?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?|public|protected|private|static|final|abstract|synchronized|default|native)\s+)*"""
            val clause = Regex("""(?s)^\s*$modifiers<([^>{};]+)>\s+""")
                .find(header)
                ?.groupValues
                ?.get(1)
            if (typeParameterDeclared(clause, name)) {
                return typeParameterBound(source, clause, name) ?: "java.lang.Object"
            }
        }

        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val enclosing = with(pass) { javaTypeBlocks(source, masked) }
            .filter { offset in it.bodyStart..it.end }
            .sortedBy { it.end - it.bodyStart }
        for (block in enclosing) {
            val header = source.substring(block.declarationStart, block.bodyStart)
            val clause = Regex(
                """(?s)\b(?:class|interface|record)\s+${Regex.escape(block.name)}\s*<([^>{}]*)>"""
            ).find(header)?.groupValues?.get(1)
            if (typeParameterDeclared(clause, name)) {
                return typeParameterBound(source, clause, name) ?: "java.lang.Object"
            }
        }
        return null
    }

    private fun typeParameterDeclared(clause: String?, name: String): Boolean =
        !clause.isNullOrBlank() && JavaSourceText.splitTopLevelArgs(clause)
            .any { Regex("""^${Regex.escape(name)}\b""").containsMatchIn(it.trim()) }

    private fun typeParameterBound(source: String, clause: String?, name: String): String? {
        if (clause.isNullOrBlank()) return null
        val entry = JavaSourceText.splitTopLevelArgs(clause)
            .map { it.trim() }
            .singleOrNull { Regex("""^${Regex.escape(name)}\b""").containsMatchIn(it) }
            ?: return null
        val bound = Regex("""^${Regex.escape(name)}\s+extends\s+(.+)$""")
            .matchEntire(entry)
            ?.groupValues
            ?.get(1)
            ?.substringBefore('&')
            ?.trim()
            ?: return null
        return canonicalTypeName(source, bound)
    }

    private fun canonicalTypeName(source: String, type: String): String {
        val raw = type.substringBefore('<').trim()
        if ('.' in raw && raw.firstOrNull()?.isLowerCase() == true) return raw
        val imports = Regex("""(?m)^\s*import\s+(?!static\b)([\w.]+)\s*;""")
            .findAll(source)
            .associate { it.groupValues[1].substringAfterLast('.') to it.groupValues[1] }
        imports[raw.substringBefore('.')]?.let {
            return it + raw.removePrefix(raw.substringBefore('.'))
        }
        val packageName = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        return if (packageName.isBlank() || raw.startsWith("java.")) raw else "$packageName.$raw"
    }

    /**
     * Prefer the exact, non-null platform entity receiver that owns serializeNBT over a broader
     * enclosing entity or carrier parameter.
     */
    private fun StructuralRefactorPass.entityReceiverRegistryAccessAt(
        source: String,
        receiver: String,
        offset: Int,
        expressionType: (String, Int) -> String?,
        bindings: StructuredSourceBindings
    ): String? {
        if (!Regex("""^$ID$""").matches(receiver)) return null
        val type = expressionType(receiver, offset)
            ?: bindings.guardedPatternTypeAt(receiver, offset)
            ?: return null
        val canonical = canonicalTypeName(source, type)
        if (!TargetMembers.hasClass(canonical) ||
            !TargetMembers.inheritsFrom(canonical, "net.minecraft.world.entity.Entity")
        ) return null
        return "$receiver.registryAccess()"
    }

    private fun repairFluidStackWrites(
        pass: StructuralRefactorPass,
        source: String,
        index: JavaInheritanceIndex
    ): String {
        if (!source.contains(".writeToNBT(") || !source.contains("FluidStack")) return source
        var result = source
        var cursor = 0
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val values = SourceValueTypes(pass, result)
            val match = Regex("""\.\s*writeToNBT\s*\(""").find(masked, cursor) ?: break
            cursor = match.range.last + 1
            val dot = match.range.first
            val start = with(pass) { findExpressionReceiverStart(masked, dot) }
            if (start !in 0 until dot) continue
            val receiver = result.substring(start, dot).trim()
            if (values.typeOf(receiver, dot) != "net.neoforged.neoforge.fluids.FluidStack") continue
            val open = match.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) continue
            val arguments = JavaSourceText.splitTopLevelArgs(result.substring(open + 1, close))
            if (arguments.size != 1) continue
            val registryAccess = with(pass) {
                registryAccessExpressionAt(result, start, index)
                    ?: minecraftServerRegistryAccessAt(result, start)
            } ?: continue
            val provider = with(pass) { holderLookupProviderArgumentExpression(registryAccess) }
            val tag = arguments.single().trim()
            val replacement = if (
                Regex("""^new\s+(?:net\.minecraft\.nbt\.)?CompoundTag\s*\(\s*\)$""").matches(tag)
            ) {
                "net.minecraft.nbt.CompoundTag.class.cast($receiver.saveOptional($provider))"
            } else {
                "net.minecraft.nbt.CompoundTag.class.cast(" +
                    "$receiver.isEmpty() ? $tag : $receiver.save($provider, $tag))"
            }
            result = result.replaceRange(start..close, replacement)
            cursor = start + replacement.length
        }
        return result
    }

    private fun repairStaticRegistryFluidReads(
        pass: StructuralRefactorPass,
        source: String,
        index: JavaInheritanceIndex
    ): String {
        if (!source.contains("FluidStack") || !source.contains("parseOptional")) return source
        var result = source
        var cursor = 0
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val values = SourceValueTypes(pass, result)
            val match = Regex("""(?<![\w$.])FluidStack\s*\.\s*parseOptional\s*\(""")
                .find(masked, cursor) ?: break
            cursor = match.range.last + 1
            if (!values.isTypeName("FluidStack", "net.neoforged.neoforge.fluids.FluidStack")) continue
            val open = match.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) continue
            val arguments = JavaSourceText.splitTopLevelArgs(result.substring(open + 1, close))
            if (arguments.size != 2 || !isStaticRegistryFallback(arguments[0])) continue
            val registryAccess = with(pass) {
                registryAccessExpressionAt(result, match.range.first, index)
                    ?: minecraftServerRegistryAccessAt(result, match.range.first)
            } ?: continue
            val provider = with(pass) { holderLookupProviderArgumentExpression(registryAccess) }
            val replacement = "$provider, ${arguments[1].trim()}"
            result = result.replaceRange((open + 1)..(close - 1), replacement)
            cursor = open + 1 + replacement.length
        }
        return result
    }

    private fun staticRegistryFluidReadOffsets(
        pass: StructuralRefactorPass,
        source: String,
        range: IntRange
    ): List<Int> {
        if (!source.contains("import net.neoforged.neoforge.fluids.FluidStack;")) return emptyList()
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val values = SourceValueTypes(pass, source)
        return Regex("""(?<![\w$.])FluidStack\s*\.\s*parseOptional\s*\(""")
            .findAll(masked.substring(range))
            .mapNotNull { relative ->
                val start = range.first + relative.range.first
                if (!values.isTypeName(
                        "FluidStack",
                        "net.neoforged.neoforge.fluids.FluidStack"
                    )
                ) return@mapNotNull null
                val open = masked.indexOf('(', start)
                val close = if (open >= 0) JavaSourceText.matchingParen(masked, open) else -1
                if (close <= open || close !in range) return@mapNotNull null
                val arguments = JavaSourceText.splitTopLevelArgs(source.substring(open + 1, close))
                start.takeIf {
                    arguments.size == 2 && isStaticRegistryFallback(arguments[0])
                }
            }
            .toList()
    }

    private fun isStaticRegistryFallback(expression: String): Boolean =
        JavaSourceText.maskCommentsAndLiterals(expression).filterNot(Char::isWhitespace) ==
            STATIC_REGISTRIES_PROVIDER.filterNot(Char::isWhitespace)

    /**
     * MinecraftServer directly exposes its registry access. The shared registry-access resolver
     * does not currently model that parameter, so use it here only when the enclosing method has
     * one unambiguous canonical server parameter and the source graph already proved the target.
     */
    private fun StructuralRefactorPass.minecraftServerRegistryAccessAt(source: String, offset: Int): String? {
        val values = SourceValueTypes(this, source)
        val server = currentMethodParametersBeforeOffset(source, offset)
            .mapNotNull { parameter ->
                val name = Regex(
                    """^(?:final\s+)?(?:@$ID(?:\([^)]*\))?\s*)*(?:net\.minecraft\.server\.)?MinecraftServer\s+($ID)$"""
                ).matchEntire(parameter.trim().replace(Regex("""\s+"""), " "))?.groupValues?.get(1)
                    ?: return@mapNotNull null
                name.takeIf { values.typeOf(it, offset) == "net.minecraft.server.MinecraftServer" }
            }
            .distinct()
            .singleOrNull()
            ?: return null
        return "$server.registryAccess()"
    }

    /**
     * NBTHelper's exact callback is Function<CompoundTag,T>. A project read method that gained a
     * provider no longer fits that callback as a method reference, so close only the uniquely typed
     * CompoundTag overload into a lambda. Other overloads, including RegistryFriendlyByteBuf reads,
     * remain untouched.
     */
    private fun repairCompoundListMethodReferences(
        pass: StructuralRefactorPass,
        source: String,
        project: Project,
        index: JavaInheritanceIndex
    ): String {
        if (!source.contains("readCompoundList") || !source.contains("::")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val values = SourceValueTypes(pass, source)
        val call = Regex(
            """(?<![\w$])((?:$ID\s*\.\s*)*$ID)\s*\.\s*readCompoundList\s*\("""
        )
        for (match in call.findAll(masked)) {
            val helper = match.groupValues[1].replace(Regex("""\s+"""), "")
            if (!values.isTypeName(helper, "net.createmod.catnip.nbt.NBTHelper")) continue
            val open = match.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) continue
            val arguments = JavaSourceText.splitTopLevelArgs(source.substring(open + 1, close))
            if (arguments.size != 2) continue
            val callback = arguments[1].trim()
            val reference = Regex("""^($ID(?:\s*\.\s*$ID)*)\s*::\s*($ID)$""").matchEntire(callback) ?: continue
            val owner = reference.groupValues[1].replace(Regex("""\s+"""), "").substringAfterLast('.')
            val name = reference.groupValues[2]
            val shapes = hierarchy(owner, project).flatMap { project.methods[it]?.get(name).orEmpty() }
            val legacyCompound = shapes.any { shape ->
                shape.count == 1 && shape.providerIndex == null && shape.parameterTypes.singleOrNull().isCompoundTag()
            }
            if (legacyCompound) continue
            val widened = shapes.filter { shape ->
                shape.count == 2 && shape.providerIndex != null &&
                    shape.parameterTypes.filterIndexed { parameterIndex, _ -> parameterIndex != shape.providerIndex }
                        .singleOrNull().isCompoundTag()
            }
            val providerIndex = widened.mapNotNull { it.providerIndex }.distinct().singleOrNull() ?: continue
            if (widened.map { it.parameterTypes }.distinct().size != 1) continue
            val callbackOffset = open + 1 + arguments[0].length + 1
            val registryAccess = with(pass) { registryAccessExpressionAt(source, callbackOffset, index) } ?: continue
            val provider = with(pass) { holderLookupProviderArgumentExpression(registryAccess) }
            val target = reference.groupValues[1].replace(Regex("""\s+"""), "")
            val invocation = mutableListOf("modporterTag").apply { add(providerIndex, provider) }
            val replacement = "modporterTag -> $target.$name(${invocation.joinToString(", ")})"
            val relativeStart = source.substring(open + 1, close).indexOf(arguments[1])
            if (relativeStart < 0) continue
            val absoluteStart = open + 1 + relativeStart
            replacements += absoluteStart..(absoluteStart + arguments[1].length - 1) to replacement
        }
        if (replacements.isEmpty()) return source
        var result = source
        for ((range, replacement) in replacements.sortedByDescending { it.first.first }) {
            result = result.replaceRange(range, replacement)
        }
        return result
    }

    private fun String?.isCompoundTag(): Boolean =
        this?.trim()?.substringBefore('<')?.removeSuffix("[]")?.substringAfterLast('.') == "CompoundTag"

    private fun constructorProviderIndex(
        type: String,
        arguments: List<String>,
        project: Project,
        argumentType: (String) -> String?
    ): Int? {
        val shapes = project.constructors[type] ?: return null
        val legacy = shapes.filter { it.count == arguments.size && it.providerIndex == null }
        val widened = shapes.filter { it.count == arguments.size + 1 }
        if (widened.isEmpty() || widened.any { it.providerIndex == null }) return null
        if (legacy.isEmpty()) return widened.mapNotNull { it.providerIndex }.distinct().singleOrNull()

        val argumentTypes = arguments.map { argument ->
            argumentType(argument)?.let(::simpleTypeName) ?: return null
        }
        fun accepts(shape: Shape): Boolean {
            if (shape.parameterTypes.size != shape.count) return false
            val retained = shape.parameterTypes.filterIndexed { index, _ -> index != shape.providerIndex }
            return retained.size == argumentTypes.size &&
                retained.map(::simpleTypeName) == argumentTypes
        }
        if (legacy.any(::accepts)) return null
        return widened.filter(::accepts).mapNotNull { it.providerIndex }.distinct().singleOrNull()
    }

    private fun methodProviderIndex(name: String, count: Int, types: Set<String>?, project: Project): Int? {
        val shapes = if (types != null) {
            val projectShapes = types.flatMap { project.methods[it]?.get(name).orEmpty() }
            // platform classes: the receiver's own type when it is one, and those above the project's types
            val platformTypes = (types.filter { it !in project.supertypes } + types.flatMap { project.supertypes[it].orEmpty() }.filter { it !in project.supertypes })
                .distinct()
            val platformShapes = platformTypes.flatMap { if (TargetMembers.hasClass(it)) listOf(it) else TargetMembers.classesNamed(it) }.flatMap { platformShapes(name, it) }
            projectShapes + platformShapes
        } else {
            project.methods.values.flatMap { it[name].orEmpty() } + platformShapes(name, null)
        }
        if (shapes.isEmpty() || shapes.any { it.count == count }) return null
        val widened = shapes.filter { it.count == count + 1 }
        if (widened.isEmpty() || widened.any { it.providerIndex == null }) return null
        return widened.map { it.providerIndex }.distinct().singleOrNull()
    }

    /** Shapes of the platform methods [name], declared by [owner] or anything above it (every class when null). */
    private fun platformShapes(name: String, owner: String?): List<Shape> =
        TargetMembers.methodsNamed(name)
            .filter { (declaring, _) -> owner == null || TargetMembers.inheritsFrom(owner, declaring) }
            .map { (_, descriptor) ->
                val parameters = TargetMembers.parameterTypes(descriptor)
                Shape(parameters.size, parameters.indexOf(PROVIDER_DESCRIPTOR).takeIf { it >= 0 })
            }

    private fun hierarchy(type: String, project: Project): Set<String> {
        val seen = linkedSetOf<String>()
        val frontier = ArrayDeque(listOf(type))
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            if (seen.add(current)) project.supertypes[current].orEmpty().forEach(frontier::add)
        }
        return seen
    }

    /** The declared type of a plain-name receiver at the exact lexical binding. */
    private fun receiverType(masked: String, name: String, at: Int): String? =
        JavaScopedDeclarations.visibleAt(masked, name, at)?.type

    private fun enclosingTypes(masked: String, at: Int): List<String> =
        Regex("""\b(?:class|interface|enum|record)\s+($ID)[^{;]*\{""").findAll(masked)
            .mapNotNull { match ->
                val close = JavaSourceText.matchingBrace(masked, match.range.last)
                if (close > 0 && at in match.range.last..close) match.groupValues[1] to (close - match.range.last) else null
            }
            .sortedBy { it.second }
            .map { it.first }
            .toList()

    private fun collect(sources: Collection<String>): Project {
        val methods = HashMap<String, HashMap<String, MutableList<Shape>>>()
        val constructors = HashMap<String, MutableList<Shape>>()
        val supertypes = HashMap<String, List<String>>()
        val declaration = Regex("""(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\s+)*(?:<[^>{};]+>\s+)?(?:([A-Za-z_$][\w$.]*(?:\s*<[^;{}()]*>)?(?:\s*\[\s*\])*)\s+)?($ID)\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)\s*(?:throws\s+[^{;]+)?[{;]""")
        for (source in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val types = Regex("""\b(?:class|interface|enum|record)\s+($ID)\s*(?:<[^{]*?>)?([^{;]*)\{""").findAll(masked).mapNotNull { match ->
                val close = JavaSourceText.matchingBrace(masked, match.range.last)
                if (close < 0) return@mapNotNull null
                supertypes[match.groupValues[1]] = Regex("""\b(?:extends|implements)\s+([^{]+?)(?=\s+(?:extends|implements|permits)\b|$)""")
                    .findAll(match.groupValues[2])
                    .flatMap { clause -> JavaSourceText.splitTopLevelArgs(clause.groupValues[1]).asSequence() }
                    .map { it.trim().substringBefore('<').substringAfterLast('.').trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                Triple(match.groupValues[1], match.range.last, close)
            }.toList()
            for (match in declaration.findAll(masked)) {
                val name = match.groupValues[2]
                if (name in javaKeywords) continue
                val owner = types.filter { (_, open, close) -> match.range.first in open..close }.minByOrNull { (_, open, close) -> close - open }?.first ?: continue
                val returnType = match.groupValues[1].trim()
                val parameterText = match.groupValues[3].trim()
                val parameters = if (parameterText.isEmpty()) emptyList() else JavaSourceText.splitTopLevelArgs(parameterText).map { it.trim() }
                if (parameters.any { !Regex("""^(?:final\s+)?(?:@[\w.]+\s+)*[\w$.<>\[\]?, ]+\s+$ID$""").matches(it) }) continue
                val providerIndex = parameters.indexOfFirst { Regex("""\bHolderLookup\.Provider\s+$ID$""").containsMatchIn(it) }.takeIf { it >= 0 }
                val parameterTypes = parameters.map { parameter ->
                    parameter
                        .replace(Regex("""^(?:final\s+)?(?:@[\w.]+\s+)*"""), "")
                        .replace(Regex("""\s+$ID$"""), "")
                        .trim()
                }
                val shape = Shape(parameters.size, providerIndex, parameterTypes)
                if (returnType.isEmpty() && name == owner) {
                    constructors.getOrPut(name) { ArrayList() } += shape
                } else if (returnType.isNotEmpty() && returnType !in setOf("return", "new", "else", "throw", "class", "interface", "enum", "record")) {
                    methods.getOrPut(owner) { HashMap() }.getOrPut(name) { ArrayList() } += shape
                }
            }
        }
        return Project(methods, constructors, supertypes)
    }

    private val javaKeywords = setOf("if", "for", "while", "switch", "catch", "return", "new", "else", "synchronized", "throw", "case", "do", "try", "super", "this", "assert")
}
