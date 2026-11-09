package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Migrates a temporary Entity.setMaxUpStep override to the 1.21.1 living step-height attribute.
 *
 * The old save/set/restore family is accepted only as a closed local transaction with the same
 * receiver and guard at both writes. The target has no mutable step height for non-living Entity
 * instances, so the mutation is applied only when the receiver is a LivingEntity, matching the
 * upstream Create 1.21.1 mechanism while leaving all movement statements intact.
 */
internal fun StructuralRefactorPass.migrateTransientStepHeightOverride(source: String): String {
    if (!source.contains("setMaxUpStep") || !source.contains("maxUpStep")) return source
    if (!targetStepHeightContract()) return source

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

    LexicalPreservingPrinter.setup(unit)
    var changed = false
    savedLoop@ for (saved in unit.findAll(VariableDeclarator::class.java).toList()) {
        if (!saved.type.isPrimitiveType ||
            saved.type.asPrimitiveType().type != PrimitiveType.Primitive.FLOAT
        ) continue
        val read = saved.initializer.orElse(null) as? MethodCallExpr ?: continue
        if (read.nameAsString != "maxUpStep" ||
            read.arguments.isNotEmpty() ||
            read.scope.isEmpty
        ) continue
        val receiver = read.scope.get() as? NameExpr ?: continue
        if (typing.typeOf(receiver.toString(), offset(read)) != ENTITY) continue
        val declaration = saved.parentNode.orElse(null) as? VariableDeclarationExpr ?: continue
        if (declaration.variables.size != 1) continue
        val declarationStatement = declaration.parentNode.orElse(null) as? ExpressionStmt
            ?: continue
        val block = declarationStatement.parentNode.orElse(null) as? BlockStmt ?: continue
        val method = declarationStatement.findAncestor(MethodDeclaration::class.java)
            .orElse(null) ?: continue
        if (method.findAll(NameExpr::class.java)
                .count { it.nameAsString == saved.nameAsString } != 1
        ) continue

        val declarationIndex = block.statements.indexOf(declarationStatement)
        if (declarationIndex < 0) continue
        val first = block.statements.asSequence()
            .drop(declarationIndex + 1)
            .mapNotNull { exactStepSetter(it, receiver.nameAsString) }
            .firstOrNull() ?: continue
        val restore = block.statements.asSequence()
            .drop(declarationIndex + 1)
            .mapNotNull { statement ->
                exactStepSetter(statement, receiver.nameAsString)?.takeIf { setter ->
                    setter.argument is NameExpr &&
                        (setter.argument as NameExpr).nameAsString == saved.nameAsString
                }
            }
            .singleOrNull() ?: continue
        if (first.statement === restore.statement ||
            first.condition.toString() != restore.condition.toString() ||
            first.argument is NameExpr &&
            (first.argument as NameExpr).nameAsString == saved.nameAsString
        ) continue
        val firstIndex = block.statements.indexOf(first.statement)
        val restoreIndex = block.statements.indexOf(restore.statement)
        if (firstIndex != declarationIndex + 1 || restoreIndex <= firstIndex) continue

        val attributeName = uniqueStepHeightName(source, "modporterStepHeight")
        val setupSource = listOf(
            "AttributeInstance $attributeName = null;",
            "double ${saved.nameAsString} = 0;",
            """
                if ((${first.condition}) && ${receiver.nameAsString} instanceof LivingEntity) {
                    $attributeName = ((LivingEntity) ${receiver.nameAsString})
                        .getAttribute(Attributes.STEP_HEIGHT);
                    if ($attributeName != null) {
                        ${saved.nameAsString} = $attributeName.getBaseValue();
                        $attributeName.setBaseValue(${first.argument});
                    }
                }
            """.trimIndent()
        )
        val setup = mutableListOf<Statement>()
        for (statementSource in setupSource) {
            val statement = parser.parseStatement(statementSource).result.orElse(null)
                ?: continue@savedLoop
            setup += statement
        }
        val restoreStatement = parser.parseStatement(
            "if ($attributeName != null) " +
                "$attributeName.setBaseValue(${saved.nameAsString});"
        ).result.orElse(null) ?: continue

        block.statements.removeAt(firstIndex)
        block.statements.removeAt(declarationIndex)
        setup.forEachIndexed { index, statement ->
            block.statements.add(declarationIndex + index, statement)
        }
        restore.statement.replace(restoreStatement)
        changed = true
    }
    if (!changed) return source

    var result = LexicalPreservingPrinter.print(unit)
    result = JavaSourceText.addImport(
        result,
        "net.minecraft.world.entity.ai.attributes.AttributeInstance"
    )
    result = JavaSourceText.addImport(
        result,
        "net.minecraft.world.entity.ai.attributes.Attributes"
    )
    result = JavaSourceText.addImport(result, "net.minecraft.world.entity.LivingEntity")
    return result
}

private data class StepSetter(
    val statement: Statement,
    val condition: com.github.javaparser.ast.expr.Expression,
    val argument: com.github.javaparser.ast.expr.Expression
)

private fun exactStepSetter(statement: Statement, receiver: String): StepSetter? {
    val conditional = statement as? IfStmt ?: return null
    if (conditional.elseStmt.isPresent) return null
    val body = when (val then = conditional.thenStmt) {
        is ExpressionStmt -> then
        is BlockStmt -> then.statements.singleOrNull() as? ExpressionStmt
        else -> null
    } ?: return null
    val call = body.expression as? MethodCallExpr ?: return null
    if (call.nameAsString != "setMaxUpStep" ||
        call.arguments.size != 1 ||
        (call.scope.orElse(null) as? NameExpr)?.nameAsString != receiver
    ) return null
    return StepSetter(statement, conditional.condition.clone(), call.arguments.single().clone())
}

private fun uniqueStepHeightName(source: String, base: String): String {
    var name = base
    while (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(source)) name += "_"
    return name
}

private const val ENTITY = "net.minecraft.world.entity.Entity"

private fun targetStepHeightContract(): Boolean =
    TargetMembers.hasMethod(ENTITY, "maxUpStep") &&
        !TargetMembers.hasMethod(ENTITY, "setMaxUpStep") &&
        TargetMembers.hasField(
            "net.minecraft.world.entity.ai.attributes.Attributes",
            "STEP_HEIGHT"
        ) &&
        TargetMembers.hasMethod(
            "net.minecraft.world.entity.ai.attributes.AttributeInstance",
            "getBaseValue"
        ) &&
        TargetMembers.hasMethod(
            "net.minecraft.world.entity.ai.attributes.AttributeInstance",
            "setBaseValue"
        )
