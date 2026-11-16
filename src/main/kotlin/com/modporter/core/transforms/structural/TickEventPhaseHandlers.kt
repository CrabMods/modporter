package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

private data class NativeTickEventFamily(val canonical: String)

private val nativeTickEventFamilies = listOf(
    NativeTickEventFamily("net.neoforged.neoforge.client.event.ClientTickEvent"),
    NativeTickEventFamily("net.neoforged.neoforge.event.tick.ServerTickEvent"),
    NativeTickEventFamily("net.neoforged.neoforge.event.tick.LevelTickEvent"),
    NativeTickEventFamily("net.neoforged.neoforge.event.tick.PlayerTickEvent")
)

/**
 * Split a legacy phase-filtered subscription only when the source proves either one selected
 * native phase or the closed two-phase dispatch shape used by legacy client tick handlers.
 */
internal fun StructuralRefactorPass.migrateUnsplitTickEventHandlers(source: String): String {
    if (!source.contains(".phase")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (method in unit.findAll(MethodDeclaration::class.java).toList()) {
        val split = splitMixedTickEventHandler(method, typing, source, this)
        if (split != null) {
            // Clones retain LexicalPreservingPrinter's old NodeText. Reparse completed ASTs
            // before insertion so printing cannot resurrect the original Post body/type.
            val pre = parser.parseBodyDeclaration<MethodDeclaration>(split.first.toString())
                .result.orElse(null) ?: continue
            val post = parser.parseBodyDeclaration<MethodDeclaration>(split.second.toString())
                .result.orElse(null) ?: continue
            val owner = method.parentNode.orElse(null) as? TypeDeclaration<*> ?: continue
            val index = owner.members.indexOf(method)
            if (index < 0) continue
            owner.members[index] = pre as BodyDeclaration<*>
            owner.members.add(index + 1, post as BodyDeclaration<*>)
            changed = true
            continue
        }

        val parameter = method.parameters.singleOrNull() ?: continue
        if (!method.type.isVoidType) continue
        val event = nativeTickEventFamilies.singleOrNull {
            typing.isTypeName(parameter.typeAsString, it.canonical)
        } ?: continue
        val body = method.body.orElse(null) ?: continue
        val guard = body.statements.firstOrNull() as? IfStmt ?: continue
        if (guard.elseStmt.isPresent) continue
        val returned = when (val then = guard.thenStmt) {
            is ReturnStmt -> then
            is BlockStmt -> then.statements.singleOrNull() as? ReturnStmt
            else -> null
        } ?: continue
        if (returned.expression.isPresent) continue
        val comparison = guard.condition as? BinaryExpr ?: continue
        val sides = listOf(comparison.left, comparison.right)
        val field = sides.filterIsInstance<FieldAccessExpr>().singleOrNull {
            it.nameAsString == "phase" && it.scope.toString() == parameter.nameAsString
        } ?: continue
        val phase = sides.single { it !== field }.toString()
        val provenPhase = legacyPhaseToken(phase, source, this) ?: continue
        val phaseUses = body.findAll(FieldAccessExpr::class.java).filter {
            it.nameAsString == "phase" && it.scope.toString() == parameter.nameAsString
        }
        if (phaseUses.size != 1) continue
        val selected = when (comparison.operator) {
            BinaryExpr.Operator.EQUALS -> if (provenPhase == "START") "Post" else "Pre"
            BinaryExpr.Operator.NOT_EQUALS -> if (provenPhase == "START") "Pre" else "Post"
            else -> continue
        }
        parameter.setType("${event.canonical}.$selected")
        guard.remove()
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

private fun splitMixedTickEventHandler(
    method: MethodDeclaration,
    typing: SourceValueTypes,
    source: String,
    pass: StructuralRefactorPass
): Pair<MethodDeclaration, MethodDeclaration>? {
    val parameter = method.parameters.singleOrNull() ?: return null
    if (!method.type.isVoidType) return null
    val family = nativeTickEventFamilies.singleOrNull {
        typing.isTypeName(parameter.typeAsString, "${it.canonical}.Post")
    } ?: return null
    if (method.annotations.none {
            typing.isTypeName(it.nameAsString, "net.neoforged.bus.api.SubscribeEvent")
        }) {
        return null
    }

    val body = method.body.orElse(null) ?: return null
    val phaseUses = body.findAll(FieldAccessExpr::class.java).filter {
        it.nameAsString == "phase" && it.scope.toString() == parameter.nameAsString
    }
    val phaseUse = phaseUses.singleOrNull() ?: return null
    val phaseDispatch = phaseUse.findAncestor(IfStmt::class.java).orElse(null) ?: return null
    if (phaseDispatch.parentNode.orElse(null) !== body || phaseDispatch.elseStmt.isPresent) return null
    val comparison = phaseDispatch.condition as? BinaryExpr ?: return null
    if (comparison.operator != BinaryExpr.Operator.EQUALS) return null
    val phaseField = listOf(comparison.left, comparison.right).filterIsInstance<FieldAccessExpr>()
        .singleOrNull { it === phaseUse } ?: return null
    val phaseToken = listOf(comparison.left, comparison.right).single { it !== phaseField }.toString()
    val selectedPhase = legacyPhaseToken(phaseToken, source, pass) ?: return null

    val eventNames = body.findAll(NameExpr::class.java).filter { it.nameAsString == parameter.nameAsString }
    if (eventNames.size != 1 || eventNames.single().parentNode.orElse(null) !== phaseUse) return null

    val branch = phaseDispatch.thenStmt as? BlockStmt ?: return null
    if (branch.statements.size < 2 || !definitelyExitsTickPhase(branch)) return null
    val dispatchIndex = body.statements.indexOf(phaseDispatch)
    if (dispatchIndex < 0 || dispatchIndex == body.statements.lastIndex) return null

    val owner = method.parentNode.orElse(null) as? TypeDeclaration<*> ?: return null
    val collides = owner.methods.filter { it !== method && it.nameAsString == method.nameAsString }.any { existing ->
        val existingParameter = existing.parameters.singleOrNull() ?: return@any false
        typing.isTypeName(existingParameter.typeAsString, "${family.canonical}.Pre") ||
            typing.isTypeName(existingParameter.typeAsString, "${family.canonical}.Post")
    }
    if (collides) return null

    val prefix = body.statements.take(dispatchIndex)
    val tail = body.statements.drop(dispatchIndex + 1)
    val phaseMethod = method.clone()
    phaseMethod.parameters[0].setType("${family.canonical}.${if (selectedPhase == "START") "Pre" else "Post"}")
    phaseMethod.setBody(BlockStmt().also { target ->
        prefix.forEach { target.addStatement(it.clone()) }
        branch.statements.forEach { target.addStatement(it.clone()) }
    })
    val otherMethod = method.clone()
    otherMethod.parameters[0].setType("${family.canonical}.${if (selectedPhase == "START") "Post" else "Pre"}")
    otherMethod.setBody(BlockStmt().also { target ->
        prefix.forEach { target.addStatement(it.clone()) }
        tail.forEach { target.addStatement(it.clone()) }
    })
    return if (selectedPhase == "START") phaseMethod to otherMethod else otherMethod to phaseMethod
}

private fun legacyPhaseToken(
    token: String,
    source: String,
    pass: StructuralRefactorPass
): String? {
    val executableSource = with(pass) { maskJavaCommentsAndLiterals(source) }
    if (token == "TickEvent.Phase.START" || token == "TickEvent.Phase.END") {
        val importsLegacyTickEvent = Regex(
            """(?m)^\s*import\s+net\.(?:minecraftforge|neoforged\.neoforge)\.event\.(?:tick\.)?TickEvent\s*;"""
        ).containsMatchIn(executableSource)
        return token.substringAfterLast('.').takeIf { importsLegacyTickEvent }
    }
    if (token != "Phase.START" && token != "Phase.END") return null
    val importsLegacyPhase = Regex(
        """(?m)^\s*import\s+net\.(?:minecraftforge|neoforged\.neoforge)\.event\.(?:tick\.)?TickEvent\.Phase\s*;"""
    ).containsMatchIn(executableSource)
    return token.substringAfterLast('.').takeIf { importsLegacyPhase }
}

private fun definitelyExitsTickPhase(statement: Statement): Boolean = when (statement) {
    is ReturnStmt, is ThrowStmt -> true
    is BlockStmt -> statement.statements.lastOrNull()?.let(::definitelyExitsTickPhase) == true
    else -> false
}
