package com.modporter.core.transforms.structural

/** NeoForge's registered condition codec replaces CraftingHelper's removed JSON writer. */
internal fun StructuralRefactorPass.migrateConditionSerialization(source: String): String {
    if (!source.contains("serialize") || !source.contains("CraftingHelper")) return source
    val typing = SourceValueTypes(this, source)
    var result = source
    for (owner in listOf("CraftingHelper", "net.neoforged.neoforge.common.crafting.CraftingHelper")) {
        if (!typing.isTypeName(owner, "net.neoforged.neoforge.common.crafting.CraftingHelper")) continue
        result = rewriteJavaInvocation(result, "$owner.serialize") { args ->
            if (args.size != 1) null else
                "net.neoforged.neoforge.common.conditions.ICondition.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, ${args.single()}).getOrThrow().getAsJsonObject()"
        }
    }
    return result
}
