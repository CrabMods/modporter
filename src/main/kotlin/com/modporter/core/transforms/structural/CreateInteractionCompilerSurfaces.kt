package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SwitchExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/**
 * Repairs source-proved Create call sites whose 1.20 overloads cannot be migrated from a method
 * name alone. Admissions prove the canonical platform type or the exact Create owner and preserve
 * the original receivers, argument expressions, evaluation count, and event source.
 */
internal fun StructuralRefactorPass.migrateCreateInteractionCompilerSurfaces(source: String): String {
    var result = migrateMountedDispenserBucketPickup(source)
    result = migrateCreateSlidingDoorUse(result)
    result = migrateCreateWhistleForwarder(result)
    result = migrateBlockStateNeighborDispatch(result)
    result = migrateLiquidBlockFluidField(result)
    result = migrateShootSmokeLevelEvent(result)
    result = migrateSmallFireballVectorConstructor(result)
    result = migrateCreativeGameTestMockPlayer(result)
    result = migrateDirectHangingEntityDataBuilder(result)
    result = migratePotatoPostAttackEffects(result)
    result = migrateCardboardSwordKnockback(result)
    return result
}

private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
private const val LIQUID_BLOCK = "net.minecraft.world.level.block.LiquidBlock"
private const val LEVEL_EVENT = "net.minecraft.world.level.block.LevelEvent"
private const val SMALL_FIREBALL = "net.minecraft.world.entity.projectile.SmallFireball"
private const val GAME_TEST_HELPER = "net.minecraft.gametest.framework.GameTestHelper"
private const val HANGING_ENTITY = "net.minecraft.world.entity.decoration.HangingEntity"
private const val ITEM_INTERACTION_RESULT = "net.minecraft.world.ItemInteractionResult"
private const val INTERACTION_RESULT = "net.minecraft.world.InteractionResult"
private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
private const val BLOCK_POS = "net.minecraft.core.BlockPos"
private const val PLAYER = "net.minecraft.world.entity.player.Player"
private const val INTERACTION_HAND = "net.minecraft.world.InteractionHand"
private const val LEVEL = "net.minecraft.world.level.Level"
private const val BLOCK_HIT_RESULT = "net.minecraft.world.phys.BlockHitResult"
private const val LIVING_DAMAGE_EVENT =
    "net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent"
private const val BUCKET_PICKUP = "net.minecraft.world.level.block.BucketPickup"

private data class CreateInteractionSource(
    val source: String,
    val unit: CompilationUnit,
    val typing: SourceValueTypes,
    val lineStarts: List<Int>
) {
    fun offset(node: Node): Int = node.range.map {
        lineStarts[it.begin.line - 1] + it.begin.column - 1
    }.orElse(-1)
}

private fun StructuralRefactorPass.parseCreateInteractionSource(
    source: String
): Pair<JavaParser, CreateInteractionSource>? {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return null
    val starts = mutableListOf(0)
    source.forEachIndexed { index, character ->
        if (character == '\n') starts += index + 1
    }
    return parser to CreateInteractionSource(source, unit, SourceValueTypes(this, source), starts)
}

private fun CreateInteractionSource.packageName(): String =
    unit.packageDeclaration.map { it.nameAsString }.orElse("")

private fun CreateInteractionSource.ownerNamed(
    method: MethodDeclaration,
    name: String
): Boolean =
    method.findAncestor(ClassOrInterfaceDeclaration::class.java)
        .map { it.nameAsString == name }
        .orElse(false)

private fun StructuralRefactorPass.migrateMountedDispenserBucketPickup(
    source: String
): String {
    if (!source.contains("bucketPickup.pickupBlock(") ||
        TargetMembers.inheritedMethod(
            BUCKET_PICKUP,
            "pickupBlock",
            "(Lnet/minecraft/world/entity/player/Player;" +
                "Lnet/minecraft/world/level/LevelAccessor;" +
                "Lnet/minecraft/core/BlockPos;" +
                "Lnet/minecraft/world/level/block/state/BlockState;)" +
                "Lnet/minecraft/world/item/ItemStack;"
        )?.let { it.owner == BUCKET_PICKUP && it.access == "public" } != true
    ) {
        return source
    }
    val (parser, parsed) = parseCreateInteractionSource(source) ?: return source
    if (parsed.packageName() != "com.simibubi.create") return source
    val owner = parsed.unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
        ?: return source
    if (owner.nameAsString != "AllMountedDispenseItemBehaviors" || owner.isInterface) {
        return source
    }

    val calls = parsed.unit.findAll(MethodCallExpr::class.java).filter { call ->
        if (call.nameAsString != "pickupBlock" ||
            call.scope.map { it.toString() == "bucketPickup" }.orElse(false).not() ||
            call.arguments.size !in 3..4
        ) {
            return@filter false
        }
        val creation = call.findAncestor(ObjectCreationExpr::class.java).orElse(null)
            ?: return@filter false
        if (creation.type.nameAsString != "DefaultMountedDispenseBehavior") {
            return@filter false
        }
        val variable = creation.findAncestor(VariableDeclarator::class.java).orElse(null)
            ?: return@filter false
        if (variable.nameAsString != "BUCKET" ||
            variable.initializer.orElse(null) !== creation ||
            simpleCreateInteractionType(variable.type.toString()) != "MountedDispenseBehavior"
        ) {
            return@filter false
        }
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return@filter false
        if (method.nameAsString != "execute" ||
            simpleCreateInteractionType(method.type.toString()) != "ItemStack" ||
            method.parameters.map { simpleCreateInteractionType(it.type.toString()) } !=
            listOf("ItemStack", "MovementContext", "BlockPos", "Vec3")
        ) {
            return@filter false
        }
        val context = method.parameters[1].nameAsString
        val expectedTail = listOf("$context.world", "interactionPos", "state")
        val actual = call.arguments.map { normalizeCreateInteractionJava(it.toString()) }
        val expected = expectedTail.map(::normalizeCreateInteractionJava)
        (actual == expected || actual == listOf("null") + expected) &&
            method.findAll(InstanceOfExpr::class.java).any { test ->
                val pattern = test.pattern.orElse(null) as? TypePatternExpr
                    ?: return@any false
                pattern.nameAsString == "bucketPickup" &&
                    simpleCreateInteractionType(pattern.type.toString()) == "BucketPickup"
            }
    }
    if (calls.size != 1 || calls.single().arguments.size == 4) return source

    LexicalPreservingPrinter.setup(parsed.unit)
    calls.single().arguments.add(
        0,
        parser.parseExpression<Expression>("null").result.orElseThrow()
    )
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun simpleCreateInteractionType(type: String): String =
    type.replace(Regex("""\s+"""), "").substringAfterLast('.')

private fun normalizeCreateInteractionJava(source: String): String =
    source.replace(Regex("""\s+"""), "")

private fun StructuralRefactorPass.migrateCreateSlidingDoorUse(source: String): String {
    if (!source.contains("useItemOn") ||
        !source.contains("ItemInteractionResult.sidedSuccess") &&
        !source.contains("net.minecraft.world.ItemInteractionResult.sidedSuccess")
    ) {
        return source
    }
    val (parser, parsed) = parseCreateInteractionSource(source) ?: return source
    if (parsed.packageName() != "com.simibubi.create.content.decoration.slidingDoor") return source
    val method = parsed.unit.findAll(MethodDeclaration::class.java).singleOrNull { candidate ->
        candidate.nameAsString == "useItemOn" &&
            candidate.parameters.size == 7 &&
            parsed.ownerNamed(candidate, "SlidingDoorBlock") &&
            parsed.typing.isTypeName(candidate.type.toString(), ITEM_INTERACTION_RESULT) &&
            parsed.typing.isTypeName(
                candidate.parameters[0].type.toString(),
                "net.minecraft.world.item.ItemStack"
            ) &&
            parsed.typing.isTypeName(
                candidate.parameters[5].type.toString(),
                "net.minecraft.world.InteractionHand"
            )
    } ?: return source
    val body = method.body.orElse(null) ?: return source
    val stack = method.parameters[0].nameAsString
    val hand = method.parameters[5].nameAsString
    if (body.findAll(NameExpr::class.java).any { it.nameAsString == stack }) return source
    val handUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == hand }
    if (handUses.size != 1) return source
    val recursive = handUses.single()
        .findAncestor(MethodCallExpr::class.java)
        .orElse(null)
        ?.takeIf { call ->
            call.nameAsString == "use" &&
                call.scope.map { it.toString() == "this" }.orElse(true) &&
                call.arguments.size == 6 &&
                call.arguments[4].toString() == hand
        } ?: return source
    val sidedSuccess = body.findAll(MethodCallExpr::class.java).singleOrNull { call ->
        call.nameAsString == "sidedSuccess" &&
            call.scope.map {
                parsed.typing.isTypeName(it.toString(), ITEM_INTERACTION_RESULT)
            }.orElse(false)
    } ?: return source

    LexicalPreservingPrinter.setup(parsed.unit)
    method.setName("useWithoutItem")
    method.setType("net.minecraft.world.InteractionResult")
    method.parameters.removeAt(5)
    method.parameters.removeAt(0)
    recursive.setName("useWithoutItem")
    recursive.arguments.removeAt(4)
    sidedSuccess.setScope(
        parser.parseExpression<Expression>("net.minecraft.world.InteractionResult")
            .result.orElseThrow()
    )
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migrateCreateWhistleForwarder(source: String): String {
    val migratedLegacy = migrateLegacyCreateWhistleForwarder(source)
    if (migratedLegacy != source) return migratedLegacy
    if (!source.contains("switch (") || !source.contains("whistle.use(")) return source
    val (parser, parsed) = parseCreateInteractionSource(source) ?: return source
    if (parsed.packageName() != "com.simibubi.create.content.decoration.steamWhistle") return source
    val method = parsed.unit.findAll(MethodDeclaration::class.java).singleOrNull { candidate ->
        candidate.nameAsString == "useItemOn" &&
            candidate.parameters.size == 7 &&
            parsed.ownerNamed(candidate, "WhistleExtenderBlock") &&
            parsed.typing.isTypeName(candidate.type.toString(), ITEM_INTERACTION_RESULT)
    } ?: return source
    val stack = method.parameters[0].nameAsString
    val returned = method.findAll(ReturnStmt::class.java).singleOrNull { statement ->
        statement.expression.orElse(null) is SwitchExpr
    } ?: return source
    val adapter = returned.expression.get() as SwitchExpr
    val forwarded = adapter.selector as? MethodCallExpr ?: return source
    val receiver = forwarded.scope.orElse(null) as? NameExpr ?: return source
    if (forwarded.nameAsString != "use" || forwarded.arguments.size != 6) return source
    val pattern = method.findAll(InstanceOfExpr::class.java).singleOrNull { test ->
        val typePattern = test.pattern.orElse(null) as? TypePatternExpr ?: return@singleOrNull false
        typePattern.nameAsString == receiver.nameAsString &&
            typePattern.type.toString().substringAfterLast('.') == "WhistleBlock"
    } ?: return source
    if (pattern.findAncestor(IfStmt::class.java).isEmpty) return source
    val normalizedAdapter = adapter.toString().replace(Regex("""\s+"""), " ")
    val expectedMappings = listOf(
        "case SUCCESS -> net.minecraft.world.ItemInteractionResult.SUCCESS;",
        "case SUCCESS_NO_ITEM_USED -> net.minecraft.world.ItemInteractionResult.SUCCESS;",
        "case CONSUME -> net.minecraft.world.ItemInteractionResult.CONSUME;",
        "case CONSUME_PARTIAL -> net.minecraft.world.ItemInteractionResult.CONSUME_PARTIAL;",
        "case FAIL -> net.minecraft.world.ItemInteractionResult.FAIL;",
        "default -> net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;"
    )
    if (adapter.entries.size != expectedMappings.size ||
        expectedMappings.any { it !in normalizedAdapter }
    ) {
        return source
    }
    val heldItemRead = method.findAll(MethodCallExpr::class.java).singleOrNull { call ->
        call.nameAsString == "getItemInHand" &&
            call.arguments.size == 1 &&
            call.arguments[0].toString() == method.parameters[5].nameAsString
    }

    LexicalPreservingPrinter.setup(parsed.unit)
    val migratedCall = parser.parseExpression<Expression>(
        "${receiver}.useItemOn($stack, ${forwarded.arguments.joinToString(", ")})"
    ).result.orElseThrow()
    returned.setExpression(migratedCall)
    heldItemRead?.replace(NameExpr(stack))
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migrateLegacyCreateWhistleForwarder(
    source: String
): String {
    if (!source.contains("whistle.use(") ||
        !source.contains("AllBlocks.STEAM_WHISTLE.isIn(")
    ) {
        return source
    }
    val (parser, parsed) = parseCreateInteractionSource(source) ?: return source
    if (parsed.packageName() != "com.simibubi.create.content.decoration.steamWhistle") {
        return source
    }
    val expectedParameters = listOf(
        BLOCK_STATE,
        LEVEL,
        BLOCK_POS,
        PLAYER,
        INTERACTION_HAND,
        BLOCK_HIT_RESULT
    )
    val method = parsed.unit.findAll(MethodDeclaration::class.java).singleOrNull { candidate ->
        candidate.nameAsString == "use" &&
            candidate.isPublic &&
            candidate.parameters.size == expectedParameters.size &&
            parsed.ownerNamed(candidate, "WhistleExtenderBlock") &&
            parsed.typing.isTypeName(candidate.type.toString(), INTERACTION_RESULT) &&
            candidate.parameters.zip(expectedParameters).all { (parameter, expected) ->
                parsed.typing.isTypeName(parameter.type.toString(), expected)
            }
    } ?: return source
    val level = method.parameters[1].nameAsString
    val player = method.parameters[3].nameAsString
    val hand = method.parameters[4].nameAsString
    val hit = method.parameters[5].nameAsString
    val body = method.body.orElse(null) ?: return source
    if (body.findAll(NameExpr::class.java).any { it.nameAsString == "stack" }
    ) {
        return source
    }

    val heldItemRead = method.findAll(MethodCallExpr::class.java).singleOrNull { call ->
        call.nameAsString == "getItemInHand" &&
            call.scope.map { it.toString() == player }.orElse(false) &&
            call.arguments.map { it.toString() } == listOf(hand)
    } ?: return source
    val steamWhistleCheck = heldItemRead.findAncestor(MethodCallExpr::class.java)
        .orElse(null)
        ?.takeIf { call ->
            call.nameAsString == "isIn" &&
                call.scope.map {
                    normalizeCreateInteractionJava(it.toString()) ==
                        "AllBlocks.STEAM_WHISTLE"
                }.orElse(false) &&
                call.arguments.singleOrNull() === heldItemRead
        } ?: return source
    val guard = steamWhistleCheck.findAncestor(IfStmt::class.java).orElse(null)
        ?: return source
    if (normalizeCreateInteractionJava(guard.condition.toString()) !=
        "$player==null||!AllBlocks.STEAM_WHISTLE.isIn(" +
        "$player.getItemInHand($hand))"
    ) {
        return source
    }

    val pattern = method.findAll(InstanceOfExpr::class.java).singleOrNull { test ->
        val typePattern = test.pattern.orElse(null) as? TypePatternExpr
            ?: return@singleOrNull false
        simpleCreateInteractionType(typePattern.type.toString()) == "WhistleBlock"
    } ?: return source
    val typePattern = pattern.pattern.orElseThrow() as TypePatternExpr
    val receiver = typePattern.nameAsString
    val stateRead = pattern.expression as? MethodCallExpr ?: return source
    if (stateRead.nameAsString != "getBlock" || stateRead.arguments.isNotEmpty()) {
        return source
    }
    val forwardedState = stateRead.scope.orElse(null) as? NameExpr ?: return source
    val patternGuard = pattern.findAncestor(IfStmt::class.java).orElse(null) ?: return source
    val forwarded = patternGuard.thenStmt.findAll(MethodCallExpr::class.java)
        .singleOrNull { call ->
            call.nameAsString == "use" &&
                call.scope.map { it.toString() == receiver }.orElse(false) &&
                call.arguments.size == 6
        } ?: return source
    val root = (forwarded.arguments[2] as? NameExpr)?.nameAsString ?: return source
    if (forwarded.arguments.take(5).map { normalizeCreateInteractionJava(it.toString()) } !=
        listOf(forwardedState.nameAsString, level, root, player, hand)
    ) {
        return source
    }
    val hitCopy = forwarded.arguments[5] as? ObjectCreationExpr ?: return source
    if (simpleCreateInteractionType(hitCopy.type.toString()) != "BlockHitResult" ||
        normalizeCreateInteractionJava(hitCopy.toString()) !=
        "newBlockHitResult($hit.getLocation(),$hit.getDirection(),$root,$hit.isInside())"
    ) {
        return source
    }
    val forwardedReturn = forwarded.findAncestor(ReturnStmt::class.java).orElse(null)
        ?: return source
    if (forwardedReturn.expression.orElse(null) !== forwarded ||
        forwardedReturn.findAncestor(IfStmt::class.java).orElse(null) !== patternGuard
    ) {
        return source
    }
    val passReturns = method.findAll(ReturnStmt::class.java).filter { statement ->
        normalizeCreateInteractionJava(
            statement.expression.map { it.toString() }.orElse("")
        ) in setOf("InteractionResult.PASS", "net.minecraft.world.InteractionResult.PASS")
    }
    if (passReturns.size != 2) return source

    LexicalPreservingPrinter.setup(parsed.unit)
    method.setName("useItemOn")
    method.setType("net.minecraft.world.ItemInteractionResult")
    method.setPublic(false)
    method.setProtected(true)
    method.parameters.add(
        0,
        parser.parseParameter("net.minecraft.world.item.ItemStack stack")
            .result.orElseThrow()
    )
    heldItemRead.replace(NameExpr("stack"))
    forwarded.setName("useItemOn")
    forwarded.arguments.add(0, NameExpr("stack"))
    passReturns.forEach { statement ->
        statement.setExpression(
            parser.parseExpression<Expression>(
                "net.minecraft.world.ItemInteractionResult." +
                    "PASS_TO_DEFAULT_BLOCK_INTERACTION"
            ).result.orElseThrow()
        )
    }
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migrateBlockStateNeighborDispatch(source: String): String {
    if (!source.contains(".neighborChanged(")) return source
    val descriptor = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V"
    if (TargetMembers.inheritedMethod(BLOCK_STATE, "handleNeighborChanged", descriptor) == null) {
        return source
    }
    val (_, parsed) = parseCreateInteractionSource(source) ?: return source
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
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migrateLiquidBlockFluidField(source: String): String {
    if (!source.contains(".getFluid()") ||
        TargetMembers.fieldDescriptor(LIQUID_BLOCK, "fluid") !=
        "Lnet/minecraft/world/level/material/FlowingFluid;"
    ) {
        return source
    }
    val (_, parsed) = parseCreateInteractionSource(source) ?: return source
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
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migrateShootSmokeLevelEvent(source: String): String {
    if (!source.contains("LevelEvent.PARTICLES_SHOOT") ||
        TargetMembers.fieldDescriptor(LEVEL_EVENT, "PARTICLES_SHOOT_SMOKE") != "I"
    ) {
        return source
    }
    val (_, parsed) = parseCreateInteractionSource(source) ?: return source
    val fields = parsed.unit.findAll(FieldAccessExpr::class.java).filter { field ->
        field.nameAsString == "PARTICLES_SHOOT" &&
            parsed.typing.isTypeName(field.scope.toString(), LEVEL_EVENT)
    }
    if (fields.isEmpty()) return source
    LexicalPreservingPrinter.setup(parsed.unit)
    fields.forEach { it.setName("PARTICLES_SHOOT_SMOKE") }
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migrateSmallFireballVectorConstructor(source: String): String {
    if (!source.contains("new SmallFireball") ||
        "(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/phys/Vec3;)V" !in
        TargetMembers.methodDescriptors(SMALL_FIREBALL, "<init>")
    ) {
        return source
    }
    val (_, parsed) = parseCreateInteractionSource(source) ?: return source
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
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migrateCreativeGameTestMockPlayer(source: String): String {
    if (!source.contains("makeMockPlayer()") ||
        "(Lnet/minecraft/world/level/GameType;)Lnet/minecraft/world/entity/player/Player;" !in
        TargetMembers.methodDescriptors(GAME_TEST_HELPER, "makeMockPlayer")
    ) {
        return source
    }
    val (parser, parsed) = parseCreateInteractionSource(source) ?: return source
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
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migrateDirectHangingEntityDataBuilder(source: String): String {
    if (!source.contains("extends HangingEntity") ||
        "(Lnet/minecraft/network/syncher/SynchedEntityData\$Builder;)V" !in
        TargetMembers.methodDescriptors("net.minecraft.world.entity.Entity", "defineSynchedData")
    ) {
        return source
    }
    val (parser, parsed) = parseCreateInteractionSource(source) ?: return source
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
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migratePotatoPostAttackEffects(source: String): String {
    if (!source.contains("causePotatoDamage()") ||
        !source.contains("doPostHurtEffects") ||
        !source.contains("doPostDamageEffects") ||
        parsedPackage(source) != "com.simibubi.create.content.equipment.potatoCannon" ||
        "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;" +
        "Lnet/minecraft/world/damagesource/DamageSource;)V" !in
        TargetMembers.methodDescriptors(
            "net.minecraft.world.item.enchantment.EnchantmentHelper",
            "doPostAttackEffects"
        )
    ) {
        return source
    }
    val (parser, parsed) = parseCreateInteractionSource(source) ?: return source
    val hurt = parsed.unit.findAll(MethodCallExpr::class.java).singleOrNull { call ->
        call.nameAsString == "hurt" &&
            call.arguments.size == 2 &&
            (call.arguments[0] as? MethodCallExpr)?.let {
                it.nameAsString == "causePotatoDamage" &&
                    it.arguments.isEmpty() &&
                    it.scope.map { scope -> scope.toString() == "this" }.orElse(true)
            } == true
    } ?: return source
    val method = hurt.findAncestor(MethodDeclaration::class.java).orElse(null)
        ?.takeIf { parsed.ownerNamed(it, "PotatoProjectileEntity") }
        ?: return source
    val hurtIf = hurt.findAncestor(IfStmt::class.java).orElse(null) ?: return source
    val parent = hurtIf.parentNode.orElse(null) as? BlockStmt ?: return source
    val insertion = parent.statements.indexOf(hurtIf)
    if (insertion < 0) return source
    val onServer = method.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
        variable.type.toString() == "boolean" &&
            variable.initializer.map {
                it.toString().replace(" ", "") == "!level().isClientSide"
            }.orElse(false)
    }?.nameAsString ?: return source
    if (!hurtIf.condition.toString().contains(onServer)) return source
    val postHurt = method.findAll(MethodCallExpr::class.java).singleOrNull {
        it.nameAsString == "doPostHurtEffects" && it.arguments.size == 2
    } ?: return source
    val postDamage = method.findAll(MethodCallExpr::class.java).singleOrNull {
        it.nameAsString == "doPostDamageEffects" && it.arguments.size == 2
    } ?: return source
    val postHurtStatement = postHurt.parentNode.orElse(null) as? ExpressionStmt ?: return source
    val postDamageStatement = postDamage.parentNode.orElse(null) as? ExpressionStmt ?: return source
    if (postHurtStatement.parentNode.orElse(null) !== postDamageStatement.parentNode.orElse(null)) {
        return source
    }
    val victim = postHurt.arguments[0].toString()
    val damageSource = uniqueCreateInteractionName(method, "modporterDamageSource")
    val declaration = parser.parseStatement(
        "net.minecraft.world.damagesource.DamageSource $damageSource = " +
            "$onServer ? causePotatoDamage() : null;"
    ).result.orElseThrow()
    val migratedPost = parser.parseStatement(
        "net.minecraft.world.item.enchantment.EnchantmentHelper.doPostAttackEffects(" +
            "(net.minecraft.server.level.ServerLevel) level(), $victim, $damageSource);"
    ).result.orElseThrow()

    LexicalPreservingPrinter.setup(parsed.unit)
    parent.addStatement(insertion, declaration)
    hurt.arguments[0].replace(NameExpr(damageSource))
    postHurtStatement.replace(migratedPost)
    postDamageStatement.remove()
    return LexicalPreservingPrinter.print(parsed.unit)
}

private fun StructuralRefactorPass.migrateCardboardSwordKnockback(source: String): String {
    if (!source.contains("getKnockbackBonus") ||
        parsedPackage(source) != "com.simibubi.create.content.equipment.tool" ||
        "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;" +
        "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;F)F" !in
        TargetMembers.methodDescriptors(
            "net.minecraft.world.item.enchantment.EnchantmentHelper",
            "modifyKnockback"
        )
    ) {
        return source
    }
    val (parser, parsed) = parseCreateInteractionSource(source) ?: return source
    val legacy = parsed.unit.findAll(MethodCallExpr::class.java).singleOrNull { call ->
        call.nameAsString == "getKnockbackBonus" && call.arguments.size == 1
    } ?: return source
    val method = legacy.findAncestor(MethodDeclaration::class.java).orElse(null)
        ?.takeIf {
            parsed.ownerNamed(it, "CardboardSwordItem") &&
                it.parameters.size == 1 &&
                parsed.typing.isTypeName(it.parameters[0].type.toString(), LIVING_DAMAGE_EVENT)
        } ?: return source
    val event = method.parameters[0].nameAsString
    val attacker = legacy.arguments[0] as? NameExpr ?: return source
    val assignment = legacy.findAncestor(AssignExpr::class.java).orElse(null)
        ?.takeIf { it.operator == AssignExpr.Operator.PLUS && it.value === legacy }
        ?: return source
    val statement = assignment.parentNode.orElse(null) as? ExpressionStmt ?: return source
    val strength = assignment.target as? NameExpr ?: return source
    val target = method.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
        parsed.typing.isTypeName(
            variable.type.toString(),
            "net.minecraft.world.entity.LivingEntity"
        ) &&
            variable.initializer.map {
                it.toString().replace(Regex("""\s+"""), "") == "$event.getEntity()"
            }.orElse(false)
    }?.nameAsString ?: return source
    if (method.findAll(MethodCallExpr::class.java).none {
            it.nameAsString == "setCanceled" &&
                it.scope.map { scope -> scope.toString() == event }.orElse(false)
        }
    ) {
        return source
    }
    val server = uniqueCreateInteractionName(method, "modporterServerLevel")
    val replacement = parser.parseStatement(
        """
        if (${attacker}.level() instanceof net.minecraft.server.level.ServerLevel $server)
            ${strength} = net.minecraft.world.item.enchantment.EnchantmentHelper.modifyKnockback(
                $server,
                ${attacker}.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
                $target,
                $event.getSource(),
                (float) ${strength});
        """.trimIndent()
    ).result.orElseThrow()

    LexicalPreservingPrinter.setup(parsed.unit)
    statement.replace(replacement)
    val migrated = LexicalPreservingPrinter.print(parsed.unit)
    val missingPatternVariable = Regex(
        """(\b${Regex.escape(attacker.nameAsString)}\.level\(\)\s+instanceof\s+""" +
            """net\.minecraft\.server\.level\.ServerLevel)(\s*\))"""
    )
    val missing = missingPatternVariable.find(migrated) ?: return migrated
    val condition = "${missing.groupValues[1]} $server${missing.groupValues[2]}"
    return migrated.replaceRange(missing.range, condition)
}

private fun parsedPackage(source: String): String =
    Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
        .find(source)
        ?.groupValues
        ?.get(1)
        .orEmpty()

private fun uniqueCreateInteractionName(node: Node, base: String): String {
    var candidate = base
    val text = node.toString()
    while (Regex("""\b${Regex.escape(candidate)}\b""").containsMatchIn(text)) {
        candidate += "_"
    }
    return candidate
}
