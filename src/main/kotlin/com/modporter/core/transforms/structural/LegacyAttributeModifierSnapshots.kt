package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Materializes the removed slot-specific ItemStack attribute multimap once, before it is applied
 * temporarily to an AttributeMap. The same snapshot is removed after the intervening activation.
 *
 * This must run before migrateItemStackAttributeModifierAccess, whose residual fallback erases the
 * old EquipmentSlot argument.
 */
internal fun StructuralRefactorPass.migrateLegacyAttributeModifierSnapshots(source: String): String {
    if (!source.contains(".getAttributeModifiers(") ||
        !source.contains(".addTransientAttributeModifiers(") ||
        !source.contains(".removeAttributeModifiers(")
    ) {
        return source
    }
    if (!legacyAttributeSnapshotTargetContract()) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    if (!legacySnapshotSimpleTypeAvailable(unit, LINKED_LIST_MULTIMAP, projectValueFacts.types) ||
        !legacySnapshotSimpleTypeAvailable(unit, HOLDER, projectValueFacts.types)
    ) {
        return source
    }

    val typing = SourceValueTypes(this, source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    fun offset(node: Node): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)

    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (block in unit.findAll(BlockStmt::class.java).toList()) {
        var statementIndex = 0
        while (statementIndex < block.statements.size) {
            val declarationStatement = block.statements[statementIndex]
            val snapshot = legacyAttributeSnapshotDeclaration(
                declarationStatement,
                typing,
                ::offset,
                this
            )
            if (snapshot == null) {
                statementIndex++
                continue
            }

            val addIndex = statementIndex + 1
            val addCall = block.statements.getOrNull(addIndex)
                ?.let { legacyAttributeMapMutation(it, "addTransientAttributeModifiers", snapshot.name) }
            if (addCall == null ||
                legacySnapshotExpressionType(addCall.scope.get(), typing, ::offset) != ATTRIBUTE_MAP
            ) {
                statementIndex++
                continue
            }

            val removeCandidates = (addIndex + 1 until block.statements.size).mapNotNull { index ->
                legacyAttributeMapMutation(
                    block.statements[index],
                    "removeAttributeModifiers",
                    snapshot.name
                )?.let { index to it }
            }
            val remove = removeCandidates.singleOrNull()
            if (remove == null || remove.first <= addIndex + 1 ||
                legacySnapshotExpressionType(remove.second.scope.get(), typing, ::offset) != ATTRIBUTE_MAP ||
                addCall.scope.get().toString() != remove.second.scope.get().toString()
            ) {
                statementIndex++
                continue
            }

            val uses = block.findAll(NameExpr::class.java)
                .filter { it.nameAsString == snapshot.name }
            if (uses.size != 2 ||
                uses.none { it === addCall.arguments[0] } ||
                uses.none { it === remove.second.arguments[0] }
            ) {
                statementIndex++
                continue
            }

            snapshot.variable.setType(
                parser.parseType(
                    "LinkedListMultimap<Holder<Attribute>, AttributeModifier>"
                ).result.orElseThrow()
            )
            snapshot.variable.setInitializer(
                parser.parseExpression<Expression>("LinkedListMultimap.create()")
                    .result.orElseThrow()
            )
            block.addStatement(
                statementIndex + 1,
                parser.parseStatement(
                    "${snapshot.stack}.getAttributeModifiers()" +
                        ".forEach(${snapshot.slot}, ${snapshot.name}::put);"
                ).result.orElseThrow()
            )
            changed = true
            statementIndex = remove.first + 2
        }
    }
    if (!changed) return source

    var result = LexicalPreservingPrinter.print(unit)
    result = JavaSourceText.addImport(result, LINKED_LIST_MULTIMAP)
    result = JavaSourceText.addImport(result, HOLDER)
    val withoutMultimapImport = JavaSourceText.removeImport(result, MULTIMAP)
    if (!Regex("""\bMultimap\b""")
            .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(withoutMultimapImport))
    ) {
        result = withoutMultimapImport
    }
    return result
}

private data class LegacyAttributeSnapshot(
    val variable: VariableDeclarator,
    val name: String,
    val stack: String,
    val slot: String
)

private fun legacyAttributeSnapshotDeclaration(
    statement: Statement,
    typing: SourceValueTypes,
    offset: (Node) -> Int,
    pass: StructuralRefactorPass
): LegacyAttributeSnapshot? {
    val declaration = ((statement as? ExpressionStmt)?.expression as? VariableDeclarationExpr)
        ?: return null
    val variable = declaration.variables.singleOrNull() ?: return null
    val type = variable.type as? ClassOrInterfaceType ?: return null
    if (!typing.isTypeName(type.nameWithScope, MULTIMAP)) return null
    val typeArguments = type.typeArguments.orElse(null)?.toList() ?: return null
    if (typeArguments.size != 2 ||
        !typing.isTypeName(typeArguments[0].toString(), ATTRIBUTE) ||
        !typing.isTypeName(typeArguments[1].toString(), ATTRIBUTE_MODIFIER)
    ) {
        return null
    }

    val getter = variable.initializer.orElse(null) as? MethodCallExpr ?: return null
    if (getter.nameAsString != "getAttributeModifiers" ||
        getter.arguments.size != 1 ||
        getter.scope.isEmpty
    ) {
        return null
    }
    val stack = getter.scope.get()
    if (pass.legacySnapshotExpressionType(stack, typing, offset) != ITEM_STACK) return null
    val slot = getter.arguments[0] as? FieldAccessExpr ?: return null
    if (!typing.isTypeName(slot.scope.toString(), EQUIPMENT_SLOT) ||
        TargetMembers.fieldDescriptor(EQUIPMENT_SLOT, slot.nameAsString) !=
        "Lnet/minecraft/world/entity/EquipmentSlot;"
    ) {
        return null
    }
    return LegacyAttributeSnapshot(variable, variable.nameAsString, stack.toString(), slot.toString())
}

private fun legacyAttributeMapMutation(
    statement: Statement,
    method: String,
    snapshot: String
): MethodCallExpr? {
    val call = (statement as? ExpressionStmt)?.expression as? MethodCallExpr ?: return null
    if (call.nameAsString != method ||
        call.scope.isEmpty ||
        call.arguments.size != 1 ||
        (call.arguments[0] as? NameExpr)?.nameAsString != snapshot
    ) {
        return null
    }
    return call
}

private fun StructuralRefactorPass.legacySnapshotExpressionType(
    expression: Expression,
    typing: SourceValueTypes,
    offset: (Node) -> Int
): String? {
    val direct = offset(expression).takeIf { it >= 0 }
        ?.let { typing.typeOf(expression.toString(), it) }
    if (direct != null) return direct

    val call = expression as? MethodCallExpr ?: return null
    if (call.arguments.isNotEmpty() || call.scope.isEmpty) return null
    val owner = legacySnapshotExpressionType(call.scope.get(), typing, offset) ?: return null
    val expected = when (call.nameAsString) {
        "getMainHandItem", "getOffhandItem" -> ITEM_STACK to
            "()Lnet/minecraft/world/item/ItemStack;"
        "getAttributes" -> ATTRIBUTE_MAP to
            "()Lnet/minecraft/world/entity/ai/attributes/AttributeMap;"
        else -> return null
    }
    return legacySnapshotNoArgumentReturn(owner, call.nameAsString, expected.second)
        ?.takeIf { it == expected.first }
}

private fun StructuralRefactorPass.legacySnapshotNoArgumentReturn(
    owner: String,
    method: String,
    descriptor: String
): String? {
    var current: String? = owner
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        val ownerType = current
        val methodFacts = projectValueFacts.methods[ownerType]?.get("$method/0")
        val getterPresent = projectValueFacts.getters[ownerType]?.containsKey(method) == true
        if (methodFacts != null || getterPresent) {
            val returns = buildSet {
                methodFacts.orEmpty()
                    .filter { it.parameterTypes.isEmpty() }
                    .mapTo(this) { it.returnType }
                projectValueFacts.getters[ownerType]?.get(method)?.let(::add)
            }.filter { it.isNotBlank() }
            return returns.singleOrNull()
        }
        if (TargetMembers.hasClass(ownerType)) {
            val target = TargetMembers.inheritedMethod(ownerType, method, descriptor) ?: return null
            if (target.access != "public") return null
            return when (descriptor.substringAfter(')')) {
                "Lnet/minecraft/world/item/ItemStack;" -> ITEM_STACK
                "Lnet/minecraft/world/entity/ai/attributes/AttributeMap;" -> ATTRIBUTE_MAP
                else -> null
            }
        }
        current = projectValueFacts.parents[ownerType]
    }
    return null
}

private fun legacySnapshotSimpleTypeAvailable(
    unit: com.github.javaparser.ast.CompilationUnit,
    canonical: String,
    projectTypes: Set<String>
): Boolean {
    val simple = canonical.substringAfterLast('.')
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return false
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    if (projectTypes.any {
            it.substringAfterLast('.') == simple &&
                it.substringBeforeLast('.', "") == packageName &&
                it != canonical
        }
    ) {
        return false
    }
    val explicit = unit.imports.filter {
        !it.isStatic && !it.isAsterisk && it.name.identifier == simple
    }.map { it.nameAsString }.distinct()
    return explicit.isEmpty() || explicit.singleOrNull() == canonical
}

private fun legacyAttributeSnapshotTargetContract(): Boolean {
    val effective = TargetMembers.inheritedMethod(
        ITEM_STACK,
        "getAttributeModifiers",
        "()Lnet/minecraft/world/item/component/ItemAttributeModifiers;"
    )
    val iterate = TargetMembers.inheritedMethod(
        ITEM_ATTRIBUTE_MODIFIERS,
        "forEach",
        "(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V"
    )
    val add = TargetMembers.inheritedMethod(
        ATTRIBUTE_MAP,
        "addTransientAttributeModifiers",
        "(Lcom/google/common/collect/Multimap;)V"
    )
    val remove = TargetMembers.inheritedMethod(
        ATTRIBUTE_MAP,
        "removeAttributeModifiers",
        "(Lcom/google/common/collect/Multimap;)V"
    )
    val expectedMap =
        "Lcom/google/common/collect/Multimap<Lnet/minecraft/core/Holder<" +
            "Lnet/minecraft/world/entity/ai/attributes/Attribute;>;" +
            "Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;>;"
    return effective?.access == "public" &&
        iterate?.owner == ITEM_ATTRIBUTE_MODIFIERS &&
        iterate.access == "public" &&
        add?.owner == ATTRIBUTE_MAP &&
        add.access == "public" &&
        add.signature == "($expectedMap)V" &&
        remove?.owner == ATTRIBUTE_MAP &&
        remove.access == "public" &&
        remove.signature == "($expectedMap)V"
}

private const val MULTIMAP = "com.google.common.collect.Multimap"
private const val LINKED_LIST_MULTIMAP = "com.google.common.collect.LinkedListMultimap"
private const val HOLDER = "net.minecraft.core.Holder"
private const val ATTRIBUTE = "net.minecraft.world.entity.ai.attributes.Attribute"
private const val ATTRIBUTE_MODIFIER =
    "net.minecraft.world.entity.ai.attributes.AttributeModifier"
private const val ATTRIBUTE_MAP =
    "net.minecraft.world.entity.ai.attributes.AttributeMap"
private const val EQUIPMENT_SLOT = "net.minecraft.world.entity.EquipmentSlot"
private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
private const val ITEM_ATTRIBUTE_MODIFIERS =
    "net.minecraft.world.item.component.ItemAttributeModifiers"
