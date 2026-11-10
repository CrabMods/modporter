package com.modporter.core.transforms.structural

import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.IfStmt

/** Proves a registry provider from the exact world that produced a serialized BlockEntity receiver. */
internal object ExactReceiverProviderProvenance {
    sealed interface Resolution {
        data object None : Resolution
        data class Unique(val expression: String) : Resolution
        data class Unsafe(val reason: String) : Resolution
    }

    fun providerExpression(
        call: MethodCallExpr,
        callable: MethodDeclaration,
        index: JavaProjectTypeIndex
    ): String? = when (val resolution = resolve(call, callable, index)) {
        Resolution.None -> null
        is Resolution.Unique -> resolution.expression
        is Resolution.Unsafe -> throw IllegalStateException(
            "Unsafe receiver registry provider provenance for ${call.nameAsString}: ${resolution.reason}"
        )
    }

    fun resolve(
        call: MethodCallExpr,
        callable: MethodDeclaration,
        index: JavaProjectTypeIndex
    ): Resolution {
        when (val callback = ExactProjectCallbackProviderProvenance.resolve(call, callable, index)) {
            is ExactProjectCallbackProviderProvenance.Resolution.Unique ->
                return Resolution.Unique(callback.expression)
            is ExactProjectCallbackProviderProvenance.Resolution.Unsafe ->
                return Resolution.Unsafe(callback.reason)
            ExactProjectCallbackProviderProvenance.Resolution.None -> Unit
        }
        val receiver = call.scope.orElse(null) ?: return Resolution.None
        return traceReceiver(receiver, call, callable, index, linkedSetOf())
            ?.let(Resolution::Unique)
            ?: Resolution.None
    }

    private fun traceReceiver(
        expression: Expression,
        use: Node,
        callable: MethodDeclaration,
        index: JavaProjectTypeIndex,
        visitedNames: MutableSet<String>
    ): String? {
        return when (val value = unwrap(expression)) {
            is MethodCallExpr -> providerFromBlockEntityLookup(value, index)
            is NameExpr -> {
                if (!visitedNames.add(value.nameAsString)) return null
                val patternSource = exactPatternSource(value.nameAsString, use, callable)
                if (patternSource != null) {
                    traceReceiver(patternSource, use, callable, index, visitedNames)
                } else {
                    val initializer = index.exactVisibleLocalInitializer(value.nameAsString, use, callable)
                        ?: return null
                    if (localWasReassigned(value.nameAsString, initializer, use, callable)) return null
                    traceReceiver(initializer, use, callable, index, visitedNames)
                }
            }
            else -> null
        }
    }

    private fun providerFromBlockEntityLookup(
        lookup: MethodCallExpr,
        index: JavaProjectTypeIndex
    ): String? {
        if (lookup.nameAsString != "getBlockEntity" || lookup.arguments.size != 1) return null
        val owner = lookup.scope.orElse(null) ?: return null
        if (owner !is NameExpr && owner !is FieldAccessExpr) return null
        val ownerType = index.expressionType(owner, lookup) ?: return null
        if (ownerType !in EXACT_LEVEL_TYPES &&
            EXACT_LEVEL_TYPES.none { expected -> index.isTypeAssignableTo(ownerType, expected) }
        ) return null
        return "${owner}.registryAccess()"
    }

    private fun exactPatternSource(
        name: String,
        use: Node,
        callable: MethodDeclaration
    ): Expression? {
        val candidates = callable.findAll(TypePatternExpr::class.java).mapNotNull { pattern ->
            if (pattern.nameAsString != name) return@mapNotNull null
            val test = pattern.findAncestor(InstanceOfExpr::class.java).orElse(null) ?: return@mapNotNull null
            val branch = test.findAncestor(IfStmt::class.java).orElse(null) ?: return@mapNotNull null
            if (!(branch.condition === test || branch.condition.isAncestorOf(test))) return@mapNotNull null
            if (!(branch.thenStmt === use || branch.thenStmt.isAncestorOf(use))) return@mapNotNull null
            if (wasAssignedBetween(name, test, use, callable)) return@mapNotNull null
            test.expression
        }
        if (candidates.size > 1) {
            throw IllegalStateException("Ambiguous exact receiver pattern provenance for '$name'")
        }
        return candidates.singleOrNull()
    }

    private fun localWasReassigned(
        name: String,
        initializer: Expression,
        use: Node,
        callable: MethodDeclaration
    ): Boolean {
        val declaration = callable.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
            variable.nameAsString == name && variable.initializer.orElse(null) === initializer &&
                visibleFrom(variable, use)
        } ?: return true
        return wasAssignedBetween(name, declaration, use, callable)
    }

    private fun visibleFrom(variable: VariableDeclarator, use: Node): Boolean {
        val declarationEnd = variable.range.orElse(null)?.end ?: return false
        val useBegin = use.range.orElse(null)?.begin ?: return false
        if (declarationEnd >= useBegin) return false
        val scope = variable.findAncestor(BlockStmt::class.java).orElse(null) ?: return false
        return scope === use || scope.isAncestorOf(use)
    }

    private fun wasAssignedBetween(
        name: String,
        start: Node,
        use: Node,
        callable: MethodDeclaration
    ): Boolean {
        val startEnd = start.range.orElse(null)?.end ?: return true
        val useBegin = use.range.orElse(null)?.begin ?: return true
        return callable.findAll(AssignExpr::class.java).any { assignment ->
            val target = assignment.target as? NameExpr ?: return@any false
            if (target.nameAsString != name) return@any false
            val begin = assignment.range.orElse(null)?.begin ?: return@any true
            begin > startEnd && begin < useBegin
        }
    }

    private fun unwrap(expression: Expression): Expression = when (expression) {
        is EnclosedExpr -> unwrap(expression.inner)
        is CastExpr -> unwrap(expression.expression)
        else -> expression
    }

    private val EXACT_LEVEL_TYPES = setOf(
        "net.minecraft.world.level.Level",
        "net.minecraft.client.multiplayer.ClientLevel",
        "net.minecraft.server.level.ServerLevel",
        "net.minecraft.world.level.WorldGenLevel",
        "net.minecraft.world.level.LevelAccessor",
        "net.minecraft.world.level.ServerLevelAccessor"
    )
}
