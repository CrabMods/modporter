package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.WildcardType
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The project's own Java type declarations, captured once from the sources as they stand when the
 * per-file structural rules start. Every file therefore sees the same project, independent of the
 * order in which files are rewritten.
 *
 * Rules use it when the declaration that decides a call-site rewrite lives in another file: the
 * declared parameter and return types of a project method, the functional parameter a lambda is
 * passed to, or a one-statement delegation body. Resolution is exact or absent: a name that cannot
 * be bound through the declaring unit's own scope, imports, package or the platform member table,
 * an unclosed type hierarchy, or disagreeing overloads yield `null` and no rewrite.
 */
internal class ProjectSourceDeclarations private constructor(sources: Collection<String>) {

    /** A resolved type reference as written at a declaration site. */
    sealed interface TypeRef {
        /** A canonical class name: package plus dotted nesting, as Java source spells it. */
        data class Named(val name: String) : TypeRef

        /** A type variable of the enclosing method or type. */
        data class Variable(val name: String) : TypeRef

        data class Primitive(val name: String) : TypeRef
    }

    /** A project method declaration and the canonical name of the type that declares it. */
    data class Method(val owner: String, val declaration: MethodDeclaration)

    private val sourceByTopLevelType: Map<String, String>
    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    private val units = HashMap<String, CompilationUnit?>()

    init {
        val indexed = LinkedHashMap<String, String>()
        val duplicated = HashSet<String>()
        for (text in sources) {
            val code = JavaSourceText.maskCommentsAndLiterals(text)
            val packageName = PACKAGE.find(code)?.groupValues?.get(1).orEmpty()
            for (match in TOP_LEVEL_TYPE.findAll(code)) {
                val name = match.groupValues[1]
                val qualified = if (packageName.isEmpty()) name else "$packageName.$name"
                if (indexed.put(qualified, text) != null) duplicated += qualified
            }
        }
        // two sources claiming one name leave that name unknown rather than guessed
        duplicated.forEach { indexed.remove(it) }
        sourceByTopLevelType = indexed
    }

    val isEmpty: Boolean get() = sourceByTopLevelType.isEmpty()

    /** The project declaration of [qualifiedName] (dotted nesting), or null when the project has none. */
    fun type(qualifiedName: String): TypeDeclaration<*>? {
        var topLevel = qualifiedName
        while (topLevel !in sourceByTopLevelType) {
            val dot = topLevel.lastIndexOf('.')
            if (dot < 0) return null
            topLevel = topLevel.substring(0, dot)
        }
        val unit = synchronized(units) {
            units.getOrPut(topLevel) {
                val parsed = parser.parse(sourceByTopLevelType.getValue(topLevel))
                parsed.result.orElse(null)?.takeIf { parsed.problems.isEmpty() }
            }
        } ?: return null
        return unit.findAll(TypeDeclaration::class.java).singleOrNull {
            it.fullyQualifiedName.orElse(null) == qualifiedName
        }
    }

    fun declares(qualifiedName: String): Boolean = type(qualifiedName) != null

    /** Resolves [type] as written at [site], a node of any compilation unit. */
    fun resolve(type: Type, site: Node): TypeRef? = when (type) {
        is PrimitiveType -> TypeRef.Primitive(type.asString())
        is ClassOrInterfaceType -> resolveName(type.nameWithScope, site)
        else -> null
    }

    /** Resolves a source type name ([name] may be qualified or nested) visible at [site]. */
    fun resolveName(name: String, site: Node): TypeRef? {
        val head = name.substringBefore('.')
        val tail = name.substring(head.length)
        if (tail.isEmpty() && isTypeVariable(head, site)) return TypeRef.Variable(head)
        resolveSimpleName(head, site)?.let { return TypeRef.Named(it + tail) }
        // a fully qualified name is canonical as written
        if (tail.isNotEmpty() && head.firstOrNull()?.isLowerCase() == true) return TypeRef.Named(name)
        return null
    }

    private fun isTypeVariable(name: String, site: Node): Boolean {
        var current: Node? = site
        while (current != null) {
            val parameters = when (current) {
                is CallableDeclaration<*> -> current.typeParameters
                is ClassOrInterfaceDeclaration -> current.typeParameters
                is RecordDeclaration -> current.typeParameters
                else -> null
            }
            if (parameters?.any { it.nameAsString == name } == true) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun resolveSimpleName(name: String, site: Node): String? {
        var current: Node? = site
        while (current != null) {
            if (current is TypeDeclaration<*>) {
                if (current.nameAsString == name) return current.fullyQualifiedName.orElse(null)
                current.members.filterIsInstance<TypeDeclaration<*>>()
                    .singleOrNull { it.nameAsString == name }
                    ?.let { return it.fullyQualifiedName.orElse(null) }
            }
            current = current.parentNode.orElse(null)
        }
        val unit = site.findCompilationUnit().orElse(null) ?: return null
        val single = unit.imports.filter { !it.isStatic && !it.isAsterisk && it.name.identifier == name }
        if (single.size > 1) return null
        single.singleOrNull()?.let { return it.nameAsString }
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val samePackage = if (packageName.isEmpty()) name else "$packageName.$name"
        if (declares(samePackage) || platformBinary(samePackage) != null) return samePackage
        if (name in JAVA_LANG) return "java.lang.$name"
        return unit.imports
            .filter { !it.isStatic && it.isAsterisk }
            .map { "${it.nameAsString}.$name" }
            .filter { declares(it) || platformBinary(it) != null }
            .distinct()
            .singleOrNull()
    }

    /** The canonical direct supertypes of [declaration], or null when one cannot be bound. */
    fun supertypes(declaration: TypeDeclaration<*>): List<String>? {
        val written = when (declaration) {
            is ClassOrInterfaceDeclaration -> declaration.extendedTypes + declaration.implementedTypes
            is RecordDeclaration -> declaration.implementedTypes
            is EnumDeclaration -> declaration.implementedTypes
            else -> return null
        }
        val resolved = written.map { (resolve(it, declaration) as? TypeRef.Named)?.name ?: return null }
        val implicit = when {
            declaration is RecordDeclaration -> listOf("java.lang.Record")
            declaration is EnumDeclaration -> listOf("java.lang.Enum")
            declaration is ClassOrInterfaceDeclaration && !declaration.isInterface &&
                declaration.extendedTypes.isEmpty() -> listOf("java.lang.Object")
            else -> emptyList()
        }
        return resolved + implicit
    }

    /**
     * Every project declaration of [name] taking [arity] parameters that a call on [owner] can bind
     * to. Null when the hierarchy is not closed: an unknown supertype, a platform supertype that
     * declares the same name and arity, a varargs overload, or an `Object` method name.
     */
    fun methods(owner: String, name: String, arity: Int): List<Method>? {
        if (name in OBJECT_METHODS) return null
        val result = mutableListOf<Method>()
        val seen = HashSet<String>()
        val frontier = ArrayDeque(listOf(owner))
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            if (!seen.add(current)) continue
            val declaration = type(current)
            if (declaration == null) {
                if (current in IMPLICIT_ROOTS) continue
                val platform = platformBinary(current) ?: return null
                if (TargetMembers.declaresInHierarchy(platform, name, arity)) return null
                continue
            }
            val named = declaration.members.filterIsInstance<MethodDeclaration>()
                .filter { it.nameAsString == name }
            if (named.any { method -> method.parameters.any { it.isVarArgs } }) return null
            named.filter { it.parameters.size == arity }.forEach { result += Method(current, it) }
            frontier.addAll(supertypes(declaration) ?: return null)
        }
        return result
    }

    /**
     * The type a lambda parameter receives: the declared type when written, otherwise the agreed
     * functional parameter of every project overload the enclosing call can bind to.
     */
    fun lambdaParameterType(lambda: LambdaExpr, index: Int): TypeRef.Named? {
        val parameter = lambda.parameters.getOrNull(index) ?: return null
        if (!parameter.type.isUnknownType) return resolve(parameter.type, parameter) as? TypeRef.Named
        val call = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return null
        val argument = call.arguments.indexOfFirst { it === lambda }
        if (argument < 0) return null
        val receiver = receiverType(call) ?: return null
        val candidates = methods(receiver, call.nameAsString, call.arguments.size)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val types = candidates.map { method ->
            functionalParameterType(
                method.declaration.parameters[argument].type,
                method.declaration,
                lambda.parameters.size,
                index
            ) ?: return null
        }
        return types.distinct().singleOrNull()
    }

    private fun functionalParameterType(type: Type, site: Node, arity: Int, index: Int): TypeRef.Named? {
        val functional = type as? ClassOrInterfaceType ?: return null
        val raw = resolveName(functional.nameWithScope, site) as? TypeRef.Named ?: return null
        val shape = FUNCTIONAL_SHAPES[raw.name] ?: return null
        if (shape.size != arity) return null
        val argument = functional.typeArguments.orElse(null)?.getOrNull(shape[index]) ?: return null
        val bound = (argument as? WildcardType)?.let {
            it.extendedType.orElse(null) ?: it.superType.orElse(null)
        } ?: argument
        return resolve(bound, site) as? TypeRef.Named
    }

    /** The canonical type a call is dispatched on: its typed receiver, or the enclosing class. */
    fun receiverType(call: MethodCallExpr): String? {
        val scope = call.scope.orElse(null)
            ?: return exactEnclosingNamedClass(call)?.fullyQualifiedName?.orElse(null)
        return expressionType(scope)?.name ?: staticTypeScope(scope)
    }

    private fun staticTypeScope(scope: Expression): String? {
        val name = scope as? NameExpr ?: return null
        if (valueBinding(name.nameAsString, name) != null) return null
        // a value name the walk cannot see (an inherited or outer field) is never read as a type
        if (name.nameAsString.firstOrNull()?.isUpperCase() != true) return null
        return (resolveName(name.nameAsString, name) as? TypeRef.Named)?.name
    }

    /** The declared or proven type of [expression], as far as source declarations show it. */
    fun expressionType(expression: Expression): TypeRef.Named? = when (expression) {
        is EnclosedExpr -> expressionType(expression.inner)
        is CastExpr -> resolve(expression.type, expression) as? TypeRef.Named
        is ObjectCreationExpr -> if (expression.anonymousClassBody.isPresent) null
            else resolve(expression.type, expression) as? TypeRef.Named
        is ThisExpr -> if (expression.typeName.isPresent) null
            else exactEnclosingNamedClass(expression)?.fullyQualifiedName?.orElse(null)?.let { TypeRef.Named(it) }
        is NameExpr -> when (val binding = valueBinding(expression.nameAsString, expression)) {
            null -> null
            is Binding.LambdaParameter -> lambdaParameterType(binding.lambda, binding.index)
            is Binding.Declared -> if (binding.type.isVarType) {
                binding.initializer?.let(::expressionType)
            } else {
                resolve(binding.type, binding.site) as? TypeRef.Named
            }
        }
        is MethodCallExpr -> returnType(expression)
        else -> null
    }

    /**
     * The element type of a value declared as a `java.util` collection or `Iterable` of one type
     * argument (`List<Foo>`, `Set<? extends Foo>`), or null when its declaration does not show it.
     */
    fun declaredElementType(expression: NameExpr): TypeRef.Named? {
        val binding = valueBinding(expression.nameAsString, expression) as? Binding.Declared ?: return null
        val declared = binding.type as? ClassOrInterfaceType ?: return null
        val raw = resolveName(declared.nameWithScope, binding.site) as? TypeRef.Named ?: return null
        if (raw.name !in ELEMENT_CONTAINERS) return null
        val argument = declared.typeArguments.orElse(null)?.singleOrNull() ?: return null
        val bound = (argument as? WildcardType)?.let {
            if (it.superType.isPresent) return null
            it.extendedType.orElse(null) ?: return null
        } ?: argument
        return resolve(bound, binding.site) as? TypeRef.Named
    }

    /** The written type, declaration site and initializer of the value a name refers to. */
    class ValueDeclaration(val type: Type, val site: Node, val initializer: Expression?)

    /** The declaration [name] binds to (a local, loop or catch variable, parameter or own field). */
    fun valueDeclaration(name: NameExpr): ValueDeclaration? =
        (valueBinding(name.nameAsString, name) as? Binding.Declared)
            ?.let { ValueDeclaration(it.type, it.site, it.initializer) }

    private sealed interface Binding {
        class Declared(val type: Type, val site: Node, val initializer: Expression?) : Binding
        class LambdaParameter(val lambda: LambdaExpr, val index: Int) : Binding
    }

    private fun returnType(call: MethodCallExpr): TypeRef.Named? {
        val receiver = receiverType(call) ?: return null
        val candidates = methods(receiver, call.nameAsString, call.arguments.size)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return candidates.map { method ->
            resolve(method.declaration.type, method.declaration) as? TypeRef.Named ?: return null
        }.distinct().singleOrNull()
    }

    /**
     * The innermost value binding of [name] visible at [use]: a lambda parameter, a local declared
     * before the use, a catch or loop variable, a callable parameter or a field of the enclosing
     * type; null when no binding is visible.
     */
    private fun valueBinding(name: String, use: Node): Binding? {
        var child: Node = use
        var current: Node? = use.parentNode.orElse(null)
        while (current != null) {
            when (current) {
                is LambdaExpr -> {
                    val index = current.parameters.indexOfFirst { it.nameAsString == name }
                    if (index >= 0) return Binding.LambdaParameter(current, index)
                }
                is BlockStmt -> {
                    val position = current.statements.indexOfFirst { it === child }
                    val visible = if (position < 0) emptyList() else current.statements.take(position)
                    visible.asReversed().forEach { statement ->
                        val declaration = (statement as? ExpressionStmt)?.expression as? VariableDeclarationExpr
                        declaration?.variables?.singleOrNull { it.nameAsString == name }?.let { variable ->
                            return Binding.Declared(variable.type, variable, variable.initializer.orElse(null))
                        }
                    }
                }
                is ForStmt -> current.initialization.filterIsInstance<VariableDeclarationExpr>()
                    .flatMap { it.variables }
                    .singleOrNull { it.nameAsString == name }
                    ?.let { variable ->
                        return Binding.Declared(variable.type, variable, variable.initializer.orElse(null))
                    }
                is ForEachStmt -> current.variable.variables.singleOrNull { it.nameAsString == name }
                    ?.let { variable -> return Binding.Declared(variable.type, variable, null) }
                is CatchClause -> if (current.parameter.nameAsString == name) {
                    return Binding.Declared(current.parameter.type, current.parameter, null)
                }
                // an anonymous class body may declare or inherit the name itself
                is ObjectCreationExpr -> if (current.anonymousClassBody.orElse(null)?.any { it === child } == true) {
                    return null
                }
                is CallableDeclaration<*> -> current.parameters.singleOrNull { it.nameAsString == name }
                    ?.let { parameter -> return Binding.Declared(parameter.type, parameter, null) }
                is TypeDeclaration<*> -> {
                    current.members.filterIsInstance<FieldDeclaration>()
                        .flatMap { it.variables }
                        .singleOrNull { it.nameAsString == name }
                        ?.let { variable ->
                            return Binding.Declared(variable.type, variable, variable.initializer.orElse(null))
                        }
                    (current as? RecordDeclaration)?.parameters?.singleOrNull { it.nameAsString == name }
                        ?.let { parameter -> return Binding.Declared(parameter.type, parameter, null) }
                    // an inherited or outer field is not resolved here
                    return null
                }
            }
            child = current
            current = current.parentNode.orElse(null)
        }
        return null
    }

    companion object {
        val EMPTY = ProjectSourceDeclarations(emptyList())

        fun collect(files: List<Path>): ProjectSourceDeclarations =
            ProjectSourceDeclarations(files.mapNotNull { runCatching { it.readText() }.getOrNull() })

        fun of(sources: Collection<String>): ProjectSourceDeclarations = ProjectSourceDeclarations(sources)

        /**
         * The binary name of [qualifiedName] in the NeoForge 1.21.1 member table (nested types are
         * `$`-separated there), or null when it is not a platform class the table lists.
         */
        fun platformBinary(qualifiedName: String): String? {
            if (!TargetMembers.isPlatformClass(qualifiedName)) return null
            var candidate = qualifiedName
            while (true) {
                if (TargetMembers.hasClass(candidate)) return candidate
                val dot = candidate.lastIndexOf('.')
                if (dot < 0) return null
                val outer = candidate.substring(0, dot).substringAfterLast('.')
                if (outer.firstOrNull()?.isUpperCase() != true) return null
                candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1)
            }
        }

        private val PACKAGE = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
        private val TOP_LEVEL_TYPE = Regex(
            """(?m)^(?:@[\w.]+(?:\([^)]*\))?\s+)*""" +
                """(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|strictfp)\s+)*""" +
                """(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)"""
        )
        private val IMPLICIT_ROOTS = setOf("java.lang.Object", "java.lang.Record", "java.lang.Enum")
        private val ELEMENT_CONTAINERS = setOf(
            "java.lang.Iterable", "java.util.Collection", "java.util.List", "java.util.Set",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet", "java.util.LinkedHashSet"
        )
        private val OBJECT_METHODS = setOf(
            "equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait", "clone", "finalize"
        )
        private val JAVA_LANG = setOf(
            "Object", "String", "CharSequence", "Number", "Integer", "Long", "Short", "Byte", "Float",
            "Double", "Boolean", "Character", "Void", "Class", "Enum", "Record", "Iterable", "Runnable",
            "Comparable", "Throwable", "Exception", "RuntimeException", "Error"
        )

        /** java.util.function interfaces: for each lambda parameter, the type argument that types it. */
        private val FUNCTIONAL_SHAPES = mapOf(
            "java.util.function.UnaryOperator" to listOf(0),
            "java.util.function.Function" to listOf(0),
            "java.util.function.Consumer" to listOf(0),
            "java.util.function.Predicate" to listOf(0),
            "java.util.function.ToIntFunction" to listOf(0),
            "java.util.function.ToLongFunction" to listOf(0),
            "java.util.function.ToDoubleFunction" to listOf(0),
            "java.util.function.BiFunction" to listOf(0, 1),
            "java.util.function.BiConsumer" to listOf(0, 1),
            "java.util.function.BiPredicate" to listOf(0, 1),
            "java.util.function.BinaryOperator" to listOf(0, 0)
        )
    }
}
