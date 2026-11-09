package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Migrates bound buffer writer references consumed by Iterable/Stream.forEach.
 *
 * The collection element, bound receiver and receiver parameter must all have exact source types.
 * Restricting the receiver to an effectively-final method parameter keeps anonymous-adapter capture
 * legal. The adapter evaluates and null-checks that receiver when the original method reference was
 * created, then invokes the real stream codec from its void method. A legacy FriendlyByteBuf
 * parameter is widened locally; the existing project registry-buffer propagation pass then aligns
 * its declaration/override family and callers.
 */
internal fun StructuralRefactorPass.migrateRegistryFriendlyStreamCodecReferences(
    source: String
): String {
    if (!source.contains("::writeItem") && !source.contains("::writeFluidStack")) return source
    val moves = bufferCodecMoves().filter(::targetHasBufferCodecMove)
    if (moves.isEmpty()) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val simpleRegistryTypeAvailable = simpleRegistryBufferNameAvailable(source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun offset(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)
    fun uniqueName(base: String): String {
        var name = base
        while (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(source)) name += "_"
        return name
    }
    val valueName = uniqueName("modporterStack")
    val capturedBufferName = uniqueName("modporterBuffer")
    val adapterName = uniqueName("modporterEncode")

    LexicalPreservingPrinter.setup(unit)
    val widen = linkedSetOf<Parameter>()
    var changed = false
    for (reference in unit.findAll(MethodReferenceExpr::class.java)) {
        val move = moves.singleOrNull { it.legacyMethod == reference.identifier } ?: continue
        val forEach = reference.parentNode.orElse(null) as? MethodCallExpr ?: continue
        if (forEach.nameAsString != "forEach" ||
            forEach.arguments.singleOrNull() !== reference
        ) continue
        val values = forEach.scope.orElse(null) ?: continue
        if (typing.iterableElementType(values.toString(), offset(values)) != move.valueType) {
            continue
        }

        val bufferScope = reference.scope as? TypeExpr ?: continue
        val receiverName = bufferScope.type.asString()
            .takeIf { it.matches(Regex("""[A-Za-z_$][\w$]*""")) }
            ?: continue
        val bufferType = typing.typeOf(receiverName, offset(bufferScope)) ?: continue
        if (bufferType !in setOf(FRIENDLY_BUFFER, REGISTRY_BUFFER)) continue
        val parameter = stableBufferParameter(reference, receiverName, typing)
            ?: continue
        if (bufferType == FRIENDLY_BUFFER) {
            if (!simpleRegistryTypeAvailable) continue
            widen += parameter
        }

        val replacement = parser.parseExpression<Expression>(
            "new Object() {" +
                " private final $REGISTRY_BUFFER $capturedBufferName = " +
                "java.util.Objects.requireNonNull($receiverName);" +
                " public void $adapterName(${move.valueType} $valueName) {" +
                " ${move.codecOwner}.OPTIONAL_STREAM_CODEC.encode(" +
                "$capturedBufferName, $valueName);" +
                " }" +
                " }::$adapterName"
        ).result.orElse(null) ?: continue
        reference.replace(replacement)
        changed = true
    }
    if (!changed) return source
    widen.forEach { it.setType("RegistryFriendlyByteBuf") }
    var result = LexicalPreservingPrinter.print(unit)
    if (widen.isNotEmpty()) {
        result = JavaSourceText.addImport(result, REGISTRY_BUFFER)
        val withoutFriendly = JavaSourceText.removeImport(result, FRIENDLY_BUFFER)
        if (!Regex("""\bFriendlyByteBuf\b""").containsMatchIn(
                JavaSourceText.maskCommentsAndLiterals(withoutFriendly)
            )
        ) result = withoutFriendly
    }
    return result
}

private const val FRIENDLY_BUFFER = "net.minecraft.network.FriendlyByteBuf"
private const val REGISTRY_BUFFER = "net.minecraft.network.RegistryFriendlyByteBuf"
private const val STREAM_CODEC_DESCRIPTOR = "Lnet/minecraft/network/codec/StreamCodec;"

private data class BufferCodecMove(
    val legacyMethod: String,
    val valueType: String,
    val codecOwner: String,
    val codecSignature: String
)

private fun bufferCodecMoves(): List<BufferCodecMove> = listOf(
    BufferCodecMove(
        legacyMethod = "writeItem",
        valueType = "net.minecraft.world.item.ItemStack",
        codecOwner = "net.minecraft.world.item.ItemStack",
        codecSignature =
            "Lnet/minecraft/network/codec/StreamCodec<" +
                "Lnet/minecraft/network/RegistryFriendlyByteBuf;" +
                "Lnet/minecraft/world/item/ItemStack;>;"
    ),
    BufferCodecMove(
        legacyMethod = "writeFluidStack",
        valueType = "net.neoforged.neoforge.fluids.FluidStack",
        codecOwner = "net.neoforged.neoforge.fluids.FluidStack",
        codecSignature =
            "Lnet/minecraft/network/codec/StreamCodec<" +
                "Lnet/minecraft/network/RegistryFriendlyByteBuf;" +
                "Lnet/neoforged/neoforge/fluids/FluidStack;>;"
    )
)

private fun targetHasBufferCodecMove(move: BufferCodecMove): Boolean =
    !TargetMembers.declaresInHierarchy(REGISTRY_BUFFER, move.legacyMethod, 1) &&
        TargetMembers.fieldDescriptor(move.codecOwner, "OPTIONAL_STREAM_CODEC") ==
            STREAM_CODEC_DESCRIPTOR &&
        TargetMembers.fieldSignatures(move.codecOwner)["OPTIONAL_STREAM_CODEC"] ==
            move.codecSignature

private fun simpleRegistryBufferNameAvailable(source: String): Boolean {
    val code = JavaSourceText.maskCommentsAndLiterals(source)
    if (Regex("""\b(?:class|interface|record|enum)\s+RegistryFriendlyByteBuf\b""")
            .containsMatchIn(code)
    ) return false
    return Regex("""(?m)^\s*import\s+([\w.]+\.RegistryFriendlyByteBuf)\s*;""")
        .findAll(code)
        .map { it.groupValues[1] }
        .all { it == REGISTRY_BUFFER }
}

private fun stableBufferParameter(
    reference: MethodReferenceExpr,
    name: String,
    typing: SourceValueTypes
): Parameter? {
    val method = reference.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
    val parameter = method.parameters.singleOrNull { it.nameAsString == name } ?: return null
    when {
        typing.isTypeName(parameter.type.asString(), REGISTRY_BUFFER) -> Unit
        typing.isTypeName(parameter.type.asString(), FRIENDLY_BUFFER) -> Unit
        else -> return null
    }
    if (method.findAll(AssignExpr::class.java).any {
            it.target is NameExpr && it.target.toString() == name
        }
    ) return null
    if (method.findAll(UnaryExpr::class.java).any {
            it.expression is NameExpr &&
                it.expression.toString() == name &&
                it.operator in setOf(
                    UnaryExpr.Operator.POSTFIX_DECREMENT,
                    UnaryExpr.Operator.POSTFIX_INCREMENT,
                    UnaryExpr.Operator.PREFIX_DECREMENT,
                    UnaryExpr.Operator.PREFIX_INCREMENT
                )
        }
    ) return null
    return parameter
}
