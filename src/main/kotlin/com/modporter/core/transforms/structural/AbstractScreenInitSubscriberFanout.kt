package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path

/**
 * NeoForge rejects automatic listeners whose declared event type is the abstract
 * ScreenEvent.Init base. Forge dispatched that base listener for both Init.Pre and
 * Init.Post, so preserve the original method as a directly callable helper and add
 * concrete subscribed wrappers for both phases.
 */
internal fun StructuralRefactorPass.migrateAbstractScreenInitSubscriberFanout(
    source: String,
    file: Path,
    changes: MutableList<Change>
): String {
    if (!source.contains("@SubscribeEvent") || !source.contains("ScreenEvent.Init")) {
        return source
    }

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    LexicalPreservingPrinter.setup(unit)
    var changed = false

    for (method in unit.findAll(MethodDeclaration::class.java).toList()) {
        val parameter = method.parameters.singleOrNull() ?: continue
        if (!typing.isTypeName(parameter.typeAsString, ASIF_SCREEN_INIT) ||
            !method.isStatic ||
            !method.type.isVoidType ||
            method.body.isEmpty ||
            method.typeParameters.isNotEmpty()
        ) {
            continue
        }

        val subscribe = method.annotations.filter {
            typing.isTypeName(it.nameAsString, ASIF_SUBSCRIBE_EVENT)
        }.singleOrNull() ?: continue
        if (method.annotations.size != 1) continue

        val owner = method.parentNode.orElse(null) as? TypeDeclaration<*> ?: continue
        val preName = method.nameAsString + "Pre"
        val postName = method.nameAsString + "Post"
        if (owner.methods.any { it !== method && it.nameAsString in setOf(preName, postName) }) {
            continue
        }
        if (owner.methods.any { candidate ->
                candidate !== method &&
                    candidate.nameAsString == method.nameAsString &&
                    candidate.parameters.singleOrNull()?.let { candidateParameter ->
                        typing.isTypeName(candidateParameter.typeAsString, "$ASIF_SCREEN_INIT.Pre") ||
                            typing.isTypeName(candidateParameter.typeAsString, "$ASIF_SCREEN_INIT.Post")
                    } == true
            }
        ) {
            continue
        }

        val pre = method.clone()
        val post = method.clone()
        pre.setName(preName)
        post.setName(postName)
        pre.parameters[0].setType(parameter.typeAsString + ".Pre")
        post.parameters[0].setType(parameter.typeAsString + ".Post")
        pre.setBody(asifDelegatingBody(method.nameAsString, parameter))
        post.setBody(asifDelegatingBody(method.nameAsString, parameter))

        val parsedPre = parser.parseBodyDeclaration<MethodDeclaration>(pre.toString())
            .result.orElse(null) ?: continue
        val parsedPost = parser.parseBodyDeclaration<MethodDeclaration>(post.toString())
            .result.orElse(null) ?: continue
        val memberIndex = owner.members.indexOf(method)
        if (memberIndex < 0) continue

        method.annotations.remove(subscribe)
        owner.members.add(memberIndex + 1, parsedPre as BodyDeclaration<*>)
        owner.members.add(memberIndex + 2, parsedPost as BodyDeclaration<*>)
        changes += Change(
            file = file,
            line = method.range.map { it.begin.line }.orElse(1),
            description = "Fan out abstract ScreenEvent.Init subscriber to concrete Pre and Post events",
            before = "${subscribe} ${method.nameAsString}(${parameter.typeAsString})",
            after = "$preName(${parameter.typeAsString}.Pre), $postName(${parameter.typeAsString}.Post)",
            confidence = Confidence.HIGH,
            ruleId = ASIF_RULE_ID
        )
        changed = true
    }

    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

private fun asifDelegatingBody(methodName: String, parameter: com.github.javaparser.ast.body.Parameter): BlockStmt {
    val call = MethodCallExpr()
        .setName(methodName)
        .addArgument(CastExpr(parameter.type.clone(), NameExpr(parameter.nameAsString)))
    return BlockStmt().addStatement(call)
}

private const val ASIF_RULE_ID = "struct-abstract-screen-init-subscriber-fanout"
private const val ASIF_SCREEN_INIT =
    "net.neoforged.neoforge.client.event.ScreenEvent.Init"
private const val ASIF_SUBSCRIBE_EVENT =
    "net.neoforged.bus.api.SubscribeEvent"
