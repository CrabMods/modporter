package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

private const val PLAYER_WILL_DESTROY_DESCRIPTOR =
    "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/state/BlockState;" +
        "Lnet/minecraft/world/entity/player/Player;)" +
        "Lnet/minecraft/world/level/block/state/BlockState;"
private const val BLOCK = "net.minecraft.world.level.block.Block"

/**
 * Completes the 1.20 `void` to 1.21 `BlockState` return-flow migration for
 * `Block#playerWillDestroy` overrides that deliberately never called `super`.
 *
 * Returning the incoming state preserves the original method's early-exit and
 * fall-through behavior. Injecting a super call would add vanilla destruction
 * side effects that the source override did not execute.
 */
internal fun StructuralRefactorPass.migrateLegacyPlayerWillDestroyWithoutSuper(source: String): String {
    if (!source.contains("playerWillDestroy") || !source.contains("return;")) return source
    if (PLAYER_WILL_DESTROY_DESCRIPTOR !in
        TargetMembers.methodDescriptors(BLOCK, "playerWillDestroy")
    ) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val candidates = unit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
        if (method.nameAsString != "playerWillDestroy" ||
            method.annotations.none { it.name.identifier == "Override" } ||
            qualified(method.type.asString(), unit) != "net.minecraft.world.level.block.state.BlockState" ||
            method.parameters.size != 4
        ) return@mapNotNull null
        val owner = qualifiedOwningType(method, unit) ?: return@mapNotNull null
        if (!inheritsFromBlock(owner)) return@mapNotNull null
        val expected = listOf(
            "net.minecraft.world.level.Level",
            "net.minecraft.core.BlockPos",
            "net.minecraft.world.level.block.state.BlockState",
            "net.minecraft.world.entity.player.Player"
        )
        if (method.parameters.map { qualified(it.type.asString(), unit) } != expected) return@mapNotNull null
        val body = method.body.orElse(null) ?: return@mapNotNull null
        if (body.findAll(MethodCallExpr::class.java).any {
                it.nameAsString == "playerWillDestroy" &&
                    it.scope.map { scope -> scope.toString() == "super" }.orElse(false)
            }) return@mapNotNull null
        val state = method.parameters[2].nameAsString
        val returns = body.findAll(ReturnStmt::class.java).filter { it.belongsDirectlyTo(method) }
        if (returns.none { it.expression.isEmpty }) return@mapNotNull null
        if (returns.any { returned ->
                returned.expression.map { it.toString() != state }.orElse(false)
            }) return@mapNotNull null
        val last = body.statements.lastOrNull()
        if (last != null && last !is ReturnStmt && last !is ThrowStmt &&
            (last.isIfStmt || last.isSwitchStmt || last.isTryStmt)
        ) return@mapNotNull null
        Triple(method, returns.filter { it.expression.isEmpty }, state)
    }
    if (candidates.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    candidates.forEach { (method, bareReturns, state) ->
        bareReturns.forEach { it.setExpression(NameExpr(state)) }
        val last = method.body.get().statements.lastOrNull()
        if (last !is ReturnStmt && last !is ThrowStmt) {
            method.body.get().addStatement(ReturnStmt(NameExpr(state)))
        }
    }
    return LexicalPreservingPrinter.print(unit)
}

private fun StructuralRefactorPass.inheritsFromBlock(owner: String): Boolean {
    var current: String? = owner
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        if (TargetMembers.inheritsFrom(current, BLOCK)) return true
        current = projectValueFacts.parents[current]
    }
    return false
}

private fun qualifiedOwningType(method: MethodDeclaration, unit: CompilationUnit): String? {
    var owner: Node = method.parentNode.orElse(null) ?: return null
    while (owner !is TypeDeclaration<*>) {
        if (owner is ObjectCreationExpr && owner.anonymousClassBody.isPresent) return null
        owner = owner.parentNode.orElse(null) ?: return null
    }

    val names = mutableListOf<String>()
    var current: Node = owner
    while (current is TypeDeclaration<*>) {
        names += current.nameAsString
        current = current.parentNode.orElse(null) ?: return null
    }
    if (current !== unit) return null

    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    val nested = names.asReversed().joinToString(".")
    return if (packageName.isBlank()) nested else "$packageName.$nested"
}

private fun qualified(type: String, unit: CompilationUnit): String {
    val plain = type.substringBefore('<').trim()
    if (plain.contains('.')) return plain
    unit.imports.firstOrNull {
        !it.isStatic && !it.isAsterisk && it.name.identifier == plain
    }?.let { return it.nameAsString }
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return if (packageName.isBlank()) plain else "$packageName.$plain"
}

private fun ReturnStmt.belongsDirectlyTo(method: MethodDeclaration): Boolean {
    var current: Node? = parentNode.orElse(null)
    while (current != null && current !== method) {
        if (current is LambdaExpr || current is ConstructorDeclaration ||
            current is MethodDeclaration || current is ObjectCreationExpr && current.anonymousClassBody.isPresent
        ) return false
        current = current.parentNode.orElse(null)
    }
    return current === method
}
