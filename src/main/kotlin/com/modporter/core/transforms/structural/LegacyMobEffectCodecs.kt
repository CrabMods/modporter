package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/** FactorData is absent from the target effect state; the remaining custom codec schema stays intact. */
internal fun StructuralRefactorPass.migrateLegacyMobEffectCodecs(source: String): String {
    if (!source.contains("getFactorData") || !source.contains("MobEffectInstance")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    if (!typing.isTypeName("MobEffectInstance", "net.minecraft.world.effect.MobEffectInstance")) return source
    val descriptor = "(Lnet/minecraft/core/Holder;IIZZZLnet/minecraft/world/effect/MobEffectInstance;)V"
    if (descriptor !in TargetMembers.methodDescriptors("net.minecraft.world.effect.MobEffectInstance", "<init>")) return source
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (getter in unit.findAll(MethodCallExpr::class.java).toList()) {
        if (getter.nameAsString != "forGetter" || getter.arguments.size != 1) continue
        val reference = getter.arguments[0] as? MethodReferenceExpr ?: continue
        if (reference.identifier != "getFactorData" || !typing.isTypeName(reference.scope.toString(), "net.minecraft.world.effect.MobEffectInstance")) continue
        val field = getter.scope.orElse(null) as? MethodCallExpr ?: continue
        if (field.nameAsString != "optionalFieldOf" || field.arguments.firstOrNull() !is StringLiteralExpr) continue
        val fieldName = (field.arguments[0] as StringLiteralExpr).asString()
        val group = getter.parentNode.orElse(null) as? MethodCallExpr ?: continue
        if (group.nameAsString != "group" || group.arguments.size != 8) continue
        val index = group.arguments.indexOf(getter)
        if (index < 0) continue
        val apply = group.parentNode.orElse(null) as? MethodCallExpr ?: continue
        if (apply.nameAsString != "apply" || apply.arguments.size != 2) continue
        val factory = apply.arguments[1] as? LambdaExpr ?: continue
        if (factory.parameters.size != group.arguments.size) continue
        val parameter = factory.parameters[index].nameAsString
        val construction = factory.findAll(ObjectCreationExpr::class.java).singleOrNull { it.type.nameAsString == "MobEffectInstance" && it.arguments.size == 8 } ?: continue
        if (construction.arguments[index].toString() != parameter) continue
        val uses = factory.body.findAll(NameExpr::class.java).filter { it.nameAsString == parameter }
        if (uses.size != 1 || uses.single() !== construction.arguments[index]) continue
        val effectGetter = group.arguments.filterIsInstance<MethodCallExpr>().singleOrNull { call ->
            call.nameAsString == "forGetter" && call.arguments.singleOrNull().let { it is MethodReferenceExpr && it.identifier == "getEffect" }
        } ?: continue
        val registryCodec = effectGetter.findAll(MethodCallExpr::class.java).singleOrNull { it.nameAsString == "byNameCodec" && it.scope.map { scope -> scope.toString() }.orElse("") == "BuiltInRegistries.MOB_EFFECT" } ?: continue
        if (!typing.isTypeName("BuiltInRegistries", "net.minecraft.core.registries.BuiltInRegistries")) continue
        registryCodec.setName("holderByNameCodec")
        construction.arguments.removeAt(index)
        val sentinel = """
            com.mojang.serialization.Codec.PASSTHROUGH.optionalFieldOf(${StringLiteralExpr(fieldName)})
                .flatXmap(legacy -> legacy.isPresent()
                    ? com.mojang.serialization.DataResult.error(() -> "Legacy factor_data cannot be migrated: target MobEffectInstance has no equivalent serialized blend state")
                    : com.mojang.serialization.DataResult.success(legacy),
                    legacy -> com.mojang.serialization.DataResult.success(legacy))
                .forGetter(effectValue -> java.util.Optional.empty())
        """.trimIndent()
        group.arguments[index] = parser.parseExpression<Expression>(sentinel).result.orElseThrow()
        changed = true
    }
    if (!changed) return source
    val result = removeUnusedSimpleImports(LexicalPreservingPrinter.print(unit), listOf("net.minecraft.world.effect.MobEffectInstance.FactorData"))
    check(parser.parse(result).isSuccessful) { "Mob effect codec migration emitted invalid Java" }
    return result
}
