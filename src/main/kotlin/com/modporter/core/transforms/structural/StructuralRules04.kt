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

// Structural migration rules, part 04 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateCuriosModifierIdentifiers, migrateCuriosModifierIdentifiers, migrateSimpleChannelPresenceChecks, migrateLegacyItemAttributeModifierOverrides, migrateCumulusMenuApi, migrateRegisterAdditionalModelResourceLocations.

/**
 * `stack.getOrCreateTagElement(key).putX(..)` (ItemStack) and `fluid.getOrCreateChildTag(key).putX(..)`
 * (FluidStack) wrote through to the stack's NBT; in 1.21 both stacks hold a CUSTOM_DATA component, so
 * the child compound is updated inside `CustomData#update` and put back.
 */
internal fun StructuralRefactorPass.migrateLegacyTagElementWriteChains(source: String): String {
    if (!source.contains(".getOrCreateTagElement(") && !source.contains(".getOrCreateChildTag(")) return source
    val args = """((?:[^()]|\([^()]*\))+?)"""
    val chain = Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.(getOrCreateTagElement|getOrCreateChildTag)\(\s*$args\s*\)\.(put[A-Z]\w*)\(\s*$args\s*\)\s*;""")
    val executable = maskJavaCommentsAndLiterals(source)
    return replaceExecutableRegex(source, chain) { match ->
        val indent = match.groupValues[1]
        val receiver = match.groupValues[2]
        val method = match.groupValues[3]
        val key = match.groupValues[4].trim()
        val put = match.groupValues[5]
        val putArgs = match.groupValues[6].trim()
        val receiverType = if (method == "getOrCreateChildTag") "FluidStack" else "ItemStack"
        if (!Regex("""\b$receiverType\s+${Regex.escape(receiver)}\b""").containsMatchIn(executable)) return@replaceExecutableRegex match.value
        val body = "tag -> { net.minecraft.nbt.CompoundTag child = tag.getCompound($key); child.$put($putArgs); tag.put($key, child); }"
        if (receiverType == "ItemStack") {
            "${indent}net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, $receiver, $body);"
        } else {
            "$indent$receiver.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, $receiver.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).update($body));"
        }
    }
}

/**
 * Create 6 (NeoForge 1.21.1): `OpenEndedPipe.fromNBT(tag, HolderLookup.Provider, pos)`,
 * `manageSource(Level, BlockEntity)` and `provideHandler()` returning Create's
 * `ICapabilityProvider<IFluidHandler>` with a nullable `getCapability()` instead of a LazyOptional.
 */
internal fun StructuralRefactorPass.migrateCreateOpenEndedPipeApi(source: String): String {
    if (!source.contains("OpenEndedPipe")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val args = """((?:[^(),]|\([^()]*\))+?)"""
    val edits = mutableListOf<Pair<IntRange, String>>()
    val fromNbt = Regex("""\bOpenEndedPipe\.fromNBT\(\s*$args\s*,\s*$args\s*\)""")
    for (method in javaMethodRangesIncludingDefault(executable)) {
        val body = executable.substring(method.range)
        fromNbt.findAll(body).forEach { match ->
            val access = registryAccessExpressionInScope(method.header, body, match.range.first) ?: return@forEach
            val start = method.range.first + match.range.first
            edits += (start..method.range.first + match.range.last) to
                "OpenEndedPipe.fromNBT(${match.groupValues[1].trim()}, $access, ${match.groupValues[2].trim()})"
        }
    }
    var result = source
    edits.sortedByDescending { it.first.first }.forEach { (range, text) -> result = result.replaceRange(range, text) }
    result = replaceExecutableRegex(result, Regex("""\.manageSource\(\s*([A-Za-z_$][\w$]*)\s*\)""")) { ".manageSource(${it.groupValues[1]}, null)" }
    result = replaceExecutableRegex(
        result,
        Regex("""\.provideHandler\(\)\.(?:orElseThrow|orElse)\((?:[^()]|\([^()]*\))*\)""")
    ) { ".provideHandler().getCapability()" }
    return result
}

/** Only the proven target library value changes its amount accessor. */
internal fun StructuralRefactorPass.migrateCreateFluidIngredientAmounts(source: String): String {
    return migrateProvenFluidIngredientAmounts(source)
}

/** `RecipeManager#byKey` yields `RecipeHolder`s in 1.21; code that used the recipe itself maps to its value. */
internal fun StructuralRefactorPass.migrateRecipeManagerByKeyValues(source: String): String {
    if (!source.contains(".byKey(") || source.contains("RecipeHolder")) return source
    return replaceExecutableRegex(
        source,
        Regex("""\.byKey\(\s*((?:[^()]|\((?:[^()]|\([^()]*\))*\))+?)\s*\)(\s*)\.(orElseThrow|orElse|get|ifPresent)\(""")
    ) { match ->
        ".byKey(${match.groupValues[1].trim()})${match.groupValues[2]}.map(net.minecraft.world.item.crafting.RecipeHolder::value)${match.groupValues[2]}.${match.groupValues[3]}("
    }
}

/** Static helpers that read `this.registries` (added for 1.21 loot providers) become instance methods. */
internal fun StructuralRefactorPass.demoteStaticRegistriesHelpers(source: String): String {
    val executable = maskJavaCommentsAndLiterals(source)
    val edits = mutableListOf<IntRange>()
    javaMethodRangesIncludingDefault(executable).forEach { method ->
        if (!executable.substring(method.range).contains("this.registries")) return@forEach
        val openBrace = executable.indexOf('{', method.range.first)
        if (openBrace < 0) return@forEach
        val header = executable.substring(method.range.first, openBrace)
        val static = Regex("""\bstatic\s+""").find(header) ?: return@forEach
        edits += (method.range.first + static.range.first)..(method.range.first + static.range.last)
    }
    if (edits.isEmpty()) return source
    var result = source
    edits.sortedByDescending { it.first }.forEach { range -> result = result.removeRange(range) }
    return result
}

/**
 * Forge's PlayerSleepInBedEvent (setResult(BedSleepingProblem) / getResultStatus()) is NeoForge's
 * CanPlayerSleepEvent with setProblem/getProblem.
 */
internal fun StructuralRefactorPass.migratePlayerSleepInBedEventSource(source: String): String {
    if (!source.contains("PlayerSleepInBedEvent")) return source
    var result = source
        .replace(
            Regex("""\bnet\.(?:minecraftforge|neoforged\.neoforge)\.event\.entity\.player\.PlayerSleepInBedEvent\b"""),
            "net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent"
        )
        .replace(Regex("""\bPlayerSleepInBedEvent\b"""), "CanPlayerSleepEvent")
    val executable = maskJavaCommentsAndLiterals(result)
    Regex("""\bCanPlayerSleepEvent\s+([A-Za-z_$][\w$]*)\b""").findAll(executable)
        .map { it.groupValues[1] }
        .toSet()
        .forEach { event ->
            val escaped = Regex.escape(event)
            result = replaceExecutableRegex(result, Regex("""\b$escaped\.setResult\(""")) { "$event.setProblem(" }
            result = replaceExecutableRegex(result, Regex("""\b$escaped\.getResultStatus\(\)""")) { "$event.getProblem()" }
        }
    return result
}

/** `Holder#get()` (1.20.1 alias of value()) is gone in 1.21; iterated holders use value(). */
internal fun StructuralRefactorPass.migrateHolderIterableElementAccess(source: String): String {
    if (!source.contains("Holder<") || !source.contains(".get()")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val holderIterables = Regex("""\b(?:Iterable\s*<\s*Holder\s*<[^<>]+>\s*>|HolderSet(?:\.Named)?\s*<[^<>]+>|List\s*<\s*Holder\s*<[^<>]+>\s*>)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executable)
        .map { it.groupValues[1] }
        .toSet()
    if (holderIterables.isEmpty()) return source
    val edits = mutableListOf<Pair<IntRange, String>>()
    holderIterables.forEach { iterable ->
        Regex("""\b${Regex.escape(iterable)}\.forEach\(\s*\(?\s*([A-Za-z_$][\w$]*)\s*\)?\s*->""").findAll(executable).forEach { lambda ->
            val element = lambda.groupValues[1]
            val openParen = executable.indexOf('(', lambda.range.first + iterable.length)
            val closeParen = if (openParen >= 0) findMatchingParen(executable, openParen) else -1
            if (closeParen < 0) return@forEach
            Regex("""\b${Regex.escape(element)}\.get\(\)""").findAll(executable.substring(lambda.range.last, closeParen)).forEach { use ->
                val start = lambda.range.last + use.range.first
                edits += (start..start + use.value.length - 1) to "$element.value()"
            }
        }
        Regex("""\bfor\s*\(\s*(?:final\s+)?Holder\s*<[^<>]+>\s+([A-Za-z_$][\w$]*)\s*:\s*${Regex.escape(iterable)}\s*\)\s*\{""").findAll(executable).forEach { loop ->
            val element = loop.groupValues[1]
            val openBrace = executable.indexOf('{', loop.range.last)
            val closeBrace = findMatchingBrace(executable, openBrace)
            if (closeBrace < 0) return@forEach
            Regex("""\b${Regex.escape(element)}\.get\(\)""").findAll(executable.substring(openBrace, closeBrace)).forEach { use ->
                val start = openBrace + use.range.first
                edits += (start..start + use.value.length - 1) to "$element.value()"
            }
        }
    }
    if (edits.isEmpty()) return source
    var result = source
    edits.sortedByDescending { it.first.first }.forEach { (range, text) -> result = result.replaceRange(range, text) }
    return result
}

/**
 * NeoForge 1.21's QuadBakingVertexConsumer has no completion callback: the quad is read back with
 * bakeQuad() after the four vertices, and VertexConsumer#addVertex takes floats.
 */
internal fun StructuralRefactorPass.migrateQuadBakingVertexConsumerCallbacks(source: String): String {
    if (!source.contains("QuadBakingVertexConsumer")) return source
    var result = source
    val executable = maskJavaCommentsAndLiterals(result)
    val declaration = Regex(
        """(?m)^([ \t]*)QuadBakingVertexConsumer\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+QuadBakingVertexConsumer\(\s*\(?\s*([A-Za-z_$][\w$]*)\s*\)?\s*->\s*([A-Za-z_$][\w$]*(?:\[[^\]]*\])?)\s*=\s*\3\s*\)\s*;"""
    )
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executable)) {
        val body = executable.substring(method.range)
        declaration.findAll(body).forEach { match ->
            val indent = match.groupValues[1]
            val builder = match.groupValues[2]
            val target = match.groupValues[4]
            val declarationStart = method.range.first + match.range.first
            edits += (declarationStart..method.range.first + match.range.last) to
                "${indent}QuadBakingVertexConsumer $builder = new QuadBakingVertexConsumer();"
            val returnStatement = Regex("""(?m)^([ \t]*)return\s+${Regex.escape(target)}\s*;""").find(body, match.range.last) ?: return@forEach
            val returnStart = method.range.first + returnStatement.range.first
            edits += (returnStart..returnStart - 1) to "${returnStatement.groupValues[1]}$target = $builder.bakeQuad();\n"
        }
        val doubleParameters = javaMethodParameters(result.substring(method.range))
            .filter { it.type == "double" }
            .map { it.name }
            .toSet()
        if (doubleParameters.isEmpty()) continue
        Regex("""\.addVertex\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)""").findAll(body).forEach { call ->
            val arguments = call.groupValues.drop(1)
            if (arguments.none { it in doubleParameters }) return@forEach
            val cast = arguments.joinToString(", ") { if (it in doubleParameters) "(float) $it" else it }
            edits += (method.range.first + call.range.first..method.range.first + call.range.last) to ".addVertex($cast)"
        }
    }
    if (edits.isEmpty()) return source
    edits.sortedByDescending { it.first.first }.forEach { (range, text) ->
        result = if (range.last < range.first) result.replaceRange(range.first, range.first, text) else result.replaceRange(range, text)
    }
    return result
}

/** NeoForge 1.21 FluidStack has no copy constructor; a tank's fluid is copied with copyWithAmount. */
internal fun StructuralRefactorPass.migrateFluidStackCopyConstructors(source: String): String {
    if (!source.contains("new FluidStack(")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val tanks = Regex("""\b(?:(?:I)?FluidTank|IFluidHandler(?:Item)?|FluidTank)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executable).map { it.groupValues[1] }.toSet()
    if (tanks.isEmpty()) return source
    return replaceExecutableRegex(
        source,
        Regex("""new\s+FluidStack\(\s*([A-Za-z_$][\w$]*)\.getFluid\(\)\s*,\s*((?:[^(),]|\([^()]*\))+?)\s*\)""")
    ) { match ->
        if (match.groupValues[1] !in tanks) match.value
        else "${match.groupValues[1]}.getFluid().copyWithAmount(${match.groupValues[2].trim()})"
    }
}

/**
 * Game-test fixtures that pin the source game's data version (1.20.1 = 3465) as "the current
 * version", or a version just above it as "a newer game", move with the target version
 * (1.21.1 = 3955; next release 1.21.2 = 4080).
 */
internal fun StructuralRefactorPass.migrateVersionRelativeGameTestFixtures(source: String): String {
    if (!source.contains("@GameTest") || !source.contains("DataVersion") && !source.contains("3955")) return source
    var result = source
    if (source.contains("getCurrentVersion().getDataVersion()")) {
        result = replaceExecutableRegex(result, Regex("""(==\s*)3465\b""")) { "${it.groupValues[1]}3955" }
    }
    result = Regex(""""1\.21\.1"\s*,\s*3955\b""").replace(result, "\"1.21.2\", 4080")
    return result
}

/**
 * In 1.21 the effect identity is `Holder<MobEffect>`: `MobEffects.*`, `MobEffectInstance#getEffect()`
 * and the registry hand out holders, and `LivingEntity#addEffect/hasEffect/removeEffect` take them.
 * Members carrying an effect identity (locals, parameters, fields, map keys and values and entries
 * fed by those sources, and the methods returning them) change type across the project until no
 * new evidence appears; value-only accessors go through `Holder#value()`.
 */
internal fun StructuralRefactorPass.migrateMobEffectIdentityFlows(javaFiles: List<Path>, dryRun: Boolean): List<Change> {
    val sources = javaFiles.filter { it.readText().contains("MobEffect") }.associateWith { it.readText() }.toMutableMap()
    if (sources.isEmpty()) return emptyList()
    val holderMethods = linkedSetOf<String>()
    val changedFiles = linkedSetOf<Path>()
    var round = 0
    while (round < 6) {
        var progressed = false
        for ((file, source) in sources.toList()) {
            val migrated = migrateMobEffectIdentityFlowsSource(source, holderMethods, sources.values.toList())
            if (migrated != source) {
                sources[file] = migrated
                changedFiles.add(file)
                progressed = true
            }
        }
        if (!progressed) break
        round++
    }
    val changes = mutableListOf<Change>()
    changedFiles.forEach { file ->
        if (!dryRun) file.writeText(sources.getValue(file))
        changes.add(Change(
            file = file,
            line = 0,
            description = "Carry MobEffect identities as Holder<MobEffect> through locals, parameters, fields, maps and returns",
            before = "MobEffect fed by MobEffects.*, getEffect() or a holder-returning method",
            after = "Holder<MobEffect>, with value() for effect-only accessors",
            confidence = Confidence.HIGH,
            ruleId = "struct-mobeffect-identity-holders"
        ))
    }
    return changes
}

internal fun StructuralRefactorPass.migrateMobEffectIdentityFlowsSource(source: String, holderMethods: MutableSet<String>, allSources: List<String>): String {
    val className = classNameOfJavaSource(source) ?: return source
    var result = source
    var executable = maskJavaCommentsAndLiterals(result)
    if (!executable.contains("MobEffect")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val holderNames = linkedSetOf<String>()
    val holderKeyMaps = linkedSetOf<String>()
    val holderValueMaps = linkedSetOf<String>()
    val holderEntries = linkedSetOf<String>()
    fun refresh() {
        executable = maskJavaCommentsAndLiterals(result)
        Regex("""\bHolder\s*<\s*MobEffect\s*>\s+($id)\b""").findAll(executable).forEach { holderNames += it.groupValues[1] }
        Regex("""\bMap\s*<\s*Holder\s*<\s*MobEffect\s*>\s*,[^>]*>\s+($id)\b""").findAll(executable).forEach { holderKeyMaps += it.groupValues[1] }
        Regex("""\bMap\s*<\s*[^,<>]+,\s*Holder\s*<\s*MobEffect\s*>\s*>\s+($id)\b""").findAll(executable).forEach { holderValueMaps += it.groupValues[1] }
        Regex("""\bMap\.Entry\s*<\s*Holder\s*<\s*MobEffect\s*>\s*,[^>]*>\s+($id)\b""").findAll(executable).forEach { holderEntries += it.groupValues[1] }
    }
    refresh()

    fun isHolderExpression(expression: String): Boolean {
        val e = expression.trim()
        if (Regex("""^MobEffects\.[A-Z0-9_]+$""").matches(e)) return true
        if (Regex("""\.getEffect\(\)$""").containsMatchIn(e)) return true
        if (e.contains("MOB_EFFECT.getHolder(")) return true
        if (e in holderNames) return true
        val call = Regex("""^(?:($id(?:\.$id)*)\.)?($id)\(""").find(e)
        if (call != null) {
            val owner = call.groupValues[1].substringAfterLast('.').ifEmpty { className }
            if ("$owner.${call.groupValues[2]}" in holderMethods) return true
        }
        val mapGet = Regex("""^($id)\.(?:get|getOrDefault)\(""").find(e)
        if (mapGet != null && mapGet.groupValues[1] in holderValueMaps) return true
        val entryKey = Regex("""^($id)\.getKey\(\)$""").find(e)
        if (entryKey != null && entryKey.groupValues[1] in holderEntries) return true
        return false
    }

    fun callArguments(name: String, text: String): List<List<String>> {
        val masked = maskJavaCommentsAndLiterals(text)
        return Regex("""(?<![\w$])${Regex.escape(name)}\s*\(""").findAll(masked).mapNotNull { call ->
            val open = masked.indexOf('(', call.range.first)
            val close = findMatchingParen(masked, open)
            if (close <= open) null else splitTopLevelJavaArgs(text.substring(open + 1, close))
        }.toList()
    }

    var changed = false
    fun edit(pattern: Regex, replacement: (MatchResult) -> String?) {
        val before = result
        result = replaceExecutableRegex(result, pattern) { match -> replacement(match) ?: match.value }
        if (result != before) {
            changed = true
            refresh()
        }
    }

    // locals and fields initialised from a holder expression
    edit(Regex("""(?m)^([ \t]*(?:(?:private|protected|public|static|final)\s+)*)MobEffect\s+($id)\s*=\s*([^;]+?)\s*;""")) { match ->
        if (!isHolderExpression(match.groupValues[3])) null
        else "${match.groupValues[1]}Holder<MobEffect> ${match.groupValues[2]} = ${match.groupValues[3]};"
    }
    // parameters whose call sites receive holders, or that key a holder map
    for (method in javaMethodRangesIncludingDefault(executable).asReversed()) {
        val methodText = result.substring(method.range)
        val parameters = javaMethodParameters(methodText)
        parameters.forEachIndexed { index, parameter ->
            if (simpleJavaTypeName(parameter.type) != "MobEffect") return@forEachIndexed
            val fedByHolder = allSources.any { text ->
                callArguments(method.name, text).any { args -> args.size > index && isHolderExpression(args[index]) }
            }
            val keysHolderMap = holderKeyMaps.any { map ->
                Regex("""\b${Regex.escape(map)}\.(?:get|getOrDefault|containsKey|remove)\(\s*${Regex.escape(parameter.name)}\s*[,)]""").containsMatchIn(methodText)
            }
            if (!fedByHolder && !keysHolderMap) return@forEachIndexed
            val migratedMethod = replaceExecutableRegex(methodText, Regex("""\bMobEffect\s+${Regex.escape(parameter.name)}\b""")) { "Holder<MobEffect> ${parameter.name}" }
            if (migratedMethod != methodText) {
                result = result.replaceRange(method.range, migratedMethod)
                changed = true
                refresh()
            }
        }
    }
    // maps, entries and collections fed by holders
    edit(Regex("""\bMap\s*<\s*MobEffect\s*,\s*([^<>]+?)\s*>\s+($id)\b""")) { match ->
        val map = match.groupValues[2]
        val fed = Regex("""\b${Regex.escape(map)}\.put\(\s*((?:[^(),]|\([^()]*\))+?)\s*,""").findAll(executable).any { isHolderExpression(it.groupValues[1]) }
        if (fed) "Map<Holder<MobEffect>, ${match.groupValues[1]}> $map" else null
    }
    edit(Regex("""\bMap\s*<\s*([^<>,]+?)\s*,\s*MobEffect\s*>\s+($id)\b""")) { match ->
        val map = match.groupValues[2]
        val fed = Regex("""\b${Regex.escape(map)}\.put\(\s*(?:[^(),]|\([^()]*\))+?\s*,\s*((?:[^(),]|\([^()]*\))+?)\s*\)""").findAll(executable).any { isHolderExpression(it.groupValues[1]) }
        if (fed) "Map<${match.groupValues[1]}, Holder<MobEffect>> $map" else null
    }
    edit(Regex("""\bMap\.Entry\s*<\s*MobEffect\s*,\s*([^<>]+?)\s*>\s+($id)\s*:\s*($id)\.entrySet\(\)""")) { match ->
        if (match.groupValues[3] !in holderKeyMaps) null
        else "Map.Entry<Holder<MobEffect>, ${match.groupValues[1]}> ${match.groupValues[2]} : ${match.groupValues[3]}.entrySet()"
    }
    edit(Regex("""\bfor\s*\(\s*MobEffect\s+($id)\s*:\s*($id)\.keySet\(\)\s*\)""")) { match ->
        if (match.groupValues[2] !in holderKeyMaps) null
        else "for (Holder<MobEffect> ${match.groupValues[1]} : ${match.groupValues[2]}.keySet())"
    }
    // methods handing out holders
    for (method in javaMethodRangesIncludingDefault(executable).asReversed()) {
        val methodText = result.substring(method.range)
        val header = methodText.substring(0, methodText.indexOf('{').coerceAtLeast(0))
        if (!Regex("""\bMobEffect\s+${Regex.escape(method.name)}\s*\(""").containsMatchIn(header)) continue
        val returnsHolder = Regex("""\breturn\s+([^;]+?)\s*;""").findAll(maskJavaCommentsAndLiterals(methodText))
            .any { isHolderExpression(it.groupValues[1]) }
        if (!returnsHolder) continue
        val migratedMethod = replaceExecutableRegex(methodText, Regex("""\bMobEffect\s+${Regex.escape(method.name)}\s*\(""")) { "Holder<MobEffect> ${method.name}(" }
        if (migratedMethod != methodText) {
            result = result.replaceRange(method.range, migratedMethod)
            holderMethods += "$className.${method.name}"
            changed = true
            refresh()
        }
    }
    // methods handing out holder-keyed maps
    for (method in javaMethodRangesIncludingDefault(executable).asReversed()) {
        val methodText = result.substring(method.range)
        val header = methodText.substring(0, methodText.indexOf('{').coerceAtLeast(0))
        val mapReturn = Regex("""\bMap\s*<\s*MobEffect\s*,\s*([^<>]+?)\s*>\s+${Regex.escape(method.name)}\s*\(""").find(header) ?: continue
        val returnsHolderMap = Regex("""\breturn\s+($id)\s*;""").findAll(maskJavaCommentsAndLiterals(methodText))
            .any { it.groupValues[1] in holderKeyMaps }
        if (!returnsHolderMap) continue
        val migratedMethod = methodText.replaceRange(mapReturn.range, "Map<Holder<MobEffect>, ${mapReturn.groupValues[1]}> ${method.name}(")
        result = result.replaceRange(method.range, migratedMethod)
        changed = true
        refresh()
    }
    // a holder wrapped again by an earlier rule is already the holder
    holderNames.forEach { name ->
        val before = result
        result = replaceExecutableRegex(result, Regex("""BuiltInRegistries\.MOB_EFFECT\.wrapAsHolder\(\s*${Regex.escape(name)}\s*\)""")) { name }
        if (result != before) changed = true
    }
    if (!changed) return source
    // identity comparisons and value-only accessors on the holders
    holderNames.forEach { name -> result = migrateHolderMobEffectVariableAccess(result, name, emptySet()) }
    holderEntries.forEach { entry ->
        val escaped = Regex.escape(entry)
        result = replaceExecutableRegex(result, Regex("""\b$escaped\.getKey\(\)\s*==\s*(MobEffects\.[A-Z0-9_]+)\b""")) { "$entry.getKey().is(${it.groupValues[1]})" }
        result = replaceExecutableRegex(result, Regex("""\b$escaped\.getKey\(\)\s*!=\s*(MobEffects\.[A-Z0-9_]+)\b""")) { "!$entry.getKey().is(${it.groupValues[1]})" }
    }
    return addImportIfMissing(result, "net.minecraft.core.Holder")
}

/**
 * Block#playerWillDestroy returns the block state in 1.21. A super call that is followed by more
 * work keeps its result in a local that the method returns at its end.
 */
/** Replaces matches of [pattern] that lie outside lambda bodies and anonymous class bodies. */
internal fun StructuralRefactorPass.replaceOutsideNestedBodies(text: String, pattern: Regex, replacement: (MatchResult) -> String): String {
    val masked = maskNestedBodies(text)
    val edits = pattern.findAll(masked).map { it.range to replacement(it) }.toList()
    return edits.asReversed().fold(text) { current, (range, value) -> current.replaceRange(range, value) }
}

/** Blanks the inside of `-> { ... }` / `-> expression` lambdas and `new T(...) { ... }` bodies, keeping offsets. */
internal fun StructuralRefactorPass.maskNestedBodies(text: String): String {
    val executable = maskJavaCommentsAndLiterals(text)
    val chars = executable.toCharArray()
    for (match in Regex("""->\s*\{|\bnew\s+[A-Za-z_$][\w$.]*(?:\s*<[^<>{}();]*>)?\s*\((?:[^()]|\([^()]*\))*\)\s*\{""").findAll(executable)) {
        val lineStart = executable.lastIndexOf('\n', match.range.first) + 1
        if (Regex("""^\s*(?:case\b.*|default)\s*$""").matches(executable.substring(lineStart, match.range.first))) continue
        val open = match.range.last
        val close = findMatchingBrace(executable, open)
        if (close < 0) continue
        for (index in open + 1 until close) chars[index] = ' '
    }
    for (match in Regex("""->\s*(?!\{)""").findAll(executable)) {
        val lineStart = executable.lastIndexOf('\n', match.range.first) + 1
        if (Regex("""^\s*(?:case\b.*|default)\s*$""").matches(executable.substring(lineStart, match.range.first))) continue
        var depth = 0
        var index = match.range.last + 1
        while (index < chars.size) {
            val c = executable[index]
            if (c == '(' || c == '[') depth++
            if ((c == ')' || c == ']') && depth-- == 0) break
            if (c == ';' || (c == ',' && depth == 0)) break
            chars[index] = ' '
            index++
        }
    }
    return String(chars)
}

internal fun StructuralRefactorPass.migratePlayerWillDestroyReturnFlow(source: String): String {
    if (!source.contains("return super.playerWillDestroy(")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val edits = mutableListOf<Pair<Int, Pair<Int, String>>>() // position to (length, replacement)
    for (method in javaMethodRangesIncludingDefault(executable)) {
        if (method.name != "playerWillDestroy") continue
        val openBrace = executable.indexOf('{', method.range.first)
        if (openBrace < 0) continue
        val closeBrace = method.range.last
        val body = executable.substring(openBrace + 1, closeBrace)
        val call = Regex("""(?m)^([ \t]*)return\s+super\.playerWillDestroy\((?:[^()]|\([^()]*\))*\)\s*;""").find(body) ?: continue
        val prefix = body.substring(0, call.range.first)
        if (prefix.count { it == '{' } != prefix.count { it == '}' }) continue
        val rest = body.substring(call.range.last + 1)
        if (rest.isBlank()) continue
        val indent = call.groupValues[1]
        val start = openBrace + 1 + call.range.first
        val raw = source.substring(start, openBrace + 1 + call.range.last + 1)
        edits += start to (raw.length to raw.replaceFirst("return super.playerWillDestroy(", "BlockState modporterDestroyedState = super.playerWillDestroy("))
        val restStart = openBrace + 1 + call.range.last + 1
        for (bareReturn in Regex("""(?<![\w$])return\s*;""").findAll(maskNestedBodies(rest))) {
            edits += (restStart + bareReturn.range.first) to (bareReturn.value.length to "return modporterDestroyedState;")
        }
        if (!Regex("""\breturn\b[^;]*;\s*$""").containsMatchIn(rest)) {
            val braceLineStart = source.lastIndexOf('\n', closeBrace) + 1
            edits += braceLineStart to (0 to "${indent}return modporterDestroyedState;\n")
        }
    }
    if (edits.isEmpty()) return source
    var result = source
    edits.sortedByDescending { it.first }.forEach { (position, edit) ->
        result = result.replaceRange(position, position + edit.first, edit.second)
    }
    return result
}

/** Loot bonus functions take enchantment holders in 1.21; loot providers resolve them from their registries. */
internal fun StructuralRefactorPass.migrateLootEnchantmentHolderArguments(source: String): String {
    if (!source.contains("ApplyBonusCount.")) return source
    if (!source.contains("this.registries") && !source.contains("extends BlockLootSubProvider")) return source
    return replaceExecutableRegex(
        source,
        Regex("""ApplyBonusCount\.(addBonusBinomialDistributionCount|addOreBonusCount|addUniformBonusCount)\(\s*(Enchantments\.[A-Z_]+)\b""")
    ) { match ->
        "ApplyBonusCount.${match.groupValues[1]}(this.registries.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(${match.groupValues[2]})"
    }
}

/**
 * ItemStack#hurt(int, RandomSource, ServerPlayer) is gone in 1.21: ItemStack#hurtAndBreak(int,
 * ServerLevel, player, Consumer<Item>) damages the stack and runs the callback when it breaks.
 */
internal fun StructuralRefactorPass.migrateItemStackHurtCalls(source: String): String {
    if (!source.contains(".hurt(")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val itemStacks = collectItemStackValueNames(source)
    val serverPlayers = Regex("""\bServerPlayer\s+([A-Za-z_$][\w$]*)\b""").findAll(executable).map { it.groupValues[1] }.toSet()
    val entityClass = Regex("""\bclass\s+[A-Za-z_$][\w$]*(?:<[^>]*>)?\s+extends\s+[A-Za-z_$][\w$.]*(?:Entity|Mob|Projectile|Monster|Animal)\b""").containsMatchIn(executable)
    val args = """((?:[^(),]|\([^()]*\))+?)"""
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executable)) {
        val body = executable.substring(method.range)
        val serverLevel = Regex("""\bServerLevel\s+([A-Za-z_$][\w$]*)\b""").find(body)?.groupValues?.get(1)
        val hurtIf = Regex("""(?m)^([ \t]*)if\s*\(\s*([A-Za-z_$][\w$]*)\.hurt\(\s*$args\s*,\s*$args\s*,\s*$args\s*\)\s*\)\s*\{""")
        val hurtStatement = Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.hurt\(\s*$args\s*,\s*$args\s*,\s*$args\s*\)\s*;""")
        fun levelFor(player: String): Pair<String, String?>? = when {
            player in serverPlayers -> "$player.serverLevel()" to null
            serverLevel != null -> serverLevel to null
            entityClass -> "modporterServerLevel" to "level() instanceof net.minecraft.server.level.ServerLevel modporterServerLevel"
            else -> null
        }
        fun playerArgument(player: String): String =
            if (player == "null") "(net.minecraft.server.level.ServerPlayer) null" else player
        hurtIf.findAll(body).forEach { match ->
            val receiver = match.groupValues[2]
            if (receiver !in itemStacks) return@forEach
            val player = match.groupValues[5].trim()
            val (level, guard) = levelFor(player) ?: return@forEach
            val open = method.range.first + match.range.last
            val close = findMatchingBrace(executable, open)
            if (close < 0) return@forEach
            val indent = match.groupValues[1]
            val block = source.substring(open + 1, close).trimEnd()
            val call = "$receiver.hurtAndBreak(${match.groupValues[3].trim()}, $level, ${playerArgument(player)}, modporterBrokenItem -> {$block\n$indent});"
            val replacement = if (guard == null) "$indent$call" else "${indent}if ($guard) {\n$indent    $call\n$indent}"
            edits += (method.range.first + match.range.first..close) to replacement
        }
        hurtStatement.findAll(body).forEach { match ->
            val receiver = match.groupValues[2]
            if (receiver !in itemStacks) return@forEach
            val player = match.groupValues[5].trim()
            val (level, guard) = levelFor(player) ?: return@forEach
            val indent = match.groupValues[1]
            val call = "$receiver.hurtAndBreak(${match.groupValues[3].trim()}, $level, ${playerArgument(player)}, modporterBrokenItem -> {});"
            val replacement = if (guard == null) "$indent$call" else "${indent}if ($guard) {\n$indent    $call\n$indent}"
            edits += (method.range.first + match.range.first..method.range.first + match.range.last) to replacement
        }
    }
    if (edits.isEmpty()) return source
    var result = source
    edits.sortedByDescending { it.first.first }.forEach { (range, text) -> result = result.replaceRange(range, text) }
    return result
}

/** NeoForge's IEventBus#post returns the event; cancellation is read from the event. */
internal fun StructuralRefactorPass.migrateEventBusPostResults(source: String): String {
    if (!source.contains("EVENT_BUS.post(")) return source
    val post = """((?:(?:net\.neoforged\.neoforge\.common\.)?NeoForge|(?:net\.minecraftforge\.common\.)?MinecraftForge)\.EVENT_BUS\.post\((?:[^()]|\([^()]*\))*\))"""
    var result = replaceExecutableRegex(source, Regex("""(boolean\s+[A-Za-z_$][\w$]*\s*=\s*)$post(\s*;)""")) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}.isCanceled()${match.groupValues[3]}"
    }
    result = replaceExecutableRegex(result, Regex("""\bif\s*\(\s*(!?)$post\s*\)""")) { match ->
        "if (${match.groupValues[1]}${match.groupValues[2]}.isCanceled())"
    }
    return result
}

/** Player heads carry a ResolvableProfile component in 1.21 instead of a SkullOwner tag. */
internal fun StructuralRefactorPass.migrateSkullOwnerProfileWrites(source: String): String {
    if (!source.contains("NbtUtils.writeGameProfile(")) return source
    val pattern = Regex(
        """(?m)^([ \t]*)CompoundTag\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+CompoundTag\(\)\s*;[ \t]*\r?\n[ \t]*NbtUtils\.writeGameProfile\(\s*\2\s*,\s*((?:[^()]|\([^()]*\))+?)\s*\)\s*;[ \t]*\r?\n[ \t]*([A-Za-z_$][\w$]*)[^\r\n;]*?\.put\(\s*"SkullOwner"\s*,\s*\2\s*\)\s*;"""
    )
    val result = pattern.replace(source) { match ->
        "${match.groupValues[1]}${match.groupValues[4]}.set(net.minecraft.core.component.DataComponents.PROFILE, new net.minecraft.world.item.component.ResolvableProfile(${match.groupValues[3].trim()}));"
    }
    return if (result == source) source else removeUnusedSimpleImport(result, "net.minecraft.nbt.NbtUtils", "NbtUtils")
}

/**
 * Curios 9 (NeoForge 1.21) detects items implementing ICurioItem; the Forge-era
 * initCapabilities(...) returning CuriosApi.createCurioProvider(() -> stack) was that default curio.
 */
internal fun StructuralRefactorPass.migrateSupplierCurioProviders(source: String): String {
    if (!source.contains("CuriosApi.createCurioProvider(")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val method = javaMethodRangesIncludingDefault(executable).firstOrNull { method ->
        method.name == "initCapabilities" &&
            Regex("""\{\s*return\s+CuriosApi\.createCurioProvider\(\s*\(\s*\)\s*->\s*[A-Za-z_$][\w$]*\s*\)\s*;\s*}\s*$""")
                .containsMatchIn(executable.substring(method.range))
    } ?: return source
    var result = source.removeRange(method.range)
    result = Regex("""(?m)^(\s*\n)+(?=\s*\n)""").replace(result, "\n")
    val classHeader = Regex("""\bclass\s+([A-Za-z_$][\w$]*)([^{]*)\{""").find(maskJavaCommentsAndLiterals(result)) ?: return source
    val clause = classHeader.groupValues[2]
    val migratedClause = if (clause.contains("implements")) {
        clause.replace(Regex("""\bimplements\s+"""), "implements ICurioItem, ")
    } else {
        "$clause implements ICurioItem ".replace(Regex("""\s+implements"""), " implements")
    }
    result = result.replaceRange(classHeader.groups[2]!!.range, migratedClause.trimEnd() + " ")
    result = addImportIfMissing(result, "top.theillusivec4.curios.api.type.capability.ICurioItem")
    listOf(
        "top.theillusivec4.curios.api.CuriosApi" to "CuriosApi",
        "net.neoforged.neoforge.capabilities.ICapabilityProvider" to "ICapabilityProvider",
        "net.minecraft.nbt.CompoundTag" to "CompoundTag",
        "org.jetbrains.annotations.Nullable" to "Nullable",
        "javax.annotation.Nullable" to "Nullable"
    ).forEach { (import, simple) -> result = removeUnusedSimpleImport(result, import, simple) }
    return result
}

/** RandomizableContainer#setBlockEntityLootTable takes the loot table key in 1.21. */
internal fun StructuralRefactorPass.migrateRandomizableContainerLootTableKeys(source: String): String {
    if (!source.contains("RandomizableContainer.setBlockEntityLootTable(")) return source
    return rewriteExecutableJavaCall(source, "setBlockEntityLootTable") { receiver, args ->
        if (receiver != "RandomizableContainer" || args.size != 4) return@rewriteExecutableJavaCall null
        val table = args[3].trim()
        val locationFactory = Regex("""^(?:net\.minecraft\.resources\.)?ResourceLocation\.(?:fromNamespaceAndPath|parse|tryParse|withDefaultNamespace)\(|^new\s+(?:net\.minecraft\.resources\.)?ResourceLocation\(""")
        val locationVariable = Regex("""^[A-Za-z_$][\w$]*$""").matches(table) &&
            Regex("""\b(?:net\.minecraft\.resources\.)?ResourceLocation\s+${Regex.escape(table)}\b""").containsMatchIn(maskJavaCommentsAndLiterals(source))
        if (!locationFactory.containsMatchIn(table) && !locationVariable) return@rewriteExecutableJavaCall null
        "RandomizableContainer.setBlockEntityLootTable(${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()}, net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, $table))"
    }
}

/** Entity#defineSynchedData receives the SynchedEntityData.Builder in 1.21; entries are defined on it. */
internal fun StructuralRefactorPass.migrateSynchedDataBuilderDefines(source: String): String {
    if (!source.contains("defineSynchedData(")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executable)) {
        if (method.name != "defineSynchedData") continue
        val builder = Regex("""SynchedEntityData\.Builder\s+([A-Za-z_$][\w$]*)""").find(method.header)?.groupValues?.get(1) ?: continue
        val body = executable.substring(method.range)
        Regex("""\b(?:this\.)?(?:entityData|getEntityData\(\))\.define\(""").findAll(body).forEach { match ->
            edits += (method.range.first + match.range.first..method.range.first + match.range.last) to "$builder.define("
        }
    }
    if (edits.isEmpty()) return source
    var result = source
    edits.sortedByDescending { it.first.first }.forEach { (range, text) -> result = result.replaceRange(range, text) }
    return result
}

/** GameTestHelper#makeMockPlayer takes the game type in 1.21 (the 1.20.1 mock was creative). */
internal fun StructuralRefactorPass.migrateGameTestMockPlayers(source: String): String {
    if (!source.contains("makeMockPlayer()") && !source.contains("makeMockSurvivalPlayer()")) return source
    var result = replaceExecutableRegex(source, Regex("""\.makeMockPlayer\(\s*\)""")) { ".makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE)" }
    result = replaceExecutableRegex(result, Regex("""\.makeMockSurvivalPlayer\(\s*\)""")) { ".makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)" }
    return result
}

/** ModelEvent.ModifyBakingResult keys are ModelResourceLocations in 1.21; the resource id is id(). */
internal fun StructuralRefactorPass.migrateModelBakingResultLocations(source: String): String {
    if (!source.contains("ModifyBakingResult") || !source.contains(".replaceAll(")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val keys = Regex("""getModels\(\)\.replaceAll\(\s*\(\s*([A-Za-z_$][\w$]*)\s*,""").findAll(executable).map { it.groupValues[1] }.toSet()
    var result = source
    keys.forEach { key ->
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(key)}\.(getNamespace|getPath)\(\)""")) { "$key.id().${it.groupValues[1]}()" }
    }
    return result
}

/**
 * Block-entity and fluid-tank NBT calls take the registries in 1.21: saveWithoutMetadata, getUpdateTag,
 * handleUpdateTag, loadWithComponents (was load) and FluidTank#readFromNBT/writeToNBT. The access
 * comes from the enclosing method (GameTestHelper, Level, entity) or from a block entity's own level.
 */
internal fun StructuralRefactorPass.migrateRegistryDependentSaveCalls(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String =
    migrateBlockEntitySelfLoads(migrateRegistryDependentSaveCallsPhase(
        migrateRegistryDependentSaveCallsPhase(source, outer = false, javaInheritanceIndex),
        outer = true,
        javaInheritanceIndex
    ))

internal fun StructuralRefactorPass.migrateRegistryDependentSaveCallsPhase(
    source: String,
    outer: Boolean,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    val needles = listOf("saveWithoutMetadata()", "getUpdateTag()", "handleUpdateTag(", ".load(", "writeToNBT(", "readFromNBT(")
    if (needles.none { source.contains(it) }) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val declaredTypes = javaDeclaredSimpleTypeSets(source).toMutableMap()
    // `var x = (Type) ...` and `var x = new Type(...)` declare the type too
    Regex("""\bvar\s+([A-Za-z_$][\w$]*)\s*=\s*(?:\(\s*([A-Za-z_$][\w$.]*)\s*\)|new\s+([A-Za-z_$][\w$.]*)\s*\()""").findAll(executable).forEach { match ->
        val type = match.groupValues[2].ifEmpty { match.groupValues[3] }.substringAfterLast('.')
        declaredTypes[match.groupValues[1]] = declaredTypes[match.groupValues[1]].orEmpty() + type
    }
    val blockEntityClass = Regex("""\bclass\s+[A-Za-z_$][\w$]*(?:<[^>]*>)?\s+extends\s+(?:[\w$]+\.)*[\w$]*(?:BlockEntity|BE)\b""").containsMatchIn(executable)
    fun isBlockEntityType(type: String): Boolean =
        type == "BlockEntity" || type.endsWith("BlockEntity") ||
            javaInheritanceIndex.inherits(type, setOf("BlockEntity", "RandomizableContainerBlockEntity", "BaseContainerBlockEntity"))
    val edits = mutableListOf<Pair<IntRange, String>>()
    val args = """((?:[^()]|\((?:[^()]|\([^()]*\))*\))*)"""
    for (method in javaMethodRangesIncludingDefault(executable)) {
        val body = executable.substring(method.range)
        if (needles.none { body.contains(it) }) continue
        fun access(offset: Int): String? =
            registryAccessExpressionInScope(method.header, body, offset)
                ?: if (blockEntityClass && !Regex("""\bstatic\b""").containsMatchIn(method.header)) "this.level.registryAccess()" else null
        fun add(match: MatchResult, text: String) {
            edits += (method.range.first + match.range.first..method.range.first + match.range.last) to text
        }
        if (!outer) Regex("""\b((?:[A-Za-z_$][\w$]*\.)?)saveWithoutMetadata\(\s*\)""").findAll(body).forEach { match ->
            val reg = access(match.range.first) ?: return@forEach
            add(match, "${match.groupValues[1]}saveWithoutMetadata($reg)")
        }
        if (!outer) Regex("""(?<!super)\b([A-Za-z_$][\w$]*)\.getUpdateTag\(\s*\)""").findAll(body).forEach { match ->
            if (match.groupValues[1] == "super") return@forEach
            val reg = access(match.range.first) ?: return@forEach
            add(match, "${match.groupValues[1]}.getUpdateTag($reg)")
        }
        if (outer) Regex("""\b([A-Za-z_$][\w$]*)\.handleUpdateTag\(\s*$args\s*\)""").findAll(body).forEach { match ->
            if (match.groupValues[2].contains(',')) return@forEach
            val reg = access(match.range.first) ?: return@forEach
            add(match, "${match.groupValues[1]}.handleUpdateTag(${source.substring(method.range.first + match.range.first, method.range.first + match.range.last + 1).let { it.substring(it.indexOf('(') + 1, it.length - 1).trim() }}, $reg)")
        }
        if (outer) Regex("""\b([A-Za-z_$][\w$]*)\.load\(\s*$args\s*\)""").findAll(body).forEach { match ->
            val receiver = match.groupValues[1]
            val escapedReceiver = Regex.escape(receiver)
            val methodTypes = linkedSetOf<String>()
            Regex("""\b(?:var)\s+$escapedReceiver\s*=\s*(?:\(\s*([A-Za-z_$][\w$.]*)\s*\)|new\s+([A-Za-z_$][\w$.]*)\s*\()""").findAll(body).forEach { declaration ->
                methodTypes += declaration.groupValues[1].ifEmpty { declaration.groupValues[2] }.substringAfterLast('.')
            }
            Regex("""\b([A-Z][\w$.]*)(?:<[^>]*>)?\s+$escapedReceiver\s*[=;,)]""").findAll(body).forEach { declaration ->
                methodTypes += declaration.groupValues[1].substringAfterLast('.')
            }
            val types = if (methodTypes.isNotEmpty()) methodTypes else declaredTypes[receiver].orEmpty().takeIf { it.size == 1 }.orEmpty()
            if (types.isEmpty() || types.any { !isBlockEntityType(it) } || match.groupValues[2].contains(',')) return@forEach
            val reg = access(match.range.first) ?: return@forEach
            val raw = source.substring(method.range.first + match.range.first, method.range.first + match.range.last + 1)
            add(match, "$receiver.loadWithComponents(${raw.substring(raw.indexOf('(') + 1, raw.length - 1).trim()}, $reg)")
        }
        if (!outer) Regex("""\b([A-Za-z_$][\w$]*)\.(writeToNBT|readFromNBT)\(\s*$args\s*\)""").findAll(body).forEach { match ->
            val receiver = match.groupValues[1]
            if (declaredTypes[receiver].orEmpty().none { it == "FluidTank" || it.endsWith("FluidTank") } || match.groupValues[3].contains(',')) return@forEach
            val reg = access(match.range.first) ?: return@forEach
            val raw = source.substring(method.range.first + match.range.first, method.range.first + match.range.last + 1)
            add(match, "$receiver.${match.groupValues[2]}($reg, ${raw.substring(raw.indexOf('(') + 1, raw.length - 1).trim()})")
        }
    }
    if (edits.isEmpty()) return source
    var result = source
    edits.sortedByDescending { it.first.first }.forEach { (range, text) -> result = result.replaceRange(range, text) }
    return result
}

/**
 * Forge 1.20.1 patched a `Supplier<BlockState> stateSupplier` into StairBlock for stairs whose base
 * block is registered later; NeoForge 1.21.1's StairBlock only has the vanilla `baseState` set by
 * the constructor, so a reflective write to the supplier has nothing to target. The real port
 * (Create 6, CreateWeatheringCopperStairBlock) keeps the base state it was constructed with and drops the write.
 */
internal fun StructuralRefactorPass.migrateForgeOnlyStairStateSupplierWrites(source: String): String {
    if (!source.contains("\"stateSupplier\"")) return source
    return Regex("""(?m)^[ \t]*ObfuscationReflectionHelper\s*\.\s*setPrivateValue\s*\(\s*(?:[\w.]+\.)?StairBlock\.class\s*,(?:[^;"]|"[^"]*")*?"stateSupplier"\s*\)\s*;[ \t]*\r?\n?""")
        .replace(source, "")
}

/** Block capabilities are queried on the level in 1.21 and return the handler (or null), not a LazyOptional. */
internal fun StructuralRefactorPass.migrateBlockEntityCapabilityLookups(source: String): String {
    if (!source.contains(".getBlockEntity(") || !source.contains(".getCapability(")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val lookup = Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*((?:[A-Za-z_$][\w$]*(?:\(\))?\.)*[A-Za-z_$][\w$]*(?:\(\))?)\.getBlockEntity\(\s*((?:[^(),]|\([^()]*\))+?)\s*\)\.getCapability\(\s*((?:[^(),]|\([^()]*\))+?)\s*\)\s*;""")
    val handlers = linkedMapOf<String, String>()
    var result = replaceExecutableRegex(source, lookup) { match ->
        val query = "${match.groupValues[2]}.getCapability(${match.groupValues[4].trim()}, ${match.groupValues[3].trim()}, null)"
        handlers[match.groupValues[1]] = query
        "${match.groupValues[1]} = $query;"
    }
    handlers.forEach { (handler, query) ->
        val escaped = Regex.escape(handler)
        result = replaceExecutableRegex(result, Regex("""\b$escaped\.resolve\(\)\.orElseThrow\((?:[^()]|\([^()]*\))*\)""")) { "java.util.Objects.requireNonNull($handler)" }
        result = replaceExecutableRegex(result, Regex("""\b$escaped\.orElseThrow\((?:[^()]|\([^()]*\))*\)""")) { "java.util.Objects.requireNonNull($handler)" }
        // isPresent() followed invalidation (block removed, capabilities invalidated); the 1.21 handler
        // object never does, so presence means the level still answers the same query
        result = replaceExecutableRegex(result, Regex("""\b$escaped\.isPresent\(\)""")) { "($query != null)" }
        result = replaceExecutableRegex(result, Regex("""\b$escaped\.orElse\(\s*null\s*\)""")) { handler }
    }
    return result
}

/**
 * Mixin injector handlers mirror the target method's parameters. When the 1.21.1 target (from the
 * real class table) gained parameters after the ones the handler lists, the missing parameters are
 * appended before the callback so the descriptor matches again.
 */
internal fun StructuralRefactorPass.migrateMixinInjectorSignatures(source: String): String {
    if (!source.contains("@Mixin(") || !source.contains("@Inject(")) return source
    val targetName = Regex("""@Mixin\(\s*(?:value\s*=\s*)?\{?\s*([A-Za-z_$][\w$.]*)\.class""").find(source)?.groupValues?.get(1) ?: return source
    val binaryName = mixinTargetBinaryName(source, targetName) ?: return source
    if (!com.modporter.mapping.TargetMembers.hasClass(binaryName)) return source
    val injector = Regex("""@Inject\(\s*method\s*=\s*"([A-Za-z_$][\w$]*)"(?:[^()]|\([^()]*\))*\)\s*(?:(?:public|protected|private|static|final|synchronized)\s+)*void\s+[A-Za-z_$][\w$]*\s*\(([^)]*)\)""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    injector.findAll(source).forEach { match ->
        val descriptors = com.modporter.mapping.TargetMembers.methodDescriptors(binaryName, match.groupValues[1])
        if (descriptors.size != 1) return@forEach
        val targetTypes = jvmDescriptorParameterTypes(descriptors.single())
        val parameters = splitTopLevelJavaArgs(match.groupValues[2]).map { it.trim() }.filter { it.isNotEmpty() }
        val callbackIndex = parameters.indexOfFirst { Regex("""\bCallbackInfo(?:Returnable)?\b""").containsMatchIn(it) }
        if (callbackIndex < 0) return@forEach
        val handlerTypes = parameters.take(callbackIndex).map { parameter ->
            parameter.replace(Regex("""@[\w.]+(?:\([^)]*\))?\s*"""), "").replace(Regex("""\bfinal\s+"""), "").trim().substringBeforeLast(' ').trim()
        }
        if (handlerTypes.size >= targetTypes.size) return@forEach
        val prefixMatches = handlerTypes.indices.all { index ->
            simpleJavaTypeName(handlerTypes[index]) == simpleJavaTypeName(targetTypes[index])
        }
        if (!prefixMatches) return@forEach
        val missing = (handlerTypes.size until targetTypes.size).map { index -> "${targetTypes[index]} modporterArg$index" }
        val rebuilt = (parameters.take(callbackIndex) + missing + parameters.drop(callbackIndex)).joinToString(", ")
        val group = match.groups[2] ?: return@forEach
        edits += group.range to rebuilt
    }
    if (edits.isEmpty()) return source
    var result = source
    edits.sortedByDescending { it.first.first }.forEach { (range, text) -> result = result.replaceRange(range, text) }
    return result
}

internal fun StructuralRefactorPass.mixinTargetBinaryName(source: String, targetName: String): String? {
    if (targetName.contains('.') && targetName.first().isLowerCase()) {
        val packageEnd = targetName.indexOfFirst { it.isUpperCase() }
        if (packageEnd <= 0) return targetName
        return targetName.substring(0, packageEnd) + targetName.substring(packageEnd).replace('.', '$')
    }
    val outer = targetName.substringBefore('.')
    val nested = targetName.substringAfter('.', "")
    val import = Regex("""(?m)^[ \t]*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(outer)})\s*;""").find(source)?.groupValues?.get(1)
        ?: return null
    return if (nested.isEmpty()) import else "$import$" + nested.replace('.', '$')
}

internal fun StructuralRefactorPass.jvmDescriptorParameterTypes(descriptor: String): List<String> {
    val parameters = descriptor.substringAfter('(').substringBefore(')')
    val types = mutableListOf<String>()
    var index = 0
    while (index < parameters.length) {
        var arrays = 0
        while (parameters[index] == '[') { arrays++; index++ }
        val base = when (parameters[index]) {
            'B' -> "byte"; 'C' -> "char"; 'D' -> "double"; 'F' -> "float"; 'I' -> "int"; 'J' -> "long"; 'S' -> "short"; 'Z' -> "boolean"
            'L' -> {
                val end = parameters.indexOf(';', index)
                val name = parameters.substring(index + 1, end).replace('/', '.').replace('$', '.')
                index = end
                name
            }
            else -> "Object"
        }
        index++
        types += base + "[]".repeat(arrays)
    }
    return types
}

/** EntityRenderDispatcher#playerRenderers / getSkinMap() are keyed by PlayerSkin.Model in 1.21, not "default"/"slim". */
internal fun StructuralRefactorPass.migratePlayerRendererMapKeys(source: String): String {
    if (!source.contains("playerRenderers") && !source.contains("getSkinMap()")) return source
    return Regex("""\b(playerRenderers|getSkinMap\(\))\.get\(\s*"(default|slim)"\s*\)""").replace(source) { match ->
        val model = if (match.groupValues[2] == "slim") "SLIM" else "WIDE"
        "${match.groupValues[1]}.get(net.minecraft.client.resources.PlayerSkin.Model.$model)"
    }
}

/** `Sheets.armorTrimsSheet()` takes the trim pattern's decal flag in 1.21, as HumanoidArmorLayer passes it. */
internal fun StructuralRefactorPass.migrateArmorTrimSheetDecal(source: String): String {
    if (!source.contains("Sheets.armorTrimsSheet()")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val trimScopes = Regex("""(?:\bArmorTrim\s+([A-Za-z_$][\w$]*)\b|\.ifPresent\(\s*\(?\s*([A-Za-z_$][\w$]*)\s*\)?\s*->)""")
        .findAll(executable)
        .toList()
    return replaceExecutableRegex(source, Regex("""Sheets\.armorTrimsSheet\(\s*\)""")) { match ->
        val scope = trimScopes.lastOrNull { it.range.first < match.range.first } ?: return@replaceExecutableRegex match.value
        val trim = scope.groupValues[1].ifEmpty { scope.groupValues[2] }
        "Sheets.armorTrimsSheet($trim.pattern().value().decal())"
    }
}

/**
 * Modifiers identified by `UUID.nameUUIDFromBytes(id.toString().getBytes())` are identified by the
 * ResourceLocation itself in 1.21: AttributeInstance#getModifier/hasModifier/removeModifier take it.
 */
internal fun StructuralRefactorPass.migrateNameDerivedAttributeModifierIds(source: String): String {
    val derived = Regex("""(?m)^[ \t]*(?:final\s+)?(?:java\.util\.)?UUID\s+([A-Za-z_$][\w$]*)\s*=\s*UUID\.nameUUIDFromBytes\(\s*([A-Za-z_$][\w$]*)\.toString\(\)\.getBytes\(\)\s*\)\s*;[ \t]*\r?\n?""")
    var result = source
    val executable = maskJavaCommentsAndLiterals(source)
    derived.findAll(executable).toList().asReversed().forEach { declaration ->
        val uuidName = declaration.groupValues[1]
        val idName = declaration.groupValues[2]
        if (!Regex("""\b(?:net\.minecraft\.resources\.)?ResourceLocation\s+${Regex.escape(idName)}\b""").containsMatchIn(executable)) return@forEach
        val escaped = Regex.escape(uuidName)
        var rewritten = replaceExecutableRegex(
            result,
            Regex("""new\s+AttributeModifier\(\s*$escaped\s*,\s*${Regex.escape(idName)}\.toString\(\)\s*,""")
        ) { "new AttributeModifier($idName," }
        rewritten = replaceExecutableRegex(rewritten, Regex("""\.(getModifier|hasModifier|removeModifier)\(\s*$escaped\s*\)""")) {
            ".${it.groupValues[1]}($idName)"
        }
        val remaining = Regex("""(?<![\w$.])$escaped(?![\w$])""").findAll(maskJavaCommentsAndLiterals(rewritten))
            .count { it.range.first < declaration.range.first || it.range.first > declaration.range.last }
        result = if (remaining == 0) rewritten.removeRange(declaration.range) else rewritten
    }
    if (result == source) return source
    return removeUnusedSimpleImport(result, "java.util.UUID", "UUID")
}

/**
 * Curios 9 (NeoForge 1.21) identifies slot/attribute modifiers by ResourceLocation instead of
 * UUID. A literal UUID keeps its uniqueness as the path of a mod-namespaced identifier, and the
 * ICurioItem override takes the ResourceLocation form so Curios actually calls it.
 */
internal fun StructuralRefactorPass.migrateCuriosModifierIdentifiers(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val mainClass = detectModMainClass(projectDir) ?: return emptyList()
    val mainSource = mainClass.readText()
    val modAccess = inferModAccess(mainSource)?.let { access ->
        val mainPackage = Regex("""(?m)^[ \t]*package\s+([\w.]+)\s*;""").find(mainSource)?.groupValues?.get(1)
        val modClass = access.modIdExpression.substringBefore('.')
        if (access.modImport != null || mainPackage == null || mainClass.nameWithoutExtension != modClass) access
        else access.copy(modImport = "$mainPackage.$modClass")
    }
    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir).filter { it.extension == "java" }.forEach { file ->
        val source = file.readText()
        val migrated = migrateCuriosModifierIdentifiers(source, modAccess)
        if (migrated == source) return@forEach
        if (!dryRun) file.writeText(migrated)
        changes.add(Change(
            file = file,
            line = 0,
            description = "Identify Curios slot modifiers by mod-namespaced ResourceLocation instead of UUID",
            before = "CuriosApi.addSlotModifier(..., UUID.fromString(...), ...)",
            after = "CuriosApi.addSlotModifier(..., ResourceLocation.fromNamespaceAndPath(modId, uuid), ...)",
            confidence = Confidence.HIGH,
            ruleId = "struct-curios-modifier-identifiers"
        ))
    }
    return changes
}

internal fun StructuralRefactorPass.migrateCuriosModifierIdentifiers(source: String, modAccess: ModAccess?): String {
    if (!source.contains("CuriosApi.addSlotModifier(") && !source.contains("getAttributeModifiers(SlotContext")) return source
    var result = source
    result = result.replace(
        Regex("""\bgetAttributeModifiers\(\s*SlotContext\s+([A-Za-z_$][\w$]*)\s*,\s*UUID\s+([A-Za-z_$][\w$]*)\s*,\s*ItemStack\s+([A-Za-z_$][\w$]*)\s*\)"""),
        "getAttributeModifiers(SlotContext $1, ResourceLocation $2, ItemStack $3)"
    )
    if (modAccess != null) {
        result = result.replace(
            Regex("""(CuriosApi\.addSlotModifier\((?:[^()]|\([^()]*\))*?,\s*)UUID\.fromString\(\s*"([0-9a-fA-F-]+)"\s*\)""")
        ) { match ->
            "${match.groupValues[1]}ResourceLocation.fromNamespaceAndPath(${modAccess.modIdExpression}, \"${match.groupValues[2].lowercase()}\")"
        }
    }
    if (result == source) return source
    result = addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
    modAccess?.modImport?.let { modImport ->
        val samePackage = Regex("""(?m)^[ \t]*package\s+${Regex.escape(modImport.substringBeforeLast('.'))}\s*;""").containsMatchIn(result)
        if (!samePackage && result.contains(modAccess.modIdExpression)) result = addImportIfMissing(result, modImport)
    }
    return removeUnusedSimpleImport(result, "java.util.UUID", "UUID")
}

/**
 * SimpleChannel.isRemotePresent(Connection) asked whether the peer registered the channel. On
 * NeoForge every payload of the former channel is registered together, so the first payload the
 * generated registrar declares answers the same question through NetworkRegistry.hasChannel.
 */
internal fun StructuralRefactorPass.migrateSimpleChannelPresenceChecks(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir).filter { it.extension == "java" }.toList()
    val presence = Regex("""\b((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)\.isRemotePresent\(\s*((?:[^()]|\([^()]*\))+?)\s*\)""")
    val candidates = javaFiles.filter { presence.containsMatchIn(maskJavaCommentsAndLiterals(it.readText())) }
    if (candidates.isEmpty()) return emptyList()
    val registrars = javaFiles.filter { it.fileName.toString() == "ModNetwork.java" }.mapNotNull { registrar ->
        val text = registrar.readText()
        val first = Regex("""registrar\.(?:playToClient|playToServer|playBidirectional)\(\s*([A-Za-z_$][\w$.]*)\.TYPE""").find(text)
            ?: return@mapNotNull null
        packageNameOf(text) to first.groupValues[1]
    }
    val registrar = registrars.singleOrNull() ?: return emptyList()
    val changes = mutableListOf<Change>()
    candidates.forEach { file ->
        val original = file.readText()
        var text = original
        text = presence.replace(text) { match ->
            val connection = match.groupValues[2].trim().removeSuffix(".connection").removeSuffix(".getConnection()")
            "NetworkRegistry.hasChannel($connection, ${registrar.second}.TYPE.id())"
        }
        if (text == original) return@forEach
        val filePackage = packageNameOf(text)
        if (filePackage != registrar.first) text = addImportIfMissing(text, "${registrar.first}.${registrar.second}")
        text = addImportIfMissing(text, "net.neoforged.neoforge.network.registration.NetworkRegistry")
        if (!dryRun) file.writeText(text)
        changes.add(Change(
            file = file,
            line = 0,
            description = "Replace SimpleChannel.isRemotePresent with NetworkRegistry.hasChannel on the first registered payload",
            before = "CHANNEL.isRemotePresent(connection)",
            after = "NetworkRegistry.hasChannel(listener, ${registrar.second}.TYPE.id())",
            confidence = Confidence.HIGH,
            ruleId = "struct-simplechannel-remote-presence"
        ))
    }
    return changes
}

internal fun StructuralRefactorPass.migrateLegacyHeartTypeSheetCoordinatesSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getX(") || !executableCode.contains(".blit(")) return source
    val heartTypeReceivers = collectHeartTypeReceiverNames(source)
    if (heartTypeReceivers.isEmpty() &&
        !Regex("""(?:net\.minecraft\.client\.gui\.)?Gui\.HeartType\.[A-Z][A-Z0-9_]*\.getX\s*\(""").containsMatchIn(executableCode) &&
        !Regex("""(?<![\w$])HeartType\.[A-Z][A-Z0-9_]*\.getX\s*\(""").containsMatchIn(executableCode)
    ) {
        return source
    }

    var changed = false
    var result = rewriteExecutableJavaInvocationArguments(source, "blit") { args ->
        var argChanged = false
        val migratedArgs = args.map { arg ->
            val replacement = replaceLegacyHeartTypeGetXCalls(arg, heartTypeReceivers)
            if (replacement != arg) argChanged = true
            replacement
        }
        if (argChanged) {
            changed = true
            migratedArgs
        } else {
            null
        }
    }
    if (!changed) return source
    if (!maskJavaCommentsAndLiterals(result).contains("private static int modporterLegacyHeartTypeX(")) {
        result = insertBeforeLastClassBrace(result, legacyHeartTypeXHelperSource())
    }
    return result
}

internal fun StructuralRefactorPass.collectHeartTypeReceiverNames(source: String): Set<String> {
    val names = linkedSetOf<String>()
    val executableCode = maskJavaCommentsAndLiterals(source)
    Regex("""\b(?:net\.minecraft\.client\.gui\.)?Gui\.HeartType\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .forEach { names += it.groupValues[1] }
    if (executableCode.contains("import net.minecraft.client.gui.Gui.HeartType;")) {
        Regex("""(?<![\w$.])HeartType\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableCode)
            .forEach { names += it.groupValues[1] }
    }
    return names
}

internal fun StructuralRefactorPass.replaceLegacyHeartTypeGetXCalls(expression: String, heartTypeReceivers: Set<String>): String {
    var result = expression
    var cursor = 0
    val receiverPattern = Regex(
        """(?<![\w$])((?:net\.minecraft\.client\.gui\.)?Gui\.HeartType\.[A-Z][A-Z0-9_]*|HeartType\.[A-Z][A-Z0-9_]*|[A-Za-z_$][\w$]*)\.getX\s*\("""
    )
    while (true) {
        val executableExpression = maskJavaCommentsAndLiterals(result)
        val match = receiverPattern.find(executableExpression, cursor) ?: break
        val receiver = match.groupValues[1]
        val isKnownHeartType = receiver in heartTypeReceivers ||
            receiver.startsWith("Gui.HeartType.") ||
            receiver.startsWith("net.minecraft.client.gui.Gui.HeartType.") ||
            receiver.startsWith("HeartType.")
        if (!isKnownHeartType) {
            cursor = match.range.last + 1
            continue
        }
        val openParen = executableExpression.indexOf('(', match.range.first)
        val closeParen = if (openParen >= 0) findMatchingParen(executableExpression, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 2) {
            cursor = closeParen + 1
            continue
        }
        val replacement = "modporterLegacyHeartTypeX($receiver, ${args[0].trim()}, ${args[1].trim()})"
        result = result.substring(0, match.range.first) + replacement + result.substring(closeParen + 1)
        cursor = match.range.first + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.legacyHeartTypeXHelperSource(): String = """

	private static int modporterLegacyHeartTypeX(net.minecraft.client.gui.Gui.HeartType heartType, boolean halfHeart, boolean blinking) {
		int index;
		boolean canBlink;
		if (heartType == net.minecraft.client.gui.Gui.HeartType.CONTAINER) {
			index = 0;
			canBlink = false;
		} else if (heartType == net.minecraft.client.gui.Gui.HeartType.NORMAL) {
			index = 2;
			canBlink = true;
		} else if (heartType == net.minecraft.client.gui.Gui.HeartType.POISIONED) {
			index = 4;
			canBlink = true;
		} else if (heartType == net.minecraft.client.gui.Gui.HeartType.WITHERED) {
			index = 6;
			canBlink = true;
		} else if (heartType == net.minecraft.client.gui.Gui.HeartType.ABSORBING) {
			index = 8;
			canBlink = false;
		} else if (heartType == net.minecraft.client.gui.Gui.HeartType.FROZEN) {
			index = 9;
			canBlink = false;
		} else {
			throw new IllegalArgumentException("Unsupported legacy Gui.HeartType sheet coordinates: " + heartType);
		}

		int offset;
		if (heartType == net.minecraft.client.gui.Gui.HeartType.CONTAINER) {
			offset = blinking ? 1 : 0;
		} else {
			offset = halfHeart ? 1 : 0;
			if (canBlink && blinking) {
				offset += 2;
			}
		}
		return 16 + (index * 2 + offset) * 9;
	}
""".trimEnd()

internal fun StructuralRefactorPass.migrateLegacyProjectilePortalBranchSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("handleInsidePortal(") ||
        !executableCode.contains("TheEndGatewayBlockEntity.teleportEntity") ||
        !executableCode.contains("HitResult.Type.BLOCK")) {
        return source
    }

    var result = source
    var cursor = 0
    val blockHitPattern = Regex("""if\s*\(\s*[A-Za-z_$][\w$]*\.getType\(\)\s*==\s*HitResult\.Type\.BLOCK\s*\)\s*\{""")
    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val match = blockHitPattern.find(executableResult, cursor) ?: break
        val openBrace = executableResult.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableResult, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = executableResult.substring(openBrace + 1, closeBrace)
        val isLegacyPortalBranch = body.contains("Blocks.NETHER_PORTAL") &&
            body.contains("handleInsidePortal(") &&
            body.contains("Blocks.END_GATEWAY") &&
            body.contains("TheEndGatewayBlockEntity.canEntityTeleport") &&
            body.contains("TheEndGatewayBlockEntity.teleportEntity")
        if (!isLegacyPortalBranch) {
            cursor = closeBrace + 1
            continue
        }
        val removeEnd = (closeBrace + 1).let { end ->
            var next = end
            while (next < result.length && (result[next] == ' ' || result[next] == '\t')) next++
            if (next < result.length && result[next] == '\r') next++
            if (next < result.length && result[next] == '\n') next + 1 else end
        }
        result = result.substring(0, match.range.first) + result.substring(removeEnd)
        cursor = match.range.first
    }
    if (result == source) return source
    listOf(
        "net.minecraft.core.BlockPos" to "BlockPos",
        "net.minecraft.world.level.block.Blocks" to "Blocks",
        "net.minecraft.world.level.block.entity.BlockEntity" to "BlockEntity",
        "net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity" to "TheEndGatewayBlockEntity",
        "net.minecraft.world.level.block.state.BlockState" to "BlockState",
        "net.minecraft.world.phys.BlockHitResult" to "BlockHitResult"
    ).forEach { (importName, simpleName) ->
        result = removeUnusedSimpleImport(result, importName, simpleName)
    }
    return cleanupRedundantBlankLines(result)
}

internal fun StructuralRefactorPass.migrateColoredCutoutModelCopyLayerRenderSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("coloredCutoutModelCopyLayerRender(")) return source
    var changed = false
    val result = rewriteExecutableJavaInvocationArguments(source, "coloredCutoutModelCopyLayerRender") { args ->
        if (args.size != 16) return@rewriteExecutableJavaInvocationArguments null
        val color = "FastColor.ARGB32.colorFromFloat(1.0F, ${args[13].trim()}, ${args[14].trim()}, ${args[15].trim()})"
        changed = true
        args.take(13) + color
    }
    return if (changed) addExecutableImportIfMissing(result, "net.minecraft.util.FastColor") else source
}

internal fun StructuralRefactorPass.migrateLegacyShearableSignaturesSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("onSheared(") && !executableCode.contains("isShearable(")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val edits = mutableListOf<Pair<IntRange, String>>()
    val methodRanges = javaMethodRanges(executableCode)

    val onShearedPattern = Regex(
        """onSheared\s*\(\s*((?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*)Player\s+($id)\s*,\s*ItemStack\s+($id)\s*,\s*Level\s+($id)\s*,\s*BlockPos\s+($id)\s*,\s*int\s+($id)\s*\)"""
    )
    onShearedPattern.findAll(executableCode).forEach { match ->
        val fortuneName = match.groupValues[6]
        val method = methodRanges.singleOrNull { it.name == "onSheared" && match.range.first in it.range }
            ?: return@forEach
        val methodText = executableCode.substring(method.range)
        val bodyStart = javaMethodOpeningBrace(methodText)
        val bodyUsesFortune = bodyStart != null &&
            Regex("""\b${Regex.escape(fortuneName)}\b""").containsMatchIn(methodText.substring(bodyStart))
        if (!bodyUsesFortune) {
            val annotations = match.groups[1]?.range?.let { source.substring(it) }.orEmpty()
            edits += match.range to
                "onSheared(${annotations}Player ${match.groupValues[2]}, ItemStack ${match.groupValues[3]}, Level ${match.groupValues[4]}, BlockPos ${match.groupValues[5]})"
        }
    }

    val isShearablePattern = Regex(
        """isShearable\s*\(\s*ItemStack\s+($id)\s*,\s*Level\s+($id)\s*,\s*BlockPos\s+($id)\s*\)"""
    )
    isShearablePattern.findAll(executableCode).forEach { match ->
        edits += match.range to
            "isShearable(Player player, ItemStack ${match.groupValues[1]}, Level ${match.groupValues[2]}, BlockPos ${match.groupValues[3]})"
    }

    if (edits.isEmpty()) return source
    var result = applyStringEdits(source, edits)
    if (Regex("""\bisShearable\s*\(\s*Player\s+""").containsMatchIn(result)) {
        result = addImportIfMissing(result, "net.minecraft.world.entity.player.Player")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyIgnoreExplosionSignatureSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ignoreExplosion(")) return source
    val signaturePattern = Regex("""\b(public\s+boolean\s+ignoreExplosion)\s*\(\s*\)""")
    val edits = signaturePattern.findAll(executableCode)
        .map { match ->
            match.range to "${match.groupValues[1]}(Explosion explosion)"
        }
        .toList()
    if (edits.isEmpty()) return source
    val result = applyStringEdits(source, edits)
    return addImportIfMissing(result, "net.minecraft.world.level.Explosion")
}

internal fun StructuralRefactorPass.migrateDimensionSpecialEffectsCloudSignatureSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("renderClouds(")) return source
    val signaturePattern = Regex(
        """renderClouds\s*\(\s*ClientLevel\s+([A-Za-z_$][\w$]*)\s*,\s*int\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*PoseStack\s+([A-Za-z_$][\w$]*)\s*,\s*double\s+([A-Za-z_$][\w$]*)\s*,\s*double\s+([A-Za-z_$][\w$]*)\s*,\s*double\s+([A-Za-z_$][\w$]*)\s*,\s*Matrix4f\s+([A-Za-z_$][\w$]*)\s*\)"""
    )
    val signatureEdits = signaturePattern.findAll(executableCode)
        .map { match ->
            match.range to
                "renderClouds(ClientLevel ${match.groupValues[1]}, int ${match.groupValues[2]}, float ${match.groupValues[3]}, PoseStack ${match.groupValues[4]}, double ${match.groupValues[5]}, double ${match.groupValues[6]}, double ${match.groupValues[7]}, Matrix4f modelViewMatrix, Matrix4f ${match.groupValues[8]})"
        }
        .toList()
    var result = applyStringEdits(source, signatureEdits)
    var changed = signatureEdits.isNotEmpty()
    val resultExecutableCode = if (changed) maskJavaCommentsAndLiterals(result) else executableCode
    val poseEdits = mutableListOf<Pair<IntRange, String>>()
    javaMethodRanges(resultExecutableCode)
        .filter { it.name == "renderClouds" }
        .forEach { method ->
            val executableMethodText = resultExecutableCode.substring(method.range)
            if (!executableMethodText.contains("Matrix4f modelViewMatrix") ||
                executableMethodText.contains(".mulPose(modelViewMatrix);")) {
                return@forEach
            }
            val poseName = Regex("""PoseStack\s+([A-Za-z_$][\w$]*)""")
                .find(executableMethodText)
                ?.groupValues
                ?.get(1)
                ?: return@forEach
            val pushPoseMatch = Regex("""(?m)^([ \t]*)${Regex.escape(poseName)}\.pushPose\(\);""")
                .find(executableMethodText)
                ?: return@forEach
            val methodText = result.substring(method.range)
            val newline = if (methodText.contains("\r\n")) "\r\n" else "\n"
            val pushPoseText = methodText.substring(pushPoseMatch.range)
            val indent = pushPoseMatch.groupValues[1]
            poseEdits += (method.range.first + pushPoseMatch.range.first)..(method.range.first + pushPoseMatch.range.last) to
                "$pushPoseText$newline$indent$poseName.mulPose(modelViewMatrix);"
        }
    if (poseEdits.isNotEmpty()) {
        result = applyStringEdits(result, poseEdits)
        changed = true
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateCustomDataComponentsSource(source: String): String {
    var result = BlockEntityTagComponents.migrate(source)

    var executableCode = maskJavaCommentsAndLiterals(result)
    val stackReceivers = (collectItemStackValueNames(result) + "stack").joinToString("|") { Regex.escape(it) }
    val receiverPattern = """(?<![\w$.])($stackReceivers)"""
    if (Regex("""$receiverPattern\.(?:getTagElement|removeTagKey)\(""").containsMatchIn(executableCode) ||
        executableCode.contains("DataComponents.CUSTOM_DATA") && executableCode.contains(".copyTag().getCompound(")) {
        val legacyCustomFluidTagCopy =
            "CompoundTag tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getCompound(TAG_CUSTOM_FLUID);"
        result = replaceExecutableRegex(result, Regex(Regex.escape(legacyCustomFluidTagCopy))) {
            "CompoundTag tag = getCustomDataChild(stack, TAG_CUSTOM_FLUID);"
        }
        // BlockItem.getBlockEntityData(stack) reads the BLOCK_ENTITY_DATA component in 1.21 (what
        // BlockItem.setBlockEntityData writes); only the other children live in CUSTOM_DATA
        result = Regex("""$receiverPattern\.getTagElement\s*\(\s*"BlockEntityTag"\s*\)""").replace(result) { match ->
            val stack = match.groupValues[1]
            "($stack.has(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA) ? $stack.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA).copyTag() : null)"
        }
        result = Regex("""$receiverPattern\.removeTagKey\s*\(\s*"BlockEntityTag"\s*\)""").replace(result) { match ->
            "${match.groupValues[1]}.remove(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA)"
        }
        result = replaceExecutableRegex(result, Regex("""$receiverPattern\.getTagElement\s*\(""")) { match ->
            "getCustomDataChild(${match.groupValues[1]}, "
        }
        result = replaceExecutableRegex(result, Regex("""$receiverPattern\.removeTagKey\s*\(""")) { match ->
            "removeCustomDataChild(${match.groupValues[1]}, "
        }

        result = replaceExecutableMethodBody(result, "setFluidId", """
                public static void setFluidId(ItemStack stack, ResourceLocation fluidId) {
                    updateCustomDataChild(stack, TAG_CUSTOM_FLUID, tag -> {
                        tag.putString(TAG_FLUID_ID, fluidId.toString());

                        CustomFluidRegistry.getDefinition(fluidId).ifPresent(definition -> {
                            tag.putInt(TAG_FLUID_COLOR, definition.color());
                            tag.putString(TAG_FLUID_NAME, definition.getTranslationKey());
                        });
                    });
                }
            """.trimIndent())

        result = addExecutableImportIfMissing(result, "net.minecraft.core.component.DataComponents")
        result = addExecutableImportIfMissing(result, "net.minecraft.world.item.component.CustomData")
        result = addExecutableImportIfMissing(result, "java.util.function.Consumer")
        result = addItemStackCustomDataHelpers(result)
    }

    executableCode = maskJavaCommentsAndLiterals(result)
    if (executableCode.contains("FluidStack") &&
        (executableCode.contains(".getOrCreateChildTag(") || executableCode.contains(".getChildTag("))) {
        result = replaceExecutableRegex(
            result,
            Regex("""CompoundTag\s+(\w+)\s*=\s*stack\.getChildTag\(([^)]+)\);""")
        ) { match ->
            "CompoundTag ${match.groupValues[1]} = getFluidCustomDataChild(stack, ${match.groupValues[2]});"
        }

        result = replaceExecutableMethodBody(result, "setFluidId", """
                public static void setFluidId(FluidStack stack, ResourceLocation fluidId) {
                    if (stack == null || stack.isEmpty() || fluidId == null || !isDynamicCustomFluid(stack)) {
                        return;
                    }
                    updateFluidCustomDataChild(stack, CustomFluidNBTHelper.TAG_CUSTOM_FLUID, customFluidTag -> {
                        customFluidTag.putString(CustomFluidNBTHelper.TAG_FLUID_ID, fluidId.toString());
                    });
                }
            """.trimIndent())

        result = addExecutableImportIfMissing(result, "net.minecraft.core.component.DataComponents")
        result = addExecutableImportIfMissing(result, "net.minecraft.world.item.component.CustomData")
        result = addExecutableImportIfMissing(result, "java.util.function.Consumer")
        result = addFluidStackCustomDataHelpers(result)
    }

    return result
}

internal fun StructuralRefactorPass.addItemStackCustomDataHelpers(source: String): String {
    if (maskJavaCommentsAndLiterals(source).contains("getCustomDataChild(ItemStack stack")) return source
    val helpers = """

    private static CompoundTag getCustomDataChild(ItemStack stack, String key) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return root.contains(key) ? root.getCompound(key) : null;
    }

    private static void updateCustomDataChild(ItemStack stack, String key, Consumer<CompoundTag> updater) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            CompoundTag child = root.getCompound(key);
            updater.accept(child);
            if (child.isEmpty()) {
                root.remove(key);
            } else {
                root.put(key, child);
            }
        });
    }

    private static void removeCustomDataChild(ItemStack stack, String key) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.remove(key));
    }
""".trimEnd()
    return addExecutableImportIfMissing(insertBeforeLastClassBrace(source, helpers), "net.minecraft.nbt.CompoundTag")
}

internal fun StructuralRefactorPass.addFluidStackCustomDataHelpers(source: String): String {
    if (maskJavaCommentsAndLiterals(source).contains("getFluidCustomDataChild(FluidStack stack")) return source
    val helpers = """

    private static CompoundTag getFluidCustomDataChild(FluidStack stack, String key) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return root.contains(key) ? root.getCompound(key) : null;
    }

    private static void updateFluidCustomDataChild(FluidStack stack, String key, Consumer<CompoundTag> updater) {
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag child = root.getCompound(key);
        updater.accept(child);
        if (child.isEmpty()) {
            root.remove(key);
        } else {
            root.put(key, child);
        }
        if (root.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        }
    }
""".trimEnd()
    return addExecutableImportIfMissing(insertBeforeLastClassBrace(source, helpers), "net.minecraft.nbt.CompoundTag")
}

internal fun StructuralRefactorPass.migrateBrewingRecipeRegistrationSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("BrewingRecipeRegistry.addRecipe(")) return source
    val recipeArguments = collectLegacyBrewingRecipeAddRecipeArguments(source)
    if (recipeArguments.isEmpty()) return source
    var result = source
    result = removeExecutableImport(result, "net.neoforged.neoforge.common.brewing.BrewingRecipeRegistry")
    result = addExecutableImportIfMissing(result, "net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent")

    result = replaceExecutableRegex(result, Regex("""(?m)^[ \t]*registerBrewingRecipes\s*\([^;\r\n]*\)\s*;\s*\r?\n?""")) { "" }
    val migratedRecipes = recipeArguments.joinToString("\n") { args ->
        "        event.getBuilder().addRecipe($args);"
    }
    val migratedMethod = """
            @SubscribeEvent
            public void registerBrewingRecipes(final RegisterBrewingRecipesEvent event) {
$migratedRecipes
            }
        """.trimIndent()
    result = replaceMethodBody(result, "registerBrewingRecipes", migratedMethod)
    result = Regex("""(?s)\n\s*/\*\s*@SubscribeEvent\s+public\s+void\s+onBrewingRecipeRegister.*?\*/""")
        .replace(result, "")
    return result
}

internal fun StructuralRefactorPass.collectLegacyBrewingRecipeAddRecipeArguments(source: String): List<String> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val token = "BrewingRecipeRegistry.addRecipe("
    val arguments = mutableListOf<String>()
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
        arguments += source.substring(openParen + 1, closeParen).trim()
        cursor = closeParen + 1
    }
    return arguments
}

internal fun StructuralRefactorPass.migrateLegacyWalkNodeEvaluatorBlockPathTypeStatic(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getBlockPathTypeStatic(") || !executableCode.contains("WalkNodeEvaluator")) {
        return source
    }

    var changed = false
    var result = rewriteExecutableJavaCallWithOffset(source, "getBlockPathTypeStatic") { receiver, args, _ ->
        val normalizedReceiver = receiver.trim()
        if (args.size != 2 ||
            normalizedReceiver != "WalkNodeEvaluator" &&
            normalizedReceiver != "net.minecraft.world.level.pathfinder.WalkNodeEvaluator") {
            return@rewriteExecutableJavaCallWithOffset null
        }
        changed = true
        "modporterGetLegacyBlockPathTypeStatic(${args[0].trim()}, ${args[1].trim()})"
    }
    if (!changed) return source

    val withoutWalkNodeEvaluatorImport = removeImport(result, "net.minecraft.world.level.pathfinder.WalkNodeEvaluator")
    if (!hasSimpleTypeReference(maskJavaCommentsAndLiterals(withoutWalkNodeEvaluatorImport), "WalkNodeEvaluator")) {
        result = withoutWalkNodeEvaluatorImport
    }
    if (!maskJavaCommentsAndLiterals(result)
            .contains("modporterGetLegacyBlockPathTypeStatic(net.minecraft.world.level.BlockGetter level")) {
        result = insertBeforeLastClassBrace(result, legacyWalkNodeEvaluatorBlockPathTypeStaticHelpers())
    }
    return result
}

internal fun StructuralRefactorPass.legacyWalkNodeEvaluatorBlockPathTypeStaticHelpers(): String = """

    private static net.minecraft.world.level.pathfinder.PathType modporterGetLegacyBlockPathTypeStatic(net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos.MutableBlockPos pos) {
        int i = pos.getX();
        int j = pos.getY();
        int k = pos.getZ();
        net.minecraft.world.level.pathfinder.PathType pathType = modporterGetLegacyPathTypeFromState(level, pos);
        if (pathType == net.minecraft.world.level.pathfinder.PathType.OPEN && j >= level.getMinBuildHeight() + 1) {
            net.minecraft.core.BlockPos.MutableBlockPos below = new net.minecraft.core.BlockPos.MutableBlockPos(i, j - 1, k);
            return switch (modporterGetLegacyPathTypeFromState(level, below)) {
                case OPEN, WATER, LAVA, WALKABLE -> net.minecraft.world.level.pathfinder.PathType.OPEN;
                case DAMAGE_FIRE -> net.minecraft.world.level.pathfinder.PathType.DAMAGE_FIRE;
                case DAMAGE_OTHER -> net.minecraft.world.level.pathfinder.PathType.DAMAGE_OTHER;
                case STICKY_HONEY -> net.minecraft.world.level.pathfinder.PathType.STICKY_HONEY;
                case POWDER_SNOW -> net.minecraft.world.level.pathfinder.PathType.DANGER_POWDER_SNOW;
                case DAMAGE_CAUTIOUS -> net.minecraft.world.level.pathfinder.PathType.DAMAGE_CAUTIOUS;
                case TRAPDOOR -> net.minecraft.world.level.pathfinder.PathType.DANGER_TRAPDOOR;
                default -> modporterCheckLegacyNeighbourBlocks(level, i, j, k, net.minecraft.world.level.pathfinder.PathType.WALKABLE);
            };
        }
        return pathType;
    }

    private static net.minecraft.world.level.pathfinder.PathType modporterCheckLegacyNeighbourBlocks(
        net.minecraft.world.level.BlockGetter level,
        int x,
        int y,
        int z,
        net.minecraft.world.level.pathfinder.PathType pathType
    ) {
        net.minecraft.core.BlockPos.MutableBlockPos cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = -1; k <= 1; k++) {
                    if (i != 0 || k != 0) {
                        cursor.set(x + i, y + j, z + k);
                        net.minecraft.world.level.pathfinder.PathType neighbourType = modporterGetLegacyPathTypeFromState(level, cursor);
                        net.minecraft.world.level.block.state.BlockState blockState = level.getBlockState(cursor);
                        net.minecraft.world.level.pathfinder.PathType blockPathType = blockState.getAdjacentBlockPathType(level, cursor, null, neighbourType);
                        if (blockPathType != null) {
                            return blockPathType;
                        }
                        net.minecraft.world.level.material.FluidState fluidState = blockState.getFluidState();
                        net.minecraft.world.level.pathfinder.PathType fluidPathType = fluidState.getAdjacentBlockPathType(level, cursor, null, neighbourType);
                        if (fluidPathType != null) {
                            return fluidPathType;
                        }
                        if (neighbourType == net.minecraft.world.level.pathfinder.PathType.DAMAGE_OTHER) {
                            return net.minecraft.world.level.pathfinder.PathType.DANGER_OTHER;
                        }
                        if (neighbourType == net.minecraft.world.level.pathfinder.PathType.DAMAGE_FIRE || neighbourType == net.minecraft.world.level.pathfinder.PathType.LAVA) {
                            return net.minecraft.world.level.pathfinder.PathType.DANGER_FIRE;
                        }
                        if (neighbourType == net.minecraft.world.level.pathfinder.PathType.WATER) {
                            return net.minecraft.world.level.pathfinder.PathType.WATER_BORDER;
                        }
                        if (neighbourType == net.minecraft.world.level.pathfinder.PathType.DAMAGE_CAUTIOUS) {
                            return net.minecraft.world.level.pathfinder.PathType.DAMAGE_CAUTIOUS;
                        }
                    }
                }
            }
        }
        return pathType;
    }

    private static net.minecraft.world.level.pathfinder.PathType modporterGetLegacyPathTypeFromState(net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState blockstate = level.getBlockState(pos);
        net.minecraft.world.level.pathfinder.PathType type = blockstate.getBlockPathType(level, pos, null);
        if (type != null) {
            return type;
        }
        net.minecraft.world.level.block.Block block = blockstate.getBlock();
        if (blockstate.isAir()) {
            return net.minecraft.world.level.pathfinder.PathType.OPEN;
        } else if (blockstate.is(net.minecraft.tags.BlockTags.TRAPDOORS) || blockstate.is(net.minecraft.world.level.block.Blocks.LILY_PAD) || blockstate.is(net.minecraft.world.level.block.Blocks.BIG_DRIPLEAF)) {
            return net.minecraft.world.level.pathfinder.PathType.TRAPDOOR;
        } else if (blockstate.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) {
            return net.minecraft.world.level.pathfinder.PathType.POWDER_SNOW;
        } else if (blockstate.is(net.minecraft.world.level.block.Blocks.CACTUS) || blockstate.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
            return net.minecraft.world.level.pathfinder.PathType.DAMAGE_OTHER;
        } else if (blockstate.is(net.minecraft.world.level.block.Blocks.HONEY_BLOCK)) {
            return net.minecraft.world.level.pathfinder.PathType.STICKY_HONEY;
        } else if (blockstate.is(net.minecraft.world.level.block.Blocks.COCOA)) {
            return net.minecraft.world.level.pathfinder.PathType.COCOA;
        } else if (!blockstate.is(net.minecraft.world.level.block.Blocks.WITHER_ROSE) && !blockstate.is(net.minecraft.world.level.block.Blocks.POINTED_DRIPSTONE)) {
            net.minecraft.world.level.material.FluidState fluidstate = blockstate.getFluidState();
            net.minecraft.world.level.pathfinder.PathType nonLoggableFluidPathType = fluidstate.getBlockPathType(level, pos, null, false);
            if (nonLoggableFluidPathType != null) {
                return nonLoggableFluidPathType;
            }
            if (fluidstate.is(net.minecraft.tags.FluidTags.LAVA)) {
                return net.minecraft.world.level.pathfinder.PathType.LAVA;
            } else if (net.minecraft.world.level.pathfinder.NodeEvaluator.isBurningBlock(blockstate)) {
                return net.minecraft.world.level.pathfinder.PathType.DAMAGE_FIRE;
            } else if (block instanceof net.minecraft.world.level.block.DoorBlock doorblock) {
                if (blockstate.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) {
                    return net.minecraft.world.level.pathfinder.PathType.DOOR_OPEN;
                }
                return doorblock.type().canOpenByHand()
                    ? net.minecraft.world.level.pathfinder.PathType.DOOR_WOOD_CLOSED
                    : net.minecraft.world.level.pathfinder.PathType.DOOR_IRON_CLOSED;
            } else if (block instanceof net.minecraft.world.level.block.BaseRailBlock) {
                return net.minecraft.world.level.pathfinder.PathType.RAIL;
            } else if (block instanceof net.minecraft.world.level.block.LeavesBlock) {
                return net.minecraft.world.level.pathfinder.PathType.LEAVES;
            } else if (!blockstate.is(net.minecraft.tags.BlockTags.FENCES)
                && !blockstate.is(net.minecraft.tags.BlockTags.WALLS)
                && (!(block instanceof net.minecraft.world.level.block.FenceGateBlock) || blockstate.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN))) {
                if (!blockstate.isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType.LAND)) {
                    return net.minecraft.world.level.pathfinder.PathType.BLOCKED;
                }
                net.minecraft.world.level.pathfinder.PathType loggableFluidPathType = fluidstate.getBlockPathType(level, pos, null, true);
                if (loggableFluidPathType != null) {
                    return loggableFluidPathType;
                }
                return fluidstate.is(net.minecraft.tags.FluidTags.WATER)
                    ? net.minecraft.world.level.pathfinder.PathType.WATER
                    : net.minecraft.world.level.pathfinder.PathType.OPEN;
            }
            return net.minecraft.world.level.pathfinder.PathType.FENCE;
        }
        return net.minecraft.world.level.pathfinder.PathType.DAMAGE_CAUTIOUS;
    }
""".trimIndent()

internal fun StructuralRefactorPass.migrateVanilla121ApiSource(
    source: String,
    mapCodecConstantOwners: Set<String> = emptySet(),
    mapCodecRequiredOwners: Set<String> = emptySet(),
    codecConstantOwners: Set<String> = emptySet(),
    spriteSourceProviderClasses: Set<String> = emptySet(),
    finalMapDecorationClasses: Set<String> = emptySet(),
    lootTableProviderClasses: Set<String> = emptySet(),
    lootTableProviderFactoryMethods: Map<String, Set<String>> = emptyMap(),
    globalLootModifierProviderClasses: Set<String> = emptySet(),
    recipeProviderClasses: RecipeProviderClassHints = RecipeProviderClassHints(emptySet(), emptySet()),
    recipeSerializerFactoryHints: RecipeSerializerFactoryHints = RecipeSerializerFactoryHints(emptyMap(), emptyMap()),
    recipeTypeHints: RecipeTypeHints = RecipeTypeHints(emptyMap()),
    holderValueParameterHints: HolderValueParameterHints = HolderValueParameterHints(emptyMap(), emptyMap(), emptyMap(), emptySet()),
    criterionInstanceFactoryHints: CriterionInstanceFactoryHints = CriterionInstanceFactoryHints(emptyMap()),
    staticRegistryFieldHints: StaticRegistryFieldHints = StaticRegistryFieldHints(emptySet(), emptySet()),
    legacyRegistryBackedMethods: Set<LegacyRegistryBackedMethod> = emptySet(),
    attributeModifierIdReferences: Set<String> = emptySet(),
    attributeModifierMethods: Map<String, Set<String>> = emptyMap(),
    genericMethodReturnTypes: Map<String, String> = emptyMap(),
    javaMethodReturnTypes: Map<String, String> = emptyMap(),
    attributeModifierIdMethodArguments: Set<AttributeModifierIdMethodArgument> = emptySet(),
    attributeHolderAccessHints: AttributeHolderAccessHints = AttributeHolderAccessHints(emptySet()),
    optionalCompoundRecipeTagOwners: Set<String> = emptySet(),
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY,
    recipeImplementationClasses: Set<String> = emptySet(),
    legacyPlacementBanBaseClasses: Set<String> = emptySet(),
    legacyPlacementBanBuilderClasses: Set<String> = emptySet(),
    legacyPlacementBanDisplayClasses: Set<String> = emptySet(),
    modId: String = "",
    generatedCompatPackage: () -> String = { "" },
    savedDataClassNames: Set<String> = emptySet(),
    legacyLootTableResourceLocationReferences: Set<String> = emptySet(),
    attributeModifierNamespaceExpression: String? = null,
    itemStackNbtMethods: Set<HolderLookupNbtMethod> = emptySet(),
    itemStackNbtConstructors: Set<HolderLookupNbtConstructor> = emptySet(),
    mobEffectDeferredHolderFields: Set<String> = emptySet(),
    entityCapabilityTypes: Map<String, String> = emptyMap(),
    projectModIdExpression: String? = null
): String {
    var result = source
    var needsEntityTypeTags = false
    var needsHolder = false
    var needsColorParticle = false
    var needsFastColor = false
    var needsPlayerSkin = false
    var needsItemCost = false
    var needsDataComponents = false
    var needsPotionContents = false
    var needsItemTooltipContext = false
    var needsOverlayTexture = false
    var needsRandomSource = false
    var needsServerLevel = false
    var needsVanillaEnchantmentProviders = false
    var needsEnchantmentEffectComponents = false
    var needsDatagenSupplier = false
    var needsDatagenBlockHelpers = false
    var needsRegistryFriendlyByteBuf = false
    var needsEntityWithComplexSpawn = false

    result = replaceExecutableRegex(result, Regex("""\.getBlockReach\(\)""")) { ".blockInteractionRange()" }
    result = replaceExecutableRegex(result, Regex("""\.getEntityReach\(\)""")) { ".entityInteractionRange()" }
    result = replaceExecutableRegex(result, Regex("""\.isAddedToWorld\(\)""")) { ".isAddedToLevel()" }
    if (result.contains("NeoForgeMod.")) {
        result = replaceExecutableRegex(
            result,
            Regex("""\bNeoForgeMod\.([A-Z][A-Z0-9_]*)\.get\(\)""")
        ) { match ->
            "NeoForgeMod.${match.groupValues[1]}"
        }
    }
    result = rewriteExecutableJavaInvocationArguments(result, "EventHooks.canCreateFluidSource") { args ->
        if (args.size == 4 && args[3].trim() == "true") args.take(3) else null
    }
    result = replaceExecutableRegex(result, Regex("""\bResourceLocation\.CODEC\.codec\(\)\.""")) { "ResourceLocation.CODEC." }
    result = migrateLegacyResourceLocationValidationSource(result)
    result = migrateLegacyResourceLocationConstructorsSource(result)
    result = replaceExecutableRegex(result, Regex("""\.mulPoseMatrix\(""")) { ".mulPose(" }
    result = replaceExecutableRegex(result, Regex("""\bTags\.Items\.TOOLS_BOWS\b""")) { "Tags.Items.TOOLS_BOW" }
    result = replaceExecutableRegex(result, Regex("""\bTags\.Items\.TOOLS_CROSSBOWS\b""")) { "Tags.Items.TOOLS_CROSSBOW" }
    result = replaceExecutableRegex(result, Regex("""\bTags\.Items\.TOOLS_FISHING_RODS\b""")) { "Tags.Items.TOOLS_FISHING_ROD" }
    result = replaceExecutableRegex(result, Regex("""\bEquipmentSlot\.Type\.ARMOR\b""")) { "EquipmentSlot.Type.HUMANOID_ARMOR" }
    result = replaceExecutableRegex(result, Regex("""\b(CauldronInteraction\.(?:EMPTY|WATER|LAVA|POWDER_SNOW))\.put\(""")) { match ->
        "${match.groupValues[1]}.map().put("
    }
    result = migrateLegacyCauldronInteractionResults(result)
    result = replaceExecutableRegex(result, Regex("""\.setTame\(\s*true\s*\);""")) { ".setTame(true, true);" }
    result = replaceExecutableRegex(result, Regex("""\.disableShield\(\s*(?:true|false)\s*\)""")) { ".disableShield()" }
    result = replaceExecutableRegex(result, Regex("""(?<![\w$])Items\.GRASS\b""")) { "Items.SHORT_GRASS" }
    result = replaceExecutableRegex(result, Regex("""\.defaultDurability\(""")) { ".durability(" }
    val executableEquipmentSlotSource = maskJavaCommentsAndLiterals(result)
    if (executableEquipmentSlotSource.contains("Mob.getEquipmentSlotForItem(") && executableEquipmentSlotSource.contains("extends ")) {
        result = replaceExecutableRegex(result, Regex("""\bMob\.getEquipmentSlotForItem\(""")) { "this.getEquipmentSlotForItem(" }
    }
    result = migrateEntityDimensionsRecordAccessors(result)
    result = migrateFinalLivingEntityDimensionsOverrides(result, javaInheritanceIndex)
    result = migrateLivingEntityDeathLootOverrides(result, javaInheritanceIndex)
    result = LevelSubclassOverrides.migrate(result) { javaInheritanceIndex.inherits(it, setOf("Level", "ServerLevel", "ClientLevel")) }
    result = migrateForwardingWorldContracts(result)
    result = migrateSaddleableEquipSaddleSignature(result)
    result = migrateMthTrigonometryFloatArguments(result)
    result = migrateAdvancementRequirementsStrategySource(result)
    result = migrateLegacyAdvancementHolderApis(result)
    result = migrateHolderReferenceValueApis(result)
    result = migrateLegacyRecipeContainerWildcards(result)
    result = migrateLegacyAdvancementDatagenSource(result, criterionInstanceFactoryHints)
    result = migrateLegacyCriterionTriggerInstanceCallSites(result, criterionInstanceFactoryHints)
    result = migrateLegacyDatagenTagConstantsSource(result)
    result = migrateCreateMixinSignatureFamilies(result)
    result = migrateLegacyComponentSerializationSource(result, javaInheritanceIndex)
    result = migrateLegacyNitrogenLanguageHelpersSource(result)
    result = migrateMixinLegacyCapabilityProviderSuperclass(result)
    result = cleanupMixinLegacyCapabilityProviderSuperclass(result)
    result = migrateLegacyArmorMaterialsHolderSource(result)
    result = migrateTextColorParseColorLiteralSource(result)
    result = migrateGameProfileDisplayNameComponents(result)
    result = migrateEntityEffectColorParticles(result)
    result = migrateRedundantFloatVariableCasts(result)
    result = migrateLegacyMobEffectApplyTickReturns(result)
    result = migrateMobEffectInstanceMobEffectVariables(result)
    result = migrateLegacyMobEffectHolderCategoryCalls(result)
    result = migrateLegacyMobEffectRemoveAttributeModifierOverrides(result)
    result = migrateItemStackTagConstructors(result)
    result = migrateLegacyClientGuiUtilityApis(result)
    result = migrateLegacyOptionsScreenMixinInitLocalCapture(result)
    result = migrateGeometryLoaderResourceLocationKeys(result)
    result = migrateProjectItemStackNbtMethods(result, itemStackNbtMethods, javaInheritanceIndex, entityCapabilityTypes, javaMethodReturnTypes)
    result = migrateProjectItemStackNbtConstructors(result, itemStackNbtConstructors, itemStackNbtMethods, javaInheritanceIndex)
    result = migrateProjectItemStackNbtMethods(result, itemStackNbtMethods, javaInheritanceIndex, entityCapabilityTypes, javaMethodReturnTypes)
    result = LibraryProviderParameters.migrateCalls(result) { text, offset ->
        holderLookupProviderNamesAt(text, offset).singleOrNull() ?: registryAccessExpressionAt(text, offset, javaInheritanceIndex)
    }
    result = migrateLegacyWalkNodeEvaluatorBlockPathTypeStatic(result)
    result = migrateLegacyItemStackRegistrySerialization(result, javaInheritanceIndex, javaMethodReturnTypes)
    result = migrateFluidTankNbtProviderCalls(result, javaInheritanceIndex)
    result = migrateLegacyFriendlyByteBufNbtCodecReads(result)
    result = migrateLegacyIdMapBufferCalls(result)
    result = migrateFluidStackNbtProviderCalls(result, javaInheritanceIndex)
    result = FluidStackTags.migrate(result)
    result = FluidStackBufferCalls.migrate(result)
    result = migrateRegistryFriendlyStreamCodecReferences(result)
    result = migrateLegacyFluidStackDeprecations(result)
    result = migrateXlintCleanApiDeprecations(result, mobEffectDeferredHolderFields, javaInheritanceIndex)
    result = migrateNestedHolderLookupProviderAccess(result, javaInheritanceIndex)
    result = migrateRegistryAccessEmptyFallbacks(result, javaInheritanceIndex)
    result = migrateLegacyGameEventListenerSource(result)
    result = migrateLegacyLivingChangeTargetEventSource(result)
    result = migrateFriendlyByteBufReadAnySizeNbtSource(result)
    result = migrateLegacyNetworkBufferCodecsSource(result)
    result = migrateHolderValueMethodArgumentSource(result, holderValueParameterHints)
    result = migrateSynchedEntityDataBufferSource(result)
    result = migrateMapDecorationRecordSource(result)
    result = migrateLegacyMapItemApis(result)
    result = migrateLegacyBlockEntityRenderBoundingBoxOverride(result, javaInheritanceIndex)
    result = migrateLegacyBlockStatePropertyGetCalls(result)
    result = migrateJavaOptionalGetValueCalls(result)
    result = migrateLegacyModelRenderPackedColorBodies(result)
    result = migrateLegacyRendererSetupRotations(result)
    result = migrateAttributeHolderApiArguments(result, attributeHolderAccessHints)
    result = migrateLegacyAttributeModifierSnapshots(result)
    result = migrateItemStackAttributeModifierAccess(result)
    result = migrateLegacyServerDataConstructors(result)
    result = migrateLegacyConnectScreenStartConnectingCalls(result)
    result = migrateLegacyClientPacketListenerDisconnectMixinSource(result)
    result = migrateLegacySingleplayerWorldFlowSource(result)
    val bindingCurseMigrated = migrateLegacyBindingCurseChecks(result)
    if (bindingCurseMigrated != result) {
        result = bindingCurseMigrated
        needsEnchantmentEffectComponents = true
    }
    val vanishingCurseMigrated = migrateLegacyVanishingCurseChecks(result)
    if (vanishingCurseMigrated != result) {
        result = vanishingCurseMigrated
        needsEnchantmentEffectComponents = true
    }
    result = migrateRecipeBookGhostRecipeSource(result)
    result = migrateOptionalCompoundRecipeTagAccess(result, optionalCompoundRecipeTagOwners)
    result = migrateLegacyClientRenderingSource(result)
    result = migrateLegacyClientApiResidualsSource(result)
    result = migrateLegacyClientRenderSoundCalls(result)
    result = migrateLevelRendererCloudTesselatorSource(result)
    result = migrateLegacySkinManagerTextureLookups(result)
    result = migrateLegacyModelEventSource(result)
    result = migrateLegacyGuiLayerRegistrationSource(result)
    result = migrateRenderGuiLayerEventOverlayApis(result)
    result = migrateRenderGuiLayerEventWindowAccess(result)
    result = migrateLegacyGuiSurvivalElementsSource(result)
    result = migrateLegacyNeoForgeModelApiSource(result)
    result = migrateLegacyEventBusPostBooleans(result)
    result = migrateLegacyForgeBusBindingCalls(result)
    result = migrateLegacyFollowOwnerGoalConstructors(result)
    result = migrateLegacyItemStackHurtSource(result)
    result = migrateLegacyItemOnBlockStartBreakOverrides(result)
    result = migrateLivingEntityGetSlotForHandEquipmentSlotSource(result)
    result = migrateLegacyItemHandlerHelperStackComparisons(result)
    result = migrateLegacyTooltipPartHiding(result)
    result = migrateLegacyBlockSourceCoordinateAccessors(result)
    result = migrateLegacyProjectileDispenseBehaviorSource(result)
    result = migrateLegacyAbstractArrowPickupItemSource(result)
    result = migrateLegacyAbstractArrowConstructors(result, javaInheritanceIndex)
    result = migrateLegacyAbstractArrowKnockbackSource(result, javaInheritanceIndex)
    result = migrateLegacyEntityArmorSlotAccessSource(result)
    result = migrateLegacyAbstractHurtingProjectilePowerFields(result)
    result = migrateEntityEffectColorParticleTrailCalls(result)
    result = migrateLegacyMaxUpStepSetterSource(result)
    result = migrateLegacyDyeColorFloatArrays(result)
    result = migrateLegacyModelRenderPackedColorBodies(result)
    result = migrateLegacyMobExperienceOverrides(result)
    result = migrateLegacyWolfModelLayerDefinitions(result)
    result = migrateLegacyEmptyUnsidedItemHandlerOverrides(result)
    result = migrateLegacyHangingEntityBoundingBoxes(result)
    result = migrateLegacyJumpFromGroundVisibility(result)
    result = migrateLegacyToastTextureRendering(result)
    result = migrateHurtAndBreakEquipmentSlotSource(result)
    result = migrateLivingEntityGetSlotForHandEquipmentSlotSource(result)
    result = migrateSingleStackRecipeInputs(result)
    result = migrateLegacyAbstractFurnaceCanBurnInvokerBoundary(result)
    result = migrateSingleStackRecipeImplementations(result)
    result = migrateRecipeBookMenuSingleStackInputs(result, recipeTypeHints)
    result = migrateProtectedBlockFluidStateAccess(result)
    result = migrateEntityEffectParticleOptions(result)
    result = migrateSkullOwnerResolvableProfileSource(result)
    result = migrateResolvableProfileGameProfileBoundaries(result)
    result = migrateLegacySkullOwnerVerifyComponentsSource(result)
    result = migrateLegacyItemStackTagReads(result)
    result = migrateLegacyItemStackTagWrites(result)
    result = migrateLegacyItemStackTagReads(result)
    result = migrateLegacyArmorColorHookOverrides(result)
    result = migrateLegacyItemEnchantmentComponents(result)
    result = migrateLegacyItemMaxDamageCalls(result)
    result = migrateDeferredSpawnEggLookups(result)
    result = migrateLegacyEntityTypeSpawnCompoundTagSource(result)
    result = migrateEnchantmentLevelHolderLookups(result, javaInheritanceIndex)
    result = migrateLegacySweepingDamageRatioCalls(result)
    result = migrateLegacyEnchantmentHelperCalls(result, javaInheritanceIndex)
    result = migrateLegacyEnchantmentTagChecks(result)
    result = migrateLegacyDamageBonusMobTypeCalls(result)
    result = migrateLegacyMobTypeTagChecks(result)
    result = migrateRemoved121SourceSymbols(result)
    result = migrateLegacyMobCustomDamageSourceAttacks(result)
    result = migrateLegacyMobCustomDamageSourceUtilityAttacks(result)
    result = migrateLegacyEntityEnchantmentHelperCalls(result)
    result = migrateLegacyTridentAndFishingEnchantmentHelpers(result)
    result = migrateLegacyStartAutoSpinAttackCalls(result)
    result = migrateMobSpawnEquipmentSignatures(result)
    result = migrateLegacyEnchantmentConstantNames(result)
    result = migrateLegacyAttachmentGetDataLazyOptionalReturns(result)
    result = migrateAttachmentGetDataIfPresentSource(result)
    result = migrateAttachmentGetDataResolveOptionalBranches(result)
    result = migrateLegacyEntityCapabilityOptionalChains(result)
    result = migratePlayerCloneCapabilityLifecycleSource(result)
    result = migrateLegacyAuthlibProfileFetchSource(result)
    result = migrateLegacyChunkGeneratorAsyncSignatures(result)
    result = migrateLegacyBlockStateSurvivalAndPlantCalls(result)
    result = migrateLegacyCropBlockPlantOverrides(result, javaInheritanceIndex)
    result = migrateLegacyPaintingVariantAccessors(result)
    result = migrateLegacyPaintingVariantRegistrySource(result, legacyRegistryBackedMethods)
    result = migrateLegacyHolderMethodReferences(result)
    result = migrateLegacyHolderValueCalls(result)
    result = migrateLegacyHolderReferenceBindKeyCalls(result)
    result = migrateBuiltInRegistryEntrySetSource(result)
    result = migrateBuiltInRegistryTagRandomElementSource(result)
    result = migrateLegacyVillagerDataAccessors(result)
    result = migrateLegacyItemConstants(result)
    result = migrateLegacyOptionalValueCalls(result)
    result = migrateNeoForgeFluidTypeValueContexts(result)
    result = migrateLegacyBiomeSourceCodecReturnSource(result)
    result = migrateAttributeModifierResourceLocationIds(
        result,
        attributeModifierIdReferences,
        attributeModifierMethods,
        genericMethodReturnTypes,
        attributeModifierIdMethodArguments,
        declaredModClassIdExpression = attributeModifierNamespaceExpression
    )
    result = migrateAttributeModifierMultimapHolderTypes(result)
    result = migrateLegacyItemAttributeModifierOverrides(result)
    result = migrateEntityRidingOffsetExpressions(result, javaInheritanceIndex)
    result = migrateLegacyPassengerRidingOffsetCallSites(result, javaInheritanceIndex)
    result = migrateLegacyPassengerAttachmentOverrides(result)
    result = migrateLegacyEntityOverrideSignatures(result, javaInheritanceIndex)
    result = migrateLegacyEntityStepHeightOverrides(result)
    result = migrateLossyCompoundAssignments(result)
    result = migrateLegacyEntityTypeAabbCalls(result)
    result = migrateLegacyBlockValidSpawnOverrides(result)
    result = migrateLegacyBlockAndEntityCapabilityAccessors(result, javaInheritanceIndex, generatedCompatPackage, entityCapabilityTypes)
    result = migrateLegacyEntityCapabilityOptionalChains(result)
    result = migrateJavaOptionalGetValueCalls(result)
    result = migrateLegacyRespawnPositionTransitions(result)
    result = migrateLegacyITeleporterDimensionTransitions(result)
    result = migrateLegacyMinecraftServerNetherGate(result)
    result = migrateLegacyCanChangeDimensionsCallSites(result)
    result = migrateLegacyPortalWaitTimeCalls(result)
    result = migrateLegacyEntityPortalProcessorAccessors(result)
    result = migrateLegacyCustomPortalBlockProtocol(result)
    result = migrateLegacyEntityPortalProcessorCallSites(result)
    result = migrateLegacyConcretePowderConcreteAccessor(result)
    result = migrateLegacyExplosionPositionCalls(result)
    result = migrateAbstractExplosionEventSubscribers(result)
    result = migrateCandleCakeByCandlePatternVariables(result)
    result = migrateLegacyAnimalFoodPredicateOverrides(result)
    result = migrateLegacyMushroomBlockConstructorOrder(result)
    result = migrateLegacyVanillaBlockConstructors121(result)
    result = migrateVanillaConstructorMoves(result)
    result = migrateBlockSetTypeConstructors(result)
    result = migrateEditBoxCursorCalls(result, javaInheritanceIndex)
    result = migrateMobEffectValueCalls(result)
    result = migrateRegistrateDataIngredients(result)
    result = migrateLegacyHelmetOverlayRendering(result)
    result = migrateFlywheelBakedModelBuilders(result)
    result = migrateSingleSlotRecipeHelpers(result)
    result = migrateConditionSerialization(result)
    result = migrateLegacyMobEffectCodecs(result)
    result = migrateEntityNbtProviders(result)
    result = migrateMobEffectModifierIteration(result)
    result = migrateLegacyRegistryAliases(result)
    result = migrateFinalizedVanillaHooks(result, javaInheritanceIndex)
    result = migrateGuiScrollSignatures(result, javaInheritanceIndex)
    result = migrateLegacyGameEventConstructors(result)
    result = migrateLegacyNbtUtilsBlockPosCompoundSource(result)
    result = migrateLegacySavedDataApis(result, savedDataClassNames, javaInheritanceIndex)
    result = migrateDefaultLootTableResourceKeys(result)
    result = migrateContainerEntityLootTableResourceKeys(result)
    result = migrateRandomizableContainerLootTableCalls(result, javaInheritanceIndex)
    result = migrateStructureStartLoadFromTagCalls(result)
    result = migrateLegacyLootTableKeyFields(result)
    result = simplifyLootTableResourceKeyCreates(result)
    result = migrateLargeFireballVec3Constructors(result)
    result = migrateLegacySignBlockConstructors(result)
    result = migrateLegacyBannerPatternConstructors(result)
    result = migrateLegacyWallSignBlockCodecSource(result)
    result = migrateLegacyPacketDistributorSends(result)
    result = migrateRequiredMapCodecConstants(result, mapCodecRequiredOwners)
    result = migrateBlockCodecReturnToConcreteOwner(result, javaInheritanceIndex)
    val effectiveMapCodecConstantOwners = mapCodecConstantOwners + collectMapCodecConstantOwnersFromSource(result)
    result = migrateRegistryFileCodecMapCodecSource(result, effectiveMapCodecConstantOwners)
    result = migrateLegacyFunctionalInterfaceMapCodecSource(result)
    result = migrateMapCodecRegistryFactorySignatures(result)
    result = migrateBuiltInPlacementModifierTypeRegistrations(result)
    result = migrateLegacyEventHooks121(result)
    result = migrateLegacyLootAndRegistryAccess(result, legacyLootTableResourceLocationReferences)
    result = migrateRegistrySetBuilderBuildPatchSource(result)
    result = migrateLegacyLootTableKeyFields(result)
    result = simplifyLootTableResourceKeyCreates(result)
    result = migrateLegacyDataAndComponentAccess(result)
    result = migrateNewItemStackComponentSetChains(result)
    result = migrateBlockEntitySaveToItemCalls(result)
    result = migrateBlockEntitySaveWithFullMetadataCalls(result)
    result = migrateLegacyHolderSoundEventPlaySoundArguments(result)
    result = migrateLegacyHolderSoundEventAssignments(result)
    result = migrateLegacySpawnEggItemTypeCalls(result)
    result = migrateLegacyFillBucketEventSource(result)
    result = migrateLegacyBucketPickupAndLiquidContainerCalls(result)
    result = migrateLegacyTriStateEventResults(result)
    result = migrateRightClickBlockTriStateResults(result)
    result = migrateLegacyCancellableEventBaseClasses(result)
    result = migrateLegacySleepingTimeCheckEventSource(result)
    result = migrateLegacyCancellableEventHelperParameters(result)
    result = migrateLegacyCancellableEventHelperCallSites(result)
    result = splitLegacyAbstractPlayerInteractSubscribers(result)
    result = migrateAbstractRenderLivingSubscribers(result)
    result = migrateStringRepresentableEnumCodecs(result)
    result = migrateLegacyMapCodecOptionalFields(result)
    result = migrateLegacyRegistryBuilderAndRegistryAccessors(result, staticRegistryFieldHints)
    result = migrateLegacyLazyRegistryCodecs(result)
    val effectiveCodecConstantOwners = codecConstantOwners + collectCodecConstantOwnersFromSource(result)
    result = migrateKnownVanillaCodecCodecCalls(result, effectiveMapCodecConstantOwners, effectiveCodecConstantOwners)
    result = migrateMapCodecSerializationCalls(result, effectiveMapCodecConstantOwners)
    result = migrateLegacyDataResultGetOrThrowCalls(result)
    result = migrateMapCodecFeatureConstructorArguments(result, effectiveMapCodecConstantOwners)
    result = migrateLegacyDeferredHolderDelegateAccessors(result)
    result = migrateLegacyCreativeTabEnchantmentInstances(result)
    result = migrateLegacyHolderAccessors(result)
    result = migrateLegacyItemConstructorsAndProperties(result)
    result = migrateLegacyTierLevelSource(result)
    result = migrateLegacyTierClassLevelSource(result)
    result = migrateLegacySimpleTierConstructors(result)
    result = migrateSupplierIngredientCallArguments(result)
    result = migrateArmorMaterialHolderFields(result)
    result = migrateArmorMaterialHolderNameAccess(result)
    result = migrateLegacyCustomRecipeSource(result)
    result = migrateContainerSizeCallsForDeclaredContainerScopes(result)
    result = migrateDeclaredCollectionGetSizeCalls(result)
    result = migrateLegacyMenuScreensRegistration(result)
    result = migrateLegacyEnchantmentIterationSource(result)
    result = migrateLambdaCapturedMutableItemStackLocals(result)
    result = migrateScreenRenderBackgroundCallSites(result)
    result = migrateItemCanBeDepletedOverrides(result)
    result = migrateAdvancementHolderIteration(result)
    result = migrateRegistryAccessDependentCalls(result)
    result = migrateConfigScreenFactoryRegistrationExact(result)
    result = migrateModLoadingContextConfigCalls(result)
    result = migrateForgeOnlyStairStateSupplierWrites(result)
    result = LibraryTypeArguments.migrate(result, projectRegistryElements)
    result = removeUnreadInteractionCancellationResults(result)
    result = EntityDataSerializerCodecs.migrate(result)
    result = EntityIgniteOverrides.migrate(result)
    result = LegacyFactoryReferences.migrate(result)
    result = migrateCodecApiMoves(result)
    result = TagFieldRenames.migrate(result)
    result = ForgeNonNullFunctionals.migrate(result)
    result = LibraryMemberRelocations.migrate(result)
    result = LibraryClassRelocations.migrate(result)
    result = CancelableEventOverrides.migrate(result)
    result = DisplayTestRegistrations.migrate(result)
    result = migrateServerNextTickTime(result)
    result = migrateBlockEntityDataPacketTagLambdas(result)
    result = ForgeRegistryTagStreams.migrate(result)
    result = migrateLevelDataSpawnCoordinates(result)
    result = migrateChunkFutureResults(result)
    result = migrateServerOnlyTickListeners(result)
    result = migrateDataFixerBuilderOverrides(result)
    result = CreativeTabHolderArguments.migrate(result, projectCreativeTabHolders)
    result = RecipeInputBounds.migrate(result, projectRecipeInputPositions)
    result = RecipeWrapperHandlers.migrate(result)
    result = PlayerInteractCancellation.migrate(result)
    result = migrateMobEffectHolderLookups(result)
    result = migrateChunkHolderResultAccess(result)
    result = migrateItemCapabilityOptionalAccess(result)
    result = migrateArmorTrimSheetDecal(result)
    result = migrateLegacyTagElementWriteChains(result)
    result = migrateCreateOpenEndedPipeApi(result)
    result = migrateCreateFluidIngredientAmounts(result)
    result = migrateRecipeManagerByKeyValues(result)
    result = migratePlayerSleepInBedEventSource(result)
    result = migrateHolderIterableElementAccess(result)
    result = migrateQuadBakingVertexConsumerCallbacks(result)
    result = migrateFluidStackCopyConstructors(result)
    result = migrateVersionRelativeGameTestFixtures(result)
    result = migratePlayerWillDestroyReturnFlow(result)
    result = migrateLootEnchantmentHolderArguments(result)
    result = migrateDeclaredHolderBuilderArguments(result)
    result = migrateItemStackHurtCalls(result)
    result = migrateEventBusPostResults(result)
    result = migrateSkullOwnerProfileWrites(result)
    result = migrateSupplierCurioProviders(result)
    result = migrateRandomizableContainerLootTableKeys(result)
    result = migrateSynchedDataBuilderDefines(result)
    result = migrateGameTestMockPlayers(result)
    result = migrateModelBakingResultLocations(result)
    result = migrateRegistryDependentSaveCalls(result, javaInheritanceIndex)
    result = migrateBlockEntityCapabilityLookups(result)
    result = migrateMixinInjectorSignatures(result)
    result = migratePlayerRendererMapKeys(result)
    result = result.replace(Regex("""@Override[ \t]+@Override\b"""), "@Override")
    result = migrateLegacyItemEnchantmentsMutableMaps(result)
    result = migrateLegacyRegistryObjectMethodReferences(result)
    result = migrateDeferredHolderCollectionVariance(result)
    result = migrateLegacyCommonHooksToolChecks(result)
    result = migrateAttributeModifierMultimapHolderTypes(result)
    result = migrateLegacyItemAttributeModifierOverrides(result)
    result = migrateExactRecordAccessors(result)
    result = migrateLegacyLootEnchantmentHolderCalls(result)
    result = migrateLegacyBlockLootSubProviderSource(result)
    result = migrateLegacySpriteSourceProviderSource(result, spriteSourceProviderClasses)
    result = migrateDataPackRegistryMapCodecSource(result, mapCodecConstantOwners)
    result = migrateLegacyBiomeSourceMapCodecSource(result)
    result = normalizeBiomeSourceMapCodecWitness(result)
    result = migrateLegacyTopElementBufferSource(result)
    result = migrateDimensionSpecialEffectsRenderSkySource(result)
    result = migrateLegacyRecipeProviderSource(result, recipeProviderClasses, projectModIdExpression)
    result = threadLookupProviderIntoStaticDatagenHelpers(result, recipeProviderClasses.concrete)
    result = migrateLegacyRecipeBuilderSource(result, recipeSerializerFactoryHints)
    result = migrateCreateRecipeConstructionBoundaries(result)
    result = migrateCreateLegacyRecipeSerializationBoundaries(result)
    result = migrateLegacyLootProviderHolderLookupSource(result, lootTableProviderClasses, lootTableProviderFactoryMethods)
    result = migrateLegacyGlobalLootModifierProviderSource(result, globalLootModifierProviderClasses)
    result = migrateLegacyEntityLootSubProviderSource(result)
    result = migrateLegacyLootFunctionSource(result)
    result = removeUnusedSimpleImport(result, "net.minecraft.world.item.alchemy.PotionUtils", "PotionUtils")
    result = migrateLegacyLootTableSubProviderFactorySource(result)
    result = migrateLegacyLootEnchantmentHolderCalls(result)
    result = migrateLegacyPackMetadataSectionSource(result)
    result = migrateFinalMapDecorationSubclassSource(result)
    result = migrateFinalMapDecorationSubclassUsageSource(result, finalMapDecorationClasses)
    result = migrateItemEntityThrowerEntitySource(result)
    result = migrateLegacyMeleeAttackGoalOverrides(result)
    result = migrateCustomGoalAttackReach(result, javaInheritanceIndex)
    result = migrateLegacyParticleCullingOverrides(result)
    result = migrateLegacyItemStackRegistrySerialization(result, javaInheritanceIndex, javaMethodReturnTypes)
    result = migrateLegacyBlockEntityLoadCalls(result, javaInheritanceIndex)
    result = migrateLegacyStructureStartCustomLoads(result)
    result = migrateLegacyTooltipContextAndEntityDataSource(result)
    result = migrateClientLevelEntityInsertionCalls(result)
    result = migrateCommandSourceStackLevelAccess(result)
    result = migrateCreateHighlightPrefixedCommandResult(result)
    result = migrateLegacyStaticFmlModEventBusAccess(result)
    result = migrateCuriosInventoryOptionalTypes(result)
    result = migrateLegacyCuriosInventoryCapabilities(result)
    result = migrateCuriosInventoryDirectResolveCalls(result)
    result = migrateCuriosAttributeModifierHolderTypes(result)
    result = migrateCaelusApi121(result)
    result = migrateLegacyOptionalValueCalls(result)
    result = migrateLegacyCurativeItemEffectsSource(result)
    if (result.contains("IEntityAdditionalSpawnData")) {
        result = result
            .replace(
                "import net.neoforged.neoforge.entity.IEntityAdditionalSpawnData;",
                "import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;"
            )
            .replace("IEntityAdditionalSpawnData", "IEntityWithComplexSpawn")
        needsEntityWithComplexSpawn = true
    }
    if (result.contains("writeSpawnData(FriendlyByteBuf") || result.contains("readSpawnData(FriendlyByteBuf")) {
        result = result
            .replace("writeSpawnData(FriendlyByteBuf", "writeSpawnData(RegistryFriendlyByteBuf")
            .replace("readSpawnData(FriendlyByteBuf", "readSpawnData(RegistryFriendlyByteBuf")
        needsRegistryFriendlyByteBuf = true
        if (!Regex("""\bFriendlyByteBuf\b""").containsMatchIn(removeImport(result, "net.minecraft.network.FriendlyByteBuf"))) {
            result = removeImport(result, "net.minecraft.network.FriendlyByteBuf")
        }
    }
    result = migrateVanillaToolItemConstructors(result)
    result = migrateMmlibBlockLootConstructor(result)
    result = migrateBonemealableTargetCalls(result)
    result = migrateBucketPickupPickupBlockCalls(result)
    result = migrateLegacyRegistryObjectReflection(result)
    result = migratePayloadContextServerPlayer(result)
    result = migrateFoodComponentAccess(result, javaInheritanceIndex)
    result = migrateLegacyItemCorrectToolForDropsSource(result, javaInheritanceIndex)
    result = migrateLegacySwordItemDamageAccess(result)
    result = migrateLegacyItemMaxStackSizeCalls(result)
    result = migrateLegacyShearableCallSitesSource(result)
    result = migrateBucketItemSupplierConstructors(result, javaInheritanceIndex)
    result = migrateDirectMobBucketItemConstructors(result)
    result = migrateBucketItemCanBlockContainFluidSource(result, javaInheritanceIndex)
    result = migrateBucketItemFluidAccessSource(result)
    result = migrateCreativeTabEntriesAccessSource(result)
    result = migrateItemStackHoverNameSource(result)
    result = migrateFriendlyByteBufAmbiguousMethodReferencesSource(result)
    result = AmbiguousBufferReferences.migrate(result)
    result = migrateCustomPacketPayloadRecordContractsSource(result, modId)
    result = migrateAbstractNestedPayloadBaseInterfaces(result)
    result = migrateLegacyParticleRenderTypeSource(result)
    result = qualifyImportsConflictingWithTopLevelType(result)
    result = migrateSupplierValueCalls(result)
    result = migrateHolderValueAccessors(result)
    result = migrateNeoForgeRegistriesValueAccess(result)
    result = migrateUnboundLevelRegistryAccessCalls(result)
    result = migrateNitrogenBlockStateRecipeConstructors(result)
    result = migrateNitrogenBiomeParameterRecipeSource(result)
    result = migrateNitrogenBiomeParameterRecipeSerializerSource(result)
    result = migrateReiBiomeTooltipOptionalSource(result)
    result = migrateNitrogenFuelCategorySource(result)
    result = migrateBiomeParameterRecipeCategoryBiomeAccessSource(result)
    result = migrateIngredientNetworkCodecs(result, javaInheritanceIndex)
    result = migrateCacheableFunctionOptionalBoundaries(result)
    result = migrateRecipeHolderAccess(result)
    result = migrateGetAllRecipesForSingleHolderLookups(result)
    result = migrateRecipeManagerByKeyHolderAccessSource(result)
    result = collapseDuplicateRecipeHolderValueMaps(result)
    result = migrateMapEntryValueGetCalls(result)
    result = migrateRecipeHolderIdAndLocalMmlibApi(result)
    result = migrateRecipeBookCategoryFinderRecipeHolders(result)
    result = migrateLegacyCraftingRecipeBoundaries(result)
    result = migrateRepairItemRecipeEnchantmentFilterRedirect(result)
    result = migrateGenericRecipeInputImplementations(result, recipeImplementationClasses)
    result = migrateLegacyPlacementBanRecipeSource(
        result,
        legacyPlacementBanBaseClasses,
        legacyPlacementBanBuilderClasses,
        legacyPlacementBanDisplayClasses
    )
    result = migrateRecipeDisplayRecipeIdWithoutHolderSource(result, javaInheritanceIndex, recipeImplementationClasses)
    result = migrateRecipeHolderOptionalMapLambdaValueAccess(result)
    result = migrateRecipeHolderAccess(result)
    result = migrateGetAllRecipesForSingleHolderLookups(result)
    result = collapseDuplicateRecipeHolderValueMaps(result)
    result = migrateMerchantOfferItemCosts(result)
    result = migrateMerchantOffersNbtCodecs(result, javaInheritanceIndex)
    result = migrateItemUseDurationCalls(result)
    result = migrateContextBoundItemStackUseDurations(result, javaInheritanceIndex)
    result = migrateLegacyOnArmorTickSource(result)
    result = migrateLegacyItemExtensionAndProjectileApis(result)
    result = migrateLegacyProjectileWeaponShootProjectileBridge(result)
    result = migrateMobEffectApplyEffectTickReturnSource(result)
    result = migrateLegacyProjectileEnchantmentPostEffectsSource(result, javaInheritanceIndex)
    result = migrateLegacyPlayerAttackMixinPostHurtInjection(result)
    result = migrateLegacyItemStackHurtAndBreakMixinInjection(result)
    result = migrateLegacyDoEnchantDamageEffectsSource(result)
    result = migrateMobEffectApplicableEventSource(result)
    result = migrateLivingDamageEventBoundarySource(result)
    result = migrateLegacyCommonHooksLivingDamagePipelineSource(result)
    result = migrateLegacyDamageEventAttackerBindings(result)
    result = migrateLegacyBreakEventExpToDropSource(result)
    result = migrateLegacyAbstractArrowKnockbackCallSites(result)
    result = migrateLegacyServerInteractionDistanceConstants(result)
    result = migrateLegacyBoatFluidUpdateHookSource(result)
    val hadLazyOptionalCompatImport = Regex(
        """(?m)^[ \t]*import\s+(?:com\.modporter(?:\.generated\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)?\.compat|net\.(?:neo)?forged\.common\.util)\.LazyOptional;\s*$"""
    ).containsMatchIn(maskJavaCommentsAndLiterals(result))
    result = migrateGeneratedLazyOptionalImportSource(result, generatedCompatPackage)
    result = removeUnusedLazyOptionalFields(result, allowPlainUnusedCleanup = hadLazyOptionalCompatImport)

    result = FoodPropertiesEffects.migrate(this, result)
    result = ItemFireResistance.migrate(result)
    result = migrateChunkStatusAccessors(result, javaInheritanceIndex)
    result = migrateBlockPosAabbConstructors(result, javaMethodReturnTypes, javaInheritanceIndex)
    result = migrateInfiniteRenderBoundingBox(result, javaInheritanceIndex)

    if (result.contains("new VillagerProfession(")) {
        result = Regex("""([A-Za-z_$][\w$]*)\s*->\s*\1\.get\(\)\s*==""")
            .replace(result) { match -> "${match.groupValues[1]} -> ${match.groupValues[1]}.value() ==" }
    }

    result = Regex(
        """return\s+!result\.consumesAction\(\)\s*&&\s*this\.has\(net\.minecraft\.core\.component\.DataComponents\.FOOD\)\s*\r?\n\s*\?\s*this\.use\([^)]+\)\.getResult\(\)\s*\r?\n\s*:\s*result;"""
    ).replace(result, "return result;")
    result = Regex(
        """return\s+!result\.consumesAction\(\)\s*&&\s*this\.has\(net\.minecraft\.core\.component\.DataComponents\.FOOD\)\s*\r?\n\s*\?\s*this\.use\(context\.getLevel\(\),\s*context\.getPlayer\(\),\s*context\.getHand\(\)\)\.getResult\(\)\s*\r?\n\s*:\s*result;"""
    ).replace(result, "return result;")

    if (result.contains("InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)")) {
        result = result.replace("ItemStack sheathStack = player.getMainHandItem();", "ItemStack sheathStack = player.getItemInHand(hand);")
        result = result.replace(
            "InteractionResultHolder.sidedSuccess(player.getMainHandItem(), level.isClientSide())",
            "InteractionResultHolder.success(player.getItemInHand(hand))"
        )
    }

    if (result.contains(".save(new net.minecraft.nbt.CompoundTag())") && result.contains("Player player")) {
        result = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\(\))*)\.save\(new net\.minecraft\.nbt\.CompoundTag\(\)\)""")
            .replace(result) { match ->
                "${match.groupValues[1]}.save(player.registryAccess(), new net.minecraft.nbt.CompoundTag())"
            }
    }
    result = migrateCopiedCustomDataWrites(result)
    result = CopiedCustomDataWrites.migrate(result)
    result = migrateStructurePlaceSettingsLiquidSettingsSource(result)

    if (result.contains("extends StructurePoolElement")) {
        result = Regex(
            """BoundingBox\s+boundingBox\s*,\s*RandomSource\s+random\s*,\s*boolean\s+keepJigsaws"""
        ).replace(result) {
            "BoundingBox boundingBox, RandomSource random, LiquidSettings liquidSettings, boolean keepJigsaws"
        }
        if (result.contains("LiquidSettings")) {
            result = addImportIfMissing(result, "net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings")
        }
    }

    result = migrateGlobalLootModifierCodecs(result)
    result = migrateDuplicateHolderLookupConstructorArguments(result)
    result = migrateNeoForgeGameTestResourceAssertions(result)

    result = Regex(
        """public\s+void\s+appendHoverText\(([^,]+),\s*(?:@(?:[\w.]+\.)?Nullable\s+)?(?:net\.minecraft\.world\.level\.)?(?:Level|BlockGetter)\s+(\w+),"""
    ).replace(result) { match ->
        needsItemTooltipContext = true
        "public void appendHoverText(${match.groupValues[1]}, Item.TooltipContext ${match.groupValues[2]},"
    }
    result = migrateLegacyItemStackRegistrySerialization(result, javaInheritanceIndex, javaMethodReturnTypes)

    if (result.contains("BlockItem.getBlockEntityData(")) {
        result = Regex("""BlockItem\.getBlockEntityData\(([^)]+)\)""")
            .replace(result) { match ->
                "${match.groupValues[1].trim()}.getOrDefault(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
            }
    }

    if (result.contains(".getEnchantmentLevel(Enchantments.BLOCK_FORTUNE)") ||
        result.contains(".getEnchantmentLevel(Enchantments.FORTUNE)")) {
        result = Regex("""([A-Za-z_$][\w$]*)\.getEnchantmentLevel\(Enchantments\.(?:BLOCK_FORTUNE|FORTUNE)\)""")
            .replace(result) { match ->
                val stack = match.groupValues[1]
                """level.registryAccess().lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .flatMap(reg -> reg.get(Enchantments.FORTUNE))
                    .map(holder -> net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(holder, $stack))
                    .orElse(0)"""
            }
    }

    if (result.contains("@Nullable CompoundTag") && result.contains("finalizeSpawn(ServerLevelAccessor")) {
        var finalizeSpawnMigrated = false
        val removedFinalizeSpawnTags = linkedSetOf<String>()
        result = Regex(
            """public\s+SpawnGroupData\s+finalizeSpawn\(\s*ServerLevelAccessor\s+(\w+)\s*,\s*DifficultyInstance\s+(\w+)\s*,\s*MobSpawnType\s+(\w+)\s*,\s*@Nullable\s+SpawnGroupData\s+(\w+)\s*,\s*@Nullable\s+CompoundTag\s+(\w+)\s*\)"""
        ).replace(result) { match ->
            finalizeSpawnMigrated = true
            val levelName = match.groupValues[1]
            val difficultyName = match.groupValues[2]
            val spawnTypeName = match.groupValues[3]
            val spawnDataName = match.groupValues[4]
            removedFinalizeSpawnTags.add(match.groupValues[5])
            "public SpawnGroupData finalizeSpawn(ServerLevelAccessor $levelName, DifficultyInstance $difficultyName,\n            MobSpawnType $spawnTypeName, @Nullable SpawnGroupData $spawnDataName)"
        }
        if (finalizeSpawnMigrated) {
            result = removeLegacyFinalizeSpawnTagGuards(result, removedFinalizeSpawnTags)
            result = Regex(
                """(\w+)\s*=\s*super\.finalizeSpawn\(\s*(\w+)\s*,\s*(\w+)\s*,\s*(\w+)\s*,\s*\1\s*,\s*(\w+)\s*\);"""
            ).replace(result, "$1 = super.finalizeSpawn($2, $3, $4, $1);")
            result = rewriteLegacyFinalizeSpawnCalls(result)
            val finalizeEquipment = migrateLegacyFinalizeSpawnEquipmentRandomSource(result)
            if (finalizeEquipment != result) {
                needsRandomSource = true
                result = finalizeEquipment
            }
            val withoutCompoundImport = removeImport(result, "net.minecraft.nbt.CompoundTag")
            if (!withoutCompoundImport.contains("CompoundTag")) {
                result = withoutCompoundImport
            }
        }
    }
    result = migrateLegacyFinalizeSpawnMixinDescriptors(result)
    result = migrateLegacyLevelStorageSourceLoadSummariesLambdaMixinDescriptor(result)

    if (result.contains("populateDefaultEquipmentEnchantments(")) {
        result = Regex(
            """protected\s+void\s+populateDefaultEquipmentEnchantments\(\s*(?:net\.minecraft\.util\.)?RandomSource\s+random\s*,\s*DifficultyInstance\s+difficulty\s*\)"""
        ).replace(result) {
            needsRandomSource = true
            "protected void populateDefaultEquipmentEnchantments(ServerLevelAccessor level, RandomSource random, DifficultyInstance difficulty)"
        }
        result = result.replace(
            "super.populateDefaultEquipmentEnchantments(random, difficulty);",
            "super.populateDefaultEquipmentEnchantments(level, random, difficulty);"
        )
        result = Regex("""EnchantmentHelper\.enchantItem\(\s*this\.random\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*5\s*\+\s*difficulty\.getDifficulty\(\)\.getId\(\)\s*\*\s*this\.random\.nextInt\(6\)\s*,\s*false\s*\);""")
            .replace(result) { match ->
                needsVanillaEnchantmentProviders = true
                "EnchantmentHelper.enchantItemFromProvider(${match.groupValues[1]}, level.registryAccess(), VanillaEnchantmentProviders.MOB_SPAWN_EQUIPMENT, difficulty, random);"
            }
    }

    if (result.contains("applyRaidBuffs(int wave, boolean")) {
        result = result.replace(
            "public void applyRaidBuffs(int wave, boolean hasBonus)",
            "public void applyRaidBuffs(ServerLevel level, int wave, boolean hasBonus)"
        )
        result = result.replace(
            "public void applyRaidBuffs(int wave, boolean unused)",
            "public void applyRaidBuffs(ServerLevel level, int wave, boolean unused)"
        )
        needsServerLevel = true
    }
    result = Regex(
        """(?s)int\s+enchLevel\s*=\s*1;\s*if\s*\(\s*wave\s*>\s*raid\.getNumGroups\(Difficulty\.NORMAL\)\s*\)\s*\{\s*enchLevel\s*=\s*2;\s*\}\s*boolean\s+shouldEnchant\s*=\s*this\.random\.nextFloat\(\)\s*<=\s*raid\.getEnchantOdds\(\);\s*if\s*\(\s*shouldEnchant\s*\)\s*\{\s*EnchantmentHelper\.enchantItem\(\s*this\.random\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*5\s*\+\s*enchLevel\s*\*\s*3\s*,\s*false\s*\);\s*\}"""
    ).replace(result) { match ->
        needsVanillaEnchantmentProviders = true
        """boolean shouldEnchant = this.random.nextFloat() <= raid.getEnchantOdds();
            if (shouldEnchant) {
                net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.providers.EnchantmentProvider> resourcekey =
                        wave > raid.getNumGroups(Difficulty.NORMAL)
                                ? VanillaEnchantmentProviders.RAID_VINDICATOR_POST_WAVE_5
                                : VanillaEnchantmentProviders.RAID_VINDICATOR;
                EnchantmentHelper.enchantItemFromProvider(${match.groupValues[1]}, level.registryAccess(), resourcekey, level.getCurrentDifficultyAt(this.blockPosition()), this.random);
            }"""
    }

    if (result.contains("VertexConsumer")) {
        result = Regex("""\.color\(\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^)]+)\)""")
            .replace(result) { match ->
                ".setColor(${match.groupValues[1].trim()}, ${match.groupValues[2].trim()}, ${match.groupValues[3].trim()}, ${match.groupValues[4].trim()})"
            }
        result = Regex("""\.overlayCoords\(\s*0\s*,\s*10\s*\)""")
            .replace(result) {
                needsOverlayTexture = true
                ".setOverlay(OverlayTexture.NO_OVERLAY)"
            }
        result = Regex("""\.uv2\(\s*([^)]+)\)""")
            .replace(result) { match -> ".setLight(${match.groupValues[1].trim()})" }
        result = Regex("""\.normal\(\s*([A-Za-z_$][\w$]*)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^)]+)\)""")
            .replace(result) { match ->
                ".setNormal(${match.groupValues[1].trim()}, ${match.groupValues[2].trim()}, ${match.groupValues[3].trim()}, ${match.groupValues[4].trim()})"
            }
        result = Regex("""\.normal\(\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^)]+)\)""")
            .replace(result) { match ->
                ".setNormal(${match.groupValues[1].trim()}, ${match.groupValues[2].trim()}, ${match.groupValues[3].trim()})"
            }
        val helperSignature = Regex(
            """private\s+void\s+addVertex\([^)]*Matrix3f\s+([A-Za-z_$][\w$]*)[\s\S]*?float\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*int\s+packedLight"""
        ).find(result)
        if (helperSignature != null && !result.contains("transformedNormal")) {
            val normalParam = helperSignature.groupValues[1]
            val nxParam = helperSignature.groupValues[2]
            val nyParam = helperSignature.groupValues[3]
            val nzParam = helperSignature.groupValues[4]
            val setNormalCall = ".setNormal($normalParam, $nxParam, $nyParam, $nzParam)"
            if (result.contains(setNormalCall)) {
                result = Regex("""(?m)^([ \t]*)builder\.addVertex\(""")
                    .replaceFirst(
                        result,
                        "$1org.joml.Vector3f transformedNormal = $normalParam.transform($nxParam, $nyParam, $nzParam, new org.joml.Vector3f());\n$1builder.addVertex("
                    )
                result = result.replace(
                    setNormalCall,
                    ".setNormal(transformedNormal.x(), transformedNormal.y(), transformedNormal.z())"
                )
            }
        }
    }

    if (result.contains("getMobType() == MobType.UNDEAD")) {
        result = Regex("""(\w+)\.getMobType\(\)\s*==\s*MobType\.UNDEAD""")
            .replace(result) { match -> "${match.groupValues[1]}.getType().is(EntityTypeTags.UNDEAD)" }
        result = removeImport(result, "net.minecraft.world.entity.MobType")
        needsEntityTypeTags = true
    }

    result = result
        .replace("AttributeModifier.Operation.ADDITION", "AttributeModifier.Operation.ADD_VALUE")
        .replace("AttributeModifier.Operation.MULTIPLY_TOTAL", "AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL")
        .replace("AttributeModifier.Operation.MULTIPLY_BASE", "AttributeModifier.Operation.ADD_MULTIPLIED_BASE")

    if (result.contains("Attribute attribute,")) {
        result = result.replace("Attribute attribute,", "Holder<Attribute> attribute,")
        needsHolder = true
    }
    result = migrateNameDerivedAttributeModifierIds(result)
    result = Regex("""new\s+AttributeModifier\(\s*\w+\s*,\s*(\w+)\.toString\(\)\s*,\s*([^,]+)\s*,\s*([^)]+)\)""")
        .replace(result) { match ->
            "new AttributeModifier(${match.groupValues[1]}, ${match.groupValues[2].trim()}, ${match.groupValues[3].trim()})"
        }
    result = result.replace("attributeInstance.hasModifier(modifier)", "attributeInstance.hasModifier(modifier.id())")
    result = result.replace("attributeInstance.removeModifier(uuid)", "attributeInstance.removeModifier(modifierName)")

    result = Regex("""protected\s+float\s+getGravity\s*\(\s*\)""")
        .replace(result, "protected double getDefaultGravity()")

    if (result.contains("ParticleTypes.ENTITY_EFFECT")) {
        val particlePattern = Regex(
            """addParticle\(\s*ParticleTypes\.ENTITY_EFFECT\s*,\s*([^,\n]+(?:\([^)]*\))?(?:\s*\+\s*[^,\n]+)?),\s*([^,\n]+(?:\([^)]*\))?(?:\s*\+\s*[^,\n]+)?),\s*([^,\n]+(?:\([^)]*\))?(?:\s*\+\s*[^,\n]+)?),\s*([A-Za-z_][\w]*)\s*,\s*([A-Za-z_][\w]*)\s*,\s*([A-Za-z_][\w]*)\s*\)"""
        )
        result = particlePattern.replace(result) { match ->
            needsColorParticle = true
            "addParticle(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, (float) ${match.groupValues[4]}, (float) ${match.groupValues[5]}, (float) ${match.groupValues[6]}), ${match.groupValues[1]}, ${match.groupValues[2]}, ${match.groupValues[3]}, 0.0D, 0.0D, 0.0D)"
        }
    }

    if (result.contains("extends AbstractItemModelProvider")) {
        result = result
            .replace("bushItem(item);", "itemBlockFlat(() -> blockItem.getBlock());")
            .replace("itemBlock(blockItem::getBlock);", "toBlock(() -> blockItem.getBlock());")
            .replace("normalItem(", "singleTex(")
    }

    if (result.contains("extends AbstractBlockStateProvider")) {
        result = Regex("""\blog\((BlockRegistry\.[A-Z0-9_]+)\);""")
            .replace(result) { match ->
                needsDatagenSupplier = true
                needsDatagenBlockHelpers = true
                "log(${match.groupValues[1]}::get);"
            }
        result = Regex("""\bcrossBlock\((BlockRegistry\.[A-Z0-9_]+)\);""")
            .replace(result) { match ->
                needsDatagenSupplier = true
                "crossBlock(${match.groupValues[1]}::get);"
            }
        result = Regex("""\bstageBlock\((BlockRegistry\.[A-Z0-9_]+)\s*,""")
            .replace(result) { match ->
                needsDatagenSupplier = true
                needsDatagenBlockHelpers = true
                "stageBlock(${match.groupValues[1]}::get,"
            }
        result = Regex("""\bfacingSlabBlock\((BlockRegistry\.[A-Z0-9_]+)\s*,""")
            .replace(result) { match ->
                needsDatagenSupplier = true
                needsDatagenBlockHelpers = true
                "facingSlabBlock(${match.groupValues[1]}::get,"
            }
        if (needsDatagenBlockHelpers && !result.contains("private void stageBlock(Supplier<? extends Block>")) {
            result = insertBeforeLastClassBrace(result, legacyMmlibBlockStateHelpers())
        }
    }

    if (result.contains("part.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, r, g, b, alpha)")) {
        result = result.replace(
            "part.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, r, g, b, alpha)",
            "part.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.colorFromFloat(alpha, r, g, b))"
        )
        needsFastColor = true
    }

    val skinModelCallMigrated = migrateLegacyPlayerSkinModelCallsSource(result)
    if (skinModelCallMigrated != result) {
        result = skinModelCallMigrated
        needsPlayerSkin = true
    }
    val skinLoopMigrated = migrateLegacyAddLayersSkinNameLoopsSource(result)
    if (skinLoopMigrated != result) {
        result = skinLoopMigrated
        needsPlayerSkin = true
    }

    result = Regex("""new\s+MerchantOffer\(\s*new\s+ItemStack\((Items\.\w+),\s*(\d+)\),""")
        .replace(result) { match ->
            needsItemCost = true
            "new MerchantOffer(\n                                            new ItemCost(${match.groupValues[1]}, ${match.groupValues[2]}),"
        }

    val potionStacksMigrated = PotionUtilsStackCalls.migrate(result) {
        "${generatedCompatPackage()}.LegacyPotionUtils.EMPTY"
    }
    if (potionStacksMigrated != result) {
        result = potionStacksMigrated
        if (!maskJavaCommentsAndLiterals(result).contains("PotionUtils.")) {
            result = removeImport(result, "net.minecraft.world.item.alchemy.PotionUtils")
        }
    }
    if (maskJavaCommentsAndLiterals(result).let { it.contains("Potions.EMPTY") || it.contains("Potion.byName(") || it.contains("new PotionContents(") }) {
        result = replaceExecutableRegex(
            result,
            Regex("""new\s+PotionContents\(\s*Potions\.EMPTY\s*\)""")
        ) { "PotionContents.EMPTY" }
        result = replaceExecutableRegex(
            result,
            Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\([^)]*\))*\.getOrDefault\(DataComponents\.POTION_CONTENTS,\s*PotionContents\.EMPTY\))\.potion\(\)\.orElse\(Potions\.EMPTY\)\s*!=\s*Potions\.EMPTY""")
        ) { match -> "${match.groupValues[1]}.potion().isPresent()" }
        result = migrateLegacyPotionEmptyStringChecks(result)
        result = replaceExecutableRegex(
            result,
            Regex("""Potion\.byName\(\s*([^)]+)\s*\)""")
        ) { match ->
            "net.minecraft.core.registries.BuiltInRegistries.POTION.get(net.minecraft.resources.ResourceLocation.parse(${match.groupValues[1].trim()}))"
        }
        result = replaceExecutableRegex(
            result,
            Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\([^)]*\))*\.getOrDefault\(DataComponents\.POTION_CONTENTS,\s*PotionContents\.EMPTY\))\.potion\(\)\.orElse\(Potions\.WATER\.value\(\)\)\s*!=\s*Potions\.WATER\.value\(\)""")
        ) { match -> "${match.groupValues[1]}.potion().isPresent()" }
        if (maskJavaCommentsAndLiterals(result).contains("PotionContents.EMPTY")) {
            needsPotionContents = true
        }
    }

    result = migrateUseOnContextDataComponentHasCalls(result)
    result = migrateThisStackUseDurationCalls(result)
    result = replaceExecutableRegex(result, Regex("""\.restore\(\s*true\s*,\s*false\s*\)""")) { ".restore()" }

    val migratedPickupOverride = migrateNotNullPickupBlockOverridePlayerParameterSource(result)
    result = migratedPickupOverride.first
    if (migratedPickupOverride.second) {
        if (!result.contains("import org.jetbrains.annotations.Nullable;")) {
            result = addImportIfMissing(result, "javax.annotation.Nullable")
        }
        result = addImportIfMissing(result, "net.minecraft.world.entity.player.Player")
    }

    result = migrateCreateInteractionCompilerSurfaces(result)
    result = migrateJava21StrictWarningSource(result)
    result = migrateDuplicateHolderLookupConstructorArguments(result)

    if (needsEntityTypeTags) result = addImportIfMissing(result, "net.minecraft.tags.EntityTypeTags")
    if (needsHolder) result = addImportIfMissing(result, "net.minecraft.core.Holder")
    if (result.contains("Holder<Attribute>")) result = addImportIfMissing(result, "net.minecraft.world.entity.ai.attributes.Attribute")
    if (needsColorParticle) result = addImportIfMissing(result, "net.minecraft.core.particles.ColorParticleOption")
    if (needsFastColor) result = addImportIfMissing(result, "net.minecraft.util.FastColor")
    if (needsPlayerSkin) result = addImportIfMissing(result, "net.minecraft.client.resources.PlayerSkin")
    if (needsItemCost) result = addImportIfMissing(result, "net.minecraft.world.item.trading.ItemCost")
    if (needsDataComponents || result.contains("DataComponents.FOOD")) result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    if (needsPotionContents) result = addImportIfMissing(result, "net.minecraft.world.item.alchemy.PotionContents")
    val resultExecutableCode = maskJavaCommentsAndLiterals(result)
    if (needsItemTooltipContext || resultExecutableCode.contains("Item.TooltipContext")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.world.item.Item")
    }
    if (needsOverlayTexture || result.contains("OverlayTexture.")) {
        result = addImportIfMissing(result, "net.minecraft.client.renderer.texture.OverlayTexture")
    }
    if (needsRandomSource) result = addImportIfMissing(result, "net.minecraft.util.RandomSource")
    if (needsServerLevel) result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    if (needsVanillaEnchantmentProviders) result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.providers.VanillaEnchantmentProviders")
    if (needsEnchantmentEffectComponents) result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.EnchantmentEffectComponents")
    if (needsDatagenSupplier) result = addImportIfMissing(result, "java.util.function.Supplier")
    if (needsRegistryFriendlyByteBuf) result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    if (needsEntityWithComplexSpawn) result = addImportIfMissing(result, "net.neoforged.neoforge.entity.IEntityWithComplexSpawn")
    if (needsDatagenBlockHelpers) {
        result = addImportIfMissing(result, "net.minecraft.world.level.block.Block")
        result = addImportIfMissing(result, "net.minecraft.world.level.block.RotatedPillarBlock")
        result = addImportIfMissing(result, "net.minecraft.world.level.block.SlabBlock")
        result = addImportIfMissing(result, "net.minecraft.world.level.block.state.properties.IntegerProperty")
        result = addImportIfMissing(result, "net.minecraft.world.level.block.state.properties.Property")
        result = addImportIfMissing(result, "net.minecraft.world.level.block.state.properties.SlabType")
        result = addImportIfMissing(result, "net.neoforged.neoforge.client.model.generators.ConfiguredModel")
        result = addImportIfMissing(result, "net.neoforged.neoforge.client.model.generators.ModelFile")
    }
    val withoutAttributeImport = removeImport(result, "net.minecraft.world.entity.ai.attributes.Attribute")
    result = if (hasSimpleTypeReference(withoutAttributeImport, "Attribute")) {
        addImportIfMissing(result, "net.minecraft.world.entity.ai.attributes.Attribute")
    } else {
        withoutAttributeImport
    }
    val nullableExecutableCode = maskJavaCommentsAndLiterals(result)
    if (Regex("""(?<![\w$])@Nullable(?![\w$])""").containsMatchIn(nullableExecutableCode) &&
        !nullableExecutableCode.contains("import org.jetbrains.annotations.Nullable;") &&
        !nullableExecutableCode.contains("import javax.annotation.Nullable;")) {
        result = addExecutableImportIfMissing(result, "javax.annotation.Nullable")
    }
    val nullableImportExecutableCode = maskJavaCommentsAndLiterals(result)
    if (nullableImportExecutableCode.contains("import org.jetbrains.annotations.Nullable;") &&
        nullableImportExecutableCode.contains("import javax.annotation.Nullable;")) {
        result = removeExecutableImport(result, "javax.annotation.Nullable")
    }
    result = migrateNewItemStackComponentSetChains(result)
    result = migrateLegacyPlayerWillDestroyWithoutSuper(result)
    result = migrateUnsplitTickEventHandlers(result)
    result = migrateLegacyItemStackMethodReferences(result)
    result = migrateEnchantmentComponentMaps(result)
    result = migrateHolderElementCalls(result)
    result = migrateEnchantmentHolderCollections(result)
    result = migrateRecipeHolderStreamValues(result)
    result = migrateCreateRecipeBoundaryValues(result)
    result = migrateLegacyScoreboardValues(result)
    result = migrateContainerRecipeArguments(result)
    result = migrateLegacyRegistryValueHolders(result)
    result = migrateLegacyHorseArmorValues(result)
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPlayerSkinModelCallsSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getSkin(")) return source
    val sourceCallPattern = Regex("""\b([A-Za-z_$][\w$]*)\.getSkin\(\s*"(default|slim)"\s*\)""")
    val executableCallPattern = Regex("""\b([A-Za-z_$][\w$]*)\.getSkin\(\s*\)""")
    val edits = sourceCallPattern.findAll(source).mapNotNull { match ->
        val executableMatch = executableCallPattern.find(executableCode, match.range.first)
            ?: return@mapNotNull null
        if (executableMatch.range != match.range ||
            executableMatch.groupValues[1] != match.groupValues[1]) {
            return@mapNotNull null
        }
        val model = when (match.groupValues[2]) {
            "default" -> "WIDE"
            "slim" -> "SLIM"
            else -> return@mapNotNull null
        }
        match.range to "${match.groupValues[1]}.getSkin(PlayerSkin.Model.$model)"
    }.toList()
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateLegacyPotionEmptyStringChecks(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getString(") || !executableCode.contains("Potions.EMPTY.toString()")) return source
    val sourcePattern = Regex("""([A-Za-z_$][\w$]*)\.getString\("Potion"\)\.equals\(Potions\.EMPTY\.toString\(\)\)""")
    val executablePattern = Regex("""([A-Za-z_$][\w$]*)\.getString\(\s*\)\.equals\(Potions\.EMPTY\.toString\(\)\)""")
    val edits = sourcePattern.findAll(source).mapNotNull { match ->
        val executableMatch = executablePattern.find(executableCode, match.range.first)
            ?: return@mapNotNull null
        if (executableMatch.range != match.range ||
            executableMatch.groupValues[1] != match.groupValues[1]) {
            return@mapNotNull null
        }
        match.range to "\"minecraft:empty\".equals(${match.groupValues[1]}.getString(\"Potion\"))"
    }.toList()
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateUseOnContextDataComponentHasCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("UseOnContext") || !executableCode.contains("this.has(")) return source
    val contextParameterPattern = Regex("""\bUseOnContext\s+([A-Za-z_$][\w$]*)\b""")
    val hasCallPattern = Regex("""\bthis\.has\(\s*(DataComponents\.[A-Z0-9_]+|net\.minecraft\.core\.component\.DataComponents\.[A-Z0-9_]+)\s*\)""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executableCode)) {
        val methodText = executableCode.substring(method.range)
        val contextParameter = contextParameterPattern.find(methodText)?.groupValues?.get(1) ?: continue
        hasCallPattern.findAll(methodText).forEach { match ->
            val absoluteRange = (method.range.first + match.range.first)..(method.range.first + match.range.last)
            if (source.substring(absoluteRange) != match.value) return@forEach
            edits += absoluteRange to "$contextParameter.getItemInHand().has(${match.groupValues[1]})"
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateThisStackUseDurationCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("this.getUseDuration(") || !executableCode.contains("LivingEntity")) return source
    val livingParameterPattern = Regex("""(?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s+)*(?:final\s+)?(?:LivingEntity|net\.minecraft\.world\.entity\.LivingEntity)\s+([A-Za-z_$][\w$]*)\b""")
    val useDurationPattern = Regex("""\bthis\.getUseDuration\(\s*stack\s*\)""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executableCode)) {
        val openParen = method.header.indexOf('(')
        val closeParen = method.header.lastIndexOf(')')
        if (openParen < 0 || closeParen <= openParen) continue
        val parameterList = method.header.substring(openParen + 1, closeParen)
        val livingParameter = livingParameterPattern.findAll(parameterList)
            .map { it.groupValues[1] }
            .distinct()
            .singleOrNull()
            ?: continue
        val methodText = executableCode.substring(method.range)
        useDurationPattern.findAll(methodText).forEach { match ->
            val absoluteRange = (method.range.first + match.range.first)..(method.range.first + match.range.last)
            if (source.substring(absoluteRange) != match.value) return@forEach
            edits += absoluteRange to "this.getUseDuration(stack, $livingParameter)"
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateNotNullPickupBlockOverridePlayerParameterSource(source: String): Pair<String, Boolean> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("pickupBlock(") || !executableCode.contains("@NotNull")) return source to false
    val signaturePattern = Regex(
        """public\s+@NotNull\s+ItemStack\s+pickupBlock\(\s*@NotNull\s+LevelAccessor\s+([A-Za-z_$][\w$]*)\s*,\s*@NotNull\s+BlockPos\s+([A-Za-z_$][\w$]*)\s*,\s*@NotNull\s+BlockState\s+([A-Za-z_$][\w$]*)\s*\)"""
    )
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (match in signaturePattern.findAll(executableCode)) {
        val openBrace = executableCode.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) continue
        val method = JavaMethodRange("pickupBlock", source.substring(match.range.first..openBrace), match.range.first..closeBrace)
        if (source.substring(match.range) != match.value) continue
        val level = match.groupValues[1]
        val pos = match.groupValues[2]
        val state = match.groupValues[3]
        edits += match.range to "public @NotNull ItemStack pickupBlock(@Nullable Player player, @NotNull LevelAccessor $level, @NotNull BlockPos $pos, @NotNull BlockState $state)"
        collectSuperPickupBlockEdits(source, executableCode, method, level, pos, state, edits)
    }
    return applyStringEdits(source, edits) to edits.isNotEmpty()
}

internal fun StructuralRefactorPass.collectSuperPickupBlockEdits(
    source: String,
    executableCode: String,
    method: JavaMethodRange,
    level: String,
    pos: String,
    state: String,
    edits: MutableList<Pair<IntRange, String>>
) {
    val methodText = executableCode.substring(method.range)
    val superPattern = Regex(
        """super\.pickupBlock\(\s*${Regex.escape(level)}\s*,\s*${Regex.escape(pos)}\s*,\s*${Regex.escape(state)}\s*\)"""
    )
    superPattern.findAll(methodText).forEach { superMatch ->
        val absoluteRange = (method.range.first + superMatch.range.first)..(method.range.first + superMatch.range.last)
        if (source.substring(absoluteRange) != superMatch.value) return@forEach
        edits += absoluteRange to "super.pickupBlock(player, $level, $pos, $state)"
    }
}

internal fun StructuralRefactorPass.migrateLegacyAddLayersSkinNameLoopsSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getSkin(") || !executableCode.contains("String")) return source
    var cursor = 0
    val sourceDeclarationPattern = Regex(
        """(?s)(?:final\s+)?String\s*\[\]\s+([A-Za-z_$][\w$]*)\s*=\s*(?:new\s+String\s*\[\]\s*)?\{\s*"default"\s*,\s*"slim"\s*}\s*;\s*for\s*\(\s*String\s+([A-Za-z_$][\w$]*)\s*:\s*\1\s*\)\s*\{"""
    )
    val executableDeclarationPattern = Regex(
        """(?s)(?:final\s+)?String\s*\[\]\s+([A-Za-z_$][\w$]*)\s*=\s*(?:new\s+String\s*\[\]\s*)?\{\s*,\s*}\s*;\s*for\s*\(\s*String\s+([A-Za-z_$][\w$]*)\s*:\s*\1\s*\)\s*\{"""
    )
    val edits = mutableListOf<Pair<IntRange, String>>()
    while (true) {
        val match = sourceDeclarationPattern.find(source, cursor) ?: break
        val executableMatch = executableDeclarationPattern.find(executableCode, match.range.first)
        if (executableMatch == null || executableMatch.range != match.range) {
            cursor = match.range.last + 1
            continue
        }
        val loopVariable = match.groupValues[2]
        val openBrace = executableCode.indexOf('{', executableMatch.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = executableCode.substring(openBrace + 1, closeBrace)
        val eventVariable = Regex("""\b([A-Za-z_$][\w$]*)\.getSkin\(\s*${Regex.escape(loopVariable)}\s*\)""")
            .find(body)
            ?.groupValues
            ?.get(1)
        if (eventVariable == null) {
            cursor = closeBrace + 1
            continue
        }
        val replacement = "for (PlayerSkin.Model $loopVariable : $eventVariable.getSkins()) {"
        edits += (match.range.first..openBrace) to replacement
        cursor = closeBrace + 1
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateJava21StrictWarningSource(source: String): String {
    var result = source
    result = migrateRedundantJava21Casts(result)
    result = migrateBlockDefaultStateThisEscapeWarning(result)
    result = migrateConstructorThisMethodThisEscapeWarning(result)
    result = migrateFieldInitializerThisMethodThisEscapeWarning(result)
    result = migrateClassLevelThisEscapeFallback(result)
    result = migrateCuriosHelperRemovalApis(result)
    result = migrateJeiRecipeCategoryBackgroundApi(result)
    result = migrateLegacyJeiRecipeBoundaries(result)
    result = migrateRequiredRemovalWarningAnnotations(result)
    result = migrateMthTrigonometryFloatArguments(result)
    return result
}

internal fun StructuralRefactorPass.migrateRedundantJava21Casts(source: String): String {
    var result = source
    val executableCode = maskJavaCommentsAndLiterals(source)
    // A cast is redundant only where the variable is a float in the same method: another
    // method may declare a double of the same name (PoolRenderer#point passes (float) y).
    val castEdits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executableCode)) {
        val methodText = executableCode.substring(method.range)
        val floatVariables = Regex("""\bfloat\s+([A-Za-z_$][\w$]*)\b""").findAll(methodText).map { it.groupValues[1] }.toSet()
        val doubleVariables = Regex("""\bdouble\s+([A-Za-z_$][\w$]*)\b""").findAll(methodText).map { it.groupValues[1] }.toSet()
        for (variable in floatVariables - doubleVariables) {
            Regex("""\(float\)\s+${Regex.escape(variable)}\b""").findAll(methodText).forEach { match ->
                castEdits += (method.range.first + match.range.first..method.range.first + match.range.last) to variable
            }
        }
    }
    castEdits.sortedByDescending { it.first.first }.forEach { (range, text) -> result = result.replaceRange(range, text) }

    val resultExecutableCode = maskJavaCommentsAndLiterals(result)
    val livingEntityVariables = Regex("""\bLivingEntity\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(resultExecutableCode)
        .map { it.groupValues[1] }
        .toSet()
    for (variable in livingEntityVariables) {
        result = replaceExecutableRegex(result, Regex("""\(\s*\(\s*LivingEntity\s*\)\s*${Regex.escape(variable)}\s*\)""")) { variable }
    }

    val vectorExecutableCode = maskJavaCommentsAndLiterals(result)
    if (vectorExecutableCode.contains("Vector3f")) {
        val vectorVariables = Regex("""\b(?:org\.joml\.)?Vector3f\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(vectorExecutableCode)
            .map { it.groupValues[1] }
            .toSet()
        val vectorArrays = Regex("""\b(?:org\.joml\.)?Vector3f\s*\[\s*]\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(vectorExecutableCode)
            .map { it.groupValues[1] }
            .toSet()
        val vectorReceivers = (vectorVariables + vectorArrays).joinToString("|") { Regex.escape(it) }
        if (vectorReceivers.isNotBlank()) {
            result = replaceExecutableRegex(
                result,
                Regex("""\(float\)\s*\(\s*(($vectorReceivers)(?:\s*\[[^]]+])?\.(?:x|y|z)\(\))\s*\)""")
            ) { match -> match.groupValues[1] }
        }
    }

    return result
}

internal fun StructuralRefactorPass.migrateBlockDefaultStateThisEscapeWarning(source: String): String {
    if (!source.contains("registerDefaultState(this.getStateDefinition()")) return source
    val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return source
    return addSuppressWarningToConstructorsContaining(
        source,
        className,
        "this-escape",
        listOf("registerDefaultState(this.getStateDefinition()")
    )
}

internal fun StructuralRefactorPass.migrateConstructorThisMethodThisEscapeWarning(source: String): String {
    val thisMethodCall = Regex("""\bthis\.[A-Za-z_$][\w$]*\s*\(""")
    val unqualifiedAccessorCall = Regex("""(?<![.\w$])(?:get|is|has|can|should|set|add|register)[A-Z][A-Za-z_$\d]*\s*\(""")
    val thisAsArgument = Regex("""\(\s*this\s*(?:,|\))|,\s*this\s*(?:,|\))""")
    val thisMethodReference = Regex("""\bthis::[A-Za-z_$][\w$]*\b""")
    if (!thisMethodCall.containsMatchIn(source) &&
        !unqualifiedAccessorCall.containsMatchIn(source) &&
        !thisAsArgument.containsMatchIn(source) &&
        !thisMethodReference.containsMatchIn(source)) return source
    val classNames = javaClassNames(source)
    if (classNames.isEmpty()) return source
    var result = source
    classNames.forEach { className ->
        result = addSuppressWarningToConstructorsMatching(result, className, "this-escape") { constructorText ->
            thisMethodCall.containsMatchIn(constructorText) ||
                unqualifiedAccessorCall.containsMatchIn(constructorText) ||
                thisAsArgument.containsMatchIn(constructorText) ||
                thisMethodReference.containsMatchIn(constructorText)
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateFieldInitializerThisMethodThisEscapeWarning(source: String): String {
    val thisMethodCall = Regex("""\bthis\.[A-Za-z_$][\w$]*\s*\(""")
    val unqualifiedAccessorCall = Regex("""(?<![.\w$])(?:get|is|has|can|should|set|add|register)[A-Z][A-Za-z_$\d]*\s*\(""")
    if (!thisMethodCall.containsMatchIn(source) && !unqualifiedAccessorCall.containsMatchIn(source)) return source
    val linePattern = Regex("""^[ \t]*(?:public|protected|private)\s+""")
    val starts = mutableListOf<Int>()
    var offset = 0
    for (line in source.splitToSequence('\n')) {
        val trimmed = line.trim()
        if (linePattern.containsMatchIn(line) &&
            !Regex("""\bstatic\b""").containsMatchIn(trimmed.substringBefore('=')) &&
            trimmed.contains("=") &&
            trimmed.endsWith(";") &&
            (thisMethodCall.containsMatchIn(trimmed) || unqualifiedAccessorCall.containsMatchIn(trimmed))) {
            starts.add(offset)
        }
        offset += line.length + 1
    }
    var result = source
    for (start in starts.asReversed()) {
        result = addSuppressWarningToElementAt(result, start, "this-escape")
    }
    return result
}

internal fun StructuralRefactorPass.migrateClassLevelThisEscapeFallback(source: String): String {
    if (source.contains("\"this-escape\"")) return source
    val thisMethodCall = Regex("""\bthis\.[A-Za-z_$][\w$]*\s*\(""")
    val thisAsArgument = Regex("""\(\s*this\s*(?:,|\))|,\s*this\s*(?:,|\))""")
    val thisMethodReference = Regex("""\bthis::[A-Za-z_$][\w$]*\b""")
    val unqualifiedAccessorCall = Regex("""(?<![.\w$])(?:get|is|has|can|should)[A-Z][A-Za-z_$\d]*\s*\(""")
    val fieldEscape = source.lineSequence().any { line ->
        val trimmed = line.trim()
        Regex("""^(?:public|protected|private)\s+""").containsMatchIn(trimmed) &&
            !Regex("""\bstatic\b""").containsMatchIn(trimmed.substringBefore('=')) &&
            trimmed.contains("=") &&
            trimmed.endsWith(";") &&
            (thisMethodCall.containsMatchIn(trimmed) || unqualifiedAccessorCall.containsMatchIn(trimmed))
    }
    val constructorEscape = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\s*\r?\n[ \t]*)*(?:public|protected|private)\s+[A-Z][A-Za-z_$\d]*\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
    ).findAll(source).any { match ->
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        closeBrace > openBrace &&
            source.substring(openBrace + 1, closeBrace).let { body ->
                thisMethodCall.containsMatchIn(body) ||
                    unqualifiedAccessorCall.containsMatchIn(body) ||
                    thisAsArgument.containsMatchIn(body) ||
                    thisMethodReference.containsMatchIn(body)
            }
    }
    if (!fieldEscape && !constructorEscape) return source
    val classDeclaration = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\s*\r?\n[ \t]*)*(?:public\s+)?(?:abstract\s+|final\s+)?class\s+[A-Za-z_$][\w$]*\b"""
    ).find(source) ?: return source
    return addSuppressWarningToElementAt(source, classDeclaration.range.first, "this-escape")
}

internal fun StructuralRefactorPass.migrateCuriosHelperRemovalApis(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("CuriosApi.getCuriosHelper()")) return source
    var result = source
    result = rewriteExecutableJavaCall(result, "getEquippedCurios") { receiver, args ->
        if (receiver != "CuriosApi.getCuriosHelper()" || args.size != 1) return@rewriteExecutableJavaCall null
        "CuriosApi.getCuriosInventory(${args[0].trim()}).map(handler -> handler.getEquippedCurios())"
    }
    result = rewriteExecutableJavaCall(result, "findFirstCurio") { receiver, args ->
        if (receiver != "CuriosApi.getCuriosHelper()" || args.size != 2) return@rewriteExecutableJavaCall null
        "CuriosApi.getCuriosInventory(${args[0].trim()}).flatMap(handler -> handler.findFirstCurio(${args[1].trim()}))"
    }
    return result
}

internal fun StructuralRefactorPass.migrateCuriosInventoryOptionalTypes(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("CuriosApi.getCuriosInventory") || !executableCode.contains("LazyOptional")) return source

    val before = source
    var result = replaceExecutableRegex(
        source,
        Regex("""\b(?:net\.neoforged\.neoforge\.common\.util\.)?LazyOptional\s*<\s*ICuriosItemHandler\s*>""")
    ) { "Optional<ICuriosItemHandler>" }
    if (result == before) return source
    val executableResult = maskJavaCommentsAndLiterals(result)
    val optionalCuriosVariables = Regex("""\bOptional\s*<\s*ICuriosItemHandler\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableResult)
        .map { it.groupValues[1] }
        .toSet()
    optionalCuriosVariables.forEach { variable ->
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(variable)}\.resolve\(\)""")) { variable }
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(variable)}\.isPresent\(\)\s*&&\s*${Regex.escape(variable)}\.isPresent\(\)""")
        ) { "$variable.isPresent()" }
    }
    result = removeImport(result, "net.neoforged.neoforge.common.util.LazyOptional")
    result = addImportIfMissing(result, "java.util.Optional")
    return result
}

internal fun StructuralRefactorPass.migrateCuriosInventoryDirectResolveCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("CuriosApi.getCuriosInventory") || !executableCode.contains(".resolve()")) return source
    return replaceExecutableRegex(
        source,
        Regex("""CuriosApi\.getCuriosInventory\(([^;\r\n{}]+)\)\s*\.\s*resolve\(\s*\)""")
    ) { match ->
        "CuriosApi.getCuriosInventory(${match.groupValues[1].trim()})"
    }
}

internal fun StructuralRefactorPass.migrateCuriosAttributeModifierHolderTypes(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getAttributeModifiers") ||
        !executableCode.contains("AttributeModifier") ||
        !executableCode.contains("Multimap") ||
        !executableCode.contains("Attribute") ||
        !Regex("""\b(?:ICurioItem|SlotContext)\b""").containsMatchIn(executableCode)) {
        return source
    }

    var result = source
    result = replaceExecutableRegex(
        result,
        Regex("""\bMultimap\s*<\s*Attribute\s*,\s*AttributeModifier\s*>""")
    ) { "Multimap<Holder<Attribute>, AttributeModifier>" }
    result = replaceExecutableRegex(
        result,
        Regex("""\bImmutableMultimap\.Builder\s*<\s*Attribute\s*,\s*AttributeModifier\s*>""")
    ) { "ImmutableMultimap.Builder<Holder<Attribute>, AttributeModifier>" }
    result = replaceExecutableRegex(
        result,
        Regex("""\bImmutableMultimap\s*<\s*Attribute\s*,\s*AttributeModifier\s*>""")
    ) { "ImmutableMultimap<Holder<Attribute>, AttributeModifier>" }
    result = wrapRawAttributeModifierMapKeys(result)
    result = unwrapHolderDirectAttributeConstants(result)

    if (result == source) return source
    return addExecutableImportIfMissing(result, "net.minecraft.core.Holder")
}

internal fun StructuralRefactorPass.migrateCaelusApi121(source: String): String {
    if (!source.contains("CaelusApi") && !source.contains("top.theillusivec4.caelus")) return source
    var result = source
        .replace(
            "import top.theillusivec4.caelus.api.CaelusApi;",
            "import com.illusivesoulworks.caelus.api.CaelusApi;"
        )
        .replace(
            "top.theillusivec4.caelus.api.CaelusApi",
            "com.illusivesoulworks.caelus.api.CaelusApi"
        )
    result = replaceExecutableRegex(
        result,
        Regex("""((?:(?:com\.illusivesoulworks\.caelus\.api\.)?CaelusApi)\.getInstance\(\))\.getFlightAttribute\(\)""")
    ) { match ->
        "${match.groupValues[1]}.getFallFlyingAttribute()"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(?:net\.minecraft\.core\.)?Holder\.direct\(\s*(((?:com\.illusivesoulworks\.caelus\.api\.)?CaelusApi)\.getInstance\(\)\.getFallFlyingAttribute\(\))\s*\)""")
    ) { match ->
        match.groupValues[1]
    }
    return result
}

internal fun StructuralRefactorPass.migrateAttributeModifierMultimapHolderTypes(source: String): String {
    if (!source.contains("AttributeModifier") ||
        !source.contains("Attributes.") ||
        (!source.contains("Multimap<Attribute") && !source.contains("ImmutableMultimap"))) {
        return source
    }

    var result = source
    result = Regex("""\bMultimap\s*<\s*Attribute\s*,\s*AttributeModifier\s*>""")
        .replace(result, "Multimap<Holder<Attribute>, AttributeModifier>")
    result = Regex("""\bImmutableMultimap\.Builder\s*<\s*Attribute\s*,\s*AttributeModifier\s*>""")
        .replace(result, "ImmutableMultimap.Builder<Holder<Attribute>, AttributeModifier>")
    result = Regex("""\bImmutableMultimap\s*<\s*Attribute\s*,\s*AttributeModifier\s*>""")
        .replace(result, "ImmutableMultimap<Holder<Attribute>, AttributeModifier>")
    result = wrapRawAttributeModifierMapKeys(result)
    result = unwrapHolderDirectAttributeConstants(result)

    if (result == source) return source
    return addImportIfMissing(result, "net.minecraft.core.Holder")
}

internal fun StructuralRefactorPass.wrapRawAttributeModifierMapKeys(source: String): String {
    if (!source.contains("Multimap<Holder<Attribute>, AttributeModifier>") &&
        !source.contains("ImmutableMultimap.Builder<Holder<Attribute>, AttributeModifier>") &&
        !source.contains("ImmutableMultimap<Holder<Attribute>, AttributeModifier>")) {
        return source
    }
    return replaceExecutableRegex(
        source,
        Regex("""\b([A-Za-z_$][\w$]*)\.put\(\s*([^,\r\n;]+?)\s*,\s*new\s+AttributeModifier\(""")
    ) { match ->
        val key = match.groupValues[2].trim()
        if (isHolderAttributeExpression(key)) {
            match.value
        } else {
            "${match.groupValues[1]}.put(Holder.direct($key), new AttributeModifier("
        }
    }
}

internal fun StructuralRefactorPass.isHolderAttributeExpression(expression: String): Boolean {
    val normalized = expression.replace(Regex("""\s+"""), "")
    return normalized.startsWith("Holder.direct(") ||
        normalized.startsWith("net.minecraft.core.Holder.direct(") ||
        normalized.startsWith("Attributes.") ||
        normalized.startsWith("net.minecraft.world.entity.ai.attributes.Attributes.") ||
        Regex("""^(?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.[A-Z0-9_]+$""").matches(normalized) ||
        normalized.contains(".getHolderOrThrow(") ||
        normalized.contains(".getOrThrow(") ||
        normalized.contains("BuiltInRegistries.ATTRIBUTE")
}

internal fun StructuralRefactorPass.unwrapHolderDirectAttributeConstants(source: String): String =
    replaceExecutableRegex(
        source,
        Regex("""\bHolder\.direct\(\s*((?:net\.minecraft\.world\.entity\.ai\.attributes\.)?Attributes\.[A-Z0-9_]+)\s*\)""")
    ) { match ->
        match.groupValues[1]
    }

internal fun StructuralRefactorPass.migrateLegacyItemAttributeModifierOverrides(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getAttributeModifiers") ||
        !executableCode.contains("EquipmentSlot") ||
        !executableCode.contains("ItemStack") ||
        !executableCode.contains("AttributeModifier")) {
        return source
    }

    var result = source
    var changed = false
    val declarationPattern = Regex(
        """public\s+Multimap[^\r\n]+getAttributeModifiers\s*\(\s*EquipmentSlot\s+([A-Za-z_$][\w$]*)\s*,\s*ItemStack\s+([A-Za-z_$][\w$]*)\s*\)"""
    )
    val declarationMatch = declarationPattern.find(result)
    if (declarationMatch != null && result.contains("ImmutableMultimap.builder()")) {
        val slotParam = declarationMatch.groupValues[1]
        val stackParam = declarationMatch.groupValues[2]
        val builderName = Regex("""ImmutableMultimap\.Builder[^\r\n;]+\s+([A-Za-z_$][\w$]*)\s*=\s*ImmutableMultimap\.builder\(\)\s*;""")
            .find(result)
            ?.groupValues
            ?.get(1)
        if (builderName != null && result.contains("$builderName.putAll(") && result.contains("return $builderName.build();")) {
            result = declarationPattern.replace(result, "public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack $stackParam)")
            result = Regex("""ImmutableMultimap\.Builder[^\r\n;]+\s+${Regex.escape(builderName)}\s*=\s*ImmutableMultimap\.builder\(\)\s*;""")
                .replace(result, "ItemAttributeModifiers modifiers = super.getDefaultAttributeModifiers($stackParam);")
            result = Regex("""${Regex.escape(builderName)}\.putAll\(\s*((?:this|super))\.getDefaultAttributeModifiers\(\s*${Regex.escape(slotParam)}\s*\)\s*\)\s*;""")
                .replace(result, "")
            listOf("MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET", "BODY").forEach { slotConstant ->
                result = Regex(
                    """if\s*\(\s*${Regex.escape(slotParam)}\s*==\s*EquipmentSlot\.${Regex.escape(slotConstant)}\s*\)\s*\{([^{}]*\b${Regex.escape(builderName)}\.put\([^{}]*)\}""",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                ).replace(result) { guarded ->
                    rewriteAttributeModifierBuilderPuts(guarded.groupValues[1], builderName, slotConstant)
                }
                result = Regex(
                    """if\s*\(\s*EquipmentSlot\.${Regex.escape(slotConstant)}\s*==\s*${Regex.escape(slotParam)}\s*\)\s*\{([^{}]*\b${Regex.escape(builderName)}\.put\([^{}]*)\}""",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                ).replace(result) { guarded ->
                    rewriteAttributeModifierBuilderPuts(guarded.groupValues[1], builderName, slotConstant)
                }
            }
            result = rewriteAttributeModifierBuilderPuts(result, builderName, "ANY")
            result = Regex("""return\s+${Regex.escape(builderName)}\.build\(\)\s*;""").replace(result, "return modifiers;")
            result = migrateResidualItemAttributeSlotConditions(result, slotParam)
            changed = true
        }
    }
    result = Regex(
        """(?ms)^([ \t]*)@Override\s*\r?\n\1public\s+Multimap\s*<[^;\r\n]+>\s+getAttributeModifiers\s*\(\s*EquipmentSlot\s+([A-Za-z_$][\w$]*)\s*,\s*ItemStack\s+([A-Za-z_$][\w$]*)\s*\)\s*\{.*?^\1\}"""
    ).replace(result) { match ->
        val methodText = match.value
        val slotParam = match.groupValues[2]
        val stackParam = match.groupValues[3]
        val builderName = Regex("""\bImmutableMultimap\.Builder\s*<[^;]+>\s+([A-Za-z_$][\w$]*)\s*=\s*ImmutableMultimap\.builder\(\)\s*;""")
            .find(methodText)
            ?.groupValues
            ?.get(1)
            ?: return@replace methodText
        if (!methodText.contains("$builderName.putAll(") || !methodText.contains("return $builderName.build();")) {
            return@replace methodText
        }
        var migratedMethod = Regex(
            """\bMultimap\s*<[^;\r\n]+>\s+getAttributeModifiers\s*\([^)]*\)"""
        ).replace(methodText, "ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack $stackParam)")
        migratedMethod = Regex(
            """\bImmutableMultimap\.Builder\s*<[^;\r\n]+>\s+${Regex.escape(builderName)}\s*=\s*ImmutableMultimap\.builder\(\)\s*;"""
        ).replace(migratedMethod, "ItemAttributeModifiers modifiers = super.getDefaultAttributeModifiers($stackParam);")
        migratedMethod = Regex(
            """\b${Regex.escape(builderName)}\.putAll\(\s*((?:this|super))\.getDefaultAttributeModifiers\(\s*${Regex.escape(slotParam)}\s*\)\s*\)\s*;"""
        ).replace(migratedMethod, "")
        val slotGroups = mapOf(
            "MAINHAND" to "MAINHAND",
            "OFFHAND" to "OFFHAND",
            "HEAD" to "HEAD",
            "CHEST" to "CHEST",
            "LEGS" to "LEGS",
            "FEET" to "FEET",
            "BODY" to "BODY"
        )
        for ((slotConstant, groupConstant) in slotGroups) {
            migratedMethod = Regex(
                """if\s*\(\s*${Regex.escape(slotParam)}\s*==\s*EquipmentSlot\.${Regex.escape(slotConstant)}\s*\)\s*\{([^{}]*\b${Regex.escape(builderName)}\.put\([^{}]*)\}""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ).replace(migratedMethod) { guarded ->
                rewriteAttributeModifierBuilderPuts(guarded.groupValues[1], builderName, groupConstant)
            }
        }
        migratedMethod = rewriteAttributeModifierBuilderPuts(migratedMethod, builderName, "ANY")
        migratedMethod = Regex("""return\s+${Regex.escape(builderName)}\.build\(\)\s*;""")
            .replace(migratedMethod, "return modifiers;")
        if (Regex("""\b${Regex.escape(slotParam)}\b""").containsMatchIn(maskJavaCommentsAndLiterals(migratedMethod))) {
            methodText
        } else {
            changed = true
            migrateResidualItemAttributeSlotConditions(migratedMethod, slotParam)
        }
    }
    for (methodRange in legacyItemAttributeModifierOverrideRanges(result).asReversed()) {
        val methodText = result.substring(methodRange)
        val params = javaMethodParameters(methodText)
        val slotParam = params.singleOrNull { simpleJavaTypeName(it.type) == "EquipmentSlot" }?.name ?: continue
        val stackParam = params.singleOrNull { simpleJavaTypeName(it.type) == "ItemStack" }?.name ?: continue
        if (!Regex("""\b(?:Multimap|ImmutableMultimap)\s*<[^{};]+>\s+getAttributeModifiers\s*\(""")
                .containsMatchIn(methodText)) {
            continue
        }

        var migrated = Regex(
            """\b(?:Multimap|ImmutableMultimap)\s*<[^{};]+>\s+getAttributeModifiers\s*\([^)]*\)"""
        ).replace(methodText, "ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack $stackParam)")

        val builderNames = linkedSetOf<String>()
        migrated = Regex(
            """\b(?:ImmutableMultimap\.Builder|Multimap)\s*<[^;\r\n]+>\s+([A-Za-z_$][\w$]*)\s*=\s*(?:ImmutableMultimap\.builder\(\)|ArrayListMultimap\.create\(\))\s*;"""
        ).replace(migrated) { match ->
            val builder = match.groupValues[1]
            builderNames += builder
            "ItemAttributeModifiers modifiers = super.getDefaultAttributeModifiers($stackParam);"
        }
        if (builderNames.isEmpty()) continue
        if (builderNames.size != 1) continue

        for (builder in builderNames) {
            migrated = Regex(
                """\b${Regex.escape(builder)}\.putAll\(\s*((?:this|super))\.getDefaultAttributeModifiers\(\s*${Regex.escape(slotParam)}\s*\)\s*\)\s*;"""
            ).replace(migrated, "")
            val slotGroups = mapOf(
                "MAINHAND" to "MAINHAND",
                "OFFHAND" to "OFFHAND",
                "HEAD" to "HEAD",
                "CHEST" to "CHEST",
                "LEGS" to "LEGS",
                "FEET" to "FEET",
                "BODY" to "BODY"
            )
            for ((slotConstant, groupConstant) in slotGroups) {
                migrated = Regex(
                    """if\s*\(\s*${Regex.escape(slotParam)}\s*==\s*EquipmentSlot\.${Regex.escape(slotConstant)}\s*\)\s*\{([^{}]*\b${Regex.escape(builder)}\.put\([^{}]*)\}""",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                ).replace(migrated) { match ->
                    rewriteAttributeModifierBuilderPuts(match.groupValues[1], builder, groupConstant)
                }
            }
            migrated = rewriteAttributeModifierBuilderPuts(migrated, builder, "ANY")
            migrated = Regex("""return\s+${Regex.escape(builder)}\.build\(\)\s*;""")
                .replace(migrated, "return modifiers;")
        }

        if (Regex("""\b${Regex.escape(slotParam)}\b""").containsMatchIn(maskJavaCommentsAndLiterals(migrated))) {
            continue
        }
        if (migrated == methodText) continue
        result = result.replaceRange(methodRange, migrated)
        changed = true
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.world.entity.EquipmentSlotGroup")
    result = addImportIfMissing(result, "net.minecraft.world.item.component.ItemAttributeModifiers")
    if (!Regex("""\bImmutableMultimap\b""").containsMatchIn(removeImport(result, "com.google.common.collect.ImmutableMultimap"))) {
        result = removeImport(result, "com.google.common.collect.ImmutableMultimap")
    }
    if (!Regex("""\bMultimap\b""").containsMatchIn(removeImport(result, "com.google.common.collect.Multimap"))) {
        result = removeImport(result, "com.google.common.collect.Multimap")
    }
    return result
}

internal fun StructuralRefactorPass.migrateResidualItemAttributeSlotConditions(source: String, slotParam: String): String {
    var result = source
    listOf("MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET", "BODY").forEach { slotConstant ->
        val slot = Regex.escape(slotParam)
        val constant = Regex.escape(slotConstant)
        val guardedWithCondition = Regex(
            """\b(if|else\s+if)\s*\(\s*$slot\s*==\s*EquipmentSlot\.$constant\s*&&\s*([^{]+?)\)\s*\{([^{}]*)\}""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        result = guardedWithCondition.replace(result) { match ->
            val keyword = match.groupValues[1]
            val condition = match.groupValues[2].trim()
            val body = match.groupValues[3].replace("EquipmentSlotGroup.ANY", "EquipmentSlotGroup.$slotConstant")
            "$keyword ($condition) {$body}"
        }
        val guardedOnly = Regex(
            """\b(if|else\s+if)\s*\(\s*$slot\s*==\s*EquipmentSlot\.$constant\s*\)\s*\{([^{}]*)\}""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        result = guardedOnly.replace(result) { match ->
            val keyword = match.groupValues[1]
            val body = match.groupValues[2].replace("EquipmentSlotGroup.ANY", "EquipmentSlotGroup.$slotConstant")
            if (keyword.startsWith("else")) {
                "else {$body}"
            } else {
                "{$body}"
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.legacyItemAttributeModifierOverrideRanges(source: String): List<IntRange> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val ranges = mutableListOf<IntRange>()
    var cursor = 0
    val token = "getAttributeModifiers"
    while (true) {
        val nameIndex = executableCode.indexOf(token, cursor)
        if (nameIndex < 0) break
        val openParen = executableCode.indexOf('(', nameIndex + token.length)
        if (openParen != nameIndex + token.length) {
            cursor = nameIndex + token.length
            continue
        }
        val closeParen = findMatchingParen(executableCode, openParen)
        val openBrace = if (closeParen >= 0) executableCode.indexOf('{', closeParen) else -1
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (closeParen < 0 || openBrace < 0 || closeBrace < 0) {
            cursor = nameIndex + token.length
            continue
        }
        val lineStart = executableCode.lastIndexOf('\n', nameIndex).let { if (it < 0) 0 else it + 1 }
        val headerPrefix = executableCode.substring(lineStart, nameIndex)
        if (!Regex("""\b(?:public|protected|private)\b""").containsMatchIn(headerPrefix) ||
            !Regex("""\b(?:Multimap|ImmutableMultimap)\s*<""").containsMatchIn(headerPrefix)) {
            cursor = closeBrace + 1
            continue
        }
        val start = annotationStartBefore(source, lineStart)
        ranges += start..closeBrace
        cursor = closeBrace + 1
    }
    return ranges
}

internal fun StructuralRefactorPass.rewriteAttributeModifierBuilderPuts(source: String, builder: String, slotGroup: String): String =
    rewriteExecutableJavaCall(source, "put") { receiver, args ->
        if (receiver.trim() != builder || args.size != 2) return@rewriteExecutableJavaCall null
        "modifiers = modifiers.withModifierAdded(${args[0].trim()}, ${args[1].trim()}, EquipmentSlotGroup.$slotGroup)"
    }

internal fun StructuralRefactorPass.migrateJeiRecipeCategoryBackgroundApi(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("IRecipeCategory<") || !executableCode.contains("getBackground()")) return source
    val backgroundMethod = javaMethodRanges(executableCode).singleOrNull { method ->
        method.name == "getBackground" &&
            Regex("""\bIDrawable\s+getBackground\s*\(\s*\)""").containsMatchIn(method.header)
    } ?: return source
    val parsedUnit = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    ).parse(source).result.orElse(null) ?: return source
    val backgroundDeclaration = parsedUnit.findAll(MethodDeclaration::class.java).singleOrNull { method ->
        method.nameAsString == "getBackground" &&
            method.parameters.isEmpty() &&
            method.type.toString().substringAfterLast('.') == "IDrawable"
    } ?: return source
    val backgroundOwner = backgroundDeclaration
        .findAncestor(ClassOrInterfaceDeclaration::class.java)
        .orElse(null)
        ?: return source
    // JEI 19 still exposes getBackground. Abstract category bases keep that source/API contract;
    // concrete categories alone are flattened to width, height, and draw methods.
    if (backgroundOwner.isAbstract) return source
    val executableBackgroundMethodText = executableCode.substring(backgroundMethod.range)
    val backgroundField = Regex("""return\s+(?:this\.)?([A-Za-z_$][\w$]*)\s*;""")
        .find(executableBackgroundMethodText)
        ?.groupValues
        ?.get(1)
        ?: return source
    if (!Regex("""\bIDrawable\s+$backgroundField\b""").containsMatchIn(executableCode)) return source

    var result = source.removeRange(backgroundMethod.range)
    val sizeMethods = """

	@Override
	public int getWidth() {
		return this.$backgroundField.getWidth();
	}

	@Override
	public int getHeight() {
		return this.$backgroundField.getHeight();
	}
""".trimEnd()
    var resultExecutableCode = maskJavaCommentsAndLiterals(result)
    if (!Regex("""\bint\s+getWidth\s*\(""").containsMatchIn(resultExecutableCode)) {
        val iconMethod = javaMethodRanges(resultExecutableCode).firstOrNull { it.name == "getIcon" }
        result = iconMethod
            ?.let { result.replaceRange(it.range, sizeMethods + "\n\n" + result.substring(it.range)) }
            ?: insertBeforeLastClassBrace(result, "\n$sizeMethods\n")
        resultExecutableCode = maskJavaCommentsAndLiterals(result)
    }

    val drawMethod = javaMethodRanges(resultExecutableCode).firstOrNull { it.name == "draw" }
    result = if (drawMethod != null) {
        val drawMethodText = result.substring(drawMethod.range)
        val executableDrawMethodText = resultExecutableCode.substring(drawMethod.range)
        val graphicsName = Regex("""\bGuiGraphics\s+([A-Za-z_$][\w$]*)\b""")
            .find(executableDrawMethodText)
            ?.groupValues
            ?.get(1)
        if (graphicsName != null && !executableDrawMethodText.contains("$backgroundField.draw($graphicsName)")) {
            val openBrace = result.indexOf('{', drawMethod.range.first)
            if (openBrace >= 0 && openBrace <= drawMethod.range.last) {
                val insertAt = openBrace + 1
                val indent = Regex("""(?m)^([ \t]*)[^\r\n]*\{""")
                    .find(drawMethodText)
                    ?.groupValues
                    ?.get(1)
                    ?.plus("\t")
                    ?: "\t\t"
                result.substring(0, insertAt) +
                    "\n${indent}this.$backgroundField.draw($graphicsName);" +
                    result.substring(insertAt)
            } else {
                result
            }
        } else {
            result
        }
    } else {
        val recipeType = Regex("""implements\s+IRecipeCategory\s*<\s*([^>{}]+)\s*>""")
            .find(resultExecutableCode)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: "Object"
        val draw = """

	@Override
	public void draw($recipeType recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics graphics, double mouseX, double mouseY) {
		this.$backgroundField.draw(graphics);
	}
""".trimEnd()
        resultExecutableCode = maskJavaCommentsAndLiterals(result)
        val setRecipeMethod = javaMethodRanges(resultExecutableCode).firstOrNull { it.name == "setRecipe" }
        result = setRecipeMethod
            ?.let { result.replaceRange(it.range, draw + "\n\n" + result.substring(it.range)) }
            ?: insertBeforeLastClassBrace(result, "\n$draw\n")
        result = addImportIfMissing(result, "mezz.jei.api.gui.ingredient.IRecipeSlotsView")
        result = addImportIfMissing(result, "net.minecraft.client.gui.GuiGraphics")
        result
    }

    return result
}

internal fun StructuralRefactorPass.migrateRequiredRemovalWarningAnnotations(source: String): String =
    addSuppressWarningToMethodsContaining(source, "removal") { methodText ->
        methodText.contains("initializeClient(Consumer<IClientItemExtensions>") ||
            methodText.contains("initializeClient(Consumer<IClientBlockExtensions>") ||
            methodText.contains("removeFromBlockToItemMap(") ||
            (methodText.contains("getTooltip(") && methodText.contains("TooltipFlag")) ||
            (methodText.contains("getUniqueId(") && methodText.contains("UidContext")) ||
            (methodText.contains("IModIngredientRegistration") && methodText.contains(".register("))
    }

internal fun StructuralRefactorPass.migrateFinalLivingEntityDimensionsOverrides(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("getDimensions(Pose") || !source.contains("@Override")) return source

    var result = source
    var cursor = 0
    val signaturePattern = Regex("""\b(public|protected)\s+EntityDimensions\s+getDimensions\s*\(\s*Pose\s+([A-Za-z_$][\w$]*)\s*\)""")
    while (true) {
        val match = signaturePattern.find(result, cursor) ?: break
        val classDeclaration = enclosingJavaClassDeclaration(result, match.range.first)
        if (!javaClassExtendsAny(classDeclaration, javaLivingEntityBaseTypes(), javaInheritanceIndex)) {
            cursor = match.range.last + 1
            continue
        }
        val openBrace = result.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val signature = result.substring(match.range.first, openBrace)
        val rewrittenSignature = signaturePattern.replace(signature) { signatureMatch ->
            "${signatureMatch.groupValues[1]} EntityDimensions getDefaultDimensions(Pose ${signatureMatch.groupValues[2]})"
        }
        val rewrittenBody = result.substring(openBrace, closeBrace + 1)
            .replace("super.getDimensions(", "super.getDefaultDimensions(")
        result = result.substring(0, match.range.first) +
            rewrittenSignature +
            rewrittenBody +
            result.substring(closeBrace + 1)
        cursor = match.range.first + rewrittenSignature.length + rewrittenBody.length
    }
    return result
}

internal fun StructuralRefactorPass.migrateSaddleableEquipSaddleSignature(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("equipSaddle(") || !executableCode.contains("SoundSource")) return source
    var changed = false
    val result = replaceExecutableRegex(
        source,
        Regex("""public\s+void\s+equipSaddle\(\s*((?:@(?:[\w.]+\.)?Nullable\s+)?)((?:net\.minecraft\.sounds\.)?SoundSource)\s+([A-Za-z_$][\w$]*)\s*\)""")
    ) { match ->
        changed = true
        val nullable = match.groupValues[1]
        val soundType = match.groupValues[2]
        val soundName = match.groupValues[3]
        "public void equipSaddle(ItemStack stack, $nullable$soundType $soundName)"
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.world.item.ItemStack") else source
}

internal fun StructuralRefactorPass.migrateMthTrigonometryFloatArguments(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("Mth.sin(") && !executableCode.contains("Mth.cos(")) return source
    val methodPattern = Regex("""(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?[ \t]+)*(?:public|protected|private)?[ \t]*(?:(?:static|final|synchronized|strictfp)[ \t]+)*(?:<[^;{}()]+>[ \t]+)?[\w<>\[\].?, \t]+[ \t]+[A-Za-z_$][\w$]*[ \t]*\([^;{}]*\)[ \t]*(?:throws[^{;]+)?\{""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    fun addExecutableEdit(methodRange: IntRange, match: MatchResult, replacement: String) {
        val absoluteRange = (methodRange.first + match.range.first)..(methodRange.first + match.range.last)
        if (executableCode.substring(absoluteRange) == match.value &&
            source.substring(absoluteRange) == match.value) {
            edits += absoluteRange to replacement
        }
    }
    var cursor = 0
    while (true) {
        val method = methodPattern.find(executableCode, cursor) ?: break
        val openBrace = executableCode.indexOf('{', method.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = method.range.last + 1
            continue
        }
        val methodRange = method.range.first..closeBrace
        val methodExecutableText = executableCode.substring(methodRange)
        val doubleVariables = Regex("""\bdouble\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(methodExecutableText)
            .map { it.groupValues[1] }
            .toSet()
        val floatVariables = Regex("""\bfloat\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(methodExecutableText)
            .map { it.groupValues[1] }
            .toSet()
        for (variable in doubleVariables - floatVariables) {
            val escaped = Regex.escape(variable)
            Regex("""\bMth\.sin\(\s*$escaped\s*\)""")
                .findAll(methodExecutableText)
                .forEach { match ->
                    addExecutableEdit(methodRange, match, "Mth.sin((float) $variable)")
                }
            Regex("""\bMth\.cos\(\s*$escaped\s*\)""")
                .findAll(methodExecutableText)
                .forEach { match ->
                    addExecutableEdit(methodRange, match, "Mth.cos((float) $variable)")
                }
        }
        for (variable in floatVariables) {
            val escaped = Regex.escape(variable)
            Regex("""\bMth\.sin\(\s*\(float\)\s*$escaped\s*\)""")
                .findAll(methodExecutableText)
                .forEach { match ->
                    addExecutableEdit(methodRange, match, "Mth.sin($variable)")
                }
            Regex("""\bMth\.cos\(\s*\(float\)\s*$escaped\s*\)""")
                .findAll(methodExecutableText)
                .forEach { match ->
                    addExecutableEdit(methodRange, match, "Mth.cos($variable)")
                }
        }
        cursor = closeBrace + 1
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateMapDecorationRecordSource(source: String): String {
    return migrateLegacyMapDecorationRecordContracts(source)
}

internal fun StructuralRefactorPass.migrateLegacyModelRenderPackedColorBodies(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("renderToBuffer(") &&
        !executableCode.contains(".render(") &&
        !executableCode.contains("renderColoredCutoutModel(")) {
        return source
    }
    var result = source
    var needsFastColor = false

    result = rewriteExecutableJavaInvocationArguments(result, "renderColoredCutoutModel") { args ->
        if (args.size != 9) return@rewriteExecutableJavaInvocationArguments null
        needsFastColor = true
        args.take(6).map { it.trim() } +
            "FastColor.ARGB32.colorFromFloat(1.0F, ${args[6].trim()}, ${args[7].trim()}, ${args[8].trim()})"
    }

    result = rewriteExecutableJavaCall(result, "render") { receiver, args ->
        if (args.size != 8) return@rewriteExecutableJavaCall null
        if (!isLegacyModelPartColorRenderArgs(args)) return@rewriteExecutableJavaCall null
        needsFastColor = true
        "$receiver.render(${args[0]}, ${args[1]}, ${args[2]}, ${args[3]}, FastColor.ARGB32.colorFromFloat(${args[7]}, ${args[4]}, ${args[5]}, ${args[6]}))"
    }
    result = rewriteExecutableJavaCall(result, "renderToBuffer") { receiver, args ->
        if (args.size != 8) return@rewriteExecutableJavaCall null
        if (!isLegacyModelPartColorRenderArgs(args)) return@rewriteExecutableJavaCall null
        needsFastColor = true
        "$receiver.renderToBuffer(${args[0]}, ${args[1]}, ${args[2]}, ${args[3]}, FastColor.ARGB32.colorFromFloat(${args[7]}, ${args[4]}, ${args[5]}, ${args[6]}))"
    }

    var cursor = 0
    val signature = Regex("""renderToBuffer\s*\([^)]*\bint\s+color\s*\)\s*\{""")
    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val match = signature.find(executableResult, cursor) ?: break
        val openBrace = executableResult.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableResult, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val unpackLocals = linkedMapOf<String, String>()
        fun addUnpackLocal(name: String, expression: String) {
            if (Regex("""[A-Za-z_$][\w$]*""").matches(name) &&
                name != "color" &&
                !Regex("""\b(?:float|int)\s+${Regex.escape(name)}\b""").containsMatchIn(body)) {
                unpackLocals.putIfAbsent(name, expression)
            }
        }
        Regex(
            """FastColor\.ARGB32\.colorFromFloat\(\s*([^,()]+)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)"""
        ).findAll(body).forEach { colorMatch ->
            val alphaExpression = colorMatch.groupValues[1].trim()
            addUnpackLocal(alphaExpression, "FastColor.ARGB32.alpha(color) / 255.0F")
            Regex("""\b(alpha|scale|a)\b""").findAll(alphaExpression).forEach { token ->
                addUnpackLocal(token.value, "FastColor.ARGB32.alpha(color) / 255.0F")
            }
            addUnpackLocal(colorMatch.groupValues[2].trim(), "FastColor.ARGB32.red(color) / 255.0F")
            addUnpackLocal(colorMatch.groupValues[3].trim(), "FastColor.ARGB32.green(color) / 255.0F")
            addUnpackLocal(colorMatch.groupValues[4].trim(), "FastColor.ARGB32.blue(color) / 255.0F")
        }
        if (unpackLocals.isEmpty() &&
            !body.contains("FastColor.ARGB32.alpha(color)") &&
            Regex("""\b(red|green|blue|alpha|scale)\b""").containsMatchIn(body)) {
            addUnpackLocal("alpha", "FastColor.ARGB32.alpha(color) / 255.0F")
            addUnpackLocal("scale", "alpha")
            addUnpackLocal("red", "FastColor.ARGB32.red(color) / 255.0F")
            addUnpackLocal("green", "FastColor.ARGB32.green(color) / 255.0F")
            addUnpackLocal("blue", "FastColor.ARGB32.blue(color) / 255.0F")
        }
        if (unpackLocals.isNotEmpty()) {
            val insertion = unpackLocals.entries.joinToString(
                separator = "",
                prefix = "\n",
                postfix = ""
            ) { (name, expression) -> "\n\t\tfloat $name = $expression;" }
            result = result.substring(0, openBrace + 1) + insertion + result.substring(openBrace + 1)
            needsFastColor = true
            cursor = closeBrace + insertion.length + 1
        } else {
            cursor = closeBrace + 1
        }
    }

    cursor = 0
    val floatColorSignature = Regex("""\([^)]*\bfloat\s+red\s*,\s*float\s+green\s*,\s*float\s+blue\s*,\s*float\s+alpha\b[^)]*\)\s*\{""")
    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val match = floatColorSignature.find(executableResult, cursor) ?: break
        val openBrace = executableResult.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableResult, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val rewrittenBody = rewriteExecutableJavaCall(body, "render") { receiver, args ->
            if (args.size == 5 && args[4].trim() == "color") {
                "$receiver.render(${args[0]}, ${args[1]}, ${args[2]}, ${args[3]}, FastColor.ARGB32.colorFromFloat(alpha, red, green, blue))"
            } else {
                null
            }
        }
        if (rewrittenBody != body) {
            result = result.substring(0, openBrace + 1) + rewrittenBody + result.substring(closeBrace)
            needsFastColor = true
            cursor = openBrace + 1 + rewrittenBody.length
        } else {
            cursor = closeBrace + 1
        }
    }

    return if (needsFastColor || result.contains("FastColor.ARGB32.")) {
        addImportIfMissing(result, "net.minecraft.util.FastColor")
    } else {
        result
    }
}

internal fun StructuralRefactorPass.isLegacyModelPartColorRenderArgs(args: List<String>): Boolean {
    if (args.size != 8) return false
    val poseArg = args[0].trim()
    val consumerArg = args[1].trim()
    if (!Regex("""[A-Za-z_$][\w$]*""").matches(poseArg)) return false
    val poseName = poseArg.lowercase()
    if (poseName != "ms" && "pose" !in poseName && "stack" !in poseName && "matrix" !in poseName) {
        return false
    }
    val consumerName = consumerArg.lowercase()
    if ("consumer" !in consumerName && "buffer" !in consumerName && "vertex" !in consumerName && "builder" !in consumerName) {
        return false
    }

    val renderInfrastructure = listOf(
        "ItemDisplayContext",
        "OverlayTexture",
        "MultiBufferSource",
        "BakedModel",
        "applyTransform(",
        "bufferSource",
        "getBuffer(",
        "ItemStack"
    )
    if (args.drop(4).any { arg -> renderInfrastructure.any { marker -> marker in arg } }) {
        return false
    }

    val colorTerms = setOf(
        "red",
        "green",
        "blue",
        "alpha",
        "r",
        "g",
        "b",
        "a",
        "scale",
        "opacity"
    )
    val colorLikeCount = args.drop(4).count { arg ->
        val tokens = Regex("""[A-Za-z_$][\w$]*""").findAll(arg).map { it.value.lowercase() }.toSet()
        arg.contains("FastColor.ARGB32.") ||
            tokens.any { it in colorTerms || it.endsWith("color") || it.endsWith("colors") || it.endsWith("col") } ||
            Regex("""[-+]?\d+(?:\.\d+)?[FfDd]?""").matches(arg.trim())
    }
    return colorLikeCount >= 3
}

internal fun StructuralRefactorPass.migrateLegacyClientRenderingSource(source: String): String {
    if (!source.contains("RenderSystem.getModelViewStack()") &&
        !source.contains(".getPartialTick()") &&
        !source.contains(".getFrameTime()") &&
        !source.contains(".getDeltaFrameTime()") &&
        !source.contains(".vertex(") &&
        !source.contains(".color(") &&
        !source.contains(".setNormal(") &&
        !source.contains(".getU(") &&
        !source.contains(".getV(") &&
        !source.contains("BufferUploader.drawWithShader(") &&
        !source.contains("Tesselator.getInstance()") &&
        !source.contains("InventoryScreen.renderEntityInInventory") &&
        !source.contains("PanoramaRenderer") &&
        !source.contains("ArmorTrim.getTrim(") &&
        !source.contains("ItemRenderer.getArmorFoilBuffer(")
    ) {
        return source
    }

    var result = source
    var needsBufferUploader = false

    result = migratePartialTickAccessors(result)
    result = Regex("""PoseStack\s+([A-Za-z_$][\w$]*)\s*=\s*RenderSystem\.getModelViewStack\(\)\s*;""")
        .replace(result) { match ->
            val stack = match.groupValues[1]
            "PoseStack $stack = new PoseStack();\n            $stack.mulPose(RenderSystem.getModelViewMatrix());"
        }

    result = migrateImplicitBufferBuildOrThrowCalls(result)
    if (result.contains("BufferUploader.drawWithShader(")) {
        needsBufferUploader = true
    }

    result = rewriteJavaCall(result, "vertex") { receiver, args ->
        when (args.size) {
            3 -> "$receiver.addVertex((float) (${args[0]}), (float) (${args[1]}), (float) (${args[2]}))"
            4 -> "$receiver.addVertex(${args[0]}, ${args[1]}, ${args[2]}, ${args[3]})"
            else -> null
        }
    }
    result = migrateTextureAtlasSpriteFloatCoordinateCalls(result)
    result = migrateVertexConsumerPoseNormals(result)
    result = migrateLevelRendererCloudTesselatorSource(result)
    result = migrateDeferredTesselatorBeginDeclarations(result)
    result = migrateLegacyTesselatorSource(result)
    result = migrateInventoryScreenEntityPreviewCalls(result)
    result = migratePanoramaRendererRenderApis(result)
    result = migrateArmorTrimComponentRendering(result)
    result = migrateArmorFoilBufferCalls(result)
    val vertexConsumerVariables = Regex("""\bVertexConsumer\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    result = rewriteJavaCall(result, "color") { receiver, args ->
        if (args.size !in setOf(3, 4)) return@rewriteJavaCall null
        if (receiver !in vertexConsumerVariables && !receiver.contains(".addVertex(")) {
            return@rewriteJavaCall null
        }
        "$receiver.setColor(${args.joinToString(", ") { it.trim() }})"
    }
    result = Regex("""\.setLight\(\s*([^,()]+)\s*,\s*([^,()]+)\s*\)""")
        .replace(result) { match -> ".setUv2(${match.groupValues[1].trim()}, ${match.groupValues[2].trim()})" }

    return if (needsBufferUploader) addImportIfMissing(result, "com.mojang.blaze3d.vertex.BufferUploader") else result
}

internal fun StructuralRefactorPass.migrateArmorFoilBufferCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ItemRenderer.getArmorFoilBuffer(")) return source

    return rewriteExecutableJavaCall(source, "getArmorFoilBuffer") { receiver, args ->
        if (receiver != "ItemRenderer" || args.size != 4) return@rewriteExecutableJavaCall null
        "ItemRenderer.getArmorFoilBuffer(${args[0].trim()}, ${args[1].trim()}, ${args[3].trim()})"
    }
}

internal fun StructuralRefactorPass.migrateArmorTrimComponentRendering(source: String): String {
    if (!source.contains("ArmorTrim.getTrim(")) return source

    var result = Regex(
        """ArmorTrim\.getTrim\s*\(\s*([^,]+)\s*,\s*([^)]+?)\s*\)\.ifPresent\s*\("""
    ).replace(source) { match ->
        val stack = match.groupValues[2].trim()
        "java.util.Optional.ofNullable($stack.get(DataComponents.TRIM)).ifPresent("
    }
    if (result == source) return source

    var cursor = 0
    val lambdaPattern = Regex("""java\.util\.Optional\.ofNullable\([^;\r\n]*DataComponents\.TRIM[^;\r\n]*\)\.ifPresent\s*\(\s*\(?\s*([A-Za-z_$][\w$]*)\s*\)?\s*->\s*\{""")
    while (true) {
        val match = lambdaPattern.find(result, cursor) ?: break
        val trimVariable = match.groupValues[1]
        val openBrace = result.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val migratedBody = body.replace(
            "Sheets.armorTrimsSheet()",
            "Sheets.armorTrimsSheet($trimVariable.pattern().value().decal())"
        )
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            cursor = openBrace + 1 + migratedBody.length
        } else {
            cursor = closeBrace + 1
        }
    }

    result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    val withoutArmorTrimImport = removeImport(result, "net.minecraft.world.item.armortrim.ArmorTrim")
    if (!Regex("""\bArmorTrim\b""").containsMatchIn(withoutArmorTrimImport)) {
        result = withoutArmorTrimImport
    }
    return result
}

internal data class JavaParameter(val type: String, val name: String)

internal fun StructuralRefactorPass.migratePanoramaRendererRenderApis(source: String): String {
    if (!source.contains("PanoramaRenderer")) return source

    var result = source
    result = rewriteJavaCallWithOffset(result, "render") { receiver, args, callOffset ->
        if (args.size != 2) return@rewriteJavaCallWithOffset null
        if (!Regex("""[A-Za-z_$][\w$]*""").matches(receiver)) return@rewriteJavaCallWithOffset null
        val methodText = enclosingMethodText(result, callOffset) ?: return@rewriteJavaCallWithOffset null
        val params = javaMethodParameters(methodText)
        if (params.none { it.name == receiver && it.type.endsWith("PanoramaRenderer") } &&
            !Regex("""\bPanoramaRenderer\s+${Regex.escape(receiver)}\b""").containsMatchIn(methodText)) {
            return@rewriteJavaCallWithOffset null
        }
        val guiGraphics = params.firstOrNull { it.type.endsWith("GuiGraphics") }?.name ?: return@rewriteJavaCallWithOffset null
        val screen = params.firstOrNull { it.type.endsWith("Screen") }?.name ?: return@rewriteJavaCallWithOffset null
        "$receiver.render($guiGraphics, $screen.width, $screen.height, ${args[0].trim()}, ${args[1].trim()})"
    }

    if (result.contains("@Mixin(PanoramaRenderer.class)") ||
        result.contains("@Mixin(net.minecraft.client.renderer.PanoramaRenderer.class)")) {
        val beforeMixin = result
        result = result
            .replace("method = \"render(FF)V\"", "method = \"render(Lnet/minecraft/client/gui/GuiGraphics;IIFF)V\"")
            .replace("method = {\"render(FF)V\"}", "method = {\"render(Lnet/minecraft/client/gui/GuiGraphics;IIFF)V\"}")
        result = Regex(
            """\b((?:public|protected|private)\s+(?:static\s+)?)void\s+([A-Za-z_$][\w$]*)\s*\(\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*CallbackInfo\s+([A-Za-z_$][\w$]*)\s*\)"""
        ).replace(result) { match ->
            "${match.groupValues[1]}void ${match.groupValues[2]}(GuiGraphics guiGraphics, int width, int height, float ${match.groupValues[3]}, float ${match.groupValues[4]}, CallbackInfo ${match.groupValues[5]})"
        }
        if (result != beforeMixin && !result.contains("import net.minecraft.client.gui.GuiGraphics;")) {
            result = addImportIfMissing(result, "net.minecraft.client.gui.GuiGraphics")
        }
    }

    return result
}

internal fun StructuralRefactorPass.javaMethodParameters(methodText: String): List<JavaParameter> {
    val normalizedMethodText = stripLeadingJavaDoc(methodText)
    val paramsText = Regex(
        """(?s)^\s*(?:@\w+(?:\([^)]*\))?\s*\r?\n[ \t]*)*(?:(?:public|protected|private|static|final|synchronized|default|abstract)\s+)*(?:<[^>{};]+>\s*)?[A-Za-z_$][\w$<>\[\].?,\s]*\s+[A-Za-z_$][\w$]*\s*\(([^{};]*)\)\s*(?:throws\s+[^{]+)?\{"""
    ).find(normalizedMethodText)?.groupValues?.get(1) ?: return emptyList()
    return splitTopLevelJavaArgs(paramsText).mapNotNull { rawParam ->
        val cleaned = rawParam
            .replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "")
            .replace(Regex("""\bfinal\s+"""), "")
            .trim()
        val name = Regex("""([A-Za-z_$][\w$]*)\s*$""").find(cleaned)?.groupValues?.get(1) ?: return@mapNotNull null
        val type = cleaned.removeSuffix(name).trim().removeSuffix("...").trim()
        if (type.isBlank()) null else JavaParameter(type, name)
    }
}

internal fun StructuralRefactorPass.javaMethodOpeningBrace(methodText: String): Int? {
    val executableMethodText = maskJavaCommentsAndLiterals(methodText)
    return executableMethodText.indexOf('{').takeIf { it >= 0 }
}

internal fun StructuralRefactorPass.javaMethodBodyText(methodText: String): String? {
    val executableMethodText = maskJavaCommentsAndLiterals(methodText)
    val openBrace = executableMethodText.indexOf('{')
    if (openBrace < 0) return null
    val closeBrace = findMatchingBrace(executableMethodText, openBrace)
    if (closeBrace <= openBrace) return null
    return methodText.substring(openBrace + 1, closeBrace)
}

internal fun StructuralRefactorPass.stripLeadingJavaDoc(source: String): String {
    var result = source.trimStart()
    while (result.startsWith("/**")) {
        val end = result.indexOf("*/")
        if (end < 0) return result
        result = result.substring(end + 2).trimStart()
    }
    return result
}

internal fun StructuralRefactorPass.migrateInventoryScreenEntityPreviewCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("InventoryScreen.renderEntityInInventory")) return source

    var result = rewriteExecutableJavaCall(source, "renderEntityInInventoryFollowsMouse") { receiver, args ->
        if (receiver != "InventoryScreen" || args.size != 7) return@rewriteExecutableJavaCall null
        val guiGraphics = args[0].trim()
        val x = args[1].trim()
        val y = args[2].trim()
        val scale = args[3].trim()
        val angleXInput = args[4].trim()
        val angleYInput = args[5].trim()
        val entity = args[6].trim()
        "InventoryScreen.renderEntityInInventoryFollowsAngle(" +
            "$guiGraphics, " +
            "($x) - ($scale), " +
            "($y) - ($scale), " +
            "($x) + ($scale), " +
            "($y) + ($scale), " +
            "$scale, " +
            "0.0F, " +
            "(float) Math.atan((double) (($angleXInput) / 40.0F)), " +
            "(float) Math.atan((double) (($angleYInput) / 40.0F)), " +
            "$entity)"
    }
    result = rewriteExecutableJavaCall(result, "renderEntityInInventory") { receiver, args ->
        if (receiver != "InventoryScreen" || args.size != 7) return@rewriteExecutableJavaCall null
        if (args[4].contains("Vector3f")) return@rewriteExecutableJavaCall null
        val entity = args[6].trim()
        "InventoryScreen.renderEntityInInventory(" +
            "${args[0].trim()}, " +
            "(float) (${args[1].trim()}), " +
            "(float) (${args[2].trim()}), " +
            "(float) (${args[3].trim()}), " +
            "new org.joml.Vector3f(0.0F, $entity.getBbHeight() / 2.0F, 0.0F), " +
            "${args[4].trim()}, " +
            "${args[5].trim()}, " +
            "$entity)"
    }
    return result
}

internal fun StructuralRefactorPass.migrateImplicitBufferBuildOrThrowCalls(source: String): String {
    val executableSource = maskJavaCommentsAndLiterals(source)
    val matches = Regex("""\bbuffer\.buildOrThrow\(\)""").findAll(executableSource).toList()
    if (matches.isEmpty()) return source

    val methods = javaMethodRanges(source)
    val hasBufferField = hasBufferBuilderBufferDeclarationOutsideMethods(executableSource, methods)
    val replacements = matches.mapNotNull { match ->
        val method = methods.singleOrNull { match.range.first in it.range }
            ?: error("Cannot derive BufferBuilder for buffer.buildOrThrow(): call must be inside one Java method")
        val methodText = executableSource.substring(method.range)
        val bufferVariables = Regex("""\bBufferBuilder\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(methodText)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        if ("buffer" in bufferVariables || hasBufferField) return@mapNotNull null
        val replacementBuffer = bufferVariables
            .filter { it != "buffer" }
            .singleOrNull()
            ?: error("Cannot derive BufferBuilder for buffer.buildOrThrow(): method must declare exactly one non-buffer BufferBuilder")
        match.range to "$replacementBuffer.buildOrThrow()"
    }
    if (replacements.isEmpty()) return source

    var result = source
    replacements.asReversed().forEach { (range, replacement) ->
        result = result.substring(0, range.first) + replacement + result.substring(range.last + 1)
    }
    return result
}

internal fun StructuralRefactorPass.hasBufferBuilderBufferDeclarationOutsideMethods(
    executableSource: String,
    methods: List<JavaMethodRange>
): Boolean {
    val methodRanges = methods.map { it.range }
    return Regex("""\bBufferBuilder\s+buffer\b""")
        .findAll(executableSource)
        .any { match -> methodRanges.none { match.range.first in it } }
}

internal fun StructuralRefactorPass.migrateDeferredTesselatorBeginDeclarations(source: String): String {
    if (!source.contains("Tesselator.getInstance().getBuilder()") ||
        !source.contains("Tesselator.getInstance().begin(")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    val methodPattern = Regex("""(?m)(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+($id)\s*\([^)]*\)\s*\{""")
    var result = source
    var cursor = 0
    while (true) {
        val method = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', method.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = method.range.last + 1
            continue
        }

        var body = result.substring(openBrace + 1, closeBrace)
        val originalBody = body
        while (true) {
            val declaration = Regex(
                """(?m)^([ \t]*)BufferBuilder\s+($id)\s*=\s*Tesselator\.getInstance\(\)\.getBuilder\(\)[ \t]*;[ \t]*(?:\r?\n)?"""
            ).find(body) ?: break
            val builder = declaration.groupValues[2]
            val beginPattern = Regex("""\b${Regex.escape(builder)}\s*=\s*Tesselator\.getInstance\(\)\.begin\(""")
            val begin = beginPattern.find(body, declaration.range.last + 1) ?: break
            val between = body.substring(declaration.range.last + 1, begin.range.first)
            if (Regex("""\b${Regex.escape(builder)}\b""").containsMatchIn(maskJavaCommentsAndLiterals(between))) {
                break
            }
            val withoutDeclaration = body.removeRange(declaration.range)
            val adjustedBegin = beginPattern.find(withoutDeclaration, declaration.range.first) ?: break
            body = withoutDeclaration.replaceRange(
                adjustedBegin.range,
                "BufferBuilder $builder = Tesselator.getInstance().begin("
            )
        }

        if (body != originalBody) {
            result = result.substring(0, openBrace + 1) + body + result.substring(closeBrace)
            cursor = openBrace + 1 + body.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyTesselatorSource(source: String): String {
    if (!source.contains("Tesselator.getInstance()") &&
        !source.contains(".getBuilder()") &&
        !source.contains(".end()")) {
        return source
    }
    var result = source
    var needsBufferUploader = false
    var needsBufferBuilder = false

    val tesselatorVariables = Regex("""\bTesselator\s+([A-Za-z_$][\w$]*)\s*=\s*Tesselator\.getInstance\(\)\s*;""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    tesselatorVariables.forEach { tesselator ->
        result = Regex("""\bBufferBuilder\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(tesselator)}\.getBuilder\(\)\s*;""")
            .replace(result) { match -> "BufferBuilder ${match.groupValues[1]} = null;" }
    }
    result = migrateTesselatorBuilderArgumentsToTesselator(result)

    result = Regex(
        """(?m)^([ \t]*)BufferBuilder\s+([A-Za-z_$][\w$]*)\s*=\s*Tesselator\.getInstance\(\)\.getBuilder\(\)\s*;\s*\r?\n[ \t]*\2\s*=\s*Tesselator\.getInstance\(\)\.begin\("""
    ).replace(result) { match ->
        "${match.groupValues[1]}BufferBuilder ${match.groupValues[2]} = Tesselator.getInstance().begin("
    }
    result = Regex("""\b([A-Za-z_$][\w$]*)\.end\(\)""")
        .replace(result) { match ->
            val receiver = match.groupValues[1]
            if (Regex("""\bBufferBuilder\s+${Regex.escape(receiver)}\b""").containsMatchIn(result)) {
                "$receiver.buildOrThrow()"
            } else {
                match.value
            }
        }

    if (result.contains("Tesselator.getInstance().end()")) {
        result = migrateTesselatorEndHelperMethods(result)
        needsBufferUploader = result.contains("BufferUploader.drawWithShader(")
        needsBufferBuilder = result.contains("BufferBuilder builder")
    }
    result = migrateTesselatorVariableEndCalls(result).also { migrated ->
        if (migrated != result) {
            needsBufferUploader = true
            result = migrated
        }
    }

    val beginBuffer = Regex(
        """\bBufferBuilder\s+([A-Za-z_$][\w$]*)\s*=\s*Tesselator\.getInstance\(\)\.begin\("""
    ).find(result)?.groupValues?.get(1)
    if (beginBuffer != null) {
        result = rewriteJavaCall(result, "invokeThenEndTesselator") { receiver, args ->
            if (receiver.isBlank() || args.any { it.trim() == beginBuffer }) return@rewriteJavaCall null
            "$receiver.invokeThenEndTesselator(${(args + beginBuffer).joinToString(", ") { it.trim() }})"
        }
    }

    if (needsBufferUploader) {
        result = addImportIfMissing(result, "com.mojang.blaze3d.vertex.BufferUploader")
    }
    if (needsBufferBuilder) {
        result = addImportIfMissing(result, "com.mojang.blaze3d.vertex.BufferBuilder")
    }
    return result
}

internal fun StructuralRefactorPass.migrateTesselatorBuilderArgumentsToTesselator(source: String): String {
    val tesselatorParameterMethods = Regex(
        """(?m)\b(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+([A-Za-z_$][\w$]*)\s*\(\s*Tesselator\s+[A-Za-z_$][\w$]*\s*\)"""
    ).findAll(source).map { it.groupValues[1] }.toSet()
    if (tesselatorParameterMethods.isEmpty()) return source

    var result = source
    val methodPattern = Regex("""(?m)(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*\([^)]*\)\s*\{""")
    var cursor = 0
    while (true) {
        val method = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', method.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = method.range.last + 1
            continue
        }
        var body = result.substring(openBrace + 1, closeBrace)
        val originalBody = body
        val tesselator = Regex("""\bTesselator\s+([A-Za-z_$][\w$]*)\s*=\s*Tesselator\.getInstance\(\)\s*;""")
            .find(body)
            ?.groupValues
            ?.get(1)
        if (tesselator != null) {
            val builderVariables = linkedSetOf<String>()
            Regex("""\bBufferBuilder\s+([A-Za-z_$][\w$]*)\s*=\s*(?:${Regex.escape(tesselator)}\.getBuilder\(\)|null)\s*;""")
                .findAll(body)
                .forEach { builderVariables += it.groupValues[1] }
            for (builder in builderVariables) {
                for (methodName in tesselatorParameterMethods) {
                    body = Regex("""\b((?:this\.)?${Regex.escape(methodName)}\s*\()\s*${Regex.escape(builder)}\s*(\))""")
                        .replace(body, "$1$tesselator$2")
                }
                val withoutDeclaration = Regex("""(?m)^[ \t]*BufferBuilder\s+${Regex.escape(builder)}\s*=\s*null\s*;\s*\r?\n""")
                    .replace(body, "")
                if (!Regex("""\b${Regex.escape(builder)}\b""").containsMatchIn(withoutDeclaration)) {
                    body = withoutDeclaration
                }
            }
        }
        if (body != originalBody) {
            result = result.substring(0, openBrace + 1) + body + result.substring(closeBrace)
            cursor = openBrace + 1 + body.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateTesselatorVariableEndCalls(source: String): String {
    var result = source
    val methodPattern = Regex("""(?m)(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*\([^)]*\)\s*\{""")
    var cursor = 0
    while (true) {
        val method = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', method.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = method.range.last + 1
            continue
        }
        var body = result.substring(openBrace + 1, closeBrace)
        val originalBody = body
        val tesselatorVariables = Regex("""\bTesselator\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
        val executableBody = maskJavaCommentsAndLiterals(body)
        val endingTesselators = tesselatorVariables
            .filter { tesselator ->
                Regex("""\b${Regex.escape(tesselator)}\.end\(\)\s*;""").containsMatchIn(executableBody)
            }
            .toSet()
        if (endingTesselators.isEmpty()) {
            cursor = closeBrace + 1
            continue
        }
        val endingTesselator = endingTesselators.singleOrNull()
            ?: error("Cannot derive active Tesselator BufferBuilder: method must contain exactly one Tesselator end receiver")
        val bufferVariables = Regex("""\bBufferBuilder\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableBody)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        val activeBuffer = activeTesselatorBufferVariable(executableBody, bufferVariables, endingTesselator)
            ?: error("Cannot derive active Tesselator BufferBuilder: tessellator.end() must have exactly one BufferBuilder assigned from begin(...) in the same method")
        endingTesselators.forEach { tesselator ->
            body = replaceExecutableRegex(body, Regex("""\b${Regex.escape(tesselator)}\.end\(\)\s*;""")) {
                "BufferUploader.drawWithShader($activeBuffer.buildOrThrow());"
            }
        }
        if (body != originalBody) {
            result = result.substring(0, openBrace + 1) + body + result.substring(closeBrace)
            cursor = openBrace + 1 + body.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.activeTesselatorBufferVariable(
    executableBody: String,
    bufferVariables: List<String>,
    endingTesselator: String
): String? {
    val activeBuffers = bufferVariables
        .filter { variable ->
            Regex("""\b(?:BufferBuilder\s+)?${Regex.escape(variable)}\s*=\s*Tesselator\.getInstance\(\)\.begin\(""").containsMatchIn(executableBody) ||
                Regex("""\b(?:BufferBuilder\s+)?${Regex.escape(variable)}\s*=\s*${Regex.escape(endingTesselator)}\.begin\(""").containsMatchIn(executableBody)
        }
        .distinct()
    return activeBuffers.singleOrNull()
}

internal fun StructuralRefactorPass.migrateTesselatorEndHelperMethods(source: String): String {
    var result = source
    val methodPattern = Regex(
        """(?m)(public|protected|private)\s+(?:final\s+)?void\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)\s*\{"""
    )
    var cursor = 0
    while (true) {
        val method = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', method.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = method.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        if (!body.contains("Tesselator.getInstance().end()")) {
            cursor = closeBrace + 1
            continue
        }

        val signature = result.substring(method.range.first, openBrace)
        val rewrittenSignature = if (signature.contains("BufferBuilder ")) {
            signature
        } else {
            val params = method.groupValues[3].trim()
            val insertion = if (params.isEmpty()) "BufferBuilder builder" else "$params, BufferBuilder builder"
            signature.replace(Regex("""\([^)]*\)"""), "($insertion)")
        }
        val rewrittenBody = body.replace(
            "Tesselator.getInstance().end()",
            "BufferUploader.drawWithShader(builder.buildOrThrow())"
        )
        result = result.substring(0, method.range.first) +
            rewrittenSignature +
            result.substring(openBrace, openBrace + 1) +
            rewrittenBody +
            result.substring(closeBrace)
        cursor = method.range.first + rewrittenSignature.length + 1 + rewrittenBody.length
    }
    return result
}

internal fun StructuralRefactorPass.migrateLevelRendererCloudTesselatorSource(source: String): String {
    if (!source.contains("callBuildClouds") &&
        !source.contains("Tesselator.getInstance().getBuilder()") &&
        !source.contains("getPositionTexColorNormalShader")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var changed = false
    var needsTesselator = false
    var needsRenderType = false
    val mutableBuilderParameterMethods = collectMutableTesselatorBuilderParameterMethods(result)

    result = Regex("""MeshData\s+callBuildClouds\s*\(\s*BufferBuilder\s+($id)\s*,""")
        .replace(result) { match ->
            changed = true
            needsTesselator = true
            "MeshData callBuildClouds(Tesselator ${match.groupValues[1]},"
        }

    val getBuilderDeclarations = Regex(
        """(?m)^([ \t]*)BufferBuilder\s+($id)\s*=\s*Tesselator\.getInstance\(\)\.getBuilder\(\)\s*;\s*\r?\n?"""
    ).findAll(result).toList()
    val mutableBuilderTesselators = linkedMapOf<String, String>()
    for (match in getBuilderDeclarations) {
        val indent = match.groupValues[1]
        val builder = match.groupValues[2]
        val callsBuildClouds = Regex("""callBuildClouds\s*\(\s*${Regex.escape(builder)}\s*,""").containsMatchIn(result)
        val startsTesselatorBuilder = Regex("""\b${Regex.escape(builder)}\s*=\s*Tesselator\.getInstance\(\)\.begin\(""").containsMatchIn(result)
        val feedsMutableBuilderParameter = mutableBuilderParameterMethods.keys.any { methodName ->
            sourceCallsJavaMethodWithArgument(result, methodName, builder)
        }
        if (callsBuildClouds) {
            result = result.replace(match.value, "")
            result = Regex("""callBuildClouds\s*\(\s*${Regex.escape(builder)}\s*,""")
                .replace(result, "callBuildClouds(Tesselator.getInstance(),")
            changed = true
            needsTesselator = true
        } else if (startsTesselatorBuilder && feedsMutableBuilderParameter) {
            val tesselator = "${builder}Tesselator"
            result = result.replace(match.value, "${indent}Tesselator $tesselator = Tesselator.getInstance();\n")
            mutableBuilderTesselators[builder] = tesselator
            changed = true
            needsTesselator = true
        } else if (startsTesselatorBuilder) {
            continue
        } else {
            result = removeUnusedTesselatorGetBuilderDeclaration(result, match.value, builder)
                .also { if (it != result) changed = true }
        }
    }

    val migratedBuilderParameterMethods = linkedMapOf<String, String>()
    val methodPattern = Regex("""(?m)(?:public|protected|private)\s+[^{;\r\n]+?\s+($id)\s*\([^)]*BufferBuilder\s+($id)[^)]*\)\s*\{""")
    var cursor = 0
    while (true) {
        val method = methodPattern.find(result, cursor) ?: break
        val methodName = method.groupValues[1]
        val builderParam = method.groupValues[2]
        val openBrace = result.indexOf('{', method.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = method.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        if (!Regex("""\b${Regex.escape(builderParam)}\s*=\s*Tesselator\.getInstance\(\)\.begin\(""").containsMatchIn(body)) {
            cursor = closeBrace + 1
            continue
        }

        val signature = result.substring(method.range.first, openBrace)
        val rewrittenSignature = Regex("""\bBufferBuilder\s+${Regex.escape(builderParam)}\b""")
            .replace(signature, "Tesselator tesselator")
        var firstBegin = true
        val rewrittenBody = Regex("""\b${Regex.escape(builderParam)}\s*=\s*Tesselator\.getInstance\(\)\.begin\(""")
            .replace(body) {
                if (firstBegin) {
                    firstBegin = false
                    "BufferBuilder $builderParam = tesselator.begin("
                } else {
                    "$builderParam = tesselator.begin("
                }
            }
        result = result.substring(0, method.range.first) +
            rewrittenSignature +
            result.substring(openBrace, openBrace + 1) +
            rewrittenBody +
            result.substring(closeBrace)
        cursor = method.range.first + rewrittenSignature.length + 1 + rewrittenBody.length
        migratedBuilderParameterMethods[methodName] = builderParam
        changed = true
        needsTesselator = true
    }

    mutableBuilderTesselators.forEach { (builder, tesselator) ->
        var firstBegin = true
        result = Regex("""\b${Regex.escape(builder)}\s*=\s*Tesselator\.getInstance\(\)\.begin\(""")
            .replace(result) {
                if (firstBegin) {
                    firstBegin = false
                    "BufferBuilder $builder = $tesselator.begin("
                } else {
                    "$builder = $tesselator.begin("
                }
            }
        for (methodName in migratedBuilderParameterMethods.keys) {
            result = rewriteJavaInvocationArguments(result, methodName) { args ->
                val migrated = args.map { arg -> if (arg.trim() == builder) tesselator else arg }
                migrated.takeIf { it != args }
            }
        }
    }

    val cloudMaskPattern = Regex(
        """(?s)if\s*\(\s*($id)\s*==\s*0\s*\)\s*\{\s*RenderSystem\.colorMask\s*\(\s*false\s*,\s*false\s*,\s*false\s*,\s*false\s*\)\s*;\s*\}\s*else\s*\{\s*RenderSystem\.colorMask\s*\(\s*true\s*,\s*true\s*,\s*true\s*,\s*true\s*\)\s*;\s*\}\s*ShaderInstance\s+($id)\s*=\s*RenderSystem\.getShader\(\)\s*;\s*([^;\r\n]+?\.drawWithShader\([^;]+?,\s*\2\s*\)\s*;)"""
    )
    result = cloudMaskPattern.replace(result) { match ->
        changed = true
        needsRenderType = true
        val loopVar = match.groupValues[1]
        val shaderVar = match.groupValues[2]
        val drawCall = match.groupValues[3]
        "RenderType rendertype = $loopVar == 0 ? RenderType.cloudsDepthOnly() : RenderType.clouds();\n" +
            "                        rendertype.setupRenderState();\n" +
            "                        ShaderInstance $shaderVar = RenderSystem.getShader();\n" +
            "                        $drawCall\n" +
            "                        rendertype.clearRenderState();"
    }
    if (needsRenderType) {
        result = Regex("""(?m)^[ \t]*RenderSystem\.setShader\(GameRenderer::getPositionTexColorNormalShader\);\s*\r?\n""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*RenderSystem\.setShaderTexture\(0,\s*CLOUDS_LOCATION\);\s*\r?\n""")
            .replace(result, "")
    }

    if (!changed) return source
    if (needsTesselator) {
        result = addImportIfMissing(result, "com.mojang.blaze3d.vertex.Tesselator")
        if (!Regex("""\bBufferBuilder\b""").containsMatchIn(removeImport(result, "com.mojang.blaze3d.vertex.BufferBuilder"))) {
            result = removeImport(result, "com.mojang.blaze3d.vertex.BufferBuilder")
        }
    }
    if (needsRenderType) {
        result = addImportIfMissing(result, "net.minecraft.client.renderer.RenderType")
    }
    return result
}

internal fun StructuralRefactorPass.removeUnusedTesselatorGetBuilderDeclaration(source: String, declaration: String, builder: String): String {
    val declarationStart = source.indexOf(declaration)
    if (declarationStart < 0) return source
    val methodStart = source.lastIndexOf('{', declarationStart)
    val methodEnd = if (methodStart >= 0) findMatchingBrace(source, methodStart) else -1
    if (methodStart < 0 || methodEnd <= methodStart) return source
    val methodText = source.substring(methodStart + 1, methodEnd)
    val withoutDeclaration = methodText.replace(declaration, "")
    return if (!Regex("""\b${Regex.escape(builder)}\b""").containsMatchIn(withoutDeclaration)) {
        source.replace(declaration, "")
    } else {
        source
    }
}

internal fun StructuralRefactorPass.collectMutableTesselatorBuilderParameterMethods(source: String): Map<String, String> {
    val id = """[A-Za-z_$][\w$]*"""
    val methods = linkedMapOf<String, String>()
    val methodPattern = Regex("""(?m)(?:public|protected|private)\s+[^{;\r\n]+?\s+($id)\s*\([^)]*BufferBuilder\s+($id)[^)]*\)\s*\{""")
    var cursor = 0
    while (true) {
        val method = methodPattern.find(source, cursor) ?: break
        val methodName = method.groupValues[1]
        val builderParam = method.groupValues[2]
        val openBrace = source.indexOf('{', method.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = method.range.last + 1
            continue
        }
        val body = source.substring(openBrace + 1, closeBrace)
        if (Regex("""\b${Regex.escape(builderParam)}\s*=\s*Tesselator\.getInstance\(\)\.begin\(""").containsMatchIn(body)) {
            methods[methodName] = builderParam
        }
        cursor = closeBrace + 1
    }
    return methods
}

internal fun StructuralRefactorPass.sourceCallsJavaMethodWithArgument(source: String, methodName: String, argument: String): Boolean {
    var cursor = 0
    val token = "$methodName("
    while (true) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) return false
        if (tokenIndex > 0 && (source[tokenIndex - 1].isLetterOrDigit() || source[tokenIndex - 1] == '_' || source[tokenIndex - 1] == '$')) {
            cursor = tokenIndex + token.length
            continue
        }
        val openParen = tokenIndex + methodName.length
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.any { it.trim() == argument }) return true
        cursor = closeParen + 1
    }
}

internal fun StructuralRefactorPass.migrateLegacyModelEventSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ModelResourceLocation") && !executableCode.contains("ModelEvent.")) return source
    var result = source
    var needsResourceLocation = false
    val modIdExpression = Regex("""@(?:Mod\.)?EventBusSubscriber\s*\([^)]*\bmodid\s*=\s*([^,\)]+)""")
        .find(result)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: Regex("""(?m)\b(?:public\s+)?static\s+final\s+String\s+(?:MOD_ID|MODID|ID)\s*=\s*([^;\r\n]+);""")
            .find(result)
            ?.groupValues
            ?.get(1)
            ?.trim()

    if (result.contains("ModelEvent.ModifyBakingResult") && result.contains(".getModels()")) {
        result = Regex("""Map\.Entry\s*<\s*ResourceLocation\s*,\s*BakedModel\s*>""")
            .replace(result, "Map.Entry<ModelResourceLocation, BakedModel>")
    }
    result = Regex("""(\b[A-Za-z_$][\w$]*\.getKey\(\))\.getNamespace\(\)""")
        .replace(result, "$1.id().getNamespace()")
    result = Regex("""(\b[A-Za-z_$][\w$]*\.getKey\(\))\.getPath\(\)""")
        .replace(result, "$1.id().getPath()")

    result = rewriteExecutableJavaNew(result, "ModelResourceLocation") { args ->
        if (args.size != 2) return@rewriteExecutableJavaNew null
        val model = args[0].trim()
        val variant = args[1].trim()
        if (variant == "\"inventory\"") {
            "ModelResourceLocation.inventory($model)"
        } else {
            "ModelResourceLocation.standalone($model)"
        }
    }

    if (result.contains("ModelEvent.RegisterGeometryLoaders")) {
        result = rewriteJavaCall(result, "register") { receiver, args ->
            if (args.size != 2) return@rewriteJavaCall null
            if (!receiver.endsWith("event") && !receiver.endsWith("Event")) return@rewriteJavaCall null
            val id = args[0].trim()
            val namespace = modIdExpression ?: return@rewriteJavaCall null
            if (!Regex(""""(?:\\.|[^"\\])*"""").matches(id)) return@rewriteJavaCall null
            needsResourceLocation = true
            "$receiver.register(ResourceLocation.fromNamespaceAndPath($namespace, $id), ${args[1].trim()})"
        }
    }

    if (result.contains("ModelEvent.RegisterAdditional")) {
        result = rewriteJavaCall(result, "register") { receiver, args ->
            if (args.size != 1) return@rewriteJavaCall null
            val arg = args[0].trim()
            if (!receiver.endsWith("event") && !receiver.endsWith("Event")) return@rewriteJavaCall null
            modelResourceLocationStandaloneExpression(arg)?.let { standalone ->
                return@rewriteJavaCall "$receiver.register($standalone)"
            }
            if (arg.startsWith("new ModelResourceLocation(") || arg.startsWith("ModelResourceLocation.")) {
                return@rewriteJavaCall null
            }
            if (arg.contains(".prefix(") || arg.contains("ResourceLocation.")) {
                "$receiver.register(ModelResourceLocation.standalone($arg))"
            } else {
                null
            }
        }
    }
    if (needsResourceLocation) {
        result = addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
    }
    if (result.contains("ModelResourceLocation")) {
        result = addImportIfMissing(result, "net.minecraft.client.resources.model.ModelResourceLocation")
    }
    return result
}

internal data class CumulusMenuOwner(
    val simpleName: String,
    val qualifiedName: String,
    val registerFieldName: String,
    val menuFields: Set<String>
)

internal data class CumulusMenuDefinitionMigration(
    val source: String,
    val owner: CumulusMenuOwner?
)

internal data class CumulusMenuReferenceMigration(
    val source: String,
    val obsoleteListeners: Set<CumulusMenuListenerReference>
)

internal data class CumulusMenuListenerReference(
    val simpleTypeName: String,
    val qualifiedTypeName: String,
    val methodName: String
)

internal data class CumulusMenuHelperMethodRemoval(
    val source: String,
    val obsoleteListeners: Set<CumulusMenuListenerReference>
)

internal data class CumulusMenuRegistration(
    val start: Int,
    val end: Int,
    val prefix: String,
    val fieldName: String,
    val id: String,
    val args: List<String>
)

internal fun StructuralRefactorPass.migrateCumulusMenuApi(projectDir: Path, dryRun: Boolean): List<Change> {
    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/") ||
                rel.startsWith("src/references/") || rel.contains("/src/references/")
        }}
        .toList()

    val changes = mutableListOf<Change>()
    val stagedSources = linkedMapOf<Path, String>()
    val owners = mutableListOf<CumulusMenuOwner>()

    for (file in javaFiles) {
        val original = file.readText()
        val migration = migrateCumulusMenuDefinitionSource(projectDir, original)
        (migration.owner ?: discoverMigratedCumulusMenuOwner(migration.source))?.let { owners += it }
        if (migration.source != original) {
            stagedSources[file] = migration.source
            changes += Change(
                file = file,
                line = 1,
                description = "Migrate Cumulus menu DeferredRegister to entrypoint callback",
                before = "DeferredRegister<Menu> + MENUS.register(...)",
                after = "@CumulusEntrypoint MenuInitializer#registerMenus",
                confidence = Confidence.HIGH,
                ruleId = "struct-cumulus-menu-api-121"
            )
        }
    }

    if (owners.isNotEmpty()) {
        val obsoleteListeners = linkedSetOf<CumulusMenuListenerReference>()
        for (file in javaFiles) {
            val base = stagedSources[file] ?: file.readText()
            val migration = migrateCumulusMenuReferences(base, owners)
            obsoleteListeners += migration.obsoleteListeners
            if (migration.source != base) {
                stagedSources[file] = migration.source
                changes += Change(
                    file = file,
                    line = 1,
                    description = "Update Cumulus menu record access and registration call sites",
                    before = "Menu DeferredHolder get()/getMusic()/MENUS.register(bus)",
                    after = "Menu record accessors and entrypoint-owned registration",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-cumulus-menu-api-121"
                )
            }
        }
        if (obsoleteListeners.isNotEmpty()) {
            for (file in javaFiles) {
                val base = stagedSources[file] ?: file.readText()
                val migrated = removeCumulusObsoleteMenuListenerRegistrations(base, obsoleteListeners)
                if (migrated != base) {
                    stagedSources[file] = migrated
                    changes += Change(
                        file = file,
                        line = 1,
                        description = "Remove Cumulus menu listener registrations replaced by entrypoints",
                        before = "mod bus listener for MenuHelper.prepareMenu setup",
                        after = "CumulusEntrypoint MenuInitializer registration",
                        confidence = Confidence.HIGH,
                        ruleId = "struct-cumulus-menu-api-121"
                    )
                }
            }
        }
    }

    if (!dryRun) {
        stagedSources.forEach { (file, source) -> file.writeText(source) }
    }
    return changes
}

internal fun StructuralRefactorPass.migrateCumulusMenuDefinitionSource(projectDir: Path, source: String): CumulusMenuDefinitionMigration {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("Cumulus.MENU_REGISTRY_KEY") ||
        !executableCode.contains("DeferredRegister<Menu>") ||
        !executableCode.contains(".register(") ||
        !executableCode.contains("com.aetherteam.cumulus.api.Menu")) {
        return CumulusMenuDefinitionMigration(source, null)
    }

    val className = javaTopLevelTypeName(executableCode) ?: return CumulusMenuDefinitionMigration(source, null)
    val registerPattern = Regex(
        """(?ms)^[ \t]*(?:public|protected|private)\s+static\s+final\s+DeferredRegister\s*<\s*Menu\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*DeferredRegister\.create\(\s*Cumulus\.MENU_REGISTRY_KEY\s*,\s*([^)]+?)\s*\)\s*;\s*\r?\n?"""
    )
    val registerMatch = registerPattern.find(executableCode) ?: return CumulusMenuDefinitionMigration(source, null)
    val registerField = registerMatch.groups[1]?.range?.let { source.substring(it) }
        ?: return CumulusMenuDefinitionMigration(source, null)
    val modIdExpression = registerMatch.groups[2]?.range?.let { source.substring(it).trim() }
        ?: return CumulusMenuDefinitionMigration(source, null)
    val registrations = findCumulusMenuRegistrations(source, executableCode, registerField)
    if (registrations.isEmpty()) return CumulusMenuDefinitionMigration(source, null)

    val hasLegacyBackground = registrations.any { registration ->
        registration.args.any { it.contains(".background(") }
    } || executableCode.contains("Menu.Background")
    val panoramaExpression = if (hasLegacyBackground) {
        cumulusPanoramaExpression(projectDir, source, modIdExpression) ?: return CumulusMenuDefinitionMigration(source, null)
    } else {
        null
    }
    val booleanSuppliers = Regex("""\bBooleanSupplier\s+([A-Za-z_$][\w$]*)\s*=""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()

    var result = source
    var needsCubeMapImport = false
    for (registration in registrations.asReversed()) {
        val migratedArgs = migrateCumulusMenuConstructorArgs(
            registration.args,
            booleanSuppliers,
            panoramaExpression
        ) ?: return CumulusMenuDefinitionMigration(source, null)
        if (migratedArgs.any { it.contains("new CubeMap(") }) {
            needsCubeMapImport = true
        }
        val replacement = "${registration.prefix}Menu ${registration.fieldName} = new Menu(${migratedArgs.joinToString(", ") { it.trim() }});\n"
        result = result.substring(0, registration.start) + replacement + result.substring(registration.end)
    }

    result = result.removeRange(registerMatch.range)
    result = removeUnusedCumulusBooleanSupplierFields(result, booleanSuppliers)
    result = removeCumulusMenuBackgroundFields(result)
    result = addCumulusMenuInitializerClassShape(result, className)
    result = insertCumulusRegisterMenusMethod(result, modIdExpression, registrations)
    result = removeImport(result, "com.aetherteam.cumulus.Cumulus")
    result = removeImport(result, "java.util.function.BooleanSupplier")
    result = removeImport(result, "net.neoforged.neoforge.registries.DeferredRegister")
    result = removeImport(result, "net.neoforged.neoforge.registries.DeferredHolder")
    result = addImportIfMissing(result, "com.aetherteam.cumulus.api.CumulusEntrypoint")
    result = addImportIfMissing(result, "com.aetherteam.cumulus.api.MenuInitializer")
    result = addImportIfMissing(result, "com.aetherteam.cumulus.api.MenuRegisterCallback")
    result = addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
    if (needsCubeMapImport) {
        result = addImportIfMissing(result, "net.minecraft.client.renderer.CubeMap")
    }
    result = cleanupRedundantBlankLines(result)

    val packageName = packageNameOf(result)
    val owner = CumulusMenuOwner(
        simpleName = className,
        qualifiedName = if (packageName.isBlank()) className else "$packageName.$className",
        registerFieldName = registerField,
        menuFields = registrations.map { it.fieldName }.toSet()
    )
    return CumulusMenuDefinitionMigration(result, owner)
}

internal fun StructuralRefactorPass.findCumulusMenuRegistrations(
    source: String,
    executableCode: String,
    registerField: String
): List<CumulusMenuRegistration> {
    val id = """[A-Za-z_$][\w$]*"""
    val prefixPattern = Regex(
        """(?m)^([ \t]*(?:public|protected|private)\s+static\s+final\s+)DeferredHolder\s*<\s*Menu\s*,\s*Menu\s*>\s+($id)\s*=\s*${Regex.escape(registerField)}\.register\(\s*"([^"]+)"\s*,\s*\(\)\s*->\s*new\s+Menu\s*\("""
    )
    val registrations = mutableListOf<CumulusMenuRegistration>()
    var cursor = 0
    while (true) {
        val match = prefixPattern.find(source, cursor) ?: break
        val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
        if (!executableSegment.contains("DeferredHolder") ||
            !executableSegment.contains("Menu") ||
            !executableSegment.contains("${registerField}.register(") ||
            !executableSegment.contains("new Menu(")) {
            cursor = match.range.last + 1
            continue
        }
        val openParen = match.range.last
        val closeMenu = findMatchingParen(executableCode, openParen)
        if (closeMenu < 0) {
            cursor = match.range.last + 1
            continue
        }
        var end = closeMenu + 1
        while (end < source.length && source[end].isWhitespace()) end++
        if (end >= source.length || source[end] != ')') {
            cursor = closeMenu + 1
            continue
        }
        end++
        while (end < source.length && source[end].isWhitespace()) end++
        if (end >= source.length || source[end] != ';') {
            cursor = closeMenu + 1
            continue
        }
        end++
        if (end < source.length && source[end] == '\r') end++
        if (end < source.length && source[end] == '\n') end++
        registrations += CumulusMenuRegistration(
            start = match.range.first,
            end = end,
            prefix = match.groupValues[1],
            fieldName = match.groupValues[2],
            id = match.groupValues[3],
            args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeMenu))
        )
        cursor = end
    }
    return registrations
}

internal fun StructuralRefactorPass.migrateCumulusMenuConstructorArgs(
    args: List<String>,
    booleanSuppliers: Set<String>,
    panoramaExpression: String?
): List<String>? {
    val migrated = args.toMutableList()
    if (migrated.size >= 4 && migrated[3].trim() in booleanSuppliers) {
        migrated.removeAt(3)
    }
    for (index in migrated.indices) {
        if (!migrated[index].contains(".background(")) continue
        val panorama = panoramaExpression ?: return null
        migrated[index] = Regex("""\.background\(\s*[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\s*\)""")
            .replace(migrated[index], ".panorama($panorama)")
    }
    return migrated
}

internal fun StructuralRefactorPass.cumulusPanoramaExpression(projectDir: Path, source: String, modIdExpression: String): String? {
    val namespace = resolveCumulusModIdNamespace(projectDir, source, modIdExpression)
        ?: return null
    val relative = Path.of("assets", namespace, "textures", "gui", "title", "panorama", "panorama_0.png")
    val hasPanorama = listOf(
        projectDir.resolve("src/main/resources"),
        projectDir.resolve("src/generated/resources")
    ).any { it.resolve(relative).exists() }
    if (!hasPanorama) return null
    return """new CubeMap(ResourceLocation.fromNamespaceAndPath($modIdExpression, "textures/gui/title/panorama/panorama"))"""
}

internal data class JavaSourceForType(val source: String, val simpleName: String)

internal fun StructuralRefactorPass.resolveCumulusModIdNamespace(projectDir: Path, source: String, expression: String): String? {
    val trimmed = expression.trim()
    resolveLiteralModIdExpression(trimmed)?.let { return it }

    val identifier = """[A-Za-z_$][\w$]*"""
    if (!Regex("""$identifier(?:\.$identifier)*""").matches(trimmed)) return null
    val constName = trimmed.substringAfterLast('.')
    val ownerRef = trimmed.substringBeforeLast('.', missingDelimiterValue = "")
    if (ownerRef.isBlank()) {
        val ownerName = javaTopLevelTypeName(source) ?: return null
        return staticFinalStringConstantInSource(source, ownerName, constName)
    }

    val ownerSource = resolveJavaSourceForTypeReference(projectDir, source, ownerRef) ?: return null
    return staticFinalStringConstantInSource(ownerSource.source, ownerSource.simpleName, constName)
}

internal fun StructuralRefactorPass.staticFinalStringConstantInSource(source: String, ownerName: String, constName: String): String? {
    val code = maskJavaComments(source)
    val executableCode = maskJavaCommentsAndLiterals(source)
    val typeBlocks = javaTypeBlocks(source, executableCode)
    val owner = typeBlocks.singleOrNull { it.name == ownerName } ?: return null
    return javaStaticFinalStringConstant(code, executableCode, owner, constName, typeBlocks)
}

internal fun StructuralRefactorPass.resolveJavaSourceForTypeReference(
    projectDir: Path,
    source: String,
    ownerRef: String
): JavaSourceForType? {
    val ownerSimpleName = ownerRef.substringAfterLast('.')
    val currentPackage = packageNameOf(source)
    val imports = Regex("""(?m)^\s*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toList()
    val ownerFqn = if (ownerRef.contains('.')) {
        ownerRef
    } else {
        val imported = imports.filter { it.substringAfterLast('.') == ownerSimpleName }.distinct()
        when (imported.size) {
            0 -> if (currentPackage.isBlank()) ownerRef else "$currentPackage.$ownerRef"
            1 -> imported.single()
            else -> return null
        }
    }
    return javaSourceForFqn(projectDir, ownerFqn)
}

internal fun StructuralRefactorPass.javaSourceForFqn(projectDir: Path, fqn: String): JavaSourceForType? {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return null
    val matches = mutableListOf<JavaSourceForType>()
    walkJavaSourceRoots(srcDir).use { files ->
        files.filter { it.extension == "java" }.forEach { file ->
            val text = file.readText()
            val simpleName = javaTopLevelTypeName(text) ?: return@forEach
            val packageName = packageNameOf(text)
            val sourceFqn = if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
            if (sourceFqn == fqn) {
                matches += JavaSourceForType(text, simpleName)
            }
        }
    }
    return matches.singleOrNull()
}

internal fun StructuralRefactorPass.resolveLiteralModIdExpression(expression: String): String? {
    val trimmed = expression.trim()
    return if (Regex(""""(?:\\.|[^"\\])*"""").matches(trimmed)) trimmed.trim('"') else null
}

internal fun StructuralRefactorPass.projectMetadataModId(projectDir: Path): String? {
    val candidates = linkedSetOf<String>()
    fun addCandidate(value: String) {
        val trimmed = value.trim()
        if (Regex("""[A-Za-z0-9_.-]+""").matches(trimmed)) {
            candidates += trimmed
        }
    }

    val gradleProperties = projectDir.resolve("gradle.properties")
    if (gradleProperties.exists()) {
        Regex("""(?m)^\s*(?:mod_id|modid)\s*=\s*([A-Za-z0-9_.-]+)\s*$""")
            .findAll(gradleProperties.readText())
            .forEach { addCandidate(it.groupValues[1]) }
    }
    listOf(
        projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml"),
        projectDir.resolve("src/main/resources/META-INF/mods.toml")
    ).asSequence()
        .filter { it.exists() }
        .forEach { file ->
            var inModsTable = false
            file.readLines().forEach { line ->
                val effective = line.substringBefore('#').trim()
                when {
                    effective.startsWith("[[") && effective.endsWith("]]") ->
                        inModsTable = effective == "[[mods]]"
                    inModsTable -> Regex("""^modId\s*=\s*"([^"]+)"$""")
                        .matchEntire(effective)
                        ?.let { addCandidate(it.groupValues[1]) }
                }
            }
        }
    return candidates.singleOrNull()
}

internal fun StructuralRefactorPass.removeUnusedCumulusBooleanSupplierFields(source: String, booleanSuppliers: Set<String>): String {
    var result = source
    for (name in booleanSuppliers) {
        val declaration = Regex(
            """(?ms)^[ \t]*(?:public|protected|private)\s+static\s+final\s+BooleanSupplier\s+${Regex.escape(name)}\s*=\s*.*?;\s*\r?\n?"""
        ).find(result) ?: continue
        val withoutDeclaration = result.substring(0, declaration.range.first) +
            result.substring(declaration.range.last + 1)
        if (!Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(withoutDeclaration)) {
            result = withoutDeclaration
        }
    }
    return result
}

internal fun StructuralRefactorPass.removeCumulusMenuBackgroundFields(source: String): String =
    Regex(
        """(?ms)^[ \t]*(?:public|protected|private)\s+static\s+final\s+Menu\.Background\s+[A-Za-z_$][\w$]*\s*=\s*new\s+Menu\.Background\(\).*?;\s*\r?\n?"""
    ).replace(source, "")

internal fun StructuralRefactorPass.addCumulusMenuInitializerClassShape(source: String, className: String): String {
    var result = Regex("""\bimplements\s+MenuInitializer(?:\s+implements\s+MenuInitializer)+\b""")
        .replace(source, "implements MenuInitializer")
    if (!Regex("""@CumulusEntrypoint\b""").containsMatchIn(result)) {
        val classMatch = Regex("""(?m)^([ \t]*)(public\s+(?:final\s+)?class\s+${Regex.escape(className)}\b)""")
            .find(result)
        if (classMatch != null) {
            result = result.substring(0, classMatch.range.first) +
                "${classMatch.groupValues[1]}@CumulusEntrypoint\n" +
                result.substring(classMatch.range.first)
        }
    }
    val classHeader = Regex("""(?m)(public\s+(?:final\s+)?class\s+${Regex.escape(className)}\b)([^{]*)\{""")
        .find(result)
        ?: return result
    if (classHeader.groupValues[2].contains(Regex("""\bMenuInitializer\b"""))) return result
    val tailRange = classHeader.groups[2]!!.range
    val tail = classHeader.groupValues[2]
    val migratedTail = if (tail.contains(Regex("""\bimplements\b"""))) {
        tail.trimEnd() + ", MenuInitializer "
    } else {
        tail.trimEnd() + " implements MenuInitializer "
    }
    return result.substring(0, tailRange.first) + migratedTail + result.substring(tailRange.last + 1)
}

internal fun StructuralRefactorPass.insertCumulusRegisterMenusMethod(
    source: String,
    modIdExpression: String,
    registrations: List<CumulusMenuRegistration>
): String {
    if (Regex("""\bvoid\s+registerMenus\s*\(\s*MenuRegisterCallback\b""").containsMatchIn(source)) return source
    val body = registrations.joinToString("\n") { registration ->
        """        menuRegisterCallback.registerMenu(ResourceLocation.fromNamespaceAndPath($modIdExpression, "${registration.id}"), ${registration.fieldName});"""
    }
    val method = """

    @Override
    public void registerMenus(MenuRegisterCallback menuRegisterCallback) {
$body
    }
""".trimEnd()
    return insertBeforeLastClassBrace(source, method)
}

internal fun StructuralRefactorPass.migrateCumulusMenuReferences(source: String, owners: List<CumulusMenuOwner>): CumulusMenuReferenceMigration {
    var result = source
    for (owner in owners) {
        val ownerNames = listOf(owner.simpleName, owner.qualifiedName).distinct()
        for (ownerName in ownerNames) {
            val escapedOwner = Regex.escape(ownerName)
            if (owner.registerFieldName.isNotBlank()) {
                result = Regex("""(?m)^[ \t]*$escapedOwner\.${Regex.escape(owner.registerFieldName)}\.register\([^;\r\n]*\);\s*\r?\n""")
                    .replace(result, "")
            }
            for (field in owner.menuFields) {
                val escapedField = Regex.escape(field)
                result = Regex("""(?m)^[ \t]*[A-Za-z_$][\w$]*\.prepareMenu\(\s*$escapedOwner\.$escapedField(?:\.get\(\))?\s*\)\s*;\s*\r?\n""")
                    .replace(result, "")
                result = Regex("""\b$escapedOwner\.$escapedField\.get\(\)\.getMusic\(\)""")
                    .replace(result, "$ownerName.$field.music()")
                result = Regex("""\b$escapedOwner\.$escapedField\.get\(\)\.toString\(\)""")
                    .replace(result, "$ownerName.$field.toString()")
                result = Regex("""\b$escapedOwner\.$escapedField\.get\(\)""")
                    .replace(result, "$ownerName.$field")
            }
        }
    }
    val methodRemoval = removeEmptyCumulusMenuHelperSetupMethods(result)
    result = methodRemoval.source
    result = removeUnusedMenuHelperImport(result)
    result = Regex("""\bMenus\.MINECRAFT\.get\(\)\.getMusic\(\)""")
        .replace(result, "Menus.MINECRAFT.music()")
    result = Regex("""\bMenus\.MINECRAFT\.get\(\)\.toString\(\)""")
        .replace(result, "Menus.MINECRAFT.toString()")
    result = Regex("""\bMenus\.MINECRAFT\.get\(\)""")
        .replace(result, "Menus.MINECRAFT")
    return CumulusMenuReferenceMigration(result, methodRemoval.obsoleteListeners)
}

internal fun StructuralRefactorPass.discoverMigratedCumulusMenuOwner(source: String): CumulusMenuOwner? {
    if (!source.contains("MenuInitializer") ||
        !source.contains("registerMenus(") ||
        !source.contains(".registerMenu(")) {
        return null
    }
    val className = javaTopLevelTypeName(source) ?: return null
    val fields = Regex(
        """\b[A-Za-z_$][\w$]*\.registerMenu\(\s*ResourceLocation\.fromNamespaceAndPath\([^;]+?\)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*;"""
    ).findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (fields.isEmpty()) return null
    val packageName = packageNameOf(source)
    return CumulusMenuOwner(
        simpleName = className,
        qualifiedName = if (packageName.isBlank()) className else "$packageName.$className",
        registerFieldName = "",
        menuFields = fields
    )
}

internal fun StructuralRefactorPass.removeEmptyCumulusMenuHelperSetupMethods(source: String): CumulusMenuHelperMethodRemoval {
    var result = source
    var cursor = 0
    val obsoleteListeners = linkedSetOf<CumulusMenuListenerReference>()
    val simpleTypeName = javaTopLevelTypeName(result)
    val packageName = packageNameOf(result)
    val methodPattern = Regex("""(?m)^[ \t]*(?:public|protected|private)\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*MenuHelper\s+[A-Za-z_$][\w$]*\s*\)\s*\{""")
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last)
        if (openBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        if (body.isBlank()) {
            val methodName = match.groupValues[1]
            if (simpleTypeName != null) {
                obsoleteListeners += CumulusMenuListenerReference(
                    simpleTypeName = simpleTypeName,
                    qualifiedTypeName = if (packageName.isBlank()) simpleTypeName else "$packageName.$simpleTypeName",
                    methodName = methodName
                )
            }
            val removalStart = javaDeclarationStartWithLeadingMetadata(result, match.range.first)
            result = result.substring(0, removalStart).trimEnd() + "\n\n" + result.substring(closeBrace + 1).trimStart('\r', '\n')
            result = Regex("""(?m)^[ \t]*[A-Za-z_$][\w$.]*\.addListener\(\s*[A-Za-z_$][\w$.]*::${Regex.escape(methodName)}\s*\)\s*;\s*\r?\n""")
                .replace(result, "")
            cursor = 0
        } else {
            cursor = closeBrace + 1
        }
    }
    return CumulusMenuHelperMethodRemoval(cleanupRedundantBlankLines(result), obsoleteListeners)
}

internal fun StructuralRefactorPass.javaDeclarationStartWithLeadingMetadata(source: String, declarationStart: Int): Int {
    var start = declarationStart
    while (start > 0) {
        val previousLineEnd = source.lastIndexOf('\n', start - 2)
        val lineStart = if (previousLineEnd < 0) 0 else previousLineEnd + 1
        val line = source.substring(lineStart, start).trim()
        if (!line.startsWith("@")) break
        start = lineStart
    }
    val before = source.substring(0, start)
    val trimmedBefore = before.trimEnd()
    if (trimmedBefore.endsWith("*/")) {
        val commentEnd = before.lastIndexOf("*/") + 2
        val commentStart = before.lastIndexOf("/**", commentEnd - 2)
        if (commentStart >= 0 && before.substring(commentEnd, start).isBlank()) {
            start = commentStart
        }
    }
    return start
}

internal fun StructuralRefactorPass.removeCumulusObsoleteMenuListenerRegistrations(
    source: String,
    obsoleteListeners: Set<CumulusMenuListenerReference>
): String {
    var result = source
    var removedDirectInvocation = false
    for (listener in obsoleteListeners) {
        for (typeName in listOf(listener.simpleTypeName, listener.qualifiedTypeName).distinct()) {
            result = Regex("""(?m)^[ \t]*[A-Za-z_$][\w$.]*\.addListener\(\s*${Regex.escape(typeName)}::${Regex.escape(listener.methodName)}\s*\)\s*;\s*\r?\n""")
                .replace(result, "")
            val directInvocation = Regex("""(?m)^[ \t]*${Regex.escape(typeName)}\.${Regex.escape(listener.methodName)}\([^;\r\n]*\)\s*;\s*\r?\n""")
            if (directInvocation.containsMatchIn(result)) {
                removedDirectInvocation = true
                result = directInvocation.replace(result, "")
            }
        }
    }
    if (removedDirectInvocation) {
        result = removeEmptyCumulusObsoleteEventMethods(result)
    }
    result = removeUnusedSimpleImport(result, "com.aetherteam.cumulus.api.MenuHelper", "MenuHelper")
    result = removeUnusedSimpleImport(result, "com.aetherteam.cumulus.client.MenuHelper", "MenuHelper")
    result = removeUnusedSimpleImport(result, "com.aetherteam.cumulus.client.CumulusClient", "CumulusClient")
    result = removeUnusedSimpleImport(result, "net.neoforged.bus.api.EventPriority", "EventPriority")
    result = removeUnusedSimpleImport(result, "net.neoforged.bus.api.SubscribeEvent", "SubscribeEvent")
    result = removeUnusedSimpleImport(result, "net.minecraftforge.eventbus.api.EventPriority", "EventPriority")
    result = removeUnusedSimpleImport(result, "net.minecraftforge.eventbus.api.SubscribeEvent", "SubscribeEvent")
    return cleanupRedundantBlankLines(result)
}

internal fun StructuralRefactorPass.removeEmptyCumulusObsoleteEventMethods(source: String): String {
    var result = source
    var cursor = 0
    val methodPattern = Regex("""(?m)^[ \t]*(?:public|protected|private)\s+static\s+void\s+[A-Za-z_$][\w$]*\s*\([^)]*\)\s*\{""")
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last)
        if (openBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        if (body.isBlank()) {
            val removalStart = javaDeclarationStartWithLeadingMetadata(result, match.range.first)
            val leadingMetadata = result.substring(removalStart, match.range.first)
            if (leadingMetadata.contains("@SubscribeEvent") || leadingMetadata.contains("MenuHooks#")) {
                result = result.substring(0, removalStart).trimEnd() + "\n\n" + result.substring(closeBrace + 1).trimStart('\r', '\n')
                cursor = 0
            } else {
                cursor = closeBrace + 1
            }
        } else {
            cursor = closeBrace + 1
        }
    }
    return cleanupRedundantBlankLines(result)
}

internal fun StructuralRefactorPass.removeUnusedMenuHelperImport(source: String): String {
    val withoutImports = removeImport(
        removeImport(source, "com.aetherteam.cumulus.api.MenuHelper"),
        "com.aetherteam.cumulus.client.MenuHelper"
    )
    return if (!Regex("""\bMenuHelper\b""").containsMatchIn(withoutImports)) {
        withoutImports
    } else {
        source
    }
}

internal fun StructuralRefactorPass.cleanupRedundantBlankLines(source: String): String =
    Regex("""\n{3,}""").replace(source, "\n\n")

internal fun StructuralRefactorPass.migrateRegisterAdditionalModelResourceLocations(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/") ||
                rel.startsWith("src/references/") || rel.contains("/src/references/")
        }}
        .toList()
    if (javaFiles.isEmpty()) return changes

    val sources = javaFiles.associateWith { it.readText() }.toMutableMap()
    val classFiles = mutableMapOf<String, MutableList<Path>>()
    for ((file, source) in sources) {
        val className = classNameOfJavaSource(source) ?: continue
        classFiles.getOrPut(className) { mutableListOf() }.add(file)
    }
    val sourcesByClass = sources.values.mapNotNull { source ->
        classNameOfJavaSource(source)?.let { it to source }
    }.toMap()

    for ((file, original) in sources.toMap()) {
        val migrated = rewriteRegisterAdditionalStandaloneModelPaths(original, projectDir, sourcesByClass)
        if (migrated == original) continue
        sources[file] = migrated
        if (!dryRun) file.writeText(migrated)
        changes.add(Change(
            file = file,
            line = 1,
            description = "Preserve legacy inventory model resource path for RegisterAdditional standalone model",
            before = "ModelResourceLocation.standalone(modid:path)",
            after = "ModelResourceLocation.standalone(modid:item/path)",
            confidence = Confidence.HIGH,
            ruleId = "struct-registeradditional-model-item-path"
        ))
    }

    val registeredFields = linkedSetOf<Pair<String, String>>()
    for ((_, source) in sources) {
        if (!source.contains("RegisterAdditional") || !source.contains(".register(")) continue
        registeredFields.addAll(collectRegisterAdditionalModelFieldReferences(source))
    }
    if (registeredFields.isEmpty()) return changes

    for ((className, fieldName) in registeredFields) {
        val targetFile = classFiles[className]?.singleOrNull() ?: continue
        val original = sources[targetFile] ?: targetFile.readText()
        val migrated = rewriteStaticModelResourceLocationField(original, fieldName, projectDir, sourcesByClass)
        if (migrated == original) continue
        sources[targetFile] = migrated
        if (!dryRun) targetFile.writeText(migrated)
        changes.add(Change(
            file = targetFile,
            line = Regex("""\bModelResourceLocation\s+${Regex.escape(fieldName)}\s*=""")
                .find(original)
                ?.let { lineNumberAt(original, it.range.first) }
                ?: 1,
            description = "Use standalone ModelResourceLocation for RegisterAdditional static model key",
            before = "$className.$fieldName = ModelResourceLocation.inventory(...)",
            after = "$className.$fieldName = ModelResourceLocation.standalone(...)",
            confidence = Confidence.HIGH,
            ruleId = "struct-registeradditional-model-standalone"
        ))
    }
    return changes
}

internal data class ResolvedResourceLocationExpression(
    val namespace: String,
    val path: String,
    val rewrittenExpression: String
)

internal fun StructuralRefactorPass.rewriteRegisterAdditionalStandaloneModelPaths(
    source: String,
    projectDir: Path,
    sourcesByClass: Map<String, String>
): String {
    return rewriteRegisterAdditionalRegisterCalls(source) { arg ->
        rewriteRegisteredModelResourceLocationExpression(arg, projectDir, sourcesByClass)
    }
}

internal fun StructuralRefactorPass.rewriteRegisterAdditionalRegisterCalls(source: String, transformArg: (String) -> String?): String {
    var result = source
    val parameterPattern = Regex("""\b(?:[A-Za-z_$][\w$]*\.)?RegisterAdditional\s+([A-Za-z_$][\w$]*)\b""")
    var cursor = 0
    while (true) {
        val paramMatch = parameterPattern.find(result, cursor) ?: break
        val openParen = result.lastIndexOf('(', paramMatch.range.first)
        val closeParen = result.indexOf(')', paramMatch.range.last + 1)
        val openBrace = if (closeParen >= 0) result.indexOf('{', closeParen + 1) else -1
        if (openParen < 0 || closeParen < 0 || openBrace < 0) {
            cursor = paramMatch.range.last + 1
            continue
        }
        val semicolon = result.indexOf(';', closeParen + 1)
        if (semicolon >= 0 && semicolon < openBrace) {
            cursor = paramMatch.range.last + 1
            continue
        }
        val params = result.substring(openParen + 1, closeParen)
        if (!params.contains(paramMatch.value)) {
            cursor = paramMatch.range.last + 1
            continue
        }
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) {
            cursor = paramMatch.range.last + 1
            continue
        }
        val eventParam = paramMatch.groupValues[1]
        val body = result.substring(openBrace + 1, closeBrace)
        val rewrittenBody = rewriteJavaCall(body, "register") { receiver, args ->
            if (receiver != eventParam || args.size != 1) return@rewriteJavaCall null
            val rewrittenArg = transformArg(args[0].trim()) ?: return@rewriteJavaCall null
            "$receiver.register($rewrittenArg)"
        }
        if (rewrittenBody != body) {
            result = result.substring(0, openBrace + 1) + rewrittenBody + result.substring(closeBrace)
            cursor = openBrace + 1 + rewrittenBody.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.collectRegisterAdditionalModelFieldReferences(source: String): Set<Pair<String, String>> {
    val refs = linkedSetOf<Pair<String, String>>()
    val ownerClass = classNameOfJavaSource(source)
    val parameterPattern = Regex("""\b(?:[A-Za-z_$][\w$]*\.)?RegisterAdditional\s+([A-Za-z_$][\w$]*)\b""")
    var cursor = 0
    while (true) {
        val paramMatch = parameterPattern.find(source, cursor) ?: break
        val openParen = source.lastIndexOf('(', paramMatch.range.first)
        val closeParen = source.indexOf(')', paramMatch.range.last + 1)
        val openBrace = if (closeParen >= 0) source.indexOf('{', closeParen + 1) else -1
        if (openParen < 0 || closeParen < 0 || openBrace < 0) {
            cursor = paramMatch.range.last + 1
            continue
        }
        val semicolon = source.indexOf(';', closeParen + 1)
        if (semicolon >= 0 && semicolon < openBrace) {
            cursor = paramMatch.range.last + 1
            continue
        }
        val params = source.substring(openParen + 1, closeParen)
        if (!params.contains(paramMatch.value)) {
            cursor = paramMatch.range.last + 1
            continue
        }
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) {
            cursor = paramMatch.range.last + 1
            continue
        }
        val eventParam = paramMatch.groupValues[1]
        val body = source.substring(openBrace + 1, closeBrace)
        collectJavaCallArguments(body, "register") { receiver, args ->
            if (receiver == eventParam && args.size == 1) {
                parseStaticModelResourceLocationReference(args[0].trim(), ownerClass)?.let(refs::add)
            }
        }
        cursor = closeBrace + 1
    }
    return refs
}

internal fun StructuralRefactorPass.collectJavaCallArguments(
    source: String,
    methodName: String,
    consumer: (receiver: String, args: List<String>) -> Unit
) {
    var cursor = 0
    val token = ".${methodName}("
    while (true) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + methodName.length + 1
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val receiverStart = findExpressionReceiverStart(source, tokenIndex)
        if (receiverStart < 0 || receiverStart >= tokenIndex) {
            cursor = closeParen + 1
            continue
        }
        val receiver = source.substring(receiverStart, tokenIndex).trim()
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        consumer(receiver, args)
        cursor = closeParen + 1
    }
}

internal fun StructuralRefactorPass.parseStaticModelResourceLocationReference(arg: String, ownerClass: String?): Pair<String, String>? {
    if (arg.startsWith("ModelResourceLocation.") || arg.startsWith("new ModelResourceLocation(")) return null
    val qualified = Regex("""^([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)$""").matchEntire(arg)
    if (qualified != null) {
        return qualified.groupValues[1] to qualified.groupValues[2]
    }
    if (ownerClass != null && Regex("""^[A-Za-z_$][\w$]*$""").matches(arg)) {
        return ownerClass to arg
    }
    return null
}

internal fun StructuralRefactorPass.rewriteStaticModelResourceLocationField(
    source: String,
    fieldName: String,
    projectDir: Path,
    sourcesByClass: Map<String, String>
): String {
    val fieldPattern = Regex(
        """(?m)^[^\r\n;{}]*\bstatic\b[^\r\n;{}]*\bModelResourceLocation\s+${Regex.escape(fieldName)}\s*="""
    )
    val match = fieldPattern.find(source) ?: return source
    val expressionStart = match.range.last + 1
    var cursor = expressionStart
    while (cursor < source.length && source[cursor].isWhitespace()) cursor++
    val callStart = cursor
    val openParen = source.indexOf('(', callStart).takeIf { it >= 0 } ?: return source
    val closeParen = findMatchingParen(source, openParen).takeIf { it >= 0 } ?: return source
    val expressionEnd = closeParen + 1
    val trailing = source.substring(expressionEnd).takeWhile { it.isWhitespace() }
    if (source.getOrNull(expressionEnd + trailing.length) != ';') return source
    val expression = source.substring(callStart, expressionEnd)
    val standalone = rewriteRegisteredModelResourceLocationExpression(expression, projectDir, sourcesByClass)
        ?: modelResourceLocationStandaloneExpression(expression)
        ?: return source
    return source.substring(0, callStart) + standalone + source.substring(expressionEnd)
}

internal fun StructuralRefactorPass.rewriteRegisteredModelResourceLocationExpression(
    expression: String,
    projectDir: Path,
    sourcesByClass: Map<String, String>
): String? {
    val trimmed = expression.trim()
    val modelExpression = when {
        trimmed.startsWith("ModelResourceLocation.inventory(") || trimmed.startsWith("ModelResourceLocation.standalone(") -> {
            val openParen = trimmed.indexOf('(')
            val closeParen = findMatchingParen(trimmed, openParen)
            if (closeParen != trimmed.length - 1) return null
            trimmed.substring(openParen + 1, closeParen).trim()
        }
        trimmed.startsWith("new ModelResourceLocation(") -> {
            val openParen = trimmed.indexOf('(')
            val closeParen = findMatchingParen(trimmed, openParen)
            if (closeParen != trimmed.length - 1) return null
            val args = splitTopLevelJavaArgs(trimmed.substring(openParen + 1, closeParen))
            if (args.size != 2 || args[1].trim() != "\"inventory\"") return null
            args[0].trim()
        }
        else -> return null
    }
    val rewrittenModel = rewriteStandaloneModelResourcePath(modelExpression, projectDir, sourcesByClass)
    val standalone = "ModelResourceLocation.standalone(${rewrittenModel ?: modelExpression})"
    return if (trimmed.startsWith("ModelResourceLocation.standalone(") && rewrittenModel == null) null else standalone
}

internal fun StructuralRefactorPass.rewriteStandaloneModelResourcePath(
    expression: String,
    projectDir: Path,
    sourcesByClass: Map<String, String>
): String? {
    val resolved = resolveResourceLocationExpression(expression, sourcesByClass) ?: return null
    if (resolved.path.startsWith("item/")) return null
    if (modelJsonExists(projectDir, resolved.namespace, resolved.path)) return null
    val itemPath = "item/${resolved.path}"
    if (!modelJsonExists(projectDir, resolved.namespace, itemPath)) return null
    return resolved.rewrittenExpression
}

internal fun StructuralRefactorPass.resolveResourceLocationExpression(
    expression: String,
    sourcesByClass: Map<String, String>
): ResolvedResourceLocationExpression? {
    val trimmed = expression.trim()
    val prefixCall = Regex("""^([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\(\s*"([^"]+)"\s*\)$""").matchEntire(trimmed)
    if (prefixCall != null) {
        val className = prefixCall.groupValues[1]
        val methodName = prefixCall.groupValues[2]
        val path = prefixCall.groupValues[3]
        val namespace = resolveResourceLocationFactoryNamespace(className, methodName, sourcesByClass) ?: return null
        return ResolvedResourceLocationExpression(namespace, path, "$className.$methodName(\"item/$path\")")
    }

    val fromNamespaceAndPath = Regex(
        """^ResourceLocation\.fromNamespaceAndPath\(\s*([^,]+)\s*,\s*"([^"]+)"\s*\)$"""
    ).matchEntire(trimmed)
    if (fromNamespaceAndPath != null) {
        val namespaceExpression = fromNamespaceAndPath.groupValues[1].trim()
        val path = fromNamespaceAndPath.groupValues[2]
        val namespace = resolveStringExpression(namespaceExpression, sourcesByClass) ?: return null
        return ResolvedResourceLocationExpression(
            namespace,
            path,
            "ResourceLocation.fromNamespaceAndPath($namespaceExpression, \"item/$path\")"
        )
    }

    val constructor = Regex("""^new ResourceLocation\(\s*([^,]+)\s*,\s*"([^"]+)"\s*\)$""").matchEntire(trimmed)
    if (constructor != null) {
        val namespaceExpression = constructor.groupValues[1].trim()
        val path = constructor.groupValues[2]
        val namespace = resolveStringExpression(namespaceExpression, sourcesByClass) ?: return null
        return ResolvedResourceLocationExpression(namespace, path, "new ResourceLocation($namespaceExpression, \"item/$path\")")
    }
    return null
}

internal fun StructuralRefactorPass.resolveResourceLocationFactoryNamespace(
    className: String,
    methodName: String,
    sourcesByClass: Map<String, String>
): String? {
    val source = sourcesByClass[className] ?: return null
    val methodPattern = Regex(
        """(?s)\b${Regex.escape(methodName)}\s*\(\s*String\s+([A-Za-z_$][\w$]*)\s*\)\s*\{(.*?)\}"""
    )
    val match = methodPattern.find(source) ?: return null
    val body = match.groupValues[2]
    val namespaceExpression = Regex("""ResourceLocation\.fromNamespaceAndPath\(\s*([^,]+)\s*,""")
        .find(body)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: Regex("""new\s+ResourceLocation\(\s*([^,]+)\s*,""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.trim()
        ?: return null
    return resolveStaticStringField(source, namespaceExpression)
        ?: resolveStringExpression(namespaceExpression, sourcesByClass + (className to source))
}

internal fun StructuralRefactorPass.resolveStringExpression(expression: String, sourcesByClass: Map<String, String>): String? {
    val trimmed = expression.trim()
    if (Regex(""""(?:\\.|[^"\\])*"""").matches(trimmed)) return trimmed.trim('"')
    val fieldRef = Regex("""^([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)$""").matchEntire(trimmed)
    if (fieldRef != null) {
        val source = sourcesByClass[fieldRef.groupValues[1]] ?: return null
        return resolveStaticStringField(source, fieldRef.groupValues[2])
    }
    return sourcesByClass.values.asSequence()
        .mapNotNull { resolveStaticStringField(it, trimmed) }
        .distinct()
        .singleOrNull()
}

internal fun StructuralRefactorPass.resolveStaticStringField(source: String, fieldName: String): String? =
    Regex("""(?m)\bstatic\s+final\s+String\s+${Regex.escape(fieldName)}\s*=\s*"([^"]+)"""")
        .find(source)
        ?.groupValues
        ?.get(1)

internal fun StructuralRefactorPass.modelJsonExists(projectDir: Path, namespace: String, modelPath: String): Boolean {
    val relative = Path.of("assets", namespace, "models", "$modelPath.json")
    return listOf(
        projectDir.resolve("src/generated/resources"),
        projectDir.resolve("src/main/resources")
    ).any { it.resolve(relative).exists() }
}

internal fun StructuralRefactorPass.modelResourceLocationStandaloneExpression(expression: String): String? {
    val trimmed = expression.trim()
    if (trimmed.startsWith("ModelResourceLocation.standalone(")) return null
    if (trimmed.startsWith("ModelResourceLocation.inventory(")) {
        val openParen = trimmed.indexOf('(')
        val closeParen = findMatchingParen(trimmed, openParen)
        if (closeParen == trimmed.length - 1) {
            return "ModelResourceLocation.standalone(${trimmed.substring(openParen + 1, closeParen).trim()})"
        }
    }
    if (trimmed.startsWith("new ModelResourceLocation(")) {
        val openParen = trimmed.indexOf('(')
        val closeParen = findMatchingParen(trimmed, openParen)
        if (closeParen == trimmed.length - 1) {
            val args = splitTopLevelJavaArgs(trimmed.substring(openParen + 1, closeParen))
            if (args.size == 2 && args[1].trim() == "\"inventory\"") {
                return "ModelResourceLocation.standalone(${args[0].trim()})"
            }
        }
    }
    return null
}

internal fun StructuralRefactorPass.migrateVertexConsumerPoseNormals(source: String): String {
    if (!source.contains(".setNormal(") && !source.contains(".normal(")) return source

    var result = source
    result = Regex("""((?:[A-Za-z_$][\w$]*\.)*last\(\))\.setNormal\(\)""")
        .replace(result, "$1.normal()")
    result = Regex("""\.(?:setNormal|normal)\(\s*([A-Za-z_$][\w$]*)\.normal\(\)\s*,""")
        .replace(result, ".setNormal($1,")
    result = Regex("""\.(?:setNormal|normal)\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\(\))*)\.normal\(\)\s*,""")
        .replace(result, ".setNormal($1,")
    if (!result.contains("Matrix3f")) return result
    val poseNormalReceiver = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^()]*\))?)*"""
    val normalToPose = Regex(
        """\bMatrix3f\s+([A-Za-z_$][\w$]*)\s*=\s*($poseNormalReceiver)\.normal\(\)\s*;"""
    ).findAll(result).associate { it.groupValues[1] to it.groupValues[2] }
    val poseNormalMethodIndexes = linkedMapOf<String, MutableSet<Int>>()
    val methodPattern = Regex(
        """(?m)((?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+([A-Za-z_$][\w$]*)\s*)\(([^)]*)\)\s*\{"""
    )

    var changed = true
    while (changed) {
        changed = false
        var cursor = 0
        while (true) {
            val method = methodPattern.find(result, cursor) ?: break
            val openBrace = result.indexOf('{', method.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (openBrace < 0 || closeBrace < 0) {
                cursor = method.range.last + 1
                continue
            }
            val params = splitTopLevelJavaArgs(method.groupValues[3])
            if (params.none { it.contains("Matrix3f") }) {
                cursor = closeBrace + 1
                continue
            }
            val body = result.substring(openBrace + 1, closeBrace)
            val normalIndexes = linkedSetOf<Int>()
            val migratedParams = params.mapIndexed { index, param ->
                val paramMatch = Regex("""\bMatrix3f\s+([A-Za-z_$][\w$]*)\b""").find(param)
                val paramName = paramMatch?.groupValues?.get(1)
                if (paramName != null &&
                    (body.contains(".setNormal($paramName,") ||
                        body.contains(".normal($paramName,") ||
                        passesParameterToKnownPoseNormalHelper(body, paramName, poseNormalMethodIndexes))) {
                    normalIndexes += index
                    param.replace(Regex("""\bMatrix3f\s+${Regex.escape(paramName)}\b"""), "PoseStack.Pose $paramName")
                } else {
                    param
                }
            }
            if (normalIndexes.isNotEmpty()) {
                val replacementSignature = method.groupValues[1] + "(" + migratedParams.joinToString(", ") { it.trim() } + ")"
                result = result.substring(0, method.range.first) +
                    replacementSignature +
                    result.substring(openBrace)
                poseNormalMethodIndexes.getOrPut(method.groupValues[2]) { linkedSetOf() }.addAll(normalIndexes)
                val delta = replacementSignature.length - (openBrace - method.range.first)
                cursor = closeBrace + delta + 1
                changed = true
            } else {
                cursor = closeBrace + 1
            }
        }
    }

    poseNormalMethodIndexes.forEach { (methodName, indexes) ->
        result = rewriteJavaInvocationArguments(result, methodName) { args ->
            if (indexes.any { it >= args.size }) return@rewriteJavaInvocationArguments null
            val migrated = args.mapIndexed { index, arg ->
                if (index in indexes) poseNormalArgumentToPose(arg, normalToPose) else arg
            }
            if (migrated == args) null else migrated
        }
    }
    result = migrateLocalPoseNormalSetNormalCalls(result)
    result = removeUnusedPoseNormalMatrixLocals(result)
    if (result.contains("PoseStack.Pose")) {
        result = addImportIfMissing(result, "com.mojang.blaze3d.vertex.PoseStack")
    }

    val withoutMatrix3fImport = removeImport(result, "org.joml.Matrix3f")
    if (!Regex("""\bMatrix3f\b""").containsMatchIn(withoutMatrix3fImport)) {
        result = withoutMatrix3fImport
    }
    return result
}

internal fun StructuralRefactorPass.passesParameterToKnownPoseNormalHelper(
    body: String,
    paramName: String,
    poseNormalMethodIndexes: Map<String, Set<Int>>
): Boolean {
    if (poseNormalMethodIndexes.isEmpty()) return false
    poseNormalMethodIndexes.forEach { (methodName, indexes) ->
        var cursor = 0
        val token = "$methodName("
        while (true) {
            val tokenIndex = body.indexOf(token, cursor)
            if (tokenIndex < 0) break
            if (tokenIndex > 0 && (body[tokenIndex - 1].isLetterOrDigit() || body[tokenIndex - 1] == '_' || body[tokenIndex - 1] == '$')) {
                cursor = tokenIndex + token.length
                continue
            }
            val openParen = tokenIndex + methodName.length
            val closeParen = findMatchingParen(body, openParen)
            if (closeParen < 0) {
                cursor = tokenIndex + token.length
                continue
            }
            val args = splitTopLevelJavaArgs(body.substring(openParen + 1, closeParen))
            if (indexes.any { it < args.size && args[it].trim() == paramName }) {
                return true
            }
            cursor = closeParen + 1
        }
    }
    return false
}

internal fun StructuralRefactorPass.poseNormalArgumentToPose(arg: String, normalToPose: Map<String, String>): String {
    val trimmed = arg.trim()
    normalToPose[trimmed]?.let { return it }
    return when {
        trimmed.endsWith(".normal()") -> trimmed.removeSuffix(".normal()")
        trimmed.endsWith(".setNormal()") -> trimmed.removeSuffix(".setNormal()")
        else -> arg
    }
}

internal fun StructuralRefactorPass.removeUnusedPoseNormalMatrixLocals(source: String): String {
    var result = source
    val methodPattern = Regex("""(?m)(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*\([^)]*\)\s*\{""")
    var cursor = 0
    while (true) {
        val method = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', method.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = method.range.last + 1
            continue
        }
        var body = result.substring(openBrace + 1, closeBrace)
        val originalBody = body
        val poseNormalReceiver = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^()]*\))?)*"""
        val declaration = Regex("""(?m)^[ \t]*Matrix3f\s+([A-Za-z_$][\w$]*)\s*=\s*$poseNormalReceiver\.normal\(\)\s*;\s*\r?\n""")
        var localCursor = 0
        while (true) {
            val match = declaration.find(body, localCursor) ?: break
            val variable = match.groupValues[1]
            val withoutDeclaration = body.removeRange(match.range)
            if (!Regex("""\b${Regex.escape(variable)}\b""").containsMatchIn(withoutDeclaration)) {
                body = withoutDeclaration
                localCursor = match.range.first
            } else {
                localCursor = match.range.last + 1
            }
        }
        if (body != originalBody) {
            result = result.substring(0, openBrace + 1) + body + result.substring(closeBrace)
            cursor = openBrace + 1 + body.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLocalPoseNormalSetNormalCalls(source: String): String {
    var result = source
    var cursor = 0
    val methodPattern = Regex("""(?m)(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*\([^)]*\)\s*\{""")
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val localNormalToPose = Regex(
            """\bMatrix3f\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^()]*\))?)*)\.normal\(\)\s*;"""
        ).findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        if (localNormalToPose.isEmpty()) {
            cursor = closeBrace + 1
            continue
        }
        var rewrittenBody = body
        for ((normal, pose) in localNormalToPose) {
            rewrittenBody = Regex("""\.setNormal\(\s*${Regex.escape(normal)}\s*,""")
                .replace(rewrittenBody, ".setNormal($pose,")
        }
        if (rewrittenBody != body) {
            result = result.substring(0, openBrace + 1) + rewrittenBody + result.substring(closeBrace)
            cursor = openBrace + 1 + rewrittenBody.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyGuiLayerRegistrationSource(source: String): String {
    if (!source.contains("VanillaGuiOverlay") && !source.contains("registerAbove(") && !source.contains("registerBelow(")) {
        return source
    }

    var result = source
    var needsVanillaGuiLayers = false
    var needsResourceLocation = false
    val vanillaLayerNames = mapOf(
        "HOTBAR" to "HOTBAR",
        "MOUNT_HEALTH" to "VEHICLE_HEALTH",
        "CROSSHAIR" to "CROSSHAIR",
        "EXPERIENCE_BAR" to "EXPERIENCE_BAR",
        "PLAYER_HEALTH" to "PLAYER_HEALTH",
        "ARMOR_LEVEL" to "ARMOR_LEVEL",
        "FOOD_LEVEL" to "FOOD_LEVEL",
        "AIR_LEVEL" to "AIR_LEVEL",
        "BOSS_OVERLAY" to "BOSS_OVERLAY",
        "CHAT" to "CHAT",
        "TAB_LIST" to "TAB_LIST"
    )
    for ((oldName, newName) in vanillaLayerNames) {
        val before = result
        result = result.replace("VanillaGuiOverlay.$oldName.id()", "VanillaGuiLayers.$newName")
        if (result != before) needsVanillaGuiLayers = true
    }

    val modIdExpression = Regex("""@(?:Mod\.)?EventBusSubscriber\s*\([^)]*\bmodid\s*=\s*([^,\)]+)""")
        .find(result)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: Regex("""@Mod\s*\(\s*([^)\r\n]+)\s*\)""")
            .find(result)
            ?.groupValues
            ?.get(1)
            ?.trim()
        ?: Regex("""(?m)\b(?:public\s+)?static\s+final\s+String\s+(?:MOD_ID|MODID|ID)\s*=\s*([^;\r\n]+);""")
            .find(result)
            ?.groupValues
            ?.get(1)
            ?.trim()

    fun migrateRegisterLayerCall(methodName: String, expectedArgs: Int): Unit {
        result = rewriteJavaInvocationArguments(result, methodName) { args ->
            if (args.size != expectedArgs) return@rewriteJavaInvocationArguments null
            val idIndex = expectedArgs - 2
            val layerIndex = expectedArgs - 1
            val rawLayerId = args[idIndex].trim()
            if (!Regex(""""(?:\\.|[^"\\])*"""").matches(rawLayerId)) return@rewriteJavaInvocationArguments null
            val namespace = modIdExpression ?: return@rewriteJavaInvocationArguments null
            val migrated = args.toMutableList()
            migrated[idIndex] = "ResourceLocation.fromNamespaceAndPath($namespace, ${rawLayerId})"
            migrated[layerIndex] = migrateLegacyGuiLayerLambda(migrated[layerIndex])
            needsResourceLocation = true
            migrated
        }
    }

    migrateRegisterLayerCall("registerAbove", 3)
    migrateRegisterLayerCall("registerBelow", 3)
    migrateRegisterLayerCall("registerAboveAll", 2)
    migrateRegisterLayerCall("registerBelowAll", 2)

    result = migrateLegacyGuiLayerLambdas(result)

    if (needsVanillaGuiLayers) {
        result = addImportIfMissing(result, "net.neoforged.neoforge.client.gui.VanillaGuiLayers")
    }
    if (needsResourceLocation) {
        result = addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
    }
    result = removeImport(result, "net.neoforged.neoforge.client.gui.overlay.VanillaGuiOverlay")
    result = removeImport(result, "net.minecraftforge.client.gui.overlay.VanillaGuiOverlay")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyGuiLayerLambdas(source: String): String {
    var result = source
    result = rewriteJavaInvocationArguments(result, "registerAbove") { args ->
        if (args.size == 3) {
            val migrated = args.toMutableList()
            migrated[2] = migrateLegacyGuiLayerLambda(args[2])
            if (migrated == args) null else migrated
        } else {
            null
        }
    }
    result = rewriteJavaInvocationArguments(result, "registerBelow") { args ->
        if (args.size == 3) {
            val migrated = args.toMutableList()
            migrated[2] = migrateLegacyGuiLayerLambda(args[2])
            if (migrated == args) null else migrated
        } else {
            null
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyGuiLayerLambda(lambdaSource: String): String {
    val lambda = lambdaSource.trim()
    val match = Regex(
        """^\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*->\s*\{([\s\S]*)}$"""
    ).find(lambda) ?: return lambdaSource
    val guiName = match.groupValues[1]
    val graphicsName = match.groupValues[2]
    val partialTickName = match.groupValues[3]
    val widthName = match.groupValues[4]
    val heightName = match.groupValues[5]
    val body = match.groupValues[6]
    val locals = mutableListOf<String>()
    if (Regex("""\b${Regex.escape(guiName)}\b""").containsMatchIn(body)) {
        locals += "net.minecraft.client.gui.Gui $guiName = net.minecraft.client.Minecraft.getInstance().gui;"
    }
    if (Regex("""\b${Regex.escape(partialTickName)}\b""").containsMatchIn(body)) {
        locals += "float $partialTickName = deltaTracker.getGameTimeDeltaPartialTick(false);"
    }
    if (Regex("""\b${Regex.escape(widthName)}\b""").containsMatchIn(body)) {
        locals += "int $widthName = $graphicsName.guiWidth();"
    }
    if (Regex("""\b${Regex.escape(heightName)}\b""").containsMatchIn(body)) {
        locals += "int $heightName = $graphicsName.guiHeight();"
    }
    val prefix = if (locals.isEmpty()) "" else locals.joinToString(separator = "\n", postfix = "\n") { "\t\t\t$it" }
    return "($graphicsName, deltaTracker) -> {\n$prefix$body}"
}

internal fun StructuralRefactorPass.migrateRenderGuiLayerEventOverlayApis(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("RenderGuiLayerEvent") &&
        !executableCode.contains("VanillaGuiOverlay") &&
        !executableCode.contains("setupOverlayRenderState")) {
        return source
    }

    var result = source
    var needsVanillaGuiLayers = false
    result = replaceExecutableRegex(
        result,
        Regex("""\bVanillaGuiOverlay\.([A-Z0-9_]+)\.type\(\s*\)""")
    ) { match ->
        needsVanillaGuiLayers = true
        "VanillaGuiLayers.${match.groupValues[1]}"
    }
    result = replaceExecutableRegex(result, Regex("""\b([A-Za-z_$][\w$]*)\.getOverlay\(\s*\)""")) { match ->
        "${match.groupValues[1]}.getName()"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""([A-Za-z_$][\w$]*\.getName\(\s*\))\s*==\s*(VanillaGuiLayers\.[A-Z0-9_]+)""")
    ) { match ->
        "${match.groupValues[2]}.equals(${match.groupValues[1]})"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""([A-Za-z_$][\w$]*\.getName\(\s*\))\s*!=\s*(VanillaGuiLayers\.[A-Z0-9_]+)""")
    ) { match ->
        "!${match.groupValues[2]}.equals(${match.groupValues[1]})"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(?m)^([ \t]*)[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.setupOverlayRenderState\(\s*true\s*,\s*(?:true|false)\s*\)\s*;\s*\r?\n""")
    ) { match ->
        "${match.groupValues[1]}RenderSystem.enableBlend();\n"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(?m)^([ \t]*)[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.setupOverlayRenderState\(\s*false\s*,\s*(?:true|false)\s*\)\s*;\s*\r?\n""")
    ) { match ->
        "${match.groupValues[1]}RenderSystem.disableBlend();\n"
    }
    if (result == source) return source
    if (needsVanillaGuiLayers) {
        result = addImportIfMissing(result, "net.neoforged.neoforge.client.gui.VanillaGuiLayers")
    }
    if (result.contains("RenderSystem.") && !source.contains("import com.mojang.blaze3d.systems.RenderSystem;")) {
        result = addImportIfMissing(result, "com.mojang.blaze3d.systems.RenderSystem")
    }
    result = removeImport(result, "net.neoforged.neoforge.client.gui.overlay.VanillaGuiOverlay")
    result = removeImport(result, "net.minecraftforge.client.gui.overlay.VanillaGuiOverlay")
    return result
}

internal fun StructuralRefactorPass.migrateRenderGuiLayerEventWindowAccess(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("RenderGuiLayerEvent") || !executableCode.contains(".getWindow()")) return source
    var result = source
    var cursor = 0
    val methodWithGuiLayerEvent = Regex(
        """\([^)]*\b(?:net\.neoforged\.neoforge\.client\.event\.)?RenderGuiLayerEvent(?:\.[A-Za-z_$][\w$]*)?\s+([A-Za-z_$][\w$]*)[^)]*\)\s*\{"""
    )
    while (true) {
        val match = methodWithGuiLayerEvent.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val eventName = match.groupValues[1]
        val body = result.substring(openBrace + 1, closeBrace)
        var rewritten = body
        rewritten = Regex("""\b${Regex.escape(eventName)}\.getWindow\(\)\.getGuiScaledWidth\(\)""")
            .replace(rewritten, "$eventName.getGuiGraphics().guiWidth()")
        rewritten = Regex("""\b${Regex.escape(eventName)}\.getWindow\(\)\.getGuiScaledHeight\(\)""")
            .replace(rewritten, "$eventName.getGuiGraphics().guiHeight()")
        rewritten = Regex(
            """\b((?:com\.mojang\.blaze3d\.platform\.)?Window\s+[A-Za-z_$][\w$]*\s*=\s*)${Regex.escape(eventName)}\.getWindow\(\)"""
        ).replace(rewritten) { declaration ->
            "${declaration.groupValues[1]}net.minecraft.client.Minecraft.getInstance().getWindow()"
        }
        if (rewritten != body) {
            result = result.substring(0, openBrace + 1) + rewritten + result.substring(closeBrace)
            cursor = openBrace + 1 + rewritten.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyGuiSurvivalElementsSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".shouldDrawSurvivalElements()")) return source
    return replaceExecutableRegex(source, Regex("""\b[A-Za-z_$][\w$]*\.shouldDrawSurvivalElements\(\)""")) {
        "net.minecraft.client.Minecraft.getInstance().gameMode.canHurtPlayer() && !net.minecraft.client.Minecraft.getInstance().options.hideGui"
    }
}

internal fun StructuralRefactorPass.migrateLegacyJumpFromGroundVisibility(source: String): String =
    Regex("""(?m)^(\s*)protected(\s+void\s+jumpFromGround\s*\(\s*\))""")
        .replace(source, "$1public$2")

internal fun StructuralRefactorPass.migrateTextureAtlasSpriteFloatCoordinateCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getU(") && !executableCode.contains(".getV(")) return source
    var result = source
    fun needsFloatCast(arg: String): Boolean {
        val trimmed = arg.trim()
        return !trimmed.startsWith("(float)") &&
            (
                "(double)" in trimmed ||
                    Regex("""\d(?:\.\d+)?[dD]\b""").containsMatchIn(trimmed) ||
                    Regex("""\bdouble\b""").containsMatchIn(trimmed)
                )
    }
    result = rewriteExecutableJavaCall(result, "getU") { receiver, args ->
        if (args.size == 1 && needsFloatCast(args[0])) {
            "$receiver.getU((float) (${args[0]}))"
        } else {
            null
        }
    }
    result = rewriteExecutableJavaCall(result, "getV") { receiver, args ->
        if (args.size == 1 && needsFloatCast(args[0])) {
            "$receiver.getV((float) (${args[0]}))"
        } else {
            null
        }
    }
    return result
}

internal fun StructuralRefactorPass.migratePartialTickAccessors(source: String): String {
    if (!source.contains("getPartialTick()") &&
        !source.contains("getFrameTime()") &&
        !source.contains("getDeltaFrameTime()")) return source
    var result = MinecraftFrameTimeCalls.migrate(source, projectFrameTimeMethods).replace(
        "Minecraft.getInstance().getPartialTick()",
        "Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)"
    ).replace(
        "Minecraft.getInstance().getFrameTime()",
        "Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)"
    ).replace(
        "Minecraft.getInstance().getDeltaFrameTime()",
        "Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)"
    )

    val minecraftVariables = Regex("""\bMinecraft\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    for (variable in minecraftVariables) {
        result = result.replace(
            "$variable.getPartialTick()",
            "$variable.getTimer().getGameTimeDeltaPartialTick(false)"
        )
        result = result.replace(
            "$variable.getFrameTime()",
            "$variable.getTimer().getGameTimeDeltaPartialTick(false)"
        )
        result = result.replace(
            "$variable.getDeltaFrameTime()",
            "$variable.getTimer().getGameTimeDeltaPartialTick(false)"
        )
    }

    result = rewritePartialTickWithinRenderHighlightMethods(result)
    result = rewritePartialTickWithinDeltaTrackerEvents(result)

    return result
}

internal fun StructuralRefactorPass.rewritePartialTickWithinRenderHighlightMethods(source: String): String {
    var result = source
    var cursor = 0
    val methodWithRenderHighlight = Regex(
        """\([^)]*\b(?:net\.neoforged\.neoforge\.client\.event\.)?RenderHighlightEvent(?:\.[A-Za-z_$][\w$]*)?\s+([A-Za-z_$][\w$]*)[^)]*\)\s*\{"""
    )
    while (true) {
        val match = methodWithRenderHighlight.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val variable = match.groupValues[1]
        val body = result.substring(openBrace + 1, closeBrace)
        val rewritten = Regex("""\b${Regex.escape(variable)}\.getPartialTick\(\)(?!\s*\.)""")
            .replace(body, "$variable.getDeltaTracker().getGameTimeDeltaPartialTick(false)")
        if (rewritten != body) {
            result = result.substring(0, openBrace + 1) + rewritten + result.substring(closeBrace)
            cursor = openBrace + 1 + rewritten.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.rewritePartialTickWithinDeltaTrackerEvents(source: String): String {
    var result = source
    var cursor = 0
    val methodWithDeltaTrackerPartial = Regex(
        """\([^)]*\b(?:net\.neoforged\.neoforge\.client\.event\.)?(?:RenderLevelStageEvent|RenderGuiLayerEvent(?:\.[A-Za-z_$][\w$]*)?)\s+([A-Za-z_$][\w$]*)[^)]*\)\s*\{"""
    )
    while (true) {
        val match = methodWithDeltaTrackerPartial.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val variable = match.groupValues[1]
        val body = result.substring(openBrace + 1, closeBrace)
        val rewritten = Regex("""\b${Regex.escape(variable)}\.getPartialTick\(\)(?!\s*\.)""")
            .replace(body, "$variable.getPartialTick().getGameTimeDeltaPartialTick(false)")
        if (rewritten != body) {
            result = result.substring(0, openBrace + 1) + rewritten + result.substring(closeBrace)
            cursor = openBrace + 1 + rewritten.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEventBusPostBooleans(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("EVENT_BUS.post(")) return source
    var result = rewriteExecutableJavaCall(source, "post") { receiver, args ->
        if (!receiver.endsWith("EVENT_BUS") || args.size != 1) return@rewriteExecutableJavaCall null
        "$receiver.post(${args[0]}).isCanceled()"
    }
    result = collapseDuplicateEventBusPostCancellationChecks(result)
    return stripUnusedEventBusPostCancellationChecks(result)
}

internal fun StructuralRefactorPass.collapseDuplicateEventBusPostCancellationChecks(source: String): String {
    var result = source
    var cursor = 0
    while (true) {
        val call = findEventBusPostCall(result, cursor) ?: break
        val chainEnds = trailingIsCanceledCallEnds(result, call.closeParen + 1)
        if (chainEnds.size <= 1) {
            cursor = call.closeParen + 1
            continue
        }
        result = result.substring(0, chainEnds.first()) + result.substring(chainEnds.last())
        cursor = chainEnds.first()
    }
    return result
}

internal fun StructuralRefactorPass.stripUnusedEventBusPostCancellationChecks(source: String): String {
    var result = source
    var cursor = 0
    while (true) {
        val call = findEventBusPostCall(result, cursor) ?: break
        val chainEnds = trailingIsCanceledCallEnds(result, call.closeParen + 1)
        if (chainEnds.isEmpty()) {
            cursor = call.closeParen + 1
            continue
        }
        val chainEnd = chainEnds.last()
        val executableCode = maskJavaCommentsAndLiterals(result)
        val next = skipWhitespace(executableCode, chainEnd)
        if (next >= result.length || executableCode[next] != ';' || !isStandaloneExpressionStart(result, call.receiverStart)) {
            cursor = chainEnd
            continue
        }
        result = result.substring(0, call.closeParen + 1) + result.substring(chainEnd)
        cursor = call.closeParen + 1
    }
    return result
}

internal data class EventBusPostCall(val receiverStart: Int, val closeParen: Int)

internal fun StructuralRefactorPass.findEventBusPostCall(source: String, start: Int): EventBusPostCall? {
    val token = ".post("
    var cursor = start
    val executableCode = maskJavaCommentsAndLiterals(source)
    while (true) {
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) return null
        val openParen = tokenIndex + "post".length + 1
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val receiverStart = findExpressionReceiverStart(executableCode, tokenIndex)
        if (receiverStart < 0 || receiverStart >= tokenIndex) {
            cursor = closeParen + 1
            continue
        }
        val receiver = source.substring(receiverStart, tokenIndex).trim()
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (receiver.endsWith("EVENT_BUS") && args.size == 1) {
            return EventBusPostCall(receiverStart, closeParen)
        }
        cursor = closeParen + 1
    }
}

internal fun StructuralRefactorPass.trailingIsCanceledCallEnds(source: String, start: Int): List<Int> {
    val ends = mutableListOf<Int>()
    var cursor = start
    val executableCode = maskJavaCommentsAndLiterals(source)
    while (true) {
        val dot = skipWhitespace(executableCode, cursor)
        if (!executableCode.startsWith(".isCanceled", dot)) break
        val openParen = skipWhitespace(executableCode, dot + ".isCanceled".length)
        if (openParen >= source.length || executableCode[openParen] != '(') break
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0 || executableCode.substring(openParen + 1, closeParen).isNotBlank()) break
        ends += closeParen + 1
        cursor = closeParen + 1
    }
    return ends
}

internal fun StructuralRefactorPass.isStandaloneExpressionStart(source: String, expressionStart: Int): Boolean {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val lineStart = source.lastIndexOfAny(charArrayOf('\n', '\r'), expressionStart - 1).let { if (it < 0) 0 else it + 1 }
    if (executableCode.substring(lineStart, expressionStart).trim().isNotEmpty()) return false
    var index = expressionStart - 1
    while (index >= 0 && executableCode[index].isWhitespace()) index--
    return index < 0 || executableCode[index] == ';' || executableCode[index] == '{' || executableCode[index] == '}'
}

internal fun StructuralRefactorPass.migrateLegacyFollowOwnerGoalConstructors(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new FollowOwnerGoal(")) return source
    return rewriteExecutableJavaNew(source, "FollowOwnerGoal") { args ->
        if (args.size == 5) "new FollowOwnerGoal(${args.take(4).joinToString(", ")})" else null
    }
}

internal fun StructuralRefactorPass.migrateLegacyDyeColorFloatArrays(source: String): String {
    if (!source.contains("getTextureDiffuseColors()") && !source.contains("Sheep.getColorArray(")) {
        return source
    }

    var result = source
    val packedColorVariables = linkedSetOf<String>()
    result = Regex("""\b(?:final\s+)?float\[\]\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]+?)\.getTextureDiffuseColors\(\)\s*;""")
        .replace(result) { match ->
            val variable = match.groupValues[1]
            packedColorVariables += variable
            "int $variable = ${match.groupValues[2].trim()}.getTextureDiffuseColor();"
        }
    result = Regex("""\b(?:final\s+)?float\[\]\s+([A-Za-z_$][\w$]*)\s*=\s*Sheep\.getColorArray\(\s*([^;\r\n]+?)\s*\)\s*;""")
        .replace(result) { match ->
            val variable = match.groupValues[1]
            packedColorVariables += variable
            "int $variable = Sheep.getColor(${match.groupValues[2].trim()});"
        }
    for (variable in packedColorVariables) {
        val escaped = Regex.escape(variable)
        result = result.replace(Regex("""\b$escaped\s*\[\s*0\s*\]"""), "FastColor.ARGB32.red($variable) / 255.0F")
        result = result.replace(Regex("""\b$escaped\s*\[\s*1\s*\]"""), "FastColor.ARGB32.green($variable) / 255.0F")
        result = result.replace(Regex("""\b$escaped\s*\[\s*2\s*\]"""), "FastColor.ARGB32.blue($variable) / 255.0F")
    }
    return if (packedColorVariables.isNotEmpty()) addImportIfMissing(result, "net.minecraft.util.FastColor") else result
}

internal fun StructuralRefactorPass.migrateLegacyRendererSetupRotations(source: String): String {
    if (!source.contains("setupRotations(") || !source.contains("PoseStack")) return source
    var result = source
    val signaturePattern = Regex(
        """(?m)(protected\s+void\s+setupRotations\s*\(\s*([^,]+)\s+([A-Za-z_$][\w$]*)\s*,\s*PoseStack\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*\))"""
    )
    var cursor = 0
    while (true) {
        val match = signaturePattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val signature = match.value
        if (signature.contains("float scale")) {
            cursor = closeBrace + 1
            continue
        }
        val entityType = match.groupValues[2].trim()
        val entity = match.groupValues[3]
        val pose = match.groupValues[4]
        val age = match.groupValues[5]
        val yaw = match.groupValues[6]
        val partial = match.groupValues[7]
        val migratedSignature =
            "protected void setupRotations($entityType $entity, PoseStack $pose, float $age, float $yaw, float scale, float $partial)"
        var body = result.substring(openBrace + 1, closeBrace)
        body = Regex(
            """super\.setupRotations\(\s*${Regex.escape(entity)}\s*,\s*${Regex.escape(pose)}\s*,\s*${Regex.escape(age)}\s*,\s*${Regex.escape(yaw)}\s*,\s*${Regex.escape(partial)}\s*\)"""
        ).replace(body, "super.setupRotations($entity, $pose, $age, $yaw, scale, $partial)")
        result = result.substring(0, match.range.first) +
            migratedSignature +
            result.substring(openBrace, openBrace + 1) +
            body +
            result.substring(closeBrace)
        cursor = match.range.first + migratedSignature.length + 1 + body.length
    }
    result = rewriteExecutableJavaInvocationArguments(result, "setupRotations") { args ->
        if (args.size == 5) {
            args.take(4).map { it.trim() } + "1.0F" + args[4].trim()
        } else {
            null
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacySkinManagerTextureLookups(source: String): String {
    if (!source.contains("DefaultPlayerSkin") &&
        !source.contains("getInsecureSkinInformation(") &&
        !source.contains("getSkinTextureLocation()")) {
        return source
    }
    var result = source
    var needsPlayerSkin = false

    result = result.replace("DefaultPlayerSkin.getDefaultSkin()", "DefaultPlayerSkin.getDefaultTexture()")
    result = Regex("""DefaultPlayerSkin\.getDefaultSkin\(\s*([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result, "DefaultPlayerSkin.get($1).texture()")
    result = Regex("""DefaultPlayerSkin\.getSkinModelName\(\s*([A-Za-z_$][\w$]*)\s*\)\.equals\(\s*"slim"\s*\)""")
        .replace(result, "DefaultPlayerSkin.get($1).model().id().equals(\"slim\")")
    result = Regex("""\b([A-Za-z_$][\w$]*)\.getSkinTextureLocation\(\)""")
        .replace(result, "$1.getSkin().texture()")

    val legacyProfileTextureBlock = Regex(
        """(?s)Map\s*<\s*MinecraftProfileTexture\.Type\s*,\s*MinecraftProfileTexture\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.getSkinManager\(\)\.getInsecureSkinInformation\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;\s*""" +
            """if\s*\(\s*\1\.containsKey\(MinecraftProfileTexture\.Type\.SKIN\)\s*\)\s*\{\s*""" +
            """MinecraftProfileTexture\s+([A-Za-z_$][\w$]*)\s*=\s*\1\.get\(MinecraftProfileTexture\.Type\.SKIN\)\s*;\s*""" +
            """([A-Za-z_$][\w$]*)\s*=\s*\2\.getSkinManager\(\)\.registerTexture\(\s*\1\.get\(MinecraftProfileTexture\.Type\.SKIN\)\s*,\s*MinecraftProfileTexture\.Type\.SKIN\s*\)\s*;\s*""" +
            """if\s*\(\s*Objects\.equals\(\s*\4\.getMetadata\(\s*"model"\s*\)\s*,\s*"slim"\s*\)\s*\)\s*this\.model\s*=\s*this\.slimModel\s*;\s*""" +
            """\}\s*else\s*\{\s*UUID\s+([A-Za-z_$][\w$]*)\s*=\s*UUIDUtil\.getOrCreatePlayerUUID\(\s*\3\s*\)\s*;\s*""" +
            """\5\s*=\s*DefaultPlayerSkin\.getDefaultSkin\(\s*\6\s*\)\s*;\s*""" +
            """if\s*\(\s*DefaultPlayerSkin\.getSkinModelName\(\s*\6\s*\)\.equals\(\s*"slim"\s*\)\s*\)\s*this\.model\s*=\s*this\.slimModel\s*;\s*""" +
            """\}"""
    )
    result = legacyProfileTextureBlock.replace(result) { match ->
        needsPlayerSkin = true
        val minecraft = match.groupValues[2]
        val profile = match.groupValues[3]
        val textureVariable = match.groupValues[5]
        "PlayerSkin playerSkin = $minecraft.getSkinManager().getInsecureSkin($profile);\n" +
            "\t\t\t$textureVariable = playerSkin.texture();\n" +
            "\t\t\tif (playerSkin.model().id().equals(\"slim\")) this.model = this.slimModel;"
    }
    val migratedDefaultProfileTextureBlock = Regex(
        """(?s)Map\s*<\s*MinecraftProfileTexture\.Type\s*,\s*MinecraftProfileTexture\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.getSkinManager\(\)\.getInsecureSkinInformation\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;\s*""" +
            """if\s*\(\s*\1\.containsKey\(MinecraftProfileTexture\.Type\.SKIN\)\s*\)\s*\{\s*""" +
            """MinecraftProfileTexture\s+([A-Za-z_$][\w$]*)\s*=\s*\1\.get\(MinecraftProfileTexture\.Type\.SKIN\)\s*;\s*""" +
            """([A-Za-z_$][\w$]*)\s*=\s*\2\.getSkinManager\(\)\.registerTexture\(\s*\1\.get\(MinecraftProfileTexture\.Type\.SKIN\)\s*,\s*MinecraftProfileTexture\.Type\.SKIN\s*\)\s*;\s*""" +
            """if\s*\(\s*Objects\.equals\(\s*\4\.getMetadata\(\s*"model"\s*\)\s*,\s*"slim"\s*\)\s*\)\s*this\.model\s*=\s*this\.slimModel\s*;\s*""" +
            """\}\s*else\s*\{\s*UUID\s+([A-Za-z_$][\w$]*)\s*=\s*UUIDUtil\.getOrCreatePlayerUUID\(\s*\3\s*\)\s*;\s*""" +
            """\5\s*=\s*DefaultPlayerSkin\.get\(\s*\6\s*\)\.texture\(\)\s*;\s*""" +
            """if\s*\(\s*DefaultPlayerSkin\.get\(\s*\6\s*\)\.model\(\)\.id\(\)\.equals\(\s*"slim"\s*\)\s*\)\s*this\.model\s*=\s*this\.slimModel\s*;\s*""" +
            """\}"""
    )
    result = migratedDefaultProfileTextureBlock.replace(result) { match ->
        needsPlayerSkin = true
        val minecraft = match.groupValues[2]
        val profile = match.groupValues[3]
        val textureVariable = match.groupValues[5]
        "PlayerSkin playerSkin = $minecraft.getSkinManager().getInsecureSkin($profile);\n" +
            "\t\t\t$textureVariable = playerSkin.texture();\n" +
            "\t\t\tif (playerSkin.model().id().equals(\"slim\")) this.model = this.slimModel;"
    }

    if (needsPlayerSkin) {
        result = addImportIfMissing(result, "net.minecraft.client.resources.PlayerSkin")
    }
    val withoutProfileTexture = removeImport(result, "com.mojang.authlib.minecraft.MinecraftProfileTexture")
    if (!Regex("""\bMinecraftProfileTexture\b""").containsMatchIn(withoutProfileTexture)) {
        result = withoutProfileTexture
    }
    val withoutUuidUtil = removeImport(result, "net.minecraft.core.UUIDUtil")
    if (!Regex("""\bUUIDUtil\b""").containsMatchIn(withoutUuidUtil)) {
        result = withoutUuidUtil
    }
    val withoutMap = removeImport(result, "java.util.Map")
    if (!Regex("""\bMap\s*<""").containsMatchIn(withoutMap)) {
        result = withoutMap
    }
    val withoutObjects = removeImport(result, "java.util.Objects")
    if (!Regex("""\bObjects\b""").containsMatchIn(withoutObjects)) {
        result = withoutObjects
    }
    val withoutUuid = removeImport(result, "java.util.UUID")
    if (!Regex("""\bUUID\b""").containsMatchIn(withoutUuid)) {
        result = withoutUuid
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyMobExperienceOverrides(source: String): String {
    if (!source.contains("dropExperience()") && !source.contains("getExperienceReward()")) {
        return source
    }

    var result = source
    result = Regex(
        """(?m)^([ \t]*)@Override\s*\r?\n[ \t]*protected\s+void\s+dropExperience\s*\(\s*\)\s*\{\s*\r?\n[ \t]*\}"""
    ).replace(result) { match ->
        val indent = match.groupValues[1]
        "${indent}@Override\n${indent}public boolean shouldDropExperience() {\n${indent}\treturn false;\n${indent}}"
    }
    result = Regex("""protected\s+void\s+dropExperience\s*\(\s*\)""")
        .replace(result, "protected void dropExperience(net.minecraft.world.entity.Entity entity)")
    result = Regex("""public\s+int\s+getExperienceReward\s*\(\s*\)""")
        .replace(result, "protected int getBaseExperienceReward()")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyWolfModelLayerDefinitions(source: String): String =
    source.replace(
        "WolfModel::createBodyLayer",
        "() -> LayerDefinition.create(WolfModel.createMeshDefinition(CubeDeformation.NONE), 64, 32)"
    )

internal fun StructuralRefactorPass.migrateLossyCompoundAssignments(source: String): String {
    if (!source.contains("+=") && !source.contains("-=") && !source.contains("*=") && !source.contains("/=")) {
        return source
    }

    val floatNames = collectPrimitiveNames(source, "float")
    val doubleNames = collectPrimitiveNames(source, "double")
    val intNames = collectPrimitiveNames(source, "int")
    val floatLikeFields = setOf("xRot", "yRot", "zRot", "quadSize", "rCol", "gCol", "bCol", "alpha")
    val lhsPattern = """[A-Za-z_$][\w$]*(?:\[[^\]\r\n]+\])?(?:\.[A-Za-z_$][\w$]*(?:\[[^\]\r\n]+\])?)*"""
    return Regex("""(?m)^([ \t]*)($lhsPattern)\s*([+\-*/])=\s*([^;\r\n]+);""")
        .replace(source) { match ->
            val lhs = match.groupValues[2].trim()
            val operator = match.groupValues[3]
            val rhs = match.groupValues[4].trim()
            val lastName = lhs.substringAfterLast('.').substringBefore('[')
            val targetType = when {
                lastName in floatNames || lastName in floatLikeFields -> "float"
                lastName in intNames -> "int"
                else -> null
            } ?: return@replace match.value
            if (!compoundAssignmentNeedsExplicitNarrowing(targetType, rhs, floatNames, doubleNames)) {
                return@replace match.value
            }
            val narrowedRhs = when (targetType) {
                "float" -> "($targetType) ($rhs)"
                else -> "($targetType) ($rhs)"
            }
            "${match.groupValues[1]}$lhs $operator= $narrowedRhs;"
        }
}

internal fun StructuralRefactorPass.collectPrimitiveNames(source: String, primitive: String): Set<String> =
    Regex("""\b${Regex.escape(primitive)}\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()

internal fun StructuralRefactorPass.compoundAssignmentNeedsExplicitNarrowing(
    targetType: String,
    rhs: String,
    floatNames: Set<String>,
    doubleNames: Set<String>
): Boolean {
    if (rhs.startsWith("($targetType)")) return false
    val tokens = Regex("""[A-Za-z_$][\w$]*""").findAll(rhs).map { it.value }.toSet()
    return when (targetType) {
        "float" ->
            (rhs.contains("Math.PI") && !rhs.contains("(float) Math.PI") && !rhs.contains("(float)Math.PI")) ||
                Regex("""\d+\.\d+(?![0-9Ff])""").containsMatchIn(rhs) ||
                tokens.any { it in doubleNames }
        "int" ->
            Regex("""\d+\.\d+[Ff]?(?![0-9A-Za-z_$])""").containsMatchIn(rhs) ||
                tokens.any { it in floatNames || it in doubleNames }
        else -> false
    }
}

internal fun StructuralRefactorPass.migrateLegacyEmptyUnsidedItemHandlerOverrides(source: String): String {
    if (!source.contains("createUnSidedHandler(")) return source
    var result = Regex(
        """(?s)\n[ \t]*@Override\s*\r?\n[ \t]*(?:protected|public)\s+IItemHandler\s+createUnSidedHandler\s*\(\s*\)\s*\{\s*(?:return\s+(?:EmptyItemHandler\.INSTANCE|new\s+EmptyHandler\s*\(\s*\))\s*;)\s*\}\s*"""
    ).replace(source, "\n")
    if (result == source) return source
    removeImport(result, "net.neoforged.neoforge.items.IItemHandler").let { withoutImport ->
        if (!Regex("""\bIItemHandler\b""").containsMatchIn(withoutImport)) result = withoutImport
    }
    removeImport(result, "net.neoforged.neoforge.items.wrapper.EmptyItemHandler").let { withoutImport ->
        if (!withoutImport.contains("EmptyItemHandler")) result = withoutImport
    }
    removeImport(result, "net.neoforged.neoforge.items.wrapper.EmptyHandler").let { withoutImport ->
        if (!withoutImport.contains("EmptyHandler")) result = withoutImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyHangingEntityBoundingBoxes(source: String): String {
    if (!source.contains("extends HangingEntity") ||
        Regex("""\b(?:public|protected|private)\s+AABB\s+calculateBoundingBox\s*\(""").containsMatchIn(source)) {
        return source
    }
    if (!source.contains("getWidth()") || !source.contains("getHeight()")) return source
    val classOpen = source.indexOf('{')
    val classClose = source.lastIndexOf('}')
    if (classOpen < 0 || classClose <= classOpen) return source

    var result = source
    result = Regex("""(?m)^([ \t]*)@Override\s*\r?\n([ \t]*public\s+int\s+get(?:Width|Height)\s*\()""")
        .replace(result, "$1$2")
    // A source-owned box calculation moves to the new hook; do not hide it behind the vanilla fallback.
    if (Regex("""\bvoid\s+recalculateBoundingBox\s*\(\s*\)""").containsMatchIn(maskJavaCommentsAndLiterals(result))) return result
    val insertion = """

    @Override
    protected AABB calculateBoundingBox(BlockPos pos, Direction direction) {
        Vec3 base = Vec3.atCenterOf(pos).relative(direction, -0.46875D);
        double widthOffset = this.offsetForHangingEntitySize(this.getWidth());
        double heightOffset = this.offsetForHangingEntitySize(this.getHeight());
        Vec3 center = base.relative(direction.getCounterClockWise(), widthOffset).relative(Direction.UP, heightOffset);
        Direction.Axis axis = direction.getAxis();
        double scale = 1.0D / 16.0D;
        double xSize = axis == Direction.Axis.X ? 0.0625D : this.getWidth() * scale;
        double ySize = this.getHeight() * scale;
        double zSize = axis == Direction.Axis.Z ? 0.0625D : this.getWidth() * scale;
        return AABB.ofSize(center, xSize, ySize, zSize);
    }

    private double offsetForHangingEntitySize(int size) {
        return size % 32 == 0 ? 0.5D : 0.0D;
    }
""".trimEnd()
    val insertAt = result.lastIndexOf('}')
    if (insertAt < 0) return source
    result = result.substring(0, insertAt) + insertion + "\n" + result.substring(insertAt)
    result = addImportIfMissing(result, "net.minecraft.world.phys.AABB")
    result = addImportIfMissing(result, "net.minecraft.world.phys.Vec3")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyToastTextureRendering(source: String): String {
    if (!source.contains("implements Toast") || !source.contains(".blit(TEXTURE")) return source
    var result = source
    result = Regex(
        """\b([A-Za-z_$][\w$]*)\.blit\(TEXTURE,\s*0,\s*0,\s*0,\s*(?:0|32),\s*this\.width\(\),\s*this\.height\(\)\);"""
    ).replace(result) { match ->
        "${match.groupValues[1]}.blitSprite(BACKGROUND_SPRITE, 0, 0, this.width(), this.height());"
    }
    if (result == source) return source
    if (!result.contains("BACKGROUND_SPRITE")) return result
    if (!Regex("""\bBACKGROUND_SPRITE\s*=""").containsMatchIn(result)) {
        val anchor = Regex("""(?m)^([ \t]*private\s+static\s+final\s+Component\s+[A-Z0-9_]+\s*=.*;\s*)$""")
            .find(result)
        val insertion = "\n\tprivate static final ResourceLocation BACKGROUND_SPRITE = ResourceLocation.withDefaultNamespace(\"toast/advancement\");"
        result = if (anchor != null) {
            result.substring(0, anchor.range.last + 1) + insertion + result.substring(anchor.range.last + 1)
        } else {
            val openBrace = result.indexOf('{')
            if (openBrace >= 0) result.substring(0, openBrace + 1) + insertion + "\n" + result.substring(openBrace + 1) else result
        }
    }
    return addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
}

internal fun StructuralRefactorPass.migrateEntityDimensionsRecordAccessors(source: String): String {
    var result = migrateEntityDimensionsCallRecordAccessors(source)
    result = replaceExecutableRegex(
        result,
        Regex("""(\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*\.dimensions)\.(width|height|fixed)\b(?!\s*\()""")
    ) { match ->
        "${match.groupValues[1]}.${match.groupValues[2]}()"
    }

    val executableResult = maskJavaCommentsAndLiterals(result)
    val dimensionsVariables = Regex("""\bEntityDimensions\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableResult)
        .map { it.groupValues[1] }
        .toSet()
    for (variable in dimensionsVariables) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(variable)}\.(width|height|fixed)\b(?!\s*\()""")
        ) { match -> "$variable.${match.groupValues[1]}()" }
    }

    return result
}

internal fun StructuralRefactorPass.migrateEntityDimensionsCallRecordAccessors(source: String): String {
    var result = source
    var cursor = 0
    val token = ".getDimensions("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) return result
        val openParen = tokenIndex + token.length - 1
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        var accessorStart = closeParen + 1
        while (accessorStart < executableCode.length && executableCode[accessorStart].isWhitespace()) accessorStart++
        if (accessorStart >= executableCode.length || executableCode[accessorStart] != '.') {
            cursor = closeParen + 1
            continue
        }
        val fieldStart = accessorStart + 1
        val field = listOf("width", "height", "fixed").firstOrNull { fieldName ->
            executableCode.startsWith(fieldName, fieldStart) &&
                executableCode.getOrNull(fieldStart + fieldName.length)?.let { !it.isLetterOrDigit() && it != '_' && it != '$' } != false
        }
        if (field == null || executableCode.drop(fieldStart + field.length).takeWhile { it.isWhitespace() }.startsWith("(")) {
            cursor = closeParen + 1
            continue
        }
        val replacement = ".$field()"
        result = result.substring(0, accessorStart) +
            replacement +
            result.substring(fieldStart + field.length)
        cursor = accessorStart + replacement.length
    }
}

internal fun StructuralRefactorPass.migrateAdvancementRequirementsStrategySource(source: String): String {
    if (!source.contains("AdvancementRequirements.Strategy") ||
        !source.contains("createRequirements(")) {
        return source
    }

    var result = Regex(
        """public\s+String\[\]\[\]\s+createRequirements(\s*\(\s*Collection\s*<\s*String\s*>\s+[A-Za-z_$][\w$]*\s*\))"""
    ).replace(source, "public AdvancementRequirements create$1")

    if (result.contains("String[][] requirements")) {
        result = Regex("""return\s+requirements\s*;""")
            .replace(
                result,
                "return new AdvancementRequirements(java.util.Arrays.stream(requirements).map(group -> java.util.List.of(group)).toList());"
            )
    } else if (result.contains("List<List<String>> requirements")) {
        result = Regex("""return\s+requirements\s*;""")
            .replace(result, "return new AdvancementRequirements(requirements);")
    }
    result = Regex("""(?s)return\s+(new\s+String\[\]\[\]\s*\{.*?})\s*;""")
        .replace(result) { match ->
            "return new AdvancementRequirements(java.util.Arrays.stream(${match.groupValues[1]}).map(group -> java.util.List.of(group)).toList());"
        }

    return result
}

internal fun StructuralRefactorPass.migrateLegacyAdvancementHolderApis(source: String): String {
    if (!source.contains("Advancement") && !source.contains(".getAdvancement(") && !source.contains("MissingAdvancementToast")) {
        return source
    }

    var result = source
    result = replaceExecutableRegex(
        result,
        Regex("""(?m)^[ \t]*import\s+net\.minecraft\.advancements\.AdvancementList;\s*\r?\n""")
    ) { "import net.minecraft.server.ServerAdvancementManager;\n" }
    result = replaceExecutableRegex(result, Regex("""\bAdvancementList\b""")) {
        "ServerAdvancementManager"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(public|private|protected)\s+Advancement\s+([A-Za-z_$][\w$]*)\s*\(([^)]*\bServerAdvancementManager\b[^)]*)\)""")
    ) { match ->
        "${match.groupValues[1]} AdvancementHolder ${match.groupValues[2]}(${match.groupValues[3]})"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\.getAdvancements\s*\(\s*\)\s*\.getAdvancement\s*\(""")
    ) { ".getAdvancements().get(" }
    result = replaceExecutableRegex(
        result,
        Regex("""\bmanager\.getAdvancements\s*\(\s*\)\s*\.get\s*\(""")
    ) { "manager.get(" }
    val executableManagerSource = maskJavaCommentsAndLiterals(result)
    val advancementManagerVariables = Regex("""\bServerAdvancementManager\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableManagerSource)
        .map { it.groupValues[1] }
        .toSet()
    for (managerVariable in advancementManagerVariables) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(managerVariable)}\.getAdvancement\s*\(""")
        ) { "$managerVariable.get(" }
    }

    result = replaceExecutableRegex(result, Regex("""\bPredicate\s*<\s*Advancement\s*>""")) { "Predicate<AdvancementHolder>" }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(matches|retrieveOverride|checkRoot)\s*\(\s*Advancement\s+""")
    ) { match ->
        "${match.groupValues[1]}(AdvancementHolder "
    }
    if (maskJavaCommentsAndLiterals(result).contains("AdvancementToast.class")) {
        result = replaceExecutableRegex(result, Regex("""\bprivate\s+Advancement\s+advancement\s*;""")) {
            "private AdvancementHolder advancement;"
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(public|private|protected)\s+static\s+Advancement\s+getAdvancement\s*\(""")
    ) { match ->
        "${match.groupValues[1]} static AdvancementHolder getAdvancement("
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(public|private|protected)\s+static\s+boolean\s+doesPlayerHaveRequiredAdvancement\(\s*Player\s+([A-Za-z_$][\w$]*)\s*,\s*Advancement\s+([A-Za-z_$][\w$]*)\s*\)""")
    ) { match ->
        "${match.groupValues[1]} static boolean doesPlayerHaveRequiredAdvancement(Player ${match.groupValues[2]}, AdvancementHolder ${match.groupValues[3]})"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\bAdvancement\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]*(?:getAdvancement|getAdvancements\(\)\.get|manager\.get|PlayerHelper\.getAdvancement)\([^;\r\n]*;)""")
    ) { match ->
        "AdvancementHolder ${match.groupValues[1]} = ${match.groupValues[2]}"
    }

    val executableHolderSource = maskJavaCommentsAndLiterals(result)
    val holderVariables = Regex("""\bAdvancementHolder\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableHolderSource)
        .map { it.groupValues[1] }
        .toSet()
    val holderLambdaVariables = if (
        executableHolderSource.contains("Predicate<AdvancementHolder>") ||
        executableHolderSource.contains("new AdvancementSoundOverride")
    ) {
        Regex("""\b([A-Za-z_$][\w$]*)\s*->[^;\r\n]*\b\1\.getId\(\)""")
            .findAll(executableHolderSource)
            .map { it.groupValues[1] }
            .toSet()
    } else {
        emptySet()
    }
    for (variable in holderVariables) {
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(variable)}\.getDisplay\s*\(\s*\)""")) {
            "$variable.value().display().orElse(null)"
        }
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(variable)}\.getId\s*\(\s*\)""")) {
            "$variable.id()"
        }
    }
    for (variable in holderLambdaVariables) {
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(variable)}\.getId\s*\(\s*\)""")) {
            "$variable.id()"
        }
    }
    result = migrateLegacyAdvancementParentLoop(result)
    result = migrateLegacyDeferredRegistryEntryStream(result)
    result = migrateClientAdvancementsListenerSource(result)

    result = replaceExecutableRegex(
        result,
        Regex("""([A-Za-z_$][\w$]*)\.FALLBACK\s*:\s*new\s+([A-Za-z_$][\w$]*Packet)\(""")
    ) { match ->
        val fallbackType = match.groupValues[1]
        val packetType = match.groupValues[2]
        "new $packetType($fallbackType.FALLBACK.title(), $fallbackType.FALLBACK.icon()) : new $packetType("
    }

    if (result != source && maskJavaCommentsAndLiterals(result).contains("AdvancementHolder")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.advancements.AdvancementHolder")
        val executableResult = maskJavaCommentsAndLiterals(result)
        if (executableResult.contains("Minecraft.getInstance().player")) {
            result = addExecutableImportIfMissing(result, "net.minecraft.client.Minecraft")
            result = addExecutableImportIfMissing(result, "net.minecraft.client.player.LocalPlayer")
        }
        if (executableResult.contains("DeferredHolder::value")) {
            result = addExecutableImportIfMissing(result, "net.neoforged.neoforge.registries.DeferredHolder")
        }
        if (executableResult.contains("ServerAdvancementManager")) {
            result = addExecutableImportIfMissing(result, "net.minecraft.server.ServerAdvancementManager")
        }
        val withoutAdvancementImport = removeExecutableImport(result, "net.minecraft.advancements.Advancement")
        if (!Regex("""\bAdvancement\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutAdvancementImport))) {
            result = withoutAdvancementImport
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateClientAdvancementsListenerSource(source: String): String {
    if (!source.contains("ClientAdvancements.Listener") && !source.contains("onUpdateAdvancementProgress")) {
        return source
    }
    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    val nodeCallbacks = "onAddAdvancementRoot|onRemoveAdvancementRoot|onAddAdvancementTask|onRemoveAdvancementTask"
    result = replaceExecutableRegex(
        result,
        Regex("""\b(public|protected|private)\s+void\s+($nodeCallbacks)\s*\(\s*Advancement\s+($id)\s*\)""")
    ) { match ->
        "${match.groupValues[1]} void ${match.groupValues[2]}(AdvancementNode ${match.groupValues[3]})"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(public|protected|private)\s+void\s+onUpdateAdvancementProgress\s*\(\s*Advancement\s+($id)\s*,\s*AdvancementProgress\s+($id)\s*\)""")
    ) { match ->
        "${match.groupValues[1]} void onUpdateAdvancementProgress(AdvancementNode ${match.groupValues[2]}, AdvancementProgress ${match.groupValues[3]})"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(public|protected|private)\s+void\s+onSelectedTabChanged\s*\(\s*((?:@[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?\s+)*)Advancement\s+($id)\s*\)""")
    ) { match ->
        "${match.groupValues[1]} void onSelectedTabChanged(${match.groupValues[2]}AdvancementHolder ${match.groupValues[3]})"
    }

    Regex("""\bAdvancementNode\s+($id)\b""").findAll(maskJavaCommentsAndLiterals(result)).forEach { match ->
        val variable = match.groupValues[1]
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(variable)}\.getId\(\)""")) {
            "$variable.holder().id()"
        }
    }
    Regex("""\bAdvancementHolder\s+($id)\b""").findAll(maskJavaCommentsAndLiterals(result)).forEach { match ->
        val variable = match.groupValues[1]
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(variable)}\.getId\(\)""")) {
            "$variable.id()"
        }
    }
    if (result.contains("AdvancementNode")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.advancements.AdvancementNode")
    }
    if (result.contains("AdvancementHolder")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.advancements.AdvancementHolder")
    }
    return result
}

internal fun StructuralRefactorPass.migrateHolderReferenceValueApis(source: String): String {
    if (!source.contains("Holder") && !source.contains(".getHolder(")) return source
    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    result = replaceExecutableRegex(
        result,
        Regex("""\b((?:java\.util\.)?Optional)\s*<\s*Holder\s*<\s*([^>\r\n;]+?)\s*>\s*>\s+($id)\s*=\s*([^;\r\n]*\.getHolder\s*\([^;\r\n]+?\))\s*;""")
    ) { match ->
        "${match.groupValues[1]}<? extends Holder<${match.groupValues[2].trim()}>> ${match.groupValues[3]} = ${match.groupValues[4].trim()};"
    }

    val holderVariables = Regex("""\b(?:Holder|Holder\.Reference)\s*<[^>\r\n;]+>\s+($id)\b""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
    for (variable in holderVariables) {
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(variable)}\.get\(\)""")) {
            "$variable.value()"
        }
    }

    val optionalHolderVariables = Regex(
        """\b(?:java\.util\.)?Optional\s*<\s*(?:\?\s+extends\s+)?(?:Holder|Holder\.Reference)\s*<[^>\r\n;]+>\s*>\s+($id)\b"""
    ).findAll(maskJavaCommentsAndLiterals(result)).map { it.groupValues[1] }.toSet()
    for (optional in optionalHolderVariables) {
        result = replaceHolderGetInsideIfPresent(result, optional)
    }
    result = replaceBuiltInRegistryHolderGetInsideIfPresent(result)
    return result
}

internal fun StructuralRefactorPass.replaceHolderGetInsideIfPresent(source: String, optionalName: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    return Regex(
        """(?s)\b${Regex.escape(optionalName)}\.ifPresent\s*\(\s*($id)\s*->\s*(\{.*?}\s*|[^;{}]*?)\)\s*;"""
    ).replace(source) { match ->
        val holder = match.groupValues[1]
        val body = match.groupValues[2].replace(Regex("""\b${Regex.escape(holder)}\.get\(\)"""), "$holder.value()")
        "${optionalName}.ifPresent($holder -> $body);"
    }
}
