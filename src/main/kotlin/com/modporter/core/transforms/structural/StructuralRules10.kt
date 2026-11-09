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

// Structural migration rules, part 10 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateDropExperienceBlockConstructorOrder, migrateStairBlockStateSupplierConstructors, migrateFlowerBlockMobEffectHolderConstructors, migrateMmlibRecipeIdTracking, migrateDeferredHolderPresenceChecks, migrateMenuScreensRegistration, migrateSourceBackedAccessorCalls, migrateUseItemOnInteractionResultReturns, extractClientOnlyEventMethods, migrateCommonBusModEventListeners, migrateStaticModBusSubscribers, removeEmptySubscriberClasses.

internal fun StructuralRefactorPass.collectRecipeBuilderFactoryInterfaceHints(
    javaFiles: List<Path>,
    recipeSerializerFactoryHints: RecipeSerializerFactoryHints
): Map<String, Set<RecipeFactoryInterfaceHint>> {
    if (recipeSerializerFactoryHints.fieldToFactoryInterface.isEmpty()) return emptyMap()
    val hintsByBuilder = linkedMapOf<String, MutableSet<RecipeFactoryInterfaceHint>>()
    val callPattern = Regex("""\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.generic\s*\(""")
    for (javaFile in javaFiles) {
        val source = maskJavaCommentsAndLiterals(javaFile.readText())
        if (!source.contains(".generic(")) continue
        var cursor = 0
        while (true) {
            val match = callPattern.find(source, cursor) ?: break
            val openParen = source.indexOf('(', match.range.last - 1)
            val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
            if (openParen < 0 || closeParen < 0) {
                cursor = match.range.last + 1
                continue
            }
            val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val serializerExpression = args.lastOrNull()?.let(::normalizeRecipeSerializerExpression)
            val hint = serializerExpression
                ?.let { recipeSerializerFactoryHints.fieldToFactoryInterface[it] }
            if (hint != null) {
                val builderClass = match.groupValues[1].substringAfterLast('.')
                hintsByBuilder.getOrPut(builderClass) { linkedSetOf() }.add(hint)
            }
            cursor = closeParen + 1
        }
    }
    return hintsByBuilder.mapValues { it.value.toSet() }
}

internal fun StructuralRefactorPass.addFactoryParameterToBuilderConstructor(
    source: String,
    className: String,
    serializerParam: String,
    factoryType: String
): String =
    Regex("""(?s)(public\s+${Regex.escape(className)}\s*\((?:[^()]|\([^()]*\))*RecipeSerializer\s*<\s*\?\s+extends\s+AbstractCookingRecipe\s*>\s+${Regex.escape(serializerParam)})(\s*\))""")
        .replace(source) { match ->
            if (match.groupValues[1].contains("${serializerParam}Factory")) {
                match.value
            } else {
                "${match.groupValues[1]}, $factoryType ${serializerParam}Factory${match.groupValues[2]}"
            }
        }

internal fun StructuralRefactorPass.addFactoryParameterToStaticBuilderMethods(
    source: String,
    className: String,
    serializerParam: String,
    factoryType: String
): String {
    var result = source
    val methodPattern = Regex(
        """(?s)(public\s+static\s+${Regex.escape(className)}\s+[A-Za-z_$][\w$]*\s*\((?:[^()]|\([^()]*\))*RecipeSerializer\s*<\s*\?\s+extends\s+AbstractCookingRecipe\s*>\s+${Regex.escape(serializerParam)})(\s*\))"""
    )
    result = methodPattern.replace(result) { match ->
        if (match.groupValues[1].contains("${serializerParam}Factory")) {
            match.value
        } else {
            "${match.groupValues[1]}, $factoryType ${serializerParam}Factory${match.groupValues[2]}"
        }
    }
    return addFactoryArgumentToConstructorCalls(result, className, serializerParam)
}

internal fun StructuralRefactorPass.addFactoryArgumentToConstructorCalls(
    source: String,
    className: String,
    serializerParam: String
): String {
    var result = source
    var cursor = 0
    val token = "new $className("
    while (true) {
        val tokenIndex = result.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + "new $className".length
        val closeParen = findMatchingParen(result, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val serializerIndex = args.indexOfFirst { it.trim() == serializerParam }
        if (serializerIndex < 0 || args.any { it.trim() == "${serializerParam}Factory" }) {
            cursor = closeParen + 1
            continue
        }
        val migrated = args.toMutableList()
        migrated.add(serializerIndex + 1, "${serializerParam}Factory")
        val replacement = "new $className(${migrated.joinToString(", ") { it.trim() }})"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.normalizeRecipeSerializerExpression(expression: String): String =
    expression.trim()
        .removeSuffix(".get()")

internal fun StructuralRefactorPass.migrateConstructableRecipeOutputResultAdapters(
    source: String,
    recipeConstructors: Map<String, List<RecipeConstructorSignature>>
): String {
    if (!source.contains("RecipeOutput") || !source.contains(".accept(new ")) return source
    val resultClassText = legacyRecipeOutputResultClassText(source) ?: return source
    val resultConstructor = Regex("""public\s+Result\s*\(([^()]*)\)""")
        .find(resultClassText)
        ?: return source
    val resultParams = parseJavaParameters(resultConstructor.groupValues[1])
    if (resultParams.isEmpty()) return source
    val recipeCandidates = legacyResultRecipeClassCandidates(source, resultClassText, recipeConstructors)
    if (recipeCandidates.isEmpty()) return source

    var result = source
    var changed = false
    var cursor = 0
    val acceptPattern = Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.accept\(\s*new\s+((?:[A-Za-z_$][\w$]*\.)*Result)\s*\(""")
    while (true) {
        val match = acceptPattern.find(result, cursor) ?: break
        val openParen = result.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(result, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val acceptClose = nextNonWhitespaceIndex(result, closeParen + 1)
        if (acceptClose < 0 || result[acceptClose] != ')') {
            cursor = closeParen + 1
            continue
        }
        val semicolon = nextNonWhitespaceIndex(result, acceptClose + 1)
        if (semicolon < 0 || result[semicolon] != ';') {
            cursor = closeParen + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen)).map { it.trim() }
        if (args.size != resultParams.size) {
            cursor = closeParen + 1
            continue
        }
        val argsByName = resultParams.mapIndexed { index, param -> param.name to args[index] }.toMap()
        val migration = recipeCandidates.asSequence()
            .flatMap { candidate -> recipeConstructors[candidate].orEmpty().asSequence() }
            .mapNotNull { constructor -> recipeOutputResultRecipeConstruction(constructor, resultParams, argsByName) }
            .firstOrNull()
        if (migration == null) {
            cursor = closeParen + 1
            continue
        }
        val output = match.groupValues[2]
        val replacement = "${match.groupValues[1]}$output.accept(${migration.idExpression}, ${migration.recipeExpression}, ${migration.advancementExpression});"
        result = result.substring(0, match.range.first) + replacement + result.substring(semicolon + 1)
        cursor = match.range.first + replacement.length
        changed = true
    }
    if (!changed) return source
    result = removeLegacyResultClass(result)
    val withoutJsonObjectImport = removeImport(result, "com.google.gson.JsonObject")
    if (!Regex("""\bJsonObject\b""").containsMatchIn(withoutJsonObjectImport)) {
        result = withoutJsonObjectImport
    }
    val withoutJsonOpsImport = removeImport(result, "com.mojang.serialization.JsonOps")
    if (!Regex("""\bJsonOps\b""").containsMatchIn(withoutJsonOpsImport)) {
        result = withoutJsonOpsImport
    }
    if (result.contains("Optional.ofNullable(")) {
        result = addImportIfMissing(result, "java.util.Optional")
    }
    return result
}

internal data class RecipeOutputResultConstruction(
    val idExpression: String,
    val recipeExpression: String,
    val advancementExpression: String
)

internal fun StructuralRefactorPass.recipeOutputResultRecipeConstruction(
    constructor: RecipeConstructorSignature,
    resultParams: List<JavaParameterInfo>,
    argsByName: Map<String, String>
): RecipeOutputResultConstruction? {
    val resultParamTypes = resultParams.associate { it.name to it.type }
    val constructorArgs = constructor.params.map { param ->
        val expression = argsByName[param.name]
            ?: constructor.defaultsByParam[param.name]
            ?: return null
        val sourceType = resultParamTypes[param.name].orEmpty()
        if (param.name in argsByName) {
            migrateRecipeOutputResultArgument(expression, sourceType, param.type)
        } else {
            expression
        }
    }
    val idExpression = argsByName["id"] ?: argsByName["recipeId"] ?: return null
    val advancement = argsByName["advancement"]
    val advancementId = argsByName["advancementId"]
    val advancementExpression = if (advancement != null && advancementId != null) {
        "$advancement.build($advancementId)"
    } else {
        "null"
    }
    return RecipeOutputResultConstruction(
        idExpression = idExpression,
        recipeExpression = "new ${constructor.className}(${constructorArgs.joinToString(", ")})",
        advancementExpression = advancementExpression
    )
}

internal fun StructuralRefactorPass.migrateRecipeOutputResultArgument(expression: String, sourceType: String, targetType: String): String {
    val targetSimple = targetType.substringBefore('<').substringAfterLast('.').trim()
    val sourceSimple = sourceType.substringBefore('<').substringAfterLast('.').trim()
    return if (targetSimple == "Optional" && sourceSimple != "Optional") {
        "Optional.ofNullable($expression)"
    } else if (targetSimple == "ItemStack" && sourceSimple == "Item") {
        "new ItemStack($expression)"
    } else {
        expression
    }
}

internal fun StructuralRefactorPass.legacyResultRecipeClassCandidates(
    source: String,
    resultClassText: String,
    recipeConstructors: Map<String, List<RecipeConstructorSignature>>
): List<String> {
    val candidates = linkedSetOf<String>()
    Regex("""RecipeSerializer\s*<\s*([A-Za-z_$][\w$]*)\s*>""")
        .findAll(resultClassText + "\n" + source)
        .forEach { candidates += it.groupValues[1] }
    Regex("""<([^;\r\n{}]*Recipe[^;\r\n{}]*)>""")
        .findAll(source)
        .forEach { match ->
            splitTopLevelJavaArgs(match.groupValues[1])
                .map { it.trim().substringAfterLast('.') }
                .filter { it.endsWith("Recipe") && recipeConstructors.containsKey(it) }
                .forEach { candidates += it }
        }
    Regex("""import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.([A-Za-z_$][\w$]*Recipe));""")
        .findAll(source)
        .forEach { match -> candidates += match.groupValues[2] }
    return candidates.filter { recipeConstructors.containsKey(it) }
}

internal fun StructuralRefactorPass.removeLegacyResultClass(source: String): String {
    val match = Regex("""(?m)^[ \t]*(?:public|protected|private)?\s*(?:static\s+)?class\s+Result\b[^{]*\{""")
        .find(source)
        ?: return source
    val openBrace = source.indexOf('{', match.range.last - 1)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    if (openBrace < 0 || closeBrace < 0) return source
    var end = closeBrace + 1
    if (end < source.length && source[end] == '\r') end++
    if (end < source.length && source[end] == '\n') end++
    return source.removeRange(match.range.first, end)
}

internal fun StructuralRefactorPass.removeUnusedLegacyRecipeOutputResultClasses(javaFiles: List<Path>, dryRun: Boolean): List<Change> {
    val texts = javaFiles.associateWith { it.readText() }
    val allText = texts.values.joinToString("\n")
    val changes = mutableListOf<Change>()
    texts.forEach { (file, source) ->
        if (!source.contains("class Result implements RecipeOutput")) return@forEach
        val owner = javaTopLevelTypeName(source) ?: return@forEach
        val withoutOwnResult = removeLegacyResultClass(source)
        val externalText = allText.replace(source, withoutOwnResult)
        val referenced = Regex("""\b(?:new\s+|extends\s+)?${Regex.escape(owner)}\.Result\b""").containsMatchIn(externalText) ||
            Regex("""\bnew\s+Result\s*\(""").containsMatchIn(withoutOwnResult) ||
            Regex("""\bextends\s+Result\b""").containsMatchIn(withoutOwnResult)
        if (referenced || withoutOwnResult == source) return@forEach
        var migrated = withoutOwnResult
        if (!Regex("""\bJsonObject\b""").containsMatchIn(migrated)) {
            migrated = removeImport(migrated, "com.google.gson.JsonObject")
        }
        if (!Regex("""\bJsonOps\b""").containsMatchIn(migrated)) {
            migrated = removeImport(migrated, "com.mojang.serialization.JsonOps")
        }
        changes.add(Change(
            file = file,
            line = 0,
            description = "Remove unreferenced legacy RecipeOutput Result adapter",
            before = "class Result implements RecipeOutput",
            after = "direct RecipeOutput.accept recipe instances",
            confidence = Confidence.HIGH,
            ruleId = "struct-legacy-recipe-output-result-cleanup"
        ))
        if (!dryRun) {
            file.writeText(migrated)
        }
    }
    return changes
}

internal fun StructuralRefactorPass.legacyRecipeOutputResultClassText(source: String): String? {
    val match = Regex("""(?m)^[ \t]*(?:public|protected|private)?\s*(?:static\s+)?class\s+Result\b[^{]*\{""")
        .find(source)
        ?: return null
    val openBrace = source.indexOf('{', match.range.last - 1)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    return if (openBrace >= 0 && closeBrace >= 0) source.substring(match.range.first, closeBrace + 1) else null
}

internal fun StructuralRefactorPass.migrateDropExperienceBlockConstructorOrder(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val migratedConstructors = linkedSetOf("DropExperienceBlock")

    for (file in javaFiles) {
        val source = file.readText()
        if (!source.contains("extends DropExperienceBlock") || !source.contains("super(")) continue
        val className = javaTopLevelTypeName(source) ?: continue
        val migrated = migrateDropExperienceSubclassConstructorSource(source, className)
        if (migrated != source) {
            migratedConstructors += className
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate DropExperienceBlock subclass constructor to xp-provider-first order",
                before = "Subclass(Properties properties, IntProvider xpRange); super(properties, xpRange)",
                after = "Subclass(IntProvider xpRange, Properties properties); super(xpRange, properties)",
                confidence = Confidence.HIGH,
                ruleId = "struct-drop-experience-constructor-order"
            ))
            if (!dryRun) {
                file.writeText(migrated)
            }
        }
    }

    for (file in javaFiles) {
        val source = file.readText()
        val migrated = migrateDropExperienceConstructorCallSitesSource(source, migratedConstructors)
        if (migrated != source) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate DropExperienceBlock constructor calls to xp-provider-first order",
                before = "new DropExperienceBlock(Properties, IntProvider)",
                after = "new DropExperienceBlock(IntProvider, Properties)",
                confidence = Confidence.HIGH,
                ruleId = "struct-drop-experience-constructor-order"
            ))
            if (!dryRun) {
                file.writeText(migrated)
            }
        }
    }

    return changes
}

internal fun StructuralRefactorPass.migrateDropExperienceSubclassConstructorSource(source: String, className: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    val constructorPattern = Regex(
        """public\s+${Regex.escape(className)}\s*\(\s*((?:BlockBehaviour\.)?Properties)\s+($id)\s*,\s*((?:UniformInt|IntProvider))\s+($id)\s*\)"""
    )
    val executableCode = maskJavaCommentsAndLiterals(source)
    val match = constructorPattern.find(executableCode) ?: return source
    val propertiesType = match.groupValues[1]
    val propertiesName = match.groupValues[2]
    val xpType = match.groupValues[3]
    val xpName = match.groupValues[4]
    var result = source.substring(0, match.range.first) +
        "public $className($xpType $xpName, $propertiesType $propertiesName)" +
        source.substring(match.range.last + 1)
    val superPattern = Regex("""super\(\s*${Regex.escape(propertiesName)}\s*,\s*${Regex.escape(xpName)}\s*\)\s*;""")
    result = replaceExecutableJavaRegex(result, superPattern) { "super($xpName, $propertiesName);" }
    return result
}

internal fun StructuralRefactorPass.migrateDropExperienceConstructorCallSitesSource(source: String, constructorClasses: Set<String>): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new ")) return source
    val intProviderVariables = Regex("""\b(?:UniformInt|IntProvider)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    var result = source
    for (className in constructorClasses) {
        result = rewriteExecutableJavaNew(result, className) { args ->
            if (args.size == 1 && className == "DropExperienceBlock") {
                val properties = args[0].trim()
                if (looksLikeBlockPropertiesExpression(properties)) {
                    return@rewriteExecutableJavaNew "new DropExperienceBlock(net.minecraft.util.valueproviders.ConstantInt.of(0), $properties)"
                }
                return@rewriteExecutableJavaNew null
            }
            if (args.size != 2) return@rewriteExecutableJavaNew null
            val properties = args[0].trim()
            val xpProvider = args[1].trim()
            if (!looksLikeBlockPropertiesExpression(properties) || !looksLikeIntProviderExpression(xpProvider, intProviderVariables)) {
                return@rewriteExecutableJavaNew null
            }
            "new $className($xpProvider, $properties)"
        }
    }
    return result
}

internal fun StructuralRefactorPass.looksLikeIntProviderExpression(expression: String, intProviderVariables: Set<String>): Boolean {
    val trimmed = expression.trim()
    return trimmed in intProviderVariables ||
        trimmed.contains("UniformInt.") ||
        trimmed.contains("ConstantInt.") ||
        trimmed.contains("ClampedInt.") ||
        trimmed.contains("IntProvider.")
}

internal fun StructuralRefactorPass.migrateStairBlockStateSupplierConstructors(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val migratedConstructors = linkedSetOf<String>()

    for (file in javaFiles) {
        val source = file.readText()
        if (!source.contains("extends StairBlock") || !source.contains("Supplier<BlockState>") || !source.contains("super(")) continue
        val className = javaTopLevelTypeName(source) ?: continue
        val migrated = migrateStairBlockSubclassConstructorSource(source, className)
        if (migrated != source) {
            migratedConstructors += className
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate StairBlock subclass constructor from state supplier to BlockState",
                before = "Subclass(Supplier<BlockState> state, Properties properties); super(state, properties)",
                after = "Subclass(BlockState state, Properties properties); super(state, properties)",
                confidence = Confidence.HIGH,
                ruleId = "struct-stairblock-state-constructor"
            ))
            if (!dryRun) {
                file.writeText(migrated)
            }
        }
    }

    if (migratedConstructors.isEmpty()) return changes
    for (file in javaFiles) {
        val source = file.readText()
        val migrated = migrateStairBlockConstructorCallSitesSource(source, migratedConstructors)
        if (migrated != source) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Pass BlockState directly to StairBlock subclass constructors",
                before = "new CustomStairsBlock(() -> BLOCK.defaultBlockState(), properties)",
                after = "new CustomStairsBlock(BLOCK.defaultBlockState(), properties)",
                confidence = Confidence.HIGH,
                ruleId = "struct-stairblock-state-constructor"
            ))
            if (!dryRun) {
                file.writeText(migrated)
            }
        }
    }
    return changes
}

internal fun StructuralRefactorPass.migrateStairBlockSubclassConstructorSource(source: String, className: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    val constructorPattern = Regex(
        """public\s+${Regex.escape(className)}\s*\(\s*(?:java\.util\.function\.)?Supplier\s*<\s*BlockState\s*>\s+($id)\s*,\s*((?:BlockBehaviour\.)?Properties)\s+($id)\s*\)"""
    )
    val executableCode = maskJavaCommentsAndLiterals(source)
    val match = constructorPattern.find(executableCode) ?: return source
    val stateName = match.groupValues[1]
    val propertiesType = match.groupValues[2]
    val propertiesName = match.groupValues[3]
    val superPattern = Regex("""super\(\s*${Regex.escape(stateName)}\s*,\s*${Regex.escape(propertiesName)}\s*\)\s*;""")
    if (!superPattern.containsMatchIn(executableCode)) return source
    var result = source.substring(0, match.range.first) +
        "public $className(BlockState $stateName, $propertiesType $propertiesName)" +
        source.substring(match.range.last + 1)
    val resultExecutableCode = maskJavaCommentsAndLiterals(result)
    if (!Regex("""\bSupplier\s*<""").containsMatchIn(resultExecutableCode) &&
        !Regex("""\bjava\.util\.function\.Supplier\s*<""").containsMatchIn(resultExecutableCode)) {
        result = removeImport(result, "java.util.function.Supplier")
    }
    return result
}

internal fun StructuralRefactorPass.migrateStairBlockConstructorCallSitesSource(source: String, constructorClasses: Set<String>): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new ") || !executableCode.contains("() ->") || !executableCode.contains(".defaultBlockState()")) return source
    var result = source
    for (className in constructorClasses) {
        result = rewriteExecutableJavaNew(result, className) { args ->
            if (args.size != 2) return@rewriteExecutableJavaNew null
            val stateSupplier = args[0].trim()
            if (!stateSupplier.startsWith("() ->") || !stateSupplier.contains(".defaultBlockState()")) {
                return@rewriteExecutableJavaNew null
            }
            val state = stateSupplier.removePrefix("() ->").trim()
            "new $className($state, ${args[1].trim()})"
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateFlowerBlockMobEffectHolderConstructors(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val migratedConstructors = linkedSetOf("FlowerBlock")

    for (file in javaFiles) {
        val source = file.readText()
        if (!source.contains("extends FlowerBlock") || !source.contains("Supplier") || !source.contains("super(")) continue
        val className = javaTopLevelTypeName(source) ?: continue
        val migrated = migrateFlowerBlockSubclassConstructorSource(source, className)
        if (migrated != source) {
            migratedConstructors += className
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate FlowerBlock subclass constructor to Holder<MobEffect>",
                before = "Subclass(Supplier<MobEffect> effect, int duration, Properties properties)",
                after = "Subclass(Holder<MobEffect> effect, float duration, Properties properties)",
                confidence = Confidence.HIGH,
                ruleId = "struct-flower-block-holder-constructor"
            ))
            if (!dryRun) {
                file.writeText(migrated)
            }
        }
    }

    for (file in javaFiles) {
        val source = file.readText()
        val migrated = migrateFlowerBlockConstructorCallSitesSource(source, migratedConstructors)
        if (migrated != source) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Pass MobEffect holders directly to FlowerBlock constructors",
                before = "new FlowerBlock(() -> MobEffects.EFFECT, duration, properties)",
                after = "new FlowerBlock(MobEffects.EFFECT, duration, properties)",
                confidence = Confidence.HIGH,
                ruleId = "struct-flower-block-holder-constructor"
            ))
            if (!dryRun) {
                file.writeText(migrated)
            }
        }
    }

    return changes
}

internal fun StructuralRefactorPass.migrateFlowerBlockSubclassConstructorSource(source: String, className: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    val propertiesTypePattern = """(?:BlockBehaviour\.)?Properties"""
    val constructorPattern = Regex(
        """public\s+${Regex.escape(className)}\s*\(\s*((?:java\.util\.function\.)?Supplier\s*<\s*(?:\?\s+extends\s+)?MobEffect\s*>)\s+($id)\s*,\s*int\s+($id)\s*,\s*($propertiesTypePattern)\s+($id)\s*\)"""
    )
    val executableCode = maskJavaCommentsAndLiterals(source)
    val match = constructorPattern.find(executableCode) ?: return source
    val effectName = match.groupValues[2]
    val durationName = match.groupValues[3]
    val propertiesType = match.groupValues[4]
    val propertiesName = match.groupValues[5]
    val superPattern = Regex(
        """super\(\s*${Regex.escape(effectName)}\s*,\s*${Regex.escape(durationName)}\s*,\s*${Regex.escape(propertiesName)}\s*\)\s*;"""
    )
    if (!superPattern.containsMatchIn(executableCode)) return source

    var result = source.substring(0, match.range.first) +
        "public $className(Holder<MobEffect> $effectName, float $durationName, $propertiesType $propertiesName)" +
        source.substring(match.range.last + 1)
    result = addImportIfMissing(result, "net.minecraft.core.Holder")
    val resultExecutableCode = maskJavaCommentsAndLiterals(result)
    if (!resultExecutableCode.contains("Supplier<") && !resultExecutableCode.contains("Supplier<?")) {
        result = removeImportIfPresent(result, "java.util.function.Supplier")
    }
    return result
}

internal fun StructuralRefactorPass.migrateFlowerBlockConstructorCallSitesSource(source: String, constructorClasses: Set<String>): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new ") || !executableCode.contains("() ->") || !executableCode.contains("Effects.")) return source
    var result = source
    for (className in constructorClasses) {
        result = rewriteExecutableJavaNew(result, className) { args ->
            if (args.size != 3) return@rewriteExecutableJavaNew null
            val effectSupplier = args[0].trim()
            if (!effectSupplier.startsWith("() ->")) return@rewriteExecutableJavaNew null
            val effect = normalizeMobEffectHolderExpression(effectSupplier.removePrefix("() ->").trim())
                ?: return@rewriteExecutableJavaNew null
            "new $className($effect, ${args[1].trim()}, ${args[2].trim()})"
        }
    }
    return result
}

internal fun StructuralRefactorPass.normalizeMobEffectHolderExpression(expression: String): String? {
    val trimmed = expression.trim()
    val match = Regex("""^((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*Effects\.[A-Z][A-Z0-9_$]*)(?:\.get\(\))?$""")
        .matchEntire(trimmed)
        ?: return null
    return match.groupValues[1]
}

internal fun StructuralRefactorPass.migrateMmlibRecipeIdTracking(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()

    for (file in javaFiles) {
        val content = file.readText()
        if (!content.contains("trackRecipeExperience") && !content.contains("lastRecipeID")) continue
        val original = content
        val migrated = migrateRecipeExperienceTrackingSource(content)
        if (migrated != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Read tracked recipe ids from the RecipeHolder-owned lastRecipeID",
                before = "trackRecipeExperience(Recipe<?> recipe)",
                after = "trackRecipeExperience(ResourceLocation recipeId)",
                confidence = Confidence.HIGH,
                ruleId = "struct-mmlib-abstract-recipe-id-tracking"
            ))
            if (!dryRun) {
                file.writeText(migrated)
            }
        }
    }

    return changes
}

internal fun StructuralRefactorPass.migrateDeferredHolderPresenceChecks(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()
    val declarationPattern = Regex("""DeferredHolder\s*<[^;\r\n]+>\s+([A-Za-z_$][\w$]*)""")
    val staticHolderRefs = javaFiles.flatMap { file ->
        val content = file.readText()
        if (!content.contains("DeferredHolder")) {
            emptyList()
        } else {
            val owner = classNameOfJavaSource(content) ?: return@flatMap emptyList()
            val executableCode = maskJavaCommentsAndLiterals(content)
            declarationPattern.findAll(executableCode)
                .map { owner to it.groupValues[1] }
                .toList()
        }
    }

    for (file in javaFiles) {
        var content = file.readText()
        if (!content.contains("isPresent")) continue
        val executableCode = maskJavaCommentsAndLiterals(content)
        val holderNames = declarationPattern.findAll(executableCode)
            .map { it.groupValues[1] }
            .toSet()

        val original = content
        for (name in holderNames) {
            content = replaceExecutableRegex(
                content,
                Regex("""(?<![A-Za-z_$0-9.])${Regex.escape(name)}\s*\.\s*isPresent\s*\(\s*\)""")
            ) { "${name}.isBound()" }
        }
        for ((owner, name) in staticHolderRefs) {
            content = replaceExecutableRegex(
                content,
                Regex("""(?<![A-Za-z_$0-9.])${Regex.escape(owner)}\s*\.\s*${Regex.escape(name)}\s*\.\s*isPresent\s*\(\s*\)""")
            ) { "$owner.$name.isBound()" }
        }
        content = replaceExecutableRegex(
            content,
            Regex("""\bNeoForgeMod\.([A-Z][A-Z0-9_]*)\.isPresent\(\)""")
        ) { match -> "NeoForgeMod.${match.groupValues[1]}.isBound()" }
        content = replaceExecutableRegex(
            content,
            Regex("""\bnet\.neoforged\.neoforge\.common\.NeoForgeMod\.([A-Z][A-Z0-9_]*)\.isPresent\(\)""")
        ) { match -> "net.neoforged.neoforge.common.NeoForgeMod.${match.groupValues[1]}.isBound()" }

        if (content != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate DeferredHolder presence checks to isBound()",
                before = "holder.isPresent()",
                after = "holder.isBound()",
                confidence = Confidence.HIGH,
                ruleId = "struct-deferredholder-isbound"
            ))
            if (!dryRun) {
                file.writeText(content)
            }
        }
    }

    return changes
}

private fun String.substringAtMatchRange(range: IntRange): String =
    if (range.isEmpty()) "" else substring(range.first, range.last + 1)

private open class SourceBackedMatchGroupCollection(
    protected val source: String,
    protected val maskedGroups: MatchGroupCollection
) : AbstractCollection<MatchGroup?>(), MatchGroupCollection {
    override val size: Int
        get() = maskedGroups.size

    override fun iterator(): Iterator<MatchGroup?> =
        (0 until size).asSequence().map(::get).iterator()

    override fun get(index: Int): MatchGroup? =
        sourceBacked(maskedGroups[index])

    protected fun sourceBacked(group: MatchGroup?): MatchGroup? =
        group?.let { MatchGroup(source.substringAtMatchRange(it.range), it.range) }
}

private class SourceBackedNamedMatchGroupCollection(
    source: String,
    private val maskedNamedGroups: MatchNamedGroupCollection
) : SourceBackedMatchGroupCollection(source, maskedNamedGroups), MatchNamedGroupCollection {
    override fun get(name: String): MatchGroup? =
        sourceBacked(maskedNamedGroups[name])
}

private class SourceBackedMatchResult(
    private val source: String,
    private val maskedMatch: MatchResult
) : MatchResult {
    override val range: IntRange
        get() = maskedMatch.range

    override val value: String
        get() = source.substringAtMatchRange(range)

    override val groups: MatchGroupCollection =
        (maskedMatch.groups as? MatchNamedGroupCollection)
            ?.let { SourceBackedNamedMatchGroupCollection(source, it) }
            ?: SourceBackedMatchGroupCollection(source, maskedMatch.groups)

    override val groupValues: List<String>
        get() = groups.map { it?.value.orEmpty() }

    override fun next(): MatchResult? =
        maskedMatch.next()?.let { SourceBackedMatchResult(source, it) }
}

internal fun StructuralRefactorPass.replaceExecutableRegex(source: String, pattern: Regex, replacement: (MatchResult) -> String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val matches = pattern.findAll(executableCode).toList()
    if (matches.isEmpty()) return source

    var result = source
    for (maskedMatch in matches.asReversed()) {
        val match = SourceBackedMatchResult(source, maskedMatch)
        val sourceMatch = pattern.find(source, match.range.first)
            ?.takeIf { it.range == match.range }
        val replacementMatch = sourceMatch ?: match
        result = result.substring(0, match.range.first) +
            replacement(replacementMatch) +
            result.substring(match.range.last + 1)
    }
    return result
}

internal fun StructuralRefactorPass.removeExecutableImport(source: String, importName: String): String =
    replaceExecutableRegex(
        source,
        Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)};\s*\r?\n""")
    ) { "" }

internal fun StructuralRefactorPass.addExecutableImportIfMissing(source: String, importName: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (executableCode.contains("import $importName;")) return source
    val importLine = "import $importName;\n"
    val lastImport = Regex("""^import\s+[^;]+;""", RegexOption.MULTILINE)
        .findAll(executableCode)
        .lastOrNull()
    if (lastImport != null) {
        val insertPos = lastImport.range.last + 1
        return source.substring(0, insertPos) + "\n" + importLine + source.substring(insertPos)
    }

    val packageDecl = Regex("""^package\s+[^;]+;""", RegexOption.MULTILINE).find(executableCode)
    if (packageDecl != null) {
        val insertPos = packageDecl.range.last + 1
        return source.substring(0, insertPos) + "\n\n" + importLine + source.substring(insertPos)
    }

    return importLine + source
}

internal fun StructuralRefactorPass.migrateMenuScreensRegistration(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()

    for (file in javaFiles) {
        val content = file.readText()
        if (!content.contains("MenuScreens.register(") &&
            !content.contains(".renderScreens();") &&
            !(content.contains("FMLClientSetupEvent") && content.contains("event.register("))) {
            continue
        }
        val migrated = migrateLegacyMenuScreensRegistration(content)

        if (migrated != content) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate client screen registration to RegisterMenuScreensEvent",
                before = "FMLClientSetupEvent + MenuScreens.register",
                after = "RegisterMenuScreensEvent + event.register",
                confidence = Confidence.HIGH,
                ruleId = "struct-register-menu-screens-event"
            ))
            if (!dryRun) {
                file.writeText(migrated)
            }
        }
    }

    return changes
}

internal fun StructuralRefactorPass.migrateSourceBackedAccessorCalls(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    if (javaFiles.isEmpty()) return emptyList()

    val id = """[A-Za-z_$][\w$]*"""
    val accessorsByType = mutableMapOf<String, MutableMap<String, String>>()
    val recordSetterLikeAccessorsByType = mutableMapOf<String, MutableMap<String, String>>()
    val capabilityHelpers = mutableSetOf<String>()
    javaFiles.forEach { file ->
        val source = file.readText()
        val className = Regex("""\b(?:class|enum|record)\s+($id)\b""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: return@forEach
        Regex("""(?m)\bpublic\s+[\w<>\[\].?,\s]+\s+get([A-Z][A-Za-z0-9_$]*)\s*\(\s*\)""")
            .findAll(source)
            .forEach { getter ->
                val suffix = getter.groupValues[1]
                val accessor = suffix.replaceFirstChar { it.lowercase() }
                val method = "get$suffix"
                accessorsByType.getOrPut(className) { mutableMapOf() }[accessor] = method
            }
        Regex("""\brecord\s+${Regex.escape(className)}\s*\(""")
            .find(source)
            ?.let { recordMatch ->
                val openParen = source.indexOf('(', recordMatch.range.last)
                val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
                if (closeParen > openParen) {
                    splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
                        .mapNotNull { raw ->
                            Regex("""([A-Za-z_$][\w$]*)\s*$""").find(raw.trim())?.groupValues?.get(1)
                        }
                        .forEach { component ->
                            val setterLike = "set" + component.replaceFirstChar { it.uppercase() }
                            recordSetterLikeAccessorsByType
                                .getOrPut(className) { mutableMapOf() }[setterLike] = component
                        }
                }
            }
        if (Regex("""\bpublic\s+static\s+LazyOptional\s*<[^>]+>\s+get\s*\(\s*(?:[A-Za-z_$][\w$]*\.)?Player\s+($id)\s*\)""")
                .containsMatchIn(source)) {
            capabilityHelpers.add(className)
        }
    }

    val payloadTypesByPackage = javaFiles.flatMap { file ->
        val source = file.readText()
        val packageName = packageNameOf(source)
        registeredPayloadTypes(source)
            .map { packageName to it }
    }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, payloadTypes) -> payloadTypes.toSet() }
        .filterValues { it.size == 1 }
        .mapValues { (_, payloadTypes) -> payloadTypes.single() }

    val changes = mutableListOf<Change>()
    javaFiles.forEach { file ->
        val original = file.readText()
        var modified = original
        for ((typeName, methods) in accessorsByType) {
            val variables = Regex("""\b(?:final\s+)?${Regex.escape(typeName)}\s+($id)\b""")
                .findAll(original)
                .map { it.groupValues[1] }
                .toSet()
            if (variables.isNotEmpty()) {
                for (variable in variables) {
                    for ((accessor, getter) in methods) {
                    modified = Regex("""\b${Regex.escape(variable)}\.${Regex.escape(accessor)}\(\)""")
                        .replace(modified, "$variable.$getter()")
                    }
                }
            }
        }
        for ((typeName, methods) in recordSetterLikeAccessorsByType) {
            val variables = Regex("""\b(?:final\s+)?${Regex.escape(typeName)}\s+($id)\b""")
                .findAll(original)
                .map { it.groupValues[1] }
                .toSet()
            if (variables.isNotEmpty()) {
                for (variable in variables) {
                    for ((setterLike, component) in methods) {
                    modified = Regex("""\b${Regex.escape(variable)}\.${Regex.escape(setterLike)}\(\)""")
                        .replace(modified, "$variable.$component()")
                    }
                }
            }
        }
        capabilityHelpers.forEach { helper ->
            modified = rewriteJavaCall(modified, "getCapability") { receiver, args ->
                val capabilityArg = args.firstOrNull()?.trim() ?: return@rewriteJavaCall null
                if (!capabilityArg.matches(Regex("""${Regex.escape(helper)}\.[A-Z_$][A-Z0-9_$]*"""))) {
                    return@rewriteJavaCall null
                }
                if (args.size > 2 || (args.size == 2 && args[1].trim() != "null")) {
                    return@rewriteJavaCall null
                }
                "$helper.get($receiver)"
            }
            modified = Regex("""(?:[A-Za-z_$][\w$]*\.)*LazyOptional\.ofNullable\(\s*${Regex.escape(helper)}\.get\(([^)]*)\)\s*\)""")
                .replace(modified) { match -> "$helper.get(${match.groupValues[1].trim()})" }
        }

        if (modified.contains(".CHANNEL")) {
            val imports = Regex("""(?m)^import\s+([\w.]+)\.($id);\s*$""")
                .findAll(modified)
                .toList()
            for (importMatch in imports) {
                val packageName = importMatch.groupValues[1]
                val importedType = importMatch.groupValues[2]
                val payloadType = payloadTypesByPackage[packageName] ?: continue
                val channelReference = "$importedType.CHANNEL"
                if (modified.contains(channelReference)) {
                    val payloadImport = "import $packageName.$payloadType;"
                    modified = modified.replace(importMatch.value, payloadImport)
                    modified = modified.replace(channelReference, "$payloadType.TYPE")
                }
            }
        }

        if (modified != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate source-backed accessor and generated network surface calls",
                before = "record-style accessor or legacy channel surface",
                after = "declared getter or registered payload TYPE",
                confidence = Confidence.HIGH,
                ruleId = "struct-source-backed-accessor-calls"
            ))
            if (!dryRun) file.writeText(modified)
        }
    }
    return changes
}

internal fun StructuralRefactorPass.registeredPayloadTypes(source: String): Set<String> {
    if (!source.contains(".TYPE") || !source.contains("PayloadRegistrar")) {
        return emptySet()
    }
    val id = """[A-Za-z_$][\w$]*"""
    val code = maskJavaCommentsAndLiterals(source)
    val registrarVariables = Regex(
        """(?:net\.neoforged\.neoforge\.network\.registration\.)?PayloadRegistrar\s+($id)\s*="""
    )
        .findAll(code)
        .map { it.groupValues[1] }
        .toSet()
    if (registrarVariables.isEmpty()) return emptySet()
    return registrarVariables.flatMap { registrar ->
        Regex("""\b${Regex.escape(registrar)}\.$id\s*\(\s*($id)\.TYPE\b""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toList()
    }.toSet()
}

internal fun StructuralRefactorPass.migrateUseItemOnInteractionResultReturns(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()
    val interactionResultHelpers = javaFiles
        .flatMap { file ->
            Regex("""(?m)^\s*(?:public|protected|private|default)?\s+(?:static\s+)?InteractionResult\s+([A-Za-z_$][\w$]*)\s*\(""")
                .findAll(file.readText())
                .map { it.groupValues[1] }
                .toList()
        }
        .toSet()
    if (interactionResultHelpers.isEmpty()) return changes
    val helperPattern = interactionResultHelpers.joinToString("|") { Regex.escape(it) }
    val helperCall = """(?:(?:this|[A-Za-z_$][\w$]*)\.)?(?:$helperPattern)\([^;\r\n]*\)"""
    val returnInteractionResult = Regex("""(?m)^([ \t]*)return\s+($helperCall)\s*;\s*$""")
    val returnTernaryInteractionResult = Regex("""(?m)^([ \t]*)return\s+(.+?)\?\s*([^?:;]+?)\s*:\s*([^?:;]+?)\s*;\s*$""")
    val helperCallOnly = Regex("""^$helperCall$""")
    val compareItemInteractionResult = Regex("""($helperCall)\s*==\s*(?:net\.minecraft\.world\.)?ItemInteractionResult\.([A-Z_]+)""")

    fun wrapInteractionResult(expression: String, indent: String): String =
        "switch ($expression) {\n" +
            "${indent}    case SUCCESS -> net.minecraft.world.ItemInteractionResult.SUCCESS;\n" +
            "${indent}    case SUCCESS_NO_ITEM_USED -> net.minecraft.world.ItemInteractionResult.SUCCESS;\n" +
            "${indent}    case CONSUME -> net.minecraft.world.ItemInteractionResult.CONSUME;\n" +
            "${indent}    case CONSUME_PARTIAL -> net.minecraft.world.ItemInteractionResult.CONSUME_PARTIAL;\n" +
            "${indent}    case FAIL -> net.minecraft.world.ItemInteractionResult.FAIL;\n" +
            "${indent}    default -> net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;\n" +
            "${indent}}"

    for (file in javaFiles) {
        var content = file.readText()
        if (!content.contains("ItemInteractionResult useItemOn")) continue
        val original = content
        val methodEdits = mutableListOf<Pair<IntRange, String>>()
        javaMethodRangesIncludingDefault(content)
            .filter { method ->
                method.name == "useItemOn" &&
                    Regex("""\bItemInteractionResult\s+useItemOn\s*\(""").containsMatchIn(method.header)
            }
            .forEach { method ->
                var methodText = content.substring(method.range)
                methodText = compareItemInteractionResult.replace(methodText) { match ->
                    "${match.groupValues[1]} == InteractionResult.${match.groupValues[2]}"
                }
                methodText = returnTernaryInteractionResult.replace(methodText) { match ->
                    val indent = match.groupValues[1]
                    val condition = match.groupValues[2].trim()
                    val leftExpression = match.groupValues[3].trim()
                    val rightExpression = match.groupValues[4].trim()
                    if (!helperCallOnly.matches(leftExpression) && !helperCallOnly.matches(rightExpression)) {
                        return@replace match.value
                    }
                    val left = if (helperCallOnly.matches(leftExpression)) wrapInteractionResult(leftExpression, indent) else leftExpression
                    val right = if (helperCallOnly.matches(rightExpression)) wrapInteractionResult(rightExpression, indent) else rightExpression
                    "${indent}return $condition ? $left : $right;"
                }
                methodText = returnInteractionResult.replace(methodText) { match ->
                    val indent = match.groupValues[1]
                    val expression = match.groupValues[2].trim()
                    "${indent}return ${wrapInteractionResult(expression, indent)};"
                }
                val resultLocals = Regex("""(?<![\w$.])InteractionResult\s+([A-Za-z_$][\w$]*)\s*[=;]""")
                    .findAll(maskNestedBodies(methodText))
                    .map { it.groupValues[1] }
                    .toSet()
                if (resultLocals.isNotEmpty()) {
                    val localReturn = Regex("""(?m)^([ \t]*)return\s+(${resultLocals.joinToString("|") { Regex.escape(it) }})\s*;""")
                    methodText = replaceOutsideNestedBodies(methodText, localReturn) { match ->
                        "${match.groupValues[1]}return ${wrapInteractionResult(match.groupValues[2], match.groupValues[1])};"
                    }
                }
                val multilineReturn = Regex("""(?m)^([ \t]*)return\s+((?:(?:this|[A-Za-z_$][\w$]*)\.)?(?:$helperPattern))\s*\(""")
                var cursor = 0
                while (true) {
                    val masked = maskJavaCommentsAndLiterals(methodText)
                    val match = multilineReturn.find(masked, cursor) ?: break
                    val openParen = match.range.last
                    val closeParen = findMatchingParen(masked, openParen)
                    if (closeParen < 0) break
                    val terminator = Regex("""^\s*;""").find(masked.substring(closeParen + 1))
                    if (terminator == null || !methodText.substring(openParen, closeParen).contains('\n')) {
                        cursor = closeParen + 1
                        continue
                    }
                    val indent = match.groupValues[1]
                    val expression = methodText.substring(match.range.first + indent.length + "return".length, closeParen + 1).trim()
                    val replacement = "${indent}return ${wrapInteractionResult(expression, indent)};"
                    methodText = methodText.substring(0, match.range.first) + replacement + methodText.substring(closeParen + 1 + terminator.range.last + 1)
                    cursor = match.range.first + replacement.length
                }
                if (methodText != content.substring(method.range)) {
                    methodEdits += method.range to methodText
                }
            }
        for ((range, replacement) in methodEdits.sortedByDescending { it.first.first }) {
            content = content.replaceRange(range, replacement)
        }

        if (content != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Map helper InteractionResult return values to ItemInteractionResult in useItemOn",
                before = "return helperReturningInteractionResult(...);",
                after = "return switch (helperReturningInteractionResult(...)) { ... };",
                confidence = Confidence.HIGH,
                ruleId = "struct-useitemon-interactionresult-return"
            ))
            if (!dryRun) {
                file.writeText(content)
            }
        }
    }

    return changes
}

/**
 * Extract mod-bus @SubscribeEvent methods into static inner classes.
 * In NeoForge 1.21.1:
 * - @EventBusSubscriber (no bus specified) = game bus, requires static methods
 * - Instance registration via modEventBus.register(this) conflicts with @EventBusSubscriber
 * - Client-only events can't be registered on the server
 *
 * This method:
 * 1. Finds all @SubscribeEvent methods that handle mod-bus events
 * 2. Separates them into client-only vs common mod-bus events
 * 3. Creates inner classes with @EventBusSubscriber(bus=MOD) / @EventBusSubscriber(bus=MOD, value=Dist.CLIENT)
 * 4. Removes modEventBus.register(this) since inner classes auto-register
*/
internal fun StructuralRefactorPass.extractClientOnlyEventMethods(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
        .forEach { file ->
            val content = file.readText()
            val executableContent = maskJavaCommentsAndLiterals(content)
            if (!Regex("""@\s*Mod\s*\(""").containsMatchIn(executableContent)) return@forEach
            if (!Regex("""@\s*SubscribeEvent\b""").containsMatchIn(executableContent)) return@forEach
            val code = executableContent
            val imports = javaNonStaticImports(code)

            val lines = content.lines().toMutableList()
            val executableLines = executableContent.lines()
            val separator = if (content.contains("\r\n")) "\r\n" else "\n"

            data class ExtractedMethod(val startLine: Int, val endLine: Int, val text: String, val isClientOnly: Boolean)
            val modBusMethods = mutableListOf<ExtractedMethod>()

            var i = 0
            while (i < lines.size) {
                if (executableLines.getOrNull(i)?.trimStart()?.startsWith("@SubscribeEvent") == true) {
                    val annotLine = i
                    var methodLine = i + 1
                    while (methodLine < executableLines.size && executableLines[methodLine].trimStart().startsWith("@")) {
                        methodLine++
                    }
                    if (methodLine >= executableLines.size) { i++; continue }

                    val methodDecl = executableLines[methodLine]

                    // Determine if this is a mod-bus event
                    val eventType = Regex("""\(\s*(?:final\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\b""")
                        .find(methodDecl)
                        ?.groupValues
                        ?.get(1)
                    val isModBusEvent = eventType?.let { isKnownModBusEventParameterType(it, imports) } == true

                    if (isModBusEvent) {
                        val isClientOnly = eventType?.let { isKnownClientOnlyModBusEventParameterType(it, imports) } == true

                        val openBraceIdx = (methodLine until executableLines.size).firstOrNull { executableLines[it].contains("{") }
                        if (openBraceIdx != null) {
                            var depth = 0
                            var methodEnd: Int = openBraceIdx
                            for (j in openBraceIdx until executableLines.size) {
                                depth += executableLines[j].count { c -> c == '{' } - executableLines[j].count { c -> c == '}' }
                                if (depth <= 0) {
                                    methodEnd = j
                                    break
                                }
                            }
                            val methodText = lines.subList(annotLine, methodEnd + 1).joinToString(separator)
                            modBusMethods.add(ExtractedMethod(annotLine, methodEnd, methodText, isClientOnly))
                        }
                    }
                }
                i++
            }

            if (modBusMethods.isEmpty()) return@forEach

            val modidRef = modAnnotationArgumentExpression(content)
                ?: throw IllegalStateException("Cannot derive mod id expression for mod-bus event extraction in $file")

            val clientMethods = modBusMethods.filter { it.isClientOnly }
            val commonMethods = modBusMethods.filter { !it.isClientOnly }

            val innerClassLines = mutableListOf<String>()

            // Build common mod-bus inner class
            if (commonMethods.isNotEmpty()) {
                innerClassLines.add("")
                innerClassLines.add("    @EventBusSubscriber(modid = $modidRef, bus = EventBusSubscriber.Bus.MOD)")
                innerClassLines.add("    public static class ModEvents {")
                for (cm in commonMethods) {
                    for (ml in cm.text.lines()) {
                        val trimmed = ml.trimStart()
                        if (trimmed.startsWith("public void ") || trimmed.startsWith("public static void ")) {
                            innerClassLines.add("        ${trimmed.replace("public void ", "public static void ")}")
                        } else {
                            innerClassLines.add("        $trimmed")
                        }
                    }
                    innerClassLines.add("")
                }
                innerClassLines.add("    }")
            }

            // Build client-only inner class
            if (clientMethods.isNotEmpty()) {
                innerClassLines.add("")
                innerClassLines.add("    @EventBusSubscriber(modid = $modidRef, bus = EventBusSubscriber.Bus.MOD, value = net.neoforged.api.distmarker.Dist.CLIENT)")
                innerClassLines.add("    public static class ClientModEvents {")
                for (cm in clientMethods) {
                    for (ml in cm.text.lines()) {
                        val trimmed = ml.trimStart()
                        if (trimmed.startsWith("public void ") || trimmed.startsWith("public static void ")) {
                            innerClassLines.add("        ${trimmed.replace("public void ", "public static void ")}")
                        } else {
                            innerClassLines.add("        $trimmed")
                        }
                    }
                    innerClassLines.add("")
                }
                innerClassLines.add("    }")
            }

            // Remove original methods (in reverse order)
            for (cm in modBusMethods.sortedByDescending { it.startLine }) {
                for (j in cm.endLine downTo cm.startLine) {
                    lines.removeAt(j)
                }
            }

            // Remove modEventBus.register(this) since inner classes auto-register
            var currentExecutableLines = maskJavaCommentsAndLiterals(lines.joinToString(separator)).lines()
            val modBusRegIdx = currentExecutableLines.indexOfFirst {
                Regex("""\bmodEventBus\s*\.\s*register\s*\(\s*this\s*\)""").containsMatchIn(it)
            }
            if (modBusRegIdx >= 0) {
                lines.removeAt(modBusRegIdx)
            }

            // Check if outer class still has @SubscribeEvent methods; if not, remove @EventBusSubscriber
            currentExecutableLines = maskJavaCommentsAndLiterals(lines.joinToString(separator)).lines()
            val hasRemainingSubscribeEvent = currentExecutableLines.any { line ->
                val t = line.trimStart()
                t.startsWith("@SubscribeEvent") && !t.contains("class ")
            }
            if (!hasRemainingSubscribeEvent) {
                val ebsIdx = currentExecutableLines.indexOfFirst {
                    it.trimStart().startsWith("@EventBusSubscriber") && !it.contains("class ")
                }
                val classIdx = currentExecutableLines.indexOfFirst {
                    it.contains("public class ") || it.contains("public final class ")
                }
                if (ebsIdx >= 0 && classIdx >= 0 && ebsIdx < classIdx) {
                    lines.removeAt(ebsIdx)
                }
            }

            // Find the class closing brace and insert inner classes before it
            var lastBrace = lines.size - 1
            while (lastBrace >= 0 && !lines[lastBrace].trimStart().startsWith("}")) {
                lastBrace--
            }
            if (lastBrace >= 0) {
                lines.addAll(lastBrace, innerClassLines)
            }

            val desc = buildString {
                append("Extract ")
                if (commonMethods.isNotEmpty()) append("${commonMethods.size} mod-bus")
                if (commonMethods.isNotEmpty() && clientMethods.isNotEmpty()) append(" + ")
                if (clientMethods.isNotEmpty()) append("${clientMethods.size} client-only")
                append(" @SubscribeEvent method(s) to inner class(es)")
            }

            changes.add(Change(
                file = file,
                line = modBusMethods.first().startLine + 1,
                description = desc,
                before = "Instance @SubscribeEvent methods + modEventBus.register(this)",
                after = "@EventBusSubscriber static inner class(es)",
                confidence = Confidence.HIGH,
                ruleId = "struct-extract-mod-bus-events"
            ))

            if (!dryRun) {
                file.writeText(lines.joinToString(separator))
            }
        }

    return changes
}

internal fun StructuralRefactorPass.migrateCommonBusModEventListeners(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val modBusListenerRefs = collectModBusListenerRefs(srcDir)
    if (modBusListenerRefs.isEmpty()) return changes
    val refPattern = modBusListenerRefs.joinToString("|") { Regex.escape(it) }
    val badListenerPattern = Regex(
        """(?m)^[ \t]*NeoForge\.EVENT_BUS\.addListener\(\s*($refPattern)\s*\);\s*\r?\n?"""
    )

    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
        .forEach { file ->
            val original = file.readText()
            if (!original.contains("@Mod(") && !original.contains("@Mod\n")) return@forEach
            if (!original.contains("NeoForge.EVENT_BUS.addListener")) return@forEach

            val matches = badListenerPattern.findAll(original).toList()
            if (matches.isEmpty()) return@forEach

            val modBusVar = detectModBusVariable(original) ?: return@forEach

            val refsToRegister = matches.map { it.groupValues[1] }.distinct()
            var modified = badListenerPattern.replace(original, "")
            val separator = if (modified.contains("\r\n")) "\r\n" else "\n"
            val lines = modified.lines().toMutableList()

            val insertIndex = lines.indexOfFirst {
                Regex("""\bIEventBus\s+${Regex.escape(modBusVar)}\s*=""").containsMatchIn(it)
            }.takeIf { it >= 0 } ?: lines.indexOfFirst {
                Regex("""\bpublic\s+\w+\s*\([^)]*\bIEventBus\s+${Regex.escape(modBusVar)}\b[^)]*\)\s*\{""")
                    .containsMatchIn(it)
            }.takeIf { it >= 0 } ?: return@forEach

            val indentation = lines[insertIndex].takeWhile { it == ' ' || it == '\t' }
            val registrationsToInsert = refsToRegister
                .filterNot { ref -> modified.contains("$modBusVar.addListener($ref);") }
                .map { ref -> "$indentation$modBusVar.addListener($ref);" }

            if (registrationsToInsert.isNotEmpty()) {
                lines.addAll(insertIndex + 1, registrationsToInsert)
                modified = lines.joinToString(separator)
            }

            if (modified == original) return@forEach

            changes.add(Change(
                file = file,
                line = lineNumberAt(original, matches.first().range.first),
                description = "Move mod-bus listener registration from NeoForge.EVENT_BUS to mod event bus",
                before = matches.joinToString(" ") { it.value.trim() },
                after = refsToRegister.joinToString(" ") { "$modBusVar.addListener($it);" },
                confidence = Confidence.HIGH,
                ruleId = "struct-modbus-listener-registration"
            ))

            if (!dryRun) {
                file.writeText(modified)
            }
        }

    return changes
}

internal fun StructuralRefactorPass.collectModBusListenerRefs(srcDir: Path): Set<String> {
    val refs = mutableSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
        .forEach { file ->
            val text = file.readText()
            val code = maskJavaComments(text)
            val packageName = packageNameOf(code)
            val imports = javaNonStaticImports(code)
            val executableCode = maskJavaCommentsAndLiterals(text)
            val typeBlocks = javaTypeBlocks(text, executableCode)
            val methodPattern = Regex(
                """(?m)\b(?:public|protected|private)?\s*(?:static\s+)?void\s+(\w+)\s*\(\s*(?:final\s+)?([\w.]+)(?:<[^>]+>)?\s+\w+"""
            )
            methodPattern.findAll(executableCode).forEach { match ->
                val methodName = match.groupValues[1]
                val parameterType = match.groupValues[2]
                val isModBusEvent = isKnownModBusEventParameterType(parameterType, imports)
                if (isModBusEvent) {
                    val owner = javaTypeBlockContainingOffset(match.range.first, typeBlocks)
                    if (owner != null) {
                        if (owner.isAccessibleFromSamePackage) {
                            refs.add("${owner.referenceName}::$methodName")
                        }
                        if (packageName.isNotBlank() && owner.isAccessibleOutsidePackage) {
                            refs.add("$packageName.${owner.referenceName}::$methodName")
                        }
                    }
                }
            }
        }
    return refs
}

internal fun StructuralRefactorPass.ensureModEventBusSurface(source: String): Pair<String, String>? {
    resolveModEventBusName(source)?.let { return source to it }
    val className = classNameOfJavaSource(source) ?: return null
    if (constructorSections(source, className).isNotEmpty()) return null
    val executable = maskJavaCommentsAndLiterals(source)
    val classDeclaration = Regex(
        """\b(?:(?:public|protected|private|abstract|final|static)\s+)*class\s+${Regex.escape(className)}\b[^\{;]*\{"""
    ).find(executable) ?: return null
    val openBrace = executable.indexOf('{', classDeclaration.range.first)
    if (openBrace < 0) return null
    val separator = if (source.contains("\r\n")) "\r\n" else "\n"
    val constructor = buildString {
        append(separator)
        append("    public $className(IEventBus modEventBus) {")
        append(separator)
        append("    }")
        append(separator)
    }
    var migrated = source.substring(0, openBrace + 1) + constructor + source.substring(openBrace + 1)
    migrated = addImportIfMissing(migrated, "net.neoforged.bus.api.IEventBus")
    return migrated to "modEventBus"
}

internal fun StructuralRefactorPass.migrateStaticModBusSubscribers(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    val subscriberFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
        .filter { file ->
            val source = file.readText()
            executableEventBusSubscriberAnnotationRanges(source).any { range ->
                isModBusSubscriberAnnotation(source, range)
            }
        }
    if (subscriberFiles.isEmpty()) return changes
    val mainFile = detectModMainClass(projectDir) ?: return changes
    var mainText = mainFile.readText()
    val preparedMain = ensureModEventBusSurface(mainText) ?: return changes
    mainText = preparedMain.first
    val modBusVar = preparedMain.second
    var registeredAny = false

    data class StaticHandler(
        val methodName: String,
        val eventType: String,
        val isClientOnly: Boolean,
        val subscribeAnnotationRange: IntRange,
        val isPublic: Boolean = false
    )

    subscriberFiles
        .filter { it != mainFile }
        .forEach { file ->
            var text = file.readText()
            val original = text
            var mainChanged = false
            val registrationEvidence = mutableListOf<String>()
            var firstChangedLine: Int? = null
            val annotationRanges = executableEventBusSubscriberAnnotationRanges(text)
                .filter { range -> isModBusSubscriberAnnotation(text, range) }
                .sortedByDescending { it.first }

            for (annotationRange in annotationRanges) {
                val code = maskJavaComments(text)
                val imports = javaNonStaticImports(code)
                val executableCode = maskJavaCommentsAndLiterals(text)
                val typeBlocks = javaTypeBlocks(text, executableCode)
                val annotationText = text.substring(annotationRange.first, annotationRange.last + 1)
                val owner = javaTypeBlockForModAnnotation(annotationRange.last, typeBlocks) ?: continue
                val ownerDistributions = staticModBusSubscriberDistributions(annotationText)
                    ?: error("Unsupported @EventBusSubscriber distribution: ${annotationText.trim()}")
                val handlers = Regex(
                    """(?m)^([ \t]*@SubscribeEvent(?:\([^)\r\n]*\))?[ \t]*\r?\n)([ \t]*(?:(?:public|protected|private)\s+)?static\s+void\s+(\w+)\s*\(\s*(?:final\s+)?([\w.]+)\s+\w+\s*\))"""
                ).findAll(executableCode)
                    .filter { match ->
                        javaTypeBlockContainingOffset(match.range.first, typeBlocks) == owner
                    }
                    .map { match ->
                        val methodPrefix = text.substring(
                            (match.range.first - 160).coerceAtLeast(0),
                            match.range.first
                        )
                        val eventType = match.groupValues[4]
                        StaticHandler(
                            methodName = match.groupValues[3],
                            eventType = eventType,
                            isClientOnly = isKnownClientOnlyModBusEventParameterType(eventType, imports) ||
                                (methodPrefix.contains("@OnlyIn") && methodPrefix.contains("Dist.CLIENT")),
                            subscribeAnnotationRange = match.groups[1]!!.range,
                            isPublic = Regex("""\bpublic\s+static\b""").containsMatchIn(match.groupValues[2])
                        )
                    }
                    .filter { handler -> isKnownModBusEventParameterType(handler.eventType, imports) }
                    .toList()
                if (handlers.isEmpty()) continue

                val ownerCode = executableCode.substring(owner.bodyStart.coerceAtMost(owner.end), owner.end)
                if (handlers.any { handler ->
                        Regex("""\bvoid\s+${Regex.escape(handler.methodName)}\s*\(""")
                            .findAll(ownerCode)
                            .count() > 1
                    }
                ) continue

                val handlerDistributions = handlers.associateWith { handler ->
                    ownerDistributions.intersect(
                        if (handler.isClientOnly) {
                            setOf(StaticSubscriberDistribution.CLIENT)
                        } else {
                            StaticSubscriberDistribution.entries.toSet()
                        }
                    )
                }
                if (handlerDistributions.values.any { it.isEmpty() }) {
                    error(
                        "@EventBusSubscriber distribution excludes one of its handlers: " +
                            annotationText.trim()
                    )
                }

                val packageName = packageNameOf(text)
                val mainPackage = packageNameOf(mainText)
                val ownerPath = javaTypeNestingPath(owner, typeBlocks)
                val directListenerClassName = javaAccessibleTypeReferenceExpression(
                    packageName,
                    mainPackage,
                    owner,
                    typeBlocks
                )?.takeIf {
                    packageName == mainPackage || handlers.all { handler -> handler.isPublic }
                }
                val indent = mainConstructorIndent(mainText, modBusVar)

                if (directListenerClassName != null) {
                    for (handler in handlers) {
                        val listener = "$modBusVar.addListener($directListenerClassName::${handler.methodName});"
                        val registration = staticModBusRegistration(
                            indent,
                            listener,
                            handlerDistributions.getValue(handler)
                        )
                        if (!mainText.contains(listener)) {
                            mainText = insertModBusListener(
                                mainText,
                                modBusVar,
                                registration,
                                owner.referenceName
                            )
                            mainChanged = true
                        }
                        registrationEvidence += listener
                    }
                } else {
                    val bridgeOwner = ownerPath.dropLast(1).asReversed().firstOrNull { candidate ->
                        javaAccessibleTypeReferenceExpression(packageName, mainPackage, candidate, typeBlocks) != null
                    } ?: continue
                    val bridgeOwnerPath = javaTypeNestingPath(bridgeOwner, typeBlocks).map { it.name }
                    val bridgeClassName = javaAccessibleTypeReferenceExpression(
                        packageName,
                        mainPackage,
                        bridgeOwner,
                        typeBlocks
                    ) ?: continue
                    val relativeOwnerName = ownerPath.drop(bridgeOwnerPath.size).joinToString(".") { it.name }

                    handlers.groupBy { handlerDistributions.getValue(it) }
                        .forEach { (distributions, groupedHandlers) ->
                            val bridgeMethodName = staticModBusBridgeMethodName(distributions)
                            val listenerRefs = groupedHandlers.map { "$relativeOwnerName::${it.methodName}" }
                            text = addStaticModBusListenerBridge(
                                text,
                                bridgeOwnerPath,
                                bridgeMethodName,
                                listenerRefs
                            )
                            val bridgeCall = "$bridgeClassName.$bridgeMethodName($modBusVar);"
                            val registration = staticModBusRegistration(indent, bridgeCall, distributions)
                            if (!mainText.contains(bridgeCall)) {
                                mainText = insertModBusListener(
                                    mainText,
                                    modBusVar,
                                    registration,
                                    bridgeOwner.referenceName
                                )
                                mainChanged = true
                            }
                            registrationEvidence += bridgeCall
                        }
                }

                registeredAny = true
                firstChangedLine = minOf(
                    firstChangedLine ?: Int.MAX_VALUE,
                    lineNumberAt(original, annotationRange.first)
                )
                handlers.sortedByDescending { it.subscribeAnnotationRange.first }.forEach { handler ->
                    text = text.removeRange(handler.subscribeAnnotationRange)
                }
                text = text.removeRange(eventBusSubscriberAnnotationRemovalRange(text, annotationRange))
            }

            val finalExecutable = maskJavaCommentsAndLiterals(text)
            if (!Regex("""@\s*SubscribeEvent\b""").containsMatchIn(finalExecutable)) {
                text = removeImport(text, "net.neoforged.bus.api.SubscribeEvent")
            }
            if (!Regex("""@\s*(?:Mod\.)?EventBusSubscriber\b""").containsMatchIn(finalExecutable)) {
                text = removeImport(text, "net.neoforged.fml.common.EventBusSubscriber")
            }
            if (!Regex("""\bMod\s*\.""").containsMatchIn(finalExecutable)) {
                text = removeImport(text, "net.neoforged.fml.common.Mod")
            }

            if (mainChanged) {
                changes.add(Change(
                    file = mainFile,
                    line = 1,
                    description = "Register static mod-bus subscriber handlers on mod event bus",
                    before = "@EventBusSubscriber(bus = Bus.MOD)",
                    after = registrationEvidence.joinToString(" "),
                    confidence = Confidence.HIGH,
                    ruleId = "struct-static-modbus-subscriber-registration"
                ))
            }

            if (text != original) {
                changes.add(Change(
                    file = file,
                    line = firstChangedLine ?: 1,
                    description = "Remove deprecated static @EventBusSubscriber(bus=MOD) wiring",
                    before = "@EventBusSubscriber(bus = Bus.MOD)",
                    after = "constructor modEventBus.addListener(...)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-static-modbus-subscriber-cleanup"
                ))
                if (!dryRun) {
                    file.writeText(text)
                }
            }
        }

    if (!dryRun && registeredAny && mainText != mainFile.readText()) {
        mainFile.writeText(mainText)
    }

    return changes
}

private fun StructuralRefactorPass.isModBusSubscriberAnnotation(source: String, range: IntRange): Boolean {
    val annotation = maskJavaCommentsAndLiterals(source.substring(range.first, range.last + 1))
    return Regex(
        """\bbus\s*=\s*(?:(?:Mod\.)?EventBusSubscriber\.)?Bus\.MOD\b"""
    ).containsMatchIn(annotation)
}

private fun eventBusSubscriberAnnotationRemovalRange(source: String, annotationRange: IntRange): IntRange {
    val lineStart = source.lastIndexOf('\n', annotationRange.first - 1)
        .let { if (it < 0) 0 else it + 1 }
    val removalStart = if (source.substring(lineStart, annotationRange.first).isBlank()) {
        lineStart
    } else {
        annotationRange.first
    }
    var removalEnd = annotationRange.last + 1
    while (removalEnd < source.length && (source[removalEnd] == ' ' || source[removalEnd] == '\t')) {
        removalEnd++
    }
    if (removalEnd < source.length && source[removalEnd] == '\r') removalEnd++
    if (removalEnd < source.length && source[removalEnd] == '\n') removalEnd++
    return removalStart until removalEnd
}

private fun javaTypeNestingPath(
    owner: JavaTypeBlock,
    typeBlocks: List<JavaTypeBlock>
): List<JavaTypeBlock> =
    typeBlocks
        .filter { candidate ->
            candidate != owner &&
                candidate.bodyStart < owner.declarationStart &&
                candidate.end > owner.end
        }
        .sortedBy { it.bodyStart } + owner

private fun javaTypeBlockForNestingPath(
    path: List<String>,
    typeBlocks: List<JavaTypeBlock>
): JavaTypeBlock? = typeBlocks.singleOrNull { candidate ->
    javaTypeNestingPath(candidate, typeBlocks).map { it.name } == path
}

private fun javaAccessibleTypeReferenceExpression(
    sourcePackage: String,
    targetPackage: String,
    owner: JavaTypeBlock,
    typeBlocks: List<JavaTypeBlock>
): String? {
    val path = javaTypeNestingPath(owner, typeBlocks)
    val samePackage = sourcePackage == targetPackage
    val accessible = if (samePackage) {
        path.none { it.isPrivate }
    } else {
        path.all { it.isPublic }
    }
    if (!accessible) return null
    val typePath = path.joinToString(".") { it.name }
    return when {
        sourcePackage.isBlank() -> typePath
        path.all { it.isPublic } -> "$sourcePackage.$typePath"
        samePackage -> typePath
        else -> null
    }
}

private enum class StaticSubscriberDistribution {
    CLIENT,
    DEDICATED_SERVER
}

private fun staticModBusSubscriberDistributions(annotation: String): Set<StaticSubscriberDistribution>? {
    val value = Regex("""\bvalue\s*=\s*(\{[^}\r\n]*\}|[^,\r\n)]*)""")
        .find(annotation)
        ?.groupValues
        ?.get(1)
        ?: return StaticSubscriberDistribution.entries.toSet()
    val expressions = value
        .trim()
        .removePrefix("{")
        .removeSuffix("}")
        .split(',')
        .map { it.trim().replace(Regex("""\s+"""), "") }
        .filter { it.isNotEmpty() }
    if (expressions.isEmpty()) return null
    return expressions.mapTo(linkedSetOf()) { expression ->
        when (expression) {
            "Dist.CLIENT",
            "net.neoforged.api.distmarker.Dist.CLIENT" -> StaticSubscriberDistribution.CLIENT
            "Dist.DEDICATED_SERVER",
            "net.neoforged.api.distmarker.Dist.DEDICATED_SERVER" ->
                StaticSubscriberDistribution.DEDICATED_SERVER
            else -> return null
        }
    }
}

private fun staticModBusBridgeMethodName(
    distributions: Set<StaticSubscriberDistribution>
): String = when (distributions) {
    setOf(StaticSubscriberDistribution.CLIENT) -> "registerClientModBusListeners"
    setOf(StaticSubscriberDistribution.DEDICATED_SERVER) -> "registerDedicatedServerModBusListeners"
    StaticSubscriberDistribution.entries.toSet() -> "registerModBusListeners"
    else -> error("Unsupported static mod-bus subscriber distribution set: $distributions")
}

private fun staticModBusRegistration(
    indent: String,
    statement: String,
    distributions: Set<StaticSubscriberDistribution>
): String {
    val guard = distributions.singleOrNull()
    return if (guard == null) {
        "$indent$statement"
    } else {
        "${indent}if (net.neoforged.fml.loading.FMLLoader.getDist() == " +
            "net.neoforged.api.distmarker.Dist.${guard.name}) {\n" +
            "$indent    $statement\n" +
            "$indent}"
    }
}

private fun StructuralRefactorPass.addStaticModBusListenerBridge(
    source: String,
    bridgeOwnerPath: List<String>,
    methodName: String,
    listenerReferences: List<String>
): String {
    val executable = maskJavaCommentsAndLiterals(source)
    val typeBlocks = javaTypeBlocks(source, executable)
    val bridgeOwner = javaTypeBlockForNestingPath(bridgeOwnerPath, typeBlocks)
        ?: error("Cannot resolve mod-bus listener bridge owner '${bridgeOwnerPath.joinToString(".")}'")
    val methodPattern = Regex(
        """(?m)^([ \t]*)public\s+static\s+void\s+${Regex.escape(methodName)}\s*\(\s*net\.neoforged\.bus\.api\.IEventBus\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    val existingMethod = methodPattern.findAll(executable).singleOrNull { match ->
        javaTypeBlockContainingOffset(match.range.first, typeBlocks) == bridgeOwner
    }
    val separator = if (source.contains("\r\n")) "\r\n" else "\n"
    if (existingMethod != null) {
        val openBrace = executable.indexOf('{', existingMethod.range.first)
        val closeBrace = findMatchingBrace(executable, openBrace)
        if (openBrace < 0 || closeBrace < 0) {
            error("Cannot match generated mod-bus listener bridge '$methodName'")
        }
        val methodIndent = existingMethod.groupValues[1]
        val eventBusName = existingMethod.groupValues[2]
        val statementIndent = "$methodIndent    "
        val additions = listenerReferences
            .map { "$eventBusName.addListener($it);" }
            .filterNot { statement ->
                maskJavaCommentsAndLiterals(source.substring(openBrace + 1, closeBrace)).contains(statement)
            }
        if (additions.isEmpty()) return source
        val insertion = additions.joinToString(
            separator,
            prefix = separator,
            postfix = "$separator$methodIndent"
        ) { "$statementIndent$it" }
        return source.substring(0, closeBrace) + insertion + source.substring(closeBrace)
    }

    val conflictingMethod = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private)\s+)?(?:static\s+)?[A-Za-z_$][\w$<>,.? \[\]]*\s+${Regex.escape(methodName)}\s*\("""
    )
        .findAll(executable)
        .any { match -> javaTypeBlockContainingOffset(match.range.first, typeBlocks) == bridgeOwner }
    if (conflictingMethod) {
        error("Cannot generate mod-bus listener bridge '$methodName': method name already exists")
    }

    val declarationLineStart = source.lastIndexOf('\n', bridgeOwner.start)
        .let { if (it < 0) 0 else it + 1 }
    val ownerIndent = source.substring(declarationLineStart, bridgeOwner.start)
    val methodIndent = "$ownerIndent    "
    val statementIndent = "$methodIndent    "
    val method = buildString {
        append(separator)
        append(methodIndent)
        append("public static void $methodName(net.neoforged.bus.api.IEventBus modEventBus) {")
        listenerReferences.forEach { listener ->
            append(separator)
            append(statementIndent)
            append("modEventBus.addListener($listener);")
        }
        append(separator)
        append(methodIndent)
        append('}')
        append(separator)
        append(ownerIndent)
    }
    return source.substring(0, bridgeOwner.end) + method + source.substring(bridgeOwner.end)
}

internal fun StructuralRefactorPass.mainConstructorIndent(source: String, eventBusName: String): String {
    val localLine = Regex(
        """(?m)^([ \t]*)IEventBus\s+${Regex.escape(eventBusName)}\s*=\s*[^;]*(?:getEventBus|getModEventBus)\s*\(\s*\)\s*;"""
    )
        .find(source)
        ?.groupValues
        ?.get(1)
    if (localLine != null) return localLine
    val className = classNameOfJavaSource(source)
    if (className == null) return "        "
    return Regex("""(?m)^([ \t]*)(?:public|protected|private)?\s*${Regex.escape(className)}\s*\([^)]*\bIEventBus\s+${Regex.escape(eventBusName)}\b[^)]*\)\s*\{""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.plus("    ")
        ?: "        "
}

/**
 * Remove empty @EventBusSubscriber-annotated inner classes.
 * An inner class is "empty" if it has zero non-empty methods.
 * A method is "empty" if its body is just {} or contains only whitespace/comments.
 */
internal fun StructuralRefactorPass.removeEmptySubscriberClasses(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    // Pattern to find @EventBusSubscriber annotated inner classes and their full body
    val innerClassPattern = Regex(
        """([ \t]*@EventBusSubscriber\b[^\r\n]*\r?\n)([ \t]*public\s+static\s+class\s+(\w+)\s*\{)""",
        RegexOption.MULTILINE
    )

    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
        .forEach { file ->
            var text = file.readText()
            var removed = false

            // Find inner classes with @EventBusSubscriber repeatedly (text changes after each removal)
            var match = innerClassPattern.find(text)
            while (match != null) {
                val className = match.groupValues[3]
                val classOpenBrace = match.range.last
                val closeBrace = findMatchingBrace(text, classOpenBrace)
                if (closeBrace < 0) {
                    error("Cannot match @EventBusSubscriber inner class body for $className in $file")
                }

                // Extract the class body (between opening { and closing })
                val classBody = text.substring(classOpenBrace + 1, closeBrace)

                // Check if the class has any non-empty methods
                // A method signature: access modifiers + return type + name + params + body
                val methodPattern = Regex(
                    """(public|protected|private|static|\s)+\s+\w[\w<>,\s\[\]]*\s+\w+\s*\([^)]*\)\s*\{([^}]*)\}"""
                )
                val methods = methodPattern.findAll(classBody).toList()
                val hasNonEmptyMethod = methods.any { m ->
                    val body = m.groupValues[2]
                    // Non-empty if body has something other than whitespace and comments
                    val stripped = body
                        .replace(Regex("""//[^\r\n]*"""), "")      // remove line comments
                        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "") // remove block comments
                        .trim()
                    stripped.isNotEmpty()
                }

                if (!hasNonEmptyMethod) {
                    // Remove the entire inner class block including annotation and trailing newline
                    val startIdx = match.range.first
                    var endIdx = closeBrace + 1
                    // Consume trailing whitespace/newline
                    while (endIdx < text.length && (text[endIdx] == '\r' || text[endIdx] == '\n')) {
                        endIdx++
                    }
                    // Also remove a blank line before the annotation if present
                    var adjStart = startIdx
                    if (adjStart > 0 && text[adjStart - 1] == '\n') {
                        adjStart--
                        if (adjStart > 0 && text[adjStart - 1] == '\r') {
                            adjStart--
                        }
                    }
                    val previousLineBreak = text.lastIndexOf('\n', (adjStart - 1).coerceAtLeast(0))
                    val previousLineStart = if (previousLineBreak >= 0) previousLineBreak + 1 else 0
                    val previousLine = text.substring(previousLineStart, adjStart).trim()
                    if (previousLine.startsWith("//") && previousLine.contains("event handlers extracted", ignoreCase = true)) {
                        adjStart = previousLineStart
                    }

                    text = text.removeRange(adjStart, endIdx)
                    removed = true
                    logger.info { "Removing empty @EventBusSubscriber inner class: $className in ${file.fileName}" }

                    changes.add(Change(
                        file = file,
                        line = 0,
                        description = "Remove empty @EventBusSubscriber inner class '$className'",
                        before = "@EventBusSubscriber ... class $className { /* empty */ }",
                        after = "(removed)",
                        confidence = Confidence.HIGH,
                        ruleId = "struct-remove-empty-subscriber-class"
                    ))

                    // Restart search from the beginning since indices shifted
                    match = innerClassPattern.find(text)
                } else {
                    match = innerClassPattern.find(text, match.range.last + 1)
                }
            }

            if (removed && !dryRun) {
                file.writeText(text)
            }
        }

    return changes
}

/** The owner of a `name[...]` receiver declared as `Owner[] name` or `Owner name[]` in [executableCode]. */
internal fun StructuralRefactorPass.arrayElementReceiverOwner(receiver: String, executableCode: String, ownerAlternation: String): String? {
    if (ownerAlternation.isBlank()) return null
    val element = Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\s*\[[^\]]*\]$""").find(receiver.trim()) ?: return null
    val name = Regex.escape(element.groupValues[1])
    return Regex("""\b($ownerAlternation)\s*\[\s*\]\s+$name(?![\w$])|\b($ownerAlternation)\s+$name\s*\[\s*\]""")
        .find(executableCode)
        ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
}

/**
 * `RegistryFriendlyByteBuf` flows through the project's own signatures. A `FriendlyByteBuf`
 * parameter is declared `RegistryFriendlyByteBuf` when it is handed, as it is, to a project
 * constructor or method whose parameter at that position already is one, or when it belongs to an
 * override family (same name and arity across the project hierarchy) in which another member
 * already declares one. This repeats until nothing changes; 1.21.1 hands menus and payloads a
 * registry buffer, so the widened entry points stay satisfiable.
 */
internal fun StructuralRefactorPass.migrateRegistryBufferParameters(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val files = walkJavaSourceRoots(srcDir).filter { it.extension == "java" }.toList()
    val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        .filterValues { it.contains("FriendlyByteBuf") }
        .toMutableMap()
    if (sources.values.none { it.contains("RegistryFriendlyByteBuf") }) return emptyList()
    val index = com.modporter.core.transforms.util.JavaProjectIndex.build(files)
    val originals = sources.toMap()

    data class BufferCallable(val file: Path, val owner: String, val name: String, val range: IntRange, val parameters: List<Pair<String, String>>)
    data class FileCallables(val source: String, val masked: String, val callables: List<BufferCallable>)
    val abstractMethod = Regex("""(?m)^[ \t]*(?:(?:public|protected|private|abstract|static|final|synchronized)\s+)*abstract\s+[\w<>\[\].?, ]+?\s+([A-Za-z_$][\w$]*)\s*\(([^;{]*)\)\s*;""")
    fun parse(file: Path, source: String): FileCallables {
        val masked = maskJavaCommentsAndLiterals(source)
        val blocks = javaTypeBlocks(source, masked)
        fun ownerAt(offset: Int): String? = blocks.filter { offset in it.bodyStart..it.end }.minByOrNull { it.end - it.bodyStart }?.name
        // the parameter list follows the callable's name (an annotation before it may have its own parentheses)
        fun parameters(range: IntRange, name: String): List<Pair<String, String>> {
            val named = Regex("""(?<![\w$])${Regex.escape(name)}\s*\(""").find(masked.substring(range.first, range.last + 1))
            val open = named?.let { range.first + it.range.last } ?: masked.indexOf('(', range.first).takeIf { it >= 0 && it <= range.last } ?: return emptyList()
            val close = findMatchingParen(masked, open).takeIf { it >= 0 && it <= range.last } ?: return emptyList()
            return splitTopLevelJavaArgs(source.substring(open + 1, close))
                .mapNotNull { parseJavaParameter(it) }
                .map { (type, name) -> type.substringAfterLast('.').substringBefore('<') to name }
        }
        val methods = javaMethodRangesIncludingDefault(source).mapNotNull { method ->
            ownerAt(method.range.first)?.let { BufferCallable(file, it, method.name, method.range, parameters(method.range, method.name)) }
        }
        val abstracts = abstractMethod.findAll(masked).mapNotNull { match ->
            ownerAt(match.range.first)?.let { BufferCallable(file, it, match.groupValues[1], match.range, parameters(match.range, match.groupValues[1])) }
        }
        val constructors = blocks.flatMap { block ->
            javaConstructorRanges(source, block.name).map { constructor -> BufferCallable(file, block.name, "<init>", constructor.range, parameters(constructor.range, block.name)) }
        }
        return FileCallables(source, masked, (constructors + abstracts + methods).distinctBy { it.range.first })
    }
    fun ancestors(type: String): List<String> {
        val result = mutableListOf<String>()
        var current = index.declaration(type)?.directSuper
        while (current != null && current !in result) {
            result.add(current)
            current = index.declaration(current)?.directSuper
        }
        return result
    }
    fun related(a: String, b: String): Boolean = a == b || a in ancestors(b) || b in ancestors(a)
    // two overrides of one method: related, or both below a project type that declares it
    fun sameFamily(a: String, b: String, declares: (String) -> Boolean): Boolean =
        related(a, b) || (listOf(a) + ancestors(a)).intersect((listOf(b) + ancestors(b)).toSet()).any(declares)
    val call = Regex("""(?<![\w$])(?:(this|super|[A-Za-z_$][\w$]*)\s*\.\s*)?(?:(new)\s+)?([A-Za-z_$][\w$]*)\s*\(""")
    // declared field types of a project class (with type arguments), for `a.field.call(buffer)` and
    // `a.list.forEach(e -> e.call(buffer))`
    val fieldTypes = HashMap<String, Map<String, String>>()
    fun fieldsOf(type: String): Map<String, String> = fieldTypes.getOrPut(type) {
        val file = index.declaration(type)?.file ?: return@getOrPut emptyMap()
        val masked = maskJavaCommentsAndLiterals(sources[file] ?: runCatching { file.readText() }.getOrDefault(""))
        Regex("""(?m)^[ \t]*(?:(?:public|protected|private|static|final|transient|volatile)\s+)*([\w$.]+(?:<[^;=(){}]*>)?(?:\[\])*)\s+([A-Za-z_$][\w$]*)\s*[;=]""")
            .findAll(masked)
            .filter { it.groupValues[1] !in setOf("return", "throw", "new", "else", "case") }
            .groupBy({ it.groupValues[2] }, { it.groupValues[1] })
            .mapValues { it.value.first() }
    }
    fun fieldType(type: String, name: String): String? =
        (listOf(type) + ancestors(type)).firstNotNullOfOrNull { fieldsOf(it.substringBefore('<'))[name] }
    val collectionTypes = setOf("List", "ArrayList", "LinkedList", "Collection", "Set", "HashSet", "LinkedHashSet", "Iterable", "NonNullList", "Queue", "Deque")

    repeat(12) {
        val parsed = sources.mapValues { (file, source) -> parse(file, source) }
        val all = parsed.values.flatMap { it.callables }
        fun registryAt(owners: List<String>, name: String, position: Int, arity: Int): Boolean = all.any { candidate ->
            candidate.owner in owners && candidate.name == name && candidate.parameters.size == arity &&
                candidate.parameters[position].first == "RegistryFriendlyByteBuf"
        }
        fun inheritsRegistryBuffer(callable: BufferCallable, position: Int): Boolean =
            callable.name != "<init>" && all.any { member ->
                member !== callable && member.name == callable.name && member.parameters.size == callable.parameters.size &&
                    member.parameters[position].first == "RegistryFriendlyByteBuf" &&
                    sameFamily(member.owner, callable.owner) { type ->
                        all.any { it.owner == type && it.name == callable.name && it.parameters.size == callable.parameters.size }
                    }
            }
        fun forwardsRegistryBuffer(callable: BufferCallable, parameter: String, file: FileCallables): Boolean {
            val body = file.masked.substring(callable.range)
            val bodyStart = callable.range.first
            val locals = javaLocalVariableTypes(body)
            return call.findAll(body).any { match ->
                val open = match.range.last
                val close = findMatchingParen(body, open)
                if (close < 0) return@any false
                val args = splitTopLevelJavaArgs(file.source.substring(bodyStart + open + 1, bodyStart + close)).map { it.trim() }
                val position = args.indexOf(parameter)
                if (position < 0) return@any false
                val qualifier = match.groupValues[1]
                val name = match.groupValues[3]
                val owners: List<String>
                val target: String
                when {
                    match.groupValues[2] == "new" -> { owners = listOf(name); target = "<init>" }
                    name == "super" && qualifier.isEmpty() -> { owners = ancestors(callable.owner).take(1); target = "<init>" }
                    name == "this" && qualifier.isEmpty() -> { owners = listOf(callable.owner); target = "<init>" }
                    qualifier.isEmpty() || qualifier == "this" -> { owners = listOf(callable.owner) + ancestors(callable.owner); target = name }
                    qualifier == "super" -> { owners = ancestors(callable.owner); target = name }
                    else -> {
                        // only a plain name (or this.name) qualifier: a.b.call(...) belongs to a's type, not to a b in scope here
                        val qualifierStart = bodyStart + match.range.first
                        fun declaredType(variable: String, at: Int): String? =
                            locals[variable] ?: callable.parameters.firstOrNull { it.second == variable }?.first
                                ?: com.modporter.core.transforms.util.JavaScopedDeclarations.visibleAt(file.masked, variable, at)?.type
                                // Owner.call(...) through a project class
                                ?: variable.takeIf { it.first().isUpperCase() && index.declaration(it) != null }
                        // `a.b.c` read through the declared types of a and its fields
                        fun chainType(chain: String, at: Int): String? {
                            val segments = chain.split('.').map { it.trim() }.filter { it.isNotEmpty() }.dropWhile { it == "this" }
                            if (segments.isEmpty()) return null
                            // a field's own declaration keeps its type arguments (List<Output>)
                            val declared = declaredType(segments.first(), at)
                            val field = fieldType(callable.owner, segments.first())
                            var type = (if (field != null && (declared == null || declared == field.substringBefore('<').trim())) field else declared) ?: return null
                            for (segment in segments.drop(1)) type = fieldType(type.substringBefore('<').trim(), segment) ?: return null
                            return type
                        }
                        val before = file.masked.substring(maxOf(0, qualifierStart - 200), qualifierStart)
                        val chainedPrefix = Regex("""((?:[A-Za-z_$][\w$]*\s*\.\s*)+)$""").find(before)?.groupValues?.get(1)
                        val escaped = Regex.escape(qualifier)
                        val lambdaParameter = Regex("""(?<![\w$])$escaped\s*->|\([^()]*(?<![\w$])$escaped\s*\)\s*->""").containsMatchIn(body)
                        val type = when {
                            Regex("""\.\s*$""").containsMatchIn(before) -> {
                                // only a chain of plain names: `x().a.call(...)` has no declared type to read
                                val prefix = chainedPrefix ?: return@any false
                                if (Regex("""[.)\]>]\s*$""").containsMatchIn(before.removeSuffix(prefix))) return@any false
                                chainType(prefix.replace(Regex("""\s+"""), "") + qualifier, qualifierStart)
                            }
                            lambdaParameter -> {
                                // `collection.forEach(element -> element.call(buffer))`: the collection's element type
                                val forEach = Regex("""((?:[A-Za-z_$][\w$]*\s*\.\s*)*[A-Za-z_$][\w$]*)\s*\.\s*forEach\s*\(\s*\(?\s*$escaped\s*\)?\s*->""").find(body)
                                    ?: return@any false
                                val collection = chainType(forEach.groupValues[1].replace(Regex("""\s+"""), ""), bodyStart + forEach.range.first) ?: return@any false
                                Regex("""^(?:[\w$.]+\.)?(\w+)\s*<\s*([\w$.]+)\s*>$""").find(collection.trim())
                                    ?.takeIf { it.groupValues[1] in collectionTypes }
                                    ?.groupValues?.get(2)?.substringAfterLast('.')
                            }
                            else -> declaredType(qualifier, qualifierStart)
                        } ?: return@any false
                        owners = listOf(type.substringBefore('<').trim()) + ancestors(type.substringBefore('<').trim()); target = name
                    }
                }
                registryAt(owners, target, position, args.size)
            }
        }
        var widened = false
        for ((path, file) in parsed) {
            var updated = file.source
            var offset = 0
            for (callable in file.callables.sortedBy { it.range.first }) {
                for ((position, parameter) in callable.parameters.withIndex()) {
                    if (parameter.first != "FriendlyByteBuf") continue
                    if (!inheritsRegistryBuffer(callable, position) && !forwardsRegistryBuffer(callable, parameter.second, file)) continue
                    val nameAt = Regex("""(?<![\w$])${Regex.escape(if (callable.name == "<init>") callable.owner else callable.name)}\s*\(""")
                        .find(file.masked.substring(callable.range.first, callable.range.last + 1))
                    val open = nameAt?.let { callable.range.first + it.range.last }
                        ?: file.masked.indexOf('(', callable.range.first).takeIf { it >= 0 } ?: continue
                    val close = findMatchingParen(file.masked, open).takeIf { it >= 0 } ?: continue
                    val declaration = Regex("""\b(?:net\.minecraft\.network\.)?FriendlyByteBuf(\s+(?:final\s+)?${Regex.escape(parameter.second)})(?![\w$])""")
                    val header = file.source.substring(open, close + 1)
                    val replaced = declaration.replaceFirst(header, "RegistryFriendlyByteBuf$1")
                    if (replaced == header) continue
                    updated = updated.substring(0, open + offset) + replaced + updated.substring(close + 1 + offset)
                    offset += replaced.length - header.length
                    widened = true
                }
            }
            if (updated == file.source) continue
            updated = addImportIfMissing(updated, "net.minecraft.network.RegistryFriendlyByteBuf")
            val withoutFriendly = removeImport(updated, "net.minecraft.network.FriendlyByteBuf")
            if (!Regex("""\bFriendlyByteBuf\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutFriendly))) updated = withoutFriendly
            sources[path] = updated
        }
        if (!widened) return@repeat
    }

    val changes = mutableListOf<Change>()
    for ((file, updated) in sources) {
        val original = originals.getValue(file)
        if (updated == original) continue
        if (!dryRun) file.writeText(updated)
        changes += Change(file, 0, "Buffer parameters handed to registry-buffer signatures become RegistryFriendlyByteBuf",
            "FriendlyByteBuf parameter forwarded to a RegistryFriendlyByteBuf signature", "RegistryFriendlyByteBuf parameter", Confidence.HIGH, "struct-registry-buffer-parameters")
    }
    return changes
}

/**
 * Forge's `RecipeWrapper` was a full `Container` over an `IItemHandlerModifiable` (javap of
 * forge-1.20.1-47.1.33: getContainerSize, getItem, removeItem, setItem, removeItemNoUpdate,
 * isEmpty, canPlaceItem, clearContent, getMaxStackSize, setChanged, stillValid, startOpen,
 * stopOpen); NeoForge 21.1's is a read-only `RecipeInput` over an `IItemHandler` with `size()`
 * and `getItem(int)` only. A project subclass that still uses the container contract, because it
 * overrides container methods, writes through `inv.setStackInSlot`, or is filled through
 * `setItem`, declares `Container` and carries Forge's delegations itself. The handler is proven
 * modifiable from the subclass's own super calls, which must pass an `ItemStackHandler` or an
 * `IItemHandlerModifiable`-typed parameter; a subclass with another super argument is reported.
 */
internal fun StructuralRefactorPass.migrateRecipeWrapperSubclassContainers(projectDir: Path, dryRun: Boolean, errors: MutableList<String>): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val files = walkJavaSourceRoots(srcDir).filter { it.extension == "java" }.toList()
    val index = com.modporter.core.transforms.util.JavaProjectIndex.build(files)
    val subclasses = index.subtypesOf("RecipeWrapper").filter { it.directSuper == "RecipeWrapper" }
    if (subclasses.isEmpty()) return emptyList()
    val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
    val containerMethods = setOf("getContainerSize", "removeItem", "setItem", "removeItemNoUpdate", "isEmpty", "canPlaceItem",
        "clearContent", "getMaxStackSize", "setChanged", "stillValid", "startOpen", "stopOpen")
    val changes = mutableListOf<Change>()
    for (declaration in subclasses) {
        val source = sources.getValue(declaration.file)
        val masked = maskJavaCommentsAndLiterals(source)
        val block = javaTypeBlocks(source, masked).firstOrNull { it.name == declaration.name } ?: continue
        val body = masked.substring(block.bodyStart, block.end + 1)
        val constructors = javaConstructorRanges(source, declaration.name)
        val overrides = containerMethods.filter { name ->
            Regex("""@Override\s+(?:public|protected)\s+[\w<>\[\].]+\s+${Regex.escape(name)}\s*\(""").containsMatchIn(body)
        }.toSet()
        val writesHandler = body.contains("inv.setStackInSlot(")
        val filledElsewhere = sources.values.any { other ->
            val otherMasked = maskJavaCommentsAndLiterals(other)
            Regex("""\b${Regex.escape(declaration.name)}\s+([A-Za-z_$][\w$]*)\s*[=;,)]""").findAll(otherMasked)
                .any { variable -> Regex("""(?<![\w$])${Regex.escape(variable.groupValues[1])}\.(?:setItem|clearContent|removeItem|getContainerSize)\(""").containsMatchIn(otherMasked) }
        }
        if (overrides.isEmpty() && !writesHandler && !filledElsewhere) continue
        val superArguments = constructors.flatMap { constructor ->
            Regex("""\bsuper\s*\(([^;]*)\)\s*;""").findAll(masked.substring(constructor.range)).map { it.groupValues[1].trim() }.toList()
        }
        val parameters = constructors.flatMap { callableParameters(source, it.range) }
            .mapNotNull { parseJavaParameter(it) }.associate { (type, name) -> name to type.substringAfterLast('.') }
        val modifiable = superArguments.isNotEmpty() && superArguments.all { argument ->
            argument.startsWith("new ItemStackHandler(") || parameters[argument] in setOf("IItemHandlerModifiable", "ItemStackHandler")
        }
        if (!modifiable) {
            errors.add("Cannot keep RecipeWrapper subclass ${declaration.name} as a Container: its super argument is not a proven IItemHandlerModifiable")
            continue
        }
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val indent = source.substring(block.declarationStart.coerceAtLeast(0), block.bodyStart).takeWhile { it == ' ' || it == '\t' }.ifEmpty { "\t" }
        val inner = "$indent\t"
        val delegations = linkedMapOf(
            "getContainerSize" to "public int getContainerSize() {${newline}$inner\treturn inv.getSlots();${newline}$inner}",
            "removeItem" to "public ItemStack removeItem(int slot, int count) {${newline}$inner\treturn inv.extractItem(slot, count, false);${newline}$inner}",
            "setItem" to "public void setItem(int slot, ItemStack stack) {${newline}$inner\t((IItemHandlerModifiable) inv).setStackInSlot(slot, stack);${newline}$inner}",
            "removeItemNoUpdate" to "public ItemStack removeItemNoUpdate(int slot) {${newline}$inner\tItemStack stack = inv.getStackInSlot(slot);${newline}$inner\treturn stack.isEmpty() ? ItemStack.EMPTY : inv.extractItem(slot, stack.getCount(), false);${newline}$inner}",
            "isEmpty" to "public boolean isEmpty() {${newline}$inner\tfor (int slot = 0; slot < inv.getSlots(); slot++) {${newline}$inner\t\tif (!inv.getStackInSlot(slot).isEmpty()) return false;${newline}$inner\t}${newline}$inner\treturn true;${newline}$inner}",
            "canPlaceItem" to "public boolean canPlaceItem(int slot, ItemStack stack) {${newline}$inner\treturn inv.isItemValid(slot, stack);${newline}$inner}",
            "clearContent" to "public void clearContent() {${newline}$inner\tfor (int slot = 0; slot < inv.getSlots(); slot++) {${newline}$inner\t\t((IItemHandlerModifiable) inv).setStackInSlot(slot, ItemStack.EMPTY);${newline}$inner\t}${newline}$inner}",
            "getMaxStackSize" to "public int getMaxStackSize() {${newline}$inner\treturn 64;${newline}$inner}",
            "setChanged" to "public void setChanged() {${newline}$inner}",
            "stillValid" to "public boolean stillValid(Player player) {${newline}$inner\treturn true;${newline}$inner}",
            "startOpen" to "public void startOpen(Player player) {${newline}$inner}",
            "stopOpen" to "public void stopOpen(Player player) {${newline}$inner}"
        )
        val missing = delegations.filterKeys { it !in overrides }
        val methods = missing.values.joinToString("") { "$newline$inner@Override$newline$inner$it$newline" }
        var migrated = source.substring(0, block.end) + methods + source.substring(block.end)
        migrated = Regex("""(?<![\w$.])inv\.setStackInSlot\(""").replace(migrated) { "((IItemHandlerModifiable) inv).setStackInSlot(" }
        val header = Regex("""\bclass\s+${Regex.escape(declaration.name)}\b([^{]*)\{""")
        migrated = header.replace(migrated) { match ->
            val tail = match.groupValues[1]
            if (Regex("""\bimplements\b[^{]*\bContainer\b""").containsMatchIn(tail)) match.value
            else if (tail.contains("implements")) match.value.replaceFirst("implements", "implements Container,")
            else "class ${declaration.name}${tail.trimEnd()} implements Container {"
        }
        migrated = addImportIfMissing(migrated, "net.minecraft.world.Container")
        migrated = addImportIfMissing(migrated, "net.minecraft.world.item.ItemStack")
        migrated = addImportIfMissing(migrated, "net.neoforged.neoforge.items.IItemHandlerModifiable")
        if (missing.keys.any { it in setOf("stillValid", "startOpen", "stopOpen") }) migrated = addImportIfMissing(migrated, "net.minecraft.world.entity.player.Player")
        if (migrated == source) continue
        if (!dryRun) declaration.file.writeText(migrated)
        changes += Change(declaration.file, 0, "RecipeWrapper subclass ${declaration.name} keeps Forge's Container contract over its modifiable handler",
            "class ${declaration.name} extends RecipeWrapper", "implements Container with the delegations Forge's RecipeWrapper provided", Confidence.HIGH, "struct-recipe-wrapper-subclass-container")
    }
    return changes
}

/**
 * Forge's `IForgeItem#canBeDepleted()` is NeoForge 21.1's `IItemExtension#isDamageable(ItemStack)`
 * (javap of neoforge-21.1.86); an override in a class that extends an item type takes the stack.
 */
internal fun StructuralRefactorPass.migrateItemCanBeDepletedOverrides(source: String): String {
    if (!source.contains("canBeDepleted()")) return source
    val executable = maskJavaCommentsAndLiterals(source)
    if (!Regex("""\bclass\s+[A-Za-z_$][\w$]*[^{]*\bextends\s+[\w$.]*Item\b""").containsMatchIn(executable)) return source
    var result = replaceExecutableRegex(source, Regex("""\b(public|protected)\s+boolean\s+canBeDepleted\s*\(\s*\)""")) { match ->
        "${match.groupValues[1]} boolean isDamageable(ItemStack stack)"
    }
    result = replaceExecutableRegex(result, Regex("""\bsuper\.canBeDepleted\(\s*\)""")) { "super.isDamageable(stack)" }
    if (result != source) result = addImportIfMissing(result, "net.minecraft.world.item.ItemStack")
    return result
}

/**
 * Forge's `IGuiOverlay` (`render(ForgeGui, GuiGraphics, float partialTick, int width, int height)`)
 * is NeoForge 21.1's `LayeredDraw.Layer` (`render(GuiGraphics, DeltaTracker)`, member table of
 * 1.21.1), and `RegisterGuiLayersEvent` keys layers by `ResourceLocation` (javap of
 * neoforge-21.1.86). A `IGuiOverlay` field bound to a static method reference becomes a layer whose
 * method takes the layer signature and recovers the former parameters it still uses the way the
 * registration-lambda rule does; a registration with a string id gets the mod's resource location.
 */
internal fun StructuralRefactorPass.migrateGuiOverlayMethodReferences(projectDir: Path, modId: String, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    val field = Regex("""\bIGuiOverlay\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)::([A-Za-z_$][\w$]*)\s*;""")
    for (file in walkJavaSourceRoots(srcDir).filter { it.extension == "java" }) {
        val source = runCatching { file.readText() }.getOrDefault("")
        if (!source.contains("IGuiOverlay") && !source.contains("LayeredDraw") && !source.contains("registerAbove") && !source.contains("registerBelow")) continue
        var result = source
        val masked = maskJavaCommentsAndLiterals(result)
        val className = classNameOfJavaSource(masked)
        for (match in field.findAll(masked).toList().asReversed()) {
            val owner = match.groupValues[2]
            if (owner != className) continue
            val converted = layerRenderSignature(result, match.groupValues[3], static = true) ?: continue
            result = converted.replaceRange(match.range, "LayeredDraw.Layer ${match.groupValues[1]} = $owner::${match.groupValues[3]};")
        }
        // a class implementing the layer interface declares render with the layer signature
        if (Regex("""\bimplements\s+[^{]*\b(?:IGuiOverlay|LayeredDraw\.Layer)\b""").containsMatchIn(maskJavaCommentsAndLiterals(result))) {
            result = layerRenderSignature(result, "render", static = false) ?: result
            result = result.replace(Regex("""\bimplements(\s+[^{]*?)\bIGuiOverlay\b"""), "implements$1LayeredDraw.Layer")
        }
        if (result != source) {
            result = addImportIfMissing(result, "net.minecraft.client.DeltaTracker")
            result = addImportIfMissing(result, "net.minecraft.client.gui.LayeredDraw")
        }
        result = rewriteJavaInvocationArguments(result, "registerAbove") { args -> layerIdArguments(args, 1, modId) }
        result = rewriteJavaInvocationArguments(result, "registerBelow") { args -> layerIdArguments(args, 1, modId) }
        result = rewriteJavaInvocationArguments(result, "registerAboveAll") { args -> layerIdArguments(args, 0, modId) }
        result = rewriteJavaInvocationArguments(result, "registerBelowAll") { args -> layerIdArguments(args, 0, modId) }
        if (result == source) continue
        if (result.contains("ResourceLocation.fromNamespaceAndPath(")) result = addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
        if (!dryRun) file.writeText(result)
        changes += Change(file, 0, "Gui overlays are LayeredDraw layers keyed by resource location", "IGuiOverlay field / string layer id", "LayeredDraw.Layer / ResourceLocation id", Confidence.HIGH, "struct-gui-overlay-layers")
    }
    return changes
}

/**
 * The method [name] with Forge's overlay parameters `(Gui gui, GuiGraphics g, float partialTick,
 * int width, int height)` takes the layer signature `(GuiGraphics g, DeltaTracker deltaTracker)`
 * and recovers the former parameters its body still reads; null when no such method exists.
 */
internal fun StructuralRefactorPass.layerRenderSignature(source: String, name: String, static: Boolean): String? {
    val id = """[A-Za-z_$][\w$]*"""
    val masked = maskJavaCommentsAndLiterals(source)
    val modifiers = if (static) """(?:public|protected|private)\s+static""" else """(?:@Override\s+)?public"""
    val header = Regex(
        """(?m)^([ \t]*)($modifiers\s+void\s+${Regex.escape(name)})\s*\(\s*(?:net\.minecraft\.client\.gui\.)?(?:Forge|Extended)?Gui\s+($id)\s*,\s*GuiGraphics\s+($id)\s*,\s*float\s+($id)\s*,\s*int\s+($id)\s*,\s*int\s+($id)\s*\)\s*\{"""
    ).find(masked) ?: return null
    val close = findMatchingBrace(masked, header.range.last)
    if (close < 0) return null
    val body = masked.substring(header.range.last + 1, close)
    val indent = header.groupValues[1]
    val declaration = header.groupValues[2]
    val gui = header.groupValues[3]
    val graphics = header.groupValues[4]
    val partialTick = header.groupValues[5]
    val width = header.groupValues[6]
    val height = header.groupValues[7]
    fun used(variable: String) = Regex("""(?<![\w$])${Regex.escape(variable)}(?![\w$])""").containsMatchIn(body)
    val locals = mutableListOf<String>()
    if (used(gui)) locals += "net.minecraft.client.gui.Gui $gui = net.minecraft.client.Minecraft.getInstance().gui;"
    if (used(partialTick)) locals += "float $partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);"
    if (used(width)) locals += "int $width = $graphics.guiWidth();"
    if (used(height)) locals += "int $height = $graphics.guiHeight();"
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val bodyIndent = Regex("""\r?\n([ \t]+)\S""").find(body)?.groupValues?.get(1) ?: "$indent\t"
    val prologue = locals.joinToString("") { "$newline$bodyIndent$it" }
    return source.substring(0, header.range.first) + "$indent$declaration(GuiGraphics $graphics, DeltaTracker deltaTracker) {$prologue" + source.substring(header.range.last + 1)
}

/** A string literal at [position] of a layer registration becomes the mod's resource location. */
internal fun StructuralRefactorPass.layerIdArguments(args: List<String>, position: Int, modId: String): List<String>? {
    val literal = args.getOrNull(position)?.trim() ?: return null
    if (!Regex("""^"[a-z0-9_./-]+"$""").matches(literal)) return null
    val migrated = args.toMutableList()
    migrated[position] = "ResourceLocation.fromNamespaceAndPath(\"$modId\", $literal)"
    return migrated
}

/**
 * Forge's `IForgeBlockEntity.INFINITE_EXTENT_AABB` (javap of forge-1.20.1-47.1.33) is vanilla's
 * `AABB.INFINITE` in 1.21.1 (member table). The bare name is replaced only inside classes that
 * extend a block entity, where it resolved to the inherited Forge constant.
 */
internal fun StructuralRefactorPass.migrateInfiniteRenderBoundingBox(source: String, javaInheritanceIndex: JavaInheritanceIndex): String {
    if (!source.contains("INFINITE_EXTENT_AABB")) return source
    val masked = maskJavaCommentsAndLiterals(source)
    if (Regex("""\bINFINITE_EXTENT_AABB\s*=""").containsMatchIn(masked)) return source
    val edits = Regex("""(?<![\w$])(?:(?:net\.minecraftforge\.common\.extensions\.)?IForgeBlockEntity\.)?INFINITE_EXTENT_AABB(?![\w$])""").findAll(masked)
        .filter { match ->
            match.value.contains("IForgeBlockEntity.") ||
                javaClassExtendsAny(enclosingJavaClassDeclaration(source, match.range.first), javaBlockEntityBaseTypes(), javaInheritanceIndex)
        }
        .map { it.range }
        .toList()
    if (edits.isEmpty()) return source
    val result = edits.asReversed().fold(source) { text, range -> text.replaceRange(range, "AABB.INFINITE") }
    return addImportIfMissing(result, "net.minecraft.world.phys.AABB")
}

/**
 * `ChunkAccess#getStatus()` is `getPersistedStatus()` in 1.21.1 (member table). The call is renamed
 * on receivers declared as a vanilla chunk type and on `getChunk(...)` results, and an override in
 * a class extending a chunk type takes the new name; other `getStatus()` methods are left alone.
 */
internal fun StructuralRefactorPass.migrateChunkStatusAccessors(source: String, javaInheritanceIndex: JavaInheritanceIndex): String {
    if (!source.contains("getStatus()")) return source
    val chunkTypes = setOf("ChunkAccess", "LevelChunk", "ProtoChunk", "ImposterProtoChunk", "EmptyLevelChunk")
    val masked = maskJavaCommentsAndLiterals(source)
    val variables = Regex("""\b(?:net\.minecraft\.world\.level\.chunk\.)?(${chunkTypes.joinToString("|")})\s+([A-Za-z_$][\w$]*)\s*(?=[=;,)])""")
        .findAll(masked).map { it.groupValues[2] }.toSet() +
        Regex("""\binstanceof\s+(?:${chunkTypes.joinToString("|")})\s+([A-Za-z_$][\w$]*)\b""").findAll(masked).map { it.groupValues[1] }.toSet()
    val edits = mutableListOf<IntRange>()
    if (variables.isNotEmpty()) {
        Regex("""(?<![\w$.])(?:this\.)?(?:${variables.joinToString("|") { Regex.escape(it) }})\s*(\.\s*getStatus\(\))""").findAll(masked).forEach { edits += it.groups[1]!!.range }
    }
    Regex("""\.getChunk\(""").findAll(masked).forEach { call ->
        val close = findMatchingParen(masked, call.range.last)
        if (close < 0) return@forEach
        Regex("""^\s*\.\s*getStatus\(\)""").find(masked.substring(close + 1))?.let { edits += (close + 1 + it.range.first)..(close + 1 + it.range.last) }
    }
    Regex("""\bChunkStatus\s+getStatus\s*\(\s*\)""").findAll(masked).forEach { declaration ->
        if (javaClassExtendsAny(enclosingJavaClassDeclaration(source, declaration.range.first), chunkTypes, javaInheritanceIndex)) {
            val name = masked.indexOf("getStatus", declaration.range.first)
            edits += name until name + "getStatus".length
        }
    }
    return edits.distinct().sortedByDescending { it.first }.fold(source) { text, range ->
        val original = text.substring(range)
        text.replaceRange(range, if (original.startsWith("getStatus")) "getPersistedStatus" else original.replace("getStatus()", "getPersistedStatus()"))
    }
}

/**
 * 1.20.1's `AABB(BlockPos a, BlockPos b)` built the box from the two positions' raw coordinates
 * (bytecode: `this(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ())`); 1.21.1 has no
 * such constructor, and `AABB(Vec3, Vec3)` with `Vec3.atLowerCornerOf` is the same box
 * (`encapsulatingFullBlocks` would grow it). Both arguments must be proven `BlockPos`: a variable,
 * field or parameter declared so, a block entity's `worldPosition`/`getBlockPos()`, a vanilla
 * neighbour call on such a position, or a project method that every declaration types as `BlockPos`.
 */
internal fun StructuralRefactorPass.migrateBlockPosAabbConstructors(
    source: String,
    javaMethodReturnTypes: Map<String, String>,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains("new AABB(")) return source
    val masked = maskJavaCommentsAndLiterals(source)
    val id = """[A-Za-z_$][\w$]*"""
    val positions = Regex("""\b(?:net\.minecraft\.core\.)?(?:BlockPos|BlockPos\.MutableBlockPos)\s+($id)\s*(?=[=;,)])""")
        .findAll(masked).map { it.groupValues[1] }.toSet()
    val blockPosMethods = javaMethodReturnTypes.entries.groupBy({ it.key.substringAfterLast('.') }, { it.value })
        .filterValues { types -> types.isNotEmpty() && types.all { it == "BlockPos" } }.keys
    val neighbours = """(?:\s*\.\s*(?:above|below|north|south|east|west|relative|offset|immutable|atY)\s*\([^()]*\))*"""
    fun provenBlockPos(argument: String, offset: Int): Boolean {
        val arg = argument.trim()
        val inBlockEntity = javaClassExtendsAny(enclosingJavaClassDeclaration(source, offset), javaBlockEntityBaseTypes(), javaInheritanceIndex)
        val root = Regex("""^(?:this\.)?($id)$neighbours$""").find(arg)?.groupValues?.get(1)
        if (root != null && (root in positions || (inBlockEntity && root == "worldPosition"))) return true
        if (Regex("""^(?:(?:this|$id)\.)?getBlockPos\(\)$neighbours$""").matches(arg) && (inBlockEntity || !arg.startsWith("getBlockPos"))) return true
        // a call of a project method declared to return BlockPos, arguments nested to any depth
        val call = Regex("""^(?:$id\.)*($id)\(""").find(arg) ?: return false
        val close = findMatchingParen(arg, call.range.last)
        return close > 0 && call.groupValues[1] in blockPosMethods && Regex("""^$neighbours$""").matches(arg.substring(close + 1))
    }
    val edits = mutableListOf<Pair<IntRange, String>>()
    Regex("""\bnew\s+AABB\s*\(""").findAll(masked).forEach { match ->
        val close = findMatchingParen(masked, match.range.last)
        if (close < 0) return@forEach
        val args = splitTopLevelJavaArgs(source.substring(match.range.last + 1, close))
        if (args.size != 2 || !args.all { provenBlockPos(it, match.range.first) }) return@forEach
        edits += (match.range.last + 1 until close) to args.joinToString(", ") { "Vec3.atLowerCornerOf(${it.trim()})" }
    }
    if (edits.isEmpty()) return source
    val result = edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    return addImportIfMissing(result, "net.minecraft.world.phys.Vec3")
}

/**
 * 1.21.1's `LivingEntity#dropAllDeathLoot` takes the `ServerLevel` it drops into (member table of
 * 1.21.1); an override in a living entity subclass takes it too and hands it to its super call.
 */
internal fun StructuralRefactorPass.migrateLivingEntityDeathLootOverrides(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("dropAllDeathLoot")) return source
    val executableCode = maskJavaCommentsAndLiterals(source)
    val signature = Regex("""\b(public|protected)\s+void\s+dropAllDeathLoot\s*\(\s*DamageSource\s+([A-Za-z_$][\w$]*)\s*\)""")
    val edits = signature.findAll(executableCode).mapNotNull { match ->
        val classDeclaration = enclosingJavaClassDeclaration(executableCode, match.range.first)
        if (!javaClassExtendsAny(classDeclaration, javaLivingEntityBaseTypes(), javaInheritanceIndex)) return@mapNotNull null
        val open = executableCode.indexOf('{', match.range.last)
        val close = if (open < 0) -1 else findMatchingBrace(executableCode, open)
        if (close < 0) return@mapNotNull null
        val body = source.substring(open, close + 1)
        val level = listOf("serverLevel", "modporterLevel").first { !Regex("""(?<![\w$])$it(?![\w$])""").containsMatchIn(body) }
        val damageSource = match.groupValues[2]
        val migratedBody = Regex("""\bsuper\s*\.\s*dropAllDeathLoot\s*\(\s*${Regex.escape(damageSource)}\s*\)""")
            .replace(body) { "super.dropAllDeathLoot($level, $damageSource)" }
        (match.range.first..close) to "${match.groupValues[1]} void dropAllDeathLoot(ServerLevel $level, DamageSource $damageSource)" +
            source.substring(match.range.last + 1, open) + migratedBody
    }.toList()
    if (edits.isEmpty()) return source
    val result = edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    return addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
}

/** The type a parenthesised cast receiver such as `((Owner) expression)` is cast to, as a simple name. */
internal fun StructuralRefactorPass.castReceiverType(receiver: String): String? {
    if (!receiver.startsWith("(") || findMatchingParen(receiver, 0) != receiver.length - 1) return null
    val cast = Regex("""^\(\s*\(\s*((?:[A-Za-z_$][\w$]*\.)*([A-Z][\w$]*))\s*\)""").find(receiver) ?: return null
    return cast.groupValues[2]
}

/**
 * 1.20.1's `MinecraftServer#nextTickTime` (milliseconds) is `nextTickTimeNanos` in 1.21.1 (member
 * table): resetting it to "now" on a server-typed receiver resets the nanosecond field to
 * `Util.getNanos()`. Other uses are left as they are; the build adds the access transformer entry
 * for the new field when the source uses it.
 */
internal fun StructuralRefactorPass.migrateServerNextTickTime(source: String): String {
    if (!source.contains("nextTickTime")) return source
    val masked = maskJavaCommentsAndLiterals(source)
    val servers = Regex("""\b(?:MinecraftServer|DedicatedServer|IntegratedServer)\s+([A-Za-z_$][\w$]*)\s*[=;,)]""")
        .findAll(masked).map { it.groupValues[1] }.toSet()
    if (servers.isEmpty()) return source
    val names = servers.joinToString("|") { Regex.escape(it) }
    return replaceExecutableRegex(source, Regex("""(?<![\w$.])((?:this\.)?(?:$names))\.nextTickTime\s*=\s*(?:net\.minecraft\.)?Util\.getMillis\(\)""")) { match ->
        "${match.groupValues[1]}.nextTickTimeNanos = net.minecraft.Util.getNanos()"
    }
}

/**
 * `ClientboundBlockEntityDataPacket.create(BlockEntity, Function<BlockEntity, CompoundTag>)` takes a
 * `BiFunction<BlockEntity, RegistryAccess, CompoundTag>` in 1.21.1 (member table); a one-parameter
 * lambda gains the registry parameter it does not use.
 */
internal fun StructuralRefactorPass.migrateBlockEntityDataPacketTagLambdas(source: String): String {
    if (!source.contains("ClientboundBlockEntityDataPacket.create(")) return source
    return rewriteExecutableJavaCallWithOffset(source, "create") { receiver, args, _ ->
        if (receiver.substringAfterLast('.') != "ClientboundBlockEntityDataPacket" || args.size != 2) return@rewriteExecutableJavaCallWithOffset null
        val function = args[1].trim()
        val lambda = Regex("""^(?:\(\s*([A-Za-z_$][\w$]*)\s*\)|([A-Za-z_$][\w$]*))\s*->""").find(function) ?: return@rewriteExecutableJavaCallWithOffset null
        val parameter = lambda.groupValues[1].ifEmpty { lambda.groupValues[2] }
        val registries = listOf("registries", "modporterRegistries").first { !Regex("""(?<![\w$])$it(?![\w$])""").containsMatchIn(function) }
        "$receiver.create(${args[0].trim()}, ($parameter, $registries) ->${function.substring(lambda.range.last + 1)})"
    }
}

/**
 * Whether the expression starting near [expressionStart] of [executableCode] is used where a
 * `CompoundTag` is required: an argument of anything but a tag container's `put`/`add`/`set`
 * (which take any `Tag`), an initializer of a `CompoundTag` declaration, or a returned value. An
 * expression statement and a tag container argument accept the plain `Tag`.
 */
internal fun StructuralRefactorPass.compoundTagExpected(executableCode: String, expressionStart: Int): Boolean {
    var index = expressionStart.coerceIn(0, executableCode.length) - 1
    while (index >= 0 && executableCode[index].isWhitespace()) index--
    if (index < 0) return false
    return when (executableCode[index]) {
        ';', '{', '}' -> false
        '(', ',' -> {
            // the call this expression is an argument of
            var depth = 0
            var open = index
            while (open >= 0) {
                when (executableCode[open]) {
                    ')' -> depth++
                    '(' -> if (depth == 0) break else depth--
                }
                if (executableCode[open] == '(' && depth == 0) break
                open--
            }
            val callee = Regex("""([A-Za-z_$][\w$]*)\s*$""").find(executableCode.substring(0, open.coerceAtLeast(0)))?.groupValues?.get(1)
            callee !in setOf("put", "add", "set", "addTag", "append")
        }
        '=' -> Regex("""\bCompoundTag\s+[A-Za-z_$][\w$]*\s*$""").containsMatchIn(executableCode.substring(0, index))
        else -> Regex("""\breturn$""").containsMatchIn(executableCode.substring(0, index + 1))
    }
}

/**
 * 1.20.1's `LevelData#getXSpawn/getYSpawn/getZSpawn` are `LevelData#getSpawnPos()` in 1.21.1 (member
 * table); a value declared with a level data type reads its spawn coordinates from that position.
 */
internal fun StructuralRefactorPass.migrateLevelDataSpawnCoordinates(source: String): String {
    if (!Regex("""\.get[XYZ]Spawn\(\)""").containsMatchIn(source)) return source
    val names = Regex("""\b(?:LevelData|WritableLevelData|ServerLevelData|PrimaryLevelData)\s+([A-Za-z_$][\w$]*)\s*[=;,)]""")
        .findAll(maskJavaCommentsAndLiterals(source)).map { it.groupValues[1] }.toSet()
    if (names.isEmpty()) return source
    val alternation = names.joinToString("|") { Regex.escape(it) }
    return replaceExecutableRegex(source, Regex("""(?<![\w$.])($alternation)\.get([XYZ])Spawn\(\)""")) { match ->
        "${match.groupValues[1]}.getSpawnPos().get${match.groupValues[2]}()"
    }
}

/**
 * 1.20.1's chunk futures (`ServerChunkCache#getChunkFutureMainThread`, `ChunkHolder#getChunkFuture`)
 * completed with `Either<ChunkAccess, ChunkLoadingFailure>`; 1.21.1's complete with `ChunkResult`
 * (member table): in a lambda continuing such a future, `result.left().orElse(x)` is
 * `result.orElse(x)` and `result.left().isPresent()` is `result.isSuccess()`.
 */
internal fun StructuralRefactorPass.migrateChunkFutureResults(source: String): String {
    if (!source.contains("getChunkFuture")) return source
    val masked = maskJavaCommentsAndLiterals(source)
    val edits = mutableListOf<Pair<IntRange, String>>()
    Regex("""\.\s*(?:getChunkFutureMainThread|getChunkFuture)\s*\(""").findAll(masked).forEach { future ->
        val close = findMatchingParen(masked, future.range.last)
        if (close < 0) return@forEach
        val continuation = Regex("""^\s*\.\s*then(?:Apply|Accept|Compose)\s*\(\s*\(?\s*([A-Za-z_$][\w$]*)\s*\)?\s*->""").find(masked.substring(close + 1)) ?: return@forEach
        val lambdaStart = close + 1 + continuation.range.last + 1
        val lambdaEnd = findMatchingParen(masked, masked.indexOf('(', close + 1))
        if (lambdaEnd < 0) return@forEach
        val name = Regex.escape(continuation.groupValues[1])
        Regex("""(?<![\w$.])$name\s*\.\s*left\(\)\s*\.\s*(orElse|isPresent)\(""").findAll(masked.substring(lambdaStart, lambdaEnd)).forEach { use ->
            val range = lambdaStart + use.range.first..lambdaStart + use.range.last
            edits += range to if (use.groupValues[1] == "orElse") "${continuation.groupValues[1]}.orElse(" else "${continuation.groupValues[1]}.isSuccess("
        }
    }
    return edits.sortedByDescending { it.first.first }.fold(source) { text, (range, value) -> text.replaceRange(range, value) }
}

/**
 * Forge dispatched every tick event (server, level, player, client, render, both phases) to a
 * listener of the abstract `TickEvent`; NeoForge 1.21.1 has no common tick event. A listener that
 * never reads its event and only works on the running server (`ServerLifecycleHooks.getCurrentServer()`)
 * did its work once per server tick at most where it mattered, so it listens to `ServerTickEvent.Post`.
 */
internal fun StructuralRefactorPass.migrateServerOnlyTickListeners(source: String): String {
    if (!source.contains("TickEvent") || !source.contains("ServerLifecycleHooks.getCurrentServer()")) return source
    val masked = maskJavaCommentsAndLiterals(source)
    val listener = Regex("""@SubscribeEvent\s+(?:public\s+|protected\s+|private\s+)?(?:static\s+)?void\s+[A-Za-z_$][\w$]*\s*\(\s*((?:net\.(?:minecraftforge|neoforged\.neoforge)\.event\.)?TickEvent)\s+([A-Za-z_$][\w$]*)\s*\)\s*\{""")
    val edits = listener.findAll(masked).mapNotNull { match ->
        val open = match.range.last
        val close = findMatchingBrace(masked, open)
        if (close < 0) return@mapNotNull null
        val body = masked.substring(open + 1, close)
        val event = Regex.escape(match.groupValues[2])
        if (Regex("""(?<![\w$.])$event(?![\w$])""").containsMatchIn(body) || !body.contains("ServerLifecycleHooks.getCurrentServer()")) return@mapNotNull null
        match.groups[1]!!.range to "ServerTickEvent.Post"
    }.toList()
    if (edits.isEmpty()) return source
    val result = edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    return addImportIfMissing(result, "net.neoforged.neoforge.event.tick.ServerTickEvent")
}

/**
 * DataFixerUpper 8 (Minecraft 1.21.1) has no `DataFixerBuilder#buildUnoptimized()` or
 * `buildOptimized(Set, Executor)`: building returns a `Result` around a `DataFixerUpper` (javap of
 * datafixerupper 6.0.8 and 8.0.16). A subclass's overrides of those methods override nothing any
 * more; they stay as ordinary methods for their direct callers.
 */
internal fun StructuralRefactorPass.migrateDataFixerBuilderOverrides(source: String): String {
    if (!source.contains("DataFixerBuilder") || !source.contains("build")) return source
    val masked = maskJavaCommentsAndLiterals(source)
    if (!Regex("""\bextends\s+(?:com\.mojang\.datafixers\.)?DataFixerBuilder\b""").containsMatchIn(masked)) return source
    return replaceExecutableRegex(source, Regex("""@Override(\s+(?:public\s+)?(?:[\w.]+\.)?DataFixer\s+(?:buildUnoptimized\s*\(\s*\)|buildOptimized\s*\())""")) { match ->
        match.groupValues[1].trimStart()
    }
}
