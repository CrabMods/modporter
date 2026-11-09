package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Migrates the old position-based WarningScreen footer hook to its 1.21.1 layout hook.
 *
 * The accepted family is deliberately narrow: a source-owned WarningScreen subclass with an
 * `initButtons(int)` override whose body only registers default-sized buttons. Their callbacks and
 * builder options are retained, while the target WarningScreen owns placement through the returned
 * horizontal layout.
 */
internal fun StructuralRefactorPass.migrateWarningScreenFooterLayout(source: String): String {
    if (!source.contains("initButtons") || !source.contains("WarningScreen")) return source
    if (!targetWarningScreenFooterContract()) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    LexicalPreservingPrinter.setup(unit)
    var changed = false

    for (method in unit.findAll(MethodDeclaration::class.java)) {
        if (method.nameAsString != "initButtons" ||
            !method.type.isVoidType ||
            method.parameters.size != 1 ||
            !method.parameters.single().type.isPrimitiveType ||
            method.parameters.single().type.asPrimitiveType().type != PrimitiveType.Primitive.INT ||
            method.body.isEmpty ||
            method.annotations.none { it.name.identifier == "Override" }
        ) continue
        val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: continue
        if (owner.extendedTypes.none {
                typing.isTypeName(it.nameWithScope, WARNING_SCREEN)
            }
        ) continue

        val yOffset = method.parameters.single().nameAsString
        val buttons = method.body.get().statements.map { statement ->
            val registration = (statement as? ExpressionStmt)?.expression as? MethodCallExpr
                ?: return@map null
            if (registration.nameAsString != "addRenderableWidget" ||
                registration.arguments.size != 1 ||
                registration.scope.orElse(null) !is ThisExpr
            ) return@map null
            val build = registration.arguments.single() as? MethodCallExpr ?: return@map null
            if (build.nameAsString != "build" ||
                build.arguments.isNotEmpty() ||
                build.scope.isEmpty
            ) return@map null
            val bounds = build.scope.get() as? MethodCallExpr ?: return@map null
            if (bounds.nameAsString != "bounds" ||
                bounds.arguments.size != 4 ||
                bounds.scope.isEmpty ||
                bounds.arguments[2].toString() != "150" ||
                bounds.arguments[3].toString() != "20" ||
                !bounds.arguments[0].findAll(FieldAccessExpr::class.java).any {
                    it.nameAsString == "width" && it.scope is ThisExpr
                } ||
                !bounds.arguments[1].findAll(NameExpr::class.java).any {
                    it.nameAsString == yOffset
                }
            ) return@map null
            build.clone().setScope(bounds.scope.get().clone())
        }
        if (buttons.size != 2 || buttons.any { it == null }) continue

        var footerName = "modporterFooter"
        while (Regex("""\b${Regex.escape(footerName)}\b""").containsMatchIn(source)) {
            footerName += "_"
        }
        val replacement = BlockStmt()
        replacement.addStatement(
            parser.parseStatement(
                "LinearLayout $footerName = LinearLayout.horizontal().spacing(8);"
            ).result.orElse(null) ?: continue
        )
        buttons.filterNotNull().forEach { button ->
            replacement.addStatement(
                parser.parseStatement("$footerName.addChild($button);")
                    .result.orElse(null) ?: return@forEach
            )
        }
        if (replacement.statements.size != 3) continue
        replacement.addStatement(ReturnStmt(NameExpr(footerName)))

        method.setName("addFooterButtons")
        method.setType(parser.parseType("Layout").result.orElse(null) ?: continue)
        method.parameters.clear()
        method.setBody(replacement)
        changed = true
    }
    if (!changed) return source

    var result = LexicalPreservingPrinter.print(unit)
    result = JavaSourceText.removeImport(
        result,
        "net.minecraft.client.gui.screens.multiplayer.Realms32bitWarningScreen"
    )
    result = Regex(
        """(?m)^[ \t]*\*[ \t]*@see[ \t]+Realms32bitWarningScreen[ \t]*\r?\n"""
    ).replace(result, "")
    result = JavaSourceText.addImport(result, "net.minecraft.client.gui.layouts.Layout")
    result = JavaSourceText.addImport(result, "net.minecraft.client.gui.layouts.LinearLayout")
    return result
}

private const val WARNING_SCREEN =
    "net.minecraft.client.gui.screens.multiplayer.WarningScreen"

private fun targetWarningScreenFooterContract(): Boolean {
    val footer = TargetMembers.inheritedMethod(
        WARNING_SCREEN,
        "addFooterButtons",
        "()Lnet/minecraft/client/gui/layouts/Layout;"
    )
    val horizontal = TargetMembers.inheritedMethod(
        "net.minecraft.client.gui.layouts.LinearLayout",
        "horizontal",
        "()Lnet/minecraft/client/gui/layouts/LinearLayout;"
    )
    return footer?.owner == WARNING_SCREEN &&
        footer.access == "protected" &&
        horizontal?.owner == "net.minecraft.client.gui.layouts.LinearLayout" &&
        horizontal.access == "public"
}
