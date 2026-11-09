package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.*
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Exact enum recipe-type accessors whose enum entry carries a constructor reference for a
 * project recipe with a concrete `Recipe<I>` input.
 *
 * Facts are admitted only from declarations: a real enum entry, a project functional interface,
 * a matching target constructor, a unique enum constructor, a concrete generic path to platform
 * `Recipe<I>`, and a zero-argument RecipeType getter backed by state definitely assigned on that
 * constructor's delegation path.
 */
internal class EnumRecipeTypeInputs private constructor(
    internal val facts: Map<Accessor, String>
) {
    internal data class Accessor(val enumType: String, val entry: String, val getter: String)

    /**
     * Resolve only direct `EnumType.ENTRY.getter()` and statically imported `ENTRY.getter()` uses.
     * Local values shadowing either name make the expression unknown.
     */
    fun inputFor(expression: Expression, unit: CompilationUnit): String? {
        val call = expression as? MethodCallExpr ?: return null
        if (call.arguments.isNotEmpty()) return null
        val scope = call.scope.orElse(null) ?: return null
        val enumTypes = facts.keys.mapTo(linkedSetOf()) { it.enumType }
        val resolved = when (scope) {
            is FieldAccessExpr -> {
                val ownerText = scope.scope.toString()
                val root = ownerText.substringBefore('.')
                if (isValueName(unit, root)) return null
                resolveVisibleEnum(ownerText, unit, enumTypes)?.let { it to scope.nameAsString }
            }
            is NameExpr -> {
                if (isValueName(unit, scope.nameAsString)) return null
                resolveStaticEntry(scope.nameAsString, unit, enumTypes)?.let { it to scope.nameAsString }
            }
            else -> null
        } ?: return null
        return facts[Accessor(resolved.first, resolved.second, call.nameAsString)]
    }

    private fun resolveVisibleEnum(name: String, unit: CompilationUnit, enumTypes: Set<String>): String? {
        val candidates = linkedSetOf<String>()
        if (name in enumTypes) candidates += name
        val first = name.substringBefore('.')
        val suffix = name.removePrefix(first)
        unit.imports.filter { !it.isStatic && !it.isAsterisk && it.nameAsString.substringAfterLast('.') == first }
            .mapTo(candidates) { it.nameAsString + suffix }
        val pkg = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        if (pkg.isNotEmpty()) candidates += "$pkg.$name"
        unit.imports.filter { !it.isStatic && it.isAsterisk }
            .mapTo(candidates) { "${it.nameAsString}.$name" }
        return candidates.filter { it in enumTypes }.distinct().singleOrNull()
    }

    private fun resolveStaticEntry(entry: String, unit: CompilationUnit, enumTypes: Set<String>): String? {
        val candidates = linkedSetOf<String>()
        for (imported in unit.imports.filter { it.isStatic }) {
            if (imported.isAsterisk) {
                val owner = imported.nameAsString
                if (owner in enumTypes && facts.keys.any { it.enumType == owner && it.entry == entry }) candidates += owner
            } else if (imported.nameAsString.substringAfterLast('.') == entry) {
                val owner = imported.nameAsString.substringBeforeLast('.')
                if (owner in enumTypes && facts.keys.any { it.enumType == owner && it.entry == entry }) candidates += owner
            }
        }
        return candidates.singleOrNull()
    }

    private fun isValueName(unit: CompilationUnit, name: String): Boolean =
        unit.findAll(VariableDeclarator::class.java).any { it.nameAsString == name } ||
            unit.findAll(Parameter::class.java).any { it.nameAsString == name }

    companion object {
        fun empty(): EnumRecipeTypeInputs = EnumRecipeTypeInputs(emptyMap())

        fun collect(files: List<Path>): EnumRecipeTypeInputs = Collector(files).collect()
    }

    private class Collector(files: List<Path>) {
        private val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        private val units = files.mapNotNull { file ->
            runCatching {
                val source = file.readText()
                parser.parse(source).result.orElse(null)?.let { ParsedUnit(source, it) }
            }.getOrNull()
        }
        private val declarations: Map<String, Declaration>
        private val simpleProjectNames: Map<String, Set<String>>

        init {
            val found = mutableListOf<Declaration>()
            for (parsed in units) {
                val pkg = parsed.unit.packageDeclaration.map { it.nameAsString }.orElse("")
                for (type in parsed.unit.types) collectDeclarations(parsed, type, pkg, emptyList(), found)
            }
            declarations = found.groupBy { it.fqn }.filterValues { it.size == 1 }.mapValues { it.value.single() }
            simpleProjectNames = declarations.keys.groupBy { it.substringAfterLast('.') }
                .mapValues { (_, values) -> values.toSet() }
        }

        fun collect(): EnumRecipeTypeInputs {
            val candidates = linkedMapOf<Accessor, MutableSet<String>>()
            for (declaration in declarations.values) {
                val enum = declaration.node as? EnumDeclaration ?: continue
                val getters = enum.members.filterIsInstance<MethodDeclaration>().mapNotNull { method ->
                    getterField(declaration, method)?.let { method.nameAsString to it }
                }
                if (getters.isEmpty()) continue
                for (entry in enum.entries) {
                    val factory = entry.arguments.mapIndexedNotNull { index, argument ->
                        val reference = argument as? MethodReferenceExpr ?: return@mapIndexedNotNull null
                        if (reference.identifier != "new") return@mapIndexedNotNull null
                        val target = resolveMethodReferenceType(reference, declaration) ?: return@mapIndexedNotNull null
                        val input = recipeInputOf(target) ?: return@mapIndexedNotNull null
                        Triple(index, target, input)
                    }.singleOrNull() ?: continue
                    val constructor = selectEnumConstructor(declaration, entry.arguments.toList(), factory.first, factory.second)
                        ?: continue
                    val assigned = assignedFields(declaration, constructor) ?: continue
                    for ((getter, field) in getters) {
                        if (field !in assigned) continue
                        candidates.getOrPut(Accessor(declaration.fqn, entry.nameAsString, getter)) { linkedSetOf() } += factory.third
                    }
                }
            }
            return EnumRecipeTypeInputs(
                candidates.filterValues { it.size == 1 }.mapValues { it.value.single() }
            )
        }

        private fun collectDeclarations(
            parsed: ParsedUnit,
            node: TypeDeclaration<*>,
            pkg: String,
            parents: List<String>,
            output: MutableList<Declaration>
        ) {
            val names = parents + node.nameAsString
            val fqn = (listOf(pkg).filter { it.isNotEmpty() } + names).joinToString(".")
            output += Declaration(fqn, parsed, node, names)
            node.members.filterIsInstance<TypeDeclaration<*>>()
                .forEach { collectDeclarations(parsed, it, pkg, names, output) }
        }

        private fun resolveMethodReferenceType(reference: MethodReferenceExpr, context: Declaration): Named? {
            val scope = reference.scope as? TypeExpr ?: return null
            return type(scope.type, context, typeVariables(context)) as? Named
        }

        private fun recipeInputOf(target: Named): String? {
            val inputs = linkedSetOf<String>()
            var unresolvedRecipe = false
            val seen = hashSetOf<String>()
            fun visit(current: Named) {
                val key = current.render()
                if (!seen.add(key)) return
                if (current.raw == RECIPE) {
                    val input = current.arguments.singleOrNull()
                    if (input is Named && input.isConcrete()) inputs += input.raw else unresolvedRecipe = true
                    return
                }
                directParents(current).forEach { parent -> if (parent is Named) visit(parent) }
            }
            visit(target)
            return inputs.singleOrNull()?.takeUnless { unresolvedRecipe }
        }

        private fun selectEnumConstructor(
            enum: Declaration,
            arguments: List<Expression>,
            factoryIndex: Int,
            target: Named
        ): ConstructorDeclaration? {
            val constructors = enum.node.members.filterIsInstance<ConstructorDeclaration>()
            return constructors.filter { constructor ->
                if (constructor.parameters.size != arguments.size) return@filter false
                val parameterType = type(
                    constructor.parameters[factoryIndex].type,
                    enum,
                    typeVariables(enum)
                ) as? Named ?: return@filter false
                val sam = samOf(parameterType, requireProject = true) ?: return@filter false
                if (!isAssignable(target, sam.result)) return@filter false
                if (!targetConstructorMatches(target, sam.parameters)) return@filter false
                arguments.indices.all { index ->
                    index == factoryIndex || argumentCompatible(arguments[index], constructor.parameters[index].type, enum)
                }
            }.singleOrNull()
        }

        private fun targetConstructorMatches(target: Named, parameters: List<TypeRef>): Boolean {
            val declaration = declarations[target.raw] ?: return false
            val constructors = declaration.node.members.filterIsInstance<ConstructorDeclaration>()
            if (constructors.isEmpty()) return parameters.isEmpty()
            val bindings = bindings(declaration, target)
            return constructors.count { constructor ->
                constructor.parameters.size == parameters.size &&
                    constructor.parameters.indices.all { index ->
                        val actual = substitute(
                            type(constructor.parameters[index].type, declaration, typeVariables(declaration)),
                            bindings
                        )
                        sameConcreteType(actual, parameters[index])
                    }
            } == 1
        }

        private fun assignedFields(enum: Declaration, selected: ConstructorDeclaration): Set<String>? {
            val enumFields = enum.node.members.filterIsInstance<FieldDeclaration>()
                .filterNot { it.isStatic }
                .flatMap { it.variables }
                .associateBy { it.nameAsString }
            val initialized = enumFields.values.filter { it.initializer.isPresent }.mapTo(linkedSetOf()) { it.nameAsString }
            val constructors = enum.node.members.filterIsInstance<ConstructorDeclaration>()
            val visiting = hashSetOf<ConstructorDeclaration>()
            fun collect(constructor: ConstructorDeclaration): Set<String>? {
                if (!visiting.add(constructor)) return null
                val delegation = constructor.body.statements.firstOrNull() as? ExplicitConstructorInvocationStmt
                val inherited = if (delegation != null && delegation.isThis) {
                    val target = constructors.filter { candidate ->
                        candidate !== constructor &&
                            candidate.parameters.size == delegation.arguments.size &&
                            delegation.arguments.indices.all { index ->
                                argumentCompatible(delegation.arguments[index], candidate.parameters[index].type, enum)
                            }
                    }.singleOrNull() ?: return null
                    collect(target) ?: return null
                } else emptySet()
                visiting.remove(constructor)
                return inherited + definitelyAssigned(constructor.body, enumFields.keys)
            }
            return initialized + (collect(selected) ?: return null)
        }

        private fun definitelyAssigned(block: BlockStmt, fields: Set<String>): Set<String> {
            val result = linkedSetOf<String>()
            for (statement in block.statements) result += definitelyAssigned(statement, fields)
            return result
        }

        private fun definitelyAssigned(statement: Statement, fields: Set<String>): Set<String> {
            return when (statement) {
                is ExpressionStmt -> {
                    val assignment = statement.expression as? AssignExpr
                    assignment?.target?.let { assignedField(it, fields) }?.let(::setOf).orEmpty()
                }
                is BlockStmt -> definitelyAssigned(statement, fields)
                is IfStmt -> {
                    val otherwise = statement.elseStmt.orElse(null)
                    if (otherwise == null) emptySet()
                    else definitelyAssigned(statement.thenStmt, fields) intersect definitelyAssigned(otherwise, fields)
                }
                else -> emptySet()
            }
        }

        private fun assignedField(target: Expression, fields: Set<String>): String? = when (target) {
            is NameExpr -> target.nameAsString.takeIf { it in fields }
            is FieldAccessExpr -> target.nameAsString.takeIf { target.scope is ThisExpr && it in fields }
            else -> null
        }

        private fun getterField(enum: Declaration, method: MethodDeclaration): String? {
            if (method.parameters.isNotEmpty() || method.body.isEmpty || !recipeTypeReturn(method, enum)) return null
            val statement = method.body.get().statements.singleOrNull() as? ReturnStmt ?: return null
            var expression = statement.expression.orElse(null) ?: return null
            while (expression is EnclosedExpr || expression is CastExpr) {
                expression = when (expression) {
                    is EnclosedExpr -> expression.inner
                    is CastExpr -> expression.expression
                    else -> expression
                }
            }
            val throughGet = expression is MethodCallExpr &&
                expression.nameAsString == "get" &&
                expression.arguments.isEmpty() &&
                expression.scope.isPresent
            if (throughGet) expression = (expression as MethodCallExpr).scope.get()
            while (expression is EnclosedExpr || expression is CastExpr) {
                expression = when (expression) {
                    is EnclosedExpr -> expression.inner
                    is CastExpr -> expression.expression
                    else -> expression
                }
            }
            val fieldName = when (expression) {
                is NameExpr -> expression.nameAsString
                is FieldAccessExpr -> expression.nameAsString.takeIf { expression.scope is ThisExpr }
                else -> null
            } ?: return null
            if (method.findAll(VariableDeclarator::class.java).any { it.nameAsString == fieldName }) return null
            val variable = enum.node.members.filterIsInstance<FieldDeclaration>()
                .filterNot { it.isStatic }
                .flatMap { it.variables }
                .singleOrNull { it.nameAsString == fieldName } ?: return null
            val fieldType = type(variable.type, enum, typeVariables(enum))
            return if (throughGet) {
                val named = fieldType as? Named ?: return null
                fieldName.takeIf { named.arguments.singleOrNull()?.let(::isRecipeType) == true }
            } else fieldName.takeIf { isRecipeType(fieldType) }
        }

        private fun recipeTypeReturn(method: MethodDeclaration, enum: Declaration): Boolean {
            val methodVariables = method.typeParameters.mapTo(linkedSetOf()) { it.nameAsString }
            val returnType = type(method.type, enum, typeVariables(enum) + methodVariables)
            if (isRecipeType(returnType)) return true
            val variable = returnType as? Variable ?: return false
            val parameter = method.typeParameters.singleOrNull { it.nameAsString == variable.name } ?: return false
            return parameter.typeBound.any { isRecipeType(type(it, enum, typeVariables(enum) + methodVariables)) }
        }

        private fun isRecipeType(type: TypeRef?): Boolean =
            type is Named &&
                type.raw == RECIPE_TYPE &&
                type.arguments.singleOrNull() is Wildcard &&
                (type.arguments.single() as Wildcard).bound == null

        private fun samOf(applied: Named, requireProject: Boolean): Sam? {
            if (!requireProject && applied.raw == SUPPLIER) {
                return Sam(emptyList(), effectiveArgument(applied.arguments.singleOrNull()))
            }
            val declaration = declarations[applied.raw] ?: return null
            val owner = declaration.node as? ClassOrInterfaceDeclaration ?: return null
            if (!owner.isInterface) return null
            val methods = abstractMethods(applied, hashSetOf())
                .distinctBy { method -> method.name + "(" + method.parameters.joinToString(",") { it.render() } + ")" }
            return methods.singleOrNull()
        }

        private fun abstractMethods(applied: Named, seen: MutableSet<String>): List<Sam> {
            if (!seen.add(applied.render())) return emptyList()
            val declaration = declarations[applied.raw] ?: return emptyList()
            val owner = declaration.node as? ClassOrInterfaceDeclaration ?: return emptyList()
            if (!owner.isInterface) return emptyList()
            val binding = bindings(declaration, applied)
            val local = owner.methods.filter { method ->
                !method.isStatic && !method.isDefault && !method.isPrivate && method.body.isEmpty &&
                    method.typeParameters.isEmpty()
            }.map { method ->
                val parameters = method.parameters.map {
                    substitute(type(it.type, declaration, typeVariables(declaration)), binding)
                }
                val rawReturn = type(method.type, declaration, typeVariables(declaration))
                Sam(parameters, effectiveReturn(rawReturn, declaration, binding), method.nameAsString)
            }
            val inherited = directParents(applied).filterIsInstance<Named>().flatMap { abstractMethods(it, seen) }
            return local + inherited
        }

        private fun effectiveReturn(
            raw: TypeRef,
            declaration: Declaration,
            binding: Map<String, TypeRef>
        ): TypeRef {
            if (raw is Variable) {
                val actual = binding[raw.name]
                if (actual is Wildcard) {
                    actual.bound?.let { return it }
                    val parameter = typeParameters(declaration).singleOrNull { it.nameAsString == raw.name }
                    val bound = parameter?.typeBound?.singleOrNull()
                    if (bound != null) return substitute(type(bound, declaration, typeVariables(declaration)), binding)
                    return Unknown
                }
            }
            return substitute(raw, binding)
        }

        private fun effectiveArgument(argument: TypeRef?): TypeRef = when (argument) {
            is Wildcard -> argument.bound ?: Unknown
            null -> Unknown
            else -> argument
        }

        private fun argumentCompatible(argument: Expression, parameter: Type, context: Declaration): Boolean {
            val parameterType = type(parameter, context, typeVariables(context))
            return when (argument) {
                is LambdaExpr -> (parameterType as? Named)?.let { samOf(it, requireProject = false) }?.parameters?.size == argument.parameters.size
                is MethodReferenceExpr -> {
                    if (argument.identifier != "new") false
                    else {
                        val target = resolveMethodReferenceType(argument, context)
                        val sam = (parameterType as? Named)?.let { samOf(it, requireProject = false) }
                        target != null && sam != null &&
                            isAssignable(target, sam.result) &&
                            targetConstructorMatches(target, sam.parameters)
                    }
                }
                is BooleanLiteralExpr -> parameterType == Primitive("boolean") || parameterType == Named("java.lang.Boolean")
                is CharLiteralExpr -> parameterType == Primitive("char") || parameterType == Named("java.lang.Character")
                is StringLiteralExpr, is TextBlockLiteralExpr -> parameterType == Named("java.lang.String")
                is IntegerLiteralExpr -> parameterType == Primitive("int") || parameterType == Named("java.lang.Integer")
                is LongLiteralExpr -> parameterType == Primitive("long") || parameterType == Named("java.lang.Long")
                is DoubleLiteralExpr -> parameterType in setOf(Primitive("double"), Primitive("float"), Named("java.lang.Double"), Named("java.lang.Float"))
                is NullLiteralExpr -> parameterType !is Primitive
                is ObjectCreationExpr -> {
                    val actual = type(argument.type, context, typeVariables(context)) as? Named
                    actual != null && isAssignable(actual, parameterType)
                }
                is EnclosedExpr -> argumentCompatible(argument.inner, parameter, context)
                else -> false
            }
        }

        private fun isAssignable(actual: Named, expected: TypeRef): Boolean {
            when (expected) {
                is Wildcard -> return expected.bound?.let { isAssignable(actual, it) } ?: true
                is Named -> if (actual.raw == expected.raw) return invariantArguments(actual.arguments, expected.arguments)
                else -> return false
            }
            val seen = hashSetOf<String>()
            fun walk(current: Named): Boolean {
                if (!seen.add(current.render())) return false
                if (current.raw == (expected as Named).raw &&
                    invariantArguments(current.arguments, expected.arguments)) return true
                return directParents(current).filterIsInstance<Named>().any(::walk)
            }
            return walk(actual)
        }

        private fun invariantArguments(actual: List<TypeRef>, expected: List<TypeRef>): Boolean {
            if (actual.size != expected.size) return actual.isEmpty() && expected.isEmpty()
            return actual.indices.all { index ->
                when (val wanted = expected[index]) {
                    is Wildcard -> wanted.bound?.let { bound ->
                        val value = actual[index] as? Named ?: return@all false
                        isAssignable(value, bound)
                    } ?: true
                    else -> sameConcreteType(actual[index], wanted)
                }
            }
        }

        private fun sameConcreteType(left: TypeRef, right: TypeRef): Boolean =
            left !is UnknownRef && right !is UnknownRef &&
                left !is Variable && right !is Variable &&
                left !is Wildcard && right !is Wildcard &&
                left == right

        private fun directParents(applied: Named): List<TypeRef> {
            val declaration = declarations[applied.raw] ?: return emptyList()
            val binding = bindings(declaration, applied)
            return parentTypes(declaration).map { substitute(it, binding) }
        }

        private fun parentTypes(declaration: Declaration): List<TypeRef> {
            val variables = typeVariables(declaration)
            return when (val node = declaration.node) {
                is ClassOrInterfaceDeclaration -> (node.extendedTypes + node.implementedTypes).map { type(it, declaration, variables) }
                is EnumDeclaration -> node.implementedTypes.map { type(it, declaration, variables) }
                is RecordDeclaration -> node.implementedTypes.map { type(it, declaration, variables) }
                else -> emptyList()
            }
        }

        private fun bindings(declaration: Declaration, applied: Named): Map<String, TypeRef> {
            val parameters = typeParameters(declaration)
            if (parameters.isEmpty()) return emptyMap()
            val arguments = if (applied.arguments.size == parameters.size) applied.arguments
            else parameters.map { Variable(it.nameAsString) }
            return parameters.mapIndexed { index, parameter -> parameter.nameAsString to arguments[index] }.toMap()
        }

        private fun typeParameters(declaration: Declaration) = when (val node = declaration.node) {
            is ClassOrInterfaceDeclaration -> node.typeParameters.toList()
            is RecordDeclaration -> node.typeParameters.toList()
            else -> emptyList()
        }

        private fun typeVariables(declaration: Declaration): Set<String> {
            val variables = linkedSetOf<String>()
            var current: Node? = declaration.node
            while (current is TypeDeclaration<*>) {
                when (current) {
                    is ClassOrInterfaceDeclaration -> current.typeParameters.mapTo(variables) { it.nameAsString }
                    is RecordDeclaration -> current.typeParameters.mapTo(variables) { it.nameAsString }
                }
                current = current.parentNode.orElse(null)
            }
            return variables
        }

        private fun substitute(type: TypeRef, bindings: Map<String, TypeRef>, depth: Int = 0): TypeRef {
            if (depth > 20) return Unknown
            return when (type) {
                is Variable -> bindings[type.name]?.let { substitute(it, bindings, depth + 1) } ?: type
                is Named -> type.copy(arguments = type.arguments.map { substitute(it, bindings, depth + 1) })
                is Wildcard -> type.copy(bound = type.bound?.let { substitute(it, bindings, depth + 1) })
                else -> type
            }
        }

        private fun type(type: Type, context: Declaration, variables: Set<String>): TypeRef {
            return when (type) {
                is ClassOrInterfaceType -> {
                    val name = type.nameWithScope
                    if (name in variables && type.scope.isEmpty && type.typeArguments.isEmpty) Variable(name)
                    else {
                        val raw = qualify(name, context)
                        if (raw == null) Unknown
                        else Named(raw, type.typeArguments.orElse(null).orEmpty().map { argument ->
                            type(argument, context, variables)
                        })
                    }
                }
                is WildcardType -> when {
                    type.extendedType.isPresent -> Wildcard(type(type.extendedType.get(), context, variables))
                    type.superType.isPresent -> Unknown
                    else -> Wildcard(null)
                }
                is PrimitiveType -> Primitive(type.asString())
                is VoidType -> Primitive("void")
                else -> Unknown
            }
        }

        private fun qualify(name: String, context: Declaration): String? {
            if (name.startsWith("java.") || name.startsWith("net.") || name.startsWith("com.") ||
                name.startsWith("org.") || name.startsWith("it.")) return name
            val first = name.substringBefore('.')
            val suffix = name.removePrefix(first)
            val candidates = linkedSetOf<String>()
            context.parsed.unit.imports
                .filter { !it.isStatic && !it.isAsterisk && it.nameAsString.substringAfterLast('.') == first }
                .mapTo(candidates) { it.nameAsString + suffix }
            val pkg = context.parsed.unit.packageDeclaration.map { it.nameAsString }.orElse("")
            if (pkg.isNotEmpty()) candidates += "$pkg.$name"
            for (depth in context.enclosingNames.size downTo 1) {
                val nested = (listOf(pkg).filter { it.isNotEmpty() } + context.enclosingNames.take(depth) + name)
                    .joinToString(".")
                candidates += nested
            }
            context.parsed.unit.imports.filter { !it.isStatic && it.isAsterisk }
                .mapTo(candidates) { "${it.nameAsString}.$name" }
            KNOWN_TYPES[first]?.let { candidates += it + suffix }
            if (first in JAVA_LANG) candidates += "java.lang.$name"
            val project = candidates.filter { it in declarations }
            if (project.isNotEmpty()) return project.distinct().singleOrNull()
            val imported = candidates.filter { candidate ->
                context.parsed.unit.imports.any { imported ->
                    !imported.isStatic && !imported.isAsterisk &&
                        (candidate == imported.nameAsString + suffix || candidate == imported.nameAsString)
                }
            }
            if (imported.isNotEmpty()) return imported.distinct().singleOrNull()
            KNOWN_TYPES[first]?.let { known ->
                val candidate = known + suffix
                if (candidate in candidates) return candidate
            }
            if (first in JAVA_LANG) return "java.lang.$name"
            return simpleProjectNames[first]?.singleOrNull()?.let { it + suffix }
        }

        private data class ParsedUnit(val source: String, val unit: CompilationUnit)
        private data class Declaration(
            val fqn: String,
            val parsed: ParsedUnit,
            val node: TypeDeclaration<*>,
            val enclosingNames: List<String>
        )
        private data class Sam(
            val parameters: List<TypeRef>,
            val result: TypeRef,
            val name: String = "get"
        )

        private sealed interface TypeRef {
            fun render(): String
        }
        private data class Named(val raw: String, val arguments: List<TypeRef> = emptyList()) : TypeRef {
            override fun render(): String = raw + arguments.takeIf { it.isNotEmpty() }
                ?.joinToString(",", "<", ">") { it.render() }.orEmpty()
            fun isConcrete(): Boolean = arguments.all { argument ->
                argument is Named && argument.isConcrete() || argument is Primitive
            }
        }
        private data class Variable(val name: String) : TypeRef {
            override fun render(): String = "var:$name"
        }
        private data class Wildcard(val bound: TypeRef?) : TypeRef {
            override fun render(): String = bound?.let { "? extends ${it.render()}" } ?: "?"
        }
        private data class Primitive(val name: String) : TypeRef {
            override fun render(): String = name
        }
        private sealed interface UnknownRef : TypeRef
        private data object Unknown : UnknownRef {
            override fun render(): String = "<unknown>"
        }

        companion object {
            private const val RECIPE = "net.minecraft.world.item.crafting.Recipe"
            private const val RECIPE_TYPE = "net.minecraft.world.item.crafting.RecipeType"
            private const val SUPPLIER = "java.util.function.Supplier"
            private val KNOWN_TYPES = mapOf(
                "Recipe" to RECIPE,
                "RecipeType" to RECIPE_TYPE,
                "RecipeInput" to "net.minecraft.world.item.crafting.RecipeInput",
                "Container" to "net.minecraft.world.Container",
                "RecipeWrapper" to "net.neoforged.neoforge.items.wrapper.RecipeWrapper",
                "RecipeSerializer" to "net.minecraft.world.item.crafting.RecipeSerializer",
                "Supplier" to SUPPLIER
            )
            private val JAVA_LANG = setOf(
                "Boolean", "Character", "String", "Integer", "Long", "Double", "Float", "Object"
            )
        }
    }
}
