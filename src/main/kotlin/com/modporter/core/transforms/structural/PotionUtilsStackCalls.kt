package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText

/** Exact, owner-aware migration of the removed 1.20.1 PotionUtils stack API. */
internal object PotionUtilsStackCalls {
    fun migrate(
        source: String,
        emptyPotionExpression: (() -> String)? = null
    ): String =
        migrateRemovedEmptyPotionRegistryGuards(
            migrateLegacyPotionUtilsCalls(source, emptyPotionExpression)
        )

    private fun migrateLegacyPotionUtilsCalls(
        source: String,
        emptyPotionExpression: (() -> String)?
    ): String {
        val executable = JavaSourceText.maskCommentsAndLiterals(source)
        if (!executable.contains("PotionUtils.")) return source

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = parser.parse(source)
        val cu = parsed.result.orElseThrow {
            IllegalStateException(
                "Cannot parse source containing legacy Minecraft PotionUtils calls: ${parsed.problems.joinToString("; ") { it.verboseMessage }.ifBlank { "unknown parse failure" }}"
            )
        }
        LexicalPreservingPrinter.setup(cu)

        val potionUtilsFqn = "net.minecraft.world.item.alchemy.PotionUtils"
        val potionFqn = "net.minecraft.world.item.alchemy.Potion"
        val potionContentsFqn = "net.minecraft.world.item.alchemy.PotionContents"
        val dataComponentsFqn = "net.minecraft.core.component.DataComponents"
        val itemStackFqn = "net.minecraft.world.item.ItemStack"
        val potionsFqn = "net.minecraft.world.item.alchemy.Potions"
        val exact = ExactJavaSemantics(cu)

        fun isExactPotionUtilsCall(call: MethodCallExpr): Boolean =
            call.scope.map { exact.exactStaticScope(it, "PotionUtils", potionUtilsFqn) }.orElse(false)

        fun isExactStaticConstant(expression: Expression, simpleOwner: String, ownerFqn: String, name: String): Boolean {
            val field = expression as? FieldAccessExpr ?: return false
            return field.nameAsString == name &&
                exact.exactStaticScope(field.scope, simpleOwner, ownerFqn)
        }

        val potionContentsRef =
            exact.targetTypeReferenceOrNull(
                "PotionContents",
                potionContentsFqn,
                expressionScope = true
            ) ?: return source
        val dataComponentsRef =
            exact.targetTypeReferenceOrNull(
                "DataComponents",
                dataComponentsFqn,
                expressionScope = true
            ) ?: return source
        fun isExactItemStackType(typeText: String): Boolean =
            exact.exactTypeReference(typeText, "ItemStack", itemStackFqn)

        fun isProvablyItemStack(expression: Expression): Boolean =
            exact.isProvablyType(expression, "ItemStack", itemStackFqn)

        fun potionContents(expression: Expression): String =
            "($expression).getOrDefault($dataComponentsRef.POTION_CONTENTS, $potionContentsRef.EMPTY)"

        fun parseExpression(text: String): Expression =
            StaticJavaParser.parseExpression(text)

        val usedNames = cu.findAll(SimpleName::class.java).map { it.asString() }.toMutableSet()
        var generatedNameCounter = 0
        fun generatedName(base: String): String {
            while (true) {
                val suffix = if (generatedNameCounter == 0) "" else generatedNameCounter.toString()
                generatedNameCounter++
                val candidate = base + suffix
                if (usedNames.add(candidate)) return candidate
            }
        }

        fun legacyEffectList(contents: String): String {
            val listName = generatedName("modPorterPotionEffects")
            val contentsName = generatedName("modPorterPotionContents")
            val holderName = generatedName("modPorterPotionHolder")
            return "net.minecraft.Util.make(" +
                "new java.util.ArrayList<net.minecraft.world.effect.MobEffectInstance>(), " +
                "$listName -> { $potionContentsRef $contentsName = $contents; " +
                "$contentsName.potion().ifPresent($holderName -> " +
                "$listName.addAll($holderName.value().getEffects())); " +
                "$listName.addAll($contentsName.customEffects()); })"
        }

        var changed = false
        var needsDataComponents = false
        var needsPotionContents = false

        fun replace(
            node: Node,
            replacement: Expression,
            requiresDataComponents: Boolean = true,
            requiresPotionContents: Boolean = true
        ) {
            node.replace(replacement)
            changed = true
            needsDataComponents =
                needsDataComponents || requiresDataComponents && dataComponentsRef == "DataComponents"
            needsPotionContents =
                needsPotionContents || requiresPotionContents && potionContentsRef == "PotionContents"
        }

        val calls = cu.findAll(MethodCallExpr::class.java)
            .filter(::isExactPotionUtilsCall)
            .sortedByDescending { call ->
                generateSequence(call.parentNode.orElse(null)) { it.parentNode.orElse(null) }.count()
            }

        for (call in calls) {
            if (call.parentNode.isEmpty) continue
            when (call.nameAsString) {
                "setPotion" -> {
                    if (call.arguments.size != 2) continue
                    val stack = call.arguments[0]
                    val potion = call.arguments[1]
                    val clearsPotion = isExactStaticConstant(potion, "Potions", potionsFqn, "EMPTY")
                    val exactBuiltInPotion = (potion as? FieldAccessExpr)?.let { field ->
                        field.nameAsString != "EMPTY" &&
                            exact.exactStaticScope(field.scope, "Potions", potionsFqn)
                    } == true
                    val targetPotion = when {
                        clearsPotion -> null
                        exactBuiltInPotion -> potion.toString()
                        else -> continue
                    }
                    val createdStack = stack as? ObjectCreationExpr
                    val exactOneItemStack = createdStack != null &&
                        createdStack.arguments.size == 1 &&
                        createdStack.anonymousClassBody.isEmpty &&
                        isExactItemStackType(createdStack.typeAsString)
                    val exactCreatedStack = if (exactOneItemStack) createdStack else null

                    if (exactCreatedStack != null && clearsPotion) {
                        replace(
                            call,
                            exactCreatedStack.clone(),
                            requiresDataComponents = false,
                            requiresPotionContents = false
                        )
                        continue
                    }

                    val update = if (clearsPotion) {
                        val contentsName = generatedName("modPorterPotionContents")
                        "update($dataComponentsRef.POTION_CONTENTS, $potionContentsRef.EMPTY, $contentsName -> " +
                            "$contentsName.customColor().isEmpty() && $contentsName.customEffects().isEmpty() ? null : " +
                            "new $potionContentsRef(java.util.Optional.empty(), $contentsName.customColor(), " +
                            "$contentsName.customEffects()))"
                    } else {
                        "update($dataComponentsRef.POTION_CONTENTS, $potionContentsRef.EMPTY, " +
                            "$targetPotion, $potionContentsRef::withPotion)"
                    }
                    if (call.parentNode.orElse(null) is com.github.javaparser.ast.stmt.ExpressionStmt) {
                        replace(call, parseExpression("($stack).$update"))
                    } else {
                        val stackName = generatedName("modPorterPotionStack")
                        replace(
                            call,
                            parseExpression("net.minecraft.Util.make($stack, $stackName -> $stackName.$update)")
                        )
                    }
                }

                "setCustomEffects" -> {
                    if (call.arguments.size != 2) continue
                    val stack = call.arguments[0]
                    val effects = call.arguments[1]
                    val stackName = generatedName("modPorterPotionStack")
                    val contentsName = generatedName("modPorterPotionContents")
                    val effectsName = generatedName("modPorterPotionEffects")
                    val update =
                        "$stackName.update($dataComponentsRef.POTION_CONTENTS, $potionContentsRef.EMPTY, " +
                            "$contentsName -> new $potionContentsRef($contentsName.potion(), " +
                            "$contentsName.customColor(), java.util.stream.Stream.concat(" +
                            "$contentsName.customEffects().stream(), $effectsName.stream().map(" +
                            "net.minecraft.world.effect.MobEffectInstance::new)).toList()))"
                    replace(
                        call,
                        parseExpression(
                            "net.minecraft.Util.make($stack, $stackName -> " +
                                "net.minecraft.Util.make($effects, $effectsName -> { " +
                                "if (!$effectsName.isEmpty()) { $update; } }))"
                        )
                    )
                }

                "getPotion" -> {
                    if (call.arguments.size != 1 || !isProvablyItemStack(call.arguments.single())) continue
                    val contents = potionContents(call.arguments.single())
                    val comparison = call.parentNode.orElse(null) as? BinaryExpr
                    if (comparison != null &&
                        comparison.operator in setOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS)
                    ) {
                        val other = when {
                            comparison.left === call -> comparison.right
                            comparison.right === call -> comparison.left
                            else -> null
                        }
                        val replacement = when {
                            other == null -> null
                            isExactStaticConstant(other, "Potions", potionsFqn, "EMPTY") -> {
                                val method =
                                    if (comparison.operator == BinaryExpr.Operator.EQUALS) "isEmpty" else "isPresent"
                                parseExpression("$contents.potion().$method()")
                        }
                        other is FieldAccessExpr &&
                            exact.exactStaticScope(other.scope, "Potions", potionsFqn) -> {
                                val holderName = generatedName("modPorterPotionHolder")
                                val membership =
                                    "$contents.potion().map($holderName -> " +
                                        "$holderName.value() == $other.value()).orElse(false)"
                                parseExpression(
                                    if (comparison.operator == BinaryExpr.Operator.EQUALS) membership else "!$membership"
                                )
                            }
                            else -> null
                        }
                        if (replacement != null) replace(comparison, replacement)
                        continue
                    }

                    val directReturn = call.parentNode.orElse(null) as? ReturnStmt
                    val returnMethod = directReturn
                        ?.takeIf {
                            it.expression.orElse(null) === call &&
                                it.findAncestor(LambdaExpr::class.java).isEmpty
                        }
                        ?.findAncestor(MethodDeclaration::class.java)
                        ?.orElse(null)
                    if (returnMethod != null &&
                        exact.exactTypeReference(returnMethod.typeAsString, "Potion", potionFqn)
                    ) {
                        val emptyPotion = emptyPotionExpression?.invoke() ?: continue
                        replace(
                            call,
                            parseExpression(
                                "$contents.potion().map(net.minecraft.core.Holder::value)" +
                                    ".orElse($emptyPotion)"
                            )
                        )
                        continue
                    }

                    val variable = call.parentNode.orElse(null) as? VariableDeclarator ?: continue
                    if (variable.initializer.orElse(null) !== call ||
                        !exact.exactTypeReference(
                            variable.typeAsString,
                            "Potion",
                            potionFqn
                        )
                    ) {
                        continue
                    }
                    val declaration = variable.parentNode.orElse(null) as? VariableDeclarationExpr ?: continue
                    if (declaration.variables.size != 1) continue
                    val uses = exact.referencesTo(variable)
                    val supportedComparisons = mutableListOf<Triple<BinaryExpr, FieldAccessExpr, Boolean>>()
                    var graphSupported = true
                    for (use in uses) {
                        val binary = use.parentNode.orElse(null) as? BinaryExpr
                        if (binary == null ||
                            binary.operator !in setOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS)
                        ) {
                            graphSupported = false
                            break
                        }
                        val other = when {
                            binary.left === use -> binary.right
                            binary.right === use -> binary.left
                            else -> null
                        } as? FieldAccessExpr
                        if (other == null || !exact.exactStaticScope(other.scope, "Potions", potionsFqn)) {
                            graphSupported = false
                            break
                        }
                        supportedComparisons += Triple(
                            binary,
                            other,
                            binary.operator == BinaryExpr.Operator.EQUALS
                        )
                    }
                    if (!graphSupported) continue

                    for ((binary, other, equals) in supportedComparisons) {
                        val replacement = if (other.nameAsString == "EMPTY") {
                            parseExpression(
                                "${variable.nameAsString}.${if (equals) "isEmpty" else "isPresent"}()"
                            )
                        } else {
                            val holderName = generatedName("modPorterPotionHolder")
                            val membership =
                                "${variable.nameAsString}.map($holderName -> " +
                                    "$holderName.value() == $other.value()).orElse(false)"
                            parseExpression(if (equals) membership else "!$membership")
                        }
                        replace(binary, replacement)
                    }
                    variable.setType(
                        StaticJavaParser.parseType(
                            "java.util.Optional<net.minecraft.core.Holder<" +
                                "net.minecraft.world.item.alchemy.Potion>>"
                        )
                    )
                    replace(call, parseExpression("$contents.potion()"))
                }

                "getColor" -> {
                    if (call.arguments.size != 1 || !isProvablyItemStack(call.arguments.single())) continue
                    replace(call, parseExpression("${potionContents(call.arguments.single())}.getColor()"))
                }

                "getCustomEffects" -> {
                    if (call.arguments.size != 1 || !isProvablyItemStack(call.arguments.single())) continue
                    replace(
                        call,
                        parseExpression(
                            "new java.util.ArrayList<>(${potionContents(call.arguments.single())}.customEffects())"
                        )
                    )
                }

                "getMobEffects", "getAllEffects" -> {
                    if (call.arguments.size != 1 || !isProvablyItemStack(call.arguments.single())) continue
                    val allEffects = legacyEffectList(potionContents(call.arguments.single()))
                    val parentCall = call.parentNode.orElse(null) as? MethodCallExpr
                    if (parentCall != null &&
                        parentCall.nameAsString == "isEmpty" &&
                        parentCall.arguments.isEmpty() &&
                        parentCall.scope.map { it === call }.orElse(false)
                    ) {
                        replace(parentCall, parseExpression("$allEffects.isEmpty()"))
                    } else if (call.parentNode.orElse(null) is com.github.javaparser.ast.stmt.ForEachStmt) {
                        replace(call, parseExpression(allEffects))
                    } else {
                        replace(call, parseExpression(allEffects))
                    }
                }

                "addPotionTooltip" -> {
                    if (call.arguments.size != 3 || !isProvablyItemStack(call.arguments[0])) continue
                    val stack = call.arguments[0]
                    val tooltip = call.arguments[1]
                    val duration = call.arguments[2]
                    val tooltipTarget = if (tooltip is NameExpr || tooltip is FieldAccessExpr) {
                        tooltip.toString()
                    } else {
                        "($tooltip)"
                    }
                    replace(
                        call,
                        parseExpression(
                            "${potionContents(stack)}.addPotionTooltip($tooltipTarget::add, $duration, 20.0F)"
                        )
                    )
                }
            }
        }

        if (!changed) return source
        var result = LexicalPreservingPrinter.print(cu)
        if (needsDataComponents) {
            result = addImportIfMissing(result, dataComponentsFqn)
        }
        if (needsPotionContents) {
            result = addImportIfMissing(result, potionContentsFqn)
        }
        if (!JavaSourceText.maskCommentsAndLiterals(result).contains("PotionUtils.")) {
            result = removeImport(result, potionUtilsFqn)
        }
        return result
    }

    private fun migrateRemovedEmptyPotionRegistryGuards(source: String): String {
        val executable = JavaSourceText.maskCommentsAndLiterals(source)
        if (!executable.contains("Potions.EMPTY") ||
            !executable.contains("BuiltInRegistries.POTION")
        ) {
            return source
        }

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = parser.parse(source)
        val cu = parsed.result.orElseThrow {
            IllegalStateException(
                "Cannot parse source containing removed empty potion registry guard: ${parsed.problems.joinToString("; ") { it.verboseMessage }.ifBlank { "unknown parse failure" }}"
            )
        }
        LexicalPreservingPrinter.setup(cu)

        val exact = ExactJavaSemantics(cu)
        val potionFqn = "net.minecraft.world.item.alchemy.Potion"
        val potionsFqn = "net.minecraft.world.item.alchemy.Potions"
        val builtInRegistriesFqn = "net.minecraft.core.registries.BuiltInRegistries"

        fun iteratesBuiltInPotionRegistry(expression: Expression): Boolean {
            var streamExpression = expression
            val toList = streamExpression as? MethodCallExpr
            if (toList != null &&
                toList.nameAsString == "toList" &&
                toList.arguments.isEmpty()
            ) {
                streamExpression = toList.scope.orElse(null) ?: return false
            }
            val stream = streamExpression as? MethodCallExpr ?: return false
            if (stream.nameAsString != "stream" || stream.arguments.isNotEmpty()) return false
            val registry = stream.scope.orElse(null) as? FieldAccessExpr ?: return false
            return registry.nameAsString == "POTION" &&
                exact.exactStaticScope(
                    registry.scope,
                    "BuiltInRegistries",
                    builtInRegistriesFqn
                )
        }

        fun isEmptyPotionComparison(
            condition: Expression,
            variable: VariableDeclarator
        ): Boolean {
            val binary = condition as? BinaryExpr ?: return false
            if (binary.operator != BinaryExpr.Operator.EQUALS) return false
            val use = when {
                binary.left is NameExpr &&
                    binary.left.asNameExpr().nameAsString == variable.nameAsString ->
                    binary.left.asNameExpr()
                binary.right is NameExpr &&
                    binary.right.asNameExpr().nameAsString == variable.nameAsString ->
                    binary.right.asNameExpr()
                else -> return false
            }
            if (exact.referencesTo(variable).none { it === use }) return false
            val other = if (binary.left === use) binary.right else binary.left
            val constant = other as? FieldAccessExpr ?: return false
            return constant.nameAsString == "EMPTY" &&
                exact.exactStaticScope(constant.scope, "Potions", potionsFqn)
        }

        fun isUnlabelledContinue(statement: com.github.javaparser.ast.stmt.Statement): Boolean =
            statement is com.github.javaparser.ast.stmt.ContinueStmt && statement.label.isEmpty

        fun isOnlyContinue(statement: com.github.javaparser.ast.stmt.Statement): Boolean =
            isUnlabelledContinue(statement) ||
                statement is com.github.javaparser.ast.stmt.BlockStmt &&
                statement.statements.size == 1 &&
                isUnlabelledContinue(statement.statements.single())

        var changed = false
        cu.findAll(com.github.javaparser.ast.stmt.ForEachStmt::class.java).forEach { loop ->
            if (loop.variable.variables.size != 1) return@forEach
            val variable = loop.variable.variables.single()
            if (!exact.exactTypeReference(variable.typeAsString, "Potion", potionFqn) ||
                !iteratesBuiltInPotionRegistry(loop.iterable)
            ) {
                return@forEach
            }
            val body = loop.body as? com.github.javaparser.ast.stmt.BlockStmt ?: return@forEach
            val guard = body.statements.filterIsInstance<IfStmt>().singleOrNull {
                it.elseStmt.isEmpty &&
                    isOnlyContinue(it.thenStmt) &&
                    isEmptyPotionComparison(it.condition, variable)
            } ?: return@forEach
            guard.remove()
            changed = true
        }

        if (!changed) return source
        var result = LexicalPreservingPrinter.print(cu)
        if (!JavaSourceText.maskCommentsAndLiterals(result).contains("Potions.")) {
            result = removeImport(result, potionsFqn)
        }
        return result
    }

    private fun addImportIfMissing(source: String, importName: String): String {
        val importLine = "import $importName;"
        if (Regex("(?m)^\\s*import\\s+${Regex.escape(importName)}\\s*;\\s*$").containsMatchIn(source)) {
            return source
        }
        val packageDeclaration = Regex("(?m)^\\s*package\\s+[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\s*;\\s*(?:\\r?\\n)?")
            .find(source)
            ?: return "$importLine\n$source"
        return source.substring(0, packageDeclaration.range.last + 1) +
            "$importLine\n" + source.substring(packageDeclaration.range.last + 1)
    }

    private fun removeImport(source: String, importName: String): String =
        Regex("(?m)^\\s*import\\s+${Regex.escape(importName)}\\s*;[ \t]*(?:\\r?\\n)?")
            .replace(source, "")
}
