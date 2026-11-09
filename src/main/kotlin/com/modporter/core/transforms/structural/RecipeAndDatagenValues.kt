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
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.modporter.core.transforms.util.JavaSourceText

/**
 * RecipeManager#getAllRecipesFor returns holders in 1.21. Restore a legacy recipe-valued target
 * only when both sides are proven: the receiver is the platform RecipeManager (or one of its
 * platform accessors), and the target is a real recipe family. Holder-valued filters such as
 * `holder.id()` are left intact for the later single-holder migration.
 */
internal fun StructuralRefactorPass.migrateRecipeHolderStreamValues(source: String): String {
    if (!source.contains("getAllRecipesFor(") && !source.contains("getRecipes()")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun start(node: Node): Int = node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    fun end(node: Node): Int = node.range.map { lineStarts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)

    val typing = SourceValueTypes(this, source)
    val managerAccessorOwners = setOf(
        "net.minecraft.world.level.Level",
        "net.minecraft.server.level.ServerLevel",
        "net.minecraft.client.multiplayer.ClientLevel",
        "net.minecraft.server.MinecraftServer",
        "net.minecraft.client.multiplayer.ClientPacketListener"
    )
    val recipeTypes = setOf(
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

    fun isMinecraftConnection(expression: Expression): Boolean {
        val connection = expression as? MethodCallExpr ?: return false
        if (connection.nameAsString != "getConnection" || connection.arguments.isNotEmpty()) return false
        val instance = connection.scope.orElse(null) as? MethodCallExpr ?: return false
        if (instance.nameAsString != "getInstance" || instance.arguments.isNotEmpty()) return false
        val owner = instance.scope.orElse(null) ?: return false
        return typing.isTypeName(owner.toString(), "net.minecraft.client.Minecraft")
    }

    fun isRecipeManagerExpression(receiver: Expression): Boolean {
        if (typing.typeOf(receiver.toString(), start(receiver)) == "net.minecraft.world.item.crafting.RecipeManager") {
            return true
        }
        val accessor = receiver as? MethodCallExpr ?: return false
        if (accessor.nameAsString != "getRecipeManager" || accessor.arguments.isNotEmpty()) return false
        val owner = accessor.scope.orElse(null) ?: return false
        return typing.typeOf(owner.toString(), start(owner)) in managerAccessorOwners ||
            isMinecraftConnection(owner)
    }

    fun isRecipeManagerReceiver(call: MethodCallExpr): Boolean {
        val receiver = call.scope.orElse(null) ?: return false
        return isRecipeManagerExpression(receiver)
    }

    fun targetType(call: MethodCallExpr): String? {
        val declaration = call.findAncestor(VariableDeclarator::class.java).orElse(null)
        if (declaration != null && declaration.initializer.map { it.isAncestorOf(call) || it === call }.orElse(false)) {
            return declaration.type.toString()
        }
        val assignment = call.findAncestor(AssignExpr::class.java).orElse(null)
        if (assignment != null && (assignment.value === call || assignment.value.isAncestorOf(call))) {
            return typing.typeOf(assignment.target.toString(), start(assignment.target))
        }
        return null
    }

    fun unwrapValueType(type: String): String {
        var value = type.trim().replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "")
        value = value.removePrefix("? extends ").removePrefix("? super ").trim()
        while (true) {
            val open = value.indexOf('<')
            val close = value.lastIndexOf('>')
            if (open < 0 || close <= open) return value
            val owner = value.substring(0, open).trim().substringAfterLast('.')
            if (owner !in setOf("List", "Collection", "Iterable", "Optional", "Stream")) return value
            val arguments = JavaSourceText.splitTopLevelArgs(value.substring(open + 1, close))
            if (arguments.size != 1) return value
            value = arguments.single().trim().removePrefix("? extends ").removePrefix("? super ").trim()
        }
    }

    fun canonical(type: String): String {
        val raw = type.substringBefore('<').trim()
        if (raw.contains('.')) return raw
        return javaNonStaticImports(source)[raw] ?: raw
    }

    fun isRecipeFamily(type: String, call: MethodCallExpr): Boolean {
        val value = unwrapValueType(type)
        val raw = value.substringBefore('<').trim()
        if (recipeTypes.any { typing.isTypeName(raw, it) }) return true

        fun sameType(first: String, second: String): Boolean {
            val left = first.substringBefore('<').trim()
            val right = second.substringBefore('<').trim()
            if (left.replace(" ", "") == right.replace(" ", "")) return true
            return (recipeTypes + projectValueFacts.types).any {
                typing.isTypeName(left, it) && typing.isTypeName(right, it)
            }
        }
        if (call.typeArguments.map { arguments ->
                arguments.size == 2 && sameType(arguments.last().asString(), raw)
            }.orElse(false)
        ) return true

        val simple = raw.substringAfterLast('.')
        val ownDeclaration = unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .singleOrNull { it.nameAsString == simple }
        if (ownDeclaration?.implementedTypes?.any {
                typing.isTypeName(it.nameWithScope, "net.minecraft.world.item.crafting.Recipe")
            } == true
        ) return true

        var current = projectValueFacts.types.singleOrNull { typing.isTypeName(raw, it) }
            ?: canonical(value)
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val parent = projectValueFacts.parents[current] ?: return false
            if (parent == "net.minecraft.world.item.crafting.Recipe" ||
                parent == "net.minecraft.world.item.crafting.CraftingRecipe" ||
                parent == "net.minecraft.world.item.crafting.AbstractCookingRecipe"
            ) return true
            current = parent
        }
        return false
    }

    fun directInitializer(call: MethodCallExpr): Boolean {
        var expression: Expression = call
        while (expression.parentNode.orElse(null) is EnclosedExpr) {
            expression = expression.parentNode.get() as EnclosedExpr
        }
        val parent = expression.parentNode.orElse(null)
        return (parent is VariableDeclarator && parent.initializer.orElse(null) === expression) ||
            (parent is AssignExpr && parent.value === expression)
    }

    fun collectionTarget(type: String): Boolean =
        type.substringBefore('<').trim().substringAfterLast('.') in setOf("List", "Collection", "Iterable")

    fun streamChild(call: MethodCallExpr): MethodCallExpr? {
        val parent = call.parentNode.orElse(null) as? MethodCallExpr ?: return null
        return parent.takeIf {
            it.scope.orElse(null) === call &&
                it.nameAsString in setOf("stream", "parallelStream") &&
                it.arguments.isEmpty()
        }
    }

    fun chainRoot(expression: MethodCallExpr): MethodCallExpr {
        var root = expression
        while (true) {
            val parent = root.parentNode.orElse(null) as? MethodCallExpr ?: return root
            if (parent.scope.orElse(null) !== root) return root
            root = parent
        }
    }

    fun defersToHolderIdFilter(call: MethodCallExpr): Boolean {
        val declaration = call.findAncestor(VariableDeclarator::class.java).orElse(null) ?: return false
        if (!declaration.initializer.map { it === call || it.isAncestorOf(call) }.orElse(false)) return false
        val method = declaration.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return false
        val name = declaration.nameAsString
        return method.findAll(AssignExpr::class.java).any { assignment ->
            if (start(assignment) <= start(declaration) || assignment.target.toString().trim() != name) return@any false
            val value = JavaSourceText.maskCommentsAndLiterals(assignment.value.toString())
            Regex("""\b${Regex.escape(name)}\s*\.\s*(?:stream|parallelStream)\s*\(""").containsMatchIn(value) &&
                Regex("""\.\s*(?:getId|id)\s*\(""").containsMatchIn(value)
        }
    }

    fun consumerValueType(forEach: MethodCallExpr): String? {
        val argument = forEach.arguments.singleOrNull()?.toString()?.trim() ?: return null
        if (!Regex("""[A-Za-z_$][\w$]*""").matches(argument)) return null
        val method = forEach.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return null
        val parameter = method.parameters.firstOrNull { it.nameAsString == argument }
        fun localScope(variable: VariableDeclarator): Node? {
            var parent = variable.parentNode.orElse(null)
            while (parent != null && parent !== method) {
                if (parent is BlockStmt || parent is ForStmt || parent is ForEachStmt || parent is CatchClause) {
                    return parent
                }
                parent = parent.parentNode.orElse(null)
            }
            return null
        }
        val local = method.findAll(VariableDeclarator::class.java)
            .mapNotNull { variable ->
                if (variable.nameAsString != argument || start(variable) > start(forEach)) return@mapNotNull null
                val scope = localScope(variable) ?: return@mapNotNull null
                if (start(forEach) !in start(scope)..end(scope)) return@mapNotNull null
                variable to scope
            }
            .sortedWith(
                compareBy<Pair<VariableDeclarator, Node>> { end(it.second) - start(it.second) }
                    .thenByDescending { start(it.first) }
            )
            .firstOrNull()
            ?.first
        val type = local?.type?.toString() ?: parameter?.type?.toString() ?: return null
        val open = type.indexOf('<')
        val close = type.lastIndexOf('>')
        if (open < 0 || close <= open ||
            !typing.isTypeName(type.substring(0, open).trim(), "java.util.function.Consumer")
        ) return null
        val arguments = JavaSourceText.splitTopLevelArgs(type.substring(open + 1, close))
        if (arguments.size != 1) return null
        return arguments.single().trim().removePrefix("? super ").removePrefix("? extends ").trim()
    }

    val edits = mutableListOf<Pair<Int, String>>()
    for (call in unit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "getAllRecipesFor" && it.arguments.size == 1 }
        .sortedByDescending(::start)
    ) {
        if (!isRecipeManagerReceiver(call)) continue
        val type = targetType(call) ?: continue
        if (!isRecipeFamily(type, call)) continue

        if (directInitializer(call)) {
            if (!collectionTarget(type)) continue
            if (defersToHolderIdFilter(call)) continue
            val callEnd = end(call)
            if (callEnd >= 0) edits += (callEnd + 1) to ".stream().map(RecipeHolder::value).toList()"
            continue
        }

        val stream = streamChild(call) ?: continue
        val root = chainRoot(stream)
        val rootStart = start(call)
        val rootEnd = end(root)
        if (rootStart < 0 || rootEnd < rootStart) continue
        val chain = JavaSourceText.maskCommentsAndLiterals(source.substring(rootStart, rootEnd + 1))
        if (Regex("""\.\s*map\s*\(\s*(?:net\.minecraft\.world\.item\.crafting\.)?RecipeHolder\s*::\s*value\s*\)""")
                .containsMatchIn(chain)
        ) continue
        // These are holder APIs. A later rule preserves that holder through the terminal lookup.
        if (Regex("""\.\s*(?:id|value)\s*\(""").containsMatchIn(chain)) continue
        val streamEnd = end(stream)
        if (streamEnd >= 0) edits += (streamEnd + 1) to ".map(RecipeHolder::value)"
    }
    for (recipes in unit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "getRecipes" && it.arguments.isEmpty() }
        .sortedByDescending(::start)
    ) {
        if (!isRecipeManagerReceiver(recipes)) continue
        val forEach = recipes.parentNode.orElse(null) as? MethodCallExpr ?: continue
        if (forEach.scope.orElse(null) !== recipes ||
            forEach.nameAsString != "forEach" ||
            forEach.arguments.size != 1
        ) continue
        val consumed = consumerValueType(forEach) ?: continue
        if (!isRecipeFamily(consumed, recipes)) continue
        val recipesEnd = end(recipes)
        if (recipesEnd >= 0) edits += (recipesEnd + 1) to ".stream().map(RecipeHolder::value)"
    }
    if (edits.isEmpty()) return source
    var result = source
    for ((offset, replacement) in edits.sortedByDescending { it.first }.distinctBy { it.first }) {
        result = result.substring(0, offset) + replacement + result.substring(offset)
    }
    return addExecutableImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
}

/** Registrate 1.21 separates its data-generation descriptor from vanilla Ingredient. */
internal fun StructuralRefactorPass.migrateRegistrateDataIngredients(source: String): String {
    if (!source.contains("DataIngredient")) return source
    val dataIngredient = "com.tterrag.registrate.util.DataIngredient"
    val importsDataIngredient = singleTypeImportFqn(source, "DataIngredient") == dataIngredient
    val declaresDataIngredient = Regex("""\b(?:class|interface|record|enum)\s+DataIngredient\b""")
        .containsMatchIn(maskJavaCommentsAndLiterals(source))

    fun isDataIngredient(expression: String, offset: Int, text: String): Boolean {
        val value = expression.trim()
        val staticOwner = when {
            value.startsWith("$dataIngredient.") -> dataIngredient
            importsDataIngredient && !declaresDataIngredient && value.startsWith("DataIngredient.") -> dataIngredient
            else -> null
        }
        if (staticOwner != null &&
            Regex("""^(?:${Regex.escape(dataIngredient)}|DataIngredient)\.(?:items|stacks|tag|ingredient)\s*\(""")
                .containsMatchIn(value)
        ) return true
        return SourceValueTypes(this, text).typeOf(value, offset) == dataIngredient
    }

    fun isBuilderReceiver(expression: String, offset: Int, text: String, expected: String): Boolean {
        val typing = SourceValueTypes(this, text)
        if (typing.typeOf(expression, offset) == expected) return true
        val simple = expected.substringAfterLast('.')
        val factory = if (simple == "ShapedRecipeBuilder") "shaped" else "shapeless"
        val fluent = if (simple == "ShapedRecipeBuilder") {
            setOf("define", "pattern", "unlockedBy", "group", "showNotification")
        } else {
            setOf("requires", "unlockedBy", "group")
        }
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        fun prove(value: String, depth: Int): Boolean {
            if (depth > 16) return false
            val call = parser.parseExpression<Expression>(value.trim()).result.orElse(null) as? MethodCallExpr
                ?: return false
            val scope = call.scope.orElse(null) ?: return false
            if (call.nameAsString == factory) {
                val owner = scope.toString()
                return typing.isTypeName(owner, expected)
            }
            return call.nameAsString in fluent && prove(scope.toString(), depth + 1)
        }
        return prove(expression, 0)
    }

    fun migrateBuilderCall(
        text: String,
        method: String,
        argumentIndex: Int,
        builder: String
    ): String = rewriteExecutableJavaCallWithOffset(text, method) { receiver, args, offset ->
        if (args.size <= argumentIndex || args[argumentIndex].trim().endsWith(".toVanilla()") ||
            !isBuilderReceiver(receiver, offset, text, builder) ||
            !isDataIngredient(args[argumentIndex], offset, text)
        ) return@rewriteExecutableJavaCallWithOffset null
        val migrated = args.toMutableList()
        migrated[argumentIndex] = "${migrated[argumentIndex].trim()}.toVanilla()"
        "$receiver.$method(${migrated.joinToString(", ") { it.trim() }})"
    }

    var result = migrateBuilderCall(
        source,
        "requires",
        0,
        "net.minecraft.data.recipes.ShapelessRecipeBuilder"
    )
    result = migrateBuilderCall(
        result,
        "define",
        1,
        "net.minecraft.data.recipes.ShapedRecipeBuilder"
    )
    result = rewriteExecutableJavaCallWithOffset(result, "getCritereon") { receiver, args, offset ->
        if (args.size != 1 || !isDataIngredient(receiver, offset, result)) {
            null
        } else {
            "$receiver.getCriterion(${args[0].trim()})"
        }
    }
    return result
}
