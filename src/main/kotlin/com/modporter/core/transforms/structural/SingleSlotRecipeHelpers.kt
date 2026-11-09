package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/**
 * Private single-slot query helpers keep their original wrapper preparation and receive a factory
 * that turns that prepared wrapper into the recipe's concrete input at the original lookup site.
 */
internal fun StructuralRefactorPass.migrateSingleSlotRecipeHelpers(source: String): String {
    if (!source.contains("Recipe<Container>") || !source.contains("getRecipeFor")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    if (!typing.isTypeName("Recipe", "net.minecraft.world.item.crafting.Recipe") ||
        !typing.isTypeName("Container", "net.minecraft.world.Container")
    ) return source
    val starts = buildList { add(0); source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    fun offset(node: com.github.javaparser.ast.Node) =
        node.range.map { starts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)
    fun field(owner: ClassOrInterfaceDeclaration, name: String): Pair<FieldDeclaration, VariableDeclarator>? =
        owner.fields.flatMap { declaration -> declaration.variables.map { declaration to it } }
            .singleOrNull { (_, variable) -> variable.nameAsString == name }
    fun isInteger(expression: Expression, value: String) =
        expression is IntegerLiteralExpr && expression.value == value
    fun isStandardSingleSlotHandler(expression: Expression): Boolean {
        val creation = expression as? ObjectCreationExpr ?: return false
        return creation.anonymousClassBody.isEmpty &&
            typing.isTypeName(
                creation.type.asString(),
                "net.neoforged.neoforge.items.ItemStackHandler"
            ) &&
            creation.arguments.singleOrNull()?.let { isInteger(it, "1") } == true
    }
    fun copiesParameter(expression: Expression, parameter: String): Boolean {
        val copy = expression as? MethodCallExpr ?: return false
        val scope = copy.scope.orElse(null) as? NameExpr ?: return false
        return copy.nameAsString == "copy" &&
            copy.arguments.isEmpty() &&
            scope.nameAsString == parameter
    }
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (method in unit.findAll(MethodDeclaration::class.java)) {
        if (!method.isPrivate || !method.isStatic || method.body.isEmpty) continue
        val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: continue
        if (owner.methods.count { it.nameAsString == method.nameAsString } != 1) continue
        val variable = method.typeParameters.singleOrNull { parameter -> parameter.typeBound.any { bound ->
            bound.nameAsString == "Recipe" &&
                bound.typeArguments.orElse(null)?.singleOrNull()?.asString() == "Container"
        } } ?: continue
        val stackIndex = method.parameters.indexOfFirst {
            typing.isTypeName(it.type.asString(), "net.minecraft.world.item.ItemStack")
        }
        if (stackIndex < 0) continue
        val stackParameter = method.parameters[stackIndex].nameAsString
        val managerCall = method.body.get().findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "getRecipeFor" && it.arguments.size == 3
        } ?: continue
        val legacyInput = managerCall.arguments[1] as? NameExpr ?: continue
        if (typing.typeOf(legacyInput.toString(), offset(managerCall)) !=
            "net.neoforged.neoforge.items.wrapper.RecipeWrapper"
        ) continue

        // The manager input must be a direct static-final field in this owner. Its initializer
        // must close over a standard one-slot ItemStackHandler in source, either inline or through
        // one direct static-final field. No factory methods, aliases, or inferred capacities.
        val wrapperField = field(owner, legacyInput.nameAsString) ?: continue
        if (!wrapperField.first.isStatic || !wrapperField.first.isFinal ||
            wrapperField.first.variables.size != 1 ||
            !typing.isTypeName(
                wrapperField.second.type.asString(),
                "net.neoforged.neoforge.items.wrapper.RecipeWrapper"
            )
        ) continue
        val wrapperCreation = wrapperField.second.initializer.orElse(null) as? ObjectCreationExpr ?: continue
        if (wrapperCreation.anonymousClassBody.isPresent ||
            !typing.isTypeName(
                wrapperCreation.type.asString(),
                "net.neoforged.neoforge.items.wrapper.RecipeWrapper"
            ) ||
            wrapperCreation.arguments.size != 1
        ) continue
        val backingArgument = wrapperCreation.arguments.single()
        val backingHandler = when {
            isStandardSingleSlotHandler(backingArgument) -> null
            backingArgument is NameExpr -> {
                val handlerField = field(owner, backingArgument.nameAsString) ?: continue
                if (!handlerField.first.isStatic || !handlerField.first.isFinal ||
                    handlerField.first.variables.size != 1 ||
                    !typing.isTypeName(
                        handlerField.second.type.asString(),
                        "net.neoforged.neoforge.items.ItemStackHandler"
                    ) ||
                    !handlerField.second.initializer.isPresent ||
                    !isStandardSingleSlotHandler(handlerField.second.initializer.get())
                ) continue
                backingArgument.nameAsString
            }
            else -> continue
        }
        val protectedNames = listOfNotNull(legacyInput.nameAsString, backingHandler).toSet()
        if (method.parameters.any { it.nameAsString in protectedNames } ||
            method.findAll(VariableDeclarator::class.java).any { it.nameAsString in protectedNames }
        ) continue

        // Preserve the exact source preparation. It must mutate this wrapper or its exact backing
        // handler, slot zero, with one copy of the helper's ItemStack parameter, before lookup.
        val ownedMutations = method.body.get().findAll(MethodCallExpr::class.java).filter { call ->
            val scope = call.scope.orElse(null) as? NameExpr ?: return@filter false
            (scope.nameAsString == legacyInput.nameAsString && call.nameAsString == "setItem") ||
                (backingHandler != null &&
                    scope.nameAsString == backingHandler &&
                    call.nameAsString == "setStackInSlot")
        }
        val preparation = ownedMutations.singleOrNull()?.takeIf { call ->
            call.arguments.size == 2 &&
                isInteger(call.arguments[0], "0") &&
                copiesParameter(call.arguments[1], stackParameter) &&
                offset(call) < offset(managerCall)
        } ?: continue
        if (preparation.findAncestor(MethodDeclaration::class.java).orElse(null) != method) continue

        val calls = owner.findAll(MethodCallExpr::class.java).filter {
            it.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) == owner &&
                it.nameAsString == method.nameAsString &&
                (it.scope.isEmpty || it.scope.get().toString() in setOf("this", owner.nameAsString))
        }
        if (calls.isEmpty() || calls.any { it.arguments.size != method.parameters.size }) continue
        val recipeTypeIndex = method.parameters.indexOfFirst {
            it.type.isClassOrInterfaceType &&
                it.type.asClassOrInterfaceType().nameAsString == "RecipeType"
        }
        if (recipeTypeIndex < 0) continue
        val cooking = setOf("SMELTING", "SMOKING", "BLASTING", "CAMPFIRE_COOKING")
        val cookingCalls = mutableMapOf<MethodCallExpr, Boolean>()
        var supported = true
        for (call in calls) {
            val type = call.arguments[recipeTypeIndex]
            val vanilla = type as? FieldAccessExpr
            val isCooking = vanilla != null &&
                vanilla.nameAsString in cooking &&
                typing.isTypeName(
                    vanilla.scope.toString(),
                    "net.minecraft.world.item.crafting.RecipeType"
                )
            // This remains deliberately narrow even though the factory no longer evaluates the
            // caller's stack expression: the admitted source shape uses a named stack value.
            if (call.arguments[stackIndex] !is NameExpr) {
                supported = false
                break
            }
            val enumInput = projectEnumRecipeTypeInputs.inputFor(type, unit)
            when {
                isCooking -> cookingCalls[call] = true
                enumInput == "net.neoforged.neoforge.items.wrapper.RecipeWrapper" ->
                    cookingCalls[call] = false
                else -> {
                    supported = false
                    break
                }
            }
        }
        if (!supported) continue
        var inputType = "ModporterRecipeInput"
        while (source.contains(inputType)) inputType += "_"
        var factoryName = "modporterRecipeInputFactory"
        while (source.contains(factoryName)) factoryName += "_"
        var wrapperName = "modporterRecipeWrapper"
        while (source.contains(wrapperName)) wrapperName += "_"
        variable.typeBound.first { it.nameAsString == "Recipe" }.typeArguments.get()[0] =
            parser.parseType(inputType).result.orElseThrow()
        method.typeParameters.addFirst(
            parser.parseTypeParameter(
                "$inputType extends net.minecraft.world.item.crafting.RecipeInput"
            ).result.orElseThrow()
        )
        method.addParameter(
            "java.util.function.Function<net.neoforged.neoforge.items.wrapper.RecipeWrapper, $inputType>",
            factoryName
        )
        managerCall.arguments[1] = parser.parseExpression<Expression>(
            "$factoryName.apply(${legacyInput.nameAsString})"
        ).result.orElseThrow()
        for ((call, isCooking) in cookingCalls) {
            val factory = if (isCooking) {
                "$wrapperName -> new net.minecraft.world.item.crafting.SingleRecipeInput(" +
                    "$wrapperName.getItem(0))"
            } else {
                "java.util.function.Function.identity()"
            }
            call.addArgument(parser.parseExpression<Expression>(factory).result.orElseThrow())
        }
        changed = true
    }
    if (!changed) return source
    val result = LexicalPreservingPrinter.print(unit)
    check(parser.parse(result).isSuccessful) {
        "Single-slot recipe helper migration emitted invalid Java"
    }
    return result
}
