package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/** Amount access belongs to SizedFluidIngredient; a source-owned FluidIngredient keeps its API. */
internal fun StructuralRefactorPass.migrateProvenFluidIngredientAmounts(source: String): String {
    if (!source.contains("getRequiredAmount")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val lines = buildList {
        add(0)
        source.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    }
    fun offset(node: Node): Int = node.range.map { lines[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    val sized = "net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient"
    // Public Create 6 ABI, verified in the actual 1.21.1 ProcessingRecipe and MixingRecipe sources.
    // A project defining these classes owns its declarations and is never treated as the library.
    val externalRecipes = setOf(
        "com.simibubi.create.content.processing.recipe.ProcessingRecipe",
        "com.simibubi.create.content.kinetics.mixer.MixingRecipe"
    )
    fun externalFluidList(expression: Expression): Boolean {
        val getter = expression as? MethodCallExpr ?: return false
        if (getter.nameAsString != "getFluidIngredients" || getter.arguments.isNotEmpty() || getter.typeArguments.isPresent) return false
        val receiver = getter.scope.orElse(null) ?: return false
        val owner = typing.typeOf(receiver.toString(), offset(receiver)) ?: return false
        return owner in externalRecipes && owner !in projectValueFacts.types
    }
    fun inferredLocalList(name: String, use: Node): Boolean {
        var current: Node? = use
        while (current != null) {
            when (val node = current) {
                is LambdaExpr -> if (node.parameters.any { it.nameAsString == name }) return false
                is CatchClause -> if (node.parameter.nameAsString == name) return false
                is ForEachStmt -> if (node.variable.variables.any { it.nameAsString == name }) return false
                is MethodDeclaration, is ClassOrInterfaceDeclaration -> return false
                is BlockStmt -> {
                    val declaration = node.statements.filterIsInstance<ExpressionStmt>()
                        .mapNotNull { it.expression as? VariableDeclarationExpr }
                        .flatMap { it.variables }
                        .lastOrNull { it.nameAsString == name && offset(it) < offset(use) }
                    if (declaration != null) {
                        if (!declaration.type.isVarType) return false
                        if (node.findAll(AssignExpr::class.java).any {
                                it.target.toString() == name && offset(it) in offset(declaration)..offset(use)
                            }) return false
                        return declaration.initializer.map(::externalFluidList).orElse(false)
                    }
                }
            }
            current = current.parentNode.orElse(null)
        }
        return false
    }
    fun sizedReceiver(expression: Expression, use: Node): Boolean {
        if (typing.typeOf(expression.toString(), offset(expression)) == sized) return true
        val get = expression as? MethodCallExpr ?: return false
        if (get.nameAsString != "get" || get.arguments.size != 1 || get.typeArguments.isPresent) return false
        val list = get.scope.orElse(null) ?: return false
        if (typing.iterableElementType(list.toString(), offset(list)) == sized) return true
        if (externalFluidList(list)) return true
        return list is NameExpr && inferredLocalList(list.nameAsString, use)
    }
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != "getRequiredAmount" || call.arguments.isNotEmpty() || call.typeArguments.isPresent) continue
        val receiver = call.scope.orElse(null) ?: continue
        if (!sizedReceiver(receiver, call)) continue
        call.setName("amount")
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}
