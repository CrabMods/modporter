package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/**
 * Replaces removed ItemStack method references with component-backed equivalents while retaining
 * the original functional shape. Bound setHoverName references capture and null-check their
 * receiver when the reference is created, then return that receiver just as the legacy method did.
 */
internal fun StructuralRefactorPass.migrateLegacyItemStackMethodReferences(source: String): String {
    if (!source.contains("isEdible") &&
        !source.contains("hasCustomHoverName") &&
        !source.contains("setHoverName")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun offset(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)
    fun uniqueName(base: String): String {
        var name = base
        while (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(source)) name += "_"
        return name
    }

    val stackParameter = uniqueName("modporterStack")
    val componentParameter = uniqueName("modporterName")
    val adapterMethod = uniqueName("modporterSetHoverName")
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (reference in unit.findAll(MethodReferenceExpr::class.java)) {
        val replacement = when (reference.identifier) {
            "isEdible" -> {
                if (!targetHasComponent("FOOD") ||
                    !typing.isTypeName(reference.scope.toString(), ITEM_STACK)) continue
                "$stackParameter -> $stackParameter.has(net.minecraft.core.component.DataComponents.FOOD)"
            }
            "hasCustomHoverName" -> {
                if (!targetHasComponent("CUSTOM_NAME") ||
                    !typing.isTypeName(reference.scope.toString(), ITEM_STACK)) continue
                "$stackParameter -> $stackParameter.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)"
            }
            "setHoverName" -> {
                if (!targetSetsCustomName()) continue
                val receiver = reference.scope
                if (typing.typeOf(receiver.toString(), offset(receiver)) != ITEM_STACK) continue
                val capturedReceiver = capturedBoundReceiver(reference) ?: continue
                "new Object() {" +
                    " private final $ITEM_STACK $stackParameter = java.util.Objects.requireNonNull($capturedReceiver);" +
                    " public $ITEM_STACK $adapterMethod(net.minecraft.network.chat.Component $componentParameter) {" +
                    " $stackParameter.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, $componentParameter);" +
                    " return $stackParameter;" +
                    " }" +
                    " }::$adapterMethod"
            }
            else -> continue
        }
        val expression = parser.parseExpression<Expression>(replacement).result.orElse(null) ?: continue
        reference.replace(expression)
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
private const val COMPONENT_TYPE = "Lnet/minecraft/core/component/DataComponentType;"
private const val COMPONENT_HOLDER = "net.minecraft.core.component.DataComponentHolder"
private const val DATA_COMPONENTS = "net.minecraft.core.component.DataComponents"

private fun targetHasComponent(field: String): Boolean {
    val has = TargetMembers.inheritedMethod(ITEM_STACK, "has", "($COMPONENT_TYPE)Z")
    val legacyMethod = when (field) {
        "FOOD" -> "isEdible"
        "CUSTOM_NAME" -> "hasCustomHoverName"
        else -> return false
    }
    return !TargetMembers.declaresInHierarchy(ITEM_STACK, legacyMethod, 0) &&
        has?.owner == COMPONENT_HOLDER &&
        has.access == "public" &&
        has.signature == "(Lnet/minecraft/core/component/DataComponentType<*>;)Z" &&
        targetComponentField(field)
}

private fun targetSetsCustomName(): Boolean {
    val set = TargetMembers.inheritedMethod(
        ITEM_STACK,
        "set",
        "(${COMPONENT_TYPE}Ljava/lang/Object;)Ljava/lang/Object;",
    )
    return !TargetMembers.declaresInHierarchy(ITEM_STACK, "setHoverName", 1) &&
        set?.owner == ITEM_STACK &&
        set.access == "public" &&
        set.signature ==
            "<T:Ljava/lang/Object;>(Lnet/minecraft/core/component/DataComponentType<-TT;>;TT;)TT;" &&
        targetComponentField("CUSTOM_NAME")
}

private fun targetComponentField(field: String): Boolean {
    val signature = when (field) {
        "FOOD" ->
            "Lnet/minecraft/core/component/DataComponentType<Lnet/minecraft/world/food/FoodProperties;>;"
        "CUSTOM_NAME" ->
            "Lnet/minecraft/core/component/DataComponentType<Lnet/minecraft/network/chat/Component;>;"
        else -> return false
    }
    return TargetMembers.fieldDescriptor(DATA_COMPONENTS, field) == COMPONENT_TYPE &&
        TargetMembers.fieldSignatures(DATA_COMPONENTS)[field] == signature
}

private fun capturedBoundReceiver(reference: MethodReferenceExpr): String? =
    capturedBoundReceiver(reference.scope, reference)

private fun capturedBoundReceiver(expression: Expression, reference: MethodReferenceExpr): String? = when (expression) {
    is NameExpr -> expression.toString()
    is TypeExpr -> expression.type.toString().takeIf { it.matches(Regex("""[A-Za-z_$][\w$]*""")) }
    is EnclosedExpr -> capturedBoundReceiver(expression.inner, reference)
    is FieldAccessExpr -> (expression.scope as? ThisExpr)?.let { owner ->
        if (owner.typeName.isPresent) expression.toString()
        else enclosingNamedOwner(reference)?.let { "$it.this.${expression.nameAsString}" }
    }
    else -> null
}

private fun enclosingNamedOwner(reference: MethodReferenceExpr): String? {
    var current: Node? = reference.parentNode.orElse(null)
    while (current != null) {
        if (current is ObjectCreationExpr && current.anonymousClassBody.isPresent) return null
        if (current is EnumConstantDeclaration && current.classBody.isNotEmpty()) return null
        if (current is TypeDeclaration<*>) return current.nameAsString
        current = current.parentNode.orElse(null)
    }
    return null
}
