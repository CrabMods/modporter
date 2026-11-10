package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.*
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ReturnStmt
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

// Structural migration rules, part 06 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateDeprecatedLevelReaderHasChunksAtCalls, migrateXlintCleanApiBridges, migrateDeprecatedClientColorRegistrations, migrateDeprecatedRenderLayerRegistrations.

internal fun StructuralRefactorPass.migrateProjectItemStackNbtConstructors(
    source: String,
    constructors: Set<HolderLookupNbtConstructor>,
    methods: Set<HolderLookupNbtMethod>,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (constructors.isEmpty() || !source.contains("CompoundTag")) return source
    val constructorOwners = constructors.map { it.owner }.toSet()
    val constructorsByOwner = constructors.groupBy { it.owner }
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (constructorOwners.none { Regex("""(?<![\w$])${Regex.escape(it)}(?![\w$])""").containsMatchIn(executableCode) }) {
        return source
    }
    val methodsByName = methods.groupBy { it.methodName }
    val methodOwnerNames = methods.map { it.owner }.toSet()
    val methodOwnerAlternation = methodOwnerNames.joinToString("|") { Regex.escape(it) }
    fun variableTypesIn(text: String): Map<String, String> =
        if (methodOwnerAlternation.isBlank()) emptyMap() else {
            Regex(
                """\b(?:[A-Za-z_$][\w$]*\.)*($methodOwnerAlternation)\s+([A-Za-z_$][\w$]*)\b"""
            )
                .findAll(maskJavaCommentsAndLiterals(text))
                .associate { it.groupValues[2] to it.groupValues[1] }
    }

    var result = source
    var changedSignature = false
    val constructorSignatureEdits = mutableListOf<Pair<IntRange, String>>()
    for (constructorRange in constructorRangesForOwners(result, constructorOwners)) {
        val span = callableParameterSpan(result, constructorRange.range) ?: continue
        val paramsText = result.substring(span.first + 1, span.second)
        val parameterCount = splitTopLevelJavaArgs(paramsText).size
        val targetConstructors = constructorsByOwner[constructorRange.owner].orEmpty()
        if (targetConstructors.none { it.parameterCount == null || it.parameterCount == parameterCount } ||
            paramsText.contains("HolderLookup.Provider")) {
            continue
        }
        changedSignature = true
        val params = paramsText.trim()
        val insertion = if (params.isBlank()) {
            "HolderLookup.Provider registries"
        } else {
            ", HolderLookup.Provider registries"
        }
        constructorSignatureEdits += span.second until span.second to insertion
    }
    if (constructorSignatureEdits.isNotEmpty()) {
        result = applyStringEdits(result, constructorSignatureEdits)
    }

    val targetConstructorRanges = constructorRangesForOwners(result, constructorOwners)
    fun targetConstructorAt(offset: Int): String? =
        targetConstructorRanges.firstOrNull { offset in it.range }?.owner

    val variableTypes = variableTypesIn(result)
    fun receiverOwner(receiver: String): String? {
        val normalized = receiver.trim()
        if (normalized == "this") return null
        return variableTypes[normalized.substringAfterLast('.')]
    }
    for (methodName in methodsByName.keys) {
        result = rewriteExecutableJavaCallWithOffset(result, methodName) { receiver, args, offset ->
            if (targetConstructorAt(offset) == null) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val owner = receiverOwner(receiver) ?: return@rewriteExecutableJavaCallWithOffset null
            val contract = resolveHolderLookupNbtMethodContract(
                methodsByName[methodName].orEmpty(),
                owner,
                methodName,
                args.size
            ) ?: methodsByName[methodName].orEmpty()
                .filter {
                    it.owner == owner &&
                        (it.parameterCount == null || it.parameterCount == args.size)
                }
                .singleOrNull()
                ?: return@rewriteExecutableJavaCallWithOffset null
            val providerNames = holderLookupProviderNamesAt(result, offset)
            if (providerNames.size != 1) {
                throw IllegalStateException(
                    "Constructor call to $owner.$methodName requires exactly one " +
                        "HolderLookup.Provider; found ${providerNames.size}"
                )
            }
            val migratedArgs = insertHolderLookupProviderArgument(
                args,
                providerNames.single(),
                contract
            )
            "$receiver.$methodName(${migratedArgs.joinToString(", ")})"
        }
    }

    for (constructor in constructors) {
        result = rewriteExecutableJavaNewWithOffset(result, constructor.owner) { args, offset ->
            if (constructor.parameterCount != null && constructor.parameterCount != args.size) {
                return@rewriteExecutableJavaNewWithOffset null
            }
            val providerNames = holderLookupProviderNamesAt(result, offset)
            if (args.any { isHolderLookupProviderExpression(it, providerNames) }) {
                return@rewriteExecutableJavaNewWithOffset null
            }
            val registryAccess = registryAccessExpressionAt(result, offset, javaInheritanceIndex)
                ?: return@rewriteExecutableJavaNewWithOffset null
            "new ${constructor.owner}(${args.joinToString(", ") { it.trim() }}, ${holderLookupProviderArgumentExpression(registryAccess)})"
        }
    }

    if (changedSignature && result.contains("HolderLookup.Provider")) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    return result
}

internal data class JavaConstructorRange(val owner: String, val range: IntRange)

internal fun StructuralRefactorPass.constructorRangesForOwners(source: String, owners: Set<String>): List<JavaConstructorRange> {
    if (owners.isEmpty()) return emptyList()
    val executableCode = maskJavaCommentsAndLiterals(source)
    val typeBlocks = javaTypeBlocks(source, executableCode)
    val result = mutableListOf<JavaConstructorRange>()
    for (type in typeBlocks) {
        if (type.name !in owners) continue
        val constructorPattern = Regex(
            """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:public|protected|private)?\s*${Regex.escape(type.name)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
        )
        constructorPattern.findAll(executableCode).forEach { match ->
            if (match.range.first <= type.bodyStart || match.range.first >= type.end) return@forEach
            val openBrace = executableCode.indexOf('{', match.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
            if (openBrace >= 0 && closeBrace >= 0 && closeBrace <= type.end) {
                result += JavaConstructorRange(type.name, match.range.first..closeBrace)
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.methodTextNeedsItemStackProvider(
    methodText: String,
    itemStackNames: Set<String>,
    itemStackCollectionNames: Set<String>
): Boolean {
    val executableMethod = maskJavaCommentsAndLiterals(methodText)
    if (Regex("""(?<![\w$])ItemStack(?:\.of\(|::of\b)""").containsMatchIn(executableMethod)) return true
    if (Regex("""\bComponent\.Serializer\.(?:fromJson|fromJsonLenient|toJson)\(""").containsMatchIn(executableMethod)) return true
    for (name in itemStackNames) {
        val receiver = Regex.escape(name)
        if (Regex("""(?<![\w$])(?:this\.)?$receiver\.(?:save|serializeNBT)\s*\(""").containsMatchIn(executableMethod)) {
            return true
        }
    }
    for (name in itemStackCollectionNames) {
        val receiver = Regex.escape(name)
        if (Regex("""(?<![\w$])(?:this\.)?$receiver\.get\s*\([^)]*\)\.(?:save|serializeNBT)\s*\(""").containsMatchIn(executableMethod)) {
            return true
        }
    }
    return false
}

internal fun StructuralRefactorPass.methodTextNeedsHolderLookupProvider(
    methodText: String,
    itemStackNames: Set<String>,
    itemStackCollectionNames: Set<String>,
    itemStackHandlerNames: Set<String>,
    fluidTankNames: Set<String>,
    ownerExtendsItemStackHandler: Boolean
): Boolean {
    val executableMethod = maskJavaCommentsAndLiterals(methodText)
    if (methodTextNeedsItemStackProvider(methodText, itemStackNames, itemStackCollectionNames)) return true
    // A BlockEntity parameter may be freshly constructed and have no level. Seed the project
    // provider graph here so callers thread a real provider instead of reading one from the receiver.
    val blockEntityNames = Regex(
        """\b(?:net\.minecraft\.world\.level\.block\.entity\.)?BlockEntity\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(executableMethod).map { it.groupValues[1] }.toSet()
    if (blockEntityNames.any { name ->
            Regex(
                """(?<![\w$])(?:this\s*\.\s*)?${Regex.escape(name)}\s*\.\s*saveWithFullMetadata\s*\(\s*\)"""
            ).containsMatchIn(executableMethod)
        }
    ) {
        return true
    }
    if (ownerExtendsItemStackHandler &&
        Regex("""(?<![\w$])(?:this\.)?stacks\.get\s*\([^)]*\)\.(?:save|serializeNBT)\s*\(""").containsMatchIn(executableMethod)
    ) {
        return true
    }
    for (name in itemStackHandlerNames) {
        val receiver = Regex.escape(name)
        if (Regex("""(?<![\w$])(?:this\.)?$receiver\.(?:serializeNBT|deserializeNBT)\s*\(""").containsMatchIn(executableMethod)) {
            return true
        }
    }
    for (name in fluidTankNames) {
        val receiver = Regex.escape(name)
        if (Regex("""(?<![\w$])(?:this\.)?$receiver\.(?:readFromNBT|writeToNBT)\s*\(""").containsMatchIn(executableMethod)) {
            return true
        }
    }
    return false
}

internal fun StructuralRefactorPass.methodTextCallsHolderLookupNbtMethod(
    methodText: String,
    source: String,
    currentOwner: String,
    methods: Set<HolderLookupNbtMethod>,
    // the project hierarchy: a receiver typed by a subclass of the declaring type calls the same method
    related: (String, String) -> Boolean = { a, b -> a == b },
    // whether a call at this offset of [methodText] has no provider of its own (none in scope)
    needsProviderAt: (Int) -> Boolean = { true },
    // the exact start of [methodText] in [source], so lambda and project-return facts use source offsets
    methodOffset: Int = source.indexOf(methodText),
    sourceValueTypes: SourceValueTypes? = null
): Boolean {
    if (methods.isEmpty()) return false
    val executableMethod = maskJavaCommentsAndLiterals(methodText)
    val calledNames = Regex("""\.([A-Za-z_$][\w$]*)\s*\(|::([A-Za-z_$][\w$]*)""")
        .findAll(executableMethod).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
    val methodsByName = methods.filter { it.methodName in calledNames }.groupBy { it.methodName }
    if (methodsByName.isEmpty()) return false
    val executableSource = maskJavaCommentsAndLiterals(source)
    // Only owners of methods this body actually calls can affect its receiver typing.
    val ownerNames = methodsByName.values.flatten().map { it.owner }.toSet()
    val ownerAlternation = ownerNames.joinToString("|") { Regex.escape(it) }
    val variableTypes = if (ownerAlternation.isBlank()) emptyMap() else {
        Regex("""\b($ownerAlternation)\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableSource)
            .associate { it.groupValues[2] to it.groupValues[1] } +
            Regex("""\b([A-Z][\w$]*)(?:<[^<>;=(){}]*>)?\s+([A-Za-z_$][\w$]*)\s*(?=[=;,):])""")
                .findAll(executableSource)
                .filter { it.groupValues[1] !in ownerNames && ownerNames.any { owner -> related(it.groupValues[1], owner) } }
                .associate { it.groupValues[2] to it.groupValues[1] }
    }
    val valueTypes by lazy { sourceValueTypes ?: SourceValueTypes(this, source) }
    fun argumentType(argument: String, offsetInMethod: Int): String? =
        argumentDeclaredType(executableMethod, argument, offsetInMethod)
            ?: methodOffset.takeIf { it >= 0 }?.let { valueTypes.typeOf(argument, it + offsetInMethod) }

    fun callsTarget(receiverOwner: String?, methodName: String, openParen: Int): Boolean {
        val targets = methodsByName[methodName].orEmpty()
        if (receiverOwner == null || targets.isEmpty() || !needsProviderAt(openParen)) return false
        val closeParen = findMatchingParen(executableMethod, openParen)
        if (closeParen < 0) return false
        val args = splitTopLevelJavaArgs(methodText.substring(openParen + 1, closeParen))
        return targets.any { target ->
            related(receiverOwner, target.owner) &&
                (target.parameterCount == null || target.parameterCount == args.size) &&
                argumentsFitOverload(target, args) { argumentType(it, openParen) }
        }
    }
    val callPattern = Regex("""\b((?:this|[A-Za-z_$][\w$]*(?:\[[^\]]*\])?))\.([A-Za-z_$][\w$]*)\s*\(""")
    val namedReceiverCall = callPattern.findAll(executableMethod).any { call ->
        val receiver = call.groupValues[1]
        val receiverOwner = if (receiver == "this") currentOwner else variableTypes[receiver]
            ?: arrayElementReceiverOwner(receiver, executableSource, ownerAlternation)
            // Owner.method(...) through the declaring class (or a project subclass) itself
            ?: receiver.takeIf { name -> name.first().isUpperCase() && ownerNames.any { owner -> related(name, owner) } }
        callsTarget(receiverOwner, call.groupValues[2], call.range.last)
    }
    // Owner::method (or this::method) handed on as a function calls it as well; the call-site
    // rewrite turns such a reference into a lambda passing the provider (a reference to an
    // overloaded name names no overload by itself)
    val referencedTarget = Regex("""(?<![\w$])(this|[A-Z][\w$]*)::([A-Za-z_$][\w$]*)(?![\w$(])""").findAll(executableMethod).any { reference ->
        val owner = if (reference.groupValues[1] == "this") currentOwner else reference.groupValues[1]
        needsProviderAt(reference.range.first) && methodsByName[reference.groupValues[2]].orEmpty().any { target ->
            related(owner, target.owner) && (target.parameterCount == 0 || target.parameterCount == 1) &&
                (target.owner to target.methodName) !in overloadedNbtMethods
        }
    }
    // ((Owner) expression).method(...): the cast names the receiver type
    val castReceiverCall = Regex("""\)\s*\.\s*([A-Za-z_$][\w$]*)\s*\(""").findAll(executableMethod).any { call ->
        val close = call.range.first
        var depth = 0
        val open = (close downTo 0).firstOrNull { index ->
            when (executableMethod[index]) {
                ')' -> depth++
                '(' -> depth--
            }
            depth == 0
        } ?: return@any false
        callsTarget(castReceiverType(executableMethod.substring(open, close + 1)), call.groupValues[1], call.range.last)
    }
    // A source-resolved project expression can carry the target behind a getter or method chain:
    // `carriage.getDimensional(dimension).read(tag)`.
    val expressionReceiverCall = methodOffset >= 0 && Regex("""\.\s*([A-Za-z_$][\w$]*)\s*\(""")
        .findAll(executableMethod)
        .any { call ->
            val dot = call.range.first
            val start = findExpressionReceiverStart(executableMethod, dot)
            if (start !in 0 until dot) return@any false
            val canonical = valueTypes.typeOf(methodText.substring(start, dot), methodOffset + dot)
                ?.takeIf { it in projectValueFacts.types }
                ?: return@any false
            callsTarget(canonical.substringAfterLast('.'), call.groupValues[1], call.range.last)
        }
    return namedReceiverCall || referencedTarget || castReceiverCall || expressionReceiverCall
}

internal fun StructuralRefactorPass.isJavaCallableDeclarationAt(source: String, offset: Int, methodName: String): Boolean =
    exactProviderJavaMethodSurfaces(source).any { method ->
        method.name == methodName && method.nameOffset == offset
    }

internal fun StructuralRefactorPass.holderLookupProviderArgumentExpression(expression: String): String {
    val cleaned = expression.trim()
    return if (isHolderLookupProviderParameter(cleaned)) {
        parseJavaParameter(cleaned)?.second ?: cleaned.substringAfterLast(' ')
    } else {
        cleaned
    }
}

internal fun StructuralRefactorPass.isLikelyJavaMethodDeclarationPrefix(prefix: String): Boolean {
    val declaration = prefix
        .lineSequence()
        .filterNot { it.trimStart().startsWith("@") }
        .joinToString(" ")
        .trim()
    if (declaration.isBlank()) return false
    val firstToken = declaration.substringBefore(' ')
    if (firstToken in setOf("return", "throw", "new", "if", "for", "while", "switch", "catch", "else", "do", "try")) {
        return false
    }
    return !Regex("""\breturn\s+new\b|\bnew\s+[A-Za-z_$][\w$]*\s*$""").containsMatchIn(declaration)
}

internal fun StructuralRefactorPass.isPrivateOrPackagePrivateJavaMethodHeader(header: String): Boolean {
    val declaration = header
        .lineSequence()
        .filterNot { it.trimStart().startsWith("@") }
        .joinToString(" ")
    return !Regex("""\b(?:public|protected)\b""").containsMatchIn(declaration)
}

internal fun StructuralRefactorPass.migrateProjectItemStackNbtMethods(
    source: String,
    methods: Set<HolderLookupNbtMethod>,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY,
    entityDataValueTypes: Map<String, String> = emptyMap(),
    javaMethodReturnTypes: Map<String, String> = emptyMap()
): String {
    if (methods.isEmpty()) return source
    val executableCode = maskJavaCommentsAndLiterals(source)
    val typeBlocks = javaTypeBlocks(source, executableCode)
    if (typeBlocks.isEmpty()) return source
    val referencedNames = Regex(
        """(?<![\w$])([A-Za-z_$][\w$]*)\s*\(|::([A-Za-z_$][\w$]*)"""
    ).findAll(executableCode)
        .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .toSet()
    val methodsByName = methods
        .filter { it.methodName in referencedNames }
        .groupBy { it.methodName }
    if (methodsByName.isEmpty()) return source
    val ownerNames = methodsByName.values.flatten().mapTo(linkedSetOf()) { it.owner }
    var result = source
    var indexedTypeSource: String? = source
    var indexedTypeBlocks = typeBlocks

    fun currentTypeBlocks(): List<JavaTypeBlock> {
        if (indexedTypeSource != result) {
            indexedTypeSource = result
            indexedTypeBlocks = javaTypeBlocks(
                result,
                maskJavaCommentsAndLiterals(result)
            )
        }
        return indexedTypeBlocks
    }

    fun ownerAt(offset: Int): String? =
        currentTypeBlocks().lastOrNull { offset in it.bodyStart..it.end }?.name

    val directTypeImports = Regex(
        """(?m)^\s*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;"""
    ).findAll(executableCode)
        .groupBy(
            keySelector = { it.groupValues[1].substringAfterLast('.') },
            valueTransform = { it.groupValues[1] }
        )

    fun normalizeContractType(type: String): String =
        type.replace(Regex("""@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*"""), "")
            .replace(Regex("""\bfinal\s+"""), "")
            .replace(Regex("""\s+"""), "")
            .replace(
                Regex("""(?<![A-Za-z0-9_$])(?:[a-z_$][\w$]*\.)+([A-Z_$][\w$]*)"""),
                "${'$'}1"
            )

    fun contractTypeIdentity(type: String, offset: Int): String {
        val normalized = normalizeContractType(type)
        val arraySuffix = Regex("""(?:\[\])+$""").find(normalized)?.value.orEmpty()
        val elementType = normalized.removeSuffix(arraySuffix)
        if (!Regex("""[A-Za-z_$][\w$]*""").matches(elementType)) return normalized

        val enclosingTypes = currentTypeBlocks()
            .filter { offset in it.bodyStart..it.end }
            .mapTo(linkedSetOf()) { it.referenceName }
        val lexicalCandidates = currentTypeBlocks()
            .filter { block ->
                if (block.name != elementType || !block.referenceName.contains('.')) return@filter false
                block.referenceName.substringBeforeLast('.') in enclosingTypes
            }
            .groupBy { block -> block.referenceName.substringBeforeLast('.').count { it == '.' } }
            .entries
            .maxByOrNull { (depth, _) -> depth }
            ?.value
            .orEmpty()
            .map { it.referenceName }
            .distinct()
        if (lexicalCandidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous lexical Java type '$elementType' at offset $offset: $lexicalCandidates"
            )
        }
        lexicalCandidates.singleOrNull()?.let { return it + arraySuffix }

        val importedTypes = directTypeImports[elementType].orEmpty()
            .map(::normalizeContractType)
            .distinct()
        if (importedTypes.size > 1) {
            throw IllegalStateException(
                "Ambiguous imported Java type '$elementType': $importedTypes"
            )
        }
        return importedTypes.singleOrNull()?.plus(arraySuffix) ?: normalized
    }

    fun contractTypesMatch(actual: String, expected: String): Boolean =
        actual == expected ||
            (
                Regex("""[A-Za-z_$][\w$]*""").matches(actual) &&
                    Regex("""[A-Za-z_$][\w$]*""").matches(expected) &&
                    javaInheritanceIndex.inherits(actual, setOf(expected))
                )

    fun declaresOrInherits(
        owner: String,
        target: HolderLookupNbtMethod
    ): Boolean =
        target.owner == owner ||
            javaInheritanceIndex.inherits(owner, setOf(target.owner))

    fun candidateContracts(
        owner: String?,
        methodName: String,
        argumentCount: Int
    ): List<HolderLookupNbtMethod> =
        if (owner == null) {
            emptyList()
        } else {
            methodsByName[methodName].orEmpty().filter { target ->
                declaresOrInherits(owner, target) &&
                    (
                        target.parameterCount == null ||
                            target.parameterCount == argumentCount
                        )
            }
        }

    fun mostSpecificContracts(
        owner: String?,
        contracts: List<HolderLookupNbtMethod>
    ): List<HolderLookupNbtMethod> {
        if (owner == null || contracts.size <= 1) return contracts
        val directlyDeclared = contracts.filter { it.owner == owner }
        if (directlyDeclared.isNotEmpty()) return directlyDeclared
        return contracts.filter { target ->
            contracts.none { other ->
                other !== target &&
                    javaInheritanceIndex.inherits(other.owner, setOf(target.owner))
            }
        }
    }

    fun declarationContract(
        owner: String?,
        methodName: String,
        parameters: List<String>,
        offset: Int
    ): HolderLookupNbtMethod? {
        val candidates = candidateContracts(owner, methodName, parameters.size)
        if (candidates.isEmpty()) return null
        val declaredTypes = parameters.map { parameter ->
            parseJavaParameter(parameter)?.first
                ?.let { contractTypeIdentity(it, offset) }
                ?: return null
        }
        val typed = candidates.filter { it.legacyParameterTypes != null }
        val exact = mostSpecificContracts(owner, typed.filter { target ->
            target.legacyParameterTypes
                ?.map { contractTypeIdentity(it, offset) } == declaredTypes
        })
        if (exact.size > 1) {
            throw IllegalStateException(
                "Ambiguous typed HolderLookup.Provider declaration contract for " +
                    "$owner.$methodName$declaredTypes"
            )
        }
        exact.singleOrNull()?.let { return it }
        if (typed.isNotEmpty()) return null
        val providerIndexes = candidates.map { target ->
            target.providerParameterIndex ?: parameters.size
        }.distinct()
        if (providerIndexes.size != 1) {
            throw IllegalStateException(
                "Conflicting HolderLookup.Provider parameter positions for " +
                    "$owner.$methodName/${parameters.size}: $providerIndexes"
            )
        }
        return candidates.first()
    }

    var sourceValueTypesSource: String? = null
    var sourceValueTypes: SourceValueTypes? = null
    fun argumentType(argument: String, offset: Int): String? {
        argumentDeclaredType(
            maskJavaCommentsAndLiterals(result),
            argument,
            offset
        )?.let { return it }
        if (sourceValueTypesSource != result) {
            sourceValueTypesSource = result
            sourceValueTypes = SourceValueTypes(this, result)
        }
        return sourceValueTypes?.typeOf(argument, offset)
    }

    fun callContract(
        owner: String?,
        methodName: String,
        arguments: List<String>,
        offset: Int
    ): HolderLookupNbtMethod? {
        val candidates = candidateContracts(owner, methodName, arguments.size)
        if (candidates.isEmpty()) return null
        val typed = candidates.filter { it.legacyParameterTypes != null }
        if (typed.isNotEmpty()) {
            val resolvedTypes = arguments.map { argument ->
                argumentType(argument.trim(), offset)
                    ?.let { contractTypeIdentity(it, offset) }
            }
            val matchStates = typed.associateWith { target ->
                val expected = target.legacyParameterTypes.orEmpty()
                    .map { contractTypeIdentity(it, offset) }
                expected.indices.map { index ->
                    val actual = resolvedTypes[index]
                    when {
                        actual != null -> contractTypesMatch(actual, expected[index])
                        index == 0 &&
                            expected[index].substringAfterLast('.') == "CompoundTag" &&
                            looksLikeTagArgument(arguments[index]) -> true
                        else -> null
                    }
                }
            }
            val exact = mostSpecificContracts(owner, typed.filter { target ->
                matchStates.getValue(target).all { it == true }
            })
            if (exact.size > 1) {
                throw IllegalStateException(
                    "Ambiguous typed HolderLookup.Provider call contract for " +
                        "$owner.$methodName/${arguments.size}"
                )
            }
            exact.singleOrNull()?.let { return it }
            val unambiguous = typed.singleOrNull()
            if (unambiguous != null &&
                !unambiguous.requiresTypedCallResolution &&
                matchStates.getValue(unambiguous).none { it == false }
            ) {
                return unambiguous
            }
            return null
        }
        val providerIndexes = candidates.map { target ->
            target.providerParameterIndex ?: arguments.size
        }.distinct()
        if (providerIndexes.size != 1) {
            throw IllegalStateException(
                "Conflicting HolderLookup.Provider parameter positions for " +
                    "$owner.$methodName/${arguments.size}: $providerIndexes"
            )
        }
        return candidates.first()
    }

    var changedSignature = false
    val signaturePattern = Regex(
        """(?m)^([ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|static|final|synchronized)\s+)*[A-Za-z_$][\w$<>\[\].?,\s]+\s+([A-Za-z_$][\w$]*)\s*)\(([^()]*)\)"""
    )
    result = replaceExecutableRegex(result, signaturePattern) { match ->
        if (!isLikelyJavaMethodDeclarationPrefix(match.groupValues[1])) {
            return@replaceExecutableRegex match.value
        }
        val methodName = match.groupValues[2]
        if (!methodsByName.containsKey(methodName) || match.groupValues[3].contains("HolderLookup.Provider")) {
            return@replaceExecutableRegex match.value
        }
        val owner = ownerAt(match.range.first)
        val params = match.groupValues[3].trim().let { parameterText ->
            if (parameterText.isBlank()) emptyList() else splitTopLevelJavaArgs(parameterText)
        }
        val contract = declarationContract(owner, methodName, params, match.range.last)
        if (contract == null) {
            return@replaceExecutableRegex match.value
        }
        if (registryAccessFromParameters(params, javaInheritanceIndex) != null) {
            return@replaceExecutableRegex match.value
        }
        changedSignature = true
        val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "registries")
        val migratedParams = insertHolderLookupProviderArgument(
            params,
            "HolderLookup.Provider $providerName",
            contract
        ).joinToString(", ")
        "${match.groupValues[1]}($migratedParams)"
    }

    fun registryAccessAt(offset: Int): String? =
        registryAccessExpressionAt(result, offset, javaInheritanceIndex)

    val ownerAlternation = ownerNames.joinToString("|") { Regex.escape(it) }
    val variableTypes = if (ownerAlternation.isBlank()) {
        emptyMap()
    } else {
        Regex("""\b($ownerAlternation)\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(maskJavaCommentsAndLiterals(result))
            .associate { it.groupValues[2] to it.groupValues[1] } +
            Regex(
                """\b([A-Z][\w$]*)(?:<[^<>;=(){}]*>)?\s+([A-Za-z_$][\w$]*)\s*(?=[=;,):])"""
            ).findAll(maskJavaCommentsAndLiterals(result))
                .filter {
                    it.groupValues[1] !in ownerNames &&
                        javaInheritanceIndex.inherits(
                            it.groupValues[1],
                            ownerNames
                        )
                }
                .associate { it.groupValues[2] to it.groupValues[1] }
    }
    val getterTypes = if (ownerAlternation.isBlank()) emptyMap() else {
        Regex("""(?m)\b(?:public|protected|private)\s+(?:[A-Za-z_$][\w$]*\.)*($ownerAlternation)\s+([A-Za-z_$][\w$]*)\s*\(\s*\)""")
            .findAll(maskJavaCommentsAndLiterals(result))
            .associate { "${it.groupValues[2]}()" to it.groupValues[1] }
    }
    val lazyOptionalValueTypes = if (ownerAlternation.isBlank()) emptyMap() else {
        Regex("""\b(?:[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.)?LazyOptional\s*<\s*($ownerAlternation)\s*>\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(maskJavaCommentsAndLiterals(result))
            .associate { it.groupValues[2] to it.groupValues[1] }
    }
    val fieldTypesByName = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    ).parse(result).let { parsed ->
        val compilationUnit = parsed.result.orElseThrow {
            IllegalStateException(
                "Cannot parse provider-aware NBT receiver types: ${parsed.problems.joinToString()}"
            )
        }
        compilationUnit.findAll(FieldDeclaration::class.java)
            .flatMap { it.variables }
            .groupBy({ it.nameAsString }, { it.typeAsString.substringAfterLast('.') })
            .mapValues { (_, types) -> types.distinct() }
    }
    val mapEntryValueTypes = if (ownerAlternation.isBlank()) emptyMap() else {
        Regex("""\b(?:[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.)?Map\.Entry\s*<\s*[^,<>]+(?:<[^<>]*>)?\s*,\s*($ownerAlternation)\s*>\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(maskJavaCommentsAndLiterals(result))
            .associate { it.groupValues[2] to it.groupValues[1] }
    }

    fun lazyOptionalUnwrappedOwner(receiver: String): String? {
        val match = Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\s*\.\s*resolve\s*\(\s*\)\s*\.\s*(?:get|orElseThrow)\s*\(\s*\)$""")
            .find(receiver)
            ?: return null
        return lazyOptionalValueTypes[match.groupValues[1]]
    }

    fun mapEntryValueOwner(receiver: String): String? {
        val match = Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\s*\.\s*getValue\s*\(\s*\)$""")
            .find(receiver)
            ?: return null
        return mapEntryValueTypes[match.groupValues[1]]
    }

    fun targetOwner(type: String?): String? {
        val declared = type
            ?.trim()
            ?.takeUnless { it.endsWith("[]") || it.endsWith("...") }
            ?: return null
        val simple = simpleJavaTypeName(declared)
        return simple.takeIf {
            it in ownerNames ||
                javaInheritanceIndex.inherits(it, ownerNames)
        }
    }

    fun genericBoundOwner(type: String, offset: Int): String? {
        val typeVariable = type.trim()
            .takeIf { Regex("""^[A-Za-z_$][\w$]*$""").matches(it) }
            ?: return null
        val bound = Regex(
            """(?:^|[<,])\s*${Regex.escape(typeVariable)}\s+extends\s+($ownerAlternation)(?=\s*(?:[,&>]|$))"""
        )
        val scopes = buildList {
            javaMethodRangesIncludingDefault(result)
                .firstOrNull { offset in it.range }
                ?.let { method ->
                    val masked = maskJavaCommentsAndLiterals(result)
                    val openBrace = masked.indexOf('{', method.range.first)
                    if (openBrace in method.range) {
                        add(masked.substring(method.range.first, openBrace))
                    }
                }
            currentTypeBlocks()
                .lastOrNull { offset in it.bodyStart..it.end }
                ?.let { type ->
                    add(
                        maskJavaCommentsAndLiterals(result)
                            .substring(type.declarationStart, type.bodyStart)
                    )
                }
        }
        return scopes.firstNotNullOfOrNull { scope ->
            bound.find(scope)?.groupValues?.get(1)
        }
    }

    fun receiverOwner(receiver: String, offset: Int): String? {
        val normalized = receiver.trim()
        if (normalized == "this") return ownerAt(offset)
        if (normalized == "super") {
            return ownerAt(offset)?.let { javaInheritanceIndex.directSuperOf(it) }
        }
        if (normalized.endsWith(".super")) {
            targetOwner(
                normalized.removeSuffix(".super").substringAfterLast('.')
            )?.let { return it }
        }
        entityDataLambdaReceiverOwner(
            result,
            offset,
            normalized,
            entityDataValueTypes,
            javaInheritanceIndex
        )?.let { return it }
        staticFactoryReceiverOwner(normalized, methodsByName.values.flatten().map { it.owner }.toSet(), javaMethodReturnTypes)?.let { return it }
        if (normalized in ownerNames) return normalized
        if (Regex("""^[A-Za-z_$][\w$]*$""").matches(normalized)) {
            com.modporter.core.transforms.util.JavaScopedDeclarations.visibleAt(
                maskJavaCommentsAndLiterals(result),
                normalized,
                offset
            )?.let { declaration ->
                targetOwner(declaration.type)?.let { return it }
                genericBoundOwner(declaration.type, offset)?.let { return it }
                return null
            }
        }
        lazyOptionalUnwrappedOwner(normalized)?.let { return it }
        mapEntryValueOwner(normalized)?.let { return it }
        getterTypes[normalized]?.let { return it }
        getterTypes[normalized.substringAfterLast('.')]?.let { return it }
        val explicitThisField = Regex("""^this\.([A-Za-z_$][\w$]*)$""").find(normalized)
        val simpleReceiver = when {
            explicitThisField != null -> explicitThisField.groupValues[1]
            Regex("""^[A-Za-z_$][\w$]*$""").matches(normalized) -> normalized
            else -> null
        }
        if (simpleReceiver != null && explicitThisField == null) {
            val matchingParameters = currentMethodParametersBeforeOffset(result, offset)
                .mapNotNull(::parseJavaParameter)
                .filter { (_, name) -> name == simpleReceiver }
            if (matchingParameters.isNotEmpty()) {
                val types = matchingParameters.map { it.first }.distinct()
                if (types.size != 1) {
                    throw IllegalStateException(
                        "Ambiguous parameter types for provider-aware NBT receiver '$simpleReceiver': $types"
                    )
                }
                return targetOwner(types.single())
            }
            val method = javaMethodRangesIncludingDefault(result)
                .filter { offset in it.range }
                .minByOrNull { it.range.last - it.range.first }
            if (method != null) {
                val visiblePrefix = result.substring(
                    method.range.first,
                    offset.coerceAtMost(method.range.last + 1)
                )
                val localTypes = javaLocalVariableTypes(visiblePrefix)
                if (simpleReceiver in localTypes) {
                    return targetOwner(localTypes.getValue(simpleReceiver))
                }
            }
        }
        if (simpleReceiver != null) {
            val fieldTypes = fieldTypesByName[simpleReceiver].orEmpty()
            if (fieldTypes.size > 1) {
                throw IllegalStateException(
                    "Ambiguous field types for provider-aware NBT receiver " +
                        "'$simpleReceiver': $fieldTypes"
                )
            }
            fieldTypes.singleOrNull()?.let { fieldType ->
                targetOwner(fieldType)?.let { return it }
            }
            variableTypes[simpleReceiver]?.let { variableType ->
                targetOwner(variableType)?.let { return it }
            }
        }
        arrayElementReceiverOwner(
            normalized,
            maskJavaCommentsAndLiterals(result),
            ownerAlternation
        )?.let { return it }
        castReceiverType(normalized)?.let { castType ->
            targetOwner(castType)?.let { return it }
        }
        argumentType(normalized, offset)?.let { inferred ->
            targetOwner(inferred)?.let { return it }
        }
        return null
    }

    for (methodName in methodsByName.keys) {
        result = rewriteExecutableJavaCallWithOffset(result, methodName) { receiver, args, offset ->
            val providerNames = holderLookupProviderNamesAt(result, offset)
            if (providerNames.size > 1) {
                throw IllegalStateException(
                    "${receiver.trim()}.$methodName requires an unambiguous HolderLookup.Provider; " +
                        "found ${providerNames.size} in the enclosing method"
                )
            }
            if (args.any { isHolderLookupProviderExpression(it, providerNames) }) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val owner = receiverOwner(receiver, offset)
            val contract = callContract(owner, methodName, args, offset)
                ?: return@rewriteExecutableJavaCallWithOffset null
            val registryAccess = providerNames.singleOrNull()
                ?: entityDataLambdaRegistryAccess(
                    result,
                    offset,
                    receiver.trim(),
                    entityDataValueTypes,
                    javaInheritanceIndex
                )
                ?: registryAccessAt(offset)
                ?: return@rewriteExecutableJavaCallWithOffset null
            val migratedArgs = insertHolderLookupProviderArgument(
                args,
                holderLookupProviderArgumentExpression(registryAccess),
                contract
            )
            "$receiver.$methodName(${migratedArgs.joinToString(", ")})"
        }
        result = rewriteExecutableJavaInvocationArgumentsWithOffset(result, methodName) { args, offset ->
            val executable = maskJavaCommentsAndLiterals(result)
            if (offset > 0 && executable[offset - 1] == '.') return@rewriteExecutableJavaInvocationArgumentsWithOffset null
            if (isJavaCallableDeclarationAt(result, offset, methodName)) return@rewriteExecutableJavaInvocationArgumentsWithOffset null
            val providerNames = holderLookupProviderNamesAt(result, offset)
            if (providerNames.size > 1) {
                throw IllegalStateException(
                    "$methodName requires an unambiguous HolderLookup.Provider; " +
                        "found ${providerNames.size} in the enclosing method"
                )
            }
            if (args.any { isHolderLookupProviderExpression(it, providerNames) }) {
                return@rewriteExecutableJavaInvocationArgumentsWithOffset null
            }
            val owner = ownerAt(offset)
            val contract = callContract(owner, methodName, args, offset)
                ?: return@rewriteExecutableJavaInvocationArgumentsWithOffset null
            val registryAccess = providerNames.singleOrNull()
                ?: registryAccessAt(offset)
                ?: return@rewriteExecutableJavaInvocationArgumentsWithOffset null
            insertHolderLookupProviderArgument(
                args,
                holderLookupProviderArgumentExpression(registryAccess),
                contract
            )
        }
    }

    for (methodName in methodsByName.keys) {
        val reference = Regex(
            """(?<![\w$])((?:this|[A-Za-z_$][\w$]*))::${Regex.escape(methodName)}(?![\w$(])"""
        )

        fun referencedTarget(
            receiver: String,
            offset: Int
        ): HolderLookupNbtMethod? {
            val owner = receiverOwner(receiver, offset) ?: return null
            var targets = (
                candidateContracts(owner, methodName, 0) +
                candidateContracts(owner, methodName, 1)
                ).distinct()
            val masked = maskJavaCommentsAndLiterals(result)
            val compoundReaderCallback = listOf(
                "readCompoundList(",
                "iterateCompoundList("
            ).any { token ->
                var start = masked.lastIndexOf(token, offset)
                var contains = false
                while (start >= 0 && !contains) {
                    val closeParen = findMatchingParen(
                        masked,
                        start + token.length - 1
                    )
                    contains = closeParen > offset
                    start = if (start == 0) {
                        -1
                    } else {
                        masked.lastIndexOf(token, start - 1)
                    }
                }
                contains
            }
            val compoundWriterCallback = run {
                val token = "writeCompoundList("
                var start = masked.lastIndexOf(token, offset)
                var contains = false
                while (start >= 0 && !contains) {
                    val closeParen = findMatchingParen(
                        masked,
                        start + token.length - 1
                    )
                    contains = closeParen > offset
                    start = if (start == 0) {
                        -1
                    } else {
                        masked.lastIndexOf(token, start - 1)
                    }
                }
                contains
            }
            if (compoundReaderCallback) {
                targets = targets.filter { target ->
                    target.legacyParameterTypes
                        ?.firstOrNull()
                        ?.substringAfterLast('.') == "CompoundTag"
                }
            } else if (compoundWriterCallback) {
                targets = targets.filter { target ->
                    target.legacyParameterTypes?.isEmpty() == true
                }
            }
            if ((!compoundReaderCallback && !compoundWriterCallback &&
                    targets.any { it.requiresTypedCallResolution }) ||
                targets.map { it.parameterCount }.distinct().size != 1
            ) {
                return null
            }
            return targets.singleOrNull()
        }

        fun insideComputeIfAbsent(masked: String, offset: Int): Boolean {
            var start = masked.lastIndexOf("computeIfAbsent(", offset)
            while (start >= 0) {
                val closeParen = findMatchingParen(
                    masked,
                    start + "computeIfAbsent".length
                )
                if (closeParen > offset) return true
                start = if (start == 0) {
                    -1
                } else {
                    masked.lastIndexOf("computeIfAbsent(", start - 1)
                }
            }
            return false
        }

        while (true) {
            val masked = maskJavaCommentsAndLiterals(result)
            val match = reference.findAll(masked).firstOrNull {
                referencedTarget(it.groupValues[1], it.range.first) != null &&
                    !insideComputeIfAbsent(masked, it.range.first)
            } ?: break
            val receiver = match.groupValues[1]
            val target = referencedTarget(receiver, match.range.first)!!
            val providerNames = holderLookupProviderNamesAt(
                result,
                match.range.first
            )
            val registryAccess = providerNames.singleOrNull()
                ?: registryAccessAt(match.range.first)
                ?: break
            val provider = holderLookupProviderArgumentExpression(registryAccess)
            val value = uniqueLocalNameInScope(
                enclosingMethodText(result, match.range.first) ?: result,
                "value"
            )
            val isStatic =
                (target.owner to methodName) in staticHolderLookupNbtMethods
            val parameterCount = target.parameterCount ?: break
            val lambda = when {
                isStatic && parameterCount == 1 -> {
                    val arguments = insertHolderLookupProviderArgument(
                        listOf(value),
                        provider,
                        target
                    )
                    "$value -> $receiver.$methodName(${arguments.joinToString(", ")})"
                }

                !isStatic && parameterCount == 0 && receiver == "this" -> {
                    val arguments = insertHolderLookupProviderArgument(
                        emptyList(),
                        provider,
                        target
                    )
                    "() -> this.$methodName(${arguments.joinToString(", ")})"
                }

                !isStatic && parameterCount == 0 -> {
                    val arguments = insertHolderLookupProviderArgument(
                        emptyList(),
                        provider,
                        target
                    )
                    "$value -> $value.$methodName(${arguments.joinToString(", ")})"
                }

                !isStatic && parameterCount == 1 && receiver == "this" -> {
                    val arguments = insertHolderLookupProviderArgument(
                        listOf(value),
                        provider,
                        target
                    )
                    "$value -> this.$methodName(${arguments.joinToString(", ")})"
                }

                else -> null
            } ?: break
            result = result.replaceRange(match.range, lambda)
        }
    }

    if (changedSignature && result.contains("HolderLookup.Provider")) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    return result
}

internal fun StructuralRefactorPass.capabilityLambdaReceiverOwner(
    source: String,
    offset: Int,
    receiver: String,
    capabilityTypes: Map<String, String>
): String? {
    if (capabilityTypes.isEmpty()) return null
    val executableCode = maskJavaCommentsAndLiterals(source)
    val token = ".ifPresent("
    var cursor = 0
    while (true) {
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) return null
        val openParen = tokenIndex + ".ifPresent".length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        if (offset !in openParen..closeParen) {
            cursor = closeParen + 1
            continue
        }
        val lambdaText = source.substring(openParen + 1, closeParen)
        val lambdaPattern = Regex("""^\s*\(?\s*${Regex.escape(receiver)}\s*\)?\s*->""")
        if (!lambdaPattern.containsMatchIn(maskJavaCommentsAndLiterals(lambdaText))) {
            cursor = tokenIndex + token.length
            continue
        }
        val receiverStart = findExpressionReceiverStart(executableCode, tokenIndex)
        if (receiverStart < 0 || receiverStart >= tokenIndex) {
            cursor = closeParen + 1
            continue
        }
        val ifPresentReceiver = source.substring(receiverStart, tokenIndex)
        val capability = capabilityKeyFromGetCapabilityExpression(ifPresentReceiver) ?: return null
        return capabilityTypes[capability]
            ?: capabilityTypes[capability.substringAfterLast('.')]
            ?: capabilityTypes[capability.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }]
    }
}

internal data class EntityDataLambdaEvidence(
    val valueType: String,
    val registryAccessExpression: String
)

internal fun StructuralRefactorPass.entityDataLambdaReceiverOwner(
    source: String,
    offset: Int,
    receiver: String,
    entityDataValueTypes: Map<String, String>,
    javaInheritanceIndex: JavaInheritanceIndex
): String? = entityDataLambdaEvidence(
    source,
    offset,
    receiver,
    entityDataValueTypes,
    javaInheritanceIndex
)?.valueType

internal fun StructuralRefactorPass.entityDataLambdaRegistryAccess(
    source: String,
    offset: Int,
    receiver: String,
    entityDataValueTypes: Map<String, String>,
    javaInheritanceIndex: JavaInheritanceIndex
): String? = entityDataLambdaEvidence(
    source,
    offset,
    receiver,
    entityDataValueTypes,
    javaInheritanceIndex
)?.registryAccessExpression

internal fun StructuralRefactorPass.entityDataLambdaEvidence(
    source: String,
    offset: Int,
    receiver: String,
    entityDataValueTypes: Map<String, String>,
    javaInheritanceIndex: JavaInheritanceIndex
): EntityDataLambdaEvidence? {
    val capabilityOwner = capabilityLambdaReceiverOwner(
        source,
        offset,
        receiver,
        entityDataValueTypes
    )
    val capabilityProvider = capabilityLambdaRegistryAccess(source, offset, receiver)
    if (capabilityOwner != null && capabilityProvider != null) {
        return EntityDataLambdaEvidence(capabilityOwner, capabilityProvider)
    }
    return attachmentLambdaEvidence(
        source,
        offset,
        receiver,
        entityDataValueTypes,
        javaInheritanceIndex
    )
}

internal fun StructuralRefactorPass.attachmentLambdaEvidence(
    source: String,
    offset: Int,
    receiver: String,
    attachmentValueTypes: Map<String, String>,
    javaInheritanceIndex: JavaInheritanceIndex
): EntityDataLambdaEvidence? {
    if (attachmentValueTypes.isEmpty()) return null
    val executableCode = maskJavaCommentsAndLiterals(source)
    val token = ".ifPresent("
    var cursor = 0
    while (true) {
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) return null
        val openParen = tokenIndex + ".ifPresent".length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        if (offset !in openParen..closeParen) {
            cursor = closeParen + 1
            continue
        }
        val lambdaText = source.substring(openParen + 1, closeParen)
        val lambdaPattern = Regex("""^\s*\(?\s*${Regex.escape(receiver)}\s*\)?\s*->""")
        if (!lambdaPattern.containsMatchIn(maskJavaCommentsAndLiterals(lambdaText))) {
            cursor = tokenIndex + token.length
            continue
        }
        val ifPresentReceiverStart = findExpressionReceiverStart(executableCode, tokenIndex)
        if (ifPresentReceiverStart < 0 || ifPresentReceiverStart >= tokenIndex) return null
        val getDataTokens = mutableListOf<Int>()
        var dataCursor = ifPresentReceiverStart
        while (true) {
            val dataToken = executableCode.indexOf(".getData(", dataCursor)
            if (dataToken < 0 || dataToken >= tokenIndex) break
            getDataTokens += dataToken
            dataCursor = dataToken + ".getData(".length
        }
        if (getDataTokens.size != 1) return null
        val dataToken = getDataTokens.single()
        val dataOpenParen = dataToken + ".getData".length
        val dataCloseParen = findMatchingParen(executableCode, dataOpenParen)
        if (dataCloseParen < 0 || dataCloseParen >= tokenIndex) return null
        val dataArgs = splitTopLevelJavaArgs(
            source.substring(dataOpenParen + 1, dataCloseParen)
        )
        if (dataArgs.size != 1) return null
        val attachmentKey = dataArgs.single()
            .trim()
            .replace(Regex("""\s+"""), "")
            .removeSuffix(".get()")
        val valueType = attachmentValueTypes[attachmentKey] ?: return null
        val entityReceiverStart = findExpressionReceiverStart(executableCode, dataToken)
        if (entityReceiverStart < 0 || entityReceiverStart >= dataToken) return null
        val entityReceiver = source.substring(entityReceiverStart, dataToken).trim()
        if (!Regex("""^(?:this|[A-Za-z_$][\w$]*)$""").matches(entityReceiver)) return null
        if (!isEntityCapabilityReceiver(
                executableCode,
                dataToken,
                entityReceiver,
                javaInheritanceIndex
            )
        ) {
            return null
        }
        val registryAccess = if (entityReceiver == "this") {
            "this.registryAccess()"
        } else {
            "$entityReceiver.registryAccess()"
        }
        return EntityDataLambdaEvidence(valueType, registryAccess)
    }
}

internal fun StructuralRefactorPass.capabilityLambdaRegistryAccess(
    source: String,
    offset: Int,
    receiver: String
): String? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val token = ".ifPresent("
    var cursor = 0
    while (true) {
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) return null
        val openParen = tokenIndex + ".ifPresent".length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        if (offset !in openParen..closeParen) {
            cursor = closeParen + 1
            continue
        }
        val lambdaText = source.substring(openParen + 1, closeParen)
        val lambdaPattern = Regex("""^\s*\(?\s*${Regex.escape(receiver)}\s*\)?\s*->""")
        if (!lambdaPattern.containsMatchIn(maskJavaCommentsAndLiterals(lambdaText))) {
            cursor = tokenIndex + token.length
            continue
        }
        val receiverStart = findExpressionReceiverStart(executableCode, tokenIndex)
        if (receiverStart < 0 || receiverStart >= tokenIndex) {
            cursor = tokenIndex + token.length
            continue
        }
        val ifPresentReceiver = source.substring(receiverStart, tokenIndex)
        return registryAccessFromGetCapabilityExpression(ifPresentReceiver)
    }
}

internal fun StructuralRefactorPass.registryAccessFromGetCapabilityExpression(expression: String): String? {
    val executableExpression = maskJavaCommentsAndLiterals(expression)
    val token = ".getCapability("
    val tokenIndex = executableExpression.lastIndexOf(token)
    val receiver = if (tokenIndex >= 0) {
        val receiverStart = findExpressionReceiverStart(executableExpression, tokenIndex)
        if (receiverStart < 0 || receiverStart >= tokenIndex) return null
        expression.substring(receiverStart, tokenIndex).trim()
    } else {
        val implicitToken = "getCapability("
        if (!executableExpression.contains(implicitToken)) return null
        "this"
    }
    return if (receiver == "this") {
        "this.registryAccess()"
    } else {
        "$receiver.registryAccess()"
    }
}

internal fun StructuralRefactorPass.capabilityKeyFromGetCapabilityExpression(expression: String): String? {
    val executableExpression = maskJavaCommentsAndLiterals(expression)
    val token = ".getCapability("
    val tokenIndex = executableExpression.lastIndexOf(token)
    val openParen = if (tokenIndex >= 0) {
        tokenIndex + ".getCapability".length
    } else {
        val implicitToken = "getCapability("
        val implicitIndex = executableExpression.lastIndexOf(implicitToken)
        if (implicitIndex < 0) return null
        implicitIndex + "getCapability".length
    }
    val closeParen = findMatchingParen(executableExpression, openParen)
    if (closeParen < 0) return null
    val args = splitTopLevelJavaArgs(expression.substring(openParen + 1, closeParen))
    return args.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
}

internal fun StructuralRefactorPass.staticFactoryReceiverOwner(
    receiverExpression: String,
    ownerNames: Set<String>,
    javaMethodReturnTypes: Map<String, String>
): String? {
    if (ownerNames.isEmpty() || javaMethodReturnTypes.isEmpty()) return null
    val executableExpression = maskJavaCommentsAndLiterals(receiverExpression)
    val match = Regex("""^\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.([A-Za-z_$][\w$]*)\s*\(""")
        .find(executableExpression)
        ?: return null
    val ownerExpression = match.groupValues[1]
    val methodName = match.groupValues[2]
    val ownerSimpleName = ownerExpression.substringAfterLast('.')
    val returnedType = javaMethodReturnTypes["$ownerSimpleName.$methodName"]
        ?: javaMethodReturnTypes["$ownerExpression.$methodName"]
        ?: return null
    return returnedType.takeIf { it in ownerNames }
}

internal fun StructuralRefactorPass.migrateEntityEffectColorParticles(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ParticleTypes.ENTITY_EFFECT")) return source
    var result = source
    var changed = false
    for (methodName in listOf("addParticle", "addAlwaysVisibleParticle")) {
        result = rewriteExecutableJavaCall(result, methodName) { receiver, args ->
            if (args.size != 7 || args[0].trim() != "ParticleTypes.ENTITY_EFFECT") return@rewriteExecutableJavaCall null
            changed = true
            val particle = "ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, (float)(${args[4].trim()}), (float)(${args[5].trim()}), (float)(${args[6].trim()}))"
            "$receiver.$methodName($particle, ${args[1].trim()}, ${args[2].trim()}, ${args[3].trim()}, 0.0D, 0.0D, 0.0D)"
        }
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.core.particles.ColorParticleOption") else source
}

internal fun StructuralRefactorPass.migrateMobEffectInstanceMobEffectVariables(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new MobEffectInstance(") || !executableCode.contains("MobEffect")) return source
    val mobEffectVariables = Regex("""\bMobEffect\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    if (mobEffectVariables.isEmpty()) return source
    var result = source
    var changed = false
    result = replaceExecutableRegex(
        result,
        Regex("""new\s+MobEffectInstance\s*\(\s*([A-Za-z_$][\w$]*)\s*,""")
    ) { match ->
            val variable = match.groupValues[1]
            if (variable !in mobEffectVariables) {
                match.value
            } else {
                changed = true
                "new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder($variable),"
            }
        }
    return if (changed) addImportIfMissing(result, "net.minecraft.core.registries.BuiltInRegistries") else source
}

internal fun StructuralRefactorPass.migrateLegacyMobEffectHolderCategoryCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getEffect().getCategory()")) return source
    return replaceExecutableRegex(
        source,
        Regex("""\.getEffect\(\)\.getCategory\(\)""")
    ) { ".getEffect().value().getCategory()" }
}

internal fun StructuralRefactorPass.migrateItemStackTagConstructors(source: String): String {
    val executableCode = maskJavaComments(source)
    if (!executableCode.contains("new ItemStack(") || !executableCode.contains("CompoundTag")) return source
    val damageTags = Regex("""\bCompoundTag\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+CompoundTag\s*\(\s*\)\s*;""")
        .findAll(executableCode)
        .mapNotNull { declaration ->
            val tagName = declaration.groupValues[1]
            val damageWrites = Regex("""\b${Regex.escape(tagName)}\.putInt\s*\(\s*"Damage"\s*,\s*([^;\r\n]+?)\s*\)\s*;""")
                .findAll(executableCode)
                .toList()
            if (damageWrites.size != 1) return@mapNotNull null
            val otherWrites = Regex("""\b${Regex.escape(tagName)}\.(?!putInt\s*\(\s*"Damage")""")
                .containsMatchIn(executableCode)
            if (otherWrites) return@mapNotNull null
            tagName to source.substring(damageWrites.single().groups[1]!!.range).trim()
        }
        .toMap()
    if (damageTags.isEmpty()) return source
    var result = source
    var cursor = 0
    var changed = false
    while (true) {
        val currentExecutable = maskJavaComments(result)
        val tokenIndex = currentExecutable.indexOf("new ItemStack(", cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + "new ItemStack".length
        val closeParen = findMatchingParen(currentExecutable, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + "new ItemStack(".length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 3) {
            cursor = closeParen + 1
            continue
        }
        val tagName = args[2].trim()
        val damage = damageTags[tagName]
        if (damage == null) {
            cursor = closeParen + 1
            continue
        }
        val replacement = "net.minecraft.Util.make(new ItemStack(${args[0].trim()}, ${args[1].trim()}), stack -> stack.setDamageValue($damage))"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
        changed = true
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateLegacyClientGuiUtilityApis(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getTooltipLines(") &&
        !executableCode.contains("moveCursorToStart()") &&
        !executableCode.contains(".tick()") &&
        !executableCode.contains("renderBackground(") &&
        !executableCode.contains(".blitRepeating(") &&
        !executableCode.contains("ClientHooks.getFluidSprites(")
    ) return source
    var result = source
    var needsItemImport = false

    result = migrateRenderBackgroundCallArguments(result)
    result = migrateGuiGraphicsBlitRepeatingSource(result)
    result = migrateClientHooksFluidSpritesSource(result)

    result = rewriteExecutableJavaCallWithOffset(result, "getTooltipLines") { receiver, args, offset ->
        if (args.size != 2) return@rewriteExecutableJavaCallWithOffset null
        if (args[0].contains("TooltipContext")) return@rewriteExecutableJavaCallWithOffset null
        val player = args[0].trim()
        val tooltipFlag = args[1].trim()
        // `mc.player` of a Minecraft declared where the call stands: that client's level
        val clientLevel = Regex("""^([A-Za-z_$][\w$]*)\.player$""").find(player)?.groupValues?.get(1)?.takeIf { client ->
            com.modporter.core.transforms.util.JavaScopedDeclarations.visibleAt(maskJavaCommentsAndLiterals(result), client, offset)?.type
                ?.substringAfterLast('.') == "Minecraft"
        }?.let { "Item.TooltipContext.of($it.level)" }
        val context = clientLevel ?: tooltipContextExpressionForPlayer(player) ?: return@rewriteExecutableJavaCallWithOffset null
        needsItemImport = true
        "$receiver.getTooltipLines($context, $player, $tooltipFlag)"
    }

    val editBoxReceivers = collectEditBoxReceiverNames(result)
    if (editBoxReceivers.isNotEmpty()) {
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.tick\s*\(\s*\)\s*;\s*$""")
        ) { match ->
            val receiver = match.groupValues[2].substringAfterLast('.')
            if (receiver in editBoxReceivers) "" else match.value
        }
    }

    return if (needsItemImport) addImportIfMissing(result, "net.minecraft.world.item.Item") else result
}

internal fun StructuralRefactorPass.migrateLegacyOptionsScreenMixinInitLocalCapture(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!Regex("""@Mixin\s*\(\s*(?:net\.minecraft\.client\.gui\.screens\.options\.)?OptionsScreen\.class\s*\)""")
            .containsMatchIn(executableCode)) return source
    if (!source.contains("@Inject") ||
        !source.contains("method = \"init\"") ||
        !source.contains("GridLayout${'$'}RowHelper;addChild") ||
        !executableCode.contains("GridLayout.RowHelper")
    ) return source

    val edits = mutableListOf<Pair<IntRange, String>>()
    val methods = javaMethodRangesIncludingDefault(source)
    for (method in methods) {
        val injectIndex = source.lastIndexOf("@Inject", method.range.first)
        if (injectIndex < 0) continue
        val previousMethodEnd = source.lastIndexOf('}', method.range.first)
        if (previousMethodEnd > injectIndex) continue

        val annotationText = source.substring(injectIndex, method.range.first)
        if (!annotationText.contains("method = \"init\"") ||
            !annotationText.contains("GridLayout${'$'}RowHelper;addChild")
        ) continue

        val parameters = callableParameters(source, method.range)
            .mapNotNull(::javaParameterTypeAndName)
        val callbackInfo = parameters.firstOrNull { simpleJavaTypeName(it.first) == "CallbackInfo" }
            ?: continue
        val rowHelper = parameters.firstOrNull { it.first.endsWith("GridLayout.RowHelper") || it.first.endsWith(".RowHelper") }
            ?: continue
        val methodText = source.substring(method.range)
        val addChild = Regex("""\b${Regex.escape(rowHelper.second)}\s*\.\s*addChild\s*\(""").find(methodText)
            ?: continue
        val openParen = methodText.indexOf('(', addChild.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(methodText, openParen) else -1
        if (openParen < 0 || closeParen < 0) continue
        val args = splitTopLevelJavaArgs(methodText.substring(openParen + 1, closeParen))
        if (args.size < 3) continue

        val annotationStart = source.lastIndexOf('\n', injectIndex).let { if (it < 0) 0 else it + 1 }
        val indent = source.substring(annotationStart, injectIndex).takeWhile { it == ' ' || it == '\t' }
        val widgetExpression = args[0].trim().trimIndent()
        val widgetBlock = widgetExpression.lines().joinToString("\n") { line ->
            "${indent}            ${line.trimEnd()}"
        }
        val callbackType = callbackInfo.first
        val callbackName = callbackInfo.second
        val replacement = buildString {
            append("${indent}@Inject(\n")
            append("${indent}        method = \"init\",\n")
            append("${indent}        at = @At(\n")
            append("${indent}                value = \"INVOKE\",\n")
            append("${indent}                target = \"Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;visitWidgets(Ljava/util/function/Consumer;)V\"\n")
            append("${indent}        )\n")
            append("${indent})\n")
            append("${indent}private void ${method.name}($callbackType $callbackName) {\n")
            append("${indent}    this.layout.addToContents(\n")
            append(widgetBlock)
            append("\n${indent}    );\n")
            append("${indent}}\n")
        }
        edits += annotationStart..method.range.last to replacement
    }
    if (edits.isEmpty()) return source

    var result = applyStringEdits(source, edits)
    result = ensureOptionsScreenLayoutShadow(result)
    result = addImportIfMissing(result, "net.minecraft.client.gui.layouts.HeaderAndFooterLayout")
    result = addImportIfMissing(result, "org.spongepowered.asm.mixin.Final")
    result = addImportIfMissing(result, "org.spongepowered.asm.mixin.Shadow")

    val withoutGridLayoutImport = removeExecutableImport(result, "net.minecraft.client.gui.layouts.GridLayout")
    if (!Regex("""\bGridLayout\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutGridLayoutImport))) {
        result = withoutGridLayoutImport
    }
    val withoutLocalCaptureImport = removeExecutableImport(result, "org.spongepowered.asm.mixin.injection.callback.LocalCapture")
    if (!Regex("""\bLocalCapture\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutLocalCaptureImport))) {
        result = withoutLocalCaptureImport
    }
    return result
}

internal fun StructuralRefactorPass.ensureOptionsScreenLayoutShadow(source: String): String {
    if (Regex("""\bHeaderAndFooterLayout\s+layout\s*;""").containsMatchIn(source)) return source
    val classMatch = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private)\s+)?(?:abstract\s+)?class\s+[A-Za-z_$][\w$]*[^{]*\{"""
    ).find(source) ?: return source
    val lineStart = source.lastIndexOf('\n', classMatch.range.first).let { if (it < 0) 0 else it + 1 }
    val indent = source.substring(lineStart, classMatch.range.first).takeWhile { it == ' ' || it == '\t' }
    val insertAt = classMatch.range.last + 1
    val insertion = """

${indent}    @Shadow
${indent}    @Final
${indent}    private HeaderAndFooterLayout layout;
""".trimEnd()
    return source.substring(0, insertAt) + insertion + source.substring(insertAt)
}

internal fun StructuralRefactorPass.migrateClientHooksFluidSpritesSource(source: String): String {
    if (!source.contains("ClientHooks.getFluidSprites(")) return source
    var changed = false
    var result = rewriteExecutableJavaCall(source, "getFluidSprites") { receiver, args ->
        if (receiver != "ClientHooks" || args.size != 3) return@rewriteExecutableJavaCall null
        changed = true
        "modporter${'$'}getFluidSprites(${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()})"
    }
    if (!changed) return source
    result = ensureModporterFluidSpritesHelper(result)
    val withoutClientHooks = removeImport(result, "net.neoforged.neoforge.client.ClientHooks")
    result = if (!Regex("""\bClientHooks\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutClientHooks))) {
        withoutClientHooks
    } else {
        result
    }
    return result
}

internal fun StructuralRefactorPass.ensureModporterFluidSpritesHelper(source: String): String {
    val helperName = "modporter${'$'}getFluidSprites"
    if (Regex("""\bprivate\s+static\s+net\.minecraft\.client\.renderer\.texture\.TextureAtlasSprite\[\]\s+${Regex.escape(helperName)}\s*\(""")
            .containsMatchIn(source)) {
        return source
    }
    val insertAt = source.lastIndexOf('}')
    if (insertAt < 0) return source
    val helper = """

    private static net.minecraft.client.renderer.texture.TextureAtlasSprite[] modporter${'$'}getFluidSprites(net.minecraft.world.level.BlockAndTintGetter level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.material.FluidState fluidState) {
        net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions extensions = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluidState);
        java.util.function.Function<net.minecraft.resources.ResourceLocation, net.minecraft.client.renderer.texture.TextureAtlasSprite> atlas = net.minecraft.client.Minecraft.getInstance().getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        return new net.minecraft.client.renderer.texture.TextureAtlasSprite[] {
                atlas.apply(extensions.getStillTexture(fluidState, level, pos)),
                atlas.apply(extensions.getFlowingTexture(fluidState, level, pos))
        };
    }
"""
    return source.substring(0, insertAt).trimEnd() + helper + source.substring(insertAt)
}

internal fun StructuralRefactorPass.migrateRenderBackgroundCallArguments(source: String): String {
    if (!source.contains("renderBackground(")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val methodPattern = Regex(
        """(?m)(?:public|protected|private)\s+(?:static\s+)?void\s+$id\s*\(\s*(?:net\.minecraft\.client\.gui\.)?GuiGraphics\s+($id)\s*,\s*int\s+($id)\s*,\s*int\s+($id)\s*,\s*float\s+($id)\s*\)\s*\{"""
    )
    var result = source
    var cursor = 0
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        val mouseX = match.groupValues[2]
        val mouseY = match.groupValues[3]
        val partialTick = match.groupValues[4]
        val openBrace = result.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val rewritten = Regex(
            """((?:this\.)?renderBackground\s*\(\s*[^,;\r\n()]+\s*,\s*)mouseX\s*,\s*mouseY\s*,\s*partialTicks(\s*\))"""
        ).replace(body) { call ->
            "${call.groupValues[1]}$mouseX, $mouseY, $partialTick${call.groupValues[2]}"
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

internal fun StructuralRefactorPass.migrateGuiGraphicsBlitRepeatingSource(source: String): String {
    if (!source.contains(".blitRepeating(")) return source
    var changed = false
    var result = source
    var cursor = 0
    val token = ".blitRepeating("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + token.length - 1
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val receiverStart = findExpressionReceiverStart(executableCode, tokenIndex)
            .takeIf { it >= 0 && it < tokenIndex }
            ?: simpleJavaReceiverStart(executableCode, tokenIndex)
        if (receiverStart < 0 || receiverStart >= tokenIndex) {
            cursor = closeParen + 1
            continue
        }
        val receiver = result.substring(receiverStart, tokenIndex).trim()
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 11) {
            cursor = closeParen + 1
            continue
        }
        val replacement = "modporter${'$'}blitRepeating($receiver, ${args.joinToString(", ") { it.trim() }})"
        result = result.substring(0, receiverStart) + replacement + result.substring(closeParen + 1)
        cursor = receiverStart + replacement.length
        changed = true
    }
    if (changed) {
        result = ensureModporterBlitRepeatingHelper(result)
    }
    return result
}

internal fun StructuralRefactorPass.simpleJavaReceiverStart(source: String, tokenIndex: Int): Int {
    var index = tokenIndex - 1
    while (index >= 0 && source[index].isWhitespace()) index--
    while (index >= 0 && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$' || source[index] == '.')) {
        index--
    }
    return index + 1
}

internal fun StructuralRefactorPass.ensureModporterBlitRepeatingHelper(source: String): String {
    val helperName = "modporter${'$'}blitRepeating"
    if (Regex("""\b${Regex.escape(helperName)}\s*\(""").containsMatchIn(source)) {
        val declaration = Regex("""\bprivate\s+static\s+void\s+${Regex.escape(helperName)}\s*\(""")
        if (declaration.containsMatchIn(source)) return source
    }
    val insertAt = source.lastIndexOf('}')
    if (insertAt < 0) return source
    val helper = """

    private static void modporter${'$'}blitRepeating(net.minecraft.client.gui.GuiGraphics graphics, net.minecraft.resources.ResourceLocation texture, int x, int y, int width, int height, int sourceX, int sourceY, int sourceWidth, int sourceHeight, int textureWidth, int textureHeight) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        for (int xOffset = 0; xOffset < width; xOffset += sourceWidth) {
            int drawWidth = Math.min(sourceWidth, width - xOffset);
            for (int yOffset = 0; yOffset < height; yOffset += sourceHeight) {
                int drawHeight = Math.min(sourceHeight, height - yOffset);
                graphics.blit(texture, x + xOffset, y + yOffset, drawWidth, drawHeight, (float) sourceX, (float) sourceY, drawWidth, drawHeight, textureWidth, textureHeight);
            }
        }
    }
"""
    return source.substring(0, insertAt).trimEnd() + helper + source.substring(insertAt)
}

internal fun StructuralRefactorPass.tooltipContextExpressionForPlayer(playerExpression: String): String? {
    val trimmed = playerExpression.trim()
    if (trimmed == "Minecraft.getInstance().player") {
        return "Item.TooltipContext.of(Minecraft.getInstance().level)"
    }
    if (trimmed.endsWith(".player")) {
        val owner = trimmed.removeSuffix(".player")
        if (owner == "Minecraft.getInstance()") {
            return "Item.TooltipContext.of(Minecraft.getInstance().level)"
        }
    }
    if (Regex("""^[A-Za-z_$][\w$]*$""").matches(trimmed)) {
        return "Item.TooltipContext.of($trimmed.level())"
    }
    return null
}

internal fun StructuralRefactorPass.collectEditBoxReceiverNames(source: String): Set<String> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val names = linkedSetOf<String>()
    Regex("""\b(?:net\.minecraft\.client\.gui\.components\.)?EditBox\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .forEach { names += it.groupValues[1] }
    Regex("""\b(?:private|protected|public)?\s*(?:final\s+)?(?:net\.minecraft\.client\.gui\.components\.)?EditBox\s+([A-Za-z_$][\w$]*)\s*(?:=|;)""")
        .findAll(executableCode)
        .forEach { names += it.groupValues[1] }
    return names
}

internal fun StructuralRefactorPass.migrateGeometryLoaderResourceLocationKeys(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ModelEvent.RegisterGeometryLoaders") || !executableCode.contains(".getPath()")) {
        return source
    }
    return rewriteExecutableJavaCall(source, "register") { receiver, args ->
        if (args.size != 2 || !args[0].trim().endsWith(".getPath()")) return@rewriteExecutableJavaCall null
        val keyExpression = args[0].trim().removeSuffix(".getPath()")
        "$receiver.register($keyExpression, ${args[1].trim()})"
    }
}

internal fun StructuralRefactorPass.migrateLegacyItemStackRegistrySerialization(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY,
    javaMethodReturnTypes: Map<String, String> = emptyMap()
): String {
    if (!source.contains("ItemStack.parseOptional(") &&
        !source.contains("ItemStack.of(") &&
        !source.contains("ItemStack::of") &&
        !source.contains("ContainerHelper.") &&
        !source.contains(".saveWithoutMetadata(") &&
        !source.contains(".save(") &&
        !source.contains(".save()") &&
        !source.contains(".serializeNBT()") &&
        !source.contains(".deserializeNBT(") &&
        !source.contains("ItemStack::serializeNBT") &&
        !source.contains("FluidStack::loadFluidStackFromNBT") &&
        !source.contains("Component.Serializer.") &&
        !source.contains("loadStatic(") &&
        !source.contains(".saveWithId(")
    ) return source
    var result = source
    var typedSource: String? = null
    var typedValues: SourceValueTypes? = null
    fun valueTypes(): SourceValueTypes {
        if (typedSource !== result) {
            typedSource = result
            typedValues = SourceValueTypes(this, result)
        }
        return typedValues!!
    }
    val inferredStackReceivers = mutableSetOf<Pair<String, Int>>()
    fun registryAccessAt(offset: Int): String? =
        registryAccessExpressionAt(result, offset, javaInheritanceIndex)
    // 1.20.5: Component.Serializer.fromJson(String, Provider) / toJson(Component, Provider)
    for (serializerMethod in listOf("fromJson", "toJson")) {
        result = rewriteExecutableJavaCallWithOffset(result, serializerMethod) { receiver, args, offset ->
            // the built-in registries stood in where no registries were in scope before the provider was threaded
            if (receiver != "Component.Serializer" || !(args.size == 1 || args.size == 2 && args[1].trim() == BUILT_IN_REGISTRY_ACCESS)) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val registryAccess = registryAccessAt(offset) ?: return@rewriteExecutableJavaCallWithOffset null
            "Component.Serializer.$serializerMethod(${args[0].trim()}, $registryAccess)"
        }
    }
    fun blockEntityReceiverRegistryAccess(receiver: String, offset: Int): String? {
        val normalized = receiver.trim()
        if (normalized == "this") {
            val declaration = enclosingJavaClassDeclaration(result, offset)
            if (javaClassExtendsAny(declaration, javaBlockEntityBaseTypes(), javaInheritanceIndex)) {
                return "this.getLevel().registryAccess()"
            }
            return null
        }
        val simpleReceiver = normalized.substringAfterLast('.')
        if (!Regex("""^[A-Za-z_$][\w$]*$""").matches(simpleReceiver)) return null
        val scope = enclosingMethodText(result, offset) ?: result
        val localType = javaLocalVariableTypes(scope)[simpleReceiver]
            ?: javaLocalVariableTypes(maskJavaCommentsAndLiterals(result))[simpleReceiver]
        if (localType != null &&
            (localType in javaBlockEntityBaseTypes() ||
                javaInheritanceIndex.inherits(localType, javaBlockEntityBaseTypes()))) {
            return "$normalized.getLevel().registryAccess()"
        }
        return null
    }
    // An entity serializes itself with its own registry access.
    fun entityReceiverRegistryAccess(receiver: String, offset: Int): String? {
        val normalized = receiver.trim()
        val simpleReceiver = normalized.substringAfterLast('.')
        if (!Regex("""^[A-Za-z_$][\w$]*$""").matches(simpleReceiver)) return null
        val scope = enclosingMethodText(result, offset) ?: result
        val localType = javaLocalVariableTypes(scope)[simpleReceiver]
            ?: javaLocalVariableTypes(maskJavaCommentsAndLiterals(result))[simpleReceiver]
            ?: return null
        val entityTypes = javaEntityBaseTypes()
        return if (localType in entityTypes || javaInheritanceIndex.inherits(localType, entityTypes)) "$normalized.registryAccess()" else null
    }
    val itemStackNames = Regex("""\bItemStack\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    val itemStackCollectionNames = Regex(
        """\b(?:NonNullList|List|ArrayList|java\.util\.List|java\.util\.ArrayList)\s*<\s*ItemStack\s*>\s+([A-Za-z_$][\w$]*)\b"""
    )
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    val itemStackHandlerNames = collectItemStackHandlerValueNames(result)
    fun isItemStackReceiver(receiver: String, offset: Int): Boolean {
        val normalized = receiver.trim()
        if (Regex("""^[A-Za-z_$][\w$]*$""").matches(normalized) && valueTypes().untypedLambdaParameter(normalized, offset)) {
            val stack = valueTypes().typeOf(normalized, offset) == "net.minecraft.world.item.ItemStack"
            if (stack) inferredStackReceivers += normalized to offset
            return stack
        }
        // a plain name is what its declaration in scope says (a stack parameter elsewhere in the
        // file does not make a same-named field a stack)
        if (Regex("""^[A-Za-z_$][\w$]*$""").matches(normalized)) {
            com.modporter.core.transforms.util.JavaScopedDeclarations.visibleAt(maskJavaCommentsAndLiterals(result), normalized, offset)?.let {
                return simpleJavaTypeName(it.type) == "ItemStack"
            }
        }
        val simpleName = normalized.substringAfterLast('.').removeSuffix("()")
        if (simpleName in itemStackNames && !normalized.endsWith(')')) return true
        if (normalized == "ItemStack.EMPTY" || normalized == "net.minecraft.world.item.ItemStack.EMPTY") return true
        Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)$""").find(normalized)?.let { match ->
            val (owner, field) = match.destructured
            val scope = enclosingMethodText(result, offset) ?: result
            val ownerType = javaLocalVariableTypes(scope)[owner] ?: javaLocalVariableTypes(maskJavaCommentsAndLiterals(result))[owner]
            if (ownerType != null && projectItemStackFields[ownerType]?.contains(field) == true) return true
        }
        // variable.getter() where the variable has one declared type whose getter is declared to return ItemStack
        Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\(\s*\)$""").find(normalized.replace(Regex("""\s*\.\s*"""), "."))?.let { match ->
            val (variable, getter) = match.destructured
            val declaredTypes = Regex("""\b([A-Z][\w$]*)(?:<[^;=(){}]*>)?\s+${Regex.escape(variable)}\s*(?=[=;,):])""")
                .findAll(maskJavaCommentsAndLiterals(result))
                .map { it.groupValues[1] }
                .toSet()
            val type = declaredTypes.singleOrNull()
            if (type != null && javaMethodReturnTypes["$type.$getter"] == "ItemStack") return true
        }
        if (normalized.startsWith("new ItemStack(") ||
            normalized.startsWith("new net.minecraft.world.item.ItemStack(") ||
            Regex("""\.(?:extractItem|getStackInSlot|insertItem)\s*\(""").containsMatchIn(normalized) ||
            Regex("""\.(?:getMainHandItem|getOffhandItem|getItemInHand|getItemBySlot)\s*\(""").containsMatchIn(normalized) ||
            Regex("""\.getItem\s*\(\s*[^)\s]""").containsMatchIn(normalized) ||
            Regex("""\.(?:copy|copyWithCount)\s*\(""").containsMatchIn(normalized)) {
            return true
        }
        val collectionReceiver = Regex("""^(?:this\.)?([A-Za-z_$][\w$]*)\.get\s*\(""").find(normalized)
        if (collectionReceiver != null && collectionReceiver.groupValues[1] in itemStackCollectionNames) return true
        if (Regex("""^(?:this\.)?stacks\.get\s*\(""").containsMatchIn(normalized)) {
            val classDecl = enclosingJavaClassDeclaration(result, offset)
            if (javaClassExtendsAny(classDecl, setOf("ItemStackHandler"), javaInheritanceIndex)) {
                return true
            }
        }
        if (valueTypes().typeOf(normalized, offset) == "net.minecraft.world.item.ItemStack") {
            inferredStackReceivers += normalized to offset
            return true
        }
        return false
    }
    fun isItemStackHandlerReceiver(receiver: String, offset: Int): Boolean {
        val normalized = receiver.trim()
        if (Regex("""^[A-Za-z_$][\w$]*$""").matches(normalized) && valueTypes().untypedLambdaParameter(normalized, offset)) {
            val inferred = valueTypes().typeOf(normalized, offset) ?: return false
            val simple = inferred.substringAfterLast('.')
            return inferred == "net.neoforged.neoforge.items.ItemStackHandler" ||
                javaInheritanceIndex.isProjectType(simple) && simple in projectNbtHandlerTypeNames
        }
        if (Regex("""^[A-Za-z_$][\w$]*$""").matches(normalized)) {
            com.modporter.core.transforms.util.JavaScopedDeclarations.visibleAt(
                maskJavaCommentsAndLiterals(result),
                normalized,
                offset
            )?.let { declaration ->
                val simple = simpleJavaTypeName(declaration.type)
                return simple == "ItemStackHandler" ||
                    (
                        javaInheritanceIndex.isProjectType(simple) &&
                            javaInheritanceIndex.inherits(simple, setOf("ItemStackHandler"))
                        )
            }
        }
        val simpleName = normalized.substringAfterLast('.').removeSuffix("()")
        if (simpleName in itemStackHandlerNames) return true
        if (normalized.contains('.') && simpleName in projectNbtHandlerFieldNames) return true
        if (normalized == "this") {
            val classDecl = enclosingJavaClassDeclaration(result, offset)
            if (javaClassExtendsAny(classDecl, setOf("ItemStackHandler"), javaInheritanceIndex)) {
                return true
            }
        }
        val inferred = valueTypes().typeOf(normalized, offset)
        if (inferred == "net.neoforged.neoforge.items.ItemStackHandler") return true
        val simple = inferred?.substringAfterLast('.')
        if (simple != null && javaInheritanceIndex.isProjectType(simple) &&
            javaInheritanceIndex.inherits(simple, setOf("ItemStackHandler"))) return true
        return false
    }
    fun itemStackReceiverRegistryAccess(receiver: String, offset: Int): String? {
        val normalized = receiver.trim()
        Regex("""^([A-Za-z_$][\w$]*)\.(?:copy|copyWithCount)\s*\(""")
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.let { copied ->
                val methodPrefix = enclosingMethodText(result, offset)
                    ?.let { method ->
                        val relative = offset - result.indexOf(method)
                        if (relative > 0 && relative <= method.length) method.substring(0, relative) else method
                    }
                    ?: result.substring(0, offset.coerceAtMost(result.length))
                Regex("""\bItemStack\s+${Regex.escape(copied)}\s*=\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.(?:getItemInHand|getMainHandItem|getOffhandItem)\s*\(""")
                    .find(maskJavaCommentsAndLiterals(methodPrefix))
                    ?.groupValues
                    ?.get(1)
                    ?.let { return "$it.registryAccess()" }
            }
        Regex("""^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.(?:getItemInHand|getMainHandItem|getOffhandItem)\s*\(""")
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.let { return "$it.registryAccess()" }
        return null
    }
    result = Regex("""ItemStack\.parseOptional\(\s*player\.registryAccess\(\)\s*,""")
        .replace(result) { match ->
            val registryAccess = registryAccessAt(match.range.first)
            if (registryAccess == null) match.value else "ItemStack.parseOptional($registryAccess,"
        }
    var itemStackOfCursor = 0
    while (true) {
        val tokenIndex = result.indexOf("ItemStack.of(", itemStackOfCursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0 && (result[tokenIndex - 1].isLetterOrDigit() || result[tokenIndex - 1] == '_' || result[tokenIndex - 1] == '$')) {
            itemStackOfCursor = tokenIndex + "ItemStack.of(".length
            continue
        }
        val openParen = tokenIndex + "ItemStack.of".length
        val closeParen = findMatchingParen(result, openParen)
        if (closeParen < 0) {
            itemStackOfCursor = tokenIndex + "ItemStack.of(".length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 1) {
            itemStackOfCursor = closeParen + 1
            continue
        }
        val registryAccess = registryAccessAt(tokenIndex)
        if (registryAccess == null) {
            itemStackOfCursor = closeParen + 1
            continue
        }
        val replacement = "ItemStack.parseOptional($registryAccess, ${args[0].trim()})"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        itemStackOfCursor = tokenIndex + replacement.length
    }
    result = replaceExecutableRegex(result, Regex("""\.map\s*\(\s*ItemStack::of\s*\)""")) { match ->
        val registryAccess = registryAccessAt(match.range.first)
        if (registryAccess == null) {
            match.value
        } else {
            ".map(tag -> ItemStack.parseOptional($registryAccess, tag))"
        }
    }
    result = rewriteExecutableJavaCallWithOffset(result, "save") { receiver, args, offset ->
        if (!isItemStackReceiver(receiver, offset)) return@rewriteExecutableJavaCallWithOffset null
        val registryAccess = registryAccessAt(offset)
            ?: itemStackReceiverRegistryAccess(receiver, offset)
            ?: return@rewriteExecutableJavaCallWithOffset null
        when (args.size) {
            0 -> "$receiver.saveOptional($registryAccess)"
            1 -> {
                val tagArg = args[0].trim()
                if (tagArg == registryAccess || tagArg.endsWith(".registryAccess()")) {
                    null
                } else if (tagArg == "new net.minecraft.nbt.CompoundTag()") {
                    "$receiver.save($registryAccess, $tagArg)"
                } else if (tagArg == "new CompoundTag()") {
                    // 1.20.1's save(CompoundTag) returned the CompoundTag; saveOptional returns a Tag that
                    // is always that compound (an empty one for an empty stack)
                    val masked = maskJavaCommentsAndLiterals(result)
                    if (compoundTagExpected(masked, findExpressionReceiverStart(masked, offset))) {
                        "((net.minecraft.nbt.CompoundTag) $receiver.saveOptional($registryAccess))"
                    } else {
                        "$receiver.saveOptional($registryAccess)"
                    }
                } else {
                    "$receiver.save($registryAccess, $tagArg)"
                }
            }
            else -> null
        }
    }
    result = rewriteExecutableJavaCallWithOffset(result, "saveAllItems") { receiver, args, offset ->
        if (receiver != "ContainerHelper" || args.size != 2) return@rewriteExecutableJavaCallWithOffset null
        val registryAccess = registryAccessAt(offset) ?: return@rewriteExecutableJavaCallWithOffset null
        "ContainerHelper.saveAllItems(${args[0].trim()}, ${args[1].trim()}, $registryAccess)"
    }
    result = rewriteExecutableJavaCallWithOffset(result, "loadAllItems") { receiver, args, offset ->
        if (receiver != "ContainerHelper" || args.size != 2) return@rewriteExecutableJavaCallWithOffset null
        val registryAccess = registryAccessAt(offset) ?: return@rewriteExecutableJavaCallWithOffset null
        "ContainerHelper.loadAllItems(${args[0].trim()}, ${args[1].trim()}, $registryAccess)"
    }
    // 1.20.5: BlockEntity.loadStatic(pos, state, tag, Provider) and BlockEntity#saveWithId(Provider)
    result = rewriteExecutableJavaCallWithOffset(result, "loadStatic") { receiver, args, offset ->
        if (receiver.substringAfterLast('.') != "BlockEntity" || args.size != 3) return@rewriteExecutableJavaCallWithOffset null
        val registryAccess = registryAccessAt(offset) ?: return@rewriteExecutableJavaCallWithOffset null
        "$receiver.loadStatic(${(args.map { it.trim() } + registryAccess).joinToString(", ")})"
    }
    result = rewriteExecutableJavaCallWithOffset(result, "saveWithId") { receiver, args, offset ->
        if (args.isNotEmpty()) return@rewriteExecutableJavaCallWithOffset null
        // the receiver must be a block entity; its own level is the registry source if nothing closer is
        val receiverRegistryAccess = blockEntityReceiverRegistryAccess(receiver, offset) ?: return@rewriteExecutableJavaCallWithOffset null
        "$receiver.saveWithId(${registryAccessAt(offset) ?: receiverRegistryAccess})"
    }

    result = rewriteExecutableJavaCallWithOffset(result, "saveWithoutMetadata") { receiver, args, offset ->
        if (args.isNotEmpty()) return@rewriteExecutableJavaCallWithOffset null
        val receiverRegistryAccess = blockEntityReceiverRegistryAccess(receiver, offset)
            ?: return@rewriteExecutableJavaCallWithOffset null
        val registryAccess = registryAccessAt(offset) ?: receiverRegistryAccess
        "$receiver.saveWithoutMetadata($registryAccess)"
    }
    result = rewriteExecutableJavaCallWithOffset(result, "serializeNBT") { receiver, args, offset ->
        if (args.isNotEmpty()) return@rewriteExecutableJavaCallWithOffset null
        entityReceiverRegistryAccess(receiver, offset)?.let { return@rewriteExecutableJavaCallWithOffset "$receiver.serializeNBT($it)" }
        val registryAccess = registryAccessAt(offset) ?: return@rewriteExecutableJavaCallWithOffset null
        when {
            // Forge's ItemStack#serializeNBT returned a CompoundTag, saveOptional a Tag that is one
            isItemStackReceiver(receiver, offset) -> {
                val masked = maskJavaCommentsAndLiterals(result)
                if ((receiver.trim() to offset) in inferredStackReceivers) {
                    // Class.cast retains serializeNBT's CompoundTag return type and remains a
                    // statement expression, including Consumer-compatible lambda bodies.
                    "net.minecraft.nbt.CompoundTag.class.cast($receiver.saveOptional($registryAccess))"
                } else if (compoundTagExpected(masked, findExpressionReceiverStart(masked, offset))) {
                    "((net.minecraft.nbt.CompoundTag) $receiver.saveOptional($registryAccess))"
                } else {
                    "$receiver.saveOptional($registryAccess)"
                }
            }
            isItemStackHandlerReceiver(receiver, offset) -> "$receiver.serializeNBT($registryAccess)"
            else -> null
        }
    }
    result = rewriteExecutableJavaCallWithOffset(result, "deserializeNBT") { receiver, args, offset ->
        if (args.size != 1) return@rewriteExecutableJavaCallWithOffset null
        entityReceiverRegistryAccess(receiver, offset)?.let { return@rewriteExecutableJavaCallWithOffset "$receiver.deserializeNBT($it, ${args[0].trim()})" }
        if (!isItemStackHandlerReceiver(receiver, offset)) return@rewriteExecutableJavaCallWithOffset null
        val registryAccess = registryAccessAt(offset) ?: return@rewriteExecutableJavaCallWithOffset null
        "$receiver.deserializeNBT($registryAccess, ${args[0].trim()})"
    }
    // ItemStack::serializeNBT / ItemStack::of as functions take the provider through a lambda; the
    // CompoundTag the reference returned is what saveOptional always builds
    result = replaceExecutableRegex(result, Regex("""(?<![\w$.])ItemStack::serializeNBT(?![\w$])""")) { match ->
        registryAccessAt(match.range.first)?.let { "modporterStack -> (net.minecraft.nbt.CompoundTag) modporterStack.saveOptional($it)" } ?: match.value
    }
    result = replaceExecutableRegex(result, Regex("""(?<![\w$.])ItemStack::of(?![\w$])""")) { match ->
        registryAccessAt(match.range.first)?.let { "modporterTag -> ItemStack.parseOptional($it, modporterTag)" } ?: match.value
    }
    if (result.contains("FluidStack::loadFluidStackFromNBT") && valueTypes().isTypeName("FluidStack", "net.neoforged.neoforge.fluids.FluidStack")) {
        result = replaceExecutableRegex(result, Regex("""(?<![\w$.])FluidStack::loadFluidStackFromNBT(?![\w$])""")) { match ->
            registryAccessAt(match.range.first)?.let { "modporterFluidTag -> FluidStack.parseOptional($it, modporterFluidTag)" } ?: match.value
        }
    }
    result = threadRegistryAccessThroughPrivateContainerHelperMethods(result, javaInheritanceIndex)
    return result
}

internal fun StructuralRefactorPass.threadRegistryAccessThroughPrivateContainerHelperMethods(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains("ContainerHelper.saveAllItems(") && !source.contains("ContainerHelper.loadAllItems(")) {
        return source
    }
    val methods = javaMethodRangesIncludingDefault(source)
    val methodCounts = methods.groupingBy { it.name }.eachCount()
    val edits = mutableListOf<Pair<IntRange, String>>()

    for (method in methods) {
        if (methodCounts[method.name] != 1) continue
        val methodText = source.substring(method.range)
        if (!methodText.contains("ContainerHelper.saveAllItems(") &&
            !methodText.contains("ContainerHelper.loadAllItems(")) {
            continue
        }
        val openBrace = source.indexOf('{', method.range.first).takeIf { it in method.range } ?: continue
        val header = source.substring(method.range.first, openBrace)
        if (!privateMethodDeclarationLine(header, method.name)) continue
        if (header.contains("HolderLookup.Provider")) continue
        val paramsText = javaMethodParameterText(header) ?: continue
        val parameterCount = splitTopLevelJavaArgs(paramsText).size
        val providerName = uniqueLocalNameInScope(methodText, "registries")
        val migratedMethod = addHolderLookupProviderToPrivateContainerHelperMethod(methodText, method.name, providerName)
        if (migratedMethod == methodText) continue
        val callEdits = privateMethodCallProviderEdits(
            source = source,
            method = method,
            originalParameterCount = parameterCount,
            javaInheritanceIndex = javaInheritanceIndex
        ) ?: continue
        edits += method.range to migratedMethod
        edits += callEdits
    }

    if (edits.isEmpty()) return source
    return addImportIfMissing(applyStringEdits(source, edits), "net.minecraft.core.HolderLookup")
}

internal fun StructuralRefactorPass.privateMethodDeclarationLine(header: String, methodName: String): Boolean =
    header.lines().any { line ->
        Regex("""^\s*private\b[^;={}]*\b${Regex.escape(methodName)}\s*\(""").containsMatchIn(line)
    }

internal fun StructuralRefactorPass.addHolderLookupProviderToPrivateContainerHelperMethod(methodText: String, methodName: String, providerName: String): String {
    val nameMatch = Regex("""\b${Regex.escape(methodName)}\s*\(""").find(methodText)
        ?: return methodText
    val openParen = methodText.indexOf('(', nameMatch.range.first)
    if (openParen < 0) return methodText
    val closeParen = findMatchingParen(methodText, openParen)
    if (closeParen <= openParen) return methodText
    val paramsText = methodText.substring(openParen + 1, closeParen)
    val newParams = if (paramsText.isBlank()) {
        "HolderLookup.Provider $providerName"
    } else {
        "$paramsText, HolderLookup.Provider $providerName"
    }
    var result = methodText.substring(0, openParen + 1) + newParams + methodText.substring(closeParen)
    result = rewriteExecutableJavaCallWithOffset(result, "saveAllItems") { receiver, args, _ ->
        if (receiver != "ContainerHelper" || args.size !in 2..3) return@rewriteExecutableJavaCallWithOffset null
        "ContainerHelper.saveAllItems(${(args.map { it.trim() } + providerName).joinToString(", ")})"
    }
    result = rewriteExecutableJavaCallWithOffset(result, "loadAllItems") { receiver, args, _ ->
        if (receiver != "ContainerHelper" || args.size != 2) return@rewriteExecutableJavaCallWithOffset null
        "ContainerHelper.loadAllItems(${args[0].trim()}, ${args[1].trim()}, $providerName)"
    }
    return result
}

internal fun StructuralRefactorPass.privateMethodCallProviderEdits(
    source: String,
    method: JavaMethodRange,
    originalParameterCount: Int,
    javaInheritanceIndex: JavaInheritanceIndex
): List<Pair<IntRange, String>>? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val token = "${method.name}("
    val edits = mutableListOf<Pair<IntRange, String>>()
    var cursor = 0
    while (true) {
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0 && (executableCode[tokenIndex - 1].isLetterOrDigit() || executableCode[tokenIndex - 1] == '_' || executableCode[tokenIndex - 1] == '$')) {
            cursor = tokenIndex + token.length
            continue
        }
        val openParen = tokenIndex + method.name.length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        if (looksLikeJavaMethodDeclaration(executableCode, tokenIndex, closeParen)) {
            cursor = closeParen + 1
            continue
        }
        if (tokenIndex in method.range) {
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
                // Already threaded by another structural migration.
            }
            else -> return null
        }
        cursor = closeParen + 1
    }
    return edits.takeIf { it.isNotEmpty() }
}

internal fun StructuralRefactorPass.migrateLegacyBlockEntityLoadCalls(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains(".load(") || !source.contains("BlockEntity")) return source
    val variables = Regex("""\b(?:BlockEntity|[A-Za-z_$][\w$]*BlockEntity)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (variables.isEmpty()) return source
    var result = source
    variables.forEach { variable ->
        result = Regex("""\b${Regex.escape(variable)}\.load\(\s*([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match ->
                val registryAccess = registryAccessExpressionAt(result, match.range.first, javaInheritanceIndex)
                    ?: return@replace match.value
                "$variable.loadWithComponents(${match.groupValues[1]}, $registryAccess)"
            }
    }
    return result
}

internal fun StructuralRefactorPass.migrateCommandSourceStackLevelAccess(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("CommandSourceStack") ||
        !Regex("""\.\s*level\s*\(\s*\)""").containsMatchIn(executableCode)) {
        return source
    }
    val commandSourceType = """(?:CommandSourceStack|net\.minecraft\.commands\.CommandSourceStack)"""
    val declarationPattern = Regex("""\b$commandSourceType\s+([A-Za-z_$][\w$]*)\b""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (scope in commandSourceStackExecutableScopes(executableCode)) {
        val declarations = declarationPattern.findAll(executableCode, scope.first)
            .takeWhile { it.range.last <= scope.last }
            .groupBy({ it.groupValues[1] }, { it.range.first })
        if (declarations.isEmpty()) continue
        for ((variable, declarationOffsets) in declarations) {
            val levelCallPattern = Regex("""\b${Regex.escape(variable)}\s*\.\s*level\s*\(\s*\)""")
            levelCallPattern.findAll(executableCode, scope.first)
                .takeWhile { it.range.last <= scope.last }
                .filter { match -> declarationOffsets.any { it < match.range.first } }
                .filterNot { match ->
                    val originalMatch = source.substring(match.range)
                    originalMatch.contains("/*") || originalMatch.contains("//")
                }
                .forEach { match -> edits += match.range to "$variable.getLevel()" }
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.commandSourceStackExecutableScopes(executableCode: String): List<IntRange> {
    val methodRanges = (
        javaMethodRanges(executableCode) +
            javaMethodRangesIncludingDefault(executableCode)
        ).map { it.range }.distinct()
    val typeBlocks = javaTypeBlocks(executableCode, executableCode)
    val constructorRanges = typeBlocks.flatMap { type ->
        val constructorPattern = Regex(
            """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s*\r?\n[ \t]*)*(?:public|protected|private)?\s*${Regex.escape(type.name)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
        )
        constructorPattern.findAll(executableCode)
            .mapNotNull { match ->
                if (match.range.first <= type.bodyStart || match.range.first >= type.end) return@mapNotNull null
                val openBrace = executableCode.indexOf('{', match.range.last - 1)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
                if (openBrace < 0 || closeBrace < 0 || closeBrace > type.end) return@mapNotNull null
                match.range.first..closeBrace
            }
            .toList()
    }
    return (methodRanges + constructorRanges)
        .distinct()
        .sortedBy { it.first }
}

internal fun StructuralRefactorPass.migrateLegacyStaticFmlModEventBusAccess(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val staticGetterPattern = Regex(
        """\bFMLJavaModLoadingContext\s*\.\s*get\s*\(\s*\)\s*\.\s*getModEventBus\s*\(\s*\)"""
    )
    if (!staticGetterPattern.containsMatchIn(executableCode)) return source
    val parsed = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    ).parse(source).result.orElse(null) ?: return source
    val imports = javaNonStaticImports(source)
    fun annotationOwner(annotation: AnnotationExpr): String? {
        val raw = annotation.nameAsString
        return if (raw.contains('.')) raw else imports[raw]
    }
    fun normalMember(annotation: AnnotationExpr, name: String): Expression? =
        (annotation as? NormalAnnotationExpr)
            ?.pairs
            ?.singleOrNull { it.nameAsString == name }
            ?.value
    val subscriberIds = parsed.findAll(AnnotationExpr::class.java).mapNotNull { annotation ->
        val owner = annotationOwner(annotation)
        if (owner != "net.neoforged.fml.common.EventBusSubscriber" &&
            owner != "net.minecraftforge.fml.common.EventBusSubscriber") {
            return@mapNotNull null
        }
        normalMember(annotation, "modid")?.toString()
    }.distinct()
    val modIds = parsed.findAll(AnnotationExpr::class.java).mapNotNull { annotation ->
        val owner = annotationOwner(annotation)
        if (owner != "net.neoforged.fml.common.Mod" &&
            owner != "net.minecraftforge.fml.common.Mod") {
            return@mapNotNull null
        }
        when (annotation) {
            is SingleMemberAnnotationExpr -> annotation.memberValue.toString()
            else -> normalMember(annotation, "value")?.toString()
        }
    }.distinct()
    val modId = subscriberIds.singleOrNull() ?: modIds.singleOrNull() ?: return source
    var result = replaceExecutableRegex(source, staticGetterPattern) {
        "net.neoforged.fml.ModList.get().getModContainerById($modId).orElseThrow().getEventBus()"
    }
    result = removeExecutableImport(result, "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEntityStepHeightOverrides(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getStepHeight")) return source
    return replaceExecutableRegex(
        source,
        Regex("""\b(public|protected)\s+float\s+getStepHeight\s*\(\s*\)""")
    ) { match -> "${match.groupValues[1]} float maxUpStep()" }
}

internal fun StructuralRefactorPass.migrateLegacyMaxUpStepSetterSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("setMaxUpStep(")) return source
    if (Regex("""\bfloat\s+maxUpStep\s*\(""").containsMatchIn(executableCode)) return source

    val setterPattern = Regex("""(?m)^([ \t]*)(?:this\.)?setMaxUpStep\s*\(\s*((?:\d+(?:\.\d+)?|\.\d+)(?:[fFdD])?)\s*\)\s*;\s*(?:\r?\n)?""")
    val match = setterPattern.find(executableCode) ?: return source
    val classDeclaration = enclosingJavaClassDeclaration(executableCode, match.range.first) ?: return source
    val directSuper = classDeclaration.directSuper.substringAfterLast('.')
    if (directSuper !in javaEntityBaseTypes()) return source
    val stepHeight = match.groupValues[2]
    var result = source.removeRange(match.range)
    val method = """

    @Override
    public float maxUpStep() {
        return $stepHeight;
    }
""".trimEnd()
    val insertAt = classDeclaration.bodyRange.last - (match.range.last + 1 - match.range.first)
    if (insertAt <= 0 || insertAt > result.length) return source
    result = result.substring(0, insertAt) + "\n$method\n" + result.substring(insertAt)
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEntityTypeAabbCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val callPattern = Regex("""\.\s*getAABB\s*\(""")
    if (!callPattern.containsMatchIn(executableCode)) return source
    val edits = mutableListOf<Pair<IntRange, String>>()
    callPattern.findAll(executableCode).forEach { match ->
        val tokenIndex = match.range.first
        val openParen = executableCode.indexOf('(', tokenIndex)
        val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
        if (openParen < 0 || closeParen < 0) return@forEach
        val receiverEnd = previousNonWhitespaceEnd(executableCode, tokenIndex)
        val receiverStart = findExpressionReceiverStart(executableCode, receiverEnd)
        if (receiverStart < 0 || receiverStart >= receiverEnd) return@forEach
        val receiver = source.substring(receiverStart, receiverEnd).trim()
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (receiver.isNotEmpty() && args.size == 3) {
            edits += receiverStart..closeParen to
                "${receiver}.getDimensions().makeBoundingBox(${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()})"
        }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.previousNonWhitespaceEnd(source: String, offset: Int): Int {
    var index = offset
    while (index > 0 && source[index - 1].isWhitespace()) index--
    return index
}

internal fun StructuralRefactorPass.findExpressionReceiverStart(source: String, tokenIndex: Int): Int {
    var index = tokenIndex - 1
    var parenDepth = 0
    var bracketDepth = 0
    scan@ while (index >= 0) {
        val c = source[index]
        when (c) {
            ')' -> parenDepth++
            '(' -> {
                if (parenDepth == 0) break@scan
                parenDepth--
            }
            ']' -> bracketDepth++
            '[' -> {
                if (bracketDepth == 0) break@scan
                bracketDepth--
            }
            else -> {
                // Java allows whitespace around the member-access dot (`a.b()\n    .c()`)
                val besideDot = c.isWhitespace() && run {
                    val right = (index + 1 until tokenIndex).firstOrNull { !source[it].isWhitespace() }?.let { source[it] } ?: source[tokenIndex]
                    val left = (index - 1 downTo 0).firstOrNull { !source[it].isWhitespace() }?.let { source[it] }
                    right == '.' || left == '.'
                }
                if (parenDepth == 0 && bracketDepth == 0 && !besideDot &&
                    !(c.isLetterOrDigit() || c == '_' || c == '$' || c == '.')) {
                    break@scan
                }
            }
        }
        index--
    }
    val start = index + 1
    // Keep the constructor keyword in an object-creation receiver.
    var before = start - 1
    while (before >= 0 && source[before].isWhitespace()) before--
    val keywordStart = before - 2
    if (keywordStart >= 0 && source.substring(keywordStart, before + 1) == "new" &&
        (keywordStart == 0 || !(source[keywordStart - 1].isLetterOrDigit() || source[keywordStart - 1] in "_$"))) return keywordStart
    return start
}

internal fun StructuralRefactorPass.migrateLegacyBlockValidSpawnOverrides(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("isValidSpawn") || !executableCode.contains("EntityType<?>")) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(
            ParserConfiguration.LanguageLevel.BLEEDING_EDGE
        )
    )
    val parsed = parser.parse(source)
    val compilationUnit = parsed.result.orElseThrow {
        IllegalStateException(
            "Cannot parse legacy Block isValidSpawn override: " +
                parsed.problems.joinToString()
        )
    }

    fun exactType(
        typeName: String,
        simpleName: String,
        qualifiedName: String
    ): Boolean {
        val normalized = typeName.replace(" ", "")
        val qualifiedImport = qualifiedName.substringBefore('<')
        return normalized == qualifiedName ||
            normalized == simpleName &&
            compilationUnit.imports.any {
                !it.isStatic && it.nameAsString == qualifiedImport
            }
    }

    fun exactSpawnType(typeName: String): Boolean {
        val normalized = typeName.replace(" ", "")
        return exactType(
            normalized,
            "Type",
            "net.minecraft.world.entity.SpawnPlacements.Type"
        ) || normalized == "SpawnPlacements.Type" &&
            compilationUnit.imports.any {
                !it.isStatic &&
                    it.nameAsString == "net.minecraft.world.entity.SpawnPlacements"
            }
    }

    LexicalPreservingPrinter.setup(compilationUnit)
    var changed = false
    compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java)
        .forEach typeLoop@{ owner ->
            val methods = owner.methods.filter {
                it.nameAsString == "isValidSpawn"
            }
            if (methods.isEmpty()) return@typeLoop
            val candidates = methods.filter { method ->
                method.typeAsString == "boolean" &&
                    method.parameters.size in 4..5 &&
                    exactType(
                        method.parameters[0].typeAsString,
                        "BlockState",
                        "net.minecraft.world.level.block.state.BlockState"
                    ) &&
                    exactType(
                        method.parameters[1].typeAsString,
                        "BlockGetter",
                        "net.minecraft.world.level.BlockGetter"
                    ) &&
                    exactType(
                        method.parameters[2].typeAsString,
                        "BlockPos",
                        "net.minecraft.core.BlockPos"
                    ) &&
                    exactType(
                        method.parameters.last().typeAsString,
                        "EntityType<?>",
                        "net.minecraft.world.entity.EntityType<?>"
                    ) &&
                    (
                        method.parameters.size == 4 ||
                            exactSpawnType(method.parameters[3].typeAsString)
                        )
            }
            if (candidates.isEmpty()) return@typeLoop
            if (candidates.size != 1 || methods.size != 1) {
                throw IllegalStateException(
                    "Legacy Block isValidSpawn owner has multiple overloads: " +
                        owner.nameAsString
                )
            }
            val method = candidates.single()
            if (method.annotations.none { it.nameAsString == "Override" }) {
                throw IllegalStateException(
                    "Legacy Block isValidSpawn method is not a proven override: " +
                        owner.nameAsString
                )
            }
            val extended = owner.extendedTypes.singleOrNull()?.nameWithScope
            if (extended == null ||
                !exactType(
                    extended,
                    "Block",
                    "net.minecraft.world.level.block.Block"
                )
            ) {
                throw IllegalStateException(
                    "Legacy isValidSpawn owner is not proven to directly extend " +
                        "Minecraft Block: ${owner.nameAsString}"
                )
            }
            val statements = method.body.orElse(null)?.statements
                ?: throw IllegalStateException(
                    "Legacy Block isValidSpawn method has no body: " +
                        owner.nameAsString
                )
            if (statements.size != 1) {
                throw IllegalStateException(
                    "Legacy Block isValidSpawn predicate has nontrivial control flow: " +
                        owner.nameAsString
                )
            }
            val returnExpression =
                (statements.single() as? ReturnStmt)?.expression?.orElse(null)
                    ?: throw IllegalStateException(
                        "Legacy Block isValidSpawn body is not one return: " +
                            owner.nameAsString
                    )
            val lambdaParameters = listOf(
                method.parameters[0],
                method.parameters[1],
                method.parameters[2],
                method.parameters.last()
            )
            val allowedNames = lambdaParameters
                .map { it.nameAsString }
                .toSet()
            val unsupportedNames = returnExpression
                .findAll(NameExpr::class.java)
                .map { it.nameAsString }
                .filterNot { it in allowedNames }
                .toSet()
            if (unsupportedNames.isNotEmpty() ||
                returnExpression.findAll(ThisExpr::class.java).isNotEmpty()
            ) {
                throw IllegalStateException(
                    "Legacy Block isValidSpawn predicate depends on state " +
                        "unavailable before super(): " +
                        unsupportedNames.sorted().joinToString()
                )
            }
            val lambda = StaticJavaParser.parseExpression<LambdaExpr>(
                "(${lambdaParameters.joinToString(", ") { it.nameAsString }}) -> " +
                    returnExpression
            )

            var directConstructors = 0
            owner.constructors.forEach constructorLoop@{ constructor ->
                val superCall = constructor.body.statements.firstOrNull()
                    as? ExplicitConstructorInvocationStmt
                    ?: throw IllegalStateException(
                        "Legacy Block isValidSpawn constructor has no explicit " +
                            "source-proven delegation: ${owner.nameAsString}"
                    )
                if (superCall.isThis) return@constructorLoop
                directConstructors++
                if (superCall.arguments.size != 1) {
                    throw IllegalStateException(
                        "Legacy Block isValidSpawn constructor does not call " +
                            "Block(Properties): ${owner.nameAsString}"
                    )
                }
                val properties = superCall.arguments.single()
                if (properties is MethodCallExpr &&
                    properties.nameAsString == "isValidSpawn"
                ) {
                    throw IllegalStateException(
                        "Legacy Block isValidSpawn constructor already configures " +
                            "a spawn predicate: ${owner.nameAsString}"
                    )
                }
                superCall.setArgument(
                    0,
                    MethodCallExpr(
                        properties.clone(),
                        "isValidSpawn"
                    ).addArgument(lambda.clone())
                )
            }
            if (directConstructors == 0) {
                throw IllegalStateException(
                    "Legacy Block isValidSpawn owner has no direct constructor path: " +
                        owner.nameAsString
                )
            }
            method.remove()
            changed = true
        }
    if (!changed) return source

    var result = LexicalPreservingPrinter.print(compilationUnit)
    result = replaceExecutableJavaRegex(
        result,
        Regex(
            """(\.isValidSpawn\s*\(\s*\()\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*->"""
        )
    ) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}, " +
            "${match.groupValues[3]}, ${match.groupValues[4]}, " +
            "${match.groupValues[5]}) ->"
    }
    result = removeImport(
        result,
        "net.minecraft.world.entity.SpawnPlacements.Type"
    )
    val withoutSpawnPlacements = removeImport(
        result,
        "net.minecraft.world.entity.SpawnPlacements"
    )
    result = if (!Regex("""\bSpawnPlacements\b""").containsMatchIn(
            maskJavaCommentsAndLiterals(withoutSpawnPlacements)
        )
    ) {
        withoutSpawnPlacements
    } else {
        result
    }
    val withoutEntityType = removeImport(result, "net.minecraft.world.entity.EntityType")
    result = if (!Regex("""\bEntityType\b""").containsMatchIn(
            maskJavaCommentsAndLiterals(withoutEntityType)
        )
    ) {
        withoutEntityType
    } else {
        result
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBlockAndEntityCapabilityAccessors(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY,
    generatedCompatPackage: () -> String = { "" },
    entityCapabilityTypes: Map<String, String> = emptyMap()
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getCapability(") && !executableCode.contains("getCapability(")) return source

    var result = source
    val blockEntityAccesses = collectBlockEntityAccesses(executableCode)
    val blockEntityVariables = collectBlockEntityVariables(executableCode)
    var needsServerLevel = false
    var needsItemHandler = false
    var handlerCounter = 0
    var compatPackageCache = generatedCompatPackageImportedBy(source)
    fun compatPackage(): String {
        val existing = compatPackageCache
        if (existing != null) return existing
        val detected = generatedCompatPackage()
        compatPackageCache = detected
        return detected
    }

    val blockIfPresentPattern = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.getCapability\(\s*Capabilities\.ItemHandler\.BLOCK\s*,\s*((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)\s*\)\.ifPresent\(([^;\r\n]+)\);\s*$"""
    )
    val blockEdits = blockIfPresentPattern.findAll(executableCode).mapNotNull { match ->
        val receiver = match.groupValues[2]
        val access = blockEntityAccesses[receiver] ?: return@mapNotNull null
        val side = match.groupValues[3]
        val consumerRange = match.groups[4]?.range ?: return@mapNotNull null
        val consumer = source.substring(consumerRange.first, consumerRange.last + 1).trim()
        val indent = match.groupValues[1]
        val handlerVar = "modporterItemHandler${handlerCounter++}"
        needsServerLevel = needsServerLevel || !access.levelIsServerLevel
        needsItemHandler = true
        val statement = consumerToItemHandlerStatement(consumer, handlerVar)
            ?: "$consumer.accept($handlerVar);"
        val replacement = if (access.levelIsServerLevel) {
            """${indent}IItemHandler $handlerVar = net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, ${access.levelExpression}, ${access.positionExpression}, $side).getCapability();
${indent}if ($handlerVar != null) $statement"""
        } else {
            """${indent}if (${access.levelExpression} instanceof ServerLevel modporterServerLevel) {
${indent}    IItemHandler $handlerVar = net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, modporterServerLevel, ${access.positionExpression}, $side).getCapability();
${indent}    if ($handlerVar != null) $statement
${indent}}"""
        }
        match.range to replacement
    }.toList()
    result = applyStringEdits(result, blockEdits)

    val entityIfPresentPattern = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.getCapability\(\s*Capabilities\.ItemHandler\.BLOCK\s*,\s*([A-Za-z_$][\w$]*)\s*\)\.ifPresent\(([^;\r\n]+)\);\s*$"""
    )
    val executableAfterBlockEdits = maskJavaCommentsAndLiterals(result)
    val entityEdits = entityIfPresentPattern.findAll(executableAfterBlockEdits).mapNotNull { match ->
        val receiver = match.groupValues[2]
        if (receiver in blockEntityVariables ||
            !isEntityCapabilityReceiver(executableAfterBlockEdits, match.range.first, receiver, javaInheritanceIndex)) {
            return@mapNotNull null
        }
        val side = match.groupValues[3]
        val consumerRange = match.groups[4]?.range ?: return@mapNotNull null
        val consumer = result.substring(consumerRange.first, consumerRange.last + 1).trim()
        val indent = match.groupValues[1]
        val handlerVar = "modporterItemHandler${handlerCounter++}"
        needsItemHandler = true
        val statement = consumerToItemHandlerStatement(consumer, handlerVar)
            ?: "$consumer.accept($handlerVar);"
        val replacement = """${indent}IItemHandler $handlerVar = $receiver.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, $side);
${indent}if ($handlerVar != null) $statement"""
        match.range to replacement
    }.toList()
    result = applyStringEdits(result, entityEdits)

    var wrappedBlockCapability = false
    var wrappedItemCapability = false
    var wrappedEntityCapability = false
    result = rewriteExecutableJavaCallWithOffset(result, "ifPresent") { receiver, args, offset ->
        val capability = parseLegacyBlockCapabilityReceiver(receiver) ?: return@rewriteExecutableJavaCallWithOffset null
        if (!isBlockEntityCapabilityReceiver(maskJavaCommentsAndLiterals(result), offset, capability.first, javaInheritanceIndex)) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        val capabilityExpression = blockCapabilityExpression(capability.third, capability.first, capability.second)
        wrappedBlockCapability = true
        "${compatPackage()}.LazyOptional.ofNullable($capabilityExpression).ifPresent(${args.joinToString(", ")})"
    }
    result = rewriteExecutableJavaCallWithOffset(result, "ifPresent") { receiver, args, _ ->
        val capability = parseLegacyItemStackCapabilityReceiver(receiver) ?: return@rewriteExecutableJavaCallWithOffset null
        if (!isItemStackCapabilityReceiver(result, capability.first)) return@rewriteExecutableJavaCallWithOffset null
        wrappedItemCapability = true
        "${compatPackage()}.LazyOptional.ofNullable(${capability.first}.getCapability(${capability.second})).ifPresent(${args.joinToString(", ")})"
    }
    // a variable declared LazyOptional<...> that is assigned an item capability lookup holds the
    // nullable 1.21 result (ItemStack#getCapability(ItemCapability) returns the handler or null)
    run {
        val masked = maskJavaCommentsAndLiterals(result)
        val lazyVariables = Regex("""\bLazyOptional\s*<[^;=()]*>\s+([A-Za-z_$][\w$]*)\s*[=;,)]""").findAll(masked).map { it.groupValues[1] }.toSet()
        if (lazyVariables.isNotEmpty()) {
            val names = lazyVariables.joinToString("|") { Regex.escape(it) }
            val assignment = Regex("""(?<![\w$.])(?:$names)\s*=\s*(([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*)\.getCapability\(\s*((?:net\.neoforged\.neoforge\.capabilities\.)?Capabilities\.[A-Za-z]+\.ITEM)\s*\))\s*;""")
            val edits = assignment.findAll(masked).mapNotNull { match ->
                val stack = match.groupValues[2]
                if (!isItemStackCapabilityReceiver(result, stack)) return@mapNotNull null
                val call = match.groups[1]!!.range
                wrappedItemCapability = true
                call to "${compatPackage()}.LazyOptional.ofNullable(${result.substring(call)})"
            }.toList()
            result = applyStringEdits(result, edits)
        }
    }
    listOf("ifPresent", "orElseThrow", "orElse", "map", "resolve", "isPresent").forEach { methodName ->
        result = rewriteExecutableJavaCallWithOffset(result, methodName) { receiver, args, offset ->
            if (receiver.contains("LazyOptional.ofNullable")) return@rewriteExecutableJavaCallWithOffset null
            val directBlockCapability = parseDirectLevelBlockEntityCapabilityReceiver(receiver)
            if (directBlockCapability != null) {
                wrappedBlockCapability = true
                val capabilityExpression = directBlockCapability.levelBlockCapabilityExpression()
                return@rewriteExecutableJavaCallWithOffset "${compatPackage()}.LazyOptional.ofNullable($capabilityExpression).$methodName(${args.joinToString(", ")})"
            }
            val capability = parseLegacyBlockCapabilityReceiver(receiver) ?: return@rewriteExecutableJavaCallWithOffset null
            if (!isBlockEntityCapabilityReceiver(maskJavaCommentsAndLiterals(result), offset, capability.first, javaInheritanceIndex)) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val capabilityExpression = blockCapabilityExpression(capability.third, capability.first, capability.second)
            wrappedBlockCapability = true
            "${compatPackage()}.LazyOptional.ofNullable($capabilityExpression).$methodName(${args.joinToString(", ")})"
        }
        result = rewriteExecutableJavaCallWithOffset(result, methodName) { receiver, args, offset ->
            if (receiver.contains("LazyOptional.ofNullable")) return@rewriteExecutableJavaCallWithOffset null
            val capability = parseKnownEntityCapabilityReceiver(receiver, entityCapabilityTypes)
                ?: return@rewriteExecutableJavaCallWithOffset null
            if (!isEntityCapabilityReceiverExpression(
                    maskJavaCommentsAndLiterals(result),
                    offset,
                    capability.receiver,
                    javaInheritanceIndex
                )
            ) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            wrappedEntityCapability = true
            "${compatPackage()}.LazyOptional.ofNullable(${capability.call}).$methodName(${args.joinToString(", ")})"
        }
    }

    val executableAfterWrappedIfPresent = maskJavaCommentsAndLiterals(result)
    val lazyBlockCapabilityPattern = Regex(
        """\b((?:com\.modporter\.generated\.[A-Za-z_$][\w$]*\.compat\.)?LazyOptional\s*<\s*(?:IItemHandler|IFluidHandler)\s*>\s+[A-Za-z_$][\w$]*\s*=\s*)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.getCapability\(\s*(Capabilities\.(?:ItemHandler|FluidHandler)\.BLOCK)\s*(?:,\s*([^;\r\n()]+?))?\s*\)\s*;"""
    )
    val lazyBlockEdits = lazyBlockCapabilityPattern.findAll(executableAfterWrappedIfPresent).mapNotNull { match ->
        val receiver = match.groupValues[2]
        if (!isBlockEntityCapabilityReceiver(executableAfterWrappedIfPresent, match.range.first, receiver, javaInheritanceIndex)) {
            return@mapNotNull null
        }
        val capability = match.groupValues[3].trim()
        val side = match.groupValues.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() }
        val capabilityExpression = blockCapabilityExpression(capability, receiver, side)
        wrappedBlockCapability = true
        match.range to "${match.groupValues[1]}${compatPackage()}.LazyOptional.ofNullable($capabilityExpression);"
    }.toList()
    result = applyStringEdits(result, lazyBlockEdits)

    val executableAfterLazyBlockEdits = maskJavaCommentsAndLiterals(result)
    val lazyEntityCapabilityPattern = Regex(
        """\b((?:com\.modporter\.generated\.[A-Za-z_$][\w$]*\.compat\.)?LazyOptional\s*<\s*([^>;\r\n]+)\s*>\s+[A-Za-z_$][\w$]*\s*=\s*)((?:[^;\r\n()]|\([^;\r\n()]*\))*?getCapability\((?:[^()]|\([^()]*\))*\))\s*;"""
    )
    val lazyEntityEdits = lazyEntityCapabilityPattern.findAll(executableAfterLazyBlockEdits).mapNotNull { match ->
        val callRange = match.groups[3]?.range ?: return@mapNotNull null
        val call = result.substring(callRange.first, callRange.last + 1).trim()
        val capability = parseKnownEntityCapabilityReceiver(call, entityCapabilityTypes) ?: return@mapNotNull null
        if (!isEntityCapabilityReceiverExpression(
                executableAfterLazyBlockEdits,
                match.range.first,
                capability.receiver,
                javaInheritanceIndex
            )
        ) {
            return@mapNotNull null
        }
        val declaredType = match.groupValues[2].trim().substringAfterLast('.')
        val capabilityType = capability.valueType.substringAfterLast('.')
        if (declaredType != capabilityType) return@mapNotNull null
        wrappedEntityCapability = true
        match.range to "${match.groupValues[1]}${compatPackage()}.LazyOptional.ofNullable($call);"
    }.toList()
    result = applyStringEdits(result, lazyEntityEdits)

    val executableAfterEntityEdits = maskJavaCommentsAndLiterals(result)
    val entityNoSidePattern = Regex(
        """\b([A-Za-z_$][\w$]*)\.getCapability\(\s*Capabilities\.ItemHandler\.BLOCK\s*\)"""
    )
    val entityNoSideEdits = entityNoSidePattern.findAll(executableAfterEntityEdits).mapNotNull { match ->
        val receiver = match.groupValues[1]
        if (receiver in blockEntityVariables ||
            !isEntityCapabilityReceiver(executableAfterEntityEdits, match.range.first, receiver, javaInheritanceIndex)) {
            return@mapNotNull null
        }
        match.range to "$receiver.getCapability(Capabilities.ItemHandler.ENTITY)"
    }.toList()
    result = applyStringEdits(result, entityNoSideEdits)

    val entityCapabilitySideMigrated = migrateSidedEntityCapabilityCalls(result, entityCapabilityTypes, javaInheritanceIndex)
    val addedEntityCapabilitySide = entityCapabilitySideMigrated != result
    result = entityCapabilitySideMigrated

    if (blockEdits.isEmpty() && entityEdits.isEmpty() && entityNoSideEdits.isEmpty() &&
        !wrappedBlockCapability && !wrappedItemCapability && !wrappedEntityCapability && !addedEntityCapabilitySide
    ) return source
    if (needsServerLevel) result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    if (needsItemHandler) result = addImportIfMissing(result, "net.neoforged.neoforge.items.IItemHandler")
    return result
}

internal data class ParsedEntityCapabilityCall(
    val receiver: String,
    val call: String,
    val valueType: String
)

internal data class DirectLevelBlockCapabilityCall(
    val levelExpression: String,
    val positionExpression: String,
    val capability: String,
    val side: String?
) {
    fun levelBlockCapabilityExpression(): String =
        "$levelExpression.getCapability($capability, $positionExpression, ${side ?: "null"})"
}

internal fun StructuralRefactorPass.generatedCompatPackageImportedBy(source: String): String? =
    Regex("""(?m)^\s*import\s+(com\.modporter\.generated\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.compat)\.LazyOptional\s*;""")
        .find(source)
        ?.groupValues
        ?.get(1)

internal fun StructuralRefactorPass.parseKnownEntityCapabilityReceiver(
    expression: String,
    entityCapabilityTypes: Map<String, String>
): ParsedEntityCapabilityCall? {
    if (entityCapabilityTypes.isEmpty()) return null
    val trimmed = expression.trim()
    val token = ".getCapability("
    val tokenIndex = trimmed.lastIndexOf(token)
    val receiver: String
    val argsStart: Int
    if (tokenIndex >= 0) {
        receiver = trimmed.substring(0, tokenIndex).trim()
        argsStart = tokenIndex + ".getCapability".length
    } else if (trimmed.startsWith("getCapability(")) {
        receiver = "this"
        argsStart = "getCapability".length
    } else {
        return null
    }
    val closeParen = findMatchingParen(trimmed, argsStart)
    if (closeParen != trimmed.length - 1) return null
    val args = splitTopLevelJavaArgs(trimmed.substring(argsStart + 1, closeParen))
    if (args.isEmpty() || args.size > 2) return null
    val capability = args[0].trim()
    val valueType = entityCapabilityTypes[capability]
        ?: entityCapabilityTypes[capability.substringAfterLast('.')]
        ?: return null
    return ParsedEntityCapabilityCall(receiver, trimmed, valueType)
}

internal fun StructuralRefactorPass.parseLegacyBlockCapabilityReceiver(expression: String): Triple<String, String?, String>? {
    val token = ".getCapability("
    val tokenIndex = expression.lastIndexOf(token)
    if (tokenIndex < 0 || !expression.trimEnd().endsWith(")")) return null
    val receiver = expression.substring(0, tokenIndex).trim()
    val argsStart = tokenIndex + ".getCapability".length
    val closeParen = findMatchingParen(expression, argsStart)
    if (closeParen != expression.length - 1) return null
    val args = splitTopLevelJavaArgs(expression.substring(argsStart + 1, closeParen))
    if (args.isEmpty()) return null
    val capability = args[0].trim()
    if (capability != "Capabilities.ItemHandler.BLOCK" && capability != "Capabilities.FluidHandler.BLOCK") return null
    if (args.size > 2) return null
    return Triple(receiver, args.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }, capability)
}

internal fun StructuralRefactorPass.parseDirectLevelBlockEntityCapabilityReceiver(expression: String): DirectLevelBlockCapabilityCall? {
    val parsedCapability = parseLegacyBlockCapabilityReceiver(expression) ?: return null
    val receiver = parsedCapability.first
    val token = ".getBlockEntity("
    val tokenIndex = receiver.lastIndexOf(token)
    if (tokenIndex <= 0 || !receiver.trimEnd().endsWith(")")) return null
    val levelExpression = receiver.substring(0, tokenIndex).trim()
    if (levelExpression.isBlank()) return null
    val argsStart = tokenIndex + ".getBlockEntity".length
    val closeParen = findMatchingParen(receiver, argsStart)
    if (closeParen != receiver.length - 1) return null
    val args = splitTopLevelJavaArgs(receiver.substring(argsStart + 1, closeParen))
    if (args.size != 1) return null
    return DirectLevelBlockCapabilityCall(
        levelExpression = levelExpression,
        positionExpression = args.single().trim(),
        capability = parsedCapability.third,
        side = parsedCapability.second
    )
}

internal fun StructuralRefactorPass.parseLegacyItemStackCapabilityReceiver(expression: String): Pair<String, String>? {
    val token = ".getCapability("
    val tokenIndex = expression.lastIndexOf(token)
    if (tokenIndex < 0 || !expression.trimEnd().endsWith(")")) return null
    val receiver = expression.substring(0, tokenIndex).trim()
    val argsStart = tokenIndex + ".getCapability".length
    val closeParen = findMatchingParen(expression, argsStart)
    if (closeParen != expression.length - 1) return null
    val args = splitTopLevelJavaArgs(expression.substring(argsStart + 1, closeParen))
    if (args.size != 1) return null
    val capability = args[0].trim()
    if (capability != "Capabilities.ItemHandler.ITEM" && capability != "Capabilities.FluidHandler.ITEM") return null
    return receiver to capability
}

internal fun StructuralRefactorPass.blockCapabilityExpression(capability: String, receiver: String, side: String?): String {
    val context = side ?: "null"
    return "$capability.getCapability($receiver.getLevel(), $receiver.getBlockPos(), $receiver.getBlockState(), $receiver, $context)"
}

internal fun StructuralRefactorPass.isItemStackCapabilityReceiver(source: String, receiver: String): Boolean {
    val normalized = receiver.trim()
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (Regex("""\bItemStack\s+${Regex.escape(normalized)}\b""").containsMatchIn(executableCode)) return true
    return normalized.endsWith(".getMainHandItem()") ||
        normalized.endsWith(".getOffhandItem()") ||
        normalized.endsWith(".getItemBySlot(")
}

internal fun StructuralRefactorPass.migrateSidedEntityCapabilityCalls(
    source: String,
    entityCapabilityTypes: Map<String, String>,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (entityCapabilityTypes.isEmpty() || !source.contains("getCapability(")) return source
    var result = source
    result = rewriteExecutableJavaCallWithOffset(result, "getCapability") { receiver, args, offset ->
        if (args.size != 1 || !isKnownSidedEntityCapability(args[0], entityCapabilityTypes)) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        if (!isEntityCapabilityReceiverExpression(maskJavaCommentsAndLiterals(result), offset, receiver, javaInheritanceIndex)) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        "$receiver.getCapability(${args[0].trim()}, null)"
    }
    val implicitPattern = Regex("""(?<![.\w$])getCapability\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*\)""")
    result = replaceExecutableRegex(result, implicitPattern) { match ->
        val capability = match.groupValues[1]
        if (!isKnownSidedEntityCapability(capability, entityCapabilityTypes) ||
            !isEntityCapabilityReceiverExpression(maskJavaCommentsAndLiterals(result), match.range.first, "this", javaInheritanceIndex)
        ) {
            return@replaceExecutableRegex match.value
        }
        "getCapability($capability, null)"
    }
    return result
}

internal fun StructuralRefactorPass.isKnownSidedEntityCapability(
    expression: String,
    entityCapabilityTypes: Map<String, String>
): Boolean {
    val normalized = expression.trim()
    return entityCapabilityTypes.containsKey(normalized) ||
        entityCapabilityTypes.containsKey(normalized.substringAfterLast('.'))
}

internal fun StructuralRefactorPass.isEntityCapabilityReceiverExpression(
    executableCode: String,
    offset: Int,
    receiver: String,
    javaInheritanceIndex: JavaInheritanceIndex
): Boolean {
    val normalized = receiver.trim()
    val thisCast = Regex("""^\(\s*\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\)\s*this\s*\)$""").find(normalized)
    if (thisCast != null) {
        val castType = thisCast.groupValues[1].substringAfterLast('.')
        if (castType in javaEntityBaseTypes() || javaInheritanceIndex.inherits(castType, javaEntityBaseTypes())) {
            return true
        }
        return isEntityCapabilityReceiver(executableCode, offset, "this", javaInheritanceIndex)
    }
    if (normalized == "this") {
        return isEntityCapabilityReceiver(executableCode, offset, "this", javaInheritanceIndex)
    }
    if (Regex("""^[A-Za-z_$][\w$]*$""").matches(normalized)) {
        return isEntityCapabilityReceiver(executableCode, offset, normalized, javaInheritanceIndex)
    }
    return false
}

internal data class BlockEntityAccess(
    val levelExpression: String,
    val positionExpression: String,
    val levelIsServerLevel: Boolean
)

internal fun StructuralRefactorPass.collectBlockEntityAccesses(source: String): Map<String, BlockEntityAccess> {
    val accesses = mutableMapOf<String, BlockEntityAccess>()
    val serverLevelVariables = Regex("""\b(?:net\.minecraft\.server\.level\.)?ServerLevel\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    val id = """[A-Za-z_$][\w$]*"""
    val accessExpression = """(?:this|$id)(?:\.$id(?:\(\))?)*"""
    Regex(
        """\b(?:net\.minecraft\.world\.level\.block\.entity\.)?BlockEntity\s+($id)\s*=\s*($accessExpression)\.getBlockEntity\(\s*($accessExpression)\s*\)\s*;"""
    ).findAll(source).forEach { match ->
        val levelExpression = match.groupValues[2]
        accesses[match.groupValues[1]] = BlockEntityAccess(
            levelExpression = levelExpression,
            positionExpression = match.groupValues[3],
            levelIsServerLevel = levelExpression in serverLevelVariables
        )
    }
    return accesses
}

internal fun StructuralRefactorPass.collectBlockEntityVariables(source: String): Set<String> =
    Regex("""\b(?:net\.minecraft\.world\.level\.block\.entity\.)?BlockEntity\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()

internal fun StructuralRefactorPass.isBlockEntityCapabilityReceiver(
    executableCode: String,
    offset: Int,
    receiver: String,
    javaInheritanceIndex: JavaInheritanceIndex
): Boolean {
    val blockEntityBaseTypes = setOf("BlockEntity")
    if (receiver == "this") {
        val declaration = enclosingJavaClassDeclaration(executableCode, offset) ?: return false
        return javaClassExtendsAny(declaration, blockEntityBaseTypes, javaInheritanceIndex)
    }
    val simpleReceiver = receiver.substringAfterLast('.')
    val scope = enclosingMethodText(executableCode, offset) ?: executableCode
    val localType = javaLocalVariableTypes(scope)[simpleReceiver]
    if (localType != null &&
        (localType in blockEntityBaseTypes || javaInheritanceIndex.inherits(localType, blockEntityBaseTypes))) {
        return true
    }
    val sourceType = javaLocalVariableTypes(executableCode)[simpleReceiver]
    return sourceType != null &&
        (sourceType in blockEntityBaseTypes || javaInheritanceIndex.inherits(sourceType, blockEntityBaseTypes))
}

internal fun StructuralRefactorPass.isEntityCapabilityReceiver(
    executableCode: String,
    offset: Int,
    receiver: String,
    javaInheritanceIndex: JavaInheritanceIndex
): Boolean {
    val entityBaseTypes = javaEntityBaseTypes()
    if (receiver == "this") {
        val declaration = enclosingJavaClassDeclaration(executableCode, offset) ?: return false
        return javaClassExtendsAny(declaration, entityBaseTypes, javaInheritanceIndex)
    }
    val scope = enclosingMethodText(executableCode, offset) ?: executableCode
    val receiverType = javaLocalVariableTypes(scope)[receiver]
    if (receiverType != null &&
        (receiverType in entityBaseTypes || javaInheritanceIndex.inherits(receiverType, entityBaseTypes))) {
        return true
    }
    return isEntityCollectionForEachLambdaReceiver(scope, receiver)
}

internal fun StructuralRefactorPass.isEntityCollectionForEachLambdaReceiver(scope: String, receiver: String): Boolean {
    val escapedReceiver = Regex.escape(receiver)
    val entityType = """(?:net\.minecraft\.world\.entity\.)?Entity"""
    return Regex(
        """(?s)\.getEntities\s*\(\s*\(\s*$entityType\s*\)\s*null\s*,.*?\)\s*\.forEach\s*\(\s*$escapedReceiver\s*->"""
    ).containsMatchIn(scope) ||
        Regex(
            """(?s)\.getEntitiesOfClass\s*\(\s*$entityType\.class\s*,.*?\)\s*\.forEach\s*\(\s*$escapedReceiver\s*->"""
        ).containsMatchIn(scope)
}

internal fun StructuralRefactorPass.consumerToItemHandlerStatement(consumer: String, handlerVar: String): String? {
    Regex("""^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::([A-Za-z_$][\w$]*)$""")
        .find(consumer)
        ?.let { return "${it.groupValues[1]}.${it.groupValues[2]}($handlerVar);" }
    Regex("""^([A-Za-z_$][\w$]*)\s*->\s*(.+)$""")
        .find(consumer)
        ?.let { match ->
            val parameter = match.groupValues[1]
            val body = match.groupValues[2].trim()
            return Regex("""\b${Regex.escape(parameter)}\b""").replace(body, handlerVar).let {
                if (it.endsWith(";")) it else "$it;"
            }
    }
    return null
}

internal fun StructuralRefactorPass.migrateLegacyEntityPortalProcessorAccessors(source: String): String {
    if (!source.contains("@Mixin(Entity.class)") &&
        !source.contains("@Mixin(net.minecraft.world.entity.Entity.class)")) {
        return source
    }
    if (!source.contains("portalEntrancePos") && !source.contains("isInsidePortal")) return source

    var result = source
    var needsPortalProcessAccessor = false

    result = Regex(
        """(?m)^([ \t]*)@Accessor\(\s*"portalEntrancePos"\s*\)\s*\r?\n[ \t]*BlockPos\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*;[ \t]*(?:\r?\n)?"""
    ).replace(result) { _ ->
        needsPortalProcessAccessor = true
        ""
    }
    result = Regex(
        """(?m)^([ \t]*)@Accessor\(\s*"portalEntrancePos"\s*\)\s*\r?\n[ \t]*void\s+([A-Za-z_$][\w$]*)\s*\(\s*BlockPos\s+([A-Za-z_$][\w$]*)\s*\)\s*;[ \t]*(?:\r?\n)?"""
    ).replace(result) { _ ->
        needsPortalProcessAccessor = true
        ""
    }
    result = Regex(
        """(?m)^([ \t]*)@Accessor\(\s*"isInsidePortal"\s*\)\s*\r?\n[ \t]*boolean\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*;[ \t]*(?:\r?\n)?"""
    ).replace(result) { _ ->
        needsPortalProcessAccessor = true
        ""
    }

    if (needsPortalProcessAccessor &&
        !Regex("""PortalProcessor\s+modporter\${'$'}getPortalProcess\s*\(\s*\)\s*;""").containsMatchIn(result)) {
        val accessor = """

    @Accessor("portalProcess")
    PortalProcessor modporter${'$'}getPortalProcess();
""".trimEnd()
        result = insertBeforeLastClassBrace(result, accessor)
    }
    if (needsPortalProcessAccessor) {
        result = addImportIfMissing(result, "net.minecraft.world.entity.PortalProcessor")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyEntityPortalProcessorCallSites(source: String): String {
    val legacyGetterNames = Regex("""([A-Za-z_${'$'}][\w${'$'}]*\${'$'}getPortalEntrancePos)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    val legacySetterNames = Regex("""([A-Za-z_${'$'}][\w${'$'}]*\${'$'}setPortalEntrancePos)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    val legacyInsidePortalNames = Regex("""([A-Za-z_${'$'}][\w${'$'}]*\${'$'}isIsInsidePortal)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (legacyGetterNames.isEmpty() && legacySetterNames.isEmpty() && legacyInsidePortalNames.isEmpty()) return source

    var result = source
    legacyGetterNames.forEach { methodName ->
        result = rewriteJavaCall(result, methodName) { receiver, args ->
            if (args.isNotEmpty()) return@rewriteJavaCall null
            "($receiver.modporter${'$'}getPortalProcess() != null ? $receiver.modporter${'$'}getPortalProcess().getEntryPosition() : null)"
        }
    }
    legacySetterNames.forEach { methodName ->
        result = rewriteJavaCall(result, methodName) { receiver, args ->
            val entryPos = args.singleOrNull()?.trim() ?: return@rewriteJavaCall null
            "java.util.Optional.ofNullable($receiver.modporter${'$'}getPortalProcess()).ifPresent(portalProcess -> portalProcess.updateEntryPosition($entryPos))"
        }
    }
    legacyInsidePortalNames.forEach { methodName ->
        result = rewriteJavaCall(result, methodName) { receiver, args ->
            if (args.isNotEmpty()) return@rewriteJavaCall null
            "($receiver.modporter${'$'}getPortalProcess() != null && $receiver.modporter${'$'}getPortalProcess().isInsidePortalThisTick())"
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyCustomPortalBlockProtocol(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getPortalEntrancePos") &&
        !executableCode.contains("setPortalEntrancePos") &&
        !executableCode.contains("portalEntrancePos")) {
        return source
    }
    if (!Regex("""\bclass\s+[A-Za-z_$][\w$]*[^{;\r\n]*\bextends\s+Block\b""").containsMatchIn(executableCode)) {
        return source
    }

    val methods = javaMethodRanges(executableCode)
    val entityInsideMethod = methods.singleOrNull { method ->
        method.name == "entityInside" &&
            Regex("""\bvoid\s+entityInside\s*\(""").containsMatchIn(method.header)
    } ?: return source
    val entityInside = source.substring(entityInsideMethod.range)
    val executableEntityInside = executableCode.substring(entityInsideMethod.range)
    if (!executableEntityInside.contains("setPortalEntrancePos") &&
        !executableEntityInside.contains("getPortalEntrancePos")) {
        return source
    }
    val params = Regex(
        """entityInside\s*\(\s*(?:net\.minecraft\.world\.level\.block\.state\.)?BlockState\s+([A-Za-z_$][\w$]*)\s*,\s*(?:net\.minecraft\.world\.level\.)?Level\s+([A-Za-z_$][\w$]*)\s*,\s*(?:net\.minecraft\.core\.)?BlockPos\s+([A-Za-z_$][\w$]*)\s*,\s*(?:net\.minecraft\.world\.entity\.)?Entity\s+([A-Za-z_$][\w$]*)\s*\)"""
    ).find(executableEntityInside) ?: return source
    val posName = params.groupValues[3]
    val entityName = params.groupValues[4]

    val openBrace = entityInside.indexOf('{')
    if (openBrace <= 0) return source
    val prefix = entityInside.substring(0, openBrace).trimEnd()
    val signatureIndent = Regex("""(?m)^([ \t]*)(?:public|protected|private)\s+void\s+entityInside""")
        .find(prefix)
        ?.groupValues
        ?.get(1)
        ?: "    "
    val migratedEntityInside = """$prefix {
$signatureIndent    if ($entityName.canUsePortal(false)) {
$signatureIndent        $entityName.setAsInsidePortal(this, $posName);
$signatureIndent    }
$signatureIndent}"""
    var result = source.replaceRange(entityInsideMethod.range, migratedEntityInside)
    result = addPortalInterfaceToBlockClass(result)
    result = addImportIfMissing(result, "net.minecraft.world.level.block.Portal")

    if (!Regex("""\bgetPortalTransitionTime\s*\(""").containsMatchIn(result)) {
        val transitionTimeMethod = """

    @Override
    public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        return entity.getDimensionChangingDelay();
    }
""".trimEnd()
        result = insertBeforeLastClassBrace(result, transitionTimeMethod)
        result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
    }

    if (!Regex("""\bgetPortalDestination\s*\(""").containsMatchIn(result)) {
        val destinationMethod = legacyPortalDestinationMethod(result)
        if (destinationMethod != null) {
            result = insertBeforeLastClassBrace(result, destinationMethod)
            result = addImportIfMissing(result, "net.minecraft.server.level.ServerLevel")
            result = addImportIfMissing(result, "net.minecraft.world.level.portal.DimensionTransition")
            result = addImportIfMissing(result, "javax.annotation.Nullable")
        }
    }

    result = removeUnusedImportsBySimpleNamePattern(result, Regex("""EntityAccessor"""))
    return result
}

internal fun StructuralRefactorPass.migrateLegacyConcretePowderConcreteAccessor(source: String): String {
    val accessorMethods = Regex("""([A-Za-z_${'$'}][\w${'$'}]*\${'$'}getConcrete)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (!source.contains("ConcretePowderBlock") && accessorMethods.isEmpty()) return source

    var result = source
    var accessorChanged = false
    if ((source.contains("@Mixin(ConcretePowderBlock.class)") ||
            source.contains("@Mixin(net.minecraft.world.level.block.ConcretePowderBlock.class)")) &&
        source.contains("@Accessor(\"concrete\")")) {
        result = Regex(
            """(?m)^([ \t]*)BlockState\s+([A-Za-z_$][\w$]*\${'$'}getConcrete)\s*\(\s*\)\s*;"""
        ).replace(result) { match ->
            accessorChanged = true
            "${match.groupValues[1]}Block ${match.groupValues[2]}();"
        }
        if (accessorChanged) {
            result = addImportIfMissing(result, "net.minecraft.world.level.block.Block")
        }
    }

    val callMethods = accessorMethods + Regex("""([A-Za-z_${'$'}][\w${'$'}]*\${'$'}getConcrete)""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    callMethods.forEach { methodName ->
        result = rewriteJavaCallWithOffset(result, methodName) { receiver, args, callOffset ->
            if (args.isNotEmpty()) return@rewriteJavaCallWithOffset null
            val openParen = callOffset + methodName.length + 1
            val closeParen = findMatchingParen(result, openParen)
            if (closeParen < 0) return@rewriteJavaCallWithOffset null
            val next = nextNonWhitespaceIndex(result, closeParen + 1)
            if (next >= 0 && result.startsWith(".defaultBlockState(", next)) return@rewriteJavaCallWithOffset null
            "$receiver.$methodName().defaultBlockState()"
        }
    }
    return result
}

internal fun StructuralRefactorPass.addPortalInterfaceToBlockClass(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val classPattern = Regex("""\bclass\s+([A-Za-z_$][\w$]*)([^{;\r\n]*?)\bextends\s+Block\b([^{;\r\n]*)""")
    val match = classPattern.find(executableCode) ?: return source
    val declaration = source.substring(match.range)
    if (Regex("""\bimplements\b[^{;\r\n]*\bPortal\b""").containsMatchIn(declaration)) return source
    val replacement = if (declaration.contains("implements")) {
        declaration.replace(Regex("""\bimplements\s+"""), "implements Portal, ").trimEnd() + " "
    } else {
        declaration.trimEnd() + " implements Portal "
    }
    return source.replaceRange(match.range, replacement)
}

internal fun StructuralRefactorPass.legacyPortalDestinationMethod(source: String): String? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val handleTeleportationMethod = javaMethodRanges(executableCode).singleOrNull { method ->
        method.name == "handleTeleportation"
    } ?: return null
    val handleTeleportation = source.substring(handleTeleportationMethod.range)
    val handleTeleportationCode = maskJavaCommentsAndLiterals(handleTeleportation)
    val destinationKeyExpression = Regex(
        """ResourceKey\s*<\s*Level\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);"""
    ).find(handleTeleportationCode)
        ?.groups
        ?.get(2)
        ?.range
        ?.let { handleTeleportation.substring(it.first, it.last + 1).trim() }
        ?: return null
    val changeDimensionExpression = extractFirstMethodCallArgument(handleTeleportation, "changeDimension") ?: return null
    val transitionExpression = changeDimensionExpression
        .replace(Regex("""\b[a-zA-Z_$][\w$]*\.getLevel\(\s*destinationKey\s*\)"""), "level.getServer().getLevel(destinationKey)")

    return """

    @Nullable
    @Override
    public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
        ResourceKey<Level> destinationKey = $destinationKeyExpression;
        ServerLevel destinationLevel = level.getServer().getLevel(destinationKey);
        if (destinationLevel != null && !entity.isPassenger() && entity.canChangeDimensions(entity.level(), destinationLevel)) {
            return $transitionExpression;
        }
        return null;
    }
""".trimEnd()
}

internal fun StructuralRefactorPass.extractFirstMethodCallArgument(source: String, methodName: String): String? {
    val code = maskJavaCommentsAndLiterals(source)
    val call = Regex("""\.${Regex.escape(methodName)}\s*\(""").find(code) ?: return null
    val openParen = code.indexOf('(', call.range.last)
    if (openParen < 0) return null
    val closeParen = findMatchingParen(code, openParen)
    if (closeParen < 0) return null
    return source.substring(openParen + 1, closeParen).trim()
}

internal fun StructuralRefactorPass.migrateLegacyItemOnBlockStartBreakOverrides(source: String): String {
    if (!source.contains("onBlockStartBreak")) return source
    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        if (method.name != "onBlockStartBreak") continue
        val methodText = result.substring(method.range)
        val parameters = javaMethodParameters(methodText)
        if (parameters.size != 3) continue
        val stack = parameters[0].takeIf { it.type.endsWithJavaType("ItemStack") }?.name ?: continue
        val pos = parameters[1].takeIf { it.type.endsWithJavaType("BlockPos") }?.name ?: continue
        val player = parameters[2].takeIf { it.type.endsWithJavaType("Player") }?.name ?: continue
        val methodBody = javaMethodBodyText(methodText) ?: continue
        if (Regex("""\breturn\s+true\s*;""").containsMatchIn(maskJavaCommentsAndLiterals(methodBody))) continue

        val finalReturn = Regex(
            """(?m)^[ \t]*return\s+(?:super\.onBlockStartBreak\(\s*${Regex.escape(stack)}\s*,\s*${Regex.escape(pos)}\s*,\s*${Regex.escape(player)}\s*\)|false)\s*;\s*$"""
        ).findAll(methodBody).toList().singleOrNull() ?: continue
        val executableBeforeReturn = maskJavaCommentsAndLiterals(methodBody.substring(0, finalReturn.range.first))
        if (Regex("""\breturn\b""").containsMatchIn(executableBeforeReturn)) continue

        val usedNames = javaDeclaredLocalNames(methodBody) + parameters.map { it.name }.toSet()
        val level = uniqueJavaIdentifier("level", usedNames)
        val state = uniqueJavaIdentifier("state", usedNames + level)
        val entity = uniqueJavaIdentifier("entity", usedNames + level + state)
        val openBrace = methodText.indexOf('{')
        if (openBrace < 0) continue
        val declaration = methodText.substring(0, openBrace).trimEnd()
        val newDeclaration = declaration.replace(
            Regex("""boolean\s+onBlockStartBreak\s*\([^;{}]*\)\s*$"""),
            "boolean mineBlock(ItemStack $stack, Level $level, BlockState $state, BlockPos $pos, LivingEntity $entity)"
        )
        if (newDeclaration == declaration) continue

        var migratedStatements = methodBody.removeRange(finalReturn.range)
        migratedStatements = Regex("""\b${Regex.escape(player)}\.level\(\)""").replace(migratedStatements, level)
        migratedStatements = Regex("""\b${Regex.escape(level)}\.getBlockState\(\s*${Regex.escape(pos)}\s*\)""")
            .replace(migratedStatements, state)
        val normalizedStatements = migratedStatements.trim().trimIndent()
        val bodyPrefix = if (normalizedStatements.isBlank()) {
            ""
        } else {
            val indented = indentJavaBlock(normalizedStatements, "        ")
            "    if ($entity instanceof Player $player) {\n$indented\n    }\n"
        }
        val migratedMethod = """
$newDeclaration {
$bodyPrefix    return super.mineBlock($stack, $level, $state, $pos, $entity);
}
""".trim()
        result = result.substring(0, method.range.first) + migratedMethod + result.substring(method.range.last + 1)
    }
    if (result == source) return source
    result = addImportIfMissing(result, "net.minecraft.world.entity.LivingEntity")
    result = addImportIfMissing(result, "net.minecraft.world.level.Level")
    result = addImportIfMissing(result, "net.minecraft.world.level.block.state.BlockState")
    return result
}

internal fun String.endsWithJavaType(simpleName: String): Boolean {
    val normalized = replace(Regex("""\s+"""), "")
        .removeSuffix("[]")
        .substringBefore("<")
    return normalized == simpleName || normalized.endsWith(".$simpleName")
}

internal fun StructuralRefactorPass.javaIdentifierTokens(source: String): Set<String> =
    Regex("""\b[A-Za-z_$][\w$]*\b""")
        .findAll(maskJavaCommentsAndLiterals(source))
        .map { it.value }
        .toSet()

internal fun StructuralRefactorPass.javaDeclaredLocalNames(source: String): Set<String> {
    val executable = maskJavaCommentsAndLiterals(source)
    return Regex(
        """(?m)(?:^|[;{}\r\n])\s*(?:final\s+)?(?:[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*(?:\s*<[^;=(){}]+>)?(?:\s*\[\s*])*)\s+([A-Za-z_$][\w$]*)\s*(?==|;|,)"""
    ).findAll(executable)
        .map { it.groupValues[1] }
        .toSet()
}

internal fun StructuralRefactorPass.uniqueJavaIdentifier(base: String, usedNames: Set<String>): String {
    if (base !in usedNames) return base
    var index = 2
    while ("$base$index" in usedNames) index++
    return "$base$index"
}

internal fun StructuralRefactorPass.migrateLegacyRespawnPositionTransitions(source: String): String {
    if (!source.contains("findRespawnPositionAndUseSpawnBlock")) return source
    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        val methodText = result.substring(method.range)
        if (!methodText.contains("findRespawnPositionAndUseSpawnBlock")) continue
        val migrated = migrateLegacyRespawnPositionTransitionsInMethod(methodText)
        if (migrated != methodText) {
            result = result.substring(0, method.range.first) + migrated + result.substring(method.range.last + 1)
        }
    }
    if (result == source) return source
    result = addImportIfMissing(result, "net.minecraft.world.level.portal.DimensionTransition")
    result = removeUnusedSimpleImport(result, "java.util.Optional", "Optional")
    result = removeUnusedSimpleImport(result, "net.minecraft.world.phys.Vec3", "Vec3")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyRespawnPositionTransitionsInMethod(methodText: String): String {
    val assignment = Regex(
        """(?m)^([ \t]*)(?:java\.util\.)?Optional\s*<\s*(?:net\.minecraft\.world\.phys\.)?Vec3\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*Player\.findRespawnPositionAndUseSpawnBlock\(\s*([^;]+?)\s*\)\s*;"""
    ).find(methodText) ?: return methodText
    val args = splitTopLevelJavaArgs(assignment.groupValues[3])
    if (args.size != 5) return methodText
    val levelVar = args[0].trim()
    val blockVar = args[1].trim()
    val angleVar = args[2].trim()
    val forcedVar = args[3].trim()
    val consumeSpawnBlock = args[4].trim()
    val prefix = methodText.substring(0, assignment.range.first)
    val player = respawnOwnerPlayer(prefix, levelVar, blockVar, angleVar, forcedVar) ?: return methodText
    val optionalVar = assignment.groupValues[2]
    val dimensionTransition = uniqueJavaIdentifier("dimensionTransition", javaIdentifierTokens(methodText) - "dimensionTransition")
    val assignmentIndent = assignment.groupValues[1]
    var result = methodText.replaceRange(
        assignment.range,
        "${assignmentIndent}DimensionTransition $dimensionTransition = $player.findRespawnPositionAndUseSpawnBlock($consumeSpawnBlock, DimensionTransition.DO_NOTHING);"
    )
    val searchStart = assignment.range.first
    val executable = maskJavaCommentsAndLiterals(result)
    val ifPresentToken = "$optionalVar.ifPresent("
    val ifPresentIndex = executable.indexOf(ifPresentToken, searchStart)
    if (ifPresentIndex < 0) return result
    val openParen = ifPresentIndex + "$optionalVar.ifPresent".length
    val closeParen = findMatchingParen(executable, openParen)
    if (closeParen < 0) return result
    var statementEnd = closeParen + 1
    while (statementEnd < result.length && result[statementEnd].isWhitespace()) statementEnd++
    if (statementEnd >= result.length || result[statementEnd] != ';') return result
    statementEnd++
    val lambda = result.substring(openParen + 1, closeParen).trim()
    val lambdaMatch = Regex("""^([A-Za-z_$][\w$]*)\s*->\s*\{(?s:(.*))\}$""").find(lambda) ?: return result
    val lambdaVar = lambdaMatch.groupValues[1]
    var lambdaBody = lambdaMatch.groupValues[2].trim().trimIndent()
    val changeDimension = Regex(
        """(?m)^[ \t]*${Regex.escape(player)}\.changeDimension\([^;\r\n]*\b${Regex.escape(lambdaVar)}\b[^;\r\n]*\);\s*"""
    ).find(lambdaBody) ?: return result
    lambdaBody = lambdaBody.removeRange(changeDimension.range).trim().trimIndent()
    val trailingBody = if (lambdaBody.isBlank()) "" else "\n" + indentJavaBlock(lambdaBody, "$assignmentIndent    ")
    val replacement = """
${assignmentIndent}if (!$dimensionTransition.missingRespawnBlock()) {
${assignmentIndent}    $player.changeDimension($dimensionTransition);$trailingBody
${assignmentIndent}}
""".trimEnd()
    return result.substring(0, ifPresentIndex) + replacement + result.substring(statementEnd)
}

internal fun StructuralRefactorPass.respawnOwnerPlayer(
    prefix: String,
    levelVar: String,
    blockVar: String,
    angleVar: String,
    forcedVar: String
): String? {
    val id = """[A-Za-z_$][\w$]*"""
    val levelOwner = Regex("""\b${Regex.escape(levelVar)}\s*=\s*[^;\r\n]*\.getLevel\(\s*($id)\.getRespawnDimension\(\)\s*\)\s*;""")
        .find(prefix)
        ?.groupValues
        ?.get(1)
    val blockOwner = Regex("""\b${Regex.escape(blockVar)}\s*=\s*($id)\.getRespawnPosition\(\)\s*;""")
        .find(prefix)
        ?.groupValues
        ?.get(1)
    val angleOwner = Regex("""\b${Regex.escape(angleVar)}\s*=\s*($id)\.getRespawnAngle\(\)\s*;""")
        .find(prefix)
        ?.groupValues
        ?.get(1)
    val forcedOwner = Regex("""\b${Regex.escape(forcedVar)}\s*=\s*($id)\.isRespawnForced\(\)\s*;""")
        .find(prefix)
        ?.groupValues
        ?.get(1)
    val owners = listOf(levelOwner, blockOwner, angleOwner, forcedOwner)
    return owners.filterNotNull().distinct().singleOrNull()
}

internal fun StructuralRefactorPass.collectLegacyTeleporterTypes(javaFiles: List<Path>): Set<String> {
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val direct = linkedSetOf<String>()
    for (file in javaFiles) {
        val source = runCatching { file.readText() }.getOrNull() ?: continue
        if (!source.contains("ITeleporter")) continue
        val unit = parser.parse(source).result.orElse(null) ?: continue
        val typing = SourceValueTypes(this, source)
        unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .filter { declaration ->
                !declaration.isInterface && declaration.implementedTypes.any { implemented ->
                    typing.isTypeName(implemented.toString(), "net.minecraftforge.common.util.ITeleporter") ||
                        typing.isTypeName(implemented.toString(), "net.neoforged.neoforge.common.util.ITeleporter")
                }
            }
            .mapNotNullTo(direct) { it.fullyQualifiedName.orElse(null) }
    }
    val owned = direct.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        projectParents.forEach { (child, parent) ->
            if (parent in owned && owned.add(child)) changed = true
        }
    } while (changed)
    return owned
}

private fun StructuralRefactorPass.isProvenProjectTeleporterConstructor(source: String, typeName: String): Boolean {
    if (projectLegacyTeleporterTypes.isEmpty()) return false
    val typing = SourceValueTypes(this, source)
    return projectLegacyTeleporterTypes.any { typing.isTypeName(typeName, it) }
}

internal fun StructuralRefactorPass.migrateLegacyITeleporterDimensionTransitions(source: String): String {
    if (Regex("""(?m)^\s*import\s+com\.modporter\.generated\.[a-z0-9_]+\.compat\.(?:PortalInfo|LegacyPortalProbe)\s*;""")
            .containsMatchIn(maskJavaCommentsAndLiterals(source))) return source
    val executable = maskJavaCommentsAndLiterals(source)
    val hasProvenProjectConstructor = executable.contains("changeDimension(") &&
        Regex("""\bnew\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(""")
            .findAll(executable)
            .any { isProvenProjectTeleporterConstructor(source, it.groupValues[1]) }
    val hasLegacyTeleporterFamily = executable.contains("ITeleporter") &&
        (
            executable.contains("implements ITeleporter") ||
                executable.contains("getPortalInfo(") ||
                executable.contains("placeEntity(") ||
                Regex("""\bchangeDimension\s*\([^;{}]*\bITeleporter\b""").containsMatchIn(executable)
            ) || hasProvenProjectConstructor
    if (!hasLegacyTeleporterFamily) {
        return source
    }

    var result = source
    result = migrateLegacyPlaceEntityOnlyITeleporter(result)
    val getPortalInfoMethod = javaMethodText(source, "getPortalInfo")
    val defaultSuperCall = getPortalInfoMethod?.let {
        Regex("""ITeleporter\.super\.getPortalInfo\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)""")
            .find(it)
    }
    val defaultPortalInfoPosition = if (defaultSuperCall != null) {
        inferLegacyTeleporterDefaultPortalPosition(getPortalInfoMethod) ?: return source
    } else {
        null
    }
    val legacySignature = legacyGetPortalInfoSignature(result)
    if (result.contains("implements ITeleporter")) {
        result = result.replace(" implements ITeleporter", "")
        result = removeImport(result, "net.neoforged.neoforge.common.util.ITeleporter")
        result = removeImport(result, "net.minecraftforge.common.util.ITeleporter")
    }
    val beforeNullChangeDimensionOverride = result
    result = Regex(
        """(?m)^([ \t]*)(@Nullable\s*\r?\n)?\1@Override\s*\r?\n\1public\s+Entity\s+changeDimension\(\s*ServerLevel\s+[A-Za-z_$][\w$]*\s*,\s*ITeleporter\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*\r?\n\1[ \t]*return\s+null\s*;\s*\r?\n\1}"""
    ).replace(result) { match ->
        val indent = match.groupValues[1]
        val nullable = match.groupValues[2]
        "${indent}${nullable}${indent}@Override\n${indent}public Entity changeDimension(DimensionTransition transition) {\n${indent}    return null;\n${indent}}"
    }
    if (result != beforeNullChangeDimensionOverride) {
        result = removeImport(result, "net.neoforged.neoforge.common.util.ITeleporter")
        result = removeImport(result, "net.minecraftforge.common.util.ITeleporter")
        result = addImportIfMissing(result, "net.minecraft.world.level.portal.DimensionTransition")
    }
    if (Regex("""(?<![\w$])PortalInfo(?![\w$])""").containsMatchIn(result)) {
        result = result.replace("import net.minecraft.world.level.portal.PortalInfo;", "import net.minecraft.world.level.portal.DimensionTransition;")
        if (!result.contains("import net.minecraft.world.level.portal.DimensionTransition;")) {
            result = addImportIfMissing(result, "net.minecraft.world.level.portal.DimensionTransition")
        }
        result = Regex("""\(\s*Entity\s+([A-Za-z_$][\w$]*)\s*,\s*ServerLevel\s+([A-Za-z_$][\w$]*)\s*,\s*(?:java\.util\.function\.)?Function\s*<\s*ServerLevel\s*,\s*PortalInfo\s*>\s*[A-Za-z_$][\w$]*\s*\)""")
            .replace(result) { match -> "(Entity ${match.groupValues[1]}, ServerLevel ${match.groupValues[2]})" }
        result = Regex("""(?m)^([ \t]*)@Override\s*\r?\n\1public\s+PortalInfo\s+getPortalInfo\(\s*Entity\s+([A-Za-z_$][\w$]*)\s*,\s*ServerLevel\s+([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match -> "${match.groupValues[1]}public DimensionTransition getPortalInfo(Entity ${match.groupValues[2]}, ServerLevel ${match.groupValues[3]})" }
        result = Regex("""(?m)^([ \t]*)public\s+PortalInfo\s+getPortalInfo\(\s*Entity\s+([A-Za-z_$][\w$]*)\s*,\s*ServerLevel\s+([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match -> "${match.groupValues[1]}public DimensionTransition getPortalInfo(Entity ${match.groupValues[2]}, ServerLevel ${match.groupValues[3]})" }
        result = Regex("""(?m)^([ \t]*)@Override\s*\r?\n\1public\s+boolean\s+playTeleportSound\(""")
            .replace(result) { match -> "${match.groupValues[1]}public boolean playTeleportSound(" }
        result = result.replace("PortalInfo pos;", "DimensionTransition pos;")
        result = Regex("""\bPortalInfo\s+([A-Za-z_$][\w$]*)\s*=""")
            .replace(result) { match -> "DimensionTransition ${match.groupValues[1]} =" }
        result = Regex("""\bprivate\s+static\s+PortalInfo\s+""").replace(result, "private static DimensionTransition ")
        result = Regex("""\bprotected\s+static\s+PortalInfo\s+""").replace(result, "protected static DimensionTransition ")
        result = Regex("""\bpublic\s+static\s+PortalInfo\s+""").replace(result, "public static DimensionTransition ")
        result = result.replace("pos.pos)", "pos.pos())")
        result = result.replace("pos.pos;", "pos.pos();")
        result = result.replace("BlockPos.containing(pos.pos)", "BlockPos.containing(pos.pos())")
        if (defaultPortalInfoPosition != null) {
            val entityParam = defaultSuperCall?.groupValues?.get(1) ?: legacySignature?.entityParam ?: "entity"
            val levelParam = defaultSuperCall?.groupValues?.get(2) ?: legacySignature?.levelParam ?: "dest"
            result = result.replace(
                defaultSuperCall!!.value,
                "makePortalInfo($levelParam, $entityParam, Vec3.atCenterOf($defaultPortalInfoPosition))"
            )
        }
        result = result.replace("return makePortalInfo(entity, portalX, portalY, portalZ);", "return makePortalInfo(destDim, entity, portalX, portalY, portalZ);")
        result = result.replace("return makePortalInfo(entity, Vec3.atCenterOf(pos));", "return makePortalInfo(world, entity, Vec3.atCenterOf(pos));")
        result = result.replace("return makePortalInfo(entity, safeCoords.getX(), entity.getY(), safeCoords.getZ());", "return makePortalInfo(world, entity, safeCoords.getX(), entity.getY(), safeCoords.getZ());")
        result = result.replace("return makePortalInfo(entity, new Vec3(x, y, z));", "return makePortalInfo(level, entity, new Vec3(x, y, z));")
        result = Regex("""private\s+static\s+DimensionTransition\s+makePortalInfo\(\s*Entity\s+entity\s*,\s*double\s+x\s*,\s*double\s+y\s*,\s*double\s+z\s*\)""")
            .replace(result, "private static DimensionTransition makePortalInfo(ServerLevel level, Entity entity, double x, double y, double z)")
        result = Regex("""private\s+static\s+DimensionTransition\s+makePortalInfo\(\s*Entity\s+entity\s*,\s*Vec3\s+pos\s*\)""")
            .replace(result, "private static DimensionTransition makePortalInfo(ServerLevel level, Entity entity, Vec3 pos)")
        result = Regex("""new\s+PortalInfo\(\s*pos\s*,\s*Vec3\.ZERO\s*,\s*entity\.getYRot\(\)\s*,\s*entity\.getXRot\(\)\s*\)""")
            .replace(result, "new DimensionTransition(level, pos, Vec3.ZERO, entity.getYRot(), entity.getXRot(), DimensionTransition.PLACE_PORTAL_TICKET)")
        legacySignature?.let {
            result = migratePortalInfoConstructorsInMethod(result, "getPortalInfo", it.levelParam)
            result = migratePortalShapeCreatePortalInfoInMethod(result, "getPortalInfo")
        }
        result = migratePortalInfoConstructorsInMethod(result, "makePortalInfo", "level")
        result = removeMethodByName(result, "placeEntity")
        result = removeImport(result, "java.util.function.Function")
        javaMethodText(result, "getPortalInfo")?.let { method ->
            val targetLevelParam = legacySignature?.levelParam ?: "dest"
            val migratedMethod = method.replace("makePortalInfo(entity,", "makePortalInfo($targetLevelParam, entity,")
            result = result.replace(method, migratedMethod)
        }
    }

    val dimensionSource = result
    result = rewriteJavaCallWithOffset(result, "changeDimension") { receiver, args, callOffset ->
        if (args.size != 2) return@rewriteJavaCallWithOffset null
        val entity = legacyChangeDimensionEntityExpression(dimensionSource, receiver, callOffset)
            ?: return@rewriteJavaCallWithOffset null
        val dest = args[0].trim()
        legacyAnonymousITeleporterDimensionTransition(args[1].trim(), entity, dest)?.let { transition ->
            return@rewriteJavaCallWithOffset "$entity.changeDimension($transition)"
        }
        legacyConditionalTeleporterConstructors(args[1].trim())?.let { conditional ->
            val trueTeleporter = conditional.trueConstructor
            val falseTeleporter = conditional.falseConstructor
            if (!isProvenProjectTeleporterConstructor(dimensionSource, trueTeleporter.first) ||
                !isProvenProjectTeleporterConstructor(dimensionSource, falseTeleporter.first)
            ) {
                return@let
            }
            return@rewriteJavaCallWithOffset buildString {
                append(entity)
                append(".changeDimension(")
                append(conditional.condition)
                append(" ? new ")
                append(trueTeleporter.first)
                append('(')
                append(trueTeleporter.second)
                append(").getPortalInfo(")
                append(entity)
                append(", ")
                append(dest)
                append(") : new ")
                append(falseTeleporter.first)
                append('(')
                append(falseTeleporter.second)
                append(").getPortalInfo(")
                append(entity)
                append(", ")
                append(dest)
                append("))")
            }
        }
        val teleporter = legacyTeleporterConstructorParts(args[1].trim())
            ?: return@rewriteJavaCallWithOffset null
        if (!isProvenProjectTeleporterConstructor(dimensionSource, teleporter.first)) {
            return@rewriteJavaCallWithOffset null
        }
        "$entity.changeDimension(new ${teleporter.first}(${teleporter.second}).getPortalInfo($entity, $dest))"
    }
    result = removeUnusedSimpleImport(result, "net.neoforged.neoforge.common.util.ITeleporter", "ITeleporter")
    result = removeUnusedSimpleImport(result, "net.minecraftforge.common.util.ITeleporter", "ITeleporter")
    result = removeUnusedSimpleImport(result, "java.util.function.Function", "Function")
    if (result.contains("DimensionTransition")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.portal.DimensionTransition")
    }
    if (result.contains("new Vec3(") || result.contains("Vec3.ZERO")) {
        result = addImportIfMissing(result, "net.minecraft.world.phys.Vec3")
    }
    return result
}

internal data class LegacyPlaceEntityTransition(
    val positionExpression: String,
    val needsVec3Constructor: Boolean
)

internal fun StructuralRefactorPass.migrateLegacyPlaceEntityOnlyITeleporter(source: String): String {
    if (!source.contains("placeEntity") || !source.contains("ITeleporter") || source.contains("PortalInfo")) {
        return source
    }
    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        if (method.name != "placeEntity") continue
        if (!isMethodInsideLegacyITeleporterClass(result, method.range.first)) continue
        val methodText = result.substring(method.range)
        val parameters = javaMethodParameters(methodText)
        if (parameters.size != 5) continue
        val entityParam = parameters[0].takeIf { it.type.endsWithJavaType("Entity") }?.name ?: continue
        val destinationParam = parameters[2].takeIf { it.type.endsWithJavaType("ServerLevel") }?.name ?: continue
        val transition = legacyPlaceEntityTransition(methodText, entityParam) ?: continue
        val methodIndent = Regex("""^[ \t]*""").find(methodText)?.value ?: ""
        val replacement = """
${methodIndent}public DimensionTransition getPortalInfo(Entity $entityParam, ServerLevel $destinationParam) {
${methodIndent}    return new DimensionTransition($destinationParam, ${transition.positionExpression}, Vec3.ZERO, $entityParam.getYRot(), $entityParam.getXRot(), DimensionTransition.PLACE_PORTAL_TICKET);
${methodIndent}}
""".trimEnd()
        result = result.substring(0, method.range.first) + replacement + result.substring(method.range.last + 1)
    }
    if (result == source) return source
    result = result.replace(" implements ITeleporter", "")
    result = removeImport(result, "net.neoforged.neoforge.common.util.ITeleporter")
    result = removeImport(result, "net.minecraftforge.common.util.ITeleporter")
    result = removeUnusedSimpleImport(result, "java.util.function.Function", "Function")
    result = addImportIfMissing(result, "net.minecraft.world.level.portal.DimensionTransition")
    result = addImportIfMissing(result, "net.minecraft.world.phys.Vec3")
    return result
}

internal fun StructuralRefactorPass.isMethodInsideLegacyITeleporterClass(source: String, methodStart: Int): Boolean {
    val prefix = source.substring(0, methodStart)
    val classMatch = Regex("""(?m)\b(?:class|record)\s+[A-Za-z_$][\w$]*[^{;]*\bimplements\b[^{;]*\bITeleporter\b[^{;]*\{""")
        .findAll(prefix)
        .lastOrNull()
        ?: return false
    val anonymousStart = prefix.lastIndexOf("new ITeleporter()")
    if (anonymousStart > classMatch.range.first) return false
    val openBrace = source.indexOf('{', classMatch.range.last - 1)
    if (openBrace < 0) return false
    val closeBrace = findMatchingBrace(source, openBrace)
    return closeBrace >= methodStart
}

internal fun StructuralRefactorPass.legacyAnonymousITeleporterDimensionTransition(
    expression: String,
    entityExpression: String,
    destinationExpression: String
): String? {
    val trimmed = expression.trim()
    if (!trimmed.startsWith("new ITeleporter()")) return null
    val openBrace = trimmed.indexOf('{')
    if (openBrace < 0) return null
    val closeBrace = findMatchingBrace(trimmed, openBrace)
    if (closeBrace != trimmed.lastIndex) return null
    val method = javaMethodRangesIncludingDefault(trimmed)
        .singleOrNull { it.name == "placeEntity" }
        ?: return null
    val methodText = trimmed.substring(method.range)
    val parameters = javaMethodParameters(methodText)
    if (parameters.size != 5) return null
    val entityParam = parameters[0].takeIf { it.type.endsWithJavaType("Entity") }?.name ?: return null
    val transition = legacyPlaceEntityTransition(methodText, entityParam) ?: return null
    return "new DimensionTransition($destinationExpression, ${transition.positionExpression}, Vec3.ZERO, $entityExpression.getYRot(), $entityExpression.getXRot(), DimensionTransition.PLACE_PORTAL_TICKET)"
}

internal fun StructuralRefactorPass.legacyPlaceEntityTransition(methodText: String, entityParam: String): LegacyPlaceEntityTransition? {
    val executable = maskJavaCommentsAndLiterals(methodText)
    if (!Regex("""\breturn\s+${Regex.escape(entityParam)}\s*;""").containsMatchIn(executable)) return null
    val call = Regex("""\b${Regex.escape(entityParam)}\.teleportTo\s*\(""").find(executable) ?: return null
    val openParen = executable.indexOf('(', call.range.last)
    if (openParen < 0) return null
    val closeParen = findMatchingParen(executable, openParen)
    if (closeParen < 0) return null
    val args = splitTopLevelJavaArgs(methodText.substring(openParen + 1, closeParen)).map { it.trim() }
    if (args.size < 3) return null
    val position = legacyVec3PositionExpression(args[0], args[1], args[2])
        ?: "new Vec3(${args[0]}, ${args[1]}, ${args[2]})"
    return LegacyPlaceEntityTransition(position, position.startsWith("new Vec3("))
}

internal fun StructuralRefactorPass.legacyVec3PositionExpression(x: String, y: String, z: String): String? {
    val vectorName = Regex("""^([A-Za-z_$][\w$]*)\.x$""").find(x.trim())?.groupValues?.get(1) ?: return null
    if (y.trim() != "$vectorName.y") return null
    if (z.trim() != "$vectorName.z") return null
    return vectorName
}

internal fun StructuralRefactorPass.legacyChangeDimensionEntityExpression(source: String, receiver: String, callOffset: Int): String? {
    val trimmed = receiver.trim()
    if (!trimmed.contains("(")) return trimmed

    val range = enclosingMethodRange(source, callOffset) ?: return null
    val prefix = source.substring(range.first, callOffset)
    val patternVariable = Regex(
        """if\s*\(\s*${Regex.escape(trimmed)}\s+instanceof\s+(?:[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)(?:\s*<[^(){};]+>)?\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    ).findAll(prefix)
        .lastOrNull { match ->
            val openBrace = range.first + match.range.last
            val closeBrace = findMatchingBrace(source, openBrace)
            closeBrace > callOffset
        }
        ?.groupValues
        ?.get(1)
    return patternVariable
}

internal fun StructuralRefactorPass.legacyTeleporterConstructorParts(expression: String): Pair<String, String>? {
    val trimmed = expression.trim()
    val match = Regex("""new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(""")
        .find(trimmed)
        ?: return null
    if (match.range.first != 0) return null
    val openParen = match.range.last
    val closeParen = findMatchingParen(trimmed, openParen)
    if (closeParen != trimmed.lastIndex) return null
    return match.groupValues[1] to trimmed.substring(openParen + 1, closeParen).trim()
}

internal data class LegacyConditionalTeleporterConstructors(
    val condition: String,
    val trueConstructor: Pair<String, String>,
    val falseConstructor: Pair<String, String>
)

internal fun StructuralRefactorPass.legacyConditionalTeleporterConstructors(
    expression: String
): LegacyConditionalTeleporterConstructors? {
    val trimmed = expression.trim()
    val executable = maskJavaCommentsAndLiterals(trimmed)
    var parens = 0
    var brackets = 0
    var braces = 0
    var question = -1
    var nestedConditionals = 0
    var colon = -1
    for (index in executable.indices) {
        when (executable[index]) {
            '(' -> parens++
            ')' -> parens--
            '[' -> brackets++
            ']' -> brackets--
            '{' -> braces++
            '}' -> braces--
            '?' -> if (parens == 0 && brackets == 0 && braces == 0) {
                if (question < 0) question = index else nestedConditionals++
            }
            ':' -> if (question >= 0 && parens == 0 && brackets == 0 && braces == 0) {
                if (nestedConditionals == 0) {
                    colon = index
                    break
                }
                nestedConditionals--
            }
        }
        if (parens < 0 || brackets < 0 || braces < 0) return null
    }
    if (question <= 0 || colon <= question + 1 || colon >= trimmed.lastIndex) return null
    val condition = trimmed.substring(0, question).trim().takeIf { it.isNotEmpty() } ?: return null
    val trueConstructor = legacyTeleporterConstructorParts(trimmed.substring(question + 1, colon))
        ?: return null
    val falseConstructor = legacyTeleporterConstructorParts(trimmed.substring(colon + 1))
        ?: return null
    return LegacyConditionalTeleporterConstructors(condition, trueConstructor, falseConstructor)
}

internal data class LegacyPortalInfoSignature(
    val entityParam: String,
    val levelParam: String,
    val defaultPortalInfoParam: String
)

internal fun StructuralRefactorPass.legacyGetPortalInfoSignature(source: String): LegacyPortalInfoSignature? =
    Regex("""public\s+PortalInfo\s+getPortalInfo\(\s*Entity\s+([A-Za-z_$][\w$]*)\s*,\s*ServerLevel\s+([A-Za-z_$][\w$]*)\s*,\s*(?:java\.util\.function\.)?Function\s*<\s*ServerLevel\s*,\s*PortalInfo\s*>\s*([A-Za-z_$][\w$]*)\s*\)""")
        .find(source)
        ?.let { match ->
            LegacyPortalInfoSignature(
                entityParam = match.groupValues[1],
                levelParam = match.groupValues[2],
                defaultPortalInfoParam = match.groupValues[3]
            )
        }

internal fun StructuralRefactorPass.migratePortalInfoConstructorsInMethod(source: String, methodName: String, levelExpression: String): String {
    val method = javaMethodText(source, methodName) ?: return source
    val migrated = migratePortalInfoConstructors(method, levelExpression)
    return if (migrated == method) source else source.replace(method, migrated)
}

internal fun StructuralRefactorPass.migratePortalInfoConstructors(source: String, levelExpression: String): String {
    val result = StringBuilder()
    var cursor = 0
    while (cursor < source.length) {
        val constructorIndex = source.indexOf("new PortalInfo", cursor)
        if (constructorIndex < 0) break
        val openParen = source.indexOf('(', constructorIndex)
        if (openParen < 0) break
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen)).map { it.trim() }
        if (args.size != 4) {
            cursor = closeParen + 1
            continue
        }
        result.append(source, cursor, constructorIndex)
        result.append("new DimensionTransition(")
        result.append(levelExpression)
        result.append(", ")
        result.append(args[0])
        result.append(", ")
        result.append(args[1])
        result.append(", ")
        result.append(args[2])
        result.append(", ")
        result.append(args[3])
        result.append(", DimensionTransition.PLACE_PORTAL_TICKET)")
        cursor = closeParen + 1
    }
    if (cursor == 0) return source
    result.append(source, cursor, source.length)
    return result.toString()
}

internal fun StructuralRefactorPass.migratePortalShapeCreatePortalInfoInMethod(source: String, methodName: String): String {
    val method = javaMethodText(source, methodName) ?: return source
    val migrated = migratePortalShapeCreatePortalInfo(method)
    return if (migrated == method) source else source.replace(method, migrated)
}

internal fun StructuralRefactorPass.migratePortalShapeCreatePortalInfo(source: String): String {
    val result = StringBuilder()
    var cursor = 0
    while (cursor < source.length) {
        val callIndex = source.indexOf("PortalShape.createPortalInfo", cursor)
        if (callIndex < 0) break
        val openParen = source.indexOf('(', callIndex)
        if (openParen < 0) break
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen)).map { it.trim() }
        if (args.size != 8) {
            cursor = closeParen + 1
            continue
        }
        val level = args[0]
        val rectangle = args[1]
        val axis = args[2]
        val relativePosition = args[3]
        val entity = args[4]
        val speed = args[5]
        val yRot = args[6]
        val xRot = args[7]
        val dimensions = "$entity.getDimensions($entity.getPose())"
        result.append(source, cursor, callIndex)
        result.append("new DimensionTransition(")
        result.append(level)
        result.append(", PortalShape.findCollisionFreePosition(PortalShape.getRelativePosition(")
        result.append(rectangle)
        result.append(", ")
        result.append(axis)
        result.append(", ")
        result.append(relativePosition)
        result.append(", ")
        result.append(dimensions)
        result.append("), ")
        result.append(level)
        result.append(", ")
        result.append(entity)
        result.append(", ")
        result.append(dimensions)
        result.append("), ")
        result.append(speed)
        result.append(", ")
        result.append(yRot)
        result.append(", ")
        result.append(xRot)
        result.append(", DimensionTransition.PLACE_PORTAL_TICKET)")
        cursor = closeParen + 1
    }
    if (cursor == 0) return source
    result.append(source, cursor, source.length)
    return result.toString()
}

internal fun StructuralRefactorPass.inferLegacyTeleporterDefaultPortalPosition(getPortalInfoMethod: String): String? {
    val id = """[A-Za-z_$][\w$]*"""
    return Regex("""\bBlockPos\s+($id)\s*=""")
        .findAll(getPortalInfoMethod)
        .map { it.groupValues[1] }
        .firstOrNull { variable ->
            val escaped = Regex.escape(variable)
            Regex("""\b(?:placeInExistingPortal|moveToSafeCoords)\(\s*[A-Za-z_$][\w$]*\s*,\s*[A-Za-z_$][\w$]*\s*,\s*$escaped\s*\)""")
                .containsMatchIn(getPortalInfoMethod)
        }
}

internal fun StructuralRefactorPass.migrateLegacyCanChangeDimensionsCallSites(source: String): String {
    if (!source.contains(".canChangeDimensions()")) return source
    var result = source
    val oldGuard = Regex(
        """if\s*\(\s*([A-Za-z_$][\w$]*)\.isPassenger\(\)\s*\|\|\s*\1\.isVehicle\(\)\s*\|\|\s*!\s*\1\.canChangeDimensions\(\)\s*\)\s*\{\s*return\s*;\s*\}"""
    ).find(result) ?: return migrateHelperResolvedCanChangeDimensionsCallSites(source)
    val entity = oldGuard.groupValues[1]
    result = result.substring(0, oldGuard.range.first) +
        "if ($entity.isPassenger() || $entity.isVehicle()) {\n\t\t\treturn;\n\t\t}" +
        result.substring(oldGuard.range.last + 1)
    val nullGuard = Regex("""if\s*\(\s*([A-Za-z_$][\w$]*)\s*==\s*null\s*\)\s*(?:\{\s*)?return\s*;(?:\s*\})?""")
        .find(result, oldGuard.range.first)
        ?: return result
    val serverLevel = nullGuard.groupValues[1]
    val replacement = "if ($serverLevel == null || !$entity.canChangeDimensions($entity.level(), $serverLevel))\n\t\t\treturn;"
    return result.substring(0, nullGuard.range.first) + replacement + result.substring(nullGuard.range.last + 1)
}

internal fun StructuralRefactorPass.migrateHelperResolvedCanChangeDimensionsCallSites(source: String): String {
    val helpers = portalDimensionHelperMethods(source)
    if (helpers.isEmpty()) return source
    var result = source
    var changed = false
    for (helper in helpers) {
        val helperCall = Regex("""\b(?:this\.)?${Regex.escape(helper.name)}\s*\(\s*([A-Za-z_$][\w$]*)\s*\)""")
        val guardedEntities = helperCall.findAll(result)
            .map { it.groupValues[1] }
            .filter { entity -> Regex("""\b${Regex.escape(entity)}\.canChangeDimensions\(\)""").containsMatchIn(result) }
            .distinct()
            .toList()
        if (guardedEntities.isEmpty()) continue

        val withHelperGuard = addCanChangeDimensionsToHelperDestinationGuard(result, helper)
        if (withHelperGuard == result) continue
        result = withHelperGuard
        for (entity in guardedEntities) {
            result = removeNoArgCanChangeDimensionsCondition(result, entity)
        }
        changed = true
    }
    return if (changed) result else source
}

internal data class PortalDimensionHelper(
    val name: String,
    val entityParameter: String,
    val destinationLevel: String
)

internal fun StructuralRefactorPass.portalDimensionHelperMethods(source: String): List<PortalDimensionHelper> {
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:private|protected|public)\s+(?:static\s+)?[\w.$<>\[\], ?]+\s+([A-Za-z_$][\w$]*)\s*\(\s*Entity\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    return methodPattern.findAll(source).mapNotNull { match ->
        val openBrace = source.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return@mapNotNull null
        val body = source.substring(openBrace + 1, closeBrace)
        val entity = match.groupValues[2]
        val destination = Regex("""\bServerLevel\s+([A-Za-z_$][\w$]*)\s*=""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: return@mapNotNull null
        val usesDestinationTransition = Regex(
            """\b${Regex.escape(entity)}\.changeDimension\s*\([\s\S]*\b${Regex.escape(destination)}\b"""
        ).containsMatchIn(body)
        val hasDestinationGuard = Regex("""\bif\s*\(\s*${Regex.escape(destination)}\s*!=\s*null\b""")
            .containsMatchIn(body)
        if (!usesDestinationTransition || !hasDestinationGuard) return@mapNotNull null
        PortalDimensionHelper(match.groupValues[1], entity, destination)
    }.toList()
}

internal fun StructuralRefactorPass.addCanChangeDimensionsToHelperDestinationGuard(source: String, helper: PortalDimensionHelper): String {
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:private|protected|public)\s+(?:static\s+)?[\w.$<>\[\], ?]+\s+${Regex.escape(helper.name)}\s*\(\s*Entity\s+${Regex.escape(helper.entityParameter)}\s*\)\s*\{"""
    )
    val method = methodPattern.find(source) ?: return source
    val openBrace = source.indexOf('{', method.range.last - 1)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    if (openBrace < 0 || closeBrace < 0) return source
    val methodText = source.substring(method.range.first, closeBrace + 1)
    val canChange = "${helper.entityParameter}.canChangeDimensions(${helper.entityParameter}.level(), ${helper.destinationLevel})"
    if (methodText.contains(canChange)) return source
    var migratedMethod = Regex(
        """if\s*\(\s*${Regex.escape(helper.destinationLevel)}\s*!=\s*null\s*&&\s*!\s*${Regex.escape(helper.entityParameter)}\.isPassenger\(\)\s*\)"""
    ).replace(methodText) {
        "if (${helper.destinationLevel} != null && !${helper.entityParameter}.isPassenger() && $canChange)"
    }
    if (migratedMethod == methodText) {
        migratedMethod = Regex("""if\s*\(\s*${Regex.escape(helper.destinationLevel)}\s*!=\s*null\s*\)""")
            .replace(methodText) { "if (${helper.destinationLevel} != null && $canChange)" }
    }
    if (migratedMethod == methodText) return source
    return source.substring(0, method.range.first) + migratedMethod + source.substring(closeBrace + 1)
}

internal fun StructuralRefactorPass.removeNoArgCanChangeDimensionsCondition(source: String, entity: String): String {
    val escapedEntity = Regex.escape(entity)
    var result = source
    result = Regex("""\s*&&\s*$escapedEntity\.canChangeDimensions\(\)""").replace(result, "")
    result = Regex("""$escapedEntity\.canChangeDimensions\(\)\s*&&\s*""").replace(result, "")
    result = Regex("""!\s*$escapedEntity\.canChangeDimensions\(\)\s*\|\|\s*""").replace(result, "")
    result = Regex("""\s*\|\|\s*!\s*$escapedEntity\.canChangeDimensions\(\)""").replace(result, "")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPortalWaitTimeCalls(source: String): String =
    if (source.contains(".getPortalWaitTime()")) {
        source.replace(".getPortalWaitTime()", ".getDimensionChangingDelay()")
    } else {
        source
    }

internal fun StructuralRefactorPass.migrateLegacyAnimalFoodPredicateOverrides(source: String): String {
    if (!source.contains("extends Animal") && !source.contains("extends TamableAnimal")) {
        return source
    }

    var result = source
    var changed = false
    collectJavaClassDeclarations(source).asReversed().forEach { declaration ->
        val directSuper = declaration.directSuper.substringAfterLast('.')
        if (directSuper !in setOf("Animal", "TamableAnimal")) return@forEach
        val classBody = result.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
        if (hasItemStackIsFoodMethod(classBody)) return@forEach
        val returnExpression = legacyAnimalFoodReturnExpression(classBody) ?: return@forEach
        val method = """

	@Override
	public boolean isFood(ItemStack stack) {
		return $returnExpression;
	}
""".trimEnd()
        result = result.substring(0, declaration.bodyRange.last) + method + result.substring(declaration.bodyRange.last)
        changed = true
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.world.item.ItemStack") else source
}

internal fun StructuralRefactorPass.hasItemStackIsFoodMethod(source: String): Boolean =
    Regex("""\bisFood\s*\(\s*(?:net\.minecraft\.world\.item\.)?ItemStack\s+[A-Za-z_$][\w$]*\s*\)""")
        .containsMatchIn(maskJavaCommentsAndLiterals(source))

internal fun StructuralRefactorPass.legacyAnimalFoodReturnExpression(classBody: String): String? {
    val inlineTemptFood = Regex("""new\s+TemptGoal\s*\([^;]*?Ingredient\.of\(\s*([^)]+?)\s*\)""")
        .find(classBody)
        ?.groupValues
        ?.get(1)
        ?.trim()
    val ingredientFields = Regex("""\bIngredient\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(maskJavaCommentsAndLiterals(classBody))
        .map { it.groupValues[1] }
        .toSet()
    val fieldTemptFood = Regex("""new\s+TemptGoal\s*\([^;]*?,\s*([A-Za-z_$][\w$]*)\s*,\s*(?:true|false)\s*\)""")
        .find(classBody)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it in ingredientFields }
    val feedingItem = listOfNotNull(javaMethodText(classBody, "interactAt"), javaMethodText(classBody, "mobInteract"))
        .firstNotNullOfOrNull { methodText ->
            legacyAnimalFoodPredicateFromInteraction(methodText)
        }
    return when {
        inlineTemptFood != null -> "stack.is($inlineTemptFood)"
        fieldTemptFood != null -> "$fieldTemptFood.test(stack)"
        feedingItem != null -> "stack.is($feedingItem)"
        else -> null
    }
}

internal fun StructuralRefactorPass.legacyAnimalFoodPredicateFromInteraction(methodText: String): String? {
    val executable = maskJavaCommentsAndLiterals(methodText)
    val stackVariables = Regex("""\bItemStack\s+([A-Za-z_$][\w$]*)\s*=""")
        .findAll(executable)
        .map { it.groupValues[1] }
        .toSet()
    val directStackPredicate = Regex(
        """\b([A-Za-z_$][\w$]*)\.is\s*\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*)\s*\)"""
    ).findAll(executable)
        .firstOrNull { it.groupValues[1] in stackVariables }
        ?.groupValues
        ?.get(2)
        ?.trim()
    if (directStackPredicate != null) return directStackPredicate
    if (!executable.contains(".tame(") && !executable.contains("setTame(")) return null
    return Regex(
        """\b([A-Za-z_$][\w$]*)\.getItem\(\)\s*==\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*)"""
    ).findAll(executable)
        .firstOrNull { it.groupValues[1] in stackVariables }
        ?.groupValues
        ?.get(2)
        ?.trim()
}

internal fun StructuralRefactorPass.migrateLegacyMushroomBlockConstructorOrder(source: String): String {
    if (!source.contains("extends MushroomBlock") || !source.contains("super(")) return source
    return Regex("""super\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*\);""")
        .replace(source) { match ->
            val first = match.groupValues[1]
            val second = match.groupValues[2]
            if (first == "properties" || first.endsWith("Properties")) {
                "super($second, $first);"
            } else {
                match.value
            }
        }
}

internal fun StructuralRefactorPass.migrateLegacyVanillaBlockConstructors121(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new StairBlock(") &&
        !executableCode.contains("new ButtonBlock(") &&
        !executableCode.contains("new FenceGateBlock(") &&
        !executableCode.contains("new PressurePlateBlock(") &&
        !executableCode.contains("new DoorBlock(") &&
        !executableCode.contains("new TrapDoorBlock(") &&
        !executableCode.contains("new TorchBlock(") &&
        !executableCode.contains("new WallTorchBlock(")
    ) {
        return source
    }

    var result = source
    result = rewriteExecutableJavaNew(result, "StairBlock") { args ->
        if (args.size != 2) return@rewriteExecutableJavaNew null
        val stateSupplier = args[0].trim()
        if (!stateSupplier.startsWith("() ->") || !stateSupplier.contains(".defaultBlockState()")) {
            return@rewriteExecutableJavaNew null
        }
        val state = stateSupplier.removePrefix("() ->").trim()
        "new StairBlock($state, ${args[1].trim()})"
    }
    result = rewriteExecutableJavaNew(result, "ButtonBlock") { args ->
        if (args.size != 4 || !looksLikeBlockPropertiesExpression(args[0])) return@rewriteExecutableJavaNew null
        "new ButtonBlock(${args[1].trim()}, ${args[2].trim()}, ${args[0].trim()})"
    }
    result = rewriteExecutableJavaNew(result, "FenceGateBlock") { args ->
        if (args.size != 2 || !looksLikeBlockPropertiesExpression(args[0])) return@rewriteExecutableJavaNew null
        "new FenceGateBlock(${args[1].trim()}, ${args[0].trim()})"
    }
    result = rewriteExecutableJavaNew(result, "PressurePlateBlock") { args ->
        if (args.size != 3 || !args[0].trim().contains("Sensitivity") || !looksLikeBlockPropertiesExpression(args[1])) {
            return@rewriteExecutableJavaNew null
        }
        "new PressurePlateBlock(${args[2].trim()}, ${args[1].trim()})"
    }
    result = rewriteExecutableJavaNew(result, "DoorBlock") { args ->
        if (args.size != 2 || !looksLikeBlockPropertiesExpression(args[0])) return@rewriteExecutableJavaNew null
        "new DoorBlock(${args[1].trim()}, ${args[0].trim()})"
    }
    result = rewriteExecutableJavaNew(result, "TrapDoorBlock") { args ->
        if (args.size != 2 || !looksLikeBlockPropertiesExpression(args[0])) return@rewriteExecutableJavaNew null
        "new TrapDoorBlock(${args[1].trim()}, ${args[0].trim()})"
    }
    result = rewriteExecutableJavaNew(result, "TorchBlock") { args ->
        if (args.size != 2 || !looksLikeBlockPropertiesExpression(args[0]) || !looksLikeParticleOptionsExpression(args[1])) {
            return@rewriteExecutableJavaNew null
        }
        "new TorchBlock(${args[1].trim()}, ${args[0].trim()})"
    }
    result = rewriteExecutableJavaNew(result, "WallTorchBlock") { args ->
        if (args.size != 2 || !looksLikeBlockPropertiesExpression(args[0]) || !looksLikeParticleOptionsExpression(args[1])) {
            return@rewriteExecutableJavaNew null
        }
        "new WallTorchBlock(${args[1].trim()}, ${args[0].trim()})"
    }
    return result
}

internal fun StructuralRefactorPass.looksLikeBlockPropertiesExpression(expression: String): Boolean {
    val trimmed = expression.trim()
    return trimmed.contains("BlockBehaviour.Properties.") ||
        trimmed.contains(".Properties.") ||
        trimmed.startsWith("properties") ||
        trimmed.endsWith("Properties") ||
        trimmed.contains(".ofFullCopy(") ||
        trimmed.contains(".ofLegacyCopy(") ||
        trimmed.contains(".of()") ||
        trimmed.contains(".strength(") ||
        trimmed.contains(".sound(") ||
        trimmed.contains(".noCollission()") ||
        trimmed.contains(".noOcclusion()")
}

internal fun StructuralRefactorPass.looksLikeParticleOptionsExpression(expression: String): Boolean {
    val trimmed = expression.trim()
    return Regex("""\b(?:ParticleTypes|[A-Za-z_$][\w$]*ParticleTypes)\.[A-Z][A-Z0-9_]*(?:\.get\(\))?\b""").containsMatchIn(trimmed) ||
        Regex("""\b(?:ParticleOptions|SimpleParticleType|ParticleType\s*<[^>]+>)\s+[A-Za-z_$][\w$]*\b""").containsMatchIn(trimmed)
}

internal fun StructuralRefactorPass.migrateLegacyGameEventConstructors(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new GameEvent(") || !executableCode.contains("DeferredRegister")) return source
    val gameEventRegisters = Regex("""\bDeferredRegister\s*<\s*GameEvent\s*>\s+([A-Za-z_$][\w$]*)\s*=""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    if (gameEventRegisters.isEmpty()) return source

    val stringLiteral = Regex(""""(?:\\.|[^"\\])*"""")
    fun migrateSupplier(registerId: String, supplier: String): String {
        return rewriteExecutableJavaNew(supplier, "GameEvent") { args ->
            if (args.size != 2) return@rewriteExecutableJavaNew null
            val legacyId = args[0].trim()
            if (!stringLiteral.matches(registerId) || legacyId != registerId) return@rewriteExecutableJavaNew null
            "new GameEvent(${args[1].trim()})"
        }
    }

    return rewriteExecutableJavaCall(source, "register") { receiver, args ->
        if (args.size < 2) return@rewriteExecutableJavaCall null
        val registerName = receiver.trim().substringAfterLast(".")
        if (registerName !in gameEventRegisters) return@rewriteExecutableJavaCall null
        val migratedSupplier = migrateSupplier(args[0].trim(), args[1])
        if (migratedSupplier == args[1]) return@rewriteExecutableJavaCall null
        val migratedArgs = args.toMutableList()
        migratedArgs[1] = migratedSupplier
        "$receiver.register(${migratedArgs.joinToString(", ") { it.trim() }})"
    }
}

internal fun StructuralRefactorPass.migrateLegacyBannerPatternConstructors(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("BannerPattern") || !executableCode.contains("new BannerPattern(")) return source
    val namespaceExpression = bannerPatternDeferredRegisterNamespaceExpression(source) ?: return source
    var changed = false
    val result = rewriteExecutableJavaNew(source, "BannerPattern") { args ->
        if (args.size != 1) return@rewriteExecutableJavaNew null
        val translationKey = args[0].trim()
        if (!Regex(""""(?:\\.|[^"\\])*"""").matches(translationKey)) return@rewriteExecutableJavaNew null
        changed = true
        "new BannerPattern(ResourceLocation.fromNamespaceAndPath($namespaceExpression, $translationKey), $translationKey)"
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.resources.ResourceLocation") else source
}

internal fun StructuralRefactorPass.bannerPatternDeferredRegisterNamespaceExpression(source: String): String? {
    val namespaces = mutableListOf<String>()
    rewriteExecutableJavaCall(source, "create") { receiver, args ->
        if (receiver == "DeferredRegister" && args.size == 2 && args[0].trim() == "Registries.BANNER_PATTERN") {
            args[1].trim().takeIf { it.isNotEmpty() }?.let(namespaces::add)
        }
        null
    }
    return namespaces.distinct().singleOrNull()
}

internal fun StructuralRefactorPass.migrateLegacyWallSignBlockCodecSource(source: String): String {
    if (!source.contains("extends WallSignBlock") || !source.contains("WoodType")) return source
    val className = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+WallSignBlock\b""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return source
    var result = source

    val codecReplacement = """
@Override
    public com.mojang.serialization.MapCodec<WallSignBlock> codec() {
        return CODEC;
    }
""".trimEnd()
    javaMethodText(result, "codec")?.let { method ->
        if (method.contains("WallSignBlock") && method.contains("CODEC")) {
            result = result.replace(method, codecReplacement)
        }
    }
    result = Regex(
        """public\s+static\s+final\s+com\.mojang\.serialization\.MapCodec<${Regex.escape(className)}>\s+CODEC\s*=\s*com\.mojang\.serialization\.codecs\.RecordCodecBuilder(?:\s*\.\s*<\s*${Regex.escape(className)}\s*>)?\.mapCodec\(\s*instance\s*->\s*instance\.group\(\s*WoodType\.CODEC\.fieldOf\("type"\)\.forGetter\(${Regex.escape(className)}::getType\),\s*propertiesCodec\(\)\s*\)\.apply\(instance,\s*\(type,\s*properties\)\s*->\s*new\s+${Regex.escape(className)}\(properties,\s*type\)\)\s*\);"""
    ).replace(result) {
        """
public static final com.mojang.serialization.MapCodec<WallSignBlock> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
            WoodType.CODEC.fieldOf("type").forGetter(block -> (($className) block).getType()),
            propertiesCodec()
        ).apply(instance, (type, properties) -> new $className(properties, type)));
""".trimEnd()
    }
    result = Regex("""public\s+static\s+final\s+com\.mojang\.serialization\.MapCodec\s*<\s*${Regex.escape(className)}\s*>\s+CODEC\s*=""")
        .replace(result, "public static final com.mojang.serialization.MapCodec<WallSignBlock> CODEC =")
    result = result
        .replace(
            "RecordCodecBuilder.<$className>mapCodec",
            "RecordCodecBuilder.mapCodec"
        )
        .replace(
            "WoodType.CODEC.fieldOf(\"type\").forGetter($className::getType)",
            "WoodType.CODEC.fieldOf(\"type\").forGetter(block -> (($className) block).getType())"
        )

    if (!Regex("""\bprivate\s+final\s+WoodType\s+type\s*;""").containsMatchIn(result)) {
        val classOpen = result.indexOf('{', Regex("""\bclass\s+${Regex.escape(className)}\b""").find(result)?.range?.first ?: 0)
        if (classOpen >= 0) {
            result = result.substring(0, classOpen + 1) +
                "\n\tprivate final WoodType type;\n" +
                result.substring(classOpen + 1)
        }
    }

    val constructor = Regex(
        """public\s+${Regex.escape(className)}\s*\(\s*Properties\s+([A-Za-z_$][\w$]*)\s*,\s*WoodType\s+([A-Za-z_$][\w$]*)\s*\)"""
    ).find(result)
    if (constructor != null) {
        val openBrace = result.indexOf('{', constructor.range.last + 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (closeBrace >= 0) {
            val propertiesName = constructor.groupValues[1]
            val typeName = constructor.groupValues[2]
            val body = result.substring(openBrace + 1, closeBrace)
            if (body.contains("super($typeName, $propertiesName)")) {
                val getter = if (Regex("""\bWoodType\s+getType\s*\(""").containsMatchIn(result)) {
                    ""
                } else {
                    """

	public WoodType getType() {
		return this.type;
	}
""".trimEnd()
                }
                val replacement = """
public $className(Properties $propertiesName, WoodType $typeName) {
		super($typeName, $propertiesName);
		this.type = $typeName;
	}$getter
""".trimEnd()
                result = result.substring(0, constructor.range.first) + replacement + result.substring(closeBrace + 1)
            }
        }
    }
    return Regex("""(?m)([ \t]*@Override\s*\r?\n\s*){2,}""")
        .replace(result) { match -> match.groupValues[1] }
}

internal fun StructuralRefactorPass.migrateLegacyMapCodecOptionalFields(source: String): String {
    if (!source.contains(".CODEC.optionalFieldOf(") || !source.contains("MapCodec<")) return source
    val mapCodecTypes = Regex("""\bMapCodec<\s*([A-Za-z_$][\w$]*)\s*>\s+CODEC\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    if (mapCodecTypes.isEmpty()) return source
    var result = source
    for (type in mapCodecTypes) {
        result = result.replace("$type.CODEC.optionalFieldOf(", "$type.CODEC.codec().optionalFieldOf(")
    }
    return result
}

/** The registries 1.20.1 resolved static content against: the built-in ones, without any world data. */
internal const val BUILT_IN_REGISTRY_ACCESS =
    "net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)"

internal fun StructuralRefactorPass.migrateLegacyComponentSerializationSource(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("Component.Serializer.toJson(") &&
        !source.contains("Component.Serializer.toJsonTree(") &&
        !source.contains("Component.Serializer.fromJson(") &&
        !source.contains("Component.Serializer.fromJsonLenient(")) {
        return source
    }
    var result = source
    result = rewriteJavaInvocationArgumentsWithOffset(result, "Component.Serializer.toJson") { args, offset ->
        val registries = registryAccessExpressionAt(result, offset, javaInheritanceIndex) ?: return@rewriteJavaInvocationArgumentsWithOffset null
        when {
            args.size == 1 -> listOf(args[0], registries)
            args.size == 2 && isRegistryAccessEmptyExpression(args[1]) -> listOf(args[0], registries)
            else -> null
        }
    }
    result = rewriteJavaInvocation(result, "Component.Serializer.toJson") { args ->
        if (args.size == 1) {
            "net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, ${args[0].trim()}).getOrThrow().toString()"
        } else {
            null
        }
    }
    result = rewriteJavaInvocation(result, "Component.Serializer.toJsonTree") { args ->
        if (args.size == 1) {
            "net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, ${args[0].trim()}).getOrThrow()"
        } else {
            null
        }
    }
    // 1.20.1's Component.Serializer parsed JSON against the built-in registries only; with no
    // registries in scope 1.21.1's provider overloads get exactly those
    for (method in listOf("Component.Serializer.fromJson", "Component.Serializer.fromJsonLenient")) {
        result = rewriteJavaInvocationArgumentsWithOffset(result, method) { args, offset ->
            val registries = registryAccessExpressionAt(result, offset, javaInheritanceIndex) ?: BUILT_IN_REGISTRY_ACCESS
            when {
                args.size == 1 -> listOf(args[0], registries)
                args.size == 2 && isRegistryAccessEmptyExpression(args[1]) -> listOf(args[0], registries)
                else -> null
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyNitrogenLanguageHelpersSource(source: String): String {
    if (!source.contains("addCuriosIdentifier(") && !source.contains("addCuriosModifier(")) return source
    var result = source
    result = rewriteJavaCall(result, "addCuriosIdentifier") { receiver, args ->
        if (args.size == 2) {
            "$receiver.add(\"curios.identifier.\" + ${args[0].trim()}, ${args[1].trim()})"
        } else {
            null
        }
    }
    result = rewriteJavaCall(result, "addCuriosModifier") { receiver, args ->
        if (args.size == 2) {
            "$receiver.add(\"curios.modifiers.\" + ${args[0].trim()}, ${args[1].trim()})"
        } else {
            null
        }
    }
    return result
}

internal val vanillaArmorMaterialConstants = setOf(
    "LEATHER",
    "CHAIN",
    "IRON",
    "GOLD",
    "DIAMOND",
    "TURTLE",
    "NETHERITE",
    "ARMADILLO"
)

internal fun StructuralRefactorPass.migrateLegacyArmorMaterialsHolderSource(source: String): String {
    if (!source.contains("ArmorMaterials")) return source
    var result = source
    var needsBuiltInRegistriesImport = false
    var needsHolderImport = false
    var needsArmorMaterialImport = false
    if (result.contains("ArmorMaterials.CODEC")) {
        result = result.replace("ArmorMaterials.CODEC", "BuiltInRegistries.ARMOR_MATERIAL.holderByNameCodec()")
        needsBuiltInRegistriesImport = true
    }
    result = migrateLegacyArmorMaterialInstanceofPatterns(result)
    result = Regex("""\bArmorMaterials\s+([A-Za-z_$][\w$]*)""")
        .replace(result) {
            needsHolderImport = true
            needsArmorMaterialImport = true
            "Holder<ArmorMaterial> ${it.groupValues[1]}"
        }
    result = Regex("""\bMap\s*<\s*ArmorMaterials\s*,\s*String\s*>""")
        .replace(result) {
            needsHolderImport = true
            needsArmorMaterialImport = true
            "Map<Holder<ArmorMaterial>, String>"
        }
    val switchMigrated = migrateLegacyArmorMaterialSwitches(result)
    if (switchMigrated != result) {
        result = switchMigrated
        needsHolderImport = true
        needsArmorMaterialImport = true
    }
    if (result.contains("Holder<ArmorMaterial>")) {
        needsHolderImport = true
        needsArmorMaterialImport = true
    }
    if (needsHolderImport) {
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
    }
    if (needsBuiltInRegistriesImport) {
        result = addImportIfMissing(result, "net.minecraft.core.registries.BuiltInRegistries")
    }
    if (needsArmorMaterialImport) {
        result = addImportIfMissing(result, "net.minecraft.world.item.ArmorMaterial")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyArmorMaterialInstanceofPatterns(source: String): String {
    if (!source.contains("instanceof ArmorMaterials")) return source
    val pattern = Regex("""if\s*\(\s*([^\r\n{};]+?)\s+instanceof\s+ArmorMaterials\s+([A-Za-z_$][\w$]*)\s*\)\s*\{""")
    var result = source
    var cursor = 0
    while (true) {
        val match = pattern.find(result, cursor) ?: break
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
        val indentStart = result.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        val indent = result.substring(indentStart, match.range.first).takeWhile { it == ' ' || it == '\t' }
        val materialExpression = match.groupValues[1].trim()
        val variable = match.groupValues[2]
        val body = result.substring(openBrace + 1, closeBrace)
        val vanillaGuard = vanillaArmorMaterialConstants.joinToString(" || ") {
            "$variable.is(ArmorMaterials.$it)"
        }
        val replacement = "{\n" +
            "$indent    Holder<ArmorMaterial> $variable = $materialExpression;\n" +
            "$indent    if ($vanillaGuard) {$body\n" +
            "$indent    }\n" +
            "$indent}"
        result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        cursor = match.range.first + replacement.length
    }
    return result
}

internal data class ArmorMaterialSwitchCase(
    val constants: List<String>,
    val body: String
)

internal fun StructuralRefactorPass.migrateLegacyArmorMaterialSwitches(source: String): String {
    if (!source.contains("switch") || !source.contains("Holder<ArmorMaterial>")) return source
    var result = source
    var cursor = 0
    val switchPattern = Regex("""switch\s*\(\s*([A-Za-z_$][\w$]*)\s*\)\s*\{""")
    while (true) {
        val match = switchPattern.find(result, cursor) ?: break
        val variable = match.groupValues[1]
        if (!holderArmorMaterialVariables(result).contains(variable)) {
            cursor = match.range.last + 1
            continue
        }
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
        val switchBody = result.substring(openBrace + 1, closeBrace)
        val cases = parseArmorMaterialSwitchCases(switchBody)
        if (cases == null) {
            cursor = closeBrace + 1
            continue
        }
        val indentStart = result.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        val indent = result.substring(indentStart, match.range.first).takeWhile { it == ' ' || it == '\t' }
        val replacement = buildString {
            cases.forEachIndexed { index, case ->
                if (index > 0) append(" else ")
                append("if")
                append(" (")
                append(case.constants.joinToString(" || ") { "$variable.is(ArmorMaterials.$it)" })
                append(") {")
                append(case.body.trimEnd())
                append("\n")
                append(indent)
                append("}")
            }
        }
        result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        cursor = match.range.first + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.holderArmorMaterialVariables(source: String): Set<String> =
    Regex("""\bHolder\s*<\s*ArmorMaterial\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()

internal fun StructuralRefactorPass.parseArmorMaterialSwitchCases(body: String): List<ArmorMaterialSwitchCase>? {
    val cases = mutableListOf<ArmorMaterialSwitchCase>()
    var cursor = 0
    while (true) {
        while (cursor < body.length && body[cursor].isWhitespace()) cursor++
        if (cursor >= body.length) break
        val match = Regex("""case\s+([A-Z0-9_,.\s]+?)\s*->""").find(body, cursor)
            ?: return null
        if (match.range.first != cursor) return null
        val constants = match.groupValues[1]
            .split(',')
            .map { it.trim().removePrefix("ArmorMaterials.") }
            .filter { it.isNotEmpty() }
        if (constants.isEmpty() || constants.any { it !in vanillaArmorMaterialConstants }) return null
        cursor = match.range.last + 1
        while (cursor < body.length && body[cursor].isWhitespace()) cursor++
        val caseBody = if (cursor < body.length && body[cursor] == '{') {
            val closeBrace = findMatchingBrace(body, cursor)
            if (closeBrace < 0) return null
            val text = body.substring(cursor + 1, closeBrace)
            cursor = closeBrace + 1
            text
        } else {
            val semicolon = body.indexOf(';', cursor)
            if (semicolon < 0) return null
            val text = body.substring(cursor, semicolon + 1)
            cursor = semicolon + 1
            "\n$text"
        }
        cases += ArmorMaterialSwitchCase(constants, caseBody)
    }
    return cases.takeIf { it.isNotEmpty() }
}

internal fun StructuralRefactorPass.migrateTextColorParseColorLiteralSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("TextColor.parseColor(")) return source
    val literalColor = Regex(""""#([0-9A-Fa-f]{6})"""")
    return rewriteExecutableJavaCall(source, "parseColor") { receiver, args ->
        if (receiver != "TextColor" || args.size != 1) return@rewriteExecutableJavaCall null
        val color = literalColor.matchEntire(args[0].trim()) ?: return@rewriteExecutableJavaCall null
        "TextColor.fromRgb(0x${color.groupValues[1].uppercase()})"
    }
}

internal fun StructuralRefactorPass.migrateGameProfileDisplayNameComponents(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ComponentUtils.getDisplayName(")) return source

    var result = rewriteExecutableJavaCall(source, "getDisplayName") { receiver, args ->
        if (receiver != "ComponentUtils" || args.size != 1) return@rewriteExecutableJavaCall null
        val profile = args[0].trim()
        "Component.literal($profile.getName())"
    }
    if (result == source) return source

    result = addImportIfMissing(result, "net.minecraft.network.chat.Component")
    val withoutComponentUtils = removeImport(result, "net.minecraft.network.chat.ComponentUtils")
    if (!Regex("""\bComponentUtils\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutComponentUtils))) {
        result = withoutComponentUtils
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBlockEntityRenderBoundingBoxOverride(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getRenderBoundingBox(") ||
        collectJavaClassDeclarations(executableCode).none { javaClassExtendsAny(it, javaBlockEntityBaseTypes(), javaInheritanceIndex) }) {
        return source
    }
    return replaceExecutableRegex(
        source,
        Regex("""(?m)^([ \t]*)@Override\s*\r?\n(?=\1(?:@[\w.]+(?:\([^)]*\))?\s*\r?\n\1)*public\s+(?:AABB|net\.minecraft\.world\.phys\.AABB)\s+getRenderBoundingBox\s*\()""")
    ) { "" }
}

internal fun StructuralRefactorPass.migrateRegistryAccessEmptyFallbacks(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    val qualifiedEmpty = "net.minecraft.core.RegistryAccess.EMPTY"
    val executableCode = maskJavaCommentsAndLiterals(source)
    val hasSimpleOwner = Regex(
        """(?m)^[ \t]*import\s+net\.minecraft\.core\.RegistryAccess\s*;[ \t]*$"""
    ).containsMatchIn(executableCode)
    val emptyPattern = if (hasSimpleOwner) {
        Regex(
            """${Regex.escape(qualifiedEmpty)}|(?<![\w$.])RegistryAccess\.EMPTY(?![\w$])"""
        )
    } else {
        Regex(Regex.escape(qualifiedEmpty))
    }
    if (!emptyPattern.containsMatchIn(executableCode)) return source
    val edits = mutableListOf<Pair<IntRange, String>>()
    emptyPattern.findAll(executableCode).forEach { match ->
        val index = match.range.first
        val replacement = registryAccessExpressionAt(source, index, javaInheritanceIndex)
        if (replacement == null || replacement == match.value) {
            throw IllegalStateException(
                "Cannot resolve exact HolderLookup.Provider for executable RegistryAccess.EMPTY at offset $index"
            )
        }
        edits += match.range to replacement
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateFluidTankNbtProviderCalls(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if ((!executableCode.contains(".writeToNBT(") && !executableCode.contains(".readFromNBT(")) ||
        !executableCode.contains("FluidTank")) {
        return source
    }
    val fluidTankType = """(?:FluidTank|net\.neoforged\.neoforge\.fluids\.capability\.templates\.FluidTank)"""
    val declaredTankVariables = collectSourceProvenFluidTankValueNames(source, javaInheritanceIndex)
    val lazyOptionalTankFields = Regex(
        """\bLazyOptional\s*<\s*$fluidTankType\s*>\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    val lazyOptionalTankVariables = lazyOptionalTankFields.flatMap { field ->
        Regex("""\b(?:this\.)?${Regex.escape(field)}\.ifPresent\s*\(\s*([A-Za-z_$][\w$]*)\s*->""")
            .findAll(executableCode)
            .map { it.groupValues[1] }
    }.toSet()
    val tankVariables = declaredTankVariables + lazyOptionalTankVariables
    if (tankVariables.isEmpty()) return source

    fun sourceProvenTankReceiver(receiver: String): Boolean {
        val normalized = receiver.filterNot(Char::isWhitespace)
        return tankVariables.any { tank -> normalized == tank || normalized.endsWith(".$tank") }
    }

    var result = source
    for (methodName in listOf("writeToNBT", "readFromNBT")) {
        result = rewriteExecutableJavaCallWithOffset(result, methodName) { receiver, args, offset ->
            if (args.size != 1 || !sourceProvenTankReceiver(receiver)) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val provider = registryAccessExpressionAt(result, offset, javaInheritanceIndex)
                ?: return@rewriteExecutableJavaCallWithOffset null
            "$receiver.$methodName($provider, ${args.single().trim()})"
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyFriendlyByteBufNbtCodecReads(source: String): String {
    if (!source.contains(".readWithCodec(")) return source
    val valueTypes = SourceValueTypes(this, source)
    return rewriteExecutableJavaCallWithOffset(source, "readWithCodec") { receiver, args, offset ->
        if (args.size != 2) return@rewriteExecutableJavaCallWithOffset null
        val type = valueTypes.typeOf(receiver, offset)
        if (type !in setOf(
                "net.minecraft.network.FriendlyByteBuf",
                "net.minecraft.network.RegistryFriendlyByteBuf"
            )
        ) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        "$receiver.readWithCodecTrusted(${args.joinToString(", ") { it.trim() }})"
    }
}

internal fun StructuralRefactorPass.migrateLegacyIdMapBufferCalls(source: String): String {
    if (!source.contains("GameData.getBlockStateIDMap()") ||
        !source.contains(".readById(") && !source.contains(".writeId(")
    ) {
        return source
    }
    val valueTypes = SourceValueTypes(this, source)
    if (!valueTypes.isTypeName("GameData", "net.neoforged.neoforge.registries.GameData")) return source
    fun isBlockStateMap(expression: String): Boolean =
        maskJavaCommentsAndLiterals(expression).filterNot(Char::isWhitespace) ==
            "GameData.getBlockStateIDMap()"

    var result = rewriteExecutableJavaCallWithOffset(source, "readById") { receiver, args, offset ->
        if (args.size != 1 || !isBlockStateMap(args[0])) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        val type = valueTypes.typeOf(receiver, offset)
        if (type !in setOf(
                "net.minecraft.network.FriendlyByteBuf",
                "net.minecraft.network.RegistryFriendlyByteBuf"
            )
        ) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        "$receiver.readById(GameData.getBlockStateIDMap()::byId)"
    }
    val migratedTypes = SourceValueTypes(this, result)
    val blockStateName = uniqueLocalNameInScope(result, "modporterBlockState")
    val blockStateIdName = uniqueLocalNameInScope(result, "modporterBlockStateId")
    result = rewriteExecutableJavaCallWithOffset(result, "writeId") { receiver, args, offset ->
        if (args.size != 2 || !isBlockStateMap(args[0])) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        val type = migratedTypes.typeOf(receiver, offset)
        if (type !in setOf(
                "net.minecraft.network.FriendlyByteBuf",
                "net.minecraft.network.RegistryFriendlyByteBuf"
            )
        ) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        "$receiver.writeById($blockStateName -> { " +
            "int $blockStateIdName = GameData.getBlockStateIDMap().getId($blockStateName); " +
            "if ($blockStateIdName == -1) throw new IllegalArgumentException(" +
            "\"Can't find id for '\" + $blockStateName + \"' in map \" + GameData.getBlockStateIDMap()); " +
            "return $blockStateIdName; }, ${args[1].trim()})"
    }
    return result
}

internal fun StructuralRefactorPass.migrateFluidStackNbtProviderCalls(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("FluidStack") ||
        !executableCode.contains(".writeToNBT(") &&
        !executableCode.contains("FluidStack.loadFluidStackFromNBT(")
    ) {
        return source
    }
    val fluidStackType = """(?:FluidStack|net\.neoforged\.neoforge\.fluids\.FluidStack)"""
    val fluidStackVariables = Regex("""\b$fluidStackType\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()

    var result = source
    // a freshly constructed stack written straight to NBT
    val constructedWrite = Regex("""new\s+FluidStack\(((?:[^()]|\([^()]*\))*)\)\.writeToNBT\(\s*((?:[^()]|\([^()]*\))*)\s*\)""")
    while (true) {
        val match = constructedWrite.find(maskJavaCommentsAndLiterals(result)) ?: break
        val provider = registryAccessExpressionAt(result, match.range.first, javaInheritanceIndex) ?: STATIC_REGISTRIES_PROVIDER
        val raw = result.substring(match.range.first, match.range.last + 1)
        val constructorArgs = raw.substring("new FluidStack(".length, raw.indexOf(").writeToNBT("))
        val tagArgument = raw.substring(raw.indexOf(").writeToNBT(") + ").writeToNBT(".length, raw.length - 1).trim()
        result = result.replaceRange(match.range, "(CompoundTag) new FluidStack($constructorArgs).save($provider, $tagArgument)")
    }
    for (fluidStack in fluidStackVariables) {
        // writeToNBT(tag) wrote an empty stack as minecraft:empty; save() throws "Cannot encode empty
        // FluidStack", so an empty stack leaves the tag untouched (parseOptional reads that as EMPTY)
        // and a fresh tag becomes saveOptional(), as the real ports write it
        val escaped = Regex.escape(fluidStack)
        val statement = Regex("""(?m)^([ \t]*)((?:this\.)?$escaped)\.writeToNBT\(\s*((?:[^(),]|\([^()]*\))+?)\s*\)\s*;[ \t]*$""")
        while (true) {
            val match = statement.find(maskJavaCommentsAndLiterals(result)) ?: break
            val provider = registryAccessExpressionAt(result, match.range.first, javaInheritanceIndex) ?: STATIC_REGISTRIES_PROVIDER
            val raw = result.substring(match.range.first, match.range.last + 1)
            val tag = raw.substring(raw.indexOf(".writeToNBT(") + ".writeToNBT(".length, raw.lastIndexOf(')')).trim()
            val receiver = match.groupValues[2]
            result = result.replaceRange(match.range, "${match.groupValues[1]}if (!$receiver.isEmpty()) $receiver.save($provider, $tag);")
        }
        result = rewriteExecutableJavaCallWithOffset(result, "writeToNBT") { receiver, args, offset ->
            if (receiver.trim() != fluidStack || args.size != 1) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val provider = registryAccessExpressionAt(result, offset, javaInheritanceIndex)
                ?: STATIC_REGISTRIES_PROVIDER
            val tag = args[0].trim()
            if (Regex("""^new\s+(?:net\.minecraft\.nbt\.)?CompoundTag\(\s*\)$""").matches(tag)) {
                "(CompoundTag) $receiver.saveOptional($provider)"
            } else {
                "($receiver.isEmpty() ? $tag : (CompoundTag) $receiver.save($provider, $tag))"
            }
        }
    }
    result = rewriteExecutableJavaCallWithOffset(result, "loadFluidStackFromNBT") { receiver, args, offset ->
        if (receiver.trim() != "FluidStack" || args.size != 1) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        val provider = registryAccessExpressionAt(result, offset, javaInheritanceIndex)
            ?: STATIC_REGISTRIES_PROVIDER
        "FluidStack.parseOptional($provider, ${args[0].trim()})"
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyFluidStackDeprecations(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("FluidStack") &&
        !executableCode.contains(".getDisplayName()") &&
        !executableCode.contains(".getTranslationKey()") &&
        !executableCode.contains(".isFluidEqual(")
    ) {
        return source
    }
    val fluidStackVariables = Regex("""\b(?:net\.neoforged\.neoforge\.fluids\.)?FluidStack\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    var result = source
    fluidStackVariables.forEach { variable ->
        result = replaceExecutableJavaRegex(result, Regex("""\b${Regex.escape(variable)}\.getDisplayName\(\)""")) {
            "$variable.getHoverName()"
        }
        result = replaceExecutableJavaRegex(result, Regex("""\b${Regex.escape(variable)}\.getTranslationKey\(\)""")) {
            "$variable.getDescriptionId()"
        }
    }
    result = rewriteExecutableJavaCallWithOffset(result, "getDisplayName") { receiver, args, _ ->
        if (args.isNotEmpty()) return@rewriteExecutableJavaCallWithOffset null
        val trimmed = receiver.trim()
        if (trimmed.startsWith("new FluidStack(") || trimmed.startsWith("new net.neoforged.neoforge.fluids.FluidStack(")) {
            "$trimmed.getHoverName()"
        } else {
            null
        }
    }
    result = rewriteExecutableJavaCallWithOffset(result, "getTranslationKey") { receiver, args, _ ->
        if (args.isNotEmpty()) return@rewriteExecutableJavaCallWithOffset null
        val trimmed = receiver.trim()
        if (trimmed.startsWith("new FluidStack(") || trimmed.startsWith("new net.neoforged.neoforge.fluids.FluidStack(")) {
            "$trimmed.getDescriptionId()"
        } else {
            null
        }
    }
    result = replaceFluidStackConstructorNoArgCall(result, "getDisplayName", "getHoverName")
    result = replaceFluidStackConstructorNoArgCall(result, "getTranslationKey", "getDescriptionId")
    result = rewriteExecutableJavaCallWithOffset(result, "isFluidEqual") { receiver, args, _ ->
        if (args.size != 1) return@rewriteExecutableJavaCallWithOffset null
        val trimmed = receiver.trim()
        val supportedReceiver = trimmed in fluidStackVariables ||
            trimmed.endsWith(".getFluid()") ||
            trimmed.startsWith("new FluidStack(") ||
            trimmed.startsWith("new net.neoforged.neoforge.fluids.FluidStack(")
        if (!supportedReceiver) return@rewriteExecutableJavaCallWithOffset null
        "FluidStack.isSameFluidSameComponents($trimmed, ${args[0].trim()})"
    }
    return result
}

internal fun StructuralRefactorPass.replaceFluidStackConstructorNoArgCall(source: String, oldMethod: String, newMethod: String): String {
    var result = source
    var cursor = 0
    while (cursor < result.length) {
        val executable = maskJavaCommentsAndLiterals(result)
        val match = Regex("""new\s+(?:net\.neoforged\.neoforge\.fluids\.)?FluidStack\s*\(""").find(executable, cursor) ?: break
        val openParen = executable.indexOf('(', match.range.last)
        if (openParen < 0) break
        val closeParen = findMatchingParen(executable, openParen)
        if (closeParen < 0) break
        val after = Regex("""^\s*\.\s*${Regex.escape(oldMethod)}\s*\(\s*\)""")
            .find(executable.substring(closeParen + 1))
        if (after == null) {
            cursor = closeParen + 1
            continue
        }
        val methodNameStart = closeParen + 1 + after.value.indexOf(oldMethod)
        result = result.substring(0, methodNameStart) + newMethod + result.substring(methodNameStart + oldMethod.length)
        cursor = methodNameStart + newMethod.length
    }
    return result
}

internal fun StructuralRefactorPass.migrateXlintCleanApiDeprecations(
    source: String,
    mobEffectDeferredHolderFields: Set<String>,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    var result = source
    result = migrateLegacyFoodEffectSuppliers(result)
    result = migrateDeprecatedTextureAtlasLocationConstants(result)
    result = migrateDeprecatedBlockStateLiquidChecks(result)
    result = migrateDeprecatedMobEffectHolderComparisons(result, mobEffectDeferredHolderFields)
    result = migrateWeightedEntryWrapperArraysToLists(result)
    result = migrateLegacyBlockCloneItemStackOverloadFamily(result)
    result = migrateDeprecatedStructureProcessorProcessBlockOverrides(result)
    result = migrateDeprecatedBlockRenderDispatcherSingleBlockCalls(result)
    result = migrateDeprecatedBakedModelGetQuadsCallSites(result)
    result = migrateDeprecatedItemTransformsConstructors(result)
    result = migrateDeprecatedStructureGenerationHelpers(result)
    result = migrateDeprecatedRenderSystemRunAsFancy(result)
    result = markRequiredDeprecatedOverrides(result)
    return result
}

internal fun StructuralRefactorPass.migrateLegacyFoodEffectSuppliers(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".effect(") || !executableCode.contains("MobEffectInstance")) return source
    var result = source
    var cursor = 0
    while (true) {
        val executable = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executable.indexOf(".effect(", cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + ".effect".length
        val closeParen = findMatchingParen(executable, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + ".effect(".length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 2) {
            cursor = closeParen + 1
            continue
        }
        val effect = args[0].trim()
        if (!effect.startsWith("new MobEffectInstance(") &&
            !effect.startsWith("new net.minecraft.world.effect.MobEffectInstance(")) {
            cursor = closeParen + 1
            continue
        }
        val replacementArgs = "() -> $effect, ${args[1].trim()}"
        result = result.substring(0, openParen + 1) + replacementArgs + result.substring(closeParen)
        cursor = openParen + 1 + replacementArgs.length
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeprecatedTextureAtlasLocationConstants(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("LOCATION_BLOCKS")) return source
    fun insideGeneratedFluidSpritesHelper(offset: Int): Boolean =
        javaMethodRangesIncludingDefault(source).any { method ->
            method.name == "modporter${'$'}getFluidSprites" && offset in method.range
        }
    var result = replaceExecutableJavaRegex(
        source,
        Regex("""\bnet\.minecraft\.client\.renderer\.texture\.TextureAtlas\.LOCATION_BLOCKS\b""")
    ) { match ->
        if (insideGeneratedFluidSpritesHelper(match.range.first)) {
            match.value
        } else {
            "net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS"
        }
    }
    result = replaceExecutableJavaRegex(result, Regex("""\bTextureAtlas\.LOCATION_BLOCKS\b""")) { match ->
        if (insideGeneratedFluidSpritesHelper(match.range.first)) {
            match.value
        } else {
            "InventoryMenu.BLOCK_ATLAS"
        }
    }
    if (result == source) return source
    if (maskJavaCommentsAndLiterals(result).contains("InventoryMenu.BLOCK_ATLAS")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.world.inventory.InventoryMenu")
    }
    val withoutTextureAtlas = removeExecutableImport(result, "net.minecraft.client.renderer.texture.TextureAtlas")
    result = if (!Regex("""(?<![\w$.])TextureAtlas(?![\w$])""").containsMatchIn(maskJavaCommentsAndLiterals(withoutTextureAtlas))) {
        withoutTextureAtlas
    } else {
        result
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeprecatedBlockStateLiquidChecks(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".liquid()")) return source
    var result = replaceExecutableJavaRegex(
        source,
        Regex("""!\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\(\))?)*)\.liquid\s*\(\s*\)""")
    ) { match ->
        "${match.groupValues[1]}.getFluidState().isEmpty()"
    }
    result = rewriteExecutableJavaCall(result, "liquid") { receiver, args ->
        if (args.isNotEmpty()) return@rewriteExecutableJavaCall null
        "!${receiver.trim()}.getFluidState().isEmpty()"
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeprecatedMobEffectHolderComparisons(
    source: String,
    mobEffectDeferredHolderFields: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".is(") ||
        !executableCode.contains("MobEffects.") &&
        mobEffectDeferredHolderFields.none { executableCode.contains(it.substringAfterLast('.', missingDelimiterValue = it)) }) {
        return source
    }

    val deferredHolderExpressions = mobEffectDeferredHolderFields
        .flatMap { field ->
            val parts = field.split('.')
            when {
                parts.size >= 2 -> listOf(field, parts.takeLast(2).joinToString("."))
                else -> listOf(field)
            }
        }
        .distinct()
        .sortedByDescending { it.length }
    val holderMobEffectFields = Regex(
        """(?m)^[ \t]*(?:private|protected|public)\s+(?:final\s+)?(?:net\.minecraft\.core\.)?Holder\s*<\s*(?:net\.minecraft\.world\.effect\.)?MobEffect\s*>\s+([A-Za-z_$][\w$]*)\b"""
    )
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()

    fun holderKeyExpression(argument: String): String? {
        val trimmed = argument.trim()
        if (Regex("""MobEffects\.[A-Z0-9_]+""").matches(trimmed)) {
            return "$trimmed.unwrapKey().orElseThrow()"
        }
        if (trimmed in deferredHolderExpressions) {
            return "$trimmed.getKey()"
        }
        return null
    }

    return rewriteExecutableJavaCall(source, "is") { receiver, args ->
        if (args.size != 1) return@rewriteExecutableJavaCall null
        val normalizedReceiver = receiver.trim().removePrefix("this.")
        if (normalizedReceiver in holderMobEffectFields) {
            val effect = Regex("""^(MobEffects\.[A-Z0-9_]+)(?:\.unwrapKey\(\)\.orElseThrow\(\))?$""")
                .find(args[0].trim())
                ?.groupValues
                ?.get(1)
            if (effect != null) {
                return@rewriteExecutableJavaCall "${receiver.trim()}.is($effect)"
            }
        }
        val key = holderKeyExpression(args[0]) ?: return@rewriteExecutableJavaCall null
        "${receiver.trim()}.is($key)"
    }
}

internal fun StructuralRefactorPass.migrateWeightedEntryWrapperArraysToLists(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("WeightedEntry.Wrapper") ||
        !executableCode.contains(".toArray(WeightedEntry.Wrapper[]::new)")) {
        return source
    }
    var changed = false
    var result = replaceExecutableJavaRegex(
        source,
        Regex("""(?ms)^([ \t]*)WeightedEntry\.Wrapper\s*<\s*([^>\r\n]+)\s*>\s*\[\]\s+([A-Za-z_$][\w$]*)\s*=\s*(.*?)\.toArray\s*\(\s*WeightedEntry\.Wrapper\[\]::new\s*\)\s*;""")
    ) { match ->
        changed = true
        val indent = match.groupValues[1]
        val type = match.groupValues[2].trim()
        val name = match.groupValues[3]
        val streamExpression = match.groupValues[4].trim()
        "${indent}List<WeightedEntry.Wrapper<$type>> $name = $streamExpression.toList();"
    }
    if (!changed) return source
    result = addExecutableImportIfMissing(result, "java.util.List")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBlockCloneItemStackOverrides(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String = migrateLegacyBlockCloneItemStackOverloadFamily(source)

internal fun StructuralRefactorPass.migrateDeprecatedStructureProcessorProcessBlockOverrides(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("processBlock(") ||
        !executableCode.contains("StructureTemplate.StructureBlockInfo") ||
        !executableCode.contains("StructurePlaceSettings")) {
        return source
    }
    var result = source
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executableCode)) {
        if (method.name != "processBlock") continue
        val methodText = result.substring(method.range)
        val methodExecutable = executableCode.substring(method.range)
        if (!Regex("""\bLevelReader\s+[A-Za-z_$][\w$]*""").containsMatchIn(methodExecutable) ||
            !Regex("""\bStructurePlaceSettings\s+[A-Za-z_$][\w$]*""").containsMatchIn(methodExecutable) ||
            !Regex("""\bStructureTemplate\.StructureBlockInfo\s+[A-Za-z_$][\w$]*""").containsMatchIn(methodExecutable)) {
            continue
        }
        val tokenIndex = methodExecutable.indexOf("processBlock")
        if (tokenIndex < 0) continue
        val openParen = methodExecutable.indexOf('(', tokenIndex)
        val closeParen = if (openParen >= 0) findMatchingParen(methodExecutable, openParen) else -1
        if (openParen < 0 || closeParen < 0) continue
        val params = splitTopLevelJavaArgs(methodText.substring(openParen + 1, closeParen))
        if (params.size != 6) continue
        val templateName = uniqueJavaIdentifier("structureTemplate", javaIdentifierTokens(methodText))
        val signatureMigrated = methodText.substring(0, tokenIndex) +
            "process" +
            methodText.substring(tokenIndex + "processBlock".length, closeParen) +
            ", @Nullable StructureTemplate $templateName" +
            methodText.substring(closeParen)
        val migratedMethod = rewriteExecutableJavaCall(signatureMigrated, "processBlock") { receiver, args ->
            if (receiver.trim() == "super" && args.size == 6) {
                "super.process(${args.joinToString(", ") { it.trim() }}, $templateName)"
            } else {
                null
            }
        }
        if (migratedMethod != methodText) {
            edits += method.range to migratedMethod
        }
    }
    if (edits.isEmpty()) return source
    result = applyStringEdits(source, edits)
    val executableResult = maskJavaCommentsAndLiterals(result)
    if (Regex("""(?<![\w$])@Nullable(?![\w$])""").containsMatchIn(executableResult) &&
        !executableResult.contains("import org.jetbrains.annotations.Nullable;") &&
        !executableResult.contains("import javax.annotation.Nullable;")) {
        result = addExecutableImportIfMissing(result, "javax.annotation.Nullable")
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeprecatedBlockRenderDispatcherSingleBlockCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".renderSingleBlock(")) return source
    val dispatcherVariables = javaVariablesOfType(executableCode, "BlockRenderDispatcher")
    var changed = false
    var result = rewriteExecutableJavaCall(source, "renderSingleBlock") { receiver, args ->
        if (args.size != 5 || !isBlockRenderDispatcherExpression(receiver, dispatcherVariables)) {
            return@rewriteExecutableJavaCall null
        }
        changed = true
        "${receiver.trim()}.renderSingleBlock(${args.joinToString(", ") { it.trim() }}, ModelData.EMPTY, null)"
    }
    if (changed) {
        result = addExecutableImportIfMissing(result, "net.neoforged.neoforge.client.model.data.ModelData")
    }
    return result
}

internal fun StructuralRefactorPass.isBlockRenderDispatcherExpression(receiver: String, dispatcherVariables: Set<String>): Boolean {
    val normalized = receiver.trim()
    if (normalized in dispatcherVariables) return true
    return normalized.endsWith(".getBlockRenderer()") ||
        normalized == "Minecraft.getInstance().getBlockRenderer()" ||
        normalized.endsWith(".blockRenderer") && normalized.substringAfterLast('.') in dispatcherVariables
}

internal fun StructuralRefactorPass.migrateDeprecatedBakedModelGetQuadsCallSites(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getQuads(")) return source
    val bakedModelVariables = javaVariablesOfType(executableCode, "BakedModel")
    var changed = false
    var result = rewriteExecutableJavaCall(source, "getQuads") { receiver, args ->
        if (args.size != 3 || !isBakedModelExpression(receiver, bakedModelVariables)) {
            return@rewriteExecutableJavaCall null
        }
        changed = true
        "${receiver.trim()}.getQuads(${args.joinToString(", ") { it.trim() }}, ModelData.EMPTY, null)"
    }
    if (changed) {
        result = addExecutableImportIfMissing(result, "net.neoforged.neoforge.client.model.data.ModelData")
    }
    return result
}

internal fun StructuralRefactorPass.isBakedModelExpression(receiver: String, bakedModelVariables: Set<String>): Boolean {
    val normalized = receiver.trim()
    if (normalized in bakedModelVariables) return true
    return normalized.contains(".getBlockModel(") ||
        normalized.contains(".getModel(")
}

internal fun StructuralRefactorPass.migrateDeprecatedItemTransformsConstructors(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("new ItemTransforms(") &&
        !executableCode.contains("new net.minecraft.client.renderer.block.model.ItemTransforms(")) {
        return source
    }
    var result = source
    var cursor = 0
    while (cursor < result.length) {
        val executable = maskJavaCommentsAndLiterals(result)
        val match = Regex("""new\s+(?:net\.minecraft\.client\.renderer\.block\.model\.)?ItemTransforms\s*\(""")
            .find(executable, cursor)
            ?: break
        val openParen = executable.indexOf('(', match.range.last)
        val closeParen = if (openParen >= 0) findMatchingParen(executable, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 8) {
            cursor = closeParen + 1
            continue
        }
        val replacement = args.joinToString(", ") { it.trim() } + ", com.google.common.collect.ImmutableMap.of()"
        result = result.substring(0, openParen + 1) + replacement + result.substring(closeParen)
        cursor = openParen + 1 + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.javaVariablesOfType(source: String, simpleType: String): Set<String> {
    val id = """[A-Za-z_$][\w$]*"""
    val type = """(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(simpleType)}"""
    return Regex("""(?<![\w$])(?:final\s+)?$type\s+($id)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
}

internal fun StructuralRefactorPass.migrateLegacyExplosionPositionCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getPosition()")) return source
    val explosionVariables = javaVariablesOfType(executableCode, "Explosion")
    return rewriteExecutableJavaCall(source, "getPosition") { receiver, args ->
        if (args.isNotEmpty()) return@rewriteExecutableJavaCall null
        val normalized = receiver.trim()
        if (normalized in explosionVariables || normalized.endsWith(".getExplosion()")) {
            "${normalized}.center()"
        } else {
            null
        }
    }
}

internal fun StructuralRefactorPass.migrateAbstractExplosionEventSubscribers(source: String): String {
    if (!source.contains("@SubscribeEvent") || !source.contains("ExplosionEvent")) return source
    var result = source
    val id = """[A-Za-z_$][\w$]*"""
    javaMethodRangesIncludingDefault(maskJavaCommentsAndLiterals(result))
        .asReversed()
        .forEach { method ->
            val header = result.substring(method.range.first, result.indexOf('{', method.range.first) + 1)
            val subscribeAnnotation = Regex("""(?m)^[ \t]*@SubscribeEvent(?:\([^)]*\))?\s*\r?\n""")
                .find(header)
                ?.value
                ?: return@forEach
            if (!Regex("""\bstatic\s+void\s+${Regex.escape(method.name)}\s*\(""").containsMatchIn(header)) {
                return@forEach
            }
            val eventParameter = Regex("""\bExplosionEvent\s+($id)\b""")
                .find(header)
                ?.groupValues
                ?.get(1)
                ?: return@forEach
            val helperHeader = header
                .replace(subscribeAnnotation, "")
                .replaceFirst(Regex("""\bpublic\s+static\s+void\b"""), "private static void")
                .replaceFirst(Regex("""\bprotected\s+static\s+void\b"""), "private static void")
            if (helperHeader == header) return@forEach
            val methodText = result.substring(method.range)
            val helperText = helperHeader + methodText.substring(header.length)
            val indent = lineIndentAt(result, method.range.first)
            val startName = uniqueLocalName(result, "${method.name}Start")
            val detonateName = uniqueLocalName(result + "\n$startName", "${method.name}Detonate")
            val wrappers = buildString {
                append(subscribeAnnotation)
                append("${indent}public static void $startName(ExplosionEvent.Start $eventParameter) {\n")
                append("${indent}    ${method.name}($eventParameter);\n")
                append("${indent}}\n\n")
                append(subscribeAnnotation)
                append("${indent}public static void $detonateName(ExplosionEvent.Detonate $eventParameter) {\n")
                append("${indent}    ${method.name}($eventParameter);\n")
                append("${indent}}\n\n")
            }
            result = result.substring(0, method.range.first) + wrappers + helperText + result.substring(method.range.last + 1)
        }
    return result
}

internal fun StructuralRefactorPass.migrateCandleCakeByCandlePatternVariables(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("instanceof CandleBlock") || !executableCode.contains("CandleCakeBlock.byCandle(")) {
        return source
    }
    val edits = mutableListOf<Pair<IntRange, String>>()
    val ifPattern = Regex("""if\s*\(\s*([A-Za-z_$][\w$]*)\s+instanceof\s+CandleBlock\s*\)\s*\{""")
    ifPattern.findAll(executableCode).forEach { match ->
        val blockVariable = match.groupValues[1]
        val openBrace = executableCode.indexOf('{', match.range.last - 1)
        if (openBrace < 0) return@forEach
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) return@forEach
        val blockText = source.substring(match.range.first, closeBrace + 1)
        val callPattern = Regex("""CandleCakeBlock\.byCandle\(\s*${Regex.escape(blockVariable)}\s*\)""")
        if (!callPattern.containsMatchIn(blockText)) return@forEach
        val patternVariable = uniqueLocalNameInScope(blockText, "candleBlock")
        val migrated = blockText
            .replace(
                Regex("""if\s*\(\s*${Regex.escape(blockVariable)}\s+instanceof\s+CandleBlock\s*\)""")
            ) {
                "if ($blockVariable instanceof CandleBlock $patternVariable)"
            }
            .replace(callPattern) {
                "CandleCakeBlock.byCandle($patternVariable)"
            }
        edits += match.range.first..closeBrace to migrated
    }
    return if (edits.isEmpty()) source else applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateDeprecatedLevelReaderHasChunksAtCalls(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    var compatPackageCache: String? = null
    fun compatPackage(): String {
        val existing = compatPackageCache
        if (existing != null) return existing
        val detected = detectRequiredGeneratedCompatPackage(projectDir, "LevelReader hasChunksAt compatibility helper")
        compatPackageCache = detected
        return detected
    }

    val changes = mutableListOf<Change>()
    var helperNeeded = false
    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(original)
        if (!executableCode.contains(".hasChunksAt(")) return@forEach
        val levelReaderVariables = javaVariablesOfLevelReaderType(executableCode)
        var changed = false
        var migrated = rewriteExecutableJavaCall(original, "hasChunksAt") { receiver, args ->
            if (args.size != 2 || !isLevelReaderExpression(receiver, levelReaderVariables)) {
                return@rewriteExecutableJavaCall null
            }
            changed = true
            helperNeeded = true
            "LevelReaderCompat.hasChunksAt(${receiver.trim()}, ${args[0].trim()}, ${args[1].trim()})"
        }
        if (!changed) return@forEach
        migrated = addExecutableImportIfMissing(migrated, "${compatPackage()}.LevelReaderCompat")
        if (!dryRun) javaFile.writeText(migrated)
        changes.add(Change(
            file = javaFile,
            line = 0,
            description = "Replace deprecated LevelReader.hasChunksAt(BlockPos, BlockPos) with non-deprecated chunk-range helper",
            before = "level.hasChunksAt(from, to)",
            after = "LevelReaderCompat.hasChunksAt(level, from, to)",
            confidence = Confidence.HIGH,
            ruleId = "struct-levelreader-haschunksat-helper"
        ))
    }

    if (helperNeeded) {
        val packageName = compatPackage()
        val helperFile = srcDir.resolve(packageName.replace('.', '/')).resolve("LevelReaderCompat.java")
        val helperSource = generatedLevelReaderCompatSource(packageName)
        val previous = if (helperFile.exists()) helperFile.readText() else null
        if (previous != helperSource) {
            if (!dryRun) {
                helperFile.parent.createDirectories()
                helperFile.writeText(helperSource)
            }
            changes.add(Change(
                file = helperFile,
                line = 0,
                description = "Generate LevelReader chunk-range helper using non-deprecated hasChunk calls",
                before = "deprecated LevelReader.hasChunksAt overloads",
                after = "LevelReaderCompat.hasChunksAt(LevelReader, BlockPos, BlockPos)",
                confidence = Confidence.HIGH,
                ruleId = "struct-levelreader-haschunksat-helper"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.javaVariablesOfLevelReaderType(source: String): Set<String> {
    val id = """[A-Za-z_$][\w$]*"""
    val type = """(?:[A-Za-z_$][\w$]*\.)*(?:LevelReader|LevelAccessor|ServerLevelAccessor|Level|ServerLevel|ClientLevel)"""
    return Regex("""(?<![\w$])(?:final\s+)?$type\s+($id)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
}

internal fun StructuralRefactorPass.isLevelReaderExpression(receiver: String, levelReaderVariables: Set<String>): Boolean {
    val normalized = receiver.trim()
    if (normalized in levelReaderVariables) return true
    return normalized == "level()" ||
        normalized == "this.level()" ||
        normalized.endsWith(".level()") ||
        normalized.endsWith(".getCommandSenderWorld()")
}

internal fun StructuralRefactorPass.generatedLevelReaderCompatSource(packageName: String): String =
    """
package $packageName;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class LevelReaderCompat {
    private LevelReaderCompat() {
    }

    public static boolean hasChunksAt(LevelReader level, BlockPos from, BlockPos to) {
        if (to.getY() < level.getMinBuildHeight() || from.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        int fromChunkX = SectionPos.blockToSectionCoord(from.getX());
        int toChunkX = SectionPos.blockToSectionCoord(to.getX());
        int fromChunkZ = SectionPos.blockToSectionCoord(from.getZ());
        int toChunkZ = SectionPos.blockToSectionCoord(to.getZ());
        for (int chunkX = fromChunkX; chunkX <= toChunkX; chunkX++) {
            for (int chunkZ = fromChunkZ; chunkZ <= toChunkZ; chunkZ++) {
                if (level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
""".trimStart()

internal data class EntityRendererRegistration(
    val entityTypeKey: String,
    val rendererSimpleName: String,
    val rendererFqn: String
)

internal data class EntityRendererMigrationResult(
    val source: String,
    val changed: Boolean,
    val requiredImports: Set<String>
)

internal fun StructuralRefactorPass.migrateXlintCleanApiBridges(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .filter { file ->
            !srcDir.relativize(file).toString().replace('\\', '/')
                .startsWith("com/modporter/generated/")
        }
        .toList()
    if (javaFiles.isEmpty()) return emptyList()

    var compatPackageCache: String? = null
    fun compatPackage(): String {
        val existing = compatPackageCache
        if (existing != null) return existing
        val detected = detectRequiredGeneratedCompatPackage(projectDir, "Xlint-clean API bridge helpers")
        compatPackageCache = detected
        return detected
    }

    val types = collectJavaSourceTypes(javaFiles)
    val rendererRegistrations = collectEntityRendererRegistrations(types)
    val rendererTargets = collectConcreteEntityRendererTargets(types)
    val changes = mutableListOf<Change>()
    var needsDatagenCompat = false
    var needsDefaultAttributesCompat = false

    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        var migrated = original

        val rendererMigrated = migrateRendererMapCastsToConcreteRenderersSource(
            migrated,
            rendererRegistrations,
            rendererTargets
        )
        if (rendererMigrated.changed) {
            migrated = rendererMigrated.source
            rendererMigrated.requiredImports.forEach { importName ->
                migrated = addExecutableImportIfMissing(migrated, importName)
            }
            migrated = removeUnusedSimpleImport(
                migrated,
                "net.minecraft.client.renderer.entity.EntityRenderer",
                "EntityRenderer"
            )
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Replace unchecked EntityRenderDispatcher renderer-map cast with source-registered concrete renderer type",
                before = "(EntityRenderer<T>) dispatcher.renderers.get(entityType)",
                after = "(ConcreteRenderer) dispatcher.renderers.get(entityType)",
                confidence = Confidence.HIGH,
                ruleId = "struct-xlint-concrete-entity-renderer-cast"
            ))
        }

        val datagenMigrated = migrateDeprecatedDatagenCodecConstructorsSource(migrated) { compatPackage() }
        if (datagenMigrated.first != migrated) {
            migrated = datagenMigrated.first
            needsDatagenCompat = needsDatagenCompat || datagenMigrated.second
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Construct deprecated datagen-only placement and loot functions through their official codecs",
                before = "deprecated 1.21 datagen builder calls",
                after = "DatagenCompat codec-backed builders",
                confidence = Confidence.HIGH,
                ruleId = "struct-xlint-datagen-codec-bridges"
            ))
        }

        val defaultAttributesMigrated = migrateDefaultAttributesSupplierCastsSource(migrated) { compatPackage() }
        if (defaultAttributesMigrated.first != migrated) {
            migrated = defaultAttributesMigrated.first
            needsDefaultAttributesCompat = needsDefaultAttributesCompat || defaultAttributesMigrated.second
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Replace unchecked DefaultAttributes.getSupplier EntityType cast with AT-backed helper",
                before = "DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type)",
                after = "DefaultAttributesCompat.getSupplier(type)",
                confidence = Confidence.HIGH,
                ruleId = "struct-xlint-defaultattributes-helper"
            ))
        }

        if (migrated != original && !dryRun) {
            javaFile.writeText(migrated)
        }
    }

    if (needsDatagenCompat) {
        changes.addAll(ensureGeneratedCompatJavaSource(
            srcDir = srcDir,
            packageName = compatPackage(),
            className = "DatagenCompat",
            source = generatedDatagenCompatSource(compatPackage()),
            dryRun = dryRun,
            description = "Generate codec-backed datagen helper for deprecated 1.21 builders",
            before = "deprecated datagen builder APIs",
            after = "DatagenCompat",
            ruleId = "struct-xlint-datagen-codec-bridges"
        ))
    }
    if (needsDefaultAttributesCompat) {
        changes.addAll(ensureGeneratedCompatJavaSource(
            srcDir = srcDir,
            packageName = compatPackage(),
            className = "DefaultAttributesCompat",
            source = generatedDefaultAttributesCompatSource(compatPackage()),
            dryRun = dryRun,
            description = "Generate AT-backed DefaultAttributes supplier helper",
            before = "unchecked EntityType<? extends LivingEntity> cast",
            after = "DefaultAttributesCompat.getSupplier(EntityType<?>)",
            ruleId = "struct-xlint-defaultattributes-helper"
        ))
    }

    return changes
}

internal fun StructuralRefactorPass.ensureGeneratedCompatJavaSource(
    srcDir: Path,
    packageName: String,
    className: String,
    source: String,
    dryRun: Boolean,
    description: String,
    before: String,
    after: String,
    ruleId: String
): List<Change> {
    val helperFile = srcDir.resolve(packageName.replace('.', '/')).resolve("$className.java")
    val previous = if (helperFile.exists()) helperFile.readText() else null
    if (previous == source) return emptyList()
    if (!dryRun) {
        helperFile.parent.createDirectories()
        helperFile.writeText(source)
    }
    return listOf(Change(
        file = helperFile,
        line = 0,
        description = description,
        before = before,
        after = after,
        confidence = Confidence.HIGH,
        ruleId = ruleId
    ))
}

internal fun StructuralRefactorPass.collectEntityRendererRegistrations(types: List<JavaSourceType>): Map<String, EntityRendererRegistration> {
    val byEntityType = linkedMapOf<String, EntityRendererRegistration>()
    val typesBySimpleName = types.groupBy { it.className }
    types.forEach { type ->
        val imports = javaImportMap(type.source)
        val executableCode = maskJavaCommentsAndLiterals(type.source)
        var cursor = 0
        while (true) {
            val tokenIndex = executableCode.indexOf(".registerEntityRenderer(", cursor)
            if (tokenIndex < 0) break
            val openParen = tokenIndex + ".registerEntityRenderer".length
            val closeParen = findMatchingParen(executableCode, openParen)
            if (closeParen < 0) {
                cursor = tokenIndex + ".registerEntityRenderer(".length
                continue
            }
            val args = splitTopLevelJavaArgs(type.source.substring(openParen + 1, closeParen))
            if (args.size >= 2) {
                val rendererRef = Regex("""^\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*::\s*new\s*$""")
                    .find(args[1])
                    ?.groupValues
                    ?.get(1)
                val rendererFqn = rendererRef?.let {
                    resolveProjectJavaTypeReference(it, type.packageName, imports, typesBySimpleName)
                }
                if (rendererRef != null && rendererFqn != null) {
                    val registration = EntityRendererRegistration(
                        entityTypeKey = normalizedJavaExpressionKey(args[0]),
                        rendererSimpleName = rendererRef.substringAfterLast('.'),
                        rendererFqn = rendererFqn
                    )
                    byEntityType.putIfAbsent(registration.entityTypeKey, registration)
                }
            }
            cursor = closeParen + 1
        }
    }
    return byEntityType
}

internal fun StructuralRefactorPass.collectConcreteEntityRendererTargets(types: List<JavaSourceType>): Map<String, String> {
    val targets = linkedMapOf<String, String>()
    val knownRendererBases = setOf(
        "EntityRenderer",
        "LivingEntityRenderer",
        "MobRenderer",
        "AgeableMobRenderer",
        "HumanoidMobRenderer"
    )
    types.forEach { type ->
        val executableCode = maskJavaCommentsAndLiterals(type.source)
        val classMatch = Regex(
            """(?m)\bclass\s+${Regex.escape(type.className)}(?:\s*<[^>{}]+>)?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*<\s*([^,>]+)"""
        ).find(executableCode) ?: return@forEach
        val superSimpleName = classMatch.groupValues[1].substringAfterLast('.')
        if (superSimpleName !in knownRendererBases) return@forEach
        targets[type.fqn] = normalizedJavaTypeKey(classMatch.groupValues[2])
    }
    return targets
}

internal fun StructuralRefactorPass.migrateRendererMapCastsToConcreteRenderersSource(
    source: String,
    registrations: Map<String, EntityRendererRegistration>,
    rendererTargets: Map<String, String>
): EntityRendererMigrationResult {
    if (registrations.isEmpty() || !source.contains("renderers.get(") || !source.contains("EntityRenderer<")) {
        return EntityRendererMigrationResult(source, changed = false, requiredImports = emptySet())
    }
    val packageName = packageNameOf(source)
    val executableCode = maskJavaCommentsAndLiterals(source)
    val id = """[A-Za-z_$][\w$]*"""
    val declarationPattern = Regex(
        """(?m)^([ \t]*)(final\s+)?(?:net\.minecraft\.client\.renderer\.entity\.)?EntityRenderer\s*<\s*([^>\r\n;]+?)\s*>\s+($id)\s*=\s*\(\s*(?:net\.minecraft\.client\.renderer\.entity\.)?EntityRenderer\s*<\s*([^>\r\n;]+?)\s*>\s*\)\s*"""
    )
    val edits = mutableListOf<Pair<IntRange, String>>()
    val imports = linkedSetOf<String>()
    for (match in declarationPattern.findAll(executableCode)) {
        val expressionStart = match.range.last + 1
        val semicolon = findJavaStatementSemicolon(executableCode, expressionStart)
        if (semicolon < 0) continue
        val renderersGetIndex = executableCode.indexOf(".renderers.get(", expressionStart)
            .takeIf { it >= 0 && it < semicolon }
            ?: continue
        val openParen = renderersGetIndex + ".renderers.get".length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0 || closeParen > semicolon) continue
        val entityTypeKey = normalizedJavaExpressionKey(source.substring(openParen + 1, closeParen))
        val registration = registrations[entityTypeKey] ?: continue
        val expectedTarget = normalizedJavaTypeKey(match.groupValues[3])
        val castTarget = normalizedJavaTypeKey(match.groupValues[5])
        if (expectedTarget != castTarget) continue
        val rendererTarget = rendererTargets[registration.rendererFqn] ?: continue
        if (rendererTarget != expectedTarget) continue

        val rendererPackage = registration.rendererFqn.substringBeforeLast('.', "")
        val rendererReference = registration.rendererSimpleName
        if (rendererPackage.isNotBlank() && rendererPackage != packageName) {
            imports += registration.rendererFqn
        }
        val expression = source.substring(expressionStart, semicolon).trim()
        val replacement = buildString {
            append(match.groupValues[1])
            append(match.groupValues[2])
            append(rendererReference)
            append(' ')
            append(match.groupValues[4])
            append(" = (")
            append(rendererReference)
            append(") ")
            append(expression)
            append(';')
        }
        edits += match.range.first..semicolon to replacement
    }
    if (edits.isEmpty()) return EntityRendererMigrationResult(source, changed = false, requiredImports = emptySet())
    var result = source
    for ((range, replacement) in edits.asReversed()) {
        result = result.substring(0, range.first) + replacement + result.substring(range.last + 1)
    }
    return EntityRendererMigrationResult(result, changed = true, requiredImports = imports)
}

internal fun StructuralRefactorPass.migrateDeprecatedDatagenCodecConstructorsSource(
    source: String,
    generatedCompatPackage: () -> String
): Pair<String, Boolean> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("CopyCustomDataFunction.copyData(") &&
        !executableCode.contains("SetCustomDataFunction.setCustomData(") &&
        !executableCode.contains("CountOnEveryLayerPlacement.of(")) {
        return source to false
    }

    var result = source
    var helperNeeded = false
    val copyDataMigrated = migrateCopyCustomDataBuilderChains(result)
    result = copyDataMigrated.first
    helperNeeded = helperNeeded || copyDataMigrated.second
    val countOnEveryLayerMigrated = migrateCountOnEveryLayerPlacementCalls(result)
    result = countOnEveryLayerMigrated.first
    helperNeeded = helperNeeded || countOnEveryLayerMigrated.second
    result = rewriteExecutableJavaCall(result, "copy") { receiver, args ->
        if (args.size != 3 || !isBlockEntityCopyCustomDataBuilder(receiver)) return@rewriteExecutableJavaCall null
        val strategy = copyCustomDataMergeStrategyName(args[2]) ?: return@rewriteExecutableJavaCall null
        helperNeeded = true
        "DatagenCompat.copyBlockEntityCustomData(${args[0].trim()}, ${args[1].trim()}, \"$strategy\")"
    }
    result = rewriteExecutableJavaCall(result, "setCustomData") { receiver, args ->
        if (args.size != 1 || !receiver.trim().endsWith("SetCustomDataFunction")) return@rewriteExecutableJavaCall null
        helperNeeded = true
        "DatagenCompat.setCustomData(${args[0].trim()})"
    }
    result = rewriteExecutableJavaCall(result, "of") { receiver, args ->
        if (args.size != 1 || !receiver.trim().endsWith("CountOnEveryLayerPlacement")) return@rewriteExecutableJavaCall null
        helperNeeded = true
        "DatagenCompat.countOnEveryLayer(${args[0].trim()})"
    }

    if (!helperNeeded) return source to false
    result = addExecutableImportIfMissing(result, "${generatedCompatPackage()}.DatagenCompat")
    result = removeUnusedSimpleImport(
        result,
        "net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider",
        "ContextNbtProvider"
    )
    result = removeUnusedSimpleImport(
        result,
        "net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction",
        "CopyCustomDataFunction"
    )
    result = removeUnusedSimpleImport(
        result,
        "net.minecraft.world.level.storage.loot.functions.SetCustomDataFunction",
        "SetCustomDataFunction"
    )
    result = removeUnusedSimpleImport(
        result,
        "net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement",
        "CountOnEveryLayerPlacement"
    )
    return result to true
}

internal fun StructuralRefactorPass.migrateCopyCustomDataBuilderChains(source: String): Pair<String, Boolean> {
    var result = source
    var changed = false
    var cursor = 0
    val tokens = listOf(
        "CopyCustomDataFunction.copyData(",
        "net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction.copyData("
    )
    while (cursor < result.length) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenMatch = tokens.mapNotNull { token ->
            val index = executableCode.indexOf(token, cursor)
            if (index >= 0) index to token else null
        }.minByOrNull { it.first } ?: break
        val tokenIndex = tokenMatch.first
        val token = tokenMatch.second
        val copyDataOpen = tokenIndex + token.removeSuffix("(").length
        val copyDataClose = findMatchingParen(executableCode, copyDataOpen)
        if (copyDataClose < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val providerArgs = splitTopLevelJavaArgs(result.substring(copyDataOpen + 1, copyDataClose))
        if (providerArgs.size != 1 ||
            providerArgs[0].trim() !in setOf(
                "ContextNbtProvider.BLOCK_ENTITY",
                "net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider.BLOCK_ENTITY"
            )) {
            cursor = copyDataClose + 1
            continue
        }
        var copyTokenIndex = copyDataClose + 1
        while (copyTokenIndex < executableCode.length && executableCode[copyTokenIndex].isWhitespace()) copyTokenIndex++
        if (!executableCode.startsWith(".copy(", copyTokenIndex)) {
            cursor = copyDataClose + 1
            continue
        }
        val copyOpen = copyTokenIndex + ".copy".length
        val copyClose = findMatchingParen(executableCode, copyOpen)
        if (copyClose < 0) {
            cursor = copyTokenIndex + ".copy(".length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(copyOpen + 1, copyClose))
        if (args.size != 3) {
            cursor = copyClose + 1
            continue
        }
        val strategy = copyCustomDataMergeStrategyName(args[2])
        if (strategy == null) {
            cursor = copyClose + 1
            continue
        }
        val replacement = "DatagenCompat.copyBlockEntityCustomData(${args[0].trim()}, ${args[1].trim()}, \"$strategy\")"
        result = result.substring(0, tokenIndex) + replacement + result.substring(copyClose + 1)
        cursor = tokenIndex + replacement.length
        changed = true
    }
    return result to changed
}

internal fun StructuralRefactorPass.migrateCountOnEveryLayerPlacementCalls(source: String): Pair<String, Boolean> {
    var result = source
    var changed = false
    var cursor = 0
    val tokens = listOf(
        "CountOnEveryLayerPlacement.of(",
        "net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement.of("
    )
    while (cursor < result.length) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenMatch = tokens.mapNotNull { token ->
            val index = executableCode.indexOf(token, cursor)
            if (index >= 0) index to token else null
        }.minByOrNull { it.first } ?: break
        val tokenIndex = tokenMatch.first
        val token = tokenMatch.second
        val openParen = tokenIndex + token.removeSuffix("(").length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 1) {
            cursor = closeParen + 1
            continue
        }
        val replacement = "DatagenCompat.countOnEveryLayer(${args[0].trim()})"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
        changed = true
    }
    return result to changed
}

internal fun StructuralRefactorPass.isBlockEntityCopyCustomDataBuilder(receiver: String): Boolean {
    val trimmed = receiver.trim()
    if (!trimmed.startsWith("CopyCustomDataFunction.copyData(") &&
        !trimmed.startsWith("net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction.copyData(")) {
        return false
    }
    val openParen = trimmed.indexOf("copyData(").takeIf { it >= 0 }?.let { it + "copyData".length } ?: return false
    val closeParen = findMatchingParen(trimmed, openParen)
    if (closeParen < 0 || trimmed.substring(closeParen + 1).isNotBlank()) return false
    val args = splitTopLevelJavaArgs(trimmed.substring(openParen + 1, closeParen))
    if (args.size != 1) return false
    val sourceProvider = args[0].trim()
    return sourceProvider == "ContextNbtProvider.BLOCK_ENTITY" ||
        sourceProvider == "net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider.BLOCK_ENTITY"
}

internal fun StructuralRefactorPass.copyCustomDataMergeStrategyName(argument: String): String? {
    val trimmed = argument.trim()
    val match = Regex("""^(?:net\.minecraft\.world\.level\.storage\.loot\.functions\.)?CopyCustomDataFunction\.MergeStrategy\.([A-Z_]+)$""")
        .find(trimmed)
        ?: return null
    return match.groupValues[1].lowercase()
}

internal fun StructuralRefactorPass.migrateDefaultAttributesSupplierCastsSource(
    source: String,
    generatedCompatPackage: () -> String
): Pair<String, Boolean> {
    if (!source.contains("DefaultAttributes.getSupplier(")) return source to false
    var helperNeeded = false
    var result = rewriteExecutableJavaCall(source, "getSupplier") { receiver, args ->
        if (args.size != 1 || !receiver.trim().endsWith("DefaultAttributes")) return@rewriteExecutableJavaCall null
        val entityTypeExpression = entityTypeExtendsLivingEntityCastArgument(args[0])
            ?: return@rewriteExecutableJavaCall null
        helperNeeded = true
        "DefaultAttributesCompat.getSupplier($entityTypeExpression)"
    }
    if (!helperNeeded) return source to false
    result = addExecutableImportIfMissing(result, "${generatedCompatPackage()}.DefaultAttributesCompat")
    result = removeUnusedSimpleImport(
        result,
        "net.minecraft.world.entity.ai.attributes.DefaultAttributes",
        "DefaultAttributes"
    )
    return result to true
}

internal fun StructuralRefactorPass.entityTypeExtendsLivingEntityCastArgument(argument: String): String? =
    Regex(
        """^\s*\(\s*(?:net\.minecraft\.world\.entity\.)?EntityType\s*<\s*\?\s+extends\s+(?:net\.minecraft\.world\.entity\.)?LivingEntity\s*>\s*\)\s*(.+?)\s*$""",
        RegexOption.DOT_MATCHES_ALL
    ).find(argument)
        ?.groupValues
        ?.get(1)
        ?.trim()

internal fun StructuralRefactorPass.generatedDatagenCompatSource(packageName: String): String =
    """
package $packageName;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;

public final class DatagenCompat {
    private DatagenCompat() {
    }

    public static PlacementModifier countOnEveryLayer(int count) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:count_on_every_layer");
        json.addProperty("count", count);
        return PlacementModifier.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    public static LootItemFunction.Builder copyBlockEntityCustomData(String sourcePath, String targetPath, String mergeStrategy) {
        return () -> {
            JsonObject operation = new JsonObject();
            operation.addProperty("source", sourcePath);
            operation.addProperty("target", targetPath);
            operation.addProperty("op", mergeStrategy);
            JsonArray operations = new JsonArray();
            operations.add(operation);
            JsonObject json = new JsonObject();
            json.addProperty("function", "minecraft:copy_custom_data");
            json.addProperty("source", "block_entity");
            json.add("ops", operations);
            return LootItemFunctions.TYPED_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        };
    }

    public static LootItemFunction.Builder setCustomData(CompoundTag tag) {
        return () -> {
            JsonObject json = new JsonObject();
            json.addProperty("function", "minecraft:set_custom_data");
            json.addProperty("tag", tag.toString());
            return LootItemFunctions.TYPED_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        };
    }
}
""".trimStart()

internal fun StructuralRefactorPass.generatedDefaultAttributesCompatSource(packageName: String): String =
    """
package $packageName;

import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.neoforged.neoforge.common.CommonHooks;

public final class DefaultAttributesCompat {
    private DefaultAttributesCompat() {
    }

    public static AttributeSupplier getSupplier(EntityType<?> entityType) {
        AttributeSupplier supplier = forgeAttributes().get(entityType);
        return supplier != null ? supplier : DefaultAttributes.SUPPLIERS.get(entityType);
    }

    @Deprecated
    private static Map<EntityType<? extends LivingEntity>, AttributeSupplier> forgeAttributes() {
        return CommonHooks.getAttributesView();
    }
}
""".trimStart()

internal fun StructuralRefactorPass.javaImportMap(source: String): Map<String, String> {
    val imports = linkedMapOf<String, String>()
    Regex("""(?m)^[ \t]*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
        .findAll(maskJavaCommentsAndLiterals(source))
        .forEach { match ->
            val fqn = match.groupValues[1]
            imports[fqn.substringAfterLast('.')] = fqn
        }
    return imports
}

internal fun StructuralRefactorPass.resolveProjectJavaTypeReference(
    reference: String,
    packageName: String,
    imports: Map<String, String>,
    typesBySimpleName: Map<String, List<JavaSourceType>>
): String? {
    val trimmed = reference.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.contains('.')) {
        val firstSegment = trimmed.substringBefore('.')
        imports[firstSegment]?.let { imported ->
            return imported + "." + trimmed.substringAfter('.')
        }
        if (firstSegment.firstOrNull()?.isLowerCase() == true) return trimmed
        val samePackage = if (packageName.isBlank()) trimmed else "$packageName.$trimmed"
        if (typesBySimpleName[trimmed.substringAfterLast('.')]?.any { it.fqn == samePackage } == true) {
            return samePackage
        }
        return null
    }
    imports[trimmed]?.let { return it }
    val samePackage = if (packageName.isBlank()) trimmed else "$packageName.$trimmed"
    if (typesBySimpleName[trimmed]?.any { it.fqn == samePackage } == true) return samePackage
    return typesBySimpleName[trimmed]?.singleOrNull()?.fqn
}

internal fun StructuralRefactorPass.normalizedJavaExpressionKey(expression: String): String =
    expression.replace(Regex("""\s+"""), "")

internal fun StructuralRefactorPass.normalizedJavaTypeKey(type: String): String =
    type.trim()
        .removePrefix("? extends ")
        .removePrefix("? super ")
        .substringBefore('<')
        .substringAfterLast('.')
        .replace(Regex("""\s+"""), "")

internal fun StructuralRefactorPass.migrateDeprecatedStructureGenerationHelpers(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getLowestYIn5by5BoxOffset7Blocks(") &&
        !executableCode.contains(".moveInsideHeights(")) {
        return source
    }
    var result = source
    var needsLowestHelper = false
    var needsMoveInsideHelper = false
    if (maskJavaCommentsAndLiterals(result).contains("getLowestYIn5by5BoxOffset7Blocks(")) {
        result = rewriteExecutableJavaCall(result, "getLowestYIn5by5BoxOffset7Blocks") { receiver, args ->
            if (args.size != 2 || receiver.trim() !in setOf("this", "super")) {
                return@rewriteExecutableJavaCall null
            }
            needsLowestHelper = true
            "modporter${'$'}getLowestYIn5by5BoxOffset7Blocks(${args[0].trim()}, ${args[1].trim()})"
        }
    }
    if (maskJavaCommentsAndLiterals(result).contains(".moveInsideHeights(")) {
        val builderVariables = javaVariablesOfType(maskJavaCommentsAndLiterals(result), "StructurePiecesBuilder")
        result = rewriteExecutableJavaCall(result, "moveInsideHeights") { receiver, args ->
            if (args.size != 3 || receiver.trim() !in builderVariables) {
                return@rewriteExecutableJavaCall null
            }
            needsMoveInsideHelper = true
            "modporter${'$'}moveInsideHeights(${receiver.trim()}, ${args[0].trim()}, ${args[1].trim()}, ${args[2].trim()})"
        }
    }
    if (needsLowestHelper && !maskJavaCommentsAndLiterals(result).contains("modporter${'$'}getLowestYIn5by5BoxOffset7Blocks(Structure.GenerationContext")) {
        result = insertBeforeLastClassBrace(result, generatedLowestYIn5By5Helper())
        result = addExecutableImportIfMissing(result, "net.minecraft.world.level.ChunkPos")
    }
    if (needsMoveInsideHelper && !maskJavaCommentsAndLiterals(result).contains("modporter${'$'}moveInsideHeights(StructurePiecesBuilder")) {
        result = insertBeforeLastClassBrace(result, generatedMoveInsideHeightsHelper())
        result = addExecutableImportIfMissing(result, "net.minecraft.util.RandomSource")
        result = addExecutableImportIfMissing(result, "net.minecraft.world.level.levelgen.structure.BoundingBox")
        result = addExecutableImportIfMissing(result, "net.minecraft.world.level.levelgen.structure.StructurePiece")
        result = addExecutableImportIfMissing(result, "net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder")
    }
    return result
}

internal fun StructuralRefactorPass.generatedLowestYIn5By5Helper(): String =
    """

    private static BlockPos modporter${'$'}getLowestYIn5by5BoxOffset7Blocks(Structure.GenerationContext context, Rotation rotation) {
        int xOffset = 5;
        int zOffset = 5;
        if (rotation == Rotation.CLOCKWISE_90) {
            xOffset = -5;
        } else if (rotation == Rotation.CLOCKWISE_180) {
            xOffset = -5;
            zOffset = -5;
        } else if (rotation == Rotation.COUNTERCLOCKWISE_90) {
            zOffset = -5;
        }

        ChunkPos chunkPos = context.chunkPos();
        int x = chunkPos.getBlockX(7);
        int z = chunkPos.getBlockZ(7);
        return new BlockPos(x, getLowestY(context, x, z, xOffset, zOffset), z);
    }
""".trimEnd()

internal fun StructuralRefactorPass.generatedMoveInsideHeightsHelper(): String =
    """

    private static void modporter${'$'}moveInsideHeights(StructurePiecesBuilder builder, RandomSource random, int minY, int maxY) {
        BoundingBox boundingBox = builder.getBoundingBox();
        int availableHeight = maxY - minY + 1 - boundingBox.getYSpan();
        int targetY;
        if (availableHeight > 1) {
            targetY = minY + random.nextInt(availableHeight);
        } else {
            targetY = minY;
        }

        int offset = targetY - boundingBox.minY();
        for (StructurePiece piece : builder.build().pieces()) {
            piece.move(0, offset, 0);
        }
    }
""".trimEnd()

internal fun StructuralRefactorPass.migrateDeprecatedRenderSystemRunAsFancy(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".runAsFancy(")) return source
    var needsHelper = false
    var result = rewriteExecutableJavaCall(source, "runAsFancy") { receiver, args ->
        val target = receiver.trim()
        if (args.size != 1 ||
            target != "RenderSystem" &&
            target != "com.mojang.blaze3d.systems.RenderSystem") {
            return@rewriteExecutableJavaCall null
        }
        needsHelper = true
        "modporter${'$'}runAsFancy(${args[0].trim()})"
    }
    if (!needsHelper) return source
    if (!maskJavaCommentsAndLiterals(result).contains("modporter${'$'}runAsFancy(Runnable")) {
        result = insertBeforeLastClassBrace(result, generatedRunAsFancyHelper())
    }
    result = addExecutableImportIfMissing(result, "net.minecraft.client.GraphicsStatus")
    result = addExecutableImportIfMissing(result, "net.minecraft.client.Minecraft")
    result = addExecutableImportIfMissing(result, "net.minecraft.client.OptionInstance")
    result = removeSimpleImportIfUnused(result, "com.mojang.blaze3d.systems.RenderSystem", "RenderSystem")
    return result
}

internal fun StructuralRefactorPass.generatedRunAsFancyHelper(): String =
    """

    private static void modporter${'$'}runAsFancy(Runnable fancyRunnable) {
        boolean shaderTransparency = Minecraft.useShaderTransparency();
        if (!shaderTransparency) {
            fancyRunnable.run();
        } else {
            OptionInstance<GraphicsStatus> graphicsMode = Minecraft.getInstance().options.graphicsMode();
            GraphicsStatus previousGraphicsStatus = graphicsMode.get();
            graphicsMode.set(GraphicsStatus.FANCY);
            fancyRunnable.run();
            graphicsMode.set(previousGraphicsStatus);
        }
    }
""".trimEnd()

internal fun StructuralRefactorPass.markRequiredDeprecatedOverrides(source: String): String {
    var result = markFinalizeSpawnRequiredDeprecatedOverride(source)
    val executableCode = maskJavaCommentsAndLiterals(result)
    if (!executableCode.contains("@Override") ||
        !executableCode.contains("getParticleIcon(") &&
        !executableCode.contains("getTransforms(") &&
        !executableCode.contains("finalizeSpawn(")) {
        return result
    }
    val insertions = mutableListOf<Pair<Int, String>>()
    for (method in javaMethodRangesIncludingDefault(executableCode)) {
        if (Regex("""(?m)^[ \t]*@(?:java\.lang\.)?Deprecated\b""").containsMatchIn(method.header)) {
            continue
        }
        val requiredDeprecatedOverride = when (method.name) {
            "getParticleIcon" -> method.header.contains("@Override") && method.header.contains("TextureAtlasSprite") && method.header.contains("()")
            "getTransforms" -> method.header.contains("@Override") && method.header.contains("ItemTransforms") && method.header.contains("()")
            "finalizeSpawn" -> method.header.contains("SpawnGroupData") &&
                method.header.contains("ServerLevelAccessor") &&
                method.header.contains("DifficultyInstance") &&
                method.header.contains("MobSpawnType")
            else -> false
        }
        if (!requiredDeprecatedOverride) continue
        val methodText = result.substring(method.range)
        val insertionMatch = Regex("""(?m)^([ \t]*)@Override\b""").find(methodText)
            ?: Regex("""(?m)^([ \t]*)(?:public|protected|private)\s+""").find(methodText)
            ?: continue
        val indent = insertionMatch.groupValues[1]
        insertions += method.range.first + insertionMatch.range.first to "${indent}@Deprecated\n"
    }
    if (insertions.isEmpty()) return result
    insertions.sortedByDescending { it.first }.forEach { (offset, text) ->
        result = result.substring(0, offset) + text + result.substring(offset)
    }
    return result
}

internal fun StructuralRefactorPass.markFinalizeSpawnRequiredDeprecatedOverride(source: String): String {
    if (!source.contains("finalizeSpawn(") ||
        !source.contains("ServerLevelAccessor") ||
        !source.contains("DifficultyInstance") ||
        !source.contains("MobSpawnType") ||
        !source.contains("SpawnGroupData")) {
        return source
    }
    val pattern = Regex(
        """(?m)^([ \t]*)((?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s*\r?\n\1)*)(public\s+SpawnGroupData\s+finalizeSpawn\s*\([^{};]*ServerLevelAccessor[^{};]*DifficultyInstance[^{};]*MobSpawnType[^{};]*SpawnGroupData[^{};]*\)\s*\{)"""
    )
    return pattern.replace(source) { match ->
        val annotations = match.groupValues[2]
        if (Regex("""(?m)^[ \t]*@(?:java\.lang\.)?Deprecated\b""").containsMatchIn(annotations)) {
            match.value
        } else {
            "${match.groupValues[1]}${annotations}${match.groupValues[1]}@Deprecated\n${match.groupValues[1]}${match.groupValues[3]}"
        }
    }
}

internal data class ClientColorAliasDeclaration(val alias: String, val range: IntRange)
internal data class ClientColorRegistrationStatement(val range: IntRange, val replacement: String)
internal data class ClientColorMigration(
    val source: String,
    val blockRegistrations: List<String>,
    val itemRegistrations: List<String>,
    val firstEditOffset: Int
)

internal fun StructuralRefactorPass.migrateDeprecatedClientColorRegistrations(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val mainFile = detectModMainClass(projectDir) ?: return emptyList()
    var mainText = mainFile.readText()
    val modBusVar = detectModBusVariable(mainText) ?: return emptyList()
    val mainPackage = packageNameOf(mainText)
    val changes = mutableListOf<Change>()

    fun methodFor(methodName: String, eventType: String, registrations: List<String>): String =
        """

    public static void $methodName($eventType event) {
${registrations.joinToString("\n") { "        $it" }}
    }
""".trimEnd()

    walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .filter { it != mainFile }
        .toList()
        .forEach { javaFile ->
            val original = javaFile.readText()
            val executableCode = maskJavaCommentsAndLiterals(original)
            if (!executableCode.contains(".register(") ||
                !executableCode.contains("getBlockColors()") &&
                !executableCode.contains("getItemColors()")) {
                return@forEach
            }
            if (executableCode.contains("RegisterColorHandlersEvent.Block") ||
                executableCode.contains("RegisterColorHandlersEvent.Item")) {
                return@forEach
            }

            val ownerName = classNameOfJavaSource(executableCode) ?: return@forEach
            val owner = javaTypeBlocks(original, executableCode).firstOrNull { it.name == ownerName } ?: return@forEach
            val sourcePackage = packageNameOf(original)
            val listenerClassName = javaListenerTypeReferenceExpression(sourcePackage, mainPackage, owner) ?: return@forEach
            val migration = migrateDeprecatedClientColorRegistrationSource(original) ?: return@forEach

            var migrated = migration.source
            if (migration.blockRegistrations.isNotEmpty()) {
                migrated = insertBeforeLastClassBrace(
                    migrated,
                    methodFor(
                        "registerBlockColors",
                        "RegisterColorHandlersEvent.Block",
                        migration.blockRegistrations
                    )
                )
            }
            if (migration.itemRegistrations.isNotEmpty()) {
                migrated = insertBeforeLastClassBrace(
                    migrated,
                    methodFor(
                        "registerItemColors",
                        "RegisterColorHandlersEvent.Item",
                        migration.itemRegistrations
                    )
                )
            }
            migrated = addExecutableImportIfMissing(migrated, "net.neoforged.neoforge.client.event.RegisterColorHandlersEvent")
            migrated = removeSimpleImportIfUnused(migrated, "net.minecraft.client.color.block.BlockColors", "BlockColors")
            migrated = removeSimpleImportIfUnused(migrated, "net.minecraft.client.color.item.ItemColors", "ItemColors")
            migrated = removeSimpleImportIfUnused(migrated, "net.minecraft.client.Minecraft", "Minecraft")

            var mainChanged = false
            listOf(
                "registerBlockColors" to migration.blockRegistrations,
                "registerItemColors" to migration.itemRegistrations
            ).forEach { (methodName, registrations) ->
                if (registrations.isEmpty()) return@forEach
                val listener = "$modBusVar.addListener($listenerClassName::$methodName);"
                if (!mainText.contains(listener)) {
                    val indent = mainConstructorIndent(mainText, modBusVar)
                    val registration = "${indent}if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {\n" +
                        "$indent    $listener\n" +
                        "$indent}"
                    mainText = insertModBusListener(mainText, modBusVar, registration, owner.name)
                    mainChanged = true
                }
            }

            changes.add(Change(
                file = javaFile,
                line = lineNumberAt(original, migration.firstEditOffset),
                description = "Migrate deprecated client color registrations to RegisterColorHandlersEvent",
                before = "Minecraft.getInstance().getBlockColors()/getItemColors().register(...)",
                after = "RegisterColorHandlersEvent.Block/Item event.register(...)",
                confidence = Confidence.HIGH,
                ruleId = "struct-client-color-handler-events"
            ))
            if (mainChanged) {
                changes.add(Change(
                    file = mainFile,
                    line = 1,
                    description = "Register migrated client color handlers on the mod event bus",
                    before = "direct color registration during another client setup event",
                    after = "mod event bus listeners for RegisterColorHandlersEvent",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-client-color-handler-events"
                ))
            }
            if (!dryRun) {
                javaFile.writeText(migrated)
            }
        }

    if (!dryRun && mainText != mainFile.readText()) {
        mainFile.writeText(mainText)
    }

    return changes
}

internal fun StructuralRefactorPass.migrateDeprecatedClientColorRegistrationSource(source: String): ClientColorMigration? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val id = """[A-Za-z_$][\w$]*"""
    fun aliases(typeName: String, methodName: String): List<ClientColorAliasDeclaration> =
        Regex(
            """(?m)^[ \t]*(?:final[ \t]+)?(?:${Regex.escape(typeName)}|${Regex.escape(typeName.substringAfterLast('.'))})[ \t]+($id)[ \t]*=[ \t]*(?:net\.minecraft\.client\.)?Minecraft\.getInstance[ \t]*\([ \t]*\)[ \t]*\.[ \t]*${Regex.escape(methodName)}[ \t]*\([ \t]*\)[ \t]*;[ \t]*(?:\r?\n)?"""
        ).findAll(executableCode)
            .map { ClientColorAliasDeclaration(it.groupValues[1], it.range) }
            .toList()

    fun statementEnd(openParen: Int): Int {
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) return -1
        var cursor = closeParen + 1
        while (cursor < executableCode.length && executableCode[cursor].isWhitespace()) cursor++
        return if (cursor < executableCode.length && executableCode[cursor] == ';') cursor else -1
    }

    fun statementRange(callStart: Int, semicolon: Int): IntRange {
        val start = source.lastIndexOf('\n', callStart).let { if (it < 0) 0 else it + 1 }
        var end = semicolon
        if (end + 1 < source.length && source[end + 1] == '\r') end++
        if (end + 1 < source.length && source[end + 1] == '\n') end++
        return start..end
    }

    fun collect(
        aliasDeclarations: List<ClientColorAliasDeclaration>,
        blockAliasNames: List<String> = emptyList()
    ): List<ClientColorRegistrationStatement> {
        val statements = mutableListOf<ClientColorRegistrationStatement>()
        aliasDeclarations.forEach { declaration ->
            var cursor = 0
            while (cursor < executableCode.length) {
                val token = "${declaration.alias}.register"
                val callStart = executableCode.indexOf(token, cursor)
                if (callStart < 0) break
                val openParen = executableCode.indexOf('(', callStart + token.length)
                if (openParen < 0) break
                val semicolon = statementEnd(openParen)
                if (semicolon < 0) {
                    cursor = openParen + 1
                    continue
                }
                val closeParen = findMatchingParen(executableCode, openParen)
                val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
                if (args.size >= 2) {
                    val rendered = blockAliasNames.fold(args.joinToString(", ") { it.trim() }) { text, alias ->
                        text.replace(Regex("""(?<![\w$.])${Regex.escape(alias)}(?![\w$])"""), "event.getBlockColors()")
                    }
                    statements += ClientColorRegistrationStatement(
                        statementRange(callStart, semicolon),
                        "event.register($rendered);"
                    )
                }
                cursor = semicolon + 1
            }
        }
        return statements
    }

    val blockAliases = aliases("net.minecraft.client.color.block.BlockColors", "getBlockColors")
    val itemAliases = aliases("net.minecraft.client.color.item.ItemColors", "getItemColors")
    val blockStatements = collect(blockAliases)
    val itemStatements = collect(itemAliases, blockAliases.map { it.alias })
    val allStatements = blockStatements + itemStatements
    if (allStatements.isEmpty()) return null

    fun rangeContains(range: IntRange, offset: Int): Boolean = offset >= range.first && offset <= range.last
    fun aliasOnlyUsedInRemovedStatements(declaration: ClientColorAliasDeclaration): Boolean =
        Regex("""\b${Regex.escape(declaration.alias)}\b""").findAll(executableCode).all { match ->
            rangeContains(declaration.range, match.range.first) ||
                allStatements.any { rangeContains(it.range, match.range.first) }
        }

    val edits = mutableListOf<Pair<IntRange, String>>()
    allStatements.forEach { edits += it.range to "" }
    (blockAliases + itemAliases)
        .filter(::aliasOnlyUsedInRemovedStatements)
        .forEach { edits += it.range to "" }

    val migrated = applyStringEdits(source, edits)
    return ClientColorMigration(
        source = migrated,
        blockRegistrations = blockStatements.map { it.replacement },
        itemRegistrations = itemStatements.map { it.replacement },
        firstEditOffset = edits.minOf { it.first.first }
    )
}

internal fun StructuralRefactorPass.removeSimpleImportIfUnused(source: String, importName: String, simpleName: String): String {
    val without = removeExecutableImport(source, importName)
    return if (!hasSimpleTypeReference(maskJavaCommentsAndLiterals(without), simpleName)) without else source
}

internal data class RenderLayerCall(
    val range: IntRange,
    val ownerName: String?,
    val fieldName: String,
    val renderType: String?,
    val allowedRenderTypes: Set<String>
)

internal data class RenderLayerModelMigration(val element: JsonObject, val changed: Boolean)

internal fun StructuralRefactorPass.migrateDeprecatedRenderLayerRegistrations(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val blockIds = collectRegisteredBlockIds(javaFiles)
    if (blockIds.isEmpty()) return emptyList()
    val resourceRoots = listOf(
        projectDir.resolve("src/main/resources"),
        projectDir.resolve("src/generated/resources")
    ).filter { it.exists() }
    if (resourceRoots.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        if (!original.contains("ItemBlockRenderTypes.setRenderLayer")) return@forEach
        val calls = collectDirectRenderLayerCalls(original)
        if (calls.isEmpty()) return@forEach

        val removable = mutableListOf<RenderLayerCall>()
        calls.forEach { call ->
            val blockId = resolveRegisteredBlockId(blockIds, call.ownerName, call.fieldName) ?: return@forEach
            val modelFiles = modelFilesForBlockResource(resourceRoots, blockId)
            if (modelFiles.isEmpty()) return@forEach
            if (!ensureModelRenderTypes(modelFiles, call.renderType, call.allowedRenderTypes, dryRun, changes)) return@forEach
            removable += call
        }

        if (removable.isEmpty()) return@forEach
        var migrated = applyStringEdits(original, removable.map { it.range to "" })
        migrated = removeSimpleImportIfUnused(migrated, "net.minecraft.client.renderer.ItemBlockRenderTypes", "ItemBlockRenderTypes")
        migrated = removeSimpleImportIfUnused(migrated, "net.minecraft.client.renderer.RenderType", "RenderType")
        changes.add(Change(
            file = javaFile,
            line = lineNumberAt(original, removable.minOf { it.range.first }),
            description = "Migrate deprecated block render-layer calls to model render_type resources",
            before = "ItemBlockRenderTypes.setRenderLayer(block, RenderType.*())",
            after = "block model JSON render_type",
            confidence = Confidence.HIGH,
            ruleId = "struct-render-layer-model-render-type"
        ))
        if (!dryRun) {
            javaFile.writeText(migrated)
        }
    }
    return changes
}

internal fun StructuralRefactorPass.collectDirectRenderLayerCalls(source: String): List<RenderLayerCall> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val calls = mutableListOf<RenderLayerCall>()
    var cursor = 0
    val token = "ItemBlockRenderTypes.setRenderLayer"
    while (cursor < executableCode.length) {
        val callStart = executableCode.indexOf(token, cursor)
        if (callStart < 0) break
        val openParen = executableCode.indexOf('(', callStart + token.length)
        if (openParen < 0) break
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = openParen + 1
            continue
        }
        var semicolon = closeParen + 1
        while (semicolon < executableCode.length && executableCode[semicolon].isWhitespace()) semicolon++
        if (semicolon >= executableCode.length || executableCode[semicolon] != ';') {
            cursor = closeParen + 1
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size == 2) {
            val target = Regex("""^(?:(\w+)\.)?(\w+)\s*\.\s*get\s*\(\s*\)$""")
                .matchEntire(args[0].trim())
            val renderLayer = renderLayerCallSemantics(args[1].trim())
            if (target != null && renderLayer != null) {
                val start = source.lastIndexOf('\n', callStart).let { if (it < 0) 0 else it + 1 }
                var end = semicolon
                if (end + 1 < source.length && source[end + 1] == '\r') end++
                if (end + 1 < source.length && source[end + 1] == '\n') end++
                calls += RenderLayerCall(
                    range = start..end,
                    ownerName = target.groupValues[1].takeIf { it.isNotBlank() },
                    fieldName = target.groupValues[2],
                    renderType = renderLayer.first,
                    allowedRenderTypes = renderLayer.second
                )
            }
        }
        cursor = semicolon + 1
    }
    return calls
}

internal fun StructuralRefactorPass.renderLayerCallSemantics(expression: String): Pair<String?, Set<String>>? {
    directModelRenderType(expression)?.let { return it to setOf(it) }
    if ("->" !in expression) return null
    val renderTypes = Regex("""(?:net\.minecraft\.client\.renderer\.)?RenderType\.(\w+)\s*\(""")
        .findAll(expression)
        .mapNotNull { directModelRenderType("RenderType.${it.groupValues[1]}()") }
        .toSet()
    val solidAndTranslucent = setOf("minecraft:solid", "minecraft:translucent")
    return if (renderTypes == solidAndTranslucent) null to renderTypes else null
}

internal fun StructuralRefactorPass.directModelRenderType(expression: String): String? {
    val match = Regex("""^(?:net\.minecraft\.client\.renderer\.)?RenderType\.(\w+)\s*\(\s*\)$""")
        .matchEntire(expression)
        ?: return null
    return when (match.groupValues[1]) {
        "solid" -> "minecraft:solid"
        "cutout" -> "minecraft:cutout"
        "cutoutMipped" -> "minecraft:cutout_mipped"
        "translucent" -> "minecraft:translucent"
        "tripwire" -> "minecraft:tripwire"
        else -> null
    }
}

internal fun StructuralRefactorPass.collectRegisteredBlockIds(javaFiles: List<Path>): Map<String, String> {
    val qualified = linkedMapOf<String, String>()
    val byField = linkedMapOf<String, MutableSet<String>>()
    val id = """[A-Za-z_$][\w$]*"""
    val fieldPattern = Regex(
        """(?s)\bpublic\s+static\s+final\s+[^=;]*(?:\bBlock\b|DeferredHolder\s*<\s*Block\b)[^=;]*\s+($id)\s*=\s*[^;]*?\.register\s*\(\s*"([^"]+)"""
    )
    javaFiles.forEach { file ->
        val source = file.readText()
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val className = classNameOfJavaSource(executableCode) ?: return@forEach
        fieldPattern.findAll(code).forEach { match ->
            val fieldName = match.groupValues[1]
            val blockId = match.groupValues[2]
            qualified["$className.$fieldName"] = blockId
            byField.getOrPut(fieldName) { linkedSetOf() } += blockId
        }
    }
    byField.forEach { (fieldName, ids) ->
        if (ids.size == 1) {
            qualified[fieldName] = ids.single()
        }
    }
    return qualified
}

internal fun StructuralRefactorPass.resolveRegisteredBlockId(blockIds: Map<String, String>, ownerName: String?, fieldName: String): String? =
    ownerName?.let { blockIds["$it.$fieldName"] } ?: blockIds[fieldName]

internal fun StructuralRefactorPass.modelFilesForBlockResource(resourceRoots: List<Path>, blockId: String): Set<Path> {
    val blockstateFiles = resourceRoots.flatMap { root ->
        val assets = root.resolve("assets")
        if (!assets.exists()) {
            emptyList()
        } else {
            Files.list(assets).use { namespaces ->
                namespaces
                    .filter { it.isDirectory() }
                    .map { it.resolve("blockstates/$blockId.json") }
                    .filter { it.exists() }
                    .toList()
            }
        }
    }
    if (blockstateFiles.isEmpty()) return emptySet()

    val modelIds = linkedSetOf<String>()
    blockstateFiles.forEach { blockstateFile ->
        val root = parseStructuralResourceJson(blockstateFile.readText()) ?: return@forEach
        val namespace = blockstateFile.parent?.parent?.fileName?.toString() ?: return@forEach
        collectModelIds(root, modelIds, namespace)
    }
    if (modelIds.isEmpty()) return emptySet()

    val modelFiles = linkedSetOf<Path>()
    modelIds.forEach { modelId ->
        val namespace = modelId.substringBefore(':')
        val path = modelId.substringAfter(':', modelId)
        resourceRoots
            .map { it.resolve("assets/$namespace/models/$path.json") }
            .firstOrNull { it.exists() }
            ?.let { modelFiles.add(it) }
            ?: return emptySet()
    }
    return modelFiles
}

internal fun StructuralRefactorPass.collectModelIds(element: JsonElement, output: MutableSet<String>, defaultNamespace: String) {
    when (element) {
        is JsonObject -> element.forEach { (key, value) ->
            if (key == "model" && value is JsonPrimitive && value.isString) {
                val modelId = value.content
                output += if (':' in modelId) modelId else "$defaultNamespace:$modelId"
            } else {
                collectModelIds(value, output, defaultNamespace)
            }
        }
        is JsonArray -> element.forEach { collectModelIds(it, output, defaultNamespace) }
        else -> {}
    }
}

internal fun StructuralRefactorPass.ensureModelRenderTypes(
    modelFiles: Set<Path>,
    renderType: String?,
    allowedRenderTypes: Set<String>,
    dryRun: Boolean,
    changes: MutableList<Change>
): Boolean {
    val migrated = linkedMapOf<Path, JsonObject>()
    modelFiles.forEach { modelFile ->
        val root = parseStructuralResourceJson(modelFile.readText()) as? JsonObject ?: return false
        val migration = ensureModelRenderType(root, renderType, allowedRenderTypes) ?: return false
        if (migration.changed) {
            migrated[modelFile] = migration.element
        }
    }
    migrated.forEach { (modelFile, element) ->
        changes.add(Change(
            file = modelFile,
            line = 0,
            description = "Write model render_type for migrated render-layer registration",
            before = "(missing model render_type)",
            after = renderType ?: allowedRenderTypes.sorted().joinToString("|"),
            confidence = Confidence.HIGH,
            ruleId = "struct-render-layer-model-render-type"
        ))
        if (!dryRun) {
            modelFile.writeText(STRUCTURAL_RESOURCE_JSON.encodeToString(JsonElement.serializer(), element) + "\n")
        }
    }
    return true
}

internal fun StructuralRefactorPass.ensureModelRenderType(
    root: JsonObject,
    renderType: String?,
    allowedRenderTypes: Set<String>
): RenderLayerModelMigration? {
    val fallbackRenderType = renderType ?: "minecraft:solid".takeIf { it in allowedRenderTypes }
    val existing = root["render_type"] as? JsonPrimitive
    if (existing != null) {
        return if (existing.isString && existing.content in allowedRenderTypes) {
            RenderLayerModelMigration(root, changed = false)
        } else {
            null
        }
    }

    val loader = (root["loader"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (loader == "neoforge:composite") {
        val children = root["children"] as? JsonObject ?: return RenderLayerModelMigration(root, changed = false)
        var changed = false
        val migratedChildren = linkedMapOf<String, JsonElement>()
        children.forEach { (name, child) ->
            val childObject = child as? JsonObject ?: return null
            val childExisting = childObject["render_type"] as? JsonPrimitive
            if (childExisting != null) {
                if (!childExisting.isString || childExisting.content !in allowedRenderTypes) return null
                migratedChildren[name] = childObject
            } else {
                val childRenderType = fallbackRenderType ?: return null
                val entries = linkedMapOf<String, JsonElement>()
                childObject.forEach { (key, value) -> entries[key] = value }
                entries["render_type"] = JsonPrimitive(childRenderType)
                migratedChildren[name] = JsonObject(entries)
                changed = true
            }
        }
        if (!changed) return RenderLayerModelMigration(root, changed = false)
        val entries = linkedMapOf<String, JsonElement>()
        root.forEach { (key, value) ->
            entries[key] = if (key == "children") JsonObject(migratedChildren) else value
        }
        return RenderLayerModelMigration(JsonObject(entries), changed = true)
    }

    val modelRenderType = fallbackRenderType ?: return null

    val entries = linkedMapOf<String, JsonElement>()
    var inserted = false
    root.forEach { (key, value) ->
        if (!inserted && key == "parent") {
            entries[key] = value
            entries["render_type"] = JsonPrimitive(modelRenderType)
            inserted = true
        } else {
            entries[key] = value
        }
    }
    if (!inserted) {
        entries["render_type"] = JsonPrimitive(modelRenderType)
    }
    return RenderLayerModelMigration(JsonObject(entries), changed = true)
}

internal fun StructuralRefactorPass.migrateLegacyItemHandlerHelperStackComparisons(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ItemHandlerHelper.canItemStacksStack(")) return source
    var result = rewriteExecutableJavaCall(source, "canItemStacksStack") { receiver, args ->
        if (receiver != "ItemHandlerHelper" || args.size != 2) return@rewriteExecutableJavaCall null
        "net.minecraft.world.item.ItemStack.isSameItemSameComponents(${args[0].trim()}, ${args[1].trim()})"
    }
    val withoutImport = removeImport(result, "net.neoforged.neoforge.items.ItemHandlerHelper")
    result = if (!Regex("""\bItemHandlerHelper\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutImport))) {
        withoutImport
    } else {
        result
    }
    return result
}

internal data class HolderLookupProviderThreadTarget(
    val owner: String,
    val name: String,
    val isConstructor: Boolean,
    val arity: Int
)

internal data class ProjectHolderLookupProviderTarget(
    val ownerSimple: String,
    val ownerQualified: String,
    val name: String,
    val isConstructor: Boolean,
    val arity: Int
)

internal data class ProjectHolderLookupForwarderTarget(
    val ownerSimple: String,
    val ownerQualified: String,
    val methodName: String,
    val arity: Int,
    val providerParameterIndex: Int,
    val dataParameterIndex: Int,
    val dataParameterName: String,
    val dataType: String,
    val forwardedMethods: Set<String>
)

internal fun StructuralRefactorPass.migrateProjectNestedHolderLookupProviderCallSites(
    javaFiles: List<Path>,
    javaInheritanceIndex: JavaInheritanceIndex
): List<Change> {
    val targets = collectProjectNestedHolderLookupProviderTargets(javaFiles)
    if (targets.isEmpty()) return emptyList()
    val changes = mutableListOf<Change>()
    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        var migrated = original
        targets.forEach { target ->
            migrated = if (target.isConstructor) {
                addHolderLookupProviderToConstructorCalls(migrated, target, javaInheritanceIndex)
            } else {
                addHolderLookupProviderToMethodCalls(migrated, target, javaInheritanceIndex)
            }
        }
        if (migrated != original) {
            javaFile.writeText(migrated)
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Thread HolderLookup.Provider through provider-aware nested helper call sites",
                before = "nested helper call without provider",
                after = "nested helper call with proven HolderLookup.Provider",
                confidence = Confidence.HIGH,
                ruleId = "struct-nested-holderlookup-call-sites"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.migrateProjectHolderLookupForwarderMethods(
    javaFiles: List<Path>,
    holderLookupMethods: Set<HolderLookupNbtMethod>,
    javaInheritanceIndex: JavaInheritanceIndex
): List<Change> {
    val effectiveHolderLookupMethods = holderLookupMethods + collectExistingHolderLookupNbtMethods(javaFiles)
    if (effectiveHolderLookupMethods.isEmpty()) return emptyList()
    val targets = collectProjectHolderLookupForwarderTargets(javaFiles, effectiveHolderLookupMethods)
    if (targets.isEmpty()) return emptyList()
    val changes = mutableListOf<Change>()
    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        var migrated = original
        targets.forEach { target ->
            migrated = migrateHolderLookupForwarderDefinition(migrated, target, effectiveHolderLookupMethods)
        }
        if (migrated != original) {
            javaFile.writeText(migrated)
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Thread HolderLookup.Provider through provider-aware NBT forwarding helper definitions",
                before = "helper calls provider-aware NBT method without provider",
                after = "helper accepts HolderLookup.Provider and forwards it",
                confidence = Confidence.HIGH,
                ruleId = "struct-holderlookup-forwarder-definition"
            ))
        }
    }

    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        var migrated = original
        targets.forEach { target ->
            migrated = addHolderLookupProviderToForwarderCalls(migrated, target, javaInheritanceIndex)
        }
        if (migrated != original) {
            javaFile.writeText(migrated)
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Thread HolderLookup.Provider through provider-aware NBT forwarding helper call sites",
                before = "forwarding helper call without provider",
                after = "forwarding helper call with provider proven from data source",
                confidence = Confidence.HIGH,
                ruleId = "struct-holderlookup-forwarder-call-sites"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.collectProjectHolderLookupForwarderTargets(
    javaFiles: List<Path>,
    holderLookupMethods: Set<HolderLookupNbtMethod>
): Set<ProjectHolderLookupForwarderTarget> {
    val methodsByOwner = holderLookupMethods.groupBy { it.owner }
    if (methodsByOwner.isEmpty()) return emptySet()
    val ownerNames = methodsByOwner.keys
    val targets = linkedSetOf<ProjectHolderLookupForwarderTarget>()
    javaFiles.forEach { javaFile ->
        val source = javaFile.readText()
        if (!source.contains("CompoundTag")) return@forEach
        val packageName = packageNameOf(source)
        val topLevel = classNameOfJavaSource(source) ?: return@forEach
        val executableCode = maskJavaCommentsAndLiterals(source)
        val typeBlocks = javaTypeBlocks(source, executableCode)
        val topLevelBlock = typeBlocks.firstOrNull { it.name == topLevel } ?: return@forEach
        javaMethodRangesIncludingDefault(source).forEach { method ->
            if (method.range.first !in topLevelBlock.bodyStart..topLevelBlock.end) return@forEach
            val params = callableParameters(source, method.range)
            if (params.any { isHolderLookupProviderParameter(it) }) return@forEach
            val parsedParams = params.mapNotNull(::parseJavaParameter)
            val dataParam = parsedParams.withIndex().firstOrNull { (_, param) ->
                param.first.substringAfterLast('.') in ownerNames
            } ?: return@forEach
            val dataType = dataParam.value.first.substringAfterLast('.')
            val dataName = dataParam.value.second
            val forwardedMethods = linkedSetOf<String>()
            val methodExecutable = executableCode.substring(method.range)
            methodsByOwner[dataType].orEmpty().forEach { targetMethod ->
                val token = ".${targetMethod.methodName}("
                var cursor = 0
                while (true) {
                    val tokenIndex = methodExecutable.indexOf(token, cursor)
                    if (tokenIndex < 0) break
                    val absoluteToken = method.range.first + tokenIndex
                    val receiverStart = findExpressionReceiverStart(executableCode, absoluteToken)
                    if (receiverStart >= 0 && receiverStart < absoluteToken) {
                        val receiver = source.substring(receiverStart, absoluteToken).trim()
                        if (receiver == dataName) {
                            val openParen = absoluteToken + targetMethod.methodName.length + 1
                            val closeParen = findMatchingParen(executableCode, openParen)
                            if (closeParen >= 0) {
                                val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
                                if ((targetMethod.parameterCount == null || targetMethod.parameterCount == args.size) &&
                                    args.none { isHolderLookupProviderExpression(it, holderLookupProviderNamesAt(source, absoluteToken)) }
                                ) {
                                    forwardedMethods += targetMethod.methodName
                                }
                            }
                        }
                    }
                    cursor = tokenIndex + token.length
                }
            }
            if (forwardedMethods.isNotEmpty()) {
                val providerPositions = holderLookupMethods.filter { contract ->
                    contract.owner == topLevel &&
                        contract.methodName == method.name &&
                        (contract.parameterCount == null || contract.parameterCount == params.size)
                }.map { contract ->
                    contract.providerParameterIndex ?: params.size
                }.distinct()
                if (providerPositions.size > 1) {
                    throw IllegalStateException(
                        "Conflicting HolderLookup.Provider parameter positions for " +
                            "$topLevel.${method.name}/${params.size}: $providerPositions"
                    )
                }
                targets += ProjectHolderLookupForwarderTarget(
                    ownerSimple = topLevel,
                    ownerQualified = if (packageName.isBlank()) topLevel else "$packageName.$topLevel",
                    methodName = method.name,
                    arity = params.size,
                    providerParameterIndex = providerPositions.singleOrNull() ?: params.size,
                    dataParameterIndex = dataParam.index,
                    dataParameterName = dataName,
                    dataType = dataType,
                    forwardedMethods = forwardedMethods
                )
            }
        }
    }
    return targets
}

internal fun StructuralRefactorPass.collectExistingHolderLookupNbtMethods(javaFiles: List<Path>): Set<HolderLookupNbtMethod> {
    val result = linkedSetOf<HolderLookupNbtMethod>()
    javaFiles.forEach { javaFile ->
        val source = javaFile.readText()
        if (!source.contains("HolderLookup.Provider") && !source.contains("net.minecraft.core.HolderLookup.Provider")) {
            return@forEach
        }
        if (!source.contains("CompoundTag")) return@forEach
        val executableCode = maskJavaCommentsAndLiterals(source)
        val typeBlocks = javaTypeBlocks(source, executableCode)
        if (typeBlocks.isEmpty()) return@forEach
        javaMethodRangesIncludingDefault(source).forEach { method ->
            if (!method.header.contains("CompoundTag") ||
                (!method.header.contains("HolderLookup.Provider") && !method.header.contains("net.minecraft.core.HolderLookup.Provider"))
            ) {
                return@forEach
            }
            val params = callableParameters(source, method.range)
            val providerIndices = params.withIndex()
                .filter { (_, parameter) -> isHolderLookupProviderParameter(parameter) }
                .map { it.index }
            if (providerIndices.isEmpty()) return@forEach
            if (providerIndices.size != 1) {
                throw IllegalStateException(
                    "${method.name} declares ${providerIndices.size} HolderLookup.Provider parameters"
                )
            }
            val parsedParams = params.map(::parseJavaParameter)
            if (parsedParams.any { it == null }) return@forEach
            val legacyParameterTypes = parsedParams.mapIndexedNotNull { index, parameter ->
                parameter?.first?.takeIf { index !in providerIndices }
            }
            val owner = typeBlocks.lastOrNull { method.range.first in it.bodyStart..it.end } ?: return@forEach
            result += HolderLookupNbtMethod(
                owner.name,
                method.name,
                params.size - 1,
                providerIndices.single(),
                legacyParameterTypes,
                requiresTypedCallResolution = true
            )
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateHolderLookupForwarderDefinition(
    source: String,
    target: ProjectHolderLookupForwarderTarget,
    holderLookupMethods: Set<HolderLookupNbtMethod>
): String {
    if (!source.contains(target.methodName) || !source.contains(target.dataParameterName)) return source
    val ownerName = classNameOfJavaSource(source) ?: return source
    if (ownerName != target.ownerSimple) return source
    var result = source
    val methodsByName = holderLookupMethods
        .filter { it.owner == target.dataType && it.methodName in target.forwardedMethods }
        .groupBy { it.methodName }
    val providerName = uniqueJavaLocalName(result, "modporterRegistries")

    result = rewriteExecutableJavaCallsInForwarder(result, target) { methodName, receiver, args ->
        val methodTargets = methodsByName[methodName].orEmpty()
        val matchingTargets = methodTargets.filter {
            it.parameterCount == null || it.parameterCount == args.size
        }
        val methodTarget = matchingTargets.singleOrNull()
        if (receiver != target.dataParameterName ||
            methodTarget == null ||
            args.any { isHolderLookupProviderExpression(it, setOf(providerName)) }
        ) {
            null
        } else {
            val migratedArgs = insertHolderLookupProviderArgument(
                args,
                providerName,
                methodTarget
            )
            "$receiver.$methodName(${migratedArgs.joinToString(", ")})"
        }
    }

    javaMethodRangesIncludingDefault(result).forEach { method ->
        if (method.name != target.methodName) return@forEach
        val params = callableParameters(result, method.range)
        if (params.size != target.arity || params.any { isHolderLookupProviderParameter(it) }) return@forEach
        val parsedParams = params.mapNotNull(::parseJavaParameter)
        if (parsedParams.getOrNull(target.dataParameterIndex)?.second != target.dataParameterName) return@forEach
        val span = callableParameterSpan(result, method.range) ?: return@forEach
        val migratedParams = params.map { it.trim() }.toMutableList().also {
            it.add(
                target.providerParameterIndex,
                "net.minecraft.core.HolderLookup.Provider $providerName"
            )
        }
        result = result.substring(0, span.first + 1) +
            migratedParams.joinToString(", ") +
            result.substring(span.second)
        return result
    }
    return result
}

internal fun StructuralRefactorPass.rewriteExecutableJavaCallsInForwarder(
    source: String,
    target: ProjectHolderLookupForwarderTarget,
    replacement: (methodName: String, receiver: String, args: List<String>) -> String?
): String {
    var result = source
    target.forwardedMethods.forEach { methodName ->
        result = rewriteExecutableJavaCallWithOffset(result, methodName) { receiver, args, offset ->
            val method = javaMethodRangesIncludingDefault(result)
                .firstOrNull { it.name == target.methodName && offset in it.range }
                ?: return@rewriteExecutableJavaCallWithOffset null
            val params = callableParameters(result, method.range)
            if (params.size != target.arity) return@rewriteExecutableJavaCallWithOffset null
            replacement(methodName, receiver, args)
        }
    }
    return result
}

internal fun StructuralRefactorPass.addHolderLookupProviderToForwarderCalls(
    source: String,
    target: ProjectHolderLookupForwarderTarget,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains("${target.methodName}(")) return source
    var result = source
    do {
        val before = result
        result = rewriteExecutableJavaCallWithOffset(result, target.methodName) { receiver, args, offset ->
            if (!matchesForwarderOwner(receiver, target) || args.size != target.arity) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val provider = holderLookupProviderForForwardedArgument(
                result,
                offset,
                args[target.dataParameterIndex],
                target.dataType,
                javaInheritanceIndex
            ) ?: return@rewriteExecutableJavaCallWithOffset null
            val migratedArgs = args.map { it.trim() }.toMutableList().also {
                it.add(
                    target.providerParameterIndex,
                    holderLookupProviderArgumentExpression(provider)
                )
            }
            "$receiver.${target.methodName}(${migratedArgs.joinToString(", ")})"
        }
    } while (result != before)
    return result
}

internal fun StructuralRefactorPass.matchesForwarderOwner(receiver: String, target: ProjectHolderLookupForwarderTarget): Boolean {
    val normalized = receiver.trim()
    return normalized == target.ownerSimple ||
        normalized == target.ownerQualified ||
        normalized.endsWith(".${target.ownerSimple}") ||
        normalized.endsWith(".${target.ownerQualified}")
}

internal fun StructuralRefactorPass.holderLookupProviderForForwardedArgument(
    source: String,
    offset: Int,
    argument: String,
    dataType: String,
    javaInheritanceIndex: JavaInheritanceIndex
): String? {
    providerFromSavedDataExpression(argument)?.let { return it }
    val variable = argument.trim().takeIf { Regex("""^[A-Za-z_$][\w$]*$""").matches(it) } ?: return registryAccessExpressionAt(source, offset, javaInheritanceIndex)
    val method = javaMethodRangesIncludingDefault(source).firstOrNull { offset in it.range }
        ?: return registryAccessExpressionAt(source, offset, javaInheritanceIndex)
    val beforeCall = source.substring(method.range.first, offset)
    val typePattern = """(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(dataType)}"""
    val declaration = Regex("""\b$typePattern\s+${Regex.escape(variable)}\s*=\s*([^;\r\n]+);""")
        .findAll(maskJavaCommentsAndLiterals(beforeCall))
        .lastOrNull()
    if (declaration != null) {
        val initializer = beforeCall.substring(declaration.groups[1]!!.range).trim()
        providerFromSavedDataExpression(initializer)?.let { return it }
    }
    return registryAccessExpressionAt(source, offset, javaInheritanceIndex)
}

internal fun StructuralRefactorPass.providerFromSavedDataExpression(expression: String): String? {
    val normalized = expression.trim()
    Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\(\))*)\.getDataStorage\(\)""")
        .find(normalized)
        ?.let { return "${it.groupValues[1]}.registryAccess()" }
    Regex("""\bgetInstance\(\s*([^()]+(?:\([^()]*\))?)\s*\)""")
        .find(normalized)
        ?.let { match ->
            val level = match.groupValues[1].trim()
            return "$level.registryAccess()"
        }
    return null
}

internal fun StructuralRefactorPass.collectProjectNestedHolderLookupProviderTargets(javaFiles: List<Path>): Set<ProjectHolderLookupProviderTarget> {
    val targets = linkedSetOf<ProjectHolderLookupProviderTarget>()
    javaFiles.forEach { javaFile ->
        val source = javaFile.readText()
        if (!source.contains("HolderLookup.Provider")) return@forEach
        val executableCode = maskJavaCommentsAndLiterals(source)
        val typeBlocks = javaTypeBlocks(source, executableCode)
        val nestedBlocks = typeBlocks.mapNotNull { inner ->
            val parent = typeBlocks
                .filter { outer -> outer != inner && inner.declarationStart > outer.bodyStart && inner.end < outer.end }
                .minByOrNull { outer -> outer.end - outer.bodyStart }
            parent?.let { inner to it }
        }
        nestedBlocks.forEach { (inner, parent) ->
            val qualifiedOwner = "${parent.name}.${inner.name}"
            javaConstructorRangesIncludingPackagePrivate(source, inner.name)
                .filter { it.range.first > inner.bodyStart && it.range.last < inner.end }
                .forEach { constructor ->
                    val params = callableParameters(source, constructor.range)
                    if (params.lastOrNull()?.let(::isHolderLookupProviderParameter) == true) {
                        targets += ProjectHolderLookupProviderTarget(
                            ownerSimple = inner.name,
                            ownerQualified = qualifiedOwner,
                            name = inner.name,
                            isConstructor = true,
                            arity = params.size - 1
                        )
                    }
                }
            javaMethodRangesIncludingDefault(source)
                .filter { it.range.first > inner.bodyStart && it.range.last < inner.end }
                .forEach { method ->
                    val params = callableParameters(source, method.range)
                    if (params.lastOrNull()?.let(::isHolderLookupProviderParameter) == true) {
                        targets += ProjectHolderLookupProviderTarget(
                            ownerSimple = inner.name,
                            ownerQualified = qualifiedOwner,
                            name = method.name,
                            isConstructor = false,
                            arity = params.size - 1
                        )
                    }
                }
        }
    }
    return targets
}

internal fun StructuralRefactorPass.migrateNestedHolderLookupProviderAccess(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    val legacyAccess = "this.getLevel().registryAccess()"
    if (!source.contains(legacyAccess)) return source

    val providerType = "net.minecraft.core.HolderLookup.Provider"
    val executableCode = maskJavaCommentsAndLiterals(source)
    val typeBlocks = javaTypeBlocks(source, executableCode)
    val nestedBlocks = typeBlocks.filter { inner ->
        typeBlocks.any { outer ->
            outer != inner && inner.declarationStart > outer.bodyStart && inner.end < outer.end
        }
    }
    if (nestedBlocks.isEmpty()) return source

    val signatureAndBodyEdits = mutableListOf<Pair<IntRange, String>>()
    val targets = linkedSetOf<HolderLookupProviderThreadTarget>()

    fun addCallableTarget(owner: String, callable: JavaMethodRange, isConstructor: Boolean) {
        val callableText = source.substring(callable.range)
        if (!callableText.contains(legacyAccess)) return
        val span = callableParameterSpan(source, callable.range) ?: return
        val params = splitTopLevelJavaArgs(source.substring(span.first + 1, span.second))
        if (params.any { isHolderLookupProviderParameter(it) }) return

        val providerName = uniqueJavaLocalName(callableText, "modporterRegistries")
        val insertion = if (params.isEmpty()) {
            "$providerType $providerName"
        } else {
            ", $providerType $providerName"
        }
        signatureAndBodyEdits += span.second until span.second to insertion

        Regex(Regex.escape(legacyAccess))
            .findAll(executableCode, callable.range.first)
            .takeWhile { it.range.last <= callable.range.last }
            .forEach { signatureAndBodyEdits += it.range to providerName }

        targets += HolderLookupProviderThreadTarget(
            owner = owner,
            name = if (isConstructor) owner else callable.name,
            isConstructor = isConstructor,
            arity = params.size
        )
    }

    for (block in nestedBlocks) {
        javaConstructorRangesIncludingPackagePrivate(source, block.name)
            .filter { it.range.first > block.bodyStart && it.range.last < block.end }
            .forEach { addCallableTarget(block.name, it, isConstructor = true) }

        javaMethodRangesIncludingDefault(source)
            .filter { it.range.first > block.bodyStart && it.range.last < block.end }
            .forEach { addCallableTarget(block.name, it, isConstructor = false) }
    }

    if (targets.isEmpty()) return source
    var result = applyStringEdits(source, signatureAndBodyEdits)
    for (target in targets) {
        result = if (target.isConstructor) {
            addHolderLookupProviderToConstructorCalls(result, target, javaInheritanceIndex)
        } else {
            addHolderLookupProviderToMethodCalls(result, target, javaInheritanceIndex)
        }
    }
    return result
}

internal fun StructuralRefactorPass.addHolderLookupProviderToConstructorCalls(
    source: String,
    target: ProjectHolderLookupProviderTarget,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    var result = source
    var cursor = 0
    val constructorPattern = Regex("""\bnew\s+((?:[A-Za-z_$][\w$]*\.)*${Regex.escape(target.ownerSimple)})\s*(?:<[^>()]+>\s*)?\(""")
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val match = constructorPattern.find(executableCode, cursor) ?: break
        val ownerExpression = match.groupValues[1]
        if (!matchesProviderTargetOwner(ownerExpression, target)) {
            cursor = match.range.last + 1
            continue
        }
        val openParen = executableCode.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != target.arity) {
            cursor = closeParen + 1
            continue
        }
        val provider = registryAccessExpressionAt(result, match.range.first, javaInheritanceIndex)
        if (provider == null) {
            cursor = closeParen + 1
            continue
        }
        val providerArgument = holderLookupProviderArgumentExpression(provider)
        val insertion = if (args.isEmpty()) providerArgument else ", $providerArgument"
        result = result.substring(0, closeParen) + insertion + result.substring(closeParen)
        cursor = closeParen + insertion.length + 1
    }
    return result
}

internal fun StructuralRefactorPass.addHolderLookupProviderToMethodCalls(
    source: String,
    target: ProjectHolderLookupProviderTarget,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    var result = source
    val receivers = collectJavaVariablesOfType(source, target.ownerSimple, target.ownerQualified)
    if (receivers.isEmpty()) return result
    var cursor = 0
    val token = ".${target.name}("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + target.name.length + 1
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
        val receiverName = receiver.substringAfterLast('.')
        if (receiverName !in receivers) {
            cursor = closeParen + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != target.arity) {
            cursor = closeParen + 1
            continue
        }
        val provider = registryAccessExpressionAt(result, tokenIndex, javaInheritanceIndex)
        if (provider == null) {
            cursor = closeParen + 1
            continue
        }
        val providerArgument = holderLookupProviderArgumentExpression(provider)
        val insertion = if (args.isEmpty()) providerArgument else ", $providerArgument"
        result = result.substring(0, closeParen) + insertion + result.substring(closeParen)
        cursor = closeParen + insertion.length + 1
    }
    return result
}

internal fun StructuralRefactorPass.javaConstructorRangesIncludingPackagePrivate(source: String, className: String): List<JavaMethodRange> {
    val pattern = Regex(
        """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s*\r?\n[ \t]*)*(?:(?:public|protected|private)\s+)?${Regex.escape(className)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    return pattern.findAll(source).mapNotNull { match ->
        val openBrace = source.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return@mapNotNull null
        JavaMethodRange(className, match.value, match.range.first..closeBrace)
    }.toList()
}

internal fun StructuralRefactorPass.callableParameters(source: String, range: IntRange): List<String> {
    val span = callableParameterSpan(source, range) ?: return emptyList()
    return splitTopLevelJavaArgs(source.substring(span.first + 1, span.second))
}

internal fun StructuralRefactorPass.callableParameterSpan(source: String, range: IntRange): Pair<Int, Int>? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    var cursor = range.first
    val annotationEnd = Regex("""@[\w$.]+\s*$""")
    while (cursor <= range.last) {
        val openParen = executableCode.indexOf('(', cursor).takeIf { it >= 0 && it <= range.last } ?: return null
        val closeParen = findMatchingParen(executableCode, openParen).takeIf { it >= 0 && it <= range.last } ?: return null
        // An annotation on the callable or its return type can have its own argument list.
        if (annotationEnd.containsMatchIn(executableCode.substring(cursor, openParen))) {
            cursor = closeParen + 1
        } else {
            return openParen to closeParen
        }
    }
    return null
}

internal fun StructuralRefactorPass.isHolderLookupProviderParameter(parameter: String): Boolean {
    val cleaned = parameter.trim()
        .replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "")
        .removePrefix("final ")
        .trim()
    return Regex("""(?:^|\s)(?:net\.minecraft\.core\.)?HolderLookup\.Provider\s+[A-Za-z_$][\w$]*$""")
        .containsMatchIn(cleaned)
}

internal fun StructuralRefactorPass.addHolderLookupProviderToConstructorCalls(
    source: String,
    target: HolderLookupProviderThreadTarget,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    var result = source
    var cursor = 0
    val constructorPattern = Regex("""\bnew\s+${Regex.escape(target.owner)}\s*(?:<[^>()]+>\s*)?\(""")
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val match = constructorPattern.find(executableCode, cursor) ?: break
        val openParen = executableCode.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != target.arity) {
            cursor = closeParen + 1
            continue
        }
        val provider = registryAccessExpressionAt(result, match.range.first, javaInheritanceIndex)
        if (provider == null) {
            cursor = closeParen + 1
            continue
        }
        val providerArgument = holderLookupProviderArgumentExpression(provider)
        val insertion = if (args.isEmpty()) providerArgument else ", $providerArgument"
        result = result.substring(0, closeParen) + insertion + result.substring(closeParen)
        cursor = closeParen + insertion.length + 1
    }
    return result
}

internal fun StructuralRefactorPass.addHolderLookupProviderToMethodCalls(
    source: String,
    target: HolderLookupProviderThreadTarget,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    var result = source
    val receivers = collectJavaVariablesOfType(source, target.owner)
    if (receivers.isEmpty()) return result
    var cursor = 0
    val token = ".${target.name}("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + target.name.length + 1
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
        val receiverName = receiver.substringAfterLast('.')
        if (receiverName !in receivers) {
            cursor = closeParen + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != target.arity) {
            cursor = closeParen + 1
            continue
        }
        val provider = registryAccessExpressionAt(result, tokenIndex, javaInheritanceIndex)
        if (provider == null) {
            cursor = closeParen + 1
            continue
        }
        val providerArgument = holderLookupProviderArgumentExpression(provider)
        val insertion = if (args.isEmpty()) providerArgument else ", $providerArgument"
        result = result.substring(0, closeParen) + insertion + result.substring(closeParen)
        cursor = closeParen + insertion.length + 1
    }
    return result
}

internal fun StructuralRefactorPass.collectJavaVariablesOfType(source: String, typeName: String): Set<String> {
    return collectJavaVariablesOfType(source, typeName, typeName)
}

internal fun StructuralRefactorPass.collectJavaVariablesOfType(source: String, simpleTypeName: String, qualifiedTypeName: String): Set<String> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val id = """[A-Za-z_$][\w$]*"""
    val variables = linkedSetOf<String>()
    val typeExpression = """(?:${Regex.escape(qualifiedTypeName)}|${Regex.escape(simpleTypeName)})"""
    Regex("""\b$typeExpression(?:\s*<[^;{}()]+>)?\s+($id)\b""")
        .findAll(executableCode)
        .forEach { variables += it.groupValues[1] }
    Regex("""\b$typeExpression(?:\s*<[^;{}()]+>)?\s+($id)\s*:""")
        .findAll(executableCode)
        .forEach { variables += it.groupValues[1] }
    return variables
}

internal fun StructuralRefactorPass.matchesProviderTargetOwner(ownerExpression: String, target: ProjectHolderLookupProviderTarget): Boolean {
    val normalized = ownerExpression.trim()
    return normalized == target.ownerSimple ||
        normalized == target.ownerQualified ||
        normalized.endsWith(".${target.ownerQualified}")
}

internal fun StructuralRefactorPass.isRegistryAccessEmptyExpression(expression: String): Boolean =
    expression.trim() == "net.minecraft.core.RegistryAccess.EMPTY" || expression.trim() == "RegistryAccess.EMPTY"

internal fun StructuralRefactorPass.isHolderLookupProviderExpression(
    expression: String,
    providerNames: Set<String> = emptySet()
): Boolean {
    val normalized = expression.trim()
    return normalized in providerNames ||
        normalized == "registries" ||
        normalized.endsWith(".registryAccess()") ||
        normalized == "this.registryAccess()" ||
        normalized == "this.getLevel().registryAccess()" ||
        normalized.contains("HolderLookup.Provider")
}

internal fun StructuralRefactorPass.migrateLegacyGameEventListenerSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("GameEventListener") || !executableCode.contains("handleGameEvent(")) return source
    var result = source
    var changed = false
    result = replaceExecutableRegex(result, Regex(
        """boolean\s+handleGameEvent\s*\(\s*ServerLevel\s+([A-Za-z_$][\w$]*)\s*,\s*GameEvent\s+([A-Za-z_$][\w$]*)\s*,\s*GameEvent\.Context\s+([A-Za-z_$][\w$]*)\s*,\s*Vec3\s+([A-Za-z_$][\w$]*)\s*\)"""
    )) { match ->
        changed = true
        "boolean handleGameEvent(ServerLevel ${match.groupValues[1]}, Holder<GameEvent> ${match.groupValues[2]}, GameEvent.Context ${match.groupValues[3]}, Vec3 ${match.groupValues[4]})"
    }

    val holderEventVariables = Regex("""\bHolder\s*<\s*GameEvent\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
    if (holderEventVariables.isEmpty()) return if (changed) addImportIfMissing(result, "net.minecraft.core.Holder") else result

    val eventConstant = """((?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$][A-Za-z0-9_$]*(?:\.get\(\))?)"""
    for (eventVariable in holderEventVariables) {
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(eventVariable)}\s*==\s*$eventConstant""")) { match ->
            changed = true
            "${eventVariable}.is(${normaliseGameEventHolderComparison(match.groupValues[1])})"
        }
        result = replaceExecutableRegex(result, Regex("""$eventConstant\s*==\s*\b${Regex.escape(eventVariable)}\b""")) { match ->
            changed = true
            "${eventVariable}.is(${normaliseGameEventHolderComparison(match.groupValues[1])})"
        }
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(eventVariable)}\s*!=\s*$eventConstant""")) { match ->
            changed = true
            "!${eventVariable}.is(${normaliseGameEventHolderComparison(match.groupValues[1])})"
        }
        result = replaceExecutableRegex(result, Regex("""$eventConstant\s*!=\s*\b${Regex.escape(eventVariable)}\b""")) { match ->
            changed = true
            "!${eventVariable}.is(${normaliseGameEventHolderComparison(match.groupValues[1])})"
        }
    }

    return if (changed) addImportIfMissing(result, "net.minecraft.core.Holder") else result
}

internal fun StructuralRefactorPass.normaliseGameEventHolderComparison(expression: String): String =
    expression.trim().removeSuffix(".get()")

internal fun StructuralRefactorPass.migrateRequiredMapCodecConstants(
    source: String,
    mapCodecRequiredOwners: Set<String> = emptySet()
): String {
    if (!source.contains("Codec<") || !source.contains("CODEC")) return source
    val mapCodecTypes = mutableSetOf<String>()
    val topLevelClassName = classNameOfJavaSource(source)
    val mapCodecBasePattern = """(?:[A-Za-z_$][\w$]*\.)*(?:BiomeSource|PlacementModifier|Structure|StructureProcessor|StructurePlacement)"""
    if (topLevelClassName != null &&
        (Regex("""\bextends\s+$mapCodecBasePattern\b""").containsMatchIn(source) ||
            topLevelClassName in mapCodecRequiredOwners)
    ) {
        mapCodecTypes += topLevelClassName
    }
    Regex("""\b(?:class|record)\s+([A-Za-z_$][\w$]*)\b[^{;]*\bextends\s+$mapCodecBasePattern\b""")
        .findAll(source)
        .forEach { mapCodecTypes += it.groupValues[1] }
    if (mapCodecRequiredOwners.isNotEmpty()) {
        Regex("""\b(?:class|record)\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(source)
            .map { it.groupValues[1] }
            .filter { it in mapCodecRequiredOwners }
            .forEach { mapCodecTypes += it }
    }
    if (mapCodecTypes.isEmpty()) return source

    var result = source
    var changed = false
    for (className in mapCodecTypes) {
        result = Regex(
            """\bCodec\s*<\s*${Regex.escape(className)}\s*>\s+([A-Z][A-Z0-9_]*)\s*=\s*RecordCodecBuilder\s*\.\s*(?:<\s*${Regex.escape(className)}\s*>\s*)?create\s*\("""
        ).replace(result) { match ->
            changed = true
            "MapCodec<$className> ${match.groupValues[1]} = RecordCodecBuilder.<$className>mapCodec("
        }
        result = Regex(
            """\bCodec\s*<\s*${Regex.escape(className)}\s*>\s+([A-Z][A-Z0-9_]*)\s*=\s*simpleCodec\s*\("""
        ).replace(result) { match ->
            changed = true
            "MapCodec<$className> ${match.groupValues[1]} = simpleCodec("
        }

        result = Regex(
            """\bCodec\s*<\s*${Regex.escape(className)}\s*>\s+([A-Z][A-Z0-9_]*)\s*=\s*((?:[A-Za-z_$][\w$]*\.)*[A-Z][A-Za-z0-9_$]*(?:\.[A-Z][A-Za-z0-9_$]*)*\.CODEC(?:\.codec\(\))?\.listOf\(\))\.xmap\(([^;\r\n]+)\);"""
        ).replace(result) { match ->
            changed = true
            "MapCodec<$className> ${match.groupValues[1]} = ${match.groupValues[2]}.fieldOf(\"value\").xmap(${match.groupValues[3]});"
        }
        result = Regex(
            """\bCodec\s*<\s*${Regex.escape(className)}\s*>\s+([A-Z][A-Z0-9_]*)\s*=\s*((?:[A-Za-z_$][\w$]*\.)*[A-Z][A-Za-z0-9_$]*(?:\.[A-Z][A-Za-z0-9_$]*)*\.CODEC(?:\.codec\(\))?\.listOf\(\)\.fieldOf\("[^"]+"\)\.xmap\([^;\r\n]+?\))\.codec\(\)\s*;"""
        ).replace(result) { match ->
            changed = true
            "MapCodec<$className> ${match.groupValues[1]} = ${match.groupValues[2]};"
        }
        result = Regex(
            """\bCodec\s*<\s*${Regex.escape(className)}\s*>\s+([A-Z][A-Z0-9_]*)\s*=\s*Codec\.unit\(\s*\(\s*\)\s*->\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\)\s*;"""
        ).replace(result) { match ->
            changed = true
            "MapCodec<$className> ${match.groupValues[1]} = MapCodec.unit(${match.groupValues[2]});"
        }
        result = Regex(
            """\bCodec\s*<\s*${Regex.escape(className)}\s*>\s+([A-Z][A-Z0-9_]*)\s*=\s*Codec\.unit\(\s*([^;\r\n]+?)\s*\)\s*;"""
        ).replace(result) { match ->
            changed = true
            "MapCodec<$className> ${match.groupValues[1]} = MapCodec.unit(${match.groupValues[2].trim()});"
        }
        result = Regex(
            """\bCodec\s*<\s*${Regex.escape(className)}\s*>\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;\r\n]+?\.fieldOf\("[^"]+"\)\.xmap\([^;\r\n]+?\))\.codec\(\)\s*;"""
        ).replace(result) { match ->
            changed = true
            "MapCodec<$className> ${match.groupValues[1]} = ${match.groupValues[2]};"
        }
        result = Regex(
            """\bCodec\s*<\s*${Regex.escape(className)}\s*>\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;\r\n]+?\.(?:comapFlatMap|flatXmap|xmap)\([^;\r\n]+?\))\s*;"""
        ).replace(result) { match ->
            val codecExpression = match.groupValues[2]
            changed = true
            "MapCodec<$className> ${match.groupValues[1]} = $codecExpression.fieldOf(\"value\");"
        }
    }

    if (changed) {
        result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
        if (!Regex("""\bCodec\s*<""").containsMatchIn(result) && !Regex("""\bCodec\.""").containsMatchIn(result)) {
            result = removeImport(result, "com.mojang.serialization.Codec")
        }
    }
    if (result.contains("extends BiomeSource")) {
        result = Regex("""protected\s+Codec\s*<\s*\?\s+extends\s+BiomeSource\s*>\s+codec\s*\(""")
            .replace(result, "protected MapCodec<? extends BiomeSource> codec(")
    }
    return result
}

internal fun StructuralRefactorPass.migrateMapCodecRegistryFactorySignatures(source: String): String {
    if (!source.contains("Codec<")) return source
    val registryTypes = listOf(
        "PlacementModifierType",
        "StructureProcessorType",
        "StructurePlacementType",
        "StructureType",
        "TreeDecoratorType"
    )
    var result = source
    var changed = false
    registryTypes.forEach { registryType ->
        result = Regex(
            """\b(${Regex.escape(registryType)}\s*<\s*([A-Za-z_$][\w$]*)\s*>\s+[A-Za-z_$][\w$]*\s*\((?:[^()]|\([^()]*\))*?,\s*)(?:com\.mojang\.serialization\.)?Codec\s*<\s*\2\s*>\s+([A-Za-z_$][\w$]*)"""
        ).replace(result) { match ->
            changed = true
            "${match.groupValues[1]}MapCodec<${match.groupValues[2]}> ${match.groupValues[3]}"
        }
    }
    if (!changed) return source
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    val withoutCodecImport = removeImport(result, "com.mojang.serialization.Codec")
    return if (!Regex("""(?<!Map)\bCodec\b""").containsMatchIn(withoutCodecImport)) withoutCodecImport else result
}

internal data class PlacementModifierTypeRegistryField(
    val ownerSimpleName: String,
    val ownerQualifiedName: String,
    val fieldName: String
)

internal fun StructuralRefactorPass.collectPlacementModifierTypeRegistryFields(javaFiles: List<Path>): Set<PlacementModifierTypeRegistryField> {
    val fields = linkedSetOf<PlacementModifierTypeRegistryField>()
    javaFiles.forEach { javaFile ->
        val source = javaFile.readText()
        if (!source.contains("BuiltInRegistries.PLACEMENT_MODIFIER_TYPE") ||
            !source.contains("PlacementModifierType")) {
            return@forEach
        }
        val owner = classNameOfJavaSource(source) ?: return@forEach
        val packageName = packageNameOf(source)
        val qualifiedOwner = if (packageName.isBlank()) owner else "$packageName.$owner"
        Regex(
            """(?m)\bstatic\s+final\s+PlacementModifierType\s*<\s*[^>\r\n]+>\s+([A-Z][A-Z0-9_]*)\s*=\s*register\s*\("""
        ).findAll(source)
            .mapTo(fields) { PlacementModifierTypeRegistryField(owner, qualifiedOwner, it.groupValues[1]) }
    }
    return fields
}

internal fun StructuralRefactorPass.migrateBuiltInPlacementModifierTypeRegistrations(source: String): String {
    if (!source.contains("BuiltInRegistries.PLACEMENT_MODIFIER_TYPE") ||
        !source.contains("PlacementModifierType")) {
        return source
    }
    val modIdExpression = placementModifierRegistryNamespaceExpression(source) ?: return source
    var changed = false
    var result = Regex(
        """(?m)^([ \t]*(?:public|protected|private)?\s*static\s+final\s+)PlacementModifierType\s*<\s*([^>\r\n]+)\s*>\s+([A-Z][A-Z0-9_]*)\s*=\s*register\s*\(\s*(.+?)\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.CODEC)\s*\)\s*;"""
    ).replace(source) { match ->
        val pathExpression = placementModifierRegistryPathExpression(match.groupValues[4].trim())
            ?: return@replace match.value
        changed = true
        "${match.groupValues[1]}DeferredHolder<PlacementModifierType<?>, PlacementModifierType<${match.groupValues[2].trim()}>> ${match.groupValues[3]} = register($pathExpression, ${match.groupValues[5].trim()});"
    }
    if (!changed) return source

    result = ensurePlacementModifierDeferredRegister(result, modIdExpression)
    result = Regex(
        """(?ms)^([ \t]*(?:public|protected|private)?\s*static\s*<\s*P\s+extends\s+PlacementModifier\s*>\s*)PlacementModifierType\s*<\s*P\s*>\s+register\s*\(\s*ResourceLocation\s+[A-Za-z_$][\w$]*\s*,\s*MapCodec\s*<\s*P\s*>\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*return\s+Registry\.register\s*\(\s*BuiltInRegistries\.PLACEMENT_MODIFIER_TYPE\s*,\s*[A-Za-z_$][\w$]*\s*,\s*\(\)\s*->\s*[A-Za-z_$][\w$]*\s*\)\s*;\s*\}"""
    ).replace(result) { match ->
        val indent = match.groupValues[1].takeWhile { it.isWhitespace() }
        "${match.groupValues[1]}DeferredHolder<PlacementModifierType<?>, PlacementModifierType<P>> register(String name, MapCodec<P> codec) {\n${indent}    return PLACEMENT_MODIFIER_TYPES.register(name, () -> () -> codec);\n$indent}"
    }
    result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
    result = addImportIfMissing(result, "net.neoforged.neoforge.registries.DeferredHolder")
    result = addImportIfMissing(result, "net.neoforged.neoforge.registries.DeferredRegister")
    if (!result.contains("Registry.")) result = removeImport(result, "net.minecraft.core.Registry")
    if (!result.contains("BuiltInRegistries.")) result = removeImport(result, "net.minecraft.core.registries.BuiltInRegistries")
    if (!Regex("""\bResourceLocation\b""").containsMatchIn(removeImport(result, "net.minecraft.resources.ResourceLocation"))) {
        result = removeImport(result, "net.minecraft.resources.ResourceLocation")
    }
    return result
}

internal fun StructuralRefactorPass.placementModifierRegistryNamespaceExpression(source: String): String? {
    Regex("""ResourceLocation\.fromNamespaceAndPath\s*\(\s*([^,\r\n]+?)\s*,""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.let { return it }
    Regex("""ResourceLocation\.parse\s*\(\s*"([a-z0-9_.-]+):[a-z0-9_./-]+"\s*\)""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.let { return "\"$it\"" }
    return null
}

internal fun StructuralRefactorPass.placementModifierRegistryPathExpression(nameExpression: String): String? {
    Regex("""ResourceLocation\.fromNamespaceAndPath\s*\(\s*[^,\r\n]+?\s*,\s*([^)]+?)\s*\)""")
        .matchEntire(nameExpression)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.let { return it }
    Regex("""ResourceLocation\.parse\s*\(\s*"[a-z0-9_.-]+:([a-z0-9_./-]+)"\s*\)""")
        .matchEntire(nameExpression)
        ?.groupValues
        ?.get(1)
        ?.let { return "\"$it\"" }
    return null
}

internal fun StructuralRefactorPass.ensurePlacementModifierDeferredRegister(source: String, modIdExpression: String): String {
    if (source.contains("DeferredRegister<PlacementModifierType<?>> PLACEMENT_MODIFIER_TYPES")) return source
    val classMatch = Regex("""\bclass\s+[A-Za-z_$][\w$]*[^{]*\{""").find(source) ?: return source
    val declaration = "\n    public static final DeferredRegister<PlacementModifierType<?>> PLACEMENT_MODIFIER_TYPES = DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, $modIdExpression);\n"
    return source.substring(0, classMatch.range.last + 1) + declaration + source.substring(classMatch.range.last + 1)
}

internal fun StructuralRefactorPass.migratePlacementModifierTypeDeferredHolderReferences(
    source: String,
    fields: Set<PlacementModifierTypeRegistryField>
): String {
    if (fields.isEmpty()) return source
    val currentOwner = classNameOfJavaSource(source)
    var result = source
    for (field in fields) {
        result = Regex("""\breturn\s+${Regex.escape(field.ownerSimpleName)}\.${Regex.escape(field.fieldName)}\s*;""")
            .replace(result, "return ${field.ownerSimpleName}.${field.fieldName}.get();")
        result = Regex("""\breturn\s+${Regex.escape(field.ownerQualifiedName)}\.${Regex.escape(field.fieldName)}\s*;""")
            .replace(result, "return ${field.ownerQualifiedName}.${field.fieldName}.get();")
        if (currentOwner == field.ownerSimpleName) {
            result = Regex("""\breturn\s+${Regex.escape(field.fieldName)}\s*;""")
                .replace(result, "return ${field.fieldName}.get();")
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateMapCodecSerializationCalls(
    source: String,
    mapCodecConstantOwners: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (mapCodecConstantOwners.isEmpty() || !executableCode.contains(".CODEC.")) return source
    var result = source
    for (owner in mapCodecConstantOwners) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b((?:[A-Za-z_$][\w$]*\.)*${Regex.escape(owner)}\.CODEC)\.(parse|encodeStart)\s*\(""")
        ) { match -> "${match.groupValues[1]}.codec().${match.groupValues[2]}(" }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyDataResultGetOrThrowCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getOrThrow(")) return source
    return rewriteExecutableJavaCall(source, "getOrThrow") { receiver, args ->
        if (args.size != 2 || args[0].trim() != "true") return@rewriteExecutableJavaCall null
        val onError = args[1].trim()
        "$receiver.promotePartial($onError).getOrThrow(IllegalStateException::new)"
    }
}

internal fun StructuralRefactorPass.migrateKnownVanillaCodecCodecCalls(
    source: String,
    mapCodecConstantOwners: Set<String>,
    codecConstantOwners: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".CODEC.codec()")) return source
    val knownCodecOwners = setOf(
        "BlockState",
        "BoundingBox",
        "MobSpawnSettings.SpawnerData",
        "ProcessorRule",
        "ResourceLocation",
        "TreeDecorator"
    )
    var result = source
    for (owner in knownCodecOwners + codecConstantOwners) {
        if (owner in mapCodecConstantOwners) continue
        result = replaceExecutableRegex(
            result,
            Regex("""\b((?:[A-Za-z_$][\w$]*\.)*${Regex.escape(owner)}\.CODEC)\.codec\(\)""")
        ) { match ->
            match.groupValues[1]
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateMapCodecFeatureConstructorArguments(
    source: String,
    mapCodecConstantOwners: Set<String>
): String {
    if (mapCodecConstantOwners.isEmpty() || !source.contains("new ") || !source.contains(".CODEC")) return source
    var result = source
    var cursor = 0
    val pattern = Regex("""new\s+([A-Za-z_$][\w$]*)\s*\(""")
    while (true) {
        val match = pattern.find(result, cursor) ?: break
        val className = match.groupValues[1]
        if (!className.endsWith("Feature")) {
            cursor = match.range.last + 1
            continue
        }
        val openParen = result.indexOf('(', match.range.last)
        val closeParen = if (openParen >= 0) findMatchingParen(result, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val migratedArgs = args.map { arg -> migrateMapCodecReferenceArgument(arg, mapCodecConstantOwners) }
        if (migratedArgs != args) {
            val replacement = migratedArgs.joinToString(", ")
            result = result.substring(0, openParen + 1) + replacement + result.substring(closeParen)
            cursor = openParen + replacement.length + 1
        } else {
            cursor = closeParen + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateMapCodecReferenceArgument(arg: String, mapCodecConstantOwners: Set<String>): String {
    val trimmed = arg.trim()
    if (trimmed.endsWith(".codec()")) return arg
    val owner = mapCodecCodecReferenceOwner(trimmed) ?: return arg
    return if (owner in mapCodecConstantOwners) "${trimmed}.codec()" else arg
}

internal fun StructuralRefactorPass.migrateLegacyLazyRegistryCodecs(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ExtraCodecs.lazyInitializedCodec(") || !executableCode.contains(".byNameCodec()")) return source
    var result = replaceExecutableJavaRegex(source, Regex(
        """ExtraCodecs\.lazyInitializedCodec\(\s*\(\s*\)\s*->\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.byNameCodec\(\))\s*\)"""
    )) { match ->
        match.groupValues[1]
    }
    if (!maskJavaCommentsAndLiterals(result).contains("ExtraCodecs.")) {
        result = removeExecutableImport(result, "net.minecraft.util.ExtraCodecs")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyDeferredHolderDelegateAccessors(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getHolder().orElseThrow()")) return source
    return replaceExecutableJavaRegex(source, Regex("""\.getHolder\(\)\.orElseThrow\(\)""")) {
        ".getDelegate()"
    }
}

internal fun StructuralRefactorPass.migrateLegacyRegistryObjectMethodReferences(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("RegistryObject::get")) return source
    var result = replaceExecutableJavaRegex(source, Regex("""\bRegistryObject::get\b""")) {
        "DeferredHolder::get"
    }
    result = addExecutableImportIfMissing(result, "net.neoforged.neoforge.registries.DeferredHolder")
    if (!Regex("""\bRegistryObject\b""").containsMatchIn(maskJavaCommentsAndLiterals(result))) {
        result = removeExecutableImport(result, "net.neoforged.neoforge.registries.RegistryObject")
        result = removeExecutableImport(result, "net.minecraftforge.registries.RegistryObject")
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeferredHolderCollectionVariance(source: String): String {
    if (!source.contains("DeferredHolder::get") && !source.contains(".getEntries().stream().map(")) return source
    return Regex(
        """Collection\s*<\s*Item\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]*\.getEntries\(\)\.stream\(\)\.map\(DeferredHolder::get\)\.toList\(\)\s*;)"""
    ).replace(source) { match ->
        "Collection<? extends Item> ${match.groupValues[1]} = ${match.groupValues[2]}"
    }
}

internal fun StructuralRefactorPass.migrateDeferredHolderGetValueCalls(
    source: String,
    deferredHolderFields: Map<String, Set<String>>
): String {
    if (!source.contains(".getValue()") || deferredHolderFields.isEmpty()) return source
    var result = source
    deferredHolderFields.forEach { (owner, fields) ->
        fields.forEach { field ->
            result = Regex("""\b${Regex.escape(owner)}\.${Regex.escape(field)}\.getValue\(\)""")
                .replace(result, "$owner.$field.get()")
        }
    }
    val localOwner = classNameOfJavaSource(source)
    val localFields = localOwner?.let { deferredHolderFields[it] }.orEmpty()
    localFields.forEach { field ->
        result = Regex("""\b${Regex.escape(field)}\.getValue\(\)""")
            .replace(result, "$field.get()")
    }
    return result
}
