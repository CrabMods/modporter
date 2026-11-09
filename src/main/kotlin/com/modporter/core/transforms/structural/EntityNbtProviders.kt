package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/** Entity NBT reads its own level's registries. Local receivers can be reused without re-evaluation. */
internal fun StructuralRefactorPass.migrateEntityNbtProviders(source: String): String {
    if (!source.contains("serializeNBT") && !source.contains("deserializeNBT")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val starts = buildList { add(0); source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    fun start(node: Node) = node.range.map { starts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    fun end(node: Node) = node.range.map { starts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java)) {
        if (!(call.nameAsString == "serializeNBT" && call.arguments.isEmpty() || call.nameAsString == "deserializeNBT" && call.arguments.size == 1)) continue
        val receiver = call.scope.orElse(null) as? NameExpr ?: continue
        val position = start(call)
        val type = typing.typeOf(receiver.nameAsString, position) ?: continue
        if (!TargetMembers.inheritsFrom(type, "net.minecraft.world.entity.Entity")) continue
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: continue
        val parameter = method.parameters.any { it.nameAsString == receiver.nameAsString }
        val local = method.findAll(VariableDeclarator::class.java).any { variable ->
            val block = variable.findAncestor(BlockStmt::class.java).orElse(null)
            variable.nameAsString == receiver.nameAsString && variable.parentNode.orElse(null) is VariableDeclarationExpr &&
                block != null && position in start(block)..end(block) && start(variable) <= position
        }
        if (!parameter && !local) continue
        call.arguments.addFirst(parser.parseExpression<Expression>("${receiver.nameAsString}.registryAccess()").result.orElseThrow())
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}
