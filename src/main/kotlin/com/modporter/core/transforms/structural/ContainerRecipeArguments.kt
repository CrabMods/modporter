package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/** Containers become crafting inputs only at recipe parameters proven to accept that input. */
internal fun StructuralRefactorPass.migrateContainerRecipeArguments(source: String): String {
    if (!source.contains("CraftingContainer") || !Regex("""\.(?:matches|assemble|getRemainingItems)\s*\(""").containsMatchIn(source)) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val starts = buildList { add(0); source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    fun offset(node: com.github.javaparser.ast.Node) = node.range.map { starts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)
    fun inherits(type: String?, base: String): Boolean {
        var current = type ?: return false
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            if (TargetMembers.hasClass(current)) return TargetMembers.inheritsFrom(current, base)
            current = projectValueFacts.parents[current] ?: return false
        }
        return false
    }
    fun ownInput(type: String?, method: String): Boolean {
        if (type == null) return false
        val namespace = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val declaration = unit.findAll(ClassOrInterfaceDeclaration::class.java).singleOrNull { "$namespace.${it.nameAsString}" == type } ?: return false
        if (declaration.methods.any { it.nameAsString == method && it.parameters.firstOrNull()?.type?.asString()?.substringAfterLast('.') == "CraftingContainer" }) return false
        return declaration.extendedTypes.any { parent ->
            projectRecipeInputPositions[parent.nameAsString].orEmpty().any { index ->
                val argument = parent.typeArguments.orElse(null)?.getOrNull(index)?.asString()
                argument == "net.minecraft.world.item.crafting.RecipeInput" || argument == "RecipeInput" && source.contains("import net.minecraft.world.item.crafting.RecipeInput;")
            }
        }
    }
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString !in setOf("matches", "assemble", "getRemainingItems") || call.arguments.isEmpty() || call.scope.isEmpty) continue
        val argument = call.arguments[0]
        val inputType = typing.typeOf(argument.toString(), offset(argument))
        if (!inherits(inputType, "net.minecraft.world.inventory.CraftingContainer")) continue
        val recipe = typing.typeOf(call.scope.get().toString(), offset(call))
        if (!inherits(recipe, "net.minecraft.world.item.crafting.CraftingRecipe") && !ownInput(recipe, call.nameAsString)) continue
        val receiver = if (argument is NameExpr || argument is FieldAccessExpr) argument.toString() else "($argument)"
        argument.replace(parser.parseExpression<Expression>("$receiver.asCraftInput()").result.orElseThrow())
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}
