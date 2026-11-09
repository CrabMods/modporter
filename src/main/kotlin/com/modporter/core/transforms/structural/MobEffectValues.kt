package com.modporter.core.transforms.structural

/** MobEffectInstance exposes its registry holder; effect behavior remains on the held value. */
internal fun StructuralRefactorPass.migrateMobEffectValueCalls(source: String): String {
    if (!source.contains("MobEffect") && !source.contains("AttributeModifier") && !source.contains("ATTRIBUTE_MODIFIER_FORMAT")) return source
    var result = source
    for (method in listOf("isInstantenous", "applyInstantenousEffect", "getCategory", "getColor", "getDescriptionId",
            "getDisplayName", "isBeneficial", "getAttributeModifiers", "getAttributeModifierValue")) {
        if (!result.contains(method)) continue
        val typing = SourceValueTypes(this, result)
        result = rewriteExecutableJavaCallWithOffset(result, method) { receiver, args, offset ->
            if (typing.holderValueType(receiver, offset) != "net.minecraft.world.effect.MobEffect") null
            else "$receiver.value().$method(${args.joinToString(", ")})"
        }
    }
    val typing = SourceValueTypes(this, result)
    result = rewriteExecutableJavaCallWithOffset(result, "toValue") { receiver, args, offset ->
        if (args.isEmpty() && typing.typeOf(receiver, offset) == "net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation") "$receiver.id()" else null
    }
    result = rewriteJavaInvocationArgumentsWithOffset(result, "MobEffectUtil.formatDuration") { args, _ ->
        if (args.size == 2 && typing.isTypeName("MobEffectUtil", "net.minecraft.world.effect.MobEffectUtil")) args + "20.0F" else null
    }
    result = replaceExecutableJavaRegex(result, Regex("""(?<![\w$.])((?:net\.minecraft\.world\.item\.)?ItemStack)\.ATTRIBUTE_MODIFIER_FORMAT\b""")) { match ->
        if (typing.isTypeName(match.groupValues[1], "net.minecraft.world.item.ItemStack"))
            "net.minecraft.world.item.component.ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT" else match.value
    }
    return result
}
