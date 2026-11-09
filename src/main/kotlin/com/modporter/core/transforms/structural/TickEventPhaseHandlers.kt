package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

private data class NativeTickEventFamily(
    val canonical: String,
    val legacy: Set<String> = emptySet(),
    val levelFields: Boolean = false,
    val renderFields: Boolean = false
) {
    val baseOwners: Set<String> = legacy + canonical
}

private val nativeTickEventFamilies = listOf(
    NativeTickEventFamily(
        "net.neoforged.neoforge.client.event.ClientTickEvent",
        setOf(
            "net.minecraftforge.event.TickEvent.ClientTickEvent",
            "net.neoforged.neoforge.event.TickEvent.ClientTickEvent"
        )
    ),
    NativeTickEventFamily(
        "net.neoforged.neoforge.event.tick.ServerTickEvent",
        setOf(
            "net.minecraftforge.event.TickEvent.ServerTickEvent",
            "net.neoforged.neoforge.event.TickEvent.ServerTickEvent"
        )
    ),
    NativeTickEventFamily(
        "net.neoforged.neoforge.event.tick.LevelTickEvent",
        setOf(
            "net.minecraftforge.event.TickEvent.LevelTickEvent",
            "net.neoforged.neoforge.event.TickEvent.LevelTickEvent"
        ),
        levelFields = true
    ),
    NativeTickEventFamily(
        "net.neoforged.neoforge.event.tick.PlayerTickEvent",
        setOf(
            "net.minecraftforge.event.TickEvent.PlayerTickEvent",
            "net.neoforged.neoforge.event.TickEvent.PlayerTickEvent"
        )
    ),
    NativeTickEventFamily(
        "net.neoforged.neoforge.client.event.RenderFrameEvent",
        setOf(
            "net.minecraftforge.event.TickEvent.RenderTickEvent",
            "net.neoforged.neoforge.event.TickEvent.RenderTickEvent"
        ),
        renderFields = true
    )
)

/**
 * Split a legacy phase-filtered subscription only when the source proves either one selected
 * native phase or the closed two-phase dispatch shape used by legacy client tick handlers.
 */
internal fun StructuralRefactorPass.migrateUnsplitTickEventHandlers(source: String): String {
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
    val phaseMigrated = if (changed) LexicalPreservingPrinter.print(unit) else source
    return migrateLegacyBaseTickEventHandlers(phaseMigrated)
}

/**
 * Legacy base tick events carried a phase field. NeoForge exposes one base event for shared
 * logic plus typed Pre/Post subclasses for subscriptions. Normalize the shared helper and fan
 * out only exact subscribed handlers, preserving the original SubscribeEvent arguments.
 */
private fun StructuralRefactorPass.migrateLegacyBaseTickEventHandlers(source: String): String {
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val candidates = unit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
        val parameter = method.parameters.singleOrNull() ?: return@mapNotNull null
        if (!method.type.isVoidType || method.body.isEmpty) return@mapNotNull null
        val family = nativeTickEventFamilies.singleOrNull { candidate ->
            candidate.baseOwners.any { typing.isTypeName(parameter.typeAsString, it) }
        } ?: return@mapNotNull null
        if (typing.isTypeName(parameter.typeAsString, "${family.canonical}.Pre") ||
            typing.isTypeName(parameter.typeAsString, "${family.canonical}.Post")
        ) {
            return@mapNotNull null
        }
        val resolvedOwner = family.baseOwners.single { typing.isTypeName(parameter.typeAsString, it) }
        val body = method.body.orElseThrow()
        val hasLegacyField = body.findAll(FieldAccessExpr::class.java).any { field ->
            field.scope.toString() == parameter.nameAsString &&
                (field.nameAsString == "phase" ||
                    family.levelFields && field.nameAsString in setOf("level", "side") ||
                    family.renderFields && field.nameAsString == "renderTickTime")
        } || family.renderFields && body.findAll(MethodCallExpr::class.java).any { call ->
            call.nameAsString == "getDeltaTracker" &&
                call.arguments.isEmpty() &&
                call.scope.orElse(null)?.toString() == parameter.nameAsString
        }
        val subscribed = method.annotations.any {
            typing.isTypeName(it.nameAsString, "net.neoforged.bus.api.SubscribeEvent") ||
                typing.isTypeName(it.nameAsString, "net.minecraftforge.eventbus.api.SubscribeEvent")
        }
        if (resolvedOwner == family.canonical && !hasLegacyField && !subscribed) return@mapNotNull null
        Triple(method, parameter, family)
    }
    if (candidates.isEmpty()) return source

    // Refuse the file before mutation when a wrapper name already belongs to the owner or when
    // a phase access is not one of the exact legacy comparisons this migration can preserve.
    for ((method, parameter, family) in candidates) {
        val owner = method.parentNode.orElse(null) as? TypeDeclaration<*> ?: return source
        val subscribe = method.annotations.singleOrNull {
            typing.isTypeName(it.nameAsString, "net.neoforged.bus.api.SubscribeEvent") ||
                typing.isTypeName(it.nameAsString, "net.minecraftforge.eventbus.api.SubscribeEvent")
        }
        if (subscribe != null) {
            val wrapperNames = setOf(
                "${method.nameAsString}\$modporterPre",
                "${method.nameAsString}\$modporterPost"
            )
            if (owner.methods.any { it !== method && it.nameAsString in wrapperNames }) return source
        }
        val phaseUses = method.body.orElseThrow().findAll(FieldAccessExpr::class.java).filter {
            it.nameAsString == "phase" && it.scope.toString() == parameter.nameAsString
        }
        if (phaseUses.any { legacyTickPhaseReplacement(it, parameter.nameAsString, parameter.typeAsString, family, source, this) == null }) {
            return source
        }
    }

    LexicalPreservingPrinter.setup(unit)
    val canonicalImports = linkedSetOf<String>()
    val legacyImports = linkedSetOf<String>()
    var changed = false
    for ((method, parameter, family) in candidates) {
        val body = method.body.orElseThrow()
        val parameterName = parameter.nameAsString
        val simpleTarget = family.canonical.substringAfterLast('.')
        val usedSimpleType = '.' !in parameter.typeAsString
        val resolvedOwner = family.baseOwners.single {
            typing.isTypeName(parameter.typeAsString, it)
        }

        val phaseUses = body.findAll(FieldAccessExpr::class.java).filter {
            it.nameAsString == "phase" && it.scope.toString() == parameterName
        }
        val phaseReplacements = phaseUses.map {
            it.findAncestor(BinaryExpr::class.java).orElseThrow() to
                legacyTickPhaseReplacement(it, parameterName, parameter.typeAsString, family, source, this)!!
        }.distinctBy { it.first }
        phaseReplacements.forEach { (comparison, replacement) ->
            comparison.replace(parser.parseExpression<Expression>(replacement).result.orElseThrow())
            changed = true
        }

        if (family.levelFields) {
            var needsLogicalSide = false
            body.findAll(FieldAccessExpr::class.java).filter {
                it.scope.toString() == parameterName && it.nameAsString in setOf("level", "side")
            }.toList().forEach { field ->
                if (field.nameAsString == "side") needsLogicalSide = true
                val replacement = if (field.nameAsString == "level") {
                    "$parameterName.getLevel()"
                } else {
                    "($parameterName.getLevel().isClientSide() ? LogicalSide.CLIENT : LogicalSide.SERVER)"
                }
                field.replace(parser.parseExpression<Expression>(replacement).result.orElseThrow())
                changed = true
            }
            if (needsLogicalSide) canonicalImports += "net.neoforged.fml.LogicalSide"
        }

        if (family.renderFields) {
            body.findAll(FieldAccessExpr::class.java).filter {
                it.scope.toString() == parameterName && it.nameAsString == "renderTickTime"
            }.toList().forEach { field ->
                field.replace(
                    parser.parseExpression<Expression>(
                        "$parameterName.getPartialTick().getGameTimeDeltaPartialTick(false)"
                    ).result.orElseThrow()
                )
                changed = true
            }
            body.findAll(MethodCallExpr::class.java).filter { call ->
                call.nameAsString == "getDeltaTracker" &&
                    call.arguments.isEmpty() &&
                    call.scope.orElse(null)?.toString() == parameterName &&
                    (call.parentNode.orElse(null) as? MethodCallExpr)?.let { outer ->
                        outer.nameAsString == "getGameTimeDeltaPartialTick" &&
                            outer.arguments.singleOrNull()?.toString() == "false"
                    } == true
            }.toList().forEach { call ->
                call.setName("getPartialTick")
                changed = true
            }
        }

        if (resolvedOwner in family.legacy) legacyImports += resolvedOwner
        parameter.setType(if (usedSimpleType) simpleTarget else family.canonical)
        if (usedSimpleType) canonicalImports += family.canonical
        changed = true

        val subscribe = method.annotations.singleOrNull {
            typing.isTypeName(it.nameAsString, "net.neoforged.bus.api.SubscribeEvent") ||
                typing.isTypeName(it.nameAsString, "net.minecraftforge.eventbus.api.SubscribeEvent")
        } ?: continue
        val owner = method.parentNode.orElseThrow() as TypeDeclaration<*>
        val insertion = owner.members.indexOf(method)
        val wrappers = listOf("Pre", "Post").map { phase ->
            val wrapper = method.clone()
            wrapper.setName("${method.nameAsString}\$modporter$phase")
            wrapper.parameters[0].setType("${if (usedSimpleType) simpleTarget else family.canonical}.$phase")
            wrapper.annotations.clear()
            wrapper.addAnnotation(subscribe.clone())
            wrapper.setBody(BlockStmt().addStatement("${method.nameAsString}($parameterName);"))
            parser.parseBodyDeclaration<MethodDeclaration>(wrapper.toString()).result.orElseThrow()
        }
        method.annotations.remove(subscribe)
        wrappers.forEachIndexed { index, wrapper ->
            owner.members.add(insertion + index + 1, wrapper as BodyDeclaration<*>)
        }
    }
    if (!changed) return source

    var result = LexicalPreservingPrinter.print(unit)
    legacyImports.forEach { result = removeImport(result, it) }
    canonicalImports.forEach { result = addImportIfMissing(result, it) }
    return result
}

private fun legacyTickPhaseReplacement(
    field: FieldAccessExpr,
    parameterName: String,
    parameterType: String,
    family: NativeTickEventFamily,
    source: String,
    pass: StructuralRefactorPass
): String? {
    val comparison = field.parentNode.orElse(null) as? BinaryExpr ?: return null
    if (comparison.operator !in setOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS)) return null
    val other = when {
        comparison.left === field -> comparison.right
        comparison.right === field -> comparison.left
        else -> return null
    }
    val legacyPhase = legacyPhaseToken(other.toString(), source, pass) ?: return null
    val eventType = if ('.' in parameterType) family.canonical else family.canonical.substringAfterLast('.')
    val target = "$parameterName instanceof $eventType.${if (legacyPhase == "START") "Pre" else "Post"}"
    return if (comparison.operator == BinaryExpr.Operator.EQUALS) target else "!($target)"
}

internal fun StructuralRefactorPass.validateLegacyTickPhaseValueUses(source: String) {
    if (!source.contains(".phase")) return
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return
    val typing = SourceValueTypes(this, source)
    for (method in unit.findAll(MethodDeclaration::class.java)) {
        val parameter = method.parameters.singleOrNull() ?: continue
        val family = nativeTickEventFamilies.singleOrNull { candidate ->
            candidate.baseOwners.any { typing.isTypeName(parameter.typeAsString, it) }
        } ?: continue
        val invalid = method.findAll(FieldAccessExpr::class.java).any {
            it.nameAsString == "phase" &&
                it.scope.toString() == parameter.nameAsString &&
                legacyTickPhaseReplacement(it, parameter.nameAsString, parameter.typeAsString, family, source, this) == null
        }
        if (invalid) {
            throw IllegalStateException(
                "Cannot migrate ${method.nameAsString}: ${parameter.nameAsString}.phase is used as a value"
            )
        }
    }
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
