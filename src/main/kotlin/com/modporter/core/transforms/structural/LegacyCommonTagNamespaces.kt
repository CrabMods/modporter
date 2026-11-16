package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import com.modporter.resources.commonTagPathPrefixIsStable
import com.modporter.resources.normalizeCommonTagPath
import com.modporter.resources.normalizeLegacyTagReference
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Java code that names tags in the legacy `forge`/`neoforge` namespace moves to the namespace and
 * path the data migration gives the same tag (`normalizeLegacyTagReference`/`normalizeCommonTagPath`),
 * so code and resources keep naming one tag. Only a namespace value that provably names tags moves:
 *
 * - a namespace literal of `ResourceLocation.fromNamespaceAndPath`/`tryBuild`/`new ResourceLocation`
 *   whose location is a tag id (`TagKey.create`, `ItemTags`/`BlockTags`/`FluidTags.create`,
 *   `TagEmptyCondition(ResourceLocation)`, or a project parameter used only that way), or of NeoForge's
 *   `TagEmptyCondition(String namespace, String path)`;
 * - the string of an enum constant whose enum only stores it and only turns it into tag-id namespaces,
 *   when every use of the enum's values in the project ends in a tag id.
 *
 * Every path that can reach a moved namespace is evaluated (literals, literal prefixes, constant
 * names through `Enum#name()`, `toLowerCase(Locale.ROOT)` and catnip `Lang.asId`, javap-verified as
 * `toLowerCase(Locale.ROOT)` in Ponder 1.0.91 and 1.0.87). A path the table would rename keeps the site
 * unchanged unless that path literal is part of the same location expression.
 */
internal object LegacyCommonTagNamespaces {
    const val RULE_ID = "struct-legacy-common-tag-namespaces"
    private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
    private const val TAG_KEY = "net.minecraft.tags.TagKey"
    private const val TAG_EMPTY_CONDITION = "net.neoforged.neoforge.common.conditions.TagEmptyCondition"
    private const val CATNIP_LANG = "net.createmod.catnip.lang.Lang"
    private const val MAX_DEPTH = 8
    private val legacyNamespaces = setOf("forge", "neoforge")
    private val locationFactories = setOf("fromNamespaceAndPath", "tryBuild")

    /** Platform classes with `public static TagKey create(ResourceLocation)` in the 1.21.1 table. */
    private val tagFactoryOwners: Set<String> by lazy {
        TargetMembers.methodsNamed("create")
            .filter { (_, descriptor) -> descriptor == "(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/tags/TagKey;" }
            .map { it.first.replace('$', '.') }
            .toSet()
    }

    private sealed interface Str
    private data class Lit(val value: String) : Str
    private data class Pre(val prefix: String) : Str
    private object NullStr : Str

    private sealed interface Value
    private data class StrValue(val value: Str) : Value
    private data class EnumValue(val enumType: String, val constant: String) : Value

    /** Parameter bindings of one evaluation context; names in [unknown] have no proven value. */
    private class Frame(
        val file: Path,
        val bindings: Map<String, List<Value>> = emptyMap(),
        val self: EnumValue? = null,
        val unknown: Set<String> = emptySet()
    )

    private class Project(files: List<Path>) {
        val sources: Map<Path, String> = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        private val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        private val units = HashMap<Path, CompilationUnit?>()
        private val normalizedFiles = files.map { it.toAbsolutePath().normalize() }.toSet()

        fun unit(file: Path): CompilationUnit? = units.getOrPut(file) {
            val parsed = parser.parse(sources[file] ?: return@getOrPut null)
            parsed.result.orElse(null)?.takeIf { parsed.isSuccessful }
        }

        fun filesMentioning(token: String): List<Path> =
            sources.filterValues { Regex("""(?<![\w$])${Regex.escape(token)}(?![\w$])""").containsMatchIn(it) }.keys.toList()

        fun fileOf(node: Node): Path? {
            val unit = node.findCompilationUnit().orElse(null) ?: return null
            return units.entries.firstOrNull { it.value === unit }?.key
        }

        fun qualifiedName(declaration: TypeDeclaration<*>): String {
            val unit = declaration.findCompilationUnit().orElse(null)
            val names = generateSequence<Node>(declaration) { it.parentNode.orElse(null) }
                .filterIsInstance<TypeDeclaration<*>>().map { it.nameAsString }.toList().asReversed()
            val pkg = unit?.packageDeclaration?.map { it.nameAsString }?.orElse("") ?: ""
            return (listOf(pkg) + names).filter { it.isNotBlank() }.joinToString(".")
        }

        /** The canonical name [typeName] denotes in [file], or null when the source does not say. */
        fun resolve(file: Path, typeName: String): String? {
            val unit = unit(file) ?: return null
            val name = typeName.substringBefore('<').trim()
            val first = name.substringBefore('.')
            if ('.' in name && first.first().isLowerCase()) return name
            unit.imports.firstOrNull { !it.isStatic && !it.isAsterisk && it.name.identifier == first }
                ?.let { return it.nameAsString + name.removePrefix(first) }
            unit.findAll(TypeDeclaration::class.java).firstOrNull { it.nameAsString == first }
                ?.let { return qualifiedName(it) + name.removePrefix(first) }
            val pkg = unit.packageDeclaration.map { it.nameAsString }.orElse("")
            if (file.toAbsolutePath().normalize().resolveSibling("$first.java") in normalizedFiles) {
                return if (pkg.isEmpty()) name else "$pkg.$name"
            }
            if (name == "String") return "java.lang.String"
            return null
        }

        fun enclosingType(node: Node): TypeDeclaration<*>? = node.findAncestor(TypeDeclaration::class.java).orElse(null)
    }

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val project = Project(files)
        val candidates = project.sources.filterValues { source -> legacyNamespaces.any { source.contains("\"$it\"") } }.keys
        if (candidates.isEmpty()) return emptyList()
        val edits = linkedMapOf<Path, MutableList<Pair<StringLiteralExpr, String>>>()
        val analysis = Analysis(project)
        for (file in candidates) {
            val unit = project.unit(file) ?: continue
            for (literal in unit.findAll(StringLiteralExpr::class.java)) {
                if (literal.value !in legacyNamespaces) continue
                val planned = analysis.directSite(file, literal) ?: analysis.carrierConstant(file, literal) ?: continue
                planned.forEach { (target, replacement) -> edits.getOrPut(project.fileOf(target) ?: file) { mutableListOf() } += target to replacement }
            }
        }
        val changes = mutableListOf<Change>()
        for ((file, planned) in edits) {
            val source = project.sources.getValue(file)
            val lineStarts = buildList { add(0); source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            var updated = source
            for ((literal, replacement) in planned.distinctBy { it.first.range.orElse(null) }
                .sortedByDescending { it.first.range.map { r -> lineStarts[r.begin.line - 1] + r.begin.column }.orElse(-1) }
            ) {
                val range = literal.range.orElse(null) ?: continue
                val from = lineStarts[range.begin.line - 1] + range.begin.column - 1
                val to = lineStarts[range.end.line - 1] + range.end.column
                updated = updated.substring(0, from) + "\"$replacement\"" + updated.substring(to)
            }
            if (updated == source) continue
            if (!dryRun) file.writeText(updated)
            changes += Change(
                file, 1, "Tag ids in the legacy Forge namespace use the namespace and path their migrated data uses",
                "\"forge\" tag namespace", "\"c\" tag namespace", Confidence.HIGH, RULE_ID
            )
        }
        return changes
    }

    private fun targetNamespace(namespace: String): String =
        normalizeLegacyTagReference(null, "#$namespace:x").removePrefix("#").substringBefore(':')

    private class Analysis(val project: Project) {
        /** A namespace literal inside a tag-id location expression or TagEmptyCondition(namespace, path). */
        fun directSite(file: Path, literal: StringLiteralExpr): List<Pair<StringLiteralExpr, String>>? {
            val parent = literal.parentNode.orElse(null) ?: return null
            val path: Expression = when {
                parent is MethodCallExpr && parent.nameAsString in locationFactories && parent.arguments.size == 2 &&
                    parent.arguments[0] === literal &&
                    parent.scope.map { project.resolve(file, it.toString()) == RESOURCE_LOCATION }.orElse(false) &&
                    isTagIdSink(file, parent, 0) -> parent.arguments[1]
                parent is ObjectCreationExpr && parent.arguments.size == 2 && parent.arguments[0] === literal &&
                    parent.anonymousClassBody.isEmpty &&
                    project.resolve(file, parent.type.nameWithScope) == RESOURCE_LOCATION &&
                    isTagIdSink(file, parent, 0) -> parent.arguments[1]
                parent is ObjectCreationExpr && parent.arguments.size == 2 && parent.arguments[0] === literal &&
                    parent.anonymousClassBody.isEmpty &&
                    project.resolve(file, parent.type.nameWithScope) == TAG_EMPTY_CONDITION -> parent.arguments[1]
                else -> return null
            }
            val values = strValues(path, Frame(file), 0) ?: return null
            val edits = mutableListOf(literal to targetNamespace(literal.value))
            for (value in values) {
                when (value) {
                    is Lit -> {
                        val normalized = normalizeCommonTagPath(value.value)
                        if (normalized != value.value) {
                            val pathLiteral = path as? StringLiteralExpr ?: return null
                            edits += pathLiteral to normalized
                        }
                    }
                    is Pre -> if (!commonTagPathPrefixIsStable(value.prefix)) return null
                    NullStr -> return null
                }
            }
            return edits
        }

        /**
         * `NAMESPACE("forge")` of an enum that only stores the string and only uses it as the
         * namespace of tag ids, when every use of that enum's values ends in a tag id whose path
         * the data migration keeps.
         */
        fun carrierConstant(file: Path, literal: StringLiteralExpr): List<Pair<StringLiteralExpr, String>>? {
            val constant = literal.parentNode.orElse(null) as? EnumConstantDeclaration ?: return null
            if (constant.arguments.singleOrNull() !== literal || constant.classBody.isNotEmpty()) return null
            val carrier = constant.parentNode.orElse(null) as? EnumDeclaration ?: return null
            val shape = carrierShape(file, carrier) ?: return null
            val carrierType = project.qualifiedName(carrier)
            val paths = mutableListOf<Str>()
            if (!usesEndInTagIds(carrier, carrierType, shape, constant.nameAsString, paths)) return null
            for (value in paths) {
                when (value) {
                    is Lit -> if (normalizeCommonTagPath(value.value) != value.value) return null
                    is Pre -> if (!commonTagPathPrefixIsStable(value.prefix)) return null
                    NullStr -> return null
                }
            }
            return listOf(literal to targetNamespace(literal.value))
        }

        private class CarrierShape(
            val field: String,
            /** id method name/arity -> (parameter names, path expression, delegate) */
            val idMethods: Map<Pair<String, Int>, MethodDeclaration>
        )

        private fun carrierShape(file: Path, carrier: EnumDeclaration): CarrierShape? {
            val constructor = carrier.constructors.singleOrNull() ?: return null
            val parameter = constructor.parameters.singleOrNull() ?: return null
            if (project.resolve(file, parameter.typeAsString) != "java.lang.String") return null
            val statements = constructor.body.statements
            val assigned = statements.map { statement ->
                val assign = (statement as? ExpressionStmt)?.expression as? AssignExpr ?: return null
                if (assign.operator != AssignExpr.Operator.ASSIGN ||
                    (assign.value as? NameExpr)?.nameAsString != parameter.nameAsString
                ) {
                    return null
                }
                fieldName(assign.target) ?: return null
            }
            val field = assigned.singleOrNull() ?: return null
            val declaration = carrier.fields.singleOrNull { declaration -> declaration.variables.any { it.nameAsString == field } }
                ?: return null
            if (!declaration.isFinal || declaration.variables.size != 1 ||
                project.resolve(file, declaration.variables.single().typeAsString) != "java.lang.String"
            ) {
                return null
            }
            // every read of the field is the namespace of a location an id method returns
            val idMethods = linkedMapOf<Pair<String, Int>, MethodDeclaration>()
            val reads = carrier.findAll(NameExpr::class.java).filter { it.nameAsString == field } +
                carrier.findAll(FieldAccessExpr::class.java).filter { it.nameAsString == field && it.scope is ThisExpr }
            for (read in reads) {
                if (read.findAncestor(ConstructorDeclaration::class.java).orElse(null) === constructor) continue
                val location = read.parentNode.orElse(null)
                val namespaceOf = when {
                    location is MethodCallExpr && location.nameAsString in locationFactories &&
                        location.arguments.size == 2 && location.arguments[0] === read &&
                        location.scope.map { project.resolve(file, it.toString()) == RESOURCE_LOCATION }.orElse(false) -> location
                    location is ObjectCreationExpr && location.arguments.size == 2 && location.arguments[0] === read &&
                        project.resolve(file, location.type.nameWithScope) == RESOURCE_LOCATION -> location
                    else -> return null
                }
                val returned = namespaceOf.parentNode.orElse(null) as? ReturnStmt ?: return null
                val method = returned.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
                if (method.isStatic || method.body.orElse(null)?.statements?.singleOrNull() !== returned) return null
                idMethods[method.nameAsString to method.parameters.size] = method
            }
            if (idMethods.isEmpty()) return null
            // delegating id methods: return this.idMethod(...)
            for (method in carrier.methods) {
                if (method.isStatic || (method.nameAsString to method.parameters.size) in idMethods) continue
                val returned = (method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt)
                    ?.expression?.orElse(null) as? MethodCallExpr ?: continue
                if (returned.scope.map { it is ThisExpr }.orElse(true) &&
                    (returned.nameAsString to returned.arguments.size) in idMethods
                ) {
                    idMethods[method.nameAsString to method.parameters.size] = method
                }
            }
            // no method other than the id methods reads the stored string
            if (carrier.methods.any { method ->
                    (method.nameAsString to method.parameters.size) !in idMethods &&
                        (method.findAll(NameExpr::class.java).any { it.nameAsString == field } ||
                            method.findAll(FieldAccessExpr::class.java).any { it.nameAsString == field })
                }
            ) {
                return null
            }
            return CarrierShape(field, idMethods)
        }

        /**
         * Every reference to the carrier enum's values in the project is the receiver of an id
         * method whose location is a tag id, or reaches one through enum-constant or `this(...)`
         * constructor arguments. Paths produced for [constantName] are added to [paths].
         */
        private fun usesEndInTagIds(
            carrier: EnumDeclaration,
            carrierType: String,
            shape: CarrierShape,
            constantName: String,
            paths: MutableList<Str>
        ): Boolean {
            val simple = carrier.nameAsString
            val constants = carrier.entries.map { it.nameAsString }.toSet()
            // only a file naming the enum type or statically importing from it can reach its values
            val files = project.sources.filter { (_, text) ->
                Regex("""(?<![\w$])${Regex.escape(simple)}(?![\w$])""").containsMatchIn(text) ||
                    text.contains("import static $carrierType.")
            }.keys
            for (file in files) {
                val unit = project.unit(file) ?: return false
                // values reached any other way (values(), valueOf, method references, class literals) are not followed
                val outside = { node: Node -> node.findAncestor(EnumDeclaration::class.java).orElse(null) !== carrier }
                if (unit.findAll(MethodCallExpr::class.java).any { call ->
                        outside(call) && call.scope.map { project.resolve(file, it.toString()) == carrierType }.orElse(false)
                    } ||
                    unit.findAll(MethodReferenceExpr::class.java).any { reference ->
                        outside(reference) && project.resolve(file, reference.scope.toString()) == carrierType
                    } ||
                    unit.findAll(com.github.javaparser.ast.expr.ClassExpr::class.java).any { literal ->
                        project.resolve(file, literal.type.asString()) == carrierType
                    } ||
                    unit.findAll(MethodDeclaration::class.java).any { method ->
                        project.resolve(file, method.typeAsString) == carrierType
                    }
                ) {
                    return false
                }
                // the stored string is never read from outside its enum
                if (unit.findAll(FieldAccessExpr::class.java).any { access ->
                        access.nameAsString == shape.field && access.findAncestor(EnumDeclaration::class.java).orElse(null) !== carrier &&
                            refersToCarrier(file, access.scope, carrierType, constants)
                    }
                ) {
                    return false
                }
                val staticConstants = unit.imports.filter { it.isStatic && !it.isAsterisk && it.nameAsString.substringBeforeLast('.') == carrierType }
                    .map { it.name.identifier }.toSet()
                if (unit.imports.any { it.isStatic && it.isAsterisk && it.nameAsString == carrierType }) return false
                val uses = unit.findAll(Expression::class.java).filter { expression ->
                    if (expression.findAncestor(EnumDeclaration::class.java).orElse(null) === carrier) return@filter false
                    when (expression) {
                        is NameExpr -> expression.nameAsString in staticConstants && !isShadowed(expression)
                        is FieldAccessExpr -> expression.nameAsString in constants &&
                            project.resolve(file, expression.scope.toString()) == carrierType
                        else -> false
                    }
                }
                for (use in uses) {
                    val constant = (use as? NameExpr)?.nameAsString ?: (use as FieldAccessExpr).nameAsString
                    val value = EnumValue(carrierType, constant)
                    if (!valueUseEndsInTagId(file, use, value, shape, carrierType, constantName, paths, Frame(file), 0)) return false
                }
                // carrier-typed variables outside constructor chains are not followed
                val typedOutside = (unit.findAll(Parameter::class.java).map { it.typeAsString to it } +
                    unit.findAll(VariableDeclarator::class.java).map { it.typeAsString to it })
                    .filter { (type, node) ->
                        project.resolve(file, type) == carrierType &&
                            node.findAncestor(EnumDeclaration::class.java).orElse(null) !== carrier &&
                            !(node is Parameter && node.parentNode.orElse(null) is ConstructorDeclaration &&
                                (node.parentNode.get() as ConstructorDeclaration).parentNode.orElse(null) is EnumDeclaration)
                    }
                if (typedOutside.isNotEmpty()) return false
            }
            return true
        }

        private fun isShadowed(name: NameExpr): Boolean {
            var scope: Node? = name.parentNode.orElse(null)
            while (scope != null) {
                if (scope is CallableDeclaration<*> && scope.parameters.any { it.nameAsString == name.nameAsString }) return true
                scope = scope.parentNode.orElse(null)
            }
            return false
        }

        private fun refersToCarrier(file: Path, scope: Expression, carrierType: String, constants: Set<String>): Boolean = when (scope) {
            is NameExpr -> scope.nameAsString in constants || project.resolve(file, scope.nameAsString) == carrierType
            is FieldAccessExpr -> scope.nameAsString in constants
            else -> true
        }

        /** A carrier value at [use] (in [frame]) only reaches tag ids; paths for [constantName] are collected. */
        private fun valueUseEndsInTagId(
            file: Path,
            use: Expression,
            value: EnumValue,
            shape: CarrierShape,
            carrierType: String,
            constantName: String,
            paths: MutableList<Str>,
            frame: Frame,
            depth: Int
        ): Boolean {
            if (depth > MAX_DEPTH) return false
            val parent = use.parentNode.orElse(null) ?: return false
            // receiver of an id method
            if (parent is MethodCallExpr && parent.scope.orElse(null) === use) {
                val method = shape.idMethods[parent.nameAsString to parent.arguments.size] ?: return false
                if (!isTagIdSink(file, parent, depth)) return false
                if (value.constant == constantName) {
                    val produced = idMethodPaths(method, shape, parent.arguments, frame, depth) ?: return false
                    paths += produced
                }
                return true
            }
            // an enum-constant argument of a consumer enum, or a this(...) argument
            val (arguments, constructor, nextFrame) = when {
                parent is EnumConstantDeclaration -> {
                    val consumer = parent.parentNode.orElse(null) as? EnumDeclaration ?: return false
                    if (parent.classBody.isNotEmpty()) return false
                    val target = consumer.constructors.filter { it.parameters.size == parent.arguments.size }.singleOrNull() ?: return false
                    val self = EnumValue(project.qualifiedName(consumer), parent.nameAsString)
                    Triple(parent.arguments, target, bind(file, target, parent.arguments, Frame(file, self = self), self))
                }
                parent is ExplicitConstructorInvocationStmt && parent.isThis -> {
                    val owner = parent.findAncestor(EnumDeclaration::class.java).orElse(null) ?: return false
                    val target = owner.constructors.filter { it.parameters.size == parent.arguments.size }.singleOrNull() ?: return false
                    Triple(parent.arguments, target, bind(file, target, parent.arguments, frame, frame.self))
                }
                else -> return false
            }
            val index = arguments.indexOfFirst { it === use }
            if (index < 0 || nextFrame == null) return false
            val parameter = constructor.parameters[index]
            if (project.resolve(file, parameter.typeAsString) != carrierType) return false
            if (surfaceReassigned(constructor, parameter.nameAsString)) return false
            val reads = constructor.body.findAll(NameExpr::class.java).filter { it.nameAsString == parameter.nameAsString }
            return reads.all { read ->
                valueUseEndsInTagId(file, read, value, shape, carrierType, constantName, paths, nextFrame, depth + 1)
            }
        }

        /** Binds constructor parameters to the values of the arguments a call site passes. */
        private fun bind(
            file: Path,
            constructor: ConstructorDeclaration,
            arguments: List<Expression>,
            caller: Frame,
            self: EnumValue?
        ): Frame? {
            val bindings = linkedMapOf<String, List<Value>>()
            val unknown = linkedSetOf<String>()
            constructor.parameters.forEachIndexed { index, parameter ->
                val argument = arguments[index]
                val values: List<Value>? = when {
                    argument is NameExpr && argument.nameAsString in caller.unknown -> null
                    argument is NameExpr && argument.nameAsString in caller.bindings -> caller.bindings.getValue(argument.nameAsString)
                    argument is ThisExpr && self != null -> listOf(self)
                    else -> enumConstantValue(file, argument)?.let { listOf(it) }
                        ?: strValues(argument, caller, 0)?.map { StrValue(it) }
                }
                if (values == null) unknown += parameter.nameAsString else bindings[parameter.nameAsString] = values
            }
            return Frame(file, bindings, self, unknown)
        }

        private fun enumConstantValue(file: Path, argument: Expression): EnumValue? {
            val unit = project.unit(file) ?: return null
            return when (argument) {
                is NameExpr -> unit.imports.firstOrNull { it.isStatic && !it.isAsterisk && it.name.identifier == argument.nameAsString }
                    ?.let { EnumValue(it.nameAsString.substringBeforeLast('.'), argument.nameAsString) }
                is FieldAccessExpr -> project.resolve(file, argument.scope.toString())?.let { EnumValue(it, argument.nameAsString) }
                else -> null
            }
        }

        /** Paths the id method [method] produces for a call with [arguments] evaluated in [caller]. */
        private fun idMethodPaths(
            method: MethodDeclaration,
            shape: CarrierShape,
            arguments: List<Expression>,
            caller: Frame,
            depth: Int
        ): List<Str>? {
            if (depth > MAX_DEPTH) return null
            val file = project.fileOf(method) ?: return null
            val bindings = linkedMapOf<String, List<Value>>()
            method.parameters.forEachIndexed { index, parameter ->
                val argument = arguments[index]
                bindings[parameter.nameAsString] = when {
                    argument is ThisExpr && caller.self != null -> listOf(caller.self)
                    argument is NameExpr && argument.nameAsString in caller.unknown -> return null
                    argument is NameExpr && argument.nameAsString in caller.bindings -> caller.bindings.getValue(argument.nameAsString)
                    else -> strValues(argument, caller, depth + 1)?.map { StrValue(it) } ?: return null
                }
            }
            val frame = Frame(file, bindings, caller.self)
            val returned = (method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt)?.expression?.orElse(null)
                ?: return null
            if (returned is MethodCallExpr && returned.scope.map { it is ThisExpr }.orElse(true) &&
                returned.nameAsString !in locationFactories
            ) {
                val delegate = shape.idMethods[returned.nameAsString to returned.arguments.size] ?: return null
                return idMethodPaths(delegate, shape, returned.arguments, frame, depth + 1)
            }
            val path = when (returned) {
                is MethodCallExpr -> returned.arguments.getOrNull(1)
                is ObjectCreationExpr -> returned.arguments.getOrNull(1)
                else -> null
            } ?: return null
            return strValues(path, frame, depth + 1)
        }

        /** Whether the location built by [location] is only ever used as a tag id. */
        fun isTagIdSink(file: Path, location: Expression, depth: Int): Boolean {
            if (depth > MAX_DEPTH) return false
            var node: Expression = location
            while (node.parentNode.orElse(null) is EnclosedExpr) node = node.parentNode.get() as Expression
            val parent = node.parentNode.orElse(null) ?: return false
            if (parent is MethodCallExpr && parent.scope.orElse(null) !== node) {
                val index = parent.arguments.indexOfFirst { it === node }
                if (index < 0) return false
                val owner = parent.scope.orElse(null)?.let { project.resolve(file, it.toString()) }
                if (parent.nameAsString == "create" && owner == TAG_KEY && parent.arguments.size == 2 && index == 1) return true
                if (parent.nameAsString == "create" && owner in tagFactoryOwners && parent.arguments.size == 1) return true
                // a project helper whose parameter is only ever a tag id
                val helper = projectMethod(file, parent) ?: return false
                val parameter = helper.parameters.getOrNull(index) ?: return false
                if (project.resolve(project.fileOf(helper) ?: return false, parameter.typeAsString) != RESOURCE_LOCATION) return false
                if (surfaceReassigned(helper, parameter.nameAsString)) return false
                val reads = helper.findAll(NameExpr::class.java).filter { it.nameAsString == parameter.nameAsString }
                return reads.isNotEmpty() && reads.all { isTagIdSink(project.fileOf(helper)!!, it, depth + 1) }
            }
            if (parent is ObjectCreationExpr && parent.arguments.size == 1 && parent.arguments[0] === node &&
                project.resolve(file, parent.type.nameWithScope) == TAG_EMPTY_CONDITION
            ) {
                return true
            }
            return false
        }

        /** The project method an unqualified or type-qualified call names, when it is unambiguous. */
        private fun projectMethod(file: Path, call: MethodCallExpr): MethodDeclaration? {
            val scope = call.scope.orElse(null)
            val owner: TypeDeclaration<*> = when {
                scope == null || scope is ThisExpr -> project.enclosingType(call) ?: return null
                else -> {
                    val name = project.resolve(file, scope.toString()) ?: return null
                    project.sources.keys.asSequence().mapNotNull { project.unit(it) }
                        .flatMap { it.findAll(TypeDeclaration::class.java).asSequence() }
                        .firstOrNull { project.qualifiedName(it) == name } ?: return null
                }
            }
            return owner.methods.filter { it.nameAsString == call.nameAsString && it.parameters.size == call.arguments.size }
                .singleOrNull()
        }

        /** Possible string values of [expression]; null when any is unknown. */
        fun strValues(expression: Expression, frame: Frame, depth: Int): List<Str>? {
            if (depth > MAX_DEPTH) return null
            return when (expression) {
                is StringLiteralExpr -> listOf(Lit(expression.value))
                is NullLiteralExpr -> listOf(NullStr)
                is EnclosedExpr -> strValues(expression.inner, frame, depth)
                is CastExpr -> strValues(expression.expression, frame, depth)
                is BinaryExpr -> {
                    if (expression.operator != BinaryExpr.Operator.PLUS) return null
                    val left = strValues(expression.left, frame, depth + 1) ?: return null
                    val right = strValues(expression.right, frame, depth + 1)
                    left.flatMap { head ->
                        when (head) {
                            is Lit -> right?.map { tail ->
                                when (tail) {
                                    is Lit -> Lit(head.value + tail.value)
                                    is Pre -> Pre(head.value + tail.prefix)
                                    NullStr -> Lit(head.value + "null")
                                }
                            } ?: listOf(Pre(head.value))
                            is Pre -> listOf(head)
                            NullStr -> return null
                        }
                    }
                }
                is NameExpr -> {
                    if (expression.nameAsString in frame.unknown) return null
                    frame.bindings[expression.nameAsString]?.let { values ->
                        return values.map { (it as? StrValue)?.value ?: return null }
                    }
                    parameterValues(expression, frame, depth)
                }
                is ConditionalExpr -> {
                    val test = surfaceUnwrapped(expression.condition) as? BinaryExpr ?: return null
                    val checked = when {
                        test.right is NullLiteralExpr -> test.left
                        test.left is NullLiteralExpr -> test.right
                        else -> return null
                    } as? NameExpr ?: return null
                    val nullWhenTrue = when (test.operator) {
                        BinaryExpr.Operator.EQUALS -> true
                        BinaryExpr.Operator.NOT_EQUALS -> false
                        else -> return null
                    }
                    val values = strValues(checked, frame, depth + 1) ?: return null
                    val nullBranch = if (nullWhenTrue) expression.thenExpr else expression.elseExpr
                    val valueBranch = if (nullWhenTrue) expression.elseExpr else expression.thenExpr
                    val result = mutableListOf<Str>()
                    if (values.any { it == NullStr }) result += strValues(nullBranch, frame, depth + 1) ?: return null
                    val present = values.filter { it != NullStr }
                    if (present.isNotEmpty()) {
                        if ((valueBranch as? NameExpr)?.nameAsString == checked.nameAsString) {
                            result += present
                        } else {
                            result += strValues(valueBranch, frame, depth + 1) ?: return null
                        }
                    }
                    result
                }
                is MethodCallExpr -> {
                    val scope = expression.scope.orElse(null)
                    when {
                        expression.nameAsString == "asId" && expression.arguments.size == 1 && scope != null &&
                            project.resolve(frame.file, scope.toString()) == CATNIP_LANG ->
                            strValues(expression.arguments.single(), frame, depth + 1)?.map(::lowerCase)
                        expression.nameAsString == "toLowerCase" && expression.arguments.size == 1 && scope != null &&
                            expression.arguments.single().toString().replace(" ", "") in setOf("Locale.ROOT", "java.util.Locale.ROOT") ->
                            strValues(scope, frame, depth + 1)?.map(::lowerCase)
                        expression.nameAsString == "name" && expression.arguments.isEmpty() -> {
                            val enumValues: List<EnumValue> = when (scope) {
                                null, is ThisExpr -> listOfNotNull(frame.self)
                                is NameExpr -> frame.bindings[scope.nameAsString]?.map { it as? EnumValue ?: return null } ?: return null
                                else -> return null
                            }
                            enumValues.map { Lit(it.constant) }.ifEmpty { null }
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }

        private fun lowerCase(value: Str): Str = when (value) {
            is Lit -> Lit(value.value.lowercase(Locale.ROOT))
            is Pre -> Pre(value.prefix.lowercase(Locale.ROOT))
            NullStr -> NullStr
        }

        /** Values of a method or constructor parameter over all of its call sites in the project. */
        private fun parameterValues(name: NameExpr, frame: Frame, depth: Int): List<Str>? {
            val callable = generateSequence<Node>(name) { it.parentNode.orElse(null) }
                .filterIsInstance<CallableDeclaration<*>>().firstOrNull() ?: return null
            val index = callable.parameters.indexOfFirst { it.nameAsString == name.nameAsString }
            if (index < 0 || surfaceReassigned(callable, name.nameAsString)) return null
            // closed world: a project callable nobody in the project calls contributes no value
            val sites = callSites(callable) ?: return null
            return sites.flatMap { (arguments, site) -> strValues(arguments[index], site, depth + 1) ?: return null }
        }

        /** Every call of [callable] in the project with its argument list and frame; null when not closed. */
        private fun callSites(callable: CallableDeclaration<*>): List<Pair<List<Expression>, Frame>>? {
            val owner = project.enclosingType(callable) ?: return null
            val ownerName = project.qualifiedName(owner)
            val arity = callable.parameters.size
            if (callable.parameters.any { it.isVarArgs }) return null
            val sites = mutableListOf<Pair<List<Expression>, Frame>>()
            if (callable is ConstructorDeclaration) {
                if (owner is EnumDeclaration) {
                    owner.entries.filter { it.arguments.size == arity }.forEach { constant ->
                        val file = project.fileOf(constant) ?: return null
                        sites += constant.arguments to Frame(file, self = EnumValue(ownerName, constant.nameAsString))
                    }
                }
                val candidates = if (callable.isPrivate || owner is EnumDeclaration) {
                    listOfNotNull(project.fileOf(callable))
                } else {
                    project.filesMentioning(owner.nameAsString)
                }
                for (file in candidates) {
                    val unit = project.unit(file) ?: return null
                    unit.findAll(ObjectCreationExpr::class.java)
                        .filter { it.arguments.size == arity && project.resolve(file, it.type.nameWithScope) == ownerName }
                        .forEach { sites += it.arguments to Frame(file) }
                    unit.findAll(ExplicitConstructorInvocationStmt::class.java)
                        .filter { it.isThis && it.arguments.size == arity && project.enclosingType(it) === owner }
                        .forEach { if (it.findAncestor(ConstructorDeclaration::class.java).orElse(null) === callable) return null; sites += it.arguments to Frame(file) }
                }
                return sites
            }
            val method = callable as? MethodDeclaration ?: return null
            val candidates = if (method.isPrivate) listOfNotNull(project.fileOf(method)) else project.filesMentioning(method.nameAsString)
            for (file in candidates) {
                val unit = project.unit(file) ?: return null
                if (unit.findAll(MethodReferenceExpr::class.java).any { it.identifier == method.nameAsString }) return null
                val staticImport = unit.imports.any { it.isStatic && it.nameAsString == "$ownerName.${method.nameAsString}" }
                for (call in unit.findAll(MethodCallExpr::class.java)) {
                    if (call.nameAsString != method.nameAsString || call.arguments.size != arity) continue
                    val scope = call.scope.orElse(null)
                    val matches = when {
                        scope == null || scope is ThisExpr -> staticImport || project.enclosingType(call)?.let { enclosing ->
                            generateSequence<Node>(enclosing) { it.parentNode.orElse(null) }
                                .filterIsInstance<TypeDeclaration<*>>().any { it === owner }
                        } == true
                        else -> project.resolve(file, scope.toString()) == ownerName
                    }
                    if (!matches) {
                        if (scope == null || !method.isStatic) return null
                        continue
                    }
                    sites += call.arguments to Frame(file)
                }
            }
            return sites
        }

        private fun fieldName(expression: Expression): String? = when (expression) {
            is NameExpr -> expression.nameAsString
            is FieldAccessExpr -> expression.nameAsString.takeIf { expression.scope is ThisExpr }
            else -> null
        }
    }
}
