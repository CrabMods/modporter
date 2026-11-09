package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Preserves the legacy Forge stained-glass ingredient sets in Create's washing recipes.
 *
 * NeoForge defines the old `forge:stained_glass` convention as the intersection of
 * `c:glass_blocks` and `c:dyed`, and `forge:stained_glass_panes` as the intersection of
 * `c:glass_panes` and `c:dyed`. Using either glass tag alone would also admit clear glass.
 *
 * The two recipes migrate as one transaction and only in CreateWashingRecipeGen with their
 * canonical types, ids, fields, and lambda receiver. This keeps unrelated tag references and
 * lookalike recipe generators unchanged.
 */
internal fun StructuralRefactorPass.migrateStainedGlassIngredientBoundaries(
    source: String
): String {
    if (!source.contains("Tags.Items.STAINED_GLASS")) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    if (parsed.problems.isNotEmpty()) return source
    if (unit.packageDeclaration.map { it.nameAsString }.orElse("") !=
        CREATE_RECIPE_PACKAGE
    ) {
        return source
    }
    val owner = unit.findAll(ClassOrInterfaceDeclaration::class.java)
        .singleOrNull { it.nameAsString == "CreateWashingRecipeGen" }
        ?: return source
    if (owner.extendedTypes.size != 1 ||
        owner.extendedTypes.single().nameWithScope != "WashingRecipeGen" ||
        !stainedGlassCanonicalSimpleType(
            unit,
            "WashingRecipeGen",
            WASHING_RECIPE_GEN
        ) ||
        !stainedGlassCanonicalOwner(
            unit,
            NameExpr("Tags"),
            "Tags",
            TAGS
        ) ||
        !stainedGlassCanonicalOwner(
            unit,
            NameExpr("Ingredient"),
            "Ingredient",
            INGREDIENT
        ) ||
        !stainedGlassSimpleNameAvailable(
            unit,
            "IntersectionIngredient",
            INTERSECTION_INGREDIENT
        )
    ) {
        return source
    }

    val plans = STAINED_GLASS_RECIPES.map { recipe ->
        planStainedGlassIngredient(owner, unit, recipe)
            ?: return source
    }
    val oldCalls = plans.mapNotNull { it.oldCall }
    if (oldCalls.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    for (plan in plans) {
        val call = plan.oldCall ?: continue
        val replacement = parser.parseExpression<Expression>(
            "IntersectionIngredient.of(" +
                "Ingredient.of(Tags.Items.${plan.recipe.targetTag}), " +
                "Ingredient.of(Tags.Items.DYED))"
        ).result.orElse(null) ?: return source
        call.setArgument(0, replacement)
    }

    var result = LexicalPreservingPrinter.print(unit)
    result = JavaSourceText.addImport(result, INTERSECTION_INGREDIENT)
    val reparsed = parser.parse(result)
    return if (reparsed.result.isPresent && reparsed.problems.isEmpty()) result else source
}

private data class StainedGlassRecipe(
    val fieldName: String,
    val recipeId: String,
    val oldTag: String,
    val targetTag: String
)

private data class StainedGlassIngredientPlan(
    val recipe: StainedGlassRecipe,
    val oldCall: MethodCallExpr?
)

private fun planStainedGlassIngredient(
    owner: ClassOrInterfaceDeclaration,
    unit: CompilationUnit,
    recipe: StainedGlassRecipe
): StainedGlassIngredientPlan? {
    val variable = owner.fields.flatMap { it.variables }
        .singleOrNull { it.nameAsString == recipe.fieldName }
        ?: return null
    val initializer = variable.initializer.orElse(null) ?: return null
    val create = initializer.findAll(MethodCallExpr::class.java)
        .singleOrNull { call ->
            call.nameAsString == "create" &&
                call.arguments.size == 2 &&
                (call.arguments[0] as? StringLiteralExpr)?.value ==
                recipe.recipeId &&
                call.arguments[1] is LambdaExpr
        } ?: return null
    val lambda = create.arguments[1] as LambdaExpr
    if (lambda.parameters.size != 1) return null
    val lambdaName = lambda.parameters.single().nameAsString
    val require = lambda.findAll(MethodCallExpr::class.java)
        .filter { call ->
            call.nameAsString == "require" &&
                call.arguments.size == 1 &&
                (call.scope.orElse(null) as? NameExpr)?.nameAsString ==
                lambdaName &&
                call.findAncestor(LambdaExpr::class.java)
                    .map { it === lambda }
                    .orElse(false)
        }
        .singleOrNull() ?: return null
    val argument = require.arguments.single()
    return when {
        stainedGlassTagMember(argument, unit, recipe.oldTag) ->
            StainedGlassIngredientPlan(recipe, require)
        stainedGlassIntersection(argument, unit, recipe.targetTag) ->
            StainedGlassIngredientPlan(recipe, null)
        else -> null
    }
}

private fun stainedGlassIntersection(
    expression: Expression,
    unit: CompilationUnit,
    glassTag: String
): Boolean {
    val intersection = expression as? MethodCallExpr ?: return false
    if (intersection.nameAsString != "of" ||
        intersection.arguments.size != 2 ||
        !stainedGlassCanonicalOwner(
            unit,
            intersection.scope.orElse(null),
            "IntersectionIngredient",
            INTERSECTION_INGREDIENT
        )
    ) {
        return false
    }
    return stainedGlassIngredientOfTag(
        intersection.arguments[0],
        unit,
        glassTag
    ) &&
        stainedGlassIngredientOfTag(
            intersection.arguments[1],
            unit,
            "DYED"
        )
}

private fun stainedGlassIngredientOfTag(
    expression: Expression,
    unit: CompilationUnit,
    tag: String
): Boolean {
    val ingredient = expression as? MethodCallExpr ?: return false
    return ingredient.nameAsString == "of" &&
        ingredient.arguments.size == 1 &&
        stainedGlassCanonicalOwner(
            unit,
            ingredient.scope.orElse(null),
            "Ingredient",
            INGREDIENT
        ) &&
        stainedGlassTagMember(ingredient.arguments.single(), unit, tag)
}

private fun stainedGlassTagMember(
    expression: Expression,
    unit: CompilationUnit,
    member: String
): Boolean {
    val field = expression as? FieldAccessExpr ?: return false
    val items = field.scope as? FieldAccessExpr ?: return false
    return field.nameAsString == member &&
        items.nameAsString == "Items" &&
        stainedGlassCanonicalOwner(
            unit,
            items.scope,
            "Tags",
            TAGS
        )
}

private fun stainedGlassCanonicalOwner(
    unit: CompilationUnit,
    expression: Expression?,
    simple: String,
    qualified: String
): Boolean {
    expression ?: return false
    if (expression.toString() == qualified) return true
    if (expression.toString() != simple ||
        !stainedGlassCanonicalSimpleType(unit, simple, qualified)
    ) {
        return false
    }
    return unit.findAll(Parameter::class.java).none {
        it.nameAsString == simple
    } &&
        unit.findAll(VariableDeclarator::class.java).none {
            it.nameAsString == simple
        }
}

private fun stainedGlassCanonicalSimpleType(
    unit: CompilationUnit,
    simple: String,
    qualified: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    if (unit.findAll(Parameter::class.java).any {
            it.nameAsString == simple
        } ||
        unit.findAll(VariableDeclarator::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    val imports = unit.imports.filter {
        !it.isStatic && !it.isAsterisk &&
            it.name.identifier == simple
    }
    return imports.size == 1 &&
        imports.single().nameAsString == qualified
}

private fun stainedGlassSimpleNameAvailable(
    unit: CompilationUnit,
    simple: String,
    qualified: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    if (unit.findAll(Parameter::class.java).any {
            it.nameAsString == simple
        } ||
        unit.findAll(VariableDeclarator::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    val imports = unit.imports.filter {
        !it.isStatic && !it.isAsterisk &&
            it.name.identifier == simple
    }
    return imports.isEmpty() ||
        imports.size == 1 && imports.single().nameAsString == qualified
}

private val STAINED_GLASS_RECIPES = listOf(
    StainedGlassRecipe(
        "STAINED_GLASS",
        "stained_glass",
        "STAINED_GLASS",
        "GLASS_BLOCKS"
    ),
    StainedGlassRecipe(
        "STAINED_GLASS_PANE",
        "stained_glass_pane",
        "STAINED_GLASS_PANES",
        "GLASS_PANES"
    )
)

private const val CREATE_RECIPE_PACKAGE =
    "com.simibubi.create.foundation.data.recipe"
private const val INGREDIENT =
    "net.minecraft.world.item.crafting.Ingredient"
private const val INTERSECTION_INGREDIENT =
    "net.neoforged.neoforge.common.crafting.IntersectionIngredient"
private const val TAGS = "net.neoforged.neoforge.common.Tags"
private const val WASHING_RECIPE_GEN =
    "com.simibubi.create.api.data.recipe.WashingRecipeGen"
