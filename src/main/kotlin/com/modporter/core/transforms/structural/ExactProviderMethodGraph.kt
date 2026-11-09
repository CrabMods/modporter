package com.modporter.core.transforms.structural

import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import java.nio.file.Path
import kotlin.io.path.readText

internal fun StructuralRefactorPass.collectExactItemStackNbtMethodsNeedingHolderLookup(
    javaFiles: List<Path>,
    javaInheritanceIndex: JavaInheritanceIndex,
    javaProjectTypeIndex: JavaProjectTypeIndex
): Set<HolderLookupNbtMethod> {
    data class ConstructorKey(
        val ownerQualified: String,
        val parameterTypes: List<String>
    )

    data class Candidate(
        val file: Path,
        val owner: String,
        val ownerQualified: String,
        val methodName: String,
        val parameterCount: Int,
        val parameterTypes: List<String>?,
        val header: String,
        val source: String,
        val range: IntRange,
        val declaration: MethodDeclaration,
        val directProviderDemand: Boolean,
        val directProviderDemandOffsets: List<Int>,
        val contractForwardingDemand: Boolean,
        val providerIndexHint: Int?
    )

    data class ProviderAwareTarget(
        val ownerQualified: String,
        val methodName: String,
        val legacyParameterTypes: List<String>
    )

    data class CandidateKey(
        val ownerQualified: String,
        val methodName: String,
        val parameterTypes: List<String>
    )

    val sourcesByFile = javaFiles.associateWith { javaFile ->
        runCatching { javaFile.readText() }.getOrDefault("")
    }
    val candidates = mutableListOf<Candidate>()
    val projectSerializableTypeFqns = collectProjectINBTSerializableTypes(javaFiles)
    val entityDataValueTypes = mergeEntityDataValueTypes(
        collectEntityCapabilityValueTypes(javaFiles),
        collectEntityAttachmentValueTypes(javaFiles)
    )
    val providerDemandingConstructors = javaFiles.flatMap { javaFile ->
        val source = sourcesByFile.getValue(javaFile)
        if (!source.contains("CompoundTag")) return@flatMap emptyList()
        val classDeclarationsByName = collectJavaClassDeclarations(source).associateBy { it.name }
        val itemStackNames = collectItemStackValueNames(source)
        val itemStackCollectionNames = collectItemStackCollectionNames(source)
        val itemStackHandlerNames = collectItemStackHandlerValueNames(source)
        val fluidTankNames = collectFluidTankValueNames(source, javaInheritanceIndex)
        javaProjectTypeIndex.unit(javaFile)
            .findAll(com.github.javaparser.ast.body.ConstructorDeclaration::class.java)
            .mapNotNull constructors@{ constructor ->
                val owner = exactEnclosingNamedClass(constructor) ?: return@constructors null
                val ownerQualified = owner.fullyQualifiedName.orElse(null) ?: return@constructors null
                val parameterTypes = constructor.parameters.map { parameter ->
                    javaProjectTypeIndex.declaredType(parameter.type, constructor)
                        ?: return@constructors null
                }
                val directlyNeedsProvider = methodTextNeedsHolderLookupProvider(
                    constructor.toString(),
                    itemStackNames,
                    itemStackCollectionNames,
                    itemStackHandlerNames,
                    fluidTankNames,
                    classDeclarationsByName[owner.nameAsString]?.let {
                        javaClassExtendsAny(it, setOf("ItemStackHandler"), javaInheritanceIndex)
                    } == true
                )
                ConstructorKey(ownerQualified, parameterTypes).takeIf { directlyNeedsProvider }
            }
    }.toSet()

    fun exactProviderDemandingConstructor(call: ObjectCreationExpr): ConstructorKey? {
        val ownerQualified = javaProjectTypeIndex.expressionType(call, call) ?: return null
        val matches = providerDemandingConstructors.filter { constructor ->
            constructor.ownerQualified == ownerQualified &&
                call.arguments.size == constructor.parameterTypes.size &&
                call.arguments.zip(constructor.parameterTypes).all { (argument, expectedType) ->
                    javaProjectTypeIndex.isExpressionAssignableTo(argument, call, expectedType)
                }
        }
        val mostSpecific = matches.filter { candidate ->
            matches.none { other ->
                other != candidate &&
                    other.parameterTypes.zip(candidate.parameterTypes).all { (actual, expected) ->
                        javaProjectTypeIndex.isTypeAssignableTo(actual, expected)
                    }
            }
        }
        if (mostSpecific.size > 1) {
            throw IllegalStateException(
                "Ambiguous exact provider-demanding constructor '$call': $mostSpecific"
            )
        }
        return mostSpecific.singleOrNull()
    }

    val providerAwareTargets = javaFiles.flatMap { javaFile ->
        javaProjectTypeIndex.unit(javaFile)
            .findAll(MethodDeclaration::class.java)
            .mapNotNull targets@{ method ->
                val owner = exactEnclosingNamedClass(method)
                    ?.fullyQualifiedName
                    ?.orElse(null)
                    ?: return@targets null
                val parameterTypes = method.parameters.map { parameter ->
                    javaProjectTypeIndex.declaredType(parameter.type, method) ?: return@targets null
                }
                val providerIndexes = parameterTypes.mapIndexedNotNull { index, type ->
                    index.takeIf { type == "net.minecraft.core.HolderLookup.Provider" }
                }
                if (providerIndexes.size == 1) {
                    val legacyTypes = parameterTypes.filterIndexed { index, _ ->
                        index != providerIndexes.single()
                    }
                    val returnType = javaProjectTypeIndex.declaredType(method.type, method)
                    if ("net.minecraft.nbt.CompoundTag" !in legacyTypes &&
                        returnType != "net.minecraft.nbt.CompoundTag"
                    ) {
                        return@targets null
                    }
                    return@targets ProviderAwareTarget(
                        owner,
                        method.nameAsString,
                        legacyTypes
                    )
                }
                if (providerIndexes.isNotEmpty() ||
                    owner !in projectSerializableTypeFqns
                ) {
                    return@targets null
                }
                when {
                    method.nameAsString == "serializeNBT" &&
                        parameterTypes.isEmpty() &&
                        javaProjectTypeIndex.declaredType(method.type, method) ==
                        "net.minecraft.nbt.CompoundTag" ->
                        ProviderAwareTarget(owner, method.nameAsString, emptyList())

                    method.nameAsString == "deserializeNBT" &&
                        parameterTypes ==
                        listOf("net.minecraft.nbt.CompoundTag") ->
                        ProviderAwareTarget(
                            owner,
                            method.nameAsString,
                            parameterTypes
                        )

                    else -> null
                }
            }
    }

    fun isExactProviderAwareProjectCall(call: MethodCallExpr): Boolean {
        val receiverType = javaProjectTypeIndex.methodCallReceiverType(call) ?: return false
        val matches = providerAwareTargets.filter { target ->
            target.methodName == call.nameAsString &&
                target.legacyParameterTypes.size == call.arguments.size &&
                javaProjectTypeIndex.isTypeAssignableTo(receiverType, target.ownerQualified) &&
                javaProjectTypeIndex.argumentsMatchTypes(call, target.legacyParameterTypes)
        }
        val mostSpecific = matches.filter { target ->
            matches.none { other ->
                other != target &&
                    javaProjectTypeIndex.isTypeAssignableTo(
                        other.ownerQualified,
                        target.ownerQualified
                    )
            }
        }
        if (mostSpecific.size > 1) {
            throw IllegalStateException(
                "Ambiguous exact provider-aware project call '$call': $mostSpecific"
            )
        }
        return mostSpecific.size == 1
    }

    fun isExactLegacyBlockEntityProviderCall(call: MethodCallExpr): Boolean {
        val receiverType = javaProjectTypeIndex.methodCallReceiverType(call) ?: return false
        if (!javaProjectTypeIndex.isTypeAssignableTo(
                receiverType,
                "net.minecraft.world.level.block.entity.BlockEntity"
            )
        ) {
            return false
        }
        return when (call.nameAsString) {
            "saveAdditional", "load", "loadAdditional", "handleUpdateTag" ->
                call.arguments.size == 1 &&
                    javaProjectTypeIndex.isExpressionAssignableTo(
                        call.arguments.single(),
                        call,
                        "net.minecraft.nbt.CompoundTag"
                    )

            "getUpdateTag", "saveWithFullMetadata", "saveWithoutMetadata", "saveCustomOnly" ->
                call.arguments.isEmpty()

            "onDataPacket" ->
                call.arguments.size == 2 &&
                    javaProjectTypeIndex.isExpressionAssignableTo(
                        call.arguments[0],
                        call,
                        "net.minecraft.network.Connection"
                    ) &&
                    javaProjectTypeIndex.isExpressionAssignableTo(
                        call.arguments[1],
                        call,
                        "net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket"
                    )

            else -> false
        }
    }

    for (javaFile in javaFiles) {
        val source = sourcesByFile.getValue(javaFile)
        val classDeclarationsByName = collectJavaClassDeclarations(source).associateBy { it.name }
        val itemStackNames = collectItemStackValueNames(source)
        val itemStackCollectionNames = collectItemStackCollectionNames(source)
        val itemStackHandlerNames = collectItemStackHandlerValueNames(source)
        val fluidTankNames = collectFluidTankValueNames(source, javaInheritanceIndex)
        val lineOffsets = buildList {
            add(0)
            source.forEachIndexed { index, character ->
                if (character == '\n') add(index + 1)
            }
        }

        fun offset(position: com.github.javaparser.Position): Int =
            lineOffsets.getOrElse(position.line - 1) {
                throw IllegalStateException(
                    "Invalid JavaParser line ${position.line} in $javaFile"
                )
            } + position.column - 1

        for (method in javaProjectTypeIndex.unit(javaFile).findAll(MethodDeclaration::class.java)) {
            var ownerNode: com.github.javaparser.ast.Node? = method.parentNode.orElse(null)
            var ownerDeclaration: TypeDeclaration<*>? = null
            while (ownerNode != null) {
                if (ownerNode is ObjectCreationExpr) break
                if (ownerNode is TypeDeclaration<*>) {
                    ownerDeclaration = ownerNode
                    break
                }
                ownerNode = ownerNode.parentNode.orElse(null)
            }
            val owner = ownerDeclaration ?: continue
            val ownerQualified = owner.fullyQualifiedName.orElse(null) ?: continue
            if (method.parameters.any { parameter ->
                    javaProjectTypeIndex.declaredType(parameter.type, method) ==
                        "net.minecraft.core.HolderLookup.Provider"
                }
            ) {
                continue
            }
            val nodeRange = method.range.orElse(null) ?: continue
            val start = offset(nodeRange.begin)
            val endExclusive = offset(nodeRange.end) + 1
            if (start !in source.indices ||
                endExclusive !in 1..source.length ||
                start >= endExclusive
            ) {
                throw IllegalStateException(
                    "Invalid method source range for $ownerQualified.${method.nameAsString} in $javaFile"
                )
            }
            val methodRange = start until endExclusive
            val methodText = source.substring(methodRange)
            val headerEnd = method.body
                .flatMap { it.range }
                .map { range -> offset(range.begin) }
                .orElse(endExclusive)
            val header = source.substring(start, headerEnd.coerceIn(start, endExclusive))
            val parameterTypes = method.parameters.map { parameter ->
                javaProjectTypeIndex.declaredType(parameter.type, method)
            }.takeIf { types -> types.none { it == null } }?.filterNotNull()
            val ownerExtendsItemStackHandler = classDeclarationsByName[owner.nameAsString]?.let {
                javaClassExtendsAny(it, setOf("ItemStackHandler"), javaInheritanceIndex)
            } == true
            val directNbtProviderDemand = methodTextNeedsHolderLookupProvider(
                methodText,
                itemStackNames,
                itemStackCollectionNames,
                itemStackHandlerNames,
                fluidTankNames,
                ownerExtendsItemStackHandler
            )
            val directItemStackOrHandlerDemand =
                methodTextNeedsItemStackProvider(
                    methodText,
                    itemStackNames,
                    itemStackCollectionNames
                ) ||
                    itemStackHandlerNames.any { name ->
                        Regex(
                            """(?<![\w$])(?:this\.)?${Regex.escape(name)}\.""" +
                                """(?:serializeNBT|deserializeNBT)\s*\("""
                        ).containsMatchIn(maskJavaCommentsAndLiterals(methodText))
                    }
            fun nativeCallNeedsProvider(call: MethodCallExpr): Boolean =
                methodTextNeedsHolderLookupProvider(
                    call.toString(),
                    itemStackNames,
                    itemStackCollectionNames,
                    itemStackHandlerNames,
                    fluidTankNames,
                    ownerExtendsItemStackHandler
                )
            val methodCalls = method.findAll(MethodCallExpr::class.java).filter { call ->
                call.findAncestor(MethodDeclaration::class.java).orElse(null) === method
            }
            val nativeDemandCalls = methodCalls.filter(::nativeCallNeedsProvider).filter { call ->
                call.findAll(MethodCallExpr::class.java).none { nested ->
                    nested !== call && nativeCallNeedsProvider(nested)
                }
            }
            val providerAwareDemandCalls = methodCalls.filter { call ->
                isExactProviderAwareProjectCall(call) || isExactLegacyBlockEntityProviderCall(call)
            }
            val directMethodCallProviderDemand = providerAwareDemandCalls.isNotEmpty()
            val providerDemandingConstructorsInMethod = method.findAll(ObjectCreationExpr::class.java).filter { call ->
                call.findAncestor(MethodDeclaration::class.java).orElse(null) === method &&
                    exactProviderDemandingConstructor(call) != null
            }
            val directConstructorProviderDemand = providerDemandingConstructorsInMethod.isNotEmpty()
            val nativeDemandReferences = method.findAll(MethodReferenceExpr::class.java).filter { reference ->
                reference.findAncestor(MethodDeclaration::class.java).orElse(null) === method &&
                    methodTextNeedsHolderLookupProvider(
                        reference.toString(),
                        itemStackNames,
                        itemStackCollectionNames,
                        itemStackHandlerNames,
                        fluidTankNames,
                        ownerExtendsItemStackHandler
                    )
            }
            val directProviderDemandOffsets = buildList<Node> {
                addAll(nativeDemandCalls)
                addAll(providerAwareDemandCalls)
                addAll(providerDemandingConstructorsInMethod)
                addAll(nativeDemandReferences)
            }.mapNotNull { node ->
                node.range.orElse(null)?.begin?.let(::offset)
            }.distinct()
            val directlyNeedsProvider = directNbtProviderDemand ||
                directMethodCallProviderDemand ||
                directConstructorProviderDemand
            if (directlyNeedsProvider && parameterTypes == null) {
                throw IllegalStateException(
                    "Cannot resolve exact parameter types for provider-demanded method " +
                        "$ownerQualified.${method.nameAsString}"
                )
            }
            candidates += Candidate(
                javaFile,
                owner.nameAsString,
                ownerQualified,
                method.nameAsString,
                method.parameters.size,
                parameterTypes,
                header,
                source,
                methodRange,
                method,
                directlyNeedsProvider,
                directProviderDemandOffsets,
                directMethodCallProviderDemand,
                when {
                    directItemStackOrHandlerDemand ||
                        directNbtProviderDemand ||
                        (
                            directConstructorProviderDemand &&
                                !directMethodCallProviderDemand
                            ) -> method.parameters.size

                    else -> null
                }
            )
        }
    }

    fun candidateForOverrideMember(
        member: JavaProjectTypeIndex.ExactProjectMethod
    ): Candidate {
        val source = sourcesByFile[member.file] ?: runCatching {
            member.file.readText()
        }.getOrElse { error ->
            throw IllegalStateException(
                "Cannot read exact override-family source ${member.file}: ${error.message}",
                error
            )
        }
        val lineOffsets = buildList {
            add(0)
            source.forEachIndexed { index, character ->
                if (character == '\n') add(index + 1)
            }
        }

        fun offset(position: com.github.javaparser.Position): Int =
            lineOffsets.getOrElse(position.line - 1) {
                throw IllegalStateException(
                    "Invalid JavaParser line ${position.line} in ${member.file}"
                )
            } + position.column - 1

        val method = member.method
        val owner = exactEnclosingNamedClass(method)
            ?: throw IllegalStateException(
                "Cannot resolve exact override-family owner for ${member.owner}"
            )
        val nodeRange = method.range.orElse(null)
            ?: throw IllegalStateException(
                "Missing exact source range for ${member.owner}.${method.nameAsString}"
            )
        val start = offset(nodeRange.begin)
        val endExclusive = offset(nodeRange.end) + 1
        if (start !in source.indices ||
            endExclusive !in 1..source.length ||
            start >= endExclusive
        ) {
            throw IllegalStateException(
                "Invalid exact source range for ${member.owner}.${method.nameAsString} in ${member.file}"
            )
        }
        val bodyStart = method.body
            .flatMap { it.range }
            .map { range -> offset(range.begin) }
            .orElse(endExclusive)
        val parameterTypes = method.parameters.map { parameter ->
            javaProjectTypeIndex.declaredType(parameter.type, method)
                ?: throw IllegalStateException(
                    "Cannot resolve exact override-family parameter type for " +
                        "${member.owner}.${method.nameAsString}"
                )
        }
        return Candidate(
            member.file,
            owner.nameAsString,
            member.owner,
            method.nameAsString,
            method.parameters.size,
            parameterTypes,
            source.substring(start, bodyStart.coerceIn(start, endExclusive)),
            source,
            start until endExclusive,
            method,
            directProviderDemand = false,
            directProviderDemandOffsets = emptyList(),
            contractForwardingDemand = false,
            providerIndexHint = null
        )
    }

    val directCandidateOwners = candidates.mapTo(linkedSetOf()) { it.owner }

    fun receiverOwnerAt(
        source: String,
        executableCode: String,
        receiver: String,
        offset: Int
    ): String? {
        val normalized = receiver.trim()
        val typeBlocks = javaTypeBlocks(source, executableCode)
        if (normalized == "this") {
            return typeBlocks.lastOrNull { offset in it.bodyStart..it.end }?.name
        }
        if (normalized in directCandidateOwners) return normalized
        val ownerAlternation = directCandidateOwners.joinToString("|") { Regex.escape(it) }
        if (ownerAlternation.isBlank()) return null
        val variableTypes = Regex(
            """\b($ownerAlternation)\s+([A-Za-z_$][\w$]*)\b"""
        ).findAll(executableCode)
            .associate { it.groupValues[2] to it.groupValues[1] }
        val getterTypes = Regex(
            """(?m)\b(?:public|protected|private)\s+($ownerAlternation)\s+([A-Za-z_$][\w$]*)\s*\(\s*\)"""
        ).findAll(executableCode)
            .associate { "${it.groupValues[2]}()" to it.groupValues[1] }
        variableTypes[normalized]?.let { return it }
        variableTypes[normalized.substringAfterLast('.')]?.let { return it }
        getterTypes[normalized]?.let { return it }
        getterTypes[normalized.substringAfterLast('.')]?.let { return it }
        return null
    }

    fun legacyINBTSerializableProviderCallSite(source: String, offset: Int): Boolean {
        if (projectSerializableTypeFqns.isEmpty()) return false
        val executableCode = maskJavaCommentsAndLiterals(source)
        val method = javaMethodRangesIncludingDefault(source)
            .firstOrNull { offset in it.range }
            ?: return false
        if (!Regex("""\b(?:public|protected)\b""").containsMatchIn(method.header)) {
            return false
        }
        if (method.name == "serializeNBT") {
            if (!method.header.contains("CompoundTag") ||
                method.header.contains("HolderLookup.Provider")
            ) {
                return false
            }
        } else if (method.name == "deserializeNBT") {
            if (!method.header.contains("CompoundTag") ||
                method.header.contains("HolderLookup.Provider")
            ) {
                return false
            }
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

    fun constructorHasProviderAwareCallSite(
        owner: String,
        parameterCount: Int
    ): Boolean {
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
                val args = splitTopLevelJavaArgs(
                    source.substring(openParen + 1, closeParen)
                )
                if (args.size == parameterCount &&
                    (
                        holderLookupProviderNamesAt(source, tokenIndex).isNotEmpty() ||
                            strictRegistryAccessExpressionAt(
                                source,
                                tokenIndex,
                                javaInheritanceIndex
                            ) != null
                        )
                ) {
                    return true
                }
                cursor = closeParen + 1
            }
        }
        return false
    }

    fun enclosingProviderAwareConstructorCallSite(
        source: String,
        offset: Int
    ): Boolean {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val type = javaTypeBlocks(source, executableCode)
            .lastOrNull { offset in it.bodyStart..it.end }
            ?: return false
        val constructorPattern = Regex(
            """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:public|protected|private)?\s*${Regex.escape(type.name)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
        )
        val constructor = constructorPattern.findAll(executableCode)
            .firstOrNull { match ->
                if (match.range.first <= type.bodyStart ||
                    match.range.first >= type.end
                ) {
                    return@firstOrNull false
                }
                val openBrace = executableCode.indexOf('{', match.range.last - 1)
                val closeBrace = if (openBrace >= 0) {
                    findMatchingBrace(executableCode, openBrace)
                } else {
                    -1
                }
                openBrace >= 0 && closeBrace >= offset && closeBrace <= type.end
            }
            ?: return false
        val openBrace = executableCode.indexOf('{', constructor.range.last - 1)
        val closeBrace = if (openBrace >= 0) {
            findMatchingBrace(executableCode, openBrace)
        } else {
            -1
        }
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
        return constructorHasProviderAwareCallSite(
            type.name,
            javaMethodParameterCount(constructor.value)
        )
    }

    fun providerEvidenceAtCallSite(
        source: String,
        offset: Int,
        receiver: String,
        targetOwner: String
    ): Boolean =
        holderLookupProviderNamesAt(source, offset).isNotEmpty() ||
            strictRegistryAccessExpressionAt(source, offset, javaInheritanceIndex) != null ||
            legacyINBTSerializableProviderCallSite(source, offset) ||
            enclosingProviderAwareConstructorCallSite(source, offset) ||
            (
                entityDataLambdaReceiverOwner(
                    source,
                    offset,
                    receiver.trim(),
                    entityDataValueTypes,
                    javaInheritanceIndex
                ) == targetOwner &&
                    entityDataLambdaRegistryAccess(
                        source,
                        offset,
                        receiver.trim(),
                        entityDataValueTypes,
                        javaInheritanceIndex
                    ) != null
                )

    fun hasProviderAwareProjectCallSite(target: Candidate): Boolean {
        val token = ".${target.methodName}("
        for (javaFile in javaFiles) {
            val source = sourcesByFile.getValue(javaFile)
            if (!source.contains(token)) continue
            val executableCode = maskJavaCommentsAndLiterals(source)
            var cursor = 0
            while (true) {
                val tokenIndex = executableCode.indexOf(token, cursor)
                if (tokenIndex < 0) break
                if (source == target.source && tokenIndex in target.range) {
                    cursor = tokenIndex + token.length
                    continue
                }
                val openParen = tokenIndex + target.methodName.length + 1
                val closeParen = findMatchingParen(executableCode, openParen)
                if (closeParen < 0) {
                    cursor = tokenIndex + token.length
                    continue
                }
                val args = splitTopLevelJavaArgs(
                    source.substring(openParen + 1, closeParen)
                )
                if (args.size != target.parameterCount) {
                    cursor = closeParen + 1
                    continue
                }
                val receiverStart = findExpressionReceiverStart(executableCode, tokenIndex)
                if (receiverStart < 0 || receiverStart >= tokenIndex) {
                    cursor = closeParen + 1
                    continue
                }
                val receiver = source.substring(receiverStart, tokenIndex)
                val receiverOwner = receiverOwnerAt(
                    source,
                    executableCode,
                    receiver,
                    tokenIndex
                ) ?: entityDataLambdaReceiverOwner(
                    source,
                    tokenIndex,
                    receiver.trim(),
                    entityDataValueTypes,
                    javaInheritanceIndex
                )
                if (receiverOwner == target.owner &&
                    providerEvidenceAtCallSite(
                        source,
                        tokenIndex,
                        receiver,
                        target.owner
                    )
                ) {
                    return true
                }
                cursor = closeParen + 1
            }
        }
        return false
    }

    fun hasExactProviderAwareProjectCallSite(target: Candidate): Boolean {
        val parameterTypes = target.parameterTypes ?: return false
        return javaFiles.any { javaFile ->
            val source = sourcesByFile.getValue(javaFile)
            if (!source.contains("${target.methodName}(")) return@any false
            val lineOffsets = buildList {
                add(0)
                source.forEachIndexed { index, character ->
                    if (character == '\n') add(index + 1)
                }
            }
            javaProjectTypeIndex.unit(javaFile)
                .findAll(MethodCallExpr::class.java)
                .any calls@{ call ->
                    if (call.nameAsString != target.methodName ||
                        call.arguments.size != target.parameterCount
                    ) {
                        return@calls false
                    }
                    val receiverType = javaProjectTypeIndex.methodCallReceiverType(call)
                        ?: return@calls false
                    if (!javaProjectTypeIndex.isTypeAssignableTo(
                            receiverType,
                            target.ownerQualified
                        ) ||
                        !javaProjectTypeIndex.argumentsMatchTypes(call, parameterTypes)
                    ) {
                        return@calls false
                    }
                    val caller = call.findAncestor(MethodDeclaration::class.java)
                        .orElse(null)
                        ?: return@calls false
                    if (caller === target.declaration) return@calls false
                    if (caller.parameters.any { parameter ->
                            javaProjectTypeIndex.declaredType(parameter.type, caller) ==
                                "net.minecraft.core.HolderLookup.Provider"
                        } ||
                        ExactExternalProviderContracts.providerExpression(
                            caller,
                            javaProjectTypeIndex
                        ) != null
                    ) {
                        return@calls true
                    }
                    val begin = call.range.orElse(null)?.begin ?: return@calls false
                    val offset = lineOffsets.getOrNull(begin.line - 1)
                        ?.plus(begin.column - 1)
                        ?: return@calls false
                    strictRegistryAccessExpressionAt(
                        source,
                        offset,
                        javaInheritanceIndex
                    ) != null
                }
        }
    }

    fun key(candidate: Candidate): CandidateKey = CandidateKey(
        candidate.ownerQualified,
        candidate.methodName,
        candidate.parameterTypes ?: candidate.declaration.parameters.map { parameter ->
            "unresolved:${normalizeJavaTypeForContract(parameter.typeAsString)}"
        }
    )

    fun exactCandidateMap(values: List<Candidate>): Map<CandidateKey, Candidate> {
        val candidatesByKey = values.groupBy(::key)
        val ambiguousCandidates = candidatesByKey.filterValues { it.size > 1 }
        if (ambiguousCandidates.isNotEmpty()) {
            throw IllegalStateException(
                "Cannot resolve duplicate exact Java method declarations: " +
                    ambiguousCandidates.keys.joinToString { method ->
                        "${method.ownerQualified}.${method.methodName}${method.parameterTypes}"
                    }
            )
        }
        return candidatesByKey.mapValues { it.value.single() }
    }

    fun argumentsMatchProviderTarget(
        call: MethodCallExpr,
        parameterTypes: List<String>
    ): Boolean {
        if (javaProjectTypeIndex.argumentsMatchTypes(call, parameterTypes)) {
            return true
        }
        if (parameterTypes != listOf("net.minecraft.nbt.CompoundTag") ||
            call.arguments.size != 1
        ) {
            return false
        }
        val argument = call.arguments.single()
        if (argument is NameExpr) {
            val lambda = call.findAncestor(LambdaExpr::class.java).orElse(null)
                ?: return false
            if (lambda.parameters.none { it.nameAsString == argument.nameAsString }) {
                return false
            }
            val callback = lambda.findAncestor(MethodCallExpr::class.java).orElse(null)
                ?: return false
            return callback.nameAsString in setOf(
                "readCompoundList",
                "iterateCompoundList",
                "writeCompoundList"
            )
        }
        val copyTag = argument as? MethodCallExpr ?: return false
        if (copyTag.nameAsString != "copyTag" ||
            copyTag.arguments.isNotEmpty()
        ) {
            return false
        }
        val customData = copyTag.scope.orElse(null) as? MethodCallExpr
            ?: return false
        if (customData.nameAsString != "getOrDefault" ||
            customData.arguments.size != 2
        ) {
            return false
        }
        val stack = customData.scope.orElse(null) ?: return false
        if (!javaProjectTypeIndex.isExpressionAssignableTo(
                stack,
                customData,
                "net.minecraft.world.item.ItemStack"
            )
        ) {
            return false
        }
        val unit = call.findCompilationUnit().orElse(null) ?: return false
        fun exactStaticField(
            expression: String,
            simpleOwner: String,
            qualifiedOwner: String,
            field: String
        ): Boolean {
            val compact = expression.replace(Regex("""\s+"""), "")
            if (compact == "$qualifiedOwner.$field") return true
            if (compact != "$simpleOwner.$field") return false
            return unit.imports.any {
                !it.isStatic && !it.isAsterisk &&
                    it.nameAsString == qualifiedOwner
            }
        }
        return exactStaticField(
            customData.arguments[0].toString(),
            "DataComponents",
            "net.minecraft.core.component.DataComponents",
            "CUSTOM_DATA"
        ) && exactStaticField(
            customData.arguments[1].toString(),
            "CustomData",
            "net.minecraft.world.item.component.CustomData",
            "EMPTY"
        )
    }

    fun exactCallsByCaller(
        candidateByKey: Map<CandidateKey, Candidate>
    ): Map<CandidateKey, Set<CandidateKey>> {
        val candidatesByName = candidateByKey.entries.groupBy { it.key.methodName }
        return candidateByKey.mapValues { (caller, candidate) ->
            val lineOffsets = buildList {
                add(0)
                candidate.source.forEachIndexed { index, character ->
                    if (character == '\n') add(index + 1)
                }
            }
            val methodCalls = candidate.declaration.findAll(MethodCallExpr::class.java)
                .mapNotNull calls@{ call ->
                    if (call.findAncestor(MethodDeclaration::class.java)
                            .orElse(null) !== candidate.declaration
                    ) {
                        return@calls null
                    }
                    val receiverType = javaProjectTypeIndex.methodCallReceiverType(call)
                        ?: return@calls null
                    val matches = candidatesByName[call.nameAsString].orEmpty()
                        .filter { (target, targetCandidate) ->
                            target.parameterTypes.size == call.arguments.size &&
                                targetCandidate.parameterTypes != null &&
                                javaProjectTypeIndex.isTypeAssignableTo(
                                    receiverType,
                                    targetCandidate.ownerQualified
                                ) &&
                                argumentsMatchProviderTarget(
                                    call,
                                    targetCandidate.parameterTypes
                                )
                        }
                        .mapTo(linkedSetOf()) { it.key }
                    val mostSpecificMatches = matches.filterTo(linkedSetOf()) { target ->
                        matches.none { other ->
                            other != target &&
                                javaProjectTypeIndex.isTypeAssignableTo(
                                    other.ownerQualified,
                                    target.ownerQualified
                                )
                        }
                    }
                    if (mostSpecificMatches.size > 1) {
                        throw IllegalStateException(
                            "Ambiguous exact provider call target '$call' in " +
                                "${candidate.ownerQualified}.${candidate.methodName}: " +
                                mostSpecificMatches
                        )
                    }
                    val target = mostSpecificMatches.singleOrNull()
                        ?: return@calls null
                    val begin = call.range.orElse(null)?.begin
                        ?: return@calls target
                    val offset = lineOffsets.getOrNull(begin.line - 1)
                        ?.plus(begin.column - 1)
                        ?: return@calls target
                    if (strictRegistryAccessExpressionAt(
                            candidate.source,
                            offset,
                            javaInheritanceIndex
                        ) != null
                    ) {
                        return@calls null
                    }
                    target
                }
                .toSet()
            val methodReferences = candidate.declaration
                .findAll(MethodReferenceExpr::class.java)
                .mapNotNull references@{ reference ->
                    if (reference.findAncestor(MethodDeclaration::class.java)
                            .orElse(null) !== candidate.declaration
                    ) {
                        return@references null
                    }
                    val receiverType = javaProjectTypeIndex.expressionType(
                        reference.scope,
                        reference
                    ) ?: return@references null
                    var matches = candidatesByName[reference.identifier]
                        .orEmpty()
                        .filter { (_, targetCandidate) ->
                            targetCandidate.parameterTypes != null &&
                                javaProjectTypeIndex.isTypeAssignableTo(
                                    receiverType,
                                    targetCandidate.ownerQualified
                                )
                        }
                    val enclosingCall = reference.findAncestor(
                        MethodCallExpr::class.java
                    ).orElse(null)
                    if (enclosingCall?.nameAsString in setOf(
                            "readCompoundList",
                            "iterateCompoundList"
                        )
                    ) {
                        matches = matches.filter { (_, targetCandidate) ->
                            targetCandidate.parameterTypes?.firstOrNull() ==
                                "net.minecraft.nbt.CompoundTag"
                        }
                    } else if (enclosingCall?.nameAsString == "writeCompoundList") {
                        matches = matches.filter { (_, targetCandidate) ->
                            targetCandidate.parameterTypes?.isEmpty() == true
                        }
                    }
                    val mostSpecific = matches.filter { (_, target) ->
                        matches.none { (_, other) ->
                            other !== target &&
                                javaProjectTypeIndex.isTypeAssignableTo(
                                    other.ownerQualified,
                                    target.ownerQualified
                                )
                        }
                    }
                    mostSpecific.singleOrNull()?.key
                }
                .toSet()
            (methodCalls + methodReferences) - caller
        }
    }

    val initialCandidateByKey = exactCandidateMap(candidates)
    val preliminaryCallsByCaller = exactCallsByCaller(initialCandidateByKey)
    val preliminaryCallersByCallee =
        linkedMapOf<CandidateKey, MutableSet<CandidateKey>>()
    preliminaryCallsByCaller.forEach { (caller, callees) ->
        callees.forEach { callee ->
            preliminaryCallersByCallee
                .getOrPut(callee) { linkedSetOf() }
                .add(caller)
        }
    }
    val familySeedKeys = candidates
        .filter { it.directProviderDemand }
        .mapTo(linkedSetOf(), ::key)
    val familySeedQueue = ArrayDeque(familySeedKeys)
    while (familySeedQueue.isNotEmpty()) {
        val demandedMethod = familySeedQueue.removeFirst()
        preliminaryCallersByCallee[demandedMethod].orEmpty().forEach { caller ->
            if (familySeedKeys.add(caller)) familySeedQueue.addLast(caller)
        }
    }

    val overrideFamilyByDeclaration = java.util.IdentityHashMap<
        MethodDeclaration,
        List<JavaProjectTypeIndex.ExactProjectMethod>
        >()
    javaProjectTypeIndex.exactProjectOverrideFamilies(
        familySeedKeys.map { seed -> initialCandidateByKey.getValue(seed).declaration }
    ).forEach { family ->
        family.forEach { member ->
            overrideFamilyByDeclaration[member.method] = family
        }
    }
    familySeedKeys.forEach { seed ->
        val candidate = initialCandidateByKey.getValue(seed)
        val family = overrideFamilyByDeclaration[candidate.declaration]
            ?: return@forEach
        family.forEach { member ->
            if (candidates.none { existing -> existing.declaration === member.method }) {
                candidates += candidateForOverrideMember(member)
            }
        }
    }
    directCandidateOwners += candidates.map { it.owner }

    val overloadedLegacyShapes = candidates
        .groupBy { Triple(it.ownerQualified, it.methodName, it.parameterCount) }
        .filterValues { overloads ->
            overloads.map { it.parameterTypes }.distinct().size > 1
        }
        .keys
    overloadedNbtMethods = candidates
        .filter { candidate ->
            Triple(
                candidate.ownerQualified,
                candidate.methodName,
                candidate.parameterCount
            ) in overloadedLegacyShapes
        }
        .mapTo(linkedSetOf()) { it.owner to it.methodName }
    overloadedNbtFirstParameterTypes = candidates
        .filter { candidate ->
            Triple(
                candidate.ownerQualified,
                candidate.methodName,
                candidate.parameterCount
            ) in overloadedLegacyShapes
        }
        .groupBy { it.owner to it.methodName }
        .mapValues { (_, overloads) ->
            overloads.mapNotNullTo(linkedSetOf()) { candidate ->
                candidate.parameterTypes
                    ?.firstOrNull()
                    ?.substringAfterLast('.')
            }
        }

    fun contract(candidate: Candidate): HolderLookupNbtMethod {
        val tagIndexes = candidate.parameterTypes.orEmpty()
            .mapIndexedNotNull { index, type ->
                index.takeIf { type == "net.minecraft.nbt.CompoundTag" }
            }
        if (tagIndexes.size > 1) {
            throw IllegalStateException(
                "Cannot choose a HolderLookup.Provider position for " +
                    "${candidate.owner}.${candidate.methodName}: multiple CompoundTag parameters"
            )
        }
        val providerIndex = candidate.providerIndexHint ?: candidate.parameterCount
        return HolderLookupNbtMethod(
            candidate.owner,
            candidate.methodName,
            candidate.parameterCount,
            providerIndex,
            candidate.parameterTypes,
            Triple(
                candidate.ownerQualified,
                candidate.methodName,
                candidate.parameterCount
            ) in overloadedLegacyShapes
        )
    }

    val candidateByKey = exactCandidateMap(candidates)
    val overridePeersByMember =
        linkedMapOf<CandidateKey, MutableSet<CandidateKey>>()
    val indexedOverrideFamilies = linkedSetOf<String>()
    val indexedOverrideDeclarations = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<MethodDeclaration, Boolean>()
    )
    candidateByKey.forEach { (_, candidate) ->
        if (!indexedOverrideDeclarations.add(candidate.declaration)) return@forEach
        val family = overrideFamilyByDeclaration[candidate.declaration]
            ?: return@forEach
        family.forEach { member ->
            indexedOverrideDeclarations.add(member.method)
        }
        val familyKey = family.joinToString("|") { member ->
            "${member.owner}.${member.method.nameAsString}/${member.method.parameters.size}"
        }
        if (!indexedOverrideFamilies.add(familyKey)) return@forEach
        val members = family.map { member ->
            val parameterTypes = member.method.parameters.map { parameter ->
                javaProjectTypeIndex.declaredType(parameter.type, member.method)
                    ?: throw IllegalStateException(
                        "Cannot resolve exact override-family parameter type for " +
                            "${member.owner}.${member.method.nameAsString}"
                    )
            }
            CandidateKey(
                member.owner,
                member.method.nameAsString,
                parameterTypes
            )
        }.toSet()
        val missing = members - candidateByKey.keys
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Provider-demanded override family is not represented atomically: $missing"
            )
        }
        members.forEach { member ->
            overridePeersByMember
                .getOrPut(member) { linkedSetOf() }
                .addAll(members - member)
        }
    }

    val callsByCaller = exactCallsByCaller(candidateByKey)
    val callersByCallee = linkedMapOf<CandidateKey, MutableSet<CandidateKey>>()
    callsByCaller.forEach { (caller, callees) ->
        callees.forEach { callee ->
            callersByCallee.getOrPut(callee) { linkedSetOf() }.add(caller)
        }
    }

    val candidateKeyByDeclaration = java.util.IdentityHashMap<
        MethodDeclaration,
        CandidateKey
        >()
    candidateByKey.forEach { (method, candidate) ->
        candidateKeyByDeclaration[candidate.declaration] = method
    }
    val candidatesByName = candidateByKey.entries.groupBy { it.key.methodName }

    fun exactCallTarget(call: MethodCallExpr): CandidateKey? {
        val receiverType = javaProjectTypeIndex.methodCallReceiverType(call) ?: return null
        val matches = candidatesByName[call.nameAsString].orEmpty()
            .filter { (target, targetCandidate) ->
                target.parameterTypes.size == call.arguments.size &&
                    targetCandidate.parameterTypes != null &&
                    javaProjectTypeIndex.isTypeAssignableTo(
                        receiverType,
                        targetCandidate.ownerQualified
                    ) &&
                    argumentsMatchProviderTarget(
                        call,
                        targetCandidate.parameterTypes
                    )
            }
            .mapTo(linkedSetOf()) { it.key }
        val mostSpecific = matches.filterTo(linkedSetOf()) { target ->
            matches.none { other ->
                other != target &&
                    javaProjectTypeIndex.isTypeAssignableTo(
                        other.ownerQualified,
                        target.ownerQualified
                    )
            }
        }
        if (mostSpecific.size > 1) {
            throw IllegalStateException(
                "Ambiguous exact provider call target '$call': $mostSpecific"
            )
        }
        return mostSpecific.singleOrNull()
    }

    val unrootedExternalCallTargets = linkedSetOf<CandidateKey>()
    javaFiles.forEach { javaFile ->
        val source = sourcesByFile.getValue(javaFile)
        val lineOffsets = buildList {
            add(0)
            source.forEachIndexed { index, character ->
                if (character == '\n') add(index + 1)
            }
        }
        javaProjectTypeIndex.unit(javaFile)
            .findAll(MethodCallExpr::class.java)
            .forEach calls@{ call ->
                val target = exactCallTarget(call) ?: return@calls
                val callerMethod = call.findAncestor(MethodDeclaration::class.java)
                    .orElse(null)
                if (callerMethod != null &&
                    candidateKeyByDeclaration.containsKey(callerMethod)
                ) {
                    return@calls
                }
                val begin = call.range.orElse(null)?.begin ?: return@calls
                val offset = lineOffsets.getOrNull(begin.line - 1)
                    ?.plus(begin.column - 1)
                    ?: return@calls
                val rootedMethod = callerMethod?.let { method ->
                    method.parameters.any { parameter ->
                        javaProjectTypeIndex.declaredType(parameter.type, method) ==
                            "net.minecraft.core.HolderLookup.Provider"
                    } ||
                        ExactExternalProviderContracts.providerExpression(
                            method,
                            javaProjectTypeIndex
                        ) != null
                } == true
                val callerConstructor = call.findAncestor(
                    ConstructorDeclaration::class.java
                ).orElse(null)
                val callerConstructorKey = callerConstructor?.let { constructor ->
                    val owner = exactEnclosingNamedClass(constructor)
                        ?.fullyQualifiedName
                        ?.orElse(null)
                        ?: return@let null
                    val parameterTypes = constructor.parameters.map { parameter ->
                        javaProjectTypeIndex.declaredType(parameter.type, constructor)
                            ?: return@let null
                    }
                    ConstructorKey(owner, parameterTypes)
                }
                val rootedConstructor = callerConstructor != null &&
                    (
                        holderLookupProviderNamesAt(source, offset).size == 1 ||
                            callerConstructorKey in providerDemandingConstructors
                        )
                val rootedSource =
                    strictRegistryAccessExpressionAt(
                        source,
                        offset,
                        javaInheritanceIndex
                    ) != null
                if (!rootedMethod && !rootedConstructor && !rootedSource) {
                    unrootedExternalCallTargets += target
                }
            }
    }

    fun hasExactDeclaredProviderRoot(candidate: Candidate): Boolean {
        if (ExactExternalProviderContracts.providerExpression(
                candidate.declaration,
                javaProjectTypeIndex
            ) != null
        ) {
            return true
        }
        val providerParameters = candidate.declaration.parameters.mapNotNull { parameter ->
            val nullable = parameter.annotations.any {
                it.name.identifier == "Nullable"
            } || candidate.declaration.findAll(BinaryExpr::class.java).any { expression ->
                val leftName = (expression.left as? NameExpr)?.nameAsString
                val rightName = (expression.right as? NameExpr)?.nameAsString
                val comparesNull =
                    expression.left is NullLiteralExpr ||
                        expression.right is NullLiteralExpr
                comparesNull &&
                    (
                        leftName == parameter.nameAsString ||
                            rightName == parameter.nameAsString
                        )
            }
            if (nullable) return@mapNotNull null
            val type = javaProjectTypeIndex.declaredType(
                parameter.type,
                candidate.declaration
            ) ?: return@mapNotNull null
            parameter.nameAsString.takeIf {
                type in setOf(
                    "net.minecraft.core.HolderLookup.Provider",
                    "net.minecraft.core.RegistryAccess"
                ) ||
                    setOf(
                        "net.minecraft.world.level.Level",
                        "net.minecraft.world.level.LevelAccessor",
                        "net.minecraft.world.entity.Entity"
                    ).any { expected ->
                        javaProjectTypeIndex.isTypeAssignableTo(type, expected)
                    }
            }
        }.distinct()
        return providerParameters.size == 1
    }

    val declaredProviderRoots = candidateByKey
        .filterValues(::hasExactDeclaredProviderRoot)
        .keys
    val closedDeclaredProviderRoots = declaredProviderRoots.filterTo(
        linkedSetOf()
    ) { method ->
        overridePeersByMember[method].orEmpty().all { peer ->
            peer in declaredProviderRoots
        }
    }
    fun sourceProvesProviderAtOffsets(candidate: Candidate, offsets: Collection<Int>): Boolean =
        offsets.isNotEmpty() && offsets.distinct().all { offset ->
            strictRegistryAccessExpressionAt(
                candidate.source,
                offset,
                javaInheritanceIndex
            ) != null
        }

    fun hasSourceProvenProviderForDirectDemands(candidate: Candidate): Boolean =
        sourceProvesProviderAtOffsets(candidate, candidate.directProviderDemandOffsets)

    val demanded = candidates.filter { candidate ->
        candidate.directProviderDemand &&
            !hasSourceProvenProviderForDirectDemands(candidate) &&
            key(candidate) !in closedDeclaredProviderRoots &&
            (
                strictRegistryAccessExpressionAt(
                    candidate.source,
                    candidate.range.first,
                    javaInheritanceIndex
                ) == null ||
                    hasExactProviderAwareProjectCallSite(candidate)
                )
    }.mapTo(linkedSetOf(), ::key)

    fun sourceProvesProviderForCallsTo(
        method: CandidateKey,
        targets: Set<CandidateKey>
    ): Boolean {
        val candidate = candidateByKey.getValue(method)
        val lineOffsets = buildList {
            add(0)
            candidate.source.forEachIndexed { index, character ->
                if (character == '\n') add(index + 1)
            }
        }
        val callOffsets = candidate.declaration.findAll(MethodCallExpr::class.java).mapNotNull { call ->
            if (call.findAncestor(MethodDeclaration::class.java).orElse(null) !== candidate.declaration ||
                exactCallTarget(call) !in targets
            ) {
                return@mapNotNull null
            }
            val begin = call.range.orElse(null)?.begin ?: return@mapNotNull null
            lineOffsets.getOrNull(begin.line - 1)?.plus(begin.column - 1)
        }
        return sourceProvesProviderAtOffsets(candidate, callOffsets)
    }

    val contractForwardingQueue = ArrayDeque(
        demanded.filter { method ->
            val candidate = candidateByKey.getValue(method)
            candidate.contractForwardingDemand &&
                javaProjectTypeIndex.isTypeAssignableTo(
                    candidate.ownerQualified,
                    "net.minecraft.world.level.block.entity.BlockEntity"
                )
        }
    )
    val contractForwardingVisited = linkedSetOf<CandidateKey>()
    while (contractForwardingQueue.isNotEmpty()) {
        val callerKey = contractForwardingQueue.removeFirst()
        if (!contractForwardingVisited.add(callerKey)) continue
        val caller = candidateByKey.getValue(callerKey)
        val callerTagParameters = caller.declaration.parameters
            .filter { parameter ->
                javaProjectTypeIndex.declaredType(
                    parameter.type,
                    caller.declaration
                ) == "net.minecraft.nbt.CompoundTag"
            }
            .mapTo(linkedSetOf()) { it.nameAsString }
        if (callerTagParameters.size != 1) continue
        val callerTagName = callerTagParameters.single()
        caller.declaration.findAll(MethodCallExpr::class.java)
            .forEach calls@{ call ->
                if (call.findAncestor(MethodDeclaration::class.java)
                        .orElse(null) !== caller.declaration
                ) {
                    return@calls
                }
                val targetKey = exactCallTarget(call) ?: return@calls
                if (targetKey in closedDeclaredProviderRoots) return@calls
                val target = candidateByKey.getValue(targetKey)
                val tagIndexes = target.parameterTypes.orEmpty()
                    .mapIndexedNotNull { index, type ->
                        index.takeIf {
                            type == "net.minecraft.nbt.CompoundTag"
                        }
                    }
                if (tagIndexes.size != 1) return@calls
                val tagArgument = call.arguments.getOrNull(tagIndexes.single())
                    ?: return@calls
                if (tagArgument.findAll(NameExpr::class.java)
                        .none { it.nameAsString == callerTagName }
                ) {
                    return@calls
                }
                if (!target.declaration.isPublic &&
                    !target.declaration.isProtected &&
                    overridePeersByMember[targetKey].isNullOrEmpty()
                ) {
                    return@calls
                }
                if (strictRegistryAccessExpressionAt(
                        target.source,
                        target.range.first,
                        javaInheritanceIndex
                    ) != null || hasSourceProvenProviderForDirectDemands(target)
                ) {
                    return@calls
                }
                if (demanded.add(targetKey)) {
                    contractForwardingQueue.addLast(targetKey)
                }
                overridePeersByMember[targetKey].orEmpty().forEach { peer ->
                    if (peer !in closedDeclaredProviderRoots &&
                        demanded.add(peer)
                    ) {
                        contractForwardingQueue.addLast(peer)
                    }
                }
            }
    }
    val demandQueue = ArrayDeque(demanded)
    while (demandQueue.isNotEmpty()) {
        val demandedMethod = demandQueue.removeFirst()
        overridePeersByMember[demandedMethod].orEmpty().forEach { peer ->
            if (demanded.add(peer)) demandQueue.addLast(peer)
        }
        callersByCallee[demandedMethod].orEmpty().forEach { caller ->
            if (sourceProvesProviderForCallsTo(caller, setOf(demandedMethod))) return@forEach
            if (demanded.add(caller)) demandQueue.addLast(caller)
        }
    }

    val exactProviderAwareCallSites = demanded.filterTo(linkedSetOf()) { method ->
        hasExactProviderAwareProjectCallSite(candidateByKey.getValue(method))
    }
    val explicitProviderBoundaries = demanded.filterTo(linkedSetOf()) { method ->
        val candidate = candidateByKey.getValue(method)
        val hasPotentialFixedArityAnnotation = candidate.declaration.annotations.any {
            it.nameAsString !in setOf(
                "Override",
                "Deprecated",
                "SuppressWarnings",
                "SafeVarargs",
                "Nullable",
                "Nonnull",
                "NotNull",
                "CheckForNull"
            )
        }
        (candidate.declaration.isPublic || candidate.declaration.isProtected) &&
            !hasPotentialFixedArityAnnotation &&
            javaProjectTypeIndex.isProvenNonOverride(candidate.declaration)
    }
    val reachable = demanded.filterTo(linkedSetOf()) { method ->
        val candidate = candidateByKey.getValue(method)
        isPrivateOrPackagePrivateJavaMethodHeader(candidate.header) ||
            method in explicitProviderBoundaries ||
            method in exactProviderAwareCallSites ||
            legacyINBTSerializableProviderCallSite(candidate.source, candidate.range.first) ||
            hasProviderAwareProjectCallSite(candidate) ||
            strictRegistryAccessExpressionAt(
                candidate.source,
                candidate.range.first,
                javaInheritanceIndex
            ) != null
    }
    val reachableQueue = ArrayDeque(reachable)
    while (reachableQueue.isNotEmpty()) {
        val reachableMethod = reachableQueue.removeFirst()
        overridePeersByMember[reachableMethod].orEmpty().forEach { peer ->
            if (peer in demanded && reachable.add(peer)) {
                reachableQueue.addLast(peer)
            }
        }
        callsByCaller[reachableMethod].orEmpty().forEach { callee ->
            if (callee in demanded && reachable.add(callee)) {
                reachableQueue.addLast(callee)
            }
        }
    }

    val accepted = reachable.toMutableSet()
    while (true) {
        val stranded = accepted.filterTo(linkedSetOf()) { method ->
            method in unrootedExternalCallTargets ||
                callersByCallee[method].orEmpty().any { caller ->
                    caller !in accepted
                }
        }
        if (stranded.isEmpty()) break
        stranded.forEach { method ->
            accepted.remove(method)
            overridePeersByMember[method].orEmpty().forEach { peer ->
                accepted.remove(peer)
            }
        }
    }

    val result = accepted.filterTo(linkedSetOf()) { method ->
        val candidate = candidateByKey.getValue(method)
        ExactExternalProviderContracts.providerExpression(
            candidate.declaration,
            javaProjectTypeIndex
        ) == null &&
            strictRegistryAccessExpressionAt(
                candidate.source,
                candidate.range.first,
                javaInheritanceIndex
            ) == null
    }.mapTo(linkedSetOf()) { method ->
        contract(candidateByKey.getValue(method))
    }
    val returnedKeys = result.mapTo(linkedSetOf()) { contract ->
        Triple(contract.owner, contract.methodName, contract.legacyParameterTypes)
    }
    staticHolderLookupNbtMethods = candidates
        .filter { candidate ->
            candidate.declaration.isStatic &&
                Triple(
                    candidate.owner,
                    candidate.methodName,
                    candidate.parameterTypes
                ) in returnedKeys
        }
        .mapTo(linkedSetOf()) { it.owner to it.methodName }
    return result
}
