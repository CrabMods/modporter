package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType

/**
 * Migrates the last 1.20.1 client sound call shapes only when the source establishes the
 * replacement value.
 *
 * Holder-valued vanilla sounds are unwrapped only for the exact source-bound builder overload
 * family, and a SubtitleOverlay replay retains the old direct-overlay unlimited range without
 * resolving or dereferencing the sound instance. Legacy nullable model-data manager queries remain
 * unchanged until their provider closure can be preserved.
 */
internal fun StructuralRefactorPass.migrateLegacyClientRenderSoundCalls(
    source: String
): String {
    var result = source
    result = migrateLegacyHolderSoundArguments(result)
    result = migrateLegacySubtitleRanges(result)
    return result
}

private const val JAVA_SUPPLIER = "java.util.function.Supplier"
private const val SOUND_EVENT = "net.minecraft.sounds.SoundEvent"
private const val SOUND_EVENTS = "net.minecraft.sounds.SoundEvents"
private const val SUBTITLE_OVERLAY = "net.minecraft.client.gui.components.SubtitleOverlay"
private const val WEIGHED_SOUND_EVENTS = "net.minecraft.client.sounds.WeighedSoundEvents"
private const val ABSTRACT_TICKABLE_SOUND =
    "net.minecraft.client.resources.sounds.AbstractTickableSoundInstance"

private data class ClientResidualSource(
    val unit: com.github.javaparser.ast.CompilationUnit,
    val lineStarts: List<Int>
) {
    fun offset(node: Node): Int = node.range.map {
        lineStarts[it.begin.line - 1] + it.begin.column - 1
    }.orElse(-1)

    fun range(node: Node): IntRange? = node.range.map {
        val from = lineStarts[it.begin.line - 1] + it.begin.column - 1
        val to = lineStarts[it.end.line - 1] + it.end.column - 1
        from..to
    }.orElse(null)
}

private fun parseClientResidual(source: String): ClientResidualSource? {
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
    return ClientResidualSource(unit, lineStarts)
}

private fun StructuralRefactorPass.migrateLegacyHolderSoundArguments(source: String): String {
    if (!source.contains("playExisting(") || !source.contains("SoundEvents.")) return source
    val parsed = parseClientResidual(source) ?: return source
    val typing = SourceValueTypes(this, source)

    val edits = parsed.unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
        if (call.nameAsString != "playExisting" || call.arguments.size != 3) {
            return@mapNotNull null
        }
        val sound = call.arguments.first() as? FieldAccessExpr ?: return@mapNotNull null
        if (!typing.isTypeName(sound.scope.toString(), SOUND_EVENTS) ||
            !isVanillaHolderSoundEvent(sound.nameAsString)
        ) {
            return@mapNotNull null
        }
        val builder = sourceBoundBuilder(call, typing) ?: return@mapNotNull null
        val candidates = builder.methods.filter {
            it.nameAsString == "playExisting" && it.parameters.size == 3
        }
        if (candidates.size != 2 ||
            candidates.count {
                typing.isTypeName(it.parameters.first().typeAsString, SOUND_EVENT)
            } != 1 ||
            candidates.count {
                isSoundEventSupplier(
                    it.parameters.first().type as? ClassOrInterfaceType,
                    typing
                )
            } != 1
        ) {
            return@mapNotNull null
        }
        parsed.range(sound)?.let { it to "${sound}.value()" }
    }
    return applyStringEdits(source, edits.distinctBy { it.first })
}

private fun StructuralRefactorPass.migrateLegacySubtitleRanges(source: String): String {
    if (!source.contains("onPlaySound(") || !source.contains("SubtitleOverlay")) return source
    val parsed = parseClientResidual(source) ?: return source
    val typing = SourceValueTypes(this, source)
    val edits = parsed.unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
        if (call.nameAsString != "onPlaySound" || call.arguments.size != 2) {
            return@mapNotNull null
        }
        val owner = call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: return@mapNotNull null
        val instance = call.arguments.first() as? ThisExpr
            ?: return@mapNotNull null
        if (owner.extendedTypes.none {
                typing.isTypeName(it.nameWithScope, ABSTRACT_TICKABLE_SOUND)
            } ||
            instance.typeName.isPresent
        ) {
            return@mapNotNull null
        }
        val overlay = call.scope.orElse(null) as? NameExpr ?: return@mapNotNull null
        val overlayField = owner.fields.flatMap { it.variables }.singleOrNull {
            it.nameAsString == overlay.nameAsString &&
                typing.isTypeName(it.type.toString(), SUBTITLE_OVERLAY)
        } ?: return@mapNotNull null
        if (overlayField.nameAsString != overlay.nameAsString) return@mapNotNull null

        val events = call.arguments[1] as? NameExpr ?: return@mapNotNull null
        val eventsField = owner.fields.flatMap { it.variables }.singleOrNull {
            it.nameAsString == events.nameAsString &&
                typing.isTypeName(it.type.toString(), WEIGHED_SOUND_EVENTS)
        } ?: return@mapNotNull null
        if (eventsField.nameAsString != events.nameAsString) return@mapNotNull null
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return@mapNotNull null
        val fieldNames = setOf(overlay.nameAsString, events.nameAsString)
        if (method.findAll(Parameter::class.java).any {
                it.nameAsString in fieldNames
            } ||
            method.findAll(VariableDeclarator::class.java).any {
                it.nameAsString in fieldNames && parsed.offset(it) < parsed.offset(call)
            }
        ) {
            return@mapNotNull null
        }
        val range = parsed.range(call) ?: return@mapNotNull null
        range.last..range.last to ", java.lang.Float.POSITIVE_INFINITY)"
    }
    return applyStringEdits(source, edits.distinctBy { it.first })
}

private fun sourceBoundBuilder(
    call: MethodCallExpr,
    typing: SourceValueTypes
): ClassOrInterfaceDeclaration? {
    val enclosing = call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
        ?: return null
    val calls = mutableListOf<MethodCallExpr>()
    var current = call.scope.orElse(null) as? MethodCallExpr ?: return null
    while (true) {
        calls += current
        current = current.scope.orElse(null) as? MethodCallExpr ?: break
    }
    val root = calls.last()
    if (root.scope.isPresent ||
        root.nameAsString != "create" ||
        root.arguments.singleOrNull() !is StringLiteralExpr
    ) {
        return null
    }
    val rootDeclarations = enclosing.methods.filter { method ->
        method.nameAsString == root.nameAsString &&
            method.parameters.size == 1 &&
            typing.isTypeName(
                method.parameters.single().typeAsString,
                "java.lang.String"
            )
    }
    val factory = rootDeclarations.singleOrNull() ?: return null
    val builderName = rawTypeName(factory.type.toString()) ?: return null
    if (builderName != "SoundEntryBuilder") return null
    val builder = enclosing.members.filterIsInstance<ClassOrInterfaceDeclaration>()
        .filter { it.nameAsString == builderName }
        .singleOrNull()
        ?: return null

    for (intermediate in calls.dropLast(1)) {
        val methods = builder.methods.filter {
            it.nameAsString == intermediate.nameAsString &&
                it.parameters.size == intermediate.arguments.size &&
                rawTypeName(it.type.toString()) == builderName
        }
        if (methods.size != 1) return null
    }
    if (builder.methods.none {
            it.nameAsString == call.nameAsString &&
                it.parameters.size == call.arguments.size &&
                typing.isTypeName(it.parameters.first().typeAsString, SOUND_EVENT)
        }
    ) {
        return null
    }
    return builder
}

private fun rawTypeName(type: String): String? =
    Regex("""(?:[A-Za-z_$][\w$]*\.)*([A-Za-z_$][\w$]*)(?:\s*<.*>)?""")
        .matchEntire(type.trim())
        ?.groupValues
        ?.get(1)

private fun isSoundEventSupplier(
    type: ClassOrInterfaceType?,
    typing: SourceValueTypes
): Boolean {
    if (type == null || !typing.isTypeName(type.nameWithScope, JAVA_SUPPLIER)) return false
    val arguments = type.typeArguments.orElse(null)?.toList() ?: return false
    return arguments.size == 1 &&
        typing.isTypeName(arguments.single().toString(), SOUND_EVENT)
}
