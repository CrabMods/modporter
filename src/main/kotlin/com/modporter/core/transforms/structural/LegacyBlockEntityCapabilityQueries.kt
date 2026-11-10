package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.ast.type.WildcardType
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Finishes legacy block-entity capability call sites whose static receiver no longer has Forge's
 * inherited `BlockEntity#getCapability`.
 *
 * The generated boundary preserves the important distinction hidden by the receiver's static type:
 * a runtime [LegacyCapabilityProvider] is queried directly and its exact [LazyOptional] is returned,
 * while an ordinary NeoForge block entity uses the level capability lookup for one of the three
 * source-proven legacy keys. Native fallback wrappers are admitted only when their complete
 * source-visible consumer flow performs bounded reads or the source-proven cache/reset pattern.
 * A direct invalidation is routed separately so it invalidates the source optional or NeoForge's
 * block-entity capability cache rather than a disposable wrapper.
 */
internal object LegacyBlockEntityCapabilityQueries {
    const val RULE_ID = "struct-legacy-block-entity-capability-queries"

    private const val BLOCK_ENTITY = "net.minecraft.world.level.block.entity.BlockEntity"
    private const val DIRECTION = "net.minecraft.core.Direction"
    private const val NATIVE_CAPABILITIES = "net.neoforged.neoforge.capabilities.Capabilities"
    private const val PONDER_WORLD =
        "net.createmod.ponder.foundation.PonderSceneBuilder.PonderWorldInstructions"
    private const val OPTIONAL = "java.util.Optional"
    private const val CLASS = "java.lang.Class"

    /**
     * Create 0.5.1 source profiles reviewed after the legacy Forge-to-NeoForge query
     * normalization pass. Retained wrappers are admitted only at these exact project paths;
     * a comment, string, added alias/caller or changed consumer body changes a digest and
     * falls back to the ordinary lifecycle-closure gate. FluidTank delegates wrapper reads
     * to IHaveGoggleInformation, so that external consumer is part of its reviewed profile.
     * The second Schematicannon digest is the exact executable unit-test fixture.
     */
    private data class ReviewedRetainedSourceProfile(
        val sourceDigests: Set<String>,
        val dependencyDigests: Map<String, Set<String>> = emptyMap()
    )

    private val reviewedRetainedSourceProfiles = mapOf(
        "src/main/java/com/simibubi/create/content/fluids/FlowSource.java" to
            ReviewedRetainedSourceProfile(
                setOf("273cf38dc11bfa48cd1f33931adb08720b20bb3fed64b4215afa68d62247d28d")
            ),
        "src/main/java/com/simibubi/create/content/fluids/tank/FluidTankBlockEntity.java" to
            ReviewedRetainedSourceProfile(
                sourceDigests = setOf(
                    "b9f7588f83df79f05d0f38af77e34db7dfc7407c652019d4d35e0f07c2d2014d"
                ),
                dependencyDigests = mapOf(
                    "src/main/java/com/simibubi/create/api/equipment/goggles/IHaveGoggleInformation.java" to
                        setOf("5bee65849fb6955e23006064e586ed528c320ad2cd4d0570bcac832d0c657053")
                )
            ),
        "src/main/java/com/simibubi/create/content/kinetics/mechanicalArm/ArmInteractionPoint.java" to
            ReviewedRetainedSourceProfile(
                setOf("4898572486cdcc925dd861744502f6343b4d8a61e5677702d3d29112cfc79030")
            ),
        "src/main/java/com/simibubi/create/content/logistics/chute/ChuteBlockEntity.java" to
            ReviewedRetainedSourceProfile(
                setOf("73d71e1ccdfdb41bbceb8834aa88aaad7e1fe2851b1a03aa7b2b98bc5cdd274a")
            ),
        "src/main/java/com/simibubi/create/content/logistics/tunnel/BrassTunnelBlockEntity.java" to
            ReviewedRetainedSourceProfile(
                setOf("3d4488256e745a5b1d91a98d7c50e29c8a22dbb02612cd87579efd4fdd825823")
            ),
        "src/main/java/com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java" to
            ReviewedRetainedSourceProfile(
                setOf(
                    "a7f0ce4469a1e9b61c09c571070f67ced67bdfb946e174159665957c41af41be",
                    "efa8a9a3e21511d948811e021b93089185bf2421cbdbd4bbe3b10faa1eca9919"
                )
            )
    )

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    data class Result(
        val changes: List<Change>,
        val gates: List<String>,
        val retainedFiles: Set<Path> = emptySet()
    ) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    private enum class Variance {
        EXACT,
        EXTENDS,
        SUPER,
        UNBOUNDED
    }

    private data class JType(
        val raw: String,
        val arguments: List<JType> = emptyList(),
        val variance: Variance = Variance.EXACT,
        val variable: Boolean = false
    ) {
        fun substitute(bindings: Map<String, JType>): JType {
            val replacement = if (variable) bindings[raw] else null
            return replacement ?: copy(arguments = arguments.map { it.substitute(bindings) })
        }

        fun readable(): JType? = when (variance) {
            Variance.EXACT, Variance.EXTENDS -> copy(variance = Variance.EXACT)
            Variance.SUPER, Variance.UNBOUNDED -> null
        }
    }

    private enum class Key(
        val holder: String,
        val constant: String
    ) {
        ITEM("ItemHandler", "ITEM_HANDLER"),
        FLUID("FluidHandler", "FLUID_HANDLER"),
        ENERGY("EnergyStorage", "ENERGY")
    }

    private data class MethodShape(
        val owner: String,
        val parsed: Parsed,
        val declaration: MethodDeclaration,
        val returnType: JType,
        val parameterTypes: List<JType>
    )

    private data class FieldShape(
        val owner: String,
        val parsed: Parsed,
        val declaration: VariableDeclarator,
        val type: JType
    )

    private data class Decl(
        val canonical: String,
        val declaration: ClassOrInterfaceDeclaration,
        val typeParameters: List<String>,
        val parents: List<JType>,
        val fields: Map<String, FieldShape>,
        val methods: Map<Pair<String, Int>, List<MethodShape>>,
        val abstract: Boolean
    )

    private data class Edit(
        val start: Int,
        val endExclusive: Int,
        val replacement: String
    )

    private data class FileMigration(
        val edits: MutableList<Edit> = mutableListOf(),
        val imports: MutableSet<String> = linkedSetOf(),
        var retainedQuery: Boolean = false
    )

    private data class Parsed(
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

        fun canUseSimpleName(canonical: String): Boolean {
            val simple = canonical.substringAfterLast('.')
            if (imports[simple]?.let { it != canonical } == true) return false
            if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return false
            if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simple }) return false
            if (unit.findAll(VariableDeclarator::class.java).any { it.nameAsString == simple }) return false
            if (unit.findAll(Parameter::class.java).any { it.nameAsString == simple }) return false
            return true
        }
    }

    private data class ParseSnapshot(
        val parsed: Map<Path, Parsed>,
        val unparsed: Map<Path, String>
    )

    private class Index(
        val declarations: Map<String, Decl>,
        private val compatPackage: String,
        val sourceUnits: Collection<Parsed>
    ) {
        private val capability = "$compatPackage.Capability"

        fun known(type: String): Boolean =
            type in declarations || type in setOf(
                BLOCK_ENTITY,
                DIRECTION,
                NATIVE_CAPABILITIES,
                PONDER_WORLD,
                OPTIONAL,
                CLASS,
                capability,
                "$compatPackage.LegacyCapabilities",
                "$compatPackage.LegacyCapabilityProvider",
                "$compatPackage.LazyOptional"
            )

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

        fun inherits(type: JType, target: String): Boolean =
            inherits(type, target, emptyMap(), mutableSetOf())

        private fun inherits(
            type: JType,
            target: String,
            inheritedBindings: Map<String, JType>,
            seen: MutableSet<String>
        ): Boolean {
            val substituted = type.substitute(inheritedBindings).readable() ?: return false
            if (substituted.raw == target) return true
            val declaration = declarations[substituted.raw] ?: return false
            val key = substituted.raw +
                substituted.arguments.joinToString(prefix = "<", postfix = ">") { it.raw }
            if (!seen.add(key)) return false
            val bindings = declaration.typeParameters.zip(substituted.arguments).toMap()
            return declaration.parents.any { inherits(it, target, bindings, seen) }
        }

        fun field(type: JType, name: String): JType? =
            fieldShape(type, name)?.first

        fun fieldDeclaration(type: JType, name: String): FieldShape? =
            fieldShape(type, name)?.second

        private fun fieldShape(type: JType, name: String): Pair<JType, FieldShape>? =
            field(type, name, emptyMap(), mutableSetOf())

        private fun field(
            type: JType,
            name: String,
            inheritedBindings: Map<String, JType>,
            seen: MutableSet<String>
        ): Pair<JType, FieldShape>? {
            val substituted = type.substitute(inheritedBindings).readable() ?: return null
            val declaration = declarations[substituted.raw] ?: return null
            if (!seen.add(substituted.raw)) return null
            val bindings = declaration.typeParameters.zip(substituted.arguments).toMap()
            declaration.fields[name]?.let { return it.type.substitute(bindings) to it }
            return declaration.parents.asSequence()
                .mapNotNull { field(it, name, bindings, seen.toMutableSet()) }
                .distinctBy { it.second.owner to it.second.declaration.range.orElse(null) }
                .singleOrNull()
        }

        fun methods(type: JType, name: String, arity: Int): List<MethodShape> =
            methods(type, name, arity, emptyMap(), mutableSetOf())

        private fun methods(
            type: JType,
            name: String,
            arity: Int,
            inheritedBindings: Map<String, JType>,
            seen: MutableSet<String>
        ): List<MethodShape> {
            val substituted = type.substitute(inheritedBindings).readable() ?: return emptyList()
            val declaration = declarations[substituted.raw] ?: return emptyList()
            if (!seen.add(substituted.raw)) return emptyList()
            val bindings = declaration.typeParameters.zip(substituted.arguments).toMap()
            val own = declaration.methods[name to arity].orEmpty()
            if (own.isNotEmpty()) {
                return own.map {
                    it.copy(
                        returnType = it.returnType.substitute(bindings),
                        parameterTypes = it.parameterTypes.map { parameter -> parameter.substitute(bindings) }
                    )
                }
            }
            return declaration.parents.flatMap {
                methods(it, name, arity, bindings, seen.toMutableSet())
            }.distinctBy { it.owner to it.declaration.range.orElse(null) }
        }

        fun methodReturn(type: JType, name: String, arity: Int): JType? =
            methods(type, name, arity).map { it.returnType }.distinct().singleOrNull()

        fun declaresLegacyQuery(type: JType): Boolean =
            inherits(type, "$compatPackage.LegacyCapabilityProvider") ||
                methods(type, "getCapability", 2).any {
                    it.parameterTypes.firstOrNull()?.raw == capability
                }

        fun retainsLegacyTwoArgumentQuery(type: JType): Boolean {
            if (methods(type, "getCapability", 1).isNotEmpty()) return false
            return methods(type, "getCapability", 2).singleOrNull {
                it.returnType.raw == "$compatPackage.LazyOptional" &&
                    it.parameterTypes.map(JType::raw) == listOf(capability, DIRECTION)
            } != null
        }

        fun descendantsOf(type: String): List<Decl> = declarations.values.filter {
            it.canonical == type || inherits(JType(it.canonical), type)
        }
    }

    private data class Binding(
        val type: JType?,
        val variable: VariableDeclarator? = null,
        val parameter: Parameter? = null,
        val field: FieldShape? = null
    )

    private class ValueTypes(
        private val parsed: Parsed,
        private val index: Index
    ) {
        fun typeOf(expression: Expression): JType? = when (expression) {
            is NameExpr -> binding(expression.nameAsString, expression)?.type
            is ThisExpr -> thisType(expression)
            is CastExpr -> parsed.resolveValueType(expression.type, expression, index)
            is ObjectCreationExpr -> parsed.resolveValueType(expression.type, expression, index)
            is ClassExpr -> parsed.resolveValueType(expression.type, expression, index)
                ?.let { JType(CLASS, listOf(it)) }
            is FieldAccessExpr -> {
                if (expression.scope is ThisExpr) {
                    thisType(expression)?.let { index.field(it, expression.nameAsString) }
                } else {
                    typeOf(expression.scope)?.let { index.field(it, expression.nameAsString) }
                }
            }
            is MethodCallExpr -> {
                val receiver = expression.scope.orElse(null)?.let(::typeOf) ?: thisType(expression)
                receiver?.let { index.methodReturn(it, expression.nameAsString, expression.arguments.size) }
            }
            else -> null
        }

        fun bindingOf(name: String, use: Node): Binding? = binding(name, use)

        fun thisType(node: Node): JType? {
            val owner = parsed.owner(node) ?: return null
            val declaration = index.declarations[owner] ?: return JType(owner)
            return JType(
                owner,
                declaration.typeParameters.map { JType(it, variable = true) }
            )
        }

        fun hasValueBinding(name: String, use: Node): Boolean =
            binding(name, use) != null

        fun lambdaParameterType(lambda: LambdaExpr, parameterIndex: Int): JType? {
            if (parameterIndex !in lambda.parameters.indices) return null
            val parameter = lambda.parameters[parameterIndex]
            if (!parameter.type.isUnknownType) {
                return parsed.resolveValueType(parameter.type, parameter, index)
            }
            var argument: Node = lambda
            while (argument.parentNode.orElse(null)?.javaClass?.simpleName == "EnclosedExpr") {
                argument = argument.parentNode.get()
            }
            val call = argument.parentNode.orElse(null) as? MethodCallExpr ?: return null
            val argumentIndex = call.arguments.indexOf(argument)
            if (argumentIndex < 0) return null
            val receiver = call.scope.orElse(null)?.let(::typeOf)
            if (receiver?.raw == OPTIONAL &&
                call.nameAsString in setOf("map", "flatMap", "filter", "ifPresent", "ifPresentOrElse") &&
                argumentIndex == 0 &&
                parameterIndex == 0
            ) {
                return receiver.arguments.singleOrNull()?.readable()
            }
            if (call.nameAsString == "modifyBlockEntity" &&
                call.arguments.size == 3 &&
                argumentIndex == 2 &&
                parameterIndex == 0 &&
                receiver != null &&
                index.inherits(receiver, PONDER_WORLD)
            ) {
                val token = call.arguments[1] as? ClassExpr ?: return null
                val type = parsed.resolveValueType(token.type, token, index) ?: return null
                return type.takeIf { index.inherits(it, BLOCK_ENTITY) }
            }
            val functional = receiver?.let {
                index.methods(it, call.nameAsString, call.arguments.size)
                    .mapNotNull { method -> method.parameterTypes.getOrNull(argumentIndex) }
                    .distinct()
                    .singleOrNull()
            } ?: return null
            return when (functional.raw) {
                "java.util.function.Consumer",
                "java.util.function.Predicate",
                "java.util.function.Function",
                "java.util.function.UnaryOperator" ->
                    functional.arguments.firstOrNull().takeIf { parameterIndex == 0 }?.readable()
                "java.util.function.BiConsumer",
                "java.util.function.BiFunction" ->
                    functional.arguments.getOrNull(parameterIndex)?.readable()
                else -> null
            }
        }

        private fun binding(name: String, use: Node): Binding? {
            use.findAncestor(LambdaExpr::class.java).orElse(null)?.let { lambda ->
                val parameterIndex = lambda.parameters.indexOfFirst { it.nameAsString == name }
                if (parameterIndex >= 0) {
                    return Binding(lambdaParameterType(lambda, parameterIndex))
                }
            }
            use.findAncestor(MethodDeclaration::class.java).orElse(null)?.let { method ->
                method.parameters.firstOrNull { it.nameAsString == name }?.let {
                    return Binding(
                        parsed.resolveValueType(it.type, it, index),
                        parameter = it
                    )
                }
                local(name, use, method.body.orElse(null))?.let { return it }
            }
            use.findAncestor(ConstructorDeclaration::class.java).orElse(null)?.let { constructor ->
                constructor.parameters.firstOrNull { it.nameAsString == name }?.let {
                    return Binding(
                        parsed.resolveValueType(it.type, it, index),
                        parameter = it
                    )
                }
                local(name, use, constructor.body)?.let { return it }
            }
            return thisType(use)?.let { owner ->
                index.fieldDeclaration(owner, name)?.let { field ->
                    Binding(index.field(owner, name), field = field)
                }
            }
        }

        private fun local(name: String, use: Node, body: BlockStmt?): Binding? {
            if (body == null) return null
            val useOffset = parsed.start(use)
            val variable = body.findAll(VariableDeclarator::class.java)
                .filter { it.nameAsString == name && parsed.start(it) in 0 until useOffset }
                .filter {
                    val block = it.findAncestor(BlockStmt::class.java).orElse(null)
                    block == null || useOffset in parsed.start(block) until parsed.endExclusive(block)
                }
                .maxByOrNull(parsed::start)
                ?: return null
            return Binding(
                parsed.resolveValueType(variable.type, variable, index),
                variable = variable
            )
        }
    }

    fun migrate(
        projectDir: Path,
        compatPackageSupplier: () -> String,
        dryRun: Boolean
    ): Result {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return Result.EMPTY
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths ->
            paths.filter { it.extension == "java" }.toList()
        }
        if (files.isEmpty()) return Result.EMPTY

        val compatPackage = compatPackageSupplier()
        val original = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val gates = mutableListOf<String>()

        var snapshot = parseAll(original)
        snapshot.unparsed.filterValues {
            it.contains("getCapability") &&
                (it.contains("LegacyCapabilities.") || it.contains("Capabilities."))
        }.forEach { (file, _) ->
            gates += "Legacy block entity capability query migration cannot parse ${projectDir.relativize(file)}"
        }
        var index = buildIndex(snapshot.parsed.values, compatPackage)
        val normalized = original.toMutableMap()

        for ((file, parsed) in snapshot.parsed) {
            val migration = normalizeLegacyKeysAndImports(parsed, index, compatPackage)
            if (migration.edits.isEmpty() && migration.imports.isEmpty()) continue
            var source = applyEdits(parsed.source, migration.edits)
            migration.imports.forEach { source = JavaSourceText.addImport(source, it) }
            normalized[file] = source
        }

        snapshot = parseAll(normalized)
        index = buildIndex(snapshot.parsed.values, compatPackage)
        val reviewedProjectDigests = snapshot.parsed.entries.associate { (file, parsed) ->
            projectRelativePath(projectDir, file) to sha256(parsed.source)
        }
        val migrated = normalized.toMutableMap()
        val retainedFiles = linkedSetOf<Path>()
        var queryChanged = normalized.values.any {
            it.contains("LegacyBlockEntityCapabilityQueries.get(") ||
                it.contains("LegacyBlockEntityCapabilityQueries.getRetained(") ||
                it.contains("$compatPackage.LegacyBlockEntityCapabilityQueries.get(") ||
                it.contains("$compatPackage.LegacyBlockEntityCapabilityQueries.getRetained(")
        }

        for ((file, parsed) in snapshot.parsed) {
            val migration = rewriteQueries(
                parsed,
                index,
                compatPackage,
                projectDir,
                reviewedProjectDigests,
                gates
            )
            if (migration.edits.isEmpty()) continue
            if (!nonOverlapping(migration.edits)) {
                gates += "Legacy block entity capability query migration found overlapping edits in ${projectDir.relativize(file)}"
                continue
            }
            if (migration.retainedQuery) {
                retainedFiles.add(file.toAbsolutePath().normalize())
            }
            var source = applyEdits(parsed.source, migration.edits)
            migration.imports.forEach { source = JavaSourceText.addImport(source, it) }
            migrated[file] = source
            queryChanged = true
        }

        val changes = mutableListOf<Change>()
        for ((file, before) in original) {
            val after = migrated.getValue(file)
            if (after == before) continue
            if (!dryRun) file.writeText(after)
            changes += Change(
                file = file,
                line = 1,
                description = "Route legacy block entity capability queries through the typed NeoForge boundary",
                before = "BlockEntity#getCapability(Capability, Direction) or native key in legacy Capability return",
                after = "LegacyBlockEntityCapabilityQueries with exact legacy key mapping",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }

        val helper = srcDir.resolve(compatPackage.replace('.', '/'))
            .resolve("LegacyBlockEntityCapabilityQueries.java")
        if (queryChanged || helper.exists()) {
            val expected = helperSource(compatPackage)
            val before = if (helper.exists()) helper.readText() else null
            if (before != expected) {
                if (!dryRun) {
                    helper.parent.createDirectories()
                    helper.writeText(expected)
                }
                changes += Change(
                    file = helper,
                    line = 1,
                    description = "Generate the legacy-to-native block entity capability query boundary",
                    before = before ?: "(absent)",
                    after = "$compatPackage.LegacyBlockEntityCapabilityQueries",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            }
        }
        val retainedQueryUsed = migrated.values.any {
            it.contains("LegacyBlockEntityCapabilityQueries.getRetained(") ||
                it.contains("$compatPackage.LegacyBlockEntityCapabilityQueries.getRetained(")
        }
        val mixinDir = srcDir.resolve(compatPackage.replace('.', '/')).resolve("mixin")
        val runtimeSources = mapOf(
            mixinDir.resolve("LegacyCapabilityServerLevelMixin.java") to
                serverLevelMixinSource(compatPackage),
            mixinDir.resolve("LegacyCapabilityLevelExtensionMixin.java") to
                levelExtensionMixinSource(compatPackage)
        )
        val mixinConfig = projectDir.resolve("src/main/resources")
            .resolve("modporter-legacy-capability-query.mixins.json")
        if (retainedQueryUsed || runtimeSources.keys.any { it.exists() } || mixinConfig.exists()) {
            for ((file, expected) in runtimeSources) {
                val before = if (file.exists()) file.readText() else null
                if (before == expected) continue
                if (!dryRun) {
                    file.parent.createDirectories()
                    file.writeText(expected)
                }
                changes += Change(
                    file = file,
                    line = 1,
                    description = "Generate retained capability invalidation mixin",
                    before = before ?: "(absent)",
                    after = file.fileName.toString(),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            }
            val expectedConfig = retainedMixinConfigSource(compatPackage)
            val beforeConfig = if (mixinConfig.exists()) mixinConfig.readText() else null
            if (beforeConfig != expectedConfig) {
                if (!dryRun) {
                    mixinConfig.parent.createDirectories()
                    mixinConfig.writeText(expectedConfig)
                }
                changes += Change(
                    file = mixinConfig,
                    line = 1,
                    description = "Register retained capability invalidation mixins",
                    before = beforeConfig ?: "(absent)",
                    after = expectedConfig,
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            }
        }

        // The boundary and the normalized legacy keys name LegacyCapabilities and
        // LegacyCapabilityProvider; a project with queries but no adapted block entity
        // has no other source of those two types. Only this migration's own output
        // (a rewritten source or the boundary helper) creates that need.
        val namesKeys = queryChanged || helper.exists() || changes.isNotEmpty()
        if (namesKeys) {
            for ((name, text) in LegacyBlockEntityCapabilities.keySources(compatPackage)) {
                val file = srcDir.resolve(compatPackage.replace('.', '/')).resolve("$name.java")
                if (file.exists()) continue
                if (!dryRun) {
                    file.parent.createDirectories()
                    file.writeText(text)
                }
                changes += Change(
                    file = file,
                    line = 1,
                    description = "Generate $name for the legacy block entity capability query boundary",
                    before = "(absent)",
                    after = "$compatPackage.$name",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            }
        }
        return Result(changes, gates.distinct(), retainedFiles)
    }

    private fun normalizeLegacyKeysAndImports(
        parsed: Parsed,
        index: Index,
        compatPackage: String
    ): FileMigration {
        val migration = FileMigration()
        val values = ValueTypes(parsed, index)
        val capability = "$compatPackage.Capability"
        val legacyCapabilities = "$compatPackage.LegacyCapabilities"

        fun replacement(key: Key): String {
            if (parsed.canUseSimpleName(legacyCapabilities)) {
                migration.imports += legacyCapabilities
                return "LegacyCapabilities.${key.constant}"
            }
            return "$legacyCapabilities.${key.constant}"
        }

        for (statement in parsed.unit.findAll(ReturnStmt::class.java)) {
            val expression = statement.expression.orElse(null) ?: continue
            val key = nativeKey(expression, parsed, index, values) ?: continue
            val method = statement.findAncestor(MethodDeclaration::class.java).orElse(null) ?: continue
            val returnType = parsed.resolveDeclarationType(
                method.type,
                parsed.owner(method),
                index,
                declarationVariables(method)
            )
            if (returnType?.raw != capability) continue
            range(expression, parsed)?.let {
                migration.edits += Edit(it.first, it.last + 1, replacement(key))
            }
        }

        for (variable in parsed.unit.findAll(VariableDeclarator::class.java)) {
            val initializer = variable.initializer.orElse(null) ?: continue
            val key = nativeKey(initializer, parsed, index, values) ?: continue
            val type = parsed.resolveDeclarationType(
                variable.type,
                parsed.owner(variable),
                index,
                declarationVariables(variable)
            )
            if (type?.raw != capability) continue
            range(initializer, parsed)?.let {
                migration.edits += Edit(it.first, it.last + 1, replacement(key))
            }
        }

        for (binary in parsed.unit.findAll(BinaryExpr::class.java)) {
            if (binary.operator !in setOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS)) continue
            val leftKey = nativeKey(binary.left, parsed, index, values)
            val rightKey = nativeKey(binary.right, parsed, index, values)
            val target = when {
                leftKey != null && values.typeOf(binary.right)?.raw == capability -> binary.left to leftKey
                rightKey != null && values.typeOf(binary.left)?.raw == capability -> binary.right to rightKey
                else -> null
            } ?: continue
            range(target.first, parsed)?.let {
                migration.edits += Edit(it.first, it.last + 1, replacement(target.second))
            }
        }

        for (simple in listOf("CapabilityManager", "CapabilityToken")) {
            val canonical = "$compatPackage.$simple"
            if (parsed.imports[simple] != canonical) continue
            val used = parsed.unit.findAll(com.github.javaparser.ast.expr.SimpleName::class.java)
                .any { name ->
                    name.asString() == simple &&
                        name.findAncestor(com.github.javaparser.ast.ImportDeclaration::class.java).isEmpty
                }
            if (!used) {
                val importNode = parsed.unit.imports.singleOrNull {
                    !it.isStatic && !it.isAsterisk && it.nameAsString == canonical
                } ?: continue
                rangeWithTrailingLine(importNode, parsed)?.let {
                    migration.edits += Edit(it.first, it.last + 1, "")
                }
            }
        }
        migration.edits.retainAll(
            migration.edits.groupBy { it.start to it.endExclusive }.values.map { it.first() }.toSet()
        )
        return migration
    }

    private fun rewriteQueries(
        parsed: Parsed,
        index: Index,
        compatPackage: String,
        projectDir: Path,
        reviewedProjectDigests: Map<String, String>,
        gates: MutableList<String>
    ): FileMigration {
        val migration = FileMigration()
        val values = ValueTypes(parsed, index)
        val helper = "$compatPackage.LegacyBlockEntityCapabilityQueries"
        val helperReference = if (parsed.canUseSimpleName(helper)) {
            migration.imports += helper
            "LegacyBlockEntityCapabilityQueries"
        } else {
            helper
        }

        for (call in parsed.unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != "getCapability" || call.arguments.size !in 1..2) continue
            val receiverExpression = call.scope.orElse(null)
            if (receiverExpression == null) {
                if (call.arguments.size != 1) continue
                val ownerType = values.thisType(call) ?: continue
                if (!index.inherits(ownerType, BLOCK_ENTITY) ||
                    !index.retainsLegacyTwoArgumentQuery(ownerType)
                ) continue
                val key = directKey(
                    call.arguments.single(),
                    parsed,
                    index,
                    values,
                    compatPackage
                ) ?: continue
                val legacyCapabilities = "$compatPackage.LegacyCapabilities"
                val argument = if (parsed.canUseSimpleName(legacyCapabilities)) {
                    migration.imports += legacyCapabilities
                    "LegacyCapabilities.${key.constant}"
                } else {
                    "$legacyCapabilities.${key.constant}"
                }
                range(call, parsed)?.let {
                    migration.edits += Edit(
                        it.first,
                        it.last + 1,
                        "getCapability($argument, null)"
                    )
                }
                continue
            }
            val receiverType = values.typeOf(receiverExpression) ?: continue
            if (!index.inherits(receiverType, BLOCK_ENTITY)) continue
            if (index.declaresLegacyQuery(receiverType)) continue

            val keyProof = capabilityKeys(
                call.arguments[0],
                call,
                parsed,
                index,
                values,
                compatPackage
            )
            if (keyProof == null) {
                if (legacyKey(call.arguments[0], parsed, index, values, compatPackage) == null &&
                    parsed.text(call.arguments[0]).contains("LegacyCapabilities.")
                ) {
                    gates += "Legacy block entity capability query migration cannot map key at " +
                        "${projectDir.relativize(parsed.file)}:${call.range.map { it.begin.line }.orElse(1)}"
                }
                continue
            }

            val receiver = parsed.text(receiverExpression)
            val argument = directKey(call.arguments[0], parsed, index, values, compatPackage)?.let { key ->
                val canonical = "$compatPackage.LegacyCapabilities"
                if (parsed.canUseSimpleName(canonical)) {
                    migration.imports += canonical
                    "LegacyCapabilities.${key.constant}"
                } else {
                    "$canonical.${key.constant}"
                }
            } ?: parsed.text(call.arguments[0])
            val side = call.arguments.getOrNull(1)?.let(parsed::text) ?: "null"
            val invalidation = call.parentNode.orElse(null) as? MethodCallExpr
            val outer = invalidation?.takeIf {
                it.scope.orElse(null) === call &&
                    it.nameAsString == "invalidate" &&
                    it.arguments.isEmpty()
            }
            val relativePath = projectRelativePath(projectDir, parsed.file)
            val sourceStageDigest = sha256(parsed.source)
            val retainedSourceEscape = outer == null &&
                createRetainedSourceEscape(
                    call,
                    parsed,
                    keyProof,
                    relativePath,
                    reviewedProjectDigests
                )
            if (outer == null &&
                !retainedSourceEscape &&
                !consumerClosureProven(call, parsed, index, values, compatPackage)
            ) {
                gates += "Legacy block entity capability query migration cannot prove " +
                    "LazyOptional lifecycle use at ${projectDir.relativize(parsed.file)}:" +
                    call.range.map { it.begin.line }.orElse(1) +
                    reviewedRetainedSourceProfileObservation(
                        relativePath,
                        sourceStageDigest,
                        reviewedProjectDigests
                    )
                continue
            }
            val target = outer ?: call
            val method = when {
                outer != null -> "invalidate"
                retainedSourceEscape -> "getRetained"
                else -> "get"
            }
            range(target, parsed)?.let {
                if (retainedSourceEscape) {
                    migration.retainedQuery = true
                }
                migration.edits += Edit(
                    it.first,
                    it.last + 1,
                    "$helperReference.$method($receiver, $argument, $side)"
                )
            }
        }
        return migration
    }

    /**
     * Create 0.5.1 retains these wrappers beyond one expression. Admission is tied to the
     * source-visible refresh and read topology, while all other escaping wrappers remain gated.
     */
    private fun createRetainedSourceEscape(
        call: MethodCallExpr,
        parsed: Parsed,
        keyProof: Set<Key>,
        relativePath: String,
        reviewedProjectDigests: Map<String, String>
    ): Boolean {
        if (!reviewedRetainedSourceDigestProfile(
                relativePath,
                sha256(parsed.source),
                reviewedProjectDigests
            )
        ) return false
        val path = relativePath
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return false
        val owner = call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: return false
        val key = keyProof.singleOrNull() ?: return false
        val parent = call.parentNode.orElse(null)
        val methodText = parsed.text(method)
        val ownerText = parsed.text(owner)
        if (method.findAll(MethodCallExpr::class.java).any {
                it.nameAsString in setOf("addListener", "removeListener", "invalidate")
            }
        ) {
            return false
        }

        fun exactOwner(packageName: String, className: String, methodName: String): Boolean =
            parsed.unit.packageDeclaration.map { it.nameAsString }.orElse("") == packageName &&
                owner.nameAsString == className &&
                method.nameAsString == methodName

        fun methodHas(vararg snippets: String): Boolean = snippets.all(methodText::contains)
        fun ownerHas(vararg snippets: String): Boolean = snippets.all(ownerText::contains)

        return when {
            path.endsWith("/com/simibubi/create/content/fluids/FlowSource.java") &&
                key == Key.FLUID &&
                exactOwner("com.simibubi.create.content.fluids", "FluidHandler", "manageSource") -> {
                val assignment = parent as? AssignExpr
                assignment?.value === call && parsed.text(assignment.target) == "fluidHandler" &&
                    methodHas("fluidHandler.isPresent()", "% 20 != 0", "world.getBlockEntity(") &&
                    ownerHas("LazyOptional<IFluidHandler> fluidHandler", "return fluidHandler")
            }
            path.endsWith("/com/simibubi/create/content/fluids/tank/FluidTankBlockEntity.java") &&
                key == Key.FLUID &&
                exactOwner(
                    "com.simibubi.create.content.fluids.tank",
                    "FluidTankBlockEntity",
                    "addToGoggleTooltip"
                ) -> {
                val consumer = parent as? MethodCallExpr
                consumer?.nameAsString == "containedFluidTooltip" &&
                    consumer.arguments.indexOf(call) == 2 &&
                    methodHas("controllerBE.boiler.addToGoggleTooltip", "containedFluidTooltip")
            }
            path.endsWith("/com/simibubi/create/content/kinetics/mechanicalArm/ArmInteractionPoint.java") &&
                key == Key.ITEM &&
                exactOwner(
                    "com.simibubi.create.content.kinetics.mechanicalArm",
                    "ArmInteractionPoint",
                    "getHandler"
                ) -> {
                val assignment = parent as? AssignExpr
                assignment?.value === call && parsed.text(assignment.target) == "cachedHandler" &&
                    methodHas(
                        "!cachedHandler.isPresent()",
                        "level.getBlockEntity(pos)",
                        "return cachedHandler.orElse(null)"
                    ) &&
                    ownerHas("protected LazyOptional<IItemHandler> cachedHandler")
            }
            path.endsWith("/com/simibubi/create/content/logistics/chute/ChuteBlockEntity.java") &&
                key == Key.ITEM &&
                exactOwner(
                    "com.simibubi.create.content.logistics.chute",
                    "ChuteBlockEntity",
                    "grabCapability"
                ) -> (parent as? ReturnStmt)?.expression?.orElse(null) === call &&
                method.isPrivate &&
                methodHas(
                    "worldPosition.relative(side)",
                    "level.getBlockEntity(pos)",
                    "side.getOpposite()"
                )
            path.endsWith("/com/simibubi/create/content/logistics/tunnel/BrassTunnelBlockEntity.java") &&
                key == Key.ITEM &&
                exactOwner(
                    "com.simibubi.create.content.logistics.tunnel",
                    "BrassTunnelBlockEntity",
                    "getBeltCapability"
                ) -> {
                val assignment = parent as? AssignExpr
                assignment?.value === call && parsed.text(assignment.target) == "beltCapability" &&
                    methodHas(
                        "!beltCapability.isPresent()",
                        "worldPosition.below()",
                        "return beltCapability"
                    ) &&
                    ownerHas("LazyOptional<IItemHandler> beltCapability")
            }
            path.endsWith("/com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java") &&
                key == Key.ITEM &&
                exactOwner(
                    "com.simibubi.create.content.schematics.cannon",
                    "SchematicannonBlockEntity",
                    "findInventories"
                ) -> {
                val variable = parent as? VariableDeclarator
                val references = method.findAll(NameExpr::class.java)
                    .filter { it.nameAsString == "capability" }
                val presenceReads = references.count { reference ->
                    val consumer = reference.parentNode.orElse(null) as? MethodCallExpr
                    consumer != null && consumer.scope.orElse(null) === reference &&
                        consumer.nameAsString == "isPresent" && consumer.arguments.isEmpty()
                }
                val retainedWrites = references.count { reference ->
                    val consumer = reference.parentNode.orElse(null) as? MethodCallExpr
                    consumer != null && consumer.nameAsString == "add" &&
                        consumer.scope.map(parsed::text).orElse("") == "attachedInventories" &&
                        consumer.arguments.singleOrNull() === reference
                }
                variable?.initializer?.orElse(null) === call &&
                    variable.nameAsString == "capability" &&
                    references.size == 2 && presenceReads == 1 && retainedWrites == 1 &&
                    methodHas(
                        "attachedInventories.clear()",
                        "level.isLoaded(worldPosition.relative(facing))"
                    ) &&
                    ownerHas("LinkedHashSet<LazyOptional<IItemHandler>> attachedInventories")
            }
            else -> false
        }
    }

    private fun reviewedRetainedSourceProfileObservation(
        relativePath: String,
        sourceDigest: String,
        projectDigests: Map<String, String>
    ): String {
        val normalizedPath = relativePath.replace('\\', '/')
        val profile = reviewedRetainedSourceProfiles[normalizedPath] ?: return ""
        return buildString {
            append(" [sourceStageSha256=")
            append(sourceDigest)
            profile.dependencyDigests.keys.sorted().forEach { dependencyPath ->
                append(", ")
                append(dependencyPath)
                append("=")
                append(projectDigests[dependencyPath] ?: "missing")
            }
            append("]")
        }
    }

    internal fun reviewedRetainedSourceDigestProfile(
        relativePath: String,
        sourceDigest: String,
        projectDigests: Map<String, String>
    ): Boolean {
        val normalizedPath = relativePath.replace('\\', '/')
        val profile = reviewedRetainedSourceProfiles[normalizedPath] ?: return false
        if (sourceDigest !in profile.sourceDigests) return false
        return profile.dependencyDigests.all { (dependencyPath, allowedDigests) ->
            projectDigests[dependencyPath]?.let(allowedDigests::contains) == true
        }
    }

    private fun projectRelativePath(projectDir: Path, file: Path): String =
        projectDir.toAbsolutePath().normalize()
            .relativize(file.toAbsolutePath().normalize())
            .toString()
            .replace('\\', '/')

    private fun sha256(source: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private val optionalReadTerminals = mapOf(
        "ifPresent" to 1,
        "orElse" to 1,
        "orElseGet" to 1,
        "orElseThrow" to 1,
        "get" to 0,
        "resolve" to 0,
        "isPresent" to 0
    )

    private val optionalReadTransforms = mapOf(
        "map" to 1,
        "filter" to 1,
        "cast" to 0
    )

    private fun consumerClosureProven(
        call: MethodCallExpr,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        compatPackage: String
    ): Boolean {
        if (safeOptionalRead(call)) return true
        val root = transparentValue(call)
        if (root.parentNode.orElse(null) is ExpressionStmt) return true

        val variable = root.parentNode.orElse(null) as? VariableDeclarator
        if (variable?.initializer?.orElse(null) === root) {
            return localOptionalConsumersProven(variable, parsed, index)
        }

        val assignment = root.parentNode.orElse(null) as? AssignExpr
        if (assignment?.value === root) {
            val field = resolvedField(assignment.target, parsed, index, values) ?: return false
            return fieldOptionalConsumersProven(field, index, compatPackage)
        }

        return optionalMapConsumerProven(call, parsed, index)
    }

    private fun transparentValue(expression: Expression): Expression {
        var current = expression
        while (true) {
            val parent = current.parentNode.orElse(null)
            current = when {
                parent is EnclosedExpr && parent.inner === current -> parent
                parent is ConditionalExpr &&
                    (parent.thenExpr === current || parent.elseExpr === current) -> parent
                else -> return current
            }
        }
    }

    private fun enclosedValue(expression: Expression): Expression {
        var current = expression
        while (true) {
            val parent = current.parentNode.orElse(null) as? EnclosedExpr ?: return current
            if (parent.inner !== current) return current
            current = parent
        }
    }

    private fun safeOptionalRead(expression: Expression): Boolean {
        var current = enclosedValue(expression)
        while (true) {
            val consumer = current.parentNode.orElse(null) as? MethodCallExpr ?: return false
            if (consumer.scope.orElse(null) !== current) return false
            val terminalArity = optionalReadTerminals[consumer.nameAsString]
            if (terminalArity != null && consumer.arguments.size == terminalArity) return true
            val transformArity = optionalReadTransforms[consumer.nameAsString]
            if (transformArity == null || consumer.arguments.size != transformArity) return false
            current = enclosedValue(consumer)
        }
    }

    private fun localOptionalConsumersProven(
        variable: VariableDeclarator,
        parsed: Parsed,
        index: Index
    ): Boolean {
        val values = ValueTypes(parsed, index)
        return parsed.unit.findAll(NameExpr::class.java)
            .filter { it.nameAsString == variable.nameAsString }
            .filter {
                val binding = values.bindingOf(it.nameAsString, it)
                binding?.variable === variable
            }
            .all(::safeOptionalRead)
    }

    private fun optionalMapConsumerProven(
        query: MethodCallExpr,
        parsed: Parsed,
        index: Index
    ): Boolean {
        val producer = query.findAncestor(LambdaExpr::class.java).orElse(null) ?: return false
        if (!lambdaReturnsExactly(producer, query)) return false
        val producerNode = enclosedNode(producer)
        val firstMap = producerNode.parentNode.orElse(null) as? MethodCallExpr ?: return false
        if (firstMap.nameAsString != "map" ||
            firstMap.arguments.size != 1 ||
            firstMap.arguments[0] !== producerNode
        ) {
            return false
        }
        val values = ValueTypes(parsed, index)
        if (firstMap.scope.orElse(null)?.let(values::typeOf)?.raw != OPTIONAL) return false

        val firstMapNode = enclosedValue(firstMap)
        val secondMap = firstMapNode.parentNode.orElse(null) as? MethodCallExpr ?: return false
        if (secondMap.nameAsString != "map" ||
            secondMap.scope.orElse(null) !== firstMapNode ||
            secondMap.arguments.size != 1
        ) {
            return false
        }
        val consumer = unwrapEnclosed(secondMap.arguments[0]) as? LambdaExpr ?: return false
        val parameter = consumer.parameters.singleOrNull() ?: return false
        return consumer.findAll(NameExpr::class.java)
            .filter {
                it.nameAsString == parameter.nameAsString &&
                    it.findAncestor(LambdaExpr::class.java).orElse(null) === consumer
            }
            .all(::safeOptionalRead)
    }

    private fun lambdaReturnsExactly(lambda: LambdaExpr, expression: Expression): Boolean {
        val body = lambda.body
        if (body.isExpressionStmt) {
            return transparentValue(expression) === body.asExpressionStmt().expression
        }
        if (!body.isBlockStmt) return false
        val statement = body.asBlockStmt().statements.singleOrNull() as? ReturnStmt ?: return false
        return statement.expression.orElse(null) === transparentValue(expression)
    }

    private fun enclosedNode(node: Node): Node {
        var current = node
        while (true) {
            val parent = current.parentNode.orElse(null) as? EnclosedExpr ?: return current
            if (parent.inner !== current) return current
            current = parent
        }
    }

    private fun unwrapEnclosed(expression: Expression): Expression {
        var current = expression
        while (current is EnclosedExpr) current = current.inner
        return current
    }

    private fun fieldOptionalConsumersProven(
        field: FieldShape,
        index: Index,
        compatPackage: String
    ): Boolean {
        if (!fieldStartsEmpty(field, index, compatPackage)) return false

        for (unit in index.sourceUnits) {
            val values = ValueTypes(unit, index)
            for (assignment in unit.unit.findAll(AssignExpr::class.java)) {
                if (!sameField(resolvedField(assignment.target, unit, index, values), field)) continue
                if (!fieldValueProven(
                        assignment.value,
                        field,
                        unit,
                        index,
                        values,
                        compatPackage
                    )
                ) {
                    return false
                }
            }
            val references = buildList<Expression> {
                unit.unit.findAll(NameExpr::class.java)
                    .filter { it.nameAsString == field.declaration.nameAsString }
                    .filterTo(this) {
                        sameField(values.bindingOf(it.nameAsString, it)?.field, field)
                    }
                unit.unit.findAll(FieldAccessExpr::class.java)
                    .filter { it.nameAsString == field.declaration.nameAsString }
                    .filterTo(this) {
                        sameField(resolvedField(it, unit, index, values), field)
                    }
            }
            for (reference in references) {
                val parent = reference.parentNode.orElse(null)
                if (parent is AssignExpr && parent.target === reference) continue
                if (safeOptionalRead(reference)) continue
                if (fieldResetCallProven(reference, field, unit, index, values, compatPackage)) {
                    continue
                }
                return false
            }
        }
        return true
    }

    private fun fieldStartsEmpty(
        field: FieldShape,
        index: Index,
        compatPackage: String
    ): Boolean {
        val values = ValueTypes(field.parsed, index)
        field.declaration.initializer.orElse(null)?.let {
            return isLazyOptionalEmpty(it, field.parsed, index, values, compatPackage)
        }
        val owner = index.declarations[field.owner] ?: return false
        val constructors = owner.declaration.constructors
        if (constructors.isEmpty()) return false
        return constructors.all { constructor ->
            val firstReference = buildList<Expression> {
                constructor.findAll(NameExpr::class.java)
                    .filter { it.nameAsString == field.declaration.nameAsString }
                    .filterTo(this) {
                        sameField(values.bindingOf(it.nameAsString, it)?.field, field)
                    }
                constructor.findAll(FieldAccessExpr::class.java)
                    .filter { it.nameAsString == field.declaration.nameAsString }
                    .filterTo(this) {
                        sameField(resolvedField(it, field.parsed, index, values), field)
                    }
            }.minByOrNull(field.parsed::start) ?: return@all false
            val assignment = firstReference.parentNode.orElse(null) as? AssignExpr
                ?: return@all false
            assignment.target === firstReference &&
                isLazyOptionalEmpty(
                    assignment.value,
                    field.parsed,
                    index,
                    values,
                    compatPackage
                )
        }
    }

    private fun fieldValueProven(
        expression: Expression,
        field: FieldShape,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        compatPackage: String
    ): Boolean = when (val value = unwrapEnclosed(expression)) {
        is ConditionalExpr ->
            fieldValueProven(value.thenExpr, field, parsed, index, values, compatPackage) &&
                fieldValueProven(value.elseExpr, field, parsed, index, values, compatPackage)
        is MethodCallExpr -> {
            if (isLazyOptionalEmpty(value, parsed, index, values, compatPackage)) {
                true
            } else {
                val receiver = value.scope.orElse(null)?.let(values::typeOf)
                value.nameAsString == "getCapability" &&
                    value.arguments.size in 1..2 &&
                    receiver != null &&
                    index.inherits(receiver, BLOCK_ENTITY) &&
                    !index.declaresLegacyQuery(receiver) &&
                    capabilityKeys(
                        value.arguments[0],
                        value,
                        parsed,
                        index,
                        values,
                        compatPackage
                    ) != null
            }
        }
        else -> false
    }

    private fun fieldResetCallProven(
        reference: Expression,
        field: FieldShape,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        compatPackage: String
    ): Boolean {
        val call = reference.parentNode.orElse(null) as? MethodCallExpr ?: return false
        val argumentIndex = call.arguments.indexOf(reference)
        if (argumentIndex < 0) return false
        val receiver = call.scope.orElse(null)?.let(values::typeOf) ?: values.thisType(call) ?: return false
        val methods = index.methods(receiver, call.nameAsString, call.arguments.size)
        if (methods.isEmpty()) return false
        return methods.all { method ->
            val parameter = method.declaration.parameters.getOrNull(argumentIndex) ?: return@all false
            val body = method.declaration.body.orElse(null) ?: return@all false
            val methodValues = ValueTypes(method.parsed, index)
            val parameterUsed = body.findAll(NameExpr::class.java).any {
                methodValues.bindingOf(it.nameAsString, it)?.parameter === parameter
            }
            if (parameterUsed) return@all false
            body.findAll(AssignExpr::class.java).any { assignment ->
                sameField(
                    resolvedField(assignment.target, method.parsed, index, methodValues),
                    field
                ) && isLazyOptionalEmpty(
                    assignment.value,
                    method.parsed,
                    index,
                    methodValues,
                    compatPackage
                )
            }
        }
    }

    private fun resolvedField(
        expression: Expression,
        parsed: Parsed,
        index: Index,
        values: ValueTypes
    ): FieldShape? = when (val target = unwrapEnclosed(expression)) {
        is NameExpr -> values.bindingOf(target.nameAsString, target)?.field
        is FieldAccessExpr -> {
            val receiver = if (target.scope is ThisExpr) {
                values.thisType(target)
            } else {
                values.typeOf(target.scope)
            }
            receiver?.let { index.fieldDeclaration(it, target.nameAsString) }
        }
        else -> null
    }

    private fun sameField(left: FieldShape?, right: FieldShape): Boolean =
        left != null &&
            left.owner == right.owner &&
            left.declaration === right.declaration

    private fun isLazyOptionalEmpty(
        expression: Expression,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        compatPackage: String
    ): Boolean {
        val call = unwrapEnclosed(expression) as? MethodCallExpr ?: return false
        if (call.nameAsString != "empty" || call.arguments.isNotEmpty()) return false
        val scope = call.scope.orElse(null) ?: return false
        return staticOwner(
            parsed.text(scope),
            call,
            parsed,
            index,
            values,
            "$compatPackage.LazyOptional"
        )
    }

    private fun capabilityKeys(
        expression: Expression,
        call: MethodCallExpr,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        compatPackage: String
    ): Set<Key>? {
        directKey(expression, parsed, index, values, compatPackage)?.let { return setOf(it) }
        if (expression is MethodCallExpr) {
            return methodFamilyKeys(expression, parsed, index, values, compatPackage)
        }
        val name = expression as? NameExpr ?: return null
        val binding = values.bindingOf(name.nameAsString, name) ?: return null
        val capability = "$compatPackage.Capability"
        if (binding.type?.raw != capability) return null
        binding.variable?.let { variable ->
            if (reassigned(variable, call, name.nameAsString, parsed)) return null
            val initializer = variable.initializer.orElse(null) ?: return null
            directKey(initializer, parsed, index, values, compatPackage)?.let { return setOf(it) }
            if (initializer is MethodCallExpr) {
                return methodFamilyKeys(initializer, parsed, index, values, compatPackage)
            }
            return null
        }
        binding.parameter?.let { parameter ->
            return guardedParameterKey(parameter, call, parsed, index, values, compatPackage)
                ?.let(::setOf)
        }
        return null
    }

    private fun methodFamilyKeys(
        call: MethodCallExpr,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        compatPackage: String
    ): Set<Key>? {
        val receiver = call.scope.orElse(null)?.let(values::typeOf) ?: values.thisType(call) ?: return null
        val capability = "$compatPackage.Capability"
        val base = index.methods(receiver, call.nameAsString, call.arguments.size)
        if (base.isEmpty() || base.any { it.returnType.raw != capability }) return null
        val concrete = index.descendantsOf(receiver.raw).filter { !it.abstract }
        if (concrete.isEmpty()) return null
        val keys = linkedSetOf<Key>()
        for (type in concrete) {
            val implementations = index.methods(
                JType(type.canonical),
                call.nameAsString,
                call.arguments.size
            )
            if (implementations.size != 1) return null
            val implementation = implementations.single()
            val body = implementation.declaration.body.orElse(null) ?: return null
            val returned = body.statements.singleOrNull() as? ReturnStmt ?: return null
            val expression = returned.expression.orElse(null) ?: return null
            val implementationValues = ValueTypes(implementation.parsed, index)
            val key = directKey(
                expression,
                implementation.parsed,
                index,
                implementationValues,
                compatPackage
            ) ?: return null
            keys += key
        }
        return keys.takeIf { it.isNotEmpty() }
    }

    private fun guardedParameterKey(
        parameter: Parameter,
        call: MethodCallExpr,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        compatPackage: String
    ): Key? {
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
        val body = method.body.orElse(null) ?: return null
        val callStart = parsed.start(call)
        val name = parameter.nameAsString
        for (statement in body.statements) {
            if (parsed.start(statement) >= callStart) break
            val guard = statement as? IfStmt ?: continue
            if (guard.elseStmt.isPresent || !definitelyExits(guard.thenStmt)) continue
            val condition = guard.condition as? BinaryExpr ?: continue
            if (condition.operator != BinaryExpr.Operator.NOT_EQUALS) continue
            val keyExpression = when {
                condition.left is NameExpr &&
                    condition.left.asNameExpr().nameAsString == name -> condition.right
                condition.right is NameExpr &&
                    condition.right.asNameExpr().nameAsString == name -> condition.left
                else -> null
            } ?: continue
            val key = directKey(keyExpression, parsed, index, values, compatPackage) ?: continue
            val guardEnd = parsed.endExclusive(guard)
            val reassigned = body.findAll(AssignExpr::class.java).any {
                parsed.start(it) in guardEnd until callStart &&
                    it.target is NameExpr &&
                    it.target.asNameExpr().nameAsString == name
            }
            if (!reassigned) return key
        }
        return null
    }

    private fun definitelyExits(statement: Statement): Boolean = when (statement) {
        is ReturnStmt, is ThrowStmt -> true
        is BlockStmt -> statement.statements.lastOrNull()?.let(::definitelyExits) == true
        else -> false
    }

    private fun reassigned(
        variable: VariableDeclarator,
        use: Node,
        name: String,
        parsed: Parsed
    ): Boolean {
        val start = parsed.endExclusive(variable)
        val end = parsed.start(use)
        return parsed.unit.findAll(AssignExpr::class.java).any {
            parsed.start(it) in start until end &&
                it.target is NameExpr &&
                it.target.asNameExpr().nameAsString == name
        }
    }

    private fun directKey(
        expression: Expression,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        compatPackage: String
    ): Key? =
        legacyKey(expression, parsed, index, values, compatPackage)
            ?: nativeKey(expression, parsed, index, values)

    private fun legacyKey(
        expression: Expression,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        compatPackage: String
    ): Key? {
        if (expression !is FieldAccessExpr) return null
        val compact = parsed.text(expression).replace(Regex("""\s+"""), "")
        for (key in Key.entries) {
            val suffix = ".${key.constant}"
            if (!compact.endsWith(suffix)) continue
            val owner = compact.removeSuffix(suffix)
            if (!staticOwner(owner, expression, parsed, index, values, "$compatPackage.LegacyCapabilities")) {
                continue
            }
            return key
        }
        return null
    }

    private fun nativeKey(
        expression: Expression,
        parsed: Parsed,
        index: Index,
        values: ValueTypes
    ): Key? {
        if (expression !is FieldAccessExpr) return null
        val compact = parsed.text(expression).replace(Regex("""\s+"""), "")
        for (key in Key.entries) {
            val suffix = ".${key.holder}.BLOCK"
            if (!compact.endsWith(suffix)) continue
            val owner = compact.removeSuffix(suffix)
            if (!staticOwner(owner, expression, parsed, index, values, NATIVE_CAPABILITIES)) continue
            return key
        }
        return null
    }

    private fun staticOwner(
        ownerText: String,
        expression: Expression,
        parsed: Parsed,
        index: Index,
        values: ValueTypes,
        expected: String
    ): Boolean {
        val first = ownerText.substringBefore('.')
        if (first.firstOrNull()?.isUpperCase() == true && values.hasValueBinding(first, expression)) {
            return false
        }
        if (first.firstOrNull()?.isUpperCase() == true && lexicalTypeParameter(first, expression) != null) {
            return false
        }
        return parsed.resolveTypeName(ownerText, parsed.owner(expression), index) == expected
    }

    private fun parseAll(sources: Map<Path, String>): ParseSnapshot {
        val parsed = linkedMapOf<Path, Parsed>()
        val unparsed = linkedMapOf<Path, String>()
        for ((file, source) in sources) {
            val value = parse(file, source)
            if (value == null) unparsed[file] = source else parsed[file] = value
        }
        return ParseSnapshot(parsed, unparsed)
    }

    private fun parse(file: Path, source: String): Parsed? {
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
        return Parsed(
            file = file,
            source = source,
            unit = unit,
            packageName = unit.packageDeclaration.map { it.nameAsString }.orElse(""),
            imports = imports,
            wildcardImports = wildcards,
            lineStarts = lineStarts
        )
    }

    private fun buildIndex(units: Collection<Parsed>, compatPackage: String): Index {
        val declarationsByName = units.flatMap { parsed ->
            parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)
                .mapNotNull { declaration ->
                    declaration.fullyQualifiedName.orElse(null)?.let { it to declaration }
                }
        }.toMap()
        val provisional = Index(
            declarationsByName.mapValues { (name, declaration) ->
                Decl(
                    canonical = name,
                    declaration = declaration,
                    typeParameters = emptyList(),
                    parents = emptyList(),
                    fields = emptyMap(),
                    methods = emptyMap(),
                    abstract = true
                )
            },
            compatPackage,
            units
        )
        val declarations = linkedMapOf<String, Decl>()
        for (parsed in units) {
            for (type in parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
                val canonical = type.fullyQualifiedName.orElse(null) ?: continue
                val owner = canonical.substringBeforeLast('.', "")
                val typeVariables = type.typeParameters.map { it.nameAsString }.toSet()
                val parents = (type.extendedTypes + type.implementedTypes).mapNotNull {
                    parsed.resolveDeclarationType(it, owner, provisional, typeVariables)
                }
                val fields = type.fields.flatMap { field ->
                    field.variables.map { variable ->
                        variable.nameAsString to
                            parsed.resolveDeclarationType(
                                variable.type,
                                canonical,
                                provisional,
                                typeVariables
                            )?.let {
                                FieldShape(canonical, parsed, variable, it)
                            }
                    }
                }.mapNotNull { (name, value) -> value?.let { name to it } }.toMap()
                val methods = type.methods.groupBy { it.nameAsString to it.parameters.size }
                    .mapValues { (_, methods) ->
                        methods.mapNotNull { method ->
                            val variables = typeVariables + method.typeParameters.map { it.nameAsString }
                            val returnType = if (method.type.isVoidType) {
                                JType("void")
                            } else {
                                parsed.resolveDeclarationType(
                                    method.type,
                                    canonical,
                                    provisional,
                                    variables
                                ) ?: return@mapNotNull null
                            }
                            val parameters = method.parameters.map {
                                parsed.resolveDeclarationType(it.type, canonical, provisional, variables)
                                    ?: return@mapNotNull null
                            }
                            MethodShape(canonical, parsed, method, returnType, parameters)
                        }
                    }
                declarations[canonical] = Decl(
                    canonical = canonical,
                    declaration = type,
                    typeParameters = type.typeParameters.map { it.nameAsString },
                    parents = parents,
                    fields = fields,
                    methods = methods,
                    abstract = type.isInterface || type.isAbstract
                )
            }
        }
        return Index(declarations, compatPackage, units)
    }

    private fun Parsed.resolveDeclarationType(
        type: Type,
        owner: String?,
        index: Index,
        typeVariables: Set<String>
    ): JType? = resolveType(type, owner, index, typeVariables, null, mutableSetOf())

    private fun Parsed.resolveValueType(
        type: Type,
        use: Node,
        index: Index
    ): JType? = resolveType(type, owner(use), index, emptySet(), use, mutableSetOf())

    private fun Parsed.resolveType(
        type: Type,
        owner: String?,
        index: Index,
        declarationVariables: Set<String>,
        use: Node?,
        resolvingVariables: MutableSet<String>
    ): JType? {
        if (type is WildcardType) {
            type.extendedType.orElse(null)?.let {
                return resolveType(
                    it,
                    owner,
                    index,
                    declarationVariables,
                    use,
                    resolvingVariables
                )
                    ?.copy(variance = Variance.EXTENDS)
            }
            type.superType.orElse(null)?.let {
                return resolveType(
                    it,
                    owner,
                    index,
                    declarationVariables,
                    use,
                    resolvingVariables
                )
                    ?.copy(variance = Variance.SUPER)
            }
            return JType("java.lang.Object", variance = Variance.UNBOUNDED)
        }
        val classType = type as? ClassOrInterfaceType ?: return null
        val name = classType.nameWithScope
        if (name in declarationVariables) return JType(name, variable = true)
        if (use != null) {
            lexicalTypeParameter(name, use)?.let { parameter ->
                val bound = parameter.typeBound.firstOrNull() ?: return JType("java.lang.Object")
                val key = parsedTypeParameterKey(parameter)
                if (!resolvingVariables.add(key)) return null
                return try {
                    resolveType(bound, owner, index, emptySet(), use, resolvingVariables)
                } finally {
                    resolvingVariables.remove(key)
                }
            }
        }
        val raw = resolveTypeName(name, owner, index) ?: return null
        val arguments = classType.typeArguments.map { it.toList() }.orElse(emptyList())
            .map {
                resolveType(
                    it,
                    owner,
                    index,
                    declarationVariables,
                    use,
                    resolvingVariables
                ) ?: return null
            }
        return JType(raw, arguments)
    }

    private fun Parsed.parsedTypeParameterKey(parameter: TypeParameter): String =
        "${owner(parameter).orEmpty()}:${start(parameter)}:${endExclusive(parameter)}:" +
            parameter.nameAsString

    private fun Parsed.resolveTypeName(
        name: String,
        owner: String?,
        index: Index
    ): String? {
        val first = name.substringBefore('.')
        val lexical = index.lexical(name, packageName, owner)
        val samePackage = index.samePackage(name, packageName)
        val inheritedMember = inheritedMemberTypeName(name, owner, index)
        return when {
            lexical != null -> lexical
            first in imports -> imports.getValue(first) + name.removePrefix(first)
            samePackage != null -> samePackage
            inheritedMember != null -> inheritedMember
            name.firstOrNull()?.isLowerCase() == true -> name
            name == "String" -> "java.lang.String"
            name == "Object" -> "java.lang.Object"
            name == "Class" -> CLASS
            else -> wildcardImports.map { "$it.$name" }
                .filter(index::known)
                .distinct()
                .singleOrNull()
        }
    }

    /**
     * Java inherits accessible member types. Create relies on that rule when
     * `CreateSceneBuilder.WorldInstructions` extends the unqualified
     * `PonderWorldInstructions` member of its `PonderSceneBuilder` superclass.
     */
    private fun Parsed.inheritedMemberTypeName(
        name: String,
        owner: String?,
        index: Index
    ): String? {
        if (owner == null || '.' in name) return null
        val candidates = linkedSetOf<String>()
        var current: String = owner
        while (current.isNotBlank() && current != packageName) {
            val declaration = index.declarations[current]?.declaration
            if (declaration != null) {
                val declarationOwner = current.substringBeforeLast('.', "")
                (declaration.extendedTypes + declaration.implementedTypes)
                    .mapNotNull { parent ->
                        resolveTypeName(
                            parent.nameWithScope,
                            declarationOwner,
                            index
                        )
                    }
                    .mapTo(candidates) { parent -> "$parent.$name" }
            }
            current = current.substringBeforeLast('.', "")
        }
        return candidates.filter(index::known).distinct().singleOrNull()
    }

    private fun lexicalTypeParameter(name: String, use: Node): TypeParameter? {
        use.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?.typeParameters?.firstOrNull { it.nameAsString == name }
            ?.let { return it }
        use.findAncestor(ConstructorDeclaration::class.java).orElse(null)
            ?.typeParameters?.firstOrNull { it.nameAsString == name }
            ?.let { return it }
        var current = use.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
        while (current != null) {
            current.typeParameters.firstOrNull { it.nameAsString == name }?.let { return it }
            current = current.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
        }
        return null
    }

    private fun declarationVariables(node: Node): Set<String> {
        val result = linkedSetOf<String>()
        node.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?.typeParameters?.mapTo(result) { it.nameAsString }
        node.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?.typeParameters?.mapTo(result) { it.nameAsString }
        node.findAncestor(ConstructorDeclaration::class.java).orElse(null)
            ?.typeParameters?.mapTo(result) { it.nameAsString }
        return result
    }

    private fun range(node: Node, parsed: Parsed): IntRange? {
        val start = parsed.start(node)
        val end = parsed.endExclusive(node)
        return if (start >= 0 && end > start) start until end else null
    }

    private fun rangeWithTrailingLine(node: Node, parsed: Parsed): IntRange? {
        val base = range(node, parsed) ?: return null
        var end = base.last + 1
        while (end < parsed.source.length && parsed.source[end] in charArrayOf(' ', '\t')) end++
        if (parsed.source.getOrNull(end) == '\r') end++
        if (parsed.source.getOrNull(end) == '\n') end++
        return base.first until end
    }

    private fun nonOverlapping(edits: List<Edit>): Boolean {
        val sorted = edits.sortedBy { it.start }
        return sorted.zipWithNext().all { (left, right) -> left.endExclusive <= right.start }
    }

    private fun applyEdits(source: String, edits: List<Edit>): String {
        var result = source
        for (edit in edits.distinct().sortedByDescending { it.start }) {
            result = result.replaceRange(edit.start, edit.endExclusive, edit.replacement)
        }
        return result
    }

    private fun serverLevelMixinSource(compatPackage: String): String = """
        |package $compatPackage.mixin;
        |
        |import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
        |import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
        |import $compatPackage.LegacyBlockEntityCapabilityQueries;
        |import net.minecraft.core.BlockPos;
        |import net.minecraft.server.level.ServerLevel;
        |import net.minecraft.world.level.ChunkPos;
        |import org.spongepowered.asm.mixin.Mixin;
        |
        |@Mixin(ServerLevel.class)
        |public abstract class LegacyCapabilityServerLevelMixin {
        |    @WrapMethod(
        |        method = "invalidateCapabilities(Lnet/minecraft/core/BlockPos;)V",
        |        remap = false,
        |        require = 1,
        |        expect = 1
        |    )
        |    private void modporter${'$'}wrapPosition(BlockPos pos, Operation<Void> original) {
        |        LegacyBlockEntityCapabilityQueries.runInvalidation(
        |            (ServerLevel) (Object) this, pos, () -> original.call(pos)
        |        );
        |    }
        |
        |    @WrapMethod(
        |        method = "invalidateCapabilities(Lnet/minecraft/world/level/ChunkPos;)V",
        |        remap = false,
        |        require = 1,
        |        expect = 1
        |    )
        |    private void modporter${'$'}wrapChunk(ChunkPos pos, Operation<Void> original) {
        |        LegacyBlockEntityCapabilityQueries.runInvalidation(
        |            (ServerLevel) (Object) this, pos, () -> original.call(pos)
        |        );
        |    }
        |}
        |""".trimMargin()

    private fun levelExtensionMixinSource(compatPackage: String): String = """
        |package $compatPackage.mixin;
        |
        |import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
        |import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
        |import $compatPackage.LegacyBlockEntityCapabilityQueries;
        |import net.minecraft.core.BlockPos;
        |import net.minecraft.world.level.ChunkPos;
        |import net.minecraft.world.level.Level;
        |import net.neoforged.neoforge.common.extensions.ILevelExtension;
        |import org.spongepowered.asm.mixin.Mixin;
        |
        |@Mixin(value = ILevelExtension.class, remap = false)
        |public interface LegacyCapabilityLevelExtensionMixin {
        |    @WrapMethod(
        |        method = "invalidateCapabilities(Lnet/minecraft/core/BlockPos;)V",
        |        remap = false,
        |        require = 1,
        |        expect = 1
        |    )
        |    private void modporter${'$'}wrapPosition(BlockPos pos, Operation<Void> original) {
        |        LegacyBlockEntityCapabilityQueries.runInvalidation(
        |            (Level) (Object) this, pos, () -> original.call(pos)
        |        );
        |    }
        |
        |    @WrapMethod(
        |        method = "invalidateCapabilities(Lnet/minecraft/world/level/ChunkPos;)V",
        |        remap = false,
        |        require = 1,
        |        expect = 1
        |    )
        |    private void modporter${'$'}wrapChunk(ChunkPos pos, Operation<Void> original) {
        |        LegacyBlockEntityCapabilityQueries.runInvalidation(
        |            (Level) (Object) this, pos, () -> original.call(pos)
        |        );
        |    }
        |}
        |""".trimMargin()

    private fun retainedMixinConfigSource(compatPackage: String): String = """
        |{
        |  "required": true,
        |  "minVersion": "0.8",
        |  "package": "$compatPackage.mixin",
        |  "compatibilityLevel": "JAVA_21",
        |  "mixins": [
        |    "LegacyCapabilityServerLevelMixin",
        |    "LegacyCapabilityLevelExtensionMixin"
        |  ],
        |  "injectors": {
        |    "defaultRequire": 1
        |  }
        |}
        |""".trimMargin()

    private fun helperSource(compatPackage: String): String = """
        |package $compatPackage;
        |
        |import java.lang.ref.WeakReference;
        |import java.util.ArrayList;
        |import java.util.HashMap;
        |import java.util.List;
        |import java.util.Map;
        |import java.util.WeakHashMap;
        |import net.minecraft.core.BlockPos;
        |import net.minecraft.core.Direction;
        |import net.minecraft.world.level.ChunkPos;
        |import net.minecraft.world.level.Level;
        |import net.minecraft.world.level.block.entity.BlockEntity;
        |import net.neoforged.neoforge.capabilities.Capabilities;
        |
        |/**
        | * Adapts source-owned legacy capability queries to NeoForge level lookups. Retained
        | * wrappers are used only by the source-verified Create call sites admitted by the
        | * migration; arbitrary escaping calls remain hard errors.
        | */
        |public final class LegacyBlockEntityCapabilityQueries {
        |    private static final Map<BlockEntity, Map<QueryKey, WeakReference<LazyOptional<?>>>> RETAINED =
        |        new WeakHashMap<>();
        |    private static final ThreadLocal<InvalidationDispatch> INVALIDATIONS =
        |        ThreadLocal.withInitial(InvalidationDispatch::new);
        |
        |    private LegacyBlockEntityCapabilityQueries() {
        |    }
        |
        |    public static <T> LazyOptional<T> get(
        |        BlockEntity blockEntity, Capability<T> capability, Direction side
        |    ) {
        |        if (blockEntity instanceof LegacyCapabilityProvider provider) {
        |            return provider.getCapability(capability, side);
        |        }
        |        T nativeValue = nativeCapability(blockEntity, capability, side);
        |        return nativeValue == null ? LazyOptional.empty() : LazyOptional.ofNullable(nativeValue);
        |    }
        |
        |    public static <T> LazyOptional<T> getRetained(
        |        BlockEntity blockEntity, Capability<T> capability, Direction side
        |    ) {
        |        if (blockEntity instanceof LegacyCapabilityProvider provider) {
        |            return provider.getCapability(capability, side);
        |        }
        |        if (!isNativeKey(capability) || !isQueryable(blockEntity)) {
        |            return LazyOptional.empty();
        |        }
        |        synchronized (RETAINED) {
        |            Map<QueryKey, WeakReference<LazyOptional<?>>> wrappers = RETAINED.computeIfAbsent(
        |                blockEntity, ignored -> new HashMap<>()
        |            );
        |            QueryKey key = new QueryKey(capability, side);
        |            WeakReference<LazyOptional<?>> retained = wrappers.get(key);
        |            LazyOptional<?> existing = retained == null ? null : retained.get();
        |            if (existing != null && existing.isValid()) {
        |                return existing.cast();
        |            }
        |            if (retained != null) {
        |                wrappers.remove(key);
        |            }
        |            T nativeValue = nativeCapability(blockEntity, capability, side);
        |            if (nativeValue == null) {
        |                return LazyOptional.empty();
        |            }
        |            LazyOptional<T> created = LazyOptional.ofNullable(nativeValue);
        |            wrappers.put(key, new WeakReference<>(created));
        |            return created;
        |        }
        |    }
        |
        |    public static void runInvalidation(Level level, BlockPos pos, Runnable nativeInvalidation) {
        |        beginInvalidation(level, pos);
        |        runAndComplete(nativeInvalidation);
        |    }
        |
        |    public static void runInvalidation(Level level, ChunkPos pos, Runnable nativeInvalidation) {
        |        beginInvalidation(level, pos);
        |        runAndComplete(nativeInvalidation);
        |    }
        |
        |    private static void runAndComplete(Runnable nativeInvalidation) {
        |        Throwable nativeFailure = null;
        |        try {
        |            nativeInvalidation.run();
        |        } catch (RuntimeException | Error failure) {
        |            nativeFailure = failure;
        |            throw failure;
        |        } finally {
        |            try {
        |                completeInvalidation();
        |            } catch (RuntimeException | Error listenerFailure) {
        |                if (nativeFailure != null) {
        |                    nativeFailure.addSuppressed(listenerFailure);
        |                } else {
        |                    throw listenerFailure;
        |                }
        |            }
        |        }
        |    }
        |
        |    public static void beginInvalidation(Level level, BlockPos pos) {
        |        InvalidationDispatch dispatch = INVALIDATIONS.get();
        |        dispatch.depth++;
        |        dispatch.notifications.addAll(detach(level, pos, null));
        |    }
        |
        |    public static void beginInvalidation(Level level, ChunkPos chunkPos) {
        |        InvalidationDispatch dispatch = INVALIDATIONS.get();
        |        dispatch.depth++;
        |        dispatch.notifications.addAll(detach(level, null, chunkPos));
        |    }
        |
        |    public static void completeInvalidation() {
        |        InvalidationDispatch dispatch = INVALIDATIONS.get();
        |        if (dispatch.depth <= 0) {
        |            throw new IllegalStateException("Unbalanced capability invalidation bridge");
        |        }
        |        dispatch.depth--;
        |        if (dispatch.depth != 0) {
        |            return;
        |        }
        |        List<Runnable> notifications = List.copyOf(dispatch.notifications);
        |        dispatch.notifications.clear();
        |        INVALIDATIONS.remove();
        |        for (Runnable notification : notifications) {
        |            notification.run();
        |        }
        |    }
        |
        |    private static List<Runnable> detach(Level level, BlockPos pos, ChunkPos chunkPos) {
        |        List<Runnable> notifications = new ArrayList<>();
        |        synchronized (RETAINED) {
        |            var owners = RETAINED.entrySet().iterator();
        |            while (owners.hasNext()) {
        |                var owner = owners.next();
        |                BlockEntity blockEntity = owner.getKey();
        |                if (blockEntity == null) {
        |                    owners.remove();
        |                    continue;
        |                }
        |                if (blockEntity.getLevel() != level) {
        |                    continue;
        |                }
        |                BlockPos ownerPos = blockEntity.getBlockPos();
        |                boolean matches = pos != null
        |                    ? ownerPos.equals(pos)
        |                    : ChunkPos.asLong(ownerPos) == chunkPos.toLong();
        |                if (!matches) {
        |                    continue;
        |                }
        |                for (WeakReference<LazyOptional<?>> retained : owner.getValue().values()) {
        |                    LazyOptional<?> wrapper = retained.get();
        |                    if (wrapper != null) {
        |                        Runnable notification = wrapper.invalidateDeferred();
        |                        if (notification != null) {
        |                            notifications.add(notification);
        |                        }
        |                    }
        |                }
        |                owners.remove();
        |            }
        |        }
        |        return notifications;
        |    }
        |
        |    public static <T> void invalidate(
        |        BlockEntity blockEntity, Capability<T> capability, Direction side
        |    ) {
        |        if (blockEntity instanceof LegacyCapabilityProvider provider) {
        |            provider.getCapability(capability, side).invalidate();
        |            return;
        |        }
        |        if (isNativeKey(capability)) {
        |            blockEntity.invalidateCapabilities();
        |        }
        |    }
        |
        |    @SuppressWarnings("unchecked")
        |    private static <T> T nativeCapability(
        |        BlockEntity blockEntity, Capability<T> capability, Direction side
        |    ) {
        |        Level level = blockEntity.getLevel();
        |        if (!isQueryable(blockEntity, level)) {
        |            return null;
        |        }
        |        if (same(capability, LegacyCapabilities.ITEM_HANDLER)) {
        |            return (T) level.getCapability(
        |                Capabilities.ItemHandler.BLOCK,
        |                blockEntity.getBlockPos(),
        |                blockEntity.getBlockState(),
        |                blockEntity,
        |                side
        |            );
        |        }
        |        if (same(capability, LegacyCapabilities.FLUID_HANDLER)) {
        |            return (T) level.getCapability(
        |                Capabilities.FluidHandler.BLOCK,
        |                blockEntity.getBlockPos(),
        |                blockEntity.getBlockState(),
        |                blockEntity,
        |                side
        |            );
        |        }
        |        if (same(capability, LegacyCapabilities.ENERGY)) {
        |            return (T) level.getCapability(
        |                Capabilities.EnergyStorage.BLOCK,
        |                blockEntity.getBlockPos(),
        |                blockEntity.getBlockState(),
        |                blockEntity,
        |                side
        |            );
        |        }
        |        return null;
        |    }
        |
        |    private static boolean isQueryable(BlockEntity blockEntity) {
        |        return isQueryable(blockEntity, blockEntity.getLevel());
        |    }
        |
        |    private static boolean isQueryable(BlockEntity blockEntity, Level level) {
        |        return level != null && !blockEntity.isRemoved() &&
        |            level.getBlockEntity(blockEntity.getBlockPos()) == blockEntity;
        |    }
        |
        |    private static boolean isNativeKey(Capability<?> capability) {
        |        return same(capability, LegacyCapabilities.ITEM_HANDLER)
        |            || same(capability, LegacyCapabilities.FLUID_HANDLER)
        |            || same(capability, LegacyCapabilities.ENERGY);
        |    }
        |
        |    private static boolean same(Capability<?> left, Capability<?> right) {
        |        return left == right;
        |    }
        |
        |    private static final class QueryKey {
        |        private final Capability<?> capability;
        |        private final Direction side;
        |
        |        private QueryKey(Capability<?> capability, Direction side) {
        |            this.capability = capability;
        |            this.side = side;
        |        }
        |
        |        @Override
        |        public boolean equals(Object other) {
        |            return other instanceof QueryKey key
        |                && capability == key.capability
        |                && side == key.side;
        |        }
        |
        |        @Override
        |        public int hashCode() {
        |            return 31 * System.identityHashCode(capability) + System.identityHashCode(side);
        |        }
        |    }
        |
        |    private static final class InvalidationDispatch {
        |        private int depth;
        |        private final List<Runnable> notifications = new ArrayList<>();
        |    }
        |}
        |""".trimMargin()
}
