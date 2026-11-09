package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.*
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.type.ClassOrInterfaceType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.*

// Structural migration rules, part 05 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateFluidTypeClientExtensionRegistrations, migrateLivingDamageEventHelperBoundaries.

internal fun StructuralRefactorPass.replaceBuiltInRegistryHolderGetInsideIfPresent(source: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    return Regex(
        """(?s)\b(BuiltInRegistries\.[A-Z0-9_]+\.getHolder\s*\([^;]+?\)\.ifPresent\s*\(\s*($id)\s*->\s*)(\{.*?}\s*|[^;{}]*?)(\)\s*;)"""
    ).replace(source) { match ->
        val holder = match.groupValues[2]
        val body = match.groupValues[3].replace(Regex("""\b${Regex.escape(holder)}\.get\(\)"""), "$holder.value()")
        "${match.groupValues[1]}$body${match.groupValues[4]}"
    }
}

internal fun StructuralRefactorPass.migrateLegacyRecipeContainerWildcards(source: String): String {
    if (!source.contains("Recipe<? extends Container>")) return source
    var result = replaceExecutableRegex(
        source,
        Regex("""Recipe\s*<\s*\?\s+extends\s+Container\s*>""")
    ) { "Recipe<?>" }
    val withoutContainer = removeExecutableImport(result, "net.minecraft.world.Container")
    if (!Regex("""\bContainer\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutContainer))) {
        result = withoutContainer
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyAdvancementParentLoop(source: String): String {
    if (!source.contains(".getParent()") || !source.contains("AdvancementHolder")) return source
    var result = source
    var cursor = 0
    val loopHeader = Regex("""for\s*\(\s*Advancement\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\s*;\s*([A-Za-z_$][\w$]*)\s*!=\s*null\s*;\s*([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.getParent\(\)\s*\)\s*\{""")
    while (true) {
        val match = loopHeader.find(result, cursor) ?: break
        val loopVariable = match.groupValues[1]
        if (match.groupValues[3] != loopVariable || match.groupValues[4] != loopVariable || match.groupValues[5] != loopVariable) {
            cursor = match.range.last + 1
            continue
        }
        val holder = match.groupValues[2]
        val openBrace = match.range.last
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val loopBody = result.substring(openBrace + 1, closeBrace)
        val bodyMatch = Regex("""if\s*\(\s*${Regex.escape(loopVariable)}\.(?:getId\(\)|id\(\))\.equals\(([^)]+)\)\s*\)\s*\{\s*return\s+true\s*;\s*}""")
            .find(loopBody)
        if (bodyMatch == null) {
            cursor = closeBrace + 1
            continue
        }
        val indent = leadingIndentAt(result, match.range.first)
        val root = bodyMatch.groupValues[1].trim()
        val inner = "$indent    "
        val replacement = """
${indent}LocalPlayer player = Minecraft.getInstance().player;
${indent}if (player != null) {
${inner}for (AdvancementHolder current = $holder; current != null && current.value().parent().isPresent(); current = player.connection.getAdvancements().get(current.value().parent().get())) {
${inner}    if (current.id().equals($root)) {
${inner}        return true;
${inner}    }
${inner}}
${indent}}
""".trim('\n')
        result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        cursor = match.range.first + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyDeferredRegistryEntryStream(source: String): String {
    if (!source.contains(".getEntries().stream().map(Map.Entry::getValue)")) return source
    val deferredRegisters = Regex("""\bDeferredRegister\s*<\s*([A-Za-z_$][\w$]*)\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] to it.groupValues[2] }
        .toList()
    if (deferredRegisters.isEmpty()) return source
    var result = source
    for ((type, deferredRegister) in deferredRegisters) {
        val registries = linkedSetOf<String>()
        registries += Regex("""\bRegistry\s*<\s*${Regex.escape(type)}\s*>\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(result)
            .map { it.groupValues[1] }
        registries += Regex("""\bIForgeRegistry\s*<\s*${Regex.escape(type)}\s*>\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(result)
            .map { it.groupValues[1] }
        registries += Regex("""\bSupplier\s*<\s*(?:IForgeRegistry|Registry)\s*<\s*${Regex.escape(type)}\s*>\s*>\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(result)
            .map { it.groupValues[1] }
        for (registry in registries) {
            result = Regex("""(?:[A-Za-z_$][\w$]*\.)?${Regex.escape(registry)}(?:\.get\(\))?\.getEntries\(\)\.stream\(\)\.map\(Map\.Entry::getValue\)""")
                .replace(result, "$deferredRegister.getEntries().stream().map(DeferredHolder::value)")
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyDatagenTagConstantsSource(source: String): String {
    if (!source.contains("ItemTags.") &&
        !source.contains("Tags.Items.") &&
        !source.contains("BuiltInRegistries.ENCHANTMENT")) {
        return source
    }

    var result = source
    result = result.replace("ItemTags.MUSIC_DISCS", "Tags.Items.MUSIC_DISCS")
    result = result.replace("Tags.Items.ARMORS_HELMETS", "ItemTags.HEAD_ARMOR")
    result = result.replace("Tags.Items.ARMORS_CHESTPLATES", "ItemTags.CHEST_ARMOR")
    result = result.replace("Tags.Items.ARMORS_LEGGINGS", "ItemTags.LEG_ARMOR")
    result = result.replace("Tags.Items.ARMORS_BOOTS", "ItemTags.FOOT_ARMOR")
    result = Regex("""\bTags\.Items\.LEATHER\b""").replace(result, "Tags.Items.LEATHERS")
    result = Regex(
        """BuiltInRegistries\.ENCHANTMENT\.getResourceKey\(\s*(Enchantments\.[A-Z0-9_]+)\s*\)\.get\(\)"""
    ).replace(result, "$1")
    if (result.contains("Tags.Items.")) {
        result = addImportIfMissing(result, "net.neoforged.neoforge.common.Tags")
    }
    return result
}

internal fun StructuralRefactorPass.migrateHolderValueMethodArgumentSource(
    source: String,
    hints: HolderValueParameterHints
): String {
    if (!source.contains("SoundEvents.") &&
        !source.contains("Potions.") &&
        hints.mobEffectHolderParameters.isEmpty()) {
        return source
    }

    var result = source
    hints.soundEventParameters.forEach { (methodName, positions) ->
        result = rewriteJavaInvocationArguments(result, methodName) { args ->
            var changed = false
            val migrated = args.toMutableList()
            positions.forEach { index ->
                val arg = migrated.getOrNull(index)?.trim() ?: return@forEach
                if (isHolderSoundEventConstant(arg)) {
                    migrated[index] = "$arg.value()"
                    changed = true
                }
            }
            if (changed) migrated else null
        }
    }
    hints.potionParameters.forEach { (methodName, positions) ->
        result = rewriteJavaInvocationArguments(result, methodName) { args ->
            var changed = false
            val migrated = args.toMutableList()
            positions.forEach { index ->
                val arg = migrated.getOrNull(index)?.trim() ?: return@forEach
                if (Regex("""^Potions\.[A-Z0-9_]+$""").matches(arg)) {
                    migrated[index] = "$arg.value()"
                    changed = true
                }
            }
            if (changed) migrated else null
        }
    }
    result = Regex("""BuiltInRegistries\.POTION\.getKey\(\s*(Potions\.[A-Z0-9_]+)\s*\)""")
        .replace(result, "BuiltInRegistries.POTION.getKey($1.value())")
    hints.mobEffectHolderParameters.forEach { (methodName, positions) ->
        result = rewriteJavaInvocationArguments(result, methodName) { args ->
            var changed = false
            val migrated = args.toMutableList()
            positions.forEach { index ->
                val arg = migrated.getOrNull(index) ?: return@forEach
                val replacement = replaceProvenMobEffectDeferredHolderGetsInText(
                    arg,
                    hints.mobEffectDeferredHolderFields
                )
                if (replacement != arg) {
                    migrated[index] = replacement
                    changed = true
                }
            }
            if (changed) migrated else null
        }
    }
    return result
}

internal fun StructuralRefactorPass.isHolderSoundEventConstant(expression: String): Boolean {
    if (!Regex("""^SoundEvents\.[A-Z0-9_]+$""").matches(expression)) return false
    val constant = expression.substringAfter("SoundEvents.")
    return constant.startsWith("ARMOR_EQUIP_") ||
        constant.startsWith("NOTE_BLOCK_") ||
        constant.startsWith("MUSIC_") ||
        constant.startsWith("AMBIENT_")
}

internal fun StructuralRefactorPass.migrateLegacyAdvancementDatagenSource(
    source: String,
    criterionFactoryHints: CriterionInstanceFactoryHints
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("Advancement") &&
        !executableCode.contains("LocationPredicate.") &&
        !executableCode.contains("ContextAwarePredicate.ANY") &&
        !executableCode.contains("ItemDurabilityTrigger.TriggerInstance.changedDurability") &&
        !executableCode.contains(".hasNbt(") &&
        !executableCode.contains(".addStat(Stats.CUSTOM.get(")) {
        return source
    }

    var result = source
    val providerName = Regex("""\bHolderLookup\.Provider\s+([A-Za-z_$][\w$]*)\b""")
        .find(executableCode)
        ?.groupValues
        ?.get(1)

    result = replaceExecutableRegex(
        result,
        Regex("""(?s)\bAdvancement\s+([A-Za-z_$][\w$]*)\s*=\s*((?:(?!;).)*?\.save\s*\()""")
    ) { match ->
        "AdvancementHolder ${match.groupValues[1]} = ${match.groupValues[2]}"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(PlayerTrigger\.TriggerInstance\s+advancementTrigger\s*\(\s*)Advancement(\s+[A-Za-z_$][\w$]*\s*\))""")
    ) { match ->
        "${match.groupValues[1]}AdvancementHolder${match.groupValues[2]}"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(private|protected|public)\s+PlayerTrigger\.TriggerInstance\s+(advancementTrigger\s*\()""")
    ) { match ->
        "${match.groupValues[1]} Criterion<PlayerTrigger.TriggerInstance> ${match.groupValues[2]}"
    }

    val executableHolderSource = maskJavaCommentsAndLiterals(result)
    val holderVariables = Regex("""\bAdvancementHolder\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableHolderSource)
        .map { it.groupValues[1] }
        .toSet()
    holderVariables.forEach { variable ->
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(variable)}\.getId\s*\(\s*\)""")) {
            "$variable.id()"
        }
    }
    result = replaceExecutableRegex(result, Regex("""\.getAdvancement\s*\(\s*\)\s*\.getId\s*\(\s*\)""")) {
        ".getAdvancement().id()"
    }

    result = replaceExecutableRegex(
        result,
        Regex("""\bLocationPredicate\.inDimension\s*\(""")
    ) { "LocationPredicate.Builder.inDimension(" }
    if (providerName != null) {
        result = replaceExecutableRegex(
            result,
            Regex("""\bLocationPredicate\.inBiome\s*\(""")
        ) { "LocationPredicate.Builder.inBiome(" }
        result = replaceExecutableRegex(
            result,
            Regex("""\bLocationPredicate\.inStructure\s*\(""")
        ) { "LocationPredicate.Builder.inStructure(" }
        result = rewriteExecutableJavaInvocationArguments(result, "LocationPredicate.Builder.inBiome") { args ->
            if (args.size == 1 && !args[0].contains("lookupOrThrow")) {
                listOf("$providerName.lookupOrThrow(Registries.BIOME).getOrThrow(${args[0].trim()})")
            } else {
                null
            }
        }
        result = rewriteExecutableJavaInvocationArguments(result, "LocationPredicate.Builder.inStructure") { args ->
            if (args.size == 1 && !args[0].contains("lookupOrThrow")) {
                listOf("$providerName.lookupOrThrow(Registries.STRUCTURE).getOrThrow(${args[0].trim()})")
            } else {
                null
            }
        }
    }

    result = rewriteJavaCall(result, "setBlock") { receiver, args ->
        val blockArg = args.singleOrNull()?.trim() ?: return@rewriteJavaCall null
        val migratedArg = removeTrailingBuildCall(blockArg, "BlockPredicate.Builder")
        if (migratedArg != blockArg) "$receiver.setBlock($migratedArg)" else null
    }
    result = rewriteJavaCall(result, "setProperties") { receiver, args ->
        val propertiesArg = args.singleOrNull()?.trim() ?: return@rewriteJavaCall null
        val migratedArg = removeTrailingBuildCall(propertiesArg, "StatePropertiesPredicate.Builder")
        if (migratedArg != propertiesArg) "$receiver.setProperties($migratedArg)" else null
    }
    listOf("vehicle", "passenger", "targetedEntity").forEach { methodName ->
        result = rewriteJavaCall(result, methodName) { receiver, args ->
            migrateEntityPredicateBuilderReceiverCall(receiver, methodName, args)
        }
    }

    result = replaceExecutableRegex(
        result,
        Regex("""new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.Instance)\(\s*ContextAwarePredicate\.ANY\s*\)""")
    ) { match ->
        "new ${match.groupValues[1]}(java.util.Optional.empty())"
    }
    criterionFactoryHints.emptyInstanceFactories.forEach { (instanceType, factoryCall) ->
        result = replaceExecutableRegex(
            result,
            Regex("""new\s+${Regex.escape(instanceType)}\(\s*(?:java\.util\.)?Optional\.empty\(\)\s*\)""")
        ) { factoryCall }
    }
    result = migrateLegacyItemUsedOnLocationTriggerConstructorReturns(result)
    result = rewriteExecutableJavaNew(result, "ItemUsedOnLocationTrigger.TriggerInstance") { args ->
        if (args.size == 3 && legacyCriteriaTriggerExpression(args[0]) != null) {
            "new ItemUsedOnLocationTrigger.TriggerInstance(${legacyOptionalContextAwarePredicate(args[1])}, ${legacyOptionalContextAwarePredicate(args[2])})"
        } else {
            null
        }
    }
    result = rewriteExecutableJavaNew(result, "PlayerTrigger.TriggerInstance") { args ->
        val trigger = if (args.size == 2) legacyCriteriaTriggerExpression(args[0]) else null
        if (trigger != null) {
            "$trigger.createCriterion(new PlayerTrigger.TriggerInstance(${legacyOptionalContextAwarePredicate(args[1])}))"
        } else {
            null
        }
    }
    result = rewriteExecutableJavaNew(result, "InventoryChangeTrigger.TriggerInstance") { args ->
        if (args.size == 5) {
            "CriteriaTriggers.INVENTORY_CHANGED.createCriterion(new InventoryChangeTrigger.TriggerInstance(" +
                "${legacyOptionalContextAwarePredicate(args[0])}, " +
                "new InventoryChangeTrigger.TriggerInstance.Slots(${args[1].trim()}, ${args[2].trim()}, ${args[3].trim()}), " +
                "${legacyItemPredicateArrayListExpression(args[4])}))"
        } else {
            null
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(\.addCriterion\s*\(\s*[^,\r\n]+,\s*)new\s+ImpossibleTrigger\.TriggerInstance\(\)(\s*\))""")
    ) { match ->
        "${match.groupValues[1]}CriteriaTriggers.IMPOSSIBLE.createCriterion(new ImpossibleTrigger.TriggerInstance())${match.groupValues[2]}"
    }
    result = migrateLegacyInventoryChangeCriterionReturnTypes(result)
    result = rewriteJavaInvocationArguments(result, "PlayerTrigger.TriggerInstance.located") { args ->
        if (args.size == 1) {
            val arg = args[0].trim()
            val migrated = removeTrailingBuildCall(arg, "EntityPredicate.Builder")
            if (migrated != arg) listOf(migrated) else null
        } else {
            null
        }
    }
    result = rewriteJavaInvocationArguments(result, "ItemDurabilityTrigger.TriggerInstance.changedDurability") { args ->
        if (args.size == 2 && !args[0].trim().startsWith("java.util.Optional.") && !args[0].trim().startsWith("Optional.")) {
            listOf("java.util.Optional.of(${args[0].trim()})", args[1])
        } else {
            null
        }
    }

    if (maskJavaCommentsAndLiterals(result).contains(".hasNbt(")) {
        val beforeCustomDataPredicate = result
        result = rewriteJavaCall(result, "hasNbt") { receiver, args ->
            val tagArg = args.singleOrNull()?.trim() ?: return@rewriteJavaCall null
            "$receiver.hasComponents(DataComponentPredicate.builder().expect(DataComponents.CUSTOM_DATA, CustomData.of($tagArg)).build())"
        }
        result = rewriteJavaCall(result, "hasComponents") { receiver, args ->
            val componentPredicate = args.singleOrNull()?.trim() ?: return@rewriteJavaCall null
            val migrated = migrateCustomDataPredicateFromStackTag(componentPredicate)
            if (migrated != null) "$receiver.hasComponents($migrated)" else null
        }
        if (result != beforeCustomDataPredicate) {
            result = addExecutableImportIfMissing(result, "net.minecraft.core.component.DataComponentPredicate")
            result = addExecutableImportIfMissing(result, "net.minecraft.core.component.DataComponents")
            result = addExecutableImportIfMissing(result, "net.minecraft.world.item.component.CustomData")
        }
    }

    result = rewriteExecutableJavaInvocationArguments(result, "addStat") { args ->
        if (args.size == 2 && args[0].trim().startsWith("Stats.CUSTOM.get(")) {
            val statArg = args[0].trim()
            val openParen = statArg.indexOf('(')
            val closeParen = if (openParen >= 0) findMatchingParen(statArg, openParen) else -1
            if (closeParen > openParen) {
                val keyExpr = statArg.substring(openParen + 1, closeParen).trim()
                listOf(
                    "Stats.CUSTOM",
                    "BuiltInRegistries.CUSTOM_STAT.getHolderOrThrow(ResourceKey.create(Registries.CUSTOM_STAT, $keyExpr))",
                    args[1]
                )
            } else {
                null
            }
        } else {
            null
        }
    }
    if (maskJavaCommentsAndLiterals(result).contains("ResourceKey.create(Registries.CUSTOM_STAT")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.resources.ResourceKey")
        result = addExecutableImportIfMissing(result, "net.minecraft.core.registries.BuiltInRegistries")
        result = addExecutableImportIfMissing(result, "net.minecraft.core.registries.Registries")
    }

    if (result != source && maskJavaCommentsAndLiterals(result).contains("AdvancementHolder")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.advancements.AdvancementHolder")
        val withoutAdvancementImport = removeExecutableImport(result, "net.minecraft.advancements.Advancement")
        if (!Regex("""\bAdvancement\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutAdvancementImport))) {
            result = withoutAdvancementImport
        }
    }
    val executableResult = maskJavaCommentsAndLiterals(result)
    if (executableResult.contains("Criterion<")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.advancements.Criterion")
    }
    if (executableResult.contains("CriteriaTriggers.")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.advancements.CriteriaTriggers")
    }
    return result
}

internal fun StructuralRefactorPass.migrateEntityPredicateBuilderReceiverCall(receiver: String, methodName: String, args: List<String>): String? {
    val arg = args.singleOrNull()?.trim() ?: return null
    val migratedArg = removeTrailingBuildCall(arg, "EntityPredicate.Builder")
    if (migratedArg == arg) return null
    return "$receiver.$methodName($migratedArg)"
}

internal fun StructuralRefactorPass.migrateLegacyInventoryChangeCriterionReturnTypes(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("InventoryChangeTrigger.TriggerInstance") ||
        !executableCode.contains("CriteriaTriggers.INVENTORY_CHANGED.createCriterion(")) {
        return source
    }

    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        val methodText = result.substring(method.range)
        val executableMethodText = maskJavaCommentsAndLiterals(methodText)
        if (!executableMethodText.contains("InventoryChangeTrigger.TriggerInstance") ||
            !executableMethodText.contains("CriteriaTriggers.INVENTORY_CHANGED.createCriterion(")) {
            continue
        }
        val migratedMethod = replaceExecutableRegex(
            methodText,
            Regex("""\b((?:public|protected|private)\s+(?:static\s+)?)InventoryChangeTrigger\.TriggerInstance(\s+${Regex.escape(method.name)}\s*\()""")
        ) { match -> "${match.groupValues[1]}Criterion<InventoryChangeTrigger.TriggerInstance>${match.groupValues[2]}" }
        if (migratedMethod != methodText) {
            result = result.replaceRange(method.range, migratedMethod)
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyItemUsedOnLocationTriggerConstructorReturns(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new ItemUsedOnLocationTrigger.TriggerInstance(")) return source
    var changed = false
    val returnPattern = Regex("""(?m)^([ \t]*)return\s+new\s+ItemUsedOnLocationTrigger\.TriggerInstance\((.*)\)\s*;""")
    var result = source
    returnPattern.findAll(executableCode).toList().asReversed().forEach { match ->
        val argsRange = match.groups[2]?.range ?: return@forEach
        val args = splitTopLevelJavaArgs(source.substring(argsRange.first, argsRange.last + 1))
        val trigger = if (args.size == 3) legacyCriteriaTriggerExpression(args[0]) else null
        if (trigger != null) {
            changed = true
            val indentRange = match.groups[1]?.range
            val indent = indentRange?.let { source.substring(it.first, it.last + 1) }.orEmpty()
            val replacement = "${indent}return $trigger.createCriterion(new ItemUsedOnLocationTrigger.TriggerInstance(${legacyOptionalContextAwarePredicate(args[1])}, ${legacyOptionalContextAwarePredicate(args[2])}));"
            result = result.substring(0, match.range.first) + replacement + result.substring(match.range.last + 1)
        }
    }
    if (!changed) return source
    val signaturePattern = Regex("""(?m)^([ \t]*(?:private|protected|public)\s+(?:static\s+)?)ItemUsedOnLocationTrigger\.TriggerInstance(\s+[A-Za-z_$][\w$]*\s*\()""")
    signaturePattern.findAll(maskJavaCommentsAndLiterals(result)).toList().asReversed().forEach { match ->
        val prefixRange = match.groups[1]?.range ?: return@forEach
        val suffixRange = match.groups[2]?.range ?: return@forEach
        val replacement = result.substring(prefixRange.first, prefixRange.last + 1) +
            "Criterion<ItemUsedOnLocationTrigger.TriggerInstance>" +
            result.substring(suffixRange.first, suffixRange.last + 1)
        result = result.substring(0, match.range.first) + replacement + result.substring(match.range.last + 1)
    }
    return result
}

internal fun StructuralRefactorPass.legacyCriteriaTriggerExpression(expression: String): String? {
    val trimmed = expression.trim()
    return Regex("""^((?:net\.minecraft\.advancements\.)?CriteriaTriggers\.[A-Z0-9_]+)(?:\.getId\(\))?$""")
        .matchEntire(trimmed)
        ?.groupValues
        ?.get(1)
}

internal fun StructuralRefactorPass.legacyOptionalContextAwarePredicate(expression: String): String {
    val trimmed = expression.trim()
    return when {
        trimmed == "ContextAwarePredicate.ANY" -> "java.util.Optional.empty()"
        trimmed.startsWith("java.util.Optional.") || trimmed.startsWith("Optional.") -> trimmed
        else -> "java.util.Optional.of($trimmed)"
    }
}

internal fun StructuralRefactorPass.legacyItemPredicateArrayListExpression(expression: String): String {
    val trimmed = expression.trim()
    if (Regex("""new\s+ItemPredicate\s*\[\s*0\s*]""").matches(trimmed)) {
        return "java.util.List.of()"
    }
    Regex("""(?s)new\s+ItemPredicate\s*\[\s*]\s*\{(.*)}""")
        .matchEntire(trimmed)
        ?.let { match ->
            val items = match.groupValues[1].trim()
            return if (items.isBlank()) "java.util.List.of()" else "java.util.List.of($items)"
        }
    return "java.util.Arrays.asList($trimmed)"
}

internal fun StructuralRefactorPass.migrateCustomDataPredicateFromStackTag(expression: String): String? {
    val expectToken = ".expect("
    val expectIndex = expression.indexOf(expectToken)
    if (expectIndex < 0) return null
    if (!expression.substring(0, expectIndex).trim().endsWith("DataComponentPredicate.builder()")) return null
    val expectOpen = expectIndex + expectToken.length - 1
    val expectClose = findMatchingParen(expression, expectOpen)
    if (expectClose < 0) return null
    if (expression.substring(expectClose + 1).trim() != ".build()") return null
    val expectArgs = splitTopLevelJavaArgs(expression.substring(expectOpen + 1, expectClose))
    if (expectArgs.size != 2) return null
    if (!expectArgs[0].trim().endsWith("DataComponents.CUSTOM_DATA")) return null

    val value = expectArgs[1].trim()
    val ofToken = "CustomData.of("
    val ofIndex = value.indexOf(ofToken)
    if (ofIndex < 0) return null
    val ofOpen = ofIndex + ofToken.length - 1
    val ofClose = findMatchingParen(value, ofOpen)
    if (ofClose < 0 || value.substring(ofClose + 1).trim().isNotEmpty()) return null
    val tagExpression = value.substring(ofOpen + 1, ofClose).trim()
    if (!tagExpression.endsWith(".getTag()")) return null
    val stackExpression = tagExpression.removeSuffix(".getTag()").trim()
    if (stackExpression.isBlank()) return null
    return "DataComponentPredicate.allOf($stackExpression.getComponents())"
}

internal fun StructuralRefactorPass.migrateLegacyCriterionTriggerInstanceCallSites(
    source: String,
    criterionFactoryHints: CriterionInstanceFactoryHints
): String {
    if (!source.contains(".INSTANCE.") || criterionFactoryHints.triggerHoldersByTriggerClass.isEmpty()) {
        return source
    }
    var result = source
    criterionFactoryHints.triggerHoldersByTriggerClass.forEach { (triggerClass, holderExpression) ->
        val simpleTrigger = triggerClass.substringAfterLast('.')
        result = Regex("""\b${Regex.escape(simpleTrigger)}\.INSTANCE\.""")
            .replace(result, "$holderExpression.get().")
        result = Regex("""\b${Regex.escape(triggerClass)}\.INSTANCE\.""")
            .replace(result, "$holderExpression.get().")
    }
    return result
}

internal fun StructuralRefactorPass.removeTrailingBuildCall(expression: String, requiredBuilder: String): String {
    val trimmed = expression.trim()
    if (!trimmed.endsWith(".build()") || !trimmed.contains(requiredBuilder)) return expression
    return trimmed.removeSuffix(".build()")
}

internal fun StructuralRefactorPass.migrateLegacyItemStackHurtSource(source: String): String {
    if (!source.contains(".hurt(") || !source.contains(", null")) return source

    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    var needsServerLevel = false

    while (cursor < source.length) {
        val tokenIndex = source.indexOf(".hurt(", cursor)
        if (tokenIndex < 0) break
        val receiverStart = findDottedReceiverStart(source, tokenIndex)
        val receiver = source.substring(receiverStart, tokenIndex).trim()
        val openParen = tokenIndex + ".hurt".length
        val closeParen = findMatchingParen(source, openParen)
        if (receiver.isBlank() || closeParen < 0) {
            migrated.append(source, cursor, tokenIndex + ".hurt(".length)
            cursor = tokenIndex + ".hurt(".length
            continue
        }

        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        val hurtPlan = if (args.size == 3 && args[2].trim() == "null") {
            legacyItemStackHurtPlan(args[1], source, receiverStart)
        } else {
            null
        }
        if (hurtPlan == null) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }

        val ifStart = findIfStatementStartForCondition(source, receiverStart)
        if (ifStart != null) {
            val conditionOpen = source.indexOf('(', ifStart)
            val conditionClose = if (conditionOpen >= 0) findMatchingParen(source, conditionOpen) else -1
            val blockOpen = nextNonWhitespaceIndex(source, conditionClose + 1)
            if (conditionOpen >= 0 &&
                receiverStart > conditionOpen &&
                closeParen < conditionClose &&
                blockOpen >= 0 &&
                source[blockOpen] == '{'
            ) {
                val blockClose = findMatchingBrace(source, blockOpen)
                if (blockClose > blockOpen) {
                    val condition = source.substring(conditionOpen + 1, conditionClose).trim()
                    val hurtCall = source.substring(receiverStart, closeParen + 1)
                    val conditionParts = legacyItemStackHurtConditionParts(condition, hurtCall)
                    if (conditionParts == null) {
                        migrated.append(source, cursor, closeParen + 1)
                        cursor = closeParen + 1
                        continue
                    }
                    val lineStart = source.lastIndexOf('\n', ifStart).let { if (it < 0) 0 else it + 1 }
                    val indent = source.substring(lineStart, ifStart).takeWhile { it == ' ' || it == '\t' }
                    val amount = args[0].trim()
                    val body = source.substring(blockOpen + 1, blockClose).trim()
                    val replacement = if (hurtPlan.serverLevel == null) {
                        legacyItemStackManualHurtReplacement(
                            indent = indent,
                            condition = conditionParts.outerCondition,
                            callbackCondition = conditionParts.callbackCondition,
                            receiver = receiver,
                            amount = amount,
                            callbackBody = body
                        )
                    } else {
                        legacyItemStackHurtReplacement(
                            indent = indent,
                            condition = conditionParts.outerCondition,
                            callbackCondition = conditionParts.callbackCondition,
                            receiver = receiver,
                            amount = amount,
                            serverLevel = hurtPlan.serverLevel,
                            callbackBody = body
                        )
                    }
                    migrated.append(source, cursor, ifStart)
                    migrated.append(replacement)
                    cursor = blockClose + 1
                    changed = true
                    needsServerLevel = needsServerLevel || hurtPlan.serverLevel != null
                    continue
                }
            }
        }

        migrated.append(source, cursor, receiverStart)
        if (hurtPlan.serverLevel == null) {
            migrated.append("${receiver}.setDamageValue(${receiver}.getDamageValue() + ${args[0].trim()})")
        } else {
            migrated.append("${receiver}.hurtAndBreak(${args[0].trim()}, ${hurtPlan.serverLevel}, null, item -> {})")
        }
        cursor = closeParen + 1
        changed = true
        needsServerLevel = needsServerLevel || hurtPlan.serverLevel != null
    }

    if (!changed) return source
    migrated.append(source, cursor, source.length)
    var result = migrated.toString()
    if (needsServerLevel) {
        result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    }
    return result
}

internal data class LegacyItemStackHurtPlan(val serverLevel: String?)

internal data class LegacyItemStackHurtCondition(
    val outerCondition: String?,
    val callbackCondition: String?
)

internal fun StructuralRefactorPass.legacyItemStackHurtPlan(randomExpression: String, source: String, callOffset: Int): LegacyItemStackHurtPlan? {
    val random = randomExpression.trim()
    val levelExpression = when {
        random.endsWith(".getRandom()") -> random.removeSuffix(".getRandom()").trim()
        random.endsWith(".random") -> random.removeSuffix(".random").trim()
        else -> return null
    }
    return LegacyItemStackHurtPlan(
        sourceProvenServerLevelExpressionForLegacyItemStackHurt(levelExpression, source, callOffset)
    )
}

internal fun StructuralRefactorPass.sourceProvenServerLevelExpressionForLegacyItemStackHurt(
    levelExpression: String,
    source: String,
    callOffset: Int
): String? {
    val level = levelExpression.trim()
    if (!Regex("""[A-Za-z_$][\w$]*""").matches(level)) return null

    val parameterPattern = Regex(
        """(?:final\s+)?(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*(?:net\.minecraft\.server\.level\.)?ServerLevel\s+${Regex.escape(level)}$"""
    )
    if (currentMethodParametersBeforeOffset(source, callOffset).any { parameter ->
            parameterPattern.matches(parameter.trim().replace(Regex("""\s+"""), " "))
        }
    ) {
        return level
    }

    val methodRange = enclosingMethodRange(source, callOffset) ?: return null
    val methodPrefix = source.substring(methodRange.first, callOffset)
    return level.takeIf {
        Regex("""\b(?:final\s+)?(?:net\.minecraft\.server\.level\.)?ServerLevel\s+${Regex.escape(level)}\b""")
            .containsMatchIn(methodPrefix)
    }
}

internal fun StructuralRefactorPass.legacyItemStackHurtConditionParts(condition: String, hurtCall: String): LegacyItemStackHurtCondition? {
    val callIndex = condition.indexOf(hurtCall)
    if (callIndex < 0) return null
    val before = condition.substring(0, callIndex).trim()
    val after = condition.substring(callIndex + hurtCall.length).trim()
    return when {
        before.isBlank() && after.isBlank() -> LegacyItemStackHurtCondition(null, null)
        before.endsWith("&&") && after.isBlank() ->
            LegacyItemStackHurtCondition(before.removeSuffix("&&").trim().ifBlank { null }, null)
        before.isBlank() && after.startsWith("&&") ->
            LegacyItemStackHurtCondition(null, after.removePrefix("&&").trim().ifBlank { null })
        before.endsWith("&&") && after.startsWith("&&") ->
            LegacyItemStackHurtCondition(
                before.removeSuffix("&&").trim().ifBlank { null },
                after.removePrefix("&&").trim().ifBlank { null }
            )
        else -> null
    }
}

internal fun StructuralRefactorPass.findIfStatementStartForCondition(source: String, expressionStart: Int): Int? {
    val lineStart = source.lastIndexOf('\n', expressionStart).let { if (it < 0) 0 else it + 1 }
    val prefix = source.substring(lineStart, expressionStart)
    val offset = prefix.lastIndexOf("if")
    if (offset < 0) return null
    val candidate = lineStart + offset
    if (candidate > 0 && (source[candidate - 1].isLetterOrDigit() || source[candidate - 1] == '_')) return null
    val after = candidate + 2
    return if (after < source.length && source.substring(after, minOf(source.length, after + 8)).trimStart().startsWith("(")) {
        candidate
    } else {
        null
    }
}

internal fun StructuralRefactorPass.nextNonWhitespaceIndex(source: String, start: Int): Int {
    var index = start
    while (index < source.length && source[index].isWhitespace()) index++
    return if (index < source.length) index else -1
}

internal fun StructuralRefactorPass.legacyItemStackHurtReplacement(
    indent: String,
    condition: String?,
    callbackCondition: String?,
    receiver: String,
    amount: String,
    serverLevel: String,
    callbackBody: String
): String {
    val callbackBodyLines = if (callbackBody.isBlank()) {
        ""
    } else {
        callbackBody.lines()
            .joinToString("\n") { line -> if (line.isBlank()) line else "$indent        ${line.trim()}" }
    }
    val guardedBody = when {
        callbackBodyLines.isBlank() -> ""
        callbackCondition.isNullOrBlank() -> callbackBodyLines
        else -> """$indent        if ($callbackCondition) {
$callbackBodyLines
$indent        }"""
    }
    val callback = if (guardedBody.isBlank()) {
        "$receiver.hurtAndBreak($amount, $serverLevel, null, item -> {});"
    } else {
        """$receiver.hurtAndBreak($amount, $serverLevel, null, item -> {
$guardedBody
$indent    });"""
    }
    return if (condition == null) {
        "$indent$callback"
    } else {
        """${indent}if ($condition) {
$indent    $callback
$indent}"""
    }
}

internal fun StructuralRefactorPass.legacyItemStackManualHurtReplacement(
    indent: String,
    condition: String?,
    callbackCondition: String?,
    receiver: String,
    amount: String,
    callbackBody: String
): String {
    val body = if (callbackBody.isBlank()) {
        ""
    } else {
        callbackBody.lines()
            .joinToString("\n") { line -> if (line.isBlank()) line else "$indent            ${line.trim()}" }
    }
    val breakBody = when {
        body.isBlank() -> ""
        callbackCondition.isNullOrBlank() -> body
        else -> """$indent            if ($callbackCondition) {
$body
$indent            }"""
    }
    val damage = "$receiver.setDamageValue($receiver.getDamageValue() + $amount);"
    val breakCheck = if (breakBody.isBlank()) {
        ""
    } else {
        """
$indent    if ($receiver.getDamageValue() >= $receiver.getMaxDamage()) {
$breakBody
$indent    }""".trimEnd()
    }
    val migrated = if (breakCheck.isBlank()) {
        "$indent$damage"
    } else {
        "$indent$damage\n$breakCheck"
    }
    return if (condition == null) {
        migrated
    } else {
        """${indent}if ($condition) {
$migrated
$indent}"""
    }
}

internal fun StructuralRefactorPass.migrateLegacyBlockSourceCoordinateAccessors(source: String): String {
    if (!source.contains("BlockSource") ||
        (!source.contains(".x()") &&
            !source.contains(".y()") &&
            !source.contains(".z()") &&
            !source.contains(".getPos()") &&
            !source.contains(".getBlockState()") &&
            !source.contains(".getLevel()") &&
            !source.contains("getEntity()"))
    ) {
        return source
    }
    val blockSourceVars = Regex("""\bBlockSource\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (blockSourceVars.isEmpty()) return source
    var result = source
    for (variable in blockSourceVars) {
        result = Regex("""\b${Regex.escape(variable)}\.(x|y|z)\(\)""")
            .replace(result) { match -> "$variable.center().${match.groupValues[1]}()" }
        result = Regex("""\b${Regex.escape(variable)}\.getPos\(\)""")
            .replace(result, "$variable.pos()")
        result = Regex("""\b${Regex.escape(variable)}\.getBlockState\(\)""")
            .replace(result, "$variable.state()")
        result = Regex("""\b${Regex.escape(variable)}\.getLevel\(\)""")
            .replace(result, "$variable.level()")
        result = Regex("""\b${Regex.escape(variable)}\s*\.\s*(?:<[^>]+>\s*)?getEntity\(\)""")
            .replace(result, "$variable.blockEntity()")
        result = Regex("""\b${Regex.escape(variable)}\.blockEntity\(\)\.addItem\(([^;\r\n]+)\)\s*<\s*0""")
            .replace(result) { match -> "!$variable.blockEntity().insertItem(${match.groupValues[1].trim()}).isEmpty()" }
        result = Regex("""\b${Regex.escape(variable)}\.blockEntity\(\)\.addItem\(""")
            .replace(result, "$variable.blockEntity().insertItem(")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyProjectileDispenseBehaviorSource(source: String): String {
    if (!source.contains("ProjectileDispenseBehavior")) return source
    var result = source
    var changed = false
    var synthesizedExecute = false

    var cursor = 0
    val anonymousPattern = Regex("""new\s+ProjectileDispenseBehavior\s*\(\s*\)\s*\{""")
    while (true) {
        val match = anonymousPattern.find(result, cursor) ?: break
        val openBrace = match.range.last
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val migratedBody = migrateLegacyProjectileDispenseBody(body)
        synthesizedExecute = synthesizedExecute || migratedBody.contains("Projectile projectile = this.getProjectile(") && !body.contains("ItemStack execute(")
        result = result.substring(0, match.range.first) +
            "new DefaultDispenseItemBehavior() {" +
            migratedBody +
            result.substring(closeBrace)
        cursor = match.range.first + "new DefaultDispenseItemBehavior() {".length + migratedBody.length + 1
        changed = true
    }

    if (Regex("""extends\s+ProjectileDispenseBehavior\b""").containsMatchIn(result) &&
        (result.contains("getProjectile(") || result.contains("getPower()") || result.contains("getUncertainty()"))
    ) {
        result = Regex("""extends\s+ProjectileDispenseBehavior\b""").replace(result, "extends DefaultDispenseItemBehavior")
        val migrated = migrateLegacyProjectileDispenseBody(result)
        synthesizedExecute = synthesizedExecute || migrated.contains("Projectile projectile = this.getProjectile(") && !result.contains("ItemStack execute(")
        result = migrated
        changed = true
    }

    if (!changed) return source
    if (synthesizedExecute) {
        result = addImportIfMissing(result, "net.minecraft.core.Direction")
        result = addImportIfMissing(result, "net.minecraft.core.dispenser.BlockSource")
        result = addImportIfMissing(result, "net.minecraft.world.level.block.DispenserBlock")
    }
    result = addImportIfMissing(result, "net.minecraft.core.dispenser.DefaultDispenseItemBehavior")
    val withoutProjectileImport = removeImport(result, "net.minecraft.core.dispenser.ProjectileDispenseBehavior")
    if (!withoutProjectileImport.contains("ProjectileDispenseBehavior")) {
        result = withoutProjectileImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyProjectileDispenseBody(source: String): String {
    val hasPowerMethod = legacyProjectileHookMethodExists(source, "getPower")
    val hasUncertaintyMethod = legacyProjectileHookMethodExists(source, "getUncertainty")
    val powerArgument = if (hasPowerMethod) "this.getPower()" else "1.1F"
    val uncertaintyArgument = if (hasUncertaintyMethod) "this.getUncertainty()" else "6.0F"
    var result = source
    if (!hasPowerMethod) {
        result = result.replace("this.getPower()", powerArgument)
    }
    if (!hasUncertaintyMethod) {
        result = result.replace("this.getUncertainty()", uncertaintyArgument)
    }
    if (!legacyProjectileExecuteMethodExists(result)) {
        legacyProjectileGetProjectileMethod(result)?.let { method ->
            val execute = buildLegacyProjectileDispenseExecute(
                indent = leadingIndentAt(result, method.start),
                level = method.level,
                position = method.position,
                stack = method.stack,
                powerArgument = powerArgument,
                uncertaintyArgument = uncertaintyArgument
            )
            result = result.substring(0, method.start) + execute + "\n" + result.substring(method.start)
        }
    }
    return removeOverrideFromLegacyProjectileHooks(result)
}

internal data class LegacyProjectileMethod(val start: Int, val level: String, val position: String, val stack: String)

internal fun StructuralRefactorPass.legacyProjectileGetProjectileMethod(source: String): LegacyProjectileMethod? {
    val pattern = Regex(
        """(?m)(?:[ \t]*@[\w.]+(?:\([^)]*\))?\s*\r?\n)*[ \t]*(?:public|protected|private)\s+Projectile\s+getProjectile\s*\(\s*Level\s+([A-Za-z_$][\w$]*)\s*,\s*Position\s+([A-Za-z_$][\w$]*)\s*,\s*ItemStack\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    val match = pattern.find(source) ?: return null
    return LegacyProjectileMethod(
        start = match.range.first,
        level = match.groupValues[1],
        position = match.groupValues[2],
        stack = match.groupValues[3]
    )
}

internal fun StructuralRefactorPass.legacyProjectileExecuteMethodExists(source: String): Boolean =
    Regex("""\bItemStack\s+execute\s*\(\s*BlockSource\s+[A-Za-z_$][\w$]*\s*,\s*ItemStack\s+[A-Za-z_$][\w$]*\s*\)""")
        .containsMatchIn(source)

internal fun StructuralRefactorPass.legacyProjectileHookMethodExists(source: String, methodName: String): Boolean =
    Regex("""\b(?:public|protected|private)\s+(?:float|Projectile)\s+${Regex.escape(methodName)}\s*\(""")
        .containsMatchIn(source)

internal fun StructuralRefactorPass.buildLegacyProjectileDispenseExecute(
    indent: String,
    level: String,
    position: String,
    stack: String,
    powerArgument: String,
    uncertaintyArgument: String
): String {
    val inner = "$indent    "
    return """
${indent}@Override
${indent}protected ItemStack execute(BlockSource source, ItemStack $stack) {
${inner}Level $level = source.level();
${inner}Direction direction = source.state().getValue(DispenserBlock.FACING);
${inner}Position $position = DispenserBlock.getDispensePosition(source);
${inner}Projectile projectile = this.getProjectile($level, $position, $stack);
${inner}projectile.shoot(direction.getStepX(), direction.getStepY(), direction.getStepZ(), $powerArgument, $uncertaintyArgument);
${inner}$level.addFreshEntity(projectile);
${inner}$stack.shrink(1);
${inner}return $stack;
${indent}}
""".trim('\n')
}

internal fun StructuralRefactorPass.removeOverrideFromLegacyProjectileHooks(source: String): String =
    Regex(
        """(?m)^[ \t]*@Override\s*\r?\n(?=[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s*\r?\n)*[ \t]*(?:public|protected|private)\s+(?:float|Projectile)\s+(?:getProjectile|getPower|getUncertainty)\s*\()"""
    ).replace(source, "")

internal fun StructuralRefactorPass.leadingIndentAt(source: String, offset: Int): String {
    val lineStart = source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    return source.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
}

internal fun StructuralRefactorPass.migrateLegacyAbstractArrowPickupItemSource(source: String): String {
    if (!source.contains("extends AbstractArrow") || !source.contains("getPickupItem()")) return source
    return Regex("""protected\s+ItemStack\s+getPickupItem\s*\(\s*\)""")
        .replace(source, "protected ItemStack getDefaultPickupItem()")
}

internal fun StructuralRefactorPass.migrateLegacyTooltipPartHiding(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".hideTooltipPart(")) return source

    var changed = false
    var needsUnit = false
    var needsAttributeModifiers = false
    var result = rewriteExecutableJavaCall(source, "hideTooltipPart") { receiver, args ->
        if (args.size != 1) return@rewriteExecutableJavaCall null
        when (args[0].trim()) {
            "ItemStack.TooltipPart.ADDITIONAL", "TooltipPart.ADDITIONAL" -> {
                changed = true
                needsUnit = true
                "$receiver.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE)"
            }
            "ItemStack.TooltipPart.MODIFIERS", "TooltipPart.MODIFIERS" -> {
                changed = true
                needsAttributeModifiers = true
                "$receiver.set(DataComponents.ATTRIBUTE_MODIFIERS, " +
                    "$receiver.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, " +
                    "ItemAttributeModifiers.EMPTY).withTooltip(false))"
            }
            else -> null
        }
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    if (needsUnit) {
        result = addImportIfMissing(result, "net.minecraft.util.Unit")
    }
    if (needsAttributeModifiers) {
        result = addImportIfMissing(
            result,
            "net.minecraft.world.item.component.ItemAttributeModifiers"
        )
    }
    if (!maskJavaCommentsAndLiterals(result).contains("TooltipPart.")) {
        result = removeImport(
            result,
            "net.minecraft.world.item.ItemStack.TooltipPart"
        )
    }
    return result
}

internal fun StructuralRefactorPass.parseJavaConstructorParameters(parameters: String): List<JavaParameter> =
    splitTopLevelJavaArgs(parameters).mapNotNull { parameter ->
        val normalized = parameter
            .replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "")
            .replace(Regex("""\bfinal\s+"""), "")
            .trim()
        val match = Regex("""^(.+?)\s+([A-Za-z_$][\w$]*)$""").find(normalized) ?: return@mapNotNull null
        JavaParameter(match.groupValues[1].trim(), match.groupValues[2])
    }

internal fun JavaParameter.simpleTypeName(): String =
    type.substringBefore('<').substringAfterLast('.').trim()

internal fun JavaParameter.isLevelParameter(): Boolean =
    simpleTypeName() == "Level"

internal fun JavaParameter.isLivingEntityParameter(): Boolean =
    simpleTypeName() == "LivingEntity"

internal fun JavaParameter.isItemStackParameter(): Boolean =
    simpleTypeName() == "ItemStack"

internal fun JavaParameter.isItemSupplierParameter(): Boolean =
    simpleTypeName() == "Supplier" && Regex("""\bItem\b""").containsMatchIn(type)

internal fun StructuralRefactorPass.migrateLegacyAbstractArrowConstructors(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("super(") || !source.contains("AbstractArrow")) return source

    var result = source
    var cursor = 0
    var changed = false
    var needsItemStack = false
    val constructorPattern = Regex(
        """(?m)^[ \t]*(?:public|protected|private)\s+([A-Za-z_$][\w$]*)\s*\(([^;{}]*)\)\s*(?:throws\s+[^{]+)?\{"""
    )
    while (true) {
        val match = constructorPattern.find(result, cursor) ?: break
        val classDeclaration = enclosingJavaClassDeclaration(result, match.range.first)
        if (classDeclaration?.name != match.groupValues[1] ||
            !javaClassExtendsAny(classDeclaration, setOf("AbstractArrow"), javaInheritanceIndex)) {
            cursor = match.range.last + 1
            continue
        }

        val parameters = parseJavaConstructorParameters(match.groupValues[2])
        val constructorOpenBrace = result.indexOf('{', match.range.last)
        val constructorCloseBrace = if (constructorOpenBrace >= 0) findMatchingBrace(result, constructorOpenBrace) else -1
        if (constructorOpenBrace < 0 || constructorCloseBrace < 0) {
            cursor = match.range.last + 1
            continue
        }

        val superIndex = result.indexOf("super(", constructorOpenBrace + 1)
        if (superIndex < 0 || superIndex > constructorCloseBrace) {
            cursor = constructorCloseBrace + 1
            continue
        }
        val superOpenParen = superIndex + "super".length
        val superCloseParen = findMatchingParen(result, superOpenParen)
        if (superCloseParen < 0 || superCloseParen > constructorCloseBrace) {
            cursor = constructorCloseBrace + 1
            continue
        }
        val semicolonIndex = nextNonWhitespaceIndex(result, superCloseParen + 1)
        if (semicolonIndex < 0 || result[semicolonIndex] != ';') {
            cursor = superCloseParen + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(superOpenParen + 1, superCloseParen))
        if (args.size != 3 && args.size != 5) {
            cursor = semicolonIndex + 1
            continue
        }

        val pickupSupplier = parameters.firstOrNull { it.name !in args && it.isItemSupplierParameter() }
        val pickupStack = parameters.firstOrNull { it.name !in args && it.isItemStackParameter() }
        val classBody = classDeclaration?.let {
            result.substring(it.bodyRange.first + 1, it.bodyRange.last)
        }.orEmpty()
        val defaultPickupExpression = noArgConstantReturnExpression(classBody, "getDefaultPickupItem")
        val pickupExpression = when {
            pickupSupplier != null -> {
                needsItemStack = true
                "new ItemStack(${pickupSupplier.name}.get())"
            }
            pickupStack != null -> pickupStack.name
            Regex("""\bgetDefaultPickupItem\s*\(\s*\)""").containsMatchIn(classBody) && defaultPickupExpression != null -> defaultPickupExpression
            Regex("""\bgetDefaultPickupItem\s*\(\s*\)""").containsMatchIn(classBody) -> {
                cursor = semicolonIndex + 1
                continue
            }
            else -> {
                cursor = semicolonIndex + 1
                continue
            }
        }
        val weaponStack = parameters.firstOrNull {
            it.name != pickupStack?.name &&
                it.name != pickupSupplier?.name &&
                it.isItemStackParameter() &&
                Regex("""(?i)(weapon|fired)""").containsMatchIn(it.name)
        }?.name ?: "null"

        val replacement = if (args.size == 3) {
            val ownerArg = args[1].trim()
            val levelArg = args[2].trim()
            val ownerParameter = parameters.firstOrNull { it.name == ownerArg && it.isLivingEntityParameter() }
            val levelParameter = parameters.firstOrNull { it.name == levelArg && it.isLevelParameter() }
            if (ownerParameter == null || levelParameter == null) {
                cursor = semicolonIndex + 1
                continue
            }
            "super(${args[0].trim()}, $ownerArg, $levelArg, $pickupExpression, $weaponStack);"
        } else {
            val xArg = args[1].trim()
            val yArg = args[2].trim()
            val zArg = args[3].trim()
            val levelArg = args[4].trim()
            val levelParameter = parameters.firstOrNull { it.name == levelArg && it.isLevelParameter() }
            val coordinateParameters = listOf(xArg, yArg, zArg)
                .map { arg -> parameters.firstOrNull { it.name == arg && simpleJavaTypeName(it.type) == "double" } }
            if (levelParameter == null || coordinateParameters.any { it == null }) {
                cursor = semicolonIndex + 1
                continue
            }
            "super(${args[0].trim()}, $xArg, $yArg, $zArg, $levelArg, $pickupExpression, $weaponStack);"
        }
        result = result.substring(0, superIndex) + replacement + result.substring(semicolonIndex + 1)
        cursor = superIndex + replacement.length
        changed = true
    }

    return if (changed && needsItemStack) addImportIfMissing(result, "net.minecraft.world.item.ItemStack") else result
}

internal fun StructuralRefactorPass.migrateLegacyAbstractArrowKnockbackSource(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("this.getKnockback()") || !source.contains("AbstractArrow")) return source

    var result = source
    var cursor = 0
    val ifPattern = Regex("""if\s*\(\s*this\.getKnockback\(\)\s*>\s*0\s*\)\s*\{""")
    while (true) {
        val match = ifPattern.find(result, cursor) ?: break
        val classDeclaration = enclosingJavaClassDeclaration(result, match.range.first)
        if (!javaClassExtendsAny(classDeclaration, setOf("AbstractArrow"), javaInheritanceIndex)) {
            cursor = match.range.last + 1
            continue
        }
        val openBrace = result.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val block = result.substring(openBrace + 1, closeBrace)
        val livingVariable = Regex("""\b([A-Za-z_$][\w$]*)\.push\s*\(""")
            .find(block)
            ?.groupValues
            ?.get(1)
        val methodRange = enclosingMethodRange(result, match.range.first)
        val methodPrefix = methodRange?.let { result.substring(it.first, match.range.first) }
        val damageSource = methodPrefix?.let(::lastDamageSourceVariable)
        if (livingVariable == null || damageSource == null || !block.contains("this.getKnockback()")) {
            cursor = closeBrace + 1
            continue
        }

        val statementStart = result.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        val indent = result.substring(statementStart, match.range.first).takeWhile { it == ' ' || it == '\t' }
        val replacement = "${indent}this.doKnockback($livingVariable, $damageSource);"
        result = result.substring(0, statementStart) + replacement + result.substring(closeBrace + 1)
        cursor = statementStart + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEntityArmorSlotAccessSource(source: String): String {
    if (!source.contains(".getArmorSlots()")) return source
    val entityVars = Regex("""\bEntity\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (entityVars.isEmpty()) return source
    var changed = false
    var result = source
    for (variable in entityVars) {
        val guarded = source.contains("$variable instanceof LivingEntity") || source.contains("$variable instanceof Player")
        if (!guarded) continue
        val before = result
        result = Regex("""\b${Regex.escape(variable)}\.getArmorSlots\(\)""")
            .replace(result, "((LivingEntity) $variable).getArmorSlots()")
        changed = changed || result != before
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.world.entity.LivingEntity") else source
}

internal fun StructuralRefactorPass.migrateLegacyAbstractHurtingProjectilePowerFields(source: String): String {
    if (!source.contains(".xPower") && !source.contains(".yPower") && !source.contains(".zPower")) return source
    var result = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.xPower\s*=\s*([A-Za-z_$][\w$]*)\.x\(\)\s*\*\s*([^;\r\n]+);\s*\r?\n\1\2\.yPower\s*=\s*\3\.y\(\)\s*\*\s*\4;\s*\r?\n\1\2\.zPower\s*=\s*\3\.z\(\)\s*\*\s*\4;"""
    ).replace(source) { match ->
        val indent = match.groupValues[1]
        val projectile = match.groupValues[2]
        val scale = match.groupValues[4].trim()
        "${indent}$projectile.accelerationPower = $scale;"
    }
    result = migrateAbstractHurtingProjectileCopiedTickPowerReads(result)
    result = migrateAbstractHurtingProjectileScaledPowerTriplets(result)
    return result
}

internal fun StructuralRefactorPass.migrateAbstractHurtingProjectileCopiedTickPowerReads(source: String): String {
    if (!Regex("""\bextends\s+(?:AbstractHurtingProjectile|Fireball|LargeFireball|SmallFireball|DragonFireball)\b""").containsMatchIn(source)) {
        return source
    }
    return Regex("""\b([A-Za-z_$][\w$]*)\.add\(\s*this\.xPower\s*,\s*this\.yPower\s*,\s*this\.zPower\s*\)""")
        .replace(source) { match ->
            val vector = match.groupValues[1]
            "$vector.add($vector.normalize().scale(this.accelerationPower))"
        }
}

internal fun StructuralRefactorPass.migrateAbstractHurtingProjectileScaledPowerTriplets(source: String): String {
    val pattern = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.xPower\s*\*=\s*([^;\r\n]+);\s*\r?\n\1\2\.yPower\s*\*=\s*\3;\s*\r?\n\1\2\.zPower\s*\*=\s*\3;"""
    )
    return pattern.replace(source) { match ->
        val indent = match.groupValues[1]
        val projectile = match.groupValues[2]
        val scale = match.groupValues[3].trim()
        if (!hasMatchingAbstractHurtingProjectileMotionScale(source, match.range.first, projectile, scale)) {
            return@replace match.value
        }
        "${indent}$projectile.accelerationPower *= ${absoluteScaleExpression(scale)};"
    }
}

internal fun StructuralRefactorPass.hasMatchingAbstractHurtingProjectileMotionScale(source: String, beforeOffset: Int, projectile: String, scale: String): Boolean {
    val prefix = source.substring(0, beforeOffset)
    val projectileOwner = Regex("""\b([A-Za-z_$][\w$]*)\s+instanceof\s+AbstractHurtingProjectile\s+${Regex.escape(projectile)}\b""")
        .findAll(prefix)
        .lastOrNull()
        ?.groupValues
        ?.get(1)
        ?: projectile.takeIf { javaDeclaredSimpleTypeSets(source)[it] == setOf("AbstractHurtingProjectile") }
        ?: return false
    val owner = Regex.escape(projectileOwner)
    val escapedScale = Regex.escape(scale)
    return Regex("""\b$owner\.setDeltaMovement\s*\(\s*$owner\.getDeltaMovement\(\)\.scale\s*\(\s*$escapedScale\s*\)\s*\)\s*;""")
        .containsMatchIn(prefix)
}

internal fun StructuralRefactorPass.absoluteScaleExpression(scale: String): String {
    val trimmed = scale.trim()
    val numeric = Regex("""^-\s*(\d+(?:\.\d+)?(?:[dDfF])?)$""").matchEntire(trimmed)
    if (numeric != null) return numeric.groupValues[1]
    val parenthesizedNumeric = Regex("""^\(\s*-\s*(\d+(?:\.\d+)?(?:[dDfF])?)\s*\)$""").matchEntire(trimmed)
    if (parenthesizedNumeric != null) return parenthesizedNumeric.groupValues[1]
    return if (trimmed.startsWith("-")) "Math.abs($trimmed)" else trimmed
}

internal fun StructuralRefactorPass.migrateEntityEffectColorParticleTrailCalls(source: String): String {
    if (!source.contains("ParticleTypes.ENTITY_EFFECT") || !source.contains(".makeTrail(")) return source
    var changed = false
    var result = migrateMethodCalls(source, ".makeTrail") { args ->
        if (args.size == 5 && args[0].trim() == "ParticleTypes.ENTITY_EFFECT") {
            changed = true
            listOf(
                "ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, (float) ${args[1].trim()}, (float) ${args[2].trim()}, (float) ${args[3].trim()})",
                args[4]
            )
        } else {
            args
        }
    }
    if (changed) {
        result = addImportIfMissing(result, "net.minecraft.core.particles.ColorParticleOption")
    }
    return result
}

internal fun StructuralRefactorPass.migrateSynchedEntityDataBufferSource(source: String): String {
    if (!source.contains("SynchedEntityData.DataValue") ||
        !source.contains("FriendlyByteBuf")) {
        return source
    }

    var result = Regex("""\bFriendlyByteBuf\b""").replace(source, "RegistryFriendlyByteBuf")
    result = removeImport(result, "net.minecraft.network.FriendlyByteBuf")
    result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    return result
}

internal fun StructuralRefactorPass.migrateHurtAndBreakEquipmentSlotSource(source: String): String {
    if (!source.contains(".hurtAndBreak(")) {
        return source
    }

    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    var needsEquipmentSlot = false
    var needsServerLevel = false
    val token = ".hurtAndBreak("

    while (cursor < source.length) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + ".hurtAndBreak".length
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        val callback = args.getOrNull(2)?.let(::parseBreakCallback)
        val breakCall = callback?.let { findBreakCallbackCall(it.body, it.parameter) }
        if (args.size == 3 && callback != null) {
            val amount = args[0].trim()
            val entity = args[1].trim()
            if (breakCall != null && breakCall.isOnlyStatement) {
                val slot = normalizeBreakSlotExpression(breakCall.slotExpression) {
                    needsEquipmentSlot = true
                }
                migrated.append(source, cursor, tokenIndex)
                migrated.append(".hurtAndBreak($amount, $entity, $slot)")
                cursor = closeParen + 1
                changed = true
                continue
            }

            if (breakCall == null && callbackReferencesParameter(callback.body, callback.parameter)) {
                migrated.append(source, cursor, closeParen + 1)
                cursor = closeParen + 1
                continue
            }

            val statementStart = source.lastIndexOf('\n', tokenIndex).let { if (it < 0) 0 else it + 1 }
            val prefix = source.substring(statementStart, tokenIndex)
            val indent = prefix.takeWhile { it == ' ' || it == '\t' }
            val receiver = prefix.substring(indent.length).trim()
            val afterParen = source.substring(closeParen + 1)
            val semicolonOffset = afterParen.indexOf(';').takeIf {
                it >= 0 && afterParen.substring(0, it).isBlank()
            }
            if (receiver.isNotEmpty() &&
                prefix.substring(indent.length).trim() == receiver &&
                semicolonOffset != null) {
                val callbackBody = if (breakCall != null) {
                    val slot = normalizeBreakSlotExpression(breakCall.slotExpression) {
                        needsEquipmentSlot = true
                    }
                    migrateBreakCallbackBody(callback.body, callback.parameter, entity, slot, "brokenItem") {
                        needsEquipmentSlot = true
                    }
                } else {
                    callback.body
                }.let(::ensureCallbackBlockStatements)
                val indentedBody = callbackBody
                    .lines()
                    .map { line -> if (line.isBlank()) line else "$indent        ${line.trim()}" }
                    .joinToString("\n")
                migrated.append(source, cursor, statementStart)
                migrated.append(
                    """${indent}if ($entity.level() instanceof ServerLevel serverLevel) {
${indent}    $receiver.hurtAndBreak($amount, serverLevel, $entity, brokenItem -> {
$indentedBody
${indent}    });
${indent}}"""
                )
                cursor = closeParen + 1 + semicolonOffset + 1
                needsServerLevel = true
                changed = true
                continue
            }
        }

        migrated.append(source, cursor, closeParen + 1)
        cursor = closeParen + 1
    }

    if (!changed) return source
    migrated.append(source, cursor, source.length)
    var result = migrated.toString()
    if (needsEquipmentSlot) {
        result = addImportIfMissing(result, "net.minecraft.world.entity.EquipmentSlot")
    }
    if (needsServerLevel) {
        result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLivingEntityGetSlotForHandEquipmentSlotSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getSlotForHand(") || !executableCode.contains("EquipmentSlot")) return source
    val equipmentSlotVariables = Regex("""\bEquipmentSlot\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    if (equipmentSlotVariables.isEmpty()) return source

    return replaceExecutableRegex(
        source,
        Regex("""\b(?:net\.minecraft\.world\.entity\.)?LivingEntity\.getSlotForHand\s*\(\s*([A-Za-z_$][\w$]*)\s*\)""")
    ) { match ->
        val variable = match.groupValues[1]
        if (variable in equipmentSlotVariables) variable else match.value
    }
}

internal data class BreakCallback(val parameter: String, val body: String)

internal data class BreakCallbackCall(val slotExpression: String, val isOnlyStatement: Boolean)

internal fun StructuralRefactorPass.callbackReferencesParameter(body: String, parameter: String): Boolean =
    Regex("""\b${Regex.escape(parameter)}\b""").containsMatchIn(body)

internal fun StructuralRefactorPass.ensureCallbackBlockStatements(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || trimmed.endsWith(";") || trimmed.endsWith("}")) return trimmed
    return "$trimmed;"
}

internal fun StructuralRefactorPass.parseBreakCallback(lambda: String): BreakCallback? {
    val match = Regex("""(?s)^\s*\(?\s*([A-Za-z_$][\w$]*)\s*\)?\s*->\s*(.*?)\s*$""")
        .find(lambda)
        ?: return null
    val parameter = match.groupValues[1]
    var body = match.groupValues[2].trim()
    if (body.startsWith("{") && body.endsWith("}")) {
        body = body.substring(1, body.length - 1).trim()
    }
    return BreakCallback(parameter, body)
}

internal fun StructuralRefactorPass.findBreakCallbackCall(body: String, parameter: String): BreakCallbackCall? {
    val receivers = listOf("onEquippedItemBroken", "broadcastBreakEvent")
    for (method in receivers) {
        val token = "$parameter.$method("
        val index = body.indexOf(token)
        if (index < 0) continue
        val openParen = index + token.length - 1
        val closeParen = findMatchingParen(body, openParen)
        if (closeParen < 0) continue
        val slotExpression = body.substring(openParen + 1, closeParen).trim()
        val before = body.substring(0, index).trim()
        val after = body.substring(closeParen + 1).trim().removePrefix(";").trim()
        return BreakCallbackCall(slotExpression, before.isEmpty() && after.isEmpty())
    }
    return null
}

internal fun StructuralRefactorPass.migrateBreakCallbackBody(
    body: String,
    parameter: String,
    entity: String,
    slot: String,
    brokenItem: String,
    markEquipmentSlot: () -> Unit
): String {
    var result = body
    val receivers = listOf("onEquippedItemBroken", "broadcastBreakEvent")
    for (method in receivers) {
        var searchFrom = 0
        val token = "$parameter.$method("
        while (searchFrom < result.length) {
            val index = result.indexOf(token, searchFrom)
            if (index < 0) break
            val openParen = index + token.length - 1
            val closeParen = findMatchingParen(result, openParen)
            if (closeParen < 0) break
            val slotExpression = result.substring(openParen + 1, closeParen).trim()
            val normalizedSlot = normalizeBreakSlotExpression(slotExpression, markEquipmentSlot)
            val replacement = "$entity.onEquippedItemBroken($brokenItem, $normalizedSlot)"
            result = result.substring(0, index) + replacement + result.substring(closeParen + 1)
            searchFrom = index + replacement.length
        }
    }
    result = Regex("""\b${Regex.escape(parameter)}\.""").replace(result, "$entity.")
    if (slot.contains("EquipmentSlot.")) markEquipmentSlot()
    return result
}

internal fun StructuralRefactorPass.normalizeBreakSlotExpression(expression: String, markEquipmentSlot: () -> Unit): String {
    val slot = expression.trim()
    return when {
        slot == "InteractionHand.MAIN_HAND" -> {
            markEquipmentSlot()
            "EquipmentSlot.MAINHAND"
        }
        slot == "InteractionHand.OFF_HAND" -> {
            markEquipmentSlot()
            "EquipmentSlot.OFFHAND"
        }
        slot == "hand" || slot == "handIn" -> "net.minecraft.world.entity.LivingEntity.getSlotForHand($slot)"
        slot.contains("EquipmentSlot.") || slot.contains(".getEquipmentSlot()") -> slot
        else -> "net.minecraft.world.entity.LivingEntity.getSlotForHand($slot)"
    }
}

/**
 * Whether `RecipeType.X` is a vanilla type whose recipes match a `SingleRecipeInput` on 1.21.1 (the
 * field's generic signature names the recipe class, whose inherited `matches` takes one): their
 * 1.20.1 versions read only slot 0 of the container they were given.
 */
internal fun isSingleInputRecipeType(expression: String): Boolean {
    val name = Regex("""^(?:net\.minecraft\.world\.item\.crafting\.)?RecipeType\.([A-Z_]+)$""").find(expression.trim())?.groupValues?.get(1) ?: return false
    val signature = com.modporter.mapping.TargetMembers.fieldSignatures("net.minecraft.world.item.crafting.RecipeType")[name] ?: return false
    val recipe = Regex("""^Lnet/minecraft/world/item/crafting/RecipeType<L([\w/$]+);>;$""").find(signature)?.groupValues?.get(1)?.replace('/', '.') ?: return false
    return com.modporter.mapping.TargetMembers.inheritedMethod(
        recipe, "matches", "(Lnet/minecraft/world/item/crafting/SingleRecipeInput;Lnet/minecraft/world/level/Level;)Z"
    ) != null
}

internal fun StructuralRefactorPass.migrateSingleStackRecipeInputs(source: String): String {
    var changedInput = false
    var result = migrateCachedCheckSingleStackRecipeHolderBoundaries(source)
    result = migrateQuickCheckRecipeHolderCallBoundaries(result)
    if (!result.contains(".getRecipeFor(")) return result
    result = Regex("""((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*getQuickCheck\(\)\.getRecipeFor\(\s*)(this|[A-Za-z_$][\w$]*)(\s*,)""")
        .replace(result) { match ->
            changedInput = true
            val receiver = match.groupValues[2]
            "${match.groupValues[1]}new SingleRecipeInput($receiver.getItem(0))${match.groupValues[3]}"
        }
    result = migrateMethodCalls(result, ".getRecipeFor") { args ->
        if (args.size >= 3) {
            val recipeType = args[0].trim()
            val migratedInput = when {
                args[1].trim().startsWith("new SingleRecipeInput(") -> args[1]
                args[1].trim().startsWith("new SimpleContainer(") -> {
                    changedInput = true
                    val inner = args[1].trim()
                        .removePrefix("new SimpleContainer(")
                        .removeSuffix(")")
                        .trim()
                    "new SingleRecipeInput($inner)"
                }
                isSingleInputRecipeType(recipeType) && Regex("""^(this|[A-Za-z_$][\w$]*)$""").matches(args[1].trim()) -> {
                    changedInput = true
                    "new SingleRecipeInput(${args[1].trim()}.getItem(0))"
                }
                else -> args[1]
            }
            listOf(args[0], migratedInput) + args.drop(2)
        } else {
            args
        }
    }
    result = rewriteJavaInvocationArguments(result, "getRecipeFor") { args ->
        if (args.size == 2 && Regex("""RecipeManager\.CachedCheck\s*<\s*SingleRecipeInput\s*,""").containsMatchIn(result)) {
            val input = args[0].trim()
            if (!input.startsWith("new SingleRecipeInput(") &&
                Regex("""^(?:this|[A-Za-z_$][\w$]*)$""").matches(input)) {
                changedInput = true
                listOf("new SingleRecipeInput($input.getItem(0))", args[1])
            } else {
                null
            }
        } else {
            null
        }
    }

    val singleStackContainers = Regex("""\bSimpleContainer\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+SimpleContainer\s*\(""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
    for (container in singleStackContainers) {
        if (!maskJavaCommentsAndLiterals(result).contains("new SingleRecipeInput($container.getItem(0))")) continue
        result = rewriteJavaInvocationArguments(result, "assemble") { args ->
            if (args.size >= 2 && args[0].trim() == container) {
                changedInput = true
                listOf("new SingleRecipeInput($container.getItem(0))") + args.drop(1)
            } else {
                null
            }
        }
    }

    if (changedInput) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.SingleRecipeInput")
        val withoutSimpleContainerImport = removeImport(result, "net.minecraft.world.SimpleContainer")
        if (!Regex("""\bSimpleContainer\b""").containsMatchIn(withoutSimpleContainerImport)) {
            result = withoutSimpleContainerImport
        }
    }

    var needsRecipeHolder = false
    result = Regex("""(?m)(\.getRecipeFor\([^;\r\n]+)\.orElse\(null\)""")
        .replace(result) { match ->
            val call = match.groupValues[1]
            if ((!call.contains("RecipeType.SMELTING") && !call.contains("new SingleRecipeInput(")) || call.contains(".map(")) {
                match.value
            } else {
                needsRecipeHolder = true
                "$call.map(RecipeHolder::value).orElse(null)"
            }
        }
    result = Regex(
        """(?s)(\.getRecipeFor\([^;\r\n]*?\))\.map\(AbstractCookingRecipe::getCookingTime\)"""
    ).replace(result) { match ->
        needsRecipeHolder = true
        "${match.groupValues[1]}.map(RecipeHolder::value).map(AbstractCookingRecipe::getCookingTime)"
    }
    result = Regex(
        """(?s)(\.getRecipeFor\([^;\r\n]*?\))\.map\(AbstractCookingRecipe::([A-Za-z_$][\w$]*)\)"""
    ).replace(result) { match ->
        if (match.value.contains("RecipeHolder::value")) {
            match.value
        } else {
            needsRecipeHolder = true
            "${match.groupValues[1]}.map(RecipeHolder::value).map(AbstractCookingRecipe::${match.groupValues[2]})"
        }
    }
    Regex("""RecipeManager\.CachedCheck\s*<\s*SingleRecipeInput\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>""")
        .findAll(result)
        .map { it.groupValues[1].substringAfterLast('.') }
        .toSet()
        .forEach { recipeType ->
            result = Regex(
                """(?s)(\.getRecipeFor\([^;\r\n]*?\))\.map\(${Regex.escape(recipeType)}::([A-Za-z_$][\w$]*)\)"""
            ).replace(result) { match ->
                needsRecipeHolder = true
                "${match.groupValues[1]}.map(RecipeHolder::value).map($recipeType::${match.groupValues[2]})"
            }
            result = Regex(
                """RecipeHolder<${Regex.escape(recipeType)}>\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]*?\.getRecipeFor\([^;\r\n]*?\))\.map\(RecipeHolder::value\)\.orElse\(null\)\s*;"""
            ).replace(result) { match ->
                needsRecipeHolder = true
                "RecipeHolder<$recipeType> ${match.groupValues[1]} = ${match.groupValues[2]}.orElse(null);"
            }
            val holderVariables = Regex("""RecipeHolder\s*<\s*${Regex.escape(recipeType)}\s*>\s+([A-Za-z_$][\w$]*)\b""")
                .findAll(result)
                .map { it.groupValues[1] }
                .toSet()
            for (holderVar in holderVariables) {
                result = Regex(
                    """\b${Regex.escape(holderVar)}\s*=\s*([^;\r\n]*?\.getRecipeFor\([^;\r\n]*?\))\.map\(RecipeHolder::value\)\.orElse\(null\)\s*;"""
                ).replace(result) { match ->
                    "$holderVar = ${match.groupValues[1]}.orElse(null);"
                }
                result = Regex("""\b${Regex.escape(holderVar)}\.getId\s*\(\s*\)""")
                    .replace(result, "$holderVar.id()")
                result = Regex("""\b${Regex.escape(holderVar)}\.(get[A-Z][A-Za-z0-9_$]*)\s*\(""")
                    .replace(result, "$holderVar.value().$1(")
            }
        }
    Regex("""RecipeHolder\s*<\s*[^;\r\n]+?\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
        .forEach { holderVar ->
            result = Regex(
                """\b${Regex.escape(holderVar)}\s*=\s*([^;\r\n]*?\.getRecipeFor\([^;\r\n]*?\))\.map\(RecipeHolder::value\)\.orElse\(null\)\s*;"""
            ).replace(result) { match ->
                needsRecipeHolder = true
                "$holderVar = ${match.groupValues[1]}.orElse(null);"
            }
        }
    result = Regex("""\.map\(\s*([A-Za-z_$][\w$]*)\s*->\s*\1\.getResultItem\(""")
        .replace(result) { match ->
            if (result.contains(".getRecipeFor(")) {
                ".map(${match.groupValues[1]} -> ${match.groupValues[1]}.value().getResultItem("
            } else {
                match.value
            }
        }
    result = migrateRecipeHolderSingleStackAssembleCalls(result)

    if (needsRecipeHolder) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
    }
    if (result.contains("RecipeHolder<")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
    }
    return result
}

internal fun StructuralRefactorPass.migrateQuickCheckRecipeHolderCallBoundaries(source: String): String {
    if (!source.contains("getQuickCheck().getRecipeFor(")) return source
    val holderAssignedVariables = Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*[^;\r\n]*getQuickCheck\(\)\.getRecipeFor\(""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (holderAssignedVariables.isEmpty()) return source

    var result = source
    var changed = false
    for (variable in holderAssignedVariables) {
        val id = Regex.escape(variable)
        result = Regex("""\bRecipe\s*<\s*\?\s*>\s+$id\s*;""")
            .replace(result) {
                changed = true
                "RecipeHolder<?> $variable;"
            }
        result = Regex("""((?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*)Recipe\s*<\s*\?\s*>\s+$id\b""")
            .replace(result) { match ->
                changed = true
                "${match.groupValues[1]}RecipeHolder<?> $variable"
            }
    }
    if (changed) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeHolderSingleStackAssembleCalls(source: String): String {
    if (!source.contains("RecipeHolder<") || !source.contains(".assemble(")) return source
    val inputStack = Regex("""\bItemStack\s+([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.get\s*\(\s*0\s*\)\s*;""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return source
    var changed = false
    var result = Regex(
        """\(\(\s*Recipe\s*<\s*(?:WorldlyContainer|Container)\s*>\s*\)\s*([A-Za-z_$][\w$]*)\s*\)\.assemble\(\s*(?:this|[A-Za-z_$][\w$]*)\s*,\s*([^)]+?)\s*\)"""
    ).replace(source) { match ->
        val recipe = match.groupValues[1]
        if (!Regex("""RecipeHolder\s*<[^;\r\n]+>\s+${Regex.escape(recipe)}\b""").containsMatchIn(source)) {
            match.value
        } else {
            changed = true
            "((Recipe<SingleRecipeInput>) $recipe.value()).assemble(new SingleRecipeInput($inputStack), ${match.groupValues[2].trim()})"
        }
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.SingleRecipeInput")
    val withoutWorldlyContainerImport = removeImport(result, "net.minecraft.world.WorldlyContainer")
    if (!Regex("""\bWorldlyContainer\b""").containsMatchIn(withoutWorldlyContainerImport)) {
        result = withoutWorldlyContainerImport
    }
    return result
}

internal data class FurnaceCanBurnAccessorCast(val typeName: String, val furnaceExpression: String)

internal fun StructuralRefactorPass.migrateLegacyAbstractFurnaceCanBurnInvokerBoundary(source: String): String {
    if (!source.contains("callCanBurn(")) return source

    var result = source
    var changed = false
    val id = """[A-Za-z_$][\w$]*"""
    if ((result.contains("@Mixin(AbstractFurnaceBlockEntity.class)") ||
            result.contains("@Mixin(net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.class)")) &&
        result.contains("@Invoker")) {
        val signaturePattern = Regex(
            """(?m)^([ \t]*)boolean\s+callCanBurn\s*\(\s*RegistryAccess\s+($id)\s*,\s*((?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*)RecipeHolder\s*<\s*\?\s*>\s+($id)\s*,\s*NonNullList\s*<\s*ItemStack\s*>\s+($id)\s*,\s*int\s+($id)\s*\)\s*;"""
        )
        result = signaturePattern.replace(result) { match ->
            changed = true
            val indent = match.groupValues[1]
            val registryAccess = match.groupValues[2]
            val annotations = match.groupValues[3]
            val recipe = match.groupValues[4]
            val stacks = match.groupValues[5]
            val stackSize = match.groupValues[6]
            """${indent}static boolean callCanBurn(RegistryAccess $registryAccess, ${annotations}RecipeHolder<?> $recipe, NonNullList<ItemStack> $stacks, int $stackSize, AbstractFurnaceBlockEntity furnace) {
${indent}    throw new AssertionError();
${indent}}"""
        }
        if (changed) {
            result = addImportIfMissing(result, "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity")
            result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
        }
    }

    val directCastPattern = Regex("""^\(\s*\(\s*($id(?:FurnaceBlockEntityAccessor|FurnaceAccessor))\s*\)\s*(.+)\s*\)$""")
    result = rewriteExecutableJavaCallWithOffset(result, "callCanBurn") { receiver, args, callOffset ->
        if (args.size != 4) return@rewriteExecutableJavaCallWithOffset null
        val cast = nearestFurnaceCanBurnAccessorCast(result, receiver, callOffset)
            ?: directCastPattern.find(receiver)?.let { match ->
                FurnaceCanBurnAccessorCast(match.groupValues[1], match.groupValues[2].trim())
            }
            ?: return@rewriteExecutableJavaCallWithOffset null
        changed = true
        "${cast.typeName}.callCanBurn(${args.joinToString(", ")}, ${cast.furnaceExpression})"
    }

    return result
}

internal fun StructuralRefactorPass.nearestFurnaceCanBurnAccessorCast(
    source: String,
    variable: String,
    offset: Int
): FurnaceCanBurnAccessorCast? {
    val id = """[A-Za-z_$][\w$]*"""
    val accessorType = """$id(?:FurnaceBlockEntityAccessor|FurnaceAccessor)"""
    val executableCode = maskJavaCommentsAndLiterals(source)
    val methodRange = enclosingMethodRange(executableCode, offset)
    val prefixStart = methodRange?.first ?: executableCode.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
    val prefixCode = executableCode.substring(prefixStart, offset)
    val bindingPattern = Regex(
        """\b(?:$accessorType\s+)?${Regex.escape(variable)}\s*=\s*\(\s*($accessorType)\s*\)\s*([^;\r\n]+)\s*;"""
    )
    val binding = bindingPattern.findAll(prefixCode).lastOrNull() ?: return null
    val afterBinding = prefixCode.substring(binding.range.last + 1)
    if (Regex("""(?<![.\w$])${Regex.escape(variable)}\s*=(?!=)""").containsMatchIn(afterBinding)) {
        return null
    }
    val typeRange = binding.groups[1]?.range ?: return null
    val expressionRange = binding.groups[2]?.range ?: return null
    return FurnaceCanBurnAccessorCast(
        typeName = source.substring(prefixStart + typeRange.first, prefixStart + typeRange.last + 1).trim(),
        furnaceExpression = source.substring(prefixStart + expressionRange.first, prefixStart + expressionRange.last + 1).trim()
    )
}

internal fun StructuralRefactorPass.migrateRecipeHolderOptionalMapLambdaValueAccess(source: String): String {
    if (!source.contains("getRecipeFor(") || !source.contains(".map(")) return source
    val id = """[A-Za-z_$][\w$]*"""
    var changed = false
    var result = Regex(
        """(?s)(getRecipeFor\s*\([^;\r\n]*?\)\s*\.map\s*\(\s*\(?\s*($id)\s*\)?\s*->\s*)\2\.getId\s*\("""
    ).replace(source) { match ->
        changed = true
        "${match.groupValues[1]}${match.groupValues[2]}.id("
    }
    result = Regex(
        """(?s)(getRecipeFor\s*\([^;\r\n]*?\)\s*\.map\s*\(\s*\(?\s*($id)\s*\)?\s*->\s*)\2\.(?!value\s*\(\)|id\s*\()($id)\s*\("""
    ).replace(result) { match ->
        changed = true
        "${match.groupValues[1]}${match.groupValues[2]}.value().${match.groupValues[3]}("
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateCachedCheckSingleStackRecipeHolderBoundaries(source: String): String {
    if (!source.contains("RecipeManager.CachedCheck") || !source.contains("Container")) return source
    val javaType = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?"""
    val cachedChecks = Regex("""RecipeManager\.CachedCheck\s*<\s*Container\s*,\s*((?:\?\s+extends\s+)?$javaType)\s*>""")
        .findAll(source)
        .map { it.groupValues[1].trim() }
        .toSet()
    if (cachedChecks.isEmpty()) return source

    var result = source
    var changed = false
    for (typeArg in cachedChecks) {
        val recipeType = typeArg.removePrefix("? extends ").trim().substringAfterLast('.')
        val isWildcard = typeArg.startsWith("?")
        result = Regex("""RecipeManager\.CachedCheck\s*<\s*Container\s*,\s*${Regex.escape(typeArg)}\s*>""")
            .replace(result) {
                changed = true
                "RecipeManager.CachedCheck<SingleRecipeInput, $typeArg>"
            }
        if (isWildcard) continue
        result = Regex("""((?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*)\b${Regex.escape(recipeType)}\s+([A-Za-z_$][\w$]*)\b""")
            .replace(result) { match ->
                val variable = match.groupValues[2]
                if (variable.first().isUpperCase()) {
                    match.value
                } else {
                    changed = true
                    "${match.groupValues[1]}RecipeHolder<$recipeType> $variable"
                }
            }
        result = Regex("""setRecipeUsed\s*\(\s*((?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*)Recipe<\?>\s+([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match ->
                changed = true
                "setRecipeUsed(${match.groupValues[1]}RecipeHolder<?> ${match.groupValues[2]})"
            }
        result = Regex("""\bRecipe<\?>\s+getRecipeUsed\s*\(""")
            .replace(result) {
                changed = true
                "RecipeHolder<?> getRecipeUsed("
            }
    }
    if (source.contains("@Mixin(AbstractFurnaceBlockEntity.class)") &&
        source.contains("@Accessor(\"quickCheck\")")) {
        result = Regex("""callCanBurn\s*\(\s*RegistryAccess\s+([A-Za-z_$][\w$]*)\s*,\s*((?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*)Recipe\s*<\s*\?\s*>\s+([A-Za-z_$][\w$]*)""")
            .replace(result) { match ->
                changed = true
                "callCanBurn(RegistryAccess ${match.groupValues[1]}, ${match.groupValues[2]}RecipeHolder<?> ${match.groupValues[3]}"
            }
    }
    if (changed) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.SingleRecipeInput")
        val withoutContainerImport = removeImport(result, "net.minecraft.world.Container")
        if (!Regex("""\bContainer\b""").containsMatchIn(withoutContainerImport)) {
            result = withoutContainerImport
        }
        val withoutRecipeImport = removeImport(result, "net.minecraft.world.item.crafting.Recipe")
        if (!Regex("""\bRecipe\s*<""").containsMatchIn(withoutRecipeImport)) {
            result = withoutRecipeImport
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeBookMenuSingleStackInputs(source: String, recipeTypeHints: RecipeTypeHints): String {
    if (!source.contains("RecipeBookMenu<")) return source
    var result = source
    var changed = false

    result = Regex("""RecipeBookMenu\s*<\s*\?\s*>""").replace(result) {
        changed = true
        "RecipeBookMenu<?, ?>"
    }
    result = Regex("""(\bclass\s+[A-Za-z_$][\w$]*(?:\s*<[^>{}]+>)?\s+extends\s+)RecipeBookMenu\s*<\s*Container\s*>""")
        .replace(result) { match ->
            val recipeClass = inferSingleStackRecipeBookRecipeClass(result, recipeTypeHints) ?: return@replace match.value
            changed = true
            "${match.groupValues[1]}RecipeBookMenu<SingleRecipeInput, $recipeClass>"
        }
    result = Regex("""RecipeBookMenu\s*<\s*Container\s*>(?=\s*,)""").replace(result) {
        changed = true
        "RecipeBookMenu<?, ?>"
    }

    val recipeClass = inferSingleStackRecipeBookRecipeClass(result, recipeTypeHints)
    if (recipeClass != null) {
        result = Regex(
            """(?ms)^([ \t]*)@Override\s*\r?\n\1public\s+boolean\s+recipeMatches\s*\(\s*Recipe\s*<\s*\?\s+super\s+Container\s*>\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*return\s+\2\.matches\s*\(\s*([^,]+?)\s*,\s*([^)]+?)\s*\)\s*;\s*\}"""
        ).replace(result) { match ->
            changed = true
            val indent = match.groupValues[1]
            val recipeVar = match.groupValues[2]
            val containerExpr = match.groupValues[3].trim()
            val levelExpr = match.groupValues[4].trim()
            "${indent}@Override\n${indent}public boolean recipeMatches(RecipeHolder<$recipeClass> $recipeVar) {\n" +
                "${indent}    return $recipeVar.value().matches(new SingleRecipeInput($containerExpr.getItem(0)), $levelExpr);\n" +
                "${indent}}"
        }
    }

    if (changed) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.SingleRecipeInput")
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
        val withoutRecipeImport = removeImport(result, "net.minecraft.world.item.crafting.Recipe")
        if (!Regex("""\bRecipe\s*<""").containsMatchIn(withoutRecipeImport)) {
            result = withoutRecipeImport
        }
        val withoutContainerImport = removeImport(result, "net.minecraft.world.Container")
        if (!Regex("""\bContainer\b""").containsMatchIn(withoutContainerImport)) {
            result = withoutContainerImport
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateSingleStackRecipeImplementations(source: String): String {
    if (!source.contains("Recipe<Container>") || !source.contains(".getItem(0)")) return source
    var result = source
    var changed = false
    result = result.replace("Recipe<Container>", "Recipe<SingleRecipeInput>")
    if (result != source) changed = true
    result = Regex(
        """(?m)^([ \t]*(?:public|protected|private)?\s*(?:boolean|ItemStack)\s+(?:matches|assemble)\s*\(\s*)Container(\s+[A-Za-z_$][\w$]*\s*,\s*(?:Level|HolderLookup\.Provider)\s+[A-Za-z_$][\w$]*\s*\))"""
    ).replace(result) { match ->
        changed = true
        "${match.groupValues[1]}SingleRecipeInput${match.groupValues[2]}"
    }
    if (changed) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.SingleRecipeInput")
        val withoutContainerImport = removeImport(result, "net.minecraft.world.Container")
        if (!Regex("""\bContainer\b""").containsMatchIn(withoutContainerImport)) {
            result = withoutContainerImport
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateGenericRecipeInputImplementations(
    source: String,
    recipeImplementationClasses: Set<String> = emptySet()
): String {
    if (!source.contains("Container") || source.contains("implements CraftingRecipe")) return source

    val executable = maskJavaCommentsAndLiterals(source)
    val typeBlocks = javaTypeBlocks(source, executable)
    val headerEdits = mutableListOf<Pair<IntRange, String>>()
    val recipeOwners = mutableSetOf<String>()
    val headers = Regex("""\b(?:class|record)\s+([A-Za-z_$][\w$]*)\b[^;{]*\{""")
    for (header in headers.findAll(executable)) {
        val owner = header.groupValues[1]
        val direct = Regex("""\bimplements\b[^;{]*\bRecipe\s*<\s*Container\s*>""").containsMatchIn(header.value)
        if (!direct && owner !in recipeImplementationClasses) continue
        val arguments = RecipeInputBounds.containerArgumentRanges(header.value, projectRecipeInputPositions)
        if (arguments.isEmpty()) continue
        recipeOwners += owner
        arguments.forEach { range -> headerEdits +=
            ((header.range.first + range.first)..(header.range.first + range.last)) to "RecipeInput" }
    }
    if (recipeOwners.isEmpty()) return source

    val id = """[A-Za-z_$][\w$]*"""
    val parameterPrefix = """(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*(?:final\s+)?"""
    val methodPattern = Regex(
        """(?m)^([ \t]*(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\r?\n[ \t]*)*(?:public|protected|private)\s+(?:boolean|ItemStack)\s+(?:matches|assemble)\s*\(\s*$parameterPrefix)((?:net\.minecraft\.world\.)?Container)(\s+($id)\s*,\s*$parameterPrefix(?:Level|HolderLookup\.Provider)\s+$id\s*\)\s*(?:throws\s+[^{;]+)?\{)"""
    )

    val replacements = mutableListOf<Pair<IntRange, String>>()
    methodPattern.findAll(executable).forEach { match ->
        val owner = typeBlocks.lastOrNull { match.range.first in it.bodyStart..it.end }?.name
        if (owner !in recipeOwners) return@forEach
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return source
        val body = source.substring(openBrace + 1, closeBrace)
        val parameterName = match.groupValues[4]
        if (!recipeInputCompatibleContainerParameterUsage(body, parameterName)) return source
        replacements.add((match.groups[2]?.range ?: return source) to "RecipeInput")
        Regex("""(?<![\w$])${Regex.escape(parameterName)}\s*\.\s*getContainerSize\s*\(\s*\)""")
            .findAll(body)
            .forEach { call ->
                val start = openBrace + 1 + call.range.first
                val end = openBrace + 1 + call.range.last
                replacements.add((start..end) to "$parameterName.size()")
            }
    }

    if (replacements.isEmpty()) return source

    var result = source
    for ((range, replacement) in (replacements + headerEdits).distinctBy { it.first }.sortedByDescending { it.first.first }) {
        result = result.substring(0, range.first) + replacement + result.substring(range.last + 1)
    }

    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeInput")
    val withoutContainerImport = removeImport(result, "net.minecraft.world.Container")
    if (!hasSimpleTypeReference(withoutContainerImport, "Container")) {
        result = withoutContainerImport
    }
    return result
}

internal fun StructuralRefactorPass.collectCustomRecipeInputTypes(javaFiles: List<Path>): Set<String> {
    val excluded = setOf(
        "Container",
        "CraftingContainer",
        "RecipeInput",
        "SingleRecipeInput",
        "CraftingInput"
    )
    return javaFiles.flatMap { javaFile ->
        val source = maskJavaCommentsAndLiterals(javaFile.readText())
        Regex("""\bimplements\b[^{;]*\bRecipe\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>""")
            .findAll(source)
            .mapNotNull { match ->
                val type = match.groupValues[1].substringAfterLast('.')
                type.takeIf { it !in excluded }
            }
            .toList()
    }.toSet()
}

internal fun StructuralRefactorPass.migrateCustomRecipeInputTypeSource(
    source: String,
    customRecipeInputTypes: Set<String>
): String {
    if (customRecipeInputTypes.isEmpty() || !source.contains("getItem(")) return source
    val className = javaTopLevelTypeName(source) ?: return source
    if (className !in customRecipeInputTypes) return source
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (Regex("""\bimplements\b[^{;]*\bRecipeInput\b""").containsMatchIn(executableCode)) return source
    if (!Regex("""\bimplements\b[^{;]*\b(?:Container|CraftingContainer)\b""").containsMatchIn(executableCode)) return source
    if (!Regex("""\bItemStack\s+getItem\s*\(\s*int\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(executableCode)) return source

    val implementsMatch = Regex("""(\bclass\s+${Regex.escape(className)}\b[^{]*\bimplements\s+)([^{]+)""")
        .find(source)
        ?: return source
    val interfaces = implementsMatch.groupValues[2].trimEnd()
    var result = if (Regex("""\bRecipeInput\b""").containsMatchIn(interfaces)) {
        source
    } else {
        source.substring(0, implementsMatch.range.first) +
            "${implementsMatch.groupValues[1]}$interfaces, RecipeInput " +
            source.substring(implementsMatch.range.last + 1)
    }

    if (!Regex("""(?m)^[ \t]*(?:@Override\s*\r?\n[ \t]*)*public\s+int\s+size\s*\(\s*\)""").containsMatchIn(maskJavaCommentsAndLiterals(result))) {
        val methods = javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result))
        val getContainerSize = methods.firstOrNull { method ->
            method.name == "getContainerSize" &&
                Regex("""\bint\s+getContainerSize\s*\(\s*\)""").containsMatchIn(method.header)
        }
        if (getContainerSize != null) {
            var insertAt = getContainerSize.range.last + 1
            if (insertAt < result.length && result[insertAt] == '\r') insertAt++
            if (insertAt < result.length && result[insertAt] == '\n') insertAt++
            val method = """

    @Override
    public int size() {
        return this.getContainerSize();
    }
""".trimEnd()
            result = result.substring(0, insertAt) + method + result.substring(insertAt)
        }
    }

    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeInput")
    return result
}

internal fun StructuralRefactorPass.recipeInputCompatibleContainerParameterUsage(body: String, parameterName: String): Boolean {
    val masked = maskJavaCommentsAndLiterals(body)
    val parameterPattern = Regex("""(?<![\w$])${Regex.escape(parameterName)}(?![\w$])""")
    val allowedRecipeInputMethods = setOf("getItem", "size", "isEmpty", "getContainerSize")

    parameterPattern.findAll(masked).forEach { match ->
        var cursor = match.range.last + 1
        while (cursor < masked.length && masked[cursor].isWhitespace()) cursor++
        if (cursor >= masked.length || masked[cursor] != '.') return false
        cursor++
        while (cursor < masked.length && masked[cursor].isWhitespace()) cursor++
        val methodStart = cursor
        while (cursor < masked.length && (masked[cursor].isLetterOrDigit() || masked[cursor] == '_' || masked[cursor] == '$')) cursor++
        val methodName = masked.substring(methodStart, cursor)
        if (methodName !in allowedRecipeInputMethods) return false
        while (cursor < masked.length && masked[cursor].isWhitespace()) cursor++
        if (cursor >= masked.length || masked[cursor] != '(') return false
    }

    return true
}

internal fun StructuralRefactorPass.maskJavaComments(source: String): String {
    val result = StringBuilder(source.length)
    var index = 0
    var inLineComment = false
    var inBlockComment = false
    var inString = false
    var inTextBlock = false
    var inChar = false
    var escaped = false
    while (index < source.length) {
        val ch = source[index]
        val next = source.getOrNull(index + 1)
        val nextTwo = source.getOrNull(index + 2)

        when {
            inLineComment -> {
                if (ch == '\r' || ch == '\n') {
                    inLineComment = false
                    result.append(ch)
                } else {
                    result.append(' ')
                }
            }
            inBlockComment -> {
                if (ch == '*' && next == '/') {
                    result.append("  ")
                    index++
                    inBlockComment = false
                } else {
                    result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                }
            }
            inTextBlock -> {
                result.append(ch)
                if (ch == '"' && next == '"' && nextTwo == '"') {
                    result.append("  ")
                    index += 2
                    inTextBlock = false
                }
            }
            inString -> {
                result.append(ch)
                if (ch == '"' && !escaped) inString = false
                escaped = ch == '\\' && !escaped
                if (ch != '\\') escaped = false
            }
            inChar -> {
                result.append(ch)
                if (ch == '\'' && !escaped) inChar = false
                escaped = ch == '\\' && !escaped
                if (ch != '\\') escaped = false
            }
            ch == '/' && next == '/' -> {
                result.append("  ")
                index++
                inLineComment = true
            }
            ch == '/' && next == '*' -> {
                result.append("  ")
                index++
                inBlockComment = true
            }
            ch == '"' && next == '"' && nextTwo == '"' -> {
                result.append("\"\"\"")
                index += 2
                inTextBlock = true
                escaped = false
            }
            ch == '"' -> {
                result.append(ch)
                inString = true
                escaped = false
            }
            ch == '\'' -> {
                result.append(ch)
                inChar = true
                escaped = false
            }
            else -> result.append(ch)
        }
        index++
    }
    return result.toString()
}

internal fun StructuralRefactorPass.maskJavaCommentsAndLiterals(source: String): String {
    val result = StringBuilder(source.length)
    var index = 0
    var inLineComment = false
    var inBlockComment = false
    var inString = false
    var inTextBlock = false
    var inChar = false
    var escaped = false
    while (index < source.length) {
        val ch = source[index]
        val next = source.getOrNull(index + 1)
        val nextTwo = source.getOrNull(index + 2)

        when {
            inLineComment -> {
                if (ch == '\r' || ch == '\n') {
                    inLineComment = false
                    result.append(ch)
                } else {
                    result.append(' ')
                }
            }
            inBlockComment -> {
                if (ch == '*' && next == '/') {
                    result.append("  ")
                    index++
                    inBlockComment = false
                } else {
                    result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                }
            }
            inTextBlock -> {
                result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                if (ch == '"' && next == '"' && nextTwo == '"') {
                    result.append("  ")
                    index += 2
                    inTextBlock = false
                }
            }
            inString -> {
                result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                if (ch == '"' && !escaped) inString = false
                escaped = ch == '\\' && !escaped
                if (ch != '\\') escaped = false
            }
            inChar -> {
                result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                if (ch == '\'' && !escaped) inChar = false
                escaped = ch == '\\' && !escaped
                if (ch != '\\') escaped = false
            }
            ch == '/' && next == '/' -> {
                result.append("  ")
                index++
                inLineComment = true
            }
            ch == '/' && next == '*' -> {
                result.append("  ")
                index++
                inBlockComment = true
            }
            ch == '"' && next == '"' && nextTwo == '"' -> {
                result.append("   ")
                index += 2
                inTextBlock = true
                escaped = false
            }
            ch == '"' -> {
                result.append(' ')
                inString = true
                escaped = false
            }
            ch == '\'' -> {
                result.append(' ')
                inChar = true
                escaped = false
            }
            else -> result.append(ch)
        }
        index++
    }
    return result.toString()
}

internal fun StructuralRefactorPass.inferSingleStackRecipeBookRecipeClass(source: String, recipeTypeHints: RecipeTypeHints): String? {
    val javaType = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?"""
    Regex("""\bRecipeType\s*<\s*\?\s+extends\s+($javaType)\s*>""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.substringAfterLast('.')
        ?.let { return it }
    Regex("""\bRecipeType\s*<\s*($javaType)\s*>""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.takeIf { it != "?" }
        ?.substringAfterLast('.')
        ?.let { return it }

    Regex("""getRecipeFor\s*\(\s*([^,]+?)\s*,\s*new\s+(?:SimpleContainer|SingleRecipeInput)\s*\(""")
        .findAll(source)
        .forEach { match ->
            recipeTypeHints.expressionToRecipeClass[normalizeRecipeTypeExpression(match.groupValues[1])]?.let { return it }
        }
    return null
}

internal fun StructuralRefactorPass.migrateProtectedBlockFluidStateAccess(source: String): String {
    var result = source
    result = Regex(
        """\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\(\))?)\.getBlock\(\)\.getFluidState\(\s*\1\s*\)"""
    ).replace(result) { match -> "${match.groupValues[1]}.getFluidState()" }
    return result
}

internal fun StructuralRefactorPass.migrateEntityEffectParticleOptions(source: String): String {
    if (!source.contains("ParticleTypes.ENTITY_EFFECT") &&
        !source.contains("MAGIC_EFFECT")) return source

    var changed = false
    var result = Regex(
        """(?s)([A-Za-z_$][\w$.]*)\.sendParticles\(\s*ParticleTypes\.ENTITY_EFFECT\s*,\s*([^,;]+?)\s*,\s*([^,;]+?)\s*,\s*([^,;]+?)\s*,\s*([^,;]+?)\s*,\s*([^,;]+?)\s*,\s*([^,;]+?)\s*,\s*([^,;]+?)\s*,\s*([^)]+?)\s*\)"""
    ).replace(source) { match ->
        changed = true
        val target = match.groupValues[1]
        val x = match.groupValues[2].trim()
        val y = match.groupValues[3].trim()
        val z = match.groupValues[4].trim()
        val count = match.groupValues[5].trim()
        val red = match.groupValues[6].trim()
        val green = match.groupValues[7].trim()
        val blue = match.groupValues[8].trim()
        val speed = match.groupValues[9].trim()
        "$target.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, $red, $green, $blue), $x, $y, $z, $count, 0, 0, 0, $speed)"
    }
    result = migrateMethodCalls(result, ".queueParticle") { args ->
        if (args.size == 8 &&
            !args[0].contains("ColorParticleOption.create") &&
            (args[0].contains("ENTITY_EFFECT") || args[0].contains("MAGIC_EFFECT"))) {
            changed = true
            listOf(
                "ColorParticleOption.create(${args[0]}, ${args[5]}, ${args[6]}, ${args[7]})",
                args[1],
                args[2],
                args[3],
                args[4],
                "0.0D",
                "0.0D",
                "0.0D"
            )
        } else {
            args
        }
    }
    if (changed) {
        result = addImportIfMissing(result, "net.minecraft.core.particles.ColorParticleOption")
    }
    return result
}

internal fun StructuralRefactorPass.migrateSkullOwnerResolvableProfileSource(source: String): String {
    if (!source.contains("SkullOwner") &&
        !source.contains("NbtUtils.readGameProfile") &&
        !source.contains("NbtUtils.writeGameProfile") &&
        !source.contains("setOwner(")) {
        return source
    }

    var result = source
    result = Regex("""NbtUtils\.readGameProfile\(\s*([^()]*(?:\([^()]*\))?)\s*\)""")
        .replace(result) { match ->
            "ResolvableProfile.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, ${match.groupValues[1].trim()}).result().orElse(null)"
        }
    result = Regex("""new\s+GameProfile\(\s*null\s*,\s*([^()]*(?:\([^()]*\))?)\s*\)""")
        .replace(result) { match ->
            "new ResolvableProfile(java.util.Optional.of(${match.groupValues[1].trim()}), java.util.Optional.empty(), new com.mojang.authlib.properties.PropertyMap())"
        }
    result = Regex("""NbtUtils\.writeGameProfile\(\s*new\s+CompoundTag\(\)\s*,\s*([^()]*(?:\([^()]*\))?)\s*\)""")
        .replace(result) { match ->
            "ResolvableProfile.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, ${match.groupValues[1].trim()}).getOrThrow()"
        }
    result = replaceExecutableRegex(
        result,
        Regex(
            """\b([A-Za-z_$][\w$]*)\.getOrDefault\(\s*(?:net\.minecraft\.core\.component\.)?DataComponents\.CUSTOM_DATA\s*,\s*(?:net\.minecraft\.world\.item\.component\.)?CustomData\.EMPTY\s*\)\.copyTag\(\)\.put\(\s*"SkullOwner"\s*,\s*ResolvableProfile\.CODEC\.encodeStart\(\s*(?:net\.minecraft\.nbt\.)?NbtOps\.INSTANCE\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\(\))*)\.getGameProfile\(\)\s*\)\.getOrThrow\(\)\s*\)"""
        )
    ) { match ->
        "${match.groupValues[1]}.set(DataComponents.PROFILE, new ResolvableProfile(${match.groupValues[2]}.getGameProfile()))"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""ResolvableProfile\.CODEC\.encodeStart\(\s*(?:net\.minecraft\.nbt\.)?NbtOps\.INSTANCE\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\(\))*)\.getGameProfile\(\)\s*\)""")
    ) { match ->
        "ResolvableProfile.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, new ResolvableProfile(${match.groupValues[1]}.getGameProfile()))"
    }
    result = replaceExecutableRegex(
        result,
        Regex(
            """\b([A-Za-z_$][\w$]*)\.getOrDefault\(\s*(?:net\.minecraft\.core\.component\.)?DataComponents\.CUSTOM_DATA\s*,\s*(?:net\.minecraft\.world\.item\.component\.)?CustomData\.EMPTY\s*\)\.copyTag\(\)\.put\(\s*"SkullOwner"\s*,\s*ResolvableProfile\.CODEC\.encodeStart\(\s*(?:net\.minecraft\.nbt\.)?NbtOps\.INSTANCE\s*,\s*new\s+ResolvableProfile\(\s*([^()]+?\.getGameProfile\(\))\s*\)\s*\)\.getOrThrow\(\)\s*\)"""
        )
    ) { match ->
        "${match.groupValues[1]}.set(DataComponents.PROFILE, new ResolvableProfile(${match.groupValues[2].trim()}))"
    }
    result = rewriteJavaCall(result, "put") { receiver, args ->
        if (args.size != 2 || args[0].trim() != "\"SkullOwner\"") return@rewriteJavaCall null
        val stackName = Regex(
            """^([A-Za-z_$][\w$]*)\.getOrDefault\(\s*(?:net\.minecraft\.core\.component\.)?DataComponents\.CUSTOM_DATA\s*,\s*(?:net\.minecraft\.world\.item\.component\.)?CustomData\.EMPTY\s*\)\.copyTag\(\)$"""
        ).find(receiver.trim())?.groupValues?.get(1) ?: return@rewriteJavaCall null
        val profileExpression = skullOwnerResolvableProfileExpression(args[1].trim()) ?: return@rewriteJavaCall null
        "$stackName.set(DataComponents.PROFILE, $profileExpression)"
    }
    result = Regex("""GameProfile\s+([A-Za-z_$][\w$]*)\s*=\s*null\s*;""")
        .replace(result) { match ->
            val variable = match.groupValues[1]
            if (result.contains("$variable = ResolvableProfile.") ||
                result.contains("$variable = new ResolvableProfile") ||
                result.contains("setOwner($variable)")) {
                "ResolvableProfile $variable = null;"
            } else {
                match.value
            }
        }
    result = Regex(
        """SkullBlockEntity\.updateGameprofile\(\s*([A-Za-z_$][\w$]*)\s*,\s*\(\s*([A-Za-z_$][\w$]*)\s*\)\s*->\s*(.*?)\s*\)\s*;""",
        RegexOption.DOT_MATCHES_ALL
    ).replace(result) { match ->
        "${match.groupValues[1]}.resolve().thenAccept(${match.groupValues[2]} ->\n\t\t\t\t\t\t\t\t${match.groupValues[3].trim()});"
    }

    if (result.contains("ResolvableProfile")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.component.ResolvableProfile")
    }
    if (result.contains("DataComponents.PROFILE")) {
        result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    }
    val withoutSkullBlockEntityImport = removeImport(result, "net.minecraft.world.level.block.entity.SkullBlockEntity")
    if (!withoutSkullBlockEntityImport.contains("SkullBlockEntity")) {
        result = withoutSkullBlockEntityImport
    }
    if (!result.contains("NbtUtils.")) {
        result = removeImport(result, "net.minecraft.nbt.NbtUtils")
    }
    val withoutGameProfileImport = removeImport(result, "com.mojang.authlib.GameProfile")
    if (!withoutGameProfileImport.contains("GameProfile")) {
        result = withoutGameProfileImport
    }
    return result
}

internal fun StructuralRefactorPass.skullOwnerResolvableProfileExpression(expression: String): String? {
    Regex(
        """^ResolvableProfile\.CODEC\.encodeStart\(\s*(?:net\.minecraft\.nbt\.)?NbtOps\.INSTANCE\s*,\s*new\s+ResolvableProfile\(\s*(.+?\.getGameProfile\(\))\s*\)\s*\)\.getOrThrow\(\)$"""
    ).find(expression)?.let { return "new ResolvableProfile(${it.groupValues[1].trim()})" }
    Regex(
        """^ResolvableProfile\.CODEC\.encodeStart\(\s*(?:net\.minecraft\.nbt\.)?NbtOps\.INSTANCE\s*,\s*(.+?\.getGameProfile\(\))\s*\)\.getOrThrow\(\)$"""
    ).find(expression)?.let { return "new ResolvableProfile(${it.groupValues[1].trim()})" }
    return null
}

internal fun StructuralRefactorPass.migrateResolvableProfileGameProfileBoundaries(source: String): String {
    if (!source.contains("ResolvableProfile") && !source.contains("GameProfile") && !source.contains("getOwnerProfile()")) {
        return source
    }
    var result = source
    var needsSkinManager = false
    var needsResolvableProfile = false

    if (result.contains("@Nullable GameProfile") &&
        result.contains("getSkinManager().getInsecureSkinInformation(") &&
        (result.contains("ResolvableProfile") || result.contains("getOwnerProfile()"))) {
        result = result.replace("@Nullable GameProfile profile", "@Nullable ResolvableProfile profile")
        needsResolvableProfile = true
        result = result.replace("DefaultPlayerSkin.getDefaultSkin()", "DefaultPlayerSkin.getDefaultTexture()")
        result = result.replace(
            Regex(
                """Minecraft\s+([A-Za-z_$][\w$]*)\s*=\s*Minecraft\.getInstance\(\)\s*;\s*""" +
                    """Map<MinecraftProfileTexture\.Type,\s*MinecraftProfileTexture>\s+([A-Za-z_$][\w$]*)\s*=\s*\1\.getSkinManager\(\)\.getInsecureSkinInformation\(\s*profile\s*\)\s*;\s*""" +
                    """return\s+\2\.containsKey\(MinecraftProfileTexture\.Type\.SKIN\)\s*\?\s*RenderType\.entityTranslucent\(\s*\1\.getSkinManager\(\)\.registerTexture\(\2\.get\(MinecraftProfileTexture\.Type\.SKIN\),\s*MinecraftProfileTexture\.Type\.SKIN\)\s*\)\s*:\s*RenderType\.entityCutoutNoCull\(DefaultPlayerSkin\.getDefaultSkin\(UUIDUtil\.getOrCreatePlayerUUID\(profile\)\)\)\s*;""",
                RegexOption.DOT_MATCHES_ALL
            )
        ) {
            needsSkinManager = true
            "SkinManager skinmanager = Minecraft.getInstance().getSkinManager();\n" +
                "\t\t\treturn RenderType.entityTranslucent(skinmanager.getInsecureSkin(profile.gameProfile()).texture());"
        }
    }

    if (needsSkinManager) {
        result = addImportIfMissing(result, "net.minecraft.client.resources.SkinManager")
    }
    if (needsResolvableProfile || result.contains("ResolvableProfile")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.component.ResolvableProfile")
    }
    val withoutGameProfile = removeImport(result, "com.mojang.authlib.GameProfile")
    if (!Regex("""\bGameProfile\b""").containsMatchIn(withoutGameProfile)) {
        result = withoutGameProfile
    }
    val withoutProfileTexture = removeImport(result, "com.mojang.authlib.minecraft.MinecraftProfileTexture")
    if (!Regex("""\bMinecraftProfileTexture\b""").containsMatchIn(withoutProfileTexture)) {
        result = withoutProfileTexture
    }
    val withoutUuidUtil = removeImport(result, "net.minecraft.core.UUIDUtil")
    if (!Regex("""\bUUIDUtil\b""").containsMatchIn(withoutUuidUtil)) {
        result = withoutUuidUtil
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacySkullOwnerVerifyComponentsSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val code = maskJavaComments(source)
    if (!executableCode.contains("verifyTagAfterLoad") || !code.contains("SkullOwner")) return source
    val method = javaMethodRanges(executableCode).singleOrNull { method ->
        method.name == "verifyTagAfterLoad" &&
            Regex("""\bvoid\s+verifyTagAfterLoad\s*\(""").containsMatchIn(method.header)
    } ?: return source
    val methodText = source.substring(method.range)
    val methodCode = maskJavaComments(methodText)
    if (!methodCode.contains("CompoundTag") || !methodCode.contains("SkullOwner")) return source

    val indent = methodText.lineSequence()
        .firstOrNull { it.contains("verifyTagAfterLoad") }
        ?.takeWhile { it == ' ' || it == '\t' }
        ?: "\t"
    val bodyIndent = indent + "\t"
    val nestedIndent = bodyIndent + "\t"
    val replacement = """
${indent}@Override
${indent}public void verifyComponentsAfterLoad(ItemStack stack) {
${bodyIndent}super.verifyComponentsAfterLoad(stack);
${bodyIndent}ResolvableProfile profile = stack.get(DataComponents.PROFILE);
${bodyIndent}if (profile == null) {
${nestedIndent}CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
${nestedIndent}if (customData != null) {
${nestedIndent}	CompoundTag tag = customData.copyTag();
${nestedIndent}	if (tag.contains("SkullOwner", 8) && !tag.getString("SkullOwner").isBlank()) {
${nestedIndent}		profile = new ResolvableProfile(java.util.Optional.of(tag.getString("SkullOwner")), java.util.Optional.empty(), new com.mojang.authlib.properties.PropertyMap());
${nestedIndent}		stack.set(DataComponents.PROFILE, profile);
${nestedIndent}	} else if (tag.contains("SkullOwner", 10)) {
${nestedIndent}		profile = ResolvableProfile.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag.get("SkullOwner")).result().orElse(null);
${nestedIndent}		if (profile != null) {
${nestedIndent}			stack.set(DataComponents.PROFILE, profile);
${nestedIndent}		}
${nestedIndent}	}
${nestedIndent}}
${bodyIndent}}
${bodyIndent}if (profile != null && !profile.isResolved()) {
${nestedIndent}profile.resolve().thenAcceptAsync(resolved -> stack.set(DataComponents.PROFILE, resolved), SkullBlockEntity.CHECKED_MAIN_THREAD_EXECUTOR);
${bodyIndent}}
${indent}}
        """.trimIndent()

    var result = source.replaceRange(method.range, replacement)
    result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    result = addImportIfMissing(result, "net.minecraft.world.item.ItemStack")
    result = addImportIfMissing(result, "net.minecraft.world.item.component.CustomData")
    result = addImportIfMissing(result, "net.minecraft.world.item.component.ResolvableProfile")
    result = addImportIfMissing(result, "net.minecraft.world.level.block.entity.SkullBlockEntity")
    val withoutGameProfile = removeImport(result, "com.mojang.authlib.GameProfile")
    if (!Regex("""\bGameProfile\b""").containsMatchIn(withoutGameProfile)) {
        result = withoutGameProfile
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyItemStackTagWrites(source: String): String {
    if (!source.contains(".addTagElement(") && !source.contains(".setTag(")) return source

    var result = source
    val itemStackNames = collectItemStackValueNames(source)
    val itemStackReturningMethods = collectItemStackReturningMethodNames(source)
    val knownItemStackAccessors = collectKnownItemStackAccessorExpressions(source)
    fun isItemStackTagReceiver(receiver: String): Boolean =
        isItemStackExpression(receiver, itemStackNames, itemStackReturningMethods, knownItemStackAccessors)
    result = Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*)\.addTagElement\(\s*"BlockEntityTag"\s*,\s*([^;\r\n]+)\s*\);\s*$""")
        .replace(result) { match ->
            val indent = match.groupValues[1]
            val stack = match.groupValues[2]
            if (!isItemStackTagReceiver(stack)) return@replace match.value
            val tag = match.groupValues[3].trim()
            "$indent$stack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.of($tag));"
        }
    result = Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*)\.addTagElement\(\s*("(?:(?:\\")|[^"])*"|[A-Za-z_$][\w$]*)\s*,\s*([^;\r\n]+)\s*\);\s*$""")
        .replace(result) { match ->
            val key = match.groupValues[3]
            if (key == "\"BlockEntityTag\"") {
                match.value
            } else {
                val indent = match.groupValues[1]
                val stack = match.groupValues[2]
                if (!isItemStackTagReceiver(stack)) return@replace match.value
                val value = match.groupValues[4].trim()
                "${indent}net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, $stack, tag -> tag.put($key, $value));"
            }
        }
    result = Regex("""(?m)^([ \t]*(?:\}\s*)?(?:else\s+)?)([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*(?:\(\))?)*)\s*\.setTag\(\s*([^;\r\n]+)\s*\);[ \t]*$""")
        .replace(result) { match ->
            val indent = match.groupValues[1]
            val stack = match.groupValues[2].replace(Regex("""\s+"""), "")
            if (!isItemStackTagReceiver(stack)) return@replace match.value
            val tag = match.groupValues[3].trim()
            val customDataValue = legacyCustomDataValueExpressionForTagWrite(
                tag,
                itemStackNames,
                itemStackReturningMethods,
                knownItemStackAccessors
            )
            "$indent$stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, $customDataValue);"
    }
    return result
}

internal fun StructuralRefactorPass.legacyCustomDataValueExpressionForTagWrite(
    tagExpression: String,
    itemStackNames: Set<String>,
    itemStackReturningMethods: Set<String>,
    knownItemStackAccessors: Set<String>
): String {
    val customDataRead = Regex(
        """^(.+?)\.getOrDefault\(\s*net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\s*,\s*net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\s*\)\.copyTag\(\)\s*$"""
    ).find(tagExpression)
    val rawTagRead = Regex("""^(.+?)\.get(?:OrCreate)?Tag\(\s*\)\s*$""").find(tagExpression)
    val receiver = customDataRead?.groupValues?.get(1)
        ?: rawTagRead?.groupValues?.get(1)
    if (receiver != null &&
        isItemStackExpression(receiver.trim(), itemStackNames, itemStackReturningMethods, knownItemStackAccessors)) {
        return "${receiver.trim()}.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY)"
    }
    return "net.minecraft.world.item.component.CustomData.of($tagExpression)"
}

internal fun StructuralRefactorPass.migrateLegacyItemStackTagReads(source: String): String {
    if (!source.contains(".getTag()") &&
        !source.contains(".getOrCreateTag()") &&
        !source.contains(".hasTag()")) {
        return source
    }
    val stackReceiver = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*"""
    var result = Regex(
        """(?:net\.minecraft\.world\.item\.component\.)?CustomData\.of\(\s*($stackReceiver)\.getTag\(\)\s*\)"""
    ).replace(source) { match ->
        val itemStackNames = collectItemStackValueNames(source)
        val itemStackReturningMethods = collectItemStackReturningMethodNames(source)
        val knownItemStackAccessors = collectKnownItemStackAccessorExpressions(source)
        val stack = match.groupValues[1]
        if (!isItemStackExpression(stack, itemStackNames, itemStackReturningMethods, knownItemStackAccessors)) {
            match.value
        } else {
            "$stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY)"
        }
    }
    val itemStackVariables = collectItemStackValueNames(result)
    for (stack in itemStackVariables) {
        val receiver = Regex.escape(stack)
        result = replaceExecutableRegex(result, Regex("""\b$receiver\.hasTag\s*\(\s*\)""")) {
            "$stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)"
        }
        result = replaceExecutableRegex(result, Regex("""\b$receiver\.getTag\s*\(\s*\)""")) {
            "$stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
        }
    }
    val itemStackReturningMethods = collectItemStackReturningMethodNames(result)
    val knownItemStackAccessors = collectKnownItemStackAccessorExpressions(result)
    result = rewriteJavaCallWithOffset(result, "hasTag") { receiver, args, _ ->
        if (args.isNotEmpty()) return@rewriteJavaCallWithOffset null
        val stack = receiver.trim()
        if (Regex("""[A-Za-z_$][\w$]*""").matches(stack)) return@rewriteJavaCallWithOffset null
        if (!isItemStackExpression(stack, itemStackVariables, itemStackReturningMethods, knownItemStackAccessors)) {
            return@rewriteJavaCallWithOffset null
        }
        "$stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)"
    }
    result = rewriteJavaCallWithOffset(result, "getTag") { receiver, args, _ ->
        if (args.isNotEmpty()) return@rewriteJavaCallWithOffset null
        val stack = receiver.trim()
        if (Regex("""[A-Za-z_$][\w$]*""").matches(stack)) return@rewriteJavaCallWithOffset null
        if (!isItemStackExpression(stack, itemStackVariables, itemStackReturningMethods, knownItemStackAccessors)) {
            return@rewriteJavaCallWithOffset null
        }
        "$stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
    }
    return result
}

internal fun StructuralRefactorPass.collectItemStackReturningMethodNames(source: String): Set<String> =
    Regex("""\b(?:net\.minecraft\.world\.item\.)?ItemStack\s+([A-Za-z_$][\w$]*)\s*\(""")
        .findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet()

internal fun StructuralRefactorPass.collectKnownItemStackAccessorExpressions(source: String): Set<String> {
    val code = maskJavaCommentsAndLiterals(source)
    val curiosSlotResults = Regex("""\b(?:top\.theillusivec4\.curios\.api\.)?SlotResult\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(code)
        .map { it.groupValues[1] }
        .toSet()
    return curiosSlotResults.mapTo(linkedSetOf()) { "$it.stack()" }
}

internal fun StructuralRefactorPass.migrateLegacyArmorColorHookOverrides(source: String): String {
    if (!source.contains("extends ArmorItem") || !source.contains("@Override")) return source
    val legacyHooks = listOf(
        Regex("""(?m)^([ \t]*)@Override\s*\r?\n(\1public\s+boolean\s+hasCustomColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*\))"""),
        Regex("""(?m)^([ \t]*)@Override\s*\r?\n(\1public\s+int\s+getColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*\))"""),
        Regex("""(?m)^([ \t]*)@Override\s*\r?\n(\1public\s+void\s+clearColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*\))"""),
        Regex("""(?m)^([ \t]*)@Override\s*\r?\n(\1public\s+void\s+setColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*,\s*int\s+[A-Za-z_$][\w$]*\s*\))""")
    )
    var result = source
    for (hook in legacyHooks) {
        result = hook.replace(result) { match -> match.groupValues[2] }
    }
    return result
}

internal fun StructuralRefactorPass.isItemStackExpression(
    receiver: String,
    itemStackNames: Set<String>,
    itemStackReturningMethods: Set<String>,
    knownItemStackAccessors: Set<String> = emptySet()
): Boolean {
    val normalized = receiver.trim()
    val simpleName = normalized.substringAfterLast('.').removeSuffix("()")
    if (simpleName in itemStackNames) return true
    if (simpleName in itemStackReturningMethods && normalized.endsWith("()")) return true
    if (normalized in knownItemStackAccessors) return true
    return normalized.startsWith("new ItemStack(") ||
        normalized.startsWith("new net.minecraft.world.item.ItemStack(") ||
        normalized.endsWith(".getMainHandItem()") ||
        normalized.endsWith(".getOffhandItem()") ||
        Regex("""\.getItemBySlot\s*\(""").containsMatchIn(normalized) ||
        Regex("""\.getItemInHand\s*\(""").containsMatchIn(normalized) ||
        Regex("""\.(?:extractItem|getStackInSlot|insertItem)\s*\(""").containsMatchIn(normalized) ||
        Regex("""\.(?:copy|copyWithCount)\s*\(""").containsMatchIn(normalized)
}

internal fun StructuralRefactorPass.migrateLegacyItemEnchantmentComponents(source: String): String {
    if (!source.contains("EnchantmentHelper.getEnchantments(") &&
        !source.contains("EnchantmentHelper.setEnchantments(") &&
        !source.contains("getRarity().getWeight()")) {
        return source
    }

    var result = source
    var changed = false

    val legacyMergePattern = Regex(
        """(?s)\s*//[^\r\n]*result has innate enchantments[^\r\n]*\r?\n\s*Map<Enchantment,\s*Integer>\s+([A-Za-z_$][\w$]*)\s*=\s*EnchantmentHelper\.getEnchantments\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;\s*\r?\n\s*Map<Enchantment,\s*Integer>\s+([A-Za-z_$][\w$]*)\s*=\s*EnchantmentHelper\.getEnchantments\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;\s*\r?\n\s*//[^\r\n]*\r?\n\s*\3\.keySet\(\)\.removeIf\(\s*([A-Za-z_$][\w$]*)\s*->\s*\5\s*==\s*null\s*\|\|\s*!\5\.canEnchant\(\s*\2\s*\)\s*\)\s*;\s*\r?\n\s*if\s*\(\s*([A-Za-z_$][\w$]*)\s*!=\s*null\s*\)\s*\{\s*\r?\n\s*//[^\r\n]*\r?\n\s*\6\.remove\(\s*"ench"\s*\)\s*;\s*\r?\n\s*\6\.remove\(\s*"Damage"\s*\)\s*;\s*\r?\n\s*\2\.set\(\s*net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\s*,\s*net\.minecraft\.world\.item\.component\.CustomData\.of\(\s*\6\s*\)\s*\)\s*;\s*\r?\n\s*EnchantmentHelper\.setEnchantments\(\s*\3\s*,\s*\2\s*\)\s*;\s*\r?\n\s*\}\s*\r?\n\s*//[^\r\n]*\r?\n\s*for\s*\(\s*Map\.Entry<Enchantment,\s*Integer>\s+([A-Za-z_$][\w$]*)\s*:\s*\1\.entrySet\(\)\s*\)\s*\{\s*\r?\n\s*Enchantment\s+([A-Za-z_$][\w$]*)\s*=\s*\7\.getKey\(\)\s*;\s*\r?\n\s*int\s+([A-Za-z_$][\w$]*)\s*=\s*\7\.getValue\(\)\s*;\s*\r?\n\s*//[^\r\n]*\r?\n\s*//[^\r\n]*\r?\n\s*if\s*\(\s*EnchantmentHelper\.isEnchantmentCompatible\(\s*EnchantmentHelper\.getEnchantments\(\s*\2\s*\)\.keySet\(\)\s*,\s*\8\s*\)\s*&&\s*EnchantmentHelper\.getTagEnchantmentLevel\(\s*\8\s*,\s*\2\s*\)\s*<\s*\9\s*\)\s*\{\s*\r?\n\s*\2\.enchant\(\s*\8\s*,\s*\9\s*\)\s*;\s*\r?\n\s*\}\s*\r?\n\s*\}"""
    )
    result = legacyMergePattern.replace(result) { match ->
        changed = true
        val resultStack = match.groupValues[2]
        val inputEnchantments = match.groupValues[3]
        val inputStack = match.groupValues[4]
        val inputTags = match.groupValues[6]
        """

				ItemEnchantments.Mutable $inputEnchantments = new ItemEnchantments.Mutable($inputStack.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
				if ($resultStack.has(net.minecraft.core.component.DataComponents.ENCHANTMENTS)) {
					$resultStack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS).entrySet().forEach(enchantment -> $inputEnchantments.set(enchantment.getKey(), enchantment.getIntValue()));
				}
				$inputEnchantments.removeIf(enchantment -> !$resultStack.supportsEnchantment(enchantment));

				if ($inputTags != null) {
					$inputTags.remove("Damage");
					$resultStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of($inputTags));
				}

				$resultStack.remove(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
				EnchantmentHelper.setEnchantments($resultStack, $inputEnchantments.toImmutable());
            """.trimEnd()
    }

    result = Regex("""EnchantmentHelper\.getEnchantments\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^)]*\))?)*)\s*\)\.size\(\)""")
        .replace(result) { match ->
            changed = true
            "${match.groupValues[1]}.getTagEnchantments().size()"
        }

    result = Regex(
        """for\s*\(\s*Map\.Entry<Enchantment,\s*Integer>\s+([A-Za-z_$][\w$]*)\s*:\s*EnchantmentHelper\.getEnchantments\(\s*([A-Za-z_$][\w$]*)\s*\)\.entrySet\(\)\s*\)"""
    ).replace(result) { match ->
        changed = true
        "for (Object2IntMap.Entry<Holder<Enchantment>> ${match.groupValues[1]} : ${match.groupValues[2]}.getTagEnchantments().entrySet())"
    }
    result = Regex("""Enchantment\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.getKey\(\)\s*;""")
        .replace(result) { match ->
            val entry = match.groupValues[2]
            if (result.contains("Object2IntMap.Entry<Holder<Enchantment>> $entry")) {
                changed = true
                "Enchantment ${match.groupValues[1]} = $entry.getKey().value();"
            } else {
                match.value
            }
        }
    result = Regex("""int\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.getValue\(\)\s*;""")
        .replace(result) { match ->
            val entry = match.groupValues[2]
            if (result.contains("Object2IntMap.Entry<Holder<Enchantment>> $entry")) {
                changed = true
                "int ${match.groupValues[1]} = $entry.getIntValue();"
            } else {
                match.value
            }
        }
    result = Regex("""([A-Za-z_$][\w$]*)\s*!=\s*null\s*&&\s*([A-Za-z_$][\w$]*)\s*>\s*0""")
        .replace(result) { match ->
            val maybeEnchantment = match.groupValues[1]
            val level = match.groupValues[2]
            if (Regex("""Enchantment\s+${Regex.escape(maybeEnchantment)}\s*=""").containsMatchIn(result)) {
                changed = true
                "$level > 0"
            } else {
                match.value
            }
        }

    if (result.contains("getRarity().getWeight()")) {
        result = result.replace(".getRarity().getWeight()", ".getWeight()")
        changed = true
    }

    if (!changed) return source
    if (result.contains("ItemEnchantments.")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.ItemEnchantments")
    }
    if (result.contains("Object2IntMap.Entry<Holder<Enchantment>>")) {
        result = addImportIfMissing(result, "it.unimi.dsi.fastutil.objects.Object2IntMap")
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
    }
    val withoutJavaMap = removeImport(result, "java.util.Map")
    if (!Regex("""\bMap\s*<""").containsMatchIn(withoutJavaMap)) {
        result = withoutJavaMap
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeferredSpawnEggLookups(source: String): String {
    if (!source.contains("DeferredSpawnEggItem.fromEntityType(")) return source

    var changed = false
    var index = 0
    val expr = """((?:[^()]|\([^()]*\))+)"""
    val pattern = Regex(
        """(?m)^([ \t]*)(.+?\.addInvisibleIngredients\([^\r\n;]+\))\.addItemStack\(\s*new\s+ItemStack\(\s*DeferredSpawnEggItem\.fromEntityType\(\s*$expr\s*\)\s*\)\s*\)\s*;\s*$"""
    )
    var result = pattern.replace(source) { match ->
        changed = true
        val indent = match.groupValues[1]
        val receiver = match.groupValues[2].trim()
        val entityType = match.groupValues[3].trim()
        val variable = "spawnEggItem${index++}"
        """${indent}SpawnEggItem $variable = SpawnEggItem.byId($entityType);
${indent}if ($variable != null) {
${indent}    $receiver.addItemStack(new ItemStack($variable));
${indent}}"""
    }

    if (!changed) {
        result = Regex("""DeferredSpawnEggItem\.fromEntityType\(\s*$expr\s*\)""")
            .replace(result) { match ->
                changed = true
                "SpawnEggItem.byId(${match.groupValues[1].trim()})"
            }
    }
    if (!changed) return source

    result = addImportIfMissing(result, "net.minecraft.world.item.SpawnEggItem")
    if (!result.contains("DeferredSpawnEggItem.")) {
        result = removeImport(result, "net.neoforged.neoforge.common.DeferredSpawnEggItem")
        result = removeImport(result, "net.minecraftforge.common.ForgeSpawnEggItem")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEntityTypeSpawnCompoundTagSource(source: String): String {
    if (!source.contains(".spawn(") || !source.contains("CompoundTag")) return source

    var changed = false
    val spawnPattern = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.spawn\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([^,]+?)\s*,\s*([^,]+?)\s*,\s*([^,]+?)\s*,\s*([^,]+?)\s*,\s*([^)]+?)\s*\)\s*;"""
    )
    val result = spawnPattern.replace(source) { match ->
        val methodRange = enclosingMethodRange(source, match.range.first) ?: return@replace match.value
        val methodPrefix = source.substring(methodRange.first, match.range.first)
        val tagVariable = match.groupValues[6]
        if (!Regex("""\bCompoundTag\s+${Regex.escape(tagVariable)}\s*=""").containsMatchIn(methodPrefix)) {
            return@replace match.value
        }
        val itemStackVariables = Regex("""\bItemStack\s+([A-Za-z_$][\w$]*)\s*=""")
            .findAll(methodPrefix)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        if (itemStackVariables.size != 1) return@replace match.value
        val indent = match.groupValues[1]
        val entityType = match.groupValues[2]
        val entityVariable = match.groupValues[3]
        val spawnReceiver = match.groupValues[4]
        val level = match.groupValues[5]
        val player = match.groupValues[7].trim()
        val pos = match.groupValues[8].trim()
        val spawnType = match.groupValues[9].trim()
        val shouldOffsetY = match.groupValues[10].trim()
        val shouldOffsetYMore = match.groupValues[11].trim()
        val itemStackVariable = itemStackVariables.single()
        changed = true
        buildString {
            appendLine("${indent}$entityType $entityVariable = $spawnReceiver.spawn($level,")
            appendLine("${indent}        net.minecraft.world.entity.EntityType.appendDefaultStackConfig(consumerEntity -> {")
            appendLine("${indent}            if ($tagVariable != null && consumerEntity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {")
            appendLine("${indent}                livingEntity.readAdditionalSaveData($tagVariable);")
            appendLine("${indent}            }")
            appendLine("${indent}        }, $level, $itemStackVariable, $player),")
            append("${indent}        $pos, $spawnType, $shouldOffsetY, $shouldOffsetYMore);")
        }
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.enclosingMethodRange(source: String, index: Int): IntRange? {
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|static|final|synchronized)\s+)*(?:<[^>{};]+>\s*)?[A-Za-z_$][\w$<>\[\].?,\s]*\s+[A-Za-z_$][\w$]*\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{"""
    )
    var best: IntRange? = null
    methodPattern.findAll(source).forEach { match ->
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (closeBrace > openBrace && index in (openBrace + 1) until closeBrace) {
            val candidate = (openBrace + 1) until closeBrace
            if (best == null || candidate.count() < best!!.count()) {
                best = candidate
            }
        }
    }
    return best
}

internal fun StructuralRefactorPass.enclosingMethodText(source: String, index: Int): String? {
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|static|final|synchronized|default|abstract)\s+)*(?:<[^>{};]+>\s*)?[A-Za-z_$][\w$<>\[\].?,\s]*\s+[A-Za-z_$][\w$]*\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{"""
    )
    var best: IntRange? = null
    methodPattern.findAll(source).forEach { match ->
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (closeBrace > openBrace && index in (openBrace + 1) until closeBrace) {
            val candidate = match.range.first..closeBrace
            if (best == null || candidate.count() < best!!.count()) {
                best = candidate
            }
        }
    }
    return best?.let { source.substring(it) }
}

internal fun StructuralRefactorPass.uniqueLocalNameInScope(scope: String, baseName: String): String {
    if (!Regex("""\b${Regex.escape(baseName)}\b""").containsMatchIn(scope)) return baseName
    var index = 1
    while (Regex("""\b${Regex.escape(baseName + index)}\b""").containsMatchIn(scope)) {
        index++
    }
    return baseName + index
}

internal fun StructuralRefactorPass.migrateEnchantmentLevelHolderLookups(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains(".getEnchantmentLevel(Enchantments.") &&
        !Regex("""\.getEnchantmentLevel\(\s*(?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+\.get\(\)\s*\)""")
            .containsMatchIn(source)) {
        return source
    }

    var changed = false
    var result = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^)]*\))?)*)\.getEnchantmentLevel\(Enchantments\.([A-Z0-9_]+)\)""")
        .replace(source) { match ->
            val stack = match.groupValues[1]
            val enchantment = match.groupValues[2]
            changed = true
            val registryAccess = registryAccessExpressionAt(source, match.range.first, javaInheritanceIndex)
                ?: return@replace "$stack.getEnchantmentLevel(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.$enchantment))"
            """$registryAccess.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .flatMap(registry -> registry.get(Enchantments.$enchantment))
                    .map(holder -> net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(holder, $stack))
                    .orElse(0)"""
        }
    result = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^)]*\))?)*)\.getEnchantmentLevel\(\s*((?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+)\.get\(\)\s*\)""")
        .replace(result) { match ->
            val stack = match.groupValues[1]
            val enchantmentKey = match.groupValues[2]
            changed = true
            val registryAccess = registryAccessExpressionAt(result, match.range.first, javaInheritanceIndex)
                ?: return@replace "$stack.getEnchantmentLevel(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow($enchantmentKey))"
            """$registryAccess.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .flatMap(registry -> registry.get($enchantmentKey))
                    .map(holder -> net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(holder, $stack))
                    .orElse(0)"""
        }
    if (changed) {
        result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.EnchantmentHelper")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacySweepingDamageRatioCalls(source: String): String {
    if (!source.contains("EnchantmentHelper.getSweepingDamageRatio(")) return source
    var result = Regex("""EnchantmentHelper\.getSweepingDamageRatio\(\s*([A-Za-z_$][\w$]*)\s*\)""")
        .replace(source) { match ->
            "(float) ${match.groupValues[1]}.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO)"
        }
    if (result != source) {
        result = addImportIfMissing(result, "net.minecraft.world.entity.ai.attributes.Attributes")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEnchantmentHelperCalls(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("Enchantments.") &&
        !source.contains("EnchantmentHelper.hasFrostWalker(") &&
        !source.contains("EnchantmentHelper.hasChanneling(") &&
        !source.contains("EnchantmentHelper.getEnchantmentLevel(") &&
        !source.contains("EnchantmentHelper.getDepthStrider(") &&
        !source.contains("EnchantmentHelper.getFireAspect(") &&
        !source.contains("EnchantmentHelper.getItemEnchantmentLevel(") &&
        !source.contains("EnchantmentHelper.getTagEnchantmentLevel(")) {
        return source
    }

    var result = migrateLegacyEnchantmentConstantNames(source)

    if (result.contains("EnchantmentHelper.hasFrostWalker(")) {
        result = Regex("""EnchantmentHelper\.hasFrostWalker\(\s*([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match ->
                val living = match.groupValues[1]
                "EnchantmentHelper.hasTag($living.getItemBySlot(EquipmentSlot.FEET), EnchantmentTags.PREVENTS_ICE_MELTING)"
            }
        result = addImportIfMissing(result, "net.minecraft.tags.EnchantmentTags")
        result = addImportIfMissing(result, "net.minecraft.world.entity.EquipmentSlot")
    }
    if (result.contains("EnchantmentHelper.hasChanneling(")) {
        result = Regex("""EnchantmentHelper\.hasChanneling\(\s*([^)]+?)\s*\)""")
            .replace(result) { match ->
                "EnchantmentHelper.getItemEnchantmentLevel(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.CHANNELING), ${match.groupValues[1].trim()}) > 0"
            }
        result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.Enchantments")
    }

    val hasMigratableEnchantmentLookup =
        result.contains("EnchantmentHelper.getTagEnchantmentLevel(Enchantments.") ||
            result.contains("EnchantmentHelper.getItemEnchantmentLevel(Enchantments.") ||
            Regex("""EnchantmentHelper\.(?:getTagEnchantmentLevel|getItemEnchantmentLevel)\(\s*(?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+\.get\(\)\s*,""")
                .containsMatchIn(result)
    if (!hasMigratableEnchantmentLookup) {
        return result
    }

    result = migrateLegacyEnchantmentLevelHelperCall(result, "getTagEnchantmentLevel", javaInheritanceIndex)
    result = migrateLegacyEnchantmentLevelHelperCall(result, "getItemEnchantmentLevel", javaInheritanceIndex)
    result = migrateLegacyEnchantmentResourceKeyHelperCall(result, "getTagEnchantmentLevel")
    result = migrateLegacyEnchantmentResourceKeyHelperCall(result, "getItemEnchantmentLevel")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEntityEnchantmentHelperCalls(source: String): String {
    if (!source.contains("EnchantmentHelper.getEnchantmentLevel(") &&
        !source.contains("EnchantmentHelper.getDepthStrider(") &&
        !source.contains("EnchantmentHelper.getFireAspect(")) {
        return source
    }

    var changed = false
    var needsEnchantments = false

    fun enchantmentHolderLookup(enchantment: String): String? {
        val trimmed = enchantment.trim()
        if (Regex("""^Enchantments\.[A-Z0-9_]+$""").matches(trimmed)) {
            return "net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow($trimmed)"
        }
        val resourceKeyMatch = Regex("""^((?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+)\.get\(\)$""").find(trimmed)
        if (resourceKeyMatch != null) {
            return "net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(${resourceKeyMatch.groupValues[1]})"
        }
        return null
    }

    fun rewriteFullInvocation(input: String, methodName: String, transform: (args: List<String>) -> String?): String {
        var result = input
        var cursor = 0
        val token = "$methodName("
        while (true) {
            val tokenIndex = result.indexOf(token, cursor)
            if (tokenIndex < 0) break
            if (tokenIndex > 0 && (result[tokenIndex - 1].isLetterOrDigit() || result[tokenIndex - 1] == '_' || result[tokenIndex - 1] == '$')) {
                cursor = tokenIndex + token.length
                continue
            }
            val openParen = tokenIndex + methodName.length
            val closeParen = findMatchingParen(result, openParen)
            if (closeParen < 0) {
                cursor = tokenIndex + token.length
                continue
            }
            val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
            val replacement = transform(args)
            if (replacement == null) {
                cursor = closeParen + 1
                continue
            }
            result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
            cursor = tokenIndex + replacement.length
        }
        return result
    }

    var result = rewriteFullInvocation(source, "EnchantmentHelper.getEnchantmentLevel") { args ->
        if (args.size != 2) return@rewriteFullInvocation null
        val holderLookup = enchantmentHolderLookup(args[0]) ?: return@rewriteFullInvocation null
        changed = true
        "EnchantmentHelper.getEnchantmentLevel($holderLookup, ${args[1].trim()})"
    }

    result = rewriteFullInvocation(result, "EnchantmentHelper.getDepthStrider") { args ->
        if (args.size != 1) return@rewriteFullInvocation null
        val holderLookup = enchantmentHolderLookup("Enchantments.DEPTH_STRIDER") ?: return@rewriteFullInvocation null
        changed = true
        needsEnchantments = true
        "EnchantmentHelper.getEnchantmentLevel($holderLookup, ${args[0].trim()})"
    }

    result = rewriteFullInvocation(result, "EnchantmentHelper.getFireAspect") { args ->
        if (args.size != 1) return@rewriteFullInvocation null
        val holderLookup = enchantmentHolderLookup("Enchantments.FIRE_ASPECT") ?: return@rewriteFullInvocation null
        changed = true
        needsEnchantments = true
        "EnchantmentHelper.getEnchantmentLevel($holderLookup, ${args[0].trim()})"
    }

    if (!changed) return source
    if (needsEnchantments) {
        result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.Enchantments")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyTridentAndFishingEnchantmentHelpers(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("EnchantmentHelper.getRiptide(") &&
        !executableCode.contains("EnchantmentHelper.getLoyalty(") &&
        !executableCode.contains("EnchantmentHelper.getFishingSpeedBonus(") &&
        !executableCode.contains("EnchantmentHelper.getFishingLuckBonus(")) {
        return source
    }

    var result = source
    var changed = false
    for (method in javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result)).asReversed()) {
        val methodText = result.substring(method.range)
        if (!methodText.contains("EnchantmentHelper.getRiptide(") &&
            !methodText.contains("EnchantmentHelper.getFishingSpeedBonus(") &&
            !methodText.contains("EnchantmentHelper.getFishingLuckBonus(")) {
            continue
        }
        val params = javaMethodParameters(methodText)
        val levelParam = params.singleOrNull { simpleJavaTypeName(it.type) == "Level" }?.name
        val playerParam = params.singleOrNull { simpleJavaTypeName(it.type) == "Player" }?.name
        var replacement = methodText

        if (levelParam != null && playerParam != null &&
            (replacement.contains("EnchantmentHelper.getFishingSpeedBonus(") ||
                replacement.contains("EnchantmentHelper.getFishingLuckBonus("))) {
            replacement = rewriteLegacyFishingEnchantmentHelpersInMethod(replacement, levelParam, playerParam)
        }

        if (replacement.contains("EnchantmentHelper.getRiptide(")) {
            replacement = rewriteLegacyRiptideHelpersInMethod(replacement, params)
        }

        if (replacement != methodText) {
            result = result.substring(0, method.range.first) + replacement + result.substring(method.range.last + 1)
            changed = true
        }
    }

    if (result.contains("EnchantmentHelper.getLoyalty(") && result.contains("extends AbstractArrow")) {
        val migrated = rewriteLegacyAbstractArrowLoyaltyHelpers(result)
        if (migrated != result) {
            result = migrated
            changed = true
        }
    }

    return if (changed) result else source
}

internal fun StructuralRefactorPass.rewriteLegacyFishingEnchantmentHelpersInMethod(methodText: String, level: String, player: String): String {
    var result = methodText
    val serverLevel = "(net.minecraft.server.level.ServerLevel) $level"
    result = rewriteExecutableJavaInvocationArgumentsWithOffset(result, "EnchantmentHelper.getFishingLuckBonus") { args, offset ->
        if (args.size != 1 || !hasServerSideGuard(result.substring(0, offset), level)) {
            return@rewriteExecutableJavaInvocationArgumentsWithOffset null
        }
        listOf(serverLevel, args[0].trim(), player)
    }
    result = rewriteExecutableJavaInvocationWithOffset(result, "EnchantmentHelper.getFishingSpeedBonus") { args, offset ->
        if (args.size != 1 || !hasServerSideGuard(result.substring(0, offset), level)) {
            return@rewriteExecutableJavaInvocationWithOffset null
        }
        "(int)(EnchantmentHelper.getFishingTimeReduction($serverLevel, ${args[0].trim()}, $player) * 20.0F)"
    }
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyRiptideHelpersInMethod(methodText: String, params: List<JavaParameter>): String =
    rewriteExecutableJavaInvocationWithOffset(methodText, "EnchantmentHelper.getRiptide") { args, offset ->
        if (args.size != 1) return@rewriteExecutableJavaInvocationWithOffset null
        val player = sourceProvenPlayerExpression(methodText.substring(0, offset), params)
            ?: return@rewriteExecutableJavaInvocationWithOffset null
        "net.minecraft.util.Mth.floor(EnchantmentHelper.getTridentSpinAttackStrength(${args[0].trim()}, $player))"
    }

internal fun StructuralRefactorPass.sourceProvenPlayerExpression(prefix: String, params: List<JavaParameter>): String? {
    val patternPlayers = Regex("""instanceof\s+(?:net\.minecraft\.world\.entity\.player\.)?Player\s+([A-Za-z_$][\w$]*)""")
        .findAll(prefix)
        .map { it.groupValues[1] }
        .toList()
    if (patternPlayers.isNotEmpty()) return patternPlayers.last()
    return params.singleOrNull { simpleJavaTypeName(it.type) == "Player" }?.name
}

internal fun StructuralRefactorPass.rewriteLegacyAbstractArrowLoyaltyHelpers(source: String): String {
    var result = rewriteExecutableJavaInvocationWithOffset(source, "EnchantmentHelper.getLoyalty") { args, _ ->
        if (args.size == 1) "getLoyaltyFromItem(${args[0].trim()})" else null
    }
    if (result == source || result.contains("getLoyaltyFromItem(ItemStack stack)")) {
        return result
    }
    val classEnd = result.lastIndexOf('}')
    if (classEnd < 0) return source
    val helper = """

    private byte getLoyaltyFromItem(ItemStack stack) {
        return this.level() instanceof ServerLevel serverlevel
            ? (byte)Mth.clamp(EnchantmentHelper.getTridentReturnToOwnerAcceleration(serverlevel, stack, this), 0, 127)
            : 0;
    }
""".trimEnd()
    result = result.substring(0, classEnd) + helper + "\n" + result.substring(classEnd)
    result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    result = addImportIfMissing(result, "net.minecraft.util.Mth")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyStartAutoSpinAttackCalls(source: String): String {
    if (!source.contains(".startAutoSpinAttack(")) return source
    var result = source
    var changed = false
    for (method in javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result)).asReversed()) {
        val methodText = result.substring(method.range)
        if (!methodText.contains(".startAutoSpinAttack(")) continue
        val stack = javaMethodParameters(methodText)
            .singleOrNull { simpleJavaTypeName(it.type) == "ItemStack" }
            ?.name
            ?: continue
        val replacement = rewriteExecutableJavaCall(methodText, "startAutoSpinAttack") { receiver, args ->
            if (args.size == 1) {
                "$receiver.startAutoSpinAttack(${args[0].trim()}, 8.0F, $stack)"
            } else {
                null
            }
        }
        if (replacement != methodText) {
            result = result.substring(0, method.range.first) + replacement + result.substring(method.range.last + 1)
            changed = true
        }
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateLegacyEnchantmentConstantNames(source: String): String {
    if (!maskJavaCommentsAndLiterals(source).contains("Enchantments.")) return source
    val renamedEnchantments = mapOf(
        "ALL_DAMAGE_PROTECTION" to "PROTECTION",
        "FALL_PROTECTION" to "FEATHER_FALLING",
        "FIRE_PROTECTION" to "FIRE_PROTECTION",
        "BLAST_PROTECTION" to "BLAST_PROTECTION",
        "PROJECTILE_PROTECTION" to "PROJECTILE_PROTECTION",
        "RESPIRATION" to "RESPIRATION",
        "AQUA_AFFINITY" to "AQUA_AFFINITY",
        "THORNS" to "THORNS",
        "DEPTH_STRIDER" to "DEPTH_STRIDER",
        "FROST_WALKER" to "FROST_WALKER",
        "BINDING_CURSE" to "BINDING_CURSE",
        "SOUL_SPEED" to "SOUL_SPEED",
        "SWIFT_SNEAK" to "SWIFT_SNEAK",
        "DAMAGE_ALL" to "SHARPNESS",
        "DAMAGE_UNDEAD" to "SMITE",
        "DAMAGE_ARTHROPODS" to "BANE_OF_ARTHROPODS",
        "KNOCKBACK" to "KNOCKBACK",
        "FIRE_ASPECT" to "FIRE_ASPECT",
        "MOB_LOOTING" to "LOOTING",
        "SWEEPING_EDGE" to "SWEEPING_EDGE",
        "BLOCK_EFFICIENCY" to "EFFICIENCY",
        "DIG_SPEED" to "EFFICIENCY",
        "SILK_TOUCH" to "SILK_TOUCH",
        "DURABILITY" to "UNBREAKING",
        "BLOCK_FORTUNE" to "FORTUNE",
        "LOOT_BONUS_BLOCKS" to "FORTUNE",
        "LOOT_BONUS_MOBS" to "LOOTING",
        "ARROW_DAMAGE" to "POWER",
        "POWER_ARROWS" to "POWER",
        "ARROW_KNOCKBACK" to "PUNCH",
        "PUNCH_ARROWS" to "PUNCH",
        "ARROW_FIRE" to "FLAME",
        "FLAMING_ARROWS" to "FLAME",
        "ARROW_INFINITE" to "INFINITY",
        "INFINITY_ARROWS" to "INFINITY",
        "MENDING" to "MENDING",
        "VANISHING_CURSE" to "VANISHING_CURSE"
    )
    var result = source
    for ((oldName, newName) in renamedEnchantments) {
        if (oldName != newName) {
            result = replaceExecutableJavaRegex(result, Regex("""\bEnchantments\.${Regex.escape(oldName)}\b""")) {
                "Enchantments.$newName"
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEnchantmentLevelHelperCall(
    source: String,
    methodName: String,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    val token = "EnchantmentHelper.$methodName("
    if (!source.contains(token)) return source
    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    while (cursor < source.length) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + "EnchantmentHelper.$methodName".length
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        val enchantment = args.getOrNull(0)?.trim()
        val stack = args.getOrNull(1)?.trim()
        val enchantmentMatch = enchantment?.let { Regex("""^Enchantments\.([A-Z0-9_]+)$""").find(it) }
        val resourceKeyMatch = enchantment?.let { Regex("""^((?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+)\.get\(\)$""").find(it) }
        val registryAccess = registryAccessExpressionAt(source, tokenIndex, javaInheritanceIndex)
        if (args.size == 2 && enchantmentMatch != null && stack != null) {
            if (registryAccess == null) {
                migrated.append(source, cursor, closeParen + 1)
                cursor = closeParen + 1
                continue
            }
            val enchantmentName = enchantmentMatch.groupValues[1]
            migrated.append(source, cursor, tokenIndex)
            migrated.append(
                """$registryAccess.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .flatMap(registry -> registry.get(Enchantments.$enchantmentName))
                    .map(holder -> EnchantmentHelper.$methodName(holder, $stack))
                    .orElse(0)"""
            )
            cursor = closeParen + 1
            changed = true
            continue
        }
        if (args.size == 2 && resourceKeyMatch != null && stack != null) {
            if (registryAccess == null) {
                migrated.append(source, cursor, closeParen + 1)
                cursor = closeParen + 1
                continue
            }
            val enchantmentKey = resourceKeyMatch.groupValues[1]
            migrated.append(source, cursor, tokenIndex)
            migrated.append(
                """$registryAccess.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .flatMap(registry -> registry.get($enchantmentKey))
                    .map(holder -> EnchantmentHelper.$methodName(holder, $stack))
                    .orElse(0)"""
            )
            cursor = closeParen + 1
            changed = true
            continue
        }
        migrated.append(source, cursor, closeParen + 1)
        cursor = closeParen + 1
    }
    if (!changed) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.migrateLegacyEnchantmentResourceKeyHelperCall(source: String, methodName: String): String {
    val token = "EnchantmentHelper.$methodName("
    if (!source.contains(token)) return source
    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    while (cursor < source.length) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + "EnchantmentHelper.$methodName".length
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        val enchantment = args.getOrNull(0)?.trim()
        val stack = args.getOrNull(1)?.trim()
        val resourceKeyMatch = enchantment?.let { Regex("""^((?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+)\.get\(\)$""").find(it) }
        if (args.size == 2 && resourceKeyMatch != null && stack != null) {
            val enchantmentKey = resourceKeyMatch.groupValues[1]
            migrated.append(source, cursor, tokenIndex)
            migrated.append(
                "EnchantmentHelper.$methodName(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow($enchantmentKey), $stack)"
            )
            cursor = closeParen + 1
            changed = true
            continue
        }
        migrated.append(source, cursor, closeParen + 1)
        cursor = closeParen + 1
    }
    if (!changed) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.registryAccessExpressionForEnclosingMethod(source: String, offset: Int): String? {
    return registryAccessFromParameters(currentMethodParametersBeforeOffset(source, offset))
}

internal fun StructuralRefactorPass.currentMethodParametersBeforeOffset(source: String, offset: Int): List<String> {
    val methodPattern = Regex(
        """(?m)(?:^|\r?\n)[ \t]*(?:(?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s*)*)(?:(?:public|protected|private|static|final|synchronized|default)\s+)*[A-Za-z_$][\w$<>\[\].?,\s]*\s+[A-Za-z_$][\w$]*\s*\(([^()]*)\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    val methodParameters = methodPattern.findAll(source)
        .filter { it.range.first < offset }
        .lastOrNull { match ->
            val openBrace = source.indexOf('{', match.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            closeBrace >= offset
        }
        ?.groupValues
        ?.get(1)
        ?.let { splitTopLevelJavaArgs(it) }
    if (methodParameters != null) return methodParameters

    val executableCode = maskJavaCommentsAndLiterals(source)
    val enclosingType = javaTypeBlocks(source, executableCode)
        .lastOrNull { offset in it.bodyStart..it.end }
        ?: return emptyList()
    val constructorPattern = Regex(
        """(?m)(?:^|\r?\n)[ \t]*(?:(?:@[^\r\n]+\r?\n[ \t]*)*)(?:public|protected|private)?\s*${Regex.escape(enclosingType.name)}\s*\(([^()]*)\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    return constructorPattern.findAll(executableCode)
        .filter { it.range.first < offset && it.range.first in enclosingType.bodyStart..enclosingType.end }
        .lastOrNull { match ->
            val openBrace = executableCode.indexOf('{', match.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
            closeBrace >= offset
        }
        ?.groupValues
        ?.get(1)
        ?.let { splitTopLevelJavaArgs(it) }
        ?: emptyList()
}

internal fun StructuralRefactorPass.registryAccessFromParameters(parameters: List<String>): String? {
    holderLookupProviderFromParameters(parameters)?.let { return it }
    return registryAccessFromParameters(parameters, JavaInheritanceIndex.EMPTY)
}

internal fun StructuralRefactorPass.registryAccessFromParameters(
    parameters: List<String>,
    javaInheritanceIndex: JavaInheritanceIndex
): String? {
    val declaredProviders = holderLookupProviderNamesFromParameters(parameters)
    if (declaredProviders.size == 1) return declaredProviders.single()
    if (declaredProviders.size > 1) return null
    val tooltipContextRegistryAccess = parameters.mapNotNull { parameter ->
        Regex("""(?:final\s+)?(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*(?:(?:net\.minecraft\.world\.item\.)?Item\.)?TooltipContext\s+([A-Za-z_$][\w$]*)$""")
            .find(parameter.trim())
            ?.groupValues
            ?.get(1)
            ?.let { "$it.registries()" }
    }.distinct()
    val lootParamsRegistryAccess = parameters.mapNotNull { parameter ->
        Regex("""(?:final\s+)?(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*(?:net\.minecraft\.world\.level\.storage\.loot\.)?LootParams\.Builder\s+([A-Za-z_$][\w$]*)$""")
            .find(parameter.trim())
            ?.groupValues
            ?.get(1)
            ?.let { "$it.getLevel().registryAccess()" }
    }.distinct()
    val parameterPattern = Regex("""(?:final\s+)?(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:<[^>]+>)?)\s+([A-Za-z_$][\w$]*)$""")
    val parsed = parameters.mapNotNull { parameter ->
        val cleaned = parameter.trim()
            .replace(Regex("""\s+"""), " ")
        parameterPattern.find(cleaned)?.let { it.groupValues[1].substringAfterLast('.') to it.groupValues[2] }
    }
    val explicitRegistryAccess = parsed
        .filter { it.first == "RegistryAccess" }
        .map { it.second }
        .distinct()

    // a registry buffer carries the registries it encodes against (RegistryFriendlyByteBuf#registryAccess)
    val bufferRegistryAccess = parsed
        .filter { it.first == "RegistryFriendlyByteBuf" }
        .map { "${it.second}.registryAccess()" }
        .distinct()

    val carriedLevelRegistryAccess = parsed
        .mapNotNull { (type, name) -> projectLevelCarriers[type]?.let { "$name$it.registryAccess()" } }
        .distinct()

    val levelRegistryAccess = parsed
        .filter { it.first in setOf("ServerLevel", "Level", "WorldGenLevel", "ServerLevelAccessor", "LevelAccessor", "LevelReader") }
        .map { "${it.second}.registryAccess()" }
        .distinct()

    val inventoryRegistryAccess = parsed
        .filter { it.first == "Inventory" }
        .map { "${it.second}.player.registryAccess()" }
        .distinct()

    // EntityEvent#getEntity is the live entity carried by player/living interaction events.
    // Resolve only unambiguous platform names, keeping same-named project classes out.
    val eventRegistryAccess = parsed.mapNotNull { (type, name) ->
        if (javaInheritanceIndex.isProjectType(type)) return@mapNotNull null
        val owners = com.modporter.mapping.TargetMembers.classesNamed(type)
        if (owners.isNotEmpty() && owners.all {
                com.modporter.mapping.TargetMembers.inheritsFrom(it, "net.neoforged.neoforge.event.entity.EntityEvent")
            }) "$name.getEntity().registryAccess()" else null
    }.distinct()

    val entityRegistryAccess = parsed
        .filter { (type, _) ->
            type in javaEntityBaseTypes() ||
                javaInheritanceIndex.inherits(type, javaEntityBaseTypes())
        }
        .map { "${it.second}.registryAccess()" }
        .distinct()
    return (
        tooltipContextRegistryAccess +
            lootParamsRegistryAccess +
            explicitRegistryAccess +
            bufferRegistryAccess +
            carriedLevelRegistryAccess +
            levelRegistryAccess +
            inventoryRegistryAccess +
            eventRegistryAccess +
            entityRegistryAccess
        ).distinct().firstOrNull()
}

internal fun StructuralRefactorPass.holderLookupProviderFromParameters(parameters: List<String>): String? {
    val providers = holderLookupProviderNamesFromParameters(parameters)
    return providers.singleOrNull()
}

internal fun StructuralRefactorPass.holderLookupProviderNamesAt(source: String, offset: Int): Set<String> =
    exactProviderJavaMethodAt(source, offset)
        ?.parameters
        .orEmpty()
        .mapNotNull { (type, name) ->
            name.takeIf {
                type == "HolderLookup.Provider" ||
                    type == "net.minecraft.core.HolderLookup.Provider"
            }
        }
        .toSet()

internal fun StructuralRefactorPass.holderLookupProviderNamesFromParameters(parameters: List<String>): List<String> {
    val parameterPattern = Regex("""(?:final\s+)?(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s+([A-Za-z_$][\w$]*)$""")
    val providers = parameters.mapNotNull { parameter ->
        val cleaned = parameter.trim()
            .replace(Regex("""\s+"""), " ")
        val match = parameterPattern.find(cleaned) ?: return@mapNotNull null
        val type = match.groupValues[1]
        if (type == "HolderLookup.Provider" || type == "net.minecraft.core.HolderLookup.Provider") {
            match.groupValues[2]
        } else {
            null
        }
    }.distinct()
    return providers
}

internal fun StructuralRefactorPass.registryAccessExpressionAt(
    source: String,
    offset: Int,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String? {
    exactExternalCallbackProviderExpressionAt(source, offset, javaInheritanceIndex)?.let { return it }
    holderLookupProviderNamesAt(source, offset).singleOrNull()?.let { return it }
    val parameters = currentMethodParametersBeforeOffset(source, offset)
    registryAccessFromParameters(parameters, javaInheritanceIndex)?.let { return it }
    // a level or guarded entity bound where the call stands is closer than a carrier parameter
    sourceProvenLocalRegistryAccessExpressionAt(source, offset)?.let { return it }
    guardedEntityPatternRegistryAccessAt(source, offset)?.let { return it }
    LibraryProviderCarriers.fromParameters(parameters, source)?.let { return it }
    clientMinecraftRegistryAccessExpression(source, offset)?.let { return it }
    if (isInsideStaticJavaMethod(source, offset)) return null
    LibraryProviderCarriers.fromEnclosingTypes(source, offset)?.let { return it }
    val declaration = enclosingJavaClassDeclaration(source, offset)
    val selfRegistryAccess = entitySelfRegistryAccessExpression(declaration, javaInheritanceIndex)
    // Entity instances always carry their constructor-bound level. Prefer that stable owner over a
    // merely declared entity field, which may be nullable or not initialized at this call site.
    if (selfRegistryAccess == "this.registryAccess()") return selfRegistryAccess
    sourceProvenInheritedRegistryAccessExpressionAt(source, offset, javaInheritanceIndex)?.let { return it }
    return selfRegistryAccess
}

internal fun StructuralRefactorPass.sourceProvenLocalRegistryAccessExpressionAt(source: String, offset: Int): String? {
    val methodRange = enclosingMethodRange(source, offset) ?: return null
    val methodText = source.substring(methodRange)
    val offsetInMethod = offset - methodRange.first
    val prefix = methodText.take(offsetInMethod)
    val localLevels = Regex("""\b(?:net\.minecraft\.world\.level\.)?(?:Level|ServerLevel)\s+([A-Za-z_$][\w$]*)\s*=""")
        .findAll(prefix)
        .map { it.groupValues[1] }
        .filter { level -> hasClientSideEarlyReturn(prefix, level) || hasServerSideGuard(prefix, level) }
        .distinct()
        .toList()
    return localLevels.singleOrNull()?.let { "$it.registryAccess()" }
}

/**
 * Whether every NBT-shaped call in [methodRange] (`ItemStack.of`, `save`/`serializeNBT`/`load`,
 * `Component.Serializer` json) has a registry source in scope where it stands - for instance
 * after a `ServerPlayer` guard inside a handler lambda - so the call-site rewrites can use it and
 * the method itself does not need the provider.
 */
internal fun StructuralRefactorPass.nbtCallsHaveRegistryAccess(
    source: String,
    methodRange: IntRange,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): Boolean {
    val methodText = maskJavaCommentsAndLiterals(source.substring(methodRange))
    val sites = Regex("""(?<![\w$])ItemStack(?:\.of\(|::of\b)|\.(?:save|saveOptional|serializeNBT|deserializeNBT|load|readFromNBT|writeToNBT)\s*\(|\bComponent\.Serializer\.(?:fromJson|toJson)\(""")
        .findAll(methodText)
        .map { methodRange.first + it.range.first }
        .toList()
    return sites.isNotEmpty() && sites.all { registryAccessExpressionAt(source, it, javaInheritanceIndex) != null }
}

/**
 * `if (!(expression instanceof ServerPlayer player)) return;` binds `player` for the rest of the
 * block holding the guard, and every entity reaches its level's registries through `registryAccess()`.
 */
internal fun StructuralRefactorPass.guardedEntityPatternRegistryAccessAt(source: String, offset: Int): String? {
    if (!source.contains(" instanceof ")) return null
    val masked = maskJavaCommentsAndLiterals(source)
    val entityTypes = javaEntityBaseTypes().joinToString("|") { Regex.escape(it) }
    val guard = Regex(
        """\bif\s*\(\s*!\s*\(\s*(?:[^()]|\([^()]*\))+?\s+instanceof\s+(?:$entityTypes)\s+([A-Za-z_$][\w$]*)\s*\)\s*\)\s*(?:\{\s*return\b[^;{}]*;\s*\}|return\b[^;{}]*;)"""
    )
    return guard.findAll(masked.substring(0, offset.coerceAtMost(masked.length))).lastOrNull { match ->
        var depth = 0
        (match.range.last + 1 until offset).all { index ->
            when (masked[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            depth >= 0
        }
    }?.let { "${it.groupValues[1]}.registryAccess()" }
}

internal fun StructuralRefactorPass.isInsideStaticJavaMethod(source: String, offset: Int): Boolean {
    val method = javaMethodRanges(source).firstOrNull { offset in it.range }
    return if (method != null) Regex("""\bstatic\b""").containsMatchIn(method.header)
        else staticRegistryMemberAt(source, offset)
}

/**
 * The client level's registries, only where the code provably runs on the client: the enclosing
 * method itself reaches `Minecraft.getInstance()` (it could not run on a dedicated server anyway), or
 * an enclosing class is `@OnlyIn(Dist.CLIENT)` or an event subscriber limited to `Dist.CLIENT`.
 * Merely mentioning the client `Minecraft` class elsewhere in the file proves nothing about a
 * server-side method beside it.
 */
internal fun StructuralRefactorPass.clientMinecraftRegistryAccessExpression(source: String, offset: Int): String? {
    if (!source.contains("Minecraft")) return null
    val executableCode = maskJavaCommentsAndLiterals(source)
    val method = enclosingMethodRange(executableCode, offset)
    val methodUsesClient = method != null &&
        Regex("""(?<![\w$.])(?:net\.minecraft\.client\.)?Minecraft\.getInstance\(\)""").containsMatchIn(executableCode.substring(method))
    val clientOnlyClass = Regex("""@(?:OnlyIn\s*\(\s*(?:value\s*=\s*)?Dist\.CLIENT\s*\)|(?:Mod\.)?EventBusSubscriber\s*\([^)]*\bDist\.CLIENT\b[^)]*\))(?:\s*@[\w.]+(?:\([^)]*\))?)*\s*(?:(?:public|protected|private|static|final|abstract)\s+)*(?:class|interface|enum|record)\s+[A-Za-z_$][\w$]*[^{]*\{""")
        .findAll(executableCode)
        .any { header ->
            val open = header.range.last
            val close = findMatchingBrace(executableCode, open)
            close > open && offset in open..close
        }
    return if (methodUsesClient || clientOnlyClass) "net.minecraft.client.Minecraft.getInstance().level.registryAccess()" else null
}

internal fun StructuralRefactorPass.migrateLegacyDamageBonusMobTypeCalls(source: String): String {
    if (!source.contains("EnchantmentHelper.getDamageBonus(") || !source.contains(".getMobType()")) return source

    var result = migrateLegacyProjectileDamageBonusWithFollowingDamageSource(source)
    var changed = result != source
    val methodNames = Regex("""(?m)^[ \t]*(?:@Override\s*)?(?:public|protected|private)\s+[\w<>\[\].?,\s]+\s+([A-Za-z_$][\w$]*)\s*\(""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toList()
    for (methodName in methodNames) {
        val methodText = javaDeclaredMethodText(result, methodName) ?: continue
        if (!methodText.contains("EnchantmentHelper.getDamageBonus(") || !methodText.contains(".getMobType()")) continue

        val serverLevelExpr = sourceProvenServerLevelExpressionForDamageBonus(methodText) ?: continue
        var replacement = methodText
        val baseDamageVariable = Regex("""\b(?:float|double)\s+([A-Za-z_$][\w$]*)\s*=\s*[^;\r\n]*Attributes\.ATTACK_DAMAGE[^;\r\n]*;""")
            .find(methodText)
            ?.groupValues
            ?.get(1)
        if (baseDamageVariable != null) {
            replacement = Regex(
                """EnchantmentHelper\.getDamageBonus\(\s*([^,]+?)\s*,\s*([A-Za-z_$][\w$]*)\.getMobType\(\)\s*\)"""
            ).replace(replacement) { match ->
                val stack = match.groupValues[1].trim()
                val living = match.groupValues[2]
                val damageSourceExpr = damageSourceForDamageBonusTarget(methodText, living) ?: return@replace match.value
                changed = true
                "(EnchantmentHelper.modifyDamage($serverLevelExpr, $stack, $living, $damageSourceExpr, $baseDamageVariable) - $baseDamageVariable)"
            }
        }
        replacement = Regex(
            """([A-Za-z_$][\w$]*|(?:\d+(?:\.\d+)?F?))\s*\+\s*EnchantmentHelper\.getDamageBonus\(\s*([^,]+?)\s*,\s*([A-Za-z_$][\w$]*)\.getMobType\(\)\s*\)"""
        ).replace(replacement) { match ->
            val baseDamage = match.groupValues[1].trim()
            val stack = match.groupValues[2].trim()
            val living = match.groupValues[3]
            val damageSourceExpr = damageSourceForDamageBonusTarget(methodText, living) ?: return@replace match.value
            changed = true
            "EnchantmentHelper.modifyDamage($serverLevelExpr, $stack, $living, $damageSourceExpr, $baseDamage)"
        }
        replacement = Regex(
            """([A-Za-z_$][\w$]*)\s*\+=\s*\(?\s*(?:float|double)?\s*\)?\s*\(?\s*EnchantmentHelper\.getDamageBonus\(\s*([^,]+?)\s*,\s*([A-Za-z_$][\w$]*)\.getMobType\(\)\s*\)\s*\)?\s*;"""
        ).replace(replacement) { match ->
            val baseDamage = match.groupValues[1].trim()
            val stack = match.groupValues[2].trim()
            val living = match.groupValues[3]
            val damageSourceExpr = damageSourceForDamageBonusTarget(methodText, living) ?: return@replace match.value
            changed = true
            "$baseDamage = EnchantmentHelper.modifyDamage($serverLevelExpr, $stack, $living, $damageSourceExpr, $baseDamage);"
        }
        if (replacement != methodText) {
            result = result.replace(methodText, replacement)
        }
    }

    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyProjectileDamageBonusWithFollowingDamageSource(source: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    var changed = false
    val result = Regex(
        """(?m)^([ \t]*)(float|double)\s+($id)\s*=\s*([^;\r\n]+?)\s*;\s*\r?\n\1if\s*\(\s*($id)\s+instanceof\s+LivingEntity\s+($id)\s*\)\s*\{\s*\r?\n\1[ \t]*\3\s*\+=\s*(?:\(\s*float\s*\)\s*)?\(?\s*EnchantmentHelper\.getDamageBonus\(\s*([^,\r\n]+?)\s*,\s*\6\.getMobType\(\)\s*\)\s*\)?\s*;\s*\r?\n\1}\s*\r?\n\s*\r?\n\1Entity\s+($id)\s*=\s*([^;\r\n]+?)\s*;\s*\r?\n\1DamageSource\s+($id)\s*=\s*([^;\r\n]+?)\s*;"""
    ).replace(source) { match ->
        changed = true
        val indent = match.groupValues[1]
        val type = match.groupValues[2]
        val damage = match.groupValues[3]
        val baseDamage = match.groupValues[4].trim()
        val target = match.groupValues[5]
        val stack = match.groupValues[7].trim()
        val owner = match.groupValues[8]
        val ownerExpr = match.groupValues[9].trim()
        val damageSource = match.groupValues[10]
        val damageSourceExpr = match.groupValues[11].trim()
        """${indent}$type $damage = $baseDamage;
${indent}Entity $owner = $ownerExpr;
${indent}DamageSource $damageSource = $damageSourceExpr;
${indent}if (this.level() instanceof ServerLevel serverlevel) {
${indent}    $damage = EnchantmentHelper.modifyDamage(serverlevel, $stack, $target, $damageSource, $damage);
${indent}}"""
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.server.level.ServerLevel") else source
}

internal fun StructuralRefactorPass.migrateRedundantFloatVariableCasts(source: String): String {
    if (!source.contains("(float)(") && !source.contains("(float) (")) return source
    val executableCode = maskJavaCommentsAndLiterals(source)
    val id = """[A-Za-z_$][\w$]*"""
    val edits = mutableListOf<Pair<IntRange, String>>()
    javaMethodRanges(executableCode).forEach { method ->
        val methodExecutable = executableCode.substring(method.range)
        val floatNames = Regex("""\b(?:final\s+)?float\s+($id)\b""")
            .findAll(methodExecutable)
            .map { it.groupValues[1] }
            .toSet()
        if (floatNames.isEmpty()) return@forEach
        val nonFloatNames = Regex(
            """\b(?:final\s+)?(?!(?:float)\b)(?:byte|short|int|long|double|boolean|char|[A-Z_$][\w$]*(?:\s*<[^;{}()]*>)?)\s+($id)\b"""
        )
            .findAll(methodExecutable)
            .map { it.groupValues[1] }
            .toSet()
        val safeFloatNames = floatNames - nonFloatNames
        if (safeFloatNames.isEmpty()) return@forEach
        Regex("""\(\s*float\s*\)\s*\(\s*($id)\s*\)""")
            .findAll(methodExecutable)
            .filter { it.groupValues[1] in safeFloatNames }
            .forEach { match ->
                val absolute = (method.range.first + match.range.first)..(method.range.first + match.range.last)
                edits += absolute to match.groupValues[1]
            }
    }
    if (edits.isEmpty()) return source
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateLivingDamageEventBoundarySource(source: String): String {
    if (!source.contains("LivingDamageEvent")) return source
    var result = source
    var needsIncomingDamageImport = false
    val methodPattern = Regex(
        """(?m)(?:^|\r?\n)[ \t]*(?:(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*)(?:(?:public|protected|private|static|final|synchronized)\s+)+[A-Za-z_$][\w$<>\[\].?,\s]*\s+[A-Za-z_$][\w$]*\s*\(([^()]*)\)\s*\{"""
    )
    methodPattern.findAll(source).toList().asReversed().forEach { match ->
        val openBrace = source.indexOf('{', match.range.last - 1)
        if (openBrace < 0) return@forEach
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return@forEach
        val methodText = result.substring(match.range.first, closeBrace + 1)
        val eventVars = Regex("""\bLivingDamageEvent\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(methodText)
            .map { it.groupValues[1] }
            .toList()
        if (eventVars.isEmpty()) return@forEach
        var replacement = methodText
        eventVars.forEach { eventVar ->
            val usesCancellationMutation = Regex("""\b${Regex.escape(eventVar)}\.setCanceled\(""")
                .containsMatchIn(replacement)
            val usesCancellationState = Regex("""\b${Regex.escape(eventVar)}\.isCanceled\(\)""")
                .containsMatchIn(replacement)
            val usesMutableDamage = Regex("""\b${Regex.escape(eventVar)}\.setAmount\(""").containsMatchIn(replacement)
            if (usesCancellationMutation) {
                replacement = Regex("""\bLivingDamageEvent\s+${Regex.escape(eventVar)}\b""")
                    .replace(replacement, "LivingIncomingDamageEvent $eventVar")
                needsIncomingDamageImport = true
            } else {
                val nestedType = if (usesMutableDamage) "Pre" else "Post"
                replacement = Regex("""\bLivingDamageEvent\s+${Regex.escape(eventVar)}\b""")
                    .replace(replacement, "LivingDamageEvent.$nestedType $eventVar")
                replacement = Regex("""\b${Regex.escape(eventVar)}\.getAmount\(\)""")
                    .replace(replacement, "$eventVar.getNewDamage()")
                replacement = Regex("""\b${Regex.escape(eventVar)}\.setAmount\(""")
                    .replace(replacement, "$eventVar.setNewDamage(")
                if (nestedType == "Post" && usesCancellationState) {
                    replacement = unwrapSimpleNotCanceledGuards(replacement, eventVar)
                    replacement = removeNotCanceledConjuncts(replacement, eventVar)
                }
            }
        }
        if (replacement != methodText) {
            result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        }
    }
    if (needsIncomingDamageImport) {
        result = addImportIfMissing(result, "net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent")
    }
    result = removeUnusedSimpleImport(
        result,
        "net.neoforged.neoforge.event.entity.living.LivingDamageEvent",
        "LivingDamageEvent"
    )
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCommonHooksLivingDamagePipelineSource(source: String): String {
    if (!source.contains("CommonHooks.onLivingHurt(") &&
        !source.contains("CommonHooks.onLivingDamage(")) {
        return source
    }
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!Regex("""actuallyHurt\s*\(\s*DamageSource\s+[A-Za-z_$][\w$]*\s*,\s*float\s+[A-Za-z_$][\w$]*\s*\)""")
            .containsMatchIn(executableCode)) {
        return source
    }
    var result = source
    var changed = false
    val id = """[A-Za-z_$][\w$]*"""

    result = Regex(
        """(?m)^([ \t]*)($id)\s*=\s*CommonHooks\.onLivingHurt\(\s*this\s*,\s*($id)\s*,\s*\2\s*\)\s*;\s*$"""
    ).replace(result) { match ->
        changed = true
        val indent = match.groupValues[1]
        val damage = match.groupValues[2]
        "$indent" + "this.damageContainers.peek().setNewDamage($damage);\n" +
            "$indent$damage = this.damageContainers.peek().getNewDamage();"
    }

    result = Regex(
        """(?m)^([ \t]*)($id)\s*=\s*CommonHooks\.onLivingDamage\(\s*this\s*,\s*($id)\s*,\s*\2\s*\)\s*;\s*$"""
    ).replace(result) { match ->
        changed = true
        val indent = match.groupValues[1]
        val damage = match.groupValues[2]
        "$indent" + "this.damageContainers.peek().setNewDamage($damage);\n" +
            "$indent$damage = CommonHooks.onLivingDamagePre(this, this.damageContainers.peek());"
    }

    if (changed && result.contains("this.getCombatTracker().recordDamage(")) {
        result = Regex("""(?m)^([ \t]*)this\.getCombatTracker\(\)\.recordDamage\(""")
            .replace(result) { match ->
                val insert = "${match.groupValues[1]}this.damageContainers.peek().setNewDamage(f1);\n"
                if (result.substring(0, match.range.first).takeLast(160).contains("damageContainers.peek().setNewDamage(f1);")) {
                    match.value
                } else {
                    insert + match.value
                }
            }
    }

    if (changed && result.contains("this.gameEvent(GameEvent.ENTITY_DAMAGE);") &&
        !result.contains("CommonHooks.onLivingDamagePost(this, this.damageContainers.peek())")) {
        result = result.replace(
            "this.gameEvent(GameEvent.ENTITY_DAMAGE);",
            "this.gameEvent(GameEvent.ENTITY_DAMAGE);\n                CommonHooks.onLivingDamagePost(this, this.damageContainers.peek());"
        )
    }

    return result
}

internal fun StructuralRefactorPass.migrateLegacyBreakEventExpToDropSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getExpToDrop()") ||
        !executableCode.contains("BlockEvent.BreakEvent")) {
        return source
    }
    var result = source
    var changed = false
    val eventVariables = Regex("""\bBlockEvent\.BreakEvent\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    for (eventName in eventVariables) {
        val replacement =
            "EnchantmentHelper.processBlockExperience((ServerLevel) $eventName.getLevel(), $eventName.getPlayer().getMainHandItem(), $eventName.getState().getExpDrop($eventName.getLevel(), $eventName.getPos(), $eventName.getLevel().getBlockEntity($eventName.getPos()), $eventName.getPlayer(), $eventName.getPlayer().getMainHandItem()))"
        val before = result
        result = Regex("""\b${Regex.escape(eventName)}\.getExpToDrop\(\)""")
            .replace(result, replacement)
        changed = changed || result != before
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.EnchantmentHelper")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyAbstractArrowKnockbackCallSites(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".setKnockback(") ||
        !executableCode.contains("Enchantments.PUNCH") ||
        !executableCode.contains("EnchantmentHelper.getItemEnchantmentLevel")) {
        return source
    }
    var result = source
    var changed = false
    val id = """[A-Za-z_$][\w$]*"""
    val registryPunchBlock = Regex(
        """(?s)(\bint\s+($id)\s*=\s*($id)\.registryAccess\(\).*?Enchantments\.PUNCH.*?EnchantmentHelper\.getItemEnchantmentLevel\([^,]+,\s*($id)\s*\).*?;\r?\n)([ \t]*)if\s*\(\s*\2\s*>\s*0\s*\)\s*\{\s*\r?\n[ \t]*($id)\.setKnockback\(\s*\2\s*\);\s*\r?\n[ \t]*}\s*"""
    )
    result = registryPunchBlock.replace(result) { match ->
        changed = true
        val assignment = match.groupValues[1]
        val levelName = match.groupValues[3]
        val weaponStack = match.groupValues[4]
        val indent = match.groupValues[5]
        val arrow = match.groupValues[6]
        assignment + renderFiredFromWeaponSetup(indent, levelName, weaponStack, arrow)
    }
    javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result)).asReversed().forEach { method ->
        val methodText = result.substring(method.range)
        if (!methodText.contains(".setKnockback(") || !methodText.contains("Enchantments.PUNCH")) return@forEach
        val levelNames = Regex("""\bLevel\s+($id)\b""")
            .findAll(methodText)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        val levelName = levelNames.singleOrNull() ?: return@forEach
        val punch = Regex(
            """(?s)\bint\s+($id)\s*=\s*.*?Enchantments\.PUNCH.*?EnchantmentHelper\.getItemEnchantmentLevel\([^,]+,\s*([A-Za-z_$][\w$]*)\s*\).*?;"""
        ).find(methodText) ?: return@forEach
        val punchVar = punch.groupValues[1]
        val weaponStack = punch.groupValues[2]
        val blockPattern = Regex(
            """(?m)^([ \t]*)if\s*\(\s*${Regex.escape(punchVar)}\s*>\s*0\s*\)\s*\{\s*\r?\n[ \t]*($id)\.setKnockback\(\s*${Regex.escape(punchVar)}\s*\);\s*\r?\n[ \t]*}\s*"""
        )
        val singleLinePattern = Regex(
            """(?m)^([ \t]*)if\s*\(\s*${Regex.escape(punchVar)}\s*>\s*0\s*\)\s*($id)\.setKnockback\(\s*${Regex.escape(punchVar)}\s*\)\s*;\s*"""
        )
        var migratedMethod = blockPattern.replace(methodText) { match ->
            changed = true
            val indent = match.groupValues[1]
            val arrow = match.groupValues[2]
            renderFiredFromWeaponSetup(indent, levelName, weaponStack, arrow)
        }
        migratedMethod = singleLinePattern.replace(migratedMethod) { match ->
            changed = true
            val indent = match.groupValues[1]
            val arrow = match.groupValues[2]
            renderFiredFromWeaponSetup(indent, levelName, weaponStack, arrow)
        }
        if (migratedMethod != methodText) {
            result = result.replaceRange(method.range, migratedMethod)
        }
    }
    if (!changed) {
        val punchAssignment = Regex(
            """(?s)\bint\s+($id)\s*=\s*($id)\.registryAccess\(\).*?Enchantments\.PUNCH.*?EnchantmentHelper\.getItemEnchantmentLevel\([^,]+,\s*($id)\s*\).*?;"""
        )
        punchAssignment.findAll(result).toList().asReversed().forEach { punch ->
            val punchVar = punch.groupValues[1]
            val levelName = punch.groupValues[2]
            val weaponStack = punch.groupValues[3]
            val tail = result.substring(punch.range.last + 1)
            val blockPattern = Regex(
                """(?m)^([ \t]*)if\s*\(\s*${Regex.escape(punchVar)}\s*>\s*0\s*\)\s*\{\s*\r?\n[ \t]*($id)\.setKnockback\(\s*${Regex.escape(punchVar)}\s*\);\s*\r?\n[ \t]*}\s*"""
            )
            val block = blockPattern.find(tail) ?: return@forEach
            val indent = block.groupValues[1]
            val arrow = block.groupValues[2]
            val replacement = renderFiredFromWeaponSetup(indent, levelName, weaponStack, arrow)
            val start = punch.range.last + 1 + block.range.first
            val end = punch.range.last + 1 + block.range.last + 1
            result = result.substring(0, start) + replacement + result.substring(end)
            changed = true
        }
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.EnchantmentHelper")
    return result
}

internal fun StructuralRefactorPass.renderFiredFromWeaponSetup(indent: String, level: String, weapon: String, arrow: String): String =
    """${indent}if ($level instanceof ServerLevel serverLevel) {
${indent}    $arrow.firedFromWeapon = $weapon.copy();
${indent}    EnchantmentHelper.onProjectileSpawned(serverLevel, $weapon, $arrow, ignored -> $arrow.firedFromWeapon = null);
${indent}}"""

internal fun StructuralRefactorPass.migrateLegacyServerInteractionDistanceConstants(source: String): String {
    if (!source.contains("ServerGamePacketListenerImpl.MAX_INTERACTION_DISTANCE")) return source
    var result = source.replace(
        "Math.sqrt(ServerGamePacketListenerImpl.MAX_INTERACTION_DISTANCE) - 1.0",
        "Player.DEFAULT_BLOCK_INTERACTION_RANGE"
    )
    result = removeImport(result, "net.minecraft.server.network.ServerGamePacketListenerImpl")
    result = addImportIfMissing(result, "net.minecraft.world.entity.player.Player")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBoatFluidUpdateHookSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("shouldUpdateFluidWhileRiding(") ||
        !executableCode.contains("shouldUpdateWhileBoating(")) {
        return source
    }
    var result = source
    result = Regex(
        """(?m)^([ \t]*)@Override\s*\r?\n([ \t]*public\s+boolean\s+shouldUpdateFluidWhileRiding\s*\()"""
    ).replace(result, "$1$2")
    result = Regex("""\b([A-Za-z_$][\w$]*)\.shouldUpdateWhileBoating\(\s*([A-Za-z_$][\w$]*)\s*,\s*[A-Za-z_$][\w$]*\s*\)""")
        .replace(result, "$1.supportsBoating($2)")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyDamageEventAttackerBindings(source: String): String {
    if (!source.contains("attacker") ||
        (!source.contains("LivingIncomingDamageEvent") && !source.contains("LivingDamageEvent"))) {
        return source
    }
    var result = source
    var changed = false
    val id = """[A-Za-z_$][\w$]*"""
    javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result)).asReversed().forEach { method ->
        val eventName = Regex("""\b(?:LivingIncomingDamageEvent|LivingDamageEvent(?:\.(?:Pre|Post))?)\s+($id)\b""")
            .find(method.header)
            ?.groupValues
            ?.get(1)
            ?: return@forEach
        val methodText = result.substring(method.range)
        if (!Regex("""\battacker\b""").containsMatchIn(methodText)) return@forEach
        if (Regex("""\b(?:LivingEntity|Entity)\s+attacker\b|\binstanceof\s+(?:LivingEntity|Entity)\s+attacker\b""")
                .containsMatchIn(methodText)) {
            return@forEach
        }
        val openBrace = result.indexOf('{', method.range.first)
        if (openBrace < 0 || openBrace > method.range.last) return@forEach
        val bodyIndent = lineIndentAt(result, openBrace) + "    "
        val attackerEntity = uniqueLocalName(methodText, "attackerEntity")
        val directAttackerEntity = uniqueLocalName(methodText + "\n$attackerEntity", "directAttackerEntity")
        val binding = "\n${bodyIndent}LivingEntity attacker = $eventName.getSource().getEntity() instanceof LivingEntity $attackerEntity ? $attackerEntity : $eventName.getSource().getDirectEntity() instanceof LivingEntity $directAttackerEntity ? $directAttackerEntity : null;"
        result = result.substring(0, openBrace + 1) + binding + result.substring(openBrace + 1)
        changed = true
    }
    if (!changed) {
        val eventParameterPattern = Regex("""\b(?:LivingIncomingDamageEvent|LivingDamageEvent(?:\.(?:Pre|Post))?)\s+($id)\b""")
        eventParameterPattern.findAll(maskJavaCommentsAndLiterals(result)).toList().asReversed().forEach { parameter ->
            val eventName = parameter.groupValues[1]
            val openBrace = result.indexOf('{', parameter.range.last)
            if (openBrace < 0) return@forEach
            val closeBrace = findMatchingBrace(result, openBrace)
            if (closeBrace <= openBrace) return@forEach
            val methodText = result.substring(openBrace + 1, closeBrace)
            if (!Regex("""\battacker\b""").containsMatchIn(methodText)) return@forEach
            if (Regex("""\b(?:LivingEntity|Entity)\s+attacker\b|\binstanceof\s+(?:LivingEntity|Entity)\s+attacker\b""")
                    .containsMatchIn(methodText)) {
                return@forEach
            }
            val bodyIndent = lineIndentAt(result, openBrace) + "    "
            val attackerEntity = uniqueLocalName(methodText, "attackerEntity")
            val directAttackerEntity = uniqueLocalName(methodText + "\n$attackerEntity", "directAttackerEntity")
            val binding = "\n${bodyIndent}LivingEntity attacker = $eventName.getSource().getEntity() instanceof LivingEntity $attackerEntity ? $attackerEntity : $eventName.getSource().getDirectEntity() instanceof LivingEntity $directAttackerEntity ? $directAttackerEntity : null;"
            result = result.substring(0, openBrace + 1) + binding + result.substring(openBrace + 1)
            changed = true
        }
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.world.entity.LivingEntity") else source
}

internal fun StructuralRefactorPass.removeNoOpGeneratedLivingDamageHelperMethods(source: String): String {
    if (!source.contains("modporter") ||
        (!source.contains("LivingDamageEvent.") && !source.contains("LivingIncomingDamageEvent"))) {
        return source
    }
    var result = source
    javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result))
        .asReversed()
        .forEach { method ->
            if (!method.name.startsWith("modporter")) return@forEach
            if (livingDamageEventParameter(method.header, result) == null) return@forEach
            val methodText = result.substring(method.range)
            val body = javaMethodBodyText(methodText)?.trim() ?: return@forEach
            val noOpLocalAssignment = Regex(
                """^(?:final\s+)?[A-Za-z_$][\w$]*(?:\s*<[^;{}]+>)?(?:\s*\[\s*])?\s+[A-Za-z_$][\w$]*\s*=\s*[^;]+;\s*$""",
                RegexOption.DOT_MATCHES_ALL
            ).matches(body)
            if (!noOpLocalAssignment) return@forEach
            val lineStart = result.lastIndexOf('\n', method.range.first).let { if (it < 0) 0 else it + 1 }
            val close = method.range.last + 1
            val end = when {
                close + 1 < result.length && result[close] == '\r' && result[close + 1] == '\n' -> close + 2
                close < result.length && result[close] == '\n' -> close + 1
                else -> close
            }
            result = result.substring(0, lineStart) + result.substring(end)
        }
    return result
}

internal data class FluidTypeClientExtensionCandidate(
    val file: Path,
    val packageName: String,
    val className: String,
    val fqn: String
)

internal data class FluidTypeClientExtensionRegistration(
    val file: Path,
    val packageName: String,
    val ownerClassName: String,
    val fieldName: String,
    val fluidTypeFqn: String,
    val modIdExpression: String,
    val modIdImport: String?
)

internal fun StructuralRefactorPass.migrateFluidTypeClientExtensionRegistrations(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val candidates = javaFiles.mapNotNull { file ->
        val source = runCatching { file.readText() }.getOrDefault("")
        if (!source.contains("extends FluidType") ||
            !source.contains("initializeClient") ||
            !source.contains("IClientFluidTypeExtensions")
        ) {
            return@mapNotNull null
        }
        val className = javaTopLevelTypeName(source) ?: return@mapNotNull null
        val packageName = packageNameOf(source)
        FluidTypeClientExtensionCandidate(
            file = file,
            packageName = packageName,
            className = className,
            fqn = if (packageName.isBlank()) className else "$packageName.$className"
        )
    }
    if (candidates.isEmpty()) return emptyList()
    val candidateBySimpleName = candidates.groupBy { it.className }
    val registrations = javaFiles.flatMap { file ->
        val source = runCatching { file.readText() }.getOrDefault("")
        collectFluidTypeClientExtensionRegistrations(file, source, candidateBySimpleName)
    }
    if (registrations.isEmpty()) return emptyList()
    val registeredTypes = registrations.map { it.fluidTypeFqn }.toSet()
    val changes = mutableListOf<Change>()

    candidates.filter { it.fqn in registeredTypes }.forEach { candidate ->
        val original = candidate.file.readText()
        val migrated = migrateFluidTypeInitializeClientToFactory(original)
        if (migrated != original) {
            if (!dryRun) candidate.file.writeText(migrated)
            changes.add(Change(
                file = candidate.file,
                line = 0,
                description = "Move FluidType client extensions from deprecated initializeClient override to explicit client extension factory",
                before = "FluidType.initializeClient(Consumer<IClientFluidTypeExtensions>) override",
                after = "createClientExtensions() factory registered from RegisterClientExtensionsEvent",
                confidence = Confidence.HIGH,
                ruleId = "struct-fluidtype-client-extension-factory"
            ))
        }
    }

    registrations.groupBy { it.file }.forEach { (registrationFile, groupedRegistrations) ->
        val ownerClassName = groupedRegistrations.first().ownerClassName
        val listenerClassName = "${ownerClassName}ClientFluidExtensions"
        val originalRegistration = registrationFile.readText()
        val registrationWithListener = addFluidClientExtensionModBusListener(originalRegistration, listenerClassName)
        if (registrationWithListener != originalRegistration) {
            if (!dryRun) registrationFile.writeText(registrationWithListener)
            changes.add(Change(
                file = registrationFile,
                line = 0,
                description = "Register FluidType client extension subscriber on the mod event bus",
                before = "DeferredRegister FluidType registration without RegisterClientExtensionsEvent listener",
                after = "IEventBus.addListener wires generated RegisterClientExtensionsEvent handler",
                confidence = Confidence.HIGH,
                ruleId = "struct-fluidtype-client-extension-listener"
            ))
        }
        val generated = generatedFluidClientExtensionSubscriberSource(groupedRegistrations)
            ?: return@forEach
        val packageDir = groupedRegistrations.first().packageName.replace('.', '/')
        val generatedFile = srcDir.resolve(packageDir).resolve("$listenerClassName.java")
        val previous = if (generatedFile.exists()) generatedFile.readText() else null
        if (previous != generated) {
            if (!dryRun) {
                generatedFile.parent.createDirectories()
                generatedFile.writeText(generated)
            }
            changes.add(Change(
                file = generatedFile,
                line = 0,
                description = "Register migrated FluidType client extensions on the client mod event bus",
                before = "FluidType.initializeClient override discovered on registered FluidType subtype",
                after = "RegisterClientExtensionsEvent subscriber registers each DeferredHolder FluidType",
                confidence = Confidence.HIGH,
                ruleId = "struct-fluidtype-client-extension-registration"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.addFluidClientExtensionModBusListener(source: String, listenerClassName: String): String {
    if (source.contains("$listenerClassName::registerClientExtensions")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val method = javaMethodRangesIncludingDefault(executable).firstOrNull { method ->
        val methodText = executable.substring(method.range)
        methodText.contains("IEventBus") && methodText.contains(".register(")
    } ?: return source
    val methodText = source.substring(method.range)
    val busParam = javaMethodParameters(methodText)
        .firstOrNull { it.type.endsWithJavaType("IEventBus") }
        ?.name
        ?: return source
    val openBrace = source.indexOf('{', method.range.first)
    if (openBrace < 0 || openBrace > method.range.last) return source
    val insertAfter = source.indexOf('\n', openBrace).let { if (it < 0 || it > method.range.last) openBrace + 1 else it + 1 }
    val indent = Regex("""^[ \t]*""").find(source.substring(insertAfter).lineSequence().firstOrNull().orEmpty())?.value ?: "        "
    return source.substring(0, insertAfter) +
        "${indent}$busParam.addListener($listenerClassName::registerClientExtensions);\n" +
        source.substring(insertAfter)
}

internal fun StructuralRefactorPass.collectFluidTypeClientExtensionRegistrations(
    file: Path,
    source: String,
    candidateBySimpleName: Map<String, List<FluidTypeClientExtensionCandidate>>
): List<FluidTypeClientExtensionRegistration> {
    if (!source.contains("DeferredHolder") || !source.contains("FluidType") || !source.contains("new ")) {
        return emptyList()
    }
    val packageName = packageNameOf(source)
    val ownerClassName = javaTopLevelTypeName(source) ?: return emptyList()
    val imports = javaSingleTypeImports(source)
    val modId = Regex(
        """DeferredRegister\s*<\s*FluidType\s*>\s+[A-Za-z_$][\w$]*\s*=\s*DeferredRegister\.create\([^;]+,\s*([^);\r\n]+)\s*\)\s*;"""
    ).find(source)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: return emptyList()
    val modIdOwner = Regex("""^([A-Za-z_$][\w$]*)\.""").find(modId)?.groupValues?.get(1)
    val modIdImport = modIdOwner?.let { imports[it] }
    val fieldPattern = Regex(
        """(?m)public\s+static\s+final\s+DeferredHolder\s*<\s*FluidType\s*,\s*[^>]+>\s+([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.register\([^;\r\n]*new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\("""
    )
    return fieldPattern.findAll(source).mapNotNull { match ->
        val fieldName = match.groupValues[1]
        val rawType = match.groupValues[2]
        val simpleType = rawType.substringAfterLast('.')
        val candidate = candidateBySimpleName[simpleType]?.singleOrNull() ?: return@mapNotNull null
        val resolvedType = when {
            rawType.contains('.') && rawType.substringBefore('.').firstOrNull()?.isLowerCase() == true -> rawType
            imports[simpleType] != null -> imports.getValue(simpleType)
            else -> candidate.fqn
        }
        if (resolvedType != candidate.fqn) return@mapNotNull null
        FluidTypeClientExtensionRegistration(
            file = file,
            packageName = packageName,
            ownerClassName = ownerClassName,
            fieldName = fieldName,
            fluidTypeFqn = candidate.fqn,
            modIdExpression = modId,
            modIdImport = modIdImport
        )
    }.toList()
}

internal fun StructuralRefactorPass.migrateFluidTypeInitializeClientToFactory(source: String): String {
    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        if (method.name != "initializeClient") continue
        val methodText = result.substring(method.range)
        val parameters = javaMethodParameters(methodText)
        if (parameters.singleOrNull()?.type?.contains("IClientFluidTypeExtensions") != true) continue
        val consumerName = parameters.single().name
        val executableMethod = maskJavaCommentsAndLiterals(methodText)
        val acceptCall = Regex("""\b${Regex.escape(consumerName)}\.accept\s*\(""").find(executableMethod) ?: continue
        val openParen = executableMethod.indexOf('(', acceptCall.range.last)
        if (openParen < 0) continue
        val closeParen = findMatchingParen(executableMethod, openParen)
        if (closeParen < 0) continue
        val extensionExpression = methodText.substring(openParen + 1, closeParen).trim()
        if (!extensionExpression.startsWith("new IClientFluidTypeExtensions()")) continue
        val methodIndent = Regex("""^[ \t]*""").find(methodText)?.value ?: ""
        val replacement = """
${methodIndent}public IClientFluidTypeExtensions createClientExtensions() {
${methodIndent}    return $extensionExpression;
${methodIndent}}
""".trimEnd()
        result = result.substring(0, method.range.first) + replacement + result.substring(method.range.last + 1)
    }
    if (result == source) return source
    result = removeUnusedSimpleImport(result, "java.util.function.Consumer", "Consumer")
    return result
}

internal fun StructuralRefactorPass.generatedFluidClientExtensionSubscriberSource(
    registrations: List<FluidTypeClientExtensionRegistration>
): String? {
    val first = registrations.firstOrNull() ?: return null
    val packageLine = if (first.packageName.isBlank()) "" else "package ${first.packageName};\n\n"
    val imports = linkedSetOf(
        "net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent"
    )
    val className = "${first.ownerClassName}ClientFluidExtensions"
    val body = registrations.distinctBy { it.fieldName }.joinToString("\n") { registration ->
        "        event.registerFluidType(((${registration.fluidTypeFqn}) ${registration.ownerClassName}.${registration.fieldName}.value()).createClientExtensions(), ${registration.ownerClassName}.${registration.fieldName}.value());"
    }
    val importBlock = imports.sorted().joinToString("\n") { "import $it;" }
    return """${packageLine}${importBlock}

public final class $className {
    private $className() {
    }

    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
$body
    }
}
"""
}

internal enum class LivingDamageBoundary {
    INCOMING,
    PRE,
    POST
}

internal data class LivingDamageEventMethodSignature(
    val packageName: String,
    val className: String,
    val methodName: String,
    val boundary: LivingDamageBoundary
) {
    val fqn: String
        get() = if (packageName.isBlank()) className else "$packageName.$className"
}

internal data class LivingDamageHelperCall(
    val targetBoundary: LivingDamageBoundary,
    val blockRange: IntRange,
    val blockText: String
)

internal fun StructuralRefactorPass.migrateLivingDamageEventHelperBoundaries(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val signatures = collectLivingDamageEventMethodSignatures(javaFiles)
    if (signatures.isEmpty()) return emptyList()
    val changes = mutableListOf<Change>()

    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        if (!original.contains("LivingDamageEvent") && !original.contains("LivingIncomingDamageEvent")) return@forEach
        var migrated = migrateLivingDamageEventHelperBoundariesInSource(original, signatures)
        migrated = migrateLegacyDamageEventAttackerBindings(migrated)
        migrated = removeNoOpGeneratedLivingDamageHelperMethods(migrated)
        if (migrated != original) {
            if (!dryRun) javaFile.writeText(migrated)
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Align living damage helper call sites with NeoForge damage event phases",
                before = "helper call passes LivingDamageEvent.Pre/Post to incompatible helper boundary",
                after = "caller signature retargeted or helper call split into matching phase subscriber",
                confidence = Confidence.HIGH,
                ruleId = "struct-living-damage-helper-boundary"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.collectLivingDamageEventMethodSignatures(
    javaFiles: List<Path>
): List<LivingDamageEventMethodSignature> =
    javaFiles.flatMap { javaFile ->
        val source = runCatching { javaFile.readText() }.getOrDefault("")
        if (!source.contains("LivingDamageEvent") && !source.contains("LivingIncomingDamageEvent")) {
            return@flatMap emptyList()
        }
        val packageName = packageNameOf(source)
        val className = javaTopLevelTypeName(source) ?: return@flatMap emptyList()
        javaMethodRangesIncludingDefault(source).mapNotNull { method ->
            val eventParameter = livingDamageEventParameter(method.header, source) ?: return@mapNotNull null
            LivingDamageEventMethodSignature(packageName, className, method.name, eventParameter.first)
        }
    }

internal fun StructuralRefactorPass.livingDamageEventParameter(
    methodHeader: String,
    source: String
): Pair<LivingDamageBoundary, String>? {
    val parametersText = methodHeader.substringAfter("(", "").substringBeforeLast(")", "")
    splitTopLevelJavaArgs(parametersText).forEach { parameter ->
        val name = javaParameterName(parameter) ?: return@forEach
        canonicalLivingDamageBoundary(parameter, source)?.let { return it to name }
    }
    return null
}

internal fun StructuralRefactorPass.canonicalLivingDamageBoundary(typeText: String, source: String): LivingDamageBoundary? {
    val normalized = typeText.replace(Regex("""\s+"""), "")
    return when {
        normalized.contains("LivingIncomingDamageEvent") -> LivingDamageBoundary.INCOMING
        normalized.contains("LivingDamageEvent.Pre") -> LivingDamageBoundary.PRE
        normalized.contains("LivingDamageEvent.Post") -> LivingDamageBoundary.POST
        Regex("""(?<![\w$])Pre(?![\w$])""").containsMatchIn(typeText) &&
            source.contains("LivingDamageEvent.Pre") -> LivingDamageBoundary.PRE
        Regex("""(?<![\w$])Post(?![\w$])""").containsMatchIn(typeText) &&
            source.contains("LivingDamageEvent.Post") -> LivingDamageBoundary.POST
        else -> null
    }
}

internal fun StructuralRefactorPass.migrateLivingDamageEventHelperBoundariesInSource(
    source: String,
    signatures: List<LivingDamageEventMethodSignature>
): String {
    val ownerClass = javaTopLevelTypeName(source) ?: return source
    val ownerPackage = packageNameOf(source)
    val imports = javaSingleTypeImports(source)
    val signaturesBySimpleClassAndMethod = signatures.groupBy { it.className to it.methodName }
    val signaturesByFqnAndMethod = signatures.groupBy { it.fqn to it.methodName }

    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        val eventParameter = livingDamageEventParameter(method.header, result) ?: continue
        val currentBoundary = eventParameter.first
        val eventVar = eventParameter.second
        val methodText = result.substring(method.range)
        val helperCalls = livingDamageHelperCalls(
            methodText = methodText,
            eventVar = eventVar,
            currentClass = ownerClass,
            currentPackage = ownerPackage,
            imports = imports,
            signaturesBySimpleClassAndMethod = signaturesBySimpleClassAndMethod,
            signaturesByFqnAndMethod = signaturesByFqnAndMethod
        )
        val incompatibleCalls = helperCalls.filter { it.targetBoundary != currentBoundary }
        if (incompatibleCalls.isEmpty()) continue

        val migratedMethod = if (canRetypeLivingDamageHandler(methodText, eventVar, helperCalls, incompatibleCalls)) {
            retypeLivingDamageHandler(methodText, currentBoundary, incompatibleCalls.single().targetBoundary)
        } else {
            splitLivingDamageHelperCalls(methodText, method.name, eventVar, incompatibleCalls)
        }
        if (migratedMethod != methodText) {
            result = result.substring(0, method.range.first) + migratedMethod + result.substring(method.range.last + 1)
        }
    }

    if (result.contains("LivingIncomingDamageEvent")) {
        result = addImportIfMissing(result, "net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent")
    }
    if (result.contains("LivingDamageEvent.Pre") || result.contains("LivingDamageEvent.Post")) {
        result = addImportIfMissing(result, "net.neoforged.neoforge.event.entity.living.LivingDamageEvent")
    }
    return result
}

internal fun StructuralRefactorPass.javaSingleTypeImports(source: String): Map<String, String> =
    Regex("""(?m)^[ \t]*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
        .findAll(source)
        .associate { match -> match.groupValues[1].substringAfterLast('.') to match.groupValues[1] }

internal fun StructuralRefactorPass.livingDamageHelperCalls(
    methodText: String,
    eventVar: String,
    currentClass: String,
    currentPackage: String,
    imports: Map<String, String>,
    signaturesBySimpleClassAndMethod: Map<Pair<String, String>, List<LivingDamageEventMethodSignature>>,
    signaturesByFqnAndMethod: Map<Pair<String, String>, List<LivingDamageEventMethodSignature>>
): List<LivingDamageHelperCall> {
    val result = mutableListOf<LivingDamageHelperCall>()
    val executableMethod = maskJavaCommentsAndLiterals(methodText)
    val callPattern = Regex("""(?m)(?:(\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\.\s*)?([A-Za-z_$][\w$]*)\s*\(([^;{}]*)\)\s*;""")
    callPattern.findAll(executableMethod).forEach { match ->
        val argsRange = match.groups[3]?.range ?: return@forEach
        val args = splitTopLevelJavaArgs(methodText.substring(argsRange.first, argsRange.last + 1))
        if (args.firstOrNull()?.trim() != eventVar) return@forEach
        val methodName = match.groupValues[2]
        val receiver = match.groupValues[1].takeIf { it.isNotBlank() }
        if (receiver == eventVar) return@forEach
        val target = resolveLivingDamageHelperSignature(
            receiver = receiver,
            methodName = methodName,
            currentClass = currentClass,
            currentPackage = currentPackage,
            imports = imports,
            signaturesBySimpleClassAndMethod = signaturesBySimpleClassAndMethod,
            signaturesByFqnAndMethod = signaturesByFqnAndMethod
        ) ?: return@forEach
        val block = extractLivingDamageHelperBlock(methodText, match.range)
        result += LivingDamageHelperCall(target.boundary, block.first, block.second)
    }
    return result
}

internal fun StructuralRefactorPass.resolveLivingDamageHelperSignature(
    receiver: String?,
    methodName: String,
    currentClass: String,
    currentPackage: String,
    imports: Map<String, String>,
    signaturesBySimpleClassAndMethod: Map<Pair<String, String>, List<LivingDamageEventMethodSignature>>,
    signaturesByFqnAndMethod: Map<Pair<String, String>, List<LivingDamageEventMethodSignature>>
): LivingDamageEventMethodSignature? {
    if (receiver == null) {
        return signaturesBySimpleClassAndMethod[currentClass to methodName]
            ?.singleOrNull { it.className == currentClass }
    }
    val simpleReceiver = receiver.substringAfterLast('.')
    if (simpleReceiver.firstOrNull()?.isUpperCase() != true) return null
    val importedFqn = imports[simpleReceiver]
    val samePackageFqn = if (currentPackage.isBlank()) simpleReceiver else "$currentPackage.$simpleReceiver"
    val receiverFqn = when {
        receiver.contains('.') && receiver.substringBefore('.').firstOrNull()?.isLowerCase() == true -> receiver
        importedFqn != null -> importedFqn
        else -> samePackageFqn
    }
    signaturesByFqnAndMethod[receiverFqn to methodName]?.singleOrNull()?.let { return it }
    return signaturesBySimpleClassAndMethod[simpleReceiver to methodName]?.singleOrNull()
}

internal fun StructuralRefactorPass.extractLivingDamageHelperBlock(methodText: String, callRange: IntRange): Pair<IntRange, String> {
    enclosingIfBlockWithOnlyCall(methodText, callRange)?.let { return it }
    val lineStart = methodText.lastIndexOf('\n', callRange.first).let { if (it < 0) 0 else it + 1 }
    var lineEnd = methodText.indexOf('\n', callRange.last + 1).let { if (it < 0) methodText.length else it + 1 }
    if (lineEnd > methodText.length) lineEnd = methodText.length
    return lineStart until lineEnd to methodText.substring(lineStart, lineEnd)
}

internal fun StructuralRefactorPass.enclosingIfBlockWithOnlyCall(methodText: String, callRange: IntRange): Pair<IntRange, String>? {
    var openBrace = methodText.lastIndexOf('{', callRange.first)
    while (openBrace >= 0) {
        val closeBrace = findMatchingBrace(methodText, openBrace)
        if (closeBrace >= callRange.last) {
            val prefixStart = methodText.lastIndexOf('\n', openBrace).let { if (it < 0) 0 else it + 1 }
            val prefix = methodText.substring(prefixStart, openBrace)
            if (Regex("""\bif\s*\([^;\r\n{}]*\)\s*$""").containsMatchIn(prefix)) {
                val body = methodText.substring(openBrace + 1, closeBrace).trim()
                val callText = methodText.substring(callRange).trim()
                if (body == callText) {
                    var end = closeBrace + 1
                    if (end < methodText.length && methodText[end] == '\r') end++
                    if (end < methodText.length && methodText[end] == '\n') end++
                    return prefixStart until end to methodText.substring(prefixStart, end)
                }
            }
        }
        openBrace = methodText.lastIndexOf('{', openBrace - 1)
    }
    return null
}

internal fun StructuralRefactorPass.canRetypeLivingDamageHandler(
    methodText: String,
    eventVar: String,
    helperCalls: List<LivingDamageHelperCall>,
    incompatibleCalls: List<LivingDamageHelperCall>
): Boolean {
    if (helperCalls.isEmpty() || helperCalls.size != incompatibleCalls.size) return false
    if (incompatibleCalls.map { it.targetBoundary }.distinct().size != 1) return false
    val executable = maskJavaCommentsAndLiterals(methodText)
    return listOf("setAmount", "setNewDamage", "getAmount", "getNewDamage", "setCanceled", "isCanceled").none { op ->
        Regex("""\b${Regex.escape(eventVar)}\.${Regex.escape(op)}\s*\(""").containsMatchIn(executable)
    }
}

internal fun StructuralRefactorPass.retypeLivingDamageHandler(
    methodText: String,
    currentBoundary: LivingDamageBoundary,
    targetBoundary: LivingDamageBoundary
): String =
    methodText.replaceFirst(Regex("""\b${Regex.escape(livingDamageBoundaryType(currentBoundary))}\b"""), livingDamageBoundaryType(targetBoundary))

internal fun StructuralRefactorPass.splitLivingDamageHelperCalls(
    methodText: String,
    methodName: String,
    eventVar: String,
    incompatibleCalls: List<LivingDamageHelperCall>
): String {
    val aliases = livingDamageEventAliases(methodText, eventVar)
    val retainedMethod = applyStringEdits(
        methodText,
        incompatibleCalls.distinctBy { it.blockRange }.map { it.blockRange to "" }
    ).trimEnd()
    val existingGeneratedNames = Regex("""\bmodporter[A-Za-z0-9_$]*\s*\(""")
        .findAll(methodText)
        .map { it.value.substringBefore("(") }
        .toSet()
    val generatedMethods = incompatibleCalls
        .groupBy { it.targetBoundary }
        .mapNotNull { (boundary, calls) ->
            val methodBlocks = calls.distinctBy { it.blockRange }
                .joinToString("") { rewriteLivingDamageHelperBlock(it.blockText, aliases).trimEnd() + "\n" }
                .trimEnd()
            if (methodBlocks.isBlank()) return@mapNotNull null
            val generatedName = uniqueLivingDamageHelperMethodName(methodName, boundary, existingGeneratedNames)
            val eventType = livingDamageBoundaryType(boundary)
            val body = indentJavaBlock(methodBlocks, "        ")
            """

    @SubscribeEvent
    public static void $generatedName($eventType $eventVar) {
$body
    }
""".trimEnd()
        }
    return if (generatedMethods.isEmpty()) methodText else retainedMethod + generatedMethods.joinToString("")
}

internal fun StructuralRefactorPass.livingDamageEventAliases(methodText: String, eventVar: String): Map<String, String> {
    val aliases = linkedMapOf<String, String>()
    val executable = maskJavaCommentsAndLiterals(methodText)
    Regex(
        """(?m)^[ \t]*(?:final\s+)?(?:LivingEntity|Entity|net\.minecraft\.world\.entity\.LivingEntity|net\.minecraft\.world\.entity\.Entity)\s+([A-Za-z_$][\w$]*)\s*=\s*(${Regex.escape(eventVar)}\.(?:getEntity|getSource)\(\)(?:\.getEntity\(\))?)\s*;"""
    ).findAll(executable).forEach { match ->
        aliases[match.groupValues[1]] = match.groupValues[2]
    }
    return aliases
}

internal fun StructuralRefactorPass.rewriteLivingDamageHelperBlock(blockText: String, aliases: Map<String, String>): String {
    var result = blockText
    aliases.forEach { (alias, expression) ->
        result = Regex("""(?<![\w$])${Regex.escape(alias)}(?![\w$])""").replace(result, expression)
    }
    return result
}

internal fun StructuralRefactorPass.uniqueLivingDamageHelperMethodName(
    originalMethodName: String,
    boundary: LivingDamageBoundary,
    existingNames: Set<String>
): String {
    val suffix = when (boundary) {
        LivingDamageBoundary.INCOMING -> "Incoming"
        LivingDamageBoundary.PRE -> "Pre"
        LivingDamageBoundary.POST -> "Post"
    }
    val base = "modporter" + originalMethodName.replaceFirstChar { it.uppercase() } + suffix
    var candidate = base
    var index = 2
    while (candidate in existingNames) {
        candidate = "$base${index++}"
    }
    return candidate
}

internal fun StructuralRefactorPass.livingDamageBoundaryType(boundary: LivingDamageBoundary): String =
    when (boundary) {
        LivingDamageBoundary.INCOMING -> "LivingIncomingDamageEvent"
        LivingDamageBoundary.PRE -> "LivingDamageEvent.Pre"
        LivingDamageBoundary.POST -> "LivingDamageEvent.Post"
    }

internal fun StructuralRefactorPass.unwrapSimpleNotCanceledGuards(source: String, eventVar: String): String {
    var result = source
    val pattern = Regex("""if\s*\(\s*!\s*${Regex.escape(eventVar)}\.isCanceled\(\)\s*\)\s*\{""")
    var cursor = 0
    while (true) {
        val match = pattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val lineStart = result.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        val guardIndent = lineIndentAt(result, match.range.first)
        val childIndent = guardIndent + "    "
        val body = result.substring(openBrace + 1, closeBrace)
            .replace(Regex("""(?m)^${Regex.escape(childIndent)}"""), guardIndent)
            .trim('\r', '\n')
        result = result.substring(0, lineStart) + body + result.substring(closeBrace + 1)
        cursor = lineStart + body.length
    }
    return result
}

internal fun StructuralRefactorPass.removeNotCanceledConjuncts(source: String, eventVar: String): String {
    val event = Regex.escape(eventVar)
    return source
        .replace(Regex("""!\s*$event\.isCanceled\(\)\s*&&\s*"""), "")
        .replace(Regex("""\s*&&\s*!\s*$event\.isCanceled\(\)"""), "")
}

internal fun StructuralRefactorPass.lineIndentAt(source: String, offset: Int): String {
    val lineStart = source.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
    return source.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
}

internal fun StructuralRefactorPass.damageSourceForDamageBonusTarget(methodText: String, livingExpression: String): String? {
    val hurtReceivers = linkedSetOf(livingExpression)
    Regex(
        """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^;\r\n{}]*\))?)*)\s+instanceof\s+LivingEntity\s+${Regex.escape(livingExpression)}\b"""
    ).findAll(methodText)
        .mapTo(hurtReceivers) { it.groupValues[1].trim() }
    val sources = linkedSetOf<String>()
    for (receiver in hurtReceivers) {
        var cursor = 0
        val token = "$receiver.hurt("
        while (cursor < methodText.length) {
            val tokenIndex = methodText.indexOf(token, cursor)
            if (tokenIndex < 0) break
            val openParen = tokenIndex + "$receiver.hurt".length
            val closeParen = findMatchingParen(methodText, openParen)
            if (closeParen < 0) break
            val args = splitTopLevelJavaArgs(methodText.substring(openParen + 1, closeParen))
            if (args.size >= 2) {
                sources += args[0].trim()
            }
            cursor = closeParen + 1
        }
    }
    if (sources.isEmpty()) {
        val playerBinding = Regex(
            """if\s*\(\s*!\s*\(\s*${Regex.escape(livingExpression)}\s+instanceof\s+(?:net\.minecraft\.world\.entity\.player\.)?Player\s+([A-Za-z_$][\w$]*)\s*\)\s*\)\s*(?:\{\s*)?return\s*;(?:\s*})?"""
        ).find(methodText)
        if (playerBinding != null) {
            val player = playerBinding.groupValues[1]
            val playerAttackSource = "$player.damageSources().playerAttack($player)"
            if (maskJavaCommentsAndLiterals(methodText).contains(playerAttackSource)) {
                sources += playerAttackSource
            }
        }
    }
    return sources.singleOrNull()
}

internal fun StructuralRefactorPass.sourceProvenServerLevelExpressionForDamageBonus(methodText: String): String? {
    val damageBonusOffset = methodText.indexOf("EnchantmentHelper.getDamageBonus(")
    if (damageBonusOffset < 0) return null
    val prefix = methodText.substring(0, damageBonusOffset)
    Regex("""this\.level\(\)\s+instanceof\s+ServerLevel\s+([A-Za-z_$][\w$]*)""")
        .find(prefix)
        ?.groupValues
        ?.get(1)
        ?.let { return it }
    Regex("""\b(?:net\.minecraft\.server\.level\.)?ServerLevel\s+([A-Za-z_$][\w$]*)\b""")
        .find(prefix)
        ?.groupValues
        ?.get(1)
        ?.let { return it }
    val serverCheckedLevels = Regex("""\b(?:net\.minecraft\.world\.level\.)?Level\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(prefix)
        .map { it.groupValues[1] }
        .filter { levelName ->
            hasClientSideEarlyReturn(prefix, levelName)
        }
        .distinct()
        .toList()
    if (serverCheckedLevels.size == 1) {
        return "(ServerLevel) ${serverCheckedLevels.single()}"
    }
    if (hasClientSideEarlyReturn(prefix, "this.level()")) {
        return "(ServerLevel) this.level()"
    }
    if (hasServerSideGuard(prefix, "this.level()")) {
        return "(ServerLevel) this.level()"
    }
    return null
}

internal fun StructuralRefactorPass.hasClientSideEarlyReturn(sourcePrefix: String, levelExpression: String): Boolean {
    val escaped = Regex.escape(levelExpression)
    return Regex(
        """if\s*\(\s*$escaped\.isClientSide(?:\(\))?\s*\)\s*""" +
            """(?:\{\s*)?return(?:\s+[^;{}]+)?\s*;(?:\s*})?"""
    )
        .containsMatchIn(sourcePrefix)
}

internal fun StructuralRefactorPass.hasServerSideGuard(sourcePrefix: String, levelExpression: String): Boolean {
    val escaped = Regex.escape(levelExpression)
    return Regex("""if\s*\(\s*!\s*$escaped\.isClientSide(?:\(\))?\b""")
        .containsMatchIn(sourcePrefix)
}

internal fun StructuralRefactorPass.migrateLegacyMobCustomDamageSourceAttacks(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("doHurtTarget") ||
        !executableCode.contains("EnchantmentHelper.getDamageBonus(") ||
        !executableCode.contains("EnchantmentHelper.getKnockbackBonus(") ||
        !executableCode.contains("EnchantmentHelper.getFireAspect(") ||
        !executableCode.contains("doEnchantDamageEffects(")) {
        return source
    }

    val method = javaMethodRanges(executableCode)
        .singleOrNull { candidate ->
            candidate.name == "doHurtTarget" &&
                Regex("""\bboolean\s+doHurtTarget\s*\(\s*(?:net\.minecraft\.world\.entity\.)?Entity\s+[A-Za-z_$][\w$]*\s*\)""")
                    .containsMatchIn(candidate.header)
        }
        ?: return source
    val methodText = source.substring(method.range)
    val executableMethodText = executableCode.substring(method.range)
    val hurtCall = Regex("""boolean\s+([A-Za-z_$][\w$]*)\s*=\s*entity\.hurt\(\s*(.+?)\s*,\s*f\s*\);""")
        .find(executableMethodText)
        ?: return source
    val flagVar = hurtCall.groupValues[1]
    val damageSourceRange = hurtCall.groups[2]?.range ?: return source
    val damageSourceExpr = methodText.substring(damageSourceRange.first, damageSourceRange.last + 1).trim()
    val edits = mutableListOf<Pair<IntRange, String>>()

    val oldEnchantmentBlock = Regex(
        """(?s)\s*float\s+f1\s*=\s*\(float\)\s*this\.getAttributeValue\(Attributes\.ATTACK_KNOCKBACK\);\s*if\s*\(\s*entity\s+instanceof\s+LivingEntity\s+[A-Za-z_$][\w$]*\s*\)\s*\{.*?EnchantmentHelper\.getKnockbackBonus\(this\).*?\}\s*int\s+[A-Za-z_$][\w$]*\s*=\s*EnchantmentHelper\.getFireAspect\(this\);\s*if\s*\(\s*[A-Za-z_$][\w$]*\s*>\s*0\s*\)\s*\{\s*entity\.igniteForSeconds\([^;]+;\s*\}\s*"""
    )
    val oldEnchantmentMatch = oldEnchantmentBlock.find(executableMethodText) ?: return source
    edits += oldEnchantmentMatch.range to """
		DamageSource damagesource = $damageSourceExpr;
		if (this.level() instanceof ServerLevel serverlevel) {
			f = EnchantmentHelper.modifyDamage(serverlevel, this.getWeaponItem(), entity, damagesource, f);
		}

            """.trimIndent().prependIndent("\t\t").removePrefix("\t\t")
    edits += hurtCall.range to "boolean $flagVar = entity.hurt(damagesource, f);"
    val knockbackGate = Regex("""if\s*\(\s*${Regex.escape(flagVar)}\s*\)\s*\{\s*\r?\n\s*if\s*\(\s*f1\s*>""")
        .find(executableMethodText)
        ?: return source
    edits += knockbackGate.range to methodText.substring(knockbackGate.range.first, knockbackGate.range.last + 1)
        .replace(
            Regex("""if\s*\(\s*f1\s*>"""),
            "float f1 = this.getKnockback(entity, damagesource);\n\t\t\tif (f1 >"
        )
    val shieldBlock = Regex(
        """(?s)\s*if\s*\(\s*entity\s+instanceof\s+Player\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*this\.maybeDisableShield\(.*?\);\s*\}\s*"""
    ).find(executableMethodText) ?: return source
    edits += shieldBlock.range to "\n"
    val postAttack = Regex("""this\.doEnchantDamageEffects\(\s*this\s*,\s*entity\s*\)\s*;""")
        .find(executableMethodText)
        ?: return source
    edits += postAttack.range to """if (this.level() instanceof ServerLevel serverlevel1) {
				EnchantmentHelper.doPostAttackEffects(serverlevel1, entity, damagesource);
			}"""
    val replacement = applyStringEdits(methodText, edits)

    var result = source.substring(0, method.range.first) + replacement + source.substring(method.range.last + 1)
    result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    result = addImportIfMissing(result, "net.minecraft.world.damagesource.DamageSource")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyMobCustomDamageSourceUtilityAttacks(source: String): String {
    if (!source.contains("EnchantmentHelper.getDamageBonus(") ||
        !source.contains("EnchantmentHelper.getKnockbackBonus(") ||
        !source.contains("EnchantmentHelper.getFireAspect(") ||
        !source.contains(".doEnchantDamageEffects(") ||
        !source.contains("DamageSource")) {
        return source
    }

    val methodNames = Regex("""(?m)^[ \t]*(?:@Override\s*)?(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+([A-Za-z_$][\w$]*)\s*\(""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toList()
    var result = source
    var changed = false

    for (methodName in methodNames) {
        val methodText = javaDeclaredMethodText(result, methodName) ?: continue
        if (!methodText.contains("EnchantmentHelper.getDamageBonus(") ||
            !methodText.contains("EnchantmentHelper.getKnockbackBonus(") ||
            !methodText.contains("EnchantmentHelper.getFireAspect(") ||
            !methodText.contains(".doEnchantDamageEffects(")) {
            continue
        }
        val mob = Regex("""\bMob\s+([A-Za-z_$][\w$]*)\b""").find(methodText)?.groupValues?.get(1) ?: continue
        val hurtCall = Regex("""boolean\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.hurt\(\s*([^,]+?)\s*,\s*([A-Za-z_$][\w$]*)\s*\);""")
            .find(methodText)
            ?: continue
        val flag = hurtCall.groupValues[1]
        val target = hurtCall.groupValues[2]
        val damageSource = hurtCall.groupValues[3].trim()
        val damageVar = hurtCall.groupValues[4]

        var replacement = methodText
        replacement = Regex(
            """(?s)\s*float\s+([A-Za-z_$][\w$]*)\s*=\s*\(float\)\s*${Regex.escape(mob)}\.getAttributeValue\(Attributes\.ATTACK_KNOCKBACK\);\s*if\s*\(\s*${Regex.escape(target)}\s+instanceof\s+LivingEntity\s*\)\s*\{.*?EnchantmentHelper\.getKnockbackBonus\(\s*${Regex.escape(mob)}\s*\).*?\}\s*int\s+[A-Za-z_$][\w$]*\s*=\s*EnchantmentHelper\.getFireAspect\(\s*${Regex.escape(mob)}\s*\);\s*if\s*\(\s*[A-Za-z_$][\w$]*\s*>\s*0\s*\)\s*\{\s*${Regex.escape(target)}\.igniteForSeconds\([^;]+;\s*\}\s*"""
        ).replace(replacement) {
            "\n\t\tif ($mob.level() instanceof ServerLevel serverlevel) {\n" +
                "\t\t\t$damageVar = EnchantmentHelper.modifyDamage(serverlevel, $mob.getWeaponItem(), $target, $damageSource, $damageVar);\n" +
                "\t\t}\n"
        }
        if (replacement == methodText) continue

        replacement = replacement.replace(hurtCall.value, "boolean $flag = $target.hurt($damageSource, $damageVar);")
        replacement = Regex("""if\s*\(\s*$flag\s*\)\s*\{\s*\r?\n\s*if\s*\(\s*[A-Za-z_$][\w$]*\s*>""")
            .replace(replacement) { match ->
                match.value.replace(
                    Regex("""if\s*\(\s*[A-Za-z_$][\w$]*\s*>"""),
                    "float f1 = (float)$mob.getAttributeValue(Attributes.ATTACK_KNOCKBACK);\n" +
                        "\t\t\tif ($mob.level() instanceof ServerLevel serverlevel2) {\n" +
                        "\t\t\t\tf1 = EnchantmentHelper.modifyKnockback(serverlevel2, $mob.getWeaponItem(), $target, $damageSource, f1);\n" +
                        "\t\t\t}\n" +
                        "\t\t\tif (f1 >"
                )
            }
        replacement = Regex(
            """(?s)\s*if\s*\(\s*${Regex.escape(target)}\s+instanceof\s+Player\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*${Regex.escape(mob)}\.maybeDisableShield\(.*?\);\s*\}\s*"""
        ).replace(replacement, "\n")
        replacement = Regex("""${Regex.escape(mob)}\.doEnchantDamageEffects\(\s*${Regex.escape(mob)}\s*,\s*${Regex.escape(target)}\s*\)\s*;""")
            .replace(
                replacement,
                """if ($mob.level() instanceof ServerLevel serverlevel1) {
				EnchantmentHelper.doPostAttackEffects(serverlevel1, $target, $damageSource);
			}"""
            )

        result = result.replace(methodText, replacement)
        changed = true
    }

    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    return result
}

internal fun StructuralRefactorPass.migrateMobSpawnEquipmentSignatures(source: String): String {
    if (!source.contains("populateDefaultEquipmentSlots") &&
        !source.contains("populateDefaultEquipmentEnchantments") &&
        !source.contains("finalizeSpawn(")) {
        return source
    }

    var result = source
    result = Regex("""super\.finalizeSpawn\(\s*([^,]+)\s*,\s*([^,]+)\s*,\s*([^,]+)\s*,\s*([^,\)]+)\s*,\s*dataTag\s*\)""")
        .replace(result) { match ->
            "super.finalizeSpawn(${match.groupValues[1].trim()}, ${match.groupValues[2].trim()}, ${match.groupValues[3].trim()}, ${match.groupValues[4].trim()})"
        }
    result = rewriteLegacyFinalizeSpawnCalls(result)
    result = Regex("""\b(this\.)?populateDefaultEquipmentSlots\(\s*([A-Za-z_$][\w$]*)\s*,\s*\2\.getRandom\(\)\s*,\s*([^)]+)\)""")
        .replace(result) { match ->
            "${match.groupValues[1]}populateDefaultEquipmentSlots(${match.groupValues[2]}.getRandom(), ${match.groupValues[3].trim()})"
        }
    result = Regex("""\b(this\.)?populateDefaultEquipmentSlots\(\s*([A-Za-z_$][\w$]*)\.getRandom\(\)\s*,\s*([^)]+)\)""")
        .replace(result) { match ->
            "${match.groupValues[1]}populateDefaultEquipmentSlots(${match.groupValues[2]}.getRandom(), ${match.groupValues[3].trim()})"
        }
    result = Regex("""\b(this\.)?populateDefaultEquipmentEnchantments\(\s*([A-Za-z_$][\w$]*)\.getRandom\(\)\s*,\s*([^)]+)\)""")
        .replace(result) { match ->
            "${match.groupValues[1]}populateDefaultEquipmentEnchantments(${match.groupValues[2]}, ${match.groupValues[2]}.getRandom(), ${match.groupValues[3].trim()})"
        }
    result = Regex(
        """protected\s+void\s+populateDefaultEquipmentSlots\(\s*RandomSource\s+([A-Za-z_$][\w$]*)\s*,\s*DifficultyInstance\s+([A-Za-z_$][\w$]*)\s*\)"""
    ).replace(result) { match ->
        "protected void populateDefaultEquipmentSlots(RandomSource ${match.groupValues[1]}, DifficultyInstance ${match.groupValues[2]})"
    }
    result = Regex(
        """protected\s+void\s+populateDefaultEquipmentSlots\(\s*ServerLevelAccessor\s+[A-Za-z_$][\w$]*\s*,\s*RandomSource\s+([A-Za-z_$][\w$]*)\s*,\s*DifficultyInstance\s+([A-Za-z_$][\w$]*)\s*\)"""
    ).replace(result) { match ->
        "protected void populateDefaultEquipmentSlots(RandomSource ${match.groupValues[1]}, DifficultyInstance ${match.groupValues[2]})"
    }
    result = Regex(
        """protected\s+void\s+populateDefaultEquipmentEnchantments\(\s*RandomSource\s+([A-Za-z_$][\w$]*)\s*,\s*DifficultyInstance\s+([A-Za-z_$][\w$]*)\s*\)"""
    ).replace(result) { match ->
        "protected void populateDefaultEquipmentEnchantments(ServerLevelAccessor accessor, RandomSource ${match.groupValues[1]}, DifficultyInstance ${match.groupValues[2]})"
    }
    if (result.contains("ServerLevelAccessor accessor")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.ServerLevelAccessor")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyFinalizeSpawnEquipmentRandomSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("finalizeSpawn(") ||
        !executableCode.contains("populateDefaultEquipmentSlots(this.random")) {
        return source
    }
    val id = """[A-Za-z_$][\w$]*"""
    val signature = Regex(
        """finalizeSpawn\s*\(\s*ServerLevelAccessor\s+($id)\s*,\s*DifficultyInstance\s+($id)\b"""
    )
    val edits = mutableListOf<Pair<IntRange, String>>()
    javaMethodRangesIncludingDefault(executableCode).forEach { method ->
        if (method.name != "finalizeSpawn") return@forEach
        val methodText = executableCode.substring(method.range)
        val signatureMatch = signature.find(methodText) ?: return@forEach
        val levelName = signatureMatch.groupValues[1]
        val difficultyName = signatureMatch.groupValues[2]
        val equipmentCalls = Regex(
            """([ \t]*)populateDefaultEquipmentSlots\(this\.random,\s*${Regex.escape(difficultyName)}\);\s*\r?\n[ \t]*populateDefaultEquipmentEnchantments\(this\.random,\s*${Regex.escape(difficultyName)}\);"""
        )
        equipmentCalls.findAll(methodText).forEach { match ->
            val indent = match.groupValues[1]
            edits += (method.range.first + match.range.first)..(method.range.first + match.range.last) to
                "${indent}RandomSource randomsource = $levelName.getRandom();\n" +
                "${indent}populateDefaultEquipmentSlots(randomsource, $difficultyName);\n" +
                "${indent}populateDefaultEquipmentEnchantments($levelName, randomsource, $difficultyName);"
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.removeLegacyFinalizeSpawnTagGuards(source: String, tagNames: Set<String>): String {
    if (tagNames.isEmpty() || !source.contains("finalizeSpawn(")) return source

    var result = source
    tagNames.forEach { tagName ->
        result = Regex("""(?m)^[ \t]*\*\s*@param\s+${Regex.escape(tagName)}\b[^\r\n]*(?:\r?\n)?""")
            .replace(result, "")
    }

    var changed: Boolean
    do {
        changed = false
        val removals = mutableListOf<IntRange>()
        val methodPattern = Regex(
            """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|static|final|synchronized)\s+)*SpawnGroupData\s+finalizeSpawn\s*\([^;{}]*\)\s*\{"""
        )

        methodPattern.findAll(result).forEach { method ->
            val methodOpen = result.indexOf('{', method.range.last)
            val methodClose = if (methodOpen >= 0) findMatchingBrace(result, methodOpen) else -1
            if (methodClose <= methodOpen) return@forEach

            var cursor = methodOpen + 1
            while (cursor in (methodOpen + 1) until methodClose) {
                val ifIndex = result.indexOf("if", cursor)
                if (ifIndex < 0 || ifIndex >= methodClose) break
                if (!isStandaloneJavaToken(result, ifIndex, "if")) {
                    cursor = ifIndex + 2
                    continue
                }
                val lineStart = result.lastIndexOf('\n', ifIndex).let { if (it < 0) 0 else it + 1 }
                if (result.substring(lineStart, ifIndex).any { it != ' ' && it != '\t' }) {
                    cursor = ifIndex + 2
                    continue
                }
                val conditionOpen = skipWhitespace(result, ifIndex + 2)
                if (conditionOpen >= result.length || result[conditionOpen] != '(') {
                    cursor = ifIndex + 2
                    continue
                }
                val conditionClose = findMatchingParen(result, conditionOpen)
                if (conditionClose < 0 || conditionClose >= methodClose) {
                    cursor = ifIndex + 2
                    continue
                }
                val condition = result.substring(conditionOpen + 1, conditionClose)
                if (!conditionHasLegacyFinalizeSpawnTagGuard(condition, tagNames)) {
                    cursor = conditionClose + 1
                    continue
                }
                val blockOpen = skipWhitespace(result, conditionClose + 1)
                if (blockOpen >= result.length || result[blockOpen] != '{') {
                    cursor = conditionClose + 1
                    continue
                }
                val blockClose = findMatchingBrace(result, blockOpen)
                if (blockClose < 0 || blockClose >= methodClose) {
                    cursor = blockOpen + 1
                    continue
                }

                var removeEnd = blockClose + 1
                if (removeEnd < result.length && result[removeEnd] == '\r') removeEnd++
                if (removeEnd < result.length && result[removeEnd] == '\n') removeEnd++
                removals.add(lineStart until removeEnd)
                cursor = removeEnd
            }
        }

        if (removals.isNotEmpty()) {
            val builder = StringBuilder(result)
            removals.sortedByDescending { it.first }.forEach { range ->
                builder.delete(range.first, range.last + 1)
            }
            result = builder.toString()
            changed = true
        }
    } while (changed)

    return result
}

internal fun StructuralRefactorPass.conditionHasLegacyFinalizeSpawnTagGuard(condition: String, tagNames: Set<String>): Boolean =
    tagNames.any { tagName ->
        Regex("""(?<![\w$])${Regex.escape(tagName)}\s*!=\s*null(?![\w$])""").containsMatchIn(condition)
    }

internal fun StructuralRefactorPass.isStandaloneJavaToken(source: String, index: Int, token: String): Boolean {
    if (!source.startsWith(token, index)) return false
    val before = source.getOrNull(index - 1)
    val after = source.getOrNull(index + token.length)
    return before?.let { !it.isLetterOrDigit() && it != '_' && it != '$' } != false &&
        after?.let { !it.isLetterOrDigit() && it != '_' && it != '$' } != false
}

internal fun StructuralRefactorPass.rewriteLegacyFinalizeSpawnCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".finalizeSpawn(") ||
        !executableCode.contains("SpawnGroupData") ||
        !executableCode.contains("MobSpawnType")) {
        return source
    }

    return rewriteExecutableJavaCall(source, "finalizeSpawn") { receiver, args ->
        if (args.size != 5) return@rewriteExecutableJavaCall null
        val tagArg = args[4]
        if (!tagArg.matches(Regex("""[A-Za-z_$][\w$]*"""))) return@rewriteExecutableJavaCall null
        "$receiver.finalizeSpawn(${args.take(4).joinToString(", ")})"
    }
}

internal fun StructuralRefactorPass.migrateLegacyFinalizeSpawnMixinDescriptors(source: String): String {
    val legacyMobFinalize = "Lnet/minecraft/world/entity/Mob;finalizeSpawn(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/entity/SpawnGroupData;"
    val legacyEventHookFinalize = "onFinalizeSpawn(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/entity/SpawnGroupData;"
    if (!source.contains(legacyMobFinalize) && !source.contains(legacyEventHookFinalize)) return source

    val migratedMobFinalize = "Lnet/minecraft/world/entity/Mob;finalizeSpawn(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;"
    val migratedEventHookFinalize = "finalizeMobSpawn(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;"
    var result = source
        .replace(legacyMobFinalize, migratedMobFinalize)
        .replace(legacyEventHookFinalize, migratedEventHookFinalize)
        .replace(
            "Mob#finalizeSpawn(ServerLevelAccessor, DifficultyInstance, MobSpawnType, SpawnGroupData, CompoundTag)",
            "Mob#finalizeSpawn(ServerLevelAccessor, DifficultyInstance, MobSpawnType, SpawnGroupData)"
        )

    val id = """[A-Za-z_$][\w$]*"""
    result = Regex(
        """(onFinalizeSpawn\s*\(\s*Mob\s+$id\s*,\s*ServerLevelAccessor\s+$id\s*,\s*DifficultyInstance\s+$id\s*,\s*MobSpawnType\s+$id\s*,\s*SpawnGroupData\s+$id\s*),\s*CompoundTag\s+$id\s*,\s*CallbackInfoReturnable\s*<\s*SpawnGroupData\s*>\s+($id)\s*\)"""
    ).replace(result) { match ->
        "${match.groupValues[1]}, CallbackInfoReturnable<SpawnGroupData> ${match.groupValues[2]})"
    }
    val withoutCompoundImport = removeImport(result, "net.minecraft.nbt.CompoundTag")
    if (!Regex("""\bCompoundTag\b""").containsMatchIn(withoutCompoundImport)) {
        result = withoutCompoundImport
    }
    return result
}

internal data class JavaAnnotatedMethodBlock(
    val start: Int,
    val endExclusive: Int,
    val bodyStart: Int,
    val bodyEnd: Int,
    val methodIndent: String,
    val text: String
)

internal fun StructuralRefactorPass.migrateLegacyAbstractClientPlayerCapeMixinToSkinWrapSource(source: String): String {
    if (!source.contains("isCapeLoaded()Z") ||
        !source.contains("getCloakTextureLocation()Lnet/minecraft/resources/ResourceLocation;") ||
        !Regex("""@Mixin\s*\(\s*(?:value\s*=\s*)?(?:AbstractClientPlayer|net\.minecraft\.client\.player\.AbstractClientPlayer)\.class\s*\)""")
            .containsMatchIn(source)) {
        return source
    }

    val loadedBlock = findAnnotatedMixinMethodBlock(
        source,
        "isCapeLoaded()Z",
        "@Inject"
    ) ?: return source
    val cloakBlock = findAnnotatedMixinMethodBlock(
        source,
        "getCloakTextureLocation()Lnet/minecraft/resources/ResourceLocation;",
        "@Inject"
    ) ?: return source
    val cloakHeader = cloakBlock.text.substring(0, cloakBlock.bodyStart - cloakBlock.start)
    val callbackName = Regex(
        """CallbackInfoReturnable\s*<\s*(?:net\.minecraft\.resources\.)?ResourceLocation\s*>\s+([A-Za-z_$][\w$]*)"""
    ).find(cloakHeader)?.groupValues?.get(1) ?: return source

    var migratedBody = cloakBlock.text.substring(cloakBlock.bodyStart - cloakBlock.start, cloakBlock.bodyEnd - cloakBlock.start)
        .trim('\r', '\n')
    val bodyIndent = Regex("""(?m)^([ \t]+)\S""")
        .find(migratedBody)
        ?.groupValues
        ?.get(1)
        ?: cloakBlock.methodIndent + "    "
    val skinName = uniqueJavaLocalName(migratedBody, "skin")
    var returnedCapeTexture = false
    migratedBody = Regex("""\b${Regex.escape(callbackName)}\.setReturnValue\(\s*([^;\r\n]+?)\s*\);""")
        .replace(migratedBody) { match ->
            returnedCapeTexture = true
            val textureExpression = match.groupValues[1].trim()
            "return new PlayerSkin($skinName.texture(), $skinName.textureUrl(), $textureExpression, $skinName.elytraTexture(), $skinName.model(), $skinName.secure());"
        }
    if (!returnedCapeTexture) return source

    migratedBody = insertPlayerSkinOriginalCall(migratedBody, bodyIndent, skinName)
        .trimEnd() + "\n${bodyIndent}return $skinName;"
    val wrapMethod = buildString {
        append(cloakBlock.methodIndent)
        append("@WrapMethod(method = \"getSkin()Lnet/minecraft/client/resources/PlayerSkin;\")\n")
        append(cloakBlock.methodIndent)
        append("private PlayerSkin getSkin(Operation<PlayerSkin> original) {\n")
        append(migratedBody)
        append("\n")
        append(cloakBlock.methodIndent)
        append("}")
    }

    var result = source
    listOf(
        loadedBlock.start to (loadedBlock.endExclusive to ""),
        cloakBlock.start to (cloakBlock.endExclusive to wrapMethod)
    ).sortedByDescending { it.first }
        .forEach { (start, endAndReplacement) ->
            val (endExclusive, replacement) = endAndReplacement
            result = result.substring(0, start) + replacement + result.substring(endExclusive)
        }

    result = addImportIfMissing(result, "com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod")
    result = addImportIfMissing(result, "com.llamalad7.mixinextras.injector.wrapoperation.Operation")
    result = addImportIfMissing(result, "net.minecraft.client.resources.PlayerSkin")
    result = removeImportIfTokenAbsent(result, "org.spongepowered.asm.mixin.injection.Inject", Regex("""(?<![\w$])@Inject\b"""))
    result = removeImportIfTokenAbsent(result, "org.spongepowered.asm.mixin.injection.At", Regex("""(?<![\w$])@At\b"""))
    result = removeImportIfTokenAbsent(
        result,
        "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable",
        Regex("""(?<![\w$])CallbackInfoReturnable(?![\w$])""")
    )
    return result
}

internal fun StructuralRefactorPass.findAnnotatedMixinMethodBlock(
    source: String,
    descriptor: String,
    annotationName: String
): JavaAnnotatedMethodBlock? {
    val descriptorIndex = source.indexOf(descriptor)
    if (descriptorIndex < 0) return null
    val annotationIndex = source.lastIndexOf(annotationName, descriptorIndex)
    if (annotationIndex < 0) return null
    val methodMatch = Regex(
        """(?m)^[ \t]*(?:private|protected|public)\s+(?:static\s+)?[\w.$<>\[\]?,\s]+\s+[A-Za-z_$][\w$]*\s*\("""
    ).find(source, descriptorIndex) ?: return null
    val openBrace = source.indexOf('{', methodMatch.range.last)
    if (openBrace < 0) return null
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace < 0) return null
    val annotationLineStart = lineStartAt(source, annotationIndex)
    val start = includeLeadingJavadoc(source, annotationLineStart)
    var endExclusive = closeBrace + 1
    if (source.getOrNull(endExclusive) == '\r') endExclusive++
    if (source.getOrNull(endExclusive) == '\n') endExclusive++
    val methodIndent = source.substring(annotationLineStart, annotationIndex)
        .takeWhile { it == ' ' || it == '\t' }
    return JavaAnnotatedMethodBlock(
        start = start,
        endExclusive = endExclusive,
        bodyStart = openBrace + 1,
        bodyEnd = closeBrace,
        methodIndent = methodIndent,
        text = source.substring(start, endExclusive)
    )
}

internal fun StructuralRefactorPass.includeLeadingJavadoc(source: String, annotationLineStart: Int): Int {
    val beforeAnnotation = source.substring(0, annotationLineStart)
    val trimmedBefore = beforeAnnotation.trimEnd()
    if (!trimmedBefore.endsWith("*/")) return annotationLineStart
    val javadocEnd = trimmedBefore.length - 2
    val javadocStart = source.lastIndexOf("/**", javadocEnd)
    if (javadocStart < 0) return annotationLineStart
    val between = source.substring(javadocEnd + 2, annotationLineStart)
    if (between.any { it != ' ' && it != '\t' && it != '\r' && it != '\n' }) return annotationLineStart
    return lineStartAt(source, javadocStart)
}

internal fun StructuralRefactorPass.insertPlayerSkinOriginalCall(body: String, bodyIndent: String, skinName: String): String {
    val originalCall = "${bodyIndent}PlayerSkin $skinName = original.call();"
    val playerLocal = Regex(
        """(?m)^([ \t]*(?:net\.minecraft\.client\.player\.)?AbstractClientPlayer\s+[A-Za-z_$][\w$]*\s*=\s*\(\s*(?:net\.minecraft\.client\.player\.)?AbstractClientPlayer\s*\)\s*\(\s*Object\s*\)\s*this\s*;)[ \t]*$"""
    ).find(body)
    if (playerLocal != null) {
        val insertAt = playerLocal.range.last + 1
        return body.substring(0, insertAt) + "\n" + originalCall + body.substring(insertAt)
    }
    val firstContent = Regex("""\S""").find(body)?.range?.first ?: 0
    return body.substring(0, firstContent) + "PlayerSkin $skinName = original.call();\n$bodyIndent" + body.substring(firstContent)
}

internal fun StructuralRefactorPass.uniqueJavaLocalName(source: String, base: String): String {
    var suffix = 0
    while (true) {
        val candidate = if (suffix == 0) base else "$base$suffix"
        if (!Regex("""(?<![\w$])${Regex.escape(candidate)}(?![\w$])""").containsMatchIn(source)) {
            return candidate
        }
        suffix++
    }
}

internal fun StructuralRefactorPass.removeImportIfTokenAbsent(source: String, importName: String, token: Regex): String {
    val withoutImport = removeImport(source, importName)
    return if (!token.containsMatchIn(withoutImport)) withoutImport else source
}

internal fun StructuralRefactorPass.lineStartAt(source: String, index: Int): Int =
    source.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }

internal fun StructuralRefactorPass.migrateLegacyLevelStorageSourceLoadSummariesLambdaMixinDescriptor(source: String): String {
    val legacyLambda = "lambda\$loadLevelSummaries\$2(Lnet/minecraft/world/level/storage/LevelStorageSource\$LevelDirectory;)Lnet/minecraft/world/level/storage/LevelSummary;"
    if (!source.contains(legacyLambda)) return source
    val targetsLevelStorageSource = Regex(
        """@Mixin\s*\(\s*(?:LevelStorageSource|net\.minecraft\.world\.level\.storage\.LevelStorageSource)\.class\s*\)"""
    ).containsMatchIn(source)
    if (!targetsLevelStorageSource) return source
    if (!source.contains("Lnet/minecraft/util/DirectoryLock;isLocked(Ljava/nio/file/Path;)Z")) return source

    val migratedLambda = "lambda\$loadLevelSummaries\$3(Lnet/minecraft/world/level/storage/LevelStorageSource\$LevelDirectory;)Lnet/minecraft/world/level/storage/LevelSummary;"
    return source.replace(legacyLambda, migratedLambda)
}

internal fun StructuralRefactorPass.migrateLegacyNeoForgeModelApiSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("CustomLoaderBuilder") &&
        !executableCode.contains("IUnbakedGeometry") &&
        !executableCode.contains("BlockElementFace") &&
        !executableCode.contains("FaceBakery") &&
        !executableCode.contains("UnbakedGeometryHelper") &&
        !executableCode.contains("new ResourceLocation(")) {
        return source
    }

    var result = source

    if (result.contains("extends CustomLoaderBuilder")) {
        result = rewriteSuperConstructorCalls(result) { args ->
            if (args.size == 3) "super(${args.joinToString(", ")}, false)" else null
        }
    }

    val modelLocationReplacements = linkedMapOf<String, String>()
    result = Regex(
        """public\s+BakedModel\s+bake\(\s*IGeometryBakingContext\s+([A-Za-z_$][\w$]*)\s*,\s*ModelBaker\s+([A-Za-z_$][\w$]*)\s*,\s*Function<Material,\s*TextureAtlasSprite>\s+([A-Za-z_$][\w$]*)\s*,\s*ModelState\s+([A-Za-z_$][\w$]*)\s*,\s*ItemOverrides\s+([A-Za-z_$][\w$]*)\s*,\s*ResourceLocation\s+([A-Za-z_$][\w$]*)\s*\)"""
    ).replace(result) { match ->
        val contextName = match.groupValues[1]
        val modelLocationName = match.groupValues[6]
        modelLocationReplacements[modelLocationName] =
            "net.minecraft.resources.ResourceLocation.parse($contextName.getModelName())"
        "public BakedModel bake(IGeometryBakingContext $contextName, ModelBaker ${match.groupValues[2]}, Function<Material, TextureAtlasSprite> ${match.groupValues[3]}, ModelState ${match.groupValues[4]}, ItemOverrides ${match.groupValues[5]})"
    }

    result = rewriteJavaCall(result, "bakeElementFace") { receiver, args ->
        if (receiver != "UnbakedGeometryHelper" || args.size != 6) return@rewriteJavaCall null
        "$receiver.bakeElementFace(${args.take(5).joinToString(", ")})"
    }
    result = rewriteJavaCall(result, "bakeQuad") { receiver, args ->
        if (args.size != 9) return@rewriteJavaCall null
        "$receiver.bakeQuad(${args.take(8).joinToString(", ")})"
    }
    for ((modelLocationName, replacement) in modelLocationReplacements) {
        result = Regex("""\b${Regex.escape(modelLocationName)}\b""").replace(result, replacement)
    }

    result = rewriteExecutableJavaNew(result, "BlockElementFace") { args ->
        when {
            args.size == 5 && args[4] == "null" ->
                "new BlockElementFace(${args.take(4).joinToString(", ")})"
            args.size == 5 ->
                "new BlockElementFace(${args.joinToString(", ")}, new org.apache.commons.lang3.mutable.MutableObject<>())"
            else -> null
        }
    }

    result = rewriteExecutableJavaNew(result, "ResourceLocation") { args ->
        when (args.size) {
            1 -> "ResourceLocation.parse(${args[0]})"
            2 -> "ResourceLocation.fromNamespaceAndPath(${args[0]}, ${args[1]})"
            else -> null
        }
    }

    if (result.contains("BlockElementFace")) {
        val faceVariables = Regex("""\bBlockElementFace\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(maskJavaCommentsAndLiterals(result))
            .map { it.groupValues[1] }
            .toSet()
        for (variable in faceVariables) {
            val escaped = Regex.escape(variable)
            result = replaceExecutableJavaRegex(result, Regex("""\b$escaped\.uv\.uvs\b""")) { "$variable.uv().uvs" }
            result = replaceExecutableJavaRegex(result, Regex("""\b$escaped\.uv\.rotation\b""")) { "$variable.uv().rotation" }
            result = replaceExecutableJavaRegex(result, Regex("""\b$escaped\.cullForDirection\b(?!\s*\()""")) { "$variable.cullForDirection()" }
            result = replaceExecutableJavaRegex(result, Regex("""\b$escaped\.texture\b(?!\s*\()""")) { "$variable.texture()" }
            result = replaceExecutableJavaRegex(result, Regex("""\b$escaped\.tintIndex\b(?!\s*\()""")) { "$variable.tintIndex()" }
            result = replaceExecutableJavaRegex(result, Regex("""\b$escaped\.getFaceData\(\)""")) { "$variable.faceData()" }
        }
    }

    return result
}

internal fun StructuralRefactorPass.migrateAttributeModifierResourceLocationIds(
    source: String,
    crossFileAttributeModifierIds: Set<String> = emptySet(),
    attributeModifierMethods: Map<String, Set<String>> = emptyMap(),
    genericMethodReturnTypes: Map<String, String> = emptyMap(),
    attributeModifierIdMethodArguments: Set<AttributeModifierIdMethodArgument> = emptySet(),
    declaredModClassIdExpression: String? = null
): String {
    val ownerName = javaTopLevelTypeName(source)
    val hasAttributeModifierIdMethodArguments = ownerName != null &&
        attributeModifierIdMethodArguments.any { it.owner == ownerName }
    if (source.contains("getDefaultAttributeModifiers(EquipmentSlot") &&
        source.contains("ImmutableMultimap") &&
        source.contains("new AttributeModifier(")) {
        return source
    }
    if (!source.contains("AttributeModifier") &&
        !source.contains("UUID.fromString") &&
        !source.contains(".hasModifier(") &&
        !source.contains(".removeModifier(") &&
        !source.contains(".getModifier(") &&
        !source.contains(".addAttributeModifier(") &&
        !hasAttributeModifierIdMethodArguments) {
        return source
    }

    val namespace = inferModAccess(source)?.modIdExpression
        ?: declaredModClassIdExpression

    val idAliases = mutableMapOf<String, String>()
    var result = source
    fun isDeclaredResourceLocationExpression(expression: String): Boolean {
        val trimmed = expression.trim()
        if (trimmed.startsWith("ResourceLocation.") ||
            trimmed.startsWith("net.minecraft.resources.ResourceLocation.")) {
            return true
        }
        if (!Regex("""^(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*$""").matches(trimmed)) return false
        val simpleName = trimmed.substringAfterLast('.')
        val declaration = Regex("""\b(?:net\.minecraft\.resources\.)?ResourceLocation\s+${Regex.escape(simpleName)}\b""")
        return declaration.containsMatchIn(source) || declaration.containsMatchIn(result)
    }

    if (namespace != null) {
        fun idExpression(path: String): String =
            "net.minecraft.resources.ResourceLocation.fromNamespaceAndPath($namespace, \"$path\")"

        result = Regex("""\bUUID\s+([A-Z][A-Z0-9_]*)\s*=\s*UUID\.fromString\(\s*"[^"]+"\s*\)""")
            .replace(result) { match ->
                val name = match.groupValues[1]
                if (!isAttributeModifierIdUse(source, name, crossFileAttributeModifierIds, ownerName)) {
                    return@replace match.value
                }
                val id = idExpression(modifierConstantPathName(name))
                idAliases[name] = id
                "net.minecraft.resources.ResourceLocation $name = $id"
            }
        result = Regex("""\bUUID\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*_[A-Z0-9_]*MODIFIER[A-Z0-9_]*)\s*;""")
            .replace(result) { match ->
                val alias = match.groupValues[1]
                val target = match.groupValues[2]
                if (!isAttributeModifierIdUse(source, alias, crossFileAttributeModifierIds, ownerName) &&
                    !isAttributeModifierIdUse(source, target, crossFileAttributeModifierIds, ownerName)) {
                    return@replace match.value
                }
                "net.minecraft.resources.ResourceLocation $alias = $target;"
            }
        result = rewriteExecutableJavaNew(result, "AttributeModifier") { args ->
            if (args.size == 3) {
                val legacyName = args[0].trim()
                val amount = args[1].trim()
                val operation = args[2].trim()
                if (!Regex(""""(?:\\.|[^"\\])*"""").matches(legacyName)) return@rewriteExecutableJavaNew null
                if (amount.contains(",") || !Regex("""AttributeModifier\.Operation\.[A-Z_]+""").matches(operation)) {
                    return@rewriteExecutableJavaNew null
                }
                return@rewriteExecutableJavaNew "new AttributeModifier(${idExpression(modifierPathName(legacyName.trim('"')))}, $amount, $operation)"
            }
            if (args.size != 4) return@rewriteExecutableJavaNew null
            val legacyId = args[0].trim()
            val legacyName = args[1].trim()
            val amount = args[2].trim()
            val operation = args[3].trim()
            val id = when {
                legacyName.endsWith(".toString()") -> {
                    val existingId = legacyName.removeSuffix(".toString()").trim()
                    if (!isDeclaredResourceLocationExpression(existingId)) return@rewriteExecutableJavaNew null
                    existingId
                }
                legacyName.startsWith("\"") -> {
                    if (amount.contains(",") && isDeclaredResourceLocationExpression(legacyId)) {
                        legacyId
                    } else {
                        idExpression(modifierPathName(legacyName.trim('"')))
                    }
                }
                else -> return@rewriteExecutableJavaNew null
            }
            if (legacyName.startsWith("\"") && !amount.contains(",")) {
                idAliases[legacyId] = id
            }
            "new AttributeModifier($id, $amount, $operation)"
        }
        result = Regex("""\.addAttributeModifier\(\s*([^,]+)\s*,\s*"([^"]+)"\s*,""")
            .replace(result) { match ->
                val id = idExpression(modifierPathName(match.groupValues[2]))
                ".addAttributeModifier(${match.groupValues[1].trim()}, $id,"
            }

        for ((oldId, newId) in idAliases) {
            val escaped = Regex.escape(oldId)
            result = Regex("""\.(getModifier|hasModifier|removeModifier)\(\s*$escaped\s*\)""")
                .replace(result) { match -> ".${match.groupValues[1]}($newId)" }
        }
    }
    result = Regex("""\.addAttributeModifier\(\s*([^,]+)\s*,\s*([^,\r\n]+)\.toString\(\)\s*,""")
        .replace(result) { match ->
            val id = match.groupValues[2].trim()
            if (!isDeclaredResourceLocationExpression(id)) return@replace match.value
            ".addAttributeModifier(${match.groupValues[1].trim()}, $id,"
        }

    val modifierVariables = Regex("""\bAttributeModifier\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    val ownerAttributeModifierMethods = ownerName?.let { attributeModifierMethods[it] }.orEmpty() +
        declaredAttributeModifierMethodNames(result)
    fun methodsForType(typeName: String, typeParameterBounds: Map<String, Set<String>>): Set<String> {
        val simple = typeName.substringAfterLast('.')
        val methods = linkedSetOf<String>()
        methods += attributeModifierMethods[simple].orEmpty()
        typeParameterBounds[simple].orEmpty().forEach { bound ->
            methods += attributeModifierMethods[bound].orEmpty()
        }
        return methods
    }

    fun attributeModifierStreamLambdaParameters(scope: String): Set<String> {
        val id = """[A-Za-z_$][\w$]*"""
        val parameters = linkedSetOf<String>()
        val attributeModifierCollectionVariables = Regex(
            """\b(?:java\.util\.)?(?:Collection|Iterable|List|Set)\s*<\s*(?:net\.minecraft\.world\.entity\.ai\.attributes\.)?AttributeModifier\s*>\s+($id)\b"""
        ).findAll(scope).map { it.groupValues[1] }.toSet()
        Regex("""(?s)([^;{}]+?)\.stream\s*\(\s*\)\s*\.\w+\s*\(\s*\(?\s*($id)\s*\)?\s*->""")
            .findAll(scope)
            .forEach { match ->
                val streamSource = match.groupValues[1].trim()
                val root = streamSource.substringBefore('.').trim()
                val isAttributeModifierSource =
                    root in attributeModifierCollectionVariables ||
                        streamSource.endsWith(".getModifiers()") ||
                        streamSource.contains(".getAttributeModifiers(")
                if (isAttributeModifierSource) {
                    parameters += match.groupValues[2]
                }
            }
        return parameters
    }

    fun attributeModifierIdExpression(argument: String, callOffset: Int): String? {
        val trimmed = argument.trim()
        if (trimmed.endsWith(".id()")) return null
        if (trimmed in modifierVariables) return "$trimmed.id()"
        if (Regex("""^new\s+(?:net\.minecraft\.world\.entity\.ai\.attributes\.)?AttributeModifier\s*\(""")
                .containsMatchIn(trimmed)) {
            return "$trimmed.id()"
        }
        val scope = enclosingMethodText(result, callOffset) ?: result
        val variableTypes = javaLocalVariableTypes(scope, genericMethodReturnTypes)
        val typeParameterBounds = javaTypeParameterBounds(scope)
        val methodCall = Regex("""(?s)^(?:(.+)\.)?([A-Za-z_$][\w$]*)\s*\(.*\)$""").matchEntire(trimmed)
            ?: return null
        val receiver = methodCall.groupValues[1].trim()
        val methodName = methodCall.groupValues[2]
        if (receiver.isBlank()) {
            return if (methodName in ownerAttributeModifierMethods) "$trimmed.id()" else null
        }
        if (receiver == "this") {
            return if (methodName in ownerAttributeModifierMethods) "$trimmed.id()" else null
        }
        val receiverType = variableTypes[receiver]
        if (receiverType != null && methodName in methodsForType(receiverType, typeParameterBounds)) {
            return "$trimmed.id()"
        }
        if (methodName in attributeModifierMethods[receiver.substringAfterLast('.')].orEmpty()) {
            return "$trimmed.id()"
        }
        return null
    }

    fun isAttributeModifierExpression(expression: String, callOffset: Int): Boolean {
        val trimmed = expression.trim()
        if (trimmed in modifierVariables) return true
        if (Regex("""^new\s+(?:net\.minecraft\.world\.entity\.ai\.attributes\.)?AttributeModifier\s*\(""")
                .containsMatchIn(trimmed)) {
            return true
        }
        val scope = enclosingMethodText(result, callOffset) ?: result
        val variableTypes = javaLocalVariableTypes(scope, genericMethodReturnTypes)
        val typeParameterBounds = javaTypeParameterBounds(scope)
        if (trimmed in attributeModifierStreamLambdaParameters(scope)) return true
        val directType = variableTypes[trimmed]
        if (directType == "AttributeModifier" || "AttributeModifier" in typeParameterBounds[directType].orEmpty()) {
            return true
        }
        val methodCall = Regex("""(?s)^(?:(.+)\.)?([A-Za-z_$][\w$]*)\s*\(.*\)$""").matchEntire(trimmed)
            ?: return false
        val receiver = methodCall.groupValues[1].trim()
        val methodName = methodCall.groupValues[2]
        if (receiver.isBlank()) {
            return methodName in ownerAttributeModifierMethods
        }
        if (receiver == "this") {
            return methodName in ownerAttributeModifierMethods
        }
        val receiverType = variableTypes[receiver]
        if (receiverType != null && methodName in methodsForType(receiverType, typeParameterBounds)) {
            return true
        }
        return methodName in attributeModifierMethods[receiver.substringAfterLast('.')].orEmpty()
    }

    for (variable in modifierVariables) {
        val escaped = Regex.escape(variable)
        result = Regex("""\.(getModifier|hasModifier|removeModifier)\(\s*$escaped\s*\)""")
            .replace(result) { match -> ".${match.groupValues[1]}($variable.id())" }
        result = Regex("""\b$escaped\.getId\(\)""").replace(result, "$variable.id()")
        result = Regex("""\b$escaped\.getAmount\(\)""").replace(result, "$variable.amount()")
        result = Regex("""\b$escaped\.getOperation\(\)""").replace(result, "$variable.operation()")
    }
    listOf(
        "getId" to "id",
        "getAmount" to "amount",
        "getOperation" to "operation"
    ).forEach { (legacyName, recordName) ->
        result = rewriteJavaCallWithOffset(result, legacyName) { receiver, args, callOffset ->
            if (args.isNotEmpty()) return@rewriteJavaCallWithOffset null
            if (!isAttributeModifierExpression(receiver, callOffset)) return@rewriteJavaCallWithOffset null
            "$receiver.$recordName()"
        }
    }
    listOf("getModifier", "hasModifier", "removeModifier").forEach { methodName ->
        result = rewriteJavaCallWithOffset(result, methodName) { receiver, args, callOffset ->
            if (args.size != 1) return@rewriteJavaCallWithOffset null
            val idExpression = attributeModifierIdExpression(args[0], callOffset) ?: return@rewriteJavaCallWithOffset null
            "$receiver.$methodName($idExpression)"
        }
    }
    result = migrateAttributeModifierIdMethodParameterTypes(result, attributeModifierIdMethodArguments)

    val withoutUuidImport = removeImport(result, "java.util.UUID")
    if (!Regex("""\bUUID\b""").containsMatchIn(withoutUuidImport)) {
        result = withoutUuidImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateAttributeModifierIdMethodParameterTypes(
    source: String,
    attributeModifierIdMethodArguments: Set<AttributeModifierIdMethodArgument>
): String {
    val ownerName = javaTopLevelTypeName(source) ?: return source
    val ownerHints = attributeModifierIdMethodArguments
        .filter { it.owner == ownerName }
        .groupBy { it.methodName }
        .mapValues { entry -> entry.value.map { it.parameterIndex }.toSet() }
    if (ownerHints.isEmpty() || !source.contains("UUID")) return source

    val id = """[A-Za-z_$][\w$]*"""
    val migratedParameterNames = linkedSetOf<String>()
    var changed = false
    var result = Regex(
        """(?m)^([ \t]*(?:(?:public|protected|private|static|final|default|abstract|synchronized)\s+)+[\w<>\[\].?,\s]+\s+($id)\s*\()([^;{}\r\n]*)\)"""
    ).replace(source) { match ->
        val methodName = match.groupValues[2]
        val migratedIndexes = ownerHints[methodName] ?: return@replace match.value
        val params = splitTopLevelJavaArgs(match.groupValues[3])
        if (params.isEmpty()) return@replace match.value
        var methodChanged = false
        val migratedParams = params.mapIndexed { index, param ->
            if (index !in migratedIndexes) return@mapIndexed param
            val migrated = Regex(
                """((?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s+)*)((?:java\.util\.)?UUID)(\s+($id)\b)"""
            ).replace(param) { paramMatch ->
                methodChanged = true
                migratedParameterNames += paramMatch.groupValues[4]
                "${paramMatch.groupValues[1]}ResourceLocation${paramMatch.groupValues[3]}"
            }
            migrated
        }
        if (!methodChanged) {
            match.value
        } else {
            changed = true
            "${match.groupValues[1]}${migratedParams.joinToString(", ")})"
        }
    }
    if (!changed) return source

    val idMaps = linkedSetOf<String>()
    for (paramName in migratedParameterNames) {
        val escaped = Regex.escape(paramName)
        Regex("""\b($id)\s*\.\s*(?:put|get|containsKey|remove)\(\s*$escaped\b""")
            .findAll(result)
            .forEach { idMaps += it.groupValues[1] }
    }
    for (mapName in idMaps) {
        result = Regex(
            """\b((?:java\.util\.)?(?:Map|HashMap|LinkedHashMap|ConcurrentHashMap|ConcurrentMap)\s*<\s*)(?:java\.util\.)?UUID(\s*,[^;=\r\n>]*(?:>[^;=\r\n>]*)?\s+${Regex.escape(mapName)}\b)"""
        ).replace(result) { match ->
            "${match.groupValues[1]}ResourceLocation${match.groupValues[2]}"
        }
    }
    return addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
}

internal fun StructuralRefactorPass.declaredAttributeModifierMethodNames(source: String): Set<String> {
    val id = """[A-Za-z_$][\w$]*"""
    return Regex(
        """(?m)\b(?:(?:public|protected|private|static|final|default|abstract|synchronized)\s+)*(?:net\.minecraft\.world\.entity\.ai\.attributes\.)?AttributeModifier\s+($id)\s*\("""
    ).findAll(source).map { it.groupValues[1] }.toSet()
}

internal fun StructuralRefactorPass.javaLocalVariableTypes(
    source: String,
    genericMethodReturnTypes: Map<String, String> = emptyMap()
): Map<String, String> {
    val id = """[A-Za-z_$][\w$]*"""
    val types = linkedMapOf<String, String>()
    val typeExpression = """(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(?:\s*<[^;=(){}]*>)?(?:\s*\[\])?"""
    fun simpleTypeName(type: String): String =
        type.replace(Regex("""<[^<>]*>"""), "")
            .trim()
            .removeSuffix("[]")
            .substringAfterLast('.')

    Regex("""\b($typeExpression)\s+($id)\b(?=\s*(?:[,;)=]))""")
        .findAll(source)
        .forEach { match ->
            val type = simpleTypeName(match.groupValues[1])
            val name = match.groupValues[2]
            if (type !in setOf("void", "return", "new", "if", "for", "while", "switch")) {
                types[name] = type
            }
        }
    Regex("""\binstanceof\s+($typeExpression)\s+($id)\b""")
        .findAll(source)
        .forEach { match ->
            types[match.groupValues[2]] = simpleTypeName(match.groupValues[1])
        }
    Regex("""\bfor\s*\(\s*($typeExpression)\s+($id)\s*:""")
        .findAll(source)
        .forEach { match ->
            types[match.groupValues[2]] = simpleTypeName(match.groupValues[1])
        }
    Regex("""\b((?:$id\.)*$id)\s*\.\s*($id)\s*\([^;{}]*?\)\s*\.\s*ifPresent\s*\(\s*($id)\s*->""")
        .findAll(source)
        .forEach { match ->
            val owner = match.groupValues[1].substringAfterLast('.')
            val method = match.groupValues[2]
            val lambdaParameter = match.groupValues[3]
            genericMethodReturnTypes["$owner.$method"]?.let { returnedType ->
                types[lambdaParameter] = returnedType
            }
        }
    return types
}

internal fun StructuralRefactorPass.javaTypeParameterBounds(source: String): Map<String, Set<String>> {
    val id = """[A-Za-z_$][\w$]*"""
    val bounds = linkedMapOf<String, MutableSet<String>>()
    fun simpleTypeName(type: String): String =
        type.replace(Regex("""<[^<>]*>"""), "")
            .trim()
            .substringAfterLast('.')

    Regex("""<([^<>]*\bextends\b[^<>]*)>""")
        .findAll(source)
        .forEach { typeParameters ->
            typeParameters.groupValues[1].split(',').forEach { parameter ->
                val match = Regex("""^\s*($id)\s+extends\s+(.+?)\s*$""").find(parameter) ?: return@forEach
                match.groupValues[2].split('&')
                    .map { simpleTypeName(it) }
                    .filter { it.isNotBlank() }
                    .forEach { bound ->
                        bounds.getOrPut(match.groupValues[1]) { linkedSetOf() } += bound
                    }
            }
        }
    return bounds
}

internal fun StructuralRefactorPass.isAttributeModifierIdUse(
    source: String,
    idName: String,
    crossFileAttributeModifierIds: Set<String> = emptySet(),
    ownerName: String? = null
): Boolean {
    if (ownerName != null && "$ownerName.$idName" in crossFileAttributeModifierIds) return true
    fun directUse(name: String): Boolean {
        val escaped = Regex.escape(name)
        return Regex("""new\s+AttributeModifier\s*\(\s*$escaped\b""").containsMatchIn(source) ||
            Regex("""\.(?:getModifier|hasModifier|removeModifier)\(\s*$escaped\s*\)""").containsMatchIn(source) ||
            Regex("""\.(?:getModifier|hasModifier|removeModifier)\(\s*[^,()]+,\s*$escaped\s*\)""").containsMatchIn(source) ||
            Regex("""\.addAttributeModifier\([^;\n]*\b$escaped\b""").containsMatchIn(source)
    }
    if (directUse(idName)) return true
    val escaped = Regex.escape(idName)
    return Regex("""\bUUID\s+([A-Za-z_$][\w$]*)\s*=\s*$escaped\s*;""")
        .findAll(source)
        .map { it.groupValues[1] }
        .any { directUse(it) }
}

internal fun StructuralRefactorPass.modifierConstantPathName(name: String): String {
    val normalized = name.removeSuffix("_UUID").removeSuffix("_ID")
    val parts = normalized.split('_').filter { it.isNotBlank() }
    val modifierIndex = parts.indexOfLast { it == "MODIFIER" }
    if (modifierIndex > 0) {
        val semantic = parts.getOrNull(modifierIndex - 1)
        if (semantic != null && semantic !in setOf("ATTRIBUTE", "ENTITY", "ITEM")) {
            return modifierPathName("${semantic}_MODIFIER")
        }
    }
    return modifierPathName(normalized.removeSuffix("_MODIFIER"))
}

internal fun StructuralRefactorPass.migrateEntityRidingOffsetExpressions(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    var result = source
    result = Regex(
        """new\s+Vec3\s*\(\s*([A-Za-z_$][\w$]*)\.getX\(\)\s*,\s*\1\.getY\(\)\s*-\s*\1\.getMyRidingOffset\(\)\s*\+\s*\1\.getBbHeight\(\)\s*/\s*2\s*,\s*\1\.getZ\(\)\s*\)"""
    ).replace(result) { match -> "${match.groupValues[1]}.getBoundingBox().getCenter()" }
    result = Regex(
        """\b([A-Za-z_$][\w$]*)\.getY\(\)\s*-\s*\1\.getMyRidingOffset\(\)\s*\+\s*\1\.getBbHeight\(\)\s*/\s*2"""
    ).replace(result) { match -> "${match.groupValues[1]}.getBoundingBox().getCenter().y" }
    if (result.contains(".getMyRidingOffset()") && sourceDeclaresEntityDerivedType(result, javaInheritanceIndex)) {
        result = rewriteJavaCallWithOffset(result, "getMyRidingOffset") { receiver, args, callOffset ->
            if (args.isNotEmpty()) return@rewriteJavaCallWithOffset null
            val normalizedReceiver = receiver.trim()
            if (normalizedReceiver == "this" || normalizedReceiver == "super") return@rewriteJavaCallWithOffset null
            val methodText = enclosingMethodText(result, callOffset) ?: return@rewriteJavaCallWithOffset null
            val methodHeader = methodText.substringBefore("{")
            if (Regex("""\bstatic\b""").containsMatchIn(methodHeader)) return@rewriteJavaCallWithOffset null
            "(-$normalizedReceiver.getVehicleAttachmentPoint(this).y)"
        }
        result = Regex("""\+\s*\(-([^;\r\n]+?\.getVehicleAttachmentPoint\(this\)\.y)\)""")
            .replace(result, "- $1")
        result = Regex("""-\s*\(-([^;\r\n]+?\.getVehicleAttachmentPoint\(this\)\.y)\)""")
            .replace(result, "+ $1")
    }
    return result
}

internal fun StructuralRefactorPass.sourceDeclaresEntityDerivedType(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): Boolean {
    val entityBaseTypes = javaEntityBaseTypes()
    return Regex(
        """\bclass\s+([A-Za-z_$][\w$]*)(?:\s*<[^>{}]+>)?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\b"""
    ).findAll(source).any { match ->
        javaInheritanceIndex.inherits(match.groupValues[2], entityBaseTypes)
    }
}

internal fun StructuralRefactorPass.migrateLegacyPassengerRidingOffsetCallSites(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getPassengersRidingOffset()") ||
        !sourceDeclaresEntityDerivedType(source, javaInheritanceIndex)) {
        return source
    }
    val id = """[A-Za-z_$][\w$]*"""
    var result = replaceExecutableRegex(
        source,
        Regex("""this\.getY\(\)\s*\+\s*this\.getPassengersRidingOffset\(\)\s*-\s*($id)\.getVehicleAttachmentPoint\(this\)\.y""")
    ) { match ->
        val passenger = match.groupValues[1]
        "this.getPassengerRidingPosition($passenger).y - $passenger.getVehicleAttachmentPoint(this).y"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\(\s*this\.isRemoved\(\)\s*\?\s*\(double\)\s*0\.01F\s*:\s*this\.getPassengersRidingOffset\(\)\s*\)\s*-\s*($id)\.getVehicleAttachmentPoint\(this\)\.y""")
    ) { match ->
        val passenger = match.groupValues[1]
        "(this.isRemoved() ? (double) 0.01F : this.getPassengerRidingPosition($passenger).y - this.getY()) - $passenger.getVehicleAttachmentPoint(this).y"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\(\s*this\.isRemoved\(\)\s*\?\s*\(double\)\s*0\.01F\s*:\s*this\.getPassengersRidingOffset\(\)\s*\)\s*-\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.getVehicleAttachmentPoint\(this\)\.y""")
    ) { match ->
        val passenger = match.groupValues[1]
        "(this.isRemoved() ? (double) 0.01F : this.getPassengerRidingPosition($passenger).y - this.getY()) - $passenger.getVehicleAttachmentPoint(this).y"
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPassengerAttachmentOverrides(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getPassengersRidingOffset()") ||
        executableCode.contains("getPassengerAttachmentPoint(")) {
        return source
    }
    val methods = javaMethodRanges(executableCode)
    val offsetMethod = methods.singleOrNull { method ->
        method.name == "getPassengersRidingOffset" &&
            Regex("""\bdouble\s+getPassengersRidingOffset\s*\(\s*\)""").containsMatchIn(method.header)
    } ?: return source
    val offsetMethodText = source.substring(offsetMethod.range)
    val executableOffsetMethodText = executableCode.substring(offsetMethod.range)
    val returnExpr = Regex("""return\s+(.+?);""", RegexOption.DOT_MATCHES_ALL)
        .find(executableOffsetMethodText)
        ?.groups
        ?.get(1)
        ?.range
        ?.let { offsetMethodText.substring(it.first, it.last + 1).trim() }
        ?: return source
    val yExpression = returnExpr.replace("super.getPassengersRidingOffset()", "dimensions.height()")
		val riderPositionMethods = methods.filter { it.name == "getRiderPosition" }
		if (riderPositionMethods.size > 1) {
			return source
		}
		val riderPositionMethod = riderPositionMethods.singleOrNull()
    val distanceExpression = riderPositionMethod
        ?.let { method ->
            Regex("""float\s+distance\s*=\s*([0-9]+(?:\.[0-9]+)?[Ff]?)\s*;""")
                .find(executableCode.substring(method.range))
                ?.groups
                ?.get(1)
                ?.range
                ?.let { range ->
                    val absoluteRange = (method.range.first + range.first)..(method.range.first + range.last)
                    source.substring(absoluteRange.first, absoluteRange.last + 1)
                }
        }
        ?.let { if (it.endsWith("F", ignoreCase = true)) it.uppercase() else "${it}F" }
        ?: "0.0F"
    val replacement = """
protected Vec3 getPassengerAttachmentPoint(Entity entity, EntityDimensions dimensions, float yRot) {
		return new Vec3(0.0F, $yExpression, $distanceExpression);
	}
        """.trimIndent()
    val edits = mutableListOf(offsetMethod.range to replacement)
    methods
        .filter { it.name == "positionRider" }
        .filter { executableCode.substring(it.range).contains("getRiderPosition(") }
        .forEach { edits += javaMethodRangeWithTrailingLineBreak(source, it.range) to "" }
    if (edits.any { it.first != offsetMethod.range } && riderPositionMethod != null) {
        edits += javaMethodRangeWithTrailingLineBreak(source, riderPositionMethod.range) to ""
    }
    var result = applyStringEdits(source, edits)
    result = addImportIfMissing(result, "net.minecraft.world.entity.EntityDimensions")
    result = addImportIfMissing(result, "net.minecraft.world.phys.Vec3")
    return result
}

internal fun StructuralRefactorPass.javaMethodRangeWithTrailingLineBreak(source: String, range: IntRange): IntRange {
    var end = range.last + 1
    if (end < source.length && source[end] == '\r') end++
    if (end < source.length && source[end] == '\n') end++
    return range.first until end
}

internal fun StructuralRefactorPass.migrateLegacyEntityOverrideSignatures(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    var result = source
    var needsLevel = false
    var needsEntity = false
    var needsVec3 = false

    result = migrateLegacyEyeHeightOverrides(result, javaInheritanceIndex)
    result = removeLegacyEntityRemoveCapabilityInvalidation(result, javaInheritanceIndex)
    result = migrateLegacyCanBreatheUnderwaterOverrides(result, javaInheritanceIndex)

    if (result.contains("canChangeDimensions()")) {
        result = Regex("""\b(public|protected)\s+boolean\s+canChangeDimensions\s*\(\s*\)""")
            .replace(result) { match ->
                needsLevel = true
                "${match.groupValues[1]} boolean canChangeDimensions(Level from, Level to)"
            }
    }

    if (result.contains("canBeLeashed(")) {
        result = Regex("""\b(public|protected)\s+boolean\s+canBeLeashed\s*\(\s*Player\s+[A-Za-z_$][\w$]*\s*\)""")
            .replace(result) { match -> "${match.groupValues[1]} boolean canBeLeashed()" }
    }

    if (result.contains("getRelativePortalPosition(") &&
        collectJavaClassDeclarations(result).any { javaClassExtendsAny(it, javaEntityBaseTypes(), javaInheritanceIndex) }
    ) {
        result = Regex(
            """(?m)^([ \t]*)protected(\s+Vec3\s+getRelativePortalPosition\s*\(\s*Direction\.Axis\s+[A-Za-z_$][\w$]*\s*,\s*BlockUtil\.FoundRectangle\s+[A-Za-z_$][\w$]*\s*\))"""
        ).replace(result) { match -> "${match.groupValues[1]}public${match.groupValues[2]}" }
    }

    if (result.contains("getPersistedStatus()") &&
        Regex("""\bStatus\s+getStatus\s*\(""").containsMatchIn(maskJavaCommentsAndLiterals(result))
    ) {
        result = replaceExecutableRegex(result, Regex("""\bgetPersistedStatus\s*\(\s*\)""")) { "getStatus()" }
    }

    if (result.contains("getMyRidingOffset()") && !result.contains("getVehicleAttachmentPoint(")) {
        val offsetMethod = javaMethodText(result, "getMyRidingOffset")
        if (offsetMethod != null) {
            val returnExpr = Regex("""return\s+(.+?);""", RegexOption.DOT_MATCHES_ALL)
                .find(offsetMethod)
                ?.groupValues
                ?.get(1)
                ?.trim()
            if (returnExpr != null) {
                val replacement = """
public Vec3 getVehicleAttachmentPoint(Entity vehicle) {
		return new Vec3(0.0D, $returnExpr, 0.0D);
	}
                    """.trimIndent()
                result = result.replace(offsetMethod, replacement)
                needsEntity = true
                needsVec3 = true
            }
        }
    }

    if (result.contains("invalidateCaps()")) {
        result = result.replace(".invalidateCaps()", ".invalidateCapabilities()")
    }

    if (result.contains("lerpTo(")) {
        result = Regex(
            """public\s+void\s+lerpTo\(\s*double\s+([A-Za-z_$][\w$]*)\s*,\s*double\s+([A-Za-z_$][\w$]*)\s*,\s*double\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*int\s+([A-Za-z_$][\w$]*)\s*,\s*boolean\s+[A-Za-z_$][\w$]*\s*\)"""
        ).replace(result) { match ->
            "public void lerpTo(double ${match.groupValues[1]}, double ${match.groupValues[2]}, double ${match.groupValues[3]}, float ${match.groupValues[4]}, float ${match.groupValues[5]}, int ${match.groupValues[6]})"
        }
        // Entity.lerpTo(DDDFFIZ)V became (DDDFFI)V (1.20.1 SRG table vs 1.21.1 class table): calls
        // drop the boolean, unless the file still declares a seven-parameter lerpTo of its own
        val declaration = Regex("""^\s*(?:final\s+)?[\w$.<>\[\]]+\s+[A-Za-z_$][\w$]*\s*$""")
        val ownSevenParameterLerp = Regex("""\blerpTo\s*\(([^()]*)\)\s*(?:throws\s+[^{;]+)?\{""").findAll(maskJavaCommentsAndLiterals(result))
            .any { splitTopLevelJavaArgs(it.groupValues[1]).size == 7 }
        if (!ownSevenParameterLerp) {
            result = rewriteJavaInvocationArguments(result, "lerpTo") { args ->
                args.take(6).takeIf { args.size == 7 && args.none { declaration.matches(it) } }
            }
        }
    }

    result = Regex(
        """(?s)\s*@Override\s*\r?\n\s*protected\s+void\s+enchantSpawned(?:Weapon|Armor)\s*\([^)]*\)\s*\{\s*\}\s*"""
    ).replace(result, "\n")

    if (needsLevel) result = addImportIfMissing(result, "net.minecraft.world.level.Level")
    if (needsEntity) result = addImportIfMissing(result, "net.minecraft.world.entity.Entity")
    if (needsVec3) result = addImportIfMissing(result, "net.minecraft.world.phys.Vec3")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCanBreatheUnderwaterOverrides(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("canBreatheUnderwater(")) return source
    val executableSource = maskJavaCommentsAndLiterals(source)
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRanges(source).filter { it.name == "canBreatheUnderwater" }) {
        val executableMethodText = executableSource.substring(method.range)
        if (!Regex("""\bboolean\s+canBreatheUnderwater\s*\(\s*\)""").containsMatchIn(executableMethodText)) {
            continue
        }
        val classDeclaration = enclosingJavaClassDeclaration(source, method.range.first)
        if (!javaClassExtendsAny(classDeclaration, javaLivingEntityBaseTypes(), javaInheritanceIndex)) {
            continue
        }
        val classBody = classDeclaration?.let {
            source.substring(it.bodyRange.first + 1, it.bodyRange.last)
        }.orEmpty()
        if (Regex("""\bcanDrownInFluidType\s*\(""").containsMatchIn(classBody)) {
            edits += method.range to ""
            continue
        }
        val openBrace = source.indexOf('{', method.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) continue
        val body = source.substring(openBrace + 1, closeBrace)
        val executableBody = executableSource.substring(openBrace + 1, closeBrace)
        val returnMatches = Regex("""\breturn\s+(.+?);""", RegexOption.DOT_MATCHES_ALL)
            .findAll(executableBody)
            .toList()
        if (returnMatches.size != 1) continue
        val returnRange = returnMatches.single().groups[1]?.range ?: continue
        val legacyBreathExpression = body.substring(returnRange).trim()
        val indent = source.lineSequence()
            .runningFold(0) { offset, line -> offset + line.length + 1 }
            .zip(source.lineSequence())
            .firstOrNull { (offset, line) -> offset <= method.range.first && method.range.first <= offset + line.length && line.contains("canBreatheUnderwater") }
            ?.second
            ?.takeWhile { it == ' ' || it == '\t' }
            ?: method.header.takeWhile { it == ' ' || it == '\t' }
        val nonOverrideAnnotations = Regex("""(?m)^[ \t]*@(?!Override\b)[^\r\n]*\r?\n""")
            .findAll(method.header.substringBefore("boolean canBreatheUnderwater"))
            .joinToString("") { it.value }
        val waterDrownExpression = invertBooleanExpression(legacyBreathExpression)
        val replacement = buildString {
            append(nonOverrideAnnotations)
            append(indent).append("@Override\n")
            append(indent).append("public boolean canDrownInFluidType(FluidType type) {\n")
            append(indent).append("    if (type == net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value()) {\n")
            append(indent).append("        return $waterDrownExpression;\n")
            append(indent).append("    }\n")
            append(indent).append("    return super.canDrownInFluidType(type);\n")
            append(indent).append("}")
        }
        edits += method.range to replacement
    }
    if (edits.isEmpty()) return source
    return addImportIfMissing(applyStringEdits(source, edits), "net.neoforged.neoforge.fluids.FluidType")
}

internal fun StructuralRefactorPass.invertBooleanExpression(expression: String): String {
    val trimmed = expression.trim()
    return when {
        trimmed == "true" -> "false"
        trimmed == "false" -> "true"
        trimmed.startsWith("!") && trimmed.drop(1).trim().matches(Regex("""[A-Za-z_$][\w$.]*(?:\(\))?""")) -> trimmed.drop(1).trim()
        else -> "!($trimmed)"
    }
}

internal fun StructuralRefactorPass.removeLegacyEntityRemoveCapabilityInvalidation(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("remove(") ||
        (!source.contains("invalidateCaps()") && !source.contains("invalidateCapabilities()"))) {
        return source
    }

    var result = source
    var cursor = 0
    val removePattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:public|protected)\s+void\s+remove\s*\(\s*(?:Entity\.)?RemovalReason\s+[A-Za-z_$][\w$]*\s*\)\s*\{"""
    )
    while (true) {
        val match = removePattern.find(result, cursor) ?: break
        val classDeclaration = enclosingJavaClassDeclaration(result, match.range.first)
        if (!javaClassExtendsAny(classDeclaration, javaEntityBaseTypes(), javaInheritanceIndex)) {
            cursor = match.range.last + 1
            continue
        }
        val openBrace = result.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val rewrittenBody = Regex(
            """(?m)^[ \t]*(?:this|super)\.invalidate(?:Caps|Capabilities)\(\);\s*\r?\n?"""
        ).replace(body, "")
        if (rewrittenBody == body) {
            cursor = closeBrace + 1
            continue
        }
        result = result.substring(0, openBrace + 1) + rewrittenBody + result.substring(closeBrace)
        cursor = openBrace + 1 + rewrittenBody.length
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyParticleCullingOverrides(source: String): String {
    if (!source.contains("shouldCull()")) return source
    return Regex("""(?m)^([ \t]*)@Override\s*\r?\n([ \t]*public\s+boolean\s+shouldCull\s*\(\s*\))""")
        .replace(source, "$1$2")
}

internal fun StructuralRefactorPass.migrateMixinLegacyCapabilityProviderSuperclass(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("@Mixin") || !executableCode.contains("CapabilityProvider")) return source
    val classMatch = Regex(
        """(?m)^([ \t]*(?:(?:public|protected|private|abstract|final)\s+)*class\s+([A-Za-z_$][\w$]*)(?:\s*<[^>{}]*>)?\s+)extends\s+(?:(?:net\.(?:minecraftforge|neoforged)\.[\w.]+|com\.modporter\.generated\.[\w.]+)\.)?CapabilityProvider\s*<[^>{}]+>\s*"""
    ).find(executableCode) ?: return source
    val className = classMatch.groupValues[2]
    val openBrace = executableCode.indexOf('{', classMatch.range.last)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
    if (openBrace < 0 || closeBrace < 0) return source
    val constructorPattern = Regex(
        """(?s)\n[ \t]*protected\s+${Regex.escape(className)}\s*\(\s*Class\s*<[^>]+>\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\s*\(\s*\1\s*\)\s*;\s*\}\s*"""
    )
    val constructor = constructorPattern.find(source, openBrace) ?: return source
    if (constructor.range.first > closeBrace) return source
    val bodyWithoutConstructor = source.substring(openBrace + 1, constructor.range.first) +
        source.substring(constructor.range.last + 1, closeBrace)
    if (Regex("""\bsuper\s*\.""").containsMatchIn(maskJavaCommentsAndLiterals(bodyWithoutConstructor))) return source

    var result = applyStringEdits(
        source,
        listOf(
            classMatch.range to "${classMatch.groupValues[1].trimEnd()} ",
            constructor.range to "\n"
        )
    )
    result = removeImport(result, "net.minecraftforge.common.capabilities.CapabilityProvider")
    result = removeImport(result, "net.neoforged.neoforge.capabilities.CapabilityProvider")
    return result
}

internal data class HolderLookupNbtMethod(
    val owner: String,
    val methodName: String,
    val parameterCount: Int? = null,
    val providerParameterIndex: Int? = null,
    val legacyParameterTypes: List<String>? = null,
    val requiresTypedCallResolution: Boolean = false
) {
    constructor(
        owner: String,
        methodName: String,
        parameterCount: Int?,
        firstParameterType: String?
    ) : this(
        owner = owner,
        methodName = methodName,
        parameterCount = parameterCount,
        providerParameterIndex = null,
        legacyParameterTypes = firstParameterType?.let(::listOf),
        requiresTypedCallResolution = false
    )

    /** Compatibility view used by the pre-existing source-backed overload filters. */
    val firstParameterType: String?
        get() = legacyParameterTypes?.firstOrNull()?.substringAfterLast('.')
}

internal fun StructuralRefactorPass.resolveHolderLookupNbtMethodContract(
    methods: Collection<HolderLookupNbtMethod>,
    owner: String?,
    methodName: String,
    legacyArgumentCount: Int
): HolderLookupNbtMethod? {
    if (owner == null) return null
    val matches = methods.filter { method ->
        method.owner == owner &&
            method.methodName == methodName &&
            (method.parameterCount == null || method.parameterCount == legacyArgumentCount)
    }
    if (matches.isEmpty()) return null
    if (matches.any { it.requiresTypedCallResolution }) return null
    val providerIndices = matches.map { method ->
        method.providerParameterIndex ?: legacyArgumentCount
    }.distinct()
    if (providerIndices.size != 1) {
        throw IllegalStateException(
            "Conflicting HolderLookup.Provider parameter positions for " +
                "$owner.$methodName/$legacyArgumentCount: $providerIndices"
        )
    }
    val providerIndex = providerIndices.single()
    if (providerIndex !in 0..legacyArgumentCount) {
        throw IllegalStateException(
            "Invalid HolderLookup.Provider parameter position $providerIndex for " +
                "$owner.$methodName/$legacyArgumentCount"
        )
    }
    return matches.first()
}

internal fun StructuralRefactorPass.resolveHolderLookupNbtDeclarationContract(
    methods: Collection<HolderLookupNbtMethod>,
    owner: String?,
    methodName: String,
    legacyParameters: List<String>
): HolderLookupNbtMethod? {
    if (owner == null) return null
    val declaredTypes = legacyParameters.map { parameter ->
        parseJavaParameter(parameter)?.first?.let(::normalizeJavaTypeForContract) ?: return null
    }
    val matches = methods.filter { method ->
        method.owner == owner &&
            method.methodName == methodName &&
            method.parameterCount == legacyParameters.size &&
            method.legacyParameterTypes?.map(::normalizeJavaTypeForContract) == declaredTypes
    }
    if (matches.size > 1) {
        throw IllegalStateException(
            "Ambiguous typed HolderLookup.Provider declaration contract for " +
                "$owner.$methodName$declaredTypes"
        )
    }
    return matches.singleOrNull()
}

internal fun normalizeJavaTypeForContract(type: String): String =
    type.replace(Regex("""@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*"""), "")
        .replace(Regex("""\bfinal\s+"""), "")
        .replace(Regex("""\s+"""), "")
        .replace(Regex("""(?:[a-z_$][\w$]*\.)+([A-Z_$][\w$]*)"""), "${'$'}1")

internal fun StructuralRefactorPass.insertHolderLookupProviderArgument(
    arguments: List<String>,
    providerExpression: String,
    method: HolderLookupNbtMethod
): List<String> {
    val providerIndex = method.providerParameterIndex ?: arguments.size
    if (providerIndex !in 0..arguments.size) {
        throw IllegalStateException(
            "Invalid HolderLookup.Provider parameter position $providerIndex for " +
                "${method.owner}.${method.methodName}/${arguments.size}"
        )
    }
    return arguments.map { it.trim() }.toMutableList().also {
        it.add(providerIndex, providerExpression.trim())
    }
}

/** True when [argument] reads like a CompoundTag: the tag-typed overload is meant. */
internal fun StructuralRefactorPass.looksLikeTagArgument(argument: String): Boolean {
    val trimmed = argument.trim()
    return Regex("""^new\s+CompoundTag\s*\(\s*\)$""").matches(trimmed) ||
        Regex("""\.(?:getCompound|getOrCreateTag|getTag|getOrCreateTagElement|getTagElement|copyTag|saveWithoutId|saveWithoutMetadata)\s*\(""").containsMatchIn(trimmed) ||
        Regex("""^(?:this\.)?[A-Za-z_$][\w$]*$""").matches(trimmed) &&
        Regex("""(?i)(?:^|[^a-z])(?:tag|nbt|compound)""").containsMatchIn(trimmed)
}

/** False only when [target] is a disambiguated overload and the first argument does not read like its type. */
internal fun StructuralRefactorPass.argumentsFitOverload(
    target: HolderLookupNbtMethod,
    args: List<String>,
    // the declared type of an argument expression where the call stands, when the source proves one
    argumentType: (String) -> String? = { null }
): Boolean {
    val type = target.firstParameterType ?: return true
    if (args.isEmpty()) return false
    argumentType(args[0].trim())?.substringAfterLast('.')?.let { return it == type }
    if ((target.owner to target.methodName) !in overloadedNbtMethods) return true
    val tagLike = looksLikeTagArgument(args[0])
    if (type == "CompoundTag") return tagLike
    if (tagLike) return false
    // with several overloads taking other values, only an argument declared as this overload's type fits
    val valueTypes = overloadedNbtFirstParameterTypes[target.owner to target.methodName].orEmpty() - "CompoundTag"
    return valueTypes.size <= 1 || argumentType(args[0].trim()) == type
}

/** (owner, name) pairs declared more than once with the same arity but different parameter types. */
internal var overloadedNbtMethods: Set<Pair<String, String>> = emptySet()

/** The first parameter types of each overloaded (owner, name) pair. */
internal var overloadedNbtFirstParameterTypes: Map<Pair<String, String>, Set<String>> = emptyMap()

/** The declared type of an argument: `new T(...)`, a cast `(T) x`, or a name declared in scope at [at]. */
internal fun StructuralRefactorPass.argumentDeclaredType(executableCode: String, argument: String, at: Int): String? {
    Regex("""^new\s+(?:[\w$]+\.)*([A-Z][\w$]*)\s*[(<]""").find(argument)?.let { return it.groupValues[1] }
    Regex("""^\(\s*(?:[\w$]+\.)*([A-Z][\w$]*)\s*\)\s*[\w$(]""").find(argument)?.let { return it.groupValues[1] }
    val name = Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)$""").find(argument)?.groupValues?.get(1) ?: return null
    return com.modporter.core.transforms.util.JavaScopedDeclarations.visibleAt(executableCode, name, at)?.type
}

internal data class HolderLookupNbtConstructor(
    val owner: String,
    val parameterCount: Int? = null
)

internal fun StructuralRefactorPass.javaMethodParameterCount(methodHeader: String): Int {
    val (openParen, closeParen) = callableParameterSpan(methodHeader, methodHeader.indices) ?: return 0
    val parameters = methodHeader.substring(openParen + 1, closeParen).trim()
    return if (parameters.isBlank()) 0 else splitTopLevelJavaArgs(parameters).size
}

internal fun StructuralRefactorPass.collectProviderAwareNbtMethodContracts(
    javaFiles: List<Path>,
    projectSerializableTypeFqns: Set<String>,
    javaInheritanceIndex: JavaInheritanceIndex
): Set<HolderLookupNbtMethod> {
    val providerAwareTypeFqns = projectSerializableTypeFqns.toMutableSet()
    javaFiles.forEach { javaFile ->
        val source = runCatching { javaFile.readText() }.getOrDefault("")
        val packageName = packageNameOf(source)
        val declarations = collectJavaClassDeclarations(source).associateBy { it.name }
        javaTypeContracts(source).forEach { type ->
            val declaration = declarations[type.simpleName] ?: return@forEach
            if (javaClassExtendsAny(declaration, setOf("ItemStackHandler"), javaInheritanceIndex)) {
                providerAwareTypeFqns += if (packageName.isBlank()) {
                    type.simpleName
                } else {
                    "$packageName.${type.simpleName}"
                }
            }
        }
    }
    val simpleNameCollisions = providerAwareTypeFqns
        .groupBy { it.substringAfterLast('.') }
        .filterValues { fqns -> fqns.distinct().size > 1 }
    if (simpleNameCollisions.isNotEmpty()) {
        throw IllegalStateException(
            "Provider-aware NBT type names are ambiguous across packages: $simpleNameCollisions"
        )
    }
    return providerAwareTypeFqns.flatMapTo(linkedSetOf()) { fqn ->
        val owner = fqn.substringAfterLast('.')
        listOf(
            HolderLookupNbtMethod(owner, "serializeNBT", 0, 0),
            HolderLookupNbtMethod(owner, "deserializeNBT", 1, 0)
        )
    }
}

internal fun StructuralRefactorPass.collectSavedDataHolderLookupMethods(
    javaFiles: List<Path>,
    savedDataClassNames: Set<String>
): Set<HolderLookupNbtMethod> {
    if (savedDataClassNames.isEmpty()) return emptySet()
    val result = linkedSetOf<HolderLookupNbtMethod>()
    for (javaFile in javaFiles) {
        val source = runCatching { javaFile.readText() }.getOrDefault("")
        if (!source.contains("SavedData") || !source.contains("CompoundTag")) continue
        val executableCode = maskJavaCommentsAndLiterals(source)
        val typeBlocks = javaTypeBlocks(source, executableCode)
        for (type in typeBlocks) {
            if (type.name !in savedDataClassNames) continue
            javaMethodRanges(source).forEach { method ->
                if (method.range.first !in type.bodyStart..type.end) return@forEach
                if (method.name != "load" && method.name != "save") return@forEach
                if (!method.header.contains("CompoundTag") || method.header.contains("HolderLookup.Provider")) return@forEach
                result += HolderLookupNbtMethod(type.name, method.name, javaMethodParameterCount(method.header))
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.collectLegacyCompatibleItemStackNbtMethodsNeedingHolderLookup(
    javaFiles: List<Path>,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY,
    // methods another rule gives a HolderLookup.Provider (SavedData load/save): their bodies are provider-aware call sites
    providerSources: Set<HolderLookupNbtMethod> = emptySet()
): Set<HolderLookupNbtMethod> {
    data class Candidate(
        val file: Path,
        val owner: String,
        val methodName: String,
        val parameterCount: Int,
        val header: String,
        val source: String,
        val range: IntRange,
        val directItemStack: Boolean,
        val parameterTypes: List<String> = emptyList(),
        val parameters: List<String> = emptyList()
    ) {
        val firstParameterType: String? get() = parameterTypes.firstOrNull()
        fun method(): HolderLookupNbtMethod = HolderLookupNbtMethod(owner, methodName, parameterCount, firstParameterType)
    }
    val candidates = mutableListOf<Candidate>()
    val sourcesByFile = javaFiles.associateWith { runCatching { it.readText() }.getOrDefault("") }
    val projectSerializableTypeFqns = collectProjectINBTSerializableTypes(javaFiles)
    val entityCapabilityTypes = collectEntityCapabilityValueTypes(javaFiles)
    val families = NbtHookFamilies.build(javaFiles)

    // A block entity's level is unset while it loads from chunk NBT, so a hook the mod owns
    // threads the provider instead of reaching for this.getLevel().registryAccess() when it is
    // overridden through the mod's hierarchy or reached from a load hook.
    val blockEntityLoadHooks = setOf("load", "loadAdditional", "read", "readNBT", "readClient", "deserializeNBT")
    fun calledFromBlockEntityLoadHook(owner: String, name: String, parameterCount: Int): Boolean {
        // unqualified / this. / super. calls inside the owner's own hierarchy, or `field.hook(...)` on a value of the owner's type
        val call = Regex("""(?<![\w$.])(?:(?:this|super|([A-Za-z_$][\w$]*))\.)?${Regex.escape(name)}\s*\(""")
        return javaFiles.any { javaFile ->
            val source = sourcesByFile.getValue(javaFile)
            if (!source.contains("$name(")) return@any false
            val executableCode = maskJavaCommentsAndLiterals(source)
            val typeBlocks = javaTypeBlocks(source, executableCode)
            val methods = javaMethodRangesIncludingDefault(source)
            call.findAll(executableCode).any { match ->
                val enclosing = methods.firstOrNull { match.range.first in it.range } ?: return@any false
                if (enclosing.name !in blockEntityLoadHooks || match.range.first < enclosing.range.first + enclosing.header.length) return@any false
                val closeParen = findMatchingParen(executableCode, match.range.last)
                if (closeParen < 0 || splitTopLevelJavaArgs(source.substring(match.range.last + 1, closeParen)).size != parameterCount) return@any false
                val receiver = match.groupValues[1]
                if (receiver.isNotEmpty()) {
                    Regex("""(?<![\w$])${Regex.escape(owner)}\s+${Regex.escape(receiver)}(?![\w$])""").containsMatchIn(executableCode)
                } else {
                    typeBlocks.lastOrNull { match.range.first in it.bodyStart..it.end }?.name?.let { families.related(it, owner) } == true
                }
            }
        }
    }

    fun threadsThroughFamily(owner: String, name: String, parameterCount: Int, header: String, source: String, offset: Int): Boolean =
        header.contains("CompoundTag") &&
            families.isProjectRooted(owner, name, parameterCount) &&
            registryAccessExpressionAt(source, offset, javaInheritanceIndex)?.endsWith(".getLevel().registryAccess()") == true &&
            (families.members(owner, name, parameterCount).size > 1 || calledFromBlockEntityLoadHook(owner, name, parameterCount))
    fun collectCandidates(javaFile: Path, source: String) {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val typeBlocks = javaTypeBlocks(source, executableCode)
        if (typeBlocks.isEmpty()) return
        val classDeclarationsByName = collectJavaClassDeclarations(source).associateBy { it.name }
        val itemStackNames = collectItemStackValueNames(source)
        val itemStackCollectionNames = collectItemStackCollectionNames(source)
        val itemStackHandlerNames = collectItemStackHandlerValueNames(source)
        val fluidTankNames = collectFluidTankValueNames(source, javaInheritanceIndex)
        for (method in javaMethodRangesIncludingDefault(source)) {
            if (method.header.contains("HolderLookup.Provider") ||
                method.header.contains("net.minecraft.core.HolderLookup.Provider")
            ) continue
            val owner = typeBlocks.lastOrNull { method.range.first in it.bodyStart..it.end } ?: continue
            val methodText = source.substring(method.range)
            val parameterCount = javaMethodParameterCount(method.header)
            // An @Override may only be widened together with its whole family, and only when
            // that family is the mod's own (not an override of a platform or library hook).
            val widenable = families.widenable(owner.name, method.name, parameterCount, method.header)
            val directItemStack = widenable &&
                (
                    methodTextNeedsHolderLookupProvider(
                        methodText,
                        itemStackNames,
                        itemStackCollectionNames,
                        itemStackHandlerNames,
                        fluidTankNames,
                        classDeclarationsByName[owner.name]?.let {
                            javaClassExtendsAny(it, setOf("ItemStackHandler"), javaInheritanceIndex)
                        } == true
                    ) || LibraryProviderParameters.callsNeedProvider(methodText, source)
                    ) &&
                (
                    (registryAccessExpressionAt(source, method.range.first, javaInheritanceIndex) == null &&
                        !nbtCallsHaveRegistryAccess(source, method.range, javaInheritanceIndex)) ||
                        threadsThroughFamily(owner.name, method.name, parameterCount, method.header, source, method.range.first) ||
                        DetachedFluidTankNbt.requiresCallerProvider(this, source, method.range, fluidTankNames, javaInheritanceIndex)
                    )
            val parameters = callableParameters(method.header, method.header.indices)
            val parameterTypes = parameters.mapNotNull { parseJavaParameter(it)?.first?.substringAfterLast('.') }
            candidates += Candidate(javaFile, owner.name, method.name, parameterCount, method.header, source, method.range, directItemStack, parameterTypes, parameters)
        }
    }
    val scannedFiles = sourcesByFile.filterValues { it.contains("CompoundTag") }.keys.toMutableSet()
    scannedFiles.forEach { collectCandidates(it, sourcesByFile.getValue(it)) }
    // A caller of such a method need not mention CompoundTag itself (a client helper reading a stack
    // through a static NBT method): files naming a followed method's owner and calling it are scanned
    // too, and their methods that call it are followed in turn.
    // Each source is queried for many owners during the graph walk. Index its identifiers
    // and call names once rather than scanning the whole source for every graph edge.
    val identifierPattern = Regex("""(?<![\w$])[A-Za-z_$][\w$]*""")
    val callNamePattern = Regex("""(?<![\w$])([A-Za-z_$][\w$]*)\s*\(|::([A-Za-z_$][\w$]*)(?![\w$])""")
    val identifiers = java.util.IdentityHashMap<String, Set<String>>()
    val callNames = java.util.IdentityHashMap<String, Set<String>>()
    val methodTexts = java.util.IdentityHashMap<Candidate, String>()
    fun methodText(method: Candidate) = methodTexts.getOrPut(method) { method.source.substring(method.range) }
    val valueTypesBySource = java.util.IdentityHashMap<String, SourceValueTypes>()
    fun valueTypes(source: String) = valueTypesBySource.getOrPut(source) { SourceValueTypes(this, source) }
    val headerRegistries = java.util.IdentityHashMap<Candidate, Lazy<String?>>()
    fun headerRegistry(candidate: Candidate) = headerRegistries.getOrPut(candidate) {
        lazy { registryAccessExpressionAt(candidate.source, candidate.range.first, javaInheritanceIndex) }
    }.value
    val familyThreading = java.util.IdentityHashMap<Candidate, Boolean>()
    fun familyThreads(candidate: Candidate) = familyThreading.getOrPut(candidate) {
        threadsThroughFamily(candidate.owner, candidate.methodName, candidate.parameterCount, candidate.header, candidate.source, candidate.range.first)
    }
    fun mentionsOwner(text: String, method: Candidate) =
        method.owner in identifiers.getOrPut(text) { identifierPattern.findAll(text).map { it.value }.toSet() }
    fun callsName(text: String, method: Candidate) =
        method.methodName in callNames.getOrPut(text) {
            callNamePattern.findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
        }
    val followed = candidates.filter { it.directItemStack }.toMutableSet()
    var frontier: Collection<Candidate> = followed.toList()
    while (frontier.isNotEmpty()) {
        // collected methods calling the frontier (their file names its owner), then files reaching any of them
        val callers = candidates.filter { candidate ->
            candidate !in followed && frontier.any {
                (mentionsOwner(candidate.source, it) || families.related(candidate.owner, it.owner)) && callsName(methodText(candidate), it)
            }
        }
        followed += callers
        val targets = frontier + callers
        val reached = sourcesByFile.filter { (file, text) -> file !in scannedFiles && targets.any { mentionsOwner(text, it) && callsName(text, it) } }
        scannedFiles += reached.keys
        val before = candidates.size
        reached.forEach { (file, source) -> collectCandidates(file, source) }
        val reachedCallers = candidates.drop(before).filter { candidate ->
            targets.any {
                (mentionsOwner(candidate.source, it) || families.related(candidate.owner, it.owner)) && callsName(methodText(candidate), it)
            }
        }
        followed += reachedCallers
        frontier = callers + reachedCallers
    }
    val directCandidateOwners = candidates.map { it.owner }.toSet()
    // Abstract/interface declarations have no body and therefore are not candidates, but their
    // declared receiver type still identifies a call into the candidate's project-owned family.
    val receiverOwnerTypes = buildSet {
        addAll(directCandidateOwners)
        candidates.forEach { candidate ->
            addAll(families.members(candidate.owner, candidate.methodName, candidate.parameterCount))
        }
    }
    val receiverOwnerAlternation =
        receiverOwnerTypes.joinToString("|") { Regex.escape(it) }
    val overloadGroups = candidates.groupBy { Triple(it.owner, it.methodName, it.parameterCount) }
        .filterValues { group -> group.map { it.parameterTypes }.distinct().size > 1 }
    overloadedNbtMethods = overloadGroups.keys.map { it.first to it.second }.toSet()
    overloadedNbtFirstParameterTypes = overloadGroups.entries
        .groupBy({ it.key.first to it.key.second }, { entry -> entry.value.mapNotNull { it.parameterTypes.firstOrNull() } })
        .mapValues { (_, types) -> types.flatten().toSet() }

    // per masked source (the same instance for a file while candidates are collected): its types and
    // the names and getters declared with a candidate owner's type
    data class ReceiverTables(val typeBlocks: List<JavaTypeBlock>, val variableTypes: Map<String, String>, val getterTypes: Map<String, String>, val valueTypes: SourceValueTypes)
    val receiverTables = java.util.IdentityHashMap<String, ReceiverTables>()
    fun sourceReceiverTables(source: String, executableCode: String): ReceiverTables {
        return receiverTables.getOrPut(executableCode) {
            ReceiverTables(
                javaTypeBlocks(source, executableCode),
                if (receiverOwnerAlternation.isBlank()) emptyMap() else Regex("""\b($receiverOwnerAlternation)\s+([A-Za-z_$][\w$]*)\b""")
                    .findAll(executableCode)
                    .associate { it.groupValues[2] to it.groupValues[1] },
                if (receiverOwnerAlternation.isBlank()) emptyMap() else Regex("""(?m)\b(?:public|protected|private)\s+($receiverOwnerAlternation)\s+([A-Za-z_$][\w$]*)\s*\(\s*\)""")
                    .findAll(executableCode)
                    .associate { "${it.groupValues[2]}()" to it.groupValues[1] },
                valueTypes(source)
            )
        }
    }
    fun receiverOwnerAt(source: String, executableCode: String, receiver: String, offset: Int): String? {
        val normalized = receiver.trim()
        val tables = sourceReceiverTables(source, executableCode)
        val typeBlocks = tables.typeBlocks
        if (normalized == "this") {
            return typeBlocks.lastOrNull { offset in it.bodyStart..it.end }?.name
        }
        if (normalized in receiverOwnerTypes) return normalized
        if (receiverOwnerAlternation.isBlank()) return null
        // the declaration in scope where the call stands (a local shadows a same-named field)
        if (Regex("""^[A-Za-z_$][\w$]*$""").matches(normalized)) {
            com.modporter.core.transforms.util.JavaScopedDeclarations.visibleAt(executableCode, normalized, offset)?.let { declaration ->
                return declaration.type.takeIf { it in receiverOwnerTypes }
            }
        }
        val variableTypes = tables.variableTypes
        val getterTypes = tables.getterTypes
        variableTypes[normalized]?.let { return it }
        variableTypes[normalized.substringAfterLast('.')]?.let { return it }
        getterTypes[normalized]?.let { return it }
        getterTypes[normalized.substringAfterLast('.')]?.let { return it }
        arrayElementReceiverOwner(normalized, executableCode, receiverOwnerAlternation)?.let { return it }
        castReceiverType(normalized)?.let { return it }
        tables.valueTypes.typeOf(normalized, offset)?.let { canonical ->
            val simple = canonical.substringAfterLast('.')
            if (simple in receiverOwnerTypes && projectValueFacts.types
                    .filter { it.substringAfterLast('.') == simple }.singleOrNull() == canonical) return simple
        }
        return null
    }

    fun legacyINBTSerializableProviderCallSite(source: String, offset: Int): Boolean {
        if (projectSerializableTypeFqns.isEmpty()) return false
        val executableCode = maskJavaCommentsAndLiterals(source)
        val method = javaMethodRangesIncludingDefault(source).firstOrNull { offset in it.range } ?: return false
        if (!Regex("""\b(?:public|protected)\b""").containsMatchIn(method.header)) return false
        if (method.name == "serializeNBT") {
            if (!method.header.contains("CompoundTag") || method.header.contains("HolderLookup.Provider")) return false
        } else if (method.name == "deserializeNBT") {
            if (!method.header.contains("CompoundTag") || method.header.contains("HolderLookup.Provider")) return false
        } else {
            return false
        }
        val type = javaTypeBlocks(source, executableCode)
            .lastOrNull { method.range.first in it.bodyStart..it.end }
            ?: return false
        val packageName = packageNameOf(source)
        val fqn = if (packageName.isBlank()) type.name else "$packageName.${type.name}"
        return fqn in projectSerializableTypeFqns
    }

    fun constructorHasProviderAwareCallSite(owner: String, parameterCount: Int): Boolean {
        val token = "new $owner("
        for (javaFile in javaFiles) {
            val source = sourcesByFile.getValue(javaFile)
            if (!source.contains(token)) continue
            val executableCode = maskJavaCommentsAndLiterals(source)
            var cursor = 0
            while (true) {
                val tokenIndex = executableCode.indexOf(token, cursor)
                if (tokenIndex < 0) break
                val openParen = tokenIndex + token.length - 1
                val closeParen = findMatchingParen(executableCode, openParen)
                if (closeParen < 0) {
                    cursor = tokenIndex + token.length
                    continue
                }
                val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
                if (args.size == parameterCount &&
                    (
                        holderLookupProviderNamesAt(source, tokenIndex).isNotEmpty() ||
                            registryAccessExpressionAt(source, tokenIndex, javaInheritanceIndex) != null
                        )
                ) {
                    return true
                }
                cursor = closeParen + 1
            }
        }
        return false
    }

    fun enclosingProviderAwareConstructorCallSite(source: String, offset: Int): Boolean {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val type = javaTypeBlocks(source, executableCode)
            .lastOrNull { offset in it.bodyStart..it.end }
            ?: return false
        val constructorPattern = Regex(
            """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:public|protected|private)?\s*${Regex.escape(type.name)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
        )
        val constructor = constructorPattern.findAll(executableCode)
            .firstOrNull { match ->
                if (match.range.first <= type.bodyStart || match.range.first >= type.end) return@firstOrNull false
                val openBrace = executableCode.indexOf('{', match.range.last - 1)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
                openBrace >= 0 && closeBrace >= offset && closeBrace <= type.end
            }
            ?: return false
        val openBrace = executableCode.indexOf('{', constructor.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace < 0 || closeBrace < offset || closeBrace > type.end) return false
        val constructorText = source.substring(constructor.range.first, closeBrace + 1)
        val classDeclarationsByName = collectJavaClassDeclarations(source).associateBy { it.name }
        if (!methodTextNeedsHolderLookupProvider(
                constructorText,
                collectItemStackValueNames(source),
                collectItemStackCollectionNames(source),
                collectItemStackHandlerValueNames(source),
                collectFluidTankValueNames(source, javaInheritanceIndex),
                classDeclarationsByName[type.name]?.let {
                    javaClassExtendsAny(it, setOf("ItemStackHandler"), javaInheritanceIndex)
                } == true
            )
        ) {
            return false
        }
        return constructorHasProviderAwareCallSite(type.name, javaMethodParameterCount(constructor.value))
    }

    // Vanilla block-entity NBT hooks receive the provider once their signatures are migrated.
    val blockEntityNbtHooks = setOf("saveAdditional", "load", "loadAdditional", "getUpdateTag", "handleUpdateTag", "onDataPacket")
    fun blockEntityNbtHookCallSite(method: JavaMethodRange?, declaration: JavaClassDeclaration?): Boolean =
        method?.name?.let { it in blockEntityNbtHooks } == true &&
            javaClassExtendsAny(declaration, javaBlockEntityBaseTypes(), javaInheritanceIndex)

    val sourceByFile = sourcesByFile
    val maskedByFile = javaFiles.associateWith { lazy { maskJavaCommentsAndLiterals(sourceByFile.getValue(it)) } }
    val referencedFiles = mutableMapOf<String, MutableList<Path>>()
    val candidateNames = candidates.map { it.methodName }.toSet()
    for (file in javaFiles) {
        val names = callNamePattern.findAll(maskedByFile.getValue(file).value)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { it in candidateNames }.toSet()
        for (name in names) referencedFiles.getOrPut(name) { mutableListOf() }.add(file)
    }
    val methodRangesByFile = javaFiles.associateWith { lazy { javaMethodRangesIncludingDefault(sourceByFile.getValue(it)) } }
    val typeBlocksByFile = javaFiles.associateWith { file ->
        lazy { javaTypeBlocks(sourceByFile.getValue(file), maskedByFile.getValue(file).value) }
    }
    val classDeclarationsByFile = javaFiles.associateWith { file ->
        lazy { collectJavaClassDeclarations(sourceByFile.getValue(file)) }
    }
    val visibleLibraryCarrierBySource = java.util.IdentityHashMap<String, Boolean>()
    fun hasVisibleLibraryProviderCarrier(source: String): Boolean =
        visibleLibraryCarrierBySource.getOrPut(source) {
            LibraryProviderCarriers.carriers.any { carrier ->
                source.contains("import ${carrier.owner};") ||
                    source.contains("import ${carrier.ownerPackage}.*;") ||
                    source.contains(carrier.owner)
            }
        }

    fun indexedRegistryAccessExpressionAt(
        source: String,
        offset: Int,
        method: JavaMethodRange,
        parameters: List<String>,
        declaration: JavaClassDeclaration?
    ): String? {
        registryAccessFromParameters(parameters, javaInheritanceIndex)?.let { return it }
        if (source.contains("Level")) {
            sourceProvenLocalRegistryAccessExpressionAt(source, offset)?.let { return it }
        }
        guardedEntityPatternRegistryAccessAt(source, offset)?.let { return it }
        LibraryProviderCarriers.fromParameters(parameters, source)?.let { return it }
        clientMinecraftRegistryAccessExpression(source, offset)?.let { return it }
        if (Regex("""\bstatic\b""").containsMatchIn(method.header)) return null
        if (hasVisibleLibraryProviderCarrier(source)) {
            LibraryProviderCarriers.fromEnclosingTypes(source, offset)?.let { return it }
        }
        val selfRegistryAccess = entitySelfRegistryAccessExpression(declaration, javaInheritanceIndex)
        if (selfRegistryAccess == "this.registryAccess()") return selfRegistryAccess
        sourceProvenInheritedRegistryAccessExpressionAt(source, offset, javaInheritanceIndex)?.let { return it }
        return selfRegistryAccess
    }

    // Static source evidence is pure for this collector invocation. The widened caller set is
    // evaluated separately on every graph round and must not enter this cache.
    val providerEvidenceByCallSite = HashMap<Pair<Path, Pair<Int, String>>, Boolean>()
    fun cachedProviderEvidence(
        javaFile: Path,
        source: String,
        offset: Int,
        receiver: String,
        targetOwner: String,
        method: JavaMethodRange?,
        parameters: List<String>?,
        declaration: JavaClassDeclaration?
    ): Boolean =
        providerEvidenceByCallSite.getOrPut(javaFile to (offset to "$receiver\u0000$targetOwner")) {
            val providerNames = parameters
                ?.let { holderLookupProviderNamesFromParameters(it) }
                ?.toSet()
                ?: holderLookupProviderNamesAt(source, offset)
            providerNames.isNotEmpty() ||
                blockEntityNbtHookCallSite(method, declaration) ||
                (
                    if (method != null && parameters != null) {
                        indexedRegistryAccessExpressionAt(source, offset, method, parameters, declaration)
                    } else {
                        registryAccessExpressionAt(source, offset, javaInheritanceIndex)
                    }
                    ) != null ||
                legacyINBTSerializableProviderCallSite(source, offset) ||
                (method == null && enclosingProviderAwareConstructorCallSite(source, offset)) ||
                (
                    capabilityLambdaReceiverOwner(source, offset, receiver.trim(), entityCapabilityTypes) == targetOwner &&
                        capabilityLambdaRegistryAccess(source, offset, receiver.trim()) != null
                    )
        }

    data class IndexedCallSite(
        val javaFile: Path,
        val source: String,
        val offset: Int,
        val staticProviderAware: Lazy<Boolean>,
        val enclosingOwner: String?,
        val enclosingMethodName: String?,
        val enclosingParameterCount: Int?
    )

    fun indexedCallSite(
        javaFile: Path,
        source: String,
        offset: Int,
        receiver: String,
        targetOwner: String
    ): IndexedCallSite {
        val method = methodRangesByFile.getValue(javaFile).value.firstOrNull { offset in it.range }
        val parameters = method?.let { callableParameters(it.header, it.header.indices) }
        val declaration = classDeclarationsByFile.getValue(javaFile).value
            .filter { offset in it.bodyRange }
            .minByOrNull { it.bodyRange.last - it.bodyRange.first }
        val owner = method?.let {
            typeBlocksByFile.getValue(javaFile).value
                .lastOrNull { type -> offset in type.bodyStart..type.end }
                ?.name
        }
        return IndexedCallSite(
            javaFile,
            source,
            offset,
            lazy {
                cachedProviderEvidence(
                    javaFile,
                    source,
                    offset,
                    receiver,
                    targetOwner,
                    method,
                    parameters,
                    declaration
                )
            },
            owner,
            method?.name,
            method?.let { javaMethodParameterCount(it.header) }
        )
    }

    // A call site inside a method that is itself being widened has the provider in scope.
    fun enclosingMethodWidened(callSite: IndexedCallSite, widened: Set<HolderLookupNbtMethod>): Boolean {
        if (widened.isEmpty()) return false
        val owner = callSite.enclosingOwner ?: return false
        val methodName = callSite.enclosingMethodName ?: return false
        val parameterCount = callSite.enclosingParameterCount ?: return false
        return widened.any {
            it.methodName == methodName &&
                it.parameterCount == parameterCount &&
                families.related(owner, it.owner)
        }
    }

    // A method nothing in the project calls or references can take the provider as a parameter
    // without leaving a call site that cannot supply one.
    fun hasNoProjectReference(target: Candidate): Boolean {
        val name = Regex.escape(target.methodName)
        val reference = Regex("""(?<![\w$])(?:$name\s*\(|::$name(?![\w$]))""")
        return referencedFiles[target.methodName].orEmpty().none { javaFile ->
            val source = sourceByFile.getValue(javaFile)
            if (!source.contains(target.methodName)) return@none false
            val executableCode = maskJavaCommentsAndLiterals(source)
            reference.findAll(executableCode).any { match ->
                !(javaFile == target.file &&
                    match.range.first in target.range.first..(target.range.first + target.header.length))
            }
        }
    }

    val traceProviderFailures = java.lang.Boolean.getBoolean("modporter.traceNbtProviders")
    val tracedProviderFailures = mutableSetOf<String>()
    fun traceMissingProvider(target: Candidate, file: Path, source: String, offset: Int) {
        if (!traceProviderFailures) return
        val key = "${target.owner}#${target.methodName}/${target.parameterCount}:$file:$offset"
        if (tracedProviderFailures.add(key)) {
            val line = source.take(offset).count { it == '\n' } + 1
            logger.info { "NBT provider rollback: ${target.owner}#${target.methodName}/${target.parameterCount} called without a provider at $file:$line" }
        }
    }

    val providerCallSitesByTarget =
        java.util.IdentityHashMap<Candidate, List<IndexedCallSite>>()
    fun providerCallSites(target: Candidate): List<IndexedCallSite> =
        providerCallSitesByTarget.getOrPut(target) {
            buildList {
                val token = ".${target.methodName}("
                val unqualifiedCall = Regex("""(?<![\w$.])${Regex.escape(target.methodName)}\s*\(""")
                val methodReference = Regex("""(?<![\w$])((?:this|[A-Za-z_$][\w$]*))::${Regex.escape(target.methodName)}(?![\w$(])""")
                for (javaFile in referencedFiles[target.methodName].orEmpty()) {
                    val source = sourceByFile.getValue(javaFile)
                    if (!source.contains("${target.methodName}(") && !source.contains("::${target.methodName}")) continue
                    val executableCode = maskedByFile.getValue(javaFile).value
                    // a reference to an overloaded name names no overload by itself
                    if (target.parameterCount <= 1 && (target.owner to target.methodName) !in overloadedNbtMethods) {
                        for (reference in methodReference.findAll(executableCode)) {
                            if (javaFile == target.file && reference.range.first in target.range) continue
                            val receiver = reference.groupValues[1]
                            val receiverOwner = receiverOwnerAt(source, executableCode, receiver, reference.range.first) ?: continue
                            if (families.related(receiverOwner, target.owner)) {
                                add(indexedCallSite(javaFile, source, reference.range.first, receiver, target.owner))
                            }
                        }
                    }
                    for (call in unqualifiedCall.findAll(executableCode)) {
                        if (javaFile == target.file && call.range.first in target.range) continue
                        if (methodRangesByFile.getValue(javaFile).value.any { method ->
                                method.name == target.methodName &&
                                    call.range.first >= method.range.first &&
                                    call.range.first < method.range.first + method.header.length
                            }
                        ) continue
                        val closeParen = findMatchingParen(executableCode, call.range.last)
                        if (closeParen < 0) continue
                        // an abstract or interface declaration of the name, not a call of it
                        if (isBodilessJavaMethodDeclaration(executableCode, call.range.first, closeParen)) continue
                        val callArguments = splitTopLevelJavaArgs(source.substring(call.range.last + 1, closeParen))
                        if (callArguments.size != target.parameterCount ||
                            !argumentsFitOverload(target.method(), callArguments) {
                                argumentDeclaredType(executableCode, it, call.range.first)
                                    ?: sourceReceiverTables(source, executableCode).valueTypes.typeOf(it, call.range.first)
                            }
                        ) continue
                        val enclosingOwner = typeBlocksByFile.getValue(javaFile).value
                            .lastOrNull { call.range.first in it.bodyStart..it.end }
                            ?.name
                            ?: continue
                        if (families.related(enclosingOwner, target.owner)) {
                            add(indexedCallSite(javaFile, source, call.range.first, "this", target.owner))
                        }
                    }
                    var cursor = 0
                    while (true) {
                        val tokenIndex = executableCode.indexOf(token, cursor)
                        if (tokenIndex < 0) break
                        if (javaFile == target.file && tokenIndex in target.range) {
                            cursor = tokenIndex + token.length
                            continue
                        }
                        val openParen = tokenIndex + target.methodName.length + 1
                        val closeParen = findMatchingParen(executableCode, openParen)
                        if (closeParen < 0) {
                            cursor = tokenIndex + token.length
                            continue
                        }
                        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
                        if (args.size != target.parameterCount ||
                            !argumentsFitOverload(target.method(), args) {
                                argumentDeclaredType(executableCode, it, tokenIndex)
                                    ?: sourceReceiverTables(source, executableCode).valueTypes.typeOf(it, tokenIndex)
                            }
                        ) {
                            cursor = closeParen + 1
                            continue
                        }
                        val receiverStart = findExpressionReceiverStart(executableCode, tokenIndex)
                        if (receiverStart < 0 || receiverStart >= tokenIndex) {
                            cursor = closeParen + 1
                            continue
                        }
                        val receiver = source.substring(receiverStart, tokenIndex)
                        val receiverOwner = receiverOwnerAt(source, executableCode, receiver, tokenIndex)
                            ?: capabilityLambdaReceiverOwner(source, tokenIndex, receiver.trim(), entityCapabilityTypes)
                        if (receiverOwner != null && families.related(receiverOwner, target.owner)) {
                            add(indexedCallSite(javaFile, source, tokenIndex, receiver, target.owner))
                        }
                        cursor = closeParen + 1
                    }
                }
            }
        }

    // [requireAll]: whether every project call site (rather than some) has a provider or a widened caller
    fun hasProviderAwareProjectCallSite(
        target: Candidate,
        widened: Set<HolderLookupNbtMethod> = emptySet(),
        requireAll: Boolean = false
    ): Boolean {
        for (callSite in providerCallSites(target)) {
            val aware = callSite.staticProviderAware.value ||
                enclosingMethodWidened(callSite, widened)
            if (aware != requireAll) {
                if (requireAll) {
                    traceMissingProvider(
                        target,
                        callSite.javaFile,
                        callSite.source,
                        callSite.offset
                    )
                }
                return aware
            }
        }
        return requireAll
    }

    val result = candidates
        .filter {
            it.directItemStack &&
                (isPrivateOrPackagePrivateJavaMethodHeader(it.header) || hasProviderAwareProjectCallSite(it))
        }
        .mapTo(linkedSetOf()) { it.method() }

    // Unqualified, this. and super. calls of a widened hook from inside its own family.
    fun callsFamilyHook(methodText: String, owner: String, targets: Set<HolderLookupNbtMethod>): Boolean {
        val body = methodText.substring(methodText.indexOf('{').coerceAtLeast(0))
        val executableBody = maskJavaCommentsAndLiterals(body)
        return Regex("""(?<![\w$.])(?:(?:this|super)\.)?([A-Za-z_$][\w$]*)\s*\(""").findAll(executableBody).any { call ->
            val name = call.groupValues[1]
            if (targets.none { it.methodName == name }) return@any false
            val closeParen = findMatchingParen(executableBody, call.range.last)
            if (closeParen < 0) return@any false
            val arguments = splitTopLevelJavaArgs(body.substring(call.range.last + 1, closeParen))
            targets.any {
                it.methodName == name &&
                    (it.parameterCount == null || it.parameterCount == arguments.size) &&
                    families.related(owner, it.owner) &&
                    argumentsFitOverload(it, arguments)
            }
        }
    }

    val expandedFamilies = mutableSetOf<HolderLookupNbtMethod>()
    fun addFamilyMembers(seeds: Iterable<HolderLookupNbtMethod> = result) {
        val pending = ArrayDeque(seeds.filter { it !in expandedFamilies })
        while (pending.isNotEmpty()) {
            val member = pending.removeFirst()
            if (!expandedFamilies.add(member)) continue
            val parameterCount = member.parameterCount ?: continue
            families.members(member.owner, member.methodName, parameterCount)
                .forEach {
                    val relatedMember = HolderLookupNbtMethod(it, member.methodName, parameterCount, member.firstParameterType)
                    if (result.add(relatedMember)) pending.add(relatedMember)
                }
        }
    }

    addFamilyMembers()
    val directMethods = candidates.filter { it.directItemStack }
        .map { it.method() }
        .toSet()

    // Private helpers and project-rooted families take the provider whenever they call a threaded
    // method; any other caller only for a call that has no provider or registry access in scope
    // (such a call is rewritten in place and gives its caller no reason to take the provider).
    val callProviderRequirements = java.util.IdentityHashMap<Candidate, MutableMap<Int, Boolean>>()
    fun callNeedsProvider(candidate: Candidate): (Int) -> Boolean {
        if (isPrivateOrPackagePrivateJavaMethodHeader(candidate.header) ||
            familyThreads(candidate)
        ) return { true }
        return { offsetInMethod ->
            val offset = candidate.range.first + offsetInMethod
            callProviderRequirements.getOrPut(candidate) { mutableMapOf() }.getOrPut(offset) {
                holderLookupProviderNamesAt(candidate.source, offset).isEmpty() &&
                    registryAccessExpressionAt(candidate.source, offset, javaInheritanceIndex) == null
            }
        }
    }

    // The callers that would carry the provider down once the methods in [seeds] are widened:
    // a direct candidate reached only through such a caller still ends at a provider.
    fun tentativeCallers(seeds: Set<HolderLookupNbtMethod>): Set<HolderLookupNbtMethod> {
        val tentative = linkedSetOf<HolderLookupNbtMethod>().apply { addAll(seeds) }
        var grew: Boolean
        do {
            grew = false
            val reachable = tentative + directMethods
            for (candidate in candidates) {
                val method = candidate.method()
                if (method in tentative) continue
                val methodText = methodText(candidate)
                val widenable = families.widenable(candidate.owner, candidate.methodName, candidate.parameterCount, candidate.header)
                if (widenable &&
                    holderLookupProviderFromParameters(candidate.parameters) == null &&
                    (
                        methodTextCallsHolderLookupNbtMethod(
                            methodText,
                            candidate.source,
                            candidate.owner,
                            reachable,
                            families::related,
                            callNeedsProvider(candidate),
                            candidate.range.first,
                            valueTypes(candidate.source)
                        ) ||
                            callsFamilyHook(methodText, candidate.owner, reachable)
                        ) &&
                    (
                        isPrivateOrPackagePrivateJavaMethodHeader(candidate.header) ||
                            headerRegistry(candidate) == null ||
                            familyThreads(candidate)
                        )
                ) {
                    tentative += method
                    grew = true
                }
            }
        } while (grew)
        return tentative
    }

    // A tentative caller only carries a provider down when it is reached from a real provider itself:
    // directly at one of its call sites, or through callers already so reached. A chain that ends in a
    // method nothing can hand a provider to (an override of a library hook, say) stays ungrounded.
    fun groundedCallers(tentative: Set<HolderLookupNbtMethod>): Set<HolderLookupNbtMethod> {
        val grounded = linkedSetOf<HolderLookupNbtMethod>().apply { addAll(providerSources); addAll(result) }
        val pending = candidates.filter { it.method() in tentative && it.method() !in grounded }.toMutableList()
        do {
            val reached = pending.filter { hasProviderAwareProjectCallSite(it, grounded) }
            reached.forEach { grounded += it.method() }
            pending.removeAll(reached)
        } while (reached.isNotEmpty())
        return grounded
    }

    var changed: Boolean
    do {
        changed = false
        val tentative = groundedCallers(tentativeCallers(result))
        for (candidate in candidates) {
            val method = candidate.method()
            if (method in result || !candidate.directItemStack) continue
            if (hasProviderAwareProjectCallSite(candidate, tentative + providerSources) ||
                // annotated methods (@GameTest, @SubscribeEvent, ...) are invoked reflectively
                (!candidate.header.contains("@") &&
                    families.isProjectRooted(candidate.owner, candidate.methodName, candidate.parameterCount) &&
                    hasNoProjectReference(candidate))
            ) {
                result += method
                addFamilyMembers(listOf(method))
                changed = true
            }
        }
        for (candidate in candidates) {
            val method = candidate.method()
            if (method in result) continue
            val methodText = methodText(candidate)
            val methodParameters = candidate.parameters
            val needsThreadedProvider = holderLookupProviderFromParameters(methodParameters) == null &&
                candidate.header.contains("CompoundTag") &&
                isPrivateOrPackagePrivateJavaMethodHeader(candidate.header)
            val widenable = families.widenable(candidate.owner, candidate.methodName, candidate.parameterCount, candidate.header)
            if (widenable &&
                (
                    methodTextCallsHolderLookupNbtMethod(
                        methodText,
                        candidate.source,
                        candidate.owner,
                        result,
                        families::related,
                        callNeedsProvider(candidate),
                        candidate.range.first,
                        valueTypes(candidate.source)
                    ) ||
                        callsFamilyHook(methodText, candidate.owner, result)
                    ) &&
                (
                    needsThreadedProvider ||
                        headerRegistry(candidate) == null ||
                        familyThreads(candidate)
                    )
            ) {
                result += method
                addFamilyMembers(listOf(method))
                changed = true
            }
        }
    } while (changed)
    // A widened method one of whose project call sites has neither a provider nor a widened caller
    // would leave that call uncompilable; it goes back to its old signature with its whole override
    // family, which may in turn strand the callers that were widened only to reach it.
    while (true) {
        val stranded = candidates.filter { it.method() in result && !hasProviderAwareProjectCallSite(it, result + providerSources, requireAll = true) }
        if (stranded.isEmpty()) break
        for (candidate in stranded) {
            val method = candidate.method()
            val owners = families.members(candidate.owner, candidate.methodName, candidate.parameterCount).toSet() + candidate.owner
            result.removeAll { it.owner in owners && it.copy(owner = method.owner) == method }
        }
    }
    staticHolderLookupNbtMethods = candidates
        .filter { Regex("""\bstatic\b""").containsMatchIn(it.header) }
        .map { it.owner to it.methodName }
        .toSet()
    return result
}

internal fun StructuralRefactorPass.collectItemStackNbtMethodsNeedingHolderLookup(
    javaFiles: List<Path>,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY,
    javaProjectTypeIndex: JavaProjectTypeIndex = JavaProjectTypeIndex.build(commonJavaSourceRoot(javaFiles))
): Set<HolderLookupNbtMethod> = collectExactItemStackNbtMethodsNeedingHolderLookup(
    javaFiles,
    javaInheritanceIndex,
    javaProjectTypeIndex
)

internal fun commonJavaSourceRoot(javaFiles: List<Path>): Path {
    require(javaFiles.isNotEmpty()) { "Cannot build a Java project type index without Java source files" }
    var root = javaFiles.first().toAbsolutePath().normalize().parent
        ?: throw IllegalStateException("Java source file has no parent: ${javaFiles.first()}")
    for (file in javaFiles.drop(1)) {
        val normalized = file.toAbsolutePath().normalize()
        while (!normalized.startsWith(root)) {
            root = root.parent
                ?: throw IllegalStateException("Java source files do not share a filesystem root")
        }
    }
    return root
}

internal fun StructuralRefactorPass.collectItemStackNbtConstructorsNeedingHolderLookup(
    javaFiles: List<Path>,
    methods: Set<HolderLookupNbtMethod>,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): Set<HolderLookupNbtConstructor> {
    data class Candidate(val owner: String, val parameterCount: Int)
    val candidates = linkedSetOf<Candidate>()
    val methodsByName = methods.groupBy { it.methodName }
    val ownerNames = methods.map { it.owner }.toSet()
    val ownerAlternation = ownerNames.joinToString("|") { Regex.escape(it) }
    for (javaFile in javaFiles) {
        val source = runCatching { javaFile.readText() }.getOrDefault("")
        if (!source.contains("CompoundTag")) continue
        val executableCode = maskJavaCommentsAndLiterals(source)
        val typeBlocks = javaTypeBlocks(source, executableCode)
        if (typeBlocks.isEmpty()) continue
        val classDeclarationsByName = collectJavaClassDeclarations(source).associateBy { it.name }
        val variableTypes = if (ownerAlternation.isBlank()) emptyMap() else {
            Regex("""\b($ownerAlternation)\s+([A-Za-z_$][\w$]*)\b""")
                .findAll(executableCode)
                .associate { it.groupValues[2] to it.groupValues[1] }
        }
        val itemStackNames = collectItemStackValueNames(source)
        val itemStackCollectionNames = collectItemStackCollectionNames(source)
        val itemStackHandlerNames = collectItemStackHandlerValueNames(source)
        val fluidTankNames = collectFluidTankValueNames(source, javaInheritanceIndex)
        for (type in typeBlocks) {
            val constructorPattern = Regex(
                """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:public|protected|private)?\s*${Regex.escape(type.name)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
            )
            constructorPattern.findAll(executableCode).forEach { match ->
                if (match.range.first <= type.bodyStart || match.range.first >= type.end) return@forEach
                val openBrace = executableCode.indexOf('{', match.range.last - 1)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
                if (openBrace < 0 || closeBrace < 0 || closeBrace > type.end) return@forEach
                val constructorText = source.substring(match.range.first, closeBrace + 1)
                val parameterCount = javaMethodParameterCount(match.value)
                if (methodTextNeedsHolderLookupProvider(
                        constructorText,
                        itemStackNames,
                        itemStackCollectionNames,
                        itemStackHandlerNames,
                        fluidTankNames,
                        classDeclarationsByName[type.name]?.let {
                        javaClassExtendsAny(it, setOf("ItemStackHandler"), JavaInheritanceIndex.EMPTY)
                    } == true
                    )
                ) {
                    candidates += Candidate(type.name, parameterCount)
                    return@forEach
                }
                val body = executableCode.substring(openBrace + 1, closeBrace)
                val callsTarget = methodsByName.any { (methodName, targets) ->
                    Regex("""\b([A-Za-z_$][\w$]*)\.${Regex.escape(methodName)}\s*\(""")
                        .findAll(body)
                        .any { call ->
                            val receiver = call.groupValues[1]
                            val receiverOwner = variableTypes[receiver]
                            receiverOwner != null && targets.any { it.owner == receiverOwner }
                        }
                }
                if (callsTarget) candidates += Candidate(type.name, parameterCount)
            }
        }
    }
    if (candidates.isEmpty()) return emptySet()
    fun constructorHasProviderAwareCallSite(candidate: Candidate): Boolean {
        val token = "new ${candidate.owner}("
        for (javaFile in javaFiles) {
            val source = runCatching { javaFile.readText() }.getOrDefault("")
            if (!source.contains(token)) continue
            val executableCode = maskJavaCommentsAndLiterals(source)
            var cursor = 0
            while (true) {
                val tokenIndex = executableCode.indexOf(token, cursor)
                if (tokenIndex < 0) break
                val openParen = tokenIndex + token.length - 1
                val closeParen = findMatchingParen(executableCode, openParen)
                if (closeParen < 0) {
                    cursor = tokenIndex + token.length
                    continue
                }
                val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
                if (args.size == candidate.parameterCount &&
                    (
                        holderLookupProviderNamesAt(source, tokenIndex).isNotEmpty() ||
                            registryAccessExpressionAt(source, tokenIndex, javaInheritanceIndex) != null
                        )
                ) {
                    return true
                }
                cursor = closeParen + 1
            }
        }
        return false
    }
    return candidates
        .filter(::constructorHasProviderAwareCallSite)
        .mapTo(linkedSetOf()) { HolderLookupNbtConstructor(it.owner, it.parameterCount) }
}

internal fun StructuralRefactorPass.collectHolderLookupConstructorFactoryMethods(
    javaFiles: List<Path>,
    constructors: Set<HolderLookupNbtConstructor>,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): Set<HolderLookupNbtMethod> {
    if (constructors.isEmpty()) return emptySet()
    val constructorsByOwner = constructors.groupBy { it.owner }
    val constructorOwners = constructorsByOwner.keys
    val result = linkedSetOf<HolderLookupNbtMethod>()
    for (javaFile in javaFiles) {
        val source = runCatching { javaFile.readText() }.getOrDefault("")
        if (!source.contains("CompoundTag")) continue
        val executableCode = maskJavaCommentsAndLiterals(source)
        val typeBlocks = javaTypeBlocks(source, executableCode)
        if (typeBlocks.isEmpty()) continue
        javaMethodRangesIncludingDefault(source).forEach { method ->
            if (!method.header.contains("CompoundTag") || method.header.contains("HolderLookup.Provider")) return@forEach
            val owner = typeBlocks.lastOrNull { method.range.first in it.bodyStart..it.end } ?: return@forEach
            val needsThreadedProvider = holderLookupProviderFromParameters(callableParameters(source, method.range)) == null &&
                isPrivateOrPackagePrivateJavaMethodHeader(method.header)
            val callsConstructorWithoutProvider = constructorOwners.any { constructorOwner ->
                val token = "new $constructorOwner("
                var cursor = method.range.first
                var missingProvider = false
                while (cursor <= method.range.last) {
                    val tokenIndex = executableCode.indexOf(token, cursor)
                    if (tokenIndex < 0 || tokenIndex > method.range.last) break
                    val openParen = tokenIndex + token.length - 1
                    val closeParen = findMatchingParen(executableCode, openParen)
                    if (closeParen < 0 || closeParen > method.range.last) break
                    val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
                    val matchingConstructors = constructorsByOwner[constructorOwner].orEmpty()
                        .filter { it.parameterCount == null || it.parameterCount == args.size }
                    if (matchingConstructors.isEmpty()) {
                        cursor = closeParen + 1
                        continue
                    }
                    val providerNames = holderLookupProviderNamesAt(source, tokenIndex)
                    if (args.none { isHolderLookupProviderExpression(it, providerNames) } &&
                        (needsThreadedProvider || registryAccessExpressionAt(source, tokenIndex, javaInheritanceIndex) == null)
                    ) {
                        missingProvider = true
                        break
                    }
                    cursor = closeParen + 1
                }
                missingProvider
            }
            if (callsConstructorWithoutProvider) {
                result += HolderLookupNbtMethod(owner.name, method.name, javaMethodParameterCount(method.header))
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.collectItemStackValueNames(source: String): Set<String> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val declared = Regex("""\bItemStack\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    return declared +
        collectItemStackColorHandlerLambdaNames(source, executableCode) +
        collectItemPropertyFunctionLambdaNames(source, executableCode)
}

internal fun StructuralRefactorPass.collectItemPropertyFunctionLambdaNames(
    source: String,
    executableCode: String = maskJavaCommentsAndLiterals(source)
): Set<String> {
    if (!executableCode.contains("ItemProperties.register(") &&
        !executableCode.contains("net.minecraft.client.renderer.item.ItemProperties.register(")) {
        return emptySet()
    }
    val itemStackParameters = linkedSetOf<String>()
    val callPattern = Regex("""(?:net\.minecraft\.client\.renderer\.item\.)?ItemProperties\.register\s*\(""")
    callPattern.findAll(executableCode).forEach { match ->
        val openParen = executableCode.indexOf('(', match.range.last)
        if (openParen < 0) return@forEach
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) return@forEach
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        val lambda = args.lastOrNull()?.trim() ?: return@forEach
        val lambdaMatch = Regex("""^\(?\s*([A-Za-z_$][\w$]*)\s*,\s*[A-Za-z_$][\w$]*\s*,\s*[A-Za-z_$][\w$]*\s*,\s*[A-Za-z_$][\w$]*\s*\)?\s*->""")
            .find(maskJavaCommentsAndLiterals(lambda))
            ?: return@forEach
        itemStackParameters += lambdaMatch.groupValues[1]
    }
    return itemStackParameters
}

internal fun StructuralRefactorPass.collectItemStackColorHandlerLambdaNames(
    source: String,
    executableCode: String = maskJavaCommentsAndLiterals(source)
): Set<String> {
    val eventVariables = Regex("""\b(?:[A-Za-z_$][\w$]*\.)*RegisterColorHandlersEvent\.Item\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    if (eventVariables.isEmpty()) return emptySet()

    val itemStackParameters = linkedSetOf<String>()
    for (eventVariable in eventVariables) {
        val token = "$eventVariable.register("
        var cursor = 0
        while (true) {
            val tokenIndex = executableCode.indexOf(token, cursor)
            if (tokenIndex < 0) break
            val openParen = tokenIndex + "$eventVariable.register".length
            val closeParen = findMatchingParen(executableCode, openParen)
            if (closeParen < 0) break
            val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
            val lambda = args.firstOrNull()?.trim()
            val match = lambda?.let {
                Regex("""^\(?\s*([A-Za-z_$][\w$]*)\s*,\s*[A-Za-z_$][\w$]*\s*\)?\s*->""")
                    .find(maskJavaCommentsAndLiterals(it))
            }
            if (match != null) {
                itemStackParameters += match.groupValues[1]
            }
            cursor = closeParen + 1
        }
    }
    return itemStackParameters
}

internal fun StructuralRefactorPass.collectItemStackCollectionNames(source: String): Set<String> =
    Regex("""\b(?:NonNullList|List|ArrayList|java\.util\.List|java\.util\.ArrayList)\s*<\s*ItemStack\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet()

internal fun StructuralRefactorPass.collectItemStackHandlerValueNames(source: String): Set<String> {
    val projectTypes = projectNbtHandlerTypeNames.joinToString("|") { Regex.escape(it) }
    val types = """ItemStackHandler|net\.neoforged\.neoforge\.items\.ItemStackHandler|[A-Za-z_$][\w$]*StackHandler""" +
        if (projectTypes.isBlank()) "" else "|$projectTypes"
    return Regex("""\b(?:$types)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet()
}

internal fun StructuralRefactorPass.collectSourceProvenFluidTankValueNames(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): Set<String> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val canonicalFluidTank = "net.neoforged.neoforge.fluids.capability.templates.FluidTank"
    val canonicalBaseVisible =
        Regex("""(?m)^\s*import\s+${Regex.escape(canonicalFluidTank)}\s*;""").containsMatchIn(executableCode) ||
            executableCode.contains(canonicalFluidTank)
    val localFluidTankShadow =
        Regex("""\b(?:class|interface|enum|record)\s+FluidTank\b""").containsMatchIn(executableCode)
    val nonVariableNames = setOf("extends", "implements", "permits", "throws", "instanceof")
    return Regex(
        """(?=\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s+([A-Za-z_$][\w$]*)\b""" +
            """(?:\s*\[\s*])?\s*(?:[=;,:)]))"""
    )
        .findAll(executableCode)
        .filter { match ->
            val type = match.groupValues[1]
            val name = match.groupValues[2]
            name !in nonVariableNames &&
                (type == canonicalFluidTank ||
                    type == "FluidTank" && canonicalBaseVisible && !localFluidTankShadow ||
                    javaInheritanceIndex.isProjectType(type) &&
                    canonicalBaseVisible &&
                    !localFluidTankShadow &&
                    javaInheritanceIndex.inherits(type, setOf("FluidTank")))
        }
        .map { it.groupValues[2] }
        .toSet()
}

internal fun StructuralRefactorPass.collectFluidTankValueNames(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): Set<String> =
    Regex("""\b(?:FluidTank|net\.neoforged\.neoforge\.fluids\.capability\.templates\.FluidTank|[A-Za-z_$][\w$]*Tank)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet() +
        collectSourceProvenFluidTankValueNames(source, javaInheritanceIndex)

internal fun StructuralRefactorPass.collectEntityCapabilityValueTypes(javaFiles: List<Path>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val declaration = Regex(
        """\b(?:net\.neoforged\.neoforge\.capabilities\.)?EntityCapability\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*,\s*(?:net\.minecraft\.core\.)?Direction\s*>\s+([A-Z][A-Z0-9_]*)\b"""
    )
    for (javaFile in javaFiles) {
        val source = runCatching { javaFile.readText() }.getOrDefault("")
        val packageName = packageNameOf(source)
        val topLevel = classNameOfJavaSource(source)
        declaration.findAll(maskJavaCommentsAndLiterals(source)).forEach { match ->
            val valueType = match.groupValues[1].substringAfterLast('.')
            val fieldName = match.groupValues[2]
            result[fieldName] = valueType
            if (topLevel != null) {
                result["$topLevel.$fieldName"] = valueType
                if (packageName.isNotBlank()) {
                    result["$packageName.$topLevel.$fieldName"] = valueType
                }
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.collectEntityAttachmentValueTypes(
    javaFiles: List<Path>
): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val declaration = Regex(
        """\b(?:java\.util\.function\.)?Supplier\s*<\s*(?:net\.neoforged\.neoforge\.attachment\.)?AttachmentType\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s*>\s+([A-Z][A-Z0-9_]*)\b"""
    )
    fun record(key: String, valueType: String) {
        val previous = result[key]
        if (previous != null && previous != valueType) {
            throw IllegalStateException(
                "Conflicting entity attachment value types for '$key': $previous and $valueType"
            )
        }
        result[key] = valueType
    }
    for (javaFile in javaFiles) {
        val source = runCatching { javaFile.readText() }.getOrDefault("")
        val packageName = packageNameOf(source)
        val topLevel = classNameOfJavaSource(source)
        declaration.findAll(maskJavaCommentsAndLiterals(source)).forEach { match ->
            val valueType = match.groupValues[1].substringAfterLast('.')
            val fieldName = match.groupValues[2]
            record(fieldName, valueType)
            if (topLevel != null) {
                record("$topLevel.$fieldName", valueType)
                if (packageName.isNotBlank()) {
                    record("$packageName.$topLevel.$fieldName", valueType)
                }
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.mergeEntityDataValueTypes(
    capabilities: Map<String, String>,
    attachments: Map<String, String>
): Map<String, String> {
    val result = linkedMapOf<String, String>()
    (capabilities.entries + attachments.entries).forEach { (key, valueType) ->
        val previous = result[key]
        if (previous != null && previous != valueType) {
            throw IllegalStateException(
                "Conflicting entity data value types for '$key': $previous and $valueType"
            )
        }
        result[key] = valueType
    }
    return result
}
