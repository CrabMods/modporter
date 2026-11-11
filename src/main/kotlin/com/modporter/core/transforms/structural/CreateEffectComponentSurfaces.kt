package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Retains the exact Create 1.20 potion/effect/component behavior at the final 1.21 source boundary.
 *
 * The conversions here are intentionally source-owned and narrow. They preserve a FluidStack's
 * legacy custom tag, the old milk cure identity, PotionUtils' mutable ordered result list, fixed
 * attribute-tooltip hiding, and the stored JSON click-event audit without deserializing rich text
 * against an empty registry.
 */
internal object CreateEffectComponentSurfaces {
    const val RULE_ID = "struct-create-effect-component-surfaces"

    private const val FLUID_STACK = "net.neoforged.neoforge.fluids.FluidStack"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val ITEMS = "net.minecraft.world.item.Items"
    private const val LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity"
    private const val MOB_EFFECT_INSTANCE = "net.minecraft.world.effect.MobEffectInstance"
    private const val DATA_COMPONENTS = "net.minecraft.core.component.DataComponents"
    private const val POTION_CONTENTS = "net.minecraft.world.item.alchemy.PotionContents"
    private const val TOOLTIP_PART = "net.minecraft.world.item.ItemStack.TooltipPart"
    private const val NBT_PROCESSORS = "net.createmod.catnip.nbt.NBTProcessors"
    private const val TAG = "net.minecraft.nbt.Tag"
    private const val CLICK_HELPER = "modporter\$legacyTextComponentHasClickEvent"
    private const val COMPONENT_NODE = "modporter\$LegacyTextComponent"
    private const val DECODE_HELPER = "modporter\$decodeLegacyTextComponent"
    private const val TOUCH_HELPER = "modporter\$touchLegacyTextComponent"
    private const val OBJECT_HELPER = "modporter\$legacyJsonObject"
    private const val ARRAY_HELPER = "modporter\$legacyJsonArray"
    private const val STRING_HELPER = "modporter\$legacyJsonString"
    private const val OPTIONAL_STRING_HELPER =
        "modporter\$legacyOptionalJsonString"
    private const val BOOLEAN_HELPER = "modporter\$legacyJsonBoolean"
    private const val INT_HELPER = "modporter\$legacyJsonInt"
    private const val RESOURCE_HELPER = "modporter\$validateLegacyResource"
    private const val STYLE_HELPER = "modporter\$validateLegacyStyle"
    private const val HOVER_HELPER = "modporter\$validateLegacyHover"
    private const val HOVER_CONTENTS_HELPER =
        "modporter\$validateLegacyHoverContents"

    private data class SourcePlan(
        val file: Path,
        val original: String,
        val migrated: String,
        val before: String,
        val after: String
    )

    private data class Parsed(
        val source: String,
        val unit: com.github.javaparser.ast.CompilationUnit,
        val typing: SourceValueTypes,
        val lineStarts: List<Int>
    ) {
        fun offset(node: Node): Int = node.range.map {
            lineStarts[it.begin.line - 1] + it.begin.column - 1
        }.orElse(-1)

        fun endOffset(node: Node): Int = node.range.map {
            lineStarts[it.end.line - 1] + it.end.column
        }.orElse(-1)
    }

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): List<Change> {
        val root = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val plans = listOfNotNull(
            planPotionFluid(pass, parser, root),
            planMilkEffect(pass, parser, root),
            planPotionEffects(pass, parser, root),
            planTooltipModifiers(pass, parser, root),
            planDeployerFoodGuard(pass, parser, root),
            planLegacyTextAudit(pass, parser, root)
        )
        return publishPlans(pass, parser, plans, dryRun)
    }

    /** Close potion tag aliases before the legacy PotionUtils owner is relocated. */
    fun migratePotionData(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): List<Change> {
        val root = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        return publishPlans(pass, parser, listOfNotNull(planPotionFluid(pass, parser, root)), dryRun)
    }

    private fun publishPlans(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        plans: List<SourcePlan>,
        dryRun: Boolean
    ): List<Change> {
        if (plans.isEmpty()) return emptyList()
        check(targetContractsVerified()) {
            "Create effect/component migration target ABI is not the verified 1.21.1 contract"
        }
        plans.forEach { plan ->
            val reparsed = parser.parse(plan.migrated)
            check(reparsed.isSuccessful) {
                "Create effect/component migration emitted invalid Java in " +
                    "${plan.file}: ${pass.parseProblems(reparsed)}"
            }
        }
        if (!dryRun) plans.forEach { it.file.writeText(it.migrated) }
        return plans.map { plan ->
            Change(
                file = plan.file,
                line = 1,
                description = "Retain Create potion, effect, tooltip, and text-audit behavior",
                before = plan.before,
                after = plan.after,
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
    }

    private fun planPotionFluid(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        root: Path
    ): SourcePlan? {
        val file = root.resolve(
            "com/simibubi/create/content/fluids/potion/PotionFluid.java"
        )
        if (!Files.isRegularFile(file)) return null
        val parsed = parse(pass, parser, file)
        val appendMethods = parsed.unit.findAll(MethodDeclaration::class.java).filter {
            it.nameAsString == "appendEffects"
        }
        val legacyAppend = appendMethods.any { method ->
            method.findAll(MethodCallExpr::class.java).any {
                it.nameAsString == "getOrCreateTag"
            }
        }
        if (!parsed.source.contains(".removeChildTag(") && !legacyAppend) return null
        LexicalPreservingPrinter.setup(parsed.unit)
        var changed = false
        parsed.unit.findAll(MethodCallExpr::class.java).toList().forEach { call ->
            if (call.nameAsString != "removeChildTag" ||
                call.arguments.singleOrNull()?.let { it as? StringLiteralExpr }
                    ?.asString() != "Potion"
            ) {
                return@forEach
            }
            val receiver = call.scope.orElse(null) as? NameExpr ?: return@forEach
            if (parsed.typing.typeOf(
                    receiver.toString(), parsed.offset(call)
                ) != FLUID_STACK
            ) {
                return@forEach
            }
            val statement = call.parentNode.orElse(null) as? ExpressionStmt
                ?: return@forEach
            if (statement.expression !== call) return@forEach
            val dataName = uniqueName(parsed.source, "modporterPotionData")
            val tagName = uniqueName(parsed.source, "modporterPotionTag")
            val block = parser.parseBlock(
                """
                {
                    net.minecraft.world.item.component.CustomData $dataName =
                        ${receiver}.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    if ($dataName != null) {
                        net.minecraft.nbt.CompoundTag $tagName = $dataName.copyTag();
                        $tagName.remove("Potion");
                        ${receiver}.set(
                            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of($tagName));
                    }
                }
                """.trimIndent()
            ).result.orElseThrow()
            statement.replace(block)
            changed = true
        }
        if (legacyAppend) {
            val method = appendMethods.singleOrNull()
                ?: error("PotionFluid has ambiguous appendEffects declarations")
            val exact = ExactJavaSemantics(parsed.unit)
            check(method.isPublic && method.isStatic && method.parameters.size == 2 &&
                exact.exactTypeReference(method.typeAsString, "FluidStack", FLUID_STACK) &&
                method.parameters[0].nameAsString == "fs" &&
                exact.exactTypeReference(method.parameters[0].typeAsString, "FluidStack", FLUID_STACK) &&
                method.parameters[1].nameAsString == "customEffects" &&
                method.parameters[1].typeAsString == "Collection<MobEffectInstance>" &&
                exact.exactTypeReference("Collection", "Collection", "java.util.Collection") &&
                exact.exactTypeReference("MobEffectInstance", "MobEffectInstance", MOB_EFFECT_INSTANCE) &&
                exact.exactTypeReference("CompoundTag", "CompoundTag", "net.minecraft.nbt.CompoundTag") &&
                exact.exactTypeReference("ListTag", "ListTag", "net.minecraft.nbt.ListTag")
            ) { "PotionFluid.appendEffects is outside the exact source-owned signature" }
            val expected = StaticJavaParser.parseBlock("""
                {
                    if (customEffects.isEmpty())
                        return fs;
                    CompoundTag compoundnbt = fs.getOrCreateTag();
                    ListTag listnbt = compoundnbt.getList("CustomPotionEffects", 9);
                    for (MobEffectInstance effectinstance : customEffects)
                        listnbt.add(effectinstance.save());
                    compoundnbt.put("CustomPotionEffects", listnbt);
                    return fs;
                }
            """.trimIndent())
            check(method.body.orElse(null) == expected) {
                "PotionFluid.appendEffects has an unproved child alias, write, exit, or consumer"
            }
            val index = JavaProjectTypeIndex.build(root)
            val receiver = method.findAll(NameExpr::class.java).first { it.nameAsString == "fs" }
            val compound = method.findAll(NameExpr::class.java).first { it.nameAsString == "compoundnbt" }
            val list = method.findAll(NameExpr::class.java).first { it.nameAsString == "listnbt" }
            check(index.isExpressionExactlyExternalType(receiver, receiver, FLUID_STACK) &&
                index.isExpressionExactlyExternalType(compound, compound, "net.minecraft.nbt.CompoundTag") &&
                index.isExpressionExactlyExternalType(list, list, "net.minecraft.nbt.ListTag") &&
                TargetMembers.inheritedMethod(
                    FLUID_STACK, "getOrDefault",
                    "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;"
                )?.access == "public" &&
                TargetMembers.inheritedMethod(
                    FLUID_STACK, "has", "(Lnet/minecraft/core/component/DataComponentType;)Z"
                )?.access == "public" &&
                TargetMembers.inheritedMethod(
                    "net.minecraft.nbt.CompoundTag", "get", "(Ljava/lang/String;)Lnet/minecraft/nbt/Tag;"
                )?.access == "public"
            ) { "PotionFluid.appendEffects target types or component read contract are not verified" }
            // An existing empty list is a live root child, whereas a missing/wrong-kind list
            // is detached until put. Preserve that distinction and exceptional partial writes.
            method.setBody(StaticJavaParser.parseBlock("""
                {
                    if (customEffects.isEmpty())
                        return fs;
                    CompoundTag compoundnbt = fs.getOrDefault(
                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    if (!fs.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA))
                        fs.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(compoundnbt));
                    ListTag listnbt = compoundnbt.getList("CustomPotionEffects", 9);
                    try {
                        for (MobEffectInstance effectinstance : customEffects) {
                            listnbt.add(effectinstance.save());
                            if (compoundnbt.get("CustomPotionEffects") == listnbt)
                                fs.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                                    net.minecraft.world.item.component.CustomData.of(compoundnbt));
                        }
                        compoundnbt.put("CustomPotionEffects", listnbt);
                    } finally {
                        fs.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(compoundnbt));
                    }
                    return fs;
                }
            """.trimIndent()))
            changed = true
        }
        check(changed) {
            "PotionFluid retains removeChildTag without one exact FluidStack Potion child removal"
        }
        return SourcePlan(
            file, parsed.source, LexicalPreservingPrinter.print(parsed.unit),
            "FluidStack legacy potion child and live effect-list access",
            "preserved potion child removal and effect-list reintegration with component writeback"
        )
    }

    private fun planMilkEffect(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        root: Path
    ): SourcePlan? {
        val file = root.resolve(
            "com/simibubi/create/impl/effect/MilkEffectHandler.java"
        )
        if (!Files.isRegularFile(file)) return null
        val parsed = parse(pass, parser, file)
        if (!parsed.source.contains(".curePotionEffects(")) return null
        LexicalPreservingPrinter.setup(parsed.unit)
        var changed = false
        parsed.unit.findAll(MethodCallExpr::class.java).toList().forEach { call ->
            if (call.nameAsString != "curePotionEffects" ||
                call.arguments.size != 1
            ) {
                return@forEach
            }
            val receiver = call.scope.orElse(null) ?: return@forEach
            if (parsed.typing.typeOf(
                    receiver.toString(), parsed.offset(call)
                ) != LIVING_ENTITY
            ) {
                return@forEach
            }
            val argument = unwrap(call.arguments.single()) as? NameExpr
                ?: return@forEach
            val method = call.findAncestor(MethodDeclaration::class.java)
                .orElse(null) ?: return@forEach
            val declaration = method.findAll(VariableDeclarator::class.java)
                .filter { it.nameAsString == argument.nameAsString }
                .filter { parsed.offset(it) < parsed.offset(call) }
                .maxByOrNull(parsed::offset) ?: return@forEach
            val declarationExpression =
                declaration.parentNode.orElse(null) as? VariableDeclarationExpr
                    ?: return@forEach
            val declarationStatement =
                declarationExpression.parentNode.orElse(null) as? ExpressionStmt
                    ?: return@forEach
            if (!parsed.typing.isTypeName(declaration.type.toString(), ITEM_STACK) ||
                declarationExpression.variables.size != 1 ||
                method.findAll(NameExpr::class.java).count {
                    it.nameAsString == argument.nameAsString &&
                        parsed.offset(it) in
                        parsed.offset(declaration)..parsed.endOffset(call)
                } != 1
            ) {
                return@forEach
            }
            val creation = declaration.initializer.orElse(null)
                ?.let(::unwrap) as? ObjectCreationExpr ?: return@forEach
            val item = creation.arguments.singleOrNull()
                ?.let(::unwrap) as? FieldAccessExpr ?: return@forEach
            if (!parsed.typing.isTypeName(creation.type.toString(), ITEM_STACK) ||
                item.nameAsString != "MILK_BUCKET" ||
                !parsed.typing.isTypeName(item.scope.toString(), ITEMS)
            ) {
                return@forEach
            }
            call.replace(
                parser.parseExpression<Expression>(
                    "${receiver}.removeEffectsCuredBy(EffectCures.MILK)"
                ).result.orElseThrow()
            )
            declarationStatement.remove()
            changed = true
        }
        check(changed) {
            "MilkEffectHandler retains curePotionEffects without one immutable milk cure source"
        }
        var result = LexicalPreservingPrinter.print(parsed.unit)
        result = with(pass) {
            addImportIfMissing(
                result, "net.neoforged.neoforge.common.EffectCures"
            )
        }
        result = with(pass) {
            removeUnusedSimpleImports(result, listOf(ITEM_STACK, ITEMS))
        }
        return SourcePlan(
            file, parsed.source, result,
            "LivingEntity.curePotionEffects(milk ItemStack)",
            "LivingEntity.removeEffectsCuredBy(EffectCures.MILK)"
        )
    }

    private fun planPotionEffects(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        root: Path
    ): SourcePlan? {
        val file = root.resolve(
            "com/simibubi/create/impl/effect/PotionEffectHandler.java"
        )
        if (!Files.isRegularFile(file)) return null
        val parsed = parse(pass, parser, file)
        if (!parsed.source.contains(".getAllEffects()")) return null
        LexicalPreservingPrinter.setup(parsed.unit)
        var changed = false
        parsed.unit.findAll(ReturnStmt::class.java).toList().forEach { returned ->
            val method = returned.findAncestor(MethodDeclaration::class.java)
                .orElse(null) ?: return@forEach
            if (!isEffectList(method.type, parsed.typing)) return@forEach
            val effects = returned.expression.orElse(null)
                ?.let(::unwrap) as? MethodCallExpr ?: return@forEach
            if (!isPotionContentsEffects(effects, parsed)) return@forEach
            val iterable = effects.clone().toString()
            effects.replace(
                parser.parseExpression<Expression>(
                    "java.util.stream.StreamSupport.stream(" +
                        "$iterable.spliterator(), false).collect(" +
                        "java.util.stream.Collectors.toCollection(" +
                        "java.util.ArrayList::new))"
                ).result.orElseThrow()
            )
            changed = true
        }
        if (!changed && parsed.unit.findAll(ReturnStmt::class.java).any {
                val method = it.findAncestor(MethodDeclaration::class.java)
                    .orElse(null)
                method != null &&
                    isEffectList(method.type, parsed.typing) &&
                    it.expression.orElse(null)?.let { expression ->
                        isMaterializedPotionContentsEffects(expression, parsed)
                    } == true
            }
        ) {
            return null
        }
        check(changed) {
            "PotionEffectHandler retains Iterable getAllEffects without one List return boundary"
        }
        return SourcePlan(
            file, parsed.source, LexicalPreservingPrinter.print(parsed.unit),
            "PotionContents.getAllEffects() Iterable returned as List",
            "ordered mutable ArrayList materialization with the same effect instances"
        )
    }

    private fun planTooltipModifiers(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        root: Path
    ): SourcePlan? {
        val file = root.resolve(
            "com/simibubi/create/foundation/data/recipe/LogStrippingFakeRecipes.java"
        )
        if (!Files.isRegularFile(file)) return null
        val parsed = parse(pass, parser, file)
        if (!parsed.source.contains(".hideTooltipPart(")) return null
        LexicalPreservingPrinter.setup(parsed.unit)
        var changed = false
        parsed.unit.findAll(MethodCallExpr::class.java).toList().forEach { call ->
            if (call.nameAsString != "hideTooltipPart" ||
                call.arguments.size != 1
            ) {
                return@forEach
            }
            val receiver = call.scope.orElse(null) ?: return@forEach
            val flag = unwrap(call.arguments.single()) as? FieldAccessExpr
                ?: return@forEach
            if (parsed.typing.typeOf(
                    receiver.toString(), parsed.offset(call)
                ) != ITEM_STACK ||
                flag.nameAsString != "MODIFIERS" ||
                !parsed.typing.isTypeName(flag.scope.toString(), TOOLTIP_PART)
            ) {
                return@forEach
            }
            call.replace(
                parser.parseExpression<Expression>(
                    "$receiver.set(DataComponents.ATTRIBUTE_MODIFIERS, " +
                        "$receiver.getOrDefault(" +
                        "DataComponents.ATTRIBUTE_MODIFIERS, " +
                        "ItemAttributeModifiers.EMPTY).withTooltip(false))"
                ).result.orElseThrow()
            )
            changed = true
        }
        check(changed) {
            "LogStrippingFakeRecipes retains hideTooltipPart without one exact MODIFIERS call"
        }
        var result = LexicalPreservingPrinter.print(parsed.unit)
        result = with(pass) {
            addImportIfMissing(result, DATA_COMPONENTS)
        }
        result = with(pass) {
            addImportIfMissing(
                result,
                "net.minecraft.world.item.component.ItemAttributeModifiers"
            )
        }
        result = with(pass) {
            removeUnusedSimpleImports(result, listOf(TOOLTIP_PART))
        }
        return SourcePlan(
            file, parsed.source, result,
            "ItemStack.TooltipPart.MODIFIERS",
            "the existing ATTRIBUTE_MODIFIERS component with its tooltip hidden"
        )
    }

    private fun planLegacyTextAudit(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        root: Path
    ): SourcePlan? {
        val file = root.resolve(
            "com/simibubi/create/foundation/CreateNBTProcessors.java"
        )
        if (!Files.isRegularFile(file)) return null
        val parsed = parse(pass, parser, file)
        if (!parsed.source.contains("textComponentHasClickEvent(")) return null
        LexicalPreservingPrinter.setup(parsed.unit)
        var changed = false
        parsed.unit.findAll(MethodCallExpr::class.java).toList().forEach { call ->
            if (call.nameAsString != "textComponentHasClickEvent" ||
                call.arguments.size != 1 ||
                call.scope.orElse(null)?.let {
                    parsed.typing.isTypeName(it.toString(), NBT_PROCESSORS)
                } != true ||
                !isLegacyText(call.arguments.single(), parsed)
            ) {
                return@forEach
            }
            call.removeScope()
            call.setName(CLICK_HELPER)
            changed = true
        }
        if (!changed) return null
        val owner = parsed.unit.getClassByName("CreateNBTProcessors")
            .orElse(null) ?: error(
            "CreateNBTProcessors click audit has no exact owning class"
        )
        val reservedMethods = setOf(
            CLICK_HELPER,
            DECODE_HELPER,
            TOUCH_HELPER,
            OBJECT_HELPER,
            ARRAY_HELPER,
            STRING_HELPER,
            OPTIONAL_STRING_HELPER,
            BOOLEAN_HELPER,
            INT_HELPER,
            RESOURCE_HELPER,
            STYLE_HELPER,
            HOVER_HELPER,
            HOVER_CONTENTS_HELPER
        )
        check(owner.methods.none { it.nameAsString in reservedMethods }) {
            "CreateNBTProcessors already declares the reserved click-audit helper"
        }
        check(
            owner.members.filterIsInstance<ClassOrInterfaceDeclaration>()
                .none { it.nameAsString == COMPONENT_NODE }
        ) {
            "CreateNBTProcessors already declares the reserved click-audit node"
        }
        addClickAuditHelpers(parser, owner)
        return SourcePlan(
            file, parsed.source, parsed.unit.toString(),
            "String JSON passed to the removed Catnip text audit overload",
            "direct recursive JSON clickEvent audit without registry-bound decoding"
        )
    }

    private fun planDeployerFoodGuard(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        root: Path
    ): SourcePlan? {
        val file = root.resolve(
            "com/simibubi/create/content/kinetics/deployer/DeployerHandler.java"
        )
        if (!Files.isRegularFile(file)) return null
        val parsed = parse(pass, parser, file)
        if (!parsed.source.contains(".isEdible()")) return null
        LexicalPreservingPrinter.setup(parsed.unit)
        val calls = parsed.unit.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "isEdible" &&
                call.arguments.isEmpty() &&
                call.scope.orElse(null)?.let { receiver ->
                    parsed.typing.typeOf(
                        receiver.toString(), parsed.offset(call)
                    ) == ITEM_STACK
                } == true
        }
        check(calls.size == 1) {
            "DeployerHandler must retain exactly one direct ItemStack.isEdible() guard"
        }
        val call = calls.single()
        val receiver = call.scope.orElseThrow().toString()
        val outer = call.findAncestor(IfStmt::class.java).orElse(null)
        check(outer != null && unwrap(outer.condition) === call) {
            "DeployerHandler ItemStack.isEdible() is not the exact food guard"
        }
        val foodVariables = outer.thenStmt.findAll(VariableDeclarator::class.java)
            .filter { variable ->
                parsed.typing.isTypeName(
                    variable.type.toString(),
                    "net.minecraft.world.food.FoodProperties"
                ) &&
                    variable.initializer.orElse(null)
                        ?.let(::unwrap)
                        ?.let { it as? MethodCallExpr }
                        ?.let { initializer ->
                            initializer.nameAsString == "getFoodProperties" &&
                                initializer.arguments.size == 2 &&
                                unwrap(initializer.arguments[0]).toString() ==
                                receiver
                        } == true
            }
        check(foodVariables.size == 1) {
            "DeployerHandler food guard has no exact FoodProperties lookup"
        }
        val foodVariable = foodVariables.single().nameAsString
        val canEatCalls = outer.thenStmt.findAll(MethodCallExpr::class.java)
            .filter { candidate ->
                candidate.nameAsString == "canEat" &&
                    candidate.arguments.size == 1 &&
                    (unwrap(candidate.arguments.single()) as? MethodCallExpr)
                        ?.let { always ->
                            always.nameAsString == "canAlwaysEat" &&
                                always.arguments.isEmpty() &&
                                always.scope.orElse(null)?.toString() ==
                                foodVariable
                        } == true
            }
        check(canEatCalls.size == 1) {
            "DeployerHandler food guard has no exact canEat call"
        }
        val canEat = canEatCalls.single()
        val inner = canEat.findAncestor(IfStmt::class.java).orElse(null)
        check(inner != null && unwrap(inner.condition) === canEat) {
            "DeployerHandler canEat call is not the exact nested condition"
        }
        call.replace(
            parser.parseExpression<Expression>(
                "$receiver.has(DataComponents.FOOD)"
            ).result.orElseThrow()
        )
        inner.setCondition(
            parser.parseExpression<Expression>(
                "$foodVariable != null && ${inner.condition}"
            ).result.orElseThrow()
        )
        val result = with(pass) {
            addImportIfMissing(
                LexicalPreservingPrinter.print(parsed.unit),
                DATA_COMPONENTS
            )
        }
        return SourcePlan(
            file, parsed.source, result,
            "ItemStack.isEdible() without a nullable target food-property guard",
            "the FOOD data component guard with the upstream null check"
        )
    }

    private fun parse(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        file: Path
    ): Parsed {
        val source = file.readText()
        val result = parser.parse(source)
        check(result.isSuccessful && result.result.isPresent) {
            "Cannot parse Create effect/component source $file"
        }
        return Parsed(
            source = source,
            unit = result.result.orElseThrow(),
            typing = SourceValueTypes(pass, source),
            lineStarts = buildList {
                add(0)
                source.forEachIndexed { index, char ->
                    if (char == '\n') add(index + 1)
                }
            }
        )
    }

    private fun isEffectList(
        type: com.github.javaparser.ast.type.Type,
        typing: SourceValueTypes
    ): Boolean {
        val list = type as? ClassOrInterfaceType ?: return false
        if (!typing.isTypeName(list.nameWithScope, "java.util.List")) return false
        val element = list.typeArguments.orElse(null)?.singleOrNull()
            ?: return false
        return typing.isTypeName(element.toString(), MOB_EFFECT_INSTANCE)
    }

    private fun isPotionContentsEffects(
        effects: MethodCallExpr,
        parsed: Parsed
    ): Boolean {
        if (effects.nameAsString != "getAllEffects" ||
            effects.arguments.isNotEmpty()
        ) {
            return false
        }
        val contents = effects.scope.orElse(null)
            ?.let(::unwrap) as? MethodCallExpr ?: return false
        if (contents.nameAsString != "getOrDefault" ||
            contents.arguments.size != 2
        ) {
            return false
        }
        val component = unwrap(contents.arguments[0]) as? FieldAccessExpr
            ?: return false
        val empty = unwrap(contents.arguments[1]) as? FieldAccessExpr
            ?: return false
        val stack = contents.scope.orElse(null) ?: return false
        return component.nameAsString == "POTION_CONTENTS" &&
            parsed.typing.isTypeName(component.scope.toString(), DATA_COMPONENTS) &&
            empty.nameAsString == "EMPTY" &&
            parsed.typing.isTypeName(empty.scope.toString(), POTION_CONTENTS) &&
            parsed.typing.typeOf(stack.toString(), parsed.offset(contents)) == ITEM_STACK
    }

    private fun isLegacyText(
        expression: Expression,
        parsed: Parsed
    ): Boolean = when (val value = unwrap(expression)) {
        is NameExpr ->
            parsed.typing.typeOf(value.toString(), parsed.offset(value)) ==
                "java.lang.String"
        is MethodCallExpr -> {
            val scope = value.scope.orElse(null)
            value.nameAsString == "getAsString" &&
                value.arguments.isEmpty() &&
                scope != null &&
                parsed.typing.typeOf(scope.toString(), parsed.offset(value)) == TAG &&
                TargetMembers.inheritedMethod(
                    TAG, "getAsString", "()Ljava/lang/String;"
                )?.access == "public"
        }
        else -> false
    }

    private fun addClickAuditHelpers(
        parser: JavaParser,
        owner: ClassOrInterfaceDeclaration
    ) {
        val wrapper = parser.parse(
            """
            final class ModporterClickAuditHelpers {
                private static final class $COMPONENT_NODE {
                    private final java.util.List<$COMPONENT_NODE> siblings =
                        new java.util.ArrayList<>();
                    private boolean click;
                }

                private static boolean $CLICK_HELPER(String json) {
                    String normalized = json.isEmpty() ? "\"\"" : json;
                    try {
                        com.google.gson.stream.JsonReader reader =
                            new com.google.gson.stream.JsonReader(
                                new java.io.StringReader(normalized));
                        reader.setLenient(false);
                        com.google.gson.JsonElement element =
                            new com.google.gson.Gson()
                                .getAdapter(com.google.gson.JsonElement.class)
                                .read(reader);
                        return $CLICK_HELPER(
                            $DECODE_HELPER(element, true));
                    } catch (java.io.IOException exception) {
                        throw new com.google.gson.JsonSyntaxException(exception);
                    }
                }

                private static boolean $CLICK_HELPER(
                        $COMPONENT_NODE component) {
                    for ($COMPONENT_NODE sibling : component.siblings)
                        if ($CLICK_HELPER(sibling))
                            return true;
                    return component.click;
                }

                private static void $TOUCH_HELPER(
                        $COMPONENT_NODE component) {
                    for ($COMPONENT_NODE sibling : component.siblings)
                        $TOUCH_HELPER(sibling);
                }

                private static $COMPONENT_NODE $DECODE_HELPER(
                        com.google.gson.JsonElement element,
                        boolean nullableNull) {
                    if (element == null || element.isJsonNull()) {
                        if (nullableNull)
                            return null;
                        throw new com.google.gson.JsonParseException(
                            "null is not a text component");
                    }
                    if (element.isJsonPrimitive())
                        return new $COMPONENT_NODE();
                    if (element.isJsonArray()) {
                        com.google.gson.JsonArray components =
                            element.getAsJsonArray();
                        $COMPONENT_NODE component = null;
                        for (com.google.gson.JsonElement child : components) {
                            $COMPONENT_NODE decoded =
                                $DECODE_HELPER(child, false);
                            if (component == null)
                                component = decoded;
                            else
                                component.siblings.add(decoded);
                        }
                        return component;
                    }
                    if (!element.isJsonObject())
                        throw new com.google.gson.JsonParseException(
                            "unsupported text component JSON");
                    com.google.gson.JsonObject object = element.getAsJsonObject();
                    $COMPONENT_NODE component = new $COMPONENT_NODE();
                    if (object.has("text")) {
                        $STRING_HELPER(object.get("text"), "text");
                    } else if (object.has("translate")) {
                        $STRING_HELPER(object.get("translate"), "translate");
                        $OPTIONAL_STRING_HELPER(object, "fallback");
                        if (object.has("with"))
                            for (com.google.gson.JsonElement argument :
                                    $ARRAY_HELPER(
                                        object.get("with"), "with"))
                                $DECODE_HELPER(argument, false);
                    } else if (object.has("score")) {
                        com.google.gson.JsonObject score = $OBJECT_HELPER(
                            object.get("score"), "score");
                        $STRING_HELPER(score.get("name"), "name");
                        $STRING_HELPER(
                            score.get("objective"), "objective");
                    } else if (object.has("selector")) {
                        if (object.has("separator"))
                            java.util.Objects.requireNonNull(
                                $DECODE_HELPER(
                                    object.get("separator"), false));
                        $STRING_HELPER(
                            object.get("selector"), "selector");
                    } else if (object.has("keybind")) {
                        $STRING_HELPER(
                            object.get("keybind"), "keybind");
                    } else if (object.has("nbt")) {
                        $STRING_HELPER(object.get("nbt"), "nbt");
                        if (object.has("separator"))
                            java.util.Objects.requireNonNull(
                                $DECODE_HELPER(
                                    object.get("separator"), false));
                        if (object.has("interpret"))
                            $BOOLEAN_HELPER(
                                object.get("interpret"), "interpret");
                        if (object.has("block"))
                            $STRING_HELPER(
                                object.get("block"), "block");
                        else if (object.has("entity"))
                            $STRING_HELPER(
                                object.get("entity"), "entity");
                        else if (object.has("storage"))
                            $RESOURCE_HELPER(
                                $STRING_HELPER(
                                    object.get("storage"), "storage"));
                        else
                            throw new com.google.gson.JsonParseException(
                                "missing nbt data source");
                    } else {
                        throw new com.google.gson.JsonParseException(
                            "missing text component contents");
                    }
                    if (object.has("extra")) {
                        com.google.gson.JsonArray siblings =
                            $ARRAY_HELPER(
                                object.get("extra"), "extra");
                        if (siblings.isEmpty())
                            throw new com.google.gson.JsonParseException(
                                "empty text component siblings");
                        for (com.google.gson.JsonElement sibling : siblings)
                            component.siblings.add(
                                $DECODE_HELPER(sibling, false));
                    }
                    component.click = $STYLE_HELPER(object);
                    return component;
                }

                private static com.google.gson.JsonObject $OBJECT_HELPER(
                        com.google.gson.JsonElement element, String name) {
                    if (element == null || !element.isJsonObject())
                        throw new com.google.gson.JsonSyntaxException(
                            name + " must be an object");
                    return element.getAsJsonObject();
                }

                private static com.google.gson.JsonArray $ARRAY_HELPER(
                        com.google.gson.JsonElement element, String name) {
                    if (element == null || !element.isJsonArray())
                        throw new com.google.gson.JsonSyntaxException(
                            name + " must be an array");
                    return element.getAsJsonArray();
                }

                private static String $STRING_HELPER(
                        com.google.gson.JsonElement element, String name) {
                    if (element == null || !element.isJsonPrimitive())
                        throw new com.google.gson.JsonSyntaxException(
                            name + " must be a primitive");
                    return element.getAsString();
                }

                private static String $OPTIONAL_STRING_HELPER(
                        com.google.gson.JsonObject object, String name) {
                    return object.has(name)
                        ? $STRING_HELPER(object.get(name), name)
                        : null;
                }

                private static boolean $BOOLEAN_HELPER(
                        com.google.gson.JsonElement element, String name) {
                    if (element == null || !element.isJsonPrimitive())
                        throw new com.google.gson.JsonSyntaxException(
                            name + " must be a primitive");
                    return element.getAsBoolean();
                }

                private static int $INT_HELPER(
                        com.google.gson.JsonElement element, String name) {
                    if (element == null || !element.isJsonPrimitive() ||
                            !element.getAsJsonPrimitive().isNumber())
                        throw new com.google.gson.JsonSyntaxException(
                            name + " must be a number");
                    return element.getAsInt();
                }

                private static void $RESOURCE_HELPER(String value) {
                    int separator = value.indexOf(':');
                    String namespace = "minecraft";
                    String path = value;
                    if (separator >= 0) {
                        path = value.substring(separator + 1);
                        if (separator >= 1)
                            namespace = value.substring(0, separator);
                    }
                    for (int index = 0; index < namespace.length(); index++) {
                        char character = namespace.charAt(index);
                        if (!(character == '_' || character == '-' ||
                                character == '.' ||
                                character >= 'a' && character <= 'z' ||
                                character >= '0' && character <= '9'))
                            throw new com.google.gson.JsonSyntaxException(
                                "invalid resource namespace: " + value);
                    }
                    for (int index = 0; index < path.length(); index++) {
                        char character = path.charAt(index);
                        if (!(character == '_' || character == '-' ||
                                character == '.' || character == '/' ||
                                character >= 'a' && character <= 'z' ||
                                character >= '0' && character <= '9'))
                            throw new com.google.gson.JsonSyntaxException(
                                "invalid resource path: " + value);
                    }
                }

                private static boolean $STYLE_HELPER(
                        com.google.gson.JsonObject object) {
                    String[] flags = {
                        "bold", "italic", "underlined",
                        "strikethrough", "obfuscated"
                    };
                    for (String flag : flags)
                        if (object.has(flag))
                            object.get(flag).getAsBoolean();
                    if (object.has("color"))
                        $STRING_HELPER(object.get("color"), "color");
                    $OPTIONAL_STRING_HELPER(object, "insertion");

                    boolean clickFound = false;
                    if (object.has("clickEvent")) {
                        com.google.gson.JsonObject click = $OBJECT_HELPER(
                            object.get("clickEvent"), "clickEvent");
                        String action =
                            $OPTIONAL_STRING_HELPER(click, "action");
                        String value =
                            $OPTIONAL_STRING_HELPER(click, "value");
                        if (action != null && value != null)
                            clickFound = switch (action) {
                                case "open_url", "run_command",
                                        "suggest_command", "change_page",
                                        "copy_to_clipboard" -> true;
                                default -> false;
                            };
                    }
                    if (object.has("hoverEvent"))
                        $HOVER_HELPER($OBJECT_HELPER(
                            object.get("hoverEvent"), "hoverEvent"));
                    if (object.has("font"))
                        $RESOURCE_HELPER(
                            $STRING_HELPER(object.get("font"), "font"));
                    return clickFound;
                }

                private static void $HOVER_HELPER(
                        com.google.gson.JsonObject hover) {
                    String action =
                        $OPTIONAL_STRING_HELPER(hover, "action");
                    if (!"show_text".equals(action) &&
                            !"show_item".equals(action) &&
                            !"show_entity".equals(action))
                        return;
                    com.google.gson.JsonElement contents =
                        hover.get("contents");
                    if (contents != null) {
                        $HOVER_CONTENTS_HELPER(action, contents);
                        return;
                    }
                    // Legacy value components are decoded first. Item SNBT
                    // errors and all legacy entity payload errors were caught.
                    $COMPONENT_NODE legacy =
                        $DECODE_HELPER(hover.get("value"), true);
                    if ("show_item".equals(action) && legacy != null)
                        $TOUCH_HELPER(legacy);
                }

                private static void $HOVER_CONTENTS_HELPER(
                        String action,
                        com.google.gson.JsonElement contents) {
                    if ("show_text".equals(action)) {
                        $DECODE_HELPER(contents, true);
                        return;
                    }
                    if ("show_item".equals(action)) {
                        if (contents.isJsonPrimitive()) {
                            $RESOURCE_HELPER(contents.getAsString());
                            return;
                        }
                        com.google.gson.JsonObject item =
                            $OBJECT_HELPER(contents, "item");
                        $RESOURCE_HELPER(
                            $STRING_HELPER(item.get("id"), "id"));
                        if (item.has("count"))
                            $INT_HELPER(item.get("count"), "count");
                        if (item.has("tag"))
                            $STRING_HELPER(item.get("tag"), "tag");
                        return;
                    }
                    if (!contents.isJsonObject())
                        return;
                    com.google.gson.JsonObject entity =
                        contents.getAsJsonObject();
                    $RESOURCE_HELPER(
                        $STRING_HELPER(entity.get("type"), "type"));
                    java.util.UUID.fromString(
                        $STRING_HELPER(entity.get("id"), "id"));
                    $DECODE_HELPER(entity.get("name"), true);
                }
            }
            """.trimIndent()
        ).result.orElseThrow().getClassByName("ModporterClickAuditHelpers")
            .orElseThrow()
        wrapper.members.forEach { owner.addMember(it.clone()) }
    }

    private fun uniqueName(source: String, base: String): String {
        var result = base
        while (Regex("""(?<![\w$])${Regex.escape(result)}(?![\w$])""")
                .containsMatchIn(source)
        ) {
            result += "_"
        }
        return result
    }

    private fun unwrap(expression: Expression): Expression = when (expression) {
        is com.github.javaparser.ast.expr.EnclosedExpr ->
            unwrap(expression.inner)
        is com.github.javaparser.ast.expr.CastExpr ->
            unwrap(expression.expression)
        else -> expression
    }

    private fun isMaterializedPotionContentsEffects(
        expression: Expression,
        parsed: Parsed
    ): Boolean {
        val collect = unwrap(expression) as? MethodCallExpr ?: return false
        if (collect.nameAsString != "collect" || collect.arguments.size != 1) {
            return false
        }
        val stream = collect.scope.orElse(null)
            ?.let(::unwrap) as? MethodCallExpr ?: return false
        if (stream.nameAsString != "stream" ||
            stream.arguments.size != 2 ||
            stream.arguments[1].toString() != "false" ||
            stream.scope.orElse(null)?.let {
                parsed.typing.isTypeName(
                    it.toString(), "java.util.stream.StreamSupport"
                )
            } != true
        ) {
            return false
        }
        val spliterator = unwrap(stream.arguments[0]) as? MethodCallExpr
            ?: return false
        if (spliterator.nameAsString != "spliterator" ||
            spliterator.arguments.isNotEmpty()
        ) {
            return false
        }
        val effects = spliterator.scope.orElse(null)
            ?.let(::unwrap) as? MethodCallExpr ?: return false
        if (!isPotionContentsEffects(effects, parsed)) return false
        val collector = unwrap(collect.arguments.single()) as? MethodCallExpr
            ?: return false
        if (collector.nameAsString != "toCollection" ||
            collector.arguments.size != 1 ||
            collector.scope.orElse(null)?.let {
                parsed.typing.isTypeName(
                    it.toString(), "java.util.stream.Collectors"
                )
            } != true
        ) {
            return false
        }
        val factory = unwrap(collector.arguments.single())
            as? com.github.javaparser.ast.expr.MethodReferenceExpr
            ?: return false
        return factory.identifier == "new" &&
            parsed.typing.isTypeName(
                factory.scope.toString(), "java.util.ArrayList"
            )
    }

    private fun targetContractsVerified(): Boolean {
        val cure = TargetMembers.inheritedMethod(
            LIVING_ENTITY,
            "removeEffectsCuredBy",
            "(Lnet/neoforged/neoforge/common/EffectCure;)Z"
        )
        val effects = TargetMembers.inheritedMethod(
            POTION_CONTENTS,
            "getAllEffects",
            "()Ljava/lang/Iterable;"
        )
        val fluidGet = TargetMembers.inheritedMethod(
            FLUID_STACK,
            "get",
            "(Lnet/minecraft/core/component/DataComponentType;)" +
                "Ljava/lang/Object;"
        )
        val fluidSet = TargetMembers.inheritedMethod(
            FLUID_STACK,
            "set",
            "(Lnet/minecraft/core/component/DataComponentType;" +
                "Ljava/lang/Object;)Ljava/lang/Object;"
        )
        val customDataOf = TargetMembers.inheritedMethod(
            "net.minecraft.world.item.component.CustomData",
            "of",
            "(Lnet/minecraft/nbt/CompoundTag;)" +
                "Lnet/minecraft/world/item/component/CustomData;"
        )
        val copyTag = TargetMembers.inheritedMethod(
            "net.minecraft.world.item.component.CustomData",
            "copyTag",
            "()Lnet/minecraft/nbt/CompoundTag;"
        )
        val removeTag = TargetMembers.inheritedMethod(
            "net.minecraft.nbt.CompoundTag",
            "remove",
            "(Ljava/lang/String;)V"
        )
        val stackGetOrDefault = TargetMembers.inheritedMethod(
            ITEM_STACK,
            "getOrDefault",
            "(Lnet/minecraft/core/component/DataComponentType;" +
                "Ljava/lang/Object;)Ljava/lang/Object;"
        )
        val withTooltip = TargetMembers.inheritedMethod(
            "net.minecraft.world.item.component.ItemAttributeModifiers",
            "withTooltip",
            "(Z)Lnet/minecraft/world/item/component/ItemAttributeModifiers;"
        )
        val stackHas = TargetMembers.inheritedMethod(
            ITEM_STACK,
            "has",
            "(Lnet/minecraft/core/component/DataComponentType;)Z"
        )
        return cure?.access == "public" &&
            effects?.owner == POTION_CONTENTS &&
            effects.access == "public" &&
            fluidGet?.access == "public" &&
            fluidSet?.owner == FLUID_STACK &&
            fluidSet.access == "public" &&
            customDataOf?.owner ==
                "net.minecraft.world.item.component.CustomData" &&
            customDataOf.access == "public" &&
            copyTag?.owner ==
                "net.minecraft.world.item.component.CustomData" &&
            copyTag.access == "public" &&
            removeTag?.owner == "net.minecraft.nbt.CompoundTag" &&
            removeTag.access == "public" &&
            stackGetOrDefault?.access == "public" &&
            withTooltip?.owner ==
                "net.minecraft.world.item.component.ItemAttributeModifiers" &&
            withTooltip.access == "public" &&
            stackHas?.access == "public" &&
            TargetMembers.fieldDescriptor(
                "net.neoforged.neoforge.common.EffectCures", "MILK"
            ) == "Lnet/neoforged/neoforge/common/EffectCure;" &&
            TargetMembers.fieldDescriptor(
                DATA_COMPONENTS, "CUSTOM_DATA"
            ) == "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor(
                DATA_COMPONENTS, "ATTRIBUTE_MODIFIERS"
            ) == "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor(
                DATA_COMPONENTS, "FOOD"
            ) == "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor(
                "net.minecraft.world.item.component.ItemAttributeModifiers",
                "EMPTY"
            ) == "Lnet/minecraft/world/item/component/ItemAttributeModifiers;"
    }
}
