package com.modporter.core.transforms.structural

import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.stmt.ReturnStmt

/** Proves the world that supplied a lambda callback receiver through exact project source. */
internal object ExactProjectCallbackProviderProvenance {
    sealed interface Resolution {
        data object None : Resolution
        data class Unique(val expression: String) : Resolution
        data class Unsafe(val reason: String) : Resolution
    }

    private sealed interface Trace {
        data class Origins(val values: List<BoundExpression>) : Trace
        data class Unsafe(val reason: String) : Trace
    }

    private sealed interface OriginTrace {
        data class Exact(val value: BoundExpression) : OriginTrace
        data class Unsafe(val reason: String) : OriginTrace
    }

    private data class BoundExpression(
        val expression: Expression,
        val use: Node,
        val callable: MethodDeclaration,
        val bindings: Map<String, BoundExpression>
    )

    private data class NameStep(
        val callable: MethodDeclaration,
        val name: String,
        val optional: Boolean
    )

    private data class TracePath(
        val methods: Set<MethodDeclaration> = emptySet(),
        val names: Set<NameStep> = emptySet(),
        val depth: Int = 0
    ) {
        fun step(): TracePath? = takeIf { depth < MAX_TRACE_DEPTH }?.copy(depth = depth + 1)
    }

    fun resolve(
        call: MethodCallExpr,
        callable: MethodDeclaration,
        index: JavaProjectTypeIndex
    ): Resolution {
        val receiver = unwrap(call.scope.orElse(null) ?: return Resolution.None) as? NameExpr
            ?: return Resolution.None
        val lambdaBinding = enclosingLambdaBinding(receiver.nameAsString, receiver, callable)
            ?: return Resolution.None
        val (lambda, lambdaParameterIndex) = lambdaBinding
        val receiverType = index.expressionType(receiver, call)
            ?: return Resolution.Unsafe(
                "callback dispatch for '${receiver.nameAsString}' has an unresolved receiver type"
            )
        if (!index.isTypeAssignableTo(receiverType, BLOCK_ENTITY)) return Resolution.None
        if (lambdaParameterWasAssigned(lambda, receiver.nameAsString, call)) {
            return Resolution.Unsafe("callback receiver '${receiver.nameAsString}' is reassigned")
        }
        val callback = index.exactLambdaCallbackBinding(lambda, lambdaParameterIndex)
            ?: return Resolution.Unsafe("callback dispatch for '${receiver.nameAsString}' is not exact")
        val callbackFormal = callback.method.parameters.getOrNull(callback.callbackArgumentIndex)
            ?: return Resolution.Unsafe("callback formal is unavailable")
        val bindings = callback.method.parameters.mapIndexedNotNull { parameterIndex, parameter ->
            callback.call.arguments.getOrNull(parameterIndex)?.let { argument ->
                parameter.nameAsString to BoundExpression(argument, callback.call, callable, emptyMap())
            }
        }.toMap()
        val trace = traceCallbackDeliveries(
            callback.method,
            callbackFormal.nameAsString,
            bindings,
            index,
            TracePath()
        )
        val origins = when (trace) {
            is Trace.Origins -> trace.values
            is Trace.Unsafe -> return Resolution.Unsafe(trace.reason)
        }
        val providers = origins.map { origin ->
            providerExpression(origin, index)
                ?: return Resolution.Unsafe("callback receiver world has no exact registry provider")
        }.distinct()
        return when (providers.size) {
            1 -> Resolution.Unique(providers.single())
            else -> Resolution.Unsafe("callback receiver has ${providers.size} registry origins")
        }
    }

    private fun traceCallbackDeliveries(
        method: MethodDeclaration,
        callbackName: String,
        bindings: Map<String, BoundExpression>,
        index: JavaProjectTypeIndex,
        path: TracePath
    ): Trace {
        if (method in path.methods) return Trace.Unsafe("callback helper cycle is unresolved")
        val stepped = path.step() ?: return Trace.Unsafe("callback provenance exceeds the trace bound")
        val methodPath = stepped.copy(methods = stepped.methods + method)
        val uses = method.findAll(NameExpr::class.java).filter { use ->
            use.nameAsString == callbackName &&
                use.findAncestor(MethodDeclaration::class.java).orElse(null) === method
        }
        if (uses.isEmpty()) return Trace.Unsafe("callback is never delivered a value")
        val traces = uses.map { use ->
            if (nestedLambdaWithin(use, method)) {
                return Trace.Unsafe("callback invocation is deferred through a nested lambda")
            }
            val parentCall = use.parentNode.orElse(null) as? MethodCallExpr
                ?: return Trace.Unsafe("callback escapes exact invocation")
            when {
                parentCall.scope.orElse(null) === use &&
                    parentCall.nameAsString == "accept" &&
                    parentCall.arguments.size == 1 ->
                    traceValue(
                        BoundExpression(parentCall.arguments.single(), parentCall, method, bindings),
                        index,
                        methodPath
                    )
                parentCall.arguments.any { it === use } &&
                    parentCall.arguments.size == 1 &&
                    parentCall.nameAsString in setOf("ifPresent", "map") -> {
                    val optional = parentCall.scope.orElse(null)
                        ?: return Trace.Unsafe("callback optional source is absent")
                    val optionalType = index.expressionType(optional, parentCall)
                    if (optionalType != OPTIONAL) {
                        return Trace.Unsafe("callback delivery is not a typed Optional contract")
                    }
                    traceOptional(
                        BoundExpression(optional, parentCall, method, bindings),
                        index,
                        methodPath
                    )
                }
                else -> Trace.Unsafe("callback escapes exact invocation")
            }
        }
        return merge(traces)
    }

    private fun traceOptional(
        bound: BoundExpression,
        index: JavaProjectTypeIndex,
        path: TracePath
    ): Trace {
        val stepped = path.step() ?: return Trace.Unsafe("callback provenance exceeds the trace bound")
        return when (val expression = unwrap(bound.expression)) {
            is NameExpr -> traceName(expression, bound, index, stepped, optional = true)
            is ConditionalExpr -> merge(
                listOf(
                    traceOptional(bound.copy(expression = expression.thenExpr), index, stepped),
                    traceOptional(bound.copy(expression = expression.elseExpr), index, stepped)
                )
            )
            is MethodCallExpr -> {
                if (isOptionalOfNullable(expression, index)) {
                    traceValue(bound.copy(expression = expression.arguments.single()), index, stepped)
                } else {
                    val declaration = index.exactProjectMethodDeclaration(expression)
                        ?: return Trace.Unsafe("optional callback helper dispatch is unresolved")
                    traceMethodReturns(expression, declaration, bound, index, stepped, optional = true)
                }
            }
            else -> Trace.Unsafe("optional callback source is not exact")
        }
    }

    private fun traceValue(
        bound: BoundExpression,
        index: JavaProjectTypeIndex,
        path: TracePath
    ): Trace {
        val stepped = path.step() ?: return Trace.Unsafe("callback provenance exceeds the trace bound")
        return when (val expression = unwrap(bound.expression)) {
            is NullLiteralExpr -> Trace.Origins(emptyList())
            is NameExpr -> traceName(expression, bound, index, stepped, optional = false)
            is ConditionalExpr -> merge(
                listOf(
                    traceValue(bound.copy(expression = expression.thenExpr), index, stepped),
                    traceValue(bound.copy(expression = expression.elseExpr), index, stepped)
                )
            )
            is MethodCallExpr -> {
                val direct = blockEntityLookupOrigin(expression, bound, index, stepped)
                if (direct != null) {
                    direct
                } else {
                    val declaration = index.exactProjectMethodDeclaration(expression)
                        ?: return Trace.Unsafe("callback value helper dispatch is unresolved")
                    traceMethodReturns(expression, declaration, bound, index, stepped, optional = false)
                }
            }
            else -> Trace.Unsafe("callback receiver value is not source-backed")
        }
    }

    private fun traceName(
        expression: NameExpr,
        bound: BoundExpression,
        index: JavaProjectTypeIndex,
        path: TracePath,
        optional: Boolean
    ): Trace {
        val nameStep = NameStep(bound.callable, expression.nameAsString, optional)
        if (nameStep in path.names) {
            return Trace.Unsafe("callback receiver alias '${expression.nameAsString}' is cyclic")
        }
        val namedPath = path.copy(names = path.names + nameStep)
        bound.bindings[expression.nameAsString]?.let { replacement ->
            if (parameterWasAssigned(expression.nameAsString, bound.use, bound.callable)) {
                return Trace.Unsafe("callback receiver formal '${expression.nameAsString}' is reassigned")
            }
            return if (optional) traceOptional(replacement, index, namedPath)
            else traceValue(replacement, index, namedPath)
        }
        val initializer = index.exactVisibleLocalInitializer(
            expression.nameAsString,
            bound.use,
            bound.callable
        ) ?: return Trace.Unsafe("callback receiver alias '${expression.nameAsString}' is unresolved")
        if (wasAssignedBetween(expression.nameAsString, initializer, bound.use, bound.callable)) {
            return Trace.Unsafe("callback receiver alias '${expression.nameAsString}' is reassigned")
        }
        val replacement = bound.copy(expression = initializer, use = initializer)
        return if (optional) traceOptional(replacement, index, namedPath)
        else traceValue(replacement, index, namedPath)
    }

    private fun traceMethodReturns(
        call: MethodCallExpr,
        declaration: MethodDeclaration,
        callerBound: BoundExpression,
        index: JavaProjectTypeIndex,
        path: TracePath,
        optional: Boolean
    ): Trace {
        if (declaration in path.methods) return Trace.Unsafe("callback helper cycle is unresolved")
        val stepped = path.step() ?: return Trace.Unsafe("callback provenance exceeds the trace bound")
        val methodPath = stepped.copy(methods = stepped.methods + declaration)
        val parameters = declaration.parameters
        if (parameters.size != call.arguments.size) {
            return Trace.Unsafe("callback helper arity changed")
        }
        val bindings = parameters.mapIndexed { parameterIndex, parameter ->
            parameter.nameAsString to BoundExpression(
                call.arguments[parameterIndex],
                call,
                callerBound.callable,
                callerBound.bindings
            )
        }.toMap()
        val returns = declaration.findAll(ReturnStmt::class.java).filter { statement ->
            statement.findAncestor(MethodDeclaration::class.java).orElse(null) === declaration &&
                !nestedLambdaWithin(statement, declaration)
        }.mapNotNull { it.expression.orElse(null) }
        if (returns.isEmpty()) return Trace.Unsafe("callback helper has no returned value")
        val traces = returns.map { returned ->
            val next = BoundExpression(returned, returned, declaration, bindings)
            if (optional) traceOptional(next, index, methodPath)
            else traceValue(next, index, methodPath)
        }
        return merge(traces)
    }

    private fun blockEntityLookupOrigin(
        lookup: MethodCallExpr,
        bound: BoundExpression,
        index: JavaProjectTypeIndex,
        path: TracePath
    ): Trace? {
        if (lookup.nameAsString != "getBlockEntity" || lookup.arguments.size != 1) return null
        val owner = lookup.scope.orElse(null) ?: return null
        val ownerType = index.expressionType(owner, lookup) ?: return null
        if (!index.isTypeAssignableTo(ownerType, BLOCK_GETTER)) return null
        if (!index.isExpressionAssignableTo(lookup.arguments.single(), lookup, BLOCK_POS)) {
            return Trace.Unsafe("BlockGetter.getBlockEntity argument is not an exact BlockPos")
        }
        return when (val origin = resolveOrigin(
            BoundExpression(owner, lookup, bound.callable, bound.bindings),
            index,
            path
        )) {
            is OriginTrace.Exact -> Trace.Origins(listOf(origin.value))
            is OriginTrace.Unsafe -> Trace.Unsafe(origin.reason)
        }
    }

    private fun resolveOrigin(
        bound: BoundExpression,
        index: JavaProjectTypeIndex,
        path: TracePath
    ): OriginTrace {
        val stepped = path.step() ?: return OriginTrace.Unsafe("callback origin exceeds the trace bound")
        return when (val expression = unwrap(bound.expression)) {
            is NameExpr -> {
                val nameStep = NameStep(bound.callable, expression.nameAsString, optional = false)
                if (nameStep in stepped.names) {
                    return OriginTrace.Unsafe("callback origin '${expression.nameAsString}' is cyclic")
                }
                val namedPath = stepped.copy(names = stepped.names + nameStep)
                bound.bindings[expression.nameAsString]?.let { replacement ->
                    if (parameterWasAssigned(expression.nameAsString, bound.use, bound.callable)) {
                        return OriginTrace.Unsafe("callback origin formal '${expression.nameAsString}' is reassigned")
                    }
                    return resolveOrigin(replacement, index, namedPath)
                }
                val initializer = index.exactVisibleLocalInitializer(
                    expression.nameAsString,
                    bound.use,
                    bound.callable
                )
                if (initializer != null) {
                    if (wasAssignedBetween(expression.nameAsString, initializer, bound.use, bound.callable)) {
                        return OriginTrace.Unsafe("callback origin alias '${expression.nameAsString}' is reassigned")
                    }
                    return resolveOrigin(bound.copy(expression = initializer, use = initializer), index, namedPath)
                }
                val parameter = bound.callable.parameters.singleOrNull {
                    it.nameAsString == expression.nameAsString
                } ?: return OriginTrace.Unsafe("callback origin '${expression.nameAsString}' is unresolved")
                if (wasAssignedBetween(expression.nameAsString, parameter, bound.use, bound.callable)) {
                    return OriginTrace.Unsafe("callback origin formal '${expression.nameAsString}' is reassigned")
                }
                OriginTrace.Exact(bound.copy(expression = expression))
            }
            else -> OriginTrace.Unsafe("callback origin expression is not a stable local or parameter")
        }
    }

    private fun providerExpression(
        origin: BoundExpression,
        index: JavaProjectTypeIndex
    ): String? {
        val expression = unwrap(origin.expression) as? NameExpr ?: return null
        val type = index.expressionType(expression, origin.use) ?: return null
        if (REGISTRY_WORLD_TYPES.none { expected -> index.isTypeAssignableTo(type, expected) }) return null
        val parameter = origin.callable.parameters.singleOrNull {
            it.nameAsString == expression.nameAsString
        } ?: return null
        if (!ExactNullabilityProof.parameterProvenNonNullAt(parameter, origin.callable, origin.use)) return null
        return "$expression.registryAccess()"
    }

    private fun enclosingLambdaBinding(
        name: String,
        use: Node,
        callable: MethodDeclaration
    ): Pair<LambdaExpr, Int>? {
        var cursor: Node? = use.parentNode.orElse(null)
        while (cursor != null && cursor !== callable) {
            if (cursor is LambdaExpr) {
                val parameterIndex = cursor.parameters.indexOfFirst { it.nameAsString == name }
                if (parameterIndex >= 0) return cursor to parameterIndex
            }
            cursor = cursor.parentNode.orElse(null)
        }
        return null
    }

    private fun lambdaParameterWasAssigned(
        lambda: LambdaExpr,
        name: String,
        use: Node
    ): Boolean {
        val useBegin = use.range.orElse(null)?.begin ?: return true
        return lambda.findAll(AssignExpr::class.java).any { assignment ->
            val target = assignment.target as? NameExpr ?: return@any false
            if (target.nameAsString != name) return@any false
            val nearestLambda = assignment.findAncestor(LambdaExpr::class.java).orElse(null)
            if (nearestLambda !== lambda) return@any false
            val begin = assignment.range.orElse(null)?.begin ?: return@any true
            begin < useBegin
        }
    }

    private fun parameterWasAssigned(
        name: String,
        use: Node,
        callable: MethodDeclaration
    ): Boolean {
        val parameter = callable.parameters.singleOrNull { it.nameAsString == name } ?: return false
        return wasAssignedBetween(name, parameter, use, callable)
    }

    private fun wasAssignedBetween(
        name: String,
        start: Node,
        use: Node,
        callable: MethodDeclaration
    ): Boolean {
        val startEnd = start.range.orElse(null)?.end ?: return true
        val useBegin = use.range.orElse(null)?.begin ?: return true
        val useLambda = use.findAncestor(LambdaExpr::class.java).orElse(null)
        return callable.findAll(AssignExpr::class.java).any { assignment ->
            val target = assignment.target as? NameExpr ?: return@any false
            if (target.nameAsString != name) return@any false
            if (assignment.findAncestor(MethodDeclaration::class.java).orElse(null) !== callable) return@any false
            if (assignment.findAncestor(LambdaExpr::class.java).orElse(null) !== useLambda) return@any false
            val begin = assignment.range.orElse(null)?.begin ?: return@any true
            begin > startEnd && begin < useBegin
        }
    }

    private fun nestedLambdaWithin(node: Node, method: MethodDeclaration): Boolean {
        val lambda = node.findAncestor(LambdaExpr::class.java).orElse(null) ?: return false
        return lambda.findAncestor(MethodDeclaration::class.java).orElse(null) === method
    }

    private fun merge(traces: List<Trace>): Trace {
        traces.filterIsInstance<Trace.Unsafe>().firstOrNull()?.let { return it }
        val origins = traces.filterIsInstance<Trace.Origins>()
            .flatMap { it.values }
            .distinctBy { Triple(it.expression.toString(), it.callable, it.use.toString()) }
        return Trace.Origins(origins)
    }

    private fun isOptionalOfNullable(
        call: MethodCallExpr,
        index: JavaProjectTypeIndex
    ): Boolean = call.nameAsString == "ofNullable" &&
        call.arguments.size == 1 &&
        index.exactMethodCallReceiverType(call) == OPTIONAL

    private fun unwrap(expression: Expression): Expression = when (expression) {
        is EnclosedExpr -> unwrap(expression.inner)
        is CastExpr -> unwrap(expression.expression)
        else -> expression
    }

    private const val MAX_TRACE_DEPTH = 64
    private const val OPTIONAL = "java.util.Optional"
    private const val BLOCK_GETTER = "net.minecraft.world.level.BlockGetter"
    private const val BLOCK_POS = "net.minecraft.core.BlockPos"
    private const val BLOCK_ENTITY = "net.minecraft.world.level.block.entity.BlockEntity"
    private val REGISTRY_WORLD_TYPES = setOf(
        "net.minecraft.world.level.Level",
        "net.minecraft.client.multiplayer.ClientLevel",
        "net.minecraft.server.level.ServerLevel",
        "net.minecraft.world.level.WorldGenLevel",
        "net.minecraft.world.level.LevelAccessor",
        "net.minecraft.world.level.ServerLevelAccessor"
    )
}
