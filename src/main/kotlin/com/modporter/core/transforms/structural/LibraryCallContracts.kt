package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.modporter.core.transforms.util.JavaSourceText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Dependency methods whose NeoForge 1.21.1 build changed a call contract in a way a call site can
 * follow exactly (`mappings/forge2neo/library-call-contracts.json`, from javap of both jars).
 *
 * A contract applies only where its owner is the dependency's class: imported by a single-type
 * import or written fully qualified, and not declared by the project being migrated (a project
 * that is itself the library keeps its own, separately migrated, declarations). Instance contracts
 * further require the receiver to be proven an owner value: declared with the owner type, or a
 * `var` initialized from an owner factory the data marks as returning the owner.
 *
 * - `provider-parameter`: the call with its Forge arity gains the registry access in scope at
 *   `providerIndex` (the method's own HolderLookup.Provider, Level, server, entity or game-test
 *   helper); without one the call is left for the compiler.
 * - `appended-argument`: the call with its Forge arity gains `argument`, which the target
 *   implementation is shown to ignore.
 * - `lazy-optional-accessor`: a `LazyOptional` return became a provider whose `accessor` returns
 *   the value or null; the `LazyOptional` read that consumed it is rewritten to its nullable form
 *   (`orElse(null)` to the accessor, the other reads through `Optional.ofNullable`).
 */
internal object LibraryCallContracts {
    private const val RESOURCE = "/mappings/forge2neo/library-call-contracts.json"

    @Serializable
    data class Contract(
        val owner: String,
        val name: String,
        val kind: String,
        val static: Boolean = false,
        val forgeParameters: List<String> = emptyList(),
        val providerIndex: Int = -1,
        val argument: String = "",
        val accessor: String = "",
        val returnsOwner: Boolean = false,
        val notes: String = ""
    ) {
        val simpleName: String get() = owner.substringAfterLast('.')
    }

    @Serializable
    private data class File(val description: String = "", val contracts: List<Contract> = emptyList())

    val contracts: List<Contract> by lazy {
        val stream = LibraryCallContracts::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyList()
        Json { ignoreUnknownKeys = true }
            .decodeFromString(File.serializer(), stream.bufferedReader().readText())
            .contracts
            .filter { it.kind in KINDS }
    }

    fun migrate(pass: StructuralRefactorPass, source: String): String {
        var result = source
        for ((owner, ownerContracts) in contracts.groupBy { it.owner }) {
            if (!result.contains(owner.substringAfterLast('.'))) continue
            if (!libraryOwnerReferenced(result, owner)) continue
            if (owner in pass.projectValueFacts.types || pass.projectSourceDeclarations.declares(owner)) continue
            for (contract in ownerContracts.filter { it.kind == "provider-parameter" && it.static }) {
                result = insertProviders(pass, result, contract)
            }
            result = migrateInstanceContracts(pass, result, ownerContracts)
        }
        return result
    }

    private fun libraryOwnerReferenced(source: String, owner: String): Boolean {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        return Regex("""(?m)^\s*import\s+${Regex.escape(owner)}\s*;""").containsMatchIn(masked) ||
            masked.contains("$owner.")
    }

    /** `Owner.name(args)` with the Forge arity gains the registry access in scope at the provider index. */
    private fun insertProviders(pass: StructuralRefactorPass, source: String, contract: Contract): String = with(pass) {
        val executable = maskJavaCommentsAndLiterals(source)
        val simpleImported = Regex("""(?m)^\s*import\s+${Regex.escape(contract.owner)}\s*;""")
            .containsMatchIn(executable)
        val owners = buildList {
            add(Regex.escape(contract.owner))
            if (simpleImported) add(Regex.escape(contract.simpleName))
        }
        val call = Regex("""(?<![\w$.])(?:${owners.joinToString("|")})\s*\.\s*${Regex.escape(contract.name)}\s*\(""")
        val edits = mutableListOf<Pair<IntRange, String>>()
        for (method in javaMethodRangesIncludingDefault(executable)) {
            val body = executable.substring(method.range)
            for (match in call.findAll(body)) {
                val open = method.range.first + match.range.last
                val close = JavaSourceText.matchingParen(executable, open)
                if (close < 0) continue
                val arguments = source.substring(open + 1, close)
                    .let { if (it.isBlank()) emptyList() else JavaSourceText.splitTopLevelArgs(it) }
                    .map { it.trim() }
                if (arguments.size != contract.forgeParameters.size) continue
                val access = registryAccessExpressionInScope(method.header, body, match.range.first) ?: continue
                val migrated = arguments.toMutableList()
                    .apply { add(contract.providerIndex.coerceIn(0, size), access) }
                edits += (open + 1 until close) to migrated.joinToString(", ")
            }
        }
        return applyStringEdits(source, edits)
    }

    private fun migrateInstanceContracts(
        pass: StructuralRefactorPass,
        source: String,
        ownerContracts: List<Contract>
    ): String {
        val instance = ownerContracts.filter { !it.static && it.kind != "provider-parameter" }
        if (instance.isEmpty() || instance.none { source.contains(".${it.name}(") }) return source
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = parser.parse(source)
        val unit = parsed.result.orElse(null) ?: return source
        if (parsed.problems.isNotEmpty()) return source
        val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
        }
        fun range(node: Node): IntRange? = node.range.map {
            (lineStarts[it.begin.line - 1] + it.begin.column - 1)..(lineStarts[it.end.line - 1] + it.end.column - 1)
        }.orElse(null)
        fun text(node: Node): String? = range(node)?.let { source.substring(it.first, it.last + 1) }

        val owner = ownerContracts.first().owner
        val factories = ownerContracts.filter { it.static && it.returnsOwner }.map { it.name }.toSet()
        val edits = mutableListOf<Pair<IntRange, String>>()
        for (call in unit.findAll(MethodCallExpr::class.java)) {
            val contract = instance.singleOrNull {
                it.name == call.nameAsString && call.arguments.size == it.forgeParameters.size
            } ?: continue
            val receiver = call.scope.orElse(null) ?: continue
            if (!ownerValue(receiver, owner, factories, unit, pass.projectSourceDeclarations)) continue
            when (contract.kind) {
                "appended-argument" -> {
                    if (contract.argument.isBlank()) continue
                    val close = range(call)?.last ?: continue
                    val prefix = if (call.arguments.isEmpty()) "" else ", "
                    edits += (close until close) to prefix + contract.argument
                }
                "lazy-optional-accessor" -> {
                    if (contract.accessor.isBlank()) continue
                    val read = call.parentNode.orElse(null) as? MethodCallExpr ?: continue
                    if (read.scope.orElse(null) !== call) continue
                    val value = "${text(call) ?: continue}.${contract.accessor}()"
                    val arguments = read.arguments.map { text(it) }
                    if (arguments.any { it == null }) continue
                    val replacement = when {
                        read.nameAsString == "orElse" && read.arguments.size == 1 &&
                            read.arguments.single() is NullLiteralExpr -> value
                        read.nameAsString in setOf("orElse", "orElseGet", "orElseThrow", "ifPresent") ->
                            "java.util.Optional.ofNullable($value).${read.nameAsString}(${arguments.joinToString(", ")})"
                        read.nameAsString == "isPresent" && read.arguments.isEmpty() -> "($value != null)"
                        read.nameAsString == "resolve" && read.arguments.isEmpty() ->
                            "java.util.Optional.ofNullable($value)"
                        else -> continue
                    }
                    edits += (range(read) ?: continue) to replacement
                }
            }
        }
        return pass.applyStringEdits(source, edits)
    }

    /** [expression] is an owner value: declared with the owner type or bound to an owner factory call. */
    private fun ownerValue(
        expression: Expression,
        owner: String,
        factories: Set<String>,
        unit: CompilationUnit,
        declarations: ProjectSourceDeclarations
    ): Boolean = when (expression) {
        is MethodCallExpr -> expression.nameAsString in factories &&
            expression.scope.map { ownerReference(it, owner, unit) }.orElse(false)
        is NameExpr -> {
            val declaration = declarations.valueDeclaration(expression)
            when {
                declaration == null -> false
                declaration.type.isVarType -> declaration.initializer
                    ?.let { ownerValue(it, owner, factories, unit, declarations) } == true
                else -> (declarations.resolve(declaration.type, declaration.site) as?
                    ProjectSourceDeclarations.TypeRef.Named)?.name == owner
            }
        }
        else -> false
    }

    /** The owner class itself: its unshadowed imported simple name or its qualified name. */
    private fun ownerReference(expression: Expression, owner: String, unit: CompilationUnit): Boolean = when (expression) {
        is FieldAccessExpr -> expression.toString() == owner
        is NameExpr -> {
            val simple = expression.nameAsString
            simple == owner.substringAfterLast('.') &&
                unit.imports.any { !it.isStatic && !it.isAsterisk && it.nameAsString == owner } &&
                unit.findAll(TypeDeclaration::class.java).none { it.nameAsString == simple } &&
                unit.findAll(Parameter::class.java).none { it.nameAsString == simple } &&
                unit.findAll(VariableDeclarator::class.java).none { it.nameAsString == simple }
        }
        else -> false
    }

    private val KINDS = setOf("provider-parameter", "appended-argument", "lazy-optional-accessor")
}
