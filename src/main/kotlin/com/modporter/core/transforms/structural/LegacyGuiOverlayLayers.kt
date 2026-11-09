package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Adapts a source-owned Forge five-argument HUD renderer to NeoForge's LayeredDraw callback.
 *
 * The renderer method itself is retained. The new layer lambda reconstructs the old GUI, partial
 * tick and dimensions from the two target callback arguments. A sixth HolderLookup.Provider
 * parameter introduced by earlier item-render migrations is supplied by the active client level.
 */
internal fun StructuralRefactorPass.migrateLegacyGuiOverlayLayer(source: String): String {
    if (!source.contains("IGuiOverlay") || !source.contains("::")) return source
    if (!targetGuiLayerContract()) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val useSimpleLayerName = simpleLayeredDrawNameAvailable(unit)
    LexicalPreservingPrinter.setup(unit)
    var changed = false

    for (variable in unit.findAll(VariableDeclarator::class.java)) {
        val field = variable.parentNode.orElse(null) as? FieldDeclaration ?: continue
        if (field.variables.size != 1 ||
            !isLegacyGuiOverlayType(variable.type.asString(), typing, unit)
        ) {
            continue
        }
        val reference = variable.initializer.orElse(null) as? MethodReferenceExpr ?: continue
        val referenceType = (reference.scope as? TypeExpr)?.type?.asString() ?: continue
        val owner = field.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: continue
        if (owner.parentNode.orElse(null) !is CompilationUnit) continue
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val canonicalOwner =
            if (packageName.isBlank()) owner.nameAsString else "$packageName.${owner.nameAsString}"
        if (!typing.isTypeName(referenceType, canonicalOwner)) continue

        val renderer = owner.getMethodsByName(reference.identifier).singleOrNull { method ->
            method.isStatic && isLegacyOverlayRenderer(method, typing)
        } ?: continue
        val graphicsName = uniqueOverlayLambdaName(source, "modporterGraphics")
        val deltaName = uniqueOverlayLambdaName(source, "modporterDelta")
        val arguments = mutableListOf(
            "net.minecraft.client.Minecraft.getInstance().gui",
            graphicsName,
            "$deltaName.getGameTimeDeltaPartialTick(false)",
            "$graphicsName.guiWidth()",
            "$graphicsName.guiHeight()"
        )
        if (renderer.parameters.size == 6) {
            arguments +=
                "net.minecraft.client.Minecraft.getInstance().level.registryAccess()"
        }
        val lambda = parser.parseExpression<com.github.javaparser.ast.expr.Expression>(
            "($graphicsName, $deltaName) -> ${reference.scope}.${reference.identifier}(" +
                arguments.joinToString(", ") + ")"
        ).result.orElse(null) ?: continue
        val layerType = parser.parseType(
            if (useSimpleLayerName) "LayeredDraw.Layer"
            else "net.minecraft.client.gui.LayeredDraw.Layer"
        ).result.orElse(null) ?: continue

        variable.setType(layerType)
        variable.setInitializer(lambda)
        changed = true
    }
    if (!changed) return source

    var result = LexicalPreservingPrinter.print(unit)
    if (useSimpleLayerName) {
        result = JavaSourceText.addImport(result, LAYERED_DRAW)
    }
    for (legacy in LEGACY_GUI_OVERLAYS) {
        if (!Regex("""\bIGuiOverlay\b""").containsMatchIn(result)) {
            result = JavaSourceText.removeImport(result, legacy)
        }
    }
    return result
}

private const val LAYERED_DRAW = "net.minecraft.client.gui.LayeredDraw"
private const val GUI = "net.minecraft.client.gui.Gui"
private const val GUI_GRAPHICS = "net.minecraft.client.gui.GuiGraphics"
private const val HOLDER_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
private val LEGACY_GUI_OVERLAYS = setOf(
    "net.minecraftforge.client.gui.overlay.IGuiOverlay",
    "net.neoforged.neoforge.client.gui.overlay.IGuiOverlay"
)

private fun isLegacyGuiOverlayType(
    type: String,
    typing: SourceValueTypes,
    unit: CompilationUnit
): Boolean {
    if (LEGACY_GUI_OVERLAYS.any { typing.isTypeName(type, it) }) return true
    if (type != "IGuiOverlay" ||
        unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .any { it.nameAsString == "IGuiOverlay" }
    ) return false
    return unit.imports
        .filter { !it.isStatic && !it.isAsterisk && it.name.identifier == "IGuiOverlay" }
        .all { it.nameAsString in LEGACY_GUI_OVERLAYS }
}

private fun isLegacyOverlayRenderer(
    method: MethodDeclaration,
    typing: SourceValueTypes
): Boolean {
    if (!method.type.isVoidType || method.parameters.size !in 5..6) return false
    val parameters = method.parameters
    if (!typing.isTypeName(parameters[0].type.asString(), GUI) ||
        !typing.isTypeName(parameters[1].type.asString(), GUI_GRAPHICS) ||
        !parameters[2].type.isPrimitiveType ||
        parameters[2].type.asPrimitiveType().type != PrimitiveType.Primitive.FLOAT ||
        !parameters[3].type.isPrimitiveType ||
        parameters[3].type.asPrimitiveType().type != PrimitiveType.Primitive.INT ||
        !parameters[4].type.isPrimitiveType ||
        parameters[4].type.asPrimitiveType().type != PrimitiveType.Primitive.INT
    ) return false
    return parameters.size == 5 ||
        typing.isTypeName(parameters[5].type.asString(), HOLDER_PROVIDER)
}

private fun uniqueOverlayLambdaName(source: String, base: String): String {
    var name = base
    while (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(source)) name += "_"
    return name
}

private fun simpleLayeredDrawNameAvailable(unit: CompilationUnit): Boolean {
    if (unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .any { it.nameAsString == "LayeredDraw" }
    ) return false
    return unit.imports
        .filter { !it.isStatic && !it.isAsterisk && it.name.identifier == "LayeredDraw" }
        .all { it.nameAsString == LAYERED_DRAW }
}

private fun targetGuiLayerContract(): Boolean {
    val render = TargetMembers.inheritedMethod(
        "$LAYERED_DRAW\$Layer",
        "render",
        "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"
    )
    val partialTick = TargetMembers.inheritedMethod(
        "net.minecraft.client.DeltaTracker",
        "getGameTimeDeltaPartialTick",
        "(Z)F"
    )
    val guiWidth = TargetMembers.inheritedMethod(GUI_GRAPHICS, "guiWidth", "()I")
    val guiHeight = TargetMembers.inheritedMethod(GUI_GRAPHICS, "guiHeight", "()I")
    return render?.owner == "$LAYERED_DRAW\$Layer" &&
        partialTick?.owner == "net.minecraft.client.DeltaTracker" &&
        guiWidth?.owner == GUI_GRAPHICS &&
        guiHeight?.owner == GUI_GRAPHICS &&
        TargetMembers.hasField("net.minecraft.client.Minecraft", "gui") &&
        TargetMembers.hasField("net.minecraft.client.Minecraft", "level")
}
