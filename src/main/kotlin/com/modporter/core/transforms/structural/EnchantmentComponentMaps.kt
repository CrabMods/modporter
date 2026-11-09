package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

internal fun StructuralRefactorPass.migrateEnchantmentComponentMaps(source: String): String {
    if (!source.contains("getEnchantmentsForCrafting") && !source.contains("ItemEnchantments")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val exact = ExactJavaSemantics(unit)
    val starts = buildList { add(0); source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    fun offset(node: com.github.javaparser.ast.Node) = node.range.map { starts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    val keySet = TargetMembers.inheritedMethod(
        "net.minecraft.world.item.enchantment.ItemEnchantments",
        "keySet",
        "()Ljava/util/Set;",
    )
    val holderKeySet = keySet?.owner == "net.minecraft.world.item.enchantment.ItemEnchantments" &&
        keySet.access == "public" &&
        keySet.signature == "()Ljava/util/Set<Lnet/minecraft/core/Holder<Lnet/minecraft/world/item/enchantment/Enchantment;>;>;"
    if (holderKeySet) {
        var enchantmentsName = "modporterEnchantments"
        while (source.contains(enchantmentsName)) enchantmentsName += "_"
        var enchantmentName = "modporterEnchantment"
        while (source.contains(enchantmentName) || enchantmentName == enchantmentsName) enchantmentName += "_"
        fun oneTimePredicate(
            receiver: Expression,
            argument: Expression,
            argumentType: String,
            body: String,
        ): String =
            "((java.util.function.BiPredicate<" +
                "net.minecraft.world.item.enchantment.ItemEnchantments, $argumentType>) " +
                "($enchantmentsName, $enchantmentName) -> $body).test($receiver, $argument)"
        for (call in unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != "containsKey" || call.arguments.size != 1 || call.scope.isEmpty) continue
            val receiver = call.scope.get()
            if (typing.typeOf(receiver.toString(), offset(receiver)) !=
                "net.minecraft.world.item.enchantment.ItemEnchantments") continue
            val argument = call.arguments.single()
            val replacement = when {
                typing.holderValueType(argument.toString(), offset(argument)) ==
                    "net.minecraft.world.item.enchantment.Enchantment" ->
                    oneTimePredicate(
                        receiver,
                        argument,
                        "net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>",
                        "$enchantmentsName.keySet().contains($enchantmentName)",
                    )
                exactRawEnchantmentReference(argument, exact) ->
                    oneTimePredicate(
                        receiver,
                        argument,
                        "net.minecraft.world.item.enchantment.Enchantment",
                        "$enchantmentsName.keySet().stream().map(net.minecraft.core.Holder::value)" +
                            ".anyMatch(java.util.Collections.singleton($enchantmentName)::contains)",
                    )
                else -> null
            } ?: continue
            val expression = parser.parseExpression<Expression>(replacement).result.orElse(null) ?: continue
            call.replace(expression)
            changed = true
        }
    }
    for (variable in unit.findAll(VariableDeclarator::class.java)) {
        val initializer = variable.initializer.orElse(null) as? MethodCallExpr ?: continue
        if (typing.typeOf(initializer.toString(), offset(initializer)) != "net.minecraft.world.item.enchantment.ItemEnchantments") continue
        if (!variable.type.isClassOrInterfaceType || variable.type.asClassOrInterfaceType().nameAsString != "Map") continue
        val block = variable.findAncestor(BlockStmt::class.java).orElse(null) ?: continue
        val uses = block.findAll(NameExpr::class.java).filter { it.nameAsString == variable.nameAsString }
        if (uses.any { use -> val call = use.parentNode.orElse(null) as? MethodCallExpr
                call == null || call.scope.orElse(null) !== use || call.nameAsString !in setOf("keySet", "size", "isEmpty") ||
                    call.nameAsString == "keySet" && call.parentNode.orElse(null) !is ForEachStmt }) continue
        variable.setType("net.minecraft.world.item.enchantment.ItemEnchantments")
        changed = true
    }
    val intermediate = if (changed) LexicalPreservingPrinter.print(unit) else source
    val loops = parser.parse(intermediate).result.orElse(null) ?: return intermediate
    val loopTyping = SourceValueTypes(this, intermediate)
    val loopStarts = buildList { add(0); intermediate.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    LexicalPreservingPrinter.setup(loops)
    var loopChanged = false
    for (loop in loops.findAll(ForEachStmt::class.java)) {
        val call = loop.iterable as? MethodCallExpr ?: continue
        if (call.nameAsString != "keySet" || call.scope.isEmpty) continue
        val at = call.range.map { loopStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)
        if (loopTyping.typeOf(call.scope.get().toString(), at) != "net.minecraft.world.item.enchantment.ItemEnchantments") continue
        val variable = loop.variable.variables.singleOrNull() ?: continue
        if (variable.type.asString() != "net.minecraft.world.item.enchantment.Enchantment" &&
            !(variable.type.asString() == "Enchantment" && intermediate.contains("import net.minecraft.world.item.enchantment.Enchantment;"))) continue
        val originalName = variable.nameAsString
        val writesBinding = loop.body.findAll(AssignExpr::class.java).any { assignment ->
            val target = assignment.target
            target is NameExpr && target.nameAsString == originalName
        }
        if (writesBinding) continue
        var holder = "modporterEnchantment"
        while (intermediate.contains(holder) || loops.findAll(VariableDeclarator::class.java).any { it.nameAsString == holder }) holder += "_"
        val binding = parser.parseVariableDeclarationExpr(loop.variable.toString()).result.orElseThrow()
        binding.variables[0].setInitializer("$holder.value()")
        variable.setName(holder).setType("net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>")
        val body = loop.body as? BlockStmt ?: BlockStmt().addStatement(loop.body.clone()).also { loop.setBody(it) }
        val enclosing = loop.findAncestor(MethodDeclaration::class.java).orElse(null)
        for (use in body.findAll(MethodCallExpr::class.java)) {
            if (use.nameAsString == "is" && use.arguments.size == 1 && use.scope.map { it.toString() }.orElse("") == originalName &&
                use.findAncestor(MethodDeclaration::class.java).orElse(null) === enclosing) use.setScope(NameExpr(holder))
        }
        body.addStatement(0, ExpressionStmt(binding))
        loopChanged = true
    }
    return if (loopChanged) LexicalPreservingPrinter.print(loops) else intermediate
}

private fun exactRawEnchantmentReference(
    expression: Expression,
    exact: ExactJavaSemantics,
): Boolean {
    if (!sideEffectFreeEnchantmentReference(expression)) return false
    if (exact.isProvablyType(
            expression,
            "Enchantment",
            "net.minecraft.world.item.enchantment.Enchantment",
        )
    ) {
        return true
    }
    var unwrapped = expression
    while (unwrapped is EnclosedExpr) unwrapped = unwrapped.inner
    val field = unwrapped as? FieldAccessExpr ?: return false
    val thisScope = field.scope as? ThisExpr ?: return false
    val owners = generateSequence(field.parentNode.orElse(null)) {
        it.parentNode.orElse(null)
    }.filterIsInstance<TypeDeclaration<*>>().toList()
    val qualifiedOwner = thisScope.typeName.map { it.asString() }.orElse(null)
    val owner = if (qualifiedOwner == null) {
        owners.firstOrNull()
    } else {
        owners.filter { it.nameAsString == qualifiedOwner }.singleOrNull()
    } ?: return false
    val declaration = owner.fields
        .flatMap { it.variables }
        .filter { it.nameAsString == field.nameAsString }
        .singleOrNull()
        ?: return false
    return exact.exactTypeReference(
        declaration.typeAsString,
        "Enchantment",
        "net.minecraft.world.item.enchantment.Enchantment",
    )
}

private fun sideEffectFreeEnchantmentReference(expression: Expression): Boolean = when (expression) {
    is NameExpr, is ThisExpr, is SuperExpr -> true
    is EnclosedExpr -> sideEffectFreeEnchantmentReference(expression.inner)
    is FieldAccessExpr -> sideEffectFreeEnchantmentReference(expression.scope)
    else -> false
}

/** Calls absent from Holder but present on its proven platform element use the held value. */
internal fun StructuralRefactorPass.migrateHolderElementCalls(source: String): String {
    if (!source.contains("Holder") && !source.contains("getEffect(") && !source.contains("getEnchantmentsForCrafting") &&
        !source.contains("MobEffects") && !source.contains("Potions") && !source.contains("Attributes")) return source
    var result = source
    var typedSource: String? = null
    var model: SourceValueTypes? = null
    fun typing(): SourceValueTypes {
        if (typedSource !== result) { typedSource = result; model = SourceValueTypes(this, result) }
        return model!!
    }
    val excluded = setOf("getClass", "hashCode", "equals", "toString", "wait", "notify", "notifyAll", "clone", "finalize")
    val names = Regex("""\.\s*([A-Za-z_$][\w$]*)\s*\(""").findAll(maskJavaCommentsAndLiterals(source)).map { it.groupValues[1] }.toSet() - excluded
    for (name in names) {
        result = rewriteExecutableJavaCallWithOffset(result, name) { receiver, args, offset ->
            val element = typing().holderValueType(receiver, offset) ?: return@rewriteExecutableJavaCallWithOffset null
            if (!TargetMembers.declaresInHierarchy(element, name, args.size) || TargetMembers.declaresInHierarchy("net.minecraft.core.Holder", name, args.size)) null
            else "$receiver.value().$name(${args.joinToString(", ")})"
        }
    }
    return result
}
