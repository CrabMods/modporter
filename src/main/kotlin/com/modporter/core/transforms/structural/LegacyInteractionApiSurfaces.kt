package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/**
 * 1.20.1 -> 1.21.1 call surfaces whose new shape is decided by the receiver's platform type
 * (proven with the source typing) and the target member that exists in 1.21.1 (proven with the
 * target class table), never by the declaring mod: BlockState neighbor dispatch, the LiquidBlock
 * fluid field, the shoot-smoke level event, the SmallFireball movement-vector constructor, the
 * unqualified GameTestHelper mock player, the SynchedEntityData builder hook of direct hanging
 * entities, the post-attack enchantment hooks and the knockback enchantment bonus.
 */
internal fun StructuralRefactorPass.migrateLegacyInteractionApiSurfaces(source: String): String {
    var result = migrateBlockStateNeighborDispatch(source)
    result = migrateLiquidBlockFluidField(result)
    result = migrateShootSmokeLevelEvent(result)
    result = migrateSmallFireballVectorConstructor(result)
    result = migrateUnqualifiedGameTestMockPlayer(result)
    result = migrateDirectHangingEntityDataBuilder(result)
    result = migrateLegacyPostAttackEnchantmentEffects(result)
    result = migrateLegacyKnockbackEnchantmentBonus(result)
    return result
}

private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
private const val LIQUID_BLOCK = "net.minecraft.world.level.block.LiquidBlock"
private const val LEVEL_EVENT = "net.minecraft.world.level.block.LevelEvent"
private const val SMALL_FIREBALL = "net.minecraft.world.entity.projectile.SmallFireball"
private const val GAME_TEST_HELPER = "net.minecraft.gametest.framework.GameTestHelper"
private const val HANGING_ENTITY = "net.minecraft.world.entity.decoration.HangingEntity"
private const val ENCHANTMENT_HELPER = "net.minecraft.world.item.enchantment.EnchantmentHelper"
private const val DAMAGE_SOURCE = "net.minecraft.world.damagesource.DamageSource"

private data class InteractionSource(
    val source: String,
    val unit: CompilationUnit,
    val typing: SourceValueTypes,
    val lineStarts: List<Int>
) {
    fun offset(node: Node): Int = node.range.map {
        lineStarts[it.begin.line - 1] + it.begin.column - 1
    }.orElse(-1)
}

private fun StructuralRefactorPass.parseInteractionSource(
    source: String
): Pair<JavaParser, InteractionSource>? {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return null
    val starts = mutableListOf(0)
    source.forEachIndexed { index, character ->
        if (character == '\n') starts += index + 1
    }
    return parser to InteractionSource(source, unit, SourceValueTypes(this, source), starts)
}

private fun StructuralRefactorPass.migrateBlockStateNeighborDispatch(source: String): String {
    if (!source.contains(".neighborChanged(")) return source
    val descriptor = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V"
    if (TargetMembers.inheritedMethod(BLOCK_STATE, "handleNeighborChanged", descriptor) == null) {
        return source
    }
    val (_, parsed) = parseInteractionSource(source) ?: return source
    val calls = parsed.unit.findAll(MethodCallExpr::class.java).filter { call ->
        call.nameAsString == "neighborChanged" &&
            call.arguments.size == 5 &&
            call.scope.map {
                parsed.typing.typeOf(it.toString(), parsed.offset(call)) == BLOCK_STATE
            }.orElse(false)
    }
    if (calls.isEmpty()) return source
    LexicalPreservingPrinter.setup(parsed.unit)
    calls.forEach { it.setName("handleNeighborChanged") }
    return printWithSourceNewlines(parsed.unit, source)
}

private fun StructuralRefactorPass.migrateLiquidBlockFluidField(source: String): String {
    if (!source.contains(".getFluid()") ||
        TargetMembers.fieldDescriptor(LIQUID_BLOCK, "fluid") !=
        "Lnet/minecraft/world/level/material/FlowingFluid;"
    ) {
        return source
    }
    val (_, parsed) = parseInteractionSource(source) ?: return source
    val calls = parsed.unit.findAll(MethodCallExpr::class.java).filter { call ->
        call.nameAsString == "getFluid" &&
            call.arguments.isEmpty() &&
            call.scope.map {
                parsed.typing.typeOf(it.toString(), parsed.offset(call)) == LIQUID_BLOCK
            }.orElse(false)
    }
    if (calls.isEmpty()) return source
    LexicalPreservingPrinter.setup(parsed.unit)
    calls.forEach { call ->
        call.replace(FieldAccessExpr(call.scope.get().clone(), "fluid"))
    }
    return printWithSourceNewlines(parsed.unit, source)
}

private fun StructuralRefactorPass.migrateShootSmokeLevelEvent(source: String): String {
    if (!source.contains("LevelEvent.PARTICLES_SHOOT") ||
        TargetMembers.fieldDescriptor(LEVEL_EVENT, "PARTICLES_SHOOT_SMOKE") != "I"
    ) {
        return source
    }
    val (_, parsed) = parseInteractionSource(source) ?: return source
    val fields = parsed.unit.findAll(FieldAccessExpr::class.java).filter { field ->
        field.nameAsString == "PARTICLES_SHOOT" &&
            parsed.typing.isTypeName(field.scope.toString(), LEVEL_EVENT)
    }
    if (fields.isEmpty()) return source
    LexicalPreservingPrinter.setup(parsed.unit)
    fields.forEach { it.setName("PARTICLES_SHOOT_SMOKE") }
    return printWithSourceNewlines(parsed.unit, source)
}

private fun StructuralRefactorPass.migrateSmallFireballVectorConstructor(source: String): String {
    if (!source.contains("new SmallFireball") ||
        "(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/phys/Vec3;)V" !in
        TargetMembers.methodDescriptors(SMALL_FIREBALL, "<init>")
    ) {
        return source
    }
    val (_, parsed) = parseInteractionSource(source) ?: return source
    val creations = parsed.unit.findAll(ObjectCreationExpr::class.java).filter { creation ->
        creation.arguments.size == 7 &&
            parsed.typing.isTypeName(creation.type.toString(), SMALL_FIREBALL)
    }
    if (creations.isEmpty()) return source
    LexicalPreservingPrinter.setup(parsed.unit)
    creations.forEach { creation ->
        val vector = ObjectCreationExpr().setType("net.minecraft.world.phys.Vec3")
        creation.arguments.drop(4).forEach { vector.addArgument(it.clone()) }
        repeat(3) { creation.arguments.removeAt(4) }
        creation.addArgument(vector)
    }
    return printWithSourceNewlines(parsed.unit, source)
}

private fun StructuralRefactorPass.migrateUnqualifiedGameTestMockPlayer(source: String): String {
    if (!source.contains("makeMockPlayer()") ||
        "(Lnet/minecraft/world/level/GameType;)Lnet/minecraft/world/entity/player/Player;" !in
        TargetMembers.methodDescriptors(GAME_TEST_HELPER, "makeMockPlayer")
    ) {
        return source
    }
    val (parser, parsed) = parseInteractionSource(source) ?: return source
    val calls = parsed.unit.findAll(MethodCallExpr::class.java).filter { call ->
        call.nameAsString == "makeMockPlayer" &&
            call.arguments.isEmpty() &&
            call.scope.isEmpty &&
            call.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .map { owner ->
                    owner.extendedTypes.any {
                        parsed.typing.isTypeName(it.toString(), GAME_TEST_HELPER)
                    } &&
                        owner.methods.none {
                            it.nameAsString == "makeMockPlayer" && it.parameters.isEmpty()
                        }
                }.orElse(false)
    }
    if (calls.isEmpty()) return source
    val creative = parser.parseExpression<Expression>(
        "net.minecraft.world.level.GameType.CREATIVE"
    ).result.orElseThrow()
    LexicalPreservingPrinter.setup(parsed.unit)
    calls.forEach { it.addArgument(creative.clone()) }
    return printWithSourceNewlines(parsed.unit, source)
}

private fun StructuralRefactorPass.migrateDirectHangingEntityDataBuilder(source: String): String {
    if (!source.contains("extends HangingEntity") ||
        "(Lnet/minecraft/network/syncher/SynchedEntityData\$Builder;)V" !in
        TargetMembers.methodDescriptors("net.minecraft.world.entity.Entity", "defineSynchedData")
    ) {
        return source
    }
    val (parser, parsed) = parseInteractionSource(source) ?: return source
    val owners = parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java).filter { owner ->
        !owner.isInterface &&
            !owner.isAbstract &&
            owner.extendedTypes.size == 1 &&
            parsed.typing.isTypeName(owner.extendedTypes[0].toString(), HANGING_ENTITY) &&
            owner.methods.none { it.nameAsString == "defineSynchedData" }
    }
    if (owners.isEmpty()) return source
    val method = parser.parseBodyDeclaration<BodyDeclaration<*>>(
        """
        @Override
        protected void defineSynchedData(
            net.minecraft.network.syncher.SynchedEntityData.Builder builder
        ) {
        }
        """.trimIndent()
    ).result.orElseThrow()
    LexicalPreservingPrinter.setup(parsed.unit)
    owners.forEach { it.addMember(method.clone()) }
    return printWithSourceNewlines(parsed.unit, source)
}

/**
 * 1.20.1 ran `EnchantmentHelper.doPostHurtEffects(victim, attacker)` then
 * `doPostDamageEffects(attacker, victim)`; 1.21.1 runs both through
 * `doPostAttackEffects(ServerLevel, victim, DamageSource)`. The damage source is the one the
 * method already handed to `hurt`: a DamageSource local is reused, otherwise the first argument
 * of the latest earlier `hurt(source, amount)` call is captured in place by an assignment, which
 * keeps its evaluation count and any short-circuit around the hurt call. Without such a source
 * the pair stays for the residual gate.
 */
private fun StructuralRefactorPass.migrateLegacyPostAttackEnchantmentEffects(source: String): String {
    if (!source.contains("doPostHurtEffects") || !source.contains("doPostDamageEffects")) return source
    if ("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;" +
        "Lnet/minecraft/world/damagesource/DamageSource;)V" !in
        TargetMembers.methodDescriptors(ENCHANTMENT_HELPER, "doPostAttackEffects")
    ) {
        return source
    }
    val (parser, parsed) = parseInteractionSource(source) ?: return source
    fun isHelperCall(call: MethodCallExpr, name: String): Boolean =
        call.nameAsString == name && call.arguments.size == 2 &&
            call.scope.map { parsed.typing.isTypeName(it.toString(), ENCHANTMENT_HELPER) }.orElse(false)
    fun bare(expression: Expression): String =
        expression.toString().replace(Regex("""^\(\s*(?:[\w.]*\.)?LivingEntity\s*\)\s*"""), "").replace(Regex("""\s+"""), "")

    data class Plan(
        val hurtStatement: ExpressionStmt,
        val damageStatement: ExpressionStmt,
        val victim: Expression,
        val sourceName: String?,
        val captured: Pair<MethodCallExpr, Statement>?
    )
    val plans = mutableListOf<Plan>()
    parsed.unit.findAll(MethodCallExpr::class.java).filter { isHelperCall(it, "doPostHurtEffects") }.forEach { hurt ->
        val hurtStatement = hurt.parentNode.orElse(null) as? ExpressionStmt ?: return@forEach
        val block = hurtStatement.parentNode.orElse(null) as? BlockStmt ?: return@forEach
        val next = block.statements.getOrNull(block.statements.indexOf(hurtStatement) + 1) as? ExpressionStmt
            ?: return@forEach
        val damage = next.expression as? MethodCallExpr ?: return@forEach
        if (!isHelperCall(damage, "doPostDamageEffects")) return@forEach
        val victim = hurt.arguments[0]
        if (bare(damage.arguments[1]) != bare(victim) || bare(damage.arguments[0]) != bare(hurt.arguments[1])) {
            return@forEach
        }
        val method = hurt.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return@forEach
        val before = parsed.offset(hurt)
        val local = method.findAll(com.github.javaparser.ast.body.VariableDeclarator::class.java).lastOrNull { variable ->
            parsed.offset(variable) in 0 until before &&
                parsed.typing.isTypeName(variable.type.toString(), DAMAGE_SOURCE)
        }
        if (local != null) {
            plans += Plan(hurtStatement, next, victim, local.nameAsString, null)
            return@forEach
        }
        val hurtCall = method.findAll(MethodCallExpr::class.java).lastOrNull { call ->
            call.nameAsString == "hurt" && call.arguments.size == 2 && parsed.offset(call) in 0 until before
        } ?: return@forEach
        val anchor = hurtCall.findAncestor(Statement::class.java).orElse(null) ?: return@forEach
        // The captured source must be in scope at the hook: its statement shares a block with it.
        val anchorBlock = anchor.parentNode.orElse(null) as? BlockStmt ?: return@forEach
        if (!hurtStatement.findAncestor(BlockStmt::class.java).map { inner ->
                generateSequence(inner as Node?) { it.parentNode.orElse(null) }.any { it === anchorBlock }
            }.orElse(false)
        ) {
            return@forEach
        }
        plans += Plan(hurtStatement, next, victim, null, hurtCall to anchor)
    }
    if (plans.isEmpty()) return source

    LexicalPreservingPrinter.setup(parsed.unit)
    val methodText = parsed.unit.toString()
    plans.forEach { plan ->
        val damageSource = plan.sourceName ?: run {
            var name = "modporterDamageSource"
            while (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(methodText)) name += "_"
            val (hurtCall, anchor) = plan.captured!!
            val block = anchor.parentNode.get() as BlockStmt
            block.statements.add(
                block.statements.indexOf(anchor),
                parser.parseStatement("$DAMAGE_SOURCE $name = null;").result.orElseThrow()
            )
            val original = hurtCall.arguments[0].clone()
            hurtCall.arguments[0].replace(EnclosedExpr(AssignExpr(NameExpr(name), original, AssignExpr.Operator.ASSIGN)))
            name
        }
        // A type test plus cast: LexicalPreservingPrinter drops the name of an inserted pattern.
        val guard = if (plan.sourceName == null) " && $damageSource != null" else ""
        val replacement = parser.parseStatement(
            "if (${plan.victim}.level() instanceof net.minecraft.server.level.ServerLevel$guard) " +
                "$ENCHANTMENT_HELPER.doPostAttackEffects(" +
                "(net.minecraft.server.level.ServerLevel) ${plan.victim}.level(), ${plan.victim}, $damageSource);"
        ).result.orElseThrow()
        plan.hurtStatement.replace(replacement)
        plan.damageStatement.remove()
    }
    return printWithSourceNewlines(parsed.unit, source)
}

/**
 * `EnchantmentHelper.getKnockbackBonus(entity)` returned the KNOCKBACK level the entity's
 * equipment carries; 1.21.1 keeps that lookup as `getEnchantmentLevel(Holder, LivingEntity)`
 * with the enchantment resolved through the entity's own registries.
 */
private fun StructuralRefactorPass.migrateLegacyKnockbackEnchantmentBonus(source: String): String {
    if (!source.contains("getKnockbackBonus")) return source
    if ("(Lnet/minecraft/core/Holder;Lnet/minecraft/world/entity/LivingEntity;)I" !in
        TargetMembers.methodDescriptors(ENCHANTMENT_HELPER, "getEnchantmentLevel")
    ) {
        return source
    }
    val (parser, parsed) = parseInteractionSource(source) ?: return source
    val calls = parsed.unit.findAll(MethodCallExpr::class.java).filter { call ->
        call.nameAsString == "getKnockbackBonus" && call.arguments.size == 1 &&
            call.scope.map { parsed.typing.isTypeName(it.toString(), ENCHANTMENT_HELPER) }.orElse(false)
    }
    if (calls.isEmpty()) return source
    LexicalPreservingPrinter.setup(parsed.unit)
    calls.forEach { call ->
        val entity = call.arguments[0].toString()
        val receiver = if (call.arguments[0] is NameExpr || call.arguments[0] is FieldAccessExpr) entity else "($entity)"
        call.replace(
            parser.parseExpression<Expression>(
                "$ENCHANTMENT_HELPER.getEnchantmentLevel($receiver.registryAccess()" +
                    ".lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)" +
                    ".getOrThrow(net.minecraft.world.item.enchantment.Enchantments.KNOCKBACK), $receiver)"
            ).result.orElseThrow()
        )
    }
    return printWithSourceNewlines(parsed.unit, source)
}

/** LexicalPreservingPrinter writes the platform separator for inserted nodes; keep the file's own. */
private fun printWithSourceNewlines(unit: CompilationUnit, source: String): String {
    val printed = LexicalPreservingPrinter.print(unit)
    return if (source.contains("\r\n")) printed else printed.replace("\r\n", "\n")
}
