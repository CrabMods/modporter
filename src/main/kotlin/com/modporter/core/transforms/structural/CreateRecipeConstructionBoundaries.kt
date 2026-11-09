package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/**
 * Repairs Create recipe construction boundaries whose 1.20 shapes cannot be inferred from a
 * constructor name alone. Every admission proves the canonical Minecraft/NeoForge owners and the
 * complete surrounding source shape before changing arguments, generic contracts, or component
 * transport.
 */
internal fun StructuralRefactorPass.migrateCreateRecipeConstructionBoundaries(source: String): String {
    var result = migrateBlueprintIngredientValues(source)
    result = migrateStonecutterRecipeConstructor(result)
    result = migrateToolboxColoringRecipeHolders(result)
    result = migrateCustomRecipeComponentCopy(result)
    return result
}

private fun recipeBoundaryParser(): JavaParser = JavaParser(
    ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
)

private fun StructuralRefactorPass.migrateBlueprintIngredientValues(source: String): String {
    if (!source.contains("MultiItemValue") ||
        !source.contains("Ingredient") ||
        !source.contains("convertIItemListToFilter")
    ) {
        return source
    }
    val parser = recipeBoundaryParser()
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    if (!typing.isTypeName(
            "MultiItemValue",
            "net.neoforged.neoforge.common.crafting.MultiItemValue"
        )
    ) {
        return source
    }

    val entry = unit.findAll(MethodDeclaration::class.java).singleOrNull { method ->
        method.parameters.size == 2 &&
            typing.isTypeName(
                method.parameters[0].type.toString(),
                "net.minecraft.world.item.crafting.Ingredient"
            ) &&
            typing.isTypeName(
                method.parameters[1].type.toString(),
                "net.minecraft.core.HolderLookup.Provider"
            ) &&
            method.body.isPresent
    } ?: return source
    val ingredientName = entry.parameters[0].nameAsString
    val providerName = entry.parameters[1].nameAsString
    val acceptedItems = entry.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
        variable.type.isArrayType &&
            typing.isTypeName(
                variable.type.asArrayType().componentType.toString(),
                "net.minecraft.world.item.crafting.Ingredient.Value"
            ) &&
            (variable.initializer.orElse(null) as? FieldAccessExpr)?.let { access ->
                access.scope.toString() == ingredientName && access.nameAsString == "values"
            } == true
    } ?: return source

    val helper = unit.findAll(MethodDeclaration::class.java).singleOrNull { method ->
        method.nameAsString == "convertIItemListToFilter" &&
            method.parameters.size == 2 &&
            typing.isTypeName(
                method.parameters[0].type.toString(),
                "net.minecraft.world.item.crafting.Ingredient.Value"
            ) &&
            typing.isTypeName(
                method.parameters[1].type.toString(),
                "net.minecraft.core.HolderLookup.Provider"
            ) &&
            method.body.isPresent
    } ?: return source
    val itemListName = helper.parameters[0].nameAsString
    if (helper.parameters[1].nameAsString != providerName) return source

    val calls = entry.findAll(MethodCallExpr::class.java).filter { call ->
        call.nameAsString == helper.nameAsString &&
            call.scope.isEmpty &&
            call.arguments.size == 2 &&
            call.arguments[1].toString() == providerName
    }
    if (calls.isEmpty()) return source
    val compoundTest = helper.findAll(InstanceOfExpr::class.java).singleOrNull { test ->
        test.expression.toString() == itemListName &&
            typing.isTypeName(
                test.type.toString(),
                "net.neoforged.neoforge.common.crafting.MultiItemValue"
            )
    } ?: return source
    val compoundIf = compoundTest.findAncestor(IfStmt::class.java).orElse(null)
        ?.takeIf { it.condition === compoundTest }
        ?: return source
    val tagTest = helper.findAll(InstanceOfExpr::class.java).singleOrNull { test ->
        test.expression.toString() == itemListName &&
            typing.isTypeName(
                test.type.toString(),
                "net.minecraft.world.item.crafting.Ingredient.TagValue"
            ) &&
            test.pattern.isEmpty
    } ?: return source
    val tagIf = tagTest.findAncestor(IfStmt::class.java).orElse(null)
        ?.takeIf { it.condition === tagTest }
        ?: return source
    val tagBlock = tagIf.thenStmt as? BlockStmt ?: return source
    val tagLocation = tagIf.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
        typing.isTypeName(
            variable.type.toString(),
            "net.minecraft.resources.ResourceLocation"
        ) &&
            variable.initializer.map { initializer ->
                initializer.toString().contains("$itemListName.serialize()") &&
                    initializer.toString().contains("GsonHelper.getAsString")
            }.orElse(false)
    } ?: return source
    val tagLocationStatement = tagLocation.findAncestor(ExpressionStmt::class.java).orElse(null)
        ?: return source
    val tagUses = tagIf.findAll(MethodCallExpr::class.java).filter { call ->
        call.nameAsString == "create" &&
            call.arguments.singleOrNull()?.toString() == tagLocation.nameAsString &&
            call.scope.map {
                typing.isTypeName(it.toString(), "net.minecraft.tags.ItemTags")
            }.orElse(false)
    }
    if (tagUses.size != 1) return source
    val acceptedStatement = acceptedItems.findAncestor(ExpressionStmt::class.java).orElse(null)
        ?: return source
    val entryBody = entry.body.get()
    val insertion = entryBody.statements.indexOf(acceptedStatement)
    if (insertion < 0) return source

    LexicalPreservingPrinter.setup(unit)
    val compoundName = "isCompoundIngredient"
    entryBody.addStatement(
        insertion,
        parser.parseStatement(
            "boolean $compoundName = $ingredientName.getCustomIngredient() instanceof CompoundIngredient;"
        ).result.orElseThrow()
    )
    helper.addParameter("boolean", compoundName)
    calls.forEach { it.addArgument(compoundName) }
    compoundIf.setCondition(NameExpr(compoundName))
    tagBlock.addStatement(
        0,
        parser.parseStatement("TagValue tagValue = (TagValue) $itemListName;")
            .result.orElseThrow()
    )
    tagLocationStatement.remove()
    tagUses.single().setArgument(
        0,
        parser.parseExpression<Expression>("tagValue.tag().location()")
            .result.orElseThrow()
    )

    var result = LexicalPreservingPrinter.print(unit)
    result = removeExecutableImport(
        result,
        "net.neoforged.neoforge.common.crafting.MultiItemValue"
    )
    result = addExecutableImportIfMissing(
        result,
        "net.neoforged.neoforge.common.crafting.CompoundIngredient"
    )
    return removeUnusedSimpleImports(
        result,
        listOf(
            "net.minecraft.resources.ResourceLocation",
            "net.minecraft.util.GsonHelper"
        )
    )
}

private fun StructuralRefactorPass.migrateStonecutterRecipeConstructor(source: String): String {
    if (!source.contains("StonecutterRecipe") ||
        !source.contains("ResourceLocation") ||
        !source.contains("super(")
    ) {
        return source
    }
    val parser = recipeBoundaryParser()
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val plans = unit.findAll(ConstructorDeclaration::class.java).mapNotNull { constructor ->
        val owner = constructor.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .orElse(null)
            ?: return@mapNotNull null
        if (owner.extendedTypes.none {
                typing.isTypeName(
                    it.nameWithScope,
                    "net.minecraft.world.item.crafting.StonecutterRecipe"
                )
            }
        ) {
            return@mapNotNull null
        }
        val invocation = constructor.body.statements.firstOrNull()
            as? ExplicitConstructorInvocationStmt
            ?: return@mapNotNull null
        if (invocation.isThis || invocation.arguments.size != 4) return@mapNotNull null
        val id = invocation.arguments[0] as? MethodCallExpr ?: return@mapNotNull null
        if (id.nameAsString != "parse" ||
            id.arguments.singleOrNull() !is StringLiteralExpr ||
            (id.arguments.single() as StringLiteralExpr).value.isNotEmpty() ||
            !id.scope.map {
                typing.isTypeName(it.toString(), "net.minecraft.resources.ResourceLocation")
            }.orElse(false)
        ) {
            return@mapNotNull null
        }
        if (invocation.arguments[1] !is StringLiteralExpr) return@mapNotNull null
        val ingredient = invocation.arguments[2] as? NameExpr ?: return@mapNotNull null
        if (constructor.parameters.none {
            it.nameAsString == ingredient.nameAsString &&
                typing.isTypeName(
                    it.type.toString(),
                    "net.minecraft.world.item.crafting.Ingredient"
                )
        }) {
            return@mapNotNull null
        }
        val result = invocation.arguments[3] as? FieldAccessExpr ?: return@mapNotNull null
        if (result.nameAsString != "EMPTY" ||
            !typing.isTypeName(
                result.scope.toString(),
                "net.minecraft.world.item.ItemStack"
            )
        ) {
            return@mapNotNull null
        }
        invocation
    }
    if (plans.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    plans.forEach { it.arguments.removeAt(0) }
    return removeUnusedSimpleImports(
        LexicalPreservingPrinter.print(unit),
        listOf("net.minecraft.resources.ResourceLocation")
    )
}

private fun StructuralRefactorPass.migrateToolboxColoringRecipeHolders(source: String): String {
    if (!source.contains("Stream<CraftingRecipe>") ||
        !source.contains("new ShapelessRecipe(") ||
        !source.contains("Create.asResource")
    ) {
        return source
    }
    val parser = recipeBoundaryParser()
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val method = unit.findAll(MethodDeclaration::class.java).singleOrNull { candidate ->
        if (!candidate.isStatic || !candidate.type.isClassOrInterfaceType) {
            false
        } else {
            val stream = candidate.type.asClassOrInterfaceType()
            val argument = stream.typeArguments.orElse(null)?.singleOrNull()
            typing.isTypeName(stream.nameWithScope, "java.util.stream.Stream") &&
                argument != null &&
                typing.isTypeName(
                    argument.toString(),
                    "net.minecraft.world.item.crafting.CraftingRecipe"
                )
        }
    } ?: return source
    val recipeReturn = method.findAll(ReturnStmt::class.java).singleOrNull { statement ->
        val creation = statement.expression.orElse(null) as? ObjectCreationExpr
        creation?.arguments?.size == 5 &&
            typing.isTypeName(
                creation.type.nameWithScope,
                "net.minecraft.world.item.crafting.ShapelessRecipe"
            )
    } ?: return source
    val creation = recipeReturn.expression.get() as ObjectCreationExpr
    val idName = (creation.arguments[0] as? NameExpr)?.nameAsString ?: return source
    val lambda = recipeReturn.findAncestor(LambdaExpr::class.java).orElse(null)
        ?.takeIf { it.parameters.size == 1 && it.body is BlockStmt }
        ?: return source
    val lambdaBody = lambda.body as BlockStmt
    val idVariable = lambdaBody.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
        variable.nameAsString == idName &&
            typing.isTypeName(
                variable.type.toString(),
                "net.minecraft.resources.ResourceLocation"
            )
    } ?: return source
    if (lambda.findAll(NameExpr::class.java).count { it.nameAsString == idName } != 1) {
        return source
    }
    val idCall = idVariable.initializer.orElse(null) as? MethodCallExpr ?: return source
    if (idCall.nameAsString != "asResource" ||
        idCall.arguments.size != 1 ||
        !idCall.scope.map {
            typing.isTypeName(it.toString(), "com.simibubi.create.Create")
        }.orElse(false)
    ) {
        return source
    }
    val idExpression = idCall.arguments.single() as? BinaryExpr ?: return source
    val idPrefix = idExpression.left as? BinaryExpr ?: return source
    if (idExpression.operator != BinaryExpr.Operator.PLUS ||
        idPrefix.operator != BinaryExpr.Operator.PLUS ||
        idPrefix.left.toString() != creation.arguments[1].toString() ||
        (idPrefix.right as? StringLiteralExpr)?.value != "." ||
        (idExpression.right as? MethodCallExpr)?.let {
            it.nameAsString == "getDescriptionId" && it.arguments.isEmpty()
        } != true
    ) {
        return source
    }
    val idStatement = idVariable.findAncestor(ExpressionStmt::class.java).orElse(null)
        ?: return source
    val returnIndex = lambdaBody.statements.indexOf(recipeReturn)
    if (returnIndex < 0 ||
        lambdaBody.statements.indexOf(idStatement).let { it < 0 || it >= returnIndex }
    ) {
        return source
    }
    if (method.findAll(VariableDeclarator::class.java).any { it.nameAsString == "recipe" }) {
        return source
    }

    LexicalPreservingPrinter.setup(unit)
    method.setType("Stream<RecipeHolder<CraftingRecipe>>")
    idStatement.remove()
    val group = creation.arguments[1].clone()
    val modernRecipe = ObjectCreationExpr()
        .setType(creation.type.clone())
        .setArguments(
            com.github.javaparser.ast.NodeList(
                group.clone(),
                creation.arguments[2].clone(),
                creation.arguments[3].clone(),
                creation.arguments[4].clone()
            )
        )
    val recipeDeclaration = parser.parseStatement(
        "ShapelessRecipe recipe = $modernRecipe;"
    ).result.orElseThrow()
    lambdaBody.addStatement(lambdaBody.statements.indexOf(recipeReturn), recipeDeclaration)
    val createOwner = idCall.scope.get().clone()
    val color = lambda.parameters.single().nameAsString
    recipeReturn.setExpression(
        parser.parseExpression<Expression>(
            "new RecipeHolder<>($createOwner.asResource($group + \"/\" + $color), recipe)"
        ).result.orElseThrow()
    )

    var result = LexicalPreservingPrinter.print(unit)
    result = addExecutableImportIfMissing(
        result,
        "net.minecraft.world.item.crafting.RecipeHolder"
    )
    return removeUnusedSimpleImports(
        result,
        listOf("net.minecraft.resources.ResourceLocation")
    )
}

private fun StructuralRefactorPass.migrateCustomRecipeComponentCopy(source: String): String {
    if (!source.contains("CustomRecipe") ||
        !source.contains("CraftingBookCategory")
    ) {
        return source
    }
    val parser = recipeBoundaryParser()
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    if (parsed.problems.isNotEmpty()) return source
    val typing = SourceValueTypes(this, source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun offset(node: Node): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)

    val constructorPlans = unit.findAll(ConstructorDeclaration::class.java).mapNotNull { constructor ->
        val owner = constructor.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .orElse(null)
            ?: return@mapNotNull null
        if (owner.extendedTypes.none {
                typing.isTypeName(
                    it.nameWithScope,
                    "net.minecraft.world.item.crafting.CustomRecipe"
                )
            }
        ) {
            return@mapNotNull null
        }
        val category = constructor.parameters.singleOrNull()?.takeIf {
            typing.isTypeName(
                it.type.toString(),
                "net.minecraft.world.item.crafting.CraftingBookCategory"
            )
        } ?: return@mapNotNull null
        val delegation = constructor.body.statements.firstOrNull()
            as? ExplicitConstructorInvocationStmt
            ?: return@mapNotNull null
        if (delegation.isThis ||
            delegation.arguments.size != 2 ||
            delegation.arguments[1].toString() != category.nameAsString
        ) {
            return@mapNotNull null
        }
        delegation
    }

    data class ComponentPlan(
        val statement: IfStmt,
        val sourceStack: String,
        val targetStack: String
    )
    val componentPlans = unit.findAll(MethodDeclaration::class.java).flatMap { method ->
        if (method.nameAsString != "assemble" ||
            method.parameters.size != 2 ||
            !typing.isTypeName(
                method.type.toString(),
                "net.minecraft.world.item.ItemStack"
            ) ||
            !typing.isTypeName(
                method.parameters[0].type.toString(),
                "net.minecraft.world.item.crafting.CraftingInput"
            ) ||
            !typing.isTypeName(
                method.parameters[1].type.toString(),
                "net.minecraft.core.HolderLookup.Provider"
            )
        ) {
            return@flatMap emptyList()
        }
        method.findAll(IfStmt::class.java).mapNotNull plan@{ statement ->
            val has = statement.condition as? MethodCallExpr ?: return@plan null
            if (has.nameAsString != "has" || has.arguments.size != 1) return@plan null
            val sourceStack = has.scope.orElse(null)?.toString() ?: return@plan null
            if (typing.typeOf(sourceStack, offset(has.scope.get())) !=
                "net.minecraft.world.item.ItemStack" ||
                !has.arguments.single().toString().endsWith("DataComponents.CUSTOM_DATA")
            ) {
                return@plan null
            }
            val setTag = statement.thenStmt.findAll(MethodCallExpr::class.java).singleOrNull { call ->
                call.nameAsString == "setTag" &&
                    call.arguments.size == 1 &&
                    call.scope.isPresent
            } ?: return@plan null
            val targetStack = setTag.scope.get().toString()
            if (typing.typeOf(targetStack, offset(setTag.scope.get())) !=
                "net.minecraft.world.item.ItemStack"
            ) {
                return@plan null
            }
            val payload = setTag.arguments.single().toString()
            if (!payload.contains("$sourceStack.getOrDefault(") ||
                !payload.contains("DataComponents.CUSTOM_DATA") ||
                !payload.contains(".copyTag()") ||
                !payload.endsWith(".copy()")
            ) {
                return@plan null
            }
            ComponentPlan(statement, sourceStack, targetStack)
        }
    }
    if (constructorPlans.isEmpty() && componentPlans.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    constructorPlans.forEach { it.arguments.removeAt(0) }
    componentPlans.forEach { plan ->
        plan.statement.replace(
            parser.parseStatement(
                """
                if (!${plan.sourceStack}.isComponentsPatchEmpty()) {
                    ${plan.targetStack}.applyComponents(${plan.sourceStack}.getComponentsPatch());
                }
                """.trimIndent()
            ).result.orElseThrow()
        )
    }
    return removeUnusedSimpleImports(
        LexicalPreservingPrinter.print(unit),
        listOf(
            "net.minecraft.resources.ResourceLocation",
            "net.minecraft.core.component.DataComponents",
            "net.minecraft.world.item.component.CustomData"
        )
    )
}
