package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.TypeParameter
import com.modporter.core.transforms.util.JavaSourceText

/**
 * JEI 19 carries vanilla crafting recipes in RecipeHolder and Minecraft 1.21 exposes recipes
 * through RecipeManager's public holder APIs. These repairs are admitted only at the exact JEI,
 * vanilla recipe-manager, effect-holder, and ItemStack serialization boundaries involved.
 */
internal fun StructuralRefactorPass.migrateLegacyJeiRecipeBoundaries(source: String): String {
    var result = repairMigratedJeiAbstractCategoryBackground(source)
    result = repairMigratedJeiSupplierStackFactory(result)
    result = migrateJeiCraftingTransferHolder(result)
    result = migrateJeiPotionEffectHolderSets(result)
    result = migrateJeiPublicTypedRecipeLookup(result)
    result = migrateJeiPolishingStackSerialization(result)
    return result
}

/**
 * Older pipeline output removed the still-valid abstract getBackground override and then selected
 * the nested IDrawable#draw method as the category draw target. Restore the API method and remove
 * only that impossible anonymous-class access, so reprocessing old output converges with fresh
 * source after [migrateJeiRecipeCategoryBackgroundApi]'s abstract-owner guard.
 */
private fun StructuralRefactorPass.repairMigratedJeiAbstractCategoryBackground(
    source: String
): String {
    if (!source.contains("abstract class") ||
        !source.contains("IRecipeCategory<") ||
        !source.contains("this.background.draw(") ||
        !source.contains("IDrawable")
    ) {
        return source
    }
    val parsed = parseJeiSource(source) ?: return source
    val unit = parsed.first
    val offsets = parsed.second
    val typing = SourceValueTypes(this, source)
    val edits = mutableListOf<Pair<IntRange, String>>()

    for (owner in unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
        if (!owner.isAbstract ||
            owner.implementedTypes.none {
                typing.isClassType(it, "mezz.jei.api.recipe.category.IRecipeCategory")
            }
        ) {
            continue
        }
        val backgroundField = owner.fields
            .filterNot { it.isStatic }
            .flatMap { it.variables }
            .singleOrNull {
                it.nameAsString == "background" &&
                    typing.isClassType(it.type, "mezz.jei.api.gui.drawable.IDrawable")
            } ?: continue
        val assignsBackground = owner.constructors.any { constructor ->
            constructor.body.findAll(AssignExpr::class.java).any { assignment ->
                assignment.target.isThisField("background") &&
                    (assignment.value as? MethodCallExpr)?.let { value ->
                        value.nameAsString == "background" &&
                            value.arguments.isEmpty() &&
                            value.scope.map { it is NameExpr }.orElse(false)
                    } == true
            }
        }
        if (!assignsBackground) continue

        val invalidStatements = owner.directMethods("asDrawable")
            .filter {
                it.isStatic &&
                    typing.isClassType(
                        it.type,
                        "mezz.jei.api.gui.drawable.IDrawable"
                    )
            }
            .flatMap { method ->
                method.body.orElse(null)
                    ?.findAll(ObjectCreationExpr::class.java)
                    .orEmpty()
            }
            .filter {
                typing.isClassType(
                    it.type,
                    "mezz.jei.api.gui.drawable.IDrawable"
                ) && it.anonymousClassBody.isPresent
            }
            .flatMap { creation ->
                creation.anonymousClassBody.orElseThrow()
                    .filterIsInstance<MethodDeclaration>()
                    .filter { draw ->
                        draw.nameAsString == "draw" &&
                            draw.parameters.size == 3 &&
                            typing.isClassType(
                                draw.parameters[0].type,
                                "net.minecraft.client.gui.GuiGraphics"
                            )
                    }
            }
            .flatMap { draw ->
                val graphicsName = draw.parameters[0].nameAsString
                draw.body.orElse(null)
                    ?.statements
                    .orEmpty()
                    .filterIsInstance<ExpressionStmt>()
                    .filter { statement ->
                        val call = statement.expression as? MethodCallExpr
                            ?: return@filter false
                        call.nameAsString == "draw" &&
                            call.arguments.singleOrNull()?.let {
                                it is NameExpr && it.nameAsString == graphicsName
                            } == true &&
                            call.scope.map {
                                it.isThisField(backgroundField.nameAsString)
                            }.orElse(false)
                    }
            }
        if (invalidStatements.isEmpty()) continue
        invalidStatements.forEach { statement ->
            offsets.range(statement)?.let { edits += it to "" }
        }

        val hasBackgroundMethod = owner.directMethods("getBackground").any {
            it.parameters.isEmpty() &&
                typing.isClassType(
                    it.type,
                    "mezz.jei.api.gui.drawable.IDrawable"
                )
        }
        if (!hasBackgroundMethod) {
            val getIcon = owner.directMethods("getIcon").singleOrNull {
                it.parameters.isEmpty() &&
                    typing.isClassType(
                        it.type,
                        "mezz.jei.api.gui.drawable.IDrawable"
                    )
            } ?: continue
            val insertAt = offsets.start(getIcon)
            if (insertAt < 0) continue
            val indent = offsets.lineIndent(getIcon)
            val method = buildString {
                append(indent).append("@Override\n")
                append(indent).append("public IDrawable getBackground() {\n")
                append(indent).append("\treturn this.background;\n")
                append(indent).append("}\n\n")
            }
            edits += (insertAt until insertAt) to method
        }
    }

    return applyStringEdits(source, edits)
}

/**
 * Older hover-name output passed a Supplier<ItemStack> as Util.make's object argument. Move the
 * supplier outside Util.make while retaining the original stack expression and naming consumer,
 * so every Supplier#get call still creates a fresh ItemStack.
 */
private fun StructuralRefactorPass.repairMigratedJeiSupplierStackFactory(source: String): String {
    val executable = maskJavaCommentsAndLiterals(source)
    if (!source.contains("Supplier<ItemStack>") ||
        !Regex("""\bUtil\s*\.\s*make\s*\(\s*\(\s*\)\s*->""")
            .containsMatchIn(executable) ||
        !source.contains("DataComponents.CUSTOM_NAME")
    ) {
        return source
    }
    val parsed = parseJeiSource(source) ?: return source
    val unit = parsed.first
    val offsets = parsed.second
    val typing = SourceValueTypes(this, source)
    val edits = mutableListOf<Pair<IntRange, String>>()

    for (method in unit.findAll(MethodDeclaration::class.java)) {
        val returnType = method.type as? ClassOrInterfaceType ?: continue
        val returnedItem = returnType.arguments().singleOrNull() ?: continue
        if (!method.isStatic ||
            !typing.isClassType(returnType, "java.util.function.Supplier") ||
            !typing.isClassType(
                returnedItem,
                "net.minecraft.world.item.ItemStack"
            )
        ) {
            continue
        }
        val returned = method.body.orElse(null)
            ?.statements
            ?.singleOrNull() as? ReturnStmt ?: continue
        val make = returned.expression.orElse(null) as? MethodCallExpr ?: continue
        if (make.nameAsString != "make" ||
            make.arguments.size != 2 ||
            !make.scope.map {
                typing.isTypeName(it.toString(), "net.minecraft.Util")
            }.orElse(false)
        ) {
            continue
        }
        val supplier = make.arguments[0] as? LambdaExpr ?: continue
        if (supplier.parameters.isNotEmpty()) continue
        val stack = (supplier.body as? ExpressionStmt)?.expression as? MethodCallExpr
            ?: continue
        if (stack.nameAsString != "asStack" || stack.arguments.isNotEmpty()) continue
        val consumer = make.arguments[1] as? LambdaExpr ?: continue
        val consumerParameter = consumer.parameters.singleOrNull()?.nameAsString ?: continue
        val setter = (consumer.body as? ExpressionStmt)?.expression as? MethodCallExpr
            ?: continue
        if (setter.nameAsString != "set" ||
            setter.arguments.size != 2 ||
            !setter.arguments[0].toString().endsWith("DataComponents.CUSTOM_NAME") ||
            !setter.scope.map {
                it is NameExpr && it.nameAsString == consumerParameter
            }.orElse(false)
        ) {
            continue
        }
        val stackRange = offsets.range(stack) ?: continue
        val consumerRange = offsets.range(consumer) ?: continue
        val makeRange = offsets.range(make) ?: continue
        val stackText = source.substring(stackRange)
        val consumerText = source.substring(consumerRange)
        edits += makeRange to
            "() -> net.minecraft.Util.make($stackText, $consumerText)"
    }

    return applyStringEdits(source, edits)
}

private fun Expression.isThisField(name: String): Boolean {
    val field = this as? FieldAccessExpr ?: return false
    return field.nameAsString == name && field.scope is ThisExpr
}

private fun StructuralRefactorPass.migrateJeiCraftingTransferHolder(source: String): String {
    if (!source.contains("IRecipeTransferHandler") ||
        !source.contains("RecipeTypes.CRAFTING") ||
        !source.contains(".getId()")
    ) {
        return source
    }
    val parsed = parseJeiSource(source) ?: return source
    val unit = parsed.first
    val offsets = parsed.second
    val typing = SourceValueTypes(this, source)
    val edits = mutableListOf<Pair<IntRange, String>>()

    for (owner in unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
        val handlerType = owner.implementedTypes.singleOrNull { implemented ->
            typing.isClassType(
                implemented,
                "mezz.jei.api.recipe.transfer.IRecipeTransferHandler"
            ) && implemented.arguments().size == 2 &&
                typing.isClassType(
                    implemented.arguments()[1],
                    "net.minecraft.world.item.crafting.CraftingRecipe"
                )
        } ?: continue
        val handlerArguments = handlerType.arguments()

        val getRecipeType = owner.directMethods("getRecipeType").singleOrNull() ?: continue
        if (getRecipeType.parameters.isNotEmpty()) continue
        val recipeType = getRecipeType.type as? ClassOrInterfaceType ?: continue
        val recipeTypeArguments = recipeType.arguments()
        if (!typing.isClassType(recipeType, "mezz.jei.api.recipe.RecipeType") ||
            recipeTypeArguments.size != 1 ||
            !typing.isClassType(
                recipeTypeArguments.single(),
                "net.minecraft.world.item.crafting.CraftingRecipe"
            )
        ) {
            continue
        }
        val returnedType = getRecipeType.body.orElse(null)
            ?.findAll(ReturnStmt::class.java)
            ?.singleOrNull()
            ?.expression
            ?.orElse(null) as? FieldAccessExpr ?: continue
        if (returnedType.nameAsString != "CRAFTING" ||
            !typing.isTypeName(
                returnedType.scope.toString(),
                "mezz.jei.api.constants.RecipeTypes"
            )
        ) {
            continue
        }

        val transfer = owner.directMethods("transferRecipe").singleOrNull() ?: continue
        if (!transfer.isPublic || transfer.isStatic || transfer.parameters.size != 6 ||
            !typing.isClassType(
                transfer.type,
                "mezz.jei.api.recipe.transfer.IRecipeTransferError"
            ) ||
            transfer.parameters[0].type.toString() != handlerArguments[0].toString() ||
            !typing.isClassType(
                transfer.parameters[1].type,
                "net.minecraft.world.item.crafting.CraftingRecipe"
            ) ||
            !typing.isClassType(
                transfer.parameters[2].type,
                "mezz.jei.api.gui.ingredient.IRecipeSlotsView"
            ) ||
            !typing.isClassType(
                transfer.parameters[3].type,
                "net.minecraft.world.entity.player.Player"
            ) ||
            !transfer.parameters[4].type.isPrimitiveType ||
            transfer.parameters[4].type.toString() != "boolean" ||
            !transfer.parameters[5].type.isPrimitiveType ||
            transfer.parameters[5].type.toString() != "boolean"
        ) {
            continue
        }
        val recipeParameter = transfer.parameters[1].nameAsString
        val body = transfer.body.orElse(null) ?: continue
        val recipeUses = body.findAll(NameExpr::class.java)
            .filter { it.nameAsString == recipeParameter }
        if (recipeUses.isEmpty()) continue
        val idCalls = recipeUses.mapNotNull { use ->
            val parent = use.parentNode.orElse(null) as? MethodCallExpr ?: return@mapNotNull null
            parent.takeIf {
                it.scope.orElse(null) === use &&
                    it.nameAsString == "getId" &&
                    it.arguments.isEmpty()
            }
        }
        if (idCalls.size != recipeUses.size) continue

        offsets.range(handlerArguments[1])?.let {
            edits += it to "RecipeHolder<CraftingRecipe>"
        }
        offsets.range(recipeTypeArguments.single())?.let {
            edits += it to "RecipeHolder<CraftingRecipe>"
        }
        offsets.range(transfer.parameters[1].type)?.let {
            edits += it to "RecipeHolder<CraftingRecipe>"
        }
        idCalls.forEach { call ->
            offsets.range(call)?.let { edits += it to "$recipeParameter.id()" }
        }
    }

    if (edits.isEmpty()) return source
    return JavaSourceText.addImport(
        applyStringEdits(source, edits),
        "net.minecraft.world.item.crafting.RecipeHolder"
    )
}

private fun StructuralRefactorPass.migrateJeiPotionEffectHolderSets(source: String): String {
    if (!source.contains("Set<Set<MobEffect>>") ||
        !source.contains("MobEffectInstance::getEffect") ||
        !source.contains("Collectors.toSet()")
    ) {
        return source
    }
    val parsed = parseJeiSource(source) ?: return source
    val unit = parsed.first
    val offsets = parsed.second
    val typing = SourceValueTypes(this, source)
    val edits = mutableListOf<Pair<IntRange, String>>()

    for (variable in unit.findAll(VariableDeclarator::class.java)) {
        val outerSet = variable.type as? ClassOrInterfaceType ?: continue
        val innerSet = outerSet.arguments().singleOrNull() as? ClassOrInterfaceType ?: continue
        val effectType = innerSet.arguments().singleOrNull() ?: continue
        if (!typing.isClassType(outerSet, "java.util.Set") ||
            !typing.isClassType(innerSet, "java.util.Set") ||
            !typing.isClassType(effectType, "net.minecraft.world.effect.MobEffect")
        ) {
            continue
        }
        val initializer = variable.initializer.orElse(null) as? ObjectCreationExpr ?: continue
        if (!typing.isClassType(initializer.type, "java.util.HashSet")) continue
        val method = variable.findAncestor(MethodDeclaration::class.java).orElse(null) ?: continue
        val matchingAdds = method.body.orElse(null)
            ?.findAll(MethodCallExpr::class.java)
            .orEmpty()
            .filter { call ->
                call.nameAsString == "add" &&
                    call.scope.map { it is NameExpr && it.nameAsString == variable.nameAsString }
                        .orElse(false) &&
                    call.arguments.size == 1 &&
                    call.arguments.single().isPotionEffectHolderSet(typing, offsets)
            }
        if (matchingAdds.isEmpty()) continue
        offsets.range(variable.type)?.let {
            edits += it to "Set<Set<Holder<MobEffect>>>"
        }
    }

    if (edits.isEmpty()) return source
    return JavaSourceText.addImport(
        applyStringEdits(source, edits),
        "net.minecraft.core.Holder"
    )
}

private fun Expression.isPotionEffectHolderSet(
    typing: SourceValueTypes,
    offsets: SourceOffsets
): Boolean {
    val collect = this as? MethodCallExpr ?: return false
    if (collect.nameAsString != "collect" || collect.arguments.size != 1) return false
    val collector = collect.arguments.single() as? MethodCallExpr ?: return false
    if (collector.nameAsString != "toSet" || collector.arguments.isNotEmpty() ||
        !collector.scope.map {
            typing.isTypeName(it.toString(), "java.util.stream.Collectors")
        }.orElse(false)
    ) {
        return false
    }
    val map = collect.scope.orElse(null) as? MethodCallExpr ?: return false
    if (map.nameAsString != "map" || map.arguments.size != 1) return false
    val effectGetter = map.arguments.single() as? MethodReferenceExpr ?: return false
    if (effectGetter.identifier != "getEffect" ||
        !typing.isTypeName(
            effectGetter.scope.toString(),
            "net.minecraft.world.effect.MobEffectInstance"
        )
    ) {
        return false
    }
    val stream = map.scope.orElse(null) as? MethodCallExpr ?: return false
    if (stream.nameAsString != "stream" || stream.arguments.isNotEmpty()) return false
    val effects = stream.scope.orElse(null) as? MethodCallExpr ?: return false
    if (effects.nameAsString != "getEffects" || effects.arguments.isNotEmpty()) return false
    val potion = effects.scope.orElse(null) ?: return false
    return typing.typeOf(potion.toString(), offsets.start(potion)) ==
        "net.minecraft.world.item.alchemy.Potion"
}

private fun StructuralRefactorPass.migrateJeiPublicTypedRecipeLookup(source: String): String {
    if (!source.contains("consumeTypedRecipes") ||
        !source.contains(".getRecipeManager().recipes.get(")
    ) {
        return source
    }
    val parsed = parseJeiSource(source) ?: return source
    val unit = parsed.first
    val offsets = parsed.second
    val typing = SourceValueTypes(this, source)
    val edits = mutableListOf<Pair<IntRange, String>>()

    for (method in unit.findAll(MethodDeclaration::class.java)) {
        if (method.nameAsString != "consumeTypedRecipes" ||
            !method.isPublic ||
            !method.isStatic ||
            !method.type.isVoidType ||
            method.parameters.size != 2
        ) {
            continue
        }
        val recipeVariable = method.typeParameters.singleOrNull()
            ?.takeIf { it.nameAsString == "T" && it.isRecipeBound(typing) }
            ?: continue
        val consumerType = method.parameters[0].type as? ClassOrInterfaceType ?: continue
        val inputRecipeType = method.parameters[1].type as? ClassOrInterfaceType ?: continue
        if (!typing.isClassType(consumerType, "java.util.function.Consumer") ||
            consumerType.arguments().singleOrNull()?.toString() != recipeVariable.nameAsString ||
            !typing.isClassType(
                inputRecipeType,
                "net.minecraft.world.item.crafting.RecipeType"
            ) ||
            inputRecipeType.arguments().singleOrNull()?.isWildcardType != true
        ) {
            continue
        }
        val consumerName = method.parameters[0].nameAsString
        val typeName = method.parameters[1].nameAsString
        val legacyMap = method.legacyTypedRecipeMap(typing, offsets, typeName) ?: continue
        if (!method.consumesLegacyTypedRecipeMap(
                legacyMap.nameAsString,
                consumerName,
                recipeVariable.nameAsString
            )
        ) {
            continue
        }
        val suppression = method.annotations.singleOrNull {
            it.nameAsString == "SuppressWarnings"
        } as? SingleMemberAnnotationExpr ?: continue
        if ((suppression.memberValue as? StringLiteralExpr)?.asString() != "unchecked") continue

        val methodIndent = offsets.lineIndent(method)
        val statementIndent = methodIndent + "\t"
        val body = """
            {
            ${statementIndent}List<? extends RecipeHolder<?>> holders = Minecraft.getInstance()
            ${statementIndent}	.getConnection()
            ${statementIndent}	.getRecipeManager()
            ${statementIndent}	.getAllRecipesFor((RecipeType) $typeName);
            ${statementIndent}holders.forEach(holder -> $consumerName.accept((T) holder.value()));
            $methodIndent}
        """.trimIndent()
        offsets.range(method.body.orElseThrow())?.let { edits += it to body }
        offsets.range(suppression)?.let {
            edits += it to "@SuppressWarnings({\"unchecked\", \"rawtypes\"})"
        }
    }

    if (edits.isEmpty()) return source
    var result = applyStringEdits(source, edits)
    result = JavaSourceText.addImport(result, "java.util.List")
    result = JavaSourceText.addImport(
        result,
        "net.minecraft.world.item.crafting.RecipeHolder"
    )
    result = removeUnusedSimpleImport(result, "java.util.Map", "Map")
    return result
}

private fun MethodDeclaration.legacyTypedRecipeMap(
    typing: SourceValueTypes,
    offsets: SourceOffsets,
    typeName: String
): VariableDeclarator? {
    val body = body.orElse(null) ?: return null
    if (body.statements.size != 2) return null
    val declaration = ((body.statements[0] as? ExpressionStmt)?.expression
        as? VariableDeclarationExpr)?.variables?.singleOrNull() ?: return null
    if (!typing.isClassType(declaration.type, "java.util.Map")) return null
    val get = declaration.initializer.orElse(null) as? MethodCallExpr ?: return null
    if (get.nameAsString != "get" ||
        get.arguments.singleOrNull()?.let {
            it is NameExpr && it.nameAsString == typeName
        } != true
    ) {
        return null
    }
    val recipes = get.scope.orElse(null) as? FieldAccessExpr ?: return null
    if (recipes.nameAsString != "recipes") return null
    val manager = recipes.scope as? MethodCallExpr ?: return null
    if (manager.nameAsString != "getRecipeManager" ||
        manager.arguments.isNotEmpty() ||
        !manager.isMinecraftConnectionRecipeManager(typing, offsets)
    ) {
        return null
    }
    return declaration
}

private fun MethodCallExpr.isMinecraftConnectionRecipeManager(
    typing: SourceValueTypes,
    offsets: SourceOffsets
): Boolean {
    if (typing.typeOf(toString(), offsets.start(this)) ==
        "net.minecraft.world.item.crafting.RecipeManager"
    ) {
        return true
    }
    val connection = scope.orElse(null) as? MethodCallExpr ?: return false
    if (connection.nameAsString != "getConnection" || connection.arguments.isNotEmpty()) {
        return false
    }
    val instance = connection.scope.orElse(null) as? MethodCallExpr ?: return false
    if (instance.nameAsString != "getInstance" || instance.arguments.isNotEmpty()) return false
    return instance.scope.map {
        typing.isTypeName(it.toString(), "net.minecraft.client.Minecraft")
    }.orElse(false)
}

private fun MethodDeclaration.consumesLegacyTypedRecipeMap(
    mapName: String,
    consumerName: String,
    recipeVariable: String
): Boolean {
    val body = body.orElse(null) ?: return false
    val branch = body.statements.getOrNull(1) as? IfStmt ?: return false
    if (branch.elseStmt.isPresent) return false
    val condition = branch.condition as? BinaryExpr ?: return false
    if (condition.operator != BinaryExpr.Operator.NOT_EQUALS ||
        !condition.isNameAndNull(mapName)
    ) {
        return false
    }
    val expression = when (val statement = branch.thenStmt) {
        is ExpressionStmt -> statement.expression
        else -> statement.findAll(ExpressionStmt::class.java).singleOrNull()?.expression
    } as? MethodCallExpr ?: return false
    if (expression.nameAsString != "forEach" || expression.arguments.size != 1) return false
    val values = expression.scope.orElse(null) as? MethodCallExpr ?: return false
    if (values.nameAsString != "values" ||
        values.arguments.isNotEmpty() ||
        !values.scope.map {
            it is NameExpr && it.nameAsString == mapName
        }.orElse(false)
    ) {
        return false
    }
    val lambda = expression.arguments.single() as? LambdaExpr ?: return false
    val lambdaParameter = lambda.parameters.singleOrNull()?.nameAsString ?: return false
    val accept = lambda.body.findAll(MethodCallExpr::class.java).singleOrNull {
        it.nameAsString == "accept" &&
            it.scope.map { scope ->
                scope is NameExpr && scope.nameAsString == consumerName
            }.orElse(false)
    } ?: return false
    val cast = accept.arguments.singleOrNull() as? CastExpr ?: return false
    return cast.type.toString() == recipeVariable &&
        cast.expression is NameExpr &&
        cast.expression.asNameExpr().nameAsString == lambdaParameter
}

private fun BinaryExpr.isNameAndNull(name: String): Boolean =
    left is NameExpr && left.asNameExpr().nameAsString == name && right is NullLiteralExpr ||
        right is NameExpr && right.asNameExpr().nameAsString == name && left is NullLiteralExpr

private fun TypeParameter.isRecipeBound(typing: SourceValueTypes): Boolean {
    val bound = typeBound.singleOrNull() as? ClassOrInterfaceType ?: return false
    return typing.isClassType(bound, "net.minecraft.world.item.crafting.Recipe") &&
        bound.arguments().singleOrNull()?.isWildcardType == true
}

private fun StructuralRefactorPass.migrateJeiPolishingStackSerialization(source: String): String {
    if (!source.contains("\"Polishing\"") ||
        !source.contains("\"JEI\"") ||
        !source.contains(".serializeNBT()")
    ) {
        return source
    }
    val parsed = parseJeiSource(source) ?: return source
    val unit = parsed.first
    val offsets = parsed.second
    val typing = SourceValueTypes(this, source)
    val edits = mutableListOf<Pair<IntRange, String>>()

    for (method in unit.findAll(MethodDeclaration::class.java)) {
        if (method.nameAsString != "draw" || method.parameters.size != 5 ||
            !typing.isClassType(
                method.parameters[2].type,
                "net.minecraft.client.gui.GuiGraphics"
            )
        ) {
            continue
        }
        val recipeName = method.parameters[0].nameAsString
        val matchingStacks = method.body.orElse(null)
            ?.findAll(VariableDeclarator::class.java)
            .orEmpty()
            .singleOrNull { variable ->
                val array = variable.type as? ArrayType
                array != null &&
                    typing.isClassType(
                        array.componentType,
                        "net.minecraft.world.item.ItemStack"
                    ) &&
                    variable.initializer.map {
                        it.isRecipeIngredientItems(recipeName, method, typing)
                    }.orElse(false)
            } ?: continue
        val body = method.body.orElse(null) ?: continue
        val tagVariable = body.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
            typing.isClassType(variable.type, "net.minecraft.nbt.CompoundTag") &&
                variable.initializer.map { initializer ->
                    initializer.isCopiedItemCustomData(typing, offsets)
                }.orElse(false)
        } ?: continue
        val tagName = tagVariable.nameAsString
        val polishingPut = body.findAll(MethodCallExpr::class.java).singleOrNull { call ->
            call.nameAsString == "put" &&
                call.scope.map {
                    it is NameExpr && it.nameAsString == tagName
                }.orElse(false) &&
                (call.arguments.firstOrNull() as? StringLiteralExpr)?.asString() ==
                    "Polishing" &&
                call.arguments.getOrNull(1).isMatchingStackSerialization(
                    matchingStacks.nameAsString
                )
        } ?: continue
        if (!body.findAll(MethodCallExpr::class.java).any { call ->
                call.nameAsString == "putBoolean" &&
                    call.scope.map {
                        it is NameExpr && it.nameAsString == tagName
                    }.orElse(false) &&
                    (call.arguments.firstOrNull() as? StringLiteralExpr)?.asString() == "JEI" &&
                    call.arguments.getOrNull(1)?.toString() == "true"
            } ||
            !body.findAll(MethodCallExpr::class.java).any { call ->
                call.nameAsString == "set" &&
                    call.arguments.size == 2 &&
                    call.arguments[0].toString().endsWith("DataComponents.CUSTOM_DATA") &&
                    (call.arguments[1] as? MethodCallExpr)?.let { customData ->
                        customData.nameAsString == "of" &&
                            customData.arguments.singleOrNull()?.toString() == tagName &&
                            customData.scope.map {
                                typing.isTypeName(
                                    it.toString(),
                                    "net.minecraft.world.item.component.CustomData"
                                )
                            }.orElse(false)
                    } == true &&
                    call.scope.map {
                        typing.typeOf(it.toString(), offsets.start(it)) ==
                            "net.minecraft.world.item.ItemStack"
                    }.orElse(false)
            }
        ) {
            continue
        }
        val serialization = polishingPut.arguments[1] as MethodCallExpr
        val matchingStack = serialization.scope.orElseThrow()
        offsets.range(serialization)?.let {
            edits += it to
                "$matchingStack.saveOptional(net.minecraft.client.Minecraft.getInstance().level.registryAccess())"
        }
    }

    return applyStringEdits(source, edits)
}

private fun Expression.isRecipeIngredientItems(
    recipeName: String,
    method: MethodDeclaration,
    typing: SourceValueTypes
): Boolean {
    val items = this as? MethodCallExpr ?: return false
    if (items.nameAsString != "getItems" || items.arguments.isNotEmpty()) return false
    val first = items.scope.orElse(null) as? MethodCallExpr ?: return false
    if (first.nameAsString != "get" ||
        first.arguments.singleOrNull()?.toString() != "0"
    ) {
        return false
    }
    val source = first.scope.orElse(null) ?: return false
    if (source is MethodCallExpr) {
        return source.isRecipeIngredientsCall(recipeName)
    }
    val name = source as? NameExpr ?: return false
    return method.body.orElse(null)
        ?.findAll(VariableDeclarator::class.java)
        .orEmpty()
        .singleOrNull { variable ->
            variable.nameAsString == name.nameAsString &&
                typing.isClassType(variable.type, "net.minecraft.core.NonNullList") &&
                (variable.type as? ClassOrInterfaceType)
                    ?.arguments()
                    ?.singleOrNull()
                    ?.let {
                        typing.isClassType(
                            it,
                            "net.minecraft.world.item.crafting.Ingredient"
                        )
                    } == true &&
                variable.initializer.map {
                    (it as? MethodCallExpr)?.isRecipeIngredientsCall(recipeName) == true
                }.orElse(false)
        } != null
}

private fun MethodCallExpr.isRecipeIngredientsCall(recipeName: String): Boolean =
    nameAsString == "getIngredients" &&
        arguments.isEmpty() &&
        scope.map {
            it is NameExpr && it.nameAsString == recipeName
        }.orElse(false)

private fun Expression.isCopiedItemCustomData(
    typing: SourceValueTypes,
    offsets: SourceOffsets
): Boolean {
    val copy = this as? MethodCallExpr ?: return false
    if (copy.nameAsString != "copyTag" || copy.arguments.isNotEmpty()) return false
    val get = copy.scope.orElse(null) as? MethodCallExpr ?: return false
    if (get.nameAsString != "getOrDefault" ||
        get.arguments.size != 2 ||
        !get.arguments[0].toString().endsWith("DataComponents.CUSTOM_DATA") ||
        !get.arguments[1].toString().endsWith("CustomData.EMPTY")
    ) {
        return false
    }
    return get.scope.map {
        typing.typeOf(it.toString(), offsets.start(it)) ==
            "net.minecraft.world.item.ItemStack"
    }.orElse(false)
}

private fun Expression?.isMatchingStackSerialization(variableName: String): Boolean {
    val call = this as? MethodCallExpr ?: return false
    if (call.nameAsString != "serializeNBT" || call.arguments.isNotEmpty()) return false
    val array = call.scope.orElse(null) as? ArrayAccessExpr ?: return false
    return array.name is NameExpr &&
        array.name.asNameExpr().nameAsString == variableName &&
        array.index.toString() == "0"
}

private fun parseJeiSource(source: String): Pair<com.github.javaparser.ast.CompilationUnit, SourceOffsets>? {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    return parser.parse(source).result.orElse(null)?.let { it to SourceOffsets(source) }
}

private class SourceOffsets(private val source: String) {
    private val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }

    fun start(node: Node): Int = node.range.map {
        lineStarts[it.begin.line - 1] + it.begin.column - 1
    }.orElse(-1)

    fun range(node: Node): IntRange? = node.range.map {
        val first = lineStarts[it.begin.line - 1] + it.begin.column - 1
        val last = lineStarts[it.end.line - 1] + it.end.column - 1
        first..last
    }.orElse(null)

    fun lineIndent(node: Node): String {
        val at = start(node)
        if (at < 0) return ""
        val lineStart = source.lastIndexOf('\n', at - 1).let { if (it < 0) 0 else it + 1 }
        return source.substring(lineStart, at).takeWhile { it == ' ' || it == '\t' }
    }
}

private fun ClassOrInterfaceDeclaration.directMethods(name: String): List<MethodDeclaration> =
    members.filterIsInstance<MethodDeclaration>().filter { it.nameAsString == name }

private fun ClassOrInterfaceType.arguments(): List<Type> =
    typeArguments.map { it.toList() }.orElse(emptyList())

private fun SourceValueTypes.isClassType(type: Type, canonical: String): Boolean {
    val classType = type as? ClassOrInterfaceType ?: return false
    return isTypeName(classType.nameWithScope, canonical)
}
