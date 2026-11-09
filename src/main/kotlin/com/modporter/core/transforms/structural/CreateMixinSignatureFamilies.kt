package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Draft only. Place immediately before migrateLegacyComponentSerializationSource in the
 * per-source pipeline. Widening StationMarker first lets the existing component migration use
 * the native HolderLookup.Provider instead of its built-in-registry compatibility fallback.
 */
internal fun StructuralRefactorPass.migrateCreateMixinSignatureFamilies(source: String): String {
    var result = source
    result = migrateCreateBeehiveBlockMixin(result)
    result = migrateCreateSmithingMenuMixin(result)
    result = migrateCreateMapItemSavedDataMixin(result)
    result = migrateCreateCameraMixin(result)
    result = migrateCreateHumanoidArmorLayerMixin(result)
    result = migrateCreateXaeroMixinPluginGuard(result)
    result = migrateCreateDispenserBlockAccessor(result)
    result = migrateCreateDispenserBehaviorConverter(result)
    result = migrateCreateStationMarkerRegistryCarrier(result)
    result = migrateCreateTestCommandMixin(result)
    return result
}

private const val MIXIN = "org.spongepowered.asm.mixin.Mixin"
private const val INVOKER = "org.spongepowered.asm.mixin.gen.Invoker"
private const val AT = "org.spongepowered.asm.mixin.injection.At"
private const val INJECT = "org.spongepowered.asm.mixin.injection.Inject"
private const val MODIFY_ARG = "org.spongepowered.asm.mixin.injection.ModifyArg"
private const val MODIFY_EXPRESSION =
    "com.llamalad7.mixinextras.injector.ModifyExpressionValue"
private const val LOCAL = "com.llamalad7.mixinextras.sugar.Local"
private const val CALLBACK_INFO_RETURNABLE =
    "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable"
private const val CALLBACK_INFO =
    "org.spongepowered.asm.mixin.injection.callback.CallbackInfo"
private const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
private const val HOLDER_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
private const val LEVEL = "net.minecraft.world.level.Level"
private const val COMPONENT = "net.minecraft.network.chat.Component"
private const val PLAYER = "net.minecraft.world.entity.player.Player"
private const val NULLABLE = "org.jetbrains.annotations.Nullable"
private const val DISPENSE_ITEM_BEHAVIOR =
    "net.minecraft.core.dispenser.DispenseItemBehavior"
private const val DISPENSER_BLOCK = "net.minecraft.world.level.block.DispenserBlock"
private const val BLOCKS = "net.minecraft.world.level.block.Blocks"
private const val DISPENSER_BLOCK_ACCESSOR =
    "com.simibubi.create.foundation.mixin.accessor.DispenserBlockAccessor"
private const val DISPENSER_BEHAVIOR_CONVERTER =
    "com.simibubi.create.impl.contraption.dispenser.DispenserBehaviorConverter"
private const val MAP_DATA =
    "net.minecraft.world.level.saveddata.maps.MapItemSavedData"
private const val STATION_MARKER =
    "com.simibubi.create.content.trains.station.StationMarker"
private const val CAMERA_DISTANCE_MODIFIER =
    "com.simibubi.create.content.trains.CameraDistanceModifier"
private const val HUMANOID_ARMOR_LAYER =
    "net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer"
private const val POSE_STACK = "com.mojang.blaze3d.vertex.PoseStack"
private const val MULTI_BUFFER_SOURCE =
    "net.minecraft.client.renderer.MultiBufferSource"
private const val LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity"
private const val EQUIPMENT_SLOT = "net.minecraft.world.entity.EquipmentSlot"
private const val HUMANOID_MODEL = "net.minecraft.client.model.HumanoidModel"
private const val CREATE_MIXIN_PLUGIN =
    "com.simibubi.create.foundation.mixin.CreateMixinPlugin"
private const val MIXIN_CONFIG_PLUGIN =
    "org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin"
private const val MODS = "com.simibubi.create.compat.Mods"
private const val XAERO_MIXIN_PREFIX =
    "com.simibubi.create.foundation.mixin.compat.xaeros"
private const val JOURNEYMAP_MIXIN_PREFIX =
    "com.simibubi.create.foundation.mixin.compat.journeymap"

private data class CreateParsedSource(
    val pass: StructuralRefactorPass,
    val source: String,
    val unit: com.github.javaparser.ast.CompilationUnit,
    val typing: SourceValueTypes,
    val lineStarts: List<Int>
) {
    fun offset(node: Node): Int = node.range.map {
        lineStarts[it.begin.line - 1] + it.begin.column - 1
    }.orElse(-1)

    fun range(node: Node): IntRange? = node.range.map {
        val start = lineStarts[it.begin.line - 1] + it.begin.column - 1
        val end = lineStarts[it.end.line - 1] + it.end.column - 1
        start..end
    }.orElse(null)

    fun insertionBeforeCallClosingParen(node: MethodCallExpr): Int? {
        val nodeRange = range(node) ?: return null
        return nodeRange.last.takeIf { source.getOrNull(it) == ')' }
    }

    fun methodParameterClosingParen(method: MethodDeclaration): Int? {
        val methodRange = range(method) ?: return null
        val nameRange = range(method.name) ?: return null
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val open = masked.indexOf('(', nameRange.last + 1)
        if (open < 0 || open !in methodRange) return null
        val close = JavaSourceText.matchingParen(masked, open)
        return close.takeIf { it in methodRange }
    }
}

private data class CreateEdit(
    val start: Int,
    val endExclusive: Int,
    val replacement: String
)

private fun StructuralRefactorPass.parseCreateSource(source: String): CreateParsedSource? {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return null
    val starts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    return CreateParsedSource(this, source, unit, SourceValueTypes(this, source), starts)
}

private fun applyCreateEdits(source: String, edits: List<CreateEdit>): String? {
    if (edits.isEmpty()) return source
    val ordered = edits.sortedWith(compareBy<CreateEdit> { it.start }.thenBy { it.endExclusive })
    if (ordered.any { it.start < 0 || it.endExclusive < it.start || it.endExclusive > source.length } ||
        ordered.zipWithNext().any { (left, right) -> left.endExclusive > right.start }
    ) {
        return null
    }
    var result = source
    for (edit in ordered.asReversed()) {
        result = result.substring(0, edit.start) + edit.replacement +
            result.substring(edit.endExclusive)
    }
    return result
}

private fun CreateParsedSource.replace(node: Node, replacement: String): CreateEdit? =
    range(node)?.let { CreateEdit(it.first, it.last + 1, replacement) }

private fun CreateParsedSource.insert(offset: Int, text: String): CreateEdit =
    CreateEdit(offset, offset, text)

private fun SourceValueTypes.isClassType(
    type: com.github.javaparser.ast.type.Type,
    canonical: String
): Boolean {
    val classType = type as? ClassOrInterfaceType ?: return false
    return isTypeName(classType.nameWithScope, canonical)
}

private fun SourceValueTypes.isGenericClassType(
    type: com.github.javaparser.ast.type.Type,
    raw: String,
    argument: String
): Boolean {
    val classType = type as? ClassOrInterfaceType ?: return false
    val args = classType.typeArguments.orElse(null)?.toList() ?: return false
    return isTypeName(classType.nameWithScope, raw) &&
        args.size == 1 &&
        isClassType(args.single(), argument)
}

private fun SourceValueTypes.isUnboundedWildcardClassType(
    type: com.github.javaparser.ast.type.Type,
    raw: String
): Boolean {
    val classType = type as? ClassOrInterfaceType ?: return false
    val argument = classType.typeArguments.orElse(null)?.singleOrNull()
        ?.takeIf { it.isWildcardType }
        ?.asWildcardType()
        ?: return false
    return isTypeName(classType.nameWithScope, raw) &&
        argument.extendedType.isEmpty &&
        argument.superType.isEmpty
}

private fun CreateParsedSource.annotation(
    annotations: List<AnnotationExpr>,
    canonical: String
): AnnotationExpr? = annotations.singleOrNull {
    typing.isTypeName(it.nameAsString, canonical)
}

private fun AnnotationExpr.member(name: String): Expression? = when {
    isSingleMemberAnnotationExpr && name == "value" ->
        asSingleMemberAnnotationExpr().memberValue
    isNormalAnnotationExpr ->
        asNormalAnnotationExpr().pairs.singleOrNull { it.nameAsString == name }?.value
    else -> null
}

private fun AnnotationExpr.stringMember(name: String): String? =
    (member(name) as? StringLiteralExpr)?.value

private fun AnnotationExpr.intMember(name: String): Int? =
    (member(name) as? IntegerLiteralExpr)?.asInt()

private fun AnnotationExpr.booleanMember(name: String): Boolean? =
    (member(name) as? BooleanLiteralExpr)?.value

private fun CreateParsedSource.mixinOwner(canonicalTarget: String): ClassOrInterfaceDeclaration? =
    unit.findAll(ClassOrInterfaceDeclaration::class.java).filter { type ->
        val mixin = annotation(type.annotations, MIXIN) ?: return@filter false
        val target = mixin.member("value") as? ClassExpr ?: return@filter false
        typing.isTypeName(target.type.asString(), canonicalTarget)
    }.singleOrNull()

private fun CreateParsedSource.methodWithAnnotation(
    owner: ClassOrInterfaceDeclaration,
    canonicalAnnotation: String
): Pair<MethodDeclaration, AnnotationExpr>? =
    owner.methods.mapNotNull { method ->
        annotation(method.annotations, canonicalAnnotation)?.let { method to it }
    }.singleOrNull()

private fun CreateParsedSource.atAnnotation(
    injection: AnnotationExpr,
    value: String,
    target: String? = null
): AnnotationExpr? {
    val at = injection.member("at") as? AnnotationExpr ?: return null
    if (!typing.isTypeName(at.nameAsString, AT) ||
        at.stringMember("value") != value ||
        (target != null && at.stringMember("target") != target)
    ) {
        return null
    }
    return at
}

private fun CreateParsedSource.callback(
    method: MethodDeclaration,
    argument: String
): com.github.javaparser.ast.body.Parameter? =
    method.parameters.singleOrNull {
        typing.isGenericClassType(it.type, CALLBACK_INFO_RETURNABLE, argument)
    }

private fun CreateParsedSource.isComponentSerializerScope(expression: Expression?): Boolean {
    val scope = expression?.toString() ?: return false
    if (!scope.endsWith(".Serializer")) return false
    return typing.isTypeName(scope.removeSuffix(".Serializer"), COMPONENT)
}

private fun StructuralRefactorPass.migrateCreateBeehiveBlockMixin(source: String): String {
    val parsed = parseCreateSource(source) ?: return source
    val owner = parsed.mixinOwner("net.minecraft.world.level.block.BeehiveBlock")
        ?: return source
    val (method, injection) = parsed.methodWithAnnotation(owner, MODIFY_EXPRESSION)
        ?: return source
    val smokeTarget =
        "Lnet/minecraft/world/level/block/CampfireBlock;" +
            "isSmokeyPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
    val nativeUse =
        "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/level/block/state/BlockState;" +
            "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/world/entity/player/Player;" +
            "Lnet/minecraft/world/InteractionHand;" +
            "Lnet/minecraft/world/phys/BlockHitResult;)" +
            "Lnet/minecraft/world/ItemInteractionResult;"
    if (nativeUse !in TargetMembers.methodDescriptors(
            "net.minecraft.world.level.block.BeehiveBlock",
            "useItemOn"
        ) ||
        "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z" !in
        TargetMembers.methodDescriptors(
            "net.minecraft.world.level.block.CampfireBlock",
            "isSmokeyPos"
        )
    ) {
        return source
    }
    if (injection.stringMember("method") != "use" ||
        parsed.atAnnotation(injection, "INVOKE", smokeTarget) == null ||
        !method.isPrivate ||
        !method.isStatic ||
        !method.type.isPrimitiveType ||
        method.type.asPrimitiveType().type != PrimitiveType.Primitive.BOOLEAN ||
        method.parameters.size != 2 ||
        !method.parameters[0].type.isPrimitiveType ||
        method.parameters[0].type.asPrimitiveType().type != PrimitiveType.Primitive.BOOLEAN ||
        !parsed.typing.isClassType(method.parameters[1].type, PLAYER)
    ) {
        return source
    }
    val local = parsed.annotation(method.parameters[1].annotations, LOCAL) ?: return source
    if (local.booleanMember("argsOnly") != true) return source
    val literal = injection.member("method") as? StringLiteralExpr ?: return source
    val edit = parsed.replace(literal, "\"useItemOn\"") ?: return source
    return applyCreateEdits(source, listOf(edit)) ?: source
}

private fun StructuralRefactorPass.migrateCreateSmithingMenuMixin(source: String): String {
    val parsed = parseCreateSource(source) ?: return source
    val owner = parsed.mixinOwner("net.minecraft.world.inventory.SmithingMenu")
        ?: return source
    val (method, injection) = parsed.methodWithAnnotation(owner, MODIFY_EXPRESSION)
        ?: return source
    val oldTarget =
        "Lnet/minecraft/world/item/crafting/SmithingRecipe;" +
            "assemble(Lnet/minecraft/world/Container;Lnet/minecraft/core/RegistryAccess;)" +
            "Lnet/minecraft/world/item/ItemStack;"
    val newTarget =
        "Lnet/minecraft/world/item/crafting/SmithingRecipe;" +
            "assemble(Lnet/minecraft/world/item/crafting/RecipeInput;" +
            "Lnet/minecraft/core/HolderLookup\$Provider;)" +
            "Lnet/minecraft/world/item/ItemStack;"
    val nativeAssemble =
        "(Lnet/minecraft/world/item/crafting/RecipeInput;" +
            "Lnet/minecraft/core/HolderLookup\$Provider;)" +
            "Lnet/minecraft/world/item/ItemStack;"
    if (TargetMembers.inheritedMethod(
            "net.minecraft.world.item.crafting.SmithingRecipe",
            "assemble",
            nativeAssemble
        )?.owner != "net.minecraft.world.item.crafting.Recipe"
    ) {
        return source
    }
    val at = parsed.atAnnotation(injection, "INVOKE", oldTarget) ?: return source
    if (injection.stringMember("method") != "createResult" ||
        !method.isPrivate ||
        method.isStatic ||
        !parsed.typing.isClassType(method.type, ITEM_STACK) ||
        method.parameters.size != 1 ||
        !parsed.typing.isClassType(method.parameters.single().type, ITEM_STACK)
    ) {
        return source
    }
    val literal = at.member("target") as? StringLiteralExpr ?: return source
    val edit = parsed.replace(literal, "\"$newTarget\"") ?: return source
    return applyCreateEdits(source, listOf(edit)) ?: source
}

private fun StructuralRefactorPass.migrateCreateCameraMixin(source: String): String {
    val parsed = parseCreateSource(source) ?: return source
    val owner = parsed.mixinOwner("net.minecraft.client.Camera") ?: return source
    val (method, injection) = parsed.methodWithAnnotation(owner, MODIFY_ARG)
        ?: return source
    val setup =
        "setup(Lnet/minecraft/world/level/BlockGetter;" +
            "Lnet/minecraft/world/entity/Entity;ZZF)V"
    val oldTarget = "Lnet/minecraft/client/Camera;getMaxZoom(D)D"
    val newTarget = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"
    val nativeSetup =
        "(Lnet/minecraft/world/level/BlockGetter;" +
            "Lnet/minecraft/world/entity/Entity;ZZF)V"
    if ("(F)F" !in TargetMembers.methodDescriptors(
            "net.minecraft.client.Camera",
            "getMaxZoom"
        ) ||
        nativeSetup !in TargetMembers.methodDescriptors(
            "net.minecraft.client.Camera",
            "setup"
        )
    ) {
        return source
    }
    val at = parsed.atAnnotation(injection, "INVOKE", oldTarget) ?: return source
    if (injection.stringMember("method") != setup ||
        injection.intMember("index") != 0 ||
        !method.isPrivate ||
        method.isStatic ||
        !method.type.isPrimitiveType ||
        method.type.asPrimitiveType().type != PrimitiveType.Primitive.DOUBLE ||
        method.parameters.size != 1 ||
        !method.parameters.single().type.isPrimitiveType ||
        method.parameters.single().type.asPrimitiveType().type != PrimitiveType.Primitive.DOUBLE
    ) {
        return source
    }

    // The body becomes float-safe only when the exact zero-argument project method is source-proven
    // to return float. Create has another one-argument overload, so arity is part of the proof.
    val multiplierMethods =
        projectValueFacts.methods[CAMERA_DISTANCE_MODIFIER]?.get("getMultiplier/0").orEmpty()
    if (multiplierMethods.size != 1 || multiplierMethods.single().returnType != "float") {
        return source
    }
    val returned = method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt
        ?: return source
    val multiplication = returned.expression.orElse(null) as? BinaryExpr ?: return source
    if (multiplication.operator != BinaryExpr.Operator.MULTIPLY) return source
    val parameterName = method.parameters.single().nameAsString
    val sides = listOf(multiplication.left, multiplication.right)
    if (sides.count { (it as? NameExpr)?.nameAsString == parameterName } != 1) return source
    val multiplier = sides.singleOrNull { it is MethodCallExpr } as? MethodCallExpr
        ?: return source
    val multiplierOwner = multiplier.scope.orElse(null)?.toString() ?: return source
    if (multiplier.nameAsString != "getMultiplier" ||
        multiplier.arguments.isNotEmpty() ||
        !parsed.typing.isTypeName(multiplierOwner, CAMERA_DISTANCE_MODIFIER)
    ) {
        return source
    }

    val targetLiteral = at.member("target") as? StringLiteralExpr ?: return source
    val edits = listOfNotNull(
        parsed.replace(targetLiteral, "\"$newTarget\""),
        parsed.replace(method.type, "float"),
        parsed.replace(method.parameters.single().type, "float")
    )
    if (edits.size != 3) return source
    return applyCreateEdits(source, edits) ?: source
}

private const val ARMOR_RENDER_LEGACY_DESCRIPTOR =
    "(Lcom/mojang/blaze3d/vertex/PoseStack;" +
        "Lnet/minecraft/client/renderer/MultiBufferSource;" +
        "Lnet/minecraft/world/entity/LivingEntity;" +
        "Lnet/minecraft/world/entity/EquipmentSlot;" +
        "ILnet/minecraft/client/model/HumanoidModel;)V"
private const val ARMOR_RENDER_NATIVE_DESCRIPTOR =
    "(Lcom/mojang/blaze3d/vertex/PoseStack;" +
        "Lnet/minecraft/client/renderer/MultiBufferSource;" +
        "Lnet/minecraft/world/entity/LivingEntity;" +
        "Lnet/minecraft/world/entity/EquipmentSlot;" +
        "ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V"
private const val ITEM_STACK_GET_ITEM_TARGET =
    "Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;"

private fun CreateParsedSource.isCreateArmorHandler(method: MethodDeclaration): Boolean {
    if (method.nameAsString != "create\$onRenderArmorPiece" ||
        !method.isPrivate ||
        method.isStatic ||
        !method.type.isVoidType ||
        method.typeParameters.isNotEmpty() ||
        method.parameters.size != 8 ||
        method.parameters.any { it.isVarArgs }
    ) {
        return false
    }
    val parameters = method.parameters
    val light = parameters[4].type
    if (!typing.isClassType(parameters[0].type, POSE_STACK) ||
        !typing.isClassType(parameters[1].type, MULTI_BUFFER_SOURCE) ||
        !typing.isClassType(parameters[2].type, LIVING_ENTITY) ||
        !typing.isClassType(parameters[3].type, EQUIPMENT_SLOT) ||
        !light.isPrimitiveType ||
        light.asPrimitiveType().type != PrimitiveType.Primitive.INT ||
        !typing.isUnboundedWildcardClassType(parameters[5].type, HUMANOID_MODEL) ||
        !typing.isClassType(parameters[6].type, CALLBACK_INFO) ||
        !typing.isClassType(parameters[7].type, ITEM_STACK)
    ) {
        return false
    }
    return annotation(parameters[7].annotations, LOCAL) != null
}

private fun StructuralRefactorPass.migrateCreateHumanoidArmorLayerMixin(
    source: String
): String {
    val nativeMethods = TargetMembers.methodDescriptors(
        HUMANOID_ARMOR_LAYER,
        "renderArmorPiece"
    )
    if (ARMOR_RENDER_LEGACY_DESCRIPTOR !in nativeMethods ||
        ARMOR_RENDER_NATIVE_DESCRIPTOR !in nativeMethods ||
        "()Lnet/minecraft/world/item/Item;" !in
        TargetMembers.methodDescriptors(ITEM_STACK, "getItem")
    ) {
        return source
    }
    val parsed = parseCreateSource(source) ?: return source
    val owner = parsed.mixinOwner(HUMANOID_ARMOR_LAYER) ?: return source
    val (method, injection) = parsed.methodWithAnnotation(owner, INJECT)
        ?: return source
    val descriptor = injection.member("method") as? StringLiteralExpr ?: return source
    if (descriptor.value != "renderArmorPiece$ARMOR_RENDER_LEGACY_DESCRIPTOR" ||
        injection.booleanMember("cancellable") != true ||
        parsed.atAnnotation(injection, "INVOKE", ITEM_STACK_GET_ITEM_TARGET) == null ||
        !parsed.isCreateArmorHandler(method)
    ) {
        return source
    }
    val callbackStart = parsed.range(method.parameters[6])?.first ?: return source
    val edits = listOfNotNull(
        parsed.replace(
            descriptor,
            "\"renderArmorPiece$ARMOR_RENDER_NATIVE_DESCRIPTOR\""
        ),
        parsed.insert(
            callbackStart,
            "float limbSwing, float limbSwingAmount, float partialTick, " +
                "float ageInTicks, float netHeadYaw, float headPitch, "
        )
    )
    if (edits.size != 2) return source
    return applyCreateEdits(source, edits) ?: source
}

private fun returnsBoolean(statement: Statement, value: Boolean): Boolean {
    val returned = when {
        statement.isReturnStmt -> statement.asReturnStmt()
        statement.isBlockStmt ->
            statement.asBlockStmt().statements.singleOrNull() as? ReturnStmt
        else -> null
    } ?: return false
    return (returned.expression.orElse(null) as? BooleanLiteralExpr)?.value == value
}

private data class OptionalMixinGuard(
    val statement: IfStmt,
    val receiver: NameExpr
)

private fun CreateParsedSource.optionalMixinGuard(
    method: MethodDeclaration,
    prefix: String,
    mod: String
): OptionalMixinGuard? {
    val body = method.body.orElse(null) ?: return null
    return body.statements.filterIsInstance<IfStmt>().mapNotNull { statement ->
        if (statement.elseStmt.isPresent ||
            !returnsBoolean(statement.thenStmt, false)
        ) {
            return@mapNotNull null
        }
        val condition = unwrapCreateExpression(statement.condition) as? BinaryExpr
            ?: return@mapNotNull null
        if (condition.operator != BinaryExpr.Operator.AND) return@mapNotNull null
        val terms = listOf(condition.left, condition.right)
        val prefixTerm = terms.mapNotNull { term ->
            val call = unwrapCreateExpression(term) as? MethodCallExpr
                ?: return@mapNotNull null
            val receiver = call.scope.orElse(null) as? NameExpr
                ?: return@mapNotNull null
            if (call.nameAsString == "startsWith" &&
                call.arguments.size == 1 &&
                (call.arguments.single() as? StringLiteralExpr)?.value == prefix
            ) {
                receiver
            } else {
                null
            }
        }.singleOrNull() ?: return@mapNotNull null
        if (terms.none { term ->
                val negation = unwrapCreateExpression(term) as? UnaryExpr
                    ?: return@none false
                if (negation.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                    return@none false
                }
                val loaded = unwrapCreateExpression(negation.expression) as? MethodCallExpr
                    ?: return@none false
                val enumConstant = loaded.scope.orElse(null) as? FieldAccessExpr
                    ?: return@none false
                loaded.nameAsString == "isLoaded" &&
                    loaded.arguments.isEmpty() &&
                    enumConstant.nameAsString == mod &&
                    typing.isTypeName(enumConstant.scope.toString(), MODS)
            }
        ) {
            return@mapNotNull null
        }
        OptionalMixinGuard(statement, prefixTerm)
    }.singleOrNull()
}

private fun StructuralRefactorPass.migrateCreateXaeroMixinPluginGuard(
    source: String
): String {
    val parsed = parseCreateSource(source) ?: return source
    val packageName = parsed.unit.packageDeclaration.map { it.nameAsString }.orElse("")
    if (packageName != CREATE_MIXIN_PLUGIN.substringBeforeLast('.')) return source
    val owner = parsed.unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
        ?: return source
    if (owner.nameAsString != CREATE_MIXIN_PLUGIN.substringAfterLast('.') ||
        !owner.isPublic ||
        owner.isInterface ||
        owner.implementedTypes.none {
            parsed.typing.isTypeName(it.nameWithScope, MIXIN_CONFIG_PLUGIN)
        }
    ) {
        return source
    }
    val method = owner.methods.singleOrNull { candidate ->
        candidate.nameAsString == "shouldApplyMixin" &&
            candidate.isPublic &&
            !candidate.isStatic &&
            candidate.type.isPrimitiveType &&
            candidate.type.asPrimitiveType().type == PrimitiveType.Primitive.BOOLEAN &&
            candidate.parameters.size == 2 &&
            candidate.parameters[0].nameAsString == "targetClassName" &&
            parsed.typing.isClassType(candidate.parameters[0].type, "java.lang.String") &&
            candidate.parameters[1].nameAsString == "mixinClassName" &&
            parsed.typing.isClassType(candidate.parameters[1].type, "java.lang.String")
    } ?: return source
    val body = method.body.orElse(null) ?: return source
    if (!returnsBoolean(body.statements.lastOrNull() ?: return source, true)) return source
    val journey = parsed.optionalMixinGuard(
        method,
        JOURNEYMAP_MIXIN_PREFIX,
        "JOURNEYMAP"
    ) ?: return source
    if (journey.receiver.nameAsString != "mixinClassName") return source
    val xaero = parsed.optionalMixinGuard(
        method,
        XAERO_MIXIN_PREFIX,
        "XAEROWORLDMAP"
    ) ?: return source
    if (xaero.receiver.nameAsString != "targetClassName") return source
    val edit = parsed.replace(xaero.receiver, "mixinClassName") ?: return source
    return applyCreateEdits(source, listOf(edit)) ?: source
}

private data class MapHandlerPlan(
    val descriptor: StringLiteralExpr,
    val callback: com.github.javaparser.ast.body.Parameter,
    val call: MethodCallExpr,
    val providerName: String,
    val migratedDescriptor: String
)

private fun CreateParsedSource.mapLoadPlan(
    owner: ClassOrInterfaceDeclaration
): MapHandlerPlan? {
    val candidates = owner.methods.mapNotNull { method ->
        val inject = annotation(method.annotations, INJECT) ?: return@mapNotNull null
        val descriptor =
            "load(Lnet/minecraft/nbt/CompoundTag;)" +
                "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;"
        if (inject.stringMember("method") != descriptor ||
            atAnnotation(inject, "RETURN") == null ||
            !method.isStatic ||
            !method.type.isVoidType ||
            method.parameters.size != 2 ||
            !typing.isClassType(method.parameters[0].type, COMPOUND_TAG)
        ) {
            return@mapNotNull null
        }
        val callback = callback(method, MAP_DATA) ?: return@mapNotNull null
        val call = method.findAll(MethodCallExpr::class.java).singleOrNull { call ->
            call.nameAsString == "load" &&
                call.arguments.size == 1 &&
                call.scope.map {
                    typing.isTypeName(it.toString(), STATION_MARKER)
                }.orElse(false)
        } ?: return@mapNotNull null
        MapHandlerPlan(
            descriptor = inject.member("method") as? StringLiteralExpr
                ?: return@mapNotNull null,
            callback = callback,
            call = call,
            providerName = pass.uniqueJavaLocalName(method.toString(), "registries"),
            migratedDescriptor =
                "load(Lnet/minecraft/nbt/CompoundTag;" +
                    "Lnet/minecraft/core/HolderLookup\$Provider;)" +
                    "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;"
        )
    }
    return candidates.singleOrNull()
}

private fun CreateParsedSource.mapSavePlan(
    owner: ClassOrInterfaceDeclaration
): MapHandlerPlan? {
    val candidates = owner.methods.mapNotNull { method ->
        val inject = annotation(method.annotations, INJECT) ?: return@mapNotNull null
        val descriptor =
            "save(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"
        if (inject.stringMember("method") != descriptor ||
            atAnnotation(inject, "RETURN") == null ||
            method.isStatic ||
            !method.type.isVoidType ||
            method.parameters.size != 2 ||
            !typing.isClassType(method.parameters[0].type, COMPOUND_TAG)
        ) {
            return@mapNotNull null
        }
        val callback = callback(method, COMPOUND_TAG) ?: return@mapNotNull null
        val loop = method.findAll(ForEachStmt::class.java).singleOrNull { loop ->
            loop.variable.variables.size == 1 &&
                typing.isClassType(loop.variable.variables.single().type, STATION_MARKER) &&
                loop.iterable.toString().replace(Regex("""\s+"""), "") ==
                "create\$stationMarkers.values()"
        } ?: return@mapNotNull null
        val markerName = loop.variable.variables.single().nameAsString
        val call = loop.findAll(MethodCallExpr::class.java).singleOrNull { call ->
            call.nameAsString == "save" &&
                call.arguments.isEmpty() &&
                (call.scope.orElse(null) as? NameExpr)?.nameAsString == markerName
        } ?: return@mapNotNull null
        MapHandlerPlan(
            descriptor = inject.member("method") as? StringLiteralExpr
                ?: return@mapNotNull null,
            callback = callback,
            call = call,
            providerName = pass.uniqueJavaLocalName(method.toString(), "registries"),
            migratedDescriptor =
                "save(Lnet/minecraft/nbt/CompoundTag;" +
                    "Lnet/minecraft/core/HolderLookup\$Provider;)" +
                    "Lnet/minecraft/nbt/CompoundTag;"
        )
    }
    return candidates.singleOrNull()
}

private fun StructuralRefactorPass.migrateCreateMapItemSavedDataMixin(
    source: String
): String {
    val parsed = parseCreateSource(source) ?: return source
    val owner = parsed.mixinOwner(MAP_DATA) ?: return source
    val nativeLoad =
        "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup\$Provider;)" +
            "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;"
    val nativeSave =
        "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup\$Provider;)" +
            "Lnet/minecraft/nbt/CompoundTag;"
    if (nativeLoad !in TargetMembers.methodDescriptors(MAP_DATA, "load") ||
        nativeSave !in TargetMembers.methodDescriptors(MAP_DATA, "save")
    ) {
        return source
    }
    val load = parsed.mapLoadPlan(owner) ?: return source
    val save = parsed.mapSavePlan(owner) ?: return source
    val loadCallClose = parsed.insertionBeforeCallClosingParen(load.call) ?: return source
    val saveCallClose = parsed.insertionBeforeCallClosingParen(save.call) ?: return source
    val loadCallback = parsed.range(load.callback)?.first ?: return source
    val saveCallback = parsed.range(save.callback)?.first ?: return source
    val edits = listOfNotNull(
        parsed.replace(load.descriptor, "\"${load.migratedDescriptor}\""),
        parsed.insert(
            loadCallback,
            "$HOLDER_PROVIDER ${load.providerName}, "
        ),
        parsed.insert(loadCallClose, ", ${load.providerName}"),
        parsed.replace(save.descriptor, "\"${save.migratedDescriptor}\""),
        parsed.insert(
            saveCallback,
            "$HOLDER_PROVIDER ${save.providerName}, "
        ),
        parsed.insert(saveCallClose, save.providerName)
    )
    if (edits.size != 6) return source
    return applyCreateEdits(source, edits) ?: source
}

private fun nativeDispenserMethodRequiresLevel(): Boolean {
    val descriptors = TargetMembers.methodDescriptors(DISPENSER_BLOCK, "getDispenseMethod")
    val legacy =
        "(Lnet/minecraft/world/item/ItemStack;)" +
            "Lnet/minecraft/core/dispenser/DispenseItemBehavior;"
    val native =
        "(Lnet/minecraft/world/level/Level;" +
            "Lnet/minecraft/world/item/ItemStack;)" +
            "Lnet/minecraft/core/dispenser/DispenseItemBehavior;"
    return native in descriptors && legacy !in descriptors
}

private fun StructuralRefactorPass.migrateCreateDispenserBlockAccessor(
    source: String
): String {
    if (!nativeDispenserMethodRequiresLevel()) return source
    val parsed = parseCreateSource(source) ?: return source
    val packageName = parsed.unit.packageDeclaration.map { it.nameAsString }.orElse("")
    if (packageName != DISPENSER_BLOCK_ACCESSOR.substringBeforeLast('.')) return source
    val owner = parsed.unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
        ?: return source
    if (owner.nameAsString != DISPENSER_BLOCK_ACCESSOR.substringAfterLast('.') ||
        !owner.isInterface ||
        !owner.isPublic ||
        owner.typeParameters.isNotEmpty() ||
        parsed.mixinOwner(DISPENSER_BLOCK) !== owner
    ) {
        return source
    }
    val method = owner.methods.singleOrNull { candidate ->
        candidate.nameAsString == "create\$callGetDispenseMethod" &&
            parsed.annotation(candidate.annotations, INVOKER)
                ?.stringMember("value") == "getDispenseMethod"
    } ?: return source
    if (method.isStatic ||
        method.body.isPresent ||
        method.typeParameters.isNotEmpty() ||
        !parsed.typing.isClassType(method.type, DISPENSE_ITEM_BEHAVIOR)
    ) {
        return source
    }
    if (method.parameters.size == 2) return source
    val stack = method.parameters.singleOrNull() ?: return source
    if (stack.isVarArgs ||
        stack.nameAsString != "stack" ||
        !parsed.typing.isClassType(stack.type, ITEM_STACK)
    ) {
        return source
    }
    val insertion = parsed.range(stack)?.first ?: return source
    return applyCreateEdits(
        source,
        listOf(parsed.insert(insertion, "$LEVEL level, "))
    ) ?: source
}

private fun unwrapCreateExpression(expression: Expression): Expression {
    var current = expression
    while (current is EnclosedExpr) current = current.inner
    return current
}

private fun CreateParsedSource.isLegacyDispenserInvokerCall(
    call: MethodCallExpr,
    stackName: String
): Boolean {
    if (call.nameAsString != "create\$callGetDispenseMethod" ||
        call.arguments.size != 1 ||
        (call.arguments.single() as? NameExpr)?.nameAsString != stackName
    ) {
        return false
    }
    val cast = call.scope.orElse(null)
        ?.let(::unwrapCreateExpression) as? CastExpr
        ?: return false
    if (!typing.isClassType(cast.type, DISPENSER_BLOCK_ACCESSOR)) return false
    val receiver = unwrapCreateExpression(cast.expression) as? FieldAccessExpr
        ?: return false
    return receiver.nameAsString == "DISPENSER" &&
        typing.isTypeName(receiver.scope.toString(), BLOCKS)
}

private fun CreateParsedSource.methodIndent(method: MethodDeclaration): String {
    val start = offset(method)
    if (start < 0) return ""
    val lineStart = source.lastIndexOf('\n', start - 1) + 1
    return source.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
}

private fun StructuralRefactorPass.migrateCreateDispenserBehaviorConverter(
    source: String
): String {
    if (!nativeDispenserMethodRequiresLevel()) return source
    val parsed = parseCreateSource(source) ?: return source
    val packageName = parsed.unit.packageDeclaration.map { it.nameAsString }.orElse("")
    if (packageName != DISPENSER_BEHAVIOR_CONVERTER.substringBeforeLast('.')) return source
    val owner = parsed.unit.types.singleOrNull() as? EnumDeclaration ?: return source
    if (owner.nameAsString != DISPENSER_BEHAVIOR_CONVERTER.substringAfterLast('.') ||
        !owner.isPublic
    ) {
        return source
    }
    val method = owner.members.filterIsInstance<MethodDeclaration>().singleOrNull { candidate ->
        candidate.nameAsString == "getDispenseMethod"
    } ?: return source
    if (!method.isPrivate ||
        !method.isStatic ||
        method.typeParameters.isNotEmpty() ||
        parsed.annotation(method.annotations, NULLABLE) == null ||
        !parsed.typing.isClassType(method.type, DISPENSE_ITEM_BEHAVIOR)
    ) {
        return source
    }
    val stack = method.parameters.singleOrNull() ?: return source
    if (stack.isVarArgs ||
        stack.nameAsString != "stack" ||
        !parsed.typing.isClassType(stack.type, ITEM_STACK)
    ) {
        return source
    }
    val body = method.body.orElse(null) ?: return source
    val returned = (body.statements.singleOrNull() as? ReturnStmt)
        ?.expression
        ?.orElse(null) as? MethodCallExpr
        ?: return source
    if (!parsed.isLegacyDispenserInvokerCall(returned, stack.nameAsString)) return source
    val newline = if ("\r\n" in source) "\r\n" else "\n"
    val indent = parsed.methodIndent(method)
    val step = if ('\t' in indent) "\t" else "    "
    val inner = indent + step
    val replacement = buildString {
        append('{').append(newline)
        append(inner)
            .append("net.minecraft.server.MinecraftServer server = ")
            .append("net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();")
            .append(newline)
        append(inner).append("if (server == null)").append(newline)
        append(inner).append(step).append("return null;").append(newline)
        append(newline)
        append(inner)
            .append("return ((com.simibubi.create.foundation.mixin.accessor.")
            .append("DispenserBlockAccessor) net.minecraft.world.level.block.Blocks.DISPENSER)")
            .append(".create\$callGetDispenseMethod(")
            .append("server.getLevel(net.minecraft.world.level.Level.OVERWORLD), stack);")
            .append(newline)
        append(indent).append('}')
    }
    val edit = parsed.replace(body, replacement) ?: return source
    return applyCreateEdits(source, listOf(edit)) ?: source
}

private fun StructuralRefactorPass.migrateCreateStationMarkerRegistryCarrier(
    source: String
): String {
    val parsed = parseCreateSource(source) ?: return source
    val packageName = parsed.unit.packageDeclaration.map { it.nameAsString }.orElse("")
    val owner = parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java).singleOrNull {
        it.nameAsString == "StationMarker" &&
            packageName == STATION_MARKER.substringBeforeLast('.') &&
            it.parentNode.orElse(null) === parsed.unit
    } ?: return source
    if ("$packageName.${owner.nameAsString}" != STATION_MARKER) return source
    val load = owner.methods.singleOrNull { method ->
        method.nameAsString == "load" &&
            method.isPublic &&
            method.isStatic &&
            parsed.typing.isClassType(method.type, STATION_MARKER) &&
            method.parameters.size == 1 &&
            parsed.typing.isClassType(method.parameters.single().type, COMPOUND_TAG)
    } ?: return source
    val save = owner.methods.singleOrNull { method ->
        method.nameAsString == "save" &&
            method.isPublic &&
            !method.isStatic &&
            parsed.typing.isClassType(method.type, COMPOUND_TAG) &&
            method.parameters.isEmpty()
    } ?: return source
    val fromJson = load.findAll(MethodCallExpr::class.java).singleOrNull { call ->
        call.nameAsString == "fromJson" &&
            call.arguments.size == 1 &&
            parsed.isComponentSerializerScope(call.scope.orElse(null))
    } ?: return source
    val toJson = save.findAll(MethodCallExpr::class.java).singleOrNull { call ->
        call.nameAsString == "toJson" &&
            call.arguments.size == 1 &&
            parsed.isComponentSerializerScope(call.scope.orElse(null))
    } ?: return source
    val loadClose = parsed.methodParameterClosingParen(load) ?: return source
    val saveClose = parsed.methodParameterClosingParen(save) ?: return source
    val loadProvider = uniqueJavaLocalName(load.toString(), "registries")
    val saveProvider = uniqueJavaLocalName(save.toString(), "registries")
    val edits = listOf(
        parsed.insert(loadClose, ", $HOLDER_PROVIDER $loadProvider"),
        parsed.insert(saveClose, "$HOLDER_PROVIDER $saveProvider")
    )
    val migrated = applyCreateEdits(source, edits) ?: return source
    // fromJson/toJson are intentionally left at one argument here. The immediately following
    // component migration sees these exact new parameters and supplies loadProvider/saveProvider.
    check(fromJson.arguments.size == 1 && toJson.arguments.size == 1)
    return migrated
}
