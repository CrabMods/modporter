package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Repairs source-proven 1.21 boundaries from legacy predicates, capabilities, tiers, holder
 * streams, and GameTest registry providers.
 *
 * The tier rewrite is deliberately limited to a Tier enum whose retained source method returns
 * literal mining level zero. Vanilla's native tier mapping and Create's 1.21 source both map that
 * exact level/material to INCORRECT_FOR_WOODEN_TOOL; no fallback tag is invented.
 */
internal fun StructuralRefactorPass.migrateLegacyCompilerSourceBoundaries(
    source: String
): String {
    if (!source.contains("new ItemPredicate") &&
        !source.contains("Capabilities.FluidHandler.ITEM") &&
        !source.contains("getLevel()") &&
        !source.contains("ItemLike::asItem") &&
        !source.contains("new EnchantmentInstance")
    ) {
        return source
    }

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    if (parsed.problems.isNotEmpty()) return source
    LexicalPreservingPrinter.setup(unit)

    val predicateChanged = migrateLegacyTagItemPredicates(unit, parser)
    val capabilityChanged = migrateItemCapabilityPresencePredicates(
        unit,
        source,
        this
    )
    val tierChanged = migrateLiteralWoodTierBoundary(unit, parser)
    val tagStreamChanged = migrateRegistryHolderItemLikeStreams(unit, parser)
    val gameTestChanged = migrateGameTestEnchantmentProviderRoots(
        unit,
        parser
    )
    if (!predicateChanged && !capabilityChanged && !tierChanged &&
        !tagStreamChanged && !gameTestChanged
    ) {
        return source
    }

    var result = LexicalPreservingPrinter.print(unit)
    if (tierChanged) {
        result = JavaSourceText.addImport(result, BLOCK_TAGS)
        result = JavaSourceText.addImport(result, TAG_KEY)
        result = JavaSourceText.addImport(result, BLOCK)
    }
    if (tagStreamChanged) {
        result = JavaSourceText.addImport(result, HOLDER)
    }
    if (predicateChanged) {
        result = removeCompilerBoundaryImportIfUnused(
            result,
            ENCHANTMENT_PREDICATE,
            "EnchantmentPredicate"
        )
        result = removeCompilerBoundaryImportIfUnused(
            result,
            MIN_MAX_BOUNDS,
            "MinMaxBounds"
        )
        result = removeCompilerBoundaryImportIfUnused(
            result,
            NBT_PREDICATE,
            "NbtPredicate"
        )
    }
    val reparsed = parser.parse(result)
    return if (reparsed.result.isPresent && reparsed.problems.isEmpty()) result else source
}

private fun migrateLegacyTagItemPredicates(
    unit: CompilationUnit,
    parser: JavaParser
): Boolean {
    val required = listOf(
        "ItemPredicate" to ITEM_PREDICATE,
        "InventoryChangeTrigger" to INVENTORY_CHANGE_TRIGGER,
        "TagKey" to TAG_KEY,
        "Item" to ITEM,
        "MinMaxBounds" to MIN_MAX_BOUNDS,
        "EnchantmentPredicate" to ENCHANTMENT_PREDICATE,
        "NbtPredicate" to NBT_PREDICATE
    )
    if (required.any { (simple, qualified) ->
            !compilerBoundaryCanonicalSimpleType(unit, simple, qualified)
        }
    ) {
        return false
    }

    var changed = false
    for (creation in unit.findAll(ObjectCreationExpr::class.java).toList()) {
        if (!compilerBoundaryCanonicalType(
                unit,
                creation.type.asString(),
                "ItemPredicate",
                ITEM_PREDICATE
            ) ||
            creation.arguments.size != 8
        ) {
            continue
        }
        val tag = creation.arguments[0] as? NameExpr ?: continue
        val method = creation.findAncestor(MethodDeclaration::class.java)
            .orElse(null) ?: continue
        val parameter = method.parameters.singleOrNull {
            it.nameAsString == tag.nameAsString &&
                compilerBoundaryNormalized(it.type.asString()) ==
                "TagKey<Item>"
        } ?: continue
        if (!compilerBoundaryCanonicalType(
                unit,
                parameter.type.asString(),
                "TagKey",
                TAG_KEY
            ) ||
            !compilerBoundaryCanonicalType(
                unit,
                parameter.type.asString(),
                "Item",
                ITEM
            )
        ) {
            continue
        }
        if (creation.arguments[1] !is NullLiteralExpr ||
            creation.arguments[2].toString() != "MinMaxBounds.Ints.ANY" ||
            creation.arguments[3].toString() != "MinMaxBounds.Ints.ANY" ||
            creation.arguments[4].toString() != "EnchantmentPredicate.NONE" ||
            creation.arguments[5].toString() != "EnchantmentPredicate.NONE" ||
            creation.arguments[6] !is NullLiteralExpr ||
            creation.arguments[7].toString() != "NbtPredicate.ANY"
        ) {
            continue
        }
        val hasItems = creation.findAncestor(MethodCallExpr::class.java)
            .orElse(null) ?: continue
        if (hasItems.nameAsString != "hasItems" ||
            hasItems.arguments.none { it === creation } ||
            !compilerBoundaryCanonicalOwner(
                unit,
                hasItems.scope.orElse(null),
                "InventoryChangeTrigger",
                INVENTORY_CHANGE_TRIGGER,
                allowedSuffix = ".TriggerInstance"
            )
        ) {
            continue
        }
        creation.replace(
            parser.parseExpression<Expression>(
                "ItemPredicate.Builder.item().of(${tag.nameAsString}).build()"
            ).result.orElseThrow()
        )
        changed = true
    }
    return changed
}

private fun migrateItemCapabilityPresencePredicates(
    unit: CompilationUnit,
    source: String,
    pass: StructuralRefactorPass
): Boolean {
    if (!compilerBoundaryCanonicalSimpleType(
            unit,
            "Capabilities",
            CAPABILITIES
        ) ||
        !compilerBoundaryCanonicalSimpleType(
            unit,
            "ItemStack",
            ITEM_STACK
        )
    ) {
        return false
    }

    val typing = SourceValueTypes(pass, source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, char ->
            if (char == '\n') add(index + 1)
        }
    }
    var changed = false
    for (present in unit.findAll(MethodCallExpr::class.java).toList()) {
        if (present.nameAsString != "isPresent" ||
            present.arguments.isNotEmpty()
        ) {
            continue
        }
        val capability = present.scope.orElse(null) as? MethodCallExpr
            ?: continue
        if (capability.nameAsString != "getCapability" ||
            capability.arguments.size != 1 ||
            capability.arguments.single().toString() !=
            "Capabilities.FluidHandler.ITEM"
        ) {
            continue
        }
        val receiver = capability.scope.orElse(null) as? NameExpr ?: continue
        val typedReceiver = typing.typeOf(
            receiver.toString(),
            compilerBoundaryOffset(capability, lineStarts)
        ) == ITEM_STACK
        if (!typedReceiver &&
            !compilerBoundaryLegacyItemPredicateReceiver(
                receiver,
                capability,
                present,
                unit
            )
        ) {
            continue
        }

        val negation = present.parentNode.orElse(null) as? UnaryExpr
        val target = negation?.takeIf {
            it.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT &&
                it.expression === present
        } ?: present
        target.replace(
            BinaryExpr(
                capability.clone(),
                NullLiteralExpr(),
                if (target === negation) {
                    BinaryExpr.Operator.EQUALS
                } else {
                    BinaryExpr.Operator.NOT_EQUALS
                }
            )
        )
        changed = true
    }
    return changed
}

private fun compilerBoundaryLegacyItemPredicateReceiver(
    receiver: NameExpr,
    capability: MethodCallExpr,
    present: MethodCallExpr,
    unit: CompilationUnit
): Boolean {
    if (!compilerBoundaryCanonicalSimpleType(
            unit,
            "Predicate",
            PREDICATE
        )
    ) {
        return false
    }
    val lambda = present.findAncestor(LambdaExpr::class.java)
        .orElse(null) ?: return false
    if (lambda.parameters.size != 1 ||
        lambda.parameters.single().nameAsString != receiver.nameAsString ||
        compilerBoundaryShadowedBetween(
            capability,
            lambda,
            receiver.nameAsString
        )
    ) {
        return false
    }
    val singleton = compilerBoundaryParentExpression(lambda)
        as? MethodCallExpr ?: return false
    if (singleton.nameAsString != "singleton" ||
        singleton.arguments.size != 2 ||
        singleton.arguments[1] !== lambda ||
        singleton.arguments[0] !is StringLiteralExpr
    ) {
        return false
    }
    val owner = singleton.findAncestor(
        ClassOrInterfaceDeclaration::class.java
    ).orElse(null) ?: return false
    return compilerBoundaryHasItemPredicateSingleton(owner, unit)
}

private fun compilerBoundaryOffset(
    node: Node,
    lineStarts: List<Int>
): Int = node.range.map {
    lineStarts[it.begin.line - 1] + it.begin.column - 1
}.orElse(-1)

private fun compilerBoundaryHasItemPredicateSingleton(
    owner: ClassOrInterfaceDeclaration,
    unit: CompilationUnit
): Boolean =
    owner.methods.any { method ->
        method.nameAsString == "singleton" &&
            method.parameters.size == 2 &&
            compilerBoundaryCanonicalType(
                unit,
                method.parameters[0].type.asString(),
                "String",
                STRING
            ) &&
            compilerBoundaryNormalized(
                method.parameters[1].type.asString()
            ) == "Predicate<ItemStack>" &&
            compilerBoundaryCanonicalType(
                unit,
                method.parameters[1].type.asString(),
                "Predicate",
                PREDICATE
            ) &&
            compilerBoundaryCanonicalType(
                unit,
                method.parameters[1].type.asString(),
                "ItemStack",
                ITEM_STACK
            )
    }

private fun migrateLiteralWoodTierBoundary(
    unit: CompilationUnit,
    parser: JavaParser
): Boolean {
    if (!compilerBoundaryCanonicalSimpleType(unit, "Tier", TIER) ||
        !compilerBoundarySimpleNameAvailable(unit, "TagKey", TAG_KEY) ||
        !compilerBoundarySimpleNameAvailable(unit, "Block", BLOCK) ||
        !compilerBoundarySimpleNameAvailable(
            unit,
            "BlockTags",
            BLOCK_TAGS
        )
    ) {
        return false
    }

    var changed = false
    for (owner in unit.findAll(EnumDeclaration::class.java)) {
        if (owner.implementedTypes.none {
                compilerBoundaryCanonicalType(
                    unit,
                    it.asString(),
                    "Tier",
                    TIER
                )
            } ||
            owner.methods.any {
                it.nameAsString == "getIncorrectBlocksForDrops" &&
                    it.parameters.isEmpty()
            }
        ) {
            continue
        }
        val level = owner.methods.singleOrNull { method ->
            method.nameAsString == "getLevel" &&
                method.parameters.isEmpty() &&
                method.type.isPrimitiveType &&
                method.type.asPrimitiveType().type ==
                com.github.javaparser.ast.type.PrimitiveType.Primitive.INT &&
                method.isPublic &&
                method.body.map { body ->
                    body.statements.size == 1 &&
                        (body.statements.single() as? ReturnStmt)
                            ?.expression
                            ?.map { it.toString() == "0" }
                            ?.orElse(false) == true
                }.orElse(false)
        } ?: continue
        val replacement = parser.parseBodyDeclaration<MethodDeclaration>(
            """
            @Override
            public TagKey<Block> getIncorrectBlocksForDrops() {
                return BlockTags.INCORRECT_FOR_WOODEN_TOOL;
            }
            """.trimIndent()
        ).result.orElse(null) ?: continue
        level.replace(replacement)
        changed = true
    }
    return changed
}

private fun migrateRegistryHolderItemLikeStreams(
    unit: CompilationUnit,
    parser: JavaParser
): Boolean {
    val required = listOf(
        "BuiltInRegistries" to BUILT_IN_REGISTRIES,
        "BlockTags" to BLOCK_TAGS,
        "ItemLike" to ITEM_LIKE
    )
    if (required.any { (simple, qualified) ->
            !compilerBoundaryCanonicalSimpleType(unit, simple, qualified)
        } ||
        !compilerBoundarySimpleNameAvailable(unit, "Holder", HOLDER)
    ) {
        return false
    }

    var changed = false
    for (map in unit.findAll(MethodCallExpr::class.java).toList()) {
        if (map.nameAsString != "map" || map.arguments.size != 1) continue
        val itemLike = map.arguments.single() as? MethodReferenceExpr
            ?: continue
        if (itemLike.identifier != "asItem" ||
            !compilerBoundaryCanonicalOwner(
                unit,
                itemLike.scope,
                "ItemLike",
                ITEM_LIKE
            )
        ) {
            continue
        }
        val stream = map.scope.orElse(null) as? MethodCallExpr ?: continue
        if (stream.nameAsString != "stream" ||
            stream.arguments.isNotEmpty()
        ) {
            continue
        }
        val tag = stream.scope.orElse(null) as? MethodCallExpr ?: continue
        if (tag.nameAsString != "getOrCreateTag" ||
            tag.arguments.size != 1 ||
            tag.arguments.single().toString() != "BlockTags.PLANKS"
        ) {
            continue
        }
        val registry = tag.scope.orElse(null) as? FieldAccessExpr ?: continue
        if (registry.nameAsString != "BLOCK" ||
            !compilerBoundaryCanonicalOwner(
                unit,
                registry.scope,
                "BuiltInRegistries",
                BUILT_IN_REGISTRIES
            )
        ) {
            continue
        }
        map.setScope(
            parser.parseExpression<Expression>(
                "$stream.map(Holder::value)"
            ).result.orElseThrow()
        )
        changed = true
    }
    return changed
}

private fun migrateGameTestEnchantmentProviderRoots(
    unit: CompilationUnit,
    parser: JavaParser
): Boolean {
    val required = listOf(
        "CreateGameTestHelper" to CREATE_GAME_TEST_HELPER,
        "EnchantmentInstance" to ENCHANTMENT_INSTANCE,
        "Enchantments" to ENCHANTMENTS,
        "Registries" to REGISTRIES
    )
    if (required.any { (simple, qualified) ->
            !compilerBoundaryCanonicalSimpleType(unit, simple, qualified)
        }
    ) {
        return false
    }

    var changed = false
    for (creation in unit.findAll(ObjectCreationExpr::class.java).toList()) {
        if (!compilerBoundaryCanonicalType(
                unit,
                creation.type.asString(),
                "EnchantmentInstance",
                ENCHANTMENT_INSTANCE
            ) ||
            creation.arguments.size < 2
        ) {
            continue
        }
        val method = creation.findAncestor(MethodDeclaration::class.java)
            .orElse(null) ?: continue
        val helpers = method.parameters.filter {
            compilerBoundaryCanonicalType(
                unit,
                it.type.asString(),
                "CreateGameTestHelper",
                CREATE_GAME_TEST_HELPER
            )
        }
        val helper = helpers.singleOrNull() ?: continue
        val key = compilerBoundaryGameTestEnchantmentKey(
            creation.arguments[0],
            unit,
            method
        ) ?: continue
        creation.setArgument(
            0,
            parser.parseExpression<Expression>(
                "${helper.nameAsString}.getLevel().registryAccess()" +
                    ".registryOrThrow(Registries.ENCHANTMENT)" +
                    ".getHolderOrThrow($key)"
            ).result.orElseThrow()
        )
        changed = true
    }
    return changed
}

private fun compilerBoundaryGameTestEnchantmentKey(
    expression: Expression,
    unit: CompilationUnit,
    method: MethodDeclaration
): String? {
    if (expression is FieldAccessExpr &&
        compilerBoundaryCanonicalOwner(
            unit,
            expression.scope,
            "Enchantments",
            ENCHANTMENTS
        ) &&
        Regex("""[A-Z][A-Z0-9_]*""").matches(expression.nameAsString)
    ) {
        return expression.toString()
    }

    val getOrThrow = expression as? MethodCallExpr ?: return null
    if (getOrThrow.nameAsString != "getOrThrow" ||
        getOrThrow.arguments.size != 1
    ) {
        return null
    }
    val key = getOrThrow.arguments.single() as? FieldAccessExpr ?: return null
    if (!compilerBoundaryCanonicalOwner(
            unit,
            key.scope,
            "Enchantments",
            ENCHANTMENTS
        ) ||
        !Regex("""[A-Z][A-Z0-9_]*""").matches(key.nameAsString)
    ) {
        return null
    }
    val lookup = getOrThrow.scope.orElse(null) as? MethodCallExpr ?: return null
    if (lookup.nameAsString != "lookupOrThrow" ||
        lookup.arguments.size != 1 ||
        lookup.arguments.single().toString() != "Registries.ENCHANTMENT"
    ) {
        return null
    }
    val holders = lookup.scope.orElse(null) as? MethodCallExpr ?: return null
    if (holders.nameAsString != "holders" ||
        holders.arguments.isNotEmpty() ||
        (holders.scope.orElse(null) as? NameExpr)?.nameAsString !=
        "parameters"
    ) {
        return null
    }
    val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java)
        .orElse(null)
    if (compilerBoundaryMethodDeclaresName(method, "parameters") ||
        owner?.fields?.flatMap { it.variables }?.any {
            it.nameAsString == "parameters"
        } == true
    ) {
        return null
    }
    return key.toString()
}

private fun compilerBoundaryMethodDeclaresName(
    method: MethodDeclaration,
    name: String
): Boolean =
    method.parameters.any { it.nameAsString == name } ||
        method.findAll(VariableDeclarator::class.java).any {
            it.nameAsString == name
        } ||
        method.findAll(LambdaExpr::class.java).any { lambda ->
            lambda.parameters.any { it.nameAsString == name }
        }

private fun compilerBoundaryParentExpression(node: Node): Node? {
    var current: Node = node
    var parent = current.parentNode.orElse(null)
    while (parent is com.github.javaparser.ast.expr.EnclosedExpr ||
        parent is com.github.javaparser.ast.expr.CastExpr
    ) {
        current = parent
        parent = current.parentNode.orElse(null)
    }
    return parent
}

private fun compilerBoundaryShadowedBetween(
    node: Node,
    ancestor: Node,
    name: String
): Boolean {
    var current = node.parentNode.orElse(null)
    while (current != null && current !== ancestor) {
        if (current is LambdaExpr &&
            current.parameters.any { it.nameAsString == name }
        ) {
            return true
        }
        current = current.parentNode.orElse(null)
    }
    return false
}

private fun compilerBoundaryCanonicalOwner(
    unit: CompilationUnit,
    expression: Expression?,
    simple: String,
    qualified: String,
    allowedSuffix: String = ""
): Boolean {
    expression ?: return false
    val text = expression.toString()
    if (text == qualified + allowedSuffix) return true
    if (text != simple + allowedSuffix) return false
    if (!compilerBoundaryCanonicalSimpleType(unit, simple, qualified)) {
        return false
    }
    if (unit.findAll(Parameter::class.java).any {
            it.nameAsString == simple
        } ||
        unit.findAll(VariableDeclarator::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    return true
}

private fun compilerBoundaryCanonicalType(
    unit: CompilationUnit,
    typeText: String,
    simple: String,
    qualified: String
): Boolean {
    if (simple == "String" &&
        compilerBoundaryNormalized(typeText) == "String" &&
        unit.findAll(TypeDeclaration::class.java).none {
            it.nameAsString == "String"
        }
    ) {
        return true
    }
    if (Regex(
            """(?<![A-Za-z0-9_$])${Regex.escape(qualified)}""" +
                """(?![A-Za-z0-9_$])"""
        ).containsMatchIn(typeText)
    ) {
        return true
    }
    if (!Regex(
            """(?<![A-Za-z0-9_$])${Regex.escape(simple)}""" +
                """(?![A-Za-z0-9_$])"""
        ).containsMatchIn(typeText)
    ) {
        return false
    }
    return compilerBoundaryCanonicalSimpleType(unit, simple, qualified)
}

private fun compilerBoundaryCanonicalSimpleType(
    unit: CompilationUnit,
    simple: String,
    qualified: String
): Boolean {
    if (simple == "String") {
        return unit.findAll(TypeDeclaration::class.java).none {
            it.nameAsString == simple
        } &&
            unit.imports.none {
                !it.isStatic && !it.isAsterisk &&
                    it.name.identifier == simple
            }
    }
    if (unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    val imports = unit.imports.filter {
        !it.isStatic && !it.isAsterisk &&
            it.name.identifier == simple
    }
    return imports.size == 1 &&
        imports.single().nameAsString == qualified
}

private fun compilerBoundarySimpleNameAvailable(
    unit: CompilationUnit,
    simple: String,
    qualified: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    val imports = unit.imports.filter {
        !it.isStatic && !it.isAsterisk &&
            it.name.identifier == simple
    }
    return imports.isEmpty() ||
        imports.size == 1 && imports.single().nameAsString == qualified
}

private fun removeCompilerBoundaryImportIfUnused(
    source: String,
    qualified: String,
    simple: String
): String {
    val without = JavaSourceText.removeImport(source, qualified)
    return if (Regex("""\b${Regex.escape(simple)}\b""")
            .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(without))
    ) {
        source
    } else {
        without
    }
}

private fun compilerBoundaryNormalized(type: String): String =
    type.replace(Regex("""\s+"""), "")

private const val BLOCK = "net.minecraft.world.level.block.Block"
private const val BLOCK_TAGS = "net.minecraft.tags.BlockTags"
private const val BUILT_IN_REGISTRIES =
    "net.minecraft.core.registries.BuiltInRegistries"
private const val CAPABILITIES =
    "net.neoforged.neoforge.capabilities.Capabilities"
private const val CREATE_GAME_TEST_HELPER =
    "com.simibubi.create.infrastructure.gametest.CreateGameTestHelper"
private const val ENCHANTMENT_INSTANCE =
    "net.minecraft.world.item.enchantment.EnchantmentInstance"
private const val ENCHANTMENT_PREDICATE =
    "net.minecraft.advancements.critereon.EnchantmentPredicate"
private const val ENCHANTMENTS =
    "net.minecraft.world.item.enchantment.Enchantments"
private const val HOLDER = "net.minecraft.core.Holder"
private const val INVENTORY_CHANGE_TRIGGER =
    "net.minecraft.advancements.critereon.InventoryChangeTrigger"
private const val ITEM = "net.minecraft.world.item.Item"
private const val ITEM_LIKE = "net.minecraft.world.level.ItemLike"
private const val ITEM_PREDICATE =
    "net.minecraft.advancements.critereon.ItemPredicate"
private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
private const val MIN_MAX_BOUNDS =
    "net.minecraft.advancements.critereon.MinMaxBounds"
private const val NBT_PREDICATE =
    "net.minecraft.advancements.critereon.NbtPredicate"
private const val PREDICATE = "java.util.function.Predicate"
private const val REGISTRIES =
    "net.minecraft.core.registries.Registries"
private const val STRING = "java.lang.String"
private const val TAG_KEY = "net.minecraft.tags.TagKey"
private const val TIER = "net.minecraft.world.item.Tier"
