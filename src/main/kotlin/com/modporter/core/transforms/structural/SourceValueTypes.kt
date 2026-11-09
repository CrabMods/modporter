package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.ThrowStmt
import com.modporter.core.transforms.util.JavaSourceText

/** Read-only expression typing for NBT receivers, using declarations and exact library generic contracts. */
internal class SourceValueTypes(private val pass: StructuralRefactorPass, private val source: String) {
    private enum class Variance { EXACT, EXTENDS, SUPER, ANY }
    private data class Type(
        val name: String,
        val arguments: List<Type> = emptyList(),
        val arrays: Int = 0,
        val variance: Variance = Variance.EXACT
    ) {
        fun readable(): Type = when (variance) {
            Variance.EXACT -> this
            Variance.EXTENDS -> copy(variance = Variance.EXACT)
            Variance.SUPER, Variance.ANY -> Type("java.lang.Object")
        }

        fun functionalInput(): Type = when (variance) {
            Variance.EXACT, Variance.EXTENDS, Variance.SUPER -> copy(variance = Variance.EXACT)
            Variance.ANY -> Type("java.lang.Object")
        }
    }
    private data class Binding(
        val node: Node,
        val scope: Node,
        val type: com.github.javaparser.ast.type.Type,
        val visibleBeforeDeclaration: Boolean = false,
        val visibleAfter: Node? = null
    )
    private val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    private val code = with(pass) { maskJavaCommentsAndLiterals(source) }
    private val blocks = with(pass) { javaTypeBlocks(source, code) }
    private val packageName = with(pass) { packageNameOf(source) }
    private val imports = Regex("""(?m)^\s*import\s+(?!static\b)([\w.]+)\s*;""").findAll(code)
        .associate { it.groupValues[1].substringAfterLast('.') to it.groupValues[1] }
    private val unit: CompilationUnit? by lazy { parser.parse(source).result.orElse(null) }
    private val lambdas by lazy { unit?.findAll(LambdaExpr::class.java).orEmpty() }
    private val declaredValueNames by lazy {
        buildSet {
            unit?.findAll(VariableDeclarator::class.java).orEmpty().forEach { add(it.nameAsString) }
            unit?.findAll(Parameter::class.java).orEmpty().forEach { add(it.nameAsString) }
        }
    }
    private val bindingsByName: Map<String, List<Binding>> by lazy {
        val result = mutableMapOf<String, MutableList<Binding>>()
        for (variable in unit?.findAll(VariableDeclarator::class.java).orEmpty()) {
            val visible = bindingScope(variable) ?: continue
            val field = variable.parentNode.orElse(null) is com.github.javaparser.ast.body.FieldDeclaration
            result.getOrPut(variable.nameAsString) { mutableListOf() } +=
                Binding(variable, visible, variable.type, field)
        }
        for (parameter in unit?.findAll(Parameter::class.java).orEmpty()) {
            val visible = bindingScope(parameter) ?: continue
            result.getOrPut(parameter.nameAsString) { mutableListOf() } +=
                Binding(parameter, visible, parameter.type)
        }
        for (statement in unit?.findAll(IfStmt::class.java).orEmpty()) {
            var condition = statement.condition
            while (condition is EnclosedExpr) condition = condition.inner
            val test = when (condition) {
                is InstanceOfExpr -> condition
                is UnaryExpr -> {
                    if (condition.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT ||
                        statement.elseStmt.isPresent ||
                        !exits(statement.thenStmt)
                    ) {
                        continue
                    }
                    var expression = condition.expression
                    while (expression is EnclosedExpr) expression = expression.inner
                    expression as? InstanceOfExpr ?: continue
                }
                else -> continue
            }
            val pattern = test.pattern.orElse(null) as? TypePatternExpr ?: continue
            val negativeGuard = condition is UnaryExpr
            val scope = if (negativeGuard) statement.parentNode.orElse(null) as? BlockStmt else statement.thenStmt
            if (scope == null) continue
            result.getOrPut(pattern.nameAsString) { mutableListOf() } +=
                Binding(
                    pattern,
                    scope,
                    pattern.type,
                    visibleAfter = statement.takeIf { negativeGuard }
                )
        }
        result
    }
    private val lineStarts = buildList { add(0); source.forEachIndexed { index, c -> if (c == '\n') add(index + 1) } }
    private fun start(node: Node): Int = node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    private fun end(node: Node): Int = node.range.map { lineStarts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)
    private fun at(node: Node, fallback: Int) = if (node.findCompilationUnit().isPresent) start(node) else fallback
    private fun exits(statement: com.github.javaparser.ast.stmt.Statement): Boolean = when (statement) {
        is ReturnStmt, is ThrowStmt -> true
        is BlockStmt -> statement.statements.size == 1 && exits(statement.statements.single())
        else -> false
    }
    private fun owner(at: Int): String? = blocks.filter { at in it.bodyStart..it.end }
        .sortedByDescending { it.end - it.bodyStart }.map { it.name }.takeIf { it.isNotEmpty() }
        ?.joinToString(".")?.let { if (packageName.isBlank()) it else "$packageName.$it" }
    private fun bindingScope(node: Node): Node? {
        var parent = node.parentNode.orElse(null)
        while (parent != null) {
            if (parent is BlockStmt || parent is MethodDeclaration || parent is ConstructorDeclaration ||
                parent is LambdaExpr || parent is ForStmt || parent is ForEachStmt || parent is CatchClause ||
                parent is TypeDeclaration<*>
            ) {
                return parent
            }
            parent = parent.parentNode.orElse(null)
        }
        return null
    }

    private fun qualify(name: String): String {
        if (name in primitiveTypes) return name
        if ('.' in name) return imports[name.substringBefore('.')]?.plus(name.removePrefix(name.substringBefore('.'))) ?: name
        if (blocks.any { it.name == name }) return owner(blocks.first { it.name == name }.bodyStart) ?: name
        imports[name]?.let { return it }
        val packageType = if (packageName.isBlank()) name else "$packageName.$name"
        if (packageType in pass.projectValueFacts.types) return packageType
        if (name in implicitJavaLangTypes) return "java.lang.$name"
        val standards = mapOf("List" to "java.util.List", "Map" to "java.util.Map", "Set" to "java.util.Set",
            "Collection" to "java.util.Collection", "Iterable" to "java.lang.Iterable", "ArrayList" to "java.util.ArrayList",
            "Optional" to "java.util.Optional")
        standards[name]?.takeIf { it.startsWith("java.lang.") || source.contains("import java.util.*;") }?.let { return it }
        return if (packageName.isBlank()) name else "$packageName.$name"
    }
    private fun type(text: String, depth: Int = 0): Type? {
        if (depth > 10) return null
        var value = text.trim()
        if (value.isEmpty()) return null
        if (value == "?") return Type("java.lang.Object", variance = Variance.ANY)
        if (value.startsWith("? extends ")) {
            return type(value.removePrefix("? extends ").trim(), depth + 1)?.copy(variance = Variance.EXTENDS)
        }
        if (value.startsWith("? super ")) {
            return type(value.removePrefix("? super ").trim(), depth + 1)?.copy(variance = Variance.SUPER)
        }
        var arrays = 0
        while (value.endsWith("[]")) { arrays++; value = value.dropLast(2).trim() }
        val open = value.indexOf('<')
        val arguments = if (open < 0) emptyList() else {
            val close = value.lastIndexOf('>')
            if (close < open) return null
            JavaSourceText.splitTopLevelArgs(value.substring(open + 1, close)).map { type(it, depth + 1) ?: return null }
        }
        return Type(qualify(if (open < 0) value else value.substring(0, open).trim()), arguments, arrays)
    }
    private fun fact(values: Map<String, Map<String, String>>, owner: String, name: String): Type? {
        var current = owner
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val own = values[current]
            if (own?.containsKey(name) == true) return own[name]?.takeIf { it.isNotEmpty() }?.let(::type)
            current = pass.projectValueFacts.parents[current] ?: return null
        }
        return null
    }
    private fun projectMethods(owner: String, name: String, arity: Int): List<ProjectValueFacts.Method> {
        val methods = mutableListOf<ProjectValueFacts.Method>()
        var current = owner
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            methods += pass.projectValueFacts.methods[current]?.get("$name/$arity").orEmpty()
            current = pass.projectValueFacts.parents[current] ?: break
        }
        return methods.distinct()
    }
    private fun compatible(parameter: Type, argument: Type): Boolean {
        if (parameter == argument) return true
        if (parameter.arrays != argument.arrays) return false
        return boxedTypes[parameter.name] == argument.name || boxedTypes[argument.name] == parameter.name
    }
    private fun projectMethod(
        owner: String,
        name: String,
        arguments: List<Type?>,
        ignoredArgument: Int? = null
    ): ProjectValueFacts.Method? {
        val candidates = projectMethods(owner, name, arguments.size)
        if (candidates.size <= 1) return candidates.singleOrNull()
        return candidates.filter { method ->
            method.parameterTypes.indices.all { index ->
                if (index == ignoredArgument) true
                else {
                    val parameter = type(method.parameterTypes[index]) ?: return@all false
                    val argument = arguments[index] ?: return@all false
                    compatible(parameter, argument)
                }
            }
        }.distinct().singleOrNull()
    }
    private fun functionalValueType(functional: Type, parameter: Int): Type? = when (functional.name) {
        "java.util.function.Consumer", "java.util.function.Predicate", "java.util.function.UnaryOperator",
        "java.util.function.Function" -> if (parameter == 0) functional.arguments.firstOrNull()?.functionalInput() else null
        "java.util.function.BiConsumer", "java.util.function.BiFunction" ->
            functional.arguments.getOrNull(parameter)?.functionalInput()
        else -> null
    }
    private fun declared(name: String, at: Int): Type? {
        val binding = bindingsByName[name].orEmpty()
            .asSequence()
            .filter {
                at in start(it.scope)..end(it.scope) &&
                    (it.visibleBeforeDeclaration || start(it.node) <= at) &&
                    (it.visibleAfter == null || end(it.visibleAfter) < at)
            }
            .sortedWith(compareBy<Binding> { end(it.scope) - start(it.scope) }.thenByDescending { start(it.node) })
            .firstOrNull()
            ?: return owner(at)?.let { fact(pass.projectValueFacts.fields, it, name) }
        if (binding.type.isUnknownType || binding.type.isVarType) return null
        return type(binding.type.toString())
    }
    fun typeOf(expression: String, offset: Int): String? = runCatching {
        val expr = parser.parseExpression<Expression>(expression.trim()).result.orElse(null) ?: return@runCatching null
        infer(expr, offset, 0)?.takeIf { it.arrays == 0 }?.name
    }.getOrNull()

    fun holderValueType(expression: String, offset: Int): String? = runCatching {
        val expr = parser.parseExpression<Expression>(expression.trim()).result.orElse(null) ?: return@runCatching null
        val value = infer(expr, offset, 0) ?: return@runCatching null
        if (value.name != "net.minecraft.core.Holder" || value.arrays != 0) return@runCatching null
        value.arguments.singleOrNull()
            ?.takeIf { it.variance in setOf(Variance.EXACT, Variance.EXTENDS) }
            ?.name
    }.getOrNull()

    /**
     * Exact readable element type of a source-proven Iterable/Collection/Stream expression.
     * Raw collections, unbounded/super wildcards and unresolved declarations return null/Object
     * and therefore cannot authorize an element-sensitive rewrite.
     */
    fun iterableElementType(expression: String, offset: Int): String? = runCatching {
        val expr = parser.parseExpression<Expression>(expression.trim()).result.orElse(null)
            ?: return@runCatching null
        val value = infer(expr, offset, 0) ?: return@runCatching null
        if (value.name !in iterableTypes || value.arrays != 0) return@runCatching null
        value.arguments.singleOrNull()
            ?.readable()
            ?.takeIf { it.arrays == 0 && it.name != "java.lang.Object" }
            ?.name
    }.getOrNull()

    private fun lambdaFor(name: String, offset: Int): LambdaExpr? = lambdas
        .filter { offset in start(it)..end(it) && it.parameters.any { p -> p.nameAsString == name } }
        .minByOrNull { end(it) - start(it) }

    fun untypedLambdaParameter(name: String, offset: Int): Boolean {
        if (!source.contains("->")) return false
        val lambda = lambdaFor(name, offset) ?: return false
        return lambda.parameters.first { it.nameAsString == name }.type.isUnknownType
    }

    fun isTypeName(name: String, canonical: String): Boolean = qualify(name) == canonical &&
        name.substringBefore('.') !in declaredValueNames

    private fun infer(expr: Expression, context: Int, depth: Int): Type? {
        if (depth > 12) return null
        val position = at(expr, context)
        fun next(value: Expression) = infer(value, at(value, position), depth + 1)
        return when (expr) {
            is EnclosedExpr -> next(expr.inner)
            is CastExpr -> type(expr.type.toString())
            is ObjectCreationExpr -> type(expr.type.toString())
            is BooleanLiteralExpr -> Type("boolean")
            is CharLiteralExpr -> Type("char")
            is DoubleLiteralExpr -> Type("double")
            is IntegerLiteralExpr -> Type("int")
            is LongLiteralExpr -> Type("long")
            is StringLiteralExpr, is TextBlockLiteralExpr -> Type("java.lang.String")
            is ArrayAccessExpr -> next(expr.name)?.takeIf { it.arrays > 0 }?.let { it.copy(arrays = it.arrays - 1) }
            is NameExpr -> {
                val lambda = lambdaFor(expr.nameAsString, position)
                if (lambda != null) lambdaType(lambda, lambda.parameters.indexOfFirst { it.nameAsString == expr.nameAsString }, depth + 1)
                else declared(expr.nameAsString, position)
            }
            is FieldAccessExpr -> {
                if (expr.scope is ThisExpr) {
                    val variable = unit?.findAll(VariableDeclarator::class.java).orEmpty().firstOrNull {
                        it.nameAsString == expr.nameAsString && owner(start(it)) == owner(position) && it.parentNode.orElse(null) is com.github.javaparser.ast.body.FieldDeclaration
                    }
                    variable?.let { type(it.type.toString()) }
                } else {
                    if (isTypeName(expr.scope.toString(), "net.minecraft.world.effect.MobEffects")) {
                        return Type("net.minecraft.core.Holder", listOf(Type("net.minecraft.world.effect.MobEffect")))
                    }
                    if (isTypeName(expr.scope.toString(), "net.minecraft.world.item.alchemy.Potions")) {
                        return Type("net.minecraft.core.Holder", listOf(Type("net.minecraft.world.item.alchemy.Potion")))
                    }
                    if (isTypeName(expr.scope.toString(), "net.minecraft.world.entity.ai.attributes.Attributes")) {
                        return Type("net.minecraft.core.Holder", listOf(Type("net.minecraft.world.entity.ai.attributes.Attribute")))
                    }
                    val receiver = next(expr.scope)
                    val staticOwner = qualify(expr.scope.toString()).takeIf {
                        it in pass.projectValueFacts.types && isTypeName(expr.scope.toString(), it)
                    }
                    (receiver?.name ?: staticOwner)?.let { fact(pass.projectValueFacts.fields, it, expr.nameAsString) }
                }
            }
            is MethodCallExpr -> {
                val name = expr.nameAsString
                val scope = expr.scope.orElse(null)
                val staticOwner = scope?.toString()?.let(::qualify)
                val shadowed = scope is NameExpr && scope.nameAsString in declaredValueNames
                when {
                    !shadowed && staticOwner == "net.minecraft.Util" && name == "make" && expr.arguments.size == 2 -> next(expr.arguments[0])
                    !shadowed && staticOwner == "net.minecraft.world.item.enchantment.EnchantmentHelper" && name == "getEnchantmentsForCrafting" && expr.arguments.size == 1 ->
                        Type("net.minecraft.world.item.enchantment.ItemEnchantments")
                    !shadowed && staticOwner in setOf("com.google.common.collect.ImmutableList", "java.util.List") && name == "of" && expr.arguments.isNotEmpty() -> {
                        val elements = expr.arguments.map { next(it) ?: return null }
                        elements.firstOrNull()?.takeIf { first -> elements.all { it == first } }?.let { Type("java.util.List", listOf(it)) }
                    }
                    // javap Ponder 1.0.87: IntAttached.with(int,T), IntAttached<T>.getValue().
                    !shadowed && staticOwner == "net.createmod.catnip.data.IntAttached" && name == "with" && expr.arguments.size == 2 ->
                        next(expr.arguments[1])?.let { Type(staticOwner, listOf(it)) }
                    else -> {
                        val receiver = scope?.let(::next)
                        when {
                            receiver?.name == "java.util.Optional" && name == "filter" && expr.arguments.size == 1 -> receiver
                            receiver?.name == "java.util.Optional" && name in setOf("get", "orElseThrow") && expr.arguments.isEmpty() ->
                                receiver.arguments.singleOrNull()?.readable()
                            receiver?.name == "java.util.Optional" && name in setOf("orElse", "orElseGet") && expr.arguments.size == 1 ->
                                receiver.arguments.singleOrNull()?.readable()
                            // WeightedRandomList<E>.getRandom(RandomSource) -> Optional<E>; SimpleWeightedRandomList<E>
                            // extends WeightedRandomList<WeightedEntry.Wrapper<E>> (1.20.1 and 1.21.1 alike)
                            receiver?.name == "net.minecraft.util.random.WeightedRandomList" && name == "getRandom" && expr.arguments.size == 1 ->
                                receiver.arguments.singleOrNull()?.readable()?.let { Type("java.util.Optional", listOf(it)) }
                            receiver?.name == "net.minecraft.util.random.SimpleWeightedRandomList" && name == "getRandom" && expr.arguments.size == 1 ->
                                receiver.arguments.singleOrNull()?.readable()?.let {
                                    Type("java.util.Optional", listOf(Type("net.minecraft.util.random.WeightedEntry.Wrapper", listOf(it))))
                                }
                            receiver?.name in setOf("com.tterrag.registrate.util.entry.ItemEntry", "com.tterrag.registrate.util.entry.BlockEntry", "com.tterrag.registrate.util.entry.ItemProviderEntry") && name == "asStack" && expr.arguments.size in 0..1 ->
                                Type("net.minecraft.world.item.ItemStack")
                            receiver?.name == "net.minecraft.world.item.enchantment.ItemEnchantments" && name == "keySet" && expr.arguments.isEmpty() ->
                                Type("java.util.Set", listOf(Type("net.minecraft.core.Holder", listOf(Type("net.minecraft.world.item.enchantment.Enchantment")))))
                            receiver?.name == "net.minecraft.world.item.enchantment.ItemEnchantments" && name == "entrySet" && expr.arguments.isEmpty() ->
                                Type("java.util.Set", listOf(Type("it.unimi.dsi.fastutil.objects.Object2IntMap.Entry", listOf(Type("net.minecraft.core.Holder", listOf(Type("net.minecraft.world.item.enchantment.Enchantment")))))))
                            receiver?.name == "java.util.Map" && name == "entrySet" && expr.arguments.isEmpty() ->
                                Type("java.util.Set", listOf(Type("java.util.Map.Entry", receiver.arguments)))
                            receiver?.name == "java.util.Map" && name == "values" && expr.arguments.isEmpty() ->
                                receiver.arguments.getOrNull(1)?.readable()?.let {
                                    Type("java.util.Collection", listOf(it))
                                }
                            receiver?.name in setOf("java.util.Map.Entry", "it.unimi.dsi.fastutil.objects.Object2IntMap.Entry") && name == "getKey" && expr.arguments.isEmpty() ->
                                receiver?.arguments?.firstOrNull()?.readable()
                            receiver?.name == "java.util.Map.Entry" && name == "getValue" && expr.arguments.isEmpty() ->
                                receiver.arguments.getOrNull(1)?.readable()
                            receiver?.name == "net.minecraft.core.Holder" && name == "value" && expr.arguments.isEmpty() ->
                                receiver.arguments.singleOrNull()?.readable()
                            receiver?.name == "net.minecraft.world.entity.ai.attributes.AttributeModifier" && name in setOf("operation", "getOperation") && expr.arguments.isEmpty() ->
                                Type("net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation")
                            receiver?.name == "net.minecraft.world.effect.MobEffectInstance" && name == "getEffect" && expr.arguments.isEmpty() ->
                                Type("net.minecraft.core.Holder", listOf(Type("net.minecraft.world.effect.MobEffect")))
                            receiver?.name == "net.createmod.catnip.data.IntAttached" && name == "getValue" && expr.arguments.isEmpty() ->
                                receiver.arguments.singleOrNull()?.readable()
                            receiver?.name == "java.util.Map" && name == "get" && expr.arguments.size == 1 ->
                                receiver.arguments.getOrNull(1)?.readable()
                            receiver?.name in iterableTypes && name == "get" && expr.arguments.size == 1 ->
                                receiver?.arguments?.singleOrNull()?.readable()
                            receiver?.name in iterableTypes && name == "stream" && expr.arguments.isEmpty() -> receiver?.let { Type("java.util.stream.Stream", it.arguments) }
                            else -> {
                                val projectOwner = when {
                                    receiver != null && receiver.name in pass.projectValueFacts.types -> receiver.name
                                    !shadowed && staticOwner in pass.projectValueFacts.types -> staticOwner
                                    scope == null -> owner(position)
                                    else -> null
                                }
                                val arguments = expr.arguments.map(::next)
                                val method = projectOwner?.let { projectMethod(it, name, arguments) }
                                method?.returnType?.let(::type)
                                    ?: if (expr.arguments.isEmpty()) {
                                        val target = receiver?.name ?: owner(position)
                                        target?.let { fact(pass.projectValueFacts.getters, it, name) }
                                    } else null
                            }
                        }
                    }
                }
            }
            else -> null
        }
    }

    private fun lambdaType(lambda: LambdaExpr, parameter: Int, depth: Int): Type? {
        if (depth > 12 || parameter < 0) return null
        val declared = lambda.parameters[parameter].type
        if (!declared.isUnknownType) return type(declared.toString())
        var argument: Node = lambda
        while (argument.parentNode.orElse(null) is EnclosedExpr) argument = argument.parentNode.get()
        val call = argument.parentNode.orElse(null) as? MethodCallExpr ?: return null
        val index = call.arguments.indexOf(argument)
        val scope = call.scope.orElse(null)
        val name = call.nameAsString
        val staticOwner = scope?.toString()?.let(::qualify)
        fun inferNode(expr: Expression) = infer(expr, start(expr), depth + 1)
        if (staticOwner == "net.createmod.catnip.nbt.NBTHelper" && index == 1 && parameter == 0 && call.arguments.size == 2) {
            // javap Catnip 1.0.11 / Ponder 1.0.87:
            // writeCompoundList(Iterable<T>, Function<T, CompoundTag>),
            // readCompoundList(ListTag, Function<CompoundTag, T>), and
            // iterateCompoundList(ListTag, Consumer<CompoundTag>).
            if (name == "writeCompoundList") {
                return inferNode(call.arguments[0])?.arguments?.singleOrNull()?.readable()
            }
            if (name in setOf("readCompoundList", "iterateCompoundList")) {
                return Type("net.minecraft.nbt.CompoundTag")
            }
        }
        // javap Catnip 1.0.11: Couple.deserializeEach(ListTag, Function<CompoundTag, T>).
        if (staticOwner == "net.createmod.catnip.data.Couple" && name == "deserializeEach" &&
            index == 1 && parameter == 0 && call.arguments.size == 2
        ) {
            return Type("net.minecraft.nbt.CompoundTag")
        }
        val receiver = scope?.let(::inferNode)
        if (receiver?.name == "java.util.Optional" && name in setOf("map", "flatMap", "filter", "ifPresent", "ifPresentOrElse") && index == 0 && parameter == 0) {
            return receiver.arguments.singleOrNull()?.readable()
        }
        if (receiver?.name == "java.util.stream.Stream" && name in setOf("map", "flatMap", "filter", "peek", "anyMatch", "allMatch", "noneMatch") && index == 0 && parameter == 0) {
            return receiver.arguments.singleOrNull()?.readable()
        }
        if (receiver?.name in setOf(
                "net.minecraft.network.FriendlyByteBuf",
                "net.minecraft.network.RegistryFriendlyByteBuf"
            ) &&
            name == "readMap" &&
            call.arguments.size == 2 &&
            index in 0..1 &&
            parameter == 0
        ) {
            // FriendlyByteBuf#readMap(Function<FriendlyByteBuf, K>,
            // Function<FriendlyByteBuf, V>) passes the same buffer to both decoders.
            return Type("net.minecraft.network.FriendlyByteBuf")
        }
        if (name == "forEach" && index == 0) {
            if (receiver?.name == "java.util.Map" && receiver.arguments.size == 2) {
                return receiver.arguments.getOrNull(parameter)?.readable()
            }
            if (receiver?.name in iterableTypes && parameter == 0) {
                return receiver?.arguments?.singleOrNull()?.readable()
            }
        }
        // javap Ponder 1.0.87: Couple<T>.serializeEach(Function<T, CompoundTag>).
        if (receiver?.name == "net.createmod.catnip.data.Couple" && name == "serializeEach" && index == 0 && parameter == 0) {
            return receiver.arguments.singleOrNull()?.readable()
        }
        val projectOwner = when {
            receiver != null && receiver.name in pass.projectValueFacts.types -> receiver.name
            staticOwner in pass.projectValueFacts.types -> staticOwner
            scope == null -> owner(start(call))
            else -> null
        } ?: return null
        val arguments = call.arguments.mapIndexed { argumentIndex, expression ->
            if (argumentIndex == index) null else inferNode(expression)
        }
        val method = projectMethod(projectOwner, name, arguments, index) ?: return null
        return type(method.parameterTypes[index])?.let { functionalValueType(it, parameter) }
    }
    companion object {
        private val implicitJavaLangTypes = setOf(
            "Object", "String", "StringBuilder", "StringBuffer", "CharSequence", "Number",
            "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character", "Void",
            "Class", "Enum", "Record", "Throwable", "Exception", "RuntimeException", "Error",
            "Runnable", "Comparable", "AutoCloseable", "Cloneable", "Iterable"
        )
        private val iterableTypes = setOf("java.util.List", "java.util.Set", "java.util.Collection", "java.util.ArrayList",
            "java.lang.Iterable", "java.util.stream.Stream", "com.google.common.collect.ImmutableList", "net.minecraft.core.NonNullList")
        private val primitiveTypes = setOf("boolean", "byte", "char", "double", "float", "int", "long", "short", "void")
        private val boxedTypes = mapOf(
            "boolean" to "java.lang.Boolean",
            "byte" to "java.lang.Byte",
            "char" to "java.lang.Character",
            "double" to "java.lang.Double",
            "float" to "java.lang.Float",
            "int" to "java.lang.Integer",
            "long" to "java.lang.Long",
            "short" to "java.lang.Short"
        )
    }
}
