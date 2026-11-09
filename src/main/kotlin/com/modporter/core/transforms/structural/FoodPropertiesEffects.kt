package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers
import com.modporter.core.transforms.util.JavaSourceText

/**
 * `FoodProperties` became a record in 1.21.1 and its effect list is `effects()` (member table);
 * other types keep their own `getEffects()` (`Potion#getEffects()` in particular). The accessor is
 * renamed only on receivers proven to be food properties: a variable, field or parameter declared
 * as `FoodProperties`, or a `getFoodProperties(...)` call. Typed legacy effect loops are migrated
 * only when every Pair element use has a source-equivalent target operation.
 */
internal object FoodPropertiesEffects {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val FOOD_PROPERTIES = "net.minecraft.world.food.FoodProperties"
    private const val MOB_EFFECT_INSTANCE = "net.minecraft.world.effect.MobEffectInstance"
    private const val PAIR = "com.mojang.datafixers.util.Pair"
    private const val ITEM = "net.minecraft.world.item.Item"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val ITEM_EXTENSION = "net.neoforged.neoforge.common.extensions.IItemExtension"
    private const val ITEM_STACK_EXTENSION = "net.neoforged.neoforge.common.extensions.IItemStackExtension"
    private const val ITEM_GET = "()Lnet/minecraft/world/item/Item;"
    private const val STACK_FOOD =
        "(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/food/FoodProperties;"
    private const val ITEM_FOOD =
        "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/food/FoodProperties;"

    fun migrate(pass: StructuralRefactorPass, source: String): String {
        if (!source.contains("FoodProperties")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val variables = Regex("""\b(?:net\.minecraft\.world\.food\.)?FoodProperties\s+($ID)\s*(?=[=;,)])""")
            .findAll(masked).map { it.groupValues[1] }.toSet()
        val edits = mutableListOf<IntRange>()
        if (source.contains(".getEffects()") && variables.isNotEmpty()) {
            val names = variables.joinToString("|") { Regex.escape(it) }
            Regex("""(?<![\w$.])(?:this\.)?(?:$names)(\.getEffects\(\))""").findAll(masked).forEach { edits += it.groups[1]!!.range }
        }
        if (source.contains(".getEffects()")) {
            Regex("""\.getFoodProperties\(""").findAll(masked).forEach { call ->
                val close = JavaSourceText.matchingParen(masked, call.range.last)
                if (close < 0) return@forEach
                Regex("""^\s*\.getEffects\(\)""").find(masked.substring(close + 1))?.let {
                    edits += (close + 1 + it.range.first + it.value.indexOf('.'))..(close + 1 + it.range.last)
                }
            }
        }
        val renamed = edits.distinct().sortedByDescending { it.first }
            .fold(source) { text, range -> text.replaceRange(range, ".effects()") }
        if (!renamed.contains("Pair") || !renamed.contains(".effects()")) return renamed

        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val unit = parser.parse(renamed).result.orElse(null) ?: return renamed
        val typing = SourceValueTypes(pass, renamed)
        val lineStarts = buildList {
            add(0)
            renamed.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
        }
        LexicalPreservingPrinter.setup(unit)
        var changed = false

        for (loop in unit.findAll(ForEachStmt::class.java).toList()) {
            val variable = loop.variable.variables.singleOrNull() ?: continue
            if (!isLegacyEffectPair(variable.type as? ClassOrInterfaceType, typing)) continue
            val iterable = loop.iterable as? MethodCallExpr ?: continue
            if (iterable.nameAsString != "effects" || iterable.arguments.isNotEmpty()) continue
            val receiver = iterable.scope.orElse(null) ?: continue
            val receiverOffset = offset(receiver, lineStarts)
            if (receiverOffset < 0 ||
                !isFoodPropertiesValue(receiver, receiverOffset, typing, pass, lineStarts)
            ) continue

            val element = variable.nameAsString
            val elementReferences = loop.body.findAll(NameExpr::class.java)
                .filter { it.nameAsString == element }
            val elementCalls = elementReferences.mapNotNull { reference ->
                (reference.parentNode.orElse(null) as? MethodCallExpr)
                    ?.takeIf { it.scope.orElse(null) === reference && it.arguments.isEmpty() }
            }
            if (elementCalls.size != elementReferences.size ||
                elementCalls.any { it.nameAsString !in setOf("getFirst", "getSecond") }
            ) continue

            val firstCalls = elementCalls.filter { it.nameAsString == "getFirst" }
            val copies = firstCalls.mapNotNull { copyConstruction(it, typing) }
            if (copies.size != firstCalls.size) continue

            variable.setType(parser.parseType("$FOOD_PROPERTIES.PossibleEffect").result.orElseThrow())
            copies.forEach { it.replace(parser.parseExpression<Expression>("$element.effect()").result.orElseThrow()) }
            elementCalls.filter { it.nameAsString == "getSecond" }.forEach { it.setName("probability") }
            changed = true
        }
        return if (changed) LexicalPreservingPrinter.print(unit) else renamed
    }

    private fun isLegacyEffectPair(type: ClassOrInterfaceType?, typing: SourceValueTypes): Boolean {
        type ?: return false
        val raw = type.toString().substringBefore('<').trim()
        if (!typing.isTypeName(raw, PAIR)) return false
        val arguments = type.typeArguments.orElse(null)?.toList() ?: return false
        if (arguments.size != 2 ||
            !typing.isTypeName(arguments[0].toString(), MOB_EFFECT_INSTANCE)
        ) return false
        return arguments[1].toString() in setOf("Float", "java.lang.Float")
    }

    private fun isFoodPropertiesValue(
        node: Node,
        nodeOffset: Int,
        typing: SourceValueTypes,
        pass: StructuralRefactorPass,
        lineStarts: List<Int>
    ): Boolean {
        if (typing.typeOf(node.toString(), nodeOffset) == FOOD_PROPERTIES) return true
        val call = node as? MethodCallExpr ?: return false
        if (call.nameAsString != "getFoodProperties") return false
        val methodReceiver = call.scope.orElse(null) ?: return false
        val owner = targetExpressionType(methodReceiver, typing, pass, lineStarts)
            ?.let { platformOwner(it, pass) }
            ?: return false
        val descriptor = when {
            call.arguments.size == 1 &&
                (owner == ITEM_STACK || TargetMembers.inheritsFrom(owner, ITEM_STACK_EXTENSION)) -> STACK_FOOD
            call.arguments.size == 2 &&
                TargetMembers.inheritsFrom(owner, ITEM_EXTENSION) -> ITEM_FOOD
            else -> return false
        }
        return TargetMembers.inheritedMethod(owner, "getFoodProperties", descriptor) != null
    }

    private fun targetExpressionType(
        node: Node,
        typing: SourceValueTypes,
        pass: StructuralRefactorPass,
        lineStarts: List<Int>
    ): String? {
        val nodeOffset = offset(node, lineStarts)
        if (nodeOffset < 0) return null
        typing.typeOf(node.toString(), nodeOffset)?.let { return it }
        val call = node as? MethodCallExpr ?: return null
        if (call.nameAsString != "getItem" || call.arguments.isNotEmpty()) return null
        val receiver = call.scope.orElse(null) ?: return null
        val owner = targetExpressionType(receiver, typing, pass, lineStarts)
            ?.let { platformOwner(it, pass) }
            ?: return null
        return ITEM.takeIf { TargetMembers.inheritedMethod(owner, "getItem", ITEM_GET) != null }
    }

    private fun platformOwner(type: String, pass: StructuralRefactorPass): String? {
        var current: String? = type
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (TargetMembers.hasClass(current)) return current
            current = pass.projectValueFacts.parents[current]
        }
        return null
    }

    private fun copyConstruction(
        call: MethodCallExpr,
        typing: SourceValueTypes
    ): ObjectCreationExpr? {
        val creation = call.parentNode.orElse(null) as? ObjectCreationExpr ?: return null
        if (creation.arguments.size != 1 || creation.arguments[0] !== call) return null
        if (!typing.isTypeName(creation.type.toString(), MOB_EFFECT_INSTANCE)) return null
        return creation
    }

    private fun offset(node: Node, lineStarts: List<Int>): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)
}
