package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/** MobEffect now creates scaled modifiers from its attribute templates through createModifiers. */
internal fun StructuralRefactorPass.migrateMobEffectModifierIteration(source: String): String {
    if (!source.contains("getAttributeModifiers") || !source.contains("getAttributeModifierValue")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val types = SourceValueTypes(this, source)
    val starts = buildList { add(0); source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    fun offset(node: Node) = node.range.map { starts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)
    fun variable(statement: Statement): VariableDeclarator? = ((statement as? ExpressionStmt)?.expression as? VariableDeclarationExpr)?.variables?.singleOrNull()
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (declaration in unit.findAll(VariableDeclarator::class.java).toList()) {
        val getter = declaration.initializer.orElse(null) as? MethodCallExpr ?: continue
        if (getter.nameAsString != "getAttributeModifiers" || getter.arguments.isNotEmpty() || getter.scope.isEmpty) continue
        val receiver = getter.scope.get()
        if (types.typeOf(receiver.toString(), offset(getter)) != "net.minecraft.world.effect.MobEffect") continue
        if (types.typeOf(declaration.nameAsString, offset(getter)) != "java.util.Map") continue
        val statement = declaration.parentNode.orElse(null)?.parentNode?.orElse(null) as? ExpressionStmt ?: continue
        val block = statement.parentNode.orElse(null) as? BlockStmt ?: continue
        val branch = block.statements.getOrNull(block.statements.indexOf(statement) + 1) as? IfStmt ?: continue
        if (branch.elseStmt.isPresent || branch.condition.toString() != "!${declaration.nameAsString}.isEmpty()") continue
        val loop = (branch.thenStmt as? ForEachStmt ?: (branch.thenStmt as? BlockStmt)?.statements?.singleOrNull() as? ForEachStmt) ?: continue
        if (loop.iterable.toString() != "${declaration.nameAsString}.entrySet()") continue
        if (block.findAll(NameExpr::class.java).filter { it.nameAsString == declaration.nameAsString }.any { use -> !branch.range.get().contains(use.range.get()) }) continue
        val entry = loop.variable.variables.singleOrNull()?.nameAsString ?: continue
        val body = loop.body as? BlockStmt ?: continue
        if (body.statements.size != 3) continue
        val template = variable(body.statements[0]) ?: continue
        if (template.initializer.map { it.toString() }.orElse("") != "$entry.getValue()") continue
        val scaled = variable(body.statements[1]) ?: continue
        val creation = scaled.initializer.orElse(null) as? ObjectCreationExpr ?: continue
        if (creation.type.nameAsString != "AttributeModifier" || creation.arguments.size != 3 || creation.arguments[0].toString() != "${template.nameAsString}.getName()" ||
            creation.arguments[2].toString() !in setOf("${template.nameAsString}.operation()", "${template.nameAsString}.getOperation()")) continue
        val amount = creation.arguments[1] as? MethodCallExpr ?: continue
        if (amount.nameAsString != "getAttributeModifierValue" || amount.scope.map { it.toString() }.orElse("") != receiver.toString() || amount.arguments.size != 2 || amount.arguments[1].toString() != template.nameAsString) continue
        val amplifier = amount.arguments[0]
        val pureAmplifier = amplifier is NameExpr || amplifier is IntegerLiteralExpr || amplifier is MethodCallExpr && amplifier.nameAsString == "getAmplifier" && amplifier.arguments.isEmpty() &&
            amplifier.scope.isPresent && types.typeOf(amplifier.scope.get().toString(), offset(amplifier)) == "net.minecraft.world.effect.MobEffectInstance"
        if (!pureAmplifier) continue
        val consumer = body.statements[2].clone()
        if (consumer.findAll(NameExpr::class.java).any { it.nameAsString == template.nameAsString }) continue
        val entryUses = consumer.findAll(NameExpr::class.java).filter { it.nameAsString == entry }
        if (entryUses.any { use -> val call = use.parentNode.orElse(null) as? MethodCallExpr; call == null || call.nameAsString != "getKey" || call.arguments.isNotEmpty() }) continue
        var attribute = "modporterAttribute"
        while (source.contains(attribute)) attribute += "_"
        for (use in entryUses) use.parentNode.get().replace(parser.parseExpression<Expression>("$attribute.value()").result.orElseThrow())
        val replacement = parser.parseStatement("$receiver.createModifiers($amplifier, ($attribute, ${scaled.nameAsString}) -> { $consumer });").result.orElseThrow()
        branch.replace(replacement)
        statement.remove()
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}
