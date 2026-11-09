package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.TypeParameter

private const val LEGACY_NETHER_MINECRAFT_SERVER = "net.minecraft.server.MinecraftServer"

/**
 * Preserves the old dedicated-server Nether gate through the target's level-aware API.
 *
 * The target method needs the actual Nether level to retain DedicatedServer.allowNether.
 * A missing level is also a failed transition, so this rewrites only the exact negative
 * null-return guard. Other boolean shapes remain unchanged unless their source contract closes.
 */
internal fun StructuralRefactorPass.migrateLegacyMinecraftServerNetherGate(source: String): String {
    if (!source.contains("isNetherEnabled")) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val calls = unit.findAll(MethodCallExpr::class.java).filter {
        it.nameAsString == "isNetherEnabled" && it.arguments.isEmpty()
    }
    if (calls.size != 1) return source

    val call = calls.single()
    val receiver = call.scope.orElse(null) as? NameExpr ?: return source
    val negation = call.parentNode.orElse(null) as? UnaryExpr ?: return source
    if (negation.expression !== call || negation.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
        return source
    }
    val guard = negation.parentNode.orElse(null) as? IfStmt ?: return source
    if (guard.condition !== negation || !isNullReturn(guard.thenStmt)) return source
    val method = guard.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return source
    if (!isExactMinecraftServerReceiver(receiver.nameAsString, call, method, unit)) return source

    val offsets = LegacyNetherOffsets(source)
    val guardStart = offsets.start(guard)
    val guardEnd = offsets.endExclusive(guard)
    val failure = offsets.text(guard.thenStmt)
    if (guardStart < 0 || guardEnd <= guardStart || failure.isBlank()) return source
    val lineStart = source.lastIndexOf('\n', guardStart - 1).let { if (it < 0) 0 else it + 1 }
    val indentation = source.substring(lineStart, guardStart)
    if (indentation.any { !it.isWhitespace() }) return source
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val netherLevel = uniqueNetherLevelName(method)
    val server = receiver.nameAsString
    val replacement = buildString {
        append("net.minecraft.server.level.ServerLevel ")
        append(netherLevel)
        append(" = ")
        append(server)
        append(".getLevel(net.minecraft.world.level.Level.NETHER);")
        append(newline)
        append(indentation)
        append("if (")
        append(netherLevel)
        append(" == null || !")
        append(server)
        append(".isLevelEnabled(")
        append(netherLevel)
        append(")) ")
        append(failure)
    }
    return source.replaceRange(guardStart, guardEnd, replacement)
}

private fun isNullReturn(statement: Node): Boolean {
    val returned = when (statement) {
        is ReturnStmt -> statement
        is BlockStmt -> statement.statements.singleOrNull() as? ReturnStmt
        else -> null
    } ?: return false
    return returned.expression.orElse(null) is NullLiteralExpr
}

private fun isExactMinecraftServerReceiver(
    name: String,
    use: Node,
    method: MethodDeclaration,
    unit: CompilationUnit
): Boolean {
    val declarations = buildList<Node> {
        addAll(method.parameters.filter { it.nameAsString == name })
        addAll(method.findAll(VariableDeclarator::class.java).filter {
            it.nameAsString == name &&
                it.findAncestor(MethodDeclaration::class.java).orElse(null) === method
        })
    }
    val declaration = declarations.singleOrNull() ?: return false
    val type = when (declaration) {
        is Parameter -> declaration.type.asString()
        is VariableDeclarator -> declaration.type.asString()
        else -> return false
    }
    if (!isExactMinecraftServerType(type, unit)) return false
    val declarationRange = declaration.range.orElse(null) ?: return false
    val useRange = use.range.orElse(null) ?: return false
    return declarationRange.begin.line < useRange.begin.line ||
        declarationRange.begin.line == useRange.begin.line &&
        declarationRange.begin.column < useRange.begin.column
}

private fun isExactMinecraftServerType(type: String, unit: CompilationUnit): Boolean {
    if (type == LEGACY_NETHER_MINECRAFT_SERVER) return true
    if (type != "MinecraftServer") return false
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == type }) return false
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == type }) return false
    val imports = unit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == type
    }.map { it.nameAsString }
    return imports == listOf(LEGACY_NETHER_MINECRAFT_SERVER)
}

private fun uniqueNetherLevelName(method: MethodDeclaration): String {
    val occupied = buildSet {
        addAll(method.parameters.map { it.nameAsString })
        addAll(method.findAll(VariableDeclarator::class.java).map { it.nameAsString })
    }
    if ("netherLevel" !in occupied) return "netherLevel"
    var suffix = 2
    while ("netherLevel$suffix" in occupied) suffix++
    return "netherLevel$suffix"
}

private class LegacyNetherOffsets(private val source: String) {
    private val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }

    fun start(node: Node): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)

    fun endExclusive(node: Node): Int = node.range
        .map { lineStarts[it.end.line - 1] + it.end.column }
        .orElse(-1)

    fun text(node: Node): String {
        val start = start(node)
        val end = endExclusive(node)
        return if (start < 0 || end < start) "" else source.substring(start, end)
    }
}
