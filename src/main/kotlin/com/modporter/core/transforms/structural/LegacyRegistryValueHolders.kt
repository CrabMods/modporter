package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/**
 * Registry#getHolder no longer accepts a registered value. Resolve that value's key first, then
 * use the same canonical built-in registry to recover its reference holder.
 */
internal fun StructuralRefactorPass.migrateLegacyRegistryValueHolders(source: String): String {
    if (!source.contains("BuiltInRegistries") || !source.contains(".getHolder(")) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    fun offset(node: Node): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)

    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
        if (call.nameAsString != "getHolder" ||
            call.arguments.size != 1 ||
            call.typeArguments.isPresent
        ) {
            continue
        }
        val registry = call.scope.orElse(null) as? FieldAccessExpr ?: continue
        if (!typing.isTypeName(registry.scope.toString(), BUILT_IN_REGISTRIES)) continue
        val contract = builtInRegistryValueContract(registry.nameAsString) ?: continue
        val argument = call.arguments[0]
        val argumentOffset = offset(argument)
        if (argumentOffset < 0 ||
            typing.typeOf(argument.toString(), argumentOffset) != contract.entryType
        ) {
            continue
        }
        val registryExpression = registry.toString()
        call.replace(
            parser.parseExpression<Expression>(
                "$registryExpression.getResourceKey($argument)" +
                    ".flatMap($registryExpression::getHolder)"
            ).result.orElse(null) ?: continue
        )
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

private data class BuiltInRegistryValueContract(
    val registryType: String,
    val entryType: String
)

private fun builtInRegistryValueContract(field: String): BuiltInRegistryValueContract? {
    val signature = TargetMembers.fieldSignatures(BUILT_IN_REGISTRIES)[field] ?: return null
    val registry = parseRegistrySignature(signature) ?: return null
    val fieldDescriptor = TargetMembers.fieldDescriptor(BUILT_IN_REGISTRIES, field)
    if (fieldDescriptor != "L${registry.registryType.replace('.', '/')};" ||
        !TargetMembers.inheritsFrom(registry.registryType, REGISTRY)
    ) {
        return null
    }
    val resourceKey = TargetMembers.inheritedMethod(
        registry.registryType,
        "getResourceKey",
        "(Ljava/lang/Object;)Ljava/util/Optional;"
    )
    val holder = TargetMembers.inheritedMethod(
        registry.registryType,
        "getHolder",
        "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;"
    )
    return registry.takeIf {
        resourceKey?.access == "public" &&
            resourceKey.signature ==
            "(TT;)Ljava/util/Optional<" +
            "Lnet/minecraft/resources/ResourceKey<TT;>;>;" &&
            holder?.access == "public" &&
            holder.signature ==
            "(Lnet/minecraft/resources/ResourceKey<TT;>;)" +
            "Ljava/util/Optional<Lnet/minecraft/core/Holder\$Reference<TT;>;>;"
    }
}

private fun parseRegistrySignature(signature: String): BuiltInRegistryValueContract? {
    if (!signature.startsWith("L")) return null
    val argumentOpen = signature.indexOf('<')
    if (argumentOpen < 2) return null
    val registryType = signature.substring(1, argumentOpen)
        .replace('/', '.')
        .replace('$', '.')
    val argumentStart = argumentOpen + 1
    if (signature.getOrNull(argumentStart) != 'L') return null
    val argumentEnd = genericObjectTypeEnd(signature, argumentStart) ?: return null
    if (signature.getOrNull(argumentEnd) != '>' ||
        signature.getOrNull(argumentEnd + 1) != ';'
    ) {
        return null
    }
    val rawEnd = listOf(
        signature.indexOf('<', argumentStart),
        signature.indexOf(';', argumentStart)
    ).filter { it >= 0 }.minOrNull() ?: return null
    if (rawEnd >= argumentEnd) return null
    val entryType = signature.substring(argumentStart + 1, rawEnd)
        .replace('/', '.')
        .replace('$', '.')
    return BuiltInRegistryValueContract(registryType, entryType)
}

private fun genericObjectTypeEnd(signature: String, start: Int): Int? {
    if (signature.getOrNull(start) != 'L') return null
    var genericDepth = 0
    for (index in start + 1 until signature.length) {
        when (signature[index]) {
            '<' -> genericDepth++
            '>' -> genericDepth--
            ';' -> if (genericDepth == 0) return index + 1
        }
        if (genericDepth < 0) return null
    }
    return null
}

private const val BUILT_IN_REGISTRIES =
    "net.minecraft.core.registries.BuiltInRegistries"
private const val REGISTRY = "net.minecraft.core.Registry"
