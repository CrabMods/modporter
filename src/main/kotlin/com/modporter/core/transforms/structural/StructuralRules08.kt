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

// Structural migration rules, part 08 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateDeclaredCollectionGetSizeCalls, migrateDeclaredCollectionGetSizeCalls, migrateIdBackedEntitySpatialLookups, migrateLegacyItemAttributeModifierOverrides, migrateAnimalIsFoodOverrides.

internal fun StructuralRefactorPass.collectSoundEventSupplierConstructors(javaFiles: List<Path>): Map<String, List<SoundEventSupplierConstructor>> {
    val constructorsBySimpleName = linkedMapOf<String, MutableList<SoundEventSupplierConstructor>>()
    val ownersBySimpleName = linkedMapOf<String, MutableSet<String>>()
    val constructorPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private)\s+)?([A-Za-z_$][\w$]*)\s*\(([^;{}]*)\)\s*(?:throws\s+[^{]+)?\{"""
    )
    val recordPattern = Regex(
        """(?s)\brecord\s+([A-Za-z_$][\w$]*)\s*\((.*?)\)\s*(?:implements\s+[^{]+)?\{"""
    )

    javaFiles.forEach { javaFile ->
        val source = runCatching { javaFile.readText() }.getOrNull() ?: return@forEach
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(code)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        val declaredTypes = Regex("""\b(?:class|record)\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableCode)
            .map { it.groupValues[1] }
            .toSet()
        if (declaredTypes.isEmpty()) return@forEach

        fun addConstructorHint(simpleName: String, paramsText: String) {
            if (simpleName !in declaredTypes) return
            val params = parseJavaParameters(paramsText)
            val supplierIndexes = params.mapIndexedNotNull { index, param ->
                index.takeIf { isLegacySoundEventSupplierParameter(param.type) }
            }.toSet()
            if (supplierIndexes.isEmpty()) return
            val qualifiedName = if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
            ownersBySimpleName.getOrPut(simpleName) { linkedSetOf() }.add(qualifiedName)
            constructorsBySimpleName.getOrPut(simpleName) { mutableListOf() }
                .add(SoundEventSupplierConstructor(params.size, supplierIndexes))
        }

        constructorPattern.findAll(executableCode).forEach { match ->
            addConstructorHint(match.groupValues[1], match.groupValues[2])
        }
        recordPattern.findAll(executableCode).forEach { match ->
            addConstructorHint(match.groupValues[1], match.groupValues[2])
        }
    }

    return constructorsBySimpleName.mapNotNull { (simpleName, constructors) ->
        if ((ownersBySimpleName[simpleName]?.size ?: 0) != 1) {
            null
        } else {
            simpleName to constructors.distinct()
        }
    }.toMap()
}

internal fun StructuralRefactorPass.isLegacySoundEventSupplierParameter(type: String): Boolean =
    Regex("""(?:^|[^A-Za-z0-9_$])(?:java\.util\.function\.)?Supplier\s*<""").containsMatchIn(type) &&
        Regex("""(?:^|[^A-Za-z0-9_$])(?:net\.minecraft\.sounds\.)?SoundEvent(?:$|[^A-Za-z0-9_$])""").containsMatchIn(type) &&
        !Regex("""(?:^|[^A-Za-z0-9_$])(?:net\.minecraft\.core\.)?Holder(?:\.[A-Za-z_$][\w$]*)?\s*<""").containsMatchIn(type)

internal fun StructuralRefactorPass.migrateLegacySoundEventSupplierLambdas(
    source: String,
    constructors: Map<String, List<SoundEventSupplierConstructor>>
): String {
    if (!maskJavaCommentsAndLiterals(source).contains("SoundEvents.") || constructors.isEmpty()) return source

    var result = source
    constructors.keys.forEach { className ->
        result = rewriteExecutableJavaNew(result, className) { args ->
            val migratedArgs = migrateSoundEventSupplierConstructorArgs(args, constructors[className].orEmpty())
                ?: return@rewriteExecutableJavaNew null
            "new $className(${migratedArgs.joinToString(", ") { it.trim() }})"
        }
    }

    var cursor = 0
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf("super(", cursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0 && (executableCode[tokenIndex - 1].isLetterOrDigit() || executableCode[tokenIndex - 1] == '_' || executableCode[tokenIndex - 1] == '.')) {
            cursor = tokenIndex + "super(".length
            continue
        }
        val classDeclaration = enclosingJavaClassDeclaration(result, tokenIndex)
        val superClassName = classDeclaration?.directSuper?.substringAfterLast('.')
        val superConstructors = superClassName?.let { constructors[it] }.orEmpty()
        if (superConstructors.isEmpty()) {
            cursor = tokenIndex + "super(".length
            continue
        }
        val openParen = tokenIndex + "super".length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + "super(".length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val migratedArgs = migrateSoundEventSupplierConstructorArgs(args, superConstructors)
        if (migratedArgs == null) {
            cursor = closeParen + 1
            continue
        }
        val replacement = "super(${migratedArgs.joinToString(", ") { it.trim() }})"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }

    return result
}

internal fun StructuralRefactorPass.migrateSoundEventSupplierConstructorArgs(
    args: List<String>,
    constructors: List<SoundEventSupplierConstructor>
): List<String>? {
    val matchingConstructors = constructors.filter { it.parameterCount == args.size }
    if (matchingConstructors.isEmpty()) return null
    val supplierIndexSets = matchingConstructors.map { it.supplierIndexes }.distinct()
    if (supplierIndexSets.size != 1) return null
    val supplierIndexes = supplierIndexSets.single()
    val migratedArgs = args.mapIndexed { index, arg ->
        if (index in supplierIndexes) migrateVanillaSoundEventSupplierLambda(arg) else arg
    }
    return migratedArgs.takeIf { it != args }
}

internal fun StructuralRefactorPass.migrateVanillaSoundEventSupplierLambda(argument: String): String {
    val match = Regex("""^\s*\(\s*\)\s*->\s*((?:net\.minecraft\.sounds\.)?SoundEvents\.([A-Z0-9_]+))\s*$""")
        .find(argument)
        ?: return argument
    val soundName = match.groupValues[2]
    if (!isVanillaHolderSoundEvent(soundName)) return argument
    val leading = argument.takeWhile { it.isWhitespace() }
    val trailing = argument.takeLastWhile { it.isWhitespace() }
    return "${leading}() -> ${match.groupValues[1]}.value()$trailing"
}

internal fun StructuralRefactorPass.migrateLegacyHolderSoundEventPlaySoundArguments(source: String): String {
    if (!source.contains(".playSound(") || !source.contains("SoundEvents.")) return source
    return migrateMethodCalls(source, ".playSound") { args ->
        val migratedArgs = args.toMutableList()
        val candidateSoundIndices = when (args.size) {
            3 -> listOf(0)
            4 -> listOf(2)
            6 -> listOf(2, 4)
            8 -> listOf(4)
            else -> emptyList()
        }
        for (index in candidateSoundIndices) {
            if (index !in migratedArgs.indices) continue
            migratedArgs[index] = unwrapVanillaHolderSoundEventArgument(migratedArgs[index])
        }
        migratedArgs
    }
}

internal fun StructuralRefactorPass.migrateLegacyHolderSoundEventAssignments(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("SoundEvents.") || !executableCode.contains("SoundEvent")) return source
    val soundVariables = Regex("""\bSoundEvent\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    var result = source
    result = replaceExecutableRegex(
        result,
        Regex("""\bSoundEvent\s+([A-Za-z_$][\w$]*)\s*=\s*SoundEvents\.([A-Z0-9_]+)\s*;""")
    ) { match ->
        val sound = match.groupValues[2]
        if (isVanillaHolderSoundEvent(sound)) {
            "SoundEvent ${match.groupValues[1]} = SoundEvents.$sound.value();"
        } else {
            match.value
        }
    }
    soundVariables.forEach { variable ->
        result = replaceExecutableRegex(
            result,
            Regex("""(?<![\w$])${Regex.escape(variable)}\s*=\s*SoundEvents\.([A-Z0-9_]+)\s*;""")
        ) { match ->
            val sound = match.groupValues[1]
            if (isVanillaHolderSoundEvent(sound)) {
                "$variable = SoundEvents.$sound.value();"
            } else {
                match.value
            }
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""return\s+SoundEvents\.([A-Z0-9_]+)\s*;""")
    ) { match ->
        val sound = match.groupValues[1]
        if (isVanillaHolderSoundEvent(sound)) {
            "return SoundEvents.$sound.value();"
        } else {
            match.value
        }
    }
    return result
}

internal fun StructuralRefactorPass.unwrapVanillaHolderSoundEventArgument(argument: String): String {
    val trimmed = argument.trim()
    if (trimmed.endsWith(".value()")) return argument
    val sound = Regex("""^SoundEvents\.([A-Z0-9_]+)$""").find(trimmed)?.groupValues?.get(1) ?: return argument
    return if (isVanillaHolderSoundEvent(sound)) "${trimmed}.value()" else argument
}

internal fun StructuralRefactorPass.isVanillaHolderSoundEvent(name: String): Boolean =
    name == "AMBIENT_CAVE" ||
        name.startsWith("AMBIENT_BASALT_DELTAS_") ||
        name.startsWith("AMBIENT_CRIMSON_FOREST_") ||
        name.startsWith("AMBIENT_NETHER_WASTES_") ||
        name.startsWith("AMBIENT_SOUL_SAND_VALLEY_") ||
        name.startsWith("AMBIENT_WARPED_FOREST_") ||
        name.startsWith("ARMOR_EQUIP_") ||
        name == "BREEZE_WIND_CHARGE_BURST" ||
        name.startsWith("CROSSBOW_LOADING_") ||
        name.startsWith("CROSSBOW_QUICK_CHARGE_") ||
        name == "GENERIC_EXPLODE" ||
        name == "LLAMA_SWAG" ||
        name.startsWith("MUSIC_") ||
        name.startsWith("NOTE_BLOCK_") ||
        name == "RAID_HORN" ||
        name == "RESPAWN_ANCHOR_DEPLETE" ||
        name == "SOUL_ESCAPE" ||
        name.startsWith("TRIDENT_RIPTIDE_") ||
        name == "TRIDENT_THROW" ||
        name == "TRIDENT_THUNDER" ||
        name == "UI_BUTTON_CLICK" ||
        name == "WIND_CHARGE_BURST"

internal fun StructuralRefactorPass.migrateCraftingInputSizeCallsForDeclaredScopes(source: String): String {
    if (!source.contains("CraftingInput") ||
        (!source.contains(".getContainerSize()") && !source.contains(".asCraftInput()"))) {
        return source
    }
    val id = """[A-Za-z_$][\w$]*"""
    val parameterPattern = Regex(
        """[(,]\s*(?:final\s+)?(?:(?:@\w+(?:\([^)]*\))?)\s+)*(?:net\.minecraft\.world\.item\.crafting\.)?CraftingInput\s+($id)\s*(?=[,)])"""
    )
    val fieldVariables = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private|static|final)\s+)*(?:net\.minecraft\.world\.item\.crafting\.)?CraftingInput\s+($id)\b"""
    ).findAll(source)
        .map { it.groupValues[1] }
        .toSet()

    var result = source
    for (field in fieldVariables) {
        val escaped = Regex.escape(field)
        result = Regex("""\bthis\.$escaped\.asCraftInput\(\)""").replace(result, "this.$field")
        result = Regex("""\bthis\.$escaped\.getContainerSize\(\)""").replace(result, "this.$field.size()")
    }

    var cursor = 0
    while (cursor < result.length) {
        val match = parameterPattern.find(result, cursor) ?: break
        val methodOpen = result.indexOf('{', match.range.last + 1)
        val nextSemicolon = result.indexOf(';', match.range.last + 1)
        if (methodOpen < 0 || (nextSemicolon >= 0 && nextSemicolon < methodOpen)) {
            cursor = match.range.last + 1
            continue
        }
        val methodClose = findMatchingBrace(result, methodOpen)
        if (methodClose <= methodOpen) {
            cursor = match.range.last + 1
            continue
        }
        val variable = match.groupValues[1]
        val escaped = Regex.escape(variable)
        val body = result.substring(methodOpen + 1, methodClose)
        val migratedBody = Regex("""\b((?:this\.)?$escaped)\.asCraftInput\(\)""")
            .replace(body) { scopedMatch -> scopedMatch.groupValues[1] }
            .let { scoped ->
                Regex("""\b$escaped\.getContainerSize\(\)""").replace(scoped, "$variable.size()")
            }
        if (migratedBody != body) {
            result = result.substring(0, methodOpen + 1) + migratedBody + result.substring(methodClose)
            cursor = methodOpen + 1 + migratedBody.length
        } else {
            cursor = methodClose + 1
        }
    }

    return result
}

internal fun StructuralRefactorPass.migrateContainerSizeCallsForDeclaredContainerScopes(source: String): String {
    if (!source.contains("Container") || !source.contains(".size()")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val parameterPattern = Regex("""[(,]\s*(?:final\s+)?(?:(?:@\w+(?:\([^)]*\))?)\s+)*(?:net\.minecraft\.world\.)?Container\s+($id)\s*(?=[,)])""")
    var result = source
    var cursor = 0
    while (cursor < result.length) {
        val match = parameterPattern.find(result, cursor) ?: break
        val methodOpen = result.indexOf('{', match.range.last + 1)
        val nextSemicolon = result.indexOf(';', match.range.last + 1)
        if (methodOpen < 0 || (nextSemicolon >= 0 && nextSemicolon < methodOpen)) {
            cursor = match.range.last + 1
            continue
        }
        val methodClose = findMatchingBrace(result, methodOpen)
        if (methodClose <= methodOpen) {
            cursor = match.range.last + 1
            continue
        }
        val variable = match.groupValues[1]
        val body = result.substring(methodOpen + 1, methodClose)
        val migratedBody = Regex("""\b${Regex.escape(variable)}\.size\(\)""")
            .replace(body, "$variable.getContainerSize()")
        if (migratedBody != body) {
            result = result.substring(0, methodOpen + 1) + migratedBody + result.substring(methodClose)
            cursor = methodOpen + 1 + migratedBody.length
        } else {
            cursor = methodClose + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeclaredCollectionGetSizeCalls(source: String): String {
    if (!source.contains(".getSize()")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val collectionType = """
            (?:
                (?:java\.util\.)?(?:Collection|Iterable|List|Map|Queue|Deque|Set|SortedMap|SortedSet|NavigableMap|NavigableSet)|
                (?:com\.google\.common\.collect\.)?(?:ImmutableList|ImmutableMap|ImmutableSet|ImmutableCollection|Multimap)|
                (?:it\.unimi\.dsi\.fastutil\.[A-Za-z0-9_.]+\.)?[A-Za-z0-9_]+(?:2[A-Za-z0-9_]+)?(?:Map|List|Set|Collection)
            )
        """.trimIndent().replace(Regex("""\s+"""), "")
    val declaredCollections = Regex(
        """\b(?:public|protected|private|static|final|transient|volatile|\s)*($collectionType)(?:\s*<[^;=(){}]*?>+)?\s+($id)\b(?=\s*(?:=|;|,|\)))"""
    ).findAll(source).map { it.groupValues[2] }.toSet()
    if (declaredCollections.isEmpty()) return source

    var result = source
    for (variable in declaredCollections) {
        val escaped = Regex.escape(variable)
        result = Regex("""\bthis\.$escaped\.getSize\(\)""").replace(result, "this.$variable.size()")
        result = Regex("""(?<!\.)\b$escaped\.getSize\(\)""").replace(result, "$variable.size()")
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeclaredCollectionGetSizeCalls(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .forEach { javaFile ->
            val original = javaFile.readText()
            val migrated = migrateDeclaredCollectionGetSizeCalls(original)
            if (migrated != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate declared Java collection getSize() calls to size()",
                    before = "Map/List/Set variable .getSize()",
                    after = "Map/List/Set variable .size()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-collection-getsize"
                ))
                if (!dryRun) {
                    javaFile.writeText(migrated)
                }
            }
        }
    return changes
}

internal fun StructuralRefactorPass.migrateAabbBlockPosPairConstructors(source: String): String {
    if (!source.contains("new AABB(")) return source
    val declaredTypes = javaDeclaredSimpleTypeSets(source)
    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    val token = "new AABB("
    while (cursor < source.length) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + "new AABB".length
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size == 2 && args.all { isAabbBlockPosExpression(it, declaredTypes) }) {
            migrated.append(source, cursor, tokenIndex)
            // 1.20.1's AABB(BlockPos, BlockPos) spans the two positions' raw coordinates;
            // encapsulatingFullBlocks would grow the box by a block
            migrated.append("new AABB(Vec3.atLowerCornerOf(${args[0].trim()}), Vec3.atLowerCornerOf(${args[1].trim()}))")
            cursor = closeParen + 1
            changed = true
            continue
        }
        migrated.append(source, cursor, closeParen + 1)
        cursor = closeParen + 1
    }
    if (!changed) return source
    migrated.append(source, cursor, source.length)
    return addImportIfMissing(migrated.toString(), "net.minecraft.world.phys.Vec3")
}

internal fun StructuralRefactorPass.migrateAabbVec3EncapsulatingFullBlocks(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("AABB.encapsulatingFullBlocks(")) return source
    val declaredTypes = javaDeclaredSimpleTypeSets(source)
    return rewriteExecutableJavaCall(source, "encapsulatingFullBlocks") { receiver, args ->
        if (receiver != "AABB" || args.size != 2) return@rewriteExecutableJavaCall null
        if (!args.all { isAabbVec3Expression(it, declaredTypes) }) return@rewriteExecutableJavaCall null
        "new AABB(${args[0].trim()}, ${args[1].trim()})"
    }
}

internal fun StructuralRefactorPass.javaDeclaredSimpleTypeSets(source: String): Map<String, Set<String>> {
    val id = """[A-Za-z_$][\w$]*"""
    val typeExpression = """(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(?:\s*<[^;=(){}]*>)?(?:\s*\[\])?"""
    val declarations = linkedMapOf<String, MutableSet<String>>()
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
                declarations.getOrPut(name) { linkedSetOf() } += type
            }
        }
    Regex("""\binstanceof\s+($typeExpression)\s+($id)\b""")
        .findAll(source)
        .forEach { match ->
            declarations.getOrPut(match.groupValues[2]) { linkedSetOf() } += simpleTypeName(match.groupValues[1])
        }
    Regex("""\bfor\s*\(\s*($typeExpression)\s+($id)\s*:""")
        .findAll(source)
        .forEach { match ->
            declarations.getOrPut(match.groupValues[2]) { linkedSetOf() } += simpleTypeName(match.groupValues[1])
        }
    return declarations
}

internal fun StructuralRefactorPass.isAabbBlockPosExpression(expression: String, declaredTypes: Map<String, Set<String>>): Boolean {
    val trimmed = expression.trim()
    if (trimmed.contains("Vec3") || Regex("""^\s*[-+]?\d""").containsMatchIn(trimmed)) return false
    if (trimmed.startsWith("new BlockPos(") || trimmed.startsWith("BlockPos.")) return true
    if (Regex("""\.(?:absolutePos|relativePos|blockPosition|getBlockPos|getOnPos)\((?:[^()]|\([^()]*\))*\)$""").containsMatchIn(trimmed)) return true
    val root = trimmed.substringBefore('.').trim()
    return declaredTypes[root] == setOf("BlockPos")
}

internal fun StructuralRefactorPass.isAabbVec3Expression(expression: String, declaredTypes: Map<String, Set<String>>): Boolean {
    val trimmed = expression.trim()
    if (trimmed.startsWith("new Vec3(") || trimmed.startsWith("Vec3.")) return true
    val root = trimmed.substringBefore('.').trim()
    return declaredTypes[root] == setOf("Vec3")
}

internal fun StructuralRefactorPass.migrateGlobalLootModifierCodecs(source: String): String {
    if (!source.contains("IGlobalLootModifier") && !source.contains("GLOBAL_LOOT_MODIFIER_SERIALIZERS")) {
        return source
    }
    var result = source
    result = Regex("""(?<!Map)Codec<\? extends IGlobalLootModifier>""")
        .replace(result, "MapCodec<? extends IGlobalLootModifier>")
    result = result.replace("MapCodec<? extends ChunkGenerator>", "MapCodec<? extends IGlobalLootModifier>")
    result = result.replace("MapMapCodec", "MapCodec")
    result = result.replace("public static final Codec<", "public static final MapCodec<")
    result = Regex("""Supplier\s*<\s*Codec\s*<""")
        .replace(result, "Supplier<MapCodec<")
    result = Regex("""RecordCodecBuilder(\s*\r?\n\s*)\.create\(""")
        .replace(result) { match -> "RecordCodecBuilder${match.groupValues[1]}.mapCodec(" }
    result = result.replace("RecordCodecBuilder.create(", "RecordCodecBuilder.mapCodec(")
    if (result.contains("MapCodec<")) {
        result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
        if (!Regex("""\bCodec\s*[<.]""").containsMatchIn(result)) {
            result = removeImport(result, "com.mojang.serialization.Codec")
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateBonemealableTargetCalls(source: String): String =
    migrateMethodCalls(source, ".isValidBonemealTarget") { args ->
        if (args.size == 4) args.take(3) else args
    }

internal fun StructuralRefactorPass.migrateItemUseDurationCalls(source: String): String {
    if (!source.contains(".getUseDuration(")) return source
    var result = source
    var cursor = 0
    val token = ".getUseDuration("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + "getUseDuration".length + 1
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
        val receiver = result.substring(receiverStart, tokenIndex).trim()
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val itemStackVariables = Regex("""\bItemStack\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableCode)
            .map { it.groupValues[1] }
            .toSet() + itemPropertiesRegisterStackParameters(executableCode)
        val replacement = migratedItemUseDurationCall(
            executableCode = executableCode,
            callOffset = tokenIndex,
            receiver = receiver,
            args = args,
            itemStackVariables = itemStackVariables
        )
        if (replacement == null) {
            cursor = closeParen + 1
            continue
        }
        result = result.substring(0, receiverStart) + replacement + result.substring(closeParen + 1)
        cursor = receiverStart + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.itemPropertiesRegisterStackParameters(executableCode: String): Set<String> {
    if (!executableCode.contains("ItemProperties.register(")) return emptySet()
    val parameters = linkedSetOf<String>()
    var cursor = 0
    val token = "ItemProperties.register("
    while (true) {
        val callStart = executableCode.indexOf(token, cursor)
        if (callStart < 0) break
        val openParen = callStart + "ItemProperties.register".length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = callStart + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(executableCode.substring(openParen + 1, closeParen))
        val lambda = args.lastOrNull().orEmpty()
        Regex("""^\s*\(?\s*([A-Za-z_$][\w$]*)\s*,\s*[A-Za-z_$][\w$]*\s*,\s*[A-Za-z_$][\w$]*\s*,\s*[A-Za-z_$][\w$]*\s*\)?\s*->""")
            .find(lambda)
            ?.groupValues
            ?.get(1)
            ?.let(parameters::add)
        cursor = closeParen + 1
    }
    return parameters
}

internal fun StructuralRefactorPass.migrateBucketPickupPickupBlockCalls(source: String): String {
    if (!source.contains(".pickupBlock(") || !source.contains("BucketPickup")) return source
    var result = source
    var cursor = 0
    val token = ".pickupBlock("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + ".pickupBlock".length
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
        val receiver = result.substring(receiverStart, tokenIndex).trim()
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen)).map { it.trim() }
        val playerArgument = bucketPickupPlayerArgumentForEnclosingMethod(result, tokenIndex)
        if (args.size != 3 || playerArgument == null || !bucketPickupReceiverHasTypeEvidence(result, receiver, tokenIndex)) {
            cursor = closeParen + 1
            continue
        }
        val replacement = "$receiver.pickupBlock($playerArgument, ${args.joinToString(", ")})"
        result = result.substring(0, receiverStart) + replacement + result.substring(closeParen + 1)
        cursor = receiverStart + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.bucketPickupReceiverHasTypeEvidence(source: String, receiver: String, offset: Int): Boolean {
    if (!Regex("""^[A-Za-z_$][\w$]*$""").matches(receiver)) return false
    return receiverHasMethodScopedTypeEvidence(source, receiver, offset, "BucketPickup")
}

internal fun StructuralRefactorPass.migratedItemUseDurationCall(
    executableCode: String,
    callOffset: Int,
    receiver: String,
    args: List<String>,
    itemStackVariables: Set<String>
): String? {
    if (receiver.endsWith(".getUseItem()") && args.isEmpty()) {
        val living = receiver.removeSuffix(".getUseItem()").trim()
        return "$receiver.getUseDuration($living)"
    }
    val simpleReceiver = receiver.removePrefix("this.").trim()
    if (simpleReceiver in itemStackVariables || receiver.endsWith(".getUseItem()")) return null
    return when (args.size) {
        0 -> "$receiver.getUseDuration(null)"
        1 -> {
            val stackArg = args[0].trim()
            if (receiver == "this" && stackArg == "stack") {
                val living = singleLivingEntityParameterAt(executableCode, callOffset) ?: return null
                "$receiver.getUseDuration($stackArg, $living)"
            } else {
                "$receiver.getUseDuration($stackArg, null)"
            }
        }
        else -> null
    }
}

internal fun StructuralRefactorPass.singleLivingEntityParameterAt(executableCode: String, offset: Int): String? {
    val method = javaMethodRangesIncludingDefault(executableCode).firstOrNull { offset in it.range } ?: return null
    val openParen = method.header.indexOf('(')
    val closeParen = method.header.lastIndexOf(')')
    if (openParen < 0 || closeParen <= openParen) return null
    return singleLivingEntityParameter(method.header.substring(openParen + 1, closeParen))
}

internal fun StructuralRefactorPass.singleLivingEntityParameter(parameterList: String): String? {
    val livingParameterPattern = Regex("""(?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s+)*(?:final\s+)?(?:LivingEntity|net\.minecraft\.world\.entity\.LivingEntity)\s+([A-Za-z_$][\w$]*)\b""")
    return livingParameterPattern.findAll(parameterList)
        .map { it.groupValues[1] }
        .distinct()
        .singleOrNull()
}

internal fun StructuralRefactorPass.migrateArmorMaterialHolderFields(source: String): String {
    if (!source.contains("ArmorMaterial") || !source.contains(" material")) return source
    val declaresMaterialField = Regex(
        """(?m)^([ \t]*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?)ArmorMaterial\s+material\s*;"""
    ).containsMatchIn(source)
    if (!declaresMaterialField) return source
    if (!source.contains("this.material.") &&
        !source.contains(" material.") &&
        !source.contains("getMaterial()")) {
        return source
    }

    var result = source
    result = Regex("""(?m)^([ \t]*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?)ArmorMaterial\s+material\s*;""")
        .replace(result, "$1Holder<ArmorMaterial> material;")
    result = Regex("""\bArmorMaterial\s+material\b""")
        .replace(result, "Holder<ArmorMaterial> material")
    result = Regex("""\bpublic\s+ArmorMaterial\s+getMaterial\s*\(\s*\)""")
        .replace(result, "public Holder<ArmorMaterial> getMaterial()")

    result = result
        .replace(".material.getEnchantmentValue()", ".material.value().enchantmentValue()")
        .replace(".material.getRepairIngredient()", ".material.value().repairIngredient().get()")
        .replace(".material.getToughness()", ".material.value().toughness()")
        .replace(".material.getKnockbackResistance()", ".material.value().knockbackResistance()")
        .replace(".material.getEquipSound()", ".material.value().equipSound().value()")
        .replace(".material.getName()", ".material.unwrapKey().orElseThrow().location().getPath()")
        .replace(".getMaterial().getName()", ".getMaterial().unwrapKey().orElseThrow().location().getPath()")

    val materialVariables = Regex("""\bHolder\s*<\s*ArmorMaterial\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    for (variable in materialVariables) {
        val escaped = Regex.escape(variable)
        result = Regex("""\b$escaped\.getEnchantmentValue\(\)""").replace(result, "$variable.value().enchantmentValue()")
        result = Regex("""\b$escaped\.getRepairIngredient\(\)""").replace(result, "$variable.value().repairIngredient().get()")
        result = Regex("""\b$escaped\.getToughness\(\)""").replace(result, "$variable.value().toughness()")
        result = Regex("""\b$escaped\.getKnockbackResistance\(\)""").replace(result, "$variable.value().knockbackResistance()")
        result = Regex("""\b$escaped\.getEquipSound\(\)""").replace(result, "$variable.value().equipSound().value()")
        result = Regex("""\b$escaped\.getName\(\)""").replace(result, "$variable.unwrapKey().orElseThrow().location().getPath()")
    }

    return if (result != source) addImportIfMissing(result, "net.minecraft.core.Holder") else source
}

internal fun StructuralRefactorPass.migrateArmorMaterialHolderNameAccess(source: String): String {
    if (!source.contains("ArmorMaterial") || !source.contains(".getName()")) return source
    var result = source
        .replace(".getMaterial().getName()", ".getMaterial().unwrapKey().orElseThrow().location().getPath()")
        .replace(".material.getName()", ".material.unwrapKey().orElseThrow().location().getPath()")

    val materialVariables = Regex("""\bHolder\s*<\s*ArmorMaterial\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    for (variable in materialVariables) {
        val escaped = Regex.escape(variable)
        result = Regex("""\b$escaped\.getName\(\)""")
            .replace(result, "$variable.unwrapKey().orElseThrow().location().getPath()")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyItemExtensionAndProjectileApis(source: String): String {
    if (!source.contains("canEquip(") &&
        !source.contains("customArrow(") &&
        !source.contains("getArmorTexture(") &&
        !source.contains("getRarity(") &&
        !source.contains("createArrow(") &&
        !source.contains("setKnockback(") &&
        !source.contains("finishUsingItem(") &&
        !source.contains("getEquipmentSlotForItem(") &&
        !source.contains(".hurtAndBreak(") &&
        !source.contains("entityLiving")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var needsLivingEntity = false
    var needsItemStack = false
    var needsEquipmentSlot = false
    var needsArmorMaterial = false
    var needsResourceLocation = false
    var needsDataComponents = false

    result = Regex("""public\s+boolean\s+canEquip\(\s*ItemStack\s+($id)\s*,\s*EquipmentSlot\s+($id)\s*,\s*Entity\s+($id)\s*\)""")
        .replace(result) { match ->
            needsLivingEntity = true
            "public boolean canEquip(ItemStack ${match.groupValues[1]}, EquipmentSlot ${match.groupValues[2]}, LivingEntity ${match.groupValues[3]})"
        }

    result = Regex("""public\s+AbstractArrow\s+customArrow\s*\(\s*AbstractArrow\s+($id)\s*\)""")
        .replace(result) { match ->
            needsItemStack = true
            "public AbstractArrow customArrow(AbstractArrow ${match.groupValues[1]}, ItemStack projectileStack, ItemStack weaponStack)"
        }
    javaDeclaredMethodText(result, "customArrow")?.let { methodText ->
        if (methodText.contains("ItemStack projectileStack") && methodText.contains("ItemStack weaponStack")) {
            val replacement = Regex("""super\.customArrow\(\s*([^,)]+)\s*\)""")
                .replace(methodText) { match -> "super.customArrow(${match.groupValues[1].trim()}, projectileStack, weaponStack)" }
            if (replacement != methodText) {
                result = result.replace(methodText, replacement)
            }
        }
    }

    var legacyArmorLayerParameter: String? = null
    result = Regex(
        """public\s+String\s+getArmorTexture\s*\(\s*ItemStack\s+($id)\s*,\s*Entity\s+($id)\s*,\s*EquipmentSlot\s+($id)\s*,\s*(?:@\w+(?:\.\w+)*\s+)?String\s+($id)\s*\)"""
    ).replace(result) { match ->
        legacyArmorLayerParameter = match.groupValues[4]
        needsArmorMaterial = true
        needsResourceLocation = true
        "public ResourceLocation getArmorTexture(ItemStack ${match.groupValues[1]}, Entity ${match.groupValues[2]}, EquipmentSlot ${match.groupValues[3]}, ArmorMaterial.Layer ${match.groupValues[4]}, boolean innerModel)"
    }
    legacyArmorLayerParameter?.let { layerParameter ->
        result = Regex("""\b${Regex.escape(layerParameter)}\s*==\s*null\b""")
            .replace(result, "$layerParameter.dyeable()")
        result = Regex("""\b${Regex.escape(layerParameter)}\s*!=\s*null\b""")
            .replace(result, "!$layerParameter.dyeable()")
        val armorTextureMethod = javaDeclaredMethodText(result, "getArmorTexture")
        if (armorTextureMethod != null) {
            val replacement = Regex("""return\s+([^;\r\n]+);""").replace(armorTextureMethod) { match ->
                val expression = match.groupValues[1].trim()
                if (expression == "null" || expression.startsWith("ResourceLocation.")) {
                    match.value
                } else {
                    "return ResourceLocation.parse($expression);"
                }
            }
            result = result.replace(armorTextureMethod, replacement)
        }
        val withoutNullableImport = removeImport(result, "org.jetbrains.annotations.Nullable")
        if (!Regex("""@Nullable\b""").containsMatchIn(withoutNullableImport)) {
            result = withoutNullableImport
        }
    }

    val legacyRarity = Regex("""public\s+Rarity\s+getRarity\s*\(\s*ItemStack\s+($id)\s*\)""").find(result)
    if (legacyRarity != null && !result.contains("verifyComponentsAfterLoad(ItemStack")) {
        val stackParameter = legacyRarity.groupValues[1]
        needsDataComponents = true
        result = Regex("""(?m)^([ \t]*)@Override\s*\r?\n([ \t]*public\s+Rarity\s+getRarity\s*\()""")
            .replace(result, "$1$2")
        val getDefaultInstanceMethod = javaDeclaredMethodText(result, "getDefaultInstance")
        if (getDefaultInstanceMethod != null) {
            val stackLocal = Regex("""\bItemStack\s+($id)\s*=""").find(getDefaultInstanceMethod)?.groupValues?.get(1)
            if (stackLocal != null) {
                val replacement = Regex("""(?m)^([ \t]*)return\s+${Regex.escape(stackLocal)}\s*;""")
                    .replace(getDefaultInstanceMethod) { match ->
                        "${match.groupValues[1]}$stackLocal.set(DataComponents.RARITY, this.getRarity($stackLocal));\n${match.value}"
                    }
                result = result.replace(getDefaultInstanceMethod, replacement)
            }
        }
        val verifier = """

	@Override
	public void verifyComponentsAfterLoad(ItemStack $stackParameter) {
		super.verifyComponentsAfterLoad($stackParameter);
		$stackParameter.set(DataComponents.RARITY, this.getRarity($stackParameter));
	}
""".trimEnd()
        result = insertBeforeLastClassBrace(result, verifier)
    }

    val releaseUsing = Regex(
        """releaseUsing\s*\(\s*ItemStack\s+($id)\s*,\s*Level\s+($id)\s*,\s*LivingEntity\s+($id)\s*,\s*int\s+($id)\s*\)"""
    ).find(result)
    if (releaseUsing != null) {
        val weaponStack = releaseUsing.groupValues[1]
        val livingEntity = releaseUsing.groupValues[3]
        result = result.replace("getUseDuration($weaponStack, entityLiving)", "getUseDuration($weaponStack, $livingEntity)")
        result = rewriteJavaCall(result, "createArrow") { receiver, args ->
            if (args.size == 3 && Regex("""\bArrowItem\b""").containsMatchIn(result)) {
                "$receiver.createArrow(${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()}, $weaponStack)"
            } else {
                null
            }
        }
        javaDeclaredMethodText(result, "releaseUsing")?.let { methodText ->
            val projectileStack = Regex("""\bItemStack\s+($id)\s*=\s*[^;\r\n]*getProjectile\s*\(""")
                .find(methodText)
                ?.groupValues
                ?.get(1)
            if (projectileStack != null) {
                var replacement = Regex("""(?<![\w$.])customArrow\s*\(\s*([A-Za-z_$][\w$]*)\s*\)""")
                    .replace(methodText) { match ->
                        "customArrow(${match.groupValues[1]}, $projectileStack, $weaponStack)"
                    }
                replacement = Regex(
                    """(?m)^[ \t]*if\s*\(\s*([A-Za-z_$][\w$]*)\s*>\s*0\s*\)\s*\{\s*\r?\n[ \t]*[A-Za-z_$][\w$]*\.setKnockback\(\s*\1\s*\);\s*\r?\n[ \t]*}\s*\r?\n"""
                ).replace(replacement, "")
                replacement = Regex("""(?m)^[ \t]*if\s*\(\s*([A-Za-z_$][\w$]*)\s*>\s*0\s*\)\s*[A-Za-z_$][\w$]*\.setKnockback\(\s*\1\s*\);\s*\r?\n""")
                    .replace(replacement, "")
                if (replacement != methodText) {
                    result = result.replace(methodText, replacement)
                }
            }
        }
    }

    result = migrateLegacyProjectileWeaponFinishUsingItemSource(result)

    result = migrateLegacyEquipmentSlotForItemReceivers(result)

    result = Regex(
        """(?s)if\s*\(\s*($id)\s+instanceof\s+Player\s+($id)\s*&&\s*!\2\.isCreative\(\)\s*\)\s*\{\s*($id)\.hurtAndBreak\(\s*([^,]+)\s*,\s*($id)\s*,\s*null\s*,\s*item\s*->\s*\{\s*}\s*\)\s*;\s*}"""
    ).replace(result) { match ->
        needsEquipmentSlot = true
        val player = match.groupValues[2]
        val stack = match.groupValues[3]
        val amount = match.groupValues[4].trim()
        "if (${match.groupValues[1]} instanceof Player $player && !$player.isCreative()) {\n\t\t\t\t\t\t\t$stack.hurtAndBreak($amount, $player, EquipmentSlot.MAINHAND);\n\t\t\t\t\t\t}"
    }

    if (needsLivingEntity) {
        result = addImportIfMissing(result, "net.minecraft.world.entity.LivingEntity")
        val withoutEntityImport = removeImport(result, "net.minecraft.world.entity.Entity")
        if (!Regex("""\bEntity\b""").containsMatchIn(withoutEntityImport)) {
            result = withoutEntityImport
        }
    }
    if (needsItemStack) {
        result = addImportIfMissing(result, "net.minecraft.world.item.ItemStack")
    }
    if (needsEquipmentSlot) {
        result = addImportIfMissing(result, "net.minecraft.world.entity.EquipmentSlot")
    }
    if (needsArmorMaterial) {
        result = addImportIfMissing(result, "net.minecraft.world.item.ArmorMaterial")
    }
    if (needsResourceLocation) {
        result = addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
    }
    if (needsDataComponents) {
        result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyProjectileWeaponShootProjectileBridge(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val hasLegacyConcreteProjectileSurface =
        executableCode.contains("getAllSupportedProjectiles(") ||
            executableCode.contains("getDefaultProjectileRange(")
    val hasLegacyProjectileLaunchSurface =
        executableCode.contains(".getProjectile(") ||
            executableCode.contains(".shootFromRotation(") ||
            executableCode.contains("customArrow(") ||
            executableCode.contains("createArrow(")
    if (!executableCode.contains("extends ProjectileWeaponItem") ||
        (!hasLegacyConcreteProjectileSurface && !hasLegacyProjectileLaunchSurface) ||
        executableCode.contains("shootProjectile(")) {
        return source
    }
    val classDeclaration = Regex("""(?m)\bpublic\s+class\s+[A-Za-z_$][\w$]*\s+extends\s+ProjectileWeaponItem\b[^{]*\{""")
        .find(executableCode)
        ?: return source
    val classOpen = executableCode.indexOf('{', classDeclaration.range.last - 1)
    val classClose = if (classOpen >= 0) findMatchingBrace(executableCode, classOpen) else -1
    if (classOpen < 0 || classClose < 0) return source
    val bridge = """

    @Override
    protected void shootProjectile(LivingEntity shooter, Projectile projectile, int index, float velocity, float inaccuracy, float angle, @javax.annotation.Nullable LivingEntity target) {
        projectile.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot() + angle, 0.0F, velocity, inaccuracy);
    }"""
    var result = source.substring(0, classClose) + bridge + source.substring(classClose)
    result = addImportIfMissing(result, "net.minecraft.world.entity.LivingEntity")
    result = addImportIfMissing(result, "net.minecraft.world.entity.projectile.Projectile")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyOnArmorTickSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("onArmorTick(")) return source

    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        if (method.name != "onArmorTick") continue
        val methodText = result.substring(method.range)
        val params = javaMethodParameters(methodText)
        if (params.size != 3 ||
            simpleJavaTypeName(params[0].type) != "ItemStack" ||
            simpleJavaTypeName(params[1].type) != "Level" ||
            simpleJavaTypeName(params[2].type) != "Player") {
            continue
        }
        val stackName = params[0].name
        val levelName = params[1].name
        val playerName = params[2].name
        val indent = Regex("""(?m)^([ \t]*)(?:@\w+(?:\([^)]*\))?\s*\r?\n[ \t]*)*public\s+void\s+onArmorTick\s*\(""")
            .find(methodText)
            ?.groupValues
            ?.get(1)
            ?: ""
        val body = javaMethodBodyText(methodText) ?: continue
        var migratedBody = body
        migratedBody = Regex("""(?m)^[ \t]*super\.onArmorTick\([^;\r\n]*\);\s*\r?\n""").replace(migratedBody, "")
        val replacement = buildString {
            append(indent)
            append("@Override\n")
            append(indent)
            append("public void inventoryTick(ItemStack ")
            append(stackName)
            append(", Level ")
            append(levelName)
            append(", Entity entity, int slot, boolean selected) {\n")
            append(indent)
            append("    super.inventoryTick(")
            append(stackName)
            append(", ")
            append(levelName)
            append(", entity, slot, selected);\n")
            append(indent)
            append("    if (!(entity instanceof Player ")
            append(playerName)
            append(") || !")
            append(playerName)
            append(".getInventory().armor.contains(")
            append(stackName)
            append(")) {\n")
            append(indent)
            append("        return;\n")
            append(indent)
            append("    }\n")
            append(migratedBody.trimEnd())
            append("\n")
            append(indent)
            append("}")
        }
        result = result.replaceRange(method.range, replacement)
    }
    return if (result != source) addImportIfMissing(result, "net.minecraft.world.entity.Entity") else source
}

internal data class NitrogenFuelTextureField(
    val fieldName: String,
    val namespaceExpression: String,
    val menuPath: String,
    val spriteStem: String
)

internal fun StructuralRefactorPass.migrateNitrogenFuelCategorySource(source: String): String {
    if (!containsNitrogenFuelCategoryApiUse(source, "jei") &&
        !containsNitrogenFuelCategoryApiUse(source, "rei")) {
        return source
    }

    val textureFields = collectNitrogenFuelTextureFields(source)
    if (textureFields.isEmpty()) return source

    var result = source
    val jeiFuelCategory = containsNitrogenFuelCategoryApiUse(result, "jei")
    if (jeiFuelCategory && containsNitrogenFuelGetTextureOverride(result)) {
        val field = nitrogenFuelTextureFieldReturnedByGetTexture(result, textureFields)
            ?: error("Cannot derive Nitrogen fuel getTexture texture field: getTexture() must return exactly one declared menu texture field")
        result = replaceExecutableRegex(result, Regex("""\bResourceLocation\s+getTexture\s*\(\s*\)""")) {
            "ResourceLocation getBackgroundTexture()"
        }
        if (!result.contains("getIconTexture()")) {
            val iconField = "MODPORTER_NITROGEN_FUEL_ICON_TEXTURE"
            if (!result.contains(iconField)) {
                result = insertNitrogenFuelResourceLocationField(
                    result,
                    field.fieldName,
                    "public static final ResourceLocation $iconField = ResourceLocation.fromNamespaceAndPath(${field.namespaceExpression}, \"textures/gui/sprites/modporter/nitrogen_fuel_${field.spriteStem}_icon.png\");"
                )
            }
            result = insertBeforeLastClassBrace(result, """

	@Override
	public ResourceLocation getIconTexture() {
		return $iconField;
	}
""".trimEnd())
        }
    }

    val reiFuelCategory = containsNitrogenFuelCategoryApiUse(result, "rei")
    if (reiFuelCategory && containsNitrogenFuelCategoryConstructorCall(result)) {
        val fieldByName = textureFields.associateBy { it.fieldName }
        collectNewAbstractFuelCategoryTextureFields(result, fieldByName).forEach { field ->
            val constantStem = nitrogenFuelConstantStem(field.fieldName)
            val textureConstant = "MODPORTER_NITROGEN_FUEL_${constantStem}_TEXTURE"
            if (!result.contains(textureConstant)) {
                result = insertNitrogenFuelResourceLocationField(
                    result,
                    field.fieldName,
                    "public static final ResourceLocation $textureConstant = ResourceLocation.fromNamespaceAndPath(${field.namespaceExpression}, \"modporter/nitrogen_fuel_${field.spriteStem}_icon\");\n" +
                        "    public static final ResourceLocation MODPORTER_NITROGEN_FUEL_${constantStem}_BACKGROUND_TEXTURE = ResourceLocation.fromNamespaceAndPath(${field.namespaceExpression}, \"modporter/nitrogen_fuel_${field.spriteStem}_background\");"
                )
            }
        }
        result = rewriteNewAbstractFuelCategoryConstructors(result, fieldByName) { field ->
            val constantStem = nitrogenFuelConstantStem(field.fieldName)
            "MODPORTER_NITROGEN_FUEL_${constantStem}_TEXTURE" to
                "MODPORTER_NITROGEN_FUEL_${constantStem}_BACKGROUND_TEXTURE"
        }
    }

    return result
}

internal fun StructuralRefactorPass.nitrogenFuelTextureFieldReturnedByGetTexture(
    source: String,
    textureFields: List<NitrogenFuelTextureField>
): NitrogenFuelTextureField? {
    val methodText = javaMethodText(maskJavaCommentsAndLiterals(source), "getTexture") ?: return null
    val returnedFields = textureFields
        .filter { field ->
            Regex("""\breturn\s+${Regex.escape(field.fieldName)}\s*;""").containsMatchIn(methodText)
        }
        .distinctBy { it.fieldName }
    return returnedFields.singleOrNull()
}

internal fun StructuralRefactorPass.containsNitrogenFuelCategoryApiUse(source: String, integration: String): Boolean {
    val code = maskJavaCommentsAndLiterals(source)
    val fqType =
        """com\.aetherteam\.nitrogen\.integration\.${Regex.escape(integration)}\.categories\.fuel\.AbstractFuelCategory"""
    val hasImport = Regex("""(?m)^\s*import\s+$fqType\s*;""").containsMatchIn(code)
    val simpleTypeUse = Regex("""\bextends\s+AbstractFuelCategory\b|\bnew\s+AbstractFuelCategory\s*\(""")
        .containsMatchIn(code)
    val qualifiedTypeUse = Regex("""\bextends\s+$fqType\b|\bnew\s+$fqType\s*\(""")
        .containsMatchIn(code)
    return qualifiedTypeUse || (hasImport && simpleTypeUse)
}

internal fun StructuralRefactorPass.containsNitrogenFuelGetTextureOverride(source: String): Boolean {
    val code = maskJavaCommentsAndLiterals(source)
    return Regex("""\bResourceLocation\s+getTexture\s*\(\s*\)""").containsMatchIn(code)
}

internal fun StructuralRefactorPass.containsNitrogenFuelCategoryConstructorCall(source: String): Boolean {
    val code = maskJavaCommentsAndLiterals(source)
    return Regex("""\bnew\s+AbstractFuelCategory\s*\(""").containsMatchIn(code) ||
        Regex("""\bnew\s+com\.aetherteam\.nitrogen\.integration\.rei\.categories\.fuel\.AbstractFuelCategory\s*\(""")
            .containsMatchIn(code)
}

internal fun StructuralRefactorPass.collectNitrogenFuelTextureFields(source: String): List<NitrogenFuelTextureField> {
    val code = maskJavaComments(source)
    val id = """[A-Za-z_$][\w$]*"""
    val resourceLocationExpression =
        """(?:new\s+ResourceLocation|ResourceLocation\.fromNamespaceAndPath|net\.minecraft\.resources\.ResourceLocation\.fromNamespaceAndPath)"""
    val fieldPattern = Regex(
        """(?m)\bResourceLocation\s+($id)\s*=\s*$resourceLocationExpression\s*\(\s*([^,\r\n]+?)\s*,\s*"textures/gui/menu/([^"]+?)\.png"\s*\)\s*;"""
    )
    return fieldPattern.findAll(code).map { match ->
        val fieldName = match.groupValues[1]
        val namespaceExpression = match.groupValues[2].trim()
        val menuPath = "textures/gui/menu/${match.groupValues[3]}.png"
        val spriteStem = match.groupValues[3]
            .substringAfterLast('/')
            .replace(Regex("""[^A-Za-z0-9_]+"""), "_")
            .trim('_')
            .ifBlank { fieldName.lowercase() }
        NitrogenFuelTextureField(fieldName, namespaceExpression, menuPath, spriteStem)
    }.toList()
}

internal fun StructuralRefactorPass.insertNitrogenFuelResourceLocationField(source: String, afterField: String, declaration: String): String {
    val fieldPattern = Regex("""(?m)^([ \t]*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?)ResourceLocation\s+${Regex.escape(afterField)}\s*=[^\r\n;]+;""")
    val match = fieldPattern.find(source) ?: return source
    val lineEnd = source.indexOf('\n', match.range.last).let { if (it < 0) match.range.last + 1 else it + 1 }
    val indent = source.substring(match.range.first, match.range.first + match.groupValues[1].length)
        .takeWhile { it == ' ' || it == '\t' }
    val indentedDeclaration = declaration.lines().joinToString("\n") { line ->
        if (line.isBlank()) line else indent + line.trimStart()
    }
    return source.substring(0, lineEnd) + indentedDeclaration + "\n" + source.substring(lineEnd)
}

internal fun StructuralRefactorPass.nitrogenFuelConstantStem(fieldName: String): String =
    fieldName.removeSuffix("_TEXTURE").ifBlank { fieldName }

internal fun StructuralRefactorPass.rewriteNewAbstractFuelCategoryConstructors(
    source: String,
    fields: Map<String, NitrogenFuelTextureField>,
    replacementFields: (NitrogenFuelTextureField) -> Pair<String, String>
): String {
    var result = source
    var code = maskJavaCommentsAndLiterals(result)
    var cursor = 0
    val token = "new AbstractFuelCategory("
    while (true) {
        val tokenIndex = code.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + "new AbstractFuelCategory".length
        val closeParen = findMatchingParen(code, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(code.substring(openParen + 1, closeParen))
        if (args.size != 2) {
            cursor = closeParen + 1
            continue
        }
        val field = fields[args[1].trim()]
        if (field == null) {
            cursor = closeParen + 1
            continue
        }
        val (textureField, backgroundField) = replacementFields(field)
        val replacementArgs = "${args[0].trim()}, $textureField, $backgroundField"
        result = result.substring(0, openParen + 1) + replacementArgs + result.substring(closeParen)
        code = maskJavaCommentsAndLiterals(result)
        cursor = openParen + 1 + replacementArgs.length
    }
    return result
}

internal fun StructuralRefactorPass.collectNewAbstractFuelCategoryTextureFields(
    source: String,
    fields: Map<String, NitrogenFuelTextureField>
): List<NitrogenFuelTextureField> {
    val found = linkedSetOf<NitrogenFuelTextureField>()
    val code = maskJavaCommentsAndLiterals(source)
    var cursor = 0
    val token = "new AbstractFuelCategory("
    while (true) {
        val tokenIndex = code.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + "new AbstractFuelCategory".length
        val closeParen = findMatchingParen(code, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(code.substring(openParen + 1, closeParen))
        if (args.size == 2) {
            fields[args[1].trim()]?.let { found += it }
        }
        cursor = closeParen + 1
    }
    return found.toList()
}

internal fun StructuralRefactorPass.migrateLegacyEquipmentSlotForItemReceivers(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("this.getEquipmentSlotForItem(")) return source
    val edits = mutableListOf<Pair<IntRange, String>>()
    val callPattern = Regex("""\bthis\.getEquipmentSlotForItem\(""")
    javaMethodRangesIncludingDefault(executableCode).forEach { method ->
        val methodText = executableCode.substring(method.range)
        val owner = equipmentSlotOwnerParameter(methodText) ?: return@forEach
        callPattern.findAll(methodText).forEach { match ->
            edits += (method.range.first + match.range.first)..(method.range.first + match.range.last) to
                "$owner.getEquipmentSlotForItem("
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.equipmentSlotOwnerParameter(methodText: String): String? {
    val parameters = javaMethodParameters(methodText)
    val playerParameters = parameters
        .filter { simpleJavaTypeName(it.type) == "Player" }
        .map { it.name }
        .distinct()
    if (playerParameters.size == 1) return playerParameters.single()
    val livingEntityParameters = parameters
        .filter { simpleJavaTypeName(it.type) == "LivingEntity" }
        .map { it.name }
        .distinct()
    return livingEntityParameters.singleOrNull()
}

internal data class LegacyProjectileWeaponShape(
    val weaponStack: String,
    val level: String,
    val user: String,
    val player: String,
    val ammoVariable: String,
    val projectileItemType: String,
    val projectileItemVariable: String,
    val projectileType: String,
    val projectileVariable: String,
    val createMethod: String,
    val fallbackItemExpression: String,
    val customProjectileMethod: String?,
    val velocity: String,
    val inaccuracy: String,
    val playSoundStatement: String
)

internal fun StructuralRefactorPass.migrateLegacyProjectileWeaponFinishUsingItemSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("extends ProjectileWeaponItem") ||
        !executableCode.contains("finishUsingItem(") ||
        !executableCode.contains(".getProjectile(") ||
        !executableCode.contains(".shootFromRotation(") ||
        !executableCode.contains(".addFreshEntity(")) {
        return source
    }
    val method = javaMethodRanges(executableCode).singleOrNull { method ->
        method.name == "finishUsingItem" &&
            Regex("""\bItemStack\s+finishUsingItem\s*\(""").containsMatchIn(method.header)
    } ?: return source
    val methodText = source.substring(method.range)
    val shape = parseLegacyProjectileWeaponShape(methodText) ?: return source
    var result = source.replaceRange(method.range, modernProjectileWeaponFinishUsingItem(shape))

    var resultCode = maskJavaCommentsAndLiterals(result)
    if (!resultCode.contains("shootProjectile(LivingEntity shooter, Projectile projectile")) {
        result = insertBeforeLastClassBrace(result, modernProjectileWeaponShootProjectileMethod())
        resultCode = maskJavaCommentsAndLiterals(result)
    }
    if (!resultCode.contains("createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean isCrit)")) {
        result = insertBeforeLastClassBrace(result, modernProjectileWeaponCreateProjectileMethod(shape))
    }
    if (shape.customProjectileMethod != null) {
        val customProjectilePattern = Regex(
            """public\s+${Regex.escape(shape.projectileType)}\s+${Regex.escape(shape.customProjectileMethod)}\s*\(\s*${Regex.escape(shape.projectileType)}\s+([A-Za-z_$][\w$]*)\s*\)"""
        )
        result = replaceExecutableRegex(result, customProjectilePattern) { match ->
            "public ${shape.projectileType} ${shape.customProjectileMethod}(${shape.projectileType} ${match.groupValues[1]}, ItemStack projectileStack, ItemStack weaponStack)"
        }
    }

    result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    result = addImportIfMissing(result, "net.minecraft.world.entity.projectile.AbstractArrow")
    result = addImportIfMissing(result, "net.minecraft.world.entity.projectile.Projectile")
    result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.EnchantmentHelper")
    result = addImportIfMissing(result, "org.jetbrains.annotations.Nullable")
    result = addImportIfMissing(result, "java.util.List")
    return result
}

internal fun StructuralRefactorPass.parseLegacyProjectileWeaponShape(methodText: String): LegacyProjectileWeaponShape? {
    val methodCode = maskJavaCommentsAndLiterals(methodText)
    val id = """[A-Za-z_$][\w$]*"""
    val signature = Regex(
        """finishUsingItem\s*\(\s*ItemStack\s+($id)\s*,\s*Level\s+($id)\s*,\s*LivingEntity\s+($id)\s*\)"""
    ).find(methodCode) ?: return null
    val weaponStack = signature.groupValues[1]
    val level = signature.groupValues[2]
    val user = signature.groupValues[3]
    val player = Regex("""\b${Regex.escape(user)}\s+instanceof\s+Player\s+($id)\b""")
        .find(methodCode)
        ?.groupValues
        ?.get(1)
        ?: return null
    val ammoVariable = Regex("""\bItemStack\s+($id)\s*=\s*${Regex.escape(player)}\.getProjectile\(\s*${Regex.escape(weaponStack)}\s*\)""")
        .find(methodCode)
        ?.groupValues
        ?.get(1)
        ?: return null
    val projectileItem = Regex(
        """\b($id)\s+($id)\s*=\s*\(\s*\1\s*\)\s*\(\s*${Regex.escape(ammoVariable)}\.getItem\(\)\s+instanceof\s+\1\s+$id\s*\?\s*$id\s*:\s*(.+?)\s*\)\s*;"""
    ).find(methodCode) ?: return null
    val projectileItemType = projectileItem.groupValues[1]
    val projectileItemVariable = projectileItem.groupValues[2]
    val fallbackItemExpression = projectileItem.groupValues[3].trim()
    val projectileCreation = Regex(
        """\b($id)\s+($id)\s*=\s*${Regex.escape(projectileItemVariable)}\.(create$id)\s*\(\s*${Regex.escape(level)}\s*,\s*${Regex.escape(player)}\s*\)\s*;"""
    ).find(methodCode) ?: return null
    val projectileType = projectileCreation.groupValues[1]
    val projectileVariable = projectileCreation.groupValues[2]
    val createMethod = projectileCreation.groupValues[3]
    val shoot = Regex(
        """\b${Regex.escape(projectileVariable)}\.shootFromRotation\(\s*${Regex.escape(player)}\s*,\s*${Regex.escape(player)}\.getXRot\(\)\s*,\s*${Regex.escape(player)}\.getYRot\(\)\s*,\s*0\.0F\s*,\s*([^,]+?)\s*,\s*([^)]+?)\s*\)\s*;"""
    ).find(methodCode) ?: return null
    val customMethod = Regex(
        """${Regex.escape(projectileVariable)}\s*=\s*this\.($id)\s*\(\s*${Regex.escape(projectileVariable)}\s*\)\s*;"""
    ).find(methodCode)?.groupValues?.get(1)
    val playSoundStatement = Regex(
        """(?m)^[ \t]*${Regex.escape(level)}\.playSound\([^;]+;\s*$"""
    ).find(methodCode)
        ?.range
        ?.let { methodText.substring(it.first, it.last + 1).trim() }
        ?: return null
    if (!methodCode.contains("$level.addFreshEntity($projectileVariable);")) return null
    return LegacyProjectileWeaponShape(
        weaponStack = weaponStack,
        level = level,
        user = user,
        player = player,
        ammoVariable = ammoVariable,
        projectileItemType = projectileItemType,
        projectileItemVariable = projectileItemVariable,
        projectileType = projectileType,
        projectileVariable = projectileVariable,
        createMethod = createMethod,
        fallbackItemExpression = fallbackItemExpression,
        customProjectileMethod = customMethod,
        velocity = shoot.groupValues[1].trim(),
        inaccuracy = shoot.groupValues[2].trim(),
        playSoundStatement = playSoundStatement
    )
}

internal fun StructuralRefactorPass.modernProjectileWeaponFinishUsingItem(shape: LegacyProjectileWeaponShape): String = """
    @Override
    public ItemStack finishUsingItem(ItemStack ${shape.weaponStack}, Level ${shape.level}, LivingEntity ${shape.user}) {
        if (${shape.user} instanceof Player ${shape.player}) {
            ItemStack itemStack = ${shape.player}.getProjectile(${shape.weaponStack});
            if (!itemStack.isEmpty()) {
                net.neoforged.neoforge.event.EventHooks.onArrowLoose(${shape.weaponStack}, ${shape.level}, ${shape.player}, 0, !itemStack.isEmpty());

                List<ItemStack> list = draw(${shape.weaponStack}, itemStack, ${shape.player});
                if (${shape.level} instanceof ServerLevel serverLevel && !list.isEmpty()) {
                    this.shoot(serverLevel, ${shape.player}, ${shape.player}.getUsedItemHand(), ${shape.weaponStack}, list, ${shape.velocity}, ${shape.inaccuracy}, false, null);
                }

                ${shape.playSoundStatement}
                ${shape.player}.awardStat(Stats.ITEM_USED.get(this));
            }
        }
        return ${shape.weaponStack};
    }
""".trimIndent()

internal fun StructuralRefactorPass.modernProjectileWeaponShootProjectileMethod(): String = """

	@Override
	protected void shootProjectile(LivingEntity shooter, Projectile projectile, int index, float velocity, float inaccuracy, float angle, @Nullable LivingEntity target) {
		projectile.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot(), 0.0F, velocity, inaccuracy);
	}
""".trimEnd()

internal fun StructuralRefactorPass.modernProjectileWeaponCreateProjectileMethod(shape: LegacyProjectileWeaponShape): String {
    val customReturn = if (shape.customProjectileMethod != null) {
        "return this.${shape.customProjectileMethod}(${shape.projectileVariable}, ammo, weapon);"
    } else {
        "return ${shape.projectileVariable};"
    }
    return """

	@Override
	protected Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean isCrit) {
		${shape.projectileItemType} ${shape.projectileItemVariable} = ammo.getItem() instanceof ${shape.projectileItemType} projectileItem ? projectileItem : (${shape.projectileItemType}) ${shape.fallbackItemExpression};
		${shape.projectileType} ${shape.projectileVariable} = ${shape.projectileItemVariable}.${shape.createMethod}(level, shooter);
		if (${shape.projectileVariable} != null) {
			${shape.projectileVariable}.setNoGravity(true);
			if (${shape.projectileVariable} instanceof AbstractArrow abstractArrow) {
				ItemStack projectileStack = ammo.copyWithCount(1);
				abstractArrow.getSlot(0).set(projectileStack);
				abstractArrow.setCustomName(projectileStack.get(DataComponents.CUSTOM_NAME));
				if (projectileStack.remove(DataComponents.INTANGIBLE_PROJECTILE) != null) {
					abstractArrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
				}
				if (level instanceof ServerLevel serverLevel && !weapon.isEmpty()) {
					abstractArrow.firedFromWeapon = weapon.copy();
					int piercing = EnchantmentHelper.getPiercingCount(serverLevel, weapon, projectileStack);
					if (piercing > 0) {
						abstractArrow.setPierceLevel((byte) piercing);
					}
					EnchantmentHelper.onProjectileSpawned(serverLevel, weapon, abstractArrow, ignored -> abstractArrow.firedFromWeapon = null);
				}
			}
			$customReturn
		}
		return super.createProjectile(level, shooter, weapon, ammo, isCrit);
	}
""".trimEnd()
}

internal fun StructuralRefactorPass.migrateLegacyTooltipContextAndEntityDataSource(source: String): String {
    if (!source.contains("TooltipContext") &&
        !source.contains("EntityType.updateCustomEntityTag(")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var needsLevel = false
    var needsCustomData = false

    val hoverText = javaDeclaredMethodText(result, "appendHoverText")
    if (hoverText != null) {
        val contextVariable = Regex("""\b(?:Item\.)?TooltipContext\s+($id)\b""")
            .find(hoverText)
            ?.groupValues
            ?.get(1)
        if (contextVariable != null &&
            (hoverText.contains("$contextVariable.registryAccess()") ||
                hoverText.contains("$contextVariable.dimensionType()") ||
                hoverText.contains("$contextVariable.getMoonPhase()") ||
                hoverText.contains("$contextVariable.holderLookup("))) {
            val levelVariable = uniqueLocalName(hoverText, "tooltipLevel")
            var migratedMethod = hoverText
            migratedMethod = Regex("""\bif\s*\(\s*${Regex.escape(contextVariable)}\s*!=\s*null\s*\)""")
                .replace(migratedMethod, "if ($levelVariable != null)")
            migratedMethod = Regex("""\b${Regex.escape(contextVariable)}\.registryAccess\(\)""")
                .replace(migratedMethod, "$levelVariable.registryAccess()")
            migratedMethod = Regex("""\b${Regex.escape(contextVariable)}\s*!=\s*null\s*&&\s*${Regex.escape(contextVariable)}\.dimensionType\(\)""")
                .replace(migratedMethod, "$levelVariable != null && $levelVariable.dimensionType()")
            migratedMethod = Regex("""\b${Regex.escape(contextVariable)}\.dimensionType\(\)""")
                .replace(migratedMethod, "$levelVariable.dimensionType()")
            migratedMethod = Regex("""\b${Regex.escape(contextVariable)}\.getMoonPhase\(\)""")
                .replace(migratedMethod, "$levelVariable.getMoonPhase()")
            // the level's feature-filtered lookup (LevelReader#holderLookup), as the Level parameter gave it
            migratedMethod = Regex("""\b${Regex.escape(contextVariable)}\.holderLookup\(""")
                .replace(migratedMethod, "$levelVariable.holderLookup(")

            val localLine = "Level $levelVariable = $contextVariable != null ? $contextVariable.level() : null;"
            if (!migratedMethod.contains(localLine)) {
                val superCall = Regex("""(?m)^([ \t]*)super\.appendHoverText\([^;\r\n]*;\s*$""").find(migratedMethod)
                migratedMethod = if (superCall != null) {
                    val insertAt = superCall.range.last + 1
                    val indent = superCall.groupValues[1]
                    migratedMethod.substring(0, insertAt) +
                        "\n${indent}$localLine" +
                        migratedMethod.substring(insertAt)
                } else {
                    val openBrace = migratedMethod.indexOf('{')
                    if (openBrace >= 0) {
                        val nextLineIndent = Regex("""\r?\n([ \t]+)\S""")
                            .find(migratedMethod, openBrace)
                            ?.groupValues
                            ?.get(1)
                            ?: "\t\t"
                        migratedMethod.substring(0, openBrace + 1) +
                            "\n$nextLineIndent$localLine" +
                            migratedMethod.substring(openBrace + 1)
                    } else {
                        migratedMethod
                    }
                }
                needsLevel = true
            }
            result = result.replace(hoverText, migratedMethod)
        }
    }

    val compoundTagVariables = Regex("""\bCompoundTag\s+($id)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    result = rewriteJavaInvocationArguments(result, "EntityType.updateCustomEntityTag") { args ->
        if (args.size != 4) return@rewriteJavaInvocationArguments null
        val tagArg = args[3].trim()
        if (tagArg.startsWith("CustomData.") ||
            tagArg.startsWith("net.minecraft.world.item.component.CustomData.") ||
            tagArg !in compoundTagVariables) {
            return@rewriteJavaInvocationArguments null
        }
        needsCustomData = true
        args.take(3) + "CustomData.of($tagArg)"
    }

    if (needsLevel) {
        result = addImportIfMissing(result, "net.minecraft.world.level.Level")
    }
    if (needsCustomData) {
        result = addImportIfMissing(result, "net.minecraft.world.item.component.CustomData")
    }
    return result
}

internal fun StructuralRefactorPass.migrateMobEffectApplyEffectTickReturnSource(source: String): String {
    if (!source.contains("boolean applyEffectTick(")) return source
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\s*\r?\n[ \t]*)*public\s+boolean\s+applyEffectTick\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    val insertions = methodPattern.findAll(source)
        .mapNotNull { match ->
            val openBrace = source.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            if (openBrace < 0 || closeBrace < 0) return@mapNotNull null
            val body = source.substring(openBrace + 1, closeBrace)
            if (!mobEffectBooleanMethodNeedsTrailingTrue(body)) return@mapNotNull null
            val indent = match.value.takeWhile { it == ' ' || it == '\t' }
            closeBrace to "\n${indent}    return true;\n$indent"
        }
        .toList()
    if (insertions.isEmpty()) return source
    var result = source
    for ((index, text) in insertions.asReversed()) {
        result = result.substring(0, index) + text + result.substring(index)
    }
    return result
}

internal fun StructuralRefactorPass.migrateMobEffectApplicableEventSource(source: String): String {
    if (!source.contains("MobEffectEvent.Applicable")) return source
    var result = source
    var cursor = 0
    var changed = false
    val constructor = "new MobEffectEvent.Applicable("
    while (true) {
        val start = result.indexOf(constructor, cursor)
        if (start < 0) break
        val openParen = start + constructor.length - 1
        val closeParen = findMatchingParen(result, openParen)
        if (closeParen < 0) {
            cursor = start + constructor.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size == 2) {
            val replacement = "new MobEffectEvent.Applicable(${args[0].trim()}, ${args[1].trim()}, null)"
            result = result.substring(0, start) + replacement + result.substring(closeParen + 1)
            cursor = start + replacement.length
            changed = true
        } else {
            cursor = closeParen + 1
        }
    }

    result = Regex("""(?m)^([ \t]*)NeoForge\.EVENT_BUS\.post\(([^;\r\n]+?)\)(?:\.isCanceled\(\))+;""")
        .replace(result) { match ->
            changed = true
            "${match.groupValues[1]}NeoForge.EVENT_BUS.post(${match.groupValues[2].trim()});"
        }
    val migratedResults = result
        .replace("TriState.DEFAULT", "MobEffectEvent.Applicable.Result.DEFAULT")
        .replace("TriState.TRUE", "MobEffectEvent.Applicable.Result.APPLY")
        .replace("TriState.FALSE", "MobEffectEvent.Applicable.Result.DO_NOT_APPLY")
    if (migratedResults != result) {
        result = migratedResults
        changed = true
    }
    if (!changed) return source
    val withoutTriStateImport = removeImport(result, "net.neoforged.neoforge.common.util.TriState")
    if (!Regex("""\bTriState\b""").containsMatchIn(withoutTriStateImport)) {
        result = withoutTriStateImport
    }
    return result
}

internal fun StructuralRefactorPass.mobEffectBooleanMethodNeedsTrailingTrue(body: String): Boolean {
    val trimmed = body.trimEnd()
    if (trimmed.isBlank()) return true
    if (!Regex("""\b(?:return|throw)\b""").containsMatchIn(maskJavaCommentsAndLiterals(trimmed))) return true
    if (Regex("""(?s)\b(?:return|throw)\b[^;]*;\s*$""").containsMatchIn(trimmed)) return false
    if (Regex("""(?s)\belse\s*(?:\{[^{}]*\}|\b(?:return|throw)\b[^;]*;)\s*$""").containsMatchIn(trimmed)) return false
    return true
}

internal fun StructuralRefactorPass.migrateLegacyLivingChangeTargetEventSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("LivingChangeTargetEvent") ||
        !executableCode.contains("NewTarget(")
    ) {
        return source
    }
    var result = source
    result = replaceExecutableRegex(result, Regex("""\.getNewTarget\s*\(""")) { ".getNewAboutToBeSetTarget(" }
    result = replaceExecutableRegex(result, Regex("""\.setNewTarget\s*\(""")) { ".setNewAboutToBeSetTarget(" }
    return result
}

internal fun StructuralRefactorPass.migrateFriendlyByteBufReadAnySizeNbtSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".readAnySizeNbt(")) return source
    return replaceExecutableRegex(source, Regex("""\.readAnySizeNbt\s*\(""")) { ".readNbt(" }
}

internal fun StructuralRefactorPass.migrateLegacyNetworkBufferCodecsSource(source: String): String {
    if (!source.contains("readComponent()") &&
        !source.contains("writeComponent(") &&
        !source.contains("readItem()") &&
        !source.contains("writeItem(")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var needsComponentSerialization = false
    var needsRegistryFriendlyByteBuf = false

    result = Regex("""\b($id)\.readComponent\(\)""").replace(result) { match ->
        needsComponentSerialization = true
        needsRegistryFriendlyByteBuf = true
        "ComponentSerialization.STREAM_CODEC.decode(${match.groupValues[1]})"
    }
    result = rewriteJavaCall(result, "writeComponent") { receiver, args ->
        if (args.size != 1) {
            null
        } else {
            needsComponentSerialization = true
            needsRegistryFriendlyByteBuf = true
            "ComponentSerialization.STREAM_CODEC.encode($receiver, ${args[0].trim()})"
        }
    }
    result = Regex("""\b($id)\.readItem\(\)""").replace(result) { match ->
        needsRegistryFriendlyByteBuf = true
        // FriendlyByteBuf#readItem/writeItem carried empty stacks (javap of 1.20.1: writeItemStack
        // writes a false flag for an empty stack), which only the optional codec accepts
        "ItemStack.OPTIONAL_STREAM_CODEC.decode(${match.groupValues[1]})"
    }
    result = rewriteJavaCall(result, "writeItem") { receiver, args ->
        if (args.size != 1) {
            null
        } else {
            needsRegistryFriendlyByteBuf = true
            "ItemStack.OPTIONAL_STREAM_CODEC.encode($receiver, ${args[0].trim()})"
        }
    }

    if (needsRegistryFriendlyByteBuf) {
        result = result
            .replace("StreamCodec<FriendlyByteBuf,", "StreamCodec<RegistryFriendlyByteBuf,")
            .replace(Regex("""\bFriendlyByteBuf\s+($id)\b"""), "RegistryFriendlyByteBuf $1")
        result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
        val withoutFriendly = removeImport(result, "net.minecraft.network.FriendlyByteBuf")
        if (!Regex("""\bFriendlyByteBuf\b""").containsMatchIn(withoutFriendly)) {
            result = withoutFriendly
        }
    }
    if (needsComponentSerialization) {
        result = addImportIfMissing(result, "net.minecraft.network.chat.ComponentSerialization")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyMapItemApis(source: String): String {
    if (!source.contains("MapItem") &&
        !source.contains("MapItemSavedData") &&
        !source.contains("ClientboundMapItemDataPacket") &&
        !source.contains("getFreeMapId()") &&
        !source.contains("getMapId(")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var needsMapId = false
    var needsDataComponents = false
    var needsHolderLookup = false
    var needsSavedData = false
    var needsRegistryFriendlyByteBuf = false

    result = Regex("""\b([A-Za-z_$][\w$]*)\.getLevelData\(\)\.getXSpawn\(\)""")
        .replace(result, "$1.getSharedSpawnPos().getX()")
    result = Regex("""\b([A-Za-z_$][\w$]*)\.getLevelData\(\)\.getYSpawn\(\)""")
        .replace(result, "$1.getSharedSpawnPos().getY()")
    result = Regex("""\b([A-Za-z_$][\w$]*)\.getLevelData\(\)\.getZSpawn\(\)""")
        .replace(result, "$1.getSharedSpawnPos().getZ()")
    if (result.contains("extends MapItem") || result.contains(" getFreeMapId()") || result.contains("getMapId(")) {
        result = Regex("""\bInteger\s+($id)\s*=\s*getMapId\(\s*($id)\s*\)\s*;""")
            .replace(result) { match ->
                needsMapId = true
                needsDataComponents = true
                "MapId ${match.groupValues[1]} = ${match.groupValues[2]}.get(DataComponents.MAP_ID);"
            }
        result = Regex("""\bint\s+($id)\s*=\s*([A-Za-z_$][\w$]*)\.getFreeMapId\(\)\s*;""")
            .replace(result) { match ->
                needsMapId = true
                "MapId ${match.groupValues[1]} = ${match.groupValues[2]}.getFreeMapId();"
            }
        result = Regex("""public\s+static\s+String\s+getMapName\s*\(\s*int\s+($id)\s*\)""")
            .replace(result) { match ->
                needsMapId = true
                "public static String getMapName(MapId ${match.groupValues[1]})"
            }
        result = Regex("""return\s+STR_ID\s*\+\s*"_[^"]*"\s*\+\s*($id)\s*;""")
            .replace(result) { match ->
                val variable = match.groupValues[1]
                if (Regex("""\bgetMapName\s*\(\s*MapId\s+${Regex.escape(variable)}\s*\)""").containsMatchIn(result)) {
                    "return STR_ID + \"_\" + $variable.id();"
                } else {
                    match.value
                }
            }
        result = Regex(
            """(?m)^([ \t]*)($id)\.getOrDefault\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\)\.copyTag\(\)\.putInt\(\s*"map"\s*,\s*($id)\s*\);\s*$"""
        ).replace(result) { match ->
            needsDataComponents = true
            "${match.groupValues[1]}${match.groupValues[2]}.set(DataComponents.MAP_ID, ${match.groupValues[3]});"
        }

        val hoverText = javaDeclaredMethodText(result, "appendHoverText")
        if (hoverText != null) {
            val contextVariable = Regex("""\b(?:Item\.)?TooltipContext\s+($id)\b""")
                .find(hoverText)
                ?.groupValues
                ?.get(1)
            if (contextVariable != null && hoverText.contains("getData(") && hoverText.contains(", $contextVariable")) {
                val levelVariable = uniqueLocalName(hoverText, "tooltipLevel")
                var migratedMethod = hoverText
                migratedMethod = Regex(
                    """\b${Regex.escape(contextVariable)}\s*==\s*null\s*\?\s*null\s*:\s*getData\(\s*([^,\r\n]+)\s*,\s*${Regex.escape(contextVariable)}\s*\)"""
                ).replace(migratedMethod) { match ->
                    "$levelVariable == null ? null : getData(${match.groupValues[1].trim()}, $levelVariable)"
                }
                migratedMethod = Regex("""\bgetData\(\s*([^,\r\n]+)\s*,\s*${Regex.escape(contextVariable)}\s*\)""")
                    .replace(migratedMethod) { match ->
                        "getData(${match.groupValues[1].trim()}, $levelVariable)"
                    }
                val localLine = "Level $levelVariable = $contextVariable != null ? $contextVariable.level() : null;"
                if (!migratedMethod.contains(localLine)) {
                    val openBrace = migratedMethod.indexOf('{')
                    val insertAfter = Regex("""(?m)^([ \t]*)super\.appendHoverText\([^;\r\n]*;\s*$""").find(migratedMethod)
                    migratedMethod = if (insertAfter != null) {
                        val insertAt = insertAfter.range.last + 1
                        val indent = insertAfter.groupValues[1]
                        migratedMethod.substring(0, insertAt) + "\n${indent}$localLine" + migratedMethod.substring(insertAt)
                    } else if (openBrace >= 0) {
                        migratedMethod.substring(0, openBrace + 1) + "\n\t\t$localLine" + migratedMethod.substring(openBrace + 1)
                    } else {
                        migratedMethod
                    }
                }
                result = result.replace(hoverText, migratedMethod)
            }
        }
    }

    if (result.contains("extends MapItemSavedData")) {
        result = Regex("""public\s+static\s+($id)\s+load\s*\(\s*CompoundTag\s+($id)\s*\)""")
            .replace(result) { match ->
                needsHolderLookup = true
                "public static ${match.groupValues[1]} load(CompoundTag ${match.groupValues[2]}, HolderLookup.Provider registries)"
            }
        result = Regex("""MapItemSavedData\.load\(\s*($id)\s*\)""")
            .replace(result) { match ->
                needsHolderLookup = true
                "MapItemSavedData.load(${match.groupValues[1]}, registries)"
            }
        result = Regex("""public\s+CompoundTag\s+save\s*\(\s*CompoundTag\s+($id)\s*\)""")
            .replace(result) { match ->
                needsHolderLookup = true
                "public CompoundTag save(CompoundTag ${match.groupValues[1]}, HolderLookup.Provider registries)"
            }
        result = Regex("""super\.save\(\s*($id)\s*\)""")
            .replace(result) { match ->
                needsHolderLookup = true
                "super.save(${match.groupValues[1]}, registries)"
            }
        result = Regex("""\.get\(\s*($id)::load\s*,\s*([^)\r\n]+)\)""")
            .replace(result) { match ->
                needsSavedData = true
                ".get(new SavedData.Factory<>(() -> null, ${match.groupValues[1]}::load), ${match.groupValues[2].trim()})"
            }
        result = Regex("""getUpdatePacket\s*\(\s*int\s+($id)\s*,\s*Player\s+($id)\s*\)""")
            .replace(result) { match ->
                needsMapId = true
                "getUpdatePacket(MapId ${match.groupValues[1]}, Player ${match.groupValues[2]})"
            }
    }

    if (result.contains("ClientboundMapItemDataPacket")) {
        result = result
            .replace("new ClientboundMapItemDataPacket(buf)", "ClientboundMapItemDataPacket.STREAM_CODEC.decode(buf)")
            .replace("this.inner.write(buf);", "ClientboundMapItemDataPacket.STREAM_CODEC.encode(buf, this.inner);")
            .replace(".getMapId()", ".mapId()")
            .replace(".getScale()", ".scale()")
            .replace(".isLocked()", ".locked()")
        if (result.contains("ClientboundMapItemDataPacket.STREAM_CODEC")) {
            result = result
                .replace("StreamCodec<FriendlyByteBuf,", "StreamCodec<RegistryFriendlyByteBuf,")
                .replace("public ${classNameOfJavaSource(result) ?: ""}(FriendlyByteBuf buf)", "public ${classNameOfJavaSource(result) ?: ""}(RegistryFriendlyByteBuf buf)")
                .replace("public void encode(FriendlyByteBuf buf)", "public void encode(RegistryFriendlyByteBuf buf)")
            needsRegistryFriendlyByteBuf = true
        }
    }

    if (needsMapId || result.contains("MapId ")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.saveddata.maps.MapId")
    }
    if (needsDataComponents) {
        result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    }
    if (needsHolderLookup) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    if (needsSavedData) {
        result = addImportIfMissing(result, "net.minecraft.world.level.saveddata.SavedData")
    }
    if (needsRegistryFriendlyByteBuf) {
        result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
        val withoutFriendly = removeImport(result, "net.minecraft.network.FriendlyByteBuf")
        if (!Regex("""\bFriendlyByteBuf\b""").containsMatchIn(withoutFriendly)) {
            result = withoutFriendly
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyLootTableKeyFields(source: String): String {
    if (!source.contains("ResourceKey.create(Registries.LOOT_TABLE") ||
        !source.contains("ResourceLocation")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    val lootTableFields = Regex("""this\.($id)\s*=\s*ResourceKey\.create\(\s*Registries\.LOOT_TABLE\s*,""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (lootTableFields.isEmpty()) return source

    var result = source
    lootTableFields.forEach { field ->
        result = Regex("""\b((?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?)ResourceLocation(\s+${Regex.escape(field)}\s*[;=])""")
            .replace(result, "$1ResourceKey<LootTable>$2")
    }

    if (result != source) {
        result = addImportIfMissing(result, "net.minecraft.resources.ResourceKey")
        result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
        result = addImportIfMissing(result, "net.minecraft.world.level.storage.loot.LootTable")
        val withoutResourceLocationImport = removeImport(result, "net.minecraft.resources.ResourceLocation")
        if (!Regex("""\bResourceLocation\b""").containsMatchIn(withoutResourceLocationImport)) {
            result = withoutResourceLocationImport
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLootTableResourceKeyReferenceCreates(
    source: String,
    resourceKeyLootTableOwners: Set<String>,
    resourceKeyLootTableFields: Set<String> = emptySet()
): String {
    val hasDefaultLootTableWrapper = source.contains(".getDefaultLootTable()")
    if ((resourceKeyLootTableOwners.isEmpty() && resourceKeyLootTableFields.isEmpty() && !hasDefaultLootTableWrapper) ||
        !source.contains("ResourceKey.create(Registries.LOOT_TABLE") ||
        (!source.contains(".lootTable") &&
            !hasDefaultLootTableWrapper &&
            resourceKeyLootTableFields.none { source.contains(it.substringAfterLast('.', "")) })) {
        return source
    }

    var result = source
    result = Regex(
        """ResourceKey\.create\(\s*Registries\.LOOT_TABLE\s*,\s*((?:[^()]|\([^()]*\))+?\.getDefaultLootTable\(\))\s*\)"""
    ).replace(result, "$1")

    if (resourceKeyLootTableFields.isNotEmpty()) {
        val fieldPattern = resourceKeyLootTableFields.joinToString("|") { Regex.escape(it) }
        result = Regex(
            """ResourceKey\.create\(\s*Registries\.LOOT_TABLE\s*,\s*($fieldPattern)\s*\)"""
        ).replace(result, "$1")
    }

    if (resourceKeyLootTableOwners.isEmpty()) return result
    val ownerPattern = resourceKeyLootTableOwners.joinToString("|") { Regex.escape(it) }
    result = Regex(
        """ResourceKey\.create\(\s*Registries\.LOOT_TABLE\s*,\s*(($ownerPattern)(?:\.[A-Za-z_$][\w$]*)+\.lootTable)\s*\)"""
    ).replace(result, "$1")

    val id = """[A-Za-z_$][\w$]*"""
    val ownerVariables = Regex("""\b(?:final\s+)?(?:$ownerPattern)\s+($id)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    if (ownerVariables.isNotEmpty()) {
        val variablePattern = ownerVariables.joinToString("|") { Regex.escape(it) }
        result = Regex(
            """ResourceKey\.create\(\s*Registries\.LOOT_TABLE\s*,\s*(($variablePattern)\.lootTable)\s*\)"""
        ).replace(result, "$1")
    }
    return result
}

internal fun StructuralRefactorPass.migrateClientLevelEntityInsertionCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".putNonPlayerEntity(")) return source
    return rewriteExecutableJavaCall(source, "putNonPlayerEntity") { receiver, args ->
        if (receiver.isBlank() || args.size != 2) {
            null
        } else {
            "$receiver.addEntity(${args[1].trim()})"
        }
    }
}

internal fun StructuralRefactorPass.migrateLegacyResourceLocationValidationSource(source: String): String {
    if (!source.contains("ResourceLocation.isValidResourceLocation(")) return source
    var result = Regex("""!\s*ResourceLocation\.isValidResourceLocation\(\s*([^()\r\n]+(?:\([^()\r\n]*\))?)\s*\)""")
        .replace(source) { match ->
            "ResourceLocation.tryParse(${match.groupValues[1].trim()}) == null"
        }
    result = Regex("""ResourceLocation\.isValidResourceLocation\(\s*([^()\r\n]+(?:\([^()\r\n]*\))?)\s*\)""").replace(result) { match ->
        "ResourceLocation.tryParse(${match.groupValues[1].trim()}) != null"
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyResourceLocationConstructorsSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new ResourceLocation(") &&
        !executableCode.contains("new net.minecraft.resources.ResourceLocation(")) {
        return source
    }

    var result = source
    result = rewriteExecutableJavaNew(result, "ResourceLocation") { args ->
        when (args.size) {
            1 -> "ResourceLocation.parse(${args[0].trim()})"
            2 -> "ResourceLocation.fromNamespaceAndPath(${args[0].trim()}, ${args[1].trim()})"
            else -> null
        }
    }
    result = rewriteExecutableJavaNew(result, "net.minecraft.resources.ResourceLocation") { args ->
        when (args.size) {
            1 -> "net.minecraft.resources.ResourceLocation.parse(${args[0].trim()})"
            2 -> "net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(${args[0].trim()}, ${args[1].trim()})"
            else -> null
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyHolderMethodReferences(source: String): String {
    if (!source.contains("Holder::get")) return source
    return source.replace("Holder::get", "Holder::value")
}

internal fun StructuralRefactorPass.migrateLegacyHolderValueCalls(source: String): String {
    if (!source.contains(".get()")) return source
    var result = source.replace(".generatorSettings().get()", ".generatorSettings().value()")
    if (result.contains("forMusic(") ||
        result.contains("forBossMusic(") ||
        result.contains("net.minecraft.sounds.Music") ||
        Regex("""\bMusic\s+[A-Za-z_$][\w$]*""").containsMatchIn(result)) {
        result = result.replace(".getEvent().get()", ".getEvent().value()")
    }
    result = Regex("""\b((?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.(?:EMPTY_TYPE|WATER_TYPE|LAVA_TYPE))\.get\(\)""")
        .replace(result) { match -> "${match.groupValues[1]}.value()" }
    val holderVariables = Regex(
        """\b(?:final\s+)?(?:net\.minecraft\.core\.)?Holder(?:\.Reference)?\s*<[^;=()]+>\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    holderVariables.forEach { variable ->
        result = Regex("""\b${Regex.escape(variable)}\.get\(\)""")
            .replace(result, "$variable.value()")
    }
    return result
}

internal fun StructuralRefactorPass.migrateNeoForgeFluidTypeValueContexts(source: String): String {
    if (!source.contains("NeoForgeMod.") || !source.contains("_TYPE")) return source
    val fluidTypeConstant =
        """((?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.(?:EMPTY_TYPE|WATER_TYPE|LAVA_TYPE|MILK_TYPE))"""

    fun fluidTypeValueExpression(expression: String): String? {
        val trimmed = expression.trim()
        if (Regex("""^$fluidTypeConstant(?:\.(?:get|value)\(\))?$""").matches(trimmed)) {
            val constant = Regex(fluidTypeConstant).find(trimmed)?.value ?: return null
            return "$constant.value()"
        }
        return null
    }

    var result = replaceExecutableRegex(
        source,
        Regex("""\b((?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.(?:EMPTY_TYPE|WATER_TYPE|LAVA_TYPE))\.get\(\)""")
    ) { match -> "${match.groupValues[1]}.value()" }
    result = rewriteExecutableJavaInvocationArguments(result, "FluidInteractionRegistry.addInteraction") { args ->
        if (args.isNotEmpty()) {
            val migrated = fluidTypeValueExpression(args[0])
            if (migrated != null) args.toMutableList().also { it[0] = migrated } else null
        } else {
            null
        }
    }
    result = rewriteExecutableJavaInvocationArguments(result, "isEyeInFluidType") { args ->
        if (args.size == 1) {
            val migrated = fluidTypeValueExpression(args[0])
            if (migrated != null) listOf(migrated) else null
        } else {
            null
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(?<![\w$.])$fluidTypeConstant(?!\s*\.)\s*(==|!=)""")
    ) { match -> "${match.groupValues[1]}.value() ${match.groupValues[2]}" }
    result = replaceExecutableRegex(
        result,
        Regex("""(==|!=)\s*$fluidTypeConstant(?!\s*\.)""")
    ) { match -> "${match.groupValues[1]} ${match.groupValues[2]}.value()" }
    result = replaceExecutableRegex(
        result,
        Regex("""(?<![\w$.])((?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.(?:EMPTY_TYPE|WATER_TYPE|LAVA_TYPE))(?!\s*\.)""")
    ) { match -> "${match.groupValues[1]}.value()" }
    result = replaceExecutableRegex(
        result,
        Regex("""(?<![\w$.])((?:net\.neoforged\.neoforge\.common\.)?NeoForgeMod\.MILK_TYPE)(?!\s*\.)""")
    ) { match -> "${match.groupValues[1]}.get()" }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyHolderReferenceBindKeyCalls(source: String): String {
    if (!source.contains(".bindKey(") || !source.contains(".getOrThrow(")) return source
    val keyedReferences = Regex(
        """\b(?:final\s+)?(?:Holder\.Reference|net\.minecraft\.core\.Holder\.Reference)\s*<[^;=()]+>\s+([A-Za-z_$][\w$]*)\s*=\s*[^;\r\n]+\.getOrThrow\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\)\s*;"""
    ).findAll(source)
        .associate { it.groupValues[1] to it.groupValues[2] }
    if (keyedReferences.isEmpty()) return source
    var result = source
    keyedReferences.forEach { (holder, key) ->
        result = Regex("""(?m)^[ \t]*${Regex.escape(holder)}\.bindKey\(\s*${Regex.escape(key)}\s*\)\s*;\s*\r?\n""")
            .replace(result, "")
    }
    return result
}

internal fun StructuralRefactorPass.migrateBuiltInRegistryEntrySetSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("BuiltInRegistries.") || !executableCode.contains(".getEntries()")) return source
    return replaceExecutableRegex(
        source,
        Regex("""\b(BuiltInRegistries\.[A-Z0-9_]+)\.getEntries\(\)""")
    ) { match -> "${match.groupValues[1]}.entrySet()" }
}

internal fun StructuralRefactorPass.migrateBuiltInRegistryTagRandomElementSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("BuiltInRegistries.ITEM.tags().getTag(") ||
        !executableCode.contains(".getRandomElement(")) {
        return source
    }
    return rewriteExecutableJavaCall(source, "orElse") { receiver, args ->
        if (args.size != 1) return@rewriteExecutableJavaCall null
        val randomToken = ".getRandomElement("
        val randomIndex = receiver.lastIndexOf(randomToken)
        if (randomIndex < 0) return@rewriteExecutableJavaCall null
        val randomOpen = randomIndex + ".getRandomElement".length
        val randomClose = findMatchingParen(receiver, randomOpen)
        if (randomClose != receiver.length - 1) return@rewriteExecutableJavaCall null
        val randomArgs = splitTopLevelJavaArgs(receiver.substring(randomOpen + 1, randomClose))
        if (randomArgs.size != 1) return@rewriteExecutableJavaCall null

        val tagReceiver = receiver.substring(0, randomIndex)
        val tagPrefix = "BuiltInRegistries.ITEM.tags().getTag("
        if (!tagReceiver.startsWith(tagPrefix)) return@rewriteExecutableJavaCall null
        val tagOpen = tagReceiver.indexOf("getTag(") + "getTag".length
        val tagClose = findMatchingParen(tagReceiver, tagOpen)
        if (tagClose != tagReceiver.length - 1) return@rewriteExecutableJavaCall null
        val tagArgs = splitTopLevelJavaArgs(tagReceiver.substring(tagOpen + 1, tagClose))
        if (tagArgs.size != 1) return@rewriteExecutableJavaCall null

        "BuiltInRegistries.ITEM.getRandomElementOf(${tagArgs[0].trim()}, ${randomArgs[0].trim()}).map(holder -> holder.value()).orElse(${args[0].trim()})"
    }
}

internal fun StructuralRefactorPass.migrateLegacyVillagerDataAccessors(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("VillagerData") || !executableCode.contains(".level()")) return source
    val declaredVariables = Regex("""\bVillagerData\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (declaredVariables.isNotEmpty()) {
        var directResult = source
        for (variable in declaredVariables) {
            directResult = replaceExecutableRegex(
                directResult,
                Regex("""\b${Regex.escape(variable)}\.level\(\)""")
            ) { "$variable.getLevel()" }
        }
        if (directResult != source) return directResult
    }

    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        val methodText = result.substring(method.range)
        val executableMethodText = maskJavaCommentsAndLiterals(methodText)
        if (!executableMethodText.contains(".level()")) continue
        val villagerDataVariables = (
            javaMethodParameters(methodText)
                .filter { simpleJavaTypeName(it.type) == "VillagerData" }
                .map { it.name } +
                Regex("""\bVillagerData\s+([A-Za-z_$][\w$]*)\b""")
                    .findAll(executableMethodText)
                    .map { it.groupValues[1] }
            ).toSet()
        if (villagerDataVariables.isEmpty()) continue
        var migratedMethod = methodText
        for (variable in villagerDataVariables) {
            migratedMethod = replaceExecutableRegex(
                migratedMethod,
                Regex("""\b${Regex.escape(variable)}\.level\(\)""")
            ) { "$variable.getLevel()" }
        }
        if (migratedMethod != methodText) {
            result = result.replaceRange(method.range, migratedMethod)
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyItemConstants(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("Items.SCUTE")) return source
    return replaceExecutableRegex(source, Regex("""\bItems\.SCUTE\b""")) { "Items.TURTLE_SCUTE" }
}

internal fun StructuralRefactorPass.migrateLegacyOptionalValueCalls(source: String): String {
    if (!source.contains(".value()") || !source.contains("Optional<")) return source
    val optionalVariables = Regex("""\bOptional\s*<[^;=()]+?>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (optionalVariables.isEmpty()) return source
    var result = source
    optionalVariables.forEach { variable ->
        result = Regex("""\b${Regex.escape(variable)}\.value\(\)""")
            .replace(result, "$variable.get()")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCurativeItemEffectsSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".curePotionEffects(")) return source
    var changed = false
    val migrated = rewriteExecutableJavaCall(source, "curePotionEffects") { receiver, args ->
        if (args.size != 1) return@rewriteExecutableJavaCall null
        val cure = legacyCurativeItemEffectCure(args[0]) ?: return@rewriteExecutableJavaCall null
        changed = true
        "$receiver.removeEffectsCuredBy(EffectCures.$cure)"
    }
    return if (changed) {
        addImportIfMissing(migrated, "net.neoforged.neoforge.common.EffectCures")
    } else {
        source
    }
}

internal fun StructuralRefactorPass.legacyCurativeItemEffectCure(argument: String): String? {
    val itemStackPattern = Regex(
        """^\s*new\s+(?:net\.minecraft\.world\.item\.)?ItemStack\s*\(\s*(?:net\.minecraft\.world\.item\.)?Items\.([A-Z0-9_]+)\s*\)\s*$"""
    )
    return when (itemStackPattern.find(argument)?.groupValues?.get(1)) {
        "MILK_BUCKET" -> "MILK"
        "HONEY_BOTTLE" -> "HONEY"
        else -> null
    }
}

internal fun StructuralRefactorPass.migrateLegacyChunkGeneratorAsyncSignatures(source: String): String {
    if (!source.contains("fillFromNoise(") && !source.contains("createBiomes(")) return source
    var result = source
    var changed = false
    val createBiomesPattern = Regex(
        """createBiomes\s*\(\s*Executor\s+([A-Za-z_$][\w$]*)\s*,\s*RandomState\s+([A-Za-z_$][\w$]*)\s*,\s*Blender\s+([A-Za-z_$][\w$]*)\s*,\s*StructureManager\s+([A-Za-z_$][\w$]*)\s*,\s*ChunkAccess\s+([A-Za-z_$][\w$]*)\s*\)"""
    )
    result = createBiomesPattern.replace(result) { match ->
        changed = true
        "createBiomes(RandomState ${match.groupValues[2]}, Blender ${match.groupValues[3]}, StructureManager ${match.groupValues[4]}, ChunkAccess ${match.groupValues[5]})"
    }
    val fillFromNoiseSignaturePattern = Regex(
        """fillFromNoise\s*\(\s*Executor\s+([A-Za-z_$][\w$]*)\s*,\s*Blender\s+([A-Za-z_$][\w$]*)\s*,\s*RandomState\s+([A-Za-z_$][\w$]*)\s*,\s*StructureManager\s+([A-Za-z_$][\w$]*)\s*,\s*ChunkAccess\s+([A-Za-z_$][\w$]*)\s*\)"""
    )
    result = fillFromNoiseSignaturePattern.replace(result) { match ->
        changed = true
        "fillFromNoise(Blender ${match.groupValues[2]}, RandomState ${match.groupValues[3]}, StructureManager ${match.groupValues[4]}, ChunkAccess ${match.groupValues[5]})"
    }
    result = Regex("""\b(super|this\.delegate|delegate)\.fillFromNoise\(\s*[A-Za-z_$][\w$]*\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match ->
            changed = true
            "${match.groupValues[1]}.fillFromNoise(${match.groupValues[2]}, ${match.groupValues[3]}, ${match.groupValues[4]}, ${match.groupValues[5]})"
        }
    if (changed && result.contains(", executor)")) {
        result = result.replace(", executor)", ", Util.backgroundExecutor())")
    }
    if (changed) {
        val withoutExecutor = removeImport(result, "java.util.concurrent.Executor")
        if (!Regex("""\bExecutor\b""").containsMatchIn(withoutExecutor)) {
            result = withoutExecutor
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBlockStateSurvivalAndPlantCalls(source: String): String {
    if (!source.contains("canSurvive(") && !source.contains("canSustainPlant(")) return source
    var result = Regex(
        """((?:this\.)?[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.getBlock\(\)\.canSurvive\(\s*\1\s*,\s*([^,\r\n]+)\s*,\s*([^)]+)\s*\)"""
    ).replace(source) { match ->
        "${match.groupValues[1]}.canSurvive(${match.groupValues[2].trim()}, ${match.groupValues[3].trim()})"
    }
    result = rewriteJavaCall(result, "canSustainPlant") { receiver, args ->
        if (receiver.isBlank() || args.size != 5) return@rewriteJavaCall null
        val plant = args[4].trim()
        val migratedPlant = when {
            plant.contains(".defaultBlockState()") -> plant
            Regex("""(?:\.get\(\)|\bBlocks\.[A-Z0-9_]+)$""").containsMatchIn(plant) -> "$plant.defaultBlockState()"
            else -> return@rewriteJavaCall null
        }
        "$receiver.canSustainPlant(${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()}, ${args[3].trim()}, $migratedPlant)"
    }
    result = Regex("""!\s*([^;\r\n]+?\.canSustainPlant\((?:[^()]|\([^()]*\))*\))""")
        .replace(result) { match ->
            val expression = match.groupValues[1]
            if (expression.contains("&&") || expression.contains("||") || expression.contains(" instanceof ")) {
                match.value
            } else {
                "$expression.isFalse()"
            }
        }
    result = Regex("""\breturn\s+([^;\r\n!]+?\.canSustainPlant\((?:[^()]|\([^()]*\))*\))\s*;""")
        .replace(result) { match ->
            val expression = match.groupValues[1]
            if (expression.contains("&&") || expression.contains("||") || expression.contains(" instanceof ")) {
                match.value
            } else {
                "return !$expression.isFalse();"
            }
        }
    result = Regex("""return\s+!super\.canSustainPlant\(((?:[^()]|\([^()]*\))*)\)\.isFalse\(\);""")
        .replace(result) { match -> "return super.canSustainPlant(${match.groupValues[1]});" }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCropBlockPlantOverrides(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("extends CropBlock") || !executableCode.contains("getPlant(")) return source

    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        if (method.name != "getPlant") continue
        val classDeclaration = enclosingJavaClassDeclaration(result, method.range.first)
        if (!javaClassExtendsAny(classDeclaration, setOf("CropBlock"), javaInheritanceIndex)) continue
        val methodText = result.substring(method.range)
        val params = javaMethodParameters(methodText)
        if (params.size != 2 ||
            simpleJavaTypeName(params[0].type) != "BlockGetter" ||
            simpleJavaTypeName(params[1].type) != "BlockPos" ||
            !Regex("""\bBlockState\s+getPlant\s*\(""").containsMatchIn(method.header)) {
            continue
        }
        result = removeRangeWithTrailingLineBreak(result, method.range)
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyAuthlibProfileFetchSource(source: String): String {
    if (!source.contains("YggdrasilAuthenticationService") ||
        !source.contains("MinecraftProfilePropertiesResponse") ||
        !source.contains("performGetRequest(")) {
        return source
    }
    var result = source
    val oldStart = result.indexOf("URL url = HttpAuthenticationService.constantURL(")
    val oldEndMarker = "GAME_PROFILES.add(result);"
    val oldEndCall = result.indexOf(oldEndMarker, oldStart)
    if (oldStart >= 0 && oldEndCall >= 0) {
        var oldBlockEnd = result.indexOf('\n', oldEndCall + oldEndMarker.length).let { if (it < 0) oldEndCall + oldEndMarker.length else it + 1 }
        while (oldBlockEnd < result.length) {
            val nextLineEnd = result.indexOf('\n', oldBlockEnd).let { if (it < 0) result.length else it + 1 }
            val line = result.substring(oldBlockEnd, nextLineEnd).trim()
            if (line.isNotEmpty() && line != "}") break
            oldBlockEnd = nextLineEnd
        }
        val indent = result.substring(result.lastIndexOf('\n', oldStart).let { if (it < 0) 0 else it + 1 }, oldStart)
            .takeWhile { it == ' ' || it == '\t' }
        val replacement = """
${indent}var profileResult = service.createMinecraftSessionService().fetchProfile(uuid, false);
${indent}if (profileResult != null && profileResult.profile() != null) {
${indent}	GAME_PROFILES.add(profileResult.profile());
${indent}}
            """.trimIndent()
        result = result.substring(0, oldStart) + replacement + "\n" + result.substring(oldBlockEnd)
    }
    result = Regex("""(?m)^[ \t]*String\s+baseUrl\s*=\s*EnvironmentParser\.getEnvironmentFromProperties\(\).*?;\s*\r?\n""")
        .replace(result, "")
    result = Regex("""(?m)^[ \t]*boolean\s+requireSecure\s*=\s*false\s*;\s*\r?\n""")
        .replace(result, "")
    result = result.replace("catch (AuthenticationException | IOException e)", "catch (RuntimeException e)")
    listOf(
        "com.mojang.authlib.EnvironmentParser",
        "com.mojang.authlib.HttpAuthenticationService",
        "com.mojang.authlib.exceptions.*",
        "com.mojang.authlib.minecraft.client.ObjectMapper",
        "com.mojang.authlib.yggdrasil.YggdrasilEnvironment",
        "com.mojang.authlib.yggdrasil.response.MinecraftProfilePropertiesResponse",
        "org.apache.commons.lang3.StringUtils",
        "java.io.IOException",
        "java.net.URL"
    ).forEach { importName ->
        result = removeImport(result, importName)
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPaintingVariantAccessors(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("PaintingVariant")) return source
    val variables = Regex("""\bPaintingVariant\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    if (variables.isEmpty()) return source

    var result = source
    variables.forEach { variable ->
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(variable)}\.getWidth\(\)""")
        ) { "$variable.width()" }
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(variable)}\.getHeight\(\)""")
        ) { "$variable.height()" }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPaintingVariantRegistrySource(
    source: String,
    legacyRegistryBackedMethods: Set<LegacyRegistryBackedMethod> = emptySet()
): String {
    if (!source.contains("BuiltInRegistries.PAINTING_VARIANT") && !source.contains(".getPaintingOfSize(")) return source
    var result = source
    var changed = false

    result = Regex("""BuiltInRegistries\.PAINTING_VARIANT\.getHolder\(\s*([^)]+)\s*\)\.get\(\)""")
        .replace(result) { match ->
            val registryAccess = registryAccessExpressionForEnclosingMethod(result, match.range.first)
                ?: return@replace match.value
            if (registryAccess == "this.registryAccess()") {
                return@replace match.value
            }
                changed = true
                "$registryAccess.registryOrThrow(Registries.PAINTING_VARIANT).getHolderOrThrow(${match.groupValues[1].trim()})"
        }

    if (result.contains("BuiltInRegistries.PAINTING_VARIANT.tags().getTag(")) {
        result = result
            .replace(
                "getPaintingOfSize(RandomSource rand, int minSize)",
                "getPaintingOfSize(RegistryAccess registryAccess, RandomSource rand, int minSize)"
            )
            .replace(
                "getPaintingOfSize(RandomSource rand, int width, int height, boolean exactMeasurements)",
                "getPaintingOfSize(RegistryAccess registryAccess, RandomSource rand, int width, int height, boolean exactMeasurements)"
            )
            .replace(
                "return getPaintingOfSize(rand, minSize, minSize, false);",
                "return getPaintingOfSize(registryAccess, rand, minSize, minSize, false);"
            )
        result = Regex(
            """for\s*\(\s*PaintingVariant\s+([A-Za-z_$][\w$]*)\s*:\s*BuiltInRegistries\.PAINTING_VARIANT\.tags\(\)\.getTag\(\s*([^)]+)\s*\)\s*\)\s*\{"""
        ).replace(result) { match ->
            changed = true
            val valueVar = match.groupValues[1]
            val tag = match.groupValues[2].trim()
            "for (Holder<PaintingVariant> ${valueVar}Holder : registryAccess.registryOrThrow(Registries.PAINTING_VARIANT).getTagOrEmpty($tag)) {\n\t\t\tPaintingVariant $valueVar = ${valueVar}Holder.value();"
        }
        result = Regex(
            """ResourceKey\.create\(\s*Registries\.PAINTING_VARIANT\s*,\s*Objects\.requireNonNull\(\s*BuiltInRegistries\.PAINTING_VARIANT\.getKey\(\s*([A-Za-z_$][\w$]*)\s*\)\s*\)\s*\)"""
        ).replace(result) { match ->
            changed = true
            "${match.groupValues[1]}Holder.unwrapKey().orElseThrow()"
        }
    }

    if (result.contains(".getPaintingOfSize(")) {
        result = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.getPaintingOfSize\(""")
            .replace(result) { match ->
                if (!isKnownRegistryBackedMethodCall(result, match.groupValues[1], "getPaintingOfSize", legacyRegistryBackedMethods)) {
                    return@replace match.value
                }
                val callRegistryAccess = registryAccessExpressionForEnclosingMethod(result, match.range.first)
                    ?: return@replace match.value
                changed = true
                "${match.groupValues[1]}.getPaintingOfSize($callRegistryAccess, "
            }
    }

    if (!changed) return source
    if (result.contains("Holder<PaintingVariant>")) {
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
    }
    if (result.contains("RegistryAccess")) {
        result = addImportIfMissing(result, "net.minecraft.core.RegistryAccess")
    }
    val withoutBuiltIn = removeImport(result, "net.minecraft.core.registries.BuiltInRegistries")
    if (!Regex("""\bBuiltInRegistries\b""").containsMatchIn(withoutBuiltIn)) {
        result = withoutBuiltIn
    }
    val withoutObjects = removeImport(result, "java.util.Objects")
    if (!Regex("""\bObjects\b""").containsMatchIn(withoutObjects)) {
        result = withoutObjects
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBiomeSourceCodecReturnSource(source: String): String {
    if (!source.contains("extends BiomeSource") || !source.contains(" codec()")) return source
    var changed = false
    var result = Regex(
        """protected\s+Codec\s*<\s*([A-Za-z_$][\w$]*)\s*>\s+codec\s*\(\s*\)"""
    ).replace(source) { match ->
        changed = true
        "protected MapCodec<${match.groupValues[1]}> codec()"
    }
    val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+BiomeSource\b""")
        .find(result)
        ?.groupValues
        ?.get(1)
    if (className != null) {
        val withoutBiomeSourceWitness = Regex("""RecordCodecBuilder\s*\.\s*<\s*${Regex.escape(className)}\s*>\s*mapCodec\(""")
            .replace(result, "RecordCodecBuilder.mapCodec(")
        if (withoutBiomeSourceWitness != result) {
            result = withoutBiomeSourceWitness
            changed = true
        }
    }
    if (!changed) return source
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    val withoutCodec = removeImport(result, "com.mojang.serialization.Codec")
    if (!Regex("""\bCodec\b""").containsMatchIn(withoutCodec)) {
        result = withoutCodec
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyAttachmentGetDataLazyOptionalReturns(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!Regex("""\bLazyOptional\s*<""").containsMatchIn(executableCode) ||
        !executableCode.contains(".getData(")) {
        return source
    }
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private)\s+)?static\s+LazyOptional\s*<[^>\r\n]+>\s+[A-Za-z_$][\w$]*\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    val returnPattern = Regex("""return\s+([^;\r\n{}]+?\.getData\([^;\r\n{}]+?\))\s*;""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    methodPattern.findAll(executableCode).forEach methodLoop@{ method ->
        val openBrace = executableCode.indexOf('{', method.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (closeBrace <= openBrace) return@methodLoop
        val methodBody = executableCode.substring(openBrace + 1, closeBrace)
        returnPattern.findAll(methodBody).forEach returnLoop@{ returnMatch ->
            val expressionRange = returnMatch.groups[1]?.range ?: return@returnLoop
            val expressionStart = openBrace + 1 + expressionRange.first
            val expressionEnd = openBrace + 1 + expressionRange.last
            val expression = source.substring(expressionStart, expressionEnd + 1).trim()
            if (expression.contains("LazyOptional.") || expression.contains("ofNullable(")) {
                return@returnLoop
            }
            val returnStart = openBrace + 1 + returnMatch.range.first
            val returnEnd = openBrace + 1 + returnMatch.range.last
            edits += returnStart..returnEnd to "return LazyOptional.ofNullable($expression);"
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateAttachmentGetDataIfPresentSource(source: String): String {
    val initialExecutableCode = maskJavaCommentsAndLiterals(source)
    if (!initialExecutableCode.contains(".getData(") || !initialExecutableCode.contains(".ifPresent(")) return source
    val receiverPattern = Regex("""((?:this|super|[A-Za-z_$][\w$]*)(?:\.[A-Za-z_$][\w$]*(?:\([^;\r\n{}]*\))?)*)\.getData\s*\(""")
    var result = source
    var cursor = 0
    var changed = false
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val match = receiverPattern.find(executableCode, cursor) ?: break
        val receiverRange = match.groups[1]?.range
        if (receiverRange == null) {
            cursor = match.range.last + 1
            continue
        }
        val receiver = result.substring(receiverRange.first, receiverRange.last + 1)
        val dataOpen = executableCode.indexOf('(', match.range.last - 1)
        val dataClose = if (dataOpen >= 0) findMatchingParen(executableCode, dataOpen) else -1
        if (dataClose < 0) {
            cursor = match.range.last + 1
            continue
        }
        var afterData = dataClose + 1
        while (afterData < executableCode.length && executableCode[afterData].isWhitespace()) afterData++
        if (!executableCode.startsWith(".ifPresent", afterData)) {
            cursor = dataClose + 1
            continue
        }
        val ifPresentOpen = executableCode.indexOf('(', afterData)
        val ifPresentClose = if (ifPresentOpen >= 0) findMatchingParen(executableCode, ifPresentOpen) else -1
        if (ifPresentClose < 0) {
            cursor = afterData + 1
            continue
        }
        var statementEnd = ifPresentClose + 1
        while (statementEnd < executableCode.length && executableCode[statementEnd].isWhitespace()) statementEnd++
        if (statementEnd >= executableCode.length || executableCode[statementEnd] != ';') {
            cursor = ifPresentClose + 1
            continue
        }

        val lambda = result.substring(ifPresentOpen + 1, ifPresentClose)
        val lambdaCode = executableCode.substring(ifPresentOpen + 1, ifPresentClose)
        val lambdaMatch = Regex("""^\s*\(?\s*([A-Za-z_$][\w$]*)\s*\)?\s*->\s*\{""").find(lambdaCode)
        if (lambdaMatch == null) {
            cursor = ifPresentClose + 1
            continue
        }
        val lambdaBrace = lambdaCode.indexOf('{', lambdaMatch.range.last)
        val lambdaBraceClose = if (lambdaBrace >= 0) findMatchingBrace(lambdaCode, lambdaBrace) else -1
        if (lambdaBrace < 0 || lambdaBraceClose < 0) {
            cursor = ifPresentClose + 1
            continue
        }
        val attachmentKey = result.substring(dataOpen + 1, dataClose).trim()
        val variable = lambdaMatch.groupValues[1]
        val body = lambda.substring(lambdaBrace + 1, lambdaBraceClose)
        val lineStart = result.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        val indent = result.substring(lineStart, match.range.first).takeWhile { it == ' ' || it == '\t' }
        val replacement = buildString {
            append("${indent}if ($receiver.hasData($attachmentKey)) {")
            append("\n${indent}    var $variable = $receiver.getData($attachmentKey);")
            append(body)
            if (!body.endsWith("\n") && !body.endsWith("\r\n")) append('\n')
            append("$indent}")
        }
        result = result.substring(0, lineStart) + replacement + result.substring(statementEnd + 1)
        cursor = lineStart + replacement.length
        changed = true
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateAttachmentGetDataResolveOptionalBranches(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getData(") ||
        !executableCode.contains(".resolve()") ||
        !executableCode.contains(".isPresent()")
    ) return source

    val id = """[A-Za-z_$][\w$]*"""
    val declarationPattern = Regex(
        """(?m)^([ \t]*)(?:java\.util\.)?Optional\s*<\s*([^>\r\n]+?)\s*>\s+($id)\s*=\s*((?:[^;\r\n{}()]|\([^;\r\n{}()]*\))*?\.getData\s*\((?:[^;\r\n{}()]|\([^;\r\n{}()]*\))*\))\.resolve\(\)\s*;\s*"""
    )
    val edits = mutableListOf<Pair<IntRange, String>>()
    declarationPattern.findAll(executableCode).forEach { declaration ->
        val indent = declaration.groupValues[1]
        val valueType = declaration.groupValues[2].trim()
        val variable = declaration.groupValues[3]
        val dataCallRange = declaration.groups[4]?.range ?: return@forEach
        val dataCall = source.substring(dataCallRange.first, dataCallRange.last + 1).trim()
        val getDataIndex = dataCall.lastIndexOf(".getData")
        if (getDataIndex <= 0) return@forEach
        val receiver = dataCall.substring(0, getDataIndex).trim()
        val dataOpen = dataCall.indexOf('(', getDataIndex)
        val dataClose = if (dataOpen >= 0) findMatchingParen(dataCall, dataOpen) else -1
        if (dataClose <= dataOpen) return@forEach
        val attachmentKey = dataCall.substring(dataOpen + 1, dataClose).trim()

        var cursor = declaration.range.last + 1
        while (cursor < executableCode.length && executableCode[cursor].isWhitespace()) cursor++
        val ifPattern = Regex("""if\s*\(\s*${Regex.escape(variable)}\.isPresent\(\)\s*\)\s*\{""")
        val ifMatch = ifPattern.find(executableCode, cursor) ?: return@forEach
        if (ifMatch.range.first != cursor) return@forEach
        val ifOpenBrace = executableCode.indexOf('{', ifMatch.range.last - 1)
        val ifCloseBrace = if (ifOpenBrace >= 0) findMatchingBrace(executableCode, ifOpenBrace) else -1
        if (ifCloseBrace <= ifOpenBrace) return@forEach

        var replacementEnd = ifCloseBrace
        var elseText = ""
        var elseCursor = ifCloseBrace + 1
        while (elseCursor < executableCode.length && executableCode[elseCursor].isWhitespace()) elseCursor++
        if (executableCode.startsWith("else", elseCursor)) {
            val elseOpenBrace = executableCode.indexOf('{', elseCursor)
            val elseCloseBrace = if (elseOpenBrace >= 0) findMatchingBrace(executableCode, elseOpenBrace) else -1
            if (elseCloseBrace > elseOpenBrace) {
                elseText = source.substring(elseCursor, elseCloseBrace + 1)
                replacementEnd = elseCloseBrace
            }
        }

        var body = source.substring(ifOpenBrace + 1, ifCloseBrace)
        body = replaceExecutableRegex(
            body,
            Regex("""\b${Regex.escape(variable)}\.get\(\)\.""")
        ) { "$variable." }
        body = replaceExecutableRegex(
            body,
            Regex("""\b${Regex.escape(variable)}\.get\(\)""")
        ) { variable }

        val replacement = buildString {
            append("${indent}if ($receiver.hasData($attachmentKey)) {")
            append("\n${indent}    $valueType $variable = $dataCall;")
            append(body)
            append("\n$indent}")
            if (elseText.isNotBlank()) {
                append(" ")
                append(elseText)
            }
        }
        edits += declaration.range.first..replacementEnd to replacement
    }
    if (edits.isEmpty()) return source
    var result = applyStringEdits(source, edits)
    if (!Regex("""\bOptional\s*<""").containsMatchIn(maskJavaCommentsAndLiterals(result))) {
        result = removeImport(result, "java.util.Optional")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEntityCapabilityOptionalChains(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getCapability(") ||
        !executableCode.contains(".map(") ||
        !executableCode.contains(".orElse(")) {
        return source
    }
    val pattern = Regex(
        """(?ms)^([ \t]*)return\s+([^\r\n;]+?\.getCapability\((?:[^()]|\([^()]*\))*\))\.map\(\s*([A-Za-z_$][\w$]*)\s*->\s*\{(.*?)\}\s*\)\.orElse\(\s*([^;\r\n]+?)\s*\)\s*;"""
    )
    val edits = pattern.findAll(executableCode).mapNotNull { match ->
        val indent = match.groupValues[1]
        val capabilityCallRange = match.groups[2]?.range ?: return@mapNotNull null
        val capabilityCall = source.substring(capabilityCallRange.first, capabilityCallRange.last + 1).trim()
        val variable = match.groupValues[3]
        val bodyRange = match.groups[4]?.range ?: return@mapNotNull null
        val body = source.substring(bodyRange.first, bodyRange.last + 1).trim('\n', '\r')
        val fallbackRange = match.groups[5]?.range ?: return@mapNotNull null
        val fallback = source.substring(fallbackRange.first, fallbackRange.last + 1).trim()
        match.range to """
${indent}var $variable = $capabilityCall;
${indent}if ($variable == null) {
${indent}	return $fallback;
${indent}}
${indent}{
$body
${indent}}
            """.trimIndent()
    }.toList()
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migratePlayerCloneCapabilityLifecycleSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("reviveCaps()") &&
        !executableCode.contains("invalidateCapabilities()") &&
        !executableCode.contains("invalidateCaps()")) {
        return source
    }
    val copyEvidence = Regex("""(?:\.(?:copyFrom|copyToNewStore)\s*\(|::(?:copyFrom|copyToNewStore)\b)""")
    if (!copyEvidence.containsMatchIn(executableCode)) return source
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executableCode)) {
        val methodExecutable = executableCode.substring(method.range)
        if (!copyEvidence.containsMatchIn(methodExecutable)) continue
        val openBrace = executableCode.indexOf('{', method.range.first)
        if (openBrace < 0 || openBrace > method.range.last) continue
        val signature = methodExecutable.substring(0, openBrace - method.range.first)
        val playerParams = Regex("""\b(?:Player|ServerPlayer)\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(signature)
            .map { it.groupValues[1] }
            .toMutableList()
        val cloneEventParams = Regex("""\b(?:PlayerEvent\.Clone|net\.neoforged\.neoforge\.event\.entity\.player\.PlayerEvent\.Clone)\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(signature)
            .map { it.groupValues[1] }
            .toList()
        if (method.name != "clone" && cloneEventParams.isEmpty()) continue
        if (playerParams.size < 2 && cloneEventParams.isEmpty()) continue
        playerParams += cloneEventParams.map { "$it.getOriginal()" }
        playerParams.forEach { playerName ->
            val revivePattern = Regex("""(?m)^[ \t]*${Regex.escape(playerName)}\.reviveCaps\(\)\s*;\s*\r?\n""")
            val invalidatePattern = Regex("""(?m)^[ \t]*${Regex.escape(playerName)}\.(?:invalidateCapabilities|invalidateCaps)\(\)\s*;\s*\r?\n""")
            val reviveMatches = revivePattern.findAll(methodExecutable).toList()
            val invalidateMatches = invalidatePattern.findAll(methodExecutable).toList()
            if (reviveMatches.isNotEmpty() && invalidateMatches.isNotEmpty()) {
                edits += reviveMatches.map { match ->
                    (method.range.first + match.range.first)..(method.range.first + match.range.last) to ""
                }
                edits += invalidateMatches.map { match ->
                    (method.range.first + match.range.first)..(method.range.first + match.range.last) to ""
                }
            }
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateLegacyEnchantmentTagChecks(source: String): String {
    if (!source.contains("BuiltInRegistries.ENCHANTMENT.tags().getTag(")) return source

    val id = """[A-Za-z_$][\w$]*"""
    val holderVariables = Regex("""\b(?:net\.minecraft\.core\.)?Holder\s*<\s*(?:net\.minecraft\.world\.item\.enchantment\.)?Enchantment\s*>\s+($id)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (holderVariables.isEmpty()) return source

    val tagExpression = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""
    var result = source
    result = Regex(
        """BuiltInRegistries\.ENCHANTMENT\.tags\(\)\.getTag\(\s*($tagExpression)\s*\)\.contains\(\s*($id)\s*\)"""
    ).replace(result) { match ->
        val holder = match.groupValues[2]
        if (holder in holderVariables) {
            "$holder.is(${match.groupValues[1]})"
        } else {
            match.value
        }
    }

    result = Regex(
        """(?s)for\s*\(\s*(?:final\s+)?Enchantment\s+($id)\s*:\s*BuiltInRegistries\.ENCHANTMENT\.tags\(\)\.getTag\(\s*($tagExpression)\s*\)\s*\)\s*\{\s*if\s*\(\s*Objects\.equals\(\s*\1\s*,\s*($id)\s*\)\s*\)\s*\{\s*(.*?)\s*break\s*;\s*}\s*}"""
    ).replace(result) { match ->
        val holder = match.groupValues[3]
        if (holder in holderVariables) {
            "if ($holder.is(${match.groupValues[2]})) {\n${match.groupValues[4].trimEnd()}\n\t\t\t}"
        } else {
            match.value
        }
    }
    result = Regex(
        """(?s)for\s*\(\s*(?:final\s+)?Enchantment\s+($id)\s*:\s*BuiltInRegistries\.ENCHANTMENT\.tags\(\)\.getTag\(\s*($tagExpression)\s*\)\s*\)\s*\{\s*if\s*\(\s*Objects\.equals\(\s*($id)\s*,\s*\1\s*\)\s*\)\s*\{\s*(.*?)\s*break\s*;\s*}\s*}"""
    ).replace(result) { match ->
        val holder = match.groupValues[3]
        if (holder in holderVariables) {
            "if ($holder.is(${match.groupValues[2]})) {\n${match.groupValues[4].trimEnd()}\n\t\t\t}"
        } else {
            match.value
        }
    }

    if (!result.contains("BuiltInRegistries.ENCHANTMENT")) {
        result = removeImport(result, "net.minecraft.core.registries.BuiltInRegistries")
    }
    if (!Regex("""\bObjects\.""").containsMatchIn(result)) {
        result = removeImport(result, "java.util.Objects")
    }
    return result
}

internal fun StructuralRefactorPass.uniqueLocalName(source: String, baseName: String): String {
    if (!Regex("""\b${Regex.escape(baseName)}\b""").containsMatchIn(source)) return baseName
    var index = 1
    while (Regex("""\b${Regex.escape(baseName)}$index\b""").containsMatchIn(source)) {
        index++
    }
    return "$baseName$index"
}

internal fun StructuralRefactorPass.migrateLegacyProjectileEnchantmentPostEffectsSource(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("EnchantmentHelper.doPostHurtEffects(") ||
        !source.contains("EnchantmentHelper.doPostDamageEffects(") ||
        !source.contains("AbstractArrow")) {
        return source
    }

    var result = source
    var cursor = 0
    var changed = false
    val hurtToken = "EnchantmentHelper.doPostHurtEffects("
    val damageToken = "EnchantmentHelper.doPostDamageEffects("
    while (true) {
        val hurtIndex = result.indexOf(hurtToken, cursor)
        if (hurtIndex < 0) break
        val classDeclaration = enclosingJavaClassDeclaration(result, hurtIndex)
        if (!javaClassExtendsAny(classDeclaration, setOf("AbstractArrow"), javaInheritanceIndex)) {
            cursor = hurtIndex + hurtToken.length
            continue
        }

        val hurtOpenParen = hurtIndex + "EnchantmentHelper.doPostHurtEffects".length
        val hurtCloseParen = findMatchingParen(result, hurtOpenParen)
        if (hurtCloseParen < 0) {
            cursor = hurtIndex + hurtToken.length
            continue
        }
        val hurtArgs = splitTopLevelJavaArgs(result.substring(hurtOpenParen + 1, hurtCloseParen))
        val hurtSemicolon = nextNonWhitespaceIndex(result, hurtCloseParen + 1)
        if (hurtArgs.size != 2 || hurtSemicolon < 0 || result[hurtSemicolon] != ';') {
            cursor = hurtCloseParen + 1
            continue
        }

        val damageIndex = nextNonWhitespaceIndex(result, hurtSemicolon + 1)
        if (damageIndex < 0 || !result.startsWith(damageToken, damageIndex)) {
            cursor = hurtSemicolon + 1
            continue
        }
        val damageOpenParen = damageIndex + "EnchantmentHelper.doPostDamageEffects".length
        val damageCloseParen = findMatchingParen(result, damageOpenParen)
        if (damageCloseParen < 0) {
            cursor = damageIndex + damageToken.length
            continue
        }
        val damageArgs = splitTopLevelJavaArgs(result.substring(damageOpenParen + 1, damageCloseParen))
        val damageSemicolon = nextNonWhitespaceIndex(result, damageCloseParen + 1)
        if (damageArgs.size != 2 || damageSemicolon < 0 || result[damageSemicolon] != ';') {
            cursor = damageCloseParen + 1
            continue
        }

        val target = hurtArgs[0].trim()
        if (damageArgs[1].trim() != target ||
            normalizeLegacyLivingEntityCast(damageArgs[0]) != hurtArgs[1].trim()) {
            cursor = damageSemicolon + 1
            continue
        }

        val methodRange = enclosingMethodRange(result, hurtIndex)
        val methodPrefix = methodRange?.let { result.substring(it.first, hurtIndex) }
        val methodText = methodRange?.let { result.substring(it) }
        val damageSource = methodPrefix?.let(::lastDamageSourceVariable)
        if (damageSource == null || methodText == null) {
            cursor = damageSemicolon + 1
            continue
        }

        val statementStart = result.lastIndexOf('\n', hurtIndex).let { if (it < 0) 0 else it + 1 }
        val indent = result.substring(statementStart, hurtIndex).takeWhile { it == ' ' || it == '\t' }
        val serverLevel = uniqueLocalNameInScope(methodText, "serverlevel")
        val replacement = """${indent}if (this.level() instanceof ServerLevel $serverLevel) {
$indent    EnchantmentHelper.doPostAttackEffectsWithItemSource($serverLevel, $target, $damageSource, this.getWeaponItem());
$indent}"""
        result = result.substring(0, statementStart) + replacement + result.substring(damageSemicolon + 1)
        cursor = statementStart + replacement.length
        changed = true
    }

    return if (changed) addImportIfMissing(result, "net.minecraft.server.level.ServerLevel") else result
}

internal fun StructuralRefactorPass.normalizeLegacyLivingEntityCast(expression: String): String =
    Regex("""^\(\s*LivingEntity\s*\)\s*""").replace(expression.trim(), "").trim()

internal fun StructuralRefactorPass.migrateLegacyPlayerAttackMixinPostHurtInjection(source: String): String {
    val legacyTarget = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;doPostHurtEffects(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/Entity;)V"
    if (!source.contains(legacyTarget) ||
        !source.contains("@Inject") ||
        !source.contains("attack(Lnet/minecraft/world/entity/Entity;)V") ||
        (!source.contains("@Mixin(Player.class)") &&
            !source.contains("@Mixin(net.minecraft.world.entity.player.Player.class)"))) {
        return source
    }

    val migratedTarget = "Lnet/minecraft/world/entity/player/Player;setLastHurtMob(Lnet/minecraft/world/entity/Entity;)V"
    var result = source.replace(legacyTarget, migratedTarget)
    var changedBody = false
    var cursor = 0
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:private|protected|public)\s+void\s+[A-Za-z_$][\w$]*\s*\(\s*(?:net\.minecraft\.world\.entity\.)?Entity\s+([A-Za-z_$][\w$]*)\s*,\s*(?:org\.spongepowered\.asm\.mixin\.injection\.callback\.)?CallbackInfo\s+[A-Za-z_$][\w$]*\s*\)\s*\{"""
    )
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val targetParameter = match.groupValues[1]
        val openBrace = result.indexOf('{', match.range.last)
        if (openBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) {
            cursor = openBrace + 1
            continue
        }
        val annotationWindowStart = result.lastIndexOf("\n\n", match.range.first).let { if (it < 0) 0 else it + 2 }
        val annotationWindow = result.substring(annotationWindowStart, match.range.first)
        if (!annotationWindow.contains("@Inject") ||
            !annotationWindow.contains(migratedTarget) ||
            !annotationWindow.contains("attack(Lnet/minecraft/world/entity/Entity;)V")) {
            cursor = closeBrace + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        if (Regex("""\b${Regex.escape(targetParameter)}\s+instanceof\s+LivingEntity\b""").containsMatchIn(body)) {
            cursor = closeBrace + 1
            continue
        }
        val trimmedBody = body.trim('\r', '\n')
        if (trimmedBody.isBlank()) {
            cursor = closeBrace + 1
            continue
        }
        val methodLineStart = result.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        val methodIndent = result.substring(methodLineStart, match.range.first).takeWhile { it == ' ' || it == '\t' }
        val bodyIndent = "$methodIndent    "
        val wrappedBody = "\n$bodyIndent" +
            "if ($targetParameter instanceof LivingEntity) {\n" +
            trimmedBody.prependIndent("$bodyIndent    ") +
            "\n$bodyIndent}\n$methodIndent"
        result = result.substring(0, openBrace + 1) + wrappedBody + result.substring(closeBrace)
        cursor = openBrace + wrappedBody.length + 1
        changedBody = true
    }

    return if (changedBody) addImportIfMissing(result, "net.minecraft.world.entity.LivingEntity") else result
}

internal fun StructuralRefactorPass.migrateLegacyItemStackHurtAndBreakMixinInjection(source: String): String {
    if (!source.contains("@Inject") ||
        !source.contains("hurtAndBreak") ||
        !source.contains("@Mixin(ItemStack.class)") &&
            !source.contains("@Mixin(net.minecraft.world.item.ItemStack.class)") ||
        !source.contains("Consumer<T>")) {
        return source
    }
    var result = source
    var changed = false
    val methodPattern = Regex(
        """(?m)^([ \t]*)(private|protected|public)\s+<\s*T\s+extends\s+(?:LivingEntity|net\.minecraft\.world\.entity\.LivingEntity)\s*>\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*int\s+([A-Za-z_$][\w$]*)\s*,\s*T\s+([A-Za-z_$][\w$]*)\s*,\s*Consumer\s*<\s*T\s*>\s+([A-Za-z_$][\w$]*)\s*,\s*CallbackInfo\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    var cursor = 0
    while (true) {
        val executable = maskJavaCommentsAndLiterals(result)
        val match = methodPattern.find(executable, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val annotationWindowStart = result.lastIndexOf("\n\n", match.range.first).let { if (it < 0) 0 else it + 2 }
        val annotationWindow = result.substring(annotationWindowStart, match.range.first)
        if (!annotationWindow.contains("@Inject") || !annotationWindow.contains("hurtAndBreak")) {
            cursor = closeBrace + 1
            continue
        }

        val methodText = result.substring(match.range.first, closeBrace + 1)
        val damageName = match.groupValues[4]
        val oldEntityName = match.groupValues[5]
        val consumerName = match.groupValues[6]
        val callbackName = match.groupValues[7]
        val serverLevelName = uniqueLocalName(methodText, "serverLevel")
        val serverPlayerName = uniqueLocalName(methodText + "\n$serverLevelName", "serverPlayer")
        val newHeader = "${match.groupValues[1]}${match.groupValues[2]} void ${match.groupValues[3]}(int $damageName, ServerLevel $serverLevelName, ServerPlayer $serverPlayerName, Consumer<Item> $consumerName, CallbackInfo $callbackName) {"

        var body = result.substring(openBrace + 1, closeBrace)
        val playerPattern = Regex(
            """\(\s*${Regex.escape(oldEntityName)}\s+instanceof\s+Player\s+([A-Za-z_$][\w$]*)\s*\)"""
        )
        val playerVariable = playerPattern.find(body)?.groupValues?.get(1)
        // the matched parentheses stay: `!(user instanceof Player p)` is `!(serverPlayer != null)`
        body = playerPattern.replace(body, "($serverPlayerName != null)")
        if (playerVariable != null) {
            body = Regex("""(?<![\w$])${Regex.escape(playerVariable)}(?![\w$])""")
                .replace(body, serverPlayerName)
        }
        body = Regex("""\b${Regex.escape(oldEntityName)}\.level\(\)""").replace(body, serverLevelName)
        body = Regex("""(?<![\w$])${Regex.escape(oldEntityName)}(?![\w$])""").replace(body, serverPlayerName)
        body = collapseRedundantServerLevelInstanceof(body, serverLevelName)

        result = result.substring(0, match.range.first) + newHeader + body + result.substring(closeBrace)
        cursor = match.range.first + newHeader.length + body.length
        changed = true
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    result = addImportIfMissing(result, "net.minecraft.server.level.ServerPlayer")
    result = addImportIfMissing(result, "net.minecraft.world.item.Item")
    return result
}

internal fun StructuralRefactorPass.collapseRedundantServerLevelInstanceof(source: String, serverLevelName: String): String {
    var result = source
    val pattern = Regex("""if\s*\(\s*${Regex.escape(serverLevelName)}\s+instanceof\s+ServerLevel\s+([A-Za-z_$][\w$]*)\s*\)\s*\{""")
    var cursor = 0
    while (true) {
        val match = pattern.find(result, cursor) ?: break
        val alias = match.groupValues[1]
        val openBrace = result.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }
        val lineStart = result.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        val guardIndent = lineIndentAt(result, match.range.first)
        val childIndent = guardIndent + "    "
        val body = result.substring(openBrace + 1, closeBrace)
            .replace(Regex("""(?<![\w$])${Regex.escape(alias)}(?![\w$])"""), serverLevelName)
            .replace(Regex("""(?m)^${Regex.escape(childIndent)}"""), guardIndent)
            .trim('\r', '\n')
        result = result.substring(0, lineStart) + body + result.substring(closeBrace + 1)
        cursor = lineStart + body.length
    }
    return result
}

internal fun StructuralRefactorPass.lastDamageSourceVariable(prefix: String): String? =
    Regex("""\bDamageSource\s+([A-Za-z_$][\w$]*)\s*(?:=|;)""")
        .findAll(prefix)
        .lastOrNull()
        ?.groupValues
        ?.get(1)

internal fun StructuralRefactorPass.migrateLegacyDoEnchantDamageEffectsSource(source: String): String {
    if (!source.contains(".doEnchantDamageEffects(")) return source

    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    var needsServerLevel = false
    var needsEnchantmentHelper = false
    var needsDamageSource = false
    val token = ".doEnchantDamageEffects("

    while (cursor < source.length) {
        val tokenIndex = source.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val receiverStart = findDottedReceiverStart(source, tokenIndex)
        val receiver = source.substring(receiverStart, tokenIndex).trim()
        val openParen = tokenIndex + ".doEnchantDamageEffects".length
        val closeParen = findMatchingParen(source, openParen)
        if (receiver.isBlank() || closeParen < 0) {
            migrated.append(source, cursor, tokenIndex + token.length)
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size != 2) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }

        val statementStart = source.lastIndexOf('\n', receiverStart).let { if (it < 0) 0 else it + 1 }
        val semicolonIndex = nextNonWhitespaceIndex(source, closeParen + 1)
        if (semicolonIndex < 0 || source[semicolonIndex] != ';') {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }

        val target = args[1].trim()
        val damageSourceExpr = damageSourceFromPrecedingTargetHurt(source, statementStart, target)
        if (damageSourceExpr == null) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }

        val indent = source.substring(statementStart, receiverStart).takeWhile { it == ' ' || it == '\t' }
        val levelExpression = if (receiver == "this") "this.level()" else "$receiver.level()"
        val damageSource = if (Regex("""^[A-Za-z_$][\w$]*$""").matches(damageSourceExpr)) {
            damageSourceExpr
        } else {
            "postAttackDamageSource"
        }
        val sourceLine = if (damageSource == damageSourceExpr) {
            ""
        } else {
            "${indent}DamageSource $damageSource = $damageSourceExpr;\n"
        }
        val replacement = """$sourceLine${indent}if ($levelExpression instanceof ServerLevel serverLevel) {
$indent    EnchantmentHelper.doPostAttackEffects(serverLevel, $target, $damageSource);
$indent}"""
        migrated.append(source, cursor, statementStart)
        migrated.append(replacement)
        cursor = semicolonIndex + 1
        changed = true
        needsServerLevel = true
        needsEnchantmentHelper = true
        needsDamageSource = needsDamageSource || sourceLine.isNotEmpty()
    }

    if (!changed) return source
    migrated.append(source, cursor, source.length)
    var result = migrated.toString()
    if (needsServerLevel) {
        result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    }
    if (needsEnchantmentHelper) {
        result = addImportIfMissing(result, "net.minecraft.world.item.enchantment.EnchantmentHelper")
    }
    if (needsDamageSource) {
        result = addImportIfMissing(result, "net.minecraft.world.damagesource.DamageSource")
    }
    return result
}

internal fun StructuralRefactorPass.damageSourceFromPrecedingTargetHurt(source: String, callStatementStart: Int, targetExpression: String): String? {
    val methodStart = enclosingMethodRange(source, callStatementStart)?.first
        ?: source.lastIndexOf('{', callStatementStart).let { if (it < 0) 0 else it + 1 }
    val prefix = source.substring(methodStart, callStatementStart)
    val token = "$targetExpression.hurt("
    var cursor = 0
    var damageSource: String? = null
    while (cursor < prefix.length) {
        val index = prefix.indexOf(token, cursor)
        if (index < 0) break
        val openParen = index + "$targetExpression.hurt".length
        val closeParen = findMatchingParen(prefix, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(prefix.substring(openParen + 1, closeParen))
        if (args.size >= 2) {
            damageSource = args[0].trim()
        }
        cursor = closeParen + 1
    }
    return damageSource
}

internal fun StructuralRefactorPass.migrateMethodCalls(
    source: String,
    methodToken: String,
    migrateArgs: (List<String>) -> List<String>
): String {
    val migrated = StringBuilder()
    var cursor = 0
    while (cursor < source.length) {
        val tokenIndex = source.indexOf(methodToken, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + methodToken.length
        if (openParen >= source.length || source[openParen] != '(') {
            migrated.append(source, cursor, openParen)
            cursor = openParen
            continue
        }
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break

        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        val migratedArgs = migrateArgs(args)
        migrated.append(source, cursor, openParen + 1)
        migrated.append(migratedArgs.joinToString(", ") { it.trim() })
        migrated.append(")")
        cursor = closeParen + 1
    }
    if (cursor == 0) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.migrateLegacyRegistryObjectReflection(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("RegistryObject.class")) return source
    var result = replaceExecutableRegex(source, Regex("""\bRegistryObject\.class\b""")) {
        "DeferredHolder.class"
    }
    val maskedResult = maskJavaCommentsAndLiterals(result)
    val messageEdits = Regex("""return\s+"([^"\\]*(?:\\.[^"\\]*)*)RegistryObject fields([^"\\]*(?:\\.[^"\\]*)*)"\s*;""")
        .findAll(result)
        .mapNotNull { match ->
            val executableSlice = maskedResult.substring(match.range)
            if (!executableSlice.trimStart().startsWith("return")) return@mapNotNull null
            match.range to match.value.replace("RegistryObject fields", "DeferredHolder fields")
        }
        .toList()
    return applyStringEdits(result, messageEdits)
}

internal fun StructuralRefactorPass.payloadPathName(className: String): String {
    val semanticName = className.removeSuffix("Packet").ifBlank { className }
    return semanticName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
}

internal fun StructuralRefactorPass.migrateNeoForgeGameTestResourceAssertions(source: String): String {
    if (!source.contains("@GameTest")) return source
    var result = source
    result = result.replace(
        "\"data/forge/loot_modifiers/global_loot_modifiers.json\"",
        "\"data/neoforge/loot_modifiers/global_loot_modifiers.json\""
    )
    result = result.replace("\"META-INF/mods.toml\"", "\"META-INF/neoforge.mods.toml\"")
    result = result.replace("block.contains(\"mandatory=false\")", "block.contains(\"type=\\\"optional\\\"\")")
    result = result.replace("forge:loot_table_id", "neoforge:loot_table_id")
    result = result.replace("minecraft:grass", "minecraft:short_grass")
    return result
}

internal data class IdBackedEntityAccessor(
    val typeName: String,
    val idGetterName: String,
    val entityGetterName: String,
    val entitySetterName: String,
    val entityFieldName: String
)

internal fun StructuralRefactorPass.migrateIdBackedEntitySpatialLookups(javaFiles: List<Path>, dryRun: Boolean): List<Change> {
    val existingJavaFiles = javaFiles.filter { it.exists() }
    val accessors = collectIdBackedEntityAccessors(existingJavaFiles)
    if (accessors.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    changes += migrateIdBackedEntityHandles(existingJavaFiles, accessors, dryRun)
    changes += migrateIdBackedEntityConstructorBindings(existingJavaFiles, accessors, dryRun)
    for (file in existingJavaFiles) {
        val original = file.readText()
        if (!original.contains(".getEntitiesOfClass(") ||
            !original.contains(".stream()") ||
            !original.contains(".findFirst()")) {
            continue
        }
        val migrated = migrateIdBackedEntitySpatialLookupsSource(original, accessors)
        if (migrated != original) {
            if (!dryRun) file.writeText(migrated)
            changes.add(
                Change(
                    file = file,
                    line = lineNumberAt(original, firstIdBackedEntityLookupInsertion(original, migrated)),
                    description = "Prefer direct UUID-backed entity lookup before spatial entity query fallback",
                    before = "level.getEntitiesOfClass(Entity.class, box).stream().findFirst()",
                    after = "session entity handle, level.getEntity(id), then original spatial fallback",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-id-backed-entity-lookup"
                )
            )
        }
    }
    return changes
}

internal fun StructuralRefactorPass.collectIdBackedEntityAccessors(javaFiles: List<Path>): Map<String, IdBackedEntityAccessor> {
    val accessors = linkedMapOf<String, IdBackedEntityAccessor>()
    val accessorPattern = Regex(
        """(?m)^[ \t]*(?:public|protected|private)?\s*(?:final\s+)?(?:java\.util\.)?UUID\s+(get[A-Za-z_$][\w$]*EntityId)\s*\(\s*\)\s*\{"""
    )
    for (file in javaFiles) {
        if (!file.exists()) continue
        val source = file.readText()
        val className = classNameOfJavaSource(source) ?: continue
        val executable = maskJavaCommentsAndLiterals(source)
        val accessor = accessorPattern.find(executable)?.groupValues?.get(1) ?: continue
        val prefix = accessor.removePrefix("get").removeSuffix("EntityId")
        accessors[className] = IdBackedEntityAccessor(
            typeName = className,
            idGetterName = accessor,
            entityGetterName = "get${prefix}Entity",
            entitySetterName = "set${prefix}Entity",
            entityFieldName = "${prefix.replaceFirstChar { it.lowercase() }}Entity"
        )
    }
    return accessors
}

internal fun StructuralRefactorPass.migrateIdBackedEntityHandles(
    javaFiles: List<Path>,
    accessors: Map<String, IdBackedEntityAccessor>,
    dryRun: Boolean
): List<Change> {
    val changes = mutableListOf<Change>()
    for (file in javaFiles) {
        if (!file.exists()) continue
        val original = file.readText()
        val className = classNameOfJavaSource(original) ?: continue
        val accessor = accessors[className] ?: continue
        val migrated = ensureIdBackedEntityHandle(original, accessor)
        if (migrated == original) continue
        if (!dryRun) file.writeText(migrated)
        changes += Change(
            file = file,
            line = lineNumberAt(original, original.lastIndexOf('}').coerceAtLeast(0)),
            description = "Preserve UUID-backed entity identity with a transient entity handle",
            before = "${accessor.idGetterName}() only exposes a UUID",
            after = "${accessor.entityGetterName}() exposes the live Entity when construction still has it",
            confidence = Confidence.HIGH,
            ruleId = "struct-id-backed-entity-handle"
        )
    }
    return changes
}

internal fun StructuralRefactorPass.ensureIdBackedEntityHandle(source: String, accessor: IdBackedEntityAccessor): String {
    if (Regex("""\b${Regex.escape(accessor.entityGetterName)}\s*\(""").containsMatchIn(source) &&
        Regex("""\b${Regex.escape(accessor.entitySetterName)}\s*\(""").containsMatchIn(source)) {
        return source
    }
    if (source.contains("net.minecraft.world.entity.Entity ${accessor.entityFieldName};") ||
        source.contains("Entity ${accessor.entityFieldName};")) {
        return source
    }
    val insertion = """

    private transient volatile net.minecraft.world.entity.Entity ${accessor.entityFieldName};

    public net.minecraft.world.entity.Entity ${accessor.entityGetterName}() {
        return ${accessor.entityFieldName};
    }

    public void ${accessor.entitySetterName}(net.minecraft.world.entity.Entity ${accessor.entityFieldName}) {
        this.${accessor.entityFieldName} = ${accessor.entityFieldName};
    }
""".trimEnd()
    return insertBeforeLastClassBrace(source, insertion)
}

internal fun StructuralRefactorPass.migrateIdBackedEntityConstructorBindings(
    javaFiles: List<Path>,
    accessors: Map<String, IdBackedEntityAccessor>,
    dryRun: Boolean
): List<Change> {
    val changes = mutableListOf<Change>()
    for (file in javaFiles) {
        if (!file.exists()) continue
        val original = file.readText()
        if (!original.contains("this.getUUID()") || !isLikelyMinecraftEntitySubclass(original)) continue
        val migrated = addIdBackedEntityConstructorBindings(original, accessors)
        if (migrated == original) continue
        if (!dryRun) file.writeText(migrated)
        changes += Change(
            file = file,
            line = lineNumberAt(original, firstIdBackedEntityBindingInsertion(original, migrated)),
            description = "Keep live Entity handle when UUID-backed session binding is made from an entity constructor",
            before = "session binding stores this.getUUID() only",
            after = "session binding also stores this Entity as a transient handle",
            confidence = Confidence.HIGH,
            ruleId = "struct-id-backed-entity-constructor-binding"
        )
    }
    return changes
}

internal fun StructuralRefactorPass.isLikelyMinecraftEntitySubclass(source: String): Boolean =
    Regex("""\bclass\s+[A-Za-z_$][\w$]*\s+extends\s+(?:[A-Za-z_$][\w$]*\.)?Entity\b""")
        .containsMatchIn(maskJavaCommentsAndLiterals(source))

internal fun StructuralRefactorPass.addIdBackedEntityConstructorBindings(
    source: String,
    accessors: Map<String, IdBackedEntityAccessor>
): String {
    val executable = maskJavaCommentsAndLiterals(source)
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executable)) {
        val methodText = source.substring(method.range)
        if (!methodText.contains("this.getUUID()")) continue
        val parameters = callableParameters(source, method.range)
            .mapNotNull(::javaParameterTypeAndName)
        val idBackedParameters = parameters.mapNotNull { (type, name) ->
            val accessor = accessors[javaSimpleTypeName(type)] ?: return@mapNotNull null
            name to accessor
        }
        if (idBackedParameters.isEmpty()) continue
        for ((holderName, accessor) in idBackedParameters) {
            if (Regex("""\b${Regex.escape(holderName)}\.${Regex.escape(accessor.entitySetterName)}\s*\(\s*this\s*\)""")
                    .containsMatchIn(methodText)) {
                continue
            }
            val statementPattern = Regex(
                """(?m)^([ \t]*).*?\b${Regex.escape(holderName)}\b.*?\bthis\.getUUID\s*\(\s*\).*?;\s*$"""
            )
            for (match in statementPattern.findAll(methodText)) {
                val indent = match.groupValues[1]
                val absoluteInsertion = method.range.first + match.range.last + 1
                edits += (absoluteInsertion until absoluteInsertion) to
                    "\n$indent${holderName}.${accessor.entitySetterName}(this);"
                break
            }
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateIdBackedEntitySpatialLookupsSource(
    source: String,
    accessors: Map<String, IdBackedEntityAccessor>
): String {
    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        val methodText = result.substring(method.range)
        if (!methodText.contains(".getEntitiesOfClass(") ||
            !methodText.contains(".stream()") ||
            !methodText.contains(".findFirst()") ||
            !methodText.contains(".orElse")) {
            continue
        }
        val parameters = callableParameters(result, method.range)
            .mapNotNull(::javaParameterTypeAndName)
        val idBackedParameters = parameters.mapNotNull { (type, name) ->
            val accessor = accessors[javaSimpleTypeName(type)] ?: return@mapNotNull null
            name to accessor
        }
        if (idBackedParameters.isEmpty()) continue

        val migratedMethod = migrateIdBackedEntitySpatialLookupsInMethod(methodText, idBackedParameters)
        if (migratedMethod != methodText) {
            result = result.substring(0, method.range.first) +
                migratedMethod +
                result.substring(method.range.last + 1)
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateIdBackedEntitySpatialLookupsInMethod(
    methodText: String,
    idBackedParameters: List<Pair<String, IdBackedEntityAccessor>>
): String {
    val executable = maskJavaCommentsAndLiterals(methodText)
    val returnPattern = Regex(
        """(?m)^([ \t]*)return\s+([A-Za-z_$][\w$]*)\.getEntitiesOfClass\s*\(\s*([A-Za-z_$][\w$]*)\.class\s*,"""
    )
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (match in returnPattern.findAll(executable)) {
        val statementEnd = executable.indexOf(';', match.range.first)
            .takeIf { it >= 0 }
            ?: continue
        val statement = methodText.substring(match.range.first, statementEnd + 1)
        if (!statement.contains(".stream()") ||
            !statement.contains(".findFirst()") ||
            !statement.contains(".orElse")) {
            continue
        }
        val (holderName, accessor) = idBackedParameters.firstOrNull { (name, _) ->
            Regex("""(?<![\w$])${Regex.escape(name)}(?![\w$])""").containsMatchIn(statement)
        } ?: continue
        val filter = Regex(
            """(?s)\.filter\s*\(\s*([A-Za-z_$][\w$]*)\s*->\s*(.*?)\)\s*\.findFirst\s*\("""
        ).find(statement) ?: continue
        val lambdaName = filter.groupValues[1]
        val predicate = filter.groupValues[2].trim()
        val indent = match.groupValues[1]
        val levelName = match.groupValues[2]
        val entityClass = match.groupValues[3]
        val directIdName = uniqueJavaLocalName(methodText, "modporterDirectEntityId")
        val directEntityName = uniqueJavaLocalName(methodText, "modporterDirectEntity")
        val directTypedName = uniqueJavaLocalName(methodText, "modporterDirect$entityClass")
        val directPredicate = Regex("""(?<![\w$])${Regex.escape(lambdaName)}(?![\w$])""")
            .replace(predicate, directTypedName)
        val guard = buildString {
            append(indent)
            append("java.util.UUID ")
            append(directIdName)
            append(" = ")
            append(holderName)
            append(".")
            append(accessor.idGetterName)
            append("();\n")
            append(indent)
            append("net.minecraft.world.entity.Entity ")
            append(directEntityName)
            append(" = ")
            append(holderName)
            append(".")
            append(accessor.entityGetterName)
            append("();\n")
            append(indent)
            append("if (")
            append(directEntityName)
            append(" == null && ")
            append(directIdName)
            append(" != null) {\n")
            append(indent)
            append("    ")
            append(directEntityName)
            append(" = ")
            append(levelName)
            append(".getEntity(")
            append(directIdName)
            append(");\n")
            append(indent)
            append("}\n")
            append(indent)
            append("if (")
            append(directEntityName)
            append(" instanceof ")
            append(entityClass)
            append(" ")
            append(directTypedName)
            append(" && !")
            append(directTypedName)
            append(".isRemoved()")
            if (directPredicate.isNotBlank()) {
                append(" && ")
                append(directPredicate)
            }
            append(") {\n")
            append(indent)
            append("    return ")
            append(directTypedName)
            append(";\n")
            append(indent)
            append("}\n")
        }
        edits += (match.range.first until match.range.first) to guard
    }
    return applyStringEdits(methodText, edits)
}

internal fun StructuralRefactorPass.javaSimpleTypeName(type: String): String =
    type.trim()
        .removeSuffix("[]")
        .substringBefore('<')
        .substringAfterLast('.')
        .trim()

internal fun StructuralRefactorPass.firstIdBackedEntityLookupInsertion(original: String, migrated: String): Int {
    val token = "modporterDirectEntityId"
    val index = migrated.indexOf(token)
    if (index < 0) return 0
    return index.coerceAtMost(original.length)
}

internal fun StructuralRefactorPass.firstIdBackedEntityBindingInsertion(original: String, migrated: String): Int {
    val token = ".set"
    val entityToken = "Entity(this);"
    val index = migrated.indexOf(entityToken)
        .takeIf { it >= 0 }
        ?.let { migrated.lastIndexOf(token, it).takeIf { tokenIndex -> tokenIndex >= 0 } ?: it }
        ?: -1
    if (index < 0) return 0
    return index.coerceAtMost(original.length)
}

internal fun StructuralRefactorPass.migrateLegacyItemAttributeModifierOverrides(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val mainClass = detectModMainClass(projectDir)
    val mainText = mainClass?.readText().orEmpty()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .toList()
    val sourceByFile = javaFiles.associateWith { it.readText() }
    val reachHelpers = collectLegacyStaticReachAttributeHelpers(sourceByFile, mainClass, mainText)
    val dynamicAttributeHelpers = collectLegacyDynamicItemAttributeHelpers(sourceByFile, mainClass, mainText)
    val changes = mutableListOf<Change>()
    javaFiles.forEach { file ->
            val original = file.readText()
            if (!original.contains("getDefaultAttributeModifiers(EquipmentSlot") ||
                !original.contains("new AttributeModifier(") ||
                !original.contains("ImmutableMultimap")) {
                return@forEach
            }
            val className = classNameOfJavaSource(original) ?: return@forEach
            val parentItem = Regex("""extends\s+([A-Za-z_$][\w$]*Item)\b""").find(original)
                ?.groupValues
                ?.get(1)
                ?: return@forEach
            val legacyConstructor = Regex(
                """public\s+${Regex.escape(className)}\s*\(\s*Tier\s+([A-Za-z_$][\w$]*)\s*,\s*Properties\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\s*\(\s*\1\s*,\s*([^,]+)\s*,\s*([^,]+)\s*,\s*\2\s*\)\s*;"""
            ).find(original)
            val componentConstructor = Regex(
                """public\s+${Regex.escape(className)}\s*\(\s*Tier\s+([A-Za-z_$][\w$]*)\s*,\s*Properties\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\s*\(\s*\1\s*,\s*\2\.attributes\(\s*((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*\.createAttributes)\(\s*\1\s*,\s*([^,]+)\s*,\s*([^)]+?)\s*\)\s*\)\s*\)\s*;"""
            ).find(original)
            if (legacyConstructor == null && componentConstructor == null) return@forEach
            val constructor = legacyConstructor ?: componentConstructor ?: return@forEach
            val tierName = constructor.groupValues[1]
            val propertiesName = constructor.groupValues[2]
            val baseAttributesFactory = componentConstructor?.groupValues?.get(3)?.trim()
                ?: when (parentItem) {
                    "AxeItem", "HoeItem", "PickaxeItem", "ShovelItem" -> "net.minecraft.world.item.DiggerItem.createAttributes"
                    else -> "$parentItem.createAttributes"
                }
            val damage = if (componentConstructor != null) constructor.groupValues[4].trim() else constructor.groupValues[3].trim()
            val speed = if (componentConstructor != null) constructor.groupValues[5].trim() else constructor.groupValues[4].trim()
            val methodText = javaMethodText(original, "getDefaultAttributeModifiers") ?: return@forEach
            val modifierLines = legacyItemAttributeModifierLines(methodText, file, mainClass, mainText, ownerSource = original)
            if (modifierLines.isEmpty()) return@forEach

            var migrated = original.replace(
                constructor.value,
                "public $className(Tier $tierName, Properties $propertiesName) {\n\t\tsuper($tierName, $propertiesName.attributes(create${className}Attributes($tierName, $damage, $speed)));"
            )
            migrated = removeMethodByNameContaining(
                migrated,
                "getDefaultAttributeModifiers",
                listOf("ImmutableMultimap", "AttributeModifier")
            )
            val factory = """

	public static ItemAttributeModifiers create${className}Attributes(Tier tier, int damage, float speed) {
		return ${baseAttributesFactory}(tier, damage, speed)
${modifierLines.joinToString("\n")}
			;
	}
                """.trimEnd()
            val classOpen = Regex("""public\s+class\s+${Regex.escape(className)}\b[^{]*\{""").find(migrated)
            if (classOpen != null && !migrated.contains("create${className}Attributes(")) {
                val insertAt = classOpen.range.last + 1
                migrated = migrated.substring(0, insertAt) + factory + migrated.substring(insertAt)
            } else if (classOpen != null) {
                val insertAt = classOpen.range.last + 1
                migrated = migrated.substring(0, insertAt) + factory + migrated.substring(insertAt)
            }
            migrated = removeImportIfPresent(migrated, "com.google.common.collect.ImmutableMultimap")
            migrated = removeImportIfPresent(migrated, "com.google.common.collect.Multimap")
            migrated = removeImportIfPresent(migrated, "net.minecraft.world.entity.EquipmentSlot")
            migrated = removeImportIfPresent(migrated, "net.minecraft.world.entity.ai.attributes.Attribute")
            migrated = removeImportIfPresent(migrated, "net.neoforged.neoforge.common.NeoForgeMod")
            migrated = addImportIfMissing(migrated, "net.minecraft.world.entity.EquipmentSlotGroup")
            migrated = addImportIfMissing(migrated, "net.minecraft.world.entity.ai.attributes.Attributes")
            migrated = addImportIfMissing(migrated, "net.minecraft.world.item.component.ItemAttributeModifiers")

            if (migrated != original) {
                if (!dryRun) file.writeText(migrated)
                changes.add(Change(
                    file = file,
                    line = 1,
                    description = "Migrate legacy item attribute modifier override to 1.21 item attribute components",
                    before = "getDefaultAttributeModifiers(EquipmentSlot) + ImmutableMultimap",
                    after = "ItemAttributeModifiers factory on Item.Properties.attributes",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-item-attribute-modifier-component"
                ))
            }
        }
    javaFiles.forEach { file ->
        val original = file.readText()
        val migrated = migrateConstructorDefaultAttributeModifiers(original, file, mainClass, mainText)
            ?: return@forEach
        if (migrated != original) {
            if (!dryRun) file.writeText(migrated)
            changes.add(Change(
                file = file,
                line = 1,
                description = "Migrate constructor-built default item modifiers to 1.21 item attribute components",
                before = "ImmutableMultimap defaultModifiers + getDefaultAttributeModifiers(EquipmentSlot)",
                after = "Item.Properties.attributes(ItemAttributeModifiers.builder().add(...).build())",
                confidence = Confidence.HIGH,
                ruleId = "struct-item-constructor-default-attribute-component"
            ))
        }
    }
    val migratedHelperNames = linkedSetOf<String>()
    javaFiles.forEach { file ->
        val original = file.readText()
        if (!original.contains("getAttributeModifiers(EquipmentSlot") ||
            !original.contains("super.getAttributeModifiers(")) {
            return@forEach
        }
        val migration = legacyStaticReachAttributeOverrideMigration(original, reachHelpers) ?: return@forEach
        val constructorMigrated = migrateLegacyItemAttributeConstructorToProperties(original)
        var migrated = appendStaticAttributeModifiersToConstructorAttributes(constructorMigrated, migration.modifierLines) ?: return@forEach
        migrated = removeMethodByNameContaining(
            migrated,
            "getAttributeModifiers",
            listOf("super.getAttributeModifiers", migration.helperName)
        )
        migrated = removeUnusedSimpleImport(migrated, "com.google.common.collect.Multimap", "Multimap")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.core.Holder", "Holder")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.world.entity.EquipmentSlot", "EquipmentSlot")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.world.entity.ai.attributes.Attribute", "Attribute")
        migrated = addImportIfMissing(migrated, "net.minecraft.world.entity.EquipmentSlotGroup")
        migrated = addImportIfMissing(migrated, "net.minecraft.world.entity.ai.attributes.AttributeModifier")
        migrated = addImportIfMissing(migrated, "net.minecraft.world.entity.ai.attributes.Attributes")
        if (migration.modifierLines.any { it.contains("ResourceLocation.") && !it.contains("net.minecraft.resources.ResourceLocation.") }) {
            migrated = addImportIfMissing(migrated, "net.minecraft.resources.ResourceLocation")
        }
        migrated = cleanupRedundantBlankLines(migrated)
        if (migrated != original) {
            if (!dryRun) file.writeText(migrated)
            migratedHelperNames += migration.helperName
            changes.add(Change(
                file = file,
                line = 1,
                description = "Migrate static item attribute helper override to constructor attribute components",
                before = "getAttributeModifiers(EquipmentSlot, ItemStack) delegates to a static reach helper",
                after = "Item.Properties.attributes(...withModifierAdded(...))",
                confidence = Confidence.HIGH,
                ruleId = "struct-item-attribute-helper-component"
            ))
        }
    }
    if (migratedHelperNames.isNotEmpty()) {
        changes += pruneUnusedLegacyReachAttributeHelpers(javaFiles, migratedHelperNames, dryRun)
    }
    val dynamicHelpersToMigrate = linkedMapOf<Path, MutableList<LegacyDynamicItemAttributeHelper>>()
    javaFiles.forEach { file ->
        val original = file.readText()
        if (!original.contains("getAttributeModifiers(EquipmentSlot") ||
            !original.contains("super.getAttributeModifiers(")) {
            return@forEach
        }
        val overrideMigration = replaceLegacyDynamicItemAttributeOverride(original, dynamicAttributeHelpers)
            ?: return@forEach
        var migrated = migrateLegacyItemAttributeConstructorToProperties(overrideMigration.source)
        migrated = removeUnusedSimpleImport(migrated, "com.google.common.collect.Multimap", "Multimap")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.core.Holder", "Holder")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.world.entity.EquipmentSlot", "EquipmentSlot")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.world.entity.ai.attributes.Attribute", "Attribute")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.world.entity.ai.attributes.AttributeModifier", "AttributeModifier")
        migrated = addImportIfMissing(migrated, "net.minecraft.world.item.component.ItemAttributeModifiers")
        migrated = cleanupRedundantBlankLines(migrated)
        if (migrated != original) {
            if (!dryRun) file.writeText(migrated)
            dynamicHelpersToMigrate.getOrPut(overrideMigration.helper.file) { mutableListOf() } += overrideMigration.helper
            changes.add(Change(
                file = file,
                line = 1,
                description = "Migrate stack-sensitive item attribute override to 1.21 item attribute components",
                before = "getAttributeModifiers(EquipmentSlot, ItemStack) delegates a stack-sensitive Multimap helper",
                after = "getDefaultAttributeModifiers(ItemStack) delegates an ItemAttributeModifiers helper",
                confidence = Confidence.HIGH,
                ruleId = "struct-item-dynamic-attribute-component"
            ))
        }
    }
    dynamicHelpersToMigrate.forEach { (file, helpers) ->
        val original = file.readText()
        val migrated = migrateLegacyDynamicItemAttributeHelperFile(original, helpers.distinctBy { it.methodName })
            ?: return@forEach
        if (migrated != original) {
            if (!dryRun) file.writeText(migrated)
            changes.add(Change(
                file = file,
                line = 1,
                description = "Migrate stack-sensitive item attribute helper to ItemAttributeModifiers",
                before = "Multimap attribute helper mutates a legacy base map",
                after = "ItemAttributeModifiers helper returns a slot-scoped modifier component",
                confidence = Confidence.HIGH,
                ruleId = "struct-item-dynamic-attribute-helper"
            ))
        }
    }
    javaFiles.forEach { file ->
        val original = file.readText()
        val migrated = migrateDirectLegacyItemAttributeOverride(original, file, mainClass, mainText)
            ?: return@forEach
        if (migrated != original) {
            if (!dryRun) file.writeText(migrated)
            changes.add(Change(
                file = file,
                line = 1,
                description = "Migrate direct stack-sensitive item attribute override to 1.21 item attribute components",
                before = "getAttributeModifiers(EquipmentSlot, ItemStack) builds an ImmutableMultimap directly",
                after = "getDefaultAttributeModifiers(ItemStack) returns ItemAttributeModifiers",
                confidence = Confidence.HIGH,
                ruleId = "struct-item-direct-dynamic-attribute-component"
            ))
        }
    }
    javaFiles.forEach { file ->
        val original = file.readText()
        val migrated = migrateLegacyItemAttributeModifierOverrides(original)
        if (migrated != original) {
            if (!dryRun) file.writeText(migrated)
            changes.add(Change(
                file = file,
                line = 1,
                description = "Migrate direct stack-sensitive item attribute override to 1.21 item attribute components",
                before = "getAttributeModifiers(EquipmentSlot, ItemStack) builds a Multimap directly",
                after = "getDefaultAttributeModifiers(ItemStack) builds ItemAttributeModifiers directly",
                confidence = Confidence.HIGH,
                ruleId = "struct-item-direct-dynamic-attribute-component"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.migrateConstructorDefaultAttributeModifiers(
    source: String,
    file: Path,
    mainClass: Path?,
    mainText: String
): String? {
    if (!source.contains("defaultModifiers") ||
        !source.contains("ImmutableMultimap") ||
        !source.contains("getDefaultAttributeModifiers(EquipmentSlot")) {
        return null
    }
    val className = classNameOfJavaSource(source) ?: return null
    var result = source
    var changed = false
    for (constructor in javaConstructorRanges(source, className)) {
        val constructorText = source.substring(constructor.range)
        if (!constructorText.contains("ImmutableMultimap") ||
            !constructorText.contains("this.defaultModifiers =") ||
            !constructorText.contains(".put(")) {
            continue
        }
        val effectiveConstructorText = inlineConstructorAssignedFieldReferences(constructorText)
        val modifierLines = legacyItemAttributeModifierLines(
            effectiveConstructorText,
            file,
            mainClass,
            mainText,
            slotGroupExpression = "EquipmentSlotGroup.MAINHAND",
            ownerSource = source
        ).map { it.replace(".withModifierAdded(", ".add(") }
        if (modifierLines.isEmpty()) continue
        val migratedConstructor = migrateConstructorDefaultAttributeModifierBody(constructorText, modifierLines)
            ?: continue
        result = result.replace(constructorText, migratedConstructor)
        changed = true
    }
    if (!changed) return null
    result = Regex(
        """(?m)^[ \t]*private\s+final\s+Multimap\s*<[^;\r\n]+>\s+defaultModifiers\s*;\s*\r?\n"""
    ).replace(result, "")
    result = removeMethodByNameContaining(result, "getDefaultAttributeModifiers", listOf("defaultModifiers"))
    result = removeUnusedSimpleImport(result, "com.google.common.collect.ImmutableMultimap", "ImmutableMultimap")
    result = removeUnusedSimpleImport(result, "com.google.common.collect.Multimap", "Multimap")
    result = removeUnusedSimpleImport(result, "net.minecraft.core.Holder", "Holder")
    result = removeUnusedSimpleImport(result, "net.minecraft.world.entity.ai.attributes.Attribute", "Attribute")
    result = addImportIfMissing(result, "net.minecraft.world.entity.EquipmentSlotGroup")
    result = addImportIfMissing(result, "net.minecraft.world.item.component.ItemAttributeModifiers")
    return cleanupRedundantBlankLines(result)
}

internal fun StructuralRefactorPass.inlineConstructorAssignedFieldReferences(constructorText: String): String {
    var result = constructorText
    Regex("""\bthis\.([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]+?)\s*;""")
        .findAll(constructorText)
        .forEach { assignment ->
            val field = assignment.groupValues[1]
            val expression = assignment.groupValues[2].trim()
            if (!Regex("""\b(?:this|super)\b""").containsMatchIn(expression)) {
                result = result.replace("this.$field", "($expression)")
            }
        }
    return result
}

internal fun StructuralRefactorPass.migrateConstructorDefaultAttributeModifierBody(
    constructorText: String,
    modifierLines: List<String>
): String? {
    val superMatch = Regex("""\bsuper\s*\(""").find(constructorText) ?: return null
    val superOpen = constructorText.indexOf('(', superMatch.range.last - 1)
    val superClose = if (superOpen >= 0) findMatchingParen(constructorText, superOpen) else -1
    if (superOpen < 0 || superClose < 0) return null
    val statementEnd = constructorText.indexOf(';', superClose)
    if (statementEnd < 0) return null
    val args = splitTopLevelJavaArgs(constructorText.substring(superOpen + 1, superClose)).toMutableList()
    val propertyParams = javaMethodParameters(constructorText)
        .filter { simpleJavaTypeName(it.type) == "Properties" }
        .map { it.name }
        .toSet()
    val propertyIndex = args.indexOfLast { arg -> isItemPropertiesArgument(arg.trim(), propertyParams) }
    if (propertyIndex < 0) return null
    val attributesExpression = buildString {
        append("ItemAttributeModifiers.builder()")
        modifierLines.forEach { line -> append("\n").append(line) }
        append("\n\t\t\t.build()")
    }
    args[propertyIndex] = appendItemAttributesToPropertiesArgument(args[propertyIndex].trim(), attributesExpression)
    val replacementSuper = "super(${args.joinToString(", ")});"
    var result = constructorText.substring(0, superMatch.range.first) +
        replacementSuper +
        constructorText.substring(statementEnd + 1)
    result = Regex(
        """(?s)\s*ImmutableMultimap\.Builder\s*<[^;]+>\s+([A-Za-z_$][\w$]*)\s*=\s*ImmutableMultimap\.builder\(\)\s*;\s*(?:\s*\1\.put\s*\((?:[^;]|\r|\n)+?\)\s*;\s*)+\s*this\.defaultModifiers\s*=\s*\1\.build\(\)\s*;\s*"""
    ).replace(result, "\n")
    return result
}

internal fun StructuralRefactorPass.isItemPropertiesArgument(argument: String, propertyParams: Set<String>): Boolean {
    if (argument in propertyParams) return true
    if (argument.contains(".attributes(")) return false
    return argument.startsWith("new Properties(") ||
        argument.startsWith("new Item.Properties(") ||
        argument.startsWith("new net.minecraft.world.item.Item.Properties(")
}

internal fun StructuralRefactorPass.appendItemAttributesToPropertiesArgument(argument: String, attributesExpression: String): String =
    if (argument.contains(".attributes(")) argument else "$argument.attributes($attributesExpression)"

internal fun StructuralRefactorPass.legacyItemAttributeModifierLines(
    methodText: String,
    file: Path,
    mainClass: Path?,
    mainText: String,
    slotGroupExpression: String = "EquipmentSlotGroup.HAND",
    ownerSource: String = file.readText()
): List<String> {
    val modRef = modIdReferenceForGeneratedClass(mainClass, mainText, packageNameOf(file.readText()))
    return legacyAttributeModifierPutCalls(methodText).mapNotNull { args ->
        val attributeExpression = legacyReachAttributeExpression(args[0]) ?: return@mapNotNull null
        val modifierArgs = attributeModifierConstructorArgs(args[1]) ?: return@mapNotNull null
        val modifierExpression = when (modifierArgs.size) {
            3 -> {
                val amount = constructorSafeAttributeModifierArgument(modifierArgs[1], ownerSource) ?: return@mapNotNull null
                val operation = modifierArgs[2].trim()
                "new AttributeModifier(${modifierArgs[0].trim()}, $amount, $operation)"
            }
            4 -> {
                val modifierName = modifierArgs[1].trim().removeSurrounding("\"")
                if (modifierName.isBlank() || modifierArgs[1].trim().startsWith("\"").not()) return@mapNotNull null
                val idExpression = resourceLocationConstantForModifierName(ownerSource, modifierName)
                    ?: legacyResourceLocationModifierIdExpression(modifierArgs[0], ownerSource)
                    ?: "net.minecraft.resources.ResourceLocation.fromNamespaceAndPath($modRef, \"${modifierPathName(modifierName)}\")"
                val amount = constructorSafeAttributeModifierArgument(modifierArgs[2], ownerSource) ?: return@mapNotNull null
                val operation = legacyAttributeModifierOperationExpression(modifierArgs[3]) ?: return@mapNotNull null
                "new AttributeModifier($idExpression, $amount, $operation)"
            }
            else -> return@mapNotNull null
        }
        "\t\t\t.withModifierAdded($attributeExpression, $modifierExpression, $slotGroupExpression)"
    }.toList()
}

internal data class LegacyReachAttributeHelper(
    val file: Path,
    val methodName: String,
    val modifierLines: List<String>
)

internal data class LegacyReachAttributeOverrideMigration(
    val helperName: String,
    val modifierLines: List<String>
)

internal data class LegacyDynamicItemAttributeHelper(
    val file: Path,
    val methodName: String,
    val mapParameter: String,
    val replacementMethod: String,
    val calculationMethodNames: Set<String>
)

internal data class LegacyDynamicItemAttributeOverrideSource(
    val source: String,
    val helper: LegacyDynamicItemAttributeHelper
)

internal fun StructuralRefactorPass.collectLegacyStaticReachAttributeHelpers(
    sourceByFile: Map<Path, String>,
    mainClass: Path?,
    mainText: String
): Map<String, List<LegacyReachAttributeHelper>> {
    val helpers = linkedMapOf<String, MutableList<LegacyReachAttributeHelper>>()
    for ((file, source) in sourceByFile) {
        if (!source.contains("ImmutableMultimap") ||
            !source.contains("new AttributeModifier(") ||
            !source.contains("EquipmentSlot.MAINHAND")) {
            continue
        }
        for (method in javaMethodRangesIncludingDefault(source)) {
            val methodText = source.substring(method.range)
            if (!methodText.contains("ImmutableMultimap") || !methodText.contains("new AttributeModifier(")) continue
            val slotGroup = legacyReachSlotGroupExpression(methodText) ?: continue
            val modifierLines = legacyItemAttributeModifierLines(methodText, file, mainClass, mainText, slotGroup, source)
            if (modifierLines.isEmpty()) continue
            helpers.getOrPut(method.name) { mutableListOf() } += LegacyReachAttributeHelper(file, method.name, modifierLines)
        }
    }
    return helpers
}

internal fun StructuralRefactorPass.legacyStaticReachAttributeOverrideMigration(
    source: String,
    helpers: Map<String, List<LegacyReachAttributeHelper>>
): LegacyReachAttributeOverrideMigration? {
    val candidates = javaMethodRangesIncludingDefault(source)
        .filter { it.name == "getAttributeModifiers" }
        .mapNotNull { method ->
            val methodText = source.substring(method.range)
            staticReachAttributeOverrideMigration(methodText, helpers)
        }
        .toList()
    return candidates.singleOrNull()
}

internal fun StructuralRefactorPass.staticReachAttributeOverrideMigration(
    methodText: String,
    helpers: Map<String, List<LegacyReachAttributeHelper>>
): LegacyReachAttributeOverrideMigration? {
    val parameters = javaMethodParameters(methodText)
    val slotName = parameters.firstOrNull { simpleJavaTypeName(it.type) == "EquipmentSlot" }?.name ?: return null
    val stackName = parameters.firstOrNull { simpleJavaTypeName(it.type) == "ItemStack" }?.name ?: return null
    val body = javaMethodBodyText(methodText)?.trim() ?: return null
    val returnMatch = Regex("""^return\s+(?:this\.)?([A-Za-z_$][\w$]*)\s*\(""")
        .find(body)
        ?: return null
    val helperName = returnMatch.groupValues[1]
    val callOpen = body.indexOf('(', returnMatch.range.last - 1)
    val callClose = if (callOpen >= 0) findMatchingParen(body, callOpen) else -1
    if (callOpen < 0 || callClose < 0 || body.substring(callClose + 1).trim() != ";") return null
    val args = splitTopLevelJavaArgs(body.substring(callOpen + 1, callClose)).map { it.trim() }
    if (args.size != 2 || args[1] != slotName) return null
    val superCallPattern = Regex(
        """^super\.getAttributeModifiers\s*\(\s*${Regex.escape(slotName)}\s*,\s*${Regex.escape(stackName)}\s*\)$"""
    )
    if (!superCallPattern.matches(args[0])) return null
    val distinctHelpers = helpers[helperName]
        ?.distinctBy { it.modifierLines }
        ?: return null
    val helper = distinctHelpers.singleOrNull() ?: return null
    return LegacyReachAttributeOverrideMigration(helperName, helper.modifierLines)
}

internal fun StructuralRefactorPass.collectLegacyDynamicItemAttributeHelpers(
    sourceByFile: Map<Path, String>,
    mainClass: Path?,
    mainText: String
): Map<String, List<LegacyDynamicItemAttributeHelper>> {
    val helpers = linkedMapOf<String, MutableList<LegacyDynamicItemAttributeHelper>>()
    for ((file, source) in sourceByFile) {
        if (!source.contains("ImmutableMultimap") ||
            !source.contains("new AttributeModifier(") ||
            !source.contains("ItemStack") ||
            !source.contains("EquipmentSlot.MAINHAND")) {
            continue
        }
        for (method in javaMethodRangesIncludingDefault(source)) {
            val methodText = source.substring(method.range)
            val helper = legacyDynamicItemAttributeHelper(method, methodText, file, source, mainClass, mainText)
                ?: continue
            helpers.getOrPut(method.name) { mutableListOf() } += helper
        }
    }
    return helpers
}

internal fun StructuralRefactorPass.legacyDynamicItemAttributeHelper(
    method: JavaMethodRange,
    methodText: String,
    file: Path,
    ownerSource: String,
    mainClass: Path?,
    mainText: String
): LegacyDynamicItemAttributeHelper? {
    if (!methodText.contains("ImmutableMultimap") ||
        !methodText.contains("new AttributeModifier(") ||
        !methodText.contains("EquipmentSlot.MAINHAND")) {
        return null
    }
    val parameters = javaMethodParameters(methodText)
    val mapParameter = parameters.firstOrNull { rawJavaTypeName(it.type) == "Multimap" }?.name ?: return null
    val stackParameter = parameters.firstOrNull { simpleJavaTypeName(it.type) == "ItemStack" }?.name ?: return null
    val slotParameter = parameters.firstOrNull { simpleJavaTypeName(it.type) == "EquipmentSlot" }?.name ?: return null
    if (legacyReachSlotGroupExpression(methodText) != "EquipmentSlotGroup.MAINHAND") return null
    val prefix = Regex("""(?m)^([ \t]*)((?:(?:public|protected|private|final|synchronized|default)\s+)+)""")
        .find(methodText)
        ?: return null
    val modifiers = prefix.groupValues[2].trim()
    if (Regex("""\bstatic\b""").containsMatchIn(modifiers)) return null
    val methodIndent = prefix.groupValues[1]
    val modRef = explicitModIdReferenceForGeneratedClass(mainClass, mainText, packageNameOf(ownerSource), null)
    val modifierLines = legacyDynamicItemAttributeModifierLines(
        methodText = methodText,
        ownerSource = ownerSource,
        modIdExpression = modRef,
        mapParameter = mapParameter,
        stackParameter = stackParameter,
        slotParameter = slotParameter
    )
    if (modifierLines.isEmpty()) return null
    val calculationMethods = modifierLines.flatMap { line ->
        directJavaMethodCalls(line)
    }.filter { name ->
        name != method.name && canMigrateLegacyDynamicAttributeCalculationMethod(ownerSource, name, mapParameter)
    }.toSet()
    if (directJavaMethodCalls(modifierLines.joinToString("\n"))
            .any { name -> name != method.name && name !in calculationMethods }) {
        return null
    }
    val bodyIndent = "$methodIndent\t"
    val chainIndent = "$methodIndent\t\t"
    val replacement = buildString {
        append(methodIndent)
        append(modifiers)
        append(" ItemAttributeModifiers ")
        append(method.name)
        append("(ItemAttributeModifiers modifiers, ItemStack ")
        append(stackParameter)
        append(") {\n")
        append(bodyIndent)
        append("return modifiers\n")
        append(modifierLines.joinToString("\n") { "$chainIndent$it" })
        append("\n")
        append(chainIndent)
        append(";\n")
        append(methodIndent)
        append("}")
    }
    return LegacyDynamicItemAttributeHelper(file, method.name, mapParameter, replacement, calculationMethods)
}

internal fun StructuralRefactorPass.legacyDynamicItemAttributeModifierLines(
    methodText: String,
    ownerSource: String,
    modIdExpression: String?,
    mapParameter: String,
    stackParameter: String,
    slotParameter: String
): List<String> =
    legacyAttributeModifierPutCalls(methodText).mapNotNull { args ->
        val attributeExpression = legacyItemAttributeExpression(args[0]) ?: return@mapNotNull null
        val modifierArgs = attributeModifierConstructorArgs(args[1]) ?: return@mapNotNull null
        val modifierExpression = when (modifierArgs.size) {
            3 -> {
                val amount = migrateDynamicAttributeModifierAmount(
                    modifierArgs[1],
                    mapParameter,
                    stackParameter,
                    slotParameter
                ) ?: return@mapNotNull null
                val operation = legacyAttributeModifierOperationExpression(modifierArgs[2]) ?: return@mapNotNull null
                "new AttributeModifier(${modifierArgs[0].trim()}, $amount, $operation)"
            }
            4 -> {
                val modifierName = modifierArgs[1].trim().removeSurrounding("\"")
                if (modifierName.isBlank() || modifierArgs[1].trim().startsWith("\"").not()) return@mapNotNull null
                val legacyId = modifierArgs[0].trim()
                val idExpression = resourceLocationConstantForModifierName(ownerSource, modifierName)
                    ?: legacyResourceLocationModifierIdExpression(legacyId, ownerSource)
                    ?: legacyId.takeIf {
                        modIdExpression != null &&
                            Regex("""^[A-Z_$][A-Z0-9_$]*$""").matches(it) &&
                            Regex("""\bUUID\s+${Regex.escape(it)}\s*=\s*UUID\.fromString\(""").containsMatchIn(ownerSource)
                    }
                    ?: modIdExpression?.let {
                        "net.minecraft.resources.ResourceLocation.fromNamespaceAndPath($it, \"${modifierPathName(modifierName)}\")"
                    }
                    ?: return@mapNotNull null
                val amount = migrateDynamicAttributeModifierAmount(
                    modifierArgs[2],
                    mapParameter,
                    stackParameter,
                    slotParameter
                ) ?: return@mapNotNull null
                val operation = legacyAttributeModifierOperationExpression(modifierArgs[3]) ?: return@mapNotNull null
                "new AttributeModifier($idExpression, $amount, $operation)"
            }
            else -> return@mapNotNull null
        }
        ".withModifierAdded($attributeExpression, $modifierExpression, EquipmentSlotGroup.MAINHAND)"
    }.toList()

internal fun StructuralRefactorPass.migrateDirectLegacyItemAttributeOverride(
    source: String,
    file: Path,
    mainClass: Path?,
    mainText: String
): String? {
    if (!source.contains("getAttributeModifiers(EquipmentSlot") ||
        !source.contains("ImmutableMultimap") ||
        !source.contains("new AttributeModifier(")) {
        return null
    }
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private)\s+)(?:Multimap|ImmutableMultimap)\s*<[^;\r\n]+>\s+getAttributeModifiers\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{"""
    )
    val matches = methodPattern.findAll(maskJavaCommentsAndLiterals(source)).toList()
    if (matches.size != 1) return null
    val openBrace = source.indexOf('{', matches.single().range.last)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    if (openBrace < 0 || closeBrace < 0) return null
    val methodRange = matches.single().range.first..closeBrace
    val methodText = source.substring(methodRange)
    val params = javaMethodParameters(methodText)
    val slotName = params.firstOrNull { simpleJavaTypeName(it.type) == "EquipmentSlot" }?.name ?: return null
    val stackName = params.firstOrNull { simpleJavaTypeName(it.type) == "ItemStack" }?.name ?: return null
    val builderName = Regex("""\bImmutableMultimap\.Builder\s*<[^;]+>\s+([A-Za-z_$][\w$]*)\s*=\s*ImmutableMultimap\.builder\(\)\s*;""")
        .find(methodText)
        ?.groupValues
        ?.get(1)
        ?: return null
    if (!Regex("""\b${Regex.escape(builderName)}\.putAll\(""").containsMatchIn(methodText) ||
        !Regex("""\breturn\s+${Regex.escape(builderName)}\.build\(\)\s*;""").containsMatchIn(methodText)) {
        return null
    }
    val modRef = explicitModIdReferenceForGeneratedClass(mainClass, mainText, packageNameOf(file.readText()), null)
    var migratedMethod = methodText
    var changed = false
    migratedMethod = rewriteExecutableJavaCallWithOffset(migratedMethod, "put") { receiver, args, callOffset ->
        if (receiver != builderName || args.size != 2) return@rewriteExecutableJavaCallWithOffset null
        val slotGroup = nearestLegacyEquipmentSlotGroup(methodText, slotName, callOffset)
            ?: return@rewriteExecutableJavaCallWithOffset null
        val attributeExpression = legacyItemAttributeExpression(args[0]) ?: return@rewriteExecutableJavaCallWithOffset null
        val modifierExpression = legacyDirectItemAttributeModifierExpression(
            args[1],
            ownerSource = source,
            modIdExpression = modRef,
            stackParameter = stackName,
            slotParameter = slotName
        ) ?: return@rewriteExecutableJavaCallWithOffset null
        changed = true
        "modifiers = modifiers.withModifierAdded($attributeExpression, $modifierExpression, $slotGroup)"
    }
    if (!changed) return null

    migratedMethod = Regex(
        """(?m)^([ \t]*)ImmutableMultimap\.Builder\s*<[^;]+>\s+${Regex.escape(builderName)}\s*=\s*ImmutableMultimap\.builder\(\)\s*;\s*\r?\n[ \t]*${Regex.escape(builderName)}\.putAll\([^;\r\n]*\)\s*;\s*\r?\n"""
    ).replace(migratedMethod) { match ->
        "${match.groupValues[1]}ItemAttributeModifiers modifiers = super.getDefaultAttributeModifiers($stackName);\n"
    }
    migratedMethod = Regex("""\breturn\s+${Regex.escape(builderName)}\.build\(\)\s*;""")
        .replace(migratedMethod, "return modifiers;")
    migratedMethod = rewriteLegacySlotConditionsForItemAttributeGroups(migratedMethod, slotName)
    migratedMethod = Regex(
        """(?m)^([ \t]*)@Override\s*\r?\n[ \t]*public\s+Multimap\s*<[^;\r\n]+>\s+getAttributeModifiers\s*\(\s*EquipmentSlot\s+${Regex.escape(slotName)}\s*,\s*ItemStack\s+${Regex.escape(stackName)}\s*\)"""
    ).replace(migratedMethod) { match ->
        "${match.groupValues[1]}@Override\n${match.groupValues[1]}public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack $stackName)"
    }
    if (migratedMethod == methodText || migratedMethod.contains("$builderName.")) return null

    var result = source.replaceRange(methodRange, migratedMethod)
    result = removeUnusedSimpleImport(result, "com.google.common.collect.ImmutableMultimap", "ImmutableMultimap")
    result = removeUnusedSimpleImport(result, "com.google.common.collect.Multimap", "Multimap")
    result = removeUnusedSimpleImport(result, "net.minecraft.core.Holder", "Holder")
    result = removeUnusedSimpleImport(result, "net.minecraft.world.entity.ai.attributes.Attribute", "Attribute")
    result = addImportIfMissing(result, "net.minecraft.world.entity.EquipmentSlotGroup")
    result = addImportIfMissing(result, "net.minecraft.world.item.component.ItemAttributeModifiers")
    return cleanupRedundantBlankLines(result)
}

internal fun StructuralRefactorPass.legacyDirectItemAttributeModifierExpression(
    expression: String,
    ownerSource: String,
    modIdExpression: String?,
    stackParameter: String,
    slotParameter: String
): String? {
    val modifierArgs = attributeModifierConstructorArgs(expression) ?: return null
    return when (modifierArgs.size) {
        3 -> {
            val amount = migrateDynamicAttributeModifierAmount(
                modifierArgs[1],
                mapParameter = "__modporter_no_legacy_map__",
                stackParameter = stackParameter,
                slotParameter = slotParameter
            ) ?: return null
            val operation = legacyAttributeModifierOperationExpression(modifierArgs[2]) ?: return null
            "new AttributeModifier(${modifierArgs[0].trim()}, $amount, $operation)"
        }
        4 -> {
            val modifierName = modifierArgs[1].trim().removeSurrounding("\"")
            if (modifierName.isBlank() || modifierArgs[1].trim().startsWith("\"").not()) return null
            val legacyId = modifierArgs[0].trim()
            val idExpression = resourceLocationConstantForModifierName(ownerSource, modifierName)
                ?: legacyResourceLocationModifierIdExpression(legacyId, ownerSource)
                ?: modIdExpression?.let {
                    "net.minecraft.resources.ResourceLocation.fromNamespaceAndPath($it, \"${modifierPathName(modifierName)}\")"
                }
                ?: return null
            val amount = migrateDynamicAttributeModifierAmount(
                modifierArgs[2],
                mapParameter = "__modporter_no_legacy_map__",
                stackParameter = stackParameter,
                slotParameter = slotParameter
            ) ?: return null
            val operation = legacyAttributeModifierOperationExpression(modifierArgs[3]) ?: return null
            "new AttributeModifier($idExpression, $amount, $operation)"
        }
        else -> null
    }
}

internal fun StructuralRefactorPass.nearestLegacyEquipmentSlotGroup(methodText: String, slotName: String, beforeOffset: Int): String? {
    val slot = Regex.escape(slotName)
    val prefix = methodText.take(beforeOffset)
    val slotNames = listOf("MAINHAND", "OFFHAND", "FEET", "LEGS", "CHEST", "HEAD", "BODY")
    return slotNames
        .mapNotNull { slotConstant ->
            val patterns = listOf(
                Regex("""\b$slot\s*==\s*EquipmentSlot\.${Regex.escape(slotConstant)}\b"""),
                Regex("""\bEquipmentSlot\.${Regex.escape(slotConstant)}\s*==\s*$slot\b"""),
                Regex("""\b$slot\.equals\s*\(\s*EquipmentSlot\.${Regex.escape(slotConstant)}\s*\)"""),
                Regex("""\bEquipmentSlot\.${Regex.escape(slotConstant)}\.equals\s*\(\s*$slot\s*\)""")
            )
            patterns.mapNotNull { pattern -> pattern.findAll(prefix).lastOrNull()?.range?.first }
                .maxOrNull()
                ?.let { it to "EquipmentSlotGroup.$slotConstant" }
        }
        .maxByOrNull { it.first }
        ?.second
}

internal fun StructuralRefactorPass.rewriteLegacySlotConditionsForItemAttributeGroups(methodText: String, slotName: String): String {
    var result = methodText
    val slot = Regex.escape(slotName)
    listOf("MAINHAND", "OFFHAND", "FEET", "LEGS", "CHEST", "HEAD", "BODY").forEach { slotConstant ->
        val constant = Regex.escape(slotConstant)
        result = Regex("""if\s*\(\s*$slot\s*==\s*EquipmentSlot\.$constant\s*&&\s*""")
            .replace(result, "if (")
        result = Regex("""if\s*\(\s*EquipmentSlot\.$constant\s*==\s*$slot\s*&&\s*""")
            .replace(result, "if (")
        result = Regex("""if\s*\(\s*$slot\s*==\s*EquipmentSlot\.$constant\s*\)\s*\{""")
            .replace(result, "{")
        result = Regex("""if\s*\(\s*EquipmentSlot\.$constant\s*==\s*$slot\s*\)\s*\{""")
            .replace(result, "{")
    }
    return result
}

internal fun StructuralRefactorPass.legacyItemAttributeExpression(expression: String): String? {
    legacyReachAttributeExpression(expression)?.let { return it }
    val trimmed = expression.trim()
    return if (Regex("""^(?:net\.minecraft\.world\.entity\.ai\.attributes\.)?Attributes\.[A-Z0-9_]+$""")
            .matches(trimmed)) {
        trimmed.removePrefix("net.minecraft.world.entity.ai.attributes.")
    } else {
        null
    }
}

internal fun StructuralRefactorPass.migrateDynamicAttributeModifierAmount(
    expression: String,
    mapParameter: String,
    stackParameter: String,
    slotParameter: String
): String? {
    var migrated = replaceJavaIdentifier(expression.trim(), mapParameter, "modifiers")
    migrated = replaceJavaIdentifier(migrated, stackParameter, stackParameter)
    if (containsJavaIdentifier(migrated, slotParameter)) return null
    if (Regex("""\bsuper\s*\.""").containsMatchIn(migrated)) return null
    return migrated
}

internal fun StructuralRefactorPass.replaceLegacyDynamicItemAttributeOverride(
    source: String,
    helpers: Map<String, List<LegacyDynamicItemAttributeHelper>>
): LegacyDynamicItemAttributeOverrideSource? {
    var result = source
    val migrations = javaMethodRangesIncludingDefault(source)
        .filter { it.name == "getAttributeModifiers" }
        .mapNotNull { method ->
            val methodText = source.substring(method.range)
            legacyDynamicItemAttributeOverrideReplacement(methodText, helpers)?.let { method to it }
        }
        .toList()
    val (method, migration) = migrations.singleOrNull() ?: return null
    result = result.replaceRange(method.range, migration.first)
    return LegacyDynamicItemAttributeOverrideSource(result, migration.second)
}

internal fun StructuralRefactorPass.legacyDynamicItemAttributeOverrideReplacement(
    methodText: String,
    helpers: Map<String, List<LegacyDynamicItemAttributeHelper>>
): Pair<String, LegacyDynamicItemAttributeHelper>? {
    val parameters = javaMethodParameters(methodText)
    val slotName = parameters.firstOrNull { simpleJavaTypeName(it.type) == "EquipmentSlot" }?.name ?: return null
    val stackName = parameters.firstOrNull { simpleJavaTypeName(it.type) == "ItemStack" }?.name ?: return null
    val body = javaMethodBodyText(methodText)?.trim() ?: return null
    val returnMatch = Regex("""^return\s+(?:this\.)?([A-Za-z_$][\w$]*)\s*\(""")
        .find(body)
        ?: return null
    val helperName = returnMatch.groupValues[1]
    val callOpen = body.indexOf('(', returnMatch.range.last - 1)
    val callClose = if (callOpen >= 0) findMatchingParen(body, callOpen) else -1
    if (callOpen < 0 || callClose < 0 || body.substring(callClose + 1).trim() != ";") return null
    val args = splitTopLevelJavaArgs(body.substring(callOpen + 1, callClose)).map { it.trim() }
    if (args.size != 3 || args[1] != stackName || args[2] != slotName) return null
    val superCallPattern = Regex(
        """^super\.getAttributeModifiers\s*\(\s*${Regex.escape(slotName)}\s*,\s*${Regex.escape(stackName)}\s*\)$"""
    )
    if (!superCallPattern.matches(args[0])) return null
    val helper = helpers[helperName]
        ?.distinctBy { it.replacementMethod }
        ?.singleOrNull()
        ?: return null
    val indent = Regex("""(?m)^([ \t]*)(?:@\w+(?:\([^)]*\))?\s*\r?\n[ \t]*)*(?:public|protected|private)\s+""")
        .find(methodText)
        ?.groupValues
        ?.get(1)
        ?: ""
    val replacement = buildString {
        append(indent)
        append("@Override\n")
        append(indent)
        append("public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack ")
        append(stackName)
        append(") {\n")
        append(indent)
        append("\treturn this.")
        append(helperName)
        append("(super.getDefaultAttributeModifiers(")
        append(stackName)
        append("), ")
        append(stackName)
        append(");\n")
        append(indent)
        append("}")
    }
    return replacement to helper
}

internal fun StructuralRefactorPass.migrateLegacyItemAttributeConstructorToProperties(source: String): String {
    val className = classNameOfJavaSource(source) ?: return source
    val parentItem = Regex("""extends\s+([A-Za-z_$][\w$]*Item)\b""").find(source)
        ?.groupValues
        ?.get(1)
        ?: return source
    val baseAttributesFactory = when (parentItem) {
        "AxeItem", "HoeItem", "PickaxeItem", "ShovelItem" -> "net.minecraft.world.item.DiggerItem.createAttributes"
        else -> "$parentItem.createAttributes"
    }
    var result = source
    javaConstructorRanges(result, className).asReversed().forEach { constructor ->
        val constructorText = result.substring(constructor.range)
        val superMatch = Regex("""\bsuper\s*\(""").find(constructorText) ?: return@forEach
        val superStart = constructor.range.first + superMatch.range.first
        val superOpen = constructor.range.first + superMatch.range.last
        val superClose = findMatchingParen(result, superOpen)
        if (superClose < 0 || superClose > constructor.range.last) return@forEach
        val args = splitTopLevelJavaArgs(result.substring(superOpen + 1, superClose)).map { it.trim() }
        if (args.size != 4 || args[3].contains(".attributes(")) return@forEach
        val migratedProperties = "${args[3]}.attributes($baseAttributesFactory(${args[0]}, ${args[1]}, ${args[2]}))"
        val replacement = "super(${args[0]}, $migratedProperties)"
        result = result.substring(0, superStart) + replacement + result.substring(superClose + 1)
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyDynamicItemAttributeHelperFile(
    source: String,
    helpers: List<LegacyDynamicItemAttributeHelper>
): String? {
    var result = source
    for (helper in helpers) {
        val method = javaMethodRangesIncludingDefault(result).firstOrNull { candidate ->
            candidate.name == helper.methodName &&
                result.substring(candidate.range).contains("ImmutableMultimap") &&
                result.substring(candidate.range).contains("new AttributeModifier(")
        } ?: return null
        result = result.replaceRange(method.range, helper.replacementMethod)
        for (calculationMethod in helper.calculationMethodNames) {
            result = migrateLegacyDynamicAttributeCalculationMethod(result, calculationMethod, helper.mapParameter)
                ?: return null
        }
    }
    result = removeUnusedSimpleImport(result, "com.google.common.collect.ImmutableMultimap", "ImmutableMultimap")
    result = removeUnusedSimpleImport(result, "com.google.common.collect.Multimap", "Multimap")
    result = removeUnusedSimpleImport(result, "net.minecraft.core.Holder", "Holder")
    result = removeUnusedSimpleImport(result, "net.minecraft.world.entity.ai.attributes.Attribute", "Attribute")
    result = addImportIfMissing(result, "net.minecraft.world.entity.EquipmentSlotGroup")
    result = addImportIfMissing(result, "net.minecraft.world.item.component.ItemAttributeModifiers")
    result = cleanupRedundantBlankLines(result)
    return result
}

internal fun StructuralRefactorPass.canMigrateLegacyDynamicAttributeCalculationMethod(
    source: String,
    methodName: String,
    oldMapParameter: String
): Boolean =
    migrateLegacyDynamicAttributeCalculationMethod(source, methodName, oldMapParameter) != null

internal fun StructuralRefactorPass.migrateLegacyDynamicAttributeCalculationMethod(
    source: String,
    methodName: String,
    oldMapParameter: String
): String? {
    val method = javaMethodRangesIncludingDefault(source).singleOrNull { it.name == methodName } ?: return null
    val methodText = source.substring(method.range)
    if (!methodText.contains(oldMapParameter)) return null
    val parameters = javaMethodParameters(methodText)
    if (parameters.firstOrNull()?.name != oldMapParameter ||
        rawJavaTypeName(parameters.first().type) != "Multimap") {
        return null
    }
    var migratedMethod = Regex(
        """(?:com\.google\.common\.collect\.)?Multimap\s*<[^()]+?>\s+${Regex.escape(oldMapParameter)}\b"""
    ).replaceFirst(methodText, "ItemAttributeModifiers modifiers")
    migratedMethod = replaceLegacyAttributeMultimapStreams(migratedMethod, oldMapParameter)
    if (containsJavaIdentifier(migratedMethod, oldMapParameter)) return null
    return source.replaceRange(method.range, migratedMethod)
}

internal fun StructuralRefactorPass.replaceLegacyAttributeMultimapStreams(methodText: String, oldMapParameter: String): String {
    val escapedMap = Regex.escape(oldMapParameter)
    var result = methodText
    val streamIteratorPattern = Regex(
        """\b$escapedMap\.get\s*\(\s*([A-Za-z_$][\w$.]*)\s*\)\s*\.stream\s*\(\s*\)\s*\.iterator\s*\(\s*\)"""
    )
    result = streamIteratorPattern.replace(result) { match ->
        legacyAttributeModifierStreamExpression(match.groupValues[1], withIterator = true)
    }
    val iteratorPattern = Regex(
        """\b$escapedMap\.get\s*\(\s*([A-Za-z_$][\w$.]*)\s*\)\s*\.iterator\s*\(\s*\)"""
    )
    result = iteratorPattern.replace(result) { match ->
        legacyAttributeModifierStreamExpression(match.groupValues[1], withIterator = true)
    }
    val streamPattern = Regex(
        """\b$escapedMap\.get\s*\(\s*([A-Za-z_$][\w$.]*)\s*\)\s*\.stream\s*\(\s*\)"""
    )
    result = streamPattern.replace(result) { match ->
        legacyAttributeModifierStreamExpression(match.groupValues[1], withIterator = false)
    }
    return result
}

internal fun StructuralRefactorPass.legacyAttributeModifierStreamExpression(attributeExpression: String, withIterator: Boolean): String {
    val entry = "modporterAttributeEntry"
    val stream = "modifiers.modifiers().stream()" +
        ".filter($entry -> $entry.slot().test(EquipmentSlot.MAINHAND) && $entry.attribute().equals($attributeExpression))" +
        ".map($entry -> $entry.modifier())"
    return if (withIterator) "$stream.iterator()" else stream
}

internal fun StructuralRefactorPass.directJavaMethodCalls(source: String): Set<String> =
    Regex("""(?<![.\w$])(?:this\.)?([A-Za-z_$][\w$]*)\s*\(""")
        .findAll(source)
        .map { it.groupValues[1] }
        .filterNot { it in setOf("if", "for", "while", "switch", "return", "new", "AttributeModifier") }
        .toSet()

internal fun StructuralRefactorPass.replaceJavaIdentifier(source: String, oldName: String, newName: String): String =
    Regex("""(?<![\w$])${Regex.escape(oldName)}(?![\w$])""").replace(source, newName)

internal fun StructuralRefactorPass.containsJavaIdentifier(source: String, name: String): Boolean =
    Regex("""(?<![.\w$])${Regex.escape(name)}(?![\w$])""").containsMatchIn(source)

internal fun StructuralRefactorPass.rawJavaTypeName(type: String): String =
    type.trim()
        .substringBefore('<')
        .substringAfterLast('.')
        .trim()

internal fun StructuralRefactorPass.appendStaticAttributeModifiersToConstructorAttributes(
    source: String,
    modifierLines: List<String>
): String? {
    val className = classNameOfJavaSource(source) ?: return null
    var result = source
    var changed = false
    javaConstructorRanges(result, className).asReversed().forEach { constructor ->
        val constructorText = result.substring(constructor.range)
        val superMatch = Regex("""\bsuper\s*\(""").find(constructorText) ?: return@forEach
        val superStart = constructor.range.first + superMatch.range.first
        val superOpen = constructor.range.first + superMatch.range.last
        val superClose = findMatchingParen(result, superOpen)
        if (superClose < 0 || superClose > constructor.range.last) return@forEach
        val args = splitTopLevelJavaArgs(result.substring(superOpen + 1, superClose)).toMutableList()
        val attributesArgIndex = args.indexOfFirst { it.contains(".attributes(") && it.contains("createAttributes") }
        if (attributesArgIndex < 0) return@forEach
        val migratedArg = appendStaticAttributeModifiersToPropertiesArgument(args[attributesArgIndex], modifierLines)
            ?: return@forEach
        if (migratedArg == args[attributesArgIndex]) return@forEach
        args[attributesArgIndex] = migratedArg
        val replacement = "super(${args.joinToString(", ") { it.trim() }})"
        result = result.substring(0, superStart) + replacement + result.substring(superClose + 1)
        changed = true
    }
    return if (changed) result else null
}

internal fun StructuralRefactorPass.appendStaticAttributeModifiersToPropertiesArgument(
    propertiesArg: String,
    modifierLines: List<String>
): String? {
    val attributesIndex = propertiesArg.indexOf(".attributes(")
    if (attributesIndex < 0) return null
    val attributesOpen = attributesIndex + ".attributes".length
    val attributesClose = findMatchingParen(propertiesArg, attributesOpen)
    if (attributesClose < 0) return null
    val attributesBody = propertiesArg.substring(attributesOpen + 1, attributesClose)
    if (modifierLines.all { attributesBody.contains(it.trim()) }) return propertiesArg
    return propertiesArg.substring(0, attributesClose).trimEnd() +
        "\n" +
        modifierLines.joinToString("\n") +
        propertiesArg.substring(attributesClose)
}

internal fun StructuralRefactorPass.pruneUnusedLegacyReachAttributeHelpers(
    javaFiles: List<Path>,
    helperNames: Set<String>,
    dryRun: Boolean
): List<Change> {
    val changes = mutableListOf<Change>()
    for (helperName in helperNames) {
        val declaringFiles = javaFiles.filter { file ->
            val source = file.readText()
            javaMethodRangesIncludingDefault(source).any { method ->
                method.name == helperName &&
                    source.substring(method.range).contains("ImmutableMultimap") &&
                    source.substring(method.range).contains("new AttributeModifier(")
            }
        }
        if (declaringFiles.size != 1) continue
        val file = declaringFiles.single()
        val original = file.readText()
        val method = javaMethodRangesIncludingDefault(original).firstOrNull { candidate ->
            candidate.name == helperName &&
                original.substring(candidate.range).contains("ImmutableMultimap") &&
                original.substring(candidate.range).contains("new AttributeModifier(")
        } ?: continue
        val removalStart = leadingJavadocStart(original, method.range.first)
        val withoutMethod = removeRangeWithTrailingLineBreak(original, removalStart..method.range.last)
        if (javaFiles.any { path ->
                val text = if (path == file) withoutMethod else path.readText()
                Regex("""(?:\.|(?<![\w$]))${Regex.escape(helperName)}\s*\(""").containsMatchIn(text)
            }) {
            continue
        }
        var migrated = removeUnusedPrivateNoArgConstantMethods(withoutMethod)
        migrated = removeUnusedSimpleImport(migrated, "com.google.common.collect.ImmutableMultimap", "ImmutableMultimap")
        migrated = removeUnusedSimpleImport(migrated, "com.google.common.collect.Multimap", "Multimap")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.core.Holder", "Holder")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.world.entity.EquipmentSlot", "EquipmentSlot")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.world.entity.ai.attributes.Attribute", "Attribute")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.world.entity.ai.attributes.AttributeModifier", "AttributeModifier")
        migrated = removeUnusedSimpleImport(migrated, "net.minecraft.world.entity.ai.attributes.Attributes", "Attributes")
        migrated = removeUnusedSimpleImport(migrated, "net.neoforged.neoforge.common.NeoForgeMod", "NeoForgeMod")
        migrated = cleanupRedundantBlankLines(migrated)
        if (migrated != original) {
            if (!dryRun) file.writeText(migrated)
            changes.add(Change(
                file = file,
                line = 1,
                description = "Remove unused legacy item attribute helper after constructor component migration",
                before = "$helperName(...) returns an ImmutableMultimap attribute helper",
                after = "helper removed after all structural call sites were migrated",
                confidence = Confidence.HIGH,
                ruleId = "struct-item-attribute-helper-prune"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.legacyAttributeModifierPutCalls(methodText: String): List<List<String>> {
    val calls = mutableListOf<List<String>>()
    var cursor = 0
    while (true) {
        val tokenIndex = methodText.indexOf(".put(", cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + ".put".length
        val closeParen = findMatchingParen(methodText, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + ".put(".length
            continue
        }
        val args = splitTopLevelJavaArgs(methodText.substring(openParen + 1, closeParen))
        if (args.size == 2 && attributeModifierConstructorArgs(args[1]) != null) {
            calls += args
        }
        cursor = closeParen + 1
    }
    return calls
}

internal fun StructuralRefactorPass.attributeModifierConstructorArgs(expression: String): List<String>? {
    val trimmed = expression.trim()
    val constructor = Regex("""^new\s+(?:[A-Za-z_$][\w$]*\.)*AttributeModifier\s*\(""")
        .find(trimmed)
        ?: return null
    val openParen = trimmed.indexOf('(', constructor.range.last - 1)
    val closeParen = if (openParen >= 0) findMatchingParen(trimmed, openParen) else -1
    if (openParen < 0 || closeParen != trimmed.lastIndex) return null
    return splitTopLevelJavaArgs(trimmed.substring(openParen + 1, closeParen))
}

internal fun StructuralRefactorPass.legacyReachAttributeExpression(expression: String): String? =
    when (expression.trim()) {
        "NeoForgeMod.BLOCK_REACH.get()",
        "ForgeMod.BLOCK_REACH.get()",
        "NeoForgeMod.BLOCK_REACH",
        "ForgeMod.BLOCK_REACH",
        "Attributes.BLOCK_INTERACTION_RANGE" -> "Attributes.BLOCK_INTERACTION_RANGE"
        "NeoForgeMod.ENTITY_REACH.get()",
        "ForgeMod.ENTITY_REACH.get()",
        "NeoForgeMod.ENTITY_REACH",
        "ForgeMod.ENTITY_REACH",
        "Attributes.ENTITY_INTERACTION_RANGE" -> "Attributes.ENTITY_INTERACTION_RANGE"
        else -> {
            val trimmed = expression.trim()
            when {
                Regex("""^Attributes\.[A-Z][A-Z0-9_]*$""").matches(trimmed) -> trimmed
                Regex("""^NeoForgeMod\.[A-Z][A-Z0-9_]*(?:\.get\(\))?$""").matches(trimmed) ->
                    trimmed.removeSuffix(".get()")
                else -> null
            }
        }
    }

internal fun StructuralRefactorPass.legacyAttributeModifierOperationExpression(expression: String): String? {
    val trimmed = expression.trim()
    val operation = Regex("""^AttributeModifier\.Operation\.([A-Z_]+)$""")
        .find(trimmed)
        ?.groupValues
        ?.get(1)
        ?: return null
    return "AttributeModifier.Operation.$operation"
}

internal fun StructuralRefactorPass.resourceLocationConstantForModifierName(ownerSource: String, modifierName: String): String? {
    val target = normalizedModifierConstantName(modifierPathName(modifierName))
    val candidates = Regex(
        """(?m)\b(?:(?:public|protected|private|static|final)\s+)*(?:ResourceLocation|net\.minecraft\.resources\.ResourceLocation)\s+([A-Z_$][A-Z0-9_$]*)\b\s*="""
    ).findAll(ownerSource)
        .map { it.groupValues[1] }
        .filter { normalizedModifierConstantName(it) == target }
        .distinct()
        .toList()
    return candidates.singleOrNull()
}

internal fun StructuralRefactorPass.normalizedModifierConstantName(name: String): String =
    name.lowercase()
        .removeSuffix("_uuid")
        .removeSuffix("_id")

internal fun StructuralRefactorPass.legacyResourceLocationModifierIdExpression(expression: String, ownerSource: String): String? {
    val trimmed = expression.trim()
    when (trimmed) {
        "BASE_ATTACK_DAMAGE_UUID" -> return "net.minecraft.world.item.Item.BASE_ATTACK_DAMAGE_ID"
        "BASE_ATTACK_SPEED_UUID" -> return "net.minecraft.world.item.Item.BASE_ATTACK_SPEED_ID"
    }
    if (trimmed.startsWith("ResourceLocation.") || trimmed.startsWith("net.minecraft.resources.ResourceLocation.")) {
        return trimmed
    }
    if (!Regex("""^[A-Z_$][A-Z0-9_$]*$""").matches(trimmed)) return null
    val compactSource = ownerSource.replace(Regex("""\s+"""), " ")
    return if (compactSource.contains("ResourceLocation $trimmed =") ||
        compactSource.contains("net.minecraft.resources.ResourceLocation $trimmed =") ||
        compactSource.contains("$trimmed = ResourceLocation.") ||
        compactSource.contains("$trimmed = net.minecraft.resources.ResourceLocation.")) {
        trimmed
    } else {
        null
    }
}

internal fun StructuralRefactorPass.constructorSafeAttributeModifierArgument(expression: String, ownerSource: String): String? {
    val trimmed = expression.trim()
    val thisCall = Regex("""^this\.([A-Za-z_$][\w$]*)\s*\(\s*\)$""")
        .find(trimmed)
        ?.groupValues
        ?.get(1)
    if (thisCall != null) return noArgConstantReturnExpression(ownerSource, thisCall)
    val bareCall = Regex("""^([A-Za-z_$][\w$]*)\s*\(\s*\)$""")
        .find(trimmed)
        ?.groupValues
        ?.get(1)
    if (bareCall != null) return noArgConstantReturnExpression(ownerSource, bareCall)
    if (Regex("""\b(?:this|super)\b""").containsMatchIn(trimmed)) return null
    val directCalls = Regex("""(?<![\w$.])([a-z][A-Za-z0-9_$]*)\s*\(""")
        .findAll(trimmed)
        .map { it.groupValues[1] }
        .toSet()
    if (directCalls.any { !isStaticNoArgJavaMethod(ownerSource, it) }) return null
    return trimmed
}

internal fun StructuralRefactorPass.isStaticNoArgJavaMethod(source: String, methodName: String): Boolean =
    javaMethodRangesIncludingDefault(source).any { method ->
        method.name == methodName &&
            Regex("""\bstatic\b""").containsMatchIn(method.header) &&
            javaMethodParameters(source.substring(method.range)).isEmpty()
    }

internal fun StructuralRefactorPass.noArgConstantReturnExpression(source: String, methodName: String): String? {
    val method = javaMethodRangesIncludingDefault(source)
        .firstOrNull { it.name == methodName }
        ?: return null
    val methodText = source.substring(method.range)
    val parameters = javaMethodParameters(methodText)
    if (parameters.isNotEmpty()) return null
    val body = javaMethodBodyText(methodText)?.trim() ?: return null
    val returned = Regex("""^return\s+(.+?)\s*;\s*$""", RegexOption.DOT_MATCHES_ALL)
        .find(body)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: return null
    if (Regex("""\b(?:this|super)\b""").containsMatchIn(returned)) return null
    if (Regex("""(?<![\w$.])[a-z][A-Za-z0-9_$]*\s*\(""").containsMatchIn(returned)) return null
    return returned
}

internal fun StructuralRefactorPass.legacyReachSlotGroupExpression(methodText: String): String? {
    val slotName = javaMethodParameters(methodText).firstOrNull { simpleJavaTypeName(it.type) == "EquipmentSlot" }?.name
        ?: return null
    val slot = Regex.escape(slotName)
    val mainHandPatterns = listOf(
        Regex("""\b$slot\s*==\s*EquipmentSlot\.MAINHAND\b"""),
        Regex("""\bEquipmentSlot\.MAINHAND\s*==\s*$slot\b"""),
        Regex("""\b$slot\.equals\s*\(\s*EquipmentSlot\.MAINHAND\s*\)"""),
        Regex("""\bEquipmentSlot\.MAINHAND\.equals\s*\(\s*$slot\s*\)""")
    )
    return if (mainHandPatterns.any { it.containsMatchIn(methodText) }) {
        "EquipmentSlotGroup.MAINHAND"
    } else {
        null
    }
}

internal fun StructuralRefactorPass.javaMethodRangesIncludingDefault(source: String): List<JavaMethodRange> {
    val pattern = Regex(
        """(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|synchronized|default|abstract)\s+)*(?:<[^>{};]+>\s*)?(?:[@\w<>\[\].?,]+\s+)+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    val executableSource = maskJavaCommentsAndLiterals(source)
    return pattern.findAll(executableSource).mapNotNull { match ->
        // A record header and `return new Value(...) { ... }` are not methods. They
        // otherwise look like a return type followed by a name and parameters.
        val header = source.substring(match.range)
        val span = callableParameterSpan(header, header.indices) ?: return@mapNotNull null
        val prefix = executableSource.substring(match.range.first, match.range.first + span.first)
        if (Regex("""\b(?:class|interface|enum|record|new|return|throw)\s+${Regex.escape(match.groupValues[1])}\s*$""").containsMatchIn(prefix)) return@mapNotNull null
        val openBrace = executableSource.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableSource, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return@mapNotNull null
        JavaMethodRange(match.groupValues[1], source.substring(match.range), match.range.first..closeBrace)
    }.toList()
}

internal fun StructuralRefactorPass.javaConstructorRanges(source: String, className: String): List<JavaMethodRange> {
    val pattern = Regex(
        """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s*\r?\n[ \t]*)*(?:public|protected|private)\s+${Regex.escape(className)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    return pattern.findAll(source).mapNotNull { match ->
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return@mapNotNull null
        JavaMethodRange(className, match.value, match.range.first..closeBrace)
    }.toList()
}

internal fun StructuralRefactorPass.migrateNewItemStackComponentSetChains(source: String): String {
    if (!source.contains("ItemStack") || !source.contains(".set(")) return source
    val executableCode = maskJavaCommentsAndLiterals(source)
    val ctorPattern = Regex("""new\s+(?:net\.minecraft\.world\.item\.)?ItemStack\s*\(""")
    val componentPattern = Regex("""^(?:net\.minecraft\.core\.component\.)?DataComponents\.[A-Za-z_$][\w$]*$""")
    val edits = mutableListOf<Pair<IntRange, String>>()

    ctorPattern.findAll(executableCode).forEach { ctor ->
        val ctorOpenParen = executableCode.indexOf('(', ctor.range.first)
        if (ctorOpenParen < 0) return@forEach
        val ctorCloseParen = findMatchingParen(executableCode, ctorOpenParen)
        if (ctorCloseParen < 0) return@forEach
        val setDot = nextNonWhitespaceIndex(executableCode, ctorCloseParen + 1)
        if (setDot < 0 || !executableCode.startsWith(".set(", setDot)) return@forEach
        val setOpenParen = setDot + ".set".length
        val setCloseParen = findMatchingParen(executableCode, setOpenParen)
        if (setCloseParen < 0) return@forEach

        val args = splitTopLevelJavaArgs(source.substring(setOpenParen + 1, setCloseParen))
        if (args.size != 2) return@forEach
        val component = args[0].trim()
        if (!componentPattern.matches(component)) return@forEach

        val receiver = source.substring(ctor.range.first, ctorCloseParen + 1).trim()
        val value = args[1].trim()
        edits += ctor.range.first..setCloseParen to
            "net.minecraft.Util.make($receiver, modporterStack -> modporterStack.set($component, $value))"
    }

    return if (edits.isEmpty()) source else applyStringEdits(source, edits)
}

internal data class BlockCodecOverrideSignature(
    val visibility: String,
    val returnType: String
)

internal fun StructuralRefactorPass.blockCodecOverrideSignatureFor(
    declaration: JavaClassDeclaration,
    javaInheritanceIndex: JavaInheritanceIndex
): BlockCodecOverrideSignature {
    fun inherits(type: String): Boolean =
        declaration.directSuper.substringAfterLast('.') == type ||
            javaInheritanceIndex.inherits(declaration.directSuper, setOf(type))

    return when {
        inherits("CraftingTableBlock") -> BlockCodecOverrideSignature(
            "public",
            "? extends net.minecraft.world.level.block.CraftingTableBlock"
        )
        inherits("TntBlock") -> BlockCodecOverrideSignature(
            "public",
            "net.minecraft.world.level.block.TntBlock"
        )
        inherits("LightBlock") -> BlockCodecOverrideSignature(
            "public",
            "net.minecraft.world.level.block.LightBlock"
        )
        inherits("AnvilBlock") -> BlockCodecOverrideSignature(
            "public",
            "net.minecraft.world.level.block.AnvilBlock"
        )
        inherits("SweetBerryBushBlock") -> BlockCodecOverrideSignature(
            "public",
            "net.minecraft.world.level.block.SweetBerryBushBlock"
        )
        inherits("FallingBlock") -> BlockCodecOverrideSignature(
            "protected",
            "? extends net.minecraft.world.level.block.FallingBlock"
        )
        inherits("BushBlock") -> BlockCodecOverrideSignature(
            "protected",
            "? extends net.minecraft.world.level.block.BushBlock"
        )
        else -> BlockCodecOverrideSignature(
            "protected",
            "? extends net.minecraft.world.level.block.Block"
        )
    }
}

internal fun StructuralRefactorPass.migrateBlockCodecReturnToConcreteOwner(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("MapCodec<") || !source.contains(" codec(")) {
        return source
    }
    val classDeclaration = collectJavaClassDeclarations(source).firstOrNull() ?: return source
    if (classDeclaration.directSuper.isBlank()) return source
    if (!isBlockCodecOwner(classDeclaration, javaInheritanceIndex)) return source
    val signature = blockCodecOverrideSignatureFor(classDeclaration, javaInheritanceIndex)
    val executableCode = maskJavaCommentsAndLiterals(source)
    val codecFieldType = if (Regex("""\breturn\s+CODEC\s*;""").containsMatchIn(executableCode)) {
        Regex(
            """(?:com\.mojang\.serialization\.)?MapCodec\s*<\s*([^>{}]+?)\s*>\s+CODEC\s*="""
        ).find(executableCode)?.groupValues?.get(1)?.trim()
    } else {
        null
    }
    val targetReturnType = when {
        !signature.returnType.startsWith("? extends") -> signature.returnType
        codecFieldType != null -> codecFieldType
        else -> signature.returnType
    }
    var result = source
    result = replaceExecutableRegex(
        result,
        Regex(
            """(public|protected)\s+com\.mojang\.serialization\.MapCodec\s*<\s*\?\s+extends\s+[^>{}]+\s*>\s+codec\s*\("""
        )
    ) { match ->
        val visibility = if (signature.visibility == "public") "public" else match.groupValues[1]
        "$visibility com.mojang.serialization.MapCodec<$targetReturnType> codec("
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(public|protected)\s+MapCodec\s*<\s*\?\s+extends\s+[^>{}]+\s*>\s+codec\s*\(""")
    ) { match ->
        val visibility = if (signature.visibility == "public") "public" else match.groupValues[1]
        "$visibility MapCodec<$targetReturnType> codec("
    }
    if (result != source &&
        !targetReturnType.startsWith("? extends") &&
        codecFieldType != null &&
        codecFieldType != targetReturnType) {
        result = addSuppressWarningsToCodecMethod(result)
    }
    return result
}

internal fun StructuralRefactorPass.isBlockCodecOwner(
    declaration: JavaClassDeclaration,
    javaInheritanceIndex: JavaInheritanceIndex
): Boolean {
    val directSuper = declaration.directSuper.substringAfterLast('.')
    return directSuper.endsWith("Block") ||
        javaInheritanceIndex.inherits(declaration.directSuper, setOf("Block"))
}

internal fun StructuralRefactorPass.migrateAnimalIsFoodOverrides(javaFiles: List<Path>, dryRun: Boolean): List<Change> {
    val existingJavaFiles = javaFiles.filter { it.exists() }
    val javaInheritanceIndex = collectJavaInheritanceIndex(existingJavaFiles)
    val animalBaseTypes = setOf("Animal", "TamableAnimal")
    val changes = mutableListOf<Change>()
    for (javaFile in existingJavaFiles) {
        val source = javaFile.readText()
        val executableSource = maskJavaCommentsAndLiterals(source)
        if (!executableSource.contains("extends") ||
            hasItemStackIsFoodMethod(executableSource)) {
            continue
        }
        val declaration = collectJavaClassDeclarations(executableSource).firstOrNull() ?: continue
        if (!javaClassExtendsAny(declaration, animalBaseTypes, javaInheritanceIndex)) continue
        val classDeclaration = collectJavaClassDeclarations(source).firstOrNull() ?: continue
        val classBody = source.substring(classDeclaration.bodyRange.first + 1, classDeclaration.bodyRange.last)
        if (hasItemStackIsFoodMethod(classBody)) continue
        val itemExpressions = inferAnimalFoodItemExpressions(source)
        if (itemExpressions.isEmpty()) continue
        val migrated = addImportIfMissing(
            insertAnimalIsFoodOverride(source, classDeclaration, itemExpressions),
            "net.minecraft.world.item.ItemStack"
        )
        if (migrated == source) continue
        if (!dryRun) {
            javaFile.writeText(migrated)
        }
        changes.add(Change(
            file = javaFile,
            line = 0,
            description = "Add Animal#isFood override from existing player feeding item checks",
            before = "Animal subclass without isFood(ItemStack)",
            after = "isFood(ItemStack) delegates to inferred stack.is(...) item checks",
            confidence = Confidence.HIGH,
            ruleId = "struct-animal-isfood-from-feeding-checks"
        ))
    }
    return changes
}

internal fun StructuralRefactorPass.inferAnimalFoodItemExpressions(source: String): Set<String> {
    val items = linkedSetOf<String>()
    val interactionMethodNames = setOf("mobInteract", "interactAt", "interact")
    for (method in javaMethodRangesIncludingDefault(source)) {
        if (method.name !in interactionMethodNames) continue
        val methodText = source.substring(method.range)
        val params = javaMethodParameters(methodText)
        val hasPlayer = params.any { simpleJavaTypeName(it.type) == "Player" }
        val hasInteractionHand = params.any { simpleJavaTypeName(it.type) == "InteractionHand" }
        if (!hasPlayer || !hasInteractionHand) continue
        val executableMethodText = maskJavaCommentsAndLiterals(methodText)
        Regex("""\.is\s*\(\s*([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*(?:\s*\.\s*get\s*\(\s*\))?)\s*\)""")
            .findAll(executableMethodText)
            .forEach { match ->
                val expression = methodText.substring(match.groups[1]!!.range).replace(Regex("""\s+"""), "")
                if (isExplicitItemStackFoodExpression(expression)) {
                    items += expression
                }
            }
    }
    return items
}

internal fun StructuralRefactorPass.isExplicitItemStackFoodExpression(expression: String): Boolean =
    expression.startsWith("Items.") ||
        expression.startsWith("ItemTags.") ||
        (expression.endsWith(".get()") &&
            expression.substringBefore('.').let { owner ->
                owner.contains("Item") || owner.contains("Items")
            })

internal fun StructuralRefactorPass.insertAnimalIsFoodOverride(
    source: String,
    declaration: JavaClassDeclaration,
    itemExpressions: Set<String>
): String {
    val bodyEnd = declaration.bodyRange.last
    if (bodyEnd <= 0 || bodyEnd > source.length) return source
    val indent = Regex("""(?m)^([ \t]*)\}""")
        .findAll(source.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last))
        .lastOrNull()
        ?.groupValues
        ?.get(1)
        ?: "\t"
    val methodIndent = indent
    val bodyIndent = "$methodIndent\t"
    val condition = itemExpressions.joinToString(" || ") { "stack.is($it)" }
    val method = """

${methodIndent}@Override
${methodIndent}public boolean isFood(ItemStack stack) {
${bodyIndent}return $condition;
${methodIndent}}
""".trimEnd()
    return source.substring(0, bodyEnd) + method + "\n" + source.substring(bodyEnd)
}

internal fun StructuralRefactorPass.addSuppressWarningsToCodecMethod(source: String): String {
    var result = source
    val methodPattern = Regex(
        """(?m)^([ \t]*)@Override\s*\r?\n([ \t]*)(public|protected)\s+(?:com\.mojang\.serialization\.)?MapCodec\s*<[^>{}]+>\s+codec\s*\("""
    )
    methodPattern.find(result)?.let { match ->
        if (!result.substring(0, match.range.first).takeLast(80).contains("@SuppressWarnings(\"unchecked\")")) {
            result = result.substring(0, match.range.first) +
                "${match.groupValues[1]}@SuppressWarnings(\"unchecked\")\n" +
                result.substring(match.range.first)
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\breturn\s+CODEC\s*;""")
    ) { "return (com.mojang.serialization.MapCodec) CODEC;" }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyParticleRenderTypeSource(source: String): String {
    if (!source.contains("ParticleRenderType") || !source.contains("void begin(")) return source
    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        if (method.name != "begin") continue
        val methodText = result.substring(method.range)
        val params = javaMethodParameters(methodText)
        if (params.size != 2 ||
            simpleJavaTypeName(params[0].type) != "BufferBuilder" ||
            simpleJavaTypeName(params[1].type) != "TextureManager") {
            continue
        }
        val openBrace = methodText.indexOf('{')
        val closeBrace = findMatchingBrace(methodText, openBrace)
        if (openBrace < 0 || closeBrace < 0) continue
        val body = methodText.substring(openBrace + 1, closeBrace)
        val beginCall = Regex(
            """(?m)^([ \t]*)${Regex.escape(params[0].name)}\s*=\s*Tesselator\.getInstance\(\)\.begin\s*\(([^;\r\n]+)\)\s*;\s*$"""
        ).find(body) ?: continue
        val migratedBody = body.replaceRange(
            beginCall.range,
            "${beginCall.groupValues[1]}return tesselator.begin(${beginCall.groupValues[2].trim()});"
        )
        val header = methodText.substring(0, openBrace)
        val migratedHeader = header
            .replace(Regex("""\bvoid\s+begin\s*\("""),
                "BufferBuilder begin(")
            .replace(
                Regex("""BufferBuilder\s+${Regex.escape(params[0].name)}\s*,"""),
                "Tesselator tesselator,"
            )
        val migratedMethod = migratedHeader + "{" + migratedBody + "}"
        result = result.replaceRange(method.range, migratedMethod)
    }
    result = removeLegacyParticleRenderTypeEndMethods(result)
    return result
}

internal fun StructuralRefactorPass.removeLegacyParticleRenderTypeEndMethods(source: String): String {
    if (!source.contains("void end(")) return source
    var result = source
    javaMethodRangesIncludingDefault(result).asReversed()
        .filter { it.name == "end" }
        .forEach { method ->
            val methodText = result.substring(method.range)
            val params = javaMethodParameters(methodText)
            if (params.size == 1 && simpleJavaTypeName(params[0].type) == "Tesselator") {
                result = result.removeRange(method.range)
            }
        }
    return result
}

internal fun StructuralRefactorPass.qualifyImportsConflictingWithTopLevelType(source: String): String {
    val topLevel = classNameOfJavaSource(source) ?: return source
    val importMatch = Regex("""(?m)^[ \t]*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(topLevel)})\s*;\s*\r?\n""")
        .find(source)
        ?: return source
    val imported = importMatch.groupValues[1]
    var result = source.removeRange(importMatch.range)
    result = replaceExecutableJavaRegex(result, Regex("""\b${Regex.escape(topLevel)}\.""")) {
        "$imported."
    }
    return result
}

internal fun StructuralRefactorPass.migrateBlockEntitySaveToItemCalls(source: String): String {
    if (!source.contains(".saveToItem(")) return source
    val replacements = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(source)) {
        val header = source.substring(method.range.first, source.indexOf('{', method.range.first).takeIf { it >= 0 && it <= method.range.last } ?: continue)
        val providerExpression = Regex("""HolderLookup\.Provider\s+([A-Za-z_$][\w$]*)""")
            .find(header)
            ?.groupValues
            ?.get(1)
            ?: Regex("""(?:LevelReader|Level)\s+([A-Za-z_$][\w$]*)""")
                .find(header)
                ?.groupValues
                ?.get(1)
                ?.let { "$it.registryAccess()" }
            ?: continue
        val methodText = source.substring(method.range)
        Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.saveToItem\(\s*([^,();\r\n]+)\s*\)""")
            .findAll(methodText)
            .forEach { match ->
                val absoluteRange = (method.range.first + match.range.first)..(method.range.first + match.range.last)
                replacements += absoluteRange to "${match.groupValues[1]}.saveToItem(${match.groupValues[2].trim()}, $providerExpression)"
            }
    }
    if (replacements.isEmpty()) return source
    var result = source
    for ((range, replacement) in replacements.sortedByDescending { it.first.first }) {
        result = result.replaceRange(range, replacement)
    }
    return result
}

internal fun StructuralRefactorPass.migrateBlockEntitySaveWithFullMetadataCalls(source: String): String {
    if (!source.contains(".saveWithFullMetadata()")) return source
    val replacements = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(source)) {
        val methodText = source.substring(method.range)
        if (!methodText.contains(".saveWithFullMetadata()")) continue
        val providerExpression = javaMethodParameters(methodText)
            .firstOrNull { simpleJavaTypeName(it.type) == "Provider" && it.type.contains("HolderLookup") }
            ?.name
            ?: javaMethodParameters(methodText)
                .singleOrNull { simpleJavaTypeName(it.type) in setOf("Level", "LevelReader", "ServerLevel") }
                ?.name
                ?.let { "$it.registryAccess()" }
            ?: continue
        Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.saveWithFullMetadata\(\s*\)""")
            .findAll(methodText)
            .forEach { match ->
                val absoluteRange = (method.range.first + match.range.first)..(method.range.first + match.range.last)
                replacements += absoluteRange to "${match.groupValues[1]}.saveWithFullMetadata($providerExpression)"
            }
    }
    if (replacements.isEmpty()) return source
    var result = source
    for ((range, replacement) in replacements.sortedByDescending { it.first.first }) {
        result = result.replaceRange(range, replacement)
    }
    return result
}

internal fun StructuralRefactorPass.leadingJavadocStart(source: String, declarationStart: Int): Int {
    val before = source.substring(0, declarationStart)
    val trimmedEnd = before.trimEnd().length
    if (trimmedEnd <= 0 || !before.substring(0, trimmedEnd).endsWith("*/")) return declarationStart
    val commentStart = before.lastIndexOf("/**", trimmedEnd)
    if (commentStart < 0) return declarationStart
    if (before.substring(trimmedEnd, declarationStart).isNotBlank()) return declarationStart
    val lineStart = before.lastIndexOf('\n', commentStart).let { if (it < 0) 0 else it + 1 }
    return lineStart
}

internal fun StructuralRefactorPass.removeRangeWithTrailingLineBreak(source: String, range: IntRange): String {
    var end = range.last + 1
    if (end < source.length && source[end] == '\r') end++
    if (end < source.length && source[end] == '\n') end++
    return source.removeRange(range.first, end)
}

internal fun StructuralRefactorPass.removeUnusedPrivateNoArgConstantMethods(source: String): String {
    var result = source
    var changed: Boolean
    do {
        changed = false
        val removable = javaMethodRangesIncludingDefault(result).filter { method ->
            val methodText = result.substring(method.range)
            methodText.trimStart().startsWith("private ") &&
                javaMethodParameters(methodText).isEmpty() &&
                noArgConstantReturnExpression(result, method.name) != null
        }
        for (method in removable.asReversed()) {
            val removalStart = leadingJavadocStart(result, method.range.first)
            val withoutMethod = removeRangeWithTrailingLineBreak(result, removalStart..method.range.last)
            if (Regex("""(?:\.|(?<![\w$]))${Regex.escape(method.name)}\s*\(""").containsMatchIn(withoutMethod)) {
                continue
            }
            result = withoutMethod
            changed = true
        }
    } while (changed)
    return result
}

internal fun StructuralRefactorPass.javaMethodText(source: String, methodName: String): String? {
    val nameMatch = Regex("""\b${Regex.escape(methodName)}\s*\(""").find(source) ?: return null
    val openBrace = source.indexOf('{', nameMatch.range.last)
    if (openBrace < 0) return null
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace < 0) return null
    val lineStart = source.lastIndexOf('\n', nameMatch.range.first).let { if (it < 0) 0 else it + 1 }
    return source.substring(lineStart, closeBrace + 1)
}

internal fun StructuralRefactorPass.javaDeclaredMethodText(source: String, methodName: String): String? {
    val declaration = Regex(
        """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+${Regex.escape(methodName)}\s*\("""
    ).find(source) ?: return null
    val openBrace = source.indexOf('{', declaration.range.last)
    if (openBrace < 0) return null
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace < 0) return null
    return source.substring(declaration.range.first, closeBrace + 1)
}

internal fun StructuralRefactorPass.addSuppressWarningToMethodsContaining(
    source: String,
    warning: String,
    predicate: (String) -> Boolean
): String {
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\s*\r?\n[ \t]*)*(?:public|protected|private)\s+(?:static\s+)?(?:<[^>{};]+>\s*)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    val starts = methodPattern.findAll(source)
        .mapNotNull { match ->
            val openBrace = source.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            if (openBrace < 0 || closeBrace < 0) return@mapNotNull null
            val methodText = source.substring(match.range.first, closeBrace + 1)
            if (predicate(methodText)) match.range.first else null
        }
        .toList()
    var result = source
    for (start in starts.asReversed()) {
        result = addSuppressWarningToElementAt(result, start, warning)
    }
    return result
}

internal fun StructuralRefactorPass.addSuppressWarningToConstructorsContaining(
    source: String,
    className: String,
    warning: String,
    tokens: List<String>
): String =
    addSuppressWarningToConstructorsMatching(source, className, warning) { constructorText ->
        tokens.any { constructorText.contains(it) }
    }

internal fun StructuralRefactorPass.addSuppressWarningToConstructorsMatching(
    source: String,
    className: String,
    warning: String,
    predicate: (String) -> Boolean
): String {
    val constructorPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\s*\r?\n[ \t]*)*(?:public|protected|private)\s+${Regex.escape(className)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    val starts = constructorPattern.findAll(source)
        .mapNotNull { match ->
            val openBrace = source.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            if (openBrace < 0 || closeBrace < 0) return@mapNotNull null
            val constructorText = source.substring(match.range.first, closeBrace + 1)
            if (predicate(constructorText)) match.range.first else null
        }
        .toList()
    var result = source
    for (start in starts.asReversed()) {
        result = addSuppressWarningToElementAt(result, start, warning)
    }
    return result
}

internal fun StructuralRefactorPass.addSuppressWarningToElementAt(source: String, elementStart: Int, warning: String): String {
    if (elementStart !in source.indices) return source
    val lineEnd = source.indexOf('\n', elementStart).let { if (it < 0) source.length else it }
    val firstLine = source.substring(elementStart, lineEnd)
    val indent = firstLine.takeWhile { it == ' ' || it == '\t' }
    val declarationStart = Regex("""(?m)^[ \t]*(?:public|protected|private|class)\b""")
        .find(source, elementStart)
        ?.range
        ?.first
        ?.takeIf { it >= elementStart }
        ?: elementStart
    val annotationBlock = source.substring(elementStart, declarationStart)
    val suppressMatch = Regex("""@SuppressWarnings\s*\(\s*(\{[^)]*}|"[^"]*")\s*\)""")
        .find(annotationBlock)
    if (suppressMatch != null) {
        if (suppressMatch.value.contains("\"$warning\"")) return source
        val absoluteStart = elementStart + suppressMatch.range.first
        val absoluteEnd = elementStart + suppressMatch.range.last + 1
        val body = suppressMatch.groupValues[1].trim()
        val replacement = if (body.startsWith("{")) {
            val inner = body.removePrefix("{").removeSuffix("}").trim()
            "@SuppressWarnings({$inner, \"$warning\"})"
        } else {
            "@SuppressWarnings({$body, \"$warning\"})"
        }
        return source.substring(0, absoluteStart) + replacement + source.substring(absoluteEnd)
    }
    return source.substring(0, elementStart) +
        "$indent@SuppressWarnings(\"$warning\")\n" +
        source.substring(elementStart)
}

internal fun StructuralRefactorPass.modifierPathName(text: String): String =
    text.trim()
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "_")
        .trim('_')

internal fun StructuralRefactorPass.migratePayloadContextServerPlayer(source: String): String {
    if (!source.contains(".player()") || !source.contains("ServerPlayer")) return source
    return Regex(
        """(?m)^([ \t]*)ServerPlayer\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.player\(\);\s*\r?\n\1if\s*\(\s*\2\s*==\s*null\s*\)\s*return\s*;"""
    ).replace(source) { match ->
        "${match.groupValues[1]}if (!(${match.groupValues[3]}.player() instanceof ServerPlayer ${match.groupValues[2]})) return;"
    }
}

internal fun StructuralRefactorPass.migrateSupplierValueCalls(source: String): String {
    if (!source.contains(".value()") || !source.contains("Supplier<")) return source
    val supplierVariables = Regex("""\bSupplier\s*<[^;=\r\n]+>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (supplierVariables.isEmpty()) return source
    var result = source
    supplierVariables.forEach { variable ->
        result = Regex("""\b${Regex.escape(variable)}\.value\(\)""").replace(result, "$variable.get()")
        result = Regex("""\bthis\.${Regex.escape(variable)}\.value\(\)""").replace(result, "this.$variable.get()")
    }
    return result
}

internal fun StructuralRefactorPass.migrateNeoForgeRegistriesValueAccess(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("NeoForgeRegistries.") || !executableCode.contains(".get().")) return source
    return replaceExecutableRegex(
        source,
        Regex("""\bNeoForgeRegistries\.([A-Z][A-Z0-9_]*)\.get\(\)\.""")
    ) { match -> "NeoForgeRegistries.${match.groupValues[1]}." }
}

internal fun StructuralRefactorPass.migrateBucketItemSupplierConstructors(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains("super(") || !source.contains("Supplier")) return source

    var result = source
    var cursor = 0
    var changed = false
    while (true) {
        val tokenIndex = result.indexOf("super(", cursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0 && (result[tokenIndex - 1].isLetterOrDigit() || result[tokenIndex - 1] == '_' || result[tokenIndex - 1] == '.')) {
            cursor = tokenIndex + "super(".length
            continue
        }
        val classDeclaration = enclosingJavaClassDeclaration(result, tokenIndex)
        val directSuper = classDeclaration?.directSuper?.substringAfterLast('.')
        if (directSuper != "BucketItem" && directSuper != "MobBucketItem") {
            cursor = tokenIndex + "super(".length
            continue
        }
        if (!javaClassExtendsAny(classDeclaration, setOf(directSuper), javaInheritanceIndex)) {
            cursor = tokenIndex + "super(".length
            continue
        }
        val constructorParams = enclosingJavaConstructorParameters(result, classDeclaration.name, tokenIndex)
        if (constructorParams == null) {
            cursor = tokenIndex + "super(".length
            continue
        }
        val openParen = tokenIndex + "super".length
        val closeParen = findMatchingParen(result, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + "super(".length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen)).map { it.trim() }
        val replacementArgs = when (directSuper) {
            "BucketItem" -> migrateBucketItemSuperArgs(args, constructorParams)
            "MobBucketItem" -> migrateMobBucketItemSuperArgs(args, constructorParams)
            else -> null
        }
        if (replacementArgs == null || replacementArgs == args) {
            cursor = closeParen + 1
            continue
        }
        val replacement = "super(${replacementArgs.joinToString(", ")})"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
        changed = true
    }

    return if (changed) result else source
}

internal fun StructuralRefactorPass.enclosingJavaConstructorParameters(
    source: String,
    className: String,
    index: Int
): Map<String, JavaParameterInfo>? {
    val constructorPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private)\s+)?${Regex.escape(className)}\s*\(([^;{}]*)\)\s*(?:throws\s+[^{]+)?\{"""
    )
    return constructorPattern.findAll(source).mapNotNull { match ->
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0 || index !in openBrace..closeBrace) {
            null
        } else {
            parseJavaParameters(match.groupValues[1]).associateBy { it.name }
        }
    }.firstOrNull()
}

internal fun StructuralRefactorPass.migrateBucketItemSuperArgs(
    args: List<String>,
    constructorParams: Map<String, JavaParameterInfo>
): List<String>? {
    if (args.size != 2) return null
    val fluidArg = args[0]
    val fluidParam = constructorParams[fluidArg] ?: return null
    if (!fluidParam.isSupplierParameterOf("Fluid")) return null
    return listOf("$fluidArg.get()", args[1])
}

internal fun StructuralRefactorPass.migrateMobBucketItemSuperArgs(
    args: List<String>,
    constructorParams: Map<String, JavaParameterInfo>
): List<String>? {
    if (args.size == 4) {
        val entityArg = args[0]
        val fluidArg = args[1]
        val soundArg = args[2]
        if (!constructorParams.isSupplierArgument(entityArg, "EntityType") ||
            !constructorParams.isSupplierArgument(fluidArg, "Fluid") ||
            !constructorParams.isSupplierArgument(soundArg, "SoundEvent")) {
            return null
        }
        return listOf("$entityArg.get()", "$fluidArg.get()", "$soundArg.get()", args[3])
    }

    if (args.size != 2) return null
    val entityArg = args[0]
    if (!constructorParams.isSupplierArgument(entityArg, "EntityType")) return null
    val attributes = legacyMobBucketAttributesExpression(args[1]) ?: return null
    val attributeArgs = attributes.second.map { it.trim() }
    if (attributeArgs.size != 3) return null
    val fluidArg = attributeArgs[1]
    val soundArg = attributeArgs[2]
    if (attributeArgs[0] != entityArg ||
        !constructorParams.isSupplierArgument(fluidArg, "Fluid") ||
        !constructorParams.isSupplierArgument(soundArg, "SoundEvent")) {
        return null
    }
    return listOf("$entityArg.get()", "$fluidArg.get()", "$soundArg.get()", attributes.first)
}

internal fun StructuralRefactorPass.migrateDirectMobBucketItemConstructors(source: String): String {
    if (!source.contains("new MobBucketItem(")) return source
    return rewriteExecutableJavaNew(source, "MobBucketItem") { args ->
        if (args.size != 4) return@rewriteExecutableJavaNew null
        val fluid = legacySupplierValueExpression(args[1]) ?: return@rewriteExecutableJavaNew null
        val sound = legacySupplierValueExpression(args[2]) ?: return@rewriteExecutableJavaNew null
        val entity = legacyMobBucketEntityTypeExpression(args[0]) ?: return@rewriteExecutableJavaNew null
        "new MobBucketItem($entity, $fluid, ${unwrapVanillaHolderSoundEventArgument(sound)}, ${args[3].trim()})"
    }
}

internal fun StructuralRefactorPass.legacySupplierValueExpression(expression: String): String? {
    val trimmed = expression.trim()
    Regex("""^\(\s*\)\s*->\s*(.+)$""")
        .find(trimmed)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.let { return it }
    Regex("""^Supplier\s*\.\s*of\s*\(\s*(.+)\s*\)$""")
        .find(trimmed)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.let { return it }
    return null
}

internal fun StructuralRefactorPass.legacyMobBucketEntityTypeExpression(expression: String): String? {
    val trimmed = expression.trim()
    if (trimmed.endsWith(".get()") || trimmed.startsWith("EntityType.")) return trimmed
    if (!Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""").matches(trimmed)) return null
    return "$trimmed.get()"
}

internal fun StructuralRefactorPass.migrateLegacyItemMaxStackSizeCalls(source: String): String {
    if (!source.contains(".getMaxStackSize()")) return source
    val itemVariables = javaDeclaredSimpleTypeSets(source)
        .filterValues { "Item" in it }
        .keys
    if (itemVariables.isEmpty()) return source
    var result = source
    itemVariables.forEach { variable ->
        result = replaceExecutableRegex(
            result,
            Regex("""(?<![\w$])${Regex.escape(variable)}\.getMaxStackSize\(\)""")
        ) {
            "$variable.getDefaultInstance().getMaxStackSize()"
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyShearableCallSitesSource(source: String): String {
    if (!source.contains("IShearable") || (!source.contains(".isShearable(") && !source.contains(".onSheared("))) {
        return source
    }
    val shearableVariables = Regex("""instanceof\s+IShearable\s+([A-Za-z_$][\w$]*)""")
        .findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet()
    if (shearableVariables.isEmpty()) return source
    var result = source
    for (method in javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result)).asReversed()) {
        val methodText = result.substring(method.range)
        val player = javaMethodParameters(methodText)
            .singleOrNull { simpleJavaTypeName(it.type) == "Player" }
            ?.name
            ?: continue
        var replacement = methodText
        shearableVariables.forEach { variable ->
            replacement = rewriteExecutableJavaCall(replacement, "isShearable") { receiver, args ->
                if (receiver == variable && args.size == 3) {
                    "$receiver.isShearable($player, ${args.joinToString(", ") { it.trim() }})"
                } else {
                    null
                }
            }
            replacement = rewriteExecutableJavaCall(replacement, "onSheared") { receiver, args ->
                if (receiver == variable && args.size == 5) {
                    "$receiver.onSheared(${args.take(4).joinToString(", ") { it.trim() }})"
                } else {
                    null
                }
            }
        }
        if (replacement != methodText) {
            result = result.substring(0, method.range.first) + replacement + result.substring(method.range.last + 1)
        }
    }
    return result
}

internal fun StructuralRefactorPass.legacyMobBucketAttributesExpression(expression: String): Pair<String, List<String>>? {
    val trimmed = expression.trim()
    val attributesIndex = trimmed.indexOf(".attributes(")
    if (attributesIndex <= 0) return null
    val attributesOpen = attributesIndex + ".attributes".length
    val attributesClose = findMatchingParen(trimmed, attributesOpen)
    if (attributesClose != trimmed.lastIndex) return null
    val propertiesExpression = trimmed.substring(0, attributesIndex).trim()
    val attributesArg = trimmed.substring(attributesOpen + 1, attributesClose).trim()
    val createAttributesMatch = Regex("""^(?:(?:[A-Za-z_$][\w$]*\.)*)createAttributes\s*\(""").find(attributesArg)
        ?: return null
    val createOpen = attributesArg.indexOf('(', createAttributesMatch.range.last)
    val createClose = findMatchingParen(attributesArg, createOpen)
    if (createOpen < 0 || createClose != attributesArg.lastIndex) return null
    return propertiesExpression to splitTopLevelJavaArgs(attributesArg.substring(createOpen + 1, createClose))
}

internal fun Map<String, JavaParameterInfo>.isSupplierArgument(argument: String, simpleTypeName: String): Boolean =
    this[argument.trim()]?.isSupplierParameterOf(simpleTypeName) == true

internal fun JavaParameterInfo.isSupplierParameterOf(simpleTypeName: String): Boolean {
    val normalized = type.replace(Regex("""\s+"""), " ")
    if (!Regex("""(?:^|[.\s])Supplier\s*<""").containsMatchIn(normalized)) return false
    return Regex("""(?<![\w$])${Regex.escape(simpleTypeName)}(?:\s*<|\b)""").containsMatchIn(normalized)
}

internal fun StructuralRefactorPass.migrateBucketItemCanBlockContainFluidSource(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("canBlockContainFluid(") ||
        !executableCode.contains("canPlaceLiquid(") ||
        !executableCode.contains("extends") ||
        collectJavaClassDeclarations(executableCode).none { javaClassExtendsAny(it, setOf("BucketItem"), javaInheritanceIndex) }) {
        return source
    }

    var result = rewriteCanBlockContainFluidCallSitesWithPlayer(source)
    val helperPattern = Regex(
        """(?m)^([ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private)\s+)?)boolean\s+canBlockContainFluid\s*\(\s*(?:Level|LevelAccessor|BlockGetter)\s+([A-Za-z_$][\w$]*)\s*,\s*BlockPos\s+([A-Za-z_$][\w$]*)\s*,\s*BlockState\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    var changed = result != source
    val executableResult = maskJavaCommentsAndLiterals(result)
    helperPattern.findAll(executableResult).toList().asReversed().forEach { match ->
        val openBrace = executableResult.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableResult, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return@forEach
        val levelName = match.groupValues[2]
        val posName = match.groupValues[3]
        val stateName = match.groupValues[4]
        val body = result.substring(openBrace + 1, closeBrace)
        val rewrittenBody = rewriteExecutableJavaCall(body, "canPlaceLiquid") { receiver, args ->
            val fluidArg = when {
                args.size == 4 &&
                    args[0].trim() == levelName &&
                    args[1].trim() == posName &&
                    args[2].trim() == stateName -> args[3].trim()
                args.size == 5 &&
                    args[0].trim() == "null" &&
                    args[1].trim() == levelName &&
                    args[2].trim() == posName &&
                    args[3].trim() == stateName -> args[4].trim()
                else -> return@rewriteExecutableJavaCall null
            }
            "$receiver.canPlaceLiquid(player, $levelName, $posName, $stateName, $fluidArg)"
        }
        if (rewrittenBody == body) return@forEach
        val replacement = "${match.groupValues[1]}boolean canBlockContainFluid(Player player, BlockGetter $levelName, BlockPos $posName, BlockState $stateName) {$rewrittenBody}"
        result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        changed = true
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.world.entity.player.Player")
    result = addImportIfMissing(result, "net.minecraft.world.level.BlockGetter")
    return result
}

internal fun StructuralRefactorPass.rewriteCanBlockContainFluidCallSitesWithPlayer(source: String): String {
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|static|final|synchronized)\s+)+(?:<[^>{};]+>\s*)?[A-Za-z_$][\w$<>\[\].?,\s]*\s+[A-Za-z_$][\w$]*\s*\(([^;{}]*)\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    var result = source
    val executableCode = maskJavaCommentsAndLiterals(source)
    methodPattern.findAll(executableCode).toList().asReversed().forEach { match ->
        val params = parseJavaParameters(match.groupValues[1])
        val playerName = params.firstOrNull { simpleJavaTypeName(it.type) == "Player" }?.name ?: return@forEach
        val openBrace = executableCode.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return@forEach
        val body = result.substring(openBrace + 1, closeBrace)
        var rewrittenBody = rewriteExecutableJavaCall(body, "canBlockContainFluid") { receiver, args ->
            if (args.size != 3 || (receiver != "this" && receiver != "super")) return@rewriteExecutableJavaCall null
            "$receiver.canBlockContainFluid($playerName, ${args.joinToString(", ") { it.trim() }})"
        }
        rewrittenBody = rewriteUnqualifiedCanBlockContainFluidCalls(rewrittenBody, playerName)
        if (rewrittenBody != body) {
            result = result.substring(0, openBrace + 1) + rewrittenBody + result.substring(closeBrace)
        }
    }
    return result
}

internal fun StructuralRefactorPass.rewriteUnqualifiedCanBlockContainFluidCalls(source: String, playerName: String): String {
    var result = source
    var cursor = 0
    val methodName = "canBlockContainFluid"
    val token = "$methodName("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val previous = if (tokenIndex > 0) executableCode[tokenIndex - 1] else '\u0000'
        if (previous == '.' || previous.isLetterOrDigit() || previous == '_' || previous == '$') {
            cursor = tokenIndex + token.length
            continue
        }
        val openParen = tokenIndex + methodName.length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen)).map { it.trim() }
        if (args.size != 3) {
            cursor = closeParen + 1
            continue
        }
        val replacement = "$methodName($playerName, ${args.joinToString(", ")})"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.migrateUnboundLevelRegistryAccessCalls(source: String): String {
    val token = "level.registryAccess()"
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(token)) return source
    val edits = mutableListOf<Pair<IntRange, String>>()
    val methodPattern = Regex(
        """(?m)(?:^|\r?\n)[ \t]*(?:(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*)(?:(?:public|protected|private|static|final|synchronized)\s+)+[A-Za-z_$][\w$<>\[\].?,\s]*\s+[A-Za-z_$][\w$]*\s*\(([^()]*)\)\s*\{"""
    )
    methodPattern.findAll(executableCode).forEach { match ->
        val openBrace = executableCode.indexOf('{', match.range.last - 1)
        if (openBrace < 0) return@forEach
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) return@forEach
        val methodText = executableCode.substring(match.range.first, closeBrace + 1)
        if (!methodText.contains(token)) return@forEach
        val parameters = splitTopLevelJavaArgs(match.groupValues[1])
        val registryAccess = registryAccessFromParameters(parameters) ?: return@forEach
        val hasLevelBinding = Regex(
            """\b(?:ServerLevel|Level|WorldGenLevel|ServerLevelAccessor|LevelAccessor|LevelReader)\s+level\b"""
        ).containsMatchIn(methodText)
        if (hasLevelBinding) return@forEach
        var cursor = openBrace + 1
        while (cursor < closeBrace) {
            val index = executableCode.indexOf(token, cursor)
            if (index < 0 || index > closeBrace) break
            edits += index until index + token.length to registryAccess
            cursor = index + token.length
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateFoodComponentAccess(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains(".getFoodProperties()") &&
        !source.contains(".getNutrition()") &&
        !source.contains(".getItem().has(") &&
        !source.contains(".isEdible()") &&
        !source.contains("isEdible()") &&
        !source.contains(".has(")) {
        return source
    }
    var result = migrateLegacyItemIsEdibleOverrides(source, javaInheritanceIndex)
    result = migrateLegacyItemIsEdibleCalls(result, javaInheritanceIndex)
    val itemStackVariables = Regex("""\bItemStack\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    val itemVariables = Regex("""\bItem\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    result = Regex(
        """(?m)^([ \t]*)Item\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]+);\s*\r?\n\1FoodProperties\s+([A-Za-z_$][\w$]*)\s*=\s*\2\.getFoodProperties\(\);"""
    ).replace(result) { match ->
        "${match.groupValues[1]}ItemStack stack = new ItemStack(${match.groupValues[3].trim()});\n" +
            "${match.groupValues[1]}FoodProperties ${match.groupValues[4]} = stack.getFoodProperties(null);"
    }
    result = migrateLegacyNoArgFoodPropertiesCalls(result, itemVariables)
    result = migrateLegacyFoodDataEatItemStackCalls(result)
    for (variable in itemStackVariables) {
        result = Regex("""\b${Regex.escape(variable)}\.getItem\(\)\.has\(\s*((?:net\.minecraft\.core\.component\.)?DataComponents\.[A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match -> "$variable.has(${match.groupValues[1]})" }
    }
    result = migrateItemFoodComponentHasCalls(result, itemVariables, javaInheritanceIndex)
    result = result.replace(".getNutrition()", ".nutrition()")
    if (result.contains("DataComponents.FOOD")) {
        result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyNoArgFoodPropertiesCalls(source: String, itemVariables: Set<String>): String {
    if (!source.contains(".getFoodProperties()")) return source
    return rewriteJavaCallWithOffset(source, "getFoodProperties") { receiver, args, offset ->
        if (args.isNotEmpty()) return@rewriteJavaCallWithOffset null
        val entity = nearestFoodPropertyEntityArgument(source, offset) ?: "null"
        val trimmedReceiver = receiver.trim()
        if (trimmedReceiver.endsWith(".getItem()")) {
            val stackReceiver = trimmedReceiver.removeSuffix(".getItem()")
            return@rewriteJavaCallWithOffset "$stackReceiver.getFoodProperties($entity)"
        }
        if (trimmedReceiver in itemVariables) {
            return@rewriteJavaCallWithOffset "$trimmedReceiver.getDefaultInstance().getFoodProperties($entity)"
        }
        null
    }
}

internal fun StructuralRefactorPass.migrateLegacyFoodDataEatItemStackCalls(source: String): String {
    if (!source.contains(".getFoodData().eat(")) return source
    return rewriteJavaCallWithOffset(source, "eat") { receiver, args, offset ->
        if (args.size != 2 || !receiver.trim().endsWith(".getFoodData()")) return@rewriteJavaCallWithOffset null
        val stackExpression = args[1].trim()
        val itemStackNames = collectItemStackValueNames(source)
        val itemStackReturningMethods = collectItemStackReturningMethodNames(source)
        val knownItemStackAccessors = collectKnownItemStackAccessorExpressions(source)
        if (!isItemStackExpression(stackExpression, itemStackNames, itemStackReturningMethods, knownItemStackAccessors)) {
            return@rewriteJavaCallWithOffset null
        }
        val stackVariable = stackExpression.takeIf { Regex("""[A-Za-z_$][\w$]*""").matches(it) }
        val foodPropertiesVariable = stackVariable?.let { nearestFoodPropertiesVariableForStack(source, offset, it) }
        val foodExpression = foodPropertiesVariable
            ?: "${stackExpression}.getFoodProperties(${nearestFoodPropertyEntityArgument(source, offset) ?: "null"})"
        "$receiver.eat($foodExpression)"
    }
}

internal fun StructuralRefactorPass.nearestFoodPropertyEntityArgument(source: String, offset: Int): String? {
    val method = enclosingMethodText(source, offset) ?: return null
    return javaMethodParameters(method)
        .filter { simpleJavaTypeName(it.type) in setOf("Player", "LivingEntity") }
        .map { it.name }
        .distinct()
        .singleOrNull()
}

internal fun StructuralRefactorPass.nearestFoodPropertiesVariableForStack(source: String, offset: Int, stackVariable: String): String? {
    val method = enclosingMethodText(source, offset) ?: return null
    val localOffset = offset - (source.indexOf(method).takeIf { it >= 0 } ?: return null)
    val prefix = method.take(localOffset.coerceIn(0, method.length))
    return Regex(
        """\bFoodProperties\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(stackVariable)}\.getFoodProperties\s*\([^;]*\)\s*;"""
    ).findAll(prefix).lastOrNull()?.groupValues?.get(1)
}

internal fun StructuralRefactorPass.migrateLegacyItemCorrectToolForDropsSource(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("isCorrectToolForDrops(")) return source
    var result = source
    var changed = false
    collectJavaClassDeclarations(source).asReversed().forEach { declaration ->
        if (!javaClassExtendsAny(declaration, javaItemBaseTypes(), javaInheritanceIndex)) return@forEach
        val body = result.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
        val hasStackSignature = Regex(
            """\bisCorrectToolForDrops\s*\(\s*(?:[^,()]+\s+)?ItemStack\s+[A-Za-z_$][\w$]*\s*,\s*(?:@\w+(?:\([^)]*\))?\s*)?BlockState\s+[A-Za-z_$][\w$]*\s*\)"""
        ).containsMatchIn(maskJavaCommentsAndLiterals(body))
        javaMethodRanges(body).asReversed()
            .filter { it.name == "isCorrectToolForDrops" }
            .forEach { method ->
                val params = javaMethodParameters(body.substring(method.range))
                if (params.size != 1 || simpleJavaTypeName(params[0].type) != "BlockState") return@forEach
                val absolute = (declaration.bodyRange.first + 1 + method.range.first)..(declaration.bodyRange.first + 1 + method.range.last)
                if (hasStackSignature) {
                    val methodText = result.substring(absolute)
                    val withoutOverride = Regex("""(?m)^([ \t]*)@Override\s*\r?\n""").replace(methodText, "")
                    if (withoutOverride != methodText) {
                        result = result.replaceRange(absolute, withoutOverride)
                        changed = true
                    }
                    return@forEach
                }
                val methodText = result.substring(absolute)
                val migrated = Regex("""isCorrectToolForDrops\s*\(\s*((?:@\w+(?:\([^)]*\))?\s*)?BlockState\s+${Regex.escape(params[0].name)})\s*\)""")
                    .replace(methodText, "isCorrectToolForDrops(ItemStack stack, $1)")
                if (migrated != methodText) {
                    result = result.replaceRange(absolute, migrated)
                    changed = true
                }
            }
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.world.item.ItemStack") else source
}

internal fun StructuralRefactorPass.migrateLegacySwordItemDamageAccess(source: String): String {
    if (!source.contains(".getDamage()") || !source.contains("SwordItem")) return source
    val code = maskJavaCommentsAndLiterals(source)
    val swordVariables = linkedSetOf<String>()
    Regex("""\bSwordItem\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(code)
        .forEach { swordVariables += it.groupValues[1] }
    Regex("""\binstanceof\s+SwordItem\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(code)
        .forEach { swordVariables += it.groupValues[1] }
    if (swordVariables.isEmpty()) return source
    var result = source
    swordVariables.forEach { variable ->
        result = replaceExecutableJavaRegex(
            result,
            Regex("""\b${Regex.escape(variable)}\.getDamage\s*\(\s*\)""")
        ) {
            "(float) new net.minecraft.world.item.ItemStack($variable).getAttributeModifiers().compute(0.0D, net.minecraft.world.entity.EquipmentSlot.MAINHAND)"
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyItemIsEdibleOverrides(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains("isEdible()")) return source
    var result = source
    collectJavaClassDeclarations(source).asReversed().forEach { declaration ->
        if (!javaClassExtendsAny(declaration, javaItemBaseTypes(), javaInheritanceIndex)) return@forEach
        val classBody = source.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
        if (Regex("""\bgetFoodProperties\s*\(\s*ItemStack\b""").containsMatchIn(classBody)) return@forEach
        val methodPattern = Regex(
            """(?m)^[ \t]*(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\r?\n[ \t]*)*public\s+boolean\s+isEdible\s*\(\s*\)\s*\{"""
        )
        val method = methodPattern.findAll(classBody).firstOrNull() ?: return@forEach
        val methodStart = declaration.bodyRange.first + 1 + method.range.first
        val openBrace = result.indexOf('{', methodStart)
        if (openBrace < 0) return@forEach
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) return@forEach
        val body = result.substring(openBrace + 1, closeBrace)
        if (Regex("""(?s)^\s*return\s+.+?;\s*$""").matches(body).not()) return@forEach
        val methodText = result.substring(methodStart, closeBrace + 1)
        val helperMethod = Regex("""(?m)^[ \t]*@Override\s*\r?\n""")
            .replace(methodText, "")
        val indent = Regex("""(?m)^([ \t]*)public\s+boolean\s+isEdible\b""")
            .find(helperMethod)
            ?.groupValues
            ?.get(1)
            ?: ""
        val foodPropertiesOverride = """

${indent}@Override
${indent}public FoodProperties getFoodProperties(ItemStack stack, LivingEntity entity) {
${indent}    return this.isEdible() ? stack.get(DataComponents.FOOD) : null;
${indent}}
""".trimEnd()
        result = result.substring(0, methodStart) +
            helperMethod +
            foodPropertiesOverride +
            result.substring(closeBrace + 1)
    }
    if (result != source) {
        result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
        result = addImportIfMissing(result, "net.minecraft.world.entity.LivingEntity")
        result = addImportIfMissing(result, "net.minecraft.world.food.FoodProperties")
        result = addImportIfMissing(result, "net.minecraft.world.item.ItemStack")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyItemIsEdibleCalls(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains("isEdible()")) return source
    val itemVariables = Regex("""\bItem\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    var result = rewriteJavaCallWithOffset(source, "isEdible") { receiver, args, offset ->
        if (args.isNotEmpty()) return@rewriteJavaCallWithOffset null
        val trimmedReceiver = receiver.trim()
        if (trimmedReceiver.endsWith(".getItem()")) {
            val stackReceiver = trimmedReceiver.removeSuffix(".getItem()")
            return@rewriteJavaCallWithOffset "$stackReceiver.getFoodProperties(null) != null"
        }
        if (trimmedReceiver in itemVariables) {
            return@rewriteJavaCallWithOffset "$trimmedReceiver.getDefaultInstance().getFoodProperties(null) != null"
        }
        if (trimmedReceiver == "this") {
            val declaration = enclosingJavaClassDeclaration(source, offset) ?: return@rewriteJavaCallWithOffset null
            if (!javaClassExtendsAny(declaration, javaItemBaseTypes(), javaInheritanceIndex)) return@rewriteJavaCallWithOffset null
            val classBody = source.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
            if (Regex("""\bpublic\s+boolean\s+isEdible\s*\(""").containsMatchIn(classBody)) {
                return@rewriteJavaCallWithOffset null
            }
            return@rewriteJavaCallWithOffset "this.components().has(DataComponents.FOOD)"
        }
        null
    }.let { migrated ->
        if (migrated.contains("DataComponents.FOOD")) {
            addImportIfMissing(migrated, "net.minecraft.core.component.DataComponents")
        } else {
            migrated
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(?<![\w$.])isEdible\s*\(\s*\)""")
    ) { match ->
        val declaration = enclosingJavaClassDeclaration(result, match.range.first) ?: return@replaceExecutableRegex match.value
        if (!javaClassExtendsAny(declaration, javaItemBaseTypes(), javaInheritanceIndex)) return@replaceExecutableRegex match.value
        val classBody = result.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
        if (Regex("""\bpublic\s+boolean\s+isEdible\s*\(""").containsMatchIn(classBody)) {
            return@replaceExecutableRegex match.value
        }
        "this.components().has(DataComponents.FOOD)"
    }
    return if (result.contains("DataComponents.FOOD")) {
        addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    } else {
        result
    }
}

internal fun StructuralRefactorPass.migrateItemFoodComponentHasCalls(
    source: String,
    itemVariables: Set<String>,
    javaInheritanceIndex: JavaInheritanceIndex
): String =
    rewriteJavaCallWithOffset(source, "has") { receiver, args, offset ->
        if (args.size != 1) return@rewriteJavaCallWithOffset null
        val component = args[0].trim()
        if (!Regex("""^(?:net\.minecraft\.core\.component\.)?DataComponents\.[A-Za-z_$][\w$]*$""").matches(component)) {
            return@rewriteJavaCallWithOffset null
        }
        val trimmedReceiver = receiver.trim()
        if (trimmedReceiver.endsWith(".getItem()")) {
            val stackReceiver = trimmedReceiver.removeSuffix(".getItem()")
            return@rewriteJavaCallWithOffset "$stackReceiver.has($component)"
        }
        if (trimmedReceiver in itemVariables) {
            return@rewriteJavaCallWithOffset "$trimmedReceiver.components().has($component)"
        }
        if (trimmedReceiver == "this") {
            val declaration = enclosingJavaClassDeclaration(source, offset) ?: return@rewriteJavaCallWithOffset null
            if (!javaClassExtendsAny(declaration, javaItemBaseTypes(), javaInheritanceIndex)) return@rewriteJavaCallWithOffset null
            val classBody = source.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
            if (component.endsWith("DataComponents.FOOD") &&
                Regex("""\bpublic\s+boolean\s+isEdible\s*\(""").containsMatchIn(classBody)) {
                return@rewriteJavaCallWithOffset "this.isEdible()"
            }
            return@rewriteJavaCallWithOffset "this.components().has($component)"
        }
        null
    }

internal fun StructuralRefactorPass.migrateBucketItemFluidAccessSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getFluid()") && !executableCode.contains("getFluidType()")) return source
    var result = source
    var changed = false
    if (executableCode.contains("extends BucketItem")) {
        val before = result
        result = replaceExecutableJavaRegex(result, Regex("""\bthis\.getFluid\(\)""")) { "this.content" }
        if (result != before) changed = true
    }
    val fluidStackVariables = Regex("""\bFluidStack\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    fluidStackVariables.forEach { variable ->
        val before = result
        result = replaceExecutableJavaRegex(result, Regex("""\b${Regex.escape(variable)}\.getFluid\(\)\.is\(""")) {
            "$variable.is("
        }
        result = replaceExecutableJavaRegex(result, Regex("""\b${Regex.escape(variable)}\.getFluid\(\)\.getFluidType\(\)""")) {
            "$variable.getFluidType()"
        }
        if (result != before) changed = true
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateCreativeTabEntriesAccessSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getEntries().putAfter(")) return source
    return rewriteExecutableJavaCall(source, "putAfter") { receiver, args ->
        if (args.size != 3 || !receiver.endsWith(".getEntries()")) return@rewriteExecutableJavaCall null
        val eventReceiver = receiver.removeSuffix(".getEntries()")
        "$eventReceiver.insertAfter(${args.joinToString(", ") { it.trim() }})"
    }
}

internal fun StructuralRefactorPass.migrateItemStackHoverNameSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".setHoverName(")) return source

    var result = source
    var changed = false
    run {
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val unit = parser.parse(result).result.orElse(null)
        if (unit != null) {
            val lineStarts = buildList {
                add(0)
                result.forEachIndexed { index, character ->
                    if (character == '\n') add(index + 1)
                }
            }
            fun nodeRange(node: com.github.javaparser.ast.Node): IntRange? = node.range.map {
                val first = lineStarts[it.begin.line - 1] + it.begin.column - 1
                val last = lineStarts[it.end.line - 1] + it.end.column - 1
                first..last
            }.orElse(null)
            var stackName = "modporterStack"
            while (Regex("""\b${Regex.escape(stackName)}\b""").containsMatchIn(result)) {
                stackName += "_"
            }
            val supplierEdits = mutableListOf<Pair<IntRange, String>>()
            for (returned in unit.findAll(com.github.javaparser.ast.stmt.ReturnStmt::class.java)) {
                val lambda = returned.expression.orElse(null) as? LambdaExpr ?: continue
                if (lambda.parameters.isNotEmpty()) continue
                val expression = (lambda.body as? com.github.javaparser.ast.stmt.ExpressionStmt)
                    ?.expression as? MethodCallExpr ?: continue
                if (expression.nameAsString != "setHoverName" ||
                    expression.arguments.size != 1 ||
                    expression.scope.isEmpty
                ) {
                    continue
                }
                val callRange = nodeRange(expression) ?: continue
                val receiverRange = nodeRange(expression.scope.get()) ?: continue
                val argumentRange = nodeRange(expression.arguments.single()) ?: continue
                val receiver = result.substring(receiverRange)
                val argument = result.substring(argumentRange)
                supplierEdits += callRange to
                    "net.minecraft.Util.make($receiver, $stackName -> " +
                    "$stackName.set(DataComponents.CUSTOM_NAME, $argument))"
            }
            if (supplierEdits.isNotEmpty()) {
                result = applyStringEdits(result, supplierEdits)
                changed = true
            }
        }
    }
    val beforeAssignmentRewrite = result
    result = replaceExecutableRegex(
        result,
        Regex(
            // ItemStack#setHoverName returned the stack itself, so any initializer splits exactly
            """(?m)^([ \t]*)ItemStack\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]+?)\s*\.setHoverName\((.*)\)\s*;[ \t]*$"""
        )
    ) { match ->
        changed = true
        val indent = match.groupValues[1]
        val variable = match.groupValues[2]
        fun originalGroup(index: Int): String =
            match.groups[index]?.range?.let { beforeAssignmentRewrite.substring(it.first, it.last + 1) }
                ?: match.groupValues[index]
        val stackExpression = originalGroup(3).trim()
        val nameExpression = originalGroup(4).trim()
        "${indent}ItemStack $variable = $stackExpression;\n" +
            "${indent}$variable.set(DataComponents.CUSTOM_NAME, $nameExpression);"
    }
    // a returned setHoverName chain keeps returning the stack
    result = replaceExecutableRegex(
        result,
        Regex("""(?m)^([ \t]*)return\s+([^;\r\n]+?)\s*\.setHoverName\(([^;\r\n]*)\)\s*;[ \t]*$""")
    ) { match ->
        changed = true
        val stackExpression = match.groupValues[2].trim()
        val supplierBody = Regex("""^\(\s*\)\s*->\s*(.+)$""")
            .matchEntire(stackExpression)
            ?.groupValues
            ?.get(1)
            ?.trim()
        if (supplierBody != null) {
            "${match.groupValues[1]}return () -> net.minecraft.Util.make($supplierBody, modporterStack -> modporterStack.set(DataComponents.CUSTOM_NAME, ${match.groupValues[3].trim()}));"
        } else {
            "${match.groupValues[1]}return net.minecraft.Util.make($stackExpression, modporterStack -> modporterStack.set(DataComponents.CUSTOM_NAME, ${match.groupValues[3].trim()}));"
        }
    }
    result = rewriteExecutableJavaCall(result, "setHoverName") { receiver, args ->
        if (args.size != 1) {
            null
        } else {
            changed = true
            "$receiver.set(DataComponents.CUSTOM_NAME, ${args[0].trim()})"
        }
    }
    if (!changed) return source
    return addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
}

internal fun StructuralRefactorPass.migrateFriendlyByteBufAmbiguousMethodReferencesSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".writeMap(") &&
        !executableCode.contains(".readMap(") &&
        !executableCode.contains("FriendlyByteBuf::writeUUID") &&
        !executableCode.contains("FriendlyByteBuf::readUUID")) {
        return source
    }
    var result = rewriteExecutableJavaCall(source, "writeMap") { receiver, args ->
        if (args.size != 3) return@rewriteExecutableJavaCall null
        val keyWriter = streamEncoderMethodReferenceLambda(args[1].trim()) ?: args[1].trim()
        val valueWriter = streamEncoderMethodReferenceLambda(args[2].trim()) ?: args[2].trim()
        if (keyWriter == args[1].trim() && valueWriter == args[2].trim()) {
            null
        } else {
            "$receiver.writeMap(${args[0].trim()}, $keyWriter, $valueWriter)"
        }
    }
    result = rewriteExecutableJavaCall(result, "readMap") { receiver, args ->
        if (args.size != 2) return@rewriteExecutableJavaCall null
        val keyReader = streamDecoderMethodReferenceLambda(args[0].trim()) ?: args[0].trim()
        val valueReader = streamDecoderMethodReferenceLambda(args[1].trim()) ?: args[1].trim()
        if (keyReader == args[0].trim() && valueReader == args[1].trim()) {
            null
        } else {
            "$receiver.readMap($keyReader, $valueReader)"
        }
    }
    return result
}

internal fun StructuralRefactorPass.streamEncoderMethodReferenceLambda(expression: String): String? {
    val match = Regex("""^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::([A-Za-z_$][\w$]*)$""")
        .find(expression)
        ?: return null
    val owner = match.groupValues[1]
    val method = match.groupValues[2]
    return if (owner.substringAfterLast('.') == "FriendlyByteBuf") {
        "(buffer, value) -> buffer.$method(value)"
    } else {
        "(buffer, value) -> $owner.$method(buffer, value)"
    }
}

internal fun StructuralRefactorPass.streamDecoderMethodReferenceLambda(expression: String): String? {
    val match = Regex("""^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::([A-Za-z_$][\w$]*)$""")
        .find(expression)
        ?: return null
    val owner = match.groupValues[1]
    val method = match.groupValues[2]
    return if (owner.substringAfterLast('.') == "FriendlyByteBuf") {
        "buffer -> buffer.$method()"
    } else {
        "buffer -> $owner.$method(buffer)"
    }
}

internal fun StructuralRefactorPass.migrateCustomPacketPayloadRecordContractsSource(source: String, modId: String): String {
    if (modId.isBlank() ||
        !source.contains("record ") ||
        !source.contains("implements CustomPacketPayload")) {
        return source
    }

    val topLevelClass = Regex("""(?m)^[ \t]*(?:public\s+)?(?:final\s+)?class\s+([A-Za-z_$][\w$]*)\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
    val recordPattern = Regex(
        """(?m)^([ \t]*)(?:public\s+)?record\s+([A-Za-z_$][\w$]*)\s*\([^{};]*\)\s+implements\s+CustomPacketPayload\s*\{"""
    )
    val insertions = recordPattern.findAll(source).mapNotNull { match ->
        val openBrace = match.range.last
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace <= openBrace) return@mapNotNull null
        val body = source.substring(openBrace + 1, closeBrace)
        val recordName = match.groupValues[2]
        if (body.contains("CustomPacketPayload.Type<$recordName> TYPE") ||
            Regex("""\btype\s*\(\s*\)""").containsMatchIn(body)) {
            return@mapNotNull null
        }
        val indent = match.groupValues[1] + "    "
        val payloadName = if (topLevelClass != null && topLevelClass != recordName) {
            payloadPathName("${topLevelClass}_${recordName}")
        } else {
            payloadPathName(recordName)
        }
        val insert = """

${indent}public static final CustomPacketPayload.Type<$recordName> TYPE =
${indent}        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("$modId", "$payloadName"));

${indent}@Override
${indent}public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
${indent}    return TYPE;
${indent}}
""".trimEnd()
        openBrace to insert
    }.toList()
    if (insertions.isEmpty()) return source

    var result = source
    for ((index, text) in insertions.asReversed()) {
        result = result.substring(0, index + 1) + text + result.substring(index + 1)
    }
    result = addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
    return result
}

internal fun StructuralRefactorPass.migrateAbstractNestedPayloadBaseInterfaces(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("abstract class") || !executableCode.contains("implements CustomPacketPayload")) {
        return source
    }
    val id = """[A-Za-z_$][\w$]*"""
    val classPattern = Regex("""(?m)^([ \t]*(?:public|protected|private)?\s*abstract\s+class\s+($id)[^{\r\n]*)(\s*\{)""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    classPattern.findAll(executableCode).forEach { match ->
        val headerRange = match.groups[1]?.range ?: return@forEach
        val header = source.substring(headerRange)
        if (Regex("""\bimplements\b[^{\r\n]*\bCustomPacketPayload\b""").containsMatchIn(header)) return@forEach
        val className = match.groupValues[2]
        val openBrace = executableCode.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return@forEach
        val body = executableCode.substring(openBrace + 1, closeBrace)
        val nestedPayload = Regex(
            """\bclass\s+$id\b[^{;]*\bextends\s+${Regex.escape(className)}\b[^{;]*\bimplements\s+CustomPacketPayload\b"""
        ).containsMatchIn(body)
        if (!nestedPayload) return@forEach
        val cleanHeader = header.trimEnd()
        val replacementHeader = if (Regex("""\bimplements\b""").containsMatchIn(header)) {
            "$cleanHeader, CustomPacketPayload "
        } else {
            "$cleanHeader implements CustomPacketPayload "
        }
        edits += headerRange to replacementHeader
    }
    if (edits.isEmpty()) return source
    return addImportIfMissing(applyStringEdits(source, edits), "net.minecraft.network.protocol.common.custom.CustomPacketPayload")
}

internal fun StructuralRefactorPass.migrateGeneratedLazyOptionalImportSource(source: String, generatedCompatPackage: () -> String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!Regex("""\bLazyOptional\s*<""").containsMatchIn(executableCode) ||
        Regex("""(?m)^[ \t]*import\s+com\.modporter\.generated\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.compat\.LazyOptional;\s*$""")
            .containsMatchIn(executableCode) ||
        Regex("""(?m)^package\s+com\.modporter\.generated\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.compat\s*;""")
            .containsMatchIn(executableCode)) {
        return source
    }
    val compatPackage = generatedCompatPackage()
    if (compatPackage.isBlank() || source.contains("import $compatPackage.LazyOptional;")) return source
    return addImportIfMissing(source, "$compatPackage.LazyOptional")
}

internal fun StructuralRefactorPass.removeUnusedLazyOptionalFields(
    source: String,
    allowPlainUnusedCleanup: Boolean = true
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!Regex("""\bLazyOptional\s*<""").containsMatchIn(executableCode)) return source
    if (!allowPlainUnusedCleanup &&
        !executableCode.contains("Capabilities.") &&
        !executableCode.contains("getCapability(") &&
        !executableCode.contains("registerCapabilities(")) {
        return source
    }
    var result = source
    val fieldPattern = Regex(
        """(?m)^([ \t]*)(?:private|protected|public)\s+(?:final\s+)?LazyOptional\s*<[^;\r\n]+>\s+([A-Za-z_$][\w$]*)\s*(?:=\s*LazyOptional\.of\([^;\r\n]*\))?\s*;\s*\r?\n?"""
    )
    var changed: Boolean
    do {
        changed = false
        val fields = fieldPattern.findAll(maskJavaCommentsAndLiterals(result)).toList()
        for (field in fields.asReversed()) {
            val name = field.groupValues[2]
            val withoutDeclaration = result.removeRange(field.range)
            val assignmentPattern = Regex(
                """(?m)^[ \t]*(?:this\.)?${Regex.escape(name)}\s*=\s*LazyOptional\.of\([^;\r\n]*\)\s*;\s*\r?\n?"""
            )
            val withoutAssignments = assignmentPattern.replace(withoutDeclaration, "")
            if (Regex("""(?<![\w$])${Regex.escape(name)}(?![\w$])""")
                    .containsMatchIn(maskJavaCommentsAndLiterals(withoutAssignments))) {
                continue
            }
            result = withoutAssignments
            changed = true
        }
    } while (changed)
    if (!Regex("""\bLazyOptional\s*<|\bLazyOptional\.""").containsMatchIn(maskJavaCommentsAndLiterals(result))) {
        result = removeImport(result, "com.modporter.compat.LazyOptional")
        result = Regex("""(?m)^[ \t]*import\s+com\.modporter\.generated\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.compat\.LazyOptional;\s*\r?\n""")
            .replace(result, "")
        result = removeImport(result, "net.neoforged.neoforge.common.util.LazyOptional")
        result = removeImport(result, "net.minecraftforge.common.util.LazyOptional")
    }
    return result
}

internal fun StructuralRefactorPass.migrateNitrogenBlockStateRecipeConstructors(source: String): String {
    if (!source.contains("BlockStateIngredient") ||
        !source.contains("BlockPropertyPair") ||
        !source.contains("CacheableFunction") ||
        !source.contains("super(")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var changed = false
    Regex("""class\s+($id)\s+extends\s+AbstractBlockStateRecipe\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.let { className ->
            val constructorPattern = Regex(
                """(?s)public\s+${Regex.escape(className)}\s*\(\s*ResourceLocation\s+$id\s*,\s*BlockStateIngredient\s+($id)\s*,\s*BlockPropertyPair\s+($id)\s*,\s*@Nullable\s+CacheableFunction\s+($id)\s*\)\s*\{\s*super\(\s*([^,]+?)\s*,\s*$id\s*,\s*\1\s*,\s*\2\s*,\s*\3\s*\)\s*;\s*\}"""
            )
            result = constructorPattern.replace(result) { match ->
                changed = true
                val ingredient = match.groupValues[1]
                val recipeResult = match.groupValues[2]
                val function = match.groupValues[3]
                val recipeType = match.groupValues[4].trim()
                "public $className(BlockStateIngredient $ingredient, BlockPropertyPair $recipeResult, Optional<ResourceLocation> $function) {\n" +
                    "        super($recipeType, $ingredient, $recipeResult, $function);\n" +
                    "    }"
            }
        }

    Regex("""class\s+($id)\s+extends\s+AbstractBiomeParameterRecipe\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.let { className ->
            val fullConstructorPattern = Regex(
                """(?s)public\s+${Regex.escape(className)}\s*\(\s*ResourceLocation\s+$id\s*,\s*@Nullable\s+ResourceKey\s*<\s*Biome\s*>\s+($id)\s*,\s*@Nullable\s+TagKey\s*<\s*Biome\s*>\s+($id)\s*,\s*BlockStateIngredient\s+($id)\s*,\s*BlockPropertyPair\s+($id)\s*,\s*@Nullable\s+CacheableFunction\s+($id)\s*\)\s*\{\s*super\(\s*([^,]+?)\s*,\s*$id\s*,\s*\1\s*,\s*\2\s*,\s*\3\s*,\s*\4\s*,\s*\5\s*\)\s*;\s*\}"""
            )
            result = fullConstructorPattern.replace(result) { match ->
                changed = true
                val ingredient = match.groupValues[3]
                val recipeResult = match.groupValues[4]
                val function = match.groupValues[5]
                val recipeType = match.groupValues[6].trim()
                "public $className(Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome, BlockStateIngredient $ingredient, BlockPropertyPair $recipeResult, Optional<ResourceLocation> $function) {\n" +
                    "        super($recipeType, biome, $ingredient, $recipeResult, $function);\n" +
                    "    }"
            }
            val simpleConstructorPattern = Regex(
                """(?s)public\s+${Regex.escape(className)}\s*\(\s*ResourceLocation\s+$id\s*,\s*BlockStateIngredient\s+($id)\s*,\s*BlockPropertyPair\s+($id)\s*,\s*@Nullable\s+CacheableFunction\s+($id)\s*\)\s*\{\s*this\(\s*$id\s*,\s*null\s*,\s*null\s*,\s*\1\s*,\s*\2\s*,\s*\3\s*\)\s*;\s*\}"""
            )
            result = simpleConstructorPattern.replace(result) { match ->
                changed = true
                val ingredient = match.groupValues[1]
                val recipeResult = match.groupValues[2]
                val function = match.groupValues[3]
                "public $className(BlockStateIngredient $ingredient, BlockPropertyPair $recipeResult, Optional<ResourceLocation> $function) {\n" +
                    "        this(Optional.empty(), $ingredient, $recipeResult, $function);\n" +
                    "    }"
            }
        }

    if (!changed) return source
    result = addImportIfMissing(result, "java.util.Optional")
    if (result.contains("Optional<Either<")) {
        result = addImportIfMissing(result, "com.mojang.datafixers.util.Either")
    }
    result = removeImport(result, "net.minecraft.commands.functions.CommandFunction")
    result = removeImport(result, "net.minecraft.commands.CacheableFunction")
    if (Regex("""\b@Nullable\b""").containsMatchIn(result)) {
        result = addImportIfMissing(result, "javax.annotation.Nullable")
    } else {
        result = removeImport(result, "javax.annotation.Nullable")
    }
    return result
}

internal fun StructuralRefactorPass.migrateNitrogenBiomeParameterRecipeSource(source: String): String {
    if (!source.contains("extends AbstractBlockStateRecipe") ||
        !source.contains("biomeKey") ||
        !source.contains("biomeTag") ||
        !source.contains("BlockStateIngredient") ||
        !source.contains("BlockPropertyPair") ||
        !source.contains("CacheableFunction")) {
        return source
    }

    val className = Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+AbstractBlockStateRecipe\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return source
    var result = source
    var changed = false
    result = Regex(
        """(?s)@Nullable\s*\r?\n\s*private\s+final\s+ResourceKey\s*<\s*Biome\s*>\s+biomeKey\s*;\s*@Nullable\s*\r?\n\s*private\s+final\s+TagKey\s*<\s*Biome\s*>\s+biomeTag\s*;"""
    ).replace(result) {
        changed = true
        "private final Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome;"
    }
    result = Regex(
        """(?s)public\s+${Regex.escape(className)}\s*\(\s*RecipeType<\?>\s+type\s*,\s*ResourceLocation\s+id\s*,\s*@Nullable\s+ResourceKey\s*<\s*Biome\s*>\s+biomeKey\s*,\s*@Nullable\s+TagKey\s*<\s*Biome\s*>\s+biomeTag\s*,\s*BlockStateIngredient\s+ingredient\s*,\s*BlockPropertyPair\s+result\s*,\s*@Nullable\s+CacheableFunction\s+function\s*\)\s*\{\s*super\(\s*type\s*,\s*id\s*,\s*ingredient\s*,\s*result\s*,\s*function\s*\)\s*;\s*this\.biomeKey\s*=\s*biomeKey\s*;\s*this\.biomeTag\s*=\s*biomeTag\s*;\s*\}"""
    ).replace(result) {
        changed = true
        "public $className(RecipeType<?> type, Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome, BlockStateIngredient ingredient, BlockPropertyPair result, Optional<ResourceLocation> function) {\n" +
            "        super(type, ingredient, result, function);\n" +
            "        this.biome = biome;\n" +
            "    }"
    }
    result = Regex(
        """(?s)if\s*\(\s*this\.biomeKey\s*!=\s*null\s*\)\s*\{\s*return\s+super\.matches\(\s*level\s*,\s*pos\s*,\s*state\s*\)\s*&&\s*level\.getBiome\(\s*pos\s*\)\.is\(\s*this\.biomeKey\s*\)\s*;\s*\}\s*else\s+if\s*\(\s*this\.biomeTag\s*!=\s*null\s*\)\s*\{\s*return\s+super\.matches\(\s*level\s*,\s*pos\s*,\s*state\s*\)\s*&&\s*level\.getBiome\(\s*pos\s*\)\.is\(\s*this\.biomeTag\s*\)\s*;"""
    ).replace(result) {
        changed = true
        "if (this.biome.isPresent() && this.biome.get().left().isPresent()) {\n" +
            "            return super.matches(level, pos, state) && level.getBiome(pos).is(this.biome.get().left().get());\n" +
            "        } else if (this.biome.isPresent() && this.biome.get().right().isPresent()) {\n" +
            "            return super.matches(level, pos, state) && level.getBiome(pos).is(this.biome.get().right().get());"
    }
    result = Regex(
        """(?s)@Nullable\s*\r?\n\s*public\s+ResourceKey\s*<\s*Biome\s*>\s+getBiomeKey\s*\(\s*\)\s*\{\s*return\s+this\.biomeKey\s*;\s*\}\s*@Nullable\s*\r?\n\s*public\s+TagKey\s*<\s*Biome\s*>\s+getBiomeTag\s*\(\s*\)\s*\{\s*return\s+this\.biomeTag\s*;\s*\}"""
    ).replace(result) {
        changed = true
        "public Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> getBiome() {\n" +
            "        return this.biome;\n" +
            "    }"
    }
    if (!changed) return source
    result = addImportIfMissing(result, "com.mojang.datafixers.util.Either")
    result = addImportIfMissing(result, "java.util.Optional")
    result = removeImport(result, "net.minecraft.commands.functions.CommandFunction")
    result = removeImport(result, "net.minecraft.commands.CacheableFunction")
    if (!Regex("""\b@Nullable\b""").containsMatchIn(result)) {
        result = removeImport(result, "javax.annotation.Nullable")
    }
    return result
}

internal fun StructuralRefactorPass.migrateNitrogenBiomeParameterRecipeSerializerSource(source: String): String {
    if (!source.contains("extends BlockStateRecipeSerializer") ||
        !source.contains("CookieBaker") ||
        !source.contains("biomeRecipeDataFromJson") ||
        !source.contains("readBiomeKey") ||
        !source.contains("writeBiomeKey")) {
        return source
    }

    val packageLine = Regex("""(?m)^package\s+[^;]+;""").find(source)?.value ?: return source
    val recipeImport = Regex("""(?m)^import\s+([^;]+\.AbstractBiomeParameterRecipe);""")
        .find(source)
        ?.groupValues
        ?.get(1)
    val recipeImportLine = recipeImport?.let { "import $it;\n" }.orEmpty()
    val classMatch = Regex("""public\s+class\s+([A-Za-z_$][\w$]*)\s*<\s*T\s+extends\s+([A-Za-z_$][\w$]*)\s*>\s+extends\s+BlockStateRecipeSerializer\s*<\s*T\s*>""")
        .find(source)
        ?: return source
    val className = classMatch.groupValues[1]
    val recipeBase = classMatch.groupValues[2]

    return """
$packageLine

${recipeImportLine}import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
import com.aetherteam.nitrogen.recipe.BlockStateRecipeUtil;
import com.aetherteam.nitrogen.recipe.recipes.AbstractBlockStateRecipe;
import com.aetherteam.nitrogen.recipe.serializer.BlockStateRecipeSerializer;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;

public class $className<T extends $recipeBase> extends BlockStateRecipeSerializer<T> {
    private final $className.Factory<T> factory;
    private final MapCodec<T> codec;

    public $className($className.Factory<T> factory, AbstractBlockStateRecipe.Factory<T> superFactory) {
        super(superFactory);
        this.factory = factory;
        this.codec = RecordCodecBuilder.mapCodec(inst -> inst.group(
                BlockStateRecipeUtil.KEY_CODEC.optionalFieldOf("biome").forGetter($recipeBase::getBiome),
                BlockStateIngredient.CODEC.fieldOf("ingredient").forGetter($recipeBase::getIngredient),
                BlockPropertyPair.CODEC.fieldOf("result").forGetter($recipeBase::getResult),
                ResourceLocation.CODEC.optionalFieldOf("mcfunction").forGetter($recipeBase::getFunctionId)
        ).apply(inst, factory::create));
    }

    @Override
    public MapCodec<T> codec() {
        return this.codec;
    }

    public T fromNetwork(RegistryFriendlyByteBuf buffer) {
        Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome = buffer.readOptional(buf -> BlockStateRecipeUtil.STREAM_CODEC.decode((RegistryFriendlyByteBuf) buf));
        BlockStateIngredient ingredient = BlockStateIngredient.CONTENTS_STREAM_CODEC.decode(buffer);
        BlockPropertyPair result = BlockStateRecipeUtil.readPair(buffer);
        Optional<ResourceLocation> function = buffer.readOptional(FriendlyByteBuf::readResourceLocation);
        return this.factory.create(biome, ingredient, result, function);
    }

    public void toNetwork(RegistryFriendlyByteBuf buffer, T recipe) {
        buffer.writeOptional(recipe.getBiome(), (buf, either) -> BlockStateRecipeUtil.STREAM_CODEC.encode((RegistryFriendlyByteBuf) buf, either));
        super.toNetwork(buffer, recipe);
    }

    public interface Factory<T extends $recipeBase> {
        T create(Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome, BlockStateIngredient ingredient, BlockPropertyPair result, Optional<ResourceLocation> function);
    }
}
""".trimStart()
}

internal fun StructuralRefactorPass.migrateReiBiomeTooltipOptionalSource(source: String): String {
    if (!source.contains("interface BiomeTooltip") ||
        !source.contains("Tooltip tooltip") ||
        !source.contains("@Nullable ResourceKey<Biome>") ||
        !source.contains("@Nullable TagKey<Biome>")) {
        return source
    }
    var result = source
    result = result.replace(
        "populateBiomeInformation(@Nullable ResourceKey<Biome> biomeKey, @Nullable TagKey<Biome> biomeTag, Tooltip tooltip)",
        "populateBiomeInformation(Optional<ResourceKey<Biome>> biomeKey, Optional<TagKey<Biome>> biomeTag, Tooltip tooltip)"
    )
    result = result
        .replace("(biomeKey != null || biomeTag != null)", "(biomeKey.isPresent() || biomeTag.isPresent())")
        .replace("biomeKey != null", "biomeKey.isPresent()")
        .replace("biomeKey.location()", "biomeKey.get().location()")
        .replace("biomeTag.location()", "biomeTag.get().location()")
        .replace("getTagOrEmpty(biomeTag)", "getTagOrEmpty(biomeTag.get())")
    result = addImportIfMissing(result, "java.util.Optional")
    result = removeImport(result, "javax.annotation.Nullable")
    if (!Regex("""\bResourceLocation\b""").containsMatchIn(removeImport(result, "net.minecraft.resources.ResourceLocation"))) {
        result = removeImport(result, "net.minecraft.resources.ResourceLocation")
    }
    return result
}

internal fun StructuralRefactorPass.migrateBiomeParameterRecipeCategoryBiomeAccessSource(source: String): String {
    if (!source.contains("AbstractBiomeParameterRecipe") ||
        (!source.contains("getBiomeKey()") && !source.contains("getBiomeTag()"))) {
        return source
    }
    var result = source
    result = Regex(
        """if\s*\(\s*Minecraft\.getInstance\(\)\.level\s*!=\s*null\s*\)\s*\{\s*this\.populateBiomeInformation\(\s*recipe\.getBiomeKey\(\)\s*,\s*recipe\.getBiomeTag\(\)\s*,\s*tooltip\s*\)\s*;\s*\}"""
    ).replace(result) {
        "if (Minecraft.getInstance().level != null && recipe.getBiome().isPresent()) {\n" +
            "            this.populateBiomeInformation(recipe.getBiome().get().left().orElse(null), recipe.getBiome().get().right().orElse(null), tooltip);\n" +
            "        }"
    }
    result = Regex(
        """this\.populateBiomeInformation\(\s*display\.getRecipe\(\)\.getBiomeKey\(\)\s*,\s*display\.getRecipe\(\)\.getBiomeTag\(\)\s*,\s*tooltip\s*\)\s*;"""
    ).replace(result) {
        "if (display.getRecipe().getBiome().isPresent()) {\n" +
            "            this.populateBiomeInformation(display.getRecipe().getBiome().get().left(), display.getRecipe().getBiome().get().right(), tooltip);\n" +
            "        }"
    }
    result = result.replace(
        "this.populateBiomeInformation(recipe.getBiomeKey(), recipe.getBiomeTag(), tooltip);",
        "if (recipe.getBiome().isPresent()) {\n" +
            "            this.populateBiomeInformation(recipe.getBiome().get().left().orElse(null), recipe.getBiome().get().right().orElse(null), tooltip);\n" +
            "        }"
    )
    return result
}

internal fun StructuralRefactorPass.migrateIngredientNetworkCodecs(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("fromNetwork(") && !source.contains(".toNetwork(")) return source
    if (!source.contains("Ingredient") && !source.contains("BlockStateIngredient")) return source
    if (source.contains("implements RecipeSerializer<") &&
        (migrateLegacyStructuredRecipeSerializerSource(source) != source ||
            migrateLegacyFactoryCookingRecipeSerializerSource(source) != source)
    ) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    fun registryBufferExpression(buffer: String): String =
        if (Regex("""\bRegistryFriendlyByteBuf\s+${Regex.escape(buffer)}\b""").containsMatchIn(source)) {
            buffer
        } else {
            "(RegistryFriendlyByteBuf) $buffer"
        }

    var result = source
    var changed = false

    result = Regex("""\bBlockStateIngredient\.fromNetwork\s*\(\s*($id)\s*\)""")
        .replace(result) { match ->
            changed = true
            "BlockStateIngredient.CONTENTS_STREAM_CODEC.decode(${registryBufferExpression(match.groupValues[1])})"
        }
    result = Regex("""\bIngredient\.fromNetwork\s*\(\s*($id)\s*\)""")
        .replace(result) { match ->
            changed = true
            "Ingredient.CONTENTS_STREAM_CODEC.decode(${registryBufferExpression(match.groupValues[1])})"
        }

    val blockStateIngredientReceivers = declaredVariablesOfSimpleType(source, "BlockStateIngredient").toMutableSet()
    val ingredientReceivers = declaredVariablesOfSimpleType(source, "Ingredient").toMutableSet()
    if (source.contains("BlockStateIngredient getBypassBlock(") || source.contains("BlockStateIngredient getBypassBlock()")) {
        blockStateIngredientReceivers.addRegexReceiver("getBypassBlock")
    }

    val placementIngredientCodecByClass = Regex("""\bclass\s+($id)(?:\s*<[^>{}]+>)?\s+extends\s+AbstractPlacementBanRecipe\s*<\s*[^,>]+,\s*([^,>]+)""")
        .findAll(source)
        .mapNotNull { match ->
            val codec = when (match.groupValues[2].trim().substringAfterLast('.')) {
                "Ingredient" -> "Ingredient.CONTENTS_STREAM_CODEC"
                "BlockStateIngredient" -> "BlockStateIngredient.CONTENTS_STREAM_CODEC"
                else -> null
            } ?: return@mapNotNull null
            match.groupValues[1] to codec
        }
        .toMap()
    val placementIngredientCodecByVariable = placementIngredientCodecByClass.flatMap { (className, codec) ->
        Regex("""\b${Regex.escape(className)}\s+($id)\b""")
            .findAll(source)
            .map { it.groupValues[1] to codec }
            .toList()
    }.toMap()

    result = replaceNetworkEncodeCalls(result, blockStateIngredientReceivers, "BlockStateIngredient.CONTENTS_STREAM_CODEC", ::registryBufferExpression) {
        changed = true
    }
    result = replaceNetworkEncodeCalls(result, ingredientReceivers, "Ingredient.CONTENTS_STREAM_CODEC", ::registryBufferExpression) {
        changed = true
    }
    val recipeIngredientReceivers = declaredVariablesOfSimpleTypes(
        source,
        recipeLikeGenericTypeVariables(source, javaInheritanceIndex) + recipeLikeBaseTypes()
    )
    recipeIngredientReceivers.forEach { receiver ->
        result = Regex("""(?<![\w$])${Regex.escape(receiver)}\.getIngredients\(\)\.get\(\s*([^)]*?)\s*\)\.toNetwork\s*\(\s*($id)\s*\)""")
            .replace(result) { match ->
                changed = true
                val index = match.groupValues[1].trim()
                "Ingredient.CONTENTS_STREAM_CODEC.encode(${registryBufferExpression(match.groupValues[2])}, $receiver.getIngredients().get($index))"
            }
    }
    result = Regex("""\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\(\))*)\.getBypassBlock\(\)\.toNetwork\s*\(\s*($id)\s*\)""")
        .replace(result) { match ->
            changed = true
            "BlockStateIngredient.CONTENTS_STREAM_CODEC.encode(${registryBufferExpression(match.groupValues[2])}, ${match.groupValues[1]}.getBypassBlock())"
        }
    placementIngredientCodecByVariable.forEach { (variable, codec) ->
        result = Regex("""(?<![\w$])${Regex.escape(variable)}\.getIngredient\(\)\.toNetwork\s*\(\s*($id)\s*\)""")
            .replace(result) { match ->
                changed = true
                "$codec.encode(${registryBufferExpression(match.groupValues[1])}, $variable.getIngredient())"
            }
    }

    if (!changed) return source
    if (result.contains("(RegistryFriendlyByteBuf)")) {
        result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    }
    return result
}

internal fun StructuralRefactorPass.collectLegacyPlacementBanBaseClasses(javaFiles: List<Path>): Set<String> {
    val id = """[A-Za-z_$][\w$]*"""
    return javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        val className = Regex(
            """\bclass\s+($id)\s*<\s*($id)\s*,\s*($id)\s+extends\s+Predicate\s*<\s*\2\s*>\s*>\s+implements\s+Recipe\s*<"""
        ).find(source)?.groupValues?.get(1) ?: return@mapNotNull null
        className.takeIf {
            source.contains("ResourceKey<Biome>") &&
                source.contains("TagKey<Biome>") &&
                source.contains("BlockStateIngredient") &&
                source.contains("ResourceLocation") &&
                source.contains("getBiomeKey()") &&
                source.contains("getBiomeTag()") &&
                source.contains("getBypassBlock()") &&
                Regex("""\bResourceLocation\s+[A-Za-z_$][\w$]*\s*;""").containsMatchIn(source)
        }
    }.toSet()
}

internal fun StructuralRefactorPass.collectLegacyPlacementBanBuilderClasses(javaFiles: List<Path>): Set<String> {
    val id = """[A-Za-z_$][\w$]*"""
    return javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        val className = javaTopLevelTypeName(source) ?: return@mapNotNull null
        className.takeIf {
            Regex("""\bclass\s+${Regex.escape(className)}\s+extends\s+($id\.)?PlacementBanBuilder\b""")
                .containsMatchIn(source) &&
                source.contains("PlacementBanRecipeSerializer") &&
                source.contains("BlockStateIngredient") &&
                source.contains("ResourceKey<Biome>") &&
                source.contains("TagKey<Biome>")
        }
    }.toSet()
}

internal fun StructuralRefactorPass.collectLegacyPlacementBanDisplayClasses(
    javaFiles: List<Path>,
    placementBanBaseClasses: Set<String>
): Set<String> {
    if (placementBanBaseClasses.isEmpty()) return emptySet()
    val id = """[A-Za-z_$][\w$]*"""
    return javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        val className = javaTopLevelTypeName(source) ?: return@mapNotNull null
        className.takeIf {
            Regex("""\bclass\s+${Regex.escape(className)}(?:\s*<[^{}]+>)?\s+extends\s+($id\.)?BasicDisplay\b""")
                .containsMatchIn(source) &&
                placementBanBaseClasses.any { baseClass -> source.contains(baseClass) } &&
                source.contains("getBiomeKey()") &&
                source.contains("getBiomeTag()") &&
                source.contains("getBypassBlock()")
        }
    }.toSet()
}

internal fun StructuralRefactorPass.migrateLegacyPlacementBanRecipeSource(
    source: String,
    placementBanBaseClasses: Set<String>,
    placementBanBuilderClasses: Set<String>,
    placementBanDisplayClasses: Set<String>
): String {
    if (placementBanBaseClasses.isEmpty()) return source
    if (!source.contains("BlockStateIngredient") &&
        !source.contains("PlacementBanRecipeSerializer") &&
        !source.contains("PlacementBanBuilder") &&
        placementBanBaseClasses.none { source.contains(it) } &&
        placementBanBuilderClasses.none { source.contains("$it.recipe(") }) {
        return source
    }
    var result = source
    result = migrateLegacyPlacementBanBaseRecipeSource(result, placementBanBaseClasses)
    result = migrateLegacyPlacementBanBaseSerializerSource(result, placementBanBaseClasses)
    result = migrateLegacyPlacementBanConcreteRecipeSource(result, placementBanBaseClasses)
    result = migrateLegacyPlacementBanBuilderSource(result)
    result = migrateLegacyPlacementBanReferenceAritiesSource(result)
    result = migrateLegacyPlacementBanBuilderCallSitesSource(result, placementBanBuilderClasses)
    result = migrateLegacyPlacementBanDisplaySource(result, placementBanBaseClasses, placementBanDisplayClasses)
    result = migrateNitrogenBlockPropertyPairRuntimeAccess(result)
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPlacementBanBaseRecipeSource(
    source: String,
    placementBanBaseClasses: Set<String>
): String {
    val className = javaTopLevelTypeName(source) ?: return source
    if (className !in placementBanBaseClasses) return source
    if (!source.contains("biomeKey") || !source.contains("biomeTag") || !source.contains("bypassBlock")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var changed = false

    result = Regex(
        """class\s+${Regex.escape(className)}\s*<\s*($id)\s*,\s*($id)\s+extends\s+Predicate\s*<\s*\1\s*>\s*>\s+implements\s+Recipe\s*<\s*[^>]+>"""
    ).replace(result) { match ->
        changed = true
        "class $className<${match.groupValues[1]}, ${match.groupValues[2]} extends Predicate<${match.groupValues[1]}>, R extends RecipeInput> implements Recipe<R>"
    }

    result = Regex(
        """(?s)\s*(?:protected|private)\s+final\s+ResourceLocation\s+$id\s*;\s*@Nullable\s*\r?\n\s*private\s+final\s+ResourceKey\s*<\s*Biome\s*>\s+$id\s*;\s*@Nullable\s*\r?\n\s*private\s+final\s+TagKey\s*<\s*Biome\s*>\s+$id\s*;\s*(?:protected|private)\s+final\s+BlockStateIngredient\s+$id\s*;"""
    ).replace(result) {
        changed = true
        "\n    private final Either<ResourceKey<Biome>, TagKey<Biome>> biome;\n    protected final Optional<BlockStateIngredient> bypassBlock;"
    }

    result = Regex(
        """(?s)public\s+${Regex.escape(className)}\s*\(\s*RecipeType<\?>\s+($id)\s*,\s*ResourceLocation\s+$id\s*,\s*@Nullable\s+ResourceKey\s*<\s*Biome\s*>\s+$id\s*,\s*@Nullable\s+TagKey\s*<\s*Biome\s*>\s+$id\s*,\s*BlockStateIngredient\s+$id\s*,\s*($id)\s+($id)\s*\)\s*\{.*?\}"""
    ).replace(result) { match ->
        changed = true
        val typeName = match.groupValues[1]
        val ingredientType = match.groupValues[2]
        val ingredientName = match.groupValues[3]
        "public $className(RecipeType<?> $typeName, Either<ResourceKey<Biome>, TagKey<Biome>> biome, Optional<BlockStateIngredient> bypassBlock, $ingredientType $ingredientName) {\n" +
            "        this.type = $typeName;\n" +
            "        this.biome = biome;\n" +
            "        this.bypassBlock = bypassBlock;\n" +
            "        this.ingredient = $ingredientName;\n" +
            "    }"
    }

    result = result
        .replace(
            "this.bypassBlock.isEmpty() || !this.bypassBlock.test(level.getBlockState(pos))",
            "this.bypassBlock.isEmpty() || this.bypassBlock.get().isEmpty() || !this.bypassBlock.get().test(level.getBlockState(pos))"
        )
        .replace("this.biomeKey != null", "this.biome.left().isPresent()")
        .replace("this.biomeTag != null", "this.biome.right().isPresent()")
        .replace("level.getBiome(pos).is(this.biomeKey)", "level.getBiome(pos).is(this.biome.left().get())")
        .replace("level.getBiome(pos).is(this.biomeTag)", "level.getBiome(pos).is(this.biome.right().get())")

    result = Regex(
        """(?s)\s*@Nullable\s*\r?\n\s*public\s+ResourceKey\s*<\s*Biome\s*>\s+getBiomeKey\s*\(\s*\)\s*\{\s*return\s+this\.[A-Za-z_$][\w$]*\s*;\s*\}\s*@Nullable\s*\r?\n\s*public\s+TagKey\s*<\s*Biome\s*>\s+getBiomeTag\s*\(\s*\)\s*\{\s*return\s+this\.[A-Za-z_$][\w$]*\s*;\s*\}"""
    ).replace(result) {
        changed = true
        "\n\n    public Either<ResourceKey<Biome>, TagKey<Biome>> getBiome() {\n" +
            "        return this.biome;\n" +
            "    }"
    }

    result = Regex("""public\s+BlockStateIngredient\s+getBypassBlock\s*\(\s*\)""")
        .replace(result) {
            changed = true
            "public Optional<BlockStateIngredient> getBypassBlock()"
        }
    result = Regex(
        """(?s)\s*(?:@Override\s*\r?\n\s*)?public\s+ResourceLocation\s+getId\(\)\s*\{\s*return\s+this\.[A-Za-z_$][\w$]*\s*;\s*\}\s*"""
    ).replace(result) {
        changed = true
        "\n"
    }
    result = Regex("""boolean\s+matches\s*\(\s*(?:RecipeInput|Container)\s+([A-Za-z_$][\w$]*)\s*,\s*Level\s+([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match ->
            changed = true
            "boolean matches(R ${match.groupValues[1]}, Level ${match.groupValues[2]})"
        }
    result = Regex("""ItemStack\s+assemble\s*\(\s*(?:RecipeInput|Container)\s+([A-Za-z_$][\w$]*)\s*,\s*(?:HolderLookup\.Provider|RegistryAccess)\s+([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match ->
            changed = true
            "ItemStack assemble(R ${match.groupValues[1]}, HolderLookup.Provider ${match.groupValues[2]})"
        }
    result = Regex("""ItemStack\s+getResultItem\s*\(\s*(?:HolderLookup\.Provider|RegistryAccess)\s+([A-Za-z_$][\w$]*)\s*\)""")
        .replace(result) { match ->
            changed = true
            "ItemStack getResultItem(HolderLookup.Provider ${match.groupValues[1]})"
        }

    if (!changed) return source
    result = addImportIfMissing(result, "com.mojang.datafixers.util.Either")
    result = addImportIfMissing(result, "java.util.Optional")
    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeInput")
    result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    result = removeImport(result, "net.minecraft.core.RegistryAccess")
    result = removeImport(result, "net.minecraft.world.Container")
    if (!hasSimpleTypeReference(removeImport(result, "net.minecraft.resources.ResourceLocation"), "ResourceLocation")) {
        result = removeImport(result, "net.minecraft.resources.ResourceLocation")
    }
    if (!Regex("""\b@Nullable\b""").containsMatchIn(result)) {
        result = removeImport(result, "javax.annotation.Nullable")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPlacementBanBaseSerializerSource(
    source: String,
    placementBanBaseClasses: Set<String>
): String {
    if (!source.contains("RecipeSerializer") ||
        !source.contains("readBiomeKey") ||
        !source.contains("writeBiomeKey") ||
        !source.contains("CookieBaker")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    val baseAlternation = placementBanBaseClasses.joinToString("|") { Regex.escape(it) }
    val classMatch = Regex(
        """public\s+class\s+($id)\s*<\s*T\s*,\s*S\s+extends\s+Predicate\s*<\s*T\s*>\s*,\s*F\s+extends\s+($baseAlternation)\s*<\s*T\s*,\s*S\s*>\s*>\s+implements\s+RecipeSerializer\s*<\s*F\s*>"""
    ).find(source) ?: return source
    val className = classMatch.groupValues[1]
    val recipeBase = classMatch.groupValues[2]
    val packageLine = Regex("""(?m)^package\s+[^;]+;""").find(source)?.value ?: return source
    val recipeImport = Regex("""(?m)^import\s+([^;]+\.${Regex.escape(recipeBase)});""")
        .find(source)
        ?.groupValues
        ?.get(1)
    val recipeImportLine = recipeImport?.let { "import $it;\n" }.orEmpty()

    return """
$packageLine

${recipeImportLine}import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
import com.aetherteam.nitrogen.recipe.BlockStateRecipeUtil;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Function3;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;
import java.util.function.Predicate;

public abstract class $className<T, S extends Predicate<T>, R extends RecipeInput, F extends $recipeBase<T, S, R>> implements RecipeSerializer<F> {
    private final $className.CookieBaker<T, S, R, F> factory;

    public $className($className.CookieBaker<T, S, R, F> factory) {
        this.factory = factory;
    }

    public void toNetwork(RegistryFriendlyByteBuf buffer, F recipe) {
        BlockStateRecipeUtil.STREAM_CODEC.encode(buffer, recipe.getBiome());
        buffer.writeOptional(recipe.getBypassBlock(), (buf, blockStateIngredient) -> BlockStateIngredient.CONTENTS_STREAM_CODEC.encode((RegistryFriendlyByteBuf) buf, blockStateIngredient));
    }

    public CookieBaker<T, S, R, F> getFactory() {
        return this.factory;
    }

    public interface CookieBaker<T, S extends Predicate<T>, R extends RecipeInput, F extends $recipeBase<T, S, R>> extends Function3<Either<ResourceKey<Biome>, TagKey<Biome>>, Optional<BlockStateIngredient>, S, F> {
        @Override
        F apply(Either<ResourceKey<Biome>, TagKey<Biome>> biome, Optional<BlockStateIngredient> bypassBlock, S ingredient);
    }
}
""".trimStart()
}
