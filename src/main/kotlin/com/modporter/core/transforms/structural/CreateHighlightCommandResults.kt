package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.TypeParameter

private const val HIGHLIGHT_COMMAND_PACKAGE = "com.simibubi.create.infrastructure.command"
private const val HIGHLIGHT_BRIGADIER_COMMAND = "com.mojang.brigadier.Command"
private const val HIGHLIGHT_COMMAND_SOURCE = "net.minecraft.commands.CommandSourceStack"
private const val HIGHLIGHT_COMMANDS = "net.minecraft.commands.Commands"
private const val HIGHLIGHT_BLOCK_POS = "net.minecraft.core.BlockPos"
private const val HIGHLIGHT_BLOCK_POS_ARGUMENT =
    "net.minecraft.commands.arguments.coordinates.BlockPosArgument"
private const val HIGHLIGHT_SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer"
private const val HIGHLIGHT_PACKET_DISTRIBUTOR =
    "net.neoforged.neoforge.network.PacketDistributor"

/**
 * Applies Create's source-owned 1.21 result contract for its nested highlight dispatch.
 *
 * The target command API queues the nested command and returns void. Create's target source keeps
 * the dispatch in the same order and returns SINGLE_SUCCESS because the closed `highlight <pos>`
 * branch has that exact normal result. Dynamic command strings and unrelated command families are
 * deliberately rejected.
 */
internal fun StructuralRefactorPass.migrateCreateHighlightPrefixedCommandResult(source: String): String {
    if (!source.contains("performPrefixedCommand") || !source.contains("/create highlight ")) {
        return source
    }
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != HIGHLIGHT_COMMAND_PACKAGE) {
        return source
    }
    val owner = unit.types.filterIsInstance<ClassOrInterfaceDeclaration>()
        .singleOrNull { it.nameAsString == "HighlightCommand" }
        ?: return source
    if (!hasExactHighlightBindings(unit)) return source
    if (!hasClosedHighlightPositionBranch(owner)) return source

    val method = owner.members.filterIsInstance<MethodDeclaration>().singleOrNull {
        it.nameAsString == "highlightAssemblyExceptionFor" &&
            it.isPrivate &&
            it.isStatic &&
            it.type.asString() == "int" &&
            it.parameters.size == 2 &&
            it.parameters[0].type.asString() == "ServerPlayer" &&
            it.parameters[0].nameAsString == "player" &&
            it.parameters[1].type.asString() == "CommandSourceStack" &&
            it.parameters[1].nameAsString == "source"
    } ?: return source

    val position = method.findAll(VariableDeclarator::class.java).singleOrNull {
        it.nameAsString == "p" &&
            it.type.asString() == "BlockPos" &&
            it.initializer.orElse(null)?.toString() == "exception.getPosition()"
    } ?: return source
    val command = method.findAll(VariableDeclarator::class.java).singleOrNull {
        it.nameAsString == "command" &&
            it.type.asString() == "String" &&
            it.initializer.orElse(null)?.toString() ==
            """"/create highlight " + p.getX() + " " + p.getY() + " " + p.getZ()"""
    } ?: return source
    if (!position.range.isPresent || !command.range.isPresent) return source

    val returnedCalls = method.findAll(ReturnStmt::class.java).mapNotNull { returned ->
        val call = returned.expression.orElse(null) as? MethodCallExpr ?: return@mapNotNull null
        if (call.nameAsString != "performPrefixedCommand" || call.arguments.size != 2) {
            return@mapNotNull null
        }
        if ((call.arguments[0] as? NameExpr)?.nameAsString != "source") return@mapNotNull null
        if ((call.arguments[1] as? NameExpr)?.nameAsString != "command") return@mapNotNull null
        val getCommands = call.scope.orElse(null) as? MethodCallExpr ?: return@mapNotNull null
        if (getCommands.nameAsString != "getCommands" || getCommands.arguments.isNotEmpty()) {
            return@mapNotNull null
        }
        val server = getCommands.scope.orElse(null) as? FieldAccessExpr ?: return@mapNotNull null
        if (server.nameAsString != "server" ||
            (server.scope as? NameExpr)?.nameAsString != "player") {
            return@mapNotNull null
        }
        returned to call
    }
    val (returned, call) = returnedCalls.singleOrNull() ?: return source
    val commandUses = method.findAll(NameExpr::class.java).filter {
        it.nameAsString == "command" &&
            it.findAncestor(MethodDeclaration::class.java).orElse(null) === method
    }
    if (commandUses.singleOrNull() !== call.arguments[1]) return source

    val offsets = CreateHighlightOffsets(source)
    val start = offsets.start(returned)
    val end = offsets.endExclusive(returned)
    val invocation = offsets.text(call)
    if (start < 0 || end <= start || invocation.isBlank()) return source
    val lineStart = source.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
    val indentation = source.substring(lineStart, start)
    if (indentation.any { !it.isWhitespace() }) return source
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val replacement = "$invocation;$newline${indentation}return Command.SINGLE_SUCCESS;"
    return source.replaceRange(start, end, replacement)
}

private fun hasExactHighlightBindings(unit: CompilationUnit): Boolean =
    listOf(
        "Command" to HIGHLIGHT_BRIGADIER_COMMAND,
        "CommandSourceStack" to HIGHLIGHT_COMMAND_SOURCE,
        "Commands" to HIGHLIGHT_COMMANDS,
        "BlockPos" to HIGHLIGHT_BLOCK_POS,
        "BlockPosArgument" to HIGHLIGHT_BLOCK_POS_ARGUMENT,
        "ServerPlayer" to HIGHLIGHT_SERVER_PLAYER,
        "PacketDistributor" to HIGHLIGHT_PACKET_DISTRIBUTOR
    ).all { (simpleName, fqn) -> hasExactHighlightBinding(unit, simpleName, fqn) }

private fun hasExactHighlightBinding(
    unit: CompilationUnit,
    simpleName: String,
    fqn: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simpleName }) return false
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simpleName }) return false
    val imports = unit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == simpleName
    }.map { it.nameAsString }
    return imports == listOf(fqn)
}

private fun hasClosedHighlightPositionBranch(owner: ClassOrInterfaceDeclaration): Boolean {
    val register = owner.members.filterIsInstance<MethodDeclaration>().singleOrNull {
        it.nameAsString == "register" && it.isPublic && it.isStatic
    } ?: return false
    val root = register.findAll(MethodCallExpr::class.java).filter {
        it.nameAsString == "literal" &&
            it.scope.orElse(null)?.toString() == "Commands" &&
            it.arguments.singleOrNull()?.toString() == "\"highlight\""
    }
    if (root.size != 1) return false

    val branches = register.findAll(LambdaExpr::class.java).filter { lambda ->
        val variables = lambda.findAll(VariableDeclarator::class.java).filter {
            it.findAncestor(LambdaExpr::class.java).orElse(null) === lambda
        }
        val position = variables.singleOrNull {
            it.type.asString() == "BlockPos" &&
                it.initializer.orElse(null)?.toString() ==
                """BlockPosArgument.getLoadedBlockPos(ctx, "pos")"""
        } ?: return@filter false
        val positionName = position.nameAsString
        val returned = lambda.findAll(ReturnStmt::class.java).filter {
            it.findAncestor(LambdaExpr::class.java).orElse(null) === lambda
        }
        if (returned.singleOrNull()?.expression?.orElse(null)?.toString() !=
            "Command.SINGLE_SUCCESS") {
            return@filter false
        }
        val packet = lambda.findAll(ObjectCreationExpr::class.java).singleOrNull {
            it.findAncestor(LambdaExpr::class.java).orElse(null) === lambda &&
                it.type.nameWithScope == "HighlightPacket" &&
                (it.arguments.singleOrNull() as? NameExpr)?.nameAsString == positionName
        } ?: return@filter false
        val send = packet.findAncestor(MethodCallExpr::class.java).orElse(null)
            ?: return@filter false
        if (send.nameAsString != "sendToPlayer" ||
            send.scope.orElse(null)?.toString() != "PacketDistributor" ||
            send.arguments.size != 2 ||
            send.arguments[1] !== packet ||
            send.arguments[0].toString() !=
            "(ServerPlayer) ctx.getSource().getEntity()") {
            return@filter false
        }
        val executes = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return@filter false
        executes.nameAsString == "executes" &&
            executes.arguments.singleOrNull() === lambda &&
            executes.scope.orElse(null)?.toString()
                ?.contains("""Commands.argument("pos", BlockPosArgument.blockPos())""") == true
    }
    return branches.size == 1
}

private class CreateHighlightOffsets(private val source: String) {
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
