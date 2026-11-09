package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.*
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
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

// Structural migration rules, part 01 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateProjectStructureStartLoadFromTagCalls, migrateFluidIngredientGetterNullSemantics, migrateFluidStackGetterNullSemantics, migrateProjectContainerHelperProviderMethods, normalizeIntroducedJavaImports, cleanupSimpleChannelRemnants, fixStaticEventBusRegistration, migrateBlockUseMethod, detectCapabilityPatterns, detectNetworkingPatterns, migrateDistExecutorDistGuards, cleanupCommentedOutJavaLogicBlocks, detectDistExecutorUsage, migrateConfigResourceLocationListValidators, migrateBlockAttachedStructureTemplateProcessors, cleanupRecursiveRecipeDimensionAccessors, migrateInstancePayloadContextHandlers, migrateKnownNestedSimpleChannelNetworking, transformPacketClasses, migrateBasePacketPayloads, detectModId, migrateMissingMappingsAliases, removeDataDrivenEnchantmentDeferredRegisterCalls, migrateItemStackCapabilityOptionals, detectLazyOptionalUsage, removeEmptyEventBusRegistration, migrateFMLJavaModLoadingContext, migrateStaticFmlModEventBusHelperInitializers, migrateFluidBucketItemCapabilities, migrateItemStackHandlerItemCapabilities, migrateDirectFluidHandlerItemStackCapabilities, migrateCustomFluidItemCapabilities.

internal fun StructuralRefactorPass.migrateProjectStructureStartLoadFromTagCalls(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir).filter { it.toString().endsWith(".java") }.toList()
    val sources = javaFiles.associateWith { it.readText() }
    val loadableStructureStarts = sources.values.flatMap { source ->
        val executable = maskJavaCommentsAndLiterals(source)
        Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+(?:[A-Za-z_$][\w$]*\.)?StructureStart\b""")
            .findAll(executable)
            .mapNotNull { match ->
                val className = match.groupValues[1]
                val loadMethod = Regex("""\bvoid\s+load\s*\(\s*(?:net\.minecraft\.nbt\.)?CompoundTag\s+[A-Za-z_$][\w$]*\s*\)""")
                className.takeIf { loadMethod.containsMatchIn(executable) }
            }
            .toList()
    }.toSet()
    if (loadableStructureStarts.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    for ((javaFile, original) in sources) {
        val migrated = migrateStructureStartLoadFromTagCalls(original, loadableStructureStarts)
        if (migrated == original) continue
        changes.add(Change(
            file = javaFile,
            line = 1,
            description = "Migrate source-declared StructureStart loadFromTag calls to custom load hooks",
            before = ".loadFromTag(CompoundTag)",
            after = ".load(CompoundTag)",
            confidence = Confidence.HIGH,
            ruleId = "struct-structure-start-load"
        ))
        if (!dryRun) {
            javaFile.writeText(migrated)
        }
    }
    return changes
}

internal data class ProjectJavaMethod(val file: Path, val source: String, val method: JavaMethodRange)

internal fun StructuralRefactorPass.migrateFluidIngredientGetterNullSemantics(projectDir: Path, dryRun: Boolean): List<Change> =
    migrateNullableGsonRecipeGetterNullSemantics(
        projectDir = projectDir,
        dryRun = dryRun,
        targetSimpleType = "FluidIngredient",
        ruleId = "struct-fluid-ingredient-null-empty",
        description = "Preserve FluidIngredient.EMPTY semantics for nullable Gson recipe fields",
        before = "return fluidIngredient;",
        after = "return fluidIngredient == null ? FluidIngredient.EMPTY : fluidIngredient;"
    )

internal fun StructuralRefactorPass.migrateFluidStackGetterNullSemantics(projectDir: Path, dryRun: Boolean): List<Change> =
    migrateNullableGsonRecipeGetterNullSemantics(
        projectDir = projectDir,
        dryRun = dryRun,
        targetSimpleType = "FluidStack",
        ruleId = "struct-fluid-stack-null-empty",
        description = "Preserve FluidStack.EMPTY semantics for nullable Gson recipe fields",
        before = "return fluidStack;",
        after = "return fluidStack == null ? FluidStack.EMPTY : fluidStack;"
    )

internal fun StructuralRefactorPass.migrateNullableGsonRecipeGetterNullSemantics(
    projectDir: Path,
    dryRun: Boolean,
    targetSimpleType: String,
    ruleId: String,
    description: String,
    before: String,
    after: String
): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    val methodPattern = Regex(
        """(?s)\b((?:public|protected|private)\s+)?((?:[A-Za-z_$][\w$]*\.)*${Regex.escape(targetSimpleType)})\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{\s*return\s+(this\.)?([A-Za-z_$][\w$]*)\s*;\s*\}"""
    )
    walkJavaSourceRoots(srcDir)
        .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
        .forEach { file ->
            val source = file.readText()
            if (!source.contains(targetSimpleType)) return@forEach
            val executable = maskJavaCommentsAndLiterals(source)
            val edits = mutableListOf<Pair<IntRange, String>>()
            for (match in methodPattern.findAll(executable)) {
                val returnType = match.groupValues[2]
                val receiver = match.groupValues[4]
                val fieldName = match.groupValues[5]
                if (!hasAnnotatedGsonRecipeField(executable, returnType, fieldName)) continue
                val methodText = source.substring(match.range)
                val emptyValue = "$returnType.EMPTY"
                val returnedField = "$receiver$fieldName"
                val migrated = Regex("""return\s+${Regex.escape(receiver)}${Regex.escape(fieldName)}\s*;""")
                    .replace(methodText, "return $returnedField == null ? $emptyValue : $returnedField;")
                if (migrated != methodText) {
                    edits += match.range to migrated
                }
            }
            if (edits.isEmpty()) return@forEach

            var migratedSource = source
            for ((range, replacement) in edits.sortedByDescending { it.first.first }) {
                migratedSource = migratedSource.replaceRange(range, replacement)
            }
            changes.add(Change(
                file = file,
                line = lineNumberAt(source, edits.minOf { it.first.first }),
                description = description,
                before = before,
                after = after,
                confidence = Confidence.HIGH,
                ruleId = ruleId
            ))
            if (!dryRun) {
                file.writeText(migratedSource)
            }
        }
    return changes
}

internal fun StructuralRefactorPass.hasAnnotatedGsonRecipeField(executableSource: String, returnType: String, fieldName: String): Boolean {
    val simpleType = returnType.substringAfterLast('.')
    val typePattern = listOf(returnType, simpleType)
        .distinct()
        .joinToString("|") { Regex.escape(it) }
    return Regex(
        """(?s)(?:@\s*(?:[A-Za-z_$][\w$]*\.)?(?:Expose|SerializedName)\s*(?:\([^)]*\))?\s*)+""" +
            """(?:public|protected|private)?\s*(?:static\s+|final\s+|transient\s+|volatile\s+)*""" +
            """(?:$typePattern)\s+${Regex.escape(fieldName)}\b"""
    )
        .containsMatchIn(executableSource)
}

internal fun StructuralRefactorPass.migrateProjectContainerHelperProviderMethods(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir).filter { it.toString().endsWith(".java") }.toList()
    if (javaFiles.isEmpty()) return emptyList()
    val javaInheritanceIndex = collectJavaInheritanceIndex(javaFiles)
    val sources = javaFiles.associateWith { it.readText() }
    val allMethods = sources.flatMap { (file, source) ->
        javaMethodRangesIncludingDefault(source).map { ProjectJavaMethod(file, source, it) }
    }
    val methodCounts = allMethods.groupingBy { it.method.name }.eachCount()
    val candidates = allMethods.filter { candidate ->
        methodCounts[candidate.method.name] == 1 &&
            isPrivateOrPackagePrivateJavaMethodHeader(candidate.method.header) &&
            !candidate.method.header.contains("HolderLookup.Provider") &&
            candidate.source.substring(candidate.method.range).let { methodText ->
                methodText.contains("ContainerHelper.saveAllItems(") ||
                    methodText.contains("ContainerHelper.loadAllItems(")
            } &&
            containerHelperCallsNeedProvider(candidate.source.substring(candidate.method.range))
    }
    if (candidates.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    val editsByFile = linkedMapOf<Path, MutableList<Pair<IntRange, String>>>()
    val importFiles = linkedSetOf<Path>()
    for (candidate in candidates) {
        val paramsText = javaMethodParameterText(candidate.method.header) ?: continue
        val originalParameterCount = splitTopLevelJavaArgs(paramsText).size
        val providerName = uniqueLocalNameInScope(candidate.source.substring(candidate.method.range), "registries")
        val migratedMethod = addHolderLookupProviderToPrivateContainerHelperMethod(
            candidate.source.substring(candidate.method.range),
            candidate.method.name,
            providerName
        )
        if (migratedMethod == candidate.source.substring(candidate.method.range)) continue

        val candidateEditsByFile = linkedMapOf<Path, MutableList<Pair<IntRange, String>>>()
        candidateEditsByFile.getOrPut(candidate.file) { mutableListOf() } += candidate.method.range to migratedMethod

        var unsafe = false
        for ((file, source) in sources) {
            val callEdits = methodCallProviderEditsForSource(
                source = source,
                methodName = candidate.method.name,
                originalParameterCount = originalParameterCount,
                declarationRange = if (file == candidate.file) candidate.method.range else null,
                javaInheritanceIndex = javaInheritanceIndex
            )
            if (callEdits == null) {
                unsafe = true
                break
            }
            if (callEdits.isNotEmpty()) {
                candidateEditsByFile.getOrPut(file) { mutableListOf() } += callEdits
            }
        }
        if (unsafe) continue

        for ((file, edits) in candidateEditsByFile) {
            editsByFile.getOrPut(file) { mutableListOf() }.addAll(edits)
            changes.add(Change(
                file = file,
                line = 1,
                description = "Thread HolderLookup.Provider through ContainerHelper save/load helper method ${candidate.method.name}",
                before = "${candidate.method.name}(... CompoundTag)",
                after = "${candidate.method.name}(..., HolderLookup.Provider)",
                confidence = Confidence.HIGH,
                ruleId = "struct-container-helper-provider-thread"
            ))
        }
        importFiles.add(candidate.file)
    }

    if (!dryRun) {
        for ((file, edits) in editsByFile) {
            val original = sources[file] ?: continue
            var migrated = applyStringEdits(original, edits)
            if (file in importFiles) {
                migrated = addImportIfMissing(migrated, "net.minecraft.core.HolderLookup")
            }
            if (migrated != original) {
                file.writeText(migrated)
            }
        }
    }
    return changes
}

internal fun StructuralRefactorPass.containerHelperCallsNeedProvider(methodText: String): Boolean {
    val executable = maskJavaCommentsAndLiterals(methodText)
    return Regex("""ContainerHelper\.(?:saveAllItems|loadAllItems)\s*\(""")
        .findAll(executable)
        .any { match ->
            val openParen = executable.indexOf('(', match.range.last)
            val closeParen = if (openParen >= 0) findMatchingParen(executable, openParen) else -1
            closeParen > openParen &&
                splitTopLevelJavaArgs(methodText.substring(openParen + 1, closeParen)).size == 2
        }
}

internal fun StructuralRefactorPass.methodCallProviderEditsForSource(
    source: String,
    methodName: String,
    originalParameterCount: Int,
    declarationRange: IntRange?,
    javaInheritanceIndex: JavaInheritanceIndex
): List<Pair<IntRange, String>>? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val token = "$methodName("
    val edits = mutableListOf<Pair<IntRange, String>>()
    var cursor = 0
    while (true) {
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0 && (executableCode[tokenIndex - 1].isLetterOrDigit() || executableCode[tokenIndex - 1] == '_' || executableCode[tokenIndex - 1] == '$')) {
            cursor = tokenIndex + token.length
            continue
        }
        val openParen = tokenIndex + methodName.length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        if (looksLikeJavaMethodDeclaration(executableCode, tokenIndex, closeParen)) {
            cursor = closeParen + 1
            continue
        }
        if (declarationRange != null && tokenIndex in declarationRange) {
            return null
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen)).map { it.trim() }
        when (args.size) {
            originalParameterCount -> {
                val registryAccess = registryAccessExpressionAt(source, tokenIndex, javaInheritanceIndex)
                    ?: return null
                edits += (openParen + 1 until closeParen) to (args + registryAccess).joinToString(", ")
            }
            originalParameterCount + 1 -> {
            }
            else -> return null
        }
        cursor = closeParen + 1
    }
    return edits
}

/**
 * Migrate Block.use(BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)
 * to Block.useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult) in 1.21.1.
 *
 * Changes: method name, visibility public->protected, remove InteractionHand param,
 * remove InteractionHand checks, update super calls.
 */
internal fun StructuralRefactorPass.normalizeIntroducedJavaImports(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()

    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val original = javaFile.readText()
            val executableCode = maskJavaCommentsAndLiterals(original)
            var modified = original
            if (executableCode.contains("BuiltInRegistries.") &&
                !executableCode.contains("import net.minecraft.core.registries.BuiltInRegistries;")
            ) {
                modified = addImportIfMissing(modified, "net.minecraft.core.registries.BuiltInRegistries")
            }
            if (modified != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Normalize imports introduced by source-level API rewrites",
                    before = "BuiltInRegistries usage without import",
                    after = "import net.minecraft.core.registries.BuiltInRegistries;",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-final-introduced-imports"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }
    return changes
}

/**
 * Fix EVENT_BUS.register(this) when @SubscribeEvent methods are static.
 * NeoForge 1.21.1 requires class registration for static methods.
 */
/**
 * Clean up orphaned SimpleChannel code after payload registration has been generated.
 * Old SimpleChannel setup is removed once ModNetwork/RegisterPayloadHandlersEvent owns
 * equivalent packet registration.
 */
internal fun StructuralRefactorPass.cleanupSimpleChannelRemnants(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val text = javaFile.toFile().readText()

            fun isLegacySimpleChannelCleanupComment(line: String): Boolean {
                val trimmed = line.trimStart()
                return trimmed.startsWith("//") &&
                    (trimmed.contains("SimpleChannel field removed") ||
                        trimmed.contains("registerMessage() removed") ||
                        (trimmed.contains("HANDLER removed") && trimmed.contains("RegisterPayloadHandlersEvent")))
            }

            // Marker comments are the exact handoff from a source-shaped packet migration.
            // A sibling ModNetwork.java is not evidence that arbitrary fluent chains in this
            // file belong to SimpleChannel.
            if (text.lineSequence().none(::isLegacySimpleChannelCleanupComment)) return@forEach

            var modified = text

            val lines = modified.lines()
            val cleanedLines = mutableListOf<String>()
            var removedRegisterMessages = false
            val removedRegisterMessageComment = lines.any { line ->
                isLegacySimpleChannelCleanupComment(line) && line.contains("registerMessage() removed")
            }
            for (line in lines) {
                val trimmed = line.trimStart()
                val removeLine =
                    isLegacySimpleChannelCleanupComment(line) ||
                        trimmed == "int packetIndex = 0;" && removedRegisterMessageComment
                if (removeLine) {
                    removedRegisterMessages = true
                } else {
                    cleanedLines.add(line)
                }
            }
            if (removedRegisterMessages) {
                modified = cleanedLines.joinToString("\n")
            }

            if (modified != text) {
                if (!dryRun) {
                    javaFile.toFile().writeText(modified)
                }
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Clean up orphaned SimpleChannel code in ${javaFile.fileName}",
                    before = "(orphaned SimpleChannel chain calls)",
                    after = "(removed after ModNetwork migration)",
                    confidence = Confidence.HIGH,
                    ruleId = "structural-simplechannel-cleanup"
                ))
            }
        }

    return changes
}

internal fun StructuralRefactorPass.fixStaticEventBusRegistration(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val text = javaFile.toFile().readText()

            // Check if file has EVENT_BUS.register(this)
            if (!text.contains(".register(this)")) return@forEach

            // Detect outer class name
            val classNameMatch = Regex("""class\s+(\w+)""").find(text) ?: return@forEach
            val className = classNameMatch.groupValues[1]

            // Check if outer class (not inner classes) has @SubscribeEvent methods
            val lines = text.lines()
            var inOuterClass = false
            var braceDepth = 0
            var outerClassDepth = -1
            var hasOuterStaticSubscribeEvent = false
            var hasOuterInstanceSubscribeEvent = false

            for (i in lines.indices) {
                val line = lines[i]
                if (!inOuterClass && line.contains("class $className")) {
                    inOuterClass = true
                    outerClassDepth = braceDepth
                }
                braceDepth += line.count { it == '{' } - line.count { it == '}' }
                if (inOuterClass && braceDepth <= outerClassDepth) {
                    break // exited outer class
                }
                // Only count @SubscribeEvent in the outer class (depth == outerClassDepth + 1)
                if (inOuterClass && braceDepth == outerClassDepth + 1) {
                    if (line.trim().startsWith("@SubscribeEvent") && i + 1 < lines.size) {
                        val nextLine = lines[i + 1].trim()
                        if (nextLine.startsWith("public static")) {
                            hasOuterStaticSubscribeEvent = true
                        } else if (nextLine.startsWith("public ") || nextLine.startsWith("private ") || nextLine.startsWith("protected ")) {
                            hasOuterInstanceSubscribeEvent = true
                        }
                    }
                }
            }

            var modified = text
            if (hasOuterStaticSubscribeEvent && !hasOuterInstanceSubscribeEvent) {
                // All @SubscribeEvent methods are static: register the class
                modified = text.replace(
                    Regex("""(EVENT_BUS|NeoForge\.EVENT_BUS)\.register\(this\)"""),
                    "$1.register(${className}.class)"
                )
                if (modified != text) {
                    changes.add(Change(
                        file = javaFile, line = 0,
                        description = "Fix static @SubscribeEvent: register(this) -> register(${className}.class)",
                        before = "EVENT_BUS.register(this)",
                        after = "EVENT_BUS.register(${className}.class)",
                        confidence = Confidence.HIGH,
                        ruleId = "structural-static-event-bus-register"
                    ))
                }
            } else if (!hasOuterStaticSubscribeEvent && !hasOuterInstanceSubscribeEvent) {
                // No @SubscribeEvent in outer class — remove the register(this) call
                modified = modified.replace(
                    Regex("""[ \t]*(NeoForge\.)?EVENT_BUS\.register\(this\);\s*\r?\n"""),
                    ""
                )
                if (modified != text) {
                    changes.add(Change(
                        file = javaFile, line = 0,
                        description = "Remove EVENT_BUS.register(this) — no @SubscribeEvent methods in ${className}",
                        before = "EVENT_BUS.register(this)",
                        after = "// Removed: no @SubscribeEvent in outer class",
                        confidence = Confidence.HIGH,
                        ruleId = "structural-remove-event-bus-register"
                    ))
                }
            }
            // If hasOuterInstanceSubscribeEvent, keep register(this) as-is

            if (modified != text && !dryRun) {
                javaFile.toFile().writeText(modified)
            }
        }

    return changes
}

internal fun StructuralRefactorPass.migrateBlockUseMethod(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val text = javaFile.toFile().readText()

            // Match Block.use() override signature with 6 params (BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult);
            // parameters may carry annotations or `final`, and the method may be `final`
            val param = """(?:(?:@[\w.]+(?:\([^)]*\))?|final)\s+)*(\w+)\s+(\w+)"""
            val useMethodPattern = Regex(
                """([ \t]*@(?:SuppressWarnings\([^)]*\)\s*\r?\n\s*@)?Override\s*\r?\n)?""" +
                """([ \t]*)(?:public\s+)((?:final\s+)?)(@?\s*\w+(?:<[^>]+>)?\s+)use\s*\(\s*""" +
                """$param\s*,\s*""" +     // BlockState param
                """$param\s*,\s*""" +     // Level/World param
                """$param\s*,\s*""" +     // BlockPos param
                """$param\s*,\s*""" +     // Player param
                """$param\s*,\s*""" +     // InteractionHand param
                """$param\s*\)""",        // BlockHitResult param
                RegexOption.MULTILINE
            )
            val blockUseTypes = listOf(setOf("BlockState"), setOf("Level", "World"), setOf("BlockPos"), setOf("Player"), setOf("InteractionHand"), setOf("BlockHitResult"))

            var modified = text
            val match = useMethodPattern.findAll(modified).firstOrNull { candidate ->
                blockUseTypes.withIndex().all { (index, types) -> candidate.groupValues[5 + 2 * index] in types }
            } ?: return@forEach

            val indent = match.groupValues[2]
            val modifiers = match.groupValues[3]
            val returnType = match.groupValues[4]
            val stateType = match.groupValues[5]; val stateName = match.groupValues[6]
            val levelType = match.groupValues[7]; val levelName = match.groupValues[8]
            val posType = match.groupValues[9]; val posName = match.groupValues[10]
            val playerType = match.groupValues[11]; val playerName = match.groupValues[12]
            val handType = match.groupValues[13]
            val handName = match.groupValues[14]
            val hitType = match.groupValues[15]; val hitName = match.groupValues[16]
            val openBrace = modified.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(modified, openBrace) else -1
            val methodBody = if (openBrace >= 0 && closeBrace > openBrace) {
                modified.substring(openBrace + 1, closeBrace)
            } else {
                ""
            }
            val executableMethodBody = maskJavaCommentsAndLiterals(methodBody)
            val recursiveUseForwardPattern = Regex(
                """(?<![\w$.])use\s*\(\s*([A-Za-z_$][\w$]*)\s*,\s*""" +
                    """${Regex.escape(levelName)}\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*""" +
                    """${Regex.escape(playerName)}\s*,\s*${Regex.escape(handName)}\s*,\s*""" +
                    """${Regex.escape(hitName)}\s*\)"""
            )
            val bodyWithoutRecursiveUseForwarding =
                recursiveUseForwardPattern.replace(executableMethodBody, "")
            val usesHeldItem = executableMethodBody.contains("getItemInHand(") ||
                executableMethodBody.contains("getMainHandItem()") ||
                Regex("""\bItemStack\b""").containsMatchIn(executableMethodBody) ||
                Regex("""\b${Regex.escape(handName)}\b""")
                    .containsMatchIn(bodyWithoutRecursiveUseForwarding)

            if (usesHeldItem && openBrace >= 0 && closeBrace > openBrace) {
                val newSig = "${indent}@Override\n${indent}protected ${modifiers}net.minecraft.world.ItemInteractionResult useItemOn(" +
                    "net.minecraft.world.item.ItemStack itemstack, $stateType $stateName, $levelType $levelName, " +
                    "$posType $posName, $playerType $playerName, $handType $handName, $hitType $hitName)"

                var newBody = methodBody
                newBody = Regex(
                    """(?m)^([ \t]*)(?:net\.minecraft\.world\.item\.)?ItemStack\s+itemstack\s*=\s*$playerName\.(?:getItemInHand\(\s*$handName\s*\)|getMainHandItem\(\s*\));\s*\r?\n"""
                ).replace(newBody, "")
                newBody = Regex(
                    """(?m)^([ \t]*)(?:net\.minecraft\.world\.item\.)?ItemStack\s+([A-Za-z_$][\w$]*)\s*=\s*$playerName\.(?:getItemInHand\(\s*$handName\s*\)|getMainHandItem\(\s*\));"""
                ).replace(newBody, "$1net.minecraft.world.item.ItemStack $2 = itemstack;")
                // Lambdas and anonymous classes inside the method keep returning InteractionResult.
                for ((legacy, migrated) in listOf(
                    "InteractionResult.sidedSuccess(" to "net.minecraft.world.ItemInteractionResult.sidedSuccess(",
                    "InteractionResult.PASS" to "net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION",
                    "InteractionResult.CONSUME" to "net.minecraft.world.ItemInteractionResult.CONSUME",
                    "InteractionResult.SUCCESS" to "net.minecraft.world.ItemInteractionResult.SUCCESS",
                    "InteractionResult.FAIL" to "net.minecraft.world.ItemInteractionResult.FAIL"
                )) {
                    val boundary = if (legacy.last().isLetterOrDigit()) """(?![\w$])""" else ""
                    newBody = replaceOutsideNestedBodies(newBody, Regex("""(?<![\w$.])${Regex.escape(legacy)}$boundary""")) { migrated }
                }
                // a constant compared with a value (`res.getResult() == InteractionResult.SUCCESS`) still
                // meets an InteractionResult: only what the method returns changed type
                val constant = """net\.minecraft\.world\.ItemInteractionResult\.([A-Z_]+)(?![\w$])"""
                fun legacyConstant(name: String) = "InteractionResult.${if (name == "PASS_TO_DEFAULT_BLOCK_INTERACTION") "PASS" else name}"
                newBody = Regex("""([!=]=\s*)$constant""").replace(newBody) { "${it.groupValues[1]}${legacyConstant(it.groupValues[2])}" }
                newBody = Regex("""$constant(\s*[!=]=)""").replace(newBody) { "${legacyConstant(it.groupValues[1])}${it.groupValues[2]}" }
                newBody = newBody
                    .replace(
                        Regex("""super\.use\(\s*$stateName\s*,\s*$levelName\s*,\s*$posName\s*,\s*$playerName\s*,\s*$handName\s*,\s*$hitName\s*\)"""),
                        "super.useItemOn(itemstack, $stateName, $levelName, $posName, $playerName, $handName, $hitName)"
                    )
                // ...but the inherited useItemOn returns ItemInteractionResult, so a constant
                // compared with it is one of those
                fun itemConstant(name: String) = "net.minecraft.world.ItemInteractionResult." +
                    if (name == "PASS") "PASS_TO_DEFAULT_BLOCK_INTERACTION" else name
                val superItemCall = """super\.useItemOn\([^()]*\)"""
                newBody = Regex("""($superItemCall\s*[!=]=\s*)InteractionResult\.([A-Z_]+)(?![\w$])""")
                    .replace(newBody) { "${it.groupValues[1]}${itemConstant(it.groupValues[2])}" }
                newBody = Regex("""(?<![\w$.])InteractionResult\.([A-Z_]+)(\s*[!=]=\s*$superItemCall)""")
                    .replace(newBody) { "${itemConstant(it.groupValues[1])}${it.groupValues[2]}" }

                val lineStart = modified.lastIndexOf('\n', match.range.first) + 1
                val annotatedSig = if (modified.substring(lineStart, match.range.first).contains("@Override")) {
                    newSig.replaceFirst("@Override\n$indent", "")
                } else {
                    newSig
                }
                modified = modified.substring(0, match.range.first) +
                    annotatedSig +
                    modified.substring(match.range.last + 1, openBrace + 1) +
                    newBody +
                    modified.substring(closeBrace)
            } else {
                // Build new method signature (without InteractionHand param)
                val newSig = "${indent}@Override\n${indent}protected ${modifiers}${returnType}useWithoutItem(" +
                    "$stateType $stateName, $levelType $levelName, $posType $posName, " +
                    "$playerType $playerName, $hitType $hitName)"

                var newBody = methodBody
                newBody = newBody.replace(
                    recursiveUseForwardPattern
                ) { forwarded ->
                    "useWithoutItem(${forwarded.groupValues[1]}, $levelName, " +
                        "${forwarded.groupValues[2]}, $playerName, $hitName)"
                }
                newBody = newBody.replace(
                    Regex("""super\.use\(\s*$stateName\s*,\s*$levelName\s*,\s*$posName\s*,\s*$playerName\s*,\s*$handName\s*,\s*$hitName\s*\)"""),
                    "super.useWithoutItem($stateName, $levelName, $posName, $playerName, $hitName)"
                )
                modified = modified.substring(0, match.range.first) +
                    newSig +
                    modified.substring(match.range.last + 1, openBrace + 1) +
                    newBody +
                    modified.substring(closeBrace)

                // Remove InteractionHand-related checks: if(hand==InteractionHand.MAIN_HAND) or similar
                // Handle "if ... && hand==InteractionHand.MAIN_HAND" by removing the hand condition
                modified = modified.replace(
                    Regex("""&&\s*$handName\s*==\s*InteractionHand\.\w+"""), ""
                )
                modified = modified.replace(
                    Regex("""$handName\s*==\s*InteractionHand\.\w+\s*&&\s*"""), ""
                )
                modified = unwrapStandaloneInteractionHandChecks(modified, handName)
            }

            // Remove unused InteractionHand import if no other references remain
            if (!modified.contains("InteractionHand") ||
                modified.lines().none { it.trim().let { t -> !t.startsWith("import") && !t.startsWith("//") && t.contains("InteractionHand") } }) {
                modified = modified.replace(
                    Regex("""^import\s+net\.minecraft\.world\.InteractionHand\s*;\s*\r?\n""", RegexOption.MULTILINE),
                    ""
                )
            }

            // Remove @SuppressWarnings("deprecation") that was before the old @Override
            // (useWithoutItem is not deprecated, so the suppression is no longer needed)

            if (modified != text) {
                if (!dryRun) {
                    javaFile.toFile().writeText(modified)
                }
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate Block.use() to 1.21 block interaction hook in ${javaFile.fileName}",
                    before = "public InteractionResult use(..., InteractionHand, BlockHitResult)",
                    after = if (usesHeldItem) {
                        "protected ItemInteractionResult useItemOn(ItemStack, ..., InteractionHand, BlockHitResult)"
                    } else {
                        "protected InteractionResult useWithoutItem(..., BlockHitResult)"
                    },
                    confidence = Confidence.HIGH,
                    ruleId = "structural-block-use-to-useWithoutItem"
                ))
            }
        }

    return changes
}

/**
 * Detect ICapabilityProvider implementations and flag for migration.
 */
internal fun StructuralRefactorPass.detectCapabilityPatterns(
    cu: CompilationUnit,
    file: Path,
    changes: MutableList<Change>
) {
    cu.findAll(ClassOrInterfaceDeclaration::class.java).forEach { classDecl ->
        // Check if implements ICapabilityProvider
        val hasCapProvider = classDecl.implementedTypes.any {
            it.nameAsString == "ICapabilityProvider"
        }

        if (hasCapProvider) {
            val line = classDecl.begin.map { it.line }.orElse(0)
            changes.add(Change(
                file = file, line = line,
                description = "CLASS IMPLEMENTS ICapabilityProvider - needs migration to RegisterCapabilitiesEvent pattern",
                before = "class ${classDecl.nameAsString} implements ICapabilityProvider",
                after = "Register the provider through RegisterCapabilitiesEvent and remove ICapabilityProvider from the class",
                confidence = Confidence.HIGH,
                ruleId = "struct-capability-provider"
            ))
        }

        // Detect getCapability method overrides
        classDecl.methods.filter { it.nameAsString == "getCapability" }.forEach { method ->
            val methodLine = method.begin.map { it.line }.orElse(0)
            changes.add(Change(
                file = file, line = methodLine,
                description = "getCapability() override detected - migrate to RegisterCapabilitiesEvent provider lambda",
                before = method.declarationAsString,
                after = "// Register via: event.registerBlockEntity(Capabilities.XXX, MY_TYPE, (be, side) -> ...)",
                confidence = Confidence.HIGH,
                ruleId = "struct-capability-getcap"
            ))
        }
    }
}

/**
 * Detect SimpleChannel / networking patterns and flag for migration.
 */
internal fun StructuralRefactorPass.detectNetworkingPatterns(
    cu: CompilationUnit,
    file: Path,
    changes: MutableList<Change>
) {
    // Detect SimpleChannel field declarations
    cu.findAll(FieldDeclaration::class.java).forEach { field ->
        val typeStr = field.elementType.toString()
        if (typeStr.contains("SimpleChannel") || typeStr.contains("EventNetworkChannel")) {
            val line = field.begin.map { it.line }.orElse(0)
            changes.add(Change(
                file = file, line = line,
                description = "SimpleChannel/EventNetworkChannel detected - migrate to CustomPacketPayload system",
                before = field.toString().trim(),
                after = "Replace with CustomPacketPayload types and RegisterPayloadHandlersEvent registration",
                confidence = Confidence.HIGH,
                ruleId = "struct-networking-channel"
            ))
        }
    }

    // Detect NetworkRegistry.newSimpleChannel calls
    cu.findAll(MethodCallExpr::class.java).forEach { call ->
        if (call.nameAsString == "newSimpleChannel" || call.nameAsString == "registerMessage") {
            val line = call.begin.map { it.line }.orElse(0)
            changes.add(Change(
                file = file, line = line,
                description = "Network registration call detected - migrate to RegisterPayloadHandlersEvent",
                before = call.toString(),
                after = "// Register via: event.registrar(MODID).playToServer(TYPE, CODEC, HANDLER)",
                confidence = Confidence.HIGH,
                ruleId = "struct-networking-register"
            ))
        }
    }
}

/**
 * Detect DistExecutor usage (removed in NeoForge 1.21.1).
 * DistExecutor.unsafeRunWhenOn/safeRunWhenOn should be replaced with direct if (FMLLoader.getDist() == Dist.CLIENT) checks.
 */
internal fun StructuralRefactorPass.migrateDistExecutorDistGuards(
    source: String,
    file: Path,
    changes: MutableList<Change>,
    projectDistExecutorTypes: Set<String>
): String {
    if (!hasNativeDistExecutorEvidence(source, projectDistExecutorTypes)) return source
    var result = source
    var migrated = false

    val safeCallMethodReferencePattern = Regex(
        """DistExecutor\.(unsafeCallWhenOn|safeCallWhenOn)\(\s*Dist\.(CLIENT|DEDICATED_SERVER)\s*,\s*\(\s*\)\s*->\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::([A-Za-z_$][\w$]*)\s*\)"""
    )
    val beforeSafeCallMigration = result
    result = replaceExecutableRegex(beforeSafeCallMigration, safeCallMethodReferencePattern) { match ->
        val methodName = match.groupValues[1]
        val dist = match.groupValues[2]
        val owner = match.groupValues[3]
        val referencedMethod = match.groupValues[4]
        val replacement = "(FMLLoader.getDist() == Dist.$dist ? $owner.$referencedMethod() : null)"
        migrated = true
        changes.add(distExecutorMigrationChange(
            file = file,
            line = lineNumberAt(beforeSafeCallMigration, match.range.first),
            methodName = methodName,
            dist = dist,
            before = match.value.trim(),
            after = replacement
        ))
        replacement
    }

    val enqueueExpressionPattern = Regex(
        """(?m)^([ \t]*)([\w.]+\.enqueueWork\(\s*)\(\s*\)\s*->\s*DistExecutor\.(unsafeRunWhenOn|safeRunWhenOn)\(\s*Dist\.(CLIENT|DEDICATED_SERVER)\s*,\s*\(\s*\)\s*->\s*\(\s*\)\s*->\s*([^{};]+?)\s*\)\s*\)\s*;"""
    )
    val beforeExpressionMigration = result
    result = enqueueExpressionPattern.replace(beforeExpressionMigration) { match ->
        val indent = match.groupValues[1]
        val enqueueCall = match.groupValues[2]
        val methodName = match.groupValues[3]
        val dist = match.groupValues[4]
        val expression = match.groupValues[5].trim()
        val expressionStatement = expression
            .lines()
            .filter { it.isNotBlank() }
            .joinToString("\n") { "$indent        ${it.trim()}" }
            .let { if (it.endsWith(";")) it else "$it;" }
        val replacement = """
${indent}${enqueueCall}() -> {
${indent}    if (FMLLoader.getDist() == Dist.$dist) {
$expressionStatement
${indent}    }
${indent}});
""".trimEnd()
        migrated = true
        changes.add(distExecutorMigrationChange(
            file = file,
            line = lineNumberAt(beforeExpressionMigration, match.range.first),
            methodName = methodName,
            dist = dist,
            before = match.value.trim(),
            after = replacement.trim()
        ))
        replacement
    }

    val enqueueBlockPattern = Regex(
        """(?m)^([ \t]*)([\w.]+\.enqueueWork\(\s*)\(\s*\)\s*->\s*DistExecutor\.(unsafeRunWhenOn|safeRunWhenOn)\(\s*Dist\.(CLIENT|DEDICATED_SERVER)\s*,\s*\(\s*\)\s*->\s*\(\s*\)\s*->\s*\{\s*\r?\n([\s\S]*?)\r?\n\1\}\s*\)\s*\)\s*;"""
    )
    val beforeEnqueueBlockMigration = result
    result = enqueueBlockPattern.replace(beforeEnqueueBlockMigration) { match ->
        val indent = match.groupValues[1]
        val enqueueCall = match.groupValues[2]
        val methodName = match.groupValues[3]
        val dist = match.groupValues[4]
        val body = match.groupValues[5].trimEnd()
        val replacement = """
${indent}${enqueueCall}() -> {
${indent}    if (FMLLoader.getDist() == Dist.$dist) {
$body
${indent}    }
${indent}});
""".trimEnd()
        migrated = true
        changes.add(distExecutorMigrationChange(
            file = file,
            line = lineNumberAt(beforeEnqueueBlockMigration, match.range.first),
            methodName = methodName,
            dist = dist,
            before = match.value.trim(),
            after = replacement.trim()
        ))
        replacement
    }

    val directExpressionPattern = Regex(
        """(?m)^([ \t]*)DistExecutor\.(unsafeRunWhenOn|safeRunWhenOn)\(\s*Dist\.(CLIENT|DEDICATED_SERVER)\s*,\s*\(\s*\)\s*->\s*\(\s*\)\s*->\s*([^{};\r\n]+)\s*\)\s*;"""
    )
    val beforeDirectExpressionMigration = result
    result = directExpressionPattern.replace(beforeDirectExpressionMigration) { match ->
        val indent = match.groupValues[1]
        val methodName = match.groupValues[2]
        val dist = match.groupValues[3]
        val expression = match.groupValues[4].trim()
        val statement = if (expression.endsWith(";")) expression else "$expression;"
        val replacement = """
${indent}if (FMLLoader.getDist() == Dist.$dist) {
${indent}    $statement
${indent}}
""".trimEnd()
        migrated = true
        changes.add(distExecutorMigrationChange(
            file = file,
            line = lineNumberAt(beforeDirectExpressionMigration, match.range.first),
            methodName = methodName,
            dist = dist,
            before = match.value.trim(),
            after = replacement.trim()
        ))
        replacement
    }

    val methodReferencePattern = Regex(
        """(?m)^([ \t]*)DistExecutor\.(unsafeRunWhenOn|safeRunWhenOn)\(\s*Dist\.(CLIENT|DEDICATED_SERVER)\s*,\s*\(\s*\)\s*->\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::([A-Za-z_$][\w$]*)\s*\)\s*;"""
    )
    val beforeMethodReferenceMigration = result
    result = methodReferencePattern.replace(beforeMethodReferenceMigration) { match ->
        val indent = match.groupValues[1]
        val methodName = match.groupValues[2]
        val dist = match.groupValues[3]
        val owner = match.groupValues[4]
        val referencedMethod = match.groupValues[5]
        val replacement = """
${indent}if (FMLLoader.getDist() == Dist.$dist) {
${indent}    $owner.$referencedMethod();
${indent}}
""".trimEnd()
        migrated = true
        changes.add(distExecutorMigrationChange(
            file = file,
            line = lineNumberAt(beforeMethodReferenceMigration, match.range.first),
            methodName = methodName,
            dist = dist,
            before = match.value.trim(),
            after = replacement.trim()
        ))
        replacement
    }

    val blockStatementPattern = Regex(
        """(?m)^([ \t]*)DistExecutor\.(unsafeRunWhenOn|safeRunWhenOn)\(\s*Dist\.(CLIENT|DEDICATED_SERVER)\s*,\s*\(\s*\)\s*->\s*\(\s*\)\s*->\s*\{\s*\r?\n([\s\S]*?)\r?\n\1\}\s*\)\s*;"""
    )
    val beforeBlockMigration = result
    result = blockStatementPattern.replace(beforeBlockMigration) { match ->
        val indent = match.groupValues[1]
        val methodName = match.groupValues[2]
        val dist = match.groupValues[3]
        val body = match.groupValues[4].trimEnd()
        val replacement = """
${indent}if (FMLLoader.getDist() == Dist.$dist) {
$body
${indent}}
""".trimEnd()
        migrated = true
        changes.add(distExecutorMigrationChange(
            file = file,
            line = lineNumberAt(beforeBlockMigration, match.range.first),
            methodName = methodName,
            dist = dist,
            before = match.value.trim(),
            after = replacement.trim()
        ))
        replacement
    }

    val runForDistClientBlockPattern = Regex(
        """(?m)^([ \t]*)DistExecutor\.(unsafeRunForDist|safeRunForDist)\(\s*\(\s*\)\s*->\s*\(\s*\)\s*->\s*\{\s*\r?\n([\s\S]*?)\r?\n\1\}\s*,\s*\(\s*\)\s*->\s*\(\s*\)\s*->\s*(?:false|true|null)\s*\)\s*;"""
    )
    val beforeRunForDistMigration = result
    result = runForDistClientBlockPattern.replace(beforeRunForDistMigration) { match ->
        val indent = match.groupValues[1]
        val methodName = match.groupValues[2]
        val body = removeTrailingSupplierReturn(match.groupValues[3]).trimEnd()
        val replacement = if (body.isBlank()) {
            """
${indent}if (FMLLoader.getDist() == Dist.CLIENT) {
${indent}}
""".trimEnd()
        } else {
            """
${indent}if (FMLLoader.getDist() == Dist.CLIENT) {
$body
${indent}}
""".trimEnd()
        }
        migrated = true
        changes.add(distExecutorMigrationChange(
            file = file,
            line = lineNumberAt(beforeRunForDistMigration, match.range.first),
            methodName = methodName,
            dist = "CLIENT",
            before = match.value.trim(),
            after = replacement.trim()
        ))
        replacement
    }

    // Call forms used as expressions: the Forge contract returns null off the dist. The callable
    // body may be any expression (including switch expressions), so the call is cut at its own
    // closing parenthesis.
    val callExpressionStart = Regex(
        """DistExecutor\.(unsafeCallWhenOn|safeCallWhenOn)\(\s*Dist\.(CLIENT|DEDICATED_SERVER)\s*,\s*\(\s*\)\s*->\s*\(\s*\)\s*->\s*"""
    )
    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val match = callExpressionStart.find(executableResult) ?: break
        val openParen = executableResult.indexOf('(', match.range.first)
        val closeParen = findMatchingParen(executableResult, openParen)
        if (closeParen <= openParen) break
        val methodName = match.groupValues[1]
        val dist = match.groupValues[2]
        val expression = result.substring(match.range.last + 1, closeParen).trim()
        val original = result.substring(match.range.first, closeParen + 1)
        val replacement = "(FMLLoader.getDist() == Dist.$dist ? $expression : null)"
        migrated = true
        changes.add(distExecutorMigrationChange(
            file = file,
            line = lineNumberAt(result, match.range.first),
            methodName = methodName,
            dist = dist,
            before = original.trim(),
            after = replacement
        ))
        result = result.substring(0, match.range.first) + replacement + result.substring(closeParen + 1)
    }

    if (!migrated) return result

    val hasResidualDistExecutorCall = Regex(
        """(?<![\w$.])DistExecutor\.[A-Za-z_$][\w$]*\s*\("""
    ).containsMatchIn(maskJavaCommentsAndLiterals(result))
    if (!hasResidualDistExecutorCall) {
        result = removeDistExecutorImports(result)
    }
    result = addImportIfMissing(result, "net.neoforged.api.distmarker.Dist")
    return addImportIfMissing(result, "net.neoforged.fml.loading.FMLLoader")
}

internal fun StructuralRefactorPass.removeTrailingSupplierReturn(body: String): String {
    val lines = body.lines().toMutableList()
    var index = lines.lastIndex
    while (index >= 0 && lines[index].isBlank()) index--
    if (index >= 0 && Regex("""^\s*return\s+[^;]+;\s*$""").matches(lines[index])) {
        lines.removeAt(index)
    }
    return lines.joinToString("\n")
}


internal fun StructuralRefactorPass.hasNativeDistExecutorEvidence(
    source: String,
    projectDistExecutorTypes: Set<String>
): Boolean {
    val executable = maskJavaCommentsAndLiterals(source)
    val nativeImport = Regex(
        """(?m)^\s*import\s+net\.(?:minecraftforge|neoforged)\.fml\.DistExecutor\s*;\s*$"""
    ).containsMatchIn(executable)
    val previousPassNativeImport = Regex(
        """(?m)^\s*//\s*(?:\[[^]\r\n]+]\s*)?import\s+net\.(?:minecraftforge|neoforged)\.fml\.DistExecutor\s*;\s*$"""
    ).containsMatchIn(source)
    val nonNativeImport = Regex(
        """(?m)^\s*import\s+(?!net\.(?:minecraftforge|neoforged)\.fml\.DistExecutor\s*;)[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.DistExecutor\s*;\s*$"""
    ).containsMatchIn(executable)
    val projectDeclaration = Regex("""\b(?:class|interface|enum|record)\s+DistExecutor\b""")
        .containsMatchIn(executable)
    val packageName = packageNameOf(source)
    val samePackageProjectOwner = projectDistExecutorTypes.any { owner ->
        owner.substringBeforeLast('.', "") == packageName
    }
    val wildcardImports = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.\*\s*;\s*$""")
        .findAll(executable)
        .map { it.groupValues[1] }
        .toSet()
    val wildcardProjectOwner = projectDistExecutorTypes.any { owner ->
        owner.substringBeforeLast('.', "") in wildcardImports
    }
    return !nonNativeImport && !projectDeclaration && !samePackageProjectOwner && !wildcardProjectOwner &&
        (nativeImport || previousPassNativeImport)
}

internal fun StructuralRefactorPass.distExecutorMigrationChange(
    file: Path,
    line: Int,
    methodName: String,
    dist: String,
    before: String,
    after: String
): Change = Change(
    file = file,
    line = line,
    description = "Convert DistExecutor.$methodName(Dist.$dist) to FMLLoader dist guard",
    before = before,
    after = after,
    confidence = Confidence.HIGH,
    ruleId = "struct-dist-executor-dist-guard"
)

internal fun StructuralRefactorPass.removeDistExecutorImports(source: String): String {
    var result = source
    result = removeExecutableImport(result, "net.minecraftforge.fml.DistExecutor")
    result = removeExecutableImport(result, "net.neoforged.fml.DistExecutor")
    return removeStandaloneLineCommentsOutsideJavaLiterals(
        result,
        listOf(
            Regex("""//\s*(?:\[[^\]\r\n]+]\s*)?import\s+net\.(?:minecraftforge|neoforged)\.fml\.DistExecutor;\s*"""),
            Regex("""//\s*DistExecutor removed in NeoForge\s*""")
        )
    )
}

internal fun StructuralRefactorPass.cleanupCommentedOutJavaLogicBlocks(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()

    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
            .filter { !srcDir.relativize(it).toString().replace('\\', '/').startsWith("references/") }
            .forEach { file ->
                val original = file.readText()
                val ranges = javaBlockCommentRanges(original)
                    .filter { range -> isCommentedOutJavaLogicBlock(original.substring(range)) }
                if (ranges.isEmpty()) return@forEach

                val modified = cleanupRedundantBlankLines(
                    buildString(original.length) {
                        var cursor = 0
                        for (range in ranges) {
                            append(original, cursor, range.first)
                            cursor = range.last + 1
                        }
                        append(original, cursor, original.length)
                    }
                )
                val firstLine = original.substring(0, ranges.first().first).count { it == '\n' } + 1
                changes.add(Change(
                    file = file,
                    line = firstLine,
                    description = "Remove commented-out Java logic from converted source",
                    before = "block comment containing disabled migration-sensitive Java logic",
                    after = "comment block removed",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-cleanup-commented-bypass-blocks"
                ))
                if (!dryRun) file.writeText(modified)
            }
    }
    return changes
}

internal fun StructuralRefactorPass.isCommentedOutJavaLogicBlock(block: String): Boolean {
    val migrationSensitiveConstruct = Regex(
        """@\s*SubscribeEvent[\s\S]{0,200}\bstatic\b|\b(?:BrewingRecipesEvent|BrewingRecipeRegister)\b"""
    )
        .containsMatchIn(block)
    val codeLike = Regex("""\b(public|private|protected|if|return|event\.|import)\b""")
        .containsMatchIn(block)
    return migrationSensitiveConstruct && codeLike
}

internal fun StructuralRefactorPass.javaBlockCommentRanges(source: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var index = 0
    var inLineComment = false
    var inTextBlock = false
    var inString = false
    var inChar = false
    var escaped = false

    while (index < source.length) {
        val ch = source[index]
        val next = source.getOrNull(index + 1)
        val nextTwo = source.getOrNull(index + 2)
        when {
            inLineComment -> {
                if (ch == '\r' || ch == '\n') inLineComment = false
                index++
            }
            inTextBlock -> {
                if (ch == '"' && next == '"' && nextTwo == '"') {
                    inTextBlock = false
                    index += 3
                } else {
                    index++
                }
            }
            inString -> {
                if (ch == '"' && !escaped) inString = false
                escaped = ch == '\\' && !escaped
                if (ch != '\\') escaped = false
                index++
            }
            inChar -> {
                if (ch == '\'' && !escaped) inChar = false
                escaped = ch == '\\' && !escaped
                if (ch != '\\') escaped = false
                index++
            }
            ch == '/' && next == '/' -> {
                inLineComment = true
                index += 2
            }
            ch == '/' && next == '*' -> {
                val start = index
                index += 2
                while (index < source.length - 1 && !(source[index] == '*' && source[index + 1] == '/')) {
                    index++
                }
                val end = if (index < source.length - 1) {
                    val commentEnd = index + 1
                    index += 2
                    commentEnd
                } else {
                    source.lastIndex.also { index = source.length }
                }
                ranges.add(start..end)
            }
            ch == '"' && next == '"' && nextTwo == '"' -> {
                inTextBlock = true
                index += 3
            }
            ch == '"' -> {
                inString = true
                escaped = false
                index++
            }
            ch == '\'' -> {
                inChar = true
                escaped = false
                index++
            }
            else -> index++
        }
    }
    return ranges
}

internal fun StructuralRefactorPass.removeStandaloneLineCommentsOutsideJavaLiterals(source: String, commentPatterns: List<Regex>): String {
    val result = StringBuilder(source.length)
    var index = 0
    var inBlockComment = false
    var inTextBlock = false
    var inString = false
    var inChar = false
    var escaped = false

    while (index < source.length) {
        val lineStart = index
        val newlineIndex = source.indexOf('\n', index)
        val lineEndExclusive = if (newlineIndex < 0) source.length else newlineIndex + 1
        val line = source.substring(lineStart, lineEndExclusive)
        val trimmed = line.trimEnd('\r', '\n').trim()
        val removeLine = !inBlockComment && !inTextBlock && !inString && !inChar &&
            commentPatterns.any { it.matches(trimmed) }

        var offset = 0
        var inLineComment = false
        while (offset < line.length) {
            val ch = line[offset]
            val next = line.getOrNull(offset + 1)
            val nextTwo = line.getOrNull(offset + 2)
            when {
                inLineComment -> {
                    if (ch == '\r' || ch == '\n') inLineComment = false
                }
                inBlockComment -> {
                    if (ch == '*' && next == '/') {
                        offset++
                        inBlockComment = false
                    }
                }
                inTextBlock -> {
                    if (ch == '"' && next == '"' && nextTwo == '"') {
                        offset += 2
                        inTextBlock = false
                    }
                }
                inString -> {
                    if (ch == '"' && !escaped) inString = false
                    escaped = ch == '\\' && !escaped
                    if (ch != '\\') escaped = false
                }
                inChar -> {
                    if (ch == '\'' && !escaped) inChar = false
                    escaped = ch == '\\' && !escaped
                    if (ch != '\\') escaped = false
                }
                ch == '/' && next == '/' -> {
                    offset++
                    inLineComment = true
                }
                ch == '/' && next == '*' -> {
                    offset++
                    inBlockComment = true
                }
                ch == '"' && next == '"' && nextTwo == '"' -> {
                    offset += 2
                    inTextBlock = true
                    escaped = false
                }
                ch == '"' -> {
                    inString = true
                    escaped = false
                }
                ch == '\'' -> {
                    inChar = true
                    escaped = false
                }
            }
            offset++
        }

        if (!removeLine) result.append(line)
        index = lineEndExclusive
    }

    return result.toString()
}

internal fun StructuralRefactorPass.unwrapStandaloneInteractionHandChecks(source: String, handName: String): String {
    var result = source
    val condition = Regex.escape(handName) + """\s*==\s*InteractionHand\.\w+"""
    val ifRegex = Regex("""if\s*\(\s*$condition\s*\)\s*\{""")
    var cursor = 0
    while (true) {
        val match = ifRegex.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val after = result.substring(closeBrace + 1).trimStart()
        if (after.startsWith("else")) {
            cursor = closeBrace + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        result = result.substring(0, match.range.first) + body + result.substring(closeBrace + 1)
        cursor = match.range.first + body.length
    }
    result = Regex("""if\s*\(\s*$condition\s*\)\s*([^;\r\n]+;)""")
        .replace(result) { match -> match.groupValues[1] }
    return result
}

internal fun StructuralRefactorPass.addImportIfMissing(source: String, importName: String): String =
    addExecutableImportIfMissing(source, importName)

internal fun StructuralRefactorPass.removeImportIfPresent(source: String, importName: String): String =
    removeExecutableImport(source, importName)

internal fun StructuralRefactorPass.annotationStartBefore(source: String, declarationIndex: Int): Int {
    val lineStart = source.lastIndexOf('\n', declarationIndex).let { if (it < 0) 0 else it + 1 }
    val annotations = Regex("""(?m)(?:^[ \t]*@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\r?\n)+[ \t]*$""")
        .findAll(source.substring(0, lineStart))
        .lastOrNull()
    return annotations?.range?.first ?: lineStart
}

internal fun StructuralRefactorPass.lineNumberAt(source: String, offset: Int): Int =
    source.take(offset).count { it == '\n' } + 1

internal fun StructuralRefactorPass.detectDistExecutorUsage(
    cu: CompilationUnit,
    file: Path,
    changes: MutableList<Change>,
    hasNativeSimpleOwner: Boolean
) {
    val nativeImports = setOf("net.neoforged.fml.DistExecutor", "net.minecraftforge.fml.DistExecutor")
    cu.findAll(MethodCallExpr::class.java).forEach { call ->
        val scope = call.scope.map { it.toString() }.orElse("")
        if ((scope == "DistExecutor" && hasNativeSimpleOwner) || scope in nativeImports) {
            val line = call.begin.map { it.line }.orElse(0)
            changes.add(Change(
                file = file, line = line,
                description = "DistExecutor.${call.nameAsString}() REMOVED - replace with if (FMLLoader.getDist() == Dist.CLIENT) pattern",
                before = call.toString(),
                after = "// Replace with: if (FMLLoader.getDist() == Dist.CLIENT) { ... }",
                confidence = Confidence.HIGH,
                ruleId = "struct-dist-executor"
            ))
        }
    }
}

/**
 * Data class for packet class detection.
 */
internal data class PacketClassInfo(
    val file: Path,
    val className: String,
    val packageName: String,
    val encodeParamName: String,
    val encodeBufferName: String,
    val handlerRef: String,
    val isPlayToClient: Boolean,
    val bufferType: String,
    val streamCodecExpression: String,
    val namespaceExpression: String,
    val referenceName: String = className,
    val registrationMethod: String? = null
)

internal data class PacketClassCandidate(
    val file: Path,
    val className: String,
    val packageName: String,
    val encodeParamName: String,
    val encodeBufferName: String,
    val handlerRef: String,
    val isPlayToClient: Boolean?,
    val bufferType: String,
    val streamCodecExpression: String
) {
    fun toPacketInfo(
        namespaceExpression: String,
        handlerRef: String = this.handlerRef,
        isPlayToClient: Boolean,
        registrationMethod: String? = null
    ): PacketClassInfo =
        PacketClassInfo(
            file = file,
            className = className,
            packageName = packageName,
            encodeParamName = encodeParamName,
            encodeBufferName = encodeBufferName,
            handlerRef = handlerRef,
            isPlayToClient = isPlayToClient,
            bufferType = bufferType,
            streamCodecExpression = streamCodecExpression,
            namespaceExpression = namespaceExpression,
            referenceName = className,
            registrationMethod = registrationMethod
        )
}

internal data class ResolvedRegisteredPacketType(
    val file: Path,
    val packageName: String,
    val ownerSimpleName: String,
    val simpleName: String,
    val sourceReferenceName: String,
    val registrationReferenceName: String
)

internal fun StructuralRefactorPass.addCustomPacketPayloadInterface(content: String, className: String): String {
    val typeStart = findTypeDeclarationStart(content, className) ?: return content
    val openBrace = content.indexOf('{', typeStart)
    if (openBrace < 0) return content
    val header = content.substring(typeStart, openBrace)
    if (header.contains("implements CustomPacketPayload")) return content
    val insertion = if (Regex("""\bimplements\b""").containsMatchIn(header)) {
        ", CustomPacketPayload"
    } else {
        " implements CustomPacketPayload"
    }
    return content.substring(0, openBrace).trimEnd() + insertion + " " + content.substring(openBrace)
}

internal fun StructuralRefactorPass.payloadHandlerLambda(handlerExpression: String): String {
    val methodRef = Regex("""^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::([A-Za-z_$][\w$]*)$""")
        .find(handlerExpression.trim())
        ?: return handlerExpression
    return "(payload, context) -> ${methodRef.groupValues[1]}.${methodRef.groupValues[2]}(payload, context)"
}

internal fun StructuralRefactorPass.staticPacketCandidate(file: Path, content: String): PacketClassCandidate? {
    val executableCode = maskJavaCommentsAndLiterals(content)
    val encodeMatch = Regex(
        """public\s+static\s+void\s+encode\s*\(\s*(\w+)\s+(\w+)\s*,\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+(\w+)\s*\)"""
    ).find(executableCode) ?: return null
    val decodeMatch = Regex(
        """public\s+static\s+(\w+)\s+decode\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+\w+\s*\)"""
    ).find(executableCode) ?: return null
    val className = encodeMatch.groupValues[1]
    if (className != decodeMatch.groupValues[1]) return null
    if (file.fileName.toString() != "$className.java") return null

    val pkg = Regex("""package\s+([\w.]+)\s*;""").find(executableCode)?.groupValues?.get(1) ?: ""
    val isPlayToClient = packetReceptionDirection(executableCode)
    val nestedHandler = Regex(
        """static\s+class\s+(\w+)[\s\S]*?static\s+void\s+handle\s*\("""
    ).find(executableCode)
    val handlerRef = if (nestedHandler != null) {
        "$className.${nestedHandler.groupValues[1]}::handle"
    } else {
        "$className::handle"
    }
    return PacketClassCandidate(
        file = file,
        className = className,
        packageName = pkg,
        encodeParamName = encodeMatch.groupValues[2],
        encodeBufferName = encodeMatch.groupValues[4],
        handlerRef = handlerRef,
        isPlayToClient = isPlayToClient,
        bufferType = if (encodeMatch.groupValues[3] == "RegistryFriendlyByteBuf" ||
            decodeMatch.groupValues[2] == "RegistryFriendlyByteBuf") {
            "RegistryFriendlyByteBuf"
        } else {
            "FriendlyByteBuf"
        },
        streamCodecExpression = "StreamCodec.of($className::encode, $className::decode)"
    )
}

internal fun StructuralRefactorPass.constructorPacketInfoFromRegisteredType(
    packetType: ResolvedRegisteredPacketType,
    packetSource: String,
    networkPackageName: String,
    namespaceExpression: String,
    registrationMethod: String,
    handlerExpression: String?
): PacketClassInfo? {
    val executablePacketSource = maskJavaCommentsAndLiterals(packetSource)
    val body = javaTypeBody(executablePacketSource, packetType.simpleName) ?: return null
    val typeStart = findTypeDeclarationStart(executablePacketSource, packetType.simpleName)
    val typeOpenBrace = typeStart?.let { executablePacketSource.indexOf('{', it) } ?: -1
    val typeHeader = if (typeStart != null && typeOpenBrace > typeStart) {
        executablePacketSource.substring(typeStart, typeOpenBrace)
    } else {
        ""
    }
    val explicitlyExtendsOwner = Regex("""\bextends\s+${Regex.escape(packetType.ownerSimpleName)}\b""")
        .containsMatchIn(typeHeader)
    val ownerBody = if (packetType.ownerSimpleName != packetType.simpleName && explicitlyExtendsOwner) {
        javaTypeBody(executablePacketSource, packetType.ownerSimpleName)
    } else {
        null
    }
    val constructorBufferType = Regex(
        """\b(?:public|protected|private)?\s*${Regex.escape(packetType.simpleName)}\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+[A-Za-z_$][\w$]*\s*\)"""
    ).find(body)?.groupValues?.get(1) ?: return null
    val writerPattern = Regex(
        """\b(?:public|protected|private)?\s*void\s+([A-Za-z_$][\w$]*)\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+[A-Za-z_$][\w$]*\s*\)"""
    )
    val writer = writerPattern.findAll(body)
        .firstOrNull { it.groupValues[1] in setOf("write", "toBytes", "encode") }
        ?: ownerBody?.let { inheritedBody ->
            writerPattern.findAll(inheritedBody)
                .firstOrNull { it.groupValues[1] in setOf("write", "toBytes", "encode") }
        }
        ?: return null
    val handlerPattern = Regex(
        """\b(?:public|protected|private)?\s*(?:boolean|void)\s+([A-Za-z_$][\w$]*)\s*\(\s*IPayloadContext\s+[A-Za-z_$][\w$]*\s*\)"""
    )
    val handlerMethod = handlerPattern.find(body)?.groupValues?.get(1)
        ?: ownerBody?.let { handlerPattern.find(it)?.groupValues?.get(1) }

    val bufferType = if (constructorBufferType == "RegistryFriendlyByteBuf" ||
        writer.groupValues[2] == "RegistryFriendlyByteBuf") {
        "RegistryFriendlyByteBuf"
    } else {
        "FriendlyByteBuf"
    }
    val handlerRef = when {
        handlerMethod != null -> "(payload, context) -> payload.$handlerMethod(context)"
        handlerExpression != null -> payloadHandlerLambda(handlerExpression)
        else -> return null
    }
    return PacketClassInfo(
        file = packetType.file,
        className = packetType.simpleName,
        packageName = networkPackageName,
        encodeParamName = packetType.simpleName.replaceFirstChar { it.lowercase() },
        encodeBufferName = "buf",
        handlerRef = handlerRef,
        isPlayToClient = registrationMethod == "playToClient",
        bufferType = bufferType,
        streamCodecExpression = "StreamCodec.of((buf, packet) -> packet.${writer.groupValues[1]}(buf), ${packetType.simpleName}::new)",
        namespaceExpression = namespaceExpression,
        referenceName = packetType.registrationReferenceName,
        registrationMethod = registrationMethod
    )
}

internal fun StructuralRefactorPass.packetReceptionDirection(executableCode: String): Boolean? {
    val hasClientReceptionGuard = Regex(
        """\.getDirection\(\)\.getReceptionSide\(\)\.isClient\(\)"""
    ).containsMatchIn(executableCode)
    val hasServerReceptionGuard = Regex(
        """\.getDirection\(\)\.getReceptionSide\(\)\.isServer\(\)"""
    ).containsMatchIn(executableCode)
    return when {
        hasClientReceptionGuard && !hasServerReceptionGuard -> true
        hasServerReceptionGuard && !hasClientReceptionGuard -> false
        else -> null
    }
}

internal fun StructuralRefactorPass.simpleChannelNamespaceExpression(source: String, channelName: String, srcDir: Path): String? {
    val args = simpleChannelCreationArgs(source, channelName) ?: return null
    val idExpression = args.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return resourceLocationNamespaceExpression(idExpression, source, srcDir)
}

internal fun StructuralRefactorPass.simpleChannelCreationArgs(source: String, channelName: String): List<String>? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val escaped = Regex.escape(channelName)
    val patterns = listOf(
        Regex("""\b(?:SimpleChannel|EventNetworkChannel)\s+$escaped\s*=\s*(?:[A-Za-z_$][\w$]*\.)*NetworkRegistry\.newSimpleChannel\s*\("""),
        Regex("""\b$escaped\s*=\s*(?:[A-Za-z_$][\w$]*\.)*NetworkRegistry\.newSimpleChannel\s*\(""")
    )
    for (pattern in patterns) {
        val match = pattern.find(executableCode) ?: continue
        val openParen = executableCode.indexOf('(', match.range.first)
        val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
        if (openParen >= 0 && closeParen > openParen) {
            return splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        }
    }
    val builderPatterns = listOf(
        Regex("""\b(?:SimpleChannel|EventNetworkChannel|var)\s+$escaped\s*=\s*NetworkRegistry\.ChannelBuilder\.named\s*\("""),
        Regex("""\b$escaped\s*=\s*NetworkRegistry\.ChannelBuilder\.named\s*\(""")
    )
    for (pattern in builderPatterns) {
        val match = pattern.find(executableCode) ?: continue
        val openParen = executableCode.indexOf('(', match.range.first)
        val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
        if (openParen >= 0 && closeParen > openParen) {
            return listOf(source.substring(openParen + 1, closeParen).trim())
        }
    }
    return null
}

internal fun StructuralRefactorPass.resolveRegisteredPacketType(
    srcDir: Path,
    registrationSource: String,
    rawReference: String
): ResolvedRegisteredPacketType? {
    val classReference = rawReference.trim().removeSuffix(".class").trim()
    if (classReference.isBlank()) return null
    val registrationPackage = packageNameOf(registrationSource)
    val imports = javaNonStaticImports(registrationSource)
    val wildcardImports = javaNonStaticWildcardImports(registrationSource)

    fun packetFromOwner(ownerFqn: String, nestedParts: List<String>, sourceReferenceName: String): ResolvedRegisteredPacketType? {
        val ownerSimpleName = ownerFqn.substringAfterLast('.')
        val ownerPackage = ownerFqn.substringBeforeLast('.', "")
        val directPath = srcDir.resolve(ownerFqn.replace('.', '/') + ".java")
        val ownerFile = when {
            Files.exists(directPath) -> directPath
            else -> walkJavaSourceRoots(srcDir).use { files ->
                files
                    .filter { it.extension == "java" && it.fileName.toString() == "$ownerSimpleName.java" }
                    .filter { candidate ->
                        val candidatePackage = packageNameOf(candidate.readText())
                        ownerPackage.isBlank() || candidatePackage == ownerPackage
                    }
                    .findFirst()
                    .orElse(null)
            }
        } ?: return null
        val ownerSource = ownerFile.readText()
        val simpleName = nestedParts.lastOrNull() ?: ownerSimpleName
        if (findTypeDeclarationStart(maskJavaCommentsAndLiterals(ownerSource), simpleName) == null) return null
        val packetPackage = packageNameOf(ownerSource)
        val registrationReferenceName = if (packetPackage == registrationPackage) {
            sourceReferenceName
        } else {
            buildString {
                append(packetPackage)
                append('.')
                append(ownerSimpleName)
                if (nestedParts.isNotEmpty()) {
                    append('.')
                    append(nestedParts.joinToString("."))
                }
            }
        }
        return ResolvedRegisteredPacketType(
            file = ownerFile,
            packageName = packetPackage,
            ownerSimpleName = ownerSimpleName,
            simpleName = simpleName,
            sourceReferenceName = sourceReferenceName,
            registrationReferenceName = registrationReferenceName
        )
    }

    val parts = classReference.split('.').filter { it.isNotBlank() }
    if (parts.isEmpty()) return null
    val first = parts.first()
    imports[first]?.let { importedOwner ->
        return packetFromOwner(importedOwner, parts.drop(1), classReference)
    }
    wildcardImports
        .mapNotNull { importedPackage -> packetFromOwner("$importedPackage.$first", parts.drop(1), classReference) }
        .distinctBy { it.file to it.simpleName }
        .singleOrNull()
        ?.let { return it }

    if (first.firstOrNull()?.isLowerCase() == true && parts.size >= 2) {
        for (ownerPartCount in parts.size downTo 1) {
            val ownerFqn = parts.take(ownerPartCount).joinToString(".")
            val directPath = srcDir.resolve(ownerFqn.replace('.', '/') + ".java")
            if (Files.exists(directPath)) {
                return packetFromOwner(ownerFqn, parts.drop(ownerPartCount), classReference)
            }
        }
    }

    val ownerFqn = if (registrationPackage.isBlank()) first else "$registrationPackage.$first"
    return packetFromOwner(ownerFqn, parts.drop(1), classReference)
}

internal fun StructuralRefactorPass.simpleChannelRegistrationMethodFromText(text: String): String? =
    when {
        text.contains("PLAY_TO_CLIENT") -> "playToClient"
        text.contains("PLAY_TO_SERVER") -> "playToServer"
        text.contains(".encoder(") &&
            text.contains(".decoder(") &&
            (text.contains(".consumerMainThread(") || text.contains(".consumerNetworkThread(")) -> "playBidirectional"
        else -> null
    }

internal fun StructuralRefactorPass.messageBuilderHandlerExpression(statement: String): String? =
    Regex("""\.consumer(?:NetworkThread|MainThread)?\s*\(\s*([^)]+?)\s*\)""")
        .find(statement)
        ?.groupValues
        ?.get(1)
        ?.trim()

internal fun StructuralRefactorPass.registeredSimpleChannelPacketSimpleNames(source: String): Set<String> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val names = linkedSetOf<String>()
    Regex("""\.registerMessage\s*\(""")
        .findAll(executableCode)
        .forEach { match ->
            val openParen = executableCode.indexOf('(', match.range.first)
            val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
            if (openParen < 0 || closeParen <= openParen) return@forEach
            val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
            args.getOrNull(1)
                ?.trim()
                ?.takeIf { it.endsWith(".class") }
                ?.removeSuffix(".class")
                ?.substringAfterLast('.')
                ?.takeIf { it.isNotBlank() }
                ?.let(names::add)
        }
    Regex("""\.messageBuilder\s*\(""")
        .findAll(executableCode)
        .forEach { match ->
            val openParen = executableCode.indexOf('(', match.range.first)
            val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
            if (openParen < 0 || closeParen <= openParen) return@forEach
            val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
            args.getOrNull(0)
                ?.trim()
                ?.takeIf { it.endsWith(".class") }
                ?.removeSuffix(".class")
                ?.substringAfterLast('.')
                ?.takeIf { it.isNotBlank() }
                ?.let(names::add)
    }
    return names
}

internal fun StructuralRefactorPass.registeredPacketNamesHavePayloadEvidence(
    srcDir: Path,
    modNetworkTexts: List<String>,
    packetNames: Set<String>
): Boolean =
    packetNames.all { packetName ->
        modNetworkTexts.any { it.contains("$packetName.TYPE") } ||
            walkJavaSourceRoots(srcDir).use { packetFiles ->
                packetFiles
                    .filter { it.extension == "java" }
                    .anyMatch { packetFile ->
                        val packetText = packetFile.readText()
                        val declarationHasPayload = Regex(
                            """\b(?:class|record)\s+${Regex.escape(packetName)}\b[\s\S]{0,300}\bimplements\s+CustomPacketPayload\b"""
                        ).containsMatchIn(packetText)
                        declarationHasPayload &&
                            packetText.contains("CustomPacketPayload.Type<$packetName>")
                    }
            }
    }

internal fun StructuralRefactorPass.resourceLocationNamespaceExpression(expression: String, ownerSource: String, srcDir: Path): String? {
    val trimmed = expression.trim()
    resourceLocationCallNamespace(trimmed, "new ResourceLocation")?.let {
        return resourceLocationNamespaceExpression(it, ownerSource, srcDir)
    }
    resourceLocationCallNamespace(trimmed, "ResourceLocation.fromNamespaceAndPath")?.let {
        return resourceLocationNamespaceExpression(it, ownerSource, srcDir)
    }
    if (Regex("""[A-Za-z_$][\w$]*""").matches(trimmed)) {
        val declaredLiteral = Regex(
            """\b(?:public|protected|private)?\s*static\s+final\s+String\s+${Regex.escape(trimmed)}\s*=\s*("[^"]+")\s*;"""
        ).find(ownerSource)?.groupValues?.get(1)
        if (declaredLiteral != null) return declaredLiteral
        val aliasInitializer = Regex(
            """\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:ResourceLocation|var)\s+${Regex.escape(trimmed)}\s*=\s*([^;]+)\s*;"""
        ).find(ownerSource)?.groupValues?.get(1)?.trim()
        if (aliasInitializer != null && aliasInitializer != trimmed) {
            return resourceLocationNamespaceExpression(aliasInitializer, ownerSource, srcDir)
        }
        return null
    }
    resourceLocationFactoryNamespaceExpression(trimmed, ownerSource, srcDir)?.let { return it }
    return usableSimpleChannelNamespaceExpression(trimmed, ownerSource)
}

internal fun StructuralRefactorPass.resourceLocationFactoryNamespaceExpression(expression: String, ownerSource: String, srcDir: Path): String? {
    val call = Regex(
        """^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.([A-Za-z_$][\w$]*)\s*\(([\s\S]*)\)$"""
    ).matchEntire(expression) ?: return null
    val ownerName = call.groupValues[1]
    val methodName = call.groupValues[2]
    val callArgs = splitTopLevelJavaArgs(call.groupValues[3])
    val targetSource = resolveJavaOwnerSource(ownerName, ownerSource, srcDir) ?: return null
    val namespaceArgument = resourceLocationFactoryMethodNamespaceArgument(targetSource, methodName, callArgs.size)
        ?: return null
    return resourceLocationNamespaceExpression(namespaceArgument, targetSource, srcDir)
}

internal fun StructuralRefactorPass.resolveJavaOwnerSource(ownerName: String, contextSource: String, srcDir: Path): String? {
    val simpleName = ownerName.substringAfterLast('.')
    if (classNameOfJavaSource(contextSource) == simpleName) return contextSource

    val candidates = linkedSetOf<String>()
    Regex("""(?m)^[ \t]*import\s+([\w.]+\.${Regex.escape(simpleName)})\s*;""")
        .find(contextSource)
        ?.groupValues
        ?.get(1)
        ?.let(candidates::add)
    if (ownerName.contains('.')) candidates.add(ownerName)
    packageNameOf(contextSource)
        .takeIf { it.isNotBlank() }
        ?.let { candidates.add("$it.$simpleName") }

    return candidates
        .map { srcDir.resolve(it.replace('.', '/') + ".java") }
        .firstOrNull { it.exists() }
        ?.readText()
}

internal fun StructuralRefactorPass.resourceLocationFactoryMethodNamespaceArgument(
    source: String,
    methodName: String,
    callArgCount: Int
): String? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val header = Regex(
        """\b(?:public|protected|private)?\s*static\s+(?:ResourceLocation|net\.minecraft\.resources\.ResourceLocation)\s+${Regex.escape(methodName)}\s*\(([^)]*)\)\s*\{"""
    ).find(executableCode) ?: return null
    val openBrace = executableCode.indexOf('{', header.range.last - 1)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
    if (openBrace < 0 || closeBrace <= openBrace) return null

    val parameters = Regex("""\b([A-Za-z_$][\w$]*)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(header.groupValues[1])
        .map { it.groupValues[2] }
        .toList()
    if (parameters.size != callArgCount) return null

    val bodyMasked = executableCode.substring(openBrace + 1, closeBrace)
    val returns = Regex("""\breturn\s+([^;]+);""").findAll(bodyMasked).toList()
    if (returns.size != 1) return null
    val returnStatement = source.substring(openBrace + 1, closeBrace).substring(returns.single().range)
    val returnExpression = Regex("""\breturn\s+([\s\S]+);""")
        .find(returnStatement)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: return null
    val resourceLocationArgs = resourceLocationCallArgs(returnExpression, "ResourceLocation.fromNamespaceAndPath")
        ?: resourceLocationCallArgs(returnExpression, "new ResourceLocation")
        ?: return null
    if (resourceLocationArgs.size < 2) return null

    val pathArgument = resourceLocationArgs[1]
    val usesMethodParameter = parameters.any { parameter ->
        Regex("""(?<![\w$])${Regex.escape(parameter)}(?![\w$])""").containsMatchIn(pathArgument)
    }
    if (!usesMethodParameter) return null
    return resourceLocationArgs[0].trim().takeIf { it.isNotBlank() }
}

internal fun StructuralRefactorPass.resourceLocationCallArgs(expression: String, callName: String): List<String>? {
    val callIndex = expression.indexOf(callName)
    if (callIndex < 0) return null
    val openParen = expression.indexOf('(', callIndex)
    val closeParen = if (openParen >= 0) findMatchingParen(expression, openParen) else -1
    if (openParen < 0 || closeParen <= openParen) return null
    return splitTopLevelJavaArgs(expression.substring(openParen + 1, closeParen))
}

internal fun StructuralRefactorPass.resourceLocationCallNamespace(expression: String, callName: String): String? {
    val args = resourceLocationCallArgs(expression, callName) ?: return null
    return args.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
}

internal fun StructuralRefactorPass.usableSimpleChannelNamespaceExpression(expression: String, ownerSource: String): String? {
    val trimmed = expression.trim()
    if (Regex("\"[^\"]+\"").matches(trimmed)) return trimmed
    if (Regex("""(?:[a-z_][\w]*\.)+[A-Z_$][\w$]*\.(?:MOD_ID|MODID|ID)""").matches(trimmed)) {
        return trimmed
    }
    val ownerConstant = Regex("""([A-Z_$][\w$]*)\.(MOD_ID|MODID|ID)""").matchEntire(trimmed)
    if (ownerConstant != null) {
        val owner = ownerConstant.groupValues[1]
        val field = ownerConstant.groupValues[2]
        Regex("""import\s+([\w.]+\.${Regex.escape(owner)})\s*;""")
            .find(ownerSource)
            ?.groupValues
            ?.get(1)
            ?.let { return "$it.$field" }
    }
    return null
}

internal fun StructuralRefactorPass.findTypeOpenBrace(content: String, className: String): Int {
    val typeStart = findTypeDeclarationStart(content, className) ?: return -1
    return content.indexOf('{', typeStart)
}

internal fun StructuralRefactorPass.packetTypeAlreadyHasCustomPayloadMembers(source: String, className: String): Boolean {
    val typeStart = findTypeDeclarationStart(source, className) ?: return false
    val openBrace = source.indexOf('{', typeStart)
    if (openBrace < 0) return false
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace <= openBrace) return false
    val header = source.substring(typeStart, openBrace)
    val body = source.substring(openBrace + 1, closeBrace)
    return header.contains("implements CustomPacketPayload") &&
        (body.contains("CustomPacketPayload.Type<$className> TYPE") ||
            body.contains("Type<$className> TYPE")) &&
        body.contains("STREAM_CODEC") &&
        Regex("""\btype\s*\(\s*\)""").containsMatchIn(body)
}

internal fun StructuralRefactorPass.migrateLegacyPayloadHandlerContext(
    content: String,
    info: PacketClassInfo,
    changes: MutableList<Change>
): String {
    val id = """[A-Za-z_$][\w$]*"""
    val contextVars = mutableSetOf<String>()
    val handlerContextPattern = Regex(
        """public\s+static\s+void\s+handle\s*\(\s*${Regex.escape(info.className)}\s+$id\s*,\s*(?:IPayloadContext|Supplier\s*<\s*NetworkEvent\.Context\s*>)\s+($id)\s*\)\s*\{"""
    )

    var result = content
    var firstChangeLine: Int? = null
    var cursor = 0
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val methodMatch = handlerContextPattern.find(executableCode, cursor) ?: break
        val supplierName = methodMatch.groupValues[1]
        val openBrace = result.indexOf('{', methodMatch.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = methodMatch.range.last + 1
            continue
        }
        val contextDeclaration = Regex(
            """(?m)^[ \t]*NetworkEvent\.Context\s+($id)\s*=\s*${Regex.escape(supplierName)}\.get\(\);\s*\r?\n"""
        )
            .find(executableCode, openBrace + 1)
            ?.takeIf { it.range.last < closeBrace }
        if (contextDeclaration == null) {
            cursor = closeBrace + 1
            continue
        }

        val contextVar = contextDeclaration.groupValues[1]
        contextVars.add(contextVar)
        firstChangeLine = firstChangeLine ?: lineNumberAt(result, methodMatch.range.first)

        val originalHeader = result.substring(methodMatch.range)
        val migratedHeader = Regex(
            """(?:IPayloadContext|Supplier\s*<\s*NetworkEvent\.Context\s*>)\s+${Regex.escape(supplierName)}(?=\s*\))"""
        ).replace(originalHeader, "IPayloadContext $contextVar")
        result = result.substring(0, contextDeclaration.range.first) +
            result.substring(contextDeclaration.range.last + 1)
        result = result.substring(0, methodMatch.range.first) +
            migratedHeader +
            result.substring(methodMatch.range.last + 1)
        cursor = methodMatch.range.first + migratedHeader.length
    }
    if (result == content) return content

    contextVars.forEach { contextVar ->
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^([ \t]*)ServerPlayer\s+(\w+)\s*=\s*${Regex.escape(contextVar)}\.(?:getSender|player)\(\);\s*\r?\n[ \t]*if\s*\(\s*\2\s*!=\s*null\s*\)\s*\{"""),
        ) { match -> "${match.groupValues[1]}if ($contextVar.player() instanceof ServerPlayer ${match.groupValues[2]}) {" }
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(contextVar)}\.getSender\(\)""")
        ) { "$contextVar.player()" }
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^[ \t]*${Regex.escape(contextVar)}\.setPacketHandled\(true\);\s*\r?\n""")
        ) { "" }
    }

    changes.add(Change(
        file = info.file,
        line = firstChangeLine ?: 1,
        description = "Migrate ${info.className} packet handler from NetworkEvent.Context to IPayloadContext",
        before = "NetworkEvent.Context context = contextSupplier.get(); ... context.setPacketHandled(true);",
        after = "handle(${info.className} packet, IPayloadContext context) { context.enqueueWork(...); }",
        confidence = Confidence.HIGH,
        ruleId = "struct-packet-payload-context"
    ))
    return result
}

internal fun StructuralRefactorPass.cleanupLegacyPayloadContextReferences(
    content: String,
    info: PacketClassInfo,
    changes: MutableList<Change>
): String {
    var result = content
    val original = result
    val firstMatch = Regex("""\b\w+\.get\(\)\.(?:getDirection|getSender|setPacketHandled)\b""")
        .find(maskJavaCommentsAndLiterals(content))

    result = replaceExecutableRegex(
        result,
        Regex("""(?m)^[ \t]*if\s*\(\s*!\s*\w+\.get\(\)\.getDirection\(\)\.getReceptionSide\(\)\.is(?:Server|Client)\(\)\s*\)\s*return;\s*\r?\n""")
    ) { "" }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(\w+)\.get\(\)\.getSender\(\)""")
    ) { match -> "${match.groupValues[1]}.player()" }
    result = replaceExecutableRegex(
        result,
        Regex("""(?m)^[ \t]*\w+\.get\(\)\.setPacketHandled\(true\);\s*\r?\n""")
    ) { "" }
    result = removeImportIfPresent(result, "java.util.function.Supplier")

    if (result != original) {
        changes.add(Change(
            file = info.file,
            line = firstMatch?.let { lineNumberAt(content, it.range.first) } ?: 1,
            description = "Remove legacy NetworkEvent.Context supplier calls after IPayloadContext migration",
            before = "ctx.get().getDirection()/getSender()/setPacketHandled(true)",
            after = "directional PayloadRegistrar registration + IPayloadContext.player()",
            confidence = Confidence.HIGH,
            ruleId = "struct-packet-payload-context-cleanup"
        ))
    }
    return result
}

internal fun StructuralRefactorPass.migrateConfigResourceLocationListValidators(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()

    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .forEach { javaFile ->
            val original = javaFile.readText()
            val modified = migrateConfigResourceLocationListValidatorSource(original)
            if (modified == original) return@forEach
            changes.add(Change(
                file = javaFile,
                line = 1,
                description = "Allow optional dependency item ids in config lists while filtering absent registry entries at runtime",
                before = "config validator requires BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id))",
                after = "validator checks ResourceLocation syntax; runtime item stream filters loaded registry entries",
                confidence = Confidence.HIGH,
                ruleId = "struct-config-resource-location-list-validator"
            ))
            if (!dryRun) {
                javaFile.writeText(modified)
            }
        }

    return changes
}

internal fun StructuralRefactorPass.migrateConfigResourceLocationListValidatorSource(source: String): String {
    if (!source.contains("ModConfigSpec") ||
        !source.contains("defineList") ||
        !source.contains("BuiltInRegistries.ITEM") ||
        !source.contains("ResourceLocation.parse")
    ) {
        return source
    }

    val javaIdentifier = """[A-Za-z_$][\w$]*"""
    var result = source
    result = replaceExecutableRegex(
        result,
        Regex(
            """return\s+($javaIdentifier)\s+instanceof\s+((?:final\s+)?)String\s+($javaIdentifier)\s*&&\s*BuiltInRegistries\.ITEM\.containsKey\s*\(\s*ResourceLocation\.parse\s*\(\s*\3\s*\)\s*\)\s*;"""
        )
    ) { match ->
        val obj = match.groupValues[1]
        val modifier = match.groupValues[2]
        val name = match.groupValues[3]
        "return $obj instanceof ${modifier}String $name && ResourceLocation.tryParse($name) != null;"
    }

    result = replaceExecutableRegex(
        result,
        Regex(
            """\.stream\(\)\s*\.map\s*\(\s*($javaIdentifier)\s*->\s*BuiltInRegistries\.ITEM\.get\s*\(\s*ResourceLocation\.parse\s*\(\s*\1\s*\)\s*\)\s*\)"""
        )
    ) { match ->
        val name = match.groupValues[1]
        ".stream().filter($name -> BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse($name))).map($name -> BuiltInRegistries.ITEM.get(ResourceLocation.parse($name)))"
    }

    return result
}

internal fun StructuralRefactorPass.migrateBlockAttachedStructureTemplateProcessors(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists() || !projectHasBlockAttachedStructureTemplateEntities(projectDir)) return emptyList()
    val changes = mutableListOf<Change>()
    var usesGeneratedProcessor = false

    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .filter { !srcDir.relativize(it).toString().replace('\\', '/').startsWith("com/modporter/generated/") }
        .forEach { javaFile ->
            val original = javaFile.readText()
            if (original.contains("ModPorterBlockAttachedEntityProcessor.PROCESSOR_LIST")) {
                usesGeneratedProcessor = true
            }
            val modified = migrateBlockAttachedStructureTemplateProcessorSource(original)
            if (modified == original) return@forEach
            usesGeneratedProcessor = true
            changes.add(Change(
                file = javaFile,
                line = 1,
                description = "Route structure template entities with TileX/Y/Z through a placement-time position processor",
                before = "SinglePoolElement.single(template, minecraft:empty processor list)",
                after = "SinglePoolElement.single(template, ModPorterBlockAttachedEntityProcessor.PROCESSOR_LIST)",
                confidence = Confidence.HIGH,
                ruleId = "struct-structure-block-attached-entity-processor"
            ))
            if (!dryRun) {
                javaFile.writeText(modified)
            }
        }

    if (usesGeneratedProcessor) {
        val generatedFile = srcDir.resolve("com/modporter/generated/structure/ModPorterBlockAttachedEntityProcessor.java")
        val generatedSource = generatedBlockAttachedEntityProcessorSource()
        if (!generatedFile.exists() || generatedFile.readText() != generatedSource) {
            changes.add(Change(
                file = generatedFile,
                line = 1,
                description = "Generate structure entity processor that writes transformed TileX/Y/Z before entity load",
                before = "(missing block-attached entity structure processor)",
                after = "ModPorterBlockAttachedEntityProcessor",
                confidence = Confidence.HIGH,
                ruleId = "struct-structure-block-attached-entity-processor-helper"
            ))
            if (!dryRun) {
                generatedFile.parent.createDirectories()
                generatedFile.writeText(generatedSource)
            }
        }
    }

    return changes
}

internal fun StructuralRefactorPass.migrateBlockAttachedStructureTemplateProcessorSource(source: String): String {
    if ((!source.contains("SinglePoolElement.single(") &&
            !source.contains("StructurePoolElement.single(") &&
            !source.contains("SinglePoolElement.legacy(") &&
            !source.contains("StructurePoolElement.legacy(")) ||
        source.contains("ModPorterBlockAttachedEntityProcessor.PROCESSOR_LIST")
    ) {
        return source
    }

    val emptyProcessorVariables = Regex(
        """(?s)\b(?:Holder\s*<\s*StructureProcessorList\s*>|net\.minecraft\.core\.Holder\s*<\s*net\.minecraft\.world\.level\.levelgen\.structure\.templatesystem\.StructureProcessorList\s*>|var)\s+([A-Za-z_$][\w$]*)\s*=\s*[^;]*Registries\.PROCESSOR_LIST[^;]*(?:"minecraft"|fromNamespaceAndPath\s*\(\s*"minecraft"\s*,\s*"empty"\s*\)|parse\s*\(\s*"minecraft:empty"\s*\))[^;]*;"""
    ).findAll(source)
        .map { it.groupValues[1] }
        .toSet()

    var result = source
    var changed = false
    for (methodName in listOf("single", "legacy")) {
        result = rewriteExecutableJavaCall(result, methodName) { receiver, args ->
            val trimmedReceiver = receiver.trim()
            if (trimmedReceiver != "SinglePoolElement" && trimmedReceiver != "StructurePoolElement") {
                return@rewriteExecutableJavaCall null
            }
            val templateArg = args.firstOrNull()?.trim() ?: return@rewriteExecutableJavaCall null
            if (args.size == 1) {
                changed = true
                "$trimmedReceiver.$methodName($templateArg, ModPorterBlockAttachedEntityProcessor.PROCESSOR_LIST)"
            } else if (args.size == 2 && args[1].trim() in emptyProcessorVariables) {
                changed = true
                "$trimmedReceiver.$methodName($templateArg, ModPorterBlockAttachedEntityProcessor.PROCESSOR_LIST)"
            } else {
                null
            }
        }
    }
    if (!changed) return source
    return addImportIfMissing(result, "com.modporter.generated.structure.ModPorterBlockAttachedEntityProcessor")
}

internal fun StructuralRefactorPass.projectHasBlockAttachedStructureTemplateEntities(projectDir: Path): Boolean {
    val dataDir = projectDir.resolve("src/main/resources/data")
    if (!dataDir.exists()) return false
    val structureNbtFiles = Files.walk(dataDir)
        .filter { Files.isRegularFile(it) && it.extension == "nbt" }
        .filter { file ->
            val relative = dataDir.relativize(file).toString().replace('\\', '/')
            relative.contains("/structure/") || relative.contains("/structures/")
        }
        .toList()

    for (file in structureNbtFiles) {
        val bytes = try {
            maybeDecompressGzip(file.readBytes())
        } catch (_: Exception) {
            continue
        }
        if (bytes.containsAscii("entities") &&
            bytes.containsAscii("TileX") &&
            bytes.containsAscii("TileY") &&
            bytes.containsAscii("TileZ")
        ) {
            return true
        }
    }
    return false
}

internal fun StructuralRefactorPass.maybeDecompressGzip(bytes: ByteArray): ByteArray {
    if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
    return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

internal fun ByteArray.containsAscii(value: String): Boolean {
    val needle = value.toByteArray(Charsets.US_ASCII)
    if (needle.isEmpty() || needle.size > size) return false
    var offset = 0
    while (offset <= size - needle.size) {
        var index = 0
        while (index < needle.size && this[offset + index] == needle[index]) {
            index++
        }
        if (index == needle.size) return true
        offset++
    }
    return false
}

internal fun StructuralRefactorPass.generatedBlockAttachedEntityProcessorSource(): String = """
        package com.modporter.generated.structure;

        import java.util.List;
        import net.minecraft.core.BlockPos;
        import net.minecraft.core.Holder;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.world.level.LevelReader;
        import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
        import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
        import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
        import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
        import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

        public final class ModPorterBlockAttachedEntityProcessor extends StructureProcessor {
            public static final ModPorterBlockAttachedEntityProcessor INSTANCE = new ModPorterBlockAttachedEntityProcessor();
            public static final Holder<StructureProcessorList> PROCESSOR_LIST =
                    Holder.direct(new StructureProcessorList(List.of(INSTANCE)));

            private ModPorterBlockAttachedEntityProcessor() {
            }

            @Override
            public StructureTemplate.StructureEntityInfo processEntity(
                    LevelReader world,
                    BlockPos seedPos,
                    StructureTemplate.StructureEntityInfo rawEntityInfo,
                    StructureTemplate.StructureEntityInfo entityInfo,
                    StructurePlaceSettings placementSettings,
                    StructureTemplate template
            ) {
                CompoundTag nbt = entityInfo.nbt.copy();
                if (!nbt.contains("TileX") || !nbt.contains("TileY") || !nbt.contains("TileZ")) {
                    return entityInfo;
                }

                BlockPos pos = entityInfo.blockPos;
                nbt.putInt("TileX", pos.getX());
                nbt.putInt("TileY", pos.getY());
                nbt.putInt("TileZ", pos.getZ());
                return new StructureTemplate.StructureEntityInfo(entityInfo.pos, pos, nbt);
            }

            @Override
            protected StructureProcessorType<?> getType() {
                return StructureProcessorType.NOP;
            }
        }
    """.trimIndent()

internal fun StructuralRefactorPass.cleanupRecursiveRecipeDimensionAccessors(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .forEach { javaFile ->
            val original = javaFile.readText()
            if (!original.contains("return getWidth();") && !original.contains("return getHeight();")) {
                return@forEach
            }
            var modified = original
            val removed = mutableListOf<String>()
            for (methodName in listOf("getWidth", "getHeight")) {
                modified = removeSelfRecursiveDuplicateAccessor(modified, methodName) { removed += methodName }
            }
            if (modified != original) {
                changes.add(Change(
                    file = javaFile,
                    line = removed.firstOrNull()
                        ?.let { Regex("""\b${Regex.escape(it)}\s*\(""").find(original) }
                        ?.let { lineNumberAt(original, it.range.first) }
                        ?: 1,
                    description = "Remove recursive duplicate shaped recipe dimension accessors",
                    before = removed.joinToString(", ") { "public int $it() { return $it(); }" },
                    after = "Use the remaining concrete width/height accessor",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-recipe-recursive-dimension-accessor-cleanup"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }
    return changes
}

internal fun StructuralRefactorPass.removeSelfRecursiveDuplicateAccessor(
    source: String,
    methodName: String,
    onRemoved: () -> Unit
): String {
    val methods = javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(source))
        .filter { it.name == methodName }
    if (methods.size < 2) return source

    fun isRecursive(method: JavaMethodRange): Boolean {
        val methodText = source.substring(method.range)
        return Regex("""(?s)\{\s*return\s+${Regex.escape(methodName)}\s*\(\s*\)\s*;\s*\}""")
            .containsMatchIn(methodText)
    }

    if (methods.none { !isRecursive(it) }) return source
    var result = source
    for (method in methods.filter(::isRecursive).sortedByDescending { it.range.first }) {
        var end = method.range.last + 1
        if (end < result.length && result[end] == '\r') end++
        if (end < result.length && result[end] == '\n') end++
        result = result.removeRange(method.range.first, end)
        onRemoved()
    }
    return result
}

internal fun StructuralRefactorPass.migrateInstancePayloadContextHandlers(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .forEach { javaFile ->
            val original = javaFile.readText()
            if (!original.contains("IPayloadContext") ||
                !original.contains("NetworkEvent.Context") ||
                !original.contains(".get()")) {
                return@forEach
            }
            val migrated = migrateInstancePayloadContextHandlersInSource(original)
            if (migrated != original) {
                changes.add(Change(
                    file = javaFile,
                    line = Regex("""NetworkEvent\.Context\s+\w+\s*=""")
                        .find(original)
                        ?.let { lineNumberAt(original, it.range.first) }
                        ?: 1,
                    description = "Migrate instance packet handler body from NetworkEvent.Context to IPayloadContext",
                    before = "NetworkEvent.Context ctx = context.get(); ctx.getSender()/getDirection()/setPacketHandled(true)",
                    after = "IPayloadContext ctx; ctx.player()/flow()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-instance-packet-payload-context"
                ))
                if (!dryRun) javaFile.writeText(migrated)
            }
        }
    return changes
}

internal fun StructuralRefactorPass.migrateInstancePayloadContextHandlersInSource(source: String): String {
    var result = source
    var cursor = 0
    val id = """[A-Za-z_$][\w$]*"""
    while (true) {
        val executable = maskJavaCommentsAndLiterals(result)
        val method = javaMethodRangesIncludingDefault(executable).firstOrNull { candidate ->
            candidate.range.first >= cursor &&
                Regex("""\bIPayloadContext\s+($id)\b""").containsMatchIn(candidate.header)
        } ?: break
        val methodText = result.substring(method.range)
        val parameterMatch = Regex("""\bIPayloadContext\s+($id)\b""").find(method.header)
        if (parameterMatch == null) {
            cursor = method.range.last + 1
            continue
        }
        val parameterName = parameterMatch.groupValues[1]
        val executableMethodText = maskJavaCommentsAndLiterals(methodText)
        val declaration = Regex(
            """(?m)^([ \t]*)NetworkEvent\.Context\s+($id)\s*=\s*${Regex.escape(parameterName)}\.get\(\);\s*\r?\n"""
        ).find(executableMethodText)
        if (declaration == null) {
            cursor = method.range.last + 1
            continue
        }
        val contextName = declaration.groupValues[2]
        var migratedMethod = methodText.removeRange(declaration.range)
        val parameterInMethod = Regex("""\bIPayloadContext\s+${Regex.escape(parameterName)}\b""").find(migratedMethod)
        if (parameterInMethod != null) {
            migratedMethod = migratedMethod.replaceRange(parameterInMethod.range, "IPayloadContext $contextName")
        }
        migratedMethod = replaceExecutableRegex(
            migratedMethod,
            Regex("""(?m)^([ \t]*)ServerPlayer\s+($id)\s*=\s*${Regex.escape(contextName)}\.getSender\(\);\s*$""")
        ) { match -> "${match.groupValues[1]}ServerPlayer ${match.groupValues[2]} = (ServerPlayer) $contextName.player();" }
        migratedMethod = replaceExecutableRegex(
            migratedMethod,
            Regex("""\b${Regex.escape(contextName)}\.getSender\(\)""")
        ) { "$contextName.player()" }
        migratedMethod = replaceExecutableRegex(
            migratedMethod,
            Regex("""(?m)^([ \t]*)ServerPlayer\s+($id)\s*=\s*\(ServerPlayer\)\s*${Regex.escape(contextName)}\.player\(\);\s*\r?\n[ \t]*if\s*\(\s*\2\s*!=\s*null\s*\)\s*\{""")
        ) { match -> "${match.groupValues[1]}if ($contextName.player() instanceof ServerPlayer ${match.groupValues[2]}) {" }
        migratedMethod = replaceExecutableRegex(
            migratedMethod,
            Regex("""\b${Regex.escape(contextName)}\.getDirection\(\)""")
        ) { "$contextName.flow()" }
        migratedMethod = replaceExecutableRegex(
            migratedMethod,
            Regex("""(?m)^[ \t]*${Regex.escape(contextName)}\.setPacketHandled\(true\);\s*\r?\n""")
        ) { "" }
        result = result.replaceRange(method.range, migratedMethod)
        cursor = method.range.first + migratedMethod.length
    }
    result = removeUnusedSimpleImport(result, "java.util.function.Supplier", "Supplier")
    return result
}

internal fun StructuralRefactorPass.migrateKnownNestedSimpleChannelNetworking(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .forEach { file ->
            val original = file.readText()
            val executableCode = maskJavaCommentsAndLiterals(original)
            if (!executableCode.contains("SimpleChannel") ||
                !executableCode.contains("NetworkRegistry.newSimpleChannel") ||
                !executableCode.contains(".registerMessage(")) {
                return@forEach
            }
            val packageName = packageNameOf(original)
            val ownerClassName = classNameOfJavaSource(original) ?: return@forEach
            val channelNames = nestedSimpleChannelNames(original)
            if (channelNames.isEmpty()) return@forEach
            val modIdExpression = nestedSimpleChannelModIdExpression(original) ?: return@forEach
            val protocolExpression = nestedSimpleChannelProtocolExpression(original) ?: return@forEach
            val packets = nestedSimpleChannelPackets(file, original, packageName, ownerClassName, channelNames)
            if (packets.isEmpty() || packets.any { it.direction == null }) return@forEach

            var migrated = original
            packets.forEach { packet ->
                migrated = migrateNestedSimpleChannelPacketSource(migrated, packet, modIdExpression)
                migrated = migrateLegacyPayloadHandlerContext(migrated, packet.toPacketClassInfo(modIdExpression), changes)
                migrated = cleanupLegacyPayloadContextReferences(migrated, packet.toPacketClassInfo(modIdExpression), changes)
            }
            migrated = insertNestedSimpleChannelPayloadRegistration(
                migrated,
                ownerClassName,
                modIdExpression,
                protocolExpression,
                packets
            )
            migrated = rewriteNestedSimpleChannelSendCalls(migrated, channelNames)
            migrated = removeNestedSimpleChannelLegacySetup(migrated, channelNames, packets)
            if (migrated == original) return@forEach
            if (!dryRun) {
                file.writeText(migrated)
            }
            changes.add(Change(
                file = file,
                line = 1,
                description = "Migrate nested SimpleChannel packet classes to CustomPacketPayload registrations",
                before = "SimpleChannel + static inner packet classes",
                after = "RegisterPayloadHandlersEvent + nested CustomPacketPayload classes + PacketDistributor sends",
                confidence = Confidence.HIGH,
                ruleId = "struct-nested-simplechannel-payloads"
            ))
        }

    return changes
}

internal data class NestedSimpleChannelPacketInfo(
    val file: Path,
    val packageName: String,
    val ownerClassName: String,
    val packetClassName: String,
    val bufferType: String,
    val handlerExpression: String,
    val direction: String?,
    val registrationText: String,
    val idVariable: String?
) {
    fun toPacketClassInfo(namespaceExpression: String): PacketClassInfo =
        PacketClassInfo(
            file = file,
            className = packetClassName,
            packageName = packageName,
            encodeParamName = packetClassName.replaceFirstChar { it.lowercase() },
            encodeBufferName = "buf",
            handlerRef = handlerExpression,
            isPlayToClient = direction == "playToClient",
            bufferType = bufferType,
            streamCodecExpression = "StreamCodec.of($packetClassName::encode, $packetClassName::decode)",
            namespaceExpression = namespaceExpression
        )
}

internal fun StructuralRefactorPass.nestedSimpleChannelNames(source: String): Set<String> =
    buildSet {
        val executableCode = maskJavaCommentsAndLiterals(source)
        Regex("""\bSimpleChannel\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableCode)
            .forEach { add(it.groupValues[1]) }
        Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*NetworkRegistry\.newSimpleChannel\s*\(""")
            .findAll(executableCode)
            .forEach { add(it.groupValues[1]) }
    }

internal fun StructuralRefactorPass.nestedSimpleChannelPackets(
    file: Path,
    source: String,
    packageName: String,
    ownerClassName: String,
    channelNames: Set<String>
): List<NestedSimpleChannelPacketInfo> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val packets = mutableListOf<NestedSimpleChannelPacketInfo>()
    for (channelName in channelNames) {
        val callName = "$channelName.registerMessage"
        var cursor = 0
        while (true) {
            val callIndex = executableCode.indexOf(callName, cursor)
            if (callIndex < 0) break
            val openParen = executableCode.indexOf('(', callIndex + callName.length)
            val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
            if (openParen < 0 || closeParen < 0) {
                cursor = callIndex + callName.length
                continue
            }
            val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen)).map { it.trim() }
            if (args.size < 5) {
                cursor = closeParen + 1
                continue
            }
            val packetClassName = args[1].removeSuffix(".class").substringAfterLast('.').trim()
            if (packetClassName.isBlank() || findTypeDeclarationStart(executableCode, packetClassName) == null) {
                cursor = closeParen + 1
                continue
            }
            val bufferType = nestedPacketBufferType(source, packetClassName)
            if (bufferType == null) {
                cursor = closeParen + 1
                continue
            }
            val direction = nestedPacketDirection(source, packetClassName, args.getOrNull(5))
            packets.add(
                NestedSimpleChannelPacketInfo(
                    file = file,
                    packageName = packageName,
                    ownerClassName = ownerClassName,
                    packetClassName = packetClassName,
                    bufferType = bufferType,
                    handlerExpression = args[4],
                    direction = direction,
                    registrationText = source.substring(callIndex, closeParen + 1),
                    idVariable = Regex("""^([A-Za-z_$][\w$]*)\s*\+\+$""")
                        .matchEntire(args[0])
                        ?.groupValues
                        ?.get(1)
                )
            )
            cursor = closeParen + 1
        }
    }
    return packets.distinctBy { it.packetClassName }
}

internal fun StructuralRefactorPass.nestedPacketBufferType(source: String, packetClassName: String): String? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    Regex(
        """static\s+void\s+encode\s*\(\s*${Regex.escape(packetClassName)}\s+[A-Za-z_$][\w$]*\s*,\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+[A-Za-z_$][\w$]*\s*\)"""
    ).find(executableCode)?.let { return it.groupValues[1] }
    Regex(
        """\bvoid\s+encode\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+[A-Za-z_$][\w$]*\s*\)"""
    ).find(executableCode)?.let { return it.groupValues[1] }
    return null
}

internal fun StructuralRefactorPass.javaTypeBody(source: String, typeName: String): String? {
    val typeStart = findTypeDeclarationStart(source, typeName) ?: return null
    val openBrace = source.indexOf('{', typeStart)
    if (openBrace < 0) return null
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace <= openBrace) return null
    return source.substring(openBrace + 1, closeBrace)
}

internal fun StructuralRefactorPass.nestedPacketDirection(source: String, packetClassName: String, directionArg: String?): String? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    directionArg?.let { explicit ->
        val executableDirection = maskJavaCommentsAndLiterals(explicit)
        return when {
            executableDirection.contains("PLAY_TO_CLIENT") -> "playToClient"
            executableDirection.contains("PLAY_TO_SERVER") -> "playToServer"
            else -> null
        }
    }
    val payloadVariables = Regex("""\b${Regex.escape(packetClassName)}\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+${Regex.escape(packetClassName)}\s*\(""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    val payloadPattern = """(?:new\s+${Regex.escape(packetClassName)}\s*\(|${payloadVariables.joinToString("|") { Regex.escape(it) }.ifBlank { "(?!)" }})"""
    val sendStatements = Regex("""(?s)\b[A-Za-z_$][\w$]*\.send(?:To)?\s*\((.*?)\)\s*;""")
        .findAll(executableCode)
        .map { it.value }
        .filter { Regex(payloadPattern).containsMatchIn(it) }
        .toList()
    val directions = sendStatements.mapNotNull { statement ->
        when {
            statement.contains("NetworkDirection.PLAY_TO_CLIENT") -> "playToClient"
            statement.contains("NetworkDirection.PLAY_TO_SERVER") -> "playToServer"
            statement.contains("PacketDistributor.SERVER") -> "playToServer"
            statement.contains("PacketDistributor.PLAYER") ||
                statement.contains("PacketDistributor.TRACKING_ENTITY") ||
                statement.contains("PacketDistributor.TRACKING_CHUNK") ||
                statement.contains("PacketDistributor.ALL") -> "playToClient"
            else -> null
        }
    }.toSet()
    if (directions.size == 1) return directions.single()

    val body = javaTypeBody(executableCode, packetClassName)
    if (body != null &&
        (body.contains("net.minecraft.client.") ||
            body.contains("Minecraft.getInstance()") ||
            Regex("""\b[A-Za-z_$][\w$]*Client[A-Za-z_$][\w$]*\b""").containsMatchIn(body))) {
        return "playToClient"
    }
    return null
}

internal fun StructuralRefactorPass.nestedSimpleChannelModIdExpression(source: String): String? {
    val args = nestedSimpleChannelArgs(source) ?: return null
    val idExpression = args.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    Regex("""new\s+ResourceLocation\s*\(\s*([^,]+)\s*,""")
        .find(idExpression)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.let { return it }
    Regex("""ResourceLocation\.fromNamespaceAndPath\s*\(\s*([^,]+)\s*,""")
        .find(idExpression)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.let { return it }
    if (Regex(""""[^"]+"""").matches(idExpression)) return idExpression
    if (Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.(?:MOD_ID|MODID|ID)""").matches(idExpression)) {
        return idExpression
    }
    return null
}

internal fun StructuralRefactorPass.nestedSimpleChannelProtocolExpression(source: String): String? {
    val args = nestedSimpleChannelArgs(source) ?: return null
    val supplier = args.getOrNull(1)?.trim().orEmpty()
    return Regex("""^\(\s*\)\s*->\s*(.+)$""")
        .find(supplier)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }
}

internal fun StructuralRefactorPass.nestedSimpleChannelArgs(source: String): List<String>? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val token = "NetworkRegistry.newSimpleChannel"
    val index = executableCode.indexOf(token)
    if (index < 0) return null
    val openParen = executableCode.indexOf('(', index + token.length)
    val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
    if (openParen < 0 || closeParen < 0) return null
    return splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
}

internal fun StructuralRefactorPass.migrateNestedSimpleChannelPacketSource(
    source: String,
    packet: NestedSimpleChannelPacketInfo,
    modIdExpression: String
): String {
    var result = source
    result = addImportIfMissing(result, "net.minecraft.network.codec.StreamCodec")
    result = addImportIfMissing(result, "net.minecraft.network.protocol.common.custom.CustomPacketPayload")
    result = addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
    result = addImportIfMissing(result, "net.neoforged.neoforge.network.handling.IPayloadContext")
    result = addNestedCustomPacketPayloadInterface(result, packet.packetClassName)
    result = insertNestedPacketPayloadMembers(result, packet, modIdExpression)
    val nestedMessageFirstEncode = Regex(
        """encode\s*\(\s*${Regex.escape(packet.packetClassName)}\s+([A-Za-z_$][\w$]*)\s*,\s*${Regex.escape(packet.bufferType)}\s+([A-Za-z_$][\w$]*)\s*\)"""
    )
    if (nestedMessageFirstEncode.containsMatchIn(result)) {
        result = result.replace(
            "StreamCodec.of(${packet.packetClassName}::encode, ${packet.packetClassName}::decode)",
            "StreamCodec.of((buf, message) -> ${packet.packetClassName}.encode(message, buf), ${packet.packetClassName}::decode)"
        )
    }
    result = Regex("""Supplier\s*<\s*NetworkEvent\.Context\s*>\s+([A-Za-z_$][\w$]*)""")
        .replace(result, "IPayloadContext $1")
    result = Regex("""\b([A-Za-z_$][\w$]*)\.get\(\)\.enqueueWork\s*\(""")
        .replace(result, "$1.enqueueWork(")
    return result
}

internal fun StructuralRefactorPass.addNestedCustomPacketPayloadInterface(source: String, packetClassName: String): String {
    val typeStart = findTypeDeclarationStart(source, packetClassName) ?: return source
    val openBrace = source.indexOf('{', typeStart)
    if (openBrace < 0) return source
    val header = source.substring(typeStart, openBrace)
    if (header.contains("implements CustomPacketPayload")) return source
    val insertion = if (Regex("""\bimplements\b""").containsMatchIn(header)) {
        ", CustomPacketPayload"
    } else {
        " implements CustomPacketPayload"
    }
    return source.substring(0, openBrace).trimEnd() + insertion + " " + source.substring(openBrace)
}

internal fun StructuralRefactorPass.insertNestedPacketPayloadMembers(
    source: String,
    packet: NestedSimpleChannelPacketInfo,
    modIdExpression: String
): String {
    val typeStart = findTypeDeclarationStart(source, packet.packetClassName) ?: return source
    val openBrace = source.indexOf('{', typeStart)
    if (openBrace < 0) return source
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace <= openBrace) return source
    val body = source.substring(openBrace + 1, closeBrace)
    if (body.contains("CustomPacketPayload.Type<${packet.packetClassName}> TYPE") ||
        body.contains("Type<${packet.packetClassName}> TYPE")) {
        return source
    }
    val payloadName = payloadPathName(packet.packetClassName)
    val insert = """

        public static final CustomPacketPayload.Type<${packet.packetClassName}> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath($modIdExpression, "$payloadName"));

        public static final StreamCodec<${packet.bufferType}, ${packet.packetClassName}> STREAM_CODEC =
                StreamCodec.of(${packet.packetClassName}::encode, ${packet.packetClassName}::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
"""
    return source.substring(0, openBrace + 1) + insert + source.substring(openBrace + 1)
}

internal fun StructuralRefactorPass.insertNestedSimpleChannelPayloadRegistration(
    source: String,
    ownerClassName: String,
    modIdExpression: String,
    protocolExpression: String,
    packets: List<NestedSimpleChannelPacketInfo>
): String {
    if (source.contains("registerPayloads(RegisterPayloadHandlersEvent")) return source
    var result = source
    result = addImportIfMissing(result, "net.neoforged.bus.api.SubscribeEvent")
    result = addImportIfMissing(result, "net.neoforged.fml.common.EventBusSubscriber")
    result = addImportIfMissing(result, "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent")
    result = addImportIfMissing(result, "net.neoforged.neoforge.network.registration.PayloadRegistrar")
    result = addEventBusSubscriberAnnotation(result, ownerClassName, modIdExpression)
    val registrations = packets.joinToString("\n") { packet ->
        """        registrar.${packet.direction}(
                ${packet.packetClassName}.TYPE,
                ${packet.packetClassName}.STREAM_CODEC,
                ${packet.handlerExpression}
        );"""
    }
    val method = """

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar($modIdExpression).versioned($protocolExpression);
$registrations
    }
"""
    return insertBeforeLastClassBrace(result, method)
}

internal fun StructuralRefactorPass.addEventBusSubscriberAnnotation(source: String, className: String, modIdExpression: String): String {
    if (source.contains("@EventBusSubscriber")) return source
    val typeStart = findTypeDeclarationStart(source, className) ?: return source
    val annotation = "@EventBusSubscriber(modid = $modIdExpression, bus = EventBusSubscriber.Bus.MOD)\n"
    return source.substring(0, typeStart) + annotation + source.substring(typeStart)
}

internal fun StructuralRefactorPass.rewriteNestedSimpleChannelSendCalls(source: String, channelNames: Set<String>): String {
    val result = rewriteSimpleChannelSendCallsInText(source, channelNames)
    return if (result != source) {
        addImportIfMissing(result, "net.neoforged.neoforge.network.PacketDistributor")
    } else {
        source
    }
}

internal fun StructuralRefactorPass.rewriteSimpleChannelSendCallsInText(source: String, channelNames: Set<String>): String {
    var result = source
    for (channelName in channelNames) {
        result = rewriteJavaInvocation(result, "$channelName.sendTo") { args ->
            if (args.size < 3) return@rewriteJavaInvocation null
            val payload = args[0].trim()
            val connection = args[1].trim()
            val direction = args[2].trim()
            val player = when {
                connection.endsWith(".connection.connection") -> connection.removeSuffix(".connection.connection")
                connection.endsWith(".connection.getConnection()") -> connection.removeSuffix(".connection.getConnection()")
                else -> null
            }
                ?: return@rewriteJavaInvocation null
            when {
                direction.endsWith("NetworkDirection.PLAY_TO_CLIENT") -> "PacketDistributor.sendToPlayer($player, $payload)"
                direction.endsWith("NetworkDirection.PLAY_TO_SERVER") -> "PacketDistributor.sendToServer($payload)"
                else -> null
            }
        }
        result = rewriteJavaInvocation(result, "$channelName.send") { args ->
            if (args.size < 2) return@rewriteJavaInvocation null
            val target = args[0].trim()
            val payload = args[1].trim()
            val supplierTarget = Regex("""PacketDistributor\.([A-Z_]+)\.with\s*\(\s*\(\)\s*->\s*(.+)\s*\)""")
                .matchEntire(target)
            when {
                supplierTarget != null -> {
                    val distributor = supplierTarget.groupValues[1]
                    val receiver = supplierTarget.groupValues[2].trim()
                    when (distributor) {
                        "PLAYER" -> "PacketDistributor.sendToPlayer($receiver, $payload)"
                        "TRACKING_ENTITY" -> "PacketDistributor.sendToPlayersTrackingEntity($receiver, $payload)"
                        "TRACKING_ENTITY_AND_SELF" -> "PacketDistributor.sendToPlayersTrackingEntityAndSelf($receiver, $payload)"
                        "TRACKING_CHUNK" -> "PacketDistributor.sendToPlayersTrackingChunk($receiver, $payload)"
                        else -> null
                    }
                }
                target == "PacketDistributor.ALL.noArg()" -> "PacketDistributor.sendToAllPlayers($payload)"
                target == "PacketDistributor.SERVER.noArg()" -> "PacketDistributor.sendToServer($payload)"
                else -> null
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.removeNestedSimpleChannelLegacySetup(
    source: String,
    channelNames: Set<String>,
    packets: List<NestedSimpleChannelPacketInfo>
): String {
    var result = source
    result = removeJavaStatementsMatching(result) { statement ->
        statement.contains("NetworkRegistry.newSimpleChannel") ||
            channelNames.any { statement.contains("$it.registerMessage(") }
    }
    packets.mapNotNull { it.idVariable }.toSet().forEach { idVariable ->
        result = Regex("""(?m)^[ \t]*(?:private\s+)?static\s+int\s+${Regex.escape(idVariable)}\s*=\s*0\s*;\s*\r?\n?""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*int\s+${Regex.escape(idVariable)}\s*=\s*0\s*;\s*\r?\n?""")
            .replace(result, "")
    }
    listOf(
        "net.minecraftforge.network.NetworkDirection",
        "net.minecraftforge.network.NetworkEvent",
        "net.minecraftforge.network.NetworkRegistry",
        "net.minecraftforge.network.PacketDistributor",
        "net.minecraftforge.network.simple.SimpleChannel",
        "java.util.function.Supplier"
    ).forEach { result = removeImportIfPresent(result, it) }
    result = Regex("""(?m)^[ \t]*\r?\n(?=[ \t]*\r?\n)""").replace(result, "")
    return result
}

/** A HolderLookup.Provider over the static registries, for codecs of static-registry data (fluids) with no level in scope. */
internal val STATIC_REGISTRIES_PROVIDER =
    "net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)"

internal data class ModAccess(
    val modIdExpression: String,
    val modImport: String?,
    val loggerExpression: String?
) {
    fun importLine(): String = modImport?.let { "import $it;\n" } ?: ""
}

internal data class RegistryAliasRule(val kind: String, val oldPattern: String, val newPattern: String)

internal data class RegistryAliasEntry(val registryRef: String, val oldId: String, val newId: String)

internal data class RegistryFieldInfo(
    val kind: String,
    val className: String,
    val qualifiedClassName: String,
    val fieldName: String,
    val registeredNames: Set<String>
) {
    val ref: String = "$className.$fieldName"
}

internal fun StructuralRefactorPass.inferModAccess(source: String): ModAccess? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val modIdExpression = Regex("""\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\.(?:MOD_ID|MODID|ID)\b""")
        .find(executableCode)
        ?.value
        ?: return null
    val modClass = modIdExpression.substringBefore('.')
    val modImport = Regex("""(?m)^[ \t]*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(modClass)})\s*;\s*$""")
        .find(executableCode)
        ?.groupValues
        ?.get(1)
    val loggerExpression = Regex("""\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\.LOGGER\b""")
        .find(executableCode)
        ?.value
    return ModAccess(modIdExpression, modImport, loggerExpression)
}

/**
 * Transform packet classes to implement CustomPacketPayload and generate ModNetwork.java.
 * Detects classes with static encode(ClassName, FriendlyByteBuf) + decode(FriendlyByteBuf) patterns.
 */
internal fun StructuralRefactorPass.transformPacketClasses(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    // Detect mod main class (for registering ModNetwork)
    val modMainClass = detectModMainClass(projectDir)

    // Find top-level packet class candidates. They are not migrated unless a
    // SimpleChannel registration below provides concrete channel namespace evidence.
    val packetCandidates = mutableMapOf<String, PacketClassCandidate>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { file ->
            val content = file.readText()
            staticPacketCandidate(file, content)?.let { candidate ->
                packetCandidates.putIfAbsent(candidate.className, candidate)
            }
        }

    // Find packet classes registered through a SimpleChannel with explicit namespace evidence.
    val packetClasses = mutableListOf<PacketClassInfo>()
    val existingPacketClasses = mutableSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach registrationFiles@{ file ->
            val content = file.readText()
            val executableContent = maskJavaCommentsAndLiterals(content)
            val hasRegistrationCall = executableContent.contains(".registerMessage(") ||
                executableContent.contains(".messageBuilder(")
            val hasSimpleChannelEvidence = executableContent.contains("SimpleChannel") ||
                executableContent.contains("NetworkRegistry.ChannelBuilder") ||
                executableContent.contains("NetworkRegistry.newSimpleChannel")
            if (!hasRegistrationCall || !hasSimpleChannelEvidence) return@registrationFiles

            Regex("""\b([A-Za-z_$][\w$]*)\.registerMessage\s*\(""")
                .findAll(executableContent)
                .forEach registrations@{ registration ->
                    val channelName = registration.groupValues[1]
                    val namespaceExpression = simpleChannelNamespaceExpression(content, channelName, srcDir) ?: return@registrations
                    val openParen = executableContent.indexOf('(', registration.range.first)
                    val closeParen = if (openParen >= 0) findMatchingParen(executableContent, openParen) else -1
                    if (openParen < 0 || closeParen <= openParen) return@registrations
                    val args = splitTopLevelJavaArgs(content.substring(openParen + 1, closeParen))
                    if (args.size < 5) return@registrations
                    val className = args[1].trim().removeSuffix(".class").substringAfterLast('.')
                    if (className.isBlank() || className in existingPacketClasses) return@registrations
                    val packetFile = walkJavaSourceRoots(srcDir)
                        .filter { it.fileName.toString() == "$className.java" }
                        .findFirst()
                        .orElse(null)
                        ?: return@registrations
                    val packetContent = packetFile.readText()
                    val executablePacketContent = maskJavaCommentsAndLiterals(packetContent)
                    val handlerRef = payloadHandlerLambda(args[4].trim())
                    val directionText = args.drop(5).joinToString(",")
                    val explicitRegistrationMethod = when {
                        directionText.contains("PLAY_TO_CLIENT") -> "playToClient"
                        directionText.contains("PLAY_TO_SERVER") -> "playToServer"
                        else -> null
                    }
                    val explicitClientDirection = when (explicitRegistrationMethod) {
                        "playToClient" -> true
                        "playToServer" -> false
                        else -> null
                    }
                    val candidate = packetCandidates[className]
                    if (candidate != null) {
                        val inferredClientDirection = explicitClientDirection ?: candidate.isPlayToClient
                        val registrationMethod = explicitRegistrationMethod
                            ?: inferredClientDirection?.let { if (it) "playToClient" else "playToServer" }
                            ?: "playBidirectional"
                        val isPlayToClient = inferredClientDirection ?: false
                        packetClasses.add(candidate.toPacketInfo(
                            namespaceExpression = namespaceExpression,
                            handlerRef = handlerRef,
                            isPlayToClient = isPlayToClient,
                            registrationMethod = registrationMethod
                        ))
                        existingPacketClasses += className
                        return@registrations
                    }

                    val constructorBufferType = Regex(
                        """public\s+${Regex.escape(className)}\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+\w+\s*\)"""
                    ).find(executablePacketContent)?.groupValues?.get(1)
                        ?: return@registrations
                    val encodeBufferType = Regex(
                        """\bvoid\s+encode\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+([A-Za-z_$][\w$]*)\s*\)"""
                    ).find(executablePacketContent)?.groupValues?.get(1)
                        ?: return@registrations
                    val bufferType = if (constructorBufferType == "RegistryFriendlyByteBuf" ||
                        encodeBufferType == "RegistryFriendlyByteBuf") {
                        "RegistryFriendlyByteBuf"
                    } else {
                        "FriendlyByteBuf"
                    }
                    val pkg = Regex("""package\s+([\w.]+)\s*;""").find(executablePacketContent)?.groupValues?.get(1) ?: ""
                    val inferredClientDirection = explicitClientDirection ?: packetReceptionDirection(executablePacketContent)
                    val registrationMethod = explicitRegistrationMethod
                        ?: inferredClientDirection?.let { if (it) "playToClient" else "playToServer" }
                        ?: "playBidirectional"
                    val isPlayToClient = inferredClientDirection ?: false
                    packetClasses.add(PacketClassInfo(
                        file = packetFile,
                        className = className,
                        packageName = pkg,
                        encodeParamName = className.replaceFirstChar { it.lowercase() },
                        encodeBufferName = "buf",
                        handlerRef = handlerRef,
                        isPlayToClient = isPlayToClient,
                        bufferType = bufferType,
                        streamCodecExpression = "StreamCodec.of((buf, packet) -> packet.encode(buf), $className::new)",
                        namespaceExpression = namespaceExpression,
                        registrationMethod = registrationMethod
                    ))
                    existingPacketClasses += className
                }

            Regex("""\b([A-Za-z_$][\w$]*)\.messageBuilder\s*\(""")
                .findAll(executableContent)
                .forEach registrations@{ registration ->
                    val channelName = registration.groupValues[1]
                    val namespaceExpression = simpleChannelNamespaceExpression(content, channelName, srcDir) ?: return@registrations
                    val openParen = executableContent.indexOf('(', registration.range.first)
                    val closeParen = if (openParen >= 0) findMatchingParen(executableContent, openParen) else -1
                    if (openParen < 0 || closeParen <= openParen) return@registrations
                    val args = splitTopLevelJavaArgs(content.substring(openParen + 1, closeParen)).map { it.trim() }
                    val packetReference = args.firstOrNull()
                        ?.takeIf { it.endsWith(".class") }
                        ?.removeSuffix(".class")
                        ?.trim()
                        ?: return@registrations
                    val statementEnd = executableContent.indexOf(';', closeParen).takeIf { it >= 0 } ?: closeParen
                    val registrationStatement = content.substring(registration.range.first, statementEnd + 1)
                    val registrationMethod = simpleChannelRegistrationMethodFromText(args.drop(2).joinToString(",") + registrationStatement)
                        ?: return@registrations
                    val packetType = resolveRegisteredPacketType(srcDir, content, packetReference) ?: return@registrations
                    if (packetType.registrationReferenceName in existingPacketClasses ||
                        packetType.sourceReferenceName in existingPacketClasses ||
                        packetType.simpleName in existingPacketClasses) {
                        return@registrations
                    }
                    val handlerExpression = messageBuilderHandlerExpression(registrationStatement)

                    packetCandidates[packetType.simpleName]?.let { candidate ->
                        val handlerRef = handlerExpression?.let(::payloadHandlerLambda) ?: candidate.handlerRef
                        packetClasses.add(candidate.toPacketInfo(
                            namespaceExpression = namespaceExpression,
                            handlerRef = handlerRef,
                            isPlayToClient = registrationMethod == "playToClient",
                            registrationMethod = registrationMethod
                        ))
                        existingPacketClasses += packetType.simpleName
                        return@registrations
                    }

                    val packetContent = packetType.file.readText()
                    val packetInfo = constructorPacketInfoFromRegisteredType(
                        packetType = packetType,
                        packetSource = packetContent,
                        networkPackageName = packageNameOf(content),
                        namespaceExpression = namespaceExpression,
                        registrationMethod = registrationMethod,
                        handlerExpression = handlerExpression
                    ) ?: return@registrations
                    packetClasses.add(packetInfo)
                    existingPacketClasses += packetType.registrationReferenceName
                    existingPacketClasses += packetType.sourceReferenceName
                    existingPacketClasses += packetType.simpleName
                }
        }

    if (packetClasses.isEmpty()) return changes
    val registrarNamespaceExpression = packetClasses
        .map { it.namespaceExpression }
        .distinct()
        .singleOrNull()
        ?: return changes

    // Transform each packet class
    for (info in packetClasses) {
        if (dryRun) {
            changes.add(Change(
                file = info.file, line = 1,
                description = "Will transform ${info.className} to implement CustomPacketPayload",
                before = "class ${info.className}",
                after = "class ${info.className} implements CustomPacketPayload",
                confidence = Confidence.HIGH,
                ruleId = "struct-packet-payload"
            ))
            continue
        }

        var content = info.file.readText()

        val originalContent = content
        val alreadyPayload = packetTypeAlreadyHasCustomPayloadMembers(content, info.className)

        // 1. Add imports
        val importBlock = buildString {
            if (!content.contains("import net.minecraft.network.codec.StreamCodec;"))
                append("import net.minecraft.network.codec.StreamCodec;\n")
            if (!content.contains("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;"))
                append("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;\n")
            if (!content.contains("import net.minecraft.resources.ResourceLocation;"))
                append("import net.minecraft.resources.ResourceLocation;\n")
        }
        if (importBlock.isNotEmpty()) {
            // Insert after last existing import
            val lastImport = Regex("""^import\s+[^;]+;""", RegexOption.MULTILINE).findAll(content).lastOrNull()
            if (lastImport != null) {
                val insertPos = lastImport.range.last + 1
                content = content.substring(0, insertPos) + "\n" + importBlock + content.substring(insertPos)
            }
        }

        if (!alreadyPayload) {
            // 2. Add implements CustomPacketPayload
            content = addCustomPacketPayloadInterface(content, info.className)

            // 3. Generate snake_case name for resource location
            val snakeName = payloadPathName(info.referenceName.replace('.', '_'))

            // 4. Add TYPE, STREAM_CODEC, type() after class opening brace
            val classBodyInsert = """
    public static final CustomPacketPayload.Type<${info.className}> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(${info.namespaceExpression}, "$snakeName"));

    public static final StreamCodec<${info.bufferType}, ${info.className}> STREAM_CODEC =
            ${info.streamCodecExpression};

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
"""
            val classOpenBrace = findTypeOpenBrace(content, info.className)
            if (classOpenBrace > 0) {
                content = content.substring(0, classOpenBrace + 1) + classBodyInsert + content.substring(classOpenBrace + 1)
            }
        }

        // 5. The mod's own encode(message, buf) stays as written: callers and method references
        //    (the mod's tests, helpers) depend on it. StreamCodec.of takes (buf, message), so the
        //    codec adapts the order instead of the method changing shape.
        val messageFirstEncode = Regex(
            """\bencode\s*\(\s*${Regex.escape(info.className)}\s+\w+\s*,\s*(?:RegistryFriendlyByteBuf|FriendlyByteBuf)\s+\w+\s*\)"""
        )
        if (messageFirstEncode.containsMatchIn(content)) {
            content = content.replace(
                "StreamCodec.of(${info.className}::encode, ${info.className}::decode)",
                "StreamCodec.of((buf, message) -> ${info.className}.encode(message, buf), ${info.className}::decode)"
            )
        }
        content = migrateLegacyPayloadHandlerContext(content, info, changes)
        content = cleanupLegacyPayloadContextReferences(content, info, changes)

        if (content == originalContent) continue

        info.file.writeText(content)
        changes.add(Change(
            file = info.file, line = 1,
            description = "Transformed ${info.className} to implement CustomPacketPayload with TYPE + STREAM_CODEC",
            before = "class ${info.className}",
            after = "class ${info.className} implements CustomPacketPayload",
            confidence = Confidence.HIGH,
            ruleId = "struct-packet-payload"
        ))
    }

    if (dryRun) return changes

    // 6. Generate ModNetwork.java
    val networkPkg = packetClasses.first().packageName
    val networkDir = srcDir.resolve(networkPkg.replace('.', '/'))
    val networkFile = networkDir.resolve("ModNetwork.java")
    if (!networkFile.exists()) {
        val registrations = packetClasses.joinToString("\n\n") { info ->
            val direction = info.registrationMethod ?: if (info.isPlayToClient) "playToClient" else "playToServer"
            """        registrar.$direction(
                ${info.referenceName}.TYPE,
                ${info.referenceName}.STREAM_CODEC,
                ${info.handlerRef}
        );"""
        }

        val networkContent = """package $networkPkg;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModNetwork {

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar($registrarNamespaceExpression).versioned("1");

$registrations
    }
}
"""
        networkFile.writeText(networkContent)
        changes.add(Change(
            file = networkFile, line = 1,
            description = "Generated ModNetwork.java for packet registration via RegisterPayloadHandlersEvent",
            before = "(new file)",
            after = "ModNetwork with ${packetClasses.size} packet registrations",
            confidence = Confidence.HIGH,
            ruleId = "struct-generate-mod-network"
        ))

        // 7. Register ModNetwork in the mod main class
        if (modMainClass != null) {
            var mainContent = modMainClass.readText()
            if (!mainContent.contains("ModNetwork::register")) {
                // Add import
                val importLine = "import ${networkPkg}.ModNetwork;"
                if (!mainContent.contains(importLine)) {
                    val lastImportMatch = Regex("""^import\s+[^;]+;""", RegexOption.MULTILINE)
                        .findAll(mainContent).lastOrNull()
                    if (lastImportMatch != null) {
                        val pos = lastImportMatch.range.last + 1
                        mainContent = mainContent.substring(0, pos) + "\n" + importLine + mainContent.substring(pos)
                    }
                }

                val eventBusName = detectModBusVariable(mainContent)
                if (eventBusName != null) {
                    val listenerLine = "        $eventBusName.addListener(ModNetwork::register);"
                    mainContent = insertModBusListener(mainContent, eventBusName, listenerLine, "ModNetwork")
                }
                if (!mainContent.contains("ModNetwork::register")) {
                    val containerMatch = Regex("""(modContainer\.(?:registerConfig|getEventBus\(\)\.addListener))""").find(mainContent)
                    if (containerMatch != null) {
                        val pos = mainContent.lastIndexOf('\n', containerMatch.range.first)
                        val regLine = "\n        modContainer.getEventBus().addListener(ModNetwork::register);\n"
                        mainContent = mainContent.substring(0, pos) + regLine + mainContent.substring(pos)
                    }
                }
                modMainClass.writeText(mainContent)
                changes.add(Change(
                    file = modMainClass, line = 1,
                    description = "Registered ModNetwork packet handler in main mod class",
                    before = "(no packet registration)",
                    after = "modEventBus.addListener(ModNetwork::register)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-register-mod-network"
                ))
            }
        }
    }

    // 8. Replace old SimpleChannel registration class (ModPackets, PacketHandler, etc.)
    // Find files that contain SimpleChannel or INSTANCE.messageBuilder and rewrite them
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .filter { it.fileName.toString() != "ModNetwork.java" }
        .forEach { file ->
            val content = file.readText()
            if (content.contains("@Mod(") || content.contains("@Mod\n")) return@forEach
            // Detect old SimpleChannel registration classes
            val hasSimpleChannel = content.contains("SimpleChannel") ||
                content.contains("NetworkRegistry.ChannelBuilder") ||
                content.contains("NetworkRegistry.newSimpleChannel") ||
                (content.contains("INSTANCE") && content.contains("messageBuilder"))
            if (!hasSimpleChannel) return@forEach
            val modNetworkFile = file.parent?.resolve("ModNetwork.java")
            val modNetworkTexts = buildList {
                if (modNetworkFile != null && Files.exists(modNetworkFile)) {
                    add(modNetworkFile.readText())
                }
                walkJavaSourceRoots(srcDir).use { files ->
                    files
                        .filter { it.fileName.toString() == "ModNetwork.java" }
                        .filter { modNetworkFile == null || it != modNetworkFile }
                        .map { it.readText() }
                        .forEach(::add)
                }
            }
            val hasPayloadRegistrarEvidence = modNetworkTexts.any { modNetworkText ->
                modNetworkText.contains("PayloadRegistrar") && modNetworkText.contains("registrar.")
            }
            if (!hasPayloadRegistrarEvidence) return@forEach

            val registeredPacketNames = registeredSimpleChannelPacketSimpleNames(content)
            if (registeredPacketNames.isNotEmpty()) {
                val allRegisteredPacketsHavePayloads = registeredPacketNamesHavePayloadEvidence(
                    srcDir,
                    modNetworkTexts,
                    registeredPacketNames
                )
                if (!allRegisteredPacketsHavePayloads) return@forEach
            }

            val pkg = Regex("""package\s+([\w.]+)\s*;""").find(content)?.groupValues?.get(1) ?: return@forEach
            val className = file.fileName.toString().removeSuffix(".java")
            val preservedMethods = preservedSimpleChannelWrapperMethods(content)
            val preservedMethodNames = preservedMethods
                .mapNotNull { javaMethodNameFromHeader(it.substringBefore('{')) }
                .toSet()

            // Check if this class has send helper methods (sendToServer, sendToPlayer, etc.)
            val hasSendHelpers = content.contains("sendToServer") || content.contains("sendToPlayer") ||
                content.contains("sendToAllClients") || content.contains("sendToTracking")
            val hasSendToDimension = content.contains("sendToDimension")
            val hasSendToTracking = content.contains("sendToTracking(")
            val hasSendToTrackingAndSelf = content.contains("sendToTrackingAndSelf")

            val newContent = buildString {
                appendLine("package $pkg;")
                appendLine()
                simpleChannelWrapperImports(content, preservedMethods, hasSendHelpers).forEach { importLine ->
                    appendLine(importLine)
                }
                if (hasSendToDimension) {
                    appendLine("import net.minecraft.resources.ResourceKey;")
                    appendLine("import net.minecraft.server.level.ServerLevel;")
                    appendLine("import net.minecraft.world.level.Level;")
                    appendLine("import net.neoforged.neoforge.server.ServerLifecycleHooks;")
                }
                if (hasSendToTracking || hasSendToTrackingAndSelf) {
                    appendLine("import net.minecraft.world.entity.Entity;")
                }
                appendLine()
                appendLine("public class $className {")
                appendLine()
                appendLine("    public static void registerPackets() {")
                appendLine("    }")
                appendLine()
                appendLine("    public static void init() {")
                appendLine("        registerPackets();")
                appendLine("    }")
                appendLine()
                appendLine("    public static void register() {")
                appendLine("        registerPackets();")
                appendLine("    }")
                appendLine()
                if (hasSendHelpers) {
                    if ("sendToServer" !in preservedMethodNames) {
                        appendLine("    public static <T extends CustomPacketPayload> void sendToServer(T msg) {")
                        appendLine("        PacketDistributor.sendToServer(msg);")
                        appendLine("    }")
                        appendLine()
                    }
                    if ("sendToPlayer" !in preservedMethodNames) {
                        appendLine("    public static <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T msg) {")
                        appendLine("        if (NetworkRegistry.hasChannel(player.connection, msg.type().id())) {")
                        appendLine("            PacketDistributor.sendToPlayer(player, msg);")
                        appendLine("        }")
                        appendLine("    }")
                        appendLine()
                        appendLine("    public static <T extends CustomPacketPayload> void sendToPlayer(T msg, ServerPlayer player) {")
                        appendLine("        sendToPlayer(player, msg);")
                        appendLine("    }")
                        appendLine()
                    }
                    if ("sendToAllClients" !in preservedMethodNames) {
                        appendLine("    public static <T extends CustomPacketPayload> void sendToAllClients(T msg) {")
                        appendLine("        PacketDistributor.sendToAllPlayers(msg);")
                        appendLine("    }")
                        appendLine()
                    }
                    if (hasSendToDimension && "sendToDimension" !in preservedMethodNames) {
                        appendLine("    public static <T extends CustomPacketPayload> void sendToDimension(T msg, ResourceKey<Level> dimension) {")
                        appendLine("        ServerLevel level = ServerLifecycleHooks.getCurrentServer().getLevel(dimension);")
                        appendLine("        if (level != null) PacketDistributor.sendToPlayersInDimension(level, msg);")
                        appendLine("    }")
                        appendLine()
                    }
                    if (hasSendToTracking && "sendToTracking" !in preservedMethodNames) {
                        appendLine("    public static <T extends CustomPacketPayload> void sendToTracking(T msg, Entity entity) {")
                        appendLine("        PacketDistributor.sendToPlayersTrackingEntity(entity, msg);")
                        appendLine("    }")
                        appendLine()
                    }
                    if (hasSendToTrackingAndSelf && "sendToTrackingAndSelf" !in preservedMethodNames) {
                        appendLine("    public static <T extends CustomPacketPayload> void sendToTrackingAndSelf(T msg, Entity entity) {")
                        appendLine("        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, msg);")
                        appendLine("    }")
                        appendLine()
                    }
                }
                preservedMethods.forEach { method ->
                    append(method)
                    if (!method.endsWith("\n")) appendLine()
                    appendLine()
                }
                appendLine("}")
            }

            if (!dryRun) {
                file.writeText(newContent)
            }
            changes.add(Change(
                file = file, line = 1,
                description = "Replaced old SimpleChannel registration class $className with NeoForge-compatible wrapper",
                before = "(SimpleChannel registration)",
                after = "(PacketDistributor wrapper + ModNetwork delegation)",
                confidence = Confidence.HIGH,
                ruleId = "struct-replace-modpackets"
            ))
        }

    changes.addAll(cleanupInlineSimpleChannelRegistrations(srcDir, dryRun))

    return changes
}

internal fun StructuralRefactorPass.simpleChannelWrapperImports(
    source: String,
    preservedMethods: List<String>,
    hasSendHelpers: Boolean
): List<String> {
    val imports = linkedSetOf<String>()
    Regex("""(?m)^[ \t]*import\s+([^;]+);\s*$""")
        .findAll(source)
        .map { it.groupValues[1].trim() }
        .filterNot { imported ->
            imported == "net.minecraftforge.network.NetworkRegistry" ||
                imported == "net.minecraftforge.network.NetworkDirection" ||
                imported == "net.minecraftforge.network.PacketDistributor" ||
                imported == "net.neoforged.neoforge.network.NetworkRegistry" ||
                imported == "net.neoforged.neoforge.network.NetworkDirection" ||
                imported == "net.neoforged.neoforge.network.PacketDistributor" ||
                imported == "net.minecraftforge.network.simple.SimpleChannel" ||
                imported == "net.neoforged.neoforge.network.simple.SimpleChannel" ||
                imported.endsWith(".SimpleChannel")
        }
        .forEach { imports += "import $it;" }

    val preservedText = preservedMethods.joinToString("\n")
    if (hasSendHelpers || preservedText.contains("PacketDistributor.")) {
        imports += "import net.neoforged.neoforge.network.PacketDistributor;"
    }
    if (hasSendHelpers || preservedText.contains("NetworkRegistry.hasChannel")) {
        imports += "import net.neoforged.neoforge.network.registration.NetworkRegistry;"
    }
    if (hasSendHelpers || preservedText.contains("CustomPacketPayload")) {
        imports += "import net.minecraft.network.protocol.common.custom.CustomPacketPayload;"
    }
    if (hasSendHelpers || preservedText.contains("ServerPlayer")) {
        imports += "import net.minecraft.server.level.ServerPlayer;"
    }
    return imports.toList()
}

internal fun StructuralRefactorPass.preservedSimpleChannelWrapperMethods(source: String): List<String> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val channelNames = nestedSimpleChannelNames(source)
    return javaMethodRangesIncludingDefault(executableCode)
        .mapNotNull { method ->
            val methodText = rewriteSimpleChannelSendCallsInText(
                source.substring(method.range).trimEnd(),
                channelNames
            )
            val methodHeader = methodText.substringBefore('{')
            val methodCode = maskJavaCommentsAndLiterals(methodText)
            val methodName = javaMethodNameFromHeader(methodHeader) ?: method.name
            if (!Regex("""\bpublic\s+static\b""").containsMatchIn(methodHeader)) return@mapNotNull null
            if (methodName in setOf("register", "registerPackets", "init")) return@mapNotNull null
            if (methodCode.contains("SimpleChannel") ||
                methodCode.contains(".registerMessage(") ||
                methodCode.contains(".messageBuilder(") ||
                methodCode.contains("NetworkRegistry.newSimpleChannel") ||
                methodCode.contains("NetworkRegistry.ChannelBuilder") ||
                (methodCode.contains(".send(") && methodCode.contains("PacketDistributor.")) ||
                methodCode.contains("CHANNEL.")) {
                return@mapNotNull null
            }
            if (methodName == "sendToTracking" &&
                methodCode.contains("PacketDistributor.sendToPlayersTrackingEntity(") &&
                Regex("""\bsendToTracking\s*\(\s*[^,]+,\s*(?:net\.minecraft\.world\.entity\.)?Entity\s+[A-Za-z_$][\w$]*\s*\)""")
                    .containsMatchIn(methodHeader)) {
                return@mapNotNull null
            }
            if (methodName.startsWith("sendTo") && !methodCode.contains("PacketDistributor.")) {
                return@mapNotNull null
            }
            guardPacketDistributorSendToPlayerCalls(
                migrateLegacyPacketDistributorHelperSignature(methodText)
            )
        }
}

internal fun StructuralRefactorPass.guardPacketDistributorSendToPlayerCalls(methodText: String): String =
    Regex("""(?m)^([ \t]*)PacketDistributor\.sendToPlayer\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*;\s*$""")
        .replace(methodText) { match ->
            val indent = match.groupValues[1]
            val player = match.groupValues[2]
            val payload = match.groupValues[3]
            """
${indent}if (NetworkRegistry.hasChannel($player.connection, $payload.type().id())) {
${indent}    PacketDistributor.sendToPlayer($player, $payload);
${indent}}
""".trimEnd()
        }

internal fun StructuralRefactorPass.migrateLegacyPacketDistributorHelperSignature(methodText: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    fun sendsPayload(payloadName: String): Boolean =
        methodText.contains("PacketDistributor.") &&
            Regex("""\b${Regex.escape(payloadName)}\b""")
                .containsMatchIn(methodText.substringAfter("PacketDistributor."))

    var result = methodText
    result = Regex("""public\s+static\s+void\s+($id)\s*\(\s*Object\s+($id)\s*\)""")
        .replace(result) { match ->
            val payloadName = match.groupValues[2]
            if (sendsPayload(payloadName)) {
                "public static <T extends CustomPacketPayload> void ${match.groupValues[1]}(T $payloadName)"
            } else {
                match.value
            }
        }
    result = Regex("""public\s+static\s+void\s+($id)\s*\(\s*Object\s+($id)\s*,""")
        .replace(result) { match ->
            val payloadName = match.groupValues[2]
            if (sendsPayload(payloadName)) {
                "public static <T extends CustomPacketPayload> void ${match.groupValues[1]}(T $payloadName,"
            } else {
                match.value
            }
        }
    result = Regex("""public\s+static\s+<\s*($id)\s*>\s+void\s+($id)\s*\(\s*\1\s+($id)""")
        .replace(result) { match ->
            val payloadName = match.groupValues[3]
            if (sendsPayload(payloadName)) {
                "public static <T extends CustomPacketPayload> void ${match.groupValues[2]}(T $payloadName"
            } else {
                match.value
            }
        }
    return result
}

internal fun StructuralRefactorPass.javaMethodNameFromHeader(header: String): String? =
    Regex("""\b([A-Za-z_$][\w$]*)\s*\([^()]*\)\s*$""")
        .find(header.trim())
        ?.groupValues
        ?.get(1)

internal fun StructuralRefactorPass.detectModBusVariable(source: String): String? =
    resolveModEventBusName(source)

internal fun StructuralRefactorPass.requireModEventBusName(source: String, context: String): String =
    resolveModEventBusName(source)
        ?: error(
            "Cannot derive mod event bus variable for $context: expected an @Mod constructor " +
                "IEventBus parameter or an IEventBus local assigned from getEventBus()/getModEventBus()"
        )

internal data class ConstructorSection(
    val header: String,
    val body: String
)

internal fun StructuralRefactorPass.resolveModEventBusName(source: String): String? {
    val className = classNameOfJavaSource(source) ?: return null
    val busType = """(?:IEventBus|net\.neoforged\.bus\.api\.IEventBus)"""
    val parameterBus = Regex("""\b(?:final\s+)?$busType\s+([A-Za-z_$][\w$]*)\b""")
    val localEventBus = Regex(
        """\b$busType\s+([A-Za-z_$][\w$]*)\s*=\s*[^;]*(?:getEventBus|getModEventBus)\s*\(\s*\)\s*;"""
    )

    for (constructor in constructorSections(source, className)) {
        parameterBus.find(constructor.header)?.groupValues?.get(1)?.let { return it }
        localEventBus.find(constructor.body)?.groupValues?.get(1)?.let { return it }
    }

    return null
}

internal fun StructuralRefactorPass.constructorSections(source: String, className: String): List<ConstructorSection> {
    val constructorPattern = Regex(
        """\b(?:public|protected|private)?\s*${Regex.escape(className)}\s*\([^)]*\)\s*\{"""
    )
    return constructorPattern.findAll(source).mapNotNull { match ->
        val openBrace = source.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) return@mapNotNull null
        ConstructorSection(
            header = source.substring(match.range.first, openBrace),
            body = source.substring(openBrace + 1, closeBrace)
        )
    }.toList()
}

internal fun StructuralRefactorPass.cleanupInlineSimpleChannelRegistrations(srcDir: Path, dryRun: Boolean): List<Change> {
    val hasGeneratedNetwork = walkJavaSourceRoots(srcDir)
        .use { stream -> stream.anyMatch { it.fileName.toString() == "ModNetwork.java" } }
    if (!hasGeneratedNetwork) return emptyList()

    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .forEach { javaFile ->
            val original = javaFile.readText()
            if (!original.contains("SimpleChannel") &&
                !original.contains("NetworkRegistry.newSimpleChannel") &&
                !original.contains("NetworkRegistry.ChannelBuilder") &&
                !original.contains(".registerMessage(") &&
                !original.contains(".messageBuilder(")) {
                return@forEach
            }
            val modNetworkTexts = walkJavaSourceRoots(srcDir).use { stream ->
                stream
                    .filter { it.extension == "java" && it.fileName.toString() == "ModNetwork.java" }
                    .map { it.readText() }
                    .toList()
            }
            val registeredPacketNames = registeredSimpleChannelPacketSimpleNames(original)
            if (registeredPacketNames.isNotEmpty() &&
                !registeredPacketNamesHavePayloadEvidence(srcDir, modNetworkTexts, registeredPacketNames)) {
                return@forEach
            }

            val channelNames = linkedSetOf<String>()
            Regex(
                """(?m)^[ \t]*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:SimpleChannel|EventNetworkChannel)\s+([A-Za-z_$][\w$]*)\b[^;]*;\s*\r?\n?"""
            ).findAll(original).forEach { channelNames.add(it.groupValues[1]) }
            Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*NetworkRegistry\.newSimpleChannel\s*\(""")
                .findAll(original)
                .forEach { channelNames.add(it.groupValues[1]) }
            Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*NetworkRegistry\.ChannelBuilder\.named\s*\(""")
                .findAll(original)
                .forEach { channelNames.add(it.groupValues[1]) }
            if (channelNames.isEmpty()) return@forEach

            var modified = original
            modified = Regex(
                """(?m)^[ \t]*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:SimpleChannel|EventNetworkChannel)\s+(?:${channelNames.joinToString("|") { Regex.escape(it) }})\b[^;]*;\s*\r?\n?"""
            ).replace(modified, "")
            for (channelName in channelNames) {
                modified = removeJavaStatementsMatching(modified) { statement ->
                    statement.contains("$channelName = NetworkRegistry.newSimpleChannel") ||
                        statement.contains("$channelName = NetworkRegistry.ChannelBuilder") ||
                        statement.contains("$channelName.registerMessage(") ||
                        statement.contains("$channelName.messageBuilder(")
                }
            }
            if (!Regex("""\b(?:registerMessage|messageBuilder)\s*\(""").containsMatchIn(modified)) {
                modified = Regex("""(?m)^[ \t]*(?:int|var)\s+packetIndex\s*=\s*0\s*;\s*\r?\n?""")
                    .replace(modified, "")
            }
            modified = removeImportIfPresent(modified, "net.minecraftforge.network.NetworkRegistry")
            modified = removeImportIfPresent(modified, "net.minecraftforge.network.simple.SimpleChannel")
            modified = removeImportIfPresent(modified, "net.minecraftforge.network.EventNetworkChannel")
            modified = removeImportIfPresent(modified, "net.minecraftforge.network.NetworkDirection")

            if (modified != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Remove legacy SimpleChannel declarations and registerMessage setup after payload registration migration",
                    before = "SimpleChannel field + NetworkRegistry.newSimpleChannel/registerMessage",
                    after = "RegisterPayloadHandlersEvent via generated ModNetwork",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-remove-inline-simplechannel-registration"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }
    return changes
}

internal data class BasePacketPayloadInfo(
    val file: Path,
    val packageName: String,
    val ownerClassName: String,
    val simpleName: String,
    val referenceName: String,
    val bufferType: String,
    val direction: String,
    val registerable: Boolean,
    val handlerExpression: String = "(payload, context) -> payload.execute(context.player())"
)

internal fun StructuralRefactorPass.migrateBasePacketPayloads(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val modId = detectModId(projectDir) ?: return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val payloads = linkedMapOf<String, BasePacketPayloadInfo>()
    val changes = mutableListOf<Change>()

    for (file in javaFiles) {
        val original = file.readText()
        val executableCode = maskJavaCommentsAndLiterals(original)
        if (!hasNitrogenBasePacketApiReference(original) ||
            !executableCode.contains("decode(") ||
            !executableCode.contains("execute(")) {
            continue
        }
        val packageName = packageNameOf(original)
        val ownerClassName = classNameOfJavaSource(original) ?: continue
        val payloadTypes = extractBasePacketPayloadTypes(file, original, packageName, ownerClassName)
        if (payloadTypes.isEmpty()) continue

        var modified = original
        modified = Regex("""implements\s+(?:com\.aetherteam\.nitrogen\.network\.)?BasePacket""")
            .replace(modified, "implements CustomPacketPayload")
        modified = removeImportIfPresent(modified, "com.aetherteam.nitrogen.network.BasePacket")
        modified = removeImportIfPresent(modified, "net.minecraftforge.network.NetworkEvent")
        modified = addImportIfMissing(modified, "net.minecraft.network.codec.StreamCodec")
        modified = addImportIfMissing(modified, "net.minecraft.network.protocol.common.custom.CustomPacketPayload")
        modified = addImportIfMissing(modified, "net.minecraft.resources.ResourceLocation")
        modified = Regex("""(?m)^([ \t]*)@Override\s*\r?\n\1(public\s+void\s+(?:encode|execute)\s*\()""")
            .replace(modified) { match -> match.groupValues[1] + match.groupValues[2] }

        for (payload in payloadTypes) {
            if (payload.registerable &&
                !modified.contains("Type<${payload.simpleName}> TYPE") &&
                !modified.contains("Type<${payload.referenceName}> TYPE")) {
                val insert = """
        public static final CustomPacketPayload.Type<${payload.simpleName}> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("$modId", "${payloadPathName(payload.referenceName.replace('.', '_'))}"));

        public static final StreamCodec<${payload.bufferType}, ${payload.simpleName}> STREAM_CODEC =
                StreamCodec.of((buf, packet) -> packet.encode(buf), ${payload.simpleName}::decode);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

""".trimStart()
                val openBrace = findTypeOpenBrace(modified, payload.simpleName)
                if (openBrace >= 0) {
                    modified = modified.substring(0, openBrace + 1) + "\n" + insert + modified.substring(openBrace + 1)
                }
            }
            if (payload.registerable) {
                payloads[payload.referenceName] = payload
            }
        }

        if (modified != original) {
            if (!dryRun) file.writeText(modified)
            changes.add(Change(
                file = file,
                line = 1,
                description = "Migrate BasePacket payload type(s) to CustomPacketPayload",
                before = "implements BasePacket + encode/decode/execute(Player)",
                after = "implements CustomPacketPayload + TYPE/STREAM_CODEC",
                confidence = Confidence.HIGH,
                ruleId = "struct-nitrogen-basepacket-payload"
            ))
        }
    }

    changes.addAll(rewriteBasePacketHandlers(projectDir, srcDir, modId, payloads, dryRun))
    changes.addAll(rewritePacketRelayCalls(srcDir, dryRun))
    return changes
}

internal fun StructuralRefactorPass.extractBasePacketPayloadTypes(
    file: Path,
    source: String,
    packageName: String,
    ownerClassName: String
): List<BasePacketPayloadInfo> {
    val results = mutableListOf<BasePacketPayloadInfo>()
    val code = maskJavaComments(source)
    val executableCode = maskJavaCommentsAndLiterals(source)
    val imports = javaNonStaticImports(code)
    val wildcardImports = javaNonStaticWildcardImports(code)
    val sourceDeclaredBasePacketTypes = sourceDeclaredNitrogenBasePacketTypeNames(
        source,
        packageName,
        imports,
        wildcardImports
    )
    val decodePattern = Regex(
        """public\s+static\s+(?:[A-Za-z_$][\w$]*\.)*([A-Za-z_$][\w$]*)\s+decode\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+([A-Za-z_$][\w$]*)\s*\)"""
    )
    for (decode in decodePattern.findAll(executableCode)) {
        val simpleName = decode.groupValues[1]
        val bufferType = decode.groupValues[2]
        val typeStart = findTypeDeclarationStart(source, simpleName) ?: continue
        val openBrace = source.indexOf('{', typeStart)
        if (openBrace < 0 || decode.range.first < openBrace) continue
        val header = source.substring(typeStart, openBrace)
        if (!typeHeaderDeclaresOrInheritsNitrogenBasePacket(
                header,
                packageName,
                imports,
                wildcardImports,
                sourceDeclaredBasePacketTypes
            )) {
            continue
        }
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace <= openBrace || decode.range.first > closeBrace) continue
        val body = source.substring(openBrace + 1, closeBrace)
        if (!Regex("""\bvoid\s+execute\s*\(\s*(?:@\w+\s+)?(?:Player|ServerPlayer)\s+[A-Za-z_$][\w$]*\s*\)""")
                .containsMatchIn(body)) {
            continue
        }
        val referenceName = if (simpleName == ownerClassName) simpleName else "$ownerClassName.$simpleName"
        results.add(BasePacketPayloadInfo(
            file = file,
            packageName = packageName,
            ownerClassName = ownerClassName,
            simpleName = simpleName,
            referenceName = referenceName,
            bufferType = bufferType,
            direction = packetDirectionFromPackage(packageName),
            registerable = true
        ))
    }

    val ownerTypeStart = findTypeDeclarationStart(source, ownerClassName)
    val ownerOpenBrace = ownerTypeStart?.let { source.indexOf('{', it) } ?: -1
    val topLevelImplementsBasePacket = ownerTypeStart != null &&
        ownerOpenBrace > ownerTypeStart &&
        typeHeaderImplementsNitrogenBasePacket(
            source.substring(ownerTypeStart, ownerOpenBrace),
            packageName,
            imports,
            wildcardImports
        )
    if (topLevelImplementsBasePacket &&
        results.none { it.simpleName == ownerClassName } &&
        Regex("""\bvoid\s+encode\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(source)) {
        // Abstract base packets, such as shared boss-bar packets, still need
        // the BasePacket interface removed. Concrete nested payloads above
        // provide TYPE/STREAM_CODEC/type().
        results.add(BasePacketPayloadInfo(
            file = file,
            packageName = packageName,
            ownerClassName = ownerClassName,
            simpleName = ownerClassName,
            referenceName = ownerClassName,
            bufferType = Regex("""\bvoid\s+encode\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+""")
                .find(source)
                ?.groupValues
                ?.get(1) ?: "FriendlyByteBuf",
            direction = packetDirectionFromPackage(packageName),
            registerable = false
        ))
    }
    return results
}

internal fun StructuralRefactorPass.findTypeDeclarationStart(source: String, simpleName: String): Int? =
    Regex("""(?m)^[ \t]*(?:(?:@[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\s*\([^;\r\n]*\))?|public|protected|private|static|abstract|final|sealed|non-sealed)\s+)*(?:class|record)\s+${Regex.escape(simpleName)}\b""")
        .find(source)
        ?.range
        ?.first

internal fun StructuralRefactorPass.packetDirectionFromPackage(packageName: String): String = when {
    packageName.contains(".clientbound") -> "playToClient"
    packageName.contains(".serverbound") -> "playToServer"
    else -> "playBidirectional"
}

internal fun StructuralRefactorPass.rewriteBasePacketHandlers(
    projectDir: Path,
    srcDir: Path,
    modId: String,
    payloads: Map<String, BasePacketPayloadInfo>,
    dryRun: Boolean
): List<Change> {
    val changes = mutableListOf<Change>()
    val uniquePayloadBySimpleName = payloads.values
        .groupBy { it.simpleName }
        .mapNotNull { (simpleName, candidates) ->
            val distinctCandidates = candidates.distinctBy { it.referenceName }
            if (distinctCandidates.size == 1) simpleName to distinctCandidates.single() else null
        }
        .toMap()
    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
        .forEach { file ->
            val original = file.readText()
            val executableCode = maskJavaCommentsAndLiterals(original)
            if (!hasNitrogenBasePacketApiReference(original) ||
                !executableCode.contains("messageBuilder") ||
                !executableCode.contains("register(")) {
                return@forEach
            }
            val registrations = Regex("""register\s*\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class\s*,\s*[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*::decode\s*\)\s*;""")
                .findAll(original)
                .mapNotNull { match ->
                    val packetRef = match.groupValues[1]
                    val payload = payloads[packetRef]
                        ?: uniquePayloadBySimpleName[packetRef.substringAfterLast('.')]
                        ?: registeredPacketPayloadInfo(srcDir, packetRef)
                        ?: return@mapNotNull null
                    "        registrar.${payload.direction}(${payload.referenceName}.TYPE, ${payload.referenceName}.STREAM_CODEC, ${payload.handlerExpression});"
                }
                .toList()
            if (registrations.isEmpty()) return@forEach

            val packageName = packageNameOf(original)
            val className = classNameOfJavaSource(original) ?: return@forEach
            val classIsPublic = javaTopLevelTypeIsPublic(original, className)
            val packetImports = Regex("""(?m)^[ \t]*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.network\.packet(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?);\s*$""")
                .findAll(original)
                .map { "import ${it.groupValues[1]};" }
                .distinct()
                .joinToString("\n")
                .let { if (it.isBlank()) "" else "$it\n" }
            val migrated = """package $packageName;

${packetImports}import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

${if (classIsPublic) "public " else ""}class $className {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("$modId").versioned("1");
${registrations.distinct().joinToString("\n")}
    }
}
"""
            if (!dryRun) file.writeText(migrated)
            changes.add(Change(
                file = file,
                line = 1,
                description = "Migrate BasePacket SimpleChannel registrations to PayloadRegistrar",
            before = "SimpleChannel.messageBuilder(... BasePacket::handle)",
            after = "RegisterPayloadHandlersEvent PayloadRegistrar registrations",
            confidence = Confidence.HIGH,
            ruleId = "struct-nitrogen-basepacket-handler-registration"
        ))
            val mainClass = detectModMainClass(projectDir) ?: return@forEach
            val mainOriginal = mainClass.readText()
            val busName = detectModBusVariable(mainOriginal) ?: return@forEach
            val listenerLine = "        $busName.addListener($className::register);"
            var mainModified = mainOriginal.replace(
                Regex("""(?m)^[ \t]*${Regex.escape(className)}\.register\s*\(\s*\)\s*;\s*\r?\n"""),
                ""
            )
            if (!mainModified.contains("$className::register")) {
                mainModified = insertModBusListener(mainModified, busName, listenerLine, className)
            }
            if (mainModified != mainOriginal) {
                if (!dryRun) mainClass.writeText(mainModified)
                changes.add(Change(
                    file = mainClass,
                    line = 1,
                    description = "Register migrated BasePacket payload handler on mod event bus",
                    before = "$className.register()",
                    after = "$busName.addListener($className::register)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-nitrogen-basepacket-main-registration"
                ))
            }
        }
    return changes
}

internal fun StructuralRefactorPass.registeredPacketPayloadInfo(srcDir: Path, packetRef: String): BasePacketPayloadInfo? {
    val simpleName = packetRef.substringAfterLast('.')
    val refSegments = packetRef.split('.')
    val candidateTypeNames = buildSet {
        add(simpleName)
        refSegments.dropLast(1).forEach { add(it) }
    }
    val javaFiles = walkJavaSourceRoots(srcDir).use { stream ->
        stream
            .filter { it.extension == "java" }
            .toList()
    }
    for (file in javaFiles) {
        val source = file.readText()
        if (candidateTypeNames.none { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(source) }) continue
        val packageName = packageNameOf(source)
        val ownerClassName = classNameOfJavaSource(source) ?: continue
        val decode = Regex(
            """public\s+static\s+(?:(?:${Regex.escape(packetRef)})|(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(simpleName)})\s+decode\s*\(\s*(RegistryFriendlyByteBuf|FriendlyByteBuf)\s+[A-Za-z_$][\w$]*\s*\)"""
        ).find(source) ?: continue
        if (!hasRegisteredPayloadSourceShape(source, packageName, ownerClassName, simpleName)) continue
        return BasePacketPayloadInfo(
            file = file,
            packageName = packageName,
            ownerClassName = ownerClassName,
            simpleName = simpleName,
            referenceName = packetRef,
            bufferType = decode.groupValues[1],
            direction = packetDirectionFromPackage(packageName),
            registerable = true,
            handlerExpression = registeredPacketHandlerExpression(source, packageName)
        )
    }
    return null
}

internal fun StructuralRefactorPass.registeredPacketHandlerExpression(source: String, packageName: String): String {
    val imports = javaNonStaticImports(source)
    val entityPacket = resolveJavaTypeReference("SyncEntityPacket", packageName, imports)
    val levelPacket = resolveJavaTypeReference("SyncLevelPacket", packageName, imports)
    return when {
        source.contains("extends SyncEntityPacket<") && entityPacket != null ->
            "(payload, context) -> $entityPacket.execute(payload, context.player())"
        source.contains("extends SyncLevelPacket<") && levelPacket != null ->
            "(payload, context) -> $levelPacket.execute(payload, context.player())"
        else -> "(payload, context) -> payload.execute(context.player())"
    }
}

internal fun StructuralRefactorPass.hasRegisteredPayloadSourceShape(
    source: String,
    packageName: String,
    ownerClassName: String,
    simpleName: String
): Boolean {
    val typeStart = findTypeDeclarationStart(source, simpleName) ?: findTypeDeclarationStart(source, ownerClassName) ?: return false
    val openBrace = source.indexOf('{', typeStart)
    if (openBrace < 0) return false
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace <= openBrace) return false
    val header = source.substring(typeStart, openBrace)
    val body = source.substring(openBrace + 1, closeBrace)
    val code = maskJavaComments(source)
    val imports = javaNonStaticImports(code)
    val wildcardImports = javaNonStaticWildcardImports(code)
    val sourceDeclaredBasePacketTypes = sourceDeclaredNitrogenBasePacketTypeNames(
        source,
        packageName,
        imports,
        wildcardImports
    )
    return typeHeaderDeclaresOrInheritsNitrogenBasePacket(
        header,
        packageName,
        imports,
        wildcardImports,
        sourceDeclaredBasePacketTypes
    ) ||
        header.contains("extends SyncEntityPacket<") ||
        header.contains("extends SyncLevelPacket<") ||
        body.contains("CustomPacketPayload.Type<") ||
        body.contains("CustomPacketPayload.Type<${simpleName}>")
}

internal fun StructuralRefactorPass.rewritePacketRelayCalls(srcDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
        .forEach { file ->
            val original = file.readText()
            if (!hasNitrogenPacketRelayApiReference(original)) return@forEach
            var modified = original
            modified = rewriteJavaInvocation(modified, "PacketRelay.sendToServer") { args ->
                if (args.size == 2) "PacketDistributor.sendToServer(${args[1].trim()})" else null
            }
            modified = rewriteJavaInvocation(modified, "PacketRelay.sendToPlayer") { args ->
                if (args.size == 3) "PacketDistributor.sendToPlayer(${args[2].trim()}, ${args[1].trim()})" else null
            }
            modified = rewriteJavaInvocation(modified, "PacketRelay.sendToAll") { args ->
                if (args.size == 2) "PacketDistributor.sendToAllPlayers(${args[1].trim()})" else null
            }
            modified = rewriteJavaInvocation(modified, "PacketRelay.sendToNear") { args ->
                if (args.size != 7) return@rewriteJavaInvocation null
                val levelExpr = args[6].trim().removeSuffix(".dimension()")
                if (!Regex("""\bServerLevel\s+${Regex.escape(levelExpr)}\b""").containsMatchIn(modified)) {
                    return@rewriteJavaInvocation null
                }
                "PacketDistributor.sendToPlayersNear($levelExpr, null, ${args[2].trim()}, ${args[3].trim()}, ${args[4].trim()}, ${args[5].trim()}, ${args[1].trim()})"
            }
            if (modified != original) {
                modified = addImportIfMissing(modified, "net.neoforged.neoforge.network.PacketDistributor")
                modified = removeImportIfPresent(modified, "com.aetherteam.nitrogen.network.PacketRelay")
                modified = removeUnusedImportsBySimpleNamePattern(modified, Regex("""[A-Za-z_$][\w$]*PacketHandler"""))
                if (!dryRun) file.writeText(modified)
                changes.add(Change(
                    file = file,
                    line = 1,
                    description = "Migrate PacketRelay sends to NeoForge PacketDistributor",
                    before = "PacketRelay.sendTo*(channel, packet, ...)",
                    after = "PacketDistributor.sendTo*(packet, ...)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-nitrogen-packetrelay-distributor"
                ))
            }
        }
    return changes
}

internal fun StructuralRefactorPass.hasNitrogenBasePacketApiReference(source: String): Boolean =
    hasResolvedNitrogenNetworkTypeReference(source, "BasePacket", "com.aetherteam.nitrogen.network.BasePacket")

internal fun StructuralRefactorPass.hasNitrogenPacketRelayApiReference(source: String): Boolean =
    hasResolvedNitrogenNetworkTypeReference(source, "PacketRelay", "com.aetherteam.nitrogen.network.PacketRelay") &&
        maskJavaCommentsAndLiterals(source).contains("PacketRelay.")

internal fun StructuralRefactorPass.hasResolvedNitrogenNetworkTypeReference(source: String, simpleName: String, fqn: String): Boolean {
    val code = maskJavaComments(source)
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(simpleName) && !executableCode.contains(fqn)) return false
    val packageName = packageNameOf(code)
    val imports = javaNonStaticImports(code)
    val wildcardImports = javaNonStaticWildcardImports(code)
    val referencePattern = Regex("""(?:${Regex.escape(fqn)}|(?<![.\w$])${Regex.escape(simpleName)}(?![\w$]))""")
    return referencePattern.findAll(executableCode).any { match ->
        resolveKnownJavaTypeReference(
            match.value,
            packageName,
            imports,
            wildcardImports,
            setOf(fqn)
        ) == fqn
    }
}

internal fun StructuralRefactorPass.sourceDeclaredNitrogenBasePacketTypeNames(
    source: String,
    packageName: String,
    imports: Map<String, String>,
    wildcardImports: Set<String>
): Set<String> =
    Regex("""\b(?:class|record)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .mapNotNull { match ->
            val typeName = match.groupValues[1]
            val typeStart = findTypeDeclarationStart(source, typeName) ?: return@mapNotNull null
            val openBrace = source.indexOf('{', typeStart)
            if (openBrace < 0) return@mapNotNull null
            val header = source.substring(typeStart, openBrace)
            if (typeHeaderImplementsNitrogenBasePacket(header, packageName, imports, wildcardImports)) {
                typeName
            } else {
                null
            }
        }
        .toSet()

internal fun StructuralRefactorPass.typeHeaderDeclaresOrInheritsNitrogenBasePacket(
    header: String,
    packageName: String,
    imports: Map<String, String>,
    wildcardImports: Set<String>,
    sourceDeclaredBasePacketTypes: Set<String>
): Boolean =
    typeHeaderImplementsNitrogenBasePacket(header, packageName, imports, wildcardImports) ||
        typeHeaderExtendsSimpleName(header)?.let { it in sourceDeclaredBasePacketTypes } == true

internal fun StructuralRefactorPass.typeHeaderImplementsNitrogenBasePacket(
    header: String,
    packageName: String,
    imports: Map<String, String>,
    wildcardImports: Set<String>
): Boolean {
    val implementedTypes = Regex("""\bimplements\s+(.+)$""")
        .find(header)
        ?.groupValues
        ?.get(1)
        ?: return false
    return splitTopLevelJavaArgs(implementedTypes).any { implemented ->
        resolveKnownJavaTypeReference(
            implemented,
            packageName,
            imports,
            wildcardImports,
            setOf("com.aetherteam.nitrogen.network.BasePacket")
        ) == "com.aetherteam.nitrogen.network.BasePacket"
    }
}

internal fun StructuralRefactorPass.typeHeaderExtendsSimpleName(header: String): String? =
    Regex("""\bextends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""")
        .find(header.substringBefore(" implements "))
        ?.groupValues
        ?.get(1)
        ?.substringAfterLast('.')

internal fun StructuralRefactorPass.rewriteJavaInvocation(source: String, qualifiedName: String, replacement: (List<String>) -> String?): String {
    var result = source
    var cursor = 0
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val index = executableCode.indexOf("$qualifiedName(", cursor)
        if (index < 0) break
        val openParen = index + qualifiedName.length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen <= openParen) {
            cursor = index + qualifiedName.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val call = replacement(args)
        if (call == null) {
            cursor = closeParen + 1
            continue
        }
        result = result.substring(0, index) + call + result.substring(closeParen + 1)
        cursor = index + call.length
    }
    return result
}

internal fun StructuralRefactorPass.removeJavaStatementsMatching(source: String, predicate: (String) -> Boolean): String {
    val separator = if (source.contains("\r\n")) "\r\n" else "\n"
    val lines = source.lines()
    val kept = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val startLine = lines[i]
        val mightRemove = startLine.contains("NetworkRegistry.newSimpleChannel") ||
            startLine.contains("NetworkRegistry.ChannelBuilder") ||
            startLine.contains(".registerMessage(") ||
            startLine.contains(".messageBuilder(")
        if (!mightRemove) {
            kept.add(startLine)
            i++
            continue
        }

        var end = i
        val statementLines = mutableListOf(startLine)
        while (!statementLines.joinToString("\n").trimEnd().endsWith(";") && end + 1 < lines.size) {
            end++
            statementLines.add(lines[end])
        }
        val statement = statementLines.joinToString("\n")
        if (predicate(statement)) {
            i = end + 1
        } else {
            kept.add(startLine)
            i++
        }
    }
    return kept.joinToString(separator)
}

/**
 * Detect mod ID from @Mod annotation.
 */
internal fun StructuralRefactorPass.detectModId(projectDir: Path): String? {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return null
    val candidates = linkedSetOf<String>()
    try {
        val javaFiles = Files.walk(srcDir).filter { it.toString().endsWith(".java") }.toList()
        for (file in javaFiles) {
            val text = file.readText()
            candidates.addAll(detectModIdsFromText(text))
        }
    } catch (_: Exception) {}
    return candidates.singleOrNull()
}

/**
 * Find the main @Mod-annotated class file.
 */
internal fun StructuralRefactorPass.detectModMainClass(projectDir: Path): Path? {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return null
    val candidates = mutableListOf<Path>()
    try {
        val javaFiles = Files.walk(srcDir).filter { it.toString().endsWith(".java") }.toList()
        for (file in javaFiles) {
            val text = file.readText()
            if (Regex("""@Mod\s*\(""").containsMatchIn(maskJavaCommentsAndLiterals(text))) {
                candidates.add(file)
            }
        }
    } catch (_: Exception) {}
    return candidates.singleOrNull()
}

internal fun StructuralRefactorPass.migrateMissingMappingsAliases(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir).filter { it.toString().endsWith(".java") }.toList()
    val registryFields = collectDeferredRegisterFields(javaFiles)
    if (registryFields.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    for (file in javaFiles) {
        val original = file.readText()
        if (!original.contains("MissingMappingsEvent") || !original.contains(".remap(")) continue
        val modAccess = inferModAccess(original) ?: continue
        val rules = extractMissingMappingAliasRules(original)
        if (rules.isEmpty()) continue
        val entries = expandMissingMappingAliasRules(rules, registryFields)
        if (entries.isEmpty()) continue

        val packageName = packageNameOf(original)
        val className = classNameOfJavaSource(original) ?: continue
        val classIsPublic = javaTopLevelTypeIsPublic(original, className)
        var migrated = missingMappingAliasClassSource(packageName, className, classIsPublic, modAccess, registryFields, entries)
        if (migrated != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate MissingMappingsEvent remaps to DeferredRegister aliases",
                before = "MissingMappingsEvent + mapping.remap(...)",
                after = "DeferredRegister#addAlias",
                confidence = Confidence.HIGH,
                ruleId = "struct-missing-mappings-add-alias"
            ))
            if (!dryRun) file.writeText(migrated)
        }

        val mainClass = detectModMainClass(projectDir)
        if (mainClass != null) {
            val mainOriginal = mainClass.readText()
            val remapperFqn = if (packageName.isBlank()) className else "$packageName.$className"
            val mainMigrated = addMissingMappingAliasCall(mainOriginal, remapperFqn, className)
            if (mainMigrated != mainOriginal) {
                changes.add(Change(
                    file = mainClass,
                    line = 0,
                    description = "Call migrated registry alias setup during mod construction",
                    before = "MissingMappingsEvent subscriber",
                    after = "$className.addRegistryAliases()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-missing-mappings-alias-call"
                ))
                if (!dryRun) mainClass.writeText(mainMigrated)
            }
        }
    }
    return changes
}

internal fun StructuralRefactorPass.collectDeferredRegisterFields(javaFiles: List<Path>): Map<String, RegistryFieldInfo> {
    val fields = mutableMapOf<String, RegistryFieldInfo>()
    val fieldPattern = Regex("""DeferredRegister\s*<\s*([^;=]+?)\s*>\s+([A-Z0-9_]+)\s*=""")
    for (file in javaFiles) {
        val source = file.readText()
        if (!source.contains("DeferredRegister<")) continue
        val packageName = packageNameOf(source)
        val className = classNameOfJavaSource(source) ?: continue
        val qualifiedClassName = if (packageName.isBlank()) className else "$packageName.$className"
        val fieldMatches = fieldPattern.findAll(source).toList()
        val helperRegisteredNames = if (fieldMatches.size == 1) {
            Regex("""\bregister[A-Za-z0-9_]*\(\s*"([^"]+)"""")
                .findAll(source)
                .map { it.groupValues[1] }
                .toSet()
        } else {
            emptySet()
        }
        fieldMatches.forEach { match ->
            val type = match.groupValues[1]
            val kind = when {
                Regex("""\bEntityType\b""").containsMatchIn(type) -> "entity"
                Regex("""\bBlock\b""").containsMatchIn(type) -> "block"
                Regex("""\bItem\b""").containsMatchIn(type) -> "item"
                else -> return@forEach
            }
            val fieldName = match.groupValues[2]
            val directRegisteredNames = Regex("""\b${Regex.escape(fieldName)}\.register\(\s*"([^"]+)"""")
                .findAll(source)
                .map { it.groupValues[1] }
                .toSet()
            val registeredNames = directRegisteredNames + helperRegisteredNames
            val info = RegistryFieldInfo(kind, className, qualifiedClassName, fieldName, registeredNames)
            val existing = fields[kind]
            if (existing == null || info.registeredNames.size > existing.registeredNames.size) {
                fields[kind] = info
            }
        }
    }
    return fields
}

internal fun StructuralRefactorPass.removeDataDrivenEnchantmentDeferredRegisterCalls(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val dataDrivenOwners = javaFiles.mapNotNull { file ->
        val source = file.readText()
        val className = classNameOfJavaSource(source) ?: return@mapNotNull null
        val hasResourceKeyList = source.contains("ResourceKey<Enchantment>") &&
            Regex("""\bENCHANTMENTS\s*=\s*java\.util\.List\.of\(""").containsMatchIn(source)
        val hasLegacyDeferredRegister = Regex("""\bDeferredRegister\s*<\s*Enchantment\s*>\s+ENCHANTMENT\b""")
            .containsMatchIn(source) ||
            Regex("""\bENCHANTMENT\s*=\s*DeferredRegister\.create\(""")
                .containsMatchIn(source)
        if (hasResourceKeyList && !hasLegacyDeferredRegister) className else null
    }.toSet()
    if (dataDrivenOwners.isEmpty()) return emptyList()
    val changes = mutableListOf<Change>()
    val ownerAlternation = dataDrivenOwners.joinToString("|") { Regex.escape(it) }
    val registerLine = Regex(
        """(?m)^[ \t]*(?:$ownerAlternation)\.ENCHANTMENT\.register\(\s*[A-Za-z_$][\w$]*\s*\)\s*;\s*\r?\n?"""
    )
    for (javaFile in javaFiles) {
        val original = javaFile.readText()
        if (!registerLine.containsMatchIn(original)) continue
        val modified = registerLine.replace(original, "")
        if (modified == original) continue
        if (!dryRun) javaFile.writeText(modified)
        changes.add(Change(
            file = javaFile,
            line = 1,
            description = "Remove obsolete DeferredRegister<Enchantment> bus registration after data-driven ResourceKey migration",
            before = ".ENCHANTMENT.register(modEventBus)",
            after = "data-driven enchantments are declared through ResourceKey list",
            confidence = Confidence.HIGH,
            ruleId = "struct-data-driven-enchantment-register-cleanup"
        ))
    }
    return changes
}

internal fun StructuralRefactorPass.extractMissingMappingAliasRules(source: String): List<RegistryAliasRule> {
    val helperKinds = mutableMapOf<String, String>()
    Regex("""\b(?:private|public|protected)?\s*static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*MissingMappingsEvent\.Mapping\s*<\s*Block\s*>\s+""")
        .findAll(source)
        .forEach { helperKinds[it.groupValues[1]] = "block" }
    Regex("""\b(?:private|public|protected)?\s*static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*MissingMappingsEvent\.Mapping\s*<\s*Item\s*>\s+""")
        .findAll(source)
        .forEach { helperKinds[it.groupValues[1]] = "item" }
    Regex("""\b(?:private|public|protected)?\s*static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*MissingMappingsEvent\.Mapping\s*<\s*EntityType<\?>\s*>\s+""")
        .findAll(source)
        .forEach { helperKinds[it.groupValues[1]] = "entity" }
    if (helperKinds.isEmpty()) return emptyList()

    return helperKinds.flatMap { (helper, kind) ->
        Regex("""\b${Regex.escape(helper)}\(\s*[A-Za-z_$][\w$]*\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
            .findAll(source)
            .map { RegistryAliasRule(kind, it.groupValues[1], it.groupValues[2]) }
            .toList()
    }
}

internal fun StructuralRefactorPass.expandMissingMappingAliasRules(
    rules: List<RegistryAliasRule>,
    registryFields: Map<String, RegistryFieldInfo>
): List<RegistryAliasEntry> {
    val entries = linkedSetOf<RegistryAliasEntry>()
    for (rule in rules) {
        val field = registryFields[rule.kind] ?: continue
        val expanded = field.registeredNames
            .filter { it.contains(rule.newPattern) }
            .map { newId -> newId.replace(rule.newPattern, rule.oldPattern) to newId }
            .filter { (oldId, newId) -> oldId != newId }
        val aliases = expanded.ifEmpty { listOf(rule.oldPattern to rule.newPattern) }
        aliases.forEach { (oldId, newId) ->
            entries.add(RegistryAliasEntry(field.ref, oldId, newId))
        }
    }
    return entries.toList()
}

internal fun StructuralRefactorPass.missingMappingAliasClassSource(
    packageName: String,
    className: String,
    classIsPublic: Boolean,
    modAccess: ModAccess,
    registryFields: Map<String, RegistryFieldInfo>,
    entries: List<RegistryAliasEntry>
): String {
    val imports = linkedSetOf(
        "net.minecraft.resources.ResourceLocation",
        "net.neoforged.neoforge.registries.DeferredRegister"
    )
    modAccess.modImport?.let { imports.add(it) }
    entries.map { it.registryRef.substringBefore('.') }
        .toSet()
        .forEach { simpleClass ->
            registryFields.values.firstOrNull { it.className == simpleClass }?.qualifiedClassName?.let(imports::add)
        }

    val grouped = entries.groupBy { it.oldId to it.newId }
    val lines = grouped.entries.map { (key, group) ->
        val refs = group.map { it.registryRef }.distinct()
        val oldId = key.first
        val newId = key.second
        if (refs.size == 1) {
            """		remapEntry(${refs.first()}, "$oldId", "$newId");"""
        } else {
            """		remapEntryFromRegistries("$oldId", "$newId", ${refs.joinToString(", ")});"""
        }
    }

    val packageLine = if (packageName.isBlank()) "" else "package $packageName;\n\n"
    return buildString {
        append(packageLine)
        imports.sorted().forEach { append("import $it;\n") }
        append("\n")
        append("${if (classIsPublic) "public " else ""}class $className {\n\n")
        append("\tpublic static void addRegistryAliases() {\n")
        lines.forEach { append(it).append("\n") }
        append("\t}\n\n")
        append("\tprivate static void remapEntry(DeferredRegister<?> registry, String oldId, String newId) {\n")
        append("\t\tregistry.addAlias(prefix(oldId), prefix(newId));\n")
        append("\t}\n\n")
        append("\tprivate static void remapEntryFromRegistries(String oldId, String newId, DeferredRegister<?>... registries) {\n")
        append("\t\tfor (DeferredRegister<?> registry : registries) {\n")
        append("\t\t\tregistry.addAlias(prefix(oldId), prefix(newId));\n")
        append("\t\t}\n")
        append("\t}\n\n")
        append("\tprivate static ResourceLocation prefix(String id) {\n")
        append("\t\treturn ResourceLocation.fromNamespaceAndPath(${modAccess.modIdExpression}, id);\n")
        append("\t}\n")
        append("}\n")
    }
}

internal fun StructuralRefactorPass.addMissingMappingAliasCall(source: String, remapperFqn: String, remapperSimpleName: String): String {
    if (source.contains("$remapperSimpleName.addRegistryAliases()")) return source
    var result = source
    val packageName = packageNameOf(source)
    if (remapperFqn.contains('.') && remapperFqn.substringBeforeLast('.') != packageName) {
        result = addImportIfMissing(result, remapperFqn)
    }
    val lines = result.lines().toMutableList()
    val insertIndex = lines.indexOfLast { Regex("""\.register\(\s*[A-Za-z_$][\w$]*\s*\)\s*;""").containsMatchIn(it) }
    if (insertIndex < 0) return result
    val indent = lines[insertIndex].takeWhile { it == ' ' || it == '\t' }
    lines.add(insertIndex + 1, "$indent$remapperSimpleName.addRegistryAliases();")
    return lines.joinToString(if (result.contains("\r\n")) "\r\n" else "\n")
}

/**
 * Migrate the common Forge item capability read pattern:
 *
 * Optional<T> maybe = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
 * if (maybe.isEmpty()) return ...
 * T handler = maybe.get();
 *
 * In NeoForge 1.21, ItemStack#getCapability returns nullable T directly.
 */
internal fun StructuralRefactorPass.migrateItemStackCapabilityOptionals(
    source: String,
    cu: CompilationUnit,
    file: Path,
    changes: MutableList<Change>
): String {
    val itemStackNames = findItemStackNames(cu)
    if (itemStackNames.isEmpty() || !source.contains("getCapability")) return source

    var result = source
    var changed = false

    for (name in itemStackNames) {
        val itemHandlerRegex = Regex(
            """\b${Regex.escape(name)}\.getCapability\(\s*Capabilities\.(?:ITEM_HANDLER|ItemHandler\.BLOCK)(?:\s*,\s*[^)\r\n]+)?\s*\)"""
        )
        result = itemHandlerRegex.replace(result) { match ->
            changed = true
            val line = lineNumber(source, match.range.first)
            changes.add(Change(
                file = file,
                line = line,
                description = "ItemStack ForgeCapabilities.ITEM_HANDLER -> Capabilities.ItemHandler.ITEM",
                before = match.value,
                after = "$name.getCapability(Capabilities.ItemHandler.ITEM)",
                confidence = Confidence.HIGH,
                ruleId = "struct-itemstack-item-handler-capability"
            ))
            "$name.getCapability(Capabilities.ItemHandler.ITEM)"
        }
    }

    val migratedOptionalNames = mutableSetOf<String>()
    val optionalResolveRegex = Regex(
        """(?m)^([ \t]*)Optional<([^>\r\n]+)>\s+(\w+)\s*=\s*([^;\r\n]+?\.getCapability\([^;\r\n]+?\))\.resolve\(\);\s*$"""
    )
    result = optionalResolveRegex.replace(result) { match ->
        val indent = match.groupValues[1]
        val type = match.groupValues[2].trim()
        val varName = match.groupValues[3]
        val getter = match.groupValues[4].trim()
        if (itemStackNames.none { getter.startsWith("$it.") }) {
            return@replace match.value
        }
        migratedOptionalNames.add(varName)
        changed = true
        val replacement = "$indent$type $varName = $getter;"
        changes.add(Change(
            file = file,
            line = lineNumber(result, match.range.first),
            description = "Capability Optional.resolve() -> nullable capability local",
            before = match.value.trim(),
            after = replacement.trim(),
            confidence = Confidence.HIGH,
            ruleId = "struct-capability-resolve-nullable"
        ))
        replacement
    }

    val directResolveOrThrowRegex = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$]*(?:\s*<[^;\r\n=]+>)?)\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]+?\.getCapability\([^;\r\n]+?\))\.resolve\(\)\.orElseThrow\(\)\s*;\s*$"""
    )
    result = directResolveOrThrowRegex.replace(result) { match ->
        val getter = match.groupValues[4].trim()
        if (itemStackNames.none { getter.startsWith("$it.") }) {
            match.value
        } else {
            changed = true
            val replacement = "${match.groupValues[1]}${match.groupValues[2].trim()} ${match.groupValues[3]} = $getter;"
            changes.add(Change(
                file = file,
                line = lineNumber(result, match.range.first),
                description = "ItemStack capability resolve().orElseThrow() -> direct nullable capability result",
                before = match.value.trim(),
                after = replacement.trim(),
                confidence = Confidence.HIGH,
                ruleId = "struct-itemstack-capability-resolve-or-throw"
            ))
            replacement
        }
    }

    val directResolveGetRegex = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$]*(?:\s*<[^;\r\n=]+>)?)\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]+?\.getCapability\([^;\r\n]+?\))\.resolve\(\)\.get\(\)\s*;\s*$"""
    )
    result = directResolveGetRegex.replace(result) { match ->
        val getter = match.groupValues[4].trim()
        if (itemStackNames.none { getter.startsWith("$it.") }) {
            match.value
        } else {
            changed = true
            val replacement = "${match.groupValues[1]}${match.groupValues[2].trim()} ${match.groupValues[3]} = $getter;"
            changes.add(Change(
                file = file,
                line = lineNumber(result, match.range.first),
                description = "ItemStack capability resolve().get() -> direct nullable capability result",
                before = match.value.trim(),
                after = replacement.trim(),
                confidence = Confidence.HIGH,
                ruleId = "struct-itemstack-capability-resolve-get"
            ))
            replacement
        }
    }

    for (varName in migratedOptionalNames) {
        result = Regex("""\b${Regex.escape(varName)}\.isEmpty\(\)""").replace(result) { match ->
            changed = true
            changes.add(Change(
                file = file,
                line = lineNumber(result, match.range.first),
                description = "Capability Optional.isEmpty() -> null check",
                before = match.value,
                after = "$varName == null",
                confidence = Confidence.HIGH,
                ruleId = "struct-capability-optional-empty-null"
            ))
            "$varName == null"
        }
        result = Regex("""\b${Regex.escape(varName)}\.isPresent\(\)""").replace(result) { match ->
            changed = true
            changes.add(Change(
                file = file,
                line = lineNumber(result, match.range.first),
                description = "Capability Optional.isPresent() -> null check",
                before = match.value,
                after = "$varName != null",
                confidence = Confidence.HIGH,
                ruleId = "struct-capability-optional-present-null"
            ))
            "$varName != null"
        }
        result = Regex("""\b${Regex.escape(varName)}\.get\(\)""").replace(result) { match ->
            changed = true
            changes.add(Change(
                file = file,
                line = lineNumber(result, match.range.first),
                description = "Capability Optional.get() -> nullable local",
                before = match.value,
                after = varName,
                confidence = Confidence.HIGH,
                ruleId = "struct-capability-optional-get-local"
            ))
            varName
        }
    }

    val directNegatedIsPresentRegex = Regex("""!\s*(\b\w+\.getCapability\([^;\r\n]+?\))\.isPresent\(\)""")
    result = directNegatedIsPresentRegex.replace(result) { match ->
        val getter = match.groupValues[1]
        if (itemStackNames.none { getter.startsWith("$it.") }) {
            match.value
        } else {
            changed = true
            val replacement = "$getter == null"
            changes.add(Change(
                file = file,
                line = lineNumber(result, match.range.first),
                description = "Negated ItemStack capability isPresent() -> null check",
                before = match.value,
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "struct-itemstack-capability-not-present-null"
            ))
            replacement
        }
    }

    val directIsPresentRegex = Regex("""(\b\w+\.getCapability\([^;\r\n]+?\))\.isPresent\(\)""")
    result = directIsPresentRegex.replace(result) { match ->
        val getter = match.groupValues[1]
        if (itemStackNames.none { getter.startsWith("$it.") }) {
            match.value
        } else {
            changed = true
            val replacement = "$getter != null"
            changes.add(Change(
                file = file,
                line = lineNumber(result, match.range.first),
                description = "ItemStack capability isPresent() -> null check",
                before = match.value,
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "struct-itemstack-capability-present-null"
            ))
            replacement
        }
    }

    if (changed && result.contains("import java.util.Optional;") &&
        !Regex("""\bOptional(?:[.<])""").containsMatchIn(result.replace("import java.util.Optional;", ""))
    ) {
        result = result.replace(Regex("""import java\.util\.Optional;\r?\n"""), "")
        changes.add(Change(
            file = file,
            line = 0,
            description = "Remove stale Optional import after capability nullable migration",
            before = "import java.util.Optional;",
            after = "",
            confidence = Confidence.HIGH,
            ruleId = "struct-capability-remove-optional-import"
        ))
    }

    return result
}

internal fun StructuralRefactorPass.findItemStackNames(cu: CompilationUnit): Set<String> {
    val names = mutableSetOf<String>()
    cu.findAll(Parameter::class.java).forEach { parameter ->
        if (parameter.type.asString() == "ItemStack") {
            names.add(parameter.nameAsString)
        }
    }
    cu.findAll(VariableDeclarator::class.java).forEach { variable ->
        if (variable.type.asString() == "ItemStack") {
            names.add(variable.nameAsString)
        }
    }
    return names
}

internal fun StructuralRefactorPass.lineNumber(text: String, offset: Int): Int =
    text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

/**
 * Detect LazyOptional usage and flag for null-based replacement.
 */
internal fun StructuralRefactorPass.detectLazyOptionalUsage(
    cu: CompilationUnit,
    file: Path,
    changes: MutableList<Change>
) {
    // Detect LazyOptional type usage
    cu.findAll(ClassOrInterfaceType::class.java).forEach { type ->
        if (type.nameAsString == "LazyOptional") {
            val line = type.begin.map { it.line }.orElse(0)
            val valueType = type.typeArguments
                .map { args -> args.firstOrNull()?.toString() ?: "T" }
                .orElse("T")
            changes.add(Change(
                file = file, line = line,
                description = "LazyOptional<T> -> @Nullable T (capabilities no longer use LazyOptional)",
                before = type.toString(),
                after = "@Nullable $valueType",
                confidence = Confidence.HIGH,
                ruleId = "struct-lazy-optional"
            ))
        }
    }

    // Detect .ifPresent() / .orElse() on LazyOptional
    cu.findAll(MethodCallExpr::class.java).forEach { call ->
        if (call.nameAsString == "ifPresent" || call.nameAsString == "orElse" ||
            call.nameAsString == "orElseThrow" || call.nameAsString == "resolve") {
            // Check if scope contains capability-related expressions
            val scopeStr = call.scope.map { it.toString() }.orElse("")
            if (scopeStr.contains("getCapability") || scopeStr.contains("LazyOptional") ||
                scopeStr.contains("cap") || scopeStr.contains("handler")) {
                val line = call.begin.map { it.line }.orElse(0)
                changes.add(Change(
                    file = file, line = line,
                    description = "LazyOptional.${call.nameAsString}() -> null check pattern",
                    before = call.toString(),
                    after = "// Replace with: var result = ...; if (result != null) { ... }",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-lazy-optional-method"
                ))
            }
        }
    }
}

internal val modBusEventTypeOwners = setOf(
    "net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent",
    "net.minecraftforge.event.entity.EntityAttributeCreationEvent",
    "net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent",
    "net.minecraftforge.event.BuildCreativeModeTabContentsEvent",
    "net.neoforged.neoforge.client.event.EntityRenderersEvent",
    "net.minecraftforge.client.event.EntityRenderersEvent",
    "net.neoforged.neoforge.registries.RegisterEvent",
    "net.minecraftforge.registries.RegisterEvent",
    "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent",
    "net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent",
    "net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent",
    "net.minecraftforge.event.entity.SpawnPlacementRegisterEvent",
    "net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent",
    "net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent",
    "net.neoforged.fml.event.lifecycle.FMLClientSetupEvent",
    "net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent",
    "net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent",
    "net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent",
    "net.neoforged.neoforge.data.event.GatherDataEvent",
    "net.minecraftforge.data.event.GatherDataEvent",
    "net.neoforged.neoforge.client.event.RegisterColorHandlersEvent",
    "net.minecraftforge.client.event.RegisterColorHandlersEvent",
    "net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent",
    "net.minecraftforge.client.event.RegisterParticleProvidersEvent",
    "net.neoforged.neoforge.client.event.ModelEvent",
    "net.minecraftforge.client.event.ModelEvent",
    "net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent",
    "net.minecraftforge.client.event.TextureAtlasStitchedEvent",
    "net.neoforged.neoforge.client.event.RegisterShadersEvent",
    "net.minecraftforge.client.event.RegisterShadersEvent",
    "net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent",
    "net.minecraftforge.client.event.RegisterKeyMappingsEvent",
    "net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent",
    "net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent",
    "net.neoforged.fml.event.lifecycle.InterModEnqueueEvent",
    "net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent",
    "net.neoforged.fml.event.lifecycle.InterModProcessEvent",
    "net.minecraftforge.fml.event.lifecycle.InterModProcessEvent",
    "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent",
    "net.neoforged.neoforge.event.RegisterGameTestsEvent",
    "net.minecraftforge.event.RegisterGameTestsEvent",
    "net.neoforged.neoforge.registries.DataPackRegistryEvent",
    "net.minecraftforge.registries.DataPackRegistryEvent",
    "net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent",
    "net.minecraftforge.client.event.RegisterClientReloadListenersEvent",
    "net.neoforged.neoforge.client.event.RegisterGuiLayersEvent",
    "net.minecraftforge.client.event.RegisterGuiOverlaysEvent",
    "net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent",
    "net.minecraftforge.client.event.RegisterItemDecorationsEvent",
    "net.neoforged.fml.event.config.ModConfigEvent",
    "net.minecraftforge.fml.event.config.ModConfigEvent"
)

internal val clientOnlyModBusEventTypeOwners = setOf(
    "net.neoforged.neoforge.client.event.EntityRenderersEvent",
    "net.minecraftforge.client.event.EntityRenderersEvent",
    "net.neoforged.neoforge.client.event.RegisterColorHandlersEvent",
    "net.minecraftforge.client.event.RegisterColorHandlersEvent",
    "net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent",
    "net.minecraftforge.client.event.RegisterParticleProvidersEvent",
    "net.neoforged.neoforge.client.event.ModelEvent",
    "net.minecraftforge.client.event.ModelEvent",
    "net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent",
    "net.minecraftforge.client.event.TextureAtlasStitchedEvent",
    "net.neoforged.neoforge.client.event.RegisterShadersEvent",
    "net.minecraftforge.client.event.RegisterShadersEvent",
    "net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent",
    "net.minecraftforge.client.event.RegisterKeyMappingsEvent",
    "net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent",
    "net.minecraftforge.client.event.RegisterClientReloadListenersEvent",
    "net.neoforged.neoforge.client.event.RegisterGuiLayersEvent",
    "net.minecraftforge.client.event.RegisterGuiOverlaysEvent",
    "net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent",
    "net.minecraftforge.client.event.RegisterItemDecorationsEvent"
)

/**
 * Remove NeoForge.EVENT_BUS.register(this) from @Mod classes that have no @SubscribeEvent methods.
 * In NeoForge 1.21.1, registering a class without @SubscribeEvent methods throws an IllegalArgumentException.
 */
internal fun StructuralRefactorPass.removeEmptyEventBusRegistration(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val text = javaFile.toFile().readText()
            val code = maskJavaComments(text)
            val executableCode = maskJavaCommentsAndLiterals(text)
            // Only process @Mod-annotated classes
            if (!Regex("""@Mod(?:\s*\(|\s*\r?\n)""").containsMatchIn(executableCode)) return@forEach
            // Only process files with EVENT_BUS.register(this)
            if (!executableCode.contains("EVENT_BUS.register(this)")) return@forEach

            // Check if any @SubscribeEvent methods handle game-bus events
            // If ALL @SubscribeEvent methods handle mod-bus events, remove EVENT_BUS.register(this)
            val imports = javaNonStaticImports(code)
            val hasGameBusEvents = if (!executableCode.contains("@SubscribeEvent")) {
                false
            } else {
                // Find @SubscribeEvent method parameter types
                val subscribePattern = Regex(
                    """@SubscribeEvent\s+(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private)\s+)?(?:static\s+)?(?:final\s+)?void\s+\w+\s*\(\s*(?:final\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\b"""
                )
                val eventTypes = subscribePattern.findAll(executableCode).map { it.groupValues[1] }.toList()
                eventTypes.isEmpty() || eventTypes.any { eventType ->
                    !isKnownModBusEventParameterType(eventType, imports)
                }
            }

            if (hasGameBusEvents) return@forEach

            // Remove the EVENT_BUS.register(this) line
            val modified = text.replace(
                Regex("""^\s*(?:NeoForge|MinecraftForge)\.EVENT_BUS\.register\(this\);\s*\r?\n?""", RegexOption.MULTILINE),
                ""
            )

            if (modified != text) {
                if (!dryRun) {
                    javaFile.toFile().writeText(modified)
                }
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Remove EVENT_BUS.register(this) — all @SubscribeEvent methods are mod-bus events",
                    before = "NeoForge.EVENT_BUS.register(this);",
                    after = "(removed)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-remove-empty-eventbus-register"
                ))
            }
        }

    return changes
}

internal fun StructuralRefactorPass.isKnownModBusEventParameterType(rawType: String, imports: Map<String, String>): Boolean {
    val resolved = resolveJavaTypeReferenceFromImportsOrFqn(rawType, imports) ?: return false
    return modBusEventTypeOwners.any { owner -> resolved == owner || resolved.startsWith("$owner.") }
}

internal fun StructuralRefactorPass.isKnownClientOnlyModBusEventParameterType(rawType: String, imports: Map<String, String>): Boolean {
    val resolved = resolveJavaTypeReferenceFromImportsOrFqn(rawType, imports) ?: return false
    return clientOnlyModBusEventTypeOwners.any { owner -> resolved == owner || resolved.startsWith("$owner.") }
}

internal fun StructuralRefactorPass.resolveJavaTypeReferenceFromImportsOrFqn(rawType: String, imports: Map<String, String>): String? {
    val normalized = normalizeJavaTypeReference(rawType) ?: return null
    if (normalized.contains(".")) {
        val firstSegment = normalized.substringBefore('.')
        imports[firstSegment]?.let { imported -> return "$imported.${normalized.substringAfter('.')}" }
        return normalized
    }
    return imports[normalized]
}

/**
 * Migrate FMLJavaModLoadingContext constructor parameter to IEventBus + ModContainer.
 * In NeoForge 1.21.1, @Mod constructors receive IEventBus and ModContainer directly
 * instead of FMLJavaModLoadingContext.
 */
internal fun StructuralRefactorPass.migrateFMLJavaModLoadingContext(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            var text = javaFile.toFile().readText()
            var executableCode = maskJavaCommentsAndLiterals(text)
            if (!Regex("""@\s*Mod(?:\s*\(|\b)""").containsMatchIn(executableCode)) return@forEach
            if (!executableCode.contains("FMLJavaModLoadingContext")) return@forEach

            val original = text
            val className = Regex("""public\s+class\s+(\w+)""").find(executableCode)?.groupValues?.get(1)
            val staticFmlModEventBusPattern = Regex(
                """\bFMLJavaModLoadingContext\s*\.\s*get\s*\(\s*\)\s*\.\s*getModEventBus\s*\(\s*\)"""
            )

            // Replace constructor parameter: FMLJavaModLoadingContext <name> -> IEventBus modEventBus
            val ctorMatch = className?.let { name ->
                Regex("""public\s+${Regex.escape(name)}\s*\(([^)]*)\)""")
                    .findAll(executableCode)
                    .filter { it.groupValues[1].contains("FMLJavaModLoadingContext") }
                    .singleOrNull()
            }
            if (ctorMatch != null) {
                val concreteClassName = className
                val parameterListRange = ctorMatch.groups[1]?.range ?: return@forEach
                val ctorParamPattern = Regex("""FMLJavaModLoadingContext\s+(\w+)""")
                val ctorParamMatches = ctorParamPattern.findAll(executableCode, parameterListRange.first)
                    .takeWhile { it.range.last <= parameterListRange.last }
                    .toList()
                val ctorParamMatch = ctorParamMatches.singleOrNull() ?: return@forEach
                val contextVarName = ctorParamMatch.groupValues[1]
                val hadModContainerParameter = Regex("""\bModContainer\b""")
                    .containsMatchIn(executableCode.substring(parameterListRange))

                // Replace the parameter type
                text = text.replaceRange(ctorParamMatch.range, "IEventBus modEventBus")
                executableCode = maskJavaCommentsAndLiterals(text)
                val constructorPattern = Regex("""public\s+${Regex.escape(concreteClassName)}\s*\(([^)]*\bIEventBus\s+modEventBus[^)]*)\)""")
                val constructorMatch = constructorPattern.find(executableCode)
                if (constructorMatch != null && !hadModContainerParameter) {
                    val params = constructorMatch.groupValues[1].trim()
                    text = text.replaceRange(
                        constructorMatch.range,
                        "public $concreteClassName($params, ModContainer modContainer)"
                    )
                }

                // Replace context.getModEventBus() -> modEventBus
                text = replaceExecutableRegex(
                    text,
                    Regex("""\b${Regex.escape(contextVarName)}\s*\.\s*getModEventBus\s*\(\s*\)""")
                ) { "modEventBus" }

                // Replace context.registerConfig -> modContainer.registerConfig
                text = replaceExecutableRegex(
                    text,
                    Regex("""\b${Regex.escape(contextVarName)}\s*\.\s*registerConfig\s*\(""")
                ) { "modContainer.registerConfig(" }
                text = replaceExecutableRegex(
                    text,
                    Regex("""\bModLoadingContext\s*\.\s*get\s*\(\s*\)\s*\.\s*getActiveContainer\s*\(\s*\)\s*\.\s*registerConfig\s*\(""")
                ) { "modContainer.registerConfig(" }
                text = replaceExecutableRegex(
                    text,
                    Regex("""\bModLoadingContext\s*\.\s*get\s*\(\s*\)\s*\.\s*registerConfig\s*\(""")
                ) { "modContainer.registerConfig(" }

                // Replace IEventBus <varName> = context.getModEventBus() assignment with modEventBus
                val busAssignPattern = Regex("""IEventBus\s+(\w+)\s*=\s*modEventBus;\s*\r?\n""")
                executableCode = maskJavaCommentsAndLiterals(text)
                val busAssignMatch = busAssignPattern.find(executableCode)
                if (busAssignMatch != null) {
                    val busVarName = busAssignMatch.groupValues[1]
                    // Remove the redundant assignment and replace usages
                    text = text.removeRange(busAssignMatch.range)
                    text = replaceExecutableRegex(text, Regex("""\b${Regex.escape(busVarName)}\b""")) {
                        "modEventBus"
                    }
                }
                text = replaceExecutableRegex(
                    text,
                    Regex("""\s*IEventBus\s+modEventBus\s*=\s*this\.modEventBus;\s*\r?\n""")
                ) { "\n" }

                val delegatedCtorPattern = Regex(
                    """(?s)\s*(?:@SuppressWarnings\("removal"\)\s*)?public\s+${Regex.escape(concreteClassName)}\s*\(\s*(?:ModContainer\s+\w+)?\s*\)\s*\{\s*this\s*\(\s*FMLJavaModLoadingContext\.get\(\)\s*\)\s*;\s*\}\s*"""
                )
                text = replaceExecutableRegex(text, delegatedCtorPattern) { "\n" }

                // Remove FMLJavaModLoadingContext import
                text = removeExecutableImport(text, "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext")

                // Remove ModLoadingContext import if present and unused
                if (!maskJavaCommentsAndLiterals(text).contains("ModLoadingContext.")) {
                    text = removeExecutableImport(text, "net.neoforged.fml.ModLoadingContext")
                }

                // Ensure ModContainer import exists
                if (!maskJavaCommentsAndLiterals(text).contains("import net.neoforged.fml.ModContainer;")) {
                    text = addExecutableImportIfMissing(text, "net.neoforged.fml.ModContainer")
                }
            }

            executableCode = maskJavaCommentsAndLiterals(text)
            if (ctorMatch == null && executableCode.contains("modContainer")) {
                if (className != null && staticFmlModEventBusPattern.containsMatchIn(executableCode)) {
                    val constructorPattern = Regex(
                        """public\s+${Regex.escape(className)}\s*\(([^)]*)\)"""
                    )
                    val constructorMatch = constructorPattern.find(executableCode)
                    if (constructorMatch != null) {
                        val params = constructorMatch.groupValues[1].trim()
                        val replacement = if (Regex("""\bIEventBus\b""").containsMatchIn(params)) {
                            constructorMatch.value
                        } else {
                            val newParams = if (params.isBlank()) {
                                "IEventBus modEventBus, ModContainer modContainer"
                            } else {
                                "IEventBus modEventBus, $params"
                            }
                            "public $className($newParams)"
                        }
                        text = text.replaceRange(constructorMatch.range, replacement)
                    }
                }
                // Constructor injection is visible only in that executable. A static
                // initializer helper in the same @Mod class must retain its getter
                // until the helper/owner-container migration handles it.
                val eventBusSource = text
                text = replaceExecutableRegex(
                    text,
                    staticFmlModEventBusPattern
                ) { match ->
                    constructorEventBusParameterExpressionAt(
                        eventBusSource,
                        match.range.first,
                        className
                    ) ?: match.value
                }
                text = replaceExecutableRegex(
                    text,
                    Regex("""\bModLoadingContext\s*\.\s*get\s*\(\s*\)\s*\.\s*getActiveContainer\s*\(\s*\)\s*\.\s*registerConfig\s*\(""")
                ) { "modContainer.registerConfig(" }
                text = replaceExecutableRegex(
                    text,
                    Regex("""\bModLoadingContext\s*\.\s*get\s*\(\s*\)\s*\.\s*registerConfig\s*\(""")
                ) { "modContainer.registerConfig(" }

                text = removeExecutableImport(text, "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext")
                if (!maskJavaCommentsAndLiterals(text).contains("ModLoadingContext.")) {
                    text = removeExecutableImport(text, "net.neoforged.fml.ModLoadingContext")
                }
            }

            executableCode = maskJavaCommentsAndLiterals(text)
            if (executableCode.contains("ModContainer modContainer") &&
                !executableCode.contains("import net.neoforged.fml.ModContainer;")) {
                text = addExecutableImportIfMissing(text, "net.neoforged.fml.ModContainer")
            }
            executableCode = maskJavaCommentsAndLiterals(text)
            if (executableCode.contains("IEventBus modEventBus") &&
                !executableCode.contains("import net.neoforged.bus.api.IEventBus;")) {
                text = addExecutableImportIfMissing(text, "net.neoforged.bus.api.IEventBus")
            }

            if (text != original) {
                if (!dryRun) {
                    javaFile.toFile().writeText(text)
                }
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate FMLJavaModLoadingContext to IEventBus + ModContainer constructor",
                    before = "FMLJavaModLoadingContext context",
                    after = "IEventBus modEventBus, ModContainer modContainer",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-fml-context-to-eventbus"
                ))
            }
        }

    return changes
}

internal data class StaticFmlModBusHelper(
    val file: Path,
    val ownerClass: String,
    val methodName: String,
    val busName: String,
    val headerPrefix: String,
    val headerRange: IntRange,
    val assignmentRange: IntRange
)

internal fun StructuralRefactorPass.migrateStaticFmlModEventBusHelperInitializers(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    val staticGetter = Regex("""\bFMLJavaModLoadingContext\s*\.\s*get\s*\(\s*\)\s*\.\s*getModEventBus\s*\(\s*\)""")
    val helperMethods = mutableListOf<StaticFmlModBusHelper>()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()

    for (javaFile in javaFiles) {
        val original = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(original)
        if (!staticGetter.containsMatchIn(executableCode)) continue
        val className = classNameOfJavaSource(original) ?: continue
        val methodHeader = Regex(
            """(?m)^([ \t]*(?:public|protected|private)\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*)\(\s*\)\s*\{"""
        )
        for (match in methodHeader.findAll(executableCode)) {
            val openBrace = executableCode.indexOf('{', match.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
            if (openBrace < 0 || closeBrace < 0) continue
            val body = executableCode.substring(openBrace + 1, closeBrace)
            val assignment = Regex(
                """(?m)^[ \t]*(?:net\.neoforged\.bus\.api\.)?IEventBus\s+([A-Za-z_$][\w$]*)\s*=\s*FMLJavaModLoadingContext\s*\.\s*get\s*\(\s*\)\s*\.\s*getModEventBus\s*\(\s*\)\s*;\s*\r?\n"""
            ).find(body) ?: continue
            val busName = assignment.groupValues[1]
            helperMethods += StaticFmlModBusHelper(
                file = javaFile,
                ownerClass = className,
                methodName = match.groupValues[2],
                busName = busName,
                headerPrefix = match.groupValues[1],
                headerRange = match.range,
                assignmentRange = (openBrace + 1 + assignment.range.first)..(openBrace + 1 + assignment.range.last)
            )
        }
    }

    if (helperMethods.isEmpty()) return changes
    val acceptedHelpers = mutableListOf<StaticFmlModBusHelper>()
    val callEditsByFile = mutableMapOf<Path, MutableList<Pair<IntRange, String>>>()
    for (helper in helperMethods) {
        val callPattern = Regex("""\b${Regex.escape(helper.ownerClass)}\.${Regex.escape(helper.methodName)}\s*\(\s*\)""")
        val sameOwnerCallPattern = Regex("""(?<![\w$.])${Regex.escape(helper.methodName)}\s*\(\s*\)""")
        var callCount = 0
        var unresolvedCallSite = false
        val helperCallEdits = mutableMapOf<Path, MutableList<Pair<IntRange, String>>>()
        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            val executableCode = maskJavaCommentsAndLiterals(original)
            for (match in callPattern.findAll(executableCode)) {
                callCount += 1
                val eventBus = eventBusParameterExpressionAt(original, match.range.first)
                if (eventBus == null) {
                    unresolvedCallSite = true
                } else {
                    helperCallEdits.getOrPut(javaFile) { mutableListOf() } +=
                        match.range to "${helper.ownerClass}.${helper.methodName}($eventBus)"
                }
            }
            if (javaFile == helper.file) {
                for (match in sameOwnerCallPattern.findAll(executableCode)) {
                    if (match.range.first in helper.headerRange) continue
                    callCount += 1
                    val eventBus = eventBusParameterExpressionAt(original, match.range.first)
                    if (eventBus == null) {
                        unresolvedCallSite = true
                    } else {
                        helperCallEdits.getOrPut(javaFile) { mutableListOf() } +=
                            match.range to "${helper.methodName}($eventBus)"
                    }
                }
            }
        }
        if (callCount > 0 && !unresolvedCallSite) {
            acceptedHelpers += helper
            for ((file, edits) in helperCallEdits) {
                callEditsByFile.getOrPut(file) { mutableListOf() }.addAll(edits)
            }
        }
    }
    if (acceptedHelpers.isEmpty()) return changes

    val allEditsByFile = mutableMapOf<Path, MutableList<Pair<IntRange, String>>>()
    val helperFiles = acceptedHelpers.map { it.file }.toSet()
    val callsiteFiles = callEditsByFile.keys
    for (helper in acceptedHelpers) {
        allEditsByFile.getOrPut(helper.file) { mutableListOf() } +=
            helper.headerRange to "${helper.headerPrefix}(IEventBus ${helper.busName}) {"
        allEditsByFile.getOrPut(helper.file) { mutableListOf() } += helper.assignmentRange to ""
    }
    for ((file, edits) in callEditsByFile) {
        allEditsByFile.getOrPut(file) { mutableListOf() }.addAll(edits)
    }

    for ((javaFile, edits) in allEditsByFile) {
        val original = javaFile.readText()
        var migrated = applyStringEdits(original, edits)
        if (javaFile in helperFiles) {
            val executableWithoutFmlImport = Regex(
                """(?m)^[ \t]*import\s+net\.neoforged\.fml\.javafmlmod\.FMLJavaModLoadingContext\s*;\s*\r?\n?"""
            ).replace(maskJavaCommentsAndLiterals(migrated), "")
            if (!executableWithoutFmlImport.contains("FMLJavaModLoadingContext")) {
                migrated = removeExecutableImport(migrated, "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext")
            }
            migrated = addExecutableImportIfMissing(migrated, "net.neoforged.bus.api.IEventBus")
        }
        if (!dryRun) javaFile.writeText(migrated)
        if (javaFile in helperFiles) {
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Migrate static FML mod event bus helper initializer to explicit IEventBus parameter",
                before = "IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus()",
                after = "static init(IEventBus bus)",
                confidence = Confidence.HIGH,
                ruleId = "struct-static-fml-helper-eventbus"
            ))
        }
        if (javaFile in callsiteFiles) {
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Pass explicit mod event bus to migrated static helper initializer",
                before = "Helper.init()",
                after = "Helper.init(modEventBus)",
                confidence = Confidence.HIGH,
                ruleId = "struct-static-fml-helper-callsite"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.eventBusParameterExpressionAt(source: String, offset: Int): String? {
    val parameters = currentJavaExecutableParametersBeforeOffset(source, offset)
    val busType = """(?:IEventBus|net\.neoforged\.bus\.api\.IEventBus)"""
    val names = parameters.mapNotNull { parameter ->
        Regex("""(?:final\s+)?(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*$busType\s+([A-Za-z_$][\w$]*)$""")
            .find(parameter.trim().replace(Regex("""\s+"""), " "))
            ?.groupValues
            ?.get(1)
    }.distinct()
    return names.singleOrNull()
}

internal fun StructuralRefactorPass.constructorEventBusParameterExpressionAt(
    source: String,
    offset: Int,
    ownerClassName: String?
): String? {
    val owner = ownerClassName ?: return null
    val compilationUnit = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    ).parse(source).result.orElse(null) ?: return null
    val lineStarts = mutableListOf(0)
    source.forEachIndexed { index, char ->
        if (char == '\n') lineStarts += index + 1
    }
    fun nodeOffsets(node: com.github.javaparser.ast.Node): IntRange? {
        val range = node.range.orElse(null) ?: return null
        val startLine = range.begin.line - 1
        val endLine = range.end.line - 1
        if (startLine !in lineStarts.indices || endLine !in lineStarts.indices) return null
        return (lineStarts[startLine] + range.begin.column - 1)..
            (lineStarts[endLine] + range.end.column - 1)
    }

    val getter = compilationUnit.findAll(MethodCallExpr::class.java)
        .filter { it.nameAsString == "getModEventBus" }
        .filter { nodeOffsets(it)?.contains(offset) == true }
        .singleOrNull() ?: return null
    val constructor = getter.findAncestor(CallableDeclaration::class.java).orElse(null)
        as? ConstructorDeclaration ?: return null
    if (!constructor.isPublic || constructor.nameAsString != owner) return null
    val ownerDeclaration = constructor.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
        ?: return null
    if (ownerDeclaration.nameAsString != owner ||
        ownerDeclaration.annotations.none { it.name.identifier == "Mod" }
    ) {
        return null
    }

    var ancestor = getter.parentNode.orElse(null)
    while (ancestor != null && ancestor !== constructor) {
        if (ancestor is LambdaExpr ||
            ancestor is MethodDeclaration ||
            ancestor is ConstructorDeclaration ||
            (ancestor is ObjectCreationExpr && ancestor.anonymousClassBody.isPresent)
        ) {
            return null
        }
        ancestor = ancestor.parentNode.orElse(null)
    }
    if (ancestor !== constructor) return null

    val eventBusNames = constructor.parameters
        .filter { parameter ->
            parameter.typeAsString
                .replace(Regex("""\s+"""), "")
                .let { it == "IEventBus" || it == "net.neoforged.bus.api.IEventBus" }
        }
        .map { it.nameAsString }
        .distinct()
    return eventBusNames.singleOrNull()
}

internal fun StructuralRefactorPass.currentJavaExecutableParametersBeforeOffset(source: String, offset: Int): List<String> {
    val methodPattern = Regex(
        """(?m)(?:^|\r?\n)[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|synchronized)\s+)+[A-Za-z_$][\w$<>\[\].?,\s]*\s+[A-Za-z_$][\w$]*\s*\(([^()]*)\)\s*\{"""
    )
    val constructorPattern = Regex(
        """(?m)(?:^|\r?\n)[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:public|protected|private)\s+[A-Za-z_$][\w$]*\s*\(([^()]*)\)\s*\{"""
    )
    val executableCode = maskJavaCommentsAndLiterals(source)
    return sequenceOf(methodPattern, constructorPattern)
        .flatMap { pattern -> pattern.findAll(executableCode).asSequence() }
        .filter { it.range.first < offset }
        .filter { match ->
            val openBrace = executableCode.indexOf('{', match.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
            closeBrace >= offset
        }
        .maxByOrNull { it.range.first }
        ?.groupValues
        ?.get(1)
        ?.let { splitTopLevelJavaArgs(it) }
        ?: emptyList()
}

internal data class FluidBucketCapabilityMigration(
    val itemClassFile: Path,
    val packageName: String,
    val className: String
)

internal data class FluidBucketInitCapabilitiesRemoval(
    val source: String,
    val providerExpression: String?
)

internal data class RegisteredItemReference(
    val registryFile: Path,
    val packageName: String,
    val registryClassName: String,
    val fieldName: String
) {
    val qualifiedRegistryClass: String
        get() = if (packageName.isBlank()) registryClassName else "$packageName.$registryClassName"
}

internal fun StructuralRefactorPass.migrateFluidBucketItemCapabilities(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val javaInheritanceIndex = collectJavaInheritanceIndex(javaFiles)
    val fluidBucketWrapperTypes = collectFluidBucketWrapperTypes(javaFiles, javaInheritanceIndex)
    val bucketItemBaseTypes = setOf("BucketItem", "MobBucketItem", "MilkBucketItem")

    val migrations = javaFiles.mapNotNull { javaFile ->
        val text = javaFile.readText()
        if (!text.contains("initCapabilities")) {
            return@mapNotNull null
        }

        val declaration = collectJavaClassDeclarations(text)
            .firstOrNull { javaClassExtendsAny(it, bucketItemBaseTypes, javaInheritanceIndex) }
            ?: return@mapNotNull null
        val className = declaration.name
        val packageName = packageNameOf(text)
        var modified = text

        val removal = removeFluidBucketInitCapabilities(modified, fluidBucketWrapperTypes)
            ?: return@mapNotNull null
        modified = removal.source

        if (removal.providerExpression != null) {
            modified = migrateBucketItemSupplierSuperCall(modified, className)
            modified = addFluidBucketCapabilityRegistrationMethod(modified, className, removal.providerExpression)
            modified = addImportIfMissing(modified, "net.minecraft.world.level.ItemLike")
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.capabilities.Capabilities")
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
            if (removal.providerExpression.contains("new FluidBucketWrapper(")) {
                modified = addImportIfMissing(
                    modified,
                    "net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper"
                )
            }
        }
        modified = cleanupFluidBucketInitCapabilitiesImports(modified)

        if (!dryRun) {
            javaFile.writeText(modified)
        }
        changes.add(Change(
            file = javaFile,
            line = 0,
            description = "Migrate legacy BucketItem initCapabilities() to RegisterCapabilitiesEvent item provider",
            before = "initCapabilities(...) { return new FluidBucketWrapper(stack); }",
            after = "registerCapabilities(RegisterCapabilitiesEvent, ItemLike...)",
            confidence = Confidence.HIGH,
            ruleId = "struct-fluid-bucket-item-capability"
        ))

        removal.providerExpression?.let {
            FluidBucketCapabilityMigration(
                itemClassFile = javaFile,
                packageName = packageName,
                className = className
            )
        }
    }

    if (migrations.isEmpty()) return changes

    val mainClass = detectModMainClass(projectDir) ?: return changes
    var mainText = mainClass.readText()
    val originalMainText = mainText
    val mainPackage = packageNameOf(mainText)
    val constructorBusName = requireModEventBusName(mainText, "fluid bucket capability listener registration")

    for (migration in migrations) {
        val itemRefs = findRegisteredItemReferences(javaFiles, migration.className)
        if (itemRefs.isEmpty()) continue
        if (mainText.contains("${migration.className}.registerCapabilities")) continue

        if (migration.packageName != mainPackage) {
            mainText = addImportIfMissing(mainText, "${migration.packageName}.${migration.className}")
        }
        mainText = addImportIfMissing(mainText, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        itemRefs.map { it.qualifiedRegistryClass }
            .distinct()
            .filter { it.substringBeforeLast('.', "") != mainPackage }
            .forEach { mainText = addImportIfMissing(mainText, it) }

        val itemArguments = itemRefs.joinToString(",\n") { ref ->
            "                ${ref.registryClassName}.${ref.fieldName}.get()"
        }
        val registration = """
        $constructorBusName.addListener((RegisterCapabilitiesEvent event) -> ${migration.className}.registerCapabilities(event,
$itemArguments
        ));
""".trimEnd()

        mainText = insertModBusListener(mainText, constructorBusName, registration, itemRefs.first().registryClassName)
        changes.add(Change(
            file = mainClass,
            line = 0,
            description = "Register ${migration.className} fluid item capability providers on the mod event bus",
            before = "(no RegisterCapabilitiesEvent listener)",
            after = "${migration.className}.registerCapabilities(event, ...)",
            confidence = Confidence.HIGH,
            ruleId = "struct-fluid-bucket-capability-listener"
        ))
    }

    if (mainText != originalMainText && !dryRun) {
        mainClass.writeText(mainText)
    }

    return changes
}

internal fun StructuralRefactorPass.collectFluidBucketWrapperTypes(
    javaFiles: List<Path>,
    javaInheritanceIndex: JavaInheritanceIndex
): Set<String> =
    buildSet {
        add("FluidBucketWrapper")
        javaFiles.flatMap { javaFile -> collectJavaClassDeclarations(javaFile.readText()) }
            .filter { declaration -> javaClassExtendsAny(declaration, setOf("FluidBucketWrapper"), javaInheritanceIndex) }
            .forEach { declaration -> add(declaration.name) }
    }

internal fun StructuralRefactorPass.removeFluidBucketInitCapabilities(
    source: String,
    fluidBucketWrapperTypes: Set<String>
): FluidBucketInitCapabilitiesRemoval? {
    val methodName = "initCapabilities"
    var searchStart = source.indexOf(methodName)
    var result = source
    while (searchStart >= 0) {
        val methodNameIndex = result.indexOf(methodName, searchStart)
        if (methodNameIndex < 0) return null
        val openBrace = result.indexOf('{', methodNameIndex)
        if (openBrace < 0) return null
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) return null
        val signatureStart = result.lastIndexOf('\n', methodNameIndex).let { if (it < 0) 0 else it + 1 }
        val annotationStart = Regex("""(?m)(?:^[ \t]*@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\r?\n)+[ \t]*$""")
            .findAll(result.substring(0, signatureStart))
            .lastOrNull()
            ?.range
            ?.first
            ?: signatureStart
        val removalStart = Regex("""(?s)/\*\*.*?\*/\s*$""")
            .find(result.substring(0, annotationStart))
            ?.range
            ?.first
            ?: annotationStart
        val methodText = result.substring(annotationStart, closeBrace + 1)
        if (!methodText.contains("ICapabilityProvider")) {
            searchStart = closeBrace + 1
            continue
        }
        val headerText = result.substring(annotationStart, openBrace)
        val bodyText = result.substring(openBrace + 1, closeBrace)
        val providerExpression = fluidBucketProviderExpression(
            headerText,
            bodyText,
            fluidBucketWrapperTypes
        )
        if (providerExpression == null && !initCapabilitiesReturnsOnlyNull(bodyText)) {
            searchStart = closeBrace + 1
            continue
        }
        val end = (closeBrace + 1).let { idx ->
            if (idx < result.length && result[idx] == '\r') (idx + 1).coerceAtMost(result.length)
            else idx
        }.let { idx ->
            if (idx < result.length && result[idx] == '\n') idx + 1 else idx
        }
        return FluidBucketInitCapabilitiesRemoval(
            source = result.removeRange(removalStart, end),
            providerExpression = providerExpression
        )
    }
    return null
}

internal fun StructuralRefactorPass.fluidBucketProviderExpression(
    methodHeader: String,
    methodBody: String,
    fluidBucketWrapperTypes: Set<String>
): String? {
    val methodNameIndex = methodHeader.indexOf("initCapabilities")
    if (methodNameIndex < 0) return null
    val parametersText = methodHeader.substring(methodNameIndex).substringAfter("(", "").substringBeforeLast(")", "")
    val parameterNames = javaParameterNames(parametersText)
    val stackParameterName = splitTopLevelJavaArgs(parametersText)
        .firstOrNull { parameter -> parameter.contains("ItemStack") }
        ?.let(::javaParameterName)
        ?: return null
    val returnExpressions = Regex("""(?s)\breturn\s+([^;]+);""")
        .findAll(methodBody)
        .map { it.groupValues[1].trim() }
        .toList()
    if (returnExpressions.isEmpty()) return null

    val providerReturns = returnExpressions.mapNotNull { expression ->
        normalizeFluidBucketProviderExpression(expression, stackParameterName, fluidBucketWrapperTypes)
    }.distinct()
    if (providerReturns.size != 1) return null

    val providerExpression = providerReturns.single()
    if (returnExpressions.size == 1) {
        return providerExpression
    }

    val unsupportedReturns = returnExpressions.filter { expression ->
        normalizeFluidBucketProviderExpression(expression, stackParameterName, fluidBucketWrapperTypes) == null &&
            expression != "null" &&
            !Regex("""^super\.initCapabilities\s*\(""").containsMatchIn(expression)
    }
    if (unsupportedReturns.isNotEmpty()) return null

    return guardedFluidBucketProviderExpression(
        methodBody,
        stackParameterName,
        parameterNames - stackParameterName,
        providerExpression,
        fluidBucketWrapperTypes
    )
}

internal fun StructuralRefactorPass.normalizeFluidBucketProviderExpression(
    expression: String,
    stackParameterName: String,
    fluidBucketWrapperTypes: Set<String>
): String? {
    val match = Regex(
        """^new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(\s*${Regex.escape(stackParameterName)}\s*\)$"""
    ).find(expression.trim()) ?: return null
    val wrapperType = match.groupValues[1]
    val wrapperSimpleName = wrapperType.substringAfterLast('.')
    if (wrapperSimpleName !in fluidBucketWrapperTypes) return null
    val emittedType = if (wrapperType == "net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper") {
        "FluidBucketWrapper"
    } else {
        wrapperType
    }
    return "new $emittedType(stack)"
}

internal fun StructuralRefactorPass.guardedFluidBucketProviderExpression(
    methodBody: String,
    stackParameterName: String,
    nonStackParameterNames: Set<String>,
    providerExpression: String,
    fluidBucketWrapperTypes: Set<String>
): String? {
    val guardedReturnPatterns = listOf(
        Regex("""(?s)\bif\s*\((.*?)\)\s*\{\s*return\s+([^;]+);\s*\}"""),
        Regex("""(?s)\bif\s*\((.*?)\)\s*return\s+([^;]+);""")
    )
    for (pattern in guardedReturnPatterns) {
        for (match in pattern.findAll(methodBody)) {
            val returnedProvider = normalizeFluidBucketProviderExpression(
                match.groupValues[2].trim(),
                stackParameterName,
                fluidBucketWrapperTypes
            ) ?: continue
            if (returnedProvider != providerExpression) continue
            val condition = match.groupValues[1].trim()
            if (nonStackParameterNames.any { name ->
                    Regex("""(?<![\w$])${Regex.escape(name)}(?![\w$])""").containsMatchIn(condition)
                }
            ) {
                continue
            }
            val normalizedCondition = Regex("""(?<![\w$])${Regex.escape(stackParameterName)}(?![\w$])""")
                .replace(condition, "stack")
            return "$normalizedCondition ? $providerExpression : null"
        }
    }
    return null
}

internal fun StructuralRefactorPass.initCapabilitiesReturnsOnlyNull(methodBody: String): Boolean {
    val returnExpressions = Regex("""(?s)\breturn\s+([^;]+);""")
        .findAll(methodBody)
        .map { it.groupValues[1].trim() }
        .toList()
    return returnExpressions.isNotEmpty() && returnExpressions.all { it == "null" }
}

internal fun StructuralRefactorPass.javaParameterNames(parametersText: String): Set<String> =
    splitTopLevelJavaArgs(parametersText)
        .mapNotNull(::javaParameterName)
        .toSet()

internal fun StructuralRefactorPass.javaParameterName(parameterText: String): String? =
    Regex("""([A-Za-z_$][\w$]*)\s*(?:\[\s*\])?\s*$""")
        .find(parameterText.trim())
        ?.groupValues
        ?.get(1)

internal fun StructuralRefactorPass.javaParameterSimpleType(parameterText: String): String? =
    javaParameterTypeAndName(parameterText)?.first?.let(::simpleJavaTypeName)

internal fun StructuralRefactorPass.migrateBucketItemSupplierSuperCall(source: String, className: String): String {
    val constructorPattern = Regex(
        """(public\s+${Regex.escape(className)}\s*\(\s*Supplier\s*<\s*\?\s+extends\s+Fluid\s*>\s+(\w+)\s*,\s*Properties\s+(\w+)\s*\)\s*\{\s*)super\(\s*\2\s*,\s*\3\s*\);"""
    )
    return constructorPattern.replace(source) { match ->
        "${match.groupValues[1]}super(${match.groupValues[2]}.get(), ${match.groupValues[3]});"
    }
}

internal fun StructuralRefactorPass.addFluidBucketCapabilityRegistrationMethod(
    source: String,
    className: String,
    providerExpression: String
): String {
    if (source.contains("registerCapabilities(RegisterCapabilitiesEvent")) return source
    val classMatch = Regex("""\bclass\s+${Regex.escape(className)}\b[^{]*\{""").find(source)
        ?: return source
    val classOpen = source.indexOf('{', classMatch.range.last)
    if (classOpen < 0) return source
    val method = """

    public static void registerCapabilities(RegisterCapabilitiesEvent event, ItemLike... items) {
        event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> $providerExpression, items);
    }
"""
    return source.substring(0, classOpen + 1) + method + source.substring(classOpen + 1)
}

internal fun StructuralRefactorPass.cleanupFluidBucketInitCapabilitiesImports(source: String): String {
    var result = source
    val withoutImports = Regex("""(?m)^[ \t]*import\s+[^\r\n]+;\s*\r?\n""").replace(result, "")
    if (!hasSimpleTypeReference(withoutImports, "ICapabilityProvider")) {
        result = removeImport(result, "net.neoforged.neoforge.capabilities.ICapabilityProvider")
    }
    if (!hasSimpleTypeReference(withoutImports, "CompoundTag")) {
        result = removeImport(result, "net.minecraft.nbt.CompoundTag")
    }
    val withoutImportsAfterTypes = Regex("""(?m)^[ \t]*import\s+[^\r\n]+;\s*\r?\n""").replace(result, "")
    if (!withoutImportsAfterTypes.contains("@Nullable")) {
        result = removeImport(result, "org.jetbrains.annotations.Nullable")
        result = removeImport(result, "javax.annotation.Nullable")
    }
    return cleanupRedundantBlankLines(result)
}

internal fun StructuralRefactorPass.findRegisteredItemReferences(
    javaFiles: List<Path>,
    itemClassName: String
): List<RegisteredItemReference> =
    javaFiles.flatMap { javaFile ->
        val text = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(text)
        val itemConstructorReference = Regex("""(?:new\s+(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(itemClassName)}\s*\()|(?:(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(itemClassName)}\s*::\s*new)""")
        if (!itemConstructorReference.containsMatchIn(executableCode)) return@flatMap emptyList()
        val packageName = packageNameOf(text)
        val registryClassName = Regex("""\bclass\s+(\w+)\b""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: return@flatMap emptyList()
        val fieldPattern = Regex(
            """(?s)public\s+static\s+final\s+[^=;]+?\s+(\w+)\s*=\s*\w+\.register\s*\([^;]*?(?:new\s+(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(itemClassName)}\s*\(|(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(itemClassName)}\s*::\s*new)"""
        )
        fieldPattern.findAll(executableCode).map { match ->
            RegisteredItemReference(
                registryFile = javaFile,
                packageName = packageName,
                registryClassName = registryClassName,
                fieldName = match.groupValues[1]
            )
        }.toList()
    }

internal fun StructuralRefactorPass.insertModBusListener(
    source: String,
    eventBusName: String,
    registration: String,
    preferredRegistryClassName: String
): String {
    val constructorBody = findConstructorBodyWithEventBus(source, eventBusName)
    fun insideConstructor(match: MatchResult): Boolean =
        constructorBody == null || match.range.first in constructorBody
    val clientGuardRanges = constructorBody?.let { findClientDistGuardRanges(source, it) }.orEmpty()
    val isClientOnlyRegistration = registration.contains("Dist.CLIENT") || registration.contains("FMLLoader.getDist()")
    fun outsideClientGuard(match: MatchResult): Boolean =
        isClientOnlyRegistration || clientGuardRanges.none { match.range.first in it }
    val busDeclarationIndent = Regex(
        """(?m)^([ \t]*)IEventBus\s+${Regex.escape(eventBusName)}\s*=\s*[^;]*(?:getEventBus|getModEventBus)\s*\(\s*\)\s*;\s*$"""
    ).findAll(source)
        .firstOrNull(::insideConstructor)
        ?.groupValues
        ?.get(1)
    fun sameConstructorLevel(match: MatchResult): Boolean =
        busDeclarationIndent == null || match.groupValues.getOrNull(1) == busDeclarationIndent
    fun allowedRegistrationAnchor(match: MatchResult): Boolean =
        insideConstructor(match) && outsideClientGuard(match) && sameConstructorLevel(match)

    val afterPreferredRegister = Regex(
        """(?m)^([ \t]*)${Regex.escape(preferredRegistryClassName)}(?:\.\w+)*\.register\(${Regex.escape(eventBusName)}\);\s*$"""
    ).findAll(source)
        .lastOrNull(::allowedRegistrationAnchor)
    if (afterPreferredRegister != null) {
        val insertPos = afterPreferredRegister.range.last + 1
        return source.substring(0, insertPos) + "\n" + registration + source.substring(insertPos)
    }

    val afterAnyRegister = Regex("""(?m)^([ \t]*)[\w.]+\.register\(${Regex.escape(eventBusName)}\);\s*$""")
        .findAll(source)
        .filter(::allowedRegistrationAnchor)
        .lastOrNull()
    if (afterAnyRegister != null) {
        val insertPos = afterAnyRegister.range.last + 1
        return source.substring(0, insertPos) + "\n" + registration + source.substring(insertPos)
    }

    if (constructorBody != null) {
        val localDeclaration = Regex(
            """(?m)^([ \t]*)IEventBus\s+${Regex.escape(eventBusName)}\s*=\s*[^;]*(?:getEventBus|getModEventBus)\s*\(\s*\)\s*;\s*$"""
        ).findAll(source)
            .filter { insideConstructor(it) && outsideClientGuard(it) }
            .firstOrNull()
        if (localDeclaration != null) {
            val insertPos = localDeclaration.range.last + 1
            return source.substring(0, insertPos) + "\n" + registration + source.substring(insertPos)
        }

        val insertPos = constructorBody.last + 1
        return source.substring(0, insertPos) + "\n" + registration + "\n" + source.substring(insertPos)
    }

    return source + "\n" + registration + "\n"
}

internal fun StructuralRefactorPass.findClientDistGuardRanges(source: String, constructorBody: IntRange): List<IntRange> {
    val guardPattern = Regex(
        """if\s*\(\s*(?:FMLLoader|net\.neoforged\.fml\.loading\.FMLLoader)\.getDist\(\)\s*==\s*(?:Dist|net\.neoforged\.api\.distmarker\.Dist)\.CLIENT\s*\)\s*\{"""
    )
    return guardPattern.findAll(source)
        .mapNotNull { match ->
            if (match.range.first !in constructorBody) return@mapNotNull null
            val openBrace = source.indexOf('{', match.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            if (openBrace < 0 || closeBrace < openBrace) null else match.range.first..closeBrace
        }
        .toList()
}

internal fun StructuralRefactorPass.findConstructorBodyWithEventBus(source: String, eventBusName: String): IntRange? {
    val className = classNameOfJavaSource(source) ?: return null
    val constructorPattern = Regex("""\b(?:public|protected|private)?\s*${Regex.escape(className)}\s*\([^)]*\)\s*\{""")
    for (constructorMatch in constructorPattern.findAll(source)) {
        val openBrace = source.indexOf('{', constructorMatch.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) continue
        val header = source.substring(constructorMatch.range.first, openBrace)
        val body = source.substring(openBrace + 1, closeBrace)
        val busType = """(?:IEventBus|net\.neoforged\.bus\.api\.IEventBus)"""
        val parameterBus = Regex("""\b(?:final\s+)?$busType\s+${Regex.escape(eventBusName)}\b""").containsMatchIn(header)
        val localBus = Regex(
            """\b$busType\s+${Regex.escape(eventBusName)}\s*=\s*[^;]*(?:getEventBus|getModEventBus)\s*\(\s*\)\s*;"""
        ).containsMatchIn(body)
        if (parameterBus || localBus) {
            return (openBrace + 1) until closeBrace
        }
    }
    return null
}

internal data class LegacyFluidItemProviderFactory(
    val file: Path,
    val packageName: String,
    val className: String,
    val handlerClassName: String,
    val providerWrapperClassName: String?,
    val providerMethodNames: Set<String>
)

internal data class LegacyFluidItemProviderRegistration(
    val itemFile: Path,
    val itemClassName: String,
    val itemReferences: List<RegisteredItemReference>,
    val handlerKindExpression: String?,
    /** Complete handler construction for the registerItem lambda (`new Handler(stack)`), when the factory branch names one. */
    val providerExpression: String? = null,
    /** Item expressions registered besides the mod's own registry fields (for example `Items.GLASS_BOTTLE`). */
    val itemExpressions: List<String> = emptyList(),
    /** A source file that only existed to attach the provider on Forge (a BucketItem#initCapabilities mixin). */
    val obsoleteMixinFile: Path? = null
)

internal data class DirectFluidHandlerItemStackCapabilityMigration(
    val itemFile: Path,
    val packageName: String,
    val className: String,
    val itemReferences: List<RegisteredItemReference>,
    val modIdExpression: String,
    val dataComponentsFieldName: String,
    val componentFieldName: String,
    val componentPath: String,
    val capacityExpression: String
)

internal data class LegacyFluidHandlerItemStackTemplate(
    val capacityExpression: String,
    val anonymousBody: String
)

internal data class ItemStackHandlerItemCapabilityMigration(
    val itemFile: Path,
    val packageName: String,
    val className: String,
    val itemReferences: List<RegisteredItemReference>,
    val handlerClassName: String,
    val handlerSizeExpression: String,
    val modIdExpression: String?,
    val dataComponentsFieldName: String?,
    val componentFieldName: String?,
    val componentPath: String?,
    val fluidTemplate: LegacyFluidHandlerItemStackTemplate?
)

internal fun StructuralRefactorPass.migrateItemStackHandlerItemCapabilities(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val mainClass = detectModMainClass(projectDir) ?: return changes
    val mainText = mainClass.readText()
    val migrations = javaFiles.mapNotNull { javaFile ->
        itemStackHandlerItemCapabilityMigration(javaFile, javaFiles, mainClass, mainText)
    }
    if (migrations.isEmpty()) return changes

    for (migration in migrations) {
        var itemText = migration.itemFile.readText()
        val originalItemText = itemText
        itemText = removeMethodByNameContaining(
            itemText,
            "initCapabilities",
            listOf(migration.handlerClassName, "ItemStackHandler")
        )
        itemText = migrateItemStackHandlerProviderClass(itemText, migration)
        itemText = insertItemStackHandlerCapabilityRegistrationMethods(itemText, migration)
        itemText = addImportIfMissing(itemText, "net.minecraft.core.component.DataComponents")
        itemText = addImportIfMissing(itemText, "net.minecraft.world.item.component.ItemContainerContents")
        itemText = addImportIfMissing(itemText, "net.minecraft.world.level.ItemLike")
        itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.capabilities.Capabilities")
        itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        if (migration.fluidTemplate != null) {
            itemText = addImportIfMissing(itemText, "net.minecraft.core.component.DataComponentType")
            itemText = addImportIfMissing(itemText, "net.minecraft.core.registries.Registries")
            itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.fluids.SimpleFluidContent")
            itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.fluids.capability.templates.FluidHandlerItemStack")
            itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.registries.DeferredHolder")
            itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.registries.DeferredRegister")
        }
        itemText = removeUnusedSimpleImports(
            itemText,
            listOf(
                "net.minecraft.core.Direction",
                "net.minecraft.core.HolderLookup",
                "net.minecraft.nbt.CompoundTag",
                "net.neoforged.neoforge.capabilities.ICapabilityProvider",
                "net.neoforged.neoforge.items.IItemHandler",
                "org.apache.commons.lang3.StringUtils",
                "org.jetbrains.annotations.Nullable",
                "javax.annotation.Nullable"
            )
        )
        itemText = removeUnusedImportsBySimpleNamePattern(itemText, Regex("""Capability|LazyOptional"""))
        itemText = cleanupRedundantBlankLines(itemText)
        if (itemText != originalItemText) {
            if (!dryRun) {
                migration.itemFile.writeText(itemText)
            }
            changes.add(Change(
                file = migration.itemFile,
                line = 0,
                description = "Migrate ItemStackHandler-backed item initCapabilities hook to registered item capability",
                before = "initCapabilities(...) -> ICapabilityProvider wrapping ItemStackHandler",
                after = "DataComponents.CONTAINER-backed ItemStackHandler + RegisterCapabilitiesEvent.registerItem",
                confidence = Confidence.HIGH,
                ruleId = "struct-itemstackhandler-item-capability"
            ))
        }
    }

    var migratedMainText = mainText
    val originalMainText = migratedMainText
    val mainPackage = packageNameOf(migratedMainText)
    val constructorBusName = requireModEventBusName(migratedMainText, "ItemStackHandler item capability registration")
    for (migration in migrations) {
        if (migration.packageName != mainPackage) {
            migratedMainText = addImportIfMissing(migratedMainText, "${migration.packageName}.${migration.className}")
        }
        migratedMainText = addImportIfMissing(migratedMainText, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        migration.itemReferences.map { it.qualifiedRegistryClass }
            .distinct()
            .filter { it.substringBeforeLast('.', "") != mainPackage }
            .forEach { migratedMainText = addImportIfMissing(migratedMainText, it) }

        val itemArguments = migration.itemReferences.joinToString(",\n") { ref ->
            "                ${ref.registryClassName}.${ref.fieldName}.get()"
        }
        val registrations = buildList {
            if (migration.fluidTemplate != null &&
                migration.dataComponentsFieldName != null &&
                !migratedMainText.contains("${migration.className}.${migration.dataComponentsFieldName}.register($constructorBusName)")
            ) {
                add("        ${migration.className}.${migration.dataComponentsFieldName}.register($constructorBusName);")
            }
            if (migration.fluidTemplate != null &&
                !migratedMainText.contains("${migration.className}.registerFluidCapabilities(event,")
            ) {
                add("""
        $constructorBusName.addListener((RegisterCapabilitiesEvent event) -> ${migration.className}.registerFluidCapabilities(event,
$itemArguments
        ));
""".trimEnd())
            }
            if (!migratedMainText.contains("${migration.className}.registerItemCapabilities(event,")) {
                add("""
        $constructorBusName.addListener((RegisterCapabilitiesEvent event) -> ${migration.className}.registerItemCapabilities(event,
$itemArguments
        ));
""".trimEnd())
            }
        }
        if (registrations.isNotEmpty()) {
            migratedMainText = insertModBusListener(
                migratedMainText,
                constructorBusName,
                registrations.joinToString("\n"),
                migration.itemReferences.first().registryClassName
            )
            changes.add(Change(
                file = mainClass,
                line = 0,
                description = "Register ItemStackHandler-backed item capabilities on the mod event bus",
                before = "(no RegisterCapabilitiesEvent item capability listener)",
                after = "${migration.className}.registerItemCapabilities(event, ...)",
                confidence = Confidence.HIGH,
                ruleId = "struct-itemstackhandler-item-capability-listener"
            ))
        }
    }
    if (migratedMainText != originalMainText && !dryRun) {
        mainClass.writeText(migratedMainText)
    }

    return changes
}

internal fun StructuralRefactorPass.itemStackHandlerItemCapabilityMigration(
    javaFile: Path,
    javaFiles: List<Path>,
    mainClass: Path,
    mainText: String
): ItemStackHandlerItemCapabilityMigration? {
    val source = javaFile.readText()
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("initCapabilities") || !executableCode.contains("ItemStackHandler")) {
        return null
    }
    val className = classNameOfJavaSource(executableCode) ?: return null
    val handlerClassNames = itemStackHandlerProviderClassNames(source)
    if (handlerClassNames.isEmpty()) return null
    val method = javaMethodRangesIncludingDefault(executableCode).firstOrNull { candidate ->
        candidate.name == "initCapabilities" &&
            handlerClassNames.any { executableCode.substring(candidate.range).contains("new $it(") }
    } ?: return null
    val parametersText = method.header.substringAfter("(", "").substringBeforeLast(")", "")
    val stackParameterName = splitTopLevelJavaArgs(parametersText)
        .firstOrNull { parameter -> parameter.contains("ItemStack") }
        ?.let(::javaParameterName)
        ?: return null
    val methodText = source.substring(method.range)
    val methodExecutable = executableCode.substring(method.range)
    val itemHandler = legacyItemStackHandlerConstruction(
        methodText,
        methodExecutable,
        stackParameterName,
        handlerClassNames
    ) ?: return null
    val itemReferences = findRegisteredItemReferences(javaFiles, className)
        .distinctBy { "${it.qualifiedRegistryClass}.${it.fieldName}" }
        .sortedWith(compareBy({ it.qualifiedRegistryClass }, { it.fieldName }))
    if (itemReferences.isEmpty()) return null

    val fluidTemplate = legacyFluidHandlerItemStackTemplate(methodText, stackParameterName)
    val modIdExpression = if (fluidTemplate != null) {
        inferModAccess(source)?.modIdExpression
            ?: explicitModIdReferenceForGeneratedClass(mainClass, mainText, packageNameOf(source), metadataModId = null)
            ?: return null
    } else {
        null
    }

    return ItemStackHandlerItemCapabilityMigration(
        itemFile = javaFile,
        packageName = packageNameOf(source),
        className = className,
        itemReferences = itemReferences,
        handlerClassName = itemHandler.first,
        handlerSizeExpression = itemHandler.second,
        modIdExpression = modIdExpression,
        dataComponentsFieldName = fluidTemplate?.let { uniqueJavaFieldName(source, "DATA_COMPONENTS") },
        componentFieldName = fluidTemplate?.let { uniqueJavaFieldName(source, "FLUID_CONTENT") },
        componentPath = fluidTemplate?.let { "${itemReferences.first().fieldName.lowercase()}_fluid_content" },
        fluidTemplate = fluidTemplate
    )
}

internal fun StructuralRefactorPass.itemStackHandlerProviderClassNames(source: String): Set<String> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    return javaTypeBlocks(source, executableCode)
        .filter { type ->
            val declaration = executableCode.substring(type.declarationStart, type.bodyStart)
            declaration.contains("extends ItemStackHandler") ||
                declaration.contains("extends net.neoforged.neoforge.items.ItemStackHandler")
        }
        .filter { type ->
            val body = executableCode.substring(type.bodyStart, type.end)
            body.contains("onContentsChanged") &&
                (body.contains("CustomData") || body.contains("ItemStack.parseOptional") || body.contains("contents"))
        }
        .mapTo(linkedSetOf()) { it.name }
}

internal fun StructuralRefactorPass.legacyItemStackHandlerConstruction(
    methodText: String,
    executableMethodText: String,
    stackParameterName: String,
    handlerClassNames: Set<String>
): Pair<String, String>? {
    val handlerAlternation = handlerClassNames.joinToString("|") { Regex.escape(it) }
    val constructor = Regex("""new\s+($handlerAlternation)\s*\(""")
        .find(executableMethodText)
        ?: return null
    val openParen = constructor.range.last
    val closeParen = findMatchingParen(executableMethodText, openParen)
    if (closeParen < 0) return null
    val args = splitTopLevelJavaArgs(methodText.substring(openParen + 1, closeParen))
    if (args.size < 2 || args[0].trim() != stackParameterName) return null
    return constructor.groupValues[1] to args[1].trim()
}

internal fun StructuralRefactorPass.legacyFluidHandlerItemStackTemplate(
    methodText: String,
    stackParameterName: String
): LegacyFluidHandlerItemStackTemplate? {
    val executableMethodText = maskJavaCommentsAndLiterals(methodText)
    val constructor = Regex("""new\s+(?:[A-Za-z_$][\w$]*\.)*FluidHandlerItemStack\s*\(""")
        .find(executableMethodText)
        ?: return null
    val openParen = constructor.range.last
    val closeParen = findMatchingParen(executableMethodText, openParen)
    if (closeParen < 0) return null
    val args = splitTopLevelJavaArgs(methodText.substring(openParen + 1, closeParen))
    if (args.size != 2 || args[0].trim() != stackParameterName) return null
    var suffixStart = closeParen + 1
    while (suffixStart < executableMethodText.length && executableMethodText[suffixStart].isWhitespace()) {
        suffixStart++
    }
    val anonymousBody = if (suffixStart < executableMethodText.length && executableMethodText[suffixStart] == '{') {
        val anonymousClose = findMatchingBrace(executableMethodText, suffixStart)
        if (anonymousClose < 0) return null
        methodText.substring(suffixStart, anonymousClose + 1)
    } else {
        ""
    }
    return LegacyFluidHandlerItemStackTemplate(args[1].trim(), anonymousBody)
}

internal fun StructuralRefactorPass.migrateItemStackHandlerProviderClass(
    source: String,
    migration: ItemStackHandlerItemCapabilityMigration
): String {
    var result = source
    result = replaceExecutableRegex(
        result,
        Regex("""(class\s+${Regex.escape(migration.handlerClassName)}\b[^{;\r\n]*?\bextends\s+(?:net\.neoforged\.neoforge\.items\.)?ItemStackHandler)\s+implements\s+ICapabilityProvider""")
    ) { match -> match.groupValues[1] }
    result = replaceExecutableRegex(
        result,
        Regex("""(?m)^[ \t]*private\s+final\s+LazyOptional\s*<\s*IItemHandler\s*>\s+[A-Za-z_$][\w$]*\s*=\s*LazyOptional\.of\s*\(\s*\(\s*\)\s*->\s*this\s*\)\s*;\s*\r?\n""")
    ) { "" }
    result = removeJavaMethodDeclarationsByNameContaining(result, "getCapability", listOf("Capabilities.ItemHandler"))

    val containerField = itemStackHandlerContainerFieldName(result, migration.handlerClassName) ?: return result
    result = migrateItemStackHandlerConstructorsToDataComponent(result, migration.handlerClassName, containerField)
    result = replaceItemStackHandlerOnContentsChanged(result, migration.handlerClassName, containerField)
    result = removeJavaMethodDeclarationsByNameContaining(result, "initStacks", listOf("ItemStack.parseOptional", "CustomData", "contents"))
    return result
}

internal fun StructuralRefactorPass.itemStackHandlerContainerFieldName(source: String, handlerClassName: String): String? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val type = javaTypeBlocks(source, executableCode).firstOrNull { it.name == handlerClassName } ?: return null
    val typeText = source.substring(type.bodyStart + 1, type.end)
    Regex("""(?m)\b(?:private|protected|public)?\s*(?:final\s+)?(?:net\.minecraft\.world\.item\.)?ItemStack\s+([A-Za-z_$][\w$]*)\s*;""")
        .find(typeText)
        ?.let { return it.groupValues[1] }
    javaConstructorRanges(typeText, handlerClassName).forEach { constructor ->
        val constructorText = typeText.substring(constructor.range)
        Regex("""this\.([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\s*;""")
            .find(constructorText)
            ?.let { return it.groupValues[1] }
    }
    return null
}

internal fun StructuralRefactorPass.migrateItemStackHandlerConstructorsToDataComponent(
    source: String,
    handlerClassName: String,
    containerFieldName: String
): String {
    var result = source
    for (constructor in javaConstructorRanges(result, handlerClassName).asReversed()) {
        val constructorText = result.substring(constructor.range)
        val migratedConstructor = replaceExecutableRegex(
            constructorText,
            Regex("""(?m)^([ \t]*)(?:this\.)?initStacks\s*\([^;]*\)\s*;\s*$""")
        ) { match ->
            "${match.groupValues[1]}$containerFieldName.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(stacks);"
        }
        if (migratedConstructor != constructorText) {
            result = result.substring(0, constructor.range.first) +
                migratedConstructor +
                result.substring(constructor.range.last + 1)
        }
    }
    return result
}

internal fun StructuralRefactorPass.replaceItemStackHandlerOnContentsChanged(
    source: String,
    handlerClassName: String,
    containerFieldName: String
): String {
    var result = source
    val executableCode = maskJavaCommentsAndLiterals(result)
    val type = javaTypeBlocks(result, executableCode).firstOrNull { it.name == handlerClassName } ?: return result
    val methods = javaMethodRangesIncludingDefault(executableCode)
        .filter { it.name == "onContentsChanged" && it.range.first in type.bodyStart..type.end }
    for (method in methods.asReversed()) {
        val openBrace = result.indexOf('{', method.range.first)
        if (openBrace < 0 || openBrace > method.range.last) continue
        val header = result.substring(method.range.first, openBrace).trimEnd()
        val indent = Regex("""(?m)^([ \t]*)""").find(header)?.groupValues?.get(1).orEmpty()
        val replacement = """$header {
$indent    $containerFieldName.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
$indent}"""
        result = result.substring(0, method.range.first) + replacement + result.substring(method.range.last + 1)
    }
    return result
}

internal fun StructuralRefactorPass.insertItemStackHandlerCapabilityRegistrationMethods(
    source: String,
    migration: ItemStackHandlerItemCapabilityMigration
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val classMatch = Regex("""\bclass\s+${Regex.escape(migration.className)}\b[^{]*\{""").find(executableCode)
        ?: return source
    val classOpen = source.indexOf('{', classMatch.range.last)
    if (classOpen < 0) return source
    val members = buildString {
        if (migration.fluidTemplate != null &&
            migration.dataComponentsFieldName != null &&
            migration.componentFieldName != null &&
            migration.componentPath != null &&
            migration.modIdExpression != null &&
            !source.contains("${migration.dataComponentsFieldName} = DeferredRegister.createDataComponents")
        ) {
            appendLine()
            appendLine("    public static final DeferredRegister.DataComponents ${migration.dataComponentsFieldName} = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ${migration.modIdExpression});")
            appendLine("    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SimpleFluidContent>> ${migration.componentFieldName} = ${migration.dataComponentsFieldName}.registerComponentType(\"${migration.componentPath}\", builder -> builder.persistent(SimpleFluidContent.CODEC).networkSynchronized(SimpleFluidContent.STREAM_CODEC));")
        }
        if (migration.fluidTemplate != null &&
            migration.componentFieldName != null &&
            !source.contains("registerFluidCapabilities(RegisterCapabilitiesEvent")
        ) {
            val anonymousBody = migration.fluidTemplate.anonymousBody
            appendLine()
            appendLine("    public static void registerFluidCapabilities(RegisterCapabilitiesEvent event, ItemLike... items) {")
            appendLine("        event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> new FluidHandlerItemStack(${migration.componentFieldName}, stack, ${migration.fluidTemplate.capacityExpression})$anonymousBody, items);")
            appendLine("    }")
        }
        if (!source.contains("registerItemCapabilities(RegisterCapabilitiesEvent")) {
            appendLine()
            appendLine("    public static void registerItemCapabilities(RegisterCapabilitiesEvent event, ItemLike... items) {")
            appendLine("        event.registerItem(Capabilities.ItemHandler.ITEM, (stack, context) -> new ${migration.handlerClassName}(stack, ${migration.handlerSizeExpression}), items);")
            appendLine("    }")
        }
    }
    if (members.isBlank()) return source
    return source.substring(0, classOpen + 1) + members + source.substring(classOpen + 1)
}

internal fun StructuralRefactorPass.migrateDirectFluidHandlerItemStackCapabilities(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val mainClass = detectModMainClass(projectDir) ?: return changes
    val mainText = mainClass.readText()
    val migrations = javaFiles.mapNotNull { javaFile ->
        directFluidHandlerItemStackCapabilityMigration(javaFile, javaFiles, mainClass, mainText)
    }
    if (migrations.isEmpty()) return changes

    for (migration in migrations) {
        var itemText = migration.itemFile.readText()
        val originalItemText = itemText
        itemText = replaceDirectFluidHandlerItemStackInitCapabilities(itemText, migration)
        itemText = addImportIfMissing(itemText, "net.minecraft.core.component.DataComponentType")
        itemText = addImportIfMissing(itemText, "net.minecraft.core.registries.Registries")
        itemText = addImportIfMissing(itemText, "net.minecraft.world.level.ItemLike")
        itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.capabilities.Capabilities")
        itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.fluids.SimpleFluidContent")
        itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.registries.DeferredHolder")
        itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.registries.DeferredRegister")
        itemText = removeUnusedSimpleImports(
            itemText,
            listOf(
                "net.minecraft.nbt.CompoundTag",
                "net.neoforged.neoforge.capabilities.ICapabilityProvider",
                "org.jetbrains.annotations.Nullable",
                "javax.annotation.Nullable"
            )
        )
        if (itemText != originalItemText) {
            if (!dryRun) {
                migration.itemFile.writeText(itemText)
            }
            changes.add(Change(
                file = migration.itemFile,
                line = 0,
                description = "Migrate direct FluidHandlerItemStack initCapabilities hook to registered item capability",
                before = "initCapabilities(...) -> new FluidHandlerItemStack(stack, capacity)",
                after = "DataComponentType<SimpleFluidContent> + RegisterCapabilitiesEvent.registerItem",
                confidence = Confidence.HIGH,
                ruleId = "struct-direct-fluid-handler-item-capability"
            ))
        }
    }

    var migratedMainText = mainText
    val originalMainText = migratedMainText
    val mainPackage = packageNameOf(migratedMainText)
    val constructorBusName = requireModEventBusName(migratedMainText, "direct fluid handler item capability registration")
    for (migration in migrations) {
        if (migration.packageName != mainPackage) {
            migratedMainText = addImportIfMissing(migratedMainText, "${migration.packageName}.${migration.className}")
        }
        migratedMainText = addImportIfMissing(migratedMainText, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        migration.itemReferences.map { it.qualifiedRegistryClass }
            .distinct()
            .filter { it.substringBeforeLast('.', "") != mainPackage }
            .forEach { migratedMainText = addImportIfMissing(migratedMainText, it) }

        if (!migratedMainText.contains("${migration.className}.${migration.dataComponentsFieldName}.register($constructorBusName)")) {
            val itemArguments = migration.itemReferences.joinToString(",\n") { ref ->
                "                ${ref.registryClassName}.${ref.fieldName}.get()"
            }
            val registration = """
        ${migration.className}.${migration.dataComponentsFieldName}.register($constructorBusName);
        $constructorBusName.addListener((RegisterCapabilitiesEvent event) -> ${migration.className}.registerFluidCapabilities(event,
$itemArguments
        ));
""".trimEnd()
            migratedMainText = insertModBusListener(
                migratedMainText,
                constructorBusName,
                registration,
                migration.itemReferences.first().registryClassName
            )
            changes.add(Change(
                file = mainClass,
                line = 0,
                description = "Register direct FluidHandlerItemStack data components and item capabilities on the mod event bus",
                before = "(no SimpleFluidContent data component / RegisterCapabilitiesEvent listener)",
                after = "${migration.className}.${migration.dataComponentsFieldName}.register(...) + registerFluidCapabilities(...)",
                confidence = Confidence.HIGH,
                ruleId = "struct-direct-fluid-handler-item-capability-listener"
            ))
        }
    }
    if (migratedMainText != originalMainText && !dryRun) {
        mainClass.writeText(migratedMainText)
    }

    return changes
}

internal fun StructuralRefactorPass.directFluidHandlerItemStackCapabilityMigration(
    javaFile: Path,
    javaFiles: List<Path>,
    mainClass: Path,
    mainText: String
): DirectFluidHandlerItemStackCapabilityMigration? {
    val source = javaFile.readText()
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("initCapabilities") || !executableCode.contains("FluidHandlerItemStack")) {
        return null
    }
    val className = classNameOfJavaSource(executableCode) ?: return null
    val method = javaMethodRangesIncludingDefault(executableCode).firstOrNull { candidate ->
        candidate.name == "initCapabilities" && executableCode.substring(candidate.range).contains("FluidHandlerItemStack")
    } ?: return null
    val parametersText = method.header.substringAfter("(", "").substringBeforeLast(")", "")
    val stackParameterName = splitTopLevelJavaArgs(parametersText)
        .firstOrNull { parameter -> parameter.contains("ItemStack") }
        ?.let(::javaParameterName)
        ?: return null
    val methodExecutable = executableCode.substring(method.range)
    val returnMatch = Regex("""(?s)\breturn\s+new\s+(?:[A-Za-z_$][\w$]*\.)*FluidHandlerItemStack\s*\(([^;{}]+)\)\s*;""")
        .find(methodExecutable)
        ?: return null
    val args = splitTopLevelJavaArgs(returnMatch.groupValues[1])
    if (args.size != 2 || args[0].trim() != stackParameterName) return null

    val itemReferences = findRegisteredItemReferences(javaFiles, className)
        .distinctBy { "${it.qualifiedRegistryClass}.${it.fieldName}" }
        .sortedWith(compareBy({ it.qualifiedRegistryClass }, { it.fieldName }))
    if (itemReferences.isEmpty()) return null

    val modIdExpression = inferModAccess(source)?.modIdExpression
        ?: explicitModIdReferenceForGeneratedClass(mainClass, mainText, packageNameOf(source), metadataModId = null)
        ?: return null
    return DirectFluidHandlerItemStackCapabilityMigration(
        itemFile = javaFile,
        packageName = packageNameOf(source),
        className = className,
        itemReferences = itemReferences,
        modIdExpression = modIdExpression,
        dataComponentsFieldName = uniqueJavaFieldName(source, "DATA_COMPONENTS"),
        componentFieldName = uniqueJavaFieldName(source, "FLUID_CONTENT"),
        componentPath = "${itemReferences.first().fieldName.lowercase()}_fluid_content",
        capacityExpression = args[1].trim()
    )
}

internal fun StructuralRefactorPass.replaceDirectFluidHandlerItemStackInitCapabilities(
    source: String,
    migration: DirectFluidHandlerItemStackCapabilityMigration
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val method = javaMethodRangesIncludingDefault(executableCode).firstOrNull { candidate ->
        candidate.name == "initCapabilities" && executableCode.substring(candidate.range).contains("FluidHandlerItemStack")
    } ?: return source
    var end = method.range.last + 1
    if (end < source.length && source[end] == '\r') end++
    if (end < source.length && source[end] == '\n') end++
    var result = source.substring(0, method.range.first) + source.substring(end)
    if (result.contains("registerFluidCapabilities(RegisterCapabilitiesEvent")) return result
    val classMatch = Regex("""\bclass\s+${Regex.escape(migration.className)}\b[^{]*\{""").find(maskJavaCommentsAndLiterals(result))
        ?: return result
    val classOpen = result.indexOf('{', classMatch.range.last)
    if (classOpen < 0) return result
    val members = """

    public static final DeferredRegister.DataComponents ${migration.dataComponentsFieldName} = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ${migration.modIdExpression});
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SimpleFluidContent>> ${migration.componentFieldName} = ${migration.dataComponentsFieldName}.registerComponentType("${migration.componentPath}", builder -> builder.persistent(SimpleFluidContent.CODEC).networkSynchronized(SimpleFluidContent.STREAM_CODEC));

    public static void registerFluidCapabilities(RegisterCapabilitiesEvent event, ItemLike... items) {
        event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> new FluidHandlerItemStack(${migration.componentFieldName}, stack, ${migration.capacityExpression}), items);
    }
"""
    return result.substring(0, classOpen + 1) + members + result.substring(classOpen + 1)
}

internal fun StructuralRefactorPass.uniqueJavaFieldName(source: String, baseName: String): String {
    var candidate = baseName
    var suffix = 2
    while (Regex("""(?<![\w$])${Regex.escape(candidate)}(?![\w$])""").containsMatchIn(source)) {
        candidate = "${baseName}_${suffix++}"
    }
    return candidate
}

internal fun StructuralRefactorPass.migrateCustomFluidItemCapabilities(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()

    val factories = javaFiles.mapNotNull { legacyFluidItemProviderFactory(it) }
    if (factories.isEmpty()) return changes

    for (factory in factories) {
        val registrations = legacyFluidItemProviderRegistrations(javaFiles, factory)
        if (registrations.isEmpty()) continue

        var capabilityText = factory.file.readText()
        val originalCapabilityText = capabilityText
        if (!capabilityText.contains("registerCapabilities(RegisterCapabilitiesEvent")) {
            val modAccess = inferModAccess(capabilityText)
            capabilityText = capabilityText.replace(
                modAccess?.let {
                    Regex("""(?m)^[ \t]*@EventBusSubscriber\(modid\s*=\s*${Regex.escape(it.modIdExpression)}\)\s*\r?\n""")
                } ?: Regex("""(?!)"""),
                ""
            )
            capabilityText = capabilityText.replace(
                Regex("""(?m)^[ \t]*public\s+static\s+final\s+ResourceLocation\s+[A-Za-z_$][\w$]*\s*=\s*[^;\r\n]+;\s*\r?\n"""),
                ""
            )
            capabilityText = removeMethodByName(capabilityText, "attachCapabilities")
            capabilityText = removeMethodsByNames(capabilityText, factory.providerMethodNames)
            factory.providerWrapperClassName?.let { wrapperName ->
                capabilityText = removeInnerClassByName(capabilityText, wrapperName)
            }
            capabilityText = addLegacyFluidItemRegisterCapabilitiesMethod(capabilityText, factory, registrations)

            listOf(
                "net.minecraft.core.Direction",
                "com.modporter.compat.Capability",
                "net.neoforged.neoforge.capabilities.ICapabilityProvider",
                "com.modporter.compat.LazyOptional",
                "net.neoforged.neoforge.event.AttachCapabilitiesEvent",
                "net.neoforged.bus.api.SubscribeEvent",
                "net.neoforged.fml.common.Mod",
                "javax.annotation.Nonnull",
                "org.jetbrains.annotations.Nullable",
                "net.neoforged.fml.common.EventBusSubscriber"
            ).plus(listOfNotNull(modAccess?.modImport)).forEach { importName ->
                capabilityText = capabilityText.replace(
                    Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)};\s*\r?\n"""),
                    ""
                )
            }
            capabilityText = addImportIfMissing(capabilityText, "net.neoforged.neoforge.capabilities.Capabilities")
            capabilityText = addImportIfMissing(capabilityText, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
            registrations
                .flatMap { it.itemReferences }
                .map { it.qualifiedRegistryClass }
                .distinct()
                .filter { it.substringBeforeLast('.', "") != factory.packageName }
                .forEach { capabilityText = addImportIfMissing(capabilityText, it) }
            capabilityText = removeUnusedSimpleImports(
                capabilityText,
                listOf(
                    "net.minecraft.resources.ResourceLocation",
                    "net.minecraft.world.item.Items"
                )
            )

            if (capabilityText != originalCapabilityText) {
                if (!dryRun) {
                    factory.file.writeText(capabilityText)
                }
                changes.add(Change(
                    file = factory.file,
                    line = 0,
                    description = "Migrate legacy fluid item capability provider wrapper to RegisterCapabilitiesEvent item registrations",
                    before = "AttachCapabilitiesEvent + ICapabilityProvider wrapper",
                    after = "RegisterCapabilitiesEvent.registerItem(Capabilities.FluidHandler.ITEM, ...)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-custom-fluid-item-capabilities"
                ))
            }
        }

        var removedItemHooks = false
        registrations.filter { it.obsoleteMixinFile != null }.map { it.obsoleteMixinFile!! }.distinct().forEach { mixinFile ->
            val mixinSource = mixinFile.readText()
            val mixinPackage = packageNameOf(mixinSource)
            val mixinClass = classNameOfJavaSource(maskJavaCommentsAndLiterals(mixinSource)) ?: return@forEach
            Files.walk(projectDir.resolve("src/main/resources"))
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(".mixins.json") }
                .forEach { config ->
                    val original = config.readText()
                    val configPackage = Regex(""""package"\s*:\s*"([^"]+)"""").find(original)?.groupValues?.get(1) ?: return@forEach
                    val relative = relativeMixinNameForConfigPackage(mixinPackage, mixinClass, configPackage) ?: return@forEach
                    val migrated = removeMixinClassFromConfigArrays(original, relative)
                    if (migrated != original && !dryRun) config.writeText(migrated)
                }
            if (!dryRun) Files.deleteIfExists(mixinFile)
            removedItemHooks = true
            changes.add(Change(
                file = mixinFile,
                line = 0,
                description = "Remove BucketItem#initCapabilities mixin; the provider is registered through RegisterCapabilitiesEvent.registerItem",
                before = "@Mixin(BucketItem.class) initCapabilities injection",
                after = "event.registerItem(Capabilities.FluidHandler.ITEM, ..., Items.BUCKET)",
                confidence = Confidence.HIGH,
                ruleId = "struct-custom-fluid-bucket-mixin"
            ))
        }
        registrations.filter { it.obsoleteMixinFile == null && it.itemFile != factory.file }.map { it.itemFile }.distinct().forEach { javaFile ->
            var text = javaFile.readText()
            val original = text
            val requiredCalls = factory.providerMethodNames.map { "${factory.className}.$it" }
            text = removeMethodByNameContaining(text, "initCapabilities", requiredCalls)
            text = text.replace(
                Regex("""(?m)^[ \t]*import\s+net\.neoforged\.neoforge\.capabilities\.ICapabilityProvider;\s*\r?\n"""),
                ""
            )
            if (!text.contains("CompoundTag")) {
                text = text.replace(Regex("""(?m)^[ \t]*import\s+net\.minecraft\.nbt\.CompoundTag;\s*\r?\n"""), "")
            }
            if (!text.contains("@Nullable")) {
                text = text.replace(Regex("""(?m)^[ \t]*import\s+org\.jetbrains\.annotations\.Nullable;\s*\r?\n"""), "")
            }
            if (text != original) {
                removedItemHooks = true
                if (!dryRun) {
                    javaFile.writeText(text)
                }
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Remove legacy initCapabilities hook for fluid item capability",
                    before = "initCapabilities(...) -> legacy fluid provider factory",
                    after = "Registered through RegisterCapabilitiesEvent.registerItem",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-custom-fluid-item-initcapabilities"
                ))
            }
        }

        if (removedItemHooks || capabilityText != originalCapabilityText) {
            val mainClass = detectModMainClass(projectDir)
            if (mainClass != null) {
                var mainText = mainClass.readText()
                val originalMainText = mainText
                val mainPackage = packageNameOf(mainText)
                val constructorBusName = requireModEventBusName(mainText, "fluid item capability listener registration")

                if (!mainText.contains("${factory.className}::registerCapabilities")) {
                    if (factory.packageName != mainPackage) {
                        mainText = addImportIfMissing(mainText, "${factory.packageName}.${factory.className}")
                    }
                    val registration = "        $constructorBusName.addListener(${factory.className}::registerCapabilities);"
                    mainText = insertModBusListener(
                        mainText,
                        constructorBusName,
                        registration,
                        registrations.flatMap { it.itemReferences }.firstOrNull()?.registryClassName ?: factory.className
                    )
                    changes.add(Change(
                        file = mainClass,
                        line = 0,
                        description = "Register legacy fluid item capabilities on the mod event bus",
                        before = "(no fluid item capability listener)",
                        after = "modEventBus.addListener(${factory.className}::registerCapabilities)",
                        confidence = Confidence.HIGH,
                        ruleId = "struct-custom-fluid-capability-listener"
                    ))
                }

                if (mainText != originalMainText && !dryRun) {
                    mainClass.writeText(mainText)
                }
            }
        }
    }

    return changes
}

internal fun StructuralRefactorPass.legacyFluidItemProviderFactory(file: Path): LegacyFluidItemProviderFactory? {
    val source = file.readText()
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("IFluidHandlerItem") || !executableCode.contains("ICapabilityProvider")) {
        return null
    }
    val className = classNameOfJavaSource(executableCode) ?: return null
    val handlerClassName = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private|static|final|abstract)\s+)*class\s+([A-Za-z_$][\w$]*)\b[^{;\r\n]*\bimplements\s+IFluidHandlerItem\b"""
    )
        .find(executableCode)
        ?.groupValues
        ?.get(1)
        ?: return null
    val providerWrapperClassName = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private|static|final|abstract)\s+)*class\s+([A-Za-z_$][\w$]*)\b[^{;\r\n]*\bimplements\s+ICapabilityProvider\b"""
    )
        .find(executableCode)
        ?.groupValues
        ?.get(1)
    val providerMethodNames = Regex(
        """(?m)\b(?:public|protected|private)\s+static\s+ICapabilityProvider\s+([A-Za-z_$][\w$]*)\s*\("""
    )
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    if (providerMethodNames.isEmpty()) return null
    return LegacyFluidItemProviderFactory(
        file = file,
        packageName = packageNameOf(source),
        className = className,
        handlerClassName = handlerClassName,
        providerWrapperClassName = providerWrapperClassName,
        providerMethodNames = providerMethodNames
    )
}

internal fun StructuralRefactorPass.legacyFluidItemProviderRegistrations(
    javaFiles: List<Path>,
    factory: LegacyFluidItemProviderFactory
): List<LegacyFluidItemProviderRegistration> =
    javaFiles.mapNotNull { javaFile ->
        if (javaFile == factory.file) return@mapNotNull null
        val source = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("initCapabilities") || !executableCode.contains("${factory.className}.")) {
            return@mapNotNull null
        }
        val itemClassName = classNameOfJavaSource(executableCode) ?: return@mapNotNull null
        val initCapabilities = javaMethodText(executableCode, "initCapabilities") ?: return@mapNotNull null
        val call = legacyFluidProviderFactoryCall(initCapabilities, factory) ?: return@mapNotNull null
        val itemReferences = findRegisteredItemReferences(javaFiles, itemClassName)
            .distinctBy { "${it.qualifiedRegistryClass}.${it.fieldName}" }
            .sortedWith(compareBy({ it.qualifiedRegistryClass }, { it.fieldName }))
        if (itemReferences.isEmpty()) return@mapNotNull null
        LegacyFluidItemProviderRegistration(
            itemFile = javaFile,
            itemClassName = itemClassName,
            itemReferences = itemReferences,
            handlerKindExpression = legacyFluidHandlerKindExpression(
                factory.file.readText(),
                call.first,
                call.second
            ),
            providerExpression = legacyFluidProviderExpression(javaFiles, factory, call.first, call.second)
        )
    } + legacyFluidAttachEventRegistrations(javaFiles, factory) + legacyFluidBucketMixinRegistrations(javaFiles, factory)

/**
 * `if (stack.is(Items.X)) event.addCapability(ID, Factory.createProvider(stack, kind));` inside the
 * factory's AttachCapabilitiesEvent<ItemStack> handler attaches the provider to a vanilla item.
 */
internal fun StructuralRefactorPass.legacyFluidAttachEventRegistrations(
    javaFiles: List<Path>,
    factory: LegacyFluidItemProviderFactory
): List<LegacyFluidItemProviderRegistration> {
    val source = factory.file.readText()
    val executable = maskJavaCommentsAndLiterals(source)
    val attach = javaMethodText(executable, "attachCapabilities") ?: return emptyList()
    val stackVariable = Regex("""\bItemStack\s+([A-Za-z_$][\w$]*)\s*=\s*event\.getObject\(\)""").find(attach)?.groupValues?.get(1)
        ?: return emptyList()
    val methodAlternatives = factory.providerMethodNames.joinToString("|") { Regex.escape(it) }
    val guarded = Regex(
        """if\s*\(\s*${Regex.escape(stackVariable)}\.is\(\s*([A-Za-z_$][\w$.]*(?:\.get\(\))?)\s*\)\s*\)\s*\{?\s*event\.addCapability\([^;]*?\b(?:${Regex.escape(factory.className)}\.)?($methodAlternatives)\s*\(\s*((?:[^()]|\([^()]*\))*?)\s*\)\s*\)\s*;"""
    )
    return guarded.findAll(attach).mapNotNull { match ->
        val args = splitTopLevelJavaArgs(match.groupValues[3])
        val provider = legacyFluidProviderExpression(javaFiles, factory, match.groupValues[2], args) ?: return@mapNotNull null
        LegacyFluidItemProviderRegistration(
            itemFile = factory.file,
            itemClassName = factory.className,
            itemReferences = emptyList(),
            handlerKindExpression = null,
            providerExpression = provider,
            itemExpressions = listOf(match.groupValues[1])
        )
    }.toList()
}

/**
 * A Mixin into Forge's BucketItem#initCapabilities returning the factory provider for a vanilla
 * item has no target on NeoForge (items register capabilities through RegisterCapabilitiesEvent).
 */
internal fun StructuralRefactorPass.legacyFluidBucketMixinRegistrations(
    javaFiles: List<Path>,
    factory: LegacyFluidItemProviderFactory
): List<LegacyFluidItemProviderRegistration> {
    val methodAlternatives = factory.providerMethodNames.joinToString("|") { Regex.escape(it) }
    return javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        // the injection names its target method in the annotation's string literal
        if (!source.contains("@Mixin") || !Regex("""@Inject\s*\((?:[^()]|\([^()]*\))*?\bmethod\s*=\s*\{?\s*"initCapabilities[("]""").containsMatchIn(source)) return@mapNotNull null
        val executable = maskJavaCommentsAndLiterals(source)
        if (!Regex("""@Mixin\s*\(\s*(?:value\s*=\s*)?BucketItem\.class""").containsMatchIn(executable)) return@mapNotNull null
        val injection = Regex(
            """if\s*\(\s*([A-Za-z_$][\w$]*)\.is\(\s*([A-Za-z_$][\w$.]*(?:\.get\(\))?)\s*\)\s*\)\s*\{?\s*[A-Za-z_$][\w$]*\.setReturnValue\(\s*(?:${Regex.escape(factory.className)}\.)?($methodAlternatives)\s*\(\s*((?:[^()]|\([^()]*\))*?)\s*\)\s*\)\s*;"""
        ).find(executable) ?: return@mapNotNull null
        val provider = legacyFluidProviderExpression(javaFiles, factory, injection.groupValues[3], splitTopLevelJavaArgs(injection.groupValues[4]))
            ?: return@mapNotNull null
        LegacyFluidItemProviderRegistration(
            itemFile = javaFile,
            itemClassName = classNameOfJavaSource(executable) ?: return@mapNotNull null,
            itemReferences = emptyList(),
            handlerKindExpression = null,
            providerExpression = provider,
            itemExpressions = listOf(injection.groupValues[2]),
            obsoleteMixinFile = javaFile
        )
    }
}

/**
 * The handler the registerItem lambda constructs for a factory call. The factory's boolean picks
 * a ternary branch; a branch constructing the LazyOptional provider wrapper means its wrapped
 * handler, a branch constructing another IFluidHandlerItem (for example a FluidBucketWrapper
 * subclass) is that construction itself.
 */
internal fun StructuralRefactorPass.legacyFluidProviderExpression(
    javaFiles: List<Path>,
    factory: LegacyFluidItemProviderFactory,
    methodName: String,
    callArgs: List<String>
): String? {
    if (callArgs.size < 2) return null
    val rawKind = callArgs[1].trim()
    if (rawKind != "true" && rawKind != "false") return null
    val executableCode = maskJavaCommentsAndLiterals(factory.file.readText())
    val branches = javaMethodRangesIncludingDefault(executableCode)
        .filter { it.name == methodName }
        .mapNotNull { method ->
            val parameters = javaMethodParameterText(method.header)?.let(::splitTopLevelJavaArgs) ?: return@mapNotNull null
            val stackParameter = parameters.getOrNull(0)?.let(::javaParameterName) ?: return@mapNotNull null
            val selectorParameter = parameters.getOrNull(1) ?: return@mapNotNull null
            if (javaParameterSimpleType(selectorParameter) != "boolean") return@mapNotNull null
            val selectorName = javaParameterName(selectorParameter) ?: return@mapNotNull null
            val methodText = executableCode.substring(method.range)
            val ternary = javaTernaryBranches(methodText, selectorName) ?: return@mapNotNull null
            val chosen = if (rawKind == "true") ternary.first else ternary.second
            Triple(stackParameter, chosen, methodText)
        }
        .distinct()
    val (stackParameter, chosen, _) = branches.singleOrNull() ?: return null
    val construction = Regex("""^new\s+([A-Za-z_$][\w$]*)\s*\(\s*${Regex.escape(stackParameter)}\s*\)$""").matchEntire(chosen) ?: return null
    val constructed = construction.groupValues[1]
    if (constructed == factory.providerWrapperClassName) return "new ${factory.handlerClassName}(stack)"
    val declaration = Regex("""\bclass\s+${Regex.escape(constructed)}\b[^{;]*""")
    val handlerLike = javaFiles.any { file ->
        val match = declaration.find(maskJavaCommentsAndLiterals(file.readText())) ?: return@any false
        match.value.contains("IFluidHandlerItem") || match.value.contains("FluidBucketWrapper") || match.value.contains("FluidHandlerItemStack")
    }
    return if (handlerLike) "new $constructed(stack)" else null
}

/** `selector ? first : second` with balanced parentheses, ending at the statement's `;`. */
internal fun StructuralRefactorPass.javaTernaryBranches(methodText: String, selectorName: String): Pair<String, String>? {
    val question = Regex("""\b${Regex.escape(selectorName)}\s*\?""").find(methodText) ?: return null
    var index = question.range.last + 1
    var depth = 0
    var colon = -1
    var end = -1
    while (index < methodText.length) {
        when (methodText[index]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> { if (depth == 0) { end = index; break }; depth-- }
            ':' -> if (depth == 0 && colon < 0) colon = index
            ';' -> if (depth == 0) { end = index; break }
        }
        index++
    }
    if (colon < 0 || end < 0) return null
    return methodText.substring(question.range.last + 1, colon).trim() to methodText.substring(colon + 1, end).trim()
}

internal fun StructuralRefactorPass.legacyFluidProviderFactoryCall(
    initCapabilitiesMethod: String,
    factory: LegacyFluidItemProviderFactory
): Pair<String, List<String>>? {
    val methodAlternatives = factory.providerMethodNames.joinToString("|") { Regex.escape(it) }
    val callPattern = Regex(
        """\b${Regex.escape(factory.className)}\.($methodAlternatives)\s*\("""
    )
    val match = callPattern.find(initCapabilitiesMethod) ?: return null
    val openParen = initCapabilitiesMethod.indexOf('(', match.range.last - 1)
    val closeParen = if (openParen >= 0) findMatchingParen(initCapabilitiesMethod, openParen) else -1
    if (closeParen < 0) return null
    val args = splitTopLevelJavaArgs(initCapabilitiesMethod.substring(openParen + 1, closeParen))
    if (args.isEmpty()) return null
    return match.groupValues[1] to args
}

internal fun StructuralRefactorPass.legacyFluidHandlerKindExpression(
    factorySource: String,
    methodName: String,
    callArgs: List<String>
): String? {
    if (callArgs.size < 2) return null
    val rawKind = callArgs[1].trim()
    if (rawKind != "true" && rawKind != "false") return rawKind

    val executableCode = maskJavaCommentsAndLiterals(factorySource)
    val candidates = javaMethodRangesIncludingDefault(executableCode)
        .filter { it.name == methodName }
        .mapNotNull { method ->
            val parameters = javaMethodParameterText(method.header)
                ?.let(::splitTopLevelJavaArgs)
                ?: return@mapNotNull null
            val selectorParameter = parameters.getOrNull(1) ?: return@mapNotNull null
            if (javaParameterSimpleType(selectorParameter) != "boolean") return@mapNotNull null
            val selectorName = javaParameterName(selectorParameter) ?: return@mapNotNull null
            val methodText = executableCode.substring(method.range)
            val ternary = Regex(
                """\b${Regex.escape(selectorName)}\s*\?\s*([^:;]+?)\s*:\s*([^);]+)"""
            ).find(methodText) ?: return@mapNotNull null
            if (rawKind == "true") ternary.groupValues[1].trim() else ternary.groupValues[2].trim()
        }
        .distinct()
    return candidates.singleOrNull()
}

internal fun StructuralRefactorPass.addLegacyFluidItemRegisterCapabilitiesMethod(
    source: String,
    factory: LegacyFluidItemProviderFactory,
    registrations: List<LegacyFluidItemProviderRegistration>
): String {
    if (source.contains("registerCapabilities(RegisterCapabilitiesEvent")) return source
    val classMatch = Regex("""\bclass\s+${Regex.escape(factory.className)}\b[^{]*\{""").find(source)
        ?: return source
    val classOpen = source.indexOf('{', classMatch.range.last)
    if (classOpen < 0) return source
    val registerLines = registrations.flatMap { registration ->
        val provider = registration.providerExpression ?: if (registration.handlerKindExpression == null) {
            "new ${factory.handlerClassName}(stack)"
        } else {
            "new ${factory.handlerClassName}(stack, ${registration.handlerKindExpression})"
        }
        val items = registration.itemReferences.map { ref -> "${ref.registryClassName}.${ref.fieldName}.get()" } +
            registration.itemExpressions
        items.map { item ->
            """
        event.registerItem(
                Capabilities.FluidHandler.ITEM,
                (stack, context) -> $provider,
                $item);
                """.trimEnd()
        }
    }.joinToString("\n")
    val method = """

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
$registerLines
    }
"""
    return source.substring(0, classOpen + 1) + method + source.substring(classOpen + 1)
}

internal fun StructuralRefactorPass.removeMethodsByNames(source: String, methodNames: Set<String>): String {
    var result = source
    var changed: Boolean
    do {
        changed = false
        for (methodName in methodNames) {
            val updated = removeMethodByName(result, methodName)
            if (updated != result) {
                result = updated
                changed = true
            }
        }
    } while (changed)
    return result
}

internal data class CuriosItemCapabilityMigration(
    val compatFile: Path,
    val compatPackageName: String,
    val compatClassName: String,
    val itemReferences: List<RegisteredItemReference>
)

internal data class LegacyCuriosProviderFactory(
    val file: Path,
    val packageName: String,
    val className: String,
    val methodName: String,
    val providerExpression: String
)

internal data class LegacyCuriosItemHook(
    val file: Path,
    val className: String
)

internal data class DirectLegacyCuriosItemHook(
    val file: Path,
    val packageName: String,
    val className: String,
    val providerExpression: String,
    val itemReferences: List<RegisteredItemReference>
)
