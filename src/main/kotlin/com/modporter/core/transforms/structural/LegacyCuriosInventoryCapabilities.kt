package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Restores the Optional boundary of Curios' inventory capability.
 *
 * Forge exposed `LivingEntity.getCapability(CuriosCapability.INVENTORY)` as a
 * LazyOptional. NeoForge exposes the raw nullable handler, while Curios keeps
 * the old absence contract through `CuriosApi.getCuriosInventory`.
 */
internal fun StructuralRefactorPass.migrateLegacyCuriosInventoryCapabilities(
    source: String
): String {
    if (!source.contains("CuriosCapability.INVENTORY") ||
        !source.contains(".getCapability(")
    ) {
        return source
    }

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(
            ParserConfiguration.LanguageLevel.BLEEDING_EDGE
        )
    )
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    if (parsed.problems.isNotEmpty() ||
        !hasExactImport(unit, CURIOS_CAPABILITY)
    ) {
        return source
    }

    val typing = SourceValueTypes(this, source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, char ->
            if (char == '\n') add(index + 1)
        }
    }
    val simpleApiName = curiosApiSimpleNameIsAvailable(unit)
    val apiOwner = if (simpleApiName) "CuriosApi" else CURIOS_API

    LexicalPreservingPrinter.setup(unit)
    var changed = false
    unit.findAll(MethodCallExpr::class.java).toList().forEach { capability ->
        if (capability.nameAsString != "getCapability" ||
            capability.arguments.size != 1 ||
            !isCuriosInventory(capability.arguments.single())
        ) {
            return@forEach
        }
        val receiver = capability.scope.orElse(null) ?: return@forEach
        if (typing.typeOf(receiver.toString(), offset(capability, lineStarts)) !=
            LIVING_ENTITY
        ) {
            return@forEach
        }
        val optionalCall = capability.parentNode.orElse(null)
            as? MethodCallExpr ?: return@forEach
        if (optionalCall.scope.orElse(null) !== capability ||
            optionalCall.nameAsString !in OPTIONAL_METHODS
        ) {
            return@forEach
        }

        val replacement = parser.parseExpression<Expression>(
            "$apiOwner.getCuriosInventory(${receiver})"
        ).result.orElse(null) ?: return@forEach
        capability.replace(replacement)
        changed = true
    }
    if (!changed) return source

    var result = LexicalPreservingPrinter.print(unit)
    if (simpleApiName) {
        result = JavaSourceText.addImport(result, CURIOS_API)
    }
    result = removeUnusedSimpleImports(
        result,
        listOf(CURIOS_CAPABILITY)
    )
    val reparsed = parser.parse(result)
    return if (reparsed.result.isPresent && reparsed.problems.isEmpty()) {
        result
    } else {
        source
    }
}

private fun hasExactImport(
    unit: CompilationUnit,
    qualifiedName: String
): Boolean =
    unit.imports.any {
        !it.isStatic && !it.isAsterisk &&
            it.nameAsString == qualifiedName
    }

private fun isCuriosInventory(expression: Expression): Boolean {
    val field = expression as? FieldAccessExpr ?: return false
    val owner = field.scope as? NameExpr ?: return false
    return owner.nameAsString == "CuriosCapability" &&
        field.nameAsString == "INVENTORY"
}

private fun curiosApiSimpleNameIsAvailable(
    unit: CompilationUnit
): Boolean {
    if (unit.imports.any {
            !it.isStatic && !it.isAsterisk &&
                it.name.identifier == "CuriosApi" &&
                it.nameAsString != CURIOS_API
        }
    ) {
        return false
    }
    return unit.findAll(TypeDeclaration::class.java)
        .none { it.nameAsString == "CuriosApi" }
}

private fun offset(node: Node, lineStarts: List<Int>): Int =
    node.range.map {
        lineStarts[it.begin.line - 1] + it.begin.column - 1
    }.orElse(-1)

private const val CURIOS_API =
    "top.theillusivec4.curios.api.CuriosApi"
private const val CURIOS_CAPABILITY =
    "top.theillusivec4.curios.api.CuriosCapability"
private const val LIVING_ENTITY =
    "net.minecraft.world.entity.LivingEntity"

private val OPTIONAL_METHODS = setOf(
    "filter",
    "flatMap",
    "get",
    "ifPresent",
    "ifPresentOrElse",
    "isEmpty",
    "isPresent",
    "map",
    "orElse",
    "orElseGet",
    "orElseThrow",
    "stream"
)
