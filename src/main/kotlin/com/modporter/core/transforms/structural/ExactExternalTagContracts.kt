package com.modporter.core.transforms.structural

import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.NameExpr

internal object ExactExternalTagContracts {
    enum class Effect {
        READ,
        MUTATE
    }

    fun compoundTagArgumentEffect(
        call: MethodCallExpr,
        argumentIndex: Int,
        exact: ExactJavaSemantics,
        typeIndex: JavaProjectTypeIndex? = null
    ): Effect? {
        if (argumentIndex == 0 &&
            call.nameAsString == "of" &&
            call.scope.map {
                exact.exactStaticScope(it, "CustomData", CUSTOM_DATA)
            }.orElse(false)
        ) {
            return Effect.READ
        }
        if (call.scope.map {
                exact.exactStaticScope(it, "ItemStack", ITEM_STACK)
            }.orElse(false) &&
            ((call.nameAsString == "of" && argumentIndex == 0) ||
                (call.nameAsString == "parseOptional" &&
                    argumentIndex == call.arguments.lastIndex))
        ) {
            return Effect.READ
        }
        if (call.nameAsString == "readBlockState" &&
            argumentIndex == call.arguments.lastIndex &&
            call.scope.map {
                exact.exactStaticScope(it, "NbtUtils", NBT_UTILS)
            }.orElse(false)
        ) {
            return Effect.READ
        }
        if (call.nameAsString == "equals" &&
            argumentIndex in call.arguments.indices &&
            call.scope.map {
                exact.exactStaticScope(it, "Objects", OBJECTS)
            }.orElse(false)
        ) {
            return Effect.READ
        }
        val receiverType = typeIndex?.methodCallReceiverType(call)
        if (call.nameAsString == "equals" && call.arguments.size == 1 && argumentIndex == 0 &&
            "(Ljava/lang/Object;)Z" in
                com.modporter.mapping.TargetMembers.methodDescriptors(COMPOUND_TAG, "equals") &&
            call.scope.map {
                typeIndex?.isExpressionExactlyExternalType(it, call, COMPOUND_TAG) == true
            }.orElse(false)
        ) {
            return Effect.READ
        }
        val exactItemHandler = call.scope.map { scope ->
            exact.isProvablyType(scope, "ItemStackHandler", ITEM_STACK_HANDLERS)
        }.orElse(false)
        if (call.nameAsString == "deserializeNBT" &&
            argumentIndex == call.arguments.lastIndex &&
            (receiverType in ITEM_STACK_HANDLERS || exactItemHandler)
        ) {
            return Effect.READ
        }
        if (call.nameAsString == "setTag" &&
            argumentIndex == 0 &&
            call.arguments.size == 1
        ) {
            val scope = call.scope.orElse(null)
            val itemStackReceiver = scope != null &&
                (exact.isProvablyType(scope, "ItemStack", ITEM_STACK) ||
                    typeIndex?.isExpressionAssignableTo(scope, call, ITEM_STACK) == true)
            if (itemStackReceiver) return Effect.READ
        }
        if (call.nameAsString == "put" &&
            argumentIndex == 1 &&
            call.arguments.size == 2
        ) {
            val scope = call.scope.orElse(null)
            val customDataUpdateTag = (scope as? NameExpr)?.let { tagName ->
                val lambda = call.findAncestor(LambdaExpr::class.java).orElse(null)
                    ?: return@let false
                val updateCall = lambda.parentNode.orElse(null) as? MethodCallExpr
                    ?: return@let false
                lambda.parameters.any { it.nameAsString == tagName.nameAsString } &&
                    updateCall.nameAsString == "update" &&
                    updateCall.arguments.size == 3 &&
                    updateCall.arguments[2] === lambda &&
                    updateCall.scope.map {
                        exact.exactStaticScope(it, "CustomData", CUSTOM_DATA)
                    }.orElse(false)
            } == true
            val exactCompound = scope != null &&
                (exact.isProvablyType(scope, "CompoundTag", COMPOUND_TAG) ||
                    typeIndex?.isExpressionAssignableTo(scope, call, COMPOUND_TAG) == true ||
                    customDataUpdateTag)
            val legacyLiveTag = (scope as? MethodCallExpr)?.let { tagCall ->
                tagCall.nameAsString == "getOrCreateTag" &&
                    tagCall.arguments.isEmpty() &&
                    tagCall.scope.map { owner ->
                        exact.isProvablyType(owner, "ItemStack", ITEM_STACK) ||
                            typeIndex?.isExpressionAssignableTo(owner, tagCall, ITEM_STACK) == true
                    }.orElse(false)
            } == true
            if (exactCompound || legacyLiveTag) return Effect.READ
        }
        if (argumentIndex == 0 &&
            call.nameAsString in POTION_UTILS_READS &&
            call.scope.map {
                exact.exactStaticScope(it, "PotionUtils", POTION_UTILS)
            }.orElse(false)
        ) {
            return Effect.READ
        }
        if (argumentIndex != 0 ||
            !call.scope.map {
                exact.exactStaticScope(it, "NBTHelper", CATNIP_NBT_HELPER)
            }.orElse(false)
        ) {
            return null
        }
        // Ponder 1.0.87+mc1.21.1 NBTHelper.class SHA-256 e654636a4b9ef362cd46e12a0646d77b37cd2287ca33278e71a725ca419a88c5:
        // readResourceLocation(CompoundTag,String) reads getString(key), then ResourceLocation.parse.
        if (call.nameAsString == "readResourceLocation" && call.arguments.size == 2 &&
            typeIndex != null && !typeIndex.hasSourceTypeDefinition(CATNIP_NBT_HELPER) &&
            typeIndex.argumentsMatchTypes(call, listOf(COMPOUND_TAG, "java.lang.String"))
        ) return Effect.READ
        return when (call.nameAsString) {
            "readEnum",
            "readCompoundList",
            "readItemList",
            "iterateCompoundList",
            "readAABB" -> Effect.READ
            "writeEnum" -> Effect.MUTATE
            else -> null
        }
    }

    fun compoundTagReceiverEffect(methodName: String): Effect? = when (methodName) {
        in MUTATOR_METHODS -> Effect.MUTATE
        in SAFE_READ_METHODS -> Effect.READ
        else -> null
    }

    private const val CATNIP_NBT_HELPER = "net.createmod.catnip.nbt.NBTHelper"
    private const val CUSTOM_DATA = "net.minecraft.world.item.component.CustomData"
    private const val POTION_UTILS = "net.minecraft.world.item.alchemy.PotionUtils"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val NBT_UTILS = "net.minecraft.nbt.NbtUtils"
    private const val OBJECTS = "java.util.Objects"
    private const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    private val ITEM_STACK_HANDLERS = setOf(
        "net.minecraftforge.items.ItemStackHandler",
        "net.neoforged.neoforge.items.ItemStackHandler"
    )
    private val POTION_UTILS_READS = setOf(
        "getPotion",
        "getCustomEffects",
        "getAllEffects",
        "getMobEffects",
        "getColor"
    )
    private val MUTATOR_METHODS = setOf(
        "put",
        "putByte",
        "putShort",
        "putInt",
        "putLong",
        "putUUID",
        "putFloat",
        "putDouble",
        "putString",
        "putByteArray",
        "putIntArray",
        "putLongArray",
        "putBoolean",
        "remove",
        "merge",
        "add",
        "addAll",
        "set",
        "clear",
        "replaceAll",
        "sort"
    )
    private val SAFE_READ_METHODS = setOf(
        "sizeInBytes",
        "getId",
        "getType",
        "size",
        "getUUID",
        "hasUUID",
        "getTagType",
        "contains",
        "getByte",
        "getShort",
        "getInt",
        "getLong",
        "getFloat",
        "getDouble",
        "getString",
        "getBoolean",
        "toString",
        "isEmpty",
        "copy",
        "equals",
        "hashCode"
    )
}
