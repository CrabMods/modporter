package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MarkerAnnotationExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path

/**
 * Limits an automatic event subscriber to the client when every direct handler is already
 * stripped on non-client distributions. NeoForge's event bus rejects the otherwise empty class.
 *
 * Integration: call after import/package migrations and migrateDistExecutorDistGuards.
 */
internal fun StructuralRefactorPass.migrateClientOnlyEventBusSubscriberDist(
    source: String,
    file: Path,
    changes: MutableList<Change>
): String {
    if (!source.contains("@EventBusSubscriber") ||
        !source.contains("@SubscribeEvent") ||
        !source.contains("@OnlyIn") ||
        !source.contains("Dist.CLIENT")
    ) {
        return source
    }

    val parser = JavaParser(
        ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    if (!unit.ceExactImports()) return source
    if (unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString in CE_RESERVED_SIMPLE_NAMES
        }
    ) {
        return source
    }

    val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
        ?: return source
    if (owner.isInterface ||
        owner.typeParameters.isNotEmpty() ||
        owner.extendedTypes.isNotEmpty() ||
        owner.implementedTypes.isNotEmpty() ||
        owner.fields.isNotEmpty() ||
        owner.constructors.isNotEmpty() ||
        owner.members.any { it is InitializerDeclaration }
    ) {
        return source
    }

    val typing = SourceValueTypes(this, source)
    if (!typing.isTypeName("Dist", CE_DIST)) return source

    val subscriberAnnotations = owner.annotations.filter {
        typing.isTypeName(it.nameAsString, CE_EVENT_BUS_SUBSCRIBER)
    }
    val subscriber = subscriberAnnotations.singleOrNull() ?: return source
    val insertion = subscriber.ceClientDistInsertion(typing) ?: return source
    if (insertion.alreadyClient) return source

    val handlers = mutableListOf<MethodDeclaration>()
    for (method in owner.methods) {
        val subscribeAnnotations = method.annotations.filter {
            typing.isTypeName(it.nameAsString, CE_SUBSCRIBE_EVENT)
        }
        if (subscribeAnnotations.isEmpty()) continue
        if (subscribeAnnotations.size != 1 ||
            !method.isStatic ||
            method.body.isEmpty ||
            method.parameters.size != 1 ||
            method.typeParameters.isNotEmpty()
        ) {
            return source
        }
        val sideAnnotations = method.annotations.filter {
            typing.isTypeName(it.nameAsString, CE_ONLY_IN)
        }
        if (sideAnnotations.size != 1 ||
            !sideAnnotations.single().ceOnlyClient(typing)
        ) {
            return source
        }
        handlers += method
    }
    if (handlers.isEmpty()) return source

    val starts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    fun offset(position: Position): Int {
        val line = starts.getOrNull(position.line - 1) ?: return -1
        return line + position.column - 1
    }

    val range = subscriber.range.orElse(null) ?: return source
    val insertAt = when (subscriber) {
        is MarkerAnnotationExpr -> offset(range.end) + 1
        is NormalAnnotationExpr -> offset(range.end)
        else -> return source
    }
    if (insertAt !in 0..source.length) return source
    if (subscriber is NormalAnnotationExpr &&
        (insertAt !in source.indices || source[insertAt] != ')')
    ) {
        return source
    }

    val migrated = source.substring(0, insertAt) +
        insertion.text +
        source.substring(insertAt)
    changes += Change(
        file = file,
        line = range.begin.line,
        description =
            "Limit an all-client automatic event subscriber to Dist.CLIENT",
        before = subscriber.toString(),
        after = subscriber.toString() + insertion.text,
        confidence = Confidence.HIGH,
        ruleId = CE_RULE_ID
    )
    return migrated
}

private const val CE_RULE_ID = "struct-client-only-event-subscriber-dist"
private const val CE_EVENT_BUS_SUBSCRIBER =
    "net.neoforged.fml.common.EventBusSubscriber"
private const val CE_SUBSCRIBE_EVENT =
    "net.neoforged.bus.api.SubscribeEvent"
private const val CE_ONLY_IN =
    "net.neoforged.api.distmarker.OnlyIn"
private const val CE_DIST =
    "net.neoforged.api.distmarker.Dist"
private val CE_RESERVED_SIMPLE_NAMES = setOf(
    "EventBusSubscriber",
    "SubscribeEvent",
    "OnlyIn",
    "Dist"
)

private data class CEInsertion(
    val text: String,
    val alreadyClient: Boolean = false
)

private fun CompilationUnit.ceExactImports(): Boolean {
    val expected = mapOf(
        "EventBusSubscriber" to CE_EVENT_BUS_SUBSCRIBER,
        "SubscribeEvent" to CE_SUBSCRIBE_EVENT,
        "OnlyIn" to CE_ONLY_IN,
        "Dist" to CE_DIST
    )
    return expected.all { (simple, canonical) ->
        imports.filter {
            !it.isStatic &&
                !it.isAsterisk &&
                it.nameAsString.substringAfterLast('.') == simple
        }.singleOrNull()?.nameAsString == canonical
    }
}

private fun AnnotationExpr.ceClientDistInsertion(
    typing: SourceValueTypes
): CEInsertion? = when (this) {
    is MarkerAnnotationExpr -> CEInsertion("(Dist.CLIENT)")
    is SingleMemberAnnotationExpr ->
        CEInsertion("", alreadyClient = memberValue.ceIsClientDist(typing))
            .takeIf { it.alreadyClient }
    is NormalAnnotationExpr -> {
        if (pairs.map { it.nameAsString }.toSet().size != pairs.size ||
            pairs.any { it.nameAsString !in setOf("value", "modid", "bus") }
        ) {
            null
        } else {
            val value = pairs.singleOrNull { it.nameAsString == "value" }
            if (value != null) {
                CEInsertion(
                    "",
                    alreadyClient = value.value.ceIsClientDist(typing)
                ).takeIf { it.alreadyClient }
            } else {
                CEInsertion(
                    if (pairs.isEmpty()) {
                        "value = Dist.CLIENT"
                    } else {
                        ", value = Dist.CLIENT"
                    }
                )
            }
        }
    }
    else -> null
}

private fun AnnotationExpr.ceOnlyClient(
    typing: SourceValueTypes
): Boolean = when (this) {
    is SingleMemberAnnotationExpr -> memberValue.ceIsClientDist(typing)
    is NormalAnnotationExpr ->
        pairs.size == 1 &&
            pairs.single().nameAsString == "value" &&
            pairs.single().value.ceIsClientDist(typing)
    else -> false
}

private fun Expression.ceIsClientDist(
    typing: SourceValueTypes
): Boolean {
    val expression = if (this is EnclosedExpr) inner else this
    if (expression is ArrayInitializerExpr) {
        return expression.values.size == 1 &&
            expression.values.single().ceIsClientDist(typing)
    }
    val client = expression as? FieldAccessExpr ?: return false
    val owner = client.scope as? NameExpr ?: return false
    return client.nameAsString == "CLIENT" &&
        typing.isTypeName(owner.nameAsString, CE_DIST)
}
