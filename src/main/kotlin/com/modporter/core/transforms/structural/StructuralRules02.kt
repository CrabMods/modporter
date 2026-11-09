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
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.*
import kotlin.streams.toList

// Structural migration rules, part 02 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateCuriosItemCapabilities, migrateLegacyWorldGenRegionStructureManagerAccessor, cleanupAttachmentRegistrationEventAnnotations, migrateProviderBackedEntityCapabilities, migrateCustomEntityCapabilities, migrateNitrogenAttachmentApi, migrateQuarkBackpackInventoryWrappers, detectRequiredGeneratedCompatPackage, migrateLegacyPackResourceApis, rewriteUnusedLegacyEnchantmentSubclasses, migrateBlockEntityFluidCapabilities, migrateBlockEntityLegacyCapabilities, migrateLegacyCapabilityFacadeLookups, migrateLegacyCapabilityFacadeToAttachment, migrateAdvancementCriterionTriggers, migrateLegacyConditionCodecRegistrations.

internal fun StructuralRefactorPass.migrateCuriosItemCapabilities(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()

    val directHooks = legacyDirectCuriosItemHooks(javaFiles)
    for (hook in directHooks) {
        var itemText = hook.file.readText()
        val originalItemText = itemText
        itemText = replaceDirectCuriosInitCapabilities(itemText, hook.providerExpression)
        itemText = addImportIfMissing(itemText, "net.minecraft.world.level.ItemLike")
        itemText = addImportIfMissing(itemText, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        itemText = addImportIfMissing(itemText, "top.theillusivec4.curios.api.CuriosCapability")
        itemText = cleanupDirectCuriosInitCapabilitiesImports(itemText)
        if (itemText != originalItemText) {
            if (!dryRun) {
                hook.file.writeText(itemText)
            }
            changes.add(Change(
                file = hook.file,
                line = 0,
                description = "Migrate direct legacy Curios Item#initCapabilities provider to RegisterCapabilitiesEvent item registration",
                before = "initCapabilities(...) -> CuriosApi.createCurioProvider(new ICurio() { ... })",
                after = "registerCuriosCapabilities(RegisterCapabilitiesEvent, ItemLike...)",
                confidence = Confidence.HIGH,
                ruleId = "struct-curios-direct-item-capability"
            ))
        }

        changes.addAll(registerCuriosItemCapabilitiesOnModBus(
            projectDir,
            CuriosItemCapabilityMigration(
                compatFile = hook.file,
                compatPackageName = hook.packageName,
                compatClassName = hook.className,
                itemReferences = hook.itemReferences
            ),
            dryRun
        ))
    }

    val factories = javaFiles.mapNotNull { legacyCuriosProviderFactory(it) }
    for (factory in factories) {
        val helperMethodNames = legacyCuriosHelperMethodNames(javaFiles, factory)
        val itemHooks = legacyCuriosItemHooks(javaFiles, factory, helperMethodNames)
        if (itemHooks.isEmpty()) continue

        val itemClassNames = itemHooks.mapTo(linkedSetOf()) { it.className }
        val itemReferences = itemClassNames
            .flatMap { itemClassName -> findRegisteredItemReferences(javaFiles, itemClassName) }
            .distinctBy { "${it.qualifiedRegistryClass}.${it.fieldName}" }
            .sortedWith(compareBy({ it.qualifiedRegistryClass }, { it.fieldName }))
        if (itemReferences.isEmpty()) continue

        var compatText = factory.file.readText()
        val originalCompatText = compatText
        compatText = replaceCuriosProviderFactory(compatText, factory.methodName, factory.providerExpression)
        compatText = removeImportIfPresent(compatText, "net.neoforged.neoforge.capabilities.ICapabilityProvider")
        compatText = removeImportIfPresent(compatText, "top.theillusivec4.curios.common.capability.CurioItemCapability")
        compatText = removeUnusedSimpleImports(compatText, listOf("top.theillusivec4.curios.api.CuriosApi"))
        compatText = addImportIfMissing(compatText, "net.minecraft.world.level.ItemLike")
        compatText = addImportIfMissing(compatText, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        compatText = addImportIfMissing(compatText, "top.theillusivec4.curios.api.CuriosCapability")
        compatText = removeUnusedImportsBySimpleNamePattern(compatText, Regex("""[A-Za-z_$][\w$]*PacketHandler"""))

        if (compatText != originalCompatText) {
            if (!dryRun) {
                factory.file.writeText(compatText)
            }
            changes.add(Change(
                file = factory.file,
                line = 0,
                description = "Migrate legacy Curios ICapabilityProvider factory to RegisterCapabilitiesEvent item registration",
                before = "CurioItemCapability.createProvider(new ICurio() { ... })",
                after = "event.registerItem(CuriosCapability.ITEM, (stack, context) -> new ICurio() { ... }, items)",
                confidence = Confidence.HIGH,
                ruleId = "struct-curios-item-capability"
            ))
        }

        val removalNeedles = curiosFactoryCallNeedles(factory) + helperMethodNames
        val itemHookFiles = itemHooks.mapTo(linkedSetOf()) { it.file }
        javaFiles.filter { it != factory.file }.forEach { javaFile ->
            var text = javaFile.readText()
            val original = text
            if (javaFile in itemHookFiles) {
                text = removeMethodByNameContaining(text, "initCapabilities", removalNeedles)
            }
            helperMethodNames.forEach { helperMethod ->
                text = removeMethodByNameContaining(text, helperMethod, curiosFactoryCallNeedles(factory))
            }
            text = removeUnusedSimpleImports(
                text,
                listOf(
                    "net.minecraft.nbt.CompoundTag",
                    "net.minecraft.world.item.ItemStack",
                    "net.neoforged.neoforge.capabilities.ICapabilityProvider",
                    "net.neoforged.fml.ModList",
                    "org.jetbrains.annotations.Nullable",
                    "${factory.packageName}.${factory.className}"
                )
            )

            if (text != original) {
                if (!dryRun) {
                    javaFile.writeText(text)
                }
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Remove legacy Item#initCapabilities Curios hook after event registration migration",
                    before = "initCapabilities(...) -> Curios capability provider",
                    after = "Registered through RegisterCapabilitiesEvent.registerItem",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-curios-item-initcapabilities"
                ))
            }
        }

        changes.addAll(registerCuriosItemCapabilitiesOnModBus(
            projectDir,
            CuriosItemCapabilityMigration(
                compatFile = factory.file,
                compatPackageName = factory.packageName,
                compatClassName = factory.className,
                itemReferences = itemReferences
            ),
            dryRun
        ))
    }

    return changes
}

internal fun StructuralRefactorPass.legacyCuriosProviderFactory(file: Path): LegacyCuriosProviderFactory? {
    val source = file.readText()
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!legacyCuriosProviderFactoryNames.any { executableCode.contains(it) } || !executableCode.contains("new ICurio")) {
        return null
    }
    val className = classNameOfJavaSource(executableCode) ?: return null
    val method = javaMethodRangesIncludingDefault(executableCode).firstOrNull { candidate ->
        val methodText = executableCode.substring(candidate.range)
        legacyCuriosProviderFactoryNames.any { methodText.contains(it) } && methodText.contains("new ICurio")
    } ?: return null
    if (method.name == "initCapabilities") return null
    val providerExpression = extractCuriosProviderExpression(source, executableCode, method.range) ?: return null
    return LegacyCuriosProviderFactory(
        file = file,
        packageName = packageNameOf(source),
        className = className,
        methodName = method.name,
        providerExpression = providerExpression
    )
}

internal fun StructuralRefactorPass.extractCuriosProviderExpression(
    source: String,
    executableCode: String,
    methodRange: IntRange
): String? {
    val methodText = executableCode.substring(methodRange)
    val factoryOffset = legacyCuriosProviderFactoryNames
        .map { methodText.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull()
        ?: return null
    if (factoryOffset < 0) return null
    val newOffset = methodText.indexOf("new ICurio", factoryOffset)
    if (newOffset < 0) return null
    val newIndex = methodRange.first + newOffset
    val openBrace = executableCode.indexOf('{', newIndex)
    if (openBrace < 0) return null
    val closeBrace = findMatchingBrace(executableCode, openBrace)
    if (closeBrace < 0 || closeBrace !in methodRange) return null
    return source.substring(newIndex, closeBrace + 1)
}

internal fun StructuralRefactorPass.replaceCuriosProviderFactory(source: String, methodName: String, providerExpression: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val method = javaMethodRangesIncludingDefault(executableCode).firstOrNull { candidate ->
        candidate.name == methodName &&
            legacyCuriosProviderFactoryNames.any { executableCode.substring(candidate.range).contains(it) } &&
            executableCode.substring(candidate.range).contains("new ICurio")
    } ?: return source
    var end = method.range.last + 1
    if (end < source.length && source[end] == '\r') end++
    if (end < source.length && source[end] == '\n') end++

    val migratedProvider = migrateCuriosProviderExpression(providerExpression)
    val replacement = """
	public static void registerCuriosCapabilities(RegisterCapabilitiesEvent event, ItemLike... items) {
		event.registerItem(CuriosCapability.ITEM, (stack, context) -> $migratedProvider, items);
	}

"""
    return source.substring(0, method.range.first) + replacement + source.substring(end)
}

internal fun StructuralRefactorPass.legacyDirectCuriosItemHooks(javaFiles: List<Path>): List<DirectLegacyCuriosItemHook> =
    javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("initCapabilities") ||
            !executableCode.contains("new ICurio") ||
            legacyCuriosProviderFactoryNames.none { executableCode.contains(it) }
        ) {
            return@mapNotNull null
        }
        val className = classNameOfJavaSource(executableCode) ?: return@mapNotNull null
        val initCapabilities = javaMethodRangesIncludingDefault(executableCode).firstOrNull { method ->
            method.name == "initCapabilities" &&
                method.header.contains("ItemStack") &&
                legacyCuriosProviderFactoryNames.any { executableCode.substring(method.range).contains(it) } &&
                executableCode.substring(method.range).contains("new ICurio")
        } ?: return@mapNotNull null
        val providerExpression = extractCuriosProviderExpression(source, executableCode, initCapabilities.range)
            ?: return@mapNotNull null
        val itemReferences = findRegisteredItemReferences(javaFiles, className)
            .distinctBy { "${it.qualifiedRegistryClass}.${it.fieldName}" }
            .sortedWith(compareBy({ it.qualifiedRegistryClass }, { it.fieldName }))
        if (itemReferences.isEmpty()) return@mapNotNull null
        DirectLegacyCuriosItemHook(
            file = javaFile,
            packageName = packageNameOf(source),
            className = className,
            providerExpression = providerExpression,
            itemReferences = itemReferences
        )
    }

internal fun StructuralRefactorPass.replaceDirectCuriosInitCapabilities(source: String, providerExpression: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val method = javaMethodRangesIncludingDefault(executableCode).firstOrNull { candidate ->
        candidate.name == "initCapabilities" &&
            legacyCuriosProviderFactoryNames.any { executableCode.substring(candidate.range).contains(it) } &&
            executableCode.substring(candidate.range).contains("new ICurio")
    } ?: return source
    var end = method.range.last + 1
    if (end < source.length && source[end] == '\r') end++
    if (end < source.length && source[end] == '\n') end++

    val migratedProvider = migrateCuriosProviderExpression(providerExpression)
    val replacement = """
    public static void registerCuriosCapabilities(RegisterCapabilitiesEvent event, ItemLike... items) {
        event.registerItem(CuriosCapability.ITEM, (stack, context) -> $migratedProvider, items);
    }

"""
    return source.substring(0, method.range.first) + replacement + source.substring(end)
}

internal fun StructuralRefactorPass.legacyCuriosHelperMethodNames(
    javaFiles: List<Path>,
    factory: LegacyCuriosProviderFactory
): Set<String> =
    javaFiles
        .filter { it != factory.file }
        .flatMap { javaFile ->
            val executableCode = maskJavaCommentsAndLiterals(javaFile.readText())
            if (curiosFactoryCallNeedles(factory).none { executableCode.contains(it) }) {
                emptyList()
            } else {
                javaMethodRangesIncludingDefault(executableCode)
                    .filter { method ->
                        method.name != "initCapabilities" &&
                            curiosFactoryCallNeedles(factory).any { executableCode.substring(method.range).contains(it) }
                    }
                    .map { it.name }
            }
        }
        .toSet()

internal fun StructuralRefactorPass.legacyCuriosItemHooks(
    javaFiles: List<Path>,
    factory: LegacyCuriosProviderFactory,
    helperMethodNames: Set<String>
): List<LegacyCuriosItemHook> {
    val factoryNeedles = curiosFactoryCallNeedles(factory)
    return javaFiles.mapNotNull { javaFile ->
        if (javaFile == factory.file) return@mapNotNull null
        val source = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(source)
        val hasCapabilityHookEvidence = factoryNeedles.any { executableCode.contains(it) } ||
            helperMethodNames.any { helperName -> executableCode.contains("$helperName(") }
        if (!hasCapabilityHookEvidence) return@mapNotNull null
        val itemClassName = classNameOfJavaSource(executableCode) ?: return@mapNotNull null
        javaMethodRangesIncludingDefault(executableCode).firstOrNull { method ->
            method.name == "initCapabilities" &&
                curiosItemHookMethodCalls(executableCode.substring(method.range), factory, helperMethodNames)
        } ?: return@mapNotNull null
        LegacyCuriosItemHook(javaFile, itemClassName)
    }
}

internal fun StructuralRefactorPass.curiosItemHookMethodCalls(
    methodText: String,
    factory: LegacyCuriosProviderFactory,
    helperMethodNames: Set<String>
): Boolean =
    curiosFactoryCallNeedles(factory).any { methodText.contains(it) } ||
        helperMethodNames.any { helperName ->
            Regex("""(?:^|[^\w$])(?:this\.)?${Regex.escape(helperName)}\s*\(""").containsMatchIn(methodText)
        }

internal fun StructuralRefactorPass.curiosFactoryCallNeedles(factory: LegacyCuriosProviderFactory): List<String> =
    listOf("${factory.className}.${factory.methodName}")

internal val legacyCuriosProviderFactoryNames = listOf(
    "CurioItemCapability.createProvider",
    "CuriosApi.createCurioProvider"
)

internal fun StructuralRefactorPass.cleanupDirectCuriosInitCapabilitiesImports(source: String): String =
    cleanupRedundantBlankLines(
        removeUnusedSimpleImports(
            source,
            listOf(
                "net.minecraft.nbt.CompoundTag",
                "net.neoforged.neoforge.capabilities.ICapabilityProvider",
                "top.theillusivec4.curios.api.CuriosApi",
                "org.jetbrains.annotations.Nullable",
                "javax.annotation.Nullable"
            )
        )
    )

internal fun StructuralRefactorPass.migrateCuriosProviderExpression(providerExpression: String): String {
    var result = providerExpression
    result = Regex("""new\s+SoundInfo\(\s*(SoundEvents\.[A-Za-z0-9_]+)\s*,""")
        .replace(result) { match -> "new SoundInfo(${match.groupValues[1]}.value()," }
    result = Regex(
        """[A-Za-z_$][\w$.]*\.CHANNEL\.send\(\s*PacketDistributor\.TRACKING_ENTITY_AND_SELF\.with\(([^)]*)\)\s*,\s*([^;]+)\);"""
    ).replace(result) { match ->
        val target = packetDistributorTargetExpression(match.groupValues[1].trim())
        "PacketDistributor.sendToPlayersTrackingEntityAndSelf($target, ${match.groupValues[2].trim()});"
    }
    return result
}

internal fun StructuralRefactorPass.packetDistributorTargetExpression(distributorArgument: String): String {
    val methodReference = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::([A-Za-z_$][\w$]*)""")
        .matchEntire(distributorArgument)
    if (methodReference != null) {
        return "${methodReference.groupValues[1]}.${methodReference.groupValues[2]}()"
    }
    return distributorArgument
}

internal fun StructuralRefactorPass.registerCuriosItemCapabilitiesOnModBus(
    projectDir: Path,
    migration: CuriosItemCapabilityMigration,
    dryRun: Boolean
): List<Change> {
    val mainClass = detectModMainClass(projectDir) ?: return emptyList()
    var mainText = mainClass.readText()
    if (mainText.contains("${migration.compatClassName}::registerCuriosCapabilities") ||
        mainText.contains("${migration.compatClassName}.registerCuriosCapabilities(event")
    ) {
        return emptyList()
    }
    val originalMainText = mainText
    val mainPackage = packageNameOf(mainText)
    val constructorBusName = requireModEventBusName(mainText, "Curios item capability listener registration")

    if (migration.compatPackageName != mainPackage) {
        mainText = addImportIfMissing(mainText, "${migration.compatPackageName}.${migration.compatClassName}")
    }
    mainText = addImportIfMissing(mainText, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
    migration.itemReferences.map { it.qualifiedRegistryClass }
        .distinct()
        .filter { it.substringBeforeLast('.', "") != mainPackage }
        .forEach { mainText = addImportIfMissing(mainText, it) }

    val itemArguments = migration.itemReferences.joinToString(",\n") { ref ->
        "                ${ref.registryClassName}.${ref.fieldName}.get()"
    }
    val registration = """
        $constructorBusName.addListener((RegisterCapabilitiesEvent event) -> ${migration.compatClassName}.registerCuriosCapabilities(event,
$itemArguments
        ));
""".trimEnd()
    val mainWithConditionalRegistration = insertIntoModLoadedBlock(
        mainText,
        "curios",
        registration.prependIndent("    ")
    )
    mainText = mainWithConditionalRegistration ?: insertModBusListener(
        mainText,
        constructorBusName,
        registration,
        migration.itemReferences.first().registryClassName
    )

    if (mainText != originalMainText && !dryRun) {
        mainClass.writeText(mainText)
    }

    return listOf(Change(
        file = mainClass,
        line = 0,
        description = "Register Curios item capability providers on the mod event bus",
        before = "(no RegisterCapabilitiesEvent Curios item listener)",
        after = "${migration.compatClassName}.registerCuriosCapabilities(event, ...)",
        confidence = Confidence.HIGH,
        ruleId = "struct-curios-item-capability-listener"
    ))
}

internal fun StructuralRefactorPass.insertIntoModLoadedBlock(source: String, modId: String, registration: String): String? {
    val modLoadedPattern = Regex(
        """if\s*\(\s*ModList\.get\(\)\.isLoaded\(\s*"${Regex.escape(modId)}"\s*\)\s*\)\s*\{"""
    )
    for (match in modLoadedPattern.findAll(source)) {
        val openBrace = source.indexOf('{', match.range.first)
        if (openBrace < 0) continue
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) continue
        val block = source.substring(openBrace + 1, closeBrace)
        if (block.contains(registration.trim())) return source
        return source.substring(0, openBrace + 1) + "\n" + registration + source.substring(openBrace + 1)
    }
    return null
}

internal fun StructuralRefactorPass.classNameOfJavaSource(source: String): String? =
    Regex("""(?m)^[ \t]*(?:(?:@[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\s*\([^;\r\n]*\))?|public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)\b""")
        .find(source)
        ?.groupValues
        ?.get(1)

internal fun StructuralRefactorPass.javaTopLevelTypeIsPublic(source: String, typeName: String): Boolean {
    val modifiers = Regex("""(?m)^[ \t]*((?:(?:@[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\s*\([^;\r\n]*\))?|public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*)(?:class|interface|enum|record)\s+${Regex.escape(typeName)}\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return false
    return Regex("""\bpublic\b""").containsMatchIn(modifiers)
}

internal fun StructuralRefactorPass.removeMethodByNameContaining(source: String, methodName: String, requiredNeedles: List<String>): String {
    var result = source
    var searchStart = 0
    while (searchStart < result.length) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val methodNameIndex = Regex("""\b${Regex.escape(methodName)}\s*\(""")
            .find(executableResult, searchStart)
            ?.range
            ?.first
            ?: return result
        val openBrace = executableResult.indexOf('{', methodNameIndex)
        if (openBrace < 0) return result
        val closeBrace = findMatchingBrace(executableResult, openBrace)
        if (closeBrace < 0) return result
        val signatureStart = result.lastIndexOf('\n', methodNameIndex).let { if (it < 0) 0 else it + 1 }
        val annotationStart = Regex("""(?m)(?:^[ \t]*@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\r?\n)+[ \t]*$""")
            .findAll(result.substring(0, signatureStart))
            .lastOrNull()
            ?.range
            ?.first
            ?: signatureStart
        val executableMethodText = executableResult.substring(annotationStart, closeBrace + 1)
        if (requiredNeedles.none { executableMethodText.contains(it) }) {
            searchStart = closeBrace + 1
            continue
        }
        var end = closeBrace + 1
        if (end < result.length && result[end] == '\r') end++
        if (end < result.length && result[end] == '\n') end++
        result = result.removeRange(annotationStart, end)
        searchStart = annotationStart
    }
    return result
}

internal fun StructuralRefactorPass.removeJavaMethodDeclarationsByNameContaining(
    source: String,
    methodName: String,
    requiredNeedles: List<String>
): String {
    var result = source
    val methods = javaMethodRangesIncludingDefault(source)
        .filter { it.name == methodName }
        .filter { method ->
            val methodText = source.substring(method.range)
            requiredNeedles.any { methodText.contains(it) }
        }
    for (method in methods.asReversed()) {
        val signatureStart = result.lastIndexOf('\n', method.range.first).let { if (it < 0) 0 else it + 1 }
        val annotationStart = Regex("""(?m)(?:^[ \t]*@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\r?\n)+[ \t]*$""")
            .findAll(result.substring(0, signatureStart))
            .lastOrNull()
            ?.range
            ?.first
            ?: signatureStart
        var end = method.range.last + 1
        if (end < result.length && result[end] == '\r') end++
        if (end < result.length && result[end] == '\n') end++
        result = result.removeRange(annotationStart, end)
    }
    return result
}

internal fun StructuralRefactorPass.removeUnusedSimpleImports(source: String, importNames: List<String>): String {
    var result = source
    for (importName in importNames) {
        val withoutImport = removeImportIfPresent(result, importName)
        val simpleName = importName.substringAfterLast('.')
        if (!Regex("""\b${Regex.escape(simpleName)}\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutImport))) {
            result = withoutImport
        }
    }
    return result
}

internal fun StructuralRefactorPass.removeUnusedImportsBySimpleNamePattern(source: String, simpleNamePattern: Regex): String {
    var result = source
    val imports = Regex("""(?m)^[ \t]*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.([A-Za-z_$][\w$]*));\s*\r?\n""")
        .findAll(source)
        .map { it.groupValues[1] to it.groupValues[2] }
        .toList()
    for ((importName, simpleName) in imports) {
        if (!simpleNamePattern.matches(simpleName)) continue
        val withoutImport = removeImportIfPresent(result, importName)
        if (!Regex("""\b${Regex.escape(simpleName)}\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutImport))) {
            result = withoutImport
        }
    }
    return result
}

internal data class WorldGenRegionStructureManagerAccessor(
    val file: Path,
    val packageName: String,
    val simpleName: String,
    val methodName: String
) {
    val fqn: String
        get() = if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
}

internal fun StructuralRefactorPass.migrateLegacyWorldGenRegionStructureManagerAccessor(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    if (javaFiles.isEmpty()) return emptyList()

    val sourcesByFile = javaFiles.associateWith { it.readText() }.toMutableMap()
    val accessors = sourcesByFile.mapNotNull { (file, source) ->
        legacyWorldGenRegionStructureManagerAccessor(file, source)
    }
    if (accessors.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    for (accessor in accessors) {
        for ((javaFile, original) in sourcesByFile.toMap()) {
            if (javaFile == accessor.file) continue
            var migrated = rewriteWorldGenRegionStructureManagerAccessorCalls(
                original,
                accessor.simpleName,
                accessor.methodName
            )
            migrated = removeUnusedSimpleImport(migrated, accessor.fqn, accessor.simpleName)
            if (migrated == original) continue

            sourcesByFile[javaFile] = migrated
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Replace obsolete WorldGenRegion structureManager accessor calls with the public level structure manager API",
                before = "((${accessor.simpleName}) receiver).${accessor.methodName}()",
                after = "receiver.getLevel().structureManager()",
                confidence = Confidence.HIGH,
                ruleId = "struct-worldgenregion-structuremanager-accessor-call"
            ))
            if (!dryRun) javaFile.writeText(migrated)
        }

        val stillReferenced = sourcesByFile.any { (file, source) ->
            file != accessor.file && hasSimpleTypeReference(source, accessor.simpleName)
        }
        if (stillReferenced) continue

        val configChanges = removeMixinAccessorFromConfigs(projectDir, accessor, dryRun)
        changes.addAll(configChanges)

        changes.add(Change(
            file = accessor.file,
            line = 0,
            description = "Remove obsolete WorldGenRegion structureManager accessor mixin after call sites use public API",
            before = "@Accessor(\"structureManager\")",
            after = "WorldGenRegion#getLevel().structureManager()",
            confidence = Confidence.HIGH,
            ruleId = "struct-worldgenregion-structuremanager-accessor-remove"
        ))
        if (!dryRun) Files.deleteIfExists(accessor.file)
        sourcesByFile.remove(accessor.file)
    }
    return changes
}

internal fun StructuralRefactorPass.legacyWorldGenRegionStructureManagerAccessor(
    file: Path,
    source: String
): WorldGenRegionStructureManagerAccessor? {
    if (!source.contains("@Accessor(\"structureManager\")") || !source.contains("WorldGenRegion")) return null
    val targetsWorldGenRegion = Regex(
        """@Mixin\s*\(\s*(?:WorldGenRegion|net\.minecraft\.server\.level\.WorldGenRegion)\.class\s*\)"""
    ).containsMatchIn(source)
    if (!targetsWorldGenRegion) return null
    val methodName = Regex(
        """(?m)^[ \t]*(?:public\s+)?(?:StructureManager|net\.minecraft\.world\.level\.StructureManager)\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*;"""
    ).find(source)?.groupValues?.get(1) ?: return null
    val simpleName = javaTopLevelTypeName(source) ?: return null
    return WorldGenRegionStructureManagerAccessor(
        file = file,
        packageName = packageNameOf(source),
        simpleName = simpleName,
        methodName = methodName
    )
}

internal fun StructuralRefactorPass.rewriteWorldGenRegionStructureManagerAccessorCalls(
    source: String,
    accessorSimpleName: String,
    methodName: String
): String {
    var result = source
    var cursor = 0
    val castPrefix = Regex("""\(\(\s*${Regex.escape(accessorSimpleName)}\s*\)""")
    while (true) {
        val match = castPrefix.find(result, cursor) ?: break
        val receiverStart = match.range.last + 1
        val receiverEnd = findAccessorCastReceiverEnd(result, receiverStart)
        if (receiverEnd < 0) {
            cursor = match.range.last + 1
            continue
        }
        val afterReceiver = result.substring(receiverEnd + 1)
        val methodCall = Regex("""^\s*\.\s*${Regex.escape(methodName)}\s*\(\s*\)""").find(afterReceiver)
        if (methodCall == null) {
            cursor = receiverEnd + 1
            continue
        }
        val receiver = result.substring(receiverStart, receiverEnd).trim()
        if (receiver.isBlank()) {
            cursor = receiverEnd + 1
            continue
        }
        val replacement = "${chainableReceiverExpression(receiver)}.getLevel().structureManager()"
        val replaceEnd = receiverEnd + 1 + methodCall.range.last + 1
        result = result.substring(0, match.range.first) + replacement + result.substring(replaceEnd)
        cursor = match.range.first + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.findAccessorCastReceiverEnd(source: String, receiverStart: Int): Int {
    var depth = 0
    var i = receiverStart
    while (i < source.length) {
        when (source[i]) {
            '"' -> i = skipJavaStringLiteral(source, i)
            '\'' -> i = skipJavaCharLiteral(source, i)
            '(' -> depth++
            ')' -> {
                if (depth == 0) return i
                depth--
            }
        }
        i++
    }
    return -1
}

internal fun StructuralRefactorPass.skipJavaStringLiteral(source: String, quoteIndex: Int): Int {
    var i = quoteIndex + 1
    while (i < source.length) {
        if (source[i] == '\\') {
            i += 2
            continue
        }
        if (source[i] == '"') return i
        i++
    }
    return source.length - 1
}

internal fun StructuralRefactorPass.skipJavaCharLiteral(source: String, quoteIndex: Int): Int {
    var i = quoteIndex + 1
    while (i < source.length) {
        if (source[i] == '\\') {
            i += 2
            continue
        }
        if (source[i] == '\'') return i
        i++
    }
    return source.length - 1
}

internal fun StructuralRefactorPass.chainableReceiverExpression(expression: String): String =
    if (Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^()]*\))?|\[[^\]]+\])*""").matches(expression)) {
        expression
    } else {
        "($expression)"
    }

internal fun StructuralRefactorPass.removeMixinAccessorFromConfigs(
    projectDir: Path,
    accessor: WorldGenRegionStructureManagerAccessor,
    dryRun: Boolean
): List<Change> {
    val resourcesDir = projectDir.resolve("src/main/resources")
    if (!resourcesDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    Files.walk(resourcesDir)
        .filter { it.isRegularFile() && it.fileName.toString().endsWith(".mixins.json") }
        .forEach { config ->
            val original = config.readText()
            val configPackage = Regex(""""package"\s*:\s*"([^"]+)"""")
                .find(original)
                ?.groupValues
                ?.get(1)
                ?: return@forEach
            val relativeMixinName = relativeMixinNameForConfigPackage(
                accessor.packageName,
                accessor.simpleName,
                configPackage
            ) ?: return@forEach
            val migrated = removeMixinClassFromConfigArrays(original, relativeMixinName)
            if (migrated == original) return@forEach
            changes.add(Change(
                file = config,
                line = 0,
                description = "Remove obsolete WorldGenRegion structureManager accessor from mixin config",
                before = "\"$relativeMixinName\"",
                after = "public WorldGenRegion#getLevel().structureManager() call sites",
                confidence = Confidence.HIGH,
                ruleId = "struct-worldgenregion-structuremanager-mixin-config"
            ))
            if (!dryRun) config.writeText(migrated)
        }
    return changes
}

internal fun StructuralRefactorPass.relativeMixinNameForConfigPackage(
    mixinPackageName: String,
    mixinClassName: String,
    configPackageName: String
): String? = when {
    mixinPackageName == configPackageName -> mixinClassName
    mixinPackageName.startsWith("$configPackageName.") ->
        mixinPackageName.removePrefix("$configPackageName.") + "." + mixinClassName
    else -> null
}

internal fun StructuralRefactorPass.removeMixinClassFromConfigArrays(source: String, mixinClassName: String): String {
    var result = source
    for (arrayName in listOf("mixins", "client", "server")) {
        result = removeMixinClassFromConfigArray(result, arrayName, mixinClassName)
    }
    return result
}

internal fun StructuralRefactorPass.removeMixinClassFromConfigArray(source: String, arrayName: String, mixinClassName: String): String {
    val arrayMatch = Regex(""""${Regex.escape(arrayName)}"\s*:\s*\[""").find(source) ?: return source
    val openBracket = source.indexOf('[', arrayMatch.range.first)
    val closeBracket = if (openBracket >= 0) findMatchingJsonBracket(source, openBracket) else -1
    if (closeBracket <= openBracket) return source
    val inside = source.substring(openBracket + 1, closeBracket)
    val entries = Regex(""""([^"]+)"""")
        .findAll(inside)
        .map { it.groupValues[1] }
        .toList()
    if (mixinClassName !in entries) return source
    val remaining = entries.filterNot { it == mixinClassName }
    val arrayIndent = Regex("""(?m)^([ \t]*)"${Regex.escape(arrayName)}"""")
        .find(source.substring(0, arrayMatch.range.last + 1))
        ?.groupValues
        ?.get(1)
        ?: "  "
    val entryIndent = Regex("""\r?\n([ \t]*)"""")
        .find(inside)
        ?.groupValues
        ?.get(1)
        ?: "$arrayIndent  "
    val replacement = if (remaining.isEmpty()) {
        "\n$arrayIndent"
    } else {
        "\n" + remaining.joinToString(",\n") { "$entryIndent\"$it\"" } + "\n$arrayIndent"
    }
    return source.substring(0, openBracket + 1) + replacement + source.substring(closeBracket)
}

internal fun StructuralRefactorPass.findMatchingJsonBracket(source: String, openBracket: Int): Int {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in openBracket until source.length) {
        val c = source[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return -1
}

internal fun StructuralRefactorPass.cleanupAttachmentRegistrationEventAnnotations(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
        .forEach { file ->
            val original = file.readText()
            val executableOriginal = maskJavaCommentsAndLiterals(original)
            if (!executableOriginal.contains("registerAttachments(IEventBus") ||
                !Regex("""@\s*SubscribeEvent\b""").containsMatchIn(executableOriginal)) {
                return@forEach
            }
            var modified = replaceExecutableRegex(
                original,
                Regex("""(?m)^([ \t]*)@SubscribeEvent[ \t]*(?:\r?\n[ \t]*)+((?:public|protected|private)\s+static\s+void\s+registerAttachments\s*\(\s*IEventBus\b)""")
            ) { match -> match.groupValues[1] + match.groupValues[2] }
            if (modified != original) {
                if (!Regex("""@\s*SubscribeEvent\b""").containsMatchIn(maskJavaCommentsAndLiterals(modified))) {
                    modified = replaceExecutableRegex(
                        modified,
                        Regex("""(?m)^[ \t]*@(?:Mod\.)?EventBusSubscriber\b[^\r\n]*\r?\n""")
                    ) { "" }
                    modified = removeExecutableImport(modified, "net.neoforged.fml.common.EventBusSubscriber")
                }
                modified = removeUnusedSimpleImports(modified, listOf(
                    "net.neoforged.bus.api.SubscribeEvent",
                    "net.neoforged.fml.common.Mod"
                ))
                if (!dryRun) file.writeText(modified)
                changes.add(Change(
                    file = file,
                    line = 1,
                    description = "Remove event-subscriber wiring from attachment registration helper",
                    before = "@SubscribeEvent public static void registerAttachments(IEventBus)",
                    after = "public static void registerAttachments(IEventBus)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-attachment-registration-event-cleanup"
                ))
            }
        }
    return changes
}

internal data class LegacyEntityCapability(
    val fieldName: String,
    val apiType: String,
    val implementationType: String?,
    val entityKind: String,
    val setsEntity: Boolean,
    val constructorArgFromEntity: Boolean = false,
    val entityVariableName: String = entityKind.replaceFirstChar { it.lowercase() },
    val idPath: String? = null,
    val modIdExpression: String? = null,
    val serializable: Boolean = false
)

internal data class LegacyLevelCapability(
    val fieldName: String,
    val apiType: String,
    val implementationType: String,
    val idPath: String,
    val modIdExpression: String
)

internal data class ProviderBackedEntityCapability(
    val providerFile: Path,
    val providerPackage: String,
    val providerClass: String,
    val fieldName: String,
    val apiType: String,
    val dataGetterName: String,
    val idPath: String,
    val modIdExpression: String,
    val modIdImport: String?,
    val attachFile: Path,
    val attachPackage: String,
    val attachClass: String,
    val guardExpression: String,
    val constructorExpression: String = "$providerClass::new"
) {
    val attachmentFieldName: String get() = "${fieldName}_ATTACHMENT"
    val qualifiedAttachClass: String get() = if (attachPackage.isBlank()) attachClass else "$attachPackage.$attachClass"
    val providerReferenceFromAttach: String get() =
        if (providerPackage == attachPackage || providerPackage.isBlank()) providerClass else "$providerPackage.$providerClass"
}

internal data class ProviderCapabilityDeclaration(
    val file: Path,
    val packageName: String,
    val className: String,
    val fieldName: String,
    val apiType: String,
    val dataGetterName: String
)

internal data class ProviderAttachBinding(
    val attachFile: Path,
    val attachPackage: String,
    val attachClass: String,
    val providerClass: String,
    val idPath: String,
    val modIdExpression: String,
    val modIdImport: String?,
    val guardExpression: String,
    val constructorExpression: String
)

internal fun StructuralRefactorPass.migrateProviderBackedEntityCapabilities(
    projectDir: Path,
    dryRun: Boolean,
    errors: MutableList<String>
): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val providerDeclarations = javaFiles.mapNotNull { providerBackedCapabilityDeclaration(it) }
        .associateBy { it.className }
    if (providerDeclarations.isEmpty()) return emptyList()

    val attachBindings = javaFiles.flatMap { providerBackedAttachBindings(it) }
    if (attachBindings.isEmpty()) return emptyList()

    val migrations = attachBindings.mapNotNull { binding ->
        val provider = providerDeclarations[binding.providerClass] ?: return@mapNotNull null
        ProviderBackedEntityCapability(
            providerFile = provider.file,
            providerPackage = provider.packageName,
            providerClass = provider.className,
            fieldName = provider.fieldName,
            apiType = provider.apiType,
            dataGetterName = provider.dataGetterName,
            idPath = binding.idPath,
            modIdExpression = binding.modIdExpression,
            modIdImport = binding.modIdImport,
            attachFile = binding.attachFile,
            attachPackage = binding.attachPackage,
            attachClass = binding.attachClass,
            guardExpression = binding.guardExpression,
            constructorExpression = binding.constructorExpression
        )
    }
    if (migrations.isEmpty()) return emptyList()

    val attachOwners = migrations.groupBy { it.attachFile }
    val namespaceSets = attachOwners.mapValues { (_, caps) -> caps.map { it.modIdExpression }.toSet() }
    val incompatibleOwners = namespaceSets.filterValues { it.size != 1 }
    if (incompatibleOwners.isNotEmpty()) {
        incompatibleOwners.forEach { (file, namespaces) ->
            errors.add(
                "Cannot migrate provider-backed entity capabilities in ${projectDir.relativize(file).invariantSeparatorsPathString}: " +
                    "multiple namespace expressions ${namespaces.joinToString(", ")}"
            )
        }
        return emptyList()
    }

    val changes = mutableListOf<Change>()
    val migrationsByProvider = migrations.groupBy { it.providerFile }
    for ((providerFile, caps) in migrationsByProvider) {
        val original = providerFile.readText()
        var modified = original
        for (capability in caps) {
            modified = migrateProviderBackedCapabilitySource(modified, capability)
        }
        modified = cleanupProviderBackedCapabilityImports(modified)
        caps.mapNotNull { it.modIdImport }.distinct().forEach { importName ->
            modified = addImportIfMissing(modified, importName)
        }
        if (modified != original) {
            if (!dryRun) providerFile.writeText(modified)
            changes.add(Change(
                file = providerFile,
                line = 1,
                description = "Migrate provider-backed serializable entity capability to NeoForge EntityCapability and AttachmentType",
                before = "CapabilityManager + ICapabilityProvider + LazyOptional provider field",
                after = "EntityCapability + provider AttachmentType",
                confidence = Confidence.HIGH,
                ruleId = "struct-provider-backed-entity-capability"
            ))
        }
    }

    for ((attachFile, caps) in attachOwners) {
        val original = attachFile.readText()
        var modified = original
        val namespace = caps.first().modIdExpression
        modified = ensureProviderBackedAttachmentRegistry(modified, namespace)
        modified = removeAttachCapabilitiesEventMethods(modified)
        modified = removeRegisterCapabilitiesEventMethod(modified)
        modified = insertBeforeLastClassBrace(modified, renderProviderBackedEntityCapabilityRegisterMethod(caps))
        modified = cleanupProviderBackedAttachImports(modified)
        modified = addImportIfMissing(modified, "net.minecraft.core.registries.BuiltInRegistries")
        modified = addImportIfMissing(modified, "net.minecraft.world.entity.EntityType")
        modified = addImportIfMissing(modified, "net.neoforged.bus.api.IEventBus")
        modified = addImportIfMissing(modified, "net.neoforged.neoforge.attachment.AttachmentType")
        modified = addImportIfMissing(modified, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        modified = addImportIfMissing(modified, "net.neoforged.neoforge.registries.DeferredRegister")
        modified = addImportIfMissing(modified, "net.neoforged.neoforge.registries.NeoForgeRegistries")
        if (modified != original) {
            if (!dryRun) attachFile.writeText(modified)
            changes.add(Change(
                file = attachFile,
                line = 1,
                description = "Migrate provider-backed AttachCapabilitiesEvent registrations to EntityCapability providers",
                before = "AttachCapabilitiesEvent<Entity>.addCapability(new Provider())",
                after = "RegisterCapabilitiesEvent.registerEntity + AttachmentType storage",
                confidence = Confidence.HIGH,
                ruleId = "struct-provider-backed-entity-capability-register"
            ))
        }
    }

    val fieldNames = migrations.map { it.fieldName }.toSet()
    val providerClassNames = migrations.map { it.providerClass }.toSet()
    var detectedCompatPackage: String? = null
    fun compatPackage(): String {
        val existing = detectedCompatPackage
        if (existing != null) return existing
        val detected = detectRequiredGeneratedCompatPackage(projectDir, "provider-backed entity capability LazyOptional bridge")
        detectedCompatPackage = detected
        return detected
    }
    for (javaFile in javaFiles) {
        val original = javaFile.readText()
        var modified = rewriteProviderBackedEntityCapabilityQueries(original, fieldNames, providerClassNames) { compatPackage() }
        modified = removeDuplicateImports(modified)
        if (modified != original) {
            if (!dryRun) javaFile.writeText(modified)
            changes.add(Change(
                file = javaFile,
                line = 1,
                description = "Wrap provider-backed entity capability lookups in LazyOptional compatibility adapter",
                before = "entity.getCapability(CUSTOM_CAP)",
                after = "LazyOptional.ofNullable(entity.getCapability(CUSTOM_CAP, null))",
                confidence = Confidence.HIGH,
                ruleId = "struct-provider-backed-entity-capability-lookups"
            ))
        }
    }

    val mainClass = detectModMainClass(projectDir)
    if (mainClass != null && attachOwners.isNotEmpty()) {
        var mainText = mainClass.readText()
        val originalMain = mainText
        val mainPackage = packageNameOf(mainText)
        val busName = requireModEventBusName(mainText, "provider-backed attachment registration")
        for (caps in attachOwners.values) {
            val first = caps.first()
            if (!mainText.contains("${first.attachClass}::registerCapabilities")) {
                if (first.attachPackage != mainPackage) {
                    mainText = addImportIfMissing(mainText, "${first.attachPackage}.${first.attachClass}")
                }
                mainText = insertModBusListener(
                    mainText,
                    busName,
                    "        $busName.addListener(${first.attachClass}::registerCapabilities);",
                    "${first.attachClass}.capabilities"
                )
            }
            if (mainText.contains("${first.attachClass}.registerAttachments(")) continue
            if (first.attachPackage != mainPackage) {
                mainText = addImportIfMissing(mainText, "${first.attachPackage}.${first.attachClass}")
            }
            mainText = insertModBusListener(
                mainText,
                busName,
                "        ${first.attachClass}.registerAttachments($busName);",
                "${first.attachClass}.attachments"
            )
        }
        if (mainText != originalMain) {
            if (!dryRun) mainClass.writeText(mainText)
            changes.add(Change(
                file = mainClass,
                line = 1,
                description = "Register provider-backed capability attachments on the mod event bus",
                before = "(no provider-backed attachment registration)",
                after = "$busName registers capabilities and AttachmentType DeferredRegister",
                confidence = Confidence.HIGH,
                ruleId = "struct-provider-backed-entity-capability-main-register"
            ))
        }
    }

    return changes
}

internal fun StructuralRefactorPass.providerBackedCapabilityDeclaration(file: Path): ProviderCapabilityDeclaration? {
    val source = file.readText()
    if (!source.contains("CapabilityManager.get") ||
        !source.contains("CapabilityToken") ||
        !source.contains("ICapabilityProvider") ||
        !source.contains("INBTSerializable") ||
        !source.contains("LazyOptional")) {
        return null
    }
    val className = classNameOfJavaSource(source) ?: return null
    val declaration = Regex(
        """public\s+static\s+(?:final\s+)?(?:Capability\s*<\s*([A-Za-z_$][\w$]*)\s*>|Object)\s+([A-Z_$][A-Z0-9_$]*)\s*=\s*CapabilityManager\.get\s*\(\s*new\s+CapabilityToken\s*<\s*([A-Za-z_$][\w$]*)?\s*>\s*\(\s*\)\s*\{\s*}\s*\)\s*;"""
    ).find(source) ?: return null
    val apiType = declaration.groupValues[1].ifBlank { declaration.groupValues[3] }.ifBlank { return null }
    val fieldName = declaration.groupValues[2]
    val getter = Regex("""\b(?:private|protected|public)\s+${Regex.escape(apiType)}\s+(get[A-Za-z_$][\w$]*)\s*\(\s*\)""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return null
    return ProviderCapabilityDeclaration(
        file = file,
        packageName = packageNameOf(source),
        className = className,
        fieldName = fieldName,
        apiType = apiType,
        dataGetterName = getter
    )
}

internal fun StructuralRefactorPass.providerBackedAttachBindings(file: Path): List<ProviderAttachBinding> {
    val source = file.readText()
    if (!source.contains("AttachCapabilitiesEvent<Entity>") || !source.contains("event.addCapability(")) return emptyList()
    val packageName = packageNameOf(source)
    val className = classNameOfJavaSource(source) ?: return emptyList()
    val result = mutableListOf<ProviderAttachBinding>()
    val executableSource = maskJavaCommentsAndLiterals(source)
    val addPattern = Regex(
        """event\.addCapability\s*\(\s*(?:new\s+ResourceLocation|ResourceLocation\.fromNamespaceAndPath)\s*\(\s*([^,]+?)\s*,\s*"([^"]+)"\s*\)\s*,\s*new\s+([A-Za-z_$][\w$]*)\s*\(\s*\)((?:\s*\.[A-Za-z_$][\w$]*\((?:[^()]|\([^()]*\))*\))*)\s*\)"""
    )
    addPattern.findAll(source).forEach { match ->
        val builderChain = match.groupValues[4].trim()
        val constructorExpression = if (builderChain.isEmpty()) {
            "${match.groupValues[3]}::new"
        } else {
            // AttachmentType.serializable(Function<IAttachmentHolder, T>): the attached entity is the holder
            val qualifiedChain = Regex("""\binstanceof\s+([A-Z][\w$]*)\b""").replace(builderChain) { type ->
                val simple = type.groupValues[1]
                "instanceof " + (importForExpression(source, "$simple.") ?: simple)
            }
            "holder -> new ${match.groupValues[3]}()" + qualifiedChain.replace("event.getObject()", "holder")
        }
        val conditions = enclosingIfConditions(executableSource, match.range.first, executableSource.indices)
            .filterNot { it.contains(".getCapability(") }
            .map { providerBackedEntityGuardExpression(it) }
            .filter { it.isNotBlank() }
            .distinct()
        val guard = if (conditions.isEmpty()) "true" else conditions.joinToString(" && ") { "($it)" }
        val modIdExpression = match.groupValues[1].trim()
        result += ProviderAttachBinding(
            attachFile = file,
            attachPackage = packageName,
            attachClass = className,
            providerClass = match.groupValues[3],
            idPath = match.groupValues[2],
            modIdExpression = modIdExpression,
            modIdImport = importForExpression(source, modIdExpression),
            guardExpression = guard,
            constructorExpression = constructorExpression
        )
    }
    return result
}

internal fun StructuralRefactorPass.enclosingIfConditions(source: String, offset: Int, searchRange: IntRange): List<String> {
    val conditions = mutableListOf<Pair<IntRange, String>>()
    var cursor = searchRange.first
    while (cursor <= searchRange.last) {
        val match = Regex("""\bif\s*\(""").find(source, cursor) ?: break
        if (match.range.first > searchRange.last) break
        val openParen = source.indexOf('(', match.range.first)
        val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
        val openBrace = if (closeParen >= 0) skipWhitespace(source, closeParen + 1) else -1
        val closeBrace = if (openBrace >= 0 && openBrace < source.length && source[openBrace] == '{') {
            findMatchingBrace(source, openBrace)
        } else {
            -1
        }
        if (closeBrace > openBrace && offset in openBrace..closeBrace) {
            conditions += (openBrace..closeBrace) to source.substring(openParen + 1, closeParen).trim()
        }
        cursor = match.range.last + 1
    }
    return conditions.sortedBy { it.first.last - it.first.first }.asReversed().map { it.second }
}

internal fun StructuralRefactorPass.providerBackedEntityGuardExpression(condition: String): String =
    condition
        .replace("event.getObject()", "entity")
        .replace(Regex("""\bgetObject\s*\(\s*\)"""), "entity")
        .replace(Regex("""\.isClientSide\s*\(\s*\)"""), ".isClientSide")

internal fun StructuralRefactorPass.importForExpression(source: String, expression: String): String? {
    val simple = Regex("""^([A-Z][A-Za-z_$][\w$]*)\.""")
        .find(expression)
        ?.groupValues
        ?.get(1)
        ?: return null
    return Regex("""(?m)^[ \t]*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(simple)})\s*;\s*$""")
        .find(source)
        ?.groupValues
        ?.get(1)
}

internal fun StructuralRefactorPass.migrateProviderBackedCapabilitySource(
    source: String,
    capability: ProviderBackedEntityCapability
): String {
    var result = source
    val attachOwner = capability.qualifiedAttachClass
    val replacement = """
    public static final EntityCapability<${capability.apiType}, Direction> ${capability.fieldName} =
            EntityCapability.createSided(ResourceLocation.fromNamespaceAndPath(${capability.modIdExpression}, "${capability.idPath}"), ${capability.apiType}.class);
    public static final Supplier<AttachmentType<${capability.providerClass}>> ${capability.attachmentFieldName} =
            $attachOwner.ATTACHMENT_TYPES.register("${capability.idPath}", () -> AttachmentType.<CompoundTag, ${capability.providerClass}>serializable(${capability.constructorExpression}).build());
""".trimEnd()
    result = Regex(
        """(?m)^[ \t]*public\s+static\s+(?:final\s+)?(?:Capability\s*<\s*${Regex.escape(capability.apiType)}\s*>|Object)\s+${Regex.escape(capability.fieldName)}\s*=\s*CapabilityManager\.get\s*\(\s*new\s+CapabilityToken\s*<\s*(?:${Regex.escape(capability.apiType)})?\s*>\s*\(\s*\)\s*\{\s*}\s*\)\s*;\s*\r?$"""
    ).replace(result, replacement)
    result = Regex(
        """(?m)^[ \t]*private\s+final\s+LazyOptional<\s*${Regex.escape(capability.apiType)}\s*>\s+[A-Za-z_$][\w$]*\s*=\s*LazyOptional\.of\(\s*this::${Regex.escape(capability.dataGetterName)}\s*\)\s*;\s*\r?\n?"""
    ).replace(result, "")
    result = removeMethodByName(result, "getCapability")
    result = Regex("""\bprivate\s+${Regex.escape(capability.apiType)}\s+${Regex.escape(capability.dataGetterName)}\s*\(""")
        .replace(result, "public ${capability.apiType} ${capability.dataGetterName}(")
    result = result
        .replace("implements ICapabilityProvider, INBTSerializable<CompoundTag>", "implements INBTSerializable<CompoundTag>")
        .replace("implements INBTSerializable<CompoundTag>, ICapabilityProvider", "implements INBTSerializable<CompoundTag>")
        .replace("implements ICapabilityProvider", "")
    result = addImportIfMissing(result, "java.util.function.Supplier")
    result = addImportIfMissing(result, "net.minecraft.core.Direction")
    result = addImportIfMissing(result, "net.minecraft.resources.ResourceLocation")
    result = addImportIfMissing(result, "net.neoforged.neoforge.attachment.AttachmentType")
    result = addImportIfMissing(result, "net.neoforged.neoforge.capabilities.EntityCapability")
    return result
}

internal fun StructuralRefactorPass.cleanupProviderBackedCapabilityImports(source: String): String {
    var result = source
    listOf(
        "com.modporter.compat.Capability",
        "com.modporter.compat.CapabilityManager",
        "com.modporter.compat.CapabilityToken",
        "com.modporter.compat.LazyOptional",
        "net.neoforged.neoforge.capabilities.Capability",
        "net.neoforged.neoforge.capabilities.CapabilityManager",
        "net.neoforged.neoforge.capabilities.CapabilityToken",
        "net.neoforged.neoforge.capabilities.ICapabilityProvider",
        "org.jetbrains.annotations.NotNull",
        "org.jetbrains.annotations.Nullable"
    ).forEach { result = removeImport(result, it) }
    result = Regex(
        """(?m)^[ \t]*import\s+com\.modporter\.generated\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.compat\.(?:Capability|CapabilityManager|CapabilityToken|LazyOptional);\s*\r?\n"""
    ).replace(result, "")
    return removeDuplicateImports(result)
}

internal fun StructuralRefactorPass.ensureProviderBackedAttachmentRegistry(source: String, modIdExpression: String): String {
    if (source.contains("DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES")) return source
    val classOpenBrace = Regex("""\bclass\s+[A-Za-z_$][\w$]*\b[^{]*\{""").find(source) ?: return source
    val insertAt = classOpenBrace.range.last + 1
    val field = """

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, $modIdExpression);
""".trimEnd()
    return source.substring(0, insertAt) + field + source.substring(insertAt)
}

internal fun StructuralRefactorPass.renderProviderBackedEntityCapabilityRegisterMethod(
    capabilities: List<ProviderBackedEntityCapability>
): String {
    val registrations = capabilities.joinToString(System.lineSeparator()) { capability ->
        val provider = capability.providerReferenceFromAttach
        val guard = if (capability.guardExpression == "true") {
            "true"
        } else {
            capability.guardExpression
        }
        "            event.registerEntity($provider.${capability.fieldName}, (EntityType) entityType, (entity, side) -> ($guard) ? entity.getData($provider.${capability.attachmentFieldName}.get()).${capability.dataGetterName}() : null);"
    }
    val attachmentInitializers = capabilities.joinToString(System.lineSeparator()) { capability ->
        "        java.util.Objects.requireNonNull(${capability.providerReferenceFromAttach}.${capability.attachmentFieldName});"
    }
    return """

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
$registrations
        }
    }

    public static void registerAttachments(IEventBus modEventBus) {
$attachmentInitializers
        ATTACHMENT_TYPES.register(modEventBus);
    }
""".trimEnd()
}

internal fun StructuralRefactorPass.cleanupProviderBackedAttachImports(source: String): String {
    var result = source
    result = removeImport(result, "net.neoforged.neoforge.event.AttachCapabilitiesEvent")
    result = removeImport(result, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
    result = removeDuplicateImports(result)
    return result
}

internal fun StructuralRefactorPass.rewriteProviderBackedEntityCapabilityQueries(
    source: String,
    fieldNames: Set<String>,
    providerClassNames: Set<String>,
    compatPackage: () -> String
): String {
    if (fieldNames.isEmpty() || !source.contains(".getCapability(")) return source
    val fieldAlternation = fieldNames.joinToString("|") { Regex.escape(it) }
    val ownerAlternation = providerClassNames.joinToString("|") { Regex.escape(it) }
    val receiver = """([A-Za-z_$][\w$]*(?:(?:\.[A-Za-z_$][\w$]*)|(?:\.[A-Za-z_$][\w$]*\(\)))*)"""
    val qualifiedCapability = if (ownerAlternation.isBlank()) {
        """($fieldAlternation)"""
    } else {
        """((?:(?:$ownerAlternation)\.)?(?:$fieldAlternation))"""
    }
    val alreadyWrapped = Regex(
        """((?:[A-Za-z_$][\w$]*\.)*LazyOptional\.ofNullable\(\s*)$receiver\.getCapability\(\s*$qualifiedCapability\s*,\s*null\s*\)(\s*\))"""
    )
    var result = alreadyWrapped.replace(source) { it.value }
    val direct = Regex("""(?<!ofNullable\()(?<![A-Za-z0-9_$])$receiver\.getCapability\(\s*$qualifiedCapability\s*(?:,\s*null\s*)?\)""")
    result = direct.replace(result) { match ->
        val receiverExpression = match.groupValues[1]
        val capabilityExpression = match.groupValues[2]
        "${compatPackage()}.LazyOptional.ofNullable($receiverExpression.getCapability($capabilityExpression, null))"
    }
    return result
}

internal fun StructuralRefactorPass.removeDuplicateImports(source: String): String {
    val seen = linkedSetOf<String>()
    return source.lines().filter { line ->
        val importName = Regex("""^\s*import\s+([^;]+);\s*$""").find(line)?.groupValues?.get(1)
        if (importName == null) {
            true
        } else {
            seen.add(importName)
        }
    }.joinToString(System.lineSeparator())
}

internal fun StructuralRefactorPass.attachmentRegisterModIdExpression(
    capabilityFile: Path,
    levelCapabilities: List<LegacyLevelCapability>,
    entityAttachmentCapabilities: List<LegacyEntityCapability>,
    errors: MutableList<String>
): String? {
    val namespaceExpressions = (
        levelCapabilities.map { it.modIdExpression } +
            entityAttachmentCapabilities.mapNotNull { it.modIdExpression }
        )
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    if (namespaceExpressions.size == 1) return namespaceExpressions.single()

    val relative = capabilityFile.toString().replace('\\', '/')
    if (namespaceExpressions.isEmpty()) {
        errors.add("Cannot migrate attachment capabilities in $relative: no source namespace expression")
    } else {
        errors.add(
            "Cannot migrate attachment capabilities in $relative: multiple namespace expressions " +
                namespaceExpressions.joinToString(", ") +
                " require separate attachment DeferredRegisters"
        )
    }
    return null
}

internal fun StructuralRefactorPass.migrateCustomEntityCapabilities(
    projectDir: Path,
    dryRun: Boolean,
    errors: MutableList<String>
): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    var detectedCompatPackage: String? = null
    fun compatPackage(): String {
        val existing = detectedCompatPackage
        if (existing != null) return existing
        val detected = detectRequiredGeneratedCompatPackage(projectDir, "custom entity capability LazyOptional bridge")
        detectedCompatPackage = detected
        return detected
    }
    val changes = mutableListOf<Change>()
    val entityCapabilityNames = linkedSetOf<String>()
    val entityAttachmentNames = linkedSetOf<String>()
    val migratedCapabilityFiles = linkedSetOf<Path>()

    val capabilityFiles = javaFiles.filter { file ->
        val text = file.readText()
        text.contains("CapabilityManager.get(new CapabilityToken") &&
            text.contains("AttachCapabilitiesEvent<Entity>")
    }
    if (capabilityFiles.isEmpty()) return emptyList()

    val implementationByInterface = findCapabilityImplementations(javaFiles)
    val entityKindByInterface = findCapabilityEntityKinds(javaFiles)
    val serializableCapabilityTypes = findSerializableCapabilityTypes(javaFiles)
    val levelCapabilityNames = capabilityFiles
        .flatMap { extractAttachLevelCapabilitySpecs(it.readText(), implementationByInterface).map { spec -> spec.fieldName } }
        .toSet()

    for (capabilityFile in capabilityFiles) {
        val original = capabilityFile.readText()
        val attachEntitySpecs = extractAttachEntityCapabilitySpecs(original)
        val levelCapabilitySpecs = extractAttachLevelCapabilitySpecs(original, implementationByInterface)
            .associateBy { it.fieldName }
        val declarations = Regex(
            """public\s+static\s+final\s+Capability<\s*([A-Za-z_$][\w$]*)\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*CapabilityManager\.get\s*\(\s*new\s+CapabilityToken<>\s*\(\s*\)\s*\{\s*}\s*\)\s*;"""
        ).findAll(original).map { match ->
            val apiType = match.groupValues[1]
            val fieldName = match.groupValues[2]
            val attachSpec = attachEntitySpecs[fieldName]
            val implementationType = attachSpec?.implementationType ?: implementationByInterface[apiType]
            LegacyEntityCapability(
                fieldName = fieldName,
                apiType = apiType,
                implementationType = implementationType,
                entityKind = attachSpec?.entityKind ?: entityKindByInterface[apiType] ?: "LivingEntity",
                setsEntity = attachSpec == null && entityKindByInterface.containsKey(apiType),
                constructorArgFromEntity = attachSpec != null,
                entityVariableName = attachSpec?.entityVariableName
                    ?: (entityKindByInterface[apiType] ?: "LivingEntity").replaceFirstChar { it.lowercase() },
                idPath = attachSpec?.idPath,
                modIdExpression = attachSpec?.modIdExpression,
                serializable = attachSpec != null &&
                    (apiType in serializableCapabilityTypes ||
                        (implementationType != null && implementationType in serializableCapabilityTypes))
            )
        }.toList()

        val entityCapabilities = declarations.filter {
            it.implementationType != null && !it.serializable && !levelCapabilitySpecs.containsKey(it.fieldName)
        }
        val entityAttachmentCapabilities = declarations.filter {
            it.implementationType != null && it.serializable && it.idPath != null &&
                it.modIdExpression != null && !levelCapabilitySpecs.containsKey(it.fieldName)
        }
        val levelCapabilities = levelCapabilitySpecs.values.toList()
        val attachmentCapabilitiesPresent = entityAttachmentCapabilities.isNotEmpty() || levelCapabilities.isNotEmpty()
        if (entityCapabilities.isEmpty() && entityAttachmentCapabilities.isEmpty() && levelCapabilities.isEmpty()) continue
        val attachmentRegisterModRef = if (attachmentCapabilitiesPresent) {
            attachmentRegisterModIdExpression(
                capabilityFile,
                levelCapabilities,
                entityAttachmentCapabilities,
                errors
            ) ?: continue
        } else {
            null
        }

        entityCapabilityNames.addAll(entityCapabilities.map { it.fieldName })
        entityAttachmentNames.addAll(entityAttachmentCapabilities.map { it.fieldName })

        var modified = original
        for (capability in declarations) {
            val levelCapability = levelCapabilitySpecs[capability.fieldName]
            val replacement = when {
                levelCapability != null -> {
                    val attachmentType = if (levelCapability.apiType in serializableCapabilityTypes ||
                        implementationByInterface[levelCapability.apiType] == levelCapability.implementationType) {
                        levelCapability.apiType
                    } else {
                        levelCapability.implementationType
                    }
                    "public static final Supplier<AttachmentType<$attachmentType>> ${levelCapability.fieldName} = ATTACHMENT_TYPES.register(\"${levelCapability.idPath}\", () -> AttachmentType.<net.minecraft.nbt.CompoundTag, $attachmentType>serializable(holder -> new ${levelCapability.implementationType}((Level) holder)).build());"
                }
                capability in entityAttachmentCapabilities -> {
                    val attachmentType = if (capability.apiType in serializableCapabilityTypes ||
                        implementationByInterface[capability.apiType] == capability.implementationType) {
                        capability.apiType
                    } else {
                        capability.implementationType!!
                    }
                    "public static final Supplier<AttachmentType<$attachmentType>> ${capability.fieldName} = ATTACHMENT_TYPES.register(\"${capability.idPath}\", () -> AttachmentType.<net.minecraft.nbt.CompoundTag, $attachmentType>serializable(holder -> new ${capability.implementationType}((${capability.entityKind}) holder)).build());"
                }
                else ->
                    "public static final EntityCapability<${capability.apiType}, Direction> ${capability.fieldName} = EntityCapability.createSided(${capability.apiType}.ID, ${capability.apiType}.class);"
            }
            modified = Regex(
                """public\s+static\s+final\s+Capability<\s*${Regex.escape(capability.apiType)}\s*>\s+${Regex.escape(capability.fieldName)}\s*=\s*CapabilityManager\.get\s*\(\s*new\s+CapabilityToken<>\s*\(\s*\)\s*\{\s*}\s*\)\s*;"""
            ).replace(modified, replacement)
        }
        if (attachmentCapabilitiesPresent && !modified.contains("DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES")) {
            val firstField = Regex("""(?m)^[ \t]*public\s+static\s+final\s+(?:Supplier<AttachmentType<[^>]+>>|EntityCapability<[^;]+>)\s+[A-Za-z_$][\w$]*\s*=""")
                .find(modified)
            val registerField = """
	public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, $attachmentRegisterModRef);

""".trimStart()
            if (firstField != null) {
                modified = modified.substring(0, firstField.range.first) + registerField + modified.substring(firstField.range.first)
            }
        }

        modified = removeAttachCapabilitiesEventMethods(modified)
        modified = if (entityCapabilities.isNotEmpty()) {
            replaceRegisterCapabilitiesMethod(modified, entityCapabilities)
        } else {
            removeRegisterCapabilitiesEventMethod(modified)
        }
        if (attachmentCapabilitiesPresent && !modified.contains("registerAttachments(IEventBus")) {
            modified = insertBeforeLastClassBrace(modified, """

	public static void registerAttachments(IEventBus modEventBus) {
		ATTACHMENT_TYPES.register(modEventBus);
	}
""".trimEnd())
        }

        listOf(
            "net.neoforged.neoforge.event.AttachCapabilitiesEvent",
            "net.neoforged.neoforge.capabilities.Capability",
            "net.neoforged.neoforge.capabilities.CapabilityManager",
            "net.neoforged.neoforge.capabilities.CapabilityToken",
            "net.neoforged.neoforge.capabilities.ICapabilitySerializable",
            "com.modporter.compat.Capability",
            "com.modporter.compat.LazyOptional",
            "javax.annotation.Nonnull",
            "org.jetbrains.annotations.NotNull",
            "org.jetbrains.annotations.Nullable"
        ).forEach { modified = removeImport(modified, it) }
        modified = Regex(
            """(?m)^[ \t]*import\s+com\.modporter\.generated\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.compat\.LazyOptional;\s*\r?\n"""
        ).replace(modified, "")
        val withoutCapabilityProviderImports = Regex("""(?m)^[ \t]*import\s+[\w.]+\.CapabilityProvider;\s*\r?\n""")
            .replace(modified, "")
        if (!Regex("""\bCapabilityProvider\b""").containsMatchIn(withoutCapabilityProviderImports)) {
            modified = withoutCapabilityProviderImports
        }
        modified = modified.replace(
            Regex("""(?m)^[ \t]*import\s+net\.neoforged\.neoforge\.capabilities\.\*;\s*\r?\n"""),
            ""
        )
        if (entityCapabilities.isNotEmpty()) {
            modified = addImportIfMissing(modified, "java.util.Collections")
            modified = addImportIfMissing(modified, "java.util.Map")
            modified = addImportIfMissing(modified, "java.util.WeakHashMap")
            modified = addImportIfMissing(modified, "net.minecraft.core.Direction")
            modified = addImportIfMissing(modified, "net.minecraft.core.registries.BuiltInRegistries")
            modified = addImportIfMissing(modified, "net.minecraft.world.entity.EntityType")
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.capabilities.EntityCapability")
            for (entityKind in entityCapabilities.map { it.entityKind }.toSet()) {
                entityKindImport(entityKind)?.let { modified = addImportIfMissing(modified, it) }
            }
        }
        if (levelCapabilities.isNotEmpty()) {
            modified = addImportIfMissing(modified, "java.util.function.Supplier")
            modified = addImportIfMissing(modified, "net.minecraft.world.level.Level")
            modified = addImportIfMissing(modified, "net.neoforged.bus.api.IEventBus")
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.attachment.AttachmentType")
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.registries.DeferredRegister")
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.registries.NeoForgeRegistries")
        }
        if (entityCapabilities.isNotEmpty()) {
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        }
        if (attachmentCapabilitiesPresent) {
            modified = addImportIfMissing(modified, "java.util.function.Supplier")
            modified = addImportIfMissing(modified, "net.neoforged.bus.api.IEventBus")
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.attachment.AttachmentType")
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.registries.DeferredRegister")
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.registries.NeoForgeRegistries")
            for (entityKind in entityAttachmentCapabilities.map { it.entityKind }.toSet()) {
                entityKindImport(entityKind)?.let { modified = addImportIfMissing(modified, it) }
            }
        }
        modified = removeUnusedImportsBySimpleNamePattern(
            modified,
            Regex("""Capability|CapabilityManager|CapabilityToken|CapabilityProvider|RegisterCapabilitiesEvent""")
        )
        modified = replaceExecutableRegex(
            modified,
            Regex("""(?m)^([ \t]*)@SubscribeEvent[ \t]*(?:\r?\n[ \t]*)+((?:public|protected|private)\s+static\s+void\s+registerAttachments\s*\()""")
        ) { match -> match.groupValues[1] + match.groupValues[2] }
        if (!Regex("""@\s*SubscribeEvent\b""").containsMatchIn(maskJavaCommentsAndLiterals(modified))) {
            modified = replaceExecutableRegex(
                modified,
                Regex("""(?m)^[ \t]*@(?:Mod\.)?EventBusSubscriber\b[^\r\n]*\r?\n""")
            ) { "" }
            modified = removeExecutableImport(modified, "net.neoforged.fml.common.EventBusSubscriber")
        }
        modified = removeUnusedSimpleImports(modified, listOf("net.neoforged.bus.api.SubscribeEvent"))

        if (modified != original) {
            migratedCapabilityFiles.add(capabilityFile)
            if (!dryRun) capabilityFile.writeText(modified)
            changes.add(Change(
                file = capabilityFile,
                line = 1,
                description = "Migrate legacy custom entity capabilities to NeoForge EntityCapability registrations",
                before = "CapabilityManager + AttachCapabilitiesEvent<Entity>",
                after = "EntityCapability.createSided + RegisterCapabilitiesEvent",
                confidence = Confidence.HIGH,
                ruleId = "struct-custom-entity-capabilities"
            ))
        }
    }

    val capabilityListNames = migratedCapabilityFiles.map { it.fileName.toString().removeSuffix(".java") }.toSet()
    for (javaFile in javaFiles) {
        var text = javaFile.readText()
        val original = text
        if (entityAttachmentNames.isNotEmpty() && capabilityListNames.any { text.contains("$it.") }) {
            text = rewriteLegacyAttachmentCapabilityQueries(text, capabilityListNames, entityAttachmentNames) { compatPackage() }
        }
        if (entityCapabilityNames.isNotEmpty() && capabilityListNames.any { text.contains("$it.") } && text.contains(".getCapability(")) {
            text = rewriteLegacyEntityCapabilityQueries(text, capabilityListNames, entityCapabilityNames) { compatPackage() }
        }
        if (levelCapabilityNames.isNotEmpty() && capabilityListNames.any { text.contains("$it.") }) {
            text = rewriteLegacyLevelCapabilityQueries(text, capabilityListNames, levelCapabilityNames)
        }
        if (capabilityListNames.any { text.contains("::attach") }) {
            text = text.lines()
                .filterNot { line -> capabilityListNames.any { name -> line.contains("$name::attach") } }
                .joinToString(System.lineSeparator())
        }
        if (text != original) {
            if (!dryRun) javaFile.writeText(text)
            changes.add(Change(
                file = javaFile,
                line = 1,
                description = "Wrap NeoForge custom entity capability lookups in LazyOptional compatibility adapter",
                before = "entity.getCapability(CapabilityList.CAP)",
                after = "LazyOptional.ofNullable(entity.getCapability(CapabilityList.CAP, null))",
                confidence = Confidence.HIGH,
                ruleId = "struct-custom-entity-capability-lookups"
            ))
        }
    }

    val mainClass = detectModMainClass(projectDir)
    val mainText = mainClass?.readText().orEmpty()
    val attachmentOwnerFiles = capabilityFiles.filter { file ->
        file.readText().contains("registerAttachments(IEventBus")
    }
    for (attachmentOwner in attachmentOwnerFiles) {
        if (mainClass == null || mainText.isBlank()) continue
        val ownerText = attachmentOwner.readText()
        val ownerPackage = packageNameOf(ownerText)
        val ownerClass = attachmentOwner.fileName.toString().removeSuffix(".java")
        val call = "$ownerClass.registerAttachments("
        if (mainClass.readText().contains(call)) continue
        var migratedMain = mainClass.readText()
        val busName = requireModEventBusName(migratedMain, "attachment registration")
        val mainPackage = packageNameOf(migratedMain)
        if (ownerPackage != mainPackage) {
            migratedMain = addImportIfMissing(migratedMain, "$ownerPackage.$ownerClass")
        }
        migratedMain = insertModBusListener(
            migratedMain,
            busName,
            "        $ownerClass.registerAttachments($busName);",
            "$ownerClass.attachments"
        )
        if (migratedMain != mainClass.readText()) {
            if (!dryRun) mainClass.writeText(migratedMain)
            changes.add(Change(
                file = mainClass,
                line = 1,
                description = "Register migrated level capability attachments on the mod event bus",
                before = "AttachCapabilitiesEvent<Level>",
                after = "$ownerClass.registerAttachments($busName)",
                confidence = Confidence.HIGH,
                ruleId = "struct-level-capability-attachment-main-register"
            ))
        }
    }

    return changes
}

internal fun StructuralRefactorPass.findCapabilityImplementations(javaFiles: List<Path>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (file in javaFiles) {
        val text = file.readText()
        val match = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+implements\s+([A-Za-z_$][\w$]*)\b""").find(text) ?: continue
        result.putIfAbsent(match.groupValues[2], match.groupValues[1])
    }
    return result
}

internal fun StructuralRefactorPass.findCapabilityEntityKinds(javaFiles: List<Path>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (file in javaFiles) {
        val text = file.readText()
        val interfaceMatch = Regex("""\binterface\s+([A-Za-z_$][\w$]*)\b""").find(text) ?: continue
        val interfaceName = interfaceMatch.groupValues[1]
        val kind = when {
            Regex("""\bsetEntity\s*\(\s*Player\b""").containsMatchIn(text) -> "Player"
            Regex("""\bsetEntity\s*\(\s*LivingEntity\b""").containsMatchIn(text) -> "LivingEntity"
            else -> continue
        }
        result[interfaceName] = kind
    }
    return result
}

internal fun StructuralRefactorPass.findSerializableCapabilityTypes(javaFiles: List<Path>): Set<String> {
    val result = linkedSetOf<String>()
    for (file in javaFiles) {
        val text = file.readText()
        val typeName = Regex("""\b(?:interface|class)\s+([A-Za-z_$][\w$]*)\b""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: continue
        if (text.contains("INBTSerializable") ||
            text.contains("INBTSynchable") ||
            (text.contains("serializeNBT(") && text.contains("deserializeNBT("))) {
            result.add(typeName)
        }
    }
    return result
}

internal data class AttachEntityCapabilitySpec(
    val fieldName: String,
    val implementationType: String,
    val entityKind: String,
    val entityVariableName: String,
    val idPath: String,
    val modIdExpression: String
)

internal fun StructuralRefactorPass.extractAttachEntityCapabilitySpecs(source: String): Map<String, AttachEntityCapabilitySpec> {
    if (!source.contains("AttachCapabilitiesEvent<Entity>") || !source.contains("event.addCapability(")) {
        return emptyMap()
    }
    val specs = linkedMapOf<String, AttachEntityCapabilitySpec>()
    val instancePattern = Regex("""(?:event\.getObject\(\)|[A-Za-z_$][\w$]*)\s+instanceof\s+([A-Za-z_$][\w$]*)\s+([A-Za-z_$][\w$]*)""")
    var cursor = 0
    while (true) {
        val match = instancePattern.find(source, cursor) ?: break
        val entityKind = match.groupValues[1]
        val entityVariable = match.groupValues[2]
        val closeParen = source.indexOf(')', match.range.last)
        val openBrace = if (closeParen >= 0) source.indexOf('{', closeParen) else -1
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = source.substring(openBrace + 1, closeBrace)
        val providerPattern = Regex(
            """event\.addCapability\s*\(\s*ResourceLocation\.fromNamespaceAndPath\s*\(\s*([^,]+?)\s*,\s*"([^"]+)"\s*\)\s*,\s*new\s+CapabilityProvider\s*\(\s*(?:[A-Za-z_$][\w$]*\.)?([A-Z_$][A-Z0-9_$]*)\s*,\s*new\s+([A-Za-z_$][\w$]*)\s*\(\s*${Regex.escape(entityVariable)}\s*\)\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        providerPattern.findAll(body).forEach { provider ->
            val fieldName = provider.groupValues[3]
            specs[fieldName] = AttachEntityCapabilitySpec(
                fieldName = fieldName,
                implementationType = provider.groupValues[4],
                entityKind = entityKind,
                entityVariableName = entityVariable,
                idPath = provider.groupValues[2],
                modIdExpression = provider.groupValues[1].trim()
            )
        }
        cursor = match.range.last + 1
    }
    return specs
}

internal fun StructuralRefactorPass.extractAttachLevelCapabilitySpecs(
    source: String,
    implementationByInterface: Map<String, String>
): List<LegacyLevelCapability> {
    if (!source.contains("AttachCapabilitiesEvent<Level>") || !source.contains("event.addCapability(")) {
        return emptyList()
    }
    val apiTypeByField = Regex(
        """public\s+static\s+final\s+Capability<\s*([A-Za-z_$][\w$]*)\s*>\s+([A-Z_$][A-Z0-9_$]*)\s*="""
    ).findAll(source).associate { it.groupValues[2] to it.groupValues[1] }
    val specs = mutableListOf<LegacyLevelCapability>()
    val pattern = Regex(
        """event\.addCapability\s*\(\s*ResourceLocation\.fromNamespaceAndPath\s*\(\s*([^,]+?)\s*,\s*"([^"]+)"\s*\)\s*,\s*new\s+CapabilityProvider\s*\(\s*(?:[A-Za-z_$][\w$]*\.)?([A-Z_$][A-Z0-9_$]*)\s*,\s*new\s+([A-Za-z_$][\w$]*)\s*\(\s*event\.getObject\(\)\s*\)\s*\)\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    pattern.findAll(source).forEach { match ->
        val fieldName = match.groupValues[3]
        val apiType = apiTypeByField[fieldName] ?: return@forEach
        val implementationType = match.groupValues[4].ifBlank { implementationByInterface[apiType] ?: return@forEach }
        specs.add(LegacyLevelCapability(
            fieldName = fieldName,
            apiType = apiType,
            implementationType = implementationType,
            idPath = match.groupValues[2],
            modIdExpression = match.groupValues[1].trim()
        ))
    }
    return specs
}

internal fun StructuralRefactorPass.removeAttachCapabilitiesEventMethods(source: String): String {
    var result = source
    val methodNames = Regex("""\b([A-Za-z_$][\w$]*)\s*\(\s*AttachCapabilitiesEvent<[^>]+>\s+[A-Za-z_$][\w$]*\s*\)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    for (methodName in methodNames) {
        result = removeMethodByName(result, methodName)
    }
    return result
}

internal fun StructuralRefactorPass.removeRegisterCapabilitiesEventMethod(source: String): String {
    val methodMatch = Regex(
        """(?:public|protected|private)\s+static\s+void\s+[A-Za-z_$][\w$]*\s*\(\s*RegisterCapabilitiesEvent\s+[A-Za-z_$][\w$]*\s*\)"""
    ).find(source) ?: return source
    val signatureStart = source.lastIndexOf('\n', methodMatch.range.first).let { if (it < 0) 0 else it + 1 }
    val start = Regex("""(?m)(?:^[ \t]*@[A-Za-z_$][\w$.]*(?:\([^)]*\))?[ \t]*\r?\n)+[ \t]*$""")
        .findAll(source.substring(0, signatureStart))
        .lastOrNull()
        ?.range
        ?.first
        ?: signatureStart
    val openBrace = source.indexOf('{', methodMatch.range.first)
    if (openBrace < 0) return source
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace <= openBrace) return source
    var end = closeBrace + 1
    if (end < source.length && source[end] == '\r') end++
    if (end < source.length && source[end] == '\n') end++
    return source.removeRange(start, end)
}

internal fun StructuralRefactorPass.entityKindImport(entityKind: String): String? = when (entityKind) {
    "Entity" -> "net.minecraft.world.entity.Entity"
    "LivingEntity" -> "net.minecraft.world.entity.LivingEntity"
    "Mob" -> "net.minecraft.world.entity.Mob"
    "Player" -> "net.minecraft.world.entity.player.Player"
    "ItemEntity" -> "net.minecraft.world.entity.item.ItemEntity"
    "LightningBolt" -> "net.minecraft.world.entity.LightningBolt"
    "AbstractArrow" -> "net.minecraft.world.entity.projectile.AbstractArrow"
    else -> null
}

internal fun StructuralRefactorPass.replaceRegisterCapabilitiesMethod(source: String, capabilities: List<LegacyEntityCapability>): String {
    val methodMatch = Regex(
        """(?:public|protected|private)\s+static\s+void\s+[A-Za-z_$][\w$]*\s*\(\s*RegisterCapabilitiesEvent\s+[A-Za-z_$][\w$]*\s*\)"""
    ).find(source) ?: return source
    val nameIndex = methodMatch.range.first
    val openBrace = source.indexOf('{', nameIndex)
    if (openBrace < 0) return source
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace <= openBrace) return source

    val methodSource = renderEntityCapabilityRegisterMethod(capabilities)
    return source.substring(0, source.lastIndexOf('\n', nameIndex).let { if (it < 0) 0 else it + 1 }) +
        methodSource +
        source.substring(closeBrace + 1)
}

internal fun StructuralRefactorPass.renderEntityCapabilityRegisterMethod(capabilities: List<LegacyEntityCapability>): String {
    val caches = capabilities.joinToString(System.lineSeparator()) { capability ->
        "    private static final Map<Entity, ${capability.apiType}> ${capability.fieldName}_CACHE = Collections.synchronizedMap(new WeakHashMap<>());"
    }
    val registrations = capabilities.joinToString(System.lineSeparator()) { capability ->
        "            event.registerEntity(${capability.fieldName}, (EntityType) entityType, (entity, side) -> getOrCreate${capability.fieldName}(entity));"
    }
    val helpers = capabilities.joinToString(System.lineSeparator() + System.lineSeparator()) { capability ->
        val implementation = capability.implementationType ?: capability.apiType
        val entityVariable = capability.entityVariableName
        val guard = when (capability.entityKind) {
            "Entity" -> ""
            else -> "if (!(entity instanceof ${capability.entityKind} $entityVariable)) return null;"
        }
        val constructor = if (capability.constructorArgFromEntity) {
            "new $implementation($entityVariable)"
        } else {
            "new $implementation()"
        }
        val setEntityArg = when {
            capability.constructorArgFromEntity -> null
            capability.entityKind == "Entity" -> "entity"
            else -> entityVariable
        }
        val setEntity = if (capability.setsEntity && setEntityArg != null) {
            "            capability.setEntity($setEntityArg);${System.lineSeparator()}"
        } else {
            ""
        }
        val guardLine = if (guard.isBlank()) {
            ""
        } else {
            "        $guard${System.lineSeparator()}"
        }
        """
    private static ${capability.apiType} getOrCreate${capability.fieldName}(Entity entity) {
$guardLine        return ${capability.fieldName}_CACHE.computeIfAbsent(entity, key -> {
            $implementation capability = $constructor;
$setEntity            return capability;
        });
    }
""".trimEnd()
    }
    return """
$caches

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
$registrations
        }
    }

$helpers
""".trimEnd()
}

internal fun StructuralRefactorPass.rewriteLegacyLevelCapabilityQueries(
    source: String,
    capabilityListNames: Set<String>,
    levelCapabilityNames: Set<String>
): String {
    var result = source
    val ownerAlternation = capabilityListNames.joinToString("|") { Regex.escape(it) }
    val fieldAlternation = levelCapabilityNames.joinToString("|") { Regex.escape(it) }
    if (ownerAlternation.isBlank() || fieldAlternation.isBlank()) return source
    val alreadyWrappedPattern = Regex(
        """((?:[A-Za-z_$][\w$]*\.)*LazyOptional\.ofNullable\(\s*)((?<![A-Za-z0-9_$])[A-Za-z_$][\w$]*(?:(?:\.[A-Za-z_$][\w$]*)|(?:\.[A-Za-z_$][\w$]*\(\)))*)\.getCapability\(\s*(($ownerAlternation)\.($fieldAlternation))\s*(?:,\s*null\s*)?\)(\s*\))"""
    )
    result = alreadyWrappedPattern.replace(result) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}.getData(${match.groupValues[3]}.get())${match.groupValues[6]}"
    }
    val directPattern = Regex(
        """(?<![A-Za-z0-9_$])([A-Za-z_$][\w$]*(?:(?:\.[A-Za-z_$][\w$]*)|(?:\.[A-Za-z_$][\w$]*\(\)))*)\.getCapability\(\s*(($ownerAlternation)\.($fieldAlternation))\s*(?:,\s*null\s*)?\)"""
    )
    result = directPattern.replace(result) { match ->
        "${match.groupValues[1]}.getData(${match.groupValues[2]}.get())"
    }
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyEntityCapabilityQueries(
    source: String,
    capabilityListNames: Set<String>,
    entityCapabilityNames: Set<String>,
    compatPackage: () -> String
): String {
    var result = source
    val listAlternation = capabilityListNames.joinToString("|") { Regex.escape(it) }
    val fieldAlternation = entityCapabilityNames.joinToString("|") { Regex.escape(it) }
    if (listAlternation.isBlank() || fieldAlternation.isBlank()) return source
    val wrappedPattern = Regex(
        """((?:[A-Za-z_$][\w$]*\.)*LazyOptional\.ofNullable\(\s*)([A-Za-z_$][\w$]*(?:(?:\.[A-Za-z_$][\w$]*)|(?:\.[A-Za-z_$][\w$]*\(\)))*)\.getCapability\(\s*(($listAlternation)\.($fieldAlternation))\s*(?:,\s*null\s*)?\)(\s*\))"""
    )
    result = wrappedPattern.replace(result) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}.getCapability(${match.groupValues[3]}, null)${match.groupValues[6]}"
    }
    val queryPattern = Regex(
        """(?<!ofNullable\()(?<![A-Za-z0-9_$])([A-Za-z_$][\w$]*(?:(?:\.[A-Za-z_$][\w$]*)|(?:\.[A-Za-z_$][\w$]*\(\)))*)\.getCapability\(\s*(($listAlternation)\.($fieldAlternation))\s*(?:,\s*null\s*)?\)"""
    )
    result = queryPattern.replace(result) { match ->
        val receiver = match.groupValues[1]
        val capability = match.groupValues[2]
        "${compatPackage()}.LazyOptional.ofNullable($receiver.getCapability($capability, null))"
    }
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyAttachmentCapabilityQueries(
    source: String,
    capabilityListNames: Set<String>,
    attachmentCapabilityNames: Set<String>,
    compatPackage: () -> String
): String {
    var result = source
    val ownerAlternation = capabilityListNames.joinToString("|") { Regex.escape(it) }
    val fieldAlternation = attachmentCapabilityNames.joinToString("|") { Regex.escape(it) }
    if (ownerAlternation.isBlank() || fieldAlternation.isBlank()) return source
    val alreadyWrappedPattern = Regex(
        """((?:[A-Za-z_$][\w$]*\.)*LazyOptional\.ofNullable\(\s*)([A-Za-z_$][\w$]*(?:(?:\.[A-Za-z_$][\w$]*)|(?:\.[A-Za-z_$][\w$]*\(\)))*)\.getCapability\(\s*(($ownerAlternation)\.($fieldAlternation))\s*(?:,\s*null\s*)?\)(\s*\))"""
    )
    result = alreadyWrappedPattern.replace(result) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}.getData(${match.groupValues[3]}.get())${match.groupValues[6]}"
    }
    val directPattern = Regex(
        """(?<![A-Za-z0-9_$])([A-Za-z_$][\w$]*(?:(?:\.[A-Za-z_$][\w$]*)|(?:\.[A-Za-z_$][\w$]*\(\)))*)\.getCapability\(\s*(($ownerAlternation)\.($fieldAlternation))\s*(?:,\s*null\s*)?\)"""
    )
    result = directPattern.replace(result) { match ->
        "${compatPackage()}.LazyOptional.ofNullable(${match.groupValues[1]}.getData(${match.groupValues[2]}.get()))"
    }
    return result
}

internal data class NitrogenAttachmentSupplier(
    val attachmentType: String,
    val packageName: String,
    val ownerType: String,
    val fieldName: String
) {
    fun referenceFrom(packageName: String): String =
        if (this.packageName.isBlank() || this.packageName == packageName) {
            "$ownerType.$fieldName"
        } else {
            "${this.packageName}.$ownerType.$fieldName"
        }
}

internal fun StructuralRefactorPass.migrateNitrogenAttachmentApi(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val modId = detectModId(projectDir)
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val changes = mutableListOf<Change>()

    val entitySyncPackets = javaFiles.mapNotNull { javaFile ->
        val text = javaFile.readText()
        if (!text.contains("SyncEntityPacket")) return@mapNotNull null
        Regex("""\b(?:class|record)\s+([A-Za-z_$][\w$]*)[\s\S]*?\bextends\s+SyncEntityPacket\s*<""")
            .find(text)
            ?.groupValues
            ?.get(1)
    }.toSet()
    val syncOwnerAccessors = collectNitrogenSyncOwnerAccessors(javaFiles)
    val attachmentSuppliers = collectNitrogenAttachmentSuppliers(javaFiles)
    val nitrogenSynchableTypes = collectNitrogenSynchableTypes(javaFiles)

    for (javaFile in javaFiles) {
        val original = javaFile.readText()
        if (!original.contains("com.aetherteam.nitrogen.capability.INBTSynchable") &&
            !original.contains("com.aetherteam.nitrogen.network.BasePacket") &&
            !original.contains("BasePacket") &&
            !original.contains("getSyncPacket(") &&
            !original.contains("setSynched(") &&
            !original.contains("forceSync(")) {
            continue
        }

        var modified = original
        modified = modified.replace(
            "com.aetherteam.nitrogen.capability.INBTSynchable",
            "com.aetherteam.nitrogen.attachment.INBTSynchable"
        )
        val hasAttachmentSyncPacketMethod = hasNitrogenAttachmentSyncPacketMethod(modified, nitrogenSynchableTypes)
        modified = rewriteNitrogenBasePacketReferences(modified, hasAttachmentSyncPacketMethod)

        val synchableGeneric = Regex("""\bINBTSynchable\s*<\s*([^>]+)\s*>""")
        if (synchableGeneric.containsMatchIn(modified)) {
            modified = synchableGeneric.replace(modified) { match ->
                "INBTSynchable, INBTSerializable<${match.groupValues[1].trim()}>"
            }
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.common.util.INBTSerializable")
        }

        if (modified.contains("extends SyncEntityPacket<") || modified.contains("extends SyncLevelPacket<")) {
            modified = modified.replace(
                "import net.minecraft.network.FriendlyByteBuf;",
                "import net.minecraft.network.RegistryFriendlyByteBuf;"
            )
            modified = Regex("""\bFriendlyByteBuf\s+([A-Za-z_$][\w$]*)""")
                .replace(modified, "RegistryFriendlyByteBuf $1")
            modified = removeMethodByName(modified, "getCapability")
            modified = ensureNitrogenSyncPacketAttachmentGetter(modified, attachmentSuppliers)
            modified = ensureNitrogenSyncPacketPayloadType(modified, modId)
            modified = removeUnusedSimpleImports(modified, listOf(
                "net.minecraftforge.common.util.LazyOptional",
                "net.minecraft.world.entity.Entity",
                "net.minecraft.world.level.Level"
            ))
            modified = removeUnusedImportsBySimpleNamePattern(modified, Regex("""LazyOptional"""))
        }

        modified = rewriteNitrogenGetSyncPacketSignature(modified, entitySyncPackets)
        val ownerEntityId = inferNitrogenOwnerEntityId(modified)
        val receiverOwnerEntityIds = inferNitrogenReceiverOwnerEntityIds(modified, syncOwnerAccessors)
        modified = rewriteNitrogenSyncCalls(modified, "setSynched", ownerEntityId, receiverOwnerEntityIds)
        modified = rewriteNitrogenSyncCalls(modified, "forceSync", ownerEntityId, receiverOwnerEntityIds)

        if (modified.contains("getPacketChannel(")) {
            modified = removeMethodByName(modified, "getPacketChannel")
        }

        modified = removeUnusedSimpleImports(modified, listOf(
            "net.minecraftforge.network.simple.SimpleChannel",
            "net.neoforged.neoforge.network.simple.SimpleChannel"
        ))
        modified = removeUnusedImportsBySimpleNamePattern(modified, Regex("""[A-Za-z_$][\w$]*PacketHandler"""))

        if (modified != original) {
            if (!dryRun) javaFile.writeText(modified)
            changes.add(Change(
                file = javaFile,
                line = 1,
                description = "Migrate Nitrogen sync capability API to attachment INBTSynchable surface",
                before = "com.aetherteam.nitrogen.capability.INBTSynchable/BasePacket",
                after = "com.aetherteam.nitrogen.attachment.INBTSynchable/SyncPacket",
                confidence = Confidence.HIGH,
                ruleId = "struct-nitrogen-attachment-api"
            ))
        }
    }

    return changes
}

internal fun StructuralRefactorPass.migrateQuarkBackpackInventoryWrappers(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val changes = mutableListOf<Change>()
    val wrapperDeclaration = Regex("""\b((?:final\s+)?)InventoryIIH\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+InventoryIIH\s*\(""")

    for (javaFile in javaFiles) {
        val original = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(original)
        if (!executableCode.contains("org.violetmoon.quark.base.util.InventoryIIH") ||
            !wrapperDeclaration.containsMatchIn(executableCode)) {
            continue
        }

        val replacements = wrapperDeclaration.findAll(executableCode).mapNotNull { match ->
            val wrapperName = match.groupValues[2]
            val backpackSlotConsumesWrapper = Regex(
                """\bnew\s+(?:org\.violetmoon\.quark\.addons\.oddities\.inventory\.slot\.)?BackpackSlot\s*\(\s*${Regex.escape(wrapperName)}\b"""
            ).containsMatchIn(executableCode)
            if (!backpackSlotConsumesWrapper) {
                null
            } else {
                match.range to "${match.groupValues[1]}BackpackContainer $wrapperName = new BackpackContainer("
            }
        }.toList()
        if (replacements.isEmpty()) continue
        var modified = original
        for ((range, replacement) in replacements.asReversed()) {
            modified = modified.substring(0, range.first) + replacement + modified.substring(range.last + 1)
        }
        modified = removeImport(modified, "org.violetmoon.quark.base.util.InventoryIIH")
        modified = addImportIfMissing(modified, "org.violetmoon.quark.addons.oddities.inventory.BackpackContainer")
        modified = cleanupRedundantBlankLines(modified)

        if (modified != original) {
            if (!dryRun) javaFile.writeText(modified)
            changes += Change(
                file = javaFile,
                line = 1,
                description = "Migrate Quark backpack InventoryIIH wrappers to BackpackContainer",
                before = "InventoryIIH item-handler wrapper for BackpackSlot",
                after = "BackpackContainer passed to BackpackSlot",
                confidence = Confidence.HIGH,
                ruleId = "struct-quark-backpack-container-121"
            )
        }
    }
    return changes
}

internal fun StructuralRefactorPass.hasNitrogenAttachmentSyncPacketMethod(source: String, nitrogenSynchableTypes: Set<String>): Boolean {
    val code = maskJavaComments(source)
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getSyncPacket(")) return false
    val hasMatchingSignature = Regex(
        """\bgetSyncPacket\s*\(\s*String\s+[A-Za-z_$][\w$]*\s*,\s*(?:INBTSynchable\.)?Type\s+[A-Za-z_$][\w$]*\s*,\s*Object\s+[A-Za-z_$][\w$]*\s*\)"""
    ).containsMatchIn(executableCode)
    if (!hasMatchingSignature) return false
    val packageName = packageNameOf(code)
    val imports = javaNonStaticImports(code)
    val wildcardImports = javaNonStaticWildcardImports(code)
    return extractJavaTopLevelSuperTypes(executableCode).any { superType ->
        resolveKnownJavaTypeReference(superType, packageName, imports, wildcardImports, nitrogenSynchableTypes) in nitrogenSynchableTypes
    }
}

internal fun StructuralRefactorPass.rewriteNitrogenBasePacketReferences(source: String, attachmentSyncPacketMethod: Boolean): String {
    if (!source.contains("BasePacket") && !source.contains("com.aetherteam.nitrogen.network.BasePacket")) return source
    val targetImport = if (attachmentSyncPacketMethod) {
        "com.aetherteam.nitrogen.network.packet.SyncPacket"
    } else {
        "net.minecraft.network.protocol.common.custom.CustomPacketPayload"
    }
    val targetType = targetImport.substringAfterLast('.')
    var result = source.replace(
        "import com.aetherteam.nitrogen.network.BasePacket;",
        "import $targetImport;"
    )
    result = result.replace(
        "com.aetherteam.nitrogen.network.BasePacket",
        targetImport
    )
    if (result.contains(targetImport) || result.contains(targetType)) {
        result = Regex("""\bBasePacket\b""").replace(result, targetType)
    }
    return result
}

internal fun StructuralRefactorPass.collectNitrogenAttachmentSuppliers(javaFiles: List<Path>): Map<String, NitrogenAttachmentSupplier> {
    val suppliers = mutableListOf<NitrogenAttachmentSupplier>()
    val typeReference = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""
    for (javaFile in javaFiles) {
        val source = javaFile.readText()
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("AttachmentType<")) continue
        val packageName = packageNameOf(code)
        val ownerType = javaTopLevelTypeName(executableCode) ?: continue
        val imports = javaNonStaticImports(code)
        val wildcardImports = javaNonStaticWildcardImports(code)

        fun addSupplier(attachmentTypeRef: String, typeName: String, fieldName: String) {
            if (!isNeoForgeAttachmentTypeReference(attachmentTypeRef, packageName, imports, wildcardImports)) return
            val resolvedType = resolveJavaTypeReference(typeName, packageName, imports) ?: return
            suppliers += NitrogenAttachmentSupplier(
                attachmentType = resolvedType,
                packageName = packageName,
                ownerType = ownerType,
                fieldName = fieldName
            )
        }

        Regex(
            """(?m)\bpublic\s+static\s+final\s+(?:java\.util\.function\.)?Supplier\s*<\s*($typeReference)\s*<\s*([^<>]+)\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*="""
        ).findAll(executableCode).forEach { match ->
            addSupplier(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        Regex(
            """(?m)\bpublic\s+static\s+final\s+(?:net\.neoforged\.neoforge\.registries\.)?DeferredHolder\s*<\s*$typeReference\s*<\s*\?\s*>\s*,\s*($typeReference)\s*<\s*([^<>]+)\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*="""
        ).findAll(executableCode).forEach { match ->
            addSupplier(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        Regex(
            """(?m)\bpublic\s+static\s+final\s+(?:net\.minecraftforge\.registries\.)?RegistryObject\s*<\s*($typeReference)\s*<\s*([^<>]+)\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*="""
        ).findAll(executableCode).forEach { match ->
            addSupplier(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
    }
    return suppliers
        .groupBy { it.attachmentType }
        .mapNotNull { (typeName, candidates) -> candidates.singleOrNull()?.let { typeName to it } }
        .toMap()
}

internal fun StructuralRefactorPass.ensureNitrogenSyncPacketAttachmentGetter(
    source: String,
    attachmentSuppliers: Map<String, NitrogenAttachmentSupplier>
): String {
    if (source.contains("getAttachment(")) return source
    val syncPacketType = Regex("""\bextends\s+Sync(?:Entity|Level)Packet\s*<\s*([^>]+)\s*>""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: return source
    val packageName = packageNameOf(source)
    val imports = javaNonStaticImports(source)
    val attachmentType = resolveJavaTypeReference(syncPacketType, packageName, imports) ?: return source
    val supplier = attachmentSuppliers[attachmentType] ?: return source
    val className = javaTopLevelTypeName(source) ?: return source
    val typeStart = findTypeDeclarationStart(source, className) ?: return source
    val openBrace = source.indexOf('{', typeStart)
    if (openBrace < 0) return source
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace <= openBrace) return source
    val insertion = """

    @Override
    public java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<$attachmentType>> getAttachment() {
        return ${supplier.referenceFrom(packageName)};
    }
""".trimEnd()
    return source.substring(0, closeBrace).trimEnd() + "\n" + insertion + "\n" + source.substring(closeBrace)
}

internal fun StructuralRefactorPass.ensureNitrogenSyncPacketPayloadType(source: String, modId: String?): String {
    if (modId.isNullOrBlank()) return source
    if (!Regex("""\bextends\s+Sync(?:Entity|Level)Packet\s*<""").containsMatchIn(source)) return source
    val className = javaTopLevelTypeName(source) ?: return source
    val typeStart = findTypeDeclarationStart(source, className) ?: return source
    val openBrace = source.indexOf('{', typeStart)
    if (openBrace < 0) return source
    var result = source
    if (!Regex("""\bTYPE\s*=""").containsMatchIn(result) ||
        !Regex("""CustomPacketPayload\.Type\s*<\s*${Regex.escape(className)}\s*>|Type\s*<\s*${Regex.escape(className)}\s*>""").containsMatchIn(result)) {
        val typeField = """

    public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<$className> TYPE =
            new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("$modId", "${payloadPathName(className)}"));
""".trimEnd()
        result = result.substring(0, openBrace + 1) + typeField + result.substring(openBrace + 1)
    }
    if (!Regex("""\bSTREAM_CODEC\s*=""").containsMatchIn(result)) {
        val refreshedOpenBrace = result.indexOf('{', findTypeDeclarationStart(result, className) ?: return result)
        if (refreshedOpenBrace < 0) return result
        val streamCodecField = """

    public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, $className> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of((buf, packet) -> packet.write(buf), ${className}::decode);
""".trimEnd()
        result = result.substring(0, refreshedOpenBrace + 1) + streamCodecField + result.substring(refreshedOpenBrace + 1)
    }
    if (Regex("""\btype\s*\(\s*\)""").containsMatchIn(result)) return result
    val refreshedOpenBrace = result.indexOf('{', findTypeDeclarationStart(result, className) ?: return result)
    if (refreshedOpenBrace < 0) return result
    val closeBrace = findMatchingBrace(result, refreshedOpenBrace)
    if (closeBrace <= refreshedOpenBrace) return result
    val typeMethod = """

    @Override
    public net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
        return TYPE;
    }
""".trimEnd()
    return result.substring(0, closeBrace).trimEnd() + "\n" + typeMethod + "\n" + result.substring(closeBrace)
}

internal fun StructuralRefactorPass.collectNitrogenSynchableTypes(javaFiles: List<Path>): Set<String> {
    data class TypeInfo(
        val fqn: String,
        val packageName: String,
        val imports: Map<String, String>,
        val wildcardImports: Set<String>,
        val superTypes: List<String>
    )

    val types = javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val typeName = javaTopLevelTypeName(executableCode) ?: return@mapNotNull null
        val packageName = packageNameOf(code)
        val fqn = if (packageName.isBlank()) typeName else "$packageName.$typeName"
        TypeInfo(
            fqn = fqn,
            packageName = packageName,
            imports = javaNonStaticImports(code),
            wildcardImports = javaNonStaticWildcardImports(code),
            superTypes = extractJavaTopLevelSuperTypes(executableCode)
        )
    }
    val synchableTypes = linkedSetOf(
        "com.aetherteam.nitrogen.capability.INBTSynchable",
        "com.aetherteam.nitrogen.attachment.INBTSynchable"
    )
    var changed = true
    while (changed) {
        changed = false
        for (type in types) {
            if (type.fqn in synchableTypes) continue
            val implementsSynchable = type.superTypes.any { superType ->
                val resolved = resolveKnownJavaTypeReference(
                    superType,
                    type.packageName,
                    type.imports,
                    type.wildcardImports,
                    synchableTypes
                )
                resolved in synchableTypes
            }
            if (implementsSynchable) {
                synchableTypes += type.fqn
                changed = true
            }
        }
    }
    return synchableTypes
}

internal fun StructuralRefactorPass.extractJavaTopLevelSuperTypes(source: String): List<String> {
    val className = javaTopLevelTypeName(source) ?: return emptyList()
    val typeStart = Regex(
        """\b(?:class|interface|record)\s+${Regex.escape(className)}(?:\s*<[^>{};]+>)?"""
    ).find(source)?.range?.first ?: return emptyList()
    val openBrace = source.indexOf('{', typeStart)
    if (openBrace < 0) return emptyList()
    val header = source.substring(typeStart, openBrace)
    val superTypes = mutableListOf<String>()
    Regex("""\bimplements\s+(.+)$""").find(header)?.let { match ->
        superTypes += splitTopLevelJavaArgs(match.groupValues[1]).map { it.trim() }
    }
    val beforeImplements = header.substringBefore(" implements ")
    Regex("""\bextends\s+(.+)$""").find(beforeImplements)?.let { match ->
        superTypes += splitTopLevelJavaArgs(match.groupValues[1]).map { it.trim() }
    }
    return superTypes.filter { it.isNotBlank() }
}

internal fun StructuralRefactorPass.javaNonStaticImports(source: String): Map<String, String> {
    val imports = linkedMapOf<String, String>()
    Regex("""(?m)^[ \t]*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;""")
        .findAll(source)
        .forEach { match ->
            val importName = match.groupValues[1]
            imports[importName.substringAfterLast('.')] = importName
        }
    return imports
}

internal fun StructuralRefactorPass.javaNonStaticWildcardImports(source: String): Set<String> =
    Regex("""(?m)^[ \t]*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.\*\s*;""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()

internal fun StructuralRefactorPass.resolveJavaTypeReference(
    rawType: String,
    packageName: String,
    imports: Map<String, String>
): String? {
    val normalized = normalizeJavaTypeReference(rawType) ?: return null
    if (normalized.contains(".")) {
        val firstSegment = normalized.substringBefore('.')
        imports[firstSegment]?.let { imported ->
            return "$imported.${normalized.substringAfter('.')}"
        }
        return normalized
    }
    imports[normalized]?.let { return it }
    return if (packageName.isBlank()) normalized else "$packageName.$normalized"
}

internal fun StructuralRefactorPass.resolveKnownJavaTypeReference(
    rawType: String,
    packageName: String,
    imports: Map<String, String>,
    wildcardImports: Set<String>,
    knownTypes: Set<String>
): String? {
    val normalized = normalizeJavaTypeReference(rawType) ?: return null
    if (normalized.contains(".")) {
        val firstSegment = normalized.substringBefore('.')
        val candidates = linkedSetOf<String>()
        imports[firstSegment]?.let { imported ->
            candidates += "$imported.${normalized.substringAfter('.')}"
        }
        candidates += normalized
        return candidates.filter { it in knownTypes }.distinct().singleOrNull()
    }

    val candidates = linkedSetOf<String>()
    imports[normalized]?.let { candidates += it }
    if (packageName.isNotBlank()) {
        candidates += "$packageName.$normalized"
    } else {
        candidates += normalized
    }
    wildcardImports.forEach { wildcardPackage ->
        candidates += "$wildcardPackage.$normalized"
    }
    return candidates.filter { it in knownTypes }.distinct().singleOrNull()
}

internal fun StructuralRefactorPass.isNeoForgeAttachmentTypeReference(
    rawType: String,
    packageName: String,
    imports: Map<String, String>,
    wildcardImports: Set<String>
): Boolean =
    resolveKnownJavaTypeReference(
        rawType,
        packageName,
        imports,
        wildcardImports,
        setOf("net.neoforged.neoforge.attachment.AttachmentType")
    ) == "net.neoforged.neoforge.attachment.AttachmentType"

internal fun StructuralRefactorPass.collectNitrogenSyncOwnerAccessors(javaFiles: List<Path>): Map<String, String> {
    val entityTypes = "(?:Player|ServerPlayer|Entity|LivingEntity|Mob|AbstractArrow|Projectile)"
    return javaFiles.mapNotNull { javaFile ->
        val text = javaFile.readText()
        val code = maskJavaComments(text)
        val executableCode = maskJavaCommentsAndLiterals(text)
        if (!executableCode.contains("INBTSynchable")) return@mapNotNull null
        val typeName = javaTopLevelTypeName(executableCode) ?: return@mapNotNull null
        val packageName = packageNameOf(code)
        val fqn = if (packageName.isBlank()) typeName else "$packageName.$typeName"
        val accessors = Regex("""\b(?:public\s+)?$entityTypes\s+(get[A-Za-z_$][\w$]*)\s*\(\s*\)\s*(?:;|\{)""")
            .findAll(executableCode)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        if (accessors.size == 1) fqn to accessors.single() else null
    }.toMap()
}

internal fun StructuralRefactorPass.rewriteNitrogenGetSyncPacketSignature(source: String, entitySyncPackets: Set<String>): String {
    var result = Regex(
        """(public\s+SyncPacket\s+getSyncPacket\s*\()\s*String\s+key\s*,\s*Type\s+type\s*,\s*Object\s+value\s*\)"""
    ).replace(source) { match ->
        "${match.groupValues[1]}int entityID, String key, Type type, Object value)"
    }
    for (packetName in entitySyncPackets) {
        result = Regex("""new\s+${Regex.escape(packetName)}\s*\(\s*key\s*,\s*type\s*,\s*value\s*\)""")
            .replace(result, "new $packetName(entityID, key, type, value)")
    }
    return result
}

internal fun StructuralRefactorPass.inferNitrogenOwnerEntityId(source: String): String? {
    val entityTypes = "(?:Player|ServerPlayer|Entity|LivingEntity|Mob|ItemEntity|LightningBolt|AbstractArrow|Projectile)"
    val accessors = Regex("""\bpublic\s+$entityTypes\s+(get[A-Za-z_$][\w$]*)\s*\(\s*\)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
    if (accessors.size == 1) {
        return "this.${accessors.single()}().getId()"
    }
    val fields = Regex("""\bprivate\s+(?:final\s+)?$entityTypes\s+([A-Za-z_$][\w$]*)\s*;""")
        .findAll(source)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
    if (fields.size == 1) {
        return "this.${fields.single()}.getId()"
    }
    return null
}

internal fun StructuralRefactorPass.inferNitrogenReceiverOwnerEntityIds(source: String, ownerAccessors: Map<String, String>): Map<String, String> {
    if (ownerAccessors.isEmpty()) return emptyMap()
    val code = maskJavaComments(source)
    val executableCode = maskJavaCommentsAndLiterals(source)
    val packageName = packageNameOf(code)
    val imports = javaNonStaticImports(code)
    val wildcardImports = javaNonStaticWildcardImports(code)
    val knownTypes = ownerAccessors.keys
    val typeReference = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""
    val receiverTypes = mutableMapOf<String, MutableSet<String>>()
    fun record(receiver: String, rawType: String) {
        val resolvedType = resolveKnownJavaTypeReference(rawType, packageName, imports, wildcardImports, knownTypes)
            ?: return
        receiverTypes.getOrPut(receiver) { mutableSetOf() }.add(resolvedType)
    }
    Regex("""\b(?:final\s+)?($typeReference)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .forEach { match -> record(match.groupValues[2], match.groupValues[1]) }
    Regex("""\b($typeReference)\.get\s*\([^)]*\)\s*(?:\.resolve\s*\(\s*\))?\s*\.ifPresent\s*\(\s*\(?\s*([A-Za-z_$][\w$]*)""")
        .findAll(executableCode)
        .forEach { match -> record(match.groupValues[2], match.groupValues[1]) }
    return receiverTypes.mapNotNull { (receiver, types) ->
        val typeName = types.singleOrNull() ?: return@mapNotNull null
        val accessor = ownerAccessors[typeName] ?: return@mapNotNull null
        receiver to "$receiver.$accessor().getId()"
    }.toMap()
}

internal fun StructuralRefactorPass.rewriteNitrogenSyncCalls(
    source: String,
    methodName: String,
    ownerEntityId: String?,
    receiverOwnerEntityIds: Map<String, String>
): String {
    var result = source
    var cursor = 0
    val pattern = Regex("""(?:(this|[A-Za-z_$][\w$]*)\.)?${Regex.escape(methodName)}\s*\(""")
    while (true) {
        val match = pattern.find(result, cursor) ?: break
        val lineStart = result.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        val linePrefix = result.substring(lineStart, match.range.first)
        if (Regex("""\b(?:public|protected|private|abstract|default)\b""").containsMatchIn(linePrefix)) {
            cursor = match.range.last + 1
            continue
        }
        val openParen = result.indexOf('(', match.range.first)
        val closeParen = if (openParen >= 0) findMatchingParen(result, openParen) else -1
        if (openParen < 0 || closeParen <= openParen) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen)).toMutableList()
        if (args.isEmpty() || !args[0].contains("Direction.")) {
            cursor = closeParen + 1
            continue
        }
        val receiverName = match.groupValues.getOrNull(1).orEmpty()
        val receiverEntityId = if (receiverName.isNotBlank() && receiverName != "this") {
            receiverOwnerEntityIds[receiverName]
        } else {
            null
        }
        val idExpression = nitrogenSyncIdExpression(args, receiverName, ownerEntityId, receiverEntityId)
        if (idExpression == null) {
            cursor = closeParen + 1
            continue
        }
        if (args[0].contains("Direction.DIMENSION") && args.isNotEmpty()) {
            val lastIndex = args.lastIndex
            args[lastIndex] = stripDimensionAccessor(args[lastIndex])
        }
        val replacementArgs = (listOf(idExpression) + args).joinToString(", ")
        result = result.substring(0, openParen + 1) + replacementArgs + result.substring(closeParen)
        cursor = openParen + replacementArgs.length + 2
    }
    return result
}

internal fun StructuralRefactorPass.nitrogenSyncIdExpression(
    args: List<String>,
    receiverName: String,
    ownerEntityId: String?,
    receiverEntityId: String?
): String? {
    val direction = args.firstOrNull() ?: return null
    return when {
        direction.contains("Direction.DIMENSION") -> "-1"
        direction.contains("Direction.PLAYER") && args.size >= 2 -> {
            val target = args.last().trim()
            if (target.isBlank()) null else "${stripDimensionAccessor(target)}.getId()"
        }
        direction.contains("Direction.CLIENT") || direction.contains("Direction.SERVER") ->
            nitrogenCallSiteEntityId(receiverName, ownerEntityId, receiverEntityId)
        else -> null
    }
}

internal fun StructuralRefactorPass.nitrogenCallSiteEntityId(receiverName: String, ownerEntityId: String?, receiverEntityId: String?): String? {
    if (receiverName.isNotBlank() && receiverName != "this") {
        return receiverEntityId
    }
    return ownerEntityId
}

internal fun StructuralRefactorPass.stripDimensionAccessor(expression: String): String =
    expression.trim().removeSuffix(".dimension()")

internal fun StructuralRefactorPass.detectRequiredGeneratedCompatPackage(projectDir: Path, reason: String): String {
    val modId = detectModId(projectDir)
        ?: projectMetadataModId(projectDir)
        ?: error("Cannot derive generated compat package for $reason: missing @Mod annotation and mod metadata mod id")
    val sanitized = modId
        .lowercase()
        .replace(Regex("""[^a-z0-9_]"""), "_")
        .trim('_')
    require(sanitized.isNotBlank()) {
        "Cannot derive generated compat package for $reason: mod id '$modId' has no valid Java package segment"
    }
    val packageSegment = if (sanitized.first().isDigit()) "m$sanitized" else sanitized
    return "com.modporter.generated.$packageSegment.compat"
}

internal fun StructuralRefactorPass.migrateLegacyPackResourceApis(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    var detectedCompatPackage: String? = null
    fun compatPackage(): String {
        val existing = detectedCompatPackage
        if (existing != null) return existing
        val detected = detectRequiredGeneratedCompatPackage(projectDir, "legacy pack resource adapters")
        detectedCompatPackage = detected
        return detected
    }
    val changes = mutableListOf<Change>()
    var needsPathPackResources = false
    var needsDelegatingPackResources = false
    var needsLegacyResourcesSupplier = false

    for (javaFile in javaFiles) {
        val generatedPackagePath = detectedCompatPackage?.replace('.', '/')
        if (generatedPackagePath != null &&
            javaFile.toString().replace('\\', '/').contains("/src/main/java/$generatedPackagePath/")
        ) {
            continue
        }
        val original = javaFile.readText()
        var modified = original
        val hadPathPackResources =
            modified.contains("net.neoforged.neoforge.resource.PathPackResources") ||
                modified.contains("net.minecraftforge.resource.PathPackResources")
        val hadDelegatingPackResources =
            modified.contains("net.neoforged.neoforge.resource.DelegatingPackResources") ||
                modified.contains("net.minecraftforge.resource.DelegatingPackResources")

        if (hadPathPackResources) {
            val generatedCompatPackage = compatPackage()
            modified = modified
                .replace("import net.neoforged.neoforge.resource.PathPackResources;", "import $generatedCompatPackage.PathPackResources;")
                .replace("import net.minecraftforge.resource.PathPackResources;", "import $generatedCompatPackage.PathPackResources;")
                .replace("net.neoforged.neoforge.resource.PathPackResources", "$generatedCompatPackage.PathPackResources")
                .replace("net.minecraftforge.resource.PathPackResources", "$generatedCompatPackage.PathPackResources")
            needsPathPackResources = true
        }
        if (hadDelegatingPackResources) {
            val generatedCompatPackage = compatPackage()
            modified = modified
                .replace("import net.neoforged.neoforge.resource.DelegatingPackResources;", "import $generatedCompatPackage.DelegatingPackResources;")
                .replace("import net.minecraftforge.resource.DelegatingPackResources;", "import $generatedCompatPackage.DelegatingPackResources;")
                .replace("net.neoforged.neoforge.resource.DelegatingPackResources", "$generatedCompatPackage.DelegatingPackResources")
                .replace("net.minecraftforge.resource.DelegatingPackResources", "$generatedCompatPackage.DelegatingPackResources")
            needsDelegatingPackResources = true
        }

        val accessorMigrated = migratePackMetadataSectionAccessors(modified)
        modified = accessorMigrated

        val createRewrite = rewriteLegacyPackCreateCalls(modified) { compatPackage() }
        modified = createRewrite.first
        needsLegacyResourcesSupplier = needsLegacyResourcesSupplier || createRewrite.second

        val readMetaRewrite = rewriteLegacyPackReadMetaAndCreateCalls(modified) { compatPackage() }
        modified = readMetaRewrite.first
        needsLegacyResourcesSupplier = needsLegacyResourcesSupplier || readMetaRewrite.second

        val supplierRewrite = rewriteLegacyPackResourcesSupplierAssignments(modified) { compatPackage() }
        modified = supplierRewrite.first
        needsLegacyResourcesSupplier = needsLegacyResourcesSupplier || supplierRewrite.second

        val readInfoRewrite = rewriteLegacyPackReadInfoCalls(modified)
        modified = readInfoRewrite

        if (modified.contains("Pack.Info")) {
            modified = replaceExecutableRegex(
                modified,
                Regex("""\bPack\.Info\b(?!\s*\()""")
            ) { "Pack.Metadata" }
        }

        modified = addLegacyPackApiImports(modified)

        if (modified != original) {
            if (!dryRun) javaFile.writeText(modified)
            changes.add(Change(
                file = javaFile,
                line = 1,
                description = "Migrate legacy built-in pack resource APIs to Minecraft 1.21 PackLocationInfo/ResourcesSupplier contracts",
                before = "PathPackResources/DelegatingPackResources/Pack.create/Pack.Info legacy shape",
                after = "PackLocationInfo + PackSelectionConfig + generated PackResources adapters",
                confidence = Confidence.HIGH,
                ruleId = "struct-legacy-pack-resources"
            ))
        }
    }

    if (needsPathPackResources) {
        val generatedCompatPackage = compatPackage()
        changes.addAll(ensureGeneratedPackResourceCompat(
            srcDir,
            generatedCompatPackage,
            "PathPackResources.java",
            generatedPathPackResourcesSource(generatedCompatPackage),
            "Generate source-compatible PathPackResources adapter",
            "struct-generate-path-pack-resources",
            dryRun
        ))
    }
    if (needsDelegatingPackResources) {
        val generatedCompatPackage = compatPackage()
        changes.addAll(ensureGeneratedPackResourceCompat(
            srcDir,
            generatedCompatPackage,
            "DelegatingPackResources.java",
            generatedDelegatingPackResourcesSource(generatedCompatPackage),
            "Generate source-compatible DelegatingPackResources adapter",
            "struct-generate-delegating-pack-resources",
            dryRun
        ))
    }
    if (needsLegacyResourcesSupplier) {
        val generatedCompatPackage = compatPackage()
        changes.addAll(ensureGeneratedPackResourceCompat(
            srcDir,
            generatedCompatPackage,
            "LegacyPackResourcesSupplier.java",
            generatedLegacyPackResourcesSupplierSource(generatedCompatPackage),
            "Generate adapter for legacy single-method Pack.ResourcesSupplier lambdas",
            "struct-generate-legacy-pack-resources-supplier",
            dryRun
        ))
    }

    return changes
}

internal fun StructuralRefactorPass.addLegacyPackApiImports(source: String): String {
    var result = source
    if (result.contains("new PackLocationInfo(") || result.contains("Pack.readPackMetadata(")) {
        result = addImportIfMissing(result, "net.minecraft.server.packs.PackLocationInfo")
    }
    if (result.contains("new PackSelectionConfig(")) {
        result = addImportIfMissing(result, "net.minecraft.server.packs.PackSelectionConfig")
    }
    if (result.contains("PackCompatibility.forVersion(")) {
        result = addImportIfMissing(result, "net.minecraft.server.packs.repository.PackCompatibility")
        result = addImportIfMissing(result, "net.minecraft.util.InclusiveRange")
        result = addImportIfMissing(result, "net.minecraft.SharedConstants")
    }
    if (result.contains("Pack.readPackMetadata(")) {
        result = addImportIfMissing(result, "net.minecraft.SharedConstants")
        result = addImportIfMissing(result, "net.minecraft.network.chat.Component")
        result = addImportIfMissing(result, "net.minecraft.server.packs.PackType")
        result = addImportIfMissing(result, "net.minecraft.server.packs.repository.PackSource")
    }
    return result
}

internal fun StructuralRefactorPass.migratePackMetadataSectionAccessors(source: String): String {
    if (!source.contains("PackMetadataSection")) return source
    val executableCode = maskJavaCommentsAndLiterals(source)
    val metadataNames = Regex("""\bPackMetadataSection\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    if (metadataNames.isEmpty()) return source
    var result = source
    for (name in metadataNames) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(name)}\.getDescription\(\)""")
        ) { "$name.description()" }
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(name)}\.getPackFormat\s*\(\s*PackType\.[A-Z_]+\s*\)""")
        ) { "$name.packFormat()" }
    }
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyPackResourcesSupplierAssignments(
    source: String,
    compatPackage: () -> String
): Pair<String, Boolean> {
    var usedAdapter = false
    val pattern = Regex(
        """(Pack\.ResourcesSupplier\s+[A-Za-z_$][\w$]*\s*=\s*)((?:\([^;\n{}]*\)|[A-Za-z_$][\w$]*)\s*->\s*[^;]+);"""
    )
    val rewritten = pattern.replace(source) { match ->
        val rhs = match.groupValues[2].trim()
        if (rhs.contains("LegacyPackResourcesSupplier")) return@replace match.value
        usedAdapter = true
        "${match.groupValues[1]}new ${compatPackage()}.LegacyPackResourcesSupplier($rhs);"
    }
    return rewritten to usedAdapter
}

internal fun StructuralRefactorPass.rewriteLegacyPackReadInfoCalls(source: String): String {
    var result = source
    var cursor = 0
    val token = "Pack.readPackInfo("
    while (true) {
        val tokenIndex = result.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + token.length - 1
        val closeParen = findMatchingParen(result, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 2) {
            cursor = closeParen + 1
            continue
        }
        val id = args[0].trim()
        val resources = args[1].trim()
        val replacement =
            "Pack.readPackMetadata(new PackLocationInfo($id, Component.literal(String.valueOf($id)), PackSource.BUILT_IN, java.util.Optional.empty()), $resources, SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES))"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyPackCreateCalls(
    source: String,
    compatPackage: () -> String
): Pair<String, Boolean> {
    var result = source
    var usedSupplierAdapter = false
    var cursor = 0
    val token = "Pack.create("
    while (true) {
        val tokenIndex = result.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + token.length - 1
        val closeParen = findMatchingParen(result, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 9) {
            cursor = closeParen + 1
            continue
        }
        val supplier = wrapLegacyPackResourcesSupplier(args[3], compatPackage)
        usedSupplierAdapter = usedSupplierAdapter || supplier != args[3].trim()
        val metadata = rewriteLegacyPackInfoExpression(args[4], args[5])
        val location = "new PackLocationInfo(${args[0]}, ${args[1]}, ${args[8]}, java.util.Optional.empty())"
        val selection = "new PackSelectionConfig(${args[2]}, ${args[6]}, ${args[7]})"
        val replacement = "new Pack($location, $supplier, $metadata, $selection)"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result to usedSupplierAdapter
}

internal fun StructuralRefactorPass.rewriteLegacyPackReadMetaAndCreateCalls(
    source: String,
    compatPackage: () -> String
): Pair<String, Boolean> {
    var result = source
    var usedSupplierAdapter = false
    var cursor = 0
    val token = "Pack.readMetaAndCreate("
    while (true) {
        val tokenIndex = result.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + token.length - 1
        val closeParen = findMatchingParen(result, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 7) {
            cursor = closeParen + 1
            continue
        }
        val supplier = wrapLegacyPackResourcesSupplier(args[3], compatPackage)
        usedSupplierAdapter = usedSupplierAdapter || supplier != args[3].trim()
        val location = "new PackLocationInfo(${args[0].trim()}, ${args[1].trim()}, ${args[6].trim()}, java.util.Optional.empty())"
        val selection = "new PackSelectionConfig(${args[2].trim()}, ${args[5].trim()}, false)"
        val replacement = "Pack.readMetaAndCreate($location, $supplier, ${args[4].trim()}, $selection)"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result to usedSupplierAdapter
}

internal fun StructuralRefactorPass.wrapLegacyPackResourcesSupplier(expression: String, compatPackage: () -> String): String {
    val trimmed = expression.trim()
    if (!trimmed.contains("->") || trimmed.contains("LegacyPackResourcesSupplier")) return trimmed
    return "new ${compatPackage()}.LegacyPackResourcesSupplier($trimmed)"
}

internal fun StructuralRefactorPass.rewriteLegacyPackInfoExpression(expression: String, packTypeExpression: String): String {
    val trimmed = expression.trim()
    val args = parseNewPackInfoArgs(trimmed) ?: return trimmed
    if (args.size < 5) return trimmed
    val packType = packTypeExpression.trim()
    val selectedFormat = "($packType == PackType.SERVER_DATA ? ${args[1]} : ${args[2]})"
    val compatibility =
        "PackCompatibility.forVersion(new InclusiveRange<>($selectedFormat), SharedConstants.getCurrentVersion().getPackVersion($packType))"
    return "new Pack.Metadata(${args[0]}, $compatibility, ${args[3]}, java.util.List.of(), ${args[4]})"
}

internal fun StructuralRefactorPass.parseNewPackInfoArgs(expression: String): List<String>? {
    val prefixes = listOf("new Pack.Info(", "new Pack.Metadata(")
    val prefix = prefixes.firstOrNull { expression.startsWith(it) } ?: return null
    val openParen = prefix.length - 1
    val closeParen = findMatchingParen(expression, openParen)
    if (closeParen != expression.length - 1) return null
    return splitTopLevelJavaArgs(expression.substring(openParen + 1, closeParen))
}

internal fun StructuralRefactorPass.ensureGeneratedPackResourceCompat(
    srcDir: Path,
    compatPackage: String,
    fileName: String,
    source: String,
    description: String,
    ruleId: String,
    dryRun: Boolean
): List<Change> {
    val compatFile = srcDir.resolve(compatPackage.replace('.', '/')).resolve(fileName)
    if (compatFile.exists() && compatFile.readText() == source) return emptyList()
    if (!dryRun) {
        compatFile.parent.createDirectories()
        compatFile.writeText(source)
    }
    return listOf(Change(
        file = compatFile,
        line = 1,
        description = description,
        before = "(missing generated pack resource adapter)",
        after = compatPackage,
        confidence = Confidence.HIGH,
        ruleId = ruleId
    ))
}

internal fun StructuralRefactorPass.generatedPathPackResourcesSource(packageName: String): String = """
package $packageName;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

/**
 * Source-compatible adapter generated by modporter for the removed legacy
 * PathPackResources constructors.
 */
public class PathPackResources extends net.minecraft.server.packs.PathPackResources {
    private final boolean hidden;
    private final Path root;

    public PathPackResources(String id, boolean hidden, Path root) {
        this(new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()), hidden, root);
    }

    public PathPackResources(PackLocationInfo location, Path root) {
        this(location, false, root);
    }

    public PathPackResources(PackLocationInfo location, boolean hidden, Path root) {
        super(location, root);
        this.hidden = hidden;
        this.root = root;
    }

    @Override
    public boolean isHidden() {
        return this.hidden;
    }

    public Path root() {
        return this.root;
    }

    protected Path resolve(String... paths) {
        Path resolved = this.root;
        for (String path : paths) {
            resolved = resolved.resolve(path);
        }
        return resolved;
    }

    public static class PathResourcesSupplier implements Pack.ResourcesSupplier {
        private final Path content;
        private final boolean hidden;

        public PathResourcesSupplier(Path content) {
            this(content, false);
        }

        public PathResourcesSupplier(Path content, boolean hidden) {
            this.content = content;
            this.hidden = hidden;
        }

        @Override
        public PackResources openPrimary(PackLocationInfo location) {
            return new PathPackResources(location, this.hidden, this.content);
        }

        @Override
        public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
            PackResources primary = this.openPrimary(location);
            List<String> overlays = metadata.overlays();
            if (overlays.isEmpty()) {
                return primary;
            }
            List<PackResources> overlayPacks = new ArrayList<>(overlays.size());
            for (String overlay : overlays) {
                overlayPacks.add(new PathPackResources(location, this.hidden, this.content.resolve(overlay)));
            }
            return new CompositePackResources(primary, overlayPacks);
        }
    }
}
""".trimStart()

internal fun StructuralRefactorPass.generatedLegacyPackResourcesSupplierSource(packageName: String): String = """
package $packageName;

import java.util.Objects;
import java.util.function.Function;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

/**
 * Adapter generated by modporter for old single-method Pack.ResourcesSupplier
 * lambdas. It preserves the old supplier contract and exposes it through the
 * Minecraft 1.21 openPrimary/openFull interface.
 */
public record LegacyPackResourcesSupplier(Function<String, ? extends PackResources> factory) implements Pack.ResourcesSupplier {
    public LegacyPackResourcesSupplier {
        Objects.requireNonNull(factory, "factory");
    }

    @Override
    public PackResources openPrimary(PackLocationInfo location) {
        return this.factory.apply(location.id());
    }

    @Override
    public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
        return this.openPrimary(location);
    }
}
""".trimStart()

internal fun StructuralRefactorPass.generatedDelegatingPackResourcesSource(packageName: String): String = """
package $packageName;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * Source-compatible implementation of the removed DelegatingPackResources base.
 * It preserves the legacy constructor contract by merging namespace lookups
 * across the supplied child packs.
 */
public class DelegatingPackResources extends AbstractPackResources {
    private final boolean hidden;
    private final PackMetadataSection packInfo;
    private final List<PackResources> packs;
    private final Map<String, List<PackResources>> assets;
    private final Map<String, List<PackResources>> data;

    public DelegatingPackResources(String id, boolean hidden, PackMetadataSection packInfo, List<? extends PackResources> packs) {
        super(new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()));
        this.hidden = hidden;
        this.packInfo = packInfo;
        this.packs = List.copyOf(packs);
        this.assets = this.buildNamespaceMap(PackType.CLIENT_RESOURCES, packs);
        this.data = this.buildNamespaceMap(PackType.SERVER_DATA, packs);
    }

    private Map<String, List<PackResources>> buildNamespaceMap(PackType type, List<? extends PackResources> packList) {
        Map<String, List<PackResources>> map = new LinkedHashMap<>();
        for (PackResources pack : packList) {
            for (String namespace : pack.getNamespaces(type)) {
                map.computeIfAbsent(namespace, key -> new ArrayList<>()).add(pack);
            }
        }
        map.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(map);
    }

    @Override
    public boolean isHidden() {
        return this.hidden;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        for (PackResources pack : this.packs) {
            IoSupplier<InputStream> resource = pack.getRootResource(paths);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) throws IOException {
        return deserializer.getMetadataSectionName().equals("pack") ? (T) this.packInfo : null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        for (PackResources pack : this.getCandidatePacks(type, location)) {
            IoSupplier<InputStream> resource = pack.getResource(type, location);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        for (PackResources pack : this.packs) {
            pack.listResources(type, namespace, path, output);
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? this.assets.keySet() : this.data.keySet();
    }

    @Override
    public void close() {
        for (PackResources pack : this.packs) {
            pack.close();
        }
    }

    @Nullable
    public List<PackResources> getChildren() {
        return this.packs;
    }

    private List<PackResources> getCandidatePacks(PackType type, ResourceLocation location) {
        Map<String, List<PackResources>> map = type == PackType.CLIENT_RESOURCES ? this.assets : this.data;
        return map.getOrDefault(location.getNamespace(), Collections.emptyList());
    }
}
""".trimStart()


internal fun StructuralRefactorPass.rewriteUnusedLegacyEnchantmentSubclasses(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val sources = javaFiles.associateWith { it.readText() }
    val extendsByClass = sources.mapNotNull { (file, source) ->
        val match = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+([A-Za-z_$][\w$]*)\b""").find(source)
            ?: return@mapNotNull null
        file to (match.groupValues[1] to match.groupValues[2])
    }

    val legacyNames = linkedSetOf<String>()
    extendsByClass
        .filter { (_, classAndBase) -> classAndBase.second == "Enchantment" }
        .forEach { (_, classAndBase) -> legacyNames.add(classAndBase.first) }
    var changed: Boolean
    do {
        changed = false
        extendsByClass.forEach { (_, classAndBase) ->
            if (classAndBase.second in legacyNames && legacyNames.add(classAndBase.first)) {
                changed = true
            }
        }
    } while (changed)
    if (legacyNames.isEmpty()) return emptyList()

    val allSource = sources.values.joinToString("\n")
    val changes = mutableListOf<Change>()
    for ((file, source) in sources) {
        val classMatch = Regex("""\bpublic\s+class\s+([A-Za-z_$][\w$]*)\s+extends\s+([A-Za-z_$][\w$]*)\b""").find(source)
            ?: continue
        val className = classMatch.groupValues[1]
        if (className !in legacyNames) continue
        if (Regex("""\bnew\s+${Regex.escape(className)}\s*\(""").containsMatchIn(allSource) ||
            Regex("""${Regex.escape(className)}\s*::\s*new""").containsMatchIn(allSource)) {
            continue
        }

        val rewritten = legacyEnchantmentUtilitySource(source, className)
        if (rewritten != source) {
            if (!dryRun) file.writeText(rewritten)
            changes.add(Change(
                file = file,
                line = 1,
                description = "Rewrite unused legacy Enchantment subclass as utility holder",
                before = "class $className extends Enchantment",
                after = "final utility class preserving static helper methods",
                confidence = Confidence.HIGH,
                ruleId = "struct-unused-enchantment-subclass-helper"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.legacyEnchantmentUtilitySource(source: String, className: String): String {
    val packageLine = Regex("""(?m)^package\s+[\w.]+\s*;\s*""").find(source)?.value?.trimEnd().orEmpty()
    val imports = source.lines()
        .filter { it.trim().startsWith("import ") }
        .filterNot { line ->
            val trimmed = line.trim()
            trimmed == "import net.minecraft.world.item.enchantment.Enchantment;" ||
                trimmed == "import net.minecraft.world.item.enchantment.Enchantments;" ||
                trimmed.contains(".EnchantmentCategory;") ||
                trimmed.contains(".MobType;")
        }
        .joinToString(System.lineSeparator())
    val staticMethods = extractStaticJavaMethods(source)
    val body = buildString {
        appendLine("public final class $className {")
        appendLine()
        appendLine("    private $className() {")
        appendLine("    }")
        if (staticMethods.isNotEmpty()) {
            appendLine()
            append(staticMethods.joinToString(System.lineSeparator() + System.lineSeparator()) { indentJavaBlock(it.trim(), "    ") })
            appendLine()
        }
        appendLine("}")
    }.trimEnd()
    return listOf(packageLine, imports, body)
        .filter { it.isNotBlank() }
        .joinToString(System.lineSeparator() + System.lineSeparator()) + System.lineSeparator()
}

internal fun StructuralRefactorPass.extractStaticJavaMethods(source: String): List<String> {
    val methods = mutableListOf<String>()
    val pattern = Regex("""(?m)^[ \t]*(?:public|protected|private)\s+static\s+[^=;]+?\)\s*\{""")
    for (match in pattern.findAll(source)) {
        val openBrace = source.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (closeBrace > openBrace) {
            methods.add(source.substring(match.range.first, closeBrace + 1))
        }
    }
    return methods
}

internal fun StructuralRefactorPass.indentJavaBlock(source: String, indent: String): String =
    source.lines().joinToString(System.lineSeparator()) { line ->
        if (line.isBlank()) line else indent + line.trimEnd()
    }

internal fun StructuralRefactorPass.removeMethodByName(source: String, methodName: String): String {
    val method = javaMethodRangesIncludingDefault(source).firstOrNull { it.name == methodName } ?: return source
    val signatureStart = source.lastIndexOf('\n', method.range.first).let { if (it < 0) 0 else it + 1 }
    val annotationStart = Regex("""(?m)(?:^[ \t]*@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\r?\n)+[ \t]*$""")
        .findAll(source.substring(0, signatureStart))
        .lastOrNull()
        ?.range
        ?.first
        ?: signatureStart
    var end = method.range.last + 1
    if (end < source.length && source[end] == '\r') end++
    if (end < source.length && source[end] == '\n') end++
    return source.removeRange(annotationStart, end)
}

internal fun StructuralRefactorPass.removeInnerClassByName(source: String, className: String): String {
    val classMatch = Regex("""(?m)^[ \t]*(?:private|public|protected)?\s*static\s+final\s+class\s+${Regex.escape(className)}\b""")
        .find(source)
        ?: return source
    val openBrace = source.indexOf('{', classMatch.range.first)
    if (openBrace < 0) return source
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace < 0) return source
    var end = closeBrace + 1
    if (end < source.length && source[end] == '\r') end++
    if (end < source.length && source[end] == '\n') end++
    return source.removeRange(classMatch.range.first, end)
}

internal data class BlockEntityFluidCapabilityMigration(
    val file: Path,
    val packageName: String,
    val className: String
)

internal fun StructuralRefactorPass.migrateBlockEntityFluidCapabilities(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val migrations = mutableListOf<BlockEntityFluidCapabilityMigration>()

    for (javaFile in javaFiles) {
        val original = javaFile.readText()
        if (!original.contains("extends BlockEntity") ||
            !original.contains("getCapability") ||
            !original.contains("Capabilities.FluidHandler.BLOCK") ||
            !original.contains("FluidTank")
        ) {
            continue
        }

        val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+BlockEntity\b""")
            .find(original)
            ?.groupValues
            ?.get(1)
            ?: continue
        val registryRef = Regex(
            """super\s*\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.([A-Z0-9_]+)\.get\(\)"""
        ).find(original) ?: continue
        val registryOwner = registryRef.groupValues[1]
        val registryField = registryRef.groupValues[2]
        val fluidTankFields = Regex(
            """(?m)^[ \t]*(?:private|protected|public)?\s*(?:final\s+)?FluidTank\s+([A-Za-z_$][\w$]*)\s*(?:=|;)"""
        ).findAll(original)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        if (fluidTankFields.size != 1) {
            continue
        }
        val tankName = fluidTankFields.single()
        val getCapabilityMethod = javaDeclaredMethodText(original, "getCapability") ?: continue
        val capabilityTokens = legacyBlockEntityCapabilityTokens(getCapabilityMethod)
        if (capabilityTokens.any { it != "Capabilities.FluidHandler.BLOCK" }) {
            continue
        }
        val handlerExpression = blockEntityCapabilityHandlerExpression(getCapabilityMethod, tankName)

        var modified = original
        modified = Regex(
            """(?m)^[ \t]*private\s+final\s+LazyOptional<[^;\r\n]+>\s+[A-Za-z_$][\w$]*\s*=\s*LazyOptional\.of\(\(\)\s*->\s*${Regex.escape(tankName)}\s*\);\s*\r?\n"""
        ).replace(modified, "")
        modified = removeMethodByName(modified, "getCapability")
        modified = removeMethodByName(modified, "invalidateCaps")
        modified = removeDeadLazyOptionalFields(modified)

        if (handlerExpression == "getFluidTank()" && !modified.contains("FluidTank getFluidTank()")) {
            modified = insertBeforeLastClassBrace(modified, """

    public FluidTank getFluidTank() {
        return $tankName;
    }
""".trimEnd())
        }

        if (!modified.contains("registerCapabilities(RegisterCapabilitiesEvent")) {
            modified = insertBeforeLastClassBrace(modified, """

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                $registryOwner.$registryField.get(),
                ($className blockEntity, net.minecraft.core.Direction side) -> blockEntity.$handlerExpression);
    }
""".trimEnd())
        }

        modified = addImportIfMissing(modified, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
        modified = removeImport(modified, "com.modporter.compat.Capability")
        modified = removeImport(modified, "com.modporter.compat.LazyOptional")
        Regex("""(?m)^import\s+(com\.modporter\.generated\.[\w.]+\.compat\.LazyOptional)\s*;""").find(modified)?.let { generated ->
            modified = removeUnusedSimpleImport(modified, generated.groupValues[1], "LazyOptional")
        }
        modified = removeUnusedSimpleImport(
            modified,
            "net.neoforged.neoforge.fluids.capability.IFluidHandler",
            "IFluidHandler"
        )
        if (!modified.contains("@NotNull")) {
            modified = removeImport(modified, "org.jetbrains.annotations.NotNull")
        }

        if (modified == original) continue

        if (!dryRun) {
            javaFile.writeText(modified)
        }
        migrations.add(BlockEntityFluidCapabilityMigration(javaFile, packageNameOf(modified), className))
        changes.add(Change(
            file = javaFile,
            line = 0,
            description = "Migrate BlockEntity fluid handler from getCapability() to RegisterCapabilitiesEvent",
            before = "getCapability(Capabilities.FluidHandler.BLOCK) -> LazyOptional fluid handler",
            after = "RegisterCapabilitiesEvent.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ...)",
            confidence = Confidence.HIGH,
            ruleId = "struct-blockentity-fluid-capability"
        ))
    }

    if (migrations.isEmpty()) return changes

    val mainClass = detectModMainClass(projectDir) ?: return changes
    var mainText = mainClass.readText()
    val originalMainText = mainText
    val mainPackage = packageNameOf(mainText)
    val constructorBusName = requireModEventBusName(mainText, "block entity fluid capability listener registration")

    for (migration in migrations) {
        if (mainText.contains("${migration.className}::registerCapabilities")) continue
        if (migration.packageName != mainPackage) {
            mainText = addImportIfMissing(mainText, "${migration.packageName}.${migration.className}")
        }
        val registration = "        $constructorBusName.addListener(${migration.className}::registerCapabilities);"
        mainText = insertModBusListener(mainText, constructorBusName, registration, "BlockEntitiesRegister")
        changes.add(Change(
            file = mainClass,
            line = 0,
            description = "Register ${migration.className} block-entity capabilities on the mod event bus",
            before = "(no block-entity capability listener)",
            after = "$constructorBusName.addListener(${migration.className}::registerCapabilities)",
            confidence = Confidence.HIGH,
            ruleId = "struct-blockentity-fluid-capability-listener"
        ))
    }

    if (mainText != originalMainText && !dryRun) {
        mainClass.writeText(mainText)
    }

    return changes
}

internal data class BlockEntityLegacyCapabilityMigration(
    val file: Path,
    val packageName: String,
    val className: String,
    val preferredRegistryClassName: String
)

internal data class BlockEntityCapabilityProviderSpec(
    val capabilityExpression: String,
    val providerExpression: String,
    val consumedLazyOptionalFields: Set<String> = emptySet()
)

internal data class JavaIfBlock(val condition: String, val body: String)

internal fun StructuralRefactorPass.migrateBlockEntityLegacyCapabilities(
    projectDir: Path,
    dryRun: Boolean,
    errors: MutableList<String>
): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val migrations = mutableListOf<BlockEntityLegacyCapabilityMigration>()
    val javaFiles = Files.walk(srcDir).use { stream ->
        stream.filter { it.extension == "java" }.toList()
    }
    val exactMigrationSources = javaFiles.associateWith { file -> file.readText() }
    val initialErrorCount = errors.size
    fun restoreExactMigrationSources() {
        if (dryRun) return
        exactMigrationSources.forEach { (file, source) ->
            if (file.readText() != source) file.writeText(source)
        }
    }

    try {
        val registryGraph = ExactBlockEntityRegistryGraph.build(srcDir)
        val legacyBlockCapabilityGraph = ExactLegacyBlockCapabilityGraph.build(srcDir)
        val lazyOptionalProviderResolver = ExactLegacyLazyOptionalProviderResolver(srcDir)
        val legacySourceIndex = JavaProjectTypeIndex.build(srcDir)
        changes += legacyBlockCapabilityGraph.migrateDeclarationsAndPredicates(dryRun)

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            if (!original.contains("getCapability") ||
                !original.contains("LazyOptional") ||
                original.contains("registerCapabilities(RegisterCapabilitiesEvent")
            ) {
                continue
            }

            val capabilityMethods = legacySourceIndex.unit(javaFile)
                .findAll(MethodDeclaration::class.java)
                .filter { method ->
                    if (method.nameAsString != "getCapability") return@filter false
                    val owner = exactEnclosingNamedClass(method)?.fullyQualifiedName?.orElse(null)
                        ?: return@filter false
                    legacySourceIndex.isTypeAssignableTo(
                        owner,
                        "net.minecraft.world.level.block.entity.BlockEntity"
                    )
                }
            if (capabilityMethods.size > 1) {
                val relative = projectDir.relativize(javaFile).invariantSeparatorsPathString
                errors.add(
                    "Cannot migrate BlockEntity getCapability in $relative: multiple BlockEntity capability " +
                        "methods in one source file require separate owner edits"
                )
                continue
            }
            val capabilityMethod = capabilityMethods.singleOrNull() ?: continue
            val ownerDeclaration = exactEnclosingNamedClass(capabilityMethod)
                ?: throw IllegalStateException("Cannot resolve getCapability owner in $javaFile")
            val blockEntityType = ownerDeclaration.fullyQualifiedName.orElse(null)
                ?: throw IllegalStateException("Cannot resolve getCapability owner type in $javaFile")
            val className = ownerDeclaration.nameAsString
            val getCapabilityMethod = capabilityMethod.toString()
            val capabilityTokens = legacyBlockEntityCapabilityTokens(getCapabilityMethod)
            val externalContracts = legacyBlockCapabilityGraph.contractsUsedBy(
                javaFile,
                blockEntityType,
                "getCapability"
            )
            val capabilityParameter = Regex(
                """(?:Capability|[A-Za-z_$][\w$]*\.Capability)\s*<[^>]+>\s+([A-Za-z_$][\w$]*)"""
            ).find(maskJavaCommentsAndLiterals(getCapabilityMethod))?.groupValues?.get(1)
            val sideParameter = getCapabilityDirectionParameterName(getCapabilityMethod)
            val exactLazyOptionalProvider = capabilityParameter?.let { parameterName ->
                { returnExpression: String ->
                    lazyOptionalProviderResolver.resolve(
                        file = javaFile,
                        ownerType = blockEntityType,
                        methodName = "getCapability",
                        returnExpression = returnExpression,
                        capabilityParameter = parameterName,
                        sideParameter = sideParameter
                    )?.let(::LegacyCapabilityProvider)
                }
            }
            val exactGuardedTailProvider = capabilityParameter?.let { parameterName ->
                { contract: ExactLegacyBlockCapabilityGraph.PredicateContract ->
                    lazyOptionalProviderResolver.resolveGuardedTail(
                        file = javaFile,
                        ownerType = blockEntityType,
                        methodName = "getCapability",
                        predicateMethod = contract.methodName,
                        capabilityParameter = parameterName,
                        sideParameter = sideParameter
                    )
                }
            }
            val exactInstanceExpression = { expression: String ->
                lazyOptionalProviderResolver.rewriteInstanceExpression(
                    file = javaFile,
                    ownerType = blockEntityType,
                    methodName = "getCapability",
                    expressionText = expression,
                    parameterRenames = mapOf(sideParameter to "side")
                )
            }
            val graphRegistryRefs = registryGraph.referencesFor(blockEntityType)
            val registryRefs = if (graphRegistryRefs.isNotEmpty()) {
                graphRegistryRefs
            } else {
                registryGraph.referencesForAssignableTo(blockEntityType).ifEmpty {
                    blockEntityRegistryRefFromConstructors(original)?.let { listOf("$it.get()") }.orEmpty()
                }
            }
            if (registryRefs.isEmpty()) {
                val relative = projectDir.relativize(javaFile).invariantSeparatorsPathString
                errors.add(
                    "Cannot migrate BlockEntity getCapability in $relative: no exact BlockEntityType registry " +
                        "reference for $blockEntityType"
                )
                continue
            }
            val externalProviders = extractExternalBlockEntityCapabilityProviders(
                getCapabilityMethod,
                original,
                externalContracts,
                exactLazyOptionalProvider,
                exactGuardedTailProvider,
                exactInstanceExpression
            )
            if (externalProviders.size != externalContracts.size) {
                val relative = projectDir.relativize(javaFile).invariantSeparatorsPathString
                errors.add(
                    "Cannot migrate BlockEntity getCapability in $relative: external block capability " +
                        "predicate/provider branch is not structurally closed"
                )
                continue
            }
            val providers = (
                extractBlockEntityLegacyCapabilityProviders(
                    getCapabilityMethod,
                    original,
                    exactLazyOptionalProvider,
                    exactInstanceExpression
                ) + externalProviders
                ).distinctBy { it.capabilityExpression }
            if (providers.isEmpty()) {
                if (isNoOpLegacyBlockEntityCapabilityOverride(getCapabilityMethod, capabilityTokens)) {
                    var modified = original
                    modified = removeMethodByName(modified, "getCapability")
                    modified = cleanupLegacyCapabilityOverrideImports(modified)
                    if (modified != original) {
                        if (!dryRun) {
                            javaFile.writeText(modified)
                        }
                        changes.add(Change(
                            file = javaFile,
                            line = 0,
                            description = "Remove no-op legacy BlockEntity getCapability override with no provider branch",
                            before = "getCapability(Capabilities.*.BLOCK) { /* no provider */ }",
                            after = "(override removed)",
                            confidence = Confidence.HIGH,
                            ruleId = "struct-blockentity-noop-capability-override"
                        ))
                    }
                    continue
                }
                val relative = projectDir.relativize(javaFile).invariantSeparatorsPathString
                errors.add(
                    "Cannot migrate BlockEntity getCapability in $relative: no source-shaped provider for " +
                        capabilityTokens.ifEmpty { listOf("Capabilities.*") }.joinToString(", ")
                )
                continue
            }
            val unconsumedBranches = unconsumedLegacyCapabilityBranches(
                getCapabilityMethod,
                providers,
                externalContracts
            )
            if (unconsumedBranches.isNotEmpty()) {
                val relative = projectDir.relativize(javaFile).invariantSeparatorsPathString
                errors.add(
                    "Cannot partially migrate BlockEntity getCapability in $relative: unproven predicate branches " +
                        unconsumedBranches.joinToString("; ")
                )
                continue
            }
            val migratedCapabilities = providers.map { it.capabilityExpression }.toSet()
            val unmigratedCapabilities = capabilityTokens.filterNot { it in migratedCapabilities }
            if (unmigratedCapabilities.isNotEmpty()) {
                val relative = projectDir.relativize(javaFile).invariantSeparatorsPathString
                errors.add(
                    "Cannot partially migrate BlockEntity getCapability in $relative: unsupported capability branches " +
                        unmigratedCapabilities.joinToString(", ")
                )
                continue
            }

            var modified = original
            val consumedFields = providers.flatMap { it.consumedLazyOptionalFields }.toSet()
            modified = removeMethodByName(modified, "getCapability")
            modified = migrateLegacyCapabilityReviveCapsSource(modified)
            modified = removeLegacyCapabilityCacheState(modified, consumedFields, className)
            modified = cleanupLegacyCapabilityOverrideImports(modified)
            modified = addImportIfMissing(modified, "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent")
            if (providers.any { it.capabilityExpression.startsWith("Capabilities.") }) {
                modified = Regex(
                    """(?m)^[ \t]*import\s+com\.modporter\.generated\.[\w.]+\.compat\.Capabilities;\s*\r?\n"""
                ).replace(modified, "")
                modified = addImportIfMissing(modified, "net.neoforged.neoforge.capabilities.Capabilities")
            }

            val registerMethod = renderBlockEntityCapabilityRegistrationMethod(
                registryRefs = registryRefs,
                providers = providers
            )
            modified = insertBeforeExactClassClosingBrace(modified, className, registerMethod)

            if (modified == original) continue
            if (!dryRun) {
                javaFile.writeText(modified)
            }
            migrations.add(
                BlockEntityLegacyCapabilityMigration(
                    file = javaFile,
                    packageName = packageNameOf(modified),
                    className = className,
                    preferredRegistryClassName = registryRefs.first()
                        .removeSuffix(".get()")
                        .substringBeforeLast('.')
                        .substringAfterLast('.')
                )
            )
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Migrate source-shaped BlockEntity capabilities to RegisterCapabilitiesEvent",
                before = "getCapability(Capabilities.*.BLOCK) LazyOptional branches",
                after = "RegisterCapabilitiesEvent.registerBlockEntity providers",
                confidence = Confidence.HIGH,
                ruleId = "struct-blockentity-capability-provider"
            ))
        }

        if (errors.size > initialErrorCount) {
            restoreExactMigrationSources()
            return emptyList()
        }

        if (migrations.isEmpty()) return changes

        val mainClass = detectModMainClass(projectDir) ?: return changes
        var mainText = mainClass.readText()
        val originalMainText = mainText
        val mainPackage = packageNameOf(mainText)
        val constructorBusName = requireModEventBusName(mainText, "block entity capability listener registration")

        for (migration in migrations) {
            if (mainText.contains("${migration.className}::registerCapabilities")) continue
            if (migration.packageName != mainPackage) {
                mainText = addImportIfMissing(mainText, "${migration.packageName}.${migration.className}")
            }
            val registration = "        $constructorBusName.addListener(${migration.className}::registerCapabilities);"
            mainText = insertModBusListener(mainText, constructorBusName, registration, migration.preferredRegistryClassName)
            changes.add(Change(
                file = mainClass,
                line = 0,
                description = "Register ${migration.className} block-entity capabilities on the mod event bus",
                before = "(no block-entity capability listener)",
                after = "$constructorBusName.addListener(${migration.className}::registerCapabilities)",
                confidence = Confidence.HIGH,
                ruleId = "struct-blockentity-capability-provider-listener"
            ))
        }

        if (mainText != originalMainText && !dryRun) {
            mainClass.writeText(mainText)
        }

        return changes
    } catch (error: Exception) {
        restoreExactMigrationSources()
        throw error
    }
}

internal fun StructuralRefactorPass.blockEntityRegistryRefFromConstructors(source: String): String? {
    val id = """[A-Za-z_$][\w$]*"""
    val registryReference = """($id(?:\.$id)*)\.([A-Z0-9_]+)\.get\(\)"""
    Regex("""super\s*\(\s*$registryReference""")
        .find(source)
        ?.let { return "${it.groupValues[1]}.${it.groupValues[2]}" }
    Regex("""\bthis\s*\(\s*$registryReference""")
        .find(source)
        ?.let { return "${it.groupValues[1]}.${it.groupValues[2]}" }
    return null
}

/**
 * The 1.21 provider returns what the legacy getCapability() wrapped for the fluid handler: the tank
 * field (getFluidTank()) or, as in ShowerCore's pool members, another expression such as the
 * controller-resolved handler, evaluated on the block entity.
 */
internal fun StructuralRefactorPass.blockEntityCapabilityHandlerExpression(getCapabilityMethod: String, tankName: String): String {
    val executable = maskJavaCommentsAndLiterals(getCapabilityMethod)
    val wrapped = Regex("""LazyOptional\.of\(\s*\(\)\s*->\s*((?:this\.)?[A-Za-z_$][\w$]*(?:\(\))?)\s*\)""").find(executable)
        ?: return "getFluidTank()"
    var expression = wrapped.groupValues[1].removePrefix("this.")
    if (!expression.endsWith("()")) {
        val local = Regex("""\b(?:var|final\s+var|[A-Z][\w$.]*(?:<[^>]*>)?)\s+${Regex.escape(expression)}\s*=\s*((?:this\.)?[A-Za-z_$][\w$]*(?:\(\))?)\s*;""")
            .find(executable)
        if (local != null) expression = local.groupValues[1].removePrefix("this.")
    }
    return if (expression == tankName) "getFluidTank()" else expression
}

/**
 * A LazyOptional field that only remains in invalidate()/reset statements once getCapability() and
 * invalidateCaps() are gone is dead: the level owns the capability lifetime in 1.21.
 */
internal fun StructuralRefactorPass.removeDeadLazyOptionalFields(source: String): String {
    var result = source
    val fieldPattern = Regex("""(?m)^[ \t]*(?:private|protected|public)?\s*(?:final\s+)?LazyOptional<[^;=\r\n]+?>\s+([A-Za-z_$][\w$]*)\s*=\s*LazyOptional\.(?:empty|of)\([^;\r\n]*\)\s*;[ \t]*\r?\n?""")
    for (field in fieldPattern.findAll(maskJavaCommentsAndLiterals(result)).toList().asReversed()) {
        val name = field.groupValues[1]
        val escaped = Regex.escape(name)
        val invalidate = Regex("""(?:this\.)?$escaped\s*\.invalidate\(\)\s*;""")
        val reset = Regex("""(?:this\.)?$escaped\s*=\s*LazyOptional\.empty\(\)\s*;""")
        val lines = result.removeRange(field.range).split("\n").toMutableList()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val replaced = reset.replace(invalidate.replace(line, ""), "")
            if (replaced == line) {
                index++
            } else if (replaced.isBlank()) {
                lines.removeAt(index)
            } else {
                lines[index] = replaced
                index++
            }
        }
        val cleaned = lines.joinToString("\n")
        if (Regex("""(?<![\w$])$escaped\b""").containsMatchIn(maskJavaCommentsAndLiterals(cleaned))) continue
        result = cleaned
    }
    return result
}

internal fun StructuralRefactorPass.legacyBlockEntityCapabilityTokens(methodText: String): List<String> =
    Regex("""Capabilities\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?""")
        .findAll(methodText)
        .map { it.value }
        .distinct()
        .toList()

internal fun StructuralRefactorPass.unconsumedLegacyCapabilityBranches(
    methodText: String,
    providers: List<BlockEntityCapabilityProviderSpec>,
    externalContracts: List<ExactLegacyBlockCapabilityGraph.PredicateContract>
): List<String> {
    val method = runCatching { com.github.javaparser.StaticJavaParser.parseMethodDeclaration(methodText) }
        .getOrElse { error ->
            throw IllegalStateException("Cannot parse legacy getCapability method: ${error.message}", error)
        }
    val capabilityParameters = method.parameters.filter { parameter ->
        parameter.type.asClassOrInterfaceType().nameAsString == "Capability"
    }
    if (capabilityParameters.size != 1) {
        throw IllegalStateException(
            "Expected one legacy Capability parameter in getCapability, found ${capabilityParameters.size}"
        )
    }
    val capabilityName = capabilityParameters.single().nameAsString
    val capabilityExpressions = providers.map { it.capabilityExpression }.toSet()
    val externalPredicates = externalContracts.map { it.receiverName to it.methodName }.toSet()
    return method.findAll(IfStmt::class.java).mapNotNull { branch ->
        if (branch.thenStmt.findAll(ReturnStmt::class.java).isEmpty()) return@mapNotNull null
        val comparisons = branch.condition.findAll(BinaryExpr::class.java).mapNotNull { comparison ->
            when {
                comparison.left is NameExpr && comparison.left.asNameExpr().nameAsString == capabilityName ->
                    comparison.right.toString()
                comparison.right is NameExpr && comparison.right.asNameExpr().nameAsString == capabilityName ->
                    comparison.left.toString()
                else -> null
            }
        }
        val predicateCalls = branch.condition.findAll(MethodCallExpr::class.java).mapNotNull { call ->
            if (call.arguments.none { argument ->
                    argument is NameExpr && argument.nameAsString == capabilityName
                }
            ) {
                return@mapNotNull null
            }
            val receiver = when (val scope = call.scope.orElse(null)) {
                is NameExpr -> scope.nameAsString
                is FieldAccessExpr -> scope.nameAsString.takeIf { scope.scope is ThisExpr }
                else -> null
            }
            receiver?.let { it to call.nameAsString }
        }
        if (comparisons.isEmpty() && predicateCalls.isEmpty()) return@mapNotNull null
        val unknownComparisons = comparisons.filterNot { it in capabilityExpressions }
        val unknownPredicates = predicateCalls.filterNot { it in externalPredicates }
        if (unknownComparisons.isEmpty() && unknownPredicates.isEmpty()) return@mapNotNull null
        branch.condition.toString()
    }.distinct()
}

internal fun StructuralRefactorPass.isNoOpLegacyBlockEntityCapabilityOverride(methodText: String, capabilityTokens: List<String>): Boolean {
    if (capabilityTokens.isEmpty()) return false
    val executableMethod = maskJavaCommentsAndLiterals(methodText)
    val hasNoOpFallback = Regex("""return\s+super\.getCapability\s*\(""").containsMatchIn(executableMethod) ||
        Regex("""return\s+(?:[A-Za-z_$][\w$]*\.)?LazyOptional\.empty\s*\(\s*\)\s*;""").containsMatchIn(executableMethod)
    if (!hasNoOpFallback) return false

    return capabilityTokens.all { token ->
        val block = findIfBlockContainingToken(executableMethod, token) ?: return@all false
        val branchWithoutNoOpReturns = Regex(
            """return\s+(?:super\.getCapability\s*\([^;]*\)|(?:[A-Za-z_$][\w$]*\.)?LazyOptional\.empty\s*\(\s*\))\s*;"""
        ).replace(block.body, "")
        !Regex("""[A-Za-z_$][\w$]*""").containsMatchIn(branchWithoutNoOpReturns)
    }
}

internal fun StructuralRefactorPass.extractBlockEntityLegacyCapabilityProviders(
    methodText: String,
    source: String,
    exactLazyOptionalProvider: ((String) -> LegacyCapabilityProvider?)? = null,
    exactInstanceExpression: ((String) -> String?)? = null
): List<BlockEntityCapabilityProviderSpec> {
    val executableMethodText = maskJavaCommentsAndLiterals(methodText)
    val removedGuard = Regex("""!\s*(?:this\.)?isRemoved\s*\(\s*\)""").containsMatchIn(executableMethodText) ||
        Regex("""!\s*(?:this\.)?remove\b""").containsMatchIn(executableMethodText)
    val sideParameter = getCapabilityDirectionParameterName(executableMethodText)
    return listOf("Capabilities.ItemHandler.BLOCK", "Capabilities.FluidHandler.BLOCK")
        .mapNotNull { capability ->
            val block = findIfBlockContainingToken(executableMethodText, capability) ?: return@mapNotNull null
            val provider = extractBlockEntityCapabilityProviderExpression(
                branchBody = block.body,
                source = source,
                sideParameter = sideParameter,
                exactLazyOptionalProvider = exactLazyOptionalProvider
            ) ?: return@mapNotNull null
            val providerGuard = blockEntityCapabilityProviderGuard(
                condition = block.condition,
                capabilityExpression = capability,
                exactInstanceExpression = exactInstanceExpression
            ) ?: return@mapNotNull null
            val guards = (if (removedGuard) listOf("!blockEntity.isRemoved()") else emptyList()) + providerGuard
            val guardedProvider = if (guards.isNotEmpty()) {
                "(${guards.distinct().joinToString(" && ")}) ? ${provider.expression} : null"
            } else {
                provider.expression
            }
            BlockEntityCapabilityProviderSpec(capability, guardedProvider, provider.consumedLazyOptionalFields)
        }
}

internal fun StructuralRefactorPass.extractExternalBlockEntityCapabilityProviders(
    methodText: String,
    source: String,
    contracts: List<ExactLegacyBlockCapabilityGraph.PredicateContract>,
    exactLazyOptionalProvider: ((String) -> LegacyCapabilityProvider?)? = null,
    exactGuardedTailProvider: ((ExactLegacyBlockCapabilityGraph.PredicateContract) -> String?)? = null,
    exactInstanceExpression: ((String) -> String?)? = null
): List<BlockEntityCapabilityProviderSpec> {
    if (contracts.isEmpty()) return emptyList()
    val executableMethod = maskJavaCommentsAndLiterals(methodText)
    val capabilityParameter = Regex(
        """(?:Capability|[A-Za-z_$][\w$]*\.Capability)\s*<[^>]+>\s+([A-Za-z_$][\w$]*)"""
    ).find(executableMethod)?.groupValues?.get(1) ?: return emptyList()
    val id = """[A-Za-z_$][\w$]*"""
    val sideParameter = getCapabilityDirectionParameterName(executableMethod)
    return contracts.mapNotNull { contract ->
        if (contract.providerStrategy == ExactLegacyBlockCapabilityGraph.ProviderStrategy.BRANCH_RETURN) {
            exactGuardedTailProvider?.invoke(contract)?.let { provider ->
                return@mapNotNull BlockEntityCapabilityProviderSpec(
                    capabilityExpression = contract.targetExpression,
                    providerExpression = provider
                )
            }
        }
        val receiverPrefix = contract.receiverName?.let { "(?:this\\.)?${Regex.escape(it)}\\." }
            ?: "(?:this\\.)?"
        val predicatePattern = Regex(
            """$receiverPrefix${Regex.escape(contract.methodName)}\s*\(\s*${Regex.escape(capabilityParameter)}\s*\)"""
        )
        val block = findIfBlockContainingToken(
            executableMethod,
            contract.receiverName?.let { "$it.${contract.methodName}" } ?: "${contract.methodName}("
        )
            ?: return@mapNotNull null
        if (!predicatePattern.containsMatchIn(block.condition) ||
            Regex("""!\s*${predicatePattern.pattern}""").containsMatchIn(block.condition)
        ) return@mapNotNull null
        val guards = block.condition.split("&&")
            .map { it.trim() }
            .filter { it.isNotBlank() && !predicatePattern.containsMatchIn(it) }
            .map { guard ->
                exactInstanceExpression?.invoke(guard) ?: return@mapNotNull null
            }
        val provider = when (contract.providerStrategy) {
            ExactLegacyBlockCapabilityGraph.ProviderStrategy.RECEIVER_LAZY_OPTIONAL -> {
                val receiver = contract.receiverName ?: return@mapNotNull null
                if (!Regex(
                        """(?m)^[ \t]*(?:private|protected|public)\s+(?:final\s+)?[^;=()]+\s+${Regex.escape(receiver)}\s*(?:=|;)"""
                    ).containsMatchIn(maskJavaCommentsAndLiterals(source))
                ) {
                    return@mapNotNull null
                }
                val providerMethod = Regex(
                    """return\s+(?:this\.)?${Regex.escape(receiver)}\.($id)\s*\(\s*\)\s*;"""
                ).find(block.body)?.groupValues?.get(1) ?: return@mapNotNull null
                val typeWitness = when (contract.providerMethodTypeParameterCount) {
                    0 -> "."
                    1 -> ".<${contract.providerApiType ?: return@mapNotNull null}>"
                    else -> return@mapNotNull null
                }
                LegacyCapabilityProvider("blockEntity.$receiver$typeWitness$providerMethod().orElse(null)")
            }
            ExactLegacyBlockCapabilityGraph.ProviderStrategy.BRANCH_RETURN ->
                extractBlockEntityCapabilityProviderExpression(
                    branchBody = block.body,
                    source = source,
                    sideParameter = sideParameter,
                    exactLazyOptionalProvider = exactLazyOptionalProvider
                ) ?: return@mapNotNull null
        }
        BlockEntityCapabilityProviderSpec(
            capabilityExpression = contract.targetExpression,
            providerExpression = if (guards.isEmpty()) {
                provider.expression
            } else {
                "(${guards.joinToString(" && ")}) ? ${provider.expression} : null"
            },
            consumedLazyOptionalFields = provider.consumedLazyOptionalFields
        )
    }
}

internal fun StructuralRefactorPass.findIfBlockContainingToken(source: String, token: String): JavaIfBlock? {
    val tokenIndex = source.indexOf(token)
    if (tokenIndex < 0) return null
    var searchBefore = tokenIndex
    while (searchBefore >= 0) {
        val ifIndex = source.lastIndexOf("if", searchBefore)
        if (ifIndex < 0) return null
        val before = source.getOrNull(ifIndex - 1)
        val after = source.getOrNull(ifIndex + 2)
        if ((before != null && (before.isLetterOrDigit() || before == '_' || before == '$')) ||
            (after != null && (after.isLetterOrDigit() || after == '_' || after == '$'))) {
            searchBefore = ifIndex - 1
            continue
        }
        val openParen = source.indexOf('(', ifIndex)
        if (openParen < 0) return null
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) return null
        if (tokenIndex !in openParen..closeParen) {
            searchBefore = ifIndex - 1
            continue
        }
        val bodyStart = skipWhitespace(source, closeParen + 1)
        if (bodyStart >= source.length) return null
        if (source[bodyStart] != '{') {
            val statementEnd = findJavaStatementSemicolon(source, bodyStart)
            if (statementEnd < 0) return null
            return JavaIfBlock(
                condition = source.substring(openParen + 1, closeParen).trim(),
                body = source.substring(bodyStart, statementEnd + 1)
            )
        }
        val closeBrace = findMatchingBrace(source, bodyStart)
        if (closeBrace < 0) return null
        return JavaIfBlock(
            condition = source.substring(openParen + 1, closeParen).trim(),
            body = source.substring(bodyStart + 1, closeBrace)
        )
    }
    return null
}

internal data class LegacyCapabilityProvider(
    val expression: String,
    val consumedLazyOptionalFields: Set<String> = emptySet()
)

internal fun StructuralRefactorPass.getCapabilityDirectionParameterName(methodText: String): String {
    val signatureEnd = javaMethodOpeningBrace(methodText) ?: return "side"
    val signature = methodText.substring(0, signatureEnd)
    val paramsStart = signature.indexOf('(')
    val paramsEnd = signature.lastIndexOf(')')
    if (paramsStart < 0 || paramsEnd <= paramsStart) return "side"
    val params = splitTopLevelJavaArgs(signature.substring(paramsStart + 1, paramsEnd))
    return params.asReversed().firstNotNullOfOrNull { param ->
        Regex("""\bDirection\s+([A-Za-z_$][\w$]*)\b""").find(param)?.groupValues?.get(1)
    } ?: "side"
}

internal fun StructuralRefactorPass.blockEntityCapabilityProviderGuard(
    condition: String,
    capabilityExpression: String,
    exactInstanceExpression: ((String) -> String?)?
): List<String>? {
    val guards = condition.split("&&")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { part ->
            part.contains(capabilityExpression) ||
                Regex("""\b(?:cap|capability)\b""").containsMatchIn(part) &&
                part.contains("Capabilities.")
        }
        .map { part ->
            val removedGuard = when {
                Regex("""^!\s*(?:this\.)?remove$""").matches(part) -> "!blockEntity.isRemoved()"
                Regex("""^(?:this\.)?remove$""").matches(part) -> "blockEntity.isRemoved()"
                else -> null
            }
            val migrated = removedGuard ?: exactInstanceExpression?.invoke(part) ?: return null
            if (migrated.contains("Capabilities.")) return null
            migrated
        }
    return guards
}

internal fun StructuralRefactorPass.extractBlockEntityCapabilityProviderExpression(
    branchBody: String,
    source: String,
    sideParameter: String,
    exactLazyOptionalProvider: ((String) -> LegacyCapabilityProvider?)? = null
): LegacyCapabilityProvider? {
    parseLegacyCapabilityReturnChain(branchBody, source, sideParameter)
        ?.let { return it }

    val sideBranch = Regex(
        """(?s)if\s*\((.*?)\)\s*\{\s*return\s+([^;]+);\s*}\s*else\s*\{\s*return\s+([^;]+);\s*}"""
    ).find(branchBody)
    if (sideBranch != null) {
        var condition = sideBranch.groupValues[1].trim().replace("this.", "blockEntity.")
        if (sideParameter != "side") {
            condition = Regex("""\b${Regex.escape(sideParameter)}\b""").replace(condition, "side")
        }
        val whenTrue = legacyCapabilityReturnToProvider(sideBranch.groupValues[2], branchBody, source) ?: return null
        val whenFalse = legacyCapabilityReturnToProvider(sideBranch.groupValues[3], branchBody, source) ?: return null
        return LegacyCapabilityProvider(
            expression = "($condition ? ${whenTrue.expression} : ${whenFalse.expression})",
            consumedLazyOptionalFields = whenTrue.consumedLazyOptionalFields + whenFalse.consumedLazyOptionalFields
        )
    }

    val directReturn = Regex("""return\s+([^;]+);""").find(branchBody) ?: return null
    return legacyCapabilityReturnToProvider(directReturn.groupValues[1], branchBody, source)
        ?: exactLazyOptionalProvider?.invoke(directReturn.groupValues[1])
}

internal fun StructuralRefactorPass.parseLegacyCapabilityReturnChain(
    branchBody: String,
    source: String,
    sideParameter: String
): LegacyCapabilityProvider? {
    val returns = Regex("""return\s+([^;]+);""").findAll(branchBody).toList()
    if (returns.size < 2) return null
    val firstIf = Regex("""(?s)^\s*if\s*\((.*?)\)\s*\{""").find(branchBody) ?: return null
    val openBrace = branchBody.indexOf('{', firstIf.range.last)
    if (openBrace < 0) return null
    val closeBrace = findMatchingBrace(branchBody, openBrace)
    if (closeBrace < 0) return null
    val condition = firstIf.groupValues[1].trim()
    val ifBody = branchBody.substring(openBrace + 1, closeBrace)
    val trueReturn = Regex("""return\s+([^;]+);""").find(ifBody)?.groupValues?.get(1) ?: return null
    val elseIndex = branchBody.indexOf("else", closeBrace + 1)
    if (elseIndex < 0) return null
    val elseTail = branchBody.substring(elseIndex + "else".length).trimStart()
    val falseProvider = if (elseTail.startsWith("if")) {
        val nested = parseLegacyCapabilityReturnChain(elseTail.removePrefix("if").let { "if$it" }, source, sideParameter)
            ?: return null
        nested
    } else {
        val elseOpen = branchBody.indexOf('{', elseIndex)
        if (elseOpen < 0) return null
        val elseClose = findMatchingBrace(branchBody, elseOpen)
        if (elseClose < 0) return null
        val elseBody = branchBody.substring(elseOpen + 1, elseClose)
        val falseReturn = Regex("""return\s+([^;]+);""").find(elseBody)?.groupValues?.get(1) ?: return null
        legacyCapabilityReturnToProvider(falseReturn, branchBody, source) ?: return null
    }
    val trueProvider = legacyCapabilityReturnToProvider(trueReturn, branchBody, source) ?: return null
    var migratedCondition = condition.replace("this.", "blockEntity.")
    if (sideParameter != "side") {
        migratedCondition = Regex("""\b${Regex.escape(sideParameter)}\b""").replace(migratedCondition, "side")
    }
    return LegacyCapabilityProvider(
        expression = "($migratedCondition ? ${trueProvider.expression} : ${falseProvider.expression})",
        consumedLazyOptionalFields = trueProvider.consumedLazyOptionalFields + falseProvider.consumedLazyOptionalFields
    )
}

internal fun StructuralRefactorPass.legacyCapabilityReturnToProvider(
    returnExpression: String,
    branchBody: String,
    source: String
): LegacyCapabilityProvider? {
    val expression = returnExpression.trim()
    if (expression == "LazyOptional.empty()" || expression.endsWith(".empty()")) return LegacyCapabilityProvider("null")
    Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\.cast\(\)$""")
        .find(expression)
        ?.let { match ->
            val field = match.groupValues[1]
            val directProvider = legacyLazyOptionalFieldProvider(field, null, branchBody, source)
            if (directProvider != null) return directProvider
            return LegacyCapabilityProvider("blockEntity.$field.orElse(null)", setOf(field))
        }
    Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\s*\[\s*(\d+)\s*]\.cast\(\)$""")
        .find(expression)
        ?.let { match ->
            val field = match.groupValues[1]
            val index = match.groupValues[2].toIntOrNull()
            val indexedProvider = legacyLazyOptionalFieldProvider(field, index, branchBody, source)
            if (indexedProvider != null) return indexedProvider
            return LegacyCapabilityProvider("blockEntity.$field[$index].orElse(null)", setOf(field))
        }
    Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\.orElse\(\s*null\s*\)$""")
        .find(expression)
        ?.let { match ->
            val field = match.groupValues[1]
            val directProvider = legacyLazyOptionalFieldProvider(field, null, branchBody, source)
            if (directProvider != null) return directProvider
            return LegacyCapabilityProvider("blockEntity.$field.orElse(null)", setOf(field))
        }
    Regex("""^LazyOptional\.of\(\s*\(\)\s*->\s*(.*?)\s*\)\.cast\(\)$""")
        .find(expression)
        ?.let { return LegacyCapabilityProvider(it.groupValues[1].trim().replace("this.", "blockEntity.")) }
    return if (expression.startsWith("this.")) {
        LegacyCapabilityProvider(expression.replaceFirst("this.", "blockEntity."))
    } else {
        null
    }
}

internal fun StructuralRefactorPass.legacyLazyOptionalFieldProvider(
    fieldName: String,
    index: Int?,
    branchBody: String,
    source: String
): LegacyCapabilityProvider? {
    val preserveLazyOptionalField = legacyLazyOptionalFieldHasLifecycle(source, fieldName)
    val branchInitializer = if (index == null) {
        lazyOptionalAssignmentInitializer(branchBody, fieldName)
    } else {
        null
    }
    if (branchInitializer != null) {
        legacyLazyOptionalInitializerToProvider(branchInitializer)
            ?.let { return LegacyCapabilityProvider(it, setOf(fieldName)) }
        return LegacyCapabilityProvider("blockEntity.$fieldName.orElse(null)")
    }
    val initializer = if (index == null) {
        lazyOptionalAssignmentInitializer(source, fieldName)
    } else {
        null
    }
    if (initializer != null) {
        if (!preserveLazyOptionalField) {
            legacyLazyOptionalInitializerToProvider(initializer)
                ?.let { return LegacyCapabilityProvider(it, setOf(fieldName)) }
        }
        return LegacyCapabilityProvider("blockEntity.$fieldName.orElse(null)")
    }

    if (index != null) {
        val sidedWrapper = sidedInvWrapperProviderForIndex(fieldName, index, source)
        if (sidedWrapper != null) {
            return LegacyCapabilityProvider(sidedWrapper, setOf(fieldName))
        }
    }

    return null
}

internal fun StructuralRefactorPass.legacyLazyOptionalFieldHasLifecycle(source: String, fieldName: String): Boolean {
    val executable = maskJavaCommentsAndLiterals(source)
    val field = Regex.escape(fieldName)
    return Regex("""(?m)^[ \t]*(?:this\.)?$field(?:\s*\[[^]\r\n]+])?\.invalidate\s*\(""").containsMatchIn(executable) ||
        Regex("""(?m)^[ \t]*(?:this\.)?$field(?:\s*\[[^]\r\n]+])?\s*=\s*(?:(?:[\w.]+\.)?LazyOptional\.of|SidedInvWrapper\.create|null)\b""").containsMatchIn(executable)
}

internal fun StructuralRefactorPass.lazyOptionalAssignmentInitializer(source: String, fieldName: String): String? {
    val executable = maskJavaCommentsAndLiterals(source)
    Regex("""(?:this\.)?${Regex.escape(fieldName)}\s*=\s*(?:[\w.]+\.)?LazyOptional\.of\s*\(\s*\(\s*\)\s*->\s*((?:this\.)?[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\)""")
        .find(executable)
        ?.let { return "() -> ${it.groupValues[1]}" }
    Regex("""(?:this\.)?${Regex.escape(fieldName)}\s*=\s*(?:[\w.]+\.)?LazyOptional\.of\s*\(\s*(this::[A-Za-z_$][\w$]*)\s*\)""")
        .find(executable)
        ?.let { return it.groupValues[1] }
    val assignment = Regex("""(?:this\.)?${Regex.escape(fieldName)}\s*=\s*(?:[\w.]+\.)?LazyOptional\.of\s*\(""")
        .find(executable)
        ?: return null
    val openParen = executable.indexOf('(', assignment.range.first)
    if (openParen < 0) return null
    val closeParen = findMatchingParen(executable, openParen)
    if (closeParen <= openParen) return null
    return source.substring(openParen + 1, closeParen).trim()
}

internal fun StructuralRefactorPass.lazyOptionalSupplierExpression(initializer: String): String? =
    Regex("""^\(\s*\)\s*->\s*(.*?)$""", RegexOption.DOT_MATCHES_ALL)
        .matchEntire(initializer.trim())
        ?.groupValues
        ?.get(1)
        ?.trim()

internal fun StructuralRefactorPass.legacyLazyOptionalInitializerToProvider(initializer: String): String? {
    val trimmed = initializer.trim()
    Regex("""^this::([A-Za-z_$][\w$]*)$""")
        .find(trimmed)
        ?.let { return "blockEntity.${it.groupValues[1]}()" }
    Regex("""^\(\s*\)\s*->\s*(.*?)$""", RegexOption.DOT_MATCHES_ALL)
        .find(trimmed)
        ?.let { match ->
            return legacyInstanceProviderExpression(match.groupValues[1].trim())
        }
    return null
}

internal fun StructuralRefactorPass.legacyInstanceProviderExpression(expression: String): String {
    val migrated = expression.replace("this.", "blockEntity.")
    if (migrated.contains("blockEntity.")) return migrated
    if (Regex("""^[A-Za-z_$][\w$]*\s*\([^;{}]*\)$""").matches(migrated)) {
        return "blockEntity.$migrated"
    }
    if (Regex("""^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$""").matches(migrated) &&
        !migrated.substringBefore('.').first().isUpperCase()) {
        return "blockEntity.$migrated"
    }
    return migrated
}

internal fun StructuralRefactorPass.sidedInvWrapperProviderForIndex(fieldName: String, index: Int, source: String): String? {
    val initializer = Regex(
        """${Regex.escape(fieldName)}\s*=\s*SidedInvWrapper\.create\(\s*this\s*,\s*([^;]+?)\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    ).find(source)?.groupValues?.get(1) ?: return null
    val sides = splitTopLevelJavaArgs(initializer).map { it.trim() }
    val side = sides.getOrNull(index) ?: return null
    return "new SidedInvWrapper(blockEntity, $side)"
}

internal fun StructuralRefactorPass.renderBlockEntityCapabilityRegistrationMethod(
    registryRefs: List<String>,
    providers: List<BlockEntityCapabilityProviderSpec>
): String {
    val registrations = registryRefs.flatMap { registryRef ->
        providers.map { provider -> registryRef to provider }
    }.joinToString("\n") { (registryRef, provider) ->
        """
        event.registerBlockEntity(
                ${provider.capabilityExpression},
                $registryRef,
                (blockEntity, side) -> ${provider.providerExpression});
""".trimEnd()
    }
    return """

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
$registrations
    }
""".trimEnd()
}

internal fun StructuralRefactorPass.insertBeforeExactClassClosingBrace(
    source: String,
    className: String,
    insertion: String
): String {
    val unit = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    ).parse(source).result.orElseThrow {
        IllegalStateException("Cannot parse block entity source while inserting registerCapabilities")
    }
    val owners = unit.findAll(ClassOrInterfaceDeclaration::class.java)
        .filter { declaration -> declaration.nameAsString == className && !declaration.isInterface }
    if (owners.size != 1) {
        throw IllegalStateException(
            "Expected one block entity owner $className while inserting registerCapabilities, found ${owners.size}"
        )
    }
    val end = owners.single().range.orElseThrow {
        IllegalStateException("Block entity owner $className has no source range")
    }.end
    val lineStarts = mutableListOf(0)
    source.forEachIndexed { index, character ->
        if (character == '\n') lineStarts += index + 1
    }
    val closeBrace = lineStarts.getOrNull(end.line - 1)
        ?.plus(end.column - 1)
        ?: throw IllegalStateException("Block entity owner $className has an invalid closing position $end")
    if (source.getOrNull(closeBrace) != '}') {
        throw IllegalStateException("Block entity owner $className does not end at a closing brace")
    }
    return source.substring(0, closeBrace) + insertion + source.substring(closeBrace)
}

internal fun StructuralRefactorPass.migrateLegacyCapabilityReviveCapsSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val method = javaMethodRanges(executableCode).singleOrNull { method ->
        method.name == "reviveCaps" &&
            Regex("""\bvoid\s+reviveCaps\s*\(\s*\)""").containsMatchIn(method.header)
    } ?: return source
    if (executableCode.contains("void clearRemoved(")) return source
    val methodText = source.substring(method.range)
    val migratedMethod = Regex("""\breviveCaps\s*\(""")
        .replace(methodText, "clearRemoved(")
        .let { Regex("""super\.reviveCaps\(\)\s*;""").replace(it, "super.clearRemoved();") }
    return source.replaceRange(method.range, migratedMethod)
}

internal fun StructuralRefactorPass.cleanupLegacyCapabilityOverrideImports(source: String): String {
    var result = source
    if (!Regex("""\bCapability\s*<""").containsMatchIn(result)) {
        result = removeImport(result, "net.neoforged.neoforge.capabilities.Capability")
        result = removeImport(result, "com.modporter.compat.Capability")
        result = Regex("""(?m)^[ \t]*import\s+[\w.]+\.compat\.Capability;\s*\r?\n""").replace(result, "")
    }
    val withoutImports = Regex("""(?m)^[ \t]*import\s+[^\r\n]+;\s*\r?\n""").replace(result, "")
    if (!withoutImports.contains("LazyOptional")) {
        result = removeImport(result, "com.modporter.compat.LazyOptional")
        result = Regex("""(?m)^[ \t]*import\s+[\w.]+\.compat\.LazyOptional;\s*\r?\n""").replace(result, "")
    }
    if (!result.contains("@Nullable")) {
        result = removeImport(result, "org.jetbrains.annotations.Nullable")
        result = removeImport(result, "javax.annotation.Nullable")
    }
    if (!result.contains("@Nonnull")) {
        result = removeImport(result, "javax.annotation.Nonnull")
    }
    if (!result.contains("@NotNull")) {
        result = removeImport(result, "org.jetbrains.annotations.NotNull")
    }
    return result
}

internal fun StructuralRefactorPass.removeLegacyCapabilityCacheState(
    source: String,
    fields: Set<String>,
    ownerClassName: String? = null
): String {
    if (fields.isEmpty()) return source
    var result = source
    result = removeLegacyCapabilityCacheOnlyMethods(result, fields)
    result = removeLegacyCapabilityCacheBlocks(result, fields)
    result = removeLegacyCapabilityCacheLines(result, fields)
    for (field in fields) {
        result = removeLazyOptionalFieldDeclaration(result, field, ownerClassName)
    }
    return result
}

internal fun StructuralRefactorPass.removeLegacyCapabilityCacheLines(source: String, fields: Set<String>): String {
    var result = source
    for (field in fields) {
        val fieldRef = Regex.escape(field)
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^[ \t]*(?:this\.)?$fieldRef(?:\s*\[[^]\r\n]+])?\.invalidate\s*\(\s*\)\s*;\s*\r?\n?""")
        ) { "" }
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^[ \t]*(?:this\.)?$fieldRef(?:\s*\[[^]\r\n]+])?\s*=\s*(?:LazyOptional\.of|SidedInvWrapper\.create)\([^;]*;\s*\r?\n?""")
        ) { "" }
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^[ \t]*(?:this\.)?$fieldRef(?:\s*\[[^]\r\n]+])?\s*=\s*null\s*;\s*\r?\n?""")
        ) { "" }
    }
    return result
}

internal fun StructuralRefactorPass.removeLegacyCapabilityCacheOnlyMethods(source: String, fields: Set<String>): String {
    var result = source
    val methodNames = listOf("invalidateCaps", "invalidateCapabilities", "reviveCaps", "clearRemoved")
    var changed: Boolean
    do {
        changed = false
        val executableCode = maskJavaCommentsAndLiterals(result)
        for (methodName in methodNames) {
            val method = javaMethodRanges(executableCode).firstOrNull { it.name == methodName } ?: continue
            val methodText = result.substring(method.range)
            val executableMethodText = executableCode.substring(method.range)
            if (fields.none { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(executableMethodText) }) continue
            if (!isLegacyCapabilityCacheOnlyMethod(methodText, fields)) continue
            result = result.removeRange(method.range)
            changed = true
            break
        }
    } while (changed)
    return result
}

internal fun StructuralRefactorPass.isLegacyCapabilityCacheOnlyMethod(methodText: String, fields: Set<String>): Boolean {
    var body = javaMethodBodyText(methodText) ?: return false
    body = removeLegacyCapabilityCacheBlocks(body, fields)
    body = Regex("""(?m)^[ \t]*super\.(?:invalidateCaps|invalidateCapabilities|reviveCaps|clearRemoved)\s*\(\s*\)\s*;\s*\r?$""")
        .replace(body, "")
    for (field in fields) {
        val fieldRef = Regex.escape(field)
        body = Regex("""(?m)^[ \t]*(?:this\.)?$fieldRef\s*=\s*(?:LazyOptional\.of|SidedInvWrapper\.create)\([^;]*;\s*\r?$""")
            .replace(body, "")
        body = Regex("""(?m)^[ \t]*(?:this\.)?$fieldRef\s*=\s*null\s*;\s*\r?$""")
            .replace(body, "")
    }
    body = Regex("""(?m)^[ \t]*LazyOptional<[^;]+>\s+[A-Za-z_$][\w$]*\s*:\s*[^;]+;\s*\r?$""").replace(body, "")
    val executableBody = maskJavaCommentsAndLiterals(body)
    return executableBody.isBlank()
}

internal fun StructuralRefactorPass.removeLegacyCapabilityCacheBlocks(source: String, fields: Set<String>): String {
    var result = source
    var executableCode = maskJavaCommentsAndLiterals(result)
    var searchStart = 0
    while (searchStart < result.length) {
        val match = Regex("""\b(?:if|for)\s*\(""").find(executableCode, searchStart) ?: break
        val openParen = executableCode.indexOf('(', match.range.first)
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            searchStart = match.range.last + 1
            continue
        }
        val openBrace = skipWhitespace(executableCode, closeParen + 1)
        if (openBrace >= executableCode.length || executableCode[openBrace] != '{') {
            searchStart = closeParen + 1
            continue
        }
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) {
            searchStart = openBrace + 1
            continue
        }
        val executableBlockText = executableCode.substring(match.range.first, closeBrace + 1)
        if (!isLegacyCapabilityCacheBlock(executableBlockText, fields)) {
            searchStart = closeBrace + 1
            continue
        }
        val lineStart = result.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        var end = closeBrace + 1
        if (end < result.length && result[end] == '\r') end++
        if (end < result.length && result[end] == '\n') end++
        result = result.removeRange(lineStart, end)
        executableCode = maskJavaCommentsAndLiterals(result)
        searchStart = lineStart
    }
    return result
}

internal fun StructuralRefactorPass.isLegacyCapabilityCacheBlock(executableBlockText: String, fields: Set<String>): Boolean =
    fields.any { field ->
        val fieldRef = Regex.escape(field)
        val indexedFieldRef = """(?:this\.)?$fieldRef(?:\s*\[[^]]+])?"""
        Regex("""\b$indexedFieldRef\s*=\s*(?:LazyOptional\.of|SidedInvWrapper\.create)\s*\(""")
            .containsMatchIn(executableBlockText) ||
            Regex("""\b$indexedFieldRef\s*=\s*null\s*;""").containsMatchIn(executableBlockText) ||
            Regex("""\b$indexedFieldRef\s*\.invalidate\s*\(""").containsMatchIn(executableBlockText) ||
            (
                executableBlockText.contains("LazyOptional") &&
                    Regex("""\b$fieldRef\b""").containsMatchIn(executableBlockText) &&
                    Regex("""\b[A-Za-z_$][\w$]*\.invalidate\s*\(""").containsMatchIn(executableBlockText)
                )
    }

internal fun StructuralRefactorPass.removeLazyOptionalFieldDeclaration(
    source: String,
    fieldName: String,
    ownerClassName: String? = null
): String {
    val lines = source.splitToSequence("\n").toMutableList()
    val executableLines = maskJavaCommentsAndLiterals(source).splitToSequence("\n").toMutableList()
    var index = 0
    while (index < lines.size) {
        val executableLine = executableLines[index]
        if (!executableLine.contains("LazyOptional") ||
            !Regex("""\b${Regex.escape(fieldName)}\b""").containsMatchIn(executableLine) ||
            !executableLine.trimEnd().endsWith(";")) {
            index++
            continue
        }
        var start = index
        while (start > 0 && Regex("""^[ \t]*@[A-Za-z0-9_.]+(?:\([^)]*\))?[ \t]*\r?$""").matches(executableLines[start - 1])) {
            start--
        }
        if (ownerClassName != null) {
            val currentSource = lines.joinToString("\n")
            val ownerBody = javaClassBodyRange(currentSource, ownerClassName)
            val declarationStart = lineStartOffset(lines, start)
            if (ownerBody == null || !isDirectClassBodyMember(currentSource, ownerBody, declarationStart)) {
                index++
                continue
            }
        }
        repeat(index - start + 1) {
            lines.removeAt(start)
            executableLines.removeAt(start)
        }
        index = start
    }
    return lines.joinToString("\n")
}

internal fun StructuralRefactorPass.lineStartOffset(lines: List<String>, lineIndex: Int): Int {
    var offset = 0
    for (i in 0 until lineIndex.coerceAtMost(lines.size)) {
        offset += lines[i].length + 1
    }
    return offset
}

internal data class LegacyCapabilityFacadeSpec(
    val file: Path,
    val packageName: String,
    val className: String,
    val classIsPublic: Boolean,
    val dataType: String,
    val fieldName: String,
    val attachmentClassName: String,
    val attachmentPath: String
)

internal data class LegacyCapabilityFacadeLookupSpec(
    val file: Path,
    val packageName: String,
    val className: String,
    val dataType: String,
    val fieldName: String
) {
    val qualifiedClassName: String
        get() = if (packageName.isBlank()) className else "$packageName.$className"
}

internal fun StructuralRefactorPass.migrateLegacyCapabilityFacadeLookups(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val lookupSpecs = collectLegacyCapabilityFacadeLookupSpecs(javaFiles)
    if (lookupSpecs.isEmpty()) return changes

    for (javaFile in javaFiles) {
        val original = javaFile.readText()
        val sourcePackage = packageNameOf(original)
        val applicableSpecs = lookupSpecs.filter { it.file != javaFile }
        if (applicableSpecs.isEmpty()) continue
        val migrated = migrateLegacyCapabilityFacadeLookupsSource(original, sourcePackage, applicableSpecs)
        if (migrated == original) continue
        if (!dryRun) {
            javaFile.writeText(migrated)
        }
        changes.add(Change(
            file = javaFile,
            line = lineNumberAt(original, Regex("""\bgetCapability\s*\(""").find(original)?.range?.first ?: 0),
            description = "Rewrite legacy capability lookup through its source-declared facade getter",
            before = "LazyOptional.ofNullable(player.getCapability(Facade.FIELD)).ifPresent(...)",
            after = "Facade.get(player).ifPresent(...)",
            confidence = Confidence.HIGH,
            ruleId = "struct-legacy-capability-facade-lookup"
        ))
    }

    return changes
}

internal fun StructuralRefactorPass.collectLegacyCapabilityFacadeLookupSpecs(javaFiles: List<Path>): List<LegacyCapabilityFacadeLookupSpec> {
    val specs = mutableListOf<LegacyCapabilityFacadeLookupSpec>()
    for (javaFile in javaFiles) {
        legacyCapabilityFacadeSpec(javaFile, mutableListOf())?.let { spec ->
            specs.add(
                LegacyCapabilityFacadeLookupSpec(
                    file = spec.file,
                    packageName = spec.packageName,
                    className = spec.className,
                    dataType = spec.dataType,
                    fieldName = spec.fieldName
                )
            )
        }
        specs.addAll(legacyCapabilityFacadeLookupSpecs(javaFile))
    }
    return specs.distinctBy { "${it.qualifiedClassName}.${it.fieldName}" }
}

internal fun StructuralRefactorPass.legacyCapabilityFacadeLookupSpecs(file: Path): List<LegacyCapabilityFacadeLookupSpec> {
    val source = file.readText()
    if (!source.contains("LazyOptional") || !source.contains("Player") || !source.contains(" get(")) {
        return emptyList()
    }
    val className = classNameOfJavaSource(source) ?: return emptyList()
    val packageName = packageNameOf(source)
    val typeRef = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""
    val getterDataTypes = Regex(
        """(?:public|protected|private)?\s*static\s+(?:$typeRef\.)?LazyOptional\s*<\s*($typeRef)\s*>\s+get\s*\(\s*(?:$typeRef\.)?Player\s+[A-Za-z_$][\w$]*\s*\)"""
    )
        .findAll(source)
        .map { it.groupValues[1].substringAfterLast('.') }
        .toSet()
    if (getterDataTypes.isEmpty()) return emptyList()

    return getterDataTypes.flatMap { dataType ->
        val typedFields = Regex(
            """(?m)\b(?:public|protected|private)?\s*static\s+(?:final\s+)?(?:$typeRef\.)?Capability\s*<\s*(?:$typeRef\.)?${Regex.escape(dataType)}\s*>\s+([A-Z_$][A-Z0-9_$]*)\b"""
        )
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        val objectFields = Regex(
            """(?m)\b(?:public|protected|private)?\s*static\s+final\s+Object\s+([A-Z_$][A-Z0-9_$]*)\b"""
        )
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        val fields = when {
            typedFields.isNotEmpty() -> typedFields
            objectFields.size == 1 -> objectFields
            else -> emptySet()
        }
        fields.map { fieldName ->
            LegacyCapabilityFacadeLookupSpec(
                file = file,
                packageName = packageName,
                className = className,
                dataType = dataType,
                fieldName = fieldName
            )
        }
    }
}

internal fun StructuralRefactorPass.migrateLegacyCapabilityFacadeLookupsSource(
    source: String,
    sourcePackage: String,
    specs: List<LegacyCapabilityFacadeLookupSpec>
): String {
    var result = source
    for (spec in specs) {
        val referencePattern = legacyCapabilityFacadeReferencePattern(source, sourcePackage, spec) ?: continue
        result = migrateLegacyCapabilityFacadeLookupSource(result, referencePattern, spec.fieldName)
    }
    return if (result != source) {
        removeUnusedImportsBySimpleNamePattern(result, Regex("""LazyOptional"""))
    } else {
        source
    }
}

internal fun StructuralRefactorPass.legacyCapabilityFacadeReferencePattern(
    source: String,
    sourcePackage: String,
    spec: LegacyCapabilityFacadeLookupSpec
): String? {
    val qualified = Regex.escape(spec.qualifiedClassName)
    val simple = Regex.escape(spec.className)
    val canUseSimpleName = sourcePackage == spec.packageName ||
        Regex("""(?m)^[ \t]*import\s+${Regex.escape(spec.qualifiedClassName)};\s*$""").containsMatchIn(source) ||
        (spec.packageName.isNotBlank() &&
            Regex("""(?m)^[ \t]*import\s+${Regex.escape(spec.packageName)}\.\*;\s*$""").containsMatchIn(source))
    val usesQualifiedName = Regex("""(?<![.\w$])$qualified\s*\.""").containsMatchIn(source)
    return when {
        canUseSimpleName && usesQualifiedName -> """(?:$qualified|$simple)"""
        canUseSimpleName -> simple
        usesQualifiedName -> qualified
        else -> null
    }
}

internal fun StructuralRefactorPass.migrateLegacyCapabilityFacadeLookupSource(
    source: String,
    facadeReferencePattern: String,
    fieldName: String
): String {
    val identifier = """[A-Za-z_$][\w$]*"""
    val receiver = """$identifier(?:\s*\.\s*$identifier(?:\s*\([^()]*\))?)*"""
    val lookup = """($receiver)\s*\.\s*getCapability\s*\(\s*($facadeReferencePattern)\s*\.\s*${Regex.escape(fieldName)}\s*(?:,\s*null\s*)?\)"""
    val lazyOptional = """(?:$identifier(?:\s*\.\s*$identifier)*\s*\.\s*)?LazyOptional\s*\.\s*ofNullable"""
    var result = source
    result = Regex(
        """$lazyOptional\s*\(\s*$lookup\s*\)\s*\.\s*ifPresent\s*\(\s*($identifier)\s*->\s*\{(.*?)\}\s*\)\s*;""",
        RegexOption.DOT_MATCHES_ALL
    ).replace(result) { match ->
        val receiverExpression = match.groupValues[1].trim()
        val facadeExpression = match.groupValues[2].trim()
        val parameterName = match.groupValues[3].trim()
        val body = match.groupValues[4]
        "$facadeExpression.get($receiverExpression).ifPresent($parameterName -> {$body});"
    }
    result = Regex("""$lazyOptional\s*\(\s*$lookup\s*\)\s*\.\s*ifPresent\s*\(\s*([^;{}]+?)\s*\)\s*;""")
        .replace(result) { match ->
            val receiverExpression = match.groupValues[1].trim()
            val facadeExpression = match.groupValues[2].trim()
            val consumer = match.groupValues[3].trim()
            "$facadeExpression.get($receiverExpression).ifPresent($consumer);"
        }
    result = Regex(
        """$lookup\s*\.\s*ifPresent\s*\(\s*($identifier)\s*->\s*\{(.*?)\}\s*\)\s*;""",
        RegexOption.DOT_MATCHES_ALL
    ).replace(result) { match ->
        val receiverExpression = match.groupValues[1].trim()
        val facadeExpression = match.groupValues[2].trim()
        val parameterName = match.groupValues[3].trim()
        val body = match.groupValues[4]
        "$facadeExpression.get($receiverExpression).ifPresent($parameterName -> {$body});"
    }
    result = Regex("""$lookup\s*\.\s*ifPresent\s*\(\s*([^;{}]+?)\s*\)\s*;""")
        .replace(result) { match ->
            val receiverExpression = match.groupValues[1].trim()
            val facadeExpression = match.groupValues[2].trim()
            val consumer = match.groupValues[3].trim()
            "$facadeExpression.get($receiverExpression).ifPresent($consumer);"
        }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCapabilityFacadeToAttachment(
    projectDir: Path,
    dryRun: Boolean,
    errors: MutableList<String>
): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()

    val mainClass = detectModMainClass(projectDir)
    val originalMainText = mainClass?.readText().orEmpty()
    var migratedMainText = originalMainText

    val facadeSpecs = javaFiles.mapNotNull { legacyCapabilityFacadeSpec(it, errors) }
    if (facadeSpecs.isEmpty()) return changes
    val dataFilesByType = javaFiles
        .mapNotNull { file ->
            val source = file.readText()
            classNameOfJavaSource(source)?.let { it to file }
        }
        .toMap()

    for (spec in facadeSpecs) {
        val capabilityText = spec.file.readText()
        val attachmentFile = spec.file.parent.resolve("${spec.attachmentClassName}.java")
        val modRef = modIdReferenceForGeneratedClass(mainClass, migratedMainText, spec.packageName)

        val bridgeSource = legacyCapabilityFacadeBridgeSource(spec)
        if (capabilityText != bridgeSource) {
            if (!dryRun) {
                spec.file.writeText(bridgeSource)
            }
            changes.add(Change(
                file = spec.file,
                line = 1,
                description = "Migrate legacy player capability facade to a NeoForge attachment bridge",
                before = "CapabilityManager + AttachCapabilitiesEvent + ICapabilityProvider",
                after = "player.getData(${spec.attachmentClassName}.${spec.fieldName}) bridge",
                confidence = Confidence.HIGH,
                ruleId = "struct-legacy-capability-attachment-bridge"
            ))
        }

        val dataFile = dataFilesByType[spec.dataType]
        if (dataFile != null) {
            val originalData = dataFile.readText()
            val migratedData = migrateAttachmentDataNbtSerializable(originalData, spec.dataType)
            if (migratedData != originalData) {
                if (!dryRun) {
                    dataFile.writeText(migratedData)
                }
                changes.add(Change(
                    file = dataFile,
                    line = 1,
                    description = "Make legacy capability data compatible with AttachmentType.serializable",
                    before = "serializeNBT()/deserializeNBT(CompoundTag)",
                    after = "INBTSerializable<CompoundTag> with HolderLookup.Provider",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-legacy-capability-data-attachment-serializable"
                ))
            }
        }

        if (!attachmentFile.exists()) {
            if (!dryRun) {
                attachmentFile.writeText(legacyCapabilityAttachmentSource(spec, modRef))
            }
            changes.add(Change(
                file = attachmentFile,
                line = 1,
                description = "Generate NeoForge attachment registration for legacy capability data",
                before = "(missing)",
                after = "${spec.attachmentClassName}.register(IEventBus)",
                confidence = Confidence.HIGH,
                ruleId = "struct-legacy-capability-attachment-register"
            ))
        }

        if (mainClass != null && migratedMainText.isNotBlank() && !migratedMainText.contains("${spec.attachmentClassName}.register(")) {
            val mainPackage = packageNameOf(migratedMainText)
            val constructorBusName = requireModEventBusName(migratedMainText, "legacy capability attachment registration")

            if (spec.packageName != mainPackage) {
                migratedMainText = addImportIfMissing(migratedMainText, "${spec.packageName}.${spec.attachmentClassName}")
            }
            val registration = "        ${spec.attachmentClassName}.register($constructorBusName);"
            val updatedMain = insertModBusListener(migratedMainText, constructorBusName, registration, "EntityRegister")
            if (updatedMain != migratedMainText) {
                migratedMainText = updatedMain
                changes.add(Change(
                    file = mainClass,
                    line = 1,
                    description = "Register legacy capability attachment on the mod event bus",
                    before = "(no ${spec.attachmentClassName}.register call)",
                    after = "${spec.attachmentClassName}.register($constructorBusName)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-legacy-capability-attachment-main-register"
                ))
            }
        }
    }

    if (mainClass != null && migratedMainText != originalMainText && !dryRun) {
        mainClass.writeText(migratedMainText)
    }

    return changes
}

internal fun StructuralRefactorPass.legacyCapabilityFacadeSpec(file: Path, errors: MutableList<String>): LegacyCapabilityFacadeSpec? {
    val source = file.readText()
    if (!source.contains("CapabilityManager.get") ||
        !source.contains("CapabilityToken") ||
        !(source.contains("ICapabilityProvider") || source.contains("AttachCapabilitiesEvent"))) {
        return null
    }
    val className = classNameOfJavaSource(source) ?: return null
    val classIsPublic = javaTopLevelTypeIsPublic(source, className)
    val getterDataType = Regex(
        """(?:public\s+)?static\s+(?:[A-Za-z_$][\w$]*\.)?LazyOptional\s*<\s*([A-Za-z_$][\w$]*)\s*>\s+get\s*\(\s*(?:[A-Za-z_$][\w$]*\.)*Player\s+[A-Za-z_$][\w$]*\s*\)"""
    ).find(source)?.groupValues?.get(1)
    val declaration = Regex(
        """(?:public|protected|private)?\s*static\s+final\s+(?:Object|(?:[A-Za-z_$][\w$]*\.)?Capability\s*<\s*([A-Za-z_$][\w$]*)\s*>)\s+([A-Z_$][A-Z0-9_$]*)\s*=\s*CapabilityManager\.get\s*\(\s*new\s+CapabilityToken\s*<\s*([A-Za-z_$][\w$]*)?\s*>\s*\(\s*\)\s*\{\s*}\s*\)"""
    ).find(source) ?: return null
    val dataType = declaration.groupValues[3]
        .ifBlank { declaration.groupValues[1] }
        .ifBlank { getterDataType.orEmpty() }
        .ifBlank { return null }
    if (getterDataType != null && getterDataType != dataType) return null
    val fieldName = declaration.groupValues[2]
    val attachmentClassName = if (className.endsWith("Capability")) {
        className.removeSuffix("Capability") + "Attachment"
    } else {
        className + "Attachment"
    }
    val attachmentPath = legacyCapabilityAttachmentPath(fieldName, dataType)
    if (attachmentPath == null) {
        errors.add(
            "Cannot derive legacy capability attachment id in ${file.fileName}: " +
                "field '$fieldName' and data type '$dataType' do not produce a valid resource path"
        )
        return null
    }
    return LegacyCapabilityFacadeSpec(
        file = file,
        packageName = packageNameOf(source),
        className = className,
        classIsPublic = classIsPublic,
        dataType = dataType,
        fieldName = fieldName,
        attachmentClassName = attachmentClassName,
        attachmentPath = attachmentPath
    )
}

internal fun StructuralRefactorPass.legacyCapabilityAttachmentPath(fieldName: String, dataType: String): String? {
    val fieldPath = resourcePathSegment(fieldName)
        .removeSuffix("_capability")
    val dataPath = resourcePathSegment(classNameToPath(dataType))
    return fieldPath
        .ifBlank { dataPath }
        .takeIf { it.isNotBlank() }
}

internal fun StructuralRefactorPass.resourcePathSegment(value: String): String =
    value
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "_")
        .trim('_')

internal fun StructuralRefactorPass.legacyCapabilityFacadeBridgeSource(spec: LegacyCapabilityFacadeSpec): String {
    val visibility = if (spec.classIsPublic) "public " else ""
    return """package ${spec.packageName};

import com.modporter.compat.LazyOptional;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

${visibility}final class ${spec.className} {
    private ${spec.className}() {
    }

    public static LazyOptional<${spec.dataType}> get(Player player) {
        return LazyOptional.of(() -> player.getData(${spec.attachmentClassName}.${spec.fieldName}));
    }

    @Nullable
    public static ${spec.dataType} getOrNull(Player player) {
        return player.getData(${spec.attachmentClassName}.${spec.fieldName});
    }
}
"""
}

internal fun StructuralRefactorPass.legacyCapabilityAttachmentSource(spec: LegacyCapabilityFacadeSpec, modRef: String): String = """package ${spec.packageName};

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class ${spec.attachmentClassName} {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, $modRef);

    public static final Supplier<AttachmentType<${spec.dataType}>> ${spec.fieldName} =
            ATTACHMENT_TYPES.register("${spec.attachmentPath}", () -> AttachmentType.serializable(${spec.dataType}::new).copyOnDeath().build());

    private ${spec.attachmentClassName}() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
"""

internal fun StructuralRefactorPass.migrateAttachmentDataNbtSerializable(source: String, dataType: String): String {
    var result = source
    if (!result.contains("implements INBTSerializable<CompoundTag>")) {
        result = ensureJavaTypeImplements(result, dataType, "INBTSerializable<CompoundTag>")
        result = addImportIfMissing(result, "net.neoforged.neoforge.common.util.INBTSerializable")
    }
    result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    result = result.replace(
        Regex("""public\s+CompoundTag\s+serializeNBT\s*\(\s*\)"""),
        "public CompoundTag serializeNBT(HolderLookup.Provider provider)"
    )
    result = result.replace(
        Regex("""public\s+void\s+deserializeNBT\s*\(\s*CompoundTag\s+(\w+)\s*\)""")
    ) { match ->
        "public void deserializeNBT(HolderLookup.Provider provider, CompoundTag ${match.groupValues[1]})"
    }
    if (result.contains("serializeNBT(HolderLookup.Provider provider)") &&
        !Regex("""@Override\s*\r?\n\s*public\s+CompoundTag\s+serializeNBT\(HolderLookup\.Provider provider\)""").containsMatchIn(result)
    ) {
        result = result.replace(
            "public CompoundTag serializeNBT(HolderLookup.Provider provider)",
            "@Override\n    public CompoundTag serializeNBT(HolderLookup.Provider provider)"
        )
    }
    if (result.contains("deserializeNBT(HolderLookup.Provider provider, CompoundTag") &&
        !Regex("""@Override\s*\r?\n\s*public\s+void\s+deserializeNBT\(HolderLookup\.Provider provider,""").containsMatchIn(result)
    ) {
        result = result.replace(
            Regex("""public\s+void\s+deserializeNBT\(HolderLookup\.Provider provider, CompoundTag\s+(\w+)\)"""),
            "@Override\n    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag $1)"
        )
    }
    return result
}

internal fun StructuralRefactorPass.ensureJavaTypeImplements(source: String, typeName: String, interfaceType: String): String {
    val typeStart = findTypeDeclarationStart(source, typeName) ?: return source
    val openBrace = source.indexOf('{', typeStart)
    if (openBrace < 0) return source
    val header = source.substring(typeStart, openBrace)
    if (Regex("""\b${Regex.escape(interfaceType.substringBefore('<'))}\b""").containsMatchIn(header)) {
        return source
    }
    val insertion = if (Regex("""\bimplements\b""").containsMatchIn(header)) {
        ", $interfaceType"
    } else {
        " implements $interfaceType"
    }
    return source.substring(0, openBrace).trimEnd() + insertion + " " + source.substring(openBrace)
}

internal fun StructuralRefactorPass.modIdReferenceForGeneratedClass(mainClass: Path?, mainText: String, generatedPackage: String): String =
    explicitModIdReferenceForGeneratedClass(mainClass, mainText, generatedPackage, metadataModId = null)
        ?: throw IllegalStateException("Cannot derive mod id expression for generated class in package $generatedPackage")

internal fun StructuralRefactorPass.modAnnotationArgumentExpression(source: String): String? {
    val code = maskJavaComments(source)
    val executableCode = maskJavaCommentsAndLiterals(source)
    val argument = Regex("""@Mod\s*\(\s*([^)]+?)\s*\)""")
        .find(code)
        ?.takeIf { match ->
            executableCode
                .substring(match.range.first, match.range.last + 1)
                .contains("@Mod")
        }
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: return null
    val value = Regex("""(?:value\s*=\s*)?(.+)""")
        .matchEntire(argument)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: return null
    if (value.isBlank() || value.any { it == ';' || it == '{' || it == '}' || it == '\n' || it == '\r' }) {
        return null
    }
    return value
}

internal fun StructuralRefactorPass.explicitModIdReferenceForGeneratedClass(
    mainClass: Path?,
    mainText: String,
    generatedPackage: String,
    metadataModId: String?
): String? {
    if (mainClass != null && mainText.isNotBlank()) {
        val mainPackage = packageNameOf(mainText)
        val code = maskJavaComments(mainText)
        val executableCode = maskJavaCommentsAndLiterals(mainText)
        val typeBlocks = javaTypeBlocks(mainText, executableCode)
        val references = linkedSetOf<String>()
        Regex("""@Mod\s*\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\)""")
            .findAll(code)
            .filter { match ->
                executableCode
                    .substring(match.range.first, match.range.last + 1)
                    .contains("@Mod")
            }
            .forEach { references += "\"${it.groupValues[1]}\"" }

        Regex("""@Mod\s*\(\s*(?:value\s*=\s*)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\)""")
            .findAll(code)
            .filter { match ->
                executableCode
                    .substring(match.range.first, match.range.last + 1)
                    .contains("@Mod")
            }
            .forEach { match ->
                val modArgument = match.groupValues[1]
                val constName = modArgument.substringAfterLast('.')
                val ownerExpression = modArgument.substringBeforeLast('.', missingDelimiterValue = "")
                val owner = if (modArgument.contains('.')) {
                    val ownerName = ownerExpression.substringAfterLast('.')
                    typeBlocks.singleOrNull { it.name == ownerName }
                } else {
                    javaTypeBlockForModAnnotation(match.range.last, typeBlocks)
                }
                if (owner != null) {
                    val constValue = javaStaticFinalStringConstant(code, executableCode, owner, constName, typeBlocks)
                    if (constValue != null) {
                        references += javaModIdReferenceExpression(mainPackage, generatedPackage, owner, constName)
                            ?: "\"$constValue\""
                    }
                } else if (ownerExpression.isNotBlank()) {
                    val ownerName = ownerExpression.substringAfterLast('.')
                    val importedOwner = singleTypeImportFqn(mainText, ownerName)
                    when {
                        importedOwner != null -> references += "$importedOwner.$constName"
                        ownerExpression.contains('.') -> references += "$ownerExpression.$constName"
                    }
                }
            }
        references.singleOrNull()?.let { return it }
    }
    return metadataModId
        ?.takeIf { it.isNotBlank() }
        ?.let { "\"$it\"" }
}

internal fun StructuralRefactorPass.javaModIdReferenceExpression(
    mainPackage: String,
    generatedPackage: String,
    owner: JavaTypeBlock,
    constName: String
): String? {
    val classRef = javaTypeReferenceExpression(mainPackage, generatedPackage, owner) ?: return null
    return "$classRef.$constName"
}

internal fun StructuralRefactorPass.javaTypeReferenceExpression(
    sourcePackage: String,
    targetPackage: String,
    owner: JavaTypeBlock
): String? = when {
    sourcePackage == targetPackage && owner.isAccessibleFromSamePackage -> owner.referenceName
    sourcePackage.isNotBlank() && owner.isAccessibleOutsidePackage ->
        "$sourcePackage.${owner.referenceName}"
    else -> null
}

internal fun StructuralRefactorPass.singleTypeImportFqn(source: String, simpleName: String): String? {
    val pattern = Regex("""(?m)^[ \t]*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(simpleName)})\s*;\s*$""")
    return pattern.findAll(maskJavaCommentsAndLiterals(source))
        .map { it.groupValues[1] }
        .toSet()
        .singleOrNull()
}

internal fun StructuralRefactorPass.javaListenerTypeReferenceExpression(
    sourcePackage: String,
    targetPackage: String,
    owner: JavaTypeBlock
): String? = when {
    sourcePackage.isNotBlank() && owner.isAccessibleOutsidePackage ->
        "$sourcePackage.${owner.referenceName}"
    sourcePackage == targetPackage && owner.isAccessibleFromSamePackage -> owner.referenceName
    else -> null
}

internal data class JavaTypeBlock(
    val name: String,
    val isPublic: Boolean,
    val isPrivate: Boolean,
    val start: Int,
    val declarationStart: Int,
    val bodyStart: Int,
    val end: Int,
    val referenceName: String = name,
    val isAccessibleFromSamePackage: Boolean = !isPrivate,
    val isAccessibleOutsidePackage: Boolean = isPublic
)

internal fun StructuralRefactorPass.detectModIdsFromText(text: String): Set<String> {
    val candidates = linkedSetOf<String>()
    val code = maskJavaComments(text)
    val executableCode = maskJavaCommentsAndLiterals(text)
    val typeBlocks = javaTypeBlocks(text, executableCode)
    Regex("""@Mod\s*\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\)""")
        .findAll(code)
        .filter { match ->
            executableCode
                .substring(match.range.first, match.range.last + 1)
                .contains("@Mod")
        }
        .forEach { candidates += it.groupValues[1] }
    Regex("""@Mod\s*\(\s*(?:value\s*=\s*)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\)""")
        .findAll(code)
        .filter { match ->
            executableCode
                .substring(match.range.first, match.range.last + 1)
                .contains("@Mod")
        }
        .forEach { match ->
            val modArgument = match.groupValues[1]
            val constName = modArgument.substringAfterLast('.')
            val owner = if (modArgument.contains('.')) {
                val ownerName = modArgument.substringBeforeLast('.').substringAfterLast('.')
                typeBlocks.singleOrNull { it.name == ownerName }
            } else {
                javaTypeBlockForModAnnotation(match.range.last, typeBlocks)
            }
            if (owner != null) {
                javaStaticFinalStringConstant(code, executableCode, owner, constName, typeBlocks)
                    ?.let { candidates += it }
            }
        }
    return candidates
}

internal fun StructuralRefactorPass.javaTypeBlocks(text: String, executableCode: String): List<JavaTypeBlock> {
    val identifier = """[A-Za-z_$][\w$]*"""
    val declaration = Regex(
        """(?m)(?:^|(?<=[;{}]))[ \t]*(?:(?:@(?:$identifier\.)*$identifier(?:\s*\([^;\r\n]*\))?|public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(class|interface|enum|record)\s+($identifier)\b"""
    )
    val blocks = declaration.findAll(executableCode).mapNotNull { match ->
        val declarationStart = match.groups[1]?.range?.first ?: match.range.first
        val name = match.groupValues[2]
        val openBrace = executableCode.indexOf('{', match.range.last + 1)
        if (openBrace < 0) return@mapNotNull null
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0 || closeBrace >= text.length) return@mapNotNull null
        JavaTypeBlock(
            name = name,
            isPublic = Regex("""\bpublic\b""").containsMatchIn(match.value),
            isPrivate = Regex("""\bprivate\b""").containsMatchIn(match.value),
            start = match.range.first,
            declarationStart = declarationStart,
            bodyStart = openBrace,
            end = closeBrace
        )
    }.toList()
    return blocks.map { block ->
        val enclosing = blocks
            .filter { candidate ->
                candidate !== block &&
                    block.declarationStart > candidate.bodyStart &&
                    block.end < candidate.end
            }
            .sortedBy { it.bodyStart }
        val chain = enclosing + block
        block.copy(
            referenceName = chain.joinToString(".") { it.name },
            isAccessibleFromSamePackage = chain.none { it.isPrivate },
            isAccessibleOutsidePackage = chain.all { it.isPublic }
        )
    }
}

internal fun StructuralRefactorPass.javaTypeBlockForModAnnotation(annotationEnd: Int, typeBlocks: List<JavaTypeBlock>): JavaTypeBlock? =
    typeBlocks
        .filter { it.declarationStart > annotationEnd }
        .minByOrNull { it.declarationStart }

internal fun StructuralRefactorPass.javaTypeBlockContainingOffset(offset: Int, typeBlocks: List<JavaTypeBlock>): JavaTypeBlock? =
    typeBlocks
        .filter { offset > it.bodyStart && offset < it.end }
        .minByOrNull { it.end - it.bodyStart }

internal fun StructuralRefactorPass.javaStaticFinalStringConstant(
    code: String,
    executableCode: String,
    owner: JavaTypeBlock,
    constName: String,
    typeBlocks: List<JavaTypeBlock>
): String? {
    val bodyStart = owner.bodyStart + 1
    val bodyEnd = owner.end
    if (bodyStart >= bodyEnd || bodyEnd > code.length) return null
    val constant = Regex("""(?m)\b((?:(?:public|private|protected|static|final)\s+)*)String\s+${Regex.escape(constName)}\s*=\s*"([^"]+)"""")
    return constant.findAll(code.substring(bodyStart, bodyEnd))
        .firstNotNullOfOrNull { match ->
            val globalStart = bodyStart + match.range.first
            val globalEnd = bodyStart + match.range.last
            val executableSegment = executableCode.substring(globalStart, globalEnd + 1)
            val modifiers = match.groupValues[1]
            val declaringType = typeBlocks
                .filter { globalStart > it.bodyStart && globalStart < it.end }
                .minByOrNull { it.end - it.bodyStart }
            if (
                declaringType == owner &&
                Regex("""\bstatic\b""").containsMatchIn(modifiers) &&
                Regex("""\bfinal\b""").containsMatchIn(modifiers) &&
                executableSegment.contains("String") &&
                executableSegment.contains("=")
            ) {
                match.groupValues[2]
            } else {
                null
            }
        }
}

internal data class AdvancementTriggerSpec(
    val triggerPackage: String,
    val triggerClassName: String,
    val namespace: String,
    val path: String
) {
    val fieldName: String
        get() = path.uppercase()
            .replace(Regex("""[^A-Z0-9]+"""), "_")
            .trim('_')
            .let { if (it.endsWith("_TRIGGER")) it else "${it}_TRIGGER" }
}

internal data class LegacyAdvancementTriggerClass(
    val file: Path,
    val packageName: String,
    val className: String,
    val classIsPublic: Boolean
)

internal fun StructuralRefactorPass.migrateAdvancementCriterionTriggers(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()

    changes.addAll(migrateLegacySimpleCriterionTriggers(projectDir, javaFiles, dryRun))

    val triggerIndex = com.modporter.core.transforms.util.JavaProjectIndex.build(javaFiles)
    val legacyTriggerClasses = javaFiles
        .mapNotNull { legacyAdvancementTriggerClass(it) }
        // a project subclass relies on the class's own API, which the template does not keep
        .filter { trigger -> triggerIndex.subtypesOf(trigger.className).isEmpty() }
        .filter { trigger ->
            val construction = Regex("""\bnew\s+(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(trigger.className)}\s*\(\s*"[^"]*"\s*,\s*"[^"]*"\s*\)""")
            javaFiles.any { file -> construction.containsMatchIn(runCatching { file.readText() }.getOrDefault("")) }
        }
        .associateBy { it.className }

    for (trigger in legacyTriggerClasses.values) {
        val triggerText = trigger.file.readText()
        val migrated = advancementTriggerCodecSource(
            packageName = trigger.packageName,
            className = trigger.className,
            classIsPublic = trigger.classIsPublic
        )
        if (triggerText != migrated) {
            if (!dryRun) {
                trigger.file.writeText(migrated)
            }
            changes.add(Change(
                file = trigger.file,
                line = 1,
                description = "Migrate custom advancement trigger to the 1.21 codec CriterionTrigger API",
                before = "getId/createInstance/serializeToJson legacy trigger",
                after = "codec() + CriterionTriggerInstance.validate",
                confidence = Confidence.HIGH,
                ruleId = "struct-advancement-trigger-codec"
            ))
        }
    }

    val mainClass = detectModMainClass(projectDir)
    val originalMainText = mainClass?.readText().orEmpty()
    var migratedMainText = originalMainText

    for (registrarFile in javaFiles) {
        val registrarText = registrarFile.readText()
        if (!registrarText.contains("CriteriaTriggers.register")) continue
        val specs = extractAdvancementTriggerSpecs(registrarText, legacyTriggerClasses)
        if (specs.isEmpty()) continue
        val registrarClassName = classNameOfJavaSource(registrarText) ?: continue
        val registrarPackage = packageNameOf(registrarText)
        val registrarClassIsPublic = javaTopLevelTypeIsPublic(registrarText, registrarClassName)
        val modRef = modIdReferenceForGeneratedClass(mainClass, migratedMainText, registrarPackage)
        val migratedRegistrar = extraEventsRegisterAdvancementSource(
            packageName = registrarPackage,
            className = registrarClassName,
            classIsPublic = registrarClassIsPublic,
            modRef = modRef,
            specs = specs
        )

        if (registrarText != migratedRegistrar) {
            if (!dryRun) {
                registrarFile.writeText(migratedRegistrar)
            }
            changes.add(Change(
                file = registrarFile,
                line = 1,
                description = "Register custom advancement triggers through DeferredRegister",
                before = "CriteriaTriggers.register(new legacy CriterionTrigger(...))",
                after = "DeferredRegister<CriterionTrigger<?>> TRIGGERS",
                confidence = Confidence.HIGH,
                ruleId = "struct-advancement-trigger-deferred-register"
            ))
        }

        if (mainClass != null && migratedMainText.isNotBlank() && !migratedMainText.contains("$registrarClassName.register(")) {
            val mainPackage = packageNameOf(migratedMainText)
            val constructorBusName = requireModEventBusName(migratedMainText, "legacy advancement trigger registration")
            if (registrarPackage != mainPackage) {
                migratedMainText = addImportIfMissing(migratedMainText, "$registrarPackage.$registrarClassName")
            }
            val registration = "        $registrarClassName.register($constructorBusName);"
            val updatedMain = insertModBusListener(migratedMainText, constructorBusName, registration, "EntityRegister")
            if (updatedMain != migratedMainText) {
                migratedMainText = updatedMain
                changes.add(Change(
                    file = mainClass,
                    line = 1,
                    description = "Register custom advancement trigger DeferredRegister on the mod event bus",
                    before = "(no $registrarClassName.register call)",
                    after = "$registrarClassName.register($constructorBusName)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-advancement-trigger-main-register"
                ))
            }
        }
    }

    if (mainClass != null && migratedMainText != originalMainText && !dryRun) {
        mainClass.writeText(migratedMainText)
    }

    return changes
}

internal fun StructuralRefactorPass.legacyAdvancementTriggerClass(file: Path): LegacyAdvancementTriggerClass? {
    val source = file.readText()
    if (!Regex("""\bimplements\s+(?:[A-Za-z_$][\w$]*\.)*CriterionTrigger\s*<""").containsMatchIn(source)) return null
    if (!listOf(
            "createInstance",
            "serializeToJson",
            "SerializationContext",
            "DeserializationContext",
            "getCriterion()",
            "getId()"
        ).any { source.contains(it) }) {
        return null
    }
    val className = classNameOfJavaSource(source) ?: return null
    // The generated replacement reproduces one shape only: a concrete, non-generic trigger
    // over its own nested Instance, built as new Trigger("namespace", "path").
    val code = maskJavaCommentsAndLiterals(source)
    val header = Regex("""\bclass\s+${Regex.escape(className)}\b([^{]*)\{""").find(code) ?: return null
    if (Regex("""\babstract\s+class\s+${Regex.escape(className)}\b""").containsMatchIn(code)) return null
    if (header.groupValues[1].trimStart().startsWith("<")) return null
    if (!Regex("""\bCriterionTrigger\s*<\s*(?:${Regex.escape(className)}\.)?Instance\s*>""").containsMatchIn(header.groupValues[1])) return null
    return LegacyAdvancementTriggerClass(
        file = file,
        packageName = packageNameOf(source),
        className = className,
        classIsPublic = javaTopLevelTypeIsPublic(source, className)
    )
}

internal fun StructuralRefactorPass.extractAdvancementTriggerSpecs(
    source: String,
    triggerClasses: Map<String, LegacyAdvancementTriggerClass>
): List<AdvancementTriggerSpec> {
    val pattern = Regex("""new\s+(?:[A-Za-z_$][\w$]*\.)*([A-Za-z_$][\w$]*)\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
    return pattern.findAll(source)
        .mapNotNull { match ->
            val trigger = triggerClasses[match.groupValues[1]] ?: return@mapNotNull null
            AdvancementTriggerSpec(
                triggerPackage = trigger.packageName,
                triggerClassName = trigger.className,
                namespace = match.groupValues[2],
                path = match.groupValues[3]
            )
        }
        .distinct()
        .toList()
}

internal data class LegacyCriterionRegistration(
    val registryFile: Path,
    val registryPackage: String,
    val registryClassName: String,
    val fieldName: String,
    val triggerClassName: String,
    val triggerPath: String
)

internal fun StructuralRefactorPass.migrateLegacySimpleCriterionTriggers(
    projectDir: Path,
    javaFiles: List<Path>,
    dryRun: Boolean
): List<Change> {
    val changes = mutableListOf<Change>()
    val registrationPattern = Regex(
        """(?m)^([ \t]*)public\s+static\s+final\s+[\w.$<>?]+\s+(\w+)\s*=\s*CriteriaTriggers\.register\s*\(\s*new\s+(\w+)\s*\(\s*\)\s*\)\s*;\s*$"""
    )
    val singletonRegistrationPattern = Regex(
        """(?m)^([ \t]*)CriteriaTriggers\.register\s*\(\s*(\w+)\.INSTANCE\s*\)\s*;\s*$"""
    )
    val triggerSources = javaFiles.associateBy { it.fileName.toString().removeSuffix(".java") }
    val registrations = javaFiles.flatMap { javaFile ->
        val text = javaFile.readText()
        if (!text.contains("CriteriaTriggers.register(")) return@flatMap emptyList()
        val registryPackage = packageNameOf(text)
        val registryClassName = Regex("""\bclass\s+(\w+)\b""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: return@flatMap emptyList()
        val fieldRegistrations = registrationPattern.findAll(text).map { match ->
            val triggerClassName = match.groupValues[3]
            LegacyCriterionRegistration(
                registryFile = javaFile,
                registryPackage = registryPackage,
                registryClassName = registryClassName,
                fieldName = match.groupValues[2],
                triggerClassName = triggerClassName,
                triggerPath = triggerSources[triggerClassName]
                    ?.readText()
                    ?.let(::extractCriterionTriggerPath)
                    ?: criterionFieldToPath(match.groupValues[2])
            )
        }
        val singletonRegistrations = singletonRegistrationPattern.findAll(text).map { match ->
            val triggerClassName = match.groupValues[2]
            val triggerPath = triggerSources[triggerClassName]
                ?.readText()
                ?.let(::extractCriterionTriggerPath)
                ?: criterionFieldToPath(triggerClassName.removeSuffix("Trigger"))
            LegacyCriterionRegistration(
                registryFile = javaFile,
                registryPackage = registryPackage,
                registryClassName = registryClassName,
                fieldName = criterionPathToField(triggerPath),
                triggerClassName = triggerClassName,
                triggerPath = triggerPath
            )
        }
        (fieldRegistrations + singletonRegistrations).toList().distinctBy { it.triggerClassName }
    }
    if (registrations.isEmpty()) return changes

    registrations.groupBy { it.registryFile }.forEach { (registryFile, fileRegistrations) ->
        val original = registryFile.readText()
        val migrated = migrateLegacyCriterionRegistrySource(projectDir, original, fileRegistrations)
        if (migrated != original) {
            if (!dryRun) registryFile.writeText(migrated)
            changes.add(Change(
                file = registryFile,
                line = 1,
                description = "Migrate CriteriaTriggers.register fields to DeferredRegister trigger types",
                before = "CriteriaTriggers.register(new Trigger())",
                after = "DeferredHolder<CriterionTrigger<?>, Trigger>",
                confidence = Confidence.HIGH,
                ruleId = "struct-criterion-trigger-deferred-register"
            ))
        }
    }

    for (registration in registrations) {
        val triggerFile = triggerSources[registration.triggerClassName] ?: continue
        val original = triggerFile.readText()
        if (!original.contains("SimpleCriterionTrigger") ||
            (!original.contains("createInstance") && !original.contains("SimpleCriterionTrigger.SimpleInstance") && !original.contains("AbstractCriterionTriggerInstance"))) {
            continue
        }
        val migrated = migrateLegacySimpleCriterionTriggerSource(original, registration) ?: continue
        if (migrated != original) {
            if (!dryRun) triggerFile.writeText(migrated)
            changes.add(Change(
                file = triggerFile,
                line = 1,
                description = "Migrate legacy SimpleCriterionTrigger to the 1.21 codec trigger API",
                before = "getId/createInstance/SimpleInstance class",
                after = "codec() + record SimpleInstance",
                confidence = Confidence.HIGH,
                ruleId = "struct-simple-criterion-trigger-codec"
            ))
        }
    }

    val registryGroups = registrations.groupBy { it.registryClassName }
    for (javaFile in javaFiles) {
        val original = javaFile.readText()
        var text = original
        for ((registryClassName, refs) in registryGroups) {
            val fieldAlternation = refs.joinToString("|") { Regex.escape(it.fieldName) }
            text = Regex("""\b${Regex.escape(registryClassName)}\.($fieldAlternation)\.trigger\s*\(""")
                .replace(text) { match -> "${registryClassName}.${match.groupValues[1]}.get().trigger(" }
            text = Regex("""(?m)^[ \t]*${Regex.escape(registryClassName)}\.init\s*\(\s*\)\s*;\s*\r?\n?""")
                .replace(text, "")
        }
        if (text != original) {
            if (!dryRun) javaFile.writeText(text)
            changes.add(Change(
                file = javaFile,
                line = 1,
                description = "Dereference DeferredHolder advancement triggers before firing",
                before = "AdvancementRegistry.FIELD.trigger(...)",
                after = "AdvancementRegistry.FIELD.get().trigger(...)",
                confidence = Confidence.HIGH,
                ruleId = "struct-criterion-trigger-holder-get"
            ))
        }
    }

    registerCriterionRegistriesOnModBus(projectDir, registrations, dryRun)?.let { changes.add(it) }

    return changes
}

internal fun StructuralRefactorPass.migrateLegacyCriterionRegistrySource(
    projectDir: Path,
    source: String,
    registrations: List<LegacyCriterionRegistration>
): String {
    var text = source
    val mainClass = detectModMainClass(projectDir)
    val mainText = mainClass?.readText().orEmpty()
    val modRef = modIdReferenceForGeneratedClass(mainClass, mainText, registrations.first().registryPackage)
    val registryClassName = registrations.first().registryClassName
    text = removeImportIfPresent(text, "net.minecraft.advancements.CriteriaTriggers")
    text = addImportIfMissing(text, "net.minecraft.advancements.CriterionTrigger")
    text = addImportIfMissing(text, "net.minecraft.core.registries.Registries")
    text = addImportIfMissing(text, "net.neoforged.neoforge.registries.DeferredHolder")
    text = addImportIfMissing(text, "net.neoforged.neoforge.registries.DeferredRegister")
    if (!text.contains("DeferredRegister<CriterionTrigger<?>> TRIGGERS")) {
        val classOpen = Regex("""public\s+class\s+${Regex.escape(registryClassName)}\s*\{\s*""").find(text)
        if (classOpen != null) {
            val insertPos = classOpen.range.last + 1
            text = text.substring(0, insertPos) +
                "\n    public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS = DeferredRegister.create(Registries.TRIGGER_TYPE, $modRef);\n" +
                text.substring(insertPos)
        }
    }
    for (registration in registrations) {
        val fieldPattern = Regex(
            """(?m)^([ \t]*)public\s+static\s+final\s+[\w.$<>?]+\s+${Regex.escape(registration.fieldName)}\s*=\s*CriteriaTriggers\.register\s*\(\s*new\s+${Regex.escape(registration.triggerClassName)}\s*\(\s*\)\s*\)\s*;\s*$"""
        )
        text = fieldPattern.replace(text) { match ->
            val indent = match.groupValues[1]
            "${indent}public static final DeferredHolder<CriterionTrigger<?>, ${registration.triggerClassName}> ${registration.fieldName} = TRIGGERS.register(\"${registration.triggerPath}\", ${registration.triggerClassName}::new);"
        }
        if (!Regex("""\b${Regex.escape(registration.fieldName)}\b\s*=""").containsMatchIn(text)) {
            val triggerRegister = Regex(
                """(?m)^([ \t]*)public\s+static\s+final\s+DeferredRegister<CriterionTrigger<\?>>\s+TRIGGERS\s*=\s*[^;\r\n]+;\s*$"""
            ).find(text)
            if (triggerRegister != null) {
                val indent = triggerRegister.groupValues[1]
                val insertPos = triggerRegister.range.last + 1
                val fieldLine = "\n${indent}public static final DeferredHolder<CriterionTrigger<?>, ${registration.triggerClassName}> ${registration.fieldName} = TRIGGERS.register(\"${registration.triggerPath}\", ${registration.triggerClassName}::new);"
                text = text.substring(0, insertPos) + fieldLine + text.substring(insertPos)
            }
        }
        text = Regex(
            """(?m)^[ \t]*CriteriaTriggers\.register\s*\(\s*${Regex.escape(registration.triggerClassName)}\.INSTANCE\s*\)\s*;\s*\r?\n?"""
        ).replace(text, "")
    }
    text = Regex("""(?s)\n[ \t]*public\s+static\s+void\s+init\s*\(\s*\)\s*\{\s*}\s*""")
        .replace(text, "\n")
    return text
}

internal fun StructuralRefactorPass.registerCriterionRegistriesOnModBus(
    projectDir: Path,
    registrations: List<LegacyCriterionRegistration>,
    dryRun: Boolean
): Change? {
    val mainClass = detectModMainClass(projectDir) ?: return null
    val original = mainClass.readText()
    var text = original
    val mainPackage = packageNameOf(text)
    val constructorBusName = requireModEventBusName(text, "advancement trigger registration")
    for ((registryFile, fileRegistrations) in registrations.groupBy { it.registryFile }) {
        val first = fileRegistrations.first()
        val classRef = if (first.registryPackage == mainPackage) {
            first.registryClassName
        } else {
            text = addImportIfMissing(text, "${first.registryPackage}.${first.registryClassName}")
            first.registryClassName
        }
        val registrationLine = "        $classRef.TRIGGERS.register($constructorBusName);"
        if (!text.contains("$classRef.TRIGGERS.register(")) {
            text = insertModBusListener(text, constructorBusName, registrationLine, first.registryClassName)
        }
        // Keep registryFile referenced so grouping is deterministic even if only one registry exists.
        registryFile.fileName
    }
    if (text == original) return null
    if (!dryRun) mainClass.writeText(text)
    return Change(
        file = mainClass,
        line = 1,
        description = "Register advancement trigger DeferredRegister on the mod event bus",
        before = "(CriteriaTriggers static registration)",
        after = "AdvancementRegistry.TRIGGERS.register(modEventBus)",
        confidence = Confidence.HIGH,
        ruleId = "struct-criterion-trigger-main-register"
    )
}

internal data class LegacyConditionCodecRegistration(
    val registerFile: Path,
    val conditionFile: Path,
    val conditionPackage: String,
    val conditionClassName: String,
    val registryIdExpression: String,
    val fieldName: String
)

internal fun StructuralRefactorPass.migrateLegacyConditionCodecRegistrations(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir).filter { it.toString().endsWith(".java") }.toList()
    val conditionFiles = javaFiles.mapNotNull { file ->
        val text = file.readText()
        val className = classNameOfJavaSource(text) ?: return@mapNotNull null
        if (text.contains("implements ICondition") && text.contains("MapCodec<") && text.contains("CODEC")) {
            className to file
        } else {
            null
        }
    }.toMap()

    val registerPattern = Regex("""CraftingHelper\.register\s*\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.Serializer\.INSTANCE\s*\)\s*;?""")
    val registrations = javaFiles.flatMap { file ->
        val text = file.readText()
        if (!text.contains("CraftingHelper.register(")) return@flatMap emptyList()
        registerPattern.findAll(text).mapNotNull { match ->
            val rawClassRef = match.groupValues[1]
            val className = rawClassRef.substringAfterLast('.')
            val conditionFile = conditionFiles[className] ?: return@mapNotNull null
            val conditionText = conditionFile.readText()
            val conditionPackage = packageNameOf(conditionText)
            LegacyConditionCodecRegistration(
                registerFile = file,
                conditionFile = conditionFile,
                conditionPackage = conditionPackage,
                conditionClassName = className,
                registryIdExpression = if (conditionText.contains("CONDITION_ID")) "$className.CONDITION_ID" else "\"${classNameToPath(className.removeSuffix("Condition"))}\"",
                fieldName = classNameToConstant(className)
            )
        }.toList()
    }
    if (registrations.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    val registryFile = selectConditionCodecRegistryFile(javaFiles) ?: detectModMainClass(projectDir) ?: return emptyList()
    val registryOriginal = registryFile.readText()
    val registryClassName = classNameOfJavaSource(registryOriginal) ?: return emptyList()
    val registryPackage = packageNameOf(registryOriginal)
    val mainClass = detectModMainClass(projectDir)
    val mainText = mainClass?.readText().orEmpty()
    val modRef = modIdReferenceForGeneratedClass(mainClass, mainText, registryPackage)
    val registryMigrated = migrateConditionCodecRegistrySource(
        registryOriginal,
        registryClassName,
        registryPackage,
        modRef,
        registrations
    )
    if (registryMigrated != registryOriginal) {
        if (!dryRun) registryFile.writeText(registryMigrated)
        changes.add(Change(
            file = registryFile,
            line = 1,
            description = "Register legacy recipe condition codecs through DeferredRegister",
            before = "CraftingHelper.register(Condition.Serializer.INSTANCE)",
            after = "DeferredRegister<MapCodec<? extends ICondition>> CONDITION_CODECS",
            confidence = Confidence.HIGH,
            ruleId = "struct-condition-codec-deferred-register"
        ))
    }

    migrateConditionCodecMainRegistration(
        projectDir,
        registryPackage,
        registryClassName,
        dryRun
    )?.let { changes.add(it) }

    registrations.map { it.registerFile }.distinct().forEach { registerFile ->
        val original = registerFile.readText()
        val migrated = removeLegacyCraftingHelperConditionRegistration(original)
        if (migrated != original) {
            if (!dryRun) registerFile.writeText(migrated)
            changes.add(Change(
                file = registerFile,
                line = 1,
                description = "Remove obsolete CraftingHelper recipe condition serializer registration after codec registry migration",
                before = "CraftingHelper.register(Condition.Serializer.INSTANCE)",
                after = "Condition codec DeferredRegister entry",
                confidence = Confidence.HIGH,
                ruleId = "struct-condition-codec-remove-craftinghelper-register"
            ))
        }
    }

    return changes
}

internal fun StructuralRefactorPass.selectConditionCodecRegistryFile(javaFiles: List<Path>): Path? =
    javaFiles.firstOrNull { file ->
        val text = file.readText()
        text.contains("DeferredRegister<") &&
            (text.contains("LootItemConditionType") || text.contains("LootItemFunctionType") || text.contains("CONDITION_CODECS"))
    }

internal fun StructuralRefactorPass.migrateConditionCodecRegistrySource(
    source: String,
    registryClassName: String,
    registryPackage: String,
    modRef: String,
    registrations: List<LegacyConditionCodecRegistration>
): String {
    var text = source
    text = addImportIfMissing(text, "com.mojang.serialization.MapCodec")
    text = addImportIfMissing(text, "net.neoforged.neoforge.common.conditions.ICondition")
    text = addImportIfMissing(text, "net.neoforged.neoforge.registries.DeferredHolder")
    text = addImportIfMissing(text, "net.neoforged.neoforge.registries.DeferredRegister")
    text = addImportIfMissing(text, "net.neoforged.neoforge.registries.NeoForgeRegistries")
    for (registration in registrations) {
        if (registration.conditionPackage.isNotBlank() && registration.conditionPackage != registryPackage) {
            text = addImportIfMissing(text, "${registration.conditionPackage}.${registration.conditionClassName}")
        }
    }

    if (!text.contains("DeferredRegister<MapCodec<? extends ICondition>> CONDITION_CODECS")) {
        val classOpen = Regex("""public\s+(?:final\s+)?class\s+${Regex.escape(registryClassName)}\s*\{\s*""").find(text)
        if (classOpen != null) {
            val insertPos = classOpen.range.last + 1
            text = text.substring(0, insertPos) +
                "\n    public static final DeferredRegister<MapCodec<? extends ICondition>> CONDITION_CODECS = DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, $modRef);\n" +
                text.substring(insertPos)
        }
    }

    for (registration in registrations) {
        if (Regex("""\b${Regex.escape(registration.fieldName)}\b""").containsMatchIn(text)) continue
        val line = "    public static final DeferredHolder<MapCodec<? extends ICondition>, MapCodec<${registration.conditionClassName}>> ${registration.fieldName} = CONDITION_CODECS.register(${registration.registryIdExpression}, () -> ${registration.conditionClassName}.CODEC);\n"
        val conditionCodecRegister = Regex(
            """(?m)^[ \t]*public\s+static\s+final\s+DeferredRegister<MapCodec<\? extends ICondition>>\s+CONDITION_CODECS\s*=\s*[^;\r\n]+;\s*$"""
        ).find(text)
        text = if (conditionCodecRegister != null) {
            val insertPos = conditionCodecRegister.range.last + 1
            text.substring(0, insertPos) + "\n" + line + text.substring(insertPos)
        } else {
            val classOpen = Regex("""public\s+(?:final\s+)?class\s+${Regex.escape(registryClassName)}\s*\{\s*""").find(text)
            if (classOpen != null) {
                text.substring(0, classOpen.range.last + 1) + "\n" + line + text.substring(classOpen.range.last + 1)
            } else {
                text
            }
        }
    }
    return text
}

internal fun StructuralRefactorPass.migrateConditionCodecMainRegistration(
    projectDir: Path,
    registryPackage: String,
    registryClassName: String,
    dryRun: Boolean
): Change? {
    val mainClass = detectModMainClass(projectDir) ?: return null
    val original = mainClass.readText()
    var text = original
    val mainPackage = packageNameOf(text)
    val constructorBusName = requireModEventBusName(text, "condition codec registration")
    val classRef = if (registryPackage == mainPackage || registryPackage.isBlank()) {
        registryClassName
    } else {
        text = addImportIfMissing(text, "$registryPackage.$registryClassName")
        registryClassName
    }
    if (!text.contains("$classRef.CONDITION_CODECS.register(")) {
        val registrationLine = "        $classRef.CONDITION_CODECS.register($constructorBusName);"
        text = insertModBusListener(text, constructorBusName, registrationLine, registryClassName)
    }
    if (text == original) return null
    if (!dryRun) mainClass.writeText(text)
    return Change(
        file = mainClass,
        line = 1,
        description = "Register recipe condition codec DeferredRegister on the mod event bus",
        before = "CraftingHelper.register condition serializer",
        after = "$classRef.CONDITION_CODECS.register($constructorBusName)",
        confidence = Confidence.HIGH,
        ruleId = "struct-condition-codec-main-register"
    )
}

internal fun StructuralRefactorPass.removeLegacyCraftingHelperConditionRegistration(source: String): String {
    var text = source
    text = Regex(
        """(?s)\s*else\s+if\s*\(\s*[A-Za-z_$][\w$]*\.getRegistryKey\(\)\.equals\(\s*Registries\.RECIPE_SERIALIZER\s*\)\s*\)\s*\{\s*CraftingHelper\.register\s*\(\s*[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.Serializer\.INSTANCE\s*\)\s*;\s*\}"""
    ).replace(text, "")
    text = Regex(
        """(?m)^[ \t]*CraftingHelper\.register\s*\(\s*[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.Serializer\.INSTANCE\s*\)\s*;\s*\r?\n"""
    ).replace(text, "")
    if (!text.contains("CraftingHelper.")) {
        text = removeImportIfPresent(text, "net.neoforged.neoforge.common.crafting.CraftingHelper")
        text = removeImportIfPresent(text, "net.minecraftforge.common.crafting.CraftingHelper")
    }
    return text
}

internal fun StructuralRefactorPass.classNameToConstant(className: String): String =
    className.replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
        .replace(Regex("""([A-Z])([A-Z][a-z])"""), "$1_$2")
        .uppercase()

internal fun StructuralRefactorPass.classNameToPath(className: String): String =
    classNameToConstant(className).lowercase()

internal fun StructuralRefactorPass.migrateLegacySimpleCriterionTriggerSource(
    source: String,
    registration: LegacyCriterionRegistration
): String? {
    val packageName = packageNameOf(source)
    val className = registration.triggerClassName
    val instanceName = Regex("""extends\s+SimpleCriterionTrigger\s*<\s*${Regex.escape(className)}\.(\w+)\s*>""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: Regex("""public\s+static\s+class\s+(\w+)\s+extends\s+(?:SimpleCriterionTrigger\.SimpleInstance|AbstractCriterionTriggerInstance)""")
            .find(source)
            ?.groupValues
            ?.get(1)
        ?: "Instance"
    val registryImport = if (registration.registryPackage == packageName) "" else "import ${registration.registryPackage}.${registration.registryClassName};\n"
    return when {
        source.contains("EntityPredicate.fromJson") && source.contains("hurt_entity") ->
            hurtBossCriterionTriggerSource(packageName, registryImport, className, instanceName, registration)
        source.contains("deserializeBug") || (source.contains("\"bug\"") && source.contains("BlockState")) ->
            blockCriterionTriggerSource(packageName, registryImport, className, instanceName, registration)
        source.contains("ItemPredicate.fromJson") ->
            itemCriterionTriggerSource(packageName, registryImport, className, instanceName, registration, source)
        source.contains("MinMaxBounds.Ints.fromJson") && source.contains("Potion") ->
            potionCriterionTriggerSource(packageName, registryImport, className, instanceName, registration)
        source.contains("\"structure\"") && source.contains("structureName") ->
            stringCriterionTriggerSource(packageName, registryImport, className, instanceName, registration)
        else -> {
            val factories = Regex("""public\s+static\s+${Regex.escape(instanceName)}\s+(\w+)\s*\(\s*\)""")
                .findAll(source)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            noArgCriterionTriggerSource(packageName, registryImport, className, instanceName, registration, factories)
        }
    }
}

internal fun StructuralRefactorPass.noArgCriterionTriggerSource(
    packageName: String,
    registryImport: String,
    className: String,
    instanceName: String,
    registration: LegacyCriterionRegistration,
    factories: List<String>
): String {
    val factoryMethods = factories.joinToString("\n\n") { methodName ->
        """
        public static Criterion<$className.$instanceName> $methodName() {
            return ${registration.registryClassName}.${registration.fieldName}.get().createCriterion(new $instanceName(Optional.empty()));
        }
""".trimEnd()
    }
    return """package $packageName;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
${registryImport}import java.util.Optional;

public class $className extends SimpleCriterionTrigger<$className.$instanceName> {

    @Override
    public Codec<$className.$instanceName> codec() {
        return $instanceName.CODEC;
    }

    public void trigger(ServerPlayer player) {
        this.trigger(player, instance -> true);
    }

    public record $instanceName(Optional<ContextAwarePredicate> player) implements SimpleInstance {
        public static final Codec<$className.$instanceName> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter($className.$instanceName::player))
            .apply(instance, $className.$instanceName::new));

$factoryMethods
    }
}
"""
}

internal fun StructuralRefactorPass.hurtBossCriterionTriggerSource(
    packageName: String,
    registryImport: String,
    className: String,
    instanceName: String,
    registration: LegacyCriterionRegistration
): String = """package $packageName;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootContext;
${registryImport}import java.util.Optional;

public class $className extends SimpleCriterionTrigger<$className.$instanceName> {

    @Override
    public Codec<$className.$instanceName> codec() {
        return $instanceName.CODEC;
    }

    public void trigger(ServerPlayer player, Entity hurt) {
        LootContext entity = EntityPredicate.createContext(player, hurt);
        this.trigger(player, instance -> instance.matches(entity));
    }

    public record $instanceName(Optional<ContextAwarePredicate> player, Optional<ContextAwarePredicate> hurt) implements SimpleInstance {
        public static final Codec<$className.$instanceName> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter($className.$instanceName::player),
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("hurt_entity").forGetter($className.$instanceName::hurt))
            .apply(instance, $className.$instanceName::new));

        public boolean matches(LootContext hurt) {
            return this.hurt.isEmpty() || this.hurt.get().matches(hurt);
        }

        public static Criterion<$className.$instanceName> hurtBoss(EntityPredicate.Builder hurt) {
            return ${registration.registryClassName}.${registration.fieldName}.get().createCriterion(new $instanceName(Optional.empty(), Optional.of(EntityPredicate.wrap(hurt.build()))));
        }
    }
}
"""

internal fun StructuralRefactorPass.blockCriterionTriggerSource(
    packageName: String,
    registryImport: String,
    className: String,
    instanceName: String,
    registration: LegacyCriterionRegistration
): String = """package $packageName;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
${registryImport}import java.util.Optional;

public class $className extends SimpleCriterionTrigger<$className.$instanceName> {

    @Override
    public Codec<$className.$instanceName> codec() {
        return $instanceName.CODEC;
    }

    public void trigger(ServerPlayer player, BlockState bug) {
        this.trigger(player, instance -> instance.matches(bug));
    }

    public record $instanceName(Optional<ContextAwarePredicate> player, Optional<Block> bugType) implements SimpleInstance {
        public static final Codec<$className.$instanceName> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter($className.$instanceName::player),
                BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("bug").forGetter($className.$instanceName::bugType))
            .apply(instance, $className.$instanceName::new));

        public static Criterion<$className.$instanceName> killBug(Block bug) {
            return ${registration.registryClassName}.${registration.fieldName}.get().createCriterion(new $instanceName(Optional.empty(), Optional.of(bug)));
        }

        public boolean matches(BlockState bug) {
            return this.bugType.isEmpty() || bug.is(this.bugType.get());
        }
    }
}
"""

internal fun StructuralRefactorPass.itemCriterionTriggerSource(
    packageName: String,
    registryImport: String,
    className: String,
    instanceName: String,
    registration: LegacyCriterionRegistration,
    source: String
): String {
    val factories = itemCriterionFactoryMethods(source, className, instanceName, registration)
    return """package $packageName;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
${registryImport}import java.util.Optional;

public class $className extends SimpleCriterionTrigger<$className.$instanceName> {

    @Override
    public Codec<$className.$instanceName> codec() {
        return $instanceName.CODEC;
    }

    public void trigger(ServerPlayer player, ItemStack stack) {
        this.trigger(player, instance -> instance.matches(stack));
    }

    public record $instanceName(Optional<ContextAwarePredicate> player, Optional<ItemPredicate> item) implements SimpleInstance {
        public static final Codec<$className.$instanceName> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter($className.$instanceName::player),
                ItemPredicate.CODEC.optionalFieldOf("item").forGetter($className.$instanceName::item))
            .apply(instance, $className.$instanceName::new));

$factories

        public boolean matches(ItemStack stack) {
            return this.item.isEmpty() || this.item.get().test(stack);
        }
    }
}
"""
}

internal fun StructuralRefactorPass.itemCriterionFactoryMethods(
    source: String,
    className: String,
    instanceName: String,
    registration: LegacyCriterionRegistration
): String {
    val methods = mutableListOf<String>()
    val instanceType = """(?:${Regex.escape(className)}\.)?${Regex.escape(instanceName)}"""
    Regex("""public\s+static\s+$instanceType\s+(\w+)\s*\(\s*ItemPredicate\s+([A-Za-z_$][\w$]*)\s*\)""")
        .findAll(source)
        .map { it.groupValues[1] to it.groupValues[2] }
        .distinctBy { it.first }
        .forEach { (methodName, paramName) ->
            methods.add("""
        public static Criterion<$className.$instanceName> $methodName(ItemPredicate $paramName) {
            return ${registration.registryClassName}.${registration.fieldName}.get().createCriterion(new $instanceName(Optional.empty(), Optional.of($paramName)));
        }
""".trimEnd())
        }
    Regex("""public\s+static\s+$instanceType\s+(\w+)\s*\(\s*ItemLike\s+([A-Za-z_$][\w$]*)\s*\)""")
        .findAll(source)
        .map { it.groupValues[1] to it.groupValues[2] }
        .distinctBy { it.first }
        .forEach { (methodName, paramName) ->
            methods.add("""
        public static Criterion<$className.$instanceName> $methodName(ItemLike $paramName) {
            return $methodName(ItemPredicate.Builder.item().of($paramName).build());
        }
""".trimEnd())
        }
    Regex("""public\s+static\s+$instanceType\s+(\w+)\s*\(\s*\)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .distinct()
        .forEach { methodName ->
            methods.add("""
        public static Criterion<$className.$instanceName> $methodName() {
            return ${registration.registryClassName}.${registration.fieldName}.get().createCriterion(new $instanceName(Optional.empty(), Optional.empty()));
        }
""".trimEnd())
        }
    if (methods.isEmpty()) {
        methods.add("""
        public static Criterion<$className.$instanceName> createCriterion(ItemPredicate predicate) {
            return ${registration.registryClassName}.${registration.fieldName}.get().createCriterion(new $instanceName(Optional.empty(), Optional.of(predicate)));
        }
""".trimEnd())
    }
    return methods.joinToString("\n\n")
}

internal fun StructuralRefactorPass.potionCriterionTriggerSource(
    packageName: String,
    registryImport: String,
    className: String,
    instanceName: String,
    registration: LegacyCriterionRegistration
): String = """package $packageName;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.alchemy.Potion;
${registryImport}import java.util.Optional;

public class $className extends SimpleCriterionTrigger<$className.$instanceName> {

    @Override
    public Codec<$className.$instanceName> codec() {
        return $instanceName.CODEC;
    }

    public void trigger(ServerPlayer player, int doses, Potion potion) {
        this.trigger(player, instance -> instance.matches(doses, potion));
    }

    public record $instanceName(Optional<ContextAwarePredicate> player, MinMaxBounds.Ints doses, Optional<Potion> potion) implements SimpleInstance {
        public static final Codec<$className.$instanceName> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter($className.$instanceName::player),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("doses", MinMaxBounds.Ints.ANY).forGetter($className.$instanceName::doses),
                BuiltInRegistries.POTION.byNameCodec().optionalFieldOf("potion").forGetter($className.$instanceName::potion))
            .apply(instance, $className.$instanceName::new));

        public static Criterion<$className.$instanceName> drankPotion(int doses, Potion potion) {
            return ${registration.registryClassName}.${registration.fieldName}.get().createCriterion(new $instanceName(Optional.empty(), MinMaxBounds.Ints.exactly(doses), Optional.of(potion)));
        }

        public boolean matches(int doses, Potion potion) {
            return this.doses.matches(doses) && this.potion.isPresent() && this.potion.get() == potion;
        }
    }
}
"""

internal fun StructuralRefactorPass.stringCriterionTriggerSource(
    packageName: String,
    registryImport: String,
    className: String,
    instanceName: String,
    registration: LegacyCriterionRegistration
): String = """package $packageName;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
${registryImport}import java.util.Optional;

public class $className extends SimpleCriterionTrigger<$className.$instanceName> {

    @Override
    public Codec<$className.$instanceName> codec() {
        return $instanceName.CODEC;
    }

    public void trigger(ServerPlayer player, String structureName) {
        this.trigger(player, instance -> instance.test(structureName));
    }

    public record $instanceName(Optional<ContextAwarePredicate> player, String structureName) implements SimpleInstance {
        public static final Codec<$className.$instanceName> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter($className.$instanceName::player),
                Codec.STRING.fieldOf("structure").forGetter($className.$instanceName::structureName))
            .apply(instance, $className.$instanceName::new));

        public static Criterion<$className.$instanceName> clearedStructure(String name) {
            return ${registration.registryClassName}.${registration.fieldName}.get().createCriterion(new $instanceName(Optional.empty(), name));
        }

        boolean test(String structureName) {
            return this.structureName.equals(structureName);
        }
    }
}
"""

internal fun StructuralRefactorPass.extractCriterionTriggerPath(source: String): String? {
    Regex("""ResourceLocation\s+\w+\s*=\s*[\w.]+\.prefix\s*\(\s*"([^"]+)"""")
        .find(source)
        ?.let { return it.groupValues[1] }
    Regex("""ResourceLocation\.fromNamespaceAndPath\s*\([^,]+,\s*"([^"]+)"""")
        .find(source)
        ?.let { return it.groupValues[1] }
    return null
}

internal fun StructuralRefactorPass.criterionFieldToPath(fieldName: String): String =
    fieldName.lowercase().removeSuffix("_trigger")

internal fun StructuralRefactorPass.criterionPathToField(triggerPath: String): String =
    triggerPath.replace(Regex("""[^A-Za-z0-9]+"""), "_")
        .trim('_')
        .uppercase()

internal fun StructuralRefactorPass.migrateTransparentBlockBeaconSource(source: String): String {
    var result = source
    result = result.replace(
        "import net.minecraft.world.level.block.AbstractGlassBlock;",
        "import net.minecraft.world.level.block.TransparentBlock;"
    )
    result = result.replace(Regex("""\bextends\s+AbstractGlassBlock\b"""), "extends TransparentBlock")
    val beaconColorPattern = Regex(
        """(?s)public\s+float\[\]\s+getBeaconColorMultiplier\s*\(([^)]*)\)\s*\{\s*int\s+color\s*=\s*([^;]+);\s*int\s+red\s*=.*?return\s+new\s+float\[\]\s*\{[^}]*}\s*;\s*\}"""
    )
    result = beaconColorPattern.replace(result) { match ->
        val params = match.groupValues[1]
        val colorExpression = match.groupValues[2].trim()
        """public Integer getBeaconColorMultiplier($params) {
        return $colorExpression;
    }"""
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPlantApiSource(source: String): String {
    var result = source
    result = Regex("""(?m)^[ \t]*(?://.*)?PlantType removed in NeoForge 1\.21[^\r\n]*\r?\n""")
        .replace(result, "")
    result = Regex("""(?m)^[ \t]*import\s+net\.neoforged\.neoforge\.common\.PlantType;\s*\r?\n""")
        .replace(result, "")
    result = Regex("""(?m)^[ \t]*import\s+net\.neoforged\.neoforge\.common\.IPlantable;\s*\r?\n""")
        .replace(result, "")
    result = Regex("""(?m)^[ \t]*import\s+net\.neoforged\.neoforge\.common\.SpecialPlantable\s*/\*.*?\*/;\s*\r?\n""")
        .replace(result, "")

    var migratedFarmlandMaintain = false
    val id = """[A-Za-z_$][\w$]*"""
    result = Regex(
        """(?s)private\s+static\s+boolean\s+shouldMaintainFarmland\s*\(\s*BlockGetter\s+($id)\s*,\s*BlockPos\s+($id)\s*\)\s*\{\s*BlockState\s+($id)\s*=\s*\1\.getBlockState\(\s*\2\.above\(\)\s*\)\s*;\s*BlockState\s+($id)\s*=\s*\1\.getBlockState\(\s*\2\s*\)\s*;\s*return\s+\3\.getBlock\(\)\s+instanceof\s+(?:IPlantable|SpecialPlantable)\s+($id)\s*&&\s*\4\.canSustainPlant\(\s*\1\s*,\s*\2\s*,\s*Direction\.UP\s*,\s*\5\s*\)\s*;\s*}"""
    ).replace(result) { match ->
        migratedFarmlandMaintain = true
        val level = match.groupValues[1]
        val pos = match.groupValues[2]
        """private static boolean shouldMaintainFarmland(BlockGetter $level, BlockPos $pos) {
        return $level.getBlockState($pos.above()).is(BlockTags.MAINTAINS_FARMLAND);
    }"""
    }

    result = Regex(
        """(?s)\n[ \t]*@Override\s*\r?\n[ \t]*public\s+PlantType\s+getPlantType\s*\([^)]*\)\s*\{.*?\n[ \t]*}\s*"""
    ).replace(result, "\n")

    val canSustainPattern = Regex(
        """(?s)@Override\s*\r?\n[ \t]*public\s+boolean\s+canSustainPlant\s*\(\s*BlockState\s+\w+\s*,\s*BlockGetter\s+\w+\s*,\s*BlockPos\s+\w+\s*,\s*Direction\s+\w+\s*,\s*(?:IPlantable|SpecialPlantable)\s+\w+\s*\)\s*\{.*?\n[ \t]*}\s*"""
    )
    val migratedCanSustain = canSustainPattern.containsMatchIn(result)
    result = canSustainPattern.replace(result) {
        """@Override
    public TriState canSustainPlant(BlockState state, BlockGetter level, BlockPos soilPosition, Direction facing, BlockState plant) {
        if (facing.getAxis() != Direction.Axis.Y) return TriState.FALSE;
        if (plant.is(BlockTags.CROPS)) return TriState.TRUE;
        return super.canSustainPlant(state, level, soilPosition, facing, plant);
    }
"""
    }
    result = Regex("""bonemealableBlock\s+instanceof\s+(?:IPlantable|SpecialPlantable)\s+\w+\s*&&\s*\w+\.getPlantType\s*\(\s*level\s*,\s*fromPos\s*\)\s*==\s*PlantType\.CROP""")
        .replace(result, "above.is(BlockTags.CROPS)")
    if (migratedCanSustain || migratedFarmlandMaintain || result.contains("BlockTags.CROPS")) {
        result = addImportIfMissing(result, "net.minecraft.tags.BlockTags")
    }
    if (migratedCanSustain) {
        result = addImportIfMissing(result, "net.neoforged.neoforge.common.util.TriState")
    }
    return result
}

internal fun StructuralRefactorPass.advancementTriggerCodecSource(
    packageName: String,
    className: String,
    classIsPublic: Boolean
): String {
    val visibility = if (classIsPublic) "public " else ""
    return """package $packageName;

import com.mojang.serialization.Codec;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.critereon.CriterionValidator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

${visibility}class $className implements CriterionTrigger<$className.Instance> {
    private final Map<PlayerAdvancements, CriterionTrigger.Listener<Instance>> listeners = new HashMap<>();
    private final ResourceLocation id;

    public $className(String modName, String advancementName) {
        this.id = ResourceLocation.fromNamespaceAndPath(modName, advancementName);
    }

    @Override
    public void addPlayerListener(
            @NotNull PlayerAdvancements playerAdvancements,
            @NotNull CriterionTrigger.Listener<Instance> listener) {
        this.listeners.put(playerAdvancements, listener);
    }

    @Override
    public void removePlayerListener(
            @NotNull PlayerAdvancements playerAdvancements,
            @NotNull CriterionTrigger.Listener<Instance> listener) {
        this.listeners.remove(playerAdvancements);
    }

    @Override
    public void removePlayerListeners(@NotNull PlayerAdvancements playerAdvancements) {
        this.listeners.remove(playerAdvancements);
    }

    public void trigger(ServerPlayer player) {
        CriterionTrigger.Listener<Instance> listener = this.listeners.get(player.getAdvancements());
        if (listener != null) {
            listener.run(player.getAdvancements());
        }
    }

    @Override
    public @NotNull Codec<Instance> codec() {
        return Codec.unit(() -> new Instance(this.id));
    }

    public static class Instance implements CriterionTriggerInstance {
        private final ResourceLocation id;

        public Instance(ResourceLocation id) {
            this.id = id;
        }

        @Override
        public void validate(@NotNull CriterionValidator validator) {
        }
    }
}
"""
}

internal fun StructuralRefactorPass.extraEventsRegisterAdvancementSource(
    packageName: String,
    className: String,
    classIsPublic: Boolean,
    modRef: String,
    specs: List<AdvancementTriggerSpec>
): String {
    val visibility = if (classIsPublic) "public " else ""
    val fields = specs.joinToString("\n") { spec ->
        """
    public static final Supplier<${spec.triggerClassName}> ${spec.fieldName} =
            TRIGGERS.register("${spec.path}", () -> new ${spec.triggerClassName}("${spec.namespace}", "${spec.path}"));
""".trimEnd()
    }
    val triggerImports = specs
        .map { it.triggerPackage to it.triggerClassName }
        .distinct()
        .filter { (triggerPackage, _) -> triggerPackage != packageName }
        .joinToString("") { (triggerPackage, triggerClassName) -> "import $triggerPackage.$triggerClassName;\n" }
    return """package $packageName;

${triggerImports}import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

${visibility}class $className {
    public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, $modRef);

$fields

    public static void register(IEventBus modEventBus) {
        TRIGGERS.register(modEventBus);
    }
}
"""
}

internal data class JavaClassDeclaration(
    val name: String,
    val directSuper: String,
    val bodyRange: IntRange
)

internal data class JavaRegistryAccessField(
    val expression: String,
    val isFinal: Boolean
)

internal data class JavaInheritanceIndex(
    private val directSuperByClass: Map<String, String>,
    private val registryAccessFieldByClass: Map<String, JavaRegistryAccessField> = emptyMap()
) {
    fun inherits(directSuper: String, baseTypes: Set<String>): Boolean {
        var current = directSuper.substringAfterLast('.')
        val visited = mutableSetOf<String>()
        while (current.isNotBlank() && visited.add(current)) {
            if (current in baseTypes) return true
            current = directSuperByClass[current]?.substringAfterLast('.')
                ?: externalDirectSuperBySimpleName[current]?.substringAfterLast('.')
                ?: return false
        }
        return false
    }

    fun inheritedRegistryAccessField(className: String): String? =
        inheritedRegistryAccessFieldInfo(className)?.expression

    fun inheritedRegistryAccessFieldInfo(className: String): JavaRegistryAccessField? {
        var current = className.substringAfterLast('.')
        val visited = mutableSetOf<String>()
        while (current.isNotBlank() && visited.add(current)) {
            registryAccessFieldByClass[current]?.let { return it }
            current = directSuperByClass[current]?.substringAfterLast('.') ?: return null
        }
        return null
    }

    fun directSuperOf(className: String): String? =
        directSuperByClass[className.substringAfterLast('.')]?.substringAfterLast('.')?.takeIf { it.isNotBlank() }

    fun isProjectType(className: String): Boolean = directSuperByClass.containsKey(className.substringAfterLast('.'))

    companion object {
        val EMPTY = JavaInheritanceIndex(emptyMap())

        private val externalDirectSuperBySimpleName: Map<String, String> = mapOf(
            "AbstractVillager" to "AgeableMob",
            "Villager" to "AbstractVillager",
            "WanderingTrader" to "AbstractVillager",
            "Raider" to "PatrollingMonster",
            "PatrollingMonster" to "Monster",
            "Zombie" to "Monster",
            "ZombieVillager" to "Zombie",
            "Husk" to "Zombie",
            "Drowned" to "Zombie",
            "AbstractSkeleton" to "Monster",
            "Skeleton" to "AbstractSkeleton",
            "WitherSkeleton" to "AbstractSkeleton",
            "Stray" to "AbstractSkeleton",
            "Creeper" to "Monster",
            "Spider" to "Monster",
            "CaveSpider" to "Spider",
            "EnderMan" to "Monster",
            "Silverfish" to "Monster",
            "Endermite" to "Monster",
            "Ghast" to "FlyingMob",
            "Witch" to "Raider",
            "Pillager" to "Raider",
            "Vindicator" to "Raider",
            "Evoker" to "Raider",
            "Ravager" to "Raider",
            "AbstractGolem" to "PathfinderMob",
            "IronGolem" to "AbstractGolem",
            "SnowGolem" to "AbstractGolem",
            "WaterAnimal" to "PathfinderMob",
            "AbstractFish" to "WaterAnimal",
            "Cod" to "AbstractFish",
            "Salmon" to "AbstractFish",
            "Pufferfish" to "AbstractFish",
            "TropicalFish" to "AbstractFish",
            "Dolphin" to "WaterAnimal",
            "Squid" to "WaterAnimal",
            "GlowSquid" to "Squid",
            "Horse" to "AbstractHorse",
            "Donkey" to "AbstractChestedHorse",
            "Mule" to "AbstractChestedHorse",
            "Llama" to "AbstractChestedHorse",
            "TraderLlama" to "Llama",
            "Camel" to "AbstractHorse",
            "Pig" to "Animal",
            "Cow" to "Animal",
            "Sheep" to "Animal",
            "Chicken" to "Animal",
            "Rabbit" to "Animal",
            "Fox" to "Animal",
            "Bee" to "Animal",
            "Panda" to "Animal",
            "Turtle" to "Animal",
            "Frog" to "Animal",
            "Sniffer" to "Animal",
            "Wolf" to "TamableAnimal",
            "Cat" to "TamableAnimal",
            "Parrot" to "TamableAnimal",
            "Ocelot" to "Animal",
            "AbstractHurtingProjectile" to "Projectile",
            "Fireball" to "AbstractHurtingProjectile",
            "SmallFireball" to "Fireball",
            "LargeFireball" to "Fireball",
            "WitherSkull" to "Fireball",
            "ThrowableItemProjectile" to "ThrowableProjectile",
            "ThrownPotion" to "ThrowableItemProjectile",
            "ThrownEnderpearl" to "ThrowableItemProjectile",
            "ThrownExperienceBottle" to "ThrowableItemProjectile",
            "Snowball" to "ThrowableItemProjectile",
            "AbstractMinecartContainer" to "AbstractMinecart",
            "Minecart" to "AbstractMinecart",
            "ChestBoat" to "Boat",
            "SyncedBlockEntity" to "BlockEntity"
        )
    }
}

internal fun StructuralRefactorPass.collectJavaInheritanceIndex(javaFiles: List<Path>): JavaInheritanceIndex {
    val directSuperByClass = linkedMapOf<String, String>()
    val fieldsByClass = linkedMapOf<String, MutableList<Triple<String, String, Boolean>>>()
    javaFiles.forEach { javaFile ->
        val source = javaFile.readText()
        val declarations = collectJavaClassDeclarations(source)
        val typeParameters by lazy { JavaTypeParameterScopes(source) }
        declarations.forEach { declaration ->
            directSuperByClass.putIfAbsent(declaration.name, declaration.directSuper)
            val body = source.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
            Regex(
                """(?m)^[ \t]*(?:@\w+(?:\.\w+)*(?:\([^)]*\))?\s+)*(?:public|protected|private)\s+(?!static\b)(?:(final)\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s+([A-Za-z_$][\w$]*)\s*(?:=[^;\r\n]*)?;"""
            ).findAll(maskJavaCommentsAndLiterals(body))
                .forEach fieldLoop@ { field ->
                    val offset = declaration.bodyRange.first + 1 + field.range.first
                    val owner = declarations.filter { offset in it.bodyRange }.minByOrNull { it.bodyRange.last - it.bodyRange.first }
                    if (owner?.name != declaration.name) return@fieldLoop
                    var type = field.groupValues[2].substringAfterLast('.')
                    val seen = mutableSetOf<String>()
                    while (seen.add(type)) {
                        val bound = typeParameters.boundOf(type, offset) ?: break
                        type = bound.substringBefore('<').substringAfterLast('.').trim()
                    }
                    fieldsByClass.getOrPut(declaration.name) { mutableListOf() } +=
                        Triple(type, field.groupValues[3], field.groupValues[1].isNotEmpty())
                }
        }
    }
    val provisional = JavaInheritanceIndex(directSuperByClass)
    val entityBaseTypes = javaEntityBaseTypes()
    val blockEntityBaseTypes = javaBlockEntityBaseTypes()
    val registryAccessFields = fieldsByClass.mapNotNull { (className, fields) ->
        val entityFields = fields.filter { (type, _, _) ->
            type in entityBaseTypes || provisional.inherits(type, entityBaseTypes)
        }
        if (entityFields.size == 1) {
            val (_, name, isFinal) = entityFields.single()
            return@mapNotNull className to JavaRegistryAccessField(name, isFinal)
        }
        // A behaviour owning one block entity reads registries through that entity's level.
        val blockEntityFields = fields.filter { (type, _, _) ->
            type in blockEntityBaseTypes || provisional.inherits(type, blockEntityBaseTypes)
        }
        if (blockEntityFields.size == 1) {
            val (_, name, isFinal) = blockEntityFields.single()
            return@mapNotNull className to JavaRegistryAccessField("$name.getLevel()", isFinal)
        }
        // a command source, a level or a command block carries the registries of its level
        // (CommandSourceStack#registryAccess, Level#registryAccess, BaseCommandBlock#getLevel in 1.21.1)
        listOf(
            setOf("CommandSourceStack") to "",
            setOf("ServerLevel", "Level") to "",
            setOf("BaseCommandBlock") to ".getLevel()"
        ).firstNotNullOfOrNull { (types, suffix) ->
            fields.filter { (type, _, _) -> type in types }.singleOrNull()?.let { field ->
                className to JavaRegistryAccessField("${field.second}$suffix", field.third)
            }
        }
    }.toMap()
    return JavaInheritanceIndex(directSuperByClass, registryAccessFields)
}

internal fun StructuralRefactorPass.collectJavaClassNamesExtending(
    javaFiles: List<Path>,
    baseTypes: Set<String>,
    javaInheritanceIndex: JavaInheritanceIndex
): Set<String> =
    javaFiles.flatMap { javaFile -> collectJavaClassDeclarations(javaFile.readText()) }
        .filter { declaration -> javaClassExtendsAny(declaration, baseTypes, javaInheritanceIndex) }
        .map { it.name }
        .toSet()

internal fun StructuralRefactorPass.collectJavaClassDeclarations(source: String): List<JavaClassDeclaration> {
    val classPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*class\s+([A-Za-z_$][\w$]*)(?:\s*<[^>{}]+>)?(?:\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?))?\b[^{]*\{"""
    )
    return classPattern.findAll(source).mapNotNull { match ->
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            null
        } else {
            JavaClassDeclaration(
                name = match.groupValues[1],
                directSuper = match.groupValues[2],
                bodyRange = openBrace..closeBrace
            )
        }
    }.toList()
}

internal fun StructuralRefactorPass.collectJavaRecipeImplementationClasses(
    javaFiles: List<Path>,
    javaInheritanceIndex: JavaInheritanceIndex
): Set<String> {
    val explicitRecipeClasses = linkedSetOf<String>()
    val declarations = mutableListOf<JavaClassDeclaration>()
    javaFiles.forEach { javaFile ->
        val source = maskJavaCommentsAndLiterals(javaFile.readText())
        declarations += collectJavaClassDeclarations(source)
        Regex(
            """(?m)\b(?:class|record)\s+([A-Za-z_$][\w$]*)\b[^{;]*\bimplements\s+[^{;]*\bRecipe\s*<"""
        ).findAll(source).forEach { match ->
            explicitRecipeClasses += match.groupValues[1]
        }
    }

    val recipeClasses = explicitRecipeClasses.toMutableSet()
    var changed = true
    while (changed) {
        changed = false
        declarations.forEach { declaration ->
            if (declaration.name !in recipeClasses &&
                javaInheritanceIndex.inherits(declaration.directSuper, recipeClasses)) {
                recipeClasses += declaration.name
                changed = true
            }
        }
    }
    return recipeClasses
}

internal fun StructuralRefactorPass.enclosingJavaClassDeclaration(source: String, index: Int): JavaClassDeclaration? =
    collectJavaClassDeclarations(source)
        .filter { index in it.bodyRange }
        .minByOrNull { it.bodyRange.last - it.bodyRange.first }

internal fun StructuralRefactorPass.javaClassExtendsAny(
    declaration: JavaClassDeclaration?,
    baseTypes: Set<String>,
    inheritanceIndex: JavaInheritanceIndex
): Boolean = declaration?.let { inheritanceIndex.inherits(it.directSuper, baseTypes) } == true

internal fun StructuralRefactorPass.javaLivingEntityBaseTypes(): Set<String> = setOf(
    "LivingEntity",
    "Mob",
    "PathfinderMob",
    "FlyingMob",
    "Monster",
    "Slime",
    "MagmaCube",
    "AgeableMob",
    "Animal",
    "TamableAnimal",
    "AbstractHorse",
    "Player",
    "ServerPlayer",
    // NeoForge's FakePlayer extends ServerPlayer (javap of neoforge-21.1.86)
    "FakePlayer"
)

internal fun StructuralRefactorPass.javaEntityBaseTypes(): Set<String> = javaLivingEntityBaseTypes() + setOf(
    "Entity",
    "AbstractMinecart",
    "Boat",
    "Projectile",
    "ThrowableProjectile",
    "AbstractArrow"
)

internal fun StructuralRefactorPass.javaBlockEntityBaseTypes(): Set<String> = setOf(
    "BlockEntity",
    "BaseContainerBlockEntity",
    "RandomizableContainerBlockEntity",
    "AbstractFurnaceBlockEntity",
    "FurnaceBlockEntity",
    "SmokerBlockEntity",
    "BlastFurnaceBlockEntity",
    "ChestBlockEntity",
    "BarrelBlockEntity",
    "ShulkerBoxBlockEntity",
    "SkullBlockEntity",
    "SignBlockEntity",
    "HangingSignBlockEntity",
    "TheEndGatewayBlockEntity",
    "BrushableBlockEntity"
)

internal fun StructuralRefactorPass.javaItemBaseTypes(): Set<String> = setOf(
    "Item",
    "BlockItem",
    "ItemNameBlockItem",
    "TieredItem",
    "DiggerItem",
    "AxeItem",
    "PickaxeItem",
    "ShovelItem",
    "HoeItem",
    "SwordItem",
    "ArmorItem",
    "BowItem",
    "CrossbowItem",
    "TridentItem",
    "ShieldItem",
    "BucketItem",
    "SpawnEggItem",
    "RecordItem"
)
