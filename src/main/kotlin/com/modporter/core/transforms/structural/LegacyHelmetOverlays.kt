package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/**
 * IClientItemExtensions now supplies GuiGraphics and DeltaTracker directly. Preserve the legacy
 * full-screen texture algorithm instead of reaching into Gui's removed fields/private helper.
 */
internal fun StructuralRefactorPass.migrateLegacyHelmetOverlayRendering(source: String): String {
    if (!source.contains("renderHelmetOverlay") ||
        !source.contains("IClientItemExtensions") ||
        !source.contains("renderTextureOverlay(")
    ) return source

    val executable = maskJavaCommentsAndLiterals(source)
    val header = Regex(
        """(?m)^([ \t]*)public\s+void\s+renderHelmetOverlay\s*\(\s*(?:net\.minecraft\.world\.item\.)?ItemStack\s+([A-Za-z_$][\w$]*)\s*,\s*(?:net\.minecraft\.world\.entity\.player\.)?Player\s+([A-Za-z_$][\w$]*)\s*,\s*int\s+([A-Za-z_$][\w$]*)\s*,\s*int\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    ).find(executable) ?: return source
    val openBrace = executable.indexOf('{', header.range.first)
    val closeBrace = findMatchingBrace(executable, openBrace)
    if (openBrace < 0 || closeBrace < 0) return source

    val indent = header.groupValues[1]
    val stack = header.groupValues[2]
    val player = header.groupValues[3]
    val width = header.groupValues[4]
    val height = header.groupValues[5]
    val partialTick = header.groupValues[6]
    val bodyIndent = "$indent\t"
    var body = source.substring(openBrace + 1, closeBrace)
    body = replaceExecutableRegex(body, Regex("""(?m)^[ \t]*screenWidth\s*=\s*${Regex.escape(width)}\s*;\s*\r?\n?""")) { "" }
    body = replaceExecutableRegex(body, Regex("""(?m)^[ \t]*screenHeight\s*=\s*${Regex.escape(height)}\s*;\s*\r?\n?""")) { "" }
    body = rewriteExecutableJavaInvocationWithOffset(body, "renderTextureOverlay") { args, _ ->
        if (args.size != 3) return@rewriteExecutableJavaInvocationWithOffset null
        val texture = args[1].trim()
        val alpha = args[2].trim()
        """RenderSystem.disableDepthTest();
${bodyIndent}RenderSystem.depthMask(false);
${bodyIndent}RenderSystem.enableBlend();
${bodyIndent}guiGraphics.setColor(1.0F, 1.0F, 1.0F, $alpha);
${bodyIndent}guiGraphics.blit($texture, 0, 0, -90, 0.0F, 0.0F, guiGraphics.guiWidth(), guiGraphics.guiHeight(), guiGraphics.guiWidth(), guiGraphics.guiHeight());
${bodyIndent}RenderSystem.disableBlend();
${bodyIndent}RenderSystem.depthMask(true);
${bodyIndent}RenderSystem.enableDepthTest();
${bodyIndent}guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F)"""
    }
    if (Regex("""(?<![\w$])${Regex.escape(partialTick)}(?![\w$])""")
            .containsMatchIn(maskJavaCommentsAndLiterals(body))) {
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        body = "$newline${bodyIndent}float $partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);" + body
    }

    val method = buildString {
        append(indent)
        append("public void renderHelmetOverlay(ItemStack ")
        append(stack)
        append(", Player ")
        append(player)
        append(", GuiGraphics guiGraphics, DeltaTracker deltaTracker) {")
        append(body)
        append('}')
    }
    var result = source.substring(0, header.range.first) + method + source.substring(closeBrace + 1)
    if (Regex("""\bextends\s+(?:net\.minecraft\.client\.gui\.)?Gui\b""")
            .containsMatchIn(maskJavaCommentsAndLiterals(result))) {
        result = rewriteExecutableJavaInvocationArgumentsWithOffset(result, "super") { args, _ ->
            if (args.size == 2 &&
                args[0].contains("Minecraft.getInstance()") &&
                args[1].contains(".getItemRenderer()")
            ) args.take(1) else null
        }
    }
    result = addExecutableImportIfMissing(result, "net.minecraft.client.DeltaTracker")
    result = addExecutableImportIfMissing(result, "com.mojang.blaze3d.systems.RenderSystem")
    return removeObsoleteCreateCardboardOverlayGuiBase(result)
}

/**
 * The legacy Create item callback is invoked by NeoForge's early client-extension scan. Once the
 * overlay renderer above no longer needs Gui state, retaining a Gui superclass constructs a second
 * ChatComponent before Minecraft.commandHistory exists. Remove that base only for the exact,
 * project-inventoried Create overlay shape.
 */
private fun StructuralRefactorPass.removeObsoleteCreateCardboardOverlayGuiBase(
    source: String
): String {
    if (!source.contains(CARDBOARD_OVERLAY_SIMPLE) ||
        !source.contains("IClientItemExtensions") ||
        !source.contains("extends Gui")
    ) {
        return source
    }

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parsed = parser.parse(source)
    if (!parsed.isSuccessful) return source
    val unit = parsed.result.orElse(null) ?: return source
    if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != CARDBOARD_OVERLAY_PACKAGE) {
        return source
    }
    val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration ?: return source
    if (owner.nameAsString != CARDBOARD_OVERLAY_SIMPLE ||
        owner.isInterface ||
        owner.isAbstract ||
        !owner.isPublic ||
        owner.typeParameters.isNotEmpty() ||
        owner.extendedTypes.size != 1 ||
        owner.implementedTypes.size != 1 ||
        owner.members.any { it is InitializerDeclaration }
    ) {
        return source
    }

    val typing = SourceValueTypes(this, source)
    if (!typing.isTypeName(owner.extendedTypes.single().toString(), GUI) ||
        !typing.isTypeName(owner.implementedTypes.single().toString(), CLIENT_ITEM_EXTENSIONS)
    ) {
        return source
    }
    if (CARDBOARD_OVERLAY_CANONICAL !in projectValueFacts.types ||
        projectValueFacts.hasDescendantOf(CARDBOARD_OVERLAY_CANONICAL)
    ) {
        return source
    }

    val constructor = owner.constructors.singleOrNull() ?: return source
    if (!constructor.isPublic ||
        constructor.parameters.isNotEmpty() ||
        constructor.typeParameters.isNotEmpty() ||
        constructor.thrownExceptions.isNotEmpty() ||
        constructor.annotations.isNotEmpty() ||
        constructor.body.statements.size != 1
    ) {
        return source
    }
    val superCall = constructor.body.statements.single()
        as? ExplicitConstructorInvocationStmt ?: return source
    if (superCall.isThis ||
        superCall.expression.isPresent ||
        superCall.arguments.size != 1
    ) {
        return source
    }
    val getMinecraft = superCall.arguments.single() as? MethodCallExpr ?: return source
    val minecraftScope = getMinecraft.scope.orElse(null) as? NameExpr ?: return source
    if (getMinecraft.nameAsString != "getInstance" ||
        getMinecraft.arguments.isNotEmpty() ||
        !typing.isTypeName(minecraftScope.nameAsString, MINECRAFT)
    ) {
        return source
    }

    if (owner.fields.any { !it.isStatic }) return source
    val render = owner.methods.filterNot { it.isStatic }.singleOrNull() ?: return source
    if (render.nameAsString != "renderHelmetOverlay" ||
        !render.isPublic ||
        !render.type.isVoidType ||
        render.typeParameters.isNotEmpty() ||
        render.parameters.size != 4 ||
        render.annotations.none { it.name.identifier == "Override" } ||
        !typing.isTypeName(render.parameters[0].type.toString(), ITEM_STACK) ||
        !typing.isTypeName(render.parameters[1].type.toString(), PLAYER) ||
        !typing.isTypeName(render.parameters[2].type.toString(), GUI_GRAPHICS) ||
        !typing.isTypeName(render.parameters[3].type.toString(), DELTA_TRACKER)
    ) {
        return source
    }

    if (owner.findAll(ThisExpr::class.java).isNotEmpty() ||
        owner.findAll(SuperExpr::class.java).isNotEmpty() ||
        owner.findAll(MethodCallExpr::class.java).any { it.scope.isEmpty } ||
        owner.findAll(FieldAccessExpr::class.java).any {
            it.scope is ThisExpr || it.scope is SuperExpr
        } ||
        owner.hasUnresolvedInstanceValueName()
    ) {
        return source
    }

    LexicalPreservingPrinter.setup(unit)
    owner.extendedTypes.clear()
    if (!constructor.remove()) return source
    return removeUnusedSimpleImports(
        LexicalPreservingPrinter.print(unit),
        listOf(GUI)
    )
}

private fun ProjectValueFacts.hasDescendantOf(parent: String): Boolean =
    parents.keys.any { child ->
        val seen = hashSetOf<String>()
        var current = parents[child]
        while (current != null && seen.add(current)) {
            if (current == parent) return@any true
            current = parents[current]
        }
        false
    }

/**
 * Every value name in the exact overlay must be locally declared, a field owned by the overlay, or
 * a capitalized type qualifier. This rejects unqualified inherited fields such as Gui.minecraft,
 * screenWidth, or screenHeight, and the unscoped-call check rejects inherited methods.
 */
private fun ClassOrInterfaceDeclaration.hasUnresolvedInstanceValueName(): Boolean {
    val declared = buildSet {
        fields.flatMapTo(this) { field -> field.variables.map { it.nameAsString } }
        methods.flatMapTo(this) { method -> method.parameters.map { it.nameAsString } }
        findAll(VariableDeclarator::class.java).mapTo(this) { it.nameAsString }
    }
    return findAll(NameExpr::class.java).any { expression ->
        val name = expression.nameAsString
        if (name in declared) return@any false
        if (name.firstOrNull()?.isUpperCase() != true) return@any true
        when (val parent = expression.parentNode.orElse(null)) {
            is MethodCallExpr -> parent.scope.orElse(null) === expression
            is FieldAccessExpr -> parent.scope === expression
            else -> false
        }.not()
    }
}

private const val CARDBOARD_OVERLAY_PACKAGE =
    "com.simibubi.create.content.equipment.armor"
private const val CARDBOARD_OVERLAY_SIMPLE = "CardboardArmorStealthOverlay"
private const val CARDBOARD_OVERLAY_CANONICAL =
    "$CARDBOARD_OVERLAY_PACKAGE.$CARDBOARD_OVERLAY_SIMPLE"
private const val GUI = "net.minecraft.client.gui.Gui"
private const val GUI_GRAPHICS = "net.minecraft.client.gui.GuiGraphics"
private const val MINECRAFT = "net.minecraft.client.Minecraft"
private const val DELTA_TRACKER = "net.minecraft.client.DeltaTracker"
private const val PLAYER = "net.minecraft.world.entity.player.Player"
private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
private const val CLIENT_ITEM_EXTENSIONS =
    "net.neoforged.neoforge.client.extensions.common.IClientItemExtensions"
