package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.CharLiteralExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.DoubleLiteralExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.LongLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.stmt.TryStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.ast.type.WildcardType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Migrates source views built from the old process-global PotionBrewing lists to the immutable
 * PotionBrewing instance owned by the current server or client connection.
 *
 * The rule is deliberately closed:
 * - exact platform symbols seed the graph;
 * - private project methods and static-final field initializers are followed by declaration;
 * - every use of a converted field or widened method must have a proved provider;
 * - all source changes and access-transformer additions are staged in memory before any write.
 *
 * Values are cached per PotionBrewing key in WeakHashMaps. A live Level/server/connection owns the
 * key; the cache does not retain a Level, server, connection, or world.
 */
internal object LegacyPotionBrewingInstances {
    const val RULE_ID = "struct-legacy-potion-brewing-instances"

    private const val POTION_BREWING = "net.minecraft.world.item.alchemy.PotionBrewing"
    private const val POTION_BREWING_MIX =
        "net.minecraft.world.item.alchemy.PotionBrewing.Mix"
    private const val HOLDER = "net.minecraft.core.Holder"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val MOB_EFFECT_INSTANCE = "net.minecraft.world.effect.MobEffectInstance"
    private const val DATA_COMPONENTS = "net.minecraft.core.component.DataComponents"
    private const val DATA_COMPONENT_TYPE = "net.minecraft.core.component.DataComponentType"
    private const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    private const val CUSTOM_DATA_TYPE = "net.minecraft.world.item.component.CustomData"
    private const val ITEMS = "net.minecraft.world.item.Items"
    private const val ITEM = "net.minecraft.world.item.Item"
    private const val POTION = "net.minecraft.world.item.alchemy.Potion"
    private const val INGREDIENT = "net.minecraft.world.item.crafting.Ingredient"
    private const val BREWING_REGISTRY = "net.neoforged.neoforge.common.brewing.BrewingRecipeRegistry"
    private const val LEVEL = "net.minecraft.world.level.Level"
    private const val SERVER_LEVEL = "net.minecraft.server.level.ServerLevel"
    private const val MINECRAFT_SERVER = "net.minecraft.server.MinecraftServer"
    private const val CLIENT_PACKET_LISTENER = "net.minecraft.client.multiplayer.ClientPacketListener"
    private const val BLOCK_ENTITY = "net.minecraft.world.level.block.entity.BlockEntity"
    private const val JEI_PLUGIN = "mezz.jei.api.JeiPlugin"
    private const val JEI_MOD_PLUGIN = "mezz.jei.api.IModPlugin"
    private const val JEI_RECIPE_REGISTRATION =
        "mezz.jei.api.registration.IRecipeRegistration"
    private const val JEI_CATEGORY_REGISTRATION =
        "mezz.jei.api.registration.IRecipeCategoryRegistration"
    private const val SUPPLIER = "java.util.function.Supplier"
    private const val CONSUMER = "java.util.function.Consumer"
    private const val CACHE_TEMPLATE = "/compat/LegacyPotionBrewingInstanceCache.java.template"
    private const val POTION_UTILS_TEMPLATE = "/compat/LegacyPotionUtils.java.template"
    private const val PROVIDER_BASE = "modporterPotionBrewing"

    private val legacyLists = setOf("POTION_MIXES", "CONTAINER_MIXES")
    private val legacyFields = legacyLists + "ALLOWED_CONTAINER"
    private val cacheValueTypes = setOf(
        "java.lang.Iterable",
        "java.util.Collection",
        "java.util.List",
        "java.util.Set",
        "java.util.Map",
        "java.util.ArrayList",
        "java.util.LinkedList",
        "java.util.HashSet",
        "java.util.LinkedHashSet",
        "java.util.HashMap",
        "java.util.LinkedHashMap",
        "com.google.common.collect.ImmutableList",
        "com.google.common.collect.ImmutableSet",
        "com.google.common.collect.ImmutableMap",
        "net.minecraft.core.NonNullList"
    )
    private val forbiddenCacheTypes = setOf(
        POTION_BREWING, LEVEL, SERVER_LEVEL, MINECRAFT_SERVER, CLIENT_PACKET_LISTENER
    )
    private val forbiddenRetentionTypes = forbiddenCacheTypes + BLOCK_ENTITY
    private val retainedPlatformDataTypes = setOf(
        "java.lang.Boolean",
        "java.lang.Byte",
        "java.lang.Character",
        "java.lang.Double",
        "java.lang.Float",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Short",
        "java.lang.String",
        "java.lang.Void",
        DATA_COMPONENT_TYPE,
        COMPOUND_TAG,
        "net.minecraft.nbt.ListTag",
        "net.minecraft.nbt.Tag",
        "net.minecraft.resources.ResourceLocation",
        "net.minecraft.tags.TagKey",
        MOB_EFFECT_INSTANCE,
        "net.minecraft.world.item.Item",
        ITEM_STACK,
        "net.minecraft.world.item.alchemy.Potion",
        POTION_BREWING_MIX,
        CUSTOM_DATA_TYPE,
        "net.minecraft.world.item.crafting.Ingredient",
        "net.minecraft.world.item.crafting.RecipeSerializer",
        "net.minecraft.world.item.crafting.RecipeType",
        "net.minecraft.world.level.ItemLike",
        "net.minecraft.world.level.material.Fluid",
        "net.neoforged.neoforge.fluids.FluidStack"
    )
    private val functionalTypes = setOf(
        SUPPLIER,
        CONSUMER,
        "java.util.function.BiConsumer",
        "java.util.function.Function",
        "java.util.function.BiFunction",
        "java.util.function.Predicate",
        "java.util.function.BiPredicate",
        "java.util.function.UnaryOperator",
        "java.util.function.BinaryOperator",
        "java.lang.Runnable",
        "java.util.concurrent.Callable"
    )
    private val implicitJavaLangTypes = setOf(
        "Boolean", "Byte", "Character", "Class", "Double", "Enum", "Float", "Integer",
        "Long", "Number", "Object", "Record", "Short", "String", "Throwable", "Void"
    )
    private val primitiveValueTypes = setOf(
        "boolean", "byte", "char", "double", "float", "int", "long", "short", "void"
    )

    private val potionMixesAt =
        "public net.minecraft.world.item.alchemy.PotionBrewing potionMixes"
    private val containerMixesAt =
        "public net.minecraft.world.item.alchemy.PotionBrewing containerMixes"
    private val isContainerAt =
        "public net.minecraft.world.item.alchemy.PotionBrewing isContainer(Lnet/minecraft/world/item/ItemStack;)Z"
    private val mixClassAt =
        "public net.minecraft.world.item.alchemy.PotionBrewing\$Mix"
    private val mixConstructorAt =
        "public net.minecraft.world.item.alchemy.PotionBrewing\$Mix " +
            "<init>(Lnet/minecraft/core/Holder;Lnet/minecraft/world/item/crafting/Ingredient;" +
            "Lnet/minecraft/core/Holder;)V"

    private data class MethodKey(val owner: String, val name: String, val arity: Int)
    private data class FieldKey(val owner: String, val name: String)

    private data class UnitState(
        val file: Path,
        val original: String,
        val unit: com.github.javaparser.ast.CompilationUnit,
        val packageName: String,
        val masked: String,
        val lineStarts: List<Int>
    ) {
        lateinit var typing: SourceValueTypes

        fun offset(node: Node): Int = node.range.map {
            lineStarts[it.begin.line - 1] + it.begin.column - 1
        }.orElse(-1)

        fun owner(type: TypeDeclaration<*>): String {
            val names = mutableListOf<String>()
            var current: Node? = type
            while (current != null) {
                if (current is TypeDeclaration<*>) names += current.nameAsString
                current = current.parentNode.orElse(null)
            }
            val nested = names.asReversed().joinToString(".")
            return if (packageName.isBlank()) nested else "$packageName.$nested"
        }

        fun owner(node: Node): String? =
            node.findAncestor(TypeDeclaration::class.java).map(::owner).orElse(null)

        fun ownerChain(node: Node): List<String> {
            val result = mutableListOf<String>()
            var current: Node? = node
            while (current != null) {
                if (current is TypeDeclaration<*>) result += owner(current)
                current = current.parentNode.orElse(null)
            }
            return result.distinct()
        }
    }

    private data class MethodDef(
        val key: MethodKey,
        val state: UnitState,
        val node: MethodDeclaration
    )

    private data class FieldDef(
        val key: FieldKey,
        val state: UnitState,
        val declaration: FieldDeclaration,
        val variable: VariableDeclarator
    )

    private data class Context(
        val method: MethodDef? = null,
        val constructor: ConstructorDeclaration? = null,
        val field: FieldDef? = null
    )

    private data class MethodUse(
        val node: MethodCallExpr,
        val target: MethodDef,
        val state: UnitState,
        val context: Context
    )

    private data class FieldUse(
        val node: Expression,
        val target: FieldDef,
        val state: UnitState,
        val context: Context
    )

    private data class MethodReferenceUse(
        val node: MethodReferenceExpr,
        val state: UnitState,
        val owner: String?,
        val candidates: List<MethodDef>
    )

    private data class LegacyUseSummary(
        val directMethods: Set<MethodKey>,
        val needsPotionMixes: Boolean,
        val needsContainerMixes: Boolean,
        val needsContainerPredicate: Boolean
    )

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }
                .filter { path ->
                    // The walker already confines inputs to declared src/*/java roots.
                    // Checkout ancestors and Java package names are not build-output evidence.
                    val relative = projectDir.relativize(path).toString().replace('\\', '/')
                    !Regex("""^src/[^/]+/java/com/modporter/generated/""")
                        .containsMatchIn(relative)
                }
                .toList()
        }
        if (files.isEmpty()) return emptyList()

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val states = files.map { file ->
            val source = file.readText()
            val parsed = parser.parse(source)
            check(parsed.isSuccessful) {
                "Cannot prove the project-wide brewing graph because $file does not parse: " +
                    pass.parseProblems(parsed)
            }
            unitState(file, source, parsed.result.orElseThrow())
        }
        if (states.isEmpty()) return emptyList()

        // The rule is callable directly from focused tests as well as from the full pass.
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        states.forEach { it.typing = SourceValueTypes(pass, it.original) }

        val typeEntries = states.flatMap { state ->
            state.unit.findAll(TypeDeclaration::class.java).map { type -> state.owner(type) to type }
        }
        val ambiguousTypes = typeEntries.groupBy { it.first }
            .filterValues { declarations -> declarations.size > 1 }
            .keys
        check(ambiguousTypes.isEmpty()) {
            "Cannot prove brewing ownership for duplicate canonical source types: " +
                ambiguousTypes.sorted().joinToString()
        }
        val typesByName = typeEntries.toMap()
        val parents = pass.projectValueFacts.parents

        val methods = collectMethods(states)
        val methodsByKey = methods.groupBy { it.key }
        val fields = collectFields(states)
        val ambiguousFields = fields.groupBy { it.key }.filterValues { it.size > 1 }.keys
        check(ambiguousFields.isEmpty()) {
            "Cannot prove duplicate source fields: ${ambiguousFields.sortedBy { "${it.owner}#${it.name}" }}"
        }
        val fieldsByKey = fields.associateBy { it.key }

        val legacy = collectLegacyUses(
            states, methods, methodsByKey, typesByName
        )
        if (legacy.directMethods.isEmpty()) return emptyList()

        validateTargetSurface(legacy)

        val methodUses = collectMethodUses(
            states, methods, methodsByKey, fieldsByKey, typesByName, parents
        )
        val fieldUses = collectFieldUses(
            states, fieldsByKey, methodsByKey, typesByName, parents
        )
        val methodReferences = collectMethodReferences(
            states, methodsByKey, typesByName, parents
        )

        val dependentMethods = legacy.directMethods.toMutableSet()
        val dependentFields = linkedSetOf<FieldKey>()
        val providerNames = dependentMethods.associateWith { method ->
            uniqueName(methodsByKey.getValue(method).single().node, PROVIDER_BASE)
        }.toMutableMap()
        val groundedProviders = IdentityHashMap<Node, String?>()
        val providerFailures = linkedSetOf<String>()

        fun ground(state: UnitState, node: Node, context: Context): String? =
            if (groundedProviders.containsKey(node)) groundedProviders[node]
            else groundedProvider(
                state, node, context, states, typesByName, methodsByKey, fieldsByKey, parents
            ).also { groundedProviders[node] = it }

        fun consume(
            state: UnitState,
            node: Node,
            context: Context,
            reason: String
        ): Boolean {
            context.field?.let { field ->
                validateCacheField(field, typesByName, parents)
                return dependentFields.add(field.key)
            }
            context.method?.let { method ->
                if (method.key in dependentMethods || ground(state, node, context) != null) return false
                if (!threadable(method)) {
                    providerFailures +=
                        "Legacy brewing $reason reaches ${method.key.owner}#${method.key.name}, " +
                            "which is not a private source method and has no real PotionBrewing provider"
                    return false
                }
                if (dependentMethods.add(method.key)) {
                    providerNames[method.key] = uniqueName(method.node, PROVIDER_BASE)
                    return true
                }
                return false
            }
            context.constructor?.let {
                if (ground(state, node, context) == null) {
                    providerFailures +=
                        "Legacy brewing $reason reaches a constructor without a " +
                            "Level/server/client-connection provider in ${state.file}"
                }
                return false
            }
            error("Legacy brewing $reason occurs outside a proved field or callable in ${state.file}")
        }

        var changed: Boolean
        do {
            changed = false
            for (use in methodUses) {
                if (use.target.key in dependentMethods) {
                    changed = consume(use.state, use.node, use.context, "method call") || changed
                }
            }
            for (use in fieldUses) {
                if (use.target.key in dependentFields) {
                    changed = consume(use.state, use.node, use.context, "field read") || changed
                }
            }
        } while (changed)

        check(dependentFields.isNotEmpty()) {
            "Legacy PotionBrewing state is read only from methods that have no static-final source view to migrate"
        }
        ensureAcyclicDependencyGraph(
            dependentMethods, dependentFields, methodUses, fieldUses
        )
        check(providerFailures.isEmpty()) {
            providerFailures.first()
        }
        ensureClosedPrivateMethodUses(
            dependentMethods, methodsByKey, methodUses, methodReferences,
            states, typesByName, parents
        )
        ensureClosedFieldUses(
            dependentFields, fieldsByKey, fieldUses, states, typesByName, parents
        )
        validateCacheRetentionClosure(
            parser, sourceRoot, states, legacy.directMethods, dependentFields, methodsByKey,
            fieldsByKey, typesByName, parents
        )

        val accessorNames = dependentFields.associateWith { field ->
            val owner = fieldsByKey.getValue(field)
            uniqueMemberName(owner.state.original, "modporter\$get\$${field.name}")
        }
        val cacheNames = dependentFields.associateWith { field ->
            val owner = fieldsByKey.getValue(field)
            uniqueMemberName(owner.state.original, "modporter\$cache\$${field.name}")
        }
        val fieldProviderNames = dependentFields.associateWith { field ->
            val owner = fieldsByKey.getValue(field)
            uniqueMemberName(
                owner.variable.initializer.orElseThrow().toString(),
                PROVIDER_BASE
            )
        }

        fun providerFor(state: UnitState, node: Node, context: Context): String {
            context.field?.takeIf { it.key in dependentFields }?.let {
                return fieldProviderNames.getValue(it.key)
            }
            context.method?.takeIf { it.key in dependentMethods }?.let {
                return providerNames.getValue(it.key)
            }
            return ground(state, node, context)
                ?: error("No proved PotionBrewing provider for ${state.file}:${state.offset(node)}")
        }

        // Nothing has been written yet. All graph and provider failures above leave the project intact.
        states.forEach { LexicalPreservingPrinter.setup(it.unit) }

        // Widen private factory/helper methods first. Stored call-use nodes still refer to the same AST.
        for (key in dependentMethods) {
            val method = methodsByKey.getValue(key).single().node
            method.addParameter(
                parser.parseParameter("$POTION_BREWING ${providerNames.getValue(key)}")
                    .result.orElseThrow()
            )
        }

        for (use in methodUses) {
            if (use.target.key !in dependentMethods) continue
            val provider = providerFor(use.state, use.node, use.context)
            use.node.addArgument(parser.parseExpression<Expression>(provider).result.orElseThrow())
        }

        for (use in fieldUses) {
            if (use.target.key !in dependentFields) continue
            val provider = providerFor(use.state, use.node, use.context)
            val call = "${use.target.key.owner}.${accessorNames.getValue(use.target.key)}($provider)"
            use.node.replace(parser.parseExpression<Expression>(call).result.orElseThrow())
        }
        states.forEach { state ->
            state.unit.imports.removeIf { imported ->
                imported.isStatic && !imported.isAsterisk &&
                    dependentFields.any { field ->
                        imported.nameAsString == "${field.owner}.${field.name}"
                    }
            }
        }

        rewriteExactLegacyState(
            parser, states, methodsByKey, legacy.directMethods, providerNames,
            typesByName
        )
        validateProviderUses(
            dependentMethods, dependentFields, methodsByKey, fieldsByKey,
            methodUses, providerNames, fieldProviderNames, accessorNames
        )

        val template = javaClass.getResource(CACHE_TEMPLATE)?.readText()
            ?: error("Missing $CACHE_TEMPLATE")
        for (field in dependentFields.map(fieldsByKey::getValue)
            .sortedByDescending { it.state.offset(it.declaration) }
        ) {
            val owner = field.declaration.parentNode.orElse(null) as? TypeDeclaration<*>
                ?: error("Converted field is not a direct type member: ${field.key}")
            val initializer = field.variable.initializer.orElseThrow().toString()
            val type = field.variable.type.toString()
            val visibility = when {
                field.declaration.isPublic -> "public"
                field.declaration.isProtected -> "protected"
                field.declaration.isPrivate -> "private"
                else -> ""
            }
            val valueName = uniqueMemberName(
                field.state.original + initializer,
                "modporterValue"
            )
            val membersSource = template
                .replace("@TYPE@", type)
                .replace("@CACHE@", cacheNames.getValue(field.key))
                .replace("@ACCESSOR@", accessorNames.getValue(field.key))
                .replace("@PARAM@", fieldProviderNames.getValue(field.key))
                .replace("@VALUE@", valueName)
                .replace("@VISIBILITY@", visibility)
                .replace("@INITIALIZER@", initializer)
            val wrapper = parser.parse(membersSource).result.orElseThrow()
                .getClassByName("ModPorterPotionBrewingCacheMembers").orElseThrow()
            val insertion = owner.members.indexOf(field.declaration)
            check(insertion >= 0) { "Cannot locate converted field member ${field.key}" }
            field.declaration.remove()
            wrapper.members.forEachIndexed { index, member ->
                owner.members.add(insertion + index, member.clone())
            }
        }

        val migrated = states.associate { state ->
            var text = LexicalPreservingPrinter.print(state.unit)
            text = pass.removeUnusedSimpleImports(
                text,
                listOf(
                    "net.minecraftforge.common.brewing.BrewingRecipeRegistry",
                    "net.neoforged.neoforge.common.brewing.BrewingRecipeRegistry"
                )
            )
            check(parser.parse(text).isSuccessful) {
                "Legacy brewing migration emitted invalid Java in ${state.file}"
            }
            check(!containsLegacyStaticBrewingAccess(pass, text)) {
                "Legacy PotionBrewing static access survived in ${state.file}"
            }
            state.file to text
        }.filter { (file, text) -> text != states.single { it.file == file }.original }

        val requiredAt = buildList {
            if (legacy.needsPotionMixes) add(potionMixesAt)
            if (legacy.needsContainerMixes) add(containerMixesAt)
            if (legacy.needsContainerPredicate) add(isContainerAt)
            if (legacy.needsPotionMixes || legacy.needsContainerMixes) {
                add(mixClassAt)
                add(mixConstructorAt)
            }
        }
        val atChange = planAccessTransformerChange(projectDir, requiredAt)

        if (!dryRun) {
            migrated.forEach { (file, text) -> file.writeText(text) }
            atChange?.write()
        }

        val changes = migrated.map { (file, _) ->
            Change(
                file = file,
                line = 1,
                description = "Bind legacy brewing-derived source views to the current immutable PotionBrewing instance",
                before = "process-global PotionBrewing fields and eager static recipe views",
                after = "per-instance weak cache with proved Level/server/client-connection providers",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }.toMutableList()
        atChange?.let {
            changes += Change(
                file = it.file,
                line = 1,
                description = "Expose the target PotionBrewing instance members required by the legacy source view",
                before = "removed legacy static brewing access-transformer targets",
                after = requiredAt.joinToString(", "),
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
        return changes
    }

    private fun unitState(
        file: Path,
        source: String,
        unit: com.github.javaparser.ast.CompilationUnit
    ): UnitState {
        val starts = buildList {
            add(0)
            source.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
        return UnitState(
            file = file,
            original = source,
            unit = unit,
            packageName = unit.packageDeclaration.map { it.nameAsString }.orElse(""),
            masked = JavaSourceText.maskCommentsAndLiterals(source),
            lineStarts = starts
        )
    }

    private fun collectMethods(states: List<UnitState>): List<MethodDef> =
        states.flatMap { state ->
            state.unit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
                if (insideAnonymousClass(method)) return@mapNotNull null
                val owner = state.owner(method) ?: return@mapNotNull null
                MethodDef(
                    MethodKey(owner, method.nameAsString, method.parameters.size),
                    state,
                    method
                )
            }
        }

    private fun collectFields(states: List<UnitState>): List<FieldDef> =
        states.flatMap { state ->
            state.unit.findAll(FieldDeclaration::class.java).flatMap { declaration ->
                if (insideAnonymousClass(declaration)) return@flatMap emptyList()
                val owner = state.owner(declaration) ?: return@flatMap emptyList()
                declaration.variables.map { variable ->
                    FieldDef(FieldKey(owner, variable.nameAsString), state, declaration, variable)
                }
            }
        }

    private fun collectLegacyUses(
        states: List<UnitState>,
        methods: List<MethodDef>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>
    ): LegacyUseSummary {
        val methodByNode = methods.associateBy { it.node }
        val direct = linkedSetOf<MethodKey>()
        var potionMixes = false
        var containerMixes = false
        var containerPredicate = false

        for (state in states) {
            check(state.unit.imports.none {
                    it.isStatic && it.isAsterisk && it.nameAsString == POTION_BREWING
                }
            ) {
                "Wildcard PotionBrewing static import prevents exact legacy-field proof in ${state.file}"
            }
            for (field in state.unit.findAll(FieldAccessExpr::class.java)) {
                val owner = resolveType(
                    state, field.scope, state.offset(field), typesByName
                )
                if (owner != POTION_BREWING || field.nameAsString !in legacyFields) continue
                val method = field.findAncestor(MethodDeclaration::class.java)
                    .map(methodByNode::get).orElse(null)
                check(method != null) {
                    "Legacy ${field.nameAsString} is read outside a method in ${state.file}"
                }
                check(!insideAnonymousClass(method.node)) {
                    "Legacy ${field.nameAsString} is read from an anonymous-class method in ${state.file}"
                }
                check(methodsByKey[method.key]?.size == 1) {
                    "Ambiguous legacy brewing method declaration: ${method.key}"
                }
                direct += method.key
                when (field.nameAsString) {
                    "POTION_MIXES" -> potionMixes = true
                    "CONTAINER_MIXES" -> containerMixes = true
                    "ALLOWED_CONTAINER" -> containerPredicate = true
                }
            }
            for (name in state.unit.findAll(NameExpr::class.java)) {
                if (name.nameAsString !in legacyFields ||
                    !legacyStaticImported(state, name)
                ) continue
                val method = name.findAncestor(MethodDeclaration::class.java)
                    .map(methodByNode::get).orElse(null)
                check(method != null) {
                    "Static-imported legacy ${name.nameAsString} is read outside a method in ${state.file}"
                }
                check(!insideAnonymousClass(method.node)) {
                    "Static-imported legacy ${name.nameAsString} is read from an anonymous-class method in ${state.file}"
                }
                direct += method.key
                when (name.nameAsString) {
                    "POTION_MIXES" -> potionMixes = true
                    "CONTAINER_MIXES" -> containerMixes = true
                    "ALLOWED_CONTAINER" -> containerPredicate = true
                }
            }
            for (call in state.unit.findAll(MethodCallExpr::class.java)) {
                if (call.nameAsString != "getRecipes" || call.arguments.isNotEmpty()) continue
                val scope = call.scope.orElse(null) ?: continue
                if (resolveType(state, scope, state.offset(call), typesByName) != BREWING_REGISTRY) continue
                val method = call.findAncestor(MethodDeclaration::class.java)
                    .map(methodByNode::get).orElse(null)
                check(method != null) {
                    "Legacy BrewingRecipeRegistry.getRecipes() is called outside a method in ${state.file}"
                }
                check(!insideAnonymousClass(method.node)) {
                    "Legacy BrewingRecipeRegistry.getRecipes() is called from an anonymous-class method in ${state.file}"
                }
                check(methodsByKey[method.key]?.size == 1) {
                    "Ambiguous legacy brewing method declaration: ${method.key}"
                }
                direct += method.key
            }
        }
        direct.forEach { key ->
            val method = methodsByKey.getValue(key).single()
            check(threadable(method)) {
                "Direct legacy brewing method ${key.owner}#${key.name} must be private source code"
            }
        }
        return LegacyUseSummary(direct, potionMixes, containerMixes, containerPredicate)
    }

    private fun collectMethodUses(
        states: List<UnitState>,
        methods: List<MethodDef>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        fieldsByKey: Map<FieldKey, FieldDef>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): List<MethodUse> {
        val methodByNode = methods.associateBy { it.node }
        return states.flatMap { state ->
            state.unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
                val target = resolveProjectMethod(
                    state, call, methodsByKey, typesByName, parents
                ) ?: return@mapNotNull null
                MethodUse(
                    call, target, state,
                    contextOf(call, methodByNode, fieldsByKey)
                )
            }
        }
    }

    private fun collectFieldUses(
        states: List<UnitState>,
        fieldsByKey: Map<FieldKey, FieldDef>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): List<FieldUse> {
        val methodByNode = methodsByKey.values.flatten().associateBy { it.node }
        val result = mutableListOf<FieldUse>()
        for (state in states) {
            for (field in state.unit.findAll(FieldAccessExpr::class.java)) {
                val owner = resolveType(state, field.scope, state.offset(field), typesByName)
                    ?: continue
                val target = resolveProjectField(
                    owner, field.nameAsString, fieldsByKey, parents
                ) ?: continue
                result += FieldUse(
                    field, target, state,
                    contextOf(field, methodByNode, fieldsByKey)
                )
            }
            for (name in state.unit.findAll(NameExpr::class.java)) {
                val target = resolveUnqualifiedField(
                    state, name, fieldsByKey, parents
                ) ?: continue
                result += FieldUse(
                    name, target, state,
                    contextOf(name, methodByNode, fieldsByKey)
                )
            }
        }
        return result
    }

    private fun collectMethodReferences(
        states: List<UnitState>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): List<MethodReferenceUse> =
        states.flatMap { state ->
            state.unit.findAll(MethodReferenceExpr::class.java).map { reference ->
                val owner = resolveType(
                    state, reference.scope, state.offset(reference), typesByName
                )
                val candidates = owner?.let {
                    projectMethodsInHierarchy(
                        it, reference.identifier, null, methodsByKey, parents
                    )
                }.orEmpty()
                MethodReferenceUse(reference, state, owner, candidates)
            }
        }

    private fun contextOf(
        node: Node,
        methods: Map<MethodDeclaration, MethodDef>,
        fields: Map<FieldKey, FieldDef>
    ): Context {
        node.findAncestor(MethodDeclaration::class.java).map(methods::get).orElse(null)?.let {
            return Context(method = it)
        }
        node.findAncestor(ConstructorDeclaration::class.java).orElse(null)?.let {
            return Context(constructor = it)
        }
        val variable = node.findAncestor(VariableDeclarator::class.java).orElse(null)
        if (variable != null && variable.parentNode.orElse(null) is FieldDeclaration) {
            val stateOwner = fields.values.singleOrNull { it.variable === variable }
            if (stateOwner != null) return Context(field = stateOwner)
        }
        return Context()
    }

    private fun resolveProjectMethod(
        state: UnitState,
        call: MethodCallExpr,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): MethodDef? = resolveProjectMethod(
        state, call, methodsByKey, typesByName, parents, 0
    )

    private fun resolveProjectMethod(
        state: UnitState,
        call: MethodCallExpr,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>,
        depth: Int
    ): MethodDef? {
        if (depth > 16) return null
        val owners = if (call.scope.isPresent) {
            val scope = call.scope.get()
            val owner = resolveType(
                state, scope, state.offset(call), typesByName
            ) ?: (unwrap(scope) as? MethodCallExpr)?.let { receiver ->
                resolveProjectMethod(
                    state, receiver, methodsByKey, typesByName, parents, depth + 1
                )?.let { target ->
                    canonicalRawType(
                        target.state, target.node.type.toString(),
                        target.state.offset(target.node), typesByName
                    )
                }
            }
            listOfNotNull(owner)
        } else {
            state.ownerChain(call)
        }
        for (owner in owners) {
            for (current in projectHierarchy(owner, parents)) {
                val matches = methodsByKey[
                    MethodKey(current, call.nameAsString, call.arguments.size)
                ].orEmpty()
                if (matches.size == 1) return matches.single()
                if (matches.size > 1) {
                    return selectProjectOverload(
                        state, call, matches, typesByName, parents
                    )
                }
            }
        }
        return null
    }

    private fun selectProjectOverload(
        state: UnitState,
        call: MethodCallExpr,
        candidates: List<MethodDef>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): MethodDef? {
        val compatible = candidates.filter { candidate ->
            candidate.node.parameters.indices.all { index ->
                val parameter = candidate.node.parameters[index]
                val argument = call.arguments[index]
                val parameterRaw = canonicalRawType(
                    candidate.state, parameter.type.toString(),
                    candidate.state.offset(parameter), typesByName
                ) ?: parameter.type.toString().substringBefore('<').trim()
                when (argument) {
                    is LambdaExpr, is MethodReferenceExpr ->
                        parameterRaw in functionalTypes
                    is NullLiteralExpr ->
                        !parameter.type.isPrimitiveType
                    else -> {
                        val argumentType =
                            state.typing.typeOf(argument.toString(), state.offset(argument))
                                ?: return@all false
                        argumentType == parameterRaw ||
                            canonicalInherits(argumentType, parameterRaw, parents)
                    }
                }
            }
        }
        return compatible.distinct().singleOrNull()
    }

    private fun projectMethodsInHierarchy(
        owner: String,
        name: String,
        arity: Int?,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        parents: Map<String, String>
    ): List<MethodDef> {
        for (current in projectHierarchy(owner, parents)) {
            val matches = methodsByKey
                .filterKeys {
                    it.owner == current && it.name == name &&
                        (arity == null || it.arity == arity)
                }
                .values.flatten()
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }

    private fun projectHierarchy(
        owner: String,
        parents: Map<String, String>
    ): List<String> {
        val result = mutableListOf<String>()
        var current: String? = owner
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            result += current
            current = parents[current]
        }
        return result
    }

    private fun resolveProjectField(
        owner: String,
        name: String,
        fieldsByKey: Map<FieldKey, FieldDef>,
        parents: Map<String, String>
    ): FieldDef? {
        for (current in projectHierarchy(owner, parents)) {
            fieldsByKey[FieldKey(current, name)]?.let { return it }
        }
        return null
    }

    private fun resolveUnqualifiedField(
        state: UnitState,
        name: NameExpr,
        fieldsByKey: Map<FieldKey, FieldDef>,
        parents: Map<String, String>
    ): FieldDef? {
        val lexical = state.ownerChain(name).firstNotNullOfOrNull { owner ->
            resolveProjectField(owner, name.nameAsString, fieldsByKey, parents)
        }
        if (lexical != null) {
            return lexical.takeUnless { shadowedByOtherValue(state, name, it) }
        }
        val staticImports = state.unit.imports.filter {
            it.isStatic && !it.isAsterisk && it.name.identifier == name.nameAsString
        }
        check(staticImports.size <= 1) {
            "Ambiguous static imports for ${name.nameAsString} in ${state.file}"
        }
        if (staticImports.size == 1) {
            val target = fieldsByKey[
                FieldKey(
                    staticImports.single().nameAsString.substringBeforeLast('.'),
                    name.nameAsString
                )
            ]
            return target?.takeUnless { shadowedByOtherValue(state, name, it) }
        }
        return null
    }

    private fun shadowedByOtherValue(
        state: UnitState,
        name: NameExpr,
        target: FieldDef
    ): Boolean {
        if (localValueShadows(state, name)) return true
        val visible = JavaScopedDeclarations.visibleAt(
            state.masked, name.nameAsString, state.offset(name)
        ) ?: return false
        if (target.state !== state) return true
        val fieldRange = target.declaration.range.orElse(null) ?: return true
        val declarationStart =
            state.lineStarts[fieldRange.begin.line - 1] + fieldRange.begin.column - 1
        val declarationEnd =
            state.lineStarts[fieldRange.end.line - 1] + fieldRange.end.column - 1
        return visible.offset !in declarationStart..declarationEnd
    }

    private fun localValueShadows(state: UnitState, name: NameExpr): Boolean {
        val useOffset = state.offset(name)
        if (useOffset < 0) return true

        state.unit.findAll(Parameter::class.java)
            .filter { it.nameAsString == name.nameAsString }
            .forEach { parameter ->
                val scope = parameterScope(parameter) ?: return@forEach
                if (containsNode(scope, name)) return true
            }

        state.unit.findAll(VariableDeclarator::class.java)
            .filter {
                it.nameAsString == name.nameAsString &&
                    it.parentNode.orElse(null) !is FieldDeclaration &&
                    state.offset(it) in 0 until useOffset
            }
            .forEach { variable ->
                val scope = variableScope(variable) ?: return@forEach
                if (containsNode(scope, name)) return true
            }

        state.unit.findAll(TypePatternExpr::class.java)
            .filter { it.nameAsString == name.nameAsString }
            .forEach { pattern ->
                val test = pattern.findAncestor(InstanceOfExpr::class.java).orElse(null)
                    ?: return@forEach
                val condition = test.findAncestor(IfStmt::class.java).orElse(null)
                    ?: return@forEach
                if (containsNode(condition.condition, test) &&
                    containsNode(condition.thenStmt, name)
                ) return true
            }
        return false
    }

    private fun parameterScope(parameter: Parameter): Node? {
        var current = parameter.parentNode.orElse(null)
        while (current != null) {
            when (current) {
                is LambdaExpr -> return current.body
                is CatchClause -> return current.body
                is CallableDeclaration<*> -> return current
                is TypeDeclaration<*> -> return null
            }
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun variableScope(variable: VariableDeclarator): Node? {
        var current = variable.parentNode.orElse(null)
        while (current != null) {
            when (current) {
                is ForEachStmt ->
                    if (current.variable.variables.any { it === variable }) return current.body
                is ForStmt ->
                    if (current.initialization.any {
                            it.findAll(VariableDeclarator::class.java).any { value -> value === variable }
                        }
                    ) return current
                is TryStmt ->
                    if (current.resources.any {
                            it.findAll(VariableDeclarator::class.java).any { value -> value === variable }
                        }
                    ) return current.tryBlock
                is BlockStmt, is LambdaExpr, is CallableDeclaration<*> -> return current
                is TypeDeclaration<*> -> return null
            }
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun containsNode(root: Node, node: Node): Boolean {
        var current: Node? = node
        while (current != null) {
            if (current === root) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun containsOffset(state: UnitState, root: Node, at: Int): Boolean =
        root.range.map { range ->
            val start = state.lineStarts[range.begin.line - 1] + range.begin.column - 1
            val end = state.lineStarts[range.end.line - 1] + range.end.column - 1
            at in start..end
        }.orElse(false)

    private fun hasVisibleTypeParameter(
        state: UnitState,
        name: String,
        at: Int
    ): Boolean = state.unit.findAll(TypeParameter::class.java).any { parameter ->
        parameter.nameAsString == name &&
            parameter.parentNode.orElse(null)?.let { owner ->
                containsOffset(state, owner, at)
            } == true
    }

    private fun declaredLexicalValueType(
        state: UnitState,
        name: NameExpr,
        typesByName: Map<String, TypeDeclaration<*>>
    ): Pair<Boolean, String?> {
        val at = state.offset(name)
        val bindings = mutableListOf<Triple<Node, Node, String>>()
        state.unit.findAll(VariableDeclarator::class.java)
            .filter { it.nameAsString == name.nameAsString && state.offset(it) <= at }
            .forEach { declaration ->
                variableScope(declaration)?.takeIf {
                    containsNode(it, name)
                }?.let { scope ->
                    bindings += Triple(declaration, scope, declaration.type.toString())
                }
            }
        state.unit.findAll(Parameter::class.java)
            .filter { it.nameAsString == name.nameAsString }
            .forEach { declaration ->
                parameterScope(declaration)?.takeIf {
                    containsNode(it, name)
                }?.let { scope ->
                    bindings += Triple(declaration, scope, declaration.type.toString())
                }
            }
        val binding = bindings.minWithOrNull(
            compareBy<Triple<Node, Node, String>> {
                it.second.range.map { range ->
                    val start =
                        state.lineStarts[range.begin.line - 1] + range.begin.column - 1
                    val end =
                        state.lineStarts[range.end.line - 1] + range.end.column - 1
                    end - start
                }.orElse(Int.MAX_VALUE)
            }.thenByDescending { state.offset(it.first) }
        ) ?: return false to null
        return true to canonicalRawType(
            state, binding.third, state.offset(binding.first), typesByName
        )
    }

    private fun resolveType(
        state: UnitState,
        expression: Expression,
        at: Int,
        typesByName: Map<String, TypeDeclaration<*>>
    ): String? {
        if (expression is ThisExpr) {
            val text = expression.toString()
            if (text == "this") return state.owner(expression)
            return resolveNamedType(state, text.removeSuffix(".this"), at, typesByName)
        }
        if (expression is NameExpr) {
            val (declared, type) = declaredLexicalValueType(
                state, expression, typesByName
            )
            if (declared) return type
        }
        state.typing.typeOf(expression.toString(), at)?.let { return it }
        return resolveNamedType(state, expression.toString(), at, typesByName)
    }

    private fun resolveNamedType(
        state: UnitState,
        spelling: String,
        at: Int,
        typesByName: Map<String, TypeDeclaration<*>>
    ): String? {
        val text = spelling.replace(" ", "")
        if (!text.matches(Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""))) {
            return null
        }
        if (text in primitiveValueTypes) return text
        val first = text.substringBefore('.')
        if (hasVisibleTypeParameter(state, first, at)) return null
        if (state.typing.typeOf(text, at) != null) return null
        if (first.firstOrNull()?.isLowerCase() == true) {
            return text.takeIf {
                it in typesByName || it in cacheValueTypes || TargetMembers.hasClass(it) ||
                    it.substringBeforeLast('.', "") == "java.lang" &&
                    it.substringAfterLast('.') in implicitJavaLangTypes ||
                    it in setOf(
                        JEI_PLUGIN, JEI_MOD_PLUGIN, JEI_RECIPE_REGISTRATION,
                        JEI_CATEGORY_REGISTRATION
                    ) ||
                    it in functionalTypes
            }
        }

        val suffix = text.removePrefix(first)
        val candidates = linkedSetOf<String>()
        state.unit.imports
            .filter { !it.isStatic && !it.isAsterisk && it.name.identifier == first }
            .forEach { candidates += it.nameAsString + suffix }
        val samePackage = if (state.packageName.isBlank()) text else "${state.packageName}.$text"
        if (samePackage in typesByName) candidates += samePackage
        state.unit.findAll(TypeDeclaration::class.java)
            .filter { it.nameAsString == first }
            .map { state.owner(it) + suffix }
            .filter { it in typesByName }
            .forEach(candidates::add)

        val known = buildList {
            addAll(typesByName.keys.filter { it.substringAfterLast('.') == text })
            if ('.' !in text) addAll(TargetMembers.classesNamed(text))
            addAll(
                listOf(
                    POTION_BREWING, BREWING_REGISTRY, LEVEL, SERVER_LEVEL,
                    MINECRAFT_SERVER, CLIENT_PACKET_LISTENER, BLOCK_ENTITY,
                    JEI_PLUGIN, JEI_MOD_PLUGIN, JEI_RECIPE_REGISTRATION,
                    JEI_CATEGORY_REGISTRATION
                ).filter { it.substringAfterLast('.') == text }
            )
            addAll(functionalTypes.filter { it.substringAfterLast('.') == text })
            addAll(cacheValueTypes.filter { it.substringAfterLast('.') == text })
            addAll(
                implicitJavaLangTypes
                    .map { "java.lang.$it" }
                    .filter { it.substringAfterLast('.') == text }
            )
        }
        if (candidates.isEmpty()) {
            state.unit.imports.filter { !it.isStatic && it.isAsterisk }.forEach { imported ->
                val prefix = imported.nameAsString + "."
                known.filter { it.startsWith(prefix) }.forEach(candidates::add)
            }
            if (suffix.isEmpty() && first in implicitJavaLangTypes) {
                candidates += "java.lang.$first"
            }
        }

        return candidates.distinct().singleOrNull()?.takeIf { canonical ->
            state.typing.isTypeName(text, canonical) ||
                state.typing.typeOf(first, at) == null &&
                state.unit.imports.any {
                    !it.isStatic && it.isAsterisk &&
                        canonical.startsWith("${it.nameAsString}.")
                }
        }
    }

    private fun canonicalRawType(
        state: UnitState,
        type: String,
        at: Int,
        typesByName: Map<String, TypeDeclaration<*>>
    ): String? =
        resolveNamedType(
            state, type.substringBefore('<').trim().removeSuffix("[]").trim(),
            at, typesByName
        )

    private fun declaredTypeMatches(
        state: UnitState,
        type: String,
        at: Int,
        expected: String,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): Boolean {
        if (canonicalRawType(state, type, at, typesByName) == expected) return true
        val simple = type.substringBefore('<').substringAfterLast('.').trim()
        if ('.' in type.substringBefore('<') ||
            simple != expected.substringAfterLast('.')
        ) return false
        val lexicalOwner = state.unit.findAll(TypeDeclaration::class.java)
            .filter { declaration ->
                declaration.range.map { range ->
                    val start = state.lineStarts[range.begin.line - 1] + range.begin.column - 1
                    val end = state.lineStarts[range.end.line - 1] + range.end.column - 1
                    at in start..end
                }.orElse(false)
            }
            .maxByOrNull { declaration -> state.owner(declaration).count { it == '.' } }
            ?.let(state::owner)
            ?: return false
        val expectedOwner = expected.substringBeforeLast('.', "")
        return expectedOwner.isNotEmpty() &&
            projectHierarchy(lexicalOwner, parents).any { inherited ->
                inherited == expectedOwner ||
                    inherited.startsWith("$expectedOwner.")
            }
    }

    private fun declaredContainerElement(
        field: FieldDef,
        typesByName: Map<String, TypeDeclaration<*>>
    ): String? {
        val container = field.variable.type as? ClassOrInterfaceType ?: return null
        val raw = canonicalRawType(
            field.state, container.nameWithScope,
            field.state.offset(container), typesByName
        ) ?: return null
        if (raw !in cacheValueTypes) return null
        val element = container.typeArguments.orElse(null)?.singleOrNull() ?: return null
        return canonicalRawType(
            field.state, element.toString(), field.state.offset(element), typesByName
        )
    }

    private fun validateCacheField(
        field: FieldDef,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ) {
        check(field.declaration.variables.size == 1) {
            "Provider-dependent field declarations must contain one variable: ${field.key}"
        }
        check(field.declaration.isStatic && field.declaration.isFinal) {
            "Provider-dependent field must be static final: ${field.key}"
        }
        check(field.variable.initializer.isPresent) {
            "Provider-dependent field has no initializer: ${field.key}"
        }
        check(!field.variable.type.isPrimitiveType && !field.variable.type.isArrayType) {
            "Provider-dependent field cache requires a reference value: ${field.key}"
        }
        val raw = canonicalRawType(
            field.state, field.variable.type.toString(),
            field.state.offset(field.variable), typesByName
        )
        check(raw in cacheValueTypes) {
            "Provider-dependent field must be a proved collection or map view: " +
                "${field.key} has ${field.variable.type}"
        }
        validateKnownCacheShape(field, typesByName, parents)
        val forbidden = Regex("""[A-Za-z_$][\w$.]*""")
            .findAll(field.variable.type.toString())
            .mapNotNull {
                resolveNamedType(
                    field.state, it.value, field.state.offset(field.variable), typesByName
                )
            }
            .firstOrNull { declared ->
                forbiddenCacheTypes.any { forbidden ->
                    canonicalInherits(declared, forbidden, parents)
                }
            }
        check(forbidden == null) {
            "Provider-dependent cache value ${field.key} declares forbidden owner type $forbidden"
        }
        check(field.declaration.annotations.isEmpty()) {
            "Annotated provider-dependent fields are not rewritten implicitly: ${field.key}"
        }
        check(!field.declaration.hasModifier(Modifier.Keyword.TRANSIENT) &&
            !field.declaration.hasModifier(Modifier.Keyword.VOLATILE)
        ) {
            "Transient/volatile provider-dependent fields are unsupported: ${field.key}"
        }
    }

    private fun validateKnownCacheShape(
        field: FieldDef,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ) {
        check(field.variable.type.findAll(WildcardType::class.java).isEmpty()) {
            "Provider-dependent cache value ${field.key} has an unknown wildcard object graph"
        }
        val offset = field.state.offset(field.variable)
        for (type in field.variable.type.findAll(ClassOrInterfaceType::class.java)) {
            val spelling = type.nameWithScope
            val nestedOwnerQualifier =
                (type.parentNode.orElse(null) as? ClassOrInterfaceType)
                    ?.scope?.map { it === type }?.orElse(false) == true
            if (nestedOwnerQualifier &&
                spelling.substringBefore('.').firstOrNull()?.isLowerCase() == true
            ) {
                continue
            }
            val canonical = resolveNamedType(
                field.state, spelling, offset, typesByName
            ) ?: spelling.substringAfterLast('.').takeIf { it in implicitJavaLangTypes }
                ?.let { "java.lang.$it" }
            check(canonical != null) {
                "Provider-dependent cache value ${field.key} has unknown declared type $spelling"
            }
            if (canonical == POTION_BREWING && nestedOwnerQualifier) {
                continue
            }
            check(canonical != "java.lang.Object") {
                "Provider-dependent cache value ${field.key} has an unknown Object element graph"
            }
            check(forbiddenRetentionTypes.none {
                    forbidden -> canonicalInherits(canonical, forbidden, parents)
                }
            ) {
                "Provider-dependent cache value ${field.key} declares retained owner type $canonical"
            }
            if (canonical in cacheValueTypes) {
                check(type.typeArguments.isPresent && type.typeArguments.get().isNotEmpty()) {
                    "Provider-dependent cache value ${field.key} uses raw container $canonical"
                }
            }
        }
    }

    private fun insideAnonymousClass(node: Node): Boolean {
        var current: Node? = node.parentNode.orElse(null)
        while (current != null) {
            if (current is ObjectCreationExpr && current.anonymousClassBody.isPresent) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun threadable(method: MethodDef): Boolean =
        method.node.isPrivate &&
            !method.node.isAbstract &&
            !method.node.hasModifier(Modifier.Keyword.NATIVE) &&
            method.node.annotations.none { it.name.identifier == "Override" }

    private fun canonicalInherits(
        type: String,
        ancestor: String,
        parents: Map<String, String>
    ): Boolean {
        var current: String? = type
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            if (TargetMembers.hasClass(current) &&
                TargetMembers.inheritsFrom(current, ancestor)
            ) return true
            current = parents[current]
        }
        return false
    }

    private fun groundedProvider(
        state: UnitState,
        node: Node,
        context: Context,
        states: List<UnitState>,
        typesByName: Map<String, TypeDeclaration<*>>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        fieldsByKey: Map<FieldKey, FieldDef>,
        parents: Map<String, String>
    ): String? {
        val offset = state.offset(node)
        val callable: CallableDeclaration<*>? = context.method?.node ?: context.constructor
        if (callable != null) {
            val names = linkedSetOf<String>()
            callable.parameters.forEach { names += it.nameAsString }
            callable.findAll(VariableDeclarator::class.java)
                .filter { state.offset(it) < offset }
                .forEach { names += it.nameAsString }
            val owner = state.owner(callable)
            owner?.let(typesByName::get)?.fields
                ?.filter { context.method?.node?.isStatic != true || it.isStatic }
                ?.flatMap { it.variables }
                ?.forEach { names += it.nameAsString }
            val candidates = linkedSetOf<String>()
            for (name in names) {
                val type = state.typing.typeOf(name, offset) ?: continue
                if (!nonNullDominates(
                        state, node, name, methodsByKey, typesByName, parents
                    )
                ) continue
                when {
                    type == POTION_BREWING -> candidates += name
                    canonicalInherits(type, LEVEL, parents) ||
                        canonicalInherits(type, MINECRAFT_SERVER, parents) ||
                        canonicalInherits(type, CLIENT_PACKET_LISTENER, parents) ->
                        candidates += "$name.potionBrewing()"
                }
            }
            check(candidates.size <= 1) {
                "Multiple visible PotionBrewing providers at ${state.file}:$offset: " +
                    candidates.joinToString()
            }
            candidates.singleOrNull()?.let { return it }
        }

        val method = context.method?.node
        if (method != null && !method.isStatic) {
            val owner = state.owner(method)
            val definition = context.method
            if (owner != null &&
                canonicalInherits(owner, BLOCK_ENTITY, parents) &&
                definition != null &&
                blockEntityLifecycleProvesProvider(
                    definition, node, states, methodsByKey, typesByName, parents
                )
            ) {
                return "this.getLevel().potionBrewing()"
            }
        }

        if (clientRecipeSupplier(
                state, node, states, typesByName, methodsByKey, fieldsByKey, parents
            )
        ) {
            return "net.minecraft.client.Minecraft.getInstance().getConnection().potionBrewing()"
        }
        return null
    }

    private fun nonNullDominates(
        state: UnitState,
        node: Node,
        name: String,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): Boolean {
        val fieldRoot = providerIsFieldRoot(state, node, name)
        var current: Node? = node
        while (current != null) {
            val parent = current.parentNode.orElse(null) ?: break
            if (parent is IfStmt) {
                val conditionWrites = providerWrittenBetween(
                    state, parent.condition, name,
                    state.offset(parent.condition), Int.MAX_VALUE,
                    fieldRoot, methodsByKey, typesByName, parents
                )
                if (containsNode(parent.thenStmt, node) &&
                    conditionTrueProvesNonNull(parent.condition, name) &&
                    !conditionWrites &&
                    !providerWrittenBetween(
                        state, parent.thenStmt, name,
                        state.offset(parent.thenStmt), state.offset(node),
                        fieldRoot, methodsByKey, typesByName, parents
                    )
                ) return true
                if (parent.elseStmt.map { containsNode(it, node) }.orElse(false) &&
                    conditionFalseProvesNonNull(parent.condition, name) &&
                    !conditionWrites &&
                    !providerWrittenBetween(
                        state, parent.elseStmt.get(), name,
                        state.offset(parent.elseStmt.get()), state.offset(node),
                        fieldRoot, methodsByKey, typesByName, parents
                    )
                ) return true
            }
            if (parent is BlockStmt) {
                var direct: Node = current
                while (direct.parentNode.orElse(null) !== parent) {
                    direct = direct.parentNode.orElse(null) ?: break
                }
                val statement = direct as? Statement
                val index = statement?.let(parent.statements::indexOf) ?: -1
                if (statement != null && index >= 0) {
                    val currentPrefixWrites = providerWrittenBetween(
                        state, statement, name,
                        state.offset(statement), state.offset(node),
                        fieldRoot, methodsByKey, typesByName, parents
                    )
                    for ((priorIndex, prior) in parent.statements.take(index).withIndex()) {
                        val following = parent.statements
                            .subList(priorIndex + 1, index)
                            .any {
                                providerWrittenBetween(
                                    state, it, name, state.offset(it), state.offset(node),
                                    fieldRoot, methodsByKey, typesByName, parents
                                )
                            }
                        val guardWrites = prior is IfStmt &&
                            providerWrittenBetween(
                                state, prior.condition, name,
                                state.offset(prior.condition), Int.MAX_VALUE,
                                fieldRoot, methodsByKey, typesByName, parents
                            )
                        if (prior is IfStmt &&
                            alwaysExits(prior.thenStmt) &&
                            conditionFalseProvesNonNull(prior.condition, name) &&
                            !guardWrites &&
                            !following &&
                            !currentPrefixWrites
                        ) return true
                        if (prior is ExpressionStmt &&
                            isRequireNonNull(prior.expression, state, name, typesByName) &&
                            !following &&
                            !currentPrefixWrites
                        ) return true
                    }
                }
            }
            current = parent
        }
        return false
    }

    private fun providerIsFieldRoot(
        state: UnitState,
        node: Node,
        name: String
    ): Boolean {
        val useOffset = state.offset(node)
        if (useOffset < 0) return true
        if (state.unit.findAll(Parameter::class.java).any { parameter ->
                parameter.nameAsString == name &&
                    parameterScope(parameter)?.let { containsNode(it, node) } == true
            }
        ) return false
        if (state.unit.findAll(VariableDeclarator::class.java).any { variable ->
                variable.nameAsString == name &&
                    variable.parentNode.orElse(null) !is FieldDeclaration &&
                    state.offset(variable) in 0 until useOffset &&
                    variableScope(variable)?.let { containsNode(it, node) } == true
            }
        ) return false
        return true
    }

    private fun conditionTrueProvesNonNull(expression: Expression, name: String): Boolean {
        val value = unwrap(expression)
        if (value is BinaryExpr) {
            if (value.operator == BinaryExpr.Operator.NOT_EQUALS &&
                nullComparisonName(value.left, value.right) == name
            ) return true
            if (value.operator == BinaryExpr.Operator.AND) {
                return conditionTrueProvesNonNull(value.left, name) ||
                    conditionTrueProvesNonNull(value.right, name)
            }
        }
        return false
    }

    private fun conditionFalseProvesNonNull(expression: Expression, name: String): Boolean {
        val value = unwrap(expression)
        if (value is BinaryExpr) {
            if (value.operator == BinaryExpr.Operator.EQUALS &&
                nullComparisonName(value.left, value.right) == name
            ) return true
            if (value.operator == BinaryExpr.Operator.OR) {
                return conditionFalseProvesNonNull(value.left, name) ||
                    conditionFalseProvesNonNull(value.right, name)
            }
        }
        return false
    }

    private fun nullComparisonName(left: Expression, right: Expression): String? = when {
        unwrap(left) is NullLiteralExpr -> expressionName(unwrap(right))
        unwrap(right) is NullLiteralExpr -> expressionName(unwrap(left))
        else -> null
    }

    private fun expressionName(expression: Expression): String? = when (val value = unwrap(expression)) {
        is NameExpr -> value.nameAsString
        is FieldAccessExpr ->
            value.nameAsString.takeIf {
                value.scope is ThisExpr || value.scope.toString() == "this"
            }
        else -> null
    }

    private fun unwrap(expression: Expression): Expression {
        var result = expression
        while (result is EnclosedExpr) result = result.inner
        return result
    }

    private fun alwaysExits(statement: Statement): Boolean = when (statement) {
        is ReturnStmt, is ThrowStmt -> true
        is BlockStmt -> statement.statements.lastOrNull()?.let(::alwaysExits) == true
        is IfStmt ->
            alwaysExits(statement.thenStmt) &&
                statement.elseStmt.map(::alwaysExits).orElse(false)
        else -> false
    }

    private fun providerWrittenBetween(
        state: UnitState,
        root: Node,
        name: String,
        from: Int,
        to: Int,
        fieldRoot: Boolean,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>,
        visitedMethods: MutableSet<MethodKey> = mutableSetOf()
    ): Boolean {
        fun within(node: Node): Boolean = state.offset(node) in from until to
        val directWrite = root.findAll(AssignExpr::class.java).any { assignment ->
            expressionName(assignment.target) == name &&
                within(assignment)
        } || root.findAll(UnaryExpr::class.java).any { unary ->
            unary.operator in setOf(
                UnaryExpr.Operator.POSTFIX_DECREMENT,
                UnaryExpr.Operator.POSTFIX_INCREMENT,
                UnaryExpr.Operator.PREFIX_DECREMENT,
                UnaryExpr.Operator.PREFIX_INCREMENT
            ) &&
                expressionName(unary.expression) == name &&
                within(unary)
        } || root.findAll(VariableDeclarator::class.java).any {
            it.nameAsString == name && within(it)
        }
        if (directWrite || !fieldRoot) return directWrite
        return root.findAll(MethodCallExpr::class.java).any { call ->
            if (!within(call)) return@any false
            if (call.arguments.any { argument ->
                    unwrap(argument) is ThisExpr ||
                        argument.findAll(ThisExpr::class.java).isNotEmpty()
                }
            ) return@any true
            val scope = call.scope.orElse(null)?.let(::unwrap)
            if (scope != null && scope !is ThisExpr && scope !is SuperExpr) {
                return@any false
            }
            val target = resolveProjectMethod(
                state, call, methodsByKey, typesByName, parents
            ) ?: return@any true
            if (!visitedMethods.add(target.key)) return@any false
            providerWrittenBetween(
                target.state, target.node, name,
                target.state.offset(target.node), Int.MAX_VALUE,
                true, methodsByKey, typesByName, parents, visitedMethods
            )
        }
    }

    private fun isRequireNonNull(
        expression: Expression,
        state: UnitState,
        name: String,
        typesByName: Map<String, TypeDeclaration<*>>
    ): Boolean {
        val call = unwrap(expression) as? MethodCallExpr ?: return false
        if (call.nameAsString != "requireNonNull" ||
            call.arguments.firstOrNull()?.let(::expressionName) != name
        ) return false
        return call.scope.map {
            resolveType(state, it, state.offset(call), typesByName) == "java.util.Objects"
        }.orElse(false)
    }

    private fun blockEntityLifecycleProvesProvider(
        method: MethodDef,
        use: Node,
        states: List<UnitState>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): Boolean {
        if (sourceShadowsBlockEntityLevel(method, typesByName, parents)) return false
        if (nonNullDominates(
                method.state, use, "level", methodsByKey, typesByName, parents
            )
        ) return true
        val byNode = methodsByKey.values.flatten().associateBy { it.node }
        val calls = mutableListOf<Pair<UnitState, MethodCallExpr>>()
        for (state in states) {
            for (call in state.unit.findAll(MethodCallExpr::class.java)) {
                if (call.nameAsString != method.key.name ||
                    call.arguments.size != method.key.arity ||
                    call.scope.orElse(null) is SuperExpr
                ) continue
                val target = resolveProjectMethod(
                    state, call, methodsByKey, typesByName, parents
                )
                val dispatches = target?.let {
                    it.key == method.key ||
                        !it.node.isStatic &&
                        canonicalInherits(method.key.owner, it.key.owner, parents)
                } ?: run {
                    val possibleOwners = if (call.scope.isPresent) {
                        listOfNotNull(
                            resolveType(
                                state, call.scope.get(), state.offset(call), typesByName
                            )
                        )
                    } else {
                        state.ownerChain(call)
                    }
                    if (possibleOwners.any {
                            canonicalInherits(method.key.owner, it, parents) ||
                                canonicalInherits(it, method.key.owner, parents)
                        }
                    ) return false
                    false
                }
                if (dispatches) calls += state to call
            }
            for (reference in state.unit.findAll(MethodReferenceExpr::class.java)) {
                if (reference.identifier != method.key.name) continue
                val owner = resolveType(
                    state, reference.scope, state.offset(reference), typesByName
                ) ?: return false
                if (canonicalInherits(method.key.owner, owner, parents) ||
                    canonicalInherits(owner, method.key.owner, parents)
                ) return false
            }
        }
        if (calls.isEmpty()) return false
        return calls.all { (state, call) ->
            val scope = call.scope.orElse(null)
            if (scope != null && scope !is ThisExpr) return@all false
            val callerNode = call.findAncestor(MethodDeclaration::class.java).orElse(null)
                ?: return@all false
            val caller = byNode[callerNode] ?: return@all false
            canonicalInherits(caller.key.owner, BLOCK_ENTITY, parents) &&
                !sourceShadowsBlockEntityLevel(caller, typesByName, parents) &&
                nonNullDominates(
                    state, call, "level", methodsByKey, typesByName, parents
                )
        }
    }

    private fun sourceShadowsBlockEntityLevel(
        method: MethodDef,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): Boolean {
        if (method.node.parameters.any { it.nameAsString == "level" } ||
            method.node.findAll(VariableDeclarator::class.java).any {
                it.nameAsString == "level"
            }
        ) return true
        return projectHierarchy(method.key.owner, parents).any { owner ->
            typesByName[owner]?.fields.orEmpty().flatMap { it.variables }
                .any { it.nameAsString == "level" }
        }
    }

    private fun clientRecipeSupplier(
        state: UnitState,
        node: Node,
        states: List<UnitState>,
        typesByName: Map<String, TypeDeclaration<*>>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        fieldsByKey: Map<FieldKey, FieldDef>,
        parents: Map<String, String>
    ): Boolean {
        val type = node.findAncestor(TypeDeclaration::class.java).orElse(null) ?: return false
        val clientPlugin = type.annotations.any {
            resolveNamedType(state, it.nameAsString, state.offset(it), typesByName) == JEI_PLUGIN
        } || (type as? ClassOrInterfaceDeclaration)?.implementedTypes.orEmpty().any {
            canonicalRawType(state, it.toString(), state.offset(it), typesByName) == JEI_MOD_PLUGIN
        }
        if (!clientPlugin) return false
        val lambda = node.findAncestor(LambdaExpr::class.java).orElse(null) ?: return false
        var parent: Node = lambda
        while (parent.parentNode.orElse(null) is EnclosedExpr) {
            parent = parent.parentNode.get()
        }
        val registration = parent.parentNode.orElse(null) as? MethodCallExpr ?: return false
        if (registration.nameAsString != "addRecipes" || parent !in registration.arguments) return false
        val target = resolveProjectMethod(
            state, registration, methodsByKey, typesByName, parents
        ) ?: return false
        if (target.state !== state || !declaredWithin(target.node, type)) return false
        val argument = registration.arguments.indexOf(parent)
        val supplier = target.node.parameters.getOrNull(argument) ?: return false
        if (canonicalRawType(
                target.state, supplier.type.toString(), target.state.offset(supplier), typesByName
            ) != SUPPLIER
        ) return false
        val deferredGet = target.node.findAll(MethodCallExpr::class.java).singleOrNull { get ->
            get.nameAsString == "get" && get.arguments.isEmpty() &&
                (get.scope.orElse(null) as? NameExpr)?.nameAsString == supplier.nameAsString &&
                get.findAncestor(LambdaExpr::class.java).map { deferred ->
                    deferred.findAncestor(MethodDeclaration::class.java)
                        .map { it === target.node }.orElse(false)
                }.orElse(false)
        } ?: return false
        val deferred = deferredGet.findAncestor(LambdaExpr::class.java).orElse(null) ?: return false
        var deferredArgument: Node = deferred
        while (deferredArgument.parentNode.orElse(null) is EnclosedExpr) {
            deferredArgument = deferredArgument.parentNode.get()
        }
        val consumerRegistration =
            deferredArgument.parentNode.orElse(null) as? MethodCallExpr ?: return false
        val consumerSink = resolveProjectMethod(
            target.state, consumerRegistration, methodsByKey, typesByName, parents
        ) ?: return false
        val consumerArgument = consumerRegistration.arguments.indexOf(deferredArgument)
        if (consumerArgument < 0) return false
        val consumerParameter = consumerSink.node.parameters.getOrNull(consumerArgument)
            ?: return false
        if (canonicalRawType(
                consumerSink.state, consumerParameter.type.toString(),
                consumerSink.state.offset(consumerParameter), typesByName
            ) != CONSUMER
        ) return false
        val storedConsumerField = consumerSink.node.findAll(MethodCallExpr::class.java)
            .mapNotNull { call ->
                if (call.nameAsString != "add" ||
                    call.arguments.none {
                        it is NameExpr &&
                            it.nameAsString == consumerParameter.nameAsString
                    }
                ) return@mapNotNull null
                val scope = call.scope.orElse(null) ?: return@mapNotNull null
                resolveFieldExpression(
                    consumerSink.state, scope, fieldsByKey, typesByName, parents
                )
            }
            .distinctBy { it.key }
            .singleOrNull()
            ?: return false
        if (!storedConsumerField.declaration.isFinal ||
            declaredContainerElement(storedConsumerField, typesByName) != CONSUMER
        ) return false

        val builderEvaluators = methodsByKey.values.flatten().mapNotNull { candidate ->
            if (candidate.key.owner != consumerSink.key.owner) return@mapNotNull null
            val loop = candidate.node.findAll(ForEachStmt::class.java).singleOrNull { loop ->
                val field = resolveFieldExpression(
                    candidate.state, loop.iterable, fieldsByKey, typesByName, parents
                )
                if (field?.key != storedConsumerField.key) return@singleOrNull false
                val value = loop.variable.variables.singleOrNull()?.nameAsString
                    ?: return@singleOrNull false
                loop.body.findAll(MethodCallExpr::class.java).any { call ->
                    call.nameAsString == "accept" &&
                        (call.scope.orElse(null) as? NameExpr)?.nameAsString == value
                }
            } ?: return@mapNotNull null
            val evaluatorLambda = loop.findAncestor(LambdaExpr::class.java).orElse(null)
                ?: return@mapNotNull null
            if (evaluatorLambda.findAncestor(MethodDeclaration::class.java)
                    .map { it !== candidate.node }.orElse(true)
            ) return@mapNotNull null
            val initializedSupplier =
                evaluatorLambda.findAncestor(VariableDeclarator::class.java)
                    .filter { variable ->
                        variable.initializer.map {
                            containsNode(it, evaluatorLambda)
                        }.orElse(false)
                    }.orElse(null)
            val assignedName = evaluatorLambda.findAncestor(AssignExpr::class.java)
                .filter { assignment ->
                    containsNode(assignment.value, evaluatorLambda)
                }.map { assignment ->
                    expressionName(assignment.target)
                }.orElse(null)
            val assignedSupplier = assignedName?.let { name ->
                candidate.node.findAll(VariableDeclarator::class.java)
                    .singleOrNull { it.nameAsString == name }
            }
            val supplierVariable = initializedSupplier ?: assignedSupplier
                ?: return@mapNotNull null
            if (canonicalRawType(
                    candidate.state, supplierVariable.type.toString(),
                    candidate.state.offset(supplierVariable), typesByName
                ) != SUPPLIER
            ) return@mapNotNull null
            candidate to supplierVariable
        }
        val (builderEvaluator, deferredSupplier) =
            builderEvaluators.singleOrNull() ?: return false
        val categoryType = canonicalRawType(
            builderEvaluator.state, builderEvaluator.node.type.toString(),
            builderEvaluator.state.offset(builderEvaluator.node), typesByName
        ) ?: return false
        val categoryRegistration = projectMethodsInHierarchy(
            categoryType, "registerRecipes", 1, methodsByKey, parents
        ).singleOrNull { method ->
            canonicalRawType(
                method.state, method.node.parameters.single().type.toString(),
                method.state.offset(method.node.parameters.single()), typesByName
            ) == JEI_RECIPE_REGISTRATION
        } ?: return false
        val categoryRegistrationParameter =
            categoryRegistration.node.parameters.single().nameAsString
        val categorySupplierField = categoryRegistration.node
            .findAll(MethodCallExpr::class.java)
            .mapNotNull { get ->
                if (get.nameAsString != "get" || get.arguments.isNotEmpty()) return@mapNotNull null
                val supplierField = get.scope.orElse(null)?.let {
                    resolveFieldExpression(
                        categoryRegistration.state, it, fieldsByKey, typesByName, parents
                    )
                } ?: return@mapNotNull null
                if (canonicalRawType(
                        supplierField.state, supplierField.variable.type.toString(),
                        supplierField.state.offset(supplierField.variable), typesByName
                    ) != SUPPLIER
                ) return@mapNotNull null
                val add = get.findAncestor(MethodCallExpr::class.java).orElse(null)
                    ?: return@mapNotNull null
                if (add.nameAsString != "addRecipes" ||
                    (add.scope.orElse(null) as? NameExpr)?.nameAsString !=
                    categoryRegistrationParameter ||
                    !containsNode(add, get)
                ) return@mapNotNull null
                supplierField
            }
            .distinctBy { it.key }
            .singleOrNull()
            ?: return false

        val supplierInfoCreation = builderEvaluator.node
            .findAll(ObjectCreationExpr::class.java)
            .singleOrNull { creation ->
                creation.arguments.any {
                    expressionName(it) == deferredSupplier.nameAsString
                }
            } ?: return false
        if (!mandatoryAfterNonExitingBranches(
                supplierInfoCreation, builderEvaluator.node
            )
        ) return false
        val infoArgument = supplierInfoCreation.arguments.indexOfFirst {
            expressionName(it) == deferredSupplier.nameAsString
        }
        if (infoArgument < 0) return false
        val infoType = canonicalRawType(
            builderEvaluator.state, supplierInfoCreation.type.toString(),
            builderEvaluator.state.offset(supplierInfoCreation), typesByName
        ) ?: return false
        val infoRecord = typesByName[infoType] as? RecordDeclaration ?: return false
        val infoState = states.singleOrNull { candidate ->
            candidate.unit.findAll(TypeDeclaration::class.java).any { it === infoRecord }
        } ?: return false
        val supplierComponent = infoRecord.parameters.getOrNull(infoArgument) ?: return false
        if (canonicalRawType(
                infoState, supplierComponent.type.toString(),
                infoState.offset(supplierComponent), typesByName
            ) != SUPPLIER
        ) return false
        val infoVariable = supplierInfoCreation.findAncestor(VariableDeclarator::class.java)
            .filter { variable ->
                variable.initializer.map {
                    containsNode(it, supplierInfoCreation)
                }.orElse(false)
            }.orElse(null)
            ?: return false

        val factoryInvocation = builderEvaluator.node.findAll(MethodCallExpr::class.java)
            .singleOrNull { call ->
                call.nameAsString == "create" &&
                    call.arguments.any {
                        expressionName(it) == infoVariable.nameAsString
                    } &&
                    call.scope.orElse(null) is NameExpr
            } ?: return false
        if (!mandatoryAfterNonExitingBranches(
                factoryInvocation, builderEvaluator.node
            )
        ) return false
        val factoryName = (factoryInvocation.scope.get() as NameExpr).nameAsString
        val factoryParameter = builderEvaluator.node.parameters.singleOrNull {
            it.nameAsString == factoryName
        } ?: return false
        val factoryType = canonicalRawType(
            builderEvaluator.state, factoryParameter.type.toString(),
            builderEvaluator.state.offset(factoryParameter), typesByName
        ) ?: return false
        val factoryMethod = projectMethodsInHierarchy(
            factoryType, factoryInvocation.nameAsString,
            factoryInvocation.arguments.size, methodsByKey, parents
        ).singleOrNull { method ->
            method.node.parameters.singleOrNull()?.let { parameter ->
                declaredTypeMatches(
                    method.state, parameter.type.toString(),
                    method.state.offset(parameter), infoType, typesByName, parents
                )
            } == true &&
                canonicalRawType(
                    method.state, method.node.type.toString(),
                    method.state.offset(method.node), typesByName
                )?.let { returned ->
                    returned == categoryType ||
                        canonicalInherits(returned, categoryType, parents)
                } == true
        } ?: return false
        val categoryVariable = factoryInvocation.findAncestor(VariableDeclarator::class.java)
            .filter { variable ->
                variable.initializer.map {
                    containsNode(it, factoryInvocation)
                }.orElse(false)
            }.orElse(null)
            ?: return false
        val allCategoriesField = builderEvaluator.node.findAll(MethodCallExpr::class.java)
            .mapNotNull { call ->
                if (call.nameAsString != "add" ||
                    call.arguments.none {
                        expressionName(it) == categoryVariable.nameAsString
                    }
                ) return@mapNotNull null
                call.scope.orElse(null)?.let {
                    resolveFieldExpression(
                        builderEvaluator.state, it, fieldsByKey, typesByName, parents
                    )
                }
            }
            .distinctBy { it.key }
            .singleOrNull()
            ?: return false
        if (!allCategoriesField.declaration.isFinal ||
            declaredContainerElement(allCategoriesField, typesByName) != categoryType
        ) return false
        val categoryAdd = builderEvaluator.node.findAll(MethodCallExpr::class.java)
            .singleOrNull { call ->
                call.nameAsString == "add" &&
                    call.arguments.any {
                        expressionName(it) == categoryVariable.nameAsString
                    } &&
                    call.scope.orElse(null)?.let {
                        resolveFieldExpression(
                            builderEvaluator.state, it, fieldsByKey,
                            typesByName, parents
                        )?.key
                    } == allCategoriesField.key
            } ?: return false
        if (!mandatoryAfterNonExitingBranches(
                categoryAdd, builderEvaluator.node
            )
        ) return false
        val categoryReturn = builderEvaluator.node.findAll(ReturnStmt::class.java)
            .filter {
                mandatoryAfterNonExitingBranches(it, builderEvaluator.node)
            }
            .singleOrNull() ?: return false
        if (categoryReturn.expression.map {
                expressionName(it) == categoryVariable.nameAsString
            }.orElse(false).not()
        ) return false

        val pluginClass = type as? ClassOrInterfaceDeclaration ?: return false
        val registrationEntry = pluginClass.methods.singleOrNull { method ->
            method.nameAsString == "registerRecipes" &&
                method.parameters.size == 1 &&
                canonicalRawType(
                    state, method.parameters.single().type.toString(),
                    state.offset(method.parameters.single()), typesByName
                ) == JEI_RECIPE_REGISTRATION
        } ?: return false
        val entryParameter = registrationEntry.parameters.single().nameAsString
        val forwarded = registrationEntry.findAll(MethodCallExpr::class.java).any { forEach ->
            if (forEach.nameAsString != "forEach") return@any false
            if (!mandatoryWithin(forEach, registrationEntry)) return@any false
            val collection = forEach.scope.orElse(null)?.let {
                resolveFieldExpression(state, it, fieldsByKey, typesByName, parents)
            }
            if (collection?.key != allCategoriesField.key) return@any false
            val lambda = forEach.arguments.singleOrNull() as? LambdaExpr ?: return@any false
            val categoryName = lambda.parameters.singleOrNull()?.nameAsString ?: return@any false
            lambda.body.findAll(MethodCallExpr::class.java).any { call ->
                call.nameAsString == categoryRegistration.key.name &&
                    (call.scope.orElse(null) as? NameExpr)?.nameAsString == categoryName &&
                    call.arguments.singleOrNull()?.let(::expressionName) == entryParameter
            }
        }
        if (!forwarded) return false

        val sourceMethod = node.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return false
        val categoryEntry = pluginClass.methods.singleOrNull { method ->
            method.nameAsString == "registerCategories" &&
                method.parameters.size == 1 &&
                canonicalRawType(
                    state, method.parameters.single().type.toString(),
                    state.offset(method.parameters.single()), typesByName
                ) == JEI_CATEGORY_REGISTRATION
        } ?: return false
        val sourceLoadCall = categoryEntry.findAll(MethodCallExpr::class.java)
            .singleOrNull { call ->
                mandatoryWithin(call, categoryEntry) &&
                resolveProjectMethod(
                    state, call, methodsByKey, typesByName, parents
                )?.node === sourceMethod
            } ?: return false
        val categoryCollectionRegistered =
            categoryEntry.findAll(MethodCallExpr::class.java).any { add ->
                if (add.nameAsString != "addRecipeCategories" ||
                    (add.scope.orElse(null) as? NameExpr)?.nameAsString !=
                    categoryEntry.parameters.single().nameAsString ||
                    state.offset(add) <= state.offset(sourceLoadCall)
                ) return@any false
                if (!mandatoryWithin(add, categoryEntry)) return@any false
                add.arguments.any { argument ->
                    argument.findAll(MethodCallExpr::class.java).any { toArray ->
                        toArray.nameAsString == "toArray" &&
                            toArray.scope.orElse(null)?.let {
                                resolveFieldExpression(
                                    state, it, fieldsByKey, typesByName, parents
                                )?.key
                            } == allCategoriesField.key
                    }
                }
            }
        if (!categoryCollectionRegistered) return false

        val sourceBuild = sourceMethod.findAll(MethodCallExpr::class.java)
            .singleOrNull { build ->
                containsNode(build, registration) &&
                    mandatoryWithin(build, sourceMethod) &&
                    resolveProjectMethod(
                        state, build, methodsByKey, typesByName, parents
                    )?.key == builderEvaluator.key
            } ?: return false
        val factoryIndex = builderEvaluator.node.parameters.indexOf(factoryParameter)
        val factoryArgument = sourceBuild.arguments.getOrNull(factoryIndex) ?: return false
        if (!factoryTransfersInfoToSupplier(
                state, factoryArgument, factoryMethod, infoType,
                supplierComponent.nameAsString, categorySupplierField,
                states, methodsByKey, typesByName, fieldsByKey, parents
            )
        ) return false

        val registeredBuilds = sourceMethod.findAll(MethodCallExpr::class.java).filter { build ->
            mandatoryWithin(build, sourceMethod) &&
            resolveProjectMethod(
                state, build, methodsByKey, typesByName, parents
            )?.key == builderEvaluator.key
        }
        return registeredBuilds.any build@ { build ->
            build.findAll(MethodCallExpr::class.java).any call@ { builderCall ->
                if (!mandatoryWithin(builderCall, sourceMethod)) return@call false
                val called = resolveProjectMethod(
                    state, builderCall, methodsByKey, typesByName, parents
                ) ?: return@call false
                methodRegistersMandatoryConnectionConsumer(
                    called, consumerSink, states, methodsByKey, typesByName, parents
                )
            }
        }
    }

    private fun factoryTransfersInfoToSupplier(
        state: UnitState,
        argument: Expression,
        factoryMethod: MethodDef,
        infoType: String,
        componentName: String,
        supplierField: FieldDef,
        states: List<UnitState>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        fieldsByKey: Map<FieldKey, FieldDef>,
        parents: Map<String, String>
    ): Boolean {
        val infoParameterIndex = factoryMethod.node.parameters.indexOfFirst { parameter ->
            declaredTypeMatches(
                factoryMethod.state, parameter.type.toString(),
                factoryMethod.state.offset(parameter), infoType, typesByName, parents
            )
        }
        if (infoParameterIndex < 0) return false

        fun creationTransfers(
            creationState: UnitState,
            creation: ObjectCreationExpr,
            infoName: String
        ): Boolean {
            val argumentIndex = creation.arguments.indexOfFirst {
                expressionName(it) == infoName
            }
            if (argumentIndex < 0) return false
            val concreteType = canonicalRawType(
                creationState, creation.type.toString(),
                creationState.offset(creation), typesByName
            ) ?: return false
            return constructorTransfersInfoToSupplier(
                concreteType, creation.arguments.size, argumentIndex,
                infoType, componentName, supplierField, states,
                typesByName, fieldsByKey, parents
            )
        }

        fun exactReturnedCreation(
            returnState: UnitState,
            root: Node,
            infoName: String
        ): Boolean {
            val returned = root.findAll(ReturnStmt::class.java)
                .filter { mandatoryWithin(it, root) }
                .singleOrNull()
                ?: return false
            val creation = returned.expression.orElse(null)?.let(::unwrap)
                as? ObjectCreationExpr
                ?: return false
            return creationTransfers(returnState, creation, infoName)
        }

        return when (val value = unwrap(argument)) {
            is MethodReferenceExpr -> {
                val concreteOwner = resolveType(
                    state, value.scope, state.offset(value), typesByName
                ) ?: return false
                if (value.identifier == "new") {
                    constructorTransfersInfoToSupplier(
                        concreteOwner, factoryMethod.node.parameters.size,
                        infoParameterIndex, infoType, componentName, supplierField,
                        states, typesByName, fieldsByKey, parents
                    )
                } else {
                    val target = projectMethodsInHierarchy(
                        concreteOwner, value.identifier,
                        factoryMethod.node.parameters.size, methodsByKey, parents
                    ).singleOrNull { candidate ->
                        candidate.node.parameters.getOrNull(infoParameterIndex)?.let {
                            declaredTypeMatches(
                                candidate.state, it.type.toString(),
                                candidate.state.offset(it), infoType, typesByName, parents
                            )
                        } == true
                    } ?: return false
                    val infoName = target.node.parameters[infoParameterIndex].nameAsString
                    exactReturnedCreation(target.state, target.node, infoName)
                }
            }
            is LambdaExpr -> {
                val infoName = value.parameters.getOrNull(infoParameterIndex)?.nameAsString
                    ?: return false
                when (val body = value.body) {
                    is ExpressionStmt -> {
                        val creation = unwrap(body.expression) as? ObjectCreationExpr
                            ?: return false
                        creationTransfers(state, creation, infoName)
                    }
                    is BlockStmt -> exactReturnedCreation(state, body, infoName)
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun constructorTransfersInfoToSupplier(
        initialType: String,
        initialArity: Int,
        initialInfoIndex: Int,
        infoType: String,
        componentName: String,
        supplierField: FieldDef,
        states: List<UnitState>,
        typesByName: Map<String, TypeDeclaration<*>>,
        fieldsByKey: Map<FieldKey, FieldDef>,
        parents: Map<String, String>
    ): Boolean {
        data class ConstructorStep(val owner: String, val arity: Int, val infoIndex: Int)

        fun stateFor(owner: String): UnitState? = states.singleOrNull { candidate ->
            candidate.unit.findAll(TypeDeclaration::class.java).any {
                candidate.owner(it) == owner
            }
        }

        val queued = ArrayDeque<ConstructorStep>()
        val seen = mutableSetOf<ConstructorStep>()
        queued.addLast(ConstructorStep(initialType, initialArity, initialInfoIndex))
        while (queued.isNotEmpty()) {
            val step = queued.removeFirst()
            if (!seen.add(step)) continue
            val declaration = typesByName[step.owner] as? ClassOrInterfaceDeclaration
                ?: return false
            val ownerState = stateFor(step.owner) ?: return false
            val constructor = declaration.constructors.singleOrNull { candidate ->
                candidate.parameters.size == step.arity &&
                    candidate.parameters.getOrNull(step.infoIndex)?.let { parameter ->
                        declaredTypeMatches(
                            ownerState, parameter.type.toString(),
                            ownerState.offset(parameter), infoType, typesByName, parents
                        )
                    } == true
            } ?: return false
            val infoName = constructor.parameters[step.infoIndex].nameAsString
            val supplierAssignments = constructor.findAll(AssignExpr::class.java).mapNotNull {
                    assignment ->
                val field = when (val target = unwrap(assignment.target)) {
                    is FieldAccessExpr -> if (target.scope is ThisExpr) {
                        resolveProjectField(
                            step.owner, target.nameAsString, fieldsByKey, parents
                        )
                    } else {
                        resolveFieldExpression(
                            ownerState, target, fieldsByKey, typesByName, parents
                        )
                    }
                    is NameExpr -> resolveUnqualifiedField(
                        ownerState, target, fieldsByKey, parents
                    )
                    else -> null
                }
                assignment.takeIf { field?.key == supplierField.key }
            }
            val stores = supplierAssignments.singleOrNull()?.let { assignment ->
                if (!supplierField.declaration.isFinal ||
                    supplierField.variable.initializer.isPresent ||
                    !mandatoryWithin(assignment, constructor)
                ) return@let false
                val accessor = unwrap(assignment.value) as? MethodCallExpr
                    ?: return@let false
                accessor.nameAsString == componentName &&
                    accessor.arguments.isEmpty() &&
                    accessor.scope.orElse(null)?.let(::expressionName) == infoName
            } == true
            if (stores) return true
            val forwarding = constructor.findAll(ExplicitConstructorInvocationStmt::class.java)
                .singleOrNull() ?: return false
            val forwardedIndex = forwarding.arguments.indexOfFirst {
                expressionName(it) == infoName
            }
            if (forwardedIndex < 0) return false
            val forwardedOwner = if (forwarding.isThis) {
                step.owner
            } else {
                parents[step.owner] ?: return false
            }
            queued.addLast(
                ConstructorStep(
                    forwardedOwner, forwarding.arguments.size, forwardedIndex
                )
            )
        }
        return false
    }

    private fun methodRegistersMandatoryConnectionConsumer(
        root: MethodDef,
        consumerSink: MethodDef,
        states: List<UnitState>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): Boolean {
        val queued = ArrayDeque<MethodDef>()
        val seen = mutableSetOf<MethodKey>()
        queued.addLast(root)
        while (queued.isNotEmpty()) {
            val current = queued.removeFirst()
            if (!seen.add(current.key)) continue
            for (call in current.node.findAll(MethodCallExpr::class.java)) {
                if (!mandatoryWithin(call, current.node)) continue
                val target = resolveProjectMethod(
                    current.state, call, methodsByKey, typesByName, parents
                ) ?: continue
                if (target.key == consumerSink.key) {
                    if (call.arguments.any { argument ->
                            val deferred = unwrap(argument) as? LambdaExpr
                                ?: return@any false
                            projectClosureHasMandatoryConnection(
                                current.state, deferred, states, methodsByKey,
                                typesByName, parents
                            )
                        }
                    ) return true
                } else if (target.key.owner == consumerSink.key.owner) {
                    queued.addLast(target)
                }
            }
        }
        return false
    }

    private fun resolveFieldExpression(
        state: UnitState,
        expression: Expression,
        fieldsByKey: Map<FieldKey, FieldDef>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): FieldDef? {
        return when (val value = unwrap(expression)) {
            is NameExpr -> resolveUnqualifiedField(state, value, fieldsByKey, parents)
            is FieldAccessExpr -> {
                val owner = resolveType(
                    state, value.scope, state.offset(value), typesByName
                ) ?: return null
                resolveProjectField(owner, value.nameAsString, fieldsByKey, parents)
            }
            else -> null
        }
    }

    private fun projectClosureHasMandatoryConnection(
        state: UnitState,
        root: Node,
        states: List<UnitState>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ): Boolean {
        val stateByUnit = states.associateBy { it.unit }
        val queued = ArrayDeque<Pair<UnitState, Node>>()
        val seen = mutableSetOf<MethodKey>()
        queued.addLast(state to root)
        while (queued.isNotEmpty()) {
            val (currentState, currentRoot) = queued.removeFirst()
            if (currentRoot.findAll(MethodCallExpr::class.java).any { call ->
                    mandatoryWithin(call, currentRoot) &&
                        mandatoryConnectionRecipeManager(
                            currentState, call, typesByName, currentRoot
                        )
                }
            ) return true
            currentRoot.findAll(MethodCallExpr::class.java).forEach { call ->
                if (!mandatoryWithin(call, currentRoot)) return@forEach
                val target = resolveProjectMethod(
                    currentState, call, methodsByKey, typesByName, parents
                ) ?: return@forEach
                if (seen.add(target.key)) {
                    val ownerState = stateByUnit[target.state.unit] ?: target.state
                    queued.addLast(ownerState to target.node)
                }
            }
        }
        return false
    }

    private fun mandatoryWithin(node: Node, root: Node): Boolean {
        var current: Node? = node.parentNode.orElse(null)
        while (current != null && current !== root) {
            if (current is BlockStmt) {
                var direct: Node = node
                while (direct.parentNode.orElse(null) !== current) {
                    direct = direct.parentNode.orElse(null) ?: return false
                }
                val statement = direct as? Statement ?: return false
                val index = current.statements.indexOf(statement)
                if (index < 0) return false
                if (current.statements.take(index).any { prior ->
                        prior is IfStmt ||
                            prior is ForStmt ||
                            prior is ForEachStmt ||
                            prior is TryStmt ||
                            containsDirectExit(prior)
                    }
                ) return false
            }
            if (current is IfStmt ||
                current is ForStmt ||
                current is ForEachStmt ||
                current is TryStmt ||
                current is LambdaExpr ||
                current is ObjectCreationExpr && current.anonymousClassBody.isPresent ||
                current is TypeDeclaration<*>
            ) return false
            current = current.parentNode.orElse(null)
        }
        return current === root
    }

    private fun mandatoryAfterNonExitingBranches(node: Node, root: Node): Boolean {
        var current: Node? = node.parentNode.orElse(null)
        while (current != null && current !== root) {
            if (current is BlockStmt) {
                var direct: Node = node
                while (direct.parentNode.orElse(null) !== current) {
                    direct = direct.parentNode.orElse(null) ?: return false
                }
                val statement = direct as? Statement ?: return false
                val index = current.statements.indexOf(statement)
                if (index < 0) return false
                if (current.statements.take(index).any { prior ->
                        prior is ForStmt ||
                            prior is ForEachStmt ||
                            prior is TryStmt ||
                            containsDirectExit(prior)
                    }
                ) return false
            }
            if (current is IfStmt ||
                current is ForStmt ||
                current is ForEachStmt ||
                current is TryStmt ||
                current is LambdaExpr ||
                current is ObjectCreationExpr && current.anonymousClassBody.isPresent ||
                current is TypeDeclaration<*>
            ) return false
            current = current.parentNode.orElse(null)
        }
        return current === root
    }

    private fun containsDirectExit(root: Node): Boolean =
        root.findAll(ReturnStmt::class.java).any { directlyExecutedWithin(it, root) } ||
            root.findAll(ThrowStmt::class.java).any { directlyExecutedWithin(it, root) }

    private fun directlyExecutedWithin(node: Node, root: Node): Boolean {
        var current = node.parentNode.orElse(null)
        while (current != null && current !== root) {
            if (current is LambdaExpr ||
                current is CallableDeclaration<*> ||
                current is TypeDeclaration<*> ||
                current is ObjectCreationExpr && current.anonymousClassBody.isPresent
            ) return false
            current = current.parentNode.orElse(null)
        }
        return current === root
    }

    private fun mandatoryConnectionRecipeManager(
        state: UnitState,
        recipeManager: MethodCallExpr,
        typesByName: Map<String, TypeDeclaration<*>>,
        root: Node
    ): Boolean {
        if (recipeManager.nameAsString != "getRecipeManager" ||
            recipeManager.arguments.isNotEmpty()
        ) return false
        val connection = recipeManager.scope.orElse(null) as? MethodCallExpr ?: return false
        if (connection.nameAsString != "getConnection" ||
            connection.arguments.isNotEmpty()
        ) return false
        val instance = connection.scope.orElse(null) as? MethodCallExpr ?: return false
        if (instance.nameAsString != "getInstance" ||
            instance.arguments.isNotEmpty() ||
            !instance.scope.map {
                resolveType(state, it, state.offset(instance), typesByName) ==
                    "net.minecraft.client.Minecraft"
            }.orElse(false)
        ) return false
        var current: Node? = recipeManager.parentNode.orElse(null)
        while (current != null && current !== root) {
            if (current is IfStmt ||
                current is LambdaExpr ||
                current is ForStmt ||
                current is ForEachStmt ||
                current is TryStmt ||
                current is ObjectCreationExpr && current.anonymousClassBody.isPresent ||
                current is TypeDeclaration<*>
            ) return false
            current = current.parentNode.orElse(null)
        }
        return current === root
    }

    private fun declaredWithin(node: Node, root: TypeDeclaration<*>): Boolean {
        var current = node.findAncestor(TypeDeclaration::class.java).orElse(null)
        while (current != null) {
            if (current === root) return true
            current = current.findAncestor(TypeDeclaration::class.java).orElse(null)
        }
        return false
    }

    private fun ensureAcyclicDependencyGraph(
        dependentMethods: Set<MethodKey>,
        dependentFields: Set<FieldKey>,
        methodUses: List<MethodUse>,
        fieldUses: List<FieldUse>
    ) {
        fun methodNode(key: MethodKey) = "method:${key.owner}#${key.name}/${key.arity}"
        fun fieldNode(key: FieldKey) = "field:${key.owner}#${key.name}"
        fun source(context: Context): String? =
            context.method?.key?.takeIf { it in dependentMethods }?.let(::methodNode)
                ?: context.field?.key?.takeIf { it in dependentFields }?.let(::fieldNode)

        val edges = buildMap<String, MutableSet<String>> {
            dependentMethods.forEach { put(methodNode(it), linkedSetOf()) }
            dependentFields.forEach { put(fieldNode(it), linkedSetOf()) }
        }
        methodUses.forEach { use ->
            val from = source(use.context) ?: return@forEach
            if (use.target.key in dependentMethods) {
                edges.getValue(from) += methodNode(use.target.key)
            }
        }
        fieldUses.forEach { use ->
            val from = source(use.context) ?: return@forEach
            if (use.target.key in dependentFields) {
                edges.getValue(from) += fieldNode(use.target.key)
            }
        }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(value: String) {
            if (value in visited) return
            check(visiting.add(value)) {
                "Provider-dependent brewing initialization cycle is not migrated: $value"
            }
            edges.getValue(value).forEach(::visit)
            visiting.remove(value)
            visited += value
        }
        edges.keys.forEach(::visit)
    }

    private fun ensureClosedPrivateMethodUses(
        dependent: Set<MethodKey>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        uses: List<MethodUse>,
        references: List<MethodReferenceUse>,
        states: List<UnitState>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ) {
        dependent.forEach { key ->
            check(methodsByKey[key]?.singleOrNull() != null) {
                "Provider-dependent method is ambiguous or missing: $key"
            }
            val resolved = uses.filter { it.target.key == key }.map { it.node }.toSet()
            states.forEach { state ->
                state.unit.findAll(MethodCallExpr::class.java).filter {
                    it.nameAsString == key.name &&
                        it.arguments.size == key.arity
                }.forEach { call ->
                    val target = resolveProjectMethod(
                        state, call, methodsByKey, typesByName, parents
                    )
                    if (target?.key == key) {
                        check(call in resolved) {
                            "Provider-dependent call was not indexed: $call in ${state.file}"
                        }
                        return@forEach
                    }
                    if (target != null) return@forEach
                    val possible = if (call.scope.isPresent) {
                        val owner = resolveType(
                            state, call.scope.get(), state.offset(call), typesByName
                        )
                        owner == null || key.owner in projectHierarchy(owner, parents)
                    } else {
                        state.ownerChain(call).any {
                            key.owner in projectHierarchy(it, parents)
                        }
                    }
                    check(!possible) {
                        "Unresolved call to provider-dependent private method " +
                            "${key.owner}#${key.name}/${key.arity}: $call in ${state.file}"
                    }
                }
            }
        }
        references.forEach { reference ->
            val sameName = dependent.filter { it.name == reference.node.identifier }
            if (sameName.isEmpty()) return@forEach
            val possible = reference.owner == null ||
                reference.candidates.any { it.key in dependent } ||
                sameName.any { key ->
                    reference.owner?.let {
                        key.owner in projectHierarchy(it, parents)
                    } == true
                }
            check(!possible) {
                "Method reference ${reference.node} may target a provider-dependent brewing " +
                    "method in ${reference.state.file}; functional arity is not guessed"
            }
        }
    }

    private fun ensureClosedFieldUses(
        dependent: Set<FieldKey>,
        fieldsByKey: Map<FieldKey, FieldDef>,
        uses: List<FieldUse>,
        states: List<UnitState>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ) {
        dependent.forEach { key ->
            val declaration = fieldsByKey.getValue(key)
            val resolved = uses.filter { it.target.key == key }.map { it.node }.toSet()
            val found = uses.count { it.target.key == key }
            check(found > 0) {
                "Provider-dependent field has no proved source use after its initializer: $key"
            }
            check(states.none { state -> state.unit.imports.any {
                    it.isStatic && it.isAsterisk && it.nameAsString == key.owner
                }
            }) {
                "Wildcard static import prevents closed field-use proof for $key"
            }
            states.forEach { state ->
                state.unit.findAll(FieldAccessExpr::class.java)
                    .filter { it.nameAsString == key.name }
                    .forEach { field ->
                        val owner = resolveType(
                            state, field.scope, state.offset(field), typesByName
                        )
                        val target = owner?.let {
                            resolveProjectField(it, key.name, fieldsByKey, parents)
                        }
                        if (target?.key == key) {
                            check(field in resolved) {
                                "Provider-dependent field read was not indexed: $field in ${state.file}"
                            }
                        } else if (target == null) {
                            check(owner != null &&
                                key.owner !in projectHierarchy(owner, parents)
                            ) {
                                "Unresolved read may target provider-dependent field $key: " +
                                    "$field in ${state.file}"
                            }
                        }
                    }
                state.unit.findAll(NameExpr::class.java)
                    .filter { it.nameAsString == key.name }
                    .forEach { name ->
                        val target = resolveUnqualifiedField(
                            state, name, fieldsByKey, parents
                        )
                        if (target?.key == key) {
                            check(name in resolved) {
                                "Provider-dependent field read was not indexed: $name in ${state.file}"
                            }
                            return@forEach
                        }
                        if (target != null) return@forEach
                        val imported = state.unit.imports.any {
                            it.isStatic && (
                                it.isAsterisk && it.nameAsString == key.owner ||
                                    !it.isAsterisk &&
                                    it.nameAsString == "${key.owner}.${key.name}"
                                )
                        }
                        val lexical = state.ownerChain(name).any {
                            key.owner in projectHierarchy(it, parents)
                        }
                        check(
                            (!imported && !lexical) ||
                                shadowedByOtherValue(state, name, declaration)
                        ) {
                            "Unresolved unqualified read may target provider-dependent field " +
                                "$key: $name in ${state.file}"
                        }
                    }
            }
        }
    }

    private fun validateCacheRetentionClosure(
        parser: JavaParser,
        sourceRoot: Path,
        states: List<UnitState>,
        directMethods: Set<MethodKey>,
        dependentFields: Set<FieldKey>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        fieldsByKey: Map<FieldKey, FieldDef>,
        typesByName: Map<String, TypeDeclaration<*>>,
        parents: Map<String, String>
    ) {
        val stateByType = buildMap<String, UnitState> {
            states.forEach { state ->
                state.unit.findAll(TypeDeclaration::class.java).forEach { type ->
                    put(state.owner(type), state)
                }
            }
        }
        val queued = ArrayDeque<Pair<UnitState, Node>>()
        val seen = IdentityHashMap<Node, Boolean>()
        val provedFactoryReceivers =
            mutableMapOf<MethodKey, MutableSet<FieldKey>>()
        val provedSourceEnums = mutableSetOf<String>()
        val generatedPotionHelperProofs = mutableMapOf<String, Boolean>()

        fun queue(state: UnitState, node: Node) {
            if (!seen.containsKey(node)) queued.addLast(state to node)
        }

        fun queueType(owner: String) {
            val declaration = typesByName[owner] ?: return
            val state = stateByType[owner] ?: return
            if (declaration is EnumDeclaration) {
                check(owner in provedSourceEnums) {
                    "Cannot prove PotionBrewing cache lifetime: source enum $owner requires an " +
                        "exact qualified constant with closed instance state"
                }
                return
            }
            declaration.fields.filter { !it.isStatic }.flatMap { it.variables }
                .mapNotNull { it.initializer.orElse(null) }
                .forEach { queue(state, it) }
            if (declaration is ClassOrInterfaceDeclaration) {
                declaration.constructors.forEach { queue(state, it) }
            }
        }

        fun declaredMixElementType(
            state: UnitState,
            expression: Expression
        ): String? {
            val name = unwrap(expression) as? NameExpr ?: return null
            val useOffset = state.offset(name)
            if (useOffset < 0) return null
            val variable = state.unit.findAll(VariableDeclarator::class.java)
                .filter { candidate ->
                    candidate.nameAsString == name.nameAsString &&
                        candidate.parentNode.orElse(null) !is FieldDeclaration &&
                        state.offset(candidate) in 0 until useOffset &&
                        variableScope(candidate)?.let {
                            containsNode(it, name)
                        } == true
                }
                .maxByOrNull(state::offset)
            val parameter = if (variable == null) {
                state.unit.findAll(Parameter::class.java)
                    .filter { candidate ->
                        candidate.nameAsString == name.nameAsString &&
                            parameterScope(candidate)?.let {
                                containsNode(it, name)
                            } == true
                    }
                    .maxByOrNull(state::offset)
            } else {
                null
            }
            val declared = (variable?.type ?: parameter?.type)
                as? ClassOrInterfaceType ?: return null
            if (canonicalRawType(
                    state, declared.toString(), state.offset(declared), typesByName
                ) != POTION_BREWING_MIX
            ) return null
            if (declared.findAll(WildcardType::class.java).isNotEmpty()) return null
            val element = declared.typeArguments.orElse(null)?.singleOrNull()
                ?: return null
            return canonicalRawType(
                state, element.toString(), state.offset(element), typesByName
            )?.takeIf { it == POTION || it == ITEM }
        }

        fun mixHolderValueType(
            state: UnitState,
            call: MethodCallExpr
        ): String? {
            if (call.nameAsString != "value" || call.arguments.isNotEmpty()) return null
            val holder = call.scope.orElse(null)?.let(::unwrap) ?: return null
            val mix = when (holder) {
                is FieldAccessExpr -> if (holder.nameAsString in setOf("from", "to")) {
                    holder.scope
                } else {
                    null
                }
                is MethodCallExpr -> if (
                    holder.nameAsString in setOf("from", "to") &&
                    holder.arguments.isEmpty()
                ) {
                    holder.scope.orElse(null)
                } else {
                    null
                }
                else -> null
            } ?: return null
            return declaredMixElementType(state, mix)
        }

        fun mixIngredientType(
            state: UnitState,
            expression: Expression
        ): String? {
            val value = unwrap(expression)
            val mix = when (value) {
                is FieldAccessExpr -> if (value.nameAsString == "ingredient") {
                    value.scope
                } else {
                    null
                }
                is MethodCallExpr -> if (
                    value.nameAsString == "ingredient" &&
                    value.arguments.isEmpty()
                ) {
                    value.scope.orElse(null)
                } else {
                    null
                }
                else -> null
            } ?: return null
            return INGREDIENT.takeIf {
                declaredMixElementType(state, mix) != null
            }
        }

        fun nativeItemsFieldType(
            state: UnitState,
            field: FieldAccessExpr
        ): String? {
            val owner = resolveType(
                state, field.scope, state.offset(field), typesByName
            ) ?: return null
            if (owner != ITEMS ||
                !state.typing.isTypeName(field.scope.toString(), ITEMS)
            ) return null
            return ITEM.takeIf {
                TargetMembers.fieldDescriptor(ITEMS, field.nameAsString) ==
                    "Lnet/minecraft/world/item/Item;"
            }
        }

        fun nativeCustomDataComponentFieldType(
            state: UnitState,
            field: FieldAccessExpr
        ): String? {
            if (field.nameAsString != "CUSTOM_DATA") return null
            val owner = resolveType(
                state, field.scope, state.offset(field), typesByName
            ) ?: return null
            if (owner != DATA_COMPONENTS ||
                !state.typing.isTypeName(field.scope.toString(), DATA_COMPONENTS)
            ) return null
            // Keep this admission pinned to the target's exact raw and generic field contract.
            if (TargetMembers.fieldDescriptor(DATA_COMPONENTS, field.nameAsString) !=
                "Lnet/minecraft/core/component/DataComponentType;"
            ) return null
            if (TargetMembers.fieldSignatures(DATA_COMPONENTS)[field.nameAsString] !=
                "Lnet/minecraft/core/component/DataComponentType<" +
                "Lnet/minecraft/world/item/component/CustomData;>;"
            ) return null
            return DATA_COMPONENT_TYPE
        }

        fun descriptorReturnType(descriptor: String): String? {
            val raw = descriptor.substringAfter(')', "")
            if (raw.isEmpty() || raw.startsWith("[")) return null
            return when (raw) {
                "Z" -> "boolean"
                "B" -> "byte"
                "C" -> "char"
                "D" -> "double"
                "F" -> "float"
                "I" -> "int"
                "J" -> "long"
                "S" -> "short"
                "V" -> "void"
                else -> raw.takeIf {
                    it.startsWith("L") && it.endsWith(";")
                }?.removePrefix("L")?.removeSuffix(";")
                    ?.replace('/', '.')?.replace('$', '.')
            }
        }

        fun erasedSignatureReturnDescriptor(signature: String): String? {
            val raw = signature.substringAfter(')', "")
            if (raw.isEmpty() || raw.startsWith("[") || raw.startsWith("T")) return null
            if (!raw.startsWith("L")) return raw.take(1)
            val generic = raw.indexOf('<')
            val end = raw.indexOf(';')
            val boundary = listOf(generic, end).filter { it >= 0 }.minOrNull()
                ?: return null
            return raw.substring(0, boundary) + ";"
        }

        fun nativeRetainedTargetReturnType(
            state: UnitState,
            call: MethodCallExpr
        ): String? {
            if (call.arguments.isNotEmpty() || call.typeArguments.isPresent) return null
            val receiver = call.scope.orElse(null)?.let(::unwrap) ?: return null
            val owner = if (receiver is MethodCallExpr) {
                nativeRetainedTargetReturnType(state, receiver)
            } else {
                resolveType(state, receiver, state.offset(call), typesByName)
            } ?: return null
            if (!TargetMembers.hasClass(owner)) return null

            val descriptors = TargetMembers.methodsNamed(call.nameAsString)
                .asSequence()
                .map { it.second }
                .filter { it.startsWith("()") }
                .distinct()
                .mapNotNull { descriptor ->
                    TargetMembers.inheritedMethod(
                        owner, call.nameAsString, descriptor
                    )?.let { descriptor to it }
                }
                .filter { (_, method) -> method.access == "public" }
                .toList()
            if (descriptors.isEmpty()) return null

            val returnTypes = descriptors.map { (descriptor, method) ->
                val rawReturn = descriptor.substringAfter(')', "")
                if (method.signature.isNotEmpty() &&
                    erasedSignatureReturnDescriptor(method.signature) != rawReturn
                ) return null
                descriptorReturnType(descriptor)?.takeIf {
                    it in retainedPlatformDataTypes ||
                        it in primitiveValueTypes && it != "void"
                } ?: return null
            }
            return returnTypes.distinct().singleOrNull()
        }

        fun closedEnumScalar(expression: Expression): Boolean =
            when (val value = unwrap(expression)) {
                is BooleanLiteralExpr,
                is CharLiteralExpr,
                is DoubleLiteralExpr,
                is IntegerLiteralExpr,
                is LongLiteralExpr -> true
                is CastExpr -> value.type.isPrimitiveType &&
                    closedEnumScalar(value.expression)
                is UnaryExpr -> value.operator in setOf(
                    UnaryExpr.Operator.PLUS,
                    UnaryExpr.Operator.MINUS,
                    UnaryExpr.Operator.LOGICAL_COMPLEMENT,
                    UnaryExpr.Operator.BITWISE_COMPLEMENT
                ) && closedEnumScalar(value.expression)
                is BinaryExpr ->
                    closedEnumScalar(value.left) && closedEnumScalar(value.right)
                is ConditionalExpr ->
                    closedEnumScalar(value.condition) &&
                        closedEnumScalar(value.thenExpr) &&
                        closedEnumScalar(value.elseExpr)
                else -> false
            }

        fun resolvedSourceEnumConstantType(
            state: UnitState,
            field: FieldAccessExpr
        ): String? {
            val owner = resolveType(
                state, field.scope, state.offset(field), typesByName
            ) ?: return null
            val declaration = typesByName[owner] as? EnumDeclaration ?: return null
            if (!state.typing.isTypeName(field.scope.toString(), owner)) return null
            val constant = declaration.entries.singleOrNull {
                it.nameAsString == field.nameAsString
            } ?: return null
            return owner
        }

        fun richEnumValueType(
            state: UnitState,
            expression: Expression
        ): String? = when (val value = unwrap(expression)) {
            is NameExpr -> {
                val field = resolveUnqualifiedField(
                    state, value, fieldsByKey, parents
                )
                if (field != null) {
                    canonicalRawType(
                        field.state, field.variable.type.toString(),
                        field.state.offset(field.variable), typesByName
                    )
                } else {
                    val (declared, type) = declaredLexicalValueType(
                        state, value, typesByName
                    )
                    if (declared) type else state.typing.typeOf(
                        value.toString(), state.offset(value)
                    )
                }
            }
            is FieldAccessExpr -> {
                val field = resolveFieldExpression(
                    state, value, fieldsByKey, typesByName, parents
                )
                if (field != null) {
                    canonicalRawType(
                        field.state, field.variable.type.toString(),
                        field.state.offset(field.variable), typesByName
                    )
                } else {
                    state.typing.typeOf(value.toString(), state.offset(value))
                        ?: resolveType(
                            state, value.scope, state.offset(value), typesByName
                        )?.let { targetOwner ->
                            TargetMembers.fieldDescriptor(
                                targetOwner, value.nameAsString
                            )
                        }?.let { descriptor ->
                            descriptorReturnType("()$descriptor")
                        }
                }
            }
            is MethodCallExpr -> {
                val target = resolveProjectMethod(
                    state, value, methodsByKey, typesByName, parents
                )
                if (target != null) {
                    canonicalRawType(
                        target.state, target.node.type.toString(),
                        target.state.offset(target.node), typesByName
                    )
                } else {
                    state.typing.typeOf(value.toString(), state.offset(value))
                }
            }
            is ObjectCreationExpr -> canonicalRawType(
                state, value.type.toString(), state.offset(value), typesByName
            )
            is ThisExpr -> state.owner(value)
            else -> state.typing.typeOf(value.toString(), state.offset(value))
        }

        fun richSourceTypeTreeClosed(
            state: UnitState,
            root: Node,
            enumOwner: String
        ): Boolean {
            for (type in root.findAll(ClassOrInterfaceType::class.java)) {
                val spelling = type.nameWithScope
                val nestedOwnerQualifier =
                    (type.parentNode.orElse(null) as? ClassOrInterfaceType)
                        ?.scope?.map { it === type }?.orElse(false) == true
                if (nestedOwnerQualifier &&
                    spelling.substringBefore('.').firstOrNull()?.isLowerCase() == true
                ) {
                    continue
                }
                val offset = state.offset(type)
                if (hasVisibleTypeParameter(state, type.nameAsString, offset)) continue
                val canonical = resolveNamedType(
                    state, spelling, offset, typesByName
                ) ?: spelling.substringAfterLast('.')
                    .takeIf { it in implicitJavaLangTypes }
                    ?.let { "java.lang.$it" }
                    ?: return false
                if (canonical == "java.lang.Object") return false
                val forbidden = forbiddenRetentionTypes.firstOrNull {
                    canonicalInherits(canonical, it, parents)
                }
                check(forbidden == null) {
                    "Cannot prove PotionBrewing cache lifetime: rich source enum $enumOwner " +
                        "declares retained owner type $canonical " +
                        "(forbidden owner $forbidden)"
                }
            }
            return true
        }

        fun proveRichSourceEnumState(
            owner: String,
            declaration: EnumDeclaration
        ): Boolean {
            val ownerState = stateByType[owner] ?: return false
            val constructors =
                declaration.members.filterIsInstance<ConstructorDeclaration>()
            val unbound = declaration.entries.firstOrNull { entry ->
                if (constructors.isEmpty()) {
                    entry.arguments.isNotEmpty()
                } else {
                    constructors.none { constructor ->
                        !constructor.parameters.any { it.isVarArgs } &&
                            constructor.parameters.size == entry.arguments.size
                    }
                }
            }
            if (unbound != null) return false

            val richQueued = ArrayDeque<Pair<UnitState, Node>>()
            val richSeen = IdentityHashMap<Node, Boolean>()
            val queuedSourceTypes = mutableSetOf<String>()

            fun queueRich(state: UnitState, node: Node) {
                if (!richSeen.containsKey(node)) richQueued.addLast(state to node)
            }

            fun assignmentsTo(field: FieldDef): List<Pair<UnitState, AssignExpr>> =
                states.flatMap { candidate ->
                    candidate.unit.findAll(AssignExpr::class.java).mapNotNull { assignment ->
                        val target = when (val assigned = unwrap(assignment.target)) {
                            is NameExpr -> resolveUnqualifiedField(
                                candidate, assigned, fieldsByKey, parents
                            )
                            is FieldAccessExpr -> resolveFieldExpression(
                                candidate, assigned, fieldsByKey, typesByName, parents
                            )
                            else -> null
                        }
                        (candidate to assignment).takeIf {
                            target?.key == field.key
                        }
                    }
                }

            fun queueSourceState(sourceOwner: String): Boolean {
                if (!queuedSourceTypes.add(sourceOwner)) return true
                val source = typesByName[sourceOwner] ?: return false
                val sourceState = stateByType[sourceOwner] ?: return false
                if (source is EnumDeclaration && sourceOwner != owner) return false
                val fields = source.fields.filter { !it.isStatic }
                if (fields.any { !it.isFinal }) return false
                for (member in fields) {
                    for (variable in member.variables) {
                        if (!richSourceTypeTreeClosed(
                                sourceState, variable.type, owner
                            )
                        ) return false
                        val field = fieldsByKey[
                            FieldKey(sourceOwner, variable.nameAsString)
                        ] ?: return false
                        val assignments = assignmentsTo(field)
                        if (!variable.initializer.isPresent && assignments.isEmpty()) {
                            return false
                        }
                        if (assignments.any { (assignmentState, assignment) ->
                                val constructor = assignment.findAncestor(
                                    ConstructorDeclaration::class.java
                                ).orElse(null)
                                constructor == null ||
                                    assignmentState.owner(constructor) != sourceOwner
                            }
                        ) return false
                        variable.initializer.orElse(null)?.let {
                            queueRich(sourceState, it)
                        }
                        assignments.forEach { (assignmentState, assignment) ->
                            queueRich(assignmentState, assignment.value)
                        }
                    }
                }
                when (source) {
                    is ClassOrInterfaceDeclaration ->
                        source.constructors.forEach { queueRich(sourceState, it) }
                    is EnumDeclaration ->
                        source.members.filterIsInstance<ConstructorDeclaration>()
                            .forEach { queueRich(sourceState, it) }
                }
                return true
            }

            if (!queueSourceState(owner)) return false
            declaration.entries.flatMap { it.arguments }
                .forEach { queueRich(ownerState, it) }

            while (richQueued.isNotEmpty()) {
                val (state, root) = richQueued.removeFirst()
                if (richSeen.put(root, true) != null) continue
                if (!richSourceTypeTreeClosed(state, root, owner)) return false

                val expressions = buildList<Expression> {
                    addAll(root.findAll(NameExpr::class.java))
                    addAll(root.findAll(FieldAccessExpr::class.java))
                    addAll(root.findAll(MethodCallExpr::class.java))
                    addAll(root.findAll(ObjectCreationExpr::class.java))
                    addAll(root.findAll(ThisExpr::class.java))
                }
                for (expression in expressions) {
                    val retained = richEnumValueType(state, expression)
                    if (retained == "java.lang.Object") return false
                    val forbidden = retained?.let { type ->
                        forbiddenRetentionTypes.firstOrNull {
                            canonicalInherits(type, it, parents)
                        }
                    }
                    check(forbidden == null) {
                        "Cannot prove PotionBrewing cache lifetime: rich source enum $owner " +
                            "initializer retains $retained through $expression " +
                            "(forbidden owner $forbidden)"
                    }
                    if (expression is NameExpr &&
                        expression.findAncestor(LambdaExpr::class.java).isPresent &&
                        retained == null &&
                        resolveType(
                            state, expression, state.offset(expression), typesByName
                        ) == null
                    ) {
                        return false
                    }
                }

                val referencedFields = buildList<FieldDef> {
                    root.findAll(NameExpr::class.java).mapNotNullTo(this) {
                        resolveUnqualifiedField(state, it, fieldsByKey, parents)
                    }
                    root.findAll(FieldAccessExpr::class.java).mapNotNullTo(this) {
                        resolveFieldExpression(
                            state, it, fieldsByKey, typesByName, parents
                        )
                    }
                }.distinctBy { it.key }
                for (field in referencedFields) {
                    if (field.declaration.isStatic) {
                        if (!field.declaration.isFinal ||
                            !richSourceTypeTreeClosed(
                                field.state, field.variable.type, owner
                            )
                        ) return false
                        val initializer = field.variable.initializer.orElse(null)
                            ?: return false
                        queueRich(field.state, initializer)
                    } else if (field.key.owner !in queuedSourceTypes &&
                        !queueSourceState(field.key.owner)
                    ) {
                        return false
                    }
                }

                for (call in root.findAll(MethodCallExpr::class.java)) {
                    val target = resolveProjectMethod(
                        state, call, methodsByKey, typesByName, parents
                    ) ?: continue
                    if (!target.node.body.isPresent) return false
                    queueRich(target.state, target.node)
                }

                for (creation in root.findAll(ObjectCreationExpr::class.java)) {
                    if (creation.anonymousClassBody.isPresent) return false
                    val created = canonicalRawType(
                        state, creation.type.toString(),
                        state.offset(creation), typesByName
                    ) ?: return false
                    if (created in typesByName && !queueSourceState(created)) {
                        return false
                    }
                }

                for (reference in root.findAll(MethodReferenceExpr::class.java)) {
                    val scope = unwrap(reference.scope)
                    val scopeOwner = resolveType(
                        state, scope, state.offset(reference), typesByName
                    ) ?: return false
                    val forbidden = forbiddenRetentionTypes.firstOrNull {
                        canonicalInherits(scopeOwner, it, parents)
                    }
                    check(forbidden == null) {
                        "Cannot prove PotionBrewing cache lifetime: rich source enum $owner " +
                            "method reference $reference uses forbidden owner $forbidden"
                    }
                    if (!state.typing.isTypeName(scope.toString(), scopeOwner)) {
                        val captured = richEnumValueType(state, scope)
                        if (captured == null || captured == "java.lang.Object") return false
                        val capturedForbidden = forbiddenRetentionTypes.firstOrNull {
                            canonicalInherits(captured, it, parents)
                        }
                        check(capturedForbidden == null) {
                            "Cannot prove PotionBrewing cache lifetime: rich source enum " +
                                "$owner method reference $reference captures $captured " +
                                "(forbidden owner $capturedForbidden)"
                        }
                    }
                }
            }
            return true
        }

        fun proveClosedSourceEnum(owner: String): String {
            if (owner in provedSourceEnums) return owner
            val declaration = typesByName[owner] as? EnumDeclaration
                ?: error("Cannot prove PotionBrewing cache lifetime: $owner is not a source enum")
            val anonymous = declaration.entries.firstOrNull {
                it.classBody.isNotEmpty()
            }
            check(anonymous == null) {
                "Cannot prove PotionBrewing cache lifetime: source enum constant " +
                    "$owner.${anonymous?.nameAsString} has an anonymous class body"
            }
            val primitiveState =
                declaration.fields.filter { !it.isStatic }.all { member ->
                    member.variables.all { it.type.isPrimitiveType }
                }
            val constructors = declaration.members.filterIsInstance<ConstructorDeclaration>()
            if (primitiveState) {
                if (constructors.isEmpty()) {
                    val unbound = declaration.entries.firstOrNull {
                        it.arguments.isNotEmpty()
                    }
                    check(unbound == null) {
                        "Cannot prove PotionBrewing cache lifetime: source enum constant " +
                            "$owner.${unbound?.nameAsString} has unbound constructor arguments"
                    }
                } else {
                    val unproved = declaration.entries.firstOrNull { entry ->
                        val constructor = constructors.singleOrNull {
                            !it.parameters.any { parameter -> parameter.isVarArgs } &&
                                it.parameters.size == entry.arguments.size
                        }
                        constructor == null ||
                            constructor.parameters.any { !it.type.isPrimitiveType } ||
                            entry.arguments.any { !closedEnumScalar(it) }
                    }
                    check(unproved == null) {
                        "Cannot prove PotionBrewing cache lifetime: source enum constant " +
                            "$owner.${unproved?.nameAsString} does not have one closed primitive " +
                            "constructor"
                    }
                }
            } else {
                check(proveRichSourceEnumState(owner, declaration)) {
                    "Cannot prove PotionBrewing cache lifetime: source enum $owner does not have " +
                        "closed primitive instance state or proved final singleton state"
                }
            }
            provedSourceEnums += owner
            return owner
        }

        fun sourceEnumConstantType(
            state: UnitState,
            field: FieldAccessExpr
        ): String? = resolvedSourceEnumConstantType(state, field)
            ?.let(::proveClosedSourceEnum)

        fun belongsToMethod(node: Node, method: MethodDeclaration): Boolean {
            var current = node.parentNode.orElse(null)
            while (current != null) {
                if (current === method) return true
                if (current is LambdaExpr || current is CallableDeclaration<*>) return false
                current = current.parentNode.orElse(null)
            }
            return false
        }

        fun exactEnumReturnOwner(
            state: UnitState,
            expression: Expression
        ): String? = when (val value = unwrap(expression)) {
            is FieldAccessExpr -> resolvedSourceEnumConstantType(state, value)
            is ConditionalExpr -> {
                val thenOwner = exactEnumReturnOwner(state, value.thenExpr)
                val elseOwner = exactEnumReturnOwner(state, value.elseExpr)
                thenOwner?.takeIf { it == elseOwner }
            }
            else -> null
        }

        fun exactProjectEnumReturnType(
            state: UnitState,
            call: MethodCallExpr
        ): String? {
            val target = resolveProjectMethod(
                state, call, methodsByKey, typesByName, parents
            ) ?: return null
            val declared = canonicalRawType(
                target.state, target.node.type.toString(),
                target.state.offset(target.node), typesByName
            ) ?: return null
            if (typesByName[declared] !is EnumDeclaration) return null
            val body = target.node.body.orElse(null) ?: return null
            val returns = body.findAll(ReturnStmt::class.java)
                .filter { belongsToMethod(it, target.node) }
            if (returns.isEmpty() || returns.any {
                    it.expression.orElse(null)?.let { value ->
                        exactEnumReturnOwner(target.state, value)
                    } != declared
                }
            ) return null
            return declared
        }

        fun exactProjectCallReturnType(
            state: UnitState,
            call: MethodCallExpr
        ): String? {
            val target = resolveProjectMethod(
                state, call, methodsByKey, typesByName, parents
            ) ?: return null
            return canonicalRawType(
                target.state, target.node.type.toString(),
                target.state.offset(target.node), typesByName
            )
        }

        fun nameValueType(
            state: UnitState,
            value: NameExpr
        ): String? {
            val field = resolveUnqualifiedField(
                state, value, fieldsByKey, parents
            )
            if (field != null) {
                return canonicalRawType(
                    field.state, field.variable.type.toString(),
                    field.state.offset(field.variable), typesByName
                )
            }
            val (declared, type) = declaredLexicalValueType(
                state, value, typesByName
            )
            if (declared) return type
            return state.typing.typeOf(value.toString(), state.offset(value))
        }

        fun fieldValueType(
            state: UnitState,
            value: FieldAccessExpr
        ): String? {
            val field = resolveFieldExpression(
                state, value, fieldsByKey, typesByName, parents
            )
            if (field != null) {
                return canonicalRawType(
                    field.state, field.variable.type.toString(),
                    field.state.offset(field.variable), typesByName
                )
            }
            return state.typing.typeOf(value.toString(), state.offset(value))
        }

        fun nativeCustomDataFactoryOwner(
            state: UnitState,
            call: MethodCallExpr
        ): Boolean {
            val scope = call.scope.orElse(null)?.let(::unwrap) ?: return false
            return resolveType(
                state, scope, state.offset(call), typesByName
            ) == CUSTOM_DATA_TYPE &&
                state.typing.isTypeName(scope.toString(), CUSTOM_DATA_TYPE)
        }

        fun nativeCustomDataFactoryArgumentType(
            state: UnitState,
            expression: Expression
        ): String? = when (val value = unwrap(expression)) {
            is NameExpr -> nameValueType(state, value)
            is FieldAccessExpr -> fieldValueType(state, value)
            is ObjectCreationExpr -> canonicalRawType(
                state, value.type.toString(), state.offset(value), typesByName
            )
            is MethodCallExpr -> exactProjectCallReturnType(state, value) ?:
                nativeRetainedTargetReturnType(state, value) ?:
                state.typing.typeOf(value.toString(), state.offset(value))
            else -> state.typing.typeOf(value.toString(), state.offset(value))
        }

        fun loadGeneratedPotionHelperProof(owner: String): Boolean {
            val normalizedRoot = sourceRoot.toAbsolutePath().normalize()
            val helper = normalizedRoot
                .resolve(owner.replace('.', '/') + ".java")
                .normalize()
            if (!helper.startsWith(normalizedRoot) || !Files.isRegularFile(helper)) {
                return false
            }
            val relative = normalizedRoot.relativize(helper)
                .toString()
                .replace('\\', '/')
            if (!relative.startsWith("com/modporter/generated/") ||
                !relative.endsWith("/compat/LegacyPotionUtils.java") ||
                states.any { it.file.toAbsolutePath().normalize() == helper } ||
                owner in typesByName
            ) {
                return false
            }
            val packageName = owner.substringBeforeLast('.', "")
            if (packageName.isEmpty() ||
                "$packageName.LegacyPotionUtils" != owner
            ) {
                return false
            }
            val template = LegacyPotionBrewingInstances::class.java
                .getResource(POTION_UTILS_TEMPLATE)
                ?.readText()
                ?.replace("@PACKAGE@", packageName)
                ?: return false
            val source = runCatching { helper.readText() }.getOrNull()
                ?: return false
            if (source != template) return false
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful) return false
            val unit = parsed.result.orElse(null) ?: return false
            if (unit.packageDeclaration.map {
                    it.nameAsString
                }.orElse("") != packageName
            ) {
                return false
            }
            val helperType = unit.types.singleOrNull()
                as? ClassOrInterfaceDeclaration ?: return false
            if (helperType.nameAsString != "LegacyPotionUtils" ||
                !helperType.isPublic ||
                !helperType.isFinal ||
                helperType.isInterface
            ) {
                return false
            }
            val method = helperType.methods.singleOrNull { candidate ->
                candidate.nameAsString == "getCustomEffects" &&
                    candidate.parameters.size == 1 &&
                    candidate.parameters.single().type.toString() == "ItemStack"
            } ?: return false
            if (!method.isPublic ||
                !method.isStatic ||
                method.type.toString() != "List<MobEffectInstance>" ||
                method.parameters.single().nameAsString != "stack" ||
                "java.util.List" !in cacheValueTypes ||
                ITEM_STACK !in retainedPlatformDataTypes ||
                MOB_EFFECT_INSTANCE !in retainedPlatformDataTypes
            ) {
                return false
            }
            val returned = method.body.orElse(null)
                ?.statements
                ?.singleOrNull() as? ReturnStmt ?: return false
            val creation = returned.expression.orElse(null)
                ?.let(::unwrap) as? ObjectCreationExpr ?: return false
            if (creation.type.nameWithScope != "ArrayList" ||
                creation.arguments.size != 1 ||
                creation.anonymousClassBody.isPresent
            ) {
                return false
            }
            val customEffects = unwrap(creation.arguments.single())
                as? MethodCallExpr ?: return false
            if (customEffects.nameAsString != "customEffects" ||
                customEffects.arguments.isNotEmpty() ||
                customEffects.typeArguments.isPresent
            ) {
                return false
            }
            val getOrDefault = customEffects.scope.orElse(null)
                ?.let(::unwrap) as? MethodCallExpr ?: return false
            if (getOrDefault.nameAsString != "getOrDefault" ||
                getOrDefault.arguments.size != 2 ||
                getOrDefault.typeArguments.isPresent ||
                getOrDefault.scope.orElse(null)
                    ?.let(::unwrap)
                    ?.let { it as? NameExpr }
                    ?.nameAsString != "stack" ||
                getOrDefault.arguments[0].toString() !=
                    "DataComponents.POTION_CONTENTS" ||
                getOrDefault.arguments[1].toString() !=
                    "PotionContents.EMPTY"
            ) {
                return false
            }
            return true
        }

        fun generatedPotionHelperReturnType(
            state: UnitState,
            call: MethodCallExpr
        ): String? {
            if (call.nameAsString != "getCustomEffects" ||
                call.typeArguments.isPresent ||
                call.arguments.size != 1 ||
                nativeCustomDataFactoryArgumentType(
                    state, call.arguments.single()
                ) != ITEM_STACK
            ) {
                return null
            }
            val scope = call.scope.orElse(null)?.let(::unwrap) ?: return null
            val owner = resolveType(
                state, scope, state.offset(call), typesByName
            ) ?: return null
            if (!state.typing.isTypeName(scope.toString(), owner) ||
                !generatedPotionHelperProofs.getOrPut(owner) {
                    loadGeneratedPotionHelperProof(owner)
                }
            ) {
                return null
            }
            return "java.util.List"
        }

        fun nativeCustomDataFactoryReturnType(
            state: UnitState,
            call: MethodCallExpr
        ): String? {
            if (call.nameAsString != "of" ||
                call.typeArguments.isPresent ||
                call.arguments.size != 1 ||
                !nativeCustomDataFactoryOwner(state, call) ||
                COMPOUND_TAG !in retainedPlatformDataTypes ||
                CUSTOM_DATA_TYPE !in retainedPlatformDataTypes ||
                nativeCustomDataFactoryArgumentType(
                    state, call.arguments.single()
                ) != COMPOUND_TAG
            ) return null
            val descriptor =
                "(Lnet/minecraft/nbt/CompoundTag;)" +
                    "Lnet/minecraft/world/item/component/CustomData;"
            if (descriptor !in TargetMembers.methodDescriptors(
                    CUSTOM_DATA_TYPE, call.nameAsString
                )
            ) return null
            val method = TargetMembers.inheritedMethod(
                CUSTOM_DATA_TYPE, call.nameAsString, descriptor
            ) ?: return null
            return CUSTOM_DATA_TYPE.takeIf {
                method.owner == CUSTOM_DATA_TYPE &&
                    method.access == "public" &&
                    method.signature.isEmpty()
            }
        }

        fun provedSourceEnumReceiverType(
            state: UnitState,
            expression: Expression
        ): String? {
            val value = unwrap(expression)
            val type = when (value) {
                is FieldAccessExpr -> sourceEnumConstantType(state, value) ?:
                    fieldValueType(state, value)
                is MethodCallExpr -> exactProjectCallReturnType(state, value)
                is NameExpr -> nameValueType(state, value)
                else -> null
            }
            return type?.takeIf {
                it in provedSourceEnums && typesByName[it] is EnumDeclaration
            }
        }

        fun inheritedJavaEnumNameType(
            state: UnitState,
            call: MethodCallExpr
        ): String? {
            if (call.nameAsString != "name" ||
                call.arguments.isNotEmpty() ||
                call.typeArguments.isPresent
            ) return null
            val receiver = call.scope.orElse(null) ?: return null
            // A Java EnumDeclaration inherits the final public
            // java.lang.Enum#name()Ljava/lang/String; contract.
            return "java.lang.String".takeIf {
                provedSourceEnumReceiverType(state, receiver) != null
            }
        }

        directMethods.forEach { key ->
            methodsByKey[key]?.singleOrNull()?.let { queue(it.state, it.node) }
        }
        dependentFields.forEach { key ->
            fieldsByKey[key]?.let { field ->
                field.variable.initializer.orElse(null)?.let {
                    queue(field.state, it)
                }
            }
        }

        fun retainedType(
            state: UnitState,
            expression: Expression
        ): String? = when (val value = unwrap(expression)) {
            is NullLiteralExpr -> null
            is ObjectCreationExpr -> canonicalRawType(
                state, value.type.toString(), state.offset(value), typesByName
            )
            is NameExpr -> nameValueType(state, value)
            is FieldAccessExpr -> mixIngredientType(state, value) ?:
                nativeItemsFieldType(state, value) ?:
                nativeCustomDataComponentFieldType(state, value) ?:
                sourceEnumConstantType(state, value) ?:
                fieldValueType(state, value)
            is MethodCallExpr -> if (
                value.nameAsString == "toString" && value.arguments.isEmpty()
            ) {
                "java.lang.String"
            } else if (mixHolderValueType(state, value) != null) {
                mixHolderValueType(state, value)
            } else if (mixIngredientType(state, value) != null) {
                mixIngredientType(state, value)
            } else if (inheritedJavaEnumNameType(state, value) != null) {
                inheritedJavaEnumNameType(state, value)
            } else if (
                value.nameAsString == "save" && value.arguments.isEmpty() &&
                value.scope.orElse(null)?.let {
                    resolveType(state, it, state.offset(value), typesByName)
                } == "net.minecraft.world.effect.MobEffectInstance"
            ) {
                "net.minecraft.nbt.CompoundTag"
            } else {
                val projectCall = resolveProjectMethod(
                    state, value, methodsByKey, typesByName, parents
                )
                if (projectCall != null) {
                    canonicalRawType(
                        projectCall.state, projectCall.node.type.toString(),
                        projectCall.state.offset(projectCall.node), typesByName
                    )
                } else {
                    generatedPotionHelperReturnType(state, value) ?:
                        nativeCustomDataFactoryReturnType(state, value) ?:
                        if (value.nameAsString == "of" &&
                            nativeCustomDataFactoryOwner(state, value)
                        ) {
                            null
                        } else {
                            nativeRetainedTargetReturnType(state, value) ?:
                                state.typing.typeOf(
                                    value.toString(), state.offset(value)
                                )
                        }
                }
            }
            is BinaryExpr -> {
                val left = retainedType(state, value.left)
                val right = retainedType(state, value.right)
                when {
                    value.operator == BinaryExpr.Operator.PLUS &&
                        (left == "java.lang.String" || right == "java.lang.String") ->
                        "java.lang.String"
                    value.operator in setOf(
                        BinaryExpr.Operator.AND,
                        BinaryExpr.Operator.OR,
                        BinaryExpr.Operator.EQUALS,
                        BinaryExpr.Operator.NOT_EQUALS,
                        BinaryExpr.Operator.LESS,
                        BinaryExpr.Operator.LESS_EQUALS,
                        BinaryExpr.Operator.GREATER,
                        BinaryExpr.Operator.GREATER_EQUALS
                    ) -> "boolean"
                    left == right -> left
                    else -> null
                }
            }
            else -> state.typing.typeOf(value.toString(), state.offset(value))
        }

        fun knownCollectionFactory(state: UnitState, call: MethodCallExpr): Boolean {
            val owner = call.scope.orElse(null)?.let {
                resolveType(state, it, state.offset(call), typesByName)
            } ?: return false
            return when (owner) {
                "java.util.List", "java.util.Set" -> call.nameAsString == "of"
                "java.util.Map" -> call.nameAsString in setOf("of", "ofEntries")
                "com.google.common.collect.ImmutableList",
                "com.google.common.collect.ImmutableSet" ->
                    call.nameAsString in setOf("of", "copyOf")
                "com.google.common.collect.ImmutableMap" ->
                    call.nameAsString in setOf("of", "copyOf")
                "net.minecraft.core.NonNullList" ->
                    call.nameAsString in setOf("create", "of", "withSize")
                "java.util.Collections" -> call.nameAsString in setOf(
                    "emptyList", "emptyMap", "emptySet",
                    "singleton", "singletonList", "singletonMap",
                    "unmodifiableCollection", "unmodifiableList",
                    "unmodifiableMap", "unmodifiableSet"
                )
                else -> false
            }
        }

        fun provedFactoryReceiver(
            state: UnitState,
            call: MethodCallExpr,
            target: MethodDef
        ): Boolean {
            val field = call.scope.orElse(null)?.let {
                resolveFieldExpression(
                    state, it, fieldsByKey, typesByName, parents
                )
            } ?: return false
            return field.key in provedFactoryReceivers[target.key].orEmpty()
        }

        fun sourceAssignmentsTo(field: FieldDef): List<AssignExpr> =
            states.flatMap { candidate ->
                candidate.unit.findAll(AssignExpr::class.java).filter { assignment ->
                    val target = when (val assigned = unwrap(assignment.target)) {
                        is NameExpr -> resolveUnqualifiedField(
                            candidate, assigned, fieldsByKey, parents
                        )
                        is FieldAccessExpr -> resolveFieldExpression(
                            candidate, assigned, fieldsByKey, typesByName, parents
                        )
                        else -> null
                    }
                    target?.key == field.key
                }
            }

        fun localDefinitions(
            state: UnitState,
            name: NameExpr
        ): List<Expression> {
            val useOffset = state.offset(name)
            if (useOffset < 0) return emptyList()
            val variable = state.unit.findAll(VariableDeclarator::class.java)
                .filter { candidate ->
                    candidate.nameAsString == name.nameAsString &&
                        candidate.parentNode.orElse(null) !is FieldDeclaration &&
                        state.offset(candidate) in 0 until useOffset &&
                        variableScope(candidate)?.let { containsNode(it, name) } == true
                }
                .maxByOrNull(state::offset)
            val parameter = if (variable == null) {
                state.unit.findAll(Parameter::class.java)
                    .filter { candidate ->
                        candidate.nameAsString == name.nameAsString &&
                            parameterScope(candidate)?.let {
                                containsNode(it, name)
                            } == true
                    }
                    .maxByOrNull(state::offset)
            } else {
                null
            }
            val scope = variable?.let(::variableScope)
                ?: parameter?.let(::parameterScope)
                ?: return emptyList()
            val start = variable?.let(state::offset)
                ?: parameter?.let(state::offset)
                ?: return emptyList()
            val definitions = mutableListOf<Expression>()
            variable?.initializer?.orElse(null)?.let(definitions::add)
            if (variable != null) {
                variable.findAncestor(ForEachStmt::class.java)
                    .filter { loop ->
                        loop.variable.variables.any { it === variable }
                    }
                    .map(ForEachStmt::getIterable)
                    .filter { iterable ->
                        when (val source = unwrap(iterable)) {
                            is NameExpr -> {
                                resolveUnqualifiedField(
                                    state, source, fieldsByKey, parents
                                ) != null ||
                                    state.unit.findAll(VariableDeclarator::class.java)
                                        .any { declaration ->
                                            declaration !== variable &&
                                                declaration.nameAsString ==
                                                source.nameAsString &&
                                                state.offset(declaration) in
                                                0 until state.offset(iterable) &&
                                                variableScope(declaration)?.let {
                                                    containsNode(it, iterable)
                                                } == true
                                        } ||
                                    state.unit.findAll(Parameter::class.java)
                                        .any { declaration ->
                                            declaration.nameAsString ==
                                                source.nameAsString &&
                                                parameterScope(declaration)?.let {
                                                    containsNode(it, iterable)
                                                } == true
                                        }
                            }
                            is FieldAccessExpr -> resolveFieldExpression(
                                state, source, fieldsByKey, typesByName, parents
                            ) != null
                            else -> false
                        }
                    }
                    .ifPresent(definitions::add)
            }
            scope.findAll(AssignExpr::class.java)
                .filter { assignment ->
                    state.offset(assignment) in start until useOffset &&
                        (unwrap(assignment.target) as? NameExpr)
                            ?.nameAsString == name.nameAsString
                }
                .map(AssignExpr::getValue)
                .forEach(definitions::add)
            return definitions.distinctBy { System.identityHashCode(it) }
        }

        fun validateRetained(
            state: UnitState,
            expression: Expression,
            reason: String,
            expectedType: String? = null
        ) {
            val value = unwrap(expression)
            if (value is NullLiteralExpr) return
            if (value is NameExpr) {
                localDefinitions(state, value).forEach {
                    queue(state, it)
                }
            }
            val sourceField = when (value) {
                is NameExpr -> resolveUnqualifiedField(
                    state, value, fieldsByKey, parents
                )
                is FieldAccessExpr -> resolveFieldExpression(
                    state, value, fieldsByKey, typesByName, parents
                )
                else -> null
            }
            sourceField?.variable?.initializer?.orElse(null)?.let {
                queue(sourceField.state, it)
            }
            if (value is MethodReferenceExpr && value.identifier == "new") {
                val owner = resolveType(
                    state, value.scope, state.offset(value), typesByName
                )
                check(owner != null && owner in typesByName) {
                    "Cannot prove PotionBrewing cache lifetime: opaque constructor reference " +
                        "$value ($reason)"
                }
                queueType(owner)
                return
            }
            check(value !is LambdaExpr) {
                "Cannot prove PotionBrewing cache lifetime: retained lambda may hide an " +
                    "unknown captured object graph ($reason)"
            }
            (value as? MethodCallExpr)
                ?.takeIf { nativeCustomDataFactoryReturnType(state, it) != null }
                ?.arguments
                ?.forEach {
                    validateRetained(state, it, "native CustomData.of argument")
                }
            val exactProjectCall = (value as? MethodCallExpr)?.let {
                resolveProjectMethod(
                    state, it, methodsByKey, typesByName, parents
                )
            }
            val type = retainedType(state, value) ?: expectedType
            val generatedPotionHelperType = (value as? MethodCallExpr)?.let {
                generatedPotionHelperReturnType(state, it)
            }
            if (generatedPotionHelperType != null &&
                generatedPotionHelperType == type
            ) {
                return
            }
            val sourceEnum = type?.let(typesByName::get) as? EnumDeclaration
            if (sourceEnum != null) {
                val exactConstant = (value as? FieldAccessExpr)?.let {
                    sourceEnumConstantType(state, it)
                }
                val exactFactory = when (value) {
                    is MethodCallExpr -> exactProjectEnumReturnType(state, value)
                    is NameExpr -> localDefinitions(state, value)
                        .map { unwrap(it) as? MethodCallExpr }
                        .takeIf { definitions ->
                            definitions.isNotEmpty() && definitions.none { it == null }
                        }
                        ?.map { exactProjectEnumReturnType(state, it!!) }
                        ?.takeIf { definitions ->
                            definitions.isNotEmpty() &&
                                definitions.all { it == definitions.first() }
                        }
                        ?.first()
                    else -> null
                }
                if (exactFactory == type) proveClosedSourceEnum(type)
                check(
                    exactConstant == type ||
                        exactFactory == type ||
                        type in provedSourceEnums
                ) {
                    "Cannot prove PotionBrewing cache lifetime: source enum ${sourceEnum.name} " +
                        "is retained through $expression without an exact qualified constant " +
                        "and closed instance state ($reason)"
                }
                return
            }
            if (value is MethodCallExpr &&
                exactProjectCall == null &&
                type in cacheValueTypes
            ) {
                val elementType = state.typing.iterableElementType(
                    value.toString(), state.offset(value)
                )
                val typedDataContainer = elementType != null && (
                    elementType in typesByName ||
                        elementType in retainedPlatformDataTypes
                    )
                check(knownCollectionFactory(state, value) || typedDataContainer) {
                    "Cannot prove PotionBrewing cache lifetime: opaque external container " +
                        "factory $value ($reason)"
                }
                elementType?.takeIf { it in typesByName }?.let(::queueType)
                value.arguments.forEach {
                    validateRetained(state, it, "collection factory argument")
                }
                return
            }
            if (type == null && exactProjectCall != null) {
                check(exactProjectCall.node.body.isPresent ||
                    (value is MethodCallExpr &&
                        provedFactoryReceiver(state, value, exactProjectCall))
                ) {
                    "Cannot prove PotionBrewing cache lifetime: abstract factory call " +
                        "$value has no source-bound constructor"
                }
                queue(exactProjectCall.state, exactProjectCall.node)
                return
            }
            check(type != null) {
                "Cannot prove PotionBrewing cache lifetime: unknown object graph from " +
                    "$expression ($reason)"
            }
            val forbidden = forbiddenRetentionTypes.firstOrNull {
                canonicalInherits(type, it, parents)
            }
            check(forbidden == null) {
                "Cannot prove PotionBrewing cache lifetime: $expression retains $type " +
                    "($reason; forbidden owner $forbidden)"
            }
            if (type !in typesByName &&
                type !in cacheValueTypes &&
                type !in retainedPlatformDataTypes &&
                type !in setOf(
                    "boolean", "byte", "char", "double", "float",
                    "int", "long", "short", "void"
                ) &&
                exactProjectCall != null
            ) {
                check(exactProjectCall.node.body.isPresent ||
                    (value is MethodCallExpr &&
                        provedFactoryReceiver(state, value, exactProjectCall))
                ) {
                    "Cannot prove PotionBrewing cache lifetime: opaque abstract factory call " +
                        "$value returns $type"
                }
                queue(exactProjectCall.state, exactProjectCall.node)
                return
            }
            check(
                type in typesByName ||
                    type in cacheValueTypes ||
                    type in retainedPlatformDataTypes ||
                    type in setOf(
                        "boolean", "byte", "char", "double", "float",
                        "int", "long", "short", "void"
                    )
            ) {
                "Cannot prove PotionBrewing cache lifetime: opaque retained type $type " +
                    "from $expression ($reason)"
            }
            if (type in typesByName) queueType(type)
        }

        while (queued.isNotEmpty()) {
            val (state, root) = queued.removeFirst()
            if (seen.put(root, true) != null) continue
            if (root is Expression) {
                val variable = root.parentNode.orElse(null)
                    ?.let { it as? VariableDeclarator }
                    ?.takeIf {
                        it.initializer.map { initializer ->
                            initializer === root
                        }.orElse(false)
                    }
                val expected = variable?.let {
                    canonicalRawType(
                        state, it.type.toString(),
                        state.offset(it), typesByName
                    )
                }
                validateRetained(
                    state, root, "reachable retained expression", expected
                )
            }

            root.findAll(MethodCallExpr::class.java).forEach { call ->
                val target = resolveProjectMethod(
                    state, call, methodsByKey, typesByName, parents
                )
                if (target != null) {
                    call.scope.orElse(null)?.let(::unwrap)?.let { receiver ->
                        val field = when (receiver) {
                            is NameExpr -> resolveUnqualifiedField(
                                state, receiver, fieldsByKey, parents
                            )
                            is FieldAccessExpr -> resolveFieldExpression(
                                state, receiver, fieldsByKey, typesByName, parents
                            )
                            else -> null
                        }
                        field?.variable?.initializer?.orElse(null)?.let {
                            queue(field.state, it)
                        }
                    }
                    call.arguments.forEach { argument ->
                        validateRetained(
                            state, argument, "reachable project call argument"
                        )
                    }
                    queue(target.state, target.node)
                    return@forEach
                }
                val possibleOwners = if (call.scope.isPresent) {
                    listOfNotNull(
                        resolveType(
                            state, call.scope.get(), state.offset(call), typesByName
                        )
                    )
                } else {
                    state.ownerChain(call)
                }
                val unresolvedProjectOverload = possibleOwners.any { owner ->
                    projectMethodsInHierarchy(
                        owner, call.nameAsString, call.arguments.size,
                        methodsByKey, parents
                    ).isNotEmpty()
                }
                check(!unresolvedProjectOverload) {
                    "Cannot prove PotionBrewing cache lifetime: unresolved project overload " +
                        "$call on a retained factory/object path"
                }
            }
            root.findAll(ObjectCreationExpr::class.java).forEach { creation ->
                val owner = canonicalRawType(
                    state, creation.type.toString(), state.offset(creation), typesByName
                )
                if (owner != null && owner in typesByName) queueType(owner)
                val declaration = owner?.let(typesByName::get)
                    as? ClassOrInterfaceDeclaration
                val constructorState = owner?.let(stateByType::get)
                val constructor = declaration?.constructors?.singleOrNull {
                    it.parameters.size == creation.arguments.size
                }
                creation.arguments.forEachIndexed { index, argument ->
                    val reference = unwrap(argument) as? MethodReferenceExpr
                        ?: return@forEachIndexed
                    if (reference.identifier != "new") return@forEachIndexed
                    val functional = constructor?.parameters?.getOrNull(index)?.let {
                        canonicalRawType(
                            constructorState ?: state, it.type.toString(),
                            (constructorState ?: state).offset(it), typesByName
                        )
                    } ?: return@forEachIndexed
                    val abstractMethod = methodsByKey
                        .filterKeys { it.owner == functional }
                        .values.flatten()
                        .singleOrNull { !it.node.body.isPresent }
                        ?: return@forEachIndexed
                    val constructed = resolveType(
                        state, reference.scope, state.offset(reference), typesByName
                    )
                    if (constructed != null && constructed in typesByName) {
                        val parameter = constructor.parameters[index]
                        val stored = constructor.findAll(AssignExpr::class.java)
                            .mapNotNull { assignment ->
                                if (expressionName(assignment.value) !=
                                    parameter.nameAsString
                                ) return@mapNotNull null
                                val target = when (
                                    val assigned = unwrap(assignment.target)
                                ) {
                                    is NameExpr -> resolveUnqualifiedField(
                                        constructorState ?: state, assigned,
                                        fieldsByKey, parents
                                    )
                                    is FieldAccessExpr -> resolveFieldExpression(
                                        constructorState ?: state, assigned,
                                        fieldsByKey, typesByName, parents
                                    )
                                    else -> null
                                }
                                target?.takeIf {
                                    mandatoryWithin(assignment, constructor)
                                }?.let { assignment to it }
                            }
                            .distinctBy { it.second.key }
                            .singleOrNull()
                            ?: return@forEachIndexed
                        val (factoryAssignment, storedField) = stored
                        if (storedField.variable.initializer.isPresent ||
                            sourceAssignmentsTo(storedField).singleOrNull() !==
                            factoryAssignment
                        ) return@forEachIndexed
                        provedFactoryReceivers
                            .getOrPut(abstractMethod.key) { linkedSetOf() }
                            .add(storedField.key)
                        queueType(constructed)
                    }
                }
                if (owner != null &&
                    (owner in typesByName || owner in cacheValueTypes)
                ) {
                    creation.arguments.forEach {
                        validateRetained(state, it, "constructor argument")
                    }
                }
            }
            root.findAll(MethodReferenceExpr::class.java).forEach { reference ->
                if (reference.identifier != "new") return@forEach
                resolveType(
                    state, reference.scope, state.offset(reference), typesByName
                )?.takeIf { it in typesByName }?.let(::queueType)
            }
            root.findAll(ExplicitConstructorInvocationStmt::class.java)
                .forEach { invocation ->
                    invocation.arguments.forEach {
                        validateRetained(state, it, "constructor forwarding")
                    }
                    val constructor = invocation.findAncestor(
                        ConstructorDeclaration::class.java
                    ).orElse(null) ?: return@forEach
                    val owner = state.owner(constructor) ?: return@forEach
                    val targetOwner = if (invocation.isThis) owner else parents[owner]
                    targetOwner?.let(::queueType)
                }
            root.findAll(AssignExpr::class.java).forEach { assignment ->
                val field = when (val target = unwrap(assignment.target)) {
                    is NameExpr -> resolveUnqualifiedField(
                        state, target, fieldsByKey, parents
                    )
                    is FieldAccessExpr -> resolveFieldExpression(
                        state, target, fieldsByKey, typesByName, parents
                    )
                    else -> null
                }
                if (field != null) {
                    val expected = canonicalRawType(
                        field.state, field.variable.type.toString(),
                        field.state.offset(field.variable), typesByName
                    )
                    validateRetained(
                        state, assignment.value, "project field assignment", expected
                    )
                }
            }
            root.findAll(MethodCallExpr::class.java).forEach { call ->
                if (call.nameAsString in setOf("add", "addAll", "put", "putAll", "set")) {
                    call.arguments.forEach {
                        validateRetained(state, it, "container mutation")
                    }
                }
            }
            root.findAll(ReturnStmt::class.java).forEach { returned ->
                returned.expression.orElse(null)?.let {
                    val method = returned.findAncestor(MethodDeclaration::class.java)
                        .orElse(null)
                    val expected = method?.let { owner ->
                        val declared = owner.type.toString()
                        canonicalRawType(
                            state, declared, state.offset(owner), typesByName
                        ) ?: declared.substringBefore('<').trim().let { raw ->
                            when {
                                raw in implicitJavaLangTypes -> "java.lang.$raw"
                                raw in setOf(
                                    "boolean", "byte", "char", "double", "float",
                                    "int", "long", "short", "void"
                                ) -> raw
                                else -> null
                            }
                        }
                    }
                    validateRetained(
                        state, it, "reachable factory return", expected
                    )
                }
            }

            val expressions = buildList<Expression> {
                addAll(root.findAll(NameExpr::class.java))
                addAll(root.findAll(FieldAccessExpr::class.java))
                addAll(root.findAll(MethodCallExpr::class.java))
                addAll(root.findAll(ThisExpr::class.java))
            }
            for (expression in expressions) {
                val retainedType = when (expression) {
                    is ThisExpr -> state.owner(expression)
                    is NameExpr -> nameValueType(state, expression)
                    is FieldAccessExpr -> fieldValueType(state, expression)
                    else -> state.typing.typeOf(
                        expression.toString(), state.offset(expression)
                    )
                } ?: continue
                val forbidden = forbiddenRetentionTypes.firstOrNull {
                    canonicalInherits(retainedType, it, parents)
                } ?: continue
                error(
                    "Cannot prove PotionBrewing cache lifetime: factory closure retains " +
                        "$retainedType through $expression (forbidden owner $forbidden)"
                )
            }
        }
    }

    private fun rewriteExactLegacyState(
        parser: JavaParser,
        states: List<UnitState>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        directMethods: Set<MethodKey>,
        providerNames: Map<MethodKey, String>,
        typesByName: Map<String, TypeDeclaration<*>>
    ) {
        for (key in directMethods) {
            val method = methodsByKey.getValue(key).single()
            val provider = providerNames.getValue(key)

            method.node.findAll(MethodCallExpr::class.java).toList().forEach { call ->
                if (call.nameAsString == "test" && call.arguments.size == 1) {
                    val allowed = call.scope.orElse(null)
                    val exactAllowed = when (allowed) {
                        is FieldAccessExpr ->
                            allowed.nameAsString == "ALLOWED_CONTAINER" &&
                                resolveType(
                                    method.state, allowed.scope, method.state.offset(allowed),
                                    typesByName
                                ) == POTION_BREWING
                        is NameExpr ->
                            allowed.nameAsString == "ALLOWED_CONTAINER" &&
                                legacyStaticImported(method.state, allowed)
                        else -> false
                    }
                    if (exactAllowed) {
                        call.replace(
                            parser.parseExpression<Expression>(
                                "$provider.isContainer(${call.arguments.single()})"
                            ).result.orElseThrow()
                        )
                        return@forEach
                    }
                }
                if (call.nameAsString == "getRecipes" && call.arguments.isEmpty()) {
                    val scope = call.scope.orElse(null) ?: return@forEach
                    if (resolveType(
                            method.state, scope, method.state.offset(call),
                            typesByName
                        ) == BREWING_REGISTRY
                    ) {
                        call.setScope(NameExpr(provider))
                    }
                }
            }

            method.node.findAll(FieldAccessExpr::class.java).toList().forEach { field ->
                if (field.nameAsString !in legacyLists) return@forEach
                if (resolveType(
                        method.state, field.scope, method.state.offset(field),
                        typesByName
                    ) != POTION_BREWING
                ) return@forEach
                field.setScope(NameExpr(provider))
                field.setName(
                    if (field.nameAsString == "POTION_MIXES") "potionMixes"
                    else "containerMixes"
                )
            }
            method.node.findAll(NameExpr::class.java).toList().forEach { name ->
                if (name.nameAsString !in legacyLists ||
                    !legacyStaticImported(method.state, name)
                ) return@forEach
                val target = if (name.nameAsString == "POTION_MIXES") {
                    "$provider.potionMixes"
                } else {
                    "$provider.containerMixes"
                }
                name.replace(parser.parseExpression<Expression>(target).result.orElseThrow())
            }
        }
        // A direct legacy loop may pass Mix values to source helpers. Rewrite every exact typed
        // Mix component read in the parsed project so that the helper side of that def-use path
        // cannot retain removed record fields.
        states.forEach { state ->
            state.unit.findAll(FieldAccessExpr::class.java).toList().forEach { field ->
                if (field.nameAsString !in setOf("from", "to", "ingredient")) {
                    return@forEach
                }
                if (state.typing.typeOf(
                        field.scope.toString(), state.offset(field)
                    ) != "$POTION_BREWING.Mix"
                ) return@forEach
                field.replace(MethodCallExpr(field.scope.clone(), field.nameAsString))
            }
        }
        states.forEach { state ->
            state.unit.imports.removeIf {
                it.isStatic && !it.isAsterisk &&
                    it.nameAsString.substringBeforeLast('.') == POTION_BREWING &&
                    it.name.identifier in legacyFields
            }
        }
    }

    private fun validateProviderUses(
        dependentMethods: Set<MethodKey>,
        dependentFields: Set<FieldKey>,
        methodsByKey: Map<MethodKey, List<MethodDef>>,
        fieldsByKey: Map<FieldKey, FieldDef>,
        methodUses: List<MethodUse>,
        providerNames: Map<MethodKey, String>,
        fieldProviderNames: Map<FieldKey, String>,
        accessorNames: Map<FieldKey, String>
    ) {
        val threadedCalls = methodUses
            .filter { it.target.key in dependentMethods }
            .map { it.node }
            .toSet()
        val accessors = accessorNames.values.toSet()

        fun capturedBetween(value: NameExpr, root: Node): Boolean {
            var current: Node? = value.parentNode.orElse(null)
            while (current != null && current !== root) {
                if (current is LambdaExpr ||
                    current is TypeDeclaration<*> ||
                    current is ObjectCreationExpr && current.anonymousClassBody.isPresent
                ) return true
                current = current.parentNode.orElse(null)
            }
            return current !== root
        }

        fun validate(root: Node, provider: String, label: String) {
            root.findAll(NameExpr::class.java)
                .filter { it.nameAsString == provider }
                .forEach { value ->
                    check(!capturedBetween(value, root)) {
                        "$label captures the PotionBrewing provider in a lambda, nested type, " +
                            "or anonymous class"
                    }
                    var operand: Expression = value
                    var parent: Node? = value.parentNode.orElse(null)
                    while (parent is EnclosedExpr) {
                        operand = parent
                        parent = parent.parentNode.orElse(null)
                    }
                    val allowed = when (parent) {
                        is FieldAccessExpr ->
                            parent.scope === operand &&
                                parent.nameAsString in setOf("potionMixes", "containerMixes")
                        is MethodCallExpr -> when {
                            parent.scope.map { it === operand }.orElse(false) ->
                                parent.nameAsString in setOf("isContainer", "getRecipes")
                            operand in parent.arguments ->
                                parent in threadedCalls || parent.nameAsString in accessors
                            else -> false
                        }
                        else -> false
                    }
                    check(allowed) {
                        "$label lets the PotionBrewing provider escape through ${parent ?: value}"
                    }
                }
        }

        dependentMethods.forEach { key ->
            validate(
                methodsByKey.getValue(key).single().node,
                providerNames.getValue(key),
                "Provider-dependent method ${key.owner}#${key.name}"
            )
        }
        dependentFields.forEach { key ->
            validate(
                fieldsByKey.getValue(key).variable.initializer.orElseThrow(),
                fieldProviderNames.getValue(key),
                "Provider-dependent field ${key.owner}#${key.name}"
            )
        }
    }

    private fun validateTargetSurface(legacy: LegacyUseSummary) {
        check(TargetMembers.hasMethod(POTION_BREWING, "getRecipes") &&
            "()Ljava/util/List;" in TargetMembers.methodDescriptors(POTION_BREWING, "getRecipes")
        ) { "Target PotionBrewing.getRecipes() is missing" }
        if (legacy.needsPotionMixes) {
            check(
                TargetMembers.fieldDescriptor(POTION_BREWING, "potionMixes") ==
                    "Ljava/util/List;" &&
                    TargetMembers.fieldSignatures(POTION_BREWING)["potionMixes"]
                        ?.contains("PotionBrewing\$Mix") == true
            ) {
                "Target PotionBrewing.potionMixes is missing"
            }
        }
        if (legacy.needsContainerMixes) {
            check(
                TargetMembers.fieldDescriptor(POTION_BREWING, "containerMixes") ==
                    "Ljava/util/List;" &&
                    TargetMembers.fieldSignatures(POTION_BREWING)["containerMixes"]
                        ?.contains("PotionBrewing\$Mix") == true
            ) {
                "Target PotionBrewing.containerMixes is missing"
            }
        }
        if (legacy.needsContainerPredicate) {
            check(
                "(Lnet/minecraft/world/item/ItemStack;)Z" in
                    TargetMembers.methodDescriptors(POTION_BREWING, "isContainer")
            ) { "Target PotionBrewing.isContainer(ItemStack) is missing" }
        }
        if (legacy.needsPotionMixes || legacy.needsContainerMixes) {
            val mix = "$POTION_BREWING\$Mix"
            check(TargetMembers.hasClass(mix)) {
                "Target PotionBrewing.Mix is missing"
            }
            val holderSignature = "Lnet/minecraft/core/Holder<TT;>;"
            val components = mapOf(
                "from" to "()Lnet/minecraft/core/Holder;",
                "ingredient" to "()Lnet/minecraft/world/item/crafting/Ingredient;",
                "to" to "()Lnet/minecraft/core/Holder;"
            )
            for ((component, descriptor) in components) {
                val method = TargetMembers.inheritedMethod(mix, component, descriptor)
                    ?: error("Target PotionBrewing.Mix.$component() is missing")
                check(method.access == "public") {
                    "Target PotionBrewing.Mix.$component() is missing"
                }
                if (component in setOf("from", "to")) {
                    check(
                        TargetMembers.fieldDescriptor(mix, component) ==
                            "Lnet/minecraft/core/Holder;" &&
                            TargetMembers.fieldSignatures(mix)[component] ==
                            holderSignature &&
                            method.signature == "()$holderSignature"
                    ) {
                        "Target PotionBrewing.Mix.$component does not preserve Holder<T>"
                    }
                }
            }
            val holderValue = TargetMembers.inheritedMethod(
                HOLDER, "value", "()Ljava/lang/Object;"
            )
            check(holderValue?.access == "public" && holderValue.signature == "()TT;") {
                "Target Holder.value() does not preserve T"
            }
        }
        val customKeyMethods = listOf("equals", "hashCode").filter {
            TargetMembers.hasMethod(POTION_BREWING, it)
        }
        check(customKeyMethods.isEmpty()) {
            "PotionBrewing now declares ${customKeyMethods.joinToString()}; " +
                "WeakHashMap key semantics require inherited Object identity methods"
        }
    }

    private fun legacyStaticImported(state: UnitState, name: NameExpr): Boolean {
        val imported = state.unit.imports.any {
            it.isStatic && !it.isAsterisk &&
                it.nameAsString == "$POTION_BREWING.${name.nameAsString}"
        }
        if (!imported) return false
        if (localValueShadows(state, name) ||
            state.typing.typeOf(name.nameAsString, state.offset(name)) != null ||
            state.typing.untypedLambdaParameter(name.nameAsString, state.offset(name)) ||
            JavaScopedDeclarations.visibleAt(
                state.masked, name.nameAsString, state.offset(name)
            ) != null
        ) return false
        check(
            state.unit.findAll(VariableDeclarator::class.java).none {
                it.nameAsString == name.nameAsString && it.type.isVarType
            }
        ) {
            "Cannot prove whether var ${name.nameAsString} shadows its exact PotionBrewing " +
                "static import in ${state.file}"
        }
        return true
    }

    private fun containsLegacyStaticBrewingAccess(
        pass: StructuralRefactorPass,
        source: String
    ): Boolean {
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = parser.parse(source)
        if (!parsed.isSuccessful) return true
        val state = unitState(Path.of("migrated.java"), source, parsed.result.orElseThrow())
        state.typing = SourceValueTypes(pass, source)
        val typesByName = state.unit.findAll(TypeDeclaration::class.java)
            .associateBy({ state.owner(it) }, { it })
        if (state.unit.imports.any {
                it.isStatic && !it.isAsterisk &&
                    it.nameAsString.substringBeforeLast('.') == POTION_BREWING &&
                    it.name.identifier in legacyFields
            }
        ) return true
        if (state.unit.findAll(FieldAccessExpr::class.java).any {
                it.nameAsString in legacyFields &&
                    resolveType(state, it.scope, state.offset(it), typesByName) == POTION_BREWING
            }
        ) return true
        if (state.unit.findAll(NameExpr::class.java).any {
                it.nameAsString in legacyFields && legacyStaticImported(state, it)
            }
        ) return true
        return state.unit.findAll(MethodCallExpr::class.java).any {
            it.nameAsString == "getRecipes" && it.arguments.isEmpty() &&
                it.scope.map { scope ->
                    resolveType(state, scope, state.offset(it), typesByName) == BREWING_REGISTRY
                }.orElse(false)
        }
    }

    private data class PlannedAtChange(
        val file: Path,
        val content: String
    ) {
        fun write() {
            file.parent.createDirectories()
            file.writeText(content)
        }
    }

    private fun planAccessTransformerChange(
        projectDir: Path,
        required: List<String>
    ): PlannedAtChange? {
        if (required.isEmpty()) return null
        val file = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        val original = if (file.exists()) file.readText() else ""
        val existing = original.lineSequence()
            .map { it.substringBefore('#').trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }
            .toMutableSet()
        val missing = required.filter { existing.add(it) }
        if (missing.isEmpty()) return null
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val content = buildString {
            append(original.trimEnd())
            if (isNotEmpty()) append(newline).append(newline)
            append(missing.joinToString(newline))
            append(newline)
        }
        return PlannedAtChange(file, content)
    }

    private fun uniqueName(method: MethodDeclaration, base: String): String {
        val occupied = buildSet {
            method.findAll(Parameter::class.java).forEach { add(it.nameAsString) }
            method.findAll(VariableDeclarator::class.java).forEach { add(it.nameAsString) }
            method.findAll(TypePatternExpr::class.java).forEach { add(it.nameAsString) }
            method.findAll(NameExpr::class.java).forEach { add(it.nameAsString) }
        }
        var result = base
        while (result in occupied) result += "_"
        return result
    }

    private fun uniqueMemberName(source: String, base: String): String {
        var result = base
        while (Regex("""(?<![\w$])${Regex.escape(result)}(?![\w$])""")
                .containsMatchIn(source)
        ) result += "_"
        return result
    }
}
