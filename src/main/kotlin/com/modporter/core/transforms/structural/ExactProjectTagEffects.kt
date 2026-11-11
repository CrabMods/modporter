package com.modporter.core.transforms.structural

import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.ForEachStmt
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.io.path.extension
import kotlin.streams.toList

internal class ExactProjectTagEffects private constructor(
    private val sourceRoot: Path,
    private val index: JavaProjectTypeIndex,
    private val borrowedArgumentsOnly: Boolean
) {
    constructor(sourceRoot: Path, index: JavaProjectTypeIndex) : this(sourceRoot, index, false)
    private data class MethodKey(val owner: String, val name: String, val arity: Int)

    private enum class State {
        VISITING,
        READ,
        MUTATE,
        UNKNOWN
    }

    private val methodsByKey: Map<MethodKey, List<MethodDeclaration>>
    private val states = IdentityHashMap<Node, State>()
    private val borrowAnalysis by lazy { ExactProjectTagEffects(sourceRoot, index, true) }

    init {
        val methods = linkedMapOf<MethodKey, MutableList<MethodDeclaration>>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }.toList().forEach { file ->
                val cu = index.unit(file)
                cu.findAll(MethodDeclaration::class.java).forEach methodLoop@{ method ->
                    val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java)
                        .flatMap { it.fullyQualifiedName }
                        .orElse(null) ?: return@methodLoop
                    methods.getOrPut(
                        MethodKey(owner, method.nameAsString, method.parameters.size),
                        ::mutableListOf
                    ) += method
                }
            }
        }
        methodsByKey = methods
    }

    fun compoundTagArgumentEffect(
        call: MethodCallExpr,
        argumentIndex: Int
    ): ExactExternalTagContracts.Effect? {
        val (method, parameter) = exactTargetParameter(
            call,
            argumentIndex,
            setOf(COMPOUND_TAG)
        ) ?: return null
        return when (bindingState(parameter, ExactJavaSemantics(method.findCompilationUnit().orElseThrow()))) {
            State.READ -> ExactExternalTagContracts.Effect.READ
            State.MUTATE -> ExactExternalTagContracts.Effect.MUTATE
            else -> null
        }
    }

    /** A concrete source body can prove a borrowed tag is read or mutated, never retained. */
    fun sourceMethodParameterEffect(
        method: MethodDeclaration,
        argumentIndex: Int
    ): ExactExternalTagContracts.Effect? {
        if (!borrowedArgumentsOnly) return borrowAnalysis.sourceMethodParameterEffect(method, argumentIndex)
        val parameter = method.parameters.getOrNull(argumentIndex) ?: return null
        if (index.declaredType(parameter.type, method) != COMPOUND_TAG) return null
        val unit = method.findCompilationUnit().orElse(null) ?: return null
        return when (bindingState(parameter, ExactJavaSemantics(unit))) {
            State.READ -> ExactExternalTagContracts.Effect.READ
            State.MUTATE -> ExactExternalTagContracts.Effect.MUTATE
            else -> null
        }
    }

    private fun exactTargetParameter(
        call: MethodCallExpr,
        argumentIndex: Int,
        acceptedTypes: Set<String>
    ): Pair<MethodDeclaration, Parameter>? {
        exactTarget(call)?.let { method ->
            method.parameters.getOrNull(argumentIndex)?.let { parameter ->
                if (index.declaredType(parameter.type, method) in acceptedTypes) {
                    return method to parameter
                }
            }
        }

        val owner = index.projectMethodOwner(call, call.arguments.size)
            ?: call.takeIf { it.scope.isEmpty }
                ?.findAncestor(ClassOrInterfaceDeclaration::class.java)
                ?.flatMap { it.fullyQualifiedName }
                ?.orElse(null)
            ?: return null
        val expanded = methodsByKey[
            MethodKey(owner, call.nameAsString, call.arguments.size + 1)
        ].orEmpty().mapNotNull { method ->
            val providerIndices = method.parameters.indices.filter { parameterIndex ->
                index.declaredType(method.parameters[parameterIndex].type, method) ==
                    HOLDER_LOOKUP_PROVIDER
            }
            val providerIndex = providerIndices.singleOrNull() ?: return@mapNotNull null
            val nonProviderParameters = method.parameters.filterIndexed { index, _ ->
                index != providerIndex
            }
            val expectedTypes = nonProviderParameters.map { parameter ->
                index.declaredType(parameter.type, method) ?: return@mapNotNull null
            }
            val callerCu = call.findCompilationUnit().orElse(null) ?: return@mapNotNull null
            val callerExact = ExactJavaSemantics(callerCu)
            val argumentsMatch = call.arguments.zip(expectedTypes).all { (argument, expected) ->
                val legacyTagElement = (argument as? MethodCallExpr)?.let { tagCall ->
                    expected in acceptedTypes &&
                        tagCall.nameAsString == "getOrCreateTagElement" &&
                        tagCall.arguments.size == 1 &&
                        tagCall.scope.map { ownerExpression ->
                            callerExact.isProvablyType(
                                ownerExpression,
                                "ItemStack",
                                ITEM_STACK
                            ) || index.isExpressionAssignableTo(
                                ownerExpression,
                                tagCall,
                                ITEM_STACK
                            )
                        }.orElse(false)
                } == true
                legacyTagElement ||
                    index.isExpressionAssignableTo(argument, call, expected)
            }
            if (!argumentsMatch) return@mapNotNull null
            val parameter = nonProviderParameters.getOrNull(argumentIndex)
                ?: return@mapNotNull null
            (method to parameter).takeIf {
                index.declaredType(parameter.type, method) in acceptedTypes
            }
        }
        return expanded.singleOrNull()
    }

    private fun exactTarget(call: MethodCallExpr): MethodDeclaration? {
        val owner = index.projectMethodOwner(call, call.arguments.size) ?: return null
        val candidates = methodsByKey[
            MethodKey(owner, call.nameAsString, call.arguments.size)
        ].orEmpty()
        if (candidates.size == 1) return candidates.single()
        return candidates.filter { method ->
            val parameterTypes = method.parameters.map { parameter ->
                index.declaredType(parameter.type, method) ?: return@filter false
            }
            index.argumentsMatchTypes(call, parameterTypes)
        }.singleOrNull()
    }

    private fun bindingState(binding: Node, exact: ExactJavaSemantics): State {
        states[binding]?.let { state ->
            return if (state == State.VISITING) State.UNKNOWN else state
        }
        if (binding is Parameter) {
            val method = binding.findAncestor(MethodDeclaration::class.java).orElse(null)
            if (method == null || (method.body.isEmpty && (method.isAbstract || method.isNative))) {
                states[binding] = State.UNKNOWN
                return State.UNKNOWN
            }
        }

        val uses = when (binding) {
            is Parameter -> exact.referencesTo(binding)
            is VariableDeclarator -> exact.referencesTo(binding)
            else -> emptyList()
        }
        if (binding !is Parameter && binding !is VariableDeclarator) {
            states[binding] = State.UNKNOWN
            return State.UNKNOWN
        }

        states[binding] = State.VISITING
        var mutates = false
        for (use in uses) {
            val assignment = use.parentNode.orElse(null) as? AssignExpr
            if (assignment?.target === use) continue
            val effect = useEffect(use, exact)
            if (effect == null) {
                states[binding] = State.UNKNOWN
                return State.UNKNOWN
            }
            if (effect == ExactExternalTagContracts.Effect.MUTATE) mutates = true
        }
        return (if (mutates) State.MUTATE else State.READ).also {
            states[binding] = it
        }
    }

    private fun useEffect(
        use: NameExpr,
        exact: ExactJavaSemantics
    ): ExactExternalTagContracts.Effect? = tagValueEffect(use, exact)

    private fun tagValueEffect(
        expression: Expression,
        exact: ExactJavaSemantics
    ): ExactExternalTagContracts.Effect? {
        var current = expression
        while (true) {
            current = when (val parent = current.parentNode.orElse(null)) {
                is CastExpr -> parent.takeIf { it.expression === current } ?: break
                is EnclosedExpr -> parent.takeIf { it.inner === current } ?: break
                else -> break
            }
        }

        return when (val parent = current.parentNode.orElse(null)) {
            is MethodCallExpr -> when {
                parent.scope.map { it === current }.orElse(false) -> when {
                    parent.nameAsString in NESTED_TAG_GETTERS -> tagValueEffect(parent, exact)
                    parent.nameAsString == "forEach" -> iterationEffect(parent, exact)
                    else -> ExactExternalTagContracts.compoundTagReceiverEffect(parent.nameAsString)
                }
                else -> {
                    val argumentIndex = parent.arguments.indexOfIdentity(current)
                    if (argumentIndex < 0) null else argumentEffect(parent, argumentIndex, exact)
                }
            }
            is VariableDeclarator -> if (parent.initializer.orElse(null) === current) {
                bindingEffect(parent, exact)
            } else {
                null
            }
            is AssignExpr -> if (parent.value === current) {
                val target = parent.target as? NameExpr ?: return null
                val declaration = exact.declarationOf(target) as? VariableDeclarator ?: return null
                if (declaration.findAncestor(FieldDeclaration::class.java).isPresent) return null
                bindingEffect(declaration, exact)
            } else {
                null
            }
            is ForEachStmt -> if (parent.iterable === current) enhancedForEffect(parent, exact) else null
            is BinaryExpr -> if (parent.operator in setOf(
                    BinaryExpr.Operator.EQUALS,
                    BinaryExpr.Operator.NOT_EQUALS
                )
            ) {
                ExactExternalTagContracts.Effect.READ
            } else {
                null
            }
            else -> null
        }
    }

    private fun bindingEffect(
        binding: Node,
        exact: ExactJavaSemantics
    ): ExactExternalTagContracts.Effect? = when (bindingState(binding, exact)) {
        State.READ -> ExactExternalTagContracts.Effect.READ
        State.MUTATE -> ExactExternalTagContracts.Effect.MUTATE
        else -> null
    }

    private fun iterationEffect(
        call: MethodCallExpr,
        exact: ExactJavaSemantics
    ): ExactExternalTagContracts.Effect? {
        if (call.arguments.size != 1) return null
        val lambda = call.arguments.single() as? LambdaExpr ?: return null
        val parameter = lambda.parameters.singleOrNull() ?: return null
        return bindingEffect(parameter, exact)
    }

    private fun enhancedForEffect(
        loop: ForEachStmt,
        exact: ExactJavaSemantics
    ): ExactExternalTagContracts.Effect? {
        val variable = loop.variable.variables.singleOrNull() ?: return null
        return bindingEffect(variable, exact)
    }

    private fun argumentEffect(
        call: MethodCallExpr,
        argumentIndex: Int,
        exact: ExactJavaSemantics
    ): ExactExternalTagContracts.Effect? {
        val external = ExactExternalTagContracts.compoundTagArgumentEffect(call, argumentIndex, exact, index)
        if (external != null) {
            return if (borrowedArgumentsOnly) borrowedExternalArgumentEffect(call, argumentIndex, exact)
            else external
        }
        return projectTagArgumentEffect(call, argumentIndex)
    }

    /** An ordinary READ can decode/retain tag views; a borrow proof needs a non-retaining boundary. */
    private fun borrowedExternalArgumentEffect(
        call: MethodCallExpr,
        argumentIndex: Int,
        exact: ExactJavaSemantics
    ): ExactExternalTagContracts.Effect? {
        if (call.nameAsString == "of" && argumentIndex == 0 && call.arguments.size == 1 &&
            call.scope.map {
                exact.exactStaticScope(it, "CustomData", "net.minecraft.world.item.component.CustomData")
            }.orElse(false) &&
            !index.hasSourceTypeDefinition("net.minecraft.world.item.component.CustomData")
        ) return ExactExternalTagContracts.Effect.READ // CustomData.of copies its input tag.

        if (!call.scope.map { exact.exactStaticScope(it, "NBTHelper", CATNIP_NBT_HELPER) }
                .orElse(false) || index.hasSourceTypeDefinition(CATNIP_NBT_HELPER) || argumentIndex != 0
        ) return null
        return when {
            call.nameAsString == "readResourceLocation" && call.arguments.size == 2 ->
                ExactExternalTagContracts.Effect.READ // Immutable identifier built from a string.
            call.nameAsString == "readEnum" && call.arguments.size == 3 ->
                ExactExternalTagContracts.Effect.READ // Enum identity, not a retained tag view.
            call.nameAsString == "writeEnum" && call.arguments.size == 3 ->
                ExactExternalTagContracts.Effect.MUTATE
            call.nameAsString in setOf("readCompoundList", "iterateCompoundList") &&
                call.arguments.size == 2 -> {
                val callback = call.arguments[1] as? LambdaExpr ?: return null
                val parameter = callback.parameters.singleOrNull() ?: return null
                // The callback's result/field escapes are checked, including an implicit `tag -> tag`.
                bindingEffect(parameter, exact)
            }
            else -> null
        }
    }

    private fun projectTagArgumentEffect(
        call: MethodCallExpr,
        argumentIndex: Int
    ): ExactExternalTagContracts.Effect? {
        val (method, parameter) = exactTargetParameter(call, argumentIndex, TAG_VALUE_TYPES)
            ?: return null
        val cu = method.findCompilationUnit().orElse(null) ?: return null
        return bindingEffect(parameter, ExactJavaSemantics(cu))
    }

    private fun <T> List<T>.indexOfIdentity(target: T): Int =
        indexOfFirst { it === target }

    private companion object {
        const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
        const val LIST_TAG = "net.minecraft.nbt.ListTag"
        const val TAG = "net.minecraft.nbt.Tag"
        const val HOLDER_LOOKUP_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
        const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
        const val CATNIP_NBT_HELPER = "net.createmod.catnip.nbt.NBTHelper"
        val TAG_VALUE_TYPES = setOf(COMPOUND_TAG, LIST_TAG, TAG)
        val NESTED_TAG_GETTERS = setOf("get", "getCompound", "getList")
    }
}
