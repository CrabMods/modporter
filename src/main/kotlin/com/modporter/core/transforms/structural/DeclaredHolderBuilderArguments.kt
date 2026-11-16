package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.structural.ProjectSourceDeclarations.TypeRef
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Repairs three source-proven holder/value boundaries:
 *
 * 1. Registrate block-loot callbacks use their provider instance and resolve enchantment keys
 *    through that provider;
 * 2. a built-in registry's `getKey` receives the value of a platform `DeferredHolder` constant
 *    whose value type is the registry's element type; and
 * 3. a project method argument that is a platform `DeferredHolder` constant receives the holder's
 *    value when the project's own overloads prove that only the value type can be accepted.
 *
 * NeoForge declares registry constants such as `NeoForgeMod.MILK` as `DeferredHolder<R, T>` (the
 * 1.21.1 member table), and the 1.20.1 `RegistryObject#get()` read of such a constant is migrated
 * to the bare holder. Rule 3 restores the value only where the call's receiver type and every
 * overload it can bind to are project declarations: exactly one overload accepts the value type,
 * none can accept the holder, and every other argument is a literal the overload provably accepts.
 * Vanilla `Holder<T>` constants are not admitted; their identity flows are migrated separately.
 */
internal fun StructuralRefactorPass.migrateDeclaredHolderBuilderArguments(
    source: String
): String {
    val loot = source.contains("createSilkTouchDispatchTable")
    if (!loot && !importsDeferredHolderOwner(source)) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    if (parsed.problems.isNotEmpty()) return source
    LexicalPreservingPrinter.setup(unit)

    var changed = false
    if (loot) {
        for (lambda in unit.findAll(LambdaExpr::class.java).toList()) {
            changed = migrateRegistrateOreLootLambda(lambda, unit, parser, source) || changed
        }
    }
    for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
        changed = migrateBuiltInRegistryKeyArgument(call, unit, parser) || changed
    }
    for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
        changed = migrateProjectDeferredHolderArgument(call, unit, parser) || changed
    }

    if (!changed) return source
    val result = LexicalPreservingPrinter.print(unit)
    val reparsed = parser.parse(result)
    return if (reparsed.result.isPresent && reparsed.problems.isEmpty()) result else source
}

private fun migrateRegistrateOreLootLambda(
    lambda: LambdaExpr,
    unit: CompilationUnit,
    parser: JavaParser,
    source: String
): Boolean {
    if (lambda.parameters.size != 2) return false
    val lootCall = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return false
    if (lootCall.nameAsString != "loot" ||
        lootCall.arguments.size != 1 ||
        lootCall.arguments.single() !== lambda
    ) {
        return false
    }

    val tablesName = lambda.parameters[0].nameAsString
    val blockName = lambda.parameters[1].nameAsString
    val dispatches = lambda.findAll(MethodCallExpr::class.java)
        .filter { it.findAncestor(LambdaExpr::class.java).orElse(null) === lambda }
        .filter { call ->
            call.nameAsString == "createSilkTouchDispatchTable" &&
                call.arguments.size == 2 &&
                (call.arguments[0] as? NameExpr)?.nameAsString == blockName &&
                call.scope.map {
                    declaredHolderCanonicalOwner(
                        it,
                        REGISTRATE_BLOCK_LOOT_TABLES_SIMPLE,
                        REGISTRATE_BLOCK_LOOT_TABLES,
                        unit,
                        source
                    )
                }.orElse(false)
        }
    val dispatch = dispatches.singleOrNull() ?: return false
    val addCall = dispatch.findAncestor(MethodCallExpr::class.java).orElse(null)
        ?: return false
    if (addCall.nameAsString != "add" ||
        addCall.arguments.size != 2 ||
        addCall.arguments[1] !== dispatch ||
        (addCall.scope.orElse(null) as? NameExpr)?.nameAsString != tablesName ||
        (addCall.arguments[0] as? NameExpr)?.nameAsString != blockName
    ) {
        return false
    }
    val bonusCalls = dispatch.arguments[1].findAll(MethodCallExpr::class.java)
        .filter { call ->
            call.nameAsString == "addOreBonusCount" &&
                call.arguments.size == 1 &&
                call.scope.map {
                    declaredHolderCanonicalOwner(
                        it,
                        APPLY_BONUS_COUNT_SIMPLE,
                        APPLY_BONUS_COUNT,
                        unit,
                        source
                    )
                }.orElse(false)
        }
    val bonus = bonusCalls.singleOrNull() ?: return false
    val key = bonus.arguments.single()
    if (!declaredHolderCanonicalStaticField(
            key,
            ENCHANTMENTS_SIMPLE,
            ENCHANTMENTS,
            unit,
            source
        )
    ) {
        return false
    }

    dispatch.setScope(NameExpr(tablesName))
    bonus.setArgument(
        0,
        parser.parseExpression<Expression>(
            "$tablesName.getRegistries().holderOrThrow($key)"
        ).result.orElseThrow()
    )
    return true
}

/** `BuiltInRegistries.X.getKey(holder)`: the registry's element type must be the holder's value type. */
private fun migrateBuiltInRegistryKeyArgument(
    call: MethodCallExpr,
    unit: CompilationUnit,
    parser: JavaParser
): Boolean {
    if (call.nameAsString != "getKey" || call.arguments.size != 1) return false
    val registry = call.scope.orElse(null) as? FieldAccessExpr ?: return false
    if (platformOwnerName(registry.scope, unit) != BUILT_IN_REGISTRIES) return false
    val element = TargetMembers.fieldSignatures(BUILT_IN_REGISTRIES)[registry.nameAsString]
        ?.let(::registryElementType)
        ?: return false
    val holder = platformDeferredHolderField(call.arguments.single(), unit) ?: return false
    if (holder.valueType != element) return false
    call.setArgument(
        0,
        parser.parseExpression<Expression>("${call.arguments.single()}.value()").result.orElseThrow()
    )
    return true
}

private fun StructuralRefactorPass.migrateProjectDeferredHolderArgument(
    call: MethodCallExpr,
    unit: CompilationUnit,
    parser: JavaParser
): Boolean {
    val holders = call.arguments.withIndex().mapNotNull { (index, argument) ->
        platformDeferredHolderField(argument, unit)?.let { index to it }
    }
    val (index, holder) = holders.singleOrNull() ?: return false
    val declarations = projectSourceDeclarations
    val receiver = runCatching { declarations.receiverType(call) }.getOrNull() ?: return false
    if (!declarations.declares(receiver)) return false
    val candidates = runCatching {
        declarations.methods(receiver, call.nameAsString, call.arguments.size)
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false

    var applicable = 0
    for (candidate in candidates) {
        val parameters = candidate.declaration.parameters.map { declarations.resolve(it.type, it) }
        val others = call.arguments.indices.filter { it != index }.map {
            literalArgumentFit(call.arguments[it], parameters[it], declarations)
        }
        if (others.any { it == HolderFit.NO }) continue
        val holderFit = platformTypeFit(DEFERRED_HOLDER, parameters[index], declarations)
        if (holderFit != HolderFit.NO) return false
        val valueFit = platformTypeFit(holder.valueType, parameters[index], declarations)
        when {
            valueFit == HolderFit.NO -> continue
            valueFit == HolderFit.YES && others.all { it == HolderFit.YES } -> applicable++
            else -> return false
        }
    }
    if (applicable != 1) return false
    call.setArgument(
        index,
        parser.parseExpression<Expression>("${call.arguments[index]}.value()").result.orElseThrow()
    )
    return true
}

private enum class HolderFit { YES, NO, UNKNOWN }

/** Whether a value of the platform class [actual] can be passed as a parameter of type [parameter]. */
private fun platformTypeFit(
    actual: String,
    parameter: TypeRef?,
    declarations: ProjectSourceDeclarations
): HolderFit = when (parameter) {
    null, is TypeRef.Variable -> HolderFit.UNKNOWN
    is TypeRef.Primitive -> HolderFit.NO
    is TypeRef.Named -> when {
        parameter.name == actual || parameter.name == "java.lang.Object" -> HolderFit.YES
        // a platform class never extends or implements a project type
        declarations.declares(parameter.name) -> HolderFit.NO
        else -> {
            val hierarchy = platformHierarchy(actual)
            val target = ProjectSourceDeclarations.platformBinary(parameter.name) ?: parameter.name
            when {
                hierarchy == null -> HolderFit.UNKNOWN
                target in hierarchy.types -> HolderFit.YES
                ProjectSourceDeclarations.platformBinary(parameter.name) != null -> HolderFit.NO
                parameter.name.startsWith("java.") && !hierarchy.throughJava -> HolderFit.NO
                else -> HolderFit.UNKNOWN
            }
        }
    }
}

private class PlatformHierarchy(val types: Set<String>, val throughJava: Boolean)

/** Every supertype the member table lists above [qualifiedName]; null when it is not a platform class. */
private fun platformHierarchy(qualifiedName: String): PlatformHierarchy? {
    val binary = ProjectSourceDeclarations.platformBinary(qualifiedName) ?: return null
    val types = linkedSetOf<String>()
    var throughJava = false
    val frontier = ArrayDeque(listOf(binary))
    while (frontier.isNotEmpty()) {
        val current = frontier.removeFirst()
        if (!types.add(current)) continue
        if (TargetMembers.hasClass(current)) {
            frontier.addAll(TargetMembers.supertypesOf(current))
        } else if (current != "java.lang.Object") {
            // a java.* supertype's own supertypes are not in the table
            throughJava = true
        }
    }
    return PlatformHierarchy(types, throughJava)
}

private fun literalArgumentFit(
    argument: Expression,
    parameter: TypeRef?,
    declarations: ProjectSourceDeclarations
): HolderFit {
    val accepted = when (argument) {
        is IntegerLiteralExpr -> setOf("int", "long", "float", "double", "java.lang.Integer", "java.lang.Number")
        is StringLiteralExpr -> setOf("java.lang.String", "java.lang.CharSequence")
        is BooleanLiteralExpr -> setOf("boolean", "java.lang.Boolean")
        is NullLiteralExpr -> return when (parameter) {
            is TypeRef.Primitive -> HolderFit.NO
            is TypeRef.Named -> HolderFit.YES
            else -> HolderFit.UNKNOWN
        }
        else -> return HolderFit.UNKNOWN
    }
    return when (parameter) {
        null, is TypeRef.Variable -> HolderFit.UNKNOWN
        is TypeRef.Primitive -> if (parameter.name in accepted) HolderFit.YES else HolderFit.NO
        is TypeRef.Named -> when {
            parameter.name in accepted || parameter.name == "java.lang.Object" -> HolderFit.YES
            declarations.declares(parameter.name) ||
                ProjectSourceDeclarations.platformBinary(parameter.name) != null ||
                parameter.name.startsWith("java.lang.") -> HolderFit.NO
            else -> HolderFit.UNKNOWN
        }
    }
}

private data class PlatformDeferredHolderField(val owner: String, val name: String, val valueType: String)

/** A static platform field the 1.21.1 member table declares as `DeferredHolder<R, T>` with a plain T. */
private fun platformDeferredHolderField(
    expression: Expression,
    unit: CompilationUnit
): PlatformDeferredHolderField? {
    val field = expression as? FieldAccessExpr ?: return null
    if (!Regex("""[A-Z][A-Z0-9_]*""").matches(field.nameAsString)) return null
    val owner = platformOwnerName(field.scope, unit) ?: return null
    val binary = ProjectSourceDeclarations.platformBinary(owner) ?: return null
    val signature = TargetMembers.fieldSignatures(binary)[field.nameAsString] ?: return null
    val value = deferredHolderValueType(signature) ?: return null
    return PlatformDeferredHolderField(owner, field.nameAsString, value)
}

/** The canonical platform class [expression] names: an unshadowed single-type import or a qualified name. */
private fun platformOwnerName(expression: Expression, unit: CompilationUnit): String? {
    val name = when (expression) {
        is NameExpr -> importedTypeName(expression.nameAsString, unit) ?: return null
        is FieldAccessExpr -> expression.toString().takeIf { text ->
            text.split('.').let { parts -> parts.first().firstOrNull()?.isLowerCase() == true }
        } ?: return null
        else -> return null
    }
    return name.takeIf { ProjectSourceDeclarations.platformBinary(it) != null }
}

private fun importedTypeName(simple: String, unit: CompilationUnit): String? {
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return null
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simple }) return null
    if (unit.findAll(Parameter::class.java).any { it.nameAsString == simple }) return null
    if (unit.findAll(VariableDeclarator::class.java).any { it.nameAsString == simple }) return null
    return unit.imports
        .filter { !it.isStatic && !it.isAsterisk && it.name.identifier == simple }
        .singleOrNull()
        ?.nameAsString
}

/** Cheap admission: the file imports a platform class that declares a `DeferredHolder` constant. */
private fun importsDeferredHolderOwner(source: String): Boolean =
    IMPORT.findAll(JavaSourceText.maskCommentsAndLiterals(source)).any { match ->
        val qualified = match.groupValues[1]
        val binary = ProjectSourceDeclarations.platformBinary(qualified) ?: return@any false
        TargetMembers.fieldSignatures(binary).values.any { it.startsWith(DEFERRED_HOLDER_SIGNATURE) } &&
            source.contains("${qualified.substringAfterLast('.')}.")
    }

private fun deferredHolderValueType(signature: String): String? {
    if (!signature.startsWith(DEFERRED_HOLDER_SIGNATURE) || !signature.endsWith(">;")) return null
    val arguments = signatureArguments(
        signature.substring(DEFERRED_HOLDER_SIGNATURE.length, signature.length - 2)
    ) ?: return null
    val value = arguments.takeIf { it.size == 2 }?.last() ?: return null
    return plainSignatureClass(value)
}

/** `Lnet/minecraft/core/DefaultedRegistry<Lnet/.../Fluid;>;` -> `net.minecraft.world.level.material.Fluid`. */
private fun registryElementType(signature: String): String? {
    val open = signature.indexOf('<')
    if (open < 0 || !signature.endsWith(">;")) return null
    val owner = signature.substring(1, open).replace('/', '.')
    val registry = owner.replace('$', '.')
    if (!TargetMembers.inheritsFrom(owner, "net.minecraft.core.Registry") && registry != "net.minecraft.core.Registry") {
        return null
    }
    val arguments = signatureArguments(signature.substring(open + 1, signature.length - 2)) ?: return null
    return plainSignatureClass(arguments.singleOrNull() ?: return null)
}

private fun plainSignatureClass(argument: String): String? {
    if (!argument.startsWith("L") || !argument.endsWith(";") || '<' in argument) return null
    return argument.substring(1, argument.length - 1).replace('/', '.').replace('$', '.')
}

private fun signatureArguments(text: String): List<String>? {
    val parts = mutableListOf<String>()
    var start = 0
    var depth = 0
    for ((index, character) in text.withIndex()) {
        when (character) {
            '<' -> depth++
            '>' -> depth--
            ';' -> if (depth == 0) {
                parts += text.substring(start, index + 1)
                start = index + 1
            }
            '*' -> if (depth == 0 && index == start) {
                parts += "*"
                start = index + 1
            }
        }
    }
    return parts.takeIf { start == text.length }
}

private fun declaredHolderCanonicalStaticField(
    expression: Expression,
    simpleOwner: String,
    qualifiedOwner: String,
    unit: CompilationUnit,
    source: String
): Boolean {
    val field = expression as? FieldAccessExpr ?: return false
    if (!Regex("""[A-Z][A-Z0-9_]*""").matches(field.nameAsString)) return false
    return declaredHolderCanonicalOwner(
        field.scope,
        simpleOwner,
        qualifiedOwner,
        unit,
        source
    )
}

private fun declaredHolderCanonicalOwner(
    expression: Expression,
    simple: String,
    qualified: String,
    unit: CompilationUnit,
    source: String
): Boolean {
    if (expression.toString() == qualified) return true
    val name = expression as? NameExpr ?: return false
    if (name.nameAsString != simple) return false
    if (!declaredHolderCanonicalSimpleType(simple, qualified, unit, source)) return false
    if (unit.findAll(Parameter::class.java).any { it.nameAsString == simple }) return false
    return unit.findAll(VariableDeclarator::class.java).none { it.nameAsString == simple }
}

private fun declaredHolderCanonicalSimpleType(
    simple: String,
    qualified: String,
    unit: CompilationUnit,
    source: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return false
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simple }) return false
    val imported = unit.imports.any {
        !it.isStatic && !it.isAsterisk && it.nameAsString == qualified
    } || Regex("""(?m)^\s*import\s+${Regex.escape(qualified)}\s*;""")
        .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source))
    if (!imported) return false
    return unit.imports.none {
        !it.isStatic &&
            !it.isAsterisk &&
            it.name.identifier == simple &&
            it.nameAsString != qualified
    }
}

private val IMPORT = Regex("""(?m)^\s*import\s+(?!static\b)([\w.]+)\s*;""")
private const val DEFERRED_HOLDER = "net.neoforged.neoforge.registries.DeferredHolder"
private const val DEFERRED_HOLDER_SIGNATURE = "Lnet/neoforged/neoforge/registries/DeferredHolder<"
private const val REGISTRATE_BLOCK_LOOT_TABLES_SIMPLE = "RegistrateBlockLootTables"
private const val REGISTRATE_BLOCK_LOOT_TABLES =
    "com.tterrag.registrate.providers.loot.RegistrateBlockLootTables"
private const val APPLY_BONUS_COUNT_SIMPLE = "ApplyBonusCount"
private const val APPLY_BONUS_COUNT =
    "net.minecraft.world.level.storage.loot.functions.ApplyBonusCount"
private const val ENCHANTMENTS_SIMPLE = "Enchantments"
private const val ENCHANTMENTS = "net.minecraft.world.item.enchantment.Enchantments"
private const val BUILT_IN_REGISTRIES = "net.minecraft.core.registries.BuiltInRegistries"
