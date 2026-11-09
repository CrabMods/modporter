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
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Repairs three source-proven holder/value boundaries:
 *
 * 1. Registrate block-loot callbacks use their provider instance and resolve enchantment keys
 *    through that provider;
 * 2. BuiltInRegistries.FLUID#getKey receives the value of NeoForge's deferred fluid holder; and
 * 3. EmptyingRecipeGen builder callbacks pass that value to output(Fluid, int).
 *
 * The admitted receivers and callback roots are canonical so this does not add `.value()` to
 * unrelated holders or method names.
 */
internal fun StructuralRefactorPass.migrateDeclaredHolderBuilderArguments(
    source: String
): String {
    if (!source.contains("createSilkTouchDispatchTable") &&
        !source.contains("NeoForgeMod.MILK") &&
        !source.contains("NeoForgeMod.FLOWING_MILK")
    ) {
        return source
    }

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    if (parsed.problems.isNotEmpty()) return source
    LexicalPreservingPrinter.setup(unit)

    var changed = false
    for (lambda in unit.findAll(LambdaExpr::class.java).toList()) {
        changed = migrateRegistrateOreLootLambda(lambda, unit, parser, source) || changed
    }
    for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
        changed = migrateBuiltInFluidKeyArgument(call, unit, parser, source) || changed
    }
    for (owner in unit.findAll(ClassOrInterfaceDeclaration::class.java).toList()) {
        if (!declaredHolderExtendsEmptyingRecipeGen(owner, unit, source)) continue
        for (lambda in owner.findAll(LambdaExpr::class.java).toList()) {
            if (lambda.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null) !== owner
            ) {
                continue
            }
            changed = migrateEmptyingFluidOutput(lambda, unit, parser, source) || changed
        }
    }

    if (!changed) return source
    val result = LexicalPreservingPrinter.print(unit)
    val reparsed = parser.parse(result)
    return if (reparsed.result.isPresent && reparsed.problems.isEmpty()) result else source
}

private fun migrateRegistrateOreLootLambda(
    lambda: LambdaExpr,
    unit: CompilationUnit,
    parser: JavaParser,
    source: String
): Boolean {
    if (lambda.parameters.size != 2) return false
    val lootCall = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return false
    if (lootCall.nameAsString != "loot" ||
        lootCall.arguments.size != 1 ||
        lootCall.arguments.single() !== lambda
    ) {
        return false
    }

    val tablesName = lambda.parameters[0].nameAsString
    val blockName = lambda.parameters[1].nameAsString
    val dispatches = lambda.findAll(MethodCallExpr::class.java)
        .filter { it.findAncestor(LambdaExpr::class.java).orElse(null) === lambda }
        .filter { call ->
            call.nameAsString == "createSilkTouchDispatchTable" &&
                call.arguments.size == 2 &&
                (call.arguments[0] as? NameExpr)?.nameAsString == blockName &&
                call.scope.map {
                    declaredHolderCanonicalOwner(
                        it,
                        REGISTRATE_BLOCK_LOOT_TABLES_SIMPLE,
                        REGISTRATE_BLOCK_LOOT_TABLES,
                        unit,
                        source
                    )
                }.orElse(false)
        }
    val dispatch = dispatches.singleOrNull() ?: return false
    val addCall = dispatch.findAncestor(MethodCallExpr::class.java).orElse(null)
        ?: return false
    if (addCall.nameAsString != "add" ||
        addCall.arguments.size != 2 ||
        addCall.arguments[1] !== dispatch ||
        (addCall.scope.orElse(null) as? NameExpr)?.nameAsString != tablesName ||
        (addCall.arguments[0] as? NameExpr)?.nameAsString != blockName
    ) {
        return false
    }
    val bonusCalls = dispatch.arguments[1].findAll(MethodCallExpr::class.java)
        .filter { call ->
            call.nameAsString == "addOreBonusCount" &&
                call.arguments.size == 1 &&
                call.scope.map {
                    declaredHolderCanonicalOwner(
                        it,
                        APPLY_BONUS_COUNT_SIMPLE,
                        APPLY_BONUS_COUNT,
                        unit,
                        source
                    )
                }.orElse(false)
        }
    val bonus = bonusCalls.singleOrNull() ?: return false
    val key = bonus.arguments.single()
    if (!declaredHolderCanonicalStaticField(
            key,
            ENCHANTMENTS_SIMPLE,
            ENCHANTMENTS,
            unit,
            source
        )
    ) {
        return false
    }

    dispatch.setScope(NameExpr(tablesName))
    bonus.setArgument(
        0,
        parser.parseExpression<Expression>(
            "$tablesName.getRegistries().holderOrThrow($key)"
        ).result.orElseThrow()
    )
    return true
}

private fun migrateBuiltInFluidKeyArgument(
    call: MethodCallExpr,
    unit: CompilationUnit,
    parser: JavaParser,
    source: String
): Boolean {
    if (call.nameAsString != "getKey" || call.arguments.size != 1) return false
    val registry = call.scope.orElse(null) as? FieldAccessExpr ?: return false
    if (registry.nameAsString != "FLUID" ||
        !declaredHolderCanonicalOwner(
            registry.scope,
            BUILT_IN_REGISTRIES_SIMPLE,
            BUILT_IN_REGISTRIES,
            unit,
            source
        )
    ) {
        return false
    }
    val holder = call.arguments.single()
    if (!declaredHolderNeoForgeFluid(holder, unit, source)) return false
    call.setArgument(
        0,
        parser.parseExpression<Expression>("$holder.value()").result.orElseThrow()
    )
    return true
}

private fun migrateEmptyingFluidOutput(
    lambda: LambdaExpr,
    unit: CompilationUnit,
    parser: JavaParser,
    source: String
): Boolean {
    if (lambda.parameters.size != 1) return false
    val createCall = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return false
    if (createCall.nameAsString != "create" ||
        createCall.arguments.size != 2 ||
        createCall.arguments.last() !== lambda
    ) {
        return false
    }
    val builderName = lambda.parameters.single().nameAsString
    var changed = false
    for (output in lambda.findAll(MethodCallExpr::class.java).toList()) {
        if (output.findAncestor(LambdaExpr::class.java).orElse(null) !== lambda ||
            output.nameAsString != "output" ||
            output.arguments.size != 2 ||
            !declaredHolderRootedIn(output, builderName) ||
            output.arguments[1] !is IntegerLiteralExpr
        ) {
            continue
        }
        val holder = output.arguments[0]
        if (!declaredHolderNeoForgeFluid(holder, unit, source)) continue
        output.setArgument(
            0,
            parser.parseExpression<Expression>("$holder.value()").result.orElseThrow()
        )
        changed = true
    }
    return changed
}

private fun declaredHolderExtendsEmptyingRecipeGen(
    owner: ClassOrInterfaceDeclaration,
    unit: CompilationUnit,
    source: String
): Boolean =
    owner.methods.none { it.nameAsString == "create" && it.parameters.size == 2 } &&
        owner.extendedTypes.any { type ->
            type.nameWithScope == EMPTYING_RECIPE_GEN ||
                type.nameWithScope == EMPTYING_RECIPE_GEN_SIMPLE &&
                declaredHolderCanonicalSimpleType(
                    EMPTYING_RECIPE_GEN_SIMPLE,
                    EMPTYING_RECIPE_GEN,
                    unit,
                    source
                )
        }

private fun declaredHolderNeoForgeFluid(
    expression: Expression,
    unit: CompilationUnit,
    source: String
): Boolean {
    val field = expression as? FieldAccessExpr ?: return false
    if (field.nameAsString !in setOf("MILK", "FLOWING_MILK")) return false
    return declaredHolderCanonicalOwner(
        field.scope,
        NEO_FORGE_MOD_SIMPLE,
        NEO_FORGE_MOD,
        unit,
        source
    )
}

private fun declaredHolderCanonicalStaticField(
    expression: Expression,
    simpleOwner: String,
    qualifiedOwner: String,
    unit: CompilationUnit,
    source: String
): Boolean {
    val field = expression as? FieldAccessExpr ?: return false
    if (!Regex("""[A-Z][A-Z0-9_]*""").matches(field.nameAsString)) return false
    return declaredHolderCanonicalOwner(
        field.scope,
        simpleOwner,
        qualifiedOwner,
        unit,
        source
    )
}

private fun declaredHolderCanonicalOwner(
    expression: Expression,
    simple: String,
    qualified: String,
    unit: CompilationUnit,
    source: String
): Boolean {
    if (expression.toString() == qualified) return true
    val name = expression as? NameExpr ?: return false
    if (name.nameAsString != simple) return false
    if (!declaredHolderCanonicalSimpleType(simple, qualified, unit, source)) return false
    if (unit.findAll(Parameter::class.java).any { it.nameAsString == simple }) return false
    return unit.findAll(VariableDeclarator::class.java).none { it.nameAsString == simple }
}

private fun declaredHolderCanonicalSimpleType(
    simple: String,
    qualified: String,
    unit: CompilationUnit,
    source: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return false
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simple }) return false
    val imported = unit.imports.any {
        !it.isStatic && !it.isAsterisk && it.nameAsString == qualified
    } || Regex("""(?m)^\s*import\s+${Regex.escape(qualified)}\s*;""")
        .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source))
    if (!imported) return false
    return unit.imports.none {
        !it.isStatic &&
            !it.isAsterisk &&
            it.name.identifier == simple &&
            it.nameAsString != qualified
    }
}

private fun declaredHolderRootedIn(call: MethodCallExpr, name: String): Boolean {
    var expression = call.scope.orElse(null) ?: return false
    while (expression is MethodCallExpr) {
        expression = expression.scope.orElse(null) ?: return false
    }
    return (expression as? NameExpr)?.nameAsString == name
}

private const val REGISTRATE_BLOCK_LOOT_TABLES_SIMPLE = "RegistrateBlockLootTables"
private const val REGISTRATE_BLOCK_LOOT_TABLES =
    "com.tterrag.registrate.providers.loot.RegistrateBlockLootTables"
private const val APPLY_BONUS_COUNT_SIMPLE = "ApplyBonusCount"
private const val APPLY_BONUS_COUNT =
    "net.minecraft.world.level.storage.loot.functions.ApplyBonusCount"
private const val ENCHANTMENTS_SIMPLE = "Enchantments"
private const val ENCHANTMENTS = "net.minecraft.world.item.enchantment.Enchantments"
private const val BUILT_IN_REGISTRIES_SIMPLE = "BuiltInRegistries"
private const val BUILT_IN_REGISTRIES = "net.minecraft.core.registries.BuiltInRegistries"
private const val NEO_FORGE_MOD_SIMPLE = "NeoForgeMod"
private const val NEO_FORGE_MOD = "net.neoforged.neoforge.common.NeoForgeMod"
private const val EMPTYING_RECIPE_GEN_SIMPLE = "EmptyingRecipeGen"
private const val EMPTYING_RECIPE_GEN =
    "com.simibubi.create.api.data.recipe.EmptyingRecipeGen"
