package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr

internal data class ExactProviderJavaMethodSurface(
    val name: String,
    val nameOffset: Int,
    val range: IntRange,
    val parameters: List<Pair<String, String>>,
    val returnType: String?,
    val isStatic: Boolean
)

private object ExactProviderJavaMethodSurfaceCache {
    private var source: String? = null
    private var surfaces: List<ExactProviderJavaMethodSurface> = emptyList()

    @Synchronized
    fun getOrCompute(
        currentSource: String,
        compute: () -> List<ExactProviderJavaMethodSurface>
    ): List<ExactProviderJavaMethodSurface> {
        if (source == currentSource) return surfaces
        return compute().also {
            source = currentSource
            surfaces = it
        }
    }
}

internal fun StructuralRefactorPass.exactProviderJavaMethodAt(
    source: String,
    offset: Int
): ExactProviderJavaMethodSurface? =
    exactProviderJavaMethodSurfaces(source)
        .filter { offset in it.range }
        .minByOrNull { it.range.last - it.range.first }

internal fun StructuralRefactorPass.exactProviderJavaMethodSurfaces(
    source: String
): List<ExactProviderJavaMethodSurface> =
    ExactProviderJavaMethodSurfaceCache.getOrCompute(source) {
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = parser.parse(source)
        val unit = parsed.result.orElseThrow {
            IllegalStateException(
                "Cannot resolve exact Java method structure for provider binding: ${parseProblems(parsed)}"
            )
        }
        val lineOffsets = buildList {
            add(0)
            source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
        }
        fun offset(position: com.github.javaparser.Position): Int =
            lineOffsets.getOrElse(position.line - 1) {
                throw IllegalStateException(
                    "Invalid JavaParser line ${position.line} in provider binding source"
                )
            } + position.column - 1
        fun surface(
            name: String,
            nameRange: com.github.javaparser.Range,
            range: com.github.javaparser.Range,
            parameters: List<Parameter>,
            returnType: String?,
            isStatic: Boolean
        ): ExactProviderJavaMethodSurface {
            val start = offset(range.begin)
            val endInclusive = offset(range.end)
            val nameStart = offset(nameRange.begin)
            if (start !in source.indices || endInclusive !in source.indices || start > endInclusive) {
                throw IllegalStateException(
                    "Invalid exact Java callable range for $name: $start..$endInclusive"
                )
            }
            if (nameStart !in start..endInclusive || !source.startsWith(name, nameStart)) {
                throw IllegalStateException(
                    "Invalid exact Java callable name range for $name at $nameStart"
                )
            }
            return ExactProviderJavaMethodSurface(
                name = name,
                nameOffset = nameStart,
                range = start..endInclusive,
                parameters = parameters.map { parameter ->
                    parameter.typeAsString to parameter.nameAsString
                },
                returnType = returnType,
                isStatic = isStatic
            )
        }
        unit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
            val range = method.range.orElse(null) ?: return@mapNotNull null
            val nameRange = method.name.range.orElse(null) ?: return@mapNotNull null
            surface(
                method.nameAsString,
                nameRange,
                range,
                method.parameters.toList(),
                method.typeAsString,
                method.isStatic
            )
        } + unit.findAll(ConstructorDeclaration::class.java).mapNotNull { constructor ->
            val range = constructor.range.orElse(null) ?: return@mapNotNull null
            val nameRange = constructor.name.range.orElse(null) ?: return@mapNotNull null
            surface(
                constructor.nameAsString,
                nameRange,
                range,
                constructor.parameters.toList(),
                returnType = null,
                isStatic = false
            )
        }
    }

internal fun StructuralRefactorPass.exactExternalCallbackProviderExpressionAt(
    source: String,
    offset: Int,
    javaInheritanceIndex: JavaInheritanceIndex
): String? {
    val method = exactProviderJavaMethodAt(source, offset) ?: return null
    val returnType = method.returnType ?: return null
    val declaration = enclosingJavaClassDeclaration(source, offset) ?: return null
    return ExactExternalProviderContracts.providerExpressionForExactSurface(
        methodName = method.name,
        returnType = returnType,
        parameterTypes = method.parameters.map { it.first },
        parameterNames = method.parameters.map { it.second },
        isStatic = method.isStatic,
        ownerMatches = { expectedOwner ->
            javaClassExtendsAny(
                declaration,
                setOf(expectedOwner.substringAfterLast('.')),
                javaInheritanceIndex
            )
        }
    )
}

internal fun StructuralRefactorPass.strictRegistryAccessExpressionAt(
    source: String,
    offset: Int,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String? {
    exactExternalCallbackProviderExpressionAt(source, offset, javaInheritanceIndex)?.let { return it }
    val parameters = currentMethodParametersBeforeOffset(source, offset)
    val declaredProviders = holderLookupProviderNamesFromParameters(parameters)
    if (declaredProviders.size == 1) return declaredProviders.single()
    if (declaredProviders.size > 1) return null
    registryAccessFromParameters(parameters, javaInheritanceIndex)?.let { return it }
    sourceProvenLocalRegistryAccessExpressionAt(source, offset)?.let { return it }
    guardedEntityPatternRegistryAccessAt(source, offset)?.let { return it }
    LibraryProviderCarriers.fromParameters(parameters, source)?.let { return it }
    sourceProvenInheritedRegistryAccessExpressionAt(source, offset, javaInheritanceIndex)?.let { return it }
    sourceProvenClientMinecraftRegistryAccessExpressionAt(source, offset)?.let { return it }
    val method = exactProviderJavaMethodAt(source, offset) ?: return null
    if (method.isStatic) return null
    LibraryProviderCarriers.fromEnclosingTypes(source, offset)?.let { return it }
    val declaration = enclosingJavaClassDeclaration(source, offset) ?: return null
    return "this.registryAccess()".takeIf {
        javaClassExtendsAny(declaration, javaEntityBaseTypes(), javaInheritanceIndex)
    }
}

internal fun StructuralRefactorPass.sourceProvenInheritedRegistryAccessExpressionAt(
    source: String,
    offset: Int,
    javaInheritanceIndex: JavaInheritanceIndex
): String? {
    val declaration = enclosingJavaClassDeclaration(source, offset) ?: return null
    val field = javaInheritanceIndex.inheritedRegistryAccessFieldInfo(declaration.name) ?: return null
    val parsed = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    ).parse(source)
    if (!parsed.isSuccessful) return null
    val unit = parsed.result.orElse(null) ?: return null
    val lineOffsets = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun sourceRange(node: Node): IntRange? = node.range.orElse(null)?.let { range ->
        (lineOffsets[range.begin.line - 1] + range.begin.column - 1)..
            (lineOffsets[range.end.line - 1] + range.end.column - 1)
    }
    val callables: List<CallableDeclaration<*>> =
        unit.findAll(MethodDeclaration::class.java) + unit.findAll(ConstructorDeclaration::class.java)
    val callable = callables
        .mapNotNull { candidate -> sourceRange(candidate)?.takeIf { offset in it }?.let { candidate to it } }
        .minByOrNull { (_, range) -> range.last - range.first }
        ?.first ?: return null
    val use = buildList<Node> {
        addAll(callable.findAll(MethodCallExpr::class.java))
        addAll(callable.findAll(MethodReferenceExpr::class.java))
        addAll(callable.findAll(ObjectCreationExpr::class.java))
    }.mapNotNull { expression ->
        sourceRange(expression)?.takeIf { offset in it }?.let { expression to it }
    }.minByOrNull { (_, range) -> range.last - range.first }
        ?.first ?: return null
    if (callable is MethodDeclaration && callable.isStatic) return null
    val fieldName = field.expression.removePrefix("this.").substringBefore('.')
    val unqualifiedIsUnshadowed = callable.parameters.none { it.nameAsString == fieldName } &&
        callable.findAll(VariableDeclarator::class.java).none { it.nameAsString == fieldName }
    val expressions = buildList {
        if (field.expression.startsWith("this.")) {
            add(field.expression)
        } else {
            if (unqualifiedIsUnshadowed) add(field.expression)
            add("this.${field.expression}")
        }
    }
    val stableDirectField = field.expression.matches(Regex("""[A-Za-z_$][\w$]*"""))
    val provenExpression = expressions.firstOrNull { expression ->
        ExactNullabilityProof.expressionProvenNonNullAt(expression, callable, use) ||
            (field.isFinal && stableDirectField &&
                ExactNullabilityProof.stableExpressionProvenNonNullAt(expression, callable, use))
    }
    return provenExpression?.let { "$it.registryAccess()" }
}

internal fun StructuralRefactorPass.sourceProvenClientMinecraftRegistryAccessExpressionAt(
    source: String,
    offset: Int
): String? {
    val methodRange = exactProviderJavaMethodAt(source, offset)?.range ?: return null
    val executableMethod = maskJavaCommentsAndLiterals(source.substring(methodRange))
    if (!executableMethod.contains("Minecraft.getInstance().level") ||
        (!executableMethod.contains("null") && !executableMethod.contains("requireNonNull"))
    ) return null

    // An unrelated client-level read does not prove that a later NBT call owns a provider.
    // Reuse the ambient level only where a dominating source guard proves it non-null.
    val parsed = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    ).parse(source)
    if (!parsed.isSuccessful) return null
    val unit = parsed.result.orElse(null) ?: return null
    val lineOffsets = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun sourceRange(node: Node): IntRange? = node.range.orElse(null)?.let { range ->
        (lineOffsets[range.begin.line - 1] + range.begin.column - 1)..
            (lineOffsets[range.end.line - 1] + range.end.column - 1)
    }
    val callables: List<CallableDeclaration<*>> =
        unit.findAll(MethodDeclaration::class.java) + unit.findAll(ConstructorDeclaration::class.java)
    val callable = callables.singleOrNull { sourceRange(it) == methodRange } ?: return null
    val use = callable.findAll(MethodCallExpr::class.java)
        .mapNotNull { call -> sourceRange(call)?.takeIf { offset in it }?.let { call to it } }
        .minByOrNull { (_, range) -> range.last - range.first }
        ?.first ?: return null
    val receivers = mutableListOf("net.minecraft.client.Minecraft.getInstance().level")
    val hasMinecraftImport = unit.imports.any {
        !it.isStatic && !it.isAsterisk && it.nameAsString == "net.minecraft.client.Minecraft"
    }
    val shadowsMinecraft = unit.findAll(com.github.javaparser.ast.body.TypeDeclaration::class.java)
        .any { it.nameAsString == "Minecraft" }
    if (hasMinecraftImport && !shadowsMinecraft) receivers += "Minecraft.getInstance().level"
    return "net.minecraft.client.Minecraft.getInstance().level.registryAccess()".takeIf {
        receivers.any { receiver ->
            ExactNullabilityProof.expressionProvenNonNullAt(receiver, callable, use)
        }
    }
}

internal fun StructuralRefactorPass.requiredHolderLookupProviderNameAt(
    source: String,
    offset: Int,
    context: String
): String =
    exactHolderLookupProviderNameAtOrNull(source, offset, context)
        ?: throw IllegalStateException(
            "$context in '${enclosingJavaMethodNameAt(source, offset) ?: "<unresolved>"}' requires one " +
                "HolderLookup.Provider parameter; found 0"
        )

internal fun StructuralRefactorPass.exactHolderLookupProviderNameAtOrNull(
    source: String,
    offset: Int,
    context: String
): String? {
    val providers = exactProviderJavaMethodAt(source, offset)
        ?.parameters
        .orEmpty()
        .mapNotNull { (type, name) ->
            name.takeIf {
                type == "HolderLookup.Provider" ||
                    type == "net.minecraft.core.HolderLookup.Provider"
            }
        }
        .toSet()
    if (providers.size > 1) {
        val method = enclosingJavaMethodNameAt(source, offset) ?: "<unresolved>"
        throw IllegalStateException(
            "$context in '$method' has ambiguous HolderLookup.Provider parameters; " +
                "found ${providers.size}"
        )
    }
    return providers.singleOrNull()
}

internal fun StructuralRefactorPass.uniqueHolderLookupProviderNameForMethodAt(
    source: String,
    methodHeaderOffset: Int,
    preferred: String
): String {
    val callable = exactProviderJavaMethodAt(source, methodHeaderOffset)
    if (callable != null) {
        return uniqueLocalNameInScope(source.substring(callable.range), preferred)
    }
    val openBrace = source.indexOf('{', methodHeaderOffset)
    val semicolon = source.indexOf(';', methodHeaderOffset)
    if (semicolon >= 0 && (openBrace < 0 || semicolon < openBrace)) {
        return uniqueLocalNameInScope(source.substring(methodHeaderOffset, semicolon + 1), preferred)
    }
    if (openBrace < 0) {
        throw IllegalStateException(
            "Cannot locate Java method body or abstract declaration while adding HolderLookup.Provider near: " +
                source.substring(methodHeaderOffset).lineSequence().firstOrNull().orEmpty().trim()
        )
    }
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace < 0) {
        throw IllegalStateException("Cannot locate Java method end while adding HolderLookup.Provider")
    }
    return uniqueLocalNameInScope(source.substring(methodHeaderOffset, closeBrace + 1), preferred)
}

internal fun StructuralRefactorPass.enclosingJavaMethodNameAt(
    source: String,
    offset: Int
): String? = exactProviderJavaMethodAt(source, offset)?.name
