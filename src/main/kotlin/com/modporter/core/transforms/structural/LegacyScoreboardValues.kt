package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt

/**
 * Migrates the 1.20 scoreboard value API to 1.21 without losing the ordering and defaults that
 * were implicit in the old methods.
 *
 * In 1.20 getPlayerScores(Objective) sorted by score ascending and then owner descending,
 * ignoring owner case. In 1.21 listPlayerScores(Objective) only collects entries, so the old
 * comparator is restored at the stream boundary before downstream stable sorts run.
 */
internal fun StructuralRefactorPass.migrateLegacyScoreboardValues(source: String): String {
    if (!source.contains("PlayerScore") &&
        !source.contains("Objective") &&
        !source.contains("hasObjective(") &&
        !source.contains("addObjective(")
    ) {
        return source
    }
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun start(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    fun end(node: Node): Int =
        node.range.map { lineStarts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)
    fun text(node: Node): String {
        val from = start(node)
        val to = end(node)
        return if (from >= 0 && to >= from) source.substring(from, to + 1) else node.toString()
    }

    val typing = SourceValueTypes(this, source)
    val scoreboardTypes = setOf(
        "net.minecraft.world.scores.Scoreboard",
        "net.minecraft.server.ServerScoreboard"
    )
    val levelTypes = setOf(
        "net.minecraft.world.level.Level",
        "net.minecraft.server.level.ServerLevel"
    )

    fun unwrapped(expression: Expression): Expression {
        var current = expression
        while (true) current = (current as? EnclosedExpr)?.inner ?: break
        return current
    }

    fun directStatement(block: BlockStmt, node: Node): Statement? {
        var current: Node = node
        while (current.parentNode.orElse(null) !== block) {
            current = current.parentNode.orElse(null) ?: return null
        }
        return current as? Statement
    }

    fun exitsAbruptly(statement: Statement): Boolean = when (statement) {
        is ReturnStmt, is ThrowStmt -> true
        is BlockStmt -> statement.statements.lastOrNull()?.let(::exitsAbruptly) == true
        else -> false
    }

    fun hasFlowScopedType(name: String, use: Node, canonicalTypes: Set<String>): Boolean {
        val block = use.findAncestor(BlockStmt::class.java).orElse(null) ?: return false
        val useStatement = directStatement(block, use) ?: return false
        val useIndex = block.statements.indexOfFirst { it === useStatement }
        if (useIndex < 0) return false
        return block.statements.take(useIndex).filterIsInstance<IfStmt>().any { guard ->
            if (guard.elseStmt.isPresent || !exitsAbruptly(guard.thenStmt)) return@any false
            val negated = unwrapped(guard.condition) as? UnaryExpr ?: return@any false
            if (negated.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return@any false
            val test = unwrapped(negated.expression) as? InstanceOfExpr ?: return@any false
            val pattern = test.pattern.orElse(null) as? TypePatternExpr ?: return@any false
            pattern.nameAsString == name &&
                canonicalTypes.any { typing.isTypeName(pattern.type.toString(), it) }
        }
    }

    fun typeMatches(expression: Expression, canonicalTypes: Set<String>): Boolean {
        val normalized = unwrapped(expression)
        val resolved = typing.typeOf(normalized.toString(), start(normalized))
        if (resolved in canonicalTypes) return true
        val name = (normalized as? NameExpr)?.nameAsString ?: return false
        return hasFlowScopedType(name, normalized, canonicalTypes)
    }

    fun isScoreboard(expression: Expression): Boolean {
        if (typeMatches(expression, scoreboardTypes)) return true
        val accessor = unwrapped(expression) as? MethodCallExpr ?: return false
        if (accessor.nameAsString != "getScoreboard" || accessor.arguments.isNotEmpty()) return false
        val owner = accessor.scope.orElse(null) ?: return false
        return typeMatches(owner, levelTypes) ||
            typeMatches(owner, setOf("net.minecraft.server.MinecraftServer"))
    }

    fun isObjective(expression: Expression): Boolean {
        if (typeMatches(expression, setOf("net.minecraft.world.scores.Objective"))) return true
        val getter = unwrapped(expression) as? MethodCallExpr ?: return false
        return getter.nameAsString == "getObjective" &&
            getter.arguments.size == 1 &&
            getter.scope.map(::isScoreboard).orElse(false)
    }

    fun isScoreHolder(expression: Expression): Boolean {
        if (typeMatches(expression, setOf("net.minecraft.world.scores.ScoreHolder"))) return true
        val factory = unwrapped(expression) as? MethodCallExpr ?: return false
        return factory.nameAsString == "forNameOnly" &&
            factory.arguments.size == 1 &&
            factory.scope.map {
                typing.isTypeName(it.toString(), "net.minecraft.world.scores.ScoreHolder")
            }.orElse(false)
    }

    fun chainRoot(call: MethodCallExpr): Expression {
        var root: Expression = call
        while (true) {
            val parent = root.parentNode.orElse(null)
            root = when {
                parent is EnclosedExpr -> parent
                parent is MethodCallExpr && parent.scope.orElse(null) === root -> parent
                else -> return root
            }
        }
    }

    data class Edit(val from: Int, val toExclusive: Int, val replacement: String)
    val edits = mutableListOf<Edit>()
    fun replace(node: Node, replacement: String) {
        val from = start(node)
        val to = end(node)
        if (from >= 0 && to >= from) edits += Edit(from, to + 1, replacement)
    }
    fun insert(offset: Int, insertion: String) {
        if (offset >= 0) edits += Edit(offset, offset, insertion)
    }

    val legacyOrder = ".sorted(java.util.Comparator.comparingInt(" +
        "net.minecraft.world.scores.PlayerScoreEntry::value)" +
        ".thenComparing(net.minecraft.world.scores.PlayerScoreEntry::owner, " +
        "String.CASE_INSENSITIVE_ORDER.reversed()))"

    for (call in unit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "getPlayerScores" && it.arguments.size == 1 }
    ) {
        val receiver = call.scope.orElse(null) ?: continue
        if (!isScoreboard(receiver) || !isObjective(call.arguments.single())) continue
        val stream = call.parentNode.orElse(null) as? MethodCallExpr ?: continue
        if (stream.scope.orElse(null) !== call ||
            stream.nameAsString != "stream" ||
            stream.arguments.isNotEmpty()
        ) {
            continue
        }
        replace(call.name, "listPlayerScores")
        insert(end(stream) + 1, legacyOrder)

        val root = chainRoot(stream)
        for (lambda in root.findAll(LambdaExpr::class.java)) {
            val parameter = lambda.parameters.singleOrNull() ?: continue
            if (!parameter.type.isUnknownType &&
                typing.isTypeName(parameter.type.toString(), "net.minecraft.world.scores.Score")
            ) {
                replace(parameter.type, "net.minecraft.world.scores.PlayerScoreEntry")
            }
            for (member in lambda.findAll(MethodCallExpr::class.java)) {
                if (member.findAncestor(LambdaExpr::class.java).orElse(null) !== lambda ||
                    member.arguments.isNotEmpty()
                ) {
                    continue
                }
                val scope = member.scope.orElse(null)?.let(::unwrapped) as? NameExpr ?: continue
                if (scope.nameAsString != parameter.nameAsString) continue
                when (member.nameAsString) {
                    "getScore" -> replace(member.name, "value")
                    "getOwner" -> replace(member.name, "owner")
                }
            }
        }
    }

    for (call in unit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "hasObjective" && it.arguments.size == 1 }
    ) {
        val receiver = call.scope.orElse(null) ?: continue
        if (!isScoreboard(receiver)) continue
        var operand: Node = call
        while (operand.parentNode.orElse(null) is EnclosedExpr) operand = operand.parentNode.get()
        val negation = operand.parentNode.orElse(null) as? UnaryExpr
        val replacement = "${text(receiver)}.getObjective(${text(call.arguments.single())})"
        if (negation != null &&
            negation.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT &&
            negation.expression === operand
        ) {
            replace(negation, "$replacement == null")
        } else {
            replace(call, "($replacement != null)")
        }
    }

    for (call in unit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "addObjective" && it.arguments.size == 4 }
    ) {
        val receiver = call.scope.orElse(null) ?: continue
        if (!isScoreboard(receiver)) continue
        insert(end(call.arguments.last()) + 1, ", false, null")
    }

    for (call in unit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "getOrCreatePlayerScore" && it.arguments.size == 2 }
    ) {
        val receiver = call.scope.orElse(null) ?: continue
        if (!isScoreboard(receiver) || !isObjective(call.arguments[1])) continue
        val holder = call.arguments[0]
        if (!isScoreHolder(holder)) {
            insert(start(holder), "net.minecraft.world.scores.ScoreHolder.forNameOnly(")
            insert(end(holder) + 1, ")")
        }
        val setter = call.parentNode.orElse(null) as? MethodCallExpr
        if (setter != null &&
            setter.scope.orElse(null) === call &&
            setter.nameAsString == "setScore" &&
            setter.arguments.size == 1
        ) {
            replace(setter.name, "set")
        }
    }

    if (edits.isEmpty()) return source
    var migrated = source
    for (edit in edits.distinct().sortedWith(compareByDescending<Edit> { it.from }.thenByDescending { it.toExclusive })) {
        migrated = migrated.substring(0, edit.from) + edit.replacement + migrated.substring(edit.toExclusive)
    }
    return migrated
}
