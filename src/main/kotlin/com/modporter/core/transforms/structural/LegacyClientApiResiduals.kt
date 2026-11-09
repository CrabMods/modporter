package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type

/**
 * Migrates residual 1.20.1 client API shapes only when the owning target type and the local
 * replacement value are both proven from the Java source.
 */
internal fun StructuralRefactorPass.migrateLegacyClientApiResidualsSource(
    source: String
): String {
    var result = source
    result = migrateLegacyBlockPickRange(result)
    result = migrateLegacyTickDuration(result)
    result = migrateLegacyGuiBufferSources(result)
    result = migrateLegacyModelRegistryKeys(result)
    return result
}

private const val MINECRAFT = "net.minecraft.client.Minecraft"
private const val LOCAL_PLAYER = "net.minecraft.client.player.LocalPlayer"
private const val GUI_GRAPHICS = "net.minecraft.client.gui.GuiGraphics"
private const val MULTI_BUFFER_SOURCE = "net.minecraft.client.renderer.MultiBufferSource"
private const val TESSELATOR = "com.mojang.blaze3d.vertex.Tesselator"
private const val STRING_UTIL = "net.minecraft.util.StringUtil"
private const val JAVA_MAP = "java.util.Map"
private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
private const val MODEL_RESOURCE_LOCATION =
    "net.minecraft.client.resources.model.ModelResourceLocation"
private const val BAKED_MODEL = "net.minecraft.client.resources.model.BakedModel"
private const val MODEL_EVENT = "net.neoforged.neoforge.client.event.ModelEvent"

private data class ResidualJavaSource(
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

private fun parseResidualJava(source: String): ResidualJavaSource? {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return null
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    return ResidualJavaSource(unit, lineStarts)
}

private fun StructuralRefactorPass.migrateLegacyBlockPickRange(source: String): String {
    if (!source.contains(".gameMode.getPickRange()")) return source
    val parsed = parseResidualJava(source) ?: return source
    val typing = SourceValueTypes(this, source)
    val edits = parsed.unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
        if (call.nameAsString != "getPickRange" || call.arguments.isNotEmpty()) {
            return@mapNotNull null
        }
        val receiver = call.scope.orElse(null)?.toString() ?: return@mapNotNull null
        val minecraft = Regex("""^([A-Za-z_$][\w$]*)\.gameMode$""")
            .matchEntire(receiver)
            ?.groupValues
            ?.get(1)
            ?: return@mapNotNull null
        val callOffset = parsed.offset(call)
        if (callOffset < 0 || typing.typeOf(minecraft, callOffset) != MINECRAFT) {
            return@mapNotNull null
        }
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return@mapNotNull null
        val player = method.parameters.filter { parameter ->
            parameter.annotations.none { it.nameAsString.endsWith("Nullable") } &&
                typing.isTypeName(parameter.typeAsString, LOCAL_PLAYER)
        }.singleOrNull() ?: return@mapNotNull null
        parsed.range(call)?.let { it to "${player.nameAsString}.blockInteractionRange()" }
    }
    return applyStringEdits(source, edits.distinctBy { it.first })
}

private fun StructuralRefactorPass.migrateLegacyTickDuration(source: String): String {
    if (!source.contains("formatTickDuration(")) return source
    val parsed = parseResidualJava(source) ?: return source
    val typing = SourceValueTypes(this, source)
    val edits = parsed.unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
        if (call.nameAsString != "formatTickDuration" || call.arguments.size != 1) {
            return@mapNotNull null
        }
        val scope = call.scope.orElse(null) ?: return@mapNotNull null
        if (!typing.isTypeName(scope.toString(), STRING_UTIL)) return@mapNotNull null
        val callRange = parsed.range(call) ?: return@mapNotNull null
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return@mapNotNull null
        val player = method.findAll(VariableDeclarator::class.java).filter { variable ->
            variable.findAncestor(MethodDeclaration::class.java).orElse(null) === method &&
                parsed.offset(variable) in 0 until callRange.first &&
                typing.isTypeName(variable.typeAsString, LOCAL_PLAYER) &&
                variable.initializer.orElse(null)?.toString()
                    ?.let { Regex("""(?:^|\.)player$""").containsMatchIn(it) } == true
        }.singleOrNull() ?: return@mapNotNull null
        if (typing.typeOf(player.nameAsString, callRange.first) != LOCAL_PLAYER) {
            return@mapNotNull null
        }
        val playerRange = parsed.range(player) ?: return@mapNotNull null
        val between = maskJavaCommentsAndLiterals(
            source.substring(playerRange.last + 1, callRange.first)
        )
        val nullReturn = Regex(
            """if\s*\(\s*${Regex.escape(player.nameAsString)}\s*==\s*null\s*\)""" +
                """\s*(?:return\s*;|\{\s*return\s*;\s*\})"""
        )
        if (!nullReturn.containsMatchIn(between)) return@mapNotNull null
        val ticks = call.arguments.single().toString()
        parsed.range(call)?.let {
            it to "$scope.formatTickDuration(" +
                "$ticks, ${player.nameAsString}.level().tickRateManager().tickrate())"
        }
    }
    return applyStringEdits(source, edits.distinctBy { it.first })
}

private fun StructuralRefactorPass.migrateLegacyGuiBufferSources(source: String): String {
    if (!source.contains("MultiBufferSource.immediate(") ||
        !source.contains("Tesselator.getInstance()")
    ) {
        return source
    }
    val parsed = parseResidualJava(source) ?: return source
    val typing = SourceValueTypes(this, source)
    val edits = parsed.unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
        if (call.nameAsString != "immediate" || call.arguments.size != 1) {
            return@mapNotNull null
        }
        val scope = call.scope.orElse(null) ?: return@mapNotNull null
        if (!typing.isTypeName(scope.toString(), MULTI_BUFFER_SOURCE)) {
            return@mapNotNull null
        }
        val getBuilder = call.arguments.single() as? MethodCallExpr
            ?: return@mapNotNull null
        if (getBuilder.nameAsString != "getBuilder" ||
            getBuilder.arguments.isNotEmpty()
        ) {
            return@mapNotNull null
        }
        val getInstance = getBuilder.scope.orElse(null) as? MethodCallExpr
            ?: return@mapNotNull null
        if (getInstance.nameAsString != "getInstance" ||
            getInstance.arguments.isNotEmpty()
        ) {
            return@mapNotNull null
        }
        val tesselator = getInstance.scope.orElse(null) ?: return@mapNotNull null
        if (!typing.isTypeName(tesselator.toString(), TESSELATOR)) {
            return@mapNotNull null
        }
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return@mapNotNull null
        val graphics = method.parameters.filter {
            typing.isTypeName(it.typeAsString, GUI_GRAPHICS)
        }.singleOrNull() ?: return@mapNotNull null
        parsed.range(call)?.let { it to "${graphics.nameAsString}.bufferSource()" }
    }
    return applyStringEdits(source, edits.distinctBy { it.first })
}

private fun StructuralRefactorPass.migrateLegacyModelRegistryKeys(source: String): String {
    if (!source.contains("ModifyBakingResult") ||
        !source.contains("getModels()") ||
        !source.contains("Map") ||
        !source.contains("ResourceLocation") ||
        !source.contains("BakedModel")
    ) {
        return source
    }
    val parsed = parseResidualJava(source) ?: return source
    val typing = SourceValueTypes(this, source)
    if (!typing.isTypeName("ModelResourceLocation", MODEL_RESOURCE_LOCATION)) {
        return source
    }

    val edits = mutableListOf<Pair<IntRange, String>>()
    val propagatedMethods = mutableSetOf<String>()
    parsed.unit.findAll(VariableDeclarator::class.java).forEach { variable ->
        if (!isLegacyModelMap(variable.type, typing)) return@forEach
        val getModels = variable.initializer.orElse(null) as? MethodCallExpr
            ?: return@forEach
        if (getModels.nameAsString != "getModels" ||
            getModels.arguments.isNotEmpty()
        ) {
            return@forEach
        }
        val event = getModels.scope.orElse(null) as? NameExpr ?: return@forEach
        val method = variable.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return@forEach
        val eventParameter = method.parameters.singleOrNull {
            it.nameAsString == event.nameAsString &&
                isModifyBakingResult(it.typeAsString, typing)
        } ?: return@forEach
        if (eventParameter.nameAsString != event.nameAsString) return@forEach

        val typeRange = parsed.range(variable.type) ?: return@forEach
        edits += typeRange to modernModelMapType(source.substring(typeRange))
        method.findAll(MethodCallExpr::class.java).forEach callLoop@ { call ->
            val first = call.arguments.firstOrNull() as? NameExpr ?: return@callLoop
            if (first.nameAsString == variable.nameAsString) {
                propagatedMethods += call.nameAsString
            }
        }
    }
    if (edits.isEmpty() || propagatedMethods.isEmpty()) return source

    parsed.unit.findAll(MethodDeclaration::class.java).forEach { method ->
        if (method.nameAsString !in propagatedMethods || method.parameters.isEmpty()) {
            return@forEach
        }
        val registry = method.parameters.first()
        if (!isLegacyModelMap(registry.type, typing) ||
            method.parameters.drop(1).none {
                it.type.findAll(ClassOrInterfaceType::class.java).any { nested ->
                    typing.isTypeName(nested.nameWithScope, MODEL_RESOURCE_LOCATION)
                }
            }
        ) {
            return@forEach
        }
        val typeRange = parsed.range(registry.type) ?: return@forEach
        edits += typeRange to modernModelMapType(source.substring(typeRange))
    }
    return applyStringEdits(source, edits.distinctBy { it.first })
}

private fun isLegacyModelMap(type: Type, typing: SourceValueTypes): Boolean {
    val map = type as? ClassOrInterfaceType ?: return false
    if (!typing.isTypeName(map.nameWithScope, JAVA_MAP)) return false
    val arguments = map.typeArguments.orElse(null)?.toList() ?: return false
    return arguments.size == 2 &&
        typing.isTypeName(arguments[0].toString(), RESOURCE_LOCATION) &&
        typing.isTypeName(arguments[1].toString(), BAKED_MODEL)
}

private fun isModifyBakingResult(type: String, typing: SourceValueTypes): Boolean {
    val suffix = ".ModifyBakingResult"
    if (!type.endsWith(suffix)) return false
    return typing.isTypeName(type.removeSuffix(suffix), MODEL_EVENT)
}

private fun modernModelMapType(legacyType: String): String =
    legacyType.substringBefore('<').trimEnd() + "<ModelResourceLocation, BakedModel>"
