package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Value-boundary migrations shared by Create and other mods that retained Forge 1.20 recipe
 * values while NeoForge 1.21 exposes RecipeHolder values. Each rewrite requires both a canonical
 * platform owner and a recipe-valued target; similarly named project APIs remain untouched.
 */
internal fun StructuralRefactorPass.migrateCreateRecipeBoundaryValues(source: String): String {
    var result = migrateShapedRecipeCraftingSizeOwner(source)
    result = migrateRecipeHolderValueBoundaries(result)
    result = migrateDataIngredientCollectionValues(result)
    result = migrateRegistrateRecipeOutputs(result)
    return result
}

private fun StructuralRefactorPass.migrateShapedRecipeCraftingSizeOwner(source: String): String {
    if (!source.contains("setCraftingSize(") || !source.contains("ShapedRecipe")) return source
    val typing = SourceValueTypes(this, source)
    var changed = false
    val migrated = rewriteExecutableJavaCallWithOffset(source, "setCraftingSize") { receiver, args, offset ->
        if (args.size != 2 ||
            !typing.isTypeName(receiver.trim(), "net.minecraft.world.item.crafting.ShapedRecipe")
        ) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        changed = true
        "ShapedRecipePattern.setCraftingSize(${args[0].trim()}, ${args[1].trim()})"
    }
    return if (changed) {
        addExecutableImportIfMissing(migrated, "net.minecraft.world.item.crafting.ShapedRecipePattern")
    } else {
        source
    }
}

private fun StructuralRefactorPass.migrateRecipeHolderValueBoundaries(source: String): String {
    if (!source.contains("getRecipeFor(") &&
        !source.contains("getRecipesFor(") &&
        !source.contains("getRecipes()") &&
        !source.contains("getCookableRecipe(")
    ) {
        return source
    }
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun start(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    fun end(node: Node): Int =
        node.range.map { lineStarts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)

    val typing = SourceValueTypes(this, source)
    val managerAccessorOwners = setOf(
        "net.minecraft.world.level.Level",
        "net.minecraft.server.level.ServerLevel",
        "net.minecraft.client.multiplayer.ClientLevel",
        "net.minecraft.server.MinecraftServer",
        "net.minecraft.client.multiplayer.ClientPacketListener"
    )
    val canonicalRecipeTypes = setOf(
        "net.minecraft.world.item.crafting.Recipe",
        "net.minecraft.world.item.crafting.CraftingRecipe",
        "net.minecraft.world.item.crafting.AbstractCookingRecipe",
        "net.minecraft.world.item.crafting.ShapedRecipe",
        "net.minecraft.world.item.crafting.ShapelessRecipe",
        "net.minecraft.world.item.crafting.SingleItemRecipe",
        "net.minecraft.world.item.crafting.StonecutterRecipe",
        "net.minecraft.world.item.crafting.SmeltingRecipe",
        "net.minecraft.world.item.crafting.BlastingRecipe",
        "net.minecraft.world.item.crafting.SmokingRecipe",
        "net.minecraft.world.item.crafting.CampfireCookingRecipe"
    )

    fun stripVariance(type: String): String =
        type.trim()
            .replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "")
            .removePrefix("? extends ")
            .removePrefix("? super ")
            .trim()

    fun genericArgument(type: String, owners: Set<String>): String? {
        val value = stripVariance(type)
        val open = value.indexOf('<')
        val close = value.lastIndexOf('>')
        if (open < 0 || close <= open) return null
        val owner = value.substring(0, open).trim().substringAfterLast('.')
        if (owner !in owners) return null
        return JavaSourceText.splitTopLevelArgs(value.substring(open + 1, close)).singleOrNull()
    }

    fun isRecipeFamily(type: String, method: MethodDeclaration? = null): Boolean {
        val value = stripVariance(type)
        val raw = value.substringBefore('<').trim()
        if (canonicalRecipeTypes.any { typing.isTypeName(raw, it) }) return true
        val typeParameter = method?.typeParameters?.singleOrNull { it.nameAsString == raw }
        if (typeParameter != null && typeParameter.typeBound.any { bound ->
                val boundRaw = bound.toString().substringBefore('<').trim()
                canonicalRecipeTypes.any { typing.isTypeName(boundRaw, it) }
            }
        ) {
            return true
        }
        val simple = raw.substringAfterLast('.')
        val ownType = unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .singleOrNull { it.nameAsString == simple }
        if (ownType?.implementedTypes?.any {
                canonicalRecipeTypes.any { recipe -> typing.isTypeName(it.nameWithScope, recipe) }
            } == true
        ) {
            return true
        }
        var current = projectValueFacts.types.singleOrNull { typing.isTypeName(raw, it) } ?: return false
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val parent = projectValueFacts.parents[current] ?: return false
            if (parent in canonicalRecipeTypes) return true
            current = parent
        }
        return false
    }

    fun isMinecraftConnection(expression: Expression): Boolean {
        val connection = expression as? MethodCallExpr ?: return false
        if (connection.nameAsString != "getConnection" || connection.arguments.isNotEmpty()) return false
        val instance = connection.scope.orElse(null) as? MethodCallExpr ?: return false
        if (instance.nameAsString != "getInstance" || instance.arguments.isNotEmpty()) return false
        val owner = instance.scope.orElse(null) ?: return false
        return typing.isTypeName(owner.toString(), "net.minecraft.client.Minecraft")
    }

    fun isRecipeManagerExpression(receiver: Expression): Boolean {
        if (typing.typeOf(receiver.toString(), start(receiver)) ==
            "net.minecraft.world.item.crafting.RecipeManager"
        ) {
            return true
        }
        val accessor = receiver as? MethodCallExpr ?: return false
        if (accessor.nameAsString != "getRecipeManager" || accessor.arguments.isNotEmpty()) return false
        val owner = accessor.scope.orElse(null) ?: return false
        return typing.typeOf(owner.toString(), start(owner)) in managerAccessorOwners ||
            isMinecraftConnection(owner)
    }

    fun isRecipeManagerCall(call: MethodCallExpr): Boolean =
        call.scope.map(::isRecipeManagerExpression).orElse(false)

    fun unwrapped(expression: Expression): Expression {
        var current = expression
        while (true) current = (current as? EnclosedExpr)?.inner ?: break
        return current
    }

    fun directStatement(block: BlockStmt, node: Node): Statement? {
        var current: Node = node
        while (current.parentNode.orElse(null) !== block) {
            current = current.parentNode.orElse(null) ?: return null
        }
        return current as? Statement
    }

    fun exitsAbruptly(statement: Statement): Boolean = when (statement) {
        is ReturnStmt, is ThrowStmt -> true
        is BlockStmt -> statement.statements.lastOrNull()?.let(::exitsAbruptly) == true
        else -> false
    }

    fun isFlowScopedCampfire(receiver: Expression, use: Node): Boolean {
        val name = (unwrapped(receiver) as? NameExpr)?.nameAsString ?: return false
        val block = use.findAncestor(BlockStmt::class.java).orElse(null) ?: return false
        val useStatement = directStatement(block, use) ?: return false
        val useIndex = block.statements.indexOfFirst { it === useStatement }
        if (useIndex < 0) return false
        return block.statements.take(useIndex).filterIsInstance<IfStmt>().any { guard ->
            if (guard.elseStmt.isPresent || !exitsAbruptly(guard.thenStmt)) return@any false
            val negated = unwrapped(guard.condition) as? UnaryExpr ?: return@any false
            if (negated.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return@any false
            val test = unwrapped(negated.expression) as? InstanceOfExpr ?: return@any false
            val pattern = test.pattern.orElse(null) as? TypePatternExpr ?: return@any false
            pattern.nameAsString == name &&
                typing.isTypeName(
                    pattern.type.toString(),
                    "net.minecraft.world.level.block.entity.CampfireBlockEntity"
                )
        }
    }

    fun chainRoot(call: MethodCallExpr): Expression {
        var root: Expression = call
        while (true) {
            val parent = root.parentNode.orElse(null)
            root = when {
                parent is EnclosedExpr -> parent
                parent is MethodCallExpr && parent.scope.orElse(null) === root -> parent
                else -> return root
            }
        }
    }

    data class Boundary(
        val expression: Expression,
        val targetType: String,
        val method: MethodDeclaration?,
        val forEach: Boolean
    )

    fun boundary(root: Expression): Boundary? {
        val normalized = unwrapped(root)
        val variable = root.findAncestor(VariableDeclarator::class.java).orElse(null)
        if (variable != null &&
            variable.initializer.map { unwrapped(it) === normalized }.orElse(false)
        ) {
            return Boundary(root, variable.type.toString(), variable.findAncestor(MethodDeclaration::class.java).orElse(null), false)
        }
        val assignment = root.findAncestor(AssignExpr::class.java).orElse(null)
        if (assignment != null && unwrapped(assignment.value) === normalized) {
            val local = (assignment.target as? NameExpr)?.let { name ->
                assignment.findAncestor(MethodDeclaration::class.java).orElse(null)
                    ?.findAll(VariableDeclarator::class.java)
                    ?.filter {
                        it.nameAsString == name.nameAsString &&
                            start(it) < start(assignment) &&
                            it.findAncestor(BlockStmt::class.java).orElse(null)?.let { scope ->
                                start(assignment) in start(scope)..end(scope)
                            } == true
                    }
                    ?.maxByOrNull(::start)
                    ?.type
                    ?.toString()
            }
            val target = local
                ?: typing.typeOf(assignment.target.toString(), start(assignment.target))
                ?: return null
            return Boundary(root, target, assignment.findAncestor(MethodDeclaration::class.java).orElse(null), false)
        }
        val returned = root.findAncestor(ReturnStmt::class.java).orElse(null)
        if (returned != null && returned.expression.map { unwrapped(it) === normalized }.orElse(false)) {
            val method = returned.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
            return Boundary(root, method.type.toString(), method, false)
        }
        val loop = root.findAncestor(ForEachStmt::class.java).orElse(null)
        if (loop != null && unwrapped(loop.iterable) === normalized) {
            return Boundary(root, loop.variable.elementType.toString(), loop.findAncestor(MethodDeclaration::class.java).orElse(null), true)
        }
        return null
    }

    fun hasRecipeHolderValueMap(expression: Expression): Boolean {
        val from = start(expression)
        val to = end(expression)
        if (from < 0 || to < from) return false
        val text = JavaSourceText.maskCommentsAndLiterals(source.substring(from, to + 1))
        return Regex(
            """\.\s*map\s*\(\s*(?:net\.minecraft\.world\.item\.crafting\.)?RecipeHolder\s*::\s*value\s*\)"""
        ).containsMatchIn(text) ||
            Regex("""\.\s*map\s*\(\s*\w+\s*->\s*\w+\s*\.\s*value\s*\(\s*\)\s*\)""")
                .containsMatchIn(text)
    }

    fun getTypeInput(call: MethodCallExpr): MethodCallExpr? {
        val first = call.arguments.firstOrNull() as? MethodCallExpr ?: return null
        return first.takeIf { it.nameAsString == "getType" && it.arguments.isEmpty() }
    }

    fun recipeInputType(recipeType: String): String? =
        genericArgument(recipeType, setOf("Recipe"))

    val insertions = mutableListOf<Pair<Int, String>>()
    var needsRecipeHolder = false

    // A generic legacy helper returning Optional<R> needs an explicit method witness because its
    // own getType() is intentionally generic and otherwise loses R at the holder boundary.
    for (call in unit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "getRecipeFor" && it.arguments.size == 3 }
    ) {
        if (!isRecipeManagerCall(call) || getTypeInput(call) == null) continue
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: continue
        val returnRecipe = genericArgument(method.type.toString(), setOf("Optional")) ?: continue
        val recipeParameter = method.typeParameters.singleOrNull { it.nameAsString == stripVariance(returnRecipe) }
            ?: continue
        val recipeBound = recipeParameter.typeBound.singleOrNull()?.toString() ?: continue
        val inputName = genericArgument(recipeBound, setOf("Recipe"))?.trim() ?: continue
        val inputParameter = method.typeParameters.singleOrNull { it.nameAsString == inputName } ?: continue
        if (inputParameter.typeBound.none {
                typing.isTypeName(it.toString().substringBefore('<').trim(), "net.minecraft.world.item.crafting.RecipeInput")
            }
        ) {
            continue
        }
        val inputValue = call.arguments[1] as? NameExpr ?: continue
        if (inputValue.nameAsString !in method.parameters.filter { it.type.toString() == inputName }
                .map { it.nameAsString }
        ) {
            continue
        }
        val root = chainRoot(call)
        val target = boundary(root) ?: continue
        if (target.method !== method || target.forEach) continue
        val alreadyUnwrapped = hasRecipeHolderValueMap(root)
        if (!call.typeArguments.isPresent) {
            val nameStart = start(call.name)
            if (nameStart >= 0) {
                insertions += nameStart to "<$inputName, ${recipeParameter.nameAsString}>"
            }
        }
        if (!alreadyUnwrapped) {
            val rootEnd = end(root)
            if (rootEnd >= 0) {
                insertions += (rootEnd + 1) to ".map(RecipeHolder::value)"
                needsRecipeHolder = true
            }
        }
    }

    for (call in unit.findAll(MethodCallExpr::class.java)
        .filter {
            (it.nameAsString == "getRecipeFor" && it.arguments.size == 3 && isRecipeManagerCall(it)) ||
                (it.nameAsString == "getCookableRecipe" &&
                    it.arguments.size == 1 &&
                    it.scope.map { receiver ->
                        typing.typeOf(receiver.toString(), start(receiver)) ==
                            "net.minecraft.world.level.block.entity.CampfireBlockEntity" ||
                            isFlowScopedCampfire(receiver, it)
                    }.orElse(false))
        }
    ) {
        val root = chainRoot(call)
        val target = boundary(root) ?: continue
        val optionalValue = genericArgument(target.targetType, setOf("Optional")) ?: continue
        if (!isRecipeFamily(optionalValue, target.method) || hasRecipeHolderValueMap(root)) continue
        val rootEnd = end(root)
        if (rootEnd >= 0) {
            insertions += (rootEnd + 1) to ".map(RecipeHolder::value)"
            needsRecipeHolder = true
        }
    }

    for (call in unit.findAll(MethodCallExpr::class.java)
        .filter {
            (it.nameAsString == "getRecipesFor" && it.arguments.size == 3 && isRecipeManagerCall(it)) ||
                (it.nameAsString == "getRecipes" && it.arguments.isEmpty() && isRecipeManagerCall(it))
        }
    ) {
        val root = chainRoot(call)
        if (unwrapped(root) !== call) continue
        val target = boundary(root) ?: continue
        val recipeType = if (target.forEach) {
            target.targetType
        } else {
            genericArgument(target.targetType, setOf("List", "Collection", "Iterable")) ?: continue
        }
        if (!isRecipeFamily(recipeType, target.method)) continue
        if (call.nameAsString == "getRecipesFor" && !call.typeArguments.isPresent && getTypeInput(call) != null) {
            val inputType = recipeInputType(recipeType)
            val nameStart = start(call.name)
            if (inputType != null && nameStart >= 0) {
                insertions += nameStart to "<${stripVariance(inputType)}, ${stripVariance(recipeType)}>"
            }
        }
        val callEnd = end(call)
        if (callEnd >= 0) {
            insertions += (callEnd + 1) to ".stream().map(RecipeHolder::value).toList()"
            needsRecipeHolder = true
        }
    }

    if (insertions.isEmpty()) return source
    var migrated = source
    for ((offset, text) in insertions.sortedByDescending { it.first }.distinct()) {
        migrated = migrated.substring(0, offset) + text + migrated.substring(offset)
    }
    return if (needsRecipeHolder) {
        addExecutableImportIfMissing(migrated, "net.minecraft.world.item.crafting.RecipeHolder")
    } else {
        migrated
    }
}

private fun StructuralRefactorPass.migrateDataIngredientCollectionValues(source: String): String {
    if (!source.contains("DataIngredient") || !source.contains(".add(")) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun start(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    fun end(node: Node): Int =
        node.range.map { lineStarts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)

    val typing = SourceValueTypes(this, source)
    val dataIngredient = "com.tterrag.registrate.util.DataIngredient"

    fun isDataIngredient(expression: Expression): Boolean {
        val text = expression.toString().trim()
        if (text.endsWith(".toVanilla()")) return false
        val call = expression as? MethodCallExpr
        if (call != null && call.nameAsString in setOf("items", "stacks", "tag", "ingredient")) {
            val owner = call.scope.orElse(null)?.toString()?.trim()
            if (owner != null && typing.isTypeName(owner, dataIngredient)) {
                return true
            }
        }
        return typing.typeOf(text, start(expression)) == dataIngredient
    }

    fun localCollectionElement(scope: Expression): String? {
        val member = scope as? com.github.javaparser.ast.expr.FieldAccessExpr ?: return null
        val owner = typing.typeOf(member.scope.toString(), start(member.scope))?.substringAfterLast('.') ?: return null
        val declaration = unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .singleOrNull { it.nameAsString == owner } ?: return null
        val field = declaration.fields.flatMap { it.variables }
            .singleOrNull { it.nameAsString == member.nameAsString } ?: return null
        val type = field.type.toString()
        val open = type.indexOf('<')
        val close = type.lastIndexOf('>')
        if (open < 0 || close <= open) return null
        val container = type.substring(0, open).trim()
        if (!typing.isTypeName(container, "net.minecraft.core.NonNullList") &&
            !typing.isTypeName(container, "java.util.List") &&
            !typing.isTypeName(container, "java.util.Collection")
        ) {
            return null
        }
        return JavaSourceText.splitTopLevelArgs(type.substring(open + 1, close)).singleOrNull()
    }

    val insertions = mutableListOf<Int>()
    for (call in unit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "add" && it.arguments.size in 1..2 }
    ) {
        val scope = call.scope.orElse(null) ?: continue
        val element = typing.iterableElementType(scope.toString(), start(scope))
            ?: localCollectionElement(scope)
            ?: continue
        if (!typing.isTypeName(element.substringBefore('<').trim(), "net.minecraft.world.item.crafting.Ingredient")) {
            continue
        }
        val value = call.arguments.last()
        if (!isDataIngredient(value)) continue
        val valueEnd = end(value)
        if (valueEnd >= 0) insertions += valueEnd + 1
    }
    if (insertions.isEmpty()) return source
    var migrated = source
    for (offset in insertions.distinct().sortedDescending()) {
        migrated = migrated.substring(0, offset) + ".toVanilla()" + migrated.substring(offset)
    }
    return migrated
}

private fun StructuralRefactorPass.migrateRegistrateRecipeOutputs(source: String): String {
    if (!source.contains(".recipe(") || !source.contains("::accept") || !source.contains(".save(")) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun start(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    fun end(node: Node): Int =
        node.range.map { lineStarts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)

    val typing = SourceValueTypes(this, source)

    fun isRegistrateType(type: String): Boolean {
        if (type == "com.tterrag.registrate.AbstractRegistrate") return true
        val local = unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .singleOrNull { it.nameAsString == type.substringAfterLast('.') }
        if (local?.extendedTypes?.any {
                typing.isTypeName(it.nameWithScope, "com.tterrag.registrate.AbstractRegistrate")
            } == true
        ) {
            return true
        }
        var current = projectValueFacts.types.singleOrNull { it == type } ?: return false
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val parent = projectValueFacts.parents[current] ?: return false
            if (parent == "com.tterrag.registrate.AbstractRegistrate") return true
            current = parent
        }
        return false
    }

    fun isRegistrateBlockChain(expression: Expression, depth: Int = 0): Boolean {
        if (depth > 24) return false
        if (typing.typeOf(expression.toString(), start(expression)) ==
            "com.tterrag.registrate.builders.BlockBuilder"
        ) {
            return true
        }
        val call = expression as? MethodCallExpr ?: return false
        val scope = call.scope.orElse(null) ?: return false
        if (call.nameAsString == "block") {
            val ownerType = typing.typeOf(scope.toString(), start(scope)) ?: return false
            return isRegistrateType(ownerType)
        }
        return isRegistrateBlockChain(scope, depth + 1)
    }

    fun isShapedBuilderChain(expression: Expression, depth: Int = 0): Boolean {
        if (depth > 20) return false
        if (typing.typeOf(expression.toString(), start(expression)) ==
            "net.minecraft.data.recipes.ShapedRecipeBuilder"
        ) {
            return true
        }
        val call = expression as? MethodCallExpr ?: return false
        val scope = call.scope.orElse(null) ?: return false
        if (call.nameAsString == "shaped") {
            return typing.isTypeName(scope.toString(), "net.minecraft.data.recipes.ShapedRecipeBuilder")
        }
        return isShapedBuilderChain(scope, depth + 1)
    }

    val replacements = mutableListOf<Pair<IntRange, String>>()
    for (recipeCall in unit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "recipe" && it.arguments.size == 1 }
    ) {
        val receiver = recipeCall.scope.orElse(null) ?: continue
        if (!isRegistrateBlockChain(receiver)) continue
        val lambda = recipeCall.arguments.single() as? LambdaExpr ?: continue
        if (lambda.parameters.size != 2) continue
        val output = lambda.parameters[1]
        if (!output.type.isUnknownType &&
            !typing.isTypeName(output.type.toString(), "com.tterrag.registrate.providers.RegistrateRecipeProvider")
        ) {
            continue
        }
        for (save in lambda.findAll(MethodCallExpr::class.java)
            .filter { it.nameAsString == "save" && it.arguments.size == 1 }
        ) {
            if (save.findAncestor(LambdaExpr::class.java).orElse(null) !== lambda) continue
            val builder = save.scope.orElse(null) ?: continue
            if (!isShapedBuilderChain(builder)) continue
            val reference = save.arguments.single() as? MethodReferenceExpr ?: continue
            if (reference.identifier != "accept" ||
                reference.scope.toString().trim() != output.nameAsString
            ) {
                continue
            }
            val from = start(reference)
            val to = end(reference)
            if (from >= 0 && to >= from) replacements += (from..to) to output.nameAsString
        }
    }
    if (replacements.isEmpty()) return source
    return replacements.sortedByDescending { it.first.first }
        .fold(source) { text, (range, replacement) -> text.replaceRange(range, replacement) }
}
