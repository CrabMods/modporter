package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/**
 * NeoForge renamed EventBusSubscriber.Bus.FORGE to Bus.GAME. Keep the explicit bus selection
 * instead of deleting it, so a game-bus subscriber cannot silently become a mod-bus subscriber.
 */
internal fun StructuralRefactorPass.migrateNeoForgeSubscriberBus(source: String): String {
    if (!source.contains("EventBusSubscriber") || !source.contains("Bus.FORGE")) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    LexicalPreservingPrinter.setup(unit)
    var changed = false

    for (annotation in unit.findAll(NormalAnnotationExpr::class.java)) {
        if (!typing.isTypeName(annotation.nameAsString, NEO_SUBSCRIBER)) continue
        val bus = annotation.pairs.singleOrNull { it.nameAsString == "bus" } ?: continue
        val value = bus.value as? FieldAccessExpr ?: continue
        if (value.nameAsString != "FORGE" ||
            !typing.isTypeName(value.scope.toString(), NEO_SUBSCRIBER_BUS_SOURCE)
        ) continue
        value.setName("GAME")
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

private const val NEO_SUBSCRIBER = "net.neoforged.fml.common.EventBusSubscriber"
private const val NEO_SUBSCRIBER_BUS_SOURCE =
    "net.neoforged.fml.common.EventBusSubscriber.Bus"
