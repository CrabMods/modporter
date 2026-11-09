package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/**
 * Draft for the two 1.20.1 getter-shaped APIs that became record component accessors in 1.21.1.
 *
 * This is deliberately type-bound. A same-named source class, a shadowed type name, or a receiver
 * whose source type cannot be established is left unchanged.
 */
internal fun StructuralRefactorPass.migrateExactRecordAccessors(source: String): String {
    if (!source.contains("getData") && !source.contains("getTestName")) return source
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
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)

    val migrations = listOf(
        ExactRecordAccessor(
            sourceOwner = "net.minecraft.util.random.WeightedEntry.Wrapper",
            binaryOwner = "net.minecraft.util.random.WeightedEntry\$Wrapper",
            oldName = "getData",
            newName = "data",
            descriptor = "()Ljava/lang/Object;",
            signature = "()TT;",
            fieldDescriptor = "Ljava/lang/Object;",
            fieldSignature = "TT;"
        ),
        ExactRecordAccessor(
            sourceOwner = "net.minecraft.gametest.framework.TestFunction",
            binaryOwner = "net.minecraft.gametest.framework.TestFunction",
            oldName = "getTestName",
            newName = "testName",
            descriptor = "()Ljava/lang/String;",
            signature = "",
            fieldDescriptor = "Ljava/lang/String;",
            fieldSignature = null
        )
    ).filter(::targetIsExactRecordAccessorMove)
    if (migrations.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (reference in unit.findAll(MethodReferenceExpr::class.java)) {
        val migration = migrations.singleOrNull { it.oldName == reference.identifier } ?: continue
        val scope = reference.scope
        val exactOwner =
            typing.isTypeName(scope.toString(), migration.sourceOwner) ||
                typing.typeOf(scope.toString(), offset(scope)) == migration.sourceOwner
        if (!exactOwner) continue
        reference.setIdentifier(migration.newName)
        changed = true
    }
    for (call in unit.findAll(MethodCallExpr::class.java)) {
        if (call.arguments.isNotEmpty()) continue
        val migration = migrations.singleOrNull { it.oldName == call.nameAsString } ?: continue
        val receiver = call.scope.orElse(null) ?: continue
        if (typing.typeOf(receiver.toString(), offset(receiver)) != migration.sourceOwner) continue
        call.setName(migration.newName)
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

private data class ExactRecordAccessor(
    val sourceOwner: String,
    val binaryOwner: String,
    val oldName: String,
    val newName: String,
    val descriptor: String,
    val signature: String,
    val fieldDescriptor: String,
    val fieldSignature: String?
)

private fun targetIsExactRecordAccessorMove(move: ExactRecordAccessor): Boolean {
    if (!TargetMembers.hasClass(move.binaryOwner) ||
        !TargetMembers.inheritsFrom(move.binaryOwner, "java.lang.Record") ||
        TargetMembers.declaresInHierarchy(move.binaryOwner, move.oldName, 0)
    ) return false
    val accessor = TargetMembers.inheritedMethod(move.binaryOwner, move.newName, move.descriptor)
        ?: return false
    if (accessor.owner != move.binaryOwner ||
        accessor.access != "public" ||
        accessor.signature != move.signature ||
        TargetMembers.fieldDescriptor(move.binaryOwner, move.newName) != move.fieldDescriptor
    ) return false
    return move.fieldSignature == null ||
        TargetMembers.fieldSignatures(move.binaryOwner)[move.newName] == move.fieldSignature
}
