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

/**
 * Dependency methods whose NeoForge 1.21.1 builds return a list of NeoForge SizedFluidIngredient
 * where their Forge 1.20.1 builds returned the library's own FluidIngredient (whose amount getter
 * was `getRequiredAmount()`). Each entry is javap evidence from the 1.21.1 jar; a type the project
 * declares itself is never looked up here, so a source-owned copy of the library keeps its own API.
 */
private data class LibrarySizedFluidList(val owner: String, val method: String, val evidence: String)

private val librarySizedFluidLists = listOf(
    LibrarySizedFluidList(
        "com.simibubi.create.content.processing.recipe.ProcessingRecipe",
        "getFluidIngredients",
        "create 6.0.9 (curse.maven:create-328085:7408951): public NonNullList<SizedFluidIngredient> " +
            "getFluidIngredients(), descriptor ()Lnet/minecraft/core/NonNullList;"
    ),
    LibrarySizedFluidList(
        "com.simibubi.create.content.kinetics.mixer.MixingRecipe",
        "getFluidIngredients",
        "create 6.0.9: MixingRecipe extends BasinRecipe extends StandardProcessingRecipe, which " +
            "inherits ProcessingRecipe#getFluidIngredients()"
    )
)

/**
 * Amount access belongs to NeoForge's SizedFluidIngredient (`amount()`); a source-owned
 * FluidIngredient keeps its own API. A receiver is rewritten only with type evidence: its declared
 * type, a declared list element type, a `var` local initialized from such a list, or a dependency
 * getter listed with javap evidence in [librarySizedFluidLists] on a type the project does not
 * declare. A getter without such evidence is never assumed to return sized ingredients.
 */
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
    fun libraryFluidList(expression: Expression): Boolean {
        val getter = expression as? MethodCallExpr ?: return false
        if (getter.arguments.isNotEmpty() || getter.typeArguments.isPresent) return false
        val receiver = getter.scope.orElse(null) ?: return false
        val owner = typing.typeOf(receiver.toString(), offset(receiver)) ?: return false
        return owner !in projectValueFacts.types && librarySizedFluidLists.any {
            it.owner == owner && it.method == getter.nameAsString
        }
    }
    fun sizedList(expression: Expression): Boolean =
        typing.iterableElementType(expression.toString(), offset(expression)) == sized ||
            libraryFluidList(expression)
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
                        return declaration.initializer.map(::sizedList).orElse(false)
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
        if (sizedList(list)) return true
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
