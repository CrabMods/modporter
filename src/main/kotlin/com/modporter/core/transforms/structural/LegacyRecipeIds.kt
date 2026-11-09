package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.ast.type.WildcardType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Source-owned recipe identity support.
 *
 * Recipe#getId disappeared in 1.21. Loaded recipes recover their canonical IDs
 * from exact RecipeHolder value identity. A project recipe with a reachable
 * source getId() exposes that value through LegacyRecipeIds.SourceOwned so
 * fresh, deliberately constructed project recipes retain their explicit IDs.
 */
internal object LegacyRecipeIds {
    const val RULE_ID = "struct-legacy-recipe-ids"
    private const val RECIPE = "net.minecraft.world.item.crafting.Recipe"
    private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
    private const val JEI_TRANSFER = "mezz.jei.api.recipe.transfer.IRecipeTransferHandler"
    private const val JEI_TRANSFER_ERROR = "mezz.jei.api.recipe.transfer.IRecipeTransferError"
    private const val JEI_SLOTS_VIEW = "mezz.jei.api.gui.ingredient.IRecipeSlotsView"
    private const val PLAYER = "net.minecraft.world.entity.player.Player"
    private const val PREDICATE = "java.util.function.Predicate"
    private val EXACT_EXTERNAL_TYPES = setOf(
        RECIPE,
        RESOURCE_LOCATION,
        JEI_TRANSFER,
        JEI_TRANSFER_ERROR,
        JEI_SLOTS_VIEW,
        PLAYER,
        PREDICATE,
        "net.minecraft.world.item.crafting.CraftingRecipe",
        "net.minecraft.world.item.crafting.RecipeHolder",
        "net.minecraft.world.item.crafting.RecipeInput",
        "net.minecraft.world.inventory.AbstractContainerMenu"
    )
    private val EXACT_EXTERNAL_PACKAGES =
        EXACT_EXTERNAL_TYPES.mapTo(linkedSetOf()) { it.substringBeforeLast('.') }
    private val KNOWN_NON_GATE_PACKAGES = setOf(
        "java.lang",
        "java.util",
        "java.util.function"
    )

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    private data class ParsedSource(
        val file: Path,
        val source: String,
        val unit: CompilationUnit,
        val packageName: String,
        val imports: Map<String, String>,
        val wildcardImports: Set<String>,
        val lineStarts: List<Int>
    ) {
        fun offset(node: Node): Int = node.range
            .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
            .orElse(-1)

        fun ownerName(declaration: TypeDeclaration<*>): String {
            val nested = generateSequence<Node>(declaration) { node ->
                node.parentNode.orElse(null)
            }
                .filterIsInstance<TypeDeclaration<*>>()
                .toList()
                .asReversed()
                .joinToString(".") { it.nameAsString }
            return if (packageName.isBlank()) nested else "$packageName.$nested"
        }

        fun canonicalType(
            raw: String,
            sourceTypes: Set<String>,
            context: TypeDeclaration<*>? = null
        ): String? {
            var value = raw.trim()
                .removePrefix("? extends ")
                .removePrefix("? super ")
                .trim()
            while (value.endsWith("[]")) value = value.dropLast(2).trim()
            value = value.substringBefore('<').trim()
            if (value.isEmpty() || value == "?") return null
            val first = value.substringBefore('.')
            imports[first]?.let { return it + value.removePrefix(first) }
            if (first.firstOrNull()?.isLowerCase() == true) return value

            val localCandidates = buildList {
                if (context != null) {
                    val enclosing = generateSequence<Node>(context) { node ->
                        node.parentNode.orElse(null)
                    }
                        .filterIsInstance<TypeDeclaration<*>>()
                        .toList()
                        .asReversed()
                        .map { it.nameAsString }
                    for (depth in enclosing.size downTo 1) {
                        add(
                            (listOf(packageName).filter { it.isNotEmpty() } +
                                enclosing.take(depth - 1) + value).joinToString(".")
                        )
                    }
                }
                add(if (packageName.isBlank()) value else "$packageName.$value")
            }
            localCandidates.firstOrNull { it in sourceTypes }?.let { return it }

            val wildcardCandidates = linkedSetOf<String>()
            var hasUnknownWildcard = false
            wildcardImports.forEach { wildcardPackage ->
                val candidate = "$wildcardPackage.$value"
                when {
                    candidate in sourceTypes ||
                        candidate in EXACT_EXTERNAL_TYPES ||
                        TargetMembers.inheritsFrom(candidate, RECIPE) -> {
                        wildcardCandidates += candidate
                    }
                    wildcardPackage in EXACT_EXTERNAL_PACKAGES ||
                        wildcardPackage in KNOWN_NON_GATE_PACKAGES ||
                        sourceTypes.any { it.substringBeforeLast('.') == wildcardPackage } -> Unit
                    else -> hasUnknownWildcard = true
                }
            }
            if (hasUnknownWildcard) return null
            return wildcardCandidates.singleOrNull()
        }

        fun typeRef(
            type: Type,
            sourceTypes: Set<String>,
            variables: Set<String>,
            context: TypeDeclaration<*>? = null
        ): TypeRef = when (type) {
            is ClassOrInterfaceType -> {
                val name = type.nameWithScope
                if (type.scope.isEmpty && type.typeArguments.isEmpty && name in variables) {
                    TypeVariable(name)
                } else {
                    canonicalType(name, sourceTypes, context)?.let { raw ->
                        NamedType(
                            raw,
                            type.typeArguments.orElse(null).orEmpty().map {
                                typeRef(it, sourceTypes, variables, context)
                            }
                        )
                    } ?: UnknownType
                }
            }
            is PrimitiveType -> PrimitiveTypeRef(type.asString())
            is WildcardType -> when {
                type.extendedType.isPresent -> WildcardTypeRef(
                    WildcardBound.EXTENDS,
                    typeRef(type.extendedType.get(), sourceTypes, variables, context)
                )
                type.superType.isPresent -> WildcardTypeRef(
                    WildcardBound.SUPER,
                    typeRef(type.superType.get(), sourceTypes, variables, context)
                )
                else -> WildcardTypeRef(WildcardBound.UNBOUNDED, null)
            }
            else -> UnknownType
        }
    }

    private sealed interface TypeRef {
        fun render(): String
    }

    private data class NamedType(
        val raw: String,
        val arguments: List<TypeRef> = emptyList()
    ) : TypeRef {
        override fun render(): String = raw + arguments.takeIf { it.isNotEmpty() }
            ?.joinToString(",", "<", ">") { it.render() }.orEmpty()
    }

    private data class TypeVariable(val name: String) : TypeRef {
        override fun render(): String = "variable:$name"
    }

    private data class PrimitiveTypeRef(val name: String) : TypeRef {
        override fun render(): String = name
    }

    private enum class WildcardBound {
        EXTENDS,
        SUPER,
        UNBOUNDED
    }

    private data class WildcardTypeRef(
        val boundKind: WildcardBound,
        val bound: TypeRef?
    ) : TypeRef {
        override fun render(): String = when (boundKind) {
            WildcardBound.EXTENDS -> "? extends ${bound?.render() ?: UnknownType.render()}"
            WildcardBound.SUPER -> "? super ${bound?.render() ?: UnknownType.render()}"
            WildcardBound.UNBOUNDED -> "?"
        }
    }

    private data object UnknownType : TypeRef {
        override fun render(): String = "<unknown>"
    }

    private data class TypeInfo(
        val typeParameters: List<String>,
        val parents: List<NamedType>,
        val ownsSourceGetId: Boolean
    )

    private class TypeIndex(
        private val types: Map<String, TypeInfo>,
        val sourceTypes: Set<String>,
        private val generatedIdentified: String?
    ) {
        fun isRecipe(type: String?): Boolean {
            val candidate = rawType(type) ?: return false
            if (candidate == RECIPE || TargetMembers.inheritsFrom(candidate, RECIPE)) return true
            return reaches(candidate) { it == RECIPE || TargetMembers.inheritsFrom(it, RECIPE) }
        }

        fun hasSourceGetId(type: String?): Boolean {
            val candidate = rawType(type) ?: return false
            return reaches(candidate) { parent -> types[parent]?.ownsSourceGetId == true }
        }

        fun hasGeneratedPendingPath(type: String?): Boolean {
            val expected = generatedIdentified ?: return false
            val candidate = rawType(type) ?: return false
            return reaches(candidate) { it == expected }
        }

        fun isSubtype(type: TypeRef, expectedRaw: String): Boolean {
            val candidate = (type as? NamedType)?.raw ?: return false
            return candidate == expectedRaw ||
                TargetMembers.inheritsFrom(candidate, expectedRaw) ||
                reaches(candidate) {
                    it == expectedRaw || TargetMembers.inheritsFrom(it, expectedRaw)
                }
        }

        fun jeiTransferArguments(owner: String): Pair<TypeRef, TypeRef>? {
            val ownerInfo = types[owner] ?: return null
            val start = NamedType(
                owner,
                ownerInfo.typeParameters.map(::TypeVariable)
            )
            val pending = ArrayDeque<NamedType>()
            val seen = mutableSetOf<String>()
            pending += start
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current.render())) continue
                if (current.raw == JEI_TRANSFER) {
                    val menu = current.arguments.getOrNull(0) ?: return null
                    val recipe = current.arguments.getOrNull(1) ?: return null
                    if (menu == UnknownType || recipe == UnknownType) return null
                    return menu to recipe
                }
                val info = types[current.raw] ?: continue
                val arguments = if (current.arguments.size == info.typeParameters.size) {
                    current.arguments
                } else {
                    info.typeParameters.map(::TypeVariable)
                }
                val bindings = info.typeParameters.zip(arguments).toMap()
                info.parents.forEach { parent ->
                    pending.addLast(substitute(parent, bindings) as? NamedType ?: parent)
                }
            }
            return null
        }

        private fun reaches(start: String, accepted: (String) -> Boolean): Boolean {
            val pending = ArrayDeque<String>()
            val seen = mutableSetOf<String>()
            pending += start
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current)) continue
                if (accepted(current)) return true
                types[current]?.parents.orEmpty().map { it.raw }.forEach(pending::addLast)
            }
            return false
        }

        private fun substitute(
            type: TypeRef,
            bindings: Map<String, TypeRef>,
            depth: Int = 0
        ): TypeRef {
            if (depth > 20) return UnknownType
            return when (type) {
                is TypeVariable -> bindings[type.name]?.let { bound ->
                    if (bound == type) type else substitute(bound, bindings, depth + 1)
                } ?: type
                is NamedType -> type.copy(
                    arguments = type.arguments.map {
                        substitute(it, bindings, depth + 1)
                    }
                )
                is WildcardTypeRef -> type.copy(
                    bound = type.bound?.let { substitute(it, bindings, depth + 1) }
                )
                else -> type
            }
        }

        private fun rawType(type: String?): String? {
            var value = type?.trim() ?: return null
            value = value.removePrefix("? extends ").removePrefix("? super ").trim()
            while (value.endsWith("[]")) value = value.dropLast(2).trim()
            value = value.substringBefore('<').trim()
            return value.takeIf { it.isNotEmpty() && '.' in it }
        }
    }

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        modId: String?,
        compatPackage: String,
        dryRun: Boolean
    ): List<Change> = migrate(
        pass = pass,
        projectDir = projectDir,
        modId = modId,
        compatPackageSupplier = { compatPackage },
        dryRun = dryRun
    )

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        modId: String?,
        compatPackageSupplier: () -> String,
        dryRun: Boolean
    ): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()

        val parsed = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths ->
            paths.filter { it.extension == "java" }
                .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
                .map { file -> parse(file) }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
        if (parsed.isEmpty()) return emptyList()

        val preflightIndex = collectTypes(parsed, generatedIdentified = null)
        val alreadyReferencesCompat = parsed.any(::referencesCompat)
        if (!alreadyReferencesCompat &&
            !hasRecipeIdentityCandidate(pass, parsed, preflightIndex)
        ) {
            return emptyList()
        }

        val compatPackage = compatPackageSupplier()
        val index = collectTypes(
            parsed,
            generatedIdentified = "$compatPackage.LegacyRecipeCodecs.Identified"
        )
        val changes = mutableListOf<Change>()
        val updated = mutableMapOf<Path, String>()

        for (source in parsed) {
            val migrated = migrateSource(pass, source, index, compatPackage)
            if (migrated == source.source) continue
            updated[source.file] = migrated
            changes += Change(
                source.file,
                1,
                "Legacy recipe-object id reads use exact loaded-holder identity or an explicit source-owned id",
                "Recipe#getId()",
                "LegacyRecipeIds.id(recipe)",
                Confidence.HIGH,
                RULE_ID
            )
        }

        if (changes.isEmpty() && !alreadyReferencesCompat) return emptyList()

        if (!dryRun) {
            updated.forEach { (file, text) -> file.writeText(text) }
        }
        changes += emitCompatSources(srcDir, compatPackage, modId, dryRun)
        return changes
    }

    private fun parse(file: Path): ParsedSource? {
        val source = runCatching { file.readText() }.getOrNull() ?: return null
        val unit = parser.parse(source).result.orElse(null) ?: return null
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val imports = unit.imports
            .filter { !it.isStatic && !it.isAsterisk }
            .associate { it.name.identifier to it.nameAsString }
        val wildcardImports = unit.imports
            .filter { !it.isStatic && it.isAsterisk }
            .mapTo(linkedSetOf()) { it.nameAsString }
        val starts = buildList {
            add(0)
            source.forEachIndexed { index, character ->
                if (character == '\n') add(index + 1)
            }
        }
        return ParsedSource(file, source, unit, packageName, imports, wildcardImports, starts)
    }

    private fun collectTypes(
        sources: List<ParsedSource>,
        generatedIdentified: String?
    ): TypeIndex {
        val declarations = sources.flatMap { source ->
            supportedDeclarations(source.unit).map { declaration -> source to declaration }
        }
        val sourceTypes = declarations
            .mapTo(linkedSetOf()) { (source, declaration) -> source.ownerName(declaration) }
        val types = mutableMapOf<String, TypeInfo>()
        for ((source, declaration) in declarations) {
            val owner = source.ownerName(declaration)
            val variables = typeVariables(declaration)
            val parents = parentTypes(declaration).mapNotNull { parent ->
                source.typeRef(parent, sourceTypes, variables, declaration) as? NamedType
            }
            val ownsGetId = declaration.members
                .filterIsInstance<MethodDeclaration>()
                .any { method ->
                    val effectivelyPublic = method.isPublic ||
                        declaration is ClassOrInterfaceDeclaration &&
                        declaration.isInterface &&
                        !method.isPrivate
                    effectivelyPublic &&
                        !method.isStatic &&
                        method.nameAsString == "getId" &&
                        method.parameters.isEmpty() &&
                        source.typeRef(
                            method.type,
                            sourceTypes,
                            variables + method.typeParameters.map { it.nameAsString },
                            declaration
                        ) == NamedType(RESOURCE_LOCATION)
                }
            types[owner] = TypeInfo(
                declarationTypeParameters(declaration),
                parents,
                ownsGetId
            )
        }
        return TypeIndex(
            types,
            sourceTypes,
            generatedIdentified
        )
    }

    private fun referencesCompat(source: ParsedSource): Boolean {
        val executable = JavaSourceText.maskCommentsAndLiterals(source.source)
        return executable.contains("LegacyRecipeIds.id(") ||
            executable.contains("LegacyRecipeIds.SourceOwned") ||
            executable.contains("LegacyRecipeIds.PendingSourceOwned")
    }

    private fun hasRecipeIdentityCandidate(
        pass: StructuralRefactorPass,
        sources: List<ParsedSource>,
        index: TypeIndex
    ): Boolean = sources.any { source ->
        val hasSourceOwnedRecipe = supportedDeclarations(source.unit).any { declaration ->
            if (declaration is ClassOrInterfaceDeclaration && declaration.isInterface) {
                false
            } else {
                val owner = source.ownerName(declaration)
                index.isRecipe(owner) && index.hasSourceGetId(owner)
            }
        }
        hasSourceOwnedRecipe || run {
            val typing = SourceValueTypes(pass, source.source)
            source.unit.findAll(MethodCallExpr::class.java).any { call ->
                isRecipeIdReadCandidate(call, source, index, typing)
            }
        }
    }

    private fun supportedDeclarations(unit: CompilationUnit): List<TypeDeclaration<*>> =
        buildList {
            addAll(unit.findAll(ClassOrInterfaceDeclaration::class.java))
            addAll(unit.findAll(RecordDeclaration::class.java))
            addAll(unit.findAll(EnumDeclaration::class.java))
        }

    private fun parentTypes(declaration: TypeDeclaration<*>): List<ClassOrInterfaceType> =
        when (declaration) {
            is ClassOrInterfaceDeclaration ->
                declaration.extendedTypes + declaration.implementedTypes
            is RecordDeclaration -> declaration.implementedTypes.toList()
            is EnumDeclaration -> declaration.implementedTypes.toList()
            else -> emptyList()
        }

    private fun implementedTypes(declaration: TypeDeclaration<*>): List<ClassOrInterfaceType> =
        when (declaration) {
            is ClassOrInterfaceDeclaration -> declaration.implementedTypes.toList()
            is RecordDeclaration -> declaration.implementedTypes.toList()
            is EnumDeclaration -> declaration.implementedTypes.toList()
            else -> emptyList()
        }

    private fun declarationTypeParameters(declaration: TypeDeclaration<*>): List<String> =
        when (declaration) {
            is ClassOrInterfaceDeclaration ->
                declaration.typeParameters.map { it.nameAsString }
            is RecordDeclaration -> declaration.typeParameters.map { it.nameAsString }
            else -> emptyList()
        }

    private fun typeVariables(declaration: TypeDeclaration<*>): Set<String> {
        val variables = linkedSetOf<String>()
        var current: Node? = declaration
        while (current is TypeDeclaration<*>) {
            declarationTypeParameters(current).mapTo(variables) { it }
            current = current.parentNode.orElse(null)
        }
        return variables
    }

    private fun isRecipeIdReadCandidate(
        call: MethodCallExpr,
        parsed: ParsedSource,
        index: TypeIndex,
        typing: SourceValueTypes
    ): Boolean {
        if (call.nameAsString != "getId" ||
            call.arguments.isNotEmpty() ||
            call.scope.isEmpty ||
            isJeiNativeHolderBoundary(call, parsed, index)
        ) {
            return false
        }
        val receiverType = provenReceiverType(call, parsed, index, typing)
        return index.isRecipe(receiverType) && !index.hasSourceGetId(receiverType)
    }

    private fun provenReceiverType(
        call: MethodCallExpr,
        parsed: ParsedSource,
        index: TypeIndex,
        typing: SourceValueTypes
    ): String? {
        val receiver = call.scope.orElse(null) ?: return null
        val inferred = typing.typeOf(receiver.toString(), parsed.offset(call))
        if (index.isRecipe(inferred) || index.hasSourceGetId(inferred)) return inferred
        val name = receiver as? NameExpr ?: return null

        val lambda = call.findAncestor(LambdaExpr::class.java).orElse(null)
        if (lambda != null) {
            val parameterIndex = lambda.parameters.indexOfFirst {
                it.nameAsString == name.nameAsString
            }
            if (parameterIndex >= 0) {
                return provenLambdaParameterType(
                    lambda,
                    parameterIndex,
                    call,
                    parsed,
                    index
                )
            }
        }
        return provenMethodParameterType(name, call, parsed, index)
    }

    private fun provenLambdaParameterType(
        lambda: LambdaExpr,
        parameterIndex: Int,
        call: MethodCallExpr,
        parsed: ParsedSource,
        index: TypeIndex
    ): String? {
        val variable = lambda.parentNode.orElse(null) as? VariableDeclarator ?: return null
        if (variable.initializer.orElse(null) !== lambda) return null
        val owner = ownerDeclaration(call) ?: return null
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null)
        val variables = typeVariables(owner) +
            method?.typeParameters?.map { it.nameAsString }.orEmpty()
        val target = parsed.typeRef(variable.type, index.sourceTypes, variables, owner)
            as? NamedType ?: return null
        if (target.raw != PREDICATE || target.arguments.size != 1 || parameterIndex != 0) {
            return null
        }
        val input = when (val argument = target.arguments.single()) {
            is WildcardTypeRef ->
                if (argument.boundKind == WildcardBound.SUPER) argument.bound else null
            else -> argument
        } ?: return null
        return provenRelevantType(input, method, owner, parsed, index, mutableSetOf())
    }

    private fun provenMethodParameterType(
        receiver: NameExpr,
        call: MethodCallExpr,
        parsed: ParsedSource,
        index: TypeIndex
    ): String? {
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
        val parameter = method.parameters.singleOrNull {
            it.nameAsString == receiver.nameAsString
        } ?: return null
        val owner = ownerDeclaration(method) ?: return null
        val variables = typeVariables(owner) + method.typeParameters.map { it.nameAsString }
        val declared = parsed.typeRef(parameter.type, index.sourceTypes, variables, owner)
        return provenRelevantType(declared, method, owner, parsed, index, mutableSetOf())
    }

    private fun provenRelevantType(
        type: TypeRef,
        method: MethodDeclaration?,
        owner: TypeDeclaration<*>,
        parsed: ParsedSource,
        index: TypeIndex,
        seenVariables: MutableSet<String>
    ): String? = when (type) {
        is NamedType -> type.render().takeIf {
            index.isRecipe(it) || index.hasSourceGetId(it)
        }
        is TypeVariable -> {
            if (!seenVariables.add(type.name)) {
                null
            } else {
                val parameter = visibleTypeParameter(type.name, method, owner)
                val variables = typeVariables(owner) +
                    method?.typeParameters?.map { it.nameAsString }.orEmpty()
                val candidates = parameter?.typeBound?.mapNotNull { bound ->
                    provenRelevantType(
                        parsed.typeRef(bound, index.sourceTypes, variables, owner),
                        method,
                        owner,
                        parsed,
                        index,
                        seenVariables.toMutableSet()
                    )
                }.orEmpty()
                candidates.firstOrNull(index::hasSourceGetId)
                    ?: candidates.firstOrNull(index::isRecipe)
            }
        }
        else -> null
    }

    private fun visibleTypeParameter(
        name: String,
        method: MethodDeclaration?,
        owner: TypeDeclaration<*>
    ): TypeParameter? {
        method?.typeParameters?.firstOrNull { it.nameAsString == name }?.let { return it }
        var current: Node? = owner
        while (current != null) {
            if (current is TypeDeclaration<*>) {
                declarationTypeParameterNodes(current)
                    .firstOrNull { it.nameAsString == name }
                    ?.let { return it }
            }
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun ownerDeclaration(node: Node): TypeDeclaration<*>? =
        generateSequence<Node>(node) { current ->
            current.parentNode.orElse(null)
        }.filterIsInstance<TypeDeclaration<*>>().firstOrNull()

    private fun declarationTypeParameterNodes(
        declaration: TypeDeclaration<*>
    ): List<TypeParameter> = when (declaration) {
        is ClassOrInterfaceDeclaration -> declaration.typeParameters.toList()
        is RecordDeclaration -> declaration.typeParameters.toList()
        else -> emptyList()
    }

    private fun migrateSource(
        pass: StructuralRefactorPass,
        parsed: ParsedSource,
        index: TypeIndex,
        compatPackage: String
    ): String {
        val unit = parser.parse(parsed.source).result.orElse(null) ?: return parsed.source
        LexicalPreservingPrinter.setup(unit)
        val typing = SourceValueTypes(pass, parsed.source)
        var changed = false

        for (call in unit.findAll(MethodCallExpr::class.java)) {
            if (!isRecipeIdReadCandidate(call, parsed, index, typing)) continue

            val receiver = call.scope.get()
            val replacement = parser.parseExpression<MethodCallExpr>(
                "LegacyRecipeIds.id(${receiver})"
            ).result.orElse(null) ?: continue
            call.replace(replacement)
            changed = true
        }

        for (declaration in supportedDeclarations(unit)) {
            if (declaration is ClassOrInterfaceDeclaration && declaration.isInterface) continue
            val owner = parsed.ownerName(declaration)
            if (!index.isRecipe(owner) || !index.hasSourceGetId(owner)) continue

            val marker = if (index.hasGeneratedPendingPath(owner)) {
                "LegacyRecipeIds.PendingSourceOwned"
            } else {
                "LegacyRecipeIds.SourceOwned"
            }
            val implemented = implementedTypes(declaration).map { it.toString() }
            val alreadyImplementsSourceOwned = implemented.any {
                    it == marker || it.endsWith(".$marker") ||
                        marker == "LegacyRecipeIds.SourceOwned" &&
                        (it == "LegacyRecipeIds.PendingSourceOwned" ||
                            it.endsWith(".LegacyRecipeIds.PendingSourceOwned"))
                }
            if (!alreadyImplementsSourceOwned) {
                when (declaration) {
                    is ClassOrInterfaceDeclaration ->
                        declaration.addImplementedType(marker)
                    is RecordDeclaration ->
                        declaration.addImplementedType(marker)
                    is EnumDeclaration ->
                        declaration.addImplementedType(marker)
                }
                changed = true
            }
            if (declaration.members.filterIsInstance<MethodDeclaration>().none {
                    it.nameAsString == "modporter${'$'}sourceRecipeId" && it.parameters.isEmpty()
                }) {
                val bridge = parser.parseBodyDeclaration<MethodDeclaration>(
                    """
                    @Override
                    public ResourceLocation modporter${'$'}sourceRecipeId() {
                        return getId();
                    }
                    """.trimIndent()
                ).result.orElse(null) ?: continue
                declaration.addMember(bridge)
                changed = true
            }
        }

        if (!changed) return parsed.source
        var result = LexicalPreservingPrinter.print(unit)
        result = JavaSourceText.addImport(result, "$compatPackage.LegacyRecipeIds")
        if (result.contains("modporter${'$'}sourceRecipeId") &&
            !result.contains("import $RESOURCE_LOCATION;")
        ) {
            result = JavaSourceText.addImport(result, RESOURCE_LOCATION)
        }
        return result
    }

    /**
     * JEI 19's vanilla recipe types already carry RecipeHolder. A separate JEI
     * migration must change this boundary to RecipeHolder<CraftingRecipe>.id().
     * Replacing the old raw parameter call here would conceal that required API repair.
     */
    private fun isJeiNativeHolderBoundary(
        call: MethodCallExpr,
        parsed: ParsedSource,
        index: TypeIndex
    ): Boolean {
        val receiver = call.scope.orElse(null) as? NameExpr ?: return false
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return false
        if (method.nameAsString != "transferRecipe" || method.parameters.size != 6) return false
        if (!method.isPublic || method.isStatic || method.typeParameters.isNotEmpty()) return false
        if (method.parameters.any { it.isVarArgs }) return false
        if (method.parameters[1].nameAsString != receiver.nameAsString) return false
        val owner = generateSequence<Node>(method) { node ->
            node.parentNode.orElse(null)
        }.filterIsInstance<TypeDeclaration<*>>().firstOrNull() ?: return false
        val expected = index.jeiTransferArguments(parsed.ownerName(owner)) ?: return false
        val variables = typeVariables(owner)
        fun resolved(type: Type): TypeRef =
            parsed.typeRef(type, index.sourceTypes, variables, owner)

        return index.isSubtype(resolved(method.type), JEI_TRANSFER_ERROR) &&
            resolved(method.parameters[0].type) == expected.first &&
            resolved(method.parameters[1].type) == expected.second &&
            resolved(method.parameters[2].type) == NamedType(JEI_SLOTS_VIEW) &&
            resolved(method.parameters[3].type) == NamedType(PLAYER) &&
            resolved(method.parameters[4].type) == PrimitiveTypeRef("boolean") &&
            resolved(method.parameters[5].type) == PrimitiveTypeRef("boolean")
    }

    private fun emitCompatSources(
        srcDir: Path,
        compatPackage: String,
        modId: String?,
        dryRun: Boolean
    ): List<Change> {
        val directory = srcDir.resolve(compatPackage.replace('.', '/'))
        val modIdArgument = modId?.let { "modid = \"$it\"" }.orEmpty()
        val clientSubscriberArguments = listOfNotNull(
            modIdArgument.takeIf { it.isNotEmpty() },
            "value = Dist.CLIENT"
        ).joinToString(", ")
        val outputs = listOf(
            "LegacyRecipeIds.java" to renderCompatTemplate(
                "/compat/LegacyRecipeIds.java.template",
                compatPackage
            ),
            "LegacyRecipeIdentityEvents.java" to renderCompatTemplate(
                "/compat/LegacyRecipeIdentityEvents.java.template",
                compatPackage,
                "@MOD_ID_ARGUMENT@" to modIdArgument
            ),
            "LegacyRecipeIdentityClientEvents.java" to renderCompatTemplate(
                "/compat/LegacyRecipeIdentityClientEvents.java.template",
                compatPackage,
                "@CLIENT_SUBSCRIBER_ARGUMENTS@" to clientSubscriberArguments
            )
        )

        return outputs.mapNotNull { (fileName, content) ->
            val file = directory.resolve(fileName)
            if (Files.exists(file) && file.readText() == content) return@mapNotNull null
            if (!dryRun) {
                directory.createDirectories()
                file.writeText(content)
            }
            Change(
                file,
                1,
                "Generated exact recipe identity compatibility support",
                "(none)",
                fileName,
                Confidence.HIGH,
                RULE_ID
            )
        }
    }

    private fun renderCompatTemplate(
        resource: String,
        compatPackage: String,
        vararg replacements: Pair<String, String>
    ): String {
        var content = requireNotNull(javaClass.getResource(resource)) {
            "Missing compatibility template resource $resource"
        }.readText().replace("@PACKAGE@", compatPackage)
        replacements.forEach { (token, value) ->
            content = content.replace(token, value)
        }
        return content
    }
}
