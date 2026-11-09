package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Source-bound migration of the 1.20.1 advancement value/data-provider contract:
 *
 *  * Advancement.Builder#save now returns AdvancementHolder.
 *  * AdvancementHolder is a record (`id()`, `value()`).
 *  * Advancement.Builder#addCriterion takes Criterion<?>; a project CriterionTrigger<T>
 *    wraps its proven T factory with CriterionTrigger#createCriterion.
 *  * Advancement JSON is encoded with Advancement.CODEC and the GatherDataEvent registry future.
 *
 * The provider migration is atomic. The provider, every constructor call, the exact old
 * deconstruct().serializeToJson() call, and a unique lexically visible registry future whose
 * last reaching write comes from DatapackBuiltinEntriesProvider#getRegistryProvider must all
 * be proven before any provider edit is made.
 */
internal object AdvancementValues {
    const val RULE_ID = "struct-advancement-values"

    private const val ADVANCEMENT = "net.minecraft.advancements.Advancement"
    private const val ADVANCEMENT_BUILDER = "net.minecraft.advancements.Advancement.Builder"
    private const val ADVANCEMENT_HOLDER = "net.minecraft.advancements.AdvancementHolder"
    private const val CRITERION_TRIGGER = "net.minecraft.advancements.CriterionTrigger"
    private const val CRITERION_INSTANCE = "net.minecraft.advancements.CriterionTriggerInstance"
    private const val SIMPLE_INSTANCE = "net.minecraft.advancements.critereon.SimpleCriterionTrigger.SimpleInstance"
    private const val DATA_PROVIDER = "net.minecraft.data.DataProvider"
    private const val CACHED_OUTPUT = "net.minecraft.data.CachedOutput"
    private const val PACK_OUTPUT = "net.minecraft.data.PackOutput"
    private const val HOLDER_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
    private const val GATHER_DATA_EVENT = "net.neoforged.neoforge.data.event.GatherDataEvent"
    private const val DATAPACK_BUILTIN_PROVIDER =
        "net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider"
    private const val COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture"
    private const val CONSUMER = "java.util.function.Consumer"
    private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val PROVEN_CONTRACT_TYPES = setOf(
        ADVANCEMENT,
        ADVANCEMENT_BUILDER,
        ADVANCEMENT_HOLDER,
        CRITERION_TRIGGER,
        CRITERION_INSTANCE,
        SIMPLE_INSTANCE,
        DATA_PROVIDER,
        CACHED_OUTPUT,
        PACK_OUTPUT,
        HOLDER_PROVIDER,
        GATHER_DATA_EVENT,
        DATAPACK_BUILTIN_PROVIDER,
        COMPLETABLE_FUTURE,
        CONSUMER,
        RESOURCE_LOCATION,
        "java.lang.String"
    )

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    private data class JType(val raw: String, val arguments: List<JType> = emptyList()) {
        fun substitute(bindings: Map<String, JType>): JType =
            bindings[raw] ?: copy(arguments = arguments.map { it.substitute(bindings) })
    }

    private data class Decl(
        val name: String,
        val typeParameters: List<String>,
        val parents: List<JType>,
        val fields: Map<String, JType>,
        val methods: Map<Pair<String, Int>, List<JType>>
    )

    private data class Edit(val start: Int, val endExclusive: Int, val replacement: String)

    private data class SourceUnit(
        val file: Path,
        val source: String,
        val unit: CompilationUnit,
        val packageName: String,
        val imports: Map<String, String>,
        val wildcardImports: Set<String>,
        val lineStarts: List<Int>
    ) {
        fun start(node: Node): Int = node.range
            .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
            .orElse(-1)

        fun endExclusive(node: Node): Int = node.range
            .map { lineStarts[it.end.line - 1] + it.end.column }
            .orElse(-1)

        fun text(node: Node): String {
            val start = start(node)
            val end = endExclusive(node)
            return if (start >= 0 && end >= start) source.substring(start, end) else node.toString()
        }

        fun owner(node: Node): String? =
            node.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .flatMap { it.fullyQualifiedName }
                .orElse(null)
    }

    private class Index(val declarations: Map<String, Decl>) {
        fun lexical(name: String, packageName: String, owner: String?): String? {
            if (owner == null) return null
            val first = name.substringBefore('.')
            val suffix = name.removePrefix(first)
            var current: String = owner
            while (current.isNotBlank() && current != packageName) {
                if (current.substringAfterLast('.') == first) {
                    val self = current + suffix
                    if (self in declarations) return self
                }
                val nested = "$current.$name"
                if (nested in declarations) return nested
                current = current.substringBeforeLast('.', "")
            }
            return null
        }

        fun samePackage(name: String, packageName: String): String? {
            val candidate = if (packageName.isBlank()) name else "$packageName.$name"
            return candidate.takeIf { it in declarations }
        }

        fun known(type: String): Boolean =
            type in declarations || type in PROVEN_CONTRACT_TYPES

        fun inherits(type: String, target: String): Boolean =
            inherits(JType(type), target, emptyMap(), mutableSetOf())

        private fun inherits(
            type: JType,
            target: String,
            inheritedBindings: Map<String, JType>,
            seen: MutableSet<String>
        ): Boolean {
            val substituted = type.substitute(inheritedBindings)
            if (substituted.raw == target) return true
            if (target == CRITERION_INSTANCE && substituted.raw == SIMPLE_INSTANCE) return true
            val decl = declarations[substituted.raw] ?: return false
            val key = substituted.raw + substituted.arguments.joinToString(prefix = "<", postfix = ">") { it.raw }
            if (!seen.add(key)) return false
            val bindings = decl.typeParameters.zip(substituted.arguments).toMap()
            return decl.parents.any { inherits(it, target, bindings, seen) }
        }

        fun criterionInstance(triggerType: String): JType? =
            criterionInstance(JType(triggerType), emptyMap(), mutableSetOf())

        private fun criterionInstance(
            type: JType,
            inheritedBindings: Map<String, JType>,
            seen: MutableSet<String>
        ): JType? {
            val substituted = type.substitute(inheritedBindings)
            val decl = declarations[substituted.raw] ?: return null
            val key = substituted.raw + substituted.arguments.joinToString(prefix = "<", postfix = ">") { it.raw }
            if (!seen.add(key)) return null
            val bindings = decl.typeParameters.zip(substituted.arguments).toMap()
            for (parent in decl.parents) {
                val resolved = parent.substitute(bindings)
                if (resolved.raw == CRITERION_TRIGGER && resolved.arguments.size == 1) {
                    return resolved.arguments.single()
                }
                criterionInstance(resolved, emptyMap(), seen)?.let { return it }
            }
            return null
        }

        fun field(owner: String, name: String): JType? =
            declarations[owner]?.fields?.get(name)

        fun method(owner: String, name: String, arity: Int): JType? =
            declarations[owner]?.methods?.get(name to arity)?.distinct()?.singleOrNull()

        fun declaresMethodBefore(type: String, target: String, name: String, arity: Int): Boolean =
            declaresMethodBefore(type, target, name, arity, mutableSetOf())

        private fun declaresMethodBefore(
            type: String,
            target: String,
            name: String,
            arity: Int,
            seen: MutableSet<String>
        ): Boolean {
            if (type == target || !seen.add(type)) return false
            val declaration = declarations[type] ?: return false
            if ((name to arity) in declaration.methods) return true
            return declaration.parents.any {
                declaresMethodBefore(it.raw, target, name, arity, seen)
            }
        }
    }

    private class ValueTypes(
        private val parsed: SourceUnit,
        private val index: Index
    ) {
        fun typeOf(expression: Expression): JType? = when (expression) {
            is NameExpr -> binding(expression.nameAsString, expression)
            is ThisExpr -> parsed.owner(expression)?.let(::JType)
            is FieldAccessExpr -> {
                if (expression.scope is ThisExpr) {
                    parsed.owner(expression)?.let { index.field(it, expression.nameAsString) }
                } else {
                    typeOf(expression.scope)?.let { index.field(it.raw, expression.nameAsString) }
                }
            }
            is MethodCallExpr -> {
                val receiver = expression.scope.orElse(null)?.let(::typeOf) ?: parsed.owner(expression)?.let(::JType)
                receiver?.let { index.method(it.raw, expression.nameAsString, expression.arguments.size) }
            }
            is ObjectCreationExpr -> parsed.resolve(expression.type, parsed.owner(expression), index)
            else -> null
        }

        private fun binding(name: String, use: Node): JType? {
            val lambda = use.findAncestor(LambdaExpr::class.java).orElse(null)
            if (lambda != null) {
                val parameterIndex = lambda.parameters.indexOfFirst { it.nameAsString == name }
                if (parameterIndex >= 0) {
                    lambdaParameterType(lambda, parameterIndex)?.let { return it }
                }
            }

            use.findAncestor(MethodDeclaration::class.java).orElse(null)?.let { method ->
                method.parameters.firstOrNull { it.nameAsString == name }?.let {
                    return parsed.resolve(it.type, parsed.owner(it), index)
                }
                local(name, use, method.body.orElse(null))?.let { return it }
            }
            use.findAncestor(ConstructorDeclaration::class.java).orElse(null)?.let { constructor ->
                constructor.parameters.firstOrNull { it.nameAsString == name }?.let {
                    return parsed.resolve(it.type, parsed.owner(it), index)
                }
                local(name, use, constructor.body)?.let { return it }
            }
            val owner = parsed.owner(use) ?: return null
            return index.field(owner, name)
        }

        private fun local(name: String, use: Node, body: BlockStmt?): JType? {
            if (body == null) return null
            val useOffset = parsed.start(use)
            return body.findAll(VariableDeclarator::class.java)
                .filter { it.nameAsString == name && parsed.start(it) in 0 until useOffset }
                .filter { variable ->
                    val variableBlock = variable.findAncestor(BlockStmt::class.java).orElse(null)
                    variableBlock == null ||
                        parsed.start(use) in parsed.start(variableBlock)..parsed.endExclusive(variableBlock)
                }
                .maxByOrNull { parsed.start(it) }
                ?.let { parsed.resolve(it.type, parsed.owner(it), index) }
        }

        private fun lambdaParameterType(lambda: LambdaExpr, parameterIndex: Int): JType? {
            val parent = lambda.parentNode.orElse(null)
            if (parent is VariableDeclarator && parent.initializer.orElse(null) === lambda) {
                val functional = parsed.resolve(parent.type, parsed.owner(parent), index) ?: return null
                return when (functional.raw) {
                    CONSUMER, "java.util.function.Predicate", "java.util.function.Function",
                    "java.util.function.UnaryOperator" ->
                        functional.arguments.firstOrNull().takeIf { parameterIndex == 0 }
                    "java.util.function.BiConsumer", "java.util.function.BiFunction" ->
                        functional.arguments.getOrNull(parameterIndex)
                    else -> null
                }
            }
            return null
        }
    }

    private data class ProviderShape(
        val file: Path,
        val typeName: String,
        val simpleName: String,
        val constructorArity: Int,
        val constructor: ConstructorDeclaration,
        val outputField: FieldDeclaration,
        val outputFieldName: String,
        val outputParameterName: String,
        val outputAssignment: ExpressionStmt,
        val runMethod: MethodDeclaration,
        val holderName: String,
        val saveCalls: List<IntRange>,
        val pathCall: IntRange,
        val registryFieldName: String,
        val registryParameterName: String,
        val registryValueName: String
    )

    private data class ConstructorCall(
        val file: Path,
        val closeParen: Int,
        val hasArguments: Boolean,
        val registryFuture: String
    )

    private data class ParseSnapshot(
        val parsed: Map<Path, SourceUnit>,
        val unparsed: Map<Path, String>
    )

    private data class FileMigration(
        val edits: MutableList<Edit> = mutableListOf(),
        val imports: MutableSet<String> = linkedSetOf()
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths ->
            paths.filter { it.extension == "java" }.toList()
        }
        val original = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        var snapshot = parseAll(original)
        val index = buildIndex(snapshot.parsed.values)
        val migrated = original.toMutableMap()

        for ((file, unit) in snapshot.parsed) {
            val rewritten = migrateValueSites(unit, index)
            if (rewritten != unit.source) migrated[file] = rewritten
        }

        snapshot = parseAll(migrated)
        val refreshedIndex = buildIndex(snapshot.parsed.values)
        val acceptedProviders = snapshot.parsed.values.mapNotNull { provider ->
            val shape = providerShape(provider, refreshedIndex) ?: return@mapNotNull null
            val calls = constructorCalls(shape, snapshot, refreshedIndex) ?: return@mapNotNull null
            shape to calls
        }

        val staged = linkedMapOf<Path, FileMigration>()
        for ((provider, calls) in acceptedProviders) {
            val providerUnit = snapshot.parsed.getValue(provider.file)
            val providerMigration = providerMigration(providerUnit, provider) ?: continue
            staged.getOrPut(provider.file, ::FileMigration).also {
                it.edits += providerMigration.edits
                it.imports += providerMigration.imports
            }
            for (call in calls) {
                staged.getOrPut(call.file, ::FileMigration).edits += Edit(
                    call.closeParen,
                    call.closeParen,
                    (if (call.hasArguments) ", " else "") + call.registryFuture
                )
            }
        }

        // All offsets above come from this one refreshed snapshot. Compose each file once.
        // Any overlap drops the whole provider/caller batch rather than applying stale offsets.
        if (staged.values.all { nonOverlapping(it.edits) }) {
            for ((file, migration) in staged) {
                var result = applyEdits(snapshot.parsed.getValue(file).source, migration.edits)
                migration.imports.forEach { result = JavaSourceText.addImport(result, it) }
                migrated[file] = result
            }
        }

        val changes = mutableListOf<Change>()
        for ((file, before) in original) {
            val after = migrated.getValue(file)
            if (after == before) continue
            if (!dryRun) file.writeText(after)
            changes += Change(
                file = file,
                line = 1,
                description = "Migrate advancement values and provider serialization to AdvancementHolder and Advancement.CODEC",
                before = "legacy Advancement save/deconstruct value flow",
                after = "AdvancementHolder id/value flow with GatherDataEvent registry-aware codec output",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
        return changes
    }

    private fun parseAll(sources: Map<Path, String>): ParseSnapshot {
        val parsed = linkedMapOf<Path, SourceUnit>()
        val unparsed = linkedMapOf<Path, String>()
        for ((file, source) in sources) {
            val unit = parse(file, source)
            if (unit == null) unparsed[file] = source else parsed[file] = unit
        }
        return ParseSnapshot(parsed, unparsed)
    }

    private fun parse(file: Path, source: String): SourceUnit? {
        val result = parser.parse(source)
        if (!result.isSuccessful) return null
        val unit = result.result.orElse(null) ?: return null
        val imports = unit.imports
            .filter { !it.isStatic && !it.isAsterisk }
            .associate { it.name.identifier to it.nameAsString }
        val wildcards = unit.imports
            .filter { !it.isStatic && it.isAsterisk }
            .map { it.nameAsString }
            .toSet()
        val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }
        return SourceUnit(
            file,
            source,
            unit,
            unit.packageDeclaration.map { it.nameAsString }.orElse(""),
            imports,
            wildcards,
            lineStarts
        )
    }

    private fun buildIndex(units: Collection<SourceUnit>): Index {
        val names = units.flatMap { parsed ->
            parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)
                .mapNotNull { it.fullyQualifiedName.orElse(null) }
        }.toSet()
        val provisional = Index(
            names.associateWith { Decl(it, emptyList(), emptyList(), emptyMap(), emptyMap()) }
        )
        val declarations = linkedMapOf<String, Decl>()
        for (parsed in units) {
            for (type in parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
                val name = type.fullyQualifiedName.orElse(null) ?: continue
                val owner = name.substringBeforeLast('.', "")
                val parameters = type.typeParameters.map { it.nameAsString }
                val parents = (type.extendedTypes + type.implementedTypes)
                    .mapNotNull {
                        parsed.resolve(
                            it,
                            owner,
                            provisional,
                            parameters.toSet()
                        )
                    }
                val fields = type.fields
                    .flatMap { field ->
                        field.variables.map {
                            it.nameAsString to parsed.resolve(
                                it.type,
                                name,
                                provisional,
                                parameters.toSet()
                            )
                        }
                    }
                    .mapNotNull { (field, value) -> value?.let { field to it } }
                    .toMap()
                val methods = type.methods.groupBy { it.nameAsString to it.parameters.size }
                    .mapValues { (_, values) ->
                        values.mapNotNull {
                            parsed.resolve(
                                it.type,
                                name,
                                provisional,
                                parameters.toSet() + it.typeParameters.map { parameter -> parameter.nameAsString }
                            )
                        }
                    }
                declarations[name] = Decl(name, parameters, parents, fields, methods)
            }
        }
        return Index(declarations)
    }

    private fun SourceUnit.resolve(
        type: Type,
        owner: String?,
        index: Index,
        typeVariables: Set<String> = emptySet()
    ): JType? {
        val classType = type as? ClassOrInterfaceType ?: return null
        return resolve(classType, owner, index, typeVariables)
    }

    private fun SourceUnit.resolve(
        type: ClassOrInterfaceType,
        owner: String?,
        index: Index,
        typeVariables: Set<String> = emptySet()
    ): JType? {
        val name = type.nameWithScope
        val first = name.substringBefore('.')
        val lexical = index.lexical(name, packageName, owner)
        val samePackage = index.samePackage(name, packageName)
        val raw = when {
            name in typeVariables -> name
            lexical != null -> lexical
            first in imports -> imports.getValue(first) + name.removePrefix(first)
            samePackage != null -> samePackage
            name.firstOrNull()?.isLowerCase() == true -> name
            else -> {
                val candidates = buildList {
                    wildcardImports
                        .map { "$it.$name" }
                        .filter(index::known)
                        .forEach { add(it) }
                    if (name == "String") add("java.lang.String")
                }.distinct()
                candidates.singleOrNull() ?: return null
            }
        }
        val arguments = type.typeArguments.map { it.toList() }.orElse(emptyList())
        return JType(raw, arguments.mapNotNull { resolve(it, owner, index, typeVariables) })
    }

    private fun migrateValueSites(parsed: SourceUnit, index: Index): String {
        val types = ValueTypes(parsed, index)
        val edits = mutableListOf<Edit>()

        for (call in parsed.unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString == "addCriterion" && call.arguments.size == 2) {
                val builder = call.scope.orElse(null)?.let(types::typeOf)
                val factory = call.arguments[1] as? MethodCallExpr
                val receiver = factory?.scope?.orElse(null)
                if (builder?.raw == ADVANCEMENT_BUILDER &&
                    factory != null &&
                    receiver != null &&
                    factory.arguments.isEmpty() &&
                    stable(receiver)
                ) {
                    val trigger = types.typeOf(receiver)
                    val triggerInstance = trigger?.let { index.criterionInstance(it.raw) }
                    val factoryResult = trigger?.let { index.method(it.raw, factory.nameAsString, 0) }
                    if (triggerInstance != null &&
                        factoryResult == triggerInstance &&
                        index.inherits(factoryResult.raw, CRITERION_INSTANCE) &&
                        index.inherits(trigger.raw, CRITERION_TRIGGER)
                    ) {
                        val argument = parsed.text(factory)
                        val triggerText = parsed.text(receiver)
                        edits += Edit(
                            parsed.start(factory),
                            parsed.endExclusive(factory),
                            "$triggerText.createCriterion($argument)"
                        )
                    }
                }
            }
            if (call.nameAsString == "getId" && call.arguments.isEmpty()) {
                val receiver = call.scope.orElse(null)?.let(types::typeOf)
                if (receiver?.raw == ADVANCEMENT_HOLDER) {
                    edits += Edit(parsed.start(call.name), parsed.endExclusive(call.name), "id")
                }
            }
        }

        for (field in parsed.unit.findAll(FieldDeclaration::class.java)) {
            if (field.variables.size != 1) continue
            val variable = field.variables.single()
            if (parsed.resolve(variable.type, parsed.owner(variable), index)?.raw != ADVANCEMENT) continue
            if (variable.initializer.isPresent) continue
            val assignments = parsed.unit.findAll(AssignExpr::class.java)
                .filter { refersToField(it.target, variable.nameAsString, it, parsed) }
            if (assignments.size != 1) continue
            val value = assignments.single().value as? MethodCallExpr ?: continue
            if (value.nameAsString != "save" || value.arguments.size != 2) continue
            val receiver = value.scope.orElse(null)?.let(types::typeOf) ?: continue
            if (receiver.raw != ADVANCEMENT_BUILDER) continue
            edits += Edit(
                parsed.start(field.elementType),
                parsed.endExclusive(field.elementType),
                "AdvancementHolder"
            )
        }

        if (edits.isEmpty()) return parsed.source
        var result = applyEdits(parsed.source, edits)
        if ("AdvancementHolder" in edits.joinToString { it.replacement }) {
            result = JavaSourceText.addImport(result, ADVANCEMENT_HOLDER)
        }
        return result
    }

    private fun stable(expression: Expression): Boolean = when (expression) {
        is NameExpr, is ThisExpr -> true
        is FieldAccessExpr -> stable(expression.scope)
        else -> false
    }

    private fun refersToField(target: Expression, name: String, use: Node, parsed: SourceUnit): Boolean = when (target) {
        is FieldAccessExpr -> target.nameAsString == name && target.scope is ThisExpr
        is NameExpr -> {
            if (target.nameAsString != name) false
            else {
                val callable = use.findAncestor(MethodDeclaration::class.java).orElse(null)
                val constructor = use.findAncestor(ConstructorDeclaration::class.java).orElse(null)
                val parameters = callable?.parameters.orEmpty() + constructor?.parameters.orEmpty()
                val body = callable?.body?.orElse(null) ?: constructor?.body
                parameters.none { it.nameAsString == name } &&
                    body?.findAll(VariableDeclarator::class.java)
                        ?.none { it.nameAsString == name && parsed.start(it) < parsed.start(use) } != false
            }
        }
        else -> false
    }

    private fun providerShape(parsed: SourceUnit, index: Index): ProviderShape? {
        val owner = parsed.unit.types.singleOrNull() as? ClassOrInterfaceDeclaration ?: return null
        val typeName = owner.fullyQualifiedName.orElse(null) ?: return null
        if (!index.inherits(typeName, DATA_PROVIDER)) return null
        if (owner.constructors.size != 1) return null
        val constructor = owner.constructors.single()
        val outputFields = owner.fields.filter { field ->
            field.variables.size == 1 &&
                parsed.resolve(field.elementType, typeName, index)?.raw == PACK_OUTPUT
        }
        if (outputFields.size != 1) return null
        val outputField = outputFields.single()
        val outputFieldName = outputField.variables.single().nameAsString
        val outputAssignments = constructor.body.findAll(AssignExpr::class.java).mapNotNull { assignment ->
            val target = assignment.target as? FieldAccessExpr ?: return@mapNotNull null
            val value = assignment.value as? NameExpr ?: return@mapNotNull null
            if (target.scope is ThisExpr && target.nameAsString == outputFieldName) {
                assignment.findAncestor(ExpressionStmt::class.java).orElse(null)?.let { it to value.nameAsString }
            } else {
                null
            }
        }
        if (outputAssignments.size != 1) return null
        val (outputAssignment, outputParameterName) = outputAssignments.single()
        val outputParameter = constructor.parameters.singleOrNull { it.nameAsString == outputParameterName } ?: return null
        if (parsed.resolve(outputParameter.type, typeName, index)?.raw != PACK_OUTPUT) return null
        if (owner.fields.any { field ->
                field.variables.any {
                    val type = parsed.resolve(it.type, typeName, index)
                    type?.raw == COMPLETABLE_FUTURE && type.arguments.singleOrNull()?.raw == HOLDER_PROVIDER
                }
            }) return null

        val run = owner.methods.singleOrNull { method ->
            method.nameAsString == "run" &&
                method.parameters.size == 1 &&
                method.body.isPresent &&
                parsed.resolve(method.type, typeName, index)?.raw == COMPLETABLE_FUTURE &&
                parsed.resolve(method.parameters.single().type, typeName, index)?.raw == CACHED_OUTPUT
        } ?: return null
        val cacheName = run.parameters.single().nameAsString
        val body = run.body.get()
        val returns = body.findAll(ReturnStmt::class.java)
        if (returns.size != 1 || body.statements.lastOrNull() !== returns.single()) return null
        val finalCall = returns.single().expression.orElse(null) as? MethodCallExpr ?: return null
        if (finalCall.nameAsString != "allOf" || finalCall.scope.orElse(null)?.toString()?.substringAfterLast('.') != "CompletableFuture") {
            return null
        }

        val consumer = body.findAll(VariableDeclarator::class.java).mapNotNull { variable ->
            val type = parsed.resolve(variable.type, typeName, index) ?: return@mapNotNull null
            if (type.raw != CONSUMER || type.arguments.singleOrNull()?.raw != ADVANCEMENT_HOLDER) return@mapNotNull null
            val lambda = variable.initializer.orElse(null) as? LambdaExpr ?: return@mapNotNull null
            if (lambda.parameters.size != 1) return@mapNotNull null
            lambda.parameters.single().nameAsString
        }.distinct()
        if (consumer.size != 1) return null
        val holderName = consumer.single()

        val runStart = parsed.start(body)
        val runEnd = parsed.endExclusive(body)
        val saveCalls = findCalls(parsed.source, "DataProvider.saveStable", runStart, runEnd)
        if (saveCalls.isEmpty()) return null
        for (range in saveCalls) {
            val call = parsed.source.substring(range)
            val open = call.indexOf('(')
            val args = JavaSourceText.splitTopLevelArgs(call.substring(open + 1, call.length - 1))
            if (args.size != 3 ||
                args[0].trim() != cacheName ||
                !Regex("""^\s*${Regex.escape(holderName)}\s*\.\s*deconstruct\s*\(\s*\)\s*\.\s*serializeToJson\s*\(\s*\)\s*$""")
                    .matches(args[1])
            ) return null
        }

        val pathCalls = (
            findCalls(parsed.source, "$outputFieldName.createPathProvider", runStart, runEnd) +
                findCalls(parsed.source, "this.$outputFieldName.createPathProvider", runStart, runEnd)
            ).distinct()
            .filter { range ->
                val text = parsed.source.substring(range)
                val args = JavaSourceText.splitTopLevelArgs(text.substring(text.indexOf('(') + 1, text.length - 1))
                args.size == 2 &&
                    args[0].replace(Regex("""\s+"""), "") == "PackOutput.Target.DATA_PACK" &&
                    args[1].trim() == "\"advancements\""
            }
        if (pathCalls.size != 1) return null

        val identifiers = Regex("""\b$ID\b""").findAll(JavaSourceText.maskCommentsAndLiterals(parsed.source))
            .map { it.value }
            .toSet()
        val registryField = fresh("registries", identifiers)
        val registryParameter = registryField
        val registryValue = fresh("registryProvider", identifiers + registryField)
        return ProviderShape(
            parsed.file,
            typeName,
            owner.nameAsString,
            constructor.parameters.size,
            constructor,
            outputField,
            outputFieldName,
            outputParameterName,
            outputAssignment,
            run,
            holderName,
            saveCalls,
            pathCalls.single(),
            registryField,
            registryParameter,
            registryValue
        )
    }

    private fun constructorCalls(
        provider: ProviderShape,
        snapshot: ParseSnapshot,
        index: Index
    ): List<ConstructorCall>? {
        for ((file, source) in snapshot.unparsed) {
            if (file == provider.file) continue
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            if (Regex("""\b${Regex.escape(provider.simpleName)}\b""").containsMatchIn(masked)) return null
        }

        val calls = mutableListOf<ConstructorCall>()
        for (parsed in snapshot.parsed.values) {
            val masked = JavaSourceText.maskCommentsAndLiterals(parsed.source)
            if (Regex("""\b${Regex.escape(provider.simpleName)}\s*::\s*new\b""").containsMatchIn(masked)) return null
            for (creation in parsed.unit.findAll(ObjectCreationExpr::class.java)) {
                val created = parsed.resolve(creation.type, parsed.owner(creation), index)?.raw ?: continue
                if (created != provider.typeName) continue
                if (creation.arguments.size != provider.constructorArity) return null
                if (creation.anonymousClassBody.isPresent) return null
                val closeParen = objectCreationCloseParen(parsed, creation) ?: return null
                val method = creation.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
                val eventParameters = method.parameters.filter {
                    parsed.resolve(it.type, parsed.owner(it), index)?.raw == GATHER_DATA_EVENT
                }
                if (eventParameters.size != 1) return null
                val registryFuture = enrichedFutureAt(parsed, method, creation, index) ?: return null
                calls += ConstructorCall(
                    parsed.file,
                    closeParen,
                    creation.arguments.isNotEmpty(),
                    registryFuture
                )
            }
        }
        return calls.takeIf { it.isNotEmpty() }
    }

    private fun objectCreationCloseParen(parsed: SourceUnit, creation: ObjectCreationExpr): Int? {
        if (creation.anonymousClassBody.isPresent) return null
        val start = parsed.start(creation)
        val end = parsed.endExclusive(creation)
        if (start < 0 || end <= start) return null
        val masked = JavaSourceText.maskCommentsAndLiterals(parsed.source)
        val open = masked.indexOf('(', start)
        if (open !in start until end) return null
        val close = JavaSourceText.matchingParen(masked, open)
        if (close !in (open + 1) until end) return null
        if (masked.substring(close + 1, end).isNotBlank()) return null
        return close
    }

    /**
     * Select only the future whose value at the constructor call is unconditionally the registry
     * provider exposed by a DatapackBuiltinEntriesProvider. Merely being initialized from
     * GatherDataEvent#getLookupProvider is the pre-enrichment value and is not accepted.
     */
    private fun enrichedFutureAt(
        parsed: SourceUnit,
        method: MethodDeclaration,
        call: ObjectCreationExpr,
        index: Index
    ): String? {
        val body = method.body.orElse(null) ?: return null
        val callOffset = parsed.start(call)
        val futures = body.findAll(VariableDeclarator::class.java)
            .filter { variable ->
                val type = parsed.resolve(variable.type, parsed.owner(variable), index)
                type?.raw == COMPLETABLE_FUTURE &&
                    type.arguments.singleOrNull()?.raw == HOLDER_PROVIDER &&
                    visibleAt(parsed, variable, call)
            }
            .groupBy { it.nameAsString }
            .mapNotNull { (_, declarations) ->
                declarations.minWithOrNull(
                    compareBy<VariableDeclarator> {
                        val block = it.findAncestor(BlockStmt::class.java).orElse(null)
                        if (block == null) Int.MAX_VALUE else parsed.endExclusive(block) - parsed.start(block)
                    }.thenByDescending { parsed.start(it) }
                )
            }

        val valueTypes = ValueTypes(parsed, index)
        val enriched = futures.filter { variable ->
            val assignments = body.findAll(AssignExpr::class.java)
                .filter { parsed.start(it) < callOffset }
                .filter { assignment ->
                    val target = assignment.target as? NameExpr ?: return@filter false
                    target.nameAsString == variable.nameAsString &&
                        localBindingAt(parsed, method, target.nameAsString, target) === variable
                }
            if (assignments.any { it.operator != AssignExpr.Operator.ASSIGN }) return@filter false
            val initializer = variable.initializer.orElse(null)
            val reachingWrites = buildList<Triple<Int, Expression, Boolean>> {
                if (initializer != null) add(Triple(parsed.start(variable), initializer, true))
                assignments.forEach {
                    add(Triple(parsed.start(it), it.value, dominates(parsed, it, call)))
                }
            }
            val lastDominant = reachingWrites
                .filter { it.third }
                .maxByOrNull { it.first }
                ?: return@filter false
            if (reachingWrites.any { !it.third && it.first > lastDominant.first }) return@filter false

            val last = lastDominant.second as? MethodCallExpr ?: return@filter false
            if (last.nameAsString != "getRegistryProvider" || last.arguments.isNotEmpty()) return@filter false
            val receiver = last.scope.orElse(null) ?: return@filter false
            val receiverType = valueTypes.typeOf(receiver) ?: return@filter false
            index.inherits(receiverType.raw, DATAPACK_BUILTIN_PROVIDER) &&
                !index.declaresMethodBefore(
                    receiverType.raw,
                    DATAPACK_BUILTIN_PROVIDER,
                    "getRegistryProvider",
                    0
                )
        }
        return enriched.singleOrNull()?.nameAsString
    }

    private fun localBindingAt(
        parsed: SourceUnit,
        method: MethodDeclaration,
        name: String,
        use: Node
    ): VariableDeclarator? =
        method.body.orElse(null)?.findAll(VariableDeclarator::class.java).orEmpty()
            .filter { it.nameAsString == name && visibleAt(parsed, it, use) }
            .minWithOrNull(
                compareBy<VariableDeclarator> {
                    val block = it.findAncestor(BlockStmt::class.java).orElse(null)
                    if (block == null) Int.MAX_VALUE else parsed.endExclusive(block) - parsed.start(block)
                }.thenByDescending { parsed.start(it) }
            )

    private fun visibleAt(parsed: SourceUnit, variable: VariableDeclarator, use: Node): Boolean {
        val block = variable.findAncestor(BlockStmt::class.java).orElse(null) ?: return false
        val declarationStatement = variable.findAncestor(ExpressionStmt::class.java).orElse(null) ?: return false
        if (declarationStatement.parentNode.orElse(null) !== block) return false
        val pathChild = directChild(block, use) ?: return false
        return parsed.start(declarationStatement) < parsed.start(pathChild)
    }

    private fun dominates(parsed: SourceUnit, write: Node, use: Node): Boolean {
        val statement = write.findAncestor(ExpressionStmt::class.java).orElse(null) ?: return false
        val block = statement.parentNode.orElse(null) as? BlockStmt ?: return false
        val pathChild = directChild(block, use) ?: return false
        return parsed.start(statement) < parsed.start(pathChild)
    }

    private fun directChild(block: BlockStmt, node: Node): Node? {
        var current: Node = node
        while (true) {
            val parent = current.parentNode.orElse(null) ?: return null
            if (parent === block) return current
            current = parent
        }
    }

    private fun providerMigration(parsed: SourceUnit, provider: ProviderShape): FileMigration? {
        val newline = if ("\r\n" in parsed.source) "\r\n" else "\n"
        val edits = mutableListOf<Edit>()

        val fieldIndent = lineIndent(parsed.source, parsed.start(provider.outputField))
        edits += Edit(
            parsed.endExclusive(provider.outputField),
            parsed.endExclusive(provider.outputField),
            "$newline${fieldIndent}private final CompletableFuture<HolderLookup.Provider> ${provider.registryFieldName};"
        )

        val constructorOpen = parsed.source.indexOf('(', parsed.start(provider.constructor))
        if (constructorOpen < 0) return null
        val constructorClose = JavaSourceText.matchingParen(
            JavaSourceText.maskCommentsAndLiterals(parsed.source),
            constructorOpen
        )
        if (constructorClose < 0 || constructorClose >= parsed.endExclusive(provider.constructor)) return null
        edits += Edit(
            constructorClose,
            constructorClose,
            (if (provider.constructor.parameters.isEmpty()) "" else ", ") +
                "CompletableFuture<HolderLookup.Provider> ${provider.registryParameterName}"
        )
        val assignmentIndent = lineIndent(parsed.source, parsed.start(provider.outputAssignment))
        edits += Edit(
            parsed.endExclusive(provider.outputAssignment),
            parsed.endExclusive(provider.outputAssignment),
            "$newline${assignmentIndent}this.${provider.registryFieldName} = ${provider.registryParameterName};"
        )

        val body = provider.runMethod.body.get()
        val bodyStart = parsed.start(body)
        val bodyEnd = parsed.endExclusive(body)
        if (bodyStart < 0 || bodyEnd <= bodyStart + 1 ||
            parsed.source.getOrNull(bodyStart) != '{' ||
            parsed.source.getOrNull(bodyEnd - 1) != '}'
        ) return null
        var inner = parsed.source.substring(bodyStart + 1, bodyEnd - 1)
        val relativeEdits = mutableListOf<Edit>()
        for (range in provider.saveCalls) {
            val original = parsed.source.substring(range)
            val open = original.indexOf('(')
            val args = JavaSourceText.splitTopLevelArgs(original.substring(open + 1, original.length - 1))
            val replacement = "DataProvider.saveStable(" +
                "${args[0]}, ${provider.registryValueName}, Advancement.CODEC, " +
                "${provider.holderName}.value(), ${args[2]})"
            relativeEdits += Edit(
                range.first - bodyStart - 1,
                range.last + 1 - bodyStart - 1,
                replacement
            )
        }
        val pathRange = provider.pathCall
        relativeEdits += Edit(
            pathRange.first - bodyStart - 1,
            pathRange.last + 1 - bodyStart - 1,
            "${provider.outputFieldName}.createRegistryElementsPathProvider(Registries.ADVANCEMENT)"
        )
        inner = applyEdits(inner, relativeEdits)
        val methodIndent = lineIndent(parsed.source, parsed.start(provider.runMethod))
        val nested = inner.trim('\r', '\n')
            .split(Regex("""\r?\n"""))
            .joinToString(newline) { if (it.isBlank()) it else "\t$it" }
        val wrapped = buildString {
            append('{').append(newline)
            append(methodIndent).append('\t')
                .append("return ").append(provider.registryFieldName)
                .append(".thenCompose(").append(provider.registryValueName).append(" -> {")
                .append(newline)
            append(nested).append(newline)
            append(methodIndent).append('\t').append("});").append(newline)
            append(methodIndent).append('}')
        }
        edits += Edit(bodyStart, bodyEnd, wrapped)

        return FileMigration(
            edits,
            linkedSetOf(
                ADVANCEMENT,
                "net.minecraft.core.HolderLookup",
                "net.minecraft.core.registries.Registries",
                COMPLETABLE_FUTURE
            )
        )
    }

    private fun findCalls(source: String, qualifiedName: String, start: Int, endExclusive: Int): List<IntRange> {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val pattern = Regex("""(?<![\w$])${Regex.escape(qualifiedName)}\s*\(""")
        return pattern.findAll(masked, start).mapNotNull { match ->
            if (match.range.first >= endExclusive) return@mapNotNull null
            val open = masked.indexOf('(', match.range.first)
            val close = if (open >= 0) JavaSourceText.matchingParen(masked, open) else -1
            if (close < 0 || close >= endExclusive) null else match.range.first..close
        }.toList()
    }

    private fun fresh(base: String, identifiers: Set<String>): String {
        if (base !in identifiers) return base
        val generated = "modporter\$$base"
        if (generated !in identifiers) return generated
        var index = 2
        while ("$generated$index" in identifiers) index++
        return "$generated$index"
    }

    private fun lineIndent(source: String, offset: Int): String {
        val start = source.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
        return source.substring(start, offset).takeWhile { it == ' ' || it == '\t' }
    }

    private fun nonOverlapping(edits: List<Edit>): Boolean {
        val ordered = edits.sortedWith(compareBy<Edit> { it.start }.thenBy { it.endExclusive })
        var previousEnd = -1
        var previousStart = -1
        for (edit in ordered) {
            if (edit.start < 0 || edit.endExclusive < edit.start) return false
            if (edit.start == previousStart) return false
            if (edit.start < previousEnd) return false
            previousStart = edit.start
            previousEnd = maxOf(previousEnd, edit.endExclusive)
        }
        return true
    }

    private fun applyEdits(source: String, edits: List<Edit>): String {
        if (edits.isEmpty()) return source
        val ordered = edits.sortedByDescending { it.start }
        var lastStart = source.length + 1
        var result = source
        for (edit in ordered) {
            require(edit.start in 0..source.length && edit.endExclusive in edit.start..source.length)
            require(edit.endExclusive <= lastStart) { "Overlapping advancement edits" }
            result = result.replaceRange(edit.start, edit.endExclusive, edit.replacement)
            lastStart = edit.start
        }
        return result
    }
}
