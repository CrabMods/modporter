package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/** Move subclass behavior to the overridable 1.21 hooks behind newly final entry points. */
internal fun StructuralRefactorPass.migrateFinalizedVanillaHooks(source: String, index: JavaInheritanceIndex): String {
    if (!source.contains("@Override") || !Regex("""\b(?:render|eat|recalculateBoundingBox)\s*\(""").containsMatchIn(source)) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (method in unit.findAll(MethodDeclaration::class.java)) {
        if (method.annotations.none { it.name.identifier == "Override" } || method.body.isEmpty) continue
        val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: continue
        val parameters = method.parameters.map { it.type.asString().substringAfterLast('.') }
        when {
            method.nameAsString == "eat" && parameters == listOf("Level", "ItemStack") && index.inherits(owner.nameAsString, javaLivingEntityBaseTypes()) -> {
                if (owner.methods.any { it !== method && it.nameAsString == "eat" && it.parameters.size == 3 }) continue
                var properties = "modporterFoodProperties"
                while (method.toString().contains(properties)) properties += "_"
                method.addParameter("net.minecraft.world.food.FoodProperties", properties)
                for (call in method.body.get().findAll(MethodCallExpr::class.java)) {
                    if (call.nameAsString == "eat" && call.scope.orElse(null) is SuperExpr && call.arguments.size == 2) call.addArgument(properties)
                }
                changed = true
            }
            method.nameAsString == "render" && parameters == listOf("GuiGraphics", "int", "int", "float") -> {
                val libraryWidget = source.contains("import net.createmod.catnip.gui.widget.AbstractSimiWidget;") &&
                    index.inherits(owner.nameAsString, setOf("AbstractSimiWidget"))
                if (!index.inherits(owner.nameAsString, setOf("AbstractWidget")) && !libraryWidget) continue
                if (owner.methods.any { it !== method && it.nameAsString == "renderWidget" && it.parameters.size == 4 }) continue
                // Calling the final entry point from its new hook would recurse.
                if (method.body.get().findAll(MethodCallExpr::class.java).any { it.nameAsString == "render" && it.scope.orElse(null) is SuperExpr }) continue
                method.setName("renderWidget")
                changed = true
            }
            method.nameAsString == "recalculateBoundingBox" && parameters.isEmpty() && method.type.isVoidType &&
                index.inherits(owner.nameAsString, setOf("HangingEntity")) -> {
                if (owner.methods.any { it.nameAsString == "calculateBoundingBox" && it.parameters.size == 2 }) continue
                val body = method.body.get()
                val last = body.statements.lastOrNull() as? ExpressionStmt ?: continue
                val setter = last.expression as? MethodCallExpr ?: continue
                if (setter.nameAsString != "setBoundingBox" || setter.arguments.size != 1 ||
                    setter.scope.map { it.toString() }.orElse("this") != "this") continue
                if (body.findAll(ReturnStmt::class.java).any { it.expression.isPresent }) continue
                var anchor = "modporterAnchor"
                while (method.toString().contains(anchor)) anchor += "_"
                var facing = "modporterFacing"
                while (method.toString().contains(facing)) facing += "_"
                method.setName("calculateBoundingBox").setType("net.minecraft.world.phys.AABB")
                method.addParameter("net.minecraft.core.BlockPos", anchor)
                method.addParameter("net.minecraft.core.Direction", facing)
                for (exit in body.findAll(ReturnStmt::class.java)) exit.setExpression(parser.parseExpression<com.github.javaparser.ast.expr.Expression>("this.getBoundingBox()").result.orElseThrow())
                last.replace(ReturnStmt(setter.arguments[0].clone()))
                changed = true
            }
        }
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}
