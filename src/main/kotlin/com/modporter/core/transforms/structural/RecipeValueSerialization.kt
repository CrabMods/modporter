package com.modporter.core.transforms.structural

/**
 * Ingredient's JSON and contents codecs and FluidStack's optional packet codec replace
 * their 1.20 serializers. Infer the receiver at its lexical position: another recipe
 * value can also declare toJson/toNetwork, including inside the same forEach chain.
 */
internal fun StructuralRefactorPass.migrateProvenRecipeValueSerialization(source: String, network: Boolean = true): String {
    if (!source.contains(".toJson(") && !source.contains(".toNetwork(") &&
        !source.contains(".writeToPacket(") && !source.contains(".readFromPacket(")) return source
    val ingredient = "net.minecraft.world.item.crafting.Ingredient"
    val fluid = "net.neoforged.neoforge.fluids.FluidStack"
    val buffer = "net.minecraft.network.RegistryFriendlyByteBuf"
    var result = source
    var typing = SourceValueTypes(this, result)
    result = rewriteExecutableJavaCallWithOffset(result, "toJson") { receiver, args, offset ->
        if (args.isNotEmpty() || typing.typeOf(receiver, offset) != ingredient) null
        else "$ingredient.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, $receiver).getOrThrow()"
    }
    if (!network) return result
    typing = SourceValueTypes(this, result)
    result = rewriteExecutableJavaCallWithOffset(result, "toNetwork") { receiver, args, offset ->
        if (args.size != 1 || typing.typeOf(receiver, offset) != ingredient) null
        else "$ingredient.CONTENTS_STREAM_CODEC.encode(($buffer) (${args[0]}), $receiver)"
    }
    typing = SourceValueTypes(this, result)
    result = rewriteExecutableJavaCallWithOffset(result, "writeToPacket") { receiver, args, offset ->
        if (args.size != 1 || typing.typeOf(receiver, offset) != fluid) null
        else "$fluid.OPTIONAL_STREAM_CODEC.encode(($buffer) (${args[0]}), $receiver)"
    }
    typing = SourceValueTypes(this, result)
    result = rewriteExecutableJavaCallWithOffset(result, "readFromPacket") { receiver, args, _ ->
        if (args.size != 1 || !typing.isTypeName(receiver, fluid)) null
        else "$fluid.OPTIONAL_STREAM_CODEC.decode(($buffer) (${args[0]}))"
    }
    return result
}
