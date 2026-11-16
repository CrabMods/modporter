package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.LiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.ReferenceType
import com.modporter.mapping.TargetMembers

/**
 * Recipe construction surfaces whose Forge 1.20.1 forms have no 1.21.1 overload. Every admission is
 * keyed on platform facts only: the 1.20.1 vanilla constructor signatures (javap of the 1.20.1 joined
 * jar), the 1.21.1 member table ([TargetMembers]), and the canonical owners the source itself
 * resolves. Project and library names never select a rewrite.
 *
 * - A `MultiItemValue` type test can never succeed on 1.21.1: Forge 1.20.1 only declared the class
 *   (neither vanilla nor Forge constructed it) and NeoForge 1.21.1 removed it.
 * - `GsonHelper.getAsString(tagValue.serialize(), "tag")` read the JSON 1.20.1 `TagValue#serialize`
 *   wrote from `tag.location()`; the 1.21.1 record exposes that tag directly.
 * - Recipe constructors lost their leading/positional `ResourceLocation` id. A subclass `super(...)`
 *   drops it (1.21.1 recipes carry no id), while a recipe created with an id and returned as a stream
 *   element keeps the id in the 1.21.1 carrier, `RecipeHolder`.
 * - The 1.20.1 whole-tag copy `if (src.hasTag()) dst.setTag(src.getTag().copy())`, already half
 *   rewritten to CUSTOM_DATA reads, is the component-patch copy vanilla uses in 1.21.1 when the
 *   destination is a freshly created stack.
 */
internal fun StructuralRefactorPass.migrateLegacyRecipeConstructionSurfaces(source: String): String {
    var result = migrateRemovedIngredientValueTests(source)
    result = migrateTagValueJsonRoundTrips(result)
    result = migrateReturnedRecipeIdHolders(result)
    result = migrateLegacyRecipeIdSuperArguments(result)
    result = migrateWholeStackDataCopies(result)
    return result
}

/** A parsed source with exact text offsets and splice edits that keep untouched text byte-identical. */
internal class RecipeSurfaceSource private constructor(
    val source: String,
    val unit: CompilationUnit,
    val typing: SourceValueTypes
) {
    private data class Edit(val from: Int, val to: Int, val text: String)

    private val lineStarts: List<Int> = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    private val edits = mutableListOf<Edit>()

    val newline: String = if (source.contains("\r\n")) "\r\n" else "\n"
    val changed: Boolean get() = edits.isNotEmpty()

    fun start(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)

    /** Exclusive end offset. */
    fun end(node: Node): Int =
        node.range.map { lineStarts[it.end.line - 1] + it.end.column }.orElse(-1)

    fun text(node: Node): String = source.substring(start(node), end(node))

    fun replace(node: Node, text: String) = replace(start(node), end(node), text)

    fun replace(from: Int, to: Int, text: String) {
        require(from in 0..to && to <= source.length)
        edits += Edit(from, to, text)
    }

    fun insert(at: Int, text: String) = replace(at, at, text)

    /** Removes [argument] from its argument list together with one adjacent separator. */
    fun removeArgument(arguments: List<Expression>, index: Int) {
        val argument = arguments[index]
        if (index < arguments.lastIndex) {
            replace(start(argument), start(arguments[index + 1]), "")
        } else if (index > 0) {
            replace(end(arguments[index - 1]), end(argument), "")
        } else {
            replace(argument, "")
        }
    }

    /** The statement's own lines when nothing else shares them, else the statement alone. */
    fun statementSpan(statement: Node): IntRange {
        val from = start(statement)
        val to = end(statement)
        val lineStart = source.lastIndexOf('\n', from - 1) + 1
        var cursor = to
        while (cursor < source.length && (source[cursor] == ' ' || source[cursor] == '\t')) cursor++
        if (cursor < source.length && source[cursor] == '\r') cursor++
        val ownsLine = source.substring(lineStart, from).isBlank() &&
            (cursor >= source.length || source[cursor] == '\n')
        return if (ownsLine) lineStart until minOf(source.length, cursor + 1) else from until to
    }

    fun indentOf(node: Node): String {
        val from = start(node)
        val lineStart = source.lastIndexOf('\n', from - 1) + 1
        return source.substring(lineStart, from).takeWhile { it == ' ' || it == '\t' }
    }

    /** Null when two edits overlap; the caller then keeps its source. */
    fun apply(): String? {
        var limit = Int.MAX_VALUE
        var result = source
        for (edit in edits.distinct().sortedWith(compareByDescending<Edit> { it.from }.thenByDescending { it.to })) {
            if (edit.to > limit) return null
            result = result.substring(0, edit.from) + edit.text + result.substring(edit.to)
            limit = edit.from
        }
        return result
    }

    companion object {
        fun parse(pass: StructuralRefactorPass, source: String): RecipeSurfaceSource? {
            val parsed = JavaParser(
                ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
            ).parse(source)
            val unit = parsed.result.orElse(null) ?: return null
            if (!parsed.isSuccessful) return null
            return RecipeSurfaceSource(source, unit, SourceValueTypes(pass, source))
        }
    }
}

internal const val SURFACE_RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
internal const val SURFACE_RECIPE = "net.minecraft.world.item.crafting.Recipe"
internal const val SURFACE_INGREDIENT = "net.minecraft.world.item.crafting.Ingredient"
private const val RECIPE_HOLDER_TYPE = "net.minecraft.world.item.crafting.RecipeHolder"
private const val ITEM_STACK_TYPE = "net.minecraft.world.item.ItemStack"
private const val TAG_VALUE_TYPE = "net.minecraft.world.item.crafting.Ingredient.TagValue"
private const val GSON_HELPER_TYPE = "net.minecraft.util.GsonHelper"
private const val DATA_COMPONENTS_TYPE = "net.minecraft.core.component.DataComponents"
private const val CUSTOM_DATA_TYPE = "net.minecraft.world.item.component.CustomData"

/** Pure value spellings that may be evaluated a different number of times (or not at all). */
internal fun isPureSurfaceValue(expression: Expression): Boolean = when (expression) {
    is NameExpr, is LiteralExpr, is ThisExpr -> true
    is EnclosedExpr -> isPureSurfaceValue(expression.inner)
    is FieldAccessExpr -> isPureSurfaceValue(expression.scope) || expression.scope.toString().first().isUpperCase()
    is BinaryExpr -> expression.operator == BinaryExpr.Operator.PLUS &&
        isPureSurfaceValue(expression.left) && isPureSurfaceValue(expression.right)
    else -> false
}

internal fun surfaceRawTypeName(type: ReferenceType): String =
    (type as? ClassOrInterfaceType)?.nameWithScope ?: type.toString().substringBefore('<')

internal fun surfaceUnwrapped(expression: Expression): Expression {
    var current = expression
    while (current is EnclosedExpr) current = current.inner
    return current
}

/** The nearest enclosing method, constructor, or lambda; values bound there are the ones a test sees. */
internal fun surfaceEnclosingCallable(node: Node): Node? {
    var current = node.parentNode.orElse(null)
    while (current != null) {
        if (current is CallableDeclaration<*> || current is LambdaExpr) return current
        current = current.parentNode.orElse(null)
    }
    return null
}

internal fun surfaceReassigned(scope: Node, name: String): Boolean =
    scope.findAll(AssignExpr::class.java).any { (it.target as? NameExpr)?.nameAsString == name } ||
        scope.findAll(UnaryExpr::class.java).any { unary ->
            unary.operator in setOf(
                UnaryExpr.Operator.PREFIX_INCREMENT,
                UnaryExpr.Operator.PREFIX_DECREMENT,
                UnaryExpr.Operator.POSTFIX_INCREMENT,
                UnaryExpr.Operator.POSTFIX_DECREMENT
            ) && (unary.expression as? NameExpr)?.nameAsString == name
        }

/**
 * Forge 1.20.1 declared `MultiItemValue` without ever constructing it; NeoForge 1.21.1 has no such
 * type, so no 1.21.1 ingredient value can be one. A statement guarded only by that test never runs:
 * it is removed (or replaced by its else branch). Any other reference keeps the source unchanged.
 */
private fun StructuralRefactorPass.migrateRemovedIngredientValueTests(source: String): String {
    if (!source.contains("MultiItemValue")) return source
    val removedTypes = listOf(
        "net.minecraftforge.common.crafting.MultiItemValue",
        "net.neoforged.neoforge.common.crafting.MultiItemValue"
    ).filter { !TargetMembers.hasClass(it) }
    if (removedTypes.isEmpty()) return source
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    val typing = surface.typing
    val tests = surface.unit.findAll(InstanceOfExpr::class.java).filter { test ->
        removedTypes.any { typing.isTypeName(surfaceRawTypeName(test.type), it) }
    }
    if (tests.isEmpty()) return source
    val code = maskJavaCommentsAndLiterals(source)
    val references = Regex("""(?<![\w$])MultiItemValue(?![\w$])""").findAll(code).count()
    val importReferences = Regex("""(?m)^[ \t]*import\s+[\w.]+\.MultiItemValue\s*;""").findAll(code).count()
    if (references != importReferences + tests.size) return source

    for (test in tests) {
        if (test.pattern.isPresent || !isPureSurfaceValue(test.expression)) return source
        var condition: Node = test
        while (condition.parentNode.orElse(null) is EnclosedExpr) condition = condition.parentNode.get()
        val statement = condition.parentNode.orElse(null) as? IfStmt ?: return source
        if (statement.condition !== condition || statement.parentNode.orElse(null) !is BlockStmt) return source
        val otherwise = statement.elseStmt.orElse(null)
        if (otherwise == null) {
            val span = surface.statementSpan(statement)
            surface.replace(span.first, span.last + 1, "")
        } else {
            surface.replace(statement, surface.text(otherwise))
        }
    }
    var result = surface.apply() ?: return source
    removedTypes.forEach { result = removeExecutableImport(result, it) }
    return result
}

/**
 * 1.20.1 `TagValue#serialize()` wrote `{"tag": tag.location().toString()}`; reading that key back is
 * the 1.21.1 record accessor `tag().location()` on a value the source proves is a `TagValue`.
 */
private fun StructuralRefactorPass.migrateTagValueJsonRoundTrips(source: String): String {
    if (!source.contains("TagValue") || !source.contains("serialize()") || !source.contains("getAsString")) {
        return source
    }
    if (!TargetMembers.hasMethod("net.minecraft.world.item.crafting.Ingredient\$TagValue", "tag") ||
        TargetMembers.hasMethod("net.minecraft.world.item.crafting.Ingredient\$TagValue", "serialize")
    ) {
        return source
    }
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    val typing = surface.typing

    fun tagView(value: NameExpr): String? {
        val name = value.nameAsString
        if (typing.typeOf(name, surface.start(value)) == TAG_VALUE_TYPE) return name
        val callable = surfaceEnclosingCallable(value) ?: return null
        if (surfaceReassigned(callable, name)) return null
        var child: Node = value
        var parent = value.parentNode.orElse(null)
        while (parent != null && parent !== callable) {
            if (parent is IfStmt && parent.thenStmt === child) {
                val test = surfaceUnwrapped(parent.condition) as? InstanceOfExpr
                if (test != null &&
                    (test.expression as? NameExpr)?.nameAsString == name &&
                    typing.isTypeName(surfaceRawTypeName(test.type), TAG_VALUE_TYPE)
                ) {
                    val pattern = test.pattern.orElse(null) as? TypePatternExpr
                    return pattern?.nameAsString ?: "((${test.type}) $name)"
                }
            }
            child = parent
            parent = parent.parentNode.orElse(null)
        }
        return null
    }

    for (call in surface.unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != "getAsString" || call.arguments.size != 2) continue
        if (!call.scope.map { typing.isTypeName(it.toString(), GSON_HELPER_TYPE) }.orElse(false)) continue
        if ((call.arguments[1] as? StringLiteralExpr)?.value != "tag") continue
        val serialize = call.arguments[0] as? MethodCallExpr ?: continue
        if (serialize.nameAsString != "serialize" || serialize.arguments.isNotEmpty()) continue
        val value = serialize.scope.orElse(null) as? NameExpr ?: continue
        val view = tagView(value) ?: continue
        val parent = call.parentNode.orElse(null)
        val parsesLocation = when (parent) {
            is MethodCallExpr -> parent.nameAsString == "parse" &&
                parent.arguments.singleOrNull() === call &&
                parent.scope.map { typing.isTypeName(it.toString(), SURFACE_RESOURCE_LOCATION) }.orElse(false)
            is ObjectCreationExpr -> parent.arguments.singleOrNull() === call &&
                parent.anonymousClassBody.isEmpty &&
                typing.isTypeName(parent.type.nameWithScope, SURFACE_RESOURCE_LOCATION)
            else -> false
        }
        if (parsesLocation) {
            surface.replace(parent!!, "$view.tag().location()")
        } else {
            surface.replace(call, "$view.tag().location().toString()")
        }
    }
    if (!surface.changed) return source
    val result = surface.apply() ?: return source
    return removeUnusedSimpleImports(result, listOf(GSON_HELPER_TYPE))
}

/** A 1.20.1 vanilla recipe constructor whose parameter [idIndex] was the recipe id (javap, 1.20.1 joined jar). */
internal data class LegacyRecipeIdConstructor(val owner: String, val parameters: List<String>, val idIndex: Int) {
    val legacyArity: Int get() = parameters.size

    /** The 1.21.1 table must declare exactly the legacy signature without the id, and not the legacy one. */
    fun provenOnTarget(): Boolean {
        val target = TargetMembers.methodDescriptors(owner, "<init>")
        val legacy = parameters.joinToString("", "(", ")V")
        val modern = parameters.filterIndexed { index, _ -> index != idIndex }.joinToString("", "(", ")V")
        return parameters[idIndex] == "L${SURFACE_RESOURCE_LOCATION.replace('.', '/')};" &&
            modern in target && legacy !in target
    }

    companion object {
        private const val RL = "Lnet/minecraft/resources/ResourceLocation;"
        private const val STRING = "Ljava/lang/String;"
        private const val INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;"
        private const val STACK = "Lnet/minecraft/world/item/ItemStack;"
        private const val CRAFTING = "Lnet/minecraft/world/item/crafting/CraftingBookCategory;"
        private const val COOKING = "Lnet/minecraft/world/item/crafting/CookingBookCategory;"
        private const val TYPE = "Lnet/minecraft/world/item/crafting/RecipeType;"
        private const val SERIALIZER = "Lnet/minecraft/world/item/crafting/RecipeSerializer;"
        private const val LIST = "Lnet/minecraft/core/NonNullList;"
        private const val CRAFTING_PACKAGE = "net.minecraft.world.item.crafting."
        private val cooking = listOf(RL, STRING, COOKING, INGREDIENT, STACK, "F", "I")

        val all: List<LegacyRecipeIdConstructor> = listOf(
            LegacyRecipeIdConstructor("${CRAFTING_PACKAGE}CustomRecipe", listOf(RL, CRAFTING), 0),
            LegacyRecipeIdConstructor("${CRAFTING_PACKAGE}StonecutterRecipe", listOf(RL, STRING, INGREDIENT, STACK), 0),
            LegacyRecipeIdConstructor("${CRAFTING_PACKAGE}ShapelessRecipe", listOf(RL, STRING, CRAFTING, STACK, LIST), 0),
            LegacyRecipeIdConstructor(
                "${CRAFTING_PACKAGE}SingleItemRecipe",
                listOf(TYPE, SERIALIZER, RL, STRING, INGREDIENT, STACK),
                2
            ),
            LegacyRecipeIdConstructor(
                "${CRAFTING_PACKAGE}AbstractCookingRecipe",
                listOf(TYPE, RL, STRING, COOKING, INGREDIENT, STACK, "F", "I"),
                1
            ),
            LegacyRecipeIdConstructor("${CRAFTING_PACKAGE}SmeltingRecipe", cooking, 0),
            LegacyRecipeIdConstructor("${CRAFTING_PACKAGE}BlastingRecipe", cooking, 0),
            LegacyRecipeIdConstructor("${CRAFTING_PACKAGE}SmokingRecipe", cooking, 0),
            LegacyRecipeIdConstructor("${CRAFTING_PACKAGE}CampfireCookingRecipe", cooking, 0),
            LegacyRecipeIdConstructor(
                "${CRAFTING_PACKAGE}SmithingTransformRecipe",
                listOf(RL, INGREDIENT, INGREDIENT, INGREDIENT, STACK),
                0
            ),
            LegacyRecipeIdConstructor(
                "${CRAFTING_PACKAGE}SmithingTrimRecipe",
                listOf(RL, INGREDIENT, INGREDIENT, INGREDIENT),
                0
            )
        )

        fun resolve(typing: SourceValueTypes, typeName: String): LegacyRecipeIdConstructor? =
            all.firstOrNull { typing.isTypeName(typeName, it.owner) }?.takeIf { it.provenOnTarget() }
    }
}

private val resourceLocationFactories = setOf(
    "parse", "tryParse", "fromNamespaceAndPath", "tryBuild", "withDefaultNamespace"
)

/** A recipe-id argument that evaluates without side effects, so dropping it keeps behavior. */
private fun RecipeSurfaceSource.isDroppableRecipeId(id: Expression): Boolean {
    val value = surfaceUnwrapped(id)
    if (value is MethodCallExpr) {
        return value.nameAsString in resourceLocationFactories &&
            value.scope.map { typing.isTypeName(it.toString(), SURFACE_RESOURCE_LOCATION) }.orElse(false) &&
            value.arguments.all(::isPureSurfaceValue)
    }
    if (value is ObjectCreationExpr) {
        return value.anonymousClassBody.isEmpty &&
            typing.isTypeName(value.type.nameWithScope, SURFACE_RESOURCE_LOCATION) &&
            value.arguments.all(::isPureSurfaceValue)
    }
    return isPureSurfaceValue(value) &&
        typing.typeOf(value.toString(), start(value)) == SURFACE_RESOURCE_LOCATION
}

/** Names no declaration in this compilation unit binds. */
private fun RecipeSurfaceSource.isUnboundName(name: String): Boolean =
    unit.findAll(Parameter::class.java).none { it.nameAsString == name } &&
        unit.findAll(VariableDeclarator::class.java).none { it.nameAsString == name } &&
        unit.findAll(TypePatternExpr::class.java).none { it.nameAsString == name } &&
        !Regex("""(?m)^\s*import\s+static\s+[\w.]+\.${Regex.escape(name)}\s*;""").containsMatchIn(source)

private fun platformHierarchyDeclaresField(owner: String, name: String): Boolean {
    val seen = HashSet<String>()
    val frontier = ArrayDeque(listOf(owner))
    while (frontier.isNotEmpty()) {
        val current = frontier.removeFirst()
        if (!seen.add(current)) continue
        if (TargetMembers.hasField(current, name)) return true
        frontier.addAll(TargetMembers.supertypesOf(current))
    }
    return false
}

/**
 * A `super(...)` call with the legacy arity of a vanilla recipe constructor passes the 1.20.1 recipe
 * id in the position the 1.20.1 signature reserves for it. 1.21.1 recipes carry no id (the loaded
 * RecipeHolder does), so the argument goes. A dangling simple name in that position is the id
 * parameter an earlier signature migration already removed.
 */
private fun StructuralRefactorPass.migrateLegacyRecipeIdSuperArguments(source: String): String {
    if (!source.contains("super(")) return source
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    val typing = surface.typing
    for (owner in surface.unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
        val parent = owner.extendedTypes.singleOrNull() ?: continue
        val constructor = LegacyRecipeIdConstructor.resolve(typing, parent.nameWithScope) ?: continue
        val modern = constructor.parameters.filterIndexed { index, _ -> index != constructor.idIndex }
        for (declaration in owner.constructors) {
            val invocation = declaration.body.statements.firstOrNull() as? ExplicitConstructorInvocationStmt ?: continue
            if (invocation.isThis || invocation.arguments.size != constructor.legacyArity) continue
            val id = invocation.arguments[constructor.idIndex]
            val droppable = surface.isDroppableRecipeId(id) ||
                (id is NameExpr &&
                    surface.isUnboundName(id.nameAsString) &&
                    !platformHierarchyDeclaresField(constructor.owner, id.nameAsString))
            if (!droppable) continue
            val consistent = invocation.arguments.withIndex()
                .filter { it.index != constructor.idIndex }
                .zip(modern)
                .all { (argument, descriptor) ->
                    val resolved = surface.typing.typeOf(argument.value.toString(), surface.start(argument.value))
                    val expected = descriptor.takeIf { it.startsWith("L") }
                        ?.removePrefix("L")?.removeSuffix(";")?.replace('/', '.')
                    resolved == null || expected == null || resolved == expected ||
                        !TargetMembers.hasClass(resolved) || TargetMembers.inheritsFrom(resolved, expected)
                }
            if (!consistent) continue
            surface.removeArgument(invocation.arguments, constructor.idIndex)
        }
    }
    if (!surface.changed) return source
    val result = surface.apply() ?: return source
    return removeUnusedSimpleImports(result, listOf(SURFACE_RESOURCE_LOCATION))
}

private val recipeValueTypes = listOf(
    SURFACE_RECIPE,
    "net.minecraft.world.item.crafting.CraftingRecipe",
    "net.minecraft.world.item.crafting.SingleItemRecipe",
    "net.minecraft.world.item.crafting.AbstractCookingRecipe",
    "net.minecraft.world.item.crafting.ShapedRecipe",
    "net.minecraft.world.item.crafting.ShapelessRecipe",
    "net.minecraft.world.item.crafting.StonecutterRecipe",
    "net.minecraft.world.item.crafting.CustomRecipe",
    "net.minecraft.world.item.crafting.SmeltingRecipe",
    "net.minecraft.world.item.crafting.BlastingRecipe",
    "net.minecraft.world.item.crafting.SmokingRecipe",
    "net.minecraft.world.item.crafting.CampfireCookingRecipe",
    "net.minecraft.world.item.crafting.SmithingRecipe",
    "net.minecraft.world.item.crafting.SmithingTransformRecipe",
    "net.minecraft.world.item.crafting.SmithingTrimRecipe"
)

/**
 * A method returning `Stream<R>` whose elements are vanilla recipes constructed with an explicit
 * 1.20.1 id: the id was part of each element value (Recipe#getId). In 1.21.1 only RecipeHolder
 * carries a recipe id, so the elements become `RecipeHolder<R>` with the very same id expression.
 */
private fun StructuralRefactorPass.migrateReturnedRecipeIdHolders(source: String): String {
    if (!source.contains("Stream<") || !source.contains("new ") || !source.contains(".map(")) return source
    val holderConstructor = "(L${SURFACE_RESOURCE_LOCATION.replace('.', '/')};L${SURFACE_RECIPE.replace('.', '/')};)V"
    if (holderConstructor !in TargetMembers.methodDescriptors(RECIPE_HOLDER_TYPE, "<init>")) return source
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    val typing = surface.typing

    data class Creation(val creation: ObjectCreationExpr, val constructor: LegacyRecipeIdConstructor)

    for (method in surface.unit.findAll(MethodDeclaration::class.java)) {
        val stream = method.type as? ClassOrInterfaceType ?: continue
        if (!typing.isTypeName(stream.nameWithScope, "java.util.stream.Stream")) continue
        val element = stream.typeArguments.orElse(null)?.singleOrNull() as? ClassOrInterfaceType ?: continue
        val elementOwner = recipeValueTypes.firstOrNull { typing.isTypeName(element.nameWithScope, it) } ?: continue
        val body = method.body.orElse(null) ?: continue
        val returned = body.findAll(ReturnStmt::class.java).filter { surfaceEnclosingCallable(it) === method }
        val map = returned.singleOrNull()?.expression?.orElse(null) as? MethodCallExpr ?: continue
        if (map.nameAsString != "map" || map.arguments.size != 1 || map.scope.isEmpty) continue
        val lambda = map.arguments.single() as? LambdaExpr ?: continue
        if (lambda.parameters.size != 1) continue
        val results: List<Expression> = when (val lambdaBody = lambda.body) {
            is ExpressionStmt -> listOf(lambdaBody.expression)
            is BlockStmt -> lambdaBody.findAll(ReturnStmt::class.java)
                .filter { surfaceEnclosingCallable(it) === lambda }
                .map { it.expression.orElse(null) ?: return source }
            else -> continue
        }
        if (results.isEmpty()) continue
        val creations = results.map { value ->
            val creation = value as? ObjectCreationExpr ?: return@map null
            if (creation.anonymousClassBody.isPresent) return@map null
            val constructor = LegacyRecipeIdConstructor.resolve(typing, creation.type.nameWithScope)
                ?: return@map null
            if (creation.arguments.size != constructor.legacyArity) return@map null
            if (constructor.owner != elementOwner && !TargetMembers.inheritsFrom(constructor.owner, elementOwner)) {
                return@map null
            }
            val id = creation.arguments[constructor.idIndex]
            if (typing.typeOf(id.toString(), surface.start(id)) != SURFACE_RESOURCE_LOCATION &&
                !surface.isDroppableRecipeId(id)
            ) {
                return@map null
            }
            Creation(creation, constructor)
        }
        if (creations.any { it == null }) continue

        surface.replace(element, "RecipeHolder<${surface.text(element)}>")
        for (planned in creations.filterNotNull()) {
            val id = planned.creation.arguments[planned.constructor.idIndex]
            surface.insert(surface.start(planned.creation), "new RecipeHolder<>(${surface.text(id)}, ")
            surface.removeArgument(planned.creation.arguments, planned.constructor.idIndex)
            surface.insert(surface.end(planned.creation), ")")
        }
    }
    if (!surface.changed) return source
    val result = surface.apply() ?: return source
    return addExecutableImportIfMissing(result, RECIPE_HOLDER_TYPE)
}

private val registrateFreshStackOwners = setOf(
    // Registrate ItemProviderEntry#asStack()/asStack(int) return `new ItemStack(get()[, count])`.
    "com.tterrag.registrate.util.entry.ItemEntry",
    "com.tterrag.registrate.util.entry.BlockEntry",
    "com.tterrag.registrate.util.entry.ItemProviderEntry"
)

/**
 * 1.20.1 `if (src.hasTag()) dst.setTag(src.getTag().copy())` replaced the destination's whole tag. An
 * earlier text rule leaves `if (src.has(CUSTOM_DATA)) dst.setTag(src.getOrDefault(CUSTOM_DATA,
 * CustomData.EMPTY).copyTag().copy())`, and `ItemStack#setTag` no longer exists. For a destination that
 * is a freshly created, untouched local stack, the 1.21.1 equivalent (vanilla ShulkerBoxColoring) is
 * the component-patch copy: an empty patch merged with the source patch equals replacing it.
 */
private fun StructuralRefactorPass.migrateWholeStackDataCopies(source: String): String {
    if (!source.contains("setTag(") || !source.contains("CUSTOM_DATA")) return source
    if (TargetMembers.hasMethod(ITEM_STACK_TYPE, "setTag") ||
        "(Lnet/minecraft/core/component/DataComponentPatch;)V" !in
        TargetMembers.methodDescriptors(ITEM_STACK_TYPE, "applyComponents") ||
        !TargetMembers.hasMethod(ITEM_STACK_TYPE, "getComponentsPatch") ||
        !TargetMembers.hasMethod(ITEM_STACK_TYPE, "isComponentsPatchEmpty")
    ) {
        return source
    }
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    val typing = surface.typing

    fun isStack(name: NameExpr): Boolean = typing.typeOf(name.nameAsString, surface.start(name)) == ITEM_STACK_TYPE
    fun isCustomData(expression: Expression): Boolean {
        val field = expression as? FieldAccessExpr ?: return false
        return field.nameAsString == "CUSTOM_DATA" && typing.isTypeName(field.scope.toString(), DATA_COMPONENTS_TYPE)
    }
    fun isFreshStack(initializer: Expression): Boolean = when (val value = surfaceUnwrapped(initializer)) {
        is ObjectCreationExpr -> value.anonymousClassBody.isEmpty &&
            value.arguments.size in 1..2 &&
            typing.isTypeName(value.type.nameWithScope, ITEM_STACK_TYPE)
        is MethodCallExpr -> value.nameAsString == "asStack" &&
            value.arguments.size <= 1 &&
            value.scope.map { scope ->
                typing.typeOf(scope.toString(), surface.start(scope)) in registrateFreshStackOwners
            }.orElse(false)
        else -> false
    }

    for (statement in surface.unit.findAll(IfStmt::class.java)) {
        if (statement.elseStmt.isPresent) continue
        val has = surfaceUnwrapped(statement.condition) as? MethodCallExpr ?: continue
        if (has.nameAsString != "has" || has.arguments.size != 1 || !isCustomData(has.arguments[0])) continue
        val sourceStack = has.scope.orElse(null) as? NameExpr ?: continue
        if (!isStack(sourceStack)) continue
        val write = when (val then = statement.thenStmt) {
            is ExpressionStmt -> then
            is BlockStmt -> then.statements.singleOrNull() as? ExpressionStmt
            else -> null
        } ?: continue
        val setTag = write.expression as? MethodCallExpr ?: continue
        if (setTag.nameAsString != "setTag" || setTag.arguments.size != 1) continue
        val target = setTag.scope.orElse(null) as? NameExpr ?: continue
        if (target.nameAsString == sourceStack.nameAsString || !isStack(target)) continue
        val copy = setTag.arguments.single() as? MethodCallExpr ?: continue
        val copyTag = copy.scope.orElse(null) as? MethodCallExpr ?: continue
        val read = copyTag.scope.orElse(null) as? MethodCallExpr ?: continue
        if (copy.nameAsString != "copy" || copy.arguments.isNotEmpty() ||
            copyTag.nameAsString != "copyTag" || copyTag.arguments.isNotEmpty() ||
            read.nameAsString != "getOrDefault" || read.arguments.size != 2 ||
            (read.scope.orElse(null) as? NameExpr)?.nameAsString != sourceStack.nameAsString ||
            !isCustomData(read.arguments[0])
        ) {
            continue
        }
        val empty = read.arguments[1] as? FieldAccessExpr ?: continue
        if (empty.nameAsString != "EMPTY" || !typing.isTypeName(empty.scope.toString(), CUSTOM_DATA_TYPE)) continue

        // the destination is a fresh local of the same block, untouched until this statement
        val block = statement.parentNode.orElse(null) as? BlockStmt ?: continue
        val ifIndex = block.statements.indexOfFirst { it === statement }
        val declarationIndex = block.statements.indexOfFirst { candidate ->
            (candidate as? ExpressionStmt)?.expression?.let { expression ->
                expression.isVariableDeclarationExpr &&
                    expression.asVariableDeclarationExpr().variables.any { it.nameAsString == target.nameAsString }
            } == true
        }
        if (declarationIndex < 0 || declarationIndex >= ifIndex) continue
        val declarator = (block.statements[declarationIndex] as ExpressionStmt).expression
            .asVariableDeclarationExpr().variables.single { it.nameAsString == target.nameAsString }
        if (!declarator.initializer.map(::isFreshStack).orElse(false)) continue
        val between: List<Statement> = block.statements.subList(declarationIndex + 1, ifIndex)
        if (between.any { node ->
                node.findAll(NameExpr::class.java).any { it.nameAsString == target.nameAsString }
            }
        ) {
            continue
        }
        surface.replace(statement.condition, "!${sourceStack.nameAsString}.isComponentsPatchEmpty()")
        surface.replace(setTag, "${target.nameAsString}.applyComponents(${sourceStack.nameAsString}.getComponentsPatch())")
    }
    if (!surface.changed) return source
    val result = surface.apply() ?: return source
    return removeUnusedSimpleImports(result, listOf(DATA_COMPONENTS_TYPE, CUSTOM_DATA_TYPE))
}
