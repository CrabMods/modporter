package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.*
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.*

// Structural migration rules, part 07 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateLegacyBowlFoodItemSubclasses, normalizeDuplicateHolderLookupConstructorArguments.

internal fun StructuralRefactorPass.migrateDeferredHolderGameEventArguments(
    source: String,
    gameEventHolderFields: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".gameEvent(") || !executableCode.contains(".get()") || gameEventHolderFields.isEmpty()) {
        return source
    }
    val localOwner = classNameOfJavaSource(source)
    val localGameEventFields = if (localOwner == null) {
        emptySet()
    } else {
        gameEventHolderFields.asSequence()
            .filter { it.substringBeforeLast('.', missingDelimiterValue = "") == localOwner }
            .map { it.substringAfterLast('.') }
            .toSet()
    }
    fun provenHolderExpression(expression: String): Boolean {
        val trimmed = expression.trim()
        return trimmed in gameEventHolderFields || trimmed in localGameEventFields
    }

    var anyChanged = false
    val migrated = rewriteExecutableJavaCall(source, "gameEvent") { receiver, args ->
        if (args.isEmpty()) return@rewriteExecutableJavaCall null
        var callChanged = false
        val rewrittenArgs = args.map { arg ->
            val trimmed = arg.trim()
            val getCall = Regex("""^((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)\.get\(\)$""")
                .find(trimmed)
                ?: return@map arg
            val holderExpression = getCall.groupValues[1]
            if (provenHolderExpression(holderExpression)) {
                callChanged = true
                anyChanged = true
                holderExpression
            } else {
                arg
            }
        }
        if (!callChanged) return@rewriteExecutableJavaCall null
        "$receiver.gameEvent(${rewrittenArgs.joinToString(", ") { it.trim() }})"
    }
    return if (anyChanged) migrated else source
}

internal fun StructuralRefactorPass.migrateLegacyCommonHooksToolChecks(source: String): String {
    if (!source.contains("CommonHooks.isCorrectToolForDrops(")) return source
    var result = Regex(
        """CommonHooks\.isCorrectToolForDrops\(\s*([^,\r\n]+)\s*,\s*([A-Za-z_$][\w$]*)\s*\)"""
    ).replace(source) { match ->
        val state = match.groupValues[1].trim()
        val player = match.groupValues[2]
        "$player.getMainHandItem().isCorrectToolForDrops($state)"
    }
    if (!result.contains("CommonHooks.")) {
        result = removeImport(result, "net.neoforged.neoforge.common.CommonHooks")
    }
    return result
}

internal fun StructuralRefactorPass.migrateWeightedEntryWrapperAccessors(source: String): String {
    if (!source.contains("WeightedEntry.Wrapper") ||
        (!source.contains(".getData()") && !source.contains("WeightedEntry.Wrapper::getData"))
    ) return source
    var result = source.replace(".getData()", ".data()")
    result = result.replace("WeightedEntry.Wrapper::getData", "WeightedEntry.Wrapper::data")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBlockStatePropertyGetCalls(source: String): String {
    if (!source.contains(".get(") || !source.contains("BlockState")) return source
    val stateVariables = Regex("""\b(?:net\.minecraft\.world\.level\.block\.state\.)?BlockState\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (stateVariables.isEmpty()) return source

    var result = source
    for (variable in stateVariables) {
        result = Regex("""\b${Regex.escape(variable)}\.get\s*\(""")
            .replace(result) { "$variable.getValue(" }
    }
    return result
}

internal fun StructuralRefactorPass.migrateJavaOptionalGetValueCalls(source: String): String {
    if (!source.contains(".getValue()")) return source
    var result = replaceExecutableRegex(
        source,
        Regex("""\.getSleepingPos\s*\(\s*\)\s*\.getValue\s*\(\s*\)""")
    ) { ".getSleepingPos().get()" }
    if (!result.contains("Optional")) return result
    val optionalVariables = Regex(
        """\b(?:java\.util\.)?Optional\s*<[^;\r\n=()]+>\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
    if (optionalVariables.isEmpty()) return result

    optionalVariables.forEach { variable ->
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(variable)}\.getValue\(\)""")
        ) { "$variable.get()" }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCauldronInteractionResults(source: String): String {
    if (!source.contains("CauldronInteraction") || !source.contains("InteractionResult")) return source
    var result = source
    val helperNames = linkedSetOf<String>()
    Regex(
        """CauldronInteraction\s+[A-Z0-9_$]+\s*=\s*\([^)]*\)\s*->\s*([A-Za-z_$][\w$]*)\s*\("""
    ).findAll(result).forEach { match ->
        helperNames += match.groupValues[1]
    }
    for (helper in helperNames) {
        result = Regex("""\b(private|protected|public)\s+static\s+InteractionResult\s+${Regex.escape(helper)}\s*\(""")
            .replace(result) { match -> "${match.groupValues[1]} static ItemInteractionResult $helper(" }
    }

    val hasCauldronBlockLambda = Regex(
        """CauldronInteraction\s+[A-Z0-9_$]+\s*=\s*\([^)]*\)\s*->\s*\{(?:[^{}]|\{[^{}]*})*InteractionResult\.sidedSuccess"""
    ).containsMatchIn(result)
    val migratedHelperReturn = helperNames.any { helper ->
        Regex("""\bItemInteractionResult\s+${Regex.escape(helper)}\s*\(""").containsMatchIn(result)
    }
    if (hasCauldronBlockLambda || migratedHelperReturn) {
        result = result.replace("InteractionResult.sidedSuccess(", "ItemInteractionResult.sidedSuccess(")
        result = addImportIfMissing(result, "net.minecraft.world.ItemInteractionResult")
        val withoutOldImport = removeImport(result, "net.minecraft.world.InteractionResult")
        if (!Regex("""\bInteractionResult\b""").containsMatchIn(withoutOldImport.replace("ItemInteractionResult", ""))) {
            result = withoutOldImport
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyRegistryBuilderAndRegistryAccessors(
    source: String,
    staticRegistryFieldHints: StaticRegistryFieldHints = StaticRegistryFieldHints(emptySet(), emptySet())
): String {
    if (!source.contains("RegistryBuilder") &&
        !source.contains(".getValues()") &&
        !source.contains(".getValue(") &&
        !source.contains(".getKey(") &&
        !source.contains(".allowModification()") &&
        !source.contains(".disableSync()") &&
        !source.contains(".getCodec()")
    ) {
        return source
    }
    var result = source
    val registryNamesUnwrappedFromSupplier = mutableSetOf<String>()
    result = Regex(
        """(?:java\.util\.function\.)?Supplier\s*<\s*Registry\s*<\s*([A-Za-z_$][\w$]*)\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]+?)\.makeRegistry\(\s*\(\s*\)\s*->\s*new\s+RegistryBuilder\s*<\s*\1\s*>\s*\([^)]*\)\s*((?:\s*\.[A-Za-z_$][\w$]*\s*\([^;()]*\))*)\s*\)"""
    ).replace(result) { match ->
        val type = match.groupValues[1]
        val registryName = match.groupValues[2]
        val registerExpression = match.groupValues[3].trim()
        val builderChain = match.groupValues[4]
            .replace(Regex("""\s*\.hasTags\(\)"""), "")
            .trim()
        registryNamesUnwrappedFromSupplier += registryName
        val builderConsumer = if (builderChain.isBlank()) {
            "builder -> {}"
        } else {
            "builder -> builder$builderChain"
        }
        "Registry<$type> $registryName = $registerExpression.makeRegistry($builderConsumer)"
    }
    result = Regex(
        """(?:java\.util\.function\.)?Supplier\s*<\s*Registry\s*<\s*([A-Za-z_$][\w$]*)\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]+?)\.makeRegistry\(\s*RegistryBuilder::new\s*\)"""
    ).replace(result) { match ->
        val type = match.groupValues[1]
        val registryName = match.groupValues[2]
        val registerExpression = match.groupValues[3].trim()
        registryNamesUnwrappedFromSupplier += registryName
        "Registry<$type> $registryName = $registerExpression.makeRegistry(builder -> {})"
    }
    for (registryName in registryNamesUnwrappedFromSupplier) {
        result = Regex("""\b((?:[A-Za-z_$][\w$]*\.)*${Regex.escape(registryName)})\.get\(\)""")
            .replace(result) { match -> match.groupValues[1] }
    }
    if (registryNamesUnwrappedFromSupplier.isNotEmpty() && !Regex("""\bSupplier\s*<""").containsMatchIn(result)) {
        result = removeImport(result, "java.util.function.Supplier")
    }
    val registrySupplierNames = Regex(
        """\b(?:java\.util\.function\.)?Supplier\s*<\s*Registry\s*<[^>\r\n]+>\s*>\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(result).map { it.groupValues[1] }.toSet()
    val registryVariableNames = Regex(
        """\bRegistry\s*<[^>\r\n]+>\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(result).map { it.groupValues[1] }.toSet()
    fun isKnownRegistryExpression(expression: String): Boolean {
        val trimmed = expression.trim()
        val registryName = trimmed.substringAfterLast(".")
        val registrySupplierName = if (trimmed.endsWith(".get()")) {
            trimmed.removeSuffix(".get()").substringAfterLast(".")
        } else {
            ""
        }
        return registryName in registryVariableNames ||
            registrySupplierName in registrySupplierNames ||
            trimmed.startsWith("BuiltInRegistries.") ||
            staticRegistryFieldHints.contains(trimmed)
    }
    val registryKeysByType = Regex(
        """ResourceKey<\s*Registry<\s*([A-Za-z_$][\w$]*)\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*="""
    ).findAll(result).associate { it.groupValues[1] to it.groupValues[2] }
    for ((type, key) in registryKeysByType) {
        result = result.replace("new RegistryBuilder<$type>()", "new RegistryBuilder<$type>($key)")
    }
    result = Regex("""\b([A-Za-z_$][\w$]*)\s*->\s*\1\.allowModification\(\)\.disableSync\(\)""")
        .replace(result) { match -> "${match.groupValues[1]} -> {}" }
    result = Regex("""\b([A-Za-z_$][\w$]*)\s*->\s*\1\.disableSync\(\)""")
        .replace(result) { match -> "${match.groupValues[1]} -> {}" }
    result = result.replace(".allowModification()", "")
    result = result.replace(".disableSync()", ".sync(false)")
    result = result.replace(".hasTags()", "")
    for (registryName in registryVariableNames) {
        result = Regex("""\b((?:[A-Za-z_$][\w$]*\.)*${Regex.escape(registryName)})\.getCodec\(\)""")
            .replace(result) { match -> "${match.groupValues[1]}.byNameCodec()" }
    }
    result = Regex("""\b(BuiltInRegistries\.[A-Z_]+)\.getCodec\(\)""")
        .replace(result) { match -> "${match.groupValues[1]}.byNameCodec()" }
    result = result.replace(".getValues().toArray(", ".stream().toArray(")
    result = rewriteJavaCall(result, "size") { receiver, args ->
        if (args.isNotEmpty() || !receiver.endsWith(".getValues()")) {
            null
        } else {
            val registryReceiver = receiver.removeSuffix(".getValues()")
            if (!isKnownRegistryExpression(registryReceiver)) {
                null
            } else {
                "(int) $registryReceiver.stream().count()"
            }
        }
    }
    result = rewriteJavaCall(result, "forEach") { receiver, args ->
        if (args.size != 1 || !receiver.endsWith(".getValues()")) {
            null
        } else {
            val registryReceiver = receiver.removeSuffix(".getValues()")
            if (!isKnownRegistryExpression(registryReceiver)) {
                null
            } else {
                "$registryReceiver.stream().forEach(${args[0].trim()})"
            }
        }
    }
    result = rewriteJavaCall(result, "getKey") { receiver, args ->
        if (args.size != 1 || !receiver.trim().endsWith(".get()")) {
            null
        } else {
            val registryReceiver = receiver.trim().removeSuffix(".get()")
            if (!isKnownRegistryExpression(registryReceiver)) {
                null
            } else {
                "$registryReceiver.getKey(${args[0].trim()})"
            }
        }
    }
    result = rewriteJavaCall(result, "getValue") { receiver, args ->
        if (args.size != 1) {
            null
        } else {
            val trimmedReceiver = receiver.trim()
            if (isKnownRegistryExpression(trimmedReceiver)) {
                "$trimmedReceiver.get(${args[0].trim()})"
            } else {
                null
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEyeHeightOverrides(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("getStandingEyeHeight(") && !source.contains("getEyeHeight(Pose")) return source
    var result = source
    var cursor = 0
    val signaturePattern = Regex(
        """(?m)(?:[ \t]*@\w+(?:\([^)]*\))?\s*\r?\n)*[ \t]*(?:public|protected)\s+float\s+(getStandingEyeHeight|getEyeHeight)\s*\(\s*Pose\s+([A-Za-z_$][\w$]*)(?:\s*,\s*EntityDimensions\s+([A-Za-z_$][\w$]*))?\s*\)\s*\{"""
    )
    while (true) {
        val match = signaturePattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val methodText = result.substring(match.range.first, closeBrace + 1)
        val returnMatch = Regex("""return\s+(.+?);""", RegexOption.DOT_MATCHES_ALL).find(methodText)
        if (returnMatch == null) {
            cursor = closeBrace + 1
            continue
        }
        val poseName = match.groupValues[2]
        val dimensionsName = match.groupValues.getOrNull(3).orEmpty()
        var eyeExpression = returnMatch.groupValues[1].trim()
        val classDeclaration = enclosingJavaClassDeclaration(result, match.range.first)
        val livingEntityTarget = match.groupValues[1] == "getStandingEyeHeight" ||
            javaClassExtendsAny(classDeclaration, javaLivingEntityBaseTypes(), javaInheritanceIndex)
        val targetMethodName = if (livingEntityTarget) "getDefaultDimensions" else "getDimensions"
        // LivingEntity declares getDefaultDimensions protected, but Player and many vanilla mobs widen it to
        // public (member table of 1.21.1); public overrides either
        val targetAccess = "public"
        val superDimensionsCall = "super.$targetMethodName($poseName)"
        if (dimensionsName.isNotBlank()) {
            eyeExpression = Regex("""\b${Regex.escape(dimensionsName)}\.height\(\)""")
                .replace(eyeExpression, "$superDimensionsCall.height()")
        }
        eyeExpression = eyeExpression
            .replace(Regex("""super\.getStandingEyeHeight\s*\(\s*${Regex.escape(poseName)}\s*,\s*[A-Za-z_$][\w$]*\s*\)"""), "$superDimensionsCall.eyeHeight()")
            .replace(Regex("""super\.getEyeHeight\s*\(\s*${Regex.escape(poseName)}\s*\)"""), "$superDimensionsCall.eyeHeight()")
            .replace(Regex("""this\.getBbHeight\s*\(\s*\)"""), "$superDimensionsCall.height()")

        val withoutLegacyMethod = result.removeRange(match.range.first, closeBrace + 1)
        val targetExistsOutsideMethod = Regex("""\b$targetMethodName\s*\(\s*Pose\s+""")
            .containsMatchIn(withoutLegacyMethod)
        if (targetExistsOutsideMethod) {
            val merged = mergeLegacyEyeHeightIntoExistingDimensionsMethod(
                withoutLegacyMethod,
                classDeclaration?.name,
                targetMethodName,
                poseName,
                dimensionsName,
                returnMatch.groupValues[1].trim()
            )
            if (merged != null) {
                result = merged
                cursor = match.range.first.coerceAtMost(result.length)
                continue
            }
            cursor = closeBrace + 1
            continue
        }

        val replacement = """
@Override
$targetAccess EntityDimensions $targetMethodName(Pose $poseName) {
		return $superDimensionsCall.withEyeHeight($eyeExpression);
	}
            """.trimIndent()
        result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        cursor = match.range.first + replacement.length
    }
    return if (result != source) addImportIfMissing(result, "net.minecraft.world.entity.EntityDimensions") else result
}

internal fun StructuralRefactorPass.mergeLegacyEyeHeightIntoExistingDimensionsMethod(
    source: String,
    className: String?,
    targetMethodName: String,
    legacyPoseName: String,
    legacyDimensionsName: String,
    legacyEyeExpression: String
): String? {
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:public|protected)\s+EntityDimensions\s+$targetMethodName\s*\(\s*Pose\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    for (match in methodPattern.findAll(source)) {
        val targetClass = enclosingJavaClassDeclaration(source, match.range.first)
        if (className != null && targetClass?.name != className) continue
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) continue
        val methodText = source.substring(match.range.first, closeBrace + 1)
        val returnMatches = Regex("""return\s+(.+?);""", RegexOption.DOT_MATCHES_ALL)
            .findAll(methodText)
            .toList()
        if (returnMatches.size != 1) {
            val rewrittenMultiReturnMethod = rewriteDimensionsMethodReturnsWithLegacyEyeHeight(
                methodText,
                targetPoseName = match.groupValues[1],
                legacyPoseName = legacyPoseName,
                legacyEyeExpression = legacyEyeExpression
            ) ?: continue
            return source.substring(0, match.range.first) +
                rewrittenMultiReturnMethod +
                source.substring(closeBrace + 1)
        }
        val returnMatch = returnMatches.single()
        val returnedDimensions = returnMatch.groupValues[1].trim()
        val localName = uniqueLocalNameInScope(methodText, "dimensions")
        val targetPoseName = match.groupValues[1]
        var eyeExpression = legacyEyeExpression
        if (legacyPoseName != targetPoseName) {
            eyeExpression = Regex("""\b${Regex.escape(legacyPoseName)}\b""").replace(eyeExpression, targetPoseName)
        }
        if (legacyDimensionsName.isNotBlank()) {
            eyeExpression = Regex("""\b${Regex.escape(legacyDimensionsName)}\.height\(\)""")
                .replace(eyeExpression, "$localName.height()")
        }
        eyeExpression = eyeExpression
            .replace(Regex("""super\.getStandingEyeHeight\s*\(\s*${Regex.escape(targetPoseName)}\s*,\s*[A-Za-z_$][\w$]*\s*\)"""), "$localName.eyeHeight()")
            .replace(Regex("""super\.getEyeHeight\s*\(\s*${Regex.escape(targetPoseName)}\s*\)"""), "$localName.eyeHeight()")
            .replace(Regex("""this\.getBbHeight\s*\(\s*\)"""), "$localName.height()")

        val returnStart = returnMatch.range.first
        val lineStart = methodText.lastIndexOf('\n', returnStart).let { if (it < 0) 0 else it + 1 }
        val indent = methodText.substring(lineStart, returnStart).takeWhile { it == ' ' || it == '\t' }
        val rewrittenReturn = "${indent}EntityDimensions $localName = $returnedDimensions;\n" +
            "${indent}return $localName.withEyeHeight($eyeExpression);"
        val rewrittenMethod = methodText.replaceRange(returnMatch.range, rewrittenReturn)
        return source.substring(0, match.range.first) +
            rewrittenMethod +
            source.substring(closeBrace + 1)
    }
    return null
}

internal fun StructuralRefactorPass.rewriteDimensionsMethodReturnsWithLegacyEyeHeight(
    methodText: String,
    targetPoseName: String,
    legacyPoseName: String,
    legacyEyeExpression: String
): String? {
    var eyeExpression = legacyEyeExpression
    if (legacyPoseName != targetPoseName) {
        eyeExpression = Regex("""\b${Regex.escape(legacyPoseName)}\b""").replace(eyeExpression, targetPoseName)
    }
    if (Regex("""\bsuper\.(?:getStandingEyeHeight|getEyeHeight)\s*\(""").containsMatchIn(eyeExpression) ||
        Regex("""\b[A-Za-z_$][\w$]*\.height\(\)""").containsMatchIn(eyeExpression) ||
        eyeExpression.contains("this.getBbHeight()")) {
        return null
    }

    var changed = false
    val rewritten = Regex("""return\s+(.+?);""", RegexOption.DOT_MATCHES_ALL).replace(methodText) { match ->
        val returnedDimensions = match.groupValues[1].trim()
        if (returnedDimensions.contains(".withEyeHeight(")) {
            match.value
        } else {
            changed = true
            "return ($returnedDimensions).withEyeHeight($eyeExpression);"
        }
    }
    return if (changed) rewritten else null
}

internal fun StructuralRefactorPass.migrateLegacyNbtUtilsBlockPosCompoundSource(source: String): String {
    // 1.20.5 changed NbtUtils.readBlockPos/writeBlockPos from an {X,Y,Z} compound to an int
    // array under a key; the mod's saved data keeps the compound layout through private helpers,
    // whether the pair is called directly or passed as a method reference.
    val executable = maskJavaCommentsAndLiterals(source)
    if (!Regex("""(?m)^[ \t]*import\s+net\.minecraft\.nbt\.NbtUtils\s*;[ \t]*$""").containsMatchIn(executable)) {
        return source
    }
    if (!Regex("""\bNbtUtils(?:\.|::)(?:read|write)BlockPos\b""").containsMatchIn(executable)) return source

    var migrated = replaceNbtUtilsBlockPosCall(source, "readBlockPos", "modporterReadLegacyBlockPos")
    migrated = replaceNbtUtilsBlockPosCall(migrated, "writeBlockPos", "modporterWriteLegacyBlockPos")
    javaTopLevelTypeName(source)?.let { topLevel ->
        migrated = replaceExecutableRegex(migrated, Regex("""\bNbtUtils::readBlockPos\b""")) { "$topLevel::modporterReadLegacyBlockPos" }
        migrated = replaceExecutableRegex(migrated, Regex("""\bNbtUtils::writeBlockPos\b""")) { "$topLevel::modporterWriteLegacyBlockPos" }
    }
    if (migrated == source) return source

    var result = addLegacyBlockPosNbtHelpers(
        migrated,
        needsReadHelper = migrated.contains("modporterReadLegacyBlockPos"),
        needsWriteHelper = migrated.contains("modporterWriteLegacyBlockPos")
    )
    result = addImportIfMissing(result, "net.minecraft.core.BlockPos")
    if (!Regex("""(?m)^[ \t]*import\s+net\.minecraft\.nbt\.\*;\s*$""").containsMatchIn(result)) {
        result = addImportIfMissing(result, "net.minecraft.nbt.CompoundTag")
    }
    val withoutNbtUtilsImport = removeImport(result, "net.minecraft.nbt.NbtUtils")
    if (!Regex("""\bNbtUtils\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutNbtUtilsImport))) {
        result = withoutNbtUtilsImport
    }
    return result
}

internal fun StructuralRefactorPass.replaceNbtUtilsBlockPosCall(source: String, methodName: String, replacementName: String): String {
    val token = "NbtUtils.$methodName("
    val executable = maskJavaCommentsAndLiterals(source)
    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    while (cursor < source.length) {
        val tokenIndex = executable.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + "NbtUtils.$methodName".length
        val closeParen = findMatchingParen(executable, openParen)
        if (closeParen < 0) {
            break
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size != 1) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }
        migrated.append(source, cursor, tokenIndex)
        migrated.append("$replacementName(${args[0].trim()})")
        cursor = closeParen + 1
        changed = true
    }
    if (!changed) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.addLegacyBlockPosNbtHelpers(source: String, needsReadHelper: Boolean = true, needsWriteHelper: Boolean): String {
    val readHelper = if (needsReadHelper && !source.contains("modporterReadLegacyBlockPos(CompoundTag tag)")) """

    private static BlockPos modporterReadLegacyBlockPos(CompoundTag tag) {
        return new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
    }
""".trimEnd() else ""
    val writeHelper = if (needsWriteHelper && !source.contains("modporterWriteLegacyBlockPos(BlockPos pos)")) """

    private static CompoundTag modporterWriteLegacyBlockPos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
    }
""".trimEnd() else ""
    if (readHelper.isEmpty() && writeHelper.isEmpty()) return source
    return insertBeforeLastClassBrace(source, readHelper + writeHelper)
}

internal fun StructuralRefactorPass.migrateLegacySavedDataApis(
    source: String,
    savedDataClassNames: Set<String>,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains("SavedData") &&
        !source.contains("computeIfAbsent(") &&
        savedDataClassNames.none { source.contains("$it.load(") }
    ) return source

    val localSavedDataClasses = collectJavaClassDeclarations(source)
        .filter { declaration -> javaClassExtendsAny(declaration, setOf("SavedData"), javaInheritanceIndex) }
        .map { it.name }
        .toSet()
    val knownSavedDataClasses = savedDataClassNames + localSavedDataClasses
    if (knownSavedDataClasses.isEmpty()) return source

    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var needsHolderLookup = false
    var needsSavedData = false

    localSavedDataClasses.forEach { className ->
        result = Regex("""public\s+static\s+${Regex.escape(className)}\s+load\s*\(\s*CompoundTag\s+($id)\s*\)""")
            .replace(result) { match ->
                needsHolderLookup = true
                "public static $className load(CompoundTag ${match.groupValues[1]}, HolderLookup.Provider registries)"
            }
        val constructorPattern = Regex("""public\s+${Regex.escape(className)}\s*\(\s*CompoundTag\s+($id)\s*\)\s*\{""")
        val constructorMatch = constructorPattern.find(result)
        if (constructorMatch != null &&
            !Regex("""public\s+${Regex.escape(className)}\s*\(\s*CompoundTag\s+$id\s*,\s*HolderLookup\.Provider\s+$id\s*\)""")
                .containsMatchIn(result)
        ) {
            val openBrace = result.indexOf('{', constructorMatch.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (openBrace >= 0 && closeBrace >= 0) {
                val tagName = constructorMatch.groupValues[1]
                val overload = """

    public $className(CompoundTag $tagName, HolderLookup.Provider registries) {
        this($tagName);
    }
""".trimEnd()
                result = result.substring(0, closeBrace + 1) + overload + result.substring(closeBrace + 1)
                needsHolderLookup = true
            }
        }
    }

    if (localSavedDataClasses.isNotEmpty()) {
        val executableBeforeSaveMigration = maskJavaCommentsAndLiterals(result)
        val typeBlocksBeforeSaveMigration = javaTypeBlocks(result, executableBeforeSaveMigration)
        result = replaceExecutableRegex(result, Regex("""public\s+CompoundTag\s+save\s*\(\s*CompoundTag\s+($id)\s*\)""")) { match ->
            val owner = typeBlocksBeforeSaveMigration
                .lastOrNull { match.range.first in it.bodyStart..it.end }
                ?.name
            if (owner in localSavedDataClasses) {
                needsHolderLookup = true
                "public CompoundTag save(CompoundTag ${match.groupValues[1]}, HolderLookup.Provider registries)"
            } else {
                match.value
            }
        }
    }

    result = rewriteJavaCall(result, "computeIfAbsent") { receiver, args ->
        if (args.size != 3) return@rewriteJavaCall null
        val loadReference = parseSimpleMethodReference(args[0]) ?: return@rewriteJavaCall null
        val supplierReference = parseSimpleMethodReference(args[1]) ?: return@rewriteJavaCall null
        if (loadReference.owner != supplierReference.owner) return@rewriteJavaCall null
        val validLoadMember = loadReference.member == "load" ||
            (loadReference.member == "new" && supplierReference.member == "new")
        if (!validLoadMember) return@rewriteJavaCall null
        if (loadReference.owner.substringAfterLast('.') !in knownSavedDataClasses) return@rewriteJavaCall null

        needsSavedData = true
        "$receiver.computeIfAbsent(new SavedData.Factory<>(${supplierReference.source}, ${loadReference.source}), ${args[2].trim()})"
    }

    knownSavedDataClasses.forEach { savedDataClass ->
        result = rewriteExecutableJavaCallWithOffset(result, "load") { receiver, args, offset ->
            if (receiver.substringAfterLast('.') != savedDataClass || args.size != 1) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val registries = registryAccessExpressionAt(result, offset, javaInheritanceIndex)
                ?: return@rewriteExecutableJavaCallWithOffset null
            "$receiver.load(${args[0].trim()}, $registries)"
        }
    }

    if (needsHolderLookup) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    if (needsSavedData) {
        result = addImportIfMissing(result, "net.minecraft.world.level.saveddata.SavedData")
    }
    return result
}

internal data class SimpleMethodReference(
    val owner: String,
    val member: String,
    val source: String
)

internal fun StructuralRefactorPass.parseSimpleMethodReference(expression: String): SimpleMethodReference? {
    val match = Regex("""^\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*::\s*([A-Za-z_$][\w$]*|new)\s*$""")
        .find(expression)
        ?: return null
    return SimpleMethodReference(
        owner = match.groupValues[1],
        member = match.groupValues[2],
        source = "${match.groupValues[1]}::${match.groupValues[2]}"
    )
}

internal fun StructuralRefactorPass.migrateDefaultLootTableResourceKeys(source: String): String {
    if (!source.contains("getDefaultLootTable()") || !source.contains("ResourceLocation")) return source
    var result = Regex("""\b(public|protected)\s+ResourceLocation\s+getDefaultLootTable\s*\(\s*\)""")
        .replace(source) { match -> "${match.groupValues[1]} ResourceKey<LootTable> getDefaultLootTable()" }
    if (result != source) {
        result = addImportIfMissing(result, "net.minecraft.resources.ResourceKey")
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.LootTable")
        val withoutResourceLocation = removeImport(result, "net.minecraft.resources.ResourceLocation")
        if (!Regex("""\bResourceLocation\b""").containsMatchIn(withoutResourceLocation)) {
            result = withoutResourceLocation
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateRandomizableContainerLootTableCalls(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("setLootTable(")) return source
    if (!executableCode.contains("RandomizableContainerBlockEntity.setLootTable(") &&
        !executableCode.contains(".setLootTable(")) return source
    var result = rewriteExecutableJavaCall(source, "setLootTable") { receiver, args ->
        if (receiver != "RandomizableContainerBlockEntity" || args.size != 4) return@rewriteExecutableJavaCall null
        "RandomizableContainer.setBlockEntityLootTable(${args.joinToString(", ") { it.trim() }})"
    }
    var needsResourceKey = false
    var needsRegistries = false
    var needsLootTable = false
    result = rewriteExecutableJavaCallWithOffset(result, "setLootTable") { receiver, args, offset ->
        if (args.size != 2) return@rewriteExecutableJavaCallWithOffset null
        val receiverName = receiver.trim().takeIf { Regex("""[A-Za-z_$][\w$]*""").matches(it) }
            ?: return@rewriteExecutableJavaCallWithOffset null
        val receiverTypes = javaDeclaredSimpleTypeSets(result)[receiverName].orEmpty()
        val isBrushableBlockEntity = receiverTypes.any { type -> type == "BrushableBlockEntity" }
        val isRandomizableContainer = receiverTypes.any { type ->
            type == "RandomizableContainerBlockEntity" ||
                javaInheritanceIndex.inherits(type, setOf("RandomizableContainerBlockEntity"))
        }
        val lootTable = args[0].trim()
        if (isBrushableBlockEntity) {
            if (lootTable.startsWith("ResourceKey.create(")) return@rewriteExecutableJavaCallWithOffset null
            needsResourceKey = true
            needsRegistries = true
            needsLootTable = true
            return@rewriteExecutableJavaCallWithOffset "$receiver.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, $lootTable), ${args[1].trim()})"
        }
        if (!isRandomizableContainer) return@rewriteExecutableJavaCallWithOffset null
        val lootTableResourceKey = unwrapLootTableResourceKeyCreate(lootTable)
            ?.takeIf { isLocalLootTableResourceKeyExpression(result, it) }
            ?: lootTable.takeIf { isLocalLootTableResourceKeyExpression(result, it) }
        if (lootTableResourceKey != null) {
            val lineStart = result.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
            val indent = result.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
            return@rewriteExecutableJavaCallWithOffset "$receiver.setLootTable($lootTableResourceKey);\n$indent$receiver.setLootTableSeed(${args[1].trim()})"
        }
        if (lootTable.startsWith("ResourceKey.create(")) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        val lineStart = result.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
        val indent = result.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
        needsResourceKey = true
        needsRegistries = true
        needsLootTable = true
        "$receiver.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, $lootTable));\n$indent$receiver.setLootTableSeed(${args[1].trim()})"
    }
    if (result != source) {
        if (result.contains("RandomizableContainer.setBlockEntityLootTable(")) {
            result = addImportIfMissing(result, "net.minecraft.world.RandomizableContainer")
        }
        if (needsResourceKey) result = addImportIfMissing(result, "net.minecraft.resources.ResourceKey")
        if (needsRegistries) result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
        if (needsLootTable) result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.LootTable")
        val withoutBlockEntityImport = removeImport(result, "net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity")
        if (!Regex("""\bRandomizableContainerBlockEntity\b""")
                .containsMatchIn(maskJavaCommentsAndLiterals(withoutBlockEntityImport))) {
            result = withoutBlockEntityImport
        }
    }
    result = simplifyLootTableResourceKeyCreates(result)
    result = blockifySplitLootTableSeedCalls(result)
    return result
}

internal fun StructuralRefactorPass.simplifyLootTableResourceKeyCreates(source: String): String =
    replaceExecutableRegex(
        source,
        Regex("""ResourceKey\.create\(\s*Registries\.LOOT_TABLE\s*,\s*([^)]+?)\s*\)""")
    ) { match ->
        val expression = match.groupValues[1].trim()
        if (isLocalLootTableResourceKeyExpression(source, expression)) expression else match.value
    }

internal fun StructuralRefactorPass.blockifySplitLootTableSeedCalls(source: String): String =
    Regex("""(?m)^([ \t]*)if\s*\(([^\r\n{}]+)\)\s*\r?\n([ \t]+)([A-Za-z_$][\w$]*)\.setLootTable\(([^;\r\n]+)\);\s*\r?\n\3\4\.setLootTableSeed\(([^;\r\n]+)\);\s*$""")
        .replace(source) { match ->
            val indent = match.groupValues[1]
            val condition = match.groupValues[2]
            val bodyIndent = match.groupValues[3]
            val receiver = match.groupValues[4]
            val lootTable = match.groupValues[5]
            val seed = match.groupValues[6]
            "$indent" + "if ($condition) {\n" +
                "$bodyIndent$receiver.setLootTable($lootTable);\n" +
                "$bodyIndent$receiver.setLootTableSeed($seed);\n" +
                "$indent}"
        }

internal fun StructuralRefactorPass.migrateStructureStartLoadFromTagCalls(
    source: String,
    loadableStructureStarts: Set<String> = emptySet()
): String {
    val sourceLocalLoadableStarts = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+(?:[A-Za-z_$][\w$]*\.)?StructureStart\b""")
        .findAll(maskJavaCommentsAndLiterals(source))
        .mapNotNull { match ->
            val className = match.groupValues[1]
            className.takeIf {
                Regex("""\bvoid\s+load\s*\(\s*(?:net\.minecraft\.nbt\.)?CompoundTag\s+[A-Za-z_$][\w$]*\s*\)""")
                    .containsMatchIn(maskJavaCommentsAndLiterals(source))
            }
        }
        .toSet()
    val loadableTypes = loadableStructureStarts + sourceLocalLoadableStarts
    if (loadableTypes.isEmpty() || !source.contains(".loadFromTag(")) return source
    var result = source
    for (type in loadableTypes) {
        val variables = Regex("""\b(?:${Regex.escape(type)}|(?:[A-Za-z_$][\w$]*\.)+${Regex.escape(type)})\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(maskJavaCommentsAndLiterals(result))
            .map { it.groupValues[1] }
            .toSet()
        for (variable in variables) {
            result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(variable)}\.loadFromTag\s*\(""")) {
                "$variable.load("
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateItemEntityThrowerEntitySource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".setThrower(") || !executableCode.contains(".getUUID()")) return source
    return rewriteExecutableJavaCall(source, "setThrower") { receiver, args ->
        if (receiver.isBlank() || args.size != 1) return@rewriteExecutableJavaCall null
        val entity = Regex("""^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*)\.getUUID\(\)$""")
            .find(args[0].trim())
            ?.groupValues
            ?.get(1)
            ?: return@rewriteExecutableJavaCall null
        "$receiver.setThrower($entity)"
    }
}

internal fun StructuralRefactorPass.migrateLegacyBucketPickupAndLiquidContainerCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("pickupBlock(") && !executableCode.contains("canPlaceLiquid(")) return source
    var result = source
    if (executableCode.contains("BucketPickup") && executableCode.contains(".pickupBlock(")) {
        result = migrateLegacyBucketPickupCallSites(result)
    }
    if (maskJavaCommentsAndLiterals(result).contains("LiquidBlockContainer") &&
        maskJavaCommentsAndLiterals(result).contains(".canPlaceLiquid(")) {
        result = migrateLegacyLiquidContainerCallSites(result)
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyLiquidContainerCallSites(source: String): String =
    rewriteExecutableJavaCallWithOffset(source, "canPlaceLiquid") { receiver, args, offset ->
        if (receiver.isBlank() || args.size != 4) return@rewriteExecutableJavaCallWithOffset null
        val player = liquidContainerPlayerArgumentForEnclosingMethod(source, offset)
            ?: "null"
        if (!isLiquidBlockContainerReceiverInMethod(source, receiver, offset)) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        "$receiver.canPlaceLiquid($player, ${args.joinToString(", ") { it.trim() }})"
    }

internal fun StructuralRefactorPass.liquidContainerPlayerArgumentForEnclosingMethod(source: String, offset: Int): String? {
    val method = javaMethodRangesIncludingDefault(source).firstOrNull { offset in it.range } ?: return null
    val paramsText = javaMethodParameterText(method.header) ?: return null
    return singlePlayerParameterName(paramsText)
}

internal fun StructuralRefactorPass.isLiquidBlockContainerReceiverInMethod(source: String, receiver: String, offset: Int): Boolean {
    if (isExplicitCastReceiverToSimpleType(receiver, "LiquidBlockContainer")) return true
    if (!Regex("""[A-Za-z_$][\w$]*""").matches(receiver)) return false
    return receiverHasMethodScopedTypeEvidence(source, receiver, offset, "LiquidBlockContainer")
}

internal fun StructuralRefactorPass.isExplicitCastReceiverToSimpleType(receiver: String, simpleType: String): Boolean {
    val type = """(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(simpleType)}"""
    return Regex("""^\s*\(+\s*$type\s*\)\s*.+\)?\s*$""").matches(receiver)
}

internal fun StructuralRefactorPass.receiverHasMethodScopedTypeEvidence(
    source: String,
    receiver: String,
    offset: Int,
    simpleType: String
): Boolean {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val method = javaMethodRangesIncludingDefault(executableCode).firstOrNull { offset in it.range } ?: return false
    val header = executableCode.substring(method.range.first, executableCode.indexOf('{', method.range.first).takeIf { it >= 0 && it <= method.range.last } ?: return false)
    val parameters = javaMethodParameterText(header)
        ?.let(::parseJavaParameters)
        .orEmpty()
    if (parameters.any { it.name == receiver && simpleJavaTypeName(it.type) == simpleType }) {
        return true
    }
    val methodText = executableCode.substring(method.range)
    val relativeOffset = offset - method.range.first
    val openBrace = methodText.indexOf('{')
    if (openBrace < 0 || relativeOffset <= openBrace) return false
    val bodyPrefix = methodText.substring(openBrace + 1, relativeOffset)
    val localType = nearestLocalDeclarationSimpleType(bodyPrefix, receiver)
    if (localType != null) return localType == simpleType
    return patternVariableScopeContainsOffset(methodText, receiver, simpleType, relativeOffset)
}

internal fun StructuralRefactorPass.nearestLocalDeclarationSimpleType(prefix: String, receiver: String): String? {
    val type = """(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(?:\s*<[^;{}()=]*>)?(?:\s*\[\s*])?"""
    val statementKeywords =
        "return|throw|break|continue|yield|assert|if|else|for|while|do|switch|case|default|new|this|super"
    val declarationPattern = Regex(
        """(?m)(?:^|[;{}\r\n])\s*(?:final\s+)?(?!(?:$statementKeywords)\b)""" +
            """($type)\s+${Regex.escape(receiver)}\b"""
    )
    return declarationPattern.findAll(prefix)
        .lastOrNull()
        ?.groupValues
        ?.get(1)
        ?.let(::simpleJavaTypeName)
}

internal fun StructuralRefactorPass.patternVariableScopeContainsOffset(
    methodText: String,
    receiver: String,
    simpleType: String,
    relativeOffset: Int
): Boolean {
    val type = """(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(simpleType)}"""
    val pattern = Regex("""\binstanceof\s+$type\s+${Regex.escape(receiver)}\b""")
    return pattern.findAll(methodText).any { match ->
        val openBrace = methodText.indexOf('{', match.range.last + 1)
        if (openBrace >= 0 && openBrace <= relativeOffset) {
            val closeBrace = findMatchingBrace(methodText, openBrace)
            if (closeBrace >= openBrace && relativeOffset in openBrace..closeBrace) return@any true
        }
        val betweenPatternAndCall = methodText.substring(match.range.last + 1, relativeOffset)
        "&&" in betweenPatternAndCall &&
            !betweenPatternAndCall.contains(';') &&
            !betweenPatternAndCall.contains('{') &&
            !betweenPatternAndCall.contains('}')
    } || negatedPatternExitGuardDominatesOffset(methodText, receiver, simpleType, relativeOffset)
}

internal fun StructuralRefactorPass.negatedPatternExitGuardDominatesOffset(
    methodText: String,
    receiver: String,
    simpleType: String,
    relativeOffset: Int
): Boolean {
    val type = """(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(simpleType)}"""
    val pattern = Regex("""\binstanceof\s+$type\s+${Regex.escape(receiver)}\b""")
    return pattern.findAll(methodText)
        .filter { it.range.last < relativeOffset }
        .any { match ->
            val ifStart = Regex("""\bif\s*\(""")
                .findAll(methodText.substring(0, match.range.first))
                .lastOrNull()
                ?.range
                ?.first
                ?: return@any false
            val conditionOpen = methodText.indexOf('(', ifStart)
            if (conditionOpen < 0 || match.range.first <= conditionOpen) return@any false
            val conditionClose = findMatchingParen(methodText, conditionOpen)
            if (conditionClose < match.range.last || conditionClose >= relativeOffset) return@any false
            val condition = methodText.substring(conditionOpen + 1, conditionClose).trim()
            val exactNegation = Regex(
                """(?s)^!\s*\(\s*[^&|?:;{}]*\binstanceof\s+$type\s+${Regex.escape(receiver)}\s*\)$"""
            )
            if (!exactNegation.matches(condition)) return@any false

            var guardBodyOpen = conditionClose + 1
            while (guardBodyOpen < methodText.length && methodText[guardBodyOpen].isWhitespace()) {
                guardBodyOpen++
            }
            if (methodText.getOrNull(guardBodyOpen) != '{') return@any false
            val guardBodyClose = findMatchingBrace(methodText, guardBodyOpen)
            if (guardBodyClose <= guardBodyOpen || guardBodyClose >= relativeOffset) return@any false
            val guardBody = methodText.substring(guardBodyOpen + 1, guardBodyClose).trim()
            if (!Regex("""(?s)^(?:return\b.*|throw\b.+);$""").matches(guardBody)) {
                return@any false
            }

            enclosingBlockRange(methodText, ifStart) ==
                enclosingBlockRange(methodText, relativeOffset)
        }
}

private fun StructuralRefactorPass.enclosingBlockRange(source: String, offset: Int): IntRange? {
    var cursor = source.lastIndexOf('{', offset)
    while (cursor >= 0) {
        val close = findMatchingBrace(source, cursor)
        if (close >= offset) return cursor..close
        cursor = source.lastIndexOf('{', cursor - 1)
    }
    return null
}

internal fun StructuralRefactorPass.migrateLegacyMobTypeTagChecks(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getMobType()") || !executableCode.contains("MobType.")) return source
    var changed = false
    var result = replaceExecutableRegex(
        source,
        Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*)\.getMobType\(\)\s*([!=]=)\s*MobType\.(UNDEAD|ARTHROPOD)""")
    ) { match ->
        changed = true
        val target = match.groupValues[1]
        val comparison = match.groupValues[2]
        val tag = when (match.groupValues[3]) {
            "UNDEAD" -> "EntityTypeTags.UNDEAD"
            "ARTHROPOD" -> "EntityTypeTags.ARTHROPOD"
            else -> return@replaceExecutableRegex match.value
        }
        val expression = "$target.getType().is($tag)"
        if (comparison == "!=") "!$expression" else expression
    }
    result = replaceExecutableRegex(
        result,
        Regex("""MobType\.(UNDEAD|ARTHROPOD)\s*([!=]=)\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*)\.getMobType\(\)""")
    ) { match ->
        changed = true
        val tag = when (match.groupValues[1]) {
            "UNDEAD" -> "EntityTypeTags.UNDEAD"
            "ARTHROPOD" -> "EntityTypeTags.ARTHROPOD"
            else -> return@replaceExecutableRegex match.value
        }
        val comparison = match.groupValues[2]
        val target = match.groupValues[3]
        val expression = "$target.getType().is($tag)"
        if (comparison == "!=") "!$expression" else expression
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.tags.EntityTypeTags")
    val withoutMobTypeImport = removeImport(result, "net.minecraft.world.entity.MobType")
    if (!maskJavaCommentsAndLiterals(withoutMobTypeImport).contains("MobType.")) {
        result = withoutMobTypeImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateRemoved121SourceSymbols(source: String): String {
    var result = source
    result = replaceExecutableRegex(result, Regex("""\bStemGrownBlock\b""")) { "AttachedStemBlock" }
    result = Regex(
        """(?m)^[ \t]*(?:@Override\s*\r?\n[ \t]*)?public\s+MobType\s+getMobType\s*\(\s*\)\s*\{\s*\r?\n[ \t]*return\s+MobType\.[A-Z_]+\s*;\s*\r?\n[ \t]*\}\s*\r?\n?"""
    ).replace(result, "")
    result = removeUnusedSimpleImport(result, "net.minecraft.world.entity.MobType", "MobType")
    result = removeUnusedSimpleImport(result, "net.neoforged.neoforge.fluids.IFluidBlock", "IFluidBlock")
    return result
}

internal fun StructuralRefactorPass.migrateContainerEntityLootTableResourceKeys(source: String): String {
    if (!source.contains("ContainerEntity")) return source
    var result = source
    result = Regex("""(?m)^([ \t]*(?:@Nullable\s*)?(?:private|protected|public)\s+)ResourceLocation(\s+lootTable\s*;)""")
        .replace(result, "$1ResourceKey<LootTable>$2")
    result = Regex("""void\s+setLootTable\s*\(\s*((?:@\w+(?:\.\w+)*\s+)*)ResourceLocation\s+([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result, "void setLootTable($1ResourceKey<LootTable> $2)")
    result = Regex("""\baddChestVehicleSaveData\(\s*([^,)]+)\s*\)""")
        .replace(result) { match -> "addChestVehicleSaveData(${match.groupValues[1].trim()}, this.registryAccess())" }
    result = Regex("""\breadChestVehicleSaveData\(\s*([^,)]+)\s*\)""")
        .replace(result) { match -> "readChestVehicleSaveData(${match.groupValues[1].trim()}, this.registryAccess())" }
    if (result != source) {
        result = addImportIfMissing(result, "net.minecraft.resources.ResourceKey")
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.LootTable")
        val withoutResourceLocation = removeImport(result, "net.minecraft.resources.ResourceLocation")
        if (!Regex("""\bResourceLocation\b""").containsMatchIn(withoutResourceLocation)) {
            result = withoutResourceLocation
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyStructureStartCustomLoads(source: String): String {
    if (!source.contains("StructureStart") &&
        !source.contains("PiecesContainer") &&
        !source.contains("loadAdditional(")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var changed = false

    if (Regex("""\bextends\s+(?:[A-Za-z_$][\w$]*\.)?StructureStart\b""").containsMatchIn(result)) {
        result = Regex(
            """(?m)^([ \t]*)(?:@Override\s*\r?\n[ \t]*)?(?:protected|public)\s+void\s+loadAdditional\s*\(\s*CompoundTag\s+($id)\s*,\s*(?:net\.minecraft\.core\.)?HolderLookup\.Provider\s+$id\s*\)"""
        ).replace(result) { match ->
            changed = true
            "${match.groupValues[1]}public void loadFromTag(CompoundTag ${match.groupValues[2]})"
        }
    }

    val structureStartVariables = Regex(
        """\b((?:[A-Za-z_$][\w$]*\.)*$id(?:StructureStart)?)\s+($id)\s*=\s*new\s+(?:[A-Za-z_$][\w$]*\.)*$id(?:StructureStart)?\s*\([^;]*\b(?:PiecesContainer|pieces)\b[^;]*\)"""
    ).findAll(result)
        .mapNotNull { match ->
            val typeName = match.groupValues[1].substringAfterLast('.')
            val variable = match.groupValues[2]
            variable.takeIf { typeName == "StructureStart" || typeName.endsWith("StructureStart") }
        }
        .toSet()
    val compoundTagVariables = Regex("""\bCompoundTag\s+($id)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()

    if (structureStartVariables.isNotEmpty() && compoundTagVariables.isNotEmpty()) {
        for (startVariable in structureStartVariables) {
            for (tagVariable in compoundTagVariables) {
                val migrated = Regex("""\b${Regex.escape(startVariable)}\.load\(\s*${Regex.escape(tagVariable)}\s*\)""")
                    .replace(result, "$startVariable.loadFromTag($tagVariable)")
                if (migrated != result) {
                    changed = true
                    result = migrated
                }
            }
        }
    }

    if (changed && !Regex("""\bHolderLookup\b""").containsMatchIn(removeImport(result, "net.minecraft.core.HolderLookup"))) {
        result = removeImport(result, "net.minecraft.core.HolderLookup")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLargeFireballVec3Constructors(source: String): String {
    if (!source.contains("LargeFireball") && !source.contains("Fireball") && !source.contains("AbstractHurtingProjectile")) return source
    var changed = false
    var result = Regex("""new\s+LargeFireball\(\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^)]+?)\s*\)""")
        .replace(source) { match ->
            changed = true
            val level = match.groupValues[1].trim()
            val owner = match.groupValues[2].trim()
            val x = match.groupValues[3].trim()
            val y = match.groupValues[4].trim()
            val z = match.groupValues[5].trim()
            val power = match.groupValues[6].trim()
            "new LargeFireball($level, $owner, new Vec3($x, $y, $z).normalize(), $power)"
    }
    if (source.contains("extends LargeFireball")) {
        result = Regex("""super\(\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^)]+?)\s*\)""")
            .replace(result) { match ->
                changed = true
                val level = match.groupValues[1].trim()
                val owner = match.groupValues[2].trim()
                val x = match.groupValues[3].trim()
                val y = match.groupValues[4].trim()
                val z = match.groupValues[5].trim()
                val power = match.groupValues[6].trim()
                "super($level, $owner, new Vec3($x, $y, $z), $power)"
            }
    }
    if (Regex("""\bextends\s+(?:Fireball|AbstractHurtingProjectile)\b""").containsMatchIn(source)) {
        val declaredTypes = javaDeclaredSimpleTypeSets(result)
        result = rewriteSuperConstructorCalls(result) { args ->
            if (args.size == 8) {
                changed = true
                "super(${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()}, ${args[3].trim()}, new Vec3(${args[4].trim()}, ${args[5].trim()}, ${args[6].trim()}), ${args[7].trim()})"
            } else {
                null
            }
        }
        result = Regex("""super\(\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*([^)]+?)\s*\)""")
            .replace(result) { match ->
                val type = match.groupValues[1].trim()
                val owner = match.groupValues[2].trim()
                val ownerTypes = declaredTypes[owner.substringBefore('.').trim()].orEmpty()
                if (ownerTypes.none { it == "LivingEntity" || it == "Player" }) {
                    return@replace match.value
                }
                changed = true
                val x = match.groupValues[3].trim()
                val y = match.groupValues[4].trim()
                val z = match.groupValues[5].trim()
                val level = match.groupValues[6].trim()
                "super($type, $owner, new Vec3($x, $y, $z), $level)"
            }
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.world.phys.Vec3") else source
}

internal fun StructuralRefactorPass.migrateLegacySignBlockConstructors(source: String): String {
    if (!source.contains("WoodType") ||
        !(source.contains("extends StandingSignBlock") ||
            source.contains("extends WallSignBlock") ||
            source.contains("extends CeilingHangingSignBlock") ||
            source.contains("extends WallHangingSignBlock"))) {
        return source
    }
    var result = source
    Regex(
        """\b(?:public|protected|private)?\s*[A-Za-z_$][\w$]*\s*\(\s*(?:(?:BlockBehaviour\.)?Properties)\s+([A-Za-z_$][\w$]*)\s*,\s*WoodType\s+([A-Za-z_$][\w$]*)\s*\)"""
    ).findAll(source).forEach { constructor ->
        val propertiesName = constructor.groupValues[1]
        val woodTypeName = constructor.groupValues[2]
        result = result.replace("super($propertiesName, $woodTypeName);", "super($woodTypeName, $propertiesName);")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPacketDistributorSends(source: String): String {
    if (!source.contains("PacketDistributor.") && !source.contains(".toVanillaPacket(")) return source

    var result = source
    var needsChunkPos = false
    var needsServerLevel = false
    val nearTargets = linkedMapOf<String, LegacyNearTargetPoint>()
    result = Regex(
        """(?m)^[ \t]*PacketDistributor\.TargetPoint\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+PacketDistributor\.TargetPoint\s*\((.*)\)\s*;\s*\r?\n"""
    ).replace(result) { match ->
        val args = splitTopLevelJavaArgs(match.groupValues[2])
        val target = legacyNearTargetPoint(args)
        if (target != null) {
            nearTargets[match.groupValues[1]] = target
            ""
        } else {
            match.value
        }
    }
    result = migrateSimpleChannelSendCalls(result) { distributor, target, payload ->
        when (distributor) {
            "TRACKING_ENTITY" -> "PacketDistributor.sendToPlayersTrackingEntity($target, $payload)"
            "TRACKING_ENTITY_AND_SELF" -> "PacketDistributor.sendToPlayersTrackingEntityAndSelf($target, $payload)"
            "PLAYER" -> "PacketDistributor.sendToPlayer($target, $payload)"
            "SERVER" -> "PacketDistributor.sendToServer($payload)"
            "ALL" -> "PacketDistributor.sendToAllPlayers($payload)"
            "NEAR" -> {
                val nearTarget = nearTargets[target] ?: legacyNearTargetPointExpression(target)
                if (nearTarget != null) {
                    needsServerLevel = needsServerLevel || nearTarget.levelNeedsCast(result)
                    "PacketDistributor.sendToPlayersNear(${nearTarget.serverLevelExpression(result)}, null, ${nearTarget.x}, ${nearTarget.y}, ${nearTarget.z}, ${nearTarget.radius}, $payload)"
                } else {
                    null
                }
            }
            "TRACKING_CHUNK" -> {
                val chunkAt = Regex("""^(.+)\.getChunkAt\((.+)\)$""").find(target)
                if (chunkAt != null) {
                    needsChunkPos = true
                    "PacketDistributor.sendToPlayersTrackingChunk(${chunkAt.groupValues[1].trim()}, new ChunkPos(${chunkAt.groupValues[2].trim()}), $payload)"
                } else {
                    null
                }
            }
            else -> null
        }
    }
    result = Regex(
        """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.toVanillaPacket\(\s*(new\s+[A-Za-z_$][\w$]*\([^;]+?\))\s*,\s*NetworkDirection\.PLAY_TO_CLIENT\s*\)"""
    ).replace(result) { match -> "${match.groupValues[2]}.toVanillaClientbound()" }
    result = Regex(
        """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.toVanillaPacket\(\s*(new\s+[A-Za-z_$][\w$]*\([^;]+?\))\s*,\s*NetworkDirection\.PLAY_TO_SERVER\s*\)"""
    ).replace(result) { match -> "${match.groupValues[2]}.toVanillaServerbound()" }
    if (needsChunkPos) {
        result = addImportIfMissing(result, "net.minecraft.world.level.ChunkPos")
    }
    if (needsServerLevel) {
        result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    }
    if (result.contains("toVanillaClientbound()") || result.contains("toVanillaServerbound()")) {
        result = removeImport(result, "net.neoforged.neoforge.network.NetworkDirection")
        result = removeImport(result, "net.minecraftforge.network.NetworkDirection")
    }
    return result
}

internal data class LegacyNearTargetPoint(
    val x: String,
    val y: String,
    val z: String,
    val radius: String,
    val levelExpression: String
) {
    fun levelNeedsCast(source: String): Boolean =
        !Regex("""\bServerLevel\s+${Regex.escape(levelExpression)}\b""").containsMatchIn(source)

    fun serverLevelExpression(source: String): String =
        if (levelNeedsCast(source)) "(ServerLevel) $levelExpression" else levelExpression
}

internal fun StructuralRefactorPass.migrateSimpleChannelSendCalls(
    source: String,
    replacementFor: (distributor: String, target: String, payload: String) -> String?
): String {
    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    val token = ".send("
    while (cursor < source.length) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + ".send".length
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val receiverStart = findDottedReceiverStart(source, tokenIndex)
        val receiver = source.substring(receiverStart, tokenIndex).trim()
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        val distributor = args.getOrNull(0)?.let(::parsePacketDistributorTarget)
        val payload = args.getOrNull(1)?.trim()
        if (receiver.isNotEmpty() && args.size == 2 && distributor != null && payload != null) {
            val replacement = replacementFor(distributor.first, distributor.second, payload)
            if (replacement != null) {
                migrated.append(source, cursor, receiverStart)
                migrated.append(replacement)
                cursor = closeParen + 1
                changed = true
                continue
            }
        }
        migrated.append(source, cursor, closeParen + 1)
        cursor = closeParen + 1
    }
    if (!changed) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.findDottedReceiverStart(source: String, tokenIndex: Int): Int {
    var index = tokenIndex - 1
    while (index >= 0) {
        val c = source[index]
        if (c.isLetterOrDigit() || c == '_' || c == '$' || c == '.') {
            index--
        } else {
            break
        }
    }
    return index + 1
}

internal fun StructuralRefactorPass.parsePacketDistributorTarget(expression: String): Pair<String, String>? {
    val trimmed = expression.trim()
    Regex("""^PacketDistributor\.([A-Z_]+)\.with\(\s*\(\)\s*->\s*(.+)\s*\)$""")
        .find(trimmed)
        ?.let { return it.groupValues[1] to it.groupValues[2].trim() }
    Regex("""^PacketDistributor\.([A-Z_]+)\.noArg\(\s*\)$""")
        .find(trimmed)
        ?.let { return it.groupValues[1] to "" }
    return null
}

internal fun StructuralRefactorPass.legacyNearTargetPointExpression(expression: String): LegacyNearTargetPoint? {
    val trimmed = expression.trim()
    if (!trimmed.startsWith("new PacketDistributor.TargetPoint(")) return null
    val openParen = trimmed.indexOf('(')
    if (openParen < 0) return null
    val closeParen = findMatchingParen(trimmed, openParen)
    if (closeParen < 0) return null
    return legacyNearTargetPoint(splitTopLevelJavaArgs(trimmed.substring(openParen + 1, closeParen)))
}

internal fun StructuralRefactorPass.legacyNearTargetPoint(args: List<String>): LegacyNearTargetPoint? {
    if (args.size != 5) return null
    val dimension = args[4].trim()
    val level = Regex("""^(.+)\.dimension\(\)$""")
        .find(dimension)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: return null
    return LegacyNearTargetPoint(
        x = args[0].trim(),
        y = args[1].trim(),
        z = args[2].trim(),
        radius = args[3].trim(),
        levelExpression = level
    )
}

internal fun StructuralRefactorPass.migrateLegacyEventHooks121(source: String): String {
    if (!source.contains("EventHooks.")) return source

    var result = removeRemovedBucketUseHookGuard(source)
    result = result.replace("EventHooks.getMobGriefingEvent(", "EventHooks.canEntityGrief(")
    if (result.contains("EventHooks.onFinalizeSpawn(")) {
        result = result.replace("EventHooks.onFinalizeSpawn(", "EventHooks.finalizeMobSpawn(")
        result = migrateMethodCalls(result, "EventHooks.finalizeMobSpawn") { args ->
            if (args.size == 6) args.take(5) else args
        }
    }
    result = migrateEventHooksDoPlayerHarvestCheck(result)
    return result
}

internal data class HarvestCheckBlockStateAccess(val blockGetter: String, val blockPos: String)

internal fun StructuralRefactorPass.migrateEventHooksDoPlayerHarvestCheck(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("EventHooks.doPlayerHarvestCheck(")) return source
    val methodRanges = javaMethodRangesIncludingDefault(executableCode)
    val edits = mutableListOf<Pair<IntRange, String>>()
    val token = ".doPlayerHarvestCheck("
    var cursor = 0
    while (true) {
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + ".doPlayerHarvestCheck".length
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
        if (receiver != "EventHooks") {
            cursor = closeParen + 1
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size != 3) {
            cursor = closeParen + 1
            continue
        }
        val method = methodRanges.firstOrNull { tokenIndex in it.range }
        if (method == null) {
            cursor = closeParen + 1
            continue
        }
        val access = resolveHarvestCheckBlockStateAccess(
            stateArgument = args[1].trim(),
            methodSource = source.substring(method.range),
            executableMethod = executableCode.substring(method.range),
            callOffsetInMethod = tokenIndex - method.range.first
        )
        if (access != null) {
            edits += receiverStart..closeParen to
                "EventHooks.doPlayerHarvestCheck(${args[0].trim()}, ${args[1].trim()}, ${access.blockGetter}, ${access.blockPos})"
        }
        cursor = closeParen + 1
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.resolveHarvestCheckBlockStateAccess(
    stateArgument: String,
    methodSource: String,
    executableMethod: String,
    callOffsetInMethod: Int
): HarvestCheckBlockStateAccess? {
    harvestCheckBlockStateAccessFromExpression(stateArgument)?.let { return it }
    val stateVariable = Regex("""[A-Za-z_$][\w$]*""").matchEntire(stateArgument)?.value ?: return null
    val escapedState = Regex.escape(stateVariable)
    val candidates = (
        Regex("""(?m)\b(?:final\s+)?(?:BlockState|var)\s+$escapedState\s*=\s*""").findAll(executableMethod) +
            Regex("""(?m)(?<![.\w$])$escapedState\s*=\s*(?!=)""").findAll(executableMethod)
        )
        .filter { it.range.first < callOffsetInMethod }
        .distinctBy { it.range.last }
        .sortedBy { it.range.first }
        .toList()
    val lastAssignment = candidates.lastOrNull() ?: return null
    val expressionStart = lastAssignment.range.last + 1
    val expressionEnd = findJavaStatementSemicolon(executableMethod, expressionStart)
    if (expressionEnd < 0 || expressionEnd > callOffsetInMethod) return null
    val expression = methodSource.substring(expressionStart, expressionEnd).trim()
    return harvestCheckBlockStateAccessFromExpression(expression)
}

internal fun StructuralRefactorPass.harvestCheckBlockStateAccessFromExpression(expression: String): HarvestCheckBlockStateAccess? {
    val trimmed = expression.trim()
    val token = ".getBlockState("
    val tokenIndex = trimmed.indexOf(token)
    if (tokenIndex < 0) return null
    val openParen = tokenIndex + ".getBlockState".length
    val closeParen = findMatchingParen(trimmed, openParen)
    if (closeParen < 0 || trimmed.substring(closeParen + 1).isNotBlank()) return null
    val receiverStart = findExpressionReceiverStart(trimmed, tokenIndex)
    if (receiverStart < 0 || receiverStart >= tokenIndex) return null
    val args = splitTopLevelJavaArgs(trimmed.substring(openParen + 1, closeParen))
    if (args.size != 1) return null
    return HarvestCheckBlockStateAccess(
        blockGetter = trimmed.substring(receiverStart, tokenIndex).trim(),
        blockPos = args[0].trim()
    )
}

internal fun StructuralRefactorPass.findJavaStatementSemicolon(source: String, start: Int): Int {
    var i = start
    var parenDepth = 0
    var braceDepth = 0
    var bracketDepth = 0
    while (i < source.length) {
        when (source[i]) {
            '"' -> i = skipJavaStringLiteral(source, i)
            '\'' -> i = skipJavaCharLiteral(source, i)
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            ';' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) return i
        }
        i++
    }
    return -1
}

internal fun StructuralRefactorPass.removeRemovedBucketUseHookGuard(source: String): String {
    if (!source.contains("EventHooks.onBucketUse(")) return source
    var result = source
    val inlineGuard = Regex(
        """(?m)^[ \t]*(?:(?:var|[\w.]+(?:\s*<[^=\r\n;]+>)?)\s+)([A-Za-z_$][\w$]*)\s*=\s*(?:net\.neoforged\.neoforge\.event\.)?EventHooks\.onBucketUse\s*\([^;\r\n]*\);\s*\r?\n[ \t]*if\s*\(\s*\1\s*!=\s*null\s*\)\s*return\s+\1\s*;\s*\r?\n?"""
    )
    result = inlineGuard.replace(result, "")
    val blockGuard = Regex(
        """(?m)^[ \t]*(?:(?:var|[\w.]+(?:\s*<[^=\r\n;]+>)?)\s+)([A-Za-z_$][\w$]*)\s*=\s*(?:net\.neoforged\.neoforge\.event\.)?EventHooks\.onBucketUse\s*\([^;\r\n]*\);\s*\r?\n[ \t]*if\s*\(\s*\1\s*!=\s*null\s*\)\s*\{\s*\r?\n[ \t]*return\s+\1\s*;\s*\r?\n[ \t]*\}\s*\r?\n?"""
    )
    result = blockGuard.replace(result, "")
    if (!Regex("""\bEventHooks\.""").containsMatchIn(result)) {
        result = removeImport(result, "net.neoforged.neoforge.event.EventHooks")
    }
    return cleanupRedundantBlankLines(result)
}

internal fun StructuralRefactorPass.migrateLegacyLootAndRegistryAccess(
    source: String,
    legacyLootTableResourceLocationReferences: Set<String>
): String {
    var result = source
    var needsBlocks = false
    var needsResourceKey = false
    var needsRegistries = false
    var needsLootTable = false

    if (result.contains(".getLootData()")) {
        result = result.replace(".getLootData()", ".reloadableRegistries()")
    }

    if (result.contains(".getLootTable(")) {
        result = rewriteJavaCall(result, "getLootTable") { receiver, args ->
            if (args.size != 1) return@rewriteJavaCall null
            val lootTable = args[0].trim()
            val nestedLootTableKey = unwrapLootTableResourceKeyCreate(lootTable)
            if (nestedLootTableKey != null &&
                (isLootTableResourceKeyExpression(nestedLootTableKey, legacyLootTableResourceLocationReferences) ||
                    isLocalLootTableResourceKeyExpression(result, nestedLootTableKey))) {
                return@rewriteJavaCall "$receiver.getLootTable($nestedLootTableKey)"
            }
            if (isLocalLootTableResourceKeyExpression(result, lootTable)) {
                return@rewriteJavaCall null
            }
            if (isLootTableResourceKeyExpression(lootTable, legacyLootTableResourceLocationReferences)) {
                null
            } else {
                needsResourceKey = true
                needsRegistries = true
                "$receiver.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, $lootTable))"
            }
        }
        result = rewriteJavaCall(result, "getLootTable") { receiver, args ->
            if (args.size != 1 || !receiver.endsWith(".getResolver()")) return@rewriteJavaCall null
            val key = args[0].trim()
            if (unwrapLootTableResourceKeyCreate(key) == null &&
                !isLocalLootTableResourceKeyExpression(result, key) &&
                !isLootTableResourceKeyExpression(key, legacyLootTableResourceLocationReferences)) {
                return@rewriteJavaCall null
            }
            needsRegistries = true
            "$receiver.lookupOrThrow(Registries.LOOT_TABLE).getOrThrow($key).value()"
        }
    }

    if (maskJavaCommentsAndLiterals(result).contains("new AdvancementRewards(")) {
        result = rewriteExecutableJavaNew(result, "AdvancementRewards") { args ->
            if (args.size != 4) return@rewriteExecutableJavaNew null
            val lootList = legacyResourceLocationArrayToList(args[1]) { entry ->
                if (isLootTableResourceKeyExpression(entry, legacyLootTableResourceLocationReferences)) {
                    entry
                } else {
                    needsResourceKey = true
                    needsRegistries = true
                    "ResourceKey.create(Registries.LOOT_TABLE, $entry)"
                }
            } ?: return@rewriteExecutableJavaNew null
            val recipeList = legacyResourceLocationArrayToList(args[2]) { it } ?: return@rewriteExecutableJavaNew null
            "new AdvancementRewards(${args[0].trim()}, $lootList, $recipeList, ${args[3].trim()})"
        }
    }

    if (result.contains("LootTableIdCondition.builder(")) {
        result = rewriteJavaCall(result, "builder") { receiver, args ->
            if (receiver != "LootTableIdCondition" || args.size != 1) return@rewriteJavaCall null
            val lootTable = args[0].trim()
            if (!isLootTableResourceKeyExpression(lootTable, legacyLootTableResourceLocationReferences) || lootTable.endsWith(".location()")) {
                null
            } else {
                "$receiver.builder($lootTable.location())"
            }
        }
    }

    if (result.contains("LootTableSubProvider") &&
        result.contains("BiConsumer<ResourceLocation, LootTable.Builder>")) {
        result = result.replace(
            "BiConsumer<ResourceLocation, LootTable.Builder>",
            "BiConsumer<ResourceKey<LootTable>, LootTable.Builder>"
        )
        needsResourceKey = true
        needsLootTable = true
        result = rewriteExecutableJavaCall(result, "accept") { receiver, args ->
            if (receiver.isBlank() || args.size != 2) return@rewriteExecutableJavaCall null
            val key = args[0].trim()
            if (key.startsWith("ResourceKey.") ||
                key.contains("ResourceKey.create(") ||
                isLootTableResourceKeyExpression(key, legacyLootTableResourceLocationReferences)) {
                null
            } else {
                needsResourceKey = true
                needsRegistries = true
                "$receiver.accept(ResourceKey.create(Registries.LOOT_TABLE, $key), ${args[1].trim()})"
            }
        }
        val withoutResourceLocation = removeImport(result, "net.minecraft.resources.ResourceLocation")
        if (!Regex("""\bResourceLocation\b""").containsMatchIn(withoutResourceLocation)) {
            result = withoutResourceLocation
        }
    }

    if (result.contains("NestedLootTable.lootTableReference(")) {
        result = migrateMethodCalls(result, "NestedLootTable.lootTableReference") { args ->
            if (args.size == 1 && !args[0].contains("ResourceKey.create(")) {
                needsResourceKey = true
                needsRegistries = true
                listOf("ResourceKey.create(Registries.LOOT_TABLE, ${args[0].trim()})")
            } else {
                args
            }
        }
    }

    if (result.contains("ResourceLocation") && result.contains("register(")) {
        val beforeLootKeys = result
        result = migrateLegacyLootTableResourceLocationRegistry(result, legacyLootTableResourceLocationReferences)
        if (result != beforeLootKeys) {
            needsResourceKey = true
            needsRegistries = true
            needsLootTable = true
        }
    }

    if (result.contains("ResourceLocation") &&
        (result.contains("LootTable") || result.contains("getLootLocation("))) {
        val beforeLootMethods = result
        result = Regex(
            """(?m)^([ \t]*(?:public|protected|private)?\s*(?:abstract\s+)?(?:@\w+(?:\([^)]*\))?\s+)*)ResourceLocation(\s+[A-Za-z_$][\w$]*LootTable\s*\()"""
        ).replace(result) { match ->
            needsResourceKey = true
            needsLootTable = true
            "${match.groupValues[1]}ResourceKey<LootTable>${match.groupValues[2]}"
        }
        result = Regex(
            """(?m)^([ \t]*(?:public|protected|private)?\s*(?:abstract\s+)?(?:@\w+(?:\([^)]*\))?\s+)*)ResourceLocation(\s+get[A-Za-z_$][\w$]*LootTable\s*\()"""
        ).replace(result) { match ->
            needsResourceKey = true
            needsLootTable = true
            "${match.groupValues[1]}ResourceKey<LootTable>${match.groupValues[2]}"
        }
        result = Regex(
            """(?m)^([ \t]*(?:public|protected|private)?\s*(?:abstract\s+)?(?:@\w+(?:\([^)]*\))?\s+)*)ResourceLocation(\s+get[A-Za-z_$][\w$]*LootLocation\s*\()"""
        ).replace(result) { match ->
            needsResourceKey = true
            needsLootTable = true
            "${match.groupValues[1]}ResourceKey<LootTable>${match.groupValues[2]}"
        }
        result = Regex(
            """(?m)^([ \t]*(?:public|protected|private)?\s*(?:abstract\s+)?(?:@\w+(?:\([^)]*\))?\s+)*)ResourceLocation(\s+getLootLocation\s*\()"""
        ).replace(result) { match ->
            needsResourceKey = true
            needsLootTable = true
            "${match.groupValues[1]}ResourceKey<LootTable>${match.groupValues[2]}"
        }
        if (result != beforeLootMethods) {
            val withoutResourceLocation = removeImport(result, "net.minecraft.resources.ResourceLocation")
            if (!Regex("""\bResourceLocation\b""").containsMatchIn(withoutResourceLocation)) {
                result = withoutResourceLocation
            }
        }
    }

    result = Regex(
        """BuiltInRegistries\.BLOCK\.tags\(\)\.getTag\(([^)]+)\)\.getRandomElement\(((?:[^()]|\([^()]*\))+)\)\.get\(\)\.defaultBlockState\(\)"""
    ).replace(result) { match ->
        needsBlocks = true
        "BuiltInRegistries.BLOCK.getRandomElementOf(${match.groupValues[1].trim()}, ${match.groupValues[2].trim()}).map(holder -> holder.value().defaultBlockState()).orElseGet(Blocks.AIR::defaultBlockState)"
    }
    result = result.replace("Objects.requireNonNull(BuiltInRegistries.BLOCK.tags()).getTagNames()", "BuiltInRegistries.BLOCK.getTagNames()")
    result = Regex("""Objects\.requireNonNull\(BuiltInRegistries\.BLOCK\.tags\(\)\)\.getTag\(([^)]+)\)""")
        .replace(result) { match -> "BuiltInRegistries.BLOCK.getOrCreateTag(${match.groupValues[1].trim()})" }
    result = Regex("""BuiltInRegistries\.BLOCK\.tags\(\)\.getTag\(([^)]+)\)""")
        .replace(result) { match -> "BuiltInRegistries.BLOCK.getOrCreateTag(${match.groupValues[1].trim()})" }

    if (needsBlocks) result = addImportIfMissing(result, "net.minecraft.world.level.block.Blocks")
    if (needsResourceKey) result = addImportIfMissing(result, "net.minecraft.resources.ResourceKey")
    if (needsRegistries) result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
    if (needsLootTable || (needsResourceKey && result.contains("ResourceKey<LootTable>"))) {
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.LootTable")
    }
    return result
}

internal fun StructuralRefactorPass.collectLegacyLootTableResourceLocationReferences(javaFiles: List<Path>): Set<String> {
    val references = linkedSetOf<String>()
    fun addReference(expression: String) {
        val normalized = expression
            .trim()
            .removeSuffix(".location()")
            .removeSurrounding("(", ")")
            .trim()
        if (Regex("""(?:[A-Za-z_$][\w$]*\.)?(?:[A-Z][A-Z0-9_]*|[A-Za-z_$][\w$]*\(\))""").matches(normalized)) {
            references += normalized
        }
    }

    javaFiles.forEach { javaFile ->
        val source = javaFile.readText()
        if (!source.contains("LootTable")) return@forEach
        var cursor = 0
        while (true) {
            val match = Regex("""new\s+LootTableProvider\s*\(""").find(source, cursor) ?: break
            val openParen = source.indexOf('(', match.range.last)
            val closeParen = findMatchingParen(source, openParen)
            if (closeParen < 0) {
                cursor = match.range.last + 1
                continue
            }
            val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
            if (args.size >= 2) addReference(args[1])
            cursor = closeParen + 1
        }
        Regex("""register\.accept\s*\(\s*([^,]+?)\s*,\s*LootTable\.lootTable\s*\(""")
            .findAll(source)
            .forEach { addReference(it.groupValues[1]) }
        Regex("""NestedLootTable\.lootTableReference\s*\(\s*([^)]+?)\s*\)""")
            .findAll(source)
            .forEach { addReference(it.groupValues[1]) }
        Regex("""LootTableIdCondition\.builder\s*\(\s*([^)]+?)\s*\)""")
            .findAll(source)
            .forEach { addReference(it.groupValues[1]) }
        Regex("""new\s+ResourceLocation\s*\[\s*]\s*\{([^}]*)}""")
            .findAll(source)
            .forEach { match ->
                splitTopLevelJavaArgs(match.groupValues[1]).forEach(::addReference)
            }
    }
    return references
}

internal fun StructuralRefactorPass.migrateLegacyLootTableResourceLocationRegistry(
    source: String,
    legacyLootTableResourceLocationReferences: Set<String>
): String {
    if (!Regex("""(?m)^\s*private\s+static\s+ResourceLocation\s+register\s*\(""").containsMatchIn(source) &&
        !Regex("""(?m)^\s*(?:public|protected|private)?\s*static\s+final\s+Set\s*<\s*ResourceLocation\s*>""").containsMatchIn(source)) {
        return source
    }

    var result = source
    val ownerClass = classNameOfJavaSource(source) ?: return source
    val ownerPackage = packageNameOf(source)
    fun isProvenLootReference(fieldName: String): Boolean {
        if (fieldName in legacyLootTableResourceLocationReferences) return true
        if (ownerClass.isNotBlank() && "$ownerClass.$fieldName" in legacyLootTableResourceLocationReferences) return true
        if (ownerClass.isNotBlank() && ownerPackage.isNotBlank() &&
            "$ownerPackage.$ownerClass.$fieldName" in legacyLootTableResourceLocationReferences) {
            return true
        }
        return false
    }
    val staticResourceLocationSetPattern = Regex(
        """(?m)^([ \t]*(?:public|protected|private)?\s*static\s+final\s+)Set\s*<\s*ResourceLocation\s*>(\s+([A-Za-z_$][\w$]*)\s*=)"""
    )
    val resourceLocationSetNames = staticResourceLocationSetPattern.findAll(result)
        .map { it.groupValues[3] }
        .toSet()
    val registerSetNames = legacyResourceLocationRegisterSets(result, resourceLocationSetNames)
    val setAliases = legacyResourceLocationSetAliases(result, resourceLocationSetNames)
    val setReturnMethods = legacyResourceLocationSetReturnMethods(result, resourceLocationSetNames)
    fun isProvenLootSetReference(fieldName: String): Boolean {
        if (isProvenLootReference(fieldName)) return true
        return setReturnMethods
            .filterValues { returnedField -> returnedField == fieldName }
            .keys
            .any { methodName ->
                "$methodName()" in legacyLootTableResourceLocationReferences ||
                    "$ownerClass.$methodName()" in legacyLootTableResourceLocationReferences ||
                    (ownerPackage.isNotBlank() &&
                        "$ownerPackage.$ownerClass.$methodName()" in legacyLootTableResourceLocationReferences)
            }
    }
    fun expandSetAliases(seed: Set<String>): Set<String> {
        val expanded = seed.toCollection(linkedSetOf())
        var changed: Boolean
        do {
            changed = false
            setAliases.forEach { (alias, target) ->
                if (target in expanded && expanded.add(alias)) changed = true
                if (alias in expanded && expanded.add(target)) changed = true
            }
        } while (changed)
        return expanded
    }
    val directlyProvenSetNames = resourceLocationSetNames.filterTo(linkedSetOf(), ::isProvenLootReference)
    val provenSetNames = registerSetNames.filterTo(linkedSetOf(), ::isProvenLootSetReference) + directlyProvenSetNames
    val provenRegisteredFieldNames = Regex(
        """(?m)^\s*public\s+static\s+final\s+ResourceLocation\s+([A-Z0-9_]+)\s*=\s*register\("""
    ).findAll(result)
        .map { it.groupValues[1] }
        .filterTo(linkedSetOf(), ::isProvenLootReference)
    val baseLootSetNames = when {
        provenSetNames.isNotEmpty() -> provenSetNames
        provenRegisteredFieldNames.isNotEmpty() -> registerSetNames
        else -> emptySet()
    }
    val lootSetNames = expandSetAliases(baseLootSetNames)
    if (lootSetNames.isEmpty()) return source
    if (!Regex("""(?m)^\s*public\s+static\s+final\s+ResourceLocation\s+[A-Z0-9_]+\s*=\s*register\(""").containsMatchIn(result) &&
        staticResourceLocationSetPattern.findAll(result).none { it.groupValues[3] in lootSetNames }) {
        return source
    }

    result = staticResourceLocationSetPattern.replace(result) { match ->
        if (match.groupValues[3].contains("loot", ignoreCase = true)) {
            "${match.groupValues[1]}Set<ResourceKey<LootTable>>${match.groupValues[2]}"
        } else {
            match.value
        }
    }
    result = Regex(
        """(?m)^([ \t]*public\s+static\s+final\s+)ResourceLocation(\s+[A-Z0-9_]+\s*=\s*register\()"""
    ).replace(result, "$1ResourceKey<LootTable>$2")
    result = Regex(
        """(?m)^([ \t]*(?:public|protected|private)?\s*(?:final\s+)?)ResourceLocation(\s+[A-Za-z_$][\w$]*(?:Loot|loot)[A-Za-z_$][\w$]*\s*;)"""
    ).replace(result, "$1ResourceKey<LootTable>$2")
    result = Regex(
        """(?m)^([ \t]*(?:public|protected|private)?\s*(?:static\s+)?)Set\s*<\s*ResourceLocation\s*>(\s+[A-Za-z_$][\w$]*Builtin\s*\()"""
    ).replace(result, "$1Set<ResourceKey<LootTable>>$2")
    result = Regex(
        """this\.([A-Za-z_$][\w$]*)\s*=\s*((?:[^;]|\([^;]*\))+);"""
    ).replace(result) { match ->
        if (!match.groupValues[1].contains("loot", ignoreCase = true)) return@replace match.value
        val expression = match.groupValues[2].trim()
        if (expression.startsWith("ResourceKey.create(")) {
            match.value
        } else {
            "this.${match.groupValues[1]} = ResourceKey.create(Registries.LOOT_TABLE, $expression);"
        }
    }
    result = Regex(
        """private\s+static\s+ResourceLocation\s+register\s*\(\s*String\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*return\s+register\(\s*((?:[^;]|\([^;]*\))+?)\s*\);\s*\}""",
        RegexOption.DOT_MATCHES_ALL
    ).replace(result) { match ->
        val idVar = match.groupValues[1]
        val expression = match.groupValues[2].trim()
        val keyExpression = if (expression.startsWith("ResourceKey.create(")) {
            expression
        } else {
            "ResourceKey.create(Registries.LOOT_TABLE, $expression)"
        }
        "private static ResourceKey<LootTable> register(String $idVar) {\n\t\treturn register($keyExpression);\n\t}"
    }
    result = Regex("""private\s+static\s+ResourceLocation\s+register\s*\(\s*ResourceLocation\s+([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match -> "private static ResourceKey<LootTable> register(ResourceKey<LootTable> ${match.groupValues[1]})" }
    val withoutResourceLocation = removeImport(result, "net.minecraft.resources.ResourceLocation")
    if (!Regex("""\bResourceLocation\b""").containsMatchIn(withoutResourceLocation)) {
        result = withoutResourceLocation
    }
    return result
}

internal fun StructuralRefactorPass.legacyResourceLocationSetAliases(source: String, setNames: Set<String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val pattern = Regex(
        """(?ms)^[ \t]*(?:public|protected|private)?\s*static\s+final\s+Set\s*<\s*ResourceLocation\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);"""
    )
    pattern.findAll(source).forEach { match ->
        val alias = match.groupValues[1]
        val initializer = match.groupValues[2]
        setNames
            .firstOrNull { target -> target != alias && Regex("""\b${Regex.escape(target)}\b""").containsMatchIn(initializer) }
            ?.let { target -> result[alias] = target }
    }
    return result
}

internal fun StructuralRefactorPass.legacyResourceLocationSetReturnMethods(source: String, setNames: Set<String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val pattern = Regex(
        """(?ms)\b(?:public|protected|private)?\s*static\s+Set\s*<\s*ResourceLocation\s*>\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{\s*return\s+([A-Za-z_$][\w$]*)\s*;\s*}"""
    )
    pattern.findAll(source).forEach { match ->
        val methodName = match.groupValues[1]
        val fieldName = match.groupValues[2]
        if (fieldName in setNames) {
            result[methodName] = fieldName
        }
    }
    return result
}

internal fun StructuralRefactorPass.legacyResourceLocationRegisterSets(source: String, setNames: Set<String>): Set<String> {
    val result = linkedSetOf<String>()
    val registerPattern = Regex(
        """private\s+static\s+ResourceLocation\s+register\s*\(\s*ResourceLocation\s+([A-Za-z_$][\w$]*)\s*\)\s*\{""",
        RegexOption.DOT_MATCHES_ALL
    )
    registerPattern.findAll(source).forEach { match ->
        val parameter = match.groupValues[1]
        val openBrace = source.indexOf('{', match.range.last)
        if (openBrace < 0) return@forEach
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return@forEach
        val body = source.substring(openBrace + 1, closeBrace)
        Regex("""\b([A-Za-z_$][\w$]*)\.add\s*\(\s*${Regex.escape(parameter)}\s*\)""")
            .findAll(body)
            .map { it.groupValues[1] }
            .filterTo(result) { it in setNames }
    }
    return result
}

internal fun StructuralRefactorPass.legacyResourceLocationArrayToList(
    expression: String,
    migrateEntry: (String) -> String
): String? {
    val trimmed = expression.trim()
    if (Regex("""new\s+ResourceLocation\s*\[\s*0\s*]\s*""").matches(trimmed)) {
        return "java.util.List.of()"
    }
    val arrayMatch = Regex("""new\s+ResourceLocation\s*\[\s*]\s*\{(.*)}""", RegexOption.DOT_MATCHES_ALL)
        .matchEntire(trimmed)
        ?: return null
    val entries = splitTopLevelJavaArgs(arrayMatch.groupValues[1])
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (entries.isEmpty()) return "java.util.List.of()"
    return entries.joinToString(prefix = "java.util.List.of(", postfix = ")") { migrateEntry(it) }
}

internal fun StructuralRefactorPass.isLootTableResourceKeyExpression(
    expression: String,
    legacyLootTableResourceLocationReferences: Set<String> = emptySet()
): Boolean {
    val trimmed = expression.trim()
    return trimmed.contains("ResourceKey.create(") ||
        trimmed.startsWith("BuiltInLootTables.") ||
        trimmed.endsWith(".getDefaultLootTable()") ||
        trimmed.endsWith(".getLootTable()") ||
        trimmed.endsWith(".getLootLocation()") ||
        trimmed.removeSuffix(".location()") in legacyLootTableResourceLocationReferences
}

internal fun StructuralRefactorPass.unwrapLootTableResourceKeyCreate(expression: String): String? {
    val trimmed = expression.trim()
    val prefix = "ResourceKey.create("
    if (!trimmed.startsWith(prefix) || !trimmed.endsWith(")")) return null
    val args = splitTopLevelJavaArgs(trimmed.substring(prefix.length, trimmed.length - 1))
    if (args.size != 2) return null
    if (args[0].trim() != "Registries.LOOT_TABLE") return null
    return args[1].trim()
}

internal fun StructuralRefactorPass.isLocalLootTableResourceKeyExpression(source: String, expression: String): Boolean {
    val trimmed = expression.trim()
    val memberCall = Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\s*\(\s*\)$""")
        .matchEntire(trimmed)
    if (memberCall != null) {
        val methodName = memberCall.groupValues[1]
        val resourceKeyMethodPattern = Regex(
            """\b(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s+)*(?:public|protected|private)?\s*(?:abstract\s+)?(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s+)*(?:net\.minecraft\.resources\.)?ResourceKey\s*<\s*(?:net\.minecraft\.world\.level\.storage\.loot\.)?LootTable\s*>\s+${Regex.escape(methodName)}\s*\("""
        )
        if (resourceKeyMethodPattern.containsMatchIn(source)) return true
        val legacyResourceLocationMethodPattern = Regex(
            """\b(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s+)*(?:public|protected|private)?\s*(?:abstract\s+)?(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s+)*(?:net\.minecraft\.resources\.)?ResourceLocation\s+${Regex.escape(methodName)}\s*\("""
        )
        if (methodName.contains("LootTable") && legacyResourceLocationMethodPattern.containsMatchIn(source)) return true
    }

    val simpleName = trimmed.substringAfterLast('.')
    if (!Regex("""[A-Za-z_$][\w$]*""").matches(simpleName)) return false
    val resourceKeyFieldPattern = Regex(
        """\b(?:(?:public|protected|private|static|final|transient|volatile)\s+)*(?:net\.minecraft\.resources\.)?ResourceKey\s*<\s*(?:net\.minecraft\.world\.level\.storage\.loot\.)?LootTable\s*>\s+${Regex.escape(simpleName)}\b"""
    )
    if (resourceKeyFieldPattern.containsMatchIn(source)) return true
    val legacyResourceLocationFieldPattern = Regex(
        """\b(?:(?:public|protected|private|static|final|transient|volatile)\s+)*(?:net\.minecraft\.resources\.)?ResourceLocation\s+${Regex.escape(simpleName)}\b"""
    )
    return simpleName.contains("LootTable") && legacyResourceLocationFieldPattern.containsMatchIn(source)
}

internal fun StructuralRefactorPass.migrateLegacySpawnEggItemTypeCalls(source: String): String {
    if (!source.contains("SpawnEggItem") || !source.contains(".getType(")) return source
    var result = Regex(
        """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.getType\(\s*([A-Za-z_$][\w$]*)\.getOrDefault\(\s*net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA\s*,\s*net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\s*\)\.copyTag\(\)\s*\)"""
    ).replace(source) { match -> "${match.groupValues[1]}.getType(${match.groupValues[2]})" }
    result = Regex(
        """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.getType\(\s*([A-Za-z_$][\w$]*)\.getTag\(\)\s*\)"""
    ).replace(result) { match -> "${match.groupValues[1]}.getType(${match.groupValues[2]})" }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyTriStateEventResults(source: String): String {
    val target = "net.neoforged.neoforge.common.util.TriState"
    val resultImport = Regex(
        """(?m)^import\s+net\.(?:minecraftforge\.eventbus|neoforged\.bus)\.api\.Event\.Result;[ \t]*\r?\n"""
    )
    val hasResultImport = resultImport.containsMatchIn(source)
    val triStateImports = Regex("""(?m)^import\s+([A-Za-z_$][\w$.]*\.TriState);[ \t]*\r?$""")
        .findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet()
    val targetRef = if (triStateImports.any { it != target }) target else "TriState"
    val targetConstants = mapOf(
        "DENY" to "FALSE",
        "ALLOW" to "TRUE",
        "DEFAULT" to "DEFAULT"
    )

    var result = resultImport.replace(source, "")
    val staticResultImport = Regex(
        """(?m)^import\s+static\s+net\.(?:minecraftforge\.eventbus|neoforged\.bus)\.api\.Event\.Result\.(DENY|ALLOW|DEFAULT);[ \t]*\r?\n"""
    )
    val staticallyImported = staticResultImport.findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    result = staticResultImport.replace(result) { match ->
        val replacement = targetConstants.getValue(match.groupValues[1])
        "import static $target.$replacement;\n"
    }
    staticallyImported.forEach { legacy ->
        val replacement = targetConstants.getValue(legacy)
        result = replaceExecutableRegex(
            result,
            Regex("""(?<![\w$.])${Regex.escape(legacy)}(?![\w$])""")
        ) { replacement }
    }
    targetConstants.forEach { (legacy, replacement) ->
        result = replaceExecutableRegex(
            result,
            Regex("""(?<![\w$.])Event\.Result\.${Regex.escape(legacy)}(?![\w$])""")
        ) { "$targetRef.$replacement" }
        if (hasResultImport) {
            result = replaceExecutableRegex(
                result,
                Regex("""(?<![\w$.])Result\.${Regex.escape(legacy)}(?![\w$])""")
            ) { "$targetRef.$replacement" }
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(?<![\w$.])Event\.Result(?=\s+[A-Za-z_$])""")
    ) { targetRef }
    if (hasResultImport) {
        result = replaceExecutableRegex(
            result,
            Regex("""(?<![\w$.])Result(?=\s+[A-Za-z_$])""")
        ) { targetRef }
    }
    if (result == source) return source
    // the bus has no Event.Result (bus 8 jar): values held in that type are the TriStates above
    // and the TriState getters of the 1.21.1 events (getUseBlock/getUseItem)
    // NeoForge events have no setResult; a DENY right after setCanceled(true) said the same thing.
    result = Regex(
        """(?m)^([ \t]*([A-Za-z_$][\w$]*)\.setCanceled\(\s*true\s*\);[ \t]*\r?\n)""" +
            """[ \t]*\2\.setResult\(\s*${Regex.escape(targetRef)}\.FALSE\s*\);[ \t]*\r?\n"""
    )
        .replace(result) { match -> match.groupValues[1] }
    if (targetRef == "TriState") {
        result = addImportIfMissing(result, "net.neoforged.neoforge.common.util.TriState")
    }
    val withoutEventImport = removeImport(result, "net.neoforged.bus.api.Event")
    if (!Regex("""\bEvent\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutEventImport))) {
        result = withoutEventImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateRightClickBlockTriStateResults(source: String): String {
    if (!source.contains("RightClickBlock") || !source.contains(".setResult(") || !source.contains("TriState.")) {
        return source
    }
    var result = source
    var changed = false
    var cursor = 0
    val id = """[A-Za-z_$][\w$]*"""
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s*\r?\n[ \t]*)*(?:(?:public|protected|private|static|final|synchronized)\s+)*(?:<[^>{};]+>\s*)?(?:[\w<>\[\].?,]+\s+)+$id\s*\([^;{}()]*\b(?:(?:[A-Za-z_$][\w$]*\.)*PlayerInteractEvent\.)?RightClickBlock\s+($id)\b[^;{}()]*\)\s*(?:throws\s+[^{]+)?\{"""
    )
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val eventVar = match.groupValues[1]
        val openBrace = result.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        var migratedBody = body
        migratedBody = replaceExecutableRegex(
            migratedBody,
            Regex("""(?m)^([ \t]*)\b${Regex.escape(eventVar)}\.setResult\(\s*TriState\.TRUE\s*\)\s*;""")
        ) { bodyMatch ->
            val indent = bodyMatch.groupValues[1]
            "${indent}$eventVar.setCancellationResult(InteractionResult.SUCCESS);\n${indent}$eventVar.setCanceled(true);"
        }
        migratedBody = replaceExecutableRegex(
            migratedBody,
            Regex("""(?m)^([ \t]*)\b${Regex.escape(eventVar)}\.setResult\(\s*TriState\.FALSE\s*\)\s*;""")
        ) { bodyMatch ->
            val indent = bodyMatch.groupValues[1]
            "${indent}$eventVar.setCancellationResult(InteractionResult.FAIL);\n${indent}$eventVar.setCanceled(true);"
        }
        migratedBody = replaceExecutableRegex(
            migratedBody,
            Regex("""(?m)^([ \t]*)\b${Regex.escape(eventVar)}\.setResult\(\s*TriState\.DEFAULT\s*\)\s*;""")
        ) { bodyMatch ->
            val indent = bodyMatch.groupValues[1]
            "${indent}$eventVar.setCanceled(false);"
        }
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            changed = true
            cursor = openBrace + 1 + migratedBody.length
        } else {
            cursor = closeBrace + 1
        }
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.world.InteractionResult")
    result = removeUnusedSimpleImports(
        result,
        listOf(
            "net.neoforged.neoforge.common.util.TriState",
            "net.neoforged.bus.api.Event"
        )
    )
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCancellableEventBaseClasses(source: String): String {
    if (!source.contains("extends Event") || !source.contains("ICancellableEvent")) return source
    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var changed = false
    val classHeader = Regex("""\b(public\s+(?:abstract\s+)?class\s+($id)\s+extends\s+Event)(\s+implements\s+([^({}\r\n]+))?\s*\{""")
    result = classHeader.replace(result) { match ->
        val className = match.groupValues[2]
        val implementsClause = match.groupValues[4]
        if (implementsClause.contains("ICancellableEvent")) return@replace match.value
        val childImplementsCancellation = Regex("""\bclass\s+$id\s+extends\s+${Regex.escape(className)}\s+implements\s+[^{}\r\n]*\bICancellableEvent\b""")
            .containsMatchIn(source)
        if (!childImplementsCancellation) return@replace match.value

        changed = true
        val header = match.groupValues[1]
        if (implementsClause.isBlank()) {
            "$header implements ICancellableEvent {"
        } else {
            "$header implements $implementsClause, ICancellableEvent {"
        }
    }
    return if (changed) addImportIfMissing(result, "net.neoforged.bus.api.ICancellableEvent") else result
}

internal fun StructuralRefactorPass.migrateLegacyFillBucketEventSource(source: String): String {
    if (!source.contains("FillBucketEvent") &&
        !(source.contains("PlayerInteractEvent.RightClickBlock") && source.contains(".setResult("))) return source
    val originalExecutable = maskJavaCommentsAndLiterals(source)
    val fillBucketEventVars = Regex("""\bFillBucketEvent\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(originalExecutable)
        .map { it.groupValues[1] }
        .toSet()
    val rightClickBlockEventVars = Regex("""\bPlayerInteractEvent\.RightClickBlock\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(originalExecutable)
        .map { it.groupValues[1] }
        .toSet()
    if (fillBucketEventVars.isEmpty() && rightClickBlockEventVars.isEmpty()) return source
    var result = source
        .replace(
            "import net.minecraftforge.event.entity.player.FillBucketEvent;",
            "import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;"
        )
        .replace(
            "import net.neoforged.neoforge.event.entity.player.FillBucketEvent;",
            "import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;"
        )
    result = replaceExecutableRegex(result, Regex("""\bFillBucketEvent\b""")) {
        "PlayerInteractEvent.RightClickBlock"
    }
    var changed = false
    var cursor = 0
    val methodPattern = Regex(
        """\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*\([^;{}()]*\bPlayerInteractEvent\.RightClickBlock\s+([A-Za-z_$][\w$]*)[^;{}()]*\)\s*(?:throws\s+[^{]+)?\{"""
    )
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val eventVar = match.groupValues[1]
        val isLegacyFillBucketEvent = eventVar in fillBucketEventVars
        val isRightClickBlockEvent = eventVar in rightClickBlockEventVars || isLegacyFillBucketEvent
        if (!isRightClickBlockEvent) {
            cursor = match.range.last + 1
            continue
        }
        val openBrace = result.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        var migratedBody = body
        if (isLegacyFillBucketEvent) {
            migratedBody = Regex("""\b${Regex.escape(eventVar)}\.getTarget\(\)""")
                .replace(migratedBody, "$eventVar.getHitVec()")
            migratedBody = Regex("""\b${Regex.escape(eventVar)}\.getEmptyBucket\(\)""")
                .replace(migratedBody, "$eventVar.getItemStack()")
            migratedBody = Regex("""\b${Regex.escape(eventVar)}\.setFilledBucket\(\s*([^;\r\n]+?)\s*\)\s*;""")
                .replace(migratedBody) { setMatch ->
                    "$eventVar.getEntity().setItemInHand($eventVar.getHand(), ${setMatch.groupValues[1].trim()});"
                }
        }
        migratedBody = Regex("""\b${Regex.escape(eventVar)}\.setResult\(\s*TriState\.TRUE\s*\)\s*;""")
            .replace(
                migratedBody,
                "$eventVar.setCancellationResult(InteractionResult.SUCCESS);\n        $eventVar.setCanceled(true);"
            )
        migratedBody = Regex("""\b${Regex.escape(eventVar)}\.setResult\(\s*TriState\.FALSE\s*\)\s*;""")
            .replace(
                migratedBody,
                "$eventVar.setCancellationResult(InteractionResult.FAIL);\n        $eventVar.setCanceled(true);"
            )
        migratedBody = Regex("""\b${Regex.escape(eventVar)}\.setResult\(\s*TriState\.DEFAULT\s*\)\s*;""")
            .replace(migratedBody, "$eventVar.setCanceled(false);")
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            changed = true
            cursor = openBrace + 1 + migratedBody.length
        } else {
            cursor = closeBrace + 1
        }
    }
    if (!changed) return result
    result = addImportIfMissing(result, "net.minecraft.world.InteractionResult")
    result = removeUnusedSimpleImports(
        result,
        listOf(
            "net.neoforged.neoforge.common.util.TriState",
            "net.neoforged.bus.api.Event"
        )
    )
    return result
}

internal fun StructuralRefactorPass.migrateLegacySleepingTimeCheckEventSource(source: String): String {
    if (!source.contains("SleepingTimeCheckEvent") &&
        !source.contains("SleepingLocationCheckEvent") &&
        !source.contains("CanPlayerSleepEvent")) return source
    val directLocationEventVars = Regex(
        """\bSleepingLocationCheckEvent\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet()
    var result = source
        .replace(
            "net.minecraftforge.event.entity.player.SleepingTimeCheckEvent",
            "net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent"
        )
        .replace(
            "net.neoforged.neoforge.event.entity.player.SleepingTimeCheckEvent",
            "net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent"
        )
        .replace(
            "net.minecraftforge.event.entity.player.SleepingLocationCheckEvent",
            "net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent"
        )
        .replace(
            "net.neoforged.neoforge.event.entity.player.SleepingLocationCheckEvent",
            "net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent"
        )
        .replace("SleepingTimeCheckEvent", "CanPlayerSleepEvent")
        .replace("SleepingLocationCheckEvent", "CanPlayerSleepEvent")

    var changed = false
    var cursor = 0
    val methodPattern = Regex(
        """\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*\([^;{}()]*\bCanPlayerSleepEvent\s+([A-Za-z_$][\w$]*)[^;{}()]*\)\s*(?:throws\s+[^{]+)?\{"""
    )
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val eventVar = match.groupValues[1]
        val openBrace = match.range.last
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        var migratedBody = body
        migratedBody = Regex("""\b${Regex.escape(eventVar)}\.setResult\(\s*TriState\.FALSE\s*\)""")
            .replace(migratedBody, "$eventVar.setProblem(net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_POSSIBLE_NOW)")
        migratedBody = Regex("""\b${Regex.escape(eventVar)}\.setResult\(\s*TriState\.TRUE\s*\)""")
            .replace(migratedBody, "$eventVar.setProblem(null)")
        migratedBody = Regex("""\b${Regex.escape(eventVar)}\.setResult\(\s*TriState\.DEFAULT\s*\)""")
            .replace(migratedBody, "$eventVar.setProblem($eventVar.getVanillaProblem())")
        migratedBody = Regex("""\b${Regex.escape(eventVar)}\.getSleepingLocation\(\)""")
            .replace(
                migratedBody,
                if (eventVar in directLocationEventVars) "$eventVar.getPos()" else "java.util.Optional.of($eventVar.getPos())"
            )
        migratedBody = Regex("""\b${Regex.escape(eventVar)}\.getPos\(\)\.isPresent\(\)\s*&&\s*""")
            .replace(migratedBody, "")
        migratedBody = Regex("""\b${Regex.escape(eventVar)}\.getPos\(\)\.getValue\(\)""")
            .replace(migratedBody, "$eventVar.getPos()")
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            changed = true
            cursor = openBrace + 1 + migratedBody.length
        } else {
            cursor = closeBrace + 1
        }
    }

    if (changed) {
        result = removeUnusedSimpleImports(
            result,
            listOf(
                "net.neoforged.neoforge.common.util.TriState",
                "net.neoforged.bus.api.Event"
            )
        )
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCancellableEventHelperParameters(source: String): String {
    if (!source.contains(".isCanceled()") && !source.contains(".setCanceled(")) {
        return source
    }
    var result = source
    var cursor = 0
    var changed = false
    val supportedBroadEventTypes = setOf(
        "Event",
        "EntityEvent",
        "LivingEvent",
        "PlayerEvent",
        "PlayerInteractEvent"
    )
    val signaturePattern = Regex(
        """\b(private|public|protected)\s+(static\s+)?void\s+([A-Za-z_$][\w$]*)\(\s*((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s+([A-Za-z_$][\w$]*)\s*([,)])"""
    )
    while (true) {
        val match = signaturePattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last + 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val annotationStart = annotationStartBefore(result, match.range.first)
        if (result.substring(annotationStart, match.range.first).contains("@SubscribeEvent")) {
            cursor = closeBrace + 1
            continue
        }
        val parameterType = match.groupValues[4]
        val simpleParameterType = parameterType.substringAfterLast('.')
        if (simpleParameterType == "ICancellableEvent" || simpleParameterType !in supportedBroadEventTypes) {
            cursor = closeBrace + 1
            continue
        }
        val parameter = match.groupValues[5]
        val methodText = result.substring(match.range.first, closeBrace + 1)
        if (Regex("""\b${Regex.escape(parameter)}\.(?:isCanceled\(\)|setCanceled\()""").containsMatchIn(methodText) &&
            !Regex("""\b${Regex.escape(parameter)}\.(?!isCanceled\(\)|setCanceled\()""").containsMatchIn(methodText)
        ) {
            val replacement = "${match.groupValues[1]} ${match.groupValues[2]}void ${match.groupValues[3]}(ICancellableEvent $parameter${match.groupValues[6]}"
            result = result.substring(0, match.range.first) + replacement + result.substring(match.range.last + 1)
            cursor = match.range.first + replacement.length
            changed = true
        } else {
            cursor = closeBrace + 1
        }
    }
    if (changed) {
        result = addImportIfMissing(result, "net.neoforged.bus.api.ICancellableEvent")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCancellableEventHelperCallSites(source: String): String {
    if (!source.contains("ICancellableEvent") || !source.contains("instanceof PlayerInteractEvent.")) return source
    val helperNames = Regex(
        """\b(?:private|public|protected)\s+(?:static\s+)?void\s+([A-Za-z_$][\w$]*)\s*\(\s*ICancellableEvent\s+[A-Za-z_$][\w$]*\s*(?:,|\))"""
    ).findAll(source).map { it.groupValues[1] }.toSet()
    if (helperNames.isEmpty()) return source

    var result = source
    var cursor = 0
    val branchPattern = Regex(
        """\bif\s*\(\s*([A-Za-z_$][\w$]*)\s+instanceof\s+PlayerInteractEvent(?:\.[A-Za-z_$][\w$]*)+\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    while (true) {
        val match = branchPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val baseEvent = match.groupValues[1]
        val narrowedEvent = match.groupValues[2]
        var body = result.substring(openBrace + 1, closeBrace)
        val originalBody = body
        for (helperName in helperNames) {
            body = Regex("""\b${Regex.escape(helperName)}\s*\(\s*${Regex.escape(baseEvent)}\s*([,)])""")
                .replace(body) { helperMatch -> "$helperName($narrowedEvent${helperMatch.groupValues[1]}" }
        }
        if (body != originalBody) {
            result = result.substring(0, openBrace + 1) + body + result.substring(closeBrace)
            cursor = openBrace + body.length + 1
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal data class PlayerInteractBranch(
    val subclass: String,
    val variable: String,
    val body: String
)

internal fun StructuralRefactorPass.splitLegacyAbstractPlayerInteractSubscribers(source: String): String {
    if (!source.contains("@SubscribeEvent") ||
        !source.contains("PlayerInteractEvent ") ||
        !source.contains("instanceof PlayerInteractEvent.")) {
        return source
    }

    var result = source
    var cursor = 0
    val signaturePattern = Regex(
        """(?m)^([ \t]*)@SubscribeEvent\s*\r?\n\1((?:(?:public|protected|private|static|final)\s+)*)void\s+([A-Za-z_$][\w$]*)\s*\(\s*PlayerInteractEvent\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    while (true) {
        val match = signaturePattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }

        val indent = match.groupValues[1]
        val modifiers = match.groupValues[2]
        val methodName = match.groupValues[3]
        val eventVariable = match.groupValues[4]
        val body = result.substring(openBrace + 1, closeBrace)
        val branches = playerInteractInstanceofBranches(body, eventVariable)
        if (branches == null || branches.isEmpty()) {
            cursor = closeBrace + 1
            continue
        }

        val replacement = branches.joinToString(System.lineSeparator() + System.lineSeparator()) { branch ->
            val branchBody = Regex("""\b${Regex.escape(eventVariable)}\b""")
                .replace(branch.body.trim('\r', '\n'), branch.variable)
            buildString {
                append(indent).append("@SubscribeEvent").append(System.lineSeparator())
                append(indent)
                append(modifiers)
                append("void ")
                append(methodName)
                append("(PlayerInteractEvent.")
                append(branch.subclass)
                append(" ")
                append(branch.variable)
                append(") {")
                append(branchBody.trimEnd().prependIndent(System.lineSeparator()).trimEnd())
                append(System.lineSeparator())
                append(indent).append("}")
            }
        }

        result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        cursor = match.range.first + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.playerInteractInstanceofBranches(body: String, eventVariable: String): List<PlayerInteractBranch>? {
    val branches = mutableListOf<PlayerInteractBranch>()
    var cursor = 0
    var firstBranch = true
    while (true) {
        cursor = skipWhitespace(body, cursor)
        if (cursor >= body.length) break
        val branchStart = Regex(
            if (firstBranch) {
                """\Gif\s*\(\s*${Regex.escape(eventVariable)}\s+instanceof\s+PlayerInteractEvent\.([A-Za-z_$][\w$]*)\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
            } else {
                """\Gelse\s+if\s*\(\s*${Regex.escape(eventVariable)}\s+instanceof\s+PlayerInteractEvent\.([A-Za-z_$][\w$]*)\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
            }
        ).find(body, cursor) ?: return null
        val openBrace = body.indexOf('{', branchStart.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(body, openBrace) else -1
        if (closeBrace <= openBrace) return null
        branches.add(PlayerInteractBranch(
            subclass = branchStart.groupValues[1],
            variable = branchStart.groupValues[2],
            body = body.substring(openBrace + 1, closeBrace)
        ))
        cursor = closeBrace + 1
        firstBranch = false
    }
    return branches
}

internal fun StructuralRefactorPass.migrateAbstractRenderLivingSubscribers(source: String): String {
    if (!source.contains("@SubscribeEvent") ||
        (!source.contains("RenderLivingEvent") &&
            !source.contains("RenderPlayerEvent") &&
            !source.contains("RenderGuiLayerEvent"))) {
        return source
    }

    var result = source
    val renderEventSpecs = listOf(
        Triple("""RenderLivingEvent\s*(?:<\s*\?\s*,\s*\?\s*>)?""", "RenderLivingEvent.Pre<?, ?>", true),
        Triple("""RenderPlayerEvent""", "RenderPlayerEvent.Pre", false),
        Triple("""RenderGuiLayerEvent""", "RenderGuiLayerEvent.Pre", false)
    )
    for ((eventTypePattern, preEventType, useLivingHeuristic) in renderEventSpecs) {
        var cursor = 0
        val signaturePattern = Regex(
            """(?m)^([ \t]*)@SubscribeEvent(?:\([^)]*\))?\s*\r?\n\1((?:(?:public|protected|private|static|final)\s+)*)void\s+([A-Za-z_$][\w$]*)\s*\(\s*($eventTypePattern)\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
        )
        while (true) {
            val match = signaturePattern.find(result, cursor) ?: break
            if (match.groupValues[4].contains(".Pre")) {
                cursor = match.range.last + 1
                continue
            }
            val openBrace = result.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (closeBrace <= openBrace) {
                cursor = match.range.last + 1
                continue
            }

            val eventVariable = match.groupValues[5]
            val body = result.substring(openBrace + 1, closeBrace)
            val usesCancellationApi = Regex("""\b${Regex.escape(eventVariable)}\.(?:setCanceled|isCanceled)\s*\(""")
                .containsMatchIn(body)
            val requiresPre = usesCancellationApi ||
                (useLivingHeuristic && isRenderLivingPreSubscriberBody(body, eventVariable))
            if (!requiresPre) {
                cursor = closeBrace + 1
                continue
            }

            val typeRange = match.groups[4]?.range
            if (typeRange == null) {
                cursor = closeBrace + 1
                continue
            }
            result = result.substring(0, typeRange.first) +
                preEventType +
                result.substring(typeRange.last + 1)
            cursor = typeRange.first + preEventType.length
        }
    }
    return result
}

internal fun StructuralRefactorPass.isRenderLivingPreSubscriberBody(body: String, eventVariable: String): Boolean {
    if (Regex("""\b${Regex.escape(eventVariable)}\.setCanceled\s*\(""").containsMatchIn(body)) return true
    val usesRendererModel = Regex("""\b${Regex.escape(eventVariable)}\.getRenderer\s*\(\s*\)\.getModel\s*\(""")
        .containsMatchIn(body)
    val mutatesModelVisibility = Regex("""\.(?:visible|xRot|yRot|zRot)\s*=""").containsMatchIn(body)
    return usesRendererModel && mutatesModelVisibility
}

internal fun StructuralRefactorPass.skipWhitespace(source: String, start: Int): Int {
    var index = start
    while (index < source.length && source[index].isWhitespace()) {
        index++
    }
    return index
}

internal fun StructuralRefactorPass.migrateLegacyCreativeTabEnchantmentInstances(source: String): String {
    val executableSource = maskJavaCommentsAndLiterals(source)
    if (!executableSource.contains("EnchantmentInstance(") &&
        !executableSource.contains(".ENCHANTMENTS.getEntries()") &&
        !executableSource.contains("Enchantments.")
    ) {
        return source
    }

    var result = migrateLegacyEnchantmentConstantNames(source)
    var changed = false

    val initialExecutableCode = maskJavaCommentsAndLiterals(result)
    val displayParametersName = Regex("""displayItems\s*\(\s*\(\s*([A-Za-z_$][\w$]*)\s*,""")
        .find(initialExecutableCode)
        ?.groupValues
        ?.get(1)
        ?: Regex("""CreativeModeTab\.ItemDisplayParameters\s+([A-Za-z_$][\w$]*)""")
            .find(initialExecutableCode)
            ?.groupValues
            ?.get(1)
        ?: "parameters"

    if (maskJavaCommentsAndLiterals(result).contains("new EnchantmentInstance(Enchantments.")) {
        result = rewriteExecutableJavaNew(result, "EnchantmentInstance") { args ->
            if (args.size < 2) return@rewriteExecutableJavaNew null
            val enchantment = args[0].trim()
            if (!Regex("""^Enchantments\.[A-Z0-9_]+$""").matches(enchantment)) return@rewriteExecutableJavaNew null
            val migratedArgs = args.toMutableList()
            migratedArgs[0] = "$displayParametersName.holders().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow($enchantment)"
            changed = true
            "new EnchantmentInstance(${migratedArgs.joinToString(", ")})"
        }
    }

    val helperNames = mutableSetOf<String>()
    val helperPattern = Regex(
        """\b(private|public|protected)\s+(static\s+)?void\s+([A-Za-z_$][\w$]*)\s*\(\s*CreativeModeTab\.Output\s+([A-Za-z_$][\w$]*)\s*,\s*CreativeModeTab\.TabVisibility\s+([A-Za-z_$][\w$]*)\s*\)"""
    )
    var cursor = 0
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val match = helperPattern.find(executableCode, cursor) ?: break
        val openBrace = executableCode.indexOf('{', match.range.last + 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val methodBody = executableCode.substring(openBrace + 1, closeBrace)
        if (methodBody.contains(".ENCHANTMENTS.getEntries()")) {
            val replacement = "${match.groupValues[1]} ${match.groupValues[2]}void ${match.groupValues[3]}(CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output ${match.groupValues[4]}, CreativeModeTab.TabVisibility ${match.groupValues[5]})"
            result = result.substring(0, match.range.first) + replacement + result.substring(match.range.last + 1)
            helperNames += match.groupValues[3]
            changed = true
            cursor = match.range.first + replacement.length
        } else {
            cursor = closeBrace + 1
        }
    }

    for (helperName in helperNames) {
        result = rewriteExecutableJavaInvocationArguments(result, helperName) { args ->
            if (args.size != 2) return@rewriteExecutableJavaInvocationArguments null
            if (args[0].contains("ItemDisplayParameters") || args[0] == displayParametersName) {
                return@rewriteExecutableJavaInvocationArguments null
            }
            changed = true
            listOf(displayParametersName, args[0], args[1])
        }
    }

    if (maskJavaCommentsAndLiterals(result).contains(".ENCHANTMENTS.getEntries()")) {
        val loopVariables = mutableSetOf<String>()
        val loopPattern = Regex(
            """(?m)^([ \t]*)for\s*\(\s*[^:\r\n]+?\s+([A-Za-z_$][\w$]*)\s*:\s*((?:[A-Za-z_$][\w$]*\.)*ENCHANTMENTS)\.getEntries\(\)\s*\)\s*\{"""
        )
        result = replaceExecutableJavaRegex(result, loopPattern) { match ->
            val indent = match.groupValues[1]
            val variable = match.groupValues[2]
            val entriesOwner = match.groupValues[3]
            val keyVariable = "${variable}Key"
            loopVariables += variable
            changed = true
            "${indent}for (ResourceKey<Enchantment> $keyVariable : $entriesOwner) {\n" +
                "${indent}    Holder<Enchantment> $variable = parameters.holders().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow($keyVariable);"
        }
        for (variable in loopVariables) {
            result = replaceExecutableJavaRegex(result, Regex("""\b${Regex.escape(variable)}\.get\(\)\.(get(?:Max|Min)Level)\(\)""")) { match ->
                "$variable.value().${match.groupValues[1]}()"
            }
            result = replaceExecutableJavaRegex(result, Regex("""new\s+EnchantmentInstance\(\s*${Regex.escape(variable)}\.get\(\)\s*,""")) {
                "new EnchantmentInstance($variable,"
            }
            result = replaceExecutableJavaRegex(result, Regex("""\b${Regex.escape(variable)}\.get\(\)""")) {
                variable
            }
        }
    }

    if (!changed && result == source) return source
    if (result.contains("lookupOrThrow(Registries.ENCHANTMENT)")) {
        result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
    }
    if (result.contains("Holder<Enchantment>")) {
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
        result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.Enchantment")
    }
    if (result.contains("ResourceKey<Enchantment>")) {
        result = addImportIfMissing(result, "net.minecraft.resources.ResourceKey")
        result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.Enchantment")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyHolderAccessors(source: String): String {
    val executableSource = maskJavaCommentsAndLiterals(source)
    if (!executableSource.contains("Holder") &&
        !executableSource.contains(".getHolder().get()") &&
        !executableSource.contains(".getHolder().orElseThrow()") &&
        !executableSource.contains(".getEffect().") &&
        !executableSource.contains(".getBiome(")
    ) {
        return source
    }
    var result = replaceExecutableJavaRegex(source, Regex("""\.getHolder\(\)\.orElseThrow\(\)""")) {
        ".getDelegate()"
    }
    result = replaceExecutableJavaRegex(result, Regex("""\.getHolder\(\)\.get\(\)""")) {
        ".getDelegate()"
    }

    val holderVariables = Regex("""\bHolder\s*<[^>\r\n]+>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
    for (variable in holderVariables) {
        result = replaceExecutableJavaRegex(
            result,
            Regex("""\bthis\.${Regex.escape(variable)}\.get\(\)""")
        ) {
            "this.$variable.value()"
        }
        result = replaceExecutableJavaRegex(
            result,
            Regex("""(?<![\w$.\]])\b${Regex.escape(variable)}\.get\(\)""")
        ) {
            "$variable.value()"
        }
    }

    val holderBlockVariables = Regex("""\bHolder\s*<\s*Block\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toMutableSet()
    Regex("""BuiltInRegistries\.BLOCK\.getOrCreateTag\([^;\r\n]*?\)\.forEach\(\s*([A-Za-z_$][\w$]*)\s*->""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .forEach { holderBlockVariables += it.groupValues[1] }
    for (variable in holderBlockVariables) {
        result = replaceExecutableJavaRegex(
            result,
            Regex("""(?<![\w$.\]])\b${Regex.escape(variable)}\.defaultBlockState\(\)""")
        ) {
            "$variable.value().defaultBlockState()"
        }
    }
    val blockMapVariables = Regex("""\b(?:Map|HashMap|LinkedHashMap|ConcurrentHashMap)\s*<\s*Block\s*,\s*Block\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
    for (mapVariable in blockMapVariables) {
        result = rewriteExecutableJavaInvocationArguments(result, "$mapVariable.put") { args ->
            if (args.size != 2) return@rewriteExecutableJavaInvocationArguments null
            args.map { arg ->
                val trimmed = arg.trim()
                if (trimmed in holderBlockVariables) "$trimmed.value()" else arg
            }.takeIf { it != args }
        }
    }

    result = replaceExecutableJavaRegex(
        result,
        Regex("""(\.getBiome\((?:[^()]|\([^()]*\))*\))\.get\(\)""")
    ) { match ->
        "${match.groupValues[1]}.value()"
    }
    result = replaceExecutableJavaRegex(result, Regex("""\.getEffect\(\)\.isInstantenous\(""")) {
        ".getEffect().value().isInstantenous("
    }
    result = replaceExecutableJavaRegex(result, Regex("""\.getEffect\(\)\.applyInstantenousEffect\(""")) {
        ".getEffect().value().applyInstantenousEffect("
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyItemConstructorsAndProperties(source: String): String {
    val executableSource = maskJavaCommentsAndLiterals(source)
    if (!executableSource.contains("new SwordItem(") &&
        !executableSource.contains("super(") &&
        !executableSource.contains("new RecordItem(") &&
        !executableSource.contains(".meat()") &&
        !executableSource.contains("new BowlFoodItem(")
    ) {
        return source
    }
    // FoodProperties.Builder#meat() is gone in 1.21 (meat is no longer a food flag): a statement
    // that only called it disappears, a chained call just loses the link. The statement pattern
    // recurses per character (nested groups), so it only runs on sources that mention meat().
    var result = source
    if (executableSource.contains(".meat()")) {
        result = replaceExecutableJavaRegex(
            result,
            Regex("""(?m)^[ \t]*(?:if\s*\((?:[^()]|\([^()]*\))*\)\s*)?[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\((?:[^()]|\([^()]*\))*\))?)*\.meat\(\)\s*;[ \t]*(?:\r?\n)?""")
        ) { "" }
        result = replaceExecutableJavaRegex(result, Regex("""\.meat\(\)""")) { "" }
    }
    if (maskJavaCommentsAndLiterals(result).contains("new BowlFoodItem(")) {
        result = replaceExecutableJavaRegex(result, Regex("""\bnew\s+BowlFoodItem\s*\(""")) {
            "new Item("
        }
        if (!maskJavaCommentsAndLiterals(result).contains("BowlFoodItem")) {
            result = removeImport(result, "net.minecraft.world.item.BowlFoodItem")
        }
        result = addImportIfMissing(result, "net.minecraft.world.item.Item")
    }
    if (Regex("""(?m)^\s*import\s+net\.minecraft\.world\.item\.SwordItem\s*;""")
            .containsMatchIn(maskJavaCommentsAndLiterals(result))) {
        result = rewriteExecutableJavaNew(result, "SwordItem") { args ->
            if (args.size != 4) return@rewriteExecutableJavaNew null
            val tier = args[0].trim()
            val damage = args[1].trim()
            val speed = args[2].trim()
            val properties = args[3].trim()
            "new SwordItem($tier, $properties.attributes(SwordItem.createAttributes($tier, $damage, $speed)))"
        }
    }
    result = migrateLegacyRecordItemRegistrations(result)
    return migrateLegacyToolItemSuperConstructors(result)
}

private fun StructuralRefactorPass.migrateLegacyToolItemSuperConstructors(source: String): String {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parsed = parser.parse(source)
    val cu = parsed.result.orElseThrow {
        IllegalStateException(
            "Cannot parse legacy tool item constructors: ${parsed.problems.joinToString()}"
        )
    }
    val importedOwners = cu.imports
        .filter { !it.isStatic && !it.isAsterisk }
        .associateBy { it.nameAsString }
    val toolOwners = linkedMapOf(
        "SwordItem" to "net.minecraft.world.item.SwordItem",
        "PickaxeItem" to "net.minecraft.world.item.PickaxeItem",
        "AxeItem" to "net.minecraft.world.item.AxeItem",
        "HoeItem" to "net.minecraft.world.item.HoeItem",
        "ShovelItem" to "net.minecraft.world.item.ShovelItem"
    )
    LexicalPreservingPrinter.setup(cu)
    var changed = false
    cu.findAll(ClassOrInterfaceDeclaration::class.java).forEach typeLoop@{ type ->
        val extended = type.extendedTypes.singleOrNull()?.nameWithScope ?: return@typeLoop
        val simpleOwner = toolOwners.entries.singleOrNull { (simple, qualified) ->
            extended == qualified || extended == simple && qualified in importedOwners
        }?.key ?: return@typeLoop
        type.constructors.forEach constructorLoop@{ constructor ->
            val superCall = constructor.body.statements.firstOrNull() as? ExplicitConstructorInvocationStmt
                ?: return@constructorLoop
            if (superCall.isThis || superCall.arguments.size != 4) return@constructorLoop
            val tier = superCall.arguments[0].clone()
            val damage = superCall.arguments[1].clone()
            val speed = superCall.arguments[2].clone()
            val properties = superCall.arguments[3].clone()
            val attributeOwner = if (simpleOwner == "SwordItem") {
                NameExpr("SwordItem")
            } else {
                "net.minecraft.world.item.DiggerItem".split('.').drop(1)
                    .fold(NameExpr("net") as Expression) { scope, part ->
                        FieldAccessExpr(scope, part)
                    }
            }
            val attributes = MethodCallExpr(attributeOwner, "createAttributes")
                .addArgument(tier.clone())
                .addArgument(damage)
                .addArgument(speed)
            val propertiesWithAttributes = MethodCallExpr(properties, "attributes").addArgument(attributes)
            superCall.arguments.clear()
            superCall.addArgument(tier)
            superCall.addArgument(propertiesWithAttributes)
            changed = true
        }
    }
    return if (changed) LexicalPreservingPrinter.print(cu) else source
}

internal fun StructuralRefactorPass.migrateLegacyBowlFoodItemSubclasses(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    if (javaFiles.isEmpty()) return emptyList()

    val inheritanceIndex = collectJavaInheritanceIndex(javaFiles)
    val bowlItemClasses = collectJavaClassNamesExtending(javaFiles, setOf("BowlFoodItem"), inheritanceIndex)
    if (bowlItemClasses.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    for (javaFile in javaFiles) {
        val original = javaFile.readText()
        var migrated = original
        var changedClass = false
        var changedRegistration = false

        if (maskJavaCommentsAndLiterals(migrated).contains("BowlFoodItem")) {
            migrated = replaceExecutableJavaRegex(
                migrated,
                Regex("""\bextends\s+(?:net\.minecraft\.world\.item\.)?BowlFoodItem\b""")
            ) {
                changedClass = true
                "extends Item"
            }
            if (changedClass) {
                migrated = removeImport(migrated, "net.minecraft.world.item.BowlFoodItem")
                migrated = addImportIfMissing(migrated, "net.minecraft.world.item.Item")
            }
        }

        for (className in bowlItemClasses) {
            if (!maskJavaCommentsAndLiterals(migrated).contains("new $className(")) continue
            migrated = rewriteExecutableJavaNew(migrated, className) { args ->
                val migratedArgs = args.map { addBowlReturnToFoodPropertiesBuilders(it) }
                if (migratedArgs == args) return@rewriteExecutableJavaNew null
                changedRegistration = true
                "new $className(${migratedArgs.joinToString(", ") { it.trim() }})"
            }
        }

        if (changedRegistration && migrated.contains("Items.BOWL")) {
            migrated = addImportIfMissing(migrated, "net.minecraft.world.item.Items")
        }

        if (migrated != original) {
            if (changedClass) {
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Migrate BowlFoodItem subclass to Item with food-component bowl remainder semantics",
                    before = "extends BowlFoodItem",
                    after = "extends Item",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-bowl-food-item-subclass"
                ))
            }
            if (changedRegistration) {
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Preserve BowlFoodItem return-container semantics on FoodProperties builders",
                    before = "new BowlFoodItemSubclass(... food(builder.build()))",
                    after = "food(builder.usingConvertsTo(Items.BOWL).build())",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-bowl-food-item-food-properties"
                ))
            }
            if (!dryRun) {
                javaFile.writeText(migrated)
            }
        }
    }
    return changes
}

internal fun StructuralRefactorPass.addBowlReturnToFoodPropertiesBuilders(expression: String): String {
    if (!expression.contains("FoodProperties.Builder") || !expression.contains(".build()")) return expression
    val executable = maskJavaCommentsAndLiterals(expression)
    val edits = mutableListOf<Pair<IntRange, String>>()
    Regex("""\.build\s*\(\s*\)""").findAll(executable).forEach { buildCall ->
        val prefix = executable.substring(0, buildCall.range.first)
        val builderStart = listOf(
            prefix.lastIndexOf("new FoodProperties.Builder()"),
            prefix.lastIndexOf("new net.minecraft.world.food.FoodProperties.Builder()")
        ).max()
        if (builderStart < 0) return@forEach
        val chain = prefix.substring(builderStart)
        if (chain.contains(".usingConvertsTo(")) return@forEach
        edits += buildCall.range.first until buildCall.range.first to ".usingConvertsTo(Items.BOWL)"
    }
    if (edits.isEmpty()) return expression
    return applyStringEdits(expression, edits)
}

internal fun StructuralRefactorPass.migrateLegacyTierLevelSource(source: String): String {
    if (!source.contains("implements Tier") || !source.contains("getLevel()")) return source
    val enumName = Regex("""\benum\s+([A-Za-z_$][\w$]*)\s+implements\s+[^{};]*\bTier\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return source
    val levelMethodRegex = Regex(
        """(?s)([ \t]*@Override\s*\r?\n)?[ \t]*public\s+int\s+getLevel\s*\(\s*\)\s*\{\s*return\s+(?:this\.)?([A-Za-z_$][\w$]*)\s*;\s*\}\s*"""
    )
    val levelMethod = levelMethodRegex.find(source) ?: return source
    val legacyLevelField = levelMethod.groupValues[2]
    val fieldRegex = Regex("""(?m)^([ \t]*)private\s+final\s+int\s+${Regex.escape(legacyLevelField)}\s*;\s*$""")
    if (!fieldRegex.containsMatchIn(source)) return source

    val constructorRegex = Regex("""\b${Regex.escape(enumName)}\s*\(([^)]*)\)""")
    val constructorMatch = constructorRegex.find(source) ?: return source
    val constructorArgs = splitTopLevelJavaArgs(constructorMatch.groupValues[1])
    val legacyLevelArgIndex = constructorArgs.indexOfFirst { Regex("""\bint\s+${Regex.escape(legacyLevelField)}\b""").containsMatchIn(it) }
    if (legacyLevelArgIndex < 0) return source

    val assignmentRegex = Regex("""this\.${Regex.escape(legacyLevelField)}\s*=\s*${Regex.escape(legacyLevelField)}\s*;""")
    if (!assignmentRegex.containsMatchIn(source)) return source

    val constantsRewrite = rewriteLegacyTierEnumConstants(source, enumName, legacyLevelArgIndex) ?: return source
    var result = constantsRewrite
    result = fieldRegex.replace(result) { match ->
        "${match.groupValues[1]}private final TagKey<Block> incorrectBlocksForDrops;"
    }
    val migratedConstructor = constructorRegex.find(result) ?: return source
    val migratedConstructorArgs = splitTopLevelJavaArgs(migratedConstructor.groupValues[1]).toMutableList()
    if (legacyLevelArgIndex !in migratedConstructorArgs.indices) return source
    migratedConstructorArgs[legacyLevelArgIndex] = Regex("""\bint\s+${Regex.escape(legacyLevelField)}\b""")
        .replace(migratedConstructorArgs[legacyLevelArgIndex], "TagKey<Block> incorrectBlocksForDrops")
    val constructorReplacement = "$enumName(${migratedConstructorArgs.joinToString(", ")})"
    result = result.substring(0, migratedConstructor.range.first) +
        constructorReplacement +
        result.substring(migratedConstructor.range.last + 1)
    result = assignmentRegex.replace(result, "this.incorrectBlocksForDrops = incorrectBlocksForDrops;")
    val migratedLevelMethod = levelMethodRegex.find(result) ?: return source
    result = result.substring(0, migratedLevelMethod.range.first) +
        """
	@Override
	public TagKey<Block> getIncorrectBlocksForDrops() {
		return this.incorrectBlocksForDrops;
	}
""".trimIndent() + "\n\n" +
        result.substring(migratedLevelMethod.range.last + 1)
    result = addImportIfMissing(result, "net.minecraft.tags.BlockTags")
    result = addImportIfMissing(result, "net.minecraft.tags.TagKey")
    result = addImportIfMissing(result, "net.minecraft.world.level.block.Block")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyTierClassLevelSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("implements Tier") ||
        !executableCode.contains("getLevel()") ||
        executableCode.contains("getIncorrectBlocksForDrops()")) {
        return source
    }
    if (!Regex("""\bclass\s+[A-Za-z_$][\w$]*\s+implements\s+[^{};]*\bTier\b""").containsMatchIn(executableCode)) {
        return source
    }

    val levelMethodPattern = Regex(
        """(?s)\n([ \t]*)(@Override\s*\r?\n[ \t]*)?public\s+int\s+getLevel\s*\(\s*\)\s*\{\s*return\s+([^;]+?)\s*;\s*\}\s*"""
    )
    val levelMethod = levelMethodPattern.find(executableCode) ?: return source
    val legacyLevelExpression = levelMethod.groupValues[3].trim()
    val tagMethodPattern = Regex(
        """(?s)\n[ \t]*(@Override\s*\r?\n[ \t]*)?public\s+(?:@Nullable\s+)?TagKey\s*<\s*Block\s*>\s+getTag\s*\(\s*\)\s*\{\s*return\s+([^;]+?)\s*;\s*\}\s*"""
    )
    val tagMethod = tagMethodPattern.find(executableCode)
    val incorrectBlocksExpression = tagMethod
        ?.groupValues
        ?.get(2)
        ?.trim()
        ?.takeIf { it != "null" }
        ?: legacyTierMiningLevelTag(legacyLevelExpression)
        ?: return source

    var result = source
    if (tagMethod != null) {
        result = result.removeRange(tagMethod.range)
    }
    val migratedLevelMethod = levelMethodPattern.find(maskJavaCommentsAndLiterals(result)) ?: return source
    val indent = migratedLevelMethod.groupValues[1]
    val replacement = """

${indent}@Override
${indent}public TagKey<Block> getIncorrectBlocksForDrops() {
${indent}    return $incorrectBlocksExpression;
${indent}}
""".trimEnd()
    result = result.replaceRange(migratedLevelMethod.range, replacement)
    result = addImportIfMissing(result, "net.minecraft.tags.BlockTags")
    result = addImportIfMissing(result, "net.minecraft.tags.TagKey")
    result = addImportIfMissing(result, "net.minecraft.world.level.block.Block")
    val withoutNullable = removeImport(result, "org.jetbrains.annotations.Nullable")
    if (!maskJavaCommentsAndLiterals(withoutNullable).contains("@Nullable")) {
        result = withoutNullable
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacySimpleTierConstructors(source: String): String {
    if (!source.contains("new SimpleTier(")) return source
    return rewriteExecutableJavaNew(source, "SimpleTier") { args ->
        if (args.size != 7) return@rewriteExecutableJavaNew null
        val incorrectBlocksForDrops = args[5].trim()
        if (!Regex("""(?:[A-Za-z_$][\w$]*\.)*BlockTags\.[A-Z0-9_]+""")
                .matches(incorrectBlocksForDrops)) {
            return@rewriteExecutableJavaNew null
        }
        "new SimpleTier($incorrectBlocksForDrops, ${args[1].trim()}, ${args[2].trim()}, ${args[3].trim()}, ${args[4].trim()}, ${args[6].trim()})"
    }
}

internal fun StructuralRefactorPass.migrateSupplierIngredientCallArguments(source: String): String {
    if (!source.contains("Supplier<Ingredient>") && !source.contains("Supplier <Ingredient>")) return source
    val supplierPositionsByMethod = linkedMapOf<String, Set<Int>>()
    fun supplierPositions(parameters: List<JavaParameter>): Set<Int> =
        parameters.mapIndexedNotNull { index, param ->
            if (Regex("""(?:java\.util\.function\.)?Supplier\s*<\s*(?:net\.minecraft\.world\.item\.crafting\.)?Ingredient\s*>""")
                    .matches(param.type.trim())) {
                index
            } else {
                null
            }
        }.toSet()
    javaMethodRangesIncludingDefault(source).forEach { method ->
        val positions = supplierPositions(javaMethodParameters(source.substring(method.range)))
        if (positions.isNotEmpty()) supplierPositionsByMethod[method.name] = positions
    }
    Regex(
        """(?ms)^[ \t]*(?:public|protected|private)\s+(?:static\s+)?[^{;=]+?\s+([A-Za-z_$][\w$]*)\s*\(([^{};]*)\)\s*(?:throws\s+[^{;]+)?\{"""
    ).findAll(maskJavaCommentsAndLiterals(source)).forEach { match ->
        val parameters = parseJavaConstructorParameters(match.groupValues[2])
        val positions = supplierPositions(parameters)
        if (positions.isNotEmpty()) {
            supplierPositionsByMethod[match.groupValues[1]] = positions
        }
    }
    if (supplierPositionsByMethod.isEmpty()) return source

    var result = source
    supplierPositionsByMethod.forEach { (methodName, supplierPositions) ->
        result = rewriteExecutableJavaInvocationArgumentsWithOffset(result, methodName) { args, offset ->
            if (isJavaCallableDeclarationAt(result, offset, methodName)) {
                return@rewriteExecutableJavaInvocationArgumentsWithOffset null
            }
            if (supplierPositions.any { it >= args.size }) {
                return@rewriteExecutableJavaInvocationArgumentsWithOffset null
            }
            var changed = false
            val migratedArgs = args.mapIndexed { index, arg ->
                if (index !in supplierPositions) return@mapIndexed arg
                val trimmed = arg.trim()
                if (trimmed.startsWith("() ->") ||
                    trimmed.startsWith("Supplier.") ||
                    Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\s*::""").containsMatchIn(trimmed)) {
                    return@mapIndexed arg
                }
                if (trimmed == "Ingredient.EMPTY" ||
                    trimmed.startsWith("Ingredient.of(") ||
                    trimmed.startsWith("net.minecraft.world.item.crafting.Ingredient.")) {
                    changed = true
                    "() -> $trimmed"
                } else {
                    arg
                }
            }
            if (changed) migratedArgs else null
        }
    }
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyTierEnumConstants(source: String, enumName: String, levelArgIndex: Int): String? {
    val enumMatch = Regex("""\benum\s+${Regex.escape(enumName)}\b""").find(source) ?: return null
    val bodyStart = source.indexOf('{', enumMatch.range.last)
    if (bodyStart < 0) return null
    val bodyEnd = findMatchingBrace(source, bodyStart)
    if (bodyEnd < 0) return null
    var cursor = bodyStart + 1
    var constantsEnd = -1
    var parenDepth = 0
    var braceDepth = 0
    var bracketDepth = 0
    var angleDepth = 0
    var inString = false
    var escaped = false
    while (cursor < bodyEnd) {
        val ch = source[cursor]
        if (inString) {
            escaped = ch == '\\' && !escaped
            if (ch == '"' && !escaped) inString = false
            if (ch != '\\') escaped = false
            cursor++
            continue
        }
        when (ch) {
            '"' -> inString = true
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '<' -> angleDepth++
            '>' -> if (angleDepth > 0) angleDepth--
            ';' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0 && angleDepth == 0) {
                constantsEnd = cursor
                break
            }
        }
        cursor++
    }
    if (constantsEnd < 0) return null
    val constants = source.substring(bodyStart + 1, constantsEnd)
    var changed = false
    val rebuilt = StringBuilder()
    var segmentStart = 0
    parenDepth = 0
    braceDepth = 0
    bracketDepth = 0
    angleDepth = 0
    inString = false
    escaped = false
    for (i in constants.indices) {
        val ch = constants[i]
        if (inString) {
            escaped = ch == '\\' && !escaped
            if (ch == '"' && !escaped) inString = false
            if (ch != '\\') escaped = false
            continue
        }
        when (ch) {
            '"' -> inString = true
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '<' -> angleDepth++
            '>' -> if (angleDepth > 0) angleDepth--
            ',' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0 && angleDepth == 0) {
                val (rewritten, didChange) = rewriteLegacyTierEnumConstantSegment(constants.substring(segmentStart, i), levelArgIndex)
                rebuilt.append(rewritten).append(',')
                changed = changed || didChange
                segmentStart = i + 1
            }
        }
    }
    val (lastSegment, lastChanged) = rewriteLegacyTierEnumConstantSegment(constants.substring(segmentStart), levelArgIndex)
    rebuilt.append(lastSegment)
    changed = changed || lastChanged
    if (!changed) return null
    return source.substring(0, bodyStart + 1) + rebuilt + source.substring(constantsEnd)
}

internal fun StructuralRefactorPass.rewriteLegacyTierEnumConstantSegment(segment: String, levelArgIndex: Int): Pair<String, Boolean> {
    val match = Regex("""^(\s*)([A-Z][A-Z0-9_]*)\s*\(""").find(segment) ?: return segment to false
    val openParen = match.range.last
    val closeParen = findMatchingParen(segment, openParen)
    if (closeParen < 0) return segment to false
    val args = splitTopLevelJavaArgs(segment.substring(openParen + 1, closeParen)).toMutableList()
    if (levelArgIndex !in args.indices) return segment to false
    val replacement = legacyTierMiningLevelTag(args[levelArgIndex].trim()) ?: return segment to false
    args[levelArgIndex] = replacement
    val rewritten = segment.substring(0, openParen + 1) +
        args.joinToString(", ") +
        segment.substring(closeParen)
    return rewritten to true
}

internal fun StructuralRefactorPass.legacyTierMiningLevelTag(levelText: String): String? =
    when (levelText.toIntOrNull()) {
        0 -> "BlockTags.INCORRECT_FOR_WOODEN_TOOL"
        1 -> "BlockTags.INCORRECT_FOR_STONE_TOOL"
        2 -> "BlockTags.INCORRECT_FOR_IRON_TOOL"
        3 -> "BlockTags.INCORRECT_FOR_DIAMOND_TOOL"
        null -> null
        else -> "BlockTags.INCORRECT_FOR_NETHERITE_TOOL"
    }

internal fun StructuralRefactorPass.migrateLegacyRecordItemRegistrations(source: String): String {
    if (!source.contains("new RecordItem(") && !source.contains(".jukeboxPlayable(")) return source
    val modIdExpression = Regex(
        """DeferredRegister\s*<\s*Item\s*>\s+[A-Z][A-Z0-9_]*\s*=\s*DeferredRegister\.create\([^,\r\n]+,\s*([^)]+?)\s*\)"""
    ).find(source)?.groupValues?.get(1)?.trim()
        ?: Regex("""\b([A-Za-z_$][\w$]*\.(?:MODID|MOD_ID|ID))\b""").find(source)?.groupValues?.get(1)
        ?: return source
    var changed = false
    val result = Regex(
        """ITEMS\.register\(\s*"([^"]+)"\s*,\s*\(\s*\)\s*->\s*new\s+RecordItem\(\s*[^,]+,\s*[^,]+,\s*(new\s+Item\.Properties\(\)(?:\.[A-Za-z_$][\w$]*\([^)]*\))*)\s*,\s*[^)]+\)\s*\)"""
    ).replace(source) { match ->
        val name = match.groupValues[1]
        val properties = match.groupValues[2]
        changed = true
        "ITEMS.register(\"$name\", () -> new Item($properties.jukeboxPlayable(ResourceKey.create(Registries.JUKEBOX_SONG, ResourceLocation.fromNamespaceAndPath($modIdExpression, \"$name\")))))"
    }
    if (!changed) return source
    var migrated = addImportIfMissing(result, "net.minecraft.resources.ResourceKey")
    migrated = addImportIfMissing(migrated, "net.minecraft.core.registries.Registries")
    migrated = addImportIfMissing(migrated, "net.minecraft.resources.ResourceLocation")
    migrated = addImportIfMissing(migrated, "net.minecraft.world.item.Item")
    return migrated
}

internal fun StructuralRefactorPass.migrateLegacyCustomRecipeSource(source: String): String {
    if (!source.contains("extends CustomRecipe")) return source
    var result = source
    result = result.replace("super(id, category);", "super(category);")
    result = Regex(
        """super\s*\(\s*id\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*;"""
    ).replace(result) { match ->
        "super(${match.groupValues[1]});"
    }
    result = removeImport(result, "net.minecraft.world.inventory.CraftingInput")
    if (result.contains("CraftingContainer")) {
        result = result.replace("CraftingContainer", "CraftingInput")
        result = removeImport(result, "net.minecraft.world.inventory.CraftingInput")
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.CraftingInput")
        if (!result.contains("CraftingContainer")) {
            result = removeImport(result, "net.minecraft.world.inventory.CraftingContainer")
        }
    }
    result = Regex("""\b([A-Za-z_$][\w$]*)\.getContainerSize\(\)""")
        .replace(result) { match -> "${match.groupValues[1]}.size()" }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyMenuScreensRegistration(source: String): String {
    if (!source.contains("MenuScreens.register(") &&
        !source.contains(".renderScreens();") &&
        !(source.contains("FMLClientSetupEvent") && source.contains("event.register("))) {
        return source
    }
    var result = source
    val convertedHelpers = linkedSetOf<String>()
    if (result.contains("MenuScreens.register(")) {
        result = Regex("""(?m)^([ \t]*)public\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{""")
            .replace(result) { match ->
                val methodStart = match.range.first
                val openBrace = result.indexOf('{', match.range.last)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
                if (closeBrace >= 0 && result.substring(openBrace + 1, closeBrace).contains("MenuScreens.register(")) {
                    val indent = match.groupValues[1]
                    val methodName = match.groupValues[2]
                    val annotationStart = annotationStartBefore(result, methodStart)
                    val hasSubscribeEvent = result.substring(annotationStart, methodStart).contains("@SubscribeEvent")
                    convertedHelpers += methodName
                    val annotation = if (hasSubscribeEvent) "" else "${indent}@SubscribeEvent\n"
                    "${annotation}${indent}public static void $methodName(RegisterMenuScreensEvent event) {"
                } else {
                    result.substring(methodStart, match.range.last + 1)
                }
        }
        result = Regex("""(?:net\.minecraft\.client\.gui\.screens\.)?MenuScreens\.register\(""").replace(result, "event.register(")
    }
    for (helper in convertedHelpers) {
        result = Regex("""(?m)^[ \t]*(?:this\.)?${Regex.escape(helper)}\(\);\s*\r?\n""")
            .replace(result, "")
    }
    if (result.contains("FMLClientSetupEvent") && result.contains("event.register(")) {
        result = migrateFmlClientSetupMenuRegistrationMethods(result)
    }
    if (result.contains(".renderScreens();") && result.contains("FMLClientSetupEvent")) {
        val helpers = Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.renderScreens\(\);\s*$""")
            .findAll(result)
            .map { it.groupValues[2] }
            .toList()
        if (helpers.isNotEmpty()) {
            result = Regex("""(?m)^[ \t]*[A-Za-z_$][\w$]*\.renderScreens\(\);\s*\r?\n""").replace(result, "")
            if (!result.contains("RegisterMenuScreensEvent event") && !result.contains("registerMenuScreens(RegisterMenuScreensEvent")) {
                val methodBody = helpers.distinct().joinToString("\n") { helper ->
                    "        $helper.renderScreens(event);"
                }
                val insertion = """

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
$methodBody
    }
""".trimEnd()
                result = insertBeforeLastClassBrace(result, insertion)
            }
        }
    }
    if (result.contains("RegisterMenuScreensEvent")) {
        result = addImportIfMissing(result, "net.neoforged.neoforge.client.event.RegisterMenuScreensEvent")
    }
    if (Regex("""(?m)^[ \t]*@SubscribeEvent\b""").containsMatchIn(result)) {
        result = addImportIfMissing(result, "net.neoforged.bus.api.SubscribeEvent")
    }
    if (!result.contains("MenuScreens.")) {
        result = removeImport(result, "net.minecraft.client.gui.screens.MenuScreens")
    }
    return result
}

internal fun StructuralRefactorPass.migrateFmlClientSetupMenuRegistrationMethods(source: String): String {
    var result = source
    var cursor = 0
    var changed = false
    val signaturePattern = Regex(
        """(?m)^([ \t]*(?:(?:@\w+(?:\([^)]*\))?)\s*)*public\s+static\s+void\s+[A-Za-z_$][\w$]*\s*\(\s*(?:final\s+)?(?:net\.neoforged\.fml\.event\.lifecycle\.)?FMLClientSetupEvent\s+event\s*\)\s*\{)"""
    )
    while (true) {
        val match = signaturePattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        if (!body.contains("event.register(")) {
            cursor = closeBrace + 1
            continue
        }
        var migratedBody = Regex("""(?m)^([ \t]*)event\.enqueueWork\(\(\)\s*->\s*\{\s*\r?\n""")
            .replace(body, "")
        migratedBody = Regex("""(?m)^[ \t]*\}\);\s*\r?\n""").replaceFirst(migratedBody, "")
        // expression lambdas: event.enqueueWork(() -> event.register(...));
        migratedBody = Regex("""event\.enqueueWork\(\s*\(\)\s*->\s*(event\.register\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\))\s*\)\s*;""")
            .replace(migratedBody) { "${it.groupValues[1]};" }
        val migratedSignature = match.value
            .replace("net.neoforged.fml.event.lifecycle.FMLClientSetupEvent", "net.neoforged.neoforge.client.event.RegisterMenuScreensEvent")
            .replace("FMLClientSetupEvent", "RegisterMenuScreensEvent")
        result = result.substring(0, match.range.first) + migratedSignature + migratedBody + result.substring(closeBrace)
        cursor = match.range.first + migratedSignature.length + migratedBody.length + 1
        changed = true
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.neoforged.neoforge.client.event.RegisterMenuScreensEvent")
    if (!Regex("""\bFMLClientSetupEvent\b""").containsMatchIn(removeImport(result, "net.neoforged.fml.event.lifecycle.FMLClientSetupEvent"))) {
        result = removeImport(result, "net.neoforged.fml.event.lifecycle.FMLClientSetupEvent")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEnchantmentIterationSource(source: String): String {
    if (collectEnchantmentHolderVariables(source).isEmpty() &&
        !source.contains("EnchantmentHelper.getEnchantments(") &&
        !source.contains("EnchantmentHelper.enchantItem(") &&
        !source.contains(".canApplyAtEnchantingTable(") &&
        !source.contains(".getAllEnchantments()") &&
        !source.contains(".category.canEnchant(") &&
        !source.contains("EnchantmentCategory") &&
        !source.contains(".category") &&
        !source.contains("canApplyEnchantment(") &&
        !source.contains(".get().equals(enchantment)") &&
        !source.contains("Objects.equals(") &&
        !source.contains("server.registryAccess().lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)")
    ) {
        return source
    }
    var result = source.replace("EnchantmentHelper.getEnchantments(", "EnchantmentHelper.getEnchantmentsForCrafting(")
    result = migrateLegacyItemStackAllEnchantments(result)
    result = rewriteLegacyRandomEnchantItemCalls(result)
    result = Regex(
        """EnchantmentHelper\.setEnchantments\(\s*EnchantmentHelper\.getEnchantmentsForCrafting\(([^()\r\n;]+)\)\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\)"""
    ).replace(result) { match ->
        "EnchantmentHelper.setEnchantments(${match.groupValues[2]}, EnchantmentHelper.getEnchantmentsForCrafting(${match.groupValues[1].trim()}))"
    }
    result = rewriteJavaInvocationArguments(result, "EnchantmentHelper.setEnchantments") { args ->
        if (args.size != 2) return@rewriteJavaInvocationArguments null
        val enchantments = args[0].trim()
        if (!enchantments.startsWith("EnchantmentHelper.getEnchantmentsForCrafting(")) {
            return@rewriteJavaInvocationArguments null
        }
        val openParen = enchantments.indexOf('(')
        val closeParen = if (openParen >= 0) findMatchingParen(enchantments, openParen) else -1
        if (closeParen != enchantments.length - 1) return@rewriteJavaInvocationArguments null
        listOf(args[1].trim(), enchantments)
    }
    result = rewriteJavaInvocationArguments(result, "EnchantmentHelper.setEnchantments") { args ->
        if (args.size != 2) return@rewriteJavaInvocationArguments null
        val enchantments = args[0].trim()
        if (!enchantments.contains(".getAllEnchantments(")) return@rewriteJavaInvocationArguments null
        listOf(args[1].trim(), enchantments)
    }
    result = Regex(
        """EnchantmentHelper\.getEnchantmentsForCrafting\(([^)]+)\)\.forEach\(\s*\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*->\s*\{"""
    ).replace(result) { match ->
        "EnchantmentHelper.getEnchantmentsForCrafting(${match.groupValues[1]}).entrySet().forEach(entry -> {\n" +
            "\t\t\tnet.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> ${match.groupValues[2]} = entry.getKey();\n" +
            "\t\t\tint ${match.groupValues[3]} = entry.getIntValue();"
    }
    val enchantmentParser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val enchantmentUnit = enchantmentParser.parse(result).result.orElse(null)
    val exactEnchantment = enchantmentUnit?.let(::ExactJavaSemantics)
    result = replaceExecutableRegex(
        result,
        Regex(
            """EnchantmentHelper\.getEnchantmentsForCrafting\(([^)]+)\)\.containsKey\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*\)"""
        )
    ) { match ->
        val key = enchantmentParser.parseExpression<Expression>(match.groupValues[2]).result.orElse(null) as? FieldAccessExpr
        if (key == null || exactEnchantment?.exactStaticScope(
                key.scope,
                "Enchantments",
                "net.minecraft.world.item.enchantment.Enchantments"
            ) != true
        ) {
            match.value
        } else {
            "EnchantmentHelper.getEnchantmentsForCrafting(${match.groupValues[1]}).keySet().stream().anyMatch(holder -> holder.is(${match.groupValues[2]}))"
        }
    }
    val holderVariables = collectEnchantmentHolderVariables(result)
    result = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\.get\(\)\.equals\(\s*([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match ->
            val holder = match.groupValues[2]
            if (holder in holderVariables) {
                "$holder.is(${match.groupValues[1]})"
            } else {
                match.value
            }
        }
    result = Regex("""Objects\.equals\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)(?:\.get\(\))?\s*,\s*([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match ->
            val holder = match.groupValues[2]
            if (holder in holderVariables) {
                "$holder.is(${match.groupValues[1]})"
            } else {
                match.value
            }
        }
    result = Regex("""super\.canApplyAtEnchantingTable\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match -> "super.supportsEnchantment(${match.groupValues[1]}, ${match.groupValues[2]})" }
    result = rewriteLegacyHolderEnchantmentSupportChecks(result)
    result = rewriteLegacyEnchantmentHolderComparisons(result)
    result = Regex(
        """server\.registryAccess\(\)\.lookup\(net\.minecraft\.core\.registries\.Registries\.ENCHANTMENT\)\s*\.\s*flatMap\(registry\s*->\s*registry\.get\(([^)]+)\)\)\s*\.\s*map\(holder\s*->\s*EnchantmentHelper\.get(Tag|Item)EnchantmentLevel\(holder,\s*([A-Za-z_$][\w$]*)\)\)\s*\.\s*orElse\(0\)""",
        RegexOption.DOT_MATCHES_ALL
    ).replace(result) { match ->
        val key = match.groupValues[1].trim()
        val kind = match.groupValues[2]
        val stack = match.groupValues[3]
        "EnchantmentHelper.get${kind}EnchantmentLevel(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow($key), $stack)"
    }
    result = migrateLegacyEnchantmentCategoryHelperMethods(result)
    result = migrateLegacyDirectEnchantmentCategorySupportChecks(result)
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEnchantmentCategoryHelperMethods(source: String): String {
    if (!source.contains("canApplyEnchantment(") ||
        !source.contains("Enchantment...") ||
        (!source.contains("Holder<Enchantment>") && !source.contains("Holder<"))) {
        return source
    }
    val usesCategory = source.contains("EnchantmentCategory") || source.contains(".category")
    var result = source
    result = Regex("""canApplyEnchantment\(\s*EnchantmentHelper\.getEnchantmentsForCrafting\(([^)]+)\)\.keySet\(\)\s*\)""")
        .replace(result) { match ->
            val stack = match.groupValues[1].trim()
            if (usesCategory) {
                "canApplyEnchantment($stack, EnchantmentHelper.getEnchantmentsForCrafting($stack).keySet())"
            } else {
                "canApplyEnchantment(EnchantmentHelper.getEnchantmentsForCrafting($stack).keySet())"
            }
        }
    result = Regex("""canApplyEnchantment\(\s*([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match ->
            val argument = match.groupValues[1]
            if (argument == "enchantments" || argument == "stack") {
                match.value
            } else if (usesCategory) {
                "canApplyEnchantment(stack, java.util.List.of($argument))"
            } else {
                "canApplyEnchantment(java.util.List.of($argument))"
            }
        }
    result = Regex(
        """private\s+boolean\s+canApplyEnchantment\s*\(\s*Enchantment\.\.\.\s+([A-Za-z_$][\w$]*)\s*\)"""
    ).replace(result) { match ->
        if (usesCategory) {
            "private boolean canApplyEnchantment(ItemStack stack, Iterable<Holder<Enchantment>> ${match.groupValues[1]})"
        } else {
            "private boolean canApplyEnchantment(Iterable<Holder<Enchantment>> ${match.groupValues[1]})"
        }
    }
    result = Regex("""for\s*\(\s*Enchantment\s+([A-Za-z_$][\w$]*)\s*:\s*([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match ->
            "for (Holder<Enchantment> ${match.groupValues[1]} : ${match.groupValues[2]})"
        }
    result = Regex("""\b([A-Za-z_$][\w$]*)\.category\s*==\s*(?:[A-Za-z_$][\w$]*\.)?[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?""")
        .replace(result) { match -> "${match.groupValues[1]}.value().isSupportedItem(stack)" }
    result = Regex("""\b([A-Za-z_$][\w$]*)\.category\.canEnchant\(\s*([^)]+)\s*\)""")
        .replace(result) { match -> "${match.groupValues[1]}.value().isSupportedItem(new ItemStack(${match.groupValues[2].trim()}))" }
    result = removeImport(result, "net.minecraft.world.item.enchantment.EnchantmentCategory")
    if (result.contains("Holder<Enchantment>")) {
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyDirectEnchantmentCategorySupportChecks(source: String): String {
    if (!source.contains(".category") || !source.contains("Holder<Enchantment>") || !source.contains("ItemStack")) {
        return source
    }
    var result = source
    val id = """[A-Za-z_$][\w$]*"""
    val methodPattern = Regex(
        """(?m)(public\s+boolean\s+(?:supportsEnchantment|isPrimaryItemFor)\s*\([^)]*\bItemStack\s+($id)[^)]*\b(?:net\.minecraft\.core\.)?Holder\s*<\s*(?:net\.minecraft\.world\.item\.enchantment\.)?Enchantment\s*>\s+($id)[^)]*\)\s*\{)"""
    )
    var changed = false
    var cursor = 0
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val stack = match.groupValues[2]
        val enchantment = match.groupValues[3]
        val body = result.substring(openBrace + 1, closeBrace)
        val migratedBody = Regex(
            """\b${Regex.escape(enchantment)}\.category\s*==\s*(?:[A-Za-z_$][\w$]*\.)?[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?"""
        ).replace(body) {
            "$enchantment.value().isSupportedItem($stack)"
        }
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            cursor = openBrace + 1 + migratedBody.length
            changed = true
        } else {
            cursor = closeBrace + 1
        }
    }
    if (!changed) return source
    return removeImport(result, "net.minecraft.world.item.enchantment.EnchantmentCategory")
}

internal fun StructuralRefactorPass.migrateLambdaCapturedMutableItemStackLocals(source: String): String {
    if (!source.contains("->") || !source.contains("ItemStack")) return source
    var result = source
    var changed = false
    // Each migration retires one mutable-local/lambda-statement capture pair. One local can be
    // captured by many lambdas, so declarations alone are not a sufficient convergence bound.
    val executable = maskJavaCommentsAndLiterals(source)
    val declarationCount = Regex("""\bItemStack\s+[A-Za-z_$][\w$]*\b""")
        .findAll(executable)
        .count()
        .coerceAtLeast(1)
    val lambdaCount = Regex("""->""")
        .findAll(executable)
        .count()
        .coerceAtLeast(1)
    val migrationBudget = minOf(
        source.length.coerceAtLeast(1).toLong(),
        declarationCount.toLong() * lambdaCount.toLong()
    ).toInt()
    var migrations = 0
    while (migrations < migrationBudget) {
        val migration = mutableItemStackLambdaCaptureMigration(result) ?: break
        result = result.replaceRange(migration.methodRange, migration.replacementMethod)
        changed = true
        migrations++
    }
    check(mutableItemStackLambdaCaptureMigration(result) == null) {
        "Mutable ItemStack lambda-capture migration did not converge within $migrationBudget capture pairs"
    }
    return if (changed) result else source
}

/**
 * `name =` preceded by a type (an identifier, `>` or `]`) declares a new variable with an
 * initializer; only the other occurrences reassign an existing one. Sibling lambdas may each
 * declare their own `ItemStack newStack`, which is not a reassignment of either.
 */
internal fun StructuralRefactorPass.isJavaDeclarationInitializer(code: String, nameStart: Int): Boolean {
    var cursor = nameStart - 1
    while (cursor >= 0 && code[cursor].isWhitespace()) cursor--
    if (cursor < 0) return false
    val previous = code[cursor]
    if (previous == '>' || previous == ']') return true
    if (!(previous.isLetterOrDigit() || previous == '_' || previous == '$')) return false
    var wordStart = cursor
    while (wordStart > 0 && (code[wordStart - 1].isLetterOrDigit() || code[wordStart - 1] == '_' || code[wordStart - 1] == '$')) wordStart--
    return code.substring(wordStart, cursor + 1) !in setOf("else", "return", "case", "yield")
}

internal data class MutableItemStackLambdaCaptureMigration(
    val methodRange: IntRange,
    val replacementMethod: String
)

internal fun StructuralRefactorPass.mutableItemStackLambdaCaptureMigration(source: String): MutableItemStackLambdaCaptureMigration? {
    val executable = maskJavaCommentsAndLiterals(source)
    val typePattern = """(?:net\.minecraft\.world\.item\.)?ItemStack"""
    val declarationPattern = Regex("""\b(final\s+)?($typePattern)\s+([A-Za-z_$][\w$]*)\s*=""")
    for (method in javaMethodRangesIncludingDefault(executable).asReversed()) {
        val methodText = source.substring(method.range)
        val methodExecutable = executable.substring(method.range)
        if (!methodExecutable.contains("->") || !methodExecutable.contains("ItemStack")) continue
        declarationPattern.findAll(methodExecutable).forEach { declaration ->
            if (declaration.groupValues[1].isNotBlank()) return@forEach
            val type = declaration.groupValues[2]
            val variable = declaration.groupValues[3]
            val assignmentPattern = Regex("""(?<![\w$.])${Regex.escape(variable)}\s*(?<![=!<>])=(?!=)""")
            val hasLaterAssignment = assignmentPattern.findAll(methodExecutable)
                .any { assignment ->
                    assignment.range.first > declaration.range.last &&
                        !isJavaDeclarationInitializer(methodExecutable, assignment.range.first)
                }
            if (!hasLaterAssignment) return@forEach

            val lambdaUse = lambdaStatementUsingVariable(methodExecutable, variable, declaration.range.last)
                ?: return@forEach
            val statementStart = javaStatementStartForOffset(methodExecutable, lambdaUse)
                ?: return@forEach
            val statementEnd = javaStatementEndFromStart(methodExecutable, statementStart)
                ?: return@forEach
            val statement = methodText.substring(statementStart, statementEnd + 1)
            val replacementStatement = replaceVariableAfterFirstLambdaArrow(statement, variable, uniqueLocalNameInScope(methodText, "final" + variable.replaceFirstChar { it.uppercaseChar() }))
            if (replacementStatement == statement) return@forEach
            val aliasName = Regex("""\bfinal${Regex.escape(variable.replaceFirstChar { it.uppercaseChar() })}\d*\b""")
                .find(replacementStatement)
                ?.value
                ?: return@forEach
            val indent = javaIndentAt(methodText, statementStart)
            val replacementMethod =
                methodText.substring(0, statementStart) +
                    "$indent$type $aliasName = $variable;\n" +
                    replacementStatement +
                    methodText.substring(statementEnd + 1)
            return MutableItemStackLambdaCaptureMigration(method.range, replacementMethod)
        }
    }
    return null
}

internal fun StructuralRefactorPass.lambdaStatementUsingVariable(methodExecutable: String, variable: String, afterOffset: Int): Int? {
    var cursor = afterOffset
    val variablePattern = Regex("""\b${Regex.escape(variable)}\b""")
    while (true) {
        val arrow = methodExecutable.indexOf("->", cursor)
        if (arrow < 0) return null
        val statementStart = javaStatementStartForOffset(methodExecutable, arrow)
        if (statementStart == null) {
            cursor = arrow + 2
            continue
        }
        val statementEnd = javaStatementEndFromStart(methodExecutable, statementStart)
        if (statementEnd == null) {
            cursor = arrow + 2
            continue
        }
        val afterArrow = methodExecutable.substring(arrow + 2, statementEnd + 1)
        if (variablePattern.containsMatchIn(afterArrow)) return arrow
        cursor = arrow + 2
    }
}

internal fun StructuralRefactorPass.replaceVariableAfterFirstLambdaArrow(statement: String, variable: String, alias: String): String {
    val arrow = statement.indexOf("->")
    if (arrow < 0) return statement
    return statement.substring(0, arrow + 2) +
        Regex("""\b${Regex.escape(variable)}\b""").replace(statement.substring(arrow + 2), alias)
}

internal fun StructuralRefactorPass.javaStatementStartForOffset(executableScope: String, offset: Int): Int? {
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var index = offset - 1
    while (index >= 0) {
        when (executableScope[index]) {
            ')' -> parenDepth++
            '(' -> if (parenDepth > 0) parenDepth--
            ']' -> bracketDepth++
            '[' -> if (bracketDepth > 0) bracketDepth--
            '}' -> braceDepth++
            '{' -> {
                if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    return firstNonWhitespaceAfter(executableScope, index + 1)
                }
                if (braceDepth > 0) braceDepth--
            }
            ';' -> {
                if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    return firstNonWhitespaceAfter(executableScope, index + 1)
                }
            }
        }
        index--
    }
    return firstNonWhitespaceAfter(executableScope, 0)
}

internal fun StructuralRefactorPass.javaStatementEndFromStart(executableScope: String, start: Int): Int? {
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var index = start
    while (index < executableScope.length) {
        when (executableScope[index]) {
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '{' -> braceDepth++
            '}' -> {
                if (braceDepth > 0) {
                    braceDepth--
                } else if (parenDepth == 0 && bracketDepth == 0) {
                    return null
                }
            }
            ';' -> if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) return index
        }
        index++
    }
    return null
}

internal fun StructuralRefactorPass.firstNonWhitespaceAfter(text: String, start: Int): Int? {
    var index = start
    while (index < text.length && text[index].isWhitespace()) index++
    return if (index < text.length) index else null
}

internal fun StructuralRefactorPass.javaIndentAt(text: String, offset: Int): String {
    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    return text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
}

internal fun StructuralRefactorPass.migrateLegacyItemEnchantmentsMutableMaps(source: String): String {
    if (!source.contains("EnchantmentHelper.getEnchantmentsForCrafting(") ||
        !source.contains("EnchantmentHelper.setEnchantments(") ||
        !source.contains(".put(")) {
        return source
    }
    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var changed = false
    val declarationPattern = Regex(
        """Map\s*<\s*Enchantment\s*,\s*Integer\s*>\s+($id)\s*=\s*EnchantmentHelper\.getEnchantmentsForCrafting\(\s*($id)\s*\)\s*;"""
    )
    for (method in javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result)).asReversed()) {
        val methodText = result.substring(method.range)
        if (!methodText.contains("EnchantmentHelper.getEnchantmentsForCrafting(") ||
            !methodText.contains("EnchantmentHelper.setEnchantments(") ||
            !methodText.contains(".put(")) {
            continue
        }
        val levelAccess = provenRegistryAccessExpressionForMethod(methodText) ?: continue
        var replacementMethod = methodText
        val declarations = declarationPattern.findAll(methodText).toList()
        if (declarations.isEmpty()) continue
        declarations.forEach { declaration ->
            val mapName = declaration.groupValues[1]
            val stackName = declaration.groupValues[2]
            if (!Regex("""EnchantmentHelper\.setEnchantments\(\s*${Regex.escape(mapName)}\s*,\s*${Regex.escape(stackName)}\s*\)\s*;""")
                    .containsMatchIn(replacementMethod)) {
                return@forEach
            }
            replacementMethod = replacementMethod.replace(
                declaration.value,
                "ItemEnchantments.Mutable $mapName = new ItemEnchantments.Mutable(EnchantmentHelper.getEnchantmentsForCrafting($stackName));"
            )
            replacementMethod = Regex(
                """\b${Regex.escape(mapName)}\.put\(\s*($id(?:\.$id)+)\s*,\s*([^;\r\n]+?)\s*\)\s*;"""
            ).replace(replacementMethod) { put ->
                val key = put.groupValues[1].trim()
                val level = put.groupValues[2].trim()
                val holder = "$levelAccess.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow($key)"
                "$mapName.set($holder, $level);"
            }
            replacementMethod = Regex(
                """EnchantmentHelper\.setEnchantments\(\s*${Regex.escape(mapName)}\s*,\s*${Regex.escape(stackName)}\s*\)\s*;"""
            ).replace(replacementMethod, "EnchantmentHelper.setEnchantments($stackName, $mapName.toImmutable());")
        }
        if (replacementMethod != methodText) {
            result = result.substring(0, method.range.first) + replacementMethod + result.substring(method.range.last + 1)
            changed = true
        }
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.ItemEnchantments")
    if (!Regex("""\bMap\s*[.<]""").containsMatchIn(removeImport(result, "java.util.Map"))) {
        result = removeImport(result, "java.util.Map")
    }
    return result
}

internal fun StructuralRefactorPass.provenRegistryAccessExpressionForMethod(methodText: String): String? {
    val params = javaMethodParameters(methodText)
    params.singleOrNull { simpleJavaTypeName(it.type) == "ServerLevel" }
        ?.name
        ?.let { return "$it.registryAccess()" }
    params.singleOrNull { simpleJavaTypeName(it.type) == "Level" }
        ?.name
        ?.takeIf { level -> hasClientSideEarlyReturn(methodText, level) || hasServerSideGuard(methodText, level) }
        ?.let { return "$it.registryAccess()" }
    Regex("""\b(?:Level|ServerLevel)\s+([A-Za-z_$][\w$]*)\s*=""")
        .find(methodText)
        ?.groupValues
        ?.get(1)
        ?.takeIf { level -> hasClientSideEarlyReturn(methodText, level) || hasServerSideGuard(methodText, level) || methodText.contains("instanceof ServerLevel") }
        ?.let { return "$it.registryAccess()" }
    return null
}

internal fun StructuralRefactorPass.migrateLegacyItemStackAllEnchantments(source: String): String {
    if (!source.contains(".getAllEnchantments()")) return source
    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var changedLookups = false
    for (method in javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result)).asReversed()) {
        val methodText = result.substring(method.range)
        if (!methodText.contains(".getAllEnchantments()")) continue
        val params = javaMethodParameters(methodText)
        val registryAccessVars = params
            .filter { simpleJavaTypeName(it.type) == "RegistryAccess" }
            .map { it.name }
            .distinct()
        val holderLookupProviderVars = params
            .filter { simpleJavaTypeName(it.type) == "Provider" && it.type.contains("HolderLookup") }
            .map { it.name }
            .distinct()
        val lootContextVars = Regex("""\bLootContext\s+($id)\b""")
            .findAll(methodText)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        val livingEntityVars = Regex("""\b(?:LivingEntity|net\.minecraft\.world\.entity\.LivingEntity)\s+($id)\b""")
            .findAll(methodText)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        val levelVars = params
            .filter { simpleJavaTypeName(it.type) in setOf("Level", "ServerLevel") }
            .map { it.name }
            .distinct()
        val lookup = when {
            registryAccessVars.size == 1 ->
                "${registryAccessVars.single()}.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)"
            holderLookupProviderVars.size == 1 ->
                "${holderLookupProviderVars.single()}.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)"
            lootContextVars.size == 1 ->
                "${lootContextVars.single()}.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)"
            livingEntityVars.size == 1 ->
                "${livingEntityVars.single()}.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)"
            levelVars.size == 1 ->
                "${levelVars.single()}.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)"
            else -> continue
        }
        var migratedMethod = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.getAllEnchantments\(\)""")
            .replace(methodText) { match -> "${match.groupValues[1]}.getAllEnchantments($lookup)" }
        if (migratedMethod != methodText) {
            if (migratedMethod.contains(".getAllEnchantments(") && migratedMethod.contains(".keySet().stream()")) {
                migratedMethod = Regex("""\bStream\s*<\s*Enchantment\s*>""")
                    .replace(migratedMethod, "Stream<Holder<Enchantment>>")
                migratedMethod = Regex("""\bPredicate\s*<\s*Enchantment\s*>""")
                    .replace(migratedMethod, "Predicate<Holder<Enchantment>>")
            }
            result = result.substring(0, method.range.first) + migratedMethod + result.substring(method.range.last + 1)
            changedLookups = true
        }
    }
    if (!changedLookups) return source
    var convertedMapEntries = false
    val convertedEntryVars = linkedSetOf<String>()
    result = Regex(
        """for\s*\(\s*Map\.Entry\s*<\s*Enchantment\s*,\s*Integer\s*>\s+($id)\s*:\s*([^\r\n]+?\.entrySet\(\))\s*\)\s*\{"""
    ).replace(result) { match ->
        convertedMapEntries = true
        convertedEntryVars += match.groupValues[1]
        "for (Object2IntMap.Entry<Holder<Enchantment>> ${match.groupValues[1]} : ${match.groupValues[2]}) {"
    }
    result = Regex("""\bEnchantment\s+($id)\s*=\s*($id)\.getKey\(\)\s*;""")
        .replace(result) { match ->
            if (match.groupValues[2] in convertedEntryVars) {
                "Holder<Enchantment> ${match.groupValues[1]} = ${match.groupValues[2]}.getKey();"
            } else {
                match.value
            }
        }
    result = Regex("""\bint\s+($id)\s*=\s*($id)\.getValue\(\)\s*;""")
        .replace(result) { match ->
            if (match.groupValues[2] in convertedEntryVars) {
                "int ${match.groupValues[1]} = ${match.groupValues[2]}.getIntValue();"
            } else {
                match.value
            }
        }
    result = Regex("""\b($id)\.getMinCost\(""")
        .replace(result) { match ->
            val receiver = match.groupValues[1]
            if (receiver in collectEnchantmentHolderVariables(result)) "$receiver.value().getMinCost(" else match.value
        }
    result = Regex("""\b($id)\.isTreasureOnly\(\)""")
        .replace(result) { match ->
            val receiver = match.groupValues[1]
            if (receiver in collectEnchantmentHolderVariables(result)) "$receiver.is(net.minecraft.tags.EnchantmentTags.TREASURE)" else match.value
        }
    result = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.canApplyAtEnchantingTable\(\s*($id)\s*\)""")
        .replace(result) { match ->
            val enchantment = match.groupValues[2]
            if (enchantment in collectEnchantmentHolderVariables(result)) {
                "${match.groupValues[1]}.isPrimaryItemFor($enchantment)"
            } else {
                match.value
            }
        }
    result = Regex("""\.enchant\(\s*($id)\s*,\s*($id)\.getValue\(\)\s*\)""")
        .replace(result) { match ->
            val enchantment = match.groupValues[1]
            val entry = match.groupValues[2]
            if (enchantment in collectEnchantmentHolderVariables(result) && entry in convertedEntryVars) {
                ".enchant($enchantment, ${match.groupValues[2]}.getIntValue())"
            } else {
                match.value
            }
        }
    if (convertedMapEntries) {
        result = addImportIfMissing(result, "it.unimi.dsi.fastutil.objects.Object2IntMap")
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
        if (!Regex("""\bMap\s*[.<]""").containsMatchIn(removeImport(result, "java.util.Map"))) {
            result = removeImport(result, "java.util.Map")
        }
    }
    if (result.contains("Holder<Enchantment>")) {
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
    }
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyRandomEnchantItemCalls(source: String): String {
    if (!source.contains("EnchantmentHelper.enchantItem(")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val lootContextVars = Regex("""\bLootContext\s+($id)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
    val mobVars = Regex("""\bMob\s+($id)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
    return rewriteJavaInvocationArguments(source, "EnchantmentHelper.enchantItem") { args ->
        if (args.size != 4) return@rewriteJavaInvocationArguments null
        val filterArg = args[3].trim()
        val registryAccess = when {
            lootContextVars.size == 1 -> "${lootContextVars.single()}.getLevel().registryAccess()"
            mobVars.size == 1 -> "${mobVars.single()}.registryAccess()"
            else -> return@rewriteJavaInvocationArguments null
        }
        val tag = when {
            lootContextVars.size == 1 -> "ON_RANDOM_LOOT"
            mobVars.size == 1 && filterArg == "false" -> "ON_MOB_SPAWN_EQUIPMENT"
            else -> return@rewriteJavaInvocationArguments null
        }
        val lookup = "$registryAccess.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)"
        listOf(
            args[0],
            args[1],
            args[2],
            registryAccess,
            "java.util.Optional.of($lookup.getOrThrow(net.minecraft.tags.EnchantmentTags.$tag))"
        )
    }
}

internal fun StructuralRefactorPass.rewriteLegacyHolderEnchantmentSupportChecks(source: String): String {
    if (!source.contains(".category.canEnchant(")) return source
    var result = source
    val id = """[A-Za-z_$][\w$]*"""
    val methodPattern = Regex(
        """(?m)(public\s+boolean\s+(supportsEnchantment|isPrimaryItemFor)\s*\([^)]*\bItemStack\s+($id)[^)]*\bHolder\s*<\s*Enchantment\s*>\s+($id)[^)]*\)\s*\{)"""
    )
    var cursor = 0
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val methodName = match.groupValues[2]
        val stack = match.groupValues[3]
        val enchantment = match.groupValues[4]
        val body = result.substring(openBrace + 1, closeBrace)
        val migratedBody = body.replace(
            Regex("""\b${Regex.escape(enchantment)}\.category\.canEnchant\(\s*${Regex.escape(stack)}\.getItem\(\)\s*\)"""),
            "super.$methodName($stack, $enchantment)"
        )
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            cursor = openBrace + 1 + migratedBody.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyEnchantmentHolderComparisons(source: String): String {
    val holderVariables = collectEnchantmentHolderVariables(source)
    if (holderVariables.isEmpty()) return source
    var result = source
    // A registered enchantment is compared through its DeferredHolder (`X.get()` in 1.20.1);
    // Holder#is accepts the holder or key itself, so the `.get()` goes with the `==`.
    val key = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+?(?=\.get\(\)|\b(?!\.))"""
    val keyWithGet = """($key)(?:\.get\(\))?"""
    for (holder in holderVariables) {
        result = Regex("""\b${Regex.escape(holder)}\s*!=\s*$keyWithGet""")
            .replace(result) { match -> "!$holder.is(${match.groupValues[1]})" }
        result = Regex("""$keyWithGet\s*!=\s*${Regex.escape(holder)}\b""")
            .replace(result) { match -> "!$holder.is(${match.groupValues[1]})" }
        result = Regex("""\b${Regex.escape(holder)}\s*==\s*$keyWithGet""")
            .replace(result) { match -> "$holder.is(${match.groupValues[1]})" }
        result = Regex("""$keyWithGet\s*==\s*${Regex.escape(holder)}\b""")
            .replace(result) { match -> "$holder.is(${match.groupValues[1]})" }
    }
    return result
}

internal fun StructuralRefactorPass.collectEnchantmentHolderVariables(source: String): Set<String> {
    val id = """[A-Za-z_$][\w$]*"""
    val holderType = """(?:net\.minecraft\.core\.)?Holder\s*<\s*(?:net\.minecraft\.world\.item\.enchantment\.)?Enchantment\s*>"""
    return Regex("""\b(?:final\s+)?$holderType\s+($id)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
}

internal fun StructuralRefactorPass.migrateStringRepresentableEnumCodecs(source: String): String {
    if (!source.contains("implements StringRepresentable") || source.contains("StringRepresentable.EnumCodec<")) {
        return source
    }
    val enumMatch = Regex("""(?m)\bpublic\s+enum\s+([A-Za-z_$][\w$]*)\s+implements\s+StringRepresentable\b[^{]*\{""")
        .find(source)
        ?: return source
    val enumName = enumMatch.groupValues[1]
    if (Regex("""\bCODEC\b""").containsMatchIn(source.substring(enumMatch.range.last + 1))) {
        return source
    }
    val openBrace = source.indexOf('{', enumMatch.range.first)
    if (openBrace < 0) return source
    val constantsEnd = findEnumConstantsTerminator(source, openBrace) ?: return source
    val insert = """

	public static final StringRepresentable.EnumCodec<$enumName> CODEC = StringRepresentable.fromEnum($enumName::values);
""".trimEnd()
    return source.substring(0, constantsEnd + 1) + insert + source.substring(constantsEnd + 1)
}

internal fun StructuralRefactorPass.findEnumConstantsTerminator(source: String, openBrace: Int): Int? {
    var parenDepth = 0
    var braceDepth = 0
    var inString = false
    var escaped = false
    var inLineComment = false
    var inBlockComment = false
    var i = openBrace + 1
    while (i < source.length) {
        val ch = source[i]
        val next = source.getOrNull(i + 1)
        if (inLineComment) {
            if (ch == '\n') inLineComment = false
            i++
            continue
        }
        if (inBlockComment) {
            if (ch == '*' && next == '/') {
                inBlockComment = false
                i += 2
            } else {
                i++
            }
            continue
        }
        if (inString) {
            escaped = ch == '\\' && !escaped
            if (ch == '"' && !escaped) inString = false
            if (ch != '\\') escaped = false
            i++
            continue
        }
        when {
            ch == '/' && next == '/' -> {
                inLineComment = true
                i += 2
                continue
            }
            ch == '/' && next == '*' -> {
                inBlockComment = true
                i += 2
                continue
            }
            ch == '"' -> inString = true
            ch == '(' -> parenDepth++
            ch == ')' && parenDepth > 0 -> parenDepth--
            ch == '{' -> braceDepth++
            ch == '}' -> {
                if (braceDepth == 0) return null
                braceDepth--
            }
            ch == ';' && parenDepth == 0 && braceDepth == 0 -> return i
        }
        i++
    }
    return null
}

internal fun StructuralRefactorPass.migrateLegacyLootEnchantmentHolderCalls(source: String): String {
    val needsFortuneHolder = source.contains("Enchantments.FORTUNE") &&
        Regex("""(?:ApplyBonusCount\.add(?:Ore|Uniform|BonusBinomialDistribution)BonusCount|BonusLevelTableCondition\.bonusLevelFlatChance)\(\s*Enchantments\.FORTUNE""")
            .containsMatchIn(source)
    val needsRandomProvider = source.contains("EnchantRandomlyFunction.randomApplicableEnchantment()")
    if (!needsFortuneHolder && !needsRandomProvider) {
        return source
    }

    var result = migrateLootTableSubProviderHolderLookupState(source)
    val providerExpression = inferHolderLookupProviderInstanceExpression(result) ?: return source
    if (needsFortuneHolder) {
        val fortuneHolder = "$providerExpression.holderOrThrow(Enchantments.FORTUNE)"
        result = Regex(
            """((?:ApplyBonusCount\.add(?:Ore|Uniform|BonusBinomialDistribution)BonusCount|BonusLevelTableCondition\.bonusLevelFlatChance)\(\s*)Enchantments\.FORTUNE"""
        ).replace(result, "$1$fortuneHolder")
    }
    if (needsRandomProvider) {
        result = result.replace(
            "EnchantRandomlyFunction.randomApplicableEnchantment()",
            "EnchantRandomlyFunction.randomApplicableEnchantment($providerExpression)"
        )
    }
    return result
}

internal fun StructuralRefactorPass.migrateLootTableSubProviderHolderLookupState(source: String): String {
    if (!source.contains("HolderLookup.Provider") ||
        !source.contains("implements LootTableSubProvider") ||
        source.contains("record ") ||
        source.contains("this.registries") ||
        Regex("""\bHolderLookup\.Provider\s+registries\s*[;=]""").containsMatchIn(source)) {
        return source
    }
    val classMatch = Regex("""(?s)\bclass\s+([A-Za-z_$][\w$]*)\b[^{;]*\bimplements\s+[^{};]*LootTableSubProvider\b[^{]*\{""")
        .find(source)
        ?: return source
    if (source.substring(classMatch.range.first, classMatch.range.last + 1).contains("extends")) return source
    val className = classMatch.groupValues[1]
    val constructorMatch = Regex(
        """(?s)\b(?:public|protected|private)?\s*${Regex.escape(className)}\s*\((.*?)\)\s*\{"""
    ).find(source, classMatch.range.last)
        ?: return source
    val providerParam = splitTopLevelJavaArgs(constructorMatch.groupValues[1])
        .mapNotNull(::parseJavaParameter)
        .firstOrNull { (type, _) -> type.endsWith("HolderLookup.Provider") || type == "HolderLookup.Provider" }
        ?: return source
    val paramName = providerParam.second
    if (Regex("""\bHolderLookup\.Provider\s+${Regex.escape(paramName)}\s*[;=]""").containsMatchIn(source)) {
        return source
    }

    var result = source
    val classOpenBrace = result.indexOf('{', classMatch.range.last)
    if (classOpenBrace < 0) return source
    val fieldLine = "\n    private final HolderLookup.Provider $paramName;\n"
    result = result.substring(0, classOpenBrace + 1) + fieldLine + result.substring(classOpenBrace + 1)
    val constructorOpenBrace = result.indexOf('{', constructorMatch.range.last + fieldLine.length)
    if (constructorOpenBrace < 0) return source
    val assignment = "\n        this.$paramName = $paramName;"
    result = result.substring(0, constructorOpenBrace + 1) + assignment + result.substring(constructorOpenBrace + 1)
    return result
}

internal fun StructuralRefactorPass.inferHolderLookupProviderInstanceExpression(source: String): String? {
    if (Regex("""extends\s+(?:[A-Za-z_$][\w$]*\.)?(?:[A-Za-z_$][\w$]*)?BlockLootSubProvider\b""").containsMatchIn(source) ||
        Regex("""extends\s+(?:[A-Za-z_$][\w$]*\.)?(?:[A-Za-z_$][\w$]*)?EntityLootSubProvider\b""").containsMatchIn(source)) {
        return "this.registries"
    }
    Regex("""\brecord\s+[A-Za-z_$][\w$]*\s*\([^)]*\bHolderLookup\.Provider\s+([A-Za-z_$][\w$]*)""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.let { return "this.$it" }
    Regex("""\b(?:private|protected|public)?\s*(?:final\s+)?HolderLookup\.Provider\s+([A-Za-z_$][\w$]*)\s*[;=]""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.let { return "this.$it" }
    return null
}

internal fun StructuralRefactorPass.migrateLegacyBlockLootSubProviderSource(source: String): String {
    if (!source.contains("BlockLootSubProvider")) return source
    val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+(?:[A-Za-z_$][\w$]*\.)?(?:[A-Za-z_$][\w$]*)?BlockLootSubProvider\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return source
    var result = source
    val constructorPattern = Regex("""(?s)((?:public|protected)\s+)${Regex.escape(className)}\s*\((.*?)\)\s*\{\s*super\s*\((.*?)\)\s*;\s*\}""")
    result = constructorPattern.replace(result) { match ->
        val params = splitTopLevelJavaArgs(match.groupValues[2])
        if (params.any { it.contains("HolderLookup.Provider") }) return@replace match.value
        val args = splitTopLevelJavaArgs(match.groupValues[3])
        if (args.size == 2) {
            val newParams = (params + "HolderLookup.Provider registries").joinToString(", ")
            "${match.groupValues[1]}$className($newParams) {\n\t\tsuper(${args[0]}, ${args[1]}, registries);\n\t}"
        } else {
            match.value
        }
    }
    if (result.contains("HolderLookup.Provider registries")) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    result = result.replace("HAS_SHEARS.or(HAS_SILK_TOUCH)", "HAS_SHEARS.or(this.hasSilkTouch())")
    result = result.replace("HAS_SILK_TOUCH", "this.hasSilkTouch()")
    result = Regex("""protected\s+static\s+LootTable\.Builder\s+(create(?:SilkTouchOr)?Shears(?:DispatchTable|OnlyDrop)\w*)\(""")
        .replace(result) { match ->
            if (match.groupValues[1].contains("DispatchTable")) {
                return@replace "protected LootTable.Builder ${match.groupValues[1]}("
            }
            val openBrace = result.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            val body = if (openBrace >= 0 && closeBrace > openBrace) result.substring(openBrace + 1, closeBrace) else ""
            if (body.contains("this.")) {
                "protected LootTable.Builder ${match.groupValues[1]}("
            } else {
                match.value
            }
        }
    result = Regex("""protected\s+LootTable\.Builder\s+createShearsOnlyDrop\(""")
        .replace(result) { match ->
            val openBrace = result.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            val body = if (openBrace >= 0 && closeBrace > openBrace) result.substring(openBrace + 1, closeBrace) else ""
            if (body.contains("this.")) match.value else "protected static LootTable.Builder createShearsOnlyDrop("
        }
    return result
}

internal fun StructuralRefactorPass.migrateLegacySpriteSourceProviderSource(
    source: String,
    spriteSourceProviderClasses: Set<String>
): String {
    if (!source.contains("SpriteSourceProvider") && spriteSourceProviderClasses.none { source.contains("new $it(") }) {
        return source
    }

    var result = source
    var changed = false
    val localProviderClasses = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+(?:[A-Za-z_$][\w$]*\.)?SpriteSourceProvider\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()

    localProviderClasses.forEach { className ->
        val constructorPattern = Regex(
            """(?s)public\s+${Regex.escape(className)}\s*\((.*?)\)\s*\{\s*super\s*\((.*?)\)\s*;\s*\}"""
        )
        result = constructorPattern.replace(result) { match ->
            val params = splitTopLevelJavaArgs(match.groupValues[1])
            val superArgs = splitTopLevelJavaArgs(match.groupValues[2])
            if (params.size != 2 || superArgs.size != 3) return@replace match.value
            val outputParam = parseJavaParameter(params[0]) ?: return@replace match.value
            val helperParam = parseJavaParameter(params[1]) ?: return@replace match.value
            if (!outputParam.first.endsWith("PackOutput") || !helperParam.first.endsWith("ExistingFileHelper")) {
                return@replace match.value
            }
            if (superArgs[0].trim() != outputParam.second || superArgs[1].trim() != helperParam.second) {
                return@replace match.value
            }
            changed = true
            "public $className(${outputParam.first} ${outputParam.second}, CompletableFuture<HolderLookup.Provider> provider, ${helperParam.first} ${helperParam.second}) {\n\t\tsuper(${outputParam.second}, provider, ${superArgs[2].trim()}, ${helperParam.second});\n\t}"
        }
        result = Regex("""(?m)^([ \t]*(?:protected|public)\s+)void\s+addSources\s*\(""")
            .replace(result) { match ->
                changed = true
                "${match.groupValues[1]}void gather("
            }
    }

    val providerExpression = inferHolderLookupProviderExpression(result)
    if (providerExpression != null && spriteSourceProviderClasses.isNotEmpty()) {
        spriteSourceProviderClasses.forEach { className ->
            val token = "new $className("
            var cursor = 0
            while (cursor < result.length) {
                val tokenIndex = result.indexOf(token, cursor)
                if (tokenIndex < 0) break
                val openParen = tokenIndex + "new $className".length
                val closeParen = findMatchingParen(result, openParen)
                if (closeParen < 0) break
                val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
                if (args.size == 2) {
                    val replacement = "new $className(${args[0].trim()}, $providerExpression, ${args[1].trim()})"
                    result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
                    cursor = tokenIndex + replacement.length
                    changed = true
                } else {
                    cursor = closeParen + 1
                }
            }
        }
    }

    if (!changed) return source
    if (result.contains("CompletableFuture<HolderLookup.Provider>")) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
        result = addImportIfMissing(result, "java.util.concurrent.CompletableFuture")
    }
    return result
}

internal fun StructuralRefactorPass.parseJavaParameter(parameter: String): Pair<String, String>? {
    val clean = parameter.trim().removePrefix("final ").trim()
    val match = Regex("""(.+?)\s+([A-Za-z_$][\w$]*)$""").find(clean) ?: return null
    return match.groupValues[1].trim() to match.groupValues[2]
}

/**
 * A static datagen helper (`registerAllProcessing(DataGenerator gen, PackOutput output)`) whose
 * body received the lookup provider for the generators it creates but declares no such value
 * gains it as its last parameter; [migrateDatagenLookupProviderCallers] passes it from callers.
 */
internal fun StructuralRefactorPass.threadLookupProviderIntoStaticDatagenHelpers(source: String, generatorClasses: Set<String>): String {
    if (!source.contains("PackOutput")) return source
    val inferred = inferHolderLookupProviderExpression(source)
    val provider = inferred?.takeIf { Regex("""^[A-Za-z_$][\w$]*$""").matches(it) } ?: "lookupProvider"
    val use = Regex("""(?<![\w$.])${Regex.escape(provider)}(?![\w$(])""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRanges(source)) {
        if (!Regex("""\bstatic\b""").containsMatchIn(method.header)) continue
        val parameters = callableParameters(source, method.range)
        if (parameters.any { parseJavaParameter(it)?.second == provider }) continue
        val outputParameter = parameters.mapNotNull(::parseJavaParameter).firstOrNull { (type, _) -> type.substringAfterLast('.') == "PackOutput" }
        val bodyStart = method.range.first + method.header.length
        val body = source.substring(bodyStart, method.range.last + 1)
        val maskedBody = maskJavaCommentsAndLiterals(body)
        if (Regex("""(?<![\w$.])(?:final\s+)?[\w$.<>?, ]+\s+${Regex.escape(provider)}\s*=""").containsMatchIn(maskedBody)) continue
        var needsProvider = use.containsMatchIn(maskedBody)
        if (outputParameter != null) {
            for (generator in generatorClasses) {
                for (creation in Regex("""\bnew\s+${Regex.escape(generator)}\s*\(""").findAll(maskedBody)) {
                    val openParen = creation.range.last
                    val closeParen = findMatchingParen(maskedBody, openParen)
                    if (closeParen < 0) continue
                    val args = splitTopLevelJavaArgs(body.substring(openParen + 1, closeParen)).map { it.trim() }
                    if (args.isEmpty() || args[0] != outputParameter.second || args.any { it == provider }) continue
                    edits += (bodyStart + openParen + 1 until bodyStart + closeParen) to (listOf(args[0], provider) + args.drop(1)).joinToString(", ")
                    needsProvider = true
                }
            }
        }
        if (!needsProvider) continue
        val span = callableParameterSpan(source, method.range) ?: continue
        val existing = source.substring(span.first + 1, span.second).trim()
        val newParameters = if (existing.isBlank()) "CompletableFuture<HolderLookup.Provider> $provider" else "$existing, CompletableFuture<HolderLookup.Provider> $provider"
        edits += (span.first + 1 until span.second) to newParameters
    }
    if (edits.isEmpty()) return source
    var result = applyStringEdits(source, edits)
    result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    result = addImportIfMissing(result, "java.util.concurrent.CompletableFuture")
    return result
}

internal data class DatagenLookupHelper(val owner: String, val name: String, val parameterCount: Int)

/** Calls of the static helpers widened by [threadLookupProviderIntoStaticDatagenHelpers] pass the caller's lookup provider. */
internal fun StructuralRefactorPass.migrateDatagenLookupProviderCallers(javaFiles: List<Path>, dryRun: Boolean): List<Change> {
    val helpers = mutableListOf<DatagenLookupHelper>()
    val sources = javaFiles.associateWith { runCatching { it.readText() }.getOrDefault("") }
    for ((_, source) in sources) {
        if (!source.contains("CompletableFuture<HolderLookup.Provider>")) continue
        val executableCode = maskJavaCommentsAndLiterals(source)
        val typeBlocks = javaTypeBlocks(source, executableCode)
        for (method in javaMethodRanges(source)) {
            if (!Regex("""\bstatic\b""").containsMatchIn(method.header)) continue
            val parameters = callableParameters(source, method.range)
            if (parameters.lastOrNull()?.contains("CompletableFuture<HolderLookup.Provider>") != true) continue
            val owner = typeBlocks.lastOrNull { method.range.first in it.bodyStart..it.end }?.name ?: continue
            helpers += DatagenLookupHelper(owner, method.name, parameters.size)
        }
    }
    if (helpers.isEmpty()) return emptyList()
    val changes = mutableListOf<Change>()
    for ((javaFile, source) in sources) {
        if (helpers.none { source.contains("${it.owner}.${it.name}(") }) continue
        val provider = inferHolderLookupProviderExpression(source) ?: continue
        var result = source
        for (helper in helpers) {
            result = rewriteExecutableJavaCallWithOffset(result, helper.name) { receiver, args, _ ->
                if (receiver != helper.owner || args.size != helper.parameterCount - 1) return@rewriteExecutableJavaCallWithOffset null
                "$receiver.${helper.name}(${(args.map { it.trim() } + provider).joinToString(", ")})"
            }
        }
        if (result == source) continue
        if (!dryRun) javaFile.writeText(result)
        changes += Change(
            file = javaFile,
            line = 1,
            description = "Pass the lookup provider to static datagen helpers that gained it",
            before = "Helper.register(generator, output)",
            after = "Helper.register(generator, output, lookupProvider)",
            confidence = Confidence.HIGH,
            ruleId = "struct-datagen-lookup-provider-callers"
        )
    }
    return changes
}

internal fun StructuralRefactorPass.inferHolderLookupProviderExpression(source: String): String? {
    Regex(
        """(?:java\.util\.concurrent\.)?CompletableFuture\s*<\s*HolderLookup\.Provider\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*[^;]*getLookupProvider\s*\("""
    ).find(source)?.groupValues?.get(1)?.let { return it }
    Regex(
        """(?:java\.util\.concurrent\.)?CompletableFuture\s*<\s*HolderLookup\.Provider\s*>\s+([A-Za-z_$][\w$]*)\b"""
    ).find(source)?.groupValues?.get(1)?.let { return it }
    Regex("""\bGatherDataEvent\s+([A-Za-z_$][\w$]*)\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.let { return "$it.getLookupProvider()" }
    return null
}

internal fun StructuralRefactorPass.inferEnrichedRecipeProviderExpression(source: String): String? =
    Regex(
        """(?:java\.util\.concurrent\.)?CompletableFuture\s*<\s*HolderLookup\.Provider\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*[^;]*getRegistryProvider\s*\("""
    ).findAll(source)
        .lastOrNull()
        ?.groupValues
        ?.get(1)

internal fun StructuralRefactorPass.migrateLegacyRecipeProviderSource(
    source: String,
    recipeProviderClasses: RecipeProviderClassHints,
    projectModIdExpression: String? = null
): String {
    if (recipeProviderClasses.all.isEmpty() ||
        (!source.contains("RecipeProvider") &&
            recipeProviderClasses.all.none {
                source.contains("new $it(") ||
                    source.contains("$it::new") ||
                    Regex("""\bclass\s+${Regex.escape(it)}\b""").containsMatchIn(source)
            })) {
        return source
    }

    var result = source
    var changed = false
    val localRecipeProviderClasses = recipeProviderClasses.all.filter { className ->
        Regex("""\bclass\s+${Regex.escape(className)}\b""").containsMatchIn(result)
    }

    for (className in localRecipeProviderClasses) {
        // Bound the explicit super invocation rather than letting a regex consume constructor
        // assignments and later methods until another `); }` appears.
        for (constructor in javaConstructorRanges(result, className).asReversed()) {
            val span = callableParameterSpan(result, constructor.range) ?: continue
            val params = callableParameters(result, constructor.range)
            val parsedParams = params.mapNotNull(::parseJavaParameter)
            val outputParam = parsedParams.firstOrNull { (type, _) -> type.substringAfterLast('.') == "PackOutput" }
                ?: continue
            if (parsedParams.firstOrNull() != outputParam) continue
            val lookupParam = parsedParams.firstOrNull { (type, _) ->
                type.contains("CompletableFuture") && type.contains("HolderLookup.Provider")
            }
            val masked = maskJavaCommentsAndLiterals(result)
            val openBody = masked.indexOf('{', span.second)
            if (openBody < 0 || openBody !in constructor.range) continue
            val call = Regex("""\bsuper\s*\(""").find(masked, openBody + 1) ?: continue
            if (call.range.first !in constructor.range || masked.substring(openBody + 1, call.range.first).isNotBlank()) continue
            val close = findMatchingParen(masked, call.range.last)
            if (close < 0 || close !in constructor.range) continue
            val superArgs = splitTopLevelJavaArgs(result.substring(call.range.last + 1, close)).map { it.trim() }
            if (superArgs.firstOrNull() != outputParam.second) continue
            val keptParams = params.filter { param ->
                val name = parseJavaParameter(param)?.second
                name != outputParam.second && name != lookupParam?.second
            }
            var lookupName = lookupParam?.second ?: "lookupProvider"
            while (lookupParam == null && parsedParams.any { it.second == lookupName }) lookupName += "_"
            val newParams = (listOf("${outputParam.first} ${outputParam.second}", "CompletableFuture<HolderLookup.Provider> $lookupName") + keptParams)
                .joinToString(", ")
            val keptSuperArgs = superArgs.drop(1).filter { it != lookupParam?.second }.toMutableList()
            val parentType = Regex("""\bclass\s+${Regex.escape(className)}\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""")
                .find(result)
                ?.groupValues
                ?.get(1)
            val parentClass = parentType?.substringAfterLast('.')
            val platformParent = parentType == "net.minecraft.data.recipes.RecipeProvider" || parentType == "RecipeProvider" &&
                (result.contains("import net.minecraft.data.recipes.RecipeProvider;") || result.contains("import net.minecraft.data.recipes.*;"))
            if (platformParent && superArgs.size == 3 && superArgs[0] == superArgs[2] && superArgs[1] == lookupParam?.second) {
                keptSuperArgs.clear()
            }
            // MMLib 1.2.15's constructor is (PackOutput, String, CompletableFuture<Provider>).
            val mmlibParent = parentType == "cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider" || parentType == "AbstractRecipeProvider" &&
                result.contains("import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;")
            val newSuperArgs = if (mmlibParent) {
                val namespace = keptSuperArgs.ifEmpty { singleProjectModIdExpression(result, projectModIdExpression)?.let(::listOf).orEmpty() }
                (listOf(outputParam.second) + namespace + lookupName).joinToString(", ")
            } else (listOf(outputParam.second, lookupName) + keptSuperArgs).joinToString(", ")
            val parameterText = result.substring(span.first + 1, span.second)
            val argumentText = result.substring(call.range.last + 1, close)
            if (parameterText != newParams || argumentText != newSuperArgs) {
                result = result.replaceRange(call.range.last + 1 until close, newSuperArgs)
                result = result.replaceRange(span.first + 1 until span.second, newParams)
                changed = true
            }
        }

        result = removeLegacyRecipeProviderStaticHasOverride(result)
    }

    val providerExpression = inferEnrichedRecipeProviderExpression(result)
        ?: inferHolderLookupProviderExpression(result)
    if (providerExpression != null && (result.contains("DataGenerator") || result.contains("GatherDataEvent"))) {
        for (className in recipeProviderClasses.concrete) {
            val token = "new $className("
            var cursor = 0
            while (cursor < result.length) {
                val tokenIndex = result.indexOf(token, cursor)
                if (tokenIndex < 0) break
                val openParen = tokenIndex + "new $className".length
                val closeParen = findMatchingParen(result, openParen)
                if (closeParen < 0) break
                val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
                if (args.none { it.trim() == providerExpression } && args.isNotEmpty()) {
                    val outputArg = args.first().trim()
                    val replacementArgs = (listOf(outputArg, providerExpression) + args.drop(1).map { it.trim() }).joinToString(", ")
                    val replacement = "new $className($replacementArgs)"
                    result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
                    cursor = tokenIndex + replacement.length
                    changed = true
                } else {
                    cursor = closeParen + 1
                }
            }
            val factoryOutputName = uniqueJavaLocalName(result, "modporterOutput")
            result = Regex(
                """\(\s*DataProvider\.Factory\s*<\s*${Regex.escape(className)}\s*>\s*\)\s*${Regex.escape(className)}::new"""
            ).replace(result) {
                changed = true
                "(DataProvider.Factory<$className>) $factoryOutputName -> new $className($factoryOutputName, $providerExpression)"
            }
        }
    }

    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    if (result.contains("CompletableFuture<HolderLookup.Provider>")) {
        result = addImportIfMissing(result, "java.util.concurrent.CompletableFuture")
    }
    return result
}

internal fun StructuralRefactorPass.singleProjectModIdExpression(source: String, projectModIdExpression: String?): String? {
    projectModIdExpression?.takeIf { it.isNotBlank() }?.let { return it }
    val executableCode = maskJavaCommentsAndLiterals(source)
    val expressions = Regex("""\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.(?:MODID|MOD_ID))\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
    return expressions.singleOrNull()
}

internal fun StructuralRefactorPass.removeLegacyRecipeProviderStaticHasOverride(source: String): String {
    if (!source.contains("InventoryChangeTrigger.TriggerInstance has(") ||
        !source.contains("extends RecipeProvider")) {
        return source
    }
    return Regex(
        """(?s)\n[ \t]*protected\s+static\s+InventoryChangeTrigger\.TriggerInstance\s+has\s*\(\s*TagKey\s*<\s*Item\s*>\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*return\s+inventoryTrigger\s*\(.*?\);\s*\}\s*"""
    ).replace(source, "\n")
}

internal fun StructuralRefactorPass.migrateLegacyRecipeBuilderSource(
    source: String,
    recipeSerializerFactoryHints: RecipeSerializerFactoryHints
): String {
    if (!source.contains("RecipeBuilder") &&
        !source.contains("SpecialRecipeBuilder.special(") &&
        !source.contains("SimpleCookingRecipeBuilder.generic(") &&
        !source.contains("consumer.accept(new Result(") &&
        !source.contains("output.accept(new Result(") &&
        !source.contains(".toJson()")) {
        return source
    }

    var result = source
    var changed = false

    if (result.contains("SpecialRecipeBuilder.special(") && recipeSerializerFactoryHints.fieldToFactory.isNotEmpty()) {
        result = rewriteJavaCall(result, "special") { receiver, args ->
            if (receiver != "SpecialRecipeBuilder" || args.size != 1) return@rewriteJavaCall null
            val expression = args[0].trim().removeSuffix(".get()")
            val factory = recipeSerializerFactoryHints.fieldToFactory[expression] ?: return@rewriteJavaCall null
            changed = true
            "SpecialRecipeBuilder.special($factory)"
        }
    }

    val cookingFactories = mapOf(
        "RecipeSerializer.SMELTING_RECIPE" to "SmeltingRecipe::new",
        "RecipeSerializer.SMOKING_RECIPE" to "SmokingRecipe::new",
        "RecipeSerializer.BLASTING_RECIPE" to "BlastingRecipe::new",
        "RecipeSerializer.CAMPFIRE_COOKING_RECIPE" to "CampfireCookingRecipe::new"
    )
    // Aliases such as `private final RecipeSerializer<? extends AbstractCookingRecipe> FURNACE =
    // RecipeSerializer.SMELTING_RECIPE, SMOKER = ...;` cannot carry a factory: their wildcard type
    // never binds the factory's T, so they are inlined at their call sites and the declaration
    // goes, as Create 6 inlines them.
    val serializerAliases = linkedMapOf<String, String>()
    val aliasDeclaration = Regex(
        """(?m)^[ \t]*(?:private|protected|public)?\s*(?:static\s+)?(?:final\s+)?RecipeSerializer\s*<\s*\?\s+extends\s+AbstractCookingRecipe\s*>\s+((?:[A-Za-z_$][\w$]*\s*=\s*RecipeSerializer\.[A-Z_]+\s*,\s*)*[A-Za-z_$][\w$]*\s*=\s*RecipeSerializer\.[A-Z_]+)\s*;[ \t]*\r?\n?"""
    )
    result = aliasDeclaration.replace(result) { match ->
        val pairs = Regex("""([A-Za-z_$][\w$]*)\s*=\s*(RecipeSerializer\.[A-Z_]+)""").findAll(match.groupValues[1]).toList()
        if (pairs.any { it.groupValues[2] !in cookingFactories }) return@replace match.value
        pairs.forEach { serializerAliases[it.groupValues[1]] = it.groupValues[2] }
        ""
    }
    for ((alias, serializer) in serializerAliases) {
        result = replaceExecutableRegex(result, Regex("""(?<![\w$.])${Regex.escape(alias)}(?![\w$])""")) { serializer }
    }
    if (result != source) changed = true

    if (result.contains("SimpleCookingRecipeBuilder.generic(")) {
        result = rewriteJavaCall(result, "generic") { receiver, args ->
            if (receiver != "SimpleCookingRecipeBuilder" || args.size != 6) return@rewriteJavaCall null
            val serializer = args[5].trim()
            val factory = cookingFactories[serializer] ?: return@rewriteJavaCall null
            changed = true
            "SimpleCookingRecipeBuilder.generic(${args.take(6).joinToString(", ") { it.trim() }}, $factory)"
        }
    }

    val cookingMethodPattern = Regex(
        """(?m)^([ \t]*private\s+(?:(?:static|final|synchronized)\s+)*)([A-Za-z_$][\w$]*(?:\s*<[^;{}()]+>)?(?:\[\])?)\s+([A-Za-z_$][\w$]*)\s*\(([^)]*RecipeSerializer\s*<\s*\?\s+extends\s+AbstractCookingRecipe\s*>\s+([A-Za-z_$][\w$]*)[^)]*)\)"""
    )
    data class CookingHelper(val name: String, val serializer: String, val owner: String?, val count: Int, val serializerIndex: Int)
    val genericCookingMethods = mutableListOf<CookingHelper>()
    val cookingSource = result
    result = cookingMethodPattern.replace(result) { match ->
        val returnType = match.groupValues[2]
        val methodName = match.groupValues[3]
        val serializerParam = match.groupValues[5]
        val parameters = splitTopLevelJavaArgs(match.groupValues[4])
        val serializerIndex = parameters.indexOfFirst { it.contains("RecipeSerializer") && it.trim().endsWith(serializerParam) }
        val owner = enclosingJavaClassDeclaration(cookingSource, match.range.first)?.name
        // Every call site gets its factory from the serializer constant it passes. A call site that
        // passes anything else (a wildcard-typed variable) cannot supply one, so the helper keeps
        // its shape and its callers keep compiling.
        var complete = serializerIndex >= 0
        rewriteJavaInvocationArgumentsWithOffset(cookingSource, methodName) { args, offset ->
            if (offset in match.range || args.size != parameters.size || cookingSource.getOrNull(offset - 1) == '.' ||
                enclosingJavaClassDeclaration(cookingSource, offset)?.name != owner
            ) return@rewriteJavaInvocationArgumentsWithOffset null
            if (cookingFactories[args[serializerIndex].trim()] == null) complete = false
            null
        }
        if (!complete) return@replace match.value
        genericCookingMethods += CookingHelper(methodName, serializerParam, owner, parameters.size, serializerIndex)
        changed = true
        val params = match.groupValues[4]
            .replace(
                Regex("""RecipeSerializer\s*<\s*\?\s+extends\s+AbstractCookingRecipe\s*>\s+${Regex.escape(serializerParam)}"""),
                "RecipeSerializer<T> $serializerParam, AbstractCookingRecipe.Factory<T> ${serializerParam}Factory"
            )
        "${match.groupValues[1]}<T extends AbstractCookingRecipe> $returnType $methodName($params)"
    }
    for (helper in genericCookingMethods) {
        val methodName = helper.name
        val serializerParam = helper.serializer
        result = rewriteExecutableJavaCallWithOffset(result, "generic") { receiver, args, offset ->
            if (receiver != "SimpleCookingRecipeBuilder" || args.size != 6 || args[5].trim() != serializerParam) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val enclosing = javaMethodRangesIncludingDefault(result).filter { offset in it.range }.minByOrNull { it.range.last - it.range.first }
            if (enclosing?.name != methodName || enclosingJavaClassDeclaration(result, offset)?.name != helper.owner) return@rewriteExecutableJavaCallWithOffset null
            changed = true
            "SimpleCookingRecipeBuilder.generic(${args.joinToString(", ") { it.trim() }}, ${serializerParam}Factory)"
        }
        result = rewriteJavaInvocationArgumentsWithOffset(result, methodName) { args, offset ->
            if (args.size != helper.count || helper.serializerIndex < 0 ||
                result.getOrNull(offset - 1) == '.' || enclosingJavaClassDeclaration(result, offset)?.name != helper.owner
            ) return@rewriteJavaInvocationArgumentsWithOffset null
            val factory = cookingFactories[args[helper.serializerIndex].trim()] ?: return@rewriteJavaInvocationArgumentsWithOffset null
            changed = true
            args.toMutableList().apply { add(helper.serializerIndex + 1, factory) }
        }
    }

    if (result.contains("implements RecipeBuilder")) {
        result = Regex("""CriterionTriggerInstance\s+([A-Za-z_$][\w$]*)""")
            .replace(result) {
                changed = true
                "Criterion<?> ${it.groupValues[1]}"
            }
        result = result.replace("this.getDefaultRecipeId(this.getResult()).getPath()", "RecipeBuilder.getDefaultRecipeId(this.getResult()).getPath()")
    }

    migrateLegacyRecipeBuilderAdvancementCriteriaSource(result)?.let { migrated ->
        result = migrated
        changed = true
    }

    migrateLegacyFinishedRecipeBuilderOutputSource(result, recipeSerializerFactoryHints)?.let { migrated ->
        result = migrated
        changed = true
    }

    migrateProvenRecipeValueSerialization(result, network = false).let { migrated ->
        if (migrated != result) changed = true
        result = migrated
    }

    if (!changed) return source
    if (result.contains("Criterion<?>")) {
        result = addImportIfMissing(result, "net.minecraft.advancements.Criterion")
        result = removeImport(result, "net.minecraft.advancements.CriterionTriggerInstance")
    }
    if (result.contains("new LinkedHashMap<>()")) {
        result = addImportIfMissing(result, "java.util.LinkedHashMap")
        result = addImportIfMissing(result, "java.util.Map")
    }
    if (result.contains("JsonOps.INSTANCE")) {
        result = addImportIfMissing(result, "com.mojang.serialization.JsonOps")
    }
    if (result.contains("ShapedRecipePattern.of(")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.ShapedRecipePattern")
    }
    if (result.contains("SmeltingRecipe::new")) result = addImportIfMissing(result, "net.minecraft.world.item.crafting.SmeltingRecipe")
    if (result.contains("SmokingRecipe::new")) result = addImportIfMissing(result, "net.minecraft.world.item.crafting.SmokingRecipe")
    if (result.contains("BlastingRecipe::new")) result = addImportIfMissing(result, "net.minecraft.world.item.crafting.BlastingRecipe")
    if (result.contains("CampfireCookingRecipe::new")) result = addImportIfMissing(result, "net.minecraft.world.item.crafting.CampfireCookingRecipe")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyRecipeBuilderAdvancementCriteriaSource(source: String): String? {
    if (!source.contains("implements RecipeBuilder") ||
        !source.contains("Advancement.Builder") ||
        !source.contains(".getCriteria()")) {
        return null
    }
    val advancementField = Regex(
        """private\s+final\s+Advancement\.Builder\s+([A-Za-z_$][\w$]*)\s*=\s*Advancement\.Builder\.advancement\(\)\s*;"""
    ).find(source)?.groupValues?.get(1) ?: return null
    val saveOutput = Regex(
        """void\s+save\s*\(\s*RecipeOutput\s+([A-Za-z_$][\w$]*)\s*,"""
    ).find(source)?.groupValues?.get(1) ?: return null

    var result = source
    result = Regex(
        """private\s+final\s+Advancement\.Builder\s+${Regex.escape(advancementField)}\s*=\s*Advancement\.Builder\.advancement\(\)\s*;"""
    ).replace(result, "private final Map<String, Criterion<?>> criteria = new LinkedHashMap<>();")
    result = Regex("""this\.${Regex.escape(advancementField)}\.addCriterion\(([^;\r\n]+)\);""")
        .replace(result) { match -> "this.criteria.put(${match.groupValues[1]});" }
    result = result.replace("this.$advancementField.getCriteria().isEmpty()", "this.criteria.isEmpty()")

    val advancementLine = Regex(
        """(?m)^([ \t]*)this\.${Regex.escape(advancementField)}\.parent\(([^;\r\n]+)\)\.addCriterion\(([^;\r\n]+)\)\.rewards\(([^;\r\n]+)\)\.requirements\(([^;\r\n]+)\);\s*$"""
    )
    result = advancementLine.replace(result) { match ->
        val indent = match.groupValues[1]
        "${indent}Advancement.Builder advancementBuilder = $saveOutput.advancement().addCriterion(${match.groupValues[3]}).rewards(${match.groupValues[4]}).requirements(${match.groupValues[5]});\n" +
            "${indent}this.criteria.forEach(advancementBuilder::addCriterion);"
    }
    result = replaceLegacyResultAdvancementArgument(result, advancementField)

    return if (result != source) result else null
}

internal fun StructuralRefactorPass.replaceLegacyResultAdvancementArgument(source: String, advancementField: String): String {
    var result = source
    var cursor = 0
    val constructorPattern = Regex("""new\s+((?:[A-Za-z_$][\w$]*\.)*Result)\s*\(""")
    while (true) {
        val match = constructorPattern.find(result, cursor) ?: break
        val openParen = result.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(result, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.none { it.trim() == "this.$advancementField" }) {
            cursor = closeParen + 1
            continue
        }
        val migratedArgs = args.joinToString(", ") { arg ->
            if (arg.trim() == "this.$advancementField") "advancementBuilder" else arg.trim()
        }
        val replacement = "new ${match.groupValues[1]}($migratedArgs)"
        result = result.substring(0, match.range.first) + replacement + result.substring(closeParen + 1)
        cursor = match.range.first + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyFinishedRecipeBuilderOutputSource(
    source: String,
    recipeSerializerFactoryHints: RecipeSerializerFactoryHints
): String? {
    if (!source.contains("implements RecipeBuilder") || !source.contains("new Result(")) return null
    val serializerExpression = Regex(
        """(?s)public\s+RecipeSerializer\s*<\s*\?\s*>\s+getType\s*\(\s*\)\s*\{\s*return\s+((?:[A-Za-z_$][\w$]*\.)+[A-Z][A-Z0-9_]*)\.get\s*\(\s*\)\s*;\s*\}"""
    ).find(source)?.groupValues?.get(1) ?: return null
    val recipeClass = recipeSerializerFactoryHints.fieldToRecipeClass[serializerExpression] ?: return null
    var changed = false
    val result = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.accept\(\s*new\s+Result\s*\(([^;\r\n]+)\)\s*\)\s*;"""
    ).replace(source) { match ->
        val args = splitTopLevelJavaArgs(match.groupValues[3]).map { it.trim() }
        if (args.size < 6) return@replace match.value
        val output = match.groupValues[2]
        val id = args[0]
        val input = args[1]
        val count = args[2]
        val cost = args[3]
        val rows = args[4]
        val key = args[5]
        changed = true
        "${match.groupValues[1]}ShapedRecipePattern pattern = ShapedRecipePattern.of($key, $rows);\n" +
            "${match.groupValues[1]}$output.accept($id, new $recipeClass($cost, $input, $count, pattern), null);"
    }
    return if (changed) result else null
}

internal fun StructuralRefactorPass.migrateDataPackRegistryMapCodecSource(
    source: String,
    mapCodecConstantOwners: Set<String>
): String {
    if (mapCodecConstantOwners.isEmpty() || !source.contains(".dataPackRegistry(")) return source

    var result = source
    var changed = false
    var cursor = 0
    val token = ".dataPackRegistry"
    while (cursor < result.length) {
        val tokenIndex = result.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = result.indexOf('(', tokenIndex + token.length)
        if (openParen < 0) break
        val closeParen = findMatchingParen(result, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val migratedArgs = args.map { arg ->
            val trimmed = arg.trim()
            val owner = mapCodecCodecReferenceOwner(trimmed)
            if (owner != null && owner in mapCodecConstantOwners) {
                changed = true
                "${trimmed}.codec()"
            } else {
                arg
            }
        }
        if (migratedArgs != args) {
            val replacement = migratedArgs.joinToString(", ")
            result = result.substring(0, openParen + 1) + replacement + result.substring(closeParen)
            cursor = openParen + replacement.length + 1
        } else {
            cursor = closeParen + 1
        }
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateRegistrySetBuilderBuildPatchSource(source: String): String {
    if (!source.contains(".buildPatch(") ||
        !source.contains("DatapackBuiltinEntriesProvider") ||
        !source.contains("extends DatapackBuiltinEntriesProvider")) {
        return source
    }
    val builderFields = Regex(
        """\b(?:public|protected|private)?\s*static\s+final\s+RegistrySetBuilder\s+([A-Za-z_$][\w$]*)\s*="""
    ).findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (builderFields.isEmpty()) return source

    var result = source
    var changed = false
    builderFields.forEach { builderField ->
        val methodPattern = Regex(
            """(?s)\n[ \t]*public\s+static\s+HolderLookup\.Provider\s+createLookup\s*\(\s*\)\s*\{\s*return\s+${Regex.escape(builderField)}\.buildPatch\s*\(\s*RegistryAccess\.fromRegistryOfRegistries\s*\(\s*BuiltInRegistries\.REGISTRY\s*\)\s*,\s*VanillaRegistries\.createLookup\s*\(\s*\)\s*\)\s*;\s*\}\s*"""
        )
        val migrated = methodPattern.replace(result, "\n")
        if (migrated != result) {
            changed = true
            result = migrated
        }
    }
    if (!changed) return source
    listOf(
        "net.minecraft.core.RegistryAccess",
        "net.minecraft.core.registries.BuiltInRegistries",
        "net.minecraft.data.registries.VanillaRegistries"
    ).forEach { importName ->
        val candidate = removeImport(result, importName)
        val simpleName = importName.substringAfterLast('.')
        if (!Regex("""\b${Regex.escape(simpleName)}\b""").containsMatchIn(candidate)) {
            result = candidate
        }
    }
    return result
}

internal fun StructuralRefactorPass.mapCodecCodecReferenceOwner(expression: String): String? {
    if (expression.endsWith(".codec()")) return null
    val match = Regex("""^(?:[A-Za-z_$][\w$]*\.)*([A-Z][A-Za-z0-9_$]*)\.CODEC$""").find(expression)
    return match?.groupValues?.get(1)
}

internal fun StructuralRefactorPass.migrateRegistryFileCodecMapCodecSource(
    source: String,
    mapCodecConstantOwners: Set<String>
): String {
    if (mapCodecConstantOwners.isEmpty() || !source.contains("RegistryFileCodec.create(")) return source
    val localClass = Regex("""\b(?:class|record|enum)\s+([A-Za-z_$][\w$]*)\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
    var changed = false
    val result = rewriteJavaInvocationArguments(source, "RegistryFileCodec.create") { args ->
        if (args.size < 2) return@rewriteJavaInvocationArguments null
        val codec = args[1].trim()
        val owner = mapCodecCodecReferenceOwner(codec)
        val isLocalCodec = codec == "CODEC" && localClass != null && localClass in mapCodecConstantOwners
        if ((owner == null || owner !in mapCodecConstantOwners) && !isLocalCodec) {
            return@rewriteJavaInvocationArguments null
        }
        val migrated = args.toMutableList()
        migrated[1] = "$codec.codec()"
        changed = true
        migrated
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateLegacyFunctionalInterfaceMapCodecSource(source: String): String {
    if (!source.contains("@FunctionalInterface") ||
        !source.contains("Codec<? extends") ||
        !source.contains("getCodec()")
    ) {
        return source
    }
    var result = Regex("""\bCodec\s*<\s*\?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s+getCodec\s*\(\s*\)\s*;""")
        .replace(source) { match ->
            "MapCodec<? extends ${match.groupValues[1]}> getCodec();"
        }
    if (result == source) return source
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    val withoutCodecImport = removeImport(result, "com.mojang.serialization.Codec")
    if (!Regex("""\bCodec\b""").containsMatchIn(withoutCodecImport)) {
        result = withoutCodecImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyForgeBusBindingCalls(source: String): String {
    if (!source.contains("Bindings.getForgeBus().get()")) return source
    var result = source.replace("Bindings.getForgeBus().get()", "NeoForge.EVENT_BUS")
    result = addImportIfMissing(result, "net.neoforged.neoforge.common.NeoForge")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBiomeSourceMapCodecSource(source: String): String {
    if (!source.contains("extends BiomeSource") || !source.contains("Codec<") || !source.contains("RecordCodecBuilder.create")) {
        return source
    }
    val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+BiomeSource\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return source
    var result = source
    result = Regex("""Codec\s*<\s*${Regex.escape(className)}\s*>\s+([A-Z0-9_]*CODEC)\s*=\s*RecordCodecBuilder\.create\(""")
        .replace(result, "MapCodec<$className> $1 = RecordCodecBuilder.mapCodec(")
    result = Regex("""protected\s+Codec\s*<\s*\?\s+extends\s+BiomeSource\s*>\s+codec\s*\(""")
        .replace(result, "protected MapCodec<? extends BiomeSource> codec(")
    if (result == source) return source
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    return result
}

internal fun StructuralRefactorPass.normalizeBiomeSourceMapCodecWitness(source: String): String {
    if (!source.contains("extends BiomeSource") || !source.contains("RecordCodecBuilder.<")) return source
    val biomeSourceTypes = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\b[^{;]*\bextends\s+(?:[A-Za-z_$][\w$]*\.)?BiomeSource\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (biomeSourceTypes.isEmpty()) return source
    var result = source
    biomeSourceTypes.forEach { typeName ->
        result = result.replace(
            "RecordCodecBuilder.<$typeName>mapCodec(",
            "RecordCodecBuilder.mapCodec("
        )
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyTopElementBufferSource(source: String): String {
    if (!source.contains("implements IElement") || !source.contains("void write(FriendlyByteBuf")) return source
    return Regex("""(?m)^([ \t]*public\s+void\s+)write(\s*\(\s*FriendlyByteBuf\s+[A-Za-z_$][\w$]*\s*\))""")
        .replace(source, "$1toBytes$2")
}

internal fun StructuralRefactorPass.migrateDimensionSpecialEffectsRenderSkySource(source: String): String {
    if (!source.contains("extends DimensionSpecialEffects") || !source.contains("renderSky(") || !source.contains("PoseStack")) {
        return source
    }
    val pattern = Regex(
        """(?s)(public\s+boolean\s+renderSky\s*\(\s*ClientLevel\s+([A-Za-z_$][\w$]*)\s*,\s*int\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+([A-Za-z_$][\w$]*)\s*,\s*)PoseStack\s+([A-Za-z_$][\w$]*)(\s*,\s*Camera\s+([A-Za-z_$][\w$]*)\s*,\s*Matrix4f\s+([A-Za-z_$][\w$]*)\s*,\s*boolean\s+([A-Za-z_$][\w$]*)\s*,\s*Runnable\s+([A-Za-z_$][\w$]*)\s*\)\s*\{)"""
    )
    var changed = false
    val result = pattern.replace(source) { match ->
        val poseName = match.groupValues[5]
        changed = true
        match.groupValues[1] + "Matrix4f modelViewMatrix" + match.groupValues[6] +
            "\n        PoseStack $poseName = new PoseStack();" +
            "\n        $poseName.mulPose(modelViewMatrix);"
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateLegacyLootProviderHolderLookupSource(
    source: String,
    lootTableProviderClasses: Set<String>,
    lootTableProviderFactoryMethods: Map<String, Set<String>>
): String {
    if (!source.contains("LootTableProvider") &&
        lootTableProviderClasses.none { source.contains("new $it(") } &&
        lootTableProviderFactoryMethods.none { (owner, methods) -> methods.any { source.contains("$owner.$it(") } }) {
        return source
    }

    var result = source
    var changed = false
    result = migrateLegacyLootTableSubProviderFactorySource(result).also {
        if (it != result) changed = true
    }
    result = migrateLegacyLootTableProviderFactorySource(result).also {
        if (it != result) changed = true
    }
    val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+LootTableProvider\b""")
        .find(result)
        ?.groupValues
        ?.get(1)
    if (className != null) {
        val constructorPattern = Regex(
            """(?s)public\s+${Regex.escape(className)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\s*\((.*?)\)\s*;\s*\}"""
        )
        result = constructorPattern.replace(result) { match ->
            val args = splitTopLevelJavaArgs(match.groupValues[2])
            if (args.size != 3) return@replace match.value
            changed = true
            "public $className(PackOutput ${match.groupValues[1]}, CompletableFuture<HolderLookup.Provider> lookupProvider) {\n\t\tsuper(${args[0]}, ${args[1]}, ${args[2]}, lookupProvider);\n\t}"
        }
        if (result.contains("public $className(PackOutput") && result.contains("CompletableFuture<HolderLookup.Provider>")) {
            result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
            result = addImportIfMissing(result, "java.util.concurrent.CompletableFuture")
        }
        result = Regex(
            """(?s)(@Override\s*)?(protected\s+void\s+validate\s*\()\s*Map\s*<\s*ResourceLocation\s*,\s*LootTable\s*>\s+([A-Za-z_$][\w$]*)\s*,\s*ValidationContext\s+([A-Za-z_$][\w$]*)\s*\)"""
        ).replace(result) { match ->
            changed = true
            "${match.groupValues[1]}${match.groupValues[2]}WritableRegistry<LootTable> writableregistry, ValidationContext ${match.groupValues[4]}, ProblemReporter.Collector problemreporter\$collector)"
        }
        if (result.contains("WritableRegistry<LootTable>")) {
            result = addImportIfMissing(result, "net.minecraft.core.WritableRegistry")
            result = addImportIfMissing(result, "net.minecraft.util.ProblemReporter")
            val withoutMap = removeImport(result, "java.util.Map")
            if (!Regex("""\bMap\s*<""").containsMatchIn(withoutMap) && !Regex("""\bMap\b""").containsMatchIn(withoutMap)) {
                result = withoutMap
            }
            val withoutResourceLocation = removeImport(result, "net.minecraft.resources.ResourceLocation")
            if (!Regex("""\bResourceLocation\b""").containsMatchIn(withoutResourceLocation)) {
                result = withoutResourceLocation
            }
        }
    }

    val providerExpression = inferHolderLookupProviderExpression(result)
    if (providerExpression != null) {
        result = rewriteJavaNew(result, "LootTableProvider") { args ->
            if (args.size != 3) return@rewriteJavaNew null
            changed = true
            "new LootTableProvider(${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()}, $providerExpression)"
        }
        result = Regex("""new\s+([A-Za-z_$][\w$]*)\s*\(\s*([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match ->
                val newClass = match.groupValues[1]
                if (newClass !in lootTableProviderClasses) return@replace match.value
                changed = true
                "new $newClass(${match.groupValues[2]}, $providerExpression)"
            }
        lootTableProviderFactoryMethods.forEach { (owner, methods) ->
            methods.forEach { method ->
                val beforeFactoryCalls = result
                result = migrateMethodCalls(result, "$owner.$method") { args ->
                    if (args.size == 1) args + providerExpression else args
                }
                if (result != beforeFactoryCalls) changed = true
            }
        }
    }

    if (changed && result.contains("CompletableFuture<HolderLookup.Provider>")) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
        result = addImportIfMissing(result, "java.util.concurrent.CompletableFuture")
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateLegacyLootTableProviderFactorySource(source: String): String {
    if (!source.contains("new LootTableProvider(")) return source
    var result = source
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:public|protected|private)\s+static\s+LootTableProvider\s+[A-Za-z_$][\w$]*\s*\("""
    )
    val methodRanges = methodPattern.findAll(result)
        .mapNotNull { match ->
            val openBrace = result.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (openBrace < 0 || closeBrace < 0) null else match.range.first..closeBrace
        }
        .toList()
        .asReversed()

    for (range in methodRanges) {
        val method = result.substring(range.first, range.last + 1)
        val declaration = Regex(
            """(?s)^([ \t]*(?:public|protected|private)\s+static\s+LootTableProvider\s+[A-Za-z_$][\w$]*\s*)\((.*?)\)(\s*\{)"""
        ).find(method) ?: continue
        val params = splitTopLevelJavaArgs(declaration.groupValues[2])
        if (params.any { it.contains("HolderLookup.Provider") }) continue
        val outputParam = params.mapNotNull(::parseJavaParameter)
            .firstOrNull { (type, _) -> type.substringAfterLast('.') == "PackOutput" }
            ?: continue
        val lookupName = "lookupProvider"
        var migratedMethod = method
        var migratedConstructor = false
        migratedMethod = rewriteJavaNew(migratedMethod, "LootTableProvider") { args ->
            if (args.size != 3) return@rewriteJavaNew null
            if (args[0].trim() != outputParam.second) return@rewriteJavaNew null
            migratedConstructor = true
            "new LootTableProvider(${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()}, $lookupName)"
        }
        if (!migratedConstructor) continue
        val paramsRange = declaration.groups[2]?.range ?: continue
        val newParams = (params + "CompletableFuture<HolderLookup.Provider> $lookupName").joinToString(", ")
        migratedMethod = migratedMethod.substring(0, paramsRange.first) + newParams + migratedMethod.substring(paramsRange.last + 1)
        result = result.substring(0, range.first) + migratedMethod + result.substring(range.last + 1)
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyLootTableSubProviderFactorySource(source: String): String {
    if (!source.contains("implements LootTableSubProvider")) return source
    val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+implements\s+LootTableSubProvider\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return source
    if (Regex("""\b${Regex.escape(className)}\s*\(\s*HolderLookup\.Provider\s+""").containsMatchIn(source) ||
        Regex("""\b${Regex.escape(className)}\s*\(\s*net\.minecraft\.core\.HolderLookup\.Provider\s+""").containsMatchIn(source)) {
        return source
    }

    val classHeader = Regex("""(?m)(public\s+class\s+${Regex.escape(className)}\s+implements\s+LootTableSubProvider\s*\{)""")
        .find(source)
        ?: return source
    val constructor = "\n\n    public $className(HolderLookup.Provider registries) {\n    }\n"
    var result = source.substring(0, classHeader.range.last + 1) +
        constructor +
        source.substring(classHeader.range.last + 1)
    result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyGlobalLootModifierProviderSource(
    source: String,
    providerClasses: Set<String>
): String {
    if (!source.contains("GlobalLootModifierProvider") && providerClasses.none { source.contains("new $it(") }) return source
    var result = source
    var changed = false
    val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+GlobalLootModifierProvider\b""")
        .find(result)
        ?.groupValues
        ?.get(1)
    if (className != null) {
        result = Regex(
            """(?s)public\s+${Regex.escape(className)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\s*\(\s*\1\s*,\s*([^;]+?)\s*\)\s*;\s*\}"""
        ).replace(result) { match ->
            changed = true
            "public $className(PackOutput ${match.groupValues[1]}, CompletableFuture<HolderLookup.Provider> lookupProvider) {\n\t\tsuper(${match.groupValues[1]}, lookupProvider, ${match.groupValues[2].trim()});\n\t}"
        }
        if (result.contains("public $className(PackOutput") && result.contains("CompletableFuture<HolderLookup.Provider>")) {
            result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
            result = addImportIfMissing(result, "java.util.concurrent.CompletableFuture")
        }
    }

    val providerExpression = inferHolderLookupProviderExpression(result)
    if (providerExpression != null) {
        result = Regex("""new\s+([A-Za-z_$][\w$]*)\s*\(\s*([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match ->
                val newClass = match.groupValues[1]
                if (newClass !in providerClasses) return@replace match.value
                changed = true
                "new $newClass(${match.groupValues[2]}, $providerExpression)"
            }
        providerClasses.forEach { className ->
            result = migrateMethodCalls(result, "new $className") { args ->
                if (args.size <= 2) return@migrateMethodCalls args
                val lookupArg = args.getOrNull(1)?.trim() ?: return@migrateMethodCalls args
                val extrasAreDuplicateLookups = args.drop(2).all { extra ->
                    val trimmed = extra.trim()
                    trimmed == lookupArg || trimmed == providerExpression
                }
                if (!extrasAreDuplicateLookups) return@migrateMethodCalls args
                changed = true
                args.take(2)
            }
        }
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateLegacyPackMetadataSectionSource(source: String): String {
    if (!source.contains("new PackMetadataSection(")) return source
    var result = source
    var changed = false
    val packTypeMapValues = collectPackMetadataMapFormatValues(result)
    val consumedPackTypeMaps = mutableSetOf<String>()
    var cursor = 0
    val token = "new PackMetadataSection"
    while (cursor < result.length) {
        val tokenIndex = result.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = result.indexOf('(', tokenIndex + token.length)
        if (openParen < 0) break
        val closeParen = findMatchingParen(result, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val supportedFormatArg = args.getOrNull(2)?.trim()
        val packFormat = when {
            args.size != 3 -> null
            supportedFormatArg?.contains("Collectors.toMap") == true -> args[1].trim()
            supportedFormatArg != null && supportedFormatArg in packTypeMapValues -> {
                consumedPackTypeMaps += supportedFormatArg
                packTypeMapValues.getValue(supportedFormatArg)
            }
            supportedFormatArg?.startsWith("Map.of(") == true -> packMetadataMapOfFormatValue(supportedFormatArg)
            else -> null
        }
        if (packFormat != null) {
            val replacementArgs = listOf(
                args[0].trim(),
                packFormat,
                "java.util.Optional.of(new InclusiveRange<>(0, Integer.MAX_VALUE))"
            ).joinToString(",\n\t\t\t\t\t\t")
            result = result.substring(0, openParen + 1) + replacementArgs + result.substring(closeParen)
            changed = true
            cursor = openParen + replacementArgs.length + 1
        } else {
            cursor = closeParen + 1
        }
    }
    if (!changed) return source
    for (mapName in consumedPackTypeMaps) {
        val declarationPattern = Regex(
            """(?m)^[ \t]*(?:java\.util\.)?Map\s*<\s*PackType\s*,\s*Integer\s*>\s+${Regex.escape(mapName)}\s*=\s*Map\.of\([^\r\n;]+;\s*\r?\n"""
        )
        val withoutDeclaration = declarationPattern.replace(result, "")
        if (!Regex("""\b${Regex.escape(mapName)}\b""").containsMatchIn(withoutDeclaration)) {
            result = withoutDeclaration
        }
    }
    result = addImportIfMissing(result, "net.minecraft.util.InclusiveRange")
    if (!result.contains("Arrays.")) {
        result = removeImport(result, "java.util.Arrays")
    }
    if (!result.contains("Function.")) {
        result = removeImport(result, "java.util.function.Function")
    }
    if (!result.contains("Collectors.")) {
        result = removeImport(result, "java.util.stream.Collectors")
    }
    val withoutMapImport = removeImport(result, "java.util.Map")
    if (!Regex("""\bMap\s*[.<]""").containsMatchIn(withoutMapImport)) {
        result = withoutMapImport
    }
    return result
}

internal fun StructuralRefactorPass.collectPackMetadataMapFormatValues(source: String): Map<String, String> {
    val values = linkedMapOf<String, String>()
    Regex("""(?m)\b(?:java\.util\.)?Map\s*<\s*PackType\s*,\s*Integer\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*(Map\.of\([^\r\n;]+)\s*;""")
        .findAll(source)
        .forEach { match ->
            val mapName = match.groupValues[1]
            val value = packMetadataMapOfFormatValue(match.groupValues[2])
            if (value != null) values[mapName] = value
        }
    return values
}

internal fun StructuralRefactorPass.packMetadataMapOfFormatValue(expression: String): String? {
    val openParen = expression.indexOf('(')
    if (openParen < 0) return null
    val closeParen = findMatchingParen(expression, openParen)
    if (closeParen < 0) return null
    val entries = splitTopLevelJavaArgs(expression.substring(openParen + 1, closeParen))
    if (entries.size != 2) return null
    if (!Regex("""^PackType\.[A-Z_]+$""").matches(entries[0].trim())) return null
    return entries[1].trim()
}

internal fun StructuralRefactorPass.migrateDuplicateHolderLookupConstructorArguments(source: String): String {
    if (!source.contains("HolderLookup.Provider") && !source.contains("getLookupProvider(")) return source
    val lookupExpressions = holderLookupProviderExpressions(source)
    if (lookupExpressions.isEmpty()) return source

    var result = source
    var cursor = 0
    val constructorPattern = Regex("""new\s+[A-Za-z_$][\w$]*(?:\s*<[^;\r\n(){}]*>)?\s*\(""")
    while (true) {
        val match = constructorPattern.find(result, cursor) ?: break
        val openParen = result.indexOf('(', match.range.first)
        val closeParen = if (openParen >= 0) findMatchingParen(result, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size < 3) {
            cursor = closeParen + 1
            continue
        }
        val seenLookupArgs = mutableSetOf<String>()
        var changed = false
        val keptArgs = args.filterIndexed { index, arg ->
            val trimmed = arg.trim()
            if (index == 0 || trimmed !in lookupExpressions) {
                true
            } else if (trimmed in seenLookupArgs) {
                changed = true
                false
            } else {
                seenLookupArgs += trimmed
                true
            }
        }
        if (!changed) {
            cursor = closeParen + 1
            continue
        }
        val replacementArgs = keptArgs.joinToString(", ") { it.trim() }
        result = result.substring(0, openParen + 1) + replacementArgs + result.substring(closeParen)
        cursor = openParen + 1 + replacementArgs.length
    }
    return result
}

internal fun StructuralRefactorPass.holderLookupProviderExpressions(source: String): Set<String> {
    val expressions = linkedSetOf<String>()
    Regex(
        """(?:java\.util\.concurrent\.)?CompletableFuture\s*<\s*HolderLookup\.Provider\s*>\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(source).forEach { expressions += it.groupValues[1] }
    Regex("""\b([A-Za-z_$][\w$]*)\.getLookupProvider\(\)""")
        .findAll(source)
        .forEach { expressions += "${it.groupValues[1]}.getLookupProvider()" }
    return expressions
}

internal fun StructuralRefactorPass.normalizeDuplicateHolderLookupConstructorArguments(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val text = javaFile.readText()
            val migrated = migrateDuplicateHolderLookupConstructorArguments(text)
            if (migrated == text) return@forEach
            if (!dryRun) {
                javaFile.writeText(migrated)
            }
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Remove duplicate HolderLookup provider constructor arguments in ${javaFile.fileName}",
                before = "new Provider(..., lookupProvider, lookupProvider)",
                after = "new Provider(..., lookupProvider)",
                confidence = Confidence.HIGH,
                ruleId = "struct-deduplicate-holderlookup-constructor-args"
            ))
        }
    return changes
}

internal fun StructuralRefactorPass.migrateLegacyLootFunctionSource(source: String): String {
    if (!source.contains("SetNbtFunction") &&
        !source.contains("EnchantWithLevelsFunction.enchantWithLevels(") &&
        !source.contains("SetEnchantmentsFunction") &&
        !source.contains("EnchantRandomlyFunction") &&
        !source.contains("LootingEnchantFunction") &&
        !source.contains("SetNameFunction.setName(") &&
        !source.contains("SetContainerContents.setContents(") &&
        !source.contains(".serializeNBT()")
    ) {
        return source
    }

    var result = migrateExactLootingMixinTarget(source)
    var changed = result != source
    val needsProvider =
        result.contains("EnchantWithLevelsFunction.enchantWithLevels(") ||
            result.contains("new SetEnchantmentsFunction.Builder") ||
            result.contains("new EnchantRandomlyFunction.Builder().withEnchantment(")
    if (needsProvider && result.contains("implements LootTableSubProvider")) {
        val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+implements\s+LootTableSubProvider\b""")
            .find(result)
            ?.groupValues
            ?.get(1)
        if (className != null && !result.contains("private final HolderLookup.Provider registries;")) {
            result = Regex("""(?m)^([ \t]*)@Override\s*\r?\n([ \t]*)public\s+void\s+generate\(""")
                .replace(result, "    private final HolderLookup.Provider registries;\n\n    public $className(HolderLookup.Provider registries) {\n        this.registries = registries;\n    }\n\n$1@Override\n$2public void generate(")
            changed = true
        }
    }

    if (result.contains("SetNbtFunction.setTag")) {
        result = Regex(
            """SetNbtFunction\.setTag\(\s*Util\.make\(\s*new\s+CompoundTag\(\)\s*,\s*(?:\(\s*)?([A-Za-z_$][\w$]*)(?:\s*\))?\s*->\s*\1\.putString\(\s*"Potion"\s*,\s*"minecraft:([a-z0-9_]+)"\s*\)\s*\)\s*\)"""
        ).replace(result) { match ->
            changed = true
            "SetPotionFunction.setPotion(Potions.${match.groupValues[2].uppercase()})"
        }
        migrateLegacySetNbtContainerContentsSource(result)?.let { migrated ->
            result = migrated
            changed = true
        }
        result = rewriteJavaCall(result, "setTag") { receiver, args ->
            if (receiver != "SetNbtFunction" || args.size != 1) return@rewriteJavaCall null
            changed = true
            "SetCustomDataFunction.setCustomData(${args[0].trim()})"
        }
    }
    if (result.contains("SetNbtFunction.set(")) {
        result = rewriteJavaCall(result, "set") { receiver, args ->
            if (receiver != "SetNbtFunction" || args.size != 2) return@rewriteJavaCall null
            changed = true
            "SetComponentsFunction.setComponent(${args[0].trim()}, ${args[1].trim()})"
        }
    }

    if (result.contains("EnchantWithLevelsFunction.enchantWithLevels(")) {
        result = rewriteJavaCall(result, "enchantWithLevels") { receiver, args ->
            if (receiver != "EnchantWithLevelsFunction" || args.size != 1) return@rewriteJavaCall null
            changed = true
            "EnchantWithLevelsFunction.enchantWithLevels(this.registries, ${args[0]})"
        }
        // 1.21 picks enchantments by tag: the registries form defaults to ON_RANDOM_LOOT (the old
        // allowTreasure()), while the old default excluded treasure, which is the NON_TREASURE tag.
        val levels = """EnchantWithLevelsFunction\.enchantWithLevels\(this\.registries,\s*((?:[^()]|\([^()]*\))+?)\)"""
        result = replaceExecutableRegex(result, Regex("""$levels(?!\.(?:allowTreasure|fromOptions)\()""")) { match ->
            changed = true
            "${match.value}.fromOptions(this.registries.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.tags.EnchantmentTags.NON_TREASURE))"
        }
        result = replaceExecutableRegex(result, Regex("""($levels)\.allowTreasure\(\)""")) { match ->
            changed = true
            match.groupValues[1]
        }
    }
    if (result.contains("this.registries")) {
        val demoted = demoteStaticRegistriesHelpers(result)
        if (demoted != result) {
            result = demoted
            changed = true
        }
    }

    if (result.contains("LootingEnchantFunction.lootingMultiplier(")) {
        result = rewriteJavaCall(result, "lootingMultiplier") { receiver, args ->
            if (receiver != "LootingEnchantFunction" || args.size != 1) return@rewriteJavaCall null
            changed = true
            "EnchantedCountIncreaseFunction.lootingMultiplier(this.registries, ${args[0].trim()})"
        }
        result = result.replace(
            "import net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction;",
            "import net.minecraft.world.level.storage.loot.functions.EnchantedCountIncreaseFunction;"
        )
    }

    if (result.contains("SetNameFunction.setName(")) {
        result = rewriteJavaCall(result, "setName") { receiver, args ->
            if (receiver != "SetNameFunction" || args.size != 1) return@rewriteJavaCall null
            changed = true
            "SetNameFunction.setName(${args[0].trim()}, SetNameFunction.Target.CUSTOM_NAME)"
        }
    }

    if (result.contains(".serializeNBT()") && result.contains("this.registries")) {
        result = Regex("""((?:new\s+)?[A-Za-z_$][\w$]*(?:\([^;]*?\))?)\.serializeNBT\(\)""")
            .replace(result) { match ->
                changed = true
                "${match.groupValues[1]}.save(this.registries)"
            }
    }

    result = Regex("""Enchantments\.ALL_DAMAGE_PROTECTION\b""").replace(result) {
        changed = true
        "Enchantments.PROTECTION"
    }
    result = Regex("""Enchantments\.FALL_PROTECTION\b""").replace(result) {
        changed = true
        "Enchantments.FEATHER_FALLING"
    }

    if (result.contains(".withEnchantment(")) {
        result = rewriteJavaCall(result, "withEnchantment") { receiver, args ->
            if (args.size != 2) return@rewriteJavaCall null
            val enchantment = args[0].trim()
            val holderExpr = enchantmentHolderExpression(enchantment) ?: return@rewriteJavaCall null
            changed = true
            "$receiver.withEnchantment($holderExpr, ${args[1].trim()})"
        }
        result = Regex("""\.withEnchantment\(\s*((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*\.get\(\))\s*\)""")
            .replace(result) { match ->
                val holderExpr = enchantmentHolderExpression(match.groupValues[1]) ?: return@replace match.value
                changed = true
                ".withEnchantment($holderExpr)"
            }
    }

    if (result.contains("SetContainerContents.setContents(")) {
        result = rewriteJavaCall(result, "setContents") { receiver, args ->
            if (receiver != "SetContainerContents" || args.size != 1) return@rewriteJavaCall null
            val component = args[0].trim()
            if (component.startsWith("ContainerComponentManipulators.")) return@rewriteJavaCall null
            changed = true
            "SetContainerContents.setContents(ContainerComponentManipulators.CONTAINER)"
        }
    }

    if (!changed) return source
    if (result.contains("HolderLookup.Provider")) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    if (result.contains("lookupOrThrow(Registries.ENCHANTMENT)")) {
        result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
    }
    if (result.contains("SetPotionFunction.setPotion(")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.alchemy.Potions")
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.functions.SetPotionFunction")
        result = result.replace(Regex("""(?m)^[ \t]*import\s+net\.minecraft\.Util;\s*\r?\n"""), "")
        result = result.replace(Regex("""(?m)^[ \t]*import\s+net\.minecraft\.nbt\.CompoundTag;\s*\r?\n"""), "")
        result = result.replace(Regex("""(?m)^[ \t]*import\s+net\.minecraft\.world\.level\.storage\.loot\.functions\.SetNbtFunction;\s*\r?\n"""), "")
    }
    if (result.contains("SetComponentsFunction.setComponent(")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.functions.SetComponentsFunction")
        result = removeUnusedSimpleImport(
            result,
            "net.minecraft.world.level.storage.loot.functions.SetNbtFunction",
            "SetNbtFunction"
        )
    }
    if (result.contains("SetCustomDataFunction.setCustomData(")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.functions.SetCustomDataFunction")
        result = removeUnusedSimpleImport(
            result,
            "net.minecraft.world.level.storage.loot.functions.SetNbtFunction",
            "SetNbtFunction"
        )
    }
    if (result.contains("SetContainerContents.setContents(")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.ContainerComponentManipulators")
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.entries.LootItem")
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.functions.SetContainerContents")
        if (result.contains("SetItemCountFunction.setCount(")) {
            result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.functions.SetItemCountFunction")
            result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.providers.number.ConstantValue")
        }
        listOf(
            "net.minecraft.Util",
            "net.minecraft.nbt.CompoundTag",
            "net.minecraft.nbt.ListTag",
            "net.minecraft.world.level.storage.loot.functions.SetNbtFunction"
        ).forEach { importName ->
            val withoutImport = removeImport(result, importName)
            val simple = importName.substringAfterLast('.')
            if (!Regex("""\b${Regex.escape(simple)}\b""").containsMatchIn(withoutImport)) {
                result = withoutImport
            }
        }
    }
    return result
}

private fun StructuralRefactorPass.migrateExactLootingMixinTarget(source: String): String {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    val legacyFunction = "net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction"
    val targetFunction = "net.minecraft.world.level.storage.loot.functions.EnchantedCountIncreaseFunction"
    fun hasExactImport(qualified: String): Boolean = unit.imports.any {
        !it.isStatic && !it.isAsterisk && it.nameAsString == qualified
    }
    if (!hasExactImport(legacyFunction) ||
        !hasExactImport("org.spongepowered.asm.mixin.Mixin") ||
        unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == "LootingEnchantFunction" }
    ) {
        return source
    }

    val targets = unit.findAll(ClassOrInterfaceDeclaration::class.java)
        .flatMap { declaration ->
            declaration.annotations.filterIsInstance<SingleMemberAnnotationExpr>()
        }
        .mapNotNull { annotation ->
            if (annotation.nameAsString != "Mixin") return@mapNotNull null
            val target = annotation.memberValue as? ClassExpr ?: return@mapNotNull null
            target.takeIf { it.typeAsString == "LootingEnchantFunction" }
        }
    if (targets.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    targets.forEach { it.setType("EnchantedCountIncreaseFunction") }
    unit.imports.filter {
        !it.isStatic && !it.isAsterisk && it.nameAsString == legacyFunction
    }.forEach { it.remove() }
    if (!hasExactImport(targetFunction)) {
        unit.addImport(targetFunction)
    }
    return LexicalPreservingPrinter.print(unit)
}

internal fun StructuralRefactorPass.migrateLegacySetNbtContainerContentsSource(source: String): String? {
    if (!source.contains("SetNbtFunction.setTag") || !source.contains(".put(\"Items\"")) return null
    var result = source
    var changed = false
    val pattern = Regex(
        """(?s)SetNbtFunction\.setTag\(\s*Util\.make\(\s*new\s+CompoundTag\(\)\s*,\s*(?:\(\s*)?([A-Za-z_$][\w$]*)(?:\s*\))?\s*->\s*\{(.*?)\}\s*\)\s*\)"""
    )
    result = pattern.replace(result) { match ->
        val nbtVar = match.groupValues[1]
        val body = match.groupValues[2]
        val listVar = Regex("""\bListTag\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+ListTag\s*\(\s*\)\s*;""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: return@replace match.value
        if (!Regex("""\b${Regex.escape(nbtVar)}\.put\s*\(\s*"Items"\s*,\s*${Regex.escape(listVar)}\s*\)\s*;""").containsMatchIn(body)) {
            return@replace match.value
        }
        val entries = Regex(
            """(?s)\b${Regex.escape(listVar)}\.add\s*\(\s*new\s+ItemStack\s*\((.*?)\)\s*\.\s*(?:save\s*\([^)]*\)|serializeNBT\s*\(\s*\))\s*\)\s*;"""
        ).findAll(body).mapNotNull { itemMatch ->
            val args = splitTopLevelJavaArgs(itemMatch.groupValues[1]).map { it.trim() }
            if (args.isEmpty()) return@mapNotNull null
            val base = "LootItem.lootTableItem(${args[0]})"
            if (args.size >= 2) {
                "$base.apply(SetItemCountFunction.setCount(ConstantValue.exactly(${args[1]})))"
            } else {
                base
            }
        }.toList()
        if (entries.isEmpty()) return@replace match.value
        changed = true
        "SetContainerContents.setContents(ContainerComponentManipulators.BUNDLE_CONTENTS)" +
            entries.joinToString("") { "\n\t\t\t\t\t\t\t\t\t\t.withEntry($it)" }
    }
    return if (changed) result else null
}

internal fun StructuralRefactorPass.migrateLegacyEntityLootSubProviderSource(source: String): String {
    if (!source.contains("extends EntityLootSubProvider") &&
        !source.contains("ENTITY_ON_FIRE") &&
        !source.contains("ResourceKey.create(Registries.LOOT_TABLE")) {
        return source
    }

    var result = source
    var changed = false
    val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+EntityLootSubProvider\b""")
        .find(result)
        ?.groupValues
        ?.get(1)

    if (className != null) {
        val constructorPattern = Regex(
            """(?s)(protected|public)\s+${Regex.escape(className)}\s*\(\s*\)\s*\{\s*super\s*\((.*?)\)\s*;\s*\}"""
        )
        result = constructorPattern.replace(result) { match ->
            val args = splitTopLevelJavaArgs(match.groupValues[2]).map { it.trim() }
            if (args.isEmpty()) return@replace match.value
            changed = true
            "${match.groupValues[1]} $className(HolderLookup.Provider registries) {\n\t\tsuper(${(args + "registries").joinToString(", ")});\n\t}"
        }
    }

    if (result.contains("ENTITY_ON_FIRE")) {
        result = Regex(
            """LootItemEntityPropertyCondition\.hasProperties\(\s*LootContext\.EntityTarget\.THIS\s*,\s*ENTITY_ON_FIRE\s*\)"""
        ).replace(result) {
            changed = true
            "this.shouldSmeltLoot()"
        }
    }

    result = Regex(
        """ResourceKey\.create\(\s*Registries\.LOOT_TABLE\s*,\s*([^()]+\.getDefaultLootTable\(\)|BuiltInLootTables\.[A-Z0-9_]+|[A-Za-z_$][\w$]*LootTables\.[A-Z0-9_]+)\s*\)"""
    ).replace(result) { match ->
        changed = true
        match.groupValues[1].trim()
    }

    if (!changed) return source
    if (result.contains("HolderLookup.Provider")) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    return result
}

internal fun StructuralRefactorPass.enchantmentHolderExpression(expression: String): String? {
    if (expression.contains("lookupOrThrow(Registries.ENCHANTMENT)") || expression.contains("Holder<")) return null
    if (expression.startsWith("Enchantments.") || expression.matches(Regex("""(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*\.get\(\)"""))) {
        val keyExpression = if (expression.endsWith(".get()")) expression.removeSuffix(".get()") else expression
        return "this.registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow($keyExpression)"
    }
    return null
}

internal fun StructuralRefactorPass.migrateFinalMapDecorationSubclassSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val composition = migrateLegacyMapDecorationCompositionSource(source)
    if (composition != source || executableCode.contains("extends MapDecoration")) {
        return composition
    }
    if (!executableCode.contains("extends MapDecoration")) return source

    var result = source
    var changed = false
    val pattern = Regex("""(?s)public\s+static\s+class\s+([A-Za-z_$][\w$]*)\s+extends\s+MapDecoration\s*\{""")
    var cursor = 0
    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val match = pattern.find(executableResult, cursor) ?: break
        val className = match.groupValues[1]
        val openBrace = executableResult.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableResult, openBrace) else -1
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val replacementBody = rewriteMapDecorationSubclassBody(className, body)
        if (replacementBody == null) {
            cursor = closeBrace + 1
            continue
        }
        result = result.substring(0, match.range.first) +
            "public static class $className {\n$replacementBody\n\t}" +
            result.substring(closeBrace + 1)
        cursor = match.range.first + "public static class $className {\n$replacementBody\n\t}".length
        changed = true
    }

    if (!changed) return source
    result = replaceExecutableRegex(result, Regex("""super\.equals\(o\)\s*&&\s*o\s+instanceof""")) { "o instanceof" }
    result = replaceExecutableRegex(result, Regex("""super\.hashCode\(\)\s*\*\s*31\s*\+""")) { "" }
    result = replaceExecutableRegex(
        result,
        Regex("""(?m)^([ \t]*)@Override\s*\r?\n([ \t]*(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*\r?\n)?[ \t]*public\s+boolean\s+render\s*\()""")
    ) { match -> "${match.groupValues[1]}${match.groupValues[2]}" }
    result = replaceExecutableRegex(result, Regex("""\bType\.TARGET_X\b""")) { "MapDecorationTypes.TARGET_X" }
    result = addExecutableImportIfMissing(result, "net.minecraft.world.level.saveddata.maps.MapDecorationTypes")
    return migrateMapDecorationRecordSource(result)
}

internal fun StructuralRefactorPass.migrateFinalMapDecorationSubclassUsageSource(
    source: String,
    finalMapDecorationClasses: Set<String>
): String {
    var executableCode = maskJavaCommentsAndLiterals(source)
    if (finalMapDecorationClasses.isEmpty() || !executableCode.contains(".decorations.put(")) return source
    var result = source
    finalMapDecorationClasses.forEach { className ->
        executableCode = maskJavaCommentsAndLiterals(result)
        val variables = Regex("""\b(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(className)}\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableCode)
            .map { it.groupValues[1] }
            .toSet()
        variables.forEach { variable ->
            result = replaceExecutableRegex(
                result,
                Regex("""(\.decorations\.put\(\s*[^,\r\n]+,\s*)${Regex.escape(variable)}(\s*\))""")
            ) { match -> "${match.groupValues[1]}$variable.asMapDecoration()${match.groupValues[2]}" }
        }
    }
    return result
}

internal fun StructuralRefactorPass.rewriteMapDecorationSubclassBody(className: String, body: String): String? {
    if (!body.contains("super(")) return null
    var result = body
    val javaKeywords = setOf("false", "return", "static", "true")
    val legacyFieldAccessors = Regex("""(?m)^[ \t]*(?:(?:public|protected|private)\s+(?!static\b)(?:final\s+)?|final\s+)(?!static\b)([A-Za-z_$][\w$<>, ?.\[\]]*)\s+([A-Za-z_$][\w$]*)\s*;\s*$""")
        .findAll(result)
        .map { it.groupValues[1].trim() to it.groupValues[2] }
        .filter { (_, name) -> name != "decoration" && name !in javaKeywords }
        .distinctBy { (_, name) -> name }
        .toList()
    result = Regex("""(?m)^([ \t]*)(?:(?:public|protected|private)\s+)?(?:final\s+)?int\s+featureId\s*;\s*$""")
        .replace(result) { match ->
            val indent = match.groupValues[1]
            "${indent}private final int featureId;\n${indent}private final MapDecoration decoration;"
        }
    val constructorPattern = Regex("""(?s)public\s+${Regex.escape(className)}\s*\((.*?)\)\s*\{\s*super\s*\((.*?)\)\s*;\s*(.*?)\}""")
    var changed = false
    result = constructorPattern.replace(result) { match ->
        val params = match.groupValues[1]
        val args = splitTopLevelJavaArgs(match.groupValues[2])
        val rest = match.groupValues[3]
        if (args.size != 5) return@replace match.value
        val componentArg = args[4].trim()
        val nameArg = when {
            componentArg == "null" -> "java.util.Optional.empty()"
            componentArg.startsWith("java.util.Optional.") -> componentArg
            else -> "java.util.Optional.of($componentArg)"
        }
        changed = true
        "public $className($params) {\n\t\t\tthis.decoration = new MapDecoration(${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()}, ${args[3].trim()}, $nameArg);\n${rest.trimEnd()}\n\t\t}"
    }
    if (!changed) return null
    val accessors = listOf(
        "x" to "byte",
        "y" to "byte",
        "rot" to "byte",
        "type" to "net.minecraft.core.Holder<net.minecraft.world.level.saveddata.maps.MapDecorationType>",
        "name" to "java.util.Optional<net.minecraft.network.chat.Component>"
    )
    val existing = accessors.filterNot { (name, _) -> result.contains(" $name()") }
        .joinToString("\n\n") { (name, type) ->
            "\t\tpublic $type $name() {\n\t\t\treturn this.decoration.$name();\n\t\t}"
        }
    val legacyAccessors = listOf(
        "getX" to "x",
        "getY" to "y",
        "getRot" to "rot"
    ).filterNot { (method, _) ->
        Regex("""(?m)\b(?:public|protected|private)\s+[\w.<>\[\]?]+\s+${Regex.escape(method)}\s*\(""")
            .containsMatchIn(result)
    }
        .joinToString("\n\n") { (method, accessor) ->
            "\t\tpublic byte $method() {\n\t\t\treturn this.decoration.$accessor();\n\t\t}"
        }
    val decorationAccessor = if (result.contains(" asMapDecoration()")) {
        ""
    } else {
        "\t\tpublic MapDecoration asMapDecoration() {\n\t\t\treturn this.decoration;\n\t\t}"
    }
    val legacyGetters = legacyFieldAccessors.joinToString("\n\n") { (type, name) ->
        "\t\tpublic $type ${name}() {\n\t\t\treturn this.$name;\n\t\t}"
    }
    val extras = listOf(existing, legacyAccessors, decorationAccessor, legacyGetters)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
    if (extras.isNotBlank()) {
        result = result.trimEnd() + "\n\n" + extras + "\n"
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyMeleeAttackGoalOverrides(source: String): String {
    if (!source.contains("checkAndPerformAttack")) {
        return source
    }
    var result = source
    result = rewriteExecutableJavaCall(result, "getPerceivedTargetDistanceSquareForMeleeAttack") { receiver, args ->
        if (args.size == 1) "$receiver.distanceToSqr(${args[0].trim()})" else null
    }
    var cursor = 0
    val signaturePattern = Regex(
        """(?m)((?:[ \t]*@\w+(?:\([^)]*\))?\s*)*[ \t]*protected\s+void\s+checkAndPerformAttack\s*\(\s*((?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s+)*(?:net\.minecraft\.world\.entity\.)?LivingEntity)\s+([A-Za-z_$][\w$]*)\s*,\s*double\s+([A-Za-z_$][\w$]*)\s*\)\s*\{)"""
    )
    while (true) {
        val match = signaturePattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        if (openBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val entityType = match.groupValues[2]
        val targetName = match.groupValues[3]
        val distanceName = match.groupValues[4]
        val body = result.substring(openBrace + 1, closeBrace)
        val reachVars = Regex(
            """\b(?:double|float)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:this\.)?getAttackReachSqr\s*\(\s*${Regex.escape(targetName)}\s*\)\s*;"""
        ).findAll(body).map { it.groupValues[1] }.toSet()

        var migratedBody = body
        migratedBody = Regex(
            """super\.checkAndPerformAttack\s*\(\s*${Regex.escape(targetName)}\s*,\s*${Regex.escape(distanceName)}\s*\)"""
        ).replace(migratedBody, "super.checkAndPerformAttack($targetName)")
        migratedBody = Regex(
            """super\.checkAndPerformAttack\s*\(\s*${Regex.escape(targetName)}\s*,\s*(?:[^()]|\([^()]*\))*\)"""
        ).replace(migratedBody, "super.checkAndPerformAttack($targetName)")
        reachVars.forEach { reachVar ->
            migratedBody = Regex(
                """(?m)^[ \t]*(?:double|float)\s+${Regex.escape(reachVar)}\s*=\s*(?:this\.)?getAttackReachSqr\s*\(\s*${Regex.escape(targetName)}\s*\)\s*;\s*\r?\n"""
            ).replace(migratedBody, "")
            migratedBody = Regex("""\b${Regex.escape(distanceName)}\s*<=\s*${Regex.escape(reachVar)}\b""")
                .replace(migratedBody, "this.canPerformAttack($targetName)")
            migratedBody = Regex("""\b${Regex.escape(reachVar)}\s*>=\s*${Regex.escape(distanceName)}\b""")
                .replace(migratedBody, "this.canPerformAttack($targetName)")
        }
        migratedBody = Regex(
            """\b${Regex.escape(distanceName)}\s*<=\s*(?:this\.)?getAttackReachSqr\s*\(\s*${Regex.escape(targetName)}\s*\)"""
        ).replace(migratedBody, "this.canPerformAttack($targetName)")
        migratedBody = Regex(
            """(?:this\.)?getAttackReachSqr\s*\(\s*${Regex.escape(targetName)}\s*\)\s*>=\s*${Regex.escape(distanceName)}\b"""
        ).replace(migratedBody, "this.canPerformAttack($targetName)")
        migratedBody = Regex("""\b${Regex.escape(distanceName)}\b""")
            .replace(migratedBody, "this.mob.distanceToSqr($targetName)")

        val migratedSignature = match.value.replace(
            Regex("""checkAndPerformAttack\s*\(\s*${Regex.escape(entityType)}\s+${Regex.escape(targetName)}\s*,\s*double\s+${Regex.escape(distanceName)}\s*\)"""),
            "checkAndPerformAttack($entityType $targetName)"
        )
        result = result.substring(0, match.range.first) +
            migratedSignature +
            migratedBody +
            result.substring(closeBrace)
        cursor = match.range.first + migratedSignature.length + migratedBody.length + 1
    }
    val hasOneArgCheckAndPerformAttack = Regex(
        """\bcheckAndPerformAttack\s*\(\s*(?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s+)*(?:net\.minecraft\.world\.entity\.)?LivingEntity\s+[A-Za-z_$][\w$]*\s*\)"""
    ).containsMatchIn(maskJavaCommentsAndLiterals(result))
    if (hasOneArgCheckAndPerformAttack) {
        result = rewriteExecutableJavaCall(result, "checkAndPerformAttack") { receiver, args ->
            if (args.size == 2) "$receiver.checkAndPerformAttack(${args[0].trim()})" else null
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateCustomGoalAttackReach(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("getAttackReachSqr")) return source
    var result = if (source.contains("MeleeAttackGoal")) {
        migrateMeleeAttackGoalReachOverrides(source, javaInheritanceIndex)
    } else {
        source
    }

    if (result.contains("extends Goal") && result.contains("getAttackReachSqr")) {
        result = Regex(
            """this\.getAttackReachSqr\s*\(\s*([A-Za-z_$][\w$]*)\s*\)\s*>=\s*this\.mob\.distanceToSqr\s*\(\s*\1\.getX\(\)\s*,\s*\1\.getY\(\)\s*,\s*\1\.getZ\(\)\s*\)"""
        ).replace(result) { match -> "this.mob.isWithinMeleeAttackRange(${match.groupValues[1]})" }
        result = Regex(
            """(?m)^[ \t]*double\s+([A-Za-z_$][\w$]*)\s*=\s*this\.mob\.getPerceivedTargetDistanceSquareForMeleeAttack\s*\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;\s*\r?\n[ \t]*this\.checkAndPerformAttack\s*\(\s*\2\s*,\s*\1\s*\)\s*;"""
        ).replace(result) { match -> "			this.checkAndPerformAttack(${match.groupValues[2]});" }
        result = Regex("""this\.canPerformAttack\s*\(\s*([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match ->
                val target = match.groupValues[1]
                "this.attackTick <= 0 && this.mob.isWithinMeleeAttackRange($target) && this.mob.hasLineOfSight($target)"
            }
        result = removeMethodByName(result, "getAttackReachSqr")
    }

    return result
}

internal fun StructuralRefactorPass.migrateAttributeHolderApiArguments(
    source: String,
    attributeHolderAccessHints: AttributeHolderAccessHints
): String {
    if (attributeHolderAccessHints.qualifiedFields.isEmpty() || !source.contains(".get()")) return source
    val expressions = linkedSetOf<String>()
    expressions += attributeHolderAccessHints.qualifiedFields
    declaredAttributeHolderFieldNames(source).forEach { expressions += it }
    Regex("""(?m)^\s*import\s+static\s+([^;\s]+)\s*;""")
        .findAll(source)
        .map { it.groupValues[1].trim() }
        .filter { it in attributeHolderAccessHints.qualifiedFields }
        .forEach { expressions += it.substringAfterLast('.') }
    if (expressions.isEmpty()) return source
    val expressionPattern = expressions
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    var result = source
    result = Regex("""(\.(?:getAttribute|getAttributeValue|hasAttribute)\(\s*)($expressionPattern)\.get\(\)(\s*\))""")
        .replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}.getDelegate()${match.groupValues[3]}"
        }
    result = Regex("""(\.add\(\s*)($expressionPattern)\.get\(\)(\s*,)""")
        .replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}.getDelegate()${match.groupValues[3]}"
    }
    return result
}

internal fun StructuralRefactorPass.migrateItemStackAttributeModifierAccess(source: String): String {
    if (!source.contains(".getAttributeModifiers(")) return source
    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    val token = ".getAttributeModifiers("
    while (cursor < source.length) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val receiverStart = findExpressionReceiverStart(source, tokenIndex)
        val openParen = tokenIndex + ".getAttributeModifiers".length
        val closeParen = findMatchingParen(source, openParen)
        if (receiverStart < 0 || closeParen < 0) {
            migrated.append(source, cursor, tokenIndex + token.length)
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size != 1) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }
        val receiver = source.substring(receiverStart, tokenIndex).trim()
        val slot = args[0].trim()
        val after = source.substring(closeParen + 1)
        var replacementEnd = closeParen + 1
        val replacement = when {
            after.startsWith(".isEmpty()") -> {
                replacementEnd = closeParen + 1 + ".isEmpty()".length
                "${itemAttributeModifierStream(receiver, slot)}.findAny().isEmpty()"
            }
            after.startsWith(".containsKey(") -> {
                val containsOpen = closeParen + 1 + ".containsKey".length
                val containsClose = findMatchingParen(source, containsOpen)
                if (containsClose < 0) null else {
                    val containsArgs = splitTopLevelJavaArgs(source.substring(containsOpen + 1, containsClose))
                    if (containsArgs.size != 1) null else {
                        replacementEnd = containsClose + 1
                        "${itemAttributeModifierStream(receiver, slot, containsArgs[0].trim())}.findAny().isPresent()"
                    }
                }
            }
            after.startsWith(".get(") -> {
                val getOpen = closeParen + 1 + ".get".length
                val getClose = findMatchingParen(source, getOpen)
                if (getClose < 0) null else {
                    val getArgs = splitTopLevelJavaArgs(source.substring(getOpen + 1, getClose))
                    if (getArgs.size != 1) null else {
                        val attribute = getArgs[0].trim()
                        val afterGet = source.substring(getClose + 1)
                        when {
                            afterGet.startsWith(".isEmpty()") -> {
                                replacementEnd = getClose + 1 + ".isEmpty()".length
                                "${itemAttributeModifierStream(receiver, slot, attribute)}.findAny().isEmpty()"
                            }
                            afterGet.startsWith(".stream()") -> {
                                replacementEnd = getClose + 1 + ".stream()".length
                                "${itemAttributeModifierStream(receiver, slot, attribute)}.map(modporterAttributeModifierEntry -> modporterAttributeModifierEntry.modifier())"
                            }
                            else -> null
                        }
                    }
                }
            }
            else -> {
                "$receiver.getAttributeModifiers()"
            }
        }
        if (replacement == null) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }
        migrated.append(source, cursor, receiverStart)
        migrated.append(replacement)
        cursor = replacementEnd
        changed = true
    }
    if (!changed) return source
    migrated.append(source, cursor, source.length)
    var result = migrated.toString()
        .replace("AttributeModifier::getAmount", "AttributeModifier::amount")
    result = Regex("""(\(\s*([A-Za-z_$][\w$]*)\s*\)\s*->\s*)\2\.getAmount\(\)""")
        .replace(result) { match -> "${match.groupValues[1]}${match.groupValues[2]}.amount()" }
    result = Regex("""(\b([A-Za-z_$][\w$]*)\s*->\s*)\2\.getAmount\(\)""")
        .replace(result) { match -> "${match.groupValues[1]}${match.groupValues[2]}.amount()" }
    return result
}

internal fun StructuralRefactorPass.itemAttributeModifierStream(receiver: String, slot: String, attribute: String? = null): String {
    val slotFilter = "modporterAttributeModifierEntry.slot().test($slot)"
    val attributeFilter = attribute?.let { " && modporterAttributeModifierEntry.attribute().equals($it)" }.orEmpty()
    return "$receiver.getAttributeModifiers().modifiers().stream().filter(modporterAttributeModifierEntry -> $slotFilter$attributeFilter)"
}

internal fun StructuralRefactorPass.migrateLegacyServerDataConstructors(source: String): String {
    if (!source.contains("new ServerData(")) return source
    return migrateMethodCalls(source, "new ServerData") { args ->
        if (args.size != 3) return@migrateMethodCalls args
        val serverType = when (args[2].trim()) {
            "true" -> "ServerData.Type.LAN"
            "false" -> "ServerData.Type.OTHER"
            else -> return@migrateMethodCalls args
        }
        listOf(args[0], args[1], serverType)
    }
}

internal fun StructuralRefactorPass.migrateLegacyConnectScreenStartConnectingCalls(source: String): String {
    val legacyDescriptor = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;Z)V"
    if (!source.contains("ConnectScreen.startConnecting(") && !source.contains(legacyDescriptor)) return source
    var result = source
    if (result.contains("ConnectScreen.startConnecting(")) {
        result = migrateMethodCalls(result, "ConnectScreen.startConnecting") { args ->
            if (args.size == 5) args + "null" else args
        }
    }
    if (result.contains(legacyDescriptor) && targetsConnectScreenMixin(result)) {
        result = result.replace(
            legacyDescriptor,
            "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V"
        )
        result = Regex(
            """(?m)^([ \t]*(?:private|protected|public)\s+static\s+void\s+[A-Za-z_$][\w$]*\s*\([^)]*\bboolean\s+[A-Za-z_$][\w$]*\s*,\s*)CallbackInfo\s+([A-Za-z_$][\w$]*)(\s*\))"""
        ).replace(result) { match ->
            "${match.groupValues[1]}TransferState transferState, CallbackInfo ${match.groupValues[2]}${match.groupValues[3]}"
        }
        result = addImportIfMissing(result, "net.minecraft.client.multiplayer.TransferState")
    }
    return result
}

internal fun StructuralRefactorPass.targetsConnectScreenMixin(source: String): Boolean =
    Regex("""@Mixin\s*\(\s*(?:ConnectScreen|net\.minecraft\.client\.gui\.screens\.ConnectScreen)\.class\s*\)""")
        .containsMatchIn(source)

internal fun StructuralRefactorPass.migrateLegacyClientPacketListenerDisconnectMixinSource(source: String): String {
    val legacyDescriptor = "onDisconnect(Lnet/minecraft/network/chat/Component;)V"
    if (!source.contains(legacyDescriptor) ||
        !Regex("""@Mixin\s*\(\s*(?:ClientPacketListener|net\.minecraft\.client\.multiplayer\.ClientPacketListener)\.class\s*\)""")
            .containsMatchIn(source)) {
        return source
    }
    val block = findAnnotatedMixinMethodBlock(source, legacyDescriptor, "@Inject") ?: return source
    val header = block.text.substring(0, block.bodyStart - block.start)
    val id = """[A-Za-z_$][\w$]*"""
    val reasonName = Regex("""(?:net\.minecraft\.network\.chat\.)?Component\s+($id)""")
        .find(header)
        ?.groupValues
        ?.get(1)
        ?: return source
    val oldCallbackName = Regex("""CallbackInfo\s+($id)""")
        .find(header)
        ?.groupValues
        ?.get(1)
        ?: return source

    val existingNames = header + block.text.substring(block.bodyStart - block.start, block.bodyEnd - block.start)
    val detailsName = uniqueJavaLocalName(existingNames, "details")
    val callbackName = uniqueJavaLocalName(existingNames, "cir")
    var returnedScreen = false
    var body = block.text.substring(block.bodyStart - block.start, block.bodyEnd - block.start)
    body = rewriteJavaCall(body, "setScreen") { receiver, args ->
        if (args.size != 1) return@rewriteJavaCall null
        val screenExpression = args.single().trim()
        val isMinecraftReceiver = receiver == "Minecraft.getInstance()" ||
            receiver == "net.minecraft.client.Minecraft.getInstance()"
        if (!isMinecraftReceiver ||
            !screenExpression.contains("new DisconnectedScreen(") ||
            !Regex("""(?<![\w$])${Regex.escape(reasonName)}(?![\w$])""").containsMatchIn(screenExpression)) {
            return@rewriteJavaCall null
        }
        returnedScreen = true
        val migratedExpression = Regex("""(?<![\w$])${Regex.escape(reasonName)}(?![\w$])""")
            .replace(screenExpression, detailsName)
        "$callbackName.setReturnValue($migratedExpression)"
    }
    if (!returnedScreen) return source
    body = Regex("""(?m)^[ \t]*${Regex.escape(oldCallbackName)}\.cancel\(\);\s*(?:\r?\n)?""")
        .replace(body, "")

    val rewrittenHeader = rewriteLegacyDisconnectScreenInjectPoint(header)
        .replace(legacyDescriptor, "createDisconnectScreen(Lnet/minecraft/network/DisconnectionDetails;)Lnet/minecraft/client/gui/screens/Screen;")
        .replace(
            Regex("""(?m)^([ \t]*(?:private|protected|public)\s+(?:static\s+)?void\s+$id\s*)\([^)]*\)\s*\{""")
        ) { match ->
            "${match.groupValues[1]}(DisconnectionDetails $detailsName, CallbackInfoReturnable<Screen> $callbackName) {"
        }
    if (!rewrittenHeader.contains("CallbackInfoReturnable<Screen>")) return source

    val rewrittenBlock = rewrittenHeader + body + block.text.substring(block.bodyEnd - block.start)
    var result = source.substring(0, block.start) + rewrittenBlock + source.substring(block.endExclusive)
    result = Regex("""@Mixin\s*\(\s*ClientPacketListener\.class\s*\)""")
        .replace(result, "@Mixin(ClientCommonPacketListenerImpl.class)")
    result = Regex("""@Mixin\s*\(\s*net\.minecraft\.client\.multiplayer\.ClientPacketListener\.class\s*\)""")
        .replace(result, "@Mixin(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class)")
    result = removeImport(result, "net.minecraft.client.multiplayer.ClientPacketListener")
    result = addImportIfMissing(result, "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl")
    result = addImportIfMissing(result, "net.minecraft.client.gui.screens.Screen")
    result = addImportIfMissing(result, "net.minecraft.network.DisconnectionDetails")
    result = addImportIfMissing(result, "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable")
    result = removeImportIfTokenAbsent(result, "net.minecraft.client.Minecraft", Regex("""(?<![\w$])Minecraft(?![\w$])"""))
    result = removeImportIfTokenAbsent(
        result,
        "org.spongepowered.asm.mixin.injection.callback.CallbackInfo",
        Regex("""(?<![\w$])CallbackInfo(?![\w$])""")
    )
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyDisconnectScreenInjectPoint(header: String): String {
    val legacyJoinScreenCtor =
        "Lnet/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen;<init>(Lnet/minecraft/client/gui/screens/Screen;)V"
    val targetIndex = header.indexOf(legacyJoinScreenCtor)
    if (targetIndex < 0) return header
    val atIndex = header.lastIndexOf("@At", targetIndex)
    if (atIndex < 0) return header
    val atOpen = header.indexOf('(', atIndex)
    if (atOpen < 0 || atOpen > targetIndex) return header
    val atClose = findMatchingParen(header, atOpen)
    if (atClose < targetIndex) return header
    val injectIndex = header.lastIndexOf("@Inject", atIndex)
    if (injectIndex < 0) return header
    val injectOpen = header.indexOf('(', injectIndex)
    val injectClose = if (injectOpen >= 0) findMatchingParen(header, injectOpen) else -1
    if (injectOpen < 0 || injectClose < atClose) return header
    return header.substring(0, atIndex) + "@At(\"HEAD\")" + header.substring(atClose + 1)
}

internal fun StructuralRefactorPass.migrateLegacySingleplayerWorldFlowSource(source: String): String {
    if (!source.contains("loadLevel(") &&
        !source.contains("clearLevel(") &&
        !source.contains("getSummary()") &&
        !source.contains("getDataTag()")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    val minecraftVariables = Regex("""\bMinecraft\s+($id)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    val worldOpenFlowsVariables = Regex("""\b(?:WorldOpenFlows|net\.minecraft\.client\.gui\.screens\.worldselection\.WorldOpenFlows)\s+($id)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    val levelStorageAccessVariables = Regex("""\b(?:LevelStorageSource\.)?LevelStorageAccess\s+($id)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()

    var result = source
    var needsMinecraftImport = false
    result = rewriteJavaCall(result, "loadLevel") { receiver, args ->
        if (args.size != 2) return@rewriteJavaCall null
        val screen = args[0].trim()
        val levelId = args[1].trim()
        val minecraftReceiver = Regex("""^(.+)\.createWorldOpenFlows\(\)$""")
            .matchEntire(receiver)
            ?.groupValues
            ?.get(1)
            ?.trim()
        if (minecraftReceiver != null) {
            "$receiver.openWorld($levelId, () -> $minecraftReceiver.setScreen($screen))"
        } else if (receiver in worldOpenFlowsVariables) {
            needsMinecraftImport = true
            "$receiver.openWorld($levelId, () -> Minecraft.getInstance().setScreen($screen))"
        } else {
            null
        }
    }

    result = migrateLegacyWorldOpenFlowsMixinSource(result)

    result = rewriteJavaCall(result, "clearLevel") { receiver, args ->
        if (args.size != 1) return@rewriteJavaCall null
        val isMinecraftReceiver = receiver == "Minecraft.getInstance()" || receiver in minecraftVariables
        if (!isMinecraftReceiver) return@rewriteJavaCall null
        "$receiver.disconnect(${args[0].trim()})"
    }

    var needsIOExceptionImport = false
    for (receiver in levelStorageAccessVariables) {
        val assignmentReturn = Regex(
            """(?m)^([ \t]*)($id(?:\.$id)*)\s*=\s*${Regex.escape(receiver)}\.getSummary\(\)\s*;\s*\r?\n\1return\s+\2\s*;"""
        )
        result = assignmentReturn.replace(result) { match ->
            if (!isNullableLevelSummaryMethod(result, match.range.first)) {
                return@replace match.value
            }
            needsIOExceptionImport = true
            val indent = match.groupValues[1]
            val summaryTarget = match.groupValues[2]
            "${indent}try {\n" +
                "$indent    $summaryTarget = $receiver.getSummary($receiver.getDataTag());\n" +
                "$indent    return $summaryTarget;\n" +
                "$indent} catch (IOException e) {\n" +
                "$indent    return null;\n" +
                "$indent}"
        }
    }

    result = rewriteJavaCall(result, "getSummary") { receiver, args ->
        if (args.isNotEmpty()) return@rewriteJavaCall null
        if (receiver !in levelStorageAccessVariables) return@rewriteJavaCall null
        "$receiver.getSummary($receiver.getDataTag())"
    }
    val wrappedDataTagReads = wrapVoidLevelStorageDataTagReads(result, levelStorageAccessVariables)
    result = wrappedDataTagReads.first
    if (wrappedDataTagReads.second) {
        needsIOExceptionImport = true
    }

    if (needsMinecraftImport) {
        result = addImportIfMissing(result, "net.minecraft.client.Minecraft")
    }
    if (needsIOExceptionImport) {
        result = addImportIfMissing(result, "java.io.IOException")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyWorldOpenFlowsMixinSource(source: String): String {
    val targetsWorldOpenFlows = Regex(
        """@Mixin\s*\(\s*(?:WorldOpenFlows|net\.minecraft\.client\.gui\.screens\.worldselection\.WorldOpenFlows)\.class\s*\)"""
    ).containsMatchIn(source)
    if (!targetsWorldOpenFlows) return source

    var result = source
    var changed = false
    val legacyOpenWorld = "loadLevel(Lnet/minecraft/client/gui/screens/Screen;Ljava/lang/String;)V"
    if (result.contains(legacyOpenWorld)) {
        result = result.replace(legacyOpenWorld, "openWorld(Ljava/lang/String;Ljava/lang/Runnable;)V")
        result = migrateLegacyWorldOpenFlowsOpenWorldInjectHandler(result)
        changed = true
    }

    val legacyDoLoadLevel = "doLoadLevel(Lnet/minecraft/client/gui/screens/Screen;Ljava/lang/String;ZZZ)V"
    if (result.contains(legacyDoLoadLevel)) {
        result = result.replace(
            legacyDoLoadLevel,
            "openWorldLoadLevelStem(Lnet/minecraft/world/level/storage/LevelStorageSource\$LevelStorageAccess;Lcom/mojang/serialization/Dynamic;ZLjava/lang/Runnable;)V"
        )
        result = migrateLegacyWorldOpenFlowsConfirmExperimentalWarningMixin(result)
        result = migrateLegacyWorldOpenFlowsLoadLevelStemInjectHandler(result)
        result = addImportIfMissing(result, "com.mojang.serialization.Dynamic")
        result = changedThenAddWorldOpenFlowsImports(result)
        result = removeImportIfTokenAbsent(result, "com.llamalad7.mixinextras.sugar.Local", Regex("""(?<![\w$])@Local\b"""))
        result = removeImportIfTokenAbsent(result, "org.spongepowered.asm.mixin.injection.ModifyVariable", Regex("""(?<![\w$])@ModifyVariable\b"""))
        result = removeImportIfTokenAbsent(result, "net.minecraft.client.gui.screens.Screen", Regex("""(?<![\w$])Screen(?![\w$])"""))
        changed = true
    }

    return if (changed) result else source
}

internal fun StructuralRefactorPass.changedThenAddWorldOpenFlowsImports(source: String): String {
    var result = source
    result = addImportIfMissing(result, "net.minecraft.server.WorldStem")
    result = addImportIfMissing(result, "net.minecraft.server.packs.repository.PackRepository")
    result = addImportIfMissing(result, "net.minecraft.world.level.storage.PrimaryLevelData")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyWorldOpenFlowsOpenWorldInjectHandler(source: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    val signature = Regex(
        """(?m)^([ \t]*(?:private|protected|public)\s+void\s+$id\s*)\(\s*Screen\s+($id)\s*,\s*String\s+($id)\s*,\s*CallbackInfo\s+($id)\s*\)\s*\{"""
    )
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (match in signature.findAll(source)) {
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (closeBrace < 0) continue
        val screenParam = match.groupValues[2]
        val body = source.substring(openBrace + 1, closeBrace)
        if (Regex("""(?<![\w$])${Regex.escape(screenParam)}(?![\w$])""").containsMatchIn(body)) continue
        val levelName = match.groupValues[3]
        val callback = match.groupValues[4]
        edits.add(match.range to "${match.groupValues[1]}(String $levelName, Runnable onFail, CallbackInfo $callback) {")
    }
    if (edits.isEmpty()) return source
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateLegacyWorldOpenFlowsConfirmExperimentalWarningMixin(source: String): String {
    val legacyDescriptor = "openWorldLoadLevelStem(Lnet/minecraft/world/level/storage/LevelStorageSource\$LevelStorageAccess;Lcom/mojang/serialization/Dynamic;ZLjava/lang/Runnable;)V"
    val annotationStart = source.indexOf("@ModifyVariable")
    if (annotationStart < 0 || !source.substring(annotationStart).take(500).contains(legacyDescriptor)) return source
    val openBrace = source.indexOf('{', annotationStart)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    if (closeBrace < 0) return source
    val header = source.substring(annotationStart, openBrace)
    val signature = Regex("""private\s+boolean\s+([A-Za-z_$][\w$]*)\s*\(\s*boolean\s+([A-Za-z_$][\w$]*)\s*\)""")
        .find(header)
        ?: return source
    val methodName = signature.groupValues[1]
    val originalParam = signature.groupValues[2]
    val body = source.substring(openBrace + 1, closeBrace)
    val condition = Regex(
        """if\s*\((.*?)\)\s*\{\s*return\s+true\s*;\s*\}\s*else\s*\{\s*return\s+${Regex.escape(originalParam)}\s*;\s*\}""",
        RegexOption.DOT_MATCHES_ALL
    ).find(body)?.groupValues?.get(1)?.trim() ?: return source
    val indent = source.substring(lineStartAt(source, annotationStart), annotationStart)
        .takeWhile { it == ' ' || it == '\t' }
    val replacement = """
${indent}@Inject(method = "openWorldCheckWorldStemCompatibility(Lnet/minecraft/world/level/storage/LevelStorageSource${'$'}LevelStorageAccess;Lnet/minecraft/server/WorldStem;Lnet/minecraft/server/packs/repository/PackRepository;Ljava/lang/Runnable;)V", at = @At("HEAD"), remap = false)
${indent}private void $methodName(LevelStorageSource.LevelStorageAccess levelStorage, WorldStem worldStem, PackRepository packRepository, Runnable onFail, CallbackInfo ci) {
${indent}    if ($condition && worldStem.worldData() instanceof PrimaryLevelData modporterPrimaryLevelData) {
${indent}        modporterPrimaryLevelData.withConfirmedWarning(true);
${indent}    }
${indent}}""".trimStart('\n')
    return source.replaceRange(annotationStart, closeBrace + 1, replacement)
}

internal fun StructuralRefactorPass.migrateLegacyWorldOpenFlowsLoadLevelStemInjectHandler(source: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    val signature = Regex(
        """(?m)^([ \t]*(?:private|protected|public)\s+void\s+$id\s*)\(\s*Screen\s+$id\s*,\s*String\s+$id\s*,\s*boolean\s+($id)\s*,\s*boolean\s+$id\s*,\s*boolean\s+$id\s*,\s*CallbackInfo\s+($id)\s*,\s*@Local\s+LevelStorageSource\.LevelStorageAccess\s+($id)\s*\)"""
    )
    return signature.replace(source) { match ->
        val safeMode = match.groupValues[2]
        val callback = match.groupValues[3]
        val levelStorage = match.groupValues[4]
        "${match.groupValues[1]}(LevelStorageSource.LevelStorageAccess $levelStorage, Dynamic<?> levelData, boolean $safeMode, Runnable onFail, CallbackInfo $callback)"
    }
}

internal fun StructuralRefactorPass.applyStringEdits(source: String, edits: List<Pair<IntRange, String>>): String {
    if (edits.isEmpty()) return source
    val builder = StringBuilder()
    var cursor = 0
    for ((range, replacement) in edits.sortedBy { it.first.first }) {
        if (range.first < cursor) continue
        builder.append(source, cursor, range.first)
        builder.append(replacement)
        cursor = range.last + 1
    }
    builder.append(source, cursor, source.length)
    return builder.toString()
}

internal fun StructuralRefactorPass.wrapVoidLevelStorageDataTagReads(
    source: String,
    levelStorageAccessVariables: Set<String>
): Pair<String, Boolean> {
    if (!source.contains(".getDataTag()") || levelStorageAccessVariables.isEmpty()) return source to false

    var result = source
    var changed = false
    for (method in javaMethodRanges(source).asReversed()) {
        val methodText = result.substring(method.range.first, method.range.last + 1)
        if (!Regex("""\bvoid\s+${Regex.escape(method.name)}\s*\(""").containsMatchIn(method.header)) continue
        if (methodText.contains("catch (IOException") || method.header.contains("throws IOException")) continue
        val wrapped = wrapVoidLevelStorageDataTagIfBlocks(methodText, levelStorageAccessVariables)
        if (wrapped != methodText) {
            result = result.substring(0, method.range.first) + wrapped + result.substring(method.range.last + 1)
            changed = true
        }
    }
    return result to changed
}

internal fun StructuralRefactorPass.wrapVoidLevelStorageDataTagIfBlocks(
    methodText: String,
    levelStorageAccessVariables: Set<String>
): String {
    var result = methodText
    var changed = false
    var searchStart = 0
    while (searchStart < result.length) {
        val ifMatch = Regex("""(?m)^([ \t]*)if\s*\(""").find(result, searchStart) ?: break
        val indent = ifMatch.groupValues[1]
        val openParen = result.indexOf('(', ifMatch.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(result, openParen) else -1
        if (closeParen < 0) {
            searchStart = ifMatch.range.last + 1
            continue
        }
        val condition = result.substring(openParen + 1, closeParen)
        val readsLevelStorageDataTag = levelStorageAccessVariables.any { receiver ->
            condition.contains("$receiver.getDataTag()")
        }
        if (!readsLevelStorageDataTag) {
            searchStart = closeParen + 1
            continue
        }
        val openBrace = result.indexOf('{', closeParen)
        if (openBrace < 0) {
            searchStart = closeParen + 1
            continue
        }
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) {
            searchStart = openBrace + 1
            continue
        }
        if (isInsideTryBlock(result, ifMatch.range.first)) {
            searchStart = closeBrace + 1
            continue
        }

        val statement = result.substring(ifMatch.range.first, closeBrace + 1)
        val wrappedStatement = buildString {
            append(indent).append("try {\n")
            append(indentJavaStatement(statement, indent))
            append("\n").append(indent).append("} catch (IOException e) {\n")
            append(indent).append("    return;\n")
            append(indent).append("}")
        }
        result = result.substring(0, ifMatch.range.first) + wrappedStatement + result.substring(closeBrace + 1)
        changed = true
        searchStart = ifMatch.range.first + wrappedStatement.length
    }
    return if (changed) result else methodText
}

internal fun StructuralRefactorPass.indentJavaStatement(statement: String, baseIndent: String): String =
    statement.lines().joinToString("\n") { line ->
        if (line.startsWith(baseIndent)) {
            baseIndent + "    " + line.substring(baseIndent.length)
        } else if (line.isBlank()) {
            line
        } else {
            baseIndent + "    " + line.trimStart()
        }
    }

internal fun StructuralRefactorPass.isInsideTryBlock(source: String, offset: Int): Boolean {
    val lineStart = source.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
    val beforeLine = source.substring(0, lineStart).lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .lastOrNull()
    return beforeLine == "try {"
}

internal fun StructuralRefactorPass.isNullableLevelSummaryMethod(source: String, offset: Int): Boolean {
    val method = javaMethodRanges(source).firstOrNull { offset in it.range } ?: return false
    return Regex("""\bLevelSummary\s+${Regex.escape(method.name)}\s*\(""").containsMatchIn(method.header) &&
        Regex("""(?<![\w$])@Nullable(?![\w$])""").containsMatchIn(method.header)
}

internal fun StructuralRefactorPass.migrateLegacyBindingCurseChecks(source: String): String {
    val token = "EnchantmentHelper.hasBindingCurse"
    if (!source.contains(token)) return source
    val migrated = StringBuilder()
    var cursor = 0
    while (cursor < source.length) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + token.length
        if (openParen >= source.length || source[openParen] != '(') {
            migrated.append(source, cursor, openParen)
            cursor = openParen
            continue
        }
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size != 1) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }
        migrated.append(source, cursor, tokenIndex)
        migrated.append("EnchantmentHelper.has(${args[0].trim()}, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)")
        cursor = closeParen + 1
    }
    if (cursor == 0) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.migrateLegacyVanishingCurseChecks(source: String): String {
    val token = "EnchantmentHelper.hasVanishingCurse"
    if (!source.contains(token)) return source
    val migrated = StringBuilder()
    var cursor = 0
    while (cursor < source.length) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + token.length
        if (openParen >= source.length || source[openParen] != '(') {
            migrated.append(source, cursor, openParen)
            cursor = openParen
            continue
        }
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size != 1) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }
        migrated.append(source, cursor, tokenIndex)
        migrated.append("EnchantmentHelper.has(${args[0].trim()}, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)")
        cursor = closeParen + 1
    }
    if (cursor == 0) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.migrateRecipeBookGhostRecipeSource(source: String): String {
    if (!source.contains("setupGhostRecipe") || !source.contains("Recipe<?>")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val signaturePattern = Regex(
        """public\s+void\s+setupGhostRecipe\s*\(\s*Recipe\s*<\s*\?\s*>\s+($id)\s*,\s*List\s*<\s*Slot\s*>\s+($id)\s*\)"""
    )
    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    while (cursor < source.length) {
        val match = signaturePattern.find(source, cursor) ?: break
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            migrated.append(source, cursor, match.range.last + 1)
            cursor = match.range.last + 1
            continue
        }
        val recipeVar = match.groupValues[1]
        migrated.append(source, cursor, match.range.first)
        migrated.append(match.value.replace(Regex("""Recipe\s*<\s*\?\s*>"""), "RecipeHolder<?>"))
        migrated.append(source, match.range.last + 1, openBrace + 1)
        val body = source.substring(openBrace + 1, closeBrace)
            .replace(Regex("""\b${Regex.escape(recipeVar)}\.getIngredients\(\)"""), "$recipeVar.value().getIngredients()")
        migrated.append(body)
        migrated.append("}")
        cursor = closeBrace + 1
        changed = true
    }
    if (!changed) return source
    migrated.append(source, cursor, source.length)
    var result = migrated.toString()
    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
    val withoutRecipeImport = removeImport(result, "net.minecraft.world.item.crafting.Recipe")
    result = if (hasSimpleTypeReference(withoutRecipeImport, "Recipe")) result else withoutRecipeImport
    return result
}

internal fun StructuralRefactorPass.migrateMeleeAttackGoalReachOverrides(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains("extends") && !source.contains("new MeleeAttackGoal")) return source
    var result = source
    var cursor = 0
    val signaturePattern = Regex(
        """(?m)((?:[ \t]*@\w+(?:\([^)]*\))?\s*)*[ \t]*protected\s+double\s+getAttackReachSqr\s*\(\s*((?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s+)*(?:net\.minecraft\.world\.entity\.)?LivingEntity)\s+([A-Za-z_$][\w$]*)\s*\)\s*\{)"""
    )
    while (true) {
        val match = signaturePattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val enclosingClass = enclosingJavaClassDeclaration(result, match.range.first)
        val isMeleeContext = javaClassExtendsAny(enclosingClass, setOf("MeleeAttackGoal"), javaInheritanceIndex) ||
            isInsideAnonymousMeleeAttackGoal(result, match.range.first)
        if (!isMeleeContext) {
            cursor = closeBrace + 1
            continue
        }
        val targetName = match.groupValues[3]
        val body = result.substring(openBrace + 1, closeBrace)
        val returnMatch = Regex("""return\s+(.+?);""", RegexOption.DOT_MATCHES_ALL).find(body)
        if (returnMatch == null) {
            cursor = closeBrace + 1
            continue
        }
        val returnExpr = returnMatch.groupValues[1].trim()
        val migratedReach = returnExpr.replace(
            Regex("""super\.getAttackReachSqr\s*\(\s*${Regex.escape(targetName)}\s*\)"""),
            "this.mob.getBbWidth() * 2.0F * this.mob.getBbWidth() * 2.0F + $targetName.getBbWidth()"
        )
        val distance = "this.mob.distanceToSqr($targetName.getX(), $targetName.getY(), $targetName.getZ())"
        val condition = "($migratedReach) >= $distance"
        val indent = match.value.takeWhile { it == ' ' || it == '\t' }
        val replacement = """
${indent}protected boolean canPerformAttack(${match.groupValues[2]} $targetName) {
${indent}    return this.isTimeToAttack() && ($condition) && this.mob.getSensing().hasLineOfSight($targetName);
${indent}}
            """.trimIndent()
        result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        cursor = match.range.first + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.isInsideAnonymousMeleeAttackGoal(source: String, index: Int): Boolean {
    val pattern = Regex("""new\s+(?:[A-Za-z_$][\w$]*\.)*MeleeAttackGoal\s*\(""")
    return pattern.findAll(source)
        .takeWhile { it.range.first < index }
        .any { match ->
            val openParen = source.indexOf('(', match.range.last)
            val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
            if (openParen < 0 || closeParen < 0) return@any false
            val openBrace = source.indexOf('{', closeParen)
            if (openBrace < 0) return@any false
            val between = source.substring(closeParen + 1, openBrace)
            if (between.any { !it.isWhitespace() }) return@any false
            val closeBrace = findMatchingBrace(source, openBrace)
            closeBrace > openBrace && index in openBrace..closeBrace
        }
}

internal fun StructuralRefactorPass.migrateLegacyDataAndComponentAccess(source: String): String {
    var result = source
    var needsDataComponents = false
    var needsItemTags = false
    var needsAttributes = false
    var needsPlayer = false
    var needsCollisionContext = false
    var needsLevelReader = false

    if (result.contains("ItemPredicate")) {
        Regex("""\bItemPredicate\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(result)
            .map { it.groupValues[1] }
            .toSet()
            .forEach { variable ->
                result = Regex("""\b${Regex.escape(variable)}\.matches\(""")
                    .replace(result, "$variable.test(")
            }
        result = Regex("""(\b[A-Za-z_$][\w$]*\.get\(\))\.matches\(""")
            .replace(result, "$1.test(")
        result = Regex("""(\bthis\.[A-Za-z_$][\w$]*\.get\(\))\.matches\(""")
            .replace(result, "$1.test(")
    }
    if (result.contains("GameEvent.ENTITY_ROAR")) {
        result = result.replace("GameEvent.ENTITY_ROAR", "GameEvent.ENTITY_ACTION")
    }
    result = migrateBuiltInRegistryDelegateMapAccess(result)
    if (result.contains("Container")) {
        val containerVariables = Regex("""(?m)^[ \t]*(?:(?:public|protected|private|static|final|transient|volatile)\s+)*(?:(?:@\w+(?:\([^)]*\))?)\s+)*(?:net\.minecraft\.world\.)?Container\s+([A-Za-z_$][\w$]*)\s*(?:[=;,)]|$)""")
            .findAll(result)
            .map { it.groupValues[1] }
            .toSet()
        containerVariables.forEach { variable ->
            result = Regex("""\b${Regex.escape(variable)}\.size\(\)""")
                .replace(result, "$variable.getContainerSize()")
        }
        result = migrateContainerSizeCallsForDeclaredContainerScopes(result)
    }
    if (result.contains("new ClipContext(")) {
        val clipBefore = result
        result = migrateMethodCalls(result, "new ClipContext") { args ->
            if (args.size == 5 && args[4].trim() == "null") {
                needsCollisionContext = true
                args.take(4) + "CollisionContext.empty()"
            } else {
                args
            }
        }
        if (result == clipBefore) {
            needsCollisionContext = false
        }
    }
    val beforeCustomHoverName = result
    result = replaceExecutableRegex(result, Regex("""\.hasCustomHoverName\(\)""")) { ".has(DataComponents.CUSTOM_NAME)" }
    if (result != beforeCustomHoverName) {
        needsDataComponents = true
    }
    result = replaceExecutableRegex(result, Regex("""\.getBiome\(([^)]+)\)\.get\(\)\.""")) { match ->
        ".getBiome(${match.groupValues[1].trim()}).value()."
    }
    val beforeItemHeads = result
    result = replaceExecutableRegex(result, Regex("""\bTags\.Items\.HEADS\b""")) { "ItemTags.SKULLS" }
    if (result != beforeItemHeads) {
        needsItemTags = true
    }
    val executableReachSource = maskJavaCommentsAndLiterals(result)
    if ((executableReachSource.contains("NeoForgeMod.BLOCK_REACH") || executableReachSource.contains("NeoForgeMod.ENTITY_REACH")) &&
        !(executableReachSource.contains("getDefaultAttributeModifiers(EquipmentSlot") && executableReachSource.contains("ImmutableMultimap"))) {
        val beforeReach = result
        result = replaceExecutableRegex(
            result,
            Regex("""\b(?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.BLOCK_REACH(?:\.get\(\))?""")
        ) { "Attributes.BLOCK_INTERACTION_RANGE" }
        result = replaceExecutableRegex(
            result,
            Regex("""\b(?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.ENTITY_REACH(?:\.get\(\))?""")
        ) { "Attributes.ENTITY_INTERACTION_RANGE" }
        if (result != beforeReach) {
            needsAttributes = true
        }
        val withoutNeoForgeMod = removeImport(result, "net.neoforged.neoforge.common.NeoForgeMod")
        if (!Regex("""\bNeoForgeMod\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutNeoForgeMod))) {
            result = withoutNeoForgeMod
        }
    }
    if (maskJavaCommentsAndLiterals(result).contains("NeoForgeMod.ENTITY_GRAVITY") ||
        maskJavaCommentsAndLiterals(result).contains("net.neoforged.neoforge.common.NeoForgeMod.ENTITY_GRAVITY")) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b(?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.ENTITY_GRAVITY(?:\.get\(\))?""")
        ) { "Attributes.GRAVITY" }
        needsAttributes = true
        val withoutNeoForgeMod = removeImport(result, "net.neoforged.neoforge.common.NeoForgeMod")
        if (!Regex("""\bNeoForgeMod\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutNeoForgeMod))) {
            result = withoutNeoForgeMod
        }
    }
    if (maskJavaCommentsAndLiterals(result).contains("NeoForgeMod.STEP_HEIGHT_ADDITION") ||
        maskJavaCommentsAndLiterals(result).contains("net.neoforged.neoforge.common.NeoForgeMod.STEP_HEIGHT_ADDITION")) {
        val beforeStepHeight = result
        result = replaceExecutableRegex(
            result,
            Regex("""\b(?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.STEP_HEIGHT_ADDITION(?:\.get\(\))?""")
        ) { "Attributes.STEP_HEIGHT" }
        if (result != beforeStepHeight) {
            needsAttributes = true
        }
        val withoutNeoForgeMod = removeImport(result, "net.neoforged.neoforge.common.NeoForgeMod")
        if (!Regex("""\bNeoForgeMod\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutNeoForgeMod))) {
            result = withoutNeoForgeMod
        }
    }
    result = migrateAabbBlockPosPairConstructors(result)
    result = migrateAabbVec3EncapsulatingFullBlocks(result)
    if (result.contains("BucketPickup") || result.contains("LiquidBlockContainer") || result.contains("pickupBlock(") || result.contains("canPlaceLiquid(")) {
        val migratedFluidInterfaces = migrateLegacyFluidInterfacePlayerParametersSource(result)
        result = migratedFluidInterfaces.first
        if (migratedFluidInterfaces.second) {
            needsPlayer = true
        }
        result = migrateLegacyBucketPickupCallSites(result)
    }

    if (needsDataComponents) result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    if (needsItemTags) result = addImportIfMissing(result, "net.minecraft.tags.ItemTags")
    if (needsAttributes) result = addImportIfMissing(result, "net.minecraft.world.entity.ai.attributes.Attributes")
    if (needsPlayer) result = addImportIfMissing(result, "net.minecraft.world.entity.player.Player")
    if (needsCollisionContext) result = addImportIfMissing(result, "net.minecraft.world.phys.shapes.CollisionContext")
    if (needsLevelReader) result = addImportIfMissing(result, "net.minecraft.world.level.LevelReader")
    return result
}

internal fun StructuralRefactorPass.migrateBuiltInRegistryDelegateMapAccess(source: String): String {
    if (!source.contains("Holder.Reference<") && !source.contains("getDelegateOrThrow(")) return source
    var result = source

    if (result.contains("@Accessor(\"blockColors\")") && result.contains("BlockColors.class")) {
        result = Regex("""Map\s*<\s*Holder\.Reference\s*<\s*Block\s*>\s*,\s*BlockColor\s*>""")
            .replace(result, "Map<Block, BlockColor>")
    }

    val registryBySimpleType = mapOf(
        "Block" to "BLOCK",
        "Item" to "ITEM",
        "Fluid" to "FLUID",
        "MobEffect" to "MOB_EFFECT",
        "SoundEvent" to "SOUND_EVENT",
        "EntityType" to "ENTITY_TYPE"
    )
    val delegateMapVariables = mutableMapOf<String, String>()
    val mapDeclaration = Regex(
        """Map\s*<\s*Holder\.Reference\s*<\s*([A-Za-z_$][\w$.]*)\s*>\s*,\s*([^>\r\n;=]+?)\s*>\s+([A-Za-z_$][\w$]*)\s*="""
    )
    result = mapDeclaration.replace(result) { match ->
        val typeName = match.groupValues[1].trim()
        val simpleType = typeName.substringAfterLast('.')
        val registryName = registryBySimpleType[simpleType] ?: return@replace match.value
        val variable = match.groupValues[3]
        val delegatesIntoMap = Regex(
            """\b${Regex.escape(variable)}\.get\s*\(\s*BuiltInRegistries\.${Regex.escape(registryName)}\.getDelegateOrThrow\s*\("""
        ).containsMatchIn(result)
        if (!delegatesIntoMap) return@replace match.value
        delegateMapVariables[variable] = registryName
        "Map<$typeName, ${match.groupValues[2].trim()}> $variable ="
    }

    for ((variable, registryName) in delegateMapVariables) {
        result = migrateMethodCalls(result, "$variable.get") { args ->
            if (args.size != 1) return@migrateMethodCalls args
            val arg = args.single().trim()
            val token = "BuiltInRegistries.$registryName.getDelegateOrThrow"
            if (!arg.startsWith("$token(")) return@migrateMethodCalls args
            val openParen = arg.indexOf('(', token.length)
            val closeParen = if (openParen >= 0) findMatchingParen(arg, openParen) else -1
            if (openParen < 0 || closeParen != arg.length - 1) return@migrateMethodCalls args
            listOf(arg.substring(openParen + 1, closeParen).trim())
        }
    }

    val withoutHolderImport = removeImport(result, "net.minecraft.core.Holder")
    if (!Regex("""(?<![\w$])Holder(?![\w$])""").containsMatchIn(withoutHolderImport)) {
        result = withoutHolderImport
    }
    val withoutBuiltInRegistriesImport = removeImport(result, "net.minecraft.core.registries.BuiltInRegistries")
    if (!Regex("""(?<![\w$])BuiltInRegistries(?![\w$])""").containsMatchIn(withoutBuiltInRegistriesImport)) {
        result = withoutBuiltInRegistriesImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyFluidInterfacePlayerParametersSource(source: String): Pair<String, Boolean> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("pickupBlock(") && !executableCode.contains("canPlaceLiquid(")) return source to false
    val edits = mutableListOf<Pair<IntRange, String>>()
    var needsNullable = false
    for (method in javaMethodRangesIncludingDefault(executableCode)) {
        val paramsText = javaMethodParameterText(method.header) ?: continue
        val rawParams = splitTopLevelJavaArgs(paramsText).map { it.trim() }
        val params = parseJavaParameters(paramsText)
        if (rawParams.size != params.size) continue
        val openParen = method.header.indexOf('(')
        if (openParen < 0) continue
        val closeParen = findMatchingParen(method.header, openParen)
        if (closeParen <= openParen) continue
        val parameterRange = (method.range.first + openParen + 1)..(method.range.first + closeParen - 1)
        if (method.name == "pickupBlock" &&
            params.size == 3 &&
            simpleJavaTypeName(params[0].type) == "LevelAccessor" &&
            simpleJavaTypeName(params[1].type) == "BlockPos" &&
            simpleJavaTypeName(params[2].type) == "BlockState") {
            val playerParam = legacyFluidPlayerParameter(rawParams)
            if (playerParam.startsWith("@Nullable")) needsNullable = true
            edits += parameterRange to (listOf(playerParam) + rawParams).joinToString(", ")
            collectSuperPickupBlockEdits(source, executableCode, method, params[0].name, params[1].name, params[2].name, edits, requireExecutableSourceMatch = true)
        }
        if (method.name == "canPlaceLiquid" &&
            params.size == 4 &&
            simpleJavaTypeName(params[0].type) == "BlockGetter" &&
            simpleJavaTypeName(params[1].type) == "BlockPos" &&
            simpleJavaTypeName(params[2].type) == "BlockState" &&
            simpleJavaTypeName(params[3].type) == "Fluid") {
            val playerParam = legacyFluidPlayerParameter(rawParams)
            if (playerParam.startsWith("@Nullable")) needsNullable = true
            edits += parameterRange to (listOf(playerParam) + rawParams).joinToString(", ")
        }
    }
    var result = applyStringEdits(source, edits)
    if (needsNullable &&
        !maskJavaCommentsAndLiterals(result).contains("import org.jetbrains.annotations.Nullable;") &&
        !maskJavaCommentsAndLiterals(result).contains("import javax.annotation.Nullable;")) {
        result = addImportIfMissing(result, "javax.annotation.Nullable")
    }
    return result to edits.isNotEmpty()
}

internal fun StructuralRefactorPass.legacyFluidPlayerParameter(rawParams: List<String>): String =
    if (rawParams.any(::hasJavaNonNullAnnotation)) "@Nullable Player player" else "Player player"

internal fun StructuralRefactorPass.hasJavaNonNullAnnotation(parameterText: String): Boolean =
    Regex("""@\s*(?:[A-Za-z_$][\w$]*\.)*(?:NotNull|Nonnull|NonNull)\b""").containsMatchIn(parameterText)

internal fun StructuralRefactorPass.collectSuperPickupBlockEdits(
    source: String,
    executableCode: String,
    method: JavaMethodRange,
    level: String,
    pos: String,
    state: String,
    edits: MutableList<Pair<IntRange, String>>,
    requireExecutableSourceMatch: Boolean
) {
    val methodText = executableCode.substring(method.range)
    val pickupPattern = Regex(
        """super\.pickupBlock\(\s*${Regex.escape(level)}\s*,\s*${Regex.escape(pos)}\s*,\s*${Regex.escape(state)}\s*\)"""
    )
    pickupPattern.findAll(methodText).forEach { pickupMatch ->
        val absoluteRange = (method.range.first + pickupMatch.range.first)..(method.range.first + pickupMatch.range.last)
        if (!requireExecutableSourceMatch || source.substring(absoluteRange) == pickupMatch.value) {
            edits += absoluteRange to "super.pickupBlock(player, $level, $pos, $state)"
        }
    }
}

internal fun StructuralRefactorPass.migrateLegacyBucketPickupCallSites(source: String): String {
    if (!source.contains("BucketPickup") || !source.contains(".pickupBlock(")) return source
    var result = source
    var cursor = 0
    val instanceofPattern = Regex("""\binstanceof\s+BucketPickup\s+([A-Za-z_$][\w$]*)\b""")
    while (true) {
        val match = instanceofPattern.find(result, cursor) ?: break
        val receiver = match.groupValues[1]
        val openBrace = result.indexOf('{', match.range.last + 1)
        if (openBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val playerArgument = bucketPickupPlayerArgumentForEnclosingMethod(result, match.range.first)
        if (playerArgument == null) {
            cursor = closeBrace + 1
            continue
        }
        val migratedBody = migrateLegacyBucketPickupCallsForReceiver(body, receiver, playerArgument)
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            cursor = openBrace + 1 + migratedBody.length
        } else {
            cursor = closeBrace + 1
        }
    }

    cursor = 0
    val parameterPattern = Regex(
        """\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*\(([^;{}()]*\b(?:net\.minecraft\.world\.level\.block\.)?BucketPickup\s+([A-Za-z_$][\w$]*)[^;{}()]*)\)\s*(?:throws\s+[^{]+)?\{"""
    )
    while (true) {
        val match = parameterPattern.find(result, cursor) ?: break
        val receiver = match.groupValues[2]
        val openBrace = match.range.last
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val playerArgument = singlePlayerParameterName(match.groupValues[1])
        if (playerArgument == null) {
            cursor = closeBrace + 1
            continue
        }
        val migratedBody = migrateLegacyBucketPickupCallsForReceiver(body, receiver, playerArgument)
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            cursor = openBrace + 1 + migratedBody.length
        } else {
            cursor = closeBrace + 1
        }
    }

    cursor = 0
    val methodPattern = Regex(
        """\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*\(([^;{}()]*)\)\s*(?:throws\s+[^{]+)?\{"""
    )
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val openBrace = match.range.last
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val playerArgument = singlePlayerParameterName(match.groupValues[1])
        if (playerArgument == null) {
            cursor = closeBrace + 1
            continue
        }
        val migratedBody = migrateLegacyBucketPickupLocalDeclarationBody(body, playerArgument)
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            cursor = openBrace + 1 + migratedBody.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.bucketPickupPlayerArgumentForEnclosingMethod(source: String, offset: Int): String? {
    val method = javaMethodRangesIncludingDefault(source).firstOrNull { offset in it.range } ?: return null
    val paramsText = javaMethodParameterText(method.header) ?: return null
    val playerArgument = singlePlayerParameterName(paramsText)
    if (playerArgument != null) return playerArgument
    return "null"
}

/**
 * Walks [srcDir]; when it is the main Java root (`src/main/java`) the sibling source sets'
 * Java roots (`src/<set>/java`, e.g. game tests) are walked too, since the build compiles them
 * against the same migrated API. `src/references` is reference material, not mod source.
 */
internal fun StructuralRefactorPass.walkJavaSourceRoots(srcDir: Path): java.util.stream.Stream<Path> {
    val setDir = srcDir.parent
    val src = setDir?.parent
    if (srcDir.fileName?.toString() != "java" || setDir?.fileName?.toString() != "main" || src?.fileName?.toString() != "src") {
        return Files.walk(srcDir)
    }
    val siblings = runCatching {
        Files.list(src).use { sets ->
            sets.filter { set ->
                set != setDir && set.fileName.toString() != "references" && Files.isDirectory(set.resolve("java"))
            }.map { it.resolve("java") }.sorted().toList()
        }
    }.getOrDefault(emptyList())
    return siblings.fold(Files.walk(srcDir)) { stream, root -> java.util.stream.Stream.concat(stream, Files.walk(root)) }
}

internal fun StructuralRefactorPass.javaMethodParameterText(methodHeader: String): String? {
    val leadingAnnotations = Regex("""^\s*(?:@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*""")
    val declaration = leadingAnnotations.replace(methodHeader, "")
    val openParen = declaration.indexOf('(')
    if (openParen < 0) return null
    val closeParen = findMatchingParen(declaration, openParen)
    if (closeParen <= openParen) return null
    return declaration.substring(openParen + 1, closeParen)
}

internal fun StructuralRefactorPass.singlePlayerParameterName(parameters: String): String? {
    val playerType = Regex(
        """(?:^|[\s<,])(?:final\s+)?(?:(?:net\.minecraft\.world\.entity\.player\.)?Player|(?:net\.minecraft\.server\.level\.)?ServerPlayer)\s+([A-Za-z_$][\w$]*)\b"""
    )
    val playerParameters = splitTopLevelJavaArgs(parameters)
        .mapNotNull { parameter -> playerType.find(parameter)?.groupValues?.get(1) }
        .distinct()
    return playerParameters.singleOrNull()
}

internal fun StructuralRefactorPass.migrateLegacyBucketPickupLocalDeclarationBody(body: String, playerArgument: String): String {
    var result = body
    val declarations = Regex("""(?m)^[ \t]*(?:final\s+)?(?:net\.minecraft\.world\.level\.block\.)?BucketPickup\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(body)
        .toList()
        .asReversed()
    for (declaration in declarations) {
        val receiver = declaration.groupValues[1]
        val suffixStart = declaration.range.last + 1
        val suffix = result.substring(suffixStart)
        val migratedSuffix = migrateLegacyBucketPickupCallsForReceiver(suffix, receiver, playerArgument)
        if (migratedSuffix != suffix) {
            result = result.substring(0, suffixStart) + migratedSuffix
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBucketPickupCallsForReceiver(source: String, receiver: String, playerArgument: String): String {
    return rewriteExecutableJavaCallWithOffset(source, "pickupBlock") { callReceiver, args, _ ->
        if (callReceiver != receiver || args.size != 3) return@rewriteExecutableJavaCallWithOffset null
        "$receiver.pickupBlock($playerArgument, ${args.joinToString(", ") { it.trim() }})"
    }
}

internal data class SoundEventSupplierConstructor(
    val parameterCount: Int,
    val supplierIndexes: Set<Int>
)
