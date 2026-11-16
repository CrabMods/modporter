package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.MappingDatabase
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 1.21 depluralised the data-pack folders (`recipes` -> `recipe`, `tags/items` -> `tags/item`, ...;
 * 1.21.1 reads `Registries.elementsDirPath`/`tagsDirPath`). Resource files move through the porter's
 * data-folder table (`resource-renames.json`); this rule applies the same table to a data entry a
 * project builds in memory for its own pack. A `withPrefix("<legacy folder>/")` literal is rewritten
 * only when both facts hold:
 * - the location is the key of an entry put into a project `PackResources` implementation (directly,
 *   or into a map the source forwards entry-by-entry to that pack's `put`) whose resources are keyed
 *   by `PackType.getDirectory()/namespace/path`;
 * - the entry's value is that folder's content: a recipe's JSON (`FinishedRecipe.serializeRecipe()`
 *   or the recipe codec) for the recipe folder, an encoded `TagFile` for a tag folder.
 */
internal object LegacyRuntimePackPaths {
    const val RULE_ID = "struct-legacy-runtime-pack-data-folders"
    private const val PACK_RESOURCES = "net.minecraft.server.packs.PackResources"
    private const val FINISHED_RECIPE = "net.minecraft.data.recipes.FinishedRecipe"
    private const val RECIPE = "net.minecraft.world.item.crafting.Recipe"
    private const val TAG_FILE = "net.minecraft.tags.TagFile"

    private val dataFolderRenames: Map<String, String> by lazy {
        MappingDatabase.loadDefault().getAllResourceRenames().filterKeys { !it.startsWith("META-INF") }
    }

    fun migrate(pass: StructuralRefactorPass, projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        if (sources.values.none { it.contains("withPrefix") }) return emptyList()
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val units = sources.filterValues { it.contains("PackResources") || it.contains("FinishedRecipe") || it.contains("serializeRecipe") }
            .mapNotNull { (file, source) -> parser.parse(source).result.orElse(null)?.let { file to it } }
            .toMap()
        val packTypes = keyedPackResources(units.values)
        if (packTypes.isEmpty()) return emptyList()
        val recipeJsonTypes = recipeJsonTypes(units.values)
        // top-level type -> file, for member types a subclass inherits
        val topLevelFiles = sources.mapNotNull { (file, source) ->
            val pkg = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(source)?.groupValues?.get(1)
            val name = file.fileName.toString().removeSuffix(".java")
            (if (pkg.isNullOrEmpty()) name else "$pkg.$name") to file
        }.toMap()
        val parsedTops = HashMap<Path, CompilationUnit?>()
        val memberType = { owner: String, simple: String ->
            var top = owner
            var file = topLevelFiles[top]
            while (file == null && '.' in top) {
                top = top.substringBeforeLast('.')
                file = topLevelFiles[top]
            }
            file?.let { path -> parsedTops.getOrPut(path) { parser.parse(sources.getValue(path)).result.orElse(null) } }
                ?.findAll(TypeDeclaration::class.java)
                ?.firstOrNull { qualifiedName(it.findCompilationUnit().get(), it) == owner }
                ?.members?.filterIsInstance<TypeDeclaration<*>>()
                ?.firstOrNull { it.nameAsString == simple }
                ?.let { "$owner.$simple" }
        }

        val changes = mutableListOf<Change>()
        for ((file, source) in sources) {
            if (!source.contains("withPrefix")) continue
            val migrated = migrateSource(pass, source, packTypes, recipeJsonTypes, memberType) ?: continue
            if (!dryRun) file.writeText(migrated)
            changes += Change(
                file, 1, "In-memory pack entries use the 1.21 data folder names",
                "withPrefix(\"<legacy folder>/\")", "withPrefix(\"<1.21 folder>/\")", Confidence.HIGH, RULE_ID
            )
        }
        return changes
    }

    internal fun migrateSource(
        pass: StructuralRefactorPass,
        source: String,
        packTypes: Set<String>,
        recipeJsonTypes: Set<String>,
        memberType: (String, String) -> String? = { _, _ -> null }
    ): String? {
        val surface = RecipeSurfaceSource.parse(pass, source) ?: return null
        val typing = surface.typing
        fun typeOf(expression: Expression): String? = typing.typeOf(expression.toString(), surface.start(expression))

        // maps of this source whose entries are handed one by one to a pack: map.forEach(pack::put)
        val forwardedMaps = surface.unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
            if (call.nameAsString != "forEach" || call.arguments.size != 1) return@mapNotNull null
            val reference = call.arguments.single() as? MethodReferenceExpr ?: return@mapNotNull null
            if (reference.identifier != "put") return@mapNotNull null
            // `pack::put` parses its receiver as a name or as an (ambiguous) type expression
            val pack = when (val scope = reference.scope) {
                is NameExpr -> scope.nameAsString
                is com.github.javaparser.ast.expr.TypeExpr -> (scope.type as? com.github.javaparser.ast.type.ClassOrInterfaceType)
                    ?.takeIf { it.scope.isEmpty && it.typeArguments.isEmpty }?.nameAsString
                else -> null
            } ?: return@mapNotNull null
            if (typing.typeOf(pack, surface.start(reference)) !in packTypes) return@mapNotNull null
            val map = call.scope.orElse(null) as? NameExpr ?: return@mapNotNull null
            map.nameAsString.takeIf { name ->
                surface.unit.findAll(FieldDeclaration::class.java).any { field ->
                    field.variables.any { it.nameAsString == name }
                }
            }
        }.toSet()

        for (put in surface.unit.findAll(MethodCallExpr::class.java)) {
            if (put.nameAsString != "put" || put.arguments.size != 2) continue
            val receiver = put.scope.orElse(null) ?: continue
            val intoPack = typeOf(receiver) in packTypes ||
                (receiver is NameExpr && receiver.nameAsString in forwardedMaps)
            if (!intoPack) continue
            val key = put.arguments[0] as? MethodCallExpr ?: continue
            if (key.nameAsString != "withPrefix" || key.arguments.size != 1) continue
            val literal = key.arguments.single() as? StringLiteralExpr ?: continue
            val folder = literal.value.removeSuffix("/").takeIf { literal.value.endsWith("/") } ?: continue
            val renamed = dataFolderRenames[folder] ?: continue
            val content = put.arguments[1]
            val proven = when {
                folder == "recipes" -> isRecipeJson(content, typing, surface, recipeJsonTypes, memberType)
                folder.startsWith("tags/") -> encodes(content, typing, TAG_FILE)
                else -> false
            }
            if (!proven) continue
            surface.replace(literal, "\"$renamed/\"")
        }
        if (!surface.changed) return null
        return surface.apply()
    }

    /** The root of `Owner.CODEC.encodeStart(...)...` chains is that owner's codec. */
    private fun encodes(value: Expression, typing: SourceValueTypes, owner: String, fields: Set<String> = setOf("CODEC")): Boolean {
        var current: Expression = value
        while (true) {
            val call = current as? MethodCallExpr ?: return false
            val scope = call.scope.orElse(null) ?: return false
            if (call.nameAsString == "encodeStart" && scope is FieldAccessExpr) {
                return scope.nameAsString in fields && typing.isTypeName(scope.scope.toString(), owner)
            }
            current = scope
        }
    }

    private fun isRecipeJson(
        value: Expression,
        typing: SourceValueTypes,
        surface: RecipeSurfaceSource,
        recipeJsonTypes: Set<String>,
        memberType: (String, String) -> String?
    ): Boolean {
        if (encodes(value, typing, RECIPE, setOf("CODEC", "CONDITIONAL_CODEC"))) return true
        val call = value as? MethodCallExpr ?: return false
        if (call.nameAsString != "serializeRecipe" || call.arguments.isNotEmpty()) return false
        val receiver = call.scope.orElse(null) ?: return false
        return receiverType(receiver, typing, surface, memberType) in recipeJsonTypes
    }

    /**
     * The declared type of a receiver, resolved as Java does: a member type the enclosing classes
     * inherit from their (project) superclasses before same-package and imported types.
     */
    private fun receiverType(
        receiver: Expression,
        typing: SourceValueTypes,
        surface: RecipeSurfaceSource,
        memberType: (String, String) -> String?
    ): String? {
        val name = receiver as? NameExpr ?: return typing.typeOf(receiver.toString(), surface.start(receiver))
        var scope: com.github.javaparser.ast.Node? = name.parentNode.orElse(null)
        var declared: com.github.javaparser.ast.type.Type? = null
        while (scope != null && declared == null) {
            declared = when (scope) {
                is com.github.javaparser.ast.body.CallableDeclaration<*> ->
                    scope.parameters.firstOrNull { it.nameAsString == name.nameAsString }?.type
                is com.github.javaparser.ast.stmt.BlockStmt -> scope.statements
                    .flatMap { it.findAll(com.github.javaparser.ast.body.VariableDeclarator::class.java) }
                    .firstOrNull { it.nameAsString == name.nameAsString }?.type
                is TypeDeclaration<*> -> scope.fields.flatMap { it.variables }
                    .firstOrNull { it.nameAsString == name.nameAsString }?.type
                else -> null
            }
            if (declared == null) scope = scope.parentNode.orElse(null)
        }
        val type = declared as? com.github.javaparser.ast.type.ClassOrInterfaceType
            ?: return typing.typeOf(name.nameAsString, surface.start(name))
        if (type.scope.isEmpty && surface.unit.findAll(TypeDeclaration::class.java).none { it.nameAsString == type.nameAsString }) {
            var owner: com.github.javaparser.ast.Node? = name.parentNode.orElse(null)
            while (owner != null) {
                if (owner is ClassOrInterfaceDeclaration) {
                    for (parent in owner.extendedTypes) {
                        val parentType = typing.typeOf("(${parent.nameWithScope}) null", 0) ?: continue
                        memberType(parentType, type.nameAsString)?.let { return it }
                    }
                }
                owner = owner.parentNode.orElse(null)
            }
        }
        return typing.typeOf(name.nameAsString, surface.start(name))
    }

    private fun qualifiedName(unit: CompilationUnit, declaration: TypeDeclaration<*>): String {
        val names = generateSequence<com.github.javaparser.ast.Node>(declaration) { it.parentNode.orElse(null) }
            .filterIsInstance<TypeDeclaration<*>>().map { it.nameAsString }.toList().asReversed()
        val pkg = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        return (listOf(pkg) + names).filter { it.isNotBlank() }.joinToString(".")
    }

    private fun resolves(unit: CompilationUnit, typeName: String, canonical: String): Boolean {
        if (typeName == canonical) return true
        val simple = canonical.substringAfterLast('.')
        if (typeName != simple) return false
        return unit.imports.any { !it.isStatic && !it.isAsterisk && it.nameAsString == canonical } ||
            unit.imports.any { !it.isStatic && it.isAsterisk && it.nameAsString == canonical.substringBeforeLast('.') }
    }

    /**
     * Project `PackResources` implementations whose resources are addressed as
     * `<PackType directory>/<namespace>/<path>` of the location they are given.
     */
    private fun keyedPackResources(units: Collection<CompilationUnit>): Set<String> {
        val result = linkedSetOf<String>()
        for (unit in units) {
            for (type in unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
                val implementsPack = type.implementedTypes.any { resolves(unit, it.nameWithScope, PACK_RESOURCES) } ||
                    type.extendedTypes.any { parent ->
                        unit.imports.firstOrNull { !it.isStatic && it.name.identifier == parent.nameAsString }
                            ?.nameAsString?.let { TargetMembers.inheritsFrom(it, PACK_RESOURCES) } == true
                    }
                if (!implementsPack) continue
                val keyed = type.methods.any { method ->
                    val returned = (method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt)
                        ?.expression?.orElse(null)?.toString()?.replace(Regex("""\s+"""), "") ?: return@any false
                    Regex("""^$ID\.getDirectory\(\)\+"/"\+$ID\.getNamespace\(\)\+"/"\+$ID\.getPath\(\)$""").matches(returned)
                }
                val servesLocations = type.methods.any { it.nameAsString == "getResource" && it.parameters.size == 2 }
                if (keyed && servesLocations) result += qualifiedName(unit, type)
            }
        }
        return result
    }

    /** Project types whose `serializeRecipe()` is a recipe's JSON: 1.20.1 FinishedRecipe results or their retained codec form. */
    private fun recipeJsonTypes(units: Collection<CompilationUnit>): Set<String> {
        val result = linkedSetOf<String>()
        for (unit in units) {
            for (type in unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
                val finished = type.implementedTypes.any { resolves(unit, it.nameWithScope, FINISHED_RECIPE) }
                val retained = type.methods.any { method: MethodDeclaration ->
                    method.nameAsString == "serializeRecipe" && method.parameters.isEmpty() &&
                        (method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt)
                            ?.expression?.orElse(null)?.toString()?.replace(Regex("""\s+"""), "")
                            ?.startsWith("${RECIPE}.CONDITIONAL_CODEC.encodeStart(") == true
                }
                if (finished || retained) result += qualifiedName(unit, type)
            }
        }
        return result
    }

    private const val ID = """[A-Za-z_$][\w$]*"""
}
