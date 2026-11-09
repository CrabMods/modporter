package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/**
 * Repairs source-proven client and base API shapes that can only be recognized after earlier
 * structural passes have introduced provider parameters or renamed the legacy call.
 *
 * Every rewrite retains the source receiver, argument order and lazy callback boundary. The
 * method is deliberately late-stage: provider propagation must run before the overlay arity
 * repair, while config-screen migration must run before the supplier overload is disambiguated.
 */
internal fun StructuralRefactorPass.migrateLegacyClientBaseApiCompat(
    source: String
): String {
    var result = source
    result = migrateAbstractSimiWidgetScrollOverride(result)
    result = migrateInheritedMinecraftTooltipContext(result)
    result = migrateShoppingListTooltipLevel(result)
    result = migrateDeployerConnectionCookie(result)
    result = migrateDelegatingRegistryConstructor(result)
    result = migrateProviderExtendedOverlayCall(result)
    result = migrateMinecraftBlueprintRecipeProvider(result)
    result = migrateRunningGoalStreams(result)
    result = migrateExplicitSpawnPlacementChecks(result)
    result = migrateAmbientOcclusionOverrides(result)
    result = migrateMinecraftDebugOverlayAccess(result)
    result = migrateVec3VertexSubmissions(result)
    result = migrateVertexFormatIntegerStride(result)
    result = migrateSharedVanillaPanoramaAccess(result)
    result = migrateConfigScreenSupplierOverload(result)
    return result
}

private const val ABSTRACT_SIMI_WIDGET =
    "net.createmod.catnip.gui.widget.AbstractSimiWidget"
private const val CLIENT_MINECRAFT =
    "net.minecraft.client.Minecraft"
private const val HOLDER_LOOKUP_PROVIDER =
    "net.minecraft.core.HolderLookup.Provider"
private const val GUI = "net.minecraft.client.gui.Gui"
private const val GUI_GRAPHICS = "net.minecraft.client.gui.GuiGraphics"
private const val MODEL_DATA =
    "net.neoforged.neoforge.client.model.data.ModelData"
private const val BLOCK_STATE =
    "net.minecraft.world.level.block.state.BlockState"
private const val RENDER_TYPE =
    "net.minecraft.client.renderer.RenderType"
private const val I_CONFIG_SCREEN_FACTORY =
    "net.neoforged.neoforge.client.gui.IConfigScreenFactory"
private const val MOD_CONTAINER = "net.neoforged.fml.ModContainer"
private const val MOD_LOADING_CONTEXT = "net.neoforged.fml.ModLoadingContext"
private const val ITEM_TOOLTIP_CONTEXT =
    "net.minecraft.world.item.Item.TooltipContext"
private const val LEVEL_ACCESSOR = "net.minecraft.world.level.LevelAccessor"
private const val BLOCK_POS = "net.minecraft.core.BlockPos"
private const val FAKE_PLAYER =
    "net.neoforged.neoforge.common.util.FakePlayer"
private const val SERVER_GAME_PACKET_LISTENER =
    "net.minecraft.server.network.ServerGamePacketListenerImpl"
private const val MINECRAFT_SERVER = "net.minecraft.server.MinecraftServer"
private const val SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer"
private const val CONNECTION = "net.minecraft.network.Connection"
private const val COMMON_LISTENER_COOKIE =
    "net.minecraft.server.network.CommonListenerCookie"
private const val SHOPPING_LIST_PACKAGE =
    "com.simibubi.create.content.logistics.tableCloth"
private const val DEPLOYER_PACKAGE =
    "com.simibubi.create.content.kinetics.deployer"

private data class ParsedClientBaseSource(
    val parser: JavaParser,
    val unit: CompilationUnit,
    val lineStarts: List<Int>
) {
    fun offset(node: Node): Int = node.range.map {
        lineStarts[it.begin.line - 1] + it.begin.column - 1
    }.orElse(-1)

    fun range(node: Node): IntRange? = node.range.map {
        val start = lineStarts[it.begin.line - 1] + it.begin.column - 1
        val end = lineStarts[it.end.line - 1] + it.end.column - 1
        start..end
    }.orElse(null)
}

private fun parseClientBaseSource(source: String): ParsedClientBaseSource? {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(
            ParserConfiguration.LanguageLevel.BLEEDING_EDGE
        )
    )
    val unit = parser.parse(source).result.orElse(null) ?: return null
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    return ParsedClientBaseSource(parser, unit, lineStarts)
}

private fun StructuralRefactorPass.migrateAbstractSimiWidgetScrollOverride(
    source: String
): String {
    if (!source.contains("mouseScrolled") ||
        !source.contains("AbstractSimiWidget")
    ) {
        return source
    }
    val parsed = parseClientBaseSource(source) ?: return source
    val typing = SourceValueTypes(this, source)
    LexicalPreservingPrinter.setup(parsed.unit)
    var changed = false
    for (method in parsed.unit.findAll(MethodDeclaration::class.java)) {
        if (method.nameAsString != "mouseScrolled" ||
            method.parameters.size != 3 ||
            method.annotations.none { it.nameAsString == "Override" } ||
            method.parameters.any {
                !it.type.isPrimitiveType ||
                    it.type.asPrimitiveType().type !=
                    PrimitiveType.Primitive.DOUBLE
            }
        ) {
            continue
        }
        val owner = method.findAncestor(
            ClassOrInterfaceDeclaration::class.java
        ).orElse(null) ?: continue
        if (owner.extendedTypes.none {
                typing.isTypeName(it.nameWithScope, ABSTRACT_SIMI_WIDGET)
            }
        ) {
            continue
        }
        val names = method.parameters.map { it.nameAsString }.toSet()
        var horizontal = "scrollX"
        while (horizontal in names) horizontal += "Horizontal"
        method.parameters.add(
            2,
            Parameter(PrimitiveType.doubleType(), horizontal)
        )
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(parsed.unit) else source
}

private fun StructuralRefactorPass.migrateInheritedMinecraftTooltipContext(
    source: String
): String {
    if (!source.contains("getTooltipLines(") ||
        !source.contains("minecraft.player")
    ) {
        return source
    }
    var changed = false
    val migrated = rewriteExecutableJavaCall(
        source,
        "getTooltipLines"
    ) { receiver, args ->
        if (args.size != 2) return@rewriteExecutableJavaCall null
        val player = args[0].trim()
        if (player !in setOf("minecraft.player", "this.minecraft.player")) {
            return@rewriteExecutableJavaCall null
        }
        changed = true
        val client = player.removeSuffix(".player")
        "$receiver.getTooltipLines(" +
            "Item.TooltipContext.of($client.level), " +
            "$player, ${args[1].trim()})"
    }
    return if (changed) {
        addExecutableImportIfMissing(migrated, "net.minecraft.world.item.Item")
    } else {
        source
    }
}

private fun StructuralRefactorPass.migrateShoppingListTooltipLevel(
    source: String
): String {
    if (!source.contains("class ShoppingListItem") ||
        !source.contains("bakeEntries(") ||
        !source.contains("Item.TooltipContext")
    ) {
        return source
    }
    val parsed = parseClientBaseSource(source) ?: return source
    if (parsed.unit.packageDeclaration.map { it.nameAsString }.orElse("") !=
        SHOPPING_LIST_PACKAGE
    ) {
        return source
    }
    val typing = SourceValueTypes(this, source)
    val owner = parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)
        .singleOrNull {
            it.nameAsString == "ShoppingListItem" &&
                it.parentNode.orElse(null) === parsed.unit
        } ?: return source
    val listType = parsed.unit.findAll(RecordDeclaration::class.java)
        .singleOrNull {
            it.nameAsString == "ShoppingList" &&
                it.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null) === owner
        } ?: return source
    val bakeEntries = listType.getMethodsByName("bakeEntries")
        .singleOrNull {
            it.parameters.size == 2 &&
                typing.isTypeName(
                    it.parameters[0].typeAsString,
                    LEVEL_ACCESSOR
                ) &&
                typing.isTypeName(
                    it.parameters[1].typeAsString,
                    BLOCK_POS
                )
        } ?: return source
    if (bakeEntries.type.isVoidType) return source
    val append = owner.getMethodsByName("appendHoverText")
        .singleOrNull {
            it.annotations.any { annotation ->
                annotation.nameAsString == "Override"
            } &&
                it.parameters.size == 4 &&
                typing.isTypeName(
                    it.parameters[1].typeAsString,
                    ITEM_TOOLTIP_CONTEXT
                )
        } ?: return source
    val contextName = append.parameters[1].nameAsString
    LexicalPreservingPrinter.setup(parsed.unit)
    var changed = false
    for (call in append.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != "bakeEntries" ||
            call.arguments.size != 2 ||
            call.arguments[0] !is NameExpr ||
            call.arguments[0].asNameExpr().nameAsString != contextName ||
            call.arguments[1] !is NullLiteralExpr
        ) {
            continue
        }
        val receiver = call.scope.orElse(null) as? NameExpr ?: continue
        val declaredList = append.findAll(VariableDeclarator::class.java)
            .singleOrNull {
                it.nameAsString == receiver.nameAsString &&
                    typing.isTypeName(
                        it.typeAsString,
                        "$SHOPPING_LIST_PACKAGE.ShoppingListItem.ShoppingList"
                    )
            } ?: continue
        if (!declaredList.initializer.isPresent) continue
        call.setArgument(
            0,
            MethodCallExpr(NameExpr(contextName), "level")
        )
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(parsed.unit) else source
}

private fun StructuralRefactorPass.migrateDeployerConnectionCookie(
    source: String
): String {
    if (!source.contains("class DeployerFakePlayer") ||
        !source.contains("class FakePlayNetHandler") ||
        !source.contains("ServerGamePacketListenerImpl")
    ) {
        return source
    }
    val parsed = parseClientBaseSource(source) ?: return source
    if (parsed.unit.packageDeclaration.map { it.nameAsString }.orElse("") !=
        DEPLOYER_PACKAGE
    ) {
        return source
    }
    val typing = SourceValueTypes(this, source)
    val owner = parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)
        .singleOrNull {
            it.nameAsString == "DeployerFakePlayer" &&
                it.parentNode.orElse(null) === parsed.unit &&
                it.extendedTypes.singleOrNull()?.let { parent ->
                    typing.isTypeName(parent.nameWithScope, FAKE_PLAYER)
                } == true
        } ?: return source
    val networkManager = owner.fields
        .flatMap { it.variables }
        .singleOrNull {
            it.nameAsString == "NETWORK_MANAGER" &&
                typing.isTypeName(it.typeAsString, CONNECTION)
        } ?: return source
    val handler = owner.members
        .filterIsInstance<ClassOrInterfaceDeclaration>()
        .singleOrNull {
            it.nameAsString == "FakePlayNetHandler" &&
                it.extendedTypes.singleOrNull()?.let { parent ->
                    typing.isTypeName(
                        parent.nameWithScope,
                        SERVER_GAME_PACKET_LISTENER
                    )
                } == true
        } ?: return source
    val constructor = handler.constructors.singleOrNull {
        it.parameters.size == 2 &&
            typing.isTypeName(
                it.parameters[0].typeAsString,
                MINECRAFT_SERVER
            ) &&
            typing.isTypeName(
                it.parameters[1].typeAsString,
                SERVER_PLAYER
            )
    } ?: return source
    val invocation = constructor.body.statements.firstOrNull()
        as? ExplicitConstructorInvocationStmt ?: return source
    if (invocation.isThis ||
        invocation.expression.isPresent ||
        invocation.arguments.size != 3
    ) {
        return source
    }
    val serverName = constructor.parameters[0].nameAsString
    val playerName = constructor.parameters[1].nameAsString
    val arguments = invocation.arguments
    if ((arguments[0] as? NameExpr)?.nameAsString != serverName ||
        (arguments[1] as? NameExpr)?.nameAsString !=
        networkManager.nameAsString ||
        (arguments[2] as? NameExpr)?.nameAsString != playerName
    ) {
        return source
    }
    val cookie = parsed.parser.parseExpression<Expression>(
        "CommonListenerCookie.createInitial(" +
            "$playerName.getGameProfile(), false)"
    ).result.orElse(null) ?: return source
    LexicalPreservingPrinter.setup(parsed.unit)
    invocation.arguments.add(cookie)
    return addExecutableImportIfMissing(
        LexicalPreservingPrinter.print(parsed.unit),
        COMMON_LISTENER_COOKIE
    )
}

private fun StructuralRefactorPass.migrateDelegatingRegistryConstructor(
    source: String
): String {
    if (!source.contains("HolderLookup.Provider") ||
        !source.contains("this(") ||
        !source.contains("Minecraft")
    ) {
        return source
    }
    val parsed = parseClientBaseSource(source) ?: return source
    val typing = SourceValueTypes(this, source)
    LexicalPreservingPrinter.setup(parsed.unit)
    var changed = false
    for (constructor in parsed.unit.findAll(ConstructorDeclaration::class.java)) {
        if (constructor.parameters.size != 7) continue
        val owner = constructor.findAncestor(
            ClassOrInterfaceDeclaration::class.java
        ).orElse(null) ?: continue
        val target = owner.constructors.singleOrNull {
            it.parameters.size == 9 &&
                typing.isTypeName(
                    it.parameters.last().type.asString(),
                    HOLDER_LOOKUP_PROVIDER
                )
        } ?: continue
        if (target.nameAsString != constructor.nameAsString) continue
        val invocation = constructor.body.statements.firstOrNull()
            ?.takeIf { it.isExplicitConstructorInvocationStmt }
            ?.asExplicitConstructorInvocationStmt()
            ?: continue
        if (!invocation.isThis ||
            invocation.arguments.size != 8 ||
            invocation.arguments.last() !is NullLiteralExpr
        ) {
            continue
        }
        val provider = parsed.parser.parseExpression<Expression>(
            "Minecraft.getInstance().level.registryAccess()"
        ).result.orElse(null) ?: continue
        invocation.arguments.add(provider)
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(parsed.unit) else source
}

private fun StructuralRefactorPass.migrateProviderExtendedOverlayCall(
    source: String
): String {
    if (!source.contains("LayeredDraw.Layer") ||
        !source.contains("HolderLookup.Provider") ||
        !source.contains("renderOverlay")
    ) {
        return source
    }
    val parsed = parseClientBaseSource(source) ?: return source
    val typing = SourceValueTypes(this, source)
    LexicalPreservingPrinter.setup(parsed.unit)
    var changed = false
    for (variable in parsed.unit.findAll(VariableDeclarator::class.java)) {
        if (!variable.type.asString().endsWith("LayeredDraw.Layer")) continue
        val lambda = variable.initializer.orElse(null) as? LambdaExpr ?: continue
        val owner = variable.findAncestor(
            ClassOrInterfaceDeclaration::class.java
        ).orElse(null) ?: continue
        val call = lambda.findAll(MethodCallExpr::class.java).singleOrNull {
            it.arguments.size == 5
        } ?: continue
        val renderer = owner.getMethodsByName(call.nameAsString).singleOrNull {
            it.isStatic &&
                it.parameters.size == 6 &&
                it.type.isVoidType &&
                typing.isTypeName(it.parameters[0].type.asString(), GUI) &&
                typing.isTypeName(
                    it.parameters[1].type.asString(),
                    GUI_GRAPHICS
                ) &&
                typing.isTypeName(
                    it.parameters.last().type.asString(),
                    HOLDER_LOOKUP_PROVIDER
                )
        } ?: continue
        if (renderer.nameAsString != call.nameAsString) continue
        val provider = parsed.parser.parseExpression<Expression>(
            "net.minecraft.client.Minecraft.getInstance()" +
                ".level.registryAccess()"
        ).result.orElse(null) ?: continue
        call.arguments.add(provider)
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(parsed.unit) else source
}

private fun StructuralRefactorPass.migrateMinecraftBlueprintRecipeProvider(
    source: String
): String {
    if (!source.contains(".sectionAt.getBlueprintWorld().registryAccess()")) {
        return source
    }
    val typing = SourceValueTypes(this, source)
    return rewriteExecutableJavaCallWithOffset(
        source,
        "registryAccess"
    ) { receiver, args, offset ->
        if (args.isNotEmpty()) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        val minecraft = Regex(
            """^([A-Za-z_$][\w$]*)\.sectionAt\.getBlueprintWorld\(\)$"""
        ).matchEntire(receiver.trim())
            ?.groupValues
            ?.get(1)
            ?: return@rewriteExecutableJavaCallWithOffset null
        if (typing.typeOf(minecraft, offset) != CLIENT_MINECRAFT) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        "$minecraft.level.registryAccess()"
    }
}

private fun StructuralRefactorPass.migrateRunningGoalStreams(
    source: String
): String {
    if (!source.contains(".getRunningGoals()")) return source
    var runningGoal = "modporterRunningGoal"
    while (Regex("""\b${Regex.escape(runningGoal)}\b""")
            .containsMatchIn(source)
    ) {
        runningGoal += "_"
    }
    return rewriteExecutableJavaCall(
        source,
        "getRunningGoals"
    ) { receiver, args ->
        if (args.isNotEmpty() ||
            !receiver.trim().endsWith(".targetSelector")
        ) {
            return@rewriteExecutableJavaCall null
        }
        "$receiver.getAvailableGoals().stream()" +
            ".filter($runningGoal -> $runningGoal.isRunning())"
    }
}

private fun StructuralRefactorPass.migrateExplicitSpawnPlacementChecks(
    source: String
): String {
    if (!source.contains("NaturalSpawner.isSpawnPositionOk(") ||
        !source.contains("SpawnPlacementTypes.")
    ) {
        return source
    }
    return rewriteExecutableJavaCall(
        source,
        "isSpawnPositionOk"
    ) { receiver, args ->
        if (receiver != "NaturalSpawner" || args.size != 4) {
            return@rewriteExecutableJavaCall null
        }
        val placement = args[0].trim()
        if (!Regex("""SpawnPlacementTypes\.[A-Z][A-Z0-9_]*""")
                .matches(placement)
        ) {
            return@rewriteExecutableJavaCall null
        }
        "$placement.isSpawnPositionOk(" +
            args.drop(1).joinToString(", ") { it.trim() } +
            ")"
    }
}

private fun StructuralRefactorPass.migrateAmbientOcclusionOverrides(
    source: String
): String {
    if (!source.contains("useAmbientOcclusion") ||
        !source.contains("ModelData") ||
        !source.contains("RenderType")
    ) {
        return source
    }
    val parsed = parseClientBaseSource(source) ?: return source
    val typing = SourceValueTypes(this, source)
    LexicalPreservingPrinter.setup(parsed.unit)
    var changed = false
    for (owner in parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
        val renderAware = owner.methods.singleOrNull {
            it.nameAsString == "useAmbientOcclusion" &&
                it.type.isPrimitiveType &&
                it.type.asPrimitiveType().type ==
                PrimitiveType.Primitive.BOOLEAN &&
                it.parameters.size == 2 &&
                typing.isTypeName(
                    it.parameters[0].type.asString(),
                    BLOCK_STATE
                ) &&
                typing.isTypeName(
                    it.parameters[1].type.asString(),
                    RENDER_TYPE
                ) &&
                it.annotations.any { annotation ->
                    annotation.nameAsString == "Override"
                }
        } ?: continue
        val stateOnly = owner.methods.singleOrNull {
            it.nameAsString == "useAmbientOcclusion" &&
                it.type.isPrimitiveType &&
                it.type.asPrimitiveType().type ==
                PrimitiveType.Primitive.BOOLEAN &&
                it.parameters.size == 1 &&
                typing.isTypeName(
                    it.parameters[0].type.asString(),
                    BLOCK_STATE
                ) &&
                it.annotations.any { annotation ->
                    annotation.nameAsString == "Override"
                }
        } ?: continue
        val noArgument = owner.methods.singleOrNull {
            it.nameAsString == "useAmbientOcclusion" &&
                it.type.isPrimitiveType &&
                it.type.asPrimitiveType().type ==
                PrimitiveType.Primitive.BOOLEAN &&
                it.parameters.isEmpty() &&
                it.annotations.any { annotation ->
                    annotation.nameAsString == "Override"
                }
        } ?: continue
        val renderExpression = singleReturnExpression(renderAware) ?: continue
        val stateExpression = singleReturnExpression(stateOnly) ?: continue
        val baseExpression = singleReturnExpression(noArgument) ?: continue
        if (renderExpression != stateExpression ||
            stateExpression != baseExpression
        ) {
            continue
        }
        var dataName = "data"
        val parameterNames = renderAware.parameters.map {
            it.nameAsString
        }.toSet()
        while (dataName in parameterNames) dataName += "_"
        renderAware.parameters.add(
            1,
            Parameter().setType("ModelData").setName(dataName)
        )
        renderAware.setType(
            parsed.parser.parseType("TriState").result.orElseThrow()
        )
        val body = parsed.parser.parseBlock(
            "{ return ($renderExpression) ? " +
                "TriState.TRUE : TriState.FALSE; }"
        ).result.orElse(null) ?: continue
        renderAware.setBody(body)
        stateOnly.annotations.removeIf {
            it.nameAsString == "Override"
        }
        changed = true
    }
    if (!changed) return source
    var result = LexicalPreservingPrinter.print(parsed.unit)
    result = addExecutableImportIfMissing(result, MODEL_DATA)
    return addExecutableImportIfMissing(
        result,
        "net.neoforged.neoforge.common.util.TriState"
    )
}

private fun StructuralRefactorPass.migrateMinecraftDebugOverlayAccess(
    source: String
): String {
    if (!source.contains(".options.renderDebug")) return source
    val parsed = parseClientBaseSource(source) ?: return source
    val typing = SourceValueTypes(this, source)
    val edits = parsed.unit.findAll(FieldAccessExpr::class.java).mapNotNull {
        renderDebug ->
        if (renderDebug.nameAsString != "renderDebug") {
            return@mapNotNull null
        }
        val options = renderDebug.scope as? FieldAccessExpr
            ?: return@mapNotNull null
        if (options.nameAsString != "options") return@mapNotNull null
        val getInstance = options.scope as? MethodCallExpr
            ?: return@mapNotNull null
        if (getInstance.nameAsString != "getInstance" ||
            getInstance.arguments.isNotEmpty()
        ) {
            return@mapNotNull null
        }
        val owner = getInstance.scope.orElse(null)
            ?: return@mapNotNull null
        if (!typing.isTypeName(owner.toString(), CLIENT_MINECRAFT)) {
            return@mapNotNull null
        }
        parsed.range(renderDebug)?.let {
            it to "${getInstance}.getDebugOverlay().showDebugScreen()"
        }
    }
    return applyStringEdits(source, edits.distinctBy { it.first })
}

private fun singleReturnExpression(method: MethodDeclaration): String? {
    val statements = method.body.orElse(null)?.statements ?: return null
    val statement = statements.singleOrNull()?.takeIf { it.isReturnStmt }
        ?.asReturnStmt()
        ?: return null
    return statement.expression.orElse(null)?.toString()
}

private fun StructuralRefactorPass.migrateVec3VertexSubmissions(
    source: String
): String {
    if (!source.contains(".addVertex(") || !source.contains("Vec3")) {
        return source
    }
    val vectors = Regex(
        """\b(?:net\.minecraft\.world\.phys\.)?Vec3\s+""" +
            """([A-Za-z_$][\w$]*)\b"""
    ).findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet()
    if (vectors.isEmpty()) return source
    return rewriteExecutableJavaCall(
        source,
        "addVertex"
    ) { receiver, args ->
        if (args.size != 3) return@rewriteExecutableJavaCall null
        val vector = vectors.singleOrNull { name ->
            args.map { it.trim() } ==
                listOf("$name.x", "$name.y", "$name.z")
        } ?: return@rewriteExecutableJavaCall null
        "$receiver.addVertex(" +
            "(float) ($vector.x), " +
            "(float) ($vector.y), " +
            "(float) ($vector.z))"
    }
}

private fun StructuralRefactorPass.migrateVertexFormatIntegerStride(
    source: String
): String {
    if (!source.contains(".getIntegerSize()") ||
        !source.contains("VertexFormat")
    ) {
        return source
    }
    val formats = Regex(
        """\b(?:com\.mojang\.blaze3d\.vertex\.)?VertexFormat\s+""" +
            """([A-Za-z_$][\w$]*)\b"""
    ).findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet()
    if (formats.isEmpty()) return source
    return rewriteExecutableJavaCall(
        source,
        "getIntegerSize"
    ) { receiver, args ->
        if (args.isNotEmpty() || receiver.trim() !in formats) {
            return@rewriteExecutableJavaCall null
        }
        "${receiver.trim()}.getVertexSize() / Integer.BYTES"
    }
}

private fun StructuralRefactorPass.migrateSharedVanillaPanoramaAccess(
    source: String
): String {
    if (!source.contains("PanoramaRenderer") ||
        !source.contains("TitleScreen")
    ) {
        return source
    }
    var result = replaceExecutableRegex(
        source,
        Regex("""\b[A-Za-z_$][\w$]*\.panorama\b""")
    ) { match ->
        if (match.value.substringBefore('.') == "titleScreen") {
            "Screen.PANORAMA"
        } else {
            match.value
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\bTitleScreen\.CUBE_MAP\b""")
    ) { "Screen.CUBE_MAP" }
    return result
}

private fun StructuralRefactorPass.migrateConfigScreenSupplierOverload(
    source: String
): String {
    if (!source.contains("registerExtensionPoint") ||
        !source.contains("IConfigScreenFactory.class")
    ) {
        return source
    }
    val parsed = parseClientBaseSource(source) ?: return source
    val typing = SourceValueTypes(this, source)
    LexicalPreservingPrinter.setup(parsed.unit)
    var changed = false
    for (registration in parsed.unit.findAll(MethodCallExpr::class.java)) {
        if (registration.nameAsString != "registerExtensionPoint" ||
            registration.arguments.size != 2
        ) {
            continue
        }
        val receiver = registration.scope.orElse(null) ?: continue
        if (!isProvenModContainerReceiver(receiver, typing, parsed)) continue
        val point = registration.arguments[0] as? ClassExpr ?: continue
        if (!typing.isTypeName(
                point.type.asString(),
                I_CONFIG_SCREEN_FACTORY
            )
        ) {
            continue
        }
        val supplier = registration.arguments[1] as? LambdaExpr ?: continue
        if (supplier.parameters.isNotEmpty()) continue
        val factory = supplier.expressionBody.orElse(null) as? LambdaExpr
            ?: continue
        if (factory.parameters.size != 2) continue
        val supplierType = parsed.parser.parseType(
            "java.util.function.Supplier<${point.type}>"
        ).result.orElse(null) ?: continue
        registration.setArgument(
            1,
            CastExpr(supplierType, supplier.clone())
        )
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(parsed.unit) else source
}

private fun isProvenModContainerReceiver(
    receiver: Expression,
    typing: SourceValueTypes,
    parsed: ParsedClientBaseSource
): Boolean {
    if (typing.typeOf(receiver.toString(), parsed.offset(receiver)) ==
        MOD_CONTAINER
    ) {
        return true
    }
    val activeContainer = receiver as? MethodCallExpr ?: return false
    if (activeContainer.nameAsString != "getActiveContainer" ||
        activeContainer.arguments.isNotEmpty()
    ) {
        return false
    }
    val contextGetter = activeContainer.scope.orElse(null)
        as? MethodCallExpr ?: return false
    if (contextGetter.nameAsString != "get" ||
        contextGetter.arguments.isNotEmpty()
    ) {
        return false
    }
    val contextOwner = contextGetter.scope.orElse(null) ?: return false
    return typing.isTypeName(
        contextOwner.toString(),
        MOD_LOADING_CONTEXT
    )
}
