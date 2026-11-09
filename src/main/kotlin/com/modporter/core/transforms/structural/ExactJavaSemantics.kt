package com.modporter.core.transforms.structural

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.stmt.TryStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType

internal class ExactJavaSemantics(private val cu: CompilationUnit) {
    private data class VisibleDeclaration(val declaration: Node, val scope: Node)

    fun hasExactImport(fqn: String): Boolean = cu.imports.any {
        !it.isStatic && !it.isAsterisk && it.nameAsString == fqn
    }

    fun hasDeclaredType(simpleName: String): Boolean =
        cu.findAll(TypeDeclaration::class.java).any { it.nameAsString == simpleName }

    fun hasValueDeclaration(simpleName: String): Boolean =
        cu.findAll(Parameter::class.java).any { it.nameAsString == simpleName } ||
            cu.findAll(VariableDeclarator::class.java).any { it.nameAsString == simpleName }

    fun exactTypeReference(typeText: String, simpleName: String, fqns: Set<String>): Boolean {
        val normalized = typeText.replace(" ", "")
        if (normalized in fqns) return true
        return normalized == simpleName &&
            fqns.count(::hasExactImport) == 1 &&
            !hasDeclaredType(simpleName)
    }

    fun exactTypeReference(typeText: String, simpleName: String, fqn: String): Boolean =
        exactTypeReference(typeText, simpleName, setOf(fqn))

    fun exactStaticScope(scope: Expression, simpleName: String, fqn: String): Boolean {
        val text = scope.toString()
        if (text == fqn) {
            val root = fqn.substringBefore('.')
            return !hasDeclaredType(root) && !hasValueDeclaration(root)
        }
        return text == simpleName &&
            hasExactImport(fqn) &&
            !hasDeclaredType(simpleName) &&
            !hasValueDeclaration(simpleName)
    }

    fun targetTypeReferenceOrNull(
        simpleName: String,
        fqn: String,
        expressionScope: Boolean
    ): String? {
        val conflictingImport = cu.imports.any {
            !it.isStatic && !it.isAsterisk &&
                it.name.identifier == simpleName &&
                it.nameAsString != fqn
        }
        val shadowed = hasDeclaredType(simpleName) ||
            (expressionScope && hasValueDeclaration(simpleName))
        if (!conflictingImport && !shadowed) return simpleName

        val root = fqn.substringBefore('.')
        return if (!hasDeclaredType(root) && !hasValueDeclaration(root)) fqn else null
    }

    fun targetTypeReference(simpleName: String, fqn: String, expressionScope: Boolean): String =
        requireNotNull(targetTypeReferenceOrNull(simpleName, fqn, expressionScope)) {
            "Target type $fqn is inaccessible because both its simple name and package root are shadowed"
        }

    fun isProvablyType(
        expression: Expression,
        simpleName: String,
        fqns: Set<String>
    ): Boolean = when (expression) {
        is EnclosedExpr -> isProvablyType(expression.inner, simpleName, fqns)
        is CastExpr -> exactTypeReference(expression.typeAsString, simpleName, fqns)
        is ObjectCreationExpr -> exactTypeReference(expression.typeAsString, simpleName, fqns)
        is NameExpr -> declarationType(valueDeclaration(expression))?.let {
            exactTypeReference(it, simpleName, fqns)
        } == true
        is FieldAccessExpr -> fieldAccessType(expression)?.let {
            exactTypeReference(it, simpleName, fqns)
        } == true
        is MethodCallExpr -> localMethodReturnType(expression)?.let {
            exactTypeReference(it, simpleName, fqns)
        } == true
        is ConditionalExpr ->
            isProvablyType(expression.thenExpr, simpleName, fqns) &&
                isProvablyType(expression.elseExpr, simpleName, fqns)
        else -> false
    }

    fun isProvablyType(expression: Expression, simpleName: String, fqn: String): Boolean =
        isProvablyType(expression, simpleName, setOf(fqn))

    fun declarationOf(name: NameExpr): Node? = valueDeclaration(name)

    fun referencesTo(declaration: VariableDeclarator): List<NameExpr> {
        val scope = variableLexicalScope(declaration) ?: return referencesToDeclaration(declaration)
        val callable = declaration.findAncestor(CallableDeclaration::class.java).orElse(null)
            ?: return emptyList()
        // Java forbids a local declaration from being shadowed while its lexical scope is
        // active. Scanning that exact scope avoids ambiguous whole-callable name matching
        // when sibling blocks reuse the same identifier.
        return scope.findAll(NameExpr::class.java).filter { name ->
            name.nameAsString == declaration.nameAsString &&
                name.findAncestor(CallableDeclaration::class.java).orElse(null) === callable &&
                isStrictlyBefore(declaration, name)
        }
    }

    fun referencesTo(parameter: Parameter): List<NameExpr> =
        referencesToDeclaration(parameter)

    private fun referencesToDeclaration(declaration: Node): List<NameExpr> {
        val callable = declaration.findAncestor(CallableDeclaration::class.java).orElse(null)
            ?: return emptyList()
        return callable.findAll(NameExpr::class.java).filter { name ->
            name.findAncestor(CallableDeclaration::class.java).orElse(null) === callable &&
                valueDeclaration(name) === declaration
        }
    }

    private fun declarationType(declaration: Node?): String? = when (declaration) {
        is VariableDeclarator -> declaration.typeAsString
        is Parameter -> declaration.type.takeUnless { it.isUnknownType }?.asString()
            ?: inferredLambdaParameterType(declaration)
        else -> null
    }

    private fun inferredLambdaParameterType(parameter: Parameter): String? {
        val lambda = parameter.parentNode.orElse(null) as? LambdaExpr ?: return null
        if (lambda.parameters.size != 1 || lambda.parameters.single() !== parameter) return null
        val variable = lambda.parentNode.orElse(null) as? VariableDeclarator ?: return null
        if (variable.initializer.orElse(null) !== lambda) return null
        val target = variable.type as? ClassOrInterfaceType ?: return null

        val supported = mapOf(
            "Function" to ("java.util.function.Function" to 2),
            "Consumer" to ("java.util.function.Consumer" to 1),
            "Predicate" to ("java.util.function.Predicate" to 1),
            "UnaryOperator" to ("java.util.function.UnaryOperator" to 1),
            "ToIntFunction" to ("java.util.function.ToIntFunction" to 1),
            "ToLongFunction" to ("java.util.function.ToLongFunction" to 1),
            "ToDoubleFunction" to ("java.util.function.ToDoubleFunction" to 1)
        )
        val (owner, arity) = supported[target.nameAsString] ?: return null
        if (!exactTypeReference(target.nameWithScope, target.nameAsString, owner)) return null
        val arguments = target.typeArguments.orElse(null) ?: return null
        if (arguments.size != arity) return null
        return arguments.first().asString()
    }

    private fun valueDeclaration(name: NameExpr): Node? {
        val visible = mutableListOf<VisibleDeclaration>()
        cu.findAll(Parameter::class.java)
            .asSequence()
            .filter { it.nameAsString == name.nameAsString }
            .mapNotNull { visibleParameter(it, name) }
            .forEach(visible::add)
        cu.findAll(VariableDeclarator::class.java)
            .asSequence()
            .filter { it.nameAsString == name.nameAsString }
            .filter { it.parentNode.orElse(null) !is FieldDeclaration }
            .mapNotNull { visibleVariable(it, name) }
            .forEach(visible::add)

        if (visible.isNotEmpty()) {
            val distances = visible.mapNotNull { candidate ->
                ancestorDistance(name, candidate.scope)?.let { candidate to it }
            }
            val nearestDistance = distances.minOfOrNull { it.second } ?: return null
            return distances.filter { it.second == nearestDistance }
                .map { it.first.declaration }
                .singleOrNull()
        }

        return unqualifiedFieldDeclaration(name)
    }

    private fun visibleParameter(parameter: Parameter, use: NameExpr): VisibleDeclaration? {
        return when (val parent = parameter.parentNode.orElse(null)) {
            is CallableDeclaration<*> -> {
                if (use.findAncestor(CallableDeclaration::class.java).orElse(null) === parent) {
                    VisibleDeclaration(parameter, parent)
                } else {
                    null
                }
            }
            is LambdaExpr -> {
                if (parent.body.isAncestorOf(use)) VisibleDeclaration(parameter, parent) else null
            }
            is CatchClause -> {
                if (parent.body.isAncestorOf(use)) VisibleDeclaration(parameter, parent) else null
            }
            else -> null
        }
    }

    private fun visibleVariable(
        declaration: VariableDeclarator,
        use: NameExpr
    ): VisibleDeclaration? {
        if (declaration.isAncestorOf(use) || !isStrictlyBefore(declaration, use)) return null
        val expression = declaration.parentNode.orElse(null) as? VariableDeclarationExpr ?: return null
        return when (val parent = expression.parentNode.orElse(null)) {
            is ExpressionStmt -> {
                val block = parent.parentNode.orElse(null) as? BlockStmt ?: return null
                if (block.isAncestorOf(use) && isStrictlyBefore(parent, use)) {
                    VisibleDeclaration(declaration, block)
                } else {
                    null
                }
            }
            is com.github.javaparser.ast.stmt.ForEachStmt -> {
                if (parent.variable === expression && parent.body.isAncestorOf(use)) {
                    VisibleDeclaration(declaration, parent)
                } else {
                    null
                }
            }
            is ForStmt -> {
                if (parent.initialization.any { it === expression } &&
                    (parent.body.isAncestorOf(use) ||
                        parent.compare.map { it.isAncestorOf(use) }.orElse(false) ||
                        parent.update.any { it.isAncestorOf(use) })
                ) {
                    VisibleDeclaration(declaration, parent)
                } else {
                    null
                }
            }
            is TryStmt -> {
                if (parent.resources.any { it === expression } && parent.tryBlock.isAncestorOf(use)) {
                    VisibleDeclaration(declaration, parent)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun variableLexicalScope(declaration: VariableDeclarator): Node? {
        val expression = declaration.parentNode.orElse(null) as? VariableDeclarationExpr ?: return null
        return when (val parent = expression.parentNode.orElse(null)) {
            is ExpressionStmt -> parent.parentNode.orElse(null) as? BlockStmt
            is com.github.javaparser.ast.stmt.ForEachStmt ->
                parent.takeIf { it.variable === expression }?.body
            is ForStmt ->
                parent.takeIf { loop -> loop.initialization.any { it === expression } }
            is TryStmt ->
                parent.takeIf { statement -> statement.resources.any { it === expression } }?.tryBlock
            else -> null
        }
    }

    private fun unqualifiedFieldDeclaration(name: NameExpr): VariableDeclarator? {
        val callable = name.findAncestor(CallableDeclaration::class.java).orElse(null)
        if (callable != null) {
            val possibleShadow = callable.findAll(Parameter::class.java)
                .any { it.nameAsString == name.nameAsString } ||
                callable.findAll(VariableDeclarator::class.java).any {
                    it.nameAsString == name.nameAsString &&
                        it.parentNode.orElse(null) !is FieldDeclaration
                }
            if (possibleShadow) return null
        }

        val owners = generateSequence(name.parentNode.orElse(null)) {
            it.parentNode.orElse(null)
        }.filterIsInstance<TypeDeclaration<*>>()
        for (owner in owners) {
            val fields = owner.fields.flatMap { it.variables }
                .filter { it.nameAsString == name.nameAsString }
            if (fields.isNotEmpty()) return fields.singleOrNull()
        }
        return null
    }

    private fun fieldAccessType(field: FieldAccessExpr): String? {
        val thisScope = field.scope as? ThisExpr ?: return null
        val qualifiedOwner = thisScope.typeName.map { it.asString() }.orElse(null)
        val owners = generateSequence(field.parentNode.orElse(null)) {
            it.parentNode.orElse(null)
        }.filterIsInstance<TypeDeclaration<*>>().toList()
        val owner = if (qualifiedOwner == null) {
            owners.firstOrNull()
        } else {
            owners.filter { it.nameAsString == qualifiedOwner }.singleOrNull()
        } ?: return null
        return owner.fields
            .flatMap { it.variables }
            .filter { it.nameAsString == field.nameAsString }
            .singleOrNull()
            ?.typeAsString
    }

    private fun localMethodReturnType(call: MethodCallExpr): String? {
        val scope = call.scope.orElse(null)
        if (scope != null && scope !is ThisExpr) return null

        val owners = generateSequence(call.parentNode.orElse(null)) {
            it.parentNode.orElse(null)
        }.filterIsInstance<TypeDeclaration<*>>().toList()
        val qualifiedOwner = (scope as? ThisExpr)?.typeName?.map { it.asString() }?.orElse(null)
        val owner = if (qualifiedOwner == null) {
            owners.firstOrNull()
        } else {
            owners.filter { it.nameAsString == qualifiedOwner }.singleOrNull()
        } as? ClassOrInterfaceDeclaration ?: return null
        if (owner.extendedTypes.isNotEmpty() || owner.implementedTypes.isNotEmpty()) return null

        val crossesAnonymousClass = generateSequence(call.parentNode.orElse(null)) {
            it.parentNode.orElse(null)
        }.takeWhile { it !== owner }.filterIsInstance<ObjectCreationExpr>()
            .any { it.anonymousClassBody.isPresent }
        if (crossesAnonymousClass) return null

        val candidates = owner.members.filterIsInstance<MethodDeclaration>().filter { method ->
            method.nameAsString == call.nameAsString &&
                method.parameters.none { it.isVarArgs } &&
                method.parameters.size == call.arguments.size
        }
        return candidates.singleOrNull()?.typeAsString
    }

    private fun isStrictlyBefore(first: Node, second: Node): Boolean {
        val firstStart = first.range.map { it.begin }.orElse(null) ?: return false
        val secondStart = second.range.map { it.begin }.orElse(null) ?: return false
        return firstStart.isBefore(secondStart)
    }

    private fun ancestorDistance(node: Node, ancestor: Node): Int? {
        var current: Node? = node
        var distance = 0
        while (current != null) {
            if (current === ancestor) return distance
            current = current.parentNode.orElse(null)
            distance++
        }
        return null
    }
}
