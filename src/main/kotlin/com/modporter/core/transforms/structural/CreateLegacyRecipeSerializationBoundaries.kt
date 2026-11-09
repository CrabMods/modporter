package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/**
 * Keeps Create's retained legacy processing-recipe serializer boundary while adapting the exact
 * JSON, nested wire, and runtime-pack contracts exposed by 1.21. The nested sequence format keeps
 * its explicit recipe id; it deliberately does not delegate to RecipeSerializer.streamCodec().
 */
internal fun StructuralRefactorPass.migrateCreateLegacyRecipeSerializationBoundaries(
    source: String
): String {
    var result = migrateProcessingIngredientJson(source)
    result = migrateSequencedRecipeSerialization(result)
    result = migrateCreateSpecialRecipeFactories(result)
    result = migrateRuntimeRecipeSerialization(result)
    return result
}

private fun legacyRecipeParser(): JavaParser = JavaParser(
    ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
)

private fun StructuralRefactorPass.migrateProcessingIngredientJson(source: String): String {
    if (!source.contains("ingredients.forEach") || !source.contains(".toJson()")) return source
    val parser = legacyRecipeParser()
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val owner = unit.findAll(ClassOrInterfaceDeclaration::class.java).singleOrNull { declaration ->
        declaration.nameAsString == "ProcessingRecipeSerializer" &&
            declaration.implementedTypes.any {
                typing.isTypeName(
                    it.nameWithScope,
                    "net.minecraft.world.item.crafting.RecipeSerializer"
                )
            }
    } ?: return source
    val method = owner.methods.singleOrNull { candidate ->
        candidate.nameAsString == "writeToJson" &&
            candidate.parameters.size == 2 &&
            typing.isTypeName(
                candidate.parameters[0].type.toString(),
                "com.google.gson.JsonObject"
            )
    } ?: return source
    val recipeName = method.parameters[1].nameAsString
    val plan = method.findAll(MethodCallExpr::class.java).singleOrNull { outer ->
        if (outer.nameAsString != "forEach" || outer.arguments.size != 1) {
            false
        } else {
            val receiver = outer.scope.orElse(null)?.toString()
            val lambda = outer.arguments.single() as? LambdaExpr
            val item = lambda?.parameters?.singleOrNull()?.nameAsString
            val add = lambda?.expressionBody?.orElse(null) as? MethodCallExpr
            val toJson = add?.arguments?.singleOrNull() as? MethodCallExpr
            receiver == "$recipeName.ingredients" &&
                item != null &&
                add?.nameAsString == "add" &&
                toJson?.nameAsString == "toJson" &&
                toJson.arguments.isEmpty() &&
                toJson.scope.map { it.toString() == item }.orElse(false)
        }
    } ?: return source
    val lambda = plan.arguments.single() as LambdaExpr
    val add = lambda.expressionBody.get() as MethodCallExpr
    val item = lambda.parameters.single().nameAsString

    LexicalPreservingPrinter.setup(unit)
    add.setArgument(
        0,
        parser.parseExpression<Expression>(
            "Ingredient.CODEC_NONEMPTY.encodeStart(JsonOps.INSTANCE, $item).getOrThrow()"
        ).result.orElseThrow()
    )
    return LexicalPreservingPrinter.print(unit)
}

private fun StructuralRefactorPass.migrateSequencedRecipeSerialization(source: String): String {
    if (!source.contains("class SequencedRecipe") ||
        !source.contains("ProcessingRecipeSerializer")
    ) {
        return source
    }
    val parser = legacyRecipeParser()
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    if (parsed.problems.isNotEmpty()) return source
    val typing = SourceValueTypes(this, source)
    val owner = unit.findAll(ClassOrInterfaceDeclaration::class.java).singleOrNull {
        it.nameAsString == "SequencedRecipe" &&
            it.typeParameters.singleOrNull()?.typeBound?.any { bound ->
                typing.isTypeName(
                    bound.nameWithScope,
                    "com.simibubi.create.content.processing.recipe.ProcessingRecipe"
                )
            } == true
    } ?: return source

    data class JsonPlan(
        val method: MethodDeclaration,
        val recipe: VariableDeclarator,
        val call: MethodCallExpr,
        val jsonName: String
    )
    val jsonPlan = owner.methods.mapNotNull { method ->
        if (!method.isStatic ||
            method.nameAsString != "fromJson" ||
            method.parameters.size != 3 ||
            !typing.isTypeName(
                method.parameters[0].type.toString(),
                "com.google.gson.JsonObject"
            )
        ) {
            return@mapNotNull null
        }
        val recipe = method.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
            variable.type.toString().replace(" ", "") == "Recipe<?>" &&
                (variable.initializer.orElse(null) as? MethodCallExpr)?.let { call ->
                    call.nameAsString == "fromJson" &&
                        call.arguments.size == 3 &&
                        call.scope.map {
                            typing.isTypeName(
                                it.toString(),
                                "net.minecraft.world.item.crafting.RecipeManager"
                            )
                        }.orElse(false)
                } == true
        } ?: return@mapNotNull null
        JsonPlan(
            method,
            recipe,
            recipe.initializer.get() as MethodCallExpr,
            method.parameters[0].nameAsString
        )
    }.singleOrNull()

    val mergePlan = owner.findAll(MethodCallExpr::class.java).singleOrNull { merge ->
        if (merge.nameAsString != "merge" ||
            merge.arguments.size != 1 ||
            !merge.scope.map {
                typing.isTypeName(
                    it.toString(),
                    "net.minecraft.world.item.crafting.Ingredient"
                )
            }.orElse(false)
        ) {
            false
        } else {
            val values = merge.arguments.single() as? MethodCallExpr
            values?.nameAsString == "of" &&
                values.arguments.size == 2 &&
                values.scope.map {
                    typing.isTypeName(
                        it.toString(),
                        "com.google.common.collect.ImmutableList"
                    )
                }.orElse(false)
        }
    }

    data class BufferPlan(
        val method: MethodDeclaration,
        val test: InstanceOfExpr,
        val serializerName: String,
        val recipe: VariableDeclarator,
        val recipeId: String,
        val bufferName: String
    )
    val bufferPlan = owner.methods.mapNotNull { method ->
        if (!method.isStatic ||
            method.nameAsString != "readFromBuffer" ||
            method.parameters.size != 1 ||
            !typing.isTypeName(
                method.parameters[0].type.toString(),
                "net.minecraft.network.FriendlyByteBuf"
            )
        ) {
            return@mapNotNull null
        }
        val serializer = method.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
            if (!variable.type.isClassOrInterfaceType) {
                false
            } else {
                val type = variable.type.asClassOrInterfaceType()
                val wildcard = type.typeArguments.orElse(null)
                    ?.singleOrNull()
                    ?.takeIf { it.isWildcardType }
                    ?.asWildcardType()
                typing.isTypeName(
                    type.nameWithScope,
                    "net.minecraft.world.item.crafting.RecipeSerializer"
                ) &&
                    wildcard != null &&
                    wildcard.extendedType.isEmpty &&
                    wildcard.superType.isEmpty
            }
        } ?: return@mapNotNull null
        val test = method.findAll(InstanceOfExpr::class.java).singleOrNull { candidate ->
            candidate.expression.toString() == serializer.nameAsString &&
                candidate.pattern.isEmpty &&
                typing.isTypeName(
                    candidate.type.toString().substringBefore('<'),
                    "com.simibubi.create.content.processing.recipe.ProcessingRecipeSerializer"
                )
        } ?: return@mapNotNull null
        val guard = test.findAncestor(IfStmt::class.java).orElse(null)
            ?: return@mapNotNull null
        fun unwrap(expression: Expression): Expression {
            var current = expression
            while (current is EnclosedExpr) current = current.inner
            return current
        }
        val negated = unwrap(guard.condition) as? UnaryExpr ?: return@mapNotNull null
        if (negated.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT ||
            unwrap(negated.expression) !== test
        ) {
            return@mapNotNull null
        }
        val recipe = method.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
            variable.type.toString() == "ProcessingRecipe" &&
                variable.initializer.isPresent
        } ?: return@mapNotNull null
        var initializer: Expression = unwrap(recipe.initializer.get())
        while (initializer is CastExpr) initializer = unwrap(initializer.expression)
        val decode = initializer as? MethodCallExpr ?: return@mapNotNull null
        if (decode.nameAsString != "fromNetwork" ||
            decode.arguments.size != 2 ||
            !decode.scope.map { it.toString() == serializer.nameAsString }.orElse(false) ||
            decode.arguments[1].toString() != method.parameters[0].nameAsString
        ) {
            return@mapNotNull null
        }
        BufferPlan(
            method,
            test,
            serializer.nameAsString,
            recipe,
            decode.arguments[0].toString(),
            method.parameters[0].nameAsString
        )
    }.singleOrNull()
    if (jsonPlan == null && mergePlan == null && bufferPlan == null) return source

    LexicalPreservingPrinter.setup(unit)
    if (jsonPlan != null) {
        val statement = jsonPlan.recipe.findAncestor(ExpressionStmt::class.java).orElse(null)
            ?: return source
        val body = jsonPlan.method.body.orElse(null) ?: return source
        val index = body.statements.indexOf(statement)
        if (index < 0) return source
        val stepExpression = jsonPlan.call.arguments[0].clone()
        statement.remove()
        val statements = listOf(
            "ResourceLocation stepId = $stepExpression;",
            "ResourceLocation serializerId = " +
                "ResourceLocation.parse(GsonHelper.getAsString(${jsonPlan.jsonName}, \"type\"));",
            "RecipeSerializer<?> serializer = " +
                "BuiltInRegistries.RECIPE_SERIALIZER.get(serializerId);",
            """
            if (!(serializer instanceof ProcessingRecipeSerializer<?>))
                throw new JsonParseException("Not a supported recipe type");
            """.trimIndent(),
            "ProcessingRecipeSerializer<?> processingSerializer = " +
                "(ProcessingRecipeSerializer<?>) serializer;",
            "Recipe<?> ${jsonPlan.recipe.nameAsString} = " +
                "processingSerializer.fromJson(stepId, ${jsonPlan.jsonName});"
        ).map { parser.parseStatement(it).result.orElseThrow() }
        statements.forEachIndexed { offset, replacement ->
            body.addStatement(index + offset, replacement)
        }
    }
    if (mergePlan != null) {
        val values = mergePlan.arguments.single() as MethodCallExpr
        mergePlan.replace(
            parser.parseExpression<Expression>(
                "CompoundIngredient.of(${values.arguments[0]}, ${values.arguments[1]})"
            ).result.orElseThrow()
        )
    }
    if (bufferPlan != null) {
        bufferPlan.method.parameters.single().setType("RegistryFriendlyByteBuf")
        val genericSerializerTest = parser.parseExpression<InstanceOfExpr>(
            "${bufferPlan.serializerName} instanceof ProcessingRecipeSerializer<?>"
        ).result.orElseThrow()
        bufferPlan.test.setType(genericSerializerTest.type)
        val recipeStatement = bufferPlan.recipe.findAncestor(ExpressionStmt::class.java)
            .orElse(null)
            ?: return source
        val body = bufferPlan.method.body.orElse(null) ?: return source
        val recipeIndex = body.statements.indexOf(recipeStatement)
        if (recipeIndex < 0) return source
        body.addStatement(
            recipeIndex,
            parser.parseStatement(
                "ProcessingRecipeSerializer<?> processingSerializer = " +
                    "(ProcessingRecipeSerializer<?>) ${bufferPlan.serializerName};"
            ).result.orElseThrow()
        )
        bufferPlan.recipe.setType("ProcessingRecipe<?>")
        bufferPlan.recipe.setInitializer(
            parser.parseExpression<Expression>(
                "processingSerializer.fromNetwork(" +
                    "${bufferPlan.recipeId}, ${bufferPlan.bufferName})"
            ).result.orElseThrow()
        )
    }

    var result = LexicalPreservingPrinter.print(unit)
    if (jsonPlan != null) {
        result = addExecutableImportIfMissing(result, "net.minecraft.util.GsonHelper")
        result = removeUnusedSimpleImports(
            result,
            listOf(
                "net.minecraft.world.item.crafting.RecipeManager",
                "net.neoforged.neoforge.common.conditions.ICondition.IContext"
            )
        )
    }
    if (mergePlan != null) {
        result = addExecutableImportIfMissing(
            result,
            "net.neoforged.neoforge.common.crafting.CompoundIngredient"
        )
        result = removeUnusedSimpleImports(
            result,
            listOf("com.google.common.collect.ImmutableList")
        )
    }
    if (bufferPlan != null) {
        result = removeUnusedSimpleImports(
            result,
            listOf("net.minecraft.network.FriendlyByteBuf")
        )
    }
    return result
}

private fun StructuralRefactorPass.migrateRuntimeRecipeSerialization(source: String): String {
    if (!source.contains("DataGenResult") || !source.contains("serializeRecipe()")) return source
    val parser = legacyRecipeParser()
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)

    data class Plan(
        val method: MethodDeclaration,
        val recipeName: String,
        val resultName: String,
        val put: MethodCallExpr
    )
    val plan = unit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
        if (method.nameAsString != "build" || method.parameters.isNotEmpty()) {
            return@mapNotNull null
        }
        val recipe = method.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
            val call = variable.initializer.orElse(null) as? MethodCallExpr
            call?.nameAsString == "build" &&
                call.arguments.isEmpty() &&
                call.scope.map { it is SuperExpr }.orElse(false)
        } ?: return@mapNotNull null
        val result = method.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
            variable.type.toString().substringBefore('<').endsWith("DataGenResult") &&
                (variable.initializer.orElse(null) as? ObjectCreationExpr)?.let { creation ->
                    creation.type.nameAsString == "DataGenResult" &&
                        creation.arguments.size == 2 &&
                        creation.arguments[0].toString() == recipe.nameAsString &&
                        (creation.arguments[1] as? MethodCallExpr)?.let { empty ->
                            empty.nameAsString == "emptyList" &&
                                empty.arguments.isEmpty() &&
                                empty.scope.map {
                                    typing.isTypeName(
                                        it.toString(),
                                        "java.util.Collections"
                                    )
                                }.orElse(false)
                        } == true
                } == true
        } ?: return@mapNotNull null
        val put = method.findAll(MethodCallExpr::class.java).singleOrNull { call ->
            if (call.nameAsString != "put" ||
                call.arguments.size != 2 ||
                call.scope.map { it.toString() == "JSON_FILES" }.orElse(false).not()
            ) {
                false
            } else {
                val prefix = call.arguments[0] as? MethodCallExpr
                val serialization = call.arguments[1] as? MethodCallExpr
                prefix?.nameAsString == "withPrefix" &&
                    (prefix.arguments.singleOrNull() as? StringLiteralExpr)?.value == "recipes/" &&
                    prefix.scope.map {
                        it.toString() == "${result.nameAsString}.getId()"
                    }.orElse(false) &&
                    serialization?.nameAsString == "serializeRecipe" &&
                    serialization.arguments.isEmpty() &&
                    serialization.scope.map {
                        it.toString() == result.nameAsString
                    }.orElse(false)
            }
        } ?: return@mapNotNull null
        Plan(method, recipe.nameAsString, result.nameAsString, put)
    }.singleOrNull() ?: return source
    val statement = plan.put.findAncestor(ExpressionStmt::class.java).orElse(null)
        ?: return source
    val body = plan.method.body.orElse(null) ?: return source
    val index = body.statements.indexOf(statement)
    if (index < 0) return source

    LexicalPreservingPrinter.setup(unit)
    body.addStatement(
        index,
        parser.parseStatement(
            "JsonObject serialized = Recipe.CONDITIONAL_CODEC.encodeStart(" +
                "JsonOps.INSTANCE, Optional.of(new WithConditions<>(${plan.recipeName})))" +
                ".getOrThrow().getAsJsonObject();"
        ).result.orElseThrow()
    )
    val prefix = plan.put.arguments[0] as MethodCallExpr
    prefix.setArgument(0, StringLiteralExpr("recipe/"))
    plan.put.setArgument(1, NameExpr("serialized"))

    var result = LexicalPreservingPrinter.print(unit)
    result = addExecutableImportIfMissing(result, "java.util.Optional")
    result = addExecutableImportIfMissing(
        result,
        "net.minecraft.world.item.crafting.Recipe"
    )
    result = addExecutableImportIfMissing(
        result,
        "net.neoforged.neoforge.common.conditions.WithConditions"
    )
    return result
}

private fun StructuralRefactorPass.migrateCreateSpecialRecipeFactories(source: String): String {
    if (!source.contains("createSpecial(") ||
        !source.contains("AllRecipeTypes.") ||
        !source.contains("SpecialRecipeBuilder.special(") ||
        !source.contains("SimpleCraftingRecipeSerializer")
    ) {
        return source
    }
    val parser = legacyRecipeParser()
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val helper = unit.findAll(MethodDeclaration::class.java).singleOrNull { method ->
        if (method.nameAsString != "createSpecial" ||
            method.parameters.size != 3 ||
            !method.parameters[0].type.isClassOrInterfaceType
        ) {
            false
        } else {
            val supplier = method.parameters[0].type.asClassOrInterfaceType()
            val supplied = supplier.typeArguments.orElse(null)
                ?.singleOrNull()
                ?.takeIf { it.isWildcardType }
                ?.asWildcardType()
                ?.extendedType
                ?.orElse(null)
            typing.isTypeName(
                supplier.nameWithScope,
                "java.util.function.Supplier"
            ) &&
                supplied?.isClassOrInterfaceType == true &&
                typing.isTypeName(
                    supplied.asClassOrInterfaceType().nameWithScope,
                    "net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer"
                ) &&
                typing.isTypeName(
                    method.parameters[1].type.toString(),
                    "java.lang.String"
                ) &&
                typing.isTypeName(
                    method.parameters[2].type.toString(),
                    "java.lang.String"
                )
        }
    } ?: return source
    val factoryName = helper.parameters[0].nameAsString
    val special = helper.findAll(MethodCallExpr::class.java).singleOrNull { call ->
        call.nameAsString == "special" &&
            call.arguments.size == 1 &&
            call.scope.map {
                typing.isTypeName(
                    it.toString(),
                    "net.minecraft.data.recipes.SpecialRecipeBuilder"
                )
            }.orElse(false) &&
            (call.arguments.single() as? MethodCallExpr)?.let { get ->
                get.nameAsString == "get" &&
                    get.arguments.isEmpty() &&
                    get.scope.map { it.toString() == factoryName }.orElse(false)
            } == true
    } ?: return source

    data class CallPlan(
        val reference: MethodReferenceExpr,
        val replacement: String
    )
    val calls = unit.findAll(MethodCallExpr::class.java).filter {
        it.nameAsString == helper.nameAsString && it.arguments.size == 3
    }
    if (calls.size != 2) return source
    val replacements = mapOf(
        "TOOLBOX_DYEING" to "ToolboxDyeingRecipe::new",
        "ITEM_COPYING" to "ItemCopyingRecipe::new"
    )
    val plans = calls.mapNotNull { call ->
        val reference = call.arguments[0] as? MethodReferenceExpr
            ?: return@mapNotNull null
        if (reference.identifier != "getSerializer") return@mapNotNull null
        val scope = reference.scope.toString().trim()
        val separator = scope.lastIndexOf('.')
        if (separator <= 0 || separator == scope.lastIndex) return@mapNotNull null
        val ownerName = scope.substring(0, separator)
        val constantName = scope.substring(separator + 1)
        if (!typing.isTypeName(ownerName, "com.simibubi.create.AllRecipeTypes")) {
            return@mapNotNull null
        }
        val replacement = replacements[constantName] ?: return@mapNotNull null
        CallPlan(reference, replacement)
    }
    if (plans.size != calls.size || plans.map { it.replacement }.toSet().size != 2) {
        return source
    }

    LexicalPreservingPrinter.setup(unit)
    helper.parameters[0]
        .setType("Function<CraftingBookCategory, Recipe<?>>")
    special.setArgument(0, NameExpr(factoryName))
    plans.forEach { plan ->
        plan.reference.replace(
            parser.parseExpression<Expression>(plan.replacement)
                .result.orElseThrow()
        )
    }

    var result = LexicalPreservingPrinter.print(unit)
    listOf(
        "java.util.function.Function",
        "com.simibubi.create.content.equipment.toolbox.ToolboxDyeingRecipe",
        "com.simibubi.create.foundation.recipe.ItemCopyingRecipe",
        "net.minecraft.world.item.crafting.CraftingBookCategory",
        "net.minecraft.world.item.crafting.Recipe"
    ).forEach { result = addExecutableImportIfMissing(result, it) }
    return removeUnusedSimpleImports(
        result,
        listOf(
            "com.simibubi.create.AllRecipeTypes",
            "net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer"
        )
    )
}
