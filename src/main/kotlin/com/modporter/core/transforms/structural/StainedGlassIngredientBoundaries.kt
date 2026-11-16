package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.TypeParameter
import com.modporter.core.transforms.structural.ProjectSourceDeclarations.TypeRef
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Preserves legacy Forge item tag sets that NeoForge 1.21.1 replaced by the members common to
 * several tags, where the source consumes the tag as an item ingredient.
 *
 * NeoForge's `TagConventionLogWarning` maps such a legacy path to more than one `c:` tag
 * (`forge:stained_glass` -> `c:glass_blocks` and `c:dyed`); `tag-field-renames.json` lists them
 * under `intersections` (scripts/forge_tag_renames.py). Either tag alone would admit other items
 * (clear glass), so the legacy `Tags.Items.<FIELD>`, which no longer exists in the 1.21.1 member
 * table, becomes `IntersectionIngredient.of(Ingredient.of(Tags.Items.A), Ingredient.of(Tags.Items.B))`
 * where an ingredient is what the tag fed:
 *
 * 1. the platform `Ingredient.of(TagKey)`, replaced as a whole; or
 * 2. an argument of a project method overload `m(TagKey<Item> tag)` whose whole body is
 *    `return m(Ingredient.of(tag));` and whose `m(Ingredient)` sibling the call can bind to: the
 *    argument becomes the ingredient the delegation would have built.
 *
 * Block tags and any other consumer have no ingredient form and are left for the compiler.
 */
internal fun StructuralRefactorPass.migrateStainedGlassIngredientBoundaries(
    source: String
): String {
    if (!source.contains(".Items.") || LEGACY_ITEM_INTERSECTIONS.isEmpty()) return source
    val masked = JavaSourceText.maskCommentsAndLiterals(source)
    if (LEGACY_ITEM_INTERSECTIONS.keys.none { masked.contains("Items.$it") }) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    if (parsed.problems.isNotEmpty()) return source

    val ingredient = intersectionTypeReference(unit, "Ingredient", INGREDIENT)
    val intersection = intersectionTypeReference(unit, "IntersectionIngredient", INTERSECTION_INGREDIENT)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun range(node: Node): IntRange? = node.range.map {
        (lineStarts[it.begin.line - 1] + it.begin.column - 1)..(lineStarts[it.end.line - 1] + it.end.column - 1)
    }.orElse(null)

    val edits = mutableListOf<Pair<IntRange, String>>()
    for (field in unit.findAll(FieldAccessExpr::class.java)) {
        val legacy = legacyIntersectionField(field, unit) ?: continue
        val replacement = "${intersection.text}.of(" + legacy.tags.joinToString(", ") {
            "${ingredient.text}.of(${legacy.tagsQualifier}.Items.$it)"
        } + ")"
        val target = intersectionConsumer(field, unit, this) ?: continue
        edits += (range(target) ?: continue) to replacement
    }
    if (edits.isEmpty()) return source

    var result = applyStringEdits(source, edits.distinctBy { it.first })
    if (ingredient.import) result = JavaSourceText.addImport(result, INGREDIENT)
    if (intersection.import) result = JavaSourceText.addImport(result, INTERSECTION_INGREDIENT)
    val reparsed = parser.parse(result)
    return if (reparsed.result.isPresent && reparsed.problems.isEmpty()) result else source
}

private class LegacyIntersection(val tagsQualifier: String, val tags: List<String>)

/** `Tags.Items.<FIELD>` of NeoForge's `Tags` whose legacy Forge field is an intersection entry. */
private fun legacyIntersectionField(field: FieldAccessExpr, unit: CompilationUnit): LegacyIntersection? {
    val tags = LEGACY_ITEM_INTERSECTIONS[field.nameAsString] ?: return null
    val items = field.scope as? FieldAccessExpr ?: return null
    if (items.nameAsString != "Items") return null
    val qualifier = items.scope
    val canonical = when (qualifier) {
        is NameExpr -> intersectionImportedName(qualifier.nameAsString, unit)
        is FieldAccessExpr -> qualifier.toString()
        else -> null
    }
    if (canonical != TAGS) return null
    // the legacy field is gone and every replacement tag exists in the 1.21.1 member table
    if (TargetMembers.hasField(TAGS_ITEMS, field.nameAsString)) return null
    if (tags.any { !TargetMembers.hasField(TAGS_ITEMS, it) }) return null
    return LegacyIntersection(qualifier.toString(), tags)
}

/** The expression the intersection ingredient replaces, or null when the tag is not an ingredient source. */
private fun intersectionConsumer(
    field: FieldAccessExpr,
    unit: CompilationUnit,
    pass: StructuralRefactorPass
): Expression? {
    val call = field.parentNode.orElse(null) as? MethodCallExpr ?: return null
    val index = call.arguments.indexOfFirst { it === field }
    if (index < 0) return null

    // 1. the platform Ingredient.of(TagKey)
    if (call.nameAsString == "of" && call.arguments.size == 1) {
        val scope = call.scope.orElse(null)
        val owner = when (scope) {
            is NameExpr -> intersectionImportedName(scope.nameAsString, unit)
            is FieldAccessExpr -> scope.toString()
            else -> null
        }
        return if (owner == INGREDIENT) call else null
    }

    // 2. a project overload that delegates the tag through Ingredient.of to its Ingredient sibling
    val declarations = pass.projectSourceDeclarations
    val receiver = runCatching { declarations.receiverType(call) }.getOrNull() ?: return null
    if (!declarations.declares(receiver)) return null
    val candidates = runCatching {
        declarations.methods(receiver, call.nameAsString, call.arguments.size)
    }.getOrNull() ?: return null
    val parameterTypes = candidates.map { method ->
        method.declaration.parameters.map { declarations.resolve(it.type, it) }
    }
    val tagOverloads = candidates.indices.filter { candidate ->
        (parameterTypes[candidate][index] as? TypeRef.Named)?.name == TAG_KEY
    }
    val tagOverload = tagOverloads.singleOrNull() ?: return null
    val ingredientOverloads = candidates.indices.filter { candidate ->
        (parameterTypes[candidate][index] as? TypeRef.Named)?.name == INGREDIENT &&
            parameterTypes[candidate].indices.all { position ->
                position == index || parameterTypes[candidate][position] == parameterTypes[tagOverload][position]
            }
    }
    if (ingredientOverloads.size != 1) return null
    if (!delegatesThroughIngredient(candidates[tagOverload].declaration, index, declarations)) return null
    return field
}

/** `m(..., TagKey<Item> tag, ...) { return m(..., Ingredient.of(tag), ...); }` with the other parameters forwarded. */
private fun delegatesThroughIngredient(
    method: MethodDeclaration,
    index: Int,
    declarations: ProjectSourceDeclarations
): Boolean {
    val statement = method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt ?: return false
    val delegate = statement.expression.orElse(null) as? MethodCallExpr ?: return false
    val scope = delegate.scope.orElse(null)
    if (scope != null && !(scope is ThisExpr && scope.typeName.isEmpty)) return false
    if (delegate.nameAsString != method.nameAsString ||
        delegate.arguments.size != method.parameters.size
    ) {
        return false
    }
    return delegate.arguments.withIndex().all { (position, argument) ->
        val parameter = method.parameters[position].nameAsString
        if (position != index) return@all (argument as? NameExpr)?.nameAsString == parameter
        val of = argument as? MethodCallExpr ?: return@all false
        val owner = of.scope.orElse(null) ?: return@all false
        of.nameAsString == "of" &&
            of.arguments.size == 1 &&
            (of.arguments.single() as? NameExpr)?.nameAsString == parameter &&
            (declarations.resolveName(owner.toString(), of) as? TypeRef.Named)?.name == INGREDIENT
    }
}

private class TypeReference(val text: String, val import: Boolean)

/** How [qualified] is written in [unit]: its imported simple name, a new import, or the qualified name. */
private fun intersectionTypeReference(unit: CompilationUnit, simple: String, qualified: String): TypeReference {
    val imports = unit.imports.filter { !it.isStatic && !it.isAsterisk && it.name.identifier == simple }
    val shadowed = unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple } ||
        unit.findAll(TypeParameter::class.java).any { it.nameAsString == simple } ||
        unit.findAll(Parameter::class.java).any { it.nameAsString == simple } ||
        unit.findAll(VariableDeclarator::class.java).any { it.nameAsString == simple }
    return when {
        shadowed -> TypeReference(qualified, import = false)
        imports.isEmpty() -> TypeReference(simple, import = true)
        imports.size == 1 && imports.single().nameAsString == qualified -> TypeReference(simple, import = false)
        else -> TypeReference(qualified, import = false)
    }
}

private fun intersectionImportedName(simple: String, unit: CompilationUnit): String? {
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return null
    if (unit.findAll(Parameter::class.java).any { it.nameAsString == simple }) return null
    if (unit.findAll(VariableDeclarator::class.java).any { it.nameAsString == simple }) return null
    return unit.imports
        .filter { !it.isStatic && !it.isAsterisk && it.name.identifier == simple }
        .singleOrNull()
        ?.nameAsString
}

@Serializable
private data class TagIntersection(
    val `class`: String,
    val forgeField: String,
    val neoforgeFields: List<String>,
    val forgePath: String = ""
)

@Serializable
private data class TagIntersectionFile(val intersections: List<TagIntersection> = emptyList())

/** Forge `Tags.Items` field -> the NeoForge `Tags.Items` fields whose common members replace it. */
private val LEGACY_ITEM_INTERSECTIONS: Map<String, List<String>> by lazy {
    val stream = StructuralRefactorPass::class.java.getResourceAsStream(TAG_FIELD_DATA)
        ?: return@lazy emptyMap()
    Json { ignoreUnknownKeys = true }
        .decodeFromString(TagIntersectionFile.serializer(), stream.bufferedReader().readText())
        .intersections
        .filter { it.`class` == "Items" && it.neoforgeFields.size > 1 }
        .associate { it.forgeField to it.neoforgeFields }
}

private const val TAG_FIELD_DATA = "/mappings/forge2neo/tag-field-renames.json"
private const val TAGS = "net.neoforged.neoforge.common.Tags"
private const val TAGS_ITEMS = "net.neoforged.neoforge.common.Tags\$Items"
private const val TAG_KEY = "net.minecraft.tags.TagKey"
private const val INGREDIENT = "net.minecraft.world.item.crafting.Ingredient"
private const val INTERSECTION_INGREDIENT =
    "net.neoforged.neoforge.common.crafting.IntersectionIngredient"
