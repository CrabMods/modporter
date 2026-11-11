package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.CharLiteralExpr
import com.github.javaparser.ast.expr.DoubleLiteralExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LongLiteralExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.PatternExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.BreakStmt
import com.github.javaparser.ast.stmt.ContinueStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.type.Type
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

internal fun exactEnclosingNamedClass(node: Node): TypeDeclaration<*>? {
    var child: Node? = null
    var cursor: Node? = node
    while (cursor != null) {
        when (cursor) {
            is ObjectCreationExpr -> if (cursor.anonymousClassBody.isPresent) return null
            is EnumConstantDeclaration -> if (cursor.classBody.any { it === child }) return null
            is TypeDeclaration<*> -> return cursor
        }
        child = cursor
        cursor = cursor.parentNode.orElse(null)
    }
    return null
}

/** Lazily resolves project-source types from explicit Java structure, without heuristic fallback. */
internal class JavaProjectTypeIndex private constructor(private val sourceRoot: Path) {
    sealed interface ExactFieldQuery {
        data object None : ExactFieldQuery
        data class Unique(val field: Pair<String, String>) : ExactFieldQuery
        data class Ambiguous(
            val ownerType: String,
            val fields: List<Pair<String, String>>
        ) : ExactFieldQuery
    }

    data class ExactProjectMethod(
        val owner: String,
        val file: Path,
        val method: MethodDeclaration
    )

    data class ExactLambdaCallbackBinding(
        val call: MethodCallExpr,
        val owner: String,
        val method: MethodDeclaration,
        val callbackArgumentIndex: Int,
        val lambdaParameterIndex: Int
    )

    private data class TypeRef(
        val name: String,
        val arguments: List<TypeRef> = emptyList(),
        val arrayDepth: Int = 0
    )

    private sealed interface ExactExternalFunctionalParameterSource {
        data class ReceiverTypeArgument(val index: Int) : ExactExternalFunctionalParameterSource
        data class IterableCallArgument(
            val argumentIndex: Int,
            val allowPureIterable: Boolean = true
        ) : ExactExternalFunctionalParameterSource
        data class ExactType(val name: String) : ExactExternalFunctionalParameterSource
    }

    private data class ExactExternalFunctionalParameterContract(
        val owner: String,
        val methodName: String,
        val methodArity: Int,
        val callbackArgumentIndex: Int,
        val functionalArity: Int,
        val lambdaParameterIndex: Int,
        val parameterSource: ExactExternalFunctionalParameterSource
    )

    private sealed interface ResolvedArgumentValue {
        data class Typed(val type: TypeRef) : ResolvedArgumentValue
        data object NullLiteral : ResolvedArgumentValue
    }

    private data class ProjectMethodBinding(
        val owner: String,
        val declaration: MethodDeclaration
    )

    private data class ProjectFunctionalParameterBinding(
        val owner: String,
        val declaration: MethodDeclaration,
        val parameterType: TypeRef
    )

    private enum class MethodOwnerKind {
        CLASS,
        INTERFACE
    }

    private sealed interface ProjectDispatchResolution<out T> {
        data class Resolved<T>(
            val value: T,
            val ownerKind: MethodOwnerKind
        ) : ProjectDispatchResolution<T>

        data object Absent : ProjectDispatchResolution<Nothing>

        data class Unknown(
            val mayDeclareConcreteClassMethod: Boolean
        ) : ProjectDispatchResolution<Nothing>
    }

    private data class DirectSuperBranch(
        val raw: TypeRef,
        val ownerKind: MethodOwnerKind
    )

    private sealed interface MemberTypeLookup {
        data class Resolved(val qualifiedName: String) : MemberTypeLookup
        data object Absent : MemberTypeLookup
        data object Unknown : MemberTypeLookup
    }

    private enum class DeclarationErasureScope {
        CALLABLE,
        OWNER_HEADER
    }

    private sealed interface DirectSuperLookup {
        data class Project(val info: TypeInfo) : DirectSuperLookup
        data class External(val qualifiedName: String) : DirectSuperLookup
        data object Unknown : DirectSuperLookup
    }

    private data class TypeInfo(
        val qualifiedName: String,
        val unit: CompilationUnit,
        val declaration: TypeDeclaration<*>,
        val typeParameters: List<String>,
        val typeParameterBounds: Map<String, TypeRef>,
        val directSupers: List<TypeRef>
    )

    private data class FunctionalMethodSignature(
        val name: String,
        val parameterTypes: List<TypeRef>,
        val returnType: TypeRef
    )

    private data class ExactMethodSignature(
        val name: String,
        val parameterTypes: List<String>
    )

    private fun methods(info: TypeInfo): List<MethodDeclaration> =
        info.declaration.members.filterIsInstance<MethodDeclaration>()

    private fun fields(info: TypeInfo): List<FieldDeclaration> =
        info.declaration.members.filterIsInstance<FieldDeclaration>()

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    private val unitsByFile = linkedMapOf<Path, CompilationUnit>()
    private val typesByQualifiedName = linkedMapOf<String, TypeInfo>()
    private val inheritedMemberTypeCache = linkedMapOf<Pair<String, String>, MemberTypeLookup>()

    internal val loadedSourceCount: Int
        get() = unitsByFile.size

    fun unit(file: Path): CompilationUnit = parseFile(file)

    fun release(file: Path) {
        val normalized = file.toAbsolutePath().normalize()
        val unit = unitsByFile.remove(normalized) ?: return
        typesByQualifiedName.entries.removeIf { (_, info) -> info.unit === unit }
        inheritedMemberTypeCache.clear()
    }

    fun expressionType(expression: Expression, use: Node): String? {
        val owner = exactEnclosingNamedClass(use) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index expression owner '${owner.nameAsString}'")
        val resolved = resolveExpression(expression, use, ownerInfo, emptyMap()) ?: return null
        return resolved.name.takeIf { resolved.arrayDepth == 0 }
    }

    fun expressionRawType(expression: Expression, use: Node): String? {
        expressionType(expression, use)?.let { return it }
        val call = expression as? MethodCallExpr ?: return null
        val ownerName = projectMethodOwner(call) ?: return null
        val owner = loadType(ownerName) ?: return null
        val candidates = methods(owner).filter { method ->
            method.nameAsString == call.nameAsString && method.parameters.size == call.arguments.size
        }
        if (candidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous project method ${owner.qualifiedName}.${call.nameAsString}/${call.arguments.size}"
            )
        }
        val method = candidates.singleOrNull() ?: return null
        val raw = parseType(method.typeAsString).copy(arguments = emptyList())
        return resolveType(raw, owner, emptyMap())?.name
    }

    fun isExpressionAssignableTo(expression: Expression, use: Node, expectedType: String): Boolean {
        val owner = exactEnclosingNamedClass(use) ?: return false
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index expression owner '${owner.nameAsString}'")
        val actual = resolveExpression(expression, use, ownerInfo, emptyMap()) ?: return false
        return isAssignableTo(actual, expectedType, mutableSetOf())
    }

    fun declaredType(type: Type, use: Node): String? {
        val owner = exactEnclosingNamedClass(use) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index type owner '${owner.nameAsString}'")
        val resolved = resolveLexicalType(parseType(type.asString()), use, ownerInfo, emptyMap()) ?: return null
        return resolved.name.takeIf { resolved.arrayDepth == 0 }
    }

    fun declaredRawType(type: Type, use: Node): String? {
        val owner = exactEnclosingNamedClass(use) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index type owner '${owner.nameAsString}'")
        val raw = parseType(type.asString()).copy(arguments = emptyList())
        val resolved = resolveLexicalType(raw, use, ownerInfo, emptyMap()) ?: return null
        return resolved.name.takeIf { resolved.arrayDepth == 0 }
    }

    fun erasedDeclarationParameterTypes(callable: CallableDeclaration<*>): List<String>? {
        val owner = exactEnclosingNamedClass(callable) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index callable owner '${owner.nameAsString}'")
        val erased = mutableListOf<String>()
        callable.parameters.forEach { parameter ->
            val resolved = eraseDeclarationType(parameterTypeRef(parameter), callable, ownerInfo) ?: return null
            erased += resolved.name + "[]".repeat(resolved.arrayDepth)
        }
        return erased
    }

    fun declaredTypeWithArguments(type: Type, use: Node): Pair<String, List<String>>? {
        val owner = exactEnclosingNamedClass(use) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index type owner '${owner.nameAsString}'")
        val resolved = resolveLexicalType(parseType(type.asString()), use, ownerInfo, emptyMap()) ?: return null
        if (resolved.arrayDepth != 0 || resolved.arguments.any { it.arrayDepth != 0 }) return null
        return resolved.name to resolved.arguments.map { it.name }
    }

    fun isExactInstanceFieldReference(reference: NameExpr): Boolean {
        val owner = exactEnclosingNamedClass(reference) ?: return false
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index expression owner '${owner.nameAsString}'")
        if (hasVisibleLexicalValueBinding(reference.nameAsString, reference)) return false
        if (isTypeAssignableTo(ownerInfo.qualifiedName, "net.minecraft.world.level.block.entity.BlockEntity") &&
            reference.nameAsString in setOf("level", "worldPosition")
        ) {
            return true
        }
        return exactFieldStaticness(
            TypeRef(ownerInfo.qualifiedName),
            reference.nameAsString,
            mutableSetOf()
        ) == false
    }

    fun isExactProjectMethodArgumentUnused(call: MethodCallExpr, argumentIndex: Int): Boolean {
        if (argumentIndex !in call.arguments.indices) return false
        val ownerName = projectMethodOwner(call) ?: return false
        val owner = loadType(ownerName) ?: return false
        val candidates = methods(owner).filter { method ->
            method.nameAsString == call.nameAsString && method.parameters.size == call.arguments.size
        }
        if (candidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous project method ${owner.qualifiedName}.${call.nameAsString}/${call.arguments.size}"
            )
        }
        val method = candidates.singleOrNull() ?: return false
        val parameterName = method.parameters[argumentIndex].nameAsString
        return method.body.orElse(null)
            ?.findAll(NameExpr::class.java)
            ?.none { it.nameAsString == parameterName }
            ?: false
    }

    fun isExactInstanceMethodCall(call: MethodCallExpr): Boolean {
        if (call.scope.isPresent) return false
        val ownerDeclaration = exactEnclosingNamedClass(call) ?: return false
        val owner = typeInfo(ownerDeclaration)
            ?: throw IllegalStateException("Cannot index call owner '${ownerDeclaration.nameAsString}'")
        if (isTypeAssignableTo(owner.qualifiedName, "net.minecraft.world.level.block.entity.BlockEntity") &&
            (call.nameAsString to call.arguments.size) in setOf("getBlockState" to 0, "isRemoved" to 0)
        ) {
            return true
        }
        val methodOwner = projectMethodOwner(call) ?: return false
        val info = loadType(methodOwner) ?: return false
        val candidates = methods(info).filter { method ->
            method.nameAsString == call.nameAsString && method.parameters.size == call.arguments.size
        }
        if (candidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous project method ${info.qualifiedName}.${call.nameAsString}/${call.arguments.size}"
            )
        }
        return candidates.singleOrNull()?.isStatic == false
    }

    fun projectMethodOwner(call: MethodCallExpr, declarationArity: Int = call.arguments.size): String? {
        val receiver = methodCallReceiverTypeRef(call) ?: return null
        return resolveProjectMethodOwner(receiver, call.nameAsString, declarationArity, mutableSetOf())
    }

    fun exactMethodCallReceiverType(call: MethodCallExpr): String? =
        methodCallReceiverTypeRef(call)?.name

    fun exactProjectMethodDeclaration(call: MethodCallExpr): MethodDeclaration? {
        val caller = exactEnclosingNamedClass(call) ?: return null
        val callerInfo = typeInfo(caller)
            ?: throw IllegalStateException("Cannot index call owner '${caller.nameAsString}'")
        val receiver = methodCallReceiverTypeRef(call) ?: return null
        return resolveProjectMethodDeclaration(
            receiver,
            call,
            callerInfo,
            emptyMap(),
            emptySet()
        )?.declaration
    }

    fun exactLambdaCallbackBinding(
        lambda: LambdaExpr,
        lambdaParameterIndex: Int
    ): ExactLambdaCallbackBinding? {
        if (lambdaParameterIndex !in lambda.parameters.indices) return null
        val call = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return null
        val argumentIndex = call.arguments.indexOfFirst { it === lambda }
        if (argumentIndex < 0) return null
        val caller = exactEnclosingNamedClass(call) ?: return null
        val callerInfo = typeInfo(caller)
            ?: throw IllegalStateException("Cannot index call owner '${caller.nameAsString}'")
        val scopeExpression = call.scope.orElse(null)
        val receiver = if (scopeExpression == null) {
            TypeRef(callerInfo.qualifiedName)
        } else {
            resolveExpression(scopeExpression, call, callerInfo, emptyMap())
                ?: resolveTypeScope(scopeExpression, callerInfo, emptyMap())
                ?: return null
        }
        val binding = resolveProjectFunctionalParameterBinding(
            receiver,
            call,
            argumentIndex,
            lambdaParameterIndex,
            callerInfo,
            emptyMap(),
            emptySet()
        ) ?: return null
        return ExactLambdaCallbackBinding(
            call,
            binding.owner,
            binding.declaration,
            argumentIndex,
            lambdaParameterIndex
        )
    }

    fun projectMethodTypeParameterCount(call: MethodCallExpr): Int? {
        val ownerName = projectMethodOwner(call) ?: return null
        val owner = loadType(ownerName) ?: return null
        val candidates = methods(owner).filter { method ->
            method.nameAsString == call.nameAsString && method.parameters.size == call.arguments.size
        }
        if (candidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous project method ${owner.qualifiedName}.${call.nameAsString}/${call.arguments.size}"
            )
        }
        return candidates.singleOrNull()?.typeParameters?.size
    }

    fun methodCallReceiverType(call: MethodCallExpr): String? = methodCallReceiverTypeRef(call)?.name

    fun isTypeAssignableTo(actualType: String, expectedType: String): Boolean =
        isAssignableTo(TypeRef(actualType), expectedType, mutableSetOf())

    fun concreteClassMethodSuppressesInterfaceMethod(
        receiverType: String,
        implementation: MethodDeclaration,
        contract: MethodDeclaration
    ): Boolean {
        if (!implementation.isPublic || implementation.isStatic || implementation.isAbstract ||
            contract.isStatic || contract.isPrivate ||
            implementation.nameAsString != contract.nameAsString
        ) return false
        val classOwner = exactEnclosingNamedClass(implementation) ?: return false
        val interfaceOwner = exactEnclosingNamedClass(contract) as? ClassOrInterfaceDeclaration
            ?: return false
        if (!interfaceOwner.isInterface ||
            (classOwner is ClassOrInterfaceDeclaration && classOwner.isInterface)
        ) return false
        val implementationSignature = erasedDeclarationParameterTypes(implementation) ?: return false
        if (implementationSignature != erasedDeclarationParameterTypes(contract)) return false
        val className = typeInfo(classOwner)?.qualifiedName ?: return false
        val interfaceName = typeInfo(interfaceOwner)?.qualifiedName ?: return false
        // JLS 8.4.8: a concrete superclass implementation prevents inheritance of
        // the override-equivalent interface declaration for this receiver.
        return isTypeAssignableTo(receiverType, className) &&
            isTypeAssignableTo(receiverType, interfaceName)
    }

    fun exactProjectOverrideFamily(
        seed: MethodDeclaration,
        rootVerifier: ((MethodDeclaration) -> Boolean)? = null
    ): List<ExactProjectMethod>? = exactProjectOverrideFamilies(listOf(seed), rootVerifier).singleOrNull()

    fun exactProjectOverrideFamilies(
        seeds: Collection<MethodDeclaration>,
        rootVerifier: ((MethodDeclaration) -> Boolean)? = null
    ): List<List<ExactProjectMethod>> {
        val seedsBySignature = seeds.mapNotNull { seed ->
            if (seed.isStatic) return@mapNotNull null
            val parameterTypes = seed.parameters.map { parameter ->
                declaredType(parameter.type, seed) ?: return@mapNotNull null
            }
            ExactMethodSignature(seed.nameAsString, parameterTypes) to seed
        }.groupBy({ it.first }, { it.second })
        if (seedsBySignature.isEmpty()) return emptyList()

        val seedNames = seedsBySignature.keys.mapTo(linkedSetOf()) { it.name }
        val callableToken = Regex("""\b([A-Za-z_$][\w$]*)\s*\(""")
        Files.walk(sourceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .filter { file ->
                    runCatching {
                        callableToken.findAll(file.readText()).any { match -> match.groupValues[1] in seedNames }
                    }.getOrDefault(false)
                }
                .forEach(::parseFile)
        }

        val candidateMethods = mutableListOf<Pair<ExactMethodSignature, ExactProjectMethod>>()
        val visitedOwnerNames = linkedSetOf<String>()
        while (true) {
            val batch = typesByQualifiedName.entries
                .filter { (qualifiedName, _) -> qualifiedName !in visitedOwnerNames }
                .map { (qualifiedName, info) -> qualifiedName to info }
            if (batch.isEmpty()) break
            visitedOwnerNames += batch.map { (qualifiedName, _) -> qualifiedName }
            batch.forEach { (_, info) ->
                methods(info).forEach methodLoop@{ method ->
                    if (method.isStatic || method.nameAsString !in seedNames) return@methodLoop
                    val parameterTypes = method.parameters.map { parameter ->
                        declaredType(parameter.type, method) ?: return@methodLoop
                    }
                    val signature = ExactMethodSignature(method.nameAsString, parameterTypes)
                    if (signature !in seedsBySignature) return@methodLoop
                    val file = unitsByFile.entries.singleOrNull { (_, unit) -> unit === info.unit }?.key
                        ?: throw IllegalStateException(
                            "Cannot locate source file for ${info.qualifiedName}.${method.nameAsString}"
                        )
                    candidateMethods += signature to ExactProjectMethod(info.qualifiedName, file, method)
                }
            }
        }
        val candidatesBySignature = candidateMethods.groupBy({ it.first }, { it.second })

        val families = mutableListOf<List<ExactProjectMethod>>()
        candidatesBySignature.forEach { (signature, signatureCandidates) ->
            val signatureSeeds = seedsBySignature.getValue(signature)
            val remaining = signatureCandidates.toMutableList()
            while (remaining.isNotEmpty()) {
                val connected = linkedSetOf(remaining.removeAt(0))
                var grew: Boolean
                do {
                    grew = false
                    val additions = remaining.filter { candidate ->
                        connected.any { current ->
                            isTypeAssignableTo(candidate.owner, current.owner) ||
                                isTypeAssignableTo(current.owner, candidate.owner)
                        }
                    }
                    if (additions.isNotEmpty()) {
                        connected.addAll(additions)
                        remaining.removeAll(additions.toSet())
                        grew = true
                    }
                } while (grew)
                if (connected.none { candidate -> signatureSeeds.any { seed -> candidate.method === seed } }) {
                    continue
                }
                val roots = connected.filter { candidate ->
                    connected.none { other ->
                        other !== candidate && isTypeAssignableTo(candidate.owner, other.owner)
                    }
                }
                val root = roots.singleOrNull() ?: continue
                if (!(rootVerifier?.invoke(root.method) ?: isProvenNonOverride(root.method))) continue
                families += connected.sortedBy { it.owner }
            }
        }
        return families.sortedBy { family -> family.joinToString("|") { it.owner } }
    }

    fun exactDirectFieldWithType(
        root: NameExpr,
        use: Node,
        acceptedTypes: Set<String>
    ): Pair<String, String>? = when (val query = exactDirectFieldQuery(root, use, acceptedTypes)) {
        ExactFieldQuery.None -> null
        is ExactFieldQuery.Unique -> query.field
        is ExactFieldQuery.Ambiguous -> {
            throw IllegalStateException(
                "Ambiguous direct registry-provider fields on ${query.ownerType}: ${query.fields}"
            )
        }
    }

    fun exactDirectFieldQuery(
        root: NameExpr,
        use: Node,
        acceptedTypes: Set<String>
    ): ExactFieldQuery {
        val owner = exactEnclosingNamedClass(use) ?: return ExactFieldQuery.None
        val ownerInfo = typeInfo(owner) ?: return ExactFieldQuery.None
        val rootType = resolveExpression(root, use, ownerInfo, emptyMap()) ?: return ExactFieldQuery.None
        val rootInfo = loadType(rootType.name) ?: return ExactFieldQuery.None
        val substitutions = substitutions(rootInfo, rootType)
        val matches = fields(rootInfo).filterNot { it.isStatic }.flatMap { declaration ->
            declaration.variables.mapNotNull { field ->
                val type = resolveType(parseType(field.typeAsString), rootInfo, substitutions)
                    ?: return@mapNotNull null
                (field.nameAsString to type.name).takeIf { (_, name) ->
                    acceptedTypes.any { accepted -> isAssignableTo(type, accepted, mutableSetOf()) } ||
                        name in acceptedTypes
                }
            }
        }.distinct()
        return exactFieldQuery(rootType.name, matches)
    }

    fun exactInstanceFieldWithType(use: Node, acceptedTypes: Set<String>): Pair<String, String>? =
        when (val query = exactInstanceFieldQuery(use, acceptedTypes)) {
            ExactFieldQuery.None -> null
            is ExactFieldQuery.Unique -> query.field
            is ExactFieldQuery.Ambiguous -> {
                throw IllegalStateException(
                    "Ambiguous inherited registry-provider fields on ${query.ownerType}: ${query.fields}"
                )
            }
        }

    fun exactInstanceFieldQuery(use: Node, acceptedTypes: Set<String>): ExactFieldQuery {
        val owner = exactEnclosingNamedClass(use) ?: return ExactFieldQuery.None
        val ownerInfo = typeInfo(owner) ?: return ExactFieldQuery.None
        val matches = collectFieldsWithTypes(TypeRef(ownerInfo.qualifiedName), acceptedTypes, mutableSetOf()).distinct()
        collapseEquivalentPlayerInventoryFields(ownerInfo, matches)?.let {
            return ExactFieldQuery.Unique(it)
        }
        return exactFieldQuery(ownerInfo.qualifiedName, matches)
    }

    fun exactInstanceFieldIsFinal(use: Node, fieldName: String): Boolean {
        val owner = exactEnclosingNamedClass(use) ?: return false
        val ownerInfo = typeInfo(owner) ?: return false
        fun declarations(receiver: TypeRef, visited: MutableSet<String>): List<FieldDeclaration> {
            if (!visited.add(receiver.toString())) return emptyList()
            val info = loadType(receiver.name) ?: return emptyList()
            val substitutions = substitutions(info, receiver)
            return fields(info).filterNot { it.isStatic } +
                resolvedSupers(info, substitutions).flatMap { superType ->
                    declarations(superType, visited)
                }
        }
        val matches = declarations(TypeRef(ownerInfo.qualifiedName), mutableSetOf()).flatMap { field ->
            field.variables.filter { it.nameAsString == fieldName }.map { field }
        }.distinct()
        return matches.singleOrNull()?.isFinal == true
    }

    private fun exactFieldQuery(
        ownerType: String,
        matches: List<Pair<String, String>>
    ): ExactFieldQuery = when (matches.size) {
        0 -> ExactFieldQuery.None
        1 -> ExactFieldQuery.Unique(matches.single())
        else -> ExactFieldQuery.Ambiguous(ownerType, matches)
    }

    fun exactVisibleLocalInitializer(
        name: String,
        use: Node,
        callable: CallableDeclaration<*>
    ): Expression? {
        val candidates = callable.findAll(VariableDeclarator::class.java).mapNotNull { variable ->
            if (variable.nameAsString != name || !variable.initializer.isPresent || !visibleBefore(variable, use)) {
                return@mapNotNull null
            }
            lexicalScopeDistance(variable, use)?.let { distance -> variable to distance }
        }
        val innermostScopeDistance = candidates.minOfOrNull { it.second } ?: return null
        val visible = candidates.filter { it.second == innermostScopeDistance }
        if (visible.size > 1) {
            throw IllegalStateException("Ambiguous visible local initializer '$name' at $use")
        }
        return visible.singleOrNull()?.first?.initializer?.orElse(null)
    }

    private fun collapseEquivalentPlayerInventoryFields(
        owner: TypeInfo,
        matches: List<Pair<String, String>>
    ): Pair<String, String>? {
        if (matches.size != 2) return null
        val player = matches.singleOrNull { it.second in setOf(
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.server.level.ServerPlayer"
        ) } ?: return null
        val inventory = matches.singleOrNull {
            it.second == "net.minecraft.world.entity.player.Inventory"
        } ?: return null
        val declarations = collectProjectDeclarations(TypeRef(owner.qualifiedName), mutableSetOf())
        val playerAssignments = declarations.flatMap { declaration ->
            declaration.findAll(AssignExpr::class.java).mapNotNull { assignment ->
                if (!isExactInstanceFieldTarget(assignment.target, assignment, player.first, owner)) {
                    return@mapNotNull null
                }
                val value = assignment.value as? FieldAccessExpr ?: return@mapNotNull null
                if (value.nameAsString != "player") return@mapNotNull null
                val root = value.scope as? NameExpr ?: return@mapNotNull null
                val rootType = resolveExpression(root, assignment, owner, emptyMap()) ?: return@mapNotNull null
                if (!isAssignableTo(rootType, "net.minecraft.world.entity.player.Inventory", mutableSetOf())) {
                    return@mapNotNull null
                }
                assignment.findAncestor(CallableDeclaration::class.java).orElse(null)?.let {
                    Triple(it, root.nameAsString, assignment)
                }
            }
        }
        val inventoryAssignments = declarations.flatMap { declaration ->
            declaration.findAll(AssignExpr::class.java).mapNotNull { assignment ->
                if (!isExactInstanceFieldTarget(assignment.target, assignment, inventory.first, owner)) {
                    return@mapNotNull null
                }
                val root = assignment.value as? NameExpr ?: return@mapNotNull null
                val rootType = resolveExpression(root, assignment, owner, emptyMap()) ?: return@mapNotNull null
                if (!isAssignableTo(rootType, "net.minecraft.world.entity.player.Inventory", mutableSetOf())) {
                    return@mapNotNull null
                }
                assignment.findAncestor(CallableDeclaration::class.java).orElse(null)?.let {
                    Triple(it, root.nameAsString, assignment)
                }
            }
        }
        if (playerAssignments.size != 1 || inventoryAssignments.size != 1) return null
        val playerAssignment = playerAssignments.single()
        val inventoryAssignment = inventoryAssignments.single()
        return player.takeIf {
            playerAssignment.first === inventoryAssignment.first &&
                playerAssignment.second == inventoryAssignment.second
        }
    }

    private fun isExactInstanceFieldTarget(
        expression: Expression,
        use: Node,
        expectedName: String,
        owner: TypeInfo
    ): Boolean {
        return when (expression) {
            is FieldAccessExpr -> expression.nameAsString == expectedName && expression.scope is ThisExpr
            is NameExpr -> {
                if (expression.nameAsString != expectedName) return false
                val callable = use.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return false
                if (callable.parameters.any { it.nameAsString == expectedName }) return false
                val shadowingLocal = callable.findAll(VariableDeclarator::class.java).any { variable ->
                    variable.nameAsString == expectedName && visibleBefore(variable, use) &&
                        lexicalScopeDistance(variable, use) != null
                }
                if (shadowingLocal) return false
                val shadowingLambda = use.findAncestor(LambdaExpr::class.java).orElse(null)?.parameters
                    ?.any { it.nameAsString == expectedName } == true
                !shadowingLambda && resolveField(TypeRef(owner.qualifiedName), expectedName, mutableSetOf()) != null
            }
            else -> false
        }
    }

    private fun collectProjectDeclarations(
        receiver: TypeRef,
        visited: MutableSet<String>
    ): List<TypeDeclaration<*>> {
        if (!visited.add(receiver.toString())) return emptyList()
        val info = loadType(receiver.name) ?: return emptyList()
        val substitutions = substitutions(info, receiver)
        return listOf(info.declaration) + resolvedSupers(info, substitutions).flatMap { superType ->
            collectProjectDeclarations(superType, visited)
        }
    }

    private fun collectFieldsWithTypes(
        receiver: TypeRef,
        acceptedTypes: Set<String>,
        visited: MutableSet<String>
    ): List<Pair<String, String>> {
        if (!visited.add(receiver.toString())) return emptyList()
        val externalSubstitutions = receiver.arguments.singleOrNull()?.let { mapOf("T" to it) }.orEmpty()
        val known = knownFields[receiver.name].orEmpty().mapNotNull { (name, field) ->
            val type = substitute(field, externalSubstitutions)
            (name to type.name).takeIf { (_, fieldType) ->
                acceptedTypes.any { accepted -> isAssignableTo(type, accepted, mutableSetOf()) } ||
                    fieldType in acceptedTypes
            }
        }
        val info = loadType(receiver.name) ?: return known
        val substitutions = substitutions(info, receiver)
        val direct = fields(info).filterNot { it.isStatic }.flatMap { declaration ->
            declaration.variables.mapNotNull { field ->
                val type = resolveType(parseType(field.typeAsString), info, substitutions)
                    ?: return@mapNotNull null
                (field.nameAsString to type.name).takeIf { (_, name) ->
                    acceptedTypes.any { accepted -> isAssignableTo(type, accepted, mutableSetOf()) } ||
                        name in acceptedTypes
                }
            }
        }
        return known + direct + resolvedSupers(info, substitutions).flatMap { superType ->
            collectFieldsWithTypes(superType, acceptedTypes, visited)
        }
    }

    private fun methodCallReceiverTypeRef(call: MethodCallExpr): TypeRef? {
        val owner = exactEnclosingNamedClass(call) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index call owner '${owner.nameAsString}'")
        val scope = call.scope.orElse(null)
        val receiver = if (scope == null) {
            TypeRef(ownerInfo.qualifiedName)
        } else if (scope is SuperExpr) {
            val qualifiedSuper = scope.typeName.orElse(null)?.asString()
            if (qualifiedSuper != null) {
                resolveType(TypeRef(qualifiedSuper), ownerInfo, emptyMap()) ?: return null
            } else {
                directSuperclass(ownerInfo) ?: return null
            }
        } else {
            resolveExpression(scope, call, ownerInfo, emptyMap())
                ?: resolveTypeScope(scope, ownerInfo, emptyMap())
                ?: return null
        }
        return receiver
    }

    private fun resolveTypeScope(
        expression: Expression,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        return when (expression) {
            is NameExpr -> {
                if (hasVisibleLexicalValueBinding(expression.nameAsString, expression)) null
                else resolveType(TypeRef(expression.nameAsString), owner, substitutions)
            }
            is FieldAccessExpr -> exactQualifiedName(expression)?.let { (head, qualifiedName) ->
                if (hasVisibleProjectTypeScopeHeadBinding(head.nameAsString, head, owner)) null
                else loadType(qualifiedName)?.let { TypeRef(it.qualifiedName) }
            }
            else -> null
        }
    }

    private fun hasVisibleProjectTypeScopeHeadBinding(
        name: String,
        use: Node,
        owner: TypeInfo
    ): Boolean {
        if (hasVisibleLexicalValueBinding(name, use)) return true
        when (lexicalMemberType(name, owner)) {
            is MemberTypeLookup.Resolved, MemberTypeLookup.Unknown -> return true
            MemberTypeLookup.Absent -> Unit
        }
        if (projectHierarchyScopeHeadIsPresentOrUnknown(name, owner, mutableSetOf())) return true
        var lexicalOwner: Node? = owner.declaration.parentNode.orElse(null)
        while (lexicalOwner != null) {
            if (lexicalOwner is TypeDeclaration<*>) {
                val outer = typeInfo(lexicalOwner) ?: return true
                if (projectHierarchyScopeHeadIsPresentOrUnknown(name, outer, mutableSetOf())) return true
            }
            lexicalOwner = lexicalOwner.parentNode.orElse(null)
        }
        return false
    }

    private fun projectHierarchyScopeHeadIsPresentOrUnknown(
        name: String,
        owner: TypeInfo,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(owner.qualifiedName)) return true
        if (fields(owner).any { declaration ->
                declaration.variables.any { it.nameAsString == name }
            } || (owner.declaration as? RecordDeclaration)?.parameters
                ?.any { it.nameAsString == name } == true ||
            immediateMemberTypes(owner, name).isNotEmpty()
        ) {
            return true
        }
        return owner.directSupers.any { rawSuper ->
            when (val directSuper = resolveDirectSuper(rawSuper, owner)) {
                is DirectSuperLookup.Project -> projectHierarchyScopeHeadIsPresentOrUnknown(
                    name,
                    directSuper.info,
                    visited
                )
                is DirectSuperLookup.External -> knownClosedExternalScopeHeadNames[directSuper.qualifiedName]
                    ?.let { name in it }
                    ?: true
                DirectSuperLookup.Unknown -> true
            }
        }
    }

    private fun exactQualifiedName(expression: Expression): Pair<NameExpr, String>? = when (expression) {
        is NameExpr -> expression to expression.nameAsString
        is FieldAccessExpr -> exactQualifiedName(expression.scope)?.let { (head, prefix) ->
            head to "$prefix.${expression.nameAsString}"
        }
        else -> null
    }

    private fun directSuperclass(info: TypeInfo): TypeRef? {
        val declaration = info.declaration as? ClassOrInterfaceDeclaration ?: return null
        if (declaration.isInterface || declaration.extendedTypes.isEmpty()) return null
        if (declaration.extendedTypes.size != 1) {
            throw IllegalStateException("Class ${info.qualifiedName} has multiple direct superclasses")
        }
        return resolveType(parseType(declaration.extendedTypes.single().asString()), info, emptyMap())
    }

    fun argumentsMatchProjectMethodExcluding(
        call: MethodCallExpr,
        declarationOwner: String,
        declarationArity: Int,
        excludedParameterIndex: Int
    ): Boolean = argumentsMatchProjectMethod(
        call,
        declarationOwner,
        declarationArity,
        excludedParameterIndex
    )

    fun argumentsMatchProjectMethod(
        call: MethodCallExpr,
        declarationOwner: String,
        declarationArity: Int
    ): Boolean = argumentsMatchProjectMethod(call, declarationOwner, declarationArity, null)

    fun argumentsMatchTypes(call: MethodCallExpr, expectedTypes: List<String>): Boolean {
        if (call.arguments.size != expectedTypes.size) return false
        return expressionsMatchTypes(call.arguments.toList(), call, expectedTypes)
    }

    fun resolvesToUniqueProjectMethodSignature(
        call: MethodCallExpr,
        expectedTypes: List<String>,
        excludedParameterIndex: Int? = null
    ): Boolean {
        if (excludedParameterIndex != null && excludedParameterIndex !in expectedTypes.indices) return false
        val expectedCallArity = expectedTypes.size - if (excludedParameterIndex == null) 0 else 1
        if (call.arguments.size != expectedCallArity) return false
        val receiver = methodCallReceiverTypeRef(call) ?: return false
        if (!hasClosedProjectMethodHierarchy(receiver.name)) return false
        val signatures = collectProjectMethodSignatures(
            receiver,
            call.nameAsString,
            expectedTypes.size,
            mutableSetOf()
        ) ?: return false
        return signatures.singleOrNull() == expectedTypes
    }

    fun resolvesToUniqueProjectMethodSignatureIgnoringArgument(
        call: MethodCallExpr,
        expectedTypes: List<String>,
        excludedArgumentIndex: Int
    ): Boolean {
        if (call.arguments.size != expectedTypes.size + 1 ||
            excludedArgumentIndex !in call.arguments.indices
        ) return false
        val receiver = methodCallReceiverTypeRef(call) ?: return false
        if (!hasClosedProjectMethodHierarchy(receiver.name)) return false
        val signatures = collectProjectMethodSignatures(
            receiver,
            call.nameAsString,
            expectedTypes.size,
            mutableSetOf()
        ) ?: return false
        return signatures.singleOrNull() == expectedTypes
    }

    fun argumentsMatchTypesExcluding(
        call: MethodCallExpr,
        expectedTypes: List<String>,
        excludedArgumentIndex: Int
    ): Boolean {
        if (excludedArgumentIndex !in call.arguments.indices) return false
        return expressionsMatchTypes(
            call.arguments.filterIndexed { index, _ -> index != excludedArgumentIndex },
            call,
            expectedTypes
        )
    }

    private fun expressionsMatchTypes(
        expressions: List<Expression>,
        use: Node,
        expectedTypes: List<String>
    ): Boolean {
        if (expressions.size != expectedTypes.size) return false
        val caller = exactEnclosingNamedClass(use) ?: return false
        val callerInfo = typeInfo(caller) ?: return false
        return expressions.zip(expectedTypes).all { (argument, expected) ->
            val actual = resolveArgumentValue(argument, use, callerInfo, emptyMap()) ?: return@all false
            argumentValueMatchesType(actual, TypeRef(expected))
        }
    }

    private fun argumentsMatchProjectMethod(
        call: MethodCallExpr,
        declarationOwner: String,
        declarationArity: Int,
        excludedParameterIndex: Int?
    ): Boolean {
        val caller = exactEnclosingNamedClass(call)
            ?: throw IllegalStateException("Call '$call' is outside an exact named type")
        val callerInfo = typeInfo(caller)
            ?: throw IllegalStateException("Cannot index call owner '${caller.nameAsString}'")
        val actualTypes = call.arguments.map { argument ->
            resolveArgumentValue(argument, call, callerInfo, emptyMap()) ?: return false
        }
        return hasMatchingProjectMethod(
            TypeRef(declarationOwner),
            call.nameAsString,
            declarationArity,
            excludedParameterIndex,
            actualTypes,
            mutableSetOf()
        )
    }

    private fun hasMatchingProjectMethod(
        receiver: TypeRef,
        name: String,
        declarationArity: Int,
        excludedParameterIndex: Int?,
        actualTypes: List<ResolvedArgumentValue>,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(receiver.toString())) return false
        val declarationInfo = loadType(receiver.name) ?: return false
        val substitutions = substitutions(declarationInfo, receiver)
        val methods = methods(declarationInfo).filter {
            it.nameAsString == name && it.parameters.size == declarationArity
        }
        val matching = methods.filter { method ->
            if (excludedParameterIndex != null && excludedParameterIndex !in method.parameters.indices) {
                return@filter false
            }
            val expectedTypes = method.parameters.mapIndexedNotNull { index, parameter ->
                if (index == excludedParameterIndex) null
                else resolveType(parseType(parameter.typeAsString), declarationInfo, substitutions)
                    ?: return@filter false
            }
            expectedTypes.size == actualTypes.size && actualTypes.zip(expectedTypes).all { (actual, expected) ->
                argumentValueMatchesType(actual, expected)
            }
        }
        if (matching.size > 1) {
            throw IllegalStateException(
                "Ambiguous typed method '$name/$declarationArity' on ${receiver.name}"
            )
        }
        if (matching.size == 1) return true
        return resolvedSupers(declarationInfo, substitutions).any { superType ->
            hasMatchingProjectMethod(
                superType,
                name,
                declarationArity,
                excludedParameterIndex,
                actualTypes,
                visited
            )
        }
    }

    private fun collectProjectMethodSignatures(
        receiver: TypeRef,
        name: String,
        declarationArity: Int,
        visited: MutableSet<String>
    ): Set<List<String>>? {
        if (!visited.add(receiver.toString())) return emptySet()
        val declarationInfo = loadType(receiver.name) ?: return null
        val substitutions = substitutions(declarationInfo, receiver)
        val signatures = linkedSetOf<List<String>>()
        methods(declarationInfo)
            .filter { it.nameAsString == name && it.parameters.size == declarationArity }
            .forEach { method ->
                val parameterTypes = method.parameters.map { parameter ->
                    resolveType(parseType(parameter.typeAsString), declarationInfo, substitutions)?.name
                        ?: return null
                }
                signatures += parameterTypes
            }
        resolvedSupers(declarationInfo, substitutions).forEach { superType ->
            signatures += collectProjectMethodSignatures(
                superType,
                name,
                declarationArity,
                visited
            ) ?: return null
        }
        return signatures
    }

    fun hasClosedProjectMethodHierarchy(qualifiedName: String): Boolean =
        hasClosedProjectMethodHierarchy(qualifiedName, mutableSetOf())

    fun isProvenNonOverride(method: MethodDeclaration): Boolean {
        if (method.isPrivate) return true
        if (method.annotations.any { it.nameAsString == "Override" }) return false
        val ownerDeclaration = exactEnclosingNamedClass(method) ?: return false
        val owner = typeInfo(ownerDeclaration) ?: return false
        if (!hasClosedProjectMethodHierarchy(owner.qualifiedName)) return false
        val parameterTypes = method.parameters.map { parameter ->
            resolveType(parseType(parameter.typeAsString), owner, emptyMap()) ?: return false
        }
        return resolvedSupers(owner, emptyMap()).none { superType ->
            hierarchyDeclaresMatchingMethod(
                superType,
                method.nameAsString,
                parameterTypes,
                mutableSetOf()
            )
        }
    }

    private fun hierarchyDeclaresMatchingMethod(
        receiver: TypeRef,
        name: String,
        parameterTypes: List<TypeRef>,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(receiver.toString())) return false
        if (ExactExternalTypeContracts.hasClosedMethodSurface(receiver.name)) {
            return parameterTypes.all { it.arrayDepth == 0 } &&
                ExactExternalTypeContracts.containsMethod(receiver.name, name, parameterTypes.map { it.name })
        }
        knownClosedExternalMethodSurfaces[receiver.name]?.let { surface ->
            return surface.any { signature ->
                signature.name == name &&
                    signature.parameterTypes.size == parameterTypes.size &&
                    signature.parameterTypes.zip(parameterTypes).all { (expected, actual) ->
                        expected == actual.name && actual.arrayDepth == 0
                    }
            }
        }
        val info = loadType(receiver.name) ?: return true
        val substitutions = substitutions(info, receiver)
        val sameArity = methods(info).filter {
            !it.isPrivate && it.nameAsString == name && it.parameters.size == parameterTypes.size
        }
        if (sameArity.any { candidate ->
                val candidateTypes = candidate.parameters.map { parameter ->
                    resolveType(parseType(parameter.typeAsString), info, substitutions) ?: return true
                }
                candidateTypes.zip(parameterTypes).all { (candidateType, targetType) ->
                    candidateType.name == targetType.name && candidateType.arrayDepth == targetType.arrayDepth
                }
            }
        ) return true
        return resolvedSupers(info, substitutions).any { superType ->
            hierarchyDeclaresMatchingMethod(superType, name, parameterTypes, visited)
        }
    }

    private fun hasClosedProjectMethodHierarchy(
        qualifiedName: String,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(qualifiedName)) return true
        if (qualifiedName in knownClosedExternalMethodSurfaces ||
            ExactExternalTypeContracts.hasClosedMethodSurface(qualifiedName)
        ) return true
        val info = loadType(qualifiedName) ?: return qualifiedName.startsWith("java.lang.")
        return info.directSupers.all { raw ->
            val resolved = resolveDirectSuperType(raw, info, emptyMap()) ?: return@all false
            resolved.name.startsWith("java.lang.") ||
                resolved.name in knownClosedExternalMethodSurfaces ||
                ExactExternalTypeContracts.hasClosedMethodSurface(resolved.name) ||
                (loadType(resolved.name) != null && hasClosedProjectMethodHierarchy(resolved.name, visited))
        }
    }

    private fun resolveArgumentValue(
        expression: Expression,
        use: Node,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): ResolvedArgumentValue? = when {
        isNullLiteral(expression) -> ResolvedArgumentValue.NullLiteral
        else -> resolveExpression(expression, use, owner, substitutions)
            ?.let { ResolvedArgumentValue.Typed(it) }
    }

    private fun isNullLiteral(expression: Expression): Boolean = when (expression) {
        is NullLiteralExpr -> true
        is EnclosedExpr -> isNullLiteral(expression.inner)
        else -> false
    }

    private fun argumentValueMatchesType(
        argument: ResolvedArgumentValue,
        expected: TypeRef
    ): Boolean = when (argument) {
        ResolvedArgumentValue.NullLiteral -> expected.arrayDepth > 0 || expected.name !in primitiveTypes
        is ResolvedArgumentValue.Typed -> isAssignableTo(argument.type, expected.name, mutableSetOf())
    }

    private fun resolveExpression(
        expression: Expression,
        use: Node,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        return when (expression) {
            is EnclosedExpr -> resolveExpression(expression.inner, use, owner, substitutions)
            is ArrayAccessExpr -> {
                val array = resolveExpression(expression.name, use, owner, substitutions) ?: return null
                if (array.arrayDepth == 0) {
                    throw IllegalStateException("Array access '$expression' has a non-array declared receiver")
                }
                array.copy(arrayDepth = array.arrayDepth - 1)
            }
            is CastExpr -> resolveLexicalType(parseType(expression.typeAsString), use, owner, substitutions)
            is BooleanLiteralExpr -> TypeRef("boolean")
            is CharLiteralExpr -> TypeRef("char")
            is DoubleLiteralExpr -> TypeRef(if (expression.value.lowercase().endsWith("f")) "float" else "double")
            is IntegerLiteralExpr -> TypeRef("int")
            is LongLiteralExpr -> TypeRef("long")
            is StringLiteralExpr -> TypeRef("java.lang.String")
            is ObjectCreationExpr -> resolveLexicalType(parseType(expression.typeAsString), use, owner, substitutions)
            is ThisExpr -> TypeRef(owner.qualifiedName)
            is TypeExpr -> resolveLexicalType(parseType(expression.typeAsString), use, owner, substitutions)
            is NameExpr -> resolveName(expression.nameAsString, use, owner, substitutions)
            is FieldAccessExpr -> {
                val scope = resolveExpression(expression.scope, use, owner, substitutions) ?: return null
                resolveField(scope, expression.nameAsString, mutableSetOf())
            }
            is MethodCallExpr -> resolveMethodCall(expression, use, owner, substitutions)
            else -> null
        }
    }

    private fun resolveMethodCall(
        call: MethodCallExpr,
        use: Node,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        val scopeExpression = call.scope.orElse(null)
        if (scopeExpression == null) {
            return resolveProjectMethodCall(
                TypeRef(owner.qualifiedName, owner.typeParameters.mapNotNull(substitutions::get)),
                call,
                owner,
                substitutions,
                mutableSetOf()
            )
        }
        val scope = resolveExpression(scopeExpression, use, owner, substitutions)
            ?: resolveTypeScope(scopeExpression, owner, substitutions)
            ?: return null
        if (scope.name == "java.util.List" && call.nameAsString == "get" && call.arguments.size == 1) {
            return scope.arguments.singleOrNull()
                ?: throw IllegalStateException("Raw List receiver has no exact element type for '$call'")
        }
        if (scope.name == "java.util.Map" && call.nameAsString == "get" && call.arguments.size == 1) {
            return scope.arguments.getOrNull(1)
                ?: throw IllegalStateException("Raw Map receiver has no exact value type for '$call'")
        }
        if (scope.name == "java.util.Map" && call.nameAsString == "values" && call.arguments.isEmpty()) {
            val valueType = scope.arguments.getOrNull(1)
                ?: throw IllegalStateException("Raw Map receiver has no exact value type for '$call'")
            return TypeRef("java.util.Collection", listOf(valueType))
        }
        if (scope.name == "java.util.Map" && call.nameAsString == "entrySet" && call.arguments.isEmpty()) {
            val keyType = scope.arguments.getOrNull(0)
                ?: throw IllegalStateException("Raw Map receiver has no exact key type for '$call'")
            val valueType = scope.arguments.getOrNull(1)
                ?: throw IllegalStateException("Raw Map receiver has no exact value type for '$call'")
            return TypeRef(
                "java.util.Set",
                listOf(TypeRef("java.util.Map.Entry", listOf(keyType, valueType)))
            )
        }
        if (scope.name == "java.util.Map.Entry" && call.arguments.isEmpty()) {
            when (call.nameAsString) {
                "getKey" -> return scope.arguments.getOrNull(0)
                    ?: throw IllegalStateException("Raw Map.Entry receiver has no exact key type for '$call'")
                "getValue" -> return scope.arguments.getOrNull(1)
                    ?: throw IllegalStateException("Raw Map.Entry receiver has no exact value type for '$call'")
            }
        }
        exactExternalMethodReturnType(scope, call, owner, substitutions)?.let { return it }
        if (scope.name == "java.util.Optional" && call.nameAsString == "get" && call.arguments.isEmpty()) {
            return scope.arguments.singleOrNull()
                ?: throw IllegalStateException("Raw Optional receiver has no exact value type for '$call'")
        }
        knownMethods[scope.name]?.get(call.nameAsString to call.arguments.size)?.let { return it }
        return resolveProjectMethodCall(scope, call, owner, substitutions, mutableSetOf())
    }

    private fun resolveProjectMethodCall(
        receiver: TypeRef,
        call: MethodCallExpr,
        callerOwner: TypeInfo,
        callerSubstitutions: Map<String, TypeRef>,
        visited: MutableSet<String>
    ): TypeRef? {
        if (!visited.add(receiver.toString())) return null
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val methods = methods(info).filter {
            it.nameAsString == call.nameAsString && it.parameters.size == call.arguments.size
        }
        val actualTypes = call.arguments.map { argument ->
            resolveArgumentValue(argument, call, callerOwner, callerSubstitutions) ?: return null
        }
        val matching = methods.filter { method ->
            val expected = method.parameters.map { parameter ->
                resolveLexicalType(parseType(parameter.typeAsString), method, info, substitutions) ?: return@filter false
            }
            actualTypes.zip(expected).all { (actual, target) ->
                argumentValueMatchesType(actual, target)
            }
        }
        if (matching.size > 1) {
            throw IllegalStateException("Ambiguous typed method '${call.nameAsString}/${call.arguments.size}' on ${receiver.name}")
        }
        matching.singleOrNull()?.let { method ->
            return resolveLexicalType(parseType(method.typeAsString), method, info, substitutions)
        }
        if (call.arguments.isEmpty()) {
            resolveRecordComponent(info, receiver, call.nameAsString)?.let { return it }
        }
        return exactSingle(
            resolvedSupers(info, substitutions).mapNotNull {
                resolveProjectMethodCall(it, call, callerOwner, callerSubstitutions, visited)
            },
            "method '${call.nameAsString}/${call.arguments.size}' inherited by ${receiver.name}"
        )
    }

    private fun resolveProjectMethodDeclaration(
        receiver: TypeRef,
        call: MethodCallExpr,
        callerOwner: TypeInfo,
        callerSubstitutions: Map<String, TypeRef>,
        visited: Set<String>
    ): ProjectMethodBinding? = when (
        val resolution = resolveProjectMethodDispatch(
            receiver,
            call,
            callerOwner,
            callerSubstitutions,
            visited
        )
    ) {
        is ProjectDispatchResolution.Resolved -> resolution.value
        ProjectDispatchResolution.Absent, is ProjectDispatchResolution.Unknown -> null
    }

    private fun resolveProjectMethodDispatch(
        receiver: TypeRef,
        call: MethodCallExpr,
        callerOwner: TypeInfo,
        callerSubstitutions: Map<String, TypeRef>,
        visited: Set<String>
    ): ProjectDispatchResolution<ProjectMethodBinding> {
        if (receiver.toString() in visited) {
            return ProjectDispatchResolution.Unknown(mayDeclareConcreteClassMethod = true)
        }
        val info = loadType(receiver.name)
            ?: return externalMethodDispatch(receiver.name, call.nameAsString, MethodOwnerKind.CLASS)
        val ownerKind = methodOwnerKind(info)
        val substitutions = substitutions(info, receiver)
        val methods = methods(info).filter {
            it.nameAsString == call.nameAsString && it.parameters.size == call.arguments.size
        }
        val actualTypes = call.arguments.map { argument ->
            resolveArgumentValue(argument, call, callerOwner, callerSubstitutions)
                ?: return ProjectDispatchResolution.Unknown(ownerKind == MethodOwnerKind.CLASS)
        }
        var unresolvedDirectCandidate = false
        val matching = methods.mapNotNull { method ->
            val expected = method.parameters.map { parameter ->
                resolveLexicalType(parseType(parameter.typeAsString), method, info, substitutions)
                    ?: run {
                        unresolvedDirectCandidate = true
                        return@mapNotNull null
                    }
            }
            method.takeIf {
                actualTypes.zip(expected).all { (actual, target) ->
                    argumentValueMatchesType(actual, target)
                }
            }
        }
        if (unresolvedDirectCandidate || matching.size > 1) {
            return ProjectDispatchResolution.Unknown(ownerKind == MethodOwnerKind.CLASS)
        }
        matching.singleOrNull()?.let { method ->
            return ProjectDispatchResolution.Resolved(
                ProjectMethodBinding(info.qualifiedName, method),
                ownerKind
            )
        }
        val nextVisited = visited + receiver.toString()
        val inherited = directSuperBranches(info).map { branch ->
            val superType = resolveDirectSuperType(branch.raw, info, substitutions)
                ?: return@map ProjectDispatchResolution.Unknown(
                    branch.ownerKind == MethodOwnerKind.CLASS
                )
            if (loadType(superType.name) == null) {
                externalMethodDispatch(superType.name, call.nameAsString, branch.ownerKind)
            } else {
                resolveProjectMethodDispatch(
                    superType,
                    call,
                    callerOwner,
                    callerSubstitutions,
                    nextVisited
                )
            }
        }
        return collapseProjectDispatch(
            inherited,
            ::projectMethodBindingKey
        ) { implementation, contract ->
            concreteClassMethodSuppressesInterfaceMethod(
                receiver.name,
                implementation.declaration,
                contract.declaration
            )
        }
    }

    private fun resolveProjectMethodOwner(
        receiver: TypeRef,
        name: String,
        arity: Int,
        visited: MutableSet<String>
    ): String? {
        if (!visited.add(receiver.toString())) return null
        val info = loadType(receiver.name) ?: return null
        val methods = methods(info).filter { it.nameAsString == name && it.parameters.size == arity }
        if (methods.size > 1) throw IllegalStateException("Ambiguous method '$name/$arity' on ${receiver.name}")
        if (methods.size == 1) return info.qualifiedName
        if (arity == 0 && resolveRecordComponent(info, receiver, name) != null) return info.qualifiedName
        val substitutions = substitutions(info, receiver)
        return exactSingle(
            resolvedSupers(info, substitutions).mapNotNull {
                resolveProjectMethodOwner(it, name, arity, visited)
            },
            "method owner '$name/$arity' inherited by ${receiver.name}"
        )
    }

    private fun isAssignableTo(actual: TypeRef, expectedType: String, visited: MutableSet<String>): Boolean {
        if (actual.arrayDepth != 0) return false
        if (actual.name == expectedType) return true
        if (!visited.add(actual.toString())) return false
        if (expectedType in knownExternalAssignableTypes[actual.name].orEmpty()) return true
        val info = loadType(actual.name) ?: return false
        val substitutions = substitutions(info, actual)
        return resolvedSupers(info, substitutions).any { isAssignableTo(it, expectedType, visited) }
    }

    private fun resolveProjectMethod(
        receiver: TypeRef,
        name: String,
        arity: Int,
        visited: MutableSet<String>
    ): TypeRef? {
        if (!visited.add(receiver.toString())) return null
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val methods = methods(info).filter { it.nameAsString == name && it.parameters.size == arity }
        if (methods.size > 1) {
            throw IllegalStateException("Ambiguous method '$name/$arity' on ${receiver.name}")
        }
        methods.singleOrNull()?.let { method ->
            return resolveLexicalType(parseType(method.typeAsString), method, info, substitutions)
        }
        if (arity == 0) resolveRecordComponent(info, receiver, name)?.let { return it }
        return exactSingle(
            resolvedSupers(info, substitutions).mapNotNull { resolveProjectMethod(it, name, arity, visited) },
            "method '$name/$arity' inherited by ${receiver.name}"
        )
    }

    private fun resolveRecordComponent(info: TypeInfo, receiver: TypeRef, name: String): TypeRef? {
        val record = info.declaration as? RecordDeclaration ?: return null
        val matches = record.parameters.filter { it.nameAsString == name }
        if (matches.size > 1) {
            throw IllegalStateException("Ambiguous record component '$name' on ${receiver.name}")
        }
        val component = matches.singleOrNull() ?: return null
        return resolveType(parseType(component.typeAsString), info, substitutions(info, receiver))
    }

    private fun parameterTypeRef(parameter: Parameter): TypeRef {
        val declared = parseType(parameter.typeAsString)
        return if (parameter.isVarArgs) {
            declared.copy(arrayDepth = declared.arrayDepth + 1)
        } else {
            declared
        }
    }

    private fun resolveName(
        name: String,
        use: Node,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        enclosingLambdaBinding(name, use)?.let { (lambda, parameterIndex) ->
            return resolveLambdaParameter(lambda, parameterIndex, owner, substitutions)
        }
        visiblePatternBinding(name, use)?.let { pattern ->
            return resolveLexicalType(parseType(pattern.typeAsString), pattern, owner, substitutions)
        }
        val callable = use.findAncestor(CallableDeclaration::class.java).orElse(null)
        if (callable != null) {
            val parameters = callable.parameters.filter { it.nameAsString == name }
            if (parameters.size > 1) {
                throw IllegalStateException("Ambiguous callable parameter '$name' at $use")
            }
            parameters.singleOrNull()?.let { parameter ->
                return resolveLexicalType(parameterTypeRef(parameter), parameter, owner, substitutions)
            }
            val localCandidates = callable.findAll(VariableDeclarator::class.java).mapNotNull { variable ->
                if (variable.nameAsString != name || !visibleBefore(variable, use)) return@mapNotNull null
                lexicalScopeDistance(variable, use)?.let { distance -> variable to distance }
            }
            val innermostScopeDistance = localCandidates.minOfOrNull { it.second }
            val locals = localCandidates.filter { it.second == innermostScopeDistance }
            if (locals.size > 1) {
                throw IllegalStateException("Ambiguous visible local '$name' at $use")
            }
            locals.singleOrNull()?.let { (variable, _) ->
                return resolveLexicalType(parseType(variable.typeAsString), variable, owner, substitutions)
            }
        }
        return resolveField(TypeRef(owner.qualifiedName), name, mutableSetOf())
    }

    private fun hasVisibleLexicalValueBinding(name: String, use: Node): Boolean {
        if (enclosingLambdaBinding(name, use) != null) return true
        if (visiblePatternBinding(name, use) != null) return true
        val callable = use.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return false
        if (callable.parameters.any { it.nameAsString == name }) return true
        return callable.findAll(VariableDeclarator::class.java).any { variable ->
            variable.nameAsString == name && visibleBefore(variable, use) &&
                lexicalScopeDistance(variable, use) != null
        }
    }

    private fun visiblePatternBinding(name: String, use: Node): PatternExpr? {
        val callable = use.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return null
        val bindings = callable.findAll(TypePatternExpr::class.java).filter { pattern ->
            pattern.nameAsString == name && patternBindingDominatesUse(pattern, use)
        }
        if (bindings.size > 1) {
            throw IllegalStateException("Ambiguous visible pattern binding '$name' at $use")
        }
        return bindings.singleOrNull()
    }

    private fun patternBindingDominatesUse(pattern: PatternExpr, use: Node): Boolean {
        val patternCallable = pattern.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return false
        val useCallable = use.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return false
        if (patternCallable !== useCallable) return false
        val instanceOf = pattern.parentNode.orElse(null) ?: return false

        var cursor: Node = instanceOf
        while (true) {
            val parent = cursor.parentNode.orElse(null) ?: break
            when {
                parent is BinaryExpr && parent.operator == BinaryExpr.Operator.AND -> {
                    if ((parent.left === cursor || parent.left.isAncestorOf(cursor)) &&
                        (parent.right === use || parent.right.isAncestorOf(use))
                    ) return true
                    cursor = parent
                }
                parent is EnclosedExpr -> cursor = parent
                else -> break
            }
        }

        val guard = instanceOf.findAncestor(IfStmt::class.java).orElse(null) ?: return false
        if (!(guard.condition === instanceOf || guard.condition.isAncestorOf(instanceOf))) return false
        if (guard.thenStmt === use || guard.thenStmt.isAncestorOf(use)) {
            return conditionTrueImpliesPattern(guard.condition, instanceOf)
        }
        val elseStatement = guard.elseStmt.orElse(null)
        if (elseStatement != null && (elseStatement === use || elseStatement.isAncestorOf(use))) {
            return conditionFalseImpliesPattern(guard.condition, instanceOf)
        }
        if (!conditionFalseImpliesPattern(guard.condition, instanceOf) || !statementAlwaysExits(guard.thenStmt)) {
            return false
        }
        val block = guard.parentNode.orElse(null) as? BlockStmt ?: return false
        if (!(block === use || block.isAncestorOf(use))) return false
        val guardIndex = block.statements.indexOfFirst { it === guard }
        if (guardIndex < 0) return false
        val containingStatement = block.statements.firstOrNull { statement ->
            statement === use || statement.isAncestorOf(use)
        } ?: return false
        return block.statements.indexOf(containingStatement) > guardIndex
    }

    private fun conditionTrueImpliesPattern(condition: Expression, instanceOf: Node): Boolean = when (condition) {
        is EnclosedExpr -> conditionTrueImpliesPattern(condition.inner, instanceOf)
        is BinaryExpr -> condition.operator == BinaryExpr.Operator.AND && when {
            condition.left === instanceOf || condition.left.isAncestorOf(instanceOf) ->
                conditionTrueImpliesPattern(condition.left, instanceOf)
            condition.right === instanceOf || condition.right.isAncestorOf(instanceOf) ->
                conditionTrueImpliesPattern(condition.right, instanceOf)
            else -> false
        }
        else -> condition === instanceOf
    }

    private fun conditionFalseImpliesPattern(condition: Expression, instanceOf: Node): Boolean = when (condition) {
        is EnclosedExpr -> conditionFalseImpliesPattern(condition.inner, instanceOf)
        is UnaryExpr -> condition.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT &&
            conditionTrueImpliesPattern(condition.expression, instanceOf)
        is BinaryExpr -> condition.operator == BinaryExpr.Operator.OR && when {
            condition.left === instanceOf || condition.left.isAncestorOf(instanceOf) ->
                conditionFalseImpliesPattern(condition.left, instanceOf)
            condition.right === instanceOf || condition.right.isAncestorOf(instanceOf) ->
                conditionFalseImpliesPattern(condition.right, instanceOf)
            else -> false
        }
        else -> false
    }

    private fun statementAlwaysExits(statement: Statement): Boolean = when (statement) {
        is ReturnStmt, is ThrowStmt, is ContinueStmt, is BreakStmt -> true
        is BlockStmt -> statement.statements.lastOrNull()?.let(::statementAlwaysExits) == true
        else -> false
    }

    private fun enclosingLambdaBinding(name: String, use: Node): Pair<LambdaExpr, Int>? {
        var cursor: Node? = use.parentNode.orElse(null)
        while (cursor != null && cursor !is CallableDeclaration<*>) {
            if (cursor is LambdaExpr) {
                val parameterIndex = cursor.parameters.indexOfFirst { it.nameAsString == name }
                if (parameterIndex >= 0) return cursor to parameterIndex
            }
            cursor = cursor.parentNode.orElse(null)
        }
        return null
    }

    private fun resolveLambdaParameter(
        lambda: LambdaExpr,
        parameterIndex: Int,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        val parentCall = lambda.findAncestor(MethodCallExpr::class.java).orElse(null) ?: return null
        val argumentIndex = parentCall.arguments.indexOfFirst { it === lambda || it.isAncestorOf(lambda) }
        if (argumentIndex < 0) return null
        val scopeExpression = parentCall.scope.orElse(null)
        val scope = if (scopeExpression == null) {
            TypeRef(owner.qualifiedName, owner.typeParameters.mapNotNull(substitutions::get))
        } else {
            resolveExpression(scopeExpression, parentCall, owner, substitutions)
                ?: resolveTypeScope(scopeExpression, owner, substitutions)
                ?: return null
        }
        val exactExternal = exactExternalFunctionalParameterType(
            scope,
            parentCall,
            argumentIndex,
            lambda.parameters.size,
            parameterIndex,
            owner,
            substitutions
        )
        val external = exactExternal ?: when {
            parentCall.nameAsString == "forEach" && argumentIndex == 0 &&
                scope.name in setOf("java.lang.Iterable", "java.util.Collection", "java.util.List") ->
                scope.arguments.singleOrNull()
                    ?: throw IllegalStateException("Raw ${scope.name} has no exact lambda element type for '$parentCall'")
            parentCall.nameAsString == "forEach" && argumentIndex == 0 && scope.name == "java.util.Map" ->
                scope.arguments.getOrNull(parameterIndex)
                    ?: throw IllegalStateException("Raw Map has no exact lambda parameter type for '$parentCall'")
            parentCall.nameAsString == "ifPresent" && argumentIndex == 0 &&
                (
                    scope.name == "java.util.Optional" ||
                        GENERATED_COMPAT_LAZY_OPTIONAL.matches(scope.name) ||
                        (
                            scope.name in EXTERNAL_LAZY_OPTIONAL_TYPES &&
                                parentCall.arguments.size == 1 &&
                                loadType(scope.name) == null
                        )
                ) &&
                parameterIndex == 0 -> scope.arguments.singleOrNull()
            parentCall.nameAsString in setOf("map", "flatMap", "filter") && argumentIndex == 0 &&
                scope.name == "java.util.Optional" && parameterIndex == 0 -> scope.arguments.singleOrNull()
            else -> null
        }
        return external ?: resolveProjectFunctionalParameterType(
            scope,
            parentCall,
            argumentIndex,
            parameterIndex,
            owner,
            substitutions,
            emptySet()
        )
    }

    private fun exactExternalFunctionalParameterType(
        receiver: TypeRef,
        call: MethodCallExpr,
        callbackArgumentIndex: Int,
        functionalArity: Int,
        lambdaParameterIndex: Int,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        val matches = exactExternalFunctionalParameterContracts.filter { contract ->
            contract.owner == receiver.name &&
                contract.methodName == call.nameAsString &&
                contract.methodArity == call.arguments.size &&
                contract.callbackArgumentIndex == callbackArgumentIndex &&
                contract.functionalArity == functionalArity &&
                contract.lambdaParameterIndex == lambdaParameterIndex
        }
        if (matches.isEmpty()) return null
        if (loadType(receiver.name) != null) return null
        if (matches.size > 1) {
            throw IllegalStateException(
                "Ambiguous exact external functional parameter contracts for " +
                    "${receiver.name}.${call.nameAsString}/${call.arguments.size}"
            )
        }
        val contract = matches.singleOrNull() ?: return null
        return when (val source = contract.parameterSource) {
            is ExactExternalFunctionalParameterSource.ReceiverTypeArgument ->
                receiver.arguments.getOrNull(source.index)
                    ?: throw IllegalStateException(
                        "Raw ${receiver.name} has no exact lambda parameter type for '$call'"
                    )
            is ExactExternalFunctionalParameterSource.IterableCallArgument -> {
                val argument = call.arguments.getOrNull(source.argumentIndex) ?: return null
                val argumentType = resolveExpression(argument, call, owner, substitutions) ?: return null
                if (argumentType.name !in EXACT_EXTERNAL_ITERABLE_TYPES) return null
                if (argumentType.name == GUAVA_IMMUTABLE_LIST && loadType(argumentType.name) != null) return null
                if (!source.allowPureIterable && argumentType.name == "java.lang.Iterable") return null
                argumentType.arguments.singleOrNull()
                    ?: throw IllegalStateException(
                        "Raw ${argumentType.name} has no exact iterable element type for '$call'"
                    )
            }
            is ExactExternalFunctionalParameterSource.ExactType -> TypeRef(source.name)
        }
    }

    private fun exactExternalMethodReturnType(
        receiver: TypeRef,
        call: MethodCallExpr,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        if (receiver.name != CATNIP_COUPLE || loadType(receiver.name) != null) return null
        if (call.nameAsString != "map" || call.arguments.size != 1) return null
        val inputType = receiver.arguments.singleOrNull()
            ?: throw IllegalStateException("Raw ${receiver.name} has no exact map input type for '$call'")
        val reference = call.arguments.single() as? MethodReferenceExpr ?: return null
        val referenceOwner = resolveExpression(reference.scope, reference, owner, substitutions)
            ?: resolveTypeScope(reference.scope, owner, substitutions)
            ?: return null
        if (referenceOwner != inputType) return null
        val outputType = exactProjectMethodReferenceReturnType(reference, referenceOwner) ?: return null
        return TypeRef(CATNIP_COUPLE, listOf(outputType))
    }

    private fun exactProjectMethodReferenceReturnType(
        reference: MethodReferenceExpr,
        receiver: TypeRef
    ): TypeRef? {
        if (reference.identifier == "new") return null
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val matches = methods(info).filter { method ->
            method.nameAsString == reference.identifier && !method.isStatic && method.parameters.isEmpty()
        }
        if (matches.size > 1) {
            throw IllegalStateException(
                "Ambiguous zero-argument project method reference '${reference.identifier}' on ${receiver.name}"
            )
        }
        val method = matches.singleOrNull() ?: return null
        return resolveLexicalType(parseType(method.typeAsString), method, info, substitutions)
    }

    private fun resolveProjectFunctionalParameterBinding(
        receiver: TypeRef,
        call: MethodCallExpr,
        argumentIndex: Int,
        lambdaParameterIndex: Int,
        callerOwner: TypeInfo,
        callerSubstitutions: Map<String, TypeRef>,
        visited: Set<String>
    ): ProjectFunctionalParameterBinding? = when (
        val resolution = resolveProjectFunctionalParameterDispatch(
            receiver,
            call,
            argumentIndex,
            lambdaParameterIndex,
            callerOwner,
            callerSubstitutions,
            visited
        )
    ) {
        is ProjectDispatchResolution.Resolved -> resolution.value
        ProjectDispatchResolution.Absent, is ProjectDispatchResolution.Unknown -> null
    }

    private fun resolveProjectFunctionalParameterDispatch(
        receiver: TypeRef,
        call: MethodCallExpr,
        argumentIndex: Int,
        lambdaParameterIndex: Int,
        callerOwner: TypeInfo,
        callerSubstitutions: Map<String, TypeRef>,
        visited: Set<String>
    ): ProjectDispatchResolution<ProjectFunctionalParameterBinding> {
        if (receiver.toString() in visited) {
            return ProjectDispatchResolution.Unknown(mayDeclareConcreteClassMethod = true)
        }
        val info = loadType(receiver.name)
            ?: return externalMethodDispatch(receiver.name, call.nameAsString, MethodOwnerKind.CLASS)
        val ownerKind = methodOwnerKind(info)
        val substitutions = substitutions(info, receiver)
        val methods = methods(info).filter {
            it.nameAsString == call.nameAsString && it.parameters.size == call.arguments.size
        }
        val lambdaArity = (call.arguments[argumentIndex] as? LambdaExpr)?.parameters?.size
            ?: return ProjectDispatchResolution.Unknown(ownerKind == MethodOwnerKind.CLASS)
        var unresolvedDirectCandidate = false
        val matching = methods.mapNotNull candidates@{ method ->
            call.arguments.forEachIndexed { index, argument ->
                if (index == argumentIndex) return@forEachIndexed
                val actual = resolveArgumentValue(argument, call, callerOwner, callerSubstitutions)
                    ?: run {
                        unresolvedDirectCandidate = true
                        return@candidates null
                    }
                val expected = resolveType(parseType(method.parameters[index].typeAsString), info, substitutions)
                    ?: run {
                        unresolvedDirectCandidate = true
                        return@candidates null
                    }
                if (!argumentValueMatchesType(actual, expected)) return@candidates null
            }
            val functionalType = resolveType(
                parseType(method.parameters[argumentIndex].typeAsString),
                info,
                substitutions
            ) ?: run {
                unresolvedDirectCandidate = true
                return@candidates null
            }
            val parameterTypes = exactFunctionalParameterTypes(functionalType) ?: run {
                unresolvedDirectCandidate = true
                return@candidates null
            }
            method.takeIf { parameterTypes.size == lambdaArity }
        }
        if (unresolvedDirectCandidate || matching.size > 1) {
            return ProjectDispatchResolution.Unknown(ownerKind == MethodOwnerKind.CLASS)
        }
        matching.singleOrNull()?.let { method ->
            val functionalType = resolveType(
                parseType(method.parameters[argumentIndex].typeAsString),
                info,
                substitutions
            ) ?: return ProjectDispatchResolution.Unknown(ownerKind == MethodOwnerKind.CLASS)
            val parameterType = exactFunctionalParameterTypes(functionalType)
                ?.getOrNull(lambdaParameterIndex)
                ?: return ProjectDispatchResolution.Unknown(ownerKind == MethodOwnerKind.CLASS)
            return ProjectDispatchResolution.Resolved(
                ProjectFunctionalParameterBinding(info.qualifiedName, method, parameterType),
                ownerKind
            )
        }
        val nextVisited = visited + receiver.toString()
        val inherited = directSuperBranches(info).map { branch ->
            val superType = resolveDirectSuperType(branch.raw, info, substitutions)
                ?: return@map ProjectDispatchResolution.Unknown(
                    branch.ownerKind == MethodOwnerKind.CLASS
                )
            if (loadType(superType.name) == null) {
                externalMethodDispatch(superType.name, call.nameAsString, branch.ownerKind)
            } else {
                resolveProjectFunctionalParameterDispatch(
                    superType,
                    call,
                    argumentIndex,
                    lambdaParameterIndex,
                    callerOwner,
                    callerSubstitutions,
                    nextVisited
                )
            }
        }
        return collapseProjectDispatch(
            inherited,
            ::projectFunctionalBindingKey
        ) { implementation, contract ->
            concreteClassMethodSuppressesInterfaceMethod(
                receiver.name,
                implementation.declaration,
                contract.declaration
            )
        }
    }

    private fun resolveProjectFunctionalParameterType(
        receiver: TypeRef,
        call: MethodCallExpr,
        argumentIndex: Int,
        lambdaParameterIndex: Int,
        callerOwner: TypeInfo,
        callerSubstitutions: Map<String, TypeRef>,
        visited: Set<String>
    ): TypeRef? {
        if (receiver.toString() in visited) return null
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val methods = methods(info).filter {
            it.nameAsString == call.nameAsString && it.parameters.size == call.arguments.size
        }
        val lambdaArity = (call.arguments[argumentIndex] as? LambdaExpr)?.parameters?.size ?: return null
        val matchingTypes = methods.mapNotNull candidates@{ method ->
            val nonFunctionalArgumentsMatch = call.arguments.withIndex()
                .filter { it.index != argumentIndex }
                .all { (index, argument) ->
                    val actual = resolveArgumentValue(argument, call, callerOwner, callerSubstitutions)
                        ?: return@candidates null
                    val expected = resolveType(parseType(method.parameters[index].typeAsString), info, substitutions)
                        ?: return@candidates null
                    argumentValueMatchesType(actual, expected)
                }
            if (!nonFunctionalArgumentsMatch) return@candidates null
            val functionalType = resolveType(
                parseType(method.parameters[argumentIndex].typeAsString),
                info,
                substitutions
            ) ?: return@candidates null
            val parameters = exactFunctionalParameterTypes(functionalType) ?: return@candidates null
            parameters.getOrNull(lambdaParameterIndex).takeIf { parameters.size == lambdaArity }
        }.distinct()
        if (matchingTypes.size > 1) {
            throw IllegalStateException(
                "Ambiguous functional parameter type '${call.nameAsString}/${call.arguments.size}' on ${receiver.name}"
            )
        }
        matchingTypes.singleOrNull()?.let { return it }
        val nextVisited = visited + receiver.toString()
        val inheritedTypes = resolvedSupers(info, substitutions).mapNotNull { superType ->
            resolveProjectFunctionalParameterType(
                superType,
                call,
                argumentIndex,
                lambdaParameterIndex,
                callerOwner,
                callerSubstitutions,
                nextVisited
            )
        }.distinct()
        if (inheritedTypes.size > 1) {
            throw IllegalStateException(
                "Ambiguous inherited functional parameter type '${call.nameAsString}/${call.arguments.size}' on ${receiver.name}"
            )
        }
        return inheritedTypes.singleOrNull()
    }

    private fun exactFunctionalParameterTypes(type: TypeRef): List<TypeRef>? {
        knownFunctionalMethod(type)?.let { return it.parameterTypes }
        val methods = collectProjectAbstractMethods(type, mutableSetOf()) ?: return null
        val contracts = methods
            .filterNot(::isPublicObjectMethod)
            .groupBy { it.name to it.parameterTypes }
        if (contracts.size != 1) return null
        val returnTypes = contracts.values.single().map { it.returnType }.distinct()
        if (returnTypes.size > 1 && returnTypes.none { candidate ->
                returnTypes.all { other -> isAssignableTo(candidate, other.name, mutableSetOf()) }
            }) {
            return null
        }
        return contracts.keys.single().second
    }

    private fun collectProjectAbstractMethods(
        receiver: TypeRef,
        visited: MutableSet<String>
    ): List<FunctionalMethodSignature>? {
        knownFunctionalMethod(receiver)?.let { return listOf(it) }
        if (!visited.add(receiver.toString())) return emptyList()
        val info = loadType(receiver.name) ?: return null
        val declaration = info.declaration as? ClassOrInterfaceDeclaration ?: return null
        if (!declaration.isInterface) return null
        val substitutions = substitutions(info, receiver)
        val direct = methods(info).filter { method ->
            !method.isStatic && !method.isPrivate && !method.isDefault && !method.body.isPresent
        }.map { method ->
            FunctionalMethodSignature(
                method.nameAsString,
                method.parameters.map { parameter ->
                    resolveType(parseType(parameter.typeAsString), info, substitutions) ?: return null
                },
                resolveType(parseType(method.typeAsString), info, substitutions) ?: return null
            )
        }
        val inherited = resolvedSupers(info, substitutions).flatMap { superType ->
            collectProjectAbstractMethods(superType, visited) ?: return null
        }
        return direct + inherited
    }

    private fun knownFunctionalMethod(type: TypeRef): FunctionalMethodSignature? {
        val arguments = type.arguments
        return when (type.name) {
            "java.lang.Runnable" -> FunctionalMethodSignature("run", emptyList(), TypeRef("void"))
            "java.util.concurrent.Callable" -> arguments.singleOrNull()?.let {
                FunctionalMethodSignature("call", emptyList(), it)
            }
            "java.util.Comparator" -> arguments.singleOrNull()?.let {
                FunctionalMethodSignature("compare", listOf(it, it), TypeRef("int"))
            }
            "java.util.function.Supplier" -> arguments.singleOrNull()?.let {
                FunctionalMethodSignature("get", emptyList(), it)
            }
            "java.util.function.Consumer" -> arguments.singleOrNull()?.let {
                FunctionalMethodSignature("accept", listOf(it), TypeRef("void"))
            }
            "java.util.function.Predicate" -> arguments.singleOrNull()?.let {
                FunctionalMethodSignature("test", listOf(it), TypeRef("boolean"))
            }
            "java.util.function.Function" -> arguments.takeIf { it.size == 2 }?.let {
                FunctionalMethodSignature("apply", listOf(it[0]), it[1])
            }
            "java.util.function.UnaryOperator" -> arguments.singleOrNull()?.let {
                FunctionalMethodSignature("apply", listOf(it), it)
            }
            "java.util.function.BiConsumer" -> arguments.takeIf { it.size == 2 }?.let {
                FunctionalMethodSignature("accept", it, TypeRef("void"))
            }
            "java.util.function.BiPredicate" -> arguments.takeIf { it.size == 2 }?.let {
                FunctionalMethodSignature("test", it, TypeRef("boolean"))
            }
            "java.util.function.BiFunction" -> arguments.takeIf { it.size == 3 }?.let {
                FunctionalMethodSignature("apply", it.take(2), it[2])
            }
            "java.util.function.BinaryOperator" -> arguments.singleOrNull()?.let {
                FunctionalMethodSignature("apply", listOf(it, it), it)
            }
            else -> null
        }
    }

    private fun isPublicObjectMethod(method: FunctionalMethodSignature): Boolean = when {
        method.name == "equals" && method.parameterTypes == listOf(TypeRef("java.lang.Object")) -> true
        method.name == "hashCode" && method.parameterTypes.isEmpty() -> true
        method.name == "toString" && method.parameterTypes.isEmpty() -> true
        else -> false
    }

    private fun resolveField(receiver: TypeRef, name: String, visited: MutableSet<String>): TypeRef? {
        if (!visited.add(receiver.toString())) return null
        knownIndexedGenericFields[receiver.name]?.get(name)?.let { argumentIndex ->
            return receiver.arguments.getOrNull(argumentIndex)
                ?: throw IllegalStateException(
                    "Raw ${receiver.name} receiver has no exact generic type for field '$name'"
                )
        }
        knownFields[receiver.name]?.get(name)?.let { field ->
            val generic = receiver.arguments.singleOrNull()
            return substitute(field, if (generic == null) emptyMap() else mapOf("T" to generic))
        }
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val matchingFields = fields(info).flatMap { it.variables }.filter { it.nameAsString == name }
        if (matchingFields.size > 1) throw IllegalStateException("Ambiguous field '$name' on ${receiver.name}")
        matchingFields.singleOrNull()?.let { field ->
            return resolveType(parseType(field.typeAsString), info, substitutions)
        }
        val recordComponents = (info.declaration as? RecordDeclaration)?.parameters
            ?.filter { it.nameAsString == name }
            .orEmpty()
        if (recordComponents.size > 1) {
            throw IllegalStateException("Ambiguous record component '$name' on ${receiver.name}")
        }
        recordComponents.singleOrNull()?.let { component ->
            return resolveType(parseType(component.typeAsString), info, substitutions)
        }
        return exactSingle(
            resolvedSupers(info, substitutions).mapNotNull { resolveField(it, name, visited) },
            "field '$name' inherited by ${receiver.name}"
        )
    }

    private fun exactFieldStaticness(
        receiver: TypeRef,
        name: String,
        visited: MutableSet<String>
    ): Boolean? {
        val key = "${receiver.name}#$name"
        if (!visited.add(key)) return null
        val info = loadType(receiver.name) ?: return null
        val direct = fields(info).filter { declaration ->
            declaration.variables.any { it.nameAsString == name }
        }
        if (direct.size > 1) {
            throw IllegalStateException("Ambiguous field '$name' on ${receiver.name}")
        }
        direct.singleOrNull()?.let { return it.isStatic }
        return exactSingle(
            resolvedSupers(info, substitutions(info, receiver)).mapNotNull { inherited ->
                exactFieldStaticness(inherited, name, visited)
            },
            "field '$name' inherited by ${receiver.name}"
        )
    }

    private fun methodOwnerKind(info: TypeInfo): MethodOwnerKind = when (val declaration = info.declaration) {
        is ClassOrInterfaceDeclaration ->
            if (declaration.isInterface) MethodOwnerKind.INTERFACE else MethodOwnerKind.CLASS
        is AnnotationDeclaration -> MethodOwnerKind.INTERFACE
        else -> MethodOwnerKind.CLASS
    }

    private fun directSuperBranches(info: TypeInfo): List<DirectSuperBranch> {
        val declaration = info.declaration
        return when (declaration) {
            is ClassOrInterfaceDeclaration -> {
                if (declaration.isInterface) {
                    info.directSupers.map { DirectSuperBranch(it, MethodOwnerKind.INTERFACE) }
                } else {
                    val explicitClassCount = declaration.extendedTypes.size
                    val explicit = info.directSupers.mapIndexed { index, raw ->
                        DirectSuperBranch(
                            raw,
                            if (index < explicitClassCount) MethodOwnerKind.CLASS else MethodOwnerKind.INTERFACE
                        )
                    }
                    if (explicitClassCount == 0) {
                        listOf(DirectSuperBranch(TypeRef("java.lang.Object"), MethodOwnerKind.CLASS)) + explicit
                    } else {
                        explicit
                    }
                }
            }
            is RecordDeclaration ->
                listOf(DirectSuperBranch(TypeRef("java.lang.Record"), MethodOwnerKind.CLASS)) +
                    info.directSupers.map { DirectSuperBranch(it, MethodOwnerKind.INTERFACE) }
            is EnumDeclaration ->
                listOf(DirectSuperBranch(TypeRef("java.lang.Enum"), MethodOwnerKind.CLASS)) +
                    info.directSupers.map { DirectSuperBranch(it, MethodOwnerKind.INTERFACE) }
            else -> info.directSupers.map { DirectSuperBranch(it, MethodOwnerKind.INTERFACE) }
        }
    }

    private fun externalMethodDispatch(
        qualifiedName: String,
        methodName: String,
        ownerKind: MethodOwnerKind
    ): ProjectDispatchResolution<Nothing> {
        val closedNames = knownClosedExternalMethodNames[qualifiedName]
            ?: return ProjectDispatchResolution.Unknown(ownerKind == MethodOwnerKind.CLASS)
        return if (methodName in closedNames) {
            ProjectDispatchResolution.Unknown(ownerKind == MethodOwnerKind.CLASS)
        } else {
            ProjectDispatchResolution.Absent
        }
    }

    private fun <T> collapseProjectDispatch(
        resolutions: List<ProjectDispatchResolution<T>>,
        key: (T) -> String,
        classSuppressesInterface: (implementation: T, contract: T) -> Boolean
    ): ProjectDispatchResolution<T> {
        val resolved = buildList<ProjectDispatchResolution.Resolved<T>> {
            resolutions.forEach { resolution ->
                if (resolution is ProjectDispatchResolution.Resolved) add(resolution)
            }
        }
        val unknown = resolutions.filterIsInstance<ProjectDispatchResolution.Unknown>()
        if (unknown.isNotEmpty()) {
            return ProjectDispatchResolution.Unknown(
                unknown.any { it.mayDeclareConcreteClassMethod }
            )
        }
        val classCandidates = resolved
            .filter { it.ownerKind == MethodOwnerKind.CLASS }
            .distinctBy { key(it.value) }
        if (classCandidates.size > 1) {
            return ProjectDispatchResolution.Unknown(mayDeclareConcreteClassMethod = true)
        }
        val interfaceCandidates = resolved
            .filter { it.ownerKind == MethodOwnerKind.INTERFACE }
            .distinctBy { key(it.value) }
        classCandidates.singleOrNull()?.let { implementation ->
            if (interfaceCandidates.all { contract ->
                    classSuppressesInterface(implementation.value, contract.value)
                }
            ) {
                return implementation
            }
            return ProjectDispatchResolution.Unknown(mayDeclareConcreteClassMethod = true)
        }
        return when (interfaceCandidates.size) {
            0 -> ProjectDispatchResolution.Absent
            1 -> interfaceCandidates.single()
            else -> ProjectDispatchResolution.Unknown(mayDeclareConcreteClassMethod = false)
        }
    }

    private fun projectMethodBindingKey(binding: ProjectMethodBinding): String =
        "${binding.owner}#${binding.declaration.nameAsString}(" +
            binding.declaration.parameters.joinToString(",") { it.typeAsString } + ")"

    private fun projectFunctionalBindingKey(binding: ProjectFunctionalParameterBinding): String =
        projectMethodBindingKey(ProjectMethodBinding(binding.owner, binding.declaration)) +
            "->${binding.parameterType}"

    private fun resolvedSupers(info: TypeInfo, substitutions: Map<String, TypeRef>): List<TypeRef> =
        info.directSupers.mapNotNull { raw -> resolveDirectSuperType(raw, info, substitutions) }

    private fun resolveDirectSuperType(
        raw: TypeRef,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>,
        resolvingBounds: MutableSet<String> = mutableSetOf()
    ): TypeRef? {
        if (raw.name in primitiveTypes) return raw
        substitutions[raw.name]?.let { return it.copy(arrayDepth = it.arrayDepth + raw.arrayDepth) }
        owner.typeParameterBounds[raw.name]?.let { bound ->
            if (!resolvingBounds.add(raw.name)) return null
            val resolved = resolveDirectSuperType(bound, owner, substitutions, resolvingBounds)
            resolvingBounds.remove(raw.name)
            return resolved?.copy(arrayDepth = resolved.arrayDepth + raw.arrayDepth)
        }
        val qualifiedName = when (val resolved = resolveDirectSuper(raw, owner)) {
            is DirectSuperLookup.Project -> resolved.info.qualifiedName
            is DirectSuperLookup.External -> resolved.qualifiedName
            DirectSuperLookup.Unknown -> return null
        }
        val arguments = raw.arguments.map { argument ->
            resolveDirectSuperType(argument, owner, substitutions, resolvingBounds) ?: return null
        }
        return TypeRef(qualifiedName, arguments, raw.arrayDepth)
    }

    private fun eraseDeclarationType(
        raw: TypeRef,
        callable: CallableDeclaration<*>,
        owner: TypeInfo,
        scope: DeclarationErasureScope = DeclarationErasureScope.CALLABLE,
        resolvingTypeVariables: MutableSet<String> = mutableSetOf()
    ): TypeRef? {
        val callableParameter = callable.typeParameters
            .takeIf { scope == DeclarationErasureScope.CALLABLE }
            ?.singleOrNull { it.nameAsString == raw.name }
        if (callableParameter != null) {
            val key = "callable:${System.identityHashCode(callable)}:${raw.name}"
            if (!resolvingTypeVariables.add(key)) return null
            val bound = callableParameter.typeBound.firstOrNull()
                ?.let { parseType(it.asString()) }
                ?: TypeRef("java.lang.Object")
            val resolved = eraseDeclarationType(
                bound,
                callable,
                owner,
                DeclarationErasureScope.CALLABLE,
                resolvingTypeVariables
            )
            resolvingTypeVariables.remove(key)
            return resolved?.copy(
                arguments = emptyList(),
                arrayDepth = resolved.arrayDepth + raw.arrayDepth
            )
        }
        if (raw.name in owner.typeParameters) {
            val key = "owner:${owner.qualifiedName}:${raw.name}"
            if (!resolvingTypeVariables.add(key)) return null
            val bound = owner.typeParameterBounds[raw.name] ?: TypeRef("java.lang.Object")
            val resolved = eraseDeclarationType(
                bound,
                callable,
                owner,
                DeclarationErasureScope.OWNER_HEADER,
                resolvingTypeVariables
            )
            resolvingTypeVariables.remove(key)
            return resolved?.copy(
                arguments = emptyList(),
                arrayDepth = resolved.arrayDepth + raw.arrayDepth
            )
        }
        val qualified = when (scope) {
            DeclarationErasureScope.CALLABLE -> qualify(raw.name, owner)
            DeclarationErasureScope.OWNER_HEADER -> qualifyDirectSuper(raw.name, owner)
        } ?: return null
        return TypeRef(qualified, arrayDepth = raw.arrayDepth)
    }

    private fun resolveLexicalType(
        raw: TypeRef,
        use: Node,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>,
        resolvingCallableBounds: MutableSet<String> = mutableSetOf()
    ): TypeRef? {
        var cursor: Node? = use
        var callable: CallableDeclaration<*>? = null
        while (cursor != null) {
            if (cursor is CallableDeclaration<*>) {
                callable = cursor
                break
            }
            cursor = cursor.parentNode.orElse(null)
        }
        val exactCallable = callable
        val callableParameter = exactCallable?.typeParameters?.singleOrNull { it.nameAsString == raw.name }
        if (callableParameter != null) {
            val callableNode = requireNotNull(exactCallable)
            val key = "${System.identityHashCode(callableNode)}:${raw.name}"
            if (!resolvingCallableBounds.add(key)) return null
            val bound = callableParameter.typeBound.firstOrNull()?.asString() ?: "java.lang.Object"
            val resolved = resolveLexicalType(
                parseType(bound),
                callableNode,
                owner,
                substitutions,
                resolvingCallableBounds
            )
            resolvingCallableBounds.remove(key)
            return resolved?.copy(arrayDepth = resolved.arrayDepth + raw.arrayDepth)
        }
        val resolvedArguments = raw.arguments.map { argument ->
            resolveLexicalType(
                argument,
                use,
                owner,
                substitutions,
                resolvingCallableBounds
            ) ?: return null
        }
        val resolvedRaw = resolveType(raw.copy(arguments = emptyList()), owner, substitutions) ?: return null
        return resolvedRaw.copy(arguments = resolvedArguments)
    }

    private fun resolveType(
        raw: TypeRef,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? = resolveType(raw, owner, substitutions, mutableSetOf())

    private fun resolveType(
        raw: TypeRef,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>,
        resolvingBounds: MutableSet<String>
    ): TypeRef? {
        substitutions[raw.name]?.let { return it.copy(arrayDepth = it.arrayDepth + raw.arrayDepth) }
        owner.typeParameterBounds[raw.name]?.let { bound ->
            if (!resolvingBounds.add(raw.name)) return null
            val resolved = resolveType(bound, owner, substitutions, resolvingBounds)
            resolvingBounds.remove(raw.name)
            return resolved?.let {
                it.copy(arrayDepth = it.arrayDepth + raw.arrayDepth)
            }
        }
        val qualified = qualify(raw.name, owner) ?: return null
        val arguments = mutableListOf<TypeRef>()
        raw.arguments.forEach { argument ->
            arguments += resolveType(argument, owner, substitutions, resolvingBounds) ?: return null
        }
        return TypeRef(qualified, arguments, raw.arrayDepth)
    }

    private fun qualify(name: String, owner: TypeInfo): String? {
        val unit = owner.unit
        val packageName = packageName(unit)
        if (name in primitiveTypes) return name
        if (name.contains('.')) {
            val first = name.substringBefore('.')
            when (val lexicalHead = lexicalMemberType(first, owner)) {
                is MemberTypeLookup.Resolved -> {
                    return resolveMemberTypePath(
                        lexicalHead.qualifiedName,
                        name.substringAfter('.').split('.'),
                        owner
                    )
                }
                MemberTypeLookup.Unknown -> return null
                MemberTypeLookup.Absent -> Unit
            }
            unit.imports
                .filter { !it.isStatic && !it.isAsterisk && it.name.identifier == first }
                .map { it.nameAsString }
                .distinct()
                .singleOrNull()
                ?.let { imported -> return imported + name.removePrefix(first) }
            val samePackage = if (packageName.isEmpty()) name else "$packageName.$name"
            if (loadType(samePackage) != null) return samePackage
            if (name.substringBefore('.').firstOrNull()?.isLowerCase() == true) return name
            return null
        }
        when (val lexical = lexicalMemberType(name, owner)) {
            is MemberTypeLookup.Resolved -> return lexical.qualifiedName
            MemberTypeLookup.Unknown -> return null
            MemberTypeLookup.Absent -> Unit
        }
        unit.types
            .filter { it.nameAsString == name }
            .mapNotNull { it.fullyQualifiedName.orElse(null) }
            .distinct()
            .let { declarations ->
                if (declarations.size > 1) {
                    throw IllegalStateException("Ambiguous compilation-unit type '$name': $declarations")
                }
                declarations.singleOrNull()?.let { return it }
            }
        unit.imports
            .filter { !it.isStatic && !it.isAsterisk && it.name.identifier == name }
            .map { it.nameAsString }
            .distinct()
            .singleOrNull()
            ?.let { return it }
        val samePackage = if (packageName.isEmpty()) name else "$packageName.$name"
        if (loadType(samePackage) != null) return samePackage
        implicitJavaLangTypes[name]?.let { return it }
        val wildcardMatches = unit.imports.filter { !it.isStatic && it.isAsterisk }
            .mapNotNull { import ->
                val qualified = "${import.nameAsString}.$name"
                qualified.takeIf { knownExternalTypes[name] == qualified || loadType(qualified) != null }
            }
            .distinct()
        if (wildcardMatches.size > 1) {
            throw IllegalStateException("Ambiguous wildcard project type '$name': $wildcardMatches")
        }
        return wildcardMatches.singleOrNull()
    }

    private fun lexicalMemberType(name: String, owner: TypeInfo): MemberTypeLookup {
        var scope: TypeInfo? = owner
        while (scope != null) {
            val exactScope = scope
            val declarations = buildList {
                if (exactScope.declaration.nameAsString == name) add(exactScope.qualifiedName)
                addAll(immediateMemberTypes(exactScope, name).map { it.qualifiedName })
            }.distinct()
            if (declarations.size > 1) {
                throw IllegalStateException(
                    "Ambiguous lexical member type '$name' in ${exactScope.qualifiedName}: $declarations"
                )
            }
            declarations.singleOrNull()?.let { return MemberTypeLookup.Resolved(it) }
            when (val inherited = inheritedMemberType(name, exactScope, emptySet())) {
                is MemberTypeLookup.Resolved -> return inherited
                MemberTypeLookup.Unknown -> return MemberTypeLookup.Unknown
                MemberTypeLookup.Absent -> Unit
            }
            val enclosing = enclosingType(exactScope.declaration)
            scope = enclosing?.let { typeInfo(it) }
            if (enclosing != null && scope == null) return MemberTypeLookup.Unknown
        }
        return MemberTypeLookup.Absent
    }

    private fun inheritedMemberType(
        name: String,
        inheritor: TypeInfo,
        visiting: Set<String>
    ): MemberTypeLookup {
        val cacheKey = inheritor.qualifiedName to name
        if (visiting.isEmpty()) inheritedMemberTypeCache[cacheKey]?.let { return it }
        val key = "${inheritor.qualifiedName}#$name"
        if (key in visiting) return MemberTypeLookup.Unknown
        fun finish(result: MemberTypeLookup): MemberTypeLookup {
            if (visiting.isEmpty()) inheritedMemberTypeCache[cacheKey] = result
            return result
        }
        if (inheritor.directSupers.isEmpty()) return finish(MemberTypeLookup.Absent)
        val nextVisiting = visiting + key
        val candidates = mutableListOf<String>()
        var unknown = false
        inheritor.directSupers.forEach { rawSuper ->
            val branch = when (val directSuper = resolveDirectSuper(rawSuper, inheritor)) {
                is DirectSuperLookup.Project -> {
                    val superInfo = directSuper.info
                    val declared = immediateMemberTypes(superInfo, name)
                    if (declared.size > 1) {
                        throw IllegalStateException(
                            "Ambiguous member type '$name' declared on ${superInfo.qualifiedName}: " +
                                declared.map { it.qualifiedName }
                        )
                    }
                    declared.singleOrNull()?.let { candidate ->
                        if (isMemberTypeInheritedBy(candidate, inheritor)) {
                            MemberTypeLookup.Resolved(candidate.qualifiedName)
                        } else {
                            MemberTypeLookup.Absent
                        }
                    } ?: inheritedMemberType(name, superInfo, nextVisiting)
                }
                is DirectSuperLookup.External -> {
                    ExactExternalTypeContracts.publicMemberType(directSuper.qualifiedName, name)
                        ?.let { MemberTypeLookup.Resolved(it) }
                        ?: MemberTypeLookup.Absent
                }
                DirectSuperLookup.Unknown -> {
                    unknown = true
                    return@forEach
                }
            }
            when (branch) {
                is MemberTypeLookup.Resolved -> {
                    val candidate = loadType(branch.qualifiedName)
                    if (candidate != null && isMemberTypeInheritedBy(candidate, inheritor)) {
                        candidates += candidate.qualifiedName
                    } else if (candidate == null &&
                        ExactExternalTypeContracts.isPublicMemberType(branch.qualifiedName)
                    ) {
                        candidates += branch.qualifiedName
                    } else if (candidate == null) {
                        unknown = true
                    }
                }
                MemberTypeLookup.Unknown -> unknown = true
                MemberTypeLookup.Absent -> Unit
            }
        }
        val distinct = candidates.distinct()
        if (distinct.size > 1) {
            throw IllegalStateException(
                "Ambiguous inherited member type '$name' on ${inheritor.qualifiedName}: $distinct"
            )
        }
        if (unknown) return finish(MemberTypeLookup.Unknown)
        return finish(distinct.singleOrNull()?.let { MemberTypeLookup.Resolved(it) } ?: MemberTypeLookup.Absent)
    }

    private fun immediateMemberTypes(owner: TypeInfo, name: String): List<TypeInfo> =
        owner.declaration.members.filterIsInstance<TypeDeclaration<*>>()
            .filter { it.nameAsString == name }
            .mapNotNull { typeInfo(it) }
            .distinctBy { it.qualifiedName }

    private fun resolveMemberTypePath(
        head: String,
        segments: List<String>,
        useOwner: TypeInfo
    ): String? {
        var current = loadType(head) ?: return null
        segments.forEach { segment ->
            when (val member = memberTypeOnType(segment, current, useOwner)) {
                is MemberTypeLookup.Resolved -> current = loadType(member.qualifiedName) ?: return null
                MemberTypeLookup.Absent, MemberTypeLookup.Unknown -> return null
            }
        }
        return current.qualifiedName
    }

    private fun memberTypeOnType(
        name: String,
        target: TypeInfo,
        useOwner: TypeInfo
    ): MemberTypeLookup {
        val declared = immediateMemberTypes(target, name)
        if (declared.size > 1) {
            throw IllegalStateException(
                "Ambiguous member type '$name' declared on ${target.qualifiedName}: " +
                    declared.map { it.qualifiedName }
            )
        }
        declared.singleOrNull()?.let { candidate ->
            return if (isMemberTypeAccessibleFrom(candidate, useOwner)) {
                MemberTypeLookup.Resolved(candidate.qualifiedName)
            } else {
                MemberTypeLookup.Absent
            }
        }
        return when (val inherited = inheritedMemberType(name, target, emptySet())) {
            is MemberTypeLookup.Resolved -> {
                val candidate = loadType(inherited.qualifiedName) ?: return MemberTypeLookup.Unknown
                if (isMemberTypeAccessibleFrom(candidate, useOwner)) inherited else MemberTypeLookup.Absent
            }
            MemberTypeLookup.Absent -> MemberTypeLookup.Absent
            MemberTypeLookup.Unknown -> MemberTypeLookup.Unknown
        }
    }

    private fun isMemberTypeInheritedBy(member: TypeInfo, inheritor: TypeInfo): Boolean {
        if (member.declaration.isPrivate) return false
        if (isImplicitlyPublicMemberType(member) || member.declaration.isPublic || member.declaration.isProtected) {
            return true
        }
        return packageName(member.unit) == packageName(inheritor.unit)
    }

    private fun isMemberTypeAccessibleFrom(member: TypeInfo, useOwner: TypeInfo): Boolean {
        val declaringOwner = memberDeclaringType(member) ?: return false
        if (isImplicitlyPublicMemberType(member) || member.declaration.isPublic) return true
        if (member.declaration.isPrivate) {
            return topLevelType(declaringOwner.declaration) === topLevelType(useOwner.declaration)
        }
        val samePackage = packageName(declaringOwner.unit) == packageName(useOwner.unit)
        if (!member.declaration.isProtected) return samePackage
        if (samePackage) return true
        var lexicalOwner: TypeInfo? = useOwner
        while (lexicalOwner != null) {
            if (isProjectSubtype(lexicalOwner, declaringOwner.qualifiedName, emptySet()) == true) return true
            lexicalOwner = enclosingType(lexicalOwner.declaration)?.let { typeInfo(it) }
        }
        return false
    }

    private fun isImplicitlyPublicMemberType(member: TypeInfo): Boolean {
        val declaringOwner = memberDeclaringType(member)?.declaration ?: return false
        return (declaringOwner is ClassOrInterfaceDeclaration && declaringOwner.isInterface) ||
            declaringOwner is AnnotationDeclaration
    }

    private fun memberDeclaringType(member: TypeInfo): TypeInfo? =
        enclosingType(member.declaration)?.let { typeInfo(it) }

    private fun isProjectSubtype(actual: TypeInfo, expected: String, visiting: Set<String>): Boolean? {
        if (actual.qualifiedName == expected) return true
        if (actual.qualifiedName in visiting) return null
        if (actual.directSupers.isEmpty()) return false
        val nextVisiting = visiting + actual.qualifiedName
        var unknown = false
        actual.directSupers.forEach { rawSuper ->
            when (val directSuper = resolveDirectSuper(rawSuper, actual)) {
                is DirectSuperLookup.Project -> when (
                    isProjectSubtype(directSuper.info, expected, nextVisiting)
                ) {
                    true -> return true
                    null -> unknown = true
                    false -> Unit
                }
                is DirectSuperLookup.External -> Unit
                DirectSuperLookup.Unknown -> unknown = true
            }
        }
        return if (unknown) null else false
    }

    private fun resolveDirectSuper(raw: TypeRef, owner: TypeInfo): DirectSuperLookup {
        val qualified = qualifyDirectSuper(raw.name, owner) ?: return DirectSuperLookup.Unknown
        return loadType(qualified)?.let { DirectSuperLookup.Project(it) }
            ?: DirectSuperLookup.External(qualified)
    }

    private fun qualifyDirectSuper(name: String, owner: TypeInfo): String? {
        val unit = owner.unit
        val packageName = packageName(unit)
        if (name.contains('.')) {
            val first = name.substringBefore('.')
            when (val lexicalHead = directSuperLexicalType(first, owner)) {
                is MemberTypeLookup.Resolved -> {
                    val qualified = lexicalHead.qualifiedName + name.removePrefix(first)
                    return qualified.takeIf {
                        loadType(it) != null || ExactExternalTypeContracts.isPublicMemberType(it)
                    }
                }
                MemberTypeLookup.Unknown -> return null
                MemberTypeLookup.Absent -> Unit
            }
            unit.imports.singleOrNull {
                !it.isStatic && !it.isAsterisk && it.name.identifier == first
            }?.nameAsString?.let { imported ->
                return imported + name.removePrefix(first)
            }
            val samePackage = if (packageName.isEmpty()) name else "$packageName.$name"
            if (loadType(samePackage) != null) return samePackage
            if (first.firstOrNull()?.isLowerCase() == true) return name
            return null
        }
        when (val lexical = directSuperLexicalType(name, owner)) {
            is MemberTypeLookup.Resolved -> return lexical.qualifiedName
            MemberTypeLookup.Unknown -> return null
            MemberTypeLookup.Absent -> Unit
        }
        unit.types
            .filter { it.nameAsString == name }
            .mapNotNull { it.fullyQualifiedName.orElse(null) }
            .distinct()
            .let { declarations ->
                if (declarations.size > 1) {
                    throw IllegalStateException("Ambiguous direct-super compilation-unit type '$name': $declarations")
                }
                declarations.singleOrNull()?.let { return it }
            }
        unit.imports.singleOrNull { !it.isStatic && !it.isAsterisk && it.name.identifier == name }
            ?.nameAsString?.let { return it }
        val samePackage = if (packageName.isEmpty()) name else "$packageName.$name"
        if (loadType(samePackage) != null) return samePackage
        implicitJavaLangTypes[name]?.let { return it }
        val wildcardMatches = unit.imports.filter { !it.isStatic && it.isAsterisk }
            .mapNotNull { import ->
                val qualified = "${import.nameAsString}.$name"
                qualified.takeIf { knownExternalTypes[name] == qualified || loadType(qualified) != null }
            }
            .distinct()
        if (wildcardMatches.size > 1) {
            throw IllegalStateException("Ambiguous direct-super wildcard project type '$name': $wildcardMatches")
        }
        return wildcardMatches.singleOrNull()
    }

    private fun directSuperLexicalType(name: String, owner: TypeInfo): MemberTypeLookup {
        val enclosing = enclosingType(owner.declaration) ?: return MemberTypeLookup.Absent
        val enclosingInfo = typeInfo(enclosing) ?: return MemberTypeLookup.Unknown
        return lexicalMemberType(name, enclosingInfo)
    }


    private fun packageName(unit: CompilationUnit): String =
        unit.packageDeclaration.map { it.nameAsString }.orElse("")

    private fun topLevelType(declaration: TypeDeclaration<*>): TypeDeclaration<*> {
        var top = declaration
        while (true) {
            top = enclosingType(top) ?: return top
        }
    }

    private fun enclosingType(declaration: TypeDeclaration<*>): TypeDeclaration<*>? {
        var cursor = declaration.parentNode.orElse(null)
        while (cursor != null) {
            if (cursor is TypeDeclaration<*>) return cursor
            cursor = cursor.parentNode.orElse(null)
        }
        return null
    }

    private fun substitutions(info: TypeInfo, receiver: TypeRef): Map<String, TypeRef> {
        if (receiver.arguments.isNotEmpty() && receiver.arguments.size != info.typeParameters.size) {
            throw IllegalStateException("Generic arity mismatch for ${receiver.name}: ${receiver.arguments}")
        }
        return info.typeParameters.mapIndexedNotNull { index, name ->
            receiver.arguments.getOrNull(index)?.let { name to it }
        }.toMap()
    }

    private fun substitute(type: TypeRef, substitutions: Map<String, TypeRef>): TypeRef =
        substitutions[type.name]?.let { it.copy(arrayDepth = it.arrayDepth + type.arrayDepth) }
            ?: TypeRef(type.name, type.arguments.map { substitute(it, substitutions) }, type.arrayDepth)

    private fun visibleBefore(variable: VariableDeclarator, use: Node): Boolean {
        val declaration = variable.range.orElse(null)?.begin ?: return false
        val usePosition = use.range.orElse(null)?.begin ?: return false
        if (!declaration.isBefore(usePosition)) return false
        val callable = variable.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return false
        return callable === use.findAncestor(CallableDeclaration::class.java).orElse(null)
    }

    private fun lexicalScopeDistance(variable: VariableDeclarator, use: Node): Int? {
        val scopeKinds = setOf(
            "BlockStmt", "CatchClause", "ForEachStmt", "ForStmt", "LambdaExpr", "SwitchEntry", "TryStmt"
        )
        var scope: Node? = variable.parentNode.orElse(null)
        while (scope != null && scope !is CallableDeclaration<*> && scope.javaClass.simpleName !in scopeKinds) {
            scope = scope.parentNode.orElse(null)
        }
        scope ?: return null
        var distance = 0
        var cursor: Node? = use
        while (cursor != null) {
            if (cursor === scope) return distance
            if (cursor is CallableDeclaration<*> && scope !is CallableDeclaration<*>) return null
            cursor = cursor.parentNode.orElse(null)
            distance++
        }
        return null
    }

    private fun typeInfo(declaration: TypeDeclaration<*>): TypeInfo? {
        val qualified = declaration.fullyQualifiedName.orElse(null) ?: return null
        return typesByQualifiedName[qualified] ?: run {
            registerUnit(declaration.findCompilationUnit().orElse(null) ?: return null)
            typesByQualifiedName[qualified]
        }
    }

    private fun loadType(qualifiedName: String): TypeInfo? {
        typesByQualifiedName[qualifiedName]?.let { return it }
        val source = sourceFileFor(qualifiedName) ?: return null
        parseFile(source)
        return typesByQualifiedName[qualifiedName]
    }

    private fun sourceFileFor(qualifiedName: String): Path? {
        val parts = qualifiedName.split('.')
        if (parts.any { !JAVA_IDENTIFIER.matches(it) }) return null
        for (end in parts.size downTo 1) {
            val candidate = sourceRoot.resolve(parts.take(end).joinToString("/") + ".java").normalize()
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }

    private fun parseFile(file: Path): CompilationUnit {
        val normalized = file.toAbsolutePath().normalize()
        unitsByFile[normalized]?.let { return it }
        val result = parser.parse(normalized.readText())
        val unit = result.result.orElseThrow {
            IllegalStateException("Cannot parse Java type index source $normalized: ${result.problems.joinToString()}")
        }
        unitsByFile[normalized] = unit
        registerUnit(unit)
        return unit
    }

    private fun registerUnit(unit: CompilationUnit) {
        var registeredNewType = false
        unit.findAll(TypeDeclaration::class.java).forEach { declaration ->
            val qualified = declaration.fullyQualifiedName.orElse(null) ?: return@forEach
            if (typesByQualifiedName.containsKey(qualified)) return@forEach
            val typeParameters = when (declaration) {
                is ClassOrInterfaceDeclaration -> declaration.typeParameters
                is RecordDeclaration -> declaration.typeParameters
                else -> emptyList()
            }
            val bounds = typeParameters.mapNotNull { parameter ->
                parameter.typeBound.firstOrNull()?.let { parameter.nameAsString to parseType(it.asString()) }
            }.toMap()
            val directSupers = when (declaration) {
                is ClassOrInterfaceDeclaration -> declaration.extendedTypes + declaration.implementedTypes
                is RecordDeclaration -> declaration.implementedTypes
                is EnumDeclaration -> declaration.implementedTypes
                is AnnotationDeclaration -> emptyList()
                else -> emptyList()
            }
            typesByQualifiedName[qualified] = TypeInfo(
                qualifiedName = qualified,
                unit = unit,
                declaration = declaration,
                typeParameters = typeParameters.map { it.nameAsString },
                typeParameterBounds = bounds,
                directSupers = directSupers.map { parseType(it.asString()) }
            )
            registeredNewType = true
        }
        if (registeredNewType) inheritedMemberTypeCache.clear()
    }

    private fun <T> exactSingle(values: List<T>, label: String): T? {
        val distinct = values.distinct()
        if (distinct.size > 1) throw IllegalStateException("Ambiguous $label: $distinct")
        return distinct.singleOrNull()
    }

    companion object {
        private data class ExternalMethodSignature(
            val name: String,
            val parameterTypes: List<String> = emptyList()
        )

        private fun externalMethod(name: String, vararg parameterTypes: String) =
            ExternalMethodSignature(name, parameterTypes.toList())

        private val JAVA_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val GENERATED_COMPAT_LAZY_OPTIONAL = Regex(
            """com\.modporter\.generated\.(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+compat\.LazyOptional"""
        )
        // Text Replacement repackages Forge to the NeoForge intermediate consumed before Build relocation.
        // Exact project-source definitions take precedence over this external fallback.
        private val EXTERNAL_LAZY_OPTIONAL_TYPES = setOf(
            "net.minecraftforge.common.util.LazyOptional",
            "net.neoforged.neoforge.common.util.LazyOptional"
        )
        private val primitiveTypes = setOf("boolean", "byte", "char", "double", "float", "int", "long", "short", "void")
        private val knownExternalTypes = mapOf(
            "ItemStack" to "net.minecraft.world.item.ItemStack",
            "FriendlyByteBuf" to "net.minecraft.network.FriendlyByteBuf",
            "RegistryFriendlyByteBuf" to "net.minecraft.network.RegistryFriendlyByteBuf",
            "Player" to "net.minecraft.world.entity.player.Player",
            "AbstractContainerScreen" to "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
            "List" to "java.util.List",
            "Collection" to "java.util.Collection",
            "Map" to "java.util.Map",
            "Optional" to "java.util.Optional"
        )
        // Complete recursive non-private field/member-type name surfaces from the same pinned
        // NeoForge artifact used below. Nested class entry names are retained as a safe over-approximation.
        private val knownClosedExternalScopeHeadNames = mapOf(
            "java.lang.Object" to emptySet(),
            "net.minecraft.network.protocol.common.custom.CustomPacketPayload" to setOf(
                "FallbackProvider", "Type", "TypeAndCodec"
            )
        )
        private val knownExternalAssignableTypes = mapOf(
            "net.minecraft.world.level.Level" to setOf("net.minecraft.world.level.BlockGetter"),
            "net.minecraft.server.level.ServerLevel" to setOf(
                "net.minecraft.world.level.Level",
                "net.minecraft.world.level.BlockGetter"
            ),
            "net.minecraft.world.entity.LivingEntity" to setOf("net.minecraft.world.entity.Entity"),
            "net.minecraft.world.entity.Mob" to setOf(
                "net.minecraft.world.entity.LivingEntity",
                "net.minecraft.world.entity.Entity"
            ),
            "net.minecraft.world.entity.player.Player" to setOf(
                "net.minecraft.world.entity.LivingEntity",
                "net.minecraft.world.entity.Entity"
            ),
            "net.minecraft.server.level.ServerPlayer" to setOf(
                "net.minecraft.world.entity.player.Player",
                "net.minecraft.world.entity.LivingEntity",
                "net.minecraft.world.entity.Entity"
            ),
            "net.minecraft.world.level.block.entity.HopperBlockEntity" to
                setOf("net.minecraft.world.level.block.entity.BlockEntity"),
            "net.minecraft.world.level.block.entity.BaseContainerBlockEntity" to
                setOf("net.minecraft.world.level.block.entity.BlockEntity"),
            "net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity" to setOf(
                "net.minecraft.world.level.block.entity.BaseContainerBlockEntity",
                "net.minecraft.world.level.block.entity.BlockEntity"
            ),
            "java.util.List" to setOf("java.util.Collection", "java.lang.Iterable"),
            "java.util.Collection" to setOf("java.lang.Iterable")
        ) + ExactExternalTypeContracts.assignableTypes
        private fun externalMethodNames(names: String): Set<String> =
            names.trim().split(Regex("\\s+")).filter(String::isNotEmpty).toSet()

        // Complete inherited non-private method-name surfaces (including static and
        // package-private declarations) parsed from the exact target artifact
        // neoforge-21.1.230.jar (SHA-256
        // af1783b7997322eb00907509b567825e65ea9bd29b6d0b138d7d0a15c589894e).
        // The matching moddev config pins net.neoforged:neoforge:21.1.230 and
        // net.neoforged:neoform:1.21.1-20240808.144430. Only name absence is proof;
        // a present name remains unresolved. Object.registerNatives is retained as
        // a harmless private-name over-approximation.
        private val knownClosedExternalMethodNames = buildMap {
            put("java.lang.Object", externalMethodNames(
                """
                clone equals finalize getClass hashCode notify notifyAll registerNatives toString wait
                """
            ))
            val horizontalDirectionalBlock = externalMethodNames(
                """
                addLandingEffects addRunningEffects animateTick appendHoverText asBlock asItem attack
                box builtInRegistryHolder byItem canBeHydrated canBeReplaced canConnectRedstone
                canDropFromExplosion canEntityDestroy canHarvestBlock canStickTo canSupportCenter
                canSupportRigidBlock canSurvive canSustainPlant clone codec collisionExtendsVertically
                createBlockStateDefinition defaultBlockState defaultDestroyTime defaultMapColor destroy
                dropFromExplosion dropResources entityInside equals fallOn finalize getAdjacentBlockPathType
                getAnalogOutputSignal getAppearance getBeaconColorMultiplier getBedDirection getBlockPathType
                getBlockSupportShape getBubbleColumnDirection getClass getCloneItemStack getCollisionShape
                getDescriptionId getDestroyProgress getDirectSignal getDrops getEnchantPowerBonus getExpDrop
                getExplosionResistance getFireSpreadSpeed getFlammability getFluidState getFriction getId
                getInteractionShape getJumpFactor getLightBlock getLightEmission getLootTable getMapColor
                getMaxHorizontalOffset getMaxVerticalOffset getMenuProvider getName getOcclusionShape
                getPistonPushReaction getRenderShape getRespawnPosition getSeed getShadeBrightness getShape
                getShapeForEachState getSignal getSoundType getSpeedFactor getStateAtViewpoint
                getStateDefinition getStateForPlacement getToolModifiedState getVisualShape getWeakChanges
                handlePrecipitation hasAnalogOutputSignal hasDynamicLightEmission hasDynamicShape hashCode
                hidesNeighborFace ignitedByLava initializeClient isAir isBed isBurning
                isCollisionShapeFullBlock isConduitFrame isEmpty isEnabled isExceptionForConnection
                isFaceFull isFertile isFireSource isFlammable isLadder isOcclusionShapeFullBlock
                isPathfindable isPortalFrame isPossibleToRespawnInThis isRandomlyTicking isScaffolding
                isShapeFullBlock isSignalSource isSlimeBlock isStickyBlock
                makesOpenTrapdoorAboveClimbable mirror neighborChanged notify notifyAll onBlockExploded
                onBlockStateChange onCaughtFire onDestroyedByPlayer onDestroyedByPushReaction
                onExplosionHit onNeighborChange onPlace onProjectileHit onRemove onTreeGrow playerDestroy
                playerWillDestroy popExperience popResource popResourceFromFace propagatesSkylightDown
                properties propertiesCodec pushEntitiesUp randomTick registerDefaultState registerNatives
                requiredFeatures rotate setBedOccupied setPlacedBy shouldCheckWeakPower
                shouldDisplayFluidOverlay shouldHideAdjacentFluidFace shouldRenderFace simpleCodec
                skipRendering spawnAfterBreak spawnDestroyParticles stateById stepOn
                supportsExternalFaceHiding tick toString triggerEvent tryDropExperience
                updateEntityAfterFallOn updateFromNeighbourShapes updateIndirectNeighbourShapes
                updateOrDestroy updateShape useItemOn useShapeForLightOcclusion useWithoutItem wait
                wasExploded withPropertiesOf
                """
            )
            put("net.minecraft.world.level.block.HorizontalDirectionalBlock", horizontalDirectionalBlock)
            put(
                "net.minecraft.world.level.block.LecternBlock",
                horizontalDirectionalBlock + externalMethodNames(
                    """
                    createTickerHelper getListener getTicker newBlockEntity resetBookState signalPageChange
                    tryPlaceBook
                    """
                )
            )
            put("net.minecraft.world.level.block.SimpleWaterloggedBlock", externalMethodNames(
                """
                canPlaceLiquid clone equals finalize getClass getPickupSound hashCode notify notifyAll
                pickupBlock placeLiquid registerNatives toString wait
                """
            ))
            put("net.minecraft.world.level.block.EntityBlock", externalMethodNames(
                """
                clone equals finalize getClass getListener getTicker hashCode newBlockEntity notify
                notifyAll registerNatives toString wait
                """
            ))
        }

        // Flattened from the mapped Minecraft 1.21.1 and NeoForge 21.1 source
        // hierarchy. A surface is closed only when it includes every inherited
        // public or protected instance method, so absence is valid override proof.
        private val knownClosedExternalMethodSurfaces = mapOf(
            "net.minecraft.world.level.block.entity.BlockEntity" to setOf(
                externalMethod("applyComponents", "net.minecraft.core.component.DataComponentMap", "net.minecraft.core.component.DataComponentPatch"),
                externalMethod("applyComponentsFromItemStack", "net.minecraft.world.item.ItemStack"),
                externalMethod("applyImplicitComponents", "net.minecraft.world.level.block.entity.BlockEntity.DataComponentInput"),
                externalMethod("clearRemoved"),
                externalMethod("clone"),
                externalMethod("collectComponents"),
                externalMethod("collectImplicitComponents", "net.minecraft.core.component.DataComponentMap.Builder"),
                externalMethod("components"),
                externalMethod("deserializeAttachments", "net.minecraft.core.HolderLookup.Provider", "net.minecraft.nbt.CompoundTag"),
                externalMethod("equals", "java.lang.Object"),
                externalMethod("fillCrashReportCategory", "net.minecraft.CrashReportCategory"),
                externalMethod("finalize"),
                externalMethod("getBlockPos"),
                externalMethod("getBlockState"),
                externalMethod("getClass"),
                externalMethod("getData", "java.util.function.Supplier"),
                externalMethod("getData", "net.neoforged.neoforge.attachment.AttachmentType"),
                externalMethod("getExistingData", "java.util.function.Supplier"),
                externalMethod("getExistingData", "net.neoforged.neoforge.attachment.AttachmentType"),
                externalMethod("getExistingDataOrNull", "java.util.function.Supplier"),
                externalMethod("getExistingDataOrNull", "net.neoforged.neoforge.attachment.AttachmentType"),
                externalMethod("getLevel"),
                externalMethod("getModelData"),
                externalMethod("getPersistentData"),
                externalMethod("getType"),
                externalMethod("getUpdatePacket"),
                externalMethod("getUpdateTag", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("handleUpdateTag", "net.minecraft.nbt.CompoundTag", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("hasAttachments"),
                externalMethod("hasCustomOutlineRendering", "net.minecraft.world.entity.player.Player"),
                externalMethod("hasData", "java.util.function.Supplier"),
                externalMethod("hasData", "net.neoforged.neoforge.attachment.AttachmentType"),
                externalMethod("hasLevel"),
                externalMethod("hashCode"),
                externalMethod("invalidateCapabilities"),
                externalMethod("isRemoved"),
                externalMethod("isValidBlockState", "net.minecraft.world.level.block.state.BlockState"),
                externalMethod("loadAdditional", "net.minecraft.nbt.CompoundTag", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("loadCustomOnly", "net.minecraft.nbt.CompoundTag", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("loadWithComponents", "net.minecraft.nbt.CompoundTag", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("notify"),
                externalMethod("notifyAll"),
                externalMethod("onChunkUnloaded"),
                externalMethod("onDataPacket", "net.minecraft.network.Connection", "net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("onLoad"),
                externalMethod("onlyOpCanSetNbt"),
                externalMethod("removeComponentsFromTag", "net.minecraft.nbt.CompoundTag"),
                externalMethod("removeData", "java.util.function.Supplier"),
                externalMethod("removeData", "net.neoforged.neoforge.attachment.AttachmentType"),
                externalMethod("requestModelDataUpdate"),
                externalMethod("saveAdditional", "net.minecraft.nbt.CompoundTag", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("saveCustomAndMetadata", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("saveCustomOnly", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("saveToItem", "net.minecraft.world.item.ItemStack", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("saveWithFullMetadata", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("saveWithId", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("saveWithoutMetadata", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("serializeAttachments", "net.minecraft.core.HolderLookup.Provider"),
                externalMethod("setBlockState", "net.minecraft.world.level.block.state.BlockState"),
                externalMethod("setChanged"),
                externalMethod("setComponents", "net.minecraft.core.component.DataComponentMap"),
                externalMethod("setData", "java.util.function.Supplier", "java.lang.Object"),
                externalMethod("setData", "net.neoforged.neoforge.attachment.AttachmentType", "java.lang.Object"),
                externalMethod("setLevel", "net.minecraft.world.level.Level"),
                externalMethod("setRemoved"),
                externalMethod("syncData", "java.util.function.Supplier"),
                externalMethod("syncData", "net.neoforged.neoforge.attachment.AttachmentType"),
                externalMethod("toString"),
                externalMethod("triggerEvent", "int", "int"),
                externalMethod("wait"),
                externalMethod("wait", "long"),
                externalMethod("wait", "long", "int")
            )
        )
        // Java 17 java.base: every public top-level type declared directly in java.lang.
        private val implicitJavaLangTypes = setOf(
            "AbstractMethodError",
            "Appendable",
            "ArithmeticException",
            "ArrayIndexOutOfBoundsException",
            "ArrayStoreException",
            "AssertionError",
            "AutoCloseable",
            "Boolean",
            "BootstrapMethodError",
            "Byte",
            "CharSequence",
            "Character",
            "Class",
            "ClassCastException",
            "ClassCircularityError",
            "ClassFormatError",
            "ClassLoader",
            "ClassNotFoundException",
            "ClassValue",
            "CloneNotSupportedException",
            "Cloneable",
            "Comparable",
            "Compiler",
            "Deprecated",
            "Double",
            "Enum",
            "EnumConstantNotPresentException",
            "Error",
            "Exception",
            "ExceptionInInitializerError",
            "Float",
            "FunctionalInterface",
            "IllegalAccessError",
            "IllegalAccessException",
            "IllegalArgumentException",
            "IllegalCallerException",
            "IllegalMonitorStateException",
            "IllegalStateException",
            "IllegalThreadStateException",
            "IncompatibleClassChangeError",
            "IndexOutOfBoundsException",
            "InheritableThreadLocal",
            "InstantiationError",
            "InstantiationException",
            "Integer",
            "InternalError",
            "InterruptedException",
            "Iterable",
            "LayerInstantiationException",
            "LinkageError",
            "Long",
            "Math",
            "Module",
            "ModuleLayer",
            "NegativeArraySizeException",
            "NoClassDefFoundError",
            "NoSuchFieldError",
            "NoSuchFieldException",
            "NoSuchMethodError",
            "NoSuchMethodException",
            "NullPointerException",
            "Number",
            "NumberFormatException",
            "Object",
            "OutOfMemoryError",
            "Override",
            "Package",
            "Process",
            "ProcessBuilder",
            "ProcessHandle",
            "Readable",
            "Record",
            "ReflectiveOperationException",
            "Runnable",
            "Runtime",
            "RuntimeException",
            "RuntimePermission",
            "SafeVarargs",
            "SecurityException",
            "SecurityManager",
            "Short",
            "StackOverflowError",
            "StackTraceElement",
            "StackWalker",
            "StrictMath",
            "String",
            "StringBuffer",
            "StringBuilder",
            "StringIndexOutOfBoundsException",
            "SuppressWarnings",
            "System",
            "Thread",
            "ThreadDeath",
            "ThreadGroup",
            "ThreadLocal",
            "Throwable",
            "TypeNotPresentException",
            "UnknownError",
            "UnsatisfiedLinkError",
            "UnsupportedClassVersionError",
            "UnsupportedOperationException",
            "VerifyError",
            "VirtualMachineError",
            "Void"
        ).associateWith { "java.lang.$it" }
        // java.base Map/Map.Entry generic signatures were read from Adoptium 8u472 rt.jar:
        // Map.class SHA-256 902b845ca852c7524528fe1162f5c37430b18203da008b03def216b9113015f6;
        // Map$Entry.class SHA-256 9996625359014bdf0a5cfc612dffc377b8d9cbd7be7966c08e8df92b954de79b.
        private const val GUAVA_IMMUTABLE_LIST = "com.google.common.collect.ImmutableList"
        private val EXACT_EXTERNAL_ITERABLE_TYPES = setOf(
            "java.lang.Iterable",
            "java.util.Collection",
            "java.util.List",
            "java.util.Set",
            GUAVA_IMMUTABLE_LIST
        )

        // net.createmod.ponder:ponder-neoforge:1.0.87+mc1.21.1
        // JAR SHA-256 d6b7fdf94359f73dc1fee730eded898d51b3bf0d626710753253becd8e4e6bef;
        // Couple.class SHA-256 7f88d8f248964d49fa3e2ce30b8170812fbaa7943db3f90e4de03e847caad9a0;
        // NBTHelper.class SHA-256 e654636a4b9ef362cd46e12a0646d77b37cd2287ca33278e71a725ca419a88c5.
        // Each entry maps only the exact proven callback parameter to its generic source.
        private const val CATNIP_COUPLE = "net.createmod.catnip.data.Couple"
        private const val CATNIP_NBT_HELPER = "net.createmod.catnip.nbt.NBTHelper"
        private val exactExternalFunctionalParameterContracts = listOf(
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "map", 1, 0, 1, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "mapNotNull", 1, 0, 1, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "mapWithContext", 1, 0, 2, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "mapWithParams", 2, 0, 2, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "mapNotNullWithParam", 2, 0, 2, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "both", 1, 0, 1, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "either", 1, 0, 1, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "replace", 1, 0, 1, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "replaceWithContext", 1, 0, 2, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "replaceWithParams", 2, 0, 2, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "forEach", 1, 0, 1, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "forEachWithContext", 1, 0, 2, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "forEachWithParams", 2, 0, 2, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_COUPLE, "serializeEach", 1, 0, 1, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                CATNIP_NBT_HELPER, "writeCompoundList", 2, 1, 1, 0,
                ExactExternalFunctionalParameterSource.IterableCallArgument(0)
            ),
            // NeoForge 21.1.230 merged SDK SHA-256 2faf217a53ff44583a6df6ba9d6f8a19f8e531c405eebd373eaaea6c08a37f3f.
            // NonNullList<E> extends AbstractList<E>; Iterable.forEach consumes Consumer<? super E>.
            // FriendlyByteBuf.writeCollection(Collection<T>, StreamEncoder<? super FriendlyByteBuf,T>),
            // inherited unchanged by RegistryFriendlyByteBuf. The implicit encoder buffer is FriendlyByteBuf.
            ExactExternalFunctionalParameterContract(
                "net.minecraft.core.NonNullList", "forEach", 1, 0, 1, 0,
                ExactExternalFunctionalParameterSource.ReceiverTypeArgument(0)
            ),
            ExactExternalFunctionalParameterContract(
                "net.minecraft.network.FriendlyByteBuf", "writeCollection", 2, 1, 2, 1,
                ExactExternalFunctionalParameterSource.IterableCallArgument(0, allowPureIterable = false)
            ),
            ExactExternalFunctionalParameterContract(
                "net.minecraft.network.RegistryFriendlyByteBuf", "writeCollection", 2, 1, 2, 1,
                ExactExternalFunctionalParameterSource.IterableCallArgument(0, allowPureIterable = false)
            ),
            ExactExternalFunctionalParameterContract(
                "net.minecraft.network.FriendlyByteBuf", "writeCollection", 2, 1, 2, 0,
                ExactExternalFunctionalParameterSource.ExactType("net.minecraft.network.FriendlyByteBuf")
            ),
            ExactExternalFunctionalParameterContract(
                "net.minecraft.network.RegistryFriendlyByteBuf", "writeCollection", 2, 1, 2, 0,
                ExactExternalFunctionalParameterSource.ExactType("net.minecraft.network.FriendlyByteBuf")
            )
        )

        private val knownFields = mapOf(
            "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen" to mapOf(
                "menu" to TypeRef("T"),
                "minecraft" to TypeRef("net.minecraft.client.Minecraft")
            ),
            "net.minecraft.client.Minecraft" to mapOf(
                "level" to TypeRef("net.minecraft.world.level.Level")
            )
        )
        private val knownIndexedGenericFields = mapOf(
            "org.apache.commons.lang3.tuple.MutablePair" to mapOf(
                "left" to 0,
                "right" to 1
            )
        )
        private val knownMethods = mapOf(
            "net.minecraft.client.Minecraft" to mapOf(
                ("getInstance" to 0) to TypeRef("net.minecraft.client.Minecraft")
            ),
            "net.minecraft.world.entity.player.Player" to mapOf(
                ("getMainHandItem" to 0) to TypeRef("net.minecraft.world.item.ItemStack"),
                ("getCommandSenderWorld" to 0) to TypeRef("net.minecraft.world.level.Level")
            ),
            "net.minecraft.world.entity.item.ItemEntity" to mapOf(
                ("getItem" to 0) to TypeRef("net.minecraft.world.item.ItemStack")
            ),
            "net.minecraft.world.item.context.UseOnContext" to mapOf(
                ("getItemInHand" to 0) to TypeRef("net.minecraft.world.item.ItemStack")
            ),
            "net.minecraft.world.level.storage.loot.LootParams.Builder" to mapOf(
                ("getLevel" to 0) to TypeRef("net.minecraft.server.level.ServerLevel")
            ),
            "net.minecraft.nbt.CompoundTag" to mapOf(
                ("getCompound" to 1) to TypeRef("net.minecraft.nbt.CompoundTag"),
                ("getList" to 2) to TypeRef("net.minecraft.nbt.ListTag")
            ),
            "net.minecraft.nbt.ListTag" to mapOf(
                ("getCompound" to 1) to TypeRef("net.minecraft.nbt.CompoundTag")
            ),
            "net.minecraft.network.FriendlyByteBuf" to mapOf(
                ("readNbt" to 0) to TypeRef("net.minecraft.nbt.CompoundTag")
            ),
            "net.minecraft.network.RegistryFriendlyByteBuf" to mapOf(
                ("readNbt" to 0) to TypeRef("net.minecraft.nbt.CompoundTag")
            )
        )

        fun build(sourceRoot: Path): JavaProjectTypeIndex =
            JavaProjectTypeIndex(sourceRoot.toAbsolutePath().normalize())

        private fun parseType(raw: String): TypeRef {
            var text = raw.trim()
            var arrayDepth = 0
            while (text.endsWith("[]")) {
                text = text.removeSuffix("[]").trimEnd()
                arrayDepth++
            }
            if (text == "?" || text.startsWith("? super ")) {
                return TypeRef("java.lang.Object", arrayDepth = arrayDepth)
            }
            if (text.startsWith("? extends ")) {
                val bound = parseType(text.removePrefix("? extends "))
                return bound.copy(arrayDepth = bound.arrayDepth + arrayDepth)
            }
            val open = text.indexOf('<')
            if (open < 0) return TypeRef(text, arrayDepth = arrayDepth)
            val close = text.lastIndexOf('>')
            if (close < open) throw IllegalStateException("Malformed generic type '$raw'")
            return TypeRef(
                text.substring(0, open).trim(),
                splitArguments(text.substring(open + 1, close)).map(::parseType),
                arrayDepth
            )
        }

        private fun splitArguments(text: String): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { index, char ->
                when (char) {
                    '<' -> depth++
                    '>' -> depth--
                    ',' -> if (depth == 0) {
                        result += text.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
            result += text.substring(start).trim()
            return result.filter { it.isNotEmpty() }
        }
    }
}
