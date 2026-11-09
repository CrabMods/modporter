package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.modporter.mapping.TargetMembers

private const val HORSE = "net.minecraft.world.entity.animal.horse.Horse"
private const val ABSTRACT_HORSE = "net.minecraft.world.entity.animal.horse.AbstractHorse"
private const val MOB = "net.minecraft.world.entity.Mob"
private const val ITEM_STACK_DESCRIPTOR = "Lnet/minecraft/world/item/ItemStack;"
private const val BODY_ARMOR_GETTER_DESCRIPTOR = "()$ITEM_STACK_DESCRIPTOR"

/**
 * The 1.20.1 `Horse#getArmor()` returned the horse armor stack mirrored from horse inventory
 * slot 1 into the CHEST equipment slot. In 1.21.1 horse armor is the native BODY item, and
 * `Mob#getBodyArmorItem()` returns that exact stored ItemStack reference.
 *
 * Only the method identifier changes. Receiver evaluation/count, null failure timing, call
 * count, and returned ItemStack identity therefore remain source-equivalent.
 */
internal fun StructuralRefactorPass.migrateLegacyHorseArmorValues(source: String): String {
    if (!source.contains("getArmor")) return source
    if (!targetHasNativeHorseBodyArmorGetter()) return source
    if (hasProjectHorseArmorOverride()) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun offset(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    fun range(node: Node): IntRange? = node.range.map {
        val start = lineStarts[it.begin.line - 1] + it.begin.column - 1
        val end = lineStarts[it.end.line - 1] + it.end.column - 1
        start..end
    }.orElse(null)

    val edits = unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
        if (call.nameAsString != "getArmor" || call.arguments.isNotEmpty()) return@mapNotNull null
        val receiver = call.scope.orElse(null)
        val receiverType = when {
            receiver == null -> owningSourceType(call, unit)
            receiver is ThisExpr && !receiver.typeName.isPresent -> owningSourceType(call, unit)
            else -> typing.typeOf(receiver.toString(), offset(receiver))
        } ?: return@mapNotNull null
        if (!isSourceHorseWithoutOverride(receiverType)) return@mapNotNull null
        range(call.name)?.let { it to "getBodyArmorItem" }
    }.distinctBy { it.first }.sortedByDescending { it.first.first }

    var result = source
    for ((nameRange, replacement) in edits) {
        result = result.replaceRange(nameRange, replacement)
    }
    return result
}

private fun targetHasNativeHorseBodyArmorGetter(): Boolean {
    if (!TargetMembers.hasClass(HORSE) ||
        !TargetMembers.inheritsFrom(HORSE, ABSTRACT_HORSE) ||
        !TargetMembers.inheritsFrom(ABSTRACT_HORSE, MOB) ||
        TargetMembers.declaresInHierarchy(HORSE, "getArmor", 0) ||
        TargetMembers.fieldDescriptor(MOB, "bodyArmorItem") != ITEM_STACK_DESCRIPTOR
    ) return false

    val getter = TargetMembers.inheritedMethod(
        HORSE,
        "getBodyArmorItem",
        BODY_ARMOR_GETTER_DESCRIPTOR
    ) ?: return false
    return getter.owner == MOB && getter.access == "public" && getter.signature.isEmpty()
}

private fun StructuralRefactorPass.hasProjectHorseArmorOverride(): Boolean =
    projectValueFacts.types.any { type ->
        declaresProjectArmorGetter(type) && sourceParentReachesHorse(type)
    }

private fun StructuralRefactorPass.declaresProjectArmorGetter(type: String): Boolean =
    projectValueFacts.getters[type]?.containsKey("getArmor") == true ||
        projectValueFacts.methods[type]?.get("getArmor/0").orEmpty().isNotEmpty()

private fun StructuralRefactorPass.sourceParentReachesHorse(type: String): Boolean {
    var current = projectValueFacts.parents[type] ?: return false
    val seen = mutableSetOf<String>()
    while (seen.add(current)) {
        if (current == HORSE) return true
        if (TargetMembers.hasClass(current)) return TargetMembers.inheritsFrom(current, HORSE)
        current = projectValueFacts.parents[current] ?: return false
    }
    return false
}

private fun StructuralRefactorPass.isSourceHorseWithoutOverride(type: String): Boolean {
    var current = type
    val seen = mutableSetOf<String>()
    while (seen.add(current)) {
        if (current == HORSE) return true
        if (TargetMembers.hasClass(current)) return TargetMembers.inheritsFrom(current, HORSE)
        if (current !in projectValueFacts.types || declaresProjectArmorGetter(current)) return false
        current = projectValueFacts.parents[current] ?: return false
    }
    return false
}

private fun owningSourceType(node: Node, unit: CompilationUnit): String? {
    val names = mutableListOf<String>()
    var current: Node? = node.parentNode.orElse(null)
    while (current != null && current !== unit) {
        if (current is TypeDeclaration<*>) names += current.nameAsString
        current = current.parentNode.orElse(null)
    }
    if (current !== unit || names.isEmpty()) return null
    val nested = names.asReversed().joinToString(".")
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return if (packageName.isBlank()) nested else "$packageName.$nested"
}
