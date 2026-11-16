package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Recipe JSON, network, and datagen surfaces that Forge 1.20.1 exposed and NeoForge 1.21.1 removed.
 * Each rewrite is admitted by platform facts (1.21.1 member table, Forge 1.20.1 source of the removed
 * member) plus types the source itself proves; no project or library name selects a rewrite.
 *
 * - `Ingredient#toJson()` is the allow-empty ingredient codec (`[]` for an empty ingredient, exactly
 *   as 1.20.1 wrote it).
 * - Forge's `Ingredient.merge(parts)` was `fromValues(parts.stream().flatMap(i -> stream(i.values)))`;
 *   1.21.1 custom ingredients have no values (`getValues()` throws), which is what they contribute.
 * - `RecipeSerializer#fromNetwork` no longer exists; after the source's own negative type guard the
 *   call goes to the guarded project serializer that still declares it, from a registry buffer.
 * - Forge's static `RecipeManager.fromJson(id, json[, context])` dispatched on the JSON `type` through
 *   the serializer registry. Serializers the source proves to be its own legacy serializers keep the
 *   explicit id; every other serializer decodes through 1.21.1's `Recipe.CODEC`.
 * - `SpecialRecipeBuilder.special` takes the recipe factory in 1.21.1. A helper that only forwarded a
 *   `Supplier` of a `SimpleCraftingRecipeSerializer` takes the factory the project registered that
 *   serializer with (see [collectSimpleCraftingSerializerAccessors]).
 */
internal fun StructuralRefactorPass.migrateLegacyRecipeSerializationSurfaces(
    source: String,
    factoryHints: RecipeSerializerFactoryHints = RecipeSerializerFactoryHints(emptyMap(), emptyMap())
): String {
    var result = migrateIngredientJsonWrites(source)
    result = migrateIngredientMergeCalls(result)
    result = migrateGuardedSerializerNetworkReads(result)
    result = migrateRecipeManagerJsonReads(result)
    result = migrateSpecialRecipeFactoryHelpers(result, factoryHints.serializerAccessorFactories)
    return result
}

private const val RECIPE_SERIALIZER_TYPE = "net.minecraft.world.item.crafting.RecipeSerializer"
private const val RECIPE_MANAGER_TYPE = "net.minecraft.world.item.crafting.RecipeManager"
private const val SIMPLE_CRAFTING_SERIALIZER_TYPE = "net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer"
private const val SPECIAL_RECIPE_BUILDER_TYPE = "net.minecraft.data.recipes.SpecialRecipeBuilder"
private const val FRIENDLY_BYTE_BUF_TYPE = "net.minecraft.network.FriendlyByteBuf"
private const val REGISTRY_BYTE_BUF_TYPE = "net.minecraft.network.RegistryFriendlyByteBuf"
private const val JSON_OPS_TYPE = "com.mojang.serialization.JsonOps"

/** Simple name usable for [fqn] here: an existing or addable import, never a clash. */
private fun RecipeSurfaceSource.importable(fqn: String): Boolean {
    val simple = fqn.substringAfterLast('.')
    val code = source
    val clash = Regex("""(?m)^\s*import\s+(?!static\b)([\w.]+)\.${Regex.escape(simple)}\s*;""").findAll(code)
        .any { "${it.groupValues[1]}.$simple" != fqn }
    val declared = unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }
    return !clash && (!declared || typing.isTypeName(simple, fqn))
}

private fun StructuralRefactorPass.withImports(source: String, imports: List<String>): String {
    var result = source
    val pkg = packageNameOf(source)
    imports.distinct().forEach { fqn ->
        val owner = fqn.substringBeforeLast('.')
        if (owner != pkg && owner != "java.lang") result = addExecutableImportIfMissing(result, fqn)
    }
    return result
}

/** Every identifier the compilation unit already spells, so generated locals and lambda names stay fresh. */
private fun RecipeSurfaceSource.freshName(base: String, taken: MutableSet<String>): String {
    val used = unit.findAll(SimpleName::class.java).mapTo(HashSet()) { it.identifier }
    var candidate = base
    var counter = 1
    while (candidate in used || candidate in taken) candidate = base + counter++
    taken += candidate
    return candidate
}

/** The single bound of a type variable declared by an enclosing method or type, for a name typed by it. */
private fun RecipeSurfaceSource.typeVariableBound(name: NameExpr): String? {
    val identifier = name.nameAsString
    var scope: Node? = name.parentNode.orElse(null)
    var declaredType: String? = null
    while (scope != null && declaredType == null) {
        declaredType = when (scope) {
            is com.github.javaparser.ast.body.CallableDeclaration<*> ->
                scope.parameters.firstOrNull { it.nameAsString == identifier }?.type?.toString()
            is BlockStmt -> scope.findAll(VariableDeclarator::class.java)
                .firstOrNull { it.nameAsString == identifier && start(it) < start(name) }?.type?.toString()
            is ClassOrInterfaceDeclaration -> scope.fields.flatMap { it.variables }
                .firstOrNull { it.nameAsString == identifier }?.type?.toString()
            else -> null
        }
        if (declaredType == null) scope = scope.parentNode.orElse(null)
    }
    val variable = declaredType?.takeIf { Regex("""[A-Za-z_$][\w$]*""").matches(it) } ?: return null
    var owner: Node? = scope
    while (owner != null) {
        if (owner is NodeWithTypeParameters<*>) {
            val parameter = owner.typeParameters.firstOrNull { it.nameAsString == variable }
            if (parameter != null) return parameter.typeBound.singleOrNull()?.toString()
        }
        owner = owner.parentNode.orElse(null)
    }
    return null
}

/** Element type of a collection expression, reading through a type-variable root by its bound. */
private fun RecipeSurfaceSource.elementTypeOf(collection: Expression): String? {
    typing.iterableElementType(collection.toString(), start(collection))?.let { return it }
    var root: Expression = collection
    while (root is FieldAccessExpr) root = root.scope
    val name = root as? NameExpr ?: return null
    val bound = typeVariableBound(name) ?: return null
    val text = collection.toString()
    if (!text.startsWith(name.nameAsString)) return null
    return typing.iterableElementType("((${bound}) ${name.nameAsString})${text.substring(name.nameAsString.length)}", start(collection))
}

private fun RecipeSurfaceSource.isIngredientValue(receiver: Expression): Boolean {
    if (typing.typeOf(receiver.toString(), start(receiver)) == SURFACE_INGREDIENT) return true
    val name = receiver as? NameExpr ?: return false
    var lambda: Node? = name.parentNode.orElse(null)
    while (lambda != null && !(lambda is LambdaExpr && lambda.parameters.any { it.nameAsString == name.nameAsString })) {
        lambda = lambda.parentNode.orElse(null)
    }
    if (lambda !is LambdaExpr || lambda.parameters.size != 1) return false
    val parameter = lambda.parameters.single()
    if (!parameter.type.isUnknownType) return typing.isTypeName(parameter.type.toString(), SURFACE_INGREDIENT)
    val call = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return false
    if (call.nameAsString != "forEach" || call.arguments.singleOrNull() !== lambda) return false
    if (surfaceReassigned(lambda, name.nameAsString)) return false
    val collection = call.scope.orElse(null) ?: return false
    return elementTypeOf(collection) == SURFACE_INGREDIENT
}

/** 1.20.1 `Ingredient#toJson()` wrote `[]` for empty ingredients: the 1.21.1 allow-empty `Ingredient.CODEC`. */
private fun StructuralRefactorPass.migrateIngredientJsonWrites(source: String): String {
    if (!source.contains(".toJson()")) return source
    if (TargetMembers.hasMethod(SURFACE_INGREDIENT, "toJson") || !TargetMembers.hasField(SURFACE_INGREDIENT, "CODEC")) {
        return source
    }
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    if (!surface.importable(SURFACE_INGREDIENT) || !surface.importable(JSON_OPS_TYPE)) return source
    for (call in surface.unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != "toJson" || call.arguments.isNotEmpty()) continue
        val receiver = call.scope.orElse(null) ?: continue
        if (!surface.isIngredientValue(receiver)) continue
        surface.replace(call, "Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, ${surface.text(receiver)}).getOrThrow()")
    }
    if (!surface.changed) return source
    val result = surface.apply() ?: return source
    return withImports(result, listOf(SURFACE_INGREDIENT, JSON_OPS_TYPE))
}

/**
 * Forge 1.20.1 `Ingredient.merge(Collection<Ingredient>)` returned
 * `fromValues(parts.stream().flatMap(i -> Arrays.stream(i.values)))`. In 1.21.1 a custom ingredient
 * has no values (`getValues()` throws), so custom parts contribute nothing, as their empty Forge
 * value arrays did.
 */
private fun StructuralRefactorPass.migrateIngredientMergeCalls(source: String): String {
    if (!source.contains("merge(")) return source
    if (TargetMembers.hasMethod(SURFACE_INGREDIENT, "merge") ||
        !TargetMembers.hasMethod(SURFACE_INGREDIENT, "fromValues") ||
        !TargetMembers.hasMethod(SURFACE_INGREDIENT, "getValues") ||
        !TargetMembers.hasMethod(SURFACE_INGREDIENT, "isCustom")
    ) {
        return source
    }
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    if (!surface.importable("java.util.Arrays")) return source
    val taken = mutableSetOf<String>()
    for (call in surface.unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != "merge" || call.arguments.size != 1) continue
        val owner = call.scope.orElse(null) ?: continue
        if (!surface.typing.isTypeName(owner.toString(), SURFACE_INGREDIENT)) continue
        val parts = call.arguments.single()
        val partsText = surface.text(parts).let {
            if (parts is NameExpr || parts is MethodCallExpr || parts is FieldAccessExpr) it else "($it)"
        }
        val part = surface.freshName("part", taken)
        surface.replace(
            call,
            "${surface.text(owner)}.fromValues($partsText.stream().filter($part -> !$part.isCustom())" +
                ".flatMap($part -> Arrays.stream($part.getValues())))"
        )
    }
    if (!surface.changed) return source
    val result = surface.apply() ?: return source
    return withImports(result, listOf("java.util.Arrays"))
}

private fun exitsAbruptly(statement: Statement): Boolean = when (statement) {
    is ThrowStmt, is ReturnStmt -> true
    is BlockStmt -> statement.statements.lastOrNull()?.let(::exitsAbruptly) == true
    else -> false
}

/** `if (!(value instanceof S)) throw/return;` statements of [block] that precede [statement]. */
private fun guardedTypes(block: BlockStmt, statement: Statement, value: String): List<InstanceOfExpr> {
    val index = block.statements.indexOfFirst { it === statement }
    if (index < 0) return emptyList()
    return block.statements.take(index).filterIsInstance<IfStmt>().mapNotNull { guard ->
        if (guard.elseStmt.isPresent || !exitsAbruptly(guard.thenStmt)) return@mapNotNull null
        val negated = surfaceUnwrapped(guard.condition) as? UnaryExpr ?: return@mapNotNull null
        if (negated.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return@mapNotNull null
        val test = surfaceUnwrapped(negated.expression) as? InstanceOfExpr ?: return@mapNotNull null
        test.takeIf { it.pattern.isEmpty && (it.expression as? NameExpr)?.nameAsString == value }
    }
}

/** The canonical name of a type the source spells as [typeText], when it is a non-platform type. */
private fun RecipeSurfaceSource.projectTypeName(typeText: String): String? {
    val raw = typeText.substringBefore('<').trim()
    val qualified = typing.typeOf("($raw) null", 0) ?: return null
    if (TargetMembers.isPlatformClass(qualified) || qualified.startsWith("java.")) return null
    return qualified
}

/**
 * `RecipeSerializer#fromNetwork(id, buffer)` is gone in 1.21.1. Behind the source's own
 * `if (!(serializer instanceof S)) throw ...;` guard, the call reaches S's retained legacy reader;
 * 1.21.1 decodes recipe payloads only from a `RegistryFriendlyByteBuf`, so a plain buffer parameter
 * that feeds it is narrowed.
 */
private fun StructuralRefactorPass.migrateGuardedSerializerNetworkReads(source: String): String {
    if (!source.contains("fromNetwork(")) return source
    if (TargetMembers.declaresInHierarchy(RECIPE_SERIALIZER_TYPE, "fromNetwork", 2)) return source
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    val typing = surface.typing
    var narrowed = false
    val narrowedParameters = mutableSetOf<Parameter>()
    for (call in surface.unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != "fromNetwork" || call.arguments.size != 2) continue
        val value = call.scope.orElse(null) as? NameExpr ?: continue
        if (typing.typeOf(value.nameAsString, surface.start(value)) != RECIPE_SERIALIZER_TYPE) continue
        var statement: Node = call
        while (statement.parentNode.orElse(null) !is BlockStmt) statement = statement.parentNode.orElse(null) ?: break
        val block = statement.parentNode.orElse(null) as? BlockStmt ?: continue
        val callable = surfaceEnclosingCallable(call) ?: continue
        if (surfaceReassigned(callable, value.nameAsString)) continue
        val guard = guardedTypes(block, statement as Statement, value.nameAsString).lastOrNull() ?: continue
        if (surface.projectTypeName(guard.type.toString()) == null) continue
        val buffer = call.arguments[1] as? NameExpr ?: continue
        val bufferParameter = (callable as? com.github.javaparser.ast.body.CallableDeclaration<*>)
            ?.parameters?.firstOrNull { it.nameAsString == buffer.nameAsString }
        val bufferType = typing.typeOf(buffer.nameAsString, surface.start(buffer))
        if (bufferType != REGISTRY_BYTE_BUF_TYPE) {
            if (bufferType != FRIENDLY_BYTE_BUF_TYPE || bufferParameter == null ||
                surfaceReassigned(callable, buffer.nameAsString) ||
                !typing.isTypeName(bufferParameter.type.toString(), FRIENDLY_BYTE_BUF_TYPE)
            ) {
                continue
            }
            if (narrowedParameters.add(bufferParameter)) {
                surface.replace(bufferParameter.type, "RegistryFriendlyByteBuf")
                narrowed = true
            }
        }
        surface.replace(value, "((${guard.type}) ${value.nameAsString})")
    }
    if (!surface.changed) return source
    var result = surface.apply() ?: return source
    if (narrowed) {
        result = withImports(result, listOf(REGISTRY_BYTE_BUF_TYPE))
        result = removeUnusedSimpleImports(result, listOf(FRIENDLY_BYTE_BUF_TYPE))
    }
    return result
}

/**
 * Forge 1.20.1 `RecipeManager.fromJson(id, json[, context])` looked the JSON `type` up in the
 * serializer registry (JsonSyntaxException when unknown) and called its `fromJson(id, json)`. The
 * legacy serializers this source itself tests or casts RecipeSerializer values to keep that explicit
 * id; any other serializer decodes through 1.21.1's `Recipe.CODEC`.
 */
private fun StructuralRefactorPass.migrateRecipeManagerJsonReads(source: String): String {
    if (!source.contains("fromJson(") || !source.contains("RecipeManager")) return source
    val removed = TargetMembers.inheritedMethod(
        RECIPE_MANAGER_TYPE,
        "fromJson",
        "(Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonObject;Lnet/minecraft/core/HolderLookup\$Provider;)Lnet/minecraft/world/item/crafting/RecipeHolder;"
    )
    if (removed == null || removed.access == "public" || !TargetMembers.hasField(SURFACE_RECIPE, "CODEC")) return source
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    val typing = surface.typing

    val legacySerializers = buildList {
        surface.unit.findAll(InstanceOfExpr::class.java).forEach { test ->
            if (typing.typeOf(test.expression.toString(), surface.start(test.expression)) == RECIPE_SERIALIZER_TYPE) {
                add(test.type.toString().substringBefore('<').trim())
            }
        }
        surface.unit.findAll(CastExpr::class.java).forEach { cast ->
            if (typing.typeOf(cast.expression.toString(), surface.start(cast.expression)) == RECIPE_SERIALIZER_TYPE) {
                add(cast.type.toString().substringBefore('<').trim())
            }
        }
    }.distinct().filter { surface.projectTypeName(it) != null }
    if (legacySerializers.isEmpty()) return source
    val imports = listOf(
        SURFACE_RESOURCE_LOCATION,
        "net.minecraft.util.GsonHelper",
        RECIPE_SERIALIZER_TYPE,
        "net.minecraft.core.registries.BuiltInRegistries",
        "com.google.gson.JsonSyntaxException",
        "com.google.gson.JsonParseException",
        SURFACE_RECIPE,
        JSON_OPS_TYPE
    )
    if (imports.any { !surface.importable(it) }) return source
    val taken = mutableSetOf<String>()

    for (call in surface.unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != "fromJson" || call.arguments.size !in 2..3) continue
        if (!call.scope.map { typing.isTypeName(it.toString(), RECIPE_MANAGER_TYPE) }.orElse(false)) continue
        val declarator = call.parentNode.orElse(null) as? VariableDeclarator ?: continue
        if (declarator.initializer.orElse(null) !== call) continue
        val declaration = declarator.parentNode.orElse(null) as? com.github.javaparser.ast.expr.VariableDeclarationExpr
            ?: continue
        if (declaration.variables.size != 1) continue
        val statement = declaration.parentNode.orElse(null) as? ExpressionStmt ?: continue
        if (statement.parentNode.orElse(null) !is BlockStmt) continue
        val json = call.arguments[1] as? NameExpr ?: continue
        val context = call.arguments.getOrNull(2)
        if (context != null && !isPureSurfaceValue(context)) continue
        val callable = surfaceEnclosingCallable(call) ?: continue
        if (surfaceReassigned(callable, json.nameAsString)) continue

        val indent = surface.indentOf(statement)
        val nl = surface.newline
        val id = surface.freshName("recipeId", taken)
        val type = surface.freshName("recipeType", taken)
        val serializer = surface.freshName("recipeSerializer", taken)
        val legacyReads = legacySerializers.joinToString("") { legacy ->
            "$serializer instanceof $legacy ? (($legacy) $serializer).fromJson($id, ${json.nameAsString}) : "
        }
        val modifiers = declaration.modifiers.joinToString("") { "${it.keyword.asString()} " }
        val annotations = declaration.annotations.joinToString("") { "$it " }
        val replacement = buildString {
            append("ResourceLocation $id = ${surface.text(call.arguments[0])};").append(nl)
            append(indent).append("String $type = GsonHelper.getAsString(${json.nameAsString}, \"type\");").append(nl)
            append(indent).append("RecipeSerializer<?> $serializer = BuiltInRegistries.RECIPE_SERIALIZER.getOptional(ResourceLocation.parse($type))").append(nl)
            append(indent).append("    .orElseThrow(() -> new JsonSyntaxException(\"Invalid or unsupported recipe type '\" + $type + \"'\"));").append(nl)
            append(indent).append(annotations).append(modifiers)
                .append("${surface.text(declaration.elementType)} ${declarator.nameAsString} = ")
                .append(legacyReads)
                .append("Recipe.CODEC.parse(JsonOps.INSTANCE, ${json.nameAsString}).getOrThrow(JsonParseException::new);")
        }
        surface.replace(statement, replacement)
    }
    if (!surface.changed) return source
    var result = surface.apply() ?: return source
    result = withImports(result, imports)
    return removeUnusedSimpleImports(
        result,
        listOf(
            RECIPE_MANAGER_TYPE,
            "net.neoforged.neoforge.common.conditions.ICondition.IContext",
            "net.minecraftforge.common.crafting.conditions.ICondition.IContext"
        )
    )
}

/**
 * `SpecialRecipeBuilder.special` took the `SimpleCraftingRecipeSerializer` in 1.20.1 and takes the
 * recipe factory (`Function<CraftingBookCategory, Recipe<?>>`) in 1.21.1. A non-public helper whose
 * `Supplier<? extends SimpleCraftingRecipeSerializer<?>>` parameter is only forwarded to `special(...)`
 * takes the factory instead, and every call in this source passes the factory its serializer accessor
 * was registered with. An unresolved call keeps the whole helper unchanged.
 */
private fun StructuralRefactorPass.migrateSpecialRecipeFactoryHelpers(
    source: String,
    accessorFactories: Map<String, String>
): String {
    if (accessorFactories.isEmpty() || !source.contains("SpecialRecipeBuilder") ||
        !source.contains("SimpleCraftingRecipeSerializer")
    ) {
        return source
    }
    val special = TargetMembers.inheritedMethod(
        SPECIAL_RECIPE_BUILDER_TYPE,
        "special",
        "(Ljava/util/function/Function;)Lnet/minecraft/data/recipes/SpecialRecipeBuilder;"
    ) ?: return source
    if (!special.signature.startsWith(
            "(Ljava/util/function/Function<Lnet/minecraft/world/item/crafting/CraftingBookCategory;" +
                "Lnet/minecraft/world/item/crafting/Recipe<*>;>;)"
        )
    ) {
        return source
    }
    val surface = RecipeSurfaceSource.parse(this, source) ?: return source
    val typing = surface.typing
    val requiredImports = listOf(
        "java.util.function.Function",
        "net.minecraft.world.item.crafting.CraftingBookCategory",
        SURFACE_RECIPE
    )
    if (requiredImports.any { !surface.importable(it) }) return source
    val factoryImports = mutableListOf<String>()

    fun factoryFor(argument: Expression): String? {
        val reference = argument as? MethodReferenceExpr ?: return null
        val scope = reference.scope.toString().trim()
        val separator = scope.lastIndexOf('.')
        if (separator <= 0) return null
        val owner = typing.typeOf("(${scope.substring(0, separator)}) null", 0) ?: return null
        return accessorFactories["$owner.${scope.substring(separator + 1)}::${reference.identifier}"]
    }

    for (helper in surface.unit.findAll(MethodDeclaration::class.java)) {
        if (helper.isPublic || helper.isProtected) continue
        val body = helper.body.orElse(null) ?: continue
        for ((index, parameter) in helper.parameters.withIndex()) {
            val supplier = parameter.type as? ClassOrInterfaceType ?: continue
            if (!typing.isTypeName(supplier.nameWithScope, "java.util.function.Supplier")) continue
            val argument = supplier.typeArguments.orElse(null)?.singleOrNull() ?: continue
            val supplied = (if (argument.isWildcardType) argument.asWildcardType().extendedType.orElse(null) else argument)
                as? ClassOrInterfaceType ?: continue
            if (!typing.isTypeName(supplied.nameWithScope, SIMPLE_CRAFTING_SERIALIZER_TYPE)) continue
            val use = body.findAll(NameExpr::class.java).filter { it.nameAsString == parameter.nameAsString }
                .singleOrNull() ?: continue
            val get = use.parentNode.orElse(null) as? MethodCallExpr ?: continue
            if (get.nameAsString != "get" || get.arguments.isNotEmpty() || get.scope.orElse(null) !== use) continue
            val forward = get.parentNode.orElse(null) as? MethodCallExpr ?: continue
            if (forward.nameAsString != "special" || forward.arguments.singleOrNull() !== get ||
                !forward.scope.map { typing.isTypeName(it.toString(), SPECIAL_RECIPE_BUILDER_TYPE) }.orElse(false)
            ) {
                continue
            }
            val siblings = surface.unit.findAll(MethodDeclaration::class.java).filter {
                it.nameAsString == helper.nameAsString && it.parameters.size == helper.parameters.size
            }
            if (siblings.size != 1) continue
            val calls = surface.unit.findAll(MethodCallExpr::class.java).filter { call ->
                call.nameAsString == helper.nameAsString &&
                    call.arguments.size == helper.parameters.size &&
                    call.scope.map { it is ThisExpr }.orElse(true)
            }
            if (calls.isEmpty()) continue
            val factories = calls.map { factoryFor(it.arguments[index]) }
            if (factories.any { it == null }) continue

            surface.replace(parameter.type, "Function<CraftingBookCategory, Recipe<?>>")
            surface.replace(get, parameter.nameAsString)
            calls.zip(factories.filterNotNull()).forEach { (call, factory) ->
                val factoryClass = factory.substringBefore("::")
                val simple = factoryClass.substringAfterLast('.')
                val spelled = if (surface.importable(factoryClass)) {
                    factoryImports += factoryClass
                    simple
                } else {
                    factoryClass
                }
                surface.replace(call.arguments[index], "$spelled::${factory.substringAfter("::")}")
            }
        }
    }
    if (!surface.changed) return source
    var result = surface.apply() ?: return source
    result = withImports(result, requiredImports + factoryImports.distinct())
    return removeUnusedSimpleImports(
        result,
        listOf("java.util.function.Supplier", SIMPLE_CRAFTING_SERIALIZER_TYPE) +
            accessorFactories.keys.map { it.substringBefore("::").substringBeforeLast('.') }.distinct()
    )
}

/**
 * Serializer accessors whose value is a `SimpleCraftingRecipeSerializer` built from a recipe factory,
 * keyed `Owner.CONSTANT::method` (enum constants) or `Owner.FIELD::get` (static holder fields) and
 * mapped to `FactoryClass::new`. Admission follows the NeoForge contract only: a value registered with
 * `DeferredRegister#register(name, supplier)` is what the returned holder's `get()` yields, and the
 * accessor returns exactly that holder's `get()`. Each step is proven from the declaring source.
 */
internal fun StructuralRefactorPass.collectSimpleCraftingSerializerAccessors(javaFiles: List<Path>): Map<String, String> {
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val fileSet = javaFiles.map { it.toAbsolutePath().normalize() }.toSet()
    val result = linkedMapOf<String, String>()
    for (file in javaFiles) {
        val text = runCatching { file.readText() }.getOrNull() ?: continue
        if (!text.contains("SimpleCraftingRecipeSerializer") || !text.contains("DeferredRegister")) continue
        val unit = parser.parse(text).result.orElse(null) ?: continue
        val pkg = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val imports = unit.imports.filter { !it.isStatic && !it.isAsterisk }
            .associate { it.name.identifier to it.nameAsString }

        fun qualifiedName(declaration: TypeDeclaration<*>): String {
            val names = generateSequence<Node>(declaration) { it.parentNode.orElse(null) }
                .filterIsInstance<TypeDeclaration<*>>().map { it.nameAsString }.toList().asReversed()
            return (listOf(pkg) + names).filter { it.isNotBlank() }.joinToString(".")
        }

        fun resolve(name: String): String? {
            if (name.isEmpty()) return null
            val first = name.substringBefore('.')
            if ('.' in name && first.first().isLowerCase()) return name
            imports[first]?.let { return it + name.removePrefix(first) }
            unit.findAll(TypeDeclaration::class.java).firstOrNull { it.nameAsString == first }?.let { declared ->
                return qualifiedName(declared) + name.removePrefix(first)
            }
            // a top-level type of the same package
            val sibling = file.toAbsolutePath().normalize().resolveSibling("$first.java")
            if (sibling in fileSet && sibling.nameWithoutExtension == first) {
                return if (pkg.isBlank()) name else "$pkg.$name"
            }
            return null
        }

        /** `FactoryClass::new` of `() -> new SimpleCraftingRecipeSerializer<>(FactoryClass::new)`. */
        fun factoryOf(expression: Expression): String? {
            val lambda = expression as? LambdaExpr ?: return null
            if (lambda.parameters.isNotEmpty()) return null
            val created = when (val body = lambda.body) {
                is ExpressionStmt -> body.expression
                is BlockStmt -> (body.statements.singleOrNull() as? ReturnStmt)?.expression?.orElse(null)
                else -> null
            } as? ObjectCreationExpr ?: return null
            if (created.anonymousClassBody.isPresent || created.arguments.size != 1) return null
            if (resolve(created.type.nameWithScope) != SIMPLE_CRAFTING_SERIALIZER_TYPE) return null
            val reference = created.arguments.single() as? MethodReferenceExpr ?: return null
            if (reference.identifier != "new") return null
            val factoryClass = resolve(reference.scope.toString()) ?: return null
            return "$factoryClass::new"
        }

        fun isDeferredRegister(scope: Expression): Boolean {
            val field = when (scope) {
                is NameExpr -> scope.nameAsString
                is FieldAccessExpr -> scope.nameAsString
                else -> return false
            }
            return unit.findAll(FieldDeclaration::class.java).any { declaration ->
                declaration.isFinal && declaration.variables.any { variable ->
                    variable.nameAsString == field &&
                        resolve((variable.type as? ClassOrInterfaceType)?.nameWithScope ?: "") in setOf(
                            "net.neoforged.neoforge.registries.DeferredRegister",
                            "net.minecraftforge.registries.DeferredRegister"
                        )
                }
            }
        }

        fun registeredSupplier(value: Expression?): Expression? {
            val register = value as? MethodCallExpr ?: return null
            if (register.nameAsString != "register" || register.arguments.size != 2) return null
            if (!register.scope.map(::isDeferredRegister).orElse(false)) return null
            return register.arguments[1]
        }

        // static holder fields: FIELD = REGISTER.register(name, () -> new SimpleCraftingRecipeSerializer<>(F::new))
        for (declaration in unit.findAll(FieldDeclaration::class.java)) {
            if (!declaration.isStatic || !declaration.isFinal) continue
            val owner = declaration.parentNode.orElse(null) as? TypeDeclaration<*> ?: continue
            for (variable in declaration.variables) {
                val factory = registeredSupplier(variable.initializer.orElse(null))?.let(::factoryOf) ?: continue
                result["${qualifiedName(owner)}.${variable.nameAsString}::get"] = factory
            }
        }

        // enum constants whose constructor registers a supplier argument into a final holder field
        for (enumeration in unit.findAll(EnumDeclaration::class.java)) {
            val owner = qualifiedName(enumeration)
            val holderFields = enumeration.fields.filter { it.isFinal && !it.isStatic }
                .flatMap { it.variables }.map { it.nameAsString }.toSet()
            val accessors = enumeration.methods.filter { method ->
                !method.isStatic && method.parameters.isEmpty() && method.body.isPresent
            }.mapNotNull { method ->
                val returned = (method.body.get().statements.singleOrNull() as? ReturnStmt)
                    ?.expression?.orElse(null) ?: return@mapNotNull null
                var value: Expression = surfaceUnwrapped(returned)
                while (value is CastExpr) value = surfaceUnwrapped(value.expression)
                val get = value as? MethodCallExpr ?: return@mapNotNull null
                if (get.nameAsString != "get" || get.arguments.isNotEmpty()) return@mapNotNull null
                val field = when (val scope = get.scope.orElse(null)) {
                    is NameExpr -> scope.nameAsString
                    is FieldAccessExpr -> scope.nameAsString.takeIf { scope.scope is ThisExpr }
                    else -> null
                } ?: return@mapNotNull null
                if (field !in holderFields) return@mapNotNull null
                method.nameAsString to field
            }
            if (accessors.isEmpty()) continue
            for (constant in enumeration.entries) {
                if (constant.classBody.isNotEmpty()) continue
                val constructors = enumeration.constructors.filter { it.parameters.size == constant.arguments.size }
                val constructor = constructors.singleOrNull() ?: continue
                for ((index, argument) in constant.arguments.withIndex()) {
                    val factory = factoryOf(argument) ?: continue
                    val parameter = constructor.parameters[index].nameAsString
                    if (surfaceReassigned(constructor, parameter)) continue
                    val holders = constructor.body.statements.mapNotNull { statement ->
                        val assign = (statement as? ExpressionStmt)?.expression as? AssignExpr ?: return@mapNotNull null
                        if (assign.operator != AssignExpr.Operator.ASSIGN) return@mapNotNull null
                        val target = when (val target = assign.target) {
                            is NameExpr -> target.nameAsString
                            is FieldAccessExpr -> target.nameAsString.takeIf { target.scope is ThisExpr }
                            else -> null
                        } ?: return@mapNotNull null
                        val supplier = registeredSupplier(assign.value) as? NameExpr ?: return@mapNotNull null
                        target.takeIf { supplier.nameAsString == parameter && it in holderFields }
                    }
                    for ((method, field) in accessors) {
                        if (field in holders) {
                            result["$owner.${constant.nameAsString}::$method"] = factory
                        }
                    }
                }
            }
        }
    }
    return result
}
