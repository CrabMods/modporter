package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Migrates two source-proven enchantment collection families to the 1.21.1 holder APIs:
 *
 * 1. an item that contributes one intrinsic keyed enchantment through
 *    getEnchantmentLevel/getAllEnchantments; and
 * 2. a mutable filter that rebuilds an ItemStack's enchantments after testing each holder value.
 *
 * Admission is structural and deliberately requires the complete legacy family. Partial or
 * side-effecting lookalikes remain unchanged.
 */
internal fun StructuralRefactorPass.migrateEnchantmentHolderCollections(source: String): String {
    if (!source.contains("getEnchantmentLevel") &&
        !source.contains("setEnchantments")
    ) {
        return source
    }

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    LexicalPreservingPrinter.setup(unit)

    var changed = false
    var needsHolder = false
    var needsHolderLookup = false
    var needsItemEnchantments = false
    val itemEnchantmentsAvailable = simpleTypeNameAvailable(
        unit,
        "ItemEnchantments",
        ITEM_ENCHANTMENTS
    )
    val intrinsicTargetTypesAvailable =
        itemEnchantmentsAvailable &&
            simpleTypeNameAvailable(unit, "Holder", HOLDER) &&
            simpleTypeNameAvailable(unit, "HolderLookup", HOLDER_LOOKUP)

    if (intrinsicTargetTypesAvailable) {
        for (owner in unit.findAll(ClassOrInterfaceDeclaration::class.java).toList()) {
            val plan = owner.methods
                .filter { it.nameAsString == "getEnchantmentLevel" }
                .mapNotNull { intrinsicEnchantmentPlan(owner, it, source) }
                .singleOrNull()
                ?: continue
            plan.levelMethod.parameters[1].setType("Holder<Enchantment>")

            val lookup = uniqueJavaName(source, "enchantments")
            val mutable = uniqueJavaName(source, "modporterEnchantments", setOf(lookup))
            plan.allMethod.setType("ItemEnchantments")
            plan.allMethod.addParameter(
                "HolderLookup.RegistryLookup<Enchantment>",
                lookup
            )
            if (plan.allMethod.annotations.none { it.nameAsString == "Override" }) {
                plan.allMethod.addAnnotation("Override")
            }
            plan.allMethod.setBody(
                parseBlock(
                    parser,
                    """
                    {
                        ItemEnchantments.Mutable $mutable =
                            new ItemEnchantments.Mutable(
                                super.getAllEnchantments(${plan.allStackName}, $lookup));
                        $mutable.set($lookup.getOrThrow(${plan.keyExpression}), ${plan.levelExpression});
                        return $mutable.toImmutable();
                    }
                    """.trimIndent()
                )
            )
            changed = true
            needsHolder = true
            needsHolderLookup = true
            needsItemEnchantments = true
        }
    }

    if (itemEnchantmentsAvailable) {
        for (block in unit.findAll(BlockStmt::class.java).toList()) {
            var index = 0
            while (index + 2 < block.statements.size) {
                val plan = enchantmentFilterPlan(
                    block.statements[index],
                    block.statements[index + 1],
                    block.statements[index + 2],
                    source
                )
                if (plan == null) {
                    index++
                    continue
                }

                plan.mapVariable.setType("ItemEnchantments.Mutable")
                plan.mapVariable.setInitializer(
                    parser.parseExpression<Expression>(
                        "new ItemEnchantments.Mutable(" +
                            "${plan.helperScope}.getEnchantmentsForCrafting(${plan.stackName}))"
                    ).result.orElseThrow()
                )
                plan.iterationStatement.replace(
                    parser.parseStatement(
                        "${plan.mapName}.removeIf(${plan.holderName} -> " +
                            "!${plan.holderName}.value().canEnchant(${plan.stackName}));"
                    ).result.orElseThrow()
                )
                plan.setCall.setArgument(0, NameExpr(plan.stackName))
                plan.setCall.setArgument(
                    1,
                    parser.parseExpression<Expression>("${plan.mapName}.toImmutable()")
                        .result.orElseThrow()
                )
                changed = true
                needsItemEnchantments = true
                index += 3
            }
        }
    }

    if (!changed) return source
    var result = LexicalPreservingPrinter.print(unit)
    if (needsHolder) {
        result = JavaSourceText.addImport(result, "net.minecraft.core.Holder")
    }
    if (needsHolderLookup) {
        result = JavaSourceText.addImport(result, "net.minecraft.core.HolderLookup")
    }
    if (needsItemEnchantments) {
        result = JavaSourceText.addImport(
            result,
            "net.minecraft.world.item.enchantment.ItemEnchantments"
        )
    }
    val reparsed = parser.parse(result)
    return if (reparsed.result.isPresent && reparsed.problems.isEmpty()) result else source
}

private data class IntrinsicEnchantmentPlan(
    val levelMethod: MethodDeclaration,
    val allMethod: MethodDeclaration,
    val allStackName: String,
    val keyExpression: String,
    val levelExpression: String
)

private fun intrinsicEnchantmentPlan(
    owner: ClassOrInterfaceDeclaration,
    levelMethod: MethodDeclaration,
    source: String
): IntrinsicEnchantmentPlan? {
    if (levelMethod.typeAsString != "int" || levelMethod.parameters.size != 2) return null
    val stackParameter = levelMethod.parameters[0]
    val enchantmentParameter = levelMethod.parameters[1]
    if (!matchesType(stackParameter.type, "ItemStack", ITEM_STACK, source) ||
        !matchesType(enchantmentParameter.type, "Enchantment", ENCHANTMENT, source)
    ) {
        return null
    }

    val statements = levelMethod.body.orElse(null)?.statements ?: return null
    if (statements.size != 2) return null
    val branch = statements[0] as? IfStmt ?: return null
    if (branch.elseStmt.isPresent) return null
    val key = enchantmentKey(branch.condition, enchantmentParameter.nameAsString) ?: return null
    if (!canonicalStaticField(key, "Enchantments", ENCHANTMENTS, source)) return null
    val level = singleReturn(branch.thenStmt)?.expression?.orElse(null) ?: return null
    if (level !is IntegerLiteralExpr ||
        !Regex("""[1-9][0-9]*""").matches(level.value)
    ) {
        return null
    }
    val fallback = statements[1] as? ReturnStmt ?: return null
    val fallbackCall = fallback.expression.orElse(null) as? MethodCallExpr ?: return null
    if (fallbackCall.nameAsString != "getEnchantmentLevel" ||
        fallbackCall.scope.orElse(null) !is SuperExpr ||
        fallbackCall.arguments.size != 2 ||
        fallbackCall.arguments[0].toString() != stackParameter.nameAsString ||
        fallbackCall.arguments[1].toString() != enchantmentParameter.nameAsString
    ) {
        return null
    }

    val allMethods = owner.methods
        .filter { it.nameAsString == "getAllEnchantments" && it.parameters.size == 1 }
        .mapNotNull {
            intrinsicAllMethodPlan(
                it,
                source,
                key.toString(),
                level.toString()
            )
        }
    val all = allMethods.singleOrNull() ?: return null
    return IntrinsicEnchantmentPlan(
        levelMethod,
        all.method,
        all.stackName,
        key.toString(),
        level.toString()
    )
}

private data class IntrinsicAllMethodPlan(
    val method: MethodDeclaration,
    val stackName: String
)

private fun intrinsicAllMethodPlan(
    method: MethodDeclaration,
    source: String,
    keyExpression: String,
    levelExpression: String
): IntrinsicAllMethodPlan? {
    if (!mapOfEnchantmentInteger(method.type as? ClassOrInterfaceType, source)) return null
    val stack = method.parameters.single()
    if (!matchesType(stack.type, "ItemStack", ITEM_STACK, source)) return null
    val statements = method.body.orElse(null)?.statements ?: return null
    if (statements.size != 3) return null

    val map = singleVariable(statements[0]) ?: return null
    if (!mapOfEnchantmentInteger(map.type as? ClassOrInterfaceType, source)) return null
    val initializer = map.initializer.orElse(null) as? MethodCallExpr ?: return null
    if (initializer.nameAsString != "getAllEnchantments" ||
        initializer.scope.orElse(null) !is SuperExpr ||
        initializer.arguments.size != 1 ||
        initializer.arguments.single().toString() != stack.nameAsString
    ) {
        return null
    }

    val put = (statements[1] as? ExpressionStmt)?.expression as? MethodCallExpr ?: return null
    if (put.nameAsString != "put" ||
        put.scope.map { it.toString() }.orElse("") != map.nameAsString ||
        put.arguments.size != 2 ||
        put.arguments[0].toString() != keyExpression ||
        put.arguments[1].toString() != levelExpression
    ) {
        return null
    }

    val returned = statements[2] as? ReturnStmt ?: return null
    if (returned.expression.map { it.toString() }.orElse("") != map.nameAsString) return null
    return IntrinsicAllMethodPlan(method, stack.nameAsString)
}

private data class EnchantmentFilterPlan(
    val mapVariable: VariableDeclarator,
    val mapName: String,
    val stackName: String,
    val helperScope: String,
    val holderName: String,
    val iterationStatement: Statement,
    val setCall: MethodCallExpr
)

private fun enchantmentFilterPlan(
    declarationStatement: Statement,
    iterationStatement: Statement,
    setStatement: Statement,
    source: String
): EnchantmentFilterPlan? {
    val map = singleVariable(declarationStatement) ?: return null
    if (!mapOfEnchantmentInteger(map.type as? ClassOrInterfaceType, source)) return null
    val creation = map.initializer.orElse(null) as? ObjectCreationExpr ?: return null
    if (!matchesClassType(creation.type, "HashMap", JAVA_HASH_MAP, source) ||
        creation.arguments.isNotEmpty()
    ) {
        return null
    }

    val forEach = (iterationStatement as? ExpressionStmt)?.expression as? MethodCallExpr
        ?: return null
    if (forEach.nameAsString != "forEach" || forEach.arguments.size != 1) return null
    val entrySet = forEach.scope.orElse(null) as? MethodCallExpr ?: return null
    if (entrySet.nameAsString != "entrySet" || entrySet.arguments.isNotEmpty()) return null
    val getter = entrySet.scope.orElse(null) as? MethodCallExpr ?: return null
    if (!enchantmentHelperCall(getter, "getEnchantmentsForCrafting", source) ||
        getter.arguments.size != 1
    ) {
        return null
    }
    val stack = getter.arguments.single() as? NameExpr ?: return null
    val method = declarationStatement.findAncestor(MethodDeclaration::class.java)
        .orElse(null)
        ?: return null
    val stackParameter = method.parameters.singleOrNull {
        it.nameAsString == stack.nameAsString
    } ?: return null
    if (!matchesType(stackParameter.type, "ItemStack", ITEM_STACK, source)) return null

    val lambda = forEach.arguments.single() as? LambdaExpr ?: return null
    if (lambda.parameters.size != 1) return null
    val entryName = lambda.parameters.single().nameAsString
    val body = lambda.body as? BlockStmt ?: return null
    if (body.statements.size != 3) return null

    val holder = singleVariable(body.statements[0]) ?: return null
    if (!holderOfEnchantment(holder.type as? ClassOrInterfaceType, source)) return null
    val holderInitializer = holder.initializer.orElse(null) as? MethodCallExpr ?: return null
    if (holderInitializer.nameAsString != "getKey" ||
        holderInitializer.arguments.isNotEmpty() ||
        holderInitializer.scope.map { it.toString() }.orElse("") != entryName
    ) {
        return null
    }

    val level = singleVariable(body.statements[1]) ?: return null
    if (level.typeAsString != "int") return null
    val levelInitializer = level.initializer.orElse(null) as? MethodCallExpr ?: return null
    if (levelInitializer.nameAsString != "getIntValue" ||
        levelInitializer.arguments.isNotEmpty() ||
        levelInitializer.scope.map { it.toString() }.orElse("") != entryName
    ) {
        return null
    }

    val branch = body.statements[2] as? IfStmt ?: return null
    if (branch.elseStmt.isPresent ||
        !canEnchantCondition(branch.condition, holder.nameAsString, stack.nameAsString)
    ) {
        return null
    }
    val put = singleExpression(branch.thenStmt) as? MethodCallExpr ?: return null
    if (put.nameAsString != "put" ||
        put.scope.map { it.toString() }.orElse("") != map.nameAsString ||
        put.arguments.size != 2 ||
        put.arguments[0].toString() != holder.nameAsString ||
        put.arguments[1].toString() != level.nameAsString
    ) {
        return null
    }

    val setCall = (setStatement as? ExpressionStmt)?.expression as? MethodCallExpr
        ?: return null
    if (!enchantmentHelperCall(setCall, "setEnchantments", source) ||
        setCall.arguments.size != 2 ||
        setCall.arguments[0].toString() != map.nameAsString ||
        setCall.arguments[1].toString() != stack.nameAsString
    ) {
        return null
    }
    if (!bindingReferencesAreContained(
            method,
            map.nameAsString,
            listOf(declarationStatement, iterationStatement, setStatement)
        )
    ) {
        return null
    }

    return EnchantmentFilterPlan(
        map,
        map.nameAsString,
        stack.nameAsString,
        getter.scope.orElseThrow().toString(),
        uniqueJavaName(
            declarationStatement.findAncestor(MethodDeclaration::class.java)
                .map { it.toString() }
                .orElse(iterationStatement.toString()),
            "enchantment"
        ),
        iterationStatement,
        setCall
    )
}

private fun enchantmentKey(condition: Expression, enchantmentName: String): Expression? =
    (condition as? MethodCallExpr)?.let {
        if (it.nameAsString == "is" &&
            it.arguments.size == 1 &&
            it.scope.map { scope -> scope.toString() }.orElse("") == enchantmentName
        ) {
            it.arguments.single()
        } else {
            null
        }
    }

private fun canEnchantCondition(
    condition: Expression,
    holderName: String,
    stackName: String
): Boolean {
    val canEnchant = condition as? MethodCallExpr ?: return false
    if (canEnchant.nameAsString != "canEnchant" ||
        canEnchant.arguments.size != 1 ||
        canEnchant.arguments.single().toString() != stackName
    ) {
        return false
    }
    val value = canEnchant.scope.orElse(null) as? MethodCallExpr ?: return false
    return value.nameAsString == "value" &&
        value.arguments.isEmpty() &&
        value.scope.map { it.toString() }.orElse("") == holderName
}

private fun enchantmentHelperCall(
    call: MethodCallExpr,
    name: String,
    source: String
): Boolean =
    call.nameAsString == name &&
        call.scope.map {
            canonicalStaticOwner(
                it,
                "EnchantmentHelper",
                ENCHANTMENT_HELPER,
                source
            )
        }.orElse(false)

private fun mapOfEnchantmentInteger(
    type: ClassOrInterfaceType?,
    source: String
): Boolean {
    if (type == null || !matchesClassType(type, "Map", JAVA_MAP, source)) return false
    val arguments = type.typeArguments.orElse(null) ?: return false
    if (arguments.size != 2) return false
    return matchesType(arguments[0], "Enchantment", ENCHANTMENT, source) &&
        matchesJavaLangInteger(arguments[1])
}

private fun holderOfEnchantment(
    type: ClassOrInterfaceType?,
    source: String
): Boolean {
    if (type == null ||
        !matchesClassType(type, "Holder", HOLDER, source)
    ) {
        return false
    }
    val argument = type.typeArguments.orElse(null)?.singleOrNull() ?: return false
    return matchesType(argument, "Enchantment", ENCHANTMENT, source)
}

private fun matchesType(
    type: Type,
    simple: String,
    qualified: String,
    source: String
): Boolean =
    matchesTypeName(type.asString(), type, simple, qualified, source)

private fun matchesClassType(
    type: ClassOrInterfaceType,
    simple: String,
    qualified: String,
    source: String
): Boolean =
    matchesTypeName(type.nameWithScope, type, simple, qualified, source)

private fun matchesTypeName(
    actual: String,
    anchor: Node,
    simple: String,
    qualified: String,
    source: String
): Boolean {
    if (actual == qualified) return true
    if (actual != simple) return false
    val unit = anchor.findCompilationUnit().orElse(null) ?: return false
    if (!hasExactImport(unit.imports.map { it.toString() }, qualified, source)) return false
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return false
    return unit.findAll(TypeParameter::class.java).none { it.nameAsString == simple }
}

private fun matchesJavaLangInteger(type: Type): Boolean {
    val actual = type.asString()
    if (actual == JAVA_LANG_INTEGER) return true
    if (actual != "Integer") return false
    val unit = type.findCompilationUnit().orElse(null) ?: return false
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == "Integer" }) {
        return false
    }
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == "Integer" }) {
        return false
    }
    if (unit.imports.any { !it.isStatic && it.isAsterisk }) return false
    return unit.imports.none {
        !it.isStatic &&
            !it.isAsterisk &&
            it.name.identifier == "Integer" &&
            it.nameAsString != JAVA_LANG_INTEGER
    }
}

private fun canonicalStaticField(
    expression: Expression,
    simpleOwner: String,
    qualifiedOwner: String,
    source: String
): Boolean {
    val field = expression as? FieldAccessExpr ?: return false
    return canonicalStaticOwner(field.scope, simpleOwner, qualifiedOwner, source)
}

private fun canonicalStaticOwner(
    scope: Expression,
    simple: String,
    qualified: String,
    source: String
): Boolean {
    if (scope.toString() == qualified) return true
    val name = scope as? NameExpr ?: return false
    if (name.nameAsString != simple) return false
    val unit = scope.findCompilationUnit().orElse(null) ?: return false
    if (!hasExactImport(unit.imports.map { it.toString() }, qualified, source)) return false
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return false
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simple }) return false
    if (unit.findAll(Parameter::class.java).any { it.nameAsString == simple }) return false
    return unit.findAll(VariableDeclarator::class.java).none { it.nameAsString == simple }
}

private fun hasExactImport(
    renderedImports: List<String>,
    qualified: String,
    source: String
): Boolean {
    if (renderedImports.any { it.trim() == "import $qualified;" }) return true
    return Regex("""(?m)^\s*import\s+${Regex.escape(qualified)}\s*;""")
        .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source))
}

private fun simpleTypeNameAvailable(
    unit: com.github.javaparser.ast.CompilationUnit,
    simple: String,
    qualified: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return false
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simple }) return false
    val conflictingImports = unit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.name.identifier == simple &&
            it.nameAsString != qualified
    }
    return conflictingImports.isEmpty()
}

private fun bindingReferencesAreContained(
    method: MethodDeclaration,
    name: String,
    allowedRoots: List<Node>
): Boolean =
    method.findAll(NameExpr::class.java)
        .filter { it.nameAsString == name }
        .all { reference -> allowedRoots.any { root -> reference.isWithin(root) } }

private fun Node.isWithin(root: Node): Boolean {
    var current: Node? = this
    while (current != null) {
        if (current === root) return true
        current = current.parentNode.orElse(null)
    }
    return false
}

private fun singleVariable(statement: Statement): VariableDeclarator? =
    ((statement as? ExpressionStmt)?.expression as? VariableDeclarationExpr)
        ?.variables
        ?.singleOrNull()

private fun singleReturn(statement: Statement): ReturnStmt? =
    statement as? ReturnStmt
        ?: (statement as? BlockStmt)?.statements?.singleOrNull() as? ReturnStmt

private fun singleExpression(statement: Statement): Expression? =
    (statement as? ExpressionStmt)?.expression
        ?: ((statement as? BlockStmt)?.statements?.singleOrNull() as? ExpressionStmt)
            ?.expression

private fun uniqueJavaName(
    source: String,
    base: String,
    reserved: Set<String> = emptySet()
): String {
    var result = base
    while (result in reserved || Regex("""\b${Regex.escape(result)}\b""").containsMatchIn(source)) {
        result += "_"
    }
    return result
}

private fun parseBlock(parser: JavaParser, source: String): BlockStmt =
    parser.parseBlock(source).result.orElseThrow()

private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
private const val ENCHANTMENT = "net.minecraft.world.item.enchantment.Enchantment"
private const val ENCHANTMENTS = "net.minecraft.world.item.enchantment.Enchantments"
private const val ENCHANTMENT_HELPER =
    "net.minecraft.world.item.enchantment.EnchantmentHelper"
private const val HOLDER = "net.minecraft.core.Holder"
private const val HOLDER_LOOKUP = "net.minecraft.core.HolderLookup"
private const val ITEM_ENCHANTMENTS =
    "net.minecraft.world.item.enchantment.ItemEnchantments"
private const val JAVA_MAP = "java.util.Map"
private const val JAVA_HASH_MAP = "java.util.HashMap"
private const val JAVA_LANG_INTEGER = "java.lang.Integer"
