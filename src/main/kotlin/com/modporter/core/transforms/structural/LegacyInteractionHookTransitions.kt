package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/**
 * Migrates source-owned living-entity interaction hooks whose 1.21.1 replacements retain
 * cancellation and item-damage behavior.
 *
 * The old living-attack cancellation gate maps to the incoming-damage event for a source owner
 * proven to be a non-player LivingEntity. The new event's mutable amount is deliberately ignored,
 * preserving the old hook's immutable amount behavior. Item damage immunity now receives the
 * stack as well as its source, and an owned dropAllDeathLoot call must provide a proven server
 * level. Shapes without all source/type evidence remain untouched.
 */
internal fun StructuralRefactorPass.migrateLegacyEntityInteractionHooks(source: String): String {
    if (!source.contains("onLivingAttack") &&
        !source.contains("canBeHurtBy") &&
        !source.contains("dropAllDeathLoot")
    ) return source

    val parser = interactionParser()
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val offset = sourceOffset(source)
    LexicalPreservingPrinter.setup(unit)
    var changed = false

    if (targetIncomingDamageContract()) {
        for (conditional in unit.findAll(IfStmt::class.java).toList()) {
            val negated = unwrapInteraction(conditional.condition) as? UnaryExpr ?: continue
            if (negated.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) continue
            val call = unwrapInteraction(negated.expression) as? MethodCallExpr ?: continue
            if (call.nameAsString != "onLivingAttack" ||
                call.arguments.size != 3 ||
                !call.hasInteractionOwner(unit, COMMON_HOOKS) ||
                call.arguments[0] !is ThisExpr ||
                !returnsFalse(conditional.thenStmt)
            ) continue
            val method = conditional.findAncestor(MethodDeclaration::class.java).orElse(null)
                ?: continue
            if (method.nameAsString != "hurt" ||
                !method.type.isPrimitiveType ||
                method.type.asPrimitiveType().type != PrimitiveType.Primitive.BOOLEAN ||
                method.parameters.size != 2 ||
                !typing.isTypeName(method.parameters[0].type.asString(), DAMAGE_SOURCE) ||
                !method.parameters[1].type.isPrimitiveType ||
                method.parameters[1].type.asPrimitiveType().type != PrimitiveType.Primitive.FLOAT
            ) continue
            val sourceName = (call.arguments[1] as? NameExpr)?.nameAsString ?: continue
            val amountName = (call.arguments[2] as? NameExpr)?.nameAsString ?: continue
            if (sourceName != method.parameters[0].nameAsString ||
                amountName != method.parameters[1].nameAsString ||
                !ownerIsProvenNonPlayerLiving(conditional, unit)
            ) continue
            val replacement = parser.parseExpression<Expression>(
                "$NEOFORGE.EVENT_BUS.post(" +
                    "new $LIVING_INCOMING_DAMAGE_EVENT(" +
                    "this, new $DAMAGE_CONTAINER($sourceName, $amountName)" +
                    ")" +
                    ").isCanceled()"
            ).result.orElse(null) ?: continue
            conditional.setCondition(replacement)
            changed = true
        }
    }

    if (targetItemDamageContract()) {
        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            if (call.nameAsString != "canBeHurtBy" || call.arguments.size != 1) continue
            val item = call.scope.orElse(null) as? MethodCallExpr ?: continue
            if (item.nameAsString != "getItem" ||
                item.arguments.isNotEmpty() ||
                item.scope.isEmpty
            ) continue
            val stack = item.scope.get()
            if (typing.typeOf(stack.toString(), offset(call)) != ITEM_STACK ||
                typing.typeOf(call.arguments[0].toString(), offset(call.arguments[0])) !=
                DAMAGE_SOURCE
            ) continue
            call.arguments.add(0, stack.clone())
            changed = true
        }
    }

    if (targetServerDeathLootContract()) {
        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            if (call.nameAsString != "dropAllDeathLoot" ||
                call.arguments.size != 1 ||
                call.scope.orElse(null) !is ThisExpr ||
                typing.typeOf(call.arguments[0].toString(), offset(call.arguments[0])) !=
                DAMAGE_SOURCE
            ) continue
            val statement = call.parentNode.orElse(null) as? ExpressionStmt ?: continue
            val owner = call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
                ?: continue
            if (owner.parentNode.orElse(null) !is CompilationUnit ||
                !ownerInherits(call, unit, LIVING_ENTITY)
            ) continue
            owner.getMethodsByName("dropAllDeathLoot").singleOrNull {
                it.parameters.size == 2 &&
                    typing.isTypeName(it.parameters[0].type.asString(), SERVER_LEVEL) &&
                    typing.isTypeName(it.parameters[1].type.asString(), DAMAGE_SOURCE)
            } ?: continue
            val damageSource = call.arguments[0].toString()
            val replacement = parser.parseStatement(
                "if (level() instanceof $SERVER_LEVEL) " +
                    "this.dropAllDeathLoot(($SERVER_LEVEL) level(), $damageSource);"
            ).result.orElse(null) ?: continue
            statement.replace(replacement)
            changed = true
        }
    }

    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

/**
 * Migrates the exact NeoForge 1.21.1 deployer event calls that still have semantic successors.
 *
 * The removed ItemStack#onBlockStartBreak hook deliberately remains untouched because NeoForge
 * exposes no cancellation-equivalent replacement.
 */
internal fun StructuralRefactorPass.migrateLegacyDeployerInteractionHooks(source: String): String {
    if (!source.contains("onLeftClickBlock") &&
        !source.contains("onBlockBreakEvent") &&
        !source.contains("getOriginalTarget")
    ) return source

    val parser = interactionParser()
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val offset = sourceOffset(source)
    LexicalPreservingPrinter.setup(unit)
    var changed = false

    if (targetLeftClickContract()) {
        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            if (call.nameAsString != "onLeftClickBlock" ||
                call.arguments.size != 3 ||
                !call.hasInteractionOwner(unit, COMMON_HOOKS) ||
                !sourceTypeInherits(
                    typing.typeOf(call.arguments[0].toString(), offset(call.arguments[0])),
                    PLAYER
                ) ||
                typing.typeOf(call.arguments[1].toString(), offset(call.arguments[1])) !=
                BLOCK_POS ||
                typing.typeOf(call.arguments[2].toString(), offset(call.arguments[2])) !=
                DIRECTION
            ) continue
            val action = parser.parseExpression<Expression>(
                "$PLAYER_ACTION.START_DESTROY_BLOCK"
            ).result.orElse(null) ?: continue
            call.addArgument(action)
            changed = true
        }
    }

    if (targetBlockBreakContract()) {
        for (comparison in unit.findAll(BinaryExpr::class.java).toList()) {
            if (comparison.operator != BinaryExpr.Operator.EQUALS) continue
            val call = when {
                unwrapInteraction(comparison.left) is MethodCallExpr &&
                    isNegativeOne(comparison.right) ->
                    unwrapInteraction(comparison.left) as MethodCallExpr
                unwrapInteraction(comparison.right) is MethodCallExpr &&
                    isNegativeOne(comparison.left) ->
                    unwrapInteraction(comparison.right) as MethodCallExpr
                else -> continue
            }
            if (call.nameAsString != "onBlockBreakEvent" ||
                call.arguments.size != 4 ||
                !call.hasInteractionOwner(unit, COMMON_HOOKS) ||
                !sourceTypeInherits(
                    typing.typeOf(call.arguments[0].toString(), offset(call.arguments[0])),
                    LEVEL
                ) ||
                typing.typeOf(call.arguments[1].toString(), offset(call.arguments[1])) !=
                GAME_TYPE ||
                !sourceTypeInherits(
                    typing.typeOf(call.arguments[2].toString(), offset(call.arguments[2])),
                    SERVER_PLAYER
                ) ||
                typing.typeOf(call.arguments[3].toString(), offset(call.arguments[3])) !=
                BLOCK_POS
            ) continue
            val method = comparison.findAncestor(MethodDeclaration::class.java).orElse(null)
                ?: continue
            val state = method.findAll(VariableDeclarator::class.java)
                .asSequence()
                .filter { offset(it) < offset(call) }
                .filter { typing.isTypeName(it.type.asString(), BLOCK_STATE) }
                .filter { variable ->
                    val getter = variable.initializer.orElse(null) as? MethodCallExpr
                        ?: return@filter false
                    getter.nameAsString == "getBlockState" &&
                        getter.arguments.size == 1 &&
                        getter.scope.map {
                            it.toString() == call.arguments[0].toString()
                        }.orElse(false) &&
                        getter.arguments[0].toString() == call.arguments[3].toString()
                }
                .maxByOrNull { offset(it) }
                ?: continue
            call.setName("fireBlockBreak")
            call.addArgument(state.nameAsString)
            val canceled = parser.parseExpression<Expression>(
                "${call}.isCanceled()"
            ).result.orElse(null) ?: continue
            comparison.replace(canceled)
            changed = true
        }
    }

    if (targetLivingTargetContract()) {
        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            if (call.nameAsString != "getOriginalTarget" ||
                call.arguments.isNotEmpty() ||
                call.scope.isEmpty ||
                typing.typeOf(call.scope.get().toString(), offset(call)) !=
                LIVING_CHANGE_TARGET_EVENT
            ) continue
            call.setName("getOriginalAboutToBeSetTarget")
            changed = true
        }
    }

    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

private fun interactionParser(): JavaParser =
    JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

private fun sourceOffset(source: String): (Node) -> Int {
    val starts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    return { node ->
        node.range.map { starts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)
    }
}

private fun unwrapInteraction(expression: Expression): Expression {
    var current = expression
    while (current is EnclosedExpr) current = current.inner
    return current
}

private fun returnsFalse(statement: Statement): Boolean {
    val returned = when (statement) {
        is ReturnStmt -> statement
        is BlockStmt -> statement.statements.singleOrNull() as? ReturnStmt
        else -> null
    } ?: return false
    val value = returned.expression.orElse(null) as? BooleanLiteralExpr ?: return false
    return !value.value
}

private fun isNegativeOne(expression: Expression): Boolean {
    val minus = unwrapInteraction(expression) as? UnaryExpr ?: return false
    if (minus.operator != UnaryExpr.Operator.MINUS) return false
    val one = unwrapInteraction(minus.expression) as? IntegerLiteralExpr ?: return false
    return one.value.replace("_", "") == "1"
}

private fun MethodCallExpr.hasInteractionOwner(
    unit: CompilationUnit,
    canonical: String
): Boolean {
    val owner = scope.orElse(null)?.toString() ?: return false
    if (owner == canonical) return true
    val simple = canonical.substringAfterLast('.')
    if (owner != simple ||
        unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .any { it.nameAsString == simple }
    ) return false
    return unit.imports.any {
        !it.isStatic &&
            (!it.isAsterisk && it.nameAsString == canonical ||
                it.isAsterisk && it.nameAsString == canonical.substringBeforeLast('.'))
    }
}

private fun StructuralRefactorPass.ownerInherits(
    node: Node,
    unit: CompilationUnit,
    ancestor: String
): Boolean {
    val owner = node.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
        ?: return false
    if (owner.parentNode.orElse(null) !is CompilationUnit) return false
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    val canonical = if (packageName.isBlank()) {
        owner.nameAsString
    } else {
        "$packageName.${owner.nameAsString}"
    }
    return sourceTypeInherits(canonical, ancestor)
}

private fun StructuralRefactorPass.ownerIsProvenNonPlayerLiving(
    node: Node,
    unit: CompilationUnit
): Boolean {
    val owner = node.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
        ?: return false
    if (owner.parentNode.orElse(null) !is CompilationUnit) return false
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    val canonical = if (packageName.isBlank()) {
        owner.nameAsString
    } else {
        "$packageName.${owner.nameAsString}"
    }
    var current = canonical
    val seen = mutableSetOf<String>()
    while (seen.add(current)) {
        if (TargetMembers.hasClass(current)) {
            return TargetMembers.inheritsFrom(current, LIVING_ENTITY) &&
                !TargetMembers.inheritsFrom(current, PLAYER)
        }
        if (current !in projectValueFacts.types) return false
        current = projectValueFacts.parents[current] ?: return false
    }
    return false
}

private fun StructuralRefactorPass.sourceTypeInherits(
    type: String?,
    ancestor: String
): Boolean {
    var current = type ?: return false
    val seen = mutableSetOf<String>()
    while (seen.add(current)) {
        if (TargetMembers.inheritsFrom(current, ancestor)) return true
        current = projectValueFacts.parents[current] ?: return false
    }
    return false
}

private const val COMMON_HOOKS = "net.neoforged.neoforge.common.CommonHooks"
private const val NEOFORGE = "net.neoforged.neoforge.common.NeoForge"
private const val CANCELLABLE_EVENT = "net.neoforged.bus.api.ICancellableEvent"
private const val DAMAGE_CONTAINER =
    "net.neoforged.neoforge.common.damagesource.DamageContainer"
private const val LIVING_INCOMING_DAMAGE_EVENT =
    "net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent"
private const val ITEM_EXTENSION =
    "net.neoforged.neoforge.common.extensions.IItemExtension"
private const val LIVING_CHANGE_TARGET_EVENT =
    "net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent"
private const val PLAYER_ACTION =
    "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action"
private const val PLAYER_ACTION_BINARY =
    "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket\$Action"
private const val PLAYER = "net.minecraft.world.entity.player.Player"
private const val SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer"
private const val LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity"
private const val LEVEL = "net.minecraft.world.level.Level"
private const val SERVER_LEVEL = "net.minecraft.server.level.ServerLevel"
private const val GAME_TYPE = "net.minecraft.world.level.GameType"
private const val BLOCK_POS = "net.minecraft.core.BlockPos"
private const val DIRECTION = "net.minecraft.core.Direction"
private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
private const val DAMAGE_SOURCE = "net.minecraft.world.damagesource.DamageSource"

private fun targetIncomingDamageContract(): Boolean =
    TargetMembers.fieldDescriptor(NEOFORGE, "EVENT_BUS") ==
        "Lnet/neoforged/bus/api/IEventBus;" &&
        TargetMembers.inheritedMethod(
            DAMAGE_CONTAINER,
            "<init>",
            "(Lnet/minecraft/world/damagesource/DamageSource;F)V"
        )?.let {
            it.owner == DAMAGE_CONTAINER && it.access == "public"
        } == true &&
        TargetMembers.inheritedMethod(
            LIVING_INCOMING_DAMAGE_EVENT,
            "<init>",
            "(Lnet/minecraft/world/entity/LivingEntity;" +
                "Lnet/neoforged/neoforge/common/damagesource/DamageContainer;)V"
        )?.let {
            it.owner == LIVING_INCOMING_DAMAGE_EVENT && it.access == "public"
        } == true &&
        TargetMembers.inheritsFrom(
            LIVING_INCOMING_DAMAGE_EVENT,
            CANCELLABLE_EVENT
        )

private fun targetItemDamageContract(): Boolean =
    TargetMembers.inheritedMethod(
        ITEM_EXTENSION,
        "canBeHurtBy",
        "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/damagesource/DamageSource;)Z"
    )?.owner == ITEM_EXTENSION

private fun targetServerDeathLootContract(): Boolean =
    TargetMembers.inheritedMethod(
        LIVING_ENTITY,
        "dropAllDeathLoot",
        "(Lnet/minecraft/server/level/ServerLevel;" +
            "Lnet/minecraft/world/damagesource/DamageSource;)V"
    )?.access == "protected" &&
        TargetMembers.inheritedMethod(
            "net.minecraft.world.entity.Entity",
            "level",
            "()Lnet/minecraft/world/level/Level;"
        ) != null

private fun targetLeftClickContract(): Boolean =
    TargetMembers.inheritedMethod(
        COMMON_HOOKS,
        "onLeftClickBlock",
        "(Lnet/minecraft/world/entity/player/Player;" +
            "Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/core/Direction;" +
            "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket\$Action;)" +
            "Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent\$LeftClickBlock;"
    )?.owner == COMMON_HOOKS &&
        TargetMembers.hasField(PLAYER_ACTION_BINARY, "START_DESTROY_BLOCK")

private fun targetBlockBreakContract(): Boolean =
    TargetMembers.inheritedMethod(
        COMMON_HOOKS,
        "fireBlockBreak",
        "(Lnet/minecraft/world/level/Level;" +
            "Lnet/minecraft/world/level/GameType;" +
            "Lnet/minecraft/server/level/ServerPlayer;" +
            "Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/world/level/block/state/BlockState;)" +
            "Lnet/neoforged/neoforge/event/level/BlockEvent\$BreakEvent;"
    )?.owner == COMMON_HOOKS

private fun targetLivingTargetContract(): Boolean =
    TargetMembers.inheritedMethod(
        LIVING_CHANGE_TARGET_EVENT,
        "getOriginalAboutToBeSetTarget",
        "()Lnet/minecraft/world/entity/LivingEntity;"
    )?.owner == LIVING_CHANGE_TARGET_EVENT
